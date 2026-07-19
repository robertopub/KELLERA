package com.kellera.kellera03.context

class ContextAnalyzer {

    fun analyzeCurrentContext(
        appName: String,
        screenName: String
    ): ContextResult {

        val description = when (appName.lowercase()) {

            "google" ->
                "Estamos na tela principal do Google."

            "youtube" ->
                "Estamos na tela inicial do YouTube."

            "whatsapp" ->
                "Estamos na tela principal do WhatsApp."

            "configurações" ->
                "Estamos nas configurações do Android."

            else ->
                "Contexto ainda não identificado."
        }

        return ContextResult(
            appName = appName,
            screenName = screenName,
            description = description,
            confidence = 1.0f
        )
    }

}