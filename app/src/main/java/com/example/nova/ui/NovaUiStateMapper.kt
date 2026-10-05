package com.example.nova.ui

import com.example.nova.core.AiProviderType
import com.example.nova.core.AssistantSessionStage
import com.example.nova.core.CrossAppInteractionChannel
import com.example.nova.core.NovaUiOrbState
import com.example.nova.core.PendingConfirmationRequest
import com.example.nova.core.SystemHealthSnapshot
import com.example.nova.core.VerificationOutcome
import com.example.nova.core.VerifiedActionResult
import com.example.nova.core.WorkspaceCardState
import com.example.nova.memory.NovaRuntimeSettings
import com.example.nova.voice.VoiceEngineState

/**
 * M1 — Pure deterministic state mapper connecting real backend/runtime states
 * to the M0 UI contracts (NovaUiOrbState and WorkspaceCardState).
 *
 * Rules enforced:
 * - Zero fabricated or simulated transitions.
 * - Missing microphone capability or missing/unavailable AI provider maps to UNAVAILABLE.
 * - Real runtime/provider/verification failures map to ERROR.
 * - Permission-required states remain distinguishable via VerificationOutcome.PERMISSION_REQUIRED.
 * - VerifiedActionSummary is emitted ONLY when outcome == VerificationOutcome.VERIFIED_SUCCESS.
 */
object NovaUiStateMapper {

    /**
     * Checks whether the device has both the platform SpeechRecognizer service
     * and RECORD_AUDIO runtime permission for voice input.
     */
    fun isMicrophoneAndSpeechCapabilityReady(health: SystemHealthSnapshot): Boolean {
        return health.speechRecognitionAvailable && health.recordAudioGranted
    }

    /**
     * Checks whether the currently configured primary AI provider (or fallback chain)
     * is actually available and configured with a real API key and network connectivity.
     */
    fun isAiProviderCapabilityAvailable(
        settings: NovaRuntimeSettings,
        health: SystemHealthSnapshot
    ): Boolean {
        if (settings.primaryProvider == AiProviderType.OFFLINE_DETERMINISTIC) {
            return true
        }
        if (!health.isNetworkOnline) {
            return false
        }
        val primaryKeyReady = when (settings.primaryProvider) {
            AiProviderType.GEMINI -> health.geminiKeyConfigured
            AiProviderType.GROQ -> health.groqKeyConfigured
            AiProviderType.OPENAI_COMPATIBLE -> health.openAiKeyConfigured
            AiProviderType.OFFLINE_DETERMINISTIC -> true
        }
        if (primaryKeyReady) return true

        if (settings.enableCloudFallback) {
            return health.geminiKeyConfigured ||
                health.groqKeyConfigured ||
                health.openAiKeyConfigured
        }
        return false
    }

