package dev.atdev.nexora

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Compact VT-style terminal screen for NEXORA.
 * Supports cursor addressing, screen/line erasing, scrolling, SGR colors,
 * alternate screen, tabs, backspace and common application key sequences.
 */
internal class TerminalView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : android.widget.TextView(context, attrs) {
    private data class Cell(var ch: Char = ' ', var fg: Int = 7, var bg: Int = 0, var bold: Boolean = false, var inverse: Boolean = false)
    private var cols = 80
    private var rows = 24
    private var grid = Array(rows) { Array(cols) { Cell() } }
    private var savedGrid: Array<Array<Cell>>? = null
    private var x = 0
    private var y = 0
    private var savedX = 0
    private var savedY = 0
    private var fg = 7
    private var bg = 0
    private var bold = false
    private var inverse = false
    private var wrap = true
    private var pendingWrap = false
    private var state = 0
    private val sequence = StringBuilder()
    private val terminalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val palette = intArrayOf(
        0xFF101820.toInt(), 0xFFCC6666.toInt(), 0xFFB5BD68.toInt(), 0xFFF0C674.toInt(),
        0xFF81A2BE.toInt(), 0xFFB294BB.toInt(), 0xFF8ABEB7.toInt(), 0xFFC5C8C6.toInt(),
        0xFF666666.toInt(), 0xFFD54E53.toInt(), 0xFFB9CA4A.toInt(), 0xFFE7C547.toInt(),
        0xFF7AA6DA.toInt(), 0xFFC397D8.toInt(), 0xFF70C0B1.toInt(), 0xFFEAEAEA.toInt()
    )
    private var cellWidth = 0f
    private var lineHeight = 0f
    private var cursorVisible = true

    init {
        setWillNotDraw(false)
        setPadding(dp(7), dp(6), dp(7), dp(8))
        terminalPaint.typeface = Typeface.MONOSPACE
        terminalPaint.textSize = 12.5f * resources.displayMetrics.scaledDensity
        terminalPaint.color = 0xFFDCE7F5.toInt()
        cellWidth = terminalPaint.measureText("M")
        val fm = terminalPaint.fontMetrics
        lineHeight = fm.descent - fm.ascent + dp(3)
        text = ""
        isFocusable = true
    }

    fun clearTerminal() {
        grid = Array(rows) { Array(cols) { Cell(fg = fg, bg = bg) } }
        x = 0; y = 0; pendingWrap = false
        invalidate()
    }

    fun feed(data: String) {
        for (c in data) {
            when (state) {
                0 -> when {
                    c == '\u001B' -> { state = 1; sequence.setLength(0) }
                    c == '\r' -> { x = 0; pendingWrap = false }
                    c == '\n' -> lineFeed()
                    c == '\b' -> { x = max(0, x - 1); pendingWrap = false }
                    c == '\t' -> { x = minOf(cols - 1, ((x / 8) + 1) * 8); pendingWrap = false }
                    c.code >= 0x20 && c.code != 0x7F -> put(c)
                }
                1 -> when (c) {
                    '[' -> { state = 2; sequence.setLength(0) }
                    ']' -> state = 3
                    '7' -> { savedX = x; savedY = y; state = 0 }
                    '8' -> { x = savedX.coerceIn(0, cols - 1); y = savedY.coerceIn(0, rows - 1); state = 0 }
                    'D' -> { lineFeed(); state = 0 }
                    'E' -> { x = 0; lineFeed(); state = 0 }
                    'M' -> { if (y == 0) scrollDown() else y--; state = 0 }
                    'c' -> { reset(); state = 0 }
                    else -> state = 0
                }
                2 -> {
                    if (c in '@'..'~') { handleCsi(c, sequence.toString()); state = 0; sequence.setLength(0) }
                    else if (sequence.length < 64) sequence.append(c)
                }
                3 -> if (c == '\u0007') state = 0 else if (c == '\u001B') state = 4
                4 -> state = if (c == '\\') 0 else 3
            }
        }
        invalidate()
    }

    private fun reset() {
        fg = 7; bg = 0; bold = false; inverse = false; x = 0; y = 0
        grid = Array(rows) { Array(cols) { Cell() } }
        savedGrid = null
    }

