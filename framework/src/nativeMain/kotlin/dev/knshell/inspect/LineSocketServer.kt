// ---------------------------------------------------------------------------
// `LineSocketServer` — a newline-delimited request/response server on a unix
// socket, driven by the GLib main loop. Handlers therefore run on the GTK main
// thread and may touch widgets directly. The framework knows nothing about the
// payload: the caller decides what a line means.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.inspect

import dev.knshell.compose.GLibDispatcher
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kn.gtk.GAsyncResult
import kn.gtk.GDataInputStream
import kn.gtk.GError
import kn.gtk.GIOStream
import kn.gtk.GObject
import kn.gtk.GSocketConnection
import kn.gtk.GSocketListener
import kn.gtk.G_SOCKET_PROTOCOL_DEFAULT
import kn.gtk.GSocketService
import kn.gtk.GSocketType
import kn.gtk.g_data_input_stream_new
import kn.gtk.g_data_input_stream_read_line_async
import kn.gtk.g_data_input_stream_read_line_finish_utf8
import kn.gtk.g_error_free
import kn.gtk.g_free
import kn.gtk.g_io_stream_close
import kn.gtk.g_io_stream_get_input_stream
import kn.gtk.g_io_stream_get_output_stream
import kn.gtk.g_object_ref
import kn.gtk.g_object_unref
import kn.gtk.g_output_stream_write_all
import kn.gtk.g_signal_connect_data
import kn.gtk.g_socket_listener_add_address
import kn.gtk.g_socket_listener_close
import kn.gtk.g_socket_service_new
import kn.gtk.g_socket_service_start
import kn.gtk.g_socket_service_stop
import kn.gtk.g_unix_socket_address_new
import platform.posix.chmod
import platform.posix.unlink

/**
 * Serves [handler] on the unix socket at [path]: every line received on a connection is passed to
 * the handler and its result is written back as one line. A connection is served strictly in
 * order; the handler must not return text containing a newline.
 *
 * The socket is only accessible to the owner (mode `0600`). Requests are processed by whatever
 * runs the default GLib main loop, so [start] before entering the loop is fine.
 */
class LineSocketServer(
    private val path: String,
    private val handler: suspend (String) -> String,
) {
    private var service: CPointer<GSocketService>? = null
    private var selfRef: StableRef<LineSocketServer>? = null
    private val connections = mutableListOf<Connection>()
    private val scope = CoroutineScope(SupervisorJob() + GLibDispatcher())

    /** Binds the socket and starts accepting; `false` (with a log line) if the bind failed. */
    fun start(): Boolean {
        check(service == null) { "LineSocketServer is already started" }
        val newService = g_socket_service_new() ?: return false
        // A socket file left behind by a killed process makes bind() fail with "address in use".
        unlink(path)
        val bound = memScoped {
            val error = alloc<CPointerVar<GError>>()
            val address = g_unix_socket_address_new(path)!!
            val ok = g_socket_listener_add_address(
                newService.reinterpret<GSocketListener>(),
                address.reinterpret(),
                GSocketType.G_SOCKET_TYPE_STREAM,
                G_SOCKET_PROTOCOL_DEFAULT,
                null,
                null,
                error.ptr,
            )
            g_object_unref(address)
            error.value?.let {
                println("[inspect] cannot listen on $path: ${it.pointed.message?.toKString()}")
                g_error_free(it)
            }
            ok != 0
        }
        if (!bound) {
            g_object_unref(newService)
            return false
        }
        chmod(path, "600".toUInt(8).convert())

        val ref = StableRef.create(this)
        selfRef = ref
        val incoming = staticCFunction {
                _: COpaquePointer?, connection: CPointer<GSocketConnection>?, _: CPointer<GObject>?, data: COpaquePointer? ->
            val server = data?.asStableRef<LineSocketServer>()?.get()
            if (server != null && connection != null) server.accept(connection)
            1
        }
        g_signal_connect_data(newService, "incoming", incoming.reinterpret(), ref.asCPointer(), null, 0u)
        g_socket_service_start(newService)
        service = newService
        return true
    }

    /** Closes the listener and every open connection and removes the socket file. */
    fun stop() {
        val running = service ?: return
        service = null
        g_socket_service_stop(running)
        g_socket_listener_close(running.reinterpret<GSocketListener>())
        g_object_unref(running)
        scope.cancel()
        connections.toList().forEach { it.close() }
        selfRef?.dispose()
        selfRef = null
        unlink(path)
    }

    private fun accept(connection: CPointer<GSocketConnection>) {
        // The service drops its reference when the signal handler returns.
        g_object_ref(connection)
        val accepted = Connection(connection)
        connections += accepted
        accepted.readNext()
    }

    /** One client: read a line, handle it, write the answer, read the next one. */
    private inner class Connection(private val connection: CPointer<GSocketConnection>) {
        private val stream = connection.reinterpret<GIOStream>()
        private val input: CPointer<GDataInputStream> =
            g_data_input_stream_new(g_io_stream_get_input_stream(stream))!!
        private val ref = StableRef.create(this)
        private var reading = false
        private var closed = false

        fun readNext() {
            if (closed) return
            reading = true
            val done = staticCFunction { _: CPointer<GObject>?, result: CPointer<GAsyncResult>?, data: COpaquePointer? ->
                data?.asStableRef<Connection>()?.get()?.onLine(result)
                Unit
            }
            g_data_input_stream_read_line_async(input, 0, null, done.reinterpret(), ref.asCPointer())
        }

        private fun onLine(result: CPointer<GAsyncResult>?) {
            reading = false
            val line = memScoped {
                val error = alloc<CPointerVar<GError>>()
                val raw = g_data_input_stream_read_line_finish_utf8(input, result, null, error.ptr)
                error.value?.let { g_error_free(it) }
                raw?.toKString().also { g_free(raw) }
            }
            // `null` is end of stream or a read error; a close requested meanwhile ends here too.
            if (line == null || closed) {
                closed = true
                release()
                return
            }
            scope.launch {
                try {
                    write(handler(line) + "\n")
                    readNext()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    println("[inspect] request failed: ${e.message}")
                    close()
                }
            }
        }

        private fun write(text: String) {
            if (closed) return
            val bytes = text.encodeToByteArray()
            val ok = bytes.usePinned { pinned ->
                g_output_stream_write_all(
                    g_io_stream_get_output_stream(stream), pinned.addressOf(0), bytes.size.convert(), null, null, null,
                )
            }
            check(ok != 0) { "could not write the response" }
        }

        fun close() {
            if (closed) return
            closed = true
            g_io_stream_close(stream, null, null)
            // With a read in flight the callback still needs this object; it releases it.
            if (!reading) release()
        }

        private fun release() {
            if (!connections.remove(this)) return
            g_io_stream_close(stream, null, null)
            g_object_unref(input)
            g_object_unref(connection)
            ref.dispose()
        }
    }
}
