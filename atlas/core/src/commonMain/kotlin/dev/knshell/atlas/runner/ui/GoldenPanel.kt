package dev.knshell.atlas.runner.ui

import androidx.compose.runtime.Composable
import dev.knshell.atlas.runner.golden.GoldenReport
import dev.knshell.atlas.runner.golden.differences
import dev.knshell.compose.Box
import dev.knshell.compose.minWidth
import dev.knshell.compose.weight
import dev.knshell.icons.lucide.Lucide
import dev.knshell.atlas.runner.golden.AcceptScope
import dev.knshell.atlas.runner.golden.CaseStatus
import dev.knshell.atlas.runner.golden.GoldenController
import dev.knshell.atlas.runner.golden.acceptable
import dev.knshell.atlas.runner.golden.storyStatuses
import dev.knshell.atlas.runner.golden.summaryText
import dev.knshell.compose.Column
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.BorderSide
import dev.knshell.compose.Color
import dev.knshell.compose.hExpand
import dev.knshell.compose.padding
import dev.knshell.compose.testTag
import dev.knshell.compose.textColor

/** Longest line of a message before it wraps, so that a long one does not widen the sidebar. */
private const val MESSAGE_CHARS = 28

/**
 * The head of the sidebar: [leading] (the environment switch) beside the button of the visual check,
 * and under them the result of the last run with what can be done about it.
 */
@Composable
internal fun GoldenPanel(golden: GoldenController, leading: @Composable () -> Unit = {}) {
    val report = golden.report
    Column(
        spacing = 6,
        modifier = with(AtlasTheme) {
            Modifier.hExpand().hairline(setOf(BorderSide.Bottom)).padding(all = 8)
        }.testTag("golden:panel"),
    ) {
        Row(spacing = 8, modifier = Modifier.hExpand()) {
            leading()
            Box(Modifier.weight())
            ToolButton(
                // While it runs, the button itself tells how far it is.
                text = if (golden.running) golden.progress ?: "запуск…" else "Все",
                tag = "golden:check",
                icon = if (golden.running) Lucide.LoaderCircle else Lucide.Play,
                enabled = golden.available && !golden.running,
            ) { golden.check() }
        }
        if (!golden.available) {
            Message("немає налаштувань: збери атлас через Gradle", AtlasTheme.dim, "golden:unavailable")
        }
        if (report != null && !golden.running) {
            Summary(report)
            report.message?.let { Message(it, AtlasTheme.failed, "golden:message") }
            val stale = report.binary != golden.binaryStamp
            if (stale) {
                Message("звіт застарів: перевір знову", AtlasTheme.changed, "golden:stale")
            }
            if (report.environmentDiffers) {
                val changes = report.recorded!!.differencesTo(report.environment!!).joinToString("; ")
                Message("інше середовище: $changes", AtlasTheme.changed, "golden:environment")
            }
            val filterable = storyStatuses(report).isNotEmpty()
            val acceptable = report.acceptable(AcceptScope.All).isNotEmpty()
            if (filterable || acceptable) {
                val blocker = golden.blocker(AcceptScope.All)
                Row(spacing = 4, modifier = Modifier.hExpand()) {
                    if (filterable) {
                        ToolButton(
                            "лише змінені",
                            "golden:only-changed",
                            icon = Lucide.ListFilter,
                            active = golden.onlyChanged,
                        ) { golden.onlyChanged = !golden.onlyChanged }
                    }
                    Box(Modifier.weight())
                    if (acceptable) {
                        ToolButton(
                            "Прийняти все",
                            "golden:accept-all",
                            icon = Lucide.CheckCheck,
                            enabled = blocker == null,
                            color = AtlasTheme.accent,
                        ) { golden.take(AcceptScope.All) }
                    }
                }
                // A stale report already says why nothing can be accepted.
                if (acceptable && !stale) blocker?.let { Message(it, AtlasTheme.dim, "golden:blocker") }
            }
        }
        golden.problem?.let { Message(it, AtlasTheme.failed, "golden:problem") }
    }
}

/** One line per kind of difference, marked as the stories in the tree are. */
@Composable
private fun Summary(report: GoldenReport) {
    Column(spacing = 2, modifier = Modifier.testTag("golden:summary")) {
        val found = differences(report)
        if (found.isEmpty()) {
            Message("✓ ${summaryText(report)}", AtlasTheme.accent, "golden:clean")
        }
        found.forEach { (status, text) ->
            val (mark, color) = status.badge()
            Row(spacing = 6) {
                Label(mark, modifier = with(AtlasTheme) { Modifier.minWidth(10).themedText().textColor(color, transitionMs = 0) })
                Label(text, align = 0f, modifier = with(AtlasTheme) { Modifier.themedText() })
            }
        }
    }
}

@Composable
private fun Message(text: String, color: Color, tag: String) {
    Label(
        text,
        modifier = with(AtlasTheme) { Modifier.themedText().textColor(color, transitionMs = 0) }.testTag(tag),
        wrap = true,
        align = 0f,
        maxWidthChars = MESSAGE_CHARS,
    )
}

/** The mark of a story in the tree. */
internal fun CaseStatus.badge(): Pair<String, Color> = when (this) {
    CaseStatus.Changed -> "●" to AtlasTheme.changed
    CaseStatus.New -> "+" to AtlasTheme.accent
    CaseStatus.Error -> "✕" to AtlasTheme.failed
    CaseStatus.PlayFailed -> "▷" to AtlasTheme.failed
    CaseStatus.Orphan -> "?" to AtlasTheme.dim
    CaseStatus.Same -> "" to AtlasTheme.dim
}
