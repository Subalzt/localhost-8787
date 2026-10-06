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
    }

    /** The list was looked at: missed calls are no longer new. */
    fun seen() {
        if (_calls.value.none { it.fresh }) return
        set(_calls.value.map { if (it.fresh) it.copy(fresh = false) else it })
    }

    fun clear() = set(emptyList())

    private fun set(list: List<CallRecord>) {
        _calls.value = list
        prefs.edit().putString(K, json.encodeToString(ser, list)).apply()
        EventBus.emit("recent", "")
    }

    private companion object {
        const val K = "calls"
        const val KEEP = 50
    }
}
