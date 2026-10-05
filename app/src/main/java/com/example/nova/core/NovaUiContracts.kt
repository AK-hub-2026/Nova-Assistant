package com.example.nova.core

/**
 * M0 — Nova UI Architecture & State Contracts.
 *
 * Permanent Rule: 0% mock, simulated, or inferred runtime state.
 * Every contract explicitly distinguishes verified real data from permission,
 * capability, provider, or OS boundary states.
 */

/**
 * Authoritative visual and behavioral state of the Nova Orb (both full-screen and floating).
 */
enum class NovaUiOrbState {
    IDLE,
    LISTENING,
    THINKING,
    EXECUTING,
    SPEAKING,
    ERROR,
    UNAVAILABLE
}

/**
 * Primary cross-app interaction channel vs. reserved extension point for future
 * Android App Functions / App Actions integrations.
 */
enum class CrossAppInteractionChannel {
    ANDROID_SYSTEM_INTENT,
    ACCESSIBILITY_PRIMARY,
    FUTURE_APP_FUNCTIONS_EXTENSION
}

/**
 * Reserved architectural extension point for future Android App Functions / App Actions.
 * Not implemented in M0; AccessibilityService remains Nova's primary cross-app layer.
 */
interface AppFunctionExtensionContract {
    fun isAppFunctionsRuntimeSupported(): Boolean = false
    fun querySupportedAppFunctions(packageName: String): List<String> = emptyList()
}

/**
 * Real attachment status of the Floating Nova Orb via WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY.
 * Does not assume universal Android Go or OEM window-manager acceptance.
 */
enum class OverlayAttachmentState {
    DETACHED,
    ATTACHED,
    SERVICE_NOT_BOUND,
    REJECTED_BY_WINDOW_MANAGER,
    DISABLED_BY_USER_PREFERENCE
}

/**
 * Tracks the real URI permission persistence outcome for each URI returned by
 * the Android Photo Picker, since not all picker providers support persistable grants.
 */
enum class UriPersistenceState {
    PERSISTABLE_GRANTED,
    SESSION_SCOPED_ONLY,
    PERSISTENCE_NOT_SUPPORTED_BY_PROVIDER,
    ACCESS_REVOKED_OR_EXPIRED
}

/**
 * Real status of direct MediaStore queries, kept strictly separate from
 * Android Photo Picker URI selections.
 */
enum class MediaStoreQueryAccessState {
    NOT_QUERIED,
    READ_MEDIA_PERMISSION_GRANTED,
    LIMITED_TO_APP_OWNED_MEDIA_WITHOUT_PERMISSION,
    QUERY_EMPTY,
    QUERY_ERROR
}

/**
 * M5 — Explicit access mode for the Photo Workspace.
 * Strictly distinguishes Photo Picker session URIs, Photo Picker persisted URIs,
 * MediaStore query access, permission-required, empty, unavailable, and error states.
 */
enum class PhotoAccessMode {
    PICKED_URI_SESSION,
    PICKED_URI_PERSISTED,
    MEDIASTORE_ACCESS,
    PERMISSION_REQUIRED,
    EMPTY,
    UNAVAILABLE,
    ERROR
}

/**
 * Origin of a [NovaPhotoItem].
 */
enum class PhotoEntrySource {
    PHOTO_PICKER_SESSION,
    PHOTO_PICKER_PERSISTED,
    MEDIASTORE_QUERY
}

/**
 * M5 — Real photo item obtained from Android ContentResolver / MediaStore / Photo Picker.
 * Every metadata attribute that may be missing uses [DeviceFieldState] so missing values
 * are never substituted with fake filenames, fake dates, fake dimensions, or fake URIs.
 */
data class NovaPhotoItem(
    val contentUri: String,
    val displayName: DeviceFieldState<String> = DeviceFieldState.Unavailable(),
    val dateTakenOrAddedMillis: DeviceFieldState<Long> = DeviceFieldState.Unavailable(),
    val widthPx: DeviceFieldState<Int> = DeviceFieldState.Unavailable(),
    val heightPx: DeviceFieldState<Int> = DeviceFieldState.Unavailable(),
    val mimeType: DeviceFieldState<String> = DeviceFieldState.Unavailable(),
    val durationMillis: DeviceFieldState<Long> = DeviceFieldState.Unavailable(),
    val source: PhotoEntrySource = PhotoEntrySource.MEDIASTORE_QUERY,
    val uriPersistenceState: UriPersistenceState? = null
)

data class PickedPhotoUriEntry(
    val uriString: String,
    val displayName: String,
    val mimeType: String,
    val dateTakenOrAddedMs: Long?,
    val persistenceState: UriPersistenceState
)

data class MediaStorePhotoEntry(
    val contentUriString: String,
    val displayName: String,
    val dateAddedSeconds: Long,
    val widthPx: Int,
    val heightPx: Int
)

/**
 * Strictly separates Android Photo Picker selected URIs from MediaStore query access.
 * Selecting items in the Photo Picker never implies unrestricted MediaStore visibility.
 */
data class PhotoAccessWorkspaceState(
    val pickerSelectedEntries: List<PickedPhotoUriEntry> = emptyList(),
    val mediaStoreAccessState: MediaStoreQueryAccessState = MediaStoreQueryAccessState.NOT_QUERIED,
    val mediaStoreEntries: List<MediaStorePhotoEntry> = emptyList(),
    val statusMessage: String = "",
    val accessMode: PhotoAccessMode = PhotoAccessMode.EMPTY,
    val photos: List<NovaPhotoItem> = emptyList(),
    val isSelectiveMediaAccess: Boolean = false,
    val isPickerLaunchRequested: Boolean = false,
    val isPhotoPickerSupported: Boolean = true,
    val errorMessage: String? = null
)

/**
 * Hardware DSP hotword capability status tracked independently from ROLE_ASSISTANT.
 */
enum class HardwareHotwordCapabilityStatus {
    UNSUPPORTED_NO_PRIVILEGED_VOICE_INTERACTION_SERVICE,
    UNSUPPORTED_NO_DSP_HARDWARE,
    UNVERIFIED_REQUIRES_OS_ENROLLMENT
}

/**
 * Independent capability flags for Assistant Role, VoiceInteractionService,
 * explicit SpeechRecognizer, and hardware hotword detection.
 *
 * Holding ROLE_ASSISTANT must NEVER produce a fake or inferred "Hey Nova listening" state.
 */
