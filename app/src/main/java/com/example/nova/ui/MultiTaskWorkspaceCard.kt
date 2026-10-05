package com.example.nova.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.nova.core.AccessibilityWorkspaceState
import com.example.nova.core.DeviceFieldState
import com.example.nova.core.LiveTaskItem
import com.example.nova.core.LiveTaskStatus
import com.example.nova.core.LiveTaskWorkspaceReference
import com.example.nova.core.NovaDeviceInfo
import com.example.nova.core.NovaTaskCapabilityType
import com.example.nova.core.PhotoAccessWorkspaceState
import com.example.nova.core.WorkspaceCardState
import com.example.nova.tools.FullscreenPhotoLoadOutcome
import com.example.nova.tools.ThumbnailDecodeOutcome
import kotlin.math.roundToInt

/**
 * M7 — Deterministic UI model for a single [LiveTaskItem] row in [MultiTaskWorkspaceCard].
 *
 * Enforces zero-mock presentation:
 * - Never fabricates progress percentages; [showIndeterminateProgress] is true for `RUNNING`
 *   tasks when [LiveTaskItem.measurableProgressFraction] is null.
 * - Exposes real lifecycle state, capability type, verification summary, failure/unavailable
 *   reason, and workspace reference.
 */
data class MultiTaskItemUiModel(
    val taskId: String,
    val title: String,
    val capabilityType: NovaTaskCapabilityType,
    val capabilityBadgeText: String,
    val status: LiveTaskStatus,
    val statusBadgeText: String,
    val statusMessage: String,
    val resultSummaryText: String,
    val failureOrUnavailableReasonText: String,
    val showIndeterminateProgress: Boolean,
    val measurableProgressPercentText: String?,
    val isCancellable: Boolean,
    val workspaceReference: LiveTaskWorkspaceReference?,
    val workspaceButtonLabel: String?,
    val elapsedTimeText: String
)

/**
 * Pure formatting helper converting a [LiveTaskItem] into a [MultiTaskItemUiModel].
 */
fun formatMultiTaskItemSummary(task: LiveTaskItem): MultiTaskItemUiModel {
    val fraction = task.measurableProgressFraction?.takeIf { it in 0f..1f }
    val isRunning = task.status == LiveTaskStatus.RUNNING
    val showIndeterminate = isRunning && fraction == null
    val measurablePercentText = if (isRunning && fraction != null) {
        "${(fraction * 100f).roundToInt()}%"
    } else {
        null
    }

    val resultSummary = if (task.status == LiveTaskStatus.COMPLETED_VERIFIED) {
        task.verificationSummary.ifBlank {
            task.verifiedResult?.userFacingMessage.orEmpty()
        }
    } else {
        ""
    }

    val failureReason = when (task.status) {
        LiveTaskStatus.FAILED,
        LiveTaskStatus.PERMISSION_REQUIRED,
        LiveTaskStatus.UNAVAILABLE,
        LiveTaskStatus.CANCELLED -> {
            task.failureOrUnavailableReason.ifBlank {
                task.verifiedResult?.userFacingMessage.orEmpty().ifBlank {
                    task.verifiedResult?.verificationDetail.orEmpty()
                }
            }
        }
        else -> ""
    }

    val workspaceButtonLabel = when (task.workspaceReference) {
        LiveTaskWorkspaceReference.DEVICE_INFO_WORKSPACE -> "Device Info Workspace"
        LiveTaskWorkspaceReference.PHOTO_WORKSPACE -> "Photo Workspace"
        LiveTaskWorkspaceReference.ACCESSIBILITY_WORKSPACE -> "Accessibility Workspace"
        LiveTaskWorkspaceReference.VERIFIED_ACTION_SUMMARY -> "Verified Action Detail"
        null -> null
    }

    val elapsedText = when {
        task.elapsedMs > 0L -> "${task.elapsedMs} ms"
        task.startedAtMs is DeviceFieldState.Available &&
            task.completedAtMs is DeviceFieldState.Available ->
            "${(task.completedAtMs.value - task.startedAtMs.value).coerceAtLeast(0L)} ms"
        else -> ""
    }

    return MultiTaskItemUiModel(
        taskId = task.taskId,
        title = task.title,
        capabilityType = task.capabilityType,
        capabilityBadgeText = task.capabilityType.displayLabel,
        status = task.status,
        statusBadgeText = task.status.name,
        statusMessage = task.statusMessage,
        resultSummaryText = resultSummary,
        failureOrUnavailableReasonText = failureReason,
        showIndeterminateProgress = showIndeterminate,
        measurableProgressPercentText = measurablePercentText,
        isCancellable = task.isCancellable,
        workspaceReference = task.workspaceReference,
        workspaceButtonLabel = workspaceButtonLabel,
        elapsedTimeText = elapsedText
    )
}

