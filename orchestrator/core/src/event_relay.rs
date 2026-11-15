//! Game events as one stream: chat, joins and leaves from every linked game, line by line.
//!
//! # Why this exists
//!
//! An agent that reacts to other players — a teammate joining, a chat line that tags it — used to
//! poll `client_read_chat` from a script every few seconds, all day, and diff the lines (#201). It
//! cost a tool call every few seconds whether or not anyone was online, lost lines that arrived
//! faster than its window, and found joins by grepping server messages in one locale.
//!
//! Each game now keeps a ring of events and answers `game_events_wait`, which blocks until one
//! matching a filter arrives and returns a cursor that loses nothing between calls. This module
//! turns that into a stream: one long poll per game, the results merged, each event stamped with the
//! game it came from and a cursor to resume from. Two things carry the stream out: the CLI's
//! `events` subcommand, which prints one JSON object per line for a watcher such as Claude Code's
//! Monitor, and the WebSocket listener in [`crate::events_ws`].
//!
//! # A client of the router, not part of it
//!
//! The relay reaches the games through the same MCP surface a model does, so routing, gating and the
//! game's own answers stay exactly where they are; the router remains a proxy. [`McpCall`] is the
//! seam: [`RouterCall`] calls an in-process router (the WebSocket listener, which lives in the
//! orchestrator), and [`SocketCall`] attaches to a running app over its MCP socket (the CLI, which is
//! a separate process). Its polls carry [`crate::router::QUIET_META_KEY`], so a relay holding one
//! open per game all day does not fill the event log and activity view with them.
//!
//! # Silence is never ambiguous
//!
//! Besides events, the stream carries notices: `link` when a game comes or goes, `unsupported` for a
//! game whose mod predates the tool, `reset` when a game restarted under a cursor, `missed` when
//! events fell off a game's ring before they were read, and `error` when polling one fails. A watcher
//! that sees nothing therefore knows nothing happened, rather than that the stream quietly died.

use anyhow::{Context, Result, anyhow, bail};
use serde_json::{Map, Value, json};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::future::Future;
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::{mpsc, oneshot};
use tracing::debug;

use crate::caller::Caller;
use crate::router::{EVENTS_WAIT_TOOL, QUIET_META_KEY, Router};

/// Every event type a game can report.
pub const EVENT_TYPES: &[&str] = &["chat", "join", "leave", "death", "advancement", "command"];

/// How long one poll asks the game to wait. Under the game's 300 s ceiling and the orchestrator's
/// silent-call timeout, so a quiet game costs one round trip a minute.
pub const POLL_TIMEOUT_S: u64 = 50;

/// The most events one poll returns. The game's own ceiling.
pub const POLL_MAX: u64 = 200;

/// How often the relay asks which games are connected.
pub const DISCOVERY_INTERVAL: Duration = Duration::from_secs(10);

/// The longest a failing game is left before it is polled again.
const MAX_BACKOFF: Duration = Duration::from_secs(30);

/// The client name the relay gives when it attaches over the socket.
pub const RELAY_CLIENT_NAME: &str = "mcmcp-events";

/// Request ids unique across every relay in this process. The router tracks calls in flight by the
/// client's id, so two relays both sending `1` would cancel each other's polls.
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

fn next_request_id() -> String {
    format!("mcmcp-events-{}", NEXT_ID.fetch_add(1, Ordering::Relaxed))
}

// ------------------------------------------------------------------
// Cursors and subscriptions
// ------------------------------------------------------------------

/// Where to resume one game's events: its ring's `stream` id and the last `seq` seen.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Cursor {
    pub stream: String,
    pub seq: u64,
}

/// The cursor string every event line carries: `<instance>@<stream>:<seq>`.
pub fn format_cursor(instance: &str, stream: &str, seq: u64) -> String {
    format!("{instance}@{stream}:{seq}")
}

/// Reads a cursor string back. The instance part is split at the *last* `@`, so an id that ever
/// contains one still parses.
pub fn parse_cursor(text: &str) -> Result<(String, Cursor), String> {
    let invalid =
        || format!("'{text}' is not a cursor; copy one from an event line, like 'alto.client@a1b2c3d4:1234'");
    let (instance, rest) = text.trim().rsplit_once('@').ok_or_else(invalid)?;
    let (stream, seq) = rest.rsplit_once(':').ok_or_else(invalid)?;
    let seq = seq.parse::<u64>().map_err(|_| invalid())?;
    if instance.is_empty() || stream.is_empty() {
        return Err(invalid());
    }
    Ok((
        instance.to_string(),
        Cursor {
            stream: stream.to_string(),
            seq,
        },
    ))
}

/// What a watcher asked for. The filters pass through to each game; `instances`, `resume` and
/// `backlog` are the relay's own.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Subscription {
    pub types: Vec<String>,
    pub players: Vec<String>,
    pub exclude_players: Vec<String>,
    pub contains: Option<String>,
    /// Only these instances; empty for every one.
    pub instances: Vec<String>,
    /// Per instance, where to pick up.
    pub resume: BTreeMap<String, Cursor>,
    /// Start every game without a resume cursor at the oldest event its ring still holds.
    pub backlog: bool,
}

