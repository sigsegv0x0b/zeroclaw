//! WebSocket-backed [`Channel`] implementation that surfaces tool approval
//! prompts to the gateway client and waits for the operator's decision.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use parking_lot::Mutex;
use tokio::sync::{mpsc, oneshot};
use uuid::Uuid;
use zeroclaw_api::agent::TurnEvent;
use zeroclaw_api::channel::{
    ApprovalSource, AttributedApprovalResponse, Channel, ChannelApprovalRequest,
    ChannelApprovalResponse, ChannelMessage, SendMessage,
};
use zeroclaw_api::elicitation::{
    ElicitationResponse, FREE_FORM_PROPERTY, decode_free_form_accept, decode_multi_select_accept,
    decode_multi_select_or_custom_accept, decode_single_select_or_custom_accept, free_form_schema,
    multi_select_or_custom_schema, multi_select_schema, single_select_or_custom_schema,
};

/// Shared map keyed by `request_id`. Consumed by the receive loop to resolve
/// the oneshot when an `approval_response` frame arrives.
pub type PendingApprovals = Arc<Mutex<HashMap<String, oneshot::Sender<ChannelApprovalResponse>>>>;

/// Construct an empty pending-approvals registry for a fresh connection.
pub fn new_pending_approvals() -> PendingApprovals {
    Arc::new(Mutex::new(HashMap::new()))
}

/// Shared map keyed by `request_id` for form elicitations. The receive loop
/// resolves the oneshot when an `elicitation_response` frame arrives.
///
/// The reply is the ACP [`ElicitationResponse`] type itself, so the gateway
/// WebSocket transport and ACP share one accept/decline/cancel model.
pub type PendingElicitations = Arc<Mutex<HashMap<String, oneshot::Sender<ElicitationResponse>>>>;

/// Construct an empty pending-elicitations registry for a fresh connection.
pub fn new_pending_elicitations() -> PendingElicitations {
    Arc::new(Mutex::new(HashMap::new()))
}

/// `Channel` implementation that emits approval and elicitation frames over a
/// connection's existing `event_tx` and parks on a oneshot until the matching
/// response arrives or `timeout` elapses.
pub struct WsApprovalChannel {
    event_tx: mpsc::Sender<TurnEvent>,
    pending: PendingApprovals,
    pending_elicitations: PendingElicitations,
    timeout: Duration,
}

impl WsApprovalChannel {
    pub fn new(
        event_tx: mpsc::Sender<TurnEvent>,
        pending: PendingApprovals,
        pending_elicitations: PendingElicitations,
        timeout: Duration,
    ) -> Self {
        Self {
            event_tx,
            pending,
            pending_elicitations,
            timeout,
        }
    }

    /// Emit one form-mode elicitation and park until the operator answers,
    /// declines, cancels, or the timeout expires. `Ok(None)` means no usable
    /// response was produced; callers treat that as cancelled/unsupported.
    async fn request_elicitation(
        &self,
        message: &str,
        requested_schema: serde_json::Value,
        timeout: Duration,
    ) -> anyhow::Result<Option<ElicitationResponse>> {
        let request_id = Uuid::new_v4().to_string();
        let (tx, rx) = oneshot::channel();
        self.pending_elicitations
            .lock()
            .insert(request_id.clone(), tx);

        let event = TurnEvent::ElicitationRequest {
            request_id: request_id.clone(),
            message: message.to_string(),
            requested_schema,
            timeout_secs: timeout.as_secs(),
        };
        if self.event_tx.send(event).await.is_err() {
            self.pending_elicitations.lock().remove(&request_id);
            return Ok(None);
        }

        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(response)) => Ok(Some(response)),
            Ok(Err(_)) | Err(_) => {
                self.pending_elicitations.lock().remove(&request_id);
                Ok(None)
            }
        }
    }
}

impl ::zeroclaw_api::attribution::Attributable for WsApprovalChannel {
    fn role(&self) -> ::zeroclaw_api::attribution::Role {
        ::zeroclaw_api::attribution::Role::Channel(
            ::zeroclaw_api::attribution::ChannelKind::Webhook,
        )
    }
    fn alias(&self) -> &str {
        "ws_approval"
    }
}

