package com.example.nova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.nova.core.AssistantSessionStage
import com.example.nova.core.PendingConfirmationRequest
import com.example.nova.core.SystemHealthSnapshot
import com.example.nova.core.VerificationOutcome
import com.example.nova.memory.ConversationTurnEntity
import com.example.nova.memory.NovaRuntimeSettings
import com.example.nova.voice.VoiceEngineState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AssistantConsoleScreen(
    turns: List<ConversationTurnEntity>,
    voiceState: VoiceEngineState,
    sessionStage: AssistantSessionStage,
    statusBanner: String,
    pendingConfirmation: PendingConfirmationRequest?,
    health: SystemHealthSnapshot,
    settings: NovaRuntimeSettings,
    onMicClick: () -> Unit,
    onStopVoiceOrTask: () -> Unit,
    onSubmitTextCommand: (String) -> Unit,
    onConfirmPending: () -> Unit,
    onRejectPending: () -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Live System & Provider Status Header
        StatusHeaderCard(
            statusBanner = statusBanner,
            sessionStage = sessionStage,
            voiceState = voiceState,
            health = health,
            settings = settings,
            onMicClick = onMicClick,
            onStopClick = onStopVoiceOrTask,
            onClearHistory = onClearHistory
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Sensitive Action Confirmation Gate Banner (if active)
        if (pendingConfirmation != null) {
            ConfirmationGateCard(
                request = pendingConfirmation,
                onConfirm = onConfirmPending,
                onCancel = onRejectPending
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Quick Verified Action Chips
        QuickActionRow(onCommandSelected = onSubmitTextCommand)

        Spacer(modifier = Modifier.height(8.dp))

        // Conversation & Real Verification Feed
        Box(modifier = Modifier.weight(1f)) {
            if (turns.isEmpty()) {
                EmptyConversationState(health = health, settings = settings)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("conversation_feed_list"),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(turns, key = { it.id }) { turn ->
                        ConversationTurnCard(turn = turn)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Command Input Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text("Ask Nova or enter a device command...")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (inputText.isNotBlank()) {
                            onSubmitTextCommand(inputText)
                            inputText = ""
                        }
                    }
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("command_input_field"),
                shape = RoundedCornerShape(24.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            FilledIconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSubmitTextCommand(inputText)
                        inputText = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("send_command_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send command"
                )
            }
        }
    }
}

@Composable
private fun StatusHeaderCard(
    statusBanner: String,
    sessionStage: AssistantSessionStage,
    voiceState: VoiceEngineState,
    health: SystemHealthSnapshot,
    settings: NovaRuntimeSettings,
    onMicClick: () -> Unit,
    onStopClick: () -> Unit,
    onClearHistory: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_header_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NOVA ASSISTANT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = statusBanner,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val voiceSubtitle = when (voiceState) {
                        is VoiceEngineState.Listening ->
                            if (voiceState.partialTranscript.isNotBlank()) {
                                "Listening: \"${voiceState.partialTranscript}\""
                            } else {
                                "Listening for speech..."
                            }
                        is VoiceEngineState.Speaking -> "Speaking: \"${voiceState.utterance.take(55)}\""
                        is VoiceEngineState.PermissionRequired -> "Microphone permission required for voice input"
                        is VoiceEngineState.Unavailable -> voiceState.reason
                        is VoiceEngineState.Error -> voiceState.errorDescription
                        VoiceEngineState.Idle ->
                            "Provider: ${settings.primaryProvider.displayName} • RAM: ${health.availableRamMb}/${health.totalRamMb} MB"
                    }
                    Text(
                        text = voiceSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onClearHistory,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("clear_conversation_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Clear conversation history"
                        )
                    }

                    val isBusyOrListening = voiceState is VoiceEngineState.Listening ||
                        voiceState is VoiceEngineState.Speaking ||
                        sessionStage == AssistantSessionStage.EXECUTING_AND_VERIFYING

                    FilledIconButton(
                        onClick = if (isBusyOrListening) onStopClick else onMicClick,
                        modifier = Modifier
                            .size(52.dp)
                            .testTag("voice_mic_button"),
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = if (isBusyOrListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isBusyOrListening) "Stop voice or task" else "Start voice input"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmationGateCard(
    request: PendingConfirmationRequest,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("confirmation_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = "Sensitive action warning",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Confirm Sensitive Action",
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
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("cancel_action_button")
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onConfirm,
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

@Composable
private fun QuickActionRow(onCommandSelected: (String) -> Unit) {
    val quickCommands = listOf(
        "Read Screen" to "read screen",
        "Volume Up" to "volume up",
        "Volume Down" to "volume down",
        "Open Settings" to "open system settings",
        "Accessibility Settings" to "open accessibility settings",
        "Set Alarm 08:00" to "set alarm for 8:00",
        "Flashlight On" to "flashlight on"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        quickCommands.forEach { (label, cmd) ->
            AssistChip(
                onClick = { onCommandSelected(cmd) },
                label = { Text(label) },
                modifier = Modifier.testTag("quick_chip_${label.lowercase().replace(' ', '_')}")
            )
        }
    }
}

@Composable
private fun EmptyConversationState(
    health: SystemHealthSnapshot,
    settings: NovaRuntimeSettings
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .testTag("empty_conversation_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Zero Mock Data • Ready for Real Commands",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Nova executes and verifies every action against the real Android OS before reporting completion. Try commands like:\n" +
                    "• \"open Settings\" or \"open <installed app>\"\n" +
                    "• \"volume up\", \"mute\", or \"set alarm for 7:30\"\n" +
                    "• \"remember that my preferred browser is Chrome\"\n" +
                    "• \"read screen\" or \"click <button label>\" (requires Nova Accessibility Service)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            val keyStatus = when (settings.primaryProvider) {
                com.example.nova.core.AiProviderType.GEMINI ->
                    if (health.geminiKeyConfigured) "Gemini API Key: Configured" else "Gemini API Key: Not set in AI Studio Secrets (.env) — Offline commands active"
                com.example.nova.core.AiProviderType.GROQ ->
                    if (health.groqKeyConfigured) "Groq API Key: Configured" else "Groq API Key: Not set in AI Studio Secrets (.env) — Offline commands active"
                com.example.nova.core.AiProviderType.OPENAI_COMPATIBLE ->
                    if (health.openAiKeyConfigured) "OpenAI API Key: Configured" else "OpenAI API Key: Not set in AI Studio Secrets (.env) — Offline commands active"
                com.example.nova.core.AiProviderType.OFFLINE_DETERMINISTIC ->
                    "Mode: Local Deterministic Parser (100% Offline)"
            }
            Text(
                text = keyStatus,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ConversationTurnCard(turn: ConversationTurnEntity) {
    val isUser = turn.role == "USER"
    val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val timeStr = timeFormatter.format(Date(turn.timestampMs))

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isUser) "You" else "Nova (${turn.providerUsed.ifBlank { "System" }})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = turn.messageText,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (!isUser && turn.verificationStatus.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val isSuccess = turn.verificationStatus == VerificationOutcome.VERIFIED_SUCCESS.name
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                            contentDescription = "Verification outcome",
                            tint = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "Tool: ${turn.toolCalled.ifBlank { "none" }} • ${turn.verificationStatus}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (turn.verificationDetail.isNotBlank()) {
                                Text(
                                    text = turn.verificationDetail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