impl Subscription {
    /// Refuses event types no game reports. A typo here would otherwise filter out everything, and
    /// look exactly like nobody saying anything.
    pub fn validate(&self) -> Result<(), String> {
        for kind in &self.types {
            if !EVENT_TYPES.contains(&kind.as_str()) {
                return Err(format!(
                    "unknown event type '{kind}'; expected some of {}",
                    EVENT_TYPES.join(", ")
                ));
            }
        }
        Ok(())
    }

    /// Adds `--resume` cursors, each naming its own instance.
    pub fn add_resume(&mut self, cursor: &str) -> Result<(), String> {
        let (instance, cursor) = parse_cursor(cursor)?;
        self.resume.insert(instance, cursor);
        Ok(())
    }

    /// Whether this watcher wants a given instance's events.
    pub fn wants(&self, instance: &str) -> bool {
        self.instances.is_empty() || self.instances.iter().any(|wanted| wanted == instance)
    }

    /// Reads the WebSocket URL's query string. Lists may be comma-separated, repeated, or both;
    /// `token` is the listener's business and ignored here.
    pub fn from_query(query: &str) -> Result<Self, String> {
        let mut subscription = Subscription::default();
        for pair in query.split('&').filter(|pair| !pair.is_empty()) {
            let (key, value) = pair.split_once('=').unwrap_or((pair, ""));
            let key = percent_decode(key);
            let value = percent_decode(value);
            let list = || {
                value
                    .split(',')
                    .map(str::trim)
                    .filter(|item| !item.is_empty())
                    .map(str::to_string)
                    .collect::<Vec<_>>()
            };
            match key.replace('-', "_").as_str() {
                "token" => {}
                "types" | "type" => subscription
                    .types
                    .extend(list().into_iter().map(|kind| kind.to_lowercase())),
                "players" | "player" => subscription.players.extend(list()),
                "exclude_players" => subscription.exclude_players.extend(list()),
                "contains" => {
                    if !value.is_empty() {
                        subscription.contains = Some(value.clone());
                    }
                }
                "instance" | "instances" => subscription.instances.extend(list()),
                "resume" => {
                    for cursor in list() {
                        subscription.add_resume(&cursor)?;
                    }
                }
                "backlog" => {
                    subscription.backlog = !matches!(value.as_str(), "0" | "false" | "no");
                }
                other => {
                    return Err(format!(
                        "unknown parameter '{other}'; expected token, types, players, \
                         exclude_players, contains, instance, resume or backlog"
                    ));
                }
            }
        }
        subscription.validate()?;
        Ok(subscription)
    }

    /// The query string [`Subscription::from_query`] reads back, without the token.
    pub fn to_query(&self) -> String {
        let mut pairs = Vec::new();
        let mut list = |key: &str, values: &[String]| {
            if !values.is_empty() {
                pairs.push(format!("{key}={}", percent_encode(&values.join(","))));
            }
        };
        list("types", &self.types);
        list("players", &self.players);
        list("exclude_players", &self.exclude_players);
        list("instance", &self.instances);
        let cursors: Vec<String> = self
            .resume
            .iter()
            .map(|(instance, cursor)| format_cursor(instance, &cursor.stream, cursor.seq))
            .collect();
        list("resume", &cursors);
        if let Some(contains) = &self.contains {
            pairs.push(format!("contains={}", percent_encode(contains)));
        }
        if self.backlog {
            pairs.push("backlog=1".to_string());
        }
        pairs.join("&")
    }

    /// The `game_events_wait` arguments for one poll.
    pub fn arguments(&self, instance: &str, state: &PollState) -> Value {
        let mut arguments = Map::new();
        arguments.insert("instance".into(), json!(instance));
        if !self.types.is_empty() {
            arguments.insert("types".into(), json!(self.types));
        }
        if !self.players.is_empty() {
            arguments.insert("players".into(), json!(self.players));
        }
        if !self.exclude_players.is_empty() {
            arguments.insert("exclude_players".into(), json!(self.exclude_players));
        }
        if let Some(contains) = &self.contains {
            arguments.insert("contains".into(), json!(contains));
        }
        if let Some(since) = state.since {
            arguments.insert("since".into(), json!(since));
        }
        if let Some(stream) = &state.stream {
            arguments.insert("stream".into(), json!(stream));
        }
        arguments.insert("timeout_s".into(), json!(POLL_TIMEOUT_S));
        arguments.insert("max".into(), json!(POLL_MAX));
        Value::Object(arguments)
    }

    /// Where one instance starts: its resume cursor, the start of its ring, or now.
    pub fn initial_state(&self, instance: &str) -> PollState {
        match self.resume.get(instance) {
            Some(cursor) => PollState {
                stream: Some(cursor.stream.clone()),
                since: Some(cursor.seq),
            },
            None if self.backlog => PollState {
                stream: None,
                since: Some(0),
            },
            None => PollState::default(),
        }
    }
}

/// Escapes everything but RFC 3986's unreserved characters, and `,` which the lists split on.
pub fn percent_encode(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for byte in text.bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'.' | b'_' | b'~' | b',') {
            out.push(byte as char);
        } else {
            out.push_str(&format!("%{byte:02X}"));
        }
    }
    out
}

