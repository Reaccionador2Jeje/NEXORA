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

/** Runs a user-started Ubuntu command while NEXORA is in the background. */
class TerminalExecutionService : Service() {
    private val notificationId = 4101
    private val channelId = "nexora_terminal_tasks"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.getStringExtra(EXTRA_COMMAND)
        val username = intent?.getStringExtra(EXTRA_USERNAME) ?: "user"
        val uid = intent?.getIntExtra(EXTRA_UID, 10000) ?: 10000
        val treeUri = intent?.getStringExtra(EXTRA_TREE_URI)?.let(Uri::parse)
        if (command.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = buildNotification("Running: $command", true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notificationId, notification)
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
                UbuntuRuntime(this).execute(
                    command,
                    username,
                    uid,
                    bridge.sharedDirectory,
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
