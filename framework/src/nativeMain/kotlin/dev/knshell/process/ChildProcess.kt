@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.process

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kn.gtk.GError
import kn.gtk.GSubprocess
import kn.gtk.G_SUBPROCESS_FLAGS_NONE
import kn.gtk.g_error_free
import kn.gtk.g_object_unref
import kn.gtk.g_subprocess_force_exit
import kn.gtk.g_subprocess_get_exit_status
import kn.gtk.g_subprocess_get_identifier
import kn.gtk.g_subprocess_get_if_exited
import kn.gtk.g_subprocess_launcher_new
import kn.gtk.g_subprocess_launcher_setenv
import kn.gtk.g_subprocess_launcher_spawnv

/**
 * A program started by this one (GIO's `GSubprocess`). It shares the output of this process and runs
 * without blocking the UI: ask [isRunning] again later.
 */
class ChildProcess private constructor(private val process: CPointer<GSubprocess>) {

    /** Whether it has not ended yet. */
    val isRunning: Boolean get() = g_subprocess_get_identifier(process) != null

    /** The exit status of an ended process, -1 when a signal ended it; `null` while it runs. */
    val exitStatus: Int?
        get() = when {
            isRunning -> null
            g_subprocess_get_if_exited(process) != 0 -> g_subprocess_get_exit_status(process)
            else -> -1
        }

    /** Kills the process if it still runs. */
    fun stop() = g_subprocess_force_exit(process)

    /** Releases the handle; the process itself is left alone. */
    fun close() = g_object_unref(process.reinterpret())

    companion object {
        /**
         * Starts [command] (the program first) with [environment] added to this process's own.
         * Throws [IllegalStateException] when it cannot be started.
         */
        fun start(command: List<String>, environment: Map<String, String> = emptyMap()): ChildProcess = memScoped {
            require(command.isNotEmpty()) { "no program to start" }
            val launcher = checkNotNull(g_subprocess_launcher_new(G_SUBPROCESS_FLAGS_NONE))
            try {
                environment.forEach { (name, value) -> g_subprocess_launcher_setenv(launcher, name, value, 1) }
                val argv = allocArray<CPointerVar<ByteVar>>(command.size + 1)
                command.forEachIndexed { index, argument -> argv[index] = argument.cstr.ptr }
                argv[command.size] = null
                val error = alloc<CPointerVar<GError>>()
                val process = g_subprocess_launcher_spawnv(launcher, argv.reinterpret(), error.ptr)
                if (process == null) {
                    val reason = error.value?.pointed?.message?.toKString()
                    error.value?.let { g_error_free(it) }
                    error("cannot start ${command.first()}: $reason")
                }
                ChildProcess(process)
            } finally {
                g_object_unref(launcher.reinterpret())
            }
        }
    }
}
