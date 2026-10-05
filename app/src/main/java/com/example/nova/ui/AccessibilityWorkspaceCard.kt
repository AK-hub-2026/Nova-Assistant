package com.example.nova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.nova.core.AccessibilityTarget
import com.example.nova.core.AccessibilityWorkspaceState
import com.example.nova.core.AccessibilityWorkspaceStatus
import com.example.nova.core.VerificationOutcome

/**
 * Pure display model formatted from [AccessibilityWorkspaceState] for deterministic UI rendering
 * and unit test verification.
 */
data class AccessibilityWorkspaceDisplaySummary(
    val headlineText: String,
    val statusBadgeText: String,
    val serviceConnectionText: String,
    val activePackageText: String,
    val activeWindowAvailabilityText: String,
    val semanticTargetCountText: String,
    val interactiveBreakdownText: String,
    val actionStateText: String,
    val verifiedResultText: String,
    val visionFallbackText: String,
    val requiresSettingsAction: Boolean
)

/**
 * Formats an [AccessibilityWorkspaceState] into privacy-safe, non-fabricated display strings.
 *
 * Enforces:
 * - Never reports connected when `serviceConnected == false`, even if `serviceEnabledInSettings == true`.
 * - Active package is `"Unavailable"` when not provided by real `AccessibilityEvent` / `rootInActiveWindow`.
 * - Never exposes raw unredacted trees or developer telemetry.
 */
fun formatAccessibilityWorkspaceSummary(
    state: AccessibilityWorkspaceState
): AccessibilityWorkspaceDisplaySummary {
    val headline = when (state.status) {
        AccessibilityWorkspaceStatus.SERVICE_DISABLED ->
            "Accessibility access required"
        AccessibilityWorkspaceStatus.SERVICE_NOT_CONNECTED ->
            "Accessibility service not connected"
        AccessibilityWorkspaceStatus.SERVICE_CONNECTED ->
            "Accessibility Service Connected"
        AccessibilityWorkspaceStatus.ACTIVE_WINDOW_AVAILABLE ->
            "Active Window Ready"
        AccessibilityWorkspaceStatus.ACTIVE_WINDOW_UNAVAILABLE ->
            "Active Window Unavailable"
        AccessibilityWorkspaceStatus.ACTION_IN_PROGRESS ->
            "Accessibility Action in Progress"
        AccessibilityWorkspaceStatus.ACTION_VERIFIED ->
            "Accessibility Action Verified"
        AccessibilityWorkspaceStatus.ACTION_FAILED ->
            "Accessibility Action Failed"
        AccessibilityWorkspaceStatus.PERMISSION_REQUIRED ->
            "Accessibility Permission Required"
        AccessibilityWorkspaceStatus.UNSUPPORTED ->
            "Capability Unsupported"
        AccessibilityWorkspaceStatus.ERROR ->
            "Accessibility Error"
    }

    val connectionText = when {
        state.serviceConnected -> "Connected (Bound by Android OS)"
        state.serviceEnabledInSettings -> "Enabled in Settings • Not Connected"
        else -> "Disabled in Android Settings"
    }

    val pkgText = state.activePackage.valueOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "Unavailable" && it != "unknown.package" }
        ?: "Unavailable"

    val windowAvailText = if (state.serviceConnected && state.activeWindowAvailable) {
        "Available"
    } else {
        "Unavailable"
    }

    val targetCountText = if (state.serviceConnected && state.activeWindowAvailable) {
        "${state.semanticTargets.size}"
    } else {
        "0"
    }

    val breakdownText = if (state.serviceConnected && state.activeWindowAvailable) {
        "${state.clickableTargetCount} clickable • ${state.editableTargetCount} editable • ${state.scrollableTargetCount} scrollable"
    } else {
        "Unavailable"
    }

    val actionStateText = when {
        state.status == AccessibilityWorkspaceStatus.ACTION_IN_PROGRESS ->
            state.currentActionDescription.ifBlank { "Executing action..." }
        state.latestActionResult != null ->
            "${state.latestActionResult.toolName} • ${state.latestActionResult.outcome.name}"
        else ->
            state.status.name
    }

    val verifiedText = state.latestActionResult?.let { res ->
        res.userFacingMessage.ifBlank { res.verificationDetail }
    } ?: state.statusMessage.ifBlank { headline }

    val requiresSettings = !state.serviceConnected ||
        state.status == AccessibilityWorkspaceStatus.SERVICE_DISABLED ||
        state.status == AccessibilityWorkspaceStatus.SERVICE_NOT_CONNECTED ||
        state.status == AccessibilityWorkspaceStatus.PERMISSION_REQUIRED

    return AccessibilityWorkspaceDisplaySummary(
        headlineText = headline,
        statusBadgeText = state.status.name,
        serviceConnectionText = connectionText,
        activePackageText = pkgText,
        activeWindowAvailabilityText = windowAvailText,
        semanticTargetCountText = targetCountText,
        interactiveBreakdownText = breakdownText,
        actionStateText = actionStateText,
        verifiedResultText = verifiedText,
        visionFallbackText = state.visionFallbackStatus.name,
        requiresSettingsAction = requiresSettings
    )
}

