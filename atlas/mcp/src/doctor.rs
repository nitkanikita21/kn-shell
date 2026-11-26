//! `atlas-mcp doctor`: says whether this machine can run an atlas, and what is missing. It reads the
//! machine and the project's layout only: no Gradle run, no atlas start.

use std::env;
use std::fs;
use std::path::Path;
use std::process::Command as Process;

use crate::config::find_gradle_root;

/// Native libraries the atlas links, as `pkg-config` names them, with the name shown to the user.
const PKG_CONFIG_MODULES: &[(&str, &str)] = &[
    ("gtk4", "GTK 4"),
    ("gtk4-layer-shell-0", "gtk4-layer-shell"),
    ("librsvg-2.0", "librsvg"),
    ("epoxy", "libepoxy"),
    ("fontconfig", "fontconfig"),
];

/// Oldest JDK the Gradle build runs on.
const MIN_JAVA: u32 = 21;

/// What one check found. `Missing` fails the doctor; `Warn` only tells.
#[derive(Debug, PartialEq)]
pub enum Verdict {
    Ok(String),
    Warn(String),
    Missing(String),
}

/// Runs every check, prints one line each, and returns whether nothing required is missing.
pub fn run(root: &Path) -> bool {
    let mut checks: Vec<(String, Verdict)> = Vec::new();
    for (module, label) in PKG_CONFIG_MODULES {
        checks.push((label.to_string(), pkg_config(module, label)));
    }
    checks.push(("JDK".to_owned(), java(env::var_os("JAVA_HOME").as_deref())));
    checks.push(("Gradle project".to_owned(), gradle_project(root)));
    checks.push(("render node".to_owned(), render_node(&render_nodes())));
    checks.push(("display".to_owned(), display(env::var_os("WAYLAND_DISPLAY").is_some(), env::var_os("DISPLAY").is_some())));

    let mut missing = 0;
    for (name, verdict) in &checks {
        let (tag, text) = match verdict {
            Verdict::Ok(text) => ("ok     ", text),
            Verdict::Warn(text) => ("warn   ", text),
            Verdict::Missing(text) => {
                missing += 1;
                ("MISSING", text)
            }
        };
        println!("{tag}  {name:<16} {text}");
    }
    if missing == 0 {
        println!("\nthe machine can run an atlas");
    } else {
        println!("\n{missing} required check(s) failed; fix the MISSING lines and run doctor again");
    }
    missing == 0
}

fn pkg_config(module: &str, label: &str) -> Verdict {
    match Process::new("pkg-config").args(["--modversion", module]).output() {
        Ok(output) if output.status.success() => {
            Verdict::Ok(String::from_utf8_lossy(&output.stdout).trim().to_owned())
        }
        Ok(_) => Verdict::Missing(format!("no pkg-config module {module}; install the development package of {label}")),
        Err(_) => Verdict::Missing("pkg-config is not installed".to_owned()),
    }
}

/// The JDK: `$JAVA_HOME/bin/java` when set, else `java` on `PATH`.
fn java(java_home: Option<&std::ffi::OsStr>) -> Verdict {
    let program = match java_home {
        Some(home) => Path::new(home).join("bin/java").to_string_lossy().into_owned(),
        None => "java".to_owned(),
    };
    // `java -version` prints to stderr.
    let output = match Process::new(&program).arg("-version").output() {
        Ok(output) => output,
        Err(_) => return Verdict::Missing(format!("cannot run {program}; install JDK {MIN_JAVA} or newer")),
    };
    let text = String::from_utf8_lossy(&output.stderr).into_owned();
    match java_major(&text) {
        Some(major) if major >= MIN_JAVA => Verdict::Ok(format!("JDK {major}")),
        Some(major) => Verdict::Missing(format!("JDK {major} found, JDK {MIN_JAVA} or newer is needed")),
        None => Verdict::Missing(format!("cannot read the version that {program} reports")),
    }
}

/// The major version from the first line of `java -version`: `21.0.2` gives 21, `1.8.0_402` gives 8.
pub fn java_major(output: &str) -> Option<u32> {
    let quoted = output.split('"').nth(1)?;
    let mut parts = quoted.split(['.', '_', '-', '+']);
    let first: u32 = parts.next()?.parse().ok()?;
    if first == 1 {
        parts.next()?.parse().ok()
    } else {
        Some(first)
    }
}

fn gradle_project(root: &Path) -> Verdict {
    if find_gradle_root(root).is_none() {
        return Verdict::Missing(format!("no settings.gradle(.kts) at or above {}", root.display()));
    }
    if root.join("gradlew").is_file() {
        Verdict::Ok(format!("{} with ./gradlew", root.display()))
    } else {
        Verdict::Warn("no ./gradlew here: the daemon runs gradle from PATH".to_owned())
    }
}

/// The DRM render nodes of the machine, by file name (`renderD128`, …).
fn render_nodes() -> Vec<String> {
    let Ok(entries) = fs::read_dir("/dev/dri") else {
        return Vec::new();
    };
    let mut names: Vec<String> = entries
        .flatten()
        .map(|entry| entry.file_name().to_string_lossy().into_owned())
        .filter(|name| name.starts_with("renderD"))
        .collect();
    names.sort();
    names
}

/// The headless atlas renders on the first render node. More than one is worth a warning: the
/// daemon always takes the first, whatever GPU it is.
pub fn render_node(names: &[String]) -> Verdict {
    match names {
        [] => Verdict::Missing("no /dev/dri/renderD*: the headless atlas needs a GPU render node".to_owned()),
        [only] => Verdict::Ok(format!("/dev/dri/{only}")),
        [first, ..] => Verdict::Warn(format!(
            "several render nodes ({}); the daemon uses /dev/dri/{first}. If screenshots fail, check that this is the right GPU",
            names.join(", ")
        )),
    }
}

/// A graphical session lets a visible atlas open; without one the atlas can only run hidden.
pub fn display(wayland: bool, x11: bool) -> Verdict {
    if wayland || x11 {
        Verdict::Ok("graphical session".to_owned())
    } else {
        Verdict::Warn("no WAYLAND_DISPLAY or DISPLAY: the atlas can only run hidden".to_owned())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_major_version_of_java() {
        assert_eq!(java_major("openjdk version \"21.0.2\" 2024-01-16\nOpenJDK"), Some(21));
        assert_eq!(java_major("java version \"1.8.0_402\""), Some(8));
        assert_eq!(java_major("no version here"), None);
    }

    #[test]
    fn a_missing_render_node_is_required_and_several_are_a_warning() {
        assert!(matches!(render_node(&[]), Verdict::Missing(_)));
        assert!(matches!(render_node(&["renderD128".to_owned()]), Verdict::Ok(_)));
        let two = ["renderD128".to_owned(), "renderD129".to_owned()];
        match render_node(&two) {
            Verdict::Warn(text) => assert!(text.contains("renderD128") && text.contains("renderD129"), "{text}"),
            other => panic!("expected a warning, got {other:?}"),
        }
    }

    #[test]
    fn no_session_only_warns() {
        assert!(matches!(display(false, false), Verdict::Warn(_)));
        assert!(matches!(display(true, false), Verdict::Ok(_)));
    }
}
