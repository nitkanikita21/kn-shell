package dev.knshell.atlas.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InspectPathTest {

    @Test
    fun `inspect path comes from the equals form`() {
        assertEquals("/run/user/1000/atlas.sock", parseInspectPath(arrayOf("--inspect=/run/user/1000/atlas.sock"), null))
    }

    @Test
    fun `inspect path comes from the separate value form`() {
        assertEquals("/run/user/1000/atlas.sock", parseInspectPath(arrayOf("--inspect", "/run/user/1000/atlas.sock"), null))
    }

    @Test
    fun `inspect path falls back to the environment`() {
        assertEquals("/tmp/from-env.sock", parseInspectPath(emptyArray(), "/tmp/from-env.sock"))
    }

    @Test
    fun `argument beats the environment`() {
        assertEquals("/tmp/from-arg.sock", parseInspectPath(arrayOf("--inspect=/tmp/from-arg.sock"), "/tmp/from-env.sock"))
        assertEquals("/tmp/from-arg.sock", parseInspectPath(arrayOf("--inspect", "/tmp/from-arg.sock"), "/tmp/from-env.sock"))
    }

    @Test
    fun `no path without argument or environment`() {
        assertNull(parseInspectPath(emptyArray(), null))
        assertNull(parseInspectPath(arrayOf("--story", "Button/Primary"), null))
    }

    @Test
    fun `blank path is no path`() {
        assertNull(parseInspectPath(arrayOf("--inspect="), null))
        assertNull(parseInspectPath(arrayOf("--inspect", "  "), null))
        assertNull(parseInspectPath(emptyArray(), ""))
        assertNull(parseInspectPath(emptyArray(), "   "))
    }

    @Test
    fun `flag without a value is no path`() {
        assertNull(parseInspectPath(arrayOf("--inspect"), null))
    }
}
