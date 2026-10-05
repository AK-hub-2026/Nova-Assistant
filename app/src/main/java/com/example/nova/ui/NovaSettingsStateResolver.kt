package com.example.nova.ui

import com.example.BuildConfig
import com.example.nova.core.AccessibilityConnectionDistinction
import com.example.nova.core.AccessibilitySettingsSectionState
import com.example.nova.core.AccessibilityWorkspaceState
import com.example.nova.core.AccessibilityWorkspaceStatus
import com.example.nova.core.AdvancedSettingsSectionState
import com.example.nova.core.AiProviderConfigurationStatus
import com.example.nova.core.AiProviderItemState
import com.example.nova.core.AiProviderSettingsSectionState
import com.example.nova.core.AiProviderType
import com.example.nova.core.AutomationSettingsSectionState
import com.example.nova.core.CapabilityStatus
import com.example.nova.core.DiagnosticsSettingsSectionState
import com.example.nova.core.LiveTaskItem
import com.example.nova.core.LiveTaskStatus
import com.example.nova.core.MemorySettingsSectionState
import com.example.nova.core.NovaDeviceInfo
import com.example.nova.core.NovaProcessingLocalityMode
import com.example.nova.core.NovaProviderModelConfig
import com.example.nova.core.NovaSettingsSection
import com.example.nova.core.NovaSettingsState
import com.example.nova.core.OverlayAttachmentState
import com.example.nova.core.PermissionAuditItemState
import com.example.nova.core.PermissionsSettingsSectionState
import com.example.nova.core.PhotoAccessMode
import com.example.nova.core.PhotoAccessWorkspaceState
import com.example.nova.core.PrivacySettingsSectionState
import com.example.nova.core.SemanticScreenSnapshot
import com.example.nova.core.SystemHealthSnapshot
import com.example.nova.core.VoiceCapabilityStatus
import com.example.nova.core.VoiceProviderSettingsSectionState
import com.example.nova.memory.NovaRuntimeSettings
import com.example.nova.security.ProviderSecretVault
import com.example.nova.security.SensitiveDataRedactor
import com.example.nova.voice.VoiceEngineState
import java.util.Locale

/**
 * M8 — Pure, deterministic resolver for the 9 Nova Settings Control Center sections.
 *
 * Enforces M8 truthfulness and security invariants:
 * - Zero fake provider status ("API key required" when key is missing, never "Connected").
 * - Zero plaintext or partial API key exposure in UI state or diagnostics.
 * - Explicit distinction between Accessibility ENABLED_IN_SETTINGS_NOT_CONNECTED and ACTUALLY_CONNECTED.
 * - Explicit distinction between LOCAL_DEVICE_PROCESSING and REMOTE_PROVIDER_PROCESSING.
 * - Never claims "Always listening" in M8.
 */
object NovaSettingsStateResolver {

