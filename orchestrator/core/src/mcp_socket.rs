//! How an MCP client reaches the orchestrator when the orchestrator is a window.
//!
//! # The problem
//!
//! stdio is the right transport for an MCP server: no port, no token in the client's config, and a
//! lifetime tied to the client. But a desktop app cannot use it — its stdin and stdout belong to
//! however it was launched, and an MCP client expects to *spawn* the process it talks to.
//!
//! # The shape
//!
//! The app listens here, and a tiny `shim` process is what the MCP client spawns. The shim owns a
//! real stdin and stdout and pumps bytes between them and this socket:
//!
//! ```text
//!   Claude  <--stdio-->  mcmcp-orchestrator shim  <--tcp-->  the app  <--tcp-->  Minecraft
//! ```
//!
//! The client's configuration therefore contains a command and no port, no token and no URL, and
//! the app can be started, stopped and restarted underneath a connection that never drops — which is
//! the same property that made stdio worth wanting in the first place.
//!
//! # Why NDJSON rather than HTTP
//!
//! Because the link already speaks it, and this reuses [`crate::link::framing`] whole. An HTTP
//! transport would mean a second wire format, a second set of framing bugs, and a shim that has to
//! understand chunked encoding to move a byte. What arrives here is JSON-RPC, one object per line —
//! exactly what arrives on the shim's stdin, which makes the shim a pump rather than a translator.
//!
//! # Authentication
//!
//! A token in a file only this user can read, presented in an `attach` frame. Loopback alone is not
//! quite enough: any process on the machine can connect to a loopback port, and this one can drive
//! somebody's game. The token is generated on first run and never leaves the state directory.

use anyhow::{Context, Result, bail};
use serde_json::{Value, json};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use tokio::io::BufReader;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;
use tracing::{info, warn};

use crate::client_calls::ClientCalls;
use crate::jsonrpc;
use crate::link::framing;
use crate::router::Router;

/// Default port the shim dials. Adjacent to the link port so the pair is memorable.
pub const DEFAULT_MCP_PORT: u16 = 25581;

const TYPE_ATTACH: &str = "attach";
const TYPE_ATTACHED: &str = "attached";
const TYPE_REFUSED: &str = "refused";

pub fn token_path(state_directory: &Path) -> PathBuf {
    state_directory.join("mcp-token")
}

/// Reads the token, generating one on first run.
///
/// Written with owner-only permissions where the platform has them. On Windows the file inherits the
/// user profile's ACL, which is the same practical guarantee — the state directory is already under
/// `%LOCALAPPDATA%`.
pub fn ensure_token(state_directory: &Path) -> Result<String> {
    let path = token_path(state_directory);
    if let Ok(existing) = std::fs::read_to_string(&path) {
        let existing = existing.trim().to_string();
        if !existing.is_empty() {
            return Ok(existing);
        }
    }

    let token = generate_token();
    std::fs::create_dir_all(state_directory)?;
    std::fs::write(&path, &token).with_context(|| format!("writing {}", path.display()))?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600));
    }

    Ok(token)
}

/// Reads the token without creating one.
///
/// What the shim uses: if there is no token, there has never been an app, and saying so is more
/// useful than inventing a token that authenticates against nothing.
pub fn read_token(state_directory: &Path) -> Result<String> {
    let path = token_path(state_directory);
    let token = std::fs::read_to_string(&path)
        .with_context(|| format!("reading {}", path.display()))?
        .trim()
        .to_string();
    if token.is_empty() {
        bail!("{} is empty", path.display());
    }
    Ok(token)
}

fn generate_token() -> String {
    // 128 bits from the OS, via getrandom the way std's HashMap seeds itself. Enough for a value
    // that only ever has to resist guessing by another local process.
    use std::hash::{BuildHasher, Hasher, RandomState};
    let mut token = String::with_capacity(32);
    for _ in 0..2 {
        let mut hasher = RandomState::new().build_hasher();
        hasher.write_usize(std::process::id() as usize);
        token.push_str(&format!("{:016x}", hasher.finish()));
    }
    token
}

