//! Game events over a WebSocket: `ws://127.0.0.1:25582/events?token=…&types=chat,join`.
//!
//! The same stream as `mcmcp-orchestrator events` (see [`crate::event_relay`]), for a watcher that
//! holds a socket rather than running a command: Claude Code's Monitor takes a `ws:` source and turns
//! each text frame into one notification. One frame is one compact JSON object, an event or a notice.
//!
//! # Authentication
//!
//! The MCP socket's token, in the `token` query parameter. A header would be better, and is not
//! possible: a browser's WebSocket and Monitor's `ws:` source can set neither. The listener binds
//! loopback only, compares the token in constant time, and refuses a page from anywhere but this
//! machine by its `Origin` — the defence against a website the user visits opening a socket to it.
//! What it carries is read-only (chat, joins, leaves, deaths), but chat is still not the business of
//! every process on the machine.
//!
//! # Lifetime
//!
//! Every connection is a watcher of one [`Hub`], which holds a single poll per game for all of them
//! and applies each watcher's filter itself; a connection leaves the hub when its socket ends. A
//! ping every [`PING_INTERVAL`] is what notices a peer that vanished without closing.

use anyhow::{Context, Result};
use futures_util::{SinkExt, StreamExt};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{OwnedSemaphorePermit, Semaphore};
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::handshake::server::{ErrorResponse, Request, Response};
use tokio_tungstenite::tungstenite::http::StatusCode;
use tracing::{debug, info, warn};

use crate::event_hub::Hub;
use crate::event_relay::{self, RouterCall, Subscription};
use crate::router::Router;

/// Default port, beside the link (25580) and MCP (25581) ports.
pub const DEFAULT_EVENTS_PORT: u16 = 25582;

/// The most watchers at once. They share one poll per game, so this bounds memory, not game load.
pub const MAX_CLIENTS: usize = 8;

/// How often a quiet connection is pinged.
pub const PING_INTERVAL: Duration = Duration::from_secs(30);

/// Why a connection was turned away, as an HTTP status and a sentence.
#[derive(Debug, PartialEq, Eq)]
pub struct Refusal {
    pub status: u16,
    pub message: String,
}

fn refuse(status: u16, message: impl Into<String>) -> Refusal {
    Refusal {
        status,
        message: message.into(),
    }
}

/// Decides whether a handshake may proceed, and with what subscription.
///
/// Pure, so every rule is a unit test: the path, the token, the origin, the client cap and the
/// filters, in that order.
pub fn authorize(
    path: &str,
    query: Option<&str>,
    origin: Option<&str>,
    token: &str,
    has_slot: bool,
) -> Result<Subscription, Refusal> {
    if path != "/events" && path != "/" {
        return Err(refuse(
            404,
            "the events stream is at /events, as ws://127.0.0.1:<port>/events?token=…",
        ));
    }
    let query = query.unwrap_or("");
    let presented = query
        .split('&')
        .filter_map(|pair| pair.split_once('='))
        .find(|(key, _)| *key == "token")
        .map(|(_, value)| event_relay::percent_decode(value))
        .unwrap_or_default();
    if !constant_time_eq(presented.as_bytes(), token.as_bytes()) {
        return Err(refuse(
            401,
            "missing or wrong token; pass the orchestrator's MCP token as ?token=…. \
             `mcmcp-orchestrator events --ws-url` prints a complete URL.",
        ));
    }
    if let Some(origin) = origin
        && !is_loopback_origin(origin)
    {
        return Err(refuse(
            403,
            format!("origin '{origin}' is not this machine; the events stream serves local pages only"),
        ));
    }
    if !has_slot {
        return Err(refuse(
            503,
            format!("{MAX_CLIENTS} watchers are already connected; close one first"),
        ));
    }
    Subscription::from_query(query).map_err(|message| refuse(400, message))
}

/// Compares two byte strings in time that depends only on their lengths.
pub fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

/// Whether an `Origin` header names this machine.
pub fn is_loopback_origin(origin: &str) -> bool {
    let Some((_, rest)) = origin.split_once("://") else {
        return false;
    };
    let authority = rest.split('/').next().unwrap_or("");
    let host = if let Some(bracketed) = authority.strip_prefix('[') {
        bracketed.split(']').next().unwrap_or("")
    } else {
        authority.split(':').next().unwrap_or("")
    };
    matches!(
        host.to_ascii_lowercase().as_str(),
        "localhost" | "127.0.0.1" | "::1"
    )
}

