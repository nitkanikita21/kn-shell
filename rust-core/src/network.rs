//! Network throughput from `/proc/net/dev`.
//!
//! Every second the byte counters of all non-loopback interfaces are summed and the delta
//! is published as bytes per second. This is the "current speed" a bar shows, not a
//! speedtest: it measures whatever the machine is actually transferring.

use std::fs;
use std::sync::OnceLock;
use std::time::{Duration, Instant};

use crate::{publish_network, NetworkState};

static STARTED: OnceLock<()> = OnceLock::new();

const POLL: Duration = Duration::from_secs(1);

pub fn ensure_started() {
    STARTED.get_or_init(|| {
        std::thread::Builder::new()
            .name("kn-network-listener".into())
            .spawn(poll_loop)
            .expect("failed to spawn network listener thread");
    });
}

/// Interface carrying the default route (the one that actually reaches the internet).
///
/// Summing every interface double counts forwarded traffic: a veth/bridge pair carries the
/// same bytes on both ends, as do tun/wireguard tunnels. The default-route interface is
/// what a bar module wants to show.
fn default_interface() -> Option<String> {
    let content = fs::read_to_string("/proc/net/route").ok()?;
    for line in content.lines().skip(1) {
        let fields: Vec<&str> = line.split_whitespace().collect();
        // iface destination gateway flags refcnt use metric mask ...
        if fields.len() >= 2 && fields[1] == "00000000" {
            return Some(fields[0].to_string());
        }
    }
    None
}

/// RX/TX byte counters of [interface].
///
/// Exact match wins; when the interface disappeared between polls (route changed) the sum
/// of physical-looking devices is used instead, skipping veth/bridge/tun copies.
fn read_counters(interface: &str) -> (u64, u64) {
    fn physical_like(device: &str) -> bool {
        !device.starts_with("veth")
            && !device.starts_with("docker")
            && !device.starts_with("br-")
            && !device.starts_with("virbr")
            && !device.starts_with("tun")
            && !device.starts_with("tap")
            && !device.starts_with("wg")
            && !device.starts_with("tailscale")
    }

    let Ok(content) = fs::read_to_string("/proc/net/dev") else {
        return (0, 0);
    };

    let mut exact: Option<(u64, u64)> = None;
    let mut fallback_rx = 0u64;
    let mut fallback_tx = 0u64;

    for line in content.lines() {
        let Some((name, rest)) = line.split_once(':') else {
            continue;
        };
        let device = name.trim();
        if device.is_empty() || device == "lo" {
            continue;
        }
        let fields: Vec<&str> = rest.split_whitespace().collect();
        if fields.len() < 9 {
            continue;
        }
        let rx = fields[0].parse::<u64>().unwrap_or(0);
        let tx = fields[8].parse::<u64>().unwrap_or(0);
        if device == interface {
            exact = Some((rx, tx));
        } else if physical_like(device) {
            fallback_rx = fallback_rx.saturating_add(rx);
            fallback_tx = fallback_tx.saturating_add(tx);
        }
    }

    exact.unwrap_or((fallback_rx, fallback_tx))
}

fn poll_loop() {
    let mut interface = default_interface().unwrap_or_default();
    let (mut last_rx, mut last_tx) = read_counters(&interface);
    let mut last_at = Instant::now();

    loop {
        std::thread::sleep(POLL);
        // Re-resolve occasionally: the default route can move (wifi -> ethernet, VPN up/down).
        if let Some(current) = default_interface() {
            interface = current;
        }
        let (rx, tx) = read_counters(&interface);
        let now = Instant::now();
        let elapsed = now.duration_since(last_at).as_secs_f64().max(0.001);

        let rx_per_sec = ((rx.saturating_sub(last_rx)) as f64 / elapsed) as u64;
        let tx_per_sec = ((tx.saturating_sub(last_tx)) as f64 / elapsed) as u64;

        publish_network(NetworkState {
            rx_bytes_per_sec: rx_per_sec,
            tx_bytes_per_sec: tx_per_sec,
        });

        last_rx = rx;
        last_tx = tx;
        last_at = now;
    }
}
