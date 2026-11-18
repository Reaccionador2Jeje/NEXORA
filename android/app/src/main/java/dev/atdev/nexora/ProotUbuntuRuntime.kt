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
    private val ptyHelper = File(app.applicationInfo.nativeLibraryDir, "libnexora-pty.so")
    private val installMarker = File(app.filesDir, "proot/ubuntu-base.sha256")
    // PRoot creates host-side glue and probe directories here. Android app cache is private and writable.
    private val prootTempDir = File(app.cacheDir, "proot-tmp")
    private val sessionDirectory = File(app.filesDir, "proot/session")
    private val sessionCwdFile = File(sessionDirectory, "cwd")

    override fun currentWorkingDirectory(): String {
        val saved = runCatching { sessionCwdFile.readText().trim() }.getOrNull().orEmpty()
        if (!saved.startsWith("/")) return "/root"
        val guestPath = saved.removePrefix("/")
        val directory = File(rootfs, guestPath).canonicalFile
        val rootPath = rootfs.canonicalPath
        return if (directory.isDirectory && (directory.path == rootPath || directory.path.startsWith(rootPath + File.separator))) saved else "/root"
    }

    override fun isInstalled(): Boolean =
        proot.isFile && proot.canRead() && proot.canExecute() &&
            loader.isFile && loader.canRead() && loader.canExecute() &&
            ptyHelper.isFile && ptyHelper.canRead() && ptyHelper.canExecute() &&
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
                check(proot.isFile && proot.canRead() && proot.canExecute() &&
                    loader.isFile && loader.canRead() && loader.canExecute()) {
                    "This APK does not include the ARM64 PRoot runtime and loader. Install the APK produced by NEXORA Android CI."
                }
                check(ptyHelper.isFile && ptyHelper.canRead() && ptyHelper.canExecute()) {
                    "This APK does not include the NEXORA PTY helper. Install the latest Android CI build."
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
                prepareProotTempDir()
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
        onInputReady: (((String) -> Unit) -> Unit),
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
                prepareSessionDirectory()
                val sessionCwd = currentWorkingDirectory()
                val commandWithSession = command + "\n" +
                    "__nexora_status=\$?\n" +
                    "printf '%s' \"\$PWD\" > /tmp/nexora-session/cwd\n" +
                    "exit \"\$__nexora_status\""

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
                    "-b", "${sessionDirectory.absolutePath}:/tmp/nexora-session",
                    "-w", sessionCwd
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
                    "/bin/bash", "-lc", commandWithSession
                )

                prepareProotTempDir()
                check(ptyHelper.isFile && ptyHelper.canExecute()) {
                    "NEXORA PTY helper is missing. Install the APK built by the latest Android CI."
                }
                val process = ProcessBuilder(listOf(ptyHelper.absolutePath) + args)
                    .directory(rootfs)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["PROOT_TMP_DIR"] = prootTempDir.absolutePath
                        environment()["TMPDIR"] = prootTempDir.absolutePath
                    }
                    .start()
                onInputReady { value ->
                    try {
                        process.outputStream.write(value.toByteArray(Charsets.UTF_8))
                        process.outputStream.flush()
                    } catch (_: Exception) {
                        // The process may have exited while the user was typing.
                    }
                }
                InputStreamReader(process.inputStream, Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (count > 0) line(String(buffer, 0, count))
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
    /** Creates persistent host-side state shared with the Ubuntu rootfs. */
    private fun prepareSessionDirectory() {
        check(sessionDirectory.isDirectory || sessionDirectory.mkdirs()) {
            "Cannot create Ubuntu session directory."
        }
        if (!sessionCwdFile.isFile || sessionCwdFile.readText().isBlank()) {
            check(sessionCwdFile.writeText("/root").let { sessionCwdFile.isFile }) {
                "Cannot initialize Ubuntu working directory."
            }
        }
    }

    /** Creates and verifies the host-side temporary directory required by PRoot. */
    private fun prepareProotTempDir() {
        check(prootTempDir.isDirectory || prootTempDir.mkdirs()) {
            "Cannot create PRoot temporary directory in app cache."
        }
        val probe = File(prootTempDir, ".nexora-host-write-check")
        check(probe.writeText("ok").let { probe.isFile && probe.readText() == "ok" }) {
            "Android does not allow writing to PRoot temporary directory: ${prootTempDir.absolutePath}"
        }
        check(probe.delete()) { "Cannot clean PRoot temporary directory write check." }
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
        prepareProotTempDir()
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .redirectOutput(checkLog)
            .apply {
                environment()["PROOT_TMP_DIR"] = prootTempDir.absolutePath
                environment()["TMPDIR"] = prootTempDir.absolutePath
            }
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
            !output.contains("Permission denied", ignoreCase = true) &&
            !output.contains("proot error", ignoreCase = true) &&
            !output.contains("proot warning", ignoreCase = true)) {
            "PRoot filesystem permission self-check failed: ${output.trim()}"
        }
    }


}
