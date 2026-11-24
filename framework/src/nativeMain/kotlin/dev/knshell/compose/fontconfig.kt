@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import dev.knshell.fontconfig.FcConfigAppFontAddFile
import dev.knshell.fontconfig.FcConfigGetCurrent
import dev.knshell.fontconfig.FcConfigSubstitute
import dev.knshell.fontconfig.FcFalse
import dev.knshell.fontconfig.FcMatchPattern
import dev.knshell.fontconfig.FcPattern
import dev.knshell.fontconfig.FcPatternAddBool
import dev.knshell.fontconfig.FcPatternAddInteger
import dev.knshell.fontconfig.FcPatternCreate
import dev.knshell.fontconfig.FcPatternDestroy
import dev.knshell.fontconfig.FcTrue
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret

fun registerFont(path: String) = memScoped {
    check(FcConfigAppFontAddFile(null, path.cstr.ptr.reinterpret()) != 0) {
        "Cannot register font: $path"
    }
}
