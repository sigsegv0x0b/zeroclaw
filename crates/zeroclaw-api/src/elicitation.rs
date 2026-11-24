//! ACP elicitation primitives.
//!
//! Implements the form-mode subset of ACP `elicitation/create` that
//! ZeroClaw uses for choice and free-form prompts. See the ACP elicitation
//! RFD: <https://agentclientprotocol.com/rfds/elicitation>
//! for the design rationale.

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// Capability block parsed from `initialize.clientCapabilities.elicitation`.
///
/// Per the RFD's backward-compat rule, an empty object (`{}`) is
/// treated as form-only. A missing parent key is treated as no
/// support (both `false`).
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct ElicitationCapabilities {
    pub form: bool,
    pub url: bool,
}

impl ElicitationCapabilities {
    /// Parse the `clientCapabilities.elicitation` JSON value.
    /// Pass `None` when the parent key is absent.
    ///
    /// Sub-key presence is checked structurally — `{"form": {}}` and
    /// `{"form": null}` both count as advertised. ACP itself encodes
    /// sub-capabilities as objects (`"form": {}`) and has no "disabled"
    /// shape, so we don't try to inspect the sub-value's type.
    pub fn from_value(v: Option<&Value>) -> Self {
        let Some(v) = v else {
            return Self::default();
        };
        let Some(obj) = v.as_object() else {
            return Self::default();
        };
        if obj.is_empty() {
            // RFD backward-compat: empty object == form only.
            return Self {
                form: true,
                url: false,
            };
        }
        Self {
            form: obj.contains_key("form"),
            url: obj.contains_key("url"),
        }
    }
}

/// Elicitation transport mode.
///
/// In-tree callers only emit `Form`. `Url` is defined so the wire types are
/// complete and so a stray future caller compiles, but the send-site in
/// `AcpChannel` asserts the mode is `Form`.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ElicitationMode {
    Form,
    Url,
}

/// Params for an outbound `elicitation/create` JSON-RPC request.
///
/// Only the session-scoped variant is modeled; no in-tree caller uses
/// request-scoped elicitation (auth/config phase).
#[derive(Debug, Clone, Serialize)]
pub struct ElicitationRequest {
    #[serde(rename = "sessionId")]
    pub session_id: String,
    pub mode: ElicitationMode,
    pub message: String,
    #[serde(rename = "requestedSchema")]
    pub requested_schema: Value,
}

/// Response to an `elicitation/create` request.
///
/// Three-action model per the RFD. `Decline` and `Cancel` both
/// collapse to `Ok(None)` at the `Channel::request_choice` layer;
/// callers that need the distinction can read the response directly.
#[derive(Debug, Deserialize)]
#[serde(tag = "action", rename_all = "lowercase")]
pub enum ElicitationResponse {
    Accept { content: Value },
    Decline,
    Cancel,
}

// ── Schema helpers ──────────────────────────────────────────────
//
// These helpers build the JSON Schema payload for the `requested_schema`
// field of an `elicitation/create` request. They live here (not in any
// single channel impl) so every channel that speaks elicitation/create
// — the ACP channel and Zerocode's RPC channel both qualify — gets the
// exact same on-the-wire shape from one source. Adding a third channel
// (gateway WS, future plugin) must call these helpers, not roll its own.

/// Property names we refuse to put into a form-mode elicitation schema.
///
/// Per the ACP RFD and MCP's parent spec, form-mode elicitation MUST NOT
/// be used for sensitive data (credentials, API keys, etc.). All in-tree
/// callers ship a fixed `"choice"` / `"choices"` property name,
/// so a match against this list is a contract violation by an in-tree
/// caller, not user-controlled input. We `debug_assert!` rather than
/// `bail!` so production builds aren't degraded by a string scan.
pub const SENSITIVE_PROPERTY_NAMES: &[&str] = &[
    "password",
    "token",
    "secret",
    "api_key",
    "apiKey",
    "credential",
    "credentials",
    "auth",
    "authorization",
    "private_key",
    "privateKey",
];

/// Property used by the "select an option or type your own" form.
pub const CUSTOM_CHOICE_PROPERTY: &str = "custom";

/// Property used by free-form-only elicitation.
pub const FREE_FORM_PROPERTY: &str = "answer";

/// Build the restricted schema for a single-select question that also accepts
/// a typed answer. The choice and `custom` properties are both optional; the
/// receiver requires at least one to be present in an accepted response.
pub fn single_select_or_custom_schema(choices: &[String]) -> Value {
    let mut schema = single_select_schema(choices);
    add_optional_custom_property(&mut schema, CUSTOM_CHOICE_PROPERTY, "Type your own");
    schema
}

