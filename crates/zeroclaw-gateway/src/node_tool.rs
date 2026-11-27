//! Wraps a node capability as a zeroclaw [`Tool`] so it can be dispatched
//! through the existing tool registry and agent loop.
//! Tool names are prefixed with the node ID: `node_<node_id>__<capability>`.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use async_trait::async_trait;
use sha2::{Digest, Sha256};
use tokio::time::Duration;

use crate::nodes::{MAX_NODE_ATTACHMENT_BYTES, NodeAttachment, NodeInvocation, NodeRegistry};
use zeroclaw_api::attribution::{ToolKind, ToolProvenance};
use zeroclaw_api::media::{MarkerKind, RenderedMarker};
use zeroclaw_api::tool::{Tool, ToolOutput, ToolResult};
use zeroclaw_api::tool_attribution;
use zeroclaw_runtime::composition::{ToolRequest, ToolSource};

tool_attribution!(NodeTool, ToolKind::Plugin, ToolProvenance::Extension);

/// Default timeout for node invocations (30 seconds).
const NODE_INVOKE_TIMEOUT_SECS: u64 = 30;

/// Image MIME types a node may attach. Anything else is a document the model
/// cannot inspect through the vision path, so it is dropped rather than
/// silently mislabelled.
const ALLOWED_IMAGE_MIMES: &[&str] = &["image/jpeg", "image/png", "image/webp", "image/gif"];

/// Per-chat switch shared by every node tool and the WebSocket handler.
///
/// `prefer_node_tools` is the app setting for this socket. When it is off,
/// `explicit_phone` is refreshed from each incoming user message: only a
/// message that names the phone may dispatch a phone tool, so the model
/// cannot skip the "which device?" question just because it ignored the
/// prompt.
#[derive(Debug)]
pub struct NodeToolPolicy {
    prefer_node_tools: AtomicBool,
    explicit_phone: AtomicBool,
}

impl NodeToolPolicy {
    pub fn new(prefer_node_tools: bool) -> Arc<Self> {
        Arc::new(Self {
            prefer_node_tools: AtomicBool::new(prefer_node_tools),
            explicit_phone: AtomicBool::new(false),
        })
    }

    pub fn set_explicit_phone(&self, explicit: bool) {
        self.explicit_phone.store(explicit, Ordering::Relaxed);
    }

    fn allows_phone_tool(&self) -> bool {
        self.prefer_node_tools.load(Ordering::Relaxed)
            || self.explicit_phone.load(Ordering::Relaxed)
    }
}

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
    /// Whether this chat may dispatch phone tools yet.
    policy: Arc<NodeToolPolicy>,
}

impl NodeTool {
    /// Create a new node tool wrapper that allows dispatch (test/default).
    pub fn new(
        node_id: String,
        capability_name: String,
        description: String,
        parameters: serde_json::Value,
        registry: Arc<NodeRegistry>,
    ) -> Self {
        Self::with_policy(
            node_id,
            capability_name,
            description,
            parameters,
            registry,
            NodeToolPolicy::new(true),
        )
    }

    fn with_policy(
        node_id: String,
        capability_name: String,
        description: String,
        parameters: serde_json::Value,
        registry: Arc<NodeRegistry>,
        policy: Arc<NodeToolPolicy>,
    ) -> Self {
        let prefixed_name = Self::tool_name(&node_id, &capability_name);
        Self {
            prefixed_name,
            node_id,
            capability_name,
            description,
            parameters,
            registry,
            policy,
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
        if name.len() > 64 {
            // Registration limits keep production names below the cap, but a
            // truncated name alone can collide (`a.b` vs `a_b`, long ids).
            // Keep a stable digest of the full name in the suffix so two
            // different capabilities never share a tool name.
            let digest = hex::encode(Sha256::digest(name.as_bytes()));
            name.truncate(55);
            name.push('-');
            name.push_str(&digest[..8]);
        }
        name
    }
}

/// Replace characters providers reject with `_` so a tool name survives the
/// strict `[a-zA-Z0-9_-]` rule. `:` and `.` are the common offenders.
pub(crate) fn sanitize_component(raw: &str) -> String {
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
    node_id: Option<String>,
    owner: Option<String>,
    enabled: bool,
    /// Shared with every tool this source builds and with the chat handler,
    /// which refreshes the explicit-phone choice before each turn.
    policy: Arc<NodeToolPolicy>,
}

impl NodeToolSource {
    pub fn new(registry: Arc<NodeRegistry>) -> Self {
        Self {
            registry,
            node_id: None,
            owner: None,
            enabled: true,
            policy: NodeToolPolicy::new(true),
        }
    }

