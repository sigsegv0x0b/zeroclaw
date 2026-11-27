//! Node inventory: paired devices joined to their live `/ws/nodes` lists.
//!
//! `GET /api/devices` reports the durable pairing registry and `/api/status`
//! reports connected node ids, but neither answers the operator's question:
//! which paired phone is connected right now, and which tools does it offer?
//! This module joins both by the token hash that owns the registration and
//! exposes one view. Revocation reuses the same credential path as
//! `DELETE /api/devices/{id}`.

use std::net::SocketAddr;

use axum::{
    Json,
    extract::{ConnectInfo, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::AppState;
use crate::api_pairing::DeviceInfo;
use crate::nodes::NodeSnapshot;

#[derive(Debug, Clone, Serialize)]
pub struct NodeToolView {
    /// Provider-safe tool name the model sees.
    pub name: String,
    /// Capability name the node registered.
    pub capability: String,
    pub description: String,
    pub parameters: Value,
}

#[derive(Debug, Clone, Serialize)]
pub struct NodeView {
    /// Paired device id (`dev-…`), or the live node id for an unpaired node.
    pub id: String,
    /// Live node id (`android-…`) when the node is connected.
    pub node_id: Option<String>,
    pub name: Option<String>,
    pub device_type: Option<String>,
    /// Present in the paired-device registry.
    pub paired: bool,
    pub connected: bool,
    pub paired_at: Option<String>,
    pub last_seen: Option<String>,
    pub ip_address: Option<String>,
    pub tools: Vec<NodeToolView>,
}

fn tool_views(node: &NodeSnapshot) -> Vec<NodeToolView> {
    node.capabilities
        .iter()
        .map(|capability| NodeToolView {
            name: crate::node_tool::NodeTool::tool_name(&node.node_id, &capability.name),
            capability: capability.name.clone(),
            description: capability.description.clone(),
            parameters: capability.parameters.clone(),
        })
        .collect()
}

/// Join durable device rows to live node registrations. A paired device with
/// no live registration is listed as disconnected; a live registration with
/// no paired row is listed as unpaired so it is still visible to the operator.
fn merge_nodes(devices: Vec<(String, DeviceInfo)>, mut live: Vec<NodeSnapshot>) -> Vec<NodeView> {
    let mut nodes = Vec::with_capacity(devices.len() + live.len());
    for (token_hash, device) in devices {
        let live_node = live
            .iter()
            .position(|node| node.owner == token_hash)
            .map(|index| live.remove(index));
        nodes.push(NodeView {
            id: device.id,
            node_id: live_node.as_ref().map(|node| node.node_id.clone()),
            name: device.name,
            device_type: device.device_type,
            paired: true,
            connected: live_node.is_some(),
            paired_at: Some(device.paired_at.to_rfc3339()),
            last_seen: Some(device.last_seen.to_rfc3339()),
            ip_address: device.ip_address,
            tools: live_node.as_ref().map(tool_views).unwrap_or_default(),
        });
    }
    for node in live {
        nodes.push(NodeView {
            id: node.node_id.clone(),
            node_id: Some(node.node_id.clone()),
            name: None,
            device_type: None,
            paired: false,
            connected: true,
            paired_at: None,
            last_seen: None,
            ip_address: None,
            tools: tool_views(&node),
        });
    }
    // Connected nodes first, then a stable id order for repeatable output.
    nodes.sort_by(|left, right| {
        right
            .connected
            .cmp(&left.connected)
            .then_with(|| left.id.cmp(&right.id))
    });
    nodes
}

fn inventory(state: &AppState) -> Result<Vec<NodeView>, (StatusCode, Json<Value>)> {
    let devices = match state.device_registry.as_ref() {
        Some(registry) => registry.entries().map_err(|error| {
            ::zeroclaw_log::record!(
                ERROR,
                ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Fail)
                    .with_outcome(::zeroclaw_log::EventOutcome::Failure)
                    .with_attrs(serde_json::json!({"error": format!("{error}")})),
                "node inventory: device registry list failed"
            );
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": format!("Device registry error: {error}")})),
            )
        })?,
        None => Vec::new(),
    };
    Ok(merge_nodes(devices, state.node_registry.snapshots()))
}

fn inventory_response(state: &AppState) -> Response {
    match inventory(state) {
        Ok(nodes) => Json(serde_json::json!({
            "nodes": nodes,
            "count": nodes.len(),
        }))
        .into_response(),
        Err((status, body)) => (status, body).into_response(),
    }
}

/// GET /api/nodes — paired devices plus currently connected nodes.
pub async fn handle_list(State(state): State<AppState>, headers: HeaderMap) -> Response {
    if let Err(denied) = crate::api::require_auth(&state, &headers) {
        return denied.into_response();
    }
    inventory_response(&state)
}

/// GET /admin/nodes — the same inventory for the local CLI.
pub async fn handle_admin_list(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
) -> Response {
    if let Err(denied) = crate::require_localhost(&peer) {
        return denied.into_response();
    }
    if let Err(denied) = crate::require_gateway_admin_token(&state, &headers) {
        return denied.into_response();
    }
    inventory_response(&state)
}

#[derive(Deserialize)]
pub struct RevokeNodeRequest {
    /// Paired device id, live node id, or token hash.
    pub selector: String,
}