    private fun put(c: Char) {
        if (pendingWrap) { x = 0; lineFeed(); pendingWrap = false }
        grid[y][x] = Cell(c, fg, bg, bold, inverse)
        if (x == cols - 1) pendingWrap = wrap else x++
    }

    private fun lineFeed() {
        pendingWrap = false
        if (y == rows - 1) scrollUp() else y++
    }

    private fun scrollUp() {
        for (r in 0 until rows - 1) grid[r] = grid[r + 1]
        grid[rows - 1] = Array(cols) { Cell(fg = fg, bg = bg) }
    }

    private fun scrollDown() {
        for (r in rows - 1 downTo 1) grid[r] = grid[r - 1]
        grid[0] = Array(cols) { Cell(fg = fg, bg = bg) }
    }

    private fun params(raw: String): Pair<Boolean, List<Int>> {
        val private = raw.startsWith("?")
        val clean = raw.removePrefix("?").removeSuffix(" ")
        return private to clean.split(';').map { it.toIntOrNull() ?: 0 }
    }

    private fun handleCsi(final: Char, raw: String) {
        val (isPrivate, p) = params(raw)
        fun n(i: Int = 0, default: Int = 1) = (p.getOrNull(i) ?: 0).let { if (it == 0) default else it }
        when (final) {
            'A' -> y = (y - n()).coerceAtLeast(0)
            'B' -> y = (y + n()).coerceAtMost(rows - 1)
            'C' -> x = (x + n()).coerceAtMost(cols - 1)
            'D' -> x = (x - n()).coerceAtLeast(0)
            'E' -> { y = (y + n()).coerceAtMost(rows - 1); x = 0 }
            'F' -> { y = (y - n()).coerceAtLeast(0); x = 0 }
            'G' -> x = (n() - 1).coerceIn(0, cols - 1)
            'd' -> y = (n() - 1).coerceIn(0, rows - 1)
            'H', 'f' -> { y = (n(0) - 1).coerceIn(0, rows - 1); x = (n(1) - 1).coerceIn(0, cols - 1); pendingWrap = false }
            'J' -> eraseDisplay(p.firstOrNull() ?: 0)
            'K' -> eraseLine(p.firstOrNull() ?: 0)
            'm' -> sgr(p)
            's' -> { savedX = x; savedY = y }
            'u' -> { x = savedX.coerceIn(0, cols - 1); y = savedY.coerceIn(0, rows - 1) }
            'S' -> repeat(n()) { scrollUp() }
            'T' -> repeat(n()) { scrollDown() }
            'L' -> repeat(n()) { insertLine() }
            'M' -> repeat(n()) { deleteLine() }
            '@' -> repeat(n()) { insertChar() }
            'P' -> repeat(n()) { deleteChar() }
            'X' -> repeat(n()) { grid[y][x] = Cell(fg = fg, bg = bg); x = minOf(cols - 1, x + 1) }
            'h', 'l' -> if (isPrivate && p.contains(1049)) {
                if (final == 'h') {
                    savedGrid = Array(rows) { r -> Array(cols) { c -> grid[r][c].copy() } }
                    clearTerminal()
                } else {
                    savedGrid?.let { grid = it }; savedGrid = null; x = 0; y = 0
                }
            }
            'r' -> { /* scrolling region defaults to the full screen */ }
        }
    }

    private fun sgr(p: List<Int>) {
        if (p.isEmpty()) { fg = 7; bg = 0; bold = false; inverse = false; return }
        var i = 0
        while (i < p.size) {
            when (val v = p[i]) {
                0 -> { fg = 7; bg = 0; bold = false; inverse = false }
                1 -> bold = true
                22 -> bold = false
                7 -> inverse = true
                27 -> inverse = false
                30..37 -> fg = v - 30
                40..47 -> bg = v - 40
                90..97 -> fg = v - 90 + 8
                100..107 -> bg = v - 100 + 8
                39 -> fg = 7
                49 -> bg = 0
                38, 48 -> if (p.getOrNull(i + 1) == 5 && p.size > i + 2) {
                    val color = ansi256(p[i + 2])
                    if (v == 38) fg = color else bg = color
                    i += 2
                } else if (p.getOrNull(i + 1) == 2 && p.size > i + 4) {
                    val color = android.graphics.Color.rgb(p[i + 2].coerceIn(0,255), p[i + 3].coerceIn(0,255), p[i + 4].coerceIn(0,255))
                    if (v == 38) fg = color else bg = color
                    i += 4
                }
            }
            i++
        }
    }

