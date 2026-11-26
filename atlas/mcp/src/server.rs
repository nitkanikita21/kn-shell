//! The MCP face of the daemon: tool listing and dispatch over rmcp. The logic lives in
//! [`Supervisor`]; this layer only merges tool lists and turns replies into MCP results.

use std::sync::Arc;

use rmcp::model::{
    CallToolRequestParams, CallToolResponse, CallToolResult, ContentBlock, Implementation, JsonObject, ListToolsResult,
    PaginatedRequestParams, ServerCapabilities, ServerConfig, Tool,
};
use rmcp::service::{RequestContext, RoleServer};
use rmcp::{ErrorData as McpError, ServerHandler};
use serde_json::{json, Value};

use crate::atlas::{Reply, Supervisor};

/// Lines returned by `logs` when the caller does not say.
const DEFAULT_LOG_LINES: usize = 50;

#[derive(Clone)]
pub struct Daemon {
    supervisor: Arc<Supervisor>,
}

impl Daemon {
    pub fn new(supervisor: Arc<Supervisor>) -> Self {
        Self { supervisor }
    }
}

impl ServerHandler for Daemon {
    fn get_info(&self) -> ServerConfig {
        let capabilities = ServerCapabilities::builder()
            .enable_tools()
            .enable_tool_list_changed()
            .build();
        ServerConfig::new(capabilities)
            .with_server_info(Implementation::new("atlas-mcp", env!("CARGO_PKG_VERSION")))
            .with_instructions(
                "Drives the Atlas component catalog of the current Gradle project on a private headless display. \
                 Atlas tools come from the atlas itself and appear when the project has one; reload rebuilds it, \
                 status and logs describe it, list_atlases and use_atlas choose between several.",
            )
    }

    async fn list_tools(
        &self,
        _request: Option<PaginatedRequestParams>,
        context: RequestContext<RoleServer>,
    ) -> Result<ListToolsResult, McpError> {
        let (atlas_tools, started) = self.supervisor.list_atlas_tools().await;
        if started {
            notify_tools_changed(&context).await;
        }
        Ok(ListToolsResult::with_all_items(merge_tools(own_tools(), atlas_tools)))
    }

    async fn call_tool(
        &self,
        request: CallToolRequestParams,
        context: RequestContext<RoleServer>,
    ) -> Result<CallToolResponse, McpError> {
        let reply = match request.name.as_ref() {
            "reload" => self.supervisor.reload().await,
            "status" => Reply {
                result: self.supervisor.status().await,
                tools_changed: false,
            },
            "logs" => match parse_lines(request.arguments.as_ref()) {
                Ok(lines) => Reply {
                    result: self.supervisor.logs(lines).await,
                    tools_changed: false,
                },
                Err(message) => Reply {
                    result: CallToolResult::error(vec![ContentBlock::text(message)]),
                    tools_changed: false,
                },
            },
            "stop" => self.supervisor.stop().await,
            "list_atlases" => self.supervisor.list_atlases().await,
            "set_visible" => match parse_visible(request.arguments.as_ref()) {
                Ok(visible) => self.supervisor.set_visible(visible).await,
                Err(message) => Reply {
                    result: CallToolResult::error(vec![ContentBlock::text(message)]),
                    tools_changed: false,
                },
            },
            "use_atlas" => match parse_module(request.arguments.as_ref()) {
                Ok(module) => self.supervisor.use_atlas(&module).await,
                Err(message) => Reply {
                    result: CallToolResult::error(vec![ContentBlock::text(message)]),
                    tools_changed: false,
                },
            },
            name => self.supervisor.call_atlas(name, request.arguments).await,
        };
        if reply.tools_changed {
            notify_tools_changed(&context).await;
        }
        Ok(CallToolResponse::from(reply.result))
    }
}

async fn notify_tools_changed(context: &RequestContext<RoleServer>) {
    if let Err(error) = context.peer.notify_tool_list_changed().await {
        eprintln!("[atlas-mcp] cannot send tools/list_changed: {error}");
    }
}

/// The daemon's own tools, listed before the atlas tools.
pub fn own_tools() -> Vec<Tool> {
    vec![
        Tool::new(
            "reload",
            "Build the atlas with Gradle and restart it. The story, matrix view and arg values are reopened in the \
             new atlas. On a compile error the reply lists the e: lines and the running atlas stays up.",
            schema(json!({ "type": "object", "properties": {} })),
        ),
        Tool::new(
            "status",
            "Show whether the atlas is running, its pid, file paths, tool count and whether its binary is older than \
             its sources.",
            schema(json!({ "type": "object", "properties": {} })),
        ),
        Tool::new(
            "logs",
            "Show the last lines of the atlas stdout and stderr.",
            schema(json!({
                "type": "object",
                "properties": { "lines": { "type": "integer", "minimum": 1, "description": "How many lines, default 50." } }
            })),
        ),
        Tool::new(
            "stop",
            "Stop the atlas. The next tool call starts it again.",
            schema(json!({ "type": "object", "properties": {} })),
        ),
        Tool::new(
            "set_visible",
            "Show the atlas window on the user's desktop, or hide it again (the default). Restarts the atlas; its \
             story, matrix view and arg values are reopened in it. Show it only when the user asks to see it.",
            schema(json!({
                "type": "object",
                "properties": { "visible": { "type": "boolean", "description": "true to show the window" } },
                "required": ["visible"]
            })),
        ),
        Tool::new(
            "list_atlases",
            "Ask Gradle which modules of this project have an atlas; the one in use is marked with *.",
            schema(json!({ "type": "object", "properties": {} })),
        ),
        Tool::new(
            "use_atlas",
            "Switch to the atlas of another module. Only needed when the project has several.",
            schema(json!({
                "type": "object",
                "properties": { "module": { "type": "string", "description": "Gradle path, for example :app" } },
                "required": ["module"]
            })),
        ),
    ]
}

/// Daemon tools first; an atlas tool with the same name as one of them is dropped.
pub fn merge_tools(own: Vec<Tool>, atlas: Vec<Tool>) -> Vec<Tool> {
    let mut merged = own;
    for tool in atlas {
        if merged.iter().any(|existing| existing.name == tool.name) {
            eprintln!(
                "[atlas-mcp] atlas tool {} clashes with a daemon tool and is hidden",
                tool.name
            );
        } else {
            merged.push(tool);
        }
    }
    merged
}

fn schema(value: Value) -> Arc<JsonObject> {
    Arc::new(value.as_object().cloned().unwrap_or_default())
}

fn parse_visible(arguments: Option<&JsonObject>) -> Result<bool, String> {
    arguments
        .and_then(|args| args.get("visible"))
        .and_then(Value::as_bool)
        .ok_or_else(|| "visible is required: true or false".to_owned())
}

fn parse_module(arguments: Option<&JsonObject>) -> Result<String, String> {
    arguments
        .and_then(|args| args.get("module"))
        .and_then(Value::as_str)
        .map(str::to_owned)
        .ok_or_else(|| "module is required: a Gradle path such as :app".to_owned())
}

fn parse_lines(arguments: Option<&JsonObject>) -> Result<usize, String> {
    match arguments.and_then(|args| args.get("lines")) {
        None => Ok(DEFAULT_LOG_LINES),
        Some(value) => value
            .as_u64()
            .filter(|lines| *lines >= 1)
            .map(|lines| lines as usize)
            .ok_or_else(|| format!("lines must be a positive integer, got {value}")),
    }
}
