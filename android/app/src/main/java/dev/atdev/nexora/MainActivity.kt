package dev.atdev.nexora

import android.app.Activity
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    private lateinit var runtimeStatus: TextView
    private lateinit var folderStatus: TextView
    private lateinit var workingDirectory: File
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = 0
    private var username = "user"
    private var hostname = "nexora"
    private lateinit var ubuntuRuntime: UbuntuRuntime
    private var ubuntuUid = 10000
    private var terminalReceiverRegistered = false

    private val terminalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TerminalExecutionService.ACTION_OUTPUT ->
                    appendLine(intent.getStringExtra(TerminalExecutionService.EXTRA_OUTPUT).orEmpty())
                TerminalExecutionService.ACTION_FINISHED -> {
                    val code = intent.getIntExtra(TerminalExecutionService.EXTRA_EXIT_CODE, 1)
                    appendLine("[process exited with code $code]")
                    input.isEnabled = true
                    promptLabel.text = prompt
                    input.requestFocus()
                }
                TerminalExecutionService.ACTION_INSTALL_FINISHED -> {
                    val success = intent.getBooleanExtra(TerminalExecutionService.EXTRA_INSTALL_SUCCESS, false)
                    val message = intent.getStringExtra(TerminalExecutionService.EXTRA_INSTALL_MESSAGE).orEmpty()
                    val uid = intent.getIntExtra(TerminalExecutionService.EXTRA_INSTALL_UID, -1)
                    input.isEnabled = true
                    if (success && uid > 0) {
                        ubuntuUid = uid
                        preferences.edit().putInt("ubuntu_uid", uid).apply()
                        appendLine(message)
                        appendLine("Ubuntu is ready. You can now run Linux commands.")
                        updateRuntimeStatus()
                    } else {
                        appendLine("Ubuntu installation failed: $message")
                        appendLine("Run install-ubuntu again to resume/retry.")
                        updateRuntimeStatus()
                    }
                    promptLabel.text = prompt
                    input.requestFocus()
                }
            }
        }
    }

    private val preferences by lazy { getSharedPreferences("nexora", MODE_PRIVATE) }
    private val prompt: String
        get() = "$username@$hostname:${displayPath()}$ "

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ubuntuRuntime = UbuntuRuntime(this)
        ubuntuUid = preferences.getInt("ubuntu_uid", 10000)
        username = preferences.getString("ubuntu_username", "user") ?: "user"
        hostname = preferences.getString("ubuntu_hostname", "nexora") ?: "nexora"
        workingDirectory = preferences.getString("working_directory", filesDir.absolutePath)
            ?.let(::File)
            ?.takeIf { isInsideWorkspace(it) && it.isDirectory }
            ?: filesDir

        buildInterface()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7401)
        }
        if (!preferences.getBoolean("ubuntu_profile_configured", false)) {
            showUbuntuSetup()
        } else {
            printWelcome()
        }
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            setBackgroundColor(0xFF080D19.toInt())
        }

        // Top bar: marca, versión y estado de conexión.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(14))
        }
        val brandColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandColumn.addView(TextView(this).apply {
            text = "NEXORA"
            textSize = 25f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setTextColor(0xFFEAF5FF.toInt())
        })
        brandColumn.addView(TextView(this).apply {
            text = "YOUR TERMINAL. YOUR UNIVERSE."
            textSize = 9f
            letterSpacing = 0.16f
            setTextColor(0xFF7186A8.toInt())
        })
        header.addView(brandColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(makePill("●  ANDROID", 0xFF86EFAC.toInt(), 0xFF102B28.toInt()))
        root.addView(header)

        // Hero panel.
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedBackground(intArrayOf(0xFF142C4A.toInt(), 0xFF17213B.toInt()), 24)
        }
        hero.addView(TextView(this).apply {
            text = "LINUX, IN YOUR POCKET"
            textSize = 10f
            letterSpacing = 0.16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF7DD3FC.toInt())
        })
        hero.addView(TextView(this).apply {
            text = "Your own Ubuntu\nworkspace."
            textSize = 26f
            setLineSpacing(dp(1).toFloat(), 1f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(0xFFF3F8FF.toInt())
            setPadding(0, dp(7), 0, dp(5))
        })
        hero.addView(TextView(this).apply {
            text = "A rootless Linux environment, powered by PRoot."
            textSize = 12f
            setTextColor(0xFFB6C8E2.toInt())
        })
        val heroActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        val installButton = makeActionButton("↓  INSTALL UBUNTU", 0xFF38BDF8.toInt(), 0xFF08243A.toInt(), true) {
            installUbuntuRuntime()
        }
        heroActions.addView(installButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        val statusButton = makeActionButton("CHECK STATUS", 0xFFD5E7FF.toInt(), 0x263B82B8, false) {
            appendLine(if (ubuntuRuntime.isInstalled()) "Ubuntu 24.04: installed and ready." else "Ubuntu 24.04: not installed.")
            updateRuntimeStatus()
        }
        val statusParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46))
        statusParams.marginStart = dp(8)
        heroActions.addView(statusButton, statusParams)
        hero.addView(heroActions)
        root.addView(hero)

        // Runtime and storage cards.
        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val runtimeCard = makeInfoCard("UBUNTU RUNTIME", "●  Checking…", 0xFF7DD3FC.toInt())
        runtimeStatus = runtimeCard.second
        infoRow.addView(runtimeCard.first, LinearLayout.LayoutParams(0, dp(78), 1f))
        val storageCard = makeInfoCard("ANDROID STORAGE", "Not connected", 0xFFC4B5FD.toInt())
        folderStatus = storageCard.second
        val storageParams = LinearLayout.LayoutParams(0, dp(78), 1f)
        storageParams.marginStart = dp(9)
        infoRow.addView(storageCard.first, storageParams)
        root.addView(infoRow)
        updateRuntimeStatus()
        updateFolderStatus()

        // Quick tools.
        val toolsTitle = TextView(this).apply {
            text = "QUICK ACTIONS"
            textSize = 10f
            letterSpacing = 0.15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF7186A8.toInt())
            setPadding(dp(2), dp(2), 0, dp(7))
        }
        root.addView(toolsTitle)
        val toolsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val toolItems = listOf(
            Triple("📁  Storage", 0xFFC4B5FD.toInt(), { openAndroidFolderPicker() }),
            Triple("⌨  Help", 0xFF7DD3FC.toInt(), { appendLine("Commands: help, version, install-ubuntu, ubuntu-status, clear, exit") }),
            Triple("✦  Clear", 0xFF86EFAC.toInt(), { output.text = "" })
        )
        toolItems.forEach { item ->
            val button = makeActionButton(item.first, item.second, 0xFF172338.toInt(), false, item.third)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
            params.marginEnd = dp(8)
            tools.addView(button, params)
        }
        toolsScroll.addView(tools)
        root.addView(toolsScroll)

        // Terminal panel.
        val terminalPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(intArrayOf(0xFF101A2A.toInt(), 0xFF0D1523.toInt()), 20)
            setPadding(dp(10), dp(10), dp(10), dp(8))
        }
        val terminalHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(2), dp(5), dp(8))
        }
        val terminalTitle = TextView(this).apply {
            text = "TERMINAL"
            textSize = 11f
            letterSpacing = 0.13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFE2EDFC.toInt())
        }
        terminalHeader.addView(terminalTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        terminalHeader.addView(makePill("●  LIVE CONSOLE", 0xFF86EFAC.toInt(), 0xFF142B2A.toInt()))
        terminalPanel.addView(terminalHeader)

        scroll = ScrollView(this).apply {
            isFillViewport = true
            setClipToPadding(false)
            setPadding(0, dp(4), 0, dp(4))
        }
        output = TextView(this).apply {
            textSize = 12.5f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextColor(0xFFDCE7F5.toInt())
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(8), dp(8), dp(8), dp(12))
        }
        scroll.addView(output)
        terminalPanel.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(terminalPanel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Terminal shortcut rail.
        val shortcutLabel = TextView(this).apply {
            text = "KEYBOARD SHORTCUTS"
            textSize = 9f
            letterSpacing = 0.13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF7186A8.toInt())
            setPadding(dp(2), dp(9), 0, dp(4))
        }
        root.addView(shortcutLabel)
        val shortcutsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(2), 0, dp(5))
        }
        val shortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val keys = listOf("ESC", "TAB", "CTRL", "ALT", "←", "→", "↑", "↓", "HOME", "END", "/", "-", "|", "~", "Ctrl+C")
        keys.forEach { key ->
            val keyView = TextView(this).apply {
                text = key
                textSize = 10f
                typeface = Typeface.create("monospace", Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(0xFFCBDAEF.toInt())
                setPadding(dp(11), dp(8), dp(11), dp(8))
                background = roundedBackground(intArrayOf(0xFF1B2940.toInt(), 0xFF152136.toInt()), 10)
                isClickable = true
                isFocusable = true
                setOnClickListener { handleShortcut(key) }
            }
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
            params.marginEnd = dp(5)
            shortcuts.addView(keyView, params)
        }
        shortcutsScroll.addView(shortcuts)
        root.addView(shortcutsScroll)

        // Command composer.
        val promptRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(6), dp(4))
            background = roundedBackground(intArrayOf(0xFF172A40.toInt(), 0xFF132238.toInt()), 16)
        }
        promptLabel = TextView(this).apply {
            text = prompt
            textSize = 10f
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setTextColor(0xFF86EFAC.toInt())
            maxWidth = dp(110)
        }
        promptRow.addView(promptLabel)
        input = EditText(this).apply {
            setSingleLine(true)
            textSize = 13f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF71819D.toInt())
            hint = "Enter a command…"
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setBackgroundColor(0x00000000)
            setPadding(dp(7), 0, dp(4), 0)
            setOnEditorActionListener { _, actionId, event ->
                val enter = actionId == EditorInfo.IME_ACTION_SEND ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
                if (enter) { submitCommand(); true } else false
            }
        }
        promptRow.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
        val runButton = TextView(this).apply {
            text = "➤"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFF071827.toInt())
            background = roundedBackground(intArrayOf(0xFF7DD3FC.toInt(), 0xFF38BDF8.toInt()), 12)
            setPadding(dp(13), 0, dp(13), 0)
            setOnClickListener { submitCommand() }
        }
        promptRow.addView(runButton, LinearLayout.LayoutParams(dp(44), dp(42)))
        root.addView(promptRow)

        setContentView(root)
    }

    private fun roundedBackground(colors: IntArray, radius: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(radius).toFloat()
        }

    private fun makePill(label: String, foreground: Int, backgroundColor: Int): TextView =
        TextView(this).apply {
            text = label
            textSize = 9f
            letterSpacing = 0.05f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(foreground)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = roundedBackground(intArrayOf(backgroundColor, backgroundColor), 30)
        }

    private fun makeActionButton(
        label: String,
        foreground: Int,
        backgroundColor: Int,
        primary: Boolean,
        action: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        textSize = if (primary) 11f else 10f
        letterSpacing = 0.03f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(foreground)
        setPadding(dp(13), dp(8), dp(13), dp(8))
        background = roundedBackground(intArrayOf(backgroundColor, backgroundColor), 13)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun makeInfoCard(title: String, value: String, accent: Int): Pair<LinearLayout, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(11), dp(10), dp(9))
            background = roundedBackground(intArrayOf(0xFF141F32.toInt(), 0xFF101A2A.toInt()), 15)
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 8f
            letterSpacing = 0.1f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF8295B3.toInt())
        })
        val valueView = TextView(this).apply {
            text = value
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
            setPadding(0, dp(8), 0, 0)
            maxLines = 1
        }
        card.addView(valueView)
        return card to valueView
    }

    private fun updateRuntimeStatus() {
        if (!::runtimeStatus.isInitialized) return
        val ready = ubuntuRuntime.isInstalled()
        runtimeStatus.text = if (ready) "●  READY" else "○  NOT INSTALLED"
        runtimeStatus.setTextColor(if (ready) 0xFF86EFAC.toInt() else 0xFFFBBF24.toInt())
    }

    private fun updateFolderStatus() {
        if (!::folderStatus.isInitialized) return
        val connected = preferences.getString("android_tree_uri", null) != null
        folderStatus.text = if (connected) "●  Connected" else "○  Not connected"
    }

    private fun openAndroidFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, 7402)
    }

    @Deprecated("Use the Activity Result API in a future UI refactor")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7402 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
            preferences.edit()
                .putString("android_tree_uri", uri.toString())
                .remove("android_tree_imported_uri")
                .apply()
            appendLine("Android folder selected. Ubuntu will access it at /mnt/android.")
            appendLine("Changes are synchronized after each command.")
            updateFolderStatus()
        }
    }

    override fun onStart() {
        super.onStart()
        if (terminalReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(TerminalExecutionService.ACTION_OUTPUT)
            addAction(TerminalExecutionService.ACTION_FINISHED)
            // Without this action the UI never receives the install result,
            // leaving the prompt disabled and hiding the actual failure.
            addAction(TerminalExecutionService.ACTION_INSTALL_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(terminalReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(terminalReceiver, filter)
        }
        terminalReceiverRegistered = true
    }

    // Keep the receiver registered while the Activity is stopped so progress
    // output is not discarded merely because the user switches apps.
    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        if (terminalReceiverRegistered) {
            unregisterReceiver(terminalReceiver)
            terminalReceiverRegistered = false
        }
        super.onDestroy()
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
        appendLine("NEXORA 0.3.0 — Android")
        appendLine("Ubuntu profile: $username@$hostname")
        appendLine("Home: /home/$username")
        appendLine("Your Terminal. Your Universe.")
        appendLine("")
        if (ubuntuRuntime.isInstalled()) {
            appendLine("Ubuntu 24.04 runtime: READY")
            appendLine("Linux commands are routed to Ubuntu.")
        } else {
            appendLine("Ubuntu runtime: NOT INSTALLED")
            appendLine("Run install-ubuntu to provision Ubuntu 24.04.")
        }
        appendLine("Type 'help' to see available commands.")
        promptLabel.text = prompt
        updateRuntimeStatus()
        updateFolderStatus()
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
                appendLine("NEXORA: help, version, clear, install-ubuntu, ubuntu-status, exit")
                appendLine("Ubuntu commands: git, apt, bash, python3, ls, cd, pwd, cat, nano, etc.")
            }
            "version" -> appendLine("NEXORA Android 0.3.0")
            "clear", "cls" -> output.text = ""
            "install-ubuntu" -> installUbuntuRuntime()
            "ubuntu-status" -> appendLine(
                if (ubuntuRuntime.isInstalled()) "Ubuntu 24.04 runtime: installed"
                else "Ubuntu runtime: not installed. Run install-ubuntu."
            )
            "exit", "logout" -> appendLine("Command execution uses isolated Ubuntu processes.")
            else -> {
                if (!ubuntuRuntime.isInstalled()) {
                    appendLine("Ubuntu runtime is not installed. Run install-ubuntu first.")
                } else {
                    input.isEnabled = false
                    val serviceIntent = Intent(this, TerminalExecutionService::class.java).apply {
                        putExtra(TerminalExecutionService.EXTRA_COMMAND, line)
                        putExtra(TerminalExecutionService.EXTRA_USERNAME, username)
                        putExtra(TerminalExecutionService.EXTRA_UID, ubuntuUid)
                        putExtra(
                            TerminalExecutionService.EXTRA_TREE_URI,
                            preferences.getString("android_tree_uri", null)
                        )
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent)
                        else startService(serviceIntent)
                    } catch (error: Exception) {
                        input.isEnabled = true
                        appendLine("Could not start background command: ${error.message}")
                    }
                }
            }
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
        appendLine("Starting Ubuntu 24.04 LTS installation in background…")
        appendLine("You can switch apps; keep the NEXORA notification active.")
        input.isEnabled = false
        val serviceIntent = Intent(this, TerminalExecutionService::class.java).apply {
            action = TerminalExecutionService.ACTION_INSTALL_UBUNTU
            putExtra(TerminalExecutionService.EXTRA_USERNAME, username)
            putExtra(TerminalExecutionService.EXTRA_INSTALL_PASSWORD, password)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent)
            else startService(serviceIntent)
        } catch (error: Exception) {
            input.isEnabled = true
            appendLine("Could not start Ubuntu background installer: ${error.message}")
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
