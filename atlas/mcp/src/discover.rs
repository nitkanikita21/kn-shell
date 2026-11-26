//! Finding the atlases of a Gradle project, without knowing anything about it in advance.
//!
//! Gradle itself is asked, through an init script shipped inside this binary. The answer is cached,
//! so only the first session in a project (or one after its build scripts changed) pays for it.

use std::fs;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

use serde_json::{json, Value};

use crate::build::run_build;
use crate::config::{find_gradle_root, Config};

/// The init script that makes Gradle describe the atlases.
const INIT_SCRIPT: &str = include_str!("atlas-discover.init.gradle");
/// Prefix of the lines the init script reports atlases with.
const MARKER: &str = "KN_ATLAS ";
/// How deep below the root build scripts are looked for.
const SCAN_DEPTH: usize = 4;

/// One atlas: a module with a `runAtlas…Executable…` task.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AtlasTarget {
    /// Gradle path of the module, for example `:app`.
    pub module: String,
    /// The task that builds the executable.
    pub link_task: String,
    pub executable: PathBuf,
    pub working_dir: PathBuf,
    /// Searched recursively by the staleness check.
    pub source_dirs: Vec<PathBuf>,
    /// Their `*.gradle(.kts)` files count as sources too (not recursive).
    pub project_dirs: Vec<PathBuf>,
}

impl AtlasTarget {
    pub fn to_json(&self) -> Value {
        json!({
            "module": self.module,
            "linkTask": self.link_task,
            "executable": self.executable,
            "workingDir": self.working_dir,
            "sourceDirs": self.source_dirs,
            "projectDirs": self.project_dirs,
        })
    }

    pub fn from_json(value: &Value) -> Option<Self> {
        let text = |key: &str| value.get(key).and_then(Value::as_str).map(str::to_owned);
        let paths = |key: &str| -> Option<Vec<PathBuf>> {
            value.get(key)?.as_array()?.iter().map(|path| path.as_str().map(PathBuf::from)).collect()
        };
        Some(Self {
            module: text("module")?,
            link_task: text("linkTask")?,
            executable: text("executable")?.into(),
            working_dir: text("workingDir")?.into(),
            source_dirs: paths("sourceDirs")?,
            project_dirs: paths("projectDirs")?,
        })
    }
}

/// The atlases reported in the output of a discovery run.
pub fn parse_output(lines: &[String]) -> Vec<AtlasTarget> {
    lines
        .iter()
        .filter_map(|line| line.strip_prefix(MARKER))
        .filter_map(|body| {
            let target = serde_json::from_str(body).ok().as_ref().and_then(AtlasTarget::from_json);
            if target.is_none() {
                eprintln!("[atlas-mcp] ignoring a malformed atlas description: {body}");
            }
            target
        })
        .collect()
}

/// The id of the Gradle plugin that makes a module an atlas. A build script that does not name it
/// has no atlas, as far as the daemon is concerned.
pub const PLUGIN_ID: &str = "dev.knshell.atlas";

/// Whether any build script of the project applies the atlas plugin. This keeps the tool, when it is
/// registered for every project, from running Gradle in projects that have nothing to do with it.
pub fn mentions_atlas(root: &Path) -> bool {
    build_files(root, SCAN_DEPTH)
        .iter()
        .any(|file| fs::read_to_string(file).is_ok_and(|text| text.contains(PLUGIN_ID)))
}

/// The cached answer, unless a build script changed since it was written.
pub fn cached(config: &Config) -> Option<Vec<AtlasTarget>> {
    let path = config.cache_path();
    let written = fs::metadata(&path).and_then(|meta| meta.modified()).ok()?;
    let changed = build_files(&config.root, SCAN_DEPTH)
        .iter()
        .any(|file| fs::metadata(file).and_then(|meta| meta.modified()).is_ok_and(|time: SystemTime| time > written));
    if changed {
        return None;
    }
    let value: Value = serde_json::from_str(&fs::read_to_string(&path).ok()?).ok()?;
    if value.get("root")?.as_str()? != config.root.to_string_lossy() {
        return None;
    }
    let targets: Vec<AtlasTarget> =
        value.get("atlases")?.as_array()?.iter().map(AtlasTarget::from_json).collect::<Option<_>>()?;
    (!targets.is_empty()).then_some(targets)
}

/// Asks Gradle and caches the answer.
pub async fn discover(config: &Config) -> Result<Vec<AtlasTarget>, String> {
    if find_gradle_root(&config.root).is_none() {
        return Err(format!(
            "{} is not inside a Gradle project (no settings.gradle or settings.gradle.kts)",
            config.root.display()
        ));
    }
    let script = config.init_script_path();
    fs::write(&script, INIT_SCRIPT).map_err(|error| format!("cannot write {}: {error}", script.display()))?;
    let command = config.discovery_command(&script);
    let outcome = run_build(&config.root, &command).await;
    let _ = fs::remove_file(&script);
    let outcome = outcome.map_err(|error| {
        format!("cannot run {}: {error} (the project needs a gradlew, or gradle on PATH)", command.program)
    })?;
    if !outcome.success {
        return Err(format!("Gradle could not describe the project:\n{}", outcome.tail.join("\n")));
    }
    let targets = parse_output(&outcome.lines);
    if !targets.is_empty() {
        store(config, &targets);
    }
    Ok(targets)
}

fn store(config: &Config, targets: &[AtlasTarget]) {
    let path = config.cache_path();
    let body = json!({
        "root": config.root,
        "atlases": targets.iter().map(AtlasTarget::to_json).collect::<Vec<_>>(),
    });
    let written = path.parent().map_or(Ok(()), fs::create_dir_all).and_then(|()| fs::write(&path, body.to_string()));
    if let Err(error) = written {
        // Only costs a new discovery next time.
        eprintln!("[atlas-mcp] cannot cache the discovery in {}: {error}", path.display());
    }
}

/// Gradle build scripts and version catalogs under `dir`, skipping build output and hidden folders.
fn build_files(dir: &Path, depth: usize) -> Vec<PathBuf> {
    let mut found = Vec::new();
    let Ok(entries) = fs::read_dir(dir) else { return found };
    for entry in entries.flatten() {
        let path = entry.path();
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if name.starts_with('.') || matches!(name.as_ref(), "build" | "node_modules" | "target") {
            continue;
        }
        if path.is_dir() {
            if depth > 0 {
                found.extend(build_files(&path, depth - 1));
            }
        } else if is_build_file(&name) {
            found.push(path);
        }
    }
    found
}

pub(crate) fn is_build_file(name: &str) -> bool {
    name.ends_with(".gradle") || name.ends_with(".gradle.kts") || name.ends_with(".versions.toml")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_marked_and_well_formed_lines_are_atlases() {
        let lines = [
            "> Task :app:knAtlasDescribe",
            r#"KN_ATLAS {"module":":app","linkTask":":app:linkAtlas","executable":"/p/a.kexe","workingDir":"/p/app","sourceDirs":["/p/app/src"],"projectDirs":["/p/app"]}"#,
            "KN_ATLAS {broken",
            r#"KN_ATLAS {"module":":half"}"#,
        ]
        .map(str::to_owned);

        let targets = parse_output(&lines);

        assert_eq!(targets.len(), 1);
        assert_eq!(targets[0].module, ":app");
        assert_eq!(targets[0].executable, PathBuf::from("/p/a.kexe"));
        assert_eq!(AtlasTarget::from_json(&targets[0].to_json()).as_ref(), Some(&targets[0]));
    }
}
