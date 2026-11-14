package dev.knshell.ui.modules

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.compose.AnimatedWidth
import dev.knshell.compose.Box
import dev.knshell.compose.Column
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.background
import dev.knshell.compose.onClick
import dev.knshell.compose.padding
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.radius
import dev.knshell.compose.textColor
import dev.knshell.core.NetworkState
import dev.knshell.services.Services
import dev.knshell.shell.HoverPopover
import dev.knshell.ui.Grayscale
import dev.knshell.ui.formatBytes
import dev.knshell.ui.formatBytesPerSec
import dev.knshell.ui.primitives.Icon

/**
 * Network module, waybar-style.
 *
 * Collapsed (in the bar): connection icon + combined In/Out throughput.
 * Expanded (hover popover): interface, SSID/signal, IP and separate In / Out rates + totals.
 * Clicking the speed toggles throughput ⇄ cumulative I/O.
 */
@Composable
fun Network() {
    val network by Services.network.state.collectAsState()
    var showTotals by remember { mutableStateOf(false) }

    val combined = if (showTotals) {
        formatBytes(network.totalRxBytes.toLong() + network.totalTxBytes.toLong())
    } else {
        formatBytesPerSec(network.rxBytesPerSec.toLong() + network.txBytesPerSec.toLong())
    }

    HoverPopover(
        delayMs = 250,
        content = { NetworkDetails(network) },
    ) {
        AnimatedWidth(spacing = 10) {
            Icon(networkIcon(network), size = 15)
            Label(
                text = "\u2195 $combined",
                modifier = Modifier
                    .textColor(Grayscale.Accent)
                    .onClick { showTotals = !showTotals }
                    .pointerCursor(),
            )
        }
    }
}

private fun networkIcon(network: NetworkState): String = when (network.connectionType) {
    "wifi" -> "\uF1EB"
    "ethernet" -> "\uF0AC"
    else -> "\uF071"
}

/** Waybar's `tooltip-format` analogue: details kept out of the bar itself. */
@Composable
private fun NetworkDetails(network: NetworkState) {
    Box(
        modifier = Modifier
            .padding(horizontal = 14, vertical = 10)
            .background(Grayscale.Surface, radius = 10),
    ) {
        Column(spacing = 4) {
            Label(
                text = "${network.`interface`} (${network.connectionType})",
                modifier = Modifier.textColor(Grayscale.Foreground),
            )
            if (network.connectionType == "wifi") {
                Label(
                    text = "ssid: ${network.ssid.ifBlank { "—" }}  signal: ${network.signalStrength}%",
                    modifier = Modifier.textColor(Grayscale.Accent),
                )
            }
            if (network.ipAddress.isNotBlank()) {
                Label(
                    text = "ip: ${network.ipAddress}",
                    modifier = Modifier.textColor(Grayscale.Accent),
                )
            }
            Label(
                text = "In:  " + formatBytesPerSec(network.rxBytesPerSec.toLong()) +
                    "  (" + formatBytes(network.totalRxBytes.toLong()) + ")",
                modifier = Modifier.textColor(Grayscale.Foreground),
            )
            Label(
                text = "Out: " + formatBytesPerSec(network.txBytesPerSec.toLong()) +
                    "  (" + formatBytes(network.totalTxBytes.toLong()) + ")",
                modifier = Modifier.textColor(Grayscale.Foreground),
            )
        }
    }
}
