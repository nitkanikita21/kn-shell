package dev.knshell.atlas.runner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import dev.knshell.atlas.core.arg.ArgSnapshot
import dev.knshell.atlas.core.arg.ArgValues
import dev.knshell.atlas.core.arg.CellArgs
import dev.knshell.atlas.core.entry.MatrixView
import dev.knshell.atlas.core.entry.Variants
import dev.knshell.atlas.core.entry.groups
import dev.knshell.atlas.core.entry.table
import dev.knshell.atlas.runner.LocalAtlasHostService
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Button
import dev.knshell.compose.Column
import dev.knshell.compose.Flow
import dev.knshell.compose.Grid
import dev.knshell.compose.Icon
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.Scroll
import dev.knshell.compose.align
import dev.knshell.compose.background
import dev.knshell.compose.expand
import dev.knshell.compose.extraBold
import dev.knshell.compose.fontFamily
import dev.knshell.compose.fontSize
import dev.knshell.compose.hExpand
import dev.knshell.compose.onClick
import dev.knshell.compose.padding
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.BorderSide
import dev.knshell.compose.testTag
import dev.knshell.compose.textColor
import dev.knshell.compose.weight
import dev.knshell.icons.lucide.Lucide

/**
 * A matrix of [variants]: a toolbar with the view switcher and the combinations laid out in the
 * chosen [MatrixView]. Every cell renders the story with its combination's axis values (see
 * [CellArgs]) and is tagged `cell:<caption>` in every view.
 */
@Composable
fun VariantsView(variants: Variants, args: ArgValues, modifier: Modifier = Modifier) {
    val service = LocalAtlasHostService.current
    val view = service.viewOf(variants)
    Column(modifier = modifier.expand().background(AtlasTheme.bg, transitionMs = 0)) {
        MatrixToolbar(variants, view)
        key(view) {
            when (view) {
                MatrixView.Compose -> ComposeLayout(variants, args, Modifier.weight().hExpand())
                MatrixView.Table -> TableLayout(variants, args, Modifier.weight().hExpand())
            }
        }
    }
}

/** The icon that stands for a view, in the switcher and on the sidebar badge. */
internal fun MatrixView.icon() = when (this) {
    MatrixView.Compose -> Lucide.LayoutList
    MatrixView.Table -> Lucide.Table
}

@Composable
private fun MatrixToolbar(variants: Variants, current: MatrixView) {
    val service = LocalAtlasHostService.current
    with(AtlasTheme) {
        Row(
            spacing = 8,
            modifier = Modifier
                .hExpand()
                .background(panel, transitionMs = 0)
                .hairline(setOf(BorderSide.Bottom))
                .padding(horizontal = 12, vertical = 6),
        ) {
            Label(variants.name, modifier = text(fg).extraBold())
            Label("${variants.combos.size} previews", align = 0f, modifier = text(dim).weight())
            variants.views.forEach { view ->
                val active = view == current
                Button(
                    modifier = Modifier
                        .themedButton()
                        .background(if (active) accent2 else panel2, radius = RADIUS, transitionMs = 0)
                        .testTag("view:${view.name.lowercase()}"),
                    onClick = { service.setView(variants, view) },
                ) {
                    Row(spacing = 6) {
                        Icon(view.icon(), size = 14, color = if (active) accent else fg, transitionMs = 0)
                        Label(view.title, modifier = text(if (active) accent else fg))
                    }
                }
            }
        }
    }
}

/** Groups by the first axis, each collapsible, with a name above every preview. */
@Composable
private fun ComposeLayout(variants: Variants, args: ArgValues, modifier: Modifier) {
    val service = LocalAtlasHostService.current
    val groups = remember(variants) { variants.groups() }
    with(AtlasTheme) {
        Scroll(modifier = modifier) {
            Column(spacing = 20, modifier = Modifier.hExpand().align(v = Align.Start).padding(all = 16)) {
                groups.forEach { group ->
                    val title = group.title
                    val collapsed = title != null && service.isCollapsed(variants, title)
                    Column(spacing = 12, modifier = Modifier.hExpand()) {
                        if (title != null) {
                            Row(
                                spacing = 8,
                                modifier = Modifier
                                    .hExpand()
                                    .hairline(setOf(BorderSide.Bottom))
                                    .padding(horizontal = 0, vertical = 4)
                                    .pointerCursor()
                                    .onClick { service.toggleGroup(variants, title) }
                                    .testTag("group:$title"),
                            ) {
                                Icon(
                                    if (collapsed) Lucide.ChevronRight else Lucide.ChevronDown,
                                    size = 14,
                                    color = dim,
                                )
                                Label(title, modifier = text(fg).extraBold())
                                Label(group.combos.size.toString(), modifier = text(dim))
                            }
                        }
                        if (!collapsed) {
                            Flow(spacing = 20, modifier = Modifier.align(h = Align.Start)) {
                                group.combos.forEach { combo ->
                                    key(combo) {
                                        Column(spacing = 6, modifier = Modifier.align(Align.Start)) {
                                            Label(captionFor(variants, combo), align = 0f, modifier = text(dim))
                                            Preview(variants, combo, args)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The first axis as rows, the second as columns; a combination removed by `exclude` stays empty. */
@Composable
private fun TableLayout(variants: Variants, args: ArgValues, modifier: Modifier) {
    val table = remember(variants) { variants.table() }
    with(AtlasTheme) {
        Scroll(modifier = modifier, horizontal = true) {
            Grid(
                columns = table.columns.size + 1,
                spacing = 8,
                modifier = Modifier.align(h = Align.Start, v = Align.Start).padding(all = 16),
            ) {
                Box()
                table.columns.forEach { column -> Label(column, modifier = text(dim)) }
                table.rows.forEachIndexed { index, row ->
                    Label(row, align = 1f, modifier = text(dim).align(v = Align.Center))
                    table.cells[index].forEach { combo ->
                        if (combo == null) {
                            Label("—", modifier = text(dim))
                        } else {
                            key(combo) { Preview(variants, combo, args) }
                        }
                    }
                }
            }
        }
    }
}

/** One framed cell: the story rendered with [combo], centred at its natural size. */
@Composable
private fun Preview(variants: Variants, combo: ArgSnapshot, args: ArgValues) {
    val cellArgs = remember(combo, args) { CellArgs(combo, args) }
    with(AtlasTheme) {
        Box(
            Modifier
                .testTag("cell:${captionFor(variants, combo)}")
                .background(panel, radius = RADIUS, transitionMs = 0)
                .framed()
                .padding(all = 16),
        ) {
            // a table cell is as tall as its row: the weight claims that height, so the story can
            // sit in the middle of it
            Decorated(LocalAtlasEnvironment.current, Modifier.weight().align(Align.Center)) {
                variants.render(cellArgs)
            }
        }
    }
}

private fun text(color: dev.knshell.compose.Color): Modifier =
    Modifier.textColor(color, transitionMs = 0).fontSize(AtlasTheme.FONT_SIZE).fontFamily(AtlasTheme.FONT)

/** Caption for one cell: the axis values of [combo] in axis order, joined by " · ". */
internal fun captionFor(variants: Variants, combo: ArgSnapshot): String =
    variants.axisArgs.joinToString(" · ") { arg -> combo[arg].toString() }
