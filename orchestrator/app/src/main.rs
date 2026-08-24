// A GUI process must not open a console window on Windows. The attribute is release-only so that a
// debug run still has somewhere to print a panic.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

//! The MCMCP orchestrator, with a window.
//!
//! # What the window is actually for
//!
//! Not "the headless one but nicer". Everything here is something a terminal genuinely cannot do:
//!
//! - **Naming.** "Which instance is which" is the hardest problem in the whole design. Deriving a
//!   name from a folder is a guess; typing one is not.
//! - **Approving.** Trust on first use is only meaningful if somebody is there to see the first use.
//!   A headless process has to either approve everything or refuse everything.
//! - **Watching.** A live view of every call a model makes, filtered by instance, is the feature
//!   that earns this app for a mod-development loop. It is what you would otherwise get by tailing
//!   `latest.log` and squinting.
//! - **Gating.** The policy's `Ask` state has no meaning without a screen. Headless, it denies.
//! - **Sharing focus.** Clicking an instance to focus it means the human and the model have one
//!   notion of "the current game" instead of two.
//!
//! # The rule this file must not break
//!
//! The GUI is a *client* of the core, with no privileged path into it. It answers the same channels
//! the CLI answers and calls the same functions; the only difference is that it can put a question
//! on a screen. Every Tauri command below is a thin wrapper over something `mcmcp-orchestrator-core`
//! already does — if one of them ever needs to reach past the core to work, the core is missing
//! something and that is the bug to fix.

use mcmcp_orchestrator_core::control::{self, Authority};
use mcmcp_orchestrator_core::events::{Actor, EventLog, Filter, Level};
use mcmcp_orchestrator_core::instance::UpstreamEvent;
use mcmcp_orchestrator_core::link::listener::{ApprovalOutcome, ApprovalRequest, LinkContext};
use mcmcp_orchestrator_core::policy::{Class, Policy, Rule};
use mcmcp_orchestrator_core::registry::Registry;
use mcmcp_orchestrator_core::router::{GateRequest, Router};
use mcmcp_orchestrator_core::store::ApprovalStore;
use mcmcp_orchestrator_core::{catalogue, link, paths};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter, State};
use tokio::sync::{mpsc, oneshot};
use tracing::{info, warn};

/// Emitted whenever anything the UI shows may have changed.
///
/// One event rather than a taxonomy of them: the UI re-reads what it is showing, which is a few
/// hundred rows at worst and far simpler than keeping a client-side model in step with a
/// server-side one. Correctness beats cleverness in a panel somebody is using to decide whether to
/// let a model reshape their world.
const CHANGED: &str = "mcmcp://changed";

const EVENT_LOG_LIMIT_BYTES: u64 = 8 * 1024 * 1024;

// ----------------------------------------------------------------------------------
// Things waiting on a person
// ----------------------------------------------------------------------------------

/// A queue of questions the UI has not answered yet.
///
/// Generic over the answer as well as the question: approvals resolve to an [`ApprovalOutcome`] and
/// gates to a plain `bool`, and collapsing both to a boolean would throw away the distinction
/// between "not now" and "never" — which is the whole difference between an instance that retries
/// and one that stops.
///
/// The id is what survives the round trip through JavaScript, since a `oneshot::Sender` obviously
/// cannot.
struct PendingQueue<T, R> {
    items: Mutex<HashMap<u64, (T, oneshot::Sender<R>)>>,
    next_id: AtomicU64,
}

impl<T, R> PendingQueue<T, R> {
    fn new() -> Self {
        Self {
            items: Mutex::new(HashMap::new()),
            next_id: AtomicU64::new(1),
        }
    }

    fn push(&self, description: T, respond: oneshot::Sender<R>) -> u64 {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        self.items
            .lock()
            .expect("pending lock")
            .insert(id, (description, respond));
        id
    }

