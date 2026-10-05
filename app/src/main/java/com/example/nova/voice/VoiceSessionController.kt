package com.example.nova.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.nova.security.PermissionAuditor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

sealed class VoiceEngineState {
    data object Idle : VoiceEngineState()
    data class Listening(val partialTranscript: String) : VoiceEngineState()
    data class Speaking(val utterance: String) : VoiceEngineState()
    data object PermissionRequired : VoiceEngineState()
    data class Unavailable(val reason: String) : VoiceEngineState()
    data class Error(val errorDescription: String) : VoiceEngineState()
}

/**
 * Real Android SpeechRecognizer (STT) and TextToSpeech (TTS) controller.
 * - Never fabricates voice transcripts or audio levels.
 * - Exposes real microphone RMS dB from RecognitionListener.onRmsChanged when available, or null when unavailable.
 * - Supports immediate barge-in (stopping TTS when the user starts listening).
 * - Cleans up binder resources when destroyed to protect low-RAM devices.
 */
class VoiceSessionController(
    context: Context,
    private val onFinalTranscriptRecognized: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val _isTtsReadyFlow = MutableStateFlow(false)
    val isTtsReadyFlow: StateFlow<Boolean> = _isTtsReadyFlow.asStateFlow()

    private val _ttsErrorFlow = MutableStateFlow<String?>(null)
    val ttsErrorFlow: StateFlow<String?> = _ttsErrorFlow.asStateFlow()

    private val _voiceState = MutableStateFlow<VoiceEngineState>(VoiceEngineState.Idle)
    val voiceState: StateFlow<VoiceEngineState> = _voiceState.asStateFlow()

    private val _liveRmsDb = MutableStateFlow<Float?>(null)
    val liveRmsDb: StateFlow<Float?> = _liveRmsDb.asStateFlow()

    init {
        initTextToSpeech()
    }

    private fun initTextToSpeech() {
        runCatching {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            com.example.nova.core.LatencyTracker.onFirstAudiblePlayback()
                        }
                        override fun onDone(utteranceId: String?) {
                            com.example.nova.core.LatencyTracker.onTotalResponseComplete()
                            if (_voiceState.value is VoiceEngineState.Speaking) {
                                _voiceState.value = VoiceEngineState.Idle
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            com.example.nova.core.LatencyTracker.onTotalResponseComplete()
                            _ttsErrorFlow.value = "Android TextToSpeech utterance playback error."
                            if (_voiceState.value is VoiceEngineState.Speaking) {
                                _voiceState.value = VoiceEngineState.Idle
                            }
                        }
                    })
                    isTtsReady = true
                    _isTtsReadyFlow.value = true
                    _ttsErrorFlow.value = null
                } else {
                    isTtsReady = false
                    _isTtsReadyFlow.value = false
                    _ttsErrorFlow.value = "Android TextToSpeech engine failed to initialize (code $status)."
                }
            }
        }.onFailure { e ->
            isTtsReady = false
            _isTtsReadyFlow.value = false
            _ttsErrorFlow.value = "Android TextToSpeech unavailable: ${e.message ?: "initialization error"}"
        }
    }

    fun startListening() {
        stopSpeaking()
        _liveRmsDb.value = null

        if (!PermissionAuditor.isRecordAudioGranted(appContext)) {
            _voiceState.value = VoiceEngineState.PermissionRequired
            return
        }
        if (!PermissionAuditor.isSpeechRecognitionAvailable(appContext)) {
            _voiceState.value = VoiceEngineState.Unavailable(
                "Android SpeechRecognizer service is not installed or enabled on this device."
            )
            return
        }

        com.example.nova.core.LatencyTracker.onSpeechRecognitionStart()
        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                        setRecognitionListener(recognitionListener)
                    }
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                _voiceState.value = VoiceEngineState.Listening("")
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                _liveRmsDb.value = null
                _voiceState.value = VoiceEngineState.Error(
                    "Failed to start SpeechRecognizer: ${e.message}"
                )
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            runCatching { speechRecognizer?.stopListening() }
            _liveRmsDb.value = null
            if (_voiceState.value is VoiceEngineState.Listening) {
                _voiceState.value = VoiceEngineState.Idle
            }
        }
    }

    fun speak(text: String, enabledInSettings: Boolean) {
        if (!enabledInSettings || text.isBlank() || !isTtsReady) return
        com.example.nova.core.LatencyTracker.onTtsStart()
        mainHandler.post {
            val utteranceId = UUID.randomUUID().toString()
            _liveRmsDb.value = null
            _voiceState.value = VoiceEngineState.Speaking(text)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    fun stopSpeaking() {
        mainHandler.post {
            runCatching {
                if (tts?.isSpeaking == true) {
                    tts?.stop()
                }
            }
            if (_voiceState.value is VoiceEngineState.Speaking) {
                _voiceState.value = VoiceEngineState.Idle
            }
        }
    }

    fun clearVoiceErrorOrUnavailableState() {
        _liveRmsDb.value = null
        val current = _voiceState.value
        if (current is VoiceEngineState.Error ||
            current is VoiceEngineState.Unavailable ||
            current is VoiceEngineState.PermissionRequired
        ) {
            _voiceState.value = VoiceEngineState.Idle
        }
    }

    fun shutdown() {
        mainHandler.post {
            _liveRmsDb.value = null
            runCatching {
                speechRecognizer?.destroy()
                speechRecognizer = null
            }
            runCatching {
                tts?.stop()
                tts?.shutdown()
                tts = null
            }
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _voiceState.value = VoiceEngineState.Listening("")
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            if (_voiceState.value is VoiceEngineState.Listening && !rmsdB.isNaN() && !rmsdB.isInfinite()) {
                _liveRmsDb.value = rmsdB
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            _liveRmsDb.value = null
        }

        override fun onError(error: Int) {
            _liveRmsDb.value = null
            val description = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Speech client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                SpeechRecognizer.ERROR_NETWORK -> "Network error during speech recognition"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized — please try speaking again"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
                SpeechRecognizer.ERROR_SERVER -> "Speech recognition server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
                else -> "Speech recognition error (code $error)"
            }
            _voiceState.value = VoiceEngineState.Error(description)
        }

        override fun onResults(results: Bundle?) {
            _liveRmsDb.value = null
            com.example.nova.core.LatencyTracker.onTranscriptAvailable()
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val transcript = matches?.firstOrNull()?.trim().orEmpty()
            _voiceState.value = VoiceEngineState.Idle
            if (transcript.isNotBlank()) {
                onFinalTranscriptRecognized(transcript)
            } else {
                _voiceState.value = VoiceEngineState.Error("Empty speech transcript received.")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (partial.isNotBlank()) {
                _voiceState.value = VoiceEngineState.Listening(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
