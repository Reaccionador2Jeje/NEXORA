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

/** Runs user-started Ubuntu PRoot installation and commands in the background. */
class TerminalExecutionService : Service() {
    private val notificationId = 4101
    private val channelId = "nexora_terminal_tasks"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val prepareUbuntu = action == ACTION_PREPARE_PROOT_UBUNTU
        val command = intent?.getStringExtra(EXTRA_COMMAND)
        val username = intent?.getStringExtra(EXTRA_USERNAME) ?: "user"
        val hostname = intent?.getStringExtra(EXTRA_HOSTNAME) ?: "nexora"
        val uid = intent?.getIntExtra(EXTRA_UID, 10000) ?: 10000
        val treeUri = intent?.getStringExtra(EXTRA_TREE_URI)?.let(Uri::parse)

        if (!prepareUbuntu && command.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val label = if (prepareUbuntu) {
            "Preparing Ubuntu 24.04 with PRoot…"
        } else {
            "Running Ubuntu command: $command"
        }
        val notification = buildNotification(label, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notificationId, notification)
        }

        if (prepareUbuntu) {
            sendOutput("Preparing the Ubuntu 24.04 ARM64 root filesystem with PRoot.")
            LinuxRuntimeFactory.create(this).install(username, hostname, { message ->
                sendOutput(message)
                updateNotification(message)
            }) { success, message, _ ->
                finishInstall(startId, success, message)
            }
            return START_NOT_STICKY
        }

        Thread({
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
                        updateNotification("Running Ubuntu command")
                    }
                ) { code ->
                    finishCommand(startId, code, treeUri, bridge)
                }
            } catch (error: Exception) {
                sendOutput("nex: " + (error.message ?: "Could not start command"))
                finishCommand(startId, 1, treeUri, bridge)
            }
        }, "nexora-ubuntu-command").start()
        return START_NOT_STICKY
    }

    private fun finishInstall(startId: Int, success: Boolean, message: String) {
        sendBroadcast(Intent(ACTION_PROOT_INSTALL_FINISHED).setPackage(packageName)
            .putExtra(EXTRA_INSTALL_SUCCESS, success)
            .putExtra(EXTRA_INSTALL_MESSAGE, message))
        getSystemService(NotificationManager::class.java).notify(
            notificationId + 1,
            buildNotification(if (success) "Ubuntu PRoot environment ready" else "Ubuntu PRoot preparation failed", false)
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun finishCommand(startId: Int, code: Int, treeUri: Uri?, bridge: AndroidFolderBridge) {
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
        const val ACTION_PREPARE_PROOT_UBUNTU = "dev.atdev.nexora.PREPARE_PROOT_UBUNTU"
        const val ACTION_PROOT_INSTALL_FINISHED = "dev.atdev.nexora.PROOT_INSTALL_FINISHED"
        const val ACTION_EXECUTE_COMMAND = "dev.atdev.nexora.EXECUTE_COMMAND"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_HOSTNAME = "hostname"
        const val EXTRA_UID = "uid"
        const val EXTRA_TREE_URI = "tree_uri"
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_EXIT_CODE = "exit_code"
        const val EXTRA_INSTALL_SUCCESS = "install_success"
        const val EXTRA_INSTALL_MESSAGE = "install_message"
        const val ACTION_OUTPUT = "dev.atdev.nexora.TERMINAL_OUTPUT"
        const val ACTION_FINISHED = "dev.atdev.nexora.TERMINAL_FINISHED"
    }
}
