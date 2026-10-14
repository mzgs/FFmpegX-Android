package com.mzgs.ffmpegx

internal object FFmpegArguments {
    fun format(arguments: List<String>): String = arguments.joinToString(" ") {
        "'${it.replace("'", "'\\''")}'"
    }

    fun parse(command: String): List<String> {
        val result = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var started = false
        var index = 0
        while (index < command.length) {
            val char = command[index++]
            when {
                char == '\\' && quote != '\'' -> {
                    require(index < command.length) { "Trailing escape in command" }
                    val next = command[index]
                    if (quote == '"' && next != '"' && next != '\\') token.append(char)
                    else token.append(command[index++])
                    started = true
                }
                char == quote -> quote = null
                quote != null -> token.append(char)
                char == '\'' || char == '"' -> { quote = char; started = true }
                char.isWhitespace() -> if (started) {
                    result.add(token.toString()); token.clear(); started = false
                }
                else -> { token.append(char); started = true }
            }
        }
        require(quote == null) { "Unclosed quote in command" }
        if (started) result.add(token.toString())
        require(result.none { '\u0000' in it }) { "NUL in command argument" }
        return result
    }
}
