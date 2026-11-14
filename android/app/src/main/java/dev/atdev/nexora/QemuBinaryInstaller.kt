package dev.atdev.nexora

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/** Extracts and verifies the standalone Android/Bionic QEMU bundle shipped in the APK. */
class QemuBinaryInstaller(private val context: Context) {
    data class InstalledQemu(
        val executable: File,
        val libraryDirectory: File
    )

    @Synchronized
    fun installFromAssets(): InstalledQemu {
        check(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
            "This QEMU build currently supports ARM64 Android devices only."
        }

        val assetPath = "qemu/arm64-v8a"
        val names = context.assets.list(assetPath)?.toSet().orEmpty()
        check("qemu-system-aarch64" in names && "SHA256SUMS" in names) {
            "The QEMU runtime is not included in this APK. Install the build produced by the QEMU workflow."
        }

        val parent = File(context.filesDir, "qemu")
        check(parent.exists() || parent.mkdirs()) { "Cannot create the private QEMU directory." }
        val staging = File(parent, "arm64-v8a.installing")
        val destination = File(parent, "arm64-v8a")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Cannot prepare the QEMU installation directory." }

        try {
            val installNames = names.filter {
                it == "qemu-system-aarch64" || it == "SHA256SUMS" || it.endsWith(".so") || it.contains(".so.")
            }
            for (name in installNames) {
                require(name.matches(Regex("[A-Za-z0-9_.+-]+"))) { "Invalid QEMU asset name." }
                context.assets.open("$assetPath/$name").use { input ->
                    FileOutputStream(File(staging, name)).use { output -> input.copyTo(output) }
                }
            }

            verifyChecksums(staging)
            val executable = File(staging, "qemu-system-aarch64")
            check(executable.setReadable(true, true) || executable.canRead()) {
                "Cannot set QEMU executable read permission."
            }
            check(executable.setExecutable(true, true) || executable.canExecute()) {
                "Android refused permission to execute QEMU from app-private storage."
            }

            val previous = File(parent, "arm64-v8a.previous")
            previous.deleteRecursively()
            if (destination.exists()) {
                check(destination.renameTo(previous)) { "Cannot replace the previous QEMU runtime safely." }
            }
            if (!staging.renameTo(destination)) {
                if (previous.exists()) previous.renameTo(destination)
                error("Cannot activate the verified QEMU runtime.")
            }
            previous.deleteRecursively()

            val installedExecutable = File(destination, "qemu-system-aarch64")
            check(installedExecutable.canExecute()) { "QEMU is not executable after installation." }
            return InstalledQemu(installedExecutable, destination)
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    /** Starts QEMU's version probe with its private library path; this does not boot a VM. */
    fun smokeTest(installed: InstalledQemu): String {
        val process = ProcessBuilder(installed.executable.absolutePath, "--version")
            .redirectErrorStream(true)
            .apply {
                environment()["LD_LIBRARY_PATH"] = installed.libraryDirectory.absolutePath
            }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "QEMU failed its Android linker test (exit $exitCode): $output"
        }
        check(output.contains("QEMU emulator version", ignoreCase = true)) {
            "Unexpected QEMU version response: $output"
        }
        return output.lineSequence().firstOrNull().orEmpty()
    }

    private fun verifyChecksums(directory: File) {
        val manifest = File(directory, "SHA256SUMS")
        check(manifest.isFile) { "QEMU checksum manifest is missing." }
        val entries = manifest.readLines().filter { it.isNotBlank() }
        check(entries.isNotEmpty()) { "QEMU checksum manifest is empty." }
        val verified = mutableSetOf<String>()
        for (line in entries) {
            val match = Regex("^([a-fA-F0-9]{64})\\s+\\*?(.+)$").matchEntire(line.trim())
                ?: error("Invalid QEMU checksum entry.")
            val expected = match.groupValues[1].lowercase()
            val name = match.groupValues[2].removePrefix("./").trim()
            require(name.matches(Regex("[A-Za-z0-9_.+-]+"))) { "Invalid file name in QEMU checksum manifest." }
            val file = File(directory, name)
            check(file.isFile) { "QEMU runtime file is missing: $name" }
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == expected) { "QEMU runtime checksum mismatch: $name" }
            verified += name
        }
        check("qemu-system-aarch64" in verified) { "QEMU executable is not covered by the checksum manifest." }
    }
}
