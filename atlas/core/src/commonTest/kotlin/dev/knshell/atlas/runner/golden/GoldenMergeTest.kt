package dev.knshell.atlas.runner.golden

import dev.knshell.atlas.play.PlayStep
import dev.knshell.atlas.runner.ui.ago
import dev.knshell.atlas.runner.ui.scriptCases
import dev.knshell.atlas.sampleGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun case(id: String, status: CaseStatus = CaseStatus.Same, steps: List<PlayStep> = emptyList()) = CaseResult(
    id = id,
    story = id.substringBefore('@').split('/').takeLast(2).joinToString("/"),
    path = "$id.png",
    status = status,
    steps = steps,
)

private fun report(vararg cases: CaseResult, binary: String = "b1", output: String = "o") = GoldenReport(
    goldens = "g",
    output = output,
    record = false,
    binary = binary,
    environment = null,
    recorded = null,
    total = cases.size,
    finished = true,
    cases = cases.toList(),
)

class GoldenMergeTest {

    private val previous = report(
        case("G/A", CaseStatus.Changed),
        case("G/B", CaseStatus.Changed),
        case("G/Gone", CaseStatus.Orphan),
    )

    @Test
    fun `a check of one story replaces its results and keeps the rest`() {
        val merged = mergeReports(previous, report(case("G/A"), output = "o/partial"), "g", "o")

        assertEquals(
            mapOf("G/A" to CaseStatus.Same, "G/B" to CaseStatus.Changed, "G/Gone" to CaseStatus.Orphan),
            merged.cases.associate { it.id to it.status },
        )
        assertEquals("o", merged.output)
        assertEquals(3, merged.total)
        assertEquals(listOf("G/B"), merged.acceptable(AcceptScope.All).map { it.id })
    }

    @Test
    fun `results of an earlier build are shown but cannot be accepted`() {
        val merged = mergeReports(previous, report(case("G/A", CaseStatus.Changed), binary = "b2"), "g", "o")

        assertEquals("b2", merged.binary)
        assertEquals(listOf("G/B", "G/Gone"), merged.cases.filter { it.stale }.map { it.id })
        assertEquals(listOf("G/A"), merged.acceptable(AcceptScope.All).map { it.id })
    }

    @Test
    fun `without an earlier report of these goldens the part is the report`() {
        val part = report(case("G/A"), output = "o/partial")

        assertEquals(part.copy(output = "o"), mergeReports(null, part, "g", "o"))
        assertEquals(part.copy(output = "o"), mergeReports(previous.copy(goldens = "other"), part, "g", "o"))
    }

    @Test
    fun `steps and freshness survive the report file`() {
        val steps = listOf(PlayStep("press Enter"), PlayStep("snapshot «open»", snapshot = "open"), PlayStep("expect a", "no"))
        val written = report(case("G/A", CaseStatus.PlayFailed, steps).copy(checkedAt = 1_790_000_000, stale = true))

        assertEquals(written, GoldenReport.parse(written.toJson()))
    }

    @Test
    fun `a group names all its cases`() {
        val groups = listOf(sampleGroup())

        assertEquals(goldenCases(groups).size, goldenCases(groups, "button").size)
        assertFailsWith<IllegalArgumentException> { goldenCases(groups, "butto") }
    }

    @Test
    fun `the script tab takes the cases of the story in the shown environment`() {
        val steps = listOf(PlayStep("focus a"))
        val found = report(
            case("Dark/G/A", steps = steps),
            case("Dark/G/A@open"),
            case("Light/G/A", steps = steps),
            case("Dark/G/B", steps = steps),
        )

        assertEquals(listOf("Dark/G/A"), scriptCases(found, "G/A", "Dark").map { it.id })
        assertEquals(emptyList(), scriptCases(null, "G/A", null))
        assertEquals("щойно", ago(5))
        assertEquals("2 хв тому", ago(150))
        assertEquals("3 год тому", ago(3 * 3600 + 5))
    }
}