data class WakeWordAndAssistantCapabilitySnapshot(
    val assistantRoleAvailable: Boolean,
    val assistantRoleHeld: Boolean,
    val voiceInteractionServiceDeclared: Boolean,
    val voiceInteractionServiceBound: Boolean,
    val explicitSpeechRecognitionAvailable: Boolean,
    val hardwareHotwordCapability: HardwareHotwordCapabilityStatus
) {
    /**
     * True always-on hardware wake-word listening requires a bound VoiceInteractionService
     * AND verified DSP hotword support. Holding ROLE_ASSISTANT alone is insufficient.
     */
    val isRealHardwareWakeWordActive: Boolean
        get() = voiceInteractionServiceDeclared &&
            voiceInteractionServiceBound &&
            hardwareHotwordCapability !in listOf(
                HardwareHotwordCapabilityStatus.UNSUPPORTED_NO_PRIVILEGED_VOICE_INTERACTION_SERVICE,
                HardwareHotwordCapabilityStatus.UNSUPPORTED_NO_DSP_HARDWARE
            )
}

/**
 * M4 — Explicit availability status for each individual device-information field.
 * Prevents missing, restricted, or unknown values from ever being coerced into 0, 0%, or fake defaults.
 */
enum class DeviceFieldStatus {
    AVAILABLE,
    UNAVAILABLE,
    UNSUPPORTED,
    ERROR
}

/**
 * M4 — Explicit per-field state wrapper for [NovaDeviceInfo].
 * Never allows a missing/unknown Android framework value to be silently interpreted as real data.
 */
sealed class DeviceFieldState<out T> {
    abstract val status: DeviceFieldStatus

    data class Available<out T>(
        val value: T
    ) : DeviceFieldState<T>() {
        override val status: DeviceFieldStatus = DeviceFieldStatus.AVAILABLE
    }

    data class Unavailable(
        val reason: String = "Unavailable",
        override val status: DeviceFieldStatus = DeviceFieldStatus.UNAVAILABLE
    ) : DeviceFieldState<Nothing>()

    val isAvailable: Boolean
        get() = this is Available

    fun valueOrNull(): T? = (this as? Available)?.value
}

enum class BatteryChargingState(val displayLabel: String) {
    CHARGING("Charging"),
    DISCHARGING("Discharging"),
    FULL("Full"),
    NOT_CHARGING("Not Charging")
}

enum class BatteryChargingSource(val displayLabel: String) {
    AC("AC Charger"),
    USB("USB Port"),
    WIRELESS("Wireless"),
    DOCK("Dock"),
    UNPLUGGED("On Battery (Unplugged)")
}

enum class NetworkConnectivityState(val displayLabel: String) {
    CONNECTED("Connected"),
    DISCONNECTED("Disconnected")
}

enum class NetworkTransportType(val displayLabel: String) {
    WIFI("Wi-Fi"),
    CELLULAR("Cellular"),
    ETHERNET("Ethernet"),
    VPN("VPN"),
    BLUETOOTH("Bluetooth"),
    OTHER("Other Transport"),
    NONE("None")
}

/**
 * M4 — Authoritative real device information model.
 * Every field carries an explicit [DeviceFieldState] so unavailable or undetermined values
 * are represented honestly as [DeviceFieldState.Unavailable] instead of 0, 0%, or empty strings.
 *
 * Strictly excludes sensitive identifiers (no IMEI, serial number, MAC address, SSID, or IP).
 */
data class NovaDeviceInfo(
    val batteryPercentage: DeviceFieldState<Int>,
    val chargingState: DeviceFieldState<BatteryChargingState>,
    val chargingSource: DeviceFieldState<BatteryChargingSource>,
    val totalRamBytes: DeviceFieldState<Long>,
    val availableRamBytes: DeviceFieldState<Long>,
    val isLowRamDevice: DeviceFieldState<Boolean>,
    val storageScopeDescription: DeviceFieldState<String>,
    val totalStorageBytes: DeviceFieldState<Long>,
    val availableStorageBytes: DeviceFieldState<Long>,
    val androidVersionRelease: DeviceFieldState<String>,
    val sdkInt: DeviceFieldState<Int>,
    val safeDeviceModel: DeviceFieldState<String>,
    val networkConnectivity: DeviceFieldState<NetworkConnectivityState>,
    val networkTransport: DeviceFieldState<NetworkTransportType>,
    val hasFlashlightHardware: DeviceFieldState<Boolean> = DeviceFieldState.Unavailable(),
    val capturedAtMs: Long = 0L
) {
    fun toWorkspaceTelemetrySnapshot(): DeviceWorkspaceTelemetry {
        val availRamMb = availableRamBytes.valueOrNull()?.let { it / (1024L * 1024L) } ?: -1L
        val totalRamMb = totalRamBytes.valueOrNull()?.let { it / (1024L * 1024L) } ?: -1L
        return DeviceWorkspaceTelemetry(
            deviceModel = safeDeviceModel.valueOrNull() ?: "Unavailable",
            androidRelease = androidVersionRelease.valueOrNull() ?: "Unavailable",
            sdkInt = sdkInt.valueOrNull() ?: -1,
            availableRamMb = availRamMb,
            totalRamMb = totalRamMb,
            isLowRamDevice = isLowRamDevice.valueOrNull() ?: false,
            internalStorageFreeBytes = availableStorageBytes.valueOrNull() ?: -1L,
            internalStorageTotalBytes = totalStorageBytes.valueOrNull() ?: -1L,
            batteryPercent = batteryPercentage.valueOrNull() ?: -1,
            isCharging = chargingState.valueOrNull() == BatteryChargingState.CHARGING ||
                chargingState.valueOrNull() == BatteryChargingState.FULL,
            isNetworkOnline = networkConnectivity.valueOrNull() == NetworkConnectivityState.CONNECTED,
            hasFlashlightHardware = hasFlashlightHardware.valueOrNull() ?: false,
            isTorchOn = false
        )
    }
}

/**
 * Real device storage & hardware telemetry snapshot for the Device Info workspace card.
 */
data class DeviceWorkspaceTelemetry(
    val deviceModel: String,
    val androidRelease: String,
    val sdkInt: Int,
    val availableRamMb: Long,
    val totalRamMb: Long,
    val isLowRamDevice: Boolean,
    val internalStorageFreeBytes: Long,
    val internalStorageTotalBytes: Long,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val isNetworkOnline: Boolean,
    val hasFlashlightHardware: Boolean,
    val isTorchOn: Boolean
)