/// Build the restricted schema for a multi-select question that also accepts
/// a typed answer. Unlike [`multi_select_schema`], the array is optional and
/// may be empty because `custom` can satisfy the question alone.
pub fn multi_select_or_custom_schema(choices: &[String], max_items: usize) -> Value {
    let mut schema =
        multi_select_schema_with_property_name("choices", choices, 0, max_items.max(1));
    add_optional_custom_property(&mut schema, CUSTOM_CHOICE_PROPERTY, "Type your own");
    schema
}

/// Build the restricted schema for a free-form answer. Used when `ask_user`
/// is called without `choices` on a transport that only has form-mode
/// elicitation (gateway WebSocket, ACP form clients).
pub fn free_form_schema(property: &str, title: &str) -> Value {
    debug_assert!(
        !SENSITIVE_PROPERTY_NAMES.contains(&property),
        "sensitive property name '{property}' in form-mode elicitation schema"
    );
    serde_json::json!({
        "type": "object",
        "properties": {
            property: {
                "type": "string",
                "title": title,
                "minLength": 1,
            }
        },
        "required": [property],
    })
}

fn add_optional_custom_property(schema: &mut Value, property: &str, title: &str) {
    debug_assert!(
        !SENSITIVE_PROPERTY_NAMES.contains(&property),
        "sensitive property name '{property}' in form-mode elicitation schema"
    );
    if let Some(object) = schema.as_object_mut() {
        // The choice is no longer mandatory: `custom` can answer instead.
        object.remove("required");
        if let Some(properties) = object.get_mut("properties").and_then(Value::as_object_mut) {
            properties.insert(
                property.to_string(),
                serde_json::json!({ "type": "string", "title": title }),
            );
        }
    }
}

/// Decode a single-select-or-custom `accept` payload. A non-empty `custom`
/// answer overrides the selected choice, matching the app's "Other" field.
pub fn decode_single_select_or_custom_accept(
    content: &Value,
    choices: &[String],
) -> anyhow::Result<String> {
    if let Some(text) = custom_answer(content) {
        return Ok(text);
    }
    decode_single_select_accept(content, choices)
}

/// Decode a multi-select-or-custom `accept` payload. Selected indices are
/// validated against `choices`; a typed answer is appended when present.
pub fn decode_multi_select_or_custom_accept(
    content: &Value,
    choices: &[String],
    min_items: usize,
    max_items: usize,
) -> anyhow::Result<Vec<String>> {
    let mut answers = match content.get("choices") {
        None | Some(Value::Null) => Vec::new(),
        Some(Value::Array(_)) => decode_multi_select_accept(content, choices)?,
        Some(_) => anyhow::bail!("elicitation accept content.choices must be an array"),
    };
    if let Some(text) = custom_answer(content)
        && !answers.iter().any(|answer| answer == &text)
    {
        answers.push(text);
    }
    let min_items = min_items.max(1);
    if answers.len() < min_items {
        anyhow::bail!(
            "elicitation accept returned {} choice(s); at least {min_items} required",
            answers.len()
        );
    }
    if answers.len() > max_items.max(1) {
        anyhow::bail!(
            "elicitation accept returned {} choice(s); at most {} allowed",
            answers.len(),
            max_items.max(1)
        );
    }
    Ok(answers)
}

/// Decode a free-form `accept` payload for `property`.
pub fn decode_free_form_accept(content: &Value, property: &str) -> anyhow::Result<String> {
    content
        .get(property)
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .map(str::to_string)
        .ok_or_else(|| {
            anyhow::Error::msg(format!(
                "elicitation accept missing non-empty content.{property} string"
            ))
        })
}

fn custom_answer(content: &Value) -> Option<String> {
    content
        .get(CUSTOM_CHOICE_PROPERTY)
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .map(str::to_string)
}

/// Build the restricted JSON Schema for a single-select enum elicitation.
///
/// `choices` is the user-visible list. The wire-format `const` values
/// are index-based (`choice-0`, `choice-1`, …) so the response → text
/// round-trip survives non-unique or empty display strings.
pub fn single_select_schema(choices: &[String]) -> Value {
    single_select_schema_with_property_name("choice", choices)
}

