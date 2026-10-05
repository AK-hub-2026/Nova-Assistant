package com.example.nova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nova.core.AccessibilityConnectionDistinction
import com.example.nova.core.AccessibilitySettingsSectionState
import com.example.nova.core.AdvancedSettingsSectionState
import com.example.nova.core.AiProviderConfigurationStatus
import com.example.nova.core.AiProviderSettingsSectionState
import com.example.nova.core.AiProviderType
import com.example.nova.core.AutomationSettingsSectionState
import com.example.nova.core.DiagnosticsSettingsSectionState
import com.example.nova.core.ElevenLabsVoiceState
import com.example.nova.core.MemorySettingsSectionState
import com.example.nova.core.NovaPermissionStatus
import com.example.nova.core.NovaProcessingLocalityMode
import com.example.nova.core.NovaSettingsSection
import com.example.nova.core.NovaSettingsState
import com.example.nova.core.OverlayAttachmentState
import com.example.nova.core.PermissionsSettingsSectionState
import com.example.nova.core.PrivacySettingsSectionState
import com.example.nova.core.SystemHealthSnapshot
import com.example.nova.core.VoiceCapabilityStatus
import com.example.nova.core.VoiceProviderSettingsSectionState
import com.example.nova.memory.MemoryFactEntity
import com.example.nova.memory.NovaRuntimeSettings
import com.example.nova.security.VoiceEnrollmentStatus
import com.example.nova.security.WakeWordCapabilityAuditor

/**
 * M10 — Simplified, uncluttered Arya Settings & Control Center.
 *
 * Master-Detail Architecture:
 * - Main Surface: Clean, readable list of 10 primary control items:
 *   1. Voice & Wake Word
 *   2. AI API
 *   3. Voice API (ElevenLabs)
 *   4. Accessibility
 *   5. Permissions
 *   6. Privacy
 *   7. Memory
 *   8. Automation
 *   9. Advanced
 *   10. Diagnostics
 * - Tapping any section opens its dedicated detail screen with specific controls,
 *   masked API keys, testing actions, and verified official documentation links.
 * - 0% plaintext secret exposure.
 * - 0% fake telemetry on the main settings screen.
 */
