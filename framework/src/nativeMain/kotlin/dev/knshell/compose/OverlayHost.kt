// ---------------------------------------------------------------------------
// Layers above the content of a window, for dialogs and toasts: composed where they are declared,
// drawn on top of everything.
// ---------------------------------------------------------------------------

package dev.knshell.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

class OverlayHostState internal constructor() {
    internal val layers = mutableStateListOf<OverlayLayer>()

    /** Whether anything is shown above the content. */
    val hasLayers: Boolean get() = layers.isNotEmpty()
}

internal class OverlayLayer {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
    var locals by mutableStateOf<CompositionLocalContext?>(null)

    @Composable
    fun Show() {
        val context = locals ?: return
        val body = content ?: return
        CompositionLocalProvider(context) { body() }
    }
}

/** The nearest [OverlayHost], or `null` outside any. */
val LocalOverlayHost = staticCompositionLocalOf<OverlayHostState?> { null }

/**
 * [content] with room above it for [Portal] layers. Put it at the root of a window. A layer covers
 * the host and places its own content with `align`; a later layer is drawn above an earlier one.
 */
@Composable
fun OverlayHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = remember { OverlayHostState() }
    CompositionLocalProvider(LocalOverlayHost provides state) {
        Stack(modifier) {
            Column(modifier = Modifier.align(Align.Fill)) { content() }
            state.layers.forEach { layer -> key(layer) { layer.Show() } }
        }
    }
}

/**
 * Draws [content] in a layer of the surrounding [OverlayHost] for as long as the Portal is composed.
 * The content sees the composition locals of the place it is declared in, its theme included.
 */
@Composable
fun Portal(content: @Composable () -> Unit) {
    val host = checkNotNull(LocalOverlayHost.current) { "Portal needs an OverlayHost above it" }
    val layer = remember { OverlayLayer() }
    val locals = currentCompositionLocalContext
    SideEffect {
        layer.locals = locals
        layer.content = content
    }
    DisposableEffect(host, layer) {
        host.layers.add(layer)
        onDispose { host.layers.remove(layer) }
    }
}
