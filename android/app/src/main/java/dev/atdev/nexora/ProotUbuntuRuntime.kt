package dev.atdev.nexora

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Runs the Ubuntu ARM64 root filesystem directly on Android's Linux kernel
 * through PRoot. This is a userspace environment, not a virtual machine.
 */
class ProotUbuntuRuntime(context: Context) : LinuxRuntimeEngine {
    private val app = context.applicationContext
    private val rootfs = File(app.filesDir, "proot/ubuntu")
    private val proot = File(app.applicationInfo.nativeLibraryDir, "libproot.so")

    override fun isInstalled(): Boolean =
        proot.isFile && proot.canRead() && proot.canExecute() &&
            File(rootfs, "usr/bin/bash").isFile &&
            File(rootfs, "etc/os-release").isFile

    override fun install(
        user: String,
        hostname: String,
        progress: (String) -> Unit,
        done: (Boolean, String, Int) -> Unit
    ) {
        Thread({
            try {
                progress("Preparing Ubuntu 24.04 ARM64 with PRoot…")
                UbuntuRootfsInstaller(app).install(progress)
                check(isInstalled()) { "Ubuntu root filesystem or packaged PRoot binary is missing." }
                done(true, "Ubuntu 24.04 ARM64 is ready through PRoot. Run Linux commands directly.", 1000)
            } catch (error: Exception) {
                done(false, error.message ?: "Ubuntu PRoot installation failed.", -1)
            }
        }, "nexora-proot-install").start()
    }

    override fun execute(
        command: String,
        user: String,
        uid: Int,
        androidShared: File?,
        line: (String) -> Unit,
        done: (Int) -> Unit
    ) {
        if (!isInstalled()) {
            line("Ubuntu is not installed. Run install-ubuntu first.")
            done(1)
            return
        }

        Thread({
            var exitCode = 1
            try {
                val args = mutableListOf(
                    proot.absolutePath,
                    "--kill-on-exit",
                    "--link2symlink",
                    "-0",
                    "-r", rootfs.absolutePath,
                    "-b", "/dev",
                    "-b", "/proc",
                    "-b", "/sys",
                    "-b", "/system",
                    "-b", "/apex",
                    "-w", "/root"
                )
                if (androidShared != null && androidShared.isDirectory) {
                    args += listOf("-b", "${androidShared.absolutePath}:/mnt/android")
                }
                args += listOf(
                    "/usr/bin/env", "-i",
                    "HOME=/root",
                    "USER=root",
                    "LOGNAME=root",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "TERM=xterm-256color",
                    "LANG=C.UTF-8",
                    "HOSTNAME=nexora",
                    "/bin/bash", "-lc", command
                )

                val process = ProcessBuilder(args)
                    .directory(rootfs)
                    .redirectErrorStream(true)
                    .start()
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    while (true) {
                        val output = reader.readLine() ?: break
                        line(output)
                    }
                }
                exitCode = process.waitFor()
            } catch (error: Exception) {
                line("proot: ${error.message ?: "could not execute command"}")
            } finally {
                done(exitCode)
            }
        }, "nexora-proot-command").start()
    }
}