    /// Answers one question. Returns the description, so the caller can act on what it was about.
    fn answer(&self, id: u64, response: R) -> Option<T> {
        let (description, respond) = self.items.lock().expect("pending lock").remove(&id)?;
        // A dropped receiver means the link went away while the dialog was up, which is ordinary
        // rather than exceptional — somebody closed the game while deciding.
        let _ = respond.send(response);
        Some(description)
    }

    fn describe<F, V>(&self, mut render: F) -> Vec<V>
    where
        F: FnMut(u64, &T) -> V,
    {
        let items = self.items.lock().expect("pending lock");
        let mut rendered: Vec<(u64, V)> = items
            .iter()
            .map(|(id, (description, _))| (*id, render(*id, description)))
            .collect();
        rendered.sort_by_key(|(id, _)| *id);
        rendered.into_iter().map(|(_, rendered)| rendered).collect()
    }
}

type ApprovalQueue = PendingQueue<mcmcp_orchestrator_core::link::listener::HelloSummary, ApprovalOutcome>;
type GateQueue = PendingQueue<GateDescription, bool>;

#[derive(Clone, Serialize)]
struct PendingApprovalView {
    id: u64,
    instance: String,
    label: String,
    side: String,
    game_directory: Option<String>,
    mod_version: String,
    minecraft_version: String,
}

/// A parked gate question, without the channel that answers it.
///
/// Deliberately not a `GateRequest`: that owns the responder, and the queue already holds the
/// responder separately. Storing one inside the other would mean two channels for one answer, and
/// exactly one of them would ever be used.
#[derive(Clone)]
struct GateDescription {
    instance: String,
    tool: String,
    arguments: Value,
    reason: String,
}

#[derive(Clone, Serialize)]
struct PendingGateView {
    id: u64,
    instance: String,
    tool: String,
    arguments: Value,
    reason: String,
}

// ----------------------------------------------------------------------------------
// Application state
// ----------------------------------------------------------------------------------

struct AppState {
    registry: Arc<Registry>,
    store: Arc<Mutex<ApprovalStore>>,
    policy: Arc<Mutex<Policy>>,
    policy_path: std::path::PathBuf,
    events: EventLog,
    approvals: Arc<ApprovalQueue>,
    gates: Arc<GateQueue>,
    link_port: u16,
}

// ----------------------------------------------------------------------------------
// Commands
//
// Each is a thin wrapper over the core. Authority::Human is passed explicitly rather than
// assumed, so the one place that decides what a model may not do is the same place for
// the GUI, the CLI and the MCP surface.
// ----------------------------------------------------------------------------------

#[derive(Serialize)]
struct InstanceView {
    instance: String,
    label: String,
    side: String,
    connected: bool,
    focused: bool,
    game_directory: Option<String>,
    mod_version: String,
    minecraft_version: String,
    http_endpoint: Option<String>,
    tools: usize,
    revoked: bool,
}

#[tauri::command]
fn list_instances(state: State<'_, AppState>) -> Vec<InstanceView> {
    let focus = state.registry.focus();
    let mut views: Vec<InstanceView> = state
        .registry
        .all()
        .iter()
        .map(|instance| {
            let info = instance.info();
            InstanceView {
                focused: focus.as_deref() == Some(info.id.as_str()),
                instance: info.id,
                label: info.label,
                side: info.side.as_str().to_string(),
                connected: true,
                game_directory: info.game_directory,
                mod_version: info.mod_version,
                minecraft_version: info.minecraft_version,
                http_endpoint: info.endpoint_url,
                tools: instance.catalogue().tools.len(),
                revoked: false,
            }
        })
        .collect();

    // Known but not running, so "where did my other game go" has an answer that is not silence.
    let connected: Vec<String> = views.iter().map(|view| view.instance.clone()).collect();
    if let Ok(store) = state.store.lock() {
        for known in store.all() {
            if connected.contains(&known.id) {
                continue;
            }
            views.push(InstanceView {
                instance: known.id.clone(),
                label: known.label.clone(),
                side: String::new(),
                connected: false,
                focused: false,
                game_directory: known.game_directory.clone(),
                mod_version: String::new(),
                minecraft_version: String::new(),
                http_endpoint: None,
                tools: 0,
                revoked: known.revoked,
            });
        }
    }
    views
}

