// Internal record for one window mounted on `ShellRuntime`: id, title, GTK window, composition,
// `StableRef`, closed flag and — for bar surfaces — the input-region tracker.

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.shell

import androidx.compose.runtime.Composition
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kn.gtk.GtkWindow

/** A window mounted on the runtime; tracked until its close-request fires. */
internal class RuntimeWindow(
    val runtime: ShellRuntime,
    val id: Int,
    val window: CPointer<GtkWindow>,
    val title: String,
    val composition: Composition,
) {
    var selfRef: StableRef<RuntimeWindow>? = null
    var closed: Boolean = false

    /** Shapes the surface's pointer input to the `Modifier.inputRegion()` widgets; bars only. */
    var inputRegion: InputRegion? = null
}
