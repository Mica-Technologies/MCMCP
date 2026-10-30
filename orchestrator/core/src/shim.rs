//! The stdio shim's relay, which outlives the app's connection (#159).
//!
//! The shim is what an MCP client spawns when the desktop app serves it (see [`crate::mcp_socket`]).
//! It used to be a pure pump that exited when the app dropped it, and the client's whole MCP session
//! went with it: every `mcp__minecraft__*` tool vanished mid-task, and whether the client restarted
//! the server was up to the client. Now the shim keeps the client's side and reconnects underneath:
//!
//! - a request the app had not answered when the connection went is answered here, as a tool error
//!   for `tools/call` (whose effect is unknown, so it says to check before retrying) and a JSON-RPC
//!   error for anything else, so nothing the client is waiting on hangs forever;
//! - while the app is away, `ping` is answered here and other requests wait, up to
//!   [`OFFLINE_WAIT`], for it to come back; a request that waits longer is answered with an error;
//! - once reattached, the client's `initialize` (and `notifications/initialized`) is replayed under
//!   an id of the shim's own, whose answer is swallowed, then the waiting requests are sent, and the
//!   client is told `notifications/tools/list_changed`, since what is connected may have moved.
//!
//! It still only *observes* the traffic it relays: lines go through byte for byte, and what it parses
//! is only enough to know the above. The bookkeeping is [`ShimState`], which is plain and tested;
//! [`run`] is the loop around it.

use anyhow::{Context, Result, bail};
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet, VecDeque};
use std::future::Future;
use std::time::{Duration, Instant};
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::sync::mpsc;
use tracing::{info, warn};

use crate::jsonrpc;
use crate::link::framing;

/// How long a request made while the app is away waits for it before being answered with an error.
pub const OFFLINE_WAIT: Duration = Duration::from_secs(20);

/// The most requests held while the app is away. Past it, new ones are answered at once.
const OFFLINE_QUEUE: usize = 64;

/// Pauses between reconnection attempts; the last repeats.
const RECONNECT_BACKOFF: [Duration; 5] = [
    Duration::from_millis(100),
    Duration::from_millis(250),
    Duration::from_millis(500),
    Duration::from_secs(1),
    Duration::from_secs(2),
];

/// How long an accepted connection may take to answer the attach.
const ATTACH_TIMEOUT: Duration = Duration::from_secs(10);

/// Prefix of the ids the shim uses for the requests it replays. Their answers are not the client's.
const REPLAY_PREFIX: &str = "mcmcp-shim-replay-";

const LOST_DURING_CALL: &str = "The connection to the MCMCP orchestrator app dropped while this call was \
     running, so its result is lost and whether it took effect is unknown. The shim is reconnecting; \
     check the game's state before retrying.";

const APP_AWAY: &str = "The MCMCP orchestrator app is not reachable right now; the shim kept trying \
     for 20 seconds. Make sure the app is running, then retry.";

/// What the shim knows about the client's session, to fail, hold and replay across reconnects.
#[derive(Debug, Default)]
pub struct ShimState {
    /// The client's `initialize` request, as sent, for replaying.
    initialize: Option<Value>,
    initialized: bool,
    /// Requests sent to the app and not yet answered: id key to (id, method).
    in_flight: HashMap<String, (Value, String)>,
    /// Ids of replayed requests, whose answers stop here.
    replays: HashSet<String>,
    next_replay: u64,
    /// Lines from the client held while the app is away, with when they arrived.
    held: VecDeque<(Instant, String)>,
}

/// What to do with a line from the client while the app is away.
#[derive(Debug, PartialEq)]
pub enum Offline {
    /// Write this to the client now.
    Answer(Value),
    /// Held until the app is back.
    Held,
    /// A notification with no room to hold it.
    Dropped,
}

impl ShimState {
    pub fn new() -> Self {
        Self::default()
    }

