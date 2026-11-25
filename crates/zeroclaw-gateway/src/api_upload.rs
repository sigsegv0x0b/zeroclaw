//! `POST /api/upload?agent=<alias>&filename=<name>` — dashboard and app
//! attachment upload.
//!
//! Accepts raw bytes (pictures or files) and writes them to
//! `<agent workspace>/webui_uploads/`. Images are typed by magic bytes, never
//! the client-declared Content-Type; the response carries `[IMAGE:<path>]` so
//! the multimodal provider path resolves them into vision content. Other
//! payloads are saved as files and returned with `[FILE:<path>]`, which the
//! agent can pass to its file tools.
//!
//! The saved filename is server-generated; a client filename contributes only
//! a sanitized display name and a non-image extension. Image acceptance uses
//! [`zeroclaw_api::media::image_mime_from_magic`] filtered through
//! [`zeroclaw_api::media::is_provider_image_mime`], the same contract channels
//! consult, so the endpoint cannot save an image the provider loader would
//! drop.

use axum::{
    Json,
    body::Bytes,
    extract::{Query, State},
    http::{HeaderMap, StatusCode},
};
use serde::{Deserialize, Serialize};
use zeroclaw_api::media::{image_mime_from_magic, is_provider_image_mime};

use super::AppState;
use super::api::require_auth;

/// Hard ceiling for the route's body limit, matching the upper clamp of
/// `MultimodalConfig::effective_limits` (20 MB). The configured limit is
/// enforced per-request inside the handler; this only bounds what axum will
/// buffer.
pub const UPLOAD_BODY_CEILING_BYTES: usize = 20 * 1024 * 1024;

#[derive(Deserialize)]
pub struct UploadQuery {
    /// Configured agent alias whose workspace receives the file. Required —
    /// mirrors the WebSocket chat contract (`/ws/chat?agent=`): no default
    /// agent exists.
    pub agent: Option<String>,
    /// Original display name. Used only for the extension of a non-image
    /// attachment and for the response's display name; never for the path.
    pub filename: Option<String>,
}

#[derive(Serialize)]
pub struct UploadResponse {
    /// Absolute path of the saved file inside the agent workspace.
    pub path: String,
    /// `[IMAGE:<path>]` or `[FILE:<path>]` marker ready to embed in a chat
    /// message.
    pub marker: String,
    /// `image` when the provider can load the payload as vision input,
    /// otherwise `file`.
    pub kind: String,
    /// Sanitized display name for the app.
    pub name: String,
    /// Saved byte length.
    pub size: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AttachmentKind {
    Image,
    File,
}

/// Why an upload payload was refused. Carries everything the HTTP layer needs
/// so the decision itself stays pure and unit-testable.
#[derive(Debug, PartialEq, Eq)]
pub enum UploadReject {
    Empty,
    TooLarge { limit_bytes: usize },
    NotAProviderImage,
}

/// The whole acceptance decision: which extension the payload earns, or why it
/// is refused. Pure — sniffs magic bytes only, so a client-supplied name or
/// Content-Type can never widen acceptance.
pub fn classify_upload(data: &[u8], max_bytes: usize) -> Result<&'static str, UploadReject> {
    if data.is_empty() {
        return Err(UploadReject::Empty);
    }
    if data.len() > max_bytes {
        return Err(UploadReject::TooLarge {
            limit_bytes: max_bytes,
        });
    }
    let mime = image_mime_from_magic(data).filter(|mime| is_provider_image_mime(mime));
    match mime {
        Some("image/png") => Ok("png"),
        Some("image/jpeg") => Ok("jpg"),
        Some("image/gif") => Ok("gif"),
        Some("image/webp") => Ok("webp"),
        // A sniffable-but-unsendable format (e.g. BMP) is refused the same as
        // an unknown one: the provider loader would drop the marker anyway.
        _ => Err(UploadReject::NotAProviderImage),
    }
}

/// Classify an attachment for the app's general file/picture upload. Images
/// the provider can load keep the `[IMAGE:]` marker; every other non-empty
/// payload is a file. The caller enforces the image-specific size limit after
/// this returns.
pub fn classify_attachment(data: &[u8]) -> AttachmentKind {
    match image_mime_from_magic(data).filter(|mime| is_provider_image_mime(mime)) {
        Some(_) => AttachmentKind::Image,
        None => AttachmentKind::File,
    }
}

/// Display name from a client-supplied filename. Path separators and control
/// characters are stripped; the saved path never uses this value.
pub fn display_attachment_name(filename: Option<&str>) -> String {
    let Some(raw) = filename else {
        return "attachment".to_string();
    };
    let name = raw
        .rsplit(['/', '\\'])
        .next()
        .unwrap_or("attachment")
        .chars()
        .filter(|ch| !ch.is_control())
        .take(120)
        .collect::<String>();
    let name = name.trim();
    if name.is_empty() {
        "attachment".to_string()
    } else {
        name.to_string()
    }
}