/// Starts the listener on `127.0.0.1:<port>` in the background; port 0 means off.
///
/// A port already taken is a warning, not a failure: the orchestrator's real job is routing, and the
/// usual cause is a second orchestrator whose own listener is already serving the same games.
pub fn spawn_listener(router: Arc<Router>, port: u16) {
    if port == 0 {
        info!("the events WebSocket is turned off");
        return;
    }
    tokio::spawn(async move {
        let token =
            match crate::paths::state_directory().and_then(|state| crate::mcp_socket::ensure_token(&state)) {
                Ok(token) => token,
                Err(error) => {
                    warn!(%error, "could not prepare the token the events WebSocket checks");
                    return;
                }
            };
        let address = format!("127.0.0.1:{port}");
        match TcpListener::bind(&address).await {
            Ok(listener) => {
                if let Err(error) = serve(listener, router, token).await {
                    warn!(%error, "the events WebSocket stopped");
                }
            }
            Err(error) => warn!(%error, %address, "could not serve the events WebSocket"),
        }
    });
}

/// Accepts watchers until the future is dropped.
pub async fn serve(listener: TcpListener, router: Arc<Router>, token: String) -> Result<()> {
    let address = listener
        .local_addr()
        .context("reading the events listener address")?;
    info!(%address, "serving game events over WebSocket");
    let slots = Arc::new(Semaphore::new(MAX_CLIENTS));
    // One poll per game for every watcher this listener has.
    let hub = Hub::new(Arc::new(RouterCall::new(router)));
    loop {
        let (stream, peer) = match listener.accept().await {
            Ok(accepted) => accepted,
            Err(error) => {
                warn!(%error, "failed to accept an events watcher");
                continue;
            }
        };
        let permit = Arc::clone(&slots).try_acquire_owned().ok();
        let hub = Arc::clone(&hub);
        let token = token.clone();
        tokio::spawn(async move {
            match handle(stream, hub, token, permit).await {
                Ok(()) => debug!(%peer, "an events watcher disconnected"),
                Err(error) => debug!(%peer, error = %format!("{error:#}"), "an events connection ended"),
            }
        });
    }
}