    /// Notes a line from the client on its way to the app.
    pub fn from_client(&mut self, line: &str) {
        let Ok(message) = serde_json::from_str::<Value>(line) else {
            return;
        };
        match (jsonrpc::method_of(&message), jsonrpc::id_of(&message)) {
            (Some("initialize"), Some(_)) => {
                self.initialize = Some(message.clone());
                self.note_request(&message);
            }
            (Some("notifications/initialized"), None) => self.initialized = true,
            (Some(_), Some(_)) => self.note_request(&message),
            _ => {}
        }
    }

    fn note_request(&mut self, message: &Value) {
        if let (Some(id), Some(method)) = (jsonrpc::id_of(message), jsonrpc::method_of(message)) {
            self.in_flight.insert(id_key(&id), (id, method.to_string()));
        }
    }

    /// Notes a line from the app. Returns whether it goes on to the client.
    pub fn from_app(&mut self, line: &str) -> bool {
        let Ok(message) = serde_json::from_str::<Value>(line) else {
            return true;
        };
        if !jsonrpc::is_response(&message) {
            return true;
        }
        let Some(id) = message.get("id") else {
            return true;
        };
        let key = id_key(id);
        if self.replays.remove(&key) {
            return false;
        }
        self.in_flight.remove(&key);
        true
    }

    /// Answers every request the app had not answered when its connection went.
    pub fn fail_in_flight(&mut self) -> Vec<Value> {
        self.replays.clear();
        self.in_flight
            .drain()
            .map(|(_, (id, method))| answer_failure(id, &method, LOST_DURING_CALL))
            .collect()
    }

    /// Decides what to do with a client line while the app is away.
    pub fn while_away(&mut self, line: String, now: Instant) -> Offline {
        let message = serde_json::from_str::<Value>(&line).ok();
        let method = message.as_ref().and_then(jsonrpc::method_of);
        let id = message.as_ref().and_then(jsonrpc::id_of);
        if let (Some("ping"), Some(id)) = (method, &id) {
            // Answered here: a client that pings to check the server is alive should hear that the
            // part of it it talks to is.
            return Offline::Answer(jsonrpc::result(id.clone(), json!({})));
        }
        if self.held.len() >= OFFLINE_QUEUE {
            return match (method, id) {
                (Some(method), Some(id)) => Offline::Answer(answer_failure(id, method, APP_AWAY)),
                _ => Offline::Dropped,
            };
        }
        self.held.push_back((now, line));
        Offline::Held
    }

    /// Answers and forgets held requests that have waited past [`OFFLINE_WAIT`].
    pub fn expire_held(&mut self, now: Instant) -> Vec<Value> {
        let mut answers = Vec::new();
        while let Some((since, _)) = self.held.front() {
            if now.duration_since(*since) < OFFLINE_WAIT {
                break;
            }
            let (_, line) = self.held.pop_front().expect("front exists");
            if let Ok(message) = serde_json::from_str::<Value>(&line)
                && let (Some(method), Some(id)) = (jsonrpc::method_of(&message), jsonrpc::id_of(&message))
            {
                answers.push(answer_failure(id, method, APP_AWAY));
            }
            // A held notification that waited this long is simply dropped.
        }
        answers
    }

    /// When the oldest held line will expire, if any is held.
    pub fn next_expiry(&self) -> Option<Instant> {
        self.held.front().map(|(since, _)| *since + OFFLINE_WAIT)
    }

    /// The lines to send a fresh connection before anything else: the replayed handshake, then
    /// whatever was held. Held lines are noted as in flight as they are taken.
    pub fn reattach(&mut self) -> Vec<String> {
        let mut lines = Vec::new();
        if let Some(initialize) = &self.initialize {
            let mut replay = initialize.clone();
            let id = format!("{REPLAY_PREFIX}{}", self.next_replay);
            self.next_replay += 1;
            replay["id"] = json!(id);
            self.replays.insert(id_key(&json!(id)));
            lines.push(replay.to_string());
            if self.initialized {
                lines.push(jsonrpc::notification("notifications/initialized", None).to_string());
            }
        }
        let held: Vec<String> = self.held.drain(..).map(|(_, line)| line).collect();
        for line in held {
            self.from_client(&line);
            lines.push(line);
        }
        lines
    }

