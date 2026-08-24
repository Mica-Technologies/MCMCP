//! The MCP transport toward the client: newline-delimited JSON-RPC over stdin and stdout.
//!
//! # Why stdio is the primary transport
//!
//! It buys three things at once, and they are exactly the friction this project set out to remove:
//! no port to allocate for the orchestrator, no bearer token in the client's configuration, and a
//! lifecycle tied to the client rather than to any game. Games come and go underneath a connection
//! that never drops.
//!
//! # One rule
//!
//! **stdout carries protocol and nothing else.** A stray `println!` is not a cosmetic bug — it is a
//! frame the client cannot parse, and the symptom is an MCP server that silently fails to start with
//! no indication why. Logging goes to stderr, everywhere, without exception.
//!
//! # Concurrency
//!
//! Each request is handled in its own task. A `tools/call` can legitimately take minutes — the far
//! end runs on Minecraft's game thread and `client_wait` blocks until its condition comes true — and
//! handling requests in sequence would mean a client could not even `ping` while one was running,
//! let alone cancel it. Cancellation that cannot be received is not cancellation.

use anyhow::{Context, Result};
use serde_json::Value;
use std::sync::Arc;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::sync::mpsc;
use tracing::{debug, warn};

use crate::jsonrpc;
use crate::router::Router;

