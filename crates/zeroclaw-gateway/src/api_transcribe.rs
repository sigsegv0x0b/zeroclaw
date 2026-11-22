//! `POST /api/transcribe` — decode a phone's recorded voice with the
//! gateway's configured speech-to-text provider.
//!
//! The Android client's "ZeroClaw" voice backend records WAV on the phone and
//! posts the bytes here, so STT credentials stay on the gateway and the phone
//! never talks to a transcription provider directly.

use axum::{
    Json,
    body::Bytes,
    extract::State,
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use serde_json::json;
use zeroclaw_channels::transcription::TranscriptionManager;

use crate::AppState;

/// Whisper-compatible providers cap uploads at 25 MB; the route body limit
/// mirrors that and a phone utterance is far smaller.
pub const TRANSCRIBE_BODY_CEILING_BYTES: usize = 25 * 1024 * 1024;

pub async fn handle_transcribe(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    if let Err(denied) = crate::api::require_auth(&state, &headers) {
        return denied.into_response();
    }
    if body.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({"error": "empty audio body"})),
        )
            .into_response();
    }

    let config = state.config.read().clone();
    if !config.transcription.enabled {
        return (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(json!({
                "error": "transcription is disabled on the gateway; set \
                          [transcription] enabled = true and configure a provider"
            })),
        )
            .into_response();
    }

    let manager = match TranscriptionManager::from_config_for_agent(
        &config,
        config.resolved_runtime_agent_alias(),
    ) {
        Ok(manager) => manager,
        Err(error) => {
            return (
                StatusCode::SERVICE_UNAVAILABLE,
                Json(json!({"error": format!("transcription is not configured: {error}")})),
            )
                .into_response();
        }
    };

    match manager.transcribe(&body, "voice.wav").await {
        Ok(text) => Json(json!({"text": text})).into_response(),
        Err(error) => (
            StatusCode::BAD_GATEWAY,
            Json(json!({"error": format!("transcription failed: {error}")})),
        )
            .into_response(),
    }
}
