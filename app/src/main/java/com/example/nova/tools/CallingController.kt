package com.example.nova.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.PhoneNumberUtils
import androidx.core.content.ContextCompat

sealed class CallingOutcome {
    data class DialerOpened(val phoneNumber: String, val message: String) : CallingOutcome()
    data class CallInitiated(val phoneNumber: String, val message: String) : CallingOutcome()
    data class PermissionRequiredFallbackToDialer(val phoneNumber: String, val message: String) : CallingOutcome()
    data class BlockedEmergencyNumber(val phoneNumber: String, val reason: String) : CallingOutcome()
    data class InvalidNumber(val reason: String) : CallingOutcome()
    data class Error(val errorDescription: String) : CallingOutcome()
}

/**
 * M10 — Real Android Phone Calling & Dialer Controller.
 *
 * Rules:
 * - 0% fake call completion or simulated calling.
 * - ACTION_DIAL opens the system dialer without requiring CALL_PHONE permission.
 * - ACTION_CALL initiates direct calling ONLY when CALL_PHONE permission is actively granted.
 * - Automated dialing of emergency numbers (911, 112, etc.) is strictly blocked for device safety;
 *   routes to manual system dialer.
 * - Never claims a phone call succeeded when the system intent fails.
 */
object CallingController {

    private val commonEmergencyNumbers = setOf("911", "112", "999", "000", "100", "101", "102")

    fun isCallPhonePermissionGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun sanitizePhoneNumber(raw: String): String {
        return raw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
    }

    @Suppress("DEPRECATION")
    fun isEmergencyNumber(number: String): Boolean {
        val sanitized = sanitizePhoneNumber(number)
        if (commonEmergencyNumbers.contains(sanitized)) return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                PhoneNumberUtils.isEmergencyNumber(sanitized)
            } else {
                @Suppress("DEPRECATION")
                PhoneNumberUtils.isEmergencyNumber(sanitized)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Opens the system dialer with [rawNumber] pre-populated.
     * Does not require CALL_PHONE runtime permission.
     */
    fun openDialer(context: Context, rawNumber: String): CallingOutcome {
        val sanitized = sanitizePhoneNumber(rawNumber)
        if (sanitized.isBlank()) {
            return CallingOutcome.InvalidNumber("Phone number cannot be blank.")
        }
        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$sanitized")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            CallingOutcome.DialerOpened(
                phoneNumber = sanitized,
                message = "Opened system dialer for $sanitized."
            )
        } catch (e: Exception) {
            CallingOutcome.Error("Failed to open system dialer: ${e.message}")
        }
    }

    /**
     * Initiates a direct phone call using ACTION_CALL if CALL_PHONE permission is granted,
     * or safely falls back to ACTION_DIAL if permission is missing.
     */
    fun initiateCall(context: Context, rawNumber: String): CallingOutcome {
        val sanitized = sanitizePhoneNumber(rawNumber)
        if (sanitized.isBlank()) {
            return CallingOutcome.InvalidNumber("Phone number cannot be blank.")
        }

        if (isEmergencyNumber(sanitized)) {
            // For life safety, open the dialer so user can confirm and talk with dispatch
            openDialer(context, sanitized)
            return CallingOutcome.BlockedEmergencyNumber(
                phoneNumber = sanitized,
                reason = "Emergency numbers must be dialed manually through the system dialer for safety."
            )
        }

        if (!isCallPhonePermissionGranted(context)) {
            openDialer(context, sanitized)
            return CallingOutcome.PermissionRequiredFallbackToDialer(
                phoneNumber = sanitized,
                message = "CALL_PHONE permission is required for direct calling. Opened the system dialer with $sanitized instead."
            )
        }

        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$sanitized")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            CallingOutcome.CallInitiated(
                phoneNumber = sanitized,
                message = "Dispatched direct call to $sanitized via Android telephony."
            )
        } catch (e: Exception) {
            // Fallback to dialer if ACTION_CALL is restricted by device/OEM policy
            openDialer(context, sanitized)
            CallingOutcome.Error("Direct calling failed (${e.message}). Opened system dialer instead.")
        }
    }
}