    /// Whether the client has initialized, so a `list_changed` means something to it.
    pub fn has_session(&self) -> bool {
        self.initialize.is_some()
    }
}

fn answer_failure(id: Value, method: &str, text: &str) -> Value {
    crate::mcp_socket::failure_answer(id, method, text)
}

fn id_key(id: &Value) -> String {
    match id {
        Value::String(text) => format!("s:{text}"),
        other => format!("n:{other}"),
    }
}

/// An attached connection to the app.
pub struct AppConnection {
    lines: tokio::io::Lines<BufReader<OwnedReadHalf>>,
    writer: OwnedWriteHalf,
}

/// Presents the token and waits to be let in.
pub async fn attach(stream: TcpStream, token: &str) -> Result<AppConnection> {
    let _ = stream.set_nodelay(true);
    let (read_half, mut writer) = stream.into_split();
    let mut reader = BufReader::new(read_half);
    framing::write_frame(&mut writer, &json!({ "type": "attach", "token": token })).await?;
    let answer = tokio::time::timeout(ATTACH_TIMEOUT, framing::read_frame(&mut reader))
        .await
        .context("the orchestrator accepted the connection but did not answer the attach")??;
    match answer {
        Some(frame) if frame.get("type").and_then(Value::as_str) == Some("attached") => {}
        Some(frame) => bail!(
            "the orchestrator refused this connection: {}",
            frame
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("no reason given")
        ),
        None => bail!("the orchestrator closed the connection without answering"),
    }
    Ok(AppConnection {
        lines: reader.lines(),
        writer,
    })
}

/// Why a connection to the app ended.
enum Lost {
    ClientDone,
    App(String),
}

/// Relays between a client and the app until the client closes its input.
///
/// `connect(launch)` returns an attached connection; `launch` is true when the shim has reason to
/// start the app — the first connection, and the first request the client makes during an outage,
/// since a request is the client saying it wants the app now. A failed first connection is an error,
/// as before; once one has succeeded, the shim never gives up on the next.
pub async fn run<I, O, C, F>(client_in: I, mut client_out: O, mut connect: C) -> Result<()>
where
    I: AsyncRead + Unpin + Send + 'static,
    O: AsyncWrite + Unpin,
    C: FnMut(bool) -> F,
    F: Future<Output = Result<AppConnection>>,
{
    let mut connection = connect(true).await?;

    // The client's input in its own task, so it can be read whether or not an app is attached.
    let (lines_tx, mut from_client) = mpsc::unbounded_channel::<String>();
    tokio::spawn(async move {
        let mut lines = BufReader::new(client_in).lines();
        while let Ok(Some(line)) = lines.next_line().await {
            if !line.trim().is_empty() && lines_tx.send(line).is_err() {
                break;
            }
        }
    });

    let mut state = ShimState::new();
    loop {
        match relay(&mut connection, &mut from_client, &mut client_out, &mut state).await? {
            Lost::ClientDone => {
                info!("the MCP client closed its input");
                return Ok(());
            }
            Lost::App(reason) => {
                warn!(%reason, "lost the orchestrator app; reconnecting");
            }
        }
        for answer in state.fail_in_flight() {
            write_client(&mut client_out, &answer.to_string()).await?;
        }

        let Some(reconnected) =
            reconnect(&mut connect, &mut from_client, &mut client_out, &mut state).await?
        else {
            info!("the MCP client closed its input while the app was away");
            return Ok(());
        };
        connection = reconnected;
        info!("reattached to the orchestrator app");
        let mut sent = true;
        for line in state.reattach() {
            if write_app(&mut connection.writer, &line).await.is_err() {
                sent = false;
                break;
            }
        }
        if sent && state.has_session() {
            // What is connected may have changed while away; a client that re-lists finds out.
            let changed = jsonrpc::notification("notifications/tools/list_changed", None);
            write_client(&mut client_out, &changed.to_string()).await?;
        }
    }
}

