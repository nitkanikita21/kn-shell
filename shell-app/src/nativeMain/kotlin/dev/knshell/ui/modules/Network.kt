package dev.knshell.ui.modules

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.knshell.compose.AnimatedWidth
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.textColor
import dev.knshell.services.Services
import dev.knshell.ui.Grayscale
import dev.knshell.ui.formatBytesPerSec

/**
 * Live network throughput: download (RX) and upload (TX) rates.
 *
 * This is the throughput the machine is actually using, computed in Rust from
 * `/proc/net/dev` once per second — not a speedtest.
 */
@Composable
fun Network() {
    val network by Services.network.state.collectAsState()

    AnimatedWidth(spacing = 10) {
        Label(
            text = "\u2193 " + formatBytesPerSec(network.rxBytesPerSec.toLong()),
            modifier = Modifier.textColor(Grayscale.Foreground),
        )
        Label(
            text = "\u2191 " + formatBytesPerSec(network.txBytesPerSec.toLong()),
            modifier = Modifier.textColor(Grayscale.Accent),
        )
    }
}