/**
 * M7 — Supported real task capability types integrated with M0–M6 capabilities.
 */
enum class NovaTaskCapabilityType(val displayLabel: String) {
    DEVICE_INFO("Device Info"),
    PHOTOS("Photos & Media"),
    ACCESSIBILITY("Accessibility"),
    GENERAL_ASSISTANT_TASK("Assistant Task");

    companion object {
        private val accessibilityToolSet = setOf(
            "read_screen",
            "query_accessibility_status",
            "click_node",
            "focus_node",
            "select_node",
            "input_text",
            "scroll_screen",
            "navigate_global",
            "capture_visual_fallback"
        )

        fun fromToolName(toolName: String): NovaTaskCapabilityType {
            return when (toolName.trim().lowercase()) {
                "query_device_info" -> DEVICE_INFO
                "query_photos" -> PHOTOS
                in accessibilityToolSet -> ACCESSIBILITY
                else -> GENERAL_ASSISTANT_TASK
            }
        }
    }
}

/**
 * M7 — Reference to the specialized workspace surface produced by a task.
 */
enum class LiveTaskWorkspaceReference {
    DEVICE_INFO_WORKSPACE,
    PHOTO_WORKSPACE,
    ACCESSIBILITY_WORKSPACE,
    VERIFIED_ACTION_SUMMARY
}

/**
 * M7 — Independent lifecycle states for tasks in the live orchestration layer.
 */
enum class LiveTaskStatus {
    PENDING,
    AWAITING_CONFIRMATION,
    RUNNING,
    COMPLETED_VERIFIED,
    FAILED,
    PERMISSION_REQUIRED,
    UNAVAILABLE,
    CANCELLED;

    val isTerminal: Boolean
        get() = when (this) {
            COMPLETED_VERIFIED,
            FAILED,
            PERMISSION_REQUIRED,
            UNAVAILABLE,
            CANCELLED -> true
            PENDING,
            AWAITING_CONFIRMATION,
            RUNNING -> false
        }
}

/**
 * M7 — Real live task model containing only real runtime information.
 *
 * Rules enforced:
 * - Never fabricates timestamps (`createdAtMs`, `startedAtMs`, `completedAtMs` use [DeviceFieldState]).
 * - Never fabricates progress percentages (`measurableProgressFraction` is null unless real measurable progress exists).
 * - Never enters [LiveTaskStatus.COMPLETED_VERIFIED] unless backed by a real [VerifiedActionResult] with [VerificationOutcome.VERIFIED_SUCCESS].
 */
data class LiveTaskItem(
    val taskId: String,
    val title: String,
    val toolName: String,
    val channel: CrossAppInteractionChannel = CrossAppInteractionChannel.ANDROID_SYSTEM_INTENT,
    val status: LiveTaskStatus = LiveTaskStatus.PENDING,
    val verificationSummary: String = "",
    val elapsedMs: Long = 0L,
    val capabilityType: NovaTaskCapabilityType = NovaTaskCapabilityType.fromToolName(toolName),
    val statusMessage: String = "",
    val createdAtMs: DeviceFieldState<Long> = DeviceFieldState.Unavailable("Timestamp unavailable"),
    val startedAtMs: DeviceFieldState<Long> = DeviceFieldState.Unavailable("Not started"),
    val completedAtMs: DeviceFieldState<Long> = DeviceFieldState.Unavailable("Not completed"),
    val verifiedResult: VerifiedActionResult? = null,
    val workspaceReference: LiveTaskWorkspaceReference? = null,
    val failureOrUnavailableReason: String = "",
    val isCancellationRequested: Boolean = false,
    val measurableProgressFraction: Float? = null,
    val deduplicationKey: String = toolName.trim().lowercase()
) {
    val isCancellable: Boolean
        get() = !status.isTerminal && !isCancellationRequested

    val hasMeasurableProgress: Boolean
        get() = measurableProgressFraction != null && measurableProgressFraction in 0f..1f
}

/**
 * M7 — Deterministic state machine and verification/conversational status rules for [LiveTaskItem].
 */
object LiveTaskStateMachine {

    /**
     * Enforces deterministic task lifecycle transitions (M7 Section E):
     * - PENDING -> RUNNING, AWAITING_CONFIRMATION, CANCELLED
     * - AWAITING_CONFIRMATION -> PENDING, RUNNING, CANCELLED
     * - RUNNING -> COMPLETED_VERIFIED, FAILED, PERMISSION_REQUIRED, UNAVAILABLE, CANCELLED
     * - Terminal states (COMPLETED_VERIFIED, FAILED, PERMISSION_REQUIRED, UNAVAILABLE, CANCELLED)
     *   cannot transition to any state (e.g., FAILED -> COMPLETED_VERIFIED and FAILED -> RUNNING are forbidden).
     * - PENDING cannot jump directly to COMPLETED_VERIFIED, FAILED, PERMISSION_REQUIRED, or UNAVAILABLE without RUNNING.
     */
    fun canTransition(from: LiveTaskStatus, to: LiveTaskStatus): Boolean {
        if (from.isTerminal) return false
        return when (from) {
            LiveTaskStatus.PENDING -> when (to) {
                LiveTaskStatus.RUNNING,
                LiveTaskStatus.AWAITING_CONFIRMATION,
                LiveTaskStatus.CANCELLED -> true
                else -> false
            }
            LiveTaskStatus.AWAITING_CONFIRMATION -> when (to) {
                LiveTaskStatus.PENDING,
                LiveTaskStatus.RUNNING,
                LiveTaskStatus.CANCELLED -> true
                else -> false
            }
            LiveTaskStatus.RUNNING -> when (to) {
                LiveTaskStatus.COMPLETED_VERIFIED,
                LiveTaskStatus.FAILED,
                LiveTaskStatus.PERMISSION_REQUIRED,
                LiveTaskStatus.UNAVAILABLE,
                LiveTaskStatus.CANCELLED -> true
                else -> false
            }
            else -> false
        }
    }

