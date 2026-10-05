package com.example.nova.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nova.core.DeviceFieldState
import com.example.nova.core.NovaDeviceInfo
import com.example.ui.theme.NovaOrbCyanGlow
import com.example.ui.theme.NovaOrbSapphireCore
import java.util.Locale

/**
 * M4 — Formatted display strings for [DeviceInfoWorkspaceCard].
 * Guarantees that any [DeviceFieldState.Unavailable] renders strictly as "Unavailable"
 * and never coerces missing values into 0, 0%, or empty strings.
 */
data class DeviceInfoDisplayRows(
    val batteryLevelText: String,
    val chargingStateText: String,
    val chargingSourceText: String,
    val availableRamText: String,
    val totalRamText: String,
    val lowRamModeText: String,
    val storageScopeText: String,
    val availableStorageText: String,
    val totalStorageText: String,
    val androidVersionText: String,
    val sdkLevelText: String,
    val deviceModelText: String,
    val networkConnectivityText: String,
    val networkTransportText: String
)

fun formatBytesHumanReadable(bytes: Long): String {
    if (bytes < 0L) return "Unavailable"
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        String.format(Locale.US, "%.2f GB", gb)
    } else {
        val mb = bytes / (1024L * 1024L)
        "$mb MB"
    }
}

fun formatDeviceInfoDisplayRows(deviceInfo: NovaDeviceInfo): DeviceInfoDisplayRows {
    val batteryLevelText = when (val pct = deviceInfo.batteryPercentage) {
        is DeviceFieldState.Available -> "${pct.value}%"
        is DeviceFieldState.Unavailable -> "Unavailable"
    }

    val chargingStateText = when (val st = deviceInfo.chargingState) {
        is DeviceFieldState.Available -> st.value.displayLabel
        is DeviceFieldState.Unavailable -> "Unavailable"
    }

    val chargingSourceText = when (val src = deviceInfo.chargingSource) {
        is DeviceFieldState.Available -> src.value.displayLabel
        is DeviceFieldState.Unavailable -> "Unavailable"
    }

    val availableRamText = when (val avail = deviceInfo.availableRamBytes) {
        is DeviceFieldState.Available -> formatBytesHumanReadable(avail.value)
        is DeviceFieldState.Unavailable -> "Unavailable"
    }

    val totalRamText = when (val total = deviceInfo.totalRamBytes) {
        is DeviceFieldState.Available -> formatBytesHumanReadable(total.value)
        is DeviceFieldState.Unavailable -> "Unavailable"
    }

    val lowRamModeText = when (val lowRam = deviceInfo.isLowRamDevice) {
        is DeviceFieldState.Available -> if (lowRam.value) "Low-RAM Tier" else "Standard Tier"
        is DeviceFieldState.Unavailable -> "Unavailable"
    }

    val storageScopeText = when (val scope = deviceInfo.storageScopeDescription) {
        is DeviceFieldState.Available -> scope.value
        is DeviceFieldState.Unavailable -> "Unavailable"
    }

    val availableStorageText = when (val avail = deviceInfo.availableStorageBytes) {
        is DeviceFieldState.Available -> formatBytesHumanReadable(avail.value)
        is DeviceFieldState.Unavailable -> "Unavailable"
    }

    val totalStorageText = when (val total = deviceInfo.totalStorageBytes) {
        is DeviceFieldState.Available -> formatBytesHumanReadable(total.value)
        is DeviceFieldState.Unavailable -> "Unavailable"
    }

    val androidVersionText = when (val rel = deviceInfo.androidVersionRelease) {
        is DeviceFieldState.Available -> "Android ${rel.value}"
        is DeviceFieldState.Unavailable -> "Unavailable"
    }

    val sdkLevelText = when (val sdk = deviceInfo.sdkInt) {
        is DeviceFieldState.Available -> "API ${sdk.value}"
        is DeviceFieldState.Unavailable -> "Unavailable"
    }

    val deviceModelText = when (val model = deviceInfo.safeDeviceModel) {
        is DeviceFieldState.Available -> model.value
        is DeviceFieldState.Unavailable -> "Unavailable"
    }

    val networkConnectivityText = when (val net = deviceInfo.networkConnectivity) {
        is DeviceFieldState.Available -> net.value.displayLabel
        is DeviceFieldState.Unavailable -> "Unavailable"
    }

    val networkTransportText = when (val tr = deviceInfo.networkTransport) {
        is DeviceFieldState.Available -> tr.value.displayLabel
        is DeviceFieldState.Unavailable -> "Unavailable"
    }

    return DeviceInfoDisplayRows(
        batteryLevelText = batteryLevelText,
        chargingStateText = chargingStateText,
        chargingSourceText = chargingSourceText,
        availableRamText = availableRamText,
        totalRamText = totalRamText,
        lowRamModeText = lowRamModeText,
        storageScopeText = storageScopeText,
        availableStorageText = availableStorageText,
        totalStorageText = totalStorageText,
        androidVersionText = androidVersionText,
        sdkLevelText = sdkLevelText,
        deviceModelText = deviceModelText,
        networkConnectivityText = networkConnectivityText,
        networkTransportText = networkTransportText
    )
}