/// Accepts MCP clients until the future is dropped.
pub async fn serve(listener: TcpListener, router: Arc<Router>, token: String) -> Result<()> {
    let address = listener
        .local_addr()
        .context("reading the MCP listener address")?;
    info!(%address, "listening for MCP clients");

    loop {
        let (stream, peer) = match listener.accept().await {
            Ok(accepted) => accepted,
            Err(error) => {
                warn!(%error, "failed to accept an MCP client");
                continue;
            }
        };
        let router = Arc::clone(&router);
        let token = token.clone();
        // Supervised, so a panic in the connection task is reported like any other reason the
        // connection ended. Until #159 every reason was a debug line or nothing, and a client that
        // lost the orchestrator several times a day left no trace of why.
        tokio::spawn(async move {
            let caller = Arc::new(crate::caller::Caller::new());
            let session = caller.id().to_string();
            let task = tokio::spawn(handle_client(stream, router, token, caller));
            match task.await {
                Ok(Ok(Ended::ClientClosed)) => info!(%peer, %session, "an MCP client disconnected"),
                Ok(Ok(Ended::Refused)) => {}
                Ok(Ok(Ended::WriteFailed(error))) => {
                    warn!(%peer, %session, %error, "closed an MCP client connection: writing to it failed")
                }
                Ok(Err(error)) => warn!(
                    %peer, %session, error = %format!("{error:#}"),
                    "closed an MCP client connection: reading from it failed"
                ),
                Err(error) => {
                    warn!(%peer, %session, %error, "closed an MCP client connection: its task panicked")
                }
            }
        });
    }
}

/// The largest message written to an MCP client in one line.
///
/// Larger than the link's frame limit on purpose. A game's answer can be up to that limit, and the
/// orchestrator then adds its instance banner; capping here at the same size turned a near-limit
/// screenshot into a dead writer and a closed connection. The shim reads lines without a limit.
pub const MAX_CLIENT_WRITE_BYTES: usize = 64 * 1024 * 1024;

/// Why a connection ended without an error from reading it.
#[derive(Debug)]
enum Ended {
    /// The client closed its side: the ordinary way.
    ClientClosed,
    /// It never presented the token.
    Refused,
    /// Writing to it failed, and nothing more could reach it.
    WriteFailed(String),
}

async fn handle_client(
    stream: TcpStream,
    router: Arc<Router>,
    token: String,
    caller: Arc<crate::caller::Caller>,
) -> Result<Ended> {
    let _ = stream.set_nodelay(true);
    let (read_half, mut write_half) = stream.into_split();
    let mut reader = BufReader::new(read_half);

    let Some(frame) = framing::read_frame(&mut reader).await? else {
        return Ok(Ended::Refused);
    };
    let presented = frame.get("token").and_then(Value::as_str).unwrap_or("");
    if frame.get("type").and_then(Value::as_str) != Some(TYPE_ATTACH) || presented != token {
        let refusal = json!({
            "type": TYPE_REFUSED,
            "message": "the first frame must be an attach carrying the token from the \
                        orchestrator's state directory",
        });
        let _ = framing::write_frame(&mut write_half, &refusal).await;
        warn!("refused an MCP client that did not present the right token");
        return Ok(Ended::Refused);
    }
    framing::write_frame(&mut write_half, &json!({ "type": TYPE_ATTACHED })).await?;
    let session = caller.id().to_string();

    // What this client has asked and not been answered: decides which progress reaches it.
    let calls = Arc::new(Mutex::new(ClientCalls::new()));

    // One task owns the socket's write half, exactly as on the link side: responses come from many
    // request tasks and notifications come from the instances, and two writers interleaving
    // mid-frame would corrupt both. It is also the one place that sees messages in the order they
    // are written, which is what lets progress for a call be dropped once its answer has gone.
    let (outgoing, mut outgoing_rx) = mpsc::unbounded_channel::<Value>();
    let mut writer = {
        let calls = Arc::clone(&calls);
        let session = session.clone();
        tokio::spawn(async move {
            while let Some(message) = outgoing_rx.recv().await {
                let (message, method) = {
                    let mut calls = calls.lock().expect("calls lock");
                    let method = calls.method_answered(&message).map(str::to_string);
                    match calls.outgoing(message) {
                        Some(message) => (message, method),
                        None => continue,
                    }
                };
                let Some(bytes) = encode_for_client(&message, method.as_deref(), &session) else {
                    continue;
                };
                if let Err(error) = framing::write_encoded(&mut write_half, &bytes).await {
                    return Err(format!("{error:#}"));
                }
            }
            Ok(())
        })
    };

    // Notifications the router raises reach the client through the same writer.
    let notifications = router.subscribe_downstream();
    let forwarding = {
        let outgoing = outgoing.clone();
        tokio::spawn(async move {
            let mut notifications = notifications;
            loop {
                match notifications.recv().await {
                    Ok(message) => {
                        if outgoing.send(message).is_err() {
                            break;
                        }
                    }
                    // This client fell behind and lost the oldest notifications. Carry on: a missed
                    // list_changed costs a stale tool list until the next one, while stopping would
                    // cost every notification after it.
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(missed)) => {
                        warn!(missed, "an MCP client fell behind on notifications");
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                }
            }
        })
    };

    // This connection is one agent; its calls carry that to the game (#94).
    loop {
        let line = tokio::select! {
            line = framing::read_line(&mut reader, framing::MAX_FRAME_BYTES) => line,
            written = &mut writer => {
                forwarding.abort();
                return Ok(Ended::WriteFailed(match written {
                    Ok(Err(error)) => error,
                    Ok(Ok(())) => "the writer stopped".to_string(),
                    Err(error) => format!("the writer panicked: {error}"),
                }));
            }
        };
        let line = match line {
            Ok(Some(line)) => line,
            Ok(None) => break,
            Err(error) => {
                // An oversized line, invalid UTF-8 or a failed read: the stream cannot be resynced.
                forwarding.abort();
                return Err(error);
            }
        };

        // A line that is not one JSON object is answered and survived, as on stdio. Ending the
        // connection over it took every call in flight down with it.
        let message: Value = match serde_json::from_str::<Value>(&line) {
            Ok(message) if message.is_object() => message,
            Ok(_) => {
                warn!(%session, "an MCP client sent something other than one JSON object on a line");
                let _ = outgoing.send(jsonrpc::error(
                    None,
                    jsonrpc::INVALID_REQUEST,
                    "send one JSON-RPC object per line; batches are not supported",
                ));
                continue;
            }
            Err(error) => {
                warn!(%session, %error, "an MCP client sent a line that is not JSON");
                let _ = outgoing.send(jsonrpc::error(
                    None,
                    jsonrpc::PARSE_ERROR,
                    "the request was not valid JSON",
                ));
                continue;
            }
        };

        calls.lock().expect("calls lock").incoming(&message);
        caller.observe(&message);
        spawn_request(&router, &caller, &outgoing, message);
    }

    drop(outgoing);
    forwarding.abort();
    let _ = writer.await;
    Ok(Ended::ClientClosed)
}

