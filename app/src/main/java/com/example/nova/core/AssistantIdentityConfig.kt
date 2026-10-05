package com.example.nova.core

/**
 * M9 — Single Source of Truth for Assistant Identity.
 *
 * Rules:
 * - Default name: "Arya", Display Name: "आर्या", Default Wake Phrase: "Hey Arya".
 * - "Arya" is the DEFAULT name, not a permanently hardcoded identity.
 * - Supports changing display and custom names via Settings without rewriting the core.
 * - Changing the display name does NOT automatically implement hardware hotword support for it.
 * - Explicitly distinguishes the user display name from the wake phrase actually supported by the active system.
 */
enum class AssistantNameValidationState {
    VALID,
    EMPTY,
    TOO_SHORT,
    TOO_LONG,
    INVALID_CHARACTERS;

    val isValid: Boolean get() = this == VALID
}

data class AssistantIdentityConfig(
    val assistantName: String = DEFAULT_NAME,
    val displayName: String = DEFAULT_DISPLAY_NAME,
    val defaultWakePhrase: String = DEFAULT_WAKE_PHRASE,
    val customName: String? = null,
    val isCustomNameSupported: Boolean = true
) {
    val effectiveAssistantName: String
        get() = customName?.trim()?.takeIf { it.isNotBlank() } ?: assistantName

    val effectiveDisplayName: String
        get() = if (customName.isNullOrBlank()) displayName else customName.trim()

    /**
     * Wake phrase supported by the active wake-word system.
     * Hardware or fixed wake-word systems typically support only the default trained phrase ("Hey Arya").
     */
    val supportedWakePhrase: String
        get() = DEFAULT_WAKE_PHRASE

    /**
     * Whether the active/custom assistant name matches the wake phrase actually supported by the hotword engine.
     */
    val isWakeWordSupportedForActiveName: Boolean
        get() = customName.isNullOrBlank() || customName.trim().equals(DEFAULT_NAME, ignoreCase = true)

    val supportedWakePhraseDetail: String
        get() = if (isWakeWordSupportedForActiveName) {
            "Wake Phrase: \"$supportedWakePhrase\" (Supported by active wake-word profile)"
        } else {
            "Wake Phrase: \"$supportedWakePhrase\" (Custom name \"$effectiveAssistantName\" does NOT have hardware hotword enrollment; wake detection remains mapped to \"$supportedWakePhrase\")"
        }

    companion object {
        const val DEFAULT_NAME = "Arya"
        const val DEFAULT_DISPLAY_NAME = "आर्या"
        const val DEFAULT_WAKE_PHRASE = "Hey Arya"

        const val MIN_NAME_LENGTH = 2
        const val MAX_NAME_LENGTH = 24

        fun validateCustomName(candidate: String): AssistantNameValidationState {
            val trimmed = candidate.trim()
            return when {
                trimmed.isEmpty() -> AssistantNameValidationState.EMPTY
                trimmed.length < MIN_NAME_LENGTH -> AssistantNameValidationState.TOO_SHORT
                trimmed.length > MAX_NAME_LENGTH -> AssistantNameValidationState.TOO_LONG
                !trimmed.all { it.isLetter() || it.isWhitespace() } -> AssistantNameValidationState.INVALID_CHARACTERS
                else -> AssistantNameValidationState.VALID
            }
        }

        fun deriveWakePhraseForName(name: String): String {
            val clean = name.trim()
            return if (clean.isBlank()) DEFAULT_WAKE_PHRASE else "Hey $clean"
        }
    }
}