/**
 * M6 — Real Contextual Accessibility Workspace Card.
 *
 * Displays truthful service connection state, active foreground package, active window availability,
 * bounded semantic target summary, post-action verification outcome, and a real button to launch
 * Android Accessibility Settings when disabled or disconnected.
 */
@Composable
fun AccessibilityWorkspaceCard(
    workspaceState: AccessibilityWorkspaceState,
    onOpenAccessibilitySettings: () -> Unit,
    onRefreshScreen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val summary = remember(workspaceState) {
        formatAccessibilityWorkspaceSummary(workspaceState)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("accessibility_workspace_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 1. Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccessibilityNew,
                            contentDescription = "Accessibility Workspace",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = summary.headlineText,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("accessibility_workspace_headline")
                        )
                        Text(
                            text = summary.statusBadgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusAccentColor(workspaceState.status),
                            modifier = Modifier.testTag("accessibility_workspace_status_badge")
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("dismiss_accessibility_workspace_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss accessibility workspace"
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Service Disabled / Not Connected State
            if (!workspaceState.serviceConnected) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("accessibility_disabled_notice")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = "Accessibility access required",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = summary.serviceConnectionText,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = workspaceState.statusMessage.ifBlank {
                                "Enable Nova in Android Accessibility Settings so Nova can read the active window and perform verified actions."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = onOpenAccessibilitySettings,
                                modifier = Modifier.testTag("accessibility_open_settings_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open Accessibility Settings")
                            }
                        }
                    }
                }
            } else {
                // 3. Connected State: Active Package, Window Availability & Target Count
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("accessibility_connected_summary")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AccessibilityInfoRow(
                            label = "Service Status",
                            value = summary.serviceConnectionText,
                            testTag = "accessibility_row_connection"
                        )
                        AccessibilityInfoRow(
                            label = "Active Package",
                            value = summary.activePackageText,
                            testTag = "accessibility_row_active_package"
                        )
                        AccessibilityInfoRow(
                            label = "Active Window",
                            value = summary.activeWindowAvailabilityText,
                            testTag = "accessibility_row_window_available"
                        )
                        AccessibilityInfoRow(
                            label = "Semantic Targets",
                            value = "${summary.semanticTargetCountText} (${summary.interactiveBreakdownText})",
                            testTag = "accessibility_row_target_count"
                        )
                        if (workspaceState.redactedFieldCount > 0) {
                            AccessibilityInfoRow(
                                label = "Privacy Redaction",
                                value = "${workspaceState.redactedFieldCount} sensitive field(s) masked",
                                testTag = "accessibility_row_redacted_count"
                            )
                        }
                    }
                }

                // 4. Ambiguous Targets Clarification Section (if action refused to guess)
                if (workspaceState.ambiguousCandidates.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    AmbiguousTargetsClarificationSection(
                        candidates = workspaceState.ambiguousCandidates
                    )
                } else if (workspaceState.semanticTargets.isNotEmpty()) {
                    // 5. Bounded preview of top actionable semantic targets (max 5, redacted)
                    Spacer(modifier = Modifier.height(10.dp))
                    TopSemanticTargetsPreview(
                        targets = workspaceState.semanticTargets.take(5)
                    )
                }

                // 6. Verified Action Result / Current Action State
                if (workspaceState.latestActionResult != null ||
                    workspaceState.status == AccessibilityWorkspaceStatus.ACTION_IN_PROGRESS
                ) {
                    Spacer(modifier = Modifier.height(10.dp))
                    LatestAccessibilityActionSection(
                        summary = summary,
                        latestOutcome = workspaceState.latestActionResult?.outcome
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(8.dp))

                // 7. Footer: Vision Fallback Extension Status + Refresh / Settings Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Vision Fallback: ${summary.visionFallbackText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("accessibility_vision_fallback_status")
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onOpenAccessibilitySettings,
                            modifier = Modifier.testTag("accessibility_open_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Open Accessibility Settings",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Settings")
                        }
                        Button(
                            onClick = onRefreshScreen,
                            modifier = Modifier.testTag("accessibility_refresh_screen_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh screen snapshot",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Refresh")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessibilityInfoRow(
    label: String,
    value: String,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AmbiguousTargetsClarificationSection(
    candidates: List<AccessibilityTarget>
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("accessibility_ambiguous_targets_section")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Clarification Required (${candidates.size} Matching Targets)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            candidates.take(5).forEach { target ->
                Text(
                    text = "• [#${target.targetIndex}] ${target.semanticDescription}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TopSemanticTargetsPreview(
    targets: List<AccessibilityTarget>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(10.dp)
            .testTag("accessibility_top_targets_preview"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Key Semantic Targets (Privacy-Redacted)",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        targets.forEach { target ->
            val capabilityBadges = buildList {
                if (!target.isEnabled) add("Disabled")
                if (target.isClickable) add("Click")
                if (target.isEditable) add("Edit")
                if (target.isScrollable) add("Scroll")
            }.joinToString("·")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "[#${target.targetIndex}] ${target.semanticDescription}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (capabilityBadges.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = capabilityBadges,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun LatestAccessibilityActionSection(
    summary: AccessibilityWorkspaceDisplaySummary,
    latestOutcome: VerificationOutcome?
) {
    val isVerifiedSuccess = latestOutcome == VerificationOutcome.VERIFIED_SUCCESS
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("accessibility_action_verification_section")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isVerifiedSuccess) {
                        Icons.Default.CheckCircle
                    } else {
                        Icons.Default.ErrorOutline
                    },
                    contentDescription = "Action verification status",
                    tint = if (isVerifiedSuccess) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = summary.actionStateText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = summary.verifiedResultText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("accessibility_verified_result_text")
            )
        }
    }
}

@Composable
private fun statusAccentColor(status: AccessibilityWorkspaceStatus): Color {
    return when (status) {
        AccessibilityWorkspaceStatus.SERVICE_CONNECTED,
        AccessibilityWorkspaceStatus.ACTIVE_WINDOW_AVAILABLE,
        AccessibilityWorkspaceStatus.ACTION_VERIFIED -> MaterialTheme.colorScheme.primary

        AccessibilityWorkspaceStatus.ACTION_IN_PROGRESS -> MaterialTheme.colorScheme.tertiary

        AccessibilityWorkspaceStatus.SERVICE_DISABLED,
        AccessibilityWorkspaceStatus.SERVICE_NOT_CONNECTED,
        AccessibilityWorkspaceStatus.ACTIVE_WINDOW_UNAVAILABLE,
        AccessibilityWorkspaceStatus.PERMISSION_REQUIRED,
        AccessibilityWorkspaceStatus.UNSUPPORTED -> MaterialTheme.colorScheme.secondary

        AccessibilityWorkspaceStatus.ACTION_FAILED,
        AccessibilityWorkspaceStatus.ERROR -> MaterialTheme.colorScheme.error
    }
}
