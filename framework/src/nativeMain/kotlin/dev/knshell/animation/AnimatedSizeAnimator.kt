// Per-widget animator for a container's main-axis size: registry membership, GTK `"destroy"`
// cleanup and the entry point the `animatedWidth`/`animatedHeight` modifiers call.

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.animation

import dev.knshell.compose.AnimatedSizeElement
import dev.knshell.core.animatedSizeContent
import dev.knshell.core.attachAnimatedSizeLayout
import dev.knshell.core.setAnimatedSizeCross
import dev.knshell.core.setAnimatedSizeCurrent
import dev.knshell.core.setAnimatedSizeFixed
import kn.gtk.GtkWidget
import kn.gtk.g_signal_connect_data
import kn.gtk.gtk_widget_get_mapped
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlin.math.abs

/**
 * Stateful, Compose-owned animator for one container widget's main-axis size.
 *
 * This is the logic that used to live on the old `GtkAnimatedBoxNode`, lifted out of the node
 * and into a free object keyed by the target widget's pointer. The modifier in
 * `dev.knshell.compose` only describes the animation ([AnimatedSizeElement]); the actual
 * [SizeTween] and the registry membership live here, so there is no dedicated `ComposeNode` and
 * no special composable.
 *
 * Lifecycle: [applyAnimatedSizeModifier] get-or-creates one of these per widget (the native
 * layout manager is attached exactly once) and reconnects nothing else — the widget's GTK
 * `"destroy"` signal disposes it (see [destroyHandler]). A widget is finalized by GTK when it is
 * unparented, which is exactly what `GtkApplier.removeChild` does, so disposal also fires when
 * a node leaves the Compose tree.
 */
