package dev.atdev.nexora

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Mirrors only the Android folder explicitly selected by the user into Ubuntu. */
class AndroidFolderBridge(context: Context) {
    private val app = context.applicationContext
    val sharedDirectory = File(app.filesDir, "android-shared")

    fun importTree(uri: Uri) {
        val tree = DocumentFile.fromTreeUri(app, uri)
            ?: error("Android folder permission is unavailable")
        require(tree.canRead()) { "Cannot read the selected Android folder" }
        sharedDirectory.deleteRecursively()
        check(sharedDirectory.mkdirs() || sharedDirectory.isDirectory)
        copyFromTree(tree, sharedDirectory)
    }

    fun exportTree(uri: Uri) {
        val tree = DocumentFile.fromTreeUri(app, uri)
            ?: error("Android folder permission is unavailable")
        require(tree.canWrite()) { "The selected Android folder is read-only" }
        syncToTree(sharedDirectory, tree)
    }

    private fun copyFromTree(source: DocumentFile, destination: File) {
        source.listFiles().forEach { child ->
            val name = safeName(child.name ?: return@forEach) ?: return@forEach
            val target = File(destination, name)
            if (child.isDirectory) {
                if (target.exists() && !target.isDirectory) target.delete()
                if (target.mkdirs() || target.isDirectory) copyFromTree(child, target)
            } else if (child.isFile) {
                target.parentFile?.mkdirs()
                app.contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun syncToTree(source: File, destination: DocumentFile) {
        val local = source.listFiles().orEmpty()
        val names = local.map { it.name }.toSet()
        destination.listFiles().forEach { remote ->
            val name = remote.name ?: return@forEach
            if (name !in names) remote.delete()
        }
        local.forEach { file ->
            val name = safeName(file.name) ?: return@forEach
            val existing = destination.findFile(name)
            if (file.isDirectory) {
                val folder = if (existing?.isDirectory == true) existing else {
                    existing?.delete()
                    destination.createDirectory(name)
                }
                if (folder != null) syncToTree(file, folder)
            } else if (file.isFile) {
                existing?.delete()
                val remote = destination.createFile("application/octet-stream", name) ?: return@forEach
                app.contentResolver.openOutputStream(remote.uri, "wt")?.use { output ->
                    FileInputStream(file).use { input -> input.copyTo(output) }
                }
            }
        }
    }

    private fun safeName(name: String): String? =
        name.takeIf { it.isNotBlank() && it != "." && it != ".." &&
            !it.contains('/') && !it.contains('\\') }
}