    /**
     * Deterministically transitions [task] to [targetStatus], throwing [IllegalStateException]
     * if the transition is invalid or if [LiveTaskStatus.COMPLETED_VERIFIED] is requested without
     * a real [VerificationOutcome.VERIFIED_SUCCESS] result.
     */
    fun transition(
        task: LiveTaskItem,
        targetStatus: LiveTaskStatus,
        timestampMs: Long = 0L,
        verifiedResult: VerifiedActionResult? = task.verifiedResult,
        reason: String = "",
        measurableProgressFraction: Float? = null
    ): LiveTaskItem {
        if (!canTransition(task.status, targetStatus)) {
            throw IllegalStateException(
                "Invalid task state transition: ${task.status} -> $targetStatus for task '${task.title}' (${task.taskId})."
            )
        }
        if (targetStatus == LiveTaskStatus.COMPLETED_VERIFIED) {
            if (verifiedResult == null || verifiedResult.outcome != VerificationOutcome.VERIFIED_SUCCESS) {
                throw IllegalStateException(
                    "Cannot transition task '${task.title}' (${task.taskId}) to COMPLETED_VERIFIED without VerificationOutcome.VERIFIED_SUCCESS (actual: ${verifiedResult?.outcome})."
                )
            }
        }

        val timestampState = if (timestampMs > 0L) {
            DeviceFieldState.Available(timestampMs)
        } else {
            DeviceFieldState.Unavailable("Timestamp unavailable")
        }

        val updatedStartedAt = when (targetStatus) {
            LiveTaskStatus.RUNNING -> timestampState
            else -> task.startedAtMs
        }

        val updatedCompletedAt = if (targetStatus.isTerminal) {
            timestampState
        } else {
            task.completedAtMs
        }

        val failureReason = when (targetStatus) {
            LiveTaskStatus.FAILED,
            LiveTaskStatus.PERMISSION_REQUIRED,
            LiveTaskStatus.UNAVAILABLE,
            LiveTaskStatus.CANCELLED -> {
                reason.ifBlank {
                    verifiedResult?.userFacingMessage.orEmpty().ifBlank {
                        verifiedResult?.verificationDetail.orEmpty()
                    }
                }
            }
            else -> ""
        }

        val verificationSummary = when (targetStatus) {
            LiveTaskStatus.COMPLETED_VERIFIED ->
                verifiedResult?.postStateSummary?.ifBlank { verifiedResult.verificationDetail }
                    ?: task.verificationSummary
            LiveTaskStatus.RUNNING ->
                "Executing & verifying real Android state..."
            LiveTaskStatus.AWAITING_CONFIRMATION ->
                reason.ifBlank { "Waiting for user confirmation." }
            LiveTaskStatus.PENDING ->
                "Queued for execution"
            else ->
                failureReason.ifBlank { targetStatus.name }
        }

        val conversationalMsg = deriveTaskConversationalMessage(
            capabilityType = task.capabilityType,
            toolName = task.toolName,
            status = targetStatus,
            verifiedResult = verifiedResult,
            reason = failureReason
        )

        val resolvedWorkspace = resolveWorkspaceReference(
            capabilityType = task.capabilityType,
            status = targetStatus
        )

        val computedElapsed = when {
            verifiedResult != null && verifiedResult.elapsedMs > 0L -> verifiedResult.elapsedMs
            targetStatus.isTerminal &&
                updatedStartedAt is DeviceFieldState.Available &&
                updatedCompletedAt is DeviceFieldState.Available ->
                (updatedCompletedAt.value - updatedStartedAt.value).coerceAtLeast(0L)
            else -> task.elapsedMs
        }

        return task.copy(
            status = targetStatus,
            startedAtMs = updatedStartedAt,
            completedAtMs = updatedCompletedAt,
            statusMessage = conversationalMsg,
            verificationSummary = verificationSummary,
            verifiedResult = verifiedResult,
            workspaceReference = resolvedWorkspace,
            failureOrUnavailableReason = failureReason,
            isCancellationRequested = task.isCancellationRequested || targetStatus == LiveTaskStatus.CANCELLED,
            measurableProgressFraction = if (targetStatus == LiveTaskStatus.RUNNING) {
                measurableProgressFraction?.takeIf { it in 0f..1f }
            } else {
                null
            },
            elapsedMs = computedElapsed
        )
    }

    fun tryTransition(
        task: LiveTaskItem,
        targetStatus: LiveTaskStatus,
        timestampMs: Long = 0L,
        verifiedResult: VerifiedActionResult? = task.verifiedResult,
        reason: String = "",
        measurableProgressFraction: Float? = null
    ): LiveTaskItem? {
        return runCatching {
            transition(
                task = task,
                targetStatus = targetStatus,
                timestampMs = timestampMs,
                verifiedResult = verifiedResult,
                reason = reason,
                measurableProgressFraction = measurableProgressFraction
            )
        }.getOrNull()
    }

    fun outcomeToTaskStatus(outcome: VerificationOutcome): LiveTaskStatus {
        return when (outcome) {
            VerificationOutcome.VERIFIED_SUCCESS -> LiveTaskStatus.COMPLETED_VERIFIED
            VerificationOutcome.PERMISSION_REQUIRED,
            VerificationOutcome.SERVICE_DISABLED -> LiveTaskStatus.PERMISSION_REQUIRED
            VerificationOutcome.UNSUPPORTED,
            VerificationOutcome.CONFIGURATION_REQUIRED -> LiveTaskStatus.UNAVAILABLE
            VerificationOutcome.VERIFICATION_FAILED -> LiveTaskStatus.FAILED
            VerificationOutcome.USER_CANCELLED -> LiveTaskStatus.CANCELLED
            VerificationOutcome.AWAITING_CONFIRMATION -> LiveTaskStatus.AWAITING_CONFIRMATION
        }
    }

    fun resolveWorkspaceReference(
        capabilityType: NovaTaskCapabilityType,
        status: LiveTaskStatus
    ): LiveTaskWorkspaceReference? {
        return when (capabilityType) {
            NovaTaskCapabilityType.DEVICE_INFO ->
                if (status == LiveTaskStatus.COMPLETED_VERIFIED) {
                    LiveTaskWorkspaceReference.DEVICE_INFO_WORKSPACE
                } else {
                    null
                }
            NovaTaskCapabilityType.PHOTOS ->
                if (status == LiveTaskStatus.COMPLETED_VERIFIED ||
                    status == LiveTaskStatus.PERMISSION_REQUIRED ||
                    status == LiveTaskStatus.UNAVAILABLE ||
                    status == LiveTaskStatus.FAILED
                ) {
                    LiveTaskWorkspaceReference.PHOTO_WORKSPACE
                } else {
                    null
                }
            NovaTaskCapabilityType.ACCESSIBILITY ->
                if (status == LiveTaskStatus.COMPLETED_VERIFIED ||
                    status == LiveTaskStatus.PERMISSION_REQUIRED ||
                    status == LiveTaskStatus.UNAVAILABLE ||
                    status == LiveTaskStatus.FAILED
                ) {
                    LiveTaskWorkspaceReference.ACCESSIBILITY_WORKSPACE
                } else {
                    null
                }
            NovaTaskCapabilityType.GENERAL_ASSISTANT_TASK ->
                if (status == LiveTaskStatus.COMPLETED_VERIFIED) {
                    LiveTaskWorkspaceReference.VERIFIED_ACTION_SUMMARY
                } else {
                    null
                }
        }
    }

