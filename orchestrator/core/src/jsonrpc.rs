//! JSON-RPC 2.0 envelope handling, at the level a proxy needs it.
//!
//! Everything here works on [`serde_json::Value`] rather than typed structs, and that is the whole
//! design. The orchestrator is a **proxy**: it forwards messages it did not author, between two
//! peers that may both be newer than it is. Deserialising into a struct would silently drop every
//! field this build has not been taught about — a capability negotiated between a newer Claude and
//! a newer MCMCP would vanish in the middle, and the resulting bug would look like it came from
//! either end but here.
//!
//! So: parse enough to route, rewrite exactly what must be rewritten, pass the rest through
//! untouched.

use serde_json::{Map, Value, json};

pub const VERSION: &str = "2.0";

// The subset of JSON-RPC error codes this proxy ever originates. Anything else it sees came from an
// instance and is forwarded unchanged.
pub const PARSE_ERROR: i64 = -32700;
pub const INVALID_REQUEST: i64 = -32600;
pub const METHOD_NOT_FOUND: i64 = -32601;
pub const INVALID_PARAMS: i64 = -32602;
pub const INTERNAL_ERROR: i64 = -32603;

/// A message's `id`, if it has one.
///
/// Returned as an owned `Value` because ids are legitimately strings *or* numbers and the spec
/// forbids assuming either. Normalising to a string would break a peer that compares ids by type,
/// which is a genuinely miserable bug to find.
pub fn id_of(message: &Value) -> Option<Value> {
    match message.get("id") {
        None | Some(Value::Null) => None,
        Some(id) => Some(id.clone()),
    }
}

pub fn method_of(message: &Value) -> Option<&str> {
    message.get("method").and_then(Value::as_str)
}

/// A request expects a response; a notification does not. The difference is only the `id`.
pub fn is_request(message: &Value) -> bool {
    method_of(message).is_some() && id_of(message).is_some()
}

pub fn is_notification(message: &Value) -> bool {
    method_of(message).is_some() && id_of(message).is_none()
}

pub fn is_response(message: &Value) -> bool {
    method_of(message).is_none() && (message.get("result").is_some() || message.get("error").is_some())
}

pub fn params_of(message: &Value) -> Option<&Map<String, Value>> {
    message.get("params").and_then(Value::as_object)
}

pub fn request(id: Value, method: &str, params: Option<Value>) -> Value {
    let mut message = json!({ "jsonrpc": VERSION, "id": id, "method": method });
    if let Some(params) = params {
        message["params"] = params;
    }
    message
}

pub fn notification(method: &str, params: Option<Value>) -> Value {
    let mut message = json!({ "jsonrpc": VERSION, "method": method });
    if let Some(params) = params {
        message["params"] = params;
    }
    message
}

pub fn result(id: Value, result: Value) -> Value {
    json!({ "jsonrpc": VERSION, "id": id, "result": result })
}

pub fn error(id: Option<Value>, code: i64, message: &str) -> Value {
    json!({
        "jsonrpc": VERSION,
        "id": id.unwrap_or(Value::Null),
        "error": { "code": code, "message": message },
    })
}

/// Whether a response carries an error rather than a result.
pub fn is_error_response(message: &Value) -> bool {
    message.get("error").is_some()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tells_requests_notifications_and_responses_apart() {
        let request = json!({"jsonrpc": "2.0", "id": 1, "method": "tools/list"});
        let notification = json!({"jsonrpc": "2.0", "method": "notifications/initialized"});
        let response = json!({"jsonrpc": "2.0", "id": 1, "result": {}});

        assert!(is_request(&request) && !is_notification(&request) && !is_response(&request));
        assert!(is_notification(&notification) && !is_request(&notification));
        assert!(is_response(&response) && !is_request(&response));
    }

    #[test]
    fn treats_a_null_id_as_no_id() {
        // A response to an unparseable request carries id: null. Treating that as a real id would
        // have the proxy try to match it against a pending request.
        let message = json!({"jsonrpc": "2.0", "id": null, "method": "ping"});

        assert_eq!(id_of(&message), None);
        assert!(is_notification(&message));
    }

    #[test]
    fn preserves_whether_an_id_was_a_string_or_a_number() {
        // The spec allows both and forbids assuming either. A peer that compares ids by type would
        // never match a number it sent against a string it got back.
        assert_eq!(id_of(&json!({"id": 7, "method": "x"})), Some(json!(7)));
        assert_eq!(id_of(&json!({"id": "7", "method": "x"})), Some(json!("7")));
    }

    #[test]
    fn an_error_response_is_still_a_response() {
        let message = json!({"jsonrpc": "2.0", "id": 1, "error": {"code": -32601, "message": "nope"}});

        assert!(is_response(&message));
        assert!(is_error_response(&message));
    }
}