#[derive(Deserialize)]
struct LogQuery {
    instance: Option<String>,
    actor: Option<String>,
    text: Option<String>,
    #[serde(default)]
    min_level: Option<String>,
    #[serde(default = "default_limit")]
    limit: usize,
}

fn default_limit() -> usize {
    300
}

#[tauri::command]
fn list_events(state: State<'_, AppState>, query: LogQuery) -> Vec<Value> {
    let filter = Filter {
        instance: query.instance.filter(|value| !value.is_empty()),
        actor: query.actor.as_deref().and_then(|actor| match actor {
            "model" => Some(Actor::Model),
            "human" => Some(Actor::Human),
            "system" => Some(Actor::System),
            _ => None,
        }),
        min_level: query.min_level.as_deref().and_then(|level| match level {
            "warn" => Some(Level::Warn),
            "error" => Some(Level::Error),
            _ => None,
        }),
        text: query.text.filter(|value| !value.is_empty()),
        ..Filter::default()
    };

    state
        .events
        .slice(&filter, query.limit)
        .into_iter()
        .map(|event| {
            // The summary rides along beside the record so the UI renders a line without
            // reimplementing the match, and "copy as JSON" still hands over the real thing.
            let mut value = serde_json::to_value(&event).unwrap_or_else(|_| json!({}));
            if let Some(object) = value.as_object_mut() {
                object.insert("summary".into(), json!(event.summary()));
            }
            value
        })
        .collect()
}

#[tauri::command]
fn pending_approvals(state: State<'_, AppState>) -> Vec<PendingApprovalView> {
    state.approvals.describe(|id, hello| PendingApprovalView {
        id,
        instance: hello.instance_id.clone(),
        label: hello.label.clone(),
        side: hello.side.as_str().to_string(),
        game_directory: hello.game_directory.clone(),
        mod_version: hello.mod_version.clone(),
        minecraft_version: hello.minecraft_version.clone(),
    })
}

/// What a person clicked on an approval prompt.
///
/// Three answers, not two, and the third is the one that matters. A plain "deny" has to mean either
/// *not now* — in which case the instance keeps retrying and the prompt returns every thirty
/// seconds — or *never*, in which case an instance denied by a misclick can only be recovered by
/// restarting the game, because a refused-for-good instance stops retrying. Neither is an acceptable
/// only option, so both are offered and the button says which is which.
#[derive(Deserialize)]
#[serde(rename_all = "lowercase")]
enum ApprovalAnswer {
    Approve,
    /// Refuse this attempt. The instance keeps retrying and will ask again.
    Later,
    /// Refuse permanently, and record it so it is refused without asking again.
    Never,
}

#[tauri::command]
fn answer_approval(state: State<'_, AppState>, app: AppHandle, id: u64, answer: ApprovalAnswer) -> bool {
    let outcome = match answer {
        ApprovalAnswer::Approve => ApprovalOutcome::Approve,
        ApprovalAnswer::Later => ApprovalOutcome::Pending,
        ApprovalAnswer::Never => ApprovalOutcome::Reject {
            reason: mcmcp_orchestrator_core::link::protocol::REASON_REVOKED.into(),
            message: "declined in the orchestrator".into(),
        },
    };

    let Some(hello) = state.approvals.answer(id, outcome.clone()) else {
        return false;
    };

    match &outcome {
        ApprovalOutcome::Approve => {
            state
                .events
                .note(Actor::Human, Some(hello.instance_id.clone()), "approved");
        }
        ApprovalOutcome::Pending => {
            state.events.note(
                Actor::Human,
                Some(hello.instance_id.clone()),
                "refused for now; it will ask again",
            );
        }
        ApprovalOutcome::Reject { .. } => {
            // Recorded so the *next* attempt is refused without asking. Without this the instance
            // would be told "revoked" by a store that has never heard of it, and would ask again on
            // the next launch.
            if let Ok(mut store) = state.store.lock() {
                store.deny_forever(&hello.instance_id, &hello.label, hello.game_directory.as_deref());
                let _ = store.save();
            }
            state.events.note(
                Actor::Human,
                Some(hello.instance_id.clone()),
                "declined permanently",
            );
        }
    }

    let _ = app.emit(CHANGED, ());
    true
}

