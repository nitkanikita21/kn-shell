package dev.knshell.atlas.dsl

import dev.knshell.atlas.Size
import dev.knshell.atlas.arg
import dev.knshell.atlas.noopRender
import dev.knshell.atlas.variantsNamed
import dev.knshell.atlas.core.arg.Arg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GroupBuilderTest {

    @Test
    fun `duplicate arg name fails the group`() {
        assertFailsWith<IllegalStateException> {
            group("G") {
                render { _ -> }
                run { val text by string("a") }
                run { val text by string("b") }
            }
        }
    }

    @Test
    fun `duplicate story name ignoring case fails the group`() {
        assertFailsWith<IllegalStateException> {
            group("G") {
                render { _ -> }
                story("Primary")
                story("primary")
            }
        }
    }

    @Test
    fun `story and variants sharing a name fail the group`() {
        assertFailsWith<IllegalStateException> {
            group("G") {
                render { _ -> }
                story("Primary")
                variants("PRIMARY") { }
            }
        }
    }

    @Test
    fun `duplicate variants name fails the group`() {
        assertFailsWith<IllegalStateException> {
            group("G") {
                render { _ -> }
                variants("Matrix") { }
                variants("matrix") { }
            }
        }
    }

    @Test
    fun `story without render and without group render fails the group`() {
        assertFailsWith<IllegalStateException> {
            group("G") { story("A") }
        }
    }

    @Test
    fun `variants without render and without group render fails the group`() {
        assertFailsWith<IllegalStateException> {
            group("G") { variants("M") { } }
        }
    }

    @Test
    fun `story with its own render needs no group render`() {
        val g = group("G") { story("A", noopRender) }
        assertEquals(listOf("A"), g.entries.map { it.name })
    }

    @Test
    fun `int default below range fails the group`() {
        assertFailsWith<IllegalArgumentException> {
            group("G") { render { _ -> }; val n by int(5, 0..3) }
        }
    }

    @Test
    fun `int default above range fails the group`() {
        assertFailsWith<IllegalArgumentException> {
            group("G") { render { _ -> }; val n by int(4, 0..3) }
        }
    }

    @Test
    fun `int default on range boundary is accepted`() {
        group("G") { render { _ -> }; val n by int(3, 0..3) }
    }

    @Test
    fun `float default outside range fails the group`() {
        assertFailsWith<IllegalArgumentException> {
            group("G") { render { _ -> }; val f by float(1.5f, 0f..1f) }
        }
    }

    @Test
    fun `float default on range boundary is accepted`() {
        group("G") { render { _ -> }; val f by float(1f, 0f..1f) }
    }

    @Test
    fun `combos keep the first axis as the slowest changing one`() {
        val g = group("G") {
            val size by enum(Size.S)
            val enabled by boolean(true)
            render { _ -> }
            variants("M") { axis(size); axis(enabled) }
        }
        val size: Arg.Choice<Size> = g.arg("size")
        val enabled: Arg.Flag = g.arg("enabled")
        val combos = g.variantsNamed("M").combos
        assertEquals(
            listOf(
                Size.S to true, Size.S to false,
                Size.M to true, Size.M to false,
                Size.L to true, Size.L to false,
            ),
            combos.map { it[size] to it[enabled] },
        )
    }

    @Test
    fun `axis with explicit values uses those values in order`() {
        val g = group("G") {
            val n by int(0, 0..9)
            render { _ -> }
            variants("M") { axis(n, listOf(7, 2, 5)) }
        }
        val n: Arg.IntNum = g.arg("n")
        assertEquals(listOf(7, 2, 5), g.variantsNamed("M").combos.map { it[n] })
    }

    @Test
    fun `exclude removes matching combos`() {
        val g = group("G") {
            val size by enum(Size.S)
            val enabled by boolean(true)
            render { _ -> }
            variants("M") {
                axis(size)
                axis(enabled)
                exclude { s -> s[enabled] == false && s[size] == Size.L }
            }
        }
        val size: Arg.Choice<Size> = g.arg("size")
        val enabled: Arg.Flag = g.arg("enabled")
        val combos = g.variantsNamed("M").combos
        assertEquals(5, combos.size)
        assertEquals(false, combos.any { it[size] == Size.L && it[enabled] == false })
    }

    @Test
    fun `exactly 500 combos is accepted`() {
        val g = group("G") {
            val n by int(0, 0..499)
            render { _ -> }
            variants("M") { axis(n, (0..499).toList()) }
        }
        assertEquals(500, g.variantsNamed("M").combos.size)
    }

    @Test
    fun `more than 500 combos fails the group`() {
        assertFailsWith<IllegalArgumentException> {
            group("G") {
                val n by int(0, 0..600)
                render { _ -> }
                variants("M") { axis(n, (0..600).toList()) }
            }
        }
    }

    @Test
    fun `exclude is applied before the 500 limit is checked`() {
        // 600 raw combos, 500 of them excluded: the remaining 100 must not trip the limit.
        val g = group("G") {
            val n by int(0, 0..599)
            render { _ -> }
            variants("M") {
                axis(n, (0..599).toList())
                exclude { s -> s[n] >= 100 }
            }
        }
        assertEquals(100, g.variantsNamed("M").combos.size)
    }

    @Test
    fun `axis taken from another group fails the group`() {
        val other = group("Other") {
            val size by enum(Size.S)
            render { _ -> }
            story("X")
        }
        assertFailsWith<IllegalStateException> {
            group("Main") {
                render { _ -> }
                variants("M") { axis(other.arg<Arg.Choice<Size>>("size")) }
            }
        }
    }
}
