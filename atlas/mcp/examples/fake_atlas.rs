//! Stand-in for `atlas.kexe` used by the daemon tests. It serves the inspect protocol on the socket
//! given with `--inspect=<path>`. `FAKE_ATLAS_MODE` changes its behaviour:
//! - unset: normal service
//! - `exit`: print a message to stderr and exit at once
//! - `silent`: never bind the socket

use std::io::{BufRead, BufReader, Write};
use std::sync::Mutex;
use std::os::unix::net::{UnixListener, UnixStream};
use std::thread;
use std::time::Duration;

use serde_json::{json, Value};

/// Size of the image payload returned by `image_tool`; large enough to exercise long lines.
const IMAGE_BYTES: usize = 3 * 1024 * 1024;

fn main() {
    let socket = std::env::args()
        .find_map(|arg| arg.strip_prefix("--inspect=").map(str::to_owned))
        .expect("--inspect=<path> is required");
    match std::env::var("FAKE_ATLAS_MODE").unwrap_or_default().as_str() {
        "exit" => {
            eprintln!("fake atlas: cannot load libgtk-4.so.1");
            std::process::exit(3);
        }
        "silent" => loop {
            thread::sleep(Duration::from_secs(3600));
        },
        _ => {}
    }
    let _ = std::fs::remove_file(&socket);
    let listener = UnixListener::bind(&socket).expect("cannot bind the inspect socket");
    eprintln!("fake atlas: listening on {socket}");
    for stream in listener.incoming().flatten() {
        thread::spawn(move || serve(stream));
    }
}

fn serve(stream: UnixStream) {
    let mut writer = stream.try_clone().expect("cannot clone the stream");
    for line in BufReader::new(stream).lines() {
        let Ok(line) = line else { return };
        let Ok(request) = serde_json::from_str::<Value>(&line) else {
            return;
        };
        let id = request["id"].clone();
        let response = match request["method"].as_str().unwrap_or_default() {
            "tools/list" => Some(json!({ "id": id, "result": { "tools": tools() } })),
            "tools/call" => call(&request, id),
            _ => Some(json!({ "id": id, "error": { "message": "unknown method" } })),
        };
        let Some(response) = response else { continue };
        let mut out = response.to_string();
        out.push('\n');
        if writer.write_all(out.as_bytes()).is_err() {
            return;
        }
    }
}

fn tools() -> Value {
    let empty = json!({ "type": "object", "properties": {} });
    json!([
        { "name": "text_tool", "description": "Echoes its arguments.", "inputSchema": empty },
        { "name": "image_tool", "description": "Returns a big PNG.", "inputSchema": empty },
        { "name": "fail_tool", "description": "Always reports an error result.", "inputSchema": empty },
        { "name": "hang", "description": "Never answers.", "inputSchema": empty },
        { "name": "list_stories", "description": "Stories of the fake catalog.", "inputSchema": empty },
        { "name": "select_story", "description": "Selects a story.", "inputSchema": empty },
        { "name": "set_view", "description": "Sets the matrix view.", "inputSchema": empty },
        { "name": "get_args", "description": "Args of the selected story.", "inputSchema": empty },
        { "name": "set_args", "description": "Sets arg values.", "inputSchema": empty },
    ])
}

/// Stories the fake atlas knows: `(name, kind)`. Only `Matrix/Grid` has views.
const STORIES: [(&str, &str); 2] = [("Button/Playground", "story"), ("Matrix/Grid", "variants")];
const VIEWS: [&str; 2] = ["compose", "table"];

/// What the user has open: it lives as long as this atlas process, so a restarted atlas starts empty.
struct Session {
    story: Option<&'static str>,
    view: &'static str,
    text: String,
    radius: i64,
}

static SESSION: Mutex<Option<Session>> = Mutex::new(None);

fn with_session<R>(f: impl FnOnce(&mut Session) -> R) -> R {
    let mut guard = SESSION.lock().unwrap();
    let session = guard.get_or_insert_with(|| Session {
        story: None,
        view: VIEWS[0],
        text: "Save".to_owned(),
        radius: 8,
    });
    f(session)
}

fn kind_of(story: &str) -> &'static str {
    STORIES.iter().find(|(name, _)| *name == story).map_or("story", |(_, kind)| *kind)
}

/// The same shape as the real atlas's `get_args` answer.
fn args_report(session: &Session) -> Value {
    json!({
        "selected": session.story,
        "args": [
            { "name": "text", "type": "string", "value": session.text },
            { "name": "radius", "type": "int", "value": session.radius },
        ],
    })
}

