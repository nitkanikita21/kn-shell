package dev.knshell.atlas.runner.golden

import dev.knshell.shell.CompositionFailedException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoldenSessionTest {

    @Test
    fun `a check without goldens finds only new cases and writes none`() {
        val work = Workspace()

        val report = work.run()

        assertEquals(work.cases.size, report.count(CaseStatus.New))
        assertNull(work.golden(work.cases.first()))
        assertEquals(1, exitCode(report))
    }

    @Test
    fun `recorded goldens then pass a check`() {
        val work = Workspace()
        assertEquals(0, exitCode(work.run(record = true)))

        val report = work.run()

        assertEquals(work.cases.size, report.count(CaseStatus.Same))
        assertEquals(0, exitCode(report))
        assertEquals(sampleEnvironment.toText(), readText("${work.goldens}/environment.txt"))
    }

    @Test
    fun `a changed case leaves three pictures and keeps its golden`() {
        val work = Workspace()
        work.run(record = true)
        val changed = work.cases.first()

        val report = work.run { if (it.id == changed.id) bluePng else redPng }

        val result = report.cases.single { it.status != CaseStatus.Same }
        assertEquals(CaseStatus.Changed, result.status)
        assertEquals(4, result.differingPixels)
        assertContentEquals(redPng, readBytes("${work.output}/${result.expectedFile}"))
        assertContentEquals(bluePng, readBytes("${work.output}/${result.actualFile}"))
        assertNotNull(readBytes("${work.output}/${result.diffFile}"))
        assertContentEquals(redPng, work.golden(changed))
        assertEquals(1, exitCode(report))
    }

    @Test
    fun `the pictures of an earlier run do not outlive it`() {
        val work = Workspace()
        work.run(record = true)
        val first = work.run { bluePng }.cases.first()

        work.run()

        assertNull(readBytes("${work.output}/${first.diffFile}"))
    }

    @Test
    fun `a record with a failing case writes nothing`() {
        val work = Workspace()

        val report = work.run(record = true) { if (it.entry.name == "Sizes") error("boom") else redPng }

        assertEquals(3, report.count(CaseStatus.Error))
        assertNull(work.golden(work.cases.first()))
        assertEquals(1, exitCode(report))
    }

    @Test
    fun `a failed composition ends the run`() {
        val work = Workspace()

        val report = work.run { throw CompositionFailedException(IllegalStateException("boom")) }

        assertEquals(1, report.cases.size)
        assertTrue(report.finished)
        assertTrue("were not run" in report.message.orEmpty())
        assertEquals(1, exitCode(report))
    }

    @Test
    fun `a golden no story produces fails the check but stays`() {
        val work = Workspace()
        work.run(record = true)
        writeBytes("${work.goldens}/Button/Gone.png", redPng)

        val report = work.run()

        assertEquals(listOf("Button/Gone.png"), report.cases.filter { it.status == CaseStatus.Orphan }.map { it.path })
        assertNotNull(readBytes("${work.goldens}/Button/Gone.png"))
        assertEquals(1, exitCode(report))
    }

    @Test
    fun `a different environment is said first and blocks a partial record`() {
        val work = Workspace()
        work.run(record = true)
        work.log.clear()
        val newer = RenderEnvironment(listOf("gtk" to "4.26.0", "renderer" to "GskCairoRenderer"))

        val report = work.run(record = true, filter = "Button/Primary", environment = newer) { bluePng }

        assertTrue("gtk: 4.24.1 → 4.26.0" in work.log.first(), work.log.first())
        assertTrue(report.environmentDiffers)
        assertContentEquals(redPng, work.golden(work.cases.first()))
        assertEquals(1, exitCode(report))
        assertEquals(0, exitCode(work.run(record = true, environment = newer) { bluePng }))
    }

    @Test
    fun `the written report reads back`() {
        val work = Workspace()
        work.run(record = true)
        val report = work.run { if (it.entry.name == "Sizes") bluePng else redPng }

        val read = GoldenReport.parse(assertNotNull(readText("${work.output}/report.json")))

        assertEquals(report, read)
        assertNull(GoldenReport.parse("{}"))
    }
}