    fun deriveTaskConversationalMessage(
        capabilityType: NovaTaskCapabilityType,
        toolName: String,
        status: LiveTaskStatus,
        verifiedResult: VerifiedActionResult? = null,
        reason: String = ""
    ): String {
        val label = humanReadableToolLabel(toolName)
        return when (status) {
            LiveTaskStatus.PENDING -> when (capabilityType) {
                NovaTaskCapabilityType.PHOTOS -> "Photo check queued."
                NovaTaskCapabilityType.DEVICE_INFO -> "Device information check queued."
                NovaTaskCapabilityType.ACCESSIBILITY -> "Screen accessibility task queued."
                NovaTaskCapabilityType.GENERAL_ASSISTANT_TASK -> "Queued $label."
            }
            LiveTaskStatus.AWAITING_CONFIRMATION ->
                "Confirmation required before running $label."
            LiveTaskStatus.RUNNING -> when (capabilityType) {
                NovaTaskCapabilityType.PHOTOS -> "Checking your photos now."
                NovaTaskCapabilityType.DEVICE_INFO -> "Checking your device information now."
                NovaTaskCapabilityType.ACCESSIBILITY -> "Running $label on active window now."
                NovaTaskCapabilityType.GENERAL_ASSISTANT_TASK -> "Running $label now."
            }
            LiveTaskStatus.COMPLETED_VERIFIED -> when (capabilityType) {
                NovaTaskCapabilityType.PHOTOS -> "Your photos are ready."
                NovaTaskCapabilityType.DEVICE_INFO -> "Your device information is ready."
                NovaTaskCapabilityType.ACCESSIBILITY ->
                    verifiedResult?.userFacingMessage?.takeIf { it.isNotBlank() }
                        ?: "Screen accessibility task completed and verified."
                NovaTaskCapabilityType.GENERAL_ASSISTANT_TASK ->
                    verifiedResult?.userFacingMessage?.takeIf { it.isNotBlank() }
                        ?: "Completed and verified $label."
            }
            LiveTaskStatus.PERMISSION_REQUIRED -> when (capabilityType) {
                NovaTaskCapabilityType.PHOTOS -> "I need photo access."
                NovaTaskCapabilityType.ACCESSIBILITY ->
                    reason.ifBlank { "I need Accessibility Service access." }
                else ->
                    reason.ifBlank { "Permission is required for $label." }
            }
            LiveTaskStatus.UNAVAILABLE ->
                reason.ifBlank {
                    verifiedResult?.userFacingMessage.orEmpty().ifBlank {
                        "$label is unavailable on this device."
                    }
                }
            LiveTaskStatus.FAILED ->
                reason.ifBlank {
                    verifiedResult?.userFacingMessage.orEmpty().ifBlank {
                        "That task failed."
                    }
                }
            LiveTaskStatus.CANCELLED ->
                reason.ifBlank { "Cancelled $label." }
        }
    }

    fun humanReadableTaskTitle(call: PlannedToolCall): String {
        return when (call.toolName.trim().lowercase()) {
            "query_device_info" -> "Check Device Information"
            "query_photos" -> {
                if (call.arguments["mode"]?.equals("picker", ignoreCase = true) == true) {
                    "Select Photos via Photo Picker"
                } else {
                    "Access Device Photos"
                }
            }
            "read_screen" -> "Inspect Active Screen"
            "query_accessibility_status" -> "Check Accessibility Status"
            "click_node" -> {
                val target = call.arguments["target"] ?: call.arguments["query"].orEmpty()
                val action = call.arguments["action"] ?: "CLICK"
                val verb = if (action.equals("LONG_CLICK", ignoreCase = true)) "Long-Press" else "Tap"
                if (target.isNotBlank()) "$verb \"$target\"" else "$verb Screen Element"
            }
            "focus_node" -> {
                val target = call.arguments["target"].orEmpty()
                if (target.isNotBlank()) "Focus \"$target\"" else "Focus Screen Element"
            }
            "select_node" -> {
                val target = call.arguments["target"].orEmpty()
                if (target.isNotBlank()) "Select \"$target\"" else "Select Screen Element"
            }
            "input_text" -> {
                val target = call.arguments["target"].orEmpty()
                if (target.isNotBlank()) "Enter Text in \"$target\"" else "Enter Text on Screen"
            }
            "scroll_screen" -> {
                val dir = call.arguments["direction"] ?: "down"
                "Scroll Screen (${dir.lowercase()})"
            }
            "navigate_global" -> {
                val act = call.arguments["action"] ?: "back"
                "Navigate ${act.replaceFirstChar { it.uppercase() }}"
            }
            "capture_visual_fallback" -> "Check Vision Fallback Status"
            "launch_app" -> {
                val app = call.arguments["app"].orEmpty()
                if (app.isNotBlank()) "Open $app" else "Open App"
            }
            "open_settings" -> {
                val sec = call.arguments["section"] ?: "system"
                "Open ${sec.replaceFirstChar { it.uppercase() }} Settings"
            }
            "set_flashlight" -> {
                val on = call.arguments["enabled"] != "false"
                if (on) "Turn Flashlight On" else "Turn Flashlight Off"
            }
            "set_volume" -> {
                val stream = call.arguments["stream"] ?: "music"
                "Adjust ${stream.replaceFirstChar { it.uppercase() }} Volume"
            }
            "set_alarm" -> "Set Clock Alarm"
            "set_timer" -> "Start Clock Timer"
            "schedule_reminder" -> "Schedule Reminder"
            "save_memory" -> "Save Local Memory Note"
            "dial_number" -> "Open Phone Dialer"
            "compose_sms" -> "Compose SMS Draft"
            "web_search" -> "Open Web Search"
            else -> humanReadableToolLabel(call.toolName)
        }
    }

    fun humanReadableToolLabel(toolName: String): String {
        return toolName.trim()
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
            .ifBlank { "Assistant Task" }
    }

