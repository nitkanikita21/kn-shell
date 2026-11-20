use thiserror::Error;

/// Errors produced by palette-core.
///
/// Over the C ABI each variant maps to an `i32` status code (listed on
/// [`crate::ffi::palette_extract_sync`]); the [`std::fmt::Display`] text is
/// intended for humans, not for parsing.
#[derive(Debug, Error)]
pub enum PaletteError {
    /// Reading the source file or reading/writing the cache file failed.
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),

    /// The bytes matched a supported format but the decoder rejected them
    /// (truncated, corrupt, invalid ICC profile, ...). The payload is the
    /// decoder's message.
    #[error("decode error: {0}")]
    Decode(String),

    /// The magic bytes did not match JPEG, PNG or WebP. The payload names what
    /// was seen instead.
    #[error("unsupported format: {0}")]
    UnsupportedFormat(String),

    /// The decoded image reports zero width or height, so there is nothing to
    /// cluster.
    #[error("empty image")]
    EmptyImage,

    /// An internal invariant failed: a malformed cache blob, or the internal
    /// cancellation signal. Cache *write* failures surface as [`PaletteError::Io`]
    /// instead. The public `extract_from_*` API never returns this; the async C
    /// ABI turns it into the literal error string `"cancelled"`.
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
