package com.example.nova.accessibility

import com.example.nova.core.ScreenUnderstandingFallbackStatus

/**
 * M6 Requirement L — Clean extension point for a future screenshot/OCR/vision fallback.
 *
 * In M6, AccessibilityService semantic hierarchy traversal is the primary and exclusive
 * mechanism for screen understanding. Full screenshot/OCR pixel vision is NOT implemented
 * in M6 to avoid low-end device RAM/CPU overhead.
 *
 * This extension point is never invoked automatically during semantic screen reads or actions.
 */
interface ScreenUnderstandingFallback {
    val status: ScreenUnderstandingFallbackStatus

    fun inspectScreenFallback(): ScreenUnderstandingFallbackOutcome

    companion object {
        fun queryFallbackStatus(): ScreenUnderstandingFallbackResult {
            return when (val outcome = DefaultScreenUnderstandingFallback.inspectScreenFallback()) {
                is ScreenUnderstandingFallbackOutcome.NotImplemented ->
                    ScreenUnderstandingFallbackResult(
                        succeeded = false,
                        status = outcome.status,
                        diagnosticMessage = outcome.message
                    )
                is ScreenUnderstandingFallbackOutcome.Unavailable ->
                    ScreenUnderstandingFallbackResult(
                        succeeded = false,
                        status = outcome.status,
                        diagnosticMessage = outcome.message
                    )
            }
        }
    }
}

data class ScreenUnderstandingFallbackResult(
    val succeeded: Boolean,
    val status: ScreenUnderstandingFallbackStatus,
    val diagnosticMessage: String
)

sealed class ScreenUnderstandingFallbackOutcome {
    data class NotImplemented(
        val status: ScreenUnderstandingFallbackStatus = ScreenUnderstandingFallbackStatus.NOT_IMPLEMENTED,
        val message: String = "Screenshot/OCR vision fallback is not implemented in M6 (status: NOT_IMPLEMENTED). Nova uses real Android AccessibilityService semantics as the primary screen interaction mechanism."
    ) : ScreenUnderstandingFallbackOutcome()

    data class Unavailable(
        val status: ScreenUnderstandingFallbackStatus = ScreenUnderstandingFallbackStatus.UNAVAILABLE,
        val message: String
    ) : ScreenUnderstandingFallbackOutcome()
}

/**
 * Default M6 implementation reporting truthful [ScreenUnderstandingFallbackStatus.NOT_IMPLEMENTED].
 */
object DefaultScreenUnderstandingFallback : ScreenUnderstandingFallback {
    override val status: ScreenUnderstandingFallbackStatus =
        ScreenUnderstandingFallbackStatus.NOT_IMPLEMENTED

    override fun inspectScreenFallback(): ScreenUnderstandingFallbackOutcome {
        return ScreenUnderstandingFallbackOutcome.NotImplemented()
    }
}
