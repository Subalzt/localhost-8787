package dev.periy.bridge.server

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/** One of the phone's notifications, as the page shows it. */
@Serializable
data class NotifDto(
    val key: String,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val at: Long,
    /** The app takes a typed reply from the notification (WhatsApp, Messages, Telegram...). */
    val canReply: Boolean = false,
    /** Its other buttons, by label ("Mark as read", "Mute"...). */
    val actions: List<String> = emptyList(),
    /** Can be swiped away (dismissed) on the phone. */
    val clearable: Boolean = true,
    /** Stays while something runs (music, a download, a service); listed apart, as Android does. */
    val ongoing: Boolean = false,
)

@Serializable data class NotifKey(val key: String = "")
@Serializable data class NotifReply(val key: String = "", val text: String = "")
@Serializable data class NotifAction(val key: String = "", val index: Int = -1)

/**
 * The phone's notifications, for the page: kept while they are on the phone, announced as they
 * arrive ("notif") and go ("notifgone"), and answerable from the laptop: reply, press one of
 * their buttons, dismiss. Fed by [NotifyListener], which Android runs in the background once
 * "Notification access" is allowed for Localhost 8787, whether Localhost 8787 is open or not.
 */
object Notifs {
    private const val TAG = "Notifs"
    private val json = Json { encodeDefaults = true }
    private val live = ConcurrentHashMap<String, StatusBarNotification>()
    private val shown = ConcurrentHashMap<String, NotifDto>()
    private val icons = ConcurrentHashMap<String, ByteArray>()
    @Volatile var service: NotifyListener? = null

    val connected: Boolean get() = service != null

    /**
     * Notification access is on for Localhost 8787. Not the same as [connected]: Android binds
     * the listener some seconds after the app starts (and may rebind it later), and until then
     * the page should show an empty list, not ask for access that is already given.
     */
    fun allowed(ctx: Context): Boolean = connected ||
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun list(): List<NotifDto> = shown.values.sortedByDescending { it.at }

    fun listJson(): String = json.encodeToString(ListSerializer(NotifDto.serializer()), list())

    /** Everything at once, for a page that has just connected or when the listener comes back. */
    fun snapshotJson(ctx: Context): String = json.encodeToString(NotifList.serializer(), NotifList(allowed(ctx), list(), connected))

    /**
     * The listener is (back) on: what the phone shows now is the whole truth. Taken in quietly
     * and sent as one list, so the page neither pops up old ones again nor keeps ones since gone.
     */
    fun resync(ctx: Context, active: Array<StatusBarNotification>?) {
        live.clear()
        shown.clear()
        active?.forEach { posted(ctx, it, announce = false) }
        EventBus.emit("notifs", snapshotJson(ctx))
    }

    fun posted(ctx: Context, sbn: StatusBarNotification, announce: Boolean = true) {
        val n = sbn.notification ?: return
        // Everything the phone shows, the ongoing kind too (music, downloads, services), which the
        // page lists apart. Left out: Localhost 8787's own, and a group's summary, which only
        // repeats the messages that come with it.
        if (sbn.packageName == ctx.packageName) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
        val e = n.extras ?: return
        val title = (e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: e.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString().orEmpty()
        val text = body(e)
        if (title.isBlank() && text.isBlank()) return
        val actions = n.actions.orEmpty()
        val dto = NotifDto(
            key = sbn.key,
            pkg = sbn.packageName,
            app = appName(ctx, sbn.packageName),
            title = title,
            text = text,
            at = sbn.postTime,
            canReply = actions.any { it.remoteInputs?.isNotEmpty() == true },
            actions = actions.filter { it.remoteInputs.isNullOrEmpty() }.mapNotNull { it.title?.toString() }.take(3),
            clearable = sbn.isClearable,
            ongoing = ongoing,
        )
        live[sbn.key] = sbn
        if (shown[sbn.key] == dto) return
        shown[sbn.key] = dto
        if (announce) EventBus.emit("notif", json.encodeToString(NotifDto.serializer(), dto))
    }

    fun removed(key: String) {
        live.remove(key)
        if (shown.remove(key) != null) EventBus.emit("notifgone", key)
    }

    /** A chat's last few messages; otherwise the long text, the inbox lines, or the text. */
    private fun body(e: Bundle): String {
        val msgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching {
            @Suppress("DEPRECATION")
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(e.getParcelableArray(Notification.EXTRA_MESSAGES))
        }.getOrNull().orEmpty() else emptyList()
        if (msgs.isNotEmpty()) {
            val group = e.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
            return msgs.takeLast(4).joinToString("\n") { m ->
                val who = m.senderPerson?.name ?: m.sender
                (if (group && who != null) "$who: " else "") + (m.text ?: "")
            }
        }
        e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.takeIf { it.isNotBlank() }?.let { return it.toString() }
        e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.takeIf { it.isNotEmpty() }?.let { return it.takeLast(4).joinToString("\n") }
        return e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    }

    private fun appName(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

    /** The app's icon as a PNG, for the page. */
    fun icon(ctx: Context, pkg: String): ByteArray? = icons[pkg] ?: runCatching {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val px = 96
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(bmp))
        ByteArrayOutputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
    }.getOrNull()?.also { icons[pkg] = it }

    fun dismiss(key: String) {
        runCatching { service?.cancelNotification(key) }
    }

    fun dismissAll() {
        runCatching { service?.cancelAllNotifications() }
    }

    /** Types [text] into the notification's reply box and sends it, as its own Reply button would. */
    fun reply(ctx: Context, key: String, text: String): Boolean = runCatching {
        val n = live[key]?.notification ?: return false
        val action = n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true } ?: return false
        val intent = Intent()
        val results = Bundle()
        action.remoteInputs.forEach { results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
        action.actionIntent.send(ctx, 0, intent)
        true
    }.onFailure { Log.w(TAG, "reply", it) }.getOrDefault(false)

    /** Presses one of the notification's own buttons ("Mark as read"...), counted as the page lists them. */
    fun press(key: String, index: Int): Boolean = runCatching {
        val n = live[key]?.notification ?: return false
        val plain = n.actions.orEmpty().filter { it.remoteInputs.isNullOrEmpty() }
        plain.getOrNull(index)?.actionIntent?.send() ?: return false
        true
    }.onFailure { Log.w(TAG, "action", it) }.getOrDefault(false)
}

/** Android's hook into the phone's notifications; runs whenever "Notification access" is on. */
class NotifyListener : NotificationListenerService() {
    override fun onListenerConnected() {
        Notifs.service = this
        runCatching { Notifs.resync(this, activeNotifications) }
    }

    override fun onListenerDisconnected() {
        if (Notifs.service === this) Notifs.service = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = Notifs.posted(this, sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = Notifs.removed(sbn.key)
}
