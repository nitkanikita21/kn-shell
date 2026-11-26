@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.knshell.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import dev.knshell.animation.Easing
import dev.knshell.animation.eased
import dev.knshell.animation.lerpColor
import dev.knshell.icons.IconNode
import dev.knshell.icons.IconRef
import dev.knshell.icons.colorToHex
import dev.knshell.icons.hexToColor
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Vector icon from an [IconRef] (`Lucide.Home`, `Material.Search`, …), rasterized
 * by librsvg inside a GTK drawing area — crisp at any size and on HiDPI: the
 * cairo context a `GtkDrawingArea` draws into is already device-scaled, so the
 * vector is rasterized at physical resolution by librsvg itself.
 *
 * ```kotlin
 * // static, themed tint
 * Icon(Lucide.Home, size = 22, color = c.primaryForeground)
 *
 * // animated tint on hover — zero recompositions during the tween
 * var hover by remember { mutableStateOf(false) }
 * Icon(Lucide.Power, size = 16, color = if (hover) c.foreground else c.primary, transitionMs = 180)
 * ```
 *
 * @param icon pack-qualified reference; unknown ids warn once and paint an empty slot.
 * @param modifier normal DSL modifiers (`onClick`, `pointerCursor`, margins …), applied to the
 *   node through the same `applyModifierFromModule` pipeline as every framework widget.
 * @param size icon box in logical px; the widget requests exactly size×size
 * (like `Image`), and the artwork is always centered inside whatever cell the
 * parent allocates — flex containers may stretch the cell, never the icon.
 * @param color tint applied through the pack's `tintAttribute`; `null` keeps the
 *   pack's native colors (useful for multicolor packs).
 * @param transitionMs when > 0 and the *previous* frame had a color, changes to
 *   [color] animate over this many ms with an OutCubic curve (same curve as
 *   [dev.knshell.animation.animateFloatAsState]); the first paint never animates.
 * @param strokeWidth stroke-width override; `null` = the pack default (Lucide 2.0).
 */
@Composable
fun Icon(
    icon: IconRef,
    modifier: Modifier = Modifier,
    size: Int = 24,
    color: Color? = null,
    transitionMs: Int = 200,
    strokeWidth: Double? = null,
) {
    // The factory runs once, so a plain local would be `null` on every recomposition: the node the
    // effect below drives has to be remembered. The updates reach it as their receiver.
    val created = remember { arrayOfNulls<IconNode>(1) }
    ComposeNode<IconNode, GtkApplier>(
        factory = {
            IconNode(icon, size).plain().also { created[0] = it }
        },
        update = {
            set(modifier) { value -> applyModifier(value) }
            set(icon) { value ->
                if (ref != value) {
                    ref = value
                    // A new icon starts its own color transition from scratch.
                    transitionStartNs = 0L
                    transitionTargetHex = null
                    requestRedraw()
                }
            }
            set(size) { value -> resize(value) }
            set(strokeWidth) { value ->
                if (strokeWidth != value) {
                    this.strokeWidth = value
                    requestRedraw()
                }
            }
            // Immediate assignment, unless the colour is to fade from the one painted now: then
            // the frame loop below owns it.
            set(color) { value ->
                if (transitionMs <= 0 || colorHex == null || value == null) {
                    colorHex = value?.let { colorToHex(it) }
                    requestRedraw()
                }
            }
        },
    )

    // Drive the tint tween off the frame clock, writing straight into the node —
    // the pattern ShaderCanvas uses for per-frame GL uniforms. Ends as soon as
    // the transition completes or the color/params change again (effect restarts).
    LaunchedEffect(icon, color, transitionMs, size) {
        val n = created[0] ?: return@LaunchedEffect
        val target = color ?: return@LaunchedEffect
        if (transitionMs <= 0) {
            n.transitionStartNs = 0L
            return@LaunchedEffect
        }
        // Only animate away from a color we actually painted; cold start is instant.
        val fromHex = n.colorHex ?: colorToHex(target)
        val from = hexToColor(fromHex)
        val targetHex = colorToHex(target)
        n.transitionTargetHex = targetHex
        n.transitionDurationNs = transitionMs * 1_000_000L
        n.transitionStartNs = withFrameNanos { it }
        val start = n.transitionStartNs
        while (true) {
            val now = withFrameNanos { it }
            val elapsed = now - start
            if (n.transitionTargetHex != targetHex) break // superseded by newer input
            if (elapsed >= n.transitionDurationNs) break // the final value is assigned below
            val t = (elapsed.toFloat() / n.transitionDurationNs.toFloat()).coerceIn(0f, 1f)
            val easedT = Easing.OutCubic.eased(t)
            n.colorHex = colorToHex(lerpColor(from, target, easedT))
            n.requestRedraw()
        }
        n.transitionStartNs = 0L
        n.transitionTargetHex = null
        n.colorHex = targetHex
        n.requestRedraw()
    }
}
