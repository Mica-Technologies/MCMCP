//! One connected game instance, and how to ask it things.
//!
//! # Shape, and why it is this shape
//!
//! A Java implementation would hand every caller the socket behind a lock. Rust pushes hard against
//! that — holding a mutex across an `await` is the classic way to deadlock an async program — so the
//! socket is owned by exactly one task, and everyone else talks to it through a channel:
//!
//! ```text
//!   router  --request-->  outbound mpsc  -->  writer task  -->  socket
//!   router  <--oneshot--  pending map    <--  reader task  <--  socket
//! ```
//!
//! The reader task owns the read half and nothing else. The writer task owns the write half and
//! nothing else. `Instance` is a handle both of them can be reached through, cheap to clone into an
//! `Arc`, and safe to share across as many concurrent tool calls as the caller likes.
//!
//! # Request ids are ours, not the caller's
//!
//! Claude's request ids and this orchestrator's are different namespaces, and two instances would
//! happily both use `1`. Every request sent to an instance gets an id minted here, and the response
//! is matched back to the waiting caller through [`Instance::pending`]. The caller's original id
//! never leaves the router.

use anyhow::{Context, Result, anyhow, bail};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::{Notify, mpsc, oneshot};

use crate::jsonrpc;
use crate::link::protocol::{Hello, Side};

/// How long to wait for an instance to answer before giving up on one request.
///
/// Generous on purpose. The far end runs tool calls on Minecraft's game thread, and `client_wait`
/// blocks until its condition comes true; a tight timeout here would abandon calls that were going
/// to succeed. The floor on usefulness is that a *hung* instance must not wedge a session forever.
pub const REQUEST_TIMEOUT: Duration = Duration::from_secs(120);

/// The MCP protocol version the orchestrator negotiates with instances.
pub const INSTANCE_PROTOCOL_VERSION: &str = "2025-06-18";

/// What an instance told us about itself, plus what we decided to call it.
#[derive(Debug, Clone)]
pub struct InstanceInfo {
    pub id: String,
    pub label: String,
    pub side: Side,
    pub game_directory: Option<String>,
    pub mod_version: String,
    pub minecraft_version: String,
    pub endpoint_url: Option<String>,
}

impl InstanceInfo {
    pub fn from_hello(hello: &Hello, label: String) -> Self {
        Self {
            id: hello.instance_id.clone(),
            label,
            side: hello.side,
            game_directory: hello.game_directory.clone(),
            mod_version: hello.mod_version.clone(),
            minecraft_version: hello.minecraft_version.clone(),
            endpoint_url: hello.endpoint_url.clone(),
        }
    }

    /// A JSON summary for `mcmcp_instances` and the roster.
    ///
    /// Note what is absent: the secret, obviously, but also anything that would let a model infer
    /// one. This value ends up in a model's context.
    pub fn to_json(&self, connected: bool, focused: bool) -> Value {
        json!({
            "instance": self.id,
            "label": self.label,
            "side": self.side.as_str(),
            "connected": connected,
            "focused": focused,
            "gameDirectory": self.game_directory,
            "modVersion": self.mod_version,
            "minecraftVersion": self.minecraft_version,
            "httpEndpoint": self.endpoint_url,
        })
    }
}

/// What an instance offers, fetched once after its handshake and refreshed when it says so.
#[derive(Debug, Default, Clone)]
pub struct Catalogue {
    pub tools: Vec<Value>,
    pub resources: Vec<Value>,
    pub resource_templates: Vec<Value>,
    pub prompts: Vec<Value>,
}

impl Catalogue {
    pub fn tool_names(&self) -> impl Iterator<Item = &str> {
        self.tools.iter().filter_map(|tool| tool.get("name").and_then(Value::as_str))
    }

    pub fn has_tool(&self, name: &str) -> bool {
        self.tool_names().any(|candidate| candidate == name)
    }
}

