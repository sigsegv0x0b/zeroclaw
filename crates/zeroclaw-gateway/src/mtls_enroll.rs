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

async fn handle_ca(
    State(state): State<Arc<MtlsState>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
) -> Response {
    if !state.allow_enroll(peer.ip()) {
        return (
            StatusCode::TOO_MANY_REQUESTS,
            Json(serde_json::json!({"error": "too many enrollment attempts; retry later"})),
        )
            .into_response();
    }
    Json(CaResponse {
        ca_chain_pem: state.ca_cert_pem.clone(),
        gateway_id: state.gateway_id.clone(),
    })
    .into_response()
}

async fn handle_enroll(
    State(state): State<Arc<MtlsState>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Json(request): Json<EnrollRequest>,
) -> Response {
    let refuse = |status: StatusCode, body: serde_json::Value| (status, Json(body)).into_response();
    if !state.allow_enroll(peer.ip()) {
        return refuse(
            StatusCode::TOO_MANY_REQUESTS,
            serde_json::json!({"error": "too many enrollment attempts; retry later"}),
        );
    }
    // A pin allowlist admits only the leaves it names, so a certificate issued
    // here could never connect. Refuse before the code is spent.
    if !state.pinned_client_certs.is_empty() {
        return refuse(
            StatusCode::CONFLICT,
            serde_json::json!({
                "error": "in-band enrollment is disabled because [gateway.mtls].pinned_client_certs \
                          is configured; issue client certificates out of band"
            }),
        );
    }
    let code = request.pairing_code.trim();
    if code.is_empty() {
        return refuse(
            StatusCode::UNAUTHORIZED,
            serde_json::json!({"error": "pairing code is required"}),
        );
    }
    let client = peer.ip().to_string();
    // Reserve, don't redeem: every later failure drops the reservation and
    // restores the code, so a bad CSR or a disk error never burns it and never
    // leaves a live token nobody received.
    let reservation = match state.pairing.reserve_pair(code, &client).await {
        Ok(Some(reservation)) => reservation,
        Ok(None) => {
            state.enrollment.record_failed_attempt();
            return refuse(
                StatusCode::UNAUTHORIZED,
                serde_json::json!({"error": "invalid or already-used pairing code"}),
            );
        }
        Err(retry_after) => {
            return refuse(
                StatusCode::TOO_MANY_REQUESTS,
                serde_json::json!({
                    "error": "too many failed pairing attempts",
                    "retry_after": retry_after,
                }),
            );
        }
    };
    let token_hash = reservation.token_hash();

    let device_id = format!("dev-{}", &uuid::Uuid::new_v4().simple().to_string()[..8]);
    let issued = match zeroclaw_tls::sign_csr(
        &state.ca_cert_pem,
        &state.ca_key_pem,
        &device_id,
        &request.csr_pem,
    ) {
        Ok(issued) => issued,
        Err(error) => {
            return refuse(
                StatusCode::BAD_REQUEST,
                serde_json::json!({"error": format!("invalid CSR: {error}")}),
            );
        }
    };
    let name = request
        .device_name
        .map(|name| name.trim().chars().take(64).collect::<String>())
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| device_id.clone());
    if let Err(error) =
        state.audit_issuance_attempted(&device_id, &issued.fingerprint, issued.not_after)
    {
        return refuse(
            StatusCode::INTERNAL_SERVER_ERROR,
            serde_json::json!({"error": format!("could not audit the certificate issuance: {error}")}),
        );
    }
    if let Err(error) = state.record_issued(IssuedCert {
        device_id: device_id.clone(),
        name: name.clone(),
        fingerprint: issued.fingerprint.clone(),
        created_at: chrono::Utc::now().to_rfc3339(),
        not_after: issued.not_after,
        revoked: false,
        token_hash: Some(token_hash.clone()),
        supersedes: None,
    }) {
        return refuse(
            StatusCode::INTERNAL_SERVER_ERROR,
            serde_json::json!({"error": format!("could not record the certificate: {error}")}),
        );
    }

    let token = reservation.commit();
    // Undo a committed issuance: the certificate goes to the CRL and the
    // token, with it, out of the paired set.
    let roll_back = |what: &str, error: String| {
        let rollback = state
            .revoke(&issued.fingerprint)
            .err()
            .map(|revoke_error| format!(" (rollback failed: {revoke_error:#})"))
            .unwrap_or_default();
        state.pairing.revoke_token_hash(&token_hash);
        refuse(
            StatusCode::INTERNAL_SERVER_ERROR,
            serde_json::json!({"error": format!("could not {what}: {error}{rollback}")}),
        )
    };
    if let Err(error) = state.register_enrolled_device(&token_hash, &device_id, &name, &client) {
        return roll_back("register the device", format!("{error:#}"));
    }
    // Persist the bearer token before returning it. Without this the token
    // only lives in the pairing guard's memory and every enrolled device is
    // unpaired by the next gateway restart.
    if let Err(error) = state.persist_pairing_tokens().await {
        return roll_back("persist the pairing token", format!("{error:#}"));
    }
    if let Err(error) = state.audit_issued(&device_id, &issued.fingerprint, issued.not_after, false)
    {
        return roll_back("write the certificate audit event", format!("{error:#}"));
    }

    state.enrollment.close_after_pairing();
    ::zeroclaw_log::record!(
        INFO,
        ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Note).with_attrs(
            serde_json::json!({
                "device_id": device_id,
                "fingerprint": issued.fingerprint,
                "peer": client,
            })
        ),
        "mTLS client certificate issued by pairing-code enrollment"
    );

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
            let window = window.clone();
            let mut stopping = shutdown.clone();
            tasks.spawn(async move {
                // Closing the window ends accepted connections too; otherwise
                // a kept-alive connection could keep redeeming codes after the
                // operator saw the port close.
                let serve = async {
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
                };
                tokio::select! {
                    _ = window.cancelled() => {}
                    _ = stopping.changed() => {}
                    () = serve => {}
                }
            });
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mtls_state::BindingRefusal;
    use zeroclaw_config::pairing::{PairingCodePolicy, PairingGuard};

    struct Fixture {
        _dir: tempfile::TempDir,
        state: Arc<MtlsState>,
        pairing: Arc<PairingGuard>,
    }

    fn fixture(pinned: Vec<String>) -> Fixture {
        let dir = tempfile::tempdir().expect("temp dir");
        let materials = zeroclaw_tls::ensure_gateway_materials(
            dir.path(),
            &zeroclaw_tls::CaKeyProtection::None,
        )
        .expect("materials");
        let pairing = Arc::new(PairingGuard::new(true, &[], PairingCodePolicy::default()));
        let state = MtlsState::load(
            dir.path().to_path_buf(),
            materials.gateway_id,
            std::fs::read_to_string(&materials.ca_cert_path).expect("ca"),
            zeroclaw_tls::load_ca_key_pem(
                &materials.ca_key_path,
                &zeroclaw_tls::CaKeyProtection::None,
            )
            .expect("ca key"),
            Arc::clone(&pairing),
            pinned,
            dir.path().join("revoked"),
            "127.0.0.1".to_string(),
            0,
            materials.server_cert_path,
            materials.server_key_path,
        )
        .expect("state");
        Fixture {
            _dir: dir,
            state,
            pairing,
        }
    }

    fn peer() -> ConnectInfo<SocketAddr> {
        ConnectInfo("198.51.100.4:5000".parse().expect("addr"))
    }

    async fn enroll(fixture: &Fixture, code: &str, csr_pem: String) -> Response {
        handle_enroll(
            State(Arc::clone(&fixture.state)),
            peer(),
            Json(EnrollRequest {
                pairing_code: code.to_string(),
                csr_pem,
                device_name: Some("phone".to_string()),
            }),
        )
        .await
    }

    fn new_code(fixture: &Fixture) -> String {
        fixture
            .pairing
            .generate_new_pairing_code(PairingCodePolicy::default())
            .expect("pairing enabled")
    }

    #[tokio::test]
    async fn a_rejected_csr_keeps_the_code_and_mints_no_token() {
        let fixture = fixture(Vec::new());
        let code = new_code(&fixture);
        let response = enroll(&fixture, &code, "not a csr".to_string()).await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        assert_eq!(
            fixture.pairing.pairing_code().as_deref(),
            Some(code.as_str())
        );
        assert!(fixture.pairing.tokens().is_empty(), "no orphaned token");
        assert!(fixture.state.read_ledger().is_empty());
    }

    #[tokio::test]
    async fn enrollment_binds_the_token_to_the_certificate() {
        let fixture = fixture(Vec::new());
        let code = new_code(&fixture);
        let (csr, _key) = zeroclaw_tls::generate_client_csr("phone").expect("csr");
        let response = enroll(&fixture, &code, csr).await;
        assert_eq!(response.status(), StatusCode::OK);
        let body = axum::body::to_bytes(response.into_body(), usize::MAX)
            .await
            .expect("body");
        let body: serde_json::Value = serde_json::from_slice(&body).expect("json");
        let token = body["token"].as_str().expect("token");
        let hash = PairingGuard::token_hash(token);

        assert!(fixture.pairing.token_is_paired(token));
        assert!(
            fixture.pairing.pairing_code().is_none(),
            "the code is spent"
        );
        let ledger = fixture.state.read_ledger();
        assert_eq!(ledger.len(), 1);
        assert_eq!(ledger[0].token_hash.as_deref(), Some(hash.as_str()));
        assert_eq!(ledger[0].device_id, body["device_id"].as_str().expect("id"));
        assert_eq!(
            fixture
                .state
                .check_binding(std::slice::from_ref(&hash), None),
            Err(BindingRefusal::CertificateRequired)
        );
        assert_eq!(
            fixture
                .state
                .check_binding(&[hash], Some(&ledger[0].fingerprint)),
            Ok(())
        );
    }

    #[tokio::test]
    async fn pinned_client_certificates_refuse_enrollment_without_spending_the_code() {
        let fixture = fixture(vec!["ab".repeat(32)]);
        let code = new_code(&fixture);
        let (csr, _key) = zeroclaw_tls::generate_client_csr("phone").expect("csr");
        let response = enroll(&fixture, &code, csr).await;
        assert_eq!(response.status(), StatusCode::CONFLICT);
        assert_eq!(
            fixture.pairing.pairing_code().as_deref(),
            Some(code.as_str())
        );
    }
}