/// Handles one message in its own task, answering it even if the handler panics.
///
/// Its own task because a tools/call can take minutes, and a client that cannot ping or cancel
/// while one runs has no way to stop it. Supervised because a panicking handler used to drop its
/// answer, and the client waited on that request forever.
fn spawn_request(
    router: &Arc<Router>,
    caller: &Arc<crate::caller::Caller>,
    outgoing: &mpsc::UnboundedSender<Value>,
    message: Value,
) {
    let router = Arc::clone(router);
    let caller = Arc::clone(caller);
    let outgoing = outgoing.clone();
    let id = jsonrpc::id_of(&message).filter(|_| jsonrpc::is_request(&message));
    let method = jsonrpc::method_of(&message).unwrap_or("").to_string();
    tokio::spawn(async move {
        let session = caller.id().to_string();
        let handled = tokio::spawn(async move { router.handle_from(Some(&caller), message).await });
        match handled.await {
            Ok(Some(response)) => {
                let _ = outgoing.send(response);
            }
            Ok(None) => {}
            Err(error) => {
                warn!(%session, %method, %error, "an MCP request handler panicked; answering it with an error");
                if let Some(id) = id {
                    let text = "The orchestrator failed while handling this request; its log has the \
                                details. Whether a tool call took effect is unknown: check before \
                                retrying.";
                    let _ = outgoing.send(failure_answer(id, &method, text));
                }
            }
        }
    });
}

/// An answer for a request that failed in the orchestrator rather than in the game.
///
/// A tool error for `tools/call`, which the model reads; a JSON-RPC error for anything else, which
/// is the client's plumbing's business. The repository's rule, applied to this side.
pub fn failure_answer(id: Value, method: &str, text: &str) -> Value {
    if method == "tools/call" {
        jsonrpc::result(id, crate::router::tool_error(text))
    } else {
        jsonrpc::error(Some(id), jsonrpc::INTERNAL_ERROR, text)
    }
}