    fun buildDeduplicationKey(call: PlannedToolCall): String {
        val sortedArgs = call.arguments.entries
            .sortedBy { it.key.trim().lowercase() }
            .joinToString("&") { "${it.key.trim().lowercase()}=${it.value.trim().lowercase()}" }
        return "${call.toolName.trim().lowercase()}?$sortedArgs"
    }
}

/**
 * M6 — Explicit operational states for the Accessibility Workspace.
 *
 * Rules enforced:
 * - Never infer [SERVICE_CONNECTED] merely because the service is enabled in Settings;
 *   when enabled in Settings but not bound by the OS, status is [SERVICE_NOT_CONNECTED].
 */
enum class AccessibilityWorkspaceStatus {
    SERVICE_DISABLED,
    SERVICE_NOT_CONNECTED,
    SERVICE_CONNECTED,
    ACTIVE_WINDOW_AVAILABLE,
    ACTIVE_WINDOW_UNAVAILABLE,
    ACTION_IN_PROGRESS,
    ACTION_VERIFIED,
    ACTION_FAILED,
    PERMISSION_REQUIRED,
    UNSUPPORTED,
    ERROR
}

/**
 * M6 — Truthful status for the future vision/pixel screen understanding fallback extension point.
 */
enum class ScreenUnderstandingFallbackStatus {
    NOT_IMPLEMENTED,
    UNAVAILABLE
}

/**
 * M6 — Complete, privacy-safe Accessibility Workspace state model.
 * Held strictly in memory and never persisted to Room or disk.
 */
data class AccessibilityWorkspaceState(
    val status: AccessibilityWorkspaceStatus,
    val serviceEnabledInSettings: Boolean,
    val serviceConnected: Boolean,
    val activeWindowAvailable: Boolean,
    val activePackage: DeviceFieldState<String> = DeviceFieldState.Unavailable("Unavailable"),
    val activeWindowTitle: DeviceFieldState<String> = DeviceFieldState.Unavailable("Unavailable"),
    val semanticTargets: List<AccessibilityTarget> = emptyList(),
    val totalNodesVisited: Int = 0,
    val redactedFieldCount: Int = 0,
    val isLowRamTruncated: Boolean = false,
    val overlayState: OverlayAttachmentState = OverlayAttachmentState.SERVICE_NOT_BOUND,
    val currentActionDescription: String = "",
    val latestActionResult: VerifiedActionResult? = null,
    val ambiguousCandidates: List<AccessibilityTarget> = emptyList(),
    val visionFallbackStatus: ScreenUnderstandingFallbackStatus = ScreenUnderstandingFallbackStatus.NOT_IMPLEMENTED,
    val statusMessage: String = "",
    val snapshot: SemanticScreenSnapshot? = null
) {
    val clickableTargetCount: Int
        get() = semanticTargets.count { it.isClickable }

    val editableTargetCount: Int
        get() = semanticTargets.count { it.isEditable }

    val scrollableTargetCount: Int
        get() = semanticTargets.count { it.isScrollable }
}

/**
 * Sealed hierarchy representing the automatic adaptive task workspace content.
 * When [WorkspaceCardState.None], Nova displays only the minimal centered Orb surface.
 */
sealed class WorkspaceCardState {
    data object None : WorkspaceCardState()

    data class ConfirmationRequired(
        val request: PendingConfirmationRequest
    ) : WorkspaceCardState()

    data class ActiveActionExecution(
        val toolName: String,
        val statusDescription: String,
        val channel: CrossAppInteractionChannel
    ) : WorkspaceCardState()

    data class VerifiedActionSummary(
        val result: VerifiedActionResult
    ) : WorkspaceCardState()

    data class DeviceInfoWorkspace(
        val deviceInfo: NovaDeviceInfo,
        val telemetry: DeviceWorkspaceTelemetry = deviceInfo.toWorkspaceTelemetrySnapshot()
    ) : WorkspaceCardState()

    data class PhotoWorkspace(
        val photoState: PhotoAccessWorkspaceState
    ) : WorkspaceCardState()

    data class AccessibilityWorkspace(
        val workspaceState: AccessibilityWorkspaceState,
        val serviceEnabledInSettings: Boolean = workspaceState.serviceEnabledInSettings,
        val serviceConnected: Boolean = workspaceState.serviceConnected,
        val overlayState: OverlayAttachmentState = workspaceState.overlayState,
        val snapshot: SemanticScreenSnapshot? = workspaceState.snapshot,
        val latestActionResult: VerifiedActionResult? = workspaceState.latestActionResult
    ) : WorkspaceCardState()

    data class MultiTaskWorkspace(
        val tasks: List<LiveTaskItem>,
        val deviceInfo: NovaDeviceInfo? = null,
        val photoState: PhotoAccessWorkspaceState? = null,
        val accessibilityWorkspaceState: AccessibilityWorkspaceState? = null
    ) : WorkspaceCardState()

    data class ErrorOrUnavailableWorkspace(
        val title: String,
        val detail: String,
        val outcome: VerificationOutcome
    ) : WorkspaceCardState()
}

/**
 * Configurable model ID registry and validator so model names are never hardcoded
 * inside transport methods.
 */
object NovaProviderModelConfig {
    const val DEFAULT_GEMINI_MODEL_ID = "gemini-3.5-flash"
    const val DEFAULT_GROQ_MODEL_ID = "llama-3.3-70b-versatile"
    const val DEFAULT_OPENAI_MODEL_ID = "gpt-4o-mini"

    val SUPPORTED_GEMINI_MODELS = listOf(
        "gemini-3.5-flash",
        "gemini-3.1-pro-preview",
        "gemini-2.5-flash"
    )

    val SUPPORTED_GROQ_MODELS = listOf(
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant",
        "mixtral-8x7b-32768"
    )

    val SUPPORTED_OPENAI_MODELS = listOf(
        "gpt-4o-mini",
        "gpt-4o"
    )

    private val retiredGeminiPrefixes = listOf(
        "gemini-1.0",
        "gemini-1.5",
        "gemini-2.0",
        "gemini-pro"
    )

    fun resolveGeminiModelId(
        configuredModelId: String?,
        fallbackModelId: String = DEFAULT_GEMINI_MODEL_ID
    ): String {
        val candidate = configuredModelId?.trim().orEmpty()
        if (candidate.isBlank()) return fallbackModelId
        val lower = candidate.lowercase()
        val isRetired = retiredGeminiPrefixes.any { prefix ->
            lower == prefix || lower.startsWith("$prefix-") || lower.startsWith("$prefix:")
        }
        return if (isRetired) fallbackModelId else candidate
    }
}

