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
use std::time::Duration;
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
        warn!(instance = %hello.instance_id, label = %hello.fallback_label(), %problem, "refused a link");
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
            warn!(instance = %hello.instance_id, label = %hello.fallback_label(), %reason, "refused a link");
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
        store.note_endpoint(&hello.instance_id, hello.side.as_str());
        if let Some(previous) = store.note_directory(&hello.instance_id, hello.game_directory.as_deref()) {
            // A known id from a new path is a moved instance, which is fine and stays approved. It
            // is also what a copied config looks like, so it earns a line rather than silence.
            warn!(
                instance = %hello.instance_id,
                label = %hello.fallback_label(),
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

    // The addressable id, not the raw hello id: both endpoints of a singleplayer world share one
    // `instanceId`, and registering them under it made the integrated server displace the client.
    let instance_id = instance.id();

    context.registry.insert(Arc::clone(&instance));
    // Listed from here on, so every way out of this function has to unlist it, a panic included.
    // That is why this is a guard and not a tail of statements: a task that panicked between the
    // insert and the cleanup left a game listed forever, with no tools and no socket behind it.
    let mut registration = Registration {
        instance: Arc::clone(&instance),
        registry: Arc::clone(&context.registry),
        events: context.events.clone(),
        label: label.clone(),
        tasks: vec![writer.abort_handle()],
    };
    info!(instance = %instance_id, %label, side = hello.side.as_str(), "instance linked");

    // Spawned, not awaited: initialize and the catalogue fetch send requests whose answers only
    // arrive once the read loop below is running. Awaiting here would deadlock on the first one.
    let bootstrap = {
        let instance = Arc::clone(&instance);
        let events = context.events.clone();
        let id = instance_id.clone();
        let name = label.clone();
        tokio::spawn(async move { bootstrap(instance, events, id, name).await })
    };
    registration.tasks.push(bootstrap.abort_handle());

    read_loop(&mut reader, &instance, &context).await
}

/// Unlists a linked instance when its connection task ends, however it ends.
struct Registration {
    instance: Arc<Instance>,
    registry: Arc<Registry>,
    events: mpsc::UnboundedSender<UpstreamEvent>,
    label: String,
    /// The writer and the bootstrap, which must not outlive the link they serve.
    tasks: Vec<tokio::task::AbortHandle>,
}

impl Drop for Registration {
    fn drop(&mut self) {
        for task in &self.tasks {
            task.abort();
        }
        self.instance.mark_closed();
        let id = self.instance.id();
        self.registry.remove(&id, &self.instance);
        let _ = self
            .events
            .send(UpstreamEvent::Disconnected { instance: id.clone() });
        info!(instance = %id, label = %self.label, "instance unlinked");
    }
}

/// How long to wait before each successive bootstrap attempt.
///
/// The last entry repeats for as long as the link stays up. Bootstrapping is not something that can
/// be given up on: the instance is already in the registry — the reader task needs it there — so an
/// abandoned bootstrap leaves a game that looks connected, offers nothing, and never recovers. The
/// task is cancelled when the link drops, which is the only bound that belongs here.
const BOOTSTRAP_BACKOFF: [Duration; 5] = [
    Duration::from_secs(1),
    Duration::from_secs(2),
    Duration::from_secs(5),
    Duration::from_secs(15),
    Duration::from_secs(60),
];

/// Brings a freshly linked instance up: MCP handshake, then its catalogue, retrying until it works.
///
/// The two stages are separate because they fail for different reasons and only one of them is safe
/// to repeat. A handshake that succeeded is not replayed — MCP does not promise a server tolerates a
/// second `initialize` — so once past it, a retry re-fetches the catalogue and nothing else.
async fn bootstrap(
    instance: Arc<Instance>,
    events: mpsc::UnboundedSender<UpstreamEvent>,
    id: String,
    label: String,
) {
    let mut handshaken = false;
    let mut attempt: u32 = 0;

    loop {
        if !instance.is_alive() {
            return;
        }

        let failure = if !handshaken {
            match instance.handshake().await {
                Ok(_) => {
                    handshaken = true;
                    None
                }
                Err(error) => Some(("handshake", error)),
            }
        } else {
            None
        };

        let failure = match failure {
            Some(failure) => Some(failure),
            None => match instance.refresh_catalogue().await {
                Ok(()) => {
                    let tools = instance.catalogue().tools.len();
                    if attempt > 0 {
                        info!(instance = %id, label = %label, tools, attempt, "instance ready after retrying");
                    } else {
                        info!(instance = %id, label = %label, tools, "instance ready");
                    }
                    let _ = events.send(UpstreamEvent::Connected { instance: id });
                    return;
                }
                Err(error) => Some(("catalogue", error)),
            },
        };

        let Some((stage, error)) = failure else {
            return;
        };

        attempt += 1;
        let message = format!("{error:#}");
        error!(instance = %id, label = %label, stage, attempt, error = %message, "instance failed to initialise; will retry");
        // Durable, not just stderr. The event log recorded only successful links, so a bootstrap
        // that never succeeded left no trace at all and the failure was invisible to anyone not
        // watching a terminal the desktop app does not have.
        let _ = events.send(UpstreamEvent::BootstrapFailed {
            instance: id.clone(),
            stage: stage.to_string(),
            attempt,
            error: message,
        });

        let delay = BOOTSTRAP_BACKOFF[usize::min(attempt as usize - 1, BOOTSTRAP_BACKOFF.len() - 1)];
        tokio::select! {
            _ = tokio::time::sleep(delay) => {}
            _ = instance.wait_closed() => return,
        }
    }
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
    // Resolved once: the label is what makes a log line auditable, and an id like `run-d0a639` on
    // its own tells a person reading the trail nothing about which game it was.
    let label = instance.info().label;
    while let Some(frame) = framing::read_frame(reader).await? {
        if protocol::is_control_frame(&frame) {
            if let Some(status) = protocol::parse_status(&frame) {
                if status.responding {
                    info!(instance = %id, %label, thread = %status.name, "game thread is running again");
                } else {
                    warn!(instance = %id, %label, thread = %status.name, silent_ms = status.silent_millis,
                        "game thread has stopped finishing frames");
                }
                instance.set_game_thread(&status);
                continue;
            }
            // Ignoring an unknown one rather than dropping the link is what lets a newer mod talk to
            // an older orchestrator.
            debug!(instance = %id, %label, "ignored a post-handshake control frame");
            continue;
        }

        if jsonrpc::is_response(&frame) {
            if !instance.complete(frame) {
                debug!(instance = %id, %label, "an answer arrived for a request nobody was waiting on");
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
            debug!(instance = %id, %label, %method, "forwarding a server-to-client request");
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

    /// Reads outbound frames until a request arrives, answering nothing. Notifications are skipped:
    /// `notifications/initialized` sits between the handshake and the catalogue fetch.
    async fn next_request(outbound: &mut mpsc::Receiver<serde_json::Value>) -> serde_json::Value {
        loop {
            let frame = outbound.recv().await.expect("bootstrap should keep asking");
            if frame.get("id").is_some() {
                return frame;
            }
        }
    }

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

    #[tokio::test]
    async fn a_connection_task_that_panics_still_unlists_its_instance() {
        // The bug: cleanup was a tail of statements after the read loop. A task that panicked
        // before reaching it (a failed log write did it to every link) left the game listed as
        // connected with no tools, long after the game itself had exited.
        let registry = Arc::new(Registry::new());
        let (sender, _outbound) = mpsc::channel(1);
        let instance = Arc::new(Instance::new(
            InstanceInfo {
                id: "alpha.client".into(),
                approval_id: "alpha".into(),
                label: "alpha".into(),
                side: protocol::Side::Client,
                game_directory: None,
                mod_version: "test".into(),
                minecraft_version: "1.12.2".into(),
                endpoint_url: None,
                pid: None,
                started_at: None,
            },
            sender,
        ));
        let (events, mut received) = mpsc::unbounded_channel();
        registry.insert(Arc::clone(&instance));

        let task = {
            let registry = Arc::clone(&registry);
            let instance = Arc::clone(&instance);
            tokio::spawn(async move {
                let _registration = Registration {
                    instance,
                    registry,
                    events,
                    label: "alpha".into(),
                    tasks: Vec::new(),
                };
                panic!("something between linked and unlinked went wrong");
            })
        };
        assert!(task.await.unwrap_err().is_panic());

        assert!(
            registry.get("alpha.client").is_none(),
            "the panicked link must not stay listed"
        );
        assert!(
            !instance.is_alive(),
            "anything waiting on it is failed, not left to time out"
        );
        assert!(matches!(
            received.try_recv(),
            Ok(UpstreamEvent::Disconnected { instance }) if instance == "alpha.client"
        ));
    }

    #[tokio::test(start_paused = true)]
    async fn a_catalogue_fetch_that_fails_is_retried_rather_than_abandoned() {
        // The bug: bootstrap ran once, and its Err arm only logged. The instance stayed in the
        // registry with an empty catalogue and no path back — it reported connected, offered
        // nothing, and every call against it failed with "no connected instance offers it".
        let (sender, mut outbound) = mpsc::channel(16);
        let instance = Arc::new(Instance::new(
            InstanceInfo {
                id: "alpha.client".into(),
                approval_id: "alpha".into(),
                label: "alpha".into(),
                side: protocol::Side::Client,
                game_directory: None,
                mod_version: "test".into(),
                minecraft_version: "1.12.2".into(),
                endpoint_url: None,
                pid: None,
                started_at: None,
            },
            sender,
        ));
        let (events, mut received) = mpsc::unbounded_channel();

        let driver = {
            let instance = Arc::clone(&instance);
            tokio::spawn(
                async move { bootstrap(instance, events, "alpha.client".into(), "alpha".into()).await },
            )
        };

        // The handshake succeeds.
        let handshake = next_request(&mut outbound).await;
        assert_eq!(handshake["method"], "initialize");
        instance.complete(jsonrpc::result(handshake["id"].clone(), json!({})));

        // The first catalogue fetch fails, the way a game still finishing its load might.
        let first = next_request(&mut outbound).await;
        assert_eq!(first["method"], "tools/list");
        instance.complete(jsonrpc::error(
            Some(first["id"].clone()),
            jsonrpc::INTERNAL_ERROR,
            "not ready yet",
        ));
        assert!(!instance.is_ready(), "a failed fetch must not look ready");

        // The retry asks again -- and this time is answered. `initialize` is NOT replayed.
        let second = next_request(&mut outbound).await;
        assert_eq!(
            second["method"], "tools/list",
            "a retry re-fetches the catalogue; replaying the handshake is not safe"
        );
        instance.complete(jsonrpc::result(
            second["id"].clone(),
            json!({"tools": [{"name": "client_move"}]}),
        ));

        driver.await.expect("bootstrap finishes once it succeeds");
        assert!(
            instance.is_ready(),
            "a successful retry leaves the instance ready"
        );
        assert_eq!(instance.catalogue().tools.len(), 1);

        let mut failures = 0;
        let mut connected = 0;
        while let Ok(event) = received.try_recv() {
            match event {
                UpstreamEvent::BootstrapFailed { attempt, stage, .. } => {
                    assert_eq!(stage, "catalogue");
                    assert_eq!(attempt, 1);
                    failures += 1;
                }
                UpstreamEvent::Connected { .. } => connected += 1,
                _ => {}
            }
        }
        assert_eq!(failures, 1, "the failure is recorded durably, not only on stderr");
        assert_eq!(connected, 1);
    }

    #[tokio::test(start_paused = true)]
    async fn bootstrap_gives_up_when_the_link_dies() {
        // The retry is bounded by the link, not by an attempt count: a game that closed mid-bootstrap
        // must not leave a task retrying into a socket that is gone.
        let (sender, mut outbound) = mpsc::channel(16);
        let instance = Arc::new(Instance::new(
            InstanceInfo {
                id: "alpha.client".into(),
                approval_id: "alpha".into(),
                label: "alpha".into(),
                side: protocol::Side::Client,
                game_directory: None,
                mod_version: "test".into(),
                minecraft_version: "1.12.2".into(),
                endpoint_url: None,
                pid: None,
                started_at: None,
            },
            sender,
        ));
        let (events, _received) = mpsc::unbounded_channel();

        let driver = {
            let instance = Arc::clone(&instance);
            tokio::spawn(
                async move { bootstrap(instance, events, "alpha.client".into(), "alpha".into()).await },
            )
        };

        let handshake = next_request(&mut outbound).await;
        instance.complete(jsonrpc::error(
            Some(handshake["id"].clone()),
            jsonrpc::INTERNAL_ERROR,
            "no",
        ));
        instance.mark_closed();

        tokio::time::timeout(Duration::from_secs(5), driver)
            .await
            .expect("a dead link must end the retry loop")
            .expect("the task should not panic");
        assert!(!instance.is_ready());
    }
}