#[async_trait]
impl Channel for WsApprovalChannel {
    fn name(&self) -> &str {
        "ws"
    }

    async fn send(&self, _message: &SendMessage) -> anyhow::Result<()> {
        // The gateway WS path streams agent output via TurnEvent::Chunk /
        // ::Thinking / ::ToolCall / ::ToolResult; it does not deliver
        // free-form `send()` messages. Returning Ok here keeps any caller
        // that probes for a generic delivery target from erroring out.
        Ok(())
    }

    /// `send` above is a deliberate no-op (see comment). Surfaces that must
    /// genuinely deliver — `poll`'s formatted-text fallback and
    /// `escalate_to_human` — check this so they fail honestly instead of
    /// claiming success for a message that was never rendered.
    fn supports_outbound_send(&self) -> bool {
        false
    }

    async fn listen(&self, _tx: mpsc::Sender<ChannelMessage>) -> anyhow::Result<()> {
        // The gateway WS path does not act as a message source for the
        // channel orchestrator; turns are driven directly by the WS
        // handler loop. Listen is a no-op for this transport.
        Ok(())
    }

    fn supports_free_form_ask(&self) -> bool {
        false
    }

    async fn request_choice(
        &self,
        question: &str,
        choices: &[String],
        timeout: Duration,
    ) -> anyhow::Result<Option<String>> {
        if choices.is_empty() {
            return Ok(None);
        }
        let schema = single_select_or_custom_schema(choices);
        let Some(response) = self.request_elicitation(question, schema, timeout).await? else {
            return Ok(None);
        };
        match response {
            ElicitationResponse::Accept { content } => {
                decode_single_select_or_custom_accept(&content, choices).map(Some)
            }
            ElicitationResponse::Decline | ElicitationResponse::Cancel => Ok(None),
        }
    }

    async fn request_multi_choice(
        &self,
        question: &str,
        choices: &[String],
        min_items: usize,
        max_items: usize,
        timeout: Duration,
    ) -> anyhow::Result<Option<Vec<String>>> {
        if choices.is_empty() {
            return Ok(None);
        }
        // A free-form slot rides along only when the caller left room for one:
        // ask_user passes `choices.len() + 1`, while poll passes
        // `choices.len()` and keeps its write-in surface closed.
        let allow_custom = max_items > choices.len();
        let schema = if allow_custom {
            multi_select_or_custom_schema(choices, max_items)
        } else {
            multi_select_schema(choices, min_items, max_items)
        };
        let Some(response) = self.request_elicitation(question, schema, timeout).await? else {
            return Ok(None);
        };
        match response {
            ElicitationResponse::Accept { content } if allow_custom => {
                decode_multi_select_or_custom_accept(&content, choices, min_items, max_items)
                    .map(Some)
            }
            ElicitationResponse::Accept { content } => {
                let answers = decode_multi_select_accept(&content, choices)?;
                if answers.len() < min_items.max(1) || answers.len() > max_items.max(1) {
                    anyhow::bail!(
                        "elicitation accept returned {} choice(s); expected {min_items}..={max_items}",
                        answers.len()
                    );
                }
                Ok(Some(answers))
            }
            ElicitationResponse::Decline | ElicitationResponse::Cancel => Ok(None),
        }
    }

    async fn request_free_form(
        &self,
        question: &str,
        timeout: Duration,
    ) -> anyhow::Result<Option<String>> {
        let schema = free_form_schema(FREE_FORM_PROPERTY, "Your answer");
        let Some(response) = self.request_elicitation(question, schema, timeout).await? else {
            return Ok(None);
        };
        match response {
            ElicitationResponse::Accept { content } => {
                decode_free_form_accept(&content, FREE_FORM_PROPERTY).map(Some)
            }
            ElicitationResponse::Decline | ElicitationResponse::Cancel => Ok(None),
        }
    }

    /// Delegates to [`Self::request_approval_attributed`] and drops the
    /// provenance, so the prompt/timeout logic lives in exactly one place.
    async fn request_approval(
        &self,
        recipient: &str,
        request: &ChannelApprovalRequest,
    ) -> anyhow::Result<Option<ChannelApprovalResponse>> {
        Ok(self
            .request_approval_attributed(recipient, request)
            .await?
            .map(|attributed| attributed.response))
    }

