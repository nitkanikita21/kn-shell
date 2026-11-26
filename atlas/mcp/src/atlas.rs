//! The atlas process: starting it, talking to its inspect socket, rebuilding it and cleaning up.
//!
//! One mutex guards the process state. Everything that needs the atlas (proxied calls, start, stop,
//! reload's swap) goes through it. The Gradle build runs outside that mutex, under its own lock, so
//! proxied calls keep working while a build is in progress.

use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Child, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant, SystemTime};

use rmcp::model::{CallToolResult, ContentBlock, JsonObject, Tool};
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixStream;
use tokio::sync::Mutex;
use tokio::time::{sleep, timeout};

use crate::build::{check_staleness, run_build};
use crate::compositor::Compositor;
use crate::config::{Config, HIDDEN_ENV_REMOVE};
use crate::discover::{self, AtlasTarget};

/// How many log lines are quoted when the atlas dies or fails to start.
const LOG_TAIL_LINES: usize = 20;
/// Pause between readiness probes.
const READY_POLL: Duration = Duration::from_millis(100);

/// The result of an operation, plus whether the list of tools may have changed (so the server must
/// send `notifications/tools/list_changed`).
pub struct Reply {
    pub result: CallToolResult,
    pub tools_changed: bool,
}

impl Reply {
    fn ok(text: impl Into<String>, tools_changed: bool) -> Self {
        Self {
            result: CallToolResult::success(vec![ContentBlock::text(text)]),
            tools_changed,
        }
    }

    fn failed(text: impl Into<String>, tools_changed: bool) -> Self {
        Self {
            result: CallToolResult::error(vec![ContentBlock::text(text)]),
            tools_changed,
        }
    }
}

/// A running atlas. Dropping it kills and reaps the process and removes its inspect socket.
struct Atlas {
    child: Child,
    socket: PathBuf,
    /// Whether its window is on the user's desktop rather than on the headless display.
    visible: bool,
}

impl Atlas {
    fn is_alive(&mut self) -> bool {
        matches!(self.child.try_wait(), Ok(None))
    }
}

impl Drop for Atlas {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
        let _ = fs::remove_file(&self.socket);
    }
}

#[derive(Default)]
struct State {
    /// The atlases of the project, once it has been asked.
    targets: Option<Vec<AtlasTarget>>,
    /// Module chosen with `use_atlas`; needed only when the project has several atlases.
    selected: Option<String>,
    /// Whether the next atlas opens on the user's desktop; set with `set_visible`.
    visible: bool,
    /// Something the daemon did on its own, to be told with the next atlas reply.
    notice: Option<String>,
    atlas: Option<Atlas>,
    /// Tools reported by the running atlas, refreshed on every start.
    tools: Vec<Tool>,
    /// The headless display; started with the first atlas and kept until shutdown.
    display: Option<Compositor>,
    /// When the last successful build finished (or started, when edits during the build count too).
    last_build: Option<std::time::SystemTime>,
    /// Why the last start failed, shown by `status`.
    last_error: Option<String>,
}

pub struct Supervisor {
    config: Config,
    state: Mutex<State>,
    build_lock: Mutex<()>,
    next_id: AtomicU64,
}

impl State {
    /// Pid of the atlas if it is still running.
    fn live_pid(&mut self) -> Option<u32> {
        let alive = self.atlas.as_mut()?.is_alive();
        if alive {
            self.atlas.as_ref().map(|atlas| atlas.child.id())
        } else {
            None
        }
    }
}

impl Supervisor {
    pub fn new(config: Config) -> Self {
        Self {
            config,
            state: Mutex::new(State::default()),
            build_lock: Mutex::new(()),
            next_id: AtomicU64::new(1),
        }
    }

    /// The atlas tools. Starts the atlas if it is not running. Returns `(tools, started)`; when the
    /// project has no atlas or it cannot be started the list is empty and the reason is logged.
    pub async fn list_atlas_tools(&self) -> (Vec<Tool>, bool) {
        let mut state = self.state.lock().await;
        // Listing happens in every project the tool is registered for: only bother Gradle in one
        // that looks like it has an atlas.
        if state.targets.is_none() && discover::cached(&self.config).is_none() && !discover::mentions_atlas(&self.config.root)
        {
            return (Vec::new(), false);
        }
        match self.ensure_running(&mut state).await {
            Ok(started) => (state.tools.clone(), started),
            Err(error) => {
                eprintln!("[atlas-mcp] atlas is not available, listing only daemon tools: {error}");
                (Vec::new(), false)
            }
        }
    }

