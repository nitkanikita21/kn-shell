@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import dev.knshell.animation.Easing
import dev.knshell.animation.easingCode
import dev.knshell.core.attachAnimatedSizeLayout
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toLong
import kn.gtk.GtkBox
import kn.gtk.GtkOrientation
import kn.gtk.GtkWidget
import kn.gtk.gtk_box_append
import kn.gtk.gtk_box_insert_child_after
import kn.gtk.gtk_box_new
import kn.gtk.gtk_box_remove

/**
 * Width that animates on content changes in **both** directions.
 *
 * Backed by a native `GtkLayoutManager` (written in C, `rust-core/src/kn_animated_size_layout.c`):
 * its `measure()` reports the animated width as both minimum and natural, so the parent
 * allocates exactly that value — growth animates just like shrinking, which a plain
 * `size_request` cannot do (it is a minimum only). The child keeps its natural size and the
 * container clips it, so the content is revealed instead of being reflowed every frame.
 *
 * ```kotlin
 * AnimatedWidth(
 *     durationMs = 200,
 *     modifier = Modifier.radius(8).background(Grayscale.Surface).padding(horizontal = 14),
 * ) {
 *     if (playing) CavaEqualizer(width = 80, height = 20) else Icon("\\uF04C")
 *     Label(title)
 * }
 * ```
 *
 * [durationMs] and [easing] are applied when the widget is created (the layout manager is
 * attached once); changing them later does not retarget a running animation.
 */
@Composable
fun AnimatedWidth(
    modifier: Modifier = Modifier,
    durationMs: Int = 180,
    easing: Easing = Easing.OutCubic,
    spacing: Int = 0,
    content: @Composable () -> Unit,
) {
    ComposeNode<GtkAnimatedWidthNode, GtkApplier>(
        factory = { GtkAnimatedWidthNode(durationMs, easing, spacing).plain() },
        update = {
            set(modifier) { value -> applyModifier(value) }
            // spacing is not settable here: the custom layout manager replaces GtkBoxLayout
            // (gtk_box_set_spacing would assert), so AnimatedWidth lays children out itself.
            set(spacing) { }
        },
    ) {
        content()
    }
}

internal class GtkAnimatedWidthNode(
    durationMs: Int,
    easing: Easing,
    spacing: Int,
) : GtkNode(gtk_box_new(GtkOrientation.GTK_ORIENTATION_HORIZONTAL, spacing)!!) {

    override fun attachChild(child: CPointer<GtkWidget>) {
        gtk_box_append(widget.reinterpret<GtkBox>(), child)
    }

    /** Order-aware insert: a conditional child (equalizer <-> icon) must keep its slot. */
    override fun attachChildAt(index: Int, child: CPointer<GtkWidget>) {
        val box = widget.reinterpret<GtkBox>()
        val previous = children.getOrNull(index - 1)?.widget
        gtk_box_insert_child_after(box, child, previous)
    }

    override fun detachChild(child: CPointer<GtkWidget>) {
        gtk_box_remove(widget.reinterpret<GtkBox>(), child)
    }

    init {
        attachAnimatedSizeLayout(
            widget.rawValue.toLong().toULong(),
            durationMs.coerceAtLeast(16).toUInt(),
            easing.easingCode,
        )
    }

}
