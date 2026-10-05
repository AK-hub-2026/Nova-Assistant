package com.example.nova.security

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.SpeechRecognizer
import com.example.nova.core.HardwareHotwordCapabilityStatus
import com.example.nova.core.WakeWordAndAssistantCapabilitySnapshot
import com.example.nova.voice.AryaVoiceInteractionService

/**
 * M9 — Enums for Voice Enrollment, Speaker Verification, Screen-Off, and Device Lock/Unlock.
 */
enum class VoiceEnrollmentStatus(val displayLabel: String) {
    NOT_ENROLLED("Not Enrolled"),
    ENROLLMENT_IN_PROGRESS("Enrollment in Progress"),
    ENROLLED("Enrolled"),
    VOICE_VERIFICATION_NOT_IMPLEMENTED("Voice Verification Not Implemented"),
    ENROLLMENT_FAILED_LOW_QUALITY("Enrollment Failed (Low Audio Quality)"),
    ENROLLMENT_FAILED_NO_MIC("Enrollment Failed (No Microphone Access)")
}

enum class SpeakerVerificationStatus(val displayLabel: String) {
    WAKE_WORD_DETECTED("Wake Word Detected"),
    VOICE_VERIFICATION_REQUIRED("Voice Verification Required"),
    VOICE_VERIFICATION_RUNNING("Voice Verification Running"),
    VOICE_VERIFIED("Voice Verified"),
    VOICE_VERIFICATION_FAILED("Voice Verification Failed"),
    VOICE_VERIFICATION_UNAVAILABLE("Voice Verification Unavailable")
}

enum class ScreenOffAssistantCapability(val displayLabel: String) {
    SCREEN_OFF_ASSISTANT_SUPPORTED("Supported via Hardware Hotword"),
    SCREEN_OFF_ASSISTANT_UNAVAILABLE("Unavailable (Requires Hardware Hotword & Bound VoiceInteractionService)"),
    DEVICE_OR_OS_LIMITATION("Device or OS Limitation")
}

enum class LockScreenAssistantCapability(val displayLabel: String) {
    LOCK_SCREEN_ASSISTANT_SUPPORTED("Supported (Assistant Role & Keyguard Integration Active)"),
    LOCK_SCREEN_ASSISTANT_LIMITED("Limited (Requires Default Assistant Role)"),
    DEVICE_OR_OS_LIMITATION("Device or OS Limitation")
}

enum class DeviceLockCapability(val displayLabel: String) {
    AVAILABLE_VIA_ACCESSIBILITY_GLOBAL_ACTION("Available (GLOBAL_ACTION_LOCK_SCREEN via Accessibility)"),
    AVAILABLE_VIA_DEVICE_ADMIN("Available (Device Administration)"),
    UNAVAILABLE("Unavailable (Requires Connected AccessibilityService or Device Admin)")
}

enum class DeviceUnlockCapability(val displayLabel: String) {
    SECURE_UNLOCK_REQUIRES_DEVICE_AUTHENTICATION("Secure Unlock Requires Device Authentication (PIN/Pattern/Biometric) — Voice verification cannot bypass device Keyguard")
}

enum class OverallWakeWordCapabilityState(val displayLabel: String) {
    FULLY_SUPPORTED("Fully Supported"),
    ASSISTANT_ROLE_REQUIRED("Assistant Role Required"),
    VOICE_INTERACTION_UNAVAILABLE("Voice Interaction Unavailable"),
    HARDWARE_HOTWORD_UNAVAILABLE("Hardware Hotword Unavailable"),
    EXPLICIT_SPEECH_ONLY("Explicit Speech Only (Push-to-Talk)"),
    VOICE_VERIFICATION_UNAVAILABLE("Voice Verification Unavailable"),
    SCREEN_OFF_UNAVAILABLE("Screen-Off Unavailable"),
    DEVICE_OR_OS_LIMITATION("Device or OS Limitation"),
    NOT_IMPLEMENTED("Not Implemented"),
    ERROR("Error")
}

/**
 * M9 — Full capability snapshot encompassing the 6 independent capabilities and truthful subsystems.
 */
