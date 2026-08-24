//! The MCP surface presented to a client, and the routing behind it.
//!
//! # Errors here are tool errors, not protocol errors
//!
//! MCMCP's own rule, and it applies unchanged across the proxy: a JSON-RPC error is handled by the
//! client's plumbing and frequently never reaches the model, which then retries the identical call
//! forever. "That instance is no longer running", "you have two games open and did not say which",
//! and "that tool only exists on alpha" are all things a model must *read* and react to, so they
//! come back as successful responses carrying `isError: true`.
//!
//! Protocol errors are reserved for what they are for: a malformed or unroutable request.
//!
//! # Every result says which instance produced it
//!
//! Sticky focus has one dangerous failure: a human changes focus in the app, the model does not
//! know, and its next `client_move` drives the wrong game. Silently. So every routed result carries
//! the instance it acted on — in `_meta`, in a short prefix on the first text block, and in
//! `structuredContent` where that is safe. The model's own transcript then reminds it continuously
//! which game it is in, and a human focus change is visible on the very next call.

use serde_json::{Map, Value, json};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tracing::{debug, warn};

use crate::catalogue::{self, ALL_INSTANCES, Aggregate, Contribution, INSTANCE_ARGUMENT};
use crate::events::{Actor, Event, EventKind, EventLog, Level};
use crate::instance::{Instance, UpstreamEvent};
use crate::jsonrpc;
use crate::policy::{Decision, Policy};
use crate::registry::{FocusResolution, Registry};
use crate::store::ApprovalStore;

/// Protocol versions this orchestrator will negotiate with its client.
pub const SUPPORTED_PROTOCOL_VERSIONS: &[&str] = &["2025-06-18", "2025-03-26", "2024-11-05"];
pub const LATEST_PROTOCOL_VERSION: &str = "2025-06-18";

/// Tools the orchestrator answers itself.
///
/// The line between these and the human-only operations is an authorisation boundary, not a
/// convenience one: **approving a pairing, revoking one, and changing gating policy are never
/// tools.** A model that can approve an instance can widen its own reach, which is precisely the
/// thing an approval prompt exists to prevent.
pub const ORCHESTRATOR_TOOLS: &[&str] = &["mcmcp_instances", "mcmcp_focus", "mcmcp_set_label"];

/// How many notifications may queue for a client that has stopped reading.
///
/// Bounded on purpose. An MCP client that subscribes to a fast-changing resource and then stops
/// reading would otherwise grow a queue inside this process without limit. Dropping the oldest
/// keeps the newest world state, which is what a client catching up actually wants.
const DOWNSTREAM_CAPACITY: usize = 512;

pub struct Router {
    registry: Arc<Registry>,
    store: Arc<Mutex<ApprovalStore>>,
    /// Notifications on their way to whichever MCP clients are attached.
    ///
    /// A broadcast rather than a single channel because there can legitimately be more than one:
    /// the desktop app serves a socket that any number of shims can attach to, and a resource
    /// update belongs to all of them. A lagging receiver drops the oldest messages rather than
    /// stalling the sender, which is the right trade — a client too slow to keep up with
    /// notifications must not be able to wedge a tool call.
    downstream: tokio::sync::broadcast::Sender<Value>,
    /// The last aggregate built while something was connected.
    ///
    /// Served when nothing is. Without it, relaunching a game client would empty and refill the
    /// tool surface every time — constant in a mod-development loop — and a client connecting before
    /// any game is up would see nothing but the roster tool and plan around having no others.
    cached: Mutex<Option<Aggregate>>,
    /// Client request id to the instance and instance-side id handling it, for cancellation.
    inflight: Mutex<HashMap<String, (Arc<Instance>, String)>>,
    protocol_version: Mutex<String>,
    initialized: AtomicBool,
    events: EventLog,
    policy: Arc<Mutex<Policy>>,
    /// Where an `Ask` decision goes to be answered.
    ///
    /// `None` in a headless process, and `Ask` then denies. There is nothing on screen to ask, and
    /// allowing what somebody explicitly asked to be prompted about fails in the direction that
    /// loses work. This is the same channel-shaped seam the approval flow uses, for the same
    /// reason: the GUI answers it without being a special case inside this code.
    gate: Mutex<Option<tokio::sync::mpsc::Sender<GateRequest>>>,
}

/// A call waiting on a human.
#[derive(Debug)]
pub struct GateRequest {
    pub instance: String,
    pub tool: String,
    pub arguments: Value,
    pub reason: String,
    pub respond: tokio::sync::oneshot::Sender<bool>,
}

impl Router {
    pub fn new(registry: Arc<Registry>, store: Arc<Mutex<ApprovalStore>>) -> Self {
        let (downstream, _) = tokio::sync::broadcast::channel(DOWNSTREAM_CAPACITY);
        Self {
            registry,
            store,
            downstream,
            cached: Mutex::new(None),
            inflight: Mutex::new(HashMap::new()),
            protocol_version: Mutex::new(LATEST_PROTOCOL_VERSION.to_string()),
            initialized: AtomicBool::new(false),
            events: EventLog::in_memory(),
            policy: Arc::new(Mutex::new(Policy::default())),
            gate: Mutex::new(None),
        }
    }

    /// Records to this log instead of the throwaway in-memory one.
    pub fn with_events(mut self, events: EventLog) -> Self {
        self.events = events;
        self
    }

    pub fn with_policy(mut self, policy: Arc<Mutex<Policy>>) -> Self {
        self.policy = policy;
        self
    }

    /// Routes `Ask` decisions to whoever can put them on a screen.
    pub fn set_gate(&self, gate: tokio::sync::mpsc::Sender<GateRequest>) {
        *self.gate.lock().expect("gate lock") = Some(gate);
    }

    pub fn events(&self) -> &EventLog {
        &self.events
    }

    pub fn policy(&self) -> Arc<Mutex<Policy>> {
        Arc::clone(&self.policy)
    }

