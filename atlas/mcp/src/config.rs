//! What the daemon knows before it asks the project: where it runs and where it may write. Tests
//! point each piece at a sandbox.

use std::collections::hash_map::DefaultHasher;
use std::env;
use std::hash::{Hash, Hasher};
use std::path::{Path, PathBuf};
use std::time::Duration;

/// Environment variables removed from a hidden atlas so it never reaches the host session.
pub const HIDDEN_ENV_REMOVE: &[&str] = &["DISPLAY", "HYPRLAND_INSTANCE_SIGNATURE"];

/// A program and its arguments, run with the project root as the working directory.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Command {
    pub program: String,
    pub args: Vec<String>,
}

#[derive(Clone, Debug)]
pub struct Config {
    /// Root of the Gradle build the atlases belong to.
    pub root: PathBuf,
    /// Directory for the inspect socket, the Wayland socket and the atlas log.
    pub runtime_dir: PathBuf,
    /// Directory for what survives a session: the discovery cache.
    pub cache_dir: PathBuf,
    /// Names the per-instance files; the daemon's pid in production.
    pub instance: u32,
    /// How Gradle is invoked; the daemon appends its own arguments.
    pub gradle: Command,
    /// Extra environment for the atlas, on top of the variables the daemon sets itself.
    pub atlas_env: Vec<(String, String)>,
    /// Whether the daemon starts its own headless Wayland compositor for a hidden atlas.
    pub headless: bool,
    /// Whether the daemon runs inside a graphical session, where a visible atlas can open.
    pub graphical_session: bool,
    /// How long a freshly started atlas may take to answer `tools/list`.
    pub ready_timeout: Duration,
    /// How long a single proxied request may take.
    pub request_timeout: Duration,
}

impl Config {
    /// Production defaults for the project at `root`.
    pub fn for_root(root: PathBuf) -> Self {
        let runtime_dir = env::var_os("XDG_RUNTIME_DIR").map(PathBuf::from).unwrap_or_else(env::temp_dir);
        let cache_dir = env::var_os("XDG_CACHE_HOME")
            .map(PathBuf::from)
            .or_else(|| env::var_os("HOME").map(|home| PathBuf::from(home).join(".cache")))
            .unwrap_or_else(env::temp_dir)
            .join("atlas-mcp");
        Self {
            gradle: default_gradle(&root),
            root,
            runtime_dir,
            cache_dir,
            instance: std::process::id(),
            atlas_env: Vec::new(),
            headless: true,
            graphical_session: env::var_os("WAYLAND_DISPLAY").is_some() || env::var_os("DISPLAY").is_some(),
            ready_timeout: Duration::from_secs(20),
            request_timeout: Duration::from_secs(60),
        }
    }

    /// The Gradle invocation that builds `task`.
    pub fn build_command(&self, task: &str) -> Command {
        self.gradle_with(["--console=plain", task])
    }

    /// The Gradle invocation that makes `init_script` report the atlases.
    pub fn discovery_command(&self, init_script: &Path) -> Command {
        let script = init_script.to_string_lossy();
        // The script reads other projects while its task runs, which the configuration cache forbids.
        self.gradle_with(["-q", "--no-configuration-cache", "-I", &script, "knAtlasDescribe"])
    }

    fn gradle_with<'a>(&self, args: impl IntoIterator<Item = &'a str>) -> Command {
        let mut command = self.gradle.clone();
        command.args.extend(args.into_iter().map(str::to_owned));
        command
    }

    /// The inspect socket the atlas listens on.
    pub fn socket_path(&self) -> PathBuf {
        self.runtime_dir.join(format!("atlas-inspect-{}.sock", self.instance))
    }

    /// The atlas stdout and stderr, appended across restarts.
    pub fn log_path(&self) -> PathBuf {
        self.runtime_dir.join(format!("{}.log", self.wayland_name()))
    }

    /// Name of the headless Wayland socket (inside `runtime_dir`) the atlas renders to.
    pub fn wayland_name(&self) -> String {
        format!("atlas-mcp-{}", self.instance)
    }

    /// Where the discovery init script is put while Gradle reads it.
    pub fn init_script_path(&self) -> PathBuf {
        self.runtime_dir.join(format!("atlas-mcp-{}.init.gradle", self.instance))
    }

    /// The discovery cache of this project.
    pub fn cache_path(&self) -> PathBuf {
        let mut hasher = DefaultHasher::new();
        self.root.hash(&mut hasher);
        self.cache_dir.join(format!("{:016x}.json", hasher.finish()))
    }
}

/// The project's Gradle wrapper, or the `gradle` on `PATH` when it has none.
fn default_gradle(root: &Path) -> Command {
    let wrapper = root.join("gradlew");
    let program = if wrapper.is_file() { wrapper.to_string_lossy().into_owned() } else { "gradle".to_owned() };
    Command { program, args: Vec::new() }
}

/// The nearest directory at or above `start` that holds a Gradle settings file: the root of the
/// build, even when the daemon was started inside one of its modules.
pub fn find_gradle_root(start: &Path) -> Option<PathBuf> {
    start
        .ancestors()
        .find(|dir| dir.join("settings.gradle.kts").is_file() || dir.join("settings.gradle").is_file())
        .map(Path::to_path_buf)
}
