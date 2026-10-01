package dev.periy.bridge.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import dev.periy.bridge.container
import dev.periy.bridge.server.SystemClipboard

/**
 * Sends the phone's latest copy to the laptop.
 *
 * Android lets an app read the clipboard only while it has the screen, so this is a tiny
 * invisible activity: it opens, reads, hands the text to the shared slot (which the laptop
 * helper puts straight into the Windows clipboard) and closes, all in a blink.
 */
class ClipSendActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || isFinishing) return
        // Opened by the background watch after a copy in another app: send it, say nothing.
        if (intent?.getBooleanExtra("quiet", false) == true) ClipSync.sendFromPhone(this)
        else Toast.makeText(this, ClipSync.sendFromPhone(this, always = true), Toast.LENGTH_SHORT).show()
        finish()
        overridePendingTransition(0, 0)
    }
}

/**
 * "Send to laptop" in the text-selection menu of any app. The selected words arrive as an
 * extra, so nothing needs the clipboard or the screen: they go straight to the shared clipboard
 * (and the laptop's), and onto the phone's own clipboard too.
 */
class ClipTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        val msg = when {
            text.isEmpty() -> "Nothing selected"
            container.clipboard.set(text) -> { SystemClipboard.write(this, text); "Sent to the laptop" }
            else -> "Too long to send"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
        overridePendingTransition(0, 0)
    }
}

/**
 * "Copy to laptop" in the share sheet of any app: a picture or file (under 50 MB) shared from
 * Gallery, Files, WhatsApp... goes onto the shared clipboard, and from there onto the laptop's,
 * ready to paste; shared text likewise. The reliable way off the phone where the system no
 * longer announces copies to an app in the background (HyperOS 3, Android 16). It stays open,
 * unseen, until the file is read, as the share's permission to read it ends with it.
 */
class ClipShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        val i = intent
        val uri: android.net.Uri? = if (Build.VERSION.SDK_INT >= 33) i?.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        else @Suppress("DEPRECATION") i?.getParcelableExtra(Intent.EXTRA_STREAM)
        val text = i?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (uri == null) {
            val msg = when {
                text.isNullOrEmpty() -> "Nothing to copy"
                container.clipboard.set(text) -> { SystemClipboard.write(this, text); "Copied to the laptop" }
                else -> "Too long to copy"
            }
            done(msg)
            return
        }
        val app = applicationContext
        val mime = contentResolver.getType(uri) ?: i?.type?.takeIf { '*' !in it } ?: "application/octet-stream"
        val name = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: if (mime.startsWith("image/")) "Picture" else "File"
        Thread {
            val meta = runCatching { contentResolver.openInputStream(uri)?.use { app.container.clipboard.setBlob(name, mime, it) } }.getOrNull()
            runOnUiThread {
                done(when {
                    meta == null -> "Too big for the clipboard (over 50 MB); send it as a file"
                    mime.startsWith("image/") -> "Picture copied to the laptop"
                    else -> "File copied to the laptop"
                })
            }
        }.start()
    }

    private fun done(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
        overridePendingTransition(0, 0)
    }
}

/** The quick-settings tile: one tap from the shade sends what you just copied. */
class ClipTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Send clipboard"
            if (Build.VERSION.SDK_INT >= 29) subtitle = "To the laptop"
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, ClipSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}

object ClipSync {
    /** The last picture or file sent from the phone's clipboard, so reopening Localhost 8787 does not send it again. */
    @Volatile private var lastUri: String? = null

    /**
     * Reads the phone's clipboard (the caller must have the screen) and publishes it when it
     * is new. [always] is for an explicit tap, which sends even with automatic sync off.
     * Returns a short line for a toast.
     */
    fun sendFromPhone(ctx: Context, always: Boolean = false): String {
        val c = ctx.container
        if (!always && !c.prefs.clipSync) return ""
        // A picture or file copied on the phone (a photo in Gallery, a PDF in Files...).
        SystemClipboard.readFile(ctx)?.let { copied ->
            // Our own copy, put there from the laptop: it is already the shared one.
            if (copied.uri.authority == ctx.packageName + ".clip") return "Already on the laptop"
            if (copied.uri.toString() == lastUri && c.clipboard.blob() != null) return "Already on the laptop"
            lastUri = copied.uri.toString()
            // Reading the clipboard needs the screen; copying its bytes does not, and a big
            // picture must not freeze the app while it is read.
            val app = ctx.applicationContext
            Thread {
                val meta = runCatching {
                    app.contentResolver.openInputStream(copied.uri)?.use { c.clipboard.setBlob(copied.name, copied.mime, it) }
                }.getOrNull()
                if (meta == null) {
                    lastUri = null
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(app, "Too big for the clipboard (over 50 MB); send it as a file", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
            return if (copied.mime.startsWith("image/")) "Picture sent to the laptop" else "File sent to the laptop"
        }
        val text = SystemClipboard.read(ctx)
        if (text.isNullOrEmpty()) return "Nothing copied on the phone"
        if (text == c.clipboard.text) return "Already on the laptop"
        return if (c.clipboard.set(text)) "Sent to the laptop" else "Too long to send"
    }
}
