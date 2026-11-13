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
        val arch = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a", "armeabi" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> error("Unsupported Android ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
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
        download(url!!, zipFile, progress)
        check(sha256(zipFile).equals(digest, true)) { "PRoot SHA-256 verification failed" }
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
        check(proot.setExecutable(true, true)) { "Cannot set PRoot executable permission" }
    }

    private fun installUbuntu(progress: (String) -> Unit) {
        val arch = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a", "armeabi" -> "armhf"
            "x86_64" -> "amd64"
            "x86" -> "i386"
            else -> error("Unsupported Android ABI")
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
        download(baseUrl + name, archive, progress)
        check(sha256(archive).equals(expected, true)) { "Ubuntu rootfs SHA-256 verification failed" }
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
                            if (source.exists()) Os.link(source.absolutePath, target.absolutePath)
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
        } finally {
            archive.delete()
            stage.deleteRecursively()
        }
    }

    private fun rootCommand(command: String, input: String = ""): Pair<Int, String> {
        val p = ProcessBuilder(proot.absolutePath, "--link2symlink", "-0", "-r", root.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "/bin/bash", "-lc", command)
            .redirectErrorStream(true).start()
        if (input.isNotEmpty()) p.outputStream.bufferedWriter().use { it.write(input) } else p.outputStream.close()
        val output = p.inputStream.bufferedReader().readText()
        return p.waitFor() to output
    }

    private fun fetchText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "NEXORA-Android")
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    private fun download(url: String, file: File, progress: (String) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "NEXORA-Android")
        try {
            c.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        total += n
                        if (total % (4L * 1024 * 1024) < buffer.size)
                            progress("Downloaded ${total / (1024 * 1024)} MB…")
                    }
                }
            }
        } finally { c.disconnect() }
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