fn stateful(name: &str, arguments: &Value) -> Result<Value, String> {
    match name {
        "list_stories" => with_session(|session| {
            Ok(json!(STORIES
                .iter()
                .map(|(story, kind)| {
                    let mut entry = json!({
                        "name": story,
                        "kind": kind,
                        "selected": session.story == Some(*story),
                    });
                    if *kind == "variants" {
                        entry["view"] = json!(session.view);
                        entry["views"] = json!(VIEWS);
                    }
                    entry
                })
                .collect::<Vec<_>>()))
        }),
        "select_story" => {
            let wanted = arguments["story"].as_str().unwrap_or_default();
            let Some((story, _)) = STORIES.iter().find(|(name, _)| *name == wanted) else {
                return Err(format!("unknown story \"{wanted}\""));
            };
            with_session(|session| {
                session.story = Some(*story);
                Ok(args_report(session))
            })
        }
        "set_view" => {
            let wanted = arguments["view"].as_str().unwrap_or_default();
            with_session(|session| {
                let Some(story) = session.story else {
                    return Err("no story is selected".to_owned());
                };
                if kind_of(story) != "variants" {
                    return Err(format!("\"{story}\" is not a matrix"));
                }
                let Some(view) = VIEWS.iter().find(|view| **view == wanted) else {
                    return Err(format!("unknown view \"{wanted}\""));
                };
                session.view = *view;
                Ok(json!({ "selected": story, "view": view }))
            })
        }
        "get_args" => with_session(|session| match session.story {
            Some(_) => Ok(args_report(session)),
            None => Err("no story is selected".to_owned()),
        }),
        "set_args" => {
            let values = arguments["values"].as_object().cloned().unwrap_or_default();
            with_session(|session| {
                if session.story.is_none() {
                    return Err("no story is selected".to_owned());
                }
                // All-or-nothing, like the real atlas: nothing changes unless every value is valid.
                let mut text = session.text.clone();
                let mut radius = session.radius;
                for (key, value) in &values {
                    match key.as_str() {
                        "text" => text = value.as_str().ok_or("arg \"text\" expects a string")?.to_owned(),
                        "radius" => {
                            radius = value
                                .as_i64()
                                .filter(|r| (0..=32).contains(r))
                                .ok_or("arg \"radius\" expects an integer in 0..32")?;
                        }
                        other => return Err(format!("unknown arg \"{other}\"")),
                    }
                }
                session.text = text;
                session.radius = radius;
                Ok(args_report(session))
            })
        }
        _ => Err(format!("unknown tool {name}")),
    }
}

fn call(request: &Value, id: Value) -> Option<Value> {
    let name = request["params"]["name"].as_str().unwrap_or_default();
    let arguments = &request["params"]["arguments"];
    let result = match name {
        "text_tool" => {
            json!({ "content": [{ "type": "text", "text": format!("text ok: {arguments}") }], "isError": false })
        }
        "image_tool" => json!({
            "content": [{ "type": "image", "data": "QUJD".repeat(IMAGE_BYTES / 4), "mimeType": "image/png" }],
            "isError": false
        }),
        "fail_tool" => json!({ "content": [{ "type": "text", "text": "no such story" }], "isError": true }),
        "env_tool" => {
            let variable = |name: &str| std::env::var(name).unwrap_or_default();
            let text = format!(
                "WAYLAND_DISPLAY={} GDK_BACKEND={}",
                variable("WAYLAND_DISPLAY"),
                variable("GDK_BACKEND")
            );
            json!({ "content": [{ "type": "text", "text": text }], "isError": false })
        }
        // Stands for the user closing the window: the process ends cleanly shortly after answering.
        "close_window" => {
            thread::spawn(|| {
                thread::sleep(Duration::from_millis(100));
                std::process::exit(0);
            });
            json!({ "content": [{ "type": "text", "text": "closing" }], "isError": false })
        }
        "hang" => {
            thread::sleep(Duration::from_secs(10));
            return None;
        }
        "list_stories" | "select_story" | "set_view" | "get_args" | "set_args" => {
            match stateful(name, arguments) {
                Ok(value) => json!({ "content": [{ "type": "text", "text": value.to_string() }], "isError": false }),
                Err(message) => json!({ "content": [{ "type": "text", "text": message }], "isError": true }),
            }
        }
        other => return Some(json!({ "id": id, "error": { "message": format!("unknown tool {other}") } })),
    };
    Some(json!({ "id": id, "result": result }))
}