/**
 * Pure helper formatting the aggregate header subtitle for [MultiTaskWorkspaceCard].
 */
fun formatMultiTaskHeaderSubtitle(tasks: List<LiveTaskItem>): String {
    if (tasks.isEmpty()) return ""
    val running = tasks.count { it.status == LiveTaskStatus.RUNNING }
    val pending = tasks.count {
        it.status == LiveTaskStatus.PENDING || it.status == LiveTaskStatus.AWAITING_CONFIRMATION
    }
    val verified = tasks.count { it.status == LiveTaskStatus.COMPLETED_VERIFIED }
    val permOrUnavail = tasks.count {
        it.status == LiveTaskStatus.PERMISSION_REQUIRED || it.status == LiveTaskStatus.UNAVAILABLE
    }
    val failed = tasks.count { it.status == LiveTaskStatus.FAILED }
    val cancelled = tasks.count { it.status == LiveTaskStatus.CANCELLED }

    val parts = mutableListOf("${tasks.size} task(s)")
    if (running > 0) parts.add("$running running")
    if (pending > 0) parts.add("$pending queued")
    if (verified > 0) parts.add("$verified verified")
    if (permOrUnavail > 0) parts.add("$permOrUnavail need access/config")
    if (failed > 0) parts.add("$failed failed")
    if (cancelled > 0) parts.add("$cancelled cancelled")
    return parts.joinToString(" • ")
}