#[tauri::command]
fn pending_gates(state: State<'_, AppState>) -> Vec<PendingGateView> {
    state.gates.describe(|id, request| PendingGateView {
        id,
        instance: request.instance.clone(),
        tool: request.tool.clone(),
        arguments: request.arguments.clone(),
        reason: request.reason.clone(),
    })
}

#[tauri::command]
fn answer_gate(state: State<'_, AppState>, app: AppHandle, id: u64, allow: bool) -> bool {
    let Some(gate) = state.gates.answer(id, allow) else {
        return false;
    };
    state.events.note(
        Actor::Human,
        Some(gate.instance),
        format!("{} {}", if allow { "allowed" } else { "blocked" }, gate.tool),
    );
    let _ = app.emit(CHANGED, ());
    true
}

#[tauri::command]
fn set_focus(state: State<'_, AppState>, app: AppHandle, instance: String) -> Result<(), String> {
    let previous = state.registry.focus();
    if !state.registry.set_focus(&instance) {
        return Err(format!("{instance} is not connected"));
    }
    // Recorded as a human action, which is the point of the distinction: "focus moved to beta"
    // means something entirely different depending on who moved it.
    state.events.record(mcmcp_orchestrator_core::events::Event::new(
        Actor::Human,
        Level::Info,
        Some(instance.clone()),
        mcmcp_orchestrator_core::events::EventKind::FocusChanged {
            from: previous,
            to: instance,
        },
    ));
    let _ = app.emit(CHANGED, ());
    Ok(())
}

#[tauri::command]
fn set_label(
    state: State<'_, AppState>,
    app: AppHandle,
    instance: String,
    label: String,
) -> Result<(), String> {
    let mut store = state.store.lock().map_err(|_| "the approval store is locked")?;
    if !store.set_label(&instance, &label) {
        return Err(format!(
            "this orchestrator has never seen an instance called {instance}"
        ));
    }
    store.save().map_err(|error| error.to_string())?;
    drop(store);

    if let Some(handle) = state.registry.get(&instance) {
        handle.set_label(label.clone());
    }
    state
        .events
        .note(Actor::Human, Some(instance), format!("renamed to \"{label}\""));
    let _ = app.emit(CHANGED, ());
    Ok(())
}

#[tauri::command]
fn revoke(state: State<'_, AppState>, app: AppHandle, instance: String) -> Result<(), String> {
    // The authority check is the core's, not this file's. It is trivially satisfied here — a
    // person is clicking — but routing through it means there is exactly one definition of what a
    // model may not do, rather than one per caller.
    Authority::Human
        .require_human("revoking an instance")
        .map_err(|error| error.to_string())?;

    let mut store = state.store.lock().map_err(|_| "the approval store is locked")?;
    if !store.revoke(&instance) {
        return Err(format!(
            "this orchestrator has never seen an instance called {instance}"
        ));
    }
    store.save().map_err(|error| error.to_string())?;
    drop(store);

    // Disconnect now rather than at its next attempt: a healthy link would never reconnect, so
    // "revoked" would mean "revoked, eventually, if it happens to drop".
    if let Some(handle) = state.registry.get(&instance) {
        handle.mark_closed();
        state.registry.remove(&instance, &handle);
    }
    state.events.record(mcmcp_orchestrator_core::events::Event::new(
        Actor::Human,
        Level::Warn,
        Some(instance),
        mcmcp_orchestrator_core::events::EventKind::InstanceRevoked,
    ));
    let _ = app.emit(CHANGED, ());
    Ok(())
}

