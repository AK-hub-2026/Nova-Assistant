package com.example.nova.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nova.core.SystemHealthSnapshot

@Composable
fun SystemAndPermissionsScreen(
    health: SystemHealthSnapshot,
    onRefreshHealth: () -> Unit,
    onRequestRecordAudioPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("system_permissions_list"),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Real Device & Low-End RAM Telemetry",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        OutlinedButton(
                            onClick = onRefreshHealth,
                            modifier = Modifier.testTag("refresh_telemetry_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh telemetry")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    TelemetryRow("Device Model", health.deviceModel)
                    TelemetryRow("Android OS", "Android ${health.androidRelease} (API ${health.sdkInt})")
                    TelemetryRow(
                        "System Memory (RAM)",
                        "${health.availableRamMb} MB available / ${health.totalRamMb} MB total"
                    )
                    TelemetryRow(
                        "Low-RAM Optimization Mode",
                        if (health.effectiveLowRamMode) {
                            "ACTIVE (Pruned node trees & minimal animations)"
                        } else {
                            "Standard (${if (health.isSystemLowRamDevice) "Low-RAM hardware" else ">4 GB RAM hardware"})"
                        }
                    )
                    TelemetryRow(
                        "Battery Status",
                        if (health.batteryPercent >= 0) {
                            "${health.batteryPercent}% (${if (health.isCharging) "Charging" else "Discharging"})"
                        } else {
                            "Unavailable"
                        }
                    )
                    TelemetryRow(
                        "Network Connectivity",
                        if (health.isNetworkOnline) "ONLINE" else "OFFLINE (Local Parser Only)"
                    )
                    TelemetryRow(
                        "Hardware Camera Flashlight",
                        if (health.hasFlashlightHardware) {
                            "Present (Torch=${if (health.isTorchCurrentlyOn) "ON" else "OFF"})"
                        } else {
                            "Absent on this device/emulator"
                        }
                    )
                }
            }
        }

        item {
            PermissionStatusCard(
                title = "Microphone Permission (RECORD_AUDIO)",
                isReady = health.recordAudioGranted,
                statusText = if (health.recordAudioGranted) {
                    "GRANTED — Real-time SpeechRecognizer voice input ready"
                } else {
                    "PERMISSION_REQUIRED — Needed for voice input"
                },
                actionLabel = if (!health.recordAudioGranted) "Grant Microphone" else null,
                onActionClick = onRequestRecordAudioPermission,
                tag = "perm_card_record_audio"
            )
        }

        item {
            PermissionStatusCard(
                title = "Nova Accessibility Service",
                isReady = health.accessibilityServiceConnected,
                statusText = when {
                    health.accessibilityServiceConnected ->
                        "CONNECTED — Cross-app UI inspection, actions & floating HUD active"
                    health.accessibilityServiceEnabled ->
                        "ENABLED IN SETTINGS — Waiting for OS service bind"
                    else ->
                        "SERVICE_DISABLED — Must be toggled on in Android Accessibility Settings"
                },
                actionLabel = "Open Accessibility Settings",
                onActionClick = onOpenAccessibilitySettings,
                tag = "perm_card_accessibility"
            )
        }

        item {
            PermissionStatusCard(
                title = "Task & Reminder Notifications (POST_NOTIFICATIONS)",
                isReady = health.postNotificationsGranted,
                statusText = if (health.postNotificationsGranted) {
                    "GRANTED — Active task Stop notifications & reminders enabled"
                } else {
                    "PERMISSION_REQUIRED — Needed on Android 13+ for notifications"
                },
                actionLabel = if (!health.postNotificationsGranted) "Grant Notifications" else null,
                onActionClick = onRequestNotificationPermission,
                tag = "perm_card_post_notifications"
            )
        }

        item {
            PermissionStatusCard(
                title = "Notification Listener Service (Opt-In)",
                isReady = health.notificationListenerEnabled,
                statusText = if (health.notificationListenerEnabled) {
                    "ENABLED — Can read real active status bar notifications"
                } else {
                    "DISABLED (Opt-In) — Enable only if you want Nova to read notifications"
                },
                actionLabel = "Notification Access Settings",
                onActionClick = onOpenNotificationListenerSettings,
                tag = "perm_card_notification_listener"
            )
        }

        item {
            PermissionStatusCard(
                title = "System SpeechRecognizer Engine",
                isReady = health.speechRecognitionAvailable,
                statusText = if (health.speechRecognitionAvailable) {
                    "AVAILABLE — Android platform speech recognition service detected"
                } else {
                    "UNSUPPORTED — No SpeechRecognizer service installed on this device"
                },
                actionLabel = null,
                onActionClick = {},
                tag = "perm_card_speech_engine"
            )
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PermissionStatusCard(
    title: String,
    isReady: Boolean,
    statusText: String,
    actionLabel: String?,
    onActionClick: () -> Unit,
    tag: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (actionLabel != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onActionClick,
                    modifier = Modifier.testTag("${tag}_button")
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}