@Composable
fun SettingsAndLicensesScreen(
    settings: NovaRuntimeSettings,
    health: SystemHealthSnapshot,
    overlayAttachmentState: OverlayAttachmentState = OverlayAttachmentState.SERVICE_NOT_BOUND,
    settingsState: NovaSettingsState? = null,
    savedMemoryFacts: List<MemoryFactEntity> = emptyList(),
    onSelectSection: (NovaSettingsSection) -> Unit = {},
    onSelectProvider: (AiProviderType) -> Unit = {},
    onToggleCloudFallback: (Boolean) -> Unit = {},
    onSelectGeminiModel: (String) -> Unit = {},
    onSelectGroqModel: (String) -> Unit = {},
    onSelectOpenAiModel: (String) -> Unit = {},
    onToggleSpokenResponses: (Boolean) -> Unit = {},
    onToggleRequireConfirmation: (Boolean) -> Unit = {},
    onToggleAccessibilityHud: (Boolean) -> Unit = {},
    onToggleMemoryContext: (Boolean) -> Unit = {},
    onSelectLowRamOverride: (Boolean?) -> Unit = {},
    onRequestRecordAudioPermission: () -> Unit = {},
    onRequestNotificationsPermission: () -> Unit = {},
    onRequestMediaPermission: () -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit = {},
    onOpenNotificationListenerSettings: () -> Unit = {},
    onOpenAppDetailsSettings: () -> Unit = {},
    onOpenExternalUrl: (String) -> Unit = {},
    onClearConversationHistory: () -> Unit = {},
    onClearMemoryFacts: () -> Unit = {},
    onClearAuditLogs: () -> Unit = {},
    onRefreshDiagnostics: () -> Unit = {},
    onCloseSettings: () -> Unit = {},
    onSaveProviderSecret: (AiProviderType, String) -> Unit = { _, _ -> },
    onSaveElevenLabsSecret: (String) -> Unit = { _ -> },
    onTestElevenLabsVoice: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var activeDetailSection by rememberSaveable { mutableStateOf<NovaSettingsSection?>(null) }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("nova_settings_control_center"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            if (activeDetailSection == null) {
                // Main Clean Settings Surface
                MainSettingsListHeader(onCloseSettings = onCloseSettings)
                MainSettingsSectionList(
                    onOpenSection = { section ->
                        activeDetailSection = section
                        onSelectSection(section)
                    }
                )
            } else {
                // Dedicated Detail Screen for the Selected Section
                DetailScreenHeader(
                    section = activeDetailSection!!,
                    onBack = { activeDetailSection = null }
                )
                Box(modifier = Modifier.weight(1f)) {
                    when (activeDetailSection!!) {
                        NovaSettingsSection.VOICE_WAKE_WORD -> VoiceAndWakeWordDetailScreen(
                            health = health,
                            spokenResponsesEnabled = settings.spokenResponsesEnabled,
                            onToggleSpokenResponses = onToggleSpokenResponses,
                            onRequestRecordAudioPermission = onRequestRecordAudioPermission,
                            onOpenAppDetails = onOpenAppDetailsSettings
                        )
                        NovaSettingsSection.AI_API -> AiApiDetailScreen(
                            aiSection = settingsState?.aiProviderSection,
                            settings = settings,
                            onSelectProvider = onSelectProvider,
                            onToggleCloudFallback = onToggleCloudFallback,
                            onSelectGeminiModel = onSelectGeminiModel,
                            onSelectGroqModel = onSelectGroqModel,
                            onSelectOpenAiModel = onSelectOpenAiModel,
                            onSaveSecret = onSaveProviderSecret,
                            onOpenUrl = onOpenExternalUrl
                        )
                        NovaSettingsSection.VOICE_API -> VoiceApiDetailScreen(
                            voiceApiState = settingsState?.voiceApiSection,
                            spokenResponsesEnabled = settings.spokenResponsesEnabled,
                            onToggleSpokenResponses = onToggleSpokenResponses,
                            onSaveElevenLabsSecret = onSaveElevenLabsSecret,
                            onTestVoice = onTestElevenLabsVoice,
                            onOpenUrl = onOpenExternalUrl
                        )
                        NovaSettingsSection.ACCESSIBILITY -> AccessibilityDetailScreen(
                            state = settingsState?.accessibilitySection,
                            showHud = settings.showAccessibilityFloatingHud,
                            onToggleHud = onToggleAccessibilityHud,
                            onOpenSettings = onOpenAccessibilitySettings
                        )
                        NovaSettingsSection.PERMISSIONS -> PermissionsDetailScreen(
                            state = settingsState?.permissionsSection,
                            onRequestRecordAudioPermission = onRequestRecordAudioPermission,
                            onRequestNotificationsPermission = onRequestNotificationsPermission,
                            onRequestMediaPermission = onRequestMediaPermission,
                            onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                            onOpenAppDetails = onOpenAppDetailsSettings
                        )
                        NovaSettingsSection.PRIVACY -> PrivacyDetailScreen(
                            state = settingsState?.privacySection,
                            requireConfirmation = settings.requireConfirmationForSensitiveActions,
                            onToggleRequireConfirmation = onToggleRequireConfirmation
                        )
                        NovaSettingsSection.MEMORY -> MemoryDetailScreen(
                            state = settingsState?.memorySection,
                            savedMemoryFacts = savedMemoryFacts,
                            memoryContextEnabled = settings.memoryContextInjectionEnabled,
                            onToggleMemoryContext = onToggleMemoryContext,
                            onClearMemoryFacts = onClearMemoryFacts,
                            onClearHistory = onClearConversationHistory
                        )
                        NovaSettingsSection.AUTOMATION -> AutomationDetailScreen(
                            state = settingsState?.automationSection,
                            requireConfirmation = settings.requireConfirmationForSensitiveActions,
                            onToggleRequireConfirmation = onToggleRequireConfirmation,
                            onClearAuditLogs = onClearAuditLogs
                        )
                        NovaSettingsSection.ADVANCED -> AdvancedDetailScreen(
                            state = settingsState?.advancedSection,
                            lowRamOverride = settings.lowRamModeOverride,
                            onSelectLowRamOverride = onSelectLowRamOverride,
                            onSelectGeminiModel = onSelectGeminiModel,
                            onSelectGroqModel = onSelectGroqModel,
                            onSelectOpenAiModel = onSelectOpenAiModel
                        )
                        NovaSettingsSection.DIAGNOSTICS -> DiagnosticsDetailScreen(
                            state = settingsState?.diagnosticsSection,
                            onRefresh = onRefreshDiagnostics
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// MAIN SETTINGS LIST
// ------------------------------------------------------------------------------------------------

@Composable
private fun MainSettingsListHeader(onCloseSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Arya",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Settings & Control Center",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = onCloseSettings,
            modifier = Modifier.testTag("settings_close_button")
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close Settings",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private data class SettingsMenuEntry(
    val section: NovaSettingsSection,
    val icon: ImageVector,
    val tag: String
)

@Composable
private fun MainSettingsSectionList(
    onOpenSection: (NovaSettingsSection) -> Unit
) {
    val entries = listOf(
        SettingsMenuEntry(NovaSettingsSection.VOICE_WAKE_WORD, Icons.Default.Mic, "menu_item_voice_wake_word"),
        SettingsMenuEntry(NovaSettingsSection.AI_API, Icons.Default.AutoAwesome, "menu_item_ai_api"),
        SettingsMenuEntry(NovaSettingsSection.VOICE_API, Icons.Default.RecordVoiceOver, "menu_item_voice_api"),
        SettingsMenuEntry(NovaSettingsSection.ACCESSIBILITY, Icons.Default.AccessibilityNew, "menu_item_accessibility"),
        SettingsMenuEntry(NovaSettingsSection.PERMISSIONS, Icons.Default.Security, "menu_item_permissions"),
        SettingsMenuEntry(NovaSettingsSection.PRIVACY, Icons.Default.Lock, "menu_item_privacy"),
        SettingsMenuEntry(NovaSettingsSection.MEMORY, Icons.Default.Storage, "menu_item_memory"),
        SettingsMenuEntry(NovaSettingsSection.AUTOMATION, Icons.Default.TaskAlt, "menu_item_automation"),
        SettingsMenuEntry(NovaSettingsSection.ADVANCED, Icons.Default.Tune, "menu_item_advanced"),
        SettingsMenuEntry(NovaSettingsSection.DIAGNOSTICS, Icons.Default.BugReport, "menu_item_diagnostics")
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(entries) { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSection(item.section) }
                    .testTag(item.tag),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.section.displayTitle,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.section.displayTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = item.section.shortSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = "Navigate to ${item.section.displayTitle}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailScreenHeader(
    section: NovaSettingsSection,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("detail_screen_back_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to Settings",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = section.displayTitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

// ------------------------------------------------------------------------------------------------
// DETAIL SCREENS
// ------------------------------------------------------------------------------------------------

@Composable
private fun VoiceAndWakeWordDetailScreen(
    health: SystemHealthSnapshot,
    spokenResponsesEnabled: Boolean,
    onToggleSpokenResponses: (Boolean) -> Unit,
    onRequestRecordAudioPermission: () -> Unit,
    onOpenAppDetails: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Assistant Identity",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Name: Arya (आर्या)", style = MaterialTheme.typography.bodyMedium)
                    Text("Wake Phrase Concept: 'Hey Arya'", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Note: Changing the display name does not automatically change Android hardware hotword triggers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Personal Voice Enrollment",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Status: ${VoiceEnrollmentStatus.VOICE_VERIFICATION_NOT_IMPLEMENTED.displayLabel}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Voice verification is not bundled in this build. Explicit speech recognition operates in conversational live session mode with 0% fake biometric hashes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Android Assistant Role & Hotword Audit",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "VoiceInteractionService: Declared in Manifest (DECLARED_NOT_BOUND by OS)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Hardware Hotword DSP: Unsupported without privileged OS binding",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Microphone Hardware: ${if (health.speechRecognitionAvailable) "Detected" else "Unavailable"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Spoken Audio Responses",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Switch(
                    checked = spokenResponsesEnabled,
                    onCheckedChange = onToggleSpokenResponses
                )
            }
        }
    }
}

@Composable
private fun AiApiDetailScreen(
    aiSection: AiProviderSettingsSectionState?,
    settings: NovaRuntimeSettings,
    onSelectProvider: (AiProviderType) -> Unit,
    onToggleCloudFallback: (Boolean) -> Unit,
    onSelectGeminiModel: (String) -> Unit,
    onSelectGroqModel: (String) -> Unit,
    onSelectOpenAiModel: (String) -> Unit,
    onSaveSecret: (AiProviderType, String) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    var editingKeyText by rememberSaveable { mutableStateOf("") }
    var keySavedMessage by rememberSaveable { mutableStateOf("") }

    val activeItem = aiSection?.providers?.firstOrNull { it.providerType == settings.primaryProvider }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Active AI Provider",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Selected: ${settings.primaryProvider.displayName}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Status: ${activeItem?.runtimeStatus?.name ?: "UNKNOWN"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (activeItem?.isKeyConfigured == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text("API Key: ${activeItem?.safeSecretStatusLabel ?: "API key required"}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Text(
                text = "Select Provider",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AiProviderType.entries.forEach { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectProvider(p) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.primaryProvider == p,
                            onClick = { onSelectProvider(p) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(p.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (settings.primaryProvider != AiProviderType.OFFLINE_DETERMINISTIC) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Configure API Key",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Stored securely in Android Keystore vault. Never exposed in plaintext.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editingKeyText,
                            onValueChange = { editingKeyText = it },
                            label = { Text("Enter ${settings.primaryProvider.displayName} API Key") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("provider_api_key_input")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (editingKeyText.isNotBlank()) {
                                        onSaveSecret(settings.primaryProvider, editingKeyText.trim())
                                        editingKeyText = ""
                                        keySavedMessage = "API key securely saved to Android Keystore vault."
                                    }
                                },
                                modifier = Modifier.testTag("save_api_key_button")
                            ) {
                                Text("Save Key")
                            }
                            activeItem?.officialDocsUrl?.let { url ->
                                OutlinedButton(onClick = { onOpenUrl(url) }) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Official Docs")
                                }
                            }
                        }
                        if (keySavedMessage.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(keySavedMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Cloud Provider Fallback", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("Falls back to secondary provider if primary request fails", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.enableCloudFallback,
                    onCheckedChange = onToggleCloudFallback
                )
            }
        }
    }
}

@Composable
private fun VoiceApiDetailScreen(
    voiceApiState: ElevenLabsVoiceState?,
    spokenResponsesEnabled: Boolean,
    onToggleSpokenResponses: (Boolean) -> Unit,
    onSaveElevenLabsSecret: (String) -> Unit,
    onTestVoice: (String) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    var keyText by rememberSaveable { mutableStateOf("") }
    var saveStatusMsg by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ElevenLabs Voice API Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Status: ${voiceApiState?.status?.name ?: "CONFIGURATION_REQUIRED"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (voiceApiState?.isConfigured == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "API Key: ${if (voiceApiState?.isConfigured == true) voiceApiState.maskedApiKey else "API key required"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = voiceApiState?.statusDetail ?: "ElevenLabs API key not configured.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Configure ElevenLabs API Key",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = keyText,
                        onValueChange = { keyText = it },
                        label = { Text("ElevenLabs API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("elevenlabs_api_key_input")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (keyText.isNotBlank()) {
                                    onSaveElevenLabsSecret(keyText.trim())
                                    keyText = ""
                                    saveStatusMsg = "ElevenLabs key saved securely in Keystore vault."
                                }
                            },
                            modifier = Modifier.testTag("save_elevenlabs_key_button")
                        ) {
                            Text("Save Key")
                        }
                        OutlinedButton(
                            onClick = { onTestVoice(voiceApiState?.selectedVoiceId ?: "21m00Tcm4TlvDq8ikWAM") },
                            modifier = Modifier.testTag("test_voice_button")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Test Voice")
                        }
                        OutlinedButton(onClick = { onOpenUrl(voiceApiState?.officialDocsUrl ?: "https://elevenlabs.io/docs") }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Docs")
                        }
                    }
                    if (saveStatusMsg.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(saveStatusMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Real Android TTS Fallback",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Engine: Android TextToSpeech (Active when ElevenLabs is unconfigured, rate-limited, or offline)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Fallback State: ${if (voiceApiState?.isFallbackActive == true) "Active (Using Android TTS)" else "Standby"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Spoken Audio Responses", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Switch(
                    checked = spokenResponsesEnabled,
                    onCheckedChange = onToggleSpokenResponses
                )
            }
        }
    }
}

@Composable
private fun AccessibilityDetailScreen(
    state: AccessibilitySettingsSectionState?,
    showHud: Boolean,
    onToggleHud: (Boolean) -> Unit,
    onOpenSettings: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Connection Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Distinction: ${state?.connectionDistinction?.name ?: "SERVICE_DISABLED"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state?.connectionDistinction == AccessibilityConnectionDistinction.ACTUALLY_CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text("Enabled in OS: ${state?.serviceEnabledInSettings == true}", style = MaterialTheme.typography.bodySmall)
                    Text("Active Window Available: ${state?.activeWindowAvailable == true}", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("open_accessibility_settings_button")
                    ) {
                        Text("Open Android Accessibility Settings")
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Floating Accessibility Orb", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("Overlay on top of other applications", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = showHud, onCheckedChange = onToggleHud)
            }
        }
    }
}

@Composable
private fun PermissionsDetailScreen(
    state: PermissionsSettingsSectionState?,
    onRequestRecordAudioPermission: () -> Unit,
    onRequestNotificationsPermission: () -> Unit,
    onRequestMediaPermission: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onOpenAppDetails: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = "Live Android OS Permissions Audit",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(state?.items.orEmpty()) { item ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Status: ${item.status.name}", style = MaterialTheme.typography.bodySmall, color = if (item.status == NovaPermissionStatus.GRANTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(item.statusDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.canRequestInApp) {
                        Button(
                            onClick = {
                                when (item.permissionId) {
                                    "microphone" -> onRequestRecordAudioPermission()
                                    "notifications" -> onRequestNotificationsPermission()
                                    "photos_media" -> onRequestMediaPermission()
                                    else -> onOpenAppDetails()
                                }
                            }
                        ) {
                            Text("Grant")
                        }
                    } else if (item.opensSystemSettings) {
                        OutlinedButton(
                            onClick = {
                                if (item.permissionId == "notification_listener") {
                                    onOpenNotificationListenerSettings()
                                } else {
                                    onOpenAppDetails()
                                }
                            }
                        ) {
                            Text("Settings")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyDetailScreen(
    state: PrivacySettingsSectionState?,
    requireConfirmation: Boolean,
    onToggleRequireConfirmation: (Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Processing Locality", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Mode: ${state?.processingLocalityMode?.displayLabel ?: "LOCAL_DEVICE_PROCESSING"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Cloud Data Leaves Device: ${state?.mayProviderRequestsLeaveDevice == true}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Sensitive Data Redaction", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(state?.sensitiveRedactionSummary ?: "Passwords, OTPs, and secrets are redacted locally before prompt construction.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Require Confirmation for Actions", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("Confirms sensitive touch/input automation before execution", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = requireConfirmation, onCheckedChange = onToggleRequireConfirmation)
            }
        }
    }
}

@Composable
private fun MemoryDetailScreen(
    state: MemorySettingsSectionState?,
    savedMemoryFacts: List<MemoryFactEntity>,
    memoryContextEnabled: Boolean,
    onToggleMemoryContext: (Boolean) -> Unit,
    onClearMemoryFacts: () -> Unit,
    onClearHistory: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("On-Device Room Memory", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Saved Facts Count: ${state?.savedMemoryFactCount ?: 0}", style = MaterialTheme.typography.bodyMedium)
                    Text("Conversation Turns Count: ${state?.conversationTurnCount ?: 0}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Inject Memory Into Prompts", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("Allows Arya to recall user-saved facts during conversations", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = memoryContextEnabled, onCheckedChange = onToggleMemoryContext)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onClearMemoryFacts) {
                    Text("Clear Facts")
                }
                OutlinedButton(onClick = onClearHistory) {
                    Text("Clear Turns")
                }
            }
        }
    }
}

@Composable
private fun AutomationDetailScreen(
    state: AutomationSettingsSectionState?,
    requireConfirmation: Boolean,
    onToggleRequireConfirmation: (Boolean) -> Unit,
    onClearAuditLogs: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("M7 Tasks & Automation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Active Running Tasks: ${state?.activeRunningTaskCount ?: 0}", style = MaterialTheme.typography.bodyMedium)
                    Text("Total Session Tasks: ${state?.totalSessionTaskCount ?: 0}", style = MaterialTheme.typography.bodyMedium)
                    Text("Scheduled Reminders: ${state?.scheduledReminderCount ?: 0}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Button(onClick = onClearAuditLogs) {
                Text("Clear Task Audit Logs")
            }
        }
    }
}

@Composable
private fun AdvancedDetailScreen(
    state: AdvancedSettingsSectionState?,
    lowRamOverride: Boolean?,
    onSelectLowRamOverride: (Boolean?) -> Unit,
    onSelectGeminiModel: (String) -> Unit,
    onSelectGroqModel: (String) -> Unit,
    onSelectOpenAiModel: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Low-RAM Optimizations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("System Low-RAM Device: ${state?.isSystemLowRamDevice == true}", style = MaterialTheme.typography.bodyMedium)
                    Text("Effective Low-RAM Mode: ${state?.effectiveLowRamMode == true}", style = MaterialTheme.typography.bodyMedium)
                    Text("Max Concurrent Tasks: ${state?.maxConcurrentTasksLimit ?: 1}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Text("Low-RAM Mode Override", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = lowRamOverride == null, onClick = { onSelectLowRamOverride(null) }, label = { Text("Auto (OS)") })
                FilterChip(selected = lowRamOverride == true, onClick = { onSelectLowRamOverride(true) }, label = { Text("Force Enabled") })
                FilterChip(selected = lowRamOverride == false, onClick = { onSelectLowRamOverride(false) }, label = { Text("Force Disabled") })
            }
        }
    }
}

