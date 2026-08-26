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
use tracing_subscriber::fmt::writer::MakeWriterExt;

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
    /// Bridge an MCP client's stdio to a running orchestrator app.
    ///
    /// This is what goes in an MCP client's configuration when the desktop app is what serves it:
    /// the app cannot use stdio itself, because its own stdin and stdout belong to whatever
    /// launched it, and an MCP client expects to spawn what it talks to.
    Shim {
        /// Port the app is listening on.
        #[arg(long, default_value_t = mcmcp_orchestrator_core::mcp_socket::DEFAULT_MCP_PORT)]
        mcp_port: u16,

        /// Do not try to start the app if nothing is listening.
        #[arg(long)]
        no_launch: bool,
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

    // The state directory has to exist before the log inside it can be opened. `--state-dir` is
    // applied first for the same reason: it decides where that directory is.
    if let Some(state_dir) = &cli.state_dir {
        // SAFETY: set once, before any task that reads it is spawned.
        unsafe { std::env::set_var("MCMCP_ORCHESTRATOR_HOME", state_dir) };
    }
    let _ = paths::ensure_state_directory();

    let builder = tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_env("MCMCP_LOG").unwrap_or_else(|_| EnvFilter::new("info")))
        .with_ansi(false);
    // stderr, always — stdout belongs to MCP — and a file beside it, so a run launched detached
    // still leaves its warnings somewhere readable.
    match paths::open_log_file() {
        Ok(file) => builder.with_writer(std::io::stderr.and(file)).init(),
        Err(error) => {
            builder.with_writer(std::io::stderr).init();
            tracing::warn!(%error, "could not open the diagnostic log; logging to stderr only");
        }
    }

    match cli.command {
        Command::Serve {
            strict_approval,
            no_stdio,
        } => serve(cli.link_port, !strict_approval, no_stdio).await,
        Command::Shim { mcp_port, no_launch } => shim(mcp_port, !no_launch).await,
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

/// Pumps an MCP client's stdio to and from the app.
///
/// Deliberately a pump and not a translator: what arrives on stdin is JSON-RPC, one object per line,
/// and what the app's socket wants is JSON-RPC, one object per line. Parsing in the middle would add
/// a place for the two to disagree and buy nothing.
///
/// Reads the token from the state directory rather than taking one as an argument. An MCP client
/// configuration containing a secret is a secret in a file people paste into issues.
async fn shim(mcp_port: u16, may_launch: bool) -> Result<()> {
    use mcmcp_orchestrator_core::mcp_socket;
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

    let state = paths::state_directory()?;

    let stream = match connect(mcp_port).await {
        Ok(stream) => stream,
        Err(_) if may_launch => {
            // Nothing listening. Starting the app is the entire point of the shim being a separate
            // process: an MCP client connecting is what brings the orchestrator up, so there is no
            // "did I remember to start it?" step.
            launch_app()?;
            wait_for_app(mcp_port).await?
        }
        Err(error) => {
            anyhow::bail!(
                "nothing is listening on 127.0.0.1:{mcp_port} ({error}). Start the MCMCP \
                 Orchestrator app, or drop --no-launch and this will start it."
            );
        }
    };

    let token = mcp_socket::read_token(&state).context(
        "reading the orchestrator's token. It is written when the app first runs; if it has never \
         run on this machine, start it once.",
    )?;

    // into_split rather than split: the stdin pump runs in its own task, which needs an owned half.
    // A borrowing split would tie both halves to this stack frame.
    let (read_half, mut write_half) = stream.into_split();
    let mut from_app = BufReader::new(read_half);

    // Attach before anything else. Until the app answers, this connection may not carry MCP.
    mcmcp_orchestrator_core::link::framing::write_frame(
        &mut write_half,
        &serde_json::json!({ "type": "attach", "token": token }),
    )
    .await?;

    match mcmcp_orchestrator_core::link::framing::read_frame(&mut from_app).await? {
        Some(frame) if frame.get("type").and_then(|t| t.as_str()) == Some("attached") => {}
        Some(frame) => anyhow::bail!(
            "the orchestrator refused this connection: {}",
            frame
                .get("message")
                .and_then(|m| m.as_str())
                .unwrap_or("no reason given")
        ),
        None => anyhow::bail!("the orchestrator closed the connection without answering"),
    }

    // Two pumps, in opposite directions, each ending when its source does.
    let to_app = tokio::spawn(async move {
        let mut lines = BufReader::new(tokio::io::stdin()).lines();
        while let Ok(Some(line)) = lines.next_line().await {
            if line.trim().is_empty() {
                continue;
            }
            if write_half.write_all(line.as_bytes()).await.is_err()
                || write_half.write_all(b"\n").await.is_err()
                || write_half.flush().await.is_err()
            {
                break;
            }
        }
    });

    let mut stdout = tokio::io::stdout();
    let mut lines = from_app.lines();
    while let Some(line) = lines.next_line().await? {
        stdout.write_all(line.as_bytes()).await?;
        stdout.write_all(b"\n").await?;
        stdout.flush().await?;
    }

    to_app.abort();
    Ok(())
}

async fn connect(port: u16) -> Result<tokio::net::TcpStream> {
    Ok(tokio::net::TcpStream::connect(("127.0.0.1", port)).await?)
}

/// Starts the desktop app beside this process and leaves it running.
///
/// Looked for next to this binary first, which is where an installer puts them both.
fn launch_app() -> Result<()> {
    let executable = std::env::current_exe()?;
    let directory = executable.parent().unwrap_or_else(|| std::path::Path::new("."));
    let candidate = directory.join(if cfg!(windows) {
        "mcmcp-orchestrator-app.exe"
    } else {
        "mcmcp-orchestrator-app"
    });

    let program = if candidate.exists() {
        candidate
    } else {
        "mcmcp-orchestrator-app".into()
    };
    info!(app = %program.display(), "starting the orchestrator app");
    std::process::Command::new(&program).spawn().with_context(|| {
        format!(
            "could not start {}. Install the MCMCP Orchestrator app, or start it yourself and run \
             this again.",
            program.display()
        )
    })?;
    Ok(())
}

/// Waits for the app to come up, rather than racing it.
async fn wait_for_app(port: u16) -> Result<tokio::net::TcpStream> {
    for _ in 0..60 {
        tokio::time::sleep(Duration::from_millis(250)).await;
        if let Ok(stream) = connect(port).await {
            return Ok(stream);
        }
    }
    anyhow::bail!("the orchestrator app did not start listening on 127.0.0.1:{port} within 15s")
}

// ----------------------------------------------------------------------------------
// Human-only operations
//
// These are the ones a model must never reach. The authority check lives in the core so
// there is exactly one of it; running them here is what "the CLI can do everything,
// because the CLI is you" means.
// ----------------------------------------------------------------------------------

/// Turns whatever a person typed into the game id the store is keyed by.
///
/// Approval, revocation and labelling are per game; routing is per endpoint. Someone copying an id
/// out of a tool result or the app's roster has an endpoint id in hand, and refusing it because the
/// store has never heard of `atm9-3f2a1c.client` would be technically true and useless.
fn game_of(store: &mcmcp_orchestrator_core::store::ApprovalStore, instance: &str) -> Option<String> {
    store.game_of(instance)
}

fn approve(instance: &str) -> Result<()> {
    let store = open_store()?;
    let mut store = store.lock().expect("store lock");
    let known = game_of(&store, instance).and_then(|game| store.get(&game).cloned());
    let Some(known) = known else {
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
    let game = game_of(&store, instance);
    if !game.map(|game| store.revoke(&game)).unwrap_or(false) {
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
    let game = game_of(&store, instance);
    // Renames the game, which is to say both of its endpoints. The label is what somebody typed to
    // mean "this Minecraft install", not "the client half of it".
    if !game.map(|game| store.set_label(&game, label)).unwrap_or(false) {
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
        if !instance.endpoints.is_empty() {
            // The game id is what an approval is filed under; these are what a call can be routed
            // to. A singleplayer world has both, and they are not interchangeable.
            let addressable: Vec<String> = instance
                .endpoints
                .iter()
                .map(|side| format!("{}.{side}", instance.id))
                .collect();
            println!("{:<24} {}", "", addressable.join("  "));
        }
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

    spawn_approver(approvals_rx, trust_on_first_use);

    // The event log goes to disk as well as memory. The most valuable moment for it is after
    // something has gone wrong, which is frequently after the process that recorded it stopped.
    let events = EventLog::with_file(paths::event_log_path()?, EVENT_LOG_LIMIT_BYTES);
    let policy_path = control::policy_path(&paths::state_directory()?);
    let policy = Arc::new(Mutex::new(control::load_policy(&policy_path)?));

    let router = Arc::new(
        Router::new(Arc::clone(&registry), Arc::clone(&store))
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
    let listener = match TcpListener::bind(&address).await {
        Ok(listener) => listener,
        Err(error) if error.kind() == std::io::ErrorKind::AddrInUse => {
            // Almost always a second orchestrator, and the advice is to use that one rather than
            // this. Worth saying: an MCP client sees only that the command exited, and "address in
            // use" on its own leaves somebody hunting for which address and whose.
            anyhow::bail!(
                concat!(
                    "Another orchestrator is already listening on {}. ",
                    "Only one can accept instance links at a time.\n",
                    "\n",
                    "If the desktop app is running, point your MCP client at it instead:\n",
                    "    mcmcp-orchestrator shim\n",
                    "\n",
                    "To run a second one anyway — a separate set of games on a separate port — ",
                    "give it its own with --link-port and its own state with --state-dir, and set ",
                    "orchestrator.orchestratorPort to match in each game's config."
                ),
                address
            );
        }
        Err(error) => {
            return Err(error).with_context(|| format!("binding the link listener on {address}"));
        }
    };

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
        let result = stdio::serve(router).await;
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
                    // Matched on the game rather than the endpoint id. A revocation is filed
                    // against the game, so comparing endpoint ids against the store would find
                    // nothing and leave a revoked instance connected until it happened to drop.
                    let revoked: Vec<String> = registry
                        .all()
                        .into_iter()
                        .filter(|handle| {
                            reloaded
                                .get(&handle.info().approval_id)
                                .map(|known| known.revoked)
                                .unwrap_or(false)
                        })
                        .map(|handle| handle.id())
                        .collect();

                    // Swap the contents rather than the Arc: the link listener holds the same one.
                    if let Ok(mut current) = store.lock() {
                        *current = reloaded;
                    }

                    for id in revoked {
                        if let Some(instance) = registry.get(&id) {
                            events.note_about(
                                Actor::Human,
                                Some(id.clone()),
                                instance.info().label,
                                "revoked; disconnecting",
                            );
                            info!(instance = %id, label = %instance.info().label, "revoked; disconnecting");
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
