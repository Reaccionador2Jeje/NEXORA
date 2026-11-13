package dev.atdev.nexora

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** First native Android frontend. Commands stay inside the app-private workspace. */
class MainActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private lateinit var workingDirectory: File

    private val prompt: String
        get() = "nexora:${workingDirectory.name.ifBlank { "/" }}$ "

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        workingDirectory = getSharedPreferences("nexora", MODE_PRIVATE)
            .getString("working_directory", filesDir.absolutePath)
            ?.let(::File)
            ?.takeIf { isInsideWorkspace(it) && it.isDirectory }
            ?: filesDir
        buildInterface()
        appendLine("NEXORA 0.1.0 — Android")
        appendLine("Your Terminal. Your Universe.")
        appendLine("Private workspace: ${filesDir.absolutePath}")
        appendLine("Type 'help' to see available commands.")
        appendPrompt()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(12))
            setBackgroundColor(0xFF10141D.toInt())
        }
        root.addView(TextView(this).apply {
            text = "NEXORA"
            textSize = 23f
            setTextColor(0xFF7DD3FC.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "ANDROID TERMINAL  •  0.1.0"
            textSize = 11f
            setTextColor(0xFF8291A8.toInt())
            setPadding(0, dp(2), 0, dp(12))
        })
        scroll = ScrollView(this)
        output = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFE2E8F0.toInt())
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        input = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8291A8.toInt())
            hint = "Enter command..."
            imeOptions = EditorInfo.IME_ACTION_SEND
            setBackgroundColor(0xFF202938.toInt())
            setPadding(dp(10), 0, dp(10), 0)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) { submitCommand(); true } else false
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        row.addView(Button(this).apply {
            text = "RUN"
            setOnClickListener { submitCommand() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        root.addView(row)
        setContentView(root)
    }

    private fun submitCommand() {
        val line = input.text.toString().trim()
        input.text.clear()
        if (line.isEmpty()) return
        appendLine("$prompt$line")
        val parts = line.split(Regex("\\s+"), limit = 2)
        val command = parts[0].lowercase(Locale.ROOT)
        val argument = parts.getOrNull(1).orEmpty()
        when (command) {
            "help" -> {
                appendLine("Commands: help, version, clear, echo, pwd, ls, cd, date, exit")
                appendLine("Filesystem access is restricted to the app-private workspace.")
                appendLine("External shell commands are not enabled in this preview.")
            }
            "version" -> appendLine("NEXORA Android 0.1.0")
            "clear", "cls" -> output.text = ""
            "echo" -> appendLine(argument)
            "pwd" -> appendLine(workingDirectory.absolutePath)
            "date" -> appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            "ls", "dir" -> listFiles(argument)
            "cd" -> changeDirectory(argument)
            "exit", "quit" -> appendLine("Use Android navigation to leave NEXORA.")
            else -> appendLine("nex: command unavailable in Android preview: $command")
        }
        appendPrompt()
    }

    private fun listFiles(argument: String) {
        val target = resolvePath(argument.ifBlank { "." })
        if (target == null) { appendLine("nex: path is outside the private workspace"); return }
        if (!target.isDirectory) { appendLine("nex: not a directory: ${target.name}"); return }
        val entries = target.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }
        if (entries.isNullOrEmpty()) appendLine("(empty)")
        else entries.forEach { appendLine(it.name + if (it.isDirectory) "/" else "") }
    }

    private fun changeDirectory(argument: String) {
        val target = if (argument.isBlank() || argument == "~") filesDir else resolvePath(argument)
        if (target == null || !target.isDirectory) {
            appendLine("nex: directory not found inside private workspace")
            return
        }
        workingDirectory = target
        getSharedPreferences("nexora", MODE_PRIVATE).edit()
            .putString("working_directory", workingDirectory.absolutePath).apply()
    }

    private fun resolvePath(path: String): File? = try {
        File(workingDirectory, path).canonicalFile.takeIf { isInsideWorkspace(it) }
    } catch (_: Exception) { null }

    private fun isInsideWorkspace(file: File): Boolean = try {
        val candidate = file.canonicalPath
        val root = filesDir.canonicalPath
        candidate == root || candidate.startsWith(root + File.separator)
    } catch (_: Exception) { false }

    private fun appendPrompt() {
        output.append("\n$prompt")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun appendLine(value: String) {
        output.append("$value\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
