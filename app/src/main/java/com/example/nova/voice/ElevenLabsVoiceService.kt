package com.example.nova.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.example.nova.core.VoiceCapabilityStatus
import com.example.nova.security.ProviderSecretVault
import com.example.nova.security.SensitiveDataRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed class ElevenLabsSpeechResult {
    data object Success : ElevenLabsSpeechResult()
    data class FallbackToAndroidTts(val reason: String) : ElevenLabsSpeechResult()
    data class Error(val message: String) : ElevenLabsSpeechResult()
}

/**
 * M10 — Real ElevenLabs Voice API integration with truthful status reporting,
 * secure secret management, audio playback, and automatic Android TextToSpeech fallback.
 *
 * Rules:
 * - 0% fake audio generation or simulated voice synthesis.
 * - API keys are retrieved from Android Keystore-backed ProviderSecretVault or BuildConfig.
 * - API keys are NEVER logged or exposed in plaintext.
 * - When ElevenLabs API key is missing or request fails, truthfully reports fallback to Android TTS.
 * - Cleans up MediaPlayer and temporary cache files immediately upon completion or cancellation.
 */
class ElevenLabsVoiceService(
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
    private val appContext = context.applicationContext

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false

    private val _status = MutableStateFlow(VoiceCapabilityStatus.CONFIGURATION_REQUIRED)
    val status: StateFlow<VoiceCapabilityStatus> = _status.asStateFlow()

    private val _statusDetail = MutableStateFlow("ElevenLabs API key required. Android TextToSpeech is active as real fallback.")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    private val _isFallbackActive = MutableStateFlow(true)
    val isFallbackActive: StateFlow<Boolean> = _isFallbackActive.asStateFlow()

    init {
        refreshConfigurationStatus()
    }

    fun refreshConfigurationStatus() {
        val configured = ProviderSecretVault.isElevenLabsConfigured(appContext)
        if (configured) {
            _status.value = VoiceCapabilityStatus.AVAILABLE
            _statusDetail.value = "ElevenLabs Voice API configured and ready."
            _isFallbackActive.value = false
        } else {
            _status.value = VoiceCapabilityStatus.CONFIGURATION_REQUIRED
            _statusDetail.value = "ElevenLabs API key not configured. Using Android TextToSpeech fallback."
            _isFallbackActive.value = true
        }
    }

    /**
     * Synthesizes [text] using ElevenLabs REST API if configured, playing audio via MediaPlayer.
     * Calls [onDone] when audio playback finishes, or [onFallbackNeeded] if ElevenLabs cannot be used.
     */
    fun speakWithFallback(
        text: String,
        voiceId: String = "21m00Tcm4TlvDq8ikWAM", // Default: Rachel
        onDone: () -> Unit,
        onFallbackNeeded: (reason: String) -> Unit
    ) {
        val apiKey = ProviderSecretVault.getElevenLabsSecretForTransport(appContext)
        if (apiKey.isNullOrBlank()) {
            _isFallbackActive.value = true
            _statusDetail.value = "ElevenLabs API key not configured. Using Android TextToSpeech fallback."
            onFallbackNeeded("ElevenLabs API key not configured")
            return
        }

        com.example.nova.core.LatencyTracker.onTtsStart()
        stopPlayback()

        serviceScope.launch {
            try {
                val endpoint = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId"
                val jsonPayload = JSONObject().apply {
                    put("text", text)
                    put("model_id", "eleven_turbo_v2_5")
                    put("voice_settings", JSONObject().apply {
                        put("stability", 0.5)
                        put("similarity_boost", 0.8)
                    })
                }.toString()

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("xi-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "audio/mpeg")
                    .post(jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute()
                }

                if (!response.isSuccessful) {
                    val code = response.code
                    val rawBody = response.body?.string().orEmpty()
                    val sanitizedError = SensitiveDataRedactor.redactText(rawBody).redactedText
                    _status.value = VoiceCapabilityStatus.ERROR
                    _statusDetail.value = "ElevenLabs request failed (HTTP $code): ${sanitizedError.take(120)}"
                    _isFallbackActive.value = true
                    withContext(Dispatchers.Main) {
                        onFallbackNeeded("HTTP $code: $sanitizedError")
                    }
                    return@launch
                }

                val responseBytes = withContext(Dispatchers.IO) {
                    response.body?.bytes()
                }

                if (responseBytes == null || responseBytes.isEmpty()) {
                    _status.value = VoiceCapabilityStatus.ERROR
                    _statusDetail.value = "ElevenLabs returned empty audio payload."
                    _isFallbackActive.value = true
                    withContext(Dispatchers.Main) {
                        onFallbackNeeded("Empty audio response")
                    }
                    return@launch
                }

                val tempFile = withContext(Dispatchers.IO) {
                    val file = File(appContext.cacheDir, "elevenlabs_temp_utterance.mp3")
                    FileOutputStream(file).use { it.write(responseBytes) }
                    file
                }

                _status.value = VoiceCapabilityStatus.AVAILABLE
                _statusDetail.value = "Audio synthesized via ElevenLabs (${responseBytes.size} bytes)."
                _isFallbackActive.value = false

                withContext(Dispatchers.Main) {
                    playAudioFile(tempFile, onDone)
                }

            } catch (e: Exception) {
                val sanitizedMsg = SensitiveDataRedactor.redactText(e.message ?: "Unknown error").redactedText
                _status.value = VoiceCapabilityStatus.ERROR
                _statusDetail.value = "ElevenLabs connection failed: $sanitizedMsg"
                _isFallbackActive.value = true
                withContext(Dispatchers.Main) {
                    onFallbackNeeded(sanitizedMsg)
                }
            }
        }
    }

    private fun playAudioFile(file: File, onDone: () -> Unit) {
        try {
            stopPlayback()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    com.example.nova.core.LatencyTracker.onTotalResponseComplete()
                    this@ElevenLabsVoiceService.isPlaying = false
                    stopPlayback()
                    runCatching { file.delete() }
                    onDone()
                }
                setOnErrorListener { _, what, extra ->
                    com.example.nova.core.LatencyTracker.onTotalResponseComplete()
                    this@ElevenLabsVoiceService.isPlaying = false
                    stopPlayback()
                    runCatching { file.delete() }
                    _statusDetail.value = "MediaPlayer playback error (what: $what, extra: $extra)"
                    onDone()
                    true
                }
                prepare()
                start()
                com.example.nova.core.LatencyTracker.onFirstAudiblePlayback()
                this@ElevenLabsVoiceService.isPlaying = true
            }
        } catch (e: Exception) {
            this@ElevenLabsVoiceService.isPlaying = false
            stopPlayback()
            runCatching { file.delete() }
            _statusDetail.value = "MediaPlayer failed to initialize: ${e.message}"
            onDone()
        }
    }

    fun stopPlayback() {
        runCatching {
            if (mediaPlayer != null) {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.stop()
                }
                mediaPlayer?.release()
                mediaPlayer = null
            }
        }
        isPlaying = false
    }

    fun isCurrentlyPlaying(): Boolean = isPlaying

    fun shutdown() {
        stopPlayback()
    }
}
