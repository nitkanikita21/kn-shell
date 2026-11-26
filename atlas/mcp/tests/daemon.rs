//! Supervisor tests against `examples/fake_atlas.rs`, which `cargo test` builds next to these tests.
//! No Gradle, no display: the compositor is off and "Gradle" is a shell script.

use std::env;
use std::fs::{self, File};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;
use std::time::{Duration, SystemTime};

use atlas_mcp::atlas::Supervisor;
use atlas_mcp::config::{find_gradle_root, Command, Config};
use atlas_mcp::server::{merge_tools, own_tools};
use rmcp::model::{CallToolResult, JsonObject, Tool};
use serde_json::{json, Value};

const IMAGE_BYTES: usize = 3 * 1024 * 1024;
static COUNTER: AtomicU32 = AtomicU32::new(1);

/// A throwaway Gradle project: a module source tree in `app/src/`, the atlas binary `atlas.kexe`,
/// and a `gradlew` script that describes the atlases listed in `atlases.txt`, "builds" by installing
/// the fake atlas (or fails while `fail-build` exists) and logs every discovery to `discoveries`.
struct Sandbox {
    root: PathBuf,
    instance: u32,
}

impl Sandbox {
    /// A project with the single atlas `:app`.
    fn new() -> Self {
        let instance = COUNTER.fetch_add(1, Ordering::SeqCst);
        let root = env::temp_dir().join(format!("atlas-mcp-test-{}-{instance}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(root.join("app/src")).unwrap();
        fs::create_dir_all(root.join("run")).unwrap();
        fs::write(root.join("settings.gradle.kts"), "include(\":app\")").unwrap();
        fs::write(root.join("app/build.gradle.kts"), "plugins { id(\"dev.knshell.atlas\") }").unwrap();
        let sandbox = Self { root, instance };
        sandbox.declare_atlases(&[(":app", sandbox.binary())]);
        sandbox.write_gradlew();
        sandbox
    }

    fn binary(&self) -> PathBuf {
        self.root.join("atlas.kexe")
    }

    fn declare_atlases(&self, atlases: &[(&str, PathBuf)]) {
        let lines: String = atlases
            .iter()
            .map(|(module, executable)| {
                let description = json!({
                    "module": module,
                    "linkTask": format!("{module}:linkAtlas"),
                    "executable": executable,
                    "workingDir": self.root.join("app"),
                    "sourceDirs": [self.root.join("app/src")],
                    "projectDirs": [],
                });
                format!("KN_ATLAS {description}\n")
            })
            .collect();
        fs::write(self.root.join("atlases.txt"), lines).unwrap();
    }

    fn write_gradlew(&self) {
        let script = format!(
            "cd '{root}'\n\
             case \"$*\" in\n\
             *knAtlasDescribe*) echo run >> discoveries; cat atlases.txt; exit 0;;\n\
             esac\n\
             if [ -f fail-build ]; then echo 'e: /src/Broken.kt:3:7 Unresolved reference: nope'; echo 'BUILD FAILED' >&2; exit 1; fi\n\
             echo \"$*\" > built\n\
             cp '{fake}' atlas.kexe.new && mv atlas.kexe.new atlas.kexe\n",
            root = self.root.display(),
            fake = fake_atlas().display(),
        );
        fs::write(self.root.join("gradlew"), script).unwrap();
    }

    fn discoveries(&self) -> usize {
        fs::read_to_string(self.root.join("discoveries")).map_or(0, |log| log.lines().count())
    }

    fn install_atlas(&self) {
        fs::copy(fake_atlas(), self.binary()).expect("cannot install the fake atlas");
    }

    fn config(&self, mode: &str) -> Config {
        Config {
            root: self.root.clone(),
            runtime_dir: self.root.join("run"),
            cache_dir: self.root.join(".cache"),
            instance: self.instance,
            // Run through `sh`, so a freshly written script is never executed directly.
            gradle: Command {
                program: "sh".to_owned(),
                args: vec![self.root.join("gradlew").display().to_string()],
            },
            atlas_env: vec![("FAKE_ATLAS_MODE".to_owned(), mode.to_owned())],
            headless: false,
            graphical_session: true,
            ready_timeout: Duration::from_secs(5),
            request_timeout: Duration::from_secs(2),
        }
    }

    fn set_fail_build(&self, on: bool) {
        let marker = self.root.join("fail-build");
        if on {
            File::create(marker).unwrap();
        } else {
            let _ = fs::remove_file(marker);
        }
    }
}

impl Drop for Sandbox {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.root);
    }
}

