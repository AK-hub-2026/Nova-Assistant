package com.example.nova.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nova.core.NovaUiOrbState
import com.example.nova.core.VerificationOutcome
import com.example.nova.core.WorkspaceCardState
import com.example.nova.memory.ConversationTurnEntity
import com.example.nova.tools.FullscreenPhotoLoadOutcome
import com.example.nova.tools.ThumbnailDecodeOutcome
import com.example.nova.voice.VoiceEngineState
import com.example.ui.theme.NovaOrbCyanGlow
import com.example.ui.theme.NovaOrbErrorCrimson
import com.example.ui.theme.NovaOrbSapphireCore
import com.example.ui.theme.NovaOrbUnavailableSlate

/**
 * M2 / M5 — Full-screen Nova Assistant experience.
 *
 * Replaces the developer dashboard with a minimal, adaptive full-screen assistant surface:
 * - Centered glowing Sapphire/Cyan Nova Orb ([NovaOrbCanvas])
 * - Real state-driven animations ([NovaUiOrbState]) and optional real microphone amplitude ([liveRmsDb])
 * - Automatic adaptive task workspace ([WorkspaceCardState]) that appears only when real task,
 *   confirmation, verification, device info, photo access, or error/unavailable data exists
 * - Minimal voice/text controls and Settings access, with zero developer telemetry on the main surface
 */
