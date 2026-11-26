//! Admin and device endpoints for the gateway mTLS certificate ledger.
//!
//! The CLI uses the admin endpoints to list issued certificates and to revoke
//! one, which also cancels that certificate's live connections and, once a
//! device has no live certificate left, revokes its bearer token. They are
//! loopback-only and need this run's gateway admin token. Renewal is served
//! to the device itself over the mTLS listener.

use std::net::SocketAddr;

use axum::{
    Json,
    extract::{ConnectInfo, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use serde::Deserialize;

use crate::AppState;
use crate::mtls_state::RenewRefusal;

fn mtls_disabled() -> Response {
    (
        StatusCode::NOT_FOUND,
        Json(serde_json::json!({"error": "[gateway.mtls] is not enabled"})),
    )
        .into_response()
}

pub async fn handle_list(
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
    let Some(mtls) = state.mtls.as_ref() else {
        return mtls_disabled();
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
pub struct RenewRequest {
    pub csr_pem: String,
}

/// Authenticated renewal: the caller presents a valid client certificate and
/// the bearer token bound to it, submits a fresh CSR, and receives a new
/// certificate for the same device. The old certificate stays valid until the
/// new one completes a handshake, so a lost response can be retried. The CA,
/// the server leaf, and the gateway identity are unchanged.
pub async fn handle_renew(
    State(state): State<AppState>,
    peer: Option<axum::Extension<crate::mtls_state::PeerCertFingerprint>>,
    headers: HeaderMap,
    Json(request): Json<RenewRequest>,
) -> Response {
    if let Err(denied) = crate::api::require_auth(&state, &headers) {
        return denied.into_response();
    }
    let Some(mtls) = state.mtls.as_ref() else {
        return mtls_disabled();
    };
    let Some(axum::Extension(peer)) = peer else {
        return (
            StatusCode::FORBIDDEN,
            Json(serde_json::json!({"error": "renewal is only served on the mTLS listener"})),
        )
            .into_response();
    };
    let Some(token) = crate::api::extract_bearer_token(&headers).filter(|t| !t.is_empty()) else {
        return (
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({"error": "renewal needs the device's bearer token"})),
        )
            .into_response();
    };
    let token_hash = zeroclaw_config::pairing::PairingGuard::token_hash(token);
    match mtls.renew(&peer.0, &token_hash, &request.csr_pem) {
        Ok(renewed) => {
            ::zeroclaw_log::record!(
                INFO,
                ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Note)
                    .with_attrs(serde_json::json!({
                        "device_id": renewed.device_id,
                        "fingerprint": renewed.leaf.fingerprint,
                        "replaces": peer.0,
                    })),
                "mTLS client certificate renewed"
            );
            Json(serde_json::json!({
                "cert_pem": renewed.leaf.cert_pem,
                "ca_chain_pem": mtls.ca_cert_pem,
                "device_id": renewed.device_id,
                "gateway_id": mtls.gateway_id,
                "not_after": renewed.leaf.not_after,
            }))
            .into_response()
        }
        Err(RenewRefusal::NotIssued) => (
            StatusCode::FORBIDDEN,
            Json(serde_json::json!({
                "error": "this certificate was not issued by enrollment; pair the device again"
            })),
        )
            .into_response(),
        Err(RenewRefusal::TokenMismatch) => (
            StatusCode::FORBIDDEN,
            Json(serde_json::json!({
                "error": "the bearer token does not belong to this certificate's device"
            })),
        )
            .into_response(),
        Err(RenewRefusal::InvalidCsr(error)) => (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"error": format!("invalid CSR: {error}")})),
        )
            .into_response(),
        Err(RenewRefusal::Internal(error)) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": format!("renewal failed: {error}")})),
        )
            .into_response(),
    }
}

#[derive(Deserialize)]
pub struct RevokeRequest {
    /// Device id or certificate fingerprint (SHA-256 hex).
    pub selector: String,
}

pub async fn handle_revoke(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(request): Json<RevokeRequest>,
) -> Response {
    if let Err(denied) = crate::require_localhost(&peer) {
        return denied.into_response();
    }
    if let Err(denied) = crate::require_gateway_admin_token(&state, &headers) {
        return denied.into_response();
    }
    let Some(mtls) = state.mtls.as_ref() else {
        return mtls_disabled();
    };
    // Serialize with every other credential write, as device revocation does.
    let commit = match state.begin_config_commit().await {
        Ok(commit) => commit,
        Err(error) => {
            let (status, message) = crate::api_pairing::revocation_not_admitted(error);
            return (status, Json(serde_json::json!({"error": message}))).into_response();
        }
    };
    let outcome = match mtls.revoke(&request.selector) {
        Ok(outcome) => outcome,
        Err(error) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": format!("{error:#}")})),
            )
                .into_response();
        }
    };
    if !outcome.released.is_empty()
        && let Err(error) = crate::persist_pairing_tokens_under(&state, commit).await
    {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({
                "error": format!("certificates and tokens revoked in memory but config persist failed: {error}"),
                "revoked": outcome.fingerprints,
            })),
        )
            .into_response();
    }
    Json(serde_json::json!({
        "revoked": outcome.fingerprints,
        "revoked_devices": outcome
            .released
            .iter()
            .map(|device| device.device_id.clone())
            .collect::<Vec<_>>(),
    }))
    .into_response()
}
