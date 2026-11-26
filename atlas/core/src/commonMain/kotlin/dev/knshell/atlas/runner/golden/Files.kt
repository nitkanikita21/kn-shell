@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package dev.knshell.atlas.runner.golden

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.mkdir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.remove
import platform.posix.rename
import platform.posix.stat

/** The content of the file at [path], or `null` when it cannot be opened. */
internal fun readBytes(path: String): ByteArray? {
    val file = fopen(path, "rb") ?: return null
    try {
        val chunks = mutableListOf<ByteArray>()
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = buffer.usePinned { fread(it.addressOf(0), 1.convert(), buffer.size.convert(), file) }.toInt()
            if (read <= 0) break
            chunks += buffer.copyOf(read)
        }
        val content = ByteArray(chunks.sumOf { it.size })
        var at = 0
        chunks.forEach { chunk ->
            chunk.copyInto(content, at)
            at += chunk.size
        }
        return content
    } finally {
        fclose(file)
    }
}

/**
 * Writes [content] to [path], creating its directories. The file appears complete or not at all: it
 * is written next to the target and moved over it.
 */
internal fun writeBytes(path: String, content: ByteArray) {
    makeDirectories(path.substringBeforeLast('/', ""))
    val part = "$path.part"
    val file = fopen(part, "wb") ?: error("cannot write $path")
    val written = try {
        if (content.isEmpty()) {
            0
        } else {
            content.usePinned { fwrite(it.addressOf(0), 1.convert(), content.size.convert(), file) }.toInt()
        }
    } finally {
        fclose(file)
    }
    check(written == content.size && rename(part, path) == 0) { "cannot write $path" }
}

internal fun readText(path: String): String? = readBytes(path)?.decodeToString()

internal fun writeText(path: String, text: String) = writeBytes(path, text.encodeToByteArray())

internal fun removeFile(path: String) {
    remove(path)
}

private fun makeDirectories(path: String) {
    if (path.isEmpty()) return
    var made = if (path.startsWith('/')) "" else "."
    path.split('/').filter { it.isNotEmpty() }.forEach { part ->
        made = "$made/$part"
        // Fails for a directory that exists; a real failure shows when the file cannot be opened.
        mkdir(made, 0b111_101_101.convert())
    }
}

/** The files below [directory] whose name ends in [suffix], as paths relative to it. */
internal fun listFiles(directory: String, suffix: String): List<String> {
    val found = mutableListOf<String>()
    fun walk(relative: String) {
        val handle = opendir(if (relative.isEmpty()) directory else "$directory/$relative") ?: return
        try {
            while (true) {
                val name = readdir(handle)?.let { it.pointed.d_name.toKString() } ?: break
                if (name == "." || name == "..") continue
                val child = if (relative.isEmpty()) name else "$relative/$name"
                val sub = opendir("$directory/$child")
                if (sub != null) {
                    closedir(sub)
                    walk(child)
                } else if (name.endsWith(suffix)) {
                    found += child
                }
            }
        } finally {
            closedir(handle)
        }
    }
    walk("")
    return found.sorted()
}

/** Size and modification time of a file: changes when it is rebuilt. `null` when it does not exist. */
internal fun fileStamp(path: String): String? = memScoped {
    val info = alloc<stat>()
    if (stat(path, info.ptr) != 0) return@memScoped null
    "${info.st_size}:${info.st_mtim.tv_sec}.${info.st_mtim.tv_nsec}"
}
