package dev.knshell.app.theme

import dev.knshell.palette.*
import kotlinx.cinterop.*

object PaletteClient {

    /** Повертає JSON-результат або кидає/повертає null при помилці. */
    @OptIn(ExperimentalForeignApi::class)
    fun extractSync(path: String, optsJson: String = "{}"): String? = memScoped {
        val outJson = alloc<CPointerVar<ByteVar>>()
        val errOut = alloc<CPointerVar<ByteVar>>()

        val code = palette_extract_sync(
            path.cstr.ptr,                 // *const c_char
            optsJson.cstr.ptr,             // *const c_char
            outJson.ptr,                   // char** (out)
            errOut.ptr,                    // char** (out)
        )

        try {
            if (code != 0) {
                val err = errOut.value?.toKString()
                println("[palette] extract failed ($code): $err")
                return null
            }
            return outJson.value?.toKString()
        } finally {
            // Rust виділив ці рядки — ми мусимо їх звільнити!
            palette_free_string(outJson.value)
            palette_free_string(errOut.value)
        }
    }
}