    fun resolveSettingsState(
        selectedSection: NovaSettingsSection,
        settings: NovaRuntimeSettings,
        health: SystemHealthSnapshot,
        voiceState: VoiceEngineState,
        isTtsReady: Boolean,
        isTtsEngineInstalled: Boolean,
        ttsErrorMessage: String?,
        hasMicrophoneHardware: Boolean,
        overlayAttachmentState: OverlayAttachmentState,
        screenSnapshot: SemanticScreenSnapshot?,
        accessibilityWorkspaceState: AccessibilityWorkspaceState?,
        photoWorkspaceState: PhotoAccessWorkspaceState?,
        latestDeviceInfo: NovaDeviceInfo?,
        activeTasks: List<LiveTaskItem>,
        permissionAuditItems: List<PermissionAuditItemState>,
        providerRuntimeErrors: Map<AiProviderType, String> = emptyMap(),
        savedMemoryFactCount: Int = 0,
        conversationTurnCount: Int = 0,
        scheduledReminderCount: Int = 0,
        latestDiagnosticMessage: String = "",
        appVersionName: String = runCatching { BuildConfig.VERSION_NAME }.getOrDefault("1.0"),
        appVersionCode: Long = runCatching { BuildConfig.VERSION_CODE.toLong() }.getOrDefault(1L),
        isElevenLabsConfigured: Boolean = false,
        maskedElevenLabsKey: String = "",
        elevenLabsStatus: VoiceCapabilityStatus = VoiceCapabilityStatus.CONFIGURATION_REQUIRED,
        elevenLabsStatusDetail: String = "ElevenLabs API key not configured. Using Android TextToSpeech fallback.",
        isElevenLabsFallbackActive: Boolean = true
    ): NovaSettingsState {
        val aiSection = buildAiProviderSection(
            settings = settings,
            health = health,
            providerRuntimeErrors = providerRuntimeErrors
        )
        val voiceSection = buildVoiceProviderSection(
            settings = settings,
            health = health,
            voiceState = voiceState,
            isTtsReady = isTtsReady,
            isTtsEngineInstalled = isTtsEngineInstalled,
            ttsErrorMessage = ttsErrorMessage,
            hasMicrophoneHardware = hasMicrophoneHardware
        )
        val voiceApiSection = com.example.nova.core.ElevenLabsVoiceState(
            isConfigured = isElevenLabsConfigured,
            selectedVoiceId = "21m00Tcm4TlvDq8ikWAM",
            selectedVoiceName = "Rachel",
            maskedApiKey = maskedElevenLabsKey,
            status = elevenLabsStatus,
            statusDetail = elevenLabsStatusDetail,
            isFallbackActive = isElevenLabsFallbackActive
        )
        val accessibilitySection = buildAccessibilitySection(
            settings = settings,
            health = health,
            overlayAttachmentState = overlayAttachmentState,
            screenSnapshot = screenSnapshot,
            accessibilityWorkspaceState = accessibilityWorkspaceState
        )
        val permissionsSection = PermissionsSettingsSectionState(
            items = permissionAuditItems
        )
        val privacySection = buildPrivacySection(
            settings = settings
        )
        val memorySection = buildMemorySection(
            settings = settings,
            savedMemoryFactCount = savedMemoryFactCount,
            conversationTurnCount = conversationTurnCount
        )
        val automationSection = buildAutomationSection(
            settings = settings,
            health = health,
            activeTasks = activeTasks,
            scheduledReminderCount = scheduledReminderCount
        )
        val advancedSection = buildAdvancedSection(
            settings = settings,
            health = health
        )
        val diagnosticsSection = buildDiagnosticsSection(
            settings = settings,
            health = health,
            aiSection = aiSection,
            voiceSection = voiceSection,
            accessibilitySection = accessibilitySection,
            photoWorkspaceState = photoWorkspaceState,
            latestDeviceInfo = latestDeviceInfo,
            activeTasks = activeTasks,
            providerRuntimeErrors = providerRuntimeErrors,
            voiceState = voiceState,
            ttsErrorMessage = ttsErrorMessage,
            latestDiagnosticMessage = latestDiagnosticMessage,
            appVersionName = appVersionName,
            appVersionCode = appVersionCode
        )

        return NovaSettingsState(
            selectedSection = selectedSection,
            aiProviderSection = aiSection,
            voiceProviderSection = voiceSection,
            voiceApiSection = voiceApiSection,
            accessibilitySection = accessibilitySection,
            permissionsSection = permissionsSection,
            privacySection = privacySection,
            memorySection = memorySection,
            automationSection = automationSection,
            advancedSection = advancedSection,
            diagnosticsSection = diagnosticsSection
        )
    }

