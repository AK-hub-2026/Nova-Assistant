package com.example.nova.voice

import android.content.Context
import com.example.nova.security.PermissionAuditor
import com.example.nova.security.SpeakerVerificationStatus
import com.example.nova.security.VoiceEnrollmentStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * M9 — Personal Voice Enrollment and Speaker Verification Controller.
 *
 * Rules:
 * - 0% fake voice embeddings or random hashes.
 * - Explains voice verification is an additional security layer and cannot provide an absolute guarantee.
 * - When an on-device biometric voice model is not installed/bundled, truthfully reports
 *   VOICE_VERIFICATION_NOT_IMPLEMENTED rather than faking success.
 * - Never stores raw voice recordings permanently.
 * - Voice verification is NOT equivalent to Android device authentication (cannot bypass Keyguard).
 */
class VoiceEnrollmentController(
    private val context: Context
) {
    private val _enrollmentStatus = MutableStateFlow(VoiceEnrollmentStatus.VOICE_VERIFICATION_NOT_IMPLEMENTED)
    val enrollmentStatus: StateFlow<VoiceEnrollmentStatus> = _enrollmentStatus.asStateFlow()

    private val _speakerVerificationStatus = MutableStateFlow(SpeakerVerificationStatus.VOICE_VERIFICATION_UNAVAILABLE)
    val speakerVerificationStatus: StateFlow<SpeakerVerificationStatus> = _speakerVerificationStatus.asStateFlow()

    private val _enrollmentMessage = MutableStateFlow(
        "On-device biometric speaker verification is not bundled in this build. Voice recognition operates in explicit push-to-talk mode."
    )
    val enrollmentMessage: StateFlow<String> = _enrollmentMessage.asStateFlow()

    val securityDisclosureNotice: String =
        "Voice verification is an additional convenience layer and cannot provide an absolute guarantee that an unauthorized voice or recording will never be accepted. Voice verification does not replace Android PIN, password, pattern, or biometric device authentication."

    val enrollmentPrompts: List<String> = listOf(
        "Hey Arya",
        "Arya, what's my battery level?",
        "Arya, open settings"
    )

    fun startEnrollment(): Boolean {
        if (!PermissionAuditor.isRecordAudioGranted(context)) {
            _enrollmentStatus.value = VoiceEnrollmentStatus.ENROLLMENT_FAILED_NO_MIC
            _enrollmentMessage.value = "Microphone permission (RECORD_AUDIO) is required for voice enrollment."
            return false
        }
        if (!PermissionAuditor.hasMicrophoneHardware(context)) {
            _enrollmentStatus.value = VoiceEnrollmentStatus.ENROLLMENT_FAILED_NO_MIC
            _enrollmentMessage.value = "No microphone hardware detected on this device."
            return false
        }

        // Truthful: Voice verification model is not implemented in this build
        _enrollmentStatus.value = VoiceEnrollmentStatus.VOICE_VERIFICATION_NOT_IMPLEMENTED
        _speakerVerificationStatus.value = SpeakerVerificationStatus.VOICE_VERIFICATION_UNAVAILABLE
        _enrollmentMessage.value = "Voice enrollment is not supported on this device/build (VOICE_VERIFICATION_NOT_IMPLEMENTED). No fake biometric embeddings will be created."
        return false
    }

    fun resetEnrollment() {
        _enrollmentStatus.value = VoiceEnrollmentStatus.VOICE_VERIFICATION_NOT_IMPLEMENTED
        _speakerVerificationStatus.value = SpeakerVerificationStatus.VOICE_VERIFICATION_UNAVAILABLE
        _enrollmentMessage.value = "Voice profile reset. Voice recognition operates in push-to-talk mode."
    }
}
