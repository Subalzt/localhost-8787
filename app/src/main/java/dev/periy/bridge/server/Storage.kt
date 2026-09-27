package dev.periy.bridge.server

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dev.periy.bridge.util.Prefs
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

private const val TAG = "BridgeStorage"

/**
 * Socket -> disk. 512 KB rather than something larger because the limit here is the
 * kernel socket buffer, not our array: past a few hundred KB a bigger read just returns
 * short. Larger buffers cost memory per concurrent stream and buy nothing.
 */
const val UPLOAD_BUFFER = 512 * 1024

/**
 * Disk -> socket. Larger than the upload buffer because this side *is* limited by our
 * own read size: each read is a syscall into the page cache, and 1 MB reads keep the
 * readahead window usefully full during a sequential scan of a multi-gigabyte file.
 */
const val DOWNLOAD_BUFFER = 1024 * 1024

/** Disk -> disk. Only used on the staged-copy path. */
private const val COPY_BUFFER = 1024 * 1024

/**
 * A file being written at an arbitrary offset, with an explicit durability point.
 *
 * `sync()` is the contract that makes resume correct: TusStore only advances the
 * committed offset it will report to a client *after* sync() returns. Anything written
 * but not synced is simply re-sent on resume, which costs a couple of seconds of
 * transfer and is always safe. The inverse -- reporting an offset that a power cut can
 * roll back -- would silently corrupt a 10 GB file with no error anywhere.
 *
 * `sync()` is `fdatasync`, not `fsync`. Combined with the preallocation done by
 * [Storage.Slot.preallocate], the file's size never changes during a transfer, so there
 * is no inode metadata worth flushing and the cheaper call is also the correct one.
 */
interface SeekableWriter : Closeable {
    fun write(buf: ByteArray, off: Int, len: Int)
    fun sync()
}

/**
 * Shared writer over a raw file descriptor, used by both storage paths.
 *
 * Writes go through FileOutputStream, which for a descriptor-backed stream is a thin
 * wrapper over the `write` syscall with no buffering of its own -- exactly what is
 * wanted, since the caller already batches into 512 KB blocks and any extra layer would
 * just add a copy.
 */
private class FdWriter(
    private val fd: java.io.FileDescriptor,
    offset: Long,
    private val onClose: () -> Unit,
) : SeekableWriter {

    private val out = FileOutputStream(fd)

    init {
        Os.lseek(fd, offset, OsConstants.SEEK_SET)
    }

    override fun write(buf: ByteArray, off: Int, len: Int) = out.write(buf, off, len)

    /**
     * Makes what was written durable. A failure here (EIO, ENOSPC) must not pass as success:
     * the upload records its resume point only after a sync, so a swallowed error would let a
     * resume skip bytes that never reached the disk. Thrown, it ends the request as a pause at
     * the last point that did. Only a descriptor that cannot be synced at all is let through.
     */
    override fun sync() {
        try {
            Os.fdatasync(fd)
        } catch (e: android.system.ErrnoException) {
            if (e.errno != OsConstants.EINVAL && e.errno != OsConstants.ENOSYS) throw e
        }
    }

    override fun close() {
        runCatching { Os.fdatasync(fd) }
        onClose()
    }
}

/**
 * Reserves the whole file up front.
 *
 * This matters more than it looks. Growing a 10 GB file 512 KB at a time means the
 * filesystem allocates and journals metadata thousands of times along the way, and the
 * resulting file is fragmented across the device. One `fallocate` claims the extents in
 * a single metadata operation, which:
 *
 *  - takes the allocator off the write path entirely,
 *  - keeps the file contiguous, which matters for the read-back speed later,
 *  - fixes the file size before the first byte arrives, so `fdatasync` has no metadata
 *    left to flush and becomes dramatically cheaper than `fsync`,
 *  - and fails immediately if the space is not there, instead of at 95%.
 *
 * Not every filesystem or FUSE layer implements it. Failure is logged and ignored;
 * everything still works, just with the old costs.
 */
private fun preallocateFd(fd: java.io.FileDescriptor, length: Long): Boolean = runCatching {
    if (length <= 0) return false
    Os.posix_fallocate(fd, 0, length)
    true
}.getOrElse {
    Log.i(TAG, "posix_fallocate not available here (${it.message}); writing without preallocation")
    false
}

