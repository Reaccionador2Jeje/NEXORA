package dev.atdev.nexora

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/** Downloads and safely extracts the official Ubuntu Base ARM64 root filesystem. */
class UbuntuRootfsInstaller(context: Context) {
    private val app = context.applicationContext
    private val installDir = File(app.filesDir, "proot")
    private val rootfs = File(installDir, "ubuntu")
    private val archive = File(installDir, IMAGE_NAME)
    private val marker = File(installDir, "ubuntu-base.sha256")

    fun install(progress: (String) -> Unit) {
        check(installDir.exists() || installDir.mkdirs()) { "Cannot create the PRoot workspace." }
        val expected = fetchExpectedHash()
        if (File(rootfs, "usr/bin/bash").isFile && marker.isFile &&
            marker.readText().trim() == expected) {
            progress("Ubuntu Base 24.04 ARM64 is already installed and verified.")
            return
        }

        download(expected, progress)
        progress("Extracting Ubuntu Base into the private app workspace…")
        val staging = File(installDir, "ubuntu.new")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Cannot create the temporary Ubuntu rootfs." }
        try {
            extractArchive(staging)
            check(File(staging, "usr/bin/bash").isFile) { "Ubuntu archive is missing /usr/bin/bash." }
            check(File(staging, "etc/os-release").isFile) { "Ubuntu archive is missing /etc/os-release." }
            configureRootfs(staging)
            val backup = File(installDir, "ubuntu.previous")
            backup.deleteRecursively()
            if (rootfs.exists()) {
                check(rootfs.renameTo(backup)) { "Could not preserve the previous Ubuntu rootfs." }
            }
            if (!staging.renameTo(rootfs)) {
                if (backup.exists()) backup.renameTo(rootfs)
                error("Could not activate the extracted Ubuntu rootfs.")
            }
            backup.deleteRecursively()
            File(rootfs, "root").mkdirs()
            File(rootfs, "mnt/android").mkdirs()
            marker.writeText(expected)
            progress("Ubuntu Base 24.04 ARM64 installed and SHA-256 verified.")
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun fetchExpectedHash(): String {
        val manifest = readText("$BASE_URL/SHA256SUMS")
        val line = manifest.lineSequence().firstOrNull { it.trim().endsWith(IMAGE_NAME) }
            ?: error("Ubuntu's official SHA256SUMS does not list the ARM64 rootfs.")
        val match = Regex("^([a-fA-F0-9]{64})\\s+\\*?(.+)$").matchEntire(line.trim())
            ?: error("Ubuntu's SHA256SUMS entry is invalid.")
        check(match.groupValues[2].trim() == IMAGE_NAME) { "Ubuntu checksum entry does not match the expected rootfs." }
        return match.groupValues[1].lowercase()
    }

    private fun download(expectedHash: String, progress: (String) -> Unit) {
        val temporary = File(installDir, "$IMAGE_NAME.part")
        temporary.delete()
        val connection = (URL("$BASE_URL/$IMAGE_NAME").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            setRequestProperty("User-Agent", "NEXORA-Android/0.5")
            instanceFollowRedirects = true
        }
        try {
            check(connection.responseCode in 200..299) {
                "Ubuntu Base download returned HTTP ${connection.responseCode}."
            }
            val total = connection.contentLengthLong
            val required = if (total > 0) maxOf(total * 20, 700L * 1024L * 1024L) else 700L * 1024L * 1024L
            check((installDir.usableSpace) >= required) {
                "Not enough free storage. NEXORA needs about ${required / (1024 * 1024)} MB free."
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
                                progress("Downloading Ubuntu Base: $percent%")
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == expectedHash) { "Ubuntu Base SHA-256 verification failed." }
            check(temporary.renameTo(archive)) { "Could not activate the verified Ubuntu archive." }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun configureRootfs(destination: File) {
        val connectivity = app.getSystemService(android.net.ConnectivityManager::class.java)
        val dnsServers = connectivity.activeNetwork
            ?.let { connectivity.getLinkProperties(it)?.dnsServers }
            ?.map { it.hostAddress }
            ?.filterNotNull()
            .orEmpty()
            .ifEmpty { listOf("1.1.1.1", "8.8.8.8") }

        val resolvConf = File(destination, "etc/resolv.conf")
        if (java.nio.file.Files.isSymbolicLink(resolvConf.toPath()) || resolvConf.exists()) {
            resolvConf.delete()
        }
        resolvConf.parentFile?.mkdirs()
        resolvConf.writeText(dnsServers.joinToString("\n") { "nameserver $it" } + "\n")
        File(destination, "etc/hostname").writeText("nexora\n")
    }

    private fun extractArchive(destination: File) {
        val root = destination.canonicalFile
        GzipCompressorInputStream(archive.inputStream().buffered()).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val output = File(root, entry.name).canonicalFile
                    check(output.toPath().startsWith(root.toPath())) {
                        "Ubuntu archive contains an unsafe path."
                    }
                    when {
                        entry.isDirectory -> check(output.isDirectory || output.mkdirs()) {
                            "Could not create Ubuntu directory: ${entry.name}"
                        }
                        entry.isSymbolicLink -> {
                            output.parentFile?.mkdirs()
                            if (output.exists() || java.nio.file.Files.isSymbolicLink(output.toPath())) output.delete()
                            java.nio.file.Files.createSymbolicLink(
                                output.toPath(),
                                java.nio.file.Paths.get(entry.linkName)
                            )
                        }
                        entry.isLink -> {
                            val target = File(root, entry.linkName).canonicalFile
                            check(target.toPath().startsWith(root.toPath())) {
                                "Ubuntu archive contains an unsafe hard link."
                            }
                            output.parentFile?.mkdirs()
                            check(target.exists()) { "Ubuntu archive hard link target is missing." }
                            java.nio.file.Files.createLink(output.toPath(), target.toPath())
                        }
                        entry.isFile -> {
                            output.parentFile?.mkdirs()
                            FileOutputStream(output).use { stream ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = tar.read(buffer)
                                    if (count < 0) break
                                    stream.write(buffer, 0, count)
                                }
                            }
                            output.setReadable(entry.mode and 0b100100100 != 0, false)
                            output.setWritable(entry.mode and 0b010010010 != 0, false)
                            output.setExecutable(entry.mode and 0b001001001 != 0, false)
                        }
                    }
                }
            }
        }
    }

    private fun readText(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "NEXORA-Android/0.5")
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
        private const val BASE_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release"
        private const val IMAGE_NAME = "ubuntu-base-24.04.5-base-arm64.tar.gz"
    }
}
