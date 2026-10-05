package com.example.nova.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * M10 — Real Android Foreground Service for Arya Live Voice Session.
 *
 * Rules:
 * - Operates with foregroundServiceType="microphone" as declared in AndroidManifest.xml.
 * - Always shows a visible ongoing system notification with an explicit "End Session" action.
 * - Never records silently or covertly.
 * - Shuts down immediately when the user ends the live session.
 */
class AryaLiveVoiceService : Service() {

    companion object {
        const val ACTION_START_LIVE_SESSION = "com.example.nova.action.START_LIVE_SESSION"
        const val ACTION_STOP_LIVE_SESSION = "com.example.nova.action.STOP_LIVE_SESSION"

        private const val CHANNEL_ID = "arya_live_voice_channel"
        private const val NOTIFICATION_ID = 2048

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, AryaLiveVoiceService::class.java).apply {
                action = ACTION_START_LIVE_SESSION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AryaLiveVoiceService::class.java).apply {
                action = ACTION_STOP_LIVE_SESSION
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_LIVE_SESSION -> {
                shutdownService()
                return START_NOT_STICKY
            }
            ACTION_START_LIVE_SESSION, null -> {
                val notification = buildOngoingNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                _isServiceActive.value = true
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Arya Live Voice Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active status during an Arya live conversational voice session."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildOngoingNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AryaLiveVoiceService::class.java).apply {
            action = ACTION_STOP_LIVE_SESSION
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Arya Live Voice Session Active")
            .setContentText("Microphone active — speaking naturally with Arya")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "End Session",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun shutdownService() {
        _isServiceActive.value = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        _isServiceActive.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
