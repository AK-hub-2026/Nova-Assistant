package com.example.nova.core

import android.os.SystemClock

/**
 * M11-C — Real internal latency instrumentation using monotonic Android timing.
 * Measures time-to-first-token and time-to-first-audio across the full conversational pipeline:
 * SpeechRecognition -> Transcript -> Router -> Local/Cloud Decision -> AI Provider -> TTS -> Playback.
 *
 * Rules:
 * - 0% fake or fabricated latency measurements.
 * - Monotonic elapsedRealtime() timestamps ensure intervals are immune to system clock adjustments.
 * - Timings are diagnostic only and never exposed as developer clutter on the main assistant screen.
 */
data class LatencySnapshot(
    val speechRecognitionStartMonoMs: Long = 0L,
    val transcriptAvailableMonoMs: Long = 0L,
    val commandRoutingStartMonoMs: Long = 0L,
    val commandRoutingEndMonoMs: Long = 0L,
    val aiRequestStartMonoMs: Long = 0L,
    val firstAiTokenMonoMs: Long = 0L,
    val completeAiResponseMonoMs: Long = 0L,
    val ttsStartMonoMs: Long = 0L,
    val firstAudiblePlaybackMonoMs: Long = 0L,
    val totalResponseCompletionMonoMs: Long = 0L,
    val providerUsed: String = "",
    val executionRoute: String = ""
) {
    val speechToTranscriptMs: Long
        get() = if (speechRecognitionStartMonoMs > 0 && transcriptAvailableMonoMs >= speechRecognitionStartMonoMs) {
            transcriptAvailableMonoMs - speechRecognitionStartMonoMs
        } else -1L

    val routingDurationMs: Long
        get() = if (commandRoutingStartMonoMs > 0 && commandRoutingEndMonoMs >= commandRoutingStartMonoMs) {
            commandRoutingEndMonoMs - commandRoutingStartMonoMs
        } else -1L

    val aiTimeToFirstTokenMs: Long
        get() = if (aiRequestStartMonoMs > 0 && firstAiTokenMonoMs >= aiRequestStartMonoMs) {
            firstAiTokenMonoMs - aiRequestStartMonoMs
        } else -1L

    val totalAiDurationMs: Long
        get() = if (aiRequestStartMonoMs > 0 && completeAiResponseMonoMs >= aiRequestStartMonoMs) {
            completeAiResponseMonoMs - aiRequestStartMonoMs
        } else -1L

    val ttsSynthesisDurationMs: Long
        get() = if (ttsStartMonoMs > 0 && totalResponseCompletionMonoMs >= ttsStartMonoMs) {
            totalResponseCompletionMonoMs - ttsStartMonoMs
        } else -1L

    val totalTurnaroundMs: Long
        get() = if (transcriptAvailableMonoMs > 0 && totalResponseCompletionMonoMs >= transcriptAvailableMonoMs) {
            totalResponseCompletionMonoMs - transcriptAvailableMonoMs
        } else -1L
}

object LatencyTracker {
    @Volatile
    private var currentSnapshot = LatencySnapshot()

    internal var timeProvider: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.nanoTime() / 1_000_000L
        }
    }

    private fun nowMonoMs(): Long = timeProvider()

    fun onSpeechRecognitionStart() {
        currentSnapshot = LatencySnapshot(speechRecognitionStartMonoMs = nowMonoMs())
    }

    fun onTranscriptAvailable() {
        currentSnapshot = currentSnapshot.copy(transcriptAvailableMonoMs = nowMonoMs())
    }

    fun onRoutingStart() {
        currentSnapshot = currentSnapshot.copy(commandRoutingStartMonoMs = nowMonoMs())
    }

    fun onRoutingEnd(route: String) {
        currentSnapshot = currentSnapshot.copy(
            commandRoutingEndMonoMs = nowMonoMs(),
            executionRoute = route
        )
    }

    fun onAiRequestStart(provider: String) {
        currentSnapshot = currentSnapshot.copy(
            aiRequestStartMonoMs = nowMonoMs(),
            providerUsed = provider
        )
    }

    fun onFirstAiToken() {
        if (currentSnapshot.firstAiTokenMonoMs == 0L) {
            currentSnapshot = currentSnapshot.copy(firstAiTokenMonoMs = nowMonoMs())
        }
    }

    fun onCompleteAiResponse() {
        currentSnapshot = currentSnapshot.copy(completeAiResponseMonoMs = nowMonoMs())
    }

    fun onTtsStart() {
        currentSnapshot = currentSnapshot.copy(ttsStartMonoMs = nowMonoMs())
    }

    fun onFirstAudiblePlayback() {
        if (currentSnapshot.firstAudiblePlaybackMonoMs == 0L) {
            currentSnapshot = currentSnapshot.copy(firstAudiblePlaybackMonoMs = nowMonoMs())
        }
    }

    fun onTotalResponseComplete() {
        currentSnapshot = currentSnapshot.copy(totalResponseCompletionMonoMs = nowMonoMs())
    }

    fun getLatestSnapshot(): LatencySnapshot = currentSnapshot

    fun reset() {
        currentSnapshot = LatencySnapshot()
    }
}
