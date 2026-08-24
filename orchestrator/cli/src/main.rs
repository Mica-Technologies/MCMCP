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
use mcmcp_orchestrator_core::events::{Actor, EventLog, Filter, Level};
use mcmcp_orchestrator_core::link::listener::{ApprovalOutcome, ApprovalRequest, LinkContext};
use mcmcp_orchestrator_core::policy::{Class, Rule};
use mcmcp_orchestrator_core::registry::Registry;
use mcmcp_orchestrator_core::router::Router;
use mcmcp_orchestrator_core::store::ApprovalStore;
use mcmcp_orchestrator_core::{catalogue, control, link, paths, stdio};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::net::TcpListener;
use tokio::sync::mpsc;
use tracing::{debug, info, warn};
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

    /// Approve an instance so it may connect.
    ///
    /// Only needed with --strict-approval; the default approves on first use.
    Approve {
        /// The instance id, as shown by `instances`.
        instance: String,
    },

    /// Revoke an instance. A running orchestrator disconnects it within a couple of seconds.
    Revoke { instance: String },

    /// Rename an instance, so it can be told apart from the others.
    Label { instance: String, label: String },

    /// Read or change the gating policy.
    Policy {
        /// Which class of tool: read-only, mutating, or destructive.
        #[arg(long, value_parser = parse_class)]
        class: Option<Class>,

        /// What to do with it: allow, ask, or deny. `ask` needs the desktop app; headless denies.
        #[arg(long, value_parser = parse_rule)]
        rule: Option<Rule>,

        /// Apply to one instance rather than to the default for all of them.
        #[arg(long)]
        instance: Option<String>,

        /// Require destructive calls to name their instance rather than following focus.
        #[arg(long)]
        require_explicit_instance: Option<bool>,
    },

    /// Read the event log.
    Log {
        /// Only this instance.
        #[arg(long)]
        instance: Option<String>,

        /// Only this actor: model, human, or system.
        #[arg(long, value_parser = parse_actor)]
        actor: Option<Actor>,

        /// Substring to search the rendered summaries for.
        #[arg(long)]
        grep: Option<String>,

        /// How many entries to show.
        #[arg(long, default_value_t = 50)]
        limit: usize,

        /// Print each entry as JSON, for pasting into a conversation or a bug report.
        #[arg(long)]
        json: bool,
    },
}

fn parse_class(value: &str) -> Result<Class, String> {
    match value.replace('-', "_").to_lowercase().as_str() {
        "read_only" | "readonly" | "read" => Ok(Class::ReadOnly),
        "mutating" | "mutate" | "write" => Ok(Class::Mutating),
        "destructive" => Ok(Class::Destructive),
        other => Err(format!(
            "unknown class '{other}'; expected read-only, mutating or destructive"
        )),
    }
}

fn parse_rule(value: &str) -> Result<Rule, String> {
    match value.to_lowercase().as_str() {
        "allow" => Ok(Rule::Allow),
        "ask" => Ok(Rule::Ask),
        "deny" => Ok(Rule::Deny),
        other => Err(format!("unknown rule '{other}'; expected allow, ask or deny")),
    }
}

fn parse_actor(value: &str) -> Result<Actor, String> {
    match value.to_lowercase().as_str() {
        "model" => Ok(Actor::Model),
        "human" | "person" => Ok(Actor::Human),
        "system" => Ok(Actor::System),
        other => Err(format!(
            "unknown actor '{other}'; expected model, human or system"
        )),
    }
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
        Command::Approve { instance } => approve(&instance),
        Command::Revoke { instance } => revoke(&instance),
        Command::Label { instance, label } => set_label(&instance, &label),
        Command::Policy {
            class,
            rule,
            instance,
            require_explicit_instance,
        } => policy_command(class, rule, instance.as_deref(), require_explicit_instance),
        Command::Log {
            instance,
            actor,
            grep,
            limit,
            json,
        } => show_log(instance, actor, grep, limit, json),
    }
}

