package dev.knshell.animation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the Compose-owned size animation engine (§4.4, §5 of
 * docs/tasks/compose-owned-animated-size.md).
 *
 * `SizeTween` and `SizeAnimationRegistry` are pure logic (no GTK calls), so they run on
 * any platform with synthetic timestamps. The key property under test: time-based
 * animation — the same wall-clock duration produces the same progress on 60 Hz and
 * 144 Hz frame cadences (smooth on any refresh rate).
 */
class SizeTweenTest {

    private companion object {
        const val MS = 1_000_000L
    }

    @Test
    fun `first frame snaps to target without animating`() {
        val tween = SizeTween()
        tween.retarget(100.0, AnimationSpec.Tween(180))
        assertFalse(tween.advance(0L), "first frame only stamps the timestamp")
        assertEquals(100.0, tween.current, 1e-9)
        assertFalse(tween.isAnimating)
    }

    @Test
    fun `tween completes in wall-clock duration at 60 Hz`() {
        val tween = SizeTween()
        tween.snapTo(0.0)
        tween.retarget(100.0, AnimationSpec.Tween(180))
        var t = 1_000_000_000L
        var frames = 0
        tween.advance(t) // stamp
        while (tween.isAnimating && frames < 1000) {
            t += 16_666_667L // 60 Hz
            tween.advance(t)
            frames++
        }
        assertEquals(100.0, tween.current, 1e-6)
        // 180 ms / 16.67 ms ≈ 11 frames (first frame is the stamp).
        assertTrue(frames in 9..14, "expected ~11 frames at 60 Hz, got $frames")
    }

    @Test
    fun `tween completes in same wall-clock time at 144 Hz`() {
        val tween = SizeTween()
        tween.snapTo(0.0)
        tween.retarget(100.0, AnimationSpec.Tween(180))
        var t = 1_000_000_000L
        tween.advance(t) // stamp
        // 144 Hz: 26 frames × 6.94 ms ≈ 180 ms.
        repeat(26) {
            t += 6_944_444L
            tween.advance(t)
        }
        assertEquals(100.0, tween.current, 1e-6)
    }

    @Test
    fun `same elapsed time gives same value on 60 and 144 Hz`() {
        fun valueAt(stepNanos: Long, steps: Int): Double {
            val tween = SizeTween()
            tween.snapTo(0.0)
            tween.retarget(100.0, AnimationSpec.Tween(200))
            var t = 1_000_000_000L
            tween.advance(t)
            repeat(steps) {
                t += stepNanos
                tween.advance(t)
            }
            return tween.current
        }
        // ~100 ms of animation on both cadences (6×16.67 ms ≈ 14×6.94 ms).
        val at60 = valueAt(16_666_667L, 6)
        val at144 = valueAt(6_944_444L, 14)
        // Frame quantisation differs, but both must be near the eased midpoint,
        // never teleporting: OutCubic(0.5) = 0.875 → ~87.5.
        assertTrue(at60 in 80.0..95.0, "60 Hz value out of range: $at60")
        assertTrue(at144 in 80.0..95.0, "144 Hz value out of range: $at144")
    }

    @Test
    fun `clamped dt cannot teleport the value after a pause`() {
        val tween = SizeTween()
        tween.snapTo(0.0)
        tween.retarget(100.0, AnimationSpec.Tween(1000))
        var t = 1_000_000_000L
        tween.advance(t)
        t += 16 * MS
        tween.advance(t)
        // 5 s pause (hidden window): dt clamped to 50 ms, value must not jump to target.
        t += 5_000 * MS
        tween.advance(t)
        assertTrue(tween.current < 100.0, "value teleported after a pause: ${tween.current}")
        assertTrue(tween.isAnimating)
    }

    @Test
    fun `retarget restarts from current value without jumping`() {
        val tween = SizeTween()
        tween.snapTo(0.0)
        tween.retarget(100.0, AnimationSpec.Tween(1000))
        var t = 1_000_000_000L
        tween.advance(t)
        repeat(10) {
            t += 16 * MS
            tween.advance(t)
        }
        val mid = tween.current
        assertTrue(mid in 1.0..99.0, "expected mid-flight value, got $mid")
        tween.retarget(200.0, AnimationSpec.Tween(180))
        t += 16 * MS
        tween.advance(t)
        // Moved towards the new target, never snapped back to 0 or to 200 at once.
        assertTrue(tween.current >= mid, "jumped backwards: $mid -> ${tween.current}")
        assertTrue(tween.current < 200.0)
    }

    @Test
    fun `snap settles immediately`() {
        val tween = SizeTween()
        tween.snapTo(10.0)
        tween.retarget(50.0, AnimationSpec.Snap)
        assertFalse(tween.advance(123L))
        assertEquals(50.0, tween.current, 1e-9)
    }

    @Test
    fun `spring settles without feedback loop`() {
        val tween = SizeTween()
        tween.snapTo(0.0)
        tween.retarget(100.0, AnimationSpec.Spring(stiffness = 500f, dampingRatio = 0.7f))
        var t = 1_000_000_000L
        tween.advance(t)
        var frames = 0
        while (tween.isAnimating && frames < 600) {
            t += 7 * MS // ~144 Hz
            tween.advance(t)
            frames++
        }
        assertFalse(tween.isAnimating, "spring did not settle in 600 frames")
        assertEquals(100.0, tween.current, 1e-6)
    }

    // ------------------------------------------------------------------
    // SizeAnimationRegistry: grace frame + hasWork
    // ------------------------------------------------------------------

    private class FakeNode(
        var natural: Double = 100.0,
        var needsFrames: Boolean = true,
    ) : ContentSizeAnimator {
        var probes = 0
        var applied = 0
        var lastRetarget = -1.0
        private val tween = SizeTween().also { it.snapTo(100.0) }

        override fun probeAndRetarget() {
            probes++
            lastRetarget = natural
            tween.retarget(natural, AnimationSpec.Tween(180))
        }

        override fun advanceFrame(nanos: Long): Boolean {
            val more = tween.advance(nanos)
            return more || needsFrames
        }

        override fun applyCurrent() {
            applied++
        }
    }

    @Test
    fun `registry reports no work when idle`() {
        val node = FakeNode(needsFrames = false)
        SizeAnimationRegistry.register(node)
        try {
            // First tick: snap path may request the grace frame; drain it.
            repeat(5) { SizeAnimationRegistry.tick(it * 16 * MS) }
            assertFalse(SizeAnimationRegistry.hasWork, "idle registry must report no work")
        } finally {
            SizeAnimationRegistry.unregister(node)
        }
    }

    @Test
    fun `registry ticks mounted nodes and applies values`() {
        val node = FakeNode(needsFrames = true)
        SizeAnimationRegistry.register(node)
        try {
            assertTrue(SizeAnimationRegistry.hasWork)
            repeat(3) { SizeAnimationRegistry.tick(1_000_000_000L + it * 16 * MS) }
            assertTrue(node.probes >= 3)
            assertTrue(node.applied >= 3)
        } finally {
            SizeAnimationRegistry.unregister(node)
        }
    }

    @Test
    fun `unregistered node stops receiving ticks`() {
        val node = FakeNode(needsFrames = true)
        SizeAnimationRegistry.register(node)
        SizeAnimationRegistry.tick(0L)
        val probes = node.probes
        SizeAnimationRegistry.unregister(node)
        SizeAnimationRegistry.tick(16 * MS)
        assertEquals(probes, node.probes, "unregistered node was ticked")
    }
}
