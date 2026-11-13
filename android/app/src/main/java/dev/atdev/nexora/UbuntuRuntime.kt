package dev.atdev.nexora

import android.content.Context
import android.os.Build
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/** Rootless Ubuntu 24.04 userspace installer and command runner. */
class UbuntuRuntime(context: Context) {
    private val app = context.applicationContext
    private val base = File(app.filesDir, "ubuntu")
    private val root = File(base, "rootfs")
    private val binDir = File(base, "runtime")
    private val proot = File(binDir, "proot")

    fun isInstalled() = proot.canExecute() && File(root, "bin/bash").isFile

    fun install(user: String, password: String, progress: (String) -> Unit, done: (Boolean, String, Int) -> Unit) {
        Thread {
            try {
                base.mkdirs()
                if (!proot.exists()) installProot(progress)
                ensureProotExecutable()
                if (!File(root, "bin/bash").exists()) installUbuntu(progress)
                progress("Creating Ubuntu account…")
                val create = rootCommand("if ! id -u '$user' >/dev/null 2>&1; then /usr/sbin/useradd -m -u 10000 -s /bin/bash '$user'; fi")
                check(create.first == 0) { "useradd failed: ${create.second}" }
                val pass = rootCommand("/usr/sbin/chpasswd", "$user:$password\n")
                check(pass.first == 0) { "chpasswd failed: ${pass.second}" }
                val uidResult = rootCommand("/usr/bin/id -u '$user'")
                check(uidResult.first == 0) { "Could not read Ubuntu UID" }
                val uid = uidResult.second.trim().toInt()
                done(true, "Ubuntu 24.04 LTS installed. User $user (UID $uid).", uid)
            } catch (e: Exception) {
                done(false, e.message ?: e.javaClass.simpleName, -1)
            }
        }.start()
    }

    fun execute(command: String, user: String, uid: Int, androidShared: File? = null, line: (String) -> Unit, done: (Int) -> Unit) {
        Thread {
            var code = 1
            try {
                ensureProotExecutable()
                val elevated = command.trimStart().startsWith("sudo ")
                val actual = if (elevated) command.trimStart().removePrefix("sudo ") else command
                val args = mutableListOf(proot.absolutePath, "--link2symlink", "-r", root.absolutePath,
                    "-b", "/dev", "-b", "/proc", "-b", "/sys")
                if (androidShared != null) {
                    File(root, "mnt/android").mkdirs()
                    args.addAll(listOf("-b", androidShared.absolutePath + ":/mnt/android"))
                }
                args.addAll(listOf("-w", "/home/$user"))
                if (elevated) args.add("-0") else args.addAll(listOf("-i", "$uid:$uid"))
                args.addAll(listOf("/usr/bin/env", "HOME=/home/$user", "USER=$user", "LOGNAME=$user",
                    "TERM=xterm-256color", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "/bin/bash", "--noprofile", "--norc", "-lc", actual))
                val p = ProcessBuilder(args).redirectErrorStream(true).start()
                p.inputStream.bufferedReader().useLines { seq -> seq.forEach(line) }
                code = p.waitFor()
            } catch (e: Exception) {
                line("nex: Ubuntu command failed: ${e.message}")
            }
            done(code)
        }.start()
    }

