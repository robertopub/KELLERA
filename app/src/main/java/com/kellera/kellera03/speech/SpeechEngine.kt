package com.kellera.kellera03.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class SpeechEngine(
    context: Context,
    private val onListeningStarted: () -> Unit,
    private val onResult: (String) -> Unit,
    private val onNoResult: () -> Unit,
    private val onError: (Int) -> Unit
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isDestroyed = false

    init {
        mainHandler.post {
            createRecognizer()
        }
    }

    private fun createRecognizer() {
        if (isDestroyed) return
        if (speechRecognizer != null) return

        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onError(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        speechRecognizer =
            SpeechRecognizer.createSpeechRecognizer(appContext).apply {

                setRecognitionListener(
                    object : RecognitionListener {

                        override fun onReadyForSpeech(params: Bundle?) {
                            isListening = true
                            onListeningStarted()
                        }

                        override fun onBeginningOfSpeech() = Unit

                        override fun onRmsChanged(rmsdB: Float) = Unit

                        override fun onBufferReceived(buffer: ByteArray?) = Unit

                        override fun onEndOfSpeech() = Unit

                        override fun onResults(results: Bundle?) {
                            isListening = false

                            val spokenText = results
                                ?.getStringArrayList(
                                    SpeechRecognizer.RESULTS_RECOGNITION
                                )
                                ?.firstOrNull()
                                ?.trim()

                            if (spokenText.isNullOrBlank()) {
                                onNoResult()
                            } else {
                                onResult(spokenText)
                            }
                        }

                        override fun onPartialResults(
                            partialResults: Bundle?
                        ) = Unit

                        override fun onError(error: Int) {
                            isListening = false

                            when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH -> {
                                    onNoResult()
                                }

                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                                    /*
                                     * Mantemos o código do timeout para que
                                     * o serviço diferencie silêncio de uma
                                     * fala que não foi compreendida.
                                     */
                                    onError(error)
                                }

                                else -> {
                                    onError(error)
                                }
                            }
                        }

                        override fun onEvent(
                            eventType: Int,
                            params: Bundle?
                        ) = Unit
                    }
                )
            }
    }

    fun startListening() {
        mainHandler.post {
            if (isDestroyed) return@post
            if (isListening) return@post

            if (speechRecognizer == null) {
                createRecognizer()
            }

            val recognizer = speechRecognizer ?: run {
                onError(SpeechRecognizer.ERROR_CLIENT)
                return@post
            }

            val recognitionIntent =
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE,
                        Locale("pt", "BR").toLanguageTag()
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                        false
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_MAX_RESULTS,
                        1
                    )

                    /*
                     * Aumenta o tempo tolerado de silêncio depois
                     * que o usuário começa a falar.
                     */
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        3_000L
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        2_000L
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        2_000L
                    )
                }

            recognizer.startListening(recognitionIntent)
        }
    }

    fun stopListening() {
        mainHandler.post {
            if (!isListening || isDestroyed) return@post

            speechRecognizer?.stopListening()
        }
    }

    fun cancel() {
        mainHandler.post {
            if (isDestroyed) return@post

            speechRecognizer?.cancel()
            isListening = false
        }
    }

    fun destroy() {
        mainHandler.post {
            if (isDestroyed) return@post

            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null

            isListening = false
            isDestroyed = true
        }
    }
}