    fun buildAiProviderSection(
        settings: NovaRuntimeSettings,
        health: SystemHealthSnapshot,
        providerRuntimeErrors: Map<AiProviderType, String> = emptyMap()
    ): AiProviderSettingsSectionState {
        val resolvedGeminiModel = NovaProviderModelConfig.resolveGeminiModelId(settings.geminiModel)

        val items = AiProviderType.entries.map { provider ->
            val isSelected = settings.primaryProvider == provider
            val isConfigured = when (provider) {
                AiProviderType.GEMINI -> health.geminiKeyConfigured
                AiProviderType.GROQ -> health.groqKeyConfigured
                AiProviderType.OPENAI_COMPATIBLE -> health.openAiKeyConfigured
                AiProviderType.OFFLINE_DETERMINISTIC -> true
            }
            val modelId = when (provider) {
                AiProviderType.GEMINI -> resolvedGeminiModel
                AiProviderType.GROQ -> settings.groqModel
                AiProviderType.OPENAI_COMPATIBLE -> settings.openAiModel
                AiProviderType.OFFLINE_DETERMINISTIC -> AiProviderType.OFFLINE_DETERMINISTIC.defaultModel
            }
            val modelOptions = when (provider) {
                AiProviderType.GEMINI -> NovaProviderModelConfig.SUPPORTED_GEMINI_MODELS
                AiProviderType.GROQ -> NovaProviderModelConfig.SUPPORTED_GROQ_MODELS
                AiProviderType.OPENAI_COMPATIBLE -> NovaProviderModelConfig.SUPPORTED_OPENAI_MODELS
                AiProviderType.OFFLINE_DETERMINISTIC -> listOf(AiProviderType.OFFLINE_DETERMINISTIC.defaultModel)
            }
            val rawError = providerRuntimeErrors[provider].orEmpty()
            val sanitizedError = sanitizeDiagnosticText(rawError)

            val safeSecretLabel = ProviderSecretVault.getSafeSecretStateLabel(
                isConfigured = isConfigured,
                provider = provider
            )

            val (runtimeStatus, statusDetail) = when {
                provider == AiProviderType.OFFLINE_DETERMINISTIC -> {
                    AiProviderConfigurationStatus.AVAILABLE to
                        "Available on-device (100% offline deterministic parser, 0 MB RAM overhead)"
                }
                !isConfigured -> {
                    AiProviderConfigurationStatus.CONFIGURATION_REQUIRED to
                        "API key required — Set ${secretEnvVarName(provider)} in AI Studio Secrets (.env)"
                }
                sanitizedError.isNotBlank() -> {
                    AiProviderConfigurationStatus.RUNTIME_ERROR to
                        "Runtime error: $sanitizedError"
                }
                !health.isNetworkOnline -> {
                    AiProviderConfigurationStatus.UNAVAILABLE to
                        "Configured ($modelId), but device is currently offline"
                }
                else -> {
                    AiProviderConfigurationStatus.CONFIGURED to
                        "Configured & ready ($modelId)"
                }
            }

            AiProviderItemState(
                providerType = provider,
                displayName = provider.displayName,
                isSelectedPrimary = isSelected,
                isKeyConfigured = isConfigured,
                safeSecretStatusLabel = safeSecretLabel,
                runtimeStatus = runtimeStatus,
                statusDetail = statusDetail,
                configuredModelId = modelId,
                availableModelOptions = modelOptions,
                officialDocsUrl = ProviderSecretVault.getOfficialDocumentationUrl(provider),
                lastRuntimeErrorMessage = sanitizedError
            )
        }

        return AiProviderSettingsSectionState(
            primaryProvider = settings.primaryProvider,
            enableCloudFallback = settings.enableCloudFallback,
            isNetworkOnline = health.isNetworkOnline,
            providers = items
        )
    }