    /// Scope the tools to the node that originated the chat prompt. If that
    /// node is disconnected the user gets no phone tools rather than another
    /// connected node silently answering in its place.
    pub fn for_node(registry: Arc<NodeRegistry>, node_id: String) -> Self {
        Self::for_node_with_preference(registry, node_id, true)
    }

    pub fn for_node_with_preference(
        registry: Arc<NodeRegistry>,
        node_id: String,
        prefer_node_tools: bool,
    ) -> Self {
        Self {
            registry,
            node_id: Some(node_id),
            owner: None,
            enabled: true,
            policy: NodeToolPolicy::new(prefer_node_tools),
        }
    }

    /// Scope to one node AND the authenticated credential that registered it.
    /// A chat turn authenticated by a paired token can only reach the phone
    /// that registered under that same token.
    pub fn for_owned_node(registry: Arc<NodeRegistry>, node_id: String, owner: String) -> Self {
        Self::for_owned_node_with_preference(registry, node_id, owner, true)
    }

    pub fn for_owned_node_with_preference(
        registry: Arc<NodeRegistry>,
        node_id: String,
        owner: String,
        prefer_node_tools: bool,
    ) -> Self {
        Self {
            registry,
            node_id: Some(node_id),
            owner: Some(owner),
            enabled: true,
            policy: NodeToolPolicy::new(prefer_node_tools),
        }
    }

    /// A source with no tools. Chat turns that did not authenticate as a node's
    /// owner get this instead of every connected phone.
    pub fn none(registry: Arc<NodeRegistry>) -> Self {
        Self {
            registry,
            node_id: None,
            owner: None,
            enabled: false,
            policy: NodeToolPolicy::new(true),
        }
    }

    /// The policy shared by every tool this source builds. A chat handler
    /// updates its explicit-phone flag before each turn.
    pub fn policy(&self) -> Arc<NodeToolPolicy> {
        Arc::clone(&self.policy)
    }

