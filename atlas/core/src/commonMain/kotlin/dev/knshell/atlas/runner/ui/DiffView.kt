// ---------------------------------------------------------------------------
// The differences of one story: golden, new picture and their difference, with Accept.
// ---------------------------------------------------------------------------

package dev.knshell.atlas.runner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.atlas.runner.golden.AcceptScope
import dev.knshell.atlas.runner.golden.CaseResult
import dev.knshell.atlas.runner.golden.CaseStatus
import dev.knshell.atlas.runner.golden.DiffMode
import dev.knshell.atlas.runner.golden.GoldenController
import dev.knshell.atlas.runner.golden.acceptable
import dev.knshell.compose.Align
import dev.knshell.compose.BorderSide
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
import dev.knshell.compose.onClick
import dev.knshell.compose.padding
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.testTag
import dev.knshell.compose.textColor
import dev.knshell.compose.weight
import dev.knshell.ui.primitives.Image

/** [cases] are the cases of [story] that do not match their golden. */
@Composable
internal fun DiffView(golden: GoldenController, story: String, cases: List<CaseResult>, modifier: Modifier) {
    val scope = AcceptScope.Story(story)
    val acceptable = golden.report?.acceptable(scope).orEmpty()
    Column(modifier = modifier.background(AtlasTheme.stage, transitionMs = 0).testTag("diff")) {
        Row(
            spacing = 8,
            modifier = with(AtlasTheme) {
                Modifier
                    .hExpand()
                    .background(panel, transitionMs = 0)
                    .hairline(setOf(BorderSide.Bottom))
                    .padding(horizontal = 12, vertical = 6)
            },
        ) {
            Label(story, align = 0f, modifier = chromeText(AtlasTheme.fg).extraBold().weight())
            DiffMode.entries.forEach { mode ->
                ActionButton(mode.title, "diff:mode:${mode.name.lowercase()}", active = golden.mode == mode) {
                    golden.mode = mode
                }
            }
            if (acceptable.isNotEmpty()) {
                ActionButton("Прийняти все в story", "golden:accept-story", enabled = golden.blocker(scope) == null) {
                    golden.take(scope)
                }
            }
        }
        Scroll(modifier = Modifier.weight().hExpand(), horizontal = true) {
            Column(spacing = 24, modifier = Modifier.align(h = Align.Start, v = Align.Start).padding(all = 16)) {
                if (acceptable.isNotEmpty()) {
                    golden.blocker(scope)?.let { Label(it, align = 0f, modifier = chromeText(AtlasTheme.dim)) }
                }
                cases.forEach { case -> key(case.id) { DiffCase(golden, story, case) } }
            }
        }
    }
}

@Composable
private fun DiffCase(golden: GoldenController, story: String, case: CaseResult) {
    val one = AcceptScope.Case(case.id)
    Column(spacing = 8, modifier = Modifier.align(h = Align.Start).testTag("diff:case:${case.id}")) {
        Row(spacing = 12) {
            // What tells this case from the others of the story: environment, cell, state or snapshot.
            val caption = case.id.replaceFirst(story, "").replace("//", "/").trim('/').replace("/@", " @")
            Label(caption.ifEmpty { story }, modifier = chromeText(AtlasTheme.fg))
            Label(describe(case), modifier = chromeText(case.status.badge().second))
            if (golden.report?.acceptable(one)?.isNotEmpty() == true) {
                ActionButton("Прийняти", "golden:accept:${case.id}", enabled = golden.blocker(one) == null) {
                    golden.take(one)
                }
            }
        }
        val output = golden.report?.output.orEmpty()
        val expected = Shot("Еталон", "$output/${case.expectedFile}", case.expectedWidth, case.expectedHeight)
        val actual = Shot("Зараз", "$output/${case.actualFile}", case.width, case.height)
        val difference = Shot(
            "Різниця",
            "$output/${case.diffFile}",
            maxOf(case.width, case.expectedWidth),
            maxOf(case.height, case.expectedHeight),
        )
        when (case.status) {
            CaseStatus.Changed -> when (golden.mode) {
                DiffMode.Side -> Row(spacing = 16, modifier = Modifier.align(h = Align.Start)) {
                    Picture(golden, expected)
                    Picture(golden, actual)
                    Picture(golden, difference)
                }
                DiffMode.Diff -> Picture(golden, difference)
                DiffMode.Flip -> Flip(golden, case.id, expected, actual)
            }
            CaseStatus.New -> Picture(golden, actual)
            CaseStatus.Error, CaseStatus.PlayFailed -> Label(
                case.error.orEmpty(),
                align = 0f,
                wrap = true,
                modifier = chromeText(AtlasTheme.failed),
            )
            CaseStatus.Orphan -> Label(
                "еталон ${case.path} не відповідає жодній story",
                align = 0f,
                modifier = chromeText(AtlasTheme.dim),
            )
            CaseStatus.Same -> Unit
        }
    }
}

private class Shot(val title: String, val path: String, val width: Int, val height: Int)

@Composable
private fun Picture(golden: GoldenController, shot: Shot, modifier: Modifier = Modifier) {
    Column(spacing = 4, modifier = modifier.align(h = Align.Start, v = Align.Start)) {
        Label(shot.title, align = 0f, modifier = chromeText(AtlasTheme.dim))
        // The frame is around the picture, not on it: a border on the image would scale it down.
        Box(with(AtlasTheme) { Modifier.framed() }.align(h = Align.Start)) {
            // The same path holds other pixels after another run, and an Image reads a path once.
            key(golden.generation, shot.path) {
                Image(shot.path, width = shot.width, height = shot.height)
            }
        }
    }
}

/** One place showing the golden or the new picture; a click swaps them, so changes jump out. */
@Composable
private fun Flip(golden: GoldenController, id: String, expected: Shot, actual: Shot) {
    var showActual by remember { mutableStateOf(false) }
    Picture(
        golden,
        if (showActual) actual else expected,
        Modifier.pointerCursor().onClick { showActual = !showActual }.testTag("diff:flip:$id"),
    )
}

private fun describe(case: CaseResult): String = when (case.status) {
    CaseStatus.Changed -> {
        val resized = case.width != case.expectedWidth || case.height != case.expectedHeight
        val size = "${case.expectedWidth}x${case.expectedHeight} → ${case.width}x${case.height}"
        "пікселів відрізняється: ${case.differingPixels}" + if (resized) ", розмір $size" else ""
    }
    CaseStatus.New -> "еталона ще немає"
    CaseStatus.Error -> "не відрендерилась"
    CaseStatus.PlayFailed -> "сценарій упав"
    CaseStatus.Orphan -> "зайвий еталон"
    CaseStatus.Same -> "без змін"
}

private fun chromeText(color: Color): Modifier = with(AtlasTheme) { Modifier.themedText().textColor(color, transitionMs = 0) }
