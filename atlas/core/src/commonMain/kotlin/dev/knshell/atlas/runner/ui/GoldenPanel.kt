package dev.knshell.atlas.runner.ui

import androidx.compose.runtime.Composable
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
import dev.knshell.compose.Switch
import dev.knshell.compose.BorderSide
import dev.knshell.compose.Color
import dev.knshell.compose.hExpand
import dev.knshell.compose.padding
import dev.knshell.compose.testTag
import dev.knshell.compose.textColor

/** Longest line of a message before it wraps, so that a long one does not widen the sidebar. */
private const val MESSAGE_CHARS = 28

/** The visual check at the top of the sidebar: start it, read its result, accept everything. */
@Composable
internal fun GoldenPanel(golden: GoldenController) {
    val report = golden.report
    Column(
        spacing = 6,
        modifier = with(AtlasTheme) {
            Modifier.hExpand().hairline(setOf(BorderSide.Bottom)).padding(all = 8)
        }.testTag("golden:panel"),
    ) {
        Row(spacing = 8) {
            ActionButton("Перевірити", "golden:check", enabled = golden.available && !golden.running) {
                golden.check()
            }
            if (golden.running) {
                Message(report?.let(::summaryText) ?: "запуск…", AtlasTheme.dim, "golden:progress")
            }
        }
        if (!golden.available) {
            Message("немає налаштувань: збери атлас через Gradle", AtlasTheme.dim, "golden:unavailable")
        }
        if (report != null && !golden.running) {
            Message(summaryText(report), AtlasTheme.fg, "golden:summary")
            report.message?.let { Message(it, AtlasTheme.failed, "golden:message") }
            if (report.binary != golden.binaryStamp) {
                Message("звіт застарів: перевір знову", AtlasTheme.changed, "golden:stale")
            }
            if (report.environmentDiffers) {
                val changes = report.recorded!!.differencesTo(report.environment!!).joinToString("; ")
                Message("інше середовище: $changes", AtlasTheme.changed, "golden:environment")
            }
            if (storyStatuses(report).isNotEmpty()) {
                Row(spacing = 8) {
                    Switch(
                        golden.onlyChanged,
                        modifier = with(AtlasTheme) { Modifier.themedSwitch() }.testTag("golden:only-changed"),
                        onCheckedChange = { golden.onlyChanged = it },
                    )
                    Label("лише змінені", modifier = with(AtlasTheme) { Modifier.themedText() })
                }
            }
            if (report.acceptable(AcceptScope.All).isNotEmpty()) {
                val blocker = golden.blocker(AcceptScope.All)
                ActionButton("Прийняти все", "golden:accept-all", enabled = blocker == null) {
                    golden.take(AcceptScope.All)
                }
                blocker?.let { Message(it, AtlasTheme.dim, "golden:blocker") }
            }
        }
        golden.problem?.let { Message(it, AtlasTheme.failed, "golden:problem") }
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
    CaseStatus.Orphan -> "?" to AtlasTheme.dim
    CaseStatus.Same -> "" to AtlasTheme.dim
}
