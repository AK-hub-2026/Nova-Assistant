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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nova.core.InstalledAppInfo
import com.example.nova.core.LiveNotificationItem
import com.example.nova.core.SemanticScreenSnapshot
import com.example.nova.core.SystemHealthSnapshot

@Composable
fun ScreenAndAppsScreen(
    health: SystemHealthSnapshot,
    screenSnapshot: SemanticScreenSnapshot?,
    installedApps: List<InstalledAppInfo>,
    notifications: List<LiveNotificationItem>,
    onCaptureScreenNow: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onExecuteCommand: (String) -> Unit,
    onRefreshApps: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSubTab by rememberSaveable { mutableIntStateOf(0) }
    var appSearchQuery by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedSubTab == 0,
                onClick = { selectedSubTab = 0 },
                label = { Text("Screen Nodes (${screenSnapshot?.nodes?.size ?: 0})") },
                leadingIcon = { Icon(Icons.Default.SettingsAccessibility, contentDescription = null) },
                modifier = Modifier.testTag("subtab_screen_nodes")
            )
            FilterChip(
                selected = selectedSubTab == 1,
                onClick = { selectedSubTab = 1 },
                label = { Text("Installed Apps (${installedApps.size})") },
                leadingIcon = { Icon(Icons.Default.Apps, contentDescription = null) },
                modifier = Modifier.testTag("subtab_installed_apps")
            )
            FilterChip(
                selected = selectedSubTab == 2,
                onClick = { selectedSubTab = 2 },
                label = { Text("Notifications (${notifications.size})") },
                leadingIcon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                modifier = Modifier.testTag("subtab_notifications")
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (selectedSubTab) {
            0 -> SemanticNodeInspectorSection(
                health = health,
                snapshot = screenSnapshot,
                onCaptureNow = onCaptureScreenNow,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onClickNodeIndex = { idx -> onExecuteCommand("click #$idx") }
            )

            1 -> InstalledAppsSection(
                apps = installedApps,
                searchQuery = appSearchQuery,
                onSearchQueryChange = { appSearchQuery = it },
                onRefreshApps = onRefreshApps,
                onLaunchApp = { pkg -> onExecuteCommand("open $pkg") }
            )

            2 -> NotificationsSection(
                health = health,
                notifications = notifications,
                onOpenNotificationSettings = onOpenNotificationSettings
            )
        }
    }
}

@Composable
private fun SemanticNodeInspectorSection(
    health: SystemHealthSnapshot,
    snapshot: SemanticScreenSnapshot?,
    onCaptureNow: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onClickNodeIndex: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (health.accessibilityServiceConnected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = if (health.accessibilityServiceConnected) {
                        "Accessibility Service Connected (Real-Time Node Tree Active)"
                    } else {
                        "Accessibility Service Disabled in Android Settings"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (health.accessibilityServiceConnected) {
                        "Nova reads semantic UI elements on demand and redacts password/OTP fields automatically."
                    } else {
                        "Enable 'Nova Accessibility Automation' in Android Accessibility Settings so Nova can inspect screen elements and perform cross-app actions."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onCaptureNow,
                        modifier = Modifier.testTag("inspect_screen_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Inspect Current Screen")
                    }
                    OutlinedButton(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.testTag("open_accessibility_settings_button")
                    ) {
                        Text("Accessibility Settings")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (snapshot == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "No Screen Snapshot Captured Yet",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap 'Inspect Current Screen' or run 'read screen' after enabling Nova Accessibility Service to view real UI nodes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Text(
                text = "Window: ${snapshot.packageName} • ${snapshot.nodes.size} semantic nodes (${snapshot.redactedFieldCount} redacted)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("semantic_nodes_list"),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(snapshot.nodes, key = { it.index }) { node ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "[#${node.index}] ${node.text.ifBlank { node.contentDescription.ifBlank { node.className } }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${node.className} • bounds=(${node.boundsInScreen.centerX()},${node.boundsInScreen.centerY()}) • clickable=${node.isClickable} editable=${node.isEditable}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (node.isClickable) {
                                OutlinedButton(
                                    onClick = { onClickNodeIndex(node.index) },
                                    modifier = Modifier.testTag("click_node_button_${node.index}")
                                ) {
                                    Icon(Icons.Default.TouchApp, contentDescription = "Click node")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Tap")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledAppsSection(
    apps: List<InstalledAppInfo>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onRefreshApps: () -> Unit,
    onLaunchApp: (String) -> Unit
) {
    val filtered = if (searchQuery.isBlank()) {
        apps
    } else {
        apps.filter {
            it.appLabel.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Filter real installed apps...") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("filter_installed_apps_field")
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onRefreshApps,
                modifier = Modifier.testTag("refresh_installed_apps_button")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh apps")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "No matching launchable apps found via PackageManager.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("installed_apps_list"),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.appLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { onLaunchApp(app.packageName) },
                                modifier = Modifier.testTag("launch_app_${app.packageName}")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Open")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationsSection(
    health: SystemHealthSnapshot,
    notifications: List<LiveNotificationItem>,
    onOpenNotificationSettings: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (!health.notificationListenerEnabled) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Notification Listener Not Enabled",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Nova does not fabricate notifications. To let Nova read active status bar notifications, enable 'Nova Notification Reader' in Android Notification Access Settings.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onOpenNotificationSettings,
                        modifier = Modifier.testTag("enable_notification_listener_button")
                    ) {
                        Text("Open Notification Listener Settings")
                    }
                }
            }
        } else if (notifications.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "0 Active Status Bar Notifications",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Notification Listener is connected and monitoring in real time, but the status bar currently has no active notifications.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("live_notifications_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(notifications, key = { it.key }) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "${item.title} (${item.packageName})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = item.text,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