/// Extension for a non-image attachment. Only ASCII alphanumerics survive;
/// an absent extension becomes `bin`.
pub fn safe_attachment_stem(filename: Option<&str>) -> String {
    let name = display_attachment_name(filename);
    let stem = name.rsplit_once('.').map(|(stem, _)| stem).unwrap_or(&name);
    let cleaned = stem
        .chars()
        .filter(|ch| ch.is_ascii_alphanumeric() || *ch == '-' || *ch == '_')
        .take(40)
        .collect::<String>();
    if cleaned.is_empty() {
        "attachment".to_string()
    } else {
        cleaned
    }
}

pub fn safe_attachment_extension(filename: Option<&str>) -> String {
    let extension = filename
        .and_then(|name| name.rsplit_once('.').map(|(_, extension)| extension))
        .unwrap_or_default()
        .chars()
        .filter(char::is_ascii_alphanumeric)
        .take(10)
        .collect::<String>()
        .to_ascii_lowercase();
    if extension.is_empty() {
        "bin".to_string()
    } else {
        extension
    }
}

pub async fn handle_upload(
    State(state): State<AppState>,
    Query(query): Query<UploadQuery>,
    headers: HeaderMap,
    body: Bytes,
) -> Result<Json<UploadResponse>, (StatusCode, Json<serde_json::Value>)> {
    require_auth(&state, &headers)?;

    let Some(agent_alias) = query
        .agent
        .as_deref()
        .map(str::trim)
        .filter(|alias| !alias.is_empty())
    else {
        return Err(err(
            StatusCode::BAD_REQUEST,
            "missing_agent",
            "Missing required `agent` query parameter — pass `?agent=<alias>` \
             matching a configured [agents.<alias>] entry.",
        ));
    };

    // Resolve workspace and size limit from live config at use time.
    let (workspace, max_bytes) = {
        let cfg = state.config.read();
        if cfg.agent(agent_alias).is_none() {
            return Err(err(
                StatusCode::NOT_FOUND,
                "unknown_agent",
                &format!(
                    "Unknown agent `{agent_alias}` — no [agents.{agent_alias}] entry configured."
                ),
            ));
        }
        let (_, max_image_size_mb) = cfg.multimodal.effective_limits();
        (
            cfg.agent_workspace_dir(agent_alias),
            max_image_size_mb.saturating_mul(1024 * 1024),
        )
    };

    if body.is_empty() {
        return Err(err(
            StatusCode::BAD_REQUEST,
            "empty_body",
            "Empty request body.",
        ));
    }
    if body.len() > UPLOAD_BODY_CEILING_BYTES {
        return Err(err(
            StatusCode::PAYLOAD_TOO_LARGE,
            "payload_too_large",
            &format!(
                "Attachment exceeds the upload ceiling ({} bytes).",
                UPLOAD_BODY_CEILING_BYTES
            ),
        ));
    }

    let display_name = display_attachment_name(query.filename.as_deref());
    let attachment_kind = classify_attachment(&body);
    let (ext, marker_kind) = match attachment_kind {
        AttachmentKind::Image => {
            if body.len() > max_bytes {
                return Err(err(
                    StatusCode::PAYLOAD_TOO_LARGE,
                    "payload_too_large",
                    &format!(
                        "Image exceeds the configured multimodal.max_image_size_mb limit ({max_bytes} bytes)."
                    ),
                ));
            }
            let ext = image_mime_from_magic(&body)
                .filter(|mime| is_provider_image_mime(mime))
                .and_then(|mime| match mime {
                    "image/png" => Some("png"),
                    "image/jpeg" => Some("jpg"),
                    "image/gif" => Some("gif"),
                    "image/webp" => Some("webp"),
                    _ => None,
                })
                .unwrap_or("png");
            (ext.to_string(), "IMAGE")
        }
        AttachmentKind::File => (safe_attachment_extension(query.filename.as_deref()), "FILE"),
    };

    let save_dir = workspace.join("webui_uploads");
    if let Err(e) = tokio::fs::create_dir_all(&save_dir).await {
        ::zeroclaw_log::record!(
            WARN,
            ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Note)
                .with_outcome(::zeroclaw_log::EventOutcome::Unknown)
                .with_attrs(::serde_json::json!({"error": zeroclaw_runtime::security::scrub(&format!("{e}"))})),
            "Failed to create webui_uploads directory"
        );
        return Err(err(
            StatusCode::INTERNAL_SERVER_ERROR,
            "upload_io_error",
            "Failed to create upload directory.",
        ));
    }

    // Server-generated name; the sniffed extension keeps name and payload in
    // agreement, and nothing from the client reaches the filesystem path.
    let file_name = format!(
        "upload_{}_{}.{ext}",
        uuid::Uuid::new_v4().simple(),
        safe_attachment_stem(query.filename.as_deref())
    );
    let local_path = save_dir.join(&file_name);
    if let Err(e) = tokio::fs::write(&local_path, &body).await {
        ::zeroclaw_log::record!(
            WARN,
            ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Note)
                .with_outcome(::zeroclaw_log::EventOutcome::Unknown)
                .with_attrs(::serde_json::json!({"error": zeroclaw_runtime::security::scrub(&format!("{e}"))})),
            "Failed to save webui upload"
        );
        return Err(err(
            StatusCode::INTERNAL_SERVER_ERROR,
            "upload_io_error",
            "Failed to save upload.",
        ));
    }

    let path = local_path.display().to_string();
    let marker = format!("[{marker_kind}:{path}]");
    Ok(Json(UploadResponse {
        path,
        marker,
        kind: match attachment_kind {
            AttachmentKind::Image => "image".to_string(),
            AttachmentKind::File => "file".to_string(),
        },
        name: display_name,
        size: body.len(),
    }))
}

