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
use std::sync::Arc;
use tokio::io::BufReader;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;
use tracing::{debug, info, warn};

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
        tokio::spawn(async move {
            if let Err(error) = handle_client(stream, router, token).await {
                debug!(%peer, %error, "MCP client ended");
            }
        });
    }
}

async fn handle_client(stream: TcpStream, router: Arc<Router>, token: String) -> Result<()> {
    let _ = stream.set_nodelay(true);
    let (read_half, mut write_half) = stream.into_split();
    let mut reader = BufReader::new(read_half);

    let Some(frame) = framing::read_frame(&mut reader).await? else {
        return Ok(());
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
        return Ok(());
    }
    framing::write_frame(&mut write_half, &json!({ "type": TYPE_ATTACHED })).await?;

    // One task owns the socket's write half, exactly as on the link side: responses come from many
    // request tasks and notifications come from the instances, and two writers interleaving
    // mid-frame would corrupt both.
    let (outgoing, mut outgoing_rx) = mpsc::unbounded_channel::<Value>();
    let writer = tokio::spawn(async move {
        while let Some(message) = outgoing_rx.recv().await {
            if framing::write_frame(&mut write_half, &message).await.is_err() {
                break;
            }
        }
    });

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

    while let Some(message) = framing::read_frame(&mut reader).await? {
        let router = Arc::clone(&router);
        let outgoing = outgoing.clone();
        // Each request in its own task: a tools/call can take minutes, and a client that cannot
        // ping or cancel while one runs has no way to stop it.
        tokio::spawn(async move {
            if let Some(response) = router.handle(message).await {
                let _ = outgoing.send(response);
            }
        });
    }

    drop(outgoing);
    forwarding.abort();
    let _ = writer.await;
    Ok(())
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

    #[test]
    fn two_tokens_generated_independently_differ() {
        let first = generate_token();
        let second = generate_token();

        assert_ne!(first, second);
    }
}
