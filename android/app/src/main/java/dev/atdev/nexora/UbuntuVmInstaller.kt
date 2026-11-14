package dev.atdev.nexora

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads the official Ubuntu 24.04 ARM64 cloud disk and prepares its private VM boot media. */
class UbuntuVmInstaller(private val context: Context) {
    data class PreparedVm(
        val diskImage: File,
        val seedImage: File,
        val firmwareCode: File,
        val firmwareVars: File
    )

    fun install(
        username: String,
        hostname: String,
        progress: (String) -> Unit
    ): PreparedVm {
        require(Regex("^[a-z_][a-z0-9_-]{0,31}$").matches(username)) { "Invalid Linux username." }
        require(Regex("^[a-z0-9][a-z0-9-]{0,62}$").matches(hostname)) { "Invalid Linux hostname." }

        val qemuInstaller = QemuBinaryInstaller(context)
        qemuInstaller.installFromAssets()
        val firmware = qemuInstaller.installFirmwareFromAssets()
        val writableVars = qemuInstaller.prepareWritableFirmwareVars(firmware)

        val vmDirectory = File(context.filesDir, "qemu/vm")
        check(vmDirectory.exists() || vmDirectory.mkdirs()) { "Cannot create the private Ubuntu VM directory." }
        val disk = File(vmDirectory, "ubuntu-24.04-arm64.qcow2")
        val checksumMarker = File(vmDirectory, "ubuntu-24.04-arm64.sha256")
        val expectedHash = fetchExpectedImageHash()
        if (disk.isFile && checksumMarker.isFile && checksumMarker.readText().trim() == expectedHash) {
            progress("Ubuntu 24.04 ARM64 disk already downloaded and verified.")
        } else {
            downloadImage(disk, expectedHash, progress)
            checksumMarker.writeText(expectedHash)
        }

        val seed = File(vmDirectory, "cidata.img")
        if (!seed.isFile) {
            progress("Preparing first-boot Ubuntu user and serial auto-login…")
            NoCloudSeedImage.create(seed, username, hostname)
        }

        check(disk.isFile && disk.length() > 100L * 1024L * 1024L) {
            "Ubuntu cloud image is incomplete."
        }
        return PreparedVm(
            diskImage = disk,
            seedImage = seed,
            firmwareCode = firmware.code,
            firmwareVars = writableVars
        )
    }

    private fun fetchExpectedImageHash(): String {
        val manifest = readText("$BASE_URL/SHA256SUMS")
        val line = manifest.lineSequence().firstOrNull {
            it.trim().endsWith(IMAGE_NAME)
        } ?: error("Ubuntu's official SHA256SUMS does not list the ARM64 cloud image.")
        val match = Regex("^([a-fA-F0-9]{64})\\s+\\*?(.+)$").matchEntire(line.trim())
            ?: error("Ubuntu's SHA256SUMS entry is invalid.")
        check(match.groupValues[2].trim() == IMAGE_NAME) { "Ubuntu checksum entry does not match the expected image." }
        return match.groupValues[1].lowercase()
    }

    private fun downloadImage(
        destination: File,
        expectedHash: String,
        progress: (String) -> Unit
    ) {
        val temporary = File(destination.parentFile, destination.name + ".part")
        temporary.delete()
        val connection = (URL("$BASE_URL/$IMAGE_NAME").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "NEXORA-Android/0.4")
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "Ubuntu image download returned HTTP ${connection.responseCode}."
            }
            val total = connection.contentLengthLong
            val required = if (total > 0) total + 256L * 1024L * 1024L else 900L * 1024L * 1024L
            check(destination.parentFile?.usableSpace ?: 0L >= required) {
                "Not enough free storage. NEXORA needs about ${required / (1024 * 1024)} MB free for the Ubuntu VM."
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt()
                            if (percent >= lastPercent + 5) {
                                lastPercent = percent
                                progress("Downloading Ubuntu ARM64: $percent%")
                            }
                        } else if (downloaded % (32L * 1024L * 1024L) < buffer.size) {
                            progress("Downloaded ${downloaded / (1024 * 1024)} MB of Ubuntu image…")
                        }
                    }
                    output.fd.sync()
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            check(actualHash == expectedHash) {
                "Ubuntu image SHA-256 verification failed. The incomplete download was rejected."
            }
            check(temporary.renameTo(destination)) { "Cannot activate the verified Ubuntu disk image." }
            progress("Ubuntu 24.04 ARM64 image downloaded and SHA-256 verified.")
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun readText(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "NEXORA-Android/0.4")
        }
        try {
            check(connection.responseCode in 200..299) {
                "Ubuntu checksum manifest returned HTTP ${connection.responseCode}."
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val BASE_URL = "https://cloud-images.ubuntu.com/releases/noble/release"
        private const val IMAGE_NAME = "ubuntu-24.04-server-cloudimg-arm64.img"
    }
}
