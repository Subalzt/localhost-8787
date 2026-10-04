package dev.periy.bridge.server

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One thing in a folder on the laptop: [path] is what to ask for to open or save it. */
@Serializable
data class LaptopEntry(val name: String, val path: String, val dir: Boolean, val size: Long = 0, val modified: Long = 0)

/** A folder on the laptop as its helper read it; [path] empty is the top: its usual folders and drives. */
@Serializable
data class LaptopListing(val path: String, val entries: List<LaptopEntry> = emptyList(), val error: String = "", val laptop: String = "")

/**
 * The laptop's files on the phone, from anywhere: the phone asks through the event stream its
 * helper keeps open ("laptopfs list|get ID PATH"), and the helper answers on the page's port
 * (`/api/laptop/fs/answer`, `/api/laptop/fs/file`), the way it reaches the phone for everything
 * else, the tunnel included. Nothing on the laptop listens; read only.
 */
object LaptopFiles {
    private val listings = ConcurrentHashMap<String, CompletableDeferred<LaptopListing>>()
    private val saves = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

    /** The folder at [path] on the laptop (the top when empty). */
    suspend fun list(path: String): LaptopListing {
        if (Control.connected.value.isEmpty()) return LaptopListing(path, error = "The laptop helper is not running")
        val id = UUID.randomUUID().toString()
        val wait = CompletableDeferred<LaptopListing>()
        listings[id] = wait
        EventBus.emit("laptopfs", "list $id ${b64(path)}")
        return try {
            withTimeoutOrNull(20_000) { wait.await() } ?: LaptopListing(path, error = "The laptop did not answer")
        } finally {
            listings.remove(id)
        }
    }

    fun answer(id: String, listing: LaptopListing) { listings.remove(id)?.complete(listing) }

    /** Saves the file at [path] on the laptop into this phone's folder for received files: its name there, or why not. */
    suspend fun save(path: String): Result<String> {
        if (Control.connected.value.isEmpty()) return Result.failure(IllegalStateException("The laptop helper is not running"))
        val id = UUID.randomUUID().toString()
        val wait = CompletableDeferred<String>()
        saves[id] = wait
        EventBus.emit("laptopfs", "get $id ${b64(path)}")
        return try {
            // A big file over a thin link takes long; the helper says at once if it cannot send it.
            val r = withTimeoutOrNull(6 * 60 * 60_000L) { wait.await() } ?: return Result.failure(IllegalStateException("It took too long"))
            if (r.startsWith("!")) Result.failure(IllegalStateException(r.drop(1))) else Result.success(r)
        } finally {
            saves.remove(id)
        }
    }

    /** The helper's word on a save: the name it was saved as, or "!" and why it was not. */
    fun saved(id: String, result: String) { saves.remove(id)?.complete(result) }
}