    /// Build one tool per connected capability. Split from [`ToolSource::tools`]
    /// so tests can exercise it without constructing a full [`ToolRequest`].
    ///
    /// The phone-or-host preference is delivered once per turn by
    /// [`crate::ws::node_preference_note`]; repeating it in every description
    /// cost ~200 characters per capability on every request.
    fn collect(&self) -> Vec<Box<dyn Tool>> {
        if !self.enabled {
            return Vec::new();
        }
        self.registry
            .capabilities_for(self.node_id.as_deref(), self.owner.as_deref())
            .into_iter()
            .map(|(node_id, capability_name, capability)| {
                Box::new(NodeTool::with_policy(
                    node_id,
                    capability_name,
                    capability.description,
                    capability.parameters,
                    Arc::clone(&self.registry),
                    Arc::clone(&self.policy),
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
        if !self.policy.allows_phone_tool() {
            return Ok(ToolResult {
                success: false,
                output: ToolOutput::default(),
                error: Some(
                    "The user has not chosen the phone for this action. Ask whether they want \
                     it on this phone or on the host/server, then wait for their answer."
                        .to_string(),
                ),
            });
        }
        // Risk is enforced by the agent's approval policy, which sees this
        // tool's name and the operator's `auto_approve` / `always_ask` sets.
        // An in-tool `approved` flag would be model-controlled, so it could
        // not be a trust boundary; it used to exist here and is deliberately
        // gone.
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
                output: {
                    let mut output = ToolOutput::text(result.output);
                    for marker in attachment_markers(&result.attachments) {
                        output = output.with_attachment(marker);
                    }
                    output
                },
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

/// Convert a node's declared attachments into tool-output markers.
///
/// Only images the vision path can load are surfaced: the bytes become a
/// `data:` URI (the one target both hosts can resolve — the phone's own URI is
/// meaningless on the gateway), and anything oversized or mislabelled is
/// dropped rather than turned into a broken attachment.
fn attachment_markers(attachments: &[NodeAttachment]) -> Vec<RenderedMarker> {
    attachments
        .iter()
        .filter_map(|attachment| {
            let mime = attachment.mime.trim().to_ascii_lowercase();
            if attachment.kind != "image" {
                return None;
            }
            if !ALLOWED_IMAGE_MIMES.contains(&mime.as_str()) {
                ::zeroclaw_log::record!(
                    WARN,
                    ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Reject)
                        .with_outcome(::zeroclaw_log::EventOutcome::Failure)
                        .with_attrs(::serde_json::json!({"mime": mime})),
                    "node attachment dropped: unsupported image mime"
                );
                return None;
            }
            let payload = attachment.data_b64.trim();
            if payload.is_empty()
                || !payload
                    .bytes()
                    .all(|b| b.is_ascii() && !b.is_ascii_whitespace())
            {
                return None;
            }
            if attachment.decoded_len() > MAX_NODE_ATTACHMENT_BYTES {
                ::zeroclaw_log::record!(
                    WARN,
                    ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Reject)
                        .with_outcome(::zeroclaw_log::EventOutcome::Failure)
                        .with_attrs(::serde_json::json!({
                            "decoded_bytes": attachment.decoded_len(),
                            "limit": MAX_NODE_ATTACHMENT_BYTES,
                        })),
                    "node attachment dropped: over size limit"
                );
                return None;
            }
            Some(RenderedMarker {
                target: format!("data:{mime};base64,{payload}"),
                kind: MarkerKind::Image,
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::nodes::{NodeCapability, NodeInfo, NodeRegistry};
    use zeroclaw_api::attribution::Attributable;

    #[test]
    fn image_attachments_become_data_uri_markers() {
        let markers = attachment_markers(&[
            NodeAttachment {
                kind: "image".to_string(),
                mime: "image/jpeg".to_string(),
                data_b64: "aGVsbG8=".to_string(),
            },
            NodeAttachment {
                kind: "image".to_string(),
                mime: "text/plain".to_string(),
                data_b64: "aGVsbG8=".to_string(),
            },
            NodeAttachment {
                kind: "document".to_string(),
                mime: "image/png".to_string(),
                data_b64: "aGVsbG8=".to_string(),
            },
        ]);

        assert_eq!(markers.len(), 1);
        assert_eq!(markers[0].kind, MarkerKind::Image);
        assert_eq!(markers[0].target, "data:image/jpeg;base64,aGVsbG8=");
    }

    #[test]
    fn attachment_decoded_len_matches_base64_padding() {
        let attachment = NodeAttachment {
            kind: "image".to_string(),
            mime: "image/png".to_string(),
            data_b64: "aGVsbG8=".to_string(),
        };
        assert_eq!(attachment.decoded_len(), 5);
    }

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
            owner: "owner".to_string(),
            generation: 0,
            shutdown: tokio_util::sync::CancellationToken::new(),
        });

        let source = NodeToolSource::new(Arc::clone(&registry));
        let tools = source.collect();
        assert_eq!(tools.len(), 1);
        assert_eq!(tools[0].name(), "node_android-1a2b3c4d__alarm_set");
        assert!(
            tools[0]
                .description()
                .starts_with("Set an alarm on the phone")
        );
        assert!(
            !tools[0].description().contains("Preference:"),
            "the phone-or-host preference belongs to the per-turn note, not every tool"
        );

        let empty = NodeToolSource::new(Arc::new(NodeRegistry::new(10)));
        assert!(empty.collect().is_empty());
    }

    #[test]
    fn node_tool_source_scopes_to_the_prompting_node() {
        let registry = Arc::new(NodeRegistry::new(10));
        for node_id in ["android-origin", "android-other"] {
            let (invoke_tx, _invoke_rx) = tokio::sync::mpsc::channel(32);
            registry.register(NodeInfo {
                node_id: node_id.to_string(),
                capabilities: vec![NodeCapability {
                    name: "time.get".to_string(),
                    description: "Read the phone clock".to_string(),
                    parameters: serde_json::json!({"type": "object", "properties": {}}),
                }],
                invoke_tx,
                owner: format!("owner-{node_id}"),
                generation: 0,
                shutdown: tokio_util::sync::CancellationToken::new(),
            });
        }

        let scoped = NodeToolSource::for_node(Arc::clone(&registry), "android-origin".to_string());
        let tools = scoped.collect();
        assert_eq!(tools.len(), 1);
        assert_eq!(tools[0].name(), "node_android-origin__time_get");

        // A scoped node that is no longer connected contributes no tools, so
        // another phone cannot answer the prompt in its place.
        let disconnected =
            NodeToolSource::for_node(Arc::clone(&registry), "android-gone".to_string());
        assert!(disconnected.collect().is_empty());

        let owned = NodeToolSource::for_owned_node(
            Arc::clone(&registry),
            "android-origin".to_string(),
            "owner-android-origin".to_string(),
        );
        assert_eq!(owned.collect().len(), 1);
        let wrong_owner = NodeToolSource::for_owned_node(
            Arc::clone(&registry),
            "android-origin".to_string(),
            "owner-someone-else".to_string(),
        );
        assert!(
            wrong_owner.collect().is_empty(),
            "a paired token must not reach a node registered by another token"
        );
        assert!(
            NodeToolSource::none(Arc::clone(&registry))
                .collect()
                .is_empty()
        );

        let all = NodeToolSource::new(Arc::clone(&registry));
        assert_eq!(all.collect().len(), 2);
    }

    #[test]
    fn preference_off_tells_the_model_to_ask() {
        let registry = Arc::new(NodeRegistry::new(10));
        let (invoke_tx, _invoke_rx) = tokio::sync::mpsc::channel(32);
        registry.register(NodeInfo {
            node_id: "android-origin".to_string(),
            capabilities: vec![NodeCapability {
                name: "alarm.set".to_string(),
                description: "Set an alarm on the phone".to_string(),
                parameters: serde_json::json!({"type": "object", "properties": {}}),
            }],
            invoke_tx,
            owner: "owner".to_string(),
            generation: 0,
            shutdown: tokio_util::sync::CancellationToken::new(),
        });

        let source = NodeToolSource::for_owned_node_with_preference(
            Arc::clone(&registry),
            "android-origin".to_string(),
            "owner".to_string(),
            false,
        );
        let tools = source.collect();
        assert_eq!(tools.len(), 1);
        assert!(
            !tools[0].description().contains("Preference:"),
            "preference off must not duplicate the per-turn note in every tool"
        );
        assert_eq!(
            tools[0].description(),
            "Set an alarm on the phone",
            "the tool description is the capability's own text"
        );
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
    async fn preference_off_refuses_until_the_user_names_the_phone() {
        let registry = Arc::new(NodeRegistry::new(10));
        let policy = NodeToolPolicy::new(false);
        let tool = NodeTool::with_policy(
            "phone-1".to_string(),
            "alarm.set".to_string(),
            "Set an alarm".to_string(),
            serde_json::json!({"type": "object", "properties": {}}),
            registry,
            Arc::clone(&policy),
        );
        let refused = tool.execute(serde_json::json!({})).await.unwrap();
        assert!(!refused.success);
        assert!(
            refused
                .error
                .as_deref()
                .is_some_and(|error| error.contains("Ask whether"))
        );

        policy.set_explicit_phone(true);
        let allowed = tool.execute(serde_json::json!({})).await.unwrap();
        assert!(
            allowed
                .error
                .as_deref()
                .is_some_and(|error| error.contains("not connected")),
            "after an explicit phone choice the tool must dispatch: {:?}",
            allowed.error
        );
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
            owner: "owner".to_string(),
            generation: 0,
            shutdown: tokio_util::sync::CancellationToken::new(),
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
                        attachments: Vec::new(),
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

    /// Sensitive capabilities are gated by the agent's approval policy, not
    /// by a model-supplied flag. The tool must dispatch the call (here: fail
    /// because no node is connected), never refuse for lack of `approved`.
    #[tokio::test]
    async fn sensitive_capability_is_dispatched_not_gated_in_tool() {
        let registry = Arc::new(NodeRegistry::new(10));
        let tool = NodeTool::new(
            "phone-1".to_string(),
            "camera.snap".to_string(),
            "Take a photo".to_string(),
            serde_json::json!({"type": "object", "properties": {}}),
            registry,
        );

        let result = tool.execute(serde_json::json!({})).await.unwrap();
        let error = result.error.unwrap_or_default();
        assert!(!result.success);
        assert!(error.contains("not connected"), "{error}");
        assert!(!error.contains("requires approval"), "{error}");
    }
}