data class WakeWordCapabilitySnapshot(
    val assistantRoleAvailable: Boolean,
    val assistantRoleHeld: Boolean,
    val voiceInteractionServiceDeclared: Boolean,
    val voiceInteractionServiceBound: Boolean,
    val explicitSpeechRecognitionAvailable: Boolean,
    val hardwareHotwordCapability: HardwareHotwordCapabilityStatus,
    val voiceEnrollmentStatus: VoiceEnrollmentStatus = VoiceEnrollmentStatus.VOICE_VERIFICATION_NOT_IMPLEMENTED,
    val speakerVerificationStatus: SpeakerVerificationStatus = SpeakerVerificationStatus.VOICE_VERIFICATION_UNAVAILABLE,
    val screenOffAssistantCapability: ScreenOffAssistantCapability = ScreenOffAssistantCapability.SCREEN_OFF_ASSISTANT_UNAVAILABLE,
    val lockScreenAssistantCapability: LockScreenAssistantCapability = LockScreenAssistantCapability.LOCK_SCREEN_ASSISTANT_LIMITED,
    val deviceLockCapability: DeviceLockCapability = DeviceLockCapability.UNAVAILABLE,
    val deviceUnlockCapability: DeviceUnlockCapability = DeviceUnlockCapability.SECURE_UNLOCK_REQUIRES_DEVICE_AUTHENTICATION,
    val overallState: OverallWakeWordCapabilityState = OverallWakeWordCapabilityState.EXPLICIT_SPEECH_ONLY
) {
    fun toLegacyCapabilitySnapshot(): WakeWordAndAssistantCapabilitySnapshot {
        return WakeWordAndAssistantCapabilitySnapshot(
            assistantRoleAvailable = assistantRoleAvailable,
            assistantRoleHeld = assistantRoleHeld,
            voiceInteractionServiceDeclared = voiceInteractionServiceDeclared,
            voiceInteractionServiceBound = voiceInteractionServiceBound,
            explicitSpeechRecognitionAvailable = explicitSpeechRecognitionAvailable,
            hardwareHotwordCapability = hardwareHotwordCapability
        )
    }
}

/**
 * M9 — Real Android Assistant Role and Wake Word Capability Auditor.
 *
 * Enforces strict invariant rules:
 * - assistantRoleHeld != hardwareHotwordCapability
 * - voiceInteractionServiceDeclared != voiceInteractionServiceBound
 * - explicitSpeechRecognitionAvailable != hardwareHotwordCapability
 * - Microphone existence does not infer hardware hotword support.
 * - Android OS version does not automatically mean hotword support exists.
 * - SpeechRecognizer availability does not infer wake-word support.
 * - When VoiceInteractionService is declared in manifest but not bound by the OS: reports DECLARED_NOT_BOUND.
 * - Never claims FULLY_SUPPORTED unless all components are legitimately active.
 */
object WakeWordCapabilityAuditor {

    fun audit(
        context: Context,
        isAccessibilityServiceConnected: Boolean = false,
        voiceEnrollmentStatusOverride: VoiceEnrollmentStatus? = null
    ): WakeWordCapabilitySnapshot {
        val appContext = context.applicationContext

        // 1. Assistant Role Available
        val roleAvailable = isAssistantRoleAvailable(appContext)

        // 2. Assistant Role Held
        val roleHeld = isAssistantRoleHeld(appContext)

        // 3. VoiceInteractionService Declared in AndroidManifest
        val serviceDeclared = isVoiceInteractionServiceDeclared(appContext)

        // 4. VoiceInteractionService Actually Bound by Android OS
        val serviceBound = AryaVoiceInteractionService.isServiceBound.value

        // 5. Explicit Speech Recognition Available
        val speechRecognitionAvailable = isSpeechRecognitionAvailable(appContext)

        // 6. Hardware Hotword Capability (DSP / Always-on privileged voice trigger)
        val hotwordCapability = determineHardwareHotwordCapability(
            voiceInteractionServiceBound = serviceBound,
            roleHeld = roleHeld
        )

        // Lock & Unlock Capabilities
        val lockCapability = if (isAccessibilityServiceConnected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            DeviceLockCapability.AVAILABLE_VIA_ACCESSIBILITY_GLOBAL_ACTION
        } else {
            DeviceLockCapability.UNAVAILABLE
        }
        val unlockCapability = DeviceUnlockCapability.SECURE_UNLOCK_REQUIRES_DEVICE_AUTHENTICATION

        // Screen-off & Lock-screen capabilities
        val screenOffCap = if (serviceBound && hotwordCapability == HardwareHotwordCapabilityStatus.UNVERIFIED_REQUIRES_OS_ENROLLMENT) {
            ScreenOffAssistantCapability.SCREEN_OFF_ASSISTANT_SUPPORTED
        } else {
            ScreenOffAssistantCapability.SCREEN_OFF_ASSISTANT_UNAVAILABLE
        }

        val lockScreenCap = if (roleHeld && serviceBound) {
            LockScreenAssistantCapability.LOCK_SCREEN_ASSISTANT_SUPPORTED
        } else {
            LockScreenAssistantCapability.LOCK_SCREEN_ASSISTANT_LIMITED
        }

        // Voice enrollment status: truthful NOT_IMPLEMENTED by default
        val enrollmentStatus = voiceEnrollmentStatusOverride
            ?: VoiceEnrollmentStatus.VOICE_VERIFICATION_NOT_IMPLEMENTED

        val speakerVerificationStatus = when (enrollmentStatus) {
            VoiceEnrollmentStatus.ENROLLED -> SpeakerVerificationStatus.VOICE_VERIFIED
            VoiceEnrollmentStatus.VOICE_VERIFICATION_NOT_IMPLEMENTED -> SpeakerVerificationStatus.VOICE_VERIFICATION_UNAVAILABLE
            else -> SpeakerVerificationStatus.VOICE_VERIFICATION_UNAVAILABLE
        }

        val overall = deriveOverallCapabilityState(
            roleAvailable = roleAvailable,
            roleHeld = roleHeld,
            serviceDeclared = serviceDeclared,
            serviceBound = serviceBound,
            speechAvailable = speechRecognitionAvailable,
            hotwordCap = hotwordCapability,
            enrollmentStatus = enrollmentStatus
        )

        return WakeWordCapabilitySnapshot(
            assistantRoleAvailable = roleAvailable,
            assistantRoleHeld = roleHeld,
            voiceInteractionServiceDeclared = serviceDeclared,
            voiceInteractionServiceBound = serviceBound,
            explicitSpeechRecognitionAvailable = speechRecognitionAvailable,
            hardwareHotwordCapability = hotwordCapability,
            voiceEnrollmentStatus = enrollmentStatus,
            speakerVerificationStatus = speakerVerificationStatus,
            screenOffAssistantCapability = screenOffCap,
            lockScreenAssistantCapability = lockScreenCap,
            deviceLockCapability = lockCapability,
            deviceUnlockCapability = unlockCapability,
            overallState = overall
        )
    }

