package dev.atdev.nexora

import java.io.File

/**
 * Runtime boundary used by the Android UI and execution service.
 * The Android implementation runs an ARM64 Ubuntu rootfs through PRoot.
 */
interface LinuxRuntimeEngine {
    fun isInstalled(): Boolean

    fun install(
        user: String,
        hostname: String,
        progress: (String) -> Unit,
        done: (Boolean, String, Int) -> Unit
    )

    fun execute(
        command: String,
        user: String,
        uid: Int,
        androidShared: File? = null,
        line: (String) -> Unit,
        done: (Int) -> Unit
    )
}

/** PRoot is the primary Android Linux runtime. */
object LinuxRuntimeFactory {
    fun create(context: android.content.Context): LinuxRuntimeEngine =
        ProotUbuntuRuntime(context.applicationContext)
}