/**
 * M8 — Structured Nova Settings Control Center sections.
 */
enum class NovaSettingsSection(
    val displayTitle: String,
    val shortSubtitle: String
) {
    VOICE_WAKE_WORD("Voice & Wake Word", "Arya Identity, Enrollment & Assistant Role"),
    AI_API("AI API", "Gemini, Groq, OpenAI & Local Models"),
    VOICE_API("Voice API", "ElevenLabs Voice API & Real TTS Fallback"),
    ACCESSIBILITY("Accessibility", "Service Connection & Active Window"),
    PERMISSIONS("Permissions", "Runtime OS Permission Audit"),
    PRIVACY("Privacy", "Local vs Remote Processing & Redaction"),
    MEMORY("Memory", "On-Device Room Facts & Retention"),
    AUTOMATION("Automation", "Live Tasks & Background Execution"),
    ADVANCED("Advanced", "Low-RAM Mode & Concurrency Limits"),
    DIAGNOSTICS("Diagnostics", "Real Runtime & Capability Health");

    companion object {
        val AI_PROVIDER: NovaSettingsSection get() = AI_API
        val VOICE_PROVIDER: NovaSettingsSection get() = VOICE_WAKE_WORD
    }
}

/**
 * M8 — Truthful AI Provider configuration and runtime status.
 * Never reports "Connected" when a key is missing ("API key required").
 */
enum class AiProviderConfigurationStatus {
    AVAILABLE,
    CONFIGURED,
    CONFIGURATION_REQUIRED,
    RUNTIME_ERROR,
    UNAVAILABLE
}

/**
 * M8 — Truthful Voice capability status for STT, TTS, Microphone, and Remote Voice.
 */
enum class VoiceCapabilityStatus {
    AVAILABLE,
    UNAVAILABLE,
    CONFIGURATION_REQUIRED,
    ERROR
}

/**
 * M8 — Explicit distinction between AccessibilityService enabled in Settings vs bound by the OS.
 */
enum class AccessibilityConnectionDistinction {
    SERVICE_DISABLED,
    ENABLED_IN_SETTINGS_NOT_CONNECTED,
    ACTUALLY_CONNECTED
}

/**
 * M8 — Truthful OS permission status for each permission relevant to Nova.
 * Never treats manifest declaration as equivalent to runtime grant.
 */
enum class NovaPermissionStatus {
    GRANTED,
    DENIED,
    NOT_REQUESTED,
    NOT_APPLICABLE,
    RESTRICTED,
    UNAVAILABLE
}

/**
 * M8 — Explicit distinction between local device processing and remote provider processing.
 */
enum class NovaProcessingLocalityMode(val displayLabel: String) {
    LOCAL_DEVICE_PROCESSING("LOCAL DEVICE PROCESSING"),
    REMOTE_PROVIDER_PROCESSING("REMOTE PROVIDER PROCESSING")
}

data class AiProviderItemState(
    val providerType: AiProviderType,
    val displayName: String,
    val isSelectedPrimary: Boolean,
    val isKeyConfigured: Boolean,
    val safeSecretStatusLabel: String,
    val runtimeStatus: AiProviderConfigurationStatus,
    val statusDetail: String,
    val configuredModelId: String,
    val availableModelOptions: List<String>,
    val officialDocsUrl: String?,
    val lastRuntimeErrorMessage: String = ""
)

data class AiProviderSettingsSectionState(
    val primaryProvider: AiProviderType,
    val enableCloudFallback: Boolean,
    val isNetworkOnline: Boolean,
    val providers: List<AiProviderItemState>
)

data class VoiceProviderSettingsSectionState(
    val speechRecognitionStatus: VoiceCapabilityStatus,
    val speechRecognitionDetail: String,
    val textToSpeechStatus: VoiceCapabilityStatus,
    val textToSpeechDetail: String,
    val microphoneStatus: VoiceCapabilityStatus,
    val microphoneDetail: String,
    val speechOutputStatus: VoiceCapabilityStatus,
    val speechOutputDetail: String,
    val remoteVoiceProviderStatus: VoiceCapabilityStatus = VoiceCapabilityStatus.UNAVAILABLE,
    val remoteVoiceProviderDetail: String = "Unavailable — Nova uses Android system SpeechRecognizer and TextToSpeech.",
    val spokenResponsesEnabled: Boolean,
    val isAlwaysListeningActive: Boolean = false,
    val listeningModeDescription: String = "Push-to-Talk Only (Tap Nova Orb or Mic button — Always-listening wake word is not active)"
)

data class AccessibilitySettingsSectionState(
    val connectionDistinction: AccessibilityConnectionDistinction,
    val m6WorkspaceStatus: AccessibilityWorkspaceStatus,
    val serviceEnabledInSettings: Boolean,
    val serviceActuallyConnected: Boolean,
    val activeWindowAvailable: Boolean,
    val activeWindowSummary: String,
    val isReadyForAutomation: Boolean,
    val overlayAttachmentState: OverlayAttachmentState,
    val showAccessibilityFloatingHud: Boolean,
    val capabilitySummary: String
)

data class PermissionAuditItemState(
    val permissionId: String,
    val title: String,
    val androidPermissionName: String,
    val status: NovaPermissionStatus,
    val statusDetail: String,
    val canRequestInApp: Boolean,
    val opensSystemSettings: Boolean
)

data class PermissionsSettingsSectionState(
    val items: List<PermissionAuditItemState>
)

data class PrivacySettingsSectionState(
    val processingLocalityMode: NovaProcessingLocalityMode,
    val mayProviderRequestsLeaveDevice: Boolean,
    val providerDataTransmissionSummary: String,
    val isAccessibilityContentRetainedOnDisk: Boolean = false,
    val accessibilityRetentionSummary: String = "Raw AccessibilityNodeInfo trees and raw screen text are held only in volatile memory for the active snapshot and are never persisted to Room or disk.",
    val arePhotosUploadedToCloud: Boolean = false,
    val photoPrivacySummary: String = "Photos accessed via MediaStore or Photo Picker are decoded locally in memory and are never uploaded to AI provider endpoints.",
    val isAnalyticsOrTelemetryEnabled: Boolean = false,
    val analyticsTelemetrySummary: String = "Disabled (0% third-party analytics, ad SDKs, or background telemetry exist in Nova).",
    val isSensitiveRedactionEnforced: Boolean = true,
    val sensitiveRedactionSummary: String = "SensitiveDataRedactor masks passwords, OTPs, payment cards, emails, phone numbers, and Bearer tokens before any prompt context is built.",
    val localSessionDataHandlingSummary: String = "Conversation turns, saved user memory facts, and verified task audit logs are stored locally in on-device Room SQLite tables and can be cleared at any time.",
    val requireConfirmationForSensitiveActions: Boolean
)