    private fun ansi256(v: Int): Int {
        if (v < 16) return v
        if (v >= 232) { val c = 8 + (v - 232) * 10; return android.graphics.Color.rgb(c,c,c) }
        val n = v - 16
        val levels = intArrayOf(0,95,135,175,215,255)
        return android.graphics.Color.rgb(levels[n / 36], levels[(n / 6) % 6], levels[n % 6])
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            2, 3 -> { for (r in 0 until rows) for (c in 0 until cols) grid[r][c] = Cell(fg = fg, bg = bg) }
            1 -> { for (r in 0..y) for (c in 0 until if (r == y) x + 1 else cols) grid[r][c] = Cell(fg = fg, bg = bg) }
            else -> { for (r in y until rows) for (c in (if (r == y) x else 0) until cols) grid[r][c] = Cell(fg = fg, bg = bg) }
        }
    }

    private fun eraseLine(mode: Int) {
        val range = when (mode) { 1 -> 0..x; 2 -> 0 until cols; else -> x until cols }
        for (c in range) if (c in 0 until cols) grid[y][c] = Cell(fg = fg, bg = bg)
    }

    private fun insertLine() { scrollDown() }
    private fun deleteLine() { scrollUp() }
    private fun insertChar() {
        for (c in cols - 1 downTo x + 1) grid[y][c] = grid[y][c - 1]
        grid[y][x] = Cell(fg = fg, bg = bg)
    }
    private fun deleteChar() {
        for (c in x until cols - 1) grid[y][c] = grid[y][c + 1]
        grid[y][cols - 1] = Cell(fg = fg, bg = bg)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = (paddingTop + paddingBottom + lineHeight * rows).toInt()
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(desiredHeight, MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredHeight
        }
        setMeasuredDimension(width, height)
        val available = max(1f, width - paddingLeft - paddingRight)
        val nextCols = (available / cellWidth).toInt().coerceAtLeast(20)
        if (nextCols != cols) resize(nextCols, rows)
    }

    private fun resize(newCols: Int, newRows: Int) {
        val next = Array(newRows) { r -> Array(newCols) { c ->
            if (r < rows && c < cols) grid[r][c] else Cell(fg = fg, bg = bg)
        } }
        grid = next; cols = newCols; rows = newRows
        x = x.coerceIn(0, cols - 1); y = y.coerceIn(0, rows - 1)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF0D1523.toInt())
        val fm = terminalPaint.fontMetrics
        val base = paddingTop - fm.ascent
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cell = grid[r][c]
                val reverse = cell.inverse xor inverse
                val back = if (reverse) cell.fg else cell.bg
                if (back != 0) {
                    terminalPaint.color = colorFor(back, null)
                    canvas.drawRect(paddingLeft + c * cellWidth, paddingTop + r * lineHeight,
                        paddingLeft + (c + 1) * cellWidth, paddingTop + (r + 1) * lineHeight, terminalPaint)
                }
                if (cell.ch != ' ') {
                    terminalPaint.color = colorFor(if (reverse) cell.bg else cell.fg, null)
                    terminalPaint.isFakeBoldText = cell.bold
                    canvas.drawText(cell.ch.toString(), paddingLeft + c * cellWidth, base + r * lineHeight, terminalPaint)
                }
            }
        }
        if (cursorVisible && isFocused) {
            terminalPaint.color = 0xFFB9E6FF.toInt()
            canvas.drawRect(paddingLeft + x * cellWidth, paddingTop + y * lineHeight,
                paddingLeft + (x + 1) * cellWidth, paddingTop + (y + 1) * lineHeight, terminalPaint)
        }
    }

    private fun colorFor(index: Int, custom: Int?): Int =
        custom ?: if (index in 0..15) palette[index] else index

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