    fun buildVoiceProviderSection(
        settings: NovaRuntimeSettings,
        health: SystemHealthSnapshot,
        voiceState: VoiceEngineState,
        isTtsReady: Boolean,
        isTtsEngineInstalled: Boolean,
        ttsErrorMessage: String?,
        hasMicrophoneHardware: Boolean
    ): VoiceProviderSettingsSectionState {
        // 1. Speech Recognition (Android SpeechRecognizer)
        val (sttStatus, sttDetail) = when {
            !health.speechRecognitionAvailable ->
                VoiceCapabilityStatus.UNAVAILABLE to
                    "Unavailable — Android SpeechRecognizer service is not installed or enabled on this device."
            !hasMicrophoneHardware ->
                VoiceCapabilityStatus.UNAVAILABLE to
                    "Unavailable — Device does not report microphone hardware."
            !health.recordAudioGranted ->
                VoiceCapabilityStatus.CONFIGURATION_REQUIRED to
                    "Configuration required — Grant RECORD_AUDIO permission to enable speech recognition."
            voiceState is VoiceEngineState.Error ->
                VoiceCapabilityStatus.ERROR to
                    "Error — ${sanitizeDiagnosticText(voiceState.errorDescription)}"
            else ->
                VoiceCapabilityStatus.AVAILABLE to
                    "Available — Android system SpeechRecognizer is ready for push-to-talk input."
        }

        // 2. Text-to-Speech (Android TextToSpeech)
        val sanitizedTtsError = sanitizeDiagnosticText(ttsErrorMessage.orEmpty())
        val (ttsStatus, ttsDetail) = when {
            sanitizedTtsError.isNotBlank() ->
                VoiceCapabilityStatus.ERROR to "Error — $sanitizedTtsError"
            isTtsReady ->
                VoiceCapabilityStatus.AVAILABLE to
                    "Available — Android system TextToSpeech engine is initialized and ready."
            isTtsEngineInstalled ->
                VoiceCapabilityStatus.AVAILABLE to
                    "Available — Android system TextToSpeech engine is installed on this device."
            else ->
                VoiceCapabilityStatus.UNAVAILABLE to
                    "Unavailable — Android TextToSpeech engine is not initialized or missing."
        }

        // 3. Microphone Capability
        val (micStatus, micDetail) = when {
            !hasMicrophoneHardware ->
                VoiceCapabilityStatus.UNAVAILABLE to
                    "Unavailable — Device does not report FEATURE_MICROPHONE hardware."
            !health.recordAudioGranted ->
                VoiceCapabilityStatus.CONFIGURATION_REQUIRED to
                    "Permission required — RECORD_AUDIO permission has not been granted."
            else ->
                VoiceCapabilityStatus.AVAILABLE to
                    "Available — Microphone hardware present and RECORD_AUDIO permission granted."
        }

        // 4. Speech Output Availability
        val (outputStatus, outputDetail) = when {
            ttsStatus == VoiceCapabilityStatus.ERROR ->
                VoiceCapabilityStatus.ERROR to
                    "Error — Speech output encountered a TextToSpeech error."
            ttsStatus == VoiceCapabilityStatus.UNAVAILABLE ->
                VoiceCapabilityStatus.UNAVAILABLE to
                    "Unavailable — Speech output requires an available Android TextToSpeech engine."
            !settings.spokenResponsesEnabled ->
                VoiceCapabilityStatus.CONFIGURATION_REQUIRED to
                    "Muted in Settings — Enable Spoken TTS Responses below to hear voice output."
            else ->
                VoiceCapabilityStatus.AVAILABLE to
                    "Available — Spoken responses are enabled via Android TextToSpeech."
        }

        return VoiceProviderSettingsSectionState(
            speechRecognitionStatus = sttStatus,
            speechRecognitionDetail = sttDetail,
            textToSpeechStatus = ttsStatus,
            textToSpeechDetail = ttsDetail,
            microphoneStatus = micStatus,
            microphoneDetail = micDetail,
            speechOutputStatus = outputStatus,
            speechOutputDetail = outputDetail,
            remoteVoiceProviderStatus = VoiceCapabilityStatus.UNAVAILABLE,
            remoteVoiceProviderDetail = "Unavailable — Remote cloud voice STT/TTS is not implemented; Nova uses Android system voice services.",
            spokenResponsesEnabled = settings.spokenResponsesEnabled,
            isAlwaysListeningActive = false,
            listeningModeDescription = "Push-to-Talk Only (Tap Nova Orb or Mic button — Always-listening wake word is not active)"
        )
    }

