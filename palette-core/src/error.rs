use thiserror::Error;

/// Errors produced by palette-core.
#[derive(Debug, Error)]
pub enum PaletteError {
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),

    #[error("decode error: {0}")]
    Decode(String),

    #[error("unsupported format: {0}")]
    UnsupportedFormat(String),

    #[error("empty image")]
    EmptyImage,

    #[error("internal error: {0}")]
    Internal(String),
}

/// Internal-only error variant used to signal cancellation from the async path.
/// It is never returned to the public `extract_from_*` API (which never takes a
/// cancellation token), only short-circuits the FFI async worker.
pub(crate) const CANCELLED: &str = "cancelled";

pub(crate) fn cancelled() -> PaletteError {
    PaletteError::Internal(CANCELLED.to_string())
}
