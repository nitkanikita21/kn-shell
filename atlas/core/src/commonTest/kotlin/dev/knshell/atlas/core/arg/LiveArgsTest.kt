package dev.knshell.atlas.core.arg

import dev.knshell.atlas.Size
import dev.knshell.atlas.arg
import dev.knshell.atlas.sampleGroup
import dev.knshell.atlas.variantsNamed
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveArgsTest {
    private val group = sampleGroup()
    private val text: Arg.Text = group.arg("text")
    private val enabled: Arg.Flag = group.arg("enabled")
    private val size: Arg.Choice<Size> = group.arg("size")
    private val n: Arg.IntNum = group.arg("n")
    private val f: Arg.FloatNum = group.arg("f")

    @Test
    fun `live args start with the declared defaults`() {
        val live = LiveArgs(group.args)
        assertEquals("Save", live[text])
        assertEquals(true, live[enabled])
        assertEquals(Size.S, live[size])
        assertEquals(16, live[n])
        assertEquals(0.5f, live[f])
    }

    @Test
    fun `set changes the value read back`() {
        val live = LiveArgs(group.args)
        live.set(n, 30)
        live.set(text, "Go")
        assertEquals(30, live[n])
        assertEquals("Go", live[text])
    }

    @Test
    fun `reset restores every arg to its default`() {
        val live = LiveArgs(group.args)
        live.set(n, 30)
        live.set(text, "Go")
        live.set(enabled, false)
        live.set(size, Size.L)
        live.set(f, 0.9f)
        live.reset()
        assertEquals(16, live[n])
        assertEquals("Save", live[text])
        assertEquals(true, live[enabled])
        assertEquals(Size.S, live[size])
        assertEquals(0.5f, live[f])
    }

    @Test
    fun `two live instances do not share values`() {
        val first = LiveArgs(group.args)
        val second = LiveArgs(group.args)
        first.set(n, 40)
        assertEquals(16, second[n])
    }

    @Test
    fun `cell args take an axis value from the combo`() {
        val live = LiveArgs(group.args)
        live.set(size, Size.L)
        val combo = group.variantsNamed("Sizes").combos.first { it[size] == Size.M }
        val cell = CellArgs(combo, live)
        assertEquals(Size.M, cell[size])
    }

    @Test
    fun `cell args take non axis args from live`() {
        val live = LiveArgs(group.args)
        live.set(text, "Go")
        live.set(n, 40)
        live.set(enabled, false)
        val combo = group.variantsNamed("Sizes").combos.first { it[size] == Size.M }
        val cell = CellArgs(combo, live)
        assertEquals("Go", cell[text])
        assertEquals(40, cell[n])
        assertEquals(false, cell[enabled])
    }

    @Test
    fun `cell args take an axis flag from the combo even when live differs`() {
        val live = LiveArgs(group.args)
        live.set(enabled, false)
        val combo = group.variantsNamed("Matrix").combos.first { it[size] == Size.S && it[enabled] == true }
        val cell = CellArgs(combo, live)
        assertEquals(true, cell[enabled])
    }
}
