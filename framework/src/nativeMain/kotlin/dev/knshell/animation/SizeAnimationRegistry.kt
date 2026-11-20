// Per-frame tick of every mounted size animator: probe the natural size, advance the tween,
// push the value to the native layout manager.

package dev.knshell.animation

/**
 * An animator that owns one content-driven animated size (one `animatedWidth`/`animatedHeight`
 * modifier instance attached to a container widget).
 *
 * Contract per tick, in this order:
 *  1. [probeAndRetarget] — read the natural content size from GTK and call
 *     `SizeTween.retarget` when it changed (or when the node is dirty);
 *  2. [advanceFrame] — integrate the frame with `SizeTween.advance`, report whether the
 *     node needs a next frame;
 *  3. [applyCurrent] — push the resulting value to the native layout manager.
 *
 * The registry never touches the frame clock and the nodes never touch it either: the clock
 * (see `GtkFrameClock`) calls [SizeAnimationRegistry.tick] and keeps its tick callback
 * installed only while [SizeAnimationRegistry.hasWork] is true.
 *
 * Main thread only: [probeAndRetarget] calls `gtk_widget_measure` through the C helper, and
 * [applyCurrent] calls `queue_resize`. Both are safe in a tick callback (outside a layout
 * pass), never from `allocate`.
 */
internal interface ContentSizeAnimator {

    /** Probes the natural content size and retargets the tween; never applies it. */
    fun probeAndRetarget()

    /** Advances one frame; returns true while the node still needs frames. */
    fun advanceFrame(nanos: Long): Boolean

    /** Pushes the current value to the native layout manager (rounded only in C). */
    fun applyCurrent()
}

/**
 * Registry of mounted content-size animators, ticked once per frame by the frame clock.
 *
 * Two sets:
 *  - [nodes] — every mounted animator. They stay here for their whole lifetime, so *any*
 *    frame produced by Compose (a recomposition after a text change, another animation)
 *    also probes idle nodes. This is what lets a `Label` change its text and still start a
 *    size animation even though the modifier-bearing container itself was not recomposed.
 *  - [requesting] — the subset that explicitly asked for frames (dirty structural change,
 *    new target, initialisation, running animation). [hasWork] reports only this subset, so
 *    an idle UI produces exactly zero frame callbacks.
 *
 * A node calls [register] whenever it needs a next frame, and [unregister] only when it is
 * removed from the tree; the tick loop itself moves it in and out of [requesting] based on
 * [ContentSizeAnimator.advanceFrame]'s return value.
 *
 * ### Grace frame
 *
 * A node that just stopped needing frames is kept in [requesting] for one more frame. This
 * closes the one-frame gap between the tick and the recomposition it woke: the frame clock
 * resumes `withFrameNanos` waiters, but their continuations are dispatched through the GLib
 * main loop, so a `Label` text change is applied *after* the registry was ticked. Without the
 * grace frame the clock would stop right there and the new natural size would never be
 * probed, so the animation would only start on the next unrelated frame. With it, the very
 * next frame probes the updated content. Cost: one extra frame after each animation settles;
 * idle still produces zero frames.
 *
 * The registry does not know (nor care) which clock drives it, so it can be unit-tested by
 * calling [tick] with synthetic timestamps.
 */
internal object SizeAnimationRegistry {

    private val nodes = mutableListOf<ContentSizeAnimator>()
    private val requesting = mutableListOf<ContentSizeAnimator>()
    private val idleFrames = mutableMapOf<ContentSizeAnimator, Int>()

    /** True while at least one node wants a next frame. */
    val hasWork: Boolean
        get() = requesting.isNotEmpty()

    /** Diagnostic snapshot for [frameclock] logging: mounted/requesting counts. */
    fun stats(): String = "mounted=$mountedCount requesting=${requesting.size}"

    /** Number of mounted animators (diagnostics/tests). */
    val mountedCount: Int
        get() = nodes.size

    /** Adds [node] to the mounted set and asks for at least one frame. Idempotent. */
    fun register(node: ContentSizeAnimator) {
        if (nodes.none { it === node }) nodes += node
        if (requesting.none { it === node }) requesting += node
    }

    /** Drops [node] completely; called when it leaves the tree. Idempotent. */
    fun unregister(node: ContentSizeAnimator) {
        nodes.removeAll { it === node }
        requesting.removeAll { it === node }
        idleFrames.remove(node)
    }

    /**
     * One frame: probe → retarget → advance → apply, for every mounted node.
     *
     * Iteration runs over a snapshot because a node may unregister itself while being
     * probed (a child removal triggered from a GTK callback is not expected here, but the
     * tick must not corrupt the list if it happens).
     */
    fun tick(nanos: Long) {
        if (nodes.isEmpty()) return
        val frame = nodes.toList()
        for (node in frame) {
            node.probeAndRetarget()
            val needsFrames = node.advanceFrame(nanos)
            node.applyCurrent()

            if (needsFrames) {
                idleFrames.remove(node)
                if (requesting.none { it === node }) requesting += node
            } else {
                val idle = (idleFrames[node] ?: 0) + 1
                if (idle <= GRACE_FRAMES) {
                    // Keep one more frame: a recomposition woken by this tick is applied
                    // after we return, and the next probe must see its new natural size.
                    idleFrames[node] = idle
                    if (requesting.none { it === node }) requesting += node
                } else {
                    // Grace consumed: stay out of `requesting` WITHOUT resetting the
                    // counter, otherwise every second idle tick would re-grant grace and
                    // the frame clock would never stop (idle must request zero frames).
                    // The counter is cleared on the next real work (branch above).
                    idleFrames[node] = idle
                    requesting.removeAll { it === node }
                }
            }
        }
    }

    private const val GRACE_FRAMES = 1
}
