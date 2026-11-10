//! CPU / memory usage from `/proc` — used by the system monitor modules of the bar.

use std::sync::OnceLock;
use std::time::Duration;

use crate::{publish_system_stats, SystemStats};

static STARTED: OnceLock<()> = OnceLock::new();

struct CpuTimes {
    total: u64,
    idle: u64,
}

fn read_cpu_times() -> Option<CpuTimes> {
    let stat = std::fs::read_to_string("/proc/stat").ok()?;
    let line = stat.lines().next()?;
    let mut fields = line.split_whitespace();
    if fields.next()? != "cpu" {
        return None;
    }
    let values: Vec<u64> = fields.filter_map(|value| value.parse().ok()).collect();
    if values.len() < 4 {
        return None;
    }
    let idle = values[3] + values.get(4).copied().unwrap_or(0);
    let total: u64 = values.iter().sum();
    Some(CpuTimes { total, idle })
}

fn cpu_percent(previous: &CpuTimes, current: &CpuTimes) -> f32 {
    let total_delta = current.total.saturating_sub(previous.total) as f32;
    let idle_delta = current.idle.saturating_sub(previous.idle) as f32;
    if total_delta <= 0.0 {
        return 0.0;
    }
    ((total_delta - idle_delta) / total_delta * 100.0).clamp(0.0, 100.0)
}

fn mem_percent() -> Option<f32> {
    let meminfo = std::fs::read_to_string("/proc/meminfo").ok()?;
    let mut total_kb: Option<f64> = None;
    let mut available_kb: Option<f64> = None;
    for line in meminfo.lines() {
        if let Some(rest) = line.strip_prefix("MemTotal:") {
            total_kb = rest.trim().split_whitespace().next()?.parse().ok();
        } else if let Some(rest) = line.strip_prefix("MemAvailable:") {
            available_kb = rest.trim().split_whitespace().next()?.parse().ok();
        }
    }
    let total = total_kb?;
    let available = available_kb?;
    if total <= 0.0 {
        return None;
    }
    Some((((total - available) / total) * 100.0).clamp(0.0, 100.0) as f32)
}

pub fn ensure_started() {
    STARTED.get_or_init(|| {
        std::thread::Builder::new()
            .name("kn-system-listener".into())
            .spawn(poll_loop)
            .expect("failed to spawn system listener thread");
    });
}

fn poll_loop() {
    let mut previous = read_cpu_times();
    let mut last: Option<SystemStats> = None;
    loop {
        let current = read_cpu_times();
        let cpu = match (&previous, &current) {
            (Some(prev), Some(cur)) => cpu_percent(prev, cur),
            _ => 0.0,
        };
        previous = current;

        let mem = mem_percent().unwrap_or(0.0);
        let stats = SystemStats {
            cpu_percent: cpu.round() as i32,
            mem_percent: mem.round() as i32,
        };
        if last.as_ref() != Some(&stats) {
            publish_system_stats(stats.clone());
            last = Some(stats);
        }
        std::thread::sleep(Duration::from_millis(1000));
    }
}
