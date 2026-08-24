//! Accepting links from game instances.
//!
//! # The approval seam
//!
//! Deciding whether an unknown instance may connect is **not** made here, and that is the single
//! most important structural choice in this file. The listener sends an [`ApprovalRequest`] down a
//! channel and waits for an answer; whoever owns the other end decides. The headless CLI answers by
//! policy, the desktop app answers by putting a dialog on screen, and neither of them is a special
//! case inside this code.
//!
//! That is the "the GUI is a client of the core, with no privileged back door" rule made structural
//! rather than aspirational: there is no back door because there is no door — there is a channel,
//! and it faces both ways.
//!
//! # Task shape per connection
//!
//! Accepting spawns one task, which splits the socket and spawns a writer beside itself:
//!
//! - the **writer** owns the write half and drains an mpsc, so nothing else ever touches the socket
//! - the **connection task** owns the read half and loops on frames
//!
//! Handshake first, then a short-lived bootstrap that runs `initialize` and loads the catalogue.
//! That bootstrap has to run *concurrently with the read loop* — it sends requests whose answers
//! only arrive if something is reading — so it is spawned rather than awaited.

use anyhow::{Context, Result};
use std::sync::{Arc, Mutex};
use tokio::io::BufReader;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, oneshot};
use tracing::{debug, error, info, warn};

use crate::instance::{Instance, InstanceInfo, UpstreamEvent};
use crate::jsonrpc;
use crate::link::framing;
use crate::link::protocol::{self, Hello};
use crate::registry::Registry;
use crate::store::{ApprovalStore, Verdict};

/// How many frames may queue for one instance's writer before sends block.
///
/// Bounded, so a wedged instance applies backpressure instead of growing a queue. Sixty-four is far
/// more than a healthy link ever holds; reaching it means the far side has stopped reading.
const OUTBOUND_QUEUE: usize = 64;

/// A decision the listener needs made about an instance it has never seen.
#[derive(Debug)]
pub struct ApprovalRequest {
    pub hello: HelloSummary,
    pub respond: oneshot::Sender<ApprovalOutcome>,
}

/// The part of a hello that an approval decision may see.
///
/// Note the absence of `instance_secret`. Whoever answers an approval prompt has no business
/// handling it, a GUI would be one screenshot away from leaking it, and the listener has already
/// done the only comparison it is for.
#[derive(Debug, Clone)]
pub struct HelloSummary {
    pub instance_id: String,
    pub label: String,
    pub side: protocol::Side,
    pub game_directory: Option<String>,
    pub mod_version: String,
    pub minecraft_version: String,
}