    fun buildAccessibilitySection(
        settings: NovaRuntimeSettings,
        health: SystemHealthSnapshot,
        overlayAttachmentState: OverlayAttachmentState,
        screenSnapshot: SemanticScreenSnapshot?,
        accessibilityWorkspaceState: AccessibilityWorkspaceState?
    ): AccessibilitySettingsSectionState {
        val enabledInSettings = health.accessibilityServiceEnabled
        val actuallyConnected = health.accessibilityServiceConnected

        val distinction = when {
            actuallyConnected -> AccessibilityConnectionDistinction.ACTUALLY_CONNECTED
            enabledInSettings -> AccessibilityConnectionDistinction.ENABLED_IN_SETTINGS_NOT_CONNECTED
            else -> AccessibilityConnectionDistinction.SERVICE_DISABLED
        }

        val activeWindowAvailable = actuallyConnected && (
            screenSnapshot != null || accessibilityWorkspaceState?.activeWindowAvailable == true
        )

        val m6Status = accessibilityWorkspaceState?.status ?: when {
            !enabledInSettings && !actuallyConnected -> AccessibilityWorkspaceStatus.SERVICE_DISABLED
            enabledInSettings && !actuallyConnected -> AccessibilityWorkspaceStatus.SERVICE_NOT_CONNECTED
            activeWindowAvailable -> AccessibilityWorkspaceStatus.ACTIVE_WINDOW_AVAILABLE
            else -> AccessibilityWorkspaceStatus.SERVICE_CONNECTED
        }

        val activeWindowSummary = when {
            !actuallyConnected ->
                "Unavailable (AccessibilityService is not connected)"
            screenSnapshot != null ->
                "Available — Package: ${screenSnapshot.packageName.ifBlank { "Unknown" }} (${screenSnapshot.nodes.size} semantic nodes)"
            accessibilityWorkspaceState?.activeWindowAvailable == true ->
                "Available — Package: ${accessibilityWorkspaceState.activePackage.valueOrNull() ?: "Active Window"}"
            else ->
                "No active foreground window snapshot currently available"
        }

        val capabilitySummary = when (distinction) {
            AccessibilityConnectionDistinction.SERVICE_DISABLED ->
                "Disabled in Android Settings — Open Accessibility Settings to enable NovaAccessibilityService."
            AccessibilityConnectionDistinction.ENABLED_IN_SETTINGS_NOT_CONNECTED ->
                "Enabled in Settings, Not Connected — Android OS has not bound NovaAccessibilityService yet. Automation is NOT ready until connected."
            AccessibilityConnectionDistinction.ACTUALLY_CONNECTED ->
                if (activeWindowAvailable) {
                    "Connected & Ready — Service is bound by Android OS and active window inspection is available."
                } else {
                    "Connected — Service is bound by Android OS; waiting for an inspectable foreground window."
                }
        }

        return AccessibilitySettingsSectionState(
            connectionDistinction = distinction,
            m6WorkspaceStatus = m6Status,
            serviceEnabledInSettings = enabledInSettings,
            serviceActuallyConnected = actuallyConnected,
            activeWindowAvailable = activeWindowAvailable,
            activeWindowSummary = activeWindowSummary,
            isReadyForAutomation = actuallyConnected,
            overlayAttachmentState = overlayAttachmentState,
            showAccessibilityFloatingHud = settings.showAccessibilityFloatingHud,
            capabilitySummary = capabilitySummary
        )
    }

    fun buildPrivacySection(
        settings: NovaRuntimeSettings
    ): PrivacySettingsSectionState {
        val isLocalOnly = settings.primaryProvider == AiProviderType.OFFLINE_DETERMINISTIC
        val localityMode = if (isLocalOnly) {
            NovaProcessingLocalityMode.LOCAL_DEVICE_PROCESSING
        } else {
            NovaProcessingLocalityMode.REMOTE_PROVIDER_PROCESSING
        }

        val transmissionSummary = if (isLocalOnly) {
            "Local Device Processing Active — Nova is set to the Offline Deterministic Parser. Commands and parameters do not leave this device."
        } else {
            "Remote Provider Processing Active — Direct device commands execute locally on-device, while complex requests may be sent to ${settings.primaryProvider.displayName} after SensitiveDataRedactor scrubs passwords, OTPs, payment cards, and API keys."
        }

        return PrivacySettingsSectionState(
            processingLocalityMode = localityMode,
            mayProviderRequestsLeaveDevice = !isLocalOnly,
            providerDataTransmissionSummary = transmissionSummary,
            isAccessibilityContentRetainedOnDisk = false,
            accessibilityRetentionSummary = "Raw AccessibilityNodeInfo trees and screen text are held strictly in volatile memory for the active window snapshot and are never written to Room or disk.",
            arePhotosUploadedToCloud = false,
            photoPrivacySummary = "Photos selected via Android Photo Picker or queried from MediaStore are decoded locally on-device and are never uploaded to external AI providers.",
            isAnalyticsOrTelemetryEnabled = false,
            analyticsTelemetrySummary = "Disabled (0% third-party analytics, ad SDKs, crash uploaders, or background telemetry exist in Nova).",
            isSensitiveRedactionEnforced = true,
            sensitiveRedactionSummary = "SensitiveDataRedactor automatically redacts password fields, OTP codes, payment card numbers, SSNs, and API keys before any prompt is constructed.",
            localSessionDataHandlingSummary = "Conversation history, user-saved memory facts, and verified task audit logs are stored locally in on-device Room SQLite tables and can be cleared below at any time.",
            requireConfirmationForSensitiveActions = settings.requireConfirmationForSensitiveActions
        )
    }