/**
 * Storage for received files.
 *
 * ## Why there are two paths
 *
 * tus needs to write at a byte offset. A SAF document is not a File, so the obvious
 * `RandomAccessFile` is unavailable; what SAF gives you is a ParcelFileDescriptor, and
 * whether that descriptor is *seekable* depends entirely on which DocumentsProvider is
 * behind it. The device's own storage provider hands back a real file descriptor and
 * `lseek` works. A cloud provider hands back a pipe and `lseek` fails with ESPIPE.
 * There is no API that tells you which you have, so this class finds out by trying it
 * once, at the moment the folder is chosen.
 *
 * - **DIRECT_SEEK** -- write the incoming bytes straight into a hidden `.bridge-incomplete`
 *   folder inside the destination, then finish with a SAF rename+move. A move within one
 *   volume is a metadata operation, so a completed 10 GB upload costs nothing extra.
 * - **STAGED_COPY** -- write into app-private storage where `RandomAccessFile` always
 *   works, then stream the finished file into the destination. Always correct, but pays
 *   a full read+write pass at the end and needs the space twice over.
 *
 * Staging lives inside the destination folder rather than in the app's own directory on
 * the fast path, precisely so that finishing is a rename and not a copy. Hiding it in a
 * dot-folder keeps partial files out of the user's way, and out of the media scanner's.
 */
class Storage(private val ctx: Context, private val prefs: Prefs) {

    enum class Mode { NO_DESTINATION, DIRECT_SEEK, STAGED_COPY }

    private val resolver get() = ctx.contentResolver

    @Volatile
    var mode: Mode = Mode.NO_DESTINATION
        private set

    @Volatile
    var destinationLabel: String? = null
        private set

    private val stagingDir: File
        // filesDir, not cacheDir: the system is free to reap cacheDir under storage
        // pressure, and a 90-minute upload gives it plenty of chances to do so.
        get() = File(ctx.filesDir, "incomplete").apply { mkdirs() }

    // ---------------------------------------------------------------- destination

    fun hasDestination(): Boolean = prefs.treeUri != null

    private fun treeDocUri(): Uri? {
        val tree = prefs.treeUri ?: return null
        return runCatching {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        }.getOrNull()
    }

    /**
     * Re-evaluates the destination and picks a mode. Safe to call repeatedly; it does
     * real I/O, so call it off the main thread. One at a time: the server and the screen
     * both call it when they start, and two probes at once got in each other's way.
     */
    @Synchronized
    fun refresh() {
        val tree = prefs.treeUri
        if (tree == null) {
            mode = Mode.NO_DESTINATION
            destinationLabel = null
            return
        }
        val persisted = resolver.persistedUriPermissions.any {
            it.uri == tree && it.isWritePermission
        }
        if (!persisted) {
            Log.w(TAG, "Lost persisted write permission for $tree")
            mode = Mode.NO_DESTINATION
            destinationLabel = null
            prefs.treeUri = null
            return
        }
        destinationLabel = displayPath(tree)
        mode = when {
            prefs.forceStagedCopy -> Mode.STAGED_COPY
            probeSeekable() -> Mode.DIRECT_SEEK
            else -> Mode.STAGED_COPY
        }
        Log.i(TAG, "Storage mode = $mode, destination = $destinationLabel")
    }

