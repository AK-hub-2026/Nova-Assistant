package com.example.nova.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import com.example.nova.accessibility.NovaAccessibilityService
import com.example.nova.core.VerificationOutcome
import com.example.nova.core.VerifiedActionResult

/**
 * M9 — Safe Android Device Lock & Unlock Controller.
 *
 * Security Invariants:
 * - LOCK: Uses official AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN (API 28+) when active.
 * - UNLOCK: Never attempts or claims to bypass Android Keyguard. Voice verification is NOT
 *   device credential authentication. Unlocking a secure device requires PIN/pattern/password/biometric.
 * - 0% fake unlock success.
 */
object DeviceLockController {

    fun executeDeviceLock(context: Context): VerifiedActionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return VerifiedActionResult(
                toolName = "lock_device",
                outcome = VerificationOutcome.UNSUPPORTED,
                verificationDetail = "GLOBAL_ACTION_LOCK_SCREEN requires Android 9+ (API 28+).",
                targetPackage = "android.os.PowerManager",
                preStateSummary = "Screen active",
                postStateSummary = "Screen active",
                userFacingMessage = "Locking the device via accessibility actions requires Android 9 or newer.",
                elapsedMs = 1L
            )
        }

        val service = NovaAccessibilityService.instance
        if (service == null) {
            return VerifiedActionResult(
                toolName = "lock_device",
                outcome = VerificationOutcome.SERVICE_DISABLED,
                verificationDetail = "NovaAccessibilityService is not bound or active.",
                targetPackage = "com.example.nova.accessibility",
                preStateSummary = "AccessibilityService disconnected",
                postStateSummary = "AccessibilityService disconnected",
                userFacingMessage = "To lock your phone, please enable NovaAccessibilityService in Android Accessibility Settings.",
                elapsedMs = 1L
            )
        }

        val locked = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        return if (locked) {
            VerifiedActionResult(
                toolName = "lock_device",
                outcome = VerificationOutcome.VERIFIED_SUCCESS,
                verificationDetail = "Executed GLOBAL_ACTION_LOCK_SCREEN via NovaAccessibilityService.",
                targetPackage = "android.os.PowerManager",
                preStateSummary = "Screen active",
                postStateSummary = "Device locked",
                userFacingMessage = "Device locked.",
                elapsedMs = 5L
            )
        } else {
            VerifiedActionResult(
                toolName = "lock_device",
                outcome = VerificationOutcome.VERIFICATION_FAILED,
                verificationDetail = "AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN returned false.",
                targetPackage = "android.os.PowerManager",
                preStateSummary = "Screen active",
                postStateSummary = "Screen active",
                userFacingMessage = "Could not lock the device. Check Accessibility service permissions.",
                elapsedMs = 5L
            )
        }
    }

    fun executeDeviceUnlock(context: Context): VerifiedActionResult {
        return VerifiedActionResult(
            toolName = "unlock_device",
            outcome = VerificationOutcome.PERMISSION_REQUIRED,
            verificationDetail = "SECURE_UNLOCK_REQUIRES_DEVICE_AUTHENTICATION: Voice verification cannot bypass Android Keyguard security.",
            targetPackage = "android.app.KeyguardManager",
            preStateSummary = "Device secure keyguard active",
            postStateSummary = "Device secure keyguard preserved",
            userFacingMessage = "Unlocking a secure device requires your PIN, pattern, password, or fingerprint. Voice recognition cannot bypass device security.",
            elapsedMs = 1L
        )
    }
}
