package dev.periy.bridge.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.periy.bridge.R
import dev.periy.bridge.container
import dev.periy.bridge.server.CamSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Camera mode (server/Cameras.kt): in the foreground as camera and microphone, so the camera keeps
 * running with the screen off and the phone locked. Android lets it take them only when started
 * while the app is in front; from the background it is refused, and Cameras wakes the phone over
 * its lock screen for a moment (ui/CamWakeActivity.kt) to start it from there.
 */
class CameraService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cams = container.cameras
        if (intent?.action == STOP) { cams.set(CamSet(on = false)); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Camera mode", NotificationManager.IMPORTANCE_LOW).apply {
            description = "While this phone is a camera for your other devices."
        })
        val stop = PendingIntent.getService(this, 8520, Intent(this, CameraService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 8521, Intent(this, dev.periy.bridge.ui.CamerasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Camera mode is on")
            .setContentText("Your other phones and laptops can watch this camera.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .build()
        val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        try {
            ServiceCompat.startForeground(this, ID, n, types)
        } catch (e: Exception) {
            Log.i(TAG, "Not allowed from the background: ${e.message}")
            runCatching { ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            cams.serviceRefused()
            return START_NOT_STICKY
        }
        cams.serviceUp()
        scope.launch { cams.state.collect { if (!it.on) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } } }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CameraService"
        private const val CHANNEL = "camera-mode"
        private const val ID = 8503
        const val STOP = "dev.periy.bridge.CAMERA_STOP"
    }
}