// The handshake callback's error type is tungstenite's own HTTP response, which is large and not ours
// to shrink.
#[allow(clippy::result_large_err)]
async fn handle(
    stream: TcpStream,
    hub: Arc<Hub>,
    token: String,
    permit: Option<OwnedSemaphorePermit>,
) -> Result<()> {
    let _ = stream.set_nodelay(true);
    let has_slot = permit.is_some();
    let decided: Arc<Mutex<Option<Subscription>>> = Arc::new(Mutex::new(None));
    let callback = {
        let decided = Arc::clone(&decided);
        move |request: &Request, response: Response| -> Result<Response, ErrorResponse> {
            let origin = request
                .headers()
                .get("origin")
                .and_then(|value| value.to_str().ok());
            match authorize(
                request.uri().path(),
                request.uri().query(),
                origin,
                &token,
                has_slot,
            ) {
                Ok(subscription) => {
                    *decided.lock().expect("decision lock") = Some(subscription);
                    Ok(response)
                }
                Err(refusal) => {
                    if refusal.status == 401 || refusal.status == 403 {
                        warn!(
                            status = refusal.status,
                            "refused an events watcher: {}", refusal.message
                        );
                    }
                    let mut answer = ErrorResponse::new(Some(refusal.message));
                    *answer.status_mut() =
                        StatusCode::from_u16(refusal.status).unwrap_or(StatusCode::BAD_REQUEST);
                    Err(answer)
                }
            }
        }
    };
    let socket = tokio_tungstenite::accept_hdr_async(stream, callback)
        .await
        .context("the WebSocket handshake failed")?;
    let Some(subscription) = decided.lock().expect("decision lock").take() else {
        return Ok(());
    };
    // Held for the connection's life: dropping it frees the slot.
    let _permit = permit;

    let (mut write, mut read) = socket.split();
    // Dropped when this returns, which is what takes the watcher out of the hub.
    let mut subscribed = hub.subscribe(subscription);
    let lines = &mut subscribed.lines;

    let mut ping = tokio::time::interval(PING_INTERVAL);
    ping.tick().await;
    let ended = loop {
        tokio::select! {
            line = lines.recv() => {
                let Some(line) = line else { break Ok(()) };
                let text = serde_json::to_string(&line).unwrap_or_default();
                if let Err(error) = write.send(Message::text(text)).await {
                    break Err(error.into());
                }
            }
            incoming = read.next() => match incoming {
                None | Some(Ok(Message::Close(_))) => break Ok(()),
                Some(Err(error)) => break Err(error.into()),
                // Pongs, and anything a watcher sends: nothing here takes input.
                Some(Ok(_)) => {}
            },
            _ = ping.tick() => {
                if let Err(error) = write.send(Message::Ping(Default::default())).await {
                    break Err(error.into());
                }
            }
        }
    };
    drop(subscribed);
    let _ = write.close().await;
    ended
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_right_token_on_the_events_path_is_let_in_with_its_filters() {
        let subscription = authorize(
            "/events",
            Some("token=t0k&types=chat&contains=%40Claude"),
            None,
            "t0k",
            true,
        )
        .unwrap();
        assert_eq!(subscription.types, vec!["chat"]);
        assert_eq!(subscription.contains.as_deref(), Some("@Claude"));
    }

    #[test]
    fn a_missing_or_wrong_token_is_refused_with_401() {
        assert_eq!(
            authorize("/events", None, None, "t0k", true).unwrap_err().status,
            401
        );
        assert_eq!(
            authorize("/events", Some("token=nope"), None, "t0k", true)
                .unwrap_err()
                .status,
            401
        );
        assert_eq!(
            authorize("/events", Some("token=t0k0"), None, "t0k", true)
                .unwrap_err()
                .status,
            401
        );
    }

    #[test]
    fn a_page_from_another_origin_is_refused_and_a_local_one_is_not() {
        let query = Some("token=t0k");
        assert_eq!(
            authorize("/events", query, Some("https://evil.example"), "t0k", true)
                .unwrap_err()
                .status,
            403
        );
        assert_eq!(
            authorize(
                "/events",
                query,
                Some("http://localhost.evil.example"),
                "t0k",
                true
            )
            .unwrap_err()
            .status,
            403
        );
        for local in ["http://localhost:5173", "http://127.0.0.1", "http://[::1]:8080"] {
            assert!(
                authorize("/events", query, Some(local), "t0k", true).is_ok(),
                "{local}"
            );
        }
    }

    #[test]
    fn the_token_is_checked_before_anything_is_said_about_the_filters() {
        // A wrong token learns nothing, not even that its filters were bad.
        assert_eq!(
            authorize("/events", Some("token=x&types=bogus"), None, "t0k", true)
                .unwrap_err()
                .status,
            401
        );
        assert_eq!(
            authorize("/events", Some("token=t0k&types=bogus"), None, "t0k", true)
                .unwrap_err()
                .status,
            400
        );
    }

    #[test]
    fn a_full_listener_and_a_wrong_path_are_refused() {
        assert_eq!(
            authorize("/events", Some("token=t0k"), None, "t0k", false)
                .unwrap_err()
                .status,
            503
        );
        assert_eq!(
            authorize("/mcp", Some("token=t0k"), None, "t0k", true)
                .unwrap_err()
                .status,
            404
        );
    }

    #[test]
    fn constant_time_comparison_agrees_with_equality() {
        assert!(constant_time_eq(b"abc", b"abc"));
        assert!(!constant_time_eq(b"abc", b"abd"));
        assert!(!constant_time_eq(b"abc", b"ab"));
    }

    #[tokio::test]
    async fn a_websocket_client_with_the_token_connects_and_one_without_is_turned_away() {
        let registry = Arc::new(crate::registry::Registry::new());
        let store = Arc::new(std::sync::Mutex::new(
            crate::store::ApprovalStore::load("unused-in-tests.json").unwrap(),
        ));
        let router = Arc::new(Router::new(registry, store));
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = tokio::spawn(serve(listener, router, "t0k".to_string()));

        let refused = tokio_tungstenite::client_async(
            format!("ws://127.0.0.1:{port}/events?token=wrong"),
            TcpStream::connect(("127.0.0.1", port)).await.unwrap(),
        )
        .await;
        match refused {
            Err(tokio_tungstenite::tungstenite::Error::Http(response)) => {
                assert_eq!(response.status(), 401)
            }
            other => panic!("expected a 401, got {other:?}"),
        }

        let (mut socket, _) = tokio_tungstenite::client_async(
            format!("ws://127.0.0.1:{port}/events?token=t0k&types=chat"),
            TcpStream::connect(("127.0.0.1", port)).await.unwrap(),
        )
        .await
        .unwrap();
        // No games are linked, so nothing arrives; the connection stays open until closed.
        let quiet = tokio::time::timeout(Duration::from_millis(300), socket.next()).await;
        assert!(quiet.is_err(), "nothing should arrive with no games linked");
        socket.close(None).await.unwrap();
        server.abort();
    }
}