/// `{code, message}` mirrors the structured error envelope the dashboard's
/// central `apiFetch` already parses into a typed `ApiError`.
fn err(status: StatusCode, code: &str, message: &str) -> (StatusCode, Json<serde_json::Value>) {
    (
        status,
        Json(serde_json::json!({ "code": code, "message": message })),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const PNG: &[u8] = &[0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1A, b'\n', 0, 0];
    const JPEG: &[u8] = &[0xFF, 0xD8, 0xFF, 0xE0, 0, 0];
    const GIF: &[u8] = b"GIF89a\x00\x00";
    const WEBP: &[u8] = b"RIFF\x00\x00\x00\x00WEBP";
    const BMP: &[u8] = b"BM\x00\x00\x00\x00";

    #[test]
    fn accepts_the_four_provider_formats_with_matching_extensions() {
        assert_eq!(classify_upload(PNG, 1024), Ok("png"));
        assert_eq!(classify_upload(JPEG, 1024), Ok("jpg"));
        assert_eq!(classify_upload(GIF, 1024), Ok("gif"));
        assert_eq!(classify_upload(WEBP, 1024), Ok("webp"));
    }

    #[test]
    fn rejects_empty_body() {
        assert_eq!(classify_upload(&[], 1024), Err(UploadReject::Empty));
    }

    #[test]
    fn rejects_payload_over_the_configured_limit() {
        assert_eq!(
            classify_upload(PNG, PNG.len() - 1),
            Err(UploadReject::TooLarge {
                limit_bytes: PNG.len() - 1
            })
        );
        // Exactly at the limit is allowed.
        assert_eq!(classify_upload(PNG, PNG.len()), Ok("png"));
    }

    #[test]
    fn rejects_sniffable_but_provider_unsendable_formats() {
        // BMP sniffs to a recognized MIME the provider path refuses to send.
        assert_eq!(
            classify_upload(BMP, 1024),
            Err(UploadReject::NotAProviderImage)
        );
    }

    #[test]
    fn classifies_images_and_other_files() {
        assert_eq!(classify_attachment(PNG), AttachmentKind::Image);
        assert_eq!(classify_attachment(JPEG), AttachmentKind::Image);
        assert_eq!(classify_attachment(b"plain text"), AttachmentKind::File);
        assert_eq!(classify_attachment(b"%PDF-1.7"), AttachmentKind::File);
    }

    #[test]
    fn sanitizes_display_names_and_extensions() {
        assert_eq!(display_attachment_name(Some("../../evil.txt")), "evil.txt");
        assert_eq!(display_attachment_name(Some("   ")), "attachment");
        assert_eq!(safe_attachment_extension(Some("report.pdf")), "pdf");
        assert_eq!(safe_attachment_extension(Some("noext")), "bin");
        assert_eq!(safe_attachment_extension(Some("x.weird!")), "weird");
        assert_eq!(safe_attachment_stem(Some("note.txt")), "note");
        assert_eq!(safe_attachment_stem(Some("../../evil.txt")), "evil");
        assert_eq!(safe_attachment_stem(Some("   ")), "attachment");
    }

    #[test]
    fn rejects_unknown_bytes_regardless_of_size() {
        assert_eq!(
            classify_upload(b"<svg xmlns='http://www.w3.org/2000/svg'/>", 1024),
            Err(UploadReject::NotAProviderImage)
        );
        assert_eq!(
            classify_upload(b"plain text", 1024),
            Err(UploadReject::NotAProviderImage)
        );
    }
}