@Composable
private fun DiagnosticsDetailScreen(
    state: DiagnosticsSettingsSectionState?,
    onRefresh: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Technical Runtime State", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Diagnostics")
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("App: v${state?.appVersionName} (${state?.appVersionCode})", style = MaterialTheme.typography.bodyMedium)
                    Text("Android: ${state?.androidReleaseAndSdk}", style = MaterialTheme.typography.bodyMedium)
                    Text("Device: ${state?.safeDeviceModel}", style = MaterialTheme.typography.bodyMedium)
                    Text("RAM: ${state?.ramSummaryText}", style = MaterialTheme.typography.bodyMedium)
                    Text("Storage: ${state?.storageSummaryText}", style = MaterialTheme.typography.bodyMedium)
                    Text("Battery: ${state?.batterySummaryText}", style = MaterialTheme.typography.bodyMedium)
                    Text("Network: ${state?.networkSummaryText}", style = MaterialTheme.typography.bodyMedium)
                    Text("AI Provider: ${state?.aiProviderStatusSummary}", style = MaterialTheme.typography.bodyMedium)
                    Text("Voice Status: ${state?.voiceCapabilityStatusSummary}", style = MaterialTheme.typography.bodyMedium)
                    Text("Accessibility: ${state?.accessibilityStatusSummary}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