    /// Seeds the cached catalogue from disk, so a client connecting before any game is up still
    /// sees a real tool surface.
    pub fn restore_cache(&self, aggregate: Aggregate) {
        *self.cached.lock().expect("cache lock") = Some(aggregate);
    }

    /// A receiver for notifications bound for MCP clients.
    ///
    /// Each attached client gets its own. Dropping it simply stops delivery to that one.
    pub fn subscribe_downstream(&self) -> tokio::sync::broadcast::Receiver<Value> {
        self.downstream.subscribe()
    }

    /// Sends a notification to every attached client.
    ///
    /// A send with nobody attached is not an error: the orchestrator runs perfectly well with no
    /// MCP client connected, quietly accumulating instances until one arrives.
    fn notify_downstream(&self, message: Value) {
        let _ = self.downstream.send(message);
    }

    pub fn cached_aggregate(&self) -> Option<Aggregate> {
        self.cached.lock().expect("cache lock").clone()
    }

    // ------------------------------------------------------------------
    // Dispatch
    // ------------------------------------------------------------------

    /// Handles one message from the client. Returns a response, or nothing for a notification.
    pub async fn handle(&self, message: Value) -> Option<Value> {
        let id = jsonrpc::id_of(&message);
        let Some(method) = jsonrpc::method_of(&message).map(str::to_string) else {
            // A response to something we asked. Nothing here asks the client anything yet.
            return None;
        };
        let params = message.get("params").cloned();

        if jsonrpc::is_notification(&message) {
            self.handle_notification(&method, params).await;
            return None;
        }

        let id = id?;
        let result = match method.as_str() {
            "initialize" => Ok(self.handle_initialize(params)),
            "ping" => Ok(json!({})),
            "tools/list" => Ok(self.handle_tools_list().await),
            "tools/call" => self.handle_tools_call(&id, params).await,
            "resources/list" => Ok(self.handle_resources_list().await),
            "resources/templates/list" => Ok(self.handle_templates_list().await),
            "resources/read" => self.handle_resources_read(params).await,
            "resources/subscribe" => {
                self.handle_resource_subscription("resources/subscribe", params)
                    .await
            }
            "resources/unsubscribe" => {
                self.handle_resource_subscription("resources/unsubscribe", params)
                    .await
            }
            "prompts/list" => Ok(self.handle_prompts_list().await),
            "prompts/get" => self.handle_prompts_get(params).await,
            // Accepted and ignored: the orchestrator has no logging of its own to level, and
            // refusing would fail a client that sets it as a matter of course during startup.
            "logging/setLevel" => Ok(json!({})),
            "completion/complete" => Ok(json!({"completion": {"values": [], "hasMore": false}})),
            other => {
                return Some(jsonrpc::error(
                    Some(id),
                    jsonrpc::METHOD_NOT_FOUND,
                    &format!("unknown method: {other}"),
                ));
            }
        };

        match result {
            Ok(value) => Some(jsonrpc::result(id, value)),
            Err(error) => Some(jsonrpc::error(
                Some(id),
                jsonrpc::INTERNAL_ERROR,
                &error.to_string(),
            )),
        }
    }

    async fn handle_notification(&self, method: &str, params: Option<Value>) {
        match method {
            "notifications/initialized" => {
                self.initialized.store(true, Ordering::SeqCst);
            }
            "notifications/cancelled" => {
                let Some(request_id) = params
                    .as_ref()
                    .and_then(|params| params.get("requestId"))
                    .map(id_key)
                else {
                    return;
                };
                let reason = params
                    .as_ref()
                    .and_then(|params| params.get("reason"))
                    .and_then(Value::as_str)
                    .unwrap_or("the client cancelled this request")
                    .to_string();

                let target = self
                    .inflight
                    .lock()
                    .expect("inflight lock")
                    .get(&request_id)
                    .cloned();
                match target {
                    Some((instance, instance_request_id)) => {
                        // Translated, not forwarded: the instance only recognises the id we minted.
                        instance.cancel(&instance_request_id, &reason).await;
                        debug!(%request_id, "forwarded a cancellation");
                    }
                    None => debug!(%request_id, "cancellation for a request that already finished"),
                }
            }
            other => debug!(method = %other, "ignored a client notification"),
        }
    }

    fn handle_initialize(&self, params: Option<Value>) -> Value {
        let requested = params
            .as_ref()
            .and_then(|params| params.get("protocolVersion"))
            .and_then(Value::as_str)
            .unwrap_or(LATEST_PROTOCOL_VERSION);

        // Echo a version we both speak, falling back to our latest. Answering with something the
        // client did not offer is how a handshake fails in a way neither side can explain.
        let negotiated = if SUPPORTED_PROTOCOL_VERSIONS.contains(&requested) {
            requested.to_string()
        } else {
            LATEST_PROTOCOL_VERSION.to_string()
        };
        *self.protocol_version.lock().expect("protocol lock") = negotiated.clone();

        json!({
            "protocolVersion": negotiated,
            "capabilities": {
                "tools": { "listChanged": true },
                "resources": { "subscribe": true, "listChanged": true },
                "prompts": { "listChanged": true },
                "logging": {},
            },
            "serverInfo": {
                "name": "mcmcp-orchestrator",
                "title": "MCMCP Orchestrator",
                "version": crate::VERSION,
            },
            "instructions": self.instructions(),
        })
    }