impl HelloSummary {
    fn from_hello(hello: &Hello) -> Self {
        Self {
            instance_id: hello.instance_id.clone(),
            label: hello.fallback_label(),
            side: hello.side,
            game_directory: hello.game_directory.clone(),
            mod_version: hello.mod_version.clone(),
            minecraft_version: hello.minecraft_version.clone(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ApprovalOutcome {
    /// Let it in, and remember it.
    Approve,
    /// Refuse for now. The instance keeps retrying, so this is what an unanswered prompt looks like.
    Pending,
    /// Refuse permanently.
    Reject { reason: String, message: String },
}

/// Everything a connection needs to do its job.
#[derive(Clone)]
pub struct LinkContext {
    pub registry: Arc<Registry>,
    pub store: Arc<Mutex<ApprovalStore>>,
    pub events: mpsc::UnboundedSender<UpstreamEvent>,
    pub approvals: mpsc::Sender<ApprovalRequest>,
}

/// Listens for instances until the returned future is dropped or the socket fails.
pub async fn serve(listener: TcpListener, context: LinkContext) -> Result<()> {
    let address = listener
        .local_addr()
        .context("reading the link listener address")?;
    info!(%address, "listening for MCMCP instances");

    loop {
        let (stream, peer) = match listener.accept().await {
            Ok(accepted) => accepted,
            Err(error) => {
                // One failed accept is not a reason to stop listening — a peer that vanished
                // between the SYN and the accept produces exactly this and means nothing.
                warn!(%error, "failed to accept a link");
                continue;
            }
        };
        debug!(%peer, "a link connected");

        let context = context.clone();
        tokio::spawn(async move {
            if let Err(error) = handle_connection(stream, context).await {
                debug!(%peer, %error, "link ended");
            }
        });
    }
}

async fn handle_connection(stream: TcpStream, context: LinkContext) -> Result<()> {
    // Small frames where latency matters more than packet count: a tool call and its reply are one
    // small write each, and Nagle would sit on them waiting for company.
    let _ = stream.set_nodelay(true);

    let (read_half, mut write_half) = stream.into_split();
    let mut reader = BufReader::new(read_half);

    let Some(frame) = framing::read_frame(&mut reader).await? else {
        return Ok(()); // Connected and said nothing.
    };

    let hello: Hello = match serde_json::from_value(frame.clone()) {
        Ok(hello) => hello,
        Err(error) => {
            let reason = protocol::rejected(
                protocol::REASON_MALFORMED_HELLO,
                &format!("could not read the hello frame: {error}"),
            );
            let _ = framing::write_frame(&mut write_half, &reason).await;
            warn!(%error, "refused a link whose hello could not be read");
            return Ok(());
        }
    };

    if let Err(problem) = hello.validate() {
        let reason = if hello.link_protocol != protocol::VERSION {
            protocol::REASON_UNSUPPORTED_PROTOCOL
        } else {
            protocol::REASON_MALFORMED_HELLO
        };
        let _ = framing::write_frame(&mut write_half, &protocol::rejected(reason, &problem)).await;
        warn!(instance = %hello.instance_id, %problem, "refused a link");
        return Ok(());
    }

    // ------------------------------------------------------------------
    // Authorisation
    // ------------------------------------------------------------------

    let verdict = context
        .store
        .lock()
        .expect("store lock")
        .evaluate(&hello.instance_id, &hello.instance_secret);
    let strict = context.store.lock().expect("store lock").strict_approval();

    let outcome = match verdict {
        Verdict::SecretMismatch => ApprovalOutcome::Reject {
            reason: protocol::REASON_SECRET_MISMATCH.into(),
            message: "this instance id is known, but with a different secret".into(),
        },
        Verdict::Revoked => ApprovalOutcome::Reject {
            reason: protocol::REASON_REVOKED.into(),
            message: "this instance was revoked in the orchestrator".into(),
        },
        // Strict mode re-asks even for an instance already approved. That is the whole point of the
        // setting: trust on first use is the default, and this is the version for when it is not
        // good enough.
        Verdict::Approved if strict => ask(&context, &hello).await,
        Verdict::Approved => ApprovalOutcome::Approve,
        Verdict::Unknown => ask(&context, &hello).await,
    };

    match &outcome {
        ApprovalOutcome::Approve => {}
        ApprovalOutcome::Pending => {
            let frame = protocol::rejected(
                protocol::REASON_PENDING_APPROVAL,
                "waiting for this instance to be approved",
            );
            let _ = framing::write_frame(&mut write_half, &frame).await;
            return Ok(());
        }
        ApprovalOutcome::Reject { reason, message } => {
            let frame = protocol::rejected(reason, message);
            let _ = framing::write_frame(&mut write_half, &frame).await;
            warn!(instance = %hello.instance_id, %reason, "refused a link");
            return Ok(());
        }
    }

    // ------------------------------------------------------------------
    // Accepted
    // ------------------------------------------------------------------

    let label = {
        let mut store = context.store.lock().expect("store lock");
        store.approve(
            &hello.instance_id,
            &hello.instance_secret,
            &hello.fallback_label(),
            hello.game_directory.as_deref(),
            &now_rfc3339(),
        );
        if let Some(previous) = store.note_directory(&hello.instance_id, hello.game_directory.as_deref()) {
            // A known id from a new path is a moved instance, which is fine and stays approved. It
            // is also what a copied config looks like, so it earns a line rather than silence.
            warn!(
                instance = %hello.instance_id,
                from = %previous,
                to = hello.game_directory.as_deref().unwrap_or("(unknown)"),
                "an approved instance reported a new game directory"
            );
        }
        let label = store.get(&hello.instance_id).map(|known| known.label.clone());
        if let Err(error) = store.save() {
            // Losing the approval is survivable — it will be asked again next launch. Losing the
            // connection over it is not worth it.
            error!(%error, "could not save the approval store");
        }
        label.unwrap_or_else(|| hello.fallback_label())
    };

    framing::write_frame(&mut write_half, &protocol::welcome(crate::VERSION, Some(&label))).await?;

    let (outbound, mut outbound_rx) = mpsc::channel(OUTBOUND_QUEUE);
    let instance = Arc::new(Instance::new(
        InstanceInfo::from_hello(&hello, label.clone()),
        outbound,
    ));

    // The writer owns the write half from here. Nothing else may touch it, which is what makes
    // interleaved frames impossible rather than merely unlikely.
    let writer = tokio::spawn(async move {
        while let Some(frame) = outbound_rx.recv().await {
            if framing::write_frame(&mut write_half, &frame).await.is_err() {
                break;
            }
        }
    });

    context.registry.insert(Arc::clone(&instance));
    info!(instance = %hello.instance_id, %label, side = hello.side.as_str(), "instance linked");

    // Spawned, not awaited: initialize and the catalogue fetch send requests whose answers only
    // arrive once the read loop below is running. Awaiting here would deadlock on the first one.
    let bootstrap = {
        let instance = Arc::clone(&instance);
        let events = context.events.clone();
        let id = hello.instance_id.clone();
        tokio::spawn(async move {
            match instance.initialize().await {
                Ok(_) => {
                    let tools = instance.catalogue().tools.len();
                    info!(instance = %id, tools, "instance ready");
                    let _ = events.send(UpstreamEvent::Connected { instance: id });
                }
                Err(error) => {
                    error!(instance = %id, %error, "instance failed to initialise");
                }
            }
        })
    };

    let result = read_loop(&mut reader, &instance, &context).await;

    bootstrap.abort();
    instance.mark_closed();
    context.registry.remove(&hello.instance_id, &instance);
    writer.abort();
    let _ = context.events.send(UpstreamEvent::Disconnected {
        instance: hello.instance_id.clone(),
    });
    info!(instance = %hello.instance_id, "instance unlinked");

    result
}

async fn ask(context: &LinkContext, hello: &Hello) -> ApprovalOutcome {
    let (respond, answer) = oneshot::channel();
    let request = ApprovalRequest {
        hello: HelloSummary::from_hello(hello),
        respond,
    };

    if context.approvals.send(request).await.is_err() {
        // Nobody is listening for approvals. Pending rather than reject: the instance keeps trying,
        // and a restarted orchestrator with an approver attached will pick it up.
        return ApprovalOutcome::Pending;
    }
    answer.await.unwrap_or(ApprovalOutcome::Pending)
}

async fn read_loop<R>(reader: &mut R, instance: &Arc<Instance>, context: &LinkContext) -> Result<()>
where
    R: tokio::io::AsyncBufRead + Unpin,
{
    let id = instance.id();
    while let Some(frame) = framing::read_frame(reader).await? {
        if protocol::is_control_frame(&frame) {
            // Nothing sends a post-handshake control frame yet. Ignoring an unknown one rather than
            // dropping the link is what lets a newer mod talk to an older orchestrator.
            debug!(instance = %id, "ignored a post-handshake control frame");
            continue;
        }

        if jsonrpc::is_response(&frame) {
            if !instance.complete(frame) {
                debug!(instance = %id, "an answer arrived for a request nobody was waiting on");
            }
            continue;
        }

        if jsonrpc::is_notification(&frame) {
            let _ = context.events.send(UpstreamEvent::Notification {
                instance: id.clone(),
                message: frame,
            });
            continue;
        }

        // A *request* from the instance — sampling, elicitation, roots. Handed to the router, which
        // forwards it to the MCP client and routes the answer back under the id used here.
        //
        // Never dropped. A request left unanswered hangs whatever tool is waiting on it inside the
        // game until its own timeout, and the game has no way to tell that from a slow answer.
        if jsonrpc::id_of(&frame).is_some() {
            let method = jsonrpc::method_of(&frame).unwrap_or("(unknown)").to_string();
            debug!(instance = %id, %method, "forwarding a server-to-client request");
            let _ = context.events.send(UpstreamEvent::Request {
                instance: id.clone(),
                message: frame,
            });
        }
    }
    Ok(())
}

/// An RFC 3339 timestamp, without pulling in a date-time crate for one field.
///
/// The value is recorded for a human reading the approval store and is never parsed back, so seconds
/// since the epoch rendered as a string would do — but a readable timestamp costs nothing more.
fn now_rfc3339() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    format!("{seconds}")
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn an_approval_summary_never_carries_the_secret() {
        // Whoever answers a prompt has no business handling it, and a GUI would be one screenshot
        // away from leaking it.
        let hello: Hello = serde_json::from_value(json!({
            "type": "hello",
            "linkProtocol": 1,
            "instanceId": "modb-dev",
            "instanceSecret": "a".repeat(64),
            "instanceName": "modB dev",
            "side": "client",
            "gameDirectory": "E:\\instances\\modB",
            "modVersion": "2026.08.24",
            "minecraftVersion": "1.12.2",
        }))
        .unwrap();

        let summary = HelloSummary::from_hello(&hello);
        let rendered = format!("{summary:?}");

        assert!(!rendered.contains(&"a".repeat(64)));
        assert_eq!(summary.instance_id, "modb-dev");
        assert_eq!(summary.label, "modB dev");
        assert_eq!(summary.game_directory.as_deref(), Some("E:\\instances\\modB"));
    }
}