    /// Lists the atlases of the project, asking Gradle again.
    pub async fn list_atlases(&self) -> Reply {
        let mut state = self.state.lock().await;
        if let Err(error) = self.rediscover(&mut state).await {
            return Reply::failed(error, false);
        }
        let current = self.current_target(&state).ok().map(|target| target.module);
        let lines: Vec<String> = state
            .targets
            .iter()
            .flatten()
            .map(|target| {
                format!(
                    "{} {}{}\n    {}{}",
                    if current.as_deref() == Some(&target.module) { "*" } else { " " },
                    target.module,
                    if current.is_none() { " (choose with use_atlas)" } else { "" },
                    target.executable.display(),
                    exists_note(&target.executable),
                )
            })
            .collect();
        Reply::ok(lines.join("\n"), false)
    }

    /// Switches to the atlas of `module` and starts it.
    pub async fn use_atlas(&self, module: &str) -> Reply {
        let mut state = self.state.lock().await;
        if let Err(error) = self.ensure_discovered(&mut state).await {
            return Reply::failed(error, false);
        }
        let targets = state.targets.as_deref().unwrap_or_default();
        let wanted = module.trim_start_matches(':');
        let Some(found) = targets.iter().find(|target| target.module.trim_start_matches(':') == wanted) else {
            return Reply::failed(format!("no atlas in module \"{module}\"; atlases: {}", modules(targets)), false);
        };
        let module = found.module.clone();
        state.atlas = None;
        state.tools.clear();
        state.last_build = None;
        state.selected = Some(module.clone());
        match self.ensure_running(&mut state).await {
            Ok(_) => Reply::ok(format!("using the atlas of {module}"), true),
            Err(error) => Reply::failed(format!("using the atlas of {module}, but it is not running: {error}"), true),
        }
    }

    /// Proxies `tools/call` to the atlas. Starts it if needed. Transport failures and atlas errors
    /// come back as `isError` results with the message as text.
    pub async fn call_atlas(&self, name: &str, arguments: Option<JsonObject>) -> Reply {
        let mut state = self.state.lock().await;
        let started = match self.ensure_running(&mut state).await {
            Ok(started) => started,
            Err(error) => return Reply::failed(format!("atlas is not running: {error}"), false),
        };
        let notice = state.notice.take();
        let params = json!({ "name": name, "arguments": arguments.unwrap_or_default() });
        let mut reply = match self.exchange(&mut state, "tools/call", Some(params)).await {
            Ok(value) => match serde_json::from_value::<CallToolResult>(value) {
                Ok(result) => Reply {
                    result,
                    tools_changed: started,
                },
                Err(error) => Reply::failed(format!("malformed tools/call result from the atlas: {error}"), started),
            },
            Err(error) => Reply::failed(error, started),
        };
        for extra in notice.into_iter().chain(self.staleness_warning(&state)) {
            reply.result.content.push(ContentBlock::text(extra));
        }
        reply
    }

    /// Moves the atlas to the user's desktop, or back to the headless display. The atlas restarts.
    pub async fn set_visible(&self, visible: bool) -> Reply {
        if visible && !self.config.graphical_session {
            return Reply::failed(
                "no graphical session to show the atlas in (neither WAYLAND_DISPLAY nor DISPLAY is set)",
                false,
            );
        }
        let mut state = self.state.lock().await;
        let place = if visible { "visible on the desktop" } else { "hidden" };
        let already = state.atlas.as_mut().is_some_and(|atlas| atlas.visible == visible && atlas.is_alive());
        state.visible = visible;
        state.notice = None;
        if already {
            return Reply::ok(format!("the atlas is already {place}"), false);
        }
        state.atlas = None;
        match self.ensure_running(&mut state).await {
            Ok(_) => Reply::ok(
                format!("the atlas is now {place}; it was restarted, so stories and args are at their defaults"),
                true,
            ),
            Err(error) => {
                // Do not keep trying to open a window that cannot be opened.
                state.visible = false;
                Reply::failed(format!("cannot make the atlas {place}: {error}"), true)
            }
        }
    }