    fn instructions(&self) -> String {
        let mut text = String::from(
            "You are connected to several running Minecraft games through one MCMCP orchestrator.\n\n\
             Every tool here takes an optional `instance` argument naming which game to act on. Omit \
             it and the call goes to the focused instance. Call `mcmcp_instances` first to see what \
             is connected, what each one is, and which is focused — the games are usually different \
             worlds with different mods, and acting on the wrong one is rarely harmless.\n\n\
             Every result tells you which instance produced it. Trust that over your memory of what \
             was focused: a human can change focus at any time, and the result line is how you find \
             out.\n\n",
        );
        let connected = self.registry.count();
        if connected == 0 {
            text.push_str(
                "No game is connected right now. The tools listed are from the last game that was, \
                 and calling one will report that nothing is running rather than acting.\n",
            );
        } else if connected == 1 {
            text.push_str("One game is connected, so `instance` can be omitted from every call.\n");
        }
        text
    }

    // ------------------------------------------------------------------
    // Catalogue
    // ------------------------------------------------------------------

    /// Instance ids the `instance` argument offers, live or recently seen.
    ///
    /// Wider than "connected" on purpose — see the note on the cached catalogue. A relaunch should
    /// not rewrite 45 tool schemas.
    fn addressable(&self) -> Vec<String> {
        let mut ids = self.registry.ids();
        if let Ok(store) = self.store.lock() {
            for known in store.all() {
                if !known.revoked && !ids.contains(&known.id) {
                    ids.push(known.id.clone());
                }
            }
        }
        ids.sort();
        ids.dedup();
        ids
    }

    async fn build_aggregate(&self) -> Aggregate {
        let instances = self.registry.all();
        if instances.is_empty() {
            // Nothing connected: serve what was there last, so the tool surface does not vanish
            // between a game closing and the next one opening.
            if let Some(cached) = self.cached_aggregate() {
                return cached;
            }
            return Aggregate::default();
        }

        let contributions: Vec<Contribution> = instances
            .iter()
            .map(|instance| {
                let info = instance.info();
                Contribution {
                    id: info.id,
                    label: info.label,
                    catalogue: instance.catalogue(),
                }
            })
            .collect();

        let aggregate = catalogue::aggregate(
            &contributions,
            &self.addressable(),
            self.registry.focus().as_deref(),
        );
        *self.cached.lock().expect("cache lock") = Some(aggregate.clone());
        aggregate
    }

    async fn handle_tools_list(&self) -> Value {
        let mut tools = self.build_aggregate().await.tools;
        tools.extend(orchestrator_tool_definitions(&self.addressable()));
        json!({ "tools": tools })
    }

    async fn handle_resources_list(&self) -> Value {
        json!({ "resources": self.build_aggregate().await.resources })
    }

    async fn handle_templates_list(&self) -> Value {
        json!({ "resourceTemplates": self.build_aggregate().await.resource_templates })
    }

    async fn handle_prompts_list(&self) -> Value {
        json!({ "prompts": self.build_aggregate().await.prompts })
    }

    // ------------------------------------------------------------------
    // Tool calls
    // ------------------------------------------------------------------

    async fn handle_tools_call(
        &self,
        client_request_id: &Value,
        params: Option<Value>,
    ) -> anyhow::Result<Value> {
        let params = params.unwrap_or_else(|| json!({}));
        let Some(name) = params.get("name").and_then(Value::as_str) else {
            anyhow::bail!("tools/call needs a tool name");
        };
        let mut arguments = params
            .get("arguments")
            .and_then(Value::as_object)
            .cloned()
            .unwrap_or_default();

        let requested_instance = arguments
            .remove(INSTANCE_ARGUMENT)
            .and_then(|value| value.as_str().map(str::to_string));

        if ORCHESTRATOR_TOOLS.contains(&name) {
            return Ok(self.call_orchestrator_tool(name, &arguments));
        }

        if requested_instance.as_deref() == Some(ALL_INSTANCES) {
            return Ok(self.fan_out(name, &arguments).await);
        }

        let instance = match self.registry.resolve(requested_instance.as_deref()) {
            FocusResolution::Resolved(id) => match self.registry.get(&id) {
                Some(instance) => instance,
                // Disconnected between resolving and fetching. Rare, and a tool error rather than a
                // protocol one for the same reason as everything else here.
                None => return Ok(tool_error(&format!("instance '{id}' disconnected just now"))),
            },
            FocusResolution::NoInstances => {
                return Ok(tool_error(
                    "No Minecraft instance is connected to this orchestrator right now. Start a game \
                     with MCMCP installed and the orchestrator link enabled, then try again.",
                ));
            }
            FocusResolution::Ambiguous(ids) => {
                return Ok(tool_error(&format!(
                    "More than one game is connected and none is focused, so this call has no \
                     unambiguous target. Pass instance as one of: {}. Or call mcmcp_focus to set a \
                     default for the rest of this session.",
                    ids.join(", ")
                )));
            }
            FocusResolution::Unknown(name) => {
                let connected = self.registry.ids();
                return Ok(tool_error(&format!(
                    "There is no connected instance called '{name}'. Connected right now: {}.",
                    if connected.is_empty() {
                        "nothing".to_string()
                    } else {
                        connected.join(", ")
                    }
                )));
            }
        };

        if !instance.catalogue().has_tool(name) {
            let owners: Vec<String> = self
                .registry
                .all()
                .iter()
                .filter(|candidate| candidate.catalogue().has_tool(name))
                .map(|candidate| candidate.id())
                .collect();
            let where_it_works = if owners.is_empty() {
                "No connected instance offers it.".to_string()
            } else {
                format!("It is available on: {}.", owners.join(", "))
            };
            return Ok(tool_error(&format!(
                "The tool '{}' is not available on instance '{}'. {where_it_works}",
                name,
                instance.id()
            )));
        }

        // Gate the call before it reaches a game. Refusals are tool errors so the model reads them
        // and can say what happened, rather than retrying into a wall.
        let definition = instance
            .catalogue()
            .tools
            .iter()
            .find(|tool| tool.get("name").and_then(Value::as_str) == Some(name))
            .cloned()
            .unwrap_or_else(|| json!({ "name": name }));
        let decision = {
            let policy = self.policy.lock().expect("policy lock");
            policy.evaluate(&instance.id(), &definition, requested_instance.is_some())
        };
        if let Some(refusal) = self.apply_gate(decision, &instance.id(), name, &arguments).await {
            return Ok(refusal);
        }

        // Kept for the log before the map is moved into the forwarded params. Rust will not let
        // both happen to one value, and the clone is the honest cost of recording what was sent.
        let logged_arguments = Value::Object(arguments.clone());

        // Forward params minus the instance argument. `_meta` goes along untouched, which is what
        // carries the client's progress token through to the game.
        let mut forwarded = Map::new();
        forwarded.insert("name".into(), json!(name));
        forwarded.insert("arguments".into(), Value::Object(arguments));
        if let Some(meta) = params.get("_meta") {
            forwarded.insert("_meta".into(), meta.clone());
        }

        let started = std::time::Instant::now();
        let instance_request_id = instance.mint_request_id();
        let tracking_key = id_key(client_request_id);
        let receiver = match instance
            .send(
                instance_request_id.clone(),
                "tools/call",
                Some(Value::Object(forwarded)),
            )
            .await
        {
            Ok(receiver) => receiver,
            Err(error) => return Ok(tool_error(&format!("could not reach that instance: {error}"))),
        };

        self.inflight.lock().expect("inflight lock").insert(
            tracking_key.clone(),
            (Arc::clone(&instance), instance_request_id.clone()),
        );

        let outcome = instance
            .await_response(&instance_request_id, "tools/call", receiver)
            .await;
        self.inflight.lock().expect("inflight lock").remove(&tracking_key);

        let duration_ms = started.elapsed().as_millis() as u64;
        match outcome {
            Ok(mut result) => {
                let info = instance.info();
                let is_error = result.get("isError").and_then(Value::as_bool).unwrap_or(false);
                self.events.record(Event::new(
                    Actor::Model,
                    if is_error { Level::Warn } else { Level::Info },
                    Some(info.id.clone()),
                    EventKind::ToolCall {
                        tool: name.to_string(),
                        arguments: logged_arguments.clone(),
                        is_error,
                        duration_ms,
                    },
                ));
                let declares_output_schema = instance
                    .catalogue()
                    .tools
                    .iter()
                    .find(|tool| tool.get("name").and_then(Value::as_str) == Some(name))
                    .is_some_and(|tool| tool.get("outputSchema").is_some());
                annotate_result(&mut result, &info.id, &info.label, declares_output_schema);
                Ok(result)
            }
            Err(error) => {
                self.events.record(Event::new(
                    Actor::Model,
                    Level::Error,
                    Some(instance.id()),
                    EventKind::ToolCall {
                        tool: name.to_string(),
                        arguments: logged_arguments.clone(),
                        is_error: true,
                        duration_ms,
                    },
                ));
                Ok(tool_error(&format!(
                    "instance '{}' did not complete that call: {error}",
                    instance.id()
                )))
            }
        }
    }

