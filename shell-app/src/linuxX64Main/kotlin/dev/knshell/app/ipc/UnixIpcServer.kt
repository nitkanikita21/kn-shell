@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.knshell.app.ipc

import kotlin.concurrent.Volatile
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*

/**
 * Minimal Unix-domain socket IPC server for Kotlin/Native (linuxX64).
 *
 * ktor-network does not expose Unix-domain sockets on Kotlin/Native, and K/N's
 * `platform.posix` does not bind `struct sockaddr_un` (it lives in <sys/un.h>,
 * which is not included by default). So we build the address inline as a
 * ByteArray (sa_family=AF_UNIX at bytes 0-1, path at offset 2) and bind it via
 * `reinterpret<sockaddr>()`.
 *
 * The accept loop and per-client I/O run on [Dispatchers.IO] (Kotlin/Native
 * worker threads), so blocking [read]/[accept] never freeze the main/UI thread.
 */
class UnixIpcServer(
    private val socketPath: String,
    private val scope: CoroutineScope,
) {
    @Volatile
    private var serverFd: Int = -1

    private val AF_UNIX = platform.posix.AF_UNIX
    private val SOCK_STREAM = platform.posix.SOCK_STREAM

    fun start(onCommand: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            runServer(onCommand)
        }
    }

    private fun runServer(onCommand: (String) -> Unit) {
        memScoped { unlink(socketPath.cstr.ptr) }

        val fd = socket(AF_UNIX, SOCK_STREAM, 0)
        if (fd < 0) {
            perror("palette-ipc: socket")
            return
        }
        serverFd = fd

        memScoped {
            // struct sockaddr_un { sa_family_t sun_family; char sun_path[108]; }
            val addrLen = 110
            val addr = allocArray<ByteVar>(addrLen)
            memset(addr, 0, addrLen.toULong())

            val pathBytes = socketPath.encodeToByteArray()
            if (pathBytes.size >= 108) {
                fprintf(stderr, "palette-ipc: socket path too long\n")
                close(fd)
                unlink(socketPath.cstr.ptr)
                return@memScoped
            }
            addr[0] = AF_UNIX.toByte() // sa_family (little-endian, low byte)
            for (i in pathBytes.indices) {
                addr[2 + i] = pathBytes[i]
            }

            val sunLen = (2 + pathBytes.size + 1).toUInt()
            if (bind(fd, addr.reinterpret(), sunLen) < 0) {
                perror("palette-ipc: bind")
                close(fd)
                unlink(socketPath.cstr.ptr)
                return@memScoped
            }
            if (listen(fd, 5) < 0) {
                perror("palette-ipc: listen")
                close(fd)
                unlink(socketPath.cstr.ptr)
                return@memScoped
            }

            println("IPC server listening on $socketPath")

            while (scope.isActive) {
                val client = accept(fd, null, null)
                if (client < 0) {
                    if (errno == EINTR) continue
                    break
                }
                val clientFd = client
                scope.launch(Dispatchers.IO) {
                    handleClient(clientFd, onCommand)
                }
            }
        }
    }

    private fun handleClient(clientFd: Int, onCommand: (String) -> Unit) {
        try {
            memScoped {
                val buf = allocArray<ByteVar>(1024)
                val n = read(clientFd, buf, 1024.toULong())
                if (n > 0) {
                    val line = buf.readBytes(n.toInt()).decodeToString().trim()
                    onCommand(line)
                    val resp = "ok\n".encodeToByteArray()
                    write(clientFd, resp.refTo(0), resp.size.toULong())
                }
            }
        } finally {
            close(clientFd)
        }
    }

    fun stop() {
        if (serverFd >= 0) {
            close(serverFd)
            serverFd = -1
        }
        memScoped { unlink(socketPath.cstr.ptr) }
    }
}
