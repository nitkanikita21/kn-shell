@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import dev.knshell.animation.Easing
import dev.knshell.animation.easingCode
import dev.knshell.core.attachAnimatedSizeLayout
import dev.knshell.core.setAnimatedSizeFixed
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

/** Which axis of [AnimatedBox] is animated. */
enum class AnimatedAxis { Width, Height }

/**
 * Full Row/Column-like container whose main axis animates on content changes.
 *
 * Backed by a native `GtkLayoutManager` (C, `rust-core/src/kn_animated_size_layout.c`):
 * `measure()` reports the animated size as both minimum and natural, so the parent allocates
 * exactly that value — growth animates just like shrinking, which a `size_request` cannot
 * express. Children keep their natural size along the main axis and the container clips
 * them, so content is revealed instead of being reflowed every frame.
 *
 * ```kotlin
 * AnimatedBox(vertical = false, spacing = 8, modifier = Modifier.padding(horizontal = 14)) {
 *     if (playing) CavaEqualizer(width = 80, height = 20) else Icon("\uF04C")
 *     Label(title)
 * }
 * ```
 *
 * [durationMs], [easing], [spacing] and [vertical] are applied when the widget is created
 * (the layout manager is attached once); changing them later does not retarget the running
 * animation.
 */
@Composable
fun AnimatedBox(
    vertical: Boolean = false,
    modifier: Modifier = Modifier,
    durationMs: Int = 180,
    easing: Easing = Easing.OutCubic,
    spacing: Int = 0,
    axis: AnimatedAxis = if (vertical) AnimatedAxis.Height else AnimatedAxis.Width,
    mainSize: Int? = null,
    content: @Composable () -> Unit,
) {
    ComposeNode<GtkAnimatedBoxNode, GtkApplier>(
        factory = {
            GtkAnimatedBoxNode(durationMs, easing, spacing, vertical, axis, mainSize).plain()
        },
        update = {
            set(modifier) { value -> applyModifier(value) }
            // spacing/vertical/axis are baked into the native layout manager at creation.
            set(spacing) { }
            set(vertical) { }
            set(axis) { }
            set(mainSize) { value -> applyMainSize(value) }
        },
    ) {
        content()
    }
}

/** Row whose width animates with its content. */
@Composable
fun AnimatedWidth(
    modifier: Modifier = Modifier,
    durationMs: Int = 180,
    easing: Easing = Easing.OutCubic,
    spacing: Int = 0,
    content: @Composable () -> Unit,
) = AnimatedBox(
    vertical = false,
    modifier = modifier,
    durationMs = durationMs,
    easing = easing,
    spacing = spacing,
    axis = AnimatedAxis.Width,
    content = content,
)

/** Column whose height animates with its content. */
@Composable
fun AnimatedHeight(
    modifier: Modifier = Modifier,
    durationMs: Int = 180,
    easing: Easing = Easing.OutCubic,
    spacing: Int = 0,
    mainSize: Int? = null,
    content: @Composable () -> Unit,
) = AnimatedBox(
    vertical = true,
    modifier = modifier,
    durationMs = durationMs,
    easing = easing,
    spacing = spacing,
    axis = AnimatedAxis.Height,
    mainSize = mainSize,
    content = content,
)

internal class GtkAnimatedBoxNode(
    durationMs: Int,
    easing: Easing,
    spacing: Int,
    vertical: Boolean,
    axis: AnimatedAxis,
    mainSize: Int?,
) : GtkNode(
    gtk_box_new(
        if (vertical) GtkOrientation.GTK_ORIENTATION_VERTICAL else GtkOrientation.GTK_ORIENTATION_HORIZONTAL,
        spacing,
    )!!,
) {

    init {
        attachAnimatedSizeLayout(
            widget.rawValue.toLong().toULong(),
            durationMs.coerceAtLeast(16).toUInt(),
            easing.easingCode,
            spacing.coerceAtLeast(0).toUInt(),
            if (axis == AnimatedAxis.Height) 1u else 0u,
        )
        mainSize?.let { applyMainSize(it) }
    }

    /** `null` resumes content-driven sizing; a value animates to that explicit main size. */
    internal fun applyMainSize(size: Int?) {
        setAnimatedSizeFixed(widget.rawValue.toLong().toULong(), size ?: -1)
    }

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
}