/**
 * M4 — Contextual Device Information Workspace Card.
 * Rendered below the centered Nova Orb only when `query_device_info` is invoked.
 */
@Composable
fun DeviceInfoWorkspaceCard(
    deviceInfo: NovaDeviceInfo,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = remember(deviceInfo) {
        formatDeviceInfoDisplayRows(deviceInfo)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = NovaOrbCyanGlow.copy(alpha = 0.28f),
                shape = RoundedCornerShape(22.dp)
            )
            .testTag("device_info_workspace_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = NovaOrbSapphireCore
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Live Device Information",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("dismiss_workspace_card_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss device info card"
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 1. Battery Section
            DeviceInfoSectionHeader(
                icon = Icons.Default.BatteryChargingFull,
                title = "Battery"
            )
            DeviceInfoKeyValueRow("Level", rows.batteryLevelText, "device_info_battery_level")
            DeviceInfoKeyValueRow("Status", rows.chargingStateText, "device_info_charging_state")
            DeviceInfoKeyValueRow("Source", rows.chargingSourceText, "device_info_charging_source")

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )

            // 2. Memory (RAM) Section
            DeviceInfoSectionHeader(
                icon = Icons.Default.Memory,
                title = "Memory (RAM)"
            )
            DeviceInfoKeyValueRow("Available RAM", rows.availableRamText, "device_info_ram_available")
            DeviceInfoKeyValueRow("Total RAM", rows.totalRamText, "device_info_ram_total")
            DeviceInfoKeyValueRow("Memory Tier", rows.lowRamModeText, "device_info_ram_tier")

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )

            // 3. Storage Section
            DeviceInfoSectionHeader(
                icon = Icons.Default.Storage,
                title = "Storage"
            )
            DeviceInfoKeyValueRow("Scope", rows.storageScopeText, "device_info_storage_scope")
            DeviceInfoKeyValueRow("Available", rows.availableStorageText, "device_info_storage_available")
            DeviceInfoKeyValueRow("Total", rows.totalStorageText, "device_info_storage_total")

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )

            // 4. Android & Device Section
            DeviceInfoSectionHeader(
                icon = Icons.Default.PhoneAndroid,
                title = "Android & Device"
            )
            DeviceInfoKeyValueRow("Model", rows.deviceModelText, "device_info_model")
            DeviceInfoKeyValueRow("Android Version", rows.androidVersionText, "device_info_android_version")
            DeviceInfoKeyValueRow("SDK Level", rows.sdkLevelText, "device_info_sdk_level")

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )

            // 5. Network Section
            DeviceInfoSectionHeader(
                icon = Icons.Default.Wifi,
                title = "Network"
            )
            DeviceInfoKeyValueRow("Connectivity", rows.networkConnectivityText, "device_info_network_state")
            DeviceInfoKeyValueRow("Transport", rows.networkTransportText, "device_info_network_transport")
        }
    }
}

@Composable
private fun DeviceInfoSectionHeader(
    icon: ImageVector,
    title: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = NovaOrbSapphireCore,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun DeviceInfoKeyValueRow(
    label: String,
    value: String,
    testTag: String
) {
    val isUnavailable = value == "Unavailable"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isUnavailable) FontWeight.Normal else FontWeight.SemiBold,
            color = if (isUnavailable) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.testTag(testTag)
        )
    }
}