    /// Builds the atlas and restarts it. On a build failure the old atlas keeps running and the reply
    /// carries the compiler's `e:` lines.
    pub async fn reload(&self) -> Reply {
        let _building = self.build_lock.lock().await;
        let target = {
            let mut state = self.state.lock().await;
            match self.ensure_discovered(&mut state).await.and_then(|()| self.current_target(&state)) {
                Ok(target) => target,
                Err(error) => return Reply::failed(error, false),
            }
        };
        let started_at = SystemTime::now();
        let command = self.config.build_command(&target.link_task);
        let outcome = match run_build(&self.config.root, &command).await {
            Ok(outcome) => outcome,
            Err(error) => return Reply::failed(format!("cannot run the build command: {error}"), false),
        };
        if !outcome.success {
            let body = if outcome.errors.is_empty() {
                outcome.tail.join("\n")
            } else {
                outcome.errors.join("\n")
            };
            return Reply::failed(
                format!("build failed; the previous atlas keeps running.\n{body}"),
                false,
            );
        }
        if !target.executable.is_file() {
            return Reply::failed(
                format!(
                    "build succeeded but the binary is missing: {}",
                    target.executable.display()
                ),
                false,
            );
        }

        let mut state = self.state.lock().await;
        state.atlas = None;
        state.last_build = Some(started_at);
        match self.start_atlas(&mut state).await {
            Ok(()) => {
                let pid = state.atlas.as_ref().map_or(0, |atlas| atlas.child.id());
                Reply::ok(format!("build ok; atlas restarted (pid {pid})"), true)
            }
            Err(error) => {
                state.last_error = Some(error.clone());
                Reply::failed(format!("build ok, but the atlas did not start:\n{error}"), true)
            }
        }
    }

    /// Stops the atlas. It starts again on the next call that needs it.
    pub async fn stop(&self) -> Reply {
        let mut state = self.state.lock().await;
        match state.atlas.take() {
            Some(atlas) => {
                let pid = atlas.child.id();
                drop(atlas);
                Reply::ok(format!("atlas stopped (pid {pid})"), false)
            }
            None => Reply::ok("atlas was not running", false),
        }
    }

    /// A text report: process, files, tools and staleness.
    pub async fn status(&self) -> CallToolResult {
        let mut state = self.state.lock().await;
        let running = state.live_pid();
        let mut lines = vec![format!("project: {}", self.config.root.display())];
        match &state.targets {
            Some(targets) if !targets.is_empty() => lines.push(format!("atlases: {}", modules(targets))),
            Some(_) => lines.push("atlases: none found".to_owned()),
            None => lines.push("atlases: not looked up yet (see list_atlases)".to_owned()),
        }
        match running {
            Some(pid) => lines.push(format!("atlas: running, pid {pid}, {} tools", state.tools.len())),
            None => {
                lines.push("atlas: not running".to_owned());
                if let Some(error) = &state.last_error {
                    lines.push(format!("last start error: {error}"));
                }
            }
        }
        lines.push(format!(
            "window: {}",
            if state.visible { "visible on the desktop" } else { "hidden (headless display)" }
        ));
        if let Ok(target) = self.current_target(&state) {
            lines.push(format!("module: {}", target.module));
            lines.push(format!(
                "binary: {}{}",
                target.executable.display(),
                exists_note(&target.executable)
            ));
        }
        lines.push(format!("socket: {}", self.config.socket_path().display()));
        lines.push(format!("log: {}", self.config.log_path().display()));
        if let Some(warning) = self.staleness_warning(&state) {
            lines.push(warning);
        }
        CallToolResult::success(vec![ContentBlock::text(lines.join("\n"))])
    }

    /// The last `lines` lines of the atlas log.
    pub async fn logs(&self, lines: usize) -> CallToolResult {
        match fs::read_to_string(self.config.log_path()) {
            Ok(text) => {
                let all: Vec<&str> = text.lines().collect();
                let tail = all[all.len().saturating_sub(lines)..].join("\n");
                CallToolResult::success(vec![ContentBlock::text(tail)])
            }
            Err(error) => CallToolResult::error(vec![ContentBlock::text(format!("no atlas log: {error}"))]),
        }
    }

    /// Kills the atlas, drops the display and removes the socket and log. Called on exit.
    pub async fn shutdown(&self) {
        let mut state = self.state.lock().await;
        state.atlas = None;
        state.display = None;
        let _ = fs::remove_file(self.config.log_path());
    }

    /// Pid of the running atlas, if any.
    pub async fn pid(&self) -> Option<u32> {
        let mut state = self.state.lock().await;
        state.live_pid()
    }

    /// Loads the atlases from the cache or asks Gradle, unless that already happened.
    async fn ensure_discovered(&self, state: &mut State) -> Result<(), String> {
        if state.targets.as_ref().is_some_and(|targets| !targets.is_empty()) {
            return Ok(());
        }
        if let Some(targets) = discover::cached(&self.config) {
            state.targets = Some(targets);
            return Ok(());
        }
        self.rediscover(state).await
    }