    /// Runs one read-only tool on every connected instance and returns all the answers together.
    ///
    /// Refused outright for anything that is not read-only, even though the schema does not offer
    /// the option there: a schema is a suggestion to a model, not a constraint on it, and the one
    /// call this must never serve is a destructive one aimed at every game at once.
    ///
    /// Failures do not abort the rest. The useful shape of "ask all three" is three answers, some of
    /// which may be "that one is not answering" — collapsing the whole call into one error because
    /// one instance is wedged would throw away the two that worked.
    async fn fan_out(&self, name: &str, arguments: &Map<String, Value>) -> Value {
        let instances = self.registry.all();
        if instances.is_empty() {
            return tool_error("No Minecraft instance is connected to this orchestrator right now.");
        }

        for instance in &instances {
            let definition = instance
                .catalogue()
                .tools
                .iter()
                .find(|tool| tool.get("name").and_then(Value::as_str) == Some(name))
                .cloned();
            if let Some(definition) = definition
                && crate::policy::Class::of(&definition) != crate::policy::Class::ReadOnly
            {
                return tool_error(&format!(
                    "'{name}' can change game state, so it cannot be run on every instance at once. \
                     Name one instance and call it again."
                ));
            }
        }

        // Concurrently, via spawn rather than a combinator crate: the whole point is not waiting
        // for three round trips in a row, and each one is a game thread that may be busy. Every
        // captured value is owned or an Arc, which is what makes the spawn 'static.
        let mut handles = Vec::with_capacity(instances.len());
        for instance in &instances {
            let instance = Arc::clone(instance);
            let arguments = arguments.clone();
            let name = name.to_string();
            handles.push(tokio::spawn(async move {
                let outcome = instance
                    .request(
                        "tools/call",
                        Some(json!({ "name": name, "arguments": Value::Object(arguments) })),
                    )
                    .await;
                (instance.info(), outcome)
            }));
        }

        let mut results = Vec::with_capacity(handles.len());
        for handle in handles {
            match handle.await {
                Ok(result) => results.push(result),
                // A panicked task must not take the other instances' answers with it.
                Err(error) => warn!(%error, "a fan-out call did not complete"),
            }
        }

        let mut content = Vec::new();
        let mut structured = Map::new();
        let mut any_error = false;

        for (info, outcome) in results {
            match outcome {
                Ok(result) => {
                    if result.get("isError").and_then(Value::as_bool).unwrap_or(false) {
                        any_error = true;
                    }
                    if let Some(items) = result.get("content").and_then(Value::as_array) {
                        content.push(json!({
                            "type": "text",
                            "text": format!("[{} · {}]", info.label, info.id),
                        }));
                        content.extend(items.iter().cloned());
                    }
                    structured.insert(
                        info.id.clone(),
                        result.get("structuredContent").cloned().unwrap_or(Value::Null),
                    );
                }
                Err(error) => {
                    any_error = true;
                    content.push(json!({
                        "type": "text",
                        "text": format!("[{} · {}] did not answer: {error}", info.label, info.id),
                    }));
                    structured.insert(info.id.clone(), json!({ "error": error.to_string() }));
                }
            }
        }

        json!({
            "content": content,
            "structuredContent": { "byInstance": Value::Object(structured) },
            "isError": any_error,
        })
    }

