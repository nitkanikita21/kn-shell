//! Running Gradle and telling whether an atlas binary is older than its sources.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::SystemTime;

use tokio::io::{AsyncBufReadExt, AsyncRead, BufReader};
use tokio::process::Command as TokioCommand;

use crate::config::Command;
use crate::discover::{is_build_file, AtlasTarget};

/// How many trailing output lines are kept when a build fails without `e:` lines.
const TAIL_LINES: usize = 40;

/// The outcome of one Gradle run.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BuildOutcome {
    pub success: bool,
    /// Lines of the form `e: ...` (Kotlin compiler errors), in output order.
    pub errors: Vec<String>,
    /// All output: stdout, then stderr.
    pub lines: Vec<String>,
    /// The last lines of output, for failures that have no `e:` lines.
    pub tail: Vec<String>,
}

/// Runs `command` in `root`. Output is piped and collected, never inherited, because the daemon's
/// stdout is the MCP channel.
pub async fn run_build(root: &Path, command: &Command) -> io::Result<BuildOutcome> {
    let mut child = TokioCommand::new(&command.program)
        .args(&command.args)
        .current_dir(root)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true)
        .spawn()?;
    let stdout = child.stdout.take().expect("stdout is piped");
    let stderr = child.stderr.take().expect("stderr is piped");
    let (status, out, err) = tokio::join!(child.wait(), read_lines(stdout), read_lines(stderr));
    let status = status?;

    let mut lines = out;
    lines.extend(err);
    let errors = lines.iter().filter(|line| line.starts_with("e: ")).cloned().collect();
    let tail = lines[lines.len().saturating_sub(TAIL_LINES)..].to_vec();
    Ok(BuildOutcome {
        success: status.success(),
        errors,
        lines,
        tail,
    })
}

async fn read_lines<R: AsyncRead + Unpin>(reader: R) -> Vec<String> {
    let mut lines = BufReader::new(reader).lines();
    let mut collected = Vec::new();
    while let Ok(Some(line)) = lines.next_line().await {
        collected.push(line);
    }
    collected
}

/// A source file newer than the atlas binary and the last successful build.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Staleness {
    pub source: PathBuf,
}

/// Returns the newest source of `target` if it is newer than `max(binary mtime, last_build)`. A
/// missing binary is not "stale" here; the caller reports it as not built.
pub fn check_staleness(target: &AtlasTarget, last_build: Option<SystemTime>) -> Option<Staleness> {
    let binary_time = mtime(&target.executable)?;
    let reference = last_build.map_or(binary_time, |built| built.max(binary_time));
    let (newest_time, newest) = newest_source(target)?;
    (newest_time > reference).then_some(Staleness { source: newest })
}

fn newest_source(target: &AtlasTarget) -> Option<(SystemTime, PathBuf)> {
    let mut newest: Option<(SystemTime, PathBuf)> = None;
    let mut consider = |time: SystemTime, path: PathBuf| {
        if newest.as_ref().is_none_or(|(best, _)| time > *best) {
            newest = Some((time, path));
        }
    };
    for dir in &target.source_dirs {
        walk(dir, &mut consider);
    }
    for dir in &target.project_dirs {
        let Ok(entries) = fs::read_dir(dir) else { continue };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_file() && is_build_file(&entry.file_name().to_string_lossy()) {
                if let Some(time) = mtime(&path) {
                    consider(time, path);
                }
            }
        }
    }
    newest
}

fn walk(dir: &Path, consider: &mut impl FnMut(SystemTime, PathBuf)) {
    let Ok(entries) = fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        let path = entry.path();
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if name.starts_with('.') || name == "build" {
            continue;
        }
        if path.is_dir() {
            walk(&path, consider);
        } else if let Some(time) = mtime(&path) {
            consider(time, path);
        }
    }
}

fn mtime(path: &Path) -> Option<SystemTime> {
    fs::metadata(path).and_then(|meta| meta.modified()).ok()
}
