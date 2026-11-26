//! MCP daemon for the Atlas component catalog. The binary in `main.rs` speaks MCP over stdio; this
//! library holds the pieces so they can be tested without an MCP client.

pub mod atlas;
pub mod build;
pub mod config;
pub mod discover;
pub mod doctor;
pub mod server;
