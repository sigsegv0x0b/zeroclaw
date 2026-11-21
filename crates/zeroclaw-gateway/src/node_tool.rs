//! Wraps a node capability as a zeroclaw [`Tool`] so it can be dispatched
//! through the existing tool registry and agent loop.
//! Tool names are prefixed with the node ID: `node_<node_id>__<capability>`.

use std::sync::Arc;

use async_trait::async_trait;
use tokio::time::Duration;

use crate::nodes::{NodeInvocation, NodeRegistry};
use zeroclaw_api::attribution::{ToolKind, ToolProvenance};
use zeroclaw_api::tool::{Tool, ToolOutput, ToolResult};
use zeroclaw_api::tool_attribution;
use zeroclaw_runtime::composition::{ToolRequest, ToolSource};
use zeroclaw_tools::node_capabilities::requires_approval;

tool_attribution!(NodeTool, ToolKind::Plugin, ToolProvenance::Extension);

/// Default timeout for node invocations (30 seconds).
const NODE_INVOKE_TIMEOUT_SECS: u64 = 30;

/// A zeroclaw [`Tool`] backed by a node capability.
/// The `prefixed_name` (e.g. `node_phone-1__camera_snap`) is what the agent
/// loop sees. Invocations are routed to the connected node via WebSocket.
pub struct NodeTool {
    /// Prefixed, provider-safe name: `node_<node_id>__<capability>`.
    prefixed_name: String,
    /// The node ID this tool belongs to.
    node_id: String,
    /// The original capability name.
    capability_name: String,
    /// Human-readable description.
    description: String,
    /// JSON schema for parameters.
    parameters: serde_json::Value,
    /// Node registry for routing invocations.
    registry: Arc<NodeRegistry>,
}

impl NodeTool {
    /// Create a new node tool wrapper.
    pub fn new(
        node_id: String,
        capability_name: String,
        description: String,
        parameters: serde_json::Value,
        registry: Arc<NodeRegistry>,
    ) -> Self {
        let prefixed_name = Self::tool_name(&node_id, &capability_name);
        Self {
            prefixed_name,
            node_id,
            capability_name,
            description,
            parameters,
            registry,
        }
    }

    /// Build the prefixed tool name for a node capability.
    ///
    /// Provider tool names are restricted to `[a-zA-Z0-9_-]{1,64}`
    /// (OpenAI-compatible APIs reject colons and dots, and OpenRouter filters
    /// such tools out entirely). The `node_<id>__<capability>` shape keeps the
    /// origin visible while staying in that alphabet; dots in capability names
    /// become `_`.
    pub fn tool_name(node_id: &str, capability_name: &str) -> String {
        let mut name = format!(
            "node_{}__{}",
            sanitize_component(node_id),
            sanitize_component(capability_name)
        );
        name.truncate(64);
        name
    }
}

/// Replace characters providers reject with `_` so a tool name survives the
/// strict `[a-zA-Z0-9_-]` rule. `:` and `.` are the common offenders.
fn sanitize_component(raw: &str) -> String {
    raw.chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '_' || c == '-' {
                c
            } else {
                '_'
            }
        })
        .collect()
}

/// Exposes every capability of every connected node as an agent tool.
///
/// The gateway attaches this source to the live execution capability it hands
/// the WebSocket-chat agent, so `alarm.set` on a paired phone becomes
/// `node_android_1a2b3c4d__alarm_set` in the model's tool list. Node tools are
/// rebuilt against the live registry for each agent construction: a
/// disconnected phone simply contributes no tools.
pub struct NodeToolSource {
    registry: Arc<NodeRegistry>,
}

impl NodeToolSource {
    pub fn new(registry: Arc<NodeRegistry>) -> Self {
        Self { registry }
    }

    /// Build one tool per connected capability. Split from [`ToolSource::tools`]
    /// so tests can exercise it without constructing a full [`ToolRequest`].
    fn collect(&self) -> Vec<Box<dyn Tool>> {
        self.registry
            .all_capabilities()
            .into_iter()
            .map(|(node_id, capability_name, capability)| {
                Box::new(NodeTool::new(
                    node_id,
                    capability_name,
                    capability.description,
                    capability.parameters,
                    Arc::clone(&self.registry),
                )) as Box<dyn Tool>
            })
            .collect()
    }
}