#[tauri::command]
fn approve_known(state: State<'_, AppState>, app: AppHandle, instance: String) -> Result<(), String> {
    Authority::Human
        .require_human("approving an instance")
        .map_err(|error| error.to_string())?;

    let mut store = state.store.lock().map_err(|_| "the approval store is locked")?;
    let Some(known) = store.get(&instance).cloned() else {
        return Err(format!(
            "this orchestrator has never seen an instance called {instance}"
        ));
    };
    store.approve_known(
        &known.id,
        &known.secret_hash,
        &known.label,
        known.game_directory.as_deref(),
    );
    store.save().map_err(|error| error.to_string())?;
    drop(store);

    state.events.note(Actor::Human, Some(instance), "approved");
    let _ = app.emit(CHANGED, ());
    Ok(())
}

#[derive(Serialize)]
struct Settings {
    strict_approval: bool,
    require_explicit_instance_for_destructive: bool,
    defaults: PolicyView,
    per_instance: HashMap<String, PolicyView>,
    link_port: u16,
    state_directory: String,
}

#[derive(Serialize)]
struct PolicyView {
    read_only: String,
    mutating: String,
    destructive: String,
}

fn rule_name(rule: Rule) -> String {
    match rule {
        Rule::Allow => "allow",
        Rule::Ask => "ask",
        Rule::Deny => "deny",
    }
    .to_string()
}

#[tauri::command]
fn get_settings(state: State<'_, AppState>) -> Settings {
    let policy = state.policy.lock().expect("policy lock");
    let view = |rules: &mcmcp_orchestrator_core::policy::ClassRules| PolicyView {
        read_only: rule_name(rules.read_only),
        mutating: rule_name(rules.mutating),
        destructive: rule_name(rules.destructive),
    };
    Settings {
        strict_approval: state
            .store
            .lock()
            .map(|store| store.strict_approval())
            .unwrap_or(false),
        require_explicit_instance_for_destructive: policy.require_explicit_instance_for_destructive,
        defaults: view(&policy.defaults),
        per_instance: policy
            .per_instance
            .iter()
            .map(|(id, rules)| (id.clone(), view(rules)))
            .collect(),
        link_port: state.link_port,
        state_directory: paths::state_directory()
            .map(|path| path.display().to_string())
            .unwrap_or_default(),
    }
}

#[tauri::command]
fn set_strict_approval(state: State<'_, AppState>, app: AppHandle, strict: bool) -> Result<(), String> {
    Authority::Human
        .require_human("changing approval strictness")
        .map_err(|e| e.to_string())?;

    let mut store = state.store.lock().map_err(|_| "the approval store is locked")?;
    store.set_strict_approval(strict);
    store.save().map_err(|error| error.to_string())?;
    drop(store);

    state.events.note(
        Actor::Human,
        None,
        if strict {
            "every connection now needs approval"
        } else {
            "trust on first use"
        },
    );
    let _ = app.emit(CHANGED, ());
    Ok(())
}

#[tauri::command]
fn set_policy_rule(
    state: State<'_, AppState>,
    app: AppHandle,
    instance: Option<String>,
    class: String,
    rule: String,
) -> Result<(), String> {
    Authority::Human
        .require_human("changing the gating policy")
        .map_err(|e| e.to_string())?;

    let class = match class.as_str() {
        "read_only" => Class::ReadOnly,
        "mutating" => Class::Mutating,
        "destructive" => Class::Destructive,
        other => return Err(format!("unknown class {other}")),
    };
    let rule = match rule.as_str() {
        "allow" => Rule::Allow,
        "ask" => Rule::Ask,
        "deny" => Rule::Deny,
        other => return Err(format!("unknown rule {other}")),
    };

    let mut policy = state.policy.lock().map_err(|_| "the policy is locked")?;
    policy.set_rule(instance.as_deref().filter(|id| !id.is_empty()), class, rule);
    control::save_policy(&state.policy_path, &policy).map_err(|error| error.to_string())?;
    drop(policy);

    state.events.note(
        Actor::Human,
        instance,
        format!("gating changed to {rule:?} for {class:?}"),
    );
    let _ = app.emit(CHANGED, ());
    Ok(())
}

