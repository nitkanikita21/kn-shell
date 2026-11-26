package dev.knshell.atlas.runner.golden

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GoldenAcceptTest {

    /** Goldens in red, then a check in which every `Sizes` cell turned blue. */
    private fun changedSizes(environment: RenderEnvironment = sampleEnvironment): Pair<Workspace, GoldenReport> {
        val work = Workspace()
        work.run(record = true)
        return work to work.run(environment = environment) { if (it.entry.name == "Sizes") bluePng else redPng }
    }

    @Test
    fun `accepting one case replaces only its golden`() {
        val (work, report) = changedSizes()
        val (first, second) = work.cases.filter { it.entry.name == "Sizes" }

        val updated = accept(report, AcceptScope.Case(first.id), "build-1")

        assertContentEquals(bluePng, work.golden(first))
        assertContentEquals(redPng, work.golden(second))
        assertEquals(2, updated.count(CaseStatus.Changed))
        assertEquals(updated, GoldenReport.parse(assertNotNull(readText("${work.output}/report.json"))))
    }

    @Test
    fun `accepting a story takes all its cells and nothing is left to accept`() {
        val (work, report) = changedSizes()

        val updated = accept(report, AcceptScope.Story("Button/Sizes"), "build-1")

        assertEquals(0, updated.count(CaseStatus.Changed))
        assertEquals("немає чого приймати", updated.acceptBlocker(AcceptScope.All, "build-1"))
        assertEquals(0, exitCode(work.run { if (it.entry.name == "Sizes") bluePng else redPng }))
    }

    @Test
    fun `a report of another build cannot be accepted`() {
        val (work, report) = changedSizes()

        assertFailsWith<IllegalStateException> { accept(report, AcceptScope.All, "build-2") }
        assertContentEquals(redPng, work.golden(work.cases.first { it.entry.name == "Sizes" }))
    }

    @Test
    fun `in another environment only everything can be accepted`() {
        val newer = RenderEnvironment(listOf("gtk" to "4.26.0", "renderer" to "GskCairoRenderer"))
        val (work, report) = changedSizes(newer)

        assertNotNull(report.acceptBlocker(AcceptScope.Story("Button/Sizes"), "build-1"))
        assertNull(report.acceptBlocker(AcceptScope.All, "build-1"))
        val updated = accept(report, AcceptScope.All, "build-1")

        assertEquals(newer.toText(), readText("${work.goldens}/environment.txt"))
        assertEquals(newer, updated.recorded)
    }
}
