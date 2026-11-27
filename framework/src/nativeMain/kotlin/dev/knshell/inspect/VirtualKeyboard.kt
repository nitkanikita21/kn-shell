// ---------------------------------------------------------------------------
// Presses real keys when the process runs under headless-compositor, whose control socket takes
// them: the events then travel the same way as those of a physical keyboard.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.inspect

import dev.knshell.compose.Key
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kn.gtk.GError
import kn.gtk.g_data_input_stream_new
import kn.gtk.g_data_input_stream_read_line
import kn.gtk.g_error_free
import kn.gtk.g_free
import kn.gtk.g_io_stream_get_input_stream
import kn.gtk.g_io_stream_get_output_stream
import kn.gtk.g_object_unref
import kn.gtk.g_output_stream_write_all
import kn.gtk.g_socket_client_connect
import kn.gtk.g_socket_client_new
import kn.gtk.g_unix_socket_address_new
import platform.posix.getenv

/** The variable through which headless-compositor names its key socket. */
private const val KEYS_ENV = "HEADLESS_COMPOSITOR_KEYS"

class VirtualKeyboard(private val socket: String) {

    /** One stroke of [key] with the modifiers held. Printing keys are sent with [type]. */
    fun press(key: Key, shift: Boolean = false, ctrl: Boolean = false, alt: Boolean = false, meta: Boolean = false) {
        require(key != Key.Character && key != Key.Unknown) { "type() sends printing keys" }
        val modifiers = listOfNotNull(
            "shift".takeIf { shift },
            "ctrl".takeIf { ctrl },
            "alt".takeIf { alt },
            "super".takeIf { meta },
        )
        send((listOf("key", key.name) + modifiers).joinToString(" "))
    }

    /** Types [text]: letters, digits, spaces and `- . , /`. */
    fun type(text: String) {
        require('\n' !in text) { "press(Key.Enter) ends a line" }
        send("type $text")
    }

    /** One command, one answer. The compositor replies once the events are on their way. */
    private fun send(line: String) = memScoped {
        val client = checkNotNull(g_socket_client_new())
        val address = checkNotNull(g_unix_socket_address_new(socket))
        val error = alloc<CPointerVar<GError>>()
        fun failure(what: String): Nothing {
            val reason = error.value?.pointed?.message?.toKString()
            error.value?.let { g_error_free(it) }
            throw InspectException("the virtual keyboard $what: $reason")
        }
        try {
            val connection = g_socket_client_connect(client, address.reinterpret(), null, error.ptr)
                ?: failure("cannot be reached at $socket")
            try {
                val bytes = "$line\n".encodeToByteArray()
                val written = bytes.usePinned {
                    g_output_stream_write_all(
                        g_io_stream_get_output_stream(connection.reinterpret()),
                        it.addressOf(0),
                        bytes.size.convert(),
                        null,
                        null,
                        error.ptr,
                    )
                }
                if (written == 0) failure("did not take \"$line\"")
                val input = checkNotNull(g_data_input_stream_new(g_io_stream_get_input_stream(connection.reinterpret())))
                try {
                    val answer = g_data_input_stream_read_line(input, null, null, error.ptr)
                        ?: failure("did not answer \"$line\"")
                    val text = answer.toKString()
                    g_free(answer)
                    if (text != "ok") throw InspectException("the virtual keyboard refused \"$line\": $text")
                } finally {
                    g_object_unref(input.reinterpret())
                }
            } finally {
                g_object_unref(connection.reinterpret())
            }
        } finally {
            g_object_unref(address.reinterpret())
            g_object_unref(client.reinterpret())
        }
    }

    companion object {
        /** The keyboard of the compositor this process runs under, or `null` on any other display. */
        fun fromEnvironment(): VirtualKeyboard? = getenv(KEYS_ENV)?.toKString()?.let(::VirtualKeyboard)
    }
}
