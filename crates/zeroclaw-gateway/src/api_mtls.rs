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
pub struct RenewRequest {
    pub csr_pem: String,
    #[serde(default)]
    pub device_name: Option<String>,
}

/// Authenticated renewal: the caller presents a valid client certificate and
/// bearer token, submits a fresh CSR, and receives a new certificate. The old
/// certificate is revoked for future handshakes and the server leaf gets a new
/// private key. The CA and gateway identity stay stable, so clients keep their
/// pinned trust anchor.
pub async fn handle_renew(
    State(state): State<AppState>,
    axum::Extension(peer): axum::Extension<crate::mtls_state::PeerCertFingerprint>,
    headers: axum::http::HeaderMap,
    Json(request): Json<RenewRequest>,
) -> Response {
    if let Err(denied) = crate::api::require_auth(&state, &headers) {
        return denied.into_response();
    }
    let Some(mtls) = state.mtls.as_ref() else {
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({"error": "[gateway.mtls] is not enabled"})),
        )
            .into_response();
    };
    let old_fingerprint = peer.0.to_lowercase();
    let device_id = mtls
        .read_ledger()
        .into_iter()
        .find(|entry| entry.fingerprint.eq_ignore_ascii_case(&old_fingerprint))
        .map(|entry| entry.device_id)
        .unwrap_or_else(|| format!("dev-{}", &uuid::Uuid::new_v4().simple().to_string()[..8]));
    let issued = match zeroclaw_tls::sign_csr(
        &mtls.ca_cert_pem,
        &mtls.ca_key_pem,
        &device_id,
        &request.csr_pem,
    ) {
        Ok(issued) => issued,
        Err(error) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({"error": format!("invalid CSR: {error}")})),
            )
                .into_response();
        }
    };
    // Rotate the server leaf's private key, then swap the live acceptor.
    let rotated = match zeroclaw_tls::rotate_gateway_server_leaf(&mtls.dir) {
        Ok(materials) => materials,
        Err(error) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": format!("server key rotation failed: {error}")})),
            )
                .into_response();
        }
    };
    match crate::mtls_state::build_acceptor(
        &rotated.server_cert_path,
        &rotated.server_key_path,
        &rotated.ca_cert_path,
        &mtls.pinned_client_certs,
        mtls,
    ) {
        Ok(acceptor) => mtls.set_acceptor(acceptor),
        Err(error) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": format!("rebuilding the listener failed: {error}")})),
            )
                .into_response();
        }
    }
    // The old certificate stops working for new handshakes; the caller's own
    // connection stays up long enough to receive this response.
    let _ = mtls.revoke_for_renewal(&old_fingerprint);
    let name = request.device_name.unwrap_or_else(|| device_id.clone());
    if let Err(error) = mtls.record_issued(crate::mtls_state::IssuedCert {
        device_id: device_id.clone(),
        name,
        fingerprint: issued.fingerprint.clone(),
        created_at: chrono::Utc::now().to_rfc3339(),
        not_after: issued.not_after,
        revoked: false,
    }) {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(
                serde_json::json!({"error": format!("could not record the certificate: {error}")}),
            ),
        )
            .into_response();
    }
    Json(serde_json::json!({
        "cert_pem": issued.cert_pem,
        "ca_chain_pem": mtls.ca_cert_pem,
        "device_id": device_id,
        "gateway_id": mtls.gateway_id,
        "not_after": issued.not_after,
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