// ----------------------------------------------------------------------------------
// Human-only operations
//
// These are the ones a model must never reach. The authority check lives in the core so
// there is exactly one of it; running them here is what "the CLI can do everything,
// because the CLI is you" means.
// ----------------------------------------------------------------------------------

fn approve(instance: &str) -> Result<()> {
    let store = open_store()?;
    let mut store = store.lock().expect("store lock");
    let Some(known) = store.get(instance).cloned() else {
        anyhow::bail!(
            "this orchestrator has never seen an instance called '{instance}'. It has to connect \
             once before it can be approved — start the game, then run this again."
        );
    };
    // Re-approving through the stored hash: the raw secret is not here and must not be.
    store.approve_known(
        &known.id,
        &known.secret_hash,
        &known.label,
        known.game_directory.as_deref(),
    );
    store.save()?;
    println!("Approved {instance}. It will connect on its next attempt, within about 30 seconds.");
    Ok(())
}

fn revoke(instance: &str) -> Result<()> {
    let store = open_store()?;
    let mut store = store.lock().expect("store lock");
    if !store.revoke(instance) {
        anyhow::bail!("this orchestrator has never seen an instance called '{instance}'");
    }
    store.save()?;
    println!(
        "Revoked {instance}. A running orchestrator disconnects it within a couple of seconds; it \
         will keep retrying and being refused until you approve it again."
    );
    Ok(())
}

fn set_label(instance: &str, label: &str) -> Result<()> {
    let store = open_store()?;
    let mut store = store.lock().expect("store lock");
    if !store.set_label(instance, label) {
        anyhow::bail!("this orchestrator has never seen an instance called '{instance}'");
    }
    store.save()?;
    println!("{instance} is now \"{label}\".");
    Ok(())
}

fn policy_command(
    class: Option<Class>,
    rule: Option<Rule>,
    instance: Option<&str>,
    require_explicit_instance: Option<bool>,
) -> Result<()> {
    let state = paths::ensure_state_directory()?;
    let path = control::policy_path(&state);
    let mut policy = control::load_policy(&path)?;

    let mut changed = false;
    match (class, rule) {
        (Some(class), Some(rule)) => {
            policy.set_rule(instance, class, rule);
            changed = true;
        }
        (Some(_), None) | (None, Some(_)) => {
            anyhow::bail!("--class and --rule go together; give both or neither");
        }
        (None, None) => {}
    }
    if let Some(require) = require_explicit_instance {
        policy.require_explicit_instance_for_destructive = require;
        changed = true;
    }

    if changed {
        control::save_policy(&path, &policy)?;
        println!(
            "Saved to {}. A running orchestrator picks this up within a couple of seconds.",
            path.display()
        );
    }

    println!(
        "default          read-only={:?} mutating={:?} destructive={:?}",
        policy.defaults.read_only, policy.defaults.mutating, policy.defaults.destructive
    );
    for (id, rules) in &policy.per_instance {
        println!(
            "{id:<16} read-only={:?} mutating={:?} destructive={:?}",
            rules.read_only, rules.mutating, rules.destructive
        );
    }
    println!(
        "destructive calls must name their instance: {}",
        policy.require_explicit_instance_for_destructive
    );
    Ok(())
}

