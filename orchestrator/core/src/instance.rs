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

/// Separates a game's id from the endpoint suffix in an addressable instance id.
///
/// A dot rather than a slash, and the reason is not cosmetic: [`crate::catalogue::qualify_uri`]
/// inserts an instance id as a resource URI's *authority*, and `unqualify_uri` splits it back off at
/// the first slash. `atm9-3f2a1c/client` would round-trip as instance `atm9-3f2a1c` and a mangled
/// original URI — silently, and only for resources. The mod's `slugify` emits nothing but
/// `[a-z0-9-]`, so a dot cannot collide with a game id it did not put there.
pub const ENDPOINT_SEPARATOR: char = '.';

/// The addressable id for one endpoint of one game.
///
/// Both sides of a singleplayer world dial in with the *same* `instanceId` — one identity per game
/// directory, shared by the client and the integrated server — so keying the registry on it made the
/// second link displace the first. Whichever connected last decided what the whole game looked like,
/// and because the integrated server links at `FMLServerStartingEvent`, long after the client links
/// at `postInit`, that was reliably the server: a singleplayer client would appear as a server and
/// lose every client-only tool.
///
/// Both sides are suffixed rather than only the server. An asymmetric rule buys a shorter name for
/// clients at the cost of two rules instead of one, and of privileging a side for no reason a person
/// reading the roster could infer.
pub fn endpoint_id(instance_id: &str, side: Side) -> String {
    format!("{instance_id}{ENDPOINT_SEPARATOR}{}", side.as_str())
}

/// What an instance told us about itself, plus what we decided to call it.
#[derive(Debug, Clone)]
pub struct InstanceInfo {
    /// The addressable id: one endpoint of one game, as [`endpoint_id`] builds it.
    ///
    /// This is what a model names in an `instance` argument, what focus points at, and what every
    /// routed result is stamped with.
    pub id: String,
    /// The id this endpoint's *game* is approved under — the raw `instanceId` from the hello.
    ///
    /// Carried rather than parsed back out of [`Self::id`]. Approval, revocation and labelling are
    /// all per-game: a person approves a Minecraft install once, not once per endpoint, and renaming
    /// a game renames both of its endpoints. Keeping the two keys as separate fields is what stops
    /// a store lookup from being handed a routing key that was never in the store.
    pub approval_id: String,
    pub label: String,
    pub side: Side,
    pub game_directory: Option<String>,
    pub mod_version: String,
    pub minecraft_version: String,
    pub endpoint_url: Option<String>,
    /// The game's OS process id, and when that process started.
    ///
    /// Carried through to the roster because they are the only fields that tell two games launched
    /// from one directory apart — see [`Hello::pid`].
    pub pid: Option<i64>,
    pub started_at: Option<String>,
}

impl InstanceInfo {
    pub fn from_hello(hello: &Hello, label: String) -> Self {
        Self {
            id: endpoint_id(&hello.instance_id, hello.side),
            approval_id: hello.instance_id.clone(),
            label,
            side: hello.side,
            game_directory: hello.game_directory.clone(),
            mod_version: hello.mod_version.clone(),
            minecraft_version: hello.minecraft_version.clone(),
            endpoint_url: hello.endpoint_url.clone(),
            pid: hello.pid,
            started_at: hello.started_at.clone(),
        }
    }

