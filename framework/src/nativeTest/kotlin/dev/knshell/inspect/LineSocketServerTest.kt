@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.knshell.inspect

import kn.gtk.g_main_context_iteration
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.linux.sockaddr_un
import platform.posix.AF_UNIX
import platform.posix.F_OK
import platform.posix.MSG_DONTWAIT
import platform.posix.SOCK_STREAM
import platform.posix.access
import platform.posix.close
import platform.posix.connect
import platform.posix.getpid
import platform.posix.memset
import platform.posix.recv
import platform.posix.send
import platform.posix.socket
import platform.posix.strcpy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class LineSocketServerTest {

    private val path = "/tmp/kn-line-socket-test-${getpid()}.sock"
    private val server = LineSocketServer(path) { line -> "echo $line" }

    @AfterTest
    fun stop() = server.stop()

    @Test
    fun `each line gets one answer in order`() {
        assertTrue(server.start())
        val client = connectClient()

        send(client, "first\nsecond\n")

        assertEquals("echo first\necho second\n", receive(client, lines = 2))
        close(client)
    }

    @Test
    fun `a large non-ascii payload arrives intact`() {
        assertTrue(server.start())
        val client = connectClient()
        val payload = "ї".repeat(20_000)

        send(client, "$payload\n")

        assertEquals("echo $payload\n", receive(client, lines = 1))
        close(client)
    }

    @Test
    fun `stop removes the socket file`() {
        assertTrue(server.start())
        assertEquals(0, access(path, F_OK))

        server.stop()

        assertTrue(access(path, F_OK) != 0)
    }

    private fun connectClient(): Int = memScoped {
        val fd = socket(AF_UNIX, SOCK_STREAM, 0)
        val address = alloc<sockaddr_un>()
        memset(address.ptr, 0, sizeOf<sockaddr_un>().convert())
        address.sun_family = AF_UNIX.convert()
        strcpy(address.sun_path, path)
        assertEquals(0, connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_un>().convert()))
        fd
    }

    private fun send(fd: Int, text: String) {
        val bytes = text.encodeToByteArray()
        var sent = 0
        bytes.usePinned { pinned ->
            while (sent < bytes.size) {
                val n = send(fd, pinned.addressOf(sent), (bytes.size - sent).convert(), MSG_DONTWAIT).toInt()
                if (n > 0) sent += n else g_main_context_iteration(null, 0)
            }
        }
    }

    /** Pumps the main loop (the server lives on it) until [lines] newline-terminated answers arrived. */
    private fun receive(fd: Int, lines: Int): String {
        val collected = mutableListOf<Byte>()
        val buffer = ByteArray(4096)
        val start = TimeSource.Monotonic.markNow()
        while (collected.count { it == '\n'.code.toByte() } < lines && start.elapsedNow().inWholeSeconds < 5) {
            g_main_context_iteration(null, 0)
            val n = buffer.usePinned { recv(fd, it.addressOf(0), buffer.size.convert(), MSG_DONTWAIT).toInt() }
            if (n > 0) collected += buffer.copyOf(n).toList()
        }
        return collected.toByteArray().decodeToString()
    }
}
