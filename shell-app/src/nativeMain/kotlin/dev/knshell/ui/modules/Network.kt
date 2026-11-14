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
 * Collapsed (bar): connection icon + combined In/Out throughput.
 * Expanded (click): separate In and Out throughput in the bar.
 * Hover popover: interface, SSID/signal, IP, and In/Out with rates and cumulative totals.
 */
@Composable
fun Network() {
    val network by Services.network.state.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    val down = formatBytesPerSec(network.rxBytesPerSec.toLong())
    val up = formatBytesPerSec(network.txBytesPerSec.toLong())
    val combined = formatBytesPerSec(
        network.rxBytesPerSec.toLong() + network.txBytesPerSec.toLong(),
    )

    HoverPopover(
        delayMs = 250,
        content = { NetworkDetails(network) },
    ) {
        AnimatedWidth(spacing = 10) {
            if (expanded) {
                Label(
                    text = "\u2193 $down",
                    modifier = Modifier
                        .textColor(Grayscale.Foreground)
                        .onClick { expanded = false }
                        .pointerCursor(),
                )
                Label(
                    text = "\u2191 $up",
                    modifier = Modifier
                        .textColor(Grayscale.Accent)
                        .onClick { expanded = false }
                        .pointerCursor(),
                )
            } else {
                Label(
                    text = "\u2195 $combined",
                    modifier = Modifier
                        .textColor(Grayscale.Accent)
                        .onClick { expanded = true }
                        .pointerCursor(),
                )
            }
        }
    }
}

private fun networkIcon(network: NetworkState): String = when (network.connectionType) {
    "wifi" -> "\uF1EB"
    "ethernet" -> "\uF0AC"
    else -> "\uF071"
}

/** Waybar's `tooltip-format` analogue: details live only in the popover. */
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