/// Serves MCP over stdin/stdout until the client closes stdin.
pub async fn serve(router: Arc<Router>) -> Result<()> {
    let mut downstream = router.subscribe_downstream();
    // One task owns stdout. Responses come from many request tasks and notifications come from the
    // instances, and two writers interleaving mid-frame would corrupt both.
    let (outgoing, mut outgoing_rx) = mpsc::unbounded_channel::<Value>();
    let writer = tokio::spawn(async move {
        let mut stdout = tokio::io::stdout();
        while let Some(message) = outgoing_rx.recv().await {
            let mut line = match serde_json::to_vec(&message) {
                Ok(line) => line,
                Err(error) => {
                    warn!(%error, "could not serialise an outgoing message");
                    continue;
                }
            };
            line.push(b'\n');
            if stdout.write_all(&line).await.is_err() || stdout.flush().await.is_err() {
                break;
            }
        }
    });

    // Notifications the router raises reach the same writer.
    {
        let outgoing = outgoing.clone();
        tokio::spawn(async move {
            loop {
                match downstream.recv().await {
                    Ok(message) => {
                        if outgoing.send(message).is_err() {
                            break;
                        }
                    }
                    // Lagged: this client fell behind and the oldest notifications were dropped for
                    // it. Keep going — a missed list_changed costs a stale tool list until the next
                    // one, and stopping would cost every notification after it.
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(missed)) => {
                        warn!(missed, "an MCP client fell behind on notifications");
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                }
            }
        });
    }

    let mut lines = BufReader::new(tokio::io::stdin()).lines();
    while let Some(line) = lines.next_line().await.context("reading stdin")? {
        if line.trim().is_empty() {
            continue;
        }

        let message: Value = match serde_json::from_str(&line) {
            Ok(message) => message,
            Err(error) => {
                debug!(%error, "ignoring an unparseable line on stdin");
                let _ = outgoing.send(jsonrpc::error(
                    None,
                    jsonrpc::PARSE_ERROR,
                    "the request was not valid JSON",
                ));
                continue;
            }
        };

        let router = Arc::clone(&router);
        let outgoing = outgoing.clone();
        tokio::spawn(async move {
            if let Some(response) = router.handle(message).await {
                let _ = outgoing.send(response);
            }
        });
    }

    // stdin closed: the client is gone.
    drop(outgoing);
    let _ = writer.await;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::registry::Registry;
    use crate::store::ApprovalStore;
    use serde_json::json;
    use std::sync::Mutex;

    fn router() -> Arc<Router> {
        Arc::new(Router::new(
            Arc::new(Registry::new()),
            Arc::new(Mutex::new(ApprovalStore::load("unused-in-tests.json").unwrap())),
        ))
    }

    #[tokio::test]
    async fn answers_initialize_with_a_version_the_client_offered() {
        // Answering with a version the client did not offer is how a handshake fails in a way
        // neither side can explain.
        let response = router()
            .handle(json!({
                "jsonrpc": "2.0", "id": 1, "method": "initialize",
                "params": {"protocolVersion": "2024-11-05", "capabilities": {}},
            }))
            .await
            .expect("initialize is a request and must be answered");

        assert_eq!(response["result"]["protocolVersion"], "2024-11-05");
        assert_eq!(response["result"]["serverInfo"]["name"], "mcmcp-orchestrator");
    }

    #[tokio::test]
    async fn falls_back_to_its_own_latest_for_a_version_it_does_not_know() {
        let response = router()
            .handle(json!({
                "jsonrpc": "2.0", "id": 1, "method": "initialize",
                "params": {"protocolVersion": "1999-01-01", "capabilities": {}},
            }))
            .await
            .unwrap();

        assert_eq!(response["result"]["protocolVersion"], "2025-06-18");
    }

    #[tokio::test]
    async fn a_notification_gets_no_response() {
        // Answering a notification is a protocol violation, and a client that gets a response to an
        // id-less message has no way to make sense of it.
        let response = router()
            .handle(json!({"jsonrpc": "2.0", "method": "notifications/initialized"}))
            .await;

        assert!(response.is_none());
    }

    #[tokio::test]
    async fn an_unknown_method_is_a_protocol_error_not_a_tool_error() {
        // This one genuinely is the client's plumbing being wrong, which is what JSON-RPC errors
        // are for.
        let response = router()
            .handle(json!({"jsonrpc": "2.0", "id": 1, "method": "does/not/exist"}))
            .await
            .unwrap();

        assert_eq!(response["error"]["code"], jsonrpc::METHOD_NOT_FOUND);
    }

    #[tokio::test]
    async fn lists_the_orchestrators_own_tools_even_with_nothing_connected() {
        // A client connecting before any game is up must still find mcmcp_instances, or it has no
        // way to discover that the answer is "start a game".
        let response = router()
            .handle(json!({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}))
            .await
            .unwrap();

        let names: Vec<&str> = response["result"]["tools"]
            .as_array()
            .unwrap()
            .iter()
            .map(|tool| tool["name"].as_str().unwrap())
            .collect();
        assert!(names.contains(&"mcmcp_instances"));
    }

    #[tokio::test]
    async fn calling_a_game_tool_with_nothing_connected_is_a_readable_tool_error() {
        let response = router()
            .handle(json!({
                "jsonrpc": "2.0", "id": 1, "method": "tools/call",
                "params": {"name": "client_move", "arguments": {}},
            }))
            .await
            .unwrap();

        // A successful response carrying isError, not a JSON-RPC error — otherwise the model never
        // reads it and retries forever.
        assert!(response.get("error").is_none());
        assert_eq!(response["result"]["isError"], true);
        let text = response["result"]["content"][0]["text"].as_str().unwrap();
        assert!(text.contains("No Minecraft instance is connected"));
    }

    #[tokio::test]
    async fn mcmcp_instances_answers_with_an_empty_roster_rather_than_failing() {
        let response = router()
            .handle(json!({
                "jsonrpc": "2.0", "id": 1, "method": "tools/call",
                "params": {"name": "mcmcp_instances", "arguments": {}},
            }))
            .await
            .unwrap();

        assert_eq!(response["result"]["isError"], false);
        assert_eq!(response["result"]["structuredContent"]["connected"], json!([]));
    }

    #[tokio::test]
    async fn focusing_something_that_is_not_connected_is_refused_with_the_list() {
        let response = router()
            .handle(json!({
                "jsonrpc": "2.0", "id": 1, "method": "tools/call",
                "params": {"name": "mcmcp_focus", "arguments": {"target": "ghost"}},
            }))
            .await
            .unwrap();

        assert_eq!(response["result"]["isError"], true);
        assert!(
            response["result"]["content"][0]["text"]
                .as_str()
                .unwrap()
                .contains("ghost")
        );
    }

    #[tokio::test]
    async fn an_unqualified_resource_uri_is_refused_with_an_explanation() {
        // The confusing case, and the reason the message says more than "not connected": an
        // unqualified URI does not fail to parse. `minecraft://game/mods` reads as instance `game`,
        // so reporting only a missing instance would send the reader looking for a game they never
        // had.
        let response = router()
            .handle(json!({
                "jsonrpc": "2.0", "id": 1, "method": "resources/read",
                "params": {"uri": "minecraft://game/mods"},
            }))
            .await
            .unwrap();

        let message = response["error"]["message"].as_str().unwrap();
        assert!(message.contains("names its instance first"), "got: {message}");
        assert!(message.contains("resources/list"), "got: {message}");
    }
}
