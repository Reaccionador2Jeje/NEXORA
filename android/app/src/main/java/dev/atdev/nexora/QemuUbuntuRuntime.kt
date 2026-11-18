package dev.atdev.nexora

import android.content.Context
import java.io.File

/**
 * QEMU-backed Ubuntu runtime used by the Android terminal.
 *
 * Installation prepares a verified ARM64 QEMU executable, matching UEFI
 * firmware, the official Ubuntu cloud disk, and its NoCloud seed. Commands
 * are entered through the persistent serial console after qemu-start.
 */
class QemuUbuntuRuntime(context: Context) : LinuxRuntimeEngine {
    private val app = context.applicationContext
    private val vmDirectory = File(app.filesDir, "qemu/vm")

    override fun isInstalled(): Boolean {
        val qemu = File(app.applicationInfo.nativeLibraryDir, "libqemu-system-aarch64.so")
        val disk = File(vmDirectory, "ubuntu-24.04-arm64.qcow2")
        val seed = File(vmDirectory, "cidata.img")
        val firmware = File(app.filesDir, "qemu/firmware/aavmf/AAVMF_CODE.fd")
        val vars = File(vmDirectory, "AAVMF_VARS.fd")
        return qemu.isFile && qemu.canRead() && qemu.canExecute() &&
            disk.isFile && disk.length() > 100L * 1024L * 1024L &&
            seed.isFile && firmware.isFile && vars.isFile
    }

    override fun install(
        user: String,
        hostname: String,
        progress: (String) -> Unit,
        done: (Boolean, String, Int) -> Unit
    ) {
        Thread({
            try {
                progress("Installing standalone QEMU runtime and Ubuntu ARM64…")
                UbuntuVmInstaller(app).install(user, hostname, progress)
                check(isInstalled()) { "QEMU Ubuntu installation did not produce all required VM files." }
                done(true, "Ubuntu 24.04 ARM64 is prepared with QEMU. Run qemu-start to boot it.", 1000)
            } catch (error: Exception) {
                val details = generateSequence(error as Throwable?) { it.cause }
                    .mapIndexed { index, cause ->
                        val label = if (index == 0) "Installation error" else "Caused by"
                        "$label: ${cause.javaClass.simpleName}: ${cause.message ?: "(no message)"}"
                    }
                    .joinToString("\n")
                progress("QEMU installation failed: ${error.message ?: "unknown error"}")
                done(false, details, -1)
            }
        }, "nexora-qemu-install").start()
    }

    override fun execute(
        command: String,
        user: String,
        uid: Int,
        androidShared: File?,
        line: (String) -> Unit,
        done: (Int) -> Unit
    ) {
        line("QEMU Ubuntu is not running. Start the persistent VM with qemu-start, then enter commands in its serial terminal.")
        done(1)
    }
}
