package dev.knshell.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.ui.kit.Bar
import dev.knshell.ui.kit.BarState
import dev.knshell.ui.kit.BarTheme
import dev.knshell.ui.kit.ClockPill
import dev.knshell.ui.kit.MetricPill
import dev.knshell.ui.kit.MusicPill
import dev.knshell.ui.kit.PowerPill
import dev.knshell.ui.kit.UpdatesPill
import dev.knshell.ui.kit.VolumePill
import dev.knshell.ui.kit.WorkspaceStrip
import dev.knshell.ui.kit.pill
import dev.knshell.ui.kit.pillText

/**
 * Waybar-like top bar expressed with the declarative kit API:
 *
 *   start : music pill (equalizer) + updates
 *   center: workspace chips
 *   end   : network, volume, CPU, memory, clock, power
 *
 * Styling, animation and service subscriptions live in `dev.knshell.ui.kit`; this file
 * only declares *what* the bar shows and in which order.
 */
@Composable
fun TopBar() {
    val theme = BarTheme.Mocha
    val stats by BarState.stats

    Bar(
        theme = theme,
        start = {
            MusicPill(theme)
            UpdatesPill(theme)
        },
        center = {
            WorkspaceStrip(theme)
        },
        end = {
            NetworkPill(theme)
            VolumePill(theme)
            MetricPill("\uF2DB", stats.cpuPercent, theme.blue, theme)
            MetricPill("\uF538", stats.memPercent, theme.sapphire, theme)
            ClockPill(theme)
            PowerPill(theme)
        },
    )
}

/** Static wired-network glyph (the toggle UI arrives with the control-centre phase). */
@Composable
private fun NetworkPill(theme: BarTheme) {
    Label("\uF0AC", Modifier.pill(theme).pillText(theme, color = theme.blue))
}
