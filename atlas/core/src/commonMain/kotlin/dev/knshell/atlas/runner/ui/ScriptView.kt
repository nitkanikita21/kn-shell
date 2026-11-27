// ---------------------------------------------------------------------------
// The `play` script of a story as the latest check ran it: its steps, where it failed and the
// pictures it took. Keys can only be pressed on the private display of a check, so the window shows
// a run and does not perform one itself.
// ---------------------------------------------------------------------------

package dev.knshell.atlas.runner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import dev.knshell.atlas.core.entry.StoryEntry
import dev.knshell.atlas.play.PlayStep
import dev.knshell.atlas.runner.AtlasEnvironment
import dev.knshell.atlas.runner.golden.CaseResult
import dev.knshell.atlas.runner.golden.CaseStatus
import dev.knshell.atlas.runner.golden.GoldenController
import dev.knshell.atlas.runner.golden.GoldenReport
import dev.knshell.atlas.runner.golden.epochSeconds
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Color
import dev.knshell.compose.Column
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.Scroll
import dev.knshell.compose.align
import dev.knshell.compose.background
import dev.knshell.compose.extraBold
import dev.knshell.compose.hExpand
import dev.knshell.compose.margins
import dev.knshell.compose.minWidth
import dev.knshell.compose.padding
import dev.knshell.compose.testTag
import dev.knshell.compose.textColor
import dev.knshell.compose.weight
import dev.knshell.icons.lucide.Lucide

/** Longest line of a failure before it wraps. */
private const val ERROR_CHARS = 80

/** The room left of what belongs to a step: the mark and the number. */
private const val STEP_INDENT = 54

/**
 * The cases of [story] in [report] that ran its script, in [environment]: one for a story, one per
 * combination for a matrix.
 */
internal fun scriptCases(report: GoldenReport?, story: String, environment: String?): List<CaseResult> =
    report?.cases.orEmpty().filter { case ->
        case.story == story && '@' !in case.id && (case.steps.isNotEmpty() || case.status == CaseStatus.Error) &&
            (environment == null || case.id.startsWith("$environment/"))
    }

/** How long ago [seconds] before now was, in words. */
internal fun ago(seconds: Long): String = when {
    seconds < 60 -> "щойно"
    seconds < 3600 -> "${seconds / 60} хв тому"
    seconds < 86400 -> "${seconds / 3600} год тому"
    else -> "${seconds / 86400} дн тому"
}

@Composable
internal fun ScriptView(
    golden: GoldenController,
    story: String,
    entry: StoryEntry,
    environments: List<AtlasEnvironment>,
    environment: AtlasEnvironment?,
    onEnvironment: (AtlasEnvironment) -> Unit,
    modifier: Modifier,
) {
    val cases = scriptCases(golden.report, story, environment?.name)
    Scroll(modifier = modifier.background(AtlasTheme.stage, transitionMs = 0).testTag("script")) {
        Column(spacing = 10, modifier = Modifier.hExpand().align(v = Align.Start).padding(horizontal = 16, vertical = 12)) {
            when {
                entry.play == null -> Note("У цієї story немає сценарію play.", "script:none")
                cases.isEmpty() -> {
                    Note(
                        "Сценарій ще не проганявся. Він виконується у прихованому вікні, " +
                            "бо натискати клавіші можна лише там.",
                        "script:never",
                    )
                    RunButton(golden, story, "Прогнати цю story")
                    golden.problem?.let { Note(it, "script:problem") }
                }
                else -> {
                    Row(spacing = 10, modifier = Modifier.hExpand()) {
                        if (environments.size > 1) {
                            Segmented(
                                options = environments,
                                selected = environment,
                                title = { it.name },
                                tag = { "script:environment:${it.name}" },
                                onSelect = onEnvironment,
                            )
                        }
                        val newest = cases.maxOf { it.checkedAt }
                        val when_ = if (newest == 0L) "" else "з прогону ${ago(epochSeconds() - newest)}"
                        val old = if (cases.any { it.stale }) " (попередня збірка)" else ""
                        Label(when_ + old, modifier = text(AtlasTheme.dim).align(v = Align.Center).testTag("script:when"))
                        Box(Modifier.weight())
                        RunButton(golden, story, "Прогнати ще раз")
                    }
                    cases.forEach { case -> key(case.id) { CaseSteps(golden, case, several = cases.size > 1) } }
                }
            }
        }
    }
}

@Composable
private fun RunButton(golden: GoldenController, story: String, title: String) {
    Row(modifier = Modifier.align(h = Align.Start)) {
        ToolButton(
            if (golden.running) golden.progress ?: "запуск…" else title,
            "script:run",
            icon = if (golden.running) Lucide.LoaderCircle else Lucide.Play,
            enabled = golden.available && !golden.running,
        ) { golden.check(story) }
    }
}

@Composable
private fun CaseSteps(golden: GoldenController, case: CaseResult, several: Boolean) {
    Column(spacing = 3, modifier = Modifier.hExpand()) {
        if (several) Label(case.id, align = 0f, modifier = text(AtlasTheme.fg).extraBold())
        case.error?.takeIf { case.status == CaseStatus.Error }?.let { Failure(it) }
        case.steps.forEachIndexed { index, step ->
            key(index) { Step(golden, case, index + 1, step) }
        }
    }
}

@Composable
private fun Step(golden: GoldenController, case: CaseResult, number: Int, step: PlayStep) {
    val failed = step.error != null
    Column(spacing = 4, modifier = Modifier.hExpand().testTag("script:step:$number")) {
        Row(spacing = 8) {
            Label(
                if (failed) "✕" else "✓",
                modifier = text(if (failed) AtlasTheme.failed else AtlasTheme.accent).minWidth(14),
            )
            Label(number.toString(), align = 1f, modifier = text(AtlasTheme.dim).minWidth(24))
            Label(step.text, align = 0f, modifier = text(AtlasTheme.fg))
        }
        step.error?.let { Failure(it) }
        step.snapshot?.let { name ->
            val report = golden.report ?: return@let
            val shot = report.cases.firstOrNull { it.id == "${case.id}@$name" } ?: return@let
            // A picture that matched was not written out: the golden is the same.
            val path = if (shot.status == CaseStatus.Same) {
                "${report.goldens}/${shot.path}"
            } else {
                "${report.output}/${shot.actualFile}"
            }
            Row(modifier = Modifier.margins(start = STEP_INDENT, bottom = 6)) {
                Picture(golden, Shot("", path, shot.width, shot.height))
            }
        }
    }
}

@Composable
private fun Failure(message: String) {
    Label(
        message,
        align = 0f,
        wrap = true,
        maxWidthChars = ERROR_CHARS,
        modifier = text(AtlasTheme.failed)
            .background(AtlasTheme.panel2, radius = AtlasTheme.RADIUS, transitionMs = 0)
            .margins(start = STEP_INDENT)
            .padding(horizontal = 8, vertical = 5)
            .align(h = Align.Start)
            .testTag("script:error"),
    )
}

@Composable
private fun Note(message: String, tag: String) {
    Label(
        message,
        align = 0f,
        wrap = true,
        maxWidthChars = ERROR_CHARS,
        modifier = text(AtlasTheme.dim).align(h = Align.Start).testTag(tag),
    )
}

private fun text(color: Color): Modifier =
    with(AtlasTheme) { Modifier.themedText().textColor(color, transitionMs = 0) }