#[tauri::command]
fn set_require_explicit_instance(
    state: State<'_, AppState>,
    app: AppHandle,
    require: bool,
) -> Result<(), String> {
    Authority::Human
        .require_human("changing the gating policy")
        .map_err(|e| e.to_string())?;

    let mut policy = state.policy.lock().map_err(|_| "the policy is locked")?;
    policy.require_explicit_instance_for_destructive = require;
    control::save_policy(&state.policy_path, &policy).map_err(|error| error.to_string())?;
    drop(policy);

    let _ = app.emit(CHANGED, ());
    Ok(())
}

/// Everything the log holds for one instance, as a file the user can hand to somebody.
#[tauri::command]
fn export_events(state: State<'_, AppState>, instance: Option<String>) -> Result<String, String> {
    let filter = Filter {
        instance: instance.clone().filter(|value| !value.is_empty()),
        ..Filter::default()
    };
    let events = state
        .events
        .slice(&filter, mcmcp_orchestrator_core::events::RING_CAPACITY);

    let directory = paths::state_directory().map_err(|error| error.to_string())?;
    let name = match &instance {
        Some(instance) if !instance.is_empty() => format!("mcmcp-events-{instance}.jsonl"),
        _ => "mcmcp-events.jsonl".to_string(),
    };
    let path = directory.join(name);

    let mut text = String::new();
    for event in &events {
        text.push_str(&serde_json::to_string(event).map_err(|error| error.to_string())?);
        text.push('\n');
    }
    std::fs::write(&path, text).map_err(|error| error.to_string())?;
    Ok(path.display().to_string())
}

// ----------------------------------------------------------------------------------
// Startup
// ----------------------------------------------------------------------------------

fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_env("MCMCP_LOG")
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .with_writer(std::io::stderr)
        .init();

    let link_port: u16 = std::env::var("MCMCP_LINK_PORT")
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(25580);

    paths::ensure_state_directory()?;
    let store = Arc::new(Mutex::new(ApprovalStore::load(paths::approval_store_path()?)?));
    let policy_path = control::policy_path(&paths::state_directory()?);
    let policy = Arc::new(Mutex::new(control::load_policy(&policy_path)?));
    let events = EventLog::with_file(paths::event_log_path()?, EVENT_LOG_LIMIT_BYTES);
    let registry = Arc::new(Registry::new());

    let (downstream_tx, _downstream_rx) = mpsc::unbounded_channel();
    let router = Arc::new(
        Router::new(Arc::clone(&registry), Arc::clone(&store), downstream_tx)
            .with_events(events.clone())
            .with_policy(Arc::clone(&policy)),
    );
    if let Some(cached) = catalogue::load_cache(&paths::catalogue_cache_path()?) {
        router.restore_cache(cached);
    }

    let approvals = Arc::new(PendingQueue::new());
    let gates = Arc::new(PendingQueue::new());

    let state = AppState {
        registry: Arc::clone(&registry),
        store: Arc::clone(&store),
        policy: Arc::clone(&policy),
        policy_path,
        events: events.clone(),
        approvals: Arc::clone(&approvals),
        gates: Arc::clone(&gates),
        link_port,
    };

    tauri::Builder::default()
        .manage(state)
        .invoke_handler(tauri::generate_handler![
            list_instances,
            list_events,
            pending_approvals,
            answer_approval,
            pending_gates,
            answer_gate,
            set_focus,
            set_label,
            revoke,
            approve_known,
            get_settings,
            set_strict_approval,
            set_policy_rule,
            set_require_explicit_instance,
            export_events,
        ])
        .setup(move |app| {
            let handle = app.handle().clone();
            spawn_background(
                handle, registry, store, router, events, approvals, gates, link_port,
            );
            Ok(())
        })
        .run(tauri::generate_context!())?;
    Ok(())
}

