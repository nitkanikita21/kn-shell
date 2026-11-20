//! Optional bridge that plugs palette-core into the rust-core `custom` extension
//! host. Behind the `custom-host` feature so the core engine never depends on
//! `kn_shell_core`.
//!
//! Registering publishes each palette color as a `CustomEntry`
//! (`extension = "palette"`, `kind = "color"`), which the Kotlin side receives
//! through `CustomListener` — keeping all extraction logic native while the UI
//! stays in Compose.

use std::path::PathBuf;
use std::sync::Arc;

use kn_shell_core::custom::{register_extension, CustomEntry, CustomExtension};

use crate::{extract_from_path, PaletteOptions};

/// A `CustomExtension` that, on each poll, extracts a palette from a fixed
/// wallpaper path and exposes its colors.
pub struct PaletteExtension {
    wallpaper: PathBuf,
    options: PaletteOptions,
}

impl PaletteExtension {
    /// Create an extension that extracts from `wallpaper` using
    /// [`PaletteOptions::default`]. Nothing is registered yet; use [`Self::register`]
    /// or register the instance yourself with the custom host.
    pub fn new(wallpaper: impl Into<PathBuf>) -> Self {
        Self {
            wallpaper: wallpaper.into(),
            options: PaletteOptions::default(),
        }
    }

    /// Build and immediately register with the custom host.
    pub fn register(wallpaper: impl Into<PathBuf>) {
        register_extension(Arc::new(Self::new(wallpaper)));
    }
}

impl CustomExtension for PaletteExtension {
    fn id(&self) -> String {
        "palette".to_string()
    }

    fn poll(&self) -> Vec<CustomEntry> {
        match extract_from_path(&self.wallpaper, self.options.clone()) {
            Ok(palette) => palette
                .colors
                .iter()
                .enumerate()
                .map(|(i, c)| CustomEntry {
                    extension: "palette".to_string(),
                    key: format!("color{i}"),
                    // l,c,h,weight — NaN hue serializes as the literal "null".
                    value: format!(
                        "{},{},{},{}",
                        c.l,
                        c.c,
                        if c.h.is_nan() {
                            "null".to_string()
                        } else {
                            c.h.to_string()
                        },
                        c.weight
                    ),
                    kind: "color".to_string(),
                })
                .collect(),
            Err(_) => Vec::new(),
        }
    }

    fn invoke(&self, command: &str) -> String {
        match command {
            "json" => match extract_from_path(&self.wallpaper, self.options.clone()) {
                Ok(p) => serde_json::to_string(&p).unwrap_or_else(|_| "{}".to_string()),
                Err(e) => format!("{{\"error\":\"{e}\"}}"),
            },
            _ => "{\"error\":\"unknown command\"}".to_string(),
        }
    }
}
