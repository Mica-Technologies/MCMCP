//! The headless MCMCP orchestrator.
//!
//! This binary is the whole product minus the window. The desktop app in Phase 3 links the same
//! core crate and answers the same channels; nothing it does is unavailable here. That is deliberate
//! and is the rule the design is built on — the GUI is a client of the core, not a privileged layer
//! inside it — and the only way to keep it honest is for the headless path to be the one that gets
//! used.
//!
//! Logging goes to **stderr**, always. `serve --stdio` puts MCP traffic on stdout, and a stray log
//! line there is a protocol violation that presents as an MCP client silently failing to start.

use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use mcmcp_orchestrator_core::link::listener::{ApprovalOutcome, ApprovalRequest, LinkContext};
use mcmcp_orchestrator_core::registry::Registry;
use mcmcp_orchestrator_core::router::Router;
use mcmcp_orchestrator_core::store::ApprovalStore;
use mcmcp_orchestrator_core::{catalogue, link, paths, stdio};
use std::sync::{Arc, Mutex};
use tokio::net::TcpListener;
use tokio::sync::mpsc;
use tracing::{info, warn};
use tracing_subscriber::EnvFilter;

#[derive(Parser)]
#[command(
    name = "mcmcp-orchestrator",
    version,
    about = "One MCP endpoint for every linked Minecraft instance"
)]
struct Cli {
    /// Port game instances dial. Must match `orchestrator.orchestratorPort` in each game's config.
    #[arg(long, default_value_t = 25580, global = true)]
    link_port: u16,

    /// Where approvals and caches live. Overrides the platform default.
    #[arg(long, global = true)]
    state_dir: Option<String>,

    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Accept instance links and serve MCP over stdin/stdout.
    Serve {
        /// Refuse instances that have not been approved, instead of approving on first connection.
        ///
        /// Trust on first use is the default because the alternative in a headless process is an
        /// instance that can never be approved: nothing is on screen to ask. With this set, an
        /// unknown instance is refused with `pending-approval` and keeps retrying, so approving it
        /// later connects it without restarting anything.
        #[arg(long)]
        strict_approval: bool,

        /// Accept links but do not serve MCP on stdout. For proving a link works.
        #[arg(long)]
        no_stdio: bool,
    },
    /// List instances the orchestrator knows about, connected or not.
    Instances,
}

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();

    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_env("MCMCP_LOG").unwrap_or_else(|_| EnvFilter::new("info")))
        // stderr, always. stdout belongs to MCP.
        .with_writer(std::io::stderr)
        .init();

    if let Some(state_dir) = &cli.state_dir {
        // SAFETY: set once, before any task that reads it is spawned.
        unsafe { std::env::set_var("MCMCP_ORCHESTRATOR_HOME", state_dir) };
    }

    match cli.command {
        Command::Serve {
            strict_approval,
            no_stdio,
        } => serve(cli.link_port, !strict_approval, no_stdio).await,
        Command::Instances => list_instances(),
    }
}

fn open_store() -> Result<Arc<Mutex<ApprovalStore>>> {
    paths::ensure_state_directory()?;
    let path = paths::approval_store_path()?;
    let store = ApprovalStore::load(&path)
        .with_context(|| format!("loading the approval store at {}", path.display()))?;
    Ok(Arc::new(Mutex::new(store)))
}

fn list_instances() -> Result<()> {
    let store = open_store()?;
    let store = store.lock().expect("store lock");

    let known: Vec<_> = store.all().collect();
    if known.is_empty() {
        println!("No instances have connected yet.");
        return Ok(());
    }
    for instance in known {
        let state = if instance.revoked { "revoked" } else { "approved" };
        println!(
            "{:<24} {:<10} {}",
            instance.id,
            state,
            instance
                .game_directory
                .as_deref()
                .unwrap_or("(unknown directory)")
        );
        println!("{:<24} {}", "", instance.label);
    }
    Ok(())
}

