package dev.atdev.nexora

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import java.io.File

/** Runs a user-started Ubuntu command while NEXORA is in the background. */
class TerminalExecutionService : Service() {
    private val notificationId = 4101
    private val channelId = "nexora_terminal_tasks"
    @Volatile private var qemuController: QemuVmController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        qemuController?.stop()
        qemuController = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_QUERY_QEMU_VM) {
            val running = qemuController?.isRunning == true
            sendBroadcast(Intent(
                if (running) ACTION_QEMU_VM_STARTED else ACTION_QEMU_VM_STOPPED
            ).setPackage(packageName).putExtra(EXTRA_QEMU_VM_EXIT, 0))
            return START_NOT_STICKY
        }
        if (action == ACTION_QEMU_VM_INPUT) {
            val text = intent?.getStringExtra(EXTRA_QEMU_INPUT).orEmpty()
            try {
                qemuController?.sendSerial(text) ?: sendOutput("QEMU VM is not running.")
            } catch (error: Exception) {
                sendOutput("QEMU input failed: ${error.message ?: "unknown error"}")
            }
            return START_NOT_STICKY
        }
        if (action == ACTION_STOP_QEMU_VM) {
            val controller = qemuController
            if (controller == null) {
                sendBroadcast(Intent(ACTION_QEMU_VM_STOPPED).setPackage(packageName)
                    .putExtra(EXTRA_QEMU_VM_EXIT, 0))
            } else {
                controller.stop()
            }
            return START_NOT_STICKY
        }

        val installUbuntu = action == ACTION_INSTALL_UBUNTU
        val prepareQemuUbuntu = action == ACTION_PREPARE_QEMU_UBUNTU
        val startQemuVm = action == ACTION_START_QEMU_VM
        val command = intent?.getStringExtra(EXTRA_COMMAND)
        val username = intent?.getStringExtra(EXTRA_USERNAME) ?: "user"
        val hostname = intent?.getStringExtra(EXTRA_HOSTNAME) ?: "nexora"
        val uid = intent?.getIntExtra(EXTRA_UID, 10000) ?: 10000
        val treeUri = intent?.getStringExtra(EXTRA_TREE_URI)?.let(Uri::parse)
        if (!installUbuntu && !prepareQemuUbuntu && !startQemuVm && command.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val taskLabel = when {
            installUbuntu -> "Installing legacy Ubuntu userspace…"
            prepareQemuUbuntu -> "Preparing Ubuntu 24.04 ARM64 VM…"
            startQemuVm -> "Starting Ubuntu virtual machine…"
            else -> "Running: $command"
        }
        val notification = buildNotification(taskLabel, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notificationId, notification)
        }

        if (prepareQemuUbuntu) {
            sendOutput("Preparing independent QEMU Ubuntu VM. The download is approximately 600 MB.")
            Thread {
                try {
                    UbuntuVmInstaller(this).install(username, hostname) { message ->
                        sendOutput(message)
                        updateNotification(message)
                    }
                    finishQemuInstall(startId, true, "Ubuntu 24.04 ARM64 VM and boot media are ready.")
                } catch (error: Exception) {
                    finishQemuInstall(
                        startId,
                        false,
                        error.message ?: "Could not prepare the QEMU Ubuntu VM."
                    )
                }
            }.start()
            return START_NOT_STICKY
        }

        if (startQemuVm) {
            startQemuVirtualMachine(startId)
            return START_NOT_STICKY
        }

        if (installUbuntu) {
            val password = intent?.getStringExtra(EXTRA_INSTALL_PASSWORD)
            if (password.isNullOrEmpty()) {
                sendOutput("Ubuntu installer: password is missing.")
                finishInstall(startId, false, "Password missing", -1)
                return START_NOT_STICKY
            }
            sendOutput("Legacy Ubuntu installation running in background.")
            LinuxRuntimeFactory.create(this).install(username, password, { message ->
                sendOutput(message)
                updateNotification(message)
            }) { success, message, installedUid ->
                finishInstall(startId, success, message, installedUid)
            }
            return START_NOT_STICKY
        }

        Thread {
            val bridge = AndroidFolderBridge(this)
            try {
                if (treeUri != null) {
                    val prefs = getSharedPreferences("nexora", MODE_PRIVATE)
                    val importedUri = prefs.getString("android_tree_imported_uri", null)
                    if (importedUri != treeUri.toString()) {
                        sendOutput("Importing selected Android folder into /mnt/android…")
                        bridge.importTree(treeUri)
                        prefs.edit().putString("android_tree_imported_uri", treeUri.toString()).apply()
                    }
                }
                LinuxRuntimeFactory.create(this).execute(
                    command.orEmpty(),
                    username,
                    uid,
                    if (treeUri != null) bridge.sharedDirectory else null,
                    { line ->
                        sendOutput(line)
                        updateNotification("Running: $command")
                    }
                ) { code ->
                    finish(startId, code, treeUri, bridge)
                }
            } catch (error: Exception) {
                sendOutput("nex: " + (error.message ?: "Could not start command"))
                finish(startId, 1, treeUri, bridge)
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun startQemuVirtualMachine(startId: Int) {
        if (qemuController?.isRunning == true) {
            sendBroadcast(Intent(ACTION_QEMU_VM_STARTED).setPackage(packageName))
            updateNotification("Ubuntu VM is running")
            return
        }
        Thread {
            try {
                val installer = QemuBinaryInstaller(this)
                val qemu = installer.installFromAssets()
                sendOutput(installer.smokeTest(qemu))
                val firmware = installer.installFirmwareFromAssets()
                val writableVars = installer.prepareWritableFirmwareVars(firmware)
                val vmDirectory = File(filesDir, "qemu/vm")
                val disk = File(vmDirectory, "ubuntu-24.04-arm64.qcow2")
                val seed = File(vmDirectory, "cidata.img")
                check(disk.isFile && seed.isFile) { "Run qemu-install before qemu-start." }

                val controller = QemuVmController(qemu)
                qemuController = controller
                controller.start(
                    diskImage = disk,
                    firmwareCode = firmware.code,
                    firmwareVars = writableVars,
                    seedImage = seed,
                    memoryMb = 1024,
                    cpuCount = 2,
                    onOutput = { chunk ->
                        sendQemuSerialOutput(chunk)
                    },
                    onExit = { code ->
                        qemuController = null
                        sendOutput("QEMU virtual machine stopped (exit $code).")
                        sendBroadcast(Intent(ACTION_QEMU_VM_STOPPED).setPackage(packageName)
                            .putExtra(EXTRA_QEMU_VM_EXIT, code))
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                )
                sendBroadcast(Intent(ACTION_QEMU_VM_STARTED).setPackage(packageName))
                sendOutput("QEMU process started. Ubuntu is booting on the serial console…")
                updateNotification("Ubuntu VM is running")
            } catch (error: Exception) {
                qemuController = null
                val message = error.message ?: "Could not start QEMU."
                sendOutput("QEMU startup failed: $message")
                sendBroadcast(Intent(ACTION_QEMU_VM_STOPPED).setPackage(packageName)
                    .putExtra(EXTRA_QEMU_VM_EXIT, -1))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }.start()
    }

    private fun finishQemuInstall(startId: Int, success: Boolean, message: String) {
        sendBroadcast(Intent(ACTION_QEMU_INSTALL_FINISHED).setPackage(packageName)
            .putExtra(EXTRA_QEMU_INSTALL_SUCCESS, success)
            .putExtra(EXTRA_QEMU_INSTALL_MESSAGE, message))
        getSystemService(NotificationManager::class.java).notify(
            notificationId + 1,
            buildNotification(if (success) "Ubuntu VM prepared" else "Ubuntu VM preparation failed", false)
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun finishInstall(startId: Int, success: Boolean, message: String, uid: Int) {
        sendBroadcast(Intent(ACTION_INSTALL_FINISHED).setPackage(packageName)
            .putExtra(EXTRA_INSTALL_SUCCESS, success)
            .putExtra(EXTRA_INSTALL_MESSAGE, message)
            .putExtra(EXTRA_INSTALL_UID, uid))
        getSystemService(NotificationManager::class.java).notify(
            notificationId + 1,
            buildNotification(if (success) "Ubuntu installed successfully" else "Ubuntu installation failed", false)
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun finish(startId: Int, code: Int, treeUri: Uri?, bridge: AndroidFolderBridge) {
        try {
            if (treeUri != null) {
                bridge.exportTree(treeUri)
                sendOutput("Android folder synchronized.")
            }
        } catch (error: Exception) {
            sendOutput("nex: Android folder sync failed: " + (error.message ?: "unknown error"))
        }
        sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName).putExtra(EXTRA_EXIT_CODE, code))
        getSystemService(NotificationManager::class.java).notify(
            notificationId + 1,
            buildNotification("Command finished (exit $code)", false)
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun sendQemuSerialOutput(chunk: String) {
        sendBroadcast(Intent(ACTION_QEMU_SERIAL_OUTPUT).setPackage(packageName)
            .putExtra(EXTRA_QEMU_SERIAL_TEXT, chunk))
    }

    private fun sendOutput(line: String) {
        sendBroadcast(Intent(ACTION_OUTPUT).setPackage(packageName).putExtra(EXTRA_OUTPUT, line))
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(notificationId, buildNotification(message, true))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                channelId,
                "NEXORA terminal",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows Ubuntu commands running in the background" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String, ongoing: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("NEXORA")
                .setContentText(message.take(120))
                .setContentIntent(openApp)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("NEXORA")
                .setContentText(message.take(120))
                .setContentIntent(openApp)
                .setOngoing(ongoing)
                .build()
        }
    }

    companion object {
        const val ACTION_INSTALL_UBUNTU = "dev.atdev.nexora.INSTALL_UBUNTU"
        const val ACTION_PREPARE_QEMU_UBUNTU = "dev.atdev.nexora.PREPARE_QEMU_UBUNTU"
        const val ACTION_START_QEMU_VM = "dev.atdev.nexora.START_QEMU_VM"
        const val ACTION_STOP_QEMU_VM = "dev.atdev.nexora.STOP_QEMU_VM"
        const val ACTION_QEMU_VM_INPUT = "dev.atdev.nexora.QEMU_VM_INPUT"
        const val ACTION_QUERY_QEMU_VM = "dev.atdev.nexora.QUERY_QEMU_VM"
        const val ACTION_QEMU_INSTALL_FINISHED = "dev.atdev.nexora.QEMU_INSTALL_FINISHED"
        const val ACTION_QEMU_VM_STARTED = "dev.atdev.nexora.QEMU_VM_STARTED"
        const val ACTION_QEMU_VM_STOPPED = "dev.atdev.nexora.QEMU_VM_STOPPED"
        const val ACTION_QEMU_SERIAL_OUTPUT = "dev.atdev.nexora.QEMU_SERIAL_OUTPUT"
        const val EXTRA_QEMU_SERIAL_TEXT = "qemu_serial_text"
        const val EXTRA_HOSTNAME = "hostname"
        const val EXTRA_QEMU_INPUT = "qemu_input"
        const val EXTRA_QEMU_INSTALL_SUCCESS = "qemu_install_success"
        const val EXTRA_QEMU_INSTALL_MESSAGE = "qemu_install_message"
        const val EXTRA_QEMU_VM_EXIT = "qemu_vm_exit"
        const val ACTION_INSTALL_FINISHED = "dev.atdev.nexora.INSTALL_FINISHED"
        const val EXTRA_INSTALL_PASSWORD = "install_password"
        const val EXTRA_INSTALL_SUCCESS = "install_success"
        const val EXTRA_INSTALL_MESSAGE = "install_message"
        const val EXTRA_INSTALL_UID = "install_uid"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_UID = "uid"
        const val EXTRA_TREE_URI = "tree_uri"
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_EXIT_CODE = "exit_code"
        const val ACTION_OUTPUT = "dev.atdev.nexora.TERMINAL_OUTPUT"
        const val ACTION_FINISHED = "dev.atdev.nexora.TERMINAL_FINISHED"
    }
}
