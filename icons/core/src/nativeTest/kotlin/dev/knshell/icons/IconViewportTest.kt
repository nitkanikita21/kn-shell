package dev.knshell.icons

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The centering rule that keeps an icon in the middle of its cell.
 *
 * Regression behind: a `GtkBox` distributes leftover main-axis space into the
 * child's allocation and ignores `valign` there, so a 22px icon inside a 44px
 * box got a 22×44 cell and was painted against its top edge.
 */
class IconViewportTest {

    private fun assertViewport(w: Int, h: Int, size: Int, x: Double, y: Double, side: Double) {
        val (vx, vy, vSide) = iconViewport(w, h, size)
        assertEquals(x, vx, "x offset for ${w}x$h cell, size $size")
        assertEquals(y, vy, "y offset for ${w}x$h cell, size $size")
        assertEquals(side, vSide, "side for ${w}x$h cell, size $size")
    }

    @Test
    fun squareCellDrawsAtRequestedSize() = assertViewport(44, 44, 22, 11.0, 11.0, 22.0)

    @Test
    fun mainAxisStretchIsCenteredNotTopAligned() = assertViewport(22, 44, 22, 0.0, 11.0, 22.0)

    @Test
    fun exactAllocationIsANoOp() = assertViewport(22, 22, 22, 0.0, 0.0, 22.0)

    @Test
    fun squeezedCellCropsToTheCell() = assertViewport(16, 16, 24, 0.0, 0.0, 16.0)

    @Test
    fun zeroSizedCellNeverGoesNegative() = assertViewport(0, 0, 24, 0.0, 0.0, 0.0)
}
