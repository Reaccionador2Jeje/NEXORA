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
    private val loader = File(app.applicationInfo.nativeLibraryDir, "libproot-loader.so")
    private val installMarker = File(app.filesDir, "proot/ubuntu-base.sha256")

    override fun isInstalled(): Boolean =
        proot.isFile && proot.canRead() && proot.canExecute() && loader.isFile && loader.canExecute() &&
            File(rootfs, "usr/bin/bash").isFile &&
            File(rootfs, "etc/os-release").isFile && installMarker.isFile

    override fun install(
        user: String,
        hostname: String,
        progress: (String) -> Unit,
        done: (Boolean, String, Int) -> Unit
    ) {
        Thread({
            try {
                check(proot.isFile && proot.canExecute() && loader.isFile && loader.canExecute()) {
                    "This APK does not include the ARM64 PRoot runtime. Install the APK produced by NEXORA Android CI."
                }
                val version = ProcessBuilder(proot.absolutePath, "--version")
                    .redirectErrorStream(true)
                    .start()
                if (!version.waitFor(10, TimeUnit.SECONDS)) {
                    version.destroyForcibly()
                    error("The packaged PRoot executable did not respond to --version.")
                }
                val versionText = version.inputStream.bufferedReader().use { it.readText() }.trim()
                check(version.exitValue() == 0) {
                    "The packaged PRoot executable failed its startup check: $versionText"
                }
                progress("PRoot runtime verified: $versionText")
                progress("Preparing Ubuntu 24.04 ARM64 with PRoot…")
                UbuntuRootfsInstaller(app).install(progress)
                check(isInstalled()) { "Ubuntu root filesystem or packaged PRoot binary is missing." }
                verifyRootfsPermissions()
                done(true, "Ubuntu 24.04 ARM64 is ready through PRoot. Filesystem permission checks passed.", 1000)
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
    /**
     * Exercises the write locations commonly used by shells and package managers.
     * This catches Android host-permission problems before the terminal is marked ready.
     */
    private fun verifyRootfsPermissions() {
        val checkLog = File(app.cacheDir, "proot-permission-check.log")
        val command = "set -eu; test \"\$(id -u)\" = 0; " +
            "touch /tmp/.nexora-proot-write-check; " +
            "mkdir -p /var/lib/nexora-proot-write-check; " +
            "printf ok > /var/lib/nexora-proot-write-check/check; " +
            "printf ok > /root/.nexora-proot-write-check; " +
            "rm -rf /var/lib/nexora-proot-write-check /tmp/.nexora-proot-write-check " +
            "/root/.nexora-proot-write-check; " +
            "echo NEXORA_PROOT_PERMISSION_CHECK_OK"
        val args = listOf(
            proot.absolutePath, "--kill-on-exit", "--link2symlink", "-0",
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/system", "-b", "/apex",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm", "LANG=C.UTF-8",
            "/bin/bash", "-lc", command
        )
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .redirectOutput(checkLog)
            .start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            checkLog.delete()
            error("PRoot filesystem permission self-check timed out.")
        }
        val output = checkLog.takeIf { it.isFile }?.readText().orEmpty()
        checkLog.delete()
        check(process.exitValue() == 0 &&
            output.contains("NEXORA_PROOT_PERMISSION_CHECK_OK") &&
            !output.contains("Permission denied", ignoreCase = true)) {
            "PRoot filesystem permission self-check failed: ${output.trim()}"
        }
    }


}
