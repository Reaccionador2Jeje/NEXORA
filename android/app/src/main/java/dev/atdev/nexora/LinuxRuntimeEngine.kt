package dev.atdev.nexora

import java.io.File

/**
 * Runtime boundary used by the Android UI and execution service.
 *
 * The current implementation is the legacy PRoot userspace. The QEMU
 * implementation will replace it only after the VM passes device tests.
 */
interface LinuxRuntimeEngine {
    fun isInstalled(): Boolean

    fun install(
        user: String,
        password: String,
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

/** Single selection point for the active Linux runtime implementation. */
object LinuxRuntimeFactory {
    fun create(context: android.content.Context): LinuxRuntimeEngine =
        ProotUbuntuRuntime(context)
}