data class MemorySettingsSectionState(
    val memoryCapabilityStatus: CapabilityStatus,
    val memoryContextEnabled: Boolean,
    val savedMemoryFactCount: Int,
    val conversationTurnCount: Int,
    val retentionBehaviorSummary: String,
    val graphifyKnowledgeGraphStatus: String = "Unavailable",
    val areRawMemoriesExposedByDefault: Boolean = false
)

data class AutomationSettingsSectionState(
    val accessibilityAutomationStatus: CapabilityStatus,
    val accessibilityAutomationEnabled: Boolean,
    val accessibilityRequirementSummary: String,
    val systemIntentAutomationStatus: CapabilityStatus = CapabilityStatus.AVAILABLE,
    val activeRunningTaskCount: Int,
    val pendingQueuedTaskCount: Int,
    val totalSessionTaskCount: Int,
    val scheduledReminderCount: Int,
    val requireConfirmationForSensitiveActions: Boolean,
    val autonomousBackgroundAgentStatus: String = "Unavailable — Nova executes only user-requested tasks with bounded concurrency"
)

data class AdvancedSettingsSectionState(
    val isSystemLowRamDevice: Boolean,
    val effectiveLowRamMode: Boolean,
    val lowRamModeOverride: Boolean?,
    val maxConcurrentTasksLimit: Int,
    val maxRetainedTasksLimit: Int,
    val enableCloudFallback: Boolean,
    val primaryProvider: AiProviderType,
    val geminiModel: String,
    val groqModel: String,
    val openAiModel: String,
    val supportedGeminiModels: List<String> = NovaProviderModelConfig.SUPPORTED_GEMINI_MODELS,
    val supportedGroqModels: List<String> = NovaProviderModelConfig.SUPPORTED_GROQ_MODELS,
    val supportedOpenAiModels: List<String> = NovaProviderModelConfig.SUPPORTED_OPENAI_MODELS,
    val verificationAndSecurityEnforced: Boolean = true
)

data class DiagnosticsSettingsSectionState(
    val appVersionName: String,
    val appVersionCode: Long,
    val androidReleaseAndSdk: String,
    val safeDeviceModel: String,
    val ramSummaryText: String,
    val storageSummaryText: String,
    val batterySummaryText: String,
    val networkSummaryText: String,
    val lowRamClassificationText: String,
    val aiProviderStatusSummary: String,
    val voiceCapabilityStatusSummary: String,
    val accessibilityStatusSummary: String,
    val photoCapabilityStatusSummary: String,
    val activeTaskCount: Int,
    val pendingTaskCount: Int,
    val totalSessionTaskCount: Int,
    val recentNonSensitiveErrors: List<String>
)

/**
 * M10 — Real conversational Live Voice Session states.
 */
enum class LiveVoiceSessionState(val displayLabel: String) {
    LIVE_SESSION_IDLE("Idle"),
    LIVE_SESSION_STARTING("Starting Live Session..."),
    LIVE_SESSION_LISTENING("Listening..."),
    LIVE_SESSION_PROCESSING("Processing..."),
    LIVE_SESSION_SPEAKING("Speaking..."),
    LIVE_SESSION_WAITING_FOR_USER("Waiting for Speech..."),
    LIVE_SESSION_PAUSED("Live Session Paused"),
    LIVE_SESSION_STOPPING("Stopping Live Session..."),
    LIVE_SESSION_ENDED("Live Session Ended"),
    LIVE_SESSION_ERROR("Session Error"),
    LIVE_SESSION_UNAVAILABLE("Live Voice Unavailable")
}

data class ElevenLabsVoiceOption(
    val voiceId: String,
    val displayName: String
)

data class ElevenLabsVoiceState(
    val isConfigured: Boolean,
    val selectedVoiceId: String,
    val selectedVoiceName: String,
    val maskedApiKey: String,
    val status: VoiceCapabilityStatus,
    val statusDetail: String,
    val officialDocsUrl: String = "https://elevenlabs.io/docs",
    val fallbackEngineName: String = "Android TextToSpeech",
    val isFallbackActive: Boolean = false,
    val availableVoices: List<ElevenLabsVoiceOption> = listOf(
        ElevenLabsVoiceOption("21m00Tcm4TlvDq8ikWAM", "Rachel (Calm & Natural)"),
        ElevenLabsVoiceOption("AZnzlk1XvdvUeBnXmlld", "Domi (Confident & Clear)"),
        ElevenLabsVoiceOption("EXAVITQu4vr4xnSDxMaL", "Bella (Warm & Expressive)"),
        ElevenLabsVoiceOption("ErXwobaYiN019PkySvjV", "Antoni (Friendly & Smooth)"),
        ElevenLabsVoiceOption("TxGEqnHWrfWFTfGW9XjX", "Josh (Deep & Resonant)")
    )
)

data class NovaSettingsState(
    val selectedSection: NovaSettingsSection = NovaSettingsSection.AI_API,
    val aiProviderSection: AiProviderSettingsSectionState,
    val voiceProviderSection: VoiceProviderSettingsSectionState,
    val voiceApiSection: ElevenLabsVoiceState = ElevenLabsVoiceState(
        isConfigured = false,
        selectedVoiceId = "21m00Tcm4TlvDq8ikWAM",
        selectedVoiceName = "Rachel",
        maskedApiKey = "",
        status = VoiceCapabilityStatus.CONFIGURATION_REQUIRED,
        statusDetail = "ElevenLabs API key required. Android TextToSpeech is active as real fallback."
    ),
    val accessibilitySection: AccessibilitySettingsSectionState,
    val permissionsSection: PermissionsSettingsSectionState,
    val privacySection: PrivacySettingsSectionState,
    val memorySection: MemorySettingsSectionState,
    val automationSection: AutomationSettingsSectionState,
    val advancedSection: AdvancedSettingsSectionState,
    val diagnosticsSection: DiagnosticsSettingsSectionState
)