/// Internal — exposed for the sensitive-name trip-wire test.
pub fn single_select_schema_with_property_name(property: &str, choices: &[String]) -> Value {
    debug_assert!(
        !SENSITIVE_PROPERTY_NAMES.contains(&property),
        "sensitive property name '{property}' in form-mode elicitation schema"
    );
    let one_of: Vec<Value> = choices
        .iter()
        .enumerate()
        .map(|(i, text)| serde_json::json!({ "const": format!("choice-{i}"), "title": text }))
        .collect();
    serde_json::json!({
        "type": "object",
        "properties": {
            property: {
                "type": "string",
                "title": "Choice",
                "oneOf": one_of,
            }
        },
        "required": [property],
    })
}

/// Build the restricted JSON Schema for a multi-select enum elicitation.
///
/// Index-based `const` values mirror `single_select_schema` so the
/// response → text round-trip survives duplicates.
pub fn multi_select_schema(choices: &[String], min_items: usize, max_items: usize) -> Value {
    multi_select_schema_with_property_name("choices", choices, min_items, max_items)
}

/// Internal — mirrors `single_select_schema_with_property_name` so the
/// `SENSITIVE_PROPERTY_NAMES` trip-wire guards the multi-select path too,
/// keeping the invariant uniform for future callers.
pub fn multi_select_schema_with_property_name(
    property: &str,
    choices: &[String],
    min_items: usize,
    max_items: usize,
) -> Value {
    debug_assert!(
        !SENSITIVE_PROPERTY_NAMES.contains(&property),
        "sensitive property name '{property}' in form-mode elicitation schema"
    );
    let any_of: Vec<Value> = choices
        .iter()
        .enumerate()
        .map(|(i, text)| serde_json::json!({ "const": format!("choice-{i}"), "title": text }))
        .collect();
    serde_json::json!({
        "type": "object",
        "properties": {
            property: {
                "type": "array",
                "title": "Choices",
                "minItems": min_items,
                "maxItems": max_items,
                "items": { "anyOf": any_of },
            }
        },
        "required": [property],
    })
}

/// Decode the accepted `content` payload of an `elicitation/create`
/// single-select response back into the original display text.
///
/// Expects `content.choice` to be a `"choice-<idx>"` string whose
/// index is in bounds against `choices`. Returns the original text.
/// Returns `Err` if the field is missing, malformed, or out of range —
/// the same defense-in-depth posture the RFD recommends.
pub fn decode_single_select_accept(content: &Value, choices: &[String]) -> anyhow::Result<String> {
    let const_value = content
        .get("choice")
        .and_then(|v| v.as_str())
        .ok_or_else(|| anyhow::Error::msg("elicitation accept missing content.choice string"))?;
    let idx = const_value
        .strip_prefix("choice-")
        .and_then(|s| s.parse::<usize>().ok());
    match idx.and_then(|i| choices.get(i)) {
        Some(text) => Ok(text.clone()),
        None => anyhow::bail!("elicitation returned unknown choice const: {const_value}"),
    }
}

