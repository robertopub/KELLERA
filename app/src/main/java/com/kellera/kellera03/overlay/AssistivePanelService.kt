package com.kellera.kellera03.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.kellera.kellera03.MainActivity
import com.kellera.kellera03.speech.SpeechEngine
import com.kellera.kellera03.voice.VoiceEngine
import kotlin.math.roundToInt

class AssistivePanelService : Service() {

    interface SpeechResultListener {
        fun onSpeechResult(text: String)
        fun onSpeechNoResult()
        fun onSpeechError(errorCode: Int)
    }

    companion object {
        const val ACTION_START_FOREGROUND =
            "com.kellera.kellera03.action.START_ASSISTIVE_PANEL_SERVICE"

        const val ACTION_STOP_FOREGROUND =
            "com.kellera.kellera03.action.STOP_ASSISTIVE_PANEL_SERVICE"

        private const val NOTIFICATION_CHANNEL_ID =
            "kellera_assistive_panel_channel"

        private const val NOTIFICATION_CHANNEL_NAME =
            "Painel Assistivo KELLERA"

        private const val NOTIFICATION_ID = 1203
        private const val LOG_TAG = "KELLERA_SPEECH"

        private const val MAX_LISTENING_ATTEMPTS = 3
        private const val RESTART_DELAY_MS = 600L
    }

    inner class LocalBinder : Binder() {
        fun getService(): AssistivePanelService {
            return this@AssistivePanelService
        }
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var speechEngine: SpeechEngine
    private lateinit var voiceEngine: VoiceEngine

    private var speechResultListener: SpeechResultListener? = null

    private var panelView: View? = null
    private var mainMessageTextView: TextView? = null
    private var statusTextView: TextView? = null

    private var foregroundSessionActive = false
    private var listeningAttempt = 0
    private var sessionFinished = false

    override fun onCreate() {
        super.onCreate()

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        voiceEngine = VoiceEngine(this)

        createNotificationChannel()
        createSpeechEngine()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START_FOREGROUND -> {
                beginForegroundSession()
            }

            ACTION_STOP_FOREGROUND -> {
                finishSession()
            }
        }

        return START_NOT_STICKY
    }

    fun setSpeechResultListener(
        listener: SpeechResultListener?
    ) {
        speechResultListener = listener
    }

