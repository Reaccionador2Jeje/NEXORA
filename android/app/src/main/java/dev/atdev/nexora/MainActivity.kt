package dev.atdev.nexora

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

/**
 * NEXORA Android terminal frontend.
 * This build stores the Ubuntu profile securely; the Ubuntu userspace/runtime
 * is a separate integration stage and is not falsely emulated by this screen.
 */
class MainActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var promptLabel: TextView
    private lateinit var scroll: ScrollView
    private lateinit var workingDirectory: File
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = 0
    private var username = "user"
    private var hostname = "nexora"

    private val preferences by lazy { getSharedPreferences("nexora", MODE_PRIVATE) }
    private val prompt: String
        get() = "$username@$hostname:${displayPath()}$ "

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        username = preferences.getString("ubuntu_username", "user") ?: "user"
        hostname = preferences.getString("ubuntu_hostname", "nexora") ?: "nexora"
        workingDirectory = preferences.getString("working_directory", filesDir.absolutePath)
            ?.let(::File)
            ?.takeIf { isInsideWorkspace(it) && it.isDirectory }
            ?: filesDir

        buildInterface()
        if (!preferences.getBoolean("ubuntu_profile_configured", false)) {
            showUbuntuSetup()
        } else {
            printWelcome()
        }
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(8))
            setBackgroundColor(0xFF0B1020.toInt())
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(12))
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(TextView(this).apply {
            text = "NEXORA"
            textSize = 22f
            setTextColor(0xFF7DD3FC.toInt())
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        brand.addView(TextView(this).apply {
            text = "YOUR TERMINAL. YOUR UNIVERSE."
            textSize = 9f
            letterSpacing = 0.12f
            setTextColor(0xFF71819D.toInt())
        })
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "●  ANDROID"
            textSize = 10f
            setTextColor(0xFF86EFAC.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(0xFF142B2A.toInt())
        })
        root.addView(header)

        val status = TextView(this).apply {
            text = "UBUNTU PROFILE  •  ${username}@${hostname}"
            textSize = 10f
            setTextColor(0xFF9AAAC2.toInt())
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setBackgroundColor(0xFF151E30.toInt())
        }
        root.addView(status)

        scroll = ScrollView(this).apply {
            isFillViewport = true
            setClipToPadding(false)
            setPadding(0, dp(8), 0, dp(8))
        }
        output = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFDCE7F5.toInt())
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(10), dp(10), dp(10), dp(14))
        }
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Teclas auxiliares de terminal, situadas justo encima del prompt/teclado.
        val shortcutsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(4), 0, dp(4))
        }
        val shortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val keys = listOf("ESC", "TAB", "CTRL", "ALT", "←", "→", "↑", "↓", "HOME", "END", "/", "-", "|", "~", "Ctrl+C")
        keys.forEach { key ->
            val keyView = TextView(this).apply {
                text = key
                textSize = 11f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setTextColor(0xFFCBD5E1.toInt())
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setBackgroundColor(0xFF1B2638.toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener { handleShortcut(key) }
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)
            )
            params.marginEnd = dp(5)
            shortcuts.addView(keyView, params)
        }
        shortcutsScroll.addView(shortcuts)
        root.addView(shortcutsScroll)

        val promptRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(3), dp(6), dp(3))
            setBackgroundColor(0xFF172235.toInt())
        }
        promptLabel = TextView(this).apply {
            text = prompt
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF86EFAC.toInt())
            maxWidth = dp(160)
        }
        promptRow.addView(promptLabel)
        input = EditText(this).apply {
            setSingleLine(true)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF71819D.toInt())
            hint = "command"
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setBackgroundColor(0x00000000)
            setPadding(dp(5), 0, dp(4), 0)
            setOnEditorActionListener { _, actionId, event ->
                val enter = actionId == EditorInfo.IME_ACTION_SEND ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
                if (enter) { submitCommand(); true } else false
            }
        }
        promptRow.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(promptRow)
        setContentView(root)
    }

    private fun showUbuntuSetup() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        fun field(hintText: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText =
            EditText(this).apply {
                hint = hintText
                inputType = type
                setSingleLine(true)
            }

        val userField = field("Username (e.g. cris)")
        val hostField = field("Hostname (e.g. nexora)")
        val passwordField = field("Ubuntu password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val confirmField = field("Confirm password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        panel.addView(userField)
        panel.addView(hostField)
        panel.addView(passwordField)
        panel.addView(confirmField)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Configure Ubuntu session")
            .setMessage("Create the Linux user that NEXORA will provision in its Ubuntu environment.")
            .setView(panel)
            .setCancelable(false)
            .setPositiveButton("Create profile", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val user = userField.text.toString().trim().lowercase(Locale.ROOT)
                val host = hostField.text.toString().trim().lowercase(Locale.ROOT)
                val password = passwordField.text.toString()
                val confirmation = confirmField.text.toString()
                val validName = Regex("^[a-z_][a-z0-9_-]{0,31}$")
                when {
                    !validName.matches(user) -> userField.error = "Use 1–32 chars: a-z, 0-9, _ or -"
                    !Regex("^[a-z0-9][a-z0-9-]{0,62}$").matches(host) -> hostField.error = "Invalid hostname"
                    password.length < 8 -> passwordField.error = "Use at least 8 characters"
                    password != confirmation -> confirmField.error = "Passwords do not match"
                    else -> {
                        try {
                            // The password is encrypted with an Android Keystore key, never stored as plain text.
                            preferences.edit()
                                .putString("ubuntu_username", user)
                                .putString("ubuntu_hostname", host)
                                .putString("ubuntu_password_enc", encryptPassword(password))
                                .putBoolean("ubuntu_profile_configured", true)
                                .apply()
                            username = user
                            hostname = host
                            promptLabel.text = prompt
                            dialog.dismiss()
                            printWelcome()
                        } catch (error: Exception) {
                            Toast.makeText(this, "Could not secure the password: ${error.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun encryptPassword(password: String): String {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "nexora_ubuntu_password_key"
        val key = if (keyStore.containsAlias(alias)) {
            (keyStore.getKey(alias, null) as SecretKey)
        } else {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generator.generateKey()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(password.toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + encrypted
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun printWelcome() {
        output.text = ""
        appendLine("NEXORA 0.2.0 — Android")
        appendLine("Ubuntu profile: $username@$hostname")
        appendLine("Home target: /home/$username")
        appendLine("Your Terminal. Your Universe.")
        appendLine("")
        appendLine("Profile saved securely. Ubuntu userspace provisioning is the next integration stage.")
        appendLine("This preview currently runs NEXORA's safe local commands, not an Ubuntu shell.")
        appendLine("Type 'help' to see available commands.")
        promptLabel.text = prompt
        input.requestFocus()
    }

    private fun displayPath(): String =
        if (workingDirectory == filesDir) "~" else "~/${workingDirectory.relativeTo(filesDir).path}"

    private fun submitCommand() {
        val line = input.text.toString().trim()
        input.text.clear()
        if (line.isEmpty()) return
        commandHistory.add(line)
        if (commandHistory.size > 300) commandHistory.removeAt(0)
        historyIndex = commandHistory.size
        appendLine("$prompt$line")

        val parts = line.split(Regex("\\s+"), limit = 2)
        val command = parts[0].lowercase(Locale.ROOT)
        val argument = parts.getOrNull(1).orEmpty()
        when (command) {
            "help" -> {
                appendLine("NEXORA commands: help, version, clear, echo, pwd, ls, cd, date, whoami, hostname, exit")
                appendLine("Ubuntu profile is configured; full Ubuntu command execution is not enabled yet.")
            }
            "version" -> appendLine("NEXORA Android 0.2.0")
            "clear", "cls" -> output.text = ""
            "echo" -> appendLine(argument)
            "pwd" -> appendLine("/home/$username" + if (workingDirectory == filesDir) "" else "/${workingDirectory.relativeTo(filesDir).path}")
            "whoami" -> appendLine(username)
            "hostname" -> appendLine(hostname)
            "date" -> appendLine(SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.getDefault()).format(Date()))
            "ls", "dir" -> listFiles(argument)
            "cd" -> changeDirectory(argument)
            "exit", "logout" -> appendLine("Ubuntu session will close when the Ubuntu runtime is connected.")
            else -> appendLine("nex: '$command' is unavailable until the Ubuntu runtime is integrated.")
        }
        promptLabel.text = prompt
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun installUbuntuRuntime() {
        if (ubuntuRuntime.isInstalled()) {
            appendLine("Ubuntu runtime is already installed.")
            return
        }
        val encrypted = preferences.getString("ubuntu_password_enc", null)
        if (encrypted.isNullOrBlank()) {
            appendLine("Ubuntu password is missing. Reconfigure the profile.")
            return
        }
        val password = try {
            decryptPassword(encrypted)
        } catch (error: Exception) {
            appendLine("Could not unlock Ubuntu password: ${error.message}")
            return
        }
        appendLine("Starting Ubuntu 24.04 LTS installation…")
        input.isEnabled = false
        ubuntuRuntime.install(username, password, { message ->
            runOnUiThread { appendLine(message) }
        }) { success, message, uid ->
            runOnUiThread {
                input.isEnabled = true
                if (success) {
                    ubuntuUid = uid
                    preferences.edit().putInt("ubuntu_uid", uid).apply()
                    appendLine(message)
                    appendLine("You can now run Linux commands directly.")
                } else {
                    appendLine("Ubuntu installation failed: $message")
                    appendLine("Run install-ubuntu again to retry.")
                }
                promptLabel.text = "$username@$hostname:~$ "
                input.requestFocus()
            }
        }
    }

    private fun decryptPassword(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > 12) { "Invalid encrypted password" }
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey("nexora_ubuntu_password_key", null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun handleShortcut(key: String) {
        when (key) {
            "ESC" -> Unit
            "TAB" -> insertText("    ")
            "CTRL" -> Toast.makeText(this, "Choose a Ctrl shortcut such as Ctrl+C.", Toast.LENGTH_SHORT).show()
            "ALT" -> Unit
            "←" -> moveCursor(-1)
            "→" -> moveCursor(1)
            "↑" -> {
                if (commandHistory.isNotEmpty()) {
                    historyIndex = (historyIndex - 1).coerceAtLeast(0)
                    input.setText(commandHistory[historyIndex])
                    input.setSelection(input.text.length)
                }
            }
            "↓" -> {
                historyIndex = (historyIndex + 1).coerceAtMost(commandHistory.size)
                input.setText(commandHistory.getOrNull(historyIndex).orEmpty())
                input.setSelection(input.text.length)
            }
            "HOME" -> input.setSelection(0)
            "END" -> input.setSelection(input.text.length)
            "Ctrl+C" -> {
                input.text.clear()
                appendLine("^C")
            }
            else -> insertText(key)
        }
        input.requestFocus()
    }

    private fun insertText(value: String) {
        val start = input.selectionStart.coerceAtLeast(0)
        val end = input.selectionEnd.coerceAtLeast(0)
        input.text.replace(minOf(start, end), maxOf(start, end), value)
    }

    private fun moveCursor(delta: Int) {
        val current = input.selectionStart.coerceAtLeast(0)
        input.setSelection((current + delta).coerceIn(0, input.text.length))
    }

    private fun listFiles(argument: String) {
        val target = resolvePath(argument.ifBlank { "." })
        if (target == null) {
            appendLine("nex: path is outside the NEXORA workspace")
            return
        }
        if (!target.isDirectory) {
            appendLine("nex: not a directory: ${target.name}")
            return
        }
        val entries = target.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }
        if (entries.isNullOrEmpty()) appendLine("(empty)")
        else entries.forEach { appendLine(it.name + if (it.isDirectory) "/" else "") }
    }

    private fun changeDirectory(argument: String) {
        val target = if (argument.isBlank() || argument == "~") filesDir else resolvePath(argument)
        if (target == null || !target.isDirectory) {
            appendLine("nex: directory not found inside NEXORA workspace")
            return
        }
        workingDirectory = target
        preferences.edit().putString("working_directory", workingDirectory.absolutePath).apply()
        promptLabel.text = prompt
    }

    private fun resolvePath(path: String): File? = try {
        File(workingDirectory, path).canonicalFile.takeIf { isInsideWorkspace(it) }
    } catch (_: Exception) {
        null
    }

    private fun isInsideWorkspace(file: File): Boolean = try {
        val candidate = file.canonicalPath
        val root = filesDir.canonicalPath
        candidate == root || candidate.startsWith(root + File.separator)
    } catch (_: Exception) {
        false
    }

    private fun appendLine(value: String) {
        output.append("$value\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
