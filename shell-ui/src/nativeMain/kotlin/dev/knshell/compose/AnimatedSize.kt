@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import dev.knshell.animation.Easing
import dev.knshell.animation.eased
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kn.gtk.GtkBox
import kn.gtk.GtkOrientation
import kn.gtk.GtkOverflow
import kn.gtk.GtkPolicyType
import kn.gtk.GtkRequisition
import kn.gtk.GtkScrolledWindow
import kn.gtk.GtkWidget
import kn.gtk.gtk_box_append
import kn.gtk.gtk_box_new
import kn.gtk.gtk_box_remove
import kn.gtk.gtk_box_set_spacing
import kn.gtk.gtk_scrolled_window_new
import kn.gtk.gtk_scrolled_window_set_child
import kn.gtk.gtk_scrolled_window_set_policy
import kn.gtk.gtk_scrolled_window_set_propagate_natural_height
import kn.gtk.gtk_scrolled_window_set_propagate_natural_width
import kn.gtk.gtk_widget_get_preferred_size
import kn.gtk.gtk_widget_set_overflow
import kn.gtk.gtk_widget_set_size_request
import kotlin.math.roundToInt

/**
 * Width that animates on content changes in **both** directions.
 *
 * A Modifier cannot do this alone: `gtk_widget_set_size_request` only enforces a *minimum*,
 * so when content grows its natural width wins and the widget jumps (shrinking works only
 * because the request is above the natural width). This container wraps the content in a
 * transparent `GtkScrolledWindow` with natural-size propagation disabled: the wrapper's own
 * natural size stays minimal, so the animated request is authoritative both ways, and the
 * viewport clips the content while the width grows.
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
            set(spacing) { value -> setSpacing(value) }
            set(durationMs) { value -> animationDuration = value }
            set(easing) { value -> animationEasing = value }
        },
    ) {
        content()
    }
}

internal class GtkAnimatedWidthNode(
    durationMs: Int,
    easing: Easing,
    spacing: Int,
) : GtkNode(gtk_scrolled_window_new()!!), SizeTween {

    private val contentBox: CPointer<GtkWidget> =
        gtk_box_new(GtkOrientation.GTK_ORIENTATION_HORIZONTAL, spacing)!!

    internal var animationDuration: Int = durationMs.coerceAtLeast(1)
    internal var animationEasing: Easing = easing

    private var initialized = false
    private var animating = false
    private var pendingStart = false
    private var startNanos = 0L
    private var frames = 0

    private var currentW = -1f
    private var fromW = -1f
    private var targetW = -1f
    private var contentH = -1f

    private var cancelled = false
    override val active: Boolean get() = !cancelled

    init {
        val scrolled = widget.reinterpret<GtkScrolledWindow>()
        gtk_scrolled_window_set_policy(
            scrolled,
            GtkPolicyType.GTK_POLICY_NEVER,
            GtkPolicyType.GTK_POLICY_NEVER,
        )
        gtk_scrolled_window_set_propagate_natural_width(scrolled, 0)
        gtk_scrolled_window_set_propagate_natural_height(scrolled, 0)
        gtk_scrolled_window_set_child(scrolled, contentBox)
        gtk_widget_set_overflow(widget, GtkOverflow.GTK_OVERFLOW_HIDDEN)
        SizeBehaviorRegistry.add(this)
    }

    override fun attachChild(child: CPointer<GtkWidget>) {
        gtk_box_append(contentBox.reinterpret<GtkBox>(), child)
    }

    override fun detachChild(child: CPointer<GtkWidget>) {
        gtk_box_remove(contentBox.reinterpret<GtkBox>(), child)
    }

    fun setSpacing(spacing: Int) {
        val box = contentBox.reinterpret<GtkBox>()
        gtk_box_set_spacing(box, spacing)
    }

    override fun cancelAnimations() {
        super.cancelAnimations()
        cancelled = true
        SizeBehaviorRegistry.remove(this)
    }

    override fun tick(nanos: Long) {
        if (cancelled) return

        if (!initialized) {
            val measured = measureContent() ?: return
            if (measured.first <= 0 && measured.second <= 0) return
            currentW = measured.first.toFloat()
            targetW = currentW
            contentH = measured.second.toFloat()
            initialized = true
            apply()
            return
        }

        frames += 1
        if (animating || frames % 6 == 0) {
            val measured = measureContent()
            if (measured != null) {
                val newTarget = measured.first.toFloat()
                contentH = measured.second.toFloat()
                if (newTarget != targetW) {
                    fromW = currentW
                    targetW = newTarget
                    if (!animating) {
                        pendingStart = true
                        animating = true
                    }
                }
            }
        }

        if (!animating) {
            apply()
            return
        }
        if (pendingStart) {
            pendingStart = false
            startNanos = nanos
        }
        val progress = ((nanos - startNanos) / 1_000_000f / animationDuration).coerceIn(0f, 1f)
        val eased = animationEasing.eased(progress)
        currentW = if (fromW < 0f || targetW < 0f) targetW else fromW + (targetW - fromW) * eased
        if (progress >= 1f) {
            currentW = targetW
            animating = false
        }
        apply()
    }

    private fun apply() {
        val widthPx = if (currentW < 0f) -1 else currentW.roundToInt()
        val heightPx = if (contentH < 0f) -1 else contentH.roundToInt()
        gtk_widget_set_size_request(widget, widthPx, heightPx)
    }

    /** Natural size of the content; the wrapper's own natural size is disabled. */
    private fun measureContent(): Pair<Int, Int>? {
        var width = -1
        var height = -1
        memScoped {
            val req = alloc<GtkRequisition>()
            gtk_widget_get_preferred_size(contentBox, null, req.ptr)
            width = req.width
            height = req.height
        }
        if (width < 0 || height < 0) return null
        return width to height
    }
}
