//! Stand-in for `atlas.kexe` used by the daemon tests. It serves the inspect protocol on the socket
//! given with `--inspect=<path>`. `FAKE_ATLAS_MODE` changes its behaviour:
//! - unset: normal service
//! - `exit`: print a message to stderr and exit at once
//! - `silent`: never bind the socket

use std::io::{BufRead, BufReader, Write};
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
    ])
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
        other => return Some(json!({ "id": id, "error": { "message": format!("unknown tool {other}") } })),
    };
    Some(json!({ "id": id, "result": result }))
}
