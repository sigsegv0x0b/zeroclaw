//! One bearer-token extractor for the gateway's HTTP and WebSocket surfaces.
//!
//! Before this module, `/ws/chat`, `/ws/nodes`, ACP, the REST handlers and the
//! mTLS binding middleware each parsed the same carriers differently. A token
//! in `Authorization: bearer …` (lowercase), in a later `Authorization` header,
//! or in `Sec-WebSocket-Protocol: Bearer.…` would be seen by one layer and
//! missed by another — and the mTLS binding check would then disagree with the
//! handler it protects. The helpers here are the only place token carriers are
//! parsed.

use axum::http::{HeaderMap, header};

const AUTHORIZATION_SCHEME: &str = "Bearer ";
const BEARER_PROTOCOL: &str = "bearer.";

/// Bearer tokens carried by `Authorization` headers, in header order.
pub(crate) fn authorization_bearer_tokens(headers: &HeaderMap) -> Vec<&str> {
    headers
        .get_all(header::AUTHORIZATION)
        .iter()
        .filter_map(|value| value.to_str().ok())
        .filter_map(|value| strip_prefix_ignore_case(value, AUTHORIZATION_SCHEME))
        .filter(|token| !token.is_empty())
        .collect()
}

/// The first bearer token in an `Authorization` header.
pub(crate) fn authorization_bearer_token(headers: &HeaderMap) -> Option<&str> {
    authorization_bearer_tokens(headers).into_iter().next()
}

/// Every bearer candidate a WebSocket upgrade may carry: `Authorization`,
/// the `bearer.` subprotocol, then the `token` query parameter. Header order is
/// preserved, so a caller's precedence is the order of this vector.
pub(crate) fn websocket_bearer_tokens<'a>(
    headers: &'a HeaderMap,
    query_tokens: impl IntoIterator<Item = &'a str>,
) -> Vec<&'a str> {
    let mut tokens = authorization_bearer_tokens(headers);
    for protocol in headers
        .get_all(header::SEC_WEBSOCKET_PROTOCOL)
        .iter()
        .filter_map(|value| value.to_str().ok())
        .flat_map(|protocols| protocols.split(','))
    {
        if let Some(token) = strip_prefix_ignore_case(protocol.trim(), BEARER_PROTOCOL)
            && !token.is_empty()
        {
            tokens.push(token);
        }
    }
    tokens.extend(query_tokens.into_iter().filter(|token| !token.is_empty()));
    tokens
}

/// The first bearer candidate for a WebSocket upgrade.
pub(crate) fn websocket_bearer_token<'a>(
    headers: &'a HeaderMap,
    query_token: Option<&'a str>,
) -> Option<&'a str> {
    websocket_bearer_tokens(headers, query_token)
        .into_iter()
        .next()
}

fn strip_prefix_ignore_case<'a>(value: &'a str, prefix: &str) -> Option<&'a str> {
    value
        .get(..prefix.len())
        .filter(|head| head.eq_ignore_ascii_case(prefix))
        .map(|_| &value[prefix.len()..])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn authorization_scheme_is_case_insensitive_and_reads_every_header() {
        let mut headers = HeaderMap::new();
        headers.append(header::AUTHORIZATION, "bearer first".parse().unwrap());
        headers.append(header::AUTHORIZATION, "BEARER second".parse().unwrap());
        assert_eq!(
            authorization_bearer_tokens(&headers),
            vec!["first", "second"]
        );
    }

    #[test]
    fn websocket_carriers_keep_header_subprotocol_query_order() {
        let mut headers = HeaderMap::new();
        headers.append(header::AUTHORIZATION, "Bearer header".parse().unwrap());
        headers.append(
            header::SEC_WEBSOCKET_PROTOCOL,
            "zeroclaw.v1, bearer.sub".parse().unwrap(),
        );
        assert_eq!(
            websocket_bearer_tokens(&headers, Some("query")),
            vec!["header", "sub", "query"]
        );
        assert_eq!(
            websocket_bearer_token(&headers, Some("query")),
            Some("header")
        );
    }

    #[test]
    fn an_empty_query_token_is_not_a_bearer() {
        assert_eq!(websocket_bearer_token(&HeaderMap::new(), Some("")), None);
    }
}
