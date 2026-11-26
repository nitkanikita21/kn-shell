package dev.knshell.atlas.runner.golden

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoldenViewModelTest {

    private fun report(vararg cases: Pair<String, CaseStatus>, finished: Boolean = true) = GoldenReport(
        goldens = "g",
        output = "o",
        record = false,
        binary = "b",
        environment = null,
        recorded = null,
        total = cases.size,
        finished = finished,
        cases = cases.map { (id, status) -> CaseResult(id, id.split('/').take(2).joinToString("/"), "$id.png", status) },
    )

    @Test
    fun `a story shows its worst status and a matching one none`() {
        val report = report(
            "G/Matrix/a" to CaseStatus.New,
            "G/Matrix/b" to CaseStatus.Error,
            "G/Matrix/c" to CaseStatus.Changed,
            "G/Plain" to CaseStatus.Same,
        )

        assertEquals(mapOf("G/Matrix" to CaseStatus.Error), storyStatuses(report))
    }

    @Test
    fun `the summary names what differs`() {
        assertEquals("без змін: 1", summaryText(report("G/A" to CaseStatus.Same)))
        assertEquals(
            "змінено: 1 · нових: 1",
            summaryText(report("G/A" to CaseStatus.Changed, "G/B" to CaseStatus.New)),
        )
        assertEquals("1/1", summaryText(report("G/A" to CaseStatus.Same, finished = false)))
    }

    @Test
    fun `the configuration gives the command and the environment`() {
        val config = GoldenConfig.parse(
            "compositor=/c\ngoldens=/g\noutput=/o\nenv.GSK_RENDERER=cairo\nenv.DBUS_SESSION_BUS_ADDRESS=disabled:\n",
        )!!

        assertEquals(listOf("/c", "--", "/atlas", "--golden=/g", "--golden-out=/o"), config.command("/atlas"))
        assertEquals(
            mapOf("GSK_RENDERER" to "cairo", "DBUS_SESSION_BUS_ADDRESS" to "disabled:"),
            config.environment,
        )
        assertNull(GoldenConfig.parse("goldens=/g\n"))
    }
}