/// `%XX` and `+` decoding for a query string. Invalid escapes are kept as written.
pub fn percent_decode(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        let byte = bytes[index];
        if byte == b'+' {
            out.push(b' ');
        } else if byte == b'%'
            && index + 2 < bytes.len()
            && let Some(decoded) = std::str::from_utf8(&bytes[index + 1..index + 3])
                .ok()
                .filter(|hex| hex.bytes().all(|digit| digit.is_ascii_hexdigit()))
                .and_then(|hex| u8::from_str_radix(hex, 16).ok())
        {
            out.push(decoded);
            index += 3;
            continue;
        } else {
            out.push(byte);
        }
        index += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// One game's position in its own event ring.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct PollState {
    pub stream: Option<String>,
    pub since: Option<u64>,
}

/// What one `game_events_wait` answer means for the stream.
#[derive(Debug, Default, PartialEq)]
pub struct PollOutcome {
    /// Lines to emit, notices first.
    pub lines: Vec<Value>,
    /// Where the next poll starts.
    pub next: PollState,
}

/// Turns a successful `game_events_wait` result into lines and the next cursor.
///
/// `None` when the result carries no `stream`: an answer from something that is not this tool, which
/// the caller treats as an error rather than guessing a cursor.
pub fn interpret_result(instance: &str, result: &Value) -> Option<PollOutcome> {
    let data = structured(result)?;
    let stream = data.get("stream").and_then(Value::as_str)?.to_string();
    let next = data.get("next").and_then(Value::as_u64)?;

    let mut lines = Vec::new();
    if data.get("reset").and_then(Value::as_bool) == Some(true) {
        lines.push(json!({
            "type": "reset",
            "instance": instance,
            "stream": stream,
            "message": "the game restarted since the cursor; its events start again from its oldest",
        }));
    }
    if let Some(missed) = data.get("missed").and_then(Value::as_u64).filter(|n| *n > 0) {
        lines.push(json!({
            "type": "missed",
            "instance": instance,
            "count": missed,
            "message": "events fell off the game's ring before they were read",
        }));
    }
    if let Some(events) = data.get("events").and_then(Value::as_array) {
        for event in events {
            let Some(object) = event.as_object() else {
                continue;
            };
            let mut line = Map::new();
            line.insert("instance".into(), json!(instance));
            for (key, value) in object {
                line.insert(key.clone(), value.clone());
            }
            if let Some(seq) = object.get("seq").and_then(Value::as_u64) {
                line.insert("cursor".into(), json!(format_cursor(instance, &stream, seq)));
            }
            lines.push(Value::Object(line));
        }
    }
    Some(PollOutcome {
        lines,
        next: PollState {
            stream: Some(stream),
            since: Some(next),
        },
    })
}

/// A tool result's structured payload: `structuredContent`, or failing that a text block that is one
/// JSON object (the orchestrator puts its instance banner in a block of its own).
fn structured(result: &Value) -> Option<Map<String, Value>> {
    if let Some(object) = result.get("structuredContent").and_then(Value::as_object) {
        return Some(object.clone());
    }
    result
        .get("content")
        .and_then(Value::as_array)?
        .iter()
        .filter_map(|block| block.get("text").and_then(Value::as_str))
        .find_map(|text| match serde_json::from_str::<Value>(text.trim()) {
            Ok(Value::Object(object)) => Some(object),
            _ => None,
        })
}

/// The text of a tool result, for reporting an error.
fn result_text(result: &Value) -> String {
    result
        .get("content")
        .and_then(Value::as_array)
        .map(|blocks| {
            blocks
                .iter()
                .filter_map(|block| block.get("text").and_then(Value::as_str))
                .collect::<Vec<_>>()
                .join(" ")
        })
        .unwrap_or_default()
}

/// Whether a tool error says the game simply has no such tool: a mod from before #201.
pub fn is_missing_tool(text: &str) -> bool {
    text.contains("is not available on instance") || text.contains("Unknown tool")
}