    /**
     * Maps real runtime states from VoiceSessionController and NovaAgentOrchestrator
     * into the authoritative NovaUiOrbState.
     */
    fun deriveOrbState(
        voiceState: VoiceEngineState,
        sessionStage: AssistantSessionStage,
        lastVerifiedResult: VerifiedActionResult? = null,
        statusBanner: String = "",
        activeTasks: List<com.example.nova.core.LiveTaskItem> = emptyList(),
        liveVoiceSessionState: com.example.nova.core.LiveVoiceSessionState = com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_IDLE
    ): NovaUiOrbState {
        // 0. Live Voice Session state takes priority when active
        when (liveVoiceSessionState) {
            com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_LISTENING,
            com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_WAITING_FOR_USER,
            com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_STARTING -> return NovaUiOrbState.LISTENING
            com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_PROCESSING -> return NovaUiOrbState.THINKING
            com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_SPEAKING -> return NovaUiOrbState.SPEAKING
            com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_ERROR -> return NovaUiOrbState.ERROR
            com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_UNAVAILABLE -> return NovaUiOrbState.UNAVAILABLE
            else -> Unit
        }

        // 1. Active voice listening takes immediate visual precedence
        if (voiceState is VoiceEngineState.Listening) {
            return NovaUiOrbState.LISTENING
        }

        // 2. Active sanitization & AI/parser planning
        if (sessionStage == AssistantSessionStage.SANITIZING_AND_PLANNING) {
            return NovaUiOrbState.THINKING
        }

        // 3. Active tool or Accessibility execution & verification (single or multi-task)
        if (sessionStage == AssistantSessionStage.EXECUTING_AND_VERIFYING ||
            activeTasks.any {
                it.status == com.example.nova.core.LiveTaskStatus.RUNNING ||
                    it.status == com.example.nova.core.LiveTaskStatus.PENDING
            }
        ) {
            return NovaUiOrbState.EXECUTING
        }

        // 4. Active TTS speech output
        if (voiceState is VoiceEngineState.Speaking ||
            sessionStage == AssistantSessionStage.SPEAKING_RESPONSE
        ) {
            return NovaUiOrbState.SPEAKING
        }

        // 5. Missing microphone permission or unavailable SpeechRecognizer service
        if (voiceState is VoiceEngineState.PermissionRequired ||
            voiceState is VoiceEngineState.Unavailable
        ) {
            return NovaUiOrbState.UNAVAILABLE
        }

        // 6. Voice recognition runtime error
        if (voiceState is VoiceEngineState.Error) {
            return NovaUiOrbState.ERROR
        }

        // 7. Missing permission, disabled Accessibility service, unconfigured AI provider, or unsupported hardware
        if (sessionStage == AssistantSessionStage.PERMISSION_OR_CONFIG_REQUIRED) {
            return NovaUiOrbState.UNAVAILABLE
        }

        // 8. Real runtime or provider failure
        if (sessionStage == AssistantSessionStage.ERROR) {
            return NovaUiOrbState.ERROR
        }

        // 9. Inspect latest verified action outcome if present
        if (lastVerifiedResult != null) {
            return when (lastVerifiedResult.outcome) {
                VerificationOutcome.VERIFIED_SUCCESS,
                VerificationOutcome.USER_CANCELLED,
                VerificationOutcome.AWAITING_CONFIRMATION -> NovaUiOrbState.IDLE

                VerificationOutcome.PERMISSION_REQUIRED,
                VerificationOutcome.SERVICE_DISABLED,
                VerificationOutcome.CONFIGURATION_REQUIRED,
                VerificationOutcome.UNSUPPORTED -> NovaUiOrbState.UNAVAILABLE

                VerificationOutcome.VERIFICATION_FAILED -> NovaUiOrbState.ERROR
            }
        }

        // 10. Status banner fallback check for explicit provider/config unavailability codes
        val upperBanner = statusBanner.uppercase()
        if (upperBanner == "CONFIGURATION_REQUIRED" ||
            upperBanner == "NETWORK_OFFLINE" ||
            upperBanner == "OFFLINE_MODE_ACTIVE" ||
            upperBanner == "PERMISSION_REQUIRED" ||
            upperBanner == "SERVICE_DISABLED"
        ) {
            return NovaUiOrbState.UNAVAILABLE
        }
        if (upperBanner == "PROVIDER_RUNTIME_ERROR") {
            return NovaUiOrbState.ERROR
        }

        return NovaUiOrbState.IDLE
    }

