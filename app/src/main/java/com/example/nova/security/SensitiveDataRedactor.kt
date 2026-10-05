package com.example.nova.security

/**
 * Redacts passwords, one-time passcodes (OTPs), payment card numbers, API keys,
 * and private credentials from UI node text and user prompts before any data is sent
 * to an external AI provider.
 */
object SensitiveDataRedactor {

    private val creditCardRegex = Regex("""\b(?:\d[ -]*?){13,19}\b""")
    private val ssnRegex = Regex("""\b\d{3}-\d{2}-\d{4}\b""")
    private val bearerOrApiKeyRegex = Regex("""\b(?:AIza[0-9A-Za-z\-_]{35}|sk-[0-9A-Za-z]{20,}|gsk_[0-9A-Za-z]{20,})\b""")
    private val otpContextRegex = Regex(
        pattern = """(?i)(otp|verification code|security code|passcode|pin\s*code)([^\d]{0,20})(\b\d{4,8}\b)""",
    )
    private val promptInjectionMarkers = listOf(
        "ignore previous instructions",
        "ignore all prior instructions",
        "system override:",
        "disregard safety rules",
        "execute shell command"
    )

    data class RedactionResult(
        val sanitizedText: String,
        val wasRedacted: Boolean
    ) {
        val redactedText: String
            get() = sanitizedText
    }

    fun redactText(rawText: String): RedactionResult {
        return sanitizeNodeText(
            rawText = rawText,
            isPasswordNode = false
        )
    }

    fun sanitizeNodeText(
        rawText: String,
        isPasswordNode: Boolean,
        viewIdResourceName: String = ""
    ): RedactionResult {
        if (rawText.isBlank()) {
            return RedactionResult("", false)
        }
        val lowerViewId = viewIdResourceName.lowercase()
        if (isPasswordNode ||
            lowerViewId.contains("password") ||
            lowerViewId.contains("pin_entry") ||
            lowerViewId.contains("passcode")
        ) {
            return RedactionResult("[REDACTED_PASSWORD_FIELD]", true)
        }

        var working = rawText
        var modified = false

        val configuredSecrets = listOfNotNull(
            runCatching { com.example.BuildConfig.GEMINI_API_KEY }.getOrNull(),
            runCatching { com.example.BuildConfig.GROQ_API_KEY }.getOrNull(),
            runCatching { com.example.BuildConfig.OPENAI_API_KEY }.getOrNull()
        ).filter { PermissionAuditor.isKeyConfigured(it) }

        for (secret in configuredSecrets) {
            val trimmedSecret = secret.trim()
            if (trimmedSecret.isNotEmpty() && working.contains(trimmedSecret)) {
                working = working.replace(trimmedSecret, "[REDACTED_API_KEY]")
                modified = true
            }
        }

        if (creditCardRegex.containsMatchIn(working)) {
            working = creditCardRegex.replace(working, "[REDACTED_CARD_NUMBER]")
            modified = true
        }
        if (ssnRegex.containsMatchIn(working)) {
            working = ssnRegex.replace(working, "[REDACTED_SSN]")
            modified = true
        }
        if (bearerOrApiKeyRegex.containsMatchIn(working)) {
            working = bearerOrApiKeyRegex.replace(working, "[REDACTED_API_KEY]")
            modified = true
        }
        if (otpContextRegex.containsMatchIn(working)) {
            working = otpContextRegex.replace(working) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}[REDACTED_OTP]"
            }
            modified = true
        }

        return RedactionResult(working, modified)
    }

    /**
     * Wraps untrusted external screen or notification content and neutralizes
     * embedded prompt-injection directives so third-party app text cannot hijack Nova.
     */
    fun neutralizeUntrustedScreenText(rawScreenContext: String): String {
        var cleaned = rawScreenContext
        for (marker in promptInjectionMarkers) {
            if (cleaned.contains(marker, ignoreCase = true)) {
                cleaned = cleaned.replace(
                    Regex(Regex.escape(marker), RegexOption.IGNORE_CASE),
                    "[BLOCKED_UNTRUSTED_DIRECTIVE]"
                )
            }
        }
        return cleaned
    }
}
