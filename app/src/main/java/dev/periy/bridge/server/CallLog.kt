package dev.periy.bridge.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.periy.bridge.R
import dev.periy.bridge.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One call, as the list of recent calls shows it. */
@Serializable
data class CallRecord(
    val id: String,
    val names: List<String>,
    val outgoing: Boolean,
    val video: Boolean,
    /** When it started ringing. */
    val at: Long,
    val durationMs: Long,
    /** answered, missed, declined, no answer, busy, cancelled, failed */
    val outcome: String,
    /** Missed and not yet looked at. */
    val fresh: Boolean = false,
)

/**
 * The calls this phone has made and had, newest first, the last [KEEP] of them, kept on the
 * phone: who, which way, voice or video, how long, and how it ended. A missed call says so in a
 * notification that opens the conversation with that phone.
 */
class CallLog(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("call-log", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(CallRecord.serializer())

    private val _calls = MutableStateFlow(
        prefs.getString(K, null)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyList(),
    )
    val calls: StateFlow<List<CallRecord>> = _calls

    fun add(r: CallRecord) {
        val next = (listOf(r) + _calls.value.filterNot { it.id == r.id }).take(KEEP)
        set(next)
        if (r.outcome == "missed") notifyMissed(r)
    }

    /** The list was looked at: missed calls are no longer new. */
    fun seen() {
        if (_calls.value.none { it.fresh }) return
        set(_calls.value.map { if (it.fresh) it.copy(fresh = false) else it })
        app.getSystemService(NotificationManager::class.java)?.cancel(MISSED_ID)
    }

    fun clear() = set(emptyList())

    private fun set(list: List<CallRecord>) {
        _calls.value = list
        prefs.edit().putString(K, json.encodeToString(ser, list)).apply()
        EventBus.emit("recent", "")
    }

    private fun notifyMissed(r: CallRecord) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Missed calls", NotificationManager.IMPORTANCE_HIGH).apply { description = "A linked phone called and nobody answered." },
        )
        val missed = _calls.value.filter { it.fresh }
        val who = r.names.firstOrNull() ?: "A phone"
        val open = PendingIntent.getActivity(
            app, 8301,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_CHAT, who),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (missed.size > 1) "${missed.size} missed calls" else "Missed ${if (r.video) "video " else ""}call")
            .setContentText(if (missed.size > 1) missed.flatMap { it.names }.distinct().joinToString(", ") else r.names.joinToString(", "))
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setWhen(r.at).setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(MISSED_ID, n) }
    }

    private companion object {
        const val K = "calls"
        const val KEEP = 50
        const val CHANNEL = "calls-missed"
        const val MISSED_ID = 8103
    }
}

/**
 * Says when the laptop with the helper drops off while it was connected: after two minutes with
 * nothing from it (asleep, off the internet, the helper stopped by Windows), a notification; and
 * when it is back, that notification goes. A helper closed on purpose says goodbye first, and
 * that is not reported.
 */
class LaptopWatch(ctx: Context, private val enabled: () -> Boolean) {
    private val app = ctx.applicationContext
    private var last: List<String> = emptyList()
    private var goneAt = 0L
    private var gone: String? = null
    private var told = false

    /** Every 15 s: whether a laptop that was there has gone. */
    fun check(now: Long = System.currentTimeMillis()) {
        val here = Control.connected.value
        if (here.isNotEmpty()) {
            if (told) back()
            last = here; gone = null; told = false
            return
        }
        if (last.isEmpty()) return
        if (gone == null) { gone = last.first(); goneAt = now; return }
        // Closed on purpose: it said goodbye.
        if (Control.byeAt >= goneAt - 10_000) { last = emptyList(); gone = null; return }
        if (!told && now - goneAt > GONE_MS && enabled()) { told = true; away(gone!!, goneAt) }
    }

    private fun channel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Laptop offline", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "The laptop with the helper stopped answering."
            },
        )
    }

    private fun away(name: String, since: Long) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        channel(nm)
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(since))
        val open = PendingIntent.getActivity(
            app, 8302, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$name is offline")
            .setContentText("Nothing from it since $time. It may be asleep, closed, or off the internet.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Nothing from it since $time. It may be asleep, closed, or off the internet. You will hear when it is back."))
            .setWhen(since).setShowWhen(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(ID, n) }
    }

    private fun back() {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        channel(nm)
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${Control.connected.value.firstOrNull() ?: "The laptop"} is back")
            .setContentText("Its helper is connected again.")
            .setTimeoutAfter(60_000)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(ID, n) }
    }

    private companion object {
        const val CHANNEL = "laptop-offline"
        const val ID = 8104
        const val GONE_MS = 120_000L
    }
}
