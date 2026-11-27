@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.knshell.app.theme

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.fread
import platform.posix.pclose
import platform.posix.popen

/**
 * Reads the active wallpaper path from the `awww` wallpaper daemon.
 *
 * This is an edge data-source: it shells out to `awww query -j` and parses the
 * JSON reply with kotlinx-serialization. No dependency on a [dev.knshell.app.theme.ThemeService]
 * or any business logic — it only answers "what file is currently on the wall".
 *
 * `awww query -j` emits, per monitor, an object whose `displaying.image` holds
 * the path, e.g.
 *   { "": [ { "name": "DP-2", "displaying": { "image": "/path/img.png" } } ] }
 */
object AwwwWallpaperSource: WallpaperSource {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun query(): String? = withContext(Dispatchers.Default) {
        val pipe = popen("awww query -j", "r") ?: return@withContext null
        try {
            memScoped {
                val buf = allocArray<ByteVar>(4096)
                val n = fread(buf, 1u, 4096u, pipe).toInt()
                if (n <= 0) null else parseImagePath(json.parseToJsonElement(buf.toKString()).toString())
            }
        } finally {
            pclose(pipe)
        }
    }
}

/**
 * Decodes `awww`'s reply using kotlinx-serialization — no model mapping.
 *
 * Walks the parsed JSON tree to the first monitor's `displaying.image` string:
 *   root (object) → first array element → "displaying" (object) → "image" (string).
 * Returns null if any step is missing or not a string.
 */
internal fun parseImagePath(jsonText: String): String? {
    val root = Json.parseToJsonElement(jsonText)
    val monitors = root.jsonObject.values.firstOrNull()?.jsonArray ?: return null
    val first = monitors.firstOrNull()?.jsonObject ?: return null
    val displaying = first["displaying"]?.jsonObject ?: return null
    return displaying["image"]?.jsonPrimitive?.content
}