    /// Turns a policy decision into either nothing (proceed) or a tool error.
    async fn apply_gate(
        &self,
        decision: Decision,
        instance: &str,
        tool: &str,
        arguments: &Map<String, Value>,
    ) -> Option<Value> {
        let (reason, rule) = match decision {
            Decision::Allow => return None,
            Decision::Deny { reason } => (reason, "deny"),
            Decision::Ask { reason } => {
                let gate = self.gate.lock().expect("gate lock").clone();
                let Some(gate) = gate else {
                    // Headless. Denying is the safe direction; saying so is what stops it looking
                    // like a bug in the tool.
                    let message = format!(
                        "{reason}, and nothing is running that can ask. Run the orchestrator's \
                         desktop app to approve calls interactively, or change this rule with \
                         'mcmcp-orchestrator policy'."
                    );
                    self.events.record(Event::new(
                        Actor::System,
                        Level::Warn,
                        Some(instance.to_string()),
                        EventKind::ToolBlocked {
                            tool: tool.to_string(),
                            rule: "ask".into(),
                        },
                    ));
                    return Some(tool_error(&message));
                };

                let (respond, answer) = tokio::sync::oneshot::channel();
                let request = GateRequest {
                    instance: instance.to_string(),
                    tool: tool.to_string(),
                    arguments: Value::Object(arguments.clone()),
                    reason: reason.clone(),
                    respond,
                };
                if gate.send(request).await.is_err() || !answer.await.unwrap_or(false) {
                    self.events.record(Event::new(
                        Actor::Human,
                        Level::Warn,
                        Some(instance.to_string()),
                        EventKind::ToolBlocked {
                            tool: tool.to_string(),
                            rule: "ask".into(),
                        },
                    ));
                    return Some(tool_error(&format!("{reason}, and it was not approved.")));
                }
                return None;
            }
        };

        self.events.record(Event::new(
            Actor::System,
            Level::Warn,
            Some(instance.to_string()),
            EventKind::ToolBlocked {
                tool: tool.to_string(),
                rule: rule.into(),
            },
        ));
        Some(tool_error(&reason))
    }

    fn call_orchestrator_tool(&self, name: &str, arguments: &Map<String, Value>) -> Value {
        match name {
            "mcmcp_instances" => {
                let focus = self.registry.focus();
                let connected: Vec<Value> = self
                    .registry
                    .all()
                    .iter()
                    .map(|instance| {
                        let info = instance.info();
                        let focused = focus.as_deref() == Some(info.id.as_str());
                        let mut summary = info.to_json(true, focused);
                        summary["tools"] = json!(instance.catalogue().tools.len());
                        summary
                    })
                    .collect();

                // Known-but-absent instances are listed too. "Where did my other game go" is a real
                // question, and an empty answer to it is much less useful than "known, not running".
                let connected_ids = self.registry.ids();
                let mut known = Vec::new();
                if let Ok(store) = self.store.lock() {
                    for instance in store.all() {
                        if instance.revoked || connected_ids.contains(&instance.id) {
                            continue;
                        }
                        known.push(json!({
                            "instance": instance.id,
                            "label": instance.label,
                            "connected": false,
                            "gameDirectory": instance.game_directory,
                        }));
                    }
                }

                structured_result(json!({
                    "connected": connected,
                    "knownButNotRunning": known,
                    "focused": focus,
                }))
            }
            "mcmcp_focus" => {
                let Some(requested) = arguments.get("target").and_then(Value::as_str) else {
                    return structured_result(json!({
                        "focused": self.registry.focus(),
                        "connected": self.registry.ids(),
                    }));
                };
                let previous = self.registry.focus();
                if self.registry.set_focus(requested) {
                    self.events.record(Event::new(
                        Actor::Model,
                        Level::Info,
                        Some(requested.to_string()),
                        EventKind::FocusChanged {
                            from: previous,
                            to: requested.to_string(),
                        },
                    ));
                    self.notify_downstream(jsonrpc::notification(
                        "notifications/message",
                        Some(json!({
                            "level": "info",
                            "logger": "mcmcp-orchestrator",
                            "data": format!("focus moved to {requested}"),
                        })),
                    ));
                    structured_result(json!({ "focused": requested }))
                } else {
                    tool_error(&format!(
                        "There is no connected instance called '{requested}'. Connected right now: {}.",
                        self.registry.ids().join(", ")
                    ))
                }
            }
            "mcmcp_set_label" => {
                let Some(target) = arguments.get("target").and_then(Value::as_str) else {
                    return tool_error("mcmcp_set_label needs a target instance");
                };
                let Some(label) = arguments.get("label").and_then(Value::as_str) else {
                    return tool_error("mcmcp_set_label needs a label");
                };
                let renamed = {
                    let mut store = self.store.lock().expect("store lock");
                    let renamed = store.set_label(target, label);
                    if renamed {
                        if let Err(error) = store.save() {
                            warn!(%error, "could not save the approval store after a rename");
                        }
                    }
                    renamed
                };
                if !renamed {
                    return tool_error(&format!(
                        "this orchestrator has never seen an instance called '{target}'"
                    ));
                }
                let previous = self
                    .registry
                    .get(target)
                    .map(|instance| instance.info().label)
                    .unwrap_or_default();
                if let Some(instance) = self.registry.get(target) {
                    instance.set_label(label.to_string());
                }
                self.events.record(Event::new(
                    Actor::Model,
                    Level::Info,
                    Some(target.to_string()),
                    EventKind::LabelChanged {
                        from: previous,
                        to: label.to_string(),
                    },
                ));
                structured_result(json!({ "instance": target, "label": label }))
            }
            other => tool_error(&format!("unknown orchestrator tool: {other}")),
        }
    }

