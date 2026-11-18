@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.knshell.icons

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Cache-behaviour tests. They exercise the librsvg path for real (no display is
 * needed to compile a handle), so they prove the two properties the renderer
 * relies on: one parse per render identity, and a bounded number of live
 * handles no matter how many icons a session touches.
 */
private class StubPack(
    private val bodies: Map<String, String>,
    override val style: IconStyle = IconStyle(),
) : IconPack {
    override val name = "Stub"
    var lookups = 0
    override fun svgInner(id: String): String? {
        lookups++
        return bodies[id]
    }
}

class RsvgCacheTest {

    private val path = "<path d=\"M3 9l9 7 9-7\"/>"

    @AfterTest
    fun reset() {
        RsvgCache.maxEntries = 256
        RsvgCache.clear()
    }

    private fun ref(id: String, pack: IconPack) = IconRef(pack, id)

    @Test
    fun sameIdentityReusesOneDocumentAndOneHandle() {
        val pack = StubPack(mapOf("a" to path))
        val h1 = RsvgCache.handleFor(ref("a", pack), "#FF0000", null)
        val h2 = RsvgCache.handleFor(ref("a", pack), "#FF0000", null)
        assertNotNull(h1)
        assertEquals(h1, h2, "second lookup must be a cache hit")
        assertEquals(1, RsvgCache.documentCount())
        assertEquals(1, RsvgCache.liveHandleCount())
        // The pack is asked once — the document cache sits in front of it.
        assertEquals(1, pack.lookups)
    }

    @Test
    fun colorAndWidthArePartOfTheIdentity() {
        val pack = StubPack(mapOf("a" to path))
        val red = RsvgCache.handleFor(ref("a", pack), "#FF0000", null)
        val green = RsvgCache.handleFor(ref("a", pack), "#00FF00", null)
        val thin = RsvgCache.handleFor(ref("a", pack), "#FF0000", 1.0)
        assertNotNull(red)
        assertNotNull(green)
        assertNotNull(thin)
        assertEquals(red, RsvgCache.handleFor(ref("a", pack), "#FF0000", null))
        assertEquals(3, RsvgCache.documentCount())
        assertEquals(3, RsvgCache.liveHandleCount())
    }

    @Test
    fun evictionDropsLiveHandlesToTheCap() {
        val bodies = (1..8).associate { "i$it" to "<path d=\"M$it $it\"/>" }
        val pack = StubPack(bodies)
        RsvgCache.maxEntries = 3
        for (n in 1..8) {
            assertNotNull(RsvgCache.handleFor(ref("i$n", pack), "#FFFFFF", null))
        }
        assertEquals(3, RsvgCache.documentCount(), "document cache must respect maxEntries")
        assertEquals(3, RsvgCache.liveHandleCount(), "evicted handles must be unref'd, not merely forgotten")
    }

    @Test
    fun evictedIconIsRecreatedOnDemand() {
        val pack = StubPack(mapOf("a" to path, "b" to path))
        RsvgCache.maxEntries = 1
        val first = RsvgCache.handleFor(ref("a", pack), "#FFFFFF", null)
        assertNotNull(first)
        assertNotNull(RsvgCache.handleFor(ref("b", pack), "#FFFFFF", null))
        // "a" was evicted; asking again must yield a usable handle.
        assertNotNull(RsvgCache.handleFor(ref("a", pack), "#FFFFFF", null))
        assertEquals(1, RsvgCache.liveHandleCount())
    }

    @Test
    fun unknownIdIsResolvedOnceAndNeverThrows() {
        val pack = StubPack(mapOf("a" to path))
        assertNull(RsvgCache.handleFor(ref("ghost", pack), "#FFFFFF", null))
        assertNull(RsvgCache.handleFor(ref("ghost", pack), "#FFFFFF", null))
        // Cached as a resolved miss: one pack lookup, no handle, no exception.
        assertEquals(1, pack.lookups, "a missing id must be memoized")
        assertEquals(1, RsvgCache.documentCount())
        assertEquals(0, RsvgCache.liveHandleCount())
    }

    @Test
    fun unparsableMarkupDegradesToEmptySlot() {
        val pack = StubPack(mapOf("bad" to "<path d=\"M1 1\""))
        assertNotNull(pack.svgInner("bad"))
        assertNull(RsvgCache.handleFor(ref("bad", pack), "#FFFFFF", null))
    }
}
