//! Custom extension host — the "custom" part of the Rust core.
//!
//! This is the framework hook that lets a shell author plug native-speed logic
//! into the core without touching the Kotlin/Compose UI. An extension is any
//! type implementing [`CustomExtension`]; it is registered with
//! [`register_extension`] and the core ticks every registered extension on a
//! fixed interval, broadcasting the aggregated [`CustomEntry`] values to Kotlin
//! listeners. The UI only has to implement `CustomListener` and call
//! `subscribe_custom`.
//!
//! Everything here follows the same service pattern as the built-in modules
//! (records + `callback_interface` listener + `OnceLock` registry + lazy
//! `ensure_started`), only generalised: the *what* is supplied by the extension,
//! the *when* and *how it reaches the UI* is owned by the core.
//!
//! # Writing your own extension
//!
//! A shell author implements [`CustomExtension`] in Rust and registers it.
//! Because `kn-shell-core` is linked into the shell binary as a static lib, the
//! extension lives in Rust — either as an extra module compiled into this crate
//! or in a crate that `kn-shell-core` depends on. Either way the registration
//! call runs before (or after) the UI subscribes:
//!
//! ```rust,ignore
//! use kn_shell_core::custom::{register_extension, CustomEntry, CustomExtension};
//! use std::sync::Arc;
//!
//! struct Clock;
//! impl CustomExtension for Clock {
//!     fn id(&self) -> String { "clock".into() }
//!     fn poll(&self) -> Vec<CustomEntry> {
//!         vec![CustomEntry {
//!             extension: "clock".into(),
//!             key: "now".into(),
//!             value: "12:00".into(),
//!             kind: "text".into(),
//!         }]
//!     }
//! }
//! register_extension(Arc::new(Clock));
//! ```

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::Duration;

// ---------------------------------------------------------------------------
// Data exposed to Kotlin
// ---------------------------------------------------------------------------

/// One named value produced by a custom extension.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CustomEntry {
    /// Id of the originating extension (e.g. `"weather"`).
    pub extension: String,
    /// Label/key within the extension (e.g. `"temp"`).
    pub key: String,
    /// Rendered value (already formatted by the extension).
    pub value: String,
    /// UI hint: `"text"` | `"icon"` | `"progress"` | `"bytes"`. Reserved; the
    /// UI may ignore it. Kept as a string so new kinds need no binding changes.
    pub kind: String,
}

// ---------------------------------------------------------------------------
// Extension point — implemented by the framework's dev-user, in Rust
// ---------------------------------------------------------------------------

/// Implement this and register it with [`register_extension`] to add native
/// logic to your shell.
pub trait CustomExtension: Send + Sync {
    /// Stable identifier. Used as the `extension` field and to dedupe/remove.
    fn id(&self) -> String;

    /// Produce the current values. Called on every tick; keep it cheap — it runs
    /// on the core thread, not the UI thread.
    fn poll(&self) -> Vec<CustomEntry>;

    /// Optional command entry point. The UI may call `custom_invoke(id, cmd)` to
    /// drive the extension; the default implementation does nothing.
    fn invoke(&self, _command: &str) -> String {
        String::new()
    }
}

// ---------------------------------------------------------------------------
// Kotlin-side listener
// ---------------------------------------------------------------------------

#[uniffi::export(callback_interface)]
pub trait CustomListener: Send + Sync {
    fn on_custom(&self, entries: Vec<CustomEntry>);
}

// ---------------------------------------------------------------------------
// Registries
// ---------------------------------------------------------------------------

static EXTENSIONS: OnceLock<Mutex<Vec<Arc<dyn CustomExtension>>>> = OnceLock::new();
static LISTENERS: OnceLock<Mutex<Vec<Arc<dyn CustomListener>>>> = OnceLock::new();
static INTERVAL_MS: AtomicU64 = AtomicU64::new(1000);
static STARTED: OnceLock<()> = OnceLock::new();

fn extensions() -> &'static Mutex<Vec<Arc<dyn CustomExtension>>> {
    EXTENSIONS.get_or_init(|| Mutex::new(Vec::new()))
}

fn listeners() -> &'static Mutex<Vec<Arc<dyn CustomListener>>> {
    LISTENERS.get_or_init(|| Mutex::new(Vec::new()))
}