/// Something an instance did that the router may need to pass on.
#[derive(Debug, Clone)]
pub enum UpstreamEvent {
    /// A notification the instance pushed — a resource update, a log message, a catalogue change.
    Notification { instance: String, message: Value },
    /// The instance finished its handshake and its catalogue is loaded.
    Connected { instance: String },
    /// The link dropped.
    Disconnected { instance: String },
}

/// A handle to one connected instance.
pub struct Instance {
    info: Mutex<InstanceInfo>,
    catalogue: Mutex<Arc<Catalogue>>,
    outbound: mpsc::Sender<Value>,
    pending: Mutex<HashMap<String, oneshot::Sender<Value>>>,
    next_request_id: AtomicU64,
    closed: Notify,
    alive: std::sync::atomic::AtomicBool,
}

impl Instance {
    pub fn new(info: InstanceInfo, outbound: mpsc::Sender<Value>) -> Self {
        Self {
            info: Mutex::new(info),
            catalogue: Mutex::new(Arc::new(Catalogue::default())),
            outbound,
            pending: Mutex::new(HashMap::new()),
            next_request_id: AtomicU64::new(1),
            closed: Notify::new(),
            alive: std::sync::atomic::AtomicBool::new(true),
        }
    }

    pub fn id(&self) -> String {
        self.info.lock().expect("instance info lock").id.clone()
    }

    pub fn info(&self) -> InstanceInfo {
        self.info.lock().expect("instance info lock").clone()
    }

    pub fn set_label(&self, label: String) {
        self.info.lock().expect("instance info lock").label = label;
    }

    /// The catalogue, as a cheap clone of a shared snapshot.
    ///
    /// `Arc<Catalogue>` rather than `Catalogue`: aggregation reads this on every `tools/list` and
    /// the tool list of a modded instance is not small. Handing out a pointer to an immutable
    /// snapshot means a refresh can swap it without anyone mid-read seeing a torn one.
    pub fn catalogue(&self) -> Arc<Catalogue> {
        Arc::clone(&self.catalogue.lock().expect("catalogue lock"))
    }

    pub fn set_catalogue(&self, catalogue: Catalogue) {
        *self.catalogue.lock().expect("catalogue lock") = Arc::new(catalogue);
    }

    pub fn is_alive(&self) -> bool {
        self.alive.load(Ordering::SeqCst)
    }

    /// Marks the link gone and fails every caller still waiting on it.
    ///
    /// Failing them explicitly rather than letting them time out is the difference between a model
    /// being told "that instance is no longer running" in a second and sitting for two minutes on a
    /// request that can never be answered.
    pub fn mark_closed(&self) {
        self.alive.store(false, Ordering::SeqCst);
        let waiting: Vec<_> = {
            let mut pending = self.pending.lock().expect("pending lock");
            pending.drain().collect()
        };
        for (id, sender) in waiting {
            let _ = sender.send(jsonrpc::error(
                Some(json!(id)),
                jsonrpc::INTERNAL_ERROR,
                "the link to this instance closed before it answered",
            ));
        }
        self.closed.notify_waiters();
    }

    /// Sends a request and waits for its answer.
    pub async fn request(&self, method: &str, params: Option<Value>) -> Result<Value> {
        if !self.is_alive() {
            bail!("instance is no longer connected");
        }

        let sequence = self.next_request_id.fetch_add(1, Ordering::Relaxed);
        let request_id = format!("orch-{sequence}");
        let (sender, receiver) = oneshot::channel();
        self.pending.lock().expect("pending lock").insert(request_id.clone(), sender);

        let message = jsonrpc::request(json!(request_id.clone()), method, params);
        if self.outbound.send(message).await.is_err() {
            self.pending.lock().expect("pending lock").remove(&request_id);
            bail!("instance is no longer connected");
        }

        let response = match tokio::time::timeout(REQUEST_TIMEOUT, receiver).await {
            Ok(Ok(response)) => response,
            Ok(Err(_)) => {
                // The oneshot was dropped without a value: the reader task went away.
                bail!("the link to this instance closed before it answered {method}");
            }
            Err(_) => {
                self.pending.lock().expect("pending lock").remove(&request_id);
                bail!("{method} timed out after {}s", REQUEST_TIMEOUT.as_secs());
            }
        };

        if jsonrpc::is_error_response(&response) {
            let message = response
                .get("error")
                .and_then(|error| error.get("message"))
                .and_then(Value::as_str)
                .unwrap_or("unknown error")
                .to_string();
            return Err(anyhow!("{method} failed: {message}"));
        }
        Ok(response.get("result").cloned().unwrap_or(Value::Null))
    }

