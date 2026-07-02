package com.dhruv.pixelfarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps [FarmRepository] updated while the app is not in the foreground by
 * listening for screen on/off and running a periodic heartbeat. Runs as a
 * foreground service with START_STICKY so OEM task killers restart it.
 *
 * ACTION_SCREEN_ON/OFF cannot be declared in the manifest -- it must be
 * registered dynamically here.
 */
class FarmTrackerService : Service() {

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> FarmRepository.onScreenOn()
                Intent.ACTION_SCREEN_OFF -> FarmRepository.onScreenOff()
            }
        }
    }

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            FarmRepository.heartbeat()
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        FarmRepository.init(applicationContext)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)

        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PixelFarm",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps your farm growing in the background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PixelFarm is watching over your crops")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "pixelfarm_tracker_channel"
        private const val NOTIFICATION_ID = 1001
        private const val HEARTBEAT_INTERVAL_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, FarmTrackerService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