fn fake_atlas() -> PathBuf {
    // cargo puts examples in the profile directory, which is an ancestor of the test binary.
    let exe = env::current_exe().unwrap();
    exe.ancestors()
        .map(|dir| dir.join("examples/fake_atlas"))
        .find(|path| path.is_file())
        .expect("the example is missing; run the tests through cargo test")
}

fn args(value: Value) -> Option<JsonObject> {
    Some(value.as_object().unwrap().clone())
}

fn as_json(result: &CallToolResult) -> Value {
    serde_json::to_value(result).unwrap()
}

fn all_text(value: &Value) -> String {
    value["content"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|block| block["text"].as_str())
        .collect::<Vec<_>>()
        .join("\n")
}

fn set_mtime(path: &Path, time: SystemTime) {
    File::options()
        .write(true)
        .open(path)
        .unwrap()
        .set_modified(time)
        .unwrap();
}

#[tokio::test]
async fn proxies_text_and_image_results() {
    let sandbox = Sandbox::new();
    sandbox.install_atlas();
    let supervisor = Supervisor::new(sandbox.config("normal"));

    let (tools, started) = supervisor.list_atlas_tools().await;
    assert!(started);
    let names: Vec<&str> = tools.iter().map(|tool| tool.name.as_ref()).collect();
    assert_eq!(names, ["text_tool", "image_tool", "fail_tool", "hang"]);

    let reply = supervisor.call_atlas("text_tool", args(json!({ "x": 1 }))).await;
    let value = as_json(&reply.result);
    assert_ne!(value["isError"], json!(true));
    assert!(all_text(&value).contains(r#"text ok: {"x":1}"#), "{value}");

    let reply = supervisor.call_atlas("image_tool", None).await;
    let value = as_json(&reply.result);
    assert_eq!(value["content"][0]["type"], "image");
    assert_eq!(value["content"][0]["mimeType"], "image/png");
    assert_eq!(value["content"][0]["data"].as_str().unwrap().len(), IMAGE_BYTES / 4 * 4);

    let logs = supervisor.logs(50).await;
    assert!(all_text(&as_json(&logs)).contains("--- atlas start"));
    supervisor.shutdown().await;
}

#[tokio::test]
async fn error_results_pass_through_as_is() {
    let sandbox = Sandbox::new();
    sandbox.install_atlas();
    let supervisor = Supervisor::new(sandbox.config("normal"));

    let reply = supervisor.call_atlas("fail_tool", None).await;
    let value = as_json(&reply.result);
    assert_eq!(value["isError"], json!(true));
    assert_eq!(all_text(&value), "no such story");
    supervisor.shutdown().await;
}

#[tokio::test]
async fn failed_start_reports_the_log_tail() {
    let sandbox = Sandbox::new();
    sandbox.install_atlas();
    let supervisor = Supervisor::new(sandbox.config("exit"));

    let (tools, started) = supervisor.list_atlas_tools().await;
    assert!(tools.is_empty());
    assert!(!started);

    let reply = supervisor.call_atlas("text_tool", None).await;
    let value = as_json(&reply.result);
    assert_eq!(value["isError"], json!(true));
    let text = all_text(&value);
    assert!(text.contains("exited before it was ready"), "{text}");
    assert!(text.contains("cannot load libgtk-4.so.1"), "{text}");

    let status = all_text(&as_json(&supervisor.status().await));
    assert!(status.contains("atlas: not running"), "{status}");
    supervisor.shutdown().await;
}

#[tokio::test]
async fn missing_binary_lists_no_atlas_tools() {
    let sandbox = Sandbox::new();
    let supervisor = Supervisor::new(sandbox.config("normal"));

    let (tools, started) = supervisor.list_atlas_tools().await;
    assert!(tools.is_empty() && !started);
    let status = all_text(&as_json(&supervisor.status().await));
    assert!(status.contains("(missing)"), "{status}");
    assert!(status.contains("last start error"), "{status}");
}

#[tokio::test]
async fn failed_build_keeps_the_old_atlas() {
    let sandbox = Sandbox::new();
    sandbox.install_atlas();
    let supervisor = Supervisor::new(sandbox.config("normal"));
    supervisor.list_atlas_tools().await;
    let pid = supervisor.pid().await.expect("atlas should be running");

    sandbox.set_fail_build(true);
    let reply = supervisor.reload().await;
    let value = as_json(&reply.result);
    assert_eq!(value["isError"], json!(true));
    let text = all_text(&value);
    assert!(
        text.contains("e: /src/Broken.kt:3:7 Unresolved reference: nope"),
        "{text}"
    );
    assert!(text.contains("previous atlas keeps running"), "{text}");
    assert_eq!(supervisor.pid().await, Some(pid));
    let reply = supervisor.call_atlas("text_tool", None).await;
    assert_ne!(as_json(&reply.result)["isError"], json!(true));

    sandbox.set_fail_build(false);
    let reply = supervisor.reload().await;
    assert!(reply.tools_changed);
    let value = as_json(&reply.result);
    assert_ne!(value["isError"], json!(true), "{value}");
    assert!(all_text(&value).contains("atlas restarted"));
    let built = fs::read_to_string(sandbox.root.join("built")).unwrap();
    assert!(built.contains(":app:linkAtlas"), "{built}");
    let new_pid = supervisor.pid().await.expect("atlas should be running after reload");
    assert_ne!(new_pid, pid);
    supervisor.shutdown().await;
}

#[tokio::test]
async fn stale_binary_gets_a_warning_until_rebuilt() {
    let sandbox = Sandbox::new();
    sandbox.install_atlas();
    let now = SystemTime::now();
    set_mtime(&sandbox.binary(), now - Duration::from_secs(24 * 3600));
    let source = sandbox.root.join("app/src/Preview.kt");
    fs::write(&source, "// edited").unwrap();
    set_mtime(&source, now - Duration::from_secs(600));
    let supervisor = Supervisor::new(sandbox.config("normal"));

    let reply = supervisor.call_atlas("text_tool", None).await;
    let text = all_text(&as_json(&reply.result));
    assert!(text.contains("older than its sources"), "{text}");
    assert!(text.contains("Preview.kt"), "{text}");

    let reply = supervisor.reload().await;
    assert_ne!(as_json(&reply.result)["isError"], json!(true));
    let reply = supervisor.call_atlas("text_tool", None).await;
    assert!(!all_text(&as_json(&reply.result)).contains("older than its sources"));
    supervisor.shutdown().await;
}

#[tokio::test]
async fn hung_request_times_out_and_the_next_call_restarts() {
    let sandbox = Sandbox::new();
    sandbox.install_atlas();
    let supervisor = Supervisor::new(sandbox.config("normal"));
    supervisor.list_atlas_tools().await;
    let pid = supervisor.pid().await.unwrap();

    let reply = supervisor.call_atlas("hang", None).await;
    let text = all_text(&as_json(&reply.result));
    assert!(text.contains("did not answer"), "{text}");
    assert_eq!(supervisor.pid().await, None);

    let reply = supervisor.call_atlas("text_tool", None).await;
    assert_ne!(as_json(&reply.result)["isError"], json!(true));
    assert_ne!(supervisor.pid().await, Some(pid));
    supervisor.shutdown().await;
}

#[tokio::test]
async fn shutdown_kills_the_atlas_and_removes_its_files() {
    let sandbox = Sandbox::new();
    sandbox.install_atlas();
    let config = sandbox.config("normal");
    let socket = config.socket_path();
    let log = config.log_path();
    let supervisor = Supervisor::new(config);
    supervisor.list_atlas_tools().await;
    let pid = supervisor.pid().await.unwrap();
    assert!(socket.exists() && log.exists());

    supervisor.shutdown().await;
    assert!(!socket.exists(), "socket left behind");
    assert!(!log.exists(), "log left behind");
    let alive = unsafe { libc::kill(pid as i32, 0) } == 0;
    assert!(!alive, "atlas process {pid} is still alive");
}

#[test]
fn daemon_tools_win_over_atlas_tools_with_the_same_name() {
    let empty = || Arc::new(JsonObject::new());
    let atlas = vec![
        Tool::new("status", "clash", empty()),
        Tool::new("click", "click a node", empty()),
    ];
    let names: Vec<String> = merge_tools(own_tools(), atlas)
        .into_iter()
        .map(|tool| tool.name.to_string())
        .collect();
    assert_eq!(names, ["reload", "status", "logs", "stop", "set_visible", "list_atlases", "use_atlas", "click"]);
}

#[tokio::test]
async fn gradle_is_left_alone_in_a_project_that_mentions_no_atlas() {
    let sandbox = Sandbox::new();
    fs::write(sandbox.root.join("app/build.gradle.kts"), "plugins { kotlin(\"jvm\") }").unwrap();
    let supervisor = Supervisor::new(sandbox.config("normal"));

    let (tools, started) = supervisor.list_atlas_tools().await;

    assert!(tools.is_empty() && !started);
    assert_eq!(sandbox.discoveries(), 0);
}

#[tokio::test]
async fn the_discovery_is_cached_until_a_build_script_changes() {
    let sandbox = Sandbox::new();
    sandbox.install_atlas();
    for _ in 0..2 {
        let supervisor = Supervisor::new(sandbox.config("normal"));
        assert!(!supervisor.list_atlas_tools().await.0.is_empty());
        supervisor.shutdown().await;
    }
    assert_eq!(sandbox.discoveries(), 1);

    set_mtime(&sandbox.root.join("app/build.gradle.kts"), SystemTime::now() + Duration::from_secs(60));
    let supervisor = Supervisor::new(sandbox.config("normal"));
    supervisor.list_atlas_tools().await;
    assert_eq!(sandbox.discoveries(), 2);
    supervisor.shutdown().await;
}

#[tokio::test]
async fn several_atlases_need_a_choice() {
    let sandbox = Sandbox::new();
    sandbox.install_atlas();
    sandbox.declare_atlases(&[(":app", sandbox.binary()), (":kit", sandbox.binary())]);
    let supervisor = Supervisor::new(sandbox.config("normal"));

    assert!(supervisor.list_atlas_tools().await.0.is_empty());
    let reply = supervisor.call_atlas("text_tool", None).await;
    let text = all_text(&as_json(&reply.result));
    assert!(text.contains("use_atlas") && text.contains(":app, :kit"), "{text}");

    let reply = supervisor.use_atlas(":nope").await;
    assert_eq!(as_json(&reply.result)["isError"], json!(true));

    let reply = supervisor.use_atlas("kit").await;
    assert!(reply.tools_changed);
    assert_ne!(as_json(&reply.result)["isError"], json!(true));
    assert!(!supervisor.list_atlas_tools().await.0.is_empty());
    let listed = all_text(&as_json(&supervisor.list_atlases().await.result));
    assert!(listed.contains("* :kit"), "{listed}");
    supervisor.shutdown().await;
}

#[tokio::test]
async fn a_project_without_an_atlas_says_how_to_add_one() {
    let sandbox = Sandbox::new();
    sandbox.declare_atlases(&[]);
    let supervisor = Supervisor::new(sandbox.config("normal"));

    let reply = supervisor.call_atlas("text_tool", None).await;
    let value = as_json(&reply.result);

    assert_eq!(value["isError"], json!(true));
    assert!(all_text(&value).contains("dev.knshell.atlas"), "{value}");
}

#[test]
fn the_build_root_is_found_from_inside_a_module() {
    let sandbox = Sandbox::new();

    assert_eq!(find_gradle_root(&sandbox.root.join("app/src")), Some(sandbox.root.clone()));
}

#[tokio::test]
async fn a_directory_outside_gradle_is_explained() {
    let sandbox = Sandbox::new();
    fs::remove_file(sandbox.root.join("settings.gradle.kts")).unwrap();
    let supervisor = Supervisor::new(sandbox.config("normal"));

    let reply = supervisor.list_atlases().await;
    let text = all_text(&as_json(&reply.result));

    assert!(text.contains("not inside a Gradle project"), "{text}");
    assert_eq!(sandbox.discoveries(), 0);
}

#[tokio::test]
async fn the_atlas_is_hidden_until_asked_to_show_itself() {
    let sandbox = Sandbox::new();
    sandbox.install_atlas();
    let config = sandbox.config("normal");
    let hidden_display = format!("WAYLAND_DISPLAY={} GDK_BACKEND=wayland", config.wayland_name());
    let supervisor = Supervisor::new(config);
    let env_of_atlas = || async { all_text(&as_json(&supervisor.call_atlas("env_tool", None).await.result)) };

    assert!(env_of_atlas().await.contains(&hidden_display));
    let hidden_pid = supervisor.pid().await.unwrap();

    let reply = supervisor.set_visible(true).await;
    assert!(reply.tools_changed);
    assert_ne!(as_json(&reply.result)["isError"], json!(true));
    assert_ne!(supervisor.pid().await, Some(hidden_pid));
    let visible_env = env_of_atlas().await;
    assert!(!visible_env.contains(&hidden_display), "{visible_env}");
    assert!(all_text(&as_json(&supervisor.status().await)).contains("window: visible"));

    let visible_pid = supervisor.pid().await.unwrap();
    let reply = supervisor.set_visible(true).await;
    assert!(all_text(&as_json(&reply.result)).contains("already visible"));
    assert_eq!(supervisor.pid().await, Some(visible_pid));

    supervisor.set_visible(false).await;
    assert!(env_of_atlas().await.contains(&hidden_display));
    supervisor.shutdown().await;
}

#[tokio::test]
async fn closing_the_visible_window_goes_back_to_hidden() {
    let sandbox = Sandbox::new();
    sandbox.install_atlas();
    let supervisor = Supervisor::new(sandbox.config("normal"));
    supervisor.set_visible(true).await;

    supervisor.call_atlas("close_window", None).await;
    for _ in 0..100 {
        if supervisor.pid().await.is_none() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    let reply = supervisor.call_atlas("env_tool", None).await;
    let text = all_text(&as_json(&reply.result));

    assert!(text.contains("window was closed"), "{text}");
    assert!(text.contains("GDK_BACKEND=wayland"), "{text}");
    assert!(all_text(&as_json(&supervisor.status().await)).contains("window: hidden"));
    supervisor.shutdown().await;
}

#[tokio::test]
async fn showing_the_atlas_needs_a_graphical_session() {
    let sandbox = Sandbox::new();
    sandbox.install_atlas();
    let mut config = sandbox.config("normal");
    config.graphical_session = false;
    let supervisor = Supervisor::new(config);

    let reply = supervisor.set_visible(true).await;
    let value = as_json(&reply.result);

    assert_eq!(value["isError"], json!(true));
    assert!(all_text(&value).contains("no graphical session"), "{value}");
    assert_eq!(supervisor.pid().await, None);
}