    /// Sends a notification. Nothing to wait for, so a dead link is not an error worth surfacing.
    pub async fn notify(&self, method: &str, params: Option<Value>) {
        let _ = self.outbound.send(jsonrpc::notification(method, params)).await;
    }

    /// Sends an already-built frame.
    ///
    /// Used to answer a server-to-client request this orchestrator cannot forward. Leaving such a
    /// request unanswered would hang whatever tool is waiting on it inside the game, so a refusal
    /// has to be able to go back out without pretending to be a notification.
    pub async fn notify_raw(&self, frame: Value) {
        let _ = self.outbound.send(frame).await;
    }

    /// Matches a response from the instance to whoever is waiting for it.
    ///
    /// Returns false for an id nobody is waiting on — a late answer to a request that already timed
    /// out, which is worth a debug line and nothing more.
    pub fn complete(&self, response: Value) -> bool {
        let Some(id) = jsonrpc::id_of(&response) else {
            return false;
        };
        let Some(key) = id.as_str() else {
            return false;
        };
        let waiting = self.pending.lock().expect("pending lock").remove(key);
        match waiting {
            Some(sender) => sender.send(response).is_ok(),
            None => false,
        }
    }

    /// Runs the MCP handshake toward the instance and loads its catalogue.
    pub async fn initialize(&self) -> Result<Value> {
        let result = self
            .request(
                "initialize",
                Some(json!({
                    "protocolVersion": INSTANCE_PROTOCOL_VERSION,
                    "capabilities": {},
                    "clientInfo": { "name": "mcmcp-orchestrator", "version": crate::VERSION },
                })),
            )
            .await
            .context("initialising the instance")?;

        self.notify("notifications/initialized", None).await;
        self.refresh_catalogue().await?;
        Ok(result)
    }

    /// Re-reads what the instance offers.
    ///
    /// Resources, templates and prompts are each allowed to fail without failing the whole refresh:
    /// they are optional MCP capabilities, and an instance that does not implement one answers with
    /// a method-not-found that must not cost us its tools.
    pub async fn refresh_catalogue(&self) -> Result<()> {
        let tools = self.request("tools/list", None).await.context("listing tools")?;
        let mut catalogue = Catalogue {
            tools: array_field(&tools, "tools"),
            ..Catalogue::default()
        };

        if let Ok(resources) = self.request("resources/list", None).await {
            catalogue.resources = array_field(&resources, "resources");
        }
        if let Ok(templates) = self.request("resources/templates/list", None).await {
            catalogue.resource_templates = array_field(&templates, "resourceTemplates");
        }
        if let Ok(prompts) = self.request("prompts/list", None).await {
            catalogue.prompts = array_field(&prompts, "prompts");
        }

        self.set_catalogue(catalogue);
        Ok(())
    }
}

