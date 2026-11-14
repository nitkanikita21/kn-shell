//! Network throughput from `/proc/net/dev`.
//!
//! Every second the byte counters of all non-loopback interfaces are summed and the delta
//! is published as bytes per second. This is the "current speed" a bar shows, not a
//! speedtest: it measures whatever the machine is actually transferring.

use std::fs;
use std::path::Path;
use std::process::Command;
use std::sync::OnceLock;
use std::time::{Duration, Instant};

use serde_json::Value;

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

/// Static connection details; queried rarely because they spawn `ip`/`nmcli`.
#[derive(Default, Clone)]
struct Details {
    connection_type: String,
    ssid: String,
    signal: i32,
    ip: String,
}

fn interface_kind(interface: &str) -> String {
    if interface.is_empty() {
        return "disconnected".to_string();
    }
    if Path::new(&format!("/sys/class/net/{interface}/wireless")).exists() {
        "wifi".to_string()
    } else {
        "ethernet".to_string()
    }
}

fn query_ip(interface: &str) -> String {
    let Ok(output) = Command::new("ip")
        .args(["-j", "-4", "addr", "show", "dev", interface])
        .output()
    else {
        return String::new();
    };
    let Ok(value) = serde_json::from_slice::<Value>(&output.stdout) else {
        return String::new();
    };
    value
        .get(0)
        .and_then(|entry| entry.get("addr_info"))
        .and_then(Value::as_array)
        .and_then(|entries| entries.first())
        .and_then(|entry| entry.get("local"))
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string()
}

/// SSID and signal of the currently used wifi network (nmcli, NetworkManager hosts).
fn query_wifi() -> (String, i32) {
    let Ok(output) = Command::new("nmcli")
        .args(["-t", "-f", "IN-USE,SIGNAL,SSID", "dev", "wifi", "list"])
        .output()
    else {
        return (String::new(), 0);
    };
    let text = String::from_utf8_lossy(&output.stdout);
    for line in text.lines() {
        // "*:78:My WiFi:5" — SSID may contain dots/colons, so split only twice.
        let mut parts = line.splitn(3, ':');
        let in_use = parts.next().unwrap_or_default();
        if !in_use.contains('*') {
            continue;
        }
        let signal = parts.next().and_then(|v| v.trim().parse::<i32>().ok()).unwrap_or(0);
        let ssid = parts.next().unwrap_or_default().trim_end_matches(":5").to_string();
        return (ssid, signal.clamp(0, 100));
    }
    (String::new(), 0)
}

fn query_details(interface: &str) -> Details {
    let connection_type = interface_kind(interface);
    let (ssid, signal) = if connection_type == "wifi" {
        query_wifi()
    } else {
        (String::new(), 0)
    };
    Details {
        connection_type,
        ssid,
        signal,
        ip: query_ip(interface),
    }
}

fn poll_loop() {
    let mut interface = default_interface().unwrap_or_default();
    let (mut last_rx, mut last_tx) = read_counters(&interface);
    let mut last_at = Instant::now();
    let mut details = query_details(&interface);
    let mut ticks = 0u32;
    let mut logged = false;

    loop {
        std::thread::sleep(POLL);
        ticks += 1;
        // Re-resolve occasionally: the default route can move (wifi -> ethernet, VPN up/down).
        if let Some(current) = default_interface() {
            if current != interface {
                interface = current;
                details = query_details(&interface);
            }
        }
        // SSID/signal/IP change rarely; spawning nmcli/ip every second would be wasteful.
        if ticks % 5 == 0 {
            details = query_details(&interface);
        }
        let (rx, tx) = read_counters(&interface);
        let now = Instant::now();
        let elapsed = now.duration_since(last_at).as_secs_f64().max(0.001);

        let rx_per_sec = ((rx.saturating_sub(last_rx)) as f64 / elapsed) as u64;
        let tx_per_sec = ((tx.saturating_sub(last_tx)) as f64 / elapsed) as u64;

        let state = NetworkState {
            rx_bytes_per_sec: rx_per_sec,
            tx_bytes_per_sec: tx_per_sec,
            total_rx_bytes: rx,
            total_tx_bytes: tx,
            interface: interface.clone(),
            connection_type: details.connection_type.clone(),
            ssid: details.ssid.clone(),
            signal_strength: details.signal,
            ip_address: details.ip.clone(),
        };
        if !logged {
            logged = true;
            eprintln!(
                "[rust/network] {} ({}) ssid=\"{}\" signal={} ip={}",
                state.interface, state.connection_type, state.ssid, state.signal_strength, state.ip_address
            );
        }
        publish_network(state);

        last_rx = rx;
        last_tx = tx;
        last_at = now;
    }
}