internal class AnimatedSizeAnimator(
    private val handle: ULong,
    private val widget: CPointer<GtkWidget>,
    private val vertical: Boolean,
    spacing: Int,
) : ContentSizeAnimator {

    private val spacing: Int = spacing.coerceAtLeast(0)
    private var spec: AnimationSpec = AnimationSpec.Tween()
    private var mainSize: Int? = null
    private var minMainSize: Int? = null
    private var crossSize: Int? = null

    private val nodeTween = SizeTween()

    /** Consecutive frames with the same, non-zero natural size during initialisation. */
    private var initFrames: Int = 0

    /** Frames spent waiting for a mapped, non-empty widget; bounded so an unseen widget cannot spin. */
    private var initAttempts: Int = 0

    /** Last natural size seen while initialising (`< 0` = not observed yet). */
    private var lastProbe: Double = -1.0

    private var initialized: Boolean = false
    private var disposed: Boolean = false

    /** Last value pushed to C — guards redundant queue_resize traffic. NaN = nothing pushed yet. */
    private var lastApplied: Double = Double.NaN

    init {
        // The native manager ignores duration/easing (it no longer animates); pass zeros.
        attachAnimatedSizeLayout(
            handle,
            0u,
            0u,
            spacing.toUInt(),
            if (vertical) 1u else 0u,
        )
        SizeAnimationRegistry.register(this)

        // Kept alive for the lifetime of the GTK widget: without this the StableRef created
        // below may be garbage-collected and the destroy handler would receive a dangling
        // pointer. The widget owns the only reference to this animator (via the signal), so
        // once the widget is finalized the animator is unreachable and collected.
        @Suppress("unused")
        val selfRef = StableRef.create(this)
        g_signal_connect_data(
            instance = widget,
            detailed_signal = "destroy",
            c_handler = destroyHandler.reinterpret(),
            data = selfRef.asCPointer(),
            destroy_data = null,
            connect_flags = 0u,
        )
    }

    /** Pushes the latest parameters (called on every modifier change for this widget). */
    internal fun update(target: Int?, spec: AnimationSpec, minMainSize: Int?, crossSize: Int?) {
        this.spec = spec
        this.mainSize = target
        this.minMainSize = minMainSize
        this.crossSize = crossSize
        setAnimatedSizeFixed(handle, target ?: minMainSize ?: -1)
        setAnimatedSizeCross(handle, crossSize ?: -1)
        requestFrame()
    }

    /** Effective main-axis target: explicit [mainSize] wins, but never below [minMainSize]. */
    private fun effectiveTarget(natural: Double): Double {
        val base = mainSize?.toDouble() ?: natural
        val floor = minMainSize?.toDouble() ?: 0.0
        return maxOf(base, floor)
    }

    private fun requestFrame() {
        if (disposed) return
        if (!initialized) initAttempts = 0
        SizeAnimationRegistry.register(this)
    }

    override fun probeAndRetarget() {
        if (disposed) return
        val natural = animatedSizeContent(handle)

        if (!initialized) {
            val mapped = gtk_widget_get_mapped(widget) != 0
            // Empty content with an explicit mainSize is legitimate: the explicit size is
            // the target, the natural size only has to be stable (it may stay 0).
            val needsNaturalSize = mainSize == null
            if (!mapped || (needsNaturalSize && natural <= 0.0)) {
                initFrames = 0
                lastProbe = natural
                return
            }
            if (lastProbe < 0.0 || abs(natural - lastProbe) > STABLE_EPSILON) {
                lastProbe = natural
                initFrames = 1
                return
            }
            initFrames += 1
            if (initFrames < INIT_STABLE_FRAMES) return

            initialized = true
            // Initial target: explicit mainSize wins over the natural content size, but both
            // are floored by minMainSize.
            nodeTween.snapTo(effectiveTarget(natural))
            lastApplied = Double.NaN
            return
        }

        // Content-driven target is the natural size at for_size = -1: it never depends on
        // `nodeTween.current`, so the layout cannot feed back into the animation target.
        // Calling retarget every frame is safe: identical target+spec is a no-op, and a spec
        // change (from `update`) is picked up here even when the target stayed the same.
        retargetQuantized(effectiveTarget(natural))
    }

    /**
     * Retargets the tween, ignoring sub-pixel noise.
     *
     * The native allocation rounds to whole pixels (`lround`), so a target change below 1 px
     * can never change a visible frame — but without this guard every Pango re-measurement
     * (sub-pixel hinting jitter) would restart the 180 ms animation and keep the frame clock
     * ticking forever on a "static" UI (e.g. a clock label re-measured every frame).
     */
    private fun retargetQuantized(target: Double) {
        val current = nodeTween.target
        if (target == current) {
            // Exact hit (the common case: unchanged content): still propagate a spec change.
            nodeTween.retarget(target, spec)
            return
        }
        if (abs(target - current) < RETARGET_EPSILON_PX) return
        nodeTween.retarget(target, spec)
    }

    override fun advanceFrame(nanos: Long): Boolean {
        val animating = nodeTween.advance(nanos)
        if (initialized) applyCurrent()
        if (!initialized) {
            initAttempts += 1
            return initAttempts < MAX_INIT_ATTEMPTS
        }
        return animating
    }

    /** Pushes the current value to C; rounding (and `queue_resize` de-duplication) stay there. */
    override fun applyCurrent() {
        if (!initialized || disposed) return
        val value = nodeTween.current
        if (value == lastApplied) return
        lastApplied = value
        setAnimatedSizeCurrent(handle, value)
    }

    /** Removes this animator from the registry and the widget map; idempotent. */
    internal fun dispose() {
        if (disposed) return
        disposed = true
        SizeAnimationRegistry.unregister(this)
        animators.remove(handle)
    }

    private companion object {
        /** Two consecutive equal probes (≈ 2 frames) before the initial snap. */
        const val INIT_STABLE_FRAMES = 2

        /** Natural-size change smaller than this is measurement noise, not a retarget. */
        const val STABLE_EPSILON = 0.5

        /** Target changes below this never move a whole pixel — ignore them (see retargetQuantized). */
        const val RETARGET_EPSILON_PX = 1.0

        /** ~10 s at 60 Hz: stops asking for frames if the widget never becomes mapped/non-empty. */
        const val MAX_INIT_ATTEMPTS = 600
    }
}

/** One animator per live container widget, keyed by the widget's `GtkWidget*` as a `u64`. */
private val animators = mutableMapOf<ULong, AnimatedSizeAnimator>()

/**
 * Applies an [AnimatedSizeElement] to a widget: get-or-creates its [AnimatedSizeAnimator]
 * (attaching the native layout manager exactly once) and pushes the latest parameters.
 *
 * Called from `applyModifiers`, which runs whenever the element's values change, so the
 * animator is created lazily and reused across recompositions of the same widget.
 */
internal fun applyAnimatedSizeModifier(widget: CPointer<GtkWidget>, element: AnimatedSizeElement) {
    val handle = widget.rawValue.toLong().toULong()
    val animator = animators.getOrPut(handle) {
        AnimatedSizeAnimator(handle, widget, element.vertical, element.spacing)
    }
    animator.update(element.target, element.spec, element.minMainSize, element.crossSize)
}

/** GTK `"destroy"` handler: drops the animator for the dying widget. */
private val destroyHandler = staticCFunction { _: COpaquePointer?, data: COpaquePointer? ->
    data?.asStableRef<AnimatedSizeAnimator>()?.get()?.dispose()
    Unit
}
