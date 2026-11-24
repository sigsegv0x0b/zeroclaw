//! Pairing-code-gated certificate enrollment for the gateway mTLS listener.
//!
//! This listener is deliberately server-authenticated TLS only: a client has
//! no certificate yet. The app fetches the CA, compares the SAS derived from
//! the pairing code and that CA, then posts a CSR. A successful enrollment
//! returns the signed client certificate, the CA chain, the gateway identity,
//! and a bearer token, so one pairing code bootstraps both layers.

use std::net::SocketAddr;
use std::sync::Arc;

use axum::{
    Json, Router,
    extract::{ConnectInfo, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post},
};
use serde::{Deserialize, Serialize};
use tokio_rustls::TlsAcceptor;
use tokio_util::sync::CancellationToken;
use zeroclaw_tls::{ServerConfigParams, build_tls_acceptor};

use crate::ServeTasks;
use crate::mtls_state::{IssuedCert, MtlsState};

#[derive(Serialize)]
struct CaResponse {
    ca_chain_pem: String,
    gateway_id: String,
}

#[derive(Deserialize)]
struct EnrollRequest {
    pairing_code: String,
    csr_pem: String,
    #[serde(default)]
    device_name: Option<String>,
}

#[derive(Serialize)]
struct EnrollResponse {
    cert_pem: String,
    ca_chain_pem: String,
    device_id: String,
    gateway_id: String,
    not_after: i64,
    token: String,
}

async fn handle_ca(State(state): State<Arc<MtlsState>>) -> Json<CaResponse> {
    Json(CaResponse {
        ca_chain_pem: state.ca_cert_pem.clone(),
        gateway_id: state.gateway_id.clone(),
    })
}

async fn handle_enroll(
    State(state): State<Arc<MtlsState>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Json(request): Json<EnrollRequest>,
) -> Response {
    if !state.allow_enroll(peer.ip()) {
        return (
            StatusCode::TOO_MANY_REQUESTS,
            Json(serde_json::json!({"error": "too many enrollment attempts; retry later"})),
        )
            .into_response();
    }
    let code = request.pairing_code.trim();
    if code.is_empty() {
        return (
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({"error": "pairing code is required"})),
        )
            .into_response();
    }
    let token = match state.pairing.try_pair(code, &peer.ip().to_string()).await {
        Ok(Some(token)) => token,
        Ok(None) => {
            return (
                StatusCode::UNAUTHORIZED,
                Json(serde_json::json!({"error": "invalid or already-used pairing code"})),
            )
                .into_response();
        }
        Err(retry_after) => {
            return (
                StatusCode::TOO_MANY_REQUESTS,
                Json(serde_json::json!({
                    "error": "too many failed pairing attempts",
                    "retry_after": retry_after,
                })),
            )
                .into_response();
        }
    };

    // Persist the bearer token before issuing material. Without this the token
    // only lives in the pairing guard's memory and every enrolled device is
    // unpaired by the next gateway restart.
    if let Err(error) = state.persist_pairing_tokens().await {
        state.pairing.revoke_token(&token);
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({
                "error": format!("could not persist the pairing token: {error}")
            })),
        )
            .into_response();
    }

    let device_id = format!("dev-{}", &uuid::Uuid::new_v4().simple().to_string()[..8]);
    let issued = match zeroclaw_tls::sign_csr(
        &state.ca_cert_pem,
        &state.ca_key_pem,
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
    let name = request.device_name.unwrap_or_else(|| device_id.clone());
    if let Err(error) = state.record_issued(IssuedCert {
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

    state.enrollment.close_after_pairing();

    Json(EnrollResponse {
        cert_pem: issued.cert_pem,
        ca_chain_pem: state.ca_cert_pem.clone(),
        device_id,
        gateway_id: state.gateway_id.clone(),
        not_after: issued.not_after,
        token,
    })
    .into_response()
}

/// Build the server-authenticated TLS acceptor for enrollment.
pub(crate) fn build_enrollment_acceptor(
    cert_path: &std::path::Path,
    key_path: &std::path::Path,
) -> anyhow::Result<TlsAcceptor> {
    build_tls_acceptor(&ServerConfigParams {
        cert_path: cert_path.to_string_lossy().to_string(),
        key_path: key_path.to_string_lossy().to_string(),
        client_auth: None,
    })
}

/// Serve an already-bound enrollment listener until the window or gateway stops.
pub(crate) fn serve(
    listener: tokio::net::TcpListener,
    acceptor: TlsAcceptor,
    state: Arc<MtlsState>,
    window: CancellationToken,
    mut shutdown: tokio::sync::watch::Receiver<bool>,
    tasks: ServeTasks,
) {
    let app = Router::new()
        .route("/enroll/ca", get(handle_ca))
        .route("/enroll", post(handle_enroll))
        .with_state(state);
    let mut service = app.into_make_service_with_connect_info::<SocketAddr>();
    ::zeroclaw_log::record!(
        INFO,
        ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Note).with_attrs(
            serde_json::json!({
                "bind": listener.local_addr().map(|addr| addr.to_string()).unwrap_or_default(),
            })
        ),
        "Gateway mTLS enrollment listener up"
    );
    zeroclaw_spawn::spawn!(async move {
        loop {
            let accepted = tokio::select! {
                _ = window.cancelled() => break,
                _ = shutdown.changed() => break,
                conn = listener.accept() => conn,
            };
            let (tcp_stream, remote_addr) = match accepted {
                Ok(pair) => pair,
                Err(_) => continue,
            };
            let acceptor = acceptor.clone();
            let svc = match tower::MakeService::<
                SocketAddr,
                hyper::Request<hyper::body::Incoming>,
            >::make_service(&mut service, remote_addr)
            .await
            {
                Ok(svc) => svc,
                Err(_) => continue,
            };
            let executor = tasks.clone();
            tasks.spawn(async move {
                let tls_stream = match acceptor.accept(tcp_stream).await {
                    Ok(stream) => stream,
                    Err(_) => return,
                };
                let hyper_svc = hyper::service::service_fn(
                    move |req: hyper::Request<hyper::body::Incoming>| {
                        let svc = svc.clone();
                        async move { tower::ServiceExt::oneshot(svc, req).await }
                    },
                );
                let _ = hyper_util::server::conn::auto::Builder::new(executor)
                    .serve_connection_with_upgrades(
                        hyper_util::rt::TokioIo::new(tls_stream),
                        hyper_svc,
                    )
                    .await;
            });
        }
    });
}