/// POST /admin/nodes/revoke — revoke a paired device from the CLI.
///
/// Resolves a live node id to the paired row that owns it, then runs the same
/// revocation path as `DELETE /api/devices/{id}`: token, device row, mTLS
/// certificates, live connections, and `paired_tokens` persistence.
pub async fn handle_admin_revoke(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(request): Json<RevokeNodeRequest>,
) -> Response {
    if let Err(denied) = crate::require_localhost(&peer) {
        return denied.into_response();
    }
    if let Err(denied) = crate::require_gateway_admin_token(&state, &headers) {
        return denied.into_response();
    }
    let selector = request.selector.trim();
    if selector.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"error": "selector is required"})),
        )
            .into_response();
    }
    let Some(registry) = state.device_registry.as_ref() else {
        return (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(serde_json::json!({"error": "Device registry is disabled"})),
        )
            .into_response();
    };
    let entries = match registry.entries() {
        Ok(entries) => entries,
        Err(error) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": format!("Device registry error: {error}")})),
            )
                .into_response();
        }
    };
    let live = state.node_registry.snapshots();
    let device_id = entries
        .iter()
        .find(|(_, device)| device.id.eq_ignore_ascii_case(selector))
        .or_else(|| {
            entries.iter().find(|(token_hash, _)| {
                live.iter().any(|node| {
                    node.node_id.eq_ignore_ascii_case(selector)
                        && node.owner.eq_ignore_ascii_case(token_hash)
                })
            })
        })
        .or_else(|| {
            entries
                .iter()
                .find(|(token_hash, _)| token_hash.eq_ignore_ascii_case(selector))
        })
        .map(|(_, device)| device.id.clone());
    let Some(device_id) = device_id else {
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({
                "error": format!("no paired node matches '{selector}'")
            })),
        )
            .into_response();
    };

    let commit = match state.begin_config_commit().await {
        Ok(commit) => commit,
        Err(error) => {
            let (status, message) = crate::api_pairing::revocation_not_admitted(error);
            return (status, Json(serde_json::json!({"error": message}))).into_response();
        }
    };
    let revoked = match crate::api_pairing::revoke_device_credential(
        &state,
        registry,
        &device_id,
        &commit,
        || Ok::<(), (StatusCode, Json<Value>)>(()),
    ) {
        Ok(result) => result,
        Err(denied) => return denied.into_response(),
    };
    match revoked {
        Ok(true) => {}
        Ok(false) => {
            return (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({
                    "error": format!("device '{device_id}' is no longer paired")
                })),
            )
                .into_response();
        }
        Err(error) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": format!("Device registry error: {error}")})),
            )
                .into_response();
        }
    }
    if let Err(error) = crate::persist_pairing_tokens_under(&state, commit).await {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({
                "error": format!("token and certificates revoked in memory but config persist failed: {error}"),
                "device_id": device_id,
            })),
        )
            .into_response();
    }
    Json(serde_json::json!({
        "revoked": device_id,
        "selector": selector,
    }))
    .into_response()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::nodes::NodeCapability;
    use chrono::Utc;

    fn device(id: &str, name: &str) -> DeviceInfo {
        let now = Utc::now();
        DeviceInfo {
            id: id.to_string(),
            name: Some(name.to_string()),
            device_type: Some("android".to_string()),
            paired_at: now,
            last_seen: now,
            ip_address: Some("10.0.0.2".to_string()),
            capabilities: None,
        }
    }

    fn live(node_id: &str, owner: &str) -> NodeSnapshot {
        NodeSnapshot {
            node_id: node_id.to_string(),
            owner: owner.to_string(),
            capabilities: vec![NodeCapability {
                name: "time.get".to_string(),
                description: "Read the clock".to_string(),
                parameters: serde_json::json!({"type": "object", "properties": {}}),
            }],
        }
    }

    #[test]
    fn joins_paired_devices_to_live_nodes_by_owner() {
        let nodes = merge_nodes(
            vec![
                ("owner-a".to_string(), device("dev-a", "Phone A")),
                ("owner-b".to_string(), device("dev-b", "Phone B")),
            ],
            vec![live("android-a", "owner-a")],
        );
        let phone_a = nodes.iter().find(|node| node.id == "dev-a").unwrap();
        assert!(phone_a.connected);
        assert_eq!(phone_a.node_id.as_deref(), Some("android-a"));
        assert_eq!(phone_a.tools.len(), 1);
        assert_eq!(phone_a.tools[0].name, "node_android-a__time_get");

        let phone_b = nodes.iter().find(|node| node.id == "dev-b").unwrap();
        assert!(!phone_b.connected);
        assert!(phone_b.tools.is_empty());
    }

    #[test]
    fn an_unpaired_live_node_is_still_visible() {
        let nodes = merge_nodes(
            vec![("owner-a".to_string(), device("dev-a", "Phone A"))],
            vec![
                live("android-a", "owner-a"),
                live("shared-node", "shared-owner"),
            ],
        );
        let unpaired = nodes.iter().find(|node| node.id == "shared-node").unwrap();
        assert!(!unpaired.paired);
        assert!(unpaired.connected);
        assert_eq!(unpaired.tools.len(), 1);
    }
}