    // ------------------------------------------------------------------
    // Resources and prompts
    // ------------------------------------------------------------------

    async fn handle_resources_read(&self, params: Option<Value>) -> anyhow::Result<Value> {
        let params = params.unwrap_or_else(|| json!({}));
        let Some(uri) = params.get("uri").and_then(Value::as_str) else {
            anyhow::bail!("resources/read needs a uri");
        };
        let Some((instance_id, original)) = catalogue::unqualify_uri(uri) else {
            anyhow::bail!("{}", unqualified_uri_help(uri));
        };
        let Some(instance) = self.registry.get(&instance_id) else {
            // An unqualified URI does not fail to parse — `minecraft://game/mods` reads as instance
            // `game` — so the honest answer covers both cases at once rather than confidently
            // reporting a missing instance that was never named.
            anyhow::bail!(
                "instance '{instance_id}' is not connected. {}",
                unqualified_uri_help(uri)
            );
        };

        let mut result = instance
            .request("resources/read", Some(json!({ "uri": original })))
            .await?;
        // Rewrite the URIs on the way back, so what the client reads matches what it asked for.
        if let Some(contents) = result.get_mut("contents").and_then(Value::as_array_mut) {
            for entry in contents {
                if let Some(entry_uri) = entry.get("uri").and_then(Value::as_str)
                    && let Some(qualified) = catalogue::qualify_uri(&instance_id, entry_uri)
                {
                    entry["uri"] = json!(qualified);
                }
            }
        }
        Ok(result)
    }

    async fn handle_resource_subscription(
        &self,
        method: &str,
        params: Option<Value>,
    ) -> anyhow::Result<Value> {
        let params = params.unwrap_or_else(|| json!({}));
        let Some(uri) = params.get("uri").and_then(Value::as_str) else {
            anyhow::bail!("{method} needs a uri");
        };
        let Some((instance_id, original)) = catalogue::unqualify_uri(uri) else {
            anyhow::bail!("{}", unqualified_uri_help(uri));
        };
        let Some(instance) = self.registry.get(&instance_id) else {
            anyhow::bail!(
                "instance '{instance_id}' is not connected. {}",
                unqualified_uri_help(uri)
            );
        };
        instance.request(method, Some(json!({ "uri": original }))).await?;
        Ok(json!({}))
    }

    async fn handle_prompts_get(&self, params: Option<Value>) -> anyhow::Result<Value> {
        let params = params.unwrap_or_else(|| json!({}));
        let Some(name) = params.get("name").and_then(Value::as_str) else {
            anyhow::bail!("prompts/get needs a name");
        };
        let mut arguments = params
            .get("arguments")
            .and_then(Value::as_object)
            .cloned()
            .unwrap_or_default();
        let requested = arguments
            .remove(INSTANCE_ARGUMENT)
            .and_then(|value| value.as_str().map(str::to_string));

        let instance = match self.registry.resolve(requested.as_deref()) {
            FocusResolution::Resolved(id) => self
                .registry
                .get(&id)
                .ok_or_else(|| anyhow::anyhow!("instance '{id}' disconnected just now"))?,
            FocusResolution::NoInstances => anyhow::bail!("no Minecraft instance is connected"),
            FocusResolution::Ambiguous(ids) => anyhow::bail!(
                "more than one game is connected and none is focused; pass instance as one of: {}",
                ids.join(", ")
            ),
            FocusResolution::Unknown(name) => anyhow::bail!("there is no connected instance called '{name}'"),
        };

        instance
            .request(
                "prompts/get",
                Some(json!({ "name": name, "arguments": arguments })),
            )
            .await
    }
}

impl Router {
    // ------------------------------------------------------------------
    // Traffic coming the other way
    // ------------------------------------------------------------------

    /// Turns something an instance did into something the client should hear about.
    ///
    /// Nothing is forwarded verbatim without asking whether it still means the same thing on this
    /// side of the proxy. A resource URI that named `minecraft://game/mods` inside one game names
    /// nothing useful to a client watching three; a log line that said "player moved" needs to say
    /// which player's game.
    pub async fn handle_upstream(&self, event: UpstreamEvent) {
        match event {
            UpstreamEvent::Connected { instance } => {
                if let Some(handle) = self.registry.get(&instance) {
                    let info = handle.info();
                    self.events.record(Event::new(
                        Actor::System,
                        Level::Info,
                        Some(instance.clone()),
                        EventKind::InstanceLinked {
                            label: info.label,
                            side: info.side.as_str().to_string(),
                            game_directory: info.game_directory,
                        },
                    ));
                }
                self.rebuild_and_announce(&format!("instance {instance} connected"))
                    .await;
            }
            UpstreamEvent::Disconnected { instance } => {
                self.events.record(Event::new(
                    Actor::System,
                    Level::Info,
                    Some(instance.clone()),
                    EventKind::InstanceUnlinked,
                ));
                // The catalogue is deliberately NOT cleared. It is what gets served while nothing is
                // connected, and dropping it would empty the tool surface every time a client is
                // relaunched — which in a mod-development loop is constantly.
                self.rebuild_and_announce(&format!("instance {instance} disconnected"))
                    .await;
            }
            UpstreamEvent::Notification { instance, message } => {
                self.forward_notification(&instance, message).await;
            }
        }
    }

    async fn rebuild_and_announce(&self, reason: &str) {
        self.build_aggregate().await;
        for method in [
            "notifications/tools/list_changed",
            "notifications/resources/list_changed",
            "notifications/prompts/list_changed",
        ] {
            self.notify_downstream(jsonrpc::notification(method, None));
        }
        self.notify_downstream(jsonrpc::notification(
            "notifications/message",
            Some(json!({
                "level": "info",
                "logger": "mcmcp-orchestrator",
                "data": reason,
            })),
        ));
    }