    private fun installProot(progress: (String) -> Unit) {
        val abi = supportedAbi()
        val arch = when (abi) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a", "armeabi" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> error("Unsupported Android ABI: $abi")
        }
        val release = JSONObject(fetchText("https://api.github.com/repos/ahmed-alnassif/proot/releases/latest"))
        val assets = release.getJSONArray("assets")
        var url: String? = null
        var digest: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == "proot-$arch.zip") {
                url = a.optString("browser_download_url")
                digest = a.optString("digest").removePrefix("sha256:")
                break
            }
        }
        check(!url.isNullOrBlank() && digest?.matches(Regex("[a-fA-F0-9]{64}")) == true) {
            "No verified PRoot release for $arch"
        }
        val zipFile = File(base, "proot.zip.part")
        downloadResumable(url!!, zipFile, progress)
        if (!sha256(zipFile).equals(digest, true)) {
            zipFile.delete()
            error("PRoot SHA-256 verification failed; the incomplete download was discarded")
        }
        binDir.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(binDir, entry.name).canonicalFile
                check(target.path == binDir.canonicalPath || target.path.startsWith(binDir.canonicalPath + File.separator)) {
                    "Unsafe path in PRoot archive"
                }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
        zipFile.delete()
        val executable = if (proot.isFile) proot else binDir.walkTopDown().firstOrNull { it.isFile && it.name == "proot" }
        check(executable != null) { "PRoot binary missing from archive" }
        if (executable != proot) executable!!.copyTo(proot, true)
        ensureProotExecutable()
    }

    private fun installUbuntu(progress: (String) -> Unit) {
        val abi = supportedAbi()
        val arch = when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a", "armeabi" -> "armhf"
            "x86_64" -> "amd64"
            "x86" -> "i386"
            else -> error("Unsupported Android ABI: $abi")
        }
        val baseUrl = "https://cloud-images.ubuntu.com/minimal/releases/noble/release/"
        val name = "ubuntu-24.04-minimal-cloudimg-$arch-root.tar.xz"
        progress("Verifying official Ubuntu 24.04 image…")
        val sums = fetchText(baseUrl + "SHA256SUMS")
        val expected = sums.lineSequence().mapNotNull { row ->
            val parts = row.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && parts[1].trimStart('*') == name) parts[0] else null
        }.firstOrNull() ?: error("Ubuntu checksum list does not contain $name")
        val archive = File(base, "$name.part")
        downloadResumable(baseUrl + name, archive, progress)
        if (!sha256(archive).equals(expected, true)) {
            archive.delete()
            error("Ubuntu rootfs SHA-256 verification failed; the incomplete download was discarded")
        }
        val stage = File(base, "rootfs-staging")
        stage.deleteRecursively()
        stage.mkdirs()
        try {
            progress("Extracting Ubuntu filesystem…")
            TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
                while (true) {
                    val e = tar.nextTarEntry ?: break
                    val rel = e.name.removePrefix("/").trimEnd('/')
                    if (rel.isBlank() || rel.startsWith("../") || rel.contains("/../")) continue
                    val target = File(stage, rel).canonicalFile
                    check(target.path == stage.canonicalPath || target.path.startsWith(stage.canonicalPath + File.separator)) {
                        "Unsafe path in Ubuntu rootfs"
                    }
                    when {
                        e.isDirectory -> target.mkdirs()
                        e.isSymbolicLink -> { target.parentFile?.mkdirs(); Os.symlink(e.linkName, target.absolutePath) }
                        e.isLink -> {
                            target.parentFile?.mkdirs()
                            val source = File(stage, e.linkName.removePrefix("/")).canonicalFile
                            check(source.exists() && source.isFile) {
                                "Ubuntu archive hard-link target is missing: ${e.linkName}"
                            }

                            // Android puede bloquear hard links (EACCES) en el sandbox.
                            // En ese caso copiamos el contenido: PRoot puede trabajar con
                            // archivos normales y --link2symlink cubre los enlaces del rootfs.
                            try {
                                Os.link(source.absolutePath, target.absolutePath)
                            } catch (_: Exception) {
                                source.copyTo(target, overwrite = true)
                                target.setReadable(source.canRead(), false)
                                target.setWritable(source.canWrite(), false)
                                target.setExecutable(source.canExecute(), false)
                            }
                        }
                        e.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { tar.copyTo(it) }
                            target.setReadable((e.mode and 292) != 0, false)
                            target.setWritable((e.mode and 146) != 0, false)
                            target.setExecutable((e.mode and 73) != 0, false)
                        }
                    }
                }
            }
            check(File(stage, "bin/bash").isFile) { "Ubuntu extraction incomplete: bash missing" }
            root.deleteRecursively()
            check(stage.renameTo(root)) { "Could not finalize Ubuntu rootfs" }
            archive.delete()
        } finally {
            // Keep a checksum-verified archive after extraction errors so retrying
            // does not force the user to download the entire Ubuntu image again.
            stage.deleteRecursively()
        }
    }

    private fun rootCommand(command: String, input: String = ""): Pair<Int, String> {
        ensureProotExecutable()
        val p = ProcessBuilder(proot.absolutePath, "--link2symlink", "-0", "-r", root.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "/bin/bash", "-lc", command)
            .redirectErrorStream(true).start()
        if (input.isNotEmpty()) p.outputStream.bufferedWriter().use { it.write(input) } else p.outputStream.close()
        val output = p.inputStream.bufferedReader().readText()
        return p.waitFor() to output
    }

    /** Force explicit Unix execute bits and verify Android can see them. */
    private fun ensureProotExecutable() {
        check(proot.isFile && proot.length() > 0L) {
            "PRoot binary is missing or empty: ${proot.absolutePath}"
        }
        try {
            // 0755: owner can read/write/execute; group and others read/execute.
            Os.chmod(proot.absolutePath, 493)
        } catch (e: Exception) {
            throw IllegalStateException("Cannot chmod PRoot: ${e.message}", e)
        }
        val mode = Os.stat(proot.absolutePath).st_mode
        val executeBits = mode and 73 // 0111
        check(executeBits != 0 && proot.canExecute()) {
            "PRoot is not executable after chmod 0755 (mode=${mode.toString(8)}, path=${proot.absolutePath}). Android may be blocking execution from this storage location."
        }
    }

    private fun fetchText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "NEXORA-Android")
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    /**
     * Chooses the first ABI this runtime actually supports, not blindly the
     * first ABI reported by Android.
     */
    private fun supportedAbi(): String =
        Build.SUPPORTED_ABIS.firstOrNull {
            it in setOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86")
        } ?: error("Unsupported device architecture: ${Build.SUPPORTED_ABIS.joinToString()}")

    /**
     * Resumable download. If the app closes or the network drops, the .part
     * file remains and the next install continues with HTTP Range when possible.
     */
    private fun downloadResumable(url: String, file: File, progress: (String) -> Unit) {
        file.parentFile?.mkdirs()
        var offset = file.takeIf { it.isFile }?.length() ?: 0L
        var connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.setRequestProperty("User-Agent", "NEXORA-Android")
        if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")

        try {
            var status = connection.responseCode
            if (status == 416 && offset > 0L) {
                connection.disconnect()
                file.delete()
                offset = 0L
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 20000
                connection.readTimeout = 60000
                connection.setRequestProperty("User-Agent", "NEXORA-Android")
                status = connection.responseCode
            }

            val append = offset > 0L && status == HttpURLConnection.HTTP_PARTIAL
            if (status !in 200..299) error("Download failed: HTTP $status")
            if (append) {
                val range = connection.getHeaderField("Content-Range").orEmpty()
                check(range.startsWith("bytes $offset-")) {
                    "Download server returned an invalid resume range"
                }
            } else if (offset > 0L) {
                file.delete()
                offset = 0L
            }

            val expectedTotal = connection.getHeaderFieldLong("Content-Length", -1L)
                .let { if (it >= 0L) it + offset else -1L }
            connection.inputStream.use { input ->
                FileOutputStream(file, append).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var received = offset
                    var lastReported = -1L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        val megabytes = received / (8L * 1024 * 1024)
                        if (megabytes != lastReported) {
                            lastReported = megabytes
                            val totalText = if (expectedTotal > 0L)
                                " / ${expectedTotal / (1024 * 1024)} MB" else ""
                            progress("Downloaded ${received / (1024 * 1024)}$totalText MB…")
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val b = ByteArray(65536)
            while (true) { val n = input.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}
