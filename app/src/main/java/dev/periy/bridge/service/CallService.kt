package dev.periy.bridge.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import dev.periy.bridge.R
import dev.periy.bridge.container
import dev.periy.bridge.server.CallState
import dev.periy.bridge.ui.MainActivity

/**
 * A call's place outside the app: while one rings here, a call notification that rings and fills
 * the screen (Answer and Decline on it); while one is under way, this foreground service, so the
 * microphone keeps working with the app in the background, with Hang up in its notification.
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = container.calls.state.value
        if (s == null || s.phase == "ended" || s.phase == "ringing") { stopSelf(); return START_NOT_STICKY }
        val n = ongoing(this, s)
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            } else startForeground(ONGOING_ID, n)
        }.recoverCatching {
            // Without call standing the microphone alone still keeps it going.
            if (Build.VERSION.SDK_INT >= 30) startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val RING_CHANNEL = "calls-ring"
        private const val ONGOING_CHANNEL = "calls"
        private const val RING_ID = 8101
        private const val ONGOING_ID = 8102
        const val ACTION_DECLINE = "dev.periy.bridge.CALL_DECLINE"
        const val ACTION_HANG_UP = "dev.periy.bridge.CALL_HANG_UP"

        /** Follows the call: rings for one coming in, runs the service for one under way, and clears up after. */
        fun update(ctx: Context, s: CallState?) {
            val app = ctx.applicationContext
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            channels(nm)
            if (s == null || s.phase == "ended") {
                nm.cancel(RING_ID)
                app.stopService(Intent(app, CallService::class.java))
                return
            }
            if (s.phase == "ringing" && !s.outgoing) {
                runCatching { nm.notify(RING_ID, ringing(app, s)) }
                return
            }
            nm.cancel(RING_ID)
            // Under way: started from the screen (calling, or answering), so it may use the microphone.
            runCatching { app.startForegroundService(Intent(app, CallService::class.java)) }
        }

        private fun channels(nm: NotificationManager) {
            if (nm.getNotificationChannel(RING_CHANNEL) == null) nm.createNotificationChannel(
                NotificationChannel(RING_CHANNEL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A linked phone calling."
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    )
                    enableVibration(true)
                },
            )
            if (nm.getNotificationChannel(ONGOING_CHANNEL) == null) nm.createNotificationChannel(
                NotificationChannel(ONGOING_CHANNEL, "Calls", NotificationManager.IMPORTANCE_LOW).apply { description = "A call under way." },
            )
        }

        private fun person(name: String) = Person.Builder().setName(name).setImportant(true).build()

        private fun open(app: Context, extra: String?) = PendingIntent.getActivity(
            app, if (extra == null) 8201 else 8202,
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_CALL, extra ?: "show"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun action(app: Context, act: String) = PendingIntent.getBroadcast(
            app, act.hashCode(), Intent(app, CallActions::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun ringing(app: Context, s: CallState) = NotificationCompat.Builder(app, RING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.peer)
            .setContentText("Calling, phone to phone")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(open(app, null), true)
            .setContentIntent(open(app, null))
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person(s.peer), action(app, ACTION_DECLINE), open(app, "answer")))
            .build()

        private fun ongoing(app: Context, s: CallState) = NotificationCompat.Builder(app, ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.peer)
            .setContentText(if (s.phase == "active") "On a call" else "Calling")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setUsesChronometer(s.phase == "active")
            .setWhen(if (s.since > 0) s.since else System.currentTimeMillis())
            .setContentIntent(open(app, null))
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(s.peer), action(app, ACTION_HANG_UP)))
            .build()
    }
}

/** Decline and Hang up, from the notifications. */
class CallActions : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            CallService.ACTION_DECLINE, CallService.ACTION_HANG_UP -> ctx.container.calls.hangUp()
        }
    }
}