    async fn forward_notification(&self, instance_id: &str, message: Value) {
        let method = jsonrpc::method_of(&message).unwrap_or("").to_string();

        match method.as_str() {
            // A catalogue changed inside a game — another mod registered a tool, most likely. Re-read
            // it before telling the client, so the very next tools/list reflects reality rather than
            // producing a second round trip.
            "notifications/tools/list_changed"
            | "notifications/resources/list_changed"
            | "notifications/prompts/list_changed" => {
                if let Some(instance) = self.registry.get(instance_id)
                    && let Err(error) = instance.refresh_catalogue().await
                {
                    warn!(instance = %instance_id, %error, "could not re-read a catalogue");
                }
                self.build_aggregate().await;
                self.notify_downstream(jsonrpc::notification(&method, None));
            }

            "notifications/resources/updated" => {
                let mut forwarded = message;
                if let Some(uri) = forwarded
                    .get("params")
                    .and_then(|params| params.get("uri"))
                    .and_then(Value::as_str)
                    && let Some(qualified) = catalogue::qualify_uri(instance_id, uri)
                {
                    forwarded["params"]["uri"] = json!(qualified);
                    self.notify_downstream(forwarded);
                }
                // A resource update whose URI could not be qualified is dropped rather than sent
                // unqualified: a subscriber would get an update for a URI it never subscribed to.
            }

            "notifications/message" => {
                // Tag the log line with its game. Three instances all logging "player moved" is
                // otherwise unreadable, and log output is most valuable exactly when several are
                // running.
                let mut forwarded = message;
                if let Some(params) = forwarded.get_mut("params").and_then(Value::as_object_mut) {
                    let logger = params
                        .get("logger")
                        .and_then(Value::as_str)
                        .unwrap_or("mcmcp")
                        .to_string();
                    params.insert("logger".into(), json!(format!("{instance_id}/{logger}")));
                }
                self.notify_downstream(forwarded);
            }

            // Progress tokens are the client's own — they ride through in `_meta` on the way down
            // and come back unchanged, so there is nothing to translate.
            "notifications/progress" => {
                self.notify_downstream(message);
            }

            other => debug!(instance = %instance_id, method = %other, "dropped an instance notification"),
        }
    }
}

// ------------------------------------------------------------------
// Result shaping
// ------------------------------------------------------------------

/// Stamps a routed result with the instance that produced it.
///
/// Three places, and each earns its keep:
///
/// - `_meta` is the spec's own out-of-band slot, so a host can surface it without parsing text.
/// - A prefix on the first text block puts it in the model's transcript, which is the one that
///   matters — it is read on every subsequent turn, so a focus change a human made is impossible to
///   miss.
/// - `structuredContent`, but **only when the tool declares no output schema**. Adding a field to
///   structured content that a schema says is closed would fail validation in a strict client, and
///   turning a working tool into a validation error to add a convenience is a bad trade.
pub fn annotate_result(result: &mut Value, instance_id: &str, label: &str, declares_output_schema: bool) {
    let Some(result) = result.as_object_mut() else {
        return;
    };

    let meta = result.entry("_meta").or_insert_with(|| json!({}));
    if let Some(meta) = meta.as_object_mut() {
        meta.insert(
            "mcmcp/instance".into(),
            json!({ "id": instance_id, "label": label }),
        );
    }

    if !declares_output_schema
        && let Some(structured) = result.get_mut("structuredContent").and_then(Value::as_object_mut)
    {
        structured.insert("instance".into(), json!(instance_id));
    }

    let banner = format!("[{label} · {instance_id}]");
    match result.get_mut("content").and_then(Value::as_array_mut) {
        Some(content) => {
            let first_text = content
                .iter_mut()
                .find(|item| item.get("type").and_then(Value::as_str) == Some("text"));
            match first_text {
                Some(item) => {
                    let existing = item.get("text").and_then(Value::as_str).unwrap_or("").to_string();
                    item["text"] = json!(format!("{banner}\n{existing}"));
                }
                // Content with no text at all — an image-only screenshot result. It still needs to
                // say where it came from, and a model comparing two games' screenshots needs it most.
                None => content.insert(0, json!({ "type": "text", "text": banner })),
            }
        }
        None => {
            result.insert("content".into(), json!([{ "type": "text", "text": banner }]));
        }
    }
}

/// The sentence to add when a resource URI did not resolve.
///
/// Behind one confusing case: a URI that was never qualified does not *fail* to parse. Through this
/// orchestrator every resource names its instance first, so `minecraft://game/mods` reads as
/// instance `game` — and reporting only "instance 'game' is not connected" sends whoever is reading
/// off looking for a game they never had. Saying what the URIs look like here costs one line and
/// resolves both readings.
fn unqualified_uri_help(uri: &str) -> String {
    format!(
        "Through this orchestrator every resource URI names its instance first, like \
         minecraft://<instance>/game/mods — '{uri}' may be an unqualified URI from a single \
         instance. Call resources/list to see the current ones."
    )
}

/// A successful response carrying a failure the model must read.
pub fn tool_error(message: &str) -> Value {
    json!({
        "content": [{ "type": "text", "text": message }],
        "isError": true,
    })
}

fn structured_result(value: Value) -> Value {
    json!({
        "content": [{ "type": "text", "text": serde_json::to_string_pretty(&value).unwrap_or_default() }],
        "structuredContent": value,
        "isError": false,
    })
}

/// A stable string key for a JSON-RPC id, which may be a string or a number.
fn id_key(id: &Value) -> String {
    match id {
        Value::String(text) => text.clone(),
        other => other.to_string(),
    }
}