async fn relay<O: AsyncWrite + Unpin>(
    connection: &mut AppConnection,
    from_client: &mut mpsc::UnboundedReceiver<String>,
    client_out: &mut O,
    state: &mut ShimState,
) -> Result<Lost> {
    loop {
        tokio::select! {
            line = from_client.recv() => {
                let Some(line) = line else { return Ok(Lost::ClientDone) };
                state.from_client(&line);
                if let Err(error) = write_app(&mut connection.writer, &line).await {
                    return Ok(Lost::App(format!("writing to it failed: {error}")));
                }
            }
            line = connection.lines.next_line() => match line {
                Ok(Some(line)) => {
                    if state.from_app(&line) {
                        write_client(client_out, &line).await?;
                    }
                }
                Ok(None) => return Ok(Lost::App("it closed the connection".into())),
                Err(error) => return Ok(Lost::App(format!("reading from it failed: {error}"))),
            },
        }
    }
}

/// Tries to reattach until it works, serving the client meanwhile. `None` when the client leaves.
async fn reconnect<O, C, F>(
    connect: &mut C,
    from_client: &mut mpsc::UnboundedReceiver<String>,
    client_out: &mut O,
    state: &mut ShimState,
) -> Result<Option<AppConnection>>
where
    O: AsyncWrite + Unpin,
    C: FnMut(bool) -> F,
    F: Future<Output = Result<AppConnection>>,
{
    let mut attempt = 0usize;
    let mut wants_launch = false;
    let mut launched = false;
    let mut next_try = tokio::time::Instant::now();
    // The attempt in progress, polled beside the client rather than awaited: a connection that is
    // accepted and never answered (an app starting up, or wedged) must not stop the shim noticing
    // that its client has gone.
    let mut dialing: Option<std::pin::Pin<Box<F>>> = None;
    loop {
        if dialing.is_none() && tokio::time::Instant::now() >= next_try {
            let launch = wants_launch && !launched;
            launched |= launch;
            dialing = Some(Box::pin(connect(launch)));
        }
        let expiry = state
            .next_expiry()
            .map(tokio::time::Instant::from_std)
            .unwrap_or(next_try + Duration::from_secs(3600));
        tokio::select! {
            line = from_client.recv() => {
                let Some(line) = line else { return Ok(None) };
                let is_request = serde_json::from_str::<Value>(&line)
                    .is_ok_and(|message| jsonrpc::is_request(&message));
                match state.while_away(line, Instant::now()) {
                    Offline::Answer(answer) => write_client(client_out, &answer.to_string()).await?,
                    Offline::Held => wants_launch |= is_request,
                    Offline::Dropped => {}
                }
            }
            _ = tokio::time::sleep_until(expiry) => {
                for answer in state.expire_held(Instant::now()) {
                    write_client(client_out, &answer.to_string()).await?;
                }
            }
            _ = tokio::time::sleep_until(next_try), if dialing.is_none() => {}
            result = async { dialing.as_mut().expect("guarded").await }, if dialing.is_some() => {
                dialing = None;
                match result {
                    Ok(connection) => return Ok(Some(connection)),
                    Err(error) => {
                        if attempt == 0 || attempt % 30 == 0 {
                            warn!(error = %format!("{error:#}"), attempt, "the orchestrator app is still unreachable");
                        }
                        let delay = RECONNECT_BACKOFF[attempt.min(RECONNECT_BACKOFF.len() - 1)];
                        attempt += 1;
                        next_try = tokio::time::Instant::now() + delay;
                    }
                }
            }
        }
    }
}

async fn write_app(writer: &mut OwnedWriteHalf, line: &str) -> std::io::Result<()> {
    writer.write_all(line.as_bytes()).await?;
    writer.write_all(b"\n").await?;
    writer.flush().await
}

