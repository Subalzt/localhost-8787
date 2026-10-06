package dev.periy.bridge.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.TileService
import android.widget.Toast
import dev.periy.bridge.server.Control
import dev.periy.bridge.server.Handoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handoff without a screen of its own. Shared a link ("Open on laptop" in the share sheet): it
 * opens in the laptop's browser, a YouTube video playing here at the same second. Opened plainly
 * (the Handoff tile, Devices): what the laptop is showing or playing carries on here, paused there.
 */
class HandoffActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        val app = applicationContext
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            val link = Regex("""https?://\S+""").find(text)?.value
            done(when {
                link == null -> "No link in that to open on the laptop"
                else -> Handoff.toLaptop(app, link)?.let { "Opening on $it" } ?: "The laptop helper is not running"
            })
            return
        }
        val laptop = Control.laptops().firstOrNull()
        if (laptop == null) { done("The laptop helper is not running"); return }
        Toast.makeText(app, "Asking ${laptop.second}...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.Main).launch {
            val d = Handoff.pull(laptop.first)
            done(Handoff.open(app, d))
        }
    }

    private fun done(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        finish()
        overridePendingTransition(0, 0)
    }
}

/** The Handoff tile: whatever the laptop is showing or playing, carried on here. */
class HandoffTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val i = Intent(this, HandoffActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 8801, i, android.app.PendingIntent.FLAG_IMMUTABLE))
        } else @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") startActivityAndCollapse(i)
    }
}
