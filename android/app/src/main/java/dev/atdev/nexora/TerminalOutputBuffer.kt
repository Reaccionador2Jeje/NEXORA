package dev.atdev.nexora

/**
 * Stateful ANSI stream cleanup for the scrolling console.
 *
 * Handles split escape sequences, ANSI styling, carriage-return progress
 * lines, backspace and erase-line controls. Full-screen TUIs need a grid-based
 * terminal emulator and are intentionally not flattened into this text buffer.
 */
internal class TerminalOutputBuffer(private val maxChars: Int) {
    private val text = StringBuilder()
    private var cursor = 0
    private var state = State.TEXT
    private val escape = StringBuilder()

    private enum class State { TEXT, ESCAPE, CSI, OSC, OSC_ESCAPE }

    fun clear() {
        text.setLength(0)
        cursor = 0
        state = State.TEXT
        escape.setLength(0)
    }

    fun append(chunk: String): String {
        for (ch in chunk) {
            when (state) {
                State.TEXT -> when {
                    ch == '\u001B' -> { state = State.ESCAPE; escape.setLength(0) }
                    ch == '\r' -> cursor = lineStart()
                    ch == '\n' -> { write('\n'); cursor = text.length }
                    ch == '\b' -> if (cursor > lineStart()) cursor--
                    ch == '\t' -> {
                        val spaces = 8 - ((cursor - lineStart()) % 8)
                        repeat(spaces) { write(' ') }
                    }
                    ch.code < 0x20 || ch.code == 0x7F -> Unit
                    else -> write(ch)
                }
                State.ESCAPE -> when (ch) {
                    '[' -> { state = State.CSI; escape.setLength(0) }
                    ']' -> state = State.OSC
                    else -> state = State.TEXT
                }
                State.CSI -> {
                    if (ch in '@'..'~') {
                        if (ch == 'K') eraseLine(escape.toString())
                        state = State.TEXT
                        escape.setLength(0)
                    } else if (escape.length < 32) {
                        escape.append(ch)
                    }
                }
                State.OSC -> when (ch) {
                    '\u0007' -> state = State.TEXT
                    '\u001B' -> state = State.OSC_ESCAPE
                }
                State.OSC_ESCAPE -> state = if (ch == '\\') State.TEXT else State.OSC
            }
        }
        trim()
        return text.toString()
    }

    private fun write(ch: Char) {
        if (cursor < text.length) text.setCharAt(cursor, ch) else text.append(ch)
        cursor++
    }

    private fun lineStart(): Int {
        val previousNewline = if (cursor > 0) text.lastIndexOf("\n", (cursor - 1).coerceAtLeast(0)) else -1
        return previousNewline + 1
    }

    private fun eraseLine(parameters: String) {
        val mode = parameters.substringAfterLast(';').toIntOrNull() ?: 0
        val start = lineStart()
        when (mode) {
            0 -> if (cursor < text.length) text.delete(cursor, text.indexOf("\n", cursor).let { if (it < 0) text.length else it })
            1 -> {
                val end = cursor.coerceAtMost(text.length)
                if (end > start) text.delete(start, end)
                cursor = start
            }
            2 -> {
                val end = text.indexOf("\n", start).let { if (it < 0) text.length else it }
                text.delete(start, end)
                cursor = start
            }
        }
    }

    private fun trim() {
        if (text.length <= maxChars) return
        val overflow = text.length - maxChars
        val newline = text.indexOf("\n", overflow)
        val remove = if (newline >= 0) newline + 1 else overflow
        text.delete(0, remove)
        cursor = (cursor - remove).coerceAtLeast(0)
    }
}