    /**
     * Maps real runtime task, confirmation, verification, and error states into WorkspaceCardState.
     * Never emits VerifiedActionSummary or DeviceInfoWorkspace unless VerificationOutcome.VERIFIED_SUCCESS was achieved.
     */
    fun deriveWorkspaceCardState(
        sessionStage: AssistantSessionStage,
        pendingConfirmation: PendingConfirmationRequest?,
        lastVerifiedResult: VerifiedActionResult?,
        voiceState: VoiceEngineState,
        statusBanner: String,
        latestDiagnosticMessage: String = "",
        latestDeviceInfo: com.example.nova.core.NovaDeviceInfo? = null,
        latestPhotoWorkspaceState: com.example.nova.core.PhotoAccessWorkspaceState? = null,
        latestAccessibilityWorkspaceState: com.example.nova.core.AccessibilityWorkspaceState? = null,
        activeTasks: List<com.example.nova.core.LiveTaskItem> = emptyList()
    ): WorkspaceCardState {
        // 1. Sensitive action paused at confirmation gate
        if (pendingConfirmation != null) {
            return WorkspaceCardState.ConfirmationRequired(request = pendingConfirmation)
        }

        // 2. Multi-task orchestration workspace when 2+ real tasks are active or tracked in session
        if (activeTasks.size > 1) {
            return WorkspaceCardState.MultiTaskWorkspace(
                tasks = activeTasks,
                deviceInfo = latestDeviceInfo,
                photoState = latestPhotoWorkspaceState,
                accessibilityWorkspaceState = latestAccessibilityWorkspaceState
            )
        }

        // 3. Active execution & verification in progress
        if (sessionStage == AssistantSessionStage.EXECUTING_AND_VERIFYING) {
            if (latestAccessibilityWorkspaceState != null &&
                latestAccessibilityWorkspaceState.status == com.example.nova.core.AccessibilityWorkspaceStatus.ACTION_IN_PROGRESS
            ) {
                return WorkspaceCardState.AccessibilityWorkspace(
                    workspaceState = latestAccessibilityWorkspaceState
                )
            }

            val channel = if (statusBanner.contains("screen", ignoreCase = true) ||
                statusBanner.contains("click", ignoreCase = true) ||
                statusBanner.contains("focus", ignoreCase = true) ||
                statusBanner.contains("select", ignoreCase = true) ||
                statusBanner.contains("scroll", ignoreCase = true) ||
                statusBanner.contains("navigate", ignoreCase = true)
            ) {
                CrossAppInteractionChannel.ACCESSIBILITY_PRIMARY
            } else {
                CrossAppInteractionChannel.ANDROID_SYSTEM_INTENT
            }
            return WorkspaceCardState.ActiveActionExecution(
                toolName = statusBanner,
                statusDescription = statusBanner,
                channel = channel
            )
        }

        // 4. Voice permission or capability unavailability
        if (voiceState is VoiceEngineState.PermissionRequired) {
            return WorkspaceCardState.ErrorOrUnavailableWorkspace(
                title = "Microphone Permission Required",
                detail = "Grant RECORD_AUDIO permission to use voice commands.",
                outcome = VerificationOutcome.PERMISSION_REQUIRED
            )
        }
        if (voiceState is VoiceEngineState.Unavailable) {
            return WorkspaceCardState.ErrorOrUnavailableWorkspace(
                title = "Speech Recognition Unavailable",
                detail = voiceState.reason,
                outcome = VerificationOutcome.UNSUPPORTED
            )
        }
        if (voiceState is VoiceEngineState.Error) {
            return WorkspaceCardState.ErrorOrUnavailableWorkspace(
                title = "Voice Recognition Error",
                detail = voiceState.errorDescription,
                outcome = VerificationOutcome.VERIFICATION_FAILED
            )
        }

        // 5. Last action verification outcome
        if (lastVerifiedResult != null) {
            if (lastVerifiedResult.toolName.equals("query_photos", ignoreCase = true) &&
                latestPhotoWorkspaceState != null
            ) {
                return WorkspaceCardState.PhotoWorkspace(photoState = latestPhotoWorkspaceState)
            }

            if (latestAccessibilityWorkspaceState != null &&
                lastVerifiedResult.toolName.lowercase() in com.example.nova.tools.SystemToolExecutor.ACCESSIBILITY_TOOL_NAMES
            ) {
                if (lastVerifiedResult.outcome == VerificationOutcome.USER_CANCELLED) {
                    return WorkspaceCardState.None
                }
                return WorkspaceCardState.AccessibilityWorkspace(
                    workspaceState = latestAccessibilityWorkspaceState
                )
            }

            return when (lastVerifiedResult.outcome) {
                VerificationOutcome.VERIFIED_SUCCESS -> {
                    if (lastVerifiedResult.toolName.equals("query_device_info", ignoreCase = true) &&
                        latestDeviceInfo != null
                    ) {
                        WorkspaceCardState.DeviceInfoWorkspace(deviceInfo = latestDeviceInfo)
                    } else {
                        WorkspaceCardState.VerifiedActionSummary(result = lastVerifiedResult)
                    }
                }

                VerificationOutcome.USER_CANCELLED ->
                    WorkspaceCardState.None

                VerificationOutcome.PERMISSION_REQUIRED,
                VerificationOutcome.SERVICE_DISABLED,
                VerificationOutcome.CONFIGURATION_REQUIRED,
                VerificationOutcome.UNSUPPORTED,
                VerificationOutcome.VERIFICATION_FAILED,
                VerificationOutcome.AWAITING_CONFIRMATION ->
                    WorkspaceCardState.ErrorOrUnavailableWorkspace(
                        title = "${lastVerifiedResult.toolName}: ${lastVerifiedResult.outcome.name}",
                        detail = lastVerifiedResult.userFacingMessage.ifBlank {
                            lastVerifiedResult.verificationDetail
                        },
                        outcome = lastVerifiedResult.outcome
                    )
            }
        }

        // 6. Single active/recent task fallback when no lastVerifiedResult is set
        if (activeTasks.isNotEmpty()) {
            return WorkspaceCardState.MultiTaskWorkspace(
                tasks = activeTasks,
                deviceInfo = latestDeviceInfo,
                photoState = latestPhotoWorkspaceState,
                accessibilityWorkspaceState = latestAccessibilityWorkspaceState
            )
        }

        // 7. Provider configuration or network unavailability when no tool was executed
        if (sessionStage == AssistantSessionStage.PERMISSION_OR_CONFIG_REQUIRED) {
            return WorkspaceCardState.ErrorOrUnavailableWorkspace(
                title = statusBanner.ifBlank { "Configuration Required" },
                detail = latestDiagnosticMessage.ifBlank { statusBanner },
                outcome = VerificationOutcome.CONFIGURATION_REQUIRED
            )
        }

        // 8. Provider or runtime failure when no tool was executed
        if (sessionStage == AssistantSessionStage.ERROR) {
            return WorkspaceCardState.ErrorOrUnavailableWorkspace(
                title = statusBanner.ifBlank { "Runtime Error" },
                detail = latestDiagnosticMessage.ifBlank { statusBanner },
                outcome = VerificationOutcome.VERIFICATION_FAILED
            )
        }

        return WorkspaceCardState.None
    }