    async fn rediscover(&self, state: &mut State) -> Result<(), String> {
        let targets = discover::discover(&self.config).await?;
        let empty = targets.is_empty();
        state.targets = Some(targets);
        if empty {
            return Err(format!(
                "no atlas in {}: apply the dev.knshell.atlas plugin to a module with a linuxX64 target",
                self.config.root.display()
            ));
        }
        Ok(())
    }

    /// The atlas everything acts on: the chosen module, or the only one there is.
    fn current_target(&self, state: &State) -> Result<AtlasTarget, String> {
        let targets = state.targets.as_deref().unwrap_or_default();
        let found = match &state.selected {
            Some(module) => targets.iter().find(|target| &target.module == module),
            None if targets.len() == 1 => targets.first(),
            None => None,
        };
        found.cloned().ok_or_else(|| match targets.len() {
            0 => "no atlas is known for this project".to_owned(),
            _ => format!("this project has several atlases, choose one with use_atlas: {}", modules(targets)),
        })
    }

    /// Starts the atlas if it is not running (or has died). Returns whether a start happened.
    async fn ensure_running(&self, state: &mut State) -> Result<bool, String> {
        if let Some(atlas) = state.atlas.as_mut() {
            if atlas.is_alive() {
                return Ok(false);
            }
            // A visible atlas that ended cleanly was closed by the user: do not reopen the window.
            if atlas.visible && matches!(atlas.child.try_wait(), Ok(Some(status)) if status.success()) {
                state.visible = false;
                state.notice =
                    Some("note: the atlas window was closed, so the atlas now runs hidden again".to_owned());
            }
            eprintln!("[atlas-mcp] atlas exited, restarting it");
            state.atlas = None;
        }
        match self.start_atlas(state).await {
            Ok(()) => Ok(true),
            Err(error) => {
                state.last_error = Some(error.clone());
                Err(error)
            }
        }
    }

    /// Spawns the atlas and waits until its inspect socket answers `tools/list`.
    async fn start_atlas(&self, state: &mut State) -> Result<(), String> {
        self.ensure_discovered(state).await?;
        let target = self.current_target(state)?;
        let visible = state.visible;
        if !visible && self.config.headless && state.display.is_none() {
            let display = Compositor::start(&self.config.wayland_name())
                .map_err(|error| format!("cannot start the headless display: {error}"))?;
            state.display = Some(display);
        }
        if !target.executable.is_file() {
            return Err(format!(
                "atlas binary not found: {} (run reload)",
                target.executable.display()
            ));
        }

        let socket = self.config.socket_path();
        let _ = fs::remove_file(&socket);
        let log_path = self.config.log_path();
        let mut log = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&log_path)
            .map_err(|error| format!("cannot open the log {}: {error}", log_path.display()))?;
        let _ = writeln!(log, "--- atlas start (daemon instance {}) ---", self.config.instance);
        let log_err = log.try_clone().map_err(|error| error.to_string())?;