    fun buildMemorySection(
        settings: NovaRuntimeSettings,
        savedMemoryFactCount: Int,
        conversationTurnCount: Int
    ): MemorySettingsSectionState {
        return MemorySettingsSectionState(
            memoryCapabilityStatus = CapabilityStatus.AVAILABLE,
            memoryContextEnabled = settings.memoryContextInjectionEnabled,
            savedMemoryFactCount = savedMemoryFactCount.coerceAtLeast(0),
            conversationTurnCount = conversationTurnCount.coerceAtLeast(0),
            retentionBehaviorSummary = "On-device Room SQLite storage retains $savedMemoryFactCount saved memory fact(s) and $conversationTurnCount conversation turn(s) locally until explicitly cleared by the user.",
            graphifyKnowledgeGraphStatus = "Unavailable (Not implemented — Nova uses local Room relational persistence)",
            areRawMemoriesExposedByDefault = false
        )
    }

    fun buildAutomationSection(
        settings: NovaRuntimeSettings,
        health: SystemHealthSnapshot,
        activeTasks: List<LiveTaskItem>,
        scheduledReminderCount: Int
    ): AutomationSettingsSectionState {
        val a11yStatus = if (health.accessibilityServiceConnected) {
            CapabilityStatus.AVAILABLE
        } else {
            CapabilityStatus.SERVICE_DISABLED
        }
        val a11yRequirement = when {
            health.accessibilityServiceConnected ->
                "Available — NovaAccessibilityService is actively bound by Android OS."
            health.accessibilityServiceEnabled ->
                "Unavailable (Enabled in Settings, Not Connected) — Waiting for Android OS service binding."
            else ->
                "Requires AccessibilityService — Enable NovaAccessibilityService in Android Accessibility Settings for cross-app UI automation."
        }

        val runningCount = activeTasks.count { it.status == LiveTaskStatus.RUNNING }
        val pendingCount = activeTasks.count {
            it.status == LiveTaskStatus.PENDING || it.status == LiveTaskStatus.AWAITING_CONFIRMATION
        }

        return AutomationSettingsSectionState(
            accessibilityAutomationStatus = a11yStatus,
            accessibilityAutomationEnabled = health.accessibilityServiceConnected,
            accessibilityRequirementSummary = a11yRequirement,
            systemIntentAutomationStatus = CapabilityStatus.AVAILABLE,
            activeRunningTaskCount = runningCount,
            pendingQueuedTaskCount = pendingCount,
            totalSessionTaskCount = activeTasks.size,
            scheduledReminderCount = scheduledReminderCount.coerceAtLeast(0),
            requireConfirmationForSensitiveActions = settings.requireConfirmationForSensitiveActions,
            autonomousBackgroundAgentStatus = "Unavailable — Nova executes only explicit user-requested tasks with bounded concurrency"
        )
    }

    fun buildAdvancedSection(
        settings: NovaRuntimeSettings,
        health: SystemHealthSnapshot
    ): AdvancedSettingsSectionState {
        val effectiveLowRam = health.effectiveLowRamMode
        return AdvancedSettingsSectionState(
            isSystemLowRamDevice = health.isSystemLowRamDevice,
            effectiveLowRamMode = effectiveLowRam,
            lowRamModeOverride = settings.lowRamModeOverride,
            maxConcurrentTasksLimit = if (effectiveLowRam) 1 else 2,
            maxRetainedTasksLimit = if (effectiveLowRam) 6 else 12,
            enableCloudFallback = settings.enableCloudFallback,
            primaryProvider = settings.primaryProvider,
            geminiModel = NovaProviderModelConfig.resolveGeminiModelId(settings.geminiModel),
            groqModel = settings.groqModel,
            openAiModel = settings.openAiModel,
            supportedGeminiModels = NovaProviderModelConfig.SUPPORTED_GEMINI_MODELS,
            supportedGroqModels = NovaProviderModelConfig.SUPPORTED_GROQ_MODELS,
            supportedOpenAiModels = NovaProviderModelConfig.SUPPORTED_OPENAI_MODELS,
            verificationAndSecurityEnforced = true
        )
    }

