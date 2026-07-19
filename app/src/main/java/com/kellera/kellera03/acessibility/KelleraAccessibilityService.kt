package com.kellera.kellera03.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class KelleraAccessibilityService : AccessibilityService() {

    companion object {

        private const val TAG = "KELLERA_ACCESSIBILITY"

        private const val GOOGLE_PACKAGE =
            "com.google.android.googlequicksearchbox"

        /*
         * Campo correto da tela principal do Modo IA do Google.
         *
         * Encontrado durante a inspeção da árvore de acessibilidade:
         *
         * classe: android.widget.EditText
         * texto: "Pergunte o que quiser"
         * editável: true
         * ação disponível: ACTION_SET_TEXT
         */
        private const val GOOGLE_EDIT_TEXT_ID =
            "com.google.android.googlequicksearchbox:id/" +
                    "searchbox_aim_autocomplete_text_input"

        /*
         * Botão Enviar localizado ao lado do campo de pesquisa
         * principal do Modo IA do Google.
         */
        private const val GOOGLE_SEND_BUTTON_ID =
            "com.google.android.googlequicksearchbox:id/" +
                    "searchbox_aim_enter_button"

        private const val MAX_SEARCH_ATTEMPTS = 12
        private const val RETRY_INTERVAL_MS = 500L

        private const val MAX_DEPTH = 12
        private const val MAX_NODES = 250

        @Volatile
        private var connectedService: KelleraAccessibilityService? = null

        @Volatile
        private var pendingQuery: String? = null

        /**
         * Método chamado pela MainActivity quando a fala do usuário
         * já foi reconhecida.
         *
         * O texto fica guardado até que a tela correta do Google
         * esteja disponível.
         */
        fun requestGoogleSearch(query: String): Boolean {
            val normalizedQuery = query.trim()

            if (normalizedQuery.isBlank()) {
                Log.w(
                    TAG,
                    "Pesquisa ignorada porque o texto está vazio."
                )

                return false
            }

            pendingQuery = normalizedQuery

            Log.d(
                TAG,
                "Nova pesquisa recebida: \"$normalizedQuery\""
            )

            val service = connectedService

            if (service == null) {
                Log.w(
                    TAG,
                    "A pesquisa foi guardada, mas o serviço " +
                            "de acessibilidade ainda não está conectado."
                )

                return false
            }

            service.startPendingSearch()

            return true
        }

        /**
         * Permite que a MainActivity verifique se o serviço
         * está conectado ao Android.
         */
        fun isServiceConnected(): Boolean {
            return connectedService != null
        }
    }

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private var searchAttemptCount = 0
    private var textInserted = false
    private var inspectedNodeCount = 0

    private val searchRunnable =
        object : Runnable {

            override fun run() {
                attemptPendingSearch()
            }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()

        connectedService = this

        Log.d(
            TAG,
            "Serviço conectado - versão K05_A1"
        )

        /*
         * Caso a MainActivity tenha enviado uma pesquisa antes
         * de o serviço terminar de conectar, tentamos executá-la
         * agora.
         */
        if (!pendingQuery.isNullOrBlank()) {
            startPendingSearch()
        }
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        if (event == null) return

        val packageName =
            event.packageName
                ?.toString()
                .orEmpty()

        if (packageName != GOOGLE_PACKAGE) {
            return
        }

        Log.d(
            TAG,
            "Google detectado: " +
                    "tipo=${event.eventType}, " +
                    "classe=${event.className}"
        )

        /*
         * Se existe uma pesquisa pendente, cada mudança relevante
         * na interface do Google pode indicar que o campo terminou
         * de carregar.
         */
        if (!pendingQuery.isNullOrBlank()) {
            scheduleSearchAttempt(
                delayMillis = 100L
            )
        }

        /*
         * K04_A5:
         * Inserção automática temporariamente desativada.
         *
         * O primeiro EditText encontrado pertencia à pesquisa
         * do histórico de conversas, e não ao campo principal
         * de pergunta do Modo IA.
         *
         * Este bloco foi mantido como registro da investigação.
         *
         * O campo correto encontrado posteriormente foi:
         *
         * searchbox_aim_autocomplete_text_input
         */

        /*
         * INSPEÇÃO TEMPORÁRIA DA ÁRVORE
         *
         * A inspeção está preservada, mas não é executada
         * automaticamente.
         *
         * Caso o Google altere novamente a interface, descomente
         * apenas a chamada abaixo para analisar os novos nós:
         */

        // inspectGoogleScreen()
    }

    /**
     * Reinicia o controle das tentativas para uma nova pesquisa.
     */
    private fun startPendingSearch() {
        mainHandler.removeCallbacks(searchRunnable)

        searchAttemptCount = 0
        textInserted = false

        scheduleSearchAttempt(
            delayMillis = 100L
        )
    }

    /**
     * Agenda uma tentativa sem criar várias execuções simultâneas.
     */
    private fun scheduleSearchAttempt(
        delayMillis: Long
    ) {
        mainHandler.removeCallbacks(searchRunnable)

        mainHandler.postDelayed(
            searchRunnable,
            delayMillis
        )
    }

    /**
     * Executa uma tentativa completa:
     *
     * 1. Obtém a janela ativa.
     * 2. Localiza o campo correto.
     * 3. Insere o texto.
     * 4. Localiza o botão Enviar.
     * 5. Pressiona Enviar.
     */
    private fun attemptPendingSearch() {
        val query =
            pendingQuery
                ?.trim()
                .orEmpty()

        if (query.isBlank()) {
            Log.d(
                TAG,
                "Nenhuma pesquisa pendente."
            )

            resetSearchState(
                clearPendingQuery = true
            )

            return
        }

        if (searchAttemptCount >= MAX_SEARCH_ATTEMPTS) {
            Log.e(
                TAG,
                "Limite de tentativas atingido. " +
                        "Não foi possível pesquisar: \"$query\""
            )

            resetSearchState(
                clearPendingQuery = false
            )

            return
        }

        searchAttemptCount++

        Log.d(
            TAG,
            "Tentativa $searchAttemptCount de " +
                    "$MAX_SEARCH_ATTEMPTS para pesquisar: \"$query\""
        )

        val rootNode = rootInActiveWindow

        if (rootNode == null) {
            Log.w(
                TAG,
                "Raiz da janela ativa indisponível."
            )

            scheduleNextAttempt()
            return
        }

        val packageName =
            rootNode.packageName
                ?.toString()
                .orEmpty()

        if (packageName != GOOGLE_PACKAGE) {
            Log.d(
                TAG,
                "A janela ativa ainda não pertence ao Google. " +
                        "Pacote atual: $packageName"
            )

            scheduleNextAttempt()
            return
        }

        val editTextNode =
            findGoogleEditText(rootNode)

        if (editTextNode == null) {
            Log.d(
                TAG,
                "Campo principal do Modo IA ainda não encontrado."
            )

            scheduleNextAttempt()
            return
        }

        if (!textInserted) {
            val insertionResult =
                insertTextIntoGoogleField(
                    editTextNode = editTextNode,
                    query = query
                )

            if (!insertionResult) {
                Log.w(
                    TAG,
                    "Não foi possível inserir o texto nesta tentativa."
                )

                scheduleNextAttempt()
                return
            }

            textInserted = true

            /*
             * Pequeno intervalo para o Google atualizar o estado
             * do botão Enviar depois de receber o texto.
             */
            scheduleSearchAttempt(
                delayMillis = 350L
            )

            return
        }

        val sendButtonNode =
            findGoogleSendButton(rootNode)

        if (sendButtonNode == null) {
            Log.d(
                TAG,
                "Botão Enviar ainda não encontrado."
            )

            scheduleNextAttempt()
            return
        }

        val clickResult =
            clickSendButton(sendButtonNode)

        if (clickResult) {
            Log.d(
                TAG,
                "Pesquisa enviada com sucesso: \"$query\""
            )

            resetSearchState(
                clearPendingQuery = true
            )
        } else {
            Log.w(
                TAG,
                "O botão Enviar foi encontrado, " +
                        "mas o clique não foi executado."
            )

            scheduleNextAttempt()
        }
    }

    /**
     * Localiza especificamente o EditText correto do Modo IA.
     *
     * Não usamos mais como primeira opção qualquer EditText
     * encontrado na tela, porque isso poderia selecionar novamente
     * o campo do histórico.
     */
    private fun findGoogleEditText(
        rootNode: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        val nodesById =
            rootNode.findAccessibilityNodeInfosByViewId(
                GOOGLE_EDIT_TEXT_ID
            )

        val nodeByExactId =
            nodesById.firstOrNull { node ->

                val supportsSetText =
                    node.actionList.any { action ->
                        action.id ==
                                AccessibilityNodeInfo.ACTION_SET_TEXT
                    }

                node.isEditable &&
                        supportsSetText &&
                        node.className?.toString() ==
                        "android.widget.EditText"
            }

        if (nodeByExactId != null) {
            Log.d(
                TAG,
                "Campo principal localizado pelo ID exato: " +
                        "${nodeByExactId.viewIdResourceName}"
            )

            return nodeByExactId
        }

        /*
         * Busca de segurança.
         *
         * Ela continua exigindo características específicas do
         * campo principal, para não retornar o EditText do histórico.
         */
        return findMainGoogleEditTextRecursively(
            node = rootNode,
            depth = 0
        )
    }

    private fun findMainGoogleEditTextRecursively(
        node: AccessibilityNodeInfo,
        depth: Int
    ): AccessibilityNodeInfo? {

        if (depth > MAX_DEPTH) {
            return null
        }

        val className =
            node.className
                ?.toString()
                .orEmpty()

        val viewId =
            node.viewIdResourceName
                .orEmpty()

        val visibleText =
            node.text
                ?.toString()
                .orEmpty()

        val supportsSetText =
            node.actionList.any { action ->
                action.id ==
                        AccessibilityNodeInfo.ACTION_SET_TEXT
            }

        val hasCorrectId =
            viewId.endsWith(
                "searchbox_aim_autocomplete_text_input"
            )

        val hasExpectedHint =
            visibleText.contains(
                "Pergunte o que quiser",
                ignoreCase = true
            )

        val isValidMainField =
            className == "android.widget.EditText" &&
                    node.isEditable &&
                    supportsSetText &&
                    (hasCorrectId || hasExpectedHint)

        if (isValidMainField) {
            Log.d(
                TAG,
                "Campo principal localizado pela busca segura: " +
                        "id=$viewId, texto=\"$visibleText\""
            )

            return node
        }

        for (index in 0 until node.childCount) {
            val childNode =
                node.getChild(index)
                    ?: continue

            val result =
                findMainGoogleEditTextRecursively(
                    node = childNode,
                    depth = depth + 1
                )

            if (result != null) {
                return result
            }
        }

        return null
    }

    /**
     * Dá foco ao campo e insere o texto reconhecido pela KELLERA.
     */
    private fun insertTextIntoGoogleField(
        editTextNode: AccessibilityNodeInfo,
        query: String
    ): Boolean {

        Log.d(
            TAG,
            "Campo encontrado | " +
                    "id=${editTextNode.viewIdResourceName} | " +
                    "classe=${editTextNode.className} | " +
                    "editável=${editTextNode.isEditable} | " +
                    "focado=${editTextNode.isFocused}"
        )

        if (!editTextNode.isEditable) {
            Log.w(
                TAG,
                "O campo encontrado não está marcado como editável."
            )

            return false
        }

        val supportsSetText =
            editTextNode.actionList.any { action ->
                action.id ==
                        AccessibilityNodeInfo.ACTION_SET_TEXT
            }

        if (!supportsSetText) {
            Log.w(
                TAG,
                "O campo não oferece ACTION_SET_TEXT."
            )

            return false
        }

        if (!editTextNode.isFocused) {
            val focusResult =
                editTextNode.performAction(
                    AccessibilityNodeInfo.ACTION_FOCUS
                )

            Log.d(
                TAG,
                "Resultado de ACTION_FOCUS = $focusResult"
            )

            if (!focusResult) {
                val clickFocusResult =
                    editTextNode.performAction(
                        AccessibilityNodeInfo.ACTION_CLICK
                    )

                Log.d(
                    TAG,
                    "Resultado do clique para obter foco = " +
                            "$clickFocusResult"
                )
            }
        }

        val arguments =
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo
                        .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    query
                )
            }

        val setTextResult =
            editTextNode.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                arguments
            )

        Log.d(
            TAG,
            "Resultado de ACTION_SET_TEXT = $setTextResult"
        )

        if (setTextResult) {
            Log.d(
                TAG,
                "Texto inserido com sucesso: \"$query\""
            )
        } else {
            Log.e(
                TAG,
                "Falha ao inserir o texto: \"$query\""
            )
        }

        return setTextResult
    }

    /**
     * Localiza o botão Enviar pelo ID exato.
     */
    private fun findGoogleSendButton(
        rootNode: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        val nodesById =
            rootNode.findAccessibilityNodeInfosByViewId(
                GOOGLE_SEND_BUTTON_ID
            )

        val buttonByExactId =
            nodesById.firstOrNull()

        if (buttonByExactId != null) {
            Log.d(
                TAG,
                "Botão Enviar localizado pelo ID exato: " +
                        "${buttonByExactId.viewIdResourceName}"
            )

            return buttonByExactId
        }

        return findSendButtonRecursively(
            node = rootNode,
            depth = 0
        )
    }

    /**
     * Busca alternativa usando o ID final ou a descrição "Enviar".
     */
    private fun findSendButtonRecursively(
        node: AccessibilityNodeInfo,
        depth: Int
    ): AccessibilityNodeInfo? {

        if (depth > MAX_DEPTH) {
            return null
        }

        val viewId =
            node.viewIdResourceName
                .orEmpty()

        val description =
            node.contentDescription
                ?.toString()
                .orEmpty()

        val className =
            node.className
                ?.toString()
                .orEmpty()

        val hasCorrectId =
            viewId.endsWith(
                "searchbox_aim_enter_button"
            )

        val hasSendDescription =
            description.equals(
                "Enviar",
                ignoreCase = true
            )

        val isButton =
            className == "android.widget.Button" ||
                    className == "android.widget.ImageButton"

        if (
            isButton &&
            (hasCorrectId || hasSendDescription)
        ) {
            Log.d(
                TAG,
                "Botão Enviar localizado pela busca alternativa | " +
                        "id=$viewId | descrição=$description"
            )

            return node
        }

        for (index in 0 until node.childCount) {
            val childNode =
                node.getChild(index)
                    ?: continue

            val result =
                findSendButtonRecursively(
                    node = childNode,
                    depth = depth + 1
                )

            if (result != null) {
                return result
            }
        }

        return null
    }

    /**
     * Tenta clicar diretamente no botão.
     *
     * Se o Android não aceitar o clique no próprio nó,
     * procura um ancestral clicável.
     */
    private fun clickSendButton(
        sendButtonNode: AccessibilityNodeInfo
    ): Boolean {

        val directClickResult =
            sendButtonNode.performAction(
                AccessibilityNodeInfo.ACTION_CLICK
            )

        Log.d(
            TAG,
            "Resultado do clique direto no botão Enviar = " +
                    "$directClickResult"
        )

        if (directClickResult) {
            return true
        }

        var parentNode =
            sendButtonNode.parent

        var parentLevel = 0

        while (
            parentNode != null &&
            parentLevel < 4
        ) {
            if (parentNode.isClickable) {
                val parentClickResult =
                    parentNode.performAction(
                        AccessibilityNodeInfo.ACTION_CLICK
                    )

                Log.d(
                    TAG,
                    "Resultado do clique no ancestral " +
                            "nível $parentLevel = $parentClickResult"
                )

                if (parentClickResult) {
                    return true
                }
            }

            parentNode = parentNode.parent
            parentLevel++
        }

        return false
    }

    private fun scheduleNextAttempt() {
        if (searchAttemptCount >= MAX_SEARCH_ATTEMPTS) {
            Log.e(
                TAG,
                "Não há mais tentativas disponíveis."
            )

            resetSearchState(
                clearPendingQuery = false
            )

            return
        }

        scheduleSearchAttempt(
            delayMillis = RETRY_INTERVAL_MS
        )
    }

    private fun resetSearchState(
        clearPendingQuery: Boolean
    ) {
        mainHandler.removeCallbacks(searchRunnable)

        searchAttemptCount = 0
        textInserted = false

        if (clearPendingQuery) {
            pendingQuery = null
        }
    }

    /*
     * ============================================================
     * FERRAMENTAS DE INSPEÇÃO
     * ============================================================
     *
     * Estes métodos foram preservados propositalmente.
     *
     * Eles não são executados automaticamente na versão funcional.
     * Servem para futuras verificações caso uma atualização do
     * aplicativo Google altere os IDs ou a estrutura da tela.
     */

    private fun inspectGoogleScreen() {
        val rootNode =
            rootInActiveWindow

        if (rootNode == null) {
            Log.w(
                TAG,
                "Não foi possível obter a raiz da janela ativa."
            )

            return
        }

        inspectedNodeCount = 0

        Log.d(
            TAG,
            "========== INÍCIO DA ÁRVORE DO GOOGLE =========="
        )

        inspectNode(
            node = rootNode,
            depth = 0
        )

        Log.d(
            TAG,
            "========== FIM DA ÁRVORE: " +
                    "$inspectedNodeCount nós =========="
        )
    }

    private fun inspectNode(
        node: AccessibilityNodeInfo,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        if (inspectedNodeCount >= MAX_NODES) return

        inspectedNodeCount++

        val indentation =
            "  ".repeat(depth)

        val text =
            node.text
                ?.toString()
                ?.replace("\n", " ")
                ?.take(100)
                .orEmpty()

        val description =
            node.contentDescription
                ?.toString()
                ?.replace("\n", " ")
                ?.take(100)
                .orEmpty()

        val viewId =
            node.viewIdResourceName
                .orEmpty()

        val className =
            node.className
                ?.toString()
                .orEmpty()

        val actionNames =
            node.actionList
                .mapNotNull { action ->
                    action.label?.toString()
                        ?: actionName(action.id)
                }
                .joinToString(
                    separator = ", "
                )

        Log.d(
            TAG,
            "$indentation" +
                    "NÓ #$inspectedNodeCount | " +
                    "classe=$className | " +
                    "id=$viewId | " +
                    "texto=\"$text\" | " +
                    "descrição=\"$description\" | " +
                    "editável=${node.isEditable} | " +
                    "clicável=${node.isClickable} | " +
                    "focado=${node.isFocused} | " +
                    "ações=[$actionNames]"
        )

        for (index in 0 until node.childCount) {
            val childNode =
                node.getChild(index)
                    ?: continue

            inspectNode(
                node = childNode,
                depth = depth + 1
            )
        }
    }

    private fun actionName(
        actionId: Int
    ): String? {
        return when (actionId) {

            AccessibilityNodeInfo.ACTION_CLICK ->
                "ACTION_CLICK"

            AccessibilityNodeInfo.ACTION_FOCUS ->
                "ACTION_FOCUS"

            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS ->
                "ACTION_ACCESSIBILITY_FOCUS"

            AccessibilityNodeInfo.ACTION_CLEAR_FOCUS ->
                "ACTION_CLEAR_FOCUS"

            AccessibilityNodeInfo.ACTION_LONG_CLICK ->
                "ACTION_LONG_CLICK"

            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ->
                "ACTION_SCROLL_FORWARD"

            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ->
                "ACTION_SCROLL_BACKWARD"

            AccessibilityNodeInfo.ACTION_SET_TEXT ->
                "ACTION_SET_TEXT"

            AccessibilityNodeInfo.ACTION_PASTE ->
                "ACTION_PASTE"

            else ->
                "ACTION_$actionId"
        }
    }

    override fun onInterrupt() {
        Log.d(
            TAG,
            "Serviço interrompido."
        )
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)

        if (connectedService === this) {
            connectedService = null
        }

        Log.d(
            TAG,
            "Serviço destruído."
        )

        super.onDestroy()
    }
}