        let mut command = std::process::Command::new(&target.executable);
        command
            // Both ways `atlasMain` accepts the socket: the variable works even when the module's
            // `main` does not pass its arguments on.
            .arg(format!("--inspect={}", socket.display()))
            .env("KN_ATLAS_INSPECT", &socket)
            .current_dir(&target.working_dir)
            .stdin(Stdio::null())
            .stdout(Stdio::from(log))
            .stderr(Stdio::from(log_err))
            .env_remove("KN_ATLAS_STORY");
        // A visible atlas simply inherits the session the daemon runs in.
        if !visible {
            command
                .env("WAYLAND_DISPLAY", self.config.wayland_name())
                .env("GDK_BACKEND", "wayland");
            for key in HIDDEN_ENV_REMOVE {
                command.env_remove(key);
            }
        }
        for (key, value) in &self.config.atlas_env {
            command.env(key, value);
        }
        // SAFETY: the closure only calls prctl, which is async-signal-safe.
        unsafe {
            command.pre_exec(|| {
                if libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL) == -1 {
                    return Err(io::Error::last_os_error());
                }
                Ok(())
            });
        }
        let child = command
            .spawn()
            .map_err(|error| format!("cannot start {}: {error}", target.executable.display()))?;
        let mut atlas = Atlas {
            child,
            socket: socket.clone(),
            visible,
        };

        let deadline = Instant::now() + self.config.ready_timeout;
        let tools = loop {
            if let Ok(Some(status)) = atlas.child.try_wait() {
                return Err(format!(
                    "atlas exited before it was ready ({status})\n{}",
                    self.log_tail()
                ));
            }
            match self.exchange_on(&socket, "tools/list", None).await {
                Ok(value) => break parse_tools(&value),
                Err(error) if Instant::now() >= deadline => {
                    return Err(format!(
                        "atlas did not answer on {} within {:?} ({error})\n{}",
                        socket.display(),
                        self.config.ready_timeout,
                        self.log_tail()
                    ));
                }
                Err(_) => sleep(READY_POLL).await,
            }
        };

        state.tools = tools;
        state.last_error = None;
        state.atlas = Some(atlas);
        Ok(())
    }

    /// One request on the running atlas, bounded by the request timeout. A timed-out atlas is killed
    /// so the next call starts a fresh one.
    async fn exchange(&self, state: &mut State, method: &str, params: Option<Value>) -> Result<Value, String> {
        let socket = self.config.socket_path();
        let limit = self.config.request_timeout;
        let outcome = timeout(limit, self.exchange_on(&socket, method, params)).await;
        match outcome {
            Ok(Ok(value)) => Ok(value),
            Ok(Err(error)) => {
                if let Some(atlas) = state.atlas.as_mut() {
                    if !atlas.is_alive() {
                        state.atlas = None;
                        return Err(format!(
                            "atlas died while handling {method}: {error}\n{}",
                            self.log_tail()
                        ));
                    }
                }
                Err(error)
            }
            Err(_) => {
                state.atlas = None;
                Err(format!(
                    "atlas did not answer {method} within {limit:?}; it was restarted on the next call"
                ))
            }
        }
    }

    /// Sends one JSON line to the inspect socket and reads one line back. Each request uses its own
    /// connection.
    async fn exchange_on(&self, socket: &Path, method: &str, params: Option<Value>) -> Result<Value, String> {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let mut request = json!({ "id": id, "method": method });
        if let Some(params) = params {
            request["params"] = params;
        }
        let stream = UnixStream::connect(socket)
            .await
            .map_err(|error| format!("connect: {error}"))?;
        let (reader, mut writer) = stream.into_split();
        let mut line = request.to_string();
        line.push('\n');
        writer
            .write_all(line.as_bytes())
            .await
            .map_err(|error| format!("write: {error}"))?;
        writer.flush().await.map_err(|error| format!("write: {error}"))?;

        let mut answer = String::new();
        let read = BufReader::new(reader)
            .read_line(&mut answer)
            .await
            .map_err(|error| format!("read: {error}"))?;
        if read == 0 {
            return Err("the atlas closed the connection without answering".to_owned());
        }
        let response: Value = serde_json::from_str(&answer).map_err(|error| format!("malformed response: {error}"))?;
        if response.get("id") != Some(&json!(id)) {
            return Err(format!("response id mismatch: expected {id}, got {}", response["id"]));
        }
        if let Some(error) = response.get("error") {
            let message = error.get("message").and_then(Value::as_str).unwrap_or("unknown error");
            return Err(message.to_owned());
        }
        response
            .get("result")
            .cloned()
            .ok_or_else(|| "response has neither result nor error".to_owned())
    }

    fn staleness_warning(&self, state: &State) -> Option<String> {
        let target = self.current_target(state).ok()?;
        let stale = check_staleness(&target, state.last_build)?;
        Some(format!(
            "warning: the atlas binary is older than its sources (for example {}); call reload to rebuild",
            stale.source.display()
        ))
    }

    fn log_tail(&self) -> String {
        let Ok(text) = fs::read_to_string(self.config.log_path()) else {
            return "(no atlas log)".to_owned();
        };
        let lines: Vec<&str> = text.lines().collect();
        let tail = lines[lines.len().saturating_sub(LOG_TAIL_LINES)..].join("\n");
        format!("last atlas log lines:\n{tail}")
    }
}

fn modules(targets: &[AtlasTarget]) -> String {
    targets.iter().map(|target| target.module.as_str()).collect::<Vec<_>>().join(", ")
}

fn exists_note(path: &Path) -> &'static str {
    if path.is_file() {
        ""
    } else {
        " (missing)"
    }
}

/// Parses `tools/list` and skips entries that are not valid MCP tools.
fn parse_tools(value: &Value) -> Vec<Tool> {
    let Some(entries) = value.get("tools").and_then(Value::as_array) else {
        eprintln!("[atlas-mcp] atlas tools/list has no tools array");
        return Vec::new();
    };
    entries
        .iter()
        .filter_map(|entry| match serde_json::from_value::<Tool>(entry.clone()) {
            Ok(tool) => Some(tool),
            Err(error) => {
                eprintln!("[atlas-mcp] skipping an invalid atlas tool: {error}");
                None
            }
        })
        .collect()
}
