//! What one MCP client connection has asked and not yet been answered (#159).
//!
//! The router broadcasts every notification to every attached client, which is right for a
//! `list_changed` and wrong for progress. A progress notification belongs to one call on one
//! connection, and a client handed progress for a token it does not know treats it as a protocol
//! error: Claude Code logged "Received a progress notification for an unknown token" dozens of times
//! an hour, for tokens that were another session's, or its own after the answer had already gone.
//!
//! So each connection keeps one of these. Requests are noted as they arrive; the connection's single
//! writer passes everything it is about to send through [`ClientCalls::outgoing`], which forgets a
//! call when its answer goes out and lets progress through only for a call still open — in the order
//! the bytes are written, so a heartbeat that loses the race with its answer is dropped, not sent
//! after it. A game that re-encodes a numeric token as a string (or back) is matched anyway, and the
//! notification is rewritten to carry the token exactly as the client sent it.

use serde_json::Value;
use std::collections::HashMap;

use crate::jsonrpc;

#[derive(Debug, Default)]
pub struct ClientCalls {
    /// Open requests by id key: the method, and the progress token's key when there was one.
    open: HashMap<String, (String, Option<String>)>,
    /// Progress token key to the token as the client spelled it, for calls still open.
    tokens: HashMap<String, Value>,
}

impl ClientCalls {
    pub fn new() -> Self {
        Self::default()
    }

    /// Notes a message from the client: a request opens a call, a cancellation closes one.
    pub fn incoming(&mut self, message: &Value) {
        let Some(method) = jsonrpc::method_of(message) else {
            return; // A response to something the orchestrator asked the client.
        };
        if method == "notifications/cancelled" {
            if let Some(id) = message.pointer("/params/requestId") {
                self.close(id);
            }
            return;
        }
        let Some(id) = jsonrpc::id_of(message) else {
            return;
        };
        let token = message
            .pointer("/params/_meta/progressToken")
            .filter(|token| !token.is_null());
        let token_key = token.and_then(token_key);
        if let (Some(key), Some(token)) = (&token_key, token) {
            self.tokens.insert(key.clone(), token.clone());
        }
        self.open.insert(id_key(&id), (method.to_string(), token_key));
    }

    /// The method a response with this id answers, while its call is open.
    pub fn method_answered(&self, response: &Value) -> Option<&str> {
        let id = response.get("id")?;
        self.open.get(&id_key(id)).map(|(method, _)| method.as_str())
    }

    /// Passes a message on its way to the client, or drops it.
    ///
    /// A response closes its call. Progress goes through only for a call still open, carrying the
    /// token as the client sent it. Everything else passes unchanged.
    pub fn outgoing(&mut self, mut message: Value) -> Option<Value> {
        if jsonrpc::is_response(&message) {
            if let Some(id) = message.get("id").cloned() {
                self.close(&id);
            }
            return Some(message);
        }
        if jsonrpc::method_of(&message) != Some("notifications/progress") {
            return Some(message);
        }
        let token = message.pointer("/params/progressToken")?;
        let original = self.tokens.get(&token_key(token)?)?.clone();
        message["params"]["progressToken"] = original;
        Some(message)
    }

    /// How many calls are open; for tests and diagnostics.
    pub fn open_calls(&self) -> usize {
        self.open.len()
    }

    fn close(&mut self, id: &Value) {
        if let Some((_, Some(token))) = self.open.remove(&id_key(id)) {
            self.tokens.remove(&token);
        }
    }
}

/// A progress token reduced so `7`, `7.0` and `"7"` are the same token. Anything but a string or
/// a number is not a token.
pub fn token_key(token: &Value) -> Option<String> {
    match token {
        Value::String(text) => Some(text.clone()),
        Value::Number(number) => Some(match number.as_f64() {
            Some(value) if value.fract() == 0.0 && value.abs() < 9.0e15 => format!("{}", value as i64),
            _ => number.to_string(),
        }),
        _ => None,
    }
}