@Composable
fun NovaAssistantScreen(
    orbState: NovaUiOrbState,
    workspaceCardState: WorkspaceCardState,
    voiceState: VoiceEngineState,
    liveRmsDb: Float?,
    lowRamMode: Boolean,
    statusBanner: String,
    latestAssistantTurn: ConversationTurnEntity?,
    onMicClick: () -> Unit,
    onStopVoiceOrTask: () -> Unit,
    onSubmitTextCommand: (String) -> Unit,
    onConfirmPendingAction: () -> Unit,
    onRejectPendingAction: () -> Unit,
    onDismissWorkspaceCard: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestRecordAudioPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestMediaPermission: () -> Unit = {},
    onLaunchPhotoPicker: () -> Unit = {},
    onLoadPhotoThumbnail: suspend (String, Boolean) -> ThumbnailDecodeOutcome = { _, _ ->
        ThumbnailDecodeOutcome.PreviewUnavailable()
    },
    onLoadFullscreenPhoto: suspend (String, Boolean) -> FullscreenPhotoLoadOutcome = { _, _ ->
        FullscreenPhotoLoadOutcome.InaccessibleOrRevoked("Unavailable")
    },
    onCancelTask: (String) -> Unit = {},
    liveVoiceSessionState: com.example.nova.core.LiveVoiceSessionState = com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_IDLE,
    isLiveVoiceMuted: Boolean = false,
    onStartLiveVoiceSession: () -> Unit = onMicClick,
    onEndLiveVoiceSession: () -> Unit = onStopVoiceOrTask,
    onToggleMuteLiveVoiceSession: () -> Unit = {},
    onBargeIn: () -> Unit = onStopVoiceOrTask,
    modifier: Modifier = Modifier
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    val surfaceColor = MaterialTheme.colorScheme.background
    val primaryAccent = MaterialTheme.colorScheme.primary

    val isBusyOrListening = orbState == NovaUiOrbState.LISTENING ||
        orbState == NovaUiOrbState.EXECUTING ||
        orbState == NovaUiOrbState.SPEAKING ||
        liveVoiceSessionState != com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_IDLE

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(surfaceColor)
            .drawBehind {
                val radius = size.minDimension * 0.85f
                if (radius > 0f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                NovaOrbSapphireCore.copy(alpha = 0.12f),
                                NovaOrbCyanGlow.copy(alpha = 0.04f),
                                Color.Transparent
                            ),
                            center = Offset(size.width / 2f, size.height * 0.42f),
                            radius = radius
                        ),
                        radius = radius,
                        center = Offset(size.width / 2f, size.height * 0.42f)
                    )
                }
            }
            .testTag("nova_assistant_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Minimal Top Header: Brand Pill + State Indicator + Settings Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OrbStateBadge(orbState = orbState)

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("open_settings_button"),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Open Nova Settings"
                    )
                }
            }

            // 2. Centered Nova Orb & Real-Time State Headline + Adaptive Workspace Card
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (liveVoiceSessionState != com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_IDLE) {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 24.dp, vertical = 6.dp)
                            .testTag("live_voice_session_pill"),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isLiveVoiceMuted) Color.Red else Color(0xFF10B981))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isLiveVoiceMuted) "Microphone Muted" else "Live Session: ${liveVoiceSessionState.displayLabel}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            TextButton(
                                onClick = onEndLiveVoiceSession,
                                modifier = Modifier.testTag("end_live_session_button")
                            ) {
                                Text("End", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))

                NovaOrbCanvas(
                    orbState = orbState,
                    liveRmsDb = liveRmsDb,
                    lowRamMode = lowRamMode,
                    onOrbClick = {
                        if (liveVoiceSessionState == com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_SPEAKING) {
                            onBargeIn()
                        } else if (liveVoiceSessionState != com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_IDLE) {
                            onEndLiveVoiceSession()
                        } else if (isBusyOrListening) {
                            onStopVoiceOrTask()
                        } else {
                            onStartLiveVoiceSession()
                        }
                    },
                    orbSize = 248.dp
                )

                Spacer(modifier = Modifier.height(24.dp))

                val headlineText = resolveAssistantHeadline(
                    orbState = orbState,
                    voiceState = voiceState,
                    statusBanner = statusBanner,
                    latestAssistantTurn = latestAssistantTurn
                )

                Text(
                    text = headlineText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .testTag("nova_state_headline")
                )

                val secondaryHint = resolveAssistantSecondarySubtitle(
                    orbState = orbState,
                    voiceState = voiceState
                )
                if (secondaryHint.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = secondaryHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .testTag("nova_state_subtitle")
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 3. Automatic Adaptive Workspace Surface (Hidden when WorkspaceCardState.None)
                AnimatedVisibility(
                    visible = workspaceCardState !is WorkspaceCardState.None,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    AdaptiveWorkspaceCardHost(
                        workspaceCardState = workspaceCardState,
                        lowRamMode = lowRamMode,
                        onConfirmPendingAction = onConfirmPendingAction,
                        onRejectPendingAction = onRejectPendingAction,
                        onStopVoiceOrTask = onStopVoiceOrTask,
                        onDismissWorkspaceCard = onDismissWorkspaceCard,
                        onRequestRecordAudioPermission = onRequestRecordAudioPermission,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        onOpenSettings = onOpenSettings,
                        onRequestMediaPermission = onRequestMediaPermission,
                        onLaunchPhotoPicker = onLaunchPhotoPicker,
                        onLoadPhotoThumbnail = onLoadPhotoThumbnail,
                        onLoadFullscreenPhoto = onLoadFullscreenPhoto,
                        onRefreshAccessibilityScreen = { onSubmitTextCommand("read screen") },
                        onCancelTask = onCancelTask
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // 4. Minimal Bottom Voice & Command Bar
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .border(
                        width = 1.dp,
                        color = primaryAccent.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(32.dp)
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledIconToggleButton(
                        checked = isBusyOrListening,
                        onCheckedChange = {
                            if (isBusyOrListening) {
                                onStopVoiceOrTask()
                            } else {
                                onMicClick()
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("voice_mic_button"),
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = if (isBusyOrListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isBusyOrListening) {
                                "Stop listening or active task"
                            } else {
                                "Start voice listening"
                            }
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                text = "Ask Arya...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                val trimmed = inputText.trim()
                                if (trimmed.isNotEmpty()) {
                                    onSubmitTextCommand(trimmed)
                                    inputText = ""
                                }
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("command_input_field")
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    FilledIconButton(
                        onClick = {
                            val trimmed = inputText.trim()
                            if (trimmed.isNotEmpty()) {
                                onSubmitTextCommand(trimmed)
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank(),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("send_command_button"),
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send command"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OrbStateBadge(orbState: NovaUiOrbState) {
    val dotColor = when (orbState) {
        NovaUiOrbState.IDLE -> NovaOrbSapphireCore
        NovaUiOrbState.LISTENING -> NovaOrbCyanGlow
        NovaUiOrbState.THINKING -> NovaOrbCyanGlow
        NovaUiOrbState.EXECUTING -> NovaOrbSapphireCore
        NovaUiOrbState.SPEAKING -> NovaOrbCyanGlow
        NovaUiOrbState.ERROR -> NovaOrbErrorCrimson
        NovaUiOrbState.UNAVAILABLE -> NovaOrbUnavailableSlate
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
        modifier = Modifier.testTag("nova_orb_state_badge")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color = dotColor, shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "ARYA • आर्या",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = orbState.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun resolveAssistantHeadline(
    orbState: NovaUiOrbState,
    voiceState: VoiceEngineState,
    statusBanner: String,
    latestAssistantTurn: ConversationTurnEntity?
): String {
    return when (orbState) {
        NovaUiOrbState.LISTENING -> {
            val partial = (voiceState as? VoiceEngineState.Listening)?.partialTranscript.orEmpty()
            if (partial.isNotBlank()) "\"$partial\"" else "Listening..."
        }
        NovaUiOrbState.THINKING -> "Thinking..."
        NovaUiOrbState.EXECUTING -> statusBanner.ifBlank { "Executing action..." }
        NovaUiOrbState.SPEAKING -> {
            val utterance = (voiceState as? VoiceEngineState.Speaking)?.utterance.orEmpty()
            utterance.ifBlank { latestAssistantTurn?.messageText ?: "Speaking response..." }
        }
        NovaUiOrbState.ERROR -> statusBanner.ifBlank { "Action or provider error" }
        NovaUiOrbState.UNAVAILABLE -> {
            when (voiceState) {
                is VoiceEngineState.PermissionRequired -> "Microphone permission required"
                is VoiceEngineState.Unavailable -> voiceState.reason
                else -> statusBanner.ifBlank { "Capability or configuration required" }
            }
        }
        NovaUiOrbState.IDLE -> {
            latestAssistantTurn?.messageText?.takeIf { it.isNotBlank() }
                ?: "Tap the orb or enter a command"
        }
    }
}

private fun resolveAssistantSecondarySubtitle(
    orbState: NovaUiOrbState,
    voiceState: VoiceEngineState
): String {
    return when (orbState) {
        NovaUiOrbState.LISTENING -> "Tap the orb to finish or cancel"
        NovaUiOrbState.EXECUTING -> "Verifying real device state..."
        NovaUiOrbState.SPEAKING -> "Tap the orb to interrupt"
        NovaUiOrbState.ERROR -> (voiceState as? VoiceEngineState.Error)?.errorDescription.orEmpty()
        else -> ""
    }
}

@Composable
private fun AdaptiveWorkspaceCardHost(
    workspaceCardState: WorkspaceCardState,
    lowRamMode: Boolean,
    onConfirmPendingAction: () -> Unit,
    onRejectPendingAction: () -> Unit,
    onStopVoiceOrTask: () -> Unit,
    onDismissWorkspaceCard: () -> Unit,
    onRequestRecordAudioPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestMediaPermission: () -> Unit,
    onLaunchPhotoPicker: () -> Unit,
    onLoadPhotoThumbnail: suspend (String, Boolean) -> ThumbnailDecodeOutcome,
    onLoadFullscreenPhoto: suspend (String, Boolean) -> FullscreenPhotoLoadOutcome,
    onRefreshAccessibilityScreen: () -> Unit,
    onCancelTask: (String) -> Unit = {}
) {
    when (workspaceCardState) {
        WorkspaceCardState.None -> Unit

        is WorkspaceCardState.ConfirmationRequired -> {
            val request = workspaceCardState.request
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("confirmation_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = "Confirmation required",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Confirmation Required",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = request.warningReason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = onRejectPendingAction,
                            modifier = Modifier.testTag("cancel_action_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onConfirmPendingAction,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier.testTag("confirm_action_button")
                        ) {
                            Text("Confirm & Execute")
                        }
                    }
                }
            }
        }

        is WorkspaceCardState.ActiveActionExecution -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("active_execution_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = workspaceCardState.statusDescription.ifBlank { workspaceCardState.toolName },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Channel: ${workspaceCardState.channel.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = onStopVoiceOrTask,
                        modifier = Modifier.testTag("stop_execution_button")
                    ) {
                        Text("Stop")
                    }
                }
            }
        }

        is WorkspaceCardState.VerifiedActionSummary -> {
            val result = workspaceCardState.result
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("verified_summary_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
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
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Verified action outcome",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${result.toolName} • ${result.outcome.name}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        IconButton(
                            onClick = onDismissWorkspaceCard,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("dismiss_workspace_card_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss workspace card"
                            )
                        }
                    }
                    if (result.userFacingMessage.isNotBlank()) {
                        Text(
                            text = result.userFacingMessage,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (result.verificationDetail.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = result.verificationDetail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        is WorkspaceCardState.ErrorOrUnavailableWorkspace -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("error_unavailable_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "Capability or error notice",
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = workspaceCardState.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(
                            onClick = onDismissWorkspaceCard,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("dismiss_workspace_card_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss notice"
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = workspaceCardState.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        when (workspaceCardState.outcome) {
                            VerificationOutcome.PERMISSION_REQUIRED -> {
                                Button(
                                    onClick = onRequestRecordAudioPermission,
                                    modifier = Modifier.testTag("workspace_action_permission_button")
                                ) {
                                    Text("Grant Permission")
                                }
                            }
                            VerificationOutcome.SERVICE_DISABLED -> {
                                Button(
                                    onClick = onOpenAccessibilitySettings,
                                    modifier = Modifier.testTag("workspace_action_service_button")
                                ) {
                                    Text("Accessibility Settings")
                                }
                            }
                            VerificationOutcome.CONFIGURATION_REQUIRED -> {
                                Button(
                                    onClick = onOpenSettings,
                                    modifier = Modifier.testTag("workspace_action_config_button")
                                ) {
                                    Text("Open Settings")
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }

        is WorkspaceCardState.DeviceInfoWorkspace -> {
            DeviceInfoWorkspaceCard(
                deviceInfo = workspaceCardState.deviceInfo,
                onDismiss = onDismissWorkspaceCard
            )
        }

        is WorkspaceCardState.PhotoWorkspace -> {
            PhotoWorkspaceCard(
                photoState = workspaceCardState.photoState,
                lowRamMode = lowRamMode,
                onLoadThumbnail = onLoadPhotoThumbnail,
                onLoadFullscreenPhoto = onLoadFullscreenPhoto,
                onRequestMediaPermission = onRequestMediaPermission,
                onLaunchPhotoPicker = onLaunchPhotoPicker,
                onDismiss = onDismissWorkspaceCard
            )
        }

        is WorkspaceCardState.AccessibilityWorkspace -> {
            AccessibilityWorkspaceCard(
                workspaceState = workspaceCardState.workspaceState,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onRefreshScreen = onRefreshAccessibilityScreen,
                onDismiss = onDismissWorkspaceCard
            )
        }

        is WorkspaceCardState.MultiTaskWorkspace -> {
            MultiTaskWorkspaceCard(
                tasks = workspaceCardState.tasks,
                lowRamMode = lowRamMode,
                deviceInfo = workspaceCardState.deviceInfo,
                photoState = workspaceCardState.photoState,
                accessibilityWorkspaceState = workspaceCardState.accessibilityWorkspaceState,
                onCancelTask = onCancelTask,
                onCancelAllTasks = onStopVoiceOrTask,
                onDismiss = onDismissWorkspaceCard,
                onRequestMediaPermission = onRequestMediaPermission,
                onLaunchPhotoPicker = onLaunchPhotoPicker,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onRefreshAccessibilityScreen = onRefreshAccessibilityScreen,
                onLoadPhotoThumbnail = onLoadPhotoThumbnail,
                onLoadFullscreenPhoto = onLoadFullscreenPhoto
            )
        }
    }
}