async fn serve(link_port: u16, trust_on_first_use: bool, no_stdio: bool) -> Result<()> {
    let store = open_store()?;
    store
        .lock()
        .expect("store lock")
        .set_strict_approval(!trust_on_first_use);

    let registry = Arc::new(Registry::new());
    let (events_tx, mut events_rx) = mpsc::unbounded_channel();
    let (approvals_tx, approvals_rx) = mpsc::channel::<ApprovalRequest>(8);
    let (downstream_tx, downstream_rx) = mpsc::unbounded_channel();

    spawn_approver(approvals_rx, trust_on_first_use);

    let router = Arc::new(Router::new(
        Arc::clone(&registry),
        Arc::clone(&store),
        downstream_tx,
    ));

    // Seed the tool surface from the last session. Without this, a client that connects before any
    // game is up sees only the roster tool and plans around having no others.
    let cache_path = paths::catalogue_cache_path()?;
    if let Some(cached) = catalogue::load_cache(&cache_path) {
        info!(tools = cached.tools.len(), "restored the cached tool catalogue");
        router.restore_cache(cached);
    }

    // Instance traffic reaches the client through the router, which rewrites what changed meaning
    // on the way across — resource URIs, log lines, catalogue changes.
    {
        let router = Arc::clone(&router);
        let cache_path = cache_path.clone();
        tokio::spawn(async move {
            while let Some(event) = events_rx.recv().await {
                router.handle_upstream(event).await;
                // Persist after each change rather than at exit: the orchestrator is killed far
                // more often than it is asked to stop, and a cache only written on a clean exit is
                // a cache that is usually stale.
                if let Some(aggregate) = router.cached_aggregate()
                    && let Err(error) = catalogue::save_cache(&cache_path, &aggregate)
                {
                    warn!(%error, "could not save the catalogue cache");
                }
            }
        });
    }

    let address = format!("127.0.0.1:{link_port}");
    let listener = TcpListener::bind(&address)
        .await
        .with_context(|| format!("binding the link listener on {address}"))?;

    let context = LinkContext {
        registry: Arc::clone(&registry),
        store,
        events: events_tx,
        approvals: approvals_tx,
    };

    let links = tokio::spawn(link::listener::serve(listener, context));

    if no_stdio {
        info!("serving links only; MCP over stdio is disabled");
        tokio::select! {
            result = links => result.unwrap_or(Ok(())),
            _ = tokio::signal::ctrl_c() => {
                info!("shutting down");
                Ok(())
            }
        }
    } else {
        // stdio owns the lifetime from here: when the client closes stdin it is gone, and there is
        // nothing left to orchestrate for.
        let result = stdio::serve(router, downstream_rx).await;
        links.abort();
        result
    }
}

/// Answers approval requests by policy.
///
/// This is the seam the desktop app replaces with a dialog. It is a task reading a channel, not an
/// interface implemented by the GUI, which is what makes "no privileged back door" structural rather
/// than a promise.
fn spawn_approver(mut requests: mpsc::Receiver<ApprovalRequest>, trust_on_first_use: bool) {
    tokio::spawn(async move {
        while let Some(request) = requests.recv().await {
            let hello = &request.hello;
            let outcome = if trust_on_first_use {
                info!(
                    instance = %hello.instance_id,
                    label = %hello.label,
                    side = hello.side.as_str(),
                    directory = hello.game_directory.as_deref().unwrap_or("(unknown)"),
                    "approving a new instance on first use"
                );
                ApprovalOutcome::Approve
            } else {
                warn!(
                    instance = %hello.instance_id,
                    label = %hello.label,
                    directory = hello.game_directory.as_deref().unwrap_or("(unknown)"),
                    "refusing an unapproved instance; approve it and it will connect on its next retry"
                );
                ApprovalOutcome::Pending
            };
            let _ = request.respond.send(outcome);
        }
    });
}
