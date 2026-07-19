package com.kellera.kellera03.context

class ContextManager {

    private var currentApp: String = "KELLERA"
    private var currentScreen: String = "Tela inicial"
    private var lastCommand: String = ""
    private var conversationState: String = "Aguardando desbloqueio"

    fun updateCurrentApp(appName: String) {
        currentApp = appName
    }

    fun updateCurrentScreen(screenName: String) {
        currentScreen = screenName
    }

    fun updateLastCommand(command: String) {
        lastCommand = command
    }

    fun updateConversationState(state: String) {
        conversationState = state
    }

    fun getCurrentApp(): String {
        return currentApp
    }

    fun getCurrentScreen(): String {
        return currentScreen
    }

    fun getLastCommand(): String {
        return lastCommand
    }

    fun getConversationState(): String {
        return conversationState
    }

    fun buildContextDescription(): String {
        return "Aplicativo atual: $currentApp. Tela atual: $currentScreen."
    }
}