//! Local admin endpoints for the gateway mTLS certificate ledger.
//!
//! The CLI uses these to list issued certificates and to revoke one, which
//! also cancels that certificate's live connections. They are loopback-only.

use std::net::SocketAddr;

use axum::{
    Json,
    extract::{ConnectInfo, State},
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::Deserialize;

use crate::AppState;

fn loopback_only(peer: SocketAddr) -> Option<Response> {
    if peer.ip().is_loopback() {
        None
    } else {
        Some(
            (
                StatusCode::FORBIDDEN,
                Json(serde_json::json!({"error": "local admin endpoint"})),
            )
                .into_response(),
        )
    }
}

pub async fn handle_list(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
) -> Response {
    if let Some(denied) = loopback_only(peer) {
        return denied;
    }
    let Some(mtls) = state.mtls.as_ref() else {
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({"error": "[gateway.mtls] is not enabled"})),
        )
            .into_response();
    };
    let (issued, active) = mtls.list();
    Json(serde_json::json!({
        "gateway_id": mtls.gateway_id,
        "issued": issued,
        "active": active
            .into_iter()
            .map(|(fingerprint, connections)| serde_json::json!({
                "fingerprint": fingerprint,
                "connections": connections,
            }))
            .collect::<Vec<_>>(),
    }))
    .into_response()
}

#[derive(Deserialize)]
pub struct RevokeRequest {
    /// Device id or certificate fingerprint (SHA-256 hex).
    pub selector: String,
}

pub async fn handle_revoke(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Json(request): Json<RevokeRequest>,
) -> Response {
    if let Some(denied) = loopback_only(peer) {
        return denied;
    }
    let Some(mtls) = state.mtls.as_ref() else {
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({"error": "[gateway.mtls] is not enabled"})),
        )
            .into_response();
    };
    match mtls.revoke(&request.selector) {
        Ok(revoked) => Json(serde_json::json!({"revoked": revoked})).into_response(),
        Err(error) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": format!("{error}")})),
        )
            .into_response(),
    }
}