async fn write_client<O: AsyncWrite + Unpin>(client_out: &mut O, line: &str) -> Result<()> {
    client_out
        .write_all(line.as_bytes())
        .await
        .context("writing to the MCP client")?;
    client_out
        .write_all(b"\n")
        .await
        .context("writing to the MCP client")?;
    client_out.flush().await.context("writing to the MCP client")?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request(id: Value, method: &str) -> String {
        json!({"jsonrpc": "2.0", "id": id, "method": method, "params": {}}).to_string()
    }

    #[test]
    fn a_call_in_flight_when_the_app_drops_is_answered_rather_than_left_hanging() {
        let mut state = ShimState::new();
        state.from_client(&request(json!(1), "tools/call"));
        state.from_client(&request(json!("r"), "resources/read"));
        state.from_client(&request(json!(2), "ping"));
        assert!(state.from_app(&json!({"jsonrpc": "2.0", "id": 2, "result": {}}).to_string()));

        let mut answers = state.fail_in_flight();
        answers.sort_by_key(|answer| answer["id"].to_string());
        assert_eq!(answers.len(), 2, "the answered ping is not failed again");
        // tools/call: a tool error the model reads, not a protocol error it never sees.
        let call = answers.iter().find(|answer| answer["id"] == 1).unwrap();
        assert_eq!(call["result"]["isError"], true);
        assert!(
            call["result"]["content"][0]["text"]
                .as_str()
                .unwrap()
                .contains("check")
        );
        let read = answers.iter().find(|answer| answer["id"] == "r").unwrap();
        assert!(read.get("error").is_some());
        assert!(state.fail_in_flight().is_empty());
    }

    #[test]
    fn reattaching_replays_the_handshake_under_the_shims_own_id_and_swallows_its_answer() {
        let mut state = ShimState::new();
        let initialize = json!({"jsonrpc": "2.0", "id": 0, "method": "initialize",
                                "params": {"protocolVersion": "2025-06-18", "capabilities": {}}});
        state.from_client(&initialize.to_string());
        state.from_client(&json!({"jsonrpc": "2.0", "method": "notifications/initialized"}).to_string());
        assert!(state.from_app(&json!({"jsonrpc": "2.0", "id": 0, "result": {}}).to_string()));

        let lines = state.reattach();
        assert_eq!(lines.len(), 2);
        let replay: Value = serde_json::from_str(&lines[0]).unwrap();
        assert_eq!(replay["method"], "initialize");
        assert_eq!(replay["params"], initialize["params"]);
        assert_ne!(replay["id"], initialize["id"], "the client's id is not reused");
        assert!(lines[1].contains("notifications/initialized"));

        let answer = json!({"jsonrpc": "2.0", "id": replay["id"], "result": {}});
        assert!(
            !state.from_app(&answer.to_string()),
            "the replay's answer is not the client's"
        );
        assert!(state.fail_in_flight().is_empty());
    }

    #[test]
    fn a_ping_while_the_app_is_away_is_answered_and_a_call_waits_for_it() {
        let mut state = ShimState::new();
        let now = Instant::now();
        assert_eq!(
            state.while_away(request(json!(5), "ping"), now),
            Offline::Answer(json!({"jsonrpc": "2.0", "id": 5, "result": {}}))
        );
        assert_eq!(
            state.while_away(request(json!(6), "tools/call"), now),
            Offline::Held
        );

        let lines = state.reattach();
        assert_eq!(lines, vec![request(json!(6), "tools/call")]);
        // Sent on the new connection, so it is in flight there and failed if that drops too.
        assert_eq!(state.fail_in_flight().len(), 1);
    }

    #[test]
    fn a_request_held_past_the_wait_is_answered_with_an_error() {
        let mut state = ShimState::new();
        let then = Instant::now();
        state.while_away(request(json!(7), "tools/call"), then);
        state.while_away(
            json!({"jsonrpc": "2.0", "method": "notifications/cancelled"}).to_string(),
            then,
        );
        assert!(state.expire_held(then + Duration::from_secs(1)).is_empty());

        let answers = state.expire_held(then + OFFLINE_WAIT);
        assert_eq!(answers.len(), 1, "the notification is dropped, not answered");
        assert_eq!(answers[0]["result"]["isError"], true);
        assert!(state.reattach().is_empty());
    }

    /// A stand-in for the app: accepts one connection and answers the attach.
    async fn accept_attached(app: &tokio::net::TcpListener) -> (BufReader<OwnedReadHalf>, OwnedWriteHalf) {
        let (stream, _) = app.accept().await.unwrap();
        let (read_half, mut write_half) = stream.into_split();
        let mut reader = BufReader::new(read_half);
        let attach = framing::read_frame(&mut reader).await.unwrap().unwrap();
        assert_eq!(attach["type"], "attach");
        framing::write_frame(&mut write_half, &json!({"type": "attached"}))
            .await
            .unwrap();
        (reader, write_half)
    }

    async fn next_json<R: tokio::io::AsyncBufRead + Unpin>(reader: &mut R) -> Value {
        let line = tokio::time::timeout(Duration::from_secs(10), framing::read_line(reader, usize::MAX))
            .await
            .expect("a line in time")
            .unwrap()
            .expect("a line, not the end");
        serde_json::from_str(&line).unwrap()
    }

    #[tokio::test]
    async fn the_clients_session_survives_the_app_dropping_it() {
        let app = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = app.local_addr().unwrap().port();
        let (mut client, shim_in) = tokio::io::duplex(64 * 1024);
        let (shim_out, from_shim) = tokio::io::duplex(64 * 1024);
        let mut from_shim = BufReader::new(from_shim);
        let shim = tokio::spawn(run(shim_in, shim_out, move |_launch| async move {
            attach(TcpStream::connect(("127.0.0.1", port)).await?, "token").await
        }));

        // An ordinary session: initialize, answered.
        let (mut app_in, mut app_out) = accept_attached(&app).await;
        let initialize = json!({"jsonrpc": "2.0", "id": 0, "method": "initialize",
                                "params": {"protocolVersion": "2025-06-18", "capabilities": {}}});
        client
            .write_all(format!("{initialize}\n").as_bytes())
            .await
            .unwrap();
        assert_eq!(next_json(&mut app_in).await["method"], "initialize");
        framing::write_frame(&mut app_out, &json!({"jsonrpc": "2.0", "id": 0, "result": {}}))
            .await
            .unwrap();
        assert_eq!(next_json(&mut from_shim).await["id"], 0);

        // A call is running when the app drops the connection.
        client
            .write_all(format!("{}\n", request(json!(1), "tools/call")).as_bytes())
            .await
            .unwrap();
        assert_eq!(next_json(&mut app_in).await["id"], 1);
        drop((app_in, app_out));
        let failed = next_json(&mut from_shim).await;
        assert_eq!(failed["id"], 1);
        assert_eq!(failed["result"]["isError"], true);

        // The app is back: the handshake is replayed and its answer kept from the client, which is
        // told to re-list instead.
        let (mut app_in, mut app_out) = accept_attached(&app).await;
        let replay = next_json(&mut app_in).await;
        assert_eq!(replay["method"], "initialize");
        framing::write_frame(
            &mut app_out,
            &json!({"jsonrpc": "2.0", "id": replay["id"], "result": {}}),
        )
        .await
        .unwrap();
        assert_eq!(
            next_json(&mut from_shim).await["method"],
            "notifications/tools/list_changed"
        );

        // And the session carries on.
        client
            .write_all(format!("{}\n", request(json!(2), "tools/list")).as_bytes())
            .await
            .unwrap();
        assert_eq!(next_json(&mut app_in).await["id"], 2);
        framing::write_frame(
            &mut app_out,
            &json!({"jsonrpc": "2.0", "id": 2, "result": {"tools": []}}),
        )
        .await
        .unwrap();
        assert_eq!(next_json(&mut from_shim).await["id"], 2);

        drop(client);
        tokio::time::timeout(Duration::from_secs(10), shim)
            .await
            .expect("the shim ends when its client does")
            .unwrap()
            .unwrap();
    }

    #[test]
    fn nothing_is_replayed_for_a_client_that_never_initialized() {
        let mut state = ShimState::new();
        assert!(state.reattach().is_empty());
        assert!(!state.has_session());
    }
}
