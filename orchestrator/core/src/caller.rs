//! Which agent a request came from.
//!
//! Several agents can drive one game through one orchestrator, and every call reaches the game over
//! that game's single link. Without a name for the connection a call arrived on, the game cannot
//! tell two agents apart: one agent's `client_input_lock` looked, to every other agent, like nothing
//! at all, and they drove the same player through each other's tests (#94).
//!
//! Each MCP client connection gets a [`Caller`]. The router stamps it into a forwarded call's
//! `_meta` as `mcmcp/caller`, which the mod reads. In Java terms this is a small value object shared
//! by reference (`Arc`) between the connection's request tasks.

use serde_json::{Value, json};
use std::sync::OnceLock;
use std::sync::atomic::{AtomicU64, Ordering};

/// The `_meta` key a forwarded call carries its caller under. The mod reads the same key.
pub const META_KEY: &str = "mcmcp/caller";

static NEXT: AtomicU64 = AtomicU64::new(1);

/// One MCP client connection: in practice one agent session, since each runs its own shim.
#[derive(Debug)]
pub struct Caller {
    id: String,
    /// The `clientInfo.name` the connection gave at `initialize`, once it has. `OnceLock` is a
    /// write-once cell, like a Java field set once and then only read.
    name: OnceLock<String>,
}

impl Caller {
    /// A caller with an id no other connection to this process has had.
    pub fn new() -> Self {
        Caller {
            id: format!("c{}", NEXT.fetch_add(1, Ordering::Relaxed)),
            name: OnceLock::new(),
        }
    }

    pub fn id(&self) -> &str {
        &self.id
    }

    /// Learns the client's name from a message, if it is the connection's `initialize`.
    pub fn observe(&self, message: &Value) {
        if message.get("method").and_then(Value::as_str) != Some("initialize") {
            return;
        }
        if let Some(name) = message
            .pointer("/params/clientInfo/name")
            .and_then(Value::as_str)
            .filter(|name| !name.is_empty())
        {
            let _ = self.name.set(name.to_string());
        }
    }

    /// What goes into `_meta`: `{"id": "c3", "name": "claude-code"}`, the name only once known.
    pub fn to_meta(&self) -> Value {
        let mut meta = json!({ "id": self.id });
        if let Some(name) = self.name.get() {
            meta["name"] = json!(name);
        }
        meta
    }
}

impl Default for Caller {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn two_connections_never_share_an_id() {
        assert_ne!(Caller::new().id(), Caller::new().id());
    }

    #[test]
    fn the_name_comes_from_the_connections_initialize_and_only_that() {
        let caller = Caller::new();
        caller.observe(&json!({"method": "tools/list", "params": {"clientInfo": {"name": "nope"}}}));
        assert!(caller.to_meta().get("name").is_none());
        caller.observe(&json!({"method": "initialize", "params": {"clientInfo": {"name": "claude-code"}}}));
        caller.observe(&json!({"method": "initialize", "params": {"clientInfo": {"name": "later"}}}));
        assert_eq!(caller.to_meta()["name"], "claude-code");
        assert_eq!(caller.to_meta()["id"], caller.id());
    }
}
