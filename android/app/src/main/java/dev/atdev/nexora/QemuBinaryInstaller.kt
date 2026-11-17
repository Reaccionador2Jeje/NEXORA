package dev.atdev.nexora

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.security.MessageDigest

/** Extracts and verifies the standalone Android/Bionic QEMU bundle shipped in the APK. */
class QemuBinaryInstaller(private val context: Context) {
    data class InstalledQemu(
        val executable: File,
        val libraryDirectory: File,
        val temporaryDirectory: File
    )

    data class InstalledFirmware(
        val code: File,
        val varsTemplate: File
    )

    @Synchronized
    fun installFromAssets(): InstalledQemu {
        check(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
            "This QEMU build currently supports ARM64 Android devices only."
        }

        val assetPath = "qemu/arm64-v8a"
        val names = context.assets.list(assetPath)?.toSet().orEmpty()
        check("SHA256SUMS" in names) {
            "The QEMU runtime is not included in this APK. Install the build produced by the QEMU workflow."
        }

        val parent = File(context.filesDir, "qemu")
        check(parent.exists() || parent.mkdirs()) { "Cannot create the private QEMU directory." }
        val staging = File(parent, "arm64-v8a.installing")
        val destination = File(parent, "arm64-v8a")
        // Android 10+ blocks execve() for binaries written into app-writable filesDir.
        // Package QEMU in jniLibs so PackageManager extracts it into nativeLibraryDir.
        val executable = File(context.applicationInfo.nativeLibraryDir, "libqemu-system-aarch64.so")
        check(executable.isFile && executable.canRead() && executable.canExecute()) {
            "QEMU executable is missing from the APK native library directory. Rebuild the APK with the QEMU jniLibs payload."
        }
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Cannot prepare the QEMU installation directory." }

        try {
            val installNames = names.filter {
                it == "SHA256SUMS" || it.endsWith(".so") || it.contains(".so.")
            }
            for (name in installNames) {
                require(name.matches(Regex("[A-Za-z0-9_.+-]+"))) { "Invalid QEMU asset name." }
                context.assets.open("$assetPath/$name").use { input ->
                    FileOutputStream(File(staging, name)).use { output -> input.copyTo(output) }
                }
            }

            verifyChecksums(staging)
            verifyChecksums(staging, externalFiles = mapOf("qemu-system-aarch64" to executable))

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
            val temporaryDirectory = context.cacheDir
            check(temporaryDirectory.isDirectory && temporaryDirectory.canWrite()) {
                "The app-private temporary directory is not writable for QEMU shared memory."
            }
            return InstalledQemu(installedExecutable, destination, temporaryDirectory)
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    /** Extracts the matching AAVMF UEFI pair included by the QEMU build workflow. */
    @Synchronized
    fun installFirmwareFromAssets(): InstalledFirmware {
        val assetPath = "qemu/arm64-v8a/firmware"
        val names = context.assets.list(assetPath)?.toSet().orEmpty()
        check("AAVMF_CODE.fd.gz" in names && "AAVMF_VARS.fd.gz" in names && "SHA256SUMS" in names) {
            "The ARM64 UEFI firmware is not included in this APK."
        }

        val parent = File(context.filesDir, "qemu/firmware")
        check(parent.exists() || parent.mkdirs()) { "Cannot create the private firmware directory." }
        val staging = File(parent, "installing")
        val destination = File(parent, "aavmf")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Cannot prepare the UEFI firmware directory." }
        try {
            for (name in listOf("AAVMF_CODE.fd.gz", "AAVMF_VARS.fd.gz", "SHA256SUMS")) {
                context.assets.open("$assetPath/$name").use { input ->
                    FileOutputStream(File(staging, name)).use { output -> input.copyTo(output) }
                }
            }
            verifyChecksums(staging, "AAVMF_CODE.fd.gz")
            check(File(staging, "AAVMF_VARS.fd.gz").isFile) { "UEFI variable store is missing." }
            for (name in listOf("AAVMF_CODE.fd", "AAVMF_VARS.fd")) {
                GZIPInputStream(File(staging, "$name.gz").inputStream()).use { input ->
                    FileOutputStream(File(staging, name)).use { output -> input.copyTo(output) }
                }
                check(File(staging, name).length() >= 1024L * 1024L) {
                    "UEFI firmware image is unexpectedly small: $name"
                }
            }
            val previous = File(parent, "aavmf.previous")
            previous.deleteRecursively()
            if (destination.exists()) {
                check(destination.renameTo(previous)) { "Cannot replace the previous UEFI firmware safely." }
            }
            if (!staging.renameTo(destination)) {
                if (previous.exists()) previous.renameTo(destination)
                error("Cannot activate the verified UEFI firmware.")
            }
            previous.deleteRecursively()
            return InstalledFirmware(
                File(destination, "AAVMF_CODE.fd"),
                File(destination, "AAVMF_VARS.fd")
            )
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    /** Creates or reuses the writable UEFI variable store for the persistent VM. */
    fun prepareWritableFirmwareVars(firmware: InstalledFirmware): File {
        val vmDirectory = File(context.filesDir, "qemu/vm")
        check(vmDirectory.exists() || vmDirectory.mkdirs()) { "Cannot create the VM directory." }
        val writable = File(vmDirectory, "AAVMF_VARS.fd")
        if (!writable.exists()) {
            FileInputStream(firmware.varsTemplate).use { input ->
                FileOutputStream(writable).use { output -> input.copyTo(output) }
            }
        }
        check(writable.isFile && writable.length() == firmware.varsTemplate.length()) {
            "The writable UEFI variable store is incomplete."
        }
        return writable
    }

    /** Starts QEMU's version probe with its private library path; this does not boot a VM. */
    fun smokeTest(installed: InstalledQemu): String {
        val process = ProcessBuilder(installed.executable.absolutePath, "--version")
            .redirectErrorStream(true)
            .apply {
                environment()["LD_LIBRARY_PATH"] = installed.libraryDirectory.absolutePath
                environment()["TMPDIR"] = installed.temporaryDirectory.absolutePath
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

    private fun verifyChecksums(
        directory: File,
        requiredFile: String = "qemu-system-aarch64",
        externalFiles: Map<String, File> = emptyMap()
    ) {
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
            val file = externalFiles[name] ?: File(directory, name)
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
        check(requiredFile in verified) { "Required runtime file is not covered by the checksum manifest: $requiredFile" }
    }
}