    async fn request_approval_attributed(
        &self,
        _recipient: &str,
        request: &ChannelApprovalRequest,
    ) -> anyhow::Result<Option<AttributedApprovalResponse>> {
        let request_id = Uuid::new_v4().to_string();
        let (tx, rx) = oneshot::channel();
        self.pending.lock().insert(request_id.clone(), tx);

        let event = TurnEvent::ApprovalRequest {
            request_id: request_id.clone(),
            tool_name: request.tool_name.clone(),
            arguments_summary: request.arguments_summary.clone(),
            timeout_secs: self.timeout.as_secs(),
        };
        if self.event_tx.send(event).await.is_err() {
            // Forward task has gone away; the WS is closing. Clean up the
            // pending entry and let the agent's caller treat this the same
            // as any other channel that returns None: fall through to
            // auto-deny per ApprovalManager policy.
            self.pending.lock().remove(&request_id);
            return Ok(None);
        }

        match tokio::time::timeout(self.timeout, rx).await {
            Ok(Ok(decision)) => Ok(Some(AttributedApprovalResponse::operator(decision))),
            Ok(Err(_)) => {
                // Sender dropped without responding (connection closed
                // mid-prompt). Treat as deny rather than None so the agent
                // does not silently fall back to "no channel handled this" —
                // but mark it Unreachable, because nobody answered.
                self.pending.lock().remove(&request_id);
                Ok(Some(AttributedApprovalResponse::from_runtime(
                    ChannelApprovalResponse::Deny,
                    ApprovalSource::Unreachable,
                )))
            }
            Err(_) => {
                // Timeout: pop and deny. Mirrors Telegram / Slack behaviour
                // when the operator does not tap a button in time. This deny is
                // the runtime's, not the operator's.
                self.pending.lock().remove(&request_id);
                Ok(Some(AttributedApprovalResponse::from_runtime(
                    ChannelApprovalResponse::Deny,
                    ApprovalSource::TimedOut,
                )))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn ws_approval_channel_declines_send_listen_free_form() {
        let (tx, _rx) = tokio::sync::mpsc::channel(8);
        let pending = new_pending_approvals();
        let channel = WsApprovalChannel::new(
            tx,
            pending,
            new_pending_elicitations(),
            Duration::from_secs(30),
        );
        assert!(
            !channel.supports_free_form_ask(),
            "WsApprovalChannel must not use send+listen; request_free_form handles form input"
        );
    }

    fn approval_request() -> ChannelApprovalRequest {
        ChannelApprovalRequest {
            tool_name: "file_write".to_string(),
            arguments_summary: "path=a.txt".to_string(),
            raw_arguments: None,
            position: None,
        }
    }

    /// The operator never taps anything and the prompt times out. The channel
    /// still synthesizes `Some(Deny)` so the agent does not fall through to
    /// "no channel handled this" — but that deny is the RUNTIME's, and the
    /// provenance has to say so. Before this, the identical `Some(Deny)` was
    /// reported to the model as "Denied by user." on a run where no human was
    /// ever asked.
    #[tokio::test]
    async fn timed_out_prompt_denies_with_runtime_provenance() {
        // Keep the receiver alive so the send succeeds and we reach the timeout
        // rather than the "forward task gone" early return.
        let (tx, _rx) = tokio::sync::mpsc::channel(8);
        let pending = new_pending_approvals();
        let channel = WsApprovalChannel::new(
            tx,
            pending,
            new_pending_elicitations(),
            Duration::from_millis(50),
        );

        let attributed = channel
            .request_approval_attributed("operator", &approval_request())
            .await
            .expect("timeout is not an error")
            .expect("a timeout still yields a deny, not None");

        assert_eq!(attributed.response, ChannelApprovalResponse::Deny);
        assert_eq!(
            attributed.source,
            ApprovalSource::TimedOut,
            "a prompt nobody answered is not an operator decision"
        );
        assert!(attributed.source.is_runtime_fail_closed());
    }

    /// The connection closes mid-prompt: the pending sender is dropped. Same
    /// contract as the timeout — a deny, but not the operator's.
    #[tokio::test]
    async fn dropped_responder_denies_with_runtime_provenance() {
        let (tx, _rx) = tokio::sync::mpsc::channel(8);
        let pending = new_pending_approvals();
        let channel = WsApprovalChannel::new(
            tx,
            Arc::clone(&pending),
            new_pending_elicitations(),
            Duration::from_secs(30),
        );

        let call = async {
            channel
                .request_approval_attributed("operator", &approval_request())
                .await
        };
        // Drop the registered oneshot sender out from under the waiter, which is
        // what a closing WebSocket does.
        let dropper = async {
            for _ in 0..100 {
                {
                    let mut guard = pending.lock();
                    if let Some(key) = guard.keys().next().cloned() {
                        guard.remove(&key);
                        return;
                    }
                }
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        };
        let (result, ()) = tokio::join!(call, dropper);

        let attributed = result
            .expect("a dropped responder is not an error")
            .expect("a dropped responder still yields a deny, not None");
        assert_eq!(attributed.response, ChannelApprovalResponse::Deny);
        assert_eq!(
            attributed.source,
            ApprovalSource::Unreachable,
            "a responder that went away is not an operator decision"
        );
    }

    /// The legacy entry point must keep its existing shape: provenance is an
    /// addition, not a change to what `request_approval` returns.
    #[tokio::test]
    async fn legacy_request_approval_still_returns_a_bare_deny_on_timeout() {
        let (tx, _rx) = tokio::sync::mpsc::channel(8);
        let pending = new_pending_approvals();
        let channel = WsApprovalChannel::new(
            tx,
            pending,
            new_pending_elicitations(),
            Duration::from_millis(50),
        );

        let response = channel
            .request_approval("operator", &approval_request())
            .await
            .expect("timeout is not an error");
        assert_eq!(response, Some(ChannelApprovalResponse::Deny));
    }

    #[tokio::test]
    async fn single_choice_emits_form_schema_and_resolves_typed_answer() {
        let (tx, mut rx) = tokio::sync::mpsc::channel(8);
        let pending_elicitations = new_pending_elicitations();
        let channel = Arc::new(WsApprovalChannel::new(
            tx,
            new_pending_approvals(),
            Arc::clone(&pending_elicitations),
            Duration::from_secs(5),
        ));

        let ask = {
            let channel = Arc::clone(&channel);
            zeroclaw_spawn::spawn!(async move {
                channel
                    .request_choice(
                        "Which layout?",
                        &["Compact".to_string(), "Comfortable".to_string()],
                        Duration::from_secs(5),
                    )
                    .await
            })
        };

        let event = rx.recv().await.expect("elicitation frame");
        let zeroclaw_api::agent::TurnEvent::ElicitationRequest {
            request_id,
            message,
            requested_schema,
            timeout_secs,
        } = event
        else {
            panic!("expected ElicitationRequest");
        };
        assert_eq!(message, "Which layout?");
        assert_eq!(timeout_secs, 5);
        assert_eq!(requested_schema["type"], "object");
        assert!(
            requested_schema.get("required").is_none(),
            "the choice and custom properties are alternatives, not both required"
        );
        assert_eq!(
            requested_schema["properties"]["choice"]["oneOf"][1]["const"],
            "choice-1"
        );
        assert_eq!(requested_schema["properties"]["custom"]["type"], "string");

        pending_elicitations
            .lock()
            .remove(&request_id)
            .unwrap()
            .send(ElicitationResponse::Accept {
                content: serde_json::json!({
                    "choice": "choice-0",
                    "custom": "  Compact but larger  "
                }),
            })
            .unwrap();

        assert_eq!(
            ask.await.unwrap().unwrap(),
            Some("Compact but larger".to_string()),
            "a typed answer overrides the selection"
        );
    }

    #[tokio::test]
    async fn multi_choice_emits_form_schema_and_appends_typed_answer() {
        let (tx, mut rx) = tokio::sync::mpsc::channel(8);
        let pending_elicitations = new_pending_elicitations();
        let channel = WsApprovalChannel::new(
            tx,
            new_pending_approvals(),
            Arc::clone(&pending_elicitations),
            Duration::from_secs(5),
        );

        let ask = zeroclaw_spawn::spawn!(async move {
            channel
                .request_multi_choice(
                    "Which services?",
                    &["Camera".to_string(), "SMS".to_string()],
                    1,
                    3,
                    Duration::from_secs(5),
                )
                .await
        });

        let event = rx.recv().await.expect("elicitation frame");
        let zeroclaw_api::agent::TurnEvent::ElicitationRequest {
            request_id,
            requested_schema,
            ..
        } = event
        else {
            panic!("expected ElicitationRequest");
        };
        assert_eq!(requested_schema["properties"]["choices"]["type"], "array");
        assert_eq!(requested_schema["properties"]["choices"]["minItems"], 0);
        assert_eq!(requested_schema["properties"]["choices"]["maxItems"], 3);
        assert_eq!(requested_schema["properties"]["custom"]["type"], "string");

        pending_elicitations
            .lock()
            .remove(&request_id)
            .unwrap()
            .send(ElicitationResponse::Accept {
                content: serde_json::json!({
                    "choices": ["choice-0"],
                    "custom": "Calendar"
                }),
            })
            .unwrap();

        let answers = ask.await.unwrap().unwrap().expect("answers");
        assert_eq!(answers, ["Camera", "Calendar"]);
    }

    #[tokio::test]
    async fn cancelled_elicitation_returns_none_and_clears_pending_entry() {
        let (tx, mut rx) = tokio::sync::mpsc::channel(8);
        let pending_elicitations = new_pending_elicitations();
        let channel = WsApprovalChannel::new(
            tx,
            new_pending_approvals(),
            Arc::clone(&pending_elicitations),
            Duration::from_secs(5),
        );

        let ask = zeroclaw_spawn::spawn!(async move {
            channel
                .request_choice("Continue?", &["Yes".to_string()], Duration::from_secs(5))
                .await
        });

        let event = rx.recv().await.expect("elicitation frame");
        let zeroclaw_api::agent::TurnEvent::ElicitationRequest { request_id, .. } = event else {
            panic!("expected ElicitationRequest");
        };
        pending_elicitations
            .lock()
            .remove(&request_id)
            .unwrap()
            .send(ElicitationResponse::Cancel)
            .unwrap();

        assert_eq!(ask.await.unwrap().unwrap(), None);
        assert!(pending_elicitations.lock().is_empty());
    }

    #[tokio::test]
    async fn free_form_elicitation_returns_typed_answer() {
        let (tx, mut rx) = tokio::sync::mpsc::channel(8);
        let pending_elicitations = new_pending_elicitations();
        let channel = WsApprovalChannel::new(
            tx,
            new_pending_approvals(),
            Arc::clone(&pending_elicitations),
            Duration::from_secs(5),
        );

        let ask = zeroclaw_spawn::spawn!(async move {
            channel
                .request_free_form("What name?", Duration::from_secs(5))
                .await
        });

        let event = rx.recv().await.expect("elicitation frame");
        let zeroclaw_api::agent::TurnEvent::ElicitationRequest {
            request_id,
            requested_schema,
            ..
        } = event
        else {
            panic!("expected ElicitationRequest");
        };
        assert_eq!(requested_schema["properties"]["answer"]["type"], "string");
        assert_eq!(requested_schema["required"], serde_json::json!(["answer"]));

        pending_elicitations
            .lock()
            .remove(&request_id)
            .unwrap()
            .send(ElicitationResponse::Accept {
                content: serde_json::json!({ "answer": "  Zero  " }),
            })
            .unwrap();

        assert_eq!(ask.await.unwrap().unwrap(), Some("Zero".to_string()));
    }

    #[tokio::test]
    async fn elicitation_timeout_returns_none() {
        let (tx, _rx) = tokio::sync::mpsc::channel(8);
        let channel = WsApprovalChannel::new(
            tx,
            new_pending_approvals(),
            new_pending_elicitations(),
            Duration::from_secs(5),
        );

        let response = channel
            .request_choice("Continue?", &["Yes".to_string()], Duration::from_millis(30))
            .await
            .expect("timeout is not an error");
        assert_eq!(response, None);
    }
}
