//! Token-to-certificate binding for devices enrolled over the mTLS listener.
//!
//! Enrollment issues a bearer token together with a client certificate. The
//! token is bound to that device's certificates in the mTLS ledger, so it is
//! refused on every listener that has no client certificate (the plain
//! gateway, the relay-served dashboard) and with any other device's
//! certificate. Revoking the certificate therefore also ends what the token
//! can reach, instead of leaving a bearer that still works on the plain port.
//!
//! This layer reads every carrier a gateway handler accepts a bearer from:
//! `Authorization: Bearer`, the `bearer.` WebSocket subprotocol, and the
//! `token` query parameter. It only refuses; authentication stays with the
//! handlers.

use axum::{
    Json,
    extract::{Query, Request, State},
    http::StatusCode,
    middleware::Next,
    response::{IntoResponse, Response},
};
use zeroclaw_config::pairing::PairingGuard;

use crate::AppState;
use crate::mtls_state::PeerCertFingerprint;

pub(crate) async fn enforce(
    State(state): State<AppState>,
    request: Request,
    next: Next,
) -> Response {
    let Some(mtls) = state.mtls.as_ref() else {
        return next.run(request).await;
    };
    let presented: Vec<String> = presented_tokens(&request)
        .iter()
        .map(|token| PairingGuard::token_hash(token))
        .collect();
    let peer = request
        .extensions()
        .get::<PeerCertFingerprint>()
        .map(|peer| peer.0.as_str());
    if let Err(refusal) = mtls.check_binding(&presented, peer) {
        return (
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({ "error": refusal.message() })),
        )
            .into_response();
    }
    next.run(request).await
}

/// Every bearer candidate in the request, as a handler would extract it.
///
/// This uses the same extractor as `/ws/chat` and `/ws/nodes`; the only
/// route-specific choice is whether `?token=` is a bearer carrier, which only
/// the WebSocket routes treat it as. On every other route a `token` parameter
/// is unrelated data, and treating it as a presented bearer would let a bound
/// certificate fail requests that never intended to authenticate.
fn presented_tokens(request: &Request) -> Vec<String> {
    let websocket_route = request
        .uri()
        .path()
        .split('/')
        .any(|segment| segment == "ws");
    let query_tokens: Vec<String> = if websocket_route {
        Query::<Vec<(String, String)>>::try_from_uri(request.uri())
            .map(|Query(pairs)| {
                pairs
                    .into_iter()
                    .filter(|(name, value)| name == "token" && !value.is_empty())
                    .map(|(_, value)| value)
                    .collect()
            })
            .unwrap_or_default()
    } else {
        Vec::new()
    };
    let query_refs: Vec<&str> = query_tokens.iter().map(String::as_str).collect();
    let tokens = if websocket_route {
        crate::token_auth::websocket_bearer_tokens(request.headers(), query_refs)
    } else {
        crate::token_auth::authorization_bearer_tokens(request.headers())
    };
    tokens.into_iter().map(str::to_string).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::header;

    fn request(builder: axum::http::request::Builder) -> Request {
        builder.body(axum::body::Body::empty()).expect("request")
    }

    #[test]
    fn every_bearer_carrier_is_read() {
        let req = request(
            axum::http::Request::builder()
                .uri("/ws/chat?session=1&token=zc_query")
                .header(header::AUTHORIZATION, "bearer zc_header")
                .header(
                    header::SEC_WEBSOCKET_PROTOCOL,
                    "zeroclaw.v1, Bearer.zc_proto",
                ),
        );
        let mut tokens = presented_tokens(&req);
        tokens.sort();
        assert_eq!(tokens, vec!["zc_header", "zc_proto", "zc_query"]);
    }

    #[test]
    fn an_unrelated_token_query_parameter_is_not_a_bearer() {
        let req =
            request(axum::http::Request::builder().uri("/api/webhooks/verify?token=not-a-bearer"));
        assert!(
            presented_tokens(&req).is_empty(),
            "only WebSocket routes read a bearer from the token query parameter"
        );
    }

    #[test]
    fn a_request_without_a_bearer_presents_nothing() {
        let req = request(
            axum::http::Request::builder()
                .uri("/api/status?q=1")
                .header(header::SEC_WEBSOCKET_PROTOCOL, "zeroclaw.v1"),
        );
        assert!(presented_tokens(&req).is_empty());
    }
}
