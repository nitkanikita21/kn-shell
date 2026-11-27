package dev.knshell.atlas.runner.golden

import dev.knshell.atlas.dsl.group
import dev.knshell.atlas.play.Play
import dev.knshell.atlas.runner.AtlasEnvironment
import dev.knshell.inspect.InteractionState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Cases per environment, and what states and play scripts add to a run. */
class GoldenStoryTest {

    private val environments = listOf(AtlasEnvironment("Dark") { it() }, AtlasEnvironment("Light") { it() })

    private val catalog = listOf(
        group("Menu", category = "Overlays") {
            docs("""
                A menu.
            """)
            render { _ -> }
            story("Basic", states = listOf(InteractionState.Hover), play = Play { })
        },
    )

    @Test
    fun `a group keeps its category docs states and script`() {
        val group = catalog.single()

        assertEquals("Overlays", group.category)
        assertEquals("A menu.", group.docs)
        assertEquals(listOf(InteractionState.Hover), group.entries.single().states)
        assertEquals("target", group.entries.single().stateTarget)
        assertNotNull(group.entries.single().play)
    }

    @Test
    fun `every environment gives its own cases and files`() {
        val cases = goldenCases(catalog, environments = environments)

        assertEquals(listOf("Dark/Menu/Basic", "Light/Menu/Basic"), cases.map { it.id })
        assertEquals(listOf("Dark/Menu/Basic.png", "Light/Menu/Basic.png"), cases.map { it.path })
        assertEquals(listOf("Menu/Basic", "Menu/Basic"), cases.map { it.story })
        assertEquals(2, goldenCases(catalog, "menu/basic", environments).size)
        assertEquals(listOf("Light/Menu/Basic"), goldenCases(catalog, "light/menu/basic", environments).map { it.id })
    }

    @Test
    fun `states and snapshots are pictures of their own`() {
        val work = Workspace()
        val shots = CaseShots(redPng, listOf("hover" to bluePng, "open" to redPng))

        assertEquals(0, exitCode(work.session(record = true) { shots }))
        val report = work.session { shots }

        assertEquals(
            listOf("Menu/Basic", "Menu/Basic@hover", "Menu/Basic@open"),
            report.cases.map { it.id },
        )
        assertEquals(3, report.count(CaseStatus.Same))
        assertContentEquals(bluePng, readBytes("${work.goldens}/Menu/Basic@hover.png"))
    }

    @Test
    fun `a failed script fails the run and leaves the goldens alone`() {
        val work = Workspace()
        work.session(record = true) { CaseShots(redPng, listOf("open" to redPng)) }
        val broken = CaseShots(bluePng, playFailure = "after step 1: no menu")

        val check = work.session { broken }
        val record = work.session(record = true) { broken }

        val failure = check.cases.single { it.status == CaseStatus.PlayFailed }
        assertEquals("Menu/Basic@play" to "after step 1: no menu", failure.id to failure.error)
        // The snapshot the script did not reach is missing, not left over.
        assertEquals(0, check.count(CaseStatus.Orphan))
        assertEquals(1, exitCode(check))
        assertEquals(1, exitCode(record))
        assertContentEquals(redPng, readBytes("${work.goldens}/Menu/Basic.png"))
        assertNull(readBytes("${work.goldens}/Menu/Basic@play.png"))
    }

    @Test
    fun `a golden no state or snapshot produces any more is an orphan of its story`() {
        val work = Workspace()
        work.session(record = true, environments = environments) { CaseShots(redPng, listOf("hover" to redPng)) }

        val report = work.session(environments = environments) { CaseShots(redPng) }

        val orphans = report.cases.filter { it.status == CaseStatus.Orphan }
        assertEquals(listOf("Menu/Basic", "Menu/Basic"), orphans.map { it.story })
        assertEquals(setOf("Dark/Menu/Basic@hover", "Light/Menu/Basic@hover"), orphans.map { it.id }.toSet())
    }

    private fun Workspace.session(
        record: Boolean = false,
        environments: List<AtlasEnvironment> = emptyList(),
        capture: (GoldenCase) -> CaseShots,
    ): GoldenReport = runSession(goldenCases(catalog, environments = environments), record, capture)
}
