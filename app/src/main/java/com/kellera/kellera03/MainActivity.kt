package com.kellera.kellera03

import com.kellera.kellera03.accessibility.KelleraAccessibilityService
import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.kellera.kellera03.launcher.AppLauncher
import com.kellera.kellera03.overlay.AssistivePanelService
import com.kellera.kellera03.overlay.OverlayPermissionManager
import com.kellera.kellera03.speech.CommandProcessor
import com.kellera.kellera03.voice.VoiceEngine
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        private const val LOG_TAG = "KELLERA_MAIN"
    }

    private lateinit var voice: VoiceEngine
    private lateinit var appLauncher: AppLauncher
    private lateinit var overlayPermissionManager: OverlayPermissionManager

    private var assistivePanelService: AssistivePanelService? = null
    private var assistivePanelBound = false

    private val commandProcessor = CommandProcessor()

    private var kelleraActive = false
    private var waitingGoogleSearch = false
    private var waitingOverlayPermission = false
    private var waitingRuntimePermissions = false
    private var receiverRegistered = false

    private var screenStatus by mutableStateOf(
        "Aguardando desbloqueio do celular."
    )

    private val panelSpeechResultListener =
        object : AssistivePanelService.SpeechResultListener {

            override fun onSpeechResult(text: String) {
                runOnUiThread {
                    val query = text.trim()

                    Log.d(
                        LOG_TAG,
                        "Texto recebido do painel: $query"
                    )

                    if (query.isBlank()) {
                        screenStatus =
                            "Nenhuma pesquisa foi compreendida."

                        waitingGoogleSearch = true

                        voice.speak(
                            "Não entendi o que deseja pesquisar. Pode repetir?"
                        ) {
                            assistivePanelService?.showPanelForTest()
                        }

                        return@runOnUiThread
                    }

                    waitingGoogleSearch = false
                    screenStatus = "Pesquisando: $query"

                    searchOnGoogle(query)
                }
            }

            override fun onSpeechNoResult() {
                runOnUiThread {
                    screenStatus =
                        "Nenhum texto recebido do Painel Assistivo."

                    Log.d(
                        LOG_TAG,
                        "Painel encerrou sem resultado."
                    )
                }
            }

            override fun onSpeechError(errorCode: Int) {
                runOnUiThread {
                    screenStatus =
                        "Erro na escuta do Painel Assistivo: $errorCode"

                    Log.e(
                        LOG_TAG,
                        "Erro recebido do painel: $errorCode"
                    )
                }
            }
        }

    private val assistivePanelConnection =
        object : ServiceConnection {

            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?
            ) {
                val binder =
                    service as? AssistivePanelService.LocalBinder

                assistivePanelService =
                    binder?.getService()

                assistivePanelBound =
                    assistivePanelService != null

                assistivePanelService
                    ?.setSpeechResultListener(
                        panelSpeechResultListener
                    )

                Log.d(
                    LOG_TAG,
                    "AssistivePanelService conectado."
                )
            }

            override fun onServiceDisconnected(
                name: ComponentName?
            ) {
                assistivePanelService
                    ?.setSpeechResultListener(null)

                assistivePanelService = null
                assistivePanelBound = false

                Log.d(
                    LOG_TAG,
                    "AssistivePanelService desconectado."
                )
            }
        }

    private val runtimePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            waitingRuntimePermissions = false

            val microphoneGranted =
                permissions[Manifest.permission.RECORD_AUDIO] == true ||
                        hasMicrophonePermission()

            if (!microphoneGranted) {
                screenStatus =
                    "A KELLERA precisa do microfone para ouvir."

                voice.speak(
                    "A KELLERA precisa da permissão de microfone " +
                            "para ouvir seus comandos."
                )

                return@registerForActivityResult
            }

            screenStatus = "Microfone autorizado."

            if (!isDeviceLocked()) {
                startKelleraAfterUnlock()
            }
        }

    private val unlockReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                if (
                    intent?.action ==
                    Intent.ACTION_USER_PRESENT
                ) {
                    startKelleraAfterUnlock()
                }
            }
        }

    private val speechLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (!kelleraActive || isDeviceLocked()) {
                return@registerForActivityResult
            }

            val spoken =
                result.data
                    ?.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS
                    )
                    ?.firstOrNull()

            if (spoken == null) {
                screenStatus =
                    "Não entendi. Tentando novamente..."

                voice.speak(
                    "Não entendi. Pode repetir?"
                ) {
                    askNextAction()
                }

                return@registerForActivityResult
            }

            val command =
                commandProcessor.process(spoken)

            val text =
                command.normalizedText

            screenStatus =
                "Comando recebido: $text"

            if (waitingGoogleSearch) {
                waitingGoogleSearch = false
                searchOnGoogle(text)

                return@registerForActivityResult
            }

            when {
                text.contains("google") ||
                        text.contains("chrome") -> {

                    openGoogleWithContext()
                }

                text.contains("youtube") ||
                        text.contains("you tube") -> {

                    openYouTube()
                }

                text.contains("whatsapp") ||
                        text.contains("zap") -> {

                    openWhatsApp()
                }

                text.contains("configuração") ||
                        text.contains("configurações") -> {

                    openSettings()
                }

                text.contains("parar") ||
                        text.contains("cancelar") ||
                        text.contains("encerrar") -> {

                    screenStatus =
                        "KELLERA em pausa."

                    assistivePanelService?.hidePanel()
                    assistivePanelService
                        ?.endForegroundSession()

                    voice.speak(
                        "Tudo bem. Vou ficar em pausa."
                    )
                }

                else -> {
                    screenStatus =
                        "Aguardando novo comando..."

                    voice.speak(
                        "Você disse: $text"
                    ) {
                        askNextAction()
                    }
                }
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        voice = VoiceEngine(this)
        appLauncher = AppLauncher(this)

        overlayPermissionManager =
            OverlayPermissionManager(this)

        registerReceiver(
            unlockReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT)
        )

        receiverRegistered = true

        bindAssistivePanelService()

        setContent {
            HomeScreen(screenStatus)
        }
    }

    override fun onResume() {
        super.onResume()

        if (!waitingOverlayPermission) {
            return
        }

        if (isDeviceLocked()) {
            screenStatus =
                "Aguardando desbloqueio do celular."

            return
        }

        if (overlayPermissionManager.hasPermission()) {
            waitingOverlayPermission = false

            screenStatus =
                "Painel Assistivo autorizado."

            startKelleraAfterUnlock()
        } else {
            screenStatus =
                "Autorize a KELLERA a aparecer " +
                        "sobre outros aplicativos."
        }
    }

    private fun bindAssistivePanelService() {
        val serviceIntent =
            Intent(
                this,
                AssistivePanelService::class.java
            )

        bindService(
            serviceIntent,
            assistivePanelConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun startKelleraAfterUnlock() {
        if (kelleraActive) return
        if (isDeviceLocked()) return

        if (!hasMicrophonePermission()) {
            requestRuntimePermissions()
            return
        }

        if (!overlayPermissionManager.hasPermission()) {
            requestOverlayPermission()
            return
        }

        activateKelleraAfterUnlock()
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRuntimePermissions() {
        if (waitingRuntimePermissions) return

        waitingRuntimePermissions = true

        screenStatus =
            "Autorize o microfone da KELLERA."

        val permissions =
            mutableListOf(
                Manifest.permission.RECORD_AUDIO
            )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            permissions.add(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        runtimePermissionLauncher.launch(
            permissions.toTypedArray()
        )
    }

    private fun requestOverlayPermission() {
        if (waitingOverlayPermission) return

        waitingOverlayPermission = true

        screenStatus =
            "Autorize o Painel Assistivo da KELLERA."

        startActivity(
            overlayPermissionManager
                .createPermissionIntent()
        )
    }

    private fun activateKelleraAfterUnlock() {
        if (kelleraActive) return
        if (isDeviceLocked()) return
        if (!hasMicrophonePermission()) return

        if (!overlayPermissionManager.hasPermission()) {
            return
        }

        kelleraActive = true
        screenStatus = "Celular desbloqueado."

        voice.speak(
            "Celular desbloqueado. " +
                    "O que deseja fazer agora?"
        ) {
            askNextAction()
        }
    }

    private fun startAssistiveForegroundService() {
        val serviceIntent =
            Intent(
                this,
                AssistivePanelService::class.java
            ).apply {
                action =
                    AssistivePanelService
                        .ACTION_START_FOREGROUND
            }

        ContextCompat.startForegroundService(
            this,
            serviceIntent
        )
    }

    private fun openGoogleWithContext() {
        val panelService =
            assistivePanelService

        if (panelService == null) {
            screenStatus =
                "Painel Assistivo ainda não está pronto."

            voice.speak(
                "O Painel Assistivo ainda não está pronto. " +
                        "Pode tentar novamente?"
            ) {
                askNextAction()
            }

            return
        }

        startAssistiveForegroundService()

        screenStatus = "Abrindo Google..."

        panelService.hidePanel()

        voice.speak("Abrindo Google.") {
            val opened =
                appLauncher.openGoogle()

            Handler(
                Looper.getMainLooper()
            ).postDelayed({

                if (opened) {
                    screenStatus =
                        "Google aberto. " +
                                "Barra de pesquisa disponível."

                    waitingGoogleSearch = true

                    voice.speak(
                        "Google aberto. " +
                                "Estamos na tela inicial do Google. " +
                                "A barra de pesquisa está disponível. " +
                                "O que você deseja pesquisar?"
                    ) {
                        panelService.showPanelForTest()
                    }

                } else {
                    screenStatus =
                        "Google não encontrado."

                    panelService.endForegroundSession()

                    voice.speak(
                        "Não consegui abrir o Google. " +
                                "O que deseja fazer agora?"
                    ) {
                        askNextAction()
                    }
                }
            }, 1500)
        }
    }

    private fun searchOnGoogle(
        query: String
    ) {
        val normalizedQuery = query.trim()

        if (normalizedQuery.isBlank()) {
            screenStatus =
                "Nenhuma pesquisa foi compreendida."

            waitingGoogleSearch = true

            voice.speak(
                "Não entendi o que deseja pesquisar. Pode repetir?"
            ) {
                assistivePanelService?.showPanelForTest()
            }

            return
        }

        assistivePanelService?.hidePanel()
        assistivePanelService
            ?.endForegroundSession()

        screenStatus =
            "Pesquisando: $normalizedQuery"

        voice.speak(
            "Pesquisando $normalizedQuery no Google."
        ) {
            val requestAccepted =
                KelleraAccessibilityService
                    .requestGoogleSearch(normalizedQuery)

            if (requestAccepted) {
                screenStatus =
                    "Pesquisa enviada para o Google: $normalizedQuery"

                Log.d(
                    LOG_TAG,
                    "Pesquisa encaminhada ao AccessibilityService: " +
                            normalizedQuery
                )
            } else {
                screenStatus =
                    "Serviço de acessibilidade indisponível."

                waitingGoogleSearch = true

                Log.e(
                    LOG_TAG,
                    "Não foi possível encaminhar a pesquisa. " +
                            "AccessibilityService desconectado."
                )

                voice.speak(
                    "O serviço de acessibilidade da KELLERA " +
                            "não está disponível. Verifique se ele está ativado."
                )
            }
        }
    }

    private fun openYouTube() {
        screenStatus =
            "Abrindo YouTube..."

        voice.speak(
            "Abrindo YouTube."
        ) {
            val opened =
                appLauncher.openYouTube()

            Handler(
                Looper.getMainLooper()
            ).postDelayed({

                if (opened) {
                    voice.speak(
                        "YouTube aberto. " +
                                "O que deseja fazer agora?"
                    ) {
                        askNextAction()
                    }
                } else {
                    voice.speak(
                        "Não consegui abrir o YouTube. " +
                                "O que deseja fazer agora?"
                    ) {
                        askNextAction()
                    }
                }
            }, 1200)
        }
    }

    private fun openWhatsApp() {
        screenStatus =
            "Abrindo WhatsApp..."

        voice.speak(
            "Abrindo WhatsApp."
        ) {
            val opened =
                appLauncher.openWhatsApp()

            Handler(
                Looper.getMainLooper()
            ).postDelayed({

                if (opened) {
                    voice.speak(
                        "WhatsApp aberto. " +
                                "O que deseja fazer agora?"
                    ) {
                        askNextAction()
                    }
                } else {
                    voice.speak(
                        "Não consegui abrir o WhatsApp. " +
                                "O que deseja fazer agora?"
                    ) {
                        askNextAction()
                    }
                }
            }, 1200)
        }
    }

    private fun openSettings() {
        screenStatus =
            "Abrindo configurações..."

        voice.speak(
            "Abrindo configurações."
        ) {
            val opened =
                appLauncher.openSettings()

            Handler(
                Looper.getMainLooper()
            ).postDelayed({

                if (opened) {
                    voice.speak(
                        "Configurações abertas. " +
                                "O que deseja fazer agora?"
                    ) {
                        askNextAction()
                    }
                } else {
                    voice.speak(
                        "Não consegui abrir as configurações. " +
                                "O que deseja fazer agora?"
                    ) {
                        askNextAction()
                    }
                }
            }, 1200)
        }
    }

    private fun askNextAction() {
        if (!kelleraActive || isDeviceLocked()) {
            return
        }

        screenStatus = "Escutando..."
        openSpeech()
    }

    private fun isDeviceLocked(): Boolean {
        val keyguardManager =
            getSystemService(
                Context.KEYGUARD_SERVICE
            ) as KeyguardManager

        return keyguardManager.isKeyguardLocked
    }

    private fun openSpeech() {
        if (!kelleraActive || isDeviceLocked()) {
            return
        }

        val intent =
            Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            )

        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )

        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE,
            Locale("pt", "BR")
        )

        intent.putExtra(
            RecognizerIntent.EXTRA_PROMPT,
            "Fale com KELLERA"
        )

        speechLauncher.launch(intent)
    }

    override fun onDestroy() {
        assistivePanelService
            ?.setSpeechResultListener(null)

        assistivePanelService?.hidePanel()
        assistivePanelService
            ?.endForegroundSession()

        if (assistivePanelBound) {
            unbindService(
                assistivePanelConnection
            )

            assistivePanelBound = false
            assistivePanelService = null
        }

        if (receiverRegistered) {
            unregisterReceiver(unlockReceiver)
            receiverRegistered = false
        }

        voice.shutdown()

        super.onDestroy()
    }
}

@Composable
fun HomeScreen(
    status: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "KELLERA\n\n$status",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}