fn show_log(
    instance: Option<String>,
    actor: Option<Actor>,
    grep: Option<String>,
    limit: usize,
    as_json: bool,
) -> Result<()> {
    // Read from the file rather than a running process: the most valuable moment for this log is
    // after something went wrong, which is frequently after the thing that went wrong stopped.
    let path = paths::event_log_path()?;
    let text = match std::fs::read_to_string(&path) {
        Ok(text) => text,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            println!("No events recorded yet ({}).", path.display());
            return Ok(());
        }
        Err(error) => return Err(error.into()),
    };

    let filter = Filter {
        instance,
        actor,
        text: grep,
        ..Filter::default()
    };
    let log = EventLog::in_memory();
    for line in text.lines() {
        if line.trim().is_empty() {
            continue;
        }
        // A truncated final line is normal — the file is appended to by a live process.
        if let Ok(event) = serde_json::from_str(line) {
            log.record(event);
        }
    }

    for event in log.slice(&filter, limit) {
        if as_json {
            println!("{}", serde_json::to_string(&event)?);
        } else {
            let marker = match event.level {
                Level::Error => "!!",
                Level::Warn => " !",
                _ => "  ",
            };
            println!("{marker} {:>14} {:?} {}", event.at, event.actor, event.summary());
        }
    }
    Ok(())
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

    // The event log goes to disk as well as memory. The most valuable moment for it is after
    // something has gone wrong, which is frequently after the process that recorded it stopped.
    let events = EventLog::with_file(paths::event_log_path()?, EVENT_LOG_LIMIT_BYTES);
    let policy_path = control::policy_path(&paths::state_directory()?);
    let policy = Arc::new(Mutex::new(control::load_policy(&policy_path)?));

    let router = Arc::new(
        Router::new(Arc::clone(&registry), Arc::clone(&store), downstream_tx)
            .with_events(events.clone())
            .with_policy(Arc::clone(&policy)),
    );

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

    spawn_control_watcher(
        Arc::clone(&store),
        Arc::clone(&policy),
        Arc::clone(&registry),
        policy_path,
        events,
    );

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

/// How often the running orchestrator re-reads the files the CLI writes.
///
/// Short enough that `revoke` feels immediate; long enough that it is two small file reads a second
/// rather than anything worth thinking about.
const CONTROL_POLL: Duration = Duration::from_secs(2);

/// Bytes before the event log rotates. One previous file is kept.
const EVENT_LOG_LIMIT_BYTES: u64 = 8 * 1024 * 1024;

/// Watches the approval store and the policy file, and acts on what changed.
///
/// This is what makes file-based control equivalent to a control socket rather than a poor
/// substitute for one. Writing a file is how the CLI sends a message; this is what receives it. The
/// case that would otherwise be wrong is revocation: without this, revoking an instance would leave
/// it connected until it happened to reconnect, which for a healthy link is never.
fn spawn_control_watcher(
    store: Arc<Mutex<ApprovalStore>>,
    policy: Arc<Mutex<mcmcp_orchestrator_core::policy::Policy>>,
    registry: Arc<Registry>,
    policy_path: std::path::PathBuf,
    events: EventLog,
) {
    tokio::spawn(async move {
        let store_path = match paths::approval_store_path() {
            Ok(path) => path,
            Err(error) => {
                warn!(%error, "control watcher could not resolve the approval store");
                return;
            }
        };

        loop {
            tokio::time::sleep(CONTROL_POLL).await;

            // Reload the approvals and disconnect anything that was revoked while connected.
            match ApprovalStore::load(&store_path) {
                Ok(reloaded) => {
                    let revoked: Vec<String> = registry
                        .ids()
                        .into_iter()
                        .filter(|id| reloaded.get(id).map(|known| known.revoked).unwrap_or(false))
                        .collect();

                    // Swap the contents rather than the Arc: the link listener holds the same one.
                    if let Ok(mut current) = store.lock() {
                        *current = reloaded;
                    }

                    for id in revoked {
                        if let Some(instance) = registry.get(&id) {
                            events.note(Actor::Human, Some(id.clone()), "revoked; disconnecting");
                            info!(instance = %id, "revoked; disconnecting");
                            instance.mark_closed();
                            registry.remove(&id, &instance);
                        }
                    }
                }
                // A half-written store is what a concurrent save looks like; the next poll gets it.
                Err(error) => debug!(%error, "could not reload the approval store"),
            }

            match control::load_policy(&policy_path) {
                Ok(reloaded) => {
                    if let Ok(mut current) = policy.lock() {
                        *current = reloaded;
                    }
                }
                Err(error) => debug!(%error, "could not reload the gating policy"),
            }
        }
    });
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