/// One line for the client, or `None` for a notification too large to send.
///
/// An answer too large is replaced by one saying so, rather than ending the writer: that ended the
/// connection, and every other call on it, over one oversized result.
fn encode_for_client(message: &Value, method: Option<&str>, session: &str) -> Option<Vec<u8>> {
    let mut bytes = match serde_json::to_vec(message) {
        Ok(bytes) => bytes,
        Err(error) => {
            warn!(%session, %error, "could not serialise a message for an MCP client");
            return None;
        }
    };
    if bytes.len() < MAX_CLIENT_WRITE_BYTES {
        bytes.push(b'\n');
        return Some(bytes);
    }
    let size = bytes.len();
    let Some(id) = jsonrpc::id_of(message).filter(|_| jsonrpc::is_response(message)) else {
        warn!(%session, size, "dropped a notification too large for an MCP client");
        return None;
    };
    warn!(%session, size, "an answer was too large for an MCP client; sending an error in its place");
    let text = format!(
        "The answer was {size} bytes, more than the {MAX_CLIENT_WRITE_BYTES} the orchestrator sends \
         in one message. Ask for less: a smaller region, a lower max_dimension, or a file path."
    );
    let mut bytes = serde_json::to_vec(&failure_answer(id, method.unwrap_or(""), &text)).ok()?;
    bytes.push(b'\n');
    Some(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generates_a_token_once_and_then_reuses_it() {
        // Regenerating on every launch would invalidate a client's shim mid-session for no reason.
        let directory = std::env::temp_dir().join(format!("mcmcp-token-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&directory);
        std::fs::create_dir_all(&directory).unwrap();

        let first = ensure_token(&directory).expect("a token should be generated");
        let second = ensure_token(&directory).expect("the token should be reused");

        assert_eq!(first, second);
        assert_eq!(first.len(), 32);
        let _ = std::fs::remove_dir_all(&directory);
    }

    #[test]
    fn reading_a_token_that_was_never_written_is_an_error() {
        // The shim uses this. "There has never been an app here" is far more useful than inventing
        // a token that authenticates against nothing.
        let directory = std::env::temp_dir().join("mcmcp-token-absent");
        let _ = std::fs::remove_dir_all(&directory);

        assert!(read_token(&directory).is_err());
    }

    #[test]
    fn an_empty_token_file_is_replaced_rather_than_trusted() {
        let directory = std::env::temp_dir().join(format!("mcmcp-token-empty-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        std::fs::write(token_path(&directory), "   \n").unwrap();

        let token = ensure_token(&directory).expect("an empty file should be replaced");

        assert_eq!(token.len(), 32);
        let _ = std::fs::remove_dir_all(&directory);
    }

    #[tokio::test]
    async fn a_line_that_is_not_json_is_answered_and_the_connection_survives() {
        // It used to end the connection, and every call in flight on it, with nothing logged.
        let router = Arc::new(Router::new(
            Arc::new(crate::registry::Registry::new()),
            Arc::new(Mutex::new(
                crate::store::ApprovalStore::load("unused-in-tests.json").unwrap(),
            )),
        ));
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = tokio::spawn(serve(listener, router, "secret".into()));

        let stream = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
        let (read_half, mut write_half) = stream.into_split();
        let mut reader = BufReader::new(read_half);
        framing::write_frame(&mut write_half, &json!({"type": "attach", "token": "secret"}))
            .await
            .unwrap();
        assert_eq!(
            framing::read_frame(&mut reader).await.unwrap().unwrap()["type"],
            "attached"
        );

        use tokio::io::AsyncWriteExt;
        write_half.write_all(b"this is not json\n[1,2]\n").await.unwrap();
        let first = framing::read_frame(&mut reader).await.unwrap().unwrap();
        assert_eq!(first["error"]["code"], jsonrpc::PARSE_ERROR);
        let second = framing::read_frame(&mut reader).await.unwrap().unwrap();
        assert_eq!(second["error"]["code"], jsonrpc::INVALID_REQUEST);

        framing::write_frame(
            &mut write_half,
            &json!({"jsonrpc": "2.0", "id": 1, "method": "ping"}),
        )
        .await
        .unwrap();
        let pong = framing::read_frame(&mut reader).await.unwrap().unwrap();
        assert_eq!(pong["id"], 1);
        assert!(pong.get("result").is_some());
        server.abort();
    }

    #[test]
    fn an_answer_too_large_to_send_is_replaced_by_a_tool_error_saying_so() {
        let huge = "x".repeat(MAX_CLIENT_WRITE_BYTES);
        let answer =
            json!({"jsonrpc": "2.0", "id": 4, "result": {"content": [{"type": "text", "text": huge}]}});
        let bytes = encode_for_client(&answer, Some("tools/call"), "c1").expect("a replacement");
        let replacement: Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(replacement["id"], 4);
        assert_eq!(replacement["result"]["isError"], true);

        let notification =
            json!({"jsonrpc": "2.0", "method": "notifications/message", "params": {"data": huge}});
        assert!(encode_for_client(&notification, None, "c1").is_none());
    }

    #[test]
    fn two_tokens_generated_independently_differ() {
        let first = generate_token();
        let second = generate_token();

        assert_ne!(first, second);
    }
}
