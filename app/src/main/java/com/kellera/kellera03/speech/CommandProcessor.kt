package com.kellera.kellera03.speech

import com.kellera.kellera03.model.UserCommand

class CommandProcessor {

    fun process(rawText: String): UserCommand {
        val normalizedText = rawText
            .trim()
            .lowercase()

        return UserCommand(
            rawText = rawText,
            normalizedText = normalizedText
        )
    }
}