    /**
     * Creates a throwaway document, writes to it out of order, reads it back, deletes it.
     * The read-back matters: a provider can accept an lseek and then quietly ignore the
     * offset, which would look like success here and produce a shredded file at 9 GB.
     *
     * Each probe has a name of its own, and any a past probe left behind are cleared first.
     * With one fixed name, every leftover took a numbered copy ("(1).bridge-seek-probe"...);
     * after 32 of them the provider could make no new name, every probe failed, and every
     * upload fell back to the staged copy for no reason.
     */
    private fun probeSeekable(): Boolean {
        val parent = treeDocUri() ?: return false
        clearProbes(parent)
        var probe: Uri? = null
        return try {
            probe = DocumentsContract.createDocument(
                resolver, parent, "application/octet-stream", PROBE_NAME + "-" + System.nanoTime()
            ) ?: return false

            resolver.openFileDescriptor(probe, "rw").use { pfd ->
                val fd = pfd!!.fileDescriptor
                val out = FileOutputStream(fd)
                out.write(ByteArray(16))
                Os.fsync(fd)
                Os.lseek(fd, 8, OsConstants.SEEK_SET)
                out.write(MARKER)
                Os.fsync(fd)
            }
            resolver.openFileDescriptor(probe, "r").use { pfd ->
                val fd = pfd!!.fileDescriptor
                Os.lseek(fd, 8, OsConstants.SEEK_SET)
                val back = ByteArray(MARKER.size)
                val ins = FileInputStream(fd)
                var n = 0
                while (n < back.size) {
                    val r = ins.read(back, n, back.size - n)
                    if (r <= 0) break
                    n += r
                }
                n == back.size && back.contentEquals(MARKER)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Seek probe failed, falling back to staged copy: ${t.message}")
            false
        } finally {
            probe?.let { p ->
                runCatching { DocumentsContract.deleteDocument(resolver, p) }
                    .onFailure { Log.w(TAG, "Could not delete the seek probe: ${it.message}") }
            }
        }
    }

    /** Deletes seek probes left in the destination by earlier probes. */
    private fun clearProbes(parent: Uri) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val stale = mutableListOf<Uri>()
        runCatching {
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1)?.contains(PROBE_NAME) == true) stale += DocumentsContract.buildDocumentUriUsingTree(parent, c.getString(0))
                }
            }
        }
        var cleared = 0
        stale.forEach { if (runCatching { DocumentsContract.deleteDocument(resolver, it) }.getOrDefault(false)) cleared++ }
        if (stale.isNotEmpty()) Log.i(TAG, "Cleared $cleared of ${stale.size} old seek probes")
    }

    // ---------------------------------------------------------------- slots

    /**
     * A partially-written file. `ref` is an opaque string persisted alongside the tus
     * upload state so the slot can be reopened after the process is killed mid-transfer.
     */
    interface Slot {
        val ref: String
        fun writer(offset: Long): SeekableWriter

        /**
         * Claims `length` bytes on disk before the transfer starts. Called once per
         * upload, never per request. Returns false if the filesystem would not do it.
         */
        fun preallocate(length: Long): Boolean
        fun currentLength(): Long
        fun discard()
        /** Completes the file as [name] inside [folder] (path segments under the destination). */
        fun finish(name: String, mime: String, origin: Origin, folder: List<String>): FileEntry
    }

    fun newSlot(id: String): Slot {
        if (mode != Mode.DIRECT_SEEK) return StagedSlot(File(stagingDir, "$id.part"))
        val dir = findOrCreateIncompleteDir() ?: return StagedSlot(File(stagingDir, "$id.part"))
        val doc = runCatching {
            DocumentsContract.createDocument(resolver, dir, "application/octet-stream", "$id.part")
        }.getOrNull() ?: return StagedSlot(File(stagingDir, "$id.part"))
        return DirectSlot(doc, dir)
    }

    fun slotFromRef(ref: String): Slot? = when {
        ref.startsWith("doc:") -> {
            val parts = ref.removePrefix("doc:").split(' ')
            if (parts.size == 2) DirectSlot(Uri.parse(parts[0]), Uri.parse(parts[1])) else null
        }
        ref.startsWith("file:") -> StagedSlot(File(ref.removePrefix("file:")))
        else -> null
    }

    private fun findOrCreateIncompleteDir(): Uri? {
        val parent = treeDocUri() ?: return null
        childUri(parent, INCOMPLETE_DIR)?.let { return it }
        return runCatching {
            DocumentsContract.createDocument(
                resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, INCOMPLETE_DIR
            )
        }.getOrNull()
    }

    // ---------------------------------------------------------------- direct path

    private inner class DirectSlot(val doc: Uri, val parent: Uri) : Slot {
        override val ref = "doc:$doc $parent"

        /**
         * Each parallel stream gets its own descriptor, so each has its own independent
         * file position and they can write to disjoint regions of the same file without
         * any locking between them.
         */
        override fun writer(offset: Long): SeekableWriter {
            val pfd = resolver.openFileDescriptor(doc, "rw")
                ?: error("Cannot open $doc for writing")
            // Only the PFD is closed on the way out. FileOutputStream(FileDescriptor)
            // shares the descriptor, so closing both would be a double close.
            return FdWriter(pfd.fileDescriptor, offset) { pfd.close() }
        }

        override fun preallocate(length: Long): Boolean {
            val pfd = resolver.openFileDescriptor(doc, "rw") ?: return false
            return pfd.use { preallocateFd(it.fileDescriptor, length) }
        }

        override fun currentLength(): Long = docSize(doc)

        override fun discard() {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
        }

        override fun finish(name: String, mime: String, origin: Origin, folder: List<String>): FileEntry {
            val root = folderFor(folder)
            val target = uniqueName(root, name)

            // Rename inside the hidden folder first: if the move then fails we are left
            // with a correctly-named complete file rather than an anonymous .part.
            var uri = runCatching {
                DocumentsContract.renameDocument(resolver, doc, target)
            }.getOrNull() ?: doc

            uri = runCatching {
                DocumentsContract.moveDocument(resolver, uri, parent, root)
            }.getOrNull() ?: run {
                Log.w(TAG, "moveDocument unsupported here; copying out of the staging folder")
                val copied = copyDocInto(uri, root, target, mime)
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                copied
            }

            return register(uri, target, mime, docSize(uri), origin)
        }
    }

    // ---------------------------------------------------------------- staged path

    private inner class StagedSlot(val part: File) : Slot {
        override val ref = "file:${part.absolutePath}"

        override fun writer(offset: Long): SeekableWriter {
            part.parentFile?.mkdirs()
            val raf = RandomAccessFile(part, "rw")
            return FdWriter(raf.fd, offset) { raf.close() }
        }

        override fun preallocate(length: Long): Boolean {
            part.parentFile?.mkdirs()
            return RandomAccessFile(part, "rw").use { preallocateFd(it.fd, length) }
        }

        override fun currentLength(): Long = if (part.exists()) part.length() else 0L

        override fun discard() {
            part.delete()
        }

        override fun finish(name: String, mime: String, origin: Origin, folder: List<String>): FileEntry {
            val root = folderFor(folder)
            val target = uniqueName(root, name)
            val dest = DocumentsContract.createDocument(resolver, root, mime, target)
                ?: error("Could not create $target in the destination folder")

            // Streamed, never buffered whole -- this is a 10 GB file.
            FileInputStream(part).use { ins ->
                resolver.openOutputStream(dest, "wt").use { out ->
                    requireNotNull(out) { "Destination refused a write stream" }
                    val buf = ByteArray(COPY_BUFFER)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    out.flush()
                }
            }
            part.delete()
            return register(dest, target, mime, docSize(dest), origin)
        }
    }

    // ---------------------------------------------------------------- folders

    private val folderLock = Any()
    /** Folders already found or made, by path, for the destination they belong to. */
    private val folderCache = HashMap<String, Uri>()

    /**
     * The folder a received file belongs in: the destination itself, or a path of
     * subfolders under it (from an uploaded folder), found or created one level at a time.
     *
     * Serialised, because the files of one folder finish several at a time: two of them
     * both finding "Photos" missing would create "Photos" and "Photos (1)".
     */
    private fun folderFor(folder: List<String>): Uri {
        val root = treeDocUri() ?: error("Destination folder is gone")
        if (folder.isEmpty()) return root
        synchronized(folderLock) {
            val key = root.toString() + "|" + folder.joinToString("/")
            folderCache[key]?.let { return it }
            val made = runCatching { walkFolders(root, folder) }.getOrElse {
                // A folder in the cache may have been deleted meanwhile; look again from the top.
                folderCache.clear()
                walkFolders(root, folder)
            }
            folderCache[key] = made
            return made
        }
    }

    private fun walkFolders(root: Uri, folder: List<String>): Uri {
        var dir = root
        for (name in folder) {
            dir = childFolderUri(dir, name)
                ?: DocumentsContract.createDocument(resolver, dir, DocumentsContract.Document.MIME_TYPE_DIR, name)
                ?: error("Could not create the folder $name")
        }
        return dir
    }

    /** A child folder by name. Unlike [childUri], a file with the same name does not count. */
    private fun childFolderUri(parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            parent, DocumentsContract.getDocumentId(parent)
        )
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, c.getString(0))
                }
            }
        }
        return null
    }

    // ---------------------------------------------------------------- shared helpers

    private fun copyDocInto(src: Uri, parent: Uri, name: String, mime: String): Uri {
        val dest = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: error("Could not create $name")
        resolver.openInputStream(src).use { ins ->
            resolver.openOutputStream(dest, "wt").use { out ->
                requireNotNull(ins)
                requireNotNull(out)
                val buf = ByteArray(COPY_BUFFER)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                out.flush()
            }
        }
        return dest
    }

    private fun register(uri: Uri, name: String, mime: String, size: Long, origin: Origin): FileEntry {
        val entry = FileEntry(
            id = UUID.randomUUID().toString(),
            name = name,
            mime = mime,
            size = size,
            addedAt = System.currentTimeMillis(),
            uri = uri.toString(),
            origin = origin.name,
            scanPath = filesystemPath(uri),
            owned = true,
        )
        indexInGallery(entry)
        return entry
    }

    /**
     * Puts received photos and videos in the gallery.
     *
     * The obvious implementation -- insert into MediaStore and copy the bytes in -- would
     * write a second full copy of every video that crosses the bridge. Instead, when the
     * destination resolves to a real path (true for anything on the device's own storage),
     * this hands that path to the media scanner, which indexes the file in place. That is
     * what a file manager does, and it costs one IPC instead of five gigabytes.
     *
     * The copy is kept only as a fallback for providers with no filesystem path at all,
     * and only below a size where the duplication is defensible.
     */
    private fun indexInGallery(entry: FileEntry) {
        val isMedia = entry.mime.startsWith("image/") || entry.mime.startsWith("video/")
        if (!isMedia) return

        val path = entry.scanPath
        if (path != null) {
            MediaScannerConnection.scanFile(ctx, arrayOf(path), arrayOf(entry.mime), null)
            return
        }
        if (entry.size > MEDIASTORE_COPY_LIMIT) {
            Log.w(TAG, "No filesystem path for ${entry.name} and too large to duplicate; not indexing")
            return
        }
        runCatching {
            val collection = if (entry.mime.startsWith("image/")) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
                put(MediaStore.MediaColumns.MIME_TYPE, entry.mime)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val dest = resolver.insert(collection, values) ?: return@runCatching
            resolver.openInputStream(Uri.parse(entry.uri)).use { ins ->
                resolver.openOutputStream(dest).use { out ->
                    requireNotNull(ins)
                    requireNotNull(out)
                    val buf = ByteArray(COPY_BUFFER)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            resolver.update(
                dest,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null,
            )
        }.onFailure { Log.w(TAG, "MediaStore fallback failed for ${entry.name}: ${it.message}") }
    }

    /**
     * Best-effort real path for a tree document, so the media scanner has something to
     * chew on. Never used to open the file -- on API 29+ we have no permission to do
     * that, and would not want to.
     */
    private fun filesystemPath(uri: Uri): String? = runCatching {
        val docId = DocumentsContract.getDocumentId(uri)
        val colon = docId.indexOf(':')
        if (colon <= 0) return@runCatching null
        val volume = docId.substring(0, colon)
        val rel = docId.substring(colon + 1)
        val root = if (volume.equals("primary", ignoreCase = true)) {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        "$root/$rel"
    }.getOrNull()

    private fun displayPath(tree: Uri): String = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val colon = docId.indexOf(':')
        if (colon < 0) return@runCatching docId
        val vol = docId.substring(0, colon)
        val rel = docId.substring(colon + 1).ifEmpty { "(root)" }
        if (vol.equals("primary", true)) "Internal storage/$rel" else "$vol/$rel"
    }.getOrDefault(tree.toString())

    private fun childUri(parent: Uri, displayName: String): Uri? = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            parent, DocumentsContract.getDocumentId(parent)
        )
        var found: Uri? = null
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == displayName) {
                    found = DocumentsContract.buildDocumentUriUsingTree(parent, c.getString(0))
                    break
                }
            }
        }
        found
    }.getOrNull()

    /** SAF auto-dedupes on create but not on rename, so do it ourselves for both paths. */
    private fun uniqueName(parent: Uri, name: String): String {
        val existing = runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                parent, DocumentsContract.getDocumentId(parent)
            )
            buildSet {
                resolver.query(
                    children,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null,
                )?.use { c -> while (c.moveToNext()) add(c.getString(0)) }
            }
        }.getOrDefault(emptySet())

        if (name !in existing) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while ("$stem ($n)$ext" in existing) n++
        return "$stem ($n)$ext"
    }

    fun docSize(uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /**
     * Null projection rather than asking for a document id: entries now also come from
     * the gallery and other providers, whose URIs have no such column and would look
     * deleted the moment they were listed.
     */
    fun exists(entry: FileEntry): Boolean = runCatching {
        resolver.query(Uri.parse(entry.uri), null, null, null, null)?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    // ---------------------------------------------------------------- phone to PC

    /** Display name, size and MIME type for any content URI. */
    fun queryMeta(uri: Uri): Triple<String, Long, String> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = 0L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return Triple(sanitizeFilename(name), size, mime)
    }

    /**
     * Publishes a file the user picked, without copying a single byte.
     *
     * Only safe for URIs whose read permission we can hold onto -- which the system
     * document picker grants and almost nothing else does. `owned = false` marks it as
     * the user's own file, so deleting it from the list never deletes the original.
     */
    fun adopt(uri: Uri): FileEntry {
        val (name, size, mime) = queryMeta(uri)
        return FileEntry(
            id = UUID.randomUUID().toString(),
            name = name,
            mime = mime,
            size = if (size > 0) size else docSize(uri),
            addedAt = System.currentTimeMillis(),
            uri = uri.toString(),
            origin = Origin.PHONE.name,
            scanPath = null,
            owned = false,
        )
    }

    /**
     * Copies a shared file into the destination folder and publishes that copy.
     *
     * Needed because a URI arriving through the share sheet carries a grant that dies
     * with the task. Serving it later would fail silently once Android revoked it, so
     * the bytes have to be taken while the grant is still alive. Streamed, never
     * buffered, and reported through [Transfers] because a shared video can be large
     * enough that a silent pause would look like a hang.
     */
    fun importCopy(uri: Uri, transferId: String): FileEntry {
        val root = treeDocUri() ?: error("No destination folder has been chosen")
        val (name, size, mime) = queryMeta(uri)
        val target = uniqueName(root, name)
        val dest = DocumentsContract.createDocument(resolver, root, mime, target)
            ?: error("Could not create $target in the destination folder")

        Transfers.begin(transferId, target, Direction.INBOUND, size)
        var copied = 0L
        try {
            resolver.openInputStream(uri).use { ins ->
                resolver.openOutputStream(dest, "wt").use { out ->
                    requireNotNull(ins) { "Cannot read the shared file" }
                    requireNotNull(out) { "Destination refused a write stream" }
                    val buf = ByteArray(COPY_BUFFER)
                    var lastAt = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        copied += n
                        val now = System.currentTimeMillis()
                        if (now - lastAt >= 300) {
                            Transfers.progress(transferId, copied)
                            lastAt = now
                        }
                    }
                    out.flush()
                }
            }
        } catch (t: Throwable) {
            Transfers.finish(transferId, ok = false)
            runCatching { DocumentsContract.deleteDocument(resolver, dest) }
            throw t
        }
        Transfers.finish(transferId, ok = true)
        return register(dest, target, mime, docSize(dest), Origin.PHONE)
    }

    fun delete(entry: FileEntry): Boolean = runCatching {
        DocumentsContract.deleteDocument(resolver, Uri.parse(entry.uri))
    }.getOrDefault(false)

    /**
     * Free space on the volume uploads will land on, or -1 when it cannot be determined.
     *
     * Checked before a transfer starts rather than discovered at 95% of 10 GB. On the
     * staged path the bytes pass through app-private storage first, so that volume is
     * the one that matters; on the direct path it is the destination's own volume.
     */
    fun freeSpaceBytes(): Long = runCatching {
        val path = if (mode == Mode.DIRECT_SEEK) treeFsPath() ?: ctx.filesDir.absolutePath
        else ctx.filesDir.absolutePath
        StatFs(path).availableBytes
    }.getOrDefault(-1L)

    private fun treeFsPath(): String? = runCatching {
        val tree = prefs.treeUri ?: return@runCatching null
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val colon = docId.indexOf(':')
        if (colon <= 0) return@runCatching null
        val volume = docId.substring(0, colon)
        val rel = docId.substring(colon + 1)
        val root = if (volume.equals("primary", ignoreCase = true)) {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        val candidate = if (rel.isEmpty()) root else "$root/$rel"
        if (File(candidate).exists()) candidate else root
    }.getOrNull()

    /** Frees staging space left behind by uploads that were abandoned outright. */
    fun sweepStaging(activeRefs: Set<String>) {
        stagingDir.listFiles()?.forEach { f ->
            if ("file:${f.absolutePath}" !in activeRefs) f.delete()
        }
        if (mode != Mode.DIRECT_SEEK) return
        val dir = findOrCreateIncompleteDir() ?: return
        runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                dir, DocumentsContract.getDocumentId(dir)
            )
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val child = DocumentsContract.buildDocumentUriUsingTree(dir, c.getString(0))
                    if (activeRefs.none { it.startsWith("doc:$child") }) {
                        DocumentsContract.deleteDocument(resolver, child)
                    }
                }
            }
        }
    }

    private companion object {
        const val INCOMPLETE_DIR = ".bridge-incomplete"
        const val MEDIASTORE_COPY_LIMIT = 256L * 1024 * 1024
        /** The seek probe's name; each one adds a number of its own. */
        private const val PROBE_NAME = ".bridge-seek-probe"
        val MARKER = byteArrayOf(0x41, 0x42, 0x43, 0x44)
    }
}