fn array_field(value: &Value, key: &str) -> Vec<Value> {
    value.get(key).and_then(Value::as_array).cloned().unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn info() -> InstanceInfo {
        InstanceInfo {
            id: "modb-dev".into(),
            label: "modB dev".into(),
            side: Side::Client,
            game_directory: Some("E:\\instances\\modB".into()),
            mod_version: "2026.08.24".into(),
            minecraft_version: "1.12.2".into(),
            endpoint_url: Some("http://127.0.0.1:25585/mcp".into()),
        }
    }

    #[tokio::test]
    async fn a_request_reaches_the_writer_and_its_answer_reaches_the_caller() {
        let (sender, mut outbound) = mpsc::channel(4);
        let instance = Arc::new(Instance::new(info(), sender));

        let calling = {
            let instance = Arc::clone(&instance);
            tokio::spawn(async move { instance.request("tools/list", None).await })
        };

        let sent = outbound.recv().await.expect("the request should reach the writer");
        assert_eq!(jsonrpc::method_of(&sent), Some("tools/list"));
        let id = jsonrpc::id_of(&sent).expect("requests carry an id");
        assert!(instance.complete(jsonrpc::result(id, json!({"tools": []}))));

        let result = calling.await.unwrap().expect("the call should succeed");
        assert_eq!(result["tools"], json!([]));
    }

    #[tokio::test]
    async fn mints_its_own_request_ids_rather_than_reusing_the_callers() {
        // Claude's ids and ours are different namespaces, and two instances would happily both use
        // 1. Colliding them would deliver one instance's answer to the other's caller.
        let (sender, mut outbound) = mpsc::channel(4);
        let instance = Arc::new(Instance::new(info(), sender));

        for _ in 0..2 {
            let instance = Arc::clone(&instance);
            tokio::spawn(async move { instance.request("ping", None).await });
        }

        let first = outbound.recv().await.unwrap();
        let second = outbound.recv().await.unwrap();
        assert_ne!(jsonrpc::id_of(&first), jsonrpc::id_of(&second));
    }

    #[tokio::test]
    async fn an_error_response_becomes_an_error_not_a_result() {
        let (sender, mut outbound) = mpsc::channel(4);
        let instance = Arc::new(Instance::new(info(), sender));

        let calling = {
            let instance = Arc::clone(&instance);
            tokio::spawn(async move { instance.request("tools/list", None).await })
        };

        let sent = outbound.recv().await.unwrap();
        let id = jsonrpc::id_of(&sent).unwrap();
        instance.complete(jsonrpc::error(Some(id), jsonrpc::METHOD_NOT_FOUND, "no such method"));

        let error = calling.await.unwrap().expect_err("an error response must not read as success");
        assert!(error.to_string().contains("no such method"));
    }

    #[tokio::test]
    async fn closing_the_link_fails_everyone_waiting_instead_of_making_them_time_out() {
        // Two minutes of silence versus an immediate, accurate answer. The model can act on one.
        let (sender, mut outbound) = mpsc::channel(4);
        let instance = Arc::new(Instance::new(info(), sender));

        let calling = {
            let instance = Arc::clone(&instance);
            tokio::spawn(async move { instance.request("tools/list", None).await })
        };
        outbound.recv().await.unwrap();

        instance.mark_closed();

        let error = calling.await.unwrap().expect_err("a closed link must fail the call");
        assert!(error.to_string().contains("closed"));
        assert!(!instance.is_alive());
    }

    #[tokio::test]
    async fn refuses_to_start_a_request_on_a_link_already_known_to_be_gone() {
        let (sender, _outbound) = mpsc::channel(4);
        let instance = Instance::new(info(), sender);
        instance.mark_closed();

        assert!(instance.request("ping", None).await.is_err());
    }

    #[test]
    fn a_late_answer_to_a_forgotten_request_is_ignored() {
        let (sender, _outbound) = mpsc::channel(4);
        let instance = Instance::new(info(), sender);

        assert!(!instance.complete(jsonrpc::result(json!("orch-999"), json!({}))));
    }

    #[test]
    fn the_roster_summary_carries_nothing_secret() {
        let summary = info().to_json(true, false);

        let text = serde_json::to_string(&summary).unwrap();
        assert!(!text.contains("secret"), "the roster summary reaches a model's context");
        assert_eq!(summary["instance"], "modb-dev");
        assert_eq!(summary["side"], "client");
        assert_eq!(summary["connected"], true);
    }

    #[test]
    fn knows_which_tools_an_instance_offers() {
        let catalogue = Catalogue {
            tools: vec![json!({"name": "client_move"}), json!({"name": "client_look"})],
            ..Catalogue::default()
        };

        assert!(catalogue.has_tool("client_move"));
        assert!(!catalogue.has_tool("server_set_block"));
    }
}