    fun isAssistantRoleAvailable(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) ?: false
        } else {
            // Pre-Q: Assistant role existence corresponds to voice interaction settings activity resolution
            val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            val matches = context.packageManager.queryIntentActivities(intent, 0)
            matches.isNotEmpty()
        }
    }

    fun isAssistantRoleHeld(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) ?: false
        } else {
            val currentAssistant = Settings.Secure.getString(context.contentResolver, "assistant")
            val currentVoiceService = Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
            val pkg = context.packageName
            (currentAssistant != null && currentAssistant.startsWith(pkg)) ||
                (currentVoiceService != null && currentVoiceService.startsWith(pkg))
        }
    }

    fun isVoiceInteractionServiceDeclared(context: Context): Boolean {
        return try {
            val intent = Intent("android.service.voice.VoiceInteractionService")
            intent.setPackage(context.packageName)
            val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentServices(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentServices(intent, 0)
            }
            services.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    fun isSpeechRecognitionAvailable(context: Context): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (_: Exception) {
            false
        }
    }

    fun determineHardwareHotwordCapability(
        voiceInteractionServiceBound: Boolean,
        roleHeld: Boolean
    ): HardwareHotwordCapabilityStatus {
        return when {
            !voiceInteractionServiceBound ->
                HardwareHotwordCapabilityStatus.UNSUPPORTED_NO_PRIVILEGED_VOICE_INTERACTION_SERVICE
            !roleHeld ->
                HardwareHotwordCapabilityStatus.UNSUPPORTED_NO_PRIVILEGED_VOICE_INTERACTION_SERVICE
            else ->
                HardwareHotwordCapabilityStatus.UNSUPPORTED_NO_DSP_HARDWARE
        }
    }

    /**
     * Deterministic overall capability state derivation from real capability flags.
     * Never claims FULLY_SUPPORTED merely because explicit speech recognition works.
     */
    fun deriveOverallCapabilityState(
        roleAvailable: Boolean,
        roleHeld: Boolean,
        serviceDeclared: Boolean,
        serviceBound: Boolean,
        speechAvailable: Boolean,
        hotwordCap: HardwareHotwordCapabilityStatus,
        enrollmentStatus: VoiceEnrollmentStatus = VoiceEnrollmentStatus.VOICE_VERIFICATION_NOT_IMPLEMENTED
    ): OverallWakeWordCapabilityState {
        return when {
            serviceBound &&
                roleHeld &&
                hotwordCap == HardwareHotwordCapabilityStatus.UNVERIFIED_REQUIRES_OS_ENROLLMENT &&
                enrollmentStatus == VoiceEnrollmentStatus.ENROLLED ->
                OverallWakeWordCapabilityState.FULLY_SUPPORTED

            serviceDeclared && !serviceBound ->
                OverallWakeWordCapabilityState.ASSISTANT_ROLE_REQUIRED

            !serviceDeclared ->
                OverallWakeWordCapabilityState.VOICE_INTERACTION_UNAVAILABLE

            hotwordCap == HardwareHotwordCapabilityStatus.UNSUPPORTED_NO_PRIVILEGED_VOICE_INTERACTION_SERVICE ||
                hotwordCap == HardwareHotwordCapabilityStatus.UNSUPPORTED_NO_DSP_HARDWARE ->
                if (speechAvailable) {
                    OverallWakeWordCapabilityState.EXPLICIT_SPEECH_ONLY
                } else {
                    OverallWakeWordCapabilityState.HARDWARE_HOTWORD_UNAVAILABLE
                }

            enrollmentStatus == VoiceEnrollmentStatus.VOICE_VERIFICATION_NOT_IMPLEMENTED ->
                OverallWakeWordCapabilityState.NOT_IMPLEMENTED

            speechAvailable ->
                OverallWakeWordCapabilityState.EXPLICIT_SPEECH_ONLY

            else ->
                OverallWakeWordCapabilityState.DEVICE_OR_OS_LIMITATION
        }
    }
}
