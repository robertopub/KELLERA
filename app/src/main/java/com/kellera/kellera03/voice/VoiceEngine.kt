package com.kellera.kellera03.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class VoiceEngine(
    context: Context
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var textToSpeech: TextToSpeech? = null
    private var isReady = false

    private val callbacks = mutableMapOf<String, () -> Unit>()

    init {
        textToSpeech = TextToSpeech(
            context.applicationContext
        ) { status ->

            if (status == TextToSpeech.SUCCESS) {
                val languageResult =
                    textToSpeech?.setLanguage(Locale("pt", "BR"))

                isReady =
                    languageResult != TextToSpeech.LANG_MISSING_DATA &&
                            languageResult != TextToSpeech.LANG_NOT_SUPPORTED

                configureListener()
            }
        }
    }

    private fun configureListener() {
        textToSpeech?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {

                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    executeCallback(utteranceId)
                }

                override fun onError(utteranceId: String?) {
                    executeCallback(utteranceId)
                }
            }
        )
    }

    fun speak(message: String) {
        if (message.isBlank()) return
        if (!isReady) return

        val utteranceId =
            "KELLERA_SIMPLE_${UUID.randomUUID()}"

        mainHandler.post {
            textToSpeech?.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
            )
        }
    }

    fun speak(
        message: String,
        onFinished: () -> Unit
    ) {
        if (message.isBlank()) {
            mainHandler.post {
                onFinished()
            }
            return
        }

        if (!isReady) {
            return
        }

        val utteranceId =
            "KELLERA_CALLBACK_${UUID.randomUUID()}"

        callbacks[utteranceId] = onFinished

        mainHandler.post {
            textToSpeech?.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
            )
        }
    }

    private fun executeCallback(utteranceId: String?) {
        if (utteranceId == null) return

        val callback = callbacks.remove(utteranceId)
            ?: return

        mainHandler.post {
            callback.invoke()
        }
    }

    fun shutdown() {
        callbacks.clear()

        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null

        isReady = false
    }
}