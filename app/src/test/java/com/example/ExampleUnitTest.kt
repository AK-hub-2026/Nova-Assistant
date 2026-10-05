package com.example

import com.example.nova.ai.LocalDeterministicParser
import com.example.nova.ai.NovaAiProviderRouter
import com.example.nova.ai.ProviderPlanOutcome
import com.example.nova.core.AiProviderType
import com.example.nova.core.AssistantSessionStage
import com.example.nova.core.ExecutionChannel
import com.example.nova.core.HardwareHotwordCapabilityStatus
import com.example.nova.core.MediaStoreQueryAccessState
import com.example.nova.core.NovaProviderModelConfig
import com.example.nova.core.NovaUiOrbState
import com.example.nova.core.PendingConfirmationRequest
import com.example.nova.core.PhotoAccessWorkspaceState
import com.example.nova.core.PickedPhotoUriEntry
import com.example.nova.core.PlannedToolCall
import com.example.nova.core.RiskLevel
import com.example.nova.core.SystemHealthSnapshot
import com.example.nova.core.UriPersistenceState
import com.example.nova.core.VerificationOutcome
import com.example.nova.core.VerifiedActionResult
import com.example.nova.core.WakeWordAndAssistantCapabilitySnapshot
import com.example.nova.core.WorkspaceCardState
import com.example.nova.memory.NovaRuntimeSettings
import com.example.nova.security.SensitiveDataRedactor
import com.example.nova.ui.NovaUiStateMapper
import com.example.nova.voice.VoiceEngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `sensitive data redactor masks password nodes and OTP codes`() {
        val pwResult = SensitiveDataRedactor.sanitizeNodeText(
            rawText = "MySecretPass123",
            isPasswordNode = true
        )
        assertTrue(pwResult.wasRedacted)
        assertEquals("[REDACTED_PASSWORD_FIELD]", pwResult.sanitizedText)

        val otpResult = SensitiveDataRedactor.sanitizeNodeText(
            rawText = "Your verification code is 849201",
            isPasswordNode = false
        )
        assertTrue(otpResult.wasRedacted)
        assertTrue(otpResult.sanitizedText.contains("[REDACTED_OTP]"))
    }

    @Test
    fun `local deterministic parser routes offline system and sensitive commands accurately`() {
        val flashCall = LocalDeterministicParser.tryParse("turn on flashlight")
        assertNotNull(flashCall)
        assertEquals("set_flashlight", flashCall?.toolName)
        assertEquals("true", flashCall?.arguments?.get("enabled"))
        assertEquals(RiskLevel.SAFE, flashCall?.riskLevel)

        val dialCall = LocalDeterministicParser.tryParse("call +1 555 0199")
        assertNotNull(dialCall)
        assertEquals("dial_number", dialCall?.toolName)
        assertEquals(RiskLevel.SENSITIVE_CONFIRM, dialCall?.riskLevel)

        val readCall = LocalDeterministicParser.tryParse("read screen")
        assertNotNull(readCall)
        assertEquals("read_screen", readCall?.toolName)
    }

    @Test
    fun `ai router parses structured json tool decisions`() {
        val router = NovaAiProviderRouter()
        val sampleJson = """
            {
              "toolName": "set_volume",
              "arguments": {"stream": "music", "level": "up"},
              "reasoning": "User asked to raise volume",
              "riskLevel": "SAFE",
              "spokenResponse": "Raising media volume."
            }
        """.trimIndent()

        val parsed = router.parseModelJsonDecision(sampleJson, "Gemini (gemini-3.5-flash)")
        assertTrue(parsed is ProviderPlanOutcome.ToolSelected)
        val selected = parsed as ProviderPlanOutcome.ToolSelected
        assertEquals("set_volume", selected.call.toolName)
        assertEquals("music", selected.call.arguments["stream"])
    }

    @Test
    fun `M0 configurable Gemini model resolver preserves custom models and replaces retired prefixes`() {
        assertEquals(
            "gemini-3.1-pro-preview",
            NovaProviderModelConfig.resolveGeminiModelId("gemini-3.1-pro-preview")
        )
        assertEquals(
            "gemini-flash-latest",
            NovaProviderModelConfig.resolveGeminiModelId("gemini-flash-latest")
        )
        assertEquals(
            NovaProviderModelConfig.DEFAULT_GEMINI_MODEL_ID,
            NovaProviderModelConfig.resolveGeminiModelId("gemini-1.5-flash")
        )
        assertEquals(
            NovaProviderModelConfig.DEFAULT_GEMINI_MODEL_ID,
            NovaProviderModelConfig.resolveGeminiModelId("gemini-2.0-flash")
        )
        assertEquals(
            "gemini-3.1-flash-lite-preview",
            NovaProviderModelConfig.resolveGeminiModelId(
                configuredModelId = "",
                fallbackModelId = "gemini-3.1-flash-lite-preview"
            )
        )
    }

    @Test
    fun `M0 wake word capability contract never equates ROLE_ASSISTANT with hardware hotword listening`() {
        val roleHeldWithoutHotword = WakeWordAndAssistantCapabilitySnapshot(
            assistantRoleAvailable = true,
            assistantRoleHeld = true,
            voiceInteractionServiceDeclared = false,
            voiceInteractionServiceBound = false,
            explicitSpeechRecognitionAvailable = true,
            hardwareHotwordCapability = HardwareHotwordCapabilityStatus.UNSUPPORTED_NO_PRIVILEGED_VOICE_INTERACTION_SERVICE
        )
        assertTrue(roleHeldWithoutHotword.assistantRoleHeld)
        assertFalse(roleHeldWithoutHotword.isRealHardwareWakeWordActive)
    }

    @Test
    fun `M0 photo access contract keeps picker session URIs separate from MediaStore query access`() {
        val state = PhotoAccessWorkspaceState(
            pickerSelectedEntries = listOf(
                PickedPhotoUriEntry(
                    uriString = "content://media/picker/0/101",
                    displayName = "IMG_001.jpg",
                    mimeType = "image/jpeg",
                    dateTakenOrAddedMs = 1700000000000L,
                    persistenceState = UriPersistenceState.SESSION_SCOPED_ONLY
                )
            ),
            mediaStoreAccessState = MediaStoreQueryAccessState.LIMITED_TO_APP_OWNED_MEDIA_WITHOUT_PERMISSION,
            mediaStoreEntries = emptyList()
        )
        assertEquals(1, state.pickerSelectedEntries.size)
        assertEquals(
            UriPersistenceState.SESSION_SCOPED_ONLY,
            state.pickerSelectedEntries.first().persistenceState
        )
        assertTrue(state.mediaStoreEntries.isEmpty())
        assertEquals(
            MediaStoreQueryAccessState.LIMITED_TO_APP_OWNED_MEDIA_WITHOUT_PERMISSION,
            state.mediaStoreAccessState
        )
    }

    @Test
    fun `M1 NovaUiStateMapper maps IDLE, LISTENING, THINKING, EXECUTING, SPEAKING, ERROR, and UNAVAILABLE accurately`() {
        // IDLE mapping
        assertEquals(
            NovaUiOrbState.IDLE,
            NovaUiStateMapper.deriveOrbState(
                voiceState = VoiceEngineState.Idle,
                sessionStage = AssistantSessionStage.IDLE
            )
        )

        // LISTENING mapping
        assertEquals(
            NovaUiOrbState.LISTENING,
            NovaUiStateMapper.deriveOrbState(
                voiceState = VoiceEngineState.Listening("open settings"),
                sessionStage = AssistantSessionStage.IDLE
            )
        )

        // THINKING mapping
        assertEquals(
            NovaUiOrbState.THINKING,
            NovaUiStateMapper.deriveOrbState(
                voiceState = VoiceEngineState.Idle,
                sessionStage = AssistantSessionStage.SANITIZING_AND_PLANNING
            )
        )

        // EXECUTING mapping
        assertEquals(
            NovaUiOrbState.EXECUTING,
            NovaUiStateMapper.deriveOrbState(
                voiceState = VoiceEngineState.Idle,
                sessionStage = AssistantSessionStage.EXECUTING_AND_VERIFYING
            )
        )

        // SPEAKING mapping
        assertEquals(
            NovaUiOrbState.SPEAKING,
            NovaUiStateMapper.deriveOrbState(
                voiceState = VoiceEngineState.Speaking("Opened Settings."),
                sessionStage = AssistantSessionStage.IDLE
            )
        )

        // UNAVAILABLE mapping (missing microphone permission or SpeechRecognizer service)
        assertEquals(
            NovaUiOrbState.UNAVAILABLE,
            NovaUiStateMapper.deriveOrbState(
                voiceState = VoiceEngineState.PermissionRequired,
                sessionStage = AssistantSessionStage.IDLE
            )
        )
        assertEquals(
            NovaUiOrbState.UNAVAILABLE,
            NovaUiStateMapper.deriveOrbState(
                voiceState = VoiceEngineState.Unavailable("No SpeechRecognizer service"),
                sessionStage = AssistantSessionStage.IDLE
            )
        )

        // UNAVAILABLE mapping (missing AI provider key or disabled Accessibility service)
        assertEquals(
            NovaUiOrbState.UNAVAILABLE,
            NovaUiStateMapper.deriveOrbState(
                voiceState = VoiceEngineState.Idle,
                sessionStage = AssistantSessionStage.PERMISSION_OR_CONFIG_REQUIRED,
                statusBanner = "CONFIGURATION_REQUIRED"
            )
        )

        // ERROR mapping (real runtime / provider / verification failure)
        assertEquals(
            NovaUiOrbState.ERROR,
            NovaUiStateMapper.deriveOrbState(
                voiceState = VoiceEngineState.Error("Audio recording error"),
                sessionStage = AssistantSessionStage.IDLE
            )
        )
        assertEquals(
            NovaUiOrbState.ERROR,
            NovaUiStateMapper.deriveOrbState(
                voiceState = VoiceEngineState.Idle,
                sessionStage = AssistantSessionStage.ERROR,
                statusBanner = "PROVIDER_RUNTIME_ERROR"
            )
        )
    }

    @Test
    fun `M1 capability checks and real failure vs permission distinction in WorkspaceCardState`() {
        val sampleHealth = SystemHealthSnapshot(
            sdkInt = 35,
            androidRelease = "15",
            deviceModel = "Pixel",
            totalRamMb = 3072,
            availableRamMb = 1400,
            isSystemLowRamDevice = true,
            effectiveLowRamMode = true,
            batteryPercent = 80,
            isCharging = false,
            isNetworkOnline = true,
            hasFlashlightHardware = false,
            isTorchCurrentlyOn = false,
            recordAudioGranted = false,
            postNotificationsGranted = false,
            speechRecognitionAvailable = true,
            accessibilityServiceEnabled = false,
            accessibilityServiceConnected = false,
            notificationListenerEnabled = false,
            geminiKeyConfigured = false,
            groqKeyConfigured = false,
            openAiKeyConfigured = false
        )

        // Missing microphone permission -> false
        assertFalse(NovaUiStateMapper.isMicrophoneAndSpeechCapabilityReady(sampleHealth))

        // Unconfigured cloud provider -> false
        val geminiSettings = NovaRuntimeSettings(
            primaryProvider = AiProviderType.GEMINI,
            enableCloudFallback = true
        )
        assertFalse(NovaUiStateMapper.isAiProviderCapabilityAvailable(geminiSettings, sampleHealth))

        // Offline deterministic parser -> true even without API keys
        val offlineSettings = NovaRuntimeSettings(
            primaryProvider = AiProviderType.OFFLINE_DETERMINISTIC
        )
        assertTrue(NovaUiStateMapper.isAiProviderCapabilityAvailable(offlineSettings, sampleHealth))

        // WorkspaceCardState: empty/Idle when no real task or result exists
        val emptyCard = NovaUiStateMapper.deriveWorkspaceCardState(
            sessionStage = AssistantSessionStage.IDLE,
            pendingConfirmation = null,
            lastVerifiedResult = null,
            voiceState = VoiceEngineState.Idle,
            statusBanner = "Ready"
        )
        assertTrue(emptyCard is WorkspaceCardState.None)

        // WorkspaceCardState: ConfirmationRequired when sensitive action is pending
        val pendingReq = PendingConfirmationRequest(
            id = "req-1",
            originalUserQuery = "call 5550199",
            plannedCall = PlannedToolCall(
                toolName = "dial_number",
                arguments = mapOf("phone" to "5550199"),
                reasoning = "Dial phone",
                expectedOutcomeDescription = "Open dialer",
                riskLevel = RiskLevel.SENSITIVE_CONFIRM,
                channel = ExecutionChannel.ANDROID_API_INTENT,
                spokenResponseDraft = "Dialing"
            ),
            providerUsed = "Local",
            warningReason = "Confirm dial"
        )
        val confirmCard = NovaUiStateMapper.deriveWorkspaceCardState(
            sessionStage = AssistantSessionStage.AWAITING_USER_CONFIRMATION,
            pendingConfirmation = pendingReq,
            lastVerifiedResult = null,
            voiceState = VoiceEngineState.Idle,
            statusBanner = "Confirm"
        )
        assertTrue(confirmCard is WorkspaceCardState.ConfirmationRequired)

        // WorkspaceCardState: Never emits VerifiedActionSummary when verification failed or permission required
        val permResult = VerifiedActionResult(
            toolName = "set_volume",
            outcome = VerificationOutcome.PERMISSION_REQUIRED,
            verificationDetail = "Do Not Disturb active",
            targetPackage = "android.media.AudioManager",
            preStateSummary = "5/15",
            postStateSummary = "5/15",
            userFacingMessage = "Permission required for DND volume change",
            elapsedMs = 10L
        )
        val permWorkspace = NovaUiStateMapper.deriveWorkspaceCardState(
            sessionStage = AssistantSessionStage.PERMISSION_OR_CONFIG_REQUIRED,
            pendingConfirmation = null,
            lastVerifiedResult = permResult,
            voiceState = VoiceEngineState.Idle,
            statusBanner = "set_volume: PERMISSION_REQUIRED"
        )
        assertTrue(permWorkspace is WorkspaceCardState.ErrorOrUnavailableWorkspace)
        assertEquals(
            VerificationOutcome.PERMISSION_REQUIRED,
            (permWorkspace as WorkspaceCardState.ErrorOrUnavailableWorkspace).outcome
        )

        val failedResult = permResult.copy(
            outcome = VerificationOutcome.VERIFICATION_FAILED,
            userFacingMessage = "Volume did not change"
        )
        val failedWorkspace = NovaUiStateMapper.deriveWorkspaceCardState(
            sessionStage = AssistantSessionStage.ERROR,
            pendingConfirmation = null,
            lastVerifiedResult = failedResult,
            voiceState = VoiceEngineState.Idle,
            statusBanner = "set_volume: VERIFICATION_FAILED"
        )
        assertTrue(failedWorkspace is WorkspaceCardState.ErrorOrUnavailableWorkspace)
        assertEquals(
            VerificationOutcome.VERIFICATION_FAILED,
            (failedWorkspace as WorkspaceCardState.ErrorOrUnavailableWorkspace).outcome
        )

        // WorkspaceCardState: Emits VerifiedActionSummary ONLY when outcome == VERIFIED_SUCCESS
        val verifiedSuccessResult = permResult.copy(
            outcome = VerificationOutcome.VERIFIED_SUCCESS,
            userFacingMessage = "Set music volume to 7 of 15."
        )
        val successWorkspace = NovaUiStateMapper.deriveWorkspaceCardState(
            sessionStage = AssistantSessionStage.IDLE,
            pendingConfirmation = null,
            lastVerifiedResult = verifiedSuccessResult,
            voiceState = VoiceEngineState.Idle,
            statusBanner = "set_volume: VERIFIED_SUCCESS"
        )
        assertTrue(successWorkspace is WorkspaceCardState.VerifiedActionSummary)
    }

    @Test
    fun `M2 NovaOrbCanvas never fabricates audio amplitude when unavailable and normalizes real RMS only in LISTENING`() {
        // Null amplitude in LISTENING -> null (never fabricated)
        assertEquals(
            null,
            com.example.nova.ui.computeRealAudioNormalizedLevel(
                orbState = NovaUiOrbState.LISTENING,
                liveRmsDb = null
            )
        )

        // Real amplitude in non-LISTENING states -> ignored (null)
        assertEquals(
            null,
            com.example.nova.ui.computeRealAudioNormalizedLevel(
                orbState = NovaUiOrbState.IDLE,
                liveRmsDb = 6.0f
            )
        )

        // Real amplitude in LISTENING -> normalized into [0f, 1f]
        val normalized = com.example.nova.ui.computeRealAudioNormalizedLevel(
            orbState = NovaUiOrbState.LISTENING,
            liveRmsDb = 4.0f
        )
        assertNotNull(normalized)
        assertEquals(0.5f, normalized!!, 0.01f)
    }

    @Test
    fun `M2 NovaOrbCanvas reduces animation layers and disables idle loop in Low-RAM mode`() {
        val standardIdle = com.example.nova.ui.resolveOrbRenderConfig(
            orbState = NovaUiOrbState.IDLE,
            lowRamMode = false
        )
        val lowRamIdle = com.example.nova.ui.resolveOrbRenderConfig(
            orbState = NovaUiOrbState.IDLE,
            lowRamMode = true
        )

        assertTrue(standardIdle.animateContinuously)
        assertFalse(lowRamIdle.animateContinuously)
        assertTrue(lowRamIdle.ringLayerCount < standardIdle.ringLayerCount)

        val standardThinking = com.example.nova.ui.resolveOrbRenderConfig(
            orbState = NovaUiOrbState.THINKING,
            lowRamMode = false
        )
        val lowRamThinking = com.example.nova.ui.resolveOrbRenderConfig(
            orbState = NovaUiOrbState.THINKING,
            lowRamMode = true
        )
        assertTrue(lowRamThinking.orbitalArcCount < standardThinking.orbitalArcCount)
        assertTrue(lowRamThinking.cycleDurationMs > standardThinking.cycleDurationMs)
    }

    @Test
    fun `M3 FloatingNovaOrbView animation spec uses subtle idle animation, stronger active animations, and Low-RAM throttling across all 7 states`() {
        val idleSpec = com.example.nova.ui.resolveFloatingOrbAnimationSpec(
            orbState = NovaUiOrbState.IDLE,
            lowRamMode = false,
            liveRmsDb = null
        )
        val listeningSpec = com.example.nova.ui.resolveFloatingOrbAnimationSpec(
            orbState = NovaUiOrbState.LISTENING,
            lowRamMode = false,
            liveRmsDb = 4.0f
        )
        val thinkingSpec = com.example.nova.ui.resolveFloatingOrbAnimationSpec(
            orbState = NovaUiOrbState.THINKING,
            lowRamMode = false
        )
        val executingSpec = com.example.nova.ui.resolveFloatingOrbAnimationSpec(
            orbState = NovaUiOrbState.EXECUTING,
            lowRamMode = false
        )
        val speakingSpec = com.example.nova.ui.resolveFloatingOrbAnimationSpec(
            orbState = NovaUiOrbState.SPEAKING,
            lowRamMode = false
        )
        val errorSpec = com.example.nova.ui.resolveFloatingOrbAnimationSpec(
            orbState = NovaUiOrbState.ERROR,
            lowRamMode = false
        )
        val unavailableSpec = com.example.nova.ui.resolveFloatingOrbAnimationSpec(
            orbState = NovaUiOrbState.UNAVAILABLE,
            lowRamMode = false
        )

        // Subtle idle vs stronger active animations
        assertTrue(listeningSpec.pulseIntensity > idleSpec.pulseIntensity)
        assertTrue(thinkingSpec.pulseIntensity > idleSpec.pulseIntensity)
        assertTrue(executingSpec.pulseIntensity > idleSpec.pulseIntensity)
        assertTrue(speakingSpec.pulseIntensity > idleSpec.pulseIntensity)
        assertTrue(listeningSpec.cycleDurationMs < idleSpec.cycleDurationMs)
        assertTrue(executingSpec.cycleDurationMs < idleSpec.cycleDurationMs)
        assertTrue(errorSpec.animateContinuously)

        // Real audio normalized only in LISTENING
        assertEquals(null, idleSpec.realNormalizedAudioLevel)
        assertNotNull(listeningSpec.realNormalizedAudioLevel)

        // UNAVAILABLE never animates or pretends to be active
        assertFalse(unavailableSpec.animateContinuously)
        assertEquals(0f, unavailableSpec.pulseIntensity, 0.001f)

        // Low-RAM mode disables continuous idle animation and reduces ring count
        val lowRamIdleSpec = com.example.nova.ui.resolveFloatingOrbAnimationSpec(
            orbState = NovaUiOrbState.IDLE,
            lowRamMode = true
        )
        assertFalse(lowRamIdleSpec.animateContinuously)
        assertTrue(lowRamIdleSpec.ringCount < idleSpec.ringCount)
    }

    @Test
    fun `M4 NovaDeviceInfoProvider maps real battery, charging, RAM, storage, Android API, and network transport accurately`() {
        val raw = com.example.nova.tools.RawAndroidDeviceReadings(
            batteryIntentPresent = true,
            batteryLevel = 84,
            batteryScale = 100,
            batteryStatusConstant = android.os.BatteryManager.BATTERY_STATUS_CHARGING,
            batteryPluggedConstant = android.os.BatteryManager.BATTERY_PLUGGED_USB,
            totalRamBytes = 4L * 1024L * 1024L * 1024L,
            availableRamBytes = 1536L * 1024L * 1024L,
            isLowRamDevice = false,
            storageScopeLabel = "Internal Data Partition (/data)",
            totalStorageBytes = 64L * 1024L * 1024L * 1024L,
            availableStorageBytes = 22L * 1024L * 1024L * 1024L,
            androidRelease = "15",
            sdkInt = 35,
            manufacturer = "Google",
            model = "Pixel 8",
            connectivityManagerAvailable = true,
            hasActiveNetwork = true,
            hasInternetCapability = true,
            activeTransportConstants = setOf(android.net.NetworkCapabilities.TRANSPORT_WIFI),
            hasFlashlightHardware = true
        )

        val provider = com.example.nova.tools.NovaDeviceInfoProvider(
            object : com.example.nova.tools.DeviceInfoDataSource {
                override fun captureRawReadings() = raw
            }
        )
        val outcome = provider.queryDeviceInfo()
        assertTrue(outcome is com.example.nova.tools.DeviceInfoQueryOutcome.Success)
        val info = (outcome as com.example.nova.tools.DeviceInfoQueryOutcome.Success).deviceInfo

        // 1. Battery & 2. Charging state/source
        assertEquals(84, info.batteryPercentage.valueOrNull())
        assertEquals(
            com.example.nova.core.BatteryChargingState.CHARGING,
            info.chargingState.valueOrNull()
        )
        assertEquals(
            com.example.nova.core.BatteryChargingSource.USB,
            info.chargingSource.valueOrNull()
        )

        // 3. RAM
        assertEquals(4L * 1024L * 1024L * 1024L, info.totalRamBytes.valueOrNull())
        assertEquals(1536L * 1024L * 1024L, info.availableRamBytes.valueOrNull())
        assertEquals(false, info.isLowRamDevice.valueOrNull())

        // 4. Storage
        assertEquals("Internal Data Partition (/data)", info.storageScopeDescription.valueOrNull())
        assertEquals(64L * 1024L * 1024L * 1024L, info.totalStorageBytes.valueOrNull())
        assertEquals(22L * 1024L * 1024L * 1024L, info.availableStorageBytes.valueOrNull())

        // 5. Android & Device
        assertEquals("15", info.androidVersionRelease.valueOrNull())
        assertEquals(35, info.sdkInt.valueOrNull())
        assertEquals("Google Pixel 8", info.safeDeviceModel.valueOrNull())

        // 6. Network
        assertEquals(
            com.example.nova.core.NetworkConnectivityState.CONNECTED,
            info.networkConnectivity.valueOrNull()
        )
        assertEquals(
            com.example.nova.core.NetworkTransportType.WIFI,
            info.networkTransport.valueOrNull()
        )

        val rows = com.example.nova.ui.formatDeviceInfoDisplayRows(info)
        assertEquals("84%", rows.batteryLevelText)
        assertEquals("Charging", rows.chargingStateText)
        assertEquals("USB Port", rows.chargingSourceText)
        assertEquals("1.50 GB", rows.availableRamText)
        assertEquals("4.00 GB", rows.totalRamText)
        assertEquals("Internal Data Partition (/data)", rows.storageScopeText)
        assertEquals("22.00 GB", rows.availableStorageText)
        assertEquals("64.00 GB", rows.totalStorageText)
        assertEquals("Android 15", rows.androidVersionText)
        assertEquals("API 35", rows.sdkLevelText)
        assertEquals("Google Pixel 8", rows.deviceModelText)
        assertEquals("Connected", rows.networkConnectivityText)
        assertEquals("Wi-Fi", rows.networkTransportText)
    }

    @Test
    fun `M4 individual unavailable fields report Unavailable and never invent 0 percent or fake fallback values`() {
        val partialRaw = com.example.nova.tools.RawAndroidDeviceReadings(
            batteryIntentPresent = false,
            batteryLevel = null,
            batteryScale = null,
            batteryStatusConstant = android.os.BatteryManager.BATTERY_STATUS_UNKNOWN,
            batteryPluggedConstant = null,
            totalRamBytes = null,
            availableRamBytes = null,
            isLowRamDevice = null,
            storageScopeLabel = null,
            totalStorageBytes = null,
            availableStorageBytes = null,
            androidRelease = "14",
            sdkInt = 34,
            manufacturer = null,
            model = null,
            connectivityManagerAvailable = false,
            hasActiveNetwork = null,
            hasInternetCapability = null,
            activeTransportConstants = null,
            hasFlashlightHardware = null
        )

        val info = com.example.nova.tools.NovaDeviceInfoProvider.mapRawReadingsToDeviceInfo(partialRaw)
        assertFalse(info.batteryPercentage.isAvailable)
        assertFalse(info.chargingState.isAvailable)
        assertFalse(info.chargingSource.isAvailable)
        assertFalse(info.totalRamBytes.isAvailable)
        assertFalse(info.availableRamBytes.isAvailable)
        assertFalse(info.totalStorageBytes.isAvailable)
        assertFalse(info.availableStorageBytes.isAvailable)
        assertFalse(info.safeDeviceModel.isAvailable)
        assertFalse(info.networkConnectivity.isAvailable)
        assertFalse(info.networkTransport.isAvailable)

        // Android version & SDK are available and preserved
        assertTrue(info.androidVersionRelease.isAvailable)
        assertEquals("14", info.androidVersionRelease.valueOrNull())
        assertEquals(34, info.sdkInt.valueOrNull())

        // Verify display formatting never turns missing values into 0, 0%, or empty strings
        val rows = com.example.nova.ui.formatDeviceInfoDisplayRows(info)
        assertEquals("Unavailable", rows.batteryLevelText)
        assertEquals("Unavailable", rows.chargingStateText)
        assertEquals("Unavailable", rows.chargingSourceText)
        assertEquals("Unavailable", rows.availableRamText)
        assertEquals("Unavailable", rows.totalRamText)
        assertEquals("Unavailable", rows.storageScopeText)
        assertEquals("Unavailable", rows.availableStorageText)
        assertEquals("Unavailable", rows.totalStorageText)
        assertEquals("Unavailable", rows.deviceModelText)
        assertEquals("Unavailable", rows.networkConnectivityText)
        assertEquals("Unavailable", rows.networkTransportText)
        assertEquals("Android 14", rows.androidVersionText)
        assertEquals("API 34", rows.sdkLevelText)
    }

    @Test
    fun `M4 provider failure and workspace card mapping through NovaUiStateMapper`() {
        // 1. LocalDeterministicParser routes device info queries to query_device_info
        val parsed = LocalDeterministicParser.tryParse("show device info")
        assertNotNull(parsed)
        assertEquals("query_device_info", parsed?.toolName)

        // 2. Provider exception -> ProviderFailure
        val throwingProvider = com.example.nova.tools.NovaDeviceInfoProvider(
            object : com.example.nova.tools.DeviceInfoDataSource {
                override fun captureRawReadings(): com.example.nova.tools.RawAndroidDeviceReadings {
                    throw IllegalStateException("System telemetry binder failure")
                }
            }
        )
        val failureOutcome = throwingProvider.queryDeviceInfo()
        assertTrue(failureOutcome is com.example.nova.tools.DeviceInfoQueryOutcome.ProviderFailure)

        // 3. All subsystems empty/failed -> ProviderFailure
        val allEmptyProvider = com.example.nova.tools.NovaDeviceInfoProvider(
            object : com.example.nova.tools.DeviceInfoDataSource {
                override fun captureRawReadings() = com.example.nova.tools.RawAndroidDeviceReadings(
                    batteryIntentPresent = false,
                    batteryLevel = null,
                    batteryScale = null,
                    batteryStatusConstant = null,
                    batteryPluggedConstant = null,
                    totalRamBytes = null,
                    availableRamBytes = null,
                    isLowRamDevice = null,
                    storageScopeLabel = null,
                    totalStorageBytes = null,
                    availableStorageBytes = null,
                    androidRelease = null,
                    sdkInt = null,
                    manufacturer = null,
                    model = null,
                    connectivityManagerAvailable = false,
                    hasActiveNetwork = null,
                    hasInternetCapability = null,
                    activeTransportConstants = null,
                    hasFlashlightHardware = null
                )
            }
        )
        assertTrue(allEmptyProvider.queryDeviceInfo() is com.example.nova.tools.DeviceInfoQueryOutcome.ProviderFailure)

        // 4. NovaUiStateMapper maps verified query_device_info + NovaDeviceInfo to WorkspaceCardState.DeviceInfoWorkspace
        val sampleInfo = com.example.nova.tools.NovaDeviceInfoProvider.mapRawReadingsToDeviceInfo(
            com.example.nova.tools.RawAndroidDeviceReadings(
                batteryIntentPresent = true,
                batteryLevel = 90,
                batteryScale = 100,
                batteryStatusConstant = android.os.BatteryManager.BATTERY_STATUS_DISCHARGING,
                batteryPluggedConstant = 0,
                totalRamBytes = 3072L * 1024L * 1024L,
                availableRamBytes = 1200L * 1024L * 1024L,
                isLowRamDevice = true,
                storageScopeLabel = "Internal Data Partition (/data)",
                totalStorageBytes = 32L * 1024L * 1024L * 1024L,
                availableStorageBytes = 10L * 1024L * 1024L * 1024L,
                androidRelease = "15",
                sdkInt = 35,
                manufacturer = "Google",
                model = "Pixel",
                connectivityManagerAvailable = true,
                hasActiveNetwork = false,
                hasInternetCapability = false,
                activeTransportConstants = emptySet(),
                hasFlashlightHardware = false
            )
        )
        val verifiedDeviceResult = VerifiedActionResult(
            toolName = "query_device_info",
            outcome = VerificationOutcome.VERIFIED_SUCCESS,
            verificationDetail = "Queried real Android device info.",
            targetPackage = "com.example",
            preStateSummary = "Requested",
            postStateSummary = "Battery 90%",
            userFacingMessage = "Battery: 90%",
            elapsedMs = 4L
        )
        val workspaceCard = NovaUiStateMapper.deriveWorkspaceCardState(
            sessionStage = AssistantSessionStage.IDLE,
            pendingConfirmation = null,
            lastVerifiedResult = verifiedDeviceResult,
            voiceState = VoiceEngineState.Idle,
            statusBanner = "query_device_info: VERIFIED_SUCCESS",
            latestDeviceInfo = sampleInfo
        )
        assertTrue(workspaceCard is WorkspaceCardState.DeviceInfoWorkspace)
        val deviceWorkspace = workspaceCard as WorkspaceCardState.DeviceInfoWorkspace
        assertEquals(90, deviceWorkspace.deviceInfo.batteryPercentage.valueOrNull())
        assertEquals(
            com.example.nova.core.BatteryChargingSource.UNPLUGGED,
            deviceWorkspace.deviceInfo.chargingSource.valueOrNull()
        )
        assertEquals(
            com.example.nova.core.NetworkConnectivityState.DISCONNECTED,
            deviceWorkspace.deviceInfo.networkConnectivity.valueOrNull()
        )
        assertEquals(
            com.example.nova.core.NetworkTransportType.NONE,
            deviceWorkspace.deviceInfo.networkTransport.valueOrNull()
        )

        // 5. Failed query_device_info maps to ERROR orb state and ErrorOrUnavailableWorkspace
        val failedDeviceResult = verifiedDeviceResult.copy(
            outcome = VerificationOutcome.VERIFICATION_FAILED,
            userFacingMessage = "Device information provider failed"
        )
        assertEquals(
            NovaUiOrbState.ERROR,
            NovaUiStateMapper.deriveOrbState(
                voiceState = VoiceEngineState.Idle,
                sessionStage = AssistantSessionStage.ERROR,
                lastVerifiedResult = failedDeviceResult
            )
        )
        val errorWorkspace = NovaUiStateMapper.deriveWorkspaceCardState(
            sessionStage = AssistantSessionStage.ERROR,
            pendingConfirmation = null,
            lastVerifiedResult = failedDeviceResult,
            voiceState = VoiceEngineState.Idle,
            statusBanner = "query_device_info: VERIFICATION_FAILED",
            latestDeviceInfo = null
        )
        assertTrue(errorWorkspace is WorkspaceCardState.ErrorOrUnavailableWorkspace)
    }

    @Test
    fun `M5 command routing for supported photo commands and Android version permission resolution`() {
        val mediastoreCommands = listOf(
            "show my photos",
            "open my recent photos",
            "show recent photos",
            "show recent pictures",
            "open recent pictures",
            "show photos from my gallery",
            "show gallery"
        )
        for (cmd in mediastoreCommands) {
            val parsed = LocalDeterministicParser.tryParse(cmd)
            assertNotNull("Expected command '$cmd' to route to query_photos", parsed)
            assertEquals("query_photos", parsed?.toolName)
            assertEquals("mediastore", parsed?.arguments?.get("mode"))
        }

        val pickerCommands = listOf(
            "pick photos",
            "select photos",
            "open photo picker"
        )
        for (cmd in pickerCommands) {
            val parsed = LocalDeterministicParser.tryParse(cmd)
            assertNotNull("Expected picker command '$cmd' to route to query_photos", parsed)
            assertEquals("query_photos", parsed?.toolName)
            assertEquals("picker", parsed?.arguments?.get("mode"))
        }

        // Unrelated commands must NOT accidentally trigger photo access
        val screenshotCall = LocalDeterministicParser.tryParse("take screenshot")
        assertEquals("capture_visual_fallback", screenshotCall?.toolName)

        // Android-version-specific MediaStore permission requirements
        val api34Perms = com.example.nova.tools.PhotoAccessManager.requiredMediaStorePermissionsForSdk(34)
        assertTrue(api34Perms.contains(android.Manifest.permission.READ_MEDIA_IMAGES))
        assertTrue(api34Perms.contains(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        assertFalse(api34Perms.contains(android.Manifest.permission.READ_EXTERNAL_STORAGE))

        val api33Perms = com.example.nova.tools.PhotoAccessManager.requiredMediaStorePermissionsForSdk(33)
        assertEquals(listOf(android.Manifest.permission.READ_MEDIA_IMAGES), api33Perms)

        val api31Perms = com.example.nova.tools.PhotoAccessManager.requiredMediaStorePermissionsForSdk(31)
        assertEquals(listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), api31Perms)
    }

    @Test
    fun `M5 Photo Picker URI persistence vs session-scoped URI handling and revoked URI pruning`() {
        val persistedDiskStore = mutableSetOf<String>()
        val accessibleUris = mutableSetOf(
            "content://media/picker/0/session_only_101",
            "content://media/picker/0/persistable_202"
        )

        val fakeSource = object : com.example.nova.tools.PhotoMediaDataSource {
            override val sdkInt: Int = 35
            override fun isPhotoPickerSupported(): Boolean = true
            override fun checkMediaPermissionState() = com.example.nova.tools.MediaPermissionGrantState.DENIED
            override fun queryMediaStoreImages(maxItems: Int) = emptyList<com.example.nova.tools.RawMediaRecord>()
            override fun isUriAccessible(uriString: String): Boolean = uriString in accessibleUris
            override fun tryTakePersistableReadPermission(uriString: String): UriPersistenceState {
                return if (uriString.contains("persistable")) {
                    UriPersistenceState.PERSISTABLE_GRANTED
                } else {
                    UriPersistenceState.SESSION_SCOPED_ONLY
                }
            }

            override fun queryUriMetadata(uriString: String): com.example.nova.tools.RawMediaRecord? {
                if (uriString !in accessibleUris) return null
                return if (uriString.contains("persistable")) {
                    com.example.nova.tools.RawMediaRecord(
                        contentUri = uriString,
                        displayName = "IMG_202.jpg",
                        dateTakenMillis = 1710000000000L,
                        widthPx = 1920,
                        heightPx = 1080,
                        mimeType = "image/jpeg"
                    )
                } else {
                    // Missing metadata fields on session-scoped URI
                    com.example.nova.tools.RawMediaRecord(
                        contentUri = uriString,
                        displayName = null,
                        dateTakenMillis = null,
                        widthPx = null,
                        heightPx = null,
                        mimeType = null
                    )
                }
            }

            override fun decodeThumbnail(
                uriString: String,
                targetSizePx: Int,
                lowRamMode: Boolean
            ) = com.example.nova.tools.ThumbnailDecodeOutcome.PreviewUnavailable("Preview unavailable")

            override fun decodeFullscreenImage(
                uriString: String,
                maxDimensionPx: Int,
                lowRamMode: Boolean
            ) = com.example.nova.tools.FullscreenPhotoLoadOutcome.DecodeError("Decode unavailable in pure JVM test")

            override fun loadPersistedUriStrings(): Set<String> = persistedDiskStore.toSet()
            override fun savePersistedUriStrings(uris: Set<String>) {
                persistedDiskStore.clear()
                persistedDiskStore.addAll(uris)
            }
        }

        val manager = com.example.nova.tools.PhotoAccessManager(fakeSource)

        // 1. Session-only URI: must NOT be persisted to disk, mode is PICKED_URI_SESSION
        val sessionState = manager.ingestPickedUris(listOf("content://media/picker/0/session_only_101"))
        assertEquals(com.example.nova.core.PhotoAccessMode.PICKED_URI_SESSION, sessionState.accessMode)
        assertEquals(1, sessionState.photos.size)
        assertEquals(
            UriPersistenceState.SESSION_SCOPED_ONLY,
            sessionState.photos.first().uriPersistenceState
        )
        assertTrue("Session-only URI must never be saved to persistent storage", persistedDiskStore.isEmpty())
        // Picker selection must never imply unrestricted MediaStore permission
        assertEquals(
            MediaStoreQueryAccessState.LIMITED_TO_APP_OWNED_MEDIA_WITHOUT_PERMISSION,
            sessionState.mediaStoreAccessState
        )

        // Verify missing metadata fields are represented as Unavailable (never fake filename/date/dimensions)
        val missingMetaPhoto = sessionState.photos.first()
        assertFalse(missingMetaPhoto.displayName.isAvailable)
        assertFalse(missingMetaPhoto.dateTakenOrAddedMillis.isAvailable)
        assertFalse(missingMetaPhoto.widthPx.isAvailable)
        assertFalse(missingMetaPhoto.heightPx.isAvailable)
        val formattedMissing = com.example.nova.ui.formatPhotoItemDisplayMetadata(missingMetaPhoto)
        assertEquals("Unavailable", formattedMissing.displayNameText)
        assertEquals("Unavailable", formattedMissing.dateText)
        assertEquals("Unavailable", formattedMissing.dimensionsText)
        assertEquals("Unavailable", formattedMissing.mimeTypeText)

        // 2. Persistable URI: saved to persistent storage and marked PICKED_URI_PERSISTED
        val persistedState = manager.ingestPickedUris(listOf("content://media/picker/0/persistable_202"))
        assertEquals(com.example.nova.core.PhotoAccessMode.PICKED_URI_PERSISTED, persistedState.accessMode)
        assertEquals(setOf("content://media/picker/0/persistable_202"), persistedDiskStore)
        val validMetaPhoto = persistedState.photos.first()
        assertEquals("IMG_202.jpg", validMetaPhoto.displayName.valueOrNull())
        assertEquals(1920, validMetaPhoto.widthPx.valueOrNull())
        assertEquals(1080, validMetaPhoto.heightPx.valueOrNull())

        // 3. Revoke URI access -> verifyAndPruneIfRevoked prunes it from persisted storage and reports ERROR when ingested
        accessibleUris.remove("content://media/picker/0/persistable_202")
        assertFalse(manager.verifyAndPruneIfRevoked("content://media/picker/0/persistable_202"))
        assertTrue(persistedDiskStore.isEmpty())

        val revokedIngestState = manager.ingestPickedUris(listOf("content://media/picker/0/revoked_999"))
        assertEquals(com.example.nova.core.PhotoAccessMode.ERROR, revokedIngestState.accessMode)
        assertTrue(revokedIngestState.photos.isEmpty())
    }

    @Test
    fun `M5 MediaStore query mapping, empty library, permission required, unsupported capability, error, and thumbnail failure`() {
        var permissionState = com.example.nova.tools.MediaPermissionGrantState.DENIED
        var pickerSupported = true
        var mediaStoreRecords = listOf<com.example.nova.tools.RawMediaRecord>()
        var shouldThrowOnQuery = false

        val source = object : com.example.nova.tools.PhotoMediaDataSource {
            override val sdkInt: Int = 34
            override fun isPhotoPickerSupported(): Boolean = pickerSupported
            override fun checkMediaPermissionState() = permissionState
            override fun queryMediaStoreImages(maxItems: Int): List<com.example.nova.tools.RawMediaRecord> {
                if (shouldThrowOnQuery) throw IllegalStateException("MediaStore provider crashed")
                return mediaStoreRecords.take(maxItems)
            }
            override fun isUriAccessible(uriString: String): Boolean = uriString.startsWith("content://")
            override fun tryTakePersistableReadPermission(uriString: String) = UriPersistenceState.SESSION_SCOPED_ONLY
            override fun queryUriMetadata(uriString: String) = null
            override fun decodeThumbnail(
                uriString: String,
                targetSizePx: Int,
                lowRamMode: Boolean
            ) = com.example.nova.tools.ThumbnailDecodeOutcome.PreviewUnavailable("Corrupt or unreadable image stream")

            override fun decodeFullscreenImage(
                uriString: String,
                maxDimensionPx: Int,
                lowRamMode: Boolean
            ) = com.example.nova.tools.FullscreenPhotoLoadOutcome.InaccessibleOrRevoked("URI revoked")

            override fun loadPersistedUriStrings(): Set<String> = emptySet()
            override fun savePersistedUriStrings(uris: Set<String>) = Unit
        }

        val manager = com.example.nova.tools.PhotoAccessManager(source)

        // 1. Permission Required state (no fake URIs or thumbnails)
        val deniedState = manager.queryPhotoWorkspace(preferPicker = false, lowRamMode = false)
        assertEquals(com.example.nova.core.PhotoAccessMode.PERMISSION_REQUIRED, deniedState.accessMode)
        assertTrue(deniedState.photos.isEmpty())

        // 2. Unsupported capability state
        permissionState = com.example.nova.tools.MediaPermissionGrantState.UNSUPPORTED
        pickerSupported = false
        val unsupportedState = manager.queryPhotoWorkspace(preferPicker = false, lowRamMode = false)
        assertEquals(com.example.nova.core.PhotoAccessMode.UNAVAILABLE, unsupportedState.accessMode)
        assertTrue(unsupportedState.photos.isEmpty())

        // 3. Real Empty MediaStore state (permission granted, 0 photos on device)
        permissionState = com.example.nova.tools.MediaPermissionGrantState.FULL_GRANTED
        pickerSupported = true
        mediaStoreRecords = emptyList()
        val emptyState = manager.queryPhotoWorkspace(preferPicker = false, lowRamMode = false)
        assertEquals(com.example.nova.core.PhotoAccessMode.EMPTY, emptyState.accessMode)
        assertEquals(MediaStoreQueryAccessState.QUERY_EMPTY, emptyState.mediaStoreAccessState)
        assertTrue(emptyState.photos.isEmpty())

        // 4. Real MediaStore photos available (selective photo access on Android 14+)
        permissionState = com.example.nova.tools.MediaPermissionGrantState.SELECTIVE_USER_SELECTED_GRANTED
        mediaStoreRecords = listOf(
            com.example.nova.tools.RawMediaRecord(
                contentUri = "content://media/external/images/media/501",
                displayName = "Camera_501.jpg",
                dateAddedSeconds = 1720000000L,
                widthPx = 4000,
                heightPx = 3000,
                mimeType = "image/jpeg"
            )
        )
        val mediaStoreState = manager.queryPhotoWorkspace(preferPicker = false, lowRamMode = true)
        assertEquals(com.example.nova.core.PhotoAccessMode.MEDIASTORE_ACCESS, mediaStoreState.accessMode)
        assertTrue(mediaStoreState.isSelectiveMediaAccess)
        assertEquals(1, mediaStoreState.photos.size)
        assertEquals("content://media/external/images/media/501", mediaStoreState.photos.first().contentUri)

        // Verify WorkspaceCardState mapping for successful real-media workspace
        val verifiedPhotoSuccess = VerifiedActionResult(
            toolName = "query_photos",
            outcome = VerificationOutcome.VERIFIED_SUCCESS,
            verificationDetail = "Obtained 1 real photo",
            targetPackage = "android.provider.MediaStore",
            preStateSummary = "Requested photos",
            postStateSummary = "1 real photo",
            userFacingMessage = mediaStoreState.statusMessage,
            elapsedMs = 5L
        )
        val mappedCard = NovaUiStateMapper.deriveWorkspaceCardState(
            sessionStage = AssistantSessionStage.IDLE,
            pendingConfirmation = null,
            lastVerifiedResult = verifiedPhotoSuccess,
            voiceState = VoiceEngineState.Idle,
            statusBanner = "query_photos: VERIFIED_SUCCESS",
            latestPhotoWorkspaceState = mediaStoreState
        )
        assertTrue(mappedCard is WorkspaceCardState.PhotoWorkspace)
        assertEquals(
            com.example.nova.core.PhotoAccessMode.MEDIASTORE_ACCESS,
            (mappedCard as WorkspaceCardState.PhotoWorkspace).photoState.accessMode
        )

        // 5. Thumbnail decode failure returns PreviewUnavailable (never a fake thumbnail)
        val thumbOutcome = kotlinx.coroutines.runBlocking {
            manager.loadThumbnail("content://media/external/images/media/501", lowRamMode = true)
        }
        assertTrue(thumbOutcome is com.example.nova.tools.ThumbnailDecodeOutcome.PreviewUnavailable)

        // 6. ContentResolver / Provider failure -> ERROR state
        shouldThrowOnQuery = true
        val errorState = manager.queryPhotoWorkspace(preferPicker = false, lowRamMode = false)
        assertEquals(com.example.nova.core.PhotoAccessMode.ERROR, errorState.accessMode)
        assertEquals(MediaStoreQueryAccessState.QUERY_ERROR, errorState.mediaStoreAccessState)
    }

    private fun testRect(l: Int, t: Int, r: Int, b: Int): android.graphics.Rect =
        android.graphics.Rect().apply {
            left = l
            top = t
            right = r
            bottom = b
        }

    private class FakeAccessibilityNode(
        override val nodeIdentityId: Int,
        override val packageName: String? = "com.android.settings",
        override val className: String? = "android.widget.TextView",
        var mutableText: String? = null,
        override val contentDescription: String? = null,
        override val viewIdResourceName: String? = null,
        override val isClickable: Boolean = false,
        override val isLongClickable: Boolean = false,
        override val isFocusable: Boolean = false,
        var mutableFocused: Boolean = false,
        override val isEditable: Boolean = false,
        override val isScrollable: Boolean = false,
        override val isEnabled: Boolean = true,
        override val isCheckable: Boolean = false,
        var mutableChecked: Boolean = false,
        var mutableSelected: Boolean = false,
        override val isPassword: Boolean = false,
        override val isVisibleToUser: Boolean = true,
        var mutableBounds: android.graphics.Rect = android.graphics.Rect().apply {
            left = 10
            top = 10
            right = 200
            bottom = 80
        },
        override val supportedActions: Set<com.example.nova.core.SupportedAccessibilityAction> = emptySet(),
        var valid: Boolean = true,
        val childrenList: MutableList<FakeAccessibilityNode> = mutableListOf(),
        var parentNode: FakeAccessibilityNode? = null,
        var onPerformAction: ((com.example.nova.core.SupportedAccessibilityAction, String?) -> Boolean)? = null
    ) : com.example.nova.accessibility.AccessibilityNodeAdapter {
        override val text: String? get() = mutableText
        override val isFocused: Boolean get() = mutableFocused
        override val isChecked: Boolean get() = mutableChecked
        override val isSelected: Boolean get() = mutableSelected
        override val boundsInScreen: android.graphics.Rect get() = mutableBounds
        override val childCount: Int get() = childrenList.size

        override fun getChild(index: Int): com.example.nova.accessibility.AccessibilityNodeAdapter? =
            childrenList.getOrNull(index)

        override fun getParent(): com.example.nova.accessibility.AccessibilityNodeAdapter? = parentNode
        override fun isNodeValid(): Boolean = valid
        override fun refreshNode(): Boolean = valid
        override fun releaseTransientNode() = Unit

        override fun performAction(
            action: com.example.nova.core.SupportedAccessibilityAction,
            textArgument: String?
        ): Boolean {
            return onPerformAction?.invoke(action, textArgument) ?: false
        }
    }

    @Test
    fun `M6 service disabled, service enabled but not connected, and connected with null active window states`() {
        // 1. Service disabled in Settings and not connected -> SERVICE_DISABLED
        val disabledState = com.example.nova.accessibility.AccessibilityActionPerformer.buildWorkspaceState(
            serviceEnabledInSettings = false,
            serviceConnected = false,
            snapshot = null
        )
        assertEquals(
            com.example.nova.core.AccessibilityWorkspaceStatus.SERVICE_DISABLED,
            disabledState.status
        )
        assertFalse(disabledState.serviceConnected)
        assertFalse(disabledState.activeWindowAvailable)
        assertFalse(disabledState.activePackage.isAvailable)

        val disabledSummary = com.example.nova.ui.formatAccessibilityWorkspaceSummary(disabledState)
        assertEquals("Accessibility access required", disabledSummary.headlineText)
        assertEquals("Unavailable", disabledSummary.activePackageText)
        assertTrue(disabledSummary.requiresSettingsAction)

        // 2. Service enabled in Settings but NOT connected by OS lifecycle -> SERVICE_NOT_CONNECTED (never inferred as connected)
        val notConnectedState = com.example.nova.accessibility.AccessibilityActionPerformer.buildWorkspaceState(
            serviceEnabledInSettings = true,
            serviceConnected = false,
            snapshot = null
        )
        assertEquals(
            com.example.nova.core.AccessibilityWorkspaceStatus.SERVICE_NOT_CONNECTED,
            notConnectedState.status
        )
        assertFalse(notConnectedState.serviceConnected)
        assertFalse(notConnectedState.activeWindowAvailable)

        val notConnectedSummary = com.example.nova.ui.formatAccessibilityWorkspaceSummary(notConnectedState)
        assertEquals("Accessibility service not connected", notConnectedSummary.headlineText)
        assertEquals("Enabled in Settings • Not Connected", notConnectedSummary.serviceConnectionText)

        // 3. Service connected prior to window inspection -> SERVICE_CONNECTED
        val connectedOnlyState = com.example.nova.accessibility.AccessibilityActionPerformer.buildWorkspaceState(
            serviceEnabledInSettings = true,
            serviceConnected = true,
            snapshot = null,
            hasInspectedWindow = false
        )
        assertEquals(
            com.example.nova.core.AccessibilityWorkspaceStatus.SERVICE_CONNECTED,
            connectedOnlyState.status
        )
        assertTrue(connectedOnlyState.serviceConnected)

        // 4. Service connected with null active window -> ACTIVE_WINDOW_UNAVAILABLE
        val nullWindowState = com.example.nova.accessibility.AccessibilityActionPerformer.buildWorkspaceState(
            serviceEnabledInSettings = true,
            serviceConnected = true,
            snapshot = null,
            hasInspectedWindow = true
        )
        assertEquals(
            com.example.nova.core.AccessibilityWorkspaceStatus.ACTIVE_WINDOW_UNAVAILABLE,
            nullWindowState.status
        )
        assertTrue(nullWindowState.serviceConnected)
        assertFalse(nullWindowState.activeWindowAvailable)
        assertEquals("Unavailable", com.example.nova.ui.formatAccessibilityWorkspaceSummary(nullWindowState).activePackageText)

        // 5. Null root extraction returns null snapshot (never fake nodes)
        val nullSnapshot = com.example.nova.accessibility.SemanticScreenExtractor.extractSnapshotFromAdapter(
            rootAdapter = null
        )
        assertEquals(null, nullSnapshot)

        // 6. ScreenUnderstandingFallback reports NOT_IMPLEMENTED and never fakes OCR/vision
        val fallback = com.example.nova.accessibility.ScreenUnderstandingFallback.queryFallbackStatus()
        assertFalse(fallback.succeeded)
        assertEquals(
            com.example.nova.core.ScreenUnderstandingFallbackStatus.NOT_IMPLEMENTED,
            fallback.status
        )
    }

    @Test
    fun `M6 semantic tree extraction, sensitive field redaction, and bounded cycle-safe traversal`() {
        val root = FakeAccessibilityNode(
            nodeIdentityId = 1,
            packageName = "com.example.banking",
            className = "android.widget.FrameLayout",
            mutableBounds = testRect(0, 0, 1080, 2400)
        )
        val header = FakeAccessibilityNode(
            nodeIdentityId = 2,
            packageName = "com.example.banking",
            className = "android.widget.TextView",
            mutableText = "Account Overview",
            mutableBounds = testRect(20, 40, 600, 110)
        )
        val passwordInput = FakeAccessibilityNode(
            nodeIdentityId = 3,
            packageName = "com.example.banking",
            className = "android.widget.EditText",
            mutableText = "SuperSecretPin999",
            viewIdResourceName = "com.example.banking:id/password_input",
            isEditable = true,
            isFocusable = true,
            isPassword = true,
            supportedActions = setOf(
                com.example.nova.core.SupportedAccessibilityAction.FOCUS,
                com.example.nova.core.SupportedAccessibilityAction.SET_TEXT
            ),
            mutableBounds = testRect(20, 150, 900, 230)
        )
        val transferButton = FakeAccessibilityNode(
            nodeIdentityId = 4,
            packageName = "com.example.banking",
            className = "android.widget.Button",
            mutableText = "Transfer Funds",
            contentDescription = "Submit transfer",
            viewIdResourceName = "com.example.banking:id/btn_transfer",
            isClickable = true,
            isFocusable = true,
            supportedActions = setOf(
                com.example.nova.core.SupportedAccessibilityAction.CLICK,
                com.example.nova.core.SupportedAccessibilityAction.FOCUS
            ),
            mutableBounds = testRect(20, 260, 500, 340)
        )

        // Introduce a cycle (transferButton -> root) to verify cycle-safe bounded traversal
        transferButton.childrenList.add(root)
        root.childrenList.addAll(listOf(header, passwordInput, transferButton))

        val snapshot = com.example.nova.accessibility.SemanticScreenExtractor.extractSnapshotFromAdapter(
            rootAdapter = root,
            activePackageOverride = "com.example.banking",
            windowTitleOverride = "Banking Home",
            lowRamMode = true
        )
        assertNotNull(snapshot)
        assertEquals("com.example.banking", snapshot!!.packageName)
        assertEquals("Banking Home", snapshot.windowTitle)
        assertEquals(3, snapshot.targets.size)
        assertEquals(1, snapshot.redactedFieldCount)

        // Verify password node was redacted and marked isSensitiveRedacted
        val extractedPwTarget = snapshot.targets.first { it.viewIdResourceName.endsWith("password_input") }
        assertTrue(extractedPwTarget.isSensitiveRedacted)
        assertEquals("[REDACTED_PASSWORD_FIELD]", extractedPwTarget.visibleText)
        assertFalse(extractedPwTarget.visibleText.contains("SuperSecretPin999"))

        // Verify bounded traversal when tree exceeds Low-RAM node limit
        val massiveRoot = FakeAccessibilityNode(
            nodeIdentityId = 100,
            packageName = "com.example.feed",
            className = "androidx.recyclerview.widget.RecyclerView",
            isScrollable = true,
            supportedActions = setOf(com.example.nova.core.SupportedAccessibilityAction.SCROLL_FORWARD),
            mutableBounds = testRect(0, 0, 1080, 2400)
        )
        for (i in 1..80) {
            massiveRoot.childrenList.add(
                FakeAccessibilityNode(
                    nodeIdentityId = 100 + i,
                    packageName = "com.example.feed",
                    className = "android.widget.Button",
                    mutableText = "Item $i",
                    isClickable = true,
                    supportedActions = setOf(com.example.nova.core.SupportedAccessibilityAction.CLICK),
                    mutableBounds = testRect(0, i * 20, 500, i * 20 + 18)
                )
            )
        }
        val boundedSnap = com.example.nova.accessibility.SemanticScreenExtractor.extractSnapshotFromAdapter(
            rootAdapter = massiveRoot,
            lowRamMode = true
        )
        assertNotNull(boundedSnap)
        assertEquals(
            com.example.nova.accessibility.SemanticScreenExtractor.MAX_TARGETS_LOW_RAM,
            boundedSnap!!.targets.size
        )
        assertTrue(boundedSnap.isLowRamTruncated)
    }

    @Test
    fun `M6 target resolution by text, contentDescription, viewId, and ambiguity refusal`() {
        val root = FakeAccessibilityNode(
            nodeIdentityId = 1,
            packageName = "com.example.notes",
            className = "android.widget.LinearLayout"
        )
        val byTextBtn = FakeAccessibilityNode(
            nodeIdentityId = 2,
            packageName = "com.example.notes",
            className = "android.widget.Button",
            mutableText = "Archive Note",
            isClickable = true,
            supportedActions = setOf(com.example.nova.core.SupportedAccessibilityAction.CLICK)
        )
        val byDescBtn = FakeAccessibilityNode(
            nodeIdentityId = 3,
            packageName = "com.example.notes",
            className = "android.widget.ImageButton",
            contentDescription = "Voice Search",
            isClickable = true,
            supportedActions = setOf(com.example.nova.core.SupportedAccessibilityAction.CLICK)
        )
        val byIdField = FakeAccessibilityNode(
            nodeIdentityId = 4,
            packageName = "com.example.notes",
            className = "android.widget.EditText",
            viewIdResourceName = "com.example.notes:id/note_title_input",
            isEditable = true,
            supportedActions = setOf(com.example.nova.core.SupportedAccessibilityAction.SET_TEXT)
        )
        val dupSave1 = FakeAccessibilityNode(
            nodeIdentityId = 5,
            packageName = "com.example.notes",
            className = "android.widget.Button",
            mutableText = "Save",
            viewIdResourceName = "com.example.notes:id/save_top",
            isClickable = true,
            supportedActions = setOf(com.example.nova.core.SupportedAccessibilityAction.CLICK),
            mutableBounds = testRect(10, 10, 150, 60)
        )
        val dupSave2 = FakeAccessibilityNode(
            nodeIdentityId = 6,
            packageName = "com.example.notes",
            className = "android.widget.Button",
            mutableText = "Save",
            viewIdResourceName = "com.example.notes:id/save_bottom",
            isClickable = true,
            supportedActions = setOf(com.example.nova.core.SupportedAccessibilityAction.CLICK),
            mutableBounds = testRect(10, 900, 150, 960)
        )
        root.childrenList.addAll(listOf(byTextBtn, byDescBtn, byIdField, dupSave1, dupSave2))

        // Resolve by visible text
        val resText = com.example.nova.accessibility.AccessibilityActionPerformer.resolveTargetInTree(
            rootAdapter = root,
            targetQuery = "Archive Note",
            requestedAction = com.example.nova.core.SupportedAccessibilityAction.CLICK
        )
        assertTrue(resText is com.example.nova.accessibility.TargetResolutionOutcome.Resolved)
        assertEquals(
            "Archive Note",
            (resText as com.example.nova.accessibility.TargetResolutionOutcome.Resolved).target.visibleText
        )

        // Resolve by contentDescription
        val resDesc = com.example.nova.accessibility.AccessibilityActionPerformer.resolveTargetInTree(
            rootAdapter = root,
            targetQuery = "Voice Search",
            requestedAction = com.example.nova.core.SupportedAccessibilityAction.CLICK
        )
        assertTrue(resDesc is com.example.nova.accessibility.TargetResolutionOutcome.Resolved)
        assertEquals(
            "Voice Search",
            (resDesc as com.example.nova.accessibility.TargetResolutionOutcome.Resolved).target.contentDescription
        )

        // Resolve by viewIdResourceName suffix
        val resId = com.example.nova.accessibility.AccessibilityActionPerformer.resolveTargetInTree(
            rootAdapter = root,
            targetQuery = "note_title_input",
            requestedAction = com.example.nova.core.SupportedAccessibilityAction.SET_TEXT
        )
        assertTrue(resId is com.example.nova.accessibility.TargetResolutionOutcome.Resolved)
        assertEquals(
            "com.example.notes:id/note_title_input",
            (resId as com.example.nova.accessibility.TargetResolutionOutcome.Resolved).target.viewIdResourceName
        )

        // Ambiguous target ("Save" matches two distinct buttons) -> refuses to guess!
        val resAmbiguous = com.example.nova.accessibility.AccessibilityActionPerformer.resolveTargetInTree(
            rootAdapter = root,
            targetQuery = "Save",
            requestedAction = com.example.nova.core.SupportedAccessibilityAction.CLICK
        )
        assertTrue(resAmbiguous is com.example.nova.accessibility.TargetResolutionOutcome.Ambiguous)
        assertEquals(
            2,
            (resAmbiguous as com.example.nova.accessibility.TargetResolutionOutcome.Ambiguous).candidates.size
        )

        val ambiguousExec = kotlinx.coroutines.runBlocking {
            com.example.nova.accessibility.AccessibilityActionPerformer.executeAndVerifySemanticAction(
                rootProvider = { root },
                targetQuery = "Save",
                requestedAction = com.example.nova.core.SupportedAccessibilityAction.CLICK,
                settleDelayMs = 0L
            )
        }
        assertEquals(VerificationOutcome.VERIFICATION_FAILED, ambiguousExec.outcome)
        assertEquals(
            com.example.nova.accessibility.AccessibilityActionFailureReason.AMBIGUOUS_TARGET,
            ambiguousExec.failureReason
        )
        assertEquals(2, ambiguousExec.ambiguousCandidates.size)
    }

    @Test
    fun `M6 disabled target rejection, unsupported action rejection, performAction false, and unverified performAction true failure`() {
        val root = FakeAccessibilityNode(
            nodeIdentityId = 1,
            packageName = "com.example.shop",
            className = "android.widget.FrameLayout"
        )
        val disabledBtn = FakeAccessibilityNode(
            nodeIdentityId = 2,
            packageName = "com.example.shop",
            className = "android.widget.Button",
            mutableText = "Checkout",
            isClickable = true,
            isEnabled = false,
            supportedActions = setOf(com.example.nova.core.SupportedAccessibilityAction.CLICK)
        )
        val nonEditableLabel = FakeAccessibilityNode(
            nodeIdentityId = 3,
            packageName = "com.example.shop",
            className = "android.widget.TextView",
            mutableText = "Order Total",
            isClickable = false,
            isEditable = false,
            supportedActions = emptySet()
        )
        val falseReturnBtn = FakeAccessibilityNode(
            nodeIdentityId = 4,
            packageName = "com.example.shop",
            className = "android.widget.Button",
            mutableText = "Apply Coupon",
            isClickable = true,
            isEnabled = true,
            supportedActions = setOf(com.example.nova.core.SupportedAccessibilityAction.CLICK),
            onPerformAction = { _, _ -> false }
        )
        // Node whose performAction returns true, but causes ZERO state change in the tree
        val noOpTrueBtn = FakeAccessibilityNode(
            nodeIdentityId = 5,
            packageName = "com.example.shop",
            className = "android.widget.Button",
            mutableText = "Refresh Status",
            isClickable = true,
            isEnabled = true,
            supportedActions = setOf(com.example.nova.core.SupportedAccessibilityAction.CLICK),
            onPerformAction = { _, _ -> true }
        )
        root.childrenList.addAll(listOf(disabledBtn, nonEditableLabel, falseReturnBtn, noOpTrueBtn))

        // 1. Disabled target rejection
        val disabledOutcome = kotlinx.coroutines.runBlocking {
            com.example.nova.accessibility.AccessibilityActionPerformer.executeAndVerifySemanticAction(
                rootProvider = { root },
                targetQuery = "Checkout",
                requestedAction = com.example.nova.core.SupportedAccessibilityAction.CLICK,
                settleDelayMs = 0L
            )
        }
        assertEquals(VerificationOutcome.VERIFICATION_FAILED, disabledOutcome.outcome)
        assertEquals(
            com.example.nova.accessibility.AccessibilityActionFailureReason.TARGET_DISABLED,
            disabledOutcome.failureReason
        )

        // 2. Unsupported action rejection (SET_TEXT on non-editable TextView)
        val unsupportedOutcome = kotlinx.coroutines.runBlocking {
            com.example.nova.accessibility.AccessibilityActionPerformer.executeAndVerifySemanticAction(
                rootProvider = { root },
                targetQuery = "Order Total",
                requestedAction = com.example.nova.core.SupportedAccessibilityAction.SET_TEXT,
                textArgument = "100",
                settleDelayMs = 0L
            )
        }
        assertEquals(VerificationOutcome.UNSUPPORTED, unsupportedOutcome.outcome)
        assertEquals(
            com.example.nova.accessibility.AccessibilityActionFailureReason.UNSUPPORTED_ACTION,
            unsupportedOutcome.failureReason
        )

        // 3. performAction returning false -> PERFORM_ACTION_RETURNED_FALSE
        val falseOutcome = kotlinx.coroutines.runBlocking {
            com.example.nova.accessibility.AccessibilityActionPerformer.executeAndVerifySemanticAction(
                rootProvider = { root },
                targetQuery = "Apply Coupon",
                requestedAction = com.example.nova.core.SupportedAccessibilityAction.CLICK,
                settleDelayMs = 0L
            )
        }
        assertEquals(VerificationOutcome.VERIFICATION_FAILED, falseOutcome.outcome)
        assertEquals(
            com.example.nova.accessibility.AccessibilityActionFailureReason.PERFORM_ACTION_RETURNED_FALSE,
            falseOutcome.failureReason
        )

        // 4. performAction returning true WITHOUT any resulting state change -> POST_ACTION_VERIFICATION_FAILED
        val unverifiedOutcome = kotlinx.coroutines.runBlocking {
            com.example.nova.accessibility.AccessibilityActionPerformer.executeAndVerifySemanticAction(
                rootProvider = { root },
                targetQuery = "Refresh Status",
                requestedAction = com.example.nova.core.SupportedAccessibilityAction.CLICK,
                settleDelayMs = 0L
            )
        }
        assertEquals(VerificationOutcome.VERIFICATION_FAILED, unverifiedOutcome.outcome)
        assertEquals(
            com.example.nova.accessibility.AccessibilityActionFailureReason.POST_ACTION_VERIFICATION_FAILED,
            unverifiedOutcome.failureReason
        )
    }

    @Test
    fun `M6 verified click, SET_TEXT, FOCUS, SELECT, and SCROLL_FORWARD state transitions`() {
        val root = FakeAccessibilityNode(
            nodeIdentityId = 1,
            packageName = "com.example.settings",
            className = "android.widget.ScrollView",
            isScrollable = true,
            supportedActions = setOf(
                com.example.nova.core.SupportedAccessibilityAction.SCROLL_FORWARD,
                com.example.nova.core.SupportedAccessibilityAction.SCROLL_BACKWARD
            )
        )
        val wifiToggle = FakeAccessibilityNode(
            nodeIdentityId = 2,
            packageName = "com.example.settings",
            className = "android.widget.Switch",
            mutableText = "Wi-Fi",
            viewIdResourceName = "com.example.settings:id/switch_wifi",
            isClickable = true,
            isCheckable = true,
            mutableChecked = false,
            supportedActions = setOf(com.example.nova.core.SupportedAccessibilityAction.CLICK)
        )
        wifiToggle.onPerformAction = { action, _ ->
            if (action == com.example.nova.core.SupportedAccessibilityAction.CLICK) {
                wifiToggle.mutableChecked = true
                true
            } else {
                false
            }
        }

        val searchInput = FakeAccessibilityNode(
            nodeIdentityId = 3,
            packageName = "com.example.settings",
            className = "android.widget.EditText",
            mutableText = "",
            contentDescription = "Search settings",
            viewIdResourceName = "com.example.settings:id/search_src_text",
            isEditable = true,
            isFocusable = true,
            supportedActions = setOf(
                com.example.nova.core.SupportedAccessibilityAction.FOCUS,
                com.example.nova.core.SupportedAccessibilityAction.SET_TEXT
            )
        )
        searchInput.onPerformAction = { action, textArg ->
            when (action) {
                com.example.nova.core.SupportedAccessibilityAction.FOCUS -> {
                    searchInput.mutableFocused = true
                    true
                }
                com.example.nova.core.SupportedAccessibilityAction.SET_TEXT -> {
                    searchInput.mutableText = textArg.orEmpty()
                    true
                }
                else -> false
            }
        }

        val tabItem = FakeAccessibilityNode(
            nodeIdentityId = 4,
            packageName = "com.example.settings",
            className = "android.widget.TabWidget",
            mutableText = "Network Tab",
            supportedActions = setOf(com.example.nova.core.SupportedAccessibilityAction.SELECT)
        )
        tabItem.onPerformAction = { action, _ ->
            if (action == com.example.nova.core.SupportedAccessibilityAction.SELECT) {
                tabItem.mutableSelected = true
                true
            } else {
                false
            }
        }

        root.onPerformAction = { action, _ ->
            if (action == com.example.nova.core.SupportedAccessibilityAction.SCROLL_FORWARD) {
                wifiToggle.mutableBounds = testRect(10, 5, 200, 45)
                true
            } else {
                false
            }
        }
        root.childrenList.addAll(listOf(wifiToggle, searchInput, tabItem))

        // 1. Successful CLICK with verified checked state change
        val clickOutcome = kotlinx.coroutines.runBlocking {
            com.example.nova.accessibility.AccessibilityActionPerformer.executeAndVerifySemanticAction(
                rootProvider = { root },
                targetQuery = "Wi-Fi",
                requestedAction = com.example.nova.core.SupportedAccessibilityAction.CLICK,
                settleDelayMs = 0L
            )
        }
        assertEquals(VerificationOutcome.VERIFIED_SUCCESS, clickOutcome.outcome)
        assertEquals(true, clickOutcome.resolvedTarget?.isChecked)

        // 2. Successful FOCUS with verified focus state change
        val focusOutcome = kotlinx.coroutines.runBlocking {
            com.example.nova.accessibility.AccessibilityActionPerformer.executeAndVerifySemanticAction(
                rootProvider = { root },
                targetQuery = "Search settings",
                requestedAction = com.example.nova.core.SupportedAccessibilityAction.FOCUS,
                settleDelayMs = 0L
            )
        }
        assertEquals(VerificationOutcome.VERIFIED_SUCCESS, focusOutcome.outcome)
        assertEquals(true, focusOutcome.resolvedTarget?.isFocused)

        // 3. Successful SET_TEXT with verified text change in post-action tree
        val setTextOutcome = kotlinx.coroutines.runBlocking {
            com.example.nova.accessibility.AccessibilityActionPerformer.executeAndVerifySemanticAction(
                rootProvider = { root },
                targetQuery = "Search settings",
                requestedAction = com.example.nova.core.SupportedAccessibilityAction.SET_TEXT,
                textArgument = "Bluetooth",
                settleDelayMs = 0L
            )
        }
        assertEquals(VerificationOutcome.VERIFIED_SUCCESS, setTextOutcome.outcome)
        assertEquals("Bluetooth", setTextOutcome.resolvedTarget?.visibleText)

        // 4. Successful SELECT with verified selected state change
        val selectOutcome = kotlinx.coroutines.runBlocking {
            com.example.nova.accessibility.AccessibilityActionPerformer.executeAndVerifySemanticAction(
                rootProvider = { root },
                targetQuery = "Network Tab",
                requestedAction = com.example.nova.core.SupportedAccessibilityAction.SELECT,
                settleDelayMs = 0L
            )
        }
        assertEquals(VerificationOutcome.VERIFIED_SUCCESS, selectOutcome.outcome)
        assertEquals(true, selectOutcome.resolvedTarget?.isSelected)

        // 5. Successful SCROLL_FORWARD with verified node bounds shift
        val scrollOutcome = kotlinx.coroutines.runBlocking {
            com.example.nova.accessibility.AccessibilityActionPerformer.executeAndVerifySemanticAction(
                rootProvider = { root },
                targetQuery = "",
                requestedAction = com.example.nova.core.SupportedAccessibilityAction.SCROLL_FORWARD,
                settleDelayMs = 0L
            )
        }
        assertEquals(VerificationOutcome.VERIFIED_SUCCESS, scrollOutcome.outcome)
    }

    @Test
    fun `M6 confirmation gate for consequential targets and command routing for accessibility inspection and actions`() {
        // 1. Consequential target ("Delete Account") requires confirmation
        val deleteAssessment = com.example.nova.accessibility.AccessibilityActionPerformer.evaluateConfirmationRequirement(
            requestedAction = com.example.nova.core.SupportedAccessibilityAction.CLICK,
            rawTargetQuery = "Delete Account"
        )
        assertTrue(deleteAssessment.requiresConfirmation)

        // 2. Entering text into sensitive password field requires confirmation
        val passwordTarget = com.example.nova.core.AccessibilityTarget(
            targetIndex = 1,
            semanticDescription = "Password",
            visibleText = "[REDACTED_PASSWORD_FIELD]",
            contentDescription = "Password",
            viewIdResourceName = "com.example.auth:id/password",
            className = "android.widget.EditText",
            boundsInScreen = testRect(0, 0, 100, 40),
            supportedActions = setOf(com.example.nova.core.SupportedAccessibilityAction.SET_TEXT),
            isEnabled = true,
            isClickable = true,
            isLongClickable = false,
            isFocusable = true,
            isFocused = false,
            isEditable = true,
            isScrollable = false,
            isCheckable = false,
            isChecked = false,
            isSelected = false,
            isVisibleToUser = true,
            wasRedacted = true
        )
        val pwAssessment = com.example.nova.accessibility.AccessibilityActionPerformer.evaluateConfirmationRequirement(
            requestedAction = com.example.nova.core.SupportedAccessibilityAction.SET_TEXT,
            resolvedTarget = passwordTarget,
            textArgument = "MySecret123"
        )
        assertTrue(pwAssessment.requiresConfirmation)

        // 3. Action inside packageinstaller / financial context requires confirmation
        val installerAssessment = com.example.nova.accessibility.AccessibilityActionPerformer.evaluateConfirmationRequirement(
            requestedAction = com.example.nova.core.SupportedAccessibilityAction.CLICK,
            activePackage = "com.google.android.packageinstaller",
            rawTargetQuery = "Install"
        )
        assertTrue(installerAssessment.requiresConfirmation)

        // 4. Safe navigation / focus / scroll does not require destructive confirmation
        val safeFocusAssessment = com.example.nova.accessibility.AccessibilityActionPerformer.evaluateConfirmationRequirement(
            requestedAction = com.example.nova.core.SupportedAccessibilityAction.FOCUS,
            rawTargetQuery = "Search"
        )
        assertFalse(safeFocusAssessment.requiresConfirmation)

        // 5. Command routing for M6 inspection and actions
        val readScreen = LocalDeterministicParser.tryParse("What is on my screen?")
        assertNotNull(readScreen)
        assertEquals("read_screen", readScreen?.toolName)
        assertEquals(RiskLevel.SAFE, readScreen?.riskLevel)

        val a11yStatus = LocalDeterministicParser.tryParse("Show accessibility status")
        assertNotNull(a11yStatus)
        assertEquals("query_accessibility_status", a11yStatus?.toolName)

        val clickSafe = LocalDeterministicParser.tryParse("Click Wi-Fi")
        assertNotNull(clickSafe)
        assertEquals("click_node", clickSafe?.toolName)
        assertEquals("Wi-Fi", clickSafe?.arguments?.get("target"))
        assertEquals("CLICK", clickSafe?.arguments?.get("action"))
        assertEquals(RiskLevel.SAFE, clickSafe?.riskLevel)

        val clickDestructive = LocalDeterministicParser.tryParse("Click Delete Account")
        assertNotNull(clickDestructive)
        assertEquals("click_node", clickDestructive?.toolName)
        assertEquals(RiskLevel.SENSITIVE_CONFIRM, clickDestructive?.riskLevel)

        val longPress = LocalDeterministicParser.tryParse("Long press Message 1")
        assertNotNull(longPress)
        assertEquals("click_node", longPress?.toolName)
        assertEquals("LONG_CLICK", longPress?.arguments?.get("action"))

        val focusCmd = LocalDeterministicParser.tryParse("Focus on Search")
        assertNotNull(focusCmd)
        assertEquals("focus_node", focusCmd?.toolName)
        assertEquals("Search", focusCmd?.arguments?.get("target"))

        val selectCmd = LocalDeterministicParser.tryParse("Select Option B")
        assertNotNull(selectCmd)
        assertEquals("select_node", selectCmd?.toolName)
        assertEquals("Option B", selectCmd?.arguments?.get("target"))

        val typeCmd = LocalDeterministicParser.tryParse("Type \"hello world\" into Search")
        assertNotNull(typeCmd)
        assertEquals("input_text", typeCmd?.toolName)
        assertEquals("hello world", typeCmd?.arguments?.get("text"))
        assertEquals("Search", typeCmd?.arguments?.get("target"))

        val scrollDown = LocalDeterministicParser.tryParse("Scroll down")
        assertNotNull(scrollDown)
        assertEquals("scroll_screen", scrollDown?.toolName)
        assertEquals("down", scrollDown?.arguments?.get("direction"))

        val scrollUp = LocalDeterministicParser.tryParse("Scroll up")
        assertNotNull(scrollUp)
        assertEquals("scroll_screen", scrollUp?.toolName)
        assertEquals("up", scrollUp?.arguments?.get("direction"))
    }

    @Test
    fun `M7 LiveTaskStateMachine enforces deterministic transitions and rejects invalid transitions and unverified success`() {
        val call = PlannedToolCall(
            toolName = "query_device_info",
            arguments = emptyMap(),
            reasoning = "Check device info",
            expectedOutcomeDescription = "Verified device info",
            riskLevel = RiskLevel.SAFE,
            channel = ExecutionChannel.ANDROID_API_INTENT,
            spokenResponseDraft = "Checking device info."
        )
        val pending = com.example.nova.core.LiveTaskItem(
            taskId = "task-1",
            title = com.example.nova.core.LiveTaskStateMachine.humanReadableTaskTitle(call),
            toolName = call.toolName,
            channel = com.example.nova.core.CrossAppInteractionChannel.ANDROID_SYSTEM_INTENT,
            status = com.example.nova.core.LiveTaskStatus.PENDING,
            capabilityType = com.example.nova.core.NovaTaskCapabilityType.DEVICE_INFO,
            createdAtMs = com.example.nova.core.DeviceFieldState.Available(1000L)
        )
        assertTrue(pending.startedAtMs is com.example.nova.core.DeviceFieldState.Unavailable)
        assertTrue(pending.completedAtMs is com.example.nova.core.DeviceFieldState.Unavailable)

        // PENDING -> RUNNING
        val running = com.example.nova.core.LiveTaskStateMachine.transition(
            task = pending,
            targetStatus = com.example.nova.core.LiveTaskStatus.RUNNING,
            timestampMs = 1050L
        )
        assertEquals(com.example.nova.core.LiveTaskStatus.RUNNING, running.status)
        assertEquals(
            com.example.nova.core.DeviceFieldState.Available(1050L),
            running.startedAtMs
        )
        assertTrue(running.completedAtMs is com.example.nova.core.DeviceFieldState.Unavailable)
        assertEquals("Checking your device information now.", running.statusMessage)

        // RUNNING -> COMPLETED_VERIFIED requires VerifiedActionResult with VERIFIED_SUCCESS
        val failedOutcomeResult = VerifiedActionResult(
            toolName = "query_device_info",
            outcome = VerificationOutcome.VERIFICATION_FAILED,
            verificationDetail = "Failed",
            targetPackage = "android",
            preStateSummary = "Pre",
            postStateSummary = "Post",
            userFacingMessage = "Failed",
            elapsedMs = 12L
        )
        val invalidCompleted = com.example.nova.core.LiveTaskStateMachine.tryTransition(
            task = running,
            targetStatus = com.example.nova.core.LiveTaskStatus.COMPLETED_VERIFIED,
            timestampMs = 1100L,
            verifiedResult = failedOutcomeResult
        )
        org.junit.Assert.assertNull(invalidCompleted)

        val verifiedResult = VerifiedActionResult(
            toolName = "query_device_info",
            outcome = VerificationOutcome.VERIFIED_SUCCESS,
            verificationDetail = "Obtained real OS properties",
            targetPackage = "android",
            preStateSummary = "Querying",
            postStateSummary = "Android 14",
            userFacingMessage = "Device info ready.",
            elapsedMs = 45L
        )
        val completed = com.example.nova.core.LiveTaskStateMachine.transition(
            task = running,
            targetStatus = com.example.nova.core.LiveTaskStatus.COMPLETED_VERIFIED,
            timestampMs = 1095L,
            verifiedResult = verifiedResult
        )
        assertEquals(com.example.nova.core.LiveTaskStatus.COMPLETED_VERIFIED, completed.status)
        assertEquals(
            com.example.nova.core.DeviceFieldState.Available(1095L),
            completed.completedAtMs
        )
        assertEquals(
            com.example.nova.core.LiveTaskWorkspaceReference.DEVICE_INFO_WORKSPACE,
            completed.workspaceReference
        )
        assertEquals("Your device information is ready.", completed.statusMessage)

        // Reject illegal transitions from terminal states (e.g. FAILED -> COMPLETED_VERIFIED, FAILED -> RUNNING)
        val failedTask = com.example.nova.core.LiveTaskStateMachine.transition(
            task = running,
            targetStatus = com.example.nova.core.LiveTaskStatus.FAILED,
            timestampMs = 1100L,
            verifiedResult = failedOutcomeResult,
            reason = "ContentResolver exception"
        )
        assertFalse(
            com.example.nova.core.LiveTaskStateMachine.canTransition(
                com.example.nova.core.LiveTaskStatus.FAILED,
                com.example.nova.core.LiveTaskStatus.COMPLETED_VERIFIED
            )
        )
        assertFalse(
            com.example.nova.core.LiveTaskStateMachine.canTransition(
                com.example.nova.core.LiveTaskStatus.FAILED,
                com.example.nova.core.LiveTaskStatus.RUNNING
            )
        )
        org.junit.Assert.assertNull(
            com.example.nova.core.LiveTaskStateMachine.tryTransition(
                task = failedTask,
                targetStatus = com.example.nova.core.LiveTaskStatus.COMPLETED_VERIFIED,
                verifiedResult = verifiedResult
            )
        )
    }

    @Test
    fun `M7 LocalDeterministicParser tryParseMulti parses compound multi-task requests accurately`() {
        // Scenario S: "Show my photos and device information."
        val scenarioSCalls = LocalDeterministicParser.tryParseMulti("Show my photos and device information.")
        assertEquals(2, scenarioSCalls.size)
        assertEquals("query_photos", scenarioSCalls[0].toolName)
        assertEquals("query_device_info", scenarioSCalls[1].toolName)

        // Three-part compound request with shared verb and explicit verbs
        val threeCalls = LocalDeterministicParser.tryParseMulti(
            "Check device info and read screen and then turn flashlight on"
        )
        assertEquals(3, threeCalls.size)
        assertEquals("query_device_info", threeCalls[0].toolName)
        assertEquals("read_screen", threeCalls[1].toolName)
        assertEquals("set_flashlight", threeCalls[2].toolName)

        // Single command with trailing period
        val singlePhoto = LocalDeterministicParser.tryParseMulti("Show my photos.")
        assertEquals(1, singlePhoto.size)
        assertEquals("query_photos", singlePhoto[0].toolName)

        // Blank input produces zero tasks
        assertTrue(LocalDeterministicParser.tryParseMulti("   ").isEmpty())
    }

    @Test
    fun `M7 NovaMultiTaskCoordinator Scenario S executes independent tasks and isolates permissions and failures`() =
        kotlinx.coroutines.runBlocking {
            val calls = LocalDeterministicParser.tryParseMulti("Show my photos and device information.")
            assertEquals(2, calls.size)

            // Case 1: Photos require permission while Device Info succeeds with verified data
            val coordinator = com.example.nova.agent.NovaMultiTaskCoordinator(
                scope = this,
                clock = { 2000L }
            )

            for (call in calls) {
                coordinator.enqueueTask(
                    originalQuery = "Show my photos and device information.",
                    call = call,
                    providerLabel = "Offline",
                    lowRamMode = false,
                    executor = { planned, _ ->
                        if (planned.toolName == "query_photos") {
                            VerifiedActionResult(
                                toolName = "query_photos",
                                outcome = VerificationOutcome.PERMISSION_REQUIRED,
                                verificationDetail = "READ_MEDIA_IMAGES denied",
                                targetPackage = "com.android.providers.media",
                                preStateSummary = "Permission=DENIED",
                                postStateSummary = "Permission=DENIED",
                                userFacingMessage = "I need photo access.",
                                elapsedMs = 8L
                            )
                        } else {
                            VerifiedActionResult(
                                toolName = "query_device_info",
                                outcome = VerificationOutcome.VERIFIED_SUCCESS,
                                verificationDetail = "Verified Android OS & battery",
                                targetPackage = "android",
                                preStateSummary = "Querying",
                                postStateSummary = "Android 14 • 85%",
                                userFacingMessage = "Your device information is ready.",
                                elapsedMs = 12L
                            )
                        }
                    }
                )
            }

            // Wait for coroutines in child jobs to finish
            var attempts = 0
            while (coordinator.activeTasks.value.any { !it.status.isTerminal } && attempts < 50) {
                kotlinx.coroutines.delay(10)
                attempts++
            }

            val finalTasks = coordinator.activeTasks.value
            assertEquals(2, finalTasks.size)

            val photoTask = finalTasks.first { it.capabilityType == com.example.nova.core.NovaTaskCapabilityType.PHOTOS }
            val deviceTask = finalTasks.first { it.capabilityType == com.example.nova.core.NovaTaskCapabilityType.DEVICE_INFO }

            assertEquals(com.example.nova.core.LiveTaskStatus.PERMISSION_REQUIRED, photoTask.status)
            assertEquals("I need photo access.", photoTask.statusMessage)
            assertEquals(
                com.example.nova.core.LiveTaskWorkspaceReference.PHOTO_WORKSPACE,
                photoTask.workspaceReference
            )

            assertEquals(com.example.nova.core.LiveTaskStatus.COMPLETED_VERIFIED, deviceTask.status)
            assertEquals("Your device information is ready.", deviceTask.statusMessage)
            assertEquals(
                com.example.nova.core.LiveTaskWorkspaceReference.DEVICE_INFO_WORKSPACE,
                deviceTask.workspaceReference
            )
        }

    @Test
    fun `M7 NovaMultiTaskCoordinator enforces low-RAM serialization, duplicate prevention, real cancellation, and retention`() =
        kotlinx.coroutines.runBlocking {
            val gate1 = kotlinx.coroutines.CompletableDeferred<Unit>()
            val task1Started = kotlinx.coroutines.CompletableDeferred<Unit>()
            var clockNow = 5000L

            val coordinator = com.example.nova.agent.NovaMultiTaskCoordinator(
                scope = this,
                clock = {
                    clockNow += 10L
                    clockNow
                },
                maxRetainedLowRam = 4
            )

            val photoCall = PlannedToolCall(
                toolName = "query_photos",
                arguments = mapOf("mode" to "mediastore"),
                reasoning = "Photos",
                expectedOutcomeDescription = "Photos",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.ANDROID_API_INTENT,
                spokenResponseDraft = ""
            )
            val deviceCall = PlannedToolCall(
                toolName = "query_device_info",
                arguments = emptyMap(),
                reasoning = "Device",
                expectedOutcomeDescription = "Device",
                riskLevel = RiskLevel.SAFE,
                channel = ExecutionChannel.ANDROID_API_INTENT,
                spokenResponseDraft = ""
            )

            // 1. Enqueue photoCall in low-RAM mode (holds the single low-RAM slot)
            val outcome1 = coordinator.enqueueTask(
                originalQuery = "photos",
                call = photoCall,
                providerLabel = "Test",
                lowRamMode = true,
                executor = { _, _ ->
                    task1Started.complete(Unit)
                    gate1.await()
                    VerifiedActionResult(
                        toolName = "query_photos",
                        outcome = VerificationOutcome.VERIFIED_SUCCESS,
                        verificationDetail = "3 photos",
                        targetPackage = "media",
                        preStateSummary = "Query",
                        postStateSummary = "3 photos",
                        userFacingMessage = "Your photos are ready.",
                        elapsedMs = 20L
                    )
                }
            )
            assertTrue(outcome1 is com.example.nova.agent.EnqueueTaskOutcome.Enqueued)
            task1Started.await()

            // 2. Duplicate prevention: enqueuing identical photoCall while active reuses existing task
            val duplicateOutcome = coordinator.enqueueTask(
                originalQuery = "photos again",
                call = photoCall,
                providerLabel = "Test",
                lowRamMode = true,
                executor = { _, _ ->
                    error("Should not execute duplicate")
                }
            )
            assertTrue(duplicateOutcome is com.example.nova.agent.EnqueueTaskOutcome.ReusedActiveTask)
            assertEquals(1, coordinator.activeTasks.value.size)

            // 3. Enqueue deviceCall in low-RAM mode: must stay PENDING while task 1 holds the single low-RAM slot
            val outcome2 = coordinator.enqueueTask(
                originalQuery = "device info",
                call = deviceCall,
                providerLabel = "Test",
                lowRamMode = true,
                executor = { _, _ ->
                    VerifiedActionResult(
                        toolName = "query_device_info",
                        outcome = VerificationOutcome.VERIFIED_SUCCESS,
                        verificationDetail = "Android",
                        targetPackage = "android",
                        preStateSummary = "Query",
                        postStateSummary = "Android",
                        userFacingMessage = "Your device information is ready.",
                        elapsedMs = 10L
                    )
                }
            )
            assertTrue(outcome2 is com.example.nova.agent.EnqueueTaskOutcome.Enqueued)
            val task2Id = (outcome2 as com.example.nova.agent.EnqueueTaskOutcome.Enqueued).task.taskId

            val midTasks = coordinator.activeTasks.value
            assertEquals(2, midTasks.size)
            assertEquals(com.example.nova.core.LiveTaskStatus.RUNNING, midTasks[0].status)
            assertEquals("Checking your photos now.", midTasks[0].statusMessage)
            assertEquals(com.example.nova.core.LiveTaskStatus.PENDING, midTasks[1].status)
            assertEquals("Device information check queued.", midTasks[1].statusMessage)

            // 4. Cancel task 2 while it is PENDING; task 1 must continue unaffected
            assertTrue(coordinator.cancelTask(task2Id, "User cancelled queued device info."))
            assertEquals(
                com.example.nova.core.LiveTaskStatus.CANCELLED,
                coordinator.activeTasks.value.first { it.taskId == task2Id }.status
            )
            assertEquals(
                com.example.nova.core.LiveTaskStatus.RUNNING,
                coordinator.activeTasks.value.first { it.taskId != task2Id }.status
            )

            // Release gate1 so task 1 finishes
            gate1.complete(Unit)
            var waitCount = 0
            while (coordinator.activeTasks.value.any { !it.status.isTerminal } && waitCount < 50) {
                kotlinx.coroutines.delay(10)
                waitCount++
            }
            assertEquals(
                com.example.nova.core.LiveTaskStatus.COMPLETED_VERIFIED,
                coordinator.activeTasks.value.first { it.taskId != task2Id }.status
            )

            // 5. Bounded retention in low-RAM mode (maxRetainedLowRam = 4)
            repeat(5) { idx ->
                val extraCall = deviceCall.copy(arguments = mapOf("req" to "$idx"))
                coordinator.enqueueTask(
                    originalQuery = "extra $idx",
                    call = extraCall,
                    providerLabel = "Test",
                    lowRamMode = true,
                    executor = { _, _ ->
                        VerifiedActionResult(
                            toolName = "query_device_info",
                            outcome = VerificationOutcome.VERIFIED_SUCCESS,
                            verificationDetail = "Done $idx",
                            targetPackage = "android",
                            preStateSummary = "Pre",
                            postStateSummary = "Post",
                            userFacingMessage = "Done",
                            elapsedMs = 5L
                        )
                    }
                )
                var w = 0
                while (coordinator.activeTasks.value.any { !it.status.isTerminal } && w < 50) {
                    kotlinx.coroutines.delay(10)
                    w++
                }
            }
            assertTrue(coordinator.activeTasks.value.size <= 4)
        }

    @Test
    fun `M7 MultiTaskWorkspaceCard formatting never fabricates progress percentages and connects workspaces`() {
        val runningTask = com.example.nova.core.LiveTaskItem(
            taskId = "t-run",
            title = "Access Device Photos",
            toolName = "query_photos",
            channel = com.example.nova.core.CrossAppInteractionChannel.ANDROID_SYSTEM_INTENT,
            status = com.example.nova.core.LiveTaskStatus.RUNNING,
            capabilityType = com.example.nova.core.NovaTaskCapabilityType.PHOTOS,
            statusMessage = "Checking your photos now.",
            measurableProgressFraction = null
        )
        val runningUi = com.example.nova.ui.formatMultiTaskItemSummary(runningTask)
        assertTrue(runningUi.showIndeterminateProgress)
        org.junit.Assert.assertNull(runningUi.measurableProgressPercentText)
        assertTrue(runningUi.isCancellable)
        assertEquals("RUNNING", runningUi.statusBadgeText)

        val verifiedDeviceTask = com.example.nova.core.LiveTaskItem(
            taskId = "t-dev",
            title = "Check Device Information",
            toolName = "query_device_info",
            channel = com.example.nova.core.CrossAppInteractionChannel.ANDROID_SYSTEM_INTENT,
            status = com.example.nova.core.LiveTaskStatus.COMPLETED_VERIFIED,
            capabilityType = com.example.nova.core.NovaTaskCapabilityType.DEVICE_INFO,
            statusMessage = "Your device information is ready.",
            verificationSummary = "Android 14 • 80% battery",
            workspaceReference = com.example.nova.core.LiveTaskWorkspaceReference.DEVICE_INFO_WORKSPACE
        )
        val sampleDeviceInfo = com.example.nova.core.NovaDeviceInfo(
            batteryPercentage = com.example.nova.core.DeviceFieldState.Available(80),
            chargingState = com.example.nova.core.DeviceFieldState.Available(
                com.example.nova.core.BatteryChargingState.CHARGING
            ),
            chargingSource = com.example.nova.core.DeviceFieldState.Available(
                com.example.nova.core.BatteryChargingSource.USB
            ),
            totalRamBytes = com.example.nova.core.DeviceFieldState.Available(8L * 1024 * 1024 * 1024),
            availableRamBytes = com.example.nova.core.DeviceFieldState.Available(4L * 1024 * 1024 * 1024),
            isLowRamDevice = com.example.nova.core.DeviceFieldState.Available(false),
            storageScopeDescription = com.example.nova.core.DeviceFieldState.Available("Internal Shared Storage"),
            totalStorageBytes = com.example.nova.core.DeviceFieldState.Available(128L * 1024 * 1024 * 1024),
            availableStorageBytes = com.example.nova.core.DeviceFieldState.Available(64L * 1024 * 1024 * 1024),
            androidVersionRelease = com.example.nova.core.DeviceFieldState.Available("14"),
            sdkInt = com.example.nova.core.DeviceFieldState.Available(34),
            safeDeviceModel = com.example.nova.core.DeviceFieldState.Available("Google Pixel 8"),
            networkConnectivity = com.example.nova.core.DeviceFieldState.Available(
                com.example.nova.core.NetworkConnectivityState.CONNECTED
            ),
            networkTransport = com.example.nova.core.DeviceFieldState.Available(
                com.example.nova.core.NetworkTransportType.WIFI
            )
        )

        val wsState = com.example.nova.ui.NovaUiStateMapper.deriveWorkspaceCardState(
            sessionStage = com.example.nova.core.AssistantSessionStage.EXECUTING_AND_VERIFYING,
            pendingConfirmation = null,
            lastVerifiedResult = null,
            voiceState = com.example.nova.voice.VoiceEngineState.Idle,
            statusBanner = "Running 1 task(s)...",
            latestDeviceInfo = sampleDeviceInfo,
            activeTasks = listOf(runningTask, verifiedDeviceTask)
        )
        assertTrue(wsState is com.example.nova.core.WorkspaceCardState.MultiTaskWorkspace)
        val connectedDeviceWs = com.example.nova.ui.NovaUiStateMapper.resolveTaskConnectedWorkspace(
            task = verifiedDeviceTask,
            latestDeviceInfo = sampleDeviceInfo
        )
        assertTrue(connectedDeviceWs is com.example.nova.core.WorkspaceCardState.DeviceInfoWorkspace)
    }

    @Test
    fun `M8 AI Provider settings report API key required when missing, configured or runtime error when set, and never leak plaintext secrets`() {
        val unconfiguredHealth = SystemHealthSnapshot(
            sdkInt = 35,
            androidRelease = "15",
            deviceModel = "Pixel 8",
            totalRamMb = 4096,
            availableRamMb = 2048,
            isSystemLowRamDevice = false,
            effectiveLowRamMode = false,
            batteryPercent = 85,
            isCharging = true,
            isNetworkOnline = true,
            hasFlashlightHardware = true,
            isTorchCurrentlyOn = false,
            recordAudioGranted = false,
            postNotificationsGranted = false,
            speechRecognitionAvailable = true,
            accessibilityServiceEnabled = false,
            accessibilityServiceConnected = false,
            notificationListenerEnabled = false,
            geminiKeyConfigured = false,
            groqKeyConfigured = false,
            openAiKeyConfigured = false
        )

        val settings = NovaRuntimeSettings(
            primaryProvider = AiProviderType.GEMINI,
            geminiModel = "gemini-3.5-flash",
            groqModel = "llama-3.3-70b-versatile",
            openAiModel = "gpt-4o-mini"
        )

        val unconfiguredAiSection = com.example.nova.ui.NovaSettingsStateResolver.buildAiProviderSection(
            settings = settings,
            health = unconfiguredHealth
        )

        val geminiUnconfigured = unconfiguredAiSection.providers.first { it.providerType == AiProviderType.GEMINI }
        assertEquals(
            com.example.nova.core.AiProviderConfigurationStatus.CONFIGURATION_REQUIRED,
            geminiUnconfigured.runtimeStatus
        )
        assertEquals("API key required", geminiUnconfigured.safeSecretStatusLabel)
        assertFalse(geminiUnconfigured.safeSecretStatusLabel.contains("Connected", ignoreCase = true))
        assertEquals("https://ai.google.dev/gemini-api/docs", geminiUnconfigured.officialDocsUrl)

        val offlineItem = unconfiguredAiSection.providers.first { it.providerType == AiProviderType.OFFLINE_DETERMINISTIC }
        assertEquals(
            com.example.nova.core.AiProviderConfigurationStatus.AVAILABLE,
            offlineItem.runtimeStatus
        )

        // Configured Gemini + Runtime Error on Groq with embedded fake secret token
        val configuredHealth = unconfiguredHealth.copy(
            geminiKeyConfigured = true,
            groqKeyConfigured = true
        )
        val leakedError = "Groq HTTP 401 failed for key gsk_1234567890ABCDEFGHIJKLMNOPQRSTUV"
        val configuredAiSection = com.example.nova.ui.NovaSettingsStateResolver.buildAiProviderSection(
            settings = settings,
            health = configuredHealth,
            providerRuntimeErrors = mapOf(AiProviderType.GROQ to leakedError)
        )

        val geminiConfigured = configuredAiSection.providers.first { it.providerType == AiProviderType.GEMINI }
        assertEquals(
            com.example.nova.core.AiProviderConfigurationStatus.CONFIGURED,
            geminiConfigured.runtimeStatus
        )
        assertEquals("Configured", geminiConfigured.safeSecretStatusLabel)

        val groqErrorItem = configuredAiSection.providers.first { it.providerType == AiProviderType.GROQ }
        assertEquals(
            com.example.nova.core.AiProviderConfigurationStatus.RUNTIME_ERROR,
            groqErrorItem.runtimeStatus
        )
        assertFalse(groqErrorItem.lastRuntimeErrorMessage.contains("gsk_1234567890ABCDEFGHIJKLMNOPQRSTUV"))
        assertTrue(groqErrorItem.lastRuntimeErrorMessage.contains("[REDACTED_API_KEY]"))
    }

    @Test
    fun `M8 Voice Provider and Accessibility sections distinguish real capabilities and never claim always listening or ready when unbound`() {
        val sampleHealth = SystemHealthSnapshot(
            sdkInt = 35,
            androidRelease = "15",
            deviceModel = "Pixel 8",
            totalRamMb = 4096,
            availableRamMb = 2048,
            isSystemLowRamDevice = false,
            effectiveLowRamMode = false,
            batteryPercent = 85,
            isCharging = false,
            isNetworkOnline = true,
            hasFlashlightHardware = true,
            isTorchCurrentlyOn = false,
            recordAudioGranted = false,
            postNotificationsGranted = false,
            speechRecognitionAvailable = false,
            accessibilityServiceEnabled = true,
            accessibilityServiceConnected = false,
            notificationListenerEnabled = false,
            geminiKeyConfigured = false,
            groqKeyConfigured = false,
            openAiKeyConfigured = false
        )
        val settings = NovaRuntimeSettings(spokenResponsesEnabled = true)

        val voiceSection = com.example.nova.ui.NovaSettingsStateResolver.buildVoiceProviderSection(
            settings = settings,
            health = sampleHealth,
            voiceState = VoiceEngineState.Idle,
            isTtsReady = true,
            isTtsEngineInstalled = true,
            ttsErrorMessage = null,
            hasMicrophoneHardware = true
        )
        assertEquals(
            com.example.nova.core.VoiceCapabilityStatus.UNAVAILABLE,
            voiceSection.speechRecognitionStatus
        )
        assertEquals(
            com.example.nova.core.VoiceCapabilityStatus.AVAILABLE,
            voiceSection.textToSpeechStatus
        )
        assertEquals(
            com.example.nova.core.VoiceCapabilityStatus.CONFIGURATION_REQUIRED,
            voiceSection.microphoneStatus
        )
        assertEquals(
            com.example.nova.core.VoiceCapabilityStatus.UNAVAILABLE,
            voiceSection.remoteVoiceProviderStatus
        )
        assertFalse(voiceSection.isAlwaysListeningActive)

        // Accessibility: Enabled in Settings but NOT connected by OS -> ENABLED_IN_SETTINGS_NOT_CONNECTED & isReadyForAutomation = false
        val a11yNotConnected = com.example.nova.ui.NovaSettingsStateResolver.buildAccessibilitySection(
            settings = settings,
            health = sampleHealth,
            overlayAttachmentState = com.example.nova.core.OverlayAttachmentState.SERVICE_NOT_BOUND,
            screenSnapshot = null,
            accessibilityWorkspaceState = null
        )
        assertEquals(
            com.example.nova.core.AccessibilityConnectionDistinction.ENABLED_IN_SETTINGS_NOT_CONNECTED,
            a11yNotConnected.connectionDistinction
        )
        assertTrue(a11yNotConnected.serviceEnabledInSettings)
        assertFalse(a11yNotConnected.serviceActuallyConnected)
        assertFalse(a11yNotConnected.isReadyForAutomation)

        // Accessibility: Connected by OS -> ACTUALLY_CONNECTED & isReadyForAutomation = true
        val a11yConnected = com.example.nova.ui.NovaSettingsStateResolver.buildAccessibilitySection(
            settings = settings,
            health = sampleHealth.copy(accessibilityServiceConnected = true),
            overlayAttachmentState = com.example.nova.core.OverlayAttachmentState.ATTACHED,
            screenSnapshot = null,
            accessibilityWorkspaceState = null
        )
        assertEquals(
            com.example.nova.core.AccessibilityConnectionDistinction.ACTUALLY_CONNECTED,
            a11yConnected.connectionDistinction
        )
        assertTrue(a11yConnected.isReadyForAutomation)
    }

    @Test
    fun `M8 PermissionAuditor and Privacy Memory Automation Advanced Diagnostics sections expose truthful non-sensitive state`() {
        val auditItems = com.example.nova.security.PermissionAuditor.auditNovaPermissions(
            sdkInt = 34,
            hasMicrophoneHardware = true,
            recordAudioGranted = false,
            recordAudioPreviouslyRequested = false,
            postNotificationsGranted = false,
            postNotificationsPreviouslyRequested = true,
            notificationsEnabledAtOsLevel = false,
            readMediaImagesGranted = false,
            readMediaVisualUserSelectedGranted = true,
            readMediaPreviouslyRequested = true,
            accessibilityServiceEnabledInSettings = true,
            accessibilityServiceConnected = false,
            notificationListenerEnabled = true
        )

        val micAudit = auditItems.first { it.permissionId == "microphone" }
        assertEquals(com.example.nova.core.NovaPermissionStatus.NOT_REQUESTED, micAudit.status)

        val notifAudit = auditItems.first { it.permissionId == "notifications" }
        assertEquals(com.example.nova.core.NovaPermissionStatus.DENIED, notifAudit.status)

        val photoAudit = auditItems.first { it.permissionId == "photos_media" }
        assertEquals(com.example.nova.core.NovaPermissionStatus.RESTRICTED, photoAudit.status)

        val legacyStorageAudit = auditItems.first { it.permissionId == "legacy_external_storage" }
        assertEquals(com.example.nova.core.NovaPermissionStatus.NOT_APPLICABLE, legacyStorageAudit.status)

        val a11yAudit = auditItems.first { it.permissionId == "accessibility_service" }
        assertEquals(com.example.nova.core.NovaPermissionStatus.RESTRICTED, a11yAudit.status)

        val notifListenerAudit = auditItems.first { it.permissionId == "notification_listener" }
        assertEquals(com.example.nova.core.NovaPermissionStatus.GRANTED, notifListenerAudit.status)

        // Privacy locality distinction: Offline Deterministic vs Cloud Provider
        val localPrivacy = com.example.nova.ui.NovaSettingsStateResolver.buildPrivacySection(
            NovaRuntimeSettings(primaryProvider = AiProviderType.OFFLINE_DETERMINISTIC)
        )
        assertEquals(
            com.example.nova.core.NovaProcessingLocalityMode.LOCAL_DEVICE_PROCESSING,
            localPrivacy.processingLocalityMode
        )
        assertFalse(localPrivacy.mayProviderRequestsLeaveDevice)
        assertFalse(localPrivacy.isAccessibilityContentRetainedOnDisk)
        assertFalse(localPrivacy.arePhotosUploadedToCloud)
        assertFalse(localPrivacy.isAnalyticsOrTelemetryEnabled)
        assertTrue(localPrivacy.isSensitiveRedactionEnforced)

        val remotePrivacy = com.example.nova.ui.NovaSettingsStateResolver.buildPrivacySection(
            NovaRuntimeSettings(primaryProvider = AiProviderType.GEMINI)
        )
        assertEquals(
            com.example.nova.core.NovaProcessingLocalityMode.REMOTE_PROVIDER_PROCESSING,
            remotePrivacy.processingLocalityMode
        )
        assertTrue(remotePrivacy.mayProviderRequestsLeaveDevice)

        // Diagnostics never invents 0% battery or 0 MB RAM when unavailable and scrubs secrets
        val unavailableTelemetryHealth = SystemHealthSnapshot(
            sdkInt = 35,
            androidRelease = "15",
            deviceModel = "Pixel",
            totalRamMb = 0L,
            availableRamMb = 0L,
            isSystemLowRamDevice = true,
            effectiveLowRamMode = true,
            batteryPercent = -1,
            isCharging = false,
            isNetworkOnline = false,
            hasFlashlightHardware = false,
            isTorchCurrentlyOn = false,
            recordAudioGranted = false,
            postNotificationsGranted = false,
            speechRecognitionAvailable = true,
            accessibilityServiceEnabled = false,
            accessibilityServiceConnected = false,
            notificationListenerEnabled = false,
            geminiKeyConfigured = false,
            groqKeyConfigured = false,
            openAiKeyConfigured = false
        )

        val fullState = com.example.nova.ui.NovaSettingsStateResolver.resolveSettingsState(
            selectedSection = com.example.nova.core.NovaSettingsSection.DIAGNOSTICS,
            settings = NovaRuntimeSettings(),
            health = unavailableTelemetryHealth,
            voiceState = VoiceEngineState.Idle,
            isTtsReady = false,
            isTtsEngineInstalled = false,
            ttsErrorMessage = "Failed with token sk-1234567890ABCDEFGHIJKLMNOPQRSTUV",
            hasMicrophoneHardware = false,
            overlayAttachmentState = com.example.nova.core.OverlayAttachmentState.SERVICE_NOT_BOUND,
            screenSnapshot = null,
            accessibilityWorkspaceState = null,
            photoWorkspaceState = null,
            latestDeviceInfo = null,
            activeTasks = emptyList(),
            permissionAuditItems = auditItems,
            savedMemoryFactCount = 3,
            conversationTurnCount = 5
        )

        assertEquals(10, com.example.nova.core.NovaSettingsSection.entries.size)
        assertEquals("Unavailable", fullState.diagnosticsSection.ramSummaryText)
        assertEquals("Unavailable", fullState.diagnosticsSection.batterySummaryText)
        assertFalse(fullState.memorySection.areRawMemoriesExposedByDefault)
        assertEquals(3, fullState.memorySection.savedMemoryFactCount)
        assertEquals(1, fullState.advancedSection.maxConcurrentTasksLimit)
        assertTrue(
            fullState.diagnosticsSection.recentNonSensitiveErrors.none {
                it.contains("sk-1234567890ABCDEFGHIJKLMNOPQRSTUV")
            }
        )
    }

    @Test
    fun `M10 LiveVoiceSessionState maps deterministically to NovaUiOrbState and handles barge-in and conversational flow`() {
        val voiceIdle = VoiceEngineState.Idle
        val stageIdle = AssistantSessionStage.IDLE

        // 1. Live listening -> LISTENING
        assertEquals(
            NovaUiOrbState.LISTENING,
            NovaUiStateMapper.deriveOrbState(
                voiceState = voiceIdle,
                sessionStage = stageIdle,
                liveVoiceSessionState = com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_LISTENING
            )
        )

        // 2. Waiting for speech in active session -> LISTENING
        assertEquals(
            NovaUiOrbState.LISTENING,
            NovaUiStateMapper.deriveOrbState(
                voiceState = voiceIdle,
                sessionStage = stageIdle,
                liveVoiceSessionState = com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_WAITING_FOR_USER
            )
        )

        // 3. AI / Orchestrator processing speech -> THINKING
        assertEquals(
            NovaUiOrbState.THINKING,
            NovaUiStateMapper.deriveOrbState(
                voiceState = voiceIdle,
                sessionStage = stageIdle,
                liveVoiceSessionState = com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_PROCESSING
            )
        )

        // 4. Assistant speaking audio response -> SPEAKING
        assertEquals(
            NovaUiOrbState.SPEAKING,
            NovaUiStateMapper.deriveOrbState(
                voiceState = voiceIdle,
                sessionStage = stageIdle,
                liveVoiceSessionState = com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_SPEAKING
            )
        )

        // 5. Error in live session -> ERROR
        assertEquals(
            NovaUiOrbState.ERROR,
            NovaUiStateMapper.deriveOrbState(
                voiceState = voiceIdle,
                sessionStage = stageIdle,
                liveVoiceSessionState = com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_ERROR
            )
        )

        // 6. Unavailable hardware -> UNAVAILABLE
        assertEquals(
            NovaUiOrbState.UNAVAILABLE,
            NovaUiStateMapper.deriveOrbState(
                voiceState = voiceIdle,
                sessionStage = stageIdle,
                liveVoiceSessionState = com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_UNAVAILABLE
            )
        )
    }

    @Test
    fun `M10 ProviderSecretVault masks API keys safely showing only suffix and preserves backward compatible settings aliases`() {
        // Safe masking: never exposes plaintext or middle characters
        assertEquals("••••••••A7F2", com.example.nova.security.ProviderSecretVault.maskApiKey("AIzaSyTestSecret12345A7F2"))
        assertEquals("••••••••9C41", com.example.nova.security.ProviderSecretVault.maskApiKey("sk-proj-elevenlabs9C41"))
        assertEquals("", com.example.nova.security.ProviderSecretVault.maskApiKey(""))
        assertEquals("", com.example.nova.security.ProviderSecretVault.maskApiKey(null))
        assertEquals("••••••••", com.example.nova.security.ProviderSecretVault.maskApiKey("abc"))

        // Backward compatibility aliases for M8
        assertEquals(
            com.example.nova.core.NovaSettingsSection.AI_API,
            com.example.nova.core.NovaSettingsSection.AI_PROVIDER
        )
        assertEquals(
            com.example.nova.core.NovaSettingsSection.VOICE_WAKE_WORD,
            com.example.nova.core.NovaSettingsSection.VOICE_PROVIDER
        )

        // ElevenLabs Voice State resolution
        val unconfiguredVoiceApi = com.example.nova.core.ElevenLabsVoiceState(
            isConfigured = false,
            selectedVoiceId = "21m00Tcm4TlvDq8ikWAM",
            selectedVoiceName = "Rachel",
            maskedApiKey = "",
            status = com.example.nova.core.VoiceCapabilityStatus.CONFIGURATION_REQUIRED,
            statusDetail = "ElevenLabs API key not configured. Using Android TextToSpeech fallback.",
            isFallbackActive = true
        )
        assertFalse(unconfiguredVoiceApi.isConfigured)
        assertTrue(unconfiguredVoiceApi.isFallbackActive)
        assertEquals("https://elevenlabs.io/docs", unconfiguredVoiceApi.officialDocsUrl)
        assertEquals("Android TextToSpeech", unconfiguredVoiceApi.fallbackEngineName)
        assertEquals(5, unconfiguredVoiceApi.availableVoices.size)

        val configuredVoiceApi = unconfiguredVoiceApi.copy(
            isConfigured = true,
            maskedApiKey = "••••••••9C41",
            status = com.example.nova.core.VoiceCapabilityStatus.AVAILABLE,
            statusDetail = "ElevenLabs Voice API configured and ready.",
            isFallbackActive = false
        )
        assertTrue(configuredVoiceApi.isConfigured)
        assertFalse(configuredVoiceApi.isFallbackActive)
        assertEquals("••••••••9C41", configuredVoiceApi.maskedApiKey)
    }

    @Test
    fun `M10 CallingController sanitizes numbers and protects emergency dispatch safety`() {
        val cleanNumber = com.example.nova.tools.CallingController.sanitizePhoneNumber("+1 (555) 019-2834")
        assertEquals("+15550192834", cleanNumber)

        assertTrue(com.example.nova.tools.CallingController.isEmergencyNumber("911"))
        assertTrue(com.example.nova.tools.CallingController.isEmergencyNumber("112"))
        assertTrue(com.example.nova.tools.CallingController.isEmergencyNumber("999"))
        assertFalse(com.example.nova.tools.CallingController.isEmergencyNumber("+15550192834"))
    }

    @Test
    fun `M11 Avatar state visuals resolve deterministically across all runtime orb states`() {
        // IDLE
        val idleVisuals = com.example.nova.ui.resolveAvatarStateVisuals(
            orbState = com.example.nova.core.NovaUiOrbState.IDLE,
            realNormalizedAudio = null,
            smoothedAudioLevel = 0f
        )
        assertEquals(1.0f, idleVisuals.scale, 0.001f)
        assertEquals(0.35f, idleVisuals.auraAlpha, 0.001f)
        assertTrue(idleVisuals.stateDescription.contains("ready", ignoreCase = true))

        // LISTENING with real audio
        val listeningVisuals = com.example.nova.ui.resolveAvatarStateVisuals(
            orbState = com.example.nova.core.NovaUiOrbState.LISTENING,
            realNormalizedAudio = 0.8f,
            smoothedAudioLevel = 0.8f
        )
        assertEquals(1.06f, listeningVisuals.scale, 0.01f)
        assertEquals(0.75f, listeningVisuals.auraAlpha, 0.001f)

        // THINKING
        val thinkingVisuals = com.example.nova.ui.resolveAvatarStateVisuals(
            orbState = com.example.nova.core.NovaUiOrbState.THINKING,
            realNormalizedAudio = null,
            smoothedAudioLevel = 0f
        )
        assertEquals(0.98f, thinkingVisuals.scale, 0.001f)

        // SPEAKING
        val speakingVisuals = com.example.nova.ui.resolveAvatarStateVisuals(
            orbState = com.example.nova.core.NovaUiOrbState.SPEAKING,
            realNormalizedAudio = null,
            smoothedAudioLevel = 0f
        )
        assertEquals(1.04f, speakingVisuals.scale, 0.001f)
        assertEquals(0.85f, speakingVisuals.auraAlpha, 0.001f)

        // ERROR
        val errorVisuals = com.example.nova.ui.resolveAvatarStateVisuals(
            orbState = com.example.nova.core.NovaUiOrbState.ERROR,
            realNormalizedAudio = null,
            smoothedAudioLevel = 0f
        )
        assertEquals(0.96f, errorVisuals.scale, 0.001f)

        // UNAVAILABLE
        val unavailableVisuals = com.example.nova.ui.resolveAvatarStateVisuals(
            orbState = com.example.nova.core.NovaUiOrbState.UNAVAILABLE,
            realNormalizedAudio = null,
            smoothedAudioLevel = 0f
        )
        assertEquals(0.92f, unavailableVisuals.scale, 0.001f)
    }

    @Test
    fun `M11 LatencyTracker instruments the pipeline with monotonic intervals`() {
        com.example.nova.core.LatencyTracker.reset()
        val initial = com.example.nova.core.LatencyTracker.getLatestSnapshot()
        assertEquals(0L, initial.speechRecognitionStartMonoMs)
        assertEquals(-1L, initial.totalTurnaroundMs)

        com.example.nova.core.LatencyTracker.onSpeechRecognitionStart()
        com.example.nova.core.LatencyTracker.onTranscriptAvailable()
        com.example.nova.core.LatencyTracker.onRoutingStart()
        com.example.nova.core.LatencyTracker.onRoutingEnd("LOCAL_DETERMINISTIC")
        com.example.nova.core.LatencyTracker.onAiRequestStart("Gemini")
        com.example.nova.core.LatencyTracker.onFirstAiToken()
        com.example.nova.core.LatencyTracker.onCompleteAiResponse()
        com.example.nova.core.LatencyTracker.onTtsStart()
        com.example.nova.core.LatencyTracker.onFirstAudiblePlayback()
        com.example.nova.core.LatencyTracker.onTotalResponseComplete()

        val populated = com.example.nova.core.LatencyTracker.getLatestSnapshot()
        assertTrue(populated.speechRecognitionStartMonoMs > 0L)
        assertTrue(populated.transcriptAvailableMonoMs >= populated.speechRecognitionStartMonoMs)
        assertTrue(populated.commandRoutingStartMonoMs > 0L)
        assertTrue(populated.commandRoutingEndMonoMs >= populated.commandRoutingStartMonoMs)
        assertEquals("LOCAL_DETERMINISTIC", populated.executionRoute)
        assertEquals("Gemini", populated.providerUsed)
        assertTrue(populated.totalTurnaroundMs >= 0L)
    }

    @Test
    fun `M11 LocalDeterministicParser resolves fast conversational queries and greetings locally`() {
        val helloCall = com.example.nova.ai.LocalDeterministicParser.tryParse("hello boss")
        assertNotNull(helloCall)
        assertEquals("none", helloCall!!.toolName)
        assertEquals(com.example.nova.core.ExecutionChannel.DIRECT_RESPONSE, helloCall.channel)
        assertTrue(helloCall.spokenResponseDraft.contains("Boss", ignoreCase = true))

        val aryaIdentity = com.example.nova.ai.LocalDeterministicParser.tryParse("who are you")
        assertNotNull(aryaIdentity)
        assertEquals("none", aryaIdentity!!.toolName)
        assertTrue(aryaIdentity.spokenResponseDraft.contains("Arya", ignoreCase = true))

        val statusCall = com.example.nova.ai.LocalDeterministicParser.tryParse("how are you")
        assertNotNull(statusCall)
        assertEquals("none", statusCall!!.toolName)
        assertTrue(statusCall.spokenResponseDraft.contains("Boss", ignoreCase = true))

        val thanksCall = com.example.nova.ai.LocalDeterministicParser.tryParse("thank you arya")
        assertNotNull(thanksCall)
        assertEquals("none", thanksCall!!.toolName)
        assertTrue(thanksCall.spokenResponseDraft.contains("Boss", ignoreCase = true))
    }

    @Test
    fun `M11 RemoteDeviceAccessContract enforces truthful authorization states`() {
        val defaultManager = com.example.nova.accessibility.DefaultRemoteDeviceAccessManager
        assertTrue(defaultManager.pairedDevices.isEmpty())

        val testDescriptor = com.example.nova.accessibility.RemoteDeviceDescriptor(
            deviceId = "laptop-1234",
            deviceName = "User MacBook",
            deviceType = "LAPTOP",
            connectionState = com.example.nova.accessibility.RemoteDeviceConnectionState.DISCONNECTED,
            isPaired = false,
            lastVerifiedAtMs = null,
            authorizedCapabilities = emptyList()
        )
        val pairingResult = defaultManager.initiatePairing(testDescriptor)
        assertEquals(com.example.nova.accessibility.RemoteDeviceConnectionState.UNAVAILABLE, pairingResult)
        assertEquals(
            com.example.nova.accessibility.RemoteDeviceConnectionState.DISCONNECTED,
            defaultManager.verifyConnection("laptop-1234")
        )
    }

    @Test
    fun `M11 GitHub Actions workflow is present and configured for debug APK build`() {
        val candidates = listOf(
            java.io.File(".github/workflows/build-apk.yml"),
            java.io.File("../.github/workflows/build-apk.yml"),
            java.io.File("/.github/workflows/build-apk.yml")
        )
        val workflowFile = candidates.firstOrNull { it.exists() }
        assertNotNull("GitHub Actions build-apk.yml workflow must exist", workflowFile)
        val content = workflowFile!!.readText()
        assertTrue(content.contains("assembleDebug"))
        assertTrue(content.contains("testDebugUnitTest"))
        assertTrue(content.contains("actions/checkout@v4"))
        assertTrue(content.contains("actions/setup-java@v4"))
        assertTrue(content.contains("actions/upload-artifact@v4"))
    }
}