    /**
     * M7 — Connects a completed or actionable [com.example.nova.core.LiveTaskItem] to its
     * corresponding specialized [WorkspaceCardState] (`DeviceInfoWorkspace`, `PhotoWorkspace`,
     * `AccessibilityWorkspace`, or `VerifiedActionSummary`) without duplicating feature logic.
     */
    fun resolveTaskConnectedWorkspace(
        task: com.example.nova.core.LiveTaskItem,
        latestDeviceInfo: com.example.nova.core.NovaDeviceInfo? = null,
        latestPhotoWorkspaceState: com.example.nova.core.PhotoAccessWorkspaceState? = null,
        latestAccessibilityWorkspaceState: com.example.nova.core.AccessibilityWorkspaceState? = null
    ): WorkspaceCardState {
        return when (task.workspaceReference) {
            com.example.nova.core.LiveTaskWorkspaceReference.DEVICE_INFO_WORKSPACE -> {
                if (task.status == com.example.nova.core.LiveTaskStatus.COMPLETED_VERIFIED &&
                    latestDeviceInfo != null
                ) {
                    WorkspaceCardState.DeviceInfoWorkspace(deviceInfo = latestDeviceInfo)
                } else {
                    WorkspaceCardState.None
                }
            }
            com.example.nova.core.LiveTaskWorkspaceReference.PHOTO_WORKSPACE -> {
                if (latestPhotoWorkspaceState != null) {
                    WorkspaceCardState.PhotoWorkspace(photoState = latestPhotoWorkspaceState)
                } else {
                    WorkspaceCardState.None
                }
            }
            com.example.nova.core.LiveTaskWorkspaceReference.ACCESSIBILITY_WORKSPACE -> {
                if (latestAccessibilityWorkspaceState != null) {
                    WorkspaceCardState.AccessibilityWorkspace(workspaceState = latestAccessibilityWorkspaceState)
                } else {
                    WorkspaceCardState.None
                }
            }
            com.example.nova.core.LiveTaskWorkspaceReference.VERIFIED_ACTION_SUMMARY -> {
                val res = task.verifiedResult
                if (task.status == com.example.nova.core.LiveTaskStatus.COMPLETED_VERIFIED && res != null) {
                    WorkspaceCardState.VerifiedActionSummary(result = res)
                } else {
                    WorkspaceCardState.None
                }
            }
            null -> WorkspaceCardState.None
        }
    }
}
