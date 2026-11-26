// ---------------------------------------------------------------------------
// ArgsPanel — the right-hand controls column of the Atlas runner. One block per
// arg (dim name, then the control bound to LiveArgs), and a Reset button that
// restores every arg to its default. Width comes from the caller via `modifier`.
// ---------------------------------------------------------------------------

package dev.knshell.atlas.runner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import dev.knshell.atlas.core.arg.Arg
import dev.knshell.atlas.core.arg.LiveArgs
import dev.knshell.compose.Align
import dev.knshell.compose.Button
import dev.knshell.compose.Column
import dev.knshell.compose.DropDown
import dev.knshell.compose.Entry
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.Scroll
import dev.knshell.compose.Slider
import dev.knshell.compose.Switch
import dev.knshell.compose.align
import dev.knshell.compose.background
import dev.knshell.compose.bold
import dev.knshell.compose.fillWidth
import dev.knshell.compose.hExpand
import dev.knshell.compose.minWidth
import dev.knshell.compose.padding
import dev.knshell.compose.rawStyle
import dev.knshell.compose.textColor
import dev.knshell.compose.weight
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ArgsPanel(args: List<Arg<*>>, live: LiveArgs, modifier: Modifier = Modifier) {
    with(AtlasTheme) {
        Column(
            spacing = 10,
            modifier = modifier
                .background(panel, transitionMs = 0)
                .padding(all = 10)
                .rawStyle("", "border-left: 1px solid ${line.toCss()};"),
        ) {
            Label("args", align = 0f, modifier = Modifier.themedText().bold())
            if (args.isEmpty()) {
                Label("no args", align = 0f, modifier = dimLabel)
            } else {
                Scroll(modifier = Modifier.weight(1.0).fillWidth()) {
                    Column(spacing = 12) {
                        args.forEach { arg -> key(arg) { ArgBlock(arg, live) } }
                    }
                }
            }
            Button("Reset", modifier = Modifier.themedButton(), onClick = { live.reset() })
        }
    }
}

private val dimLabel: Modifier
    get() = with(AtlasTheme) { Modifier.themedText().textColor(dim, transitionMs = 0) }

private val valueLabel: Modifier
    get() = with(AtlasTheme) { Modifier.minWidth(36).themedText() }

@Suppress("UNCHECKED_CAST")
@Composable
private fun ArgBlock(arg: Arg<*>, live: LiveArgs) {
    Column(spacing = 4) {
        Label(arg.name, align = 0f, modifier = dimLabel)
        when (arg) {
            is Arg.Text -> TextControl(arg, live)
            is Arg.Flag -> FlagControl(arg, live)
            is Arg.Choice<*> -> ChoiceControl(arg as Arg.Choice<Any?>, live)
            is Arg.IntNum -> IntControl(arg, live)
            is Arg.FloatNum -> FloatControl(arg, live)
        }
    }
}

@Composable
private fun TextControl(arg: Arg.Text, live: LiveArgs) {
    Entry(
        value = live[arg],
        modifier = with(AtlasTheme) { Modifier.hExpand().themedEntry() },
        onValueChange = { live.set(arg, it) },
    )
}

@Composable
private fun FlagControl(arg: Arg.Flag, live: LiveArgs) {
    Switch(
        checked = live[arg],
        modifier = with(AtlasTheme) { Modifier.themedSwitch() }.align(h = Align.Start),
        onCheckedChange = { live.set(arg, it) },
    )
}

@Composable
private fun <E> ChoiceControl(arg: Arg.Choice<E>, live: LiveArgs) {
    val options = arg.options
    val current = live[arg]
    DropDown(
        items = options.map { it.toString() },
        selected = options.indexOf(current),
        modifier = with(AtlasTheme) { Modifier.hExpand().themedDropDown() },
        onSelect = { index -> live.set(arg, options[index]) },
    )
}

@Composable
private fun IntControl(arg: Arg.IntNum, live: LiveArgs) {
    val value = live[arg]
    val range = arg.range
    Row(spacing = 8, modifier = Modifier.fillWidth()) {
        // GTK requires min < max: a single-value range shows only the value.
        if (range.first < range.last) {
            Slider(
                value = value.toFloat(),
                min = range.first.toFloat(),
                max = range.last.toFloat(),
                step = 1f,
                modifier = with(AtlasTheme) { Modifier.hExpand().weight(1.0).themedSlider() },
                onValueChange = { live.set(arg, it.roundToInt()) },
            )
        }
        Label(value.toString(), modifier = valueLabel)
    }
}

@Composable
private fun FloatControl(arg: Arg.FloatNum, live: LiveArgs) {
    val value = live[arg]
    val start = arg.range.start
    val end = arg.range.endInclusive
    Row(spacing = 8, modifier = Modifier.fillWidth()) {
        // GTK requires min < max: a single-value range shows only the value.
        if (start < end) {
            Slider(
                value = value,
                min = start,
                max = end,
                step = (end - start) / 100f,
                modifier = with(AtlasTheme) { Modifier.hExpand().weight(1.0).themedSlider() },
                onValueChange = { live.set(arg, it) },
            )
        }
        Label(formatTwoDecimals(value), modifier = valueLabel)
    }
}

/** Fixed two-decimal text without String.format (unavailable in Kotlin/Native). */
private fun formatTwoDecimals(v: Float): String {
    val hundredths = (abs(v) * 100f).roundToInt()
    val sign = if (v < 0f && hundredths != 0) "-" else ""
    val whole = hundredths / 100
    val fraction = (hundredths % 100).toString().padStart(2, '0')
    return "$sign$whole.$fraction"
}