/// The connected, ready instances in an `mcmcp_instances` result.
pub fn connected_instances(result: &Value) -> Vec<String> {
    let Some(data) = structured(result) else {
        return Vec::new();
    };
    data.get("connected")
        .and_then(Value::as_array)
        .map(|entries| {
            entries
                .iter()
                .filter(|entry| entry.get("ready").and_then(Value::as_bool) != Some(false))
                .filter_map(|entry| entry.get("instance").and_then(Value::as_str))
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_default()
}

// ------------------------------------------------------------------
// Reaching the router
// ------------------------------------------------------------------

/// A boxed future, so [`McpCall`] can be used as a trait object (`dyn`), like a Java interface.
pub type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

/// One way to make an MCP request of the orchestrator.
pub trait McpCall: Send + Sync {
    /// Sends a request and returns its JSON-RPC `result`, or an error for a JSON-RPC error or a
    /// failed transport.
    fn call(&self, method: &str, params: Value) -> BoxFuture<'_, Result<Value>>;

    /// True once the transport can never answer again, so the relay stops rather than retrying.
    fn is_closed(&self) -> bool {
        false
    }
}

/// The `result` of a JSON-RPC response, or its error as an error.
fn response_result(response: Value) -> Result<Value> {
    if let Some(error) = response.get("error") {
        bail!(
            "{}",
            error
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("the orchestrator returned an error")
        );
    }
    response
        .get("result")
        .cloned()
        .ok_or_else(|| anyhow!("the orchestrator's answer had no result"))
}

/// Calls an in-process router: the WebSocket listener's way in.
pub struct RouterCall {
    router: Arc<Router>,
    caller: Arc<Caller>,
}

impl RouterCall {
    pub fn new(router: Arc<Router>) -> Self {
        let caller = Arc::new(Caller::new());
        // Named without an `initialize`: the router's initialize records the client's capabilities
        // for the whole process, and the relay declaring none would turn off sampling for the agent.
        caller.observe(&json!({
            "method": "initialize",
            "params": {"clientInfo": {"name": RELAY_CLIENT_NAME}},
        }));
        RouterCall { router, caller }
    }
}

impl McpCall for RouterCall {
    fn call(&self, method: &str, params: Value) -> BoxFuture<'_, Result<Value>> {
        let message = json!({
            "jsonrpc": "2.0",
            "id": next_request_id(),
            "method": method,
            "params": params,
        });
        let router = Arc::clone(&self.router);
        let caller = Arc::clone(&self.caller);
        Box::pin(async move {
            // Its own task, which dropping this future does not stop. A watcher that disconnects
            // mid-poll then leaves the call to finish within its timeout, and the router to forget
            // it; dropped half-way, the router's record of it would never be removed.
            let handled = tokio::spawn(async move { router.handle_from(Some(&caller), message).await });
            match handled.await {
                Ok(Some(response)) => response_result(response),
                Ok(None) => bail!("the orchestrator did not answer"),
                Err(error) => bail!("the orchestrator failed handling the request: {error}"),
            }
        })
    }
}

type Pending = Arc<Mutex<HashMap<String, oneshot::Sender<Value>>>>;

/// Calls a running orchestrator over its MCP socket: the CLI's way in.
pub struct SocketCall {
    requests: mpsc::UnboundedSender<Value>,
    pending: Pending,
    closed: Arc<std::sync::atomic::AtomicBool>,
}

impl SocketCall {
    /// Attaches with the token from the state directory.
    pub async fn connect(port: u16, token: &str) -> Result<Self> {
        let stream = tokio::net::TcpStream::connect(("127.0.0.1", port))
            .await
            .with_context(|| {
                format!(
                    "nothing is listening on 127.0.0.1:{port}. The events stream needs the \
                     orchestrator app running (or pass --port for another one)."
                )
            })?;
        let connection = crate::shim::attach(stream, token).await?;
        let (mut lines, mut writer) = connection.into_parts();

        let pending: Pending = Arc::new(Mutex::new(HashMap::new()));
        let closed = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let (requests, mut requests_rx) = mpsc::unbounded_channel::<Value>();

        // One writer, as everywhere else: two tasks writing one socket would interleave frames.
        {
            let closed = Arc::clone(&closed);
            tokio::spawn(async move {
                while let Some(request) = requests_rx.recv().await {
                    if crate::link::framing::write_frame(&mut writer, &request)
                        .await
                        .is_err()
                    {
                        break;
                    }
                }
                closed.store(true, Ordering::SeqCst);
            });
        }
        {
            let pending = Arc::clone(&pending);
            let closed = Arc::clone(&closed);
            tokio::spawn(async move {
                while let Ok(Some(line)) = lines.next_line().await {
                    let Ok(message) = serde_json::from_str::<Value>(&line) else {
                        continue;
                    };
                    // Notifications the router fans out to every client are not for us.
                    let Some(id) = message.get("id").and_then(Value::as_str) else {
                        continue;
                    };
                    let waiter = pending.lock().expect("pending lock").remove(id);
                    if let Some(waiter) = waiter {
                        let _ = waiter.send(message);
                    }
                }
                closed.store(true, Ordering::SeqCst);
                // Dropping every waiter fails each call still waiting, instead of leaving it forever.
                pending.lock().expect("pending lock").clear();
            });
        }
        Ok(SocketCall {
            requests,
            pending,
            closed,
        })
    }
}

impl McpCall for SocketCall {
    fn call(&self, method: &str, params: Value) -> BoxFuture<'_, Result<Value>> {
        let id = next_request_id();
        let (answer, answered) = oneshot::channel();
        self.pending
            .lock()
            .expect("pending lock")
            .insert(id.clone(), answer);
        let sent = self.requests.send(json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": method,
            "params": params,
        }));
        Box::pin(async move {
            if sent.is_err() {
                bail!("the connection to the orchestrator is closed");
            }
            match answered.await {
                Ok(response) => response_result(response),
                Err(_) => bail!("the connection to the orchestrator closed"),
            }
        })
    }

    fn is_closed(&self) -> bool {
        self.closed.load(Ordering::SeqCst)
    }
}

// ------------------------------------------------------------------
// The relay
// ------------------------------------------------------------------