impl ToolSource for NodeToolSource {
    fn tools(&self, _request: &ToolRequest<'_>) -> anyhow::Result<Vec<Box<dyn Tool>>> {
        Ok(self.collect())
    }
}

#[async_trait]
impl Tool for NodeTool {
    fn name(&self) -> &str {
        &self.prefixed_name
    }

    fn description(&self) -> &str {
        &self.description
    }

    fn parameters_schema(&self) -> serde_json::Value {
        self.parameters.clone()
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        // Check if this capability requires approval
        if requires_approval(&self.capability_name) {
            let approved = args
                .get("approved")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            if !approved {
                return Ok(ToolResult {
                    success: false,
                    output: ToolOutput::default(),
                    error: Some(format!(
                        "Capability '{}' requires approval. Set approved=true to proceed.",
                        self.capability_name
                    )),
                });
            }
        }

        // Strip the `approved` field (same as MCP tools)
        let args = match args {
            serde_json::Value::Object(mut map) => {
                map.remove("approved");
                serde_json::Value::Object(map)
            }
            other => other,
        };

        let invoke_tx: tokio::sync::mpsc::Sender<NodeInvocation> =
            match self.registry.invoke_tx(&self.node_id) {
                Some(tx) => tx,
                None => {
                    return Ok(ToolResult {
                        success: false,
                        output: ToolOutput::default(),
                        error: Some(format!("Node '{}' is not connected", self.node_id)),
                    });
                }
            };

        let call_id = uuid::Uuid::new_v4().to_string();
        let (response_tx, response_rx) = tokio::sync::oneshot::channel();

        let invocation = NodeInvocation {
            call_id,
            capability: self.capability_name.clone(),
            args,
            response_tx,
        };

        if invoke_tx.send(invocation).await.is_err() {
            return Ok(ToolResult {
                success: false,
                output: ToolOutput::default(),
                error: Some(format!(
                    "Failed to send invocation to node '{}'",
                    self.node_id
                )),
            });
        }

        // Wait for response with timeout
        match tokio::time::timeout(Duration::from_secs(NODE_INVOKE_TIMEOUT_SECS), response_rx).await
        {
            Ok(Ok(result)) => Ok(ToolResult {
                success: result.success,
                output: result.output.into(),
                error: result.error,
            }),
            Ok(Err(_)) => Ok(ToolResult {
                success: false,
                output: ToolOutput::default(),
                error: Some(format!(
                    "Node '{}' dropped the invocation channel",
                    self.node_id
                )),
            }),
            Err(_) => Ok(ToolResult {
                success: false,
                output: ToolOutput::default(),
                error: Some(format!(
                    "Node '{}' invocation timed out after {NODE_INVOKE_TIMEOUT_SECS}s",
                    self.node_id
                )),
            }),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::nodes::{NodeCapability, NodeInfo, NodeRegistry};
    use zeroclaw_api::attribution::Attributable;

    #[test]
    fn node_tool_name_is_provider_safe() {
        let name = NodeTool::tool_name("phone-1", "camera.snap");
        assert_eq!(name, "node_phone-1__camera_snap");
        assert!(name.len() <= 64);
        assert!(
            name.bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-'),
            "provider tool names allow only [a-zA-Z0-9_-]: {name}"
        );
    }

    #[test]
    fn node_tool_name_sanitizes_unknown_characters_and_truncates() {
        let name = NodeTool::tool_name("android:1a2b 3c4d", "alarm.set");
        assert_eq!(name, "node_android_1a2b_3c4d__alarm_set");
        assert!(NodeTool::tool_name(&"x".repeat(200), "cap").len() <= 64);
    }

    #[test]
    fn node_tool_source_exposes_connected_capabilities() {
        let registry = Arc::new(NodeRegistry::new(10));
        let (invoke_tx, _invoke_rx) = tokio::sync::mpsc::channel(32);
        registry.register(NodeInfo {
            node_id: "android-1a2b3c4d".to_string(),
            capabilities: vec![NodeCapability {
                name: "alarm.set".to_string(),
                description: "Set an alarm on the phone".to_string(),
                parameters: serde_json::json!({
                    "type": "object",
                    "properties": { "hour": { "type": "integer" } }
                }),
            }],
            invoke_tx,
        });

        let source = NodeToolSource::new(Arc::clone(&registry));
        let tools = source.collect();
        assert_eq!(tools.len(), 1);
        assert_eq!(tools[0].name(), "node_android-1a2b3c4d__alarm_set");
        assert_eq!(tools[0].description(), "Set an alarm on the phone");

        let empty = NodeToolSource::new(Arc::new(NodeRegistry::new(10)));
        assert!(empty.collect().is_empty());
    }

    #[test]
    fn node_tool_metadata() {
        let registry = Arc::new(NodeRegistry::new(10));
        let tool = NodeTool::new(
            "phone-1".to_string(),
            "camera.snap".to_string(),
            "Take a photo".to_string(),
            serde_json::json!({"type": "object", "properties": {"resolution": {"type": "string"}}}),
            registry,
        );

        assert_eq!(tool.name(), "node_phone-1__camera_snap");
        assert_eq!(tool.description(), "Take a photo");
        assert_eq!(tool.parameters_schema()["type"], "object");
        assert_eq!(tool.tool_provenance(), ToolProvenance::Extension);
    }

    #[tokio::test]
    async fn node_tool_execute_node_not_connected() {
        let registry = Arc::new(NodeRegistry::new(10));
        let tool = NodeTool::new(
            "missing-node".to_string(),
            "test".to_string(),
            "Test".to_string(),
            serde_json::json!({"type": "object", "properties": {}}),
            registry,
        );

        let result = tool.execute(serde_json::json!({})).await.unwrap();
        assert!(!result.success);
        assert!(result.error.unwrap().contains("not connected"));
    }

    #[tokio::test]
    async fn node_tool_execute_success() {
        let registry = Arc::new(NodeRegistry::new(10));
        let (invoke_tx, mut invoke_rx) = tokio::sync::mpsc::channel(32);

        registry.register(NodeInfo {
            node_id: "test-node".to_string(),
            capabilities: vec![NodeCapability {
                name: "echo".to_string(),
                description: "Echo back".to_string(),
                parameters: serde_json::json!({"type": "object", "properties": {}}),
            }],
            invoke_tx,
        });

        let tool = NodeTool::new(
            "test-node".to_string(),
            "echo".to_string(),
            "Echo back".to_string(),
            serde_json::json!({"type": "object", "properties": {}}),
            Arc::clone(&registry),
        );

        // Spawn a task that simulates the node responding
        zeroclaw_spawn::spawn!(async move {
            if let Some(invocation) = invoke_rx.recv().await {
                let _ = invocation
                    .response_tx
                    .send(crate::nodes::NodeInvocationResult {
                        success: true,
                        output: "echoed".to_string(),
                        error: None,
                    });
            }
        });

        let result = tool
            .execute(serde_json::json!({"msg": "hello"}))
            .await
            .unwrap();
        assert!(result.success);
        assert_eq!(result.output, "echoed");
        assert!(result.error.is_none());
    }

    #[test]
    fn node_tool_spec_generation() {
        let registry = Arc::new(NodeRegistry::new(10));
        let tool = NodeTool::new(
            "sensor-1".to_string(),
            "temp.read".to_string(),
            "Read temperature".to_string(),
            serde_json::json!({"type": "object", "properties": {"unit": {"type": "string"}}}),
            registry,
        );

        let spec = tool.spec();
        assert_eq!(spec.name, "node_sensor-1__temp_read");
        assert_eq!(spec.description, "Read temperature");
        assert!(spec.parameters["properties"]["unit"]["type"] == "string");
    }

    #[tokio::test]
    async fn node_tool_rejects_unapproved_sensitive_operation() {
        let registry = Arc::new(NodeRegistry::new(10));
        let tool = NodeTool::new(
            "phone-1".to_string(),
            "camera.snap".to_string(),
            "Take a photo".to_string(),
            serde_json::json!({
                "type": "object",
                "properties": {
                    "approved": { "type": "boolean" }
                },
                "required": ["approved"]
            }),
            registry,
        );

        // Without approved field
        let result = tool.execute(serde_json::json!({})).await.unwrap();
        assert!(!result.success);
        assert!(result.error.as_ref().unwrap().contains("requires approval"));

        // With approved=false
        let result = tool
            .execute(serde_json::json!({"approved": false}))
            .await
            .unwrap();
        assert!(!result.success);
        assert!(result.error.as_ref().unwrap().contains("requires approval"));
    }
}