/**
 * M7 — Contextual Multi-Task Workspace Card.
 *
 * Appears only when real active or recent tasks exist in the current session.
 * Coordinates independent tasks (`DEVICE_INFO`, `PHOTOS`, `ACCESSIBILITY`,
 * `GENERAL_ASSISTANT_TASK`) and connects completed/actionable tasks directly to
 * `DeviceInfoWorkspaceCard`, `PhotoWorkspaceCard`, and `AccessibilityWorkspaceCard`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiTaskWorkspaceCard(
    tasks: List<LiveTaskItem>,
    lowRamMode: Boolean,
    deviceInfo: NovaDeviceInfo? = null,
    photoState: PhotoAccessWorkspaceState? = null,
    accessibilityWorkspaceState: AccessibilityWorkspaceState? = null,
    onCancelTask: (String) -> Unit,
    onCancelAllTasks: () -> Unit,
    onDismiss: () -> Unit,
    onRequestMediaPermission: () -> Unit = {},
    onLaunchPhotoPicker: () -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit = {},
    onRefreshAccessibilityScreen: () -> Unit = {},
    onLoadPhotoThumbnail: suspend (String, Boolean) -> ThumbnailDecodeOutcome = { _, _ ->
        ThumbnailDecodeOutcome.PreviewUnavailable()
    },
    onLoadFullscreenPhoto: suspend (String, Boolean) -> FullscreenPhotoLoadOutcome = { _, _ ->
        FullscreenPhotoLoadOutcome.InaccessibleOrRevoked("Unavailable")
    },
    modifier: Modifier = Modifier
) {
    if (tasks.isEmpty()) return

    val uiModels = remember(tasks) { tasks.map(::formatMultiTaskItemSummary) }
    val headerSubtitle = remember(tasks) { formatMultiTaskHeaderSubtitle(tasks) }
    val anyCancellable = remember(tasks) { tasks.any { it.isCancellable } }
    val allTerminal = remember(tasks) { tasks.all { it.status.isTerminal } }

    // Determine which tasks have a connected workspace ready to display
    val tasksWithWorkspaces = remember(tasks, deviceInfo, photoState, accessibilityWorkspaceState) {
        tasks.filter { task ->
            NovaUiStateMapper.resolveTaskConnectedWorkspace(
                task = task,
                latestDeviceInfo = deviceInfo,
                latestPhotoWorkspaceState = photoState,
                latestAccessibilityWorkspaceState = accessibilityWorkspaceState
            ) != WorkspaceCardState.None
        }
    }

    var selectedWorkspaceTaskId by remember(tasksWithWorkspaces.map { it.taskId }) {
        mutableStateOf(tasksWithWorkspaces.firstOrNull()?.taskId)
    }

    val activeSelectedTask = tasksWithWorkspaces.firstOrNull { it.taskId == selectedWorkspaceTaskId }
        ?: tasksWithWorkspaces.firstOrNull()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("multi_task_workspace_card"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Live Task Orchestration",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("multi_task_header_title")
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = headerSubtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("multi_task_header_subtitle")
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (anyCancellable) {
                            OutlinedButton(
                                onClick = onCancelAllTasks,
                                modifier = Modifier.testTag("multi_task_cancel_all_button")
                            ) {
                                Text("Stop All")
                            }
                        }
                        if (allTerminal) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("dismiss_multi_task_workspace_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss multi-task workspace"
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Individual Task Rows
                uiModels.forEach { item ->
                    MultiTaskItemRow(
                        item = item,
                        isSelectedWorkspace = activeSelectedTask?.taskId == item.taskId,
                        onCancelTask = { onCancelTask(item.taskId) },
                        onSelectWorkspace = {
                            selectedWorkspaceTaskId = if (selectedWorkspaceTaskId == item.taskId) {
                                null
                            } else {
                                item.taskId
                            }
                        },
                        onRequestMediaPermission = onRequestMediaPermission,
                        onLaunchPhotoPicker = onLaunchPhotoPicker,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings
                    )
                }

                // Connected Workspace Switcher Chips when multiple workspaces are available
                if (tasksWithWorkspaces.size > 1) {
                    HorizontalDivider()
                    Text(
                        text = "Connected Task Workspaces",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tasksWithWorkspaces.forEach { task ->
                            val selected = activeSelectedTask?.taskId == task.taskId
                            FilterChip(
                                selected = selected,
                                onClick = { selectedWorkspaceTaskId = task.taskId },
                                label = {
                                    Text(
                                        text = task.capabilityType.displayLabel,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.testTag("workspace_tab_${task.taskId}")
                            )
                        }
                    }
                }
            }
        }

        // Render the connected specialized workspace card for the selected task without duplicating its UI
        if (activeSelectedTask != null) {
            val connectedState = NovaUiStateMapper.resolveTaskConnectedWorkspace(
                task = activeSelectedTask,
                latestDeviceInfo = deviceInfo,
                latestPhotoWorkspaceState = photoState,
                latestAccessibilityWorkspaceState = accessibilityWorkspaceState
            )
            when (connectedState) {
                is WorkspaceCardState.DeviceInfoWorkspace -> {
                    DeviceInfoWorkspaceCard(
                        deviceInfo = connectedState.deviceInfo,
                        onDismiss = { selectedWorkspaceTaskId = null }
                    )
                }
                is WorkspaceCardState.PhotoWorkspace -> {
                    PhotoWorkspaceCard(
                        photoState = connectedState.photoState,
                        lowRamMode = lowRamMode,
                        onLoadThumbnail = onLoadPhotoThumbnail,
                        onLoadFullscreenPhoto = onLoadFullscreenPhoto,
                        onRequestMediaPermission = onRequestMediaPermission,
                        onLaunchPhotoPicker = onLaunchPhotoPicker,
                        onDismiss = { selectedWorkspaceTaskId = null }
                    )
                }
                is WorkspaceCardState.AccessibilityWorkspace -> {
                    AccessibilityWorkspaceCard(
                        workspaceState = connectedState.workspaceState,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        onRefreshScreen = onRefreshAccessibilityScreen,
                        onDismiss = { selectedWorkspaceTaskId = null }
                    )
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun MultiTaskItemRow(
    item: MultiTaskItemUiModel,
    isSelectedWorkspace: Boolean,
    onCancelTask: () -> Unit,
    onSelectWorkspace: () -> Unit,
    onRequestMediaPermission: () -> Unit,
    onLaunchPhotoPicker: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val statusColors = rememberTaskStatusColors(item.status)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("multi_task_item_${item.taskId}"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = capabilityIcon(item.capabilityType),
                        contentDescription = item.capabilityBadgeText,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("multi_task_title_${item.taskId}")
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = item.capabilityBadgeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("multi_task_capability_${item.taskId}")
                            )
                            if (item.elapsedTimeText.isNotBlank()) {
                                Text(
                                    text = "• ${item.elapsedTimeText}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Real Lifecycle State Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColors.containerColor,
                    modifier = Modifier.testTag("multi_task_status_badge_${item.taskId}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = statusIcon(item.status),
                            contentDescription = null,
                            tint = statusColors.contentColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = item.statusBadgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColors.contentColor
                        )
                    }
                }
            }

            // Indeterminate progress indicator when RUNNING and progress cannot be measured
            if (item.showIndeterminateProgress) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("multi_task_running_indicator_${item.taskId}")
                )
            } else if (item.measurableProgressPercentText != null) {
                Text(
                    text = "Progress: ${item.measurableProgressPercentText}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Conversational / current real status message
            if (item.statusMessage.isNotBlank()) {
                Text(
                    text = item.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("multi_task_status_message_${item.taskId}")
                )
            }

            // Real verified result summary when COMPLETED_VERIFIED
            if (item.resultSummaryText.isNotBlank() &&
                item.resultSummaryText != item.statusMessage
            ) {
                Text(
                    text = item.resultSummaryText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("multi_task_result_summary_${item.taskId}")
                )
            }

            // Real failure, permission, or unavailable reason when applicable
            if (item.failureOrUnavailableReasonText.isNotBlank() &&
                item.failureOrUnavailableReasonText != item.statusMessage
            ) {
                Text(
                    text = item.failureOrUnavailableReasonText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("multi_task_failure_reason_${item.taskId}")
                )
            }

            // Action row: Cancel button for cancellable tasks, permission actions, or workspace toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.isCancellable) {
                    OutlinedButton(
                        onClick = onCancelTask,
                        modifier = Modifier.testTag("cancel_task_button_${item.taskId}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel ${item.title}",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cancel")
                    }
                }

                if (item.status == LiveTaskStatus.PERMISSION_REQUIRED &&
                    item.capabilityType == NovaTaskCapabilityType.PHOTOS
                ) {
                    OutlinedButton(
                        onClick = onLaunchPhotoPicker,
                        modifier = Modifier.testTag("task_photo_picker_button_${item.taskId}")
                    ) {
                        Text("Photo Picker")
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = onRequestMediaPermission,
                        modifier = Modifier.testTag("task_photo_permission_button_${item.taskId}")
                    ) {
                        Text("Grant Photo Access")
                    }
                }

                if (item.status == LiveTaskStatus.PERMISSION_REQUIRED &&
                    item.capabilityType == NovaTaskCapabilityType.ACCESSIBILITY
                ) {
                    Button(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.testTag("task_a11y_settings_button_${item.taskId}")
                    ) {
                        Text("Accessibility Settings")
                    }
                }

                if (item.workspaceButtonLabel != null && !item.isCancellable) {
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = onSelectWorkspace,
                        modifier = Modifier.testTag("open_task_workspace_${item.taskId}")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            if (isSelectedWorkspace) "Hide Workspace" else item.workspaceButtonLabel
                        )
                    }
                }
            }
        }
    }
}

private data class TaskBadgeColors(
    val containerColor: Color,
    val contentColor: Color
)

@Composable
private fun rememberTaskStatusColors(status: LiveTaskStatus): TaskBadgeColors {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        LiveTaskStatus.COMPLETED_VERIFIED -> TaskBadgeColors(
            containerColor = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer
        )
        LiveTaskStatus.RUNNING -> TaskBadgeColors(
            containerColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer
        )
        LiveTaskStatus.PENDING,
        LiveTaskStatus.AWAITING_CONFIRMATION -> TaskBadgeColors(
            containerColor = scheme.surfaceContainerHighest,
            contentColor = scheme.onSurfaceVariant
        )
        LiveTaskStatus.PERMISSION_REQUIRED,
        LiveTaskStatus.UNAVAILABLE -> TaskBadgeColors(
            containerColor = scheme.tertiaryContainer,
            contentColor = scheme.onTertiaryContainer
        )
        LiveTaskStatus.FAILED -> TaskBadgeColors(
            containerColor = scheme.errorContainer,
            contentColor = scheme.onErrorContainer
        )
        LiveTaskStatus.CANCELLED -> TaskBadgeColors(
            containerColor = scheme.surfaceVariant,
            contentColor = scheme.onSurfaceVariant
        )
    }
}

private fun capabilityIcon(capabilityType: NovaTaskCapabilityType) = when (capabilityType) {
    NovaTaskCapabilityType.DEVICE_INFO -> Icons.Default.Memory
    NovaTaskCapabilityType.PHOTOS -> Icons.Default.PhotoLibrary
    NovaTaskCapabilityType.ACCESSIBILITY -> Icons.Default.TouchApp
    NovaTaskCapabilityType.GENERAL_ASSISTANT_TASK -> Icons.Default.PlayCircleOutline
}

private fun statusIcon(status: LiveTaskStatus) = when (status) {
    LiveTaskStatus.PENDING -> Icons.Default.HourglassTop
    LiveTaskStatus.AWAITING_CONFIRMATION -> Icons.Default.WarningAmber
    LiveTaskStatus.RUNNING -> Icons.Default.PlayCircleOutline
    LiveTaskStatus.COMPLETED_VERIFIED -> Icons.Default.CheckCircle
    LiveTaskStatus.PERMISSION_REQUIRED -> Icons.Default.Lock
    LiveTaskStatus.UNAVAILABLE -> Icons.Default.WarningAmber
    LiveTaskStatus.FAILED -> Icons.Default.ErrorOutline
    LiveTaskStatus.CANCELLED -> Icons.Default.Cancel
}