/// Registers a custom extension. If an extension with the same `id` is already
/// registered it is replaced. Safe to call before or after the core starts.
pub fn register_extension(ext: Arc<dyn CustomExtension>) {
    let id = ext.id();
    let mut guard = extensions().lock().unwrap();
    if let Some(slot) = guard.iter_mut().find(|e| e.id() == id) {
        *slot = ext;
    } else {
        guard.push(ext);
    }
}

fn broadcast_custom(entries: Vec<CustomEntry>) {
    if entries.is_empty() {
        return;
    }
    let listeners: Vec<Arc<dyn CustomListener>> = listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_custom(entries.clone());
    }
}


// ---------------------------------------------------------------------------
// Core loop
// ---------------------------------------------------------------------------

fn tick() {
    let exts: Vec<Arc<dyn CustomExtension>> = extensions().lock().unwrap().clone();
    if exts.is_empty() {
        return;
    }
    let mut entries = Vec::new();
    for ext in exts {
        entries.extend(ext.poll());
    }
    broadcast_custom(entries);
}

/// Starts the core tick loop. Idempotent: the worker thread is spawned once,
/// on the first call (directly or via `subscribe_custom`).
pub fn ensure_started() {
    STARTED.get_or_init(|| {
        // Built-in demo extension (opt-in) so the loop is observable end-to-end
        // without writing any Rust. Enable with `--features demo-extensions`.
        #[cfg(feature = "demo-extensions")]
        register_demo();

        thread::Builder::new()
            .name("kn-custom".into())
            .spawn(run)
            .expect("failed to spawn custom thread");
    });
}

fn run() {
    loop {
        tick();
        let ms = INTERVAL_MS.load(Ordering::Relaxed).max(50);
        thread::sleep(Duration::from_millis(ms));
    }
}

// ---------------------------------------------------------------------------
// UniFFI exports (Kotlin side)
// ---------------------------------------------------------------------------

/// Subscribes a Kotlin listener to aggregated custom-extension values and starts
/// the core tick loop on first subscription.
#[uniffi::export]
pub fn subscribe_custom(listener: Box<dyn CustomListener>) {
    listeners().lock().unwrap().push(Arc::from(listener));
    ensure_started();
}

/// Sets the poll interval in milliseconds (minimum 50 ms).
#[uniffi::export]
pub fn custom_set_interval(ms: u64) {
    INTERVAL_MS.store(ms.max(50), Ordering::Relaxed);
}

/// Forces one immediate poll + broadcast, independent of the interval.
#[uniffi::export]
pub fn custom_refresh() {
    tick();
}

/// Lists the ids of all currently registered extensions.
#[uniffi::export]
pub fn custom_list_extensions() -> Vec<String> {
    extensions().lock().unwrap().iter().map(|e| e.id()).collect()
}

/// Removes a registered extension by id. Returns `true` if something was removed.
#[uniffi::export]
pub fn custom_unregister(id: String) -> bool {
    let mut guard = extensions().lock().unwrap();
    let before = guard.len();
    guard.retain(|e| e.id() != id);
    guard.len() != before
}

/// Invokes a command on a registered extension and returns its response.
/// Empty string when the extension or command is unknown.
#[uniffi::export]
pub fn custom_invoke(id: String, command: String) -> String {
    let exts: Vec<Arc<dyn CustomExtension>> = extensions().lock().unwrap().clone();
    for ext in exts {
        if ext.id() == id {
            return ext.invoke(&command);
        }
    }
    String::new()
}

// ---------------------------------------------------------------------------
// Opt-in demo extension (feature `demo-extensions`)
// ---------------------------------------------------------------------------

#[cfg(feature = "demo-extensions")]
fn register_demo() {
    struct Demo;
    impl CustomExtension for Demo {
        fn id(&self) -> String {
            "demo".into()
        }
        fn poll(&self) -> Vec<CustomEntry> {
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0);
            vec![CustomEntry {
                extension: "demo".into(),
                key: "heartbeat".into(),
                value: now.to_string(),
                kind: "text".into(),
            }]
        }
        fn invoke(&self, command: &str) -> String {
            format!("demo received: {command}")
        }
    }
    register_extension(Arc::new(Demo));
}