/// Starts everything that is not the window.
///
/// Tauri owns the main thread for the event loop, so the async runtime is spawned beside it rather
/// than wrapping it. That is the one structural difference from the CLI, and the reason `main` here
/// is not `#[tokio::main]`.
#[allow(clippy::too_many_arguments)]
fn spawn_background(
    app: AppHandle,
    registry: Arc<Registry>,
    store: Arc<Mutex<ApprovalStore>>,
    router: Arc<Router>,
    events: EventLog,
    approvals: Arc<ApprovalQueue>,
    gates: Arc<GateQueue>,
    link_port: u16,
) {
    std::thread::Builder::new()
        .name("mcmcp-orchestrator".into())
        .spawn(move || {
            let runtime = match tokio::runtime::Runtime::new() {
                Ok(runtime) => runtime,
                Err(error) => {
                    warn!(%error, "could not start the async runtime");
                    return;
                }
            };
            runtime.block_on(async move {
                let (events_tx, mut events_rx) = mpsc::unbounded_channel::<UpstreamEvent>();
                let (approvals_tx, mut approvals_rx) = mpsc::channel::<ApprovalRequest>(8);
                let (gates_tx, mut gates_rx) = mpsc::channel::<GateRequest>(8);
                router.set_gate(gates_tx);

                // Approvals: park the question, wake the window, let a person answer it.
                {
                    let approvals = Arc::clone(&approvals);
                    let app = app.clone();
                    let events = events.clone();
                    tokio::spawn(async move {
                        while let Some(request) = approvals_rx.recv().await {
                            let summary = request.hello.clone();
                            events.note(
                                Actor::System,
                                Some(summary.instance_id.clone()),
                                "waiting to be approved",
                            );
                            approvals.push(summary, request.respond);
                            let _ = app.emit(CHANGED, ());
                        }
                    });
                }

                {
                    let gates = Arc::clone(&gates);
                    let app = app.clone();
                    tokio::spawn(async move {
                        while let Some(request) = gates_rx.recv().await {
                            gates.push(
                                GateDescription {
                                    instance: request.instance,
                                    tool: request.tool,
                                    arguments: request.arguments,
                                    reason: request.reason,
                                },
                                request.respond,
                            );
                            let _ = app.emit(CHANGED, ());
                        }
                    });
                }

                {
                    let router = Arc::clone(&router);
                    let app = app.clone();
                    tokio::spawn(async move {
                        while let Some(event) = events_rx.recv().await {
                            router.handle_upstream(event).await;
                            if let Some(aggregate) = router.cached_aggregate()
                                && let Ok(path) = paths::catalogue_cache_path()
                            {
                                let _ = catalogue::save_cache(&path, &aggregate);
                            }
                            let _ = app.emit(CHANGED, ());
                        }
                    });
                }

                let address = format!("127.0.0.1:{link_port}");
                match tokio::net::TcpListener::bind(&address).await {
                    Ok(listener) => {
                        info!(%address, "listening for MCMCP instances");
                        let context = LinkContext {
                            registry,
                            store,
                            events: events_tx,
                            approvals: approvals_tx,
                        };
                        if let Err(error) = link::listener::serve(listener, context).await {
                            warn!(%error, "the link listener stopped");
                        }
                    }
                    Err(error) => {
                        // Almost always a second copy of the app already running, which is worth
                        // saying plainly — the window would otherwise sit there looking healthy
                        // while no game could ever reach it.
                        warn!(%error, %address, "could not listen for instances");
                        events.note(
                            Actor::System,
                            None,
                            format!(
                                "Could not listen on {address}: {error}. Another orchestrator is \
                                 probably already running."
                            ),
                        );
                        let _ = app.emit(CHANGED, ());
                    }
                }
            });
        })
        .expect("spawning the orchestrator thread");
}

/// Answering an approval the way the headless build would, for reference.
///
/// Not used — the window answers them — but kept as the honest statement of what this app adds:
/// nothing but the ability to ask.
#[allow(dead_code)]
fn headless_outcome(trust_on_first_use: bool) -> ApprovalOutcome {
    if trust_on_first_use {
        ApprovalOutcome::Approve
    } else {
        ApprovalOutcome::Pending
    }
}