/// The orchestrator's own tools.
pub fn orchestrator_tool_definitions(addressable: &[String]) -> Vec<Value> {
    let enum_values: Vec<Value> = addressable.iter().map(|id| json!(id)).collect();
    let mut target = json!({
        "type": "string",
        "description": "The instance id, as reported by mcmcp_instances.",
    });
    if !enum_values.is_empty() {
        target["enum"] = Value::Array(enum_values);
    }

    vec![
        json!({
            "name": "mcmcp_instances",
            "title": "List Minecraft instances",
            "description": "List every Minecraft game connected to this orchestrator, with its id, \
                label, which side it is (client or server), what it is running, and which one is \
                currently focused. Call this before acting when more than one game may be open — the \
                games are usually different worlds with different mods, and acting on the wrong one \
                is rarely harmless. Also lists instances this orchestrator knows but that are not \
                running right now.",
            "inputSchema": { "type": "object", "properties": {} },
            "annotations": { "readOnlyHint": true, "destructiveHint": false, "idempotentHint": true },
        }),
        json!({
            "name": "mcmcp_focus",
            "title": "Get or set the focused instance",
            "description": "Read or change which instance tool calls go to when they do not name one. \
                Call with no arguments to read the current focus. Setting focus is a convenience, not \
                a lock: a human can change it at any time from the orchestrator, so always trust the \
                instance named in a tool result over your memory of what you focused.",
            "inputSchema": {
                "type": "object",
                "properties": { "target": target.clone() },
            },
            "annotations": { "readOnlyHint": false, "destructiveHint": false, "idempotentHint": true },
        }),
        json!({
            "name": "mcmcp_set_label",
            "title": "Rename an instance",
            "description": "Give an instance a human-readable label, so it can be told apart from the \
                others in later calls and in the orchestrator's own roster. Names the instance for \
                everyone, not just this session.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "target": target,
                    "label": { "type": "string", "description": "The new label, e.g. 'mymod dev'." },
                },
                "required": ["target", "label"],
            },
            "annotations": { "readOnlyHint": false, "destructiveHint": false, "idempotentHint": true },
        }),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_tool_error_is_a_successful_response_the_model_can_read() {
        // A JSON-RPC error is eaten by client plumbing and the model retries the identical call
        // forever. This has to arrive as text it reads.
        let error = tool_error("nothing is connected");

        assert_eq!(error["isError"], true);
        assert_eq!(error["content"][0]["text"], "nothing is connected");
    }

    #[test]
    fn every_result_says_which_instance_produced_it() {
        let mut result = json!({"content": [{"type": "text", "text": "you are at 10, 64, 20"}]});

        annotate_result(&mut result, "modb-dev", "modB dev", false);

        let text = result["content"][0]["text"].as_str().unwrap();
        assert!(text.starts_with("[modB dev · modb-dev]"));
        assert!(text.contains("you are at 10, 64, 20"));
        assert_eq!(result["_meta"]["mcmcp/instance"]["id"], "modb-dev");
    }

    #[test]
    fn an_image_only_result_still_says_where_it_came_from() {
        // A model comparing two games' screenshots needs this more than anywhere else.
        let mut result = json!({"content": [{"type": "image", "data": "...", "mimeType": "image/png"}]});

        annotate_result(&mut result, "alpha", "alpha", false);

        assert_eq!(result["content"][0]["type"], "text");
        assert_eq!(result["content"][1]["type"], "image");
    }

    #[test]
    fn structured_content_is_stamped_only_when_no_output_schema_forbids_it() {
        // Adding a field to structured content that a schema declares closed would fail validation
        // in a strict client. Turning a working tool into a validation error is a bad trade for a
        // convenience that _meta already provides.
        let base = json!({
            "content": [{"type": "text", "text": "ok"}],
            "structuredContent": {"x": 1},
        });

        let mut lenient = base.clone();
        annotate_result(&mut lenient, "alpha", "alpha", false);
        assert_eq!(lenient["structuredContent"]["instance"], "alpha");

        let mut strict = base;
        annotate_result(&mut strict, "alpha", "alpha", true);
        assert!(strict["structuredContent"].get("instance").is_none());
        // _meta still carries it, so nothing is lost.
        assert_eq!(strict["_meta"]["mcmcp/instance"]["id"], "alpha");
    }

    #[test]
    fn a_result_with_no_content_gains_one_rather_than_being_left_unlabelled() {
        let mut result = json!({"isError": false});

        annotate_result(&mut result, "alpha", "alpha", false);

        assert_eq!(result["content"][0]["text"], "[alpha · alpha]");
    }

    #[test]
    fn the_orchestrator_offers_no_tool_that_approves_an_instance() {
        // The authorisation boundary, asserted rather than assumed. A model that can approve a
        // pairing can widen its own reach, which is exactly what an approval prompt prevents.
        let names: Vec<String> = orchestrator_tool_definitions(&[])
            .iter()
            .map(|tool| tool["name"].as_str().unwrap().to_string())
            .collect();

        for forbidden in [
            "mcmcp_approve",
            "mcmcp_revoke",
            "mcmcp_policy_set",
            "mcmcp_strict",
        ] {
            assert!(
                !names.contains(&forbidden.to_string()),
                "{forbidden} must not be a tool"
            );
        }
        assert_eq!(names, ORCHESTRATOR_TOOLS);
    }

    #[test]
    fn orchestrator_tools_offer_known_instances_as_an_enum() {
        let tools = orchestrator_tool_definitions(&["alpha".into(), "beta".into()]);
        let focus = tools.iter().find(|tool| tool["name"] == "mcmcp_focus").unwrap();

        assert_eq!(
            focus["inputSchema"]["properties"]["target"]["enum"],
            json!(["alpha", "beta"])
        );
    }

    #[test]
    fn a_numeric_request_id_gets_a_stable_tracking_key() {
        // Ids are legitimately numbers or strings, and cancellation has to find the same key the
        // call was filed under.
        assert_eq!(id_key(&json!(7)), "7");
        assert_eq!(id_key(&json!("7")), "7");
        assert_ne!(id_key(&json!(7)), id_key(&json!("seven")));
    }
}