fn id_key(id: &Value) -> String {
    match id {
        Value::String(text) => format!("s:{text}"),
        other => format!("n:{other}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn call(id: Value, token: Value) -> Value {
        json!({"jsonrpc": "2.0", "id": id, "method": "tools/call",
               "params": {"name": "client_wait", "_meta": {"progressToken": token}}})
    }

    fn progress(token: Value) -> Value {
        json!({"jsonrpc": "2.0", "method": "notifications/progress",
               "params": {"progressToken": token, "progress": 1, "total": 2}})
    }

    #[test]
    fn progress_for_an_open_call_reaches_its_client() {
        let mut calls = ClientCalls::new();
        calls.incoming(&call(json!(1), json!("tok-1")));
        assert!(calls.outgoing(progress(json!("tok-1"))).is_some());
    }

    #[test]
    fn progress_after_the_answer_went_out_is_dropped() {
        // A heartbeat that crossed the response on the wire: the client has already forgotten it.
        let mut calls = ClientCalls::new();
        calls.incoming(&call(json!(1), json!("tok-1")));
        assert!(
            calls
                .outgoing(json!({"jsonrpc": "2.0", "id": 1, "result": {}}))
                .is_some()
        );
        assert!(calls.outgoing(progress(json!("tok-1"))).is_none());
        assert_eq!(calls.open_calls(), 0);
    }

    #[test]
    fn progress_for_another_connections_call_is_dropped() {
        let mut calls = ClientCalls::new();
        calls.incoming(&call(json!(1), json!("mine")));
        assert!(calls.outgoing(progress(json!("someone-elses"))).is_none());
        assert!(calls.outgoing(progress(json!(1))).is_none());
    }

    #[test]
    fn a_token_the_game_re_encoded_reaches_the_client_spelled_as_it_sent_it() {
        let mut calls = ClientCalls::new();
        calls.incoming(&call(json!("a"), json!(42)));
        let forwarded = calls.outgoing(progress(json!("42"))).expect("the same token");
        assert_eq!(forwarded["params"]["progressToken"], json!(42));

        calls.incoming(&call(json!("b"), json!("7")));
        let forwarded = calls.outgoing(progress(json!(7.0))).expect("the same token");
        assert_eq!(forwarded["params"]["progressToken"], json!("7"));
    }

    #[test]
    fn a_cancelled_call_gets_no_more_progress() {
        let mut calls = ClientCalls::new();
        calls.incoming(&call(json!(9), json!("tok-9")));
        calls.incoming(&json!({"jsonrpc": "2.0", "method": "notifications/cancelled",
                               "params": {"requestId": 9}}));
        assert!(calls.outgoing(progress(json!("tok-9"))).is_none());
    }

    #[test]
    fn a_numeric_and_a_string_request_id_are_different_calls() {
        // JSON-RPC ids are compared by value and type; "1" answering 1 would close the wrong call.
        let mut calls = ClientCalls::new();
        calls.incoming(&call(json!(1), json!("one")));
        calls.outgoing(json!({"jsonrpc": "2.0", "id": "1", "result": {}}));
        assert!(calls.outgoing(progress(json!("one"))).is_some());
    }

    #[test]
    fn everything_that_is_not_progress_passes_untouched() {
        let mut calls = ClientCalls::new();
        let changed = json!({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"});
        assert_eq!(calls.outgoing(changed.clone()), Some(changed));
        let request = json!({"jsonrpc": "2.0", "id": "mcmcp-up-1", "method": "sampling/createMessage"});
        assert_eq!(calls.outgoing(request.clone()), Some(request));
    }

    #[test]
    fn the_method_a_response_answers_is_known_until_it_is_sent() {
        let mut calls = ClientCalls::new();
        calls.incoming(&call(json!(3), json!("t")));
        let answer = json!({"jsonrpc": "2.0", "id": 3, "result": {}});
        assert_eq!(calls.method_answered(&answer), Some("tools/call"));
        calls.outgoing(answer.clone());
        assert_eq!(calls.method_answered(&answer), None);
    }
}