    private fun createSpeechEngine() {
        speechEngine = SpeechEngine(
            context = this,

            onListeningStarted = {
                mainHandler.post {
                    updateMainMessage("Pode falar...")
                    updatePanelStatus("Ouvindo...")

                    Log.d(
                        LOG_TAG,
                        "Escuta iniciada. Tentativa: $listeningAttempt"
                    )
                }
            },

            onResult = { spokenText ->
                mainHandler.post {
                    if (sessionFinished) {
                        return@post
                    }

                    sessionFinished = true

                    Log.d(
                        LOG_TAG,
                        "Texto recebido: $spokenText"
                    )

                    updateMainMessage("Comando recebido")
                    updatePanelStatus(spokenText)

                    speechResultListener
                        ?.onSpeechResult(spokenText)

                    mainHandler.postDelayed({
                        finishListeningOnly()
                    }, 900)
                }
            },

            onNoResult = {
                mainHandler.post {
                    if (sessionFinished) {
                        return@post
                    }

                    Log.d(
                        LOG_TAG,
                        "Fala não compreendida."
                    )

                    handleNotUnderstood()
                }
            },

            onError = { errorCode ->
                mainHandler.post {
                    if (sessionFinished) {
                        return@post
                    }

                    Log.e(
                        LOG_TAG,
                        "Erro de reconhecimento: $errorCode"
                    )

                    when (errorCode) {
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            handleSilenceTimeout()
                        }

                        else -> {
                            handleRecognitionError(errorCode)
                        }
                    }
                }
            }
        )
    }

    private fun handleSilenceTimeout() {
        if (listeningAttempt >= MAX_LISTENING_ATTEMPTS) {
            finishAfterMaximumAttempts()
            return
        }

        updateMainMessage("Você está por aí?")
        updatePanelStatus("O que deseja pesquisar?")

        voiceEngine.speak(
            "Você está por aí? O que deseja pesquisar?"
        ) {
            restartListening()
        }
    }

    private fun handleNotUnderstood() {
        if (listeningAttempt >= MAX_LISTENING_ATTEMPTS) {
            speechResultListener?.onSpeechNoResult()
            finishAfterMaximumAttempts()
            return
        }

        updateMainMessage("Não entendi")
        updatePanelStatus("Pode repetir?")

        /*
         * A escuta já terminou antes da fala da KELLERA,
         * evitando que ela reconheça a própria voz.
         */
        voiceEngine.speak(
            "Não entendi. Pode repetir?"
        ) {
            restartListening()
        }
    }

    private fun handleRecognitionError(
        errorCode: Int
    ) {
        if (listeningAttempt >= MAX_LISTENING_ATTEMPTS) {
            speechResultListener
                ?.onSpeechError(errorCode)

            finishAfterMaximumAttempts()
            return
        }

        updateMainMessage("Não consegui ouvir")
        updatePanelStatus("Vamos tentar novamente")

        voiceEngine.speak(
            "Não consegui ouvir corretamente. Pode repetir?"
        ) {
            restartListening()
        }
    }

    private fun restartListening() {
        if (sessionFinished) return

        mainHandler.postDelayed({
            startListeningAttempt()
        }, RESTART_DELAY_MS)
    }

    private fun startListeningAttempt() {
        if (sessionFinished) return

        listeningAttempt += 1

        updateMainMessage("Pode falar...")
        updatePanelStatus(
            "Tentativa $listeningAttempt de $MAX_LISTENING_ATTEMPTS"
        )

        speechEngine.startListening()
    }

    private fun finishAfterMaximumAttempts() {
        if (sessionFinished) return

        sessionFinished = true

        updateMainMessage("Não consegui entender")
        updatePanelStatus("Encerrando escuta")

        voiceEngine.speak(
            "Não consegui entender desta vez."
        ) {
            mainHandler.postDelayed({
                finishListeningOnly()
            }, 500)
        }
    }

    fun beginForegroundSession() {
        if (foregroundSessionActive) return

        val openKelleraIntent =
            Intent(this, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openKelleraIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

        val notification =
            NotificationCompat.Builder(
                this,
                NOTIFICATION_CHANNEL_ID
            )
                .setSmallIcon(
                    android.R.drawable.ic_btn_speak_now
                )
                .setContentTitle(
                    "KELLERA está ativa"
                )
                .setContentText(
                    "O Painel Assistivo está preparado para ouvir."
                )
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(
                    NotificationCompat.PRIORITY_LOW
                )
                .setCategory(
                    NotificationCompat.CATEGORY_SERVICE
                )
                .build()

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }

        foregroundSessionActive = true
    }

    fun showPanelForTest() {
        sessionFinished = false
        listeningAttempt = 0

        showPanel()

        mainHandler.postDelayed({
            startListeningAttempt()
        }, 700)
    }

    fun showPanel() {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(
                LOG_TAG,
                "Permissão de overlay ausente."
            )
            return
        }

        if (panelView != null) {
            return
        }

        val displayMetrics =
            resources.displayMetrics

        val panelWidth =
            (
                    displayMetrics.widthPixels *
                            0.88f
                    ).roundToInt()

        val panelHeight =
            (
                    displayMetrics.heightPixels *
                            0.38f
                    ).roundToInt()

        val panel = createPanelView()

        val layoutParams =
            WindowManager.LayoutParams(
                panelWidth,
                panelHeight,
                WindowManager.LayoutParams
                    .TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams
                            .FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams
                            .FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

        try {
            windowManager.addView(
                panel,
                layoutParams
            )

            panelView = panel

        } catch (exception: Exception) {
            Log.e(
                LOG_TAG,
                "Falha ao exibir painel.",
                exception
            )

            panelView = null
            mainMessageTextView = null
            statusTextView = null
        }
    }

    fun hidePanel() {
        val currentPanel =
            panelView ?: return

        try {
            windowManager.removeView(
                currentPanel
            )
        } catch (exception: Exception) {
            Log.e(
                LOG_TAG,
                "Falha ao remover painel.",
                exception
            )
        }

        panelView = null
        mainMessageTextView = null
        statusTextView = null
    }

    fun endForegroundSession() {
        if (!foregroundSessionActive) return

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )

        foregroundSessionActive = false
    }

    private fun finishListeningOnly() {
        speechEngine.cancel()
        hidePanel()
    }

    private fun finishSession() {
        sessionFinished = true

        speechEngine.cancel()
        hidePanel()
        endForegroundSession()

        stopSelf()
    }

    private fun updateMainMessage(
        message: String
    ) {
        mainMessageTextView?.text = message
    }

    private fun updatePanelStatus(
        status: String
    ) {
        statusTextView?.text = status
    }

    private fun createNotificationChannel() {
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.O
        ) {
            return
        }

        val notificationManager =
            getSystemService(
                NOTIFICATION_SERVICE
            ) as NotificationManager

        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Indica quando o Painel Assistivo da KELLERA está ativo."

                setShowBadge(false)
            }

        notificationManager
            .createNotificationChannel(channel)
    }

    private fun createPanelView(): View {
        val panelBackground =
            GradientDrawable().apply {
                shape =
                    GradientDrawable.RECTANGLE

                cornerRadius =
                    dpToPx(28f)

                setColor(
                    Color.rgb(
                        15,
                        23,
                        42
                    )
                )

                setStroke(
                    dpToPx(3f).roundToInt(),
                    Color.WHITE
                )
            }

        val container =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER

                background =
                    panelBackground

                setPadding(
                    dpToPx(24f).roundToInt(),
                    dpToPx(20f).roundToInt(),
                    dpToPx(24f).roundToInt(),
                    dpToPx(20f).roundToInt()
                )

                contentDescription =
                    "Painel Assistivo da KELLERA. Pode falar."
            }

        val microphoneText =
            TextView(this).apply {
                text = "🎤"
                textSize = 62f
                gravity = Gravity.CENTER

                contentDescription =
                    "Microfone"
            }

        val titleText =
            TextView(this).apply {
                text = "KELLERA"
                textSize = 32f

                setTextColor(
                    Color.WHITE
                )

                gravity =
                    Gravity.CENTER

                setTypeface(
                    typeface,
                    android.graphics.Typeface.BOLD
                )
            }

        val mainMessageText =
            TextView(this).apply {
                text = "Pode falar..."
                textSize = 30f

                setTextColor(
                    Color.WHITE
                )

                gravity =
                    Gravity.CENTER

                setPadding(
                    0,
                    dpToPx(14f).roundToInt(),
                    0,
                    0
                )
            }

        val statusText =
            TextView(this).apply {
                text =
                    "Preparando escuta..."

                textSize = 21f

                setTextColor(
                    Color.LTGRAY
                )

                gravity =
                    Gravity.CENTER

                setPadding(
                    0,
                    dpToPx(10f).roundToInt(),
                    0,
                    0
                )
            }

        mainMessageTextView =
            mainMessageText

        statusTextView =
            statusText

        container.addView(
            microphoneText
        )

        container.addView(
            titleText
        )

        container.addView(
            mainMessageText
        )

        container.addView(
            statusText
        )

        return container
    }

    private fun dpToPx(
        dp: Float
    ): Float {
        return dp *
                resources.displayMetrics.density
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(
            null
        )

        sessionFinished = true
        speechResultListener = null

        speechEngine.destroy()
        voiceEngine.shutdown()

        hidePanel()
        endForegroundSession()

        super.onDestroy()
    }
}