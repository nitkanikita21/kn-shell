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
 * Network module, waybar-style: an icon for the connection type, the SSID/interface, and
 * the current throughput. Click toggles throughput ⇄ cumulative I/O; hovering shows the
 * details (interface, signal, IP, totals) in a popover.
 */
@Composable
fun Network() {
    val network by Services.network.state.collectAsState()
    var showTotals by remember { mutableStateOf(false) }

    val down = if (showTotals) {
        formatBytes(network.totalRxBytes.toLong())
    } else {
        formatBytesPerSec(network.rxBytesPerSec.toLong())
    }
    val up = if (showTotals) {
        formatBytes(network.totalTxBytes.toLong())
    } else {
        formatBytesPerSec(network.txBytesPerSec.toLong())
    }

    HoverPopover(
        delayMs = 250,
        content = { NetworkDetails(network) },
    ) {
        AnimatedWidth(spacing = 10) {
            Icon(networkIcon(network), size = 15)
            Label(
                text = connectionLabel(network),
                modifier = Modifier.textColor(Grayscale.Foreground),
            )
            Label(
                text = "\u2193 $down",
                modifier = Modifier
                    .textColor(Grayscale.Accent)
                    .onClick { showTotals = !showTotals }
                    .pointerCursor(),
            )
            Label(
                text = "\u2191 $up",
                modifier = Modifier.textColor(Grayscale.Accent),
            )
        }
    }
}

private fun networkIcon(network: NetworkState): String = when (network.connectionType) {
    "wifi" -> "\uF1EB"
    "ethernet" -> "\uF0AC"
    else -> "\uF071"
}

private fun connectionLabel(network: NetworkState): String = when (network.connectionType) {
    "wifi" -> network.ssid.ifBlank { network.`interface` }.ifBlank { "wifi" }
    "ethernet" -> network.`interface`.ifBlank { "ethernet" }
    else -> "offline"
}

/** Waybar's `tooltip-format` analogue: interface, signal, IP and cumulative traffic. */
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
                text = "down: ${formatBytes(network.totalRxBytes.toLong())}  " +
                    "up: ${formatBytes(network.totalTxBytes.toLong())}",
                modifier = Modifier.textColor(Grayscale.Foreground),
            )
        }
    }
}