/// Streams events into `sink` until it closes or the transport does.
///
/// Returns `Ok` when the watcher went away (the sink closed) and an error when the orchestrator did;
/// the CLI reconnects on the second, the WebSocket listener simply ends.
pub async fn run(
    call: Arc<dyn McpCall>,
    subscription: Subscription,
    sink: mpsc::Sender<Value>,
) -> Result<()> {
    run_with(call, subscription, sink, States::default()).await
}

/// Every game's cursor, shared so it can outlive one connection to the orchestrator: the CLI hands
/// the same map to each reconnect, and a game that said nothing during the outage still resumes
/// from where it was rather than from "now".
pub type States = Arc<Mutex<HashMap<String, PollState>>>;

/// [`run`], with cursors kept in `states`.
pub async fn run_with(
    call: Arc<dyn McpCall>,
    subscription: Subscription,
    sink: mpsc::Sender<Value>,
    states: States,
) -> Result<()> {
    let subscription = Arc::new(subscription);
    let mut polling: HashMap<String, tokio::task::JoinHandle<()>> = HashMap::new();
    // Instances whose own poll found them gone and already said `link down`.
    let departed: Departed = Arc::new(Mutex::new(HashSet::new()));

    let result = loop {
        if sink.is_closed() {
            break Ok(());
        }
        let listed = call
            .call("tools/call", json!({"name": "mcmcp_instances", "arguments": {}}))
            .await;
        let connected: HashSet<String> = match listed {
            Ok(result) => connected_instances(&result)
                .into_iter()
                .filter(|instance| subscription.wants(instance))
                .collect(),
            Err(error) if call.is_closed() => break Err(error),
            Err(error) => {
                debug!(%error, "could not list instances; trying again");
                tokio::time::sleep(DISCOVERY_INTERVAL).await;
                continue;
            }
        };

        // Gone: stop polling and say so. The cursor is kept, so a game that relinks resumes.
        let gone: Vec<String> = polling
            .keys()
            .filter(|instance| !connected.contains(*instance))
            .cloned()
            .collect();
        for instance in gone {
            if let Some(task) = polling.remove(&instance) {
                task.abort();
            }
            // Said once, by whichever noticed first: claiming the instance in `departed` is the right
            // to say it, and its own poll may have claimed it already.
            if !departed.lock().expect("departed lock").insert(instance.clone()) {
                continue;
            }
            if sink
                .send(json!({"type": "link", "instance": instance, "state": "down"}))
                .await
                .is_err()
            {
                break;
            }
        }

        // A poll that reported its game gone, when the game is listed again by now: it relinked
        // between the two looks. Forget the stopped poll so it is announced and polled afresh.
        {
            let mut departed = departed.lock().expect("departed lock");
            let relinked: Vec<String> = departed
                .iter()
                .filter(|instance| connected.contains(*instance))
                .cloned()
                .collect();
            for instance in relinked {
                departed.remove(&instance);
                if let Some(task) = polling.remove(&instance) {
                    task.abort();
                }
            }
        }

        // New, or back.
        let mut fresh: Vec<&String> = connected
            .iter()
            .filter(|instance| !polling.contains_key(*instance))
            .collect();
        fresh.sort();
        for instance in fresh {
            if sink
                .send(json!({"type": "link", "instance": instance, "state": "up"}))
                .await
                .is_err()
            {
                break;
            }
            let state = states
                .lock()
                .expect("states lock")
                .entry(instance.clone())
                .or_insert_with(|| subscription.initial_state(instance))
                .clone();
            let task = tokio::spawn(poll_instance(
                Arc::clone(&call),
                Arc::clone(&subscription),
                instance.clone(),
                state,
                Arc::clone(&states),
                Arc::clone(&departed),
                sink.clone(),
            ));
            polling.insert(instance.clone(), task);
        }

        tokio::select! {
            _ = tokio::time::sleep(DISCOVERY_INTERVAL) => {}
            _ = sink.closed() => {}
        }
    };

    for (_, task) in polling {
        task.abort();
    }
    result
}

/// Instances whose `link down` has been said, so it is said once. See [`run_with`].
type Departed = Arc<Mutex<HashSet<String>>>;

/// Whether the orchestrator still lists `instance` as connected and ready. True when it cannot say,
/// so a failure on a game that may still be there is reported rather than hidden.
async fn still_connected(call: &dyn McpCall, instance: &str) -> bool {
    match call
        .call("tools/call", json!({"name": "mcmcp_instances", "arguments": {}}))
        .await
    {
        Ok(result) if structured(&result).is_some() => connected_instances(&result)
            .iter()
            .any(|connected| connected == instance),
        _ => true,
    }
}