/// Decode the accepted `content` payload of an `elicitation/create`
/// multi-select response back into the original display texts.
pub fn decode_multi_select_accept(
    content: &Value,
    choices: &[String],
) -> anyhow::Result<Vec<String>> {
    let arr = content
        .get("choices")
        .and_then(|v| v.as_array())
        .ok_or_else(|| anyhow::Error::msg("elicitation accept missing content.choices array"))?;
    let mut out = Vec::with_capacity(arr.len());
    for v in arr {
        let s = v
            .as_str()
            .ok_or_else(|| anyhow::Error::msg("non-string entry in content.choices"))?;
        let idx = s
            .strip_prefix("choice-")
            .and_then(|n| n.parse::<usize>().ok());
        match idx.and_then(|i| choices.get(i)) {
            Some(text) => out.push(text.clone()),
            None => anyhow::bail!("elicitation returned unknown choice const: {s}"),
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn missing_key_is_no_support() {
        let caps = ElicitationCapabilities::from_value(None);
        assert!(!caps.form);
        assert!(!caps.url);
    }

    #[test]
    fn empty_object_is_form_only_per_rfd_compat() {
        let v = json!({});
        let caps = ElicitationCapabilities::from_value(Some(&v));
        assert!(caps.form);
        assert!(!caps.url);
    }

    #[test]
    fn form_only() {
        let v = json!({ "form": {} });
        let caps = ElicitationCapabilities::from_value(Some(&v));
        assert!(caps.form);
        assert!(!caps.url);
    }

    #[test]
    fn url_only() {
        let v = json!({ "url": {} });
        let caps = ElicitationCapabilities::from_value(Some(&v));
        assert!(!caps.form);
        assert!(caps.url);
    }

    #[test]
    fn both() {
        let v = json!({ "form": {}, "url": {} });
        let caps = ElicitationCapabilities::from_value(Some(&v));
        assert!(caps.form);
        assert!(caps.url);
    }

    #[test]
    fn non_object_is_no_support() {
        let v = json!("nonsense");
        let caps = ElicitationCapabilities::from_value(Some(&v));
        assert!(!caps.form);
        assert!(!caps.url);
    }

    #[test]
    fn form_with_null_value_is_still_support() {
        // Pin the structural-presence interpretation: ACP encodes
        // sub-capabilities as objects, but a forgiving parser should
        // accept `null` too rather than silently dropping support.
        let v = json!({ "form": null });
        let caps = ElicitationCapabilities::from_value(Some(&v));
        assert!(caps.form);
        assert!(!caps.url);
    }

    #[test]
    fn request_serializes_with_camelcase_keys() {
        let req = ElicitationRequest {
            session_id: "sess_1".to_string(),
            mode: ElicitationMode::Form,
            message: "Pick one".to_string(),
            requested_schema: json!({ "type": "object" }),
        };
        let v = serde_json::to_value(&req).unwrap();
        assert_eq!(v["sessionId"], "sess_1");
        assert_eq!(v["mode"], "form");
        assert_eq!(v["message"], "Pick one");
        assert!(v["requestedSchema"].is_object());
    }

    #[test]
    fn response_accept_parses() {
        let raw = json!({ "action": "accept", "content": { "choice": "choice-1" } });
        let parsed: ElicitationResponse = serde_json::from_value(raw).unwrap();
        match parsed {
            ElicitationResponse::Accept { content } => {
                assert_eq!(content["choice"], "choice-1");
            }
            other => panic!("expected Accept, got {other:?}"),
        }
    }

    #[test]
    fn response_decline_parses() {
        let raw = json!({ "action": "decline" });
        let parsed: ElicitationResponse = serde_json::from_value(raw).unwrap();
        assert!(matches!(parsed, ElicitationResponse::Decline));
    }

    #[test]
    fn response_cancel_parses() {
        let raw = json!({ "action": "cancel" });
        let parsed: ElicitationResponse = serde_json::from_value(raw).unwrap();
        assert!(matches!(parsed, ElicitationResponse::Cancel));
    }

    #[test]
    fn response_unknown_action_is_error() {
        let raw = json!({ "action": "frobnicate" });
        let res: Result<ElicitationResponse, _> = serde_json::from_value(raw);
        assert!(res.is_err());
    }

    // ── Schema helper tests ────────────────────────────────────

    #[test]
    fn single_select_schema_has_object_shape() {
        let schema = single_select_schema(&[
            "Conservative".to_string(),
            "Balanced".to_string(),
            "Aggressive".to_string(),
        ]);
        assert_eq!(schema["type"], "object");
        assert_eq!(schema["required"], json!(["choice"]));
        let choice = &schema["properties"]["choice"];
        assert_eq!(choice["type"], "string");
        let one_of = choice["oneOf"].as_array().expect("oneOf array");
        assert_eq!(one_of.len(), 3);
        assert_eq!(one_of[0]["const"], "choice-0");
        assert_eq!(one_of[0]["title"], "Conservative");
        assert_eq!(one_of[2]["const"], "choice-2");
        assert_eq!(one_of[2]["title"], "Aggressive");
    }

    #[test]
    fn single_select_schema_preserves_choice_text_via_index() {
        // Empty / duplicate display strings must not collide because the
        // wire-format `const` is index-based.
        let schema = single_select_schema(&["".to_string(), "".to_string()]);
        let one_of = schema["properties"]["choice"]["oneOf"].as_array().unwrap();
        assert_eq!(one_of[0]["const"], "choice-0");
        assert_eq!(one_of[1]["const"], "choice-1");
    }

    #[test]
    #[should_panic(expected = "sensitive")]
    fn single_select_schema_rejects_sensitive_property_names_in_debug() {
        let _ = single_select_schema_with_property_name("password", &["x".to_string()]);
    }

    #[test]
    #[should_panic(expected = "sensitive")]
    fn multi_select_schema_rejects_sensitive_property_names_in_debug() {
        // The multi-select path must enforce the same sensitive-name guard
        // as single-select — keep the invariant uniform.
        let _ = multi_select_schema_with_property_name("token", &["x".to_string()], 1, 1);
    }

    #[test]
    fn multi_select_schema_has_array_shape() {
        let schema = multi_select_schema(
            &["Red".to_string(), "Green".to_string(), "Blue".to_string()],
            1,
            2,
        );
        assert_eq!(schema["type"], "object");
        assert_eq!(schema["required"], json!(["choices"]));
        let choices = &schema["properties"]["choices"];
        assert_eq!(choices["type"], "array");
        assert_eq!(choices["minItems"], 1);
        assert_eq!(choices["maxItems"], 2);
        let any_of = choices["items"]["anyOf"].as_array().expect("anyOf array");
        assert_eq!(any_of.len(), 3);
        assert_eq!(any_of[0]["const"], "choice-0");
        assert_eq!(any_of[2]["title"], "Blue");
    }

    #[test]
    fn decode_single_select_accept_returns_text() {
        let content = json!({ "choice": "choice-1" });
        let choices = vec!["A".to_string(), "B".to_string(), "C".to_string()];
        let text = decode_single_select_accept(&content, &choices).unwrap();
        assert_eq!(text, "B");
    }

    #[test]
    fn decode_single_select_accept_rejects_unknown_const() {
        let content = json!({ "choice": "choice-99" });
        let choices = vec!["A".to_string()];
        assert!(decode_single_select_accept(&content, &choices).is_err());
    }

    #[test]
    fn decode_single_select_accept_rejects_missing_field() {
        let content = json!({});
        let choices = vec!["A".to_string()];
        assert!(decode_single_select_accept(&content, &choices).is_err());
    }

    #[test]
    fn decode_multi_select_accept_returns_texts() {
        let content = json!({ "choices": ["choice-0", "choice-2"] });
        let choices = vec!["Red".to_string(), "Green".to_string(), "Blue".to_string()];
        let texts = decode_multi_select_accept(&content, &choices).unwrap();
        assert_eq!(texts, vec!["Red".to_string(), "Blue".to_string()]);
    }

    #[test]
    fn decode_multi_select_accept_rejects_unknown_const() {
        let content = json!({ "choices": ["choice-99"] });
        let choices = vec!["A".to_string()];
        assert!(decode_multi_select_accept(&content, &choices).is_err());
    }

    #[test]
    fn select_or_custom_schemas_are_not_required() {
        let single = single_select_or_custom_schema(&["A".to_string(), "B".to_string()]);
        assert!(single.get("required").is_none());
        assert_eq!(single["properties"]["custom"]["type"], "string");
        assert_eq!(
            single["properties"]["choice"]["oneOf"][1]["const"],
            "choice-1"
        );

        let multi = multi_select_or_custom_schema(&["A".to_string(), "B".to_string()], 3);
        assert!(multi.get("required").is_none());
        assert_eq!(multi["properties"]["choices"]["minItems"], 0);
        assert_eq!(multi["properties"]["choices"]["maxItems"], 3);
        assert_eq!(multi["properties"]["custom"]["type"], "string");
    }

    #[test]
    fn decode_single_select_or_custom_prefers_typed_answer() {
        let choices = vec!["A".to_string(), "B".to_string()];
        let content = json!({ "choice": "choice-0", "custom": "  Typed  " });
        assert_eq!(
            decode_single_select_or_custom_accept(&content, &choices).unwrap(),
            "Typed"
        );
        let selected = json!({ "choice": "choice-1" });
        assert_eq!(
            decode_single_select_or_custom_accept(&selected, &choices).unwrap(),
            "B"
        );
    }

    #[test]
    fn decode_multi_select_or_custom_appends_typed_answer() {
        let choices = vec!["A".to_string(), "B".to_string(), "C".to_string()];
        let content = json!({ "choices": ["choice-0", "choice-2"], "custom": "D" });
        let answers = decode_multi_select_or_custom_accept(&content, &choices, 1, 4).unwrap();
        assert_eq!(answers, ["A", "C", "D"]);

        let custom_only = json!({ "custom": "D" });
        let answers = decode_multi_select_or_custom_accept(&custom_only, &choices, 1, 4).unwrap();
        assert_eq!(answers, ["D"]);

        let empty = json!({});
        assert!(
            decode_multi_select_or_custom_accept(&empty, &choices, 1, 4).is_err(),
            "an accept with no choices and no typed answer must be rejected"
        );
    }

    #[test]
    fn free_form_schema_and_decoder_round_trip() {
        let schema = free_form_schema(FREE_FORM_PROPERTY, "Your answer");
        assert_eq!(schema["required"], json!([FREE_FORM_PROPERTY]));
        assert_eq!(schema["properties"][FREE_FORM_PROPERTY]["type"], "string");
        assert_eq!(
            decode_free_form_accept(&json!({ "answer": "  hello  " }), FREE_FORM_PROPERTY).unwrap(),
            "hello"
        );
        assert!(decode_free_form_accept(&json!({}), FREE_FORM_PROPERTY).is_err());
    }
}
