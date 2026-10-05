package com.example.nova.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.nova.core.LiveVoiceSessionState
import com.example.nova.security.PermissionAuditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * M10 — Live Conversational Voice Session Coordinator.
 *
 * Rules:
 * - 0% mock data, 0% simulated voice transcripts, 0% fake microphone activity.
 * - The microphone remains available and active for the entire live session without
 *   automatically terminating after one sentence like keyboard dictation.
 * - Automatically re-arms listening after Arya finishes speaking its response.
 * - Supports real barge-in (stops TTS/audio playback and switches immediately to listening).
 * - Manages Android AudioFocus, releasing it cleanly upon session completion.
 * - Coordinates with AryaLiveVoiceService so the session can continue legitimately in background.
 */
class LiveVoiceSessionCoordinator(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val voiceSessionController: VoiceSessionController,
    private val elevenLabsVoiceService: ElevenLabsVoiceService,
    private val onUserUtteranceSubmitted: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _sessionState = MutableStateFlow(LiveVoiceSessionState.LIVE_SESSION_IDLE)
    val sessionState: StateFlow<LiveVoiceSessionState> = _sessionState.asStateFlow()

    private var isSessionActive = false
    private var isMuted = false
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isSessionActive) {
                    pauseLiveSession()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (isSessionActive && _sessionState.value == LiveVoiceSessionState.LIVE_SESSION_PAUSED) {
                    resumeLiveSession()
                }
            }
        }
    }

    fun startLiveSession(): Boolean {
        if (!PermissionAuditor.isRecordAudioGranted(appContext)) {
            _sessionState.value = LiveVoiceSessionState.LIVE_SESSION_ERROR
            return false
        }
        if (!PermissionAuditor.hasMicrophoneHardware(appContext) ||
            !PermissionAuditor.isSpeechRecognitionAvailable(appContext)
        ) {
            _sessionState.value = LiveVoiceSessionState.LIVE_SESSION_UNAVAILABLE
            return false
        }

        isSessionActive = true
        isMuted = false
        _sessionState.value = LiveVoiceSessionState.LIVE_SESSION_STARTING

        requestAudioFocus()
        runCatching { AryaLiveVoiceService.start(appContext) }

        mainHandler.postDelayed({
            if (isSessionActive) {
                beginListeningTurn()
            }
        }, 150)
        return true
    }

    fun beginListeningTurn() {
        if (!isSessionActive || isMuted) return
        stopAnySpeaking()
        _sessionState.value = LiveVoiceSessionState.LIVE_SESSION_LISTENING
        voiceSessionController.startListening()
    }

    /**
     * Called when a final transcript is recognized by the speech recognition engine.
     */
    fun onTranscriptReceived(transcript: String) {
        if (!isSessionActive) return

        val trimmed = transcript.trim()
        if (trimmed.isBlank()) {
            // Re-arm listening if no speech was detected
            _sessionState.value = LiveVoiceSessionState.LIVE_SESSION_WAITING_FOR_USER
            mainHandler.postDelayed({
                if (isSessionActive && _sessionState.value == LiveVoiceSessionState.LIVE_SESSION_WAITING_FOR_USER) {
                    beginListeningTurn()
                }
            }, 300)
            return
        }

        // Check for verbal exit commands
        val lower = trimmed.lowercase()
        if (lower == "stop live session" || lower == "end live session" ||
            lower == "exit session" || lower == "goodbye arya" || lower == "bye arya"
        ) {
            endLiveSession()
            return
        }

        _sessionState.value = LiveVoiceSessionState.LIVE_SESSION_PROCESSING
        onUserUtteranceSubmitted(trimmed)
    }

    /**
     * Called when speech recognition encounters a recoverable timeout or no-match error during live session.
     */
    fun onRecognitionRecoverableError() {
        if (!isSessionActive) return
        // Do not kill the session! Re-arm so user can speak naturally
        _sessionState.value = LiveVoiceSessionState.LIVE_SESSION_WAITING_FOR_USER
        mainHandler.postDelayed({
            if (isSessionActive && _sessionState.value == LiveVoiceSessionState.LIVE_SESSION_WAITING_FOR_USER) {
                beginListeningTurn()
            }
        }, 400)
    }

    /**
     * Speaks the assistant's response through ElevenLabs (or Android TTS fallback).
     * Once speech playback is finished, seamlessly transitions back to LISTENING.
     */
    fun speakResponse(text: String, voiceId: String = "21m00Tcm4TlvDq8ikWAM", spokenResponsesEnabled: Boolean) {
        if (!spokenResponsesEnabled || text.isBlank()) {
            if (isSessionActive) {
                mainHandler.postDelayed({
                    if (isSessionActive) beginListeningTurn()
                }, 400)
            }
            return
        }

        _sessionState.value = LiveVoiceSessionState.LIVE_SESSION_SPEAKING

        elevenLabsVoiceService.speakWithFallback(
            text = text,
            voiceId = voiceId,
            onDone = {
                handleSpeechPlaybackFinished()
            },
            onFallbackNeeded = { _ ->
                // Fallback to real Android TextToSpeech engine
                voiceSessionController.speak(text, enabledInSettings = true)
                // When TTS is speaking, we wait until it transitions back to idle
                monitorTtsPlaybackDone()
            }
        )
    }

    private fun monitorTtsPlaybackDone() {
        coroutineScope.launch(Dispatchers.Main) {
            // Check periodic completion of TTS
            var checks = 0
            while (isSessionActive && checks < 80) { // Max ~8 seconds
                kotlinx.coroutines.delay(200)
                checks++
                if (voiceSessionController.voiceState.value !is VoiceEngineState.Speaking) {
                    break
                }
            }
            if (isSessionActive && _sessionState.value == LiveVoiceSessionState.LIVE_SESSION_SPEAKING) {
                handleSpeechPlaybackFinished()
            }
        }
    }

    private fun handleSpeechPlaybackFinished() {
        mainHandler.post {
            if (isSessionActive && !isMuted) {
                _sessionState.value = LiveVoiceSessionState.LIVE_SESSION_WAITING_FOR_USER
                mainHandler.postDelayed({
                    if (isSessionActive && !isMuted) {
                        beginListeningTurn()
                    }
                }, 250)
            }
        }
    }

    /**
     * Real barge-in: Stops speech playback immediately and starts listening.
     */
    fun bargeIn() {
        if (!isSessionActive) return
        stopAnySpeaking()
        beginListeningTurn()
    }

    fun toggleMuteMicrophone(): Boolean {
        if (!isSessionActive) return false
        isMuted = !isMuted
        if (isMuted) {
            voiceSessionController.stopListening()
            _sessionState.value = LiveVoiceSessionState.LIVE_SESSION_PAUSED
        } else {
            beginListeningTurn()
        }
        return isMuted
    }

    fun isMicrophoneMuted(): Boolean = isMuted

    fun pauseLiveSession() {
        if (!isSessionActive) return
        voiceSessionController.stopListening()
        stopAnySpeaking()
        _sessionState.value = LiveVoiceSessionState.LIVE_SESSION_PAUSED
    }

    fun resumeLiveSession() {
        if (!isSessionActive) return
        isMuted = false
        beginListeningTurn()
    }

    fun endLiveSession() {
        isSessionActive = false
        isMuted = false
        _sessionState.value = LiveVoiceSessionState.LIVE_SESSION_STOPPING

        voiceSessionController.stopListening()
        stopAnySpeaking()
        abandonAudioFocus()

        runCatching { AryaLiveVoiceService.stop(appContext) }

        mainHandler.postDelayed({
            _sessionState.value = LiveVoiceSessionState.LIVE_SESSION_ENDED
            _sessionState.value = LiveVoiceSessionState.LIVE_SESSION_IDLE
        }, 150)
    }

    private fun stopAnySpeaking() {
        elevenLabsVoiceService.stopPlayback()
        voiceSessionController.stopSpeaking()
    }

    private fun requestAudioFocus() {
        if (audioManager == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }
    }

    private fun abandonAudioFocus() {
        if (audioManager == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    fun isLiveSessionActive(): Boolean = isSessionActive

    fun shutdown() {
        endLiveSession()
        elevenLabsVoiceService.shutdown()
    }
}
