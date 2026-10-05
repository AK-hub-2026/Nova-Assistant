package com.example.nova.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.nova.accessibility.NovaAccessibilityService
import com.example.nova.core.LiveNotificationItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Opt-in NotificationListenerService that surfaces real status bar notifications.
 * Never fabricates or mocks notifications; when disabled or empty, returns an empty list.
 */
class NovaNotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isListenerConnected.value = true
        refreshActiveNotifications()
    }

    override fun onListenerDisconnected() {
        _isListenerConnected.value = false
        _activeNotifications.value = emptyList()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshActiveNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshActiveNotifications()
    }

    private fun refreshActiveNotifications() {
        val list = runCatching {
            activeNotifications?.mapNotNull { sbn ->
                val extras = sbn.notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                if (title.isBlank() && text.isBlank()) {
                    null
                } else {
                    LiveNotificationItem(
                        key = sbn.key ?: "${sbn.packageName}_${sbn.postTime}",
                        packageName = sbn.packageName ?: "unknown",
                        title = title,
                        text = text,
                        postedAtMs = sbn.postTime
                    )
                }
            }?.sortedByDescending { it.postedAtMs }?.take(25) ?: emptyList()
        }.getOrDefault(emptyList())

        _activeNotifications.value = list
    }

    companion object {
        private val _isListenerConnected = MutableStateFlow(false)
        val isListenerConnected: StateFlow<Boolean> = _isListenerConnected.asStateFlow()

        private val _activeNotifications = MutableStateFlow<List<LiveNotificationItem>>(emptyList())
        val activeNotifications: StateFlow<List<LiveNotificationItem>> = _activeNotifications.asStateFlow()
    }
}

/**
 * Strictly scoped ForegroundService started ONLY while Nova is actively executing
 * a multi-step task so low-RAM Android devices do not kill the execution coroutine.
 */
class NovaActiveTaskService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_TASK) {
            NovaAccessibilityService.onUserCancelTaskCallback?.invoke()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val stepSummary = intent?.getStringExtra(EXTRA_TASK_SUMMARY) ?: "Executing verified action..."
        ensureChannel()

        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, NovaActiveTaskService::class.java).apply { action = ACTION_STOP_TASK },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.nova_active_task_notification_title))
            .setContentText(stepSummary)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.nova_stop_task),
                stopIntent
            )
            .build()

        runCatching {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.nova_active_task_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "nova_active_task_channel"
        private const val NOTIFICATION_ID = 4201
        private const val EXTRA_TASK_SUMMARY = "extra_task_summary"
        const val ACTION_STOP_TASK = "com.example.nova.ACTION_STOP_TASK"

        fun startForActiveTask(context: Context, summary: String) {
            val intent = Intent(context, NovaActiveTaskService::class.java).apply {
                putExtra(EXTRA_TASK_SUMMARY, summary)
            }
            runCatching {
                ContextCompatStart.start(context, intent)
            }
        }

        fun stopForCompletedTask(context: Context) {
            runCatching {
                context.stopService(Intent(context, NovaActiveTaskService::class.java))
            }
        }
    }
}

private object ContextCompatStart {
    fun start(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
