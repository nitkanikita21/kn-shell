package dev.knshell.uikit.a11y

import dev.knshell.compose.Key
import dev.knshell.uikit.core.UOrientation
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UThemeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class RovingTest {

    private val keys = listOf("a", "b", "c")

    private fun move(from: String, key: Key, orientation: UOrientation? = null, loop: Boolean = true) =
        rovingTarget(keys, from, key, orientation, loop)

    @Test
    fun `arrows go to the neighbour and around the ends`() {
        assertEquals("b", move("a", Key.Down))
        assertEquals("b", move("a", Key.Right))
        assertEquals("c", move("a", Key.Up))
        assertEquals("a", move("c", Key.Right))
        assertEquals("c", move("c", Key.Down, loop = false))
        assertEquals("a", move("c", Key.Home))
        assertEquals("c", move("a", Key.End))
    }

    @Test
    fun `an orientation leaves the other axis alone`() {
        assertNull(move("a", Key.Down, UOrientation.Horizontal))
        assertNull(move("a", Key.Right, UOrientation.Vertical))
        assertEquals("b", move("a", Key.Right, UOrientation.Horizontal))
        assertEquals("b", move("a", Key.Down, UOrientation.Vertical))
        assertNull(move("a", Key.Enter))
    }
}

class TypeaheadTest {

    private val labels = listOf("Copy", "Cut", "Paste", "Close")
    private val time = TestTimeSource()
    private val typeahead = UTypeahead(timeout = 500.milliseconds, time = time)

    @Test
    fun `one letter walks through the items that start with it`() {
        assertEquals(0, typeahead.type('c', labels, -1))
        assertEquals(1, typeahead.type('c', labels, 0))
        assertEquals(3, typeahead.type('C', labels, 1))
        assertEquals(0, typeahead.type('c', labels, 3))
    }

    @Test
    fun `letters typed in a row search for a prefix until a pause`() {
        assertEquals(0, typeahead.type('c', labels, -1))
        assertEquals(3, typeahead.type('l', labels, 0))
        time += 1.seconds
        assertEquals(2, typeahead.type('p', labels, 3))
        time += 1.seconds
        assertNull(typeahead.type('z', labels, 2))
    }
}

class ThemeConfigTest {

    private data class First(val value: Int) : UComponentDefaults
    private data class Second(val value: Int) : UComponentDefaults

    @Test
    fun `an inner configuration replaces only the classes it names`() {
        val merged = UThemeConfig(First(1), Second(2)) + UThemeConfig(First(3))

        assertEquals(First(3), merged.find<First>())
        assertEquals(Second(2), merged.find<Second>())
        assertNull(UThemeConfig().find<First>())
    }
}