/// Long-polls one game until its task is aborted, the sink closes, or the game turns out to lack the
/// tool.
async fn poll_instance(
    call: Arc<dyn McpCall>,
    subscription: Arc<Subscription>,
    instance: String,
    mut state: PollState,
    states: States,
    departed: Departed,
    sink: mpsc::Sender<Value>,
) {
    let mut backoff = Duration::from_secs(1);
    let mut last_error: Option<String> = None;
    loop {
        // Checked every time round, not only when a send fails: a quiet game's polls produce nothing
        // to send, and a relay whose watcher left would otherwise poll it for ever.
        if sink.is_closed() {
            return;
        }
        let params = json!({
            "name": EVENTS_WAIT_TOOL,
            "arguments": subscription.arguments(&instance, &state),
            "_meta": { QUIET_META_KEY: true },
        });
        let failure = match call.call("tools/call", params).await {
            Ok(result) if result.get("isError").and_then(Value::as_bool) == Some(true) => {
                let text = result_text(&result);
                if is_missing_tool(&text) {
                    let _ = sink
                        .send(json!({
                            "type": "unsupported",
                            "instance": instance,
                            "message": "this game's MCMCP has no game_events_wait; update the mod to \
                                        get its events",
                        }))
                        .await;
                    return;
                }
                text
            }
            Ok(result) => match interpret_result(&instance, &result) {
                Some(outcome) => {
                    for line in outcome.lines {
                        if sink.send(line).await.is_err() {
                            return;
                        }
                    }
                    state = outcome.next;
                    states
                        .lock()
                        .expect("states lock")
                        .insert(instance.clone(), state.clone());
                    backoff = Duration::from_secs(1);
                    last_error = None;
                    continue;
                }
                None => "the game's answer had no stream cursor".to_string(),
            },
            Err(_) if call.is_closed() => return,
            Err(error) => format!("{error:#}"),
        };

        // A game quitting fails the poll it was holding, and the next one finds no such instance.
        // Both are the quit, which `link down` says once; as errors they woke a watcher twice more
        // for something ordinary.
        if !still_connected(call.as_ref(), &instance).await {
            if departed.lock().expect("departed lock").insert(instance.clone()) {
                let _ = sink
                    .send(json!({"type": "link", "instance": instance, "state": "down"}))
                    .await;
            }
            return;
        }

        // Said once per distinct failure, not once per retry: a game refusing every poll for an hour
        // is one line, not a hundred and twenty.
        if last_error.as_deref() != Some(failure.as_str()) {
            if sink
                .send(json!({"type": "error", "instance": instance, "message": failure}))
                .await
                .is_err()
            {
                return;
            }
            last_error = Some(failure);
        }
        tokio::time::sleep(backoff).await;
        backoff = (backoff * 2).min(MAX_BACKOFF);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_cursor_round_trips_through_its_string() {
        let text = format_cursor("alto.client", "a1b2c3d4", 1234);
        assert_eq!(text, "alto.client@a1b2c3d4:1234");
        let (instance, cursor) = parse_cursor(&text).unwrap();
        assert_eq!(instance, "alto.client");
        assert_eq!(
            cursor,
            Cursor {
                stream: "a1b2c3d4".into(),
                seq: 1234
            }
        );
    }

    #[test]
    fn a_malformed_cursor_is_refused_with_an_example() {
        for bad in ["", "alto", "alto@stream", "alto@stream:x", "@s:1", "alto@:1"] {
            let error = parse_cursor(bad).unwrap_err();
            assert!(error.contains("alto.client@"), "{bad}: {error}");
        }
    }

    #[test]
    fn a_query_string_fills_every_filter() {
        let subscription = Subscription::from_query(
            "token=secret&types=chat,join&type=leave&players=AngelWingsPanda&exclude-players=Me\
             &contains=%40Claude+hi&instance=a.client&resume=a.client%40s1%3A7&backlog=1",
        )
        .unwrap();
        assert_eq!(subscription.types, vec!["chat", "join", "leave"]);
        assert_eq!(subscription.players, vec!["AngelWingsPanda"]);
        assert_eq!(subscription.exclude_players, vec!["Me"]);
        assert_eq!(subscription.contains.as_deref(), Some("@Claude hi"));
        assert_eq!(subscription.instances, vec!["a.client"]);
        assert_eq!(
            subscription.resume.get("a.client"),
            Some(&Cursor {
                stream: "s1".into(),
                seq: 7
            })
        );
        assert!(subscription.backlog);
    }

    #[test]
    fn a_subscription_survives_the_round_trip_through_a_query_string() {
        let mut subscription = Subscription {
            types: vec!["chat".into(), "join".into()],
            players: vec!["AngelWingsPanda".into()],
            exclude_players: vec!["MicaAlex".into()],
            contains: Some("@Claude & co, 100%".into()),
            instances: vec!["alto.client".into()],
            backlog: true,
            ..Default::default()
        };
        subscription.add_resume("alto.client@a1b2:99").unwrap();
        let query = subscription.to_query();
        assert!(!query.contains(' ') && !query.contains('@'), "{query}");
        assert_eq!(Subscription::from_query(&query).unwrap(), subscription);
    }

    #[test]
    fn an_unknown_event_type_or_parameter_is_refused() {
        assert!(
            Subscription::from_query("types=chat,jion")
                .unwrap_err()
                .contains("jion")
        );
        assert!(
            Subscription::from_query("colour=red")
                .unwrap_err()
                .contains("colour")
        );
    }

    #[test]
    fn percent_decoding_keeps_invalid_escapes_as_written() {
        assert_eq!(percent_decode("a%20b+c"), "a b c");
        assert_eq!(percent_decode("100%"), "100%");
        assert_eq!(percent_decode("%zz"), "%zz");
        assert_eq!(percent_decode("%C3%A9"), "é");
    }

    #[test]
    fn a_resumed_instance_starts_at_its_cursor_and_others_start_now_or_at_the_backlog() {
        let mut subscription = Subscription::default();
        subscription.add_resume("a.client@s1:7").unwrap();
        assert_eq!(
            subscription.initial_state("a.client"),
            PollState {
                stream: Some("s1".into()),
                since: Some(7)
            }
        );
        assert_eq!(subscription.initial_state("b.server"), PollState::default());
        subscription.backlog = true;
        assert_eq!(
            subscription.initial_state("b.server"),
            PollState {
                stream: None,
                since: Some(0)
            }
        );
    }

    #[test]
    fn poll_arguments_carry_the_filters_and_the_cursor_but_omit_what_is_unset() {
        let subscription = Subscription {
            types: vec!["chat".into()],
            contains: Some("@Claude".into()),
            ..Default::default()
        };
        let arguments = subscription.arguments("a.client", &PollState::default());
        assert_eq!(arguments["instance"], "a.client");
        assert_eq!(arguments["types"], json!(["chat"]));
        assert_eq!(arguments["contains"], "@Claude");
        assert!(arguments.get("since").is_none());
        assert!(arguments.get("players").is_none());

        let arguments = subscription.arguments(
            "a.client",
            &PollState {
                stream: Some("s1".into()),
                since: Some(9),
            },
        );
        assert_eq!(arguments["since"], 9);
        assert_eq!(arguments["stream"], "s1");
    }

    #[test]
    fn events_are_stamped_with_their_instance_and_a_cursor_and_the_next_cursor_is_kept() {
        let result = json!({
            "structuredContent": {
                "stream": "s1",
                "next": 12,
                "events": [{"seq": 10, "type": "chat", "player": "P", "text": "@Claude hi"}],
            }
        });
        let outcome = interpret_result("a.client", &result).unwrap();
        assert_eq!(outcome.lines.len(), 1);
        assert_eq!(outcome.lines[0]["instance"], "a.client");
        assert_eq!(outcome.lines[0]["cursor"], "a.client@s1:10");
        assert_eq!(outcome.lines[0]["text"], "@Claude hi");
        assert_eq!(
            outcome.next,
            PollState {
                stream: Some("s1".into()),
                since: Some(12)
            }
        );
    }

    #[test]
    fn a_reset_and_missed_events_become_notices_ahead_of_the_events() {
        let result = json!({
            "structuredContent": {
                "stream": "s2", "next": 3, "reset": true, "missed": 4,
                "events": [{"seq": 1, "type": "join", "player": "P"}],
            }
        });
        let outcome = interpret_result("a.server", &result).unwrap();
        let kinds: Vec<&str> = outcome
            .lines
            .iter()
            .map(|line| line["type"].as_str().unwrap())
            .collect();
        assert_eq!(kinds, vec!["reset", "missed", "join"]);
        assert_eq!(outcome.lines[1]["count"], 4);
    }

    #[test]
    fn a_result_without_structured_content_is_read_from_its_json_text_block() {
        let result = json!({"content": [
            {"type": "text", "text": "[instance a.client · Alto]"},
            {"type": "text", "text": "{\"stream\":\"s1\",\"next\":5,\"events\":[],\"timedOut\":true}"},
        ]});
        let outcome = interpret_result("a.client", &result).unwrap();
        assert!(outcome.lines.is_empty());
        assert_eq!(outcome.next.since, Some(5));
    }

    #[test]
    fn an_answer_that_is_not_from_the_events_tool_has_no_outcome() {
        assert!(interpret_result("a", &json!({"structuredContent": {"next": 1}})).is_none());
        assert!(interpret_result("a", &json!({"content": []})).is_none());
    }

    #[test]
    fn only_ready_connected_instances_are_polled() {
        let result = json!({"structuredContent": {"connected": [
            {"instance": "a.client", "ready": true},
            {"instance": "b.server", "ready": false},
            {"instance": "c.client"},
        ]}});
        assert_eq!(connected_instances(&result), vec!["a.client", "c.client"]);
    }

    /// A pretend orchestrator: one instance, answering polls from a script of results.
    struct Scripted {
        polls: Mutex<Vec<Value>>,
        seen: Mutex<Vec<Value>>,
    }

    impl McpCall for Scripted {
        fn call(&self, _method: &str, params: Value) -> BoxFuture<'_, Result<Value>> {
            let name = params["name"].as_str().unwrap_or("").to_string();
            let answer = if name == "mcmcp_instances" {
                Some(json!({"structuredContent": {"connected": [{"instance": "a.client", "ready": true}]}}))
            } else {
                self.seen.lock().unwrap().push(params.clone());
                let mut polls = self.polls.lock().unwrap();
                if polls.is_empty() {
                    None
                } else {
                    Some(polls.remove(0))
                }
            };
            Box::pin(async move {
                match answer {
                    Some(answer) => Ok(answer),
                    // Out of script: wait like a quiet game.
                    None => {
                        tokio::time::sleep(Duration::from_secs(3600)).await;
                        bail!("unreachable")
                    }
                }
            })
        }
    }

    #[tokio::test]
    async fn the_relay_streams_events_and_carries_the_cursor_into_the_next_poll() {
        let scripted = Arc::new(Scripted {
            polls: Mutex::new(vec![
                json!({"structuredContent": {"stream": "s1", "next": 3,
                    "events": [{"seq": 3, "type": "chat", "player": "P", "text": "hi"}]}}),
                json!({"structuredContent": {"stream": "s1", "next": 4,
                    "events": [{"seq": 4, "type": "leave", "player": "P"}]}}),
            ]),
            seen: Mutex::new(Vec::new()),
        });
        let (sink, mut lines) = mpsc::channel(16);
        let relay = tokio::spawn(run(scripted.clone(), Subscription::default(), sink));

        let mut received = Vec::new();
        for _ in 0..3 {
            received.push(
                tokio::time::timeout(Duration::from_secs(5), lines.recv())
                    .await
                    .unwrap()
                    .unwrap(),
            );
        }
        assert_eq!(
            received[0],
            json!({"type": "link", "instance": "a.client", "state": "up"})
        );
        assert_eq!(received[1]["cursor"], "a.client@s1:3");
        assert_eq!(received[2]["type"], "leave");

        // The second poll resumed from the first's cursor, and every poll asked to stay quiet.
        let seen = scripted.seen.lock().unwrap().clone();
        assert!(seen[0]["arguments"].get("since").is_none());
        assert_eq!(seen[1]["arguments"]["since"], 3);
        assert_eq!(seen[1]["arguments"]["stream"], "s1");
        assert!(seen.iter().all(|call| call["_meta"][QUIET_META_KEY] == true));

        drop(lines);
        relay.abort();
    }

    /// A pretend orchestrator with one game whose first poll fails. With `quits`, the game has left
    /// the roster by the time anyone looks again; without, it is still there.
    struct FailingPoll {
        quits: bool,
        failed: std::sync::atomic::AtomicBool,
    }

    impl McpCall for FailingPoll {
        fn call(&self, _method: &str, params: Value) -> BoxFuture<'_, Result<Value>> {
            let listing = params["name"] == "mcmcp_instances";
            let failed = self.failed.load(Ordering::SeqCst);
            if !listing {
                self.failed.store(true, Ordering::SeqCst);
            }
            let quits = self.quits;
            Box::pin(async move {
                if listing {
                    let connected = if quits && failed {
                        json!([])
                    } else {
                        json!([{"instance": "a.client", "ready": true}])
                    };
                    return Ok(json!({"structuredContent": {"connected": connected}}));
                }
                if failed {
                    // Every poll after the first: a quiet game.
                    tokio::time::sleep(Duration::from_secs(3600)).await;
                }
                Ok(json!({
                    "isError": true,
                    "content": [{"type": "text", "text": "instance 'a.client' did not complete that \
                        call: the link to this instance closed before it answered"}],
                }))
            })
        }
    }

    /// Every line the relay sends in two minutes of (paused, so instant) time.
    async fn lines_over_two_minutes(call: Arc<dyn McpCall>) -> Vec<Value> {
        let (sink, mut lines) = mpsc::channel(64);
        let relay = tokio::spawn(run(call, Subscription::default(), sink));
        let mut received = Vec::new();
        let deadline = tokio::time::Instant::now() + Duration::from_secs(120);
        while let Ok(Some(line)) = tokio::time::timeout_at(deadline, lines.recv()).await {
            received.push(line);
        }
        relay.abort();
        received
    }

    #[tokio::test(start_paused = true)]
    async fn a_game_that_quits_mid_poll_is_one_link_down_and_no_errors() {
        let received = lines_over_two_minutes(Arc::new(FailingPoll {
            quits: true,
            failed: Default::default(),
        }))
        .await;
        assert_eq!(
            received,
            vec![
                json!({"type": "link", "instance": "a.client", "state": "up"}),
                json!({"type": "link", "instance": "a.client", "state": "down"}),
            ]
        );
    }

    #[tokio::test(start_paused = true)]
    async fn a_failing_poll_on_a_game_still_connected_is_reported_as_an_error() {
        let received = lines_over_two_minutes(Arc::new(FailingPoll {
            quits: false,
            failed: Default::default(),
        }))
        .await;
        let kinds: Vec<&str> = received
            .iter()
            .map(|line| line["type"].as_str().unwrap())
            .collect();
        assert_eq!(kinds, vec!["link", "error"]);
        assert_eq!(received[1]["instance"], "a.client");
    }

    #[tokio::test]
    async fn a_game_without_the_tool_is_reported_once_and_not_polled_again() {
        let scripted = Arc::new(Scripted {
            polls: Mutex::new(vec![json!({
                "isError": true,
                "content": [{"type": "text", "text": "The tool 'game_events_wait' is not available on instance 'a.client'."}],
            })]),
            seen: Mutex::new(Vec::new()),
        });
        let (sink, mut lines) = mpsc::channel(16);
        let relay = tokio::spawn(run(scripted.clone(), Subscription::default(), sink));
        let _up = lines.recv().await.unwrap();
        let unsupported = tokio::time::timeout(Duration::from_secs(5), lines.recv())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(unsupported["type"], "unsupported");
        tokio::time::sleep(Duration::from_millis(100)).await;
        assert_eq!(scripted.seen.lock().unwrap().len(), 1);
        relay.abort();
    }
}