    /// A JSON summary for `mcmcp_instances` and the roster.
    ///
    /// Note what is absent: the secret, obviously, but also anything that would let a model infer
    /// one. This value ends up in a model's context.
    pub fn to_json(&self, connected: bool, focused: bool) -> Value {
        json!({
            "instance": self.id,
            // The game both endpoints of a singleplayer world share. Without it a model sees two
            // entries with the same label and no way to tell "two games" from "one game, two
            // endpoints" — which decides whether acting on both is redundant or destructive.
            "game": self.approval_id,
            "label": self.label,
            "side": self.side.as_str(),
            "connected": connected,
            "focused": focused,
            "gameDirectory": self.game_directory,
            "modVersion": self.mod_version,
            "minecraftVersion": self.minecraft_version,
            "httpEndpoint": self.endpoint_url,
            // Which process, as opposed to which game. Two clients launched from one directory are
            // identical in every field above, and only one of them is answering.
            "pid": self.pid,
            "startedAt": self.started_at,
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
        self.tools
            .iter()
            .filter_map(|tool| tool.get("name").and_then(Value::as_str))
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
    /// Bringing the instance up failed, and will be retried.
    ///
    /// Carried as an event rather than only logged because the log a desktop app writes to is
    /// stderr, which nobody sees. An instance stuck part-way up looks identical to a healthy one in
    /// the roster, so the durable record is the only way to find out afterwards what went wrong.
    BootstrapFailed {
        instance: String,
        stage: String,
        attempt: u32,
        error: String,
    },
    /// The link dropped.
    Disconnected { instance: String },
    /// The instance asked the *client* something — sampling, elicitation, roots.
    ///
    /// The inverted direction, and the reason it needs an event of its own: everything else here is
    /// something to pass on, while this is something that must be answered, and the answer has to
    /// find its way back to the instance under the id the instance used.
    Request { instance: String, message: Value },
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
    ready: std::sync::atomic::AtomicBool,
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
            ready: std::sync::atomic::AtomicBool::new(false),
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

    /// Installs a catalogue, which is also what marks the instance ready.
    ///
    /// The two are one operation on purpose: "has been asked what it can do" and "has a catalogue"
    /// are the same fact, and letting a caller establish one without the other is how an instance
    /// ends up holding tools that aggregation refuses to look at.
    pub fn set_catalogue(&self, catalogue: Catalogue) {
        *self.catalogue.lock().expect("catalogue lock") = Arc::new(catalogue);
        self.ready.store(true, Ordering::SeqCst);
    }

    pub fn is_alive(&self) -> bool {
        self.alive.load(Ordering::SeqCst)
    }

    /// Whether this instance has ever successfully loaded a catalogue.
    ///
    /// Distinct from [`Self::is_alive`], and the distinction is the whole point: an instance is
    /// registered the moment its link is up, because the reader task needs somewhere to route
    /// answers to, but that is *before* it has been asked what it can do. A registered instance
    /// with no catalogue looks connected and offers nothing, which reads to a caller as "this game
    /// has no tools" when the truth is "nobody has asked it yet, or asking failed".
    ///
    /// Aggregation and the roster both consult this rather than counting tools, so an instance
    /// still being brought up cannot be mistaken for one that genuinely offers nothing.
    pub fn is_ready(&self) -> bool {
        self.ready.load(Ordering::SeqCst)
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

    /// Resolves when the link drops.
    ///
    /// Checks first rather than waiting straight away: a link that closed before this was called
    /// has already fired its notification, and `Notify` does not keep one for a waiter that was not
    /// yet waiting.
    pub async fn wait_closed(&self) {
        if !self.is_alive() {
            return;
        }
        self.closed.notified().await;
    }

    /// Reserves the id this instance will see for the next request.
    ///
    /// Split out from [`Self::request`] so a caller can record the id *before* the answer arrives.
    /// That is what makes cancellation forwardable: `notifications/cancelled` from an MCP client
    /// names the client's own request id, and the instance only recognises the id minted here.
    /// Without somewhere to hold that mapping, a model abandoning a long `client_wait` would leave
    /// the game holding a key down until the call timed out on its own.
    pub fn mint_request_id(&self) -> String {
        let sequence = self.next_request_id.fetch_add(1, Ordering::Relaxed);
        format!("orch-{sequence}")
    }

    /// Sends a request under an id from [`Self::mint_request_id`] and hands back its answer channel.
    pub async fn send(
        &self,
        request_id: String,
        method: &str,
        params: Option<Value>,
    ) -> Result<oneshot::Receiver<Value>> {
        if !self.is_alive() {
            bail!("instance is no longer connected");
        }

        let (sender, receiver) = oneshot::channel();
        self.pending
            .lock()
            .expect("pending lock")
            .insert(request_id.clone(), sender);

        let message = jsonrpc::request(json!(request_id.clone()), method, params);
        if self.outbound.send(message).await.is_err() {
            self.pending.lock().expect("pending lock").remove(&request_id);
            bail!("instance is no longer connected");
        }
        Ok(receiver)
    }

    /// Waits for an answer sent under `request_id`, and unwraps it into a result or an error.
    pub async fn await_response(
        &self,
        request_id: &str,
        method: &str,
        receiver: oneshot::Receiver<Value>,
    ) -> Result<Value> {
        let response = match tokio::time::timeout(REQUEST_TIMEOUT, receiver).await {
            Ok(Ok(response)) => response,
            Ok(Err(_)) => {
                // The oneshot was dropped without a value: the reader task went away.
                bail!("the link to this instance closed before it answered {method}");
            }
            Err(_) => {
                self.pending.lock().expect("pending lock").remove(request_id);
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

    /// Sends a request and waits for its answer.
    pub async fn request(&self, method: &str, params: Option<Value>) -> Result<Value> {
        let request_id = self.mint_request_id();
        let receiver = self.send(request_id.clone(), method, params).await?;
        self.await_response(&request_id, method, receiver).await
    }

    /// Asks the instance to abandon a request it is still working on.
    ///
    /// Cooperative on the far side — MCMCP never interrupts a running handler, because a
    /// half-applied world mutation is worse than a late cancellation — so this is a request, not a
    /// guarantee. Sending it still matters: without it a `client_wait` the model gave up on keeps
    /// running in the game until its own timeout.
    pub async fn cancel(&self, request_id: &str, reason: &str) {
        self.notify(
            "notifications/cancelled",
            Some(json!({ "requestId": request_id, "reason": reason })),
        )
        .await;
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
    ///
    /// Kept as one call for the common path. A caller that retries should drive [`Self::handshake`]
    /// and [`Self::refresh_catalogue`] separately instead: re-sending `initialize` to a session that
    /// already completed one is not something MCP promises to tolerate, so a retry that failed on
    /// the catalogue must not replay the handshake to get back to it.
    pub async fn initialize(&self) -> Result<Value> {
        let result = self.handshake().await?;
        self.refresh_catalogue().await?;
        Ok(result)
    }

    /// The MCP handshake alone: `initialize`, then `notifications/initialized`.
    pub async fn handshake(&self) -> Result<Value> {
        let result = self
            .request(
                "initialize",
                Some(json!({
                    "protocolVersion": INSTANCE_PROTOCOL_VERSION,
                    // Declared optimistically, because an instance is initialised when it connects
                    // and that is routinely *before* any MCP client has attached — so what the real
                    // client can do is not knowable yet. A tool that asks when nobody is listening
                    // gets a clear error saying so, which is a better failure than a capability the
                    // game was told it did not have and therefore never tried to use.
                    "capabilities": { "sampling": {}, "elicitation": {} },
                    "clientInfo": { "name": "mcmcp-orchestrator", "version": crate::VERSION },
                })),
            )
            .await
            .context("initialising the instance")?;

        self.notify("notifications/initialized", None).await;
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

        // Marks the instance ready as a side effect, and only on a refresh that actually returned.
        // A `list_changed` refresh reaching this line is also what promotes an instance whose
        // bootstrap failed, so a game that registers a tool late recovers on its own.
        self.set_catalogue(catalogue);
        Ok(())
    }
}

fn array_field(value: &Value, key: &str) -> Vec<Value> {
    value
        .get(key)
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn info() -> InstanceInfo {
        InstanceInfo {
            id: "modb-dev.client".into(),
            approval_id: "modb-dev".into(),
            label: "modB dev".into(),
            side: Side::Client,
            game_directory: Some("E:\\instances\\modB".into()),
            mod_version: "2026.08.24".into(),
            minecraft_version: "1.12.2".into(),
            endpoint_url: Some("http://127.0.0.1:25585/mcp".into()),
            pid: None,
            started_at: None,
        }
    }

    fn hello(side: &str) -> Hello {
        serde_json::from_value(json!({
            "type": "hello",
            "linkProtocol": 1,
            "instanceId": "modb-dev",
            "instanceSecret": "a".repeat(64),
            "instanceName": "modB dev",
            "side": side,
            "gameDirectory": "E:\\instances\\modB",
            "modVersion": "2026.08.24",
            "minecraftVersion": "1.12.2",
        }))
        .unwrap()
    }

    #[test]
    fn an_instance_is_not_ready_until_it_has_a_catalogue() {
        // The distinction the roster and aggregation both hang on. An instance is registered the
        // moment its link is up, because the reader task needs somewhere to route answers to — but
        // that is before anyone has asked it what it can do. Treating registered as ready is what
        // let a game that linked and then failed to initialise sit there advertising nothing.
        let (sender, _outbound) = mpsc::channel(8);
        let instance = Instance::new(info(), sender);
        assert!(instance.is_alive());
        assert!(
            !instance.is_ready(),
            "a fresh link has not been asked anything yet"
        );

        instance.set_catalogue(Catalogue {
            tools: vec![json!({"name": "client_move"})],
            ..Catalogue::default()
        });
        assert!(instance.is_ready(), "a loaded catalogue is what ready means");
    }

    #[test]
    fn both_endpoints_of_one_game_get_different_addresses() {
        // The bug this exists to prevent: a singleplayer world's client and integrated server dial
        // in with the same instanceId, so keying the registry on it made the server — which links
        // later, at FMLServerStartingEvent — displace the client. The game then appeared as a
        // server and lost every client-only tool.
        let client = InstanceInfo::from_hello(&hello("client"), "modB dev".into());
        let server = InstanceInfo::from_hello(&hello("server"), "modB dev".into());

        assert_ne!(client.id, server.id);
        assert_eq!(client.id, "modb-dev.client");
        assert_eq!(server.id, "modb-dev.server");
    }

    #[test]
    fn both_endpoints_of_one_game_are_approved_under_one_id() {
        // Approval is per game: a person approves a Minecraft install once, not once per endpoint.
        let client = InstanceInfo::from_hello(&hello("client"), "modB dev".into());
        let server = InstanceInfo::from_hello(&hello("server"), "modB dev".into());

        assert_eq!(client.approval_id, "modb-dev");
        assert_eq!(server.approval_id, "modb-dev");
    }

    #[test]
    fn an_addressable_id_survives_being_put_into_a_resource_uri() {
        // qualify_uri inserts the instance id as a URI authority and unqualify_uri splits it back
        // off at the first slash. A slash-separated endpoint id would round-trip as the game id
        // plus a mangled URI — silently, and only for resources.
        let id = endpoint_id("modb-dev", Side::Server);
        let qualified = crate::catalogue::qualify_uri(&id, "minecraft://game/mods").unwrap();

        assert_eq!(
            crate::catalogue::unqualify_uri(&qualified),
            Some((id, "minecraft://game/mods".to_string()))
        );
    }

    #[test]
    fn a_summary_names_the_game_both_endpoints_share() {
        // Without it a model sees two entries with the same label and no way to tell "two games"
        // from "one game, two endpoints".
        let summary = info().to_json(true, false);

        assert_eq!(summary["instance"], "modb-dev.client");
        assert_eq!(summary["game"], "modb-dev");
    }

    #[test]
    fn a_summary_names_the_process_so_two_games_from_one_directory_are_tellable_apart() {
        // The failure this exists for: a stopped launcher task leaves the game running and holding
        // the port, the next launch cannot bind, and every call keeps reaching the old build. Both
        // rows carry the same id, label, directory and mod version — the pid is the only field that
        // differs, and startedAt is what says which of them predates the build being tested.
        let mut hello = hello("client");
        hello.pid = Some(28056);
        hello.started_at = Some("2026-09-11T08:14:02Z".into());

        let summary = InstanceInfo::from_hello(&hello, "modB dev".into()).to_json(true, false);

        assert_eq!(summary["pid"], 28056);
        assert_eq!(summary["startedAt"], "2026-09-11T08:14:02Z");
    }

    #[test]
    fn a_hello_without_a_process_id_still_parses() {
        // Older mods do not send one, and a JVM is not obliged to expose a parsable pid. Rejecting
        // the handshake over an advisory field would take a game offline for a diagnostic.
        let summary = InstanceInfo::from_hello(&hello("client"), "modB dev".into()).to_json(true, false);

        assert!(summary["pid"].is_null());
        assert!(summary["startedAt"].is_null());
    }

    #[tokio::test]
    async fn a_request_reaches_the_writer_and_its_answer_reaches_the_caller() {
        let (sender, mut outbound) = mpsc::channel(4);
        let instance = Arc::new(Instance::new(info(), sender));

        let calling = {
            let instance = Arc::clone(&instance);
            tokio::spawn(async move { instance.request("tools/list", None).await })
        };

        let sent = outbound
            .recv()
            .await
            .expect("the request should reach the writer");
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
        instance.complete(jsonrpc::error(
            Some(id),
            jsonrpc::METHOD_NOT_FOUND,
            "no such method",
        ));

        let error = calling
            .await
            .unwrap()
            .expect_err("an error response must not read as success");
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

        let error = calling
            .await
            .unwrap()
            .expect_err("a closed link must fail the call");
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

    #[tokio::test]
    async fn an_id_can_be_minted_before_the_request_is_sent() {
        // This is what makes cancellation forwardable: the caller records the instance-side id
        // before the answer arrives, so a later notifications/cancelled has something to name.
        let (sender, mut outbound) = mpsc::channel(4);
        let instance = Arc::new(Instance::new(info(), sender));

        let request_id = instance.mint_request_id();
        let receiver = instance
            .send(request_id.clone(), "tools/call", None)
            .await
            .expect("the request should send");

        let sent = outbound.recv().await.unwrap();
        assert_eq!(jsonrpc::id_of(&sent), Some(json!(request_id.clone())));

        instance.complete(jsonrpc::result(json!(request_id.clone()), json!({"ok": true})));
        let result = instance
            .await_response(&request_id, "tools/call", receiver)
            .await
            .unwrap();
        assert_eq!(result["ok"], true);
    }

    #[tokio::test]
    async fn cancelling_names_the_instance_side_id() {
        let (sender, mut outbound) = mpsc::channel(4);
        let instance = Instance::new(info(), sender);

        instance.cancel("orch-7", "the client gave up").await;

        let sent = outbound.recv().await.unwrap();
        assert_eq!(jsonrpc::method_of(&sent), Some("notifications/cancelled"));
        assert_eq!(sent["params"]["requestId"], "orch-7");
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
        assert!(
            !text.contains("secret"),
            "the roster summary reaches a model's context"
        );
        assert_eq!(summary["instance"], "modb-dev.client");
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
