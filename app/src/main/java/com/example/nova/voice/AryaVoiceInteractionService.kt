package com.example.nova.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * M9 — Real Android VoiceInteractionService for Arya Assistant.
 *
 * Truthfulness rules:
 * - When declared in manifest but not bound by the OS: reports DECLARED_NOT_BOUND.
 * - Never claims ACTIVE, READY, or LISTENING unless the Android OS actually calls onReady().
 * - Cleans up resources immediately onShutdown() or onDestroy().
 */
class AryaVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private val _isServiceBound = MutableStateFlow(false)
        val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

        @Volatile
        var instance: AryaVoiceInteractionService? = null
            private set
    }

    override fun onReady() {
        super.onReady()
        instance = this
        _isServiceBound.value = true
    }

    override fun onShutdown() {
        if (instance == this) {
            instance = null
        }
        _isServiceBound.value = false
        super.onShutdown()
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        _isServiceBound.value = false
        super.onDestroy()
    }
}

class AryaVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AryaVoiceInteractionSession(this)
    }
}

class AryaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context)

class AryaRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // Truthful: explicit speech recognition in Nova is handled via Android system SpeechRecognizer
    }

    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
