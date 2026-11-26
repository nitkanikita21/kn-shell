//! `atlas-mcp`: an MCP server over stdio that runs the Atlas on a private headless display.
//!
//! stdout is the MCP channel, so everything here logs to stderr.

use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;

use atlas_mcp::atlas::Supervisor;
use atlas_mcp::config::{find_gradle_root, Config};
use atlas_mcp::server::Daemon;
use rmcp::transport::stdio;
use rmcp::ServiceExt;
use tokio::signal::unix::{signal, SignalKind};

const USAGE: &str = "usage: atlas-mcp [--root <project root>]";

#[tokio::main]
async fn main() -> ExitCode {
    let root = match parse_args(std::env::args().skip(1)) {
        Ok(root) => root,
        Err(message) => {
            eprintln!("atlas-mcp: {message}\n{USAGE}");
            return ExitCode::from(2);
        }
    };
    let config = Config::for_root(root);
    let supervisor = Arc::new(Supervisor::new(config));

    let running = match Daemon::new(supervisor.clone()).serve(stdio()).await {
        Ok(running) => running,
        Err(error) => {
            eprintln!("[atlas-mcp] cannot start the MCP server: {error}");
            supervisor.shutdown().await;
            return ExitCode::FAILURE;
        }
    };

    let mut terminate = signal(SignalKind::terminate()).expect("cannot listen for SIGTERM");
    let mut interrupt = signal(SignalKind::interrupt()).expect("cannot listen for SIGINT");
    tokio::select! {
        _ = running.waiting() => eprintln!("[atlas-mcp] client closed stdin"),
        _ = terminate.recv() => eprintln!("[atlas-mcp] SIGTERM"),
        _ = interrupt.recv() => eprintln!("[atlas-mcp] SIGINT"),
    }
    supervisor.shutdown().await;
    // The stdin reader may still be blocked on a runtime thread; exit without waiting for it.
    std::process::exit(0);
}

fn parse_args(mut args: impl Iterator<Item = String>) -> Result<PathBuf, String> {
    let mut root = None;
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--root" => root = Some(PathBuf::from(args.next().ok_or("--root needs a path")?)),
            "-h" | "--help" => {
                println!("{USAGE}");
                std::process::exit(0);
            }
            other => return Err(format!("unknown argument {other}")),
        }
    }
    let given = root.is_some();
    let root = match root {
        Some(root) => root,
        None => std::env::current_dir().map_err(|error| format!("cannot read the working directory: {error}"))?,
    };
    let root = root
        .canonicalize()
        .map_err(|error| format!("bad project root {}: {error}", root.display()))?;
    // Without --root the daemon may be started anywhere inside the build, or outside any: then the
    // directory stays as it is and simply has no atlas.
    Ok(if given { root } else { find_gradle_root(&root).unwrap_or(root) })
}
