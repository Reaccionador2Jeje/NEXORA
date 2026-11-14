package dev.atdev.nexora

import java.io.File
import java.io.IOException

/**
 * Owns one headless ARM64 QEMU process and exposes its serial stdio as a byte stream.
 *
 * The caller must provide a bootable guest disk, read-only UEFI code image, and a
 * private writable copy of the UEFI variable store. This controller intentionally
 * does not guess or download firmware and never modifies the legacy Ubuntu rootfs.
 */
class QemuVmController(
    private val qemu: QemuBinaryInstaller.InstalledQemu
) {
    @Volatile private var process: Process? = null
    private val processLock = Any()

    val isRunning: Boolean
        get() = process?.isAlive == true

    @Throws(IOException::class)
    fun start(
        diskImage: File,
        firmwareCode: File,
        firmwareVars: File,
        seedImage: File? = null,
        memoryMb: Int = 1024,
        cpuCount: Int = 2,
        onOutput: (String) -> Unit,
        onExit: (Int) -> Unit
    ) {
        synchronized(processLock) {
            check(process?.isAlive != true) { "The QEMU virtual machine is already running." }
            require(diskImage.isFile && diskImage.canRead()) { "Ubuntu virtual disk is missing or unreadable." }
            require(firmwareCode.isFile && firmwareCode.canRead()) { "ARM64 UEFI firmware code is missing." }
            require(firmwareVars.isFile && firmwareVars.canRead()) { "Writable UEFI variables image is missing." }
            require(seedImage == null || (seedImage.isFile && seedImage.canRead())) {
                "The cloud-init seed disk is missing or unreadable."
            }
            require(memoryMb in 512..4096) { "VM memory must be between 512 and 4096 MB." }
            require(cpuCount in 1..4) { "VM CPU count must be between 1 and 4." }

            val command = mutableListOf(
                qemu.executable.absolutePath,
                "-machine", "virt",
                "-cpu", "cortex-a72",
                "-smp", cpuCount.toString(),
                "-m", memoryMb.toString(),
                "-drive", "if=pflash,format=raw,readonly=on,file=${firmwareCode.absolutePath}",
                "-drive", "if=pflash,format=raw,file=${firmwareVars.absolutePath}",
                "-drive", "if=none,id=system,format=qcow2,file=${diskImage.absolutePath}",
                "-device", "virtio-blk-device,drive=system",
                "-display", "none",
                "-serial", "stdio",
                "-monitor", "none",
                "-no-reboot"
            )
            if (seedImage != null) {
                command.addAll(listOf(
                    "-drive", "if=none,id=seed,format=raw,file=${seedImage.absolutePath}",
                    "-device", "virtio-blk-device,drive=seed"
                ))
            }
            val child = ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = qemu.libraryDirectory.absolutePath
                }
                .start()
            process = child

            Thread({
                try {
                    child.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> onOutput(line) }
                    }
                } catch (error: Exception) {
                    onOutput("NEXORA QEMU output stream: ${error.message ?: "closed"}")
                }
            }, "nexora-qemu-output").apply { isDaemon = true; start() }

            Thread({
                val exitCode = try {
                    child.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    -1
                }
                synchronized(processLock) {
                    if (process === child) process = null
                }
                onExit(exitCode)
            }, "nexora-qemu-exit").apply { isDaemon = true; start() }
        }
    }

    @Throws(IOException::class)
    fun sendSerial(text: String) {
        val child = process
        check(child?.isAlive == true) { "The QEMU virtual machine is not running." }
        child.outputStream.apply {
            write(text.toByteArray(Charsets.UTF_8))
            flush()
        }
    }

    fun stop() {
        val child = process ?: return
        if (child.isAlive) {
            try {
                child.outputStream.write(byteArrayOf(0x04))
                child.outputStream.flush()
            } catch (_: Exception) {
                // Destroy below if the guest does not shut down on EOF.
            }
            if (!child.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                child.destroy()
            }
            if (!child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                child.destroyForcibly()
            }
        }
        synchronized(processLock) {
            if (process === child) process = null
        }
    }
}