    fun buildDiagnosticsSection(
        settings: NovaRuntimeSettings,
        health: SystemHealthSnapshot,
        aiSection: AiProviderSettingsSectionState,
        voiceSection: VoiceProviderSettingsSectionState,
        accessibilitySection: AccessibilitySettingsSectionState,
        photoWorkspaceState: PhotoAccessWorkspaceState?,
        latestDeviceInfo: NovaDeviceInfo?,
        activeTasks: List<LiveTaskItem>,
        providerRuntimeErrors: Map<AiProviderType, String> = emptyMap(),
        voiceState: VoiceEngineState = VoiceEngineState.Idle,
        ttsErrorMessage: String? = null,
        latestDiagnosticMessage: String = "",
        appVersionName: String = "1.0",
        appVersionCode: Long = 1L
    ): DiagnosticsSettingsSectionState {
        val ramSummary = if (health.totalRamMb > 0L && health.availableRamMb > 0L) {
            "${health.availableRamMb} MB available / ${health.totalRamMb} MB total"
        } else {
            "Unavailable"
        }

        val storageAvail = latestDeviceInfo?.availableStorageBytes?.valueOrNull()
        val storageTotal = latestDeviceInfo?.totalStorageBytes?.valueOrNull()
        val storageSummary = if (storageAvail != null && storageTotal != null && storageTotal > 0L) {
            val availGb = String.format(Locale.US, "%.2f GB", storageAvail.toDouble() / (1024.0 * 1024.0 * 1024.0))
            val totalGb = String.format(Locale.US, "%.2f GB", storageTotal.toDouble() / (1024.0 * 1024.0 * 1024.0))
            "$availGb available / $totalGb total"
        } else {
            "Not queried in current session (run 'device info' to inspect StatFs)"
        }

        val batterySummary = if (health.batteryPercent in 0..100) {
            val chargeLabel = if (health.isCharging) "Charging" else "Discharging"
            "${health.batteryPercent}% ($chargeLabel)"
        } else {
            "Unavailable"
        }

        val networkSummary = if (health.isNetworkOnline) "Online (Internet capability verified)" else "Offline"

        val lowRamSummary = "System Low-RAM: ${health.isSystemLowRamDevice} • Effective Low-RAM Mode: ${health.effectiveLowRamMode}"

        val primaryProviderItem = aiSection.providers.firstOrNull { it.providerType == settings.primaryProvider }
        val aiSummary = if (primaryProviderItem != null) {
            "${primaryProviderItem.displayName}: ${primaryProviderItem.runtimeStatus.name} (${primaryProviderItem.safeSecretStatusLabel})"
        } else {
            "${settings.primaryProvider.displayName}: ${ProviderSecretVault.getSafeSecretStateLabel(false, settings.primaryProvider)}"
        }

        val voiceSummary = "STT: ${voiceSection.speechRecognitionStatus.name} • TTS: ${voiceSection.textToSpeechStatus.name} • Mic: ${voiceSection.microphoneStatus.name}"

        val a11ySummary = "${accessibilitySection.connectionDistinction.name} (Workspace: ${accessibilitySection.m6WorkspaceStatus.name}, Overlay: ${accessibilitySection.overlayAttachmentState.name})"

        val photoSummary = when (val mode = photoWorkspaceState?.accessMode) {
            null -> "Not queried in current session (Photo Picker & MediaStore supported)"
            PhotoAccessMode.PICKED_URI_SESSION -> "Photo Picker Session (${photoWorkspaceState.photos.size} photo(s))"
            PhotoAccessMode.PICKED_URI_PERSISTED -> "Photo Picker Persisted (${photoWorkspaceState.photos.size} photo(s))"
            PhotoAccessMode.MEDIASTORE_ACCESS -> "MediaStore Access (${photoWorkspaceState.photos.size} photo(s))"
            PhotoAccessMode.PERMISSION_REQUIRED -> "Permission Required (MediaStore denied; Photo Picker available)"
            PhotoAccessMode.EMPTY -> "Empty (0 accessible photos found)"
            PhotoAccessMode.UNAVAILABLE -> "Unavailable"
            PhotoAccessMode.ERROR -> "Error (${sanitizeDiagnosticText(photoWorkspaceState.errorMessage.orEmpty())})"
        }

        val runningCount = activeTasks.count { it.status == LiveTaskStatus.RUNNING }
        val pendingCount = activeTasks.count {
            it.status == LiveTaskStatus.PENDING || it.status == LiveTaskStatus.AWAITING_CONFIRMATION
        }

        val recentErrors = buildList {
            providerRuntimeErrors.forEach { (provider, err) ->
                if (err.isNotBlank()) {
                    add("${provider.displayName}: ${sanitizeDiagnosticText(err)}")
                }
            }
            if (voiceState is VoiceEngineState.Error && voiceState.errorDescription.isNotBlank()) {
                add("Voice STT: ${sanitizeDiagnosticText(voiceState.errorDescription)}")
            }
            if (!ttsErrorMessage.isNullOrBlank()) {
                add("Voice TTS: ${sanitizeDiagnosticText(ttsErrorMessage)}")
            }
            activeTasks.filter {
                it.status == LiveTaskStatus.FAILED || it.status == LiveTaskStatus.UNAVAILABLE
            }.forEach { failedTask ->
                val reason = failedTask.failureOrUnavailableReason.ifBlank { failedTask.statusMessage }
                if (reason.isNotBlank()) {
                    add("Task '${failedTask.title}' (${failedTask.status.name}): ${sanitizeDiagnosticText(reason)}")
                }
            }
            if (latestDiagnosticMessage.isNotBlank() &&
                (latestDiagnosticMessage.contains("failed", ignoreCase = true) ||
                    latestDiagnosticMessage.contains("error", ignoreCase = true) ||
                    latestDiagnosticMessage.contains("required", ignoreCase = true))
            ) {
                val cleanDiag = sanitizeDiagnosticText(latestDiagnosticMessage)
                if (none { it.contains(cleanDiag) }) {
                    add(cleanDiag)
                }
            }
        }

        return DiagnosticsSettingsSectionState(
            appVersionName = appVersionName.ifBlank { "Unavailable" },
            appVersionCode = appVersionCode,
            androidReleaseAndSdk = "Android ${health.androidRelease.ifBlank { "Unknown" }} (API ${health.sdkInt})",
            safeDeviceModel = health.deviceModel.ifBlank { "Unavailable" },
            ramSummaryText = ramSummary,
            storageSummaryText = storageSummary,
            batterySummaryText = batterySummary,
            networkSummaryText = networkSummary,
            lowRamClassificationText = lowRamSummary,
            aiProviderStatusSummary = sanitizeDiagnosticText(aiSummary),
            voiceCapabilityStatusSummary = voiceSummary,
            accessibilityStatusSummary = a11ySummary,
            photoCapabilityStatusSummary = photoSummary,
            activeTaskCount = runningCount,
            pendingTaskCount = pendingCount,
            totalSessionTaskCount = activeTasks.size,
            recentNonSensitiveErrors = recentErrors
        )
    }

    fun sanitizeDiagnosticText(raw: String): String {
        if (raw.isBlank()) return ""
        val redacted = SensitiveDataRedactor.redactText(raw.trim()).redactedText
        return if (ProviderSecretVault.containsAnyConfiguredPlaintextSecret(redacted)) {
            "[REDACTED_SENSITIVE_DIAGNOSTIC]"
        } else {
            redacted
        }
    }

    private fun secretEnvVarName(provider: AiProviderType): String {
        return when (provider) {
            AiProviderType.GEMINI -> "GEMINI_API_KEY"
            AiProviderType.GROQ -> "GROQ_API_KEY"
            AiProviderType.OPENAI_COMPATIBLE -> "OPENAI_API_KEY"
            AiProviderType.OFFLINE_DETERMINISTIC -> ""
        }
    }
}
