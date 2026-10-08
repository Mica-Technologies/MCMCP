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

use mcmcp_orchestrator_core::activity::{LogLine, Snapshot};
use mcmcp_orchestrator_core::control::{self, Authority};
use mcmcp_orchestrator_core::events::{Actor, EventLog, Filter, Level};
use mcmcp_orchestrator_core::flight::{self, LogCache, SessionSummary};
use mcmcp_orchestrator_core::instance::UpstreamEvent;
use mcmcp_orchestrator_core::link::listener::{ApprovalOutcome, ApprovalRequest, LinkContext};
use mcmcp_orchestrator_core::policy::{Class, Policy, Rule};
use mcmcp_orchestrator_core::registry::Registry;
use mcmcp_orchestrator_core::router::{GateRequest, Router};
use mcmcp_orchestrator_core::store::ApprovalStore;
use mcmcp_orchestrator_core::tasks::{Status, TaskList, TaskStore, TaskUpdate};
use mcmcp_orchestrator_core::{catalogue, jsonrpc, link, mcp_socket, paths, storage};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_autostart::ManagerExt;
use tokio::sync::{mpsc, oneshot};
use tracing::{info, warn};

/// Emitted whenever anything the UI shows may have changed.
///
/// One event rather than a taxonomy of them: the UI re-reads what it is showing, which is a few
/// hundred rows at worst and far simpler than keeping a client-side model in step with a
/// server-side one. Correctness beats cleverness in a panel somebody is using to decide whether to
/// let a model reshape their world.
const CHANGED: &str = "mcmcp://changed";

/// Emitted when a call starts, reports progress or finishes, carrying an activity snapshot.
///
/// The one exception to "one event, and the UI re-reads": progress arrives many times a second
/// during a batch, and re-reading the roster, the prompts and the log for each one is the wrong
/// cost for a moving progress bar. This carries what changed, at most every [`ACTIVITY_INTERVAL`].
const ACTIVITY: &str = "mcmcp://activity";

/// How often, at most, [`ACTIVITY`] is emitted. Ten a second is smooth for a progress bar.
const ACTIVITY_INTERVAL: std::time::Duration = std::time::Duration::from_millis(100);

/// How many finished calls each [`ACTIVITY`] event carries. Enough for every card's "last call" line
/// and the top of a detail view; the detail view reads the rest when it opens.
const ACTIVITY_RECENT: usize = 50;

/// Emitted when a task list changes, by a model or by a person here. No payload: the Tasks tab
/// re-reads the lists, which are few and small.
const TASKS: &str = "mcmcp://tasks";

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
    router: Arc<Router>,
    store: Arc<Mutex<ApprovalStore>>,
    policy: Arc<Mutex<Policy>>,
    policy_path: std::path::PathBuf,
    events: EventLog,
    approvals: Arc<ApprovalQueue>,
    gates: Arc<GateQueue>,
    link_port: u16,
    /// Why this process is not listening for games, when it is not.
    ///
    /// Set once, by the background thread, if the link port cannot be bound. The roster reads it so
    /// that an orchestrator no game can reach does not describe itself as one no game has reached.
    link_failure: Mutex<Option<String>>,
    /// The event log as the Sessions tab reads it: parsed once, then read on as it grows.
    flight: Arc<Mutex<LogCache>>,
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
    /// The game this endpoint belongs to — both endpoints of a singleplayer world share one.
    game: String,
    label: String,
    side: String,
    connected: bool,
    focused: bool,
    game_directory: Option<String>,
    mod_version: String,
    minecraft_version: String,
    http_endpoint: Option<String>,
    /// Which process, as opposed to which game.
    ///
    /// Two games launched from one directory share every field above, and only the one that won the
    /// port is answering. The roster is where a person notices that, so the pid belongs on the card.
    pid: Option<i64>,
    started_at: Option<String>,
    tools: usize,
    revoked: bool,
}

/// The human-readable label for an instance id, for a log line.
///
/// Falls back to the id: a log entry that is missing its name is worth more than one that is missing
/// its subject. `run-d0a639` is a directory slug plus a few bytes of entropy, and nobody reading an
/// audit trail knows which game that was.
fn label_of(state: &State<'_, AppState>, instance: &str) -> String {
    if let Some(handle) = state.registry.get(instance) {
        return handle.info().label;
    }
    state
        .store
        .lock()
        .ok()
        .and_then(|store| {
            store
                .game_of(instance)
                .and_then(|game| store.get(&game).map(|known| known.label.clone()))
        })
        .unwrap_or_else(|| instance.to_string())
}

/// The game an id from the UI belongs to.
///
/// Rows in the roster are addressed per endpoint (`atm9-3f2a1c.client`) while approval, revocation
/// and labelling are all filed per game (`atm9-3f2a1c`). Every command that touches the store has to
/// cross that seam, and doing it in one place is what stops one of them from being missed.
fn game_of(state: &State<'_, AppState>, instance: &str) -> Result<String, String> {
    if let Some(handle) = state.registry.get(instance) {
        return Ok(handle.info().approval_id);
    }
    let store = state.store.lock().map_err(|_| "the approval store is locked")?;
    store
        .game_of(instance)
        .ok_or_else(|| format!("this orchestrator has never seen an instance called {instance}"))
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
                game: info.approval_id,
                label: info.label,
                side: info.side.as_str().to_string(),
                connected: true,
                game_directory: info.game_directory,
                mod_version: info.mod_version,
                minecraft_version: info.minecraft_version,
                http_endpoint: info.endpoint_url,
                pid: info.pid,
                started_at: info.started_at,
                tools: instance.catalogue().tools.len(),
                revoked: false,
            }
        })
        .collect();

    // Known but not running, so "where did my other game go" has an answer that is not silence.
    // Matched on the game rather than the endpoint id: a running singleplayer world contributes two
    // endpoint rows under one game, and comparing endpoint ids would list that game as missing.
    let connected: Vec<String> = views.iter().map(|view| view.game.clone()).collect();
    if let Ok(store) = state.store.lock() {
        for known in store.all() {
            if connected.contains(&known.id) {
                continue;
            }
            views.push(InstanceView {
                instance: known.id.clone(),
                game: known.id.clone(),
                label: known.label.clone(),
                side: String::new(),
                connected: false,
                focused: false,
                game_directory: known.game_directory.clone(),
                mod_version: String::new(),
                minecraft_version: String::new(),
                http_endpoint: None,
                pid: None,
                started_at: None,
                tools: 0,
                revoked: known.revoked,
            });
        }
    }
    views
}

/// Why this orchestrator cannot be reached by a game, or `None` when it can.
///
/// An empty roster has two meanings that look identical: nothing has connected, and nothing *can*.
/// The second is what a headless `mcmcp-orchestrator serve` holding the link port produces — every
/// game is connected, to it — and "no game connected" is then true of this process and useless to
/// the person reading it.
/// Calls in flight and recently finished, for every instance.
#[tauri::command]
fn activity_snapshot(state: State<'_, AppState>) -> Snapshot {
    state.router.activity().snapshot_limited(None, ACTIVITY_RECENT)
}

#[derive(Serialize)]
struct InstanceDetail {
    instance: String,
    activity: Snapshot,
    /// `notifications/message` lines the game sent, oldest first.
    messages: Vec<LogLine>,
    /// How long the game thread has been stalled, when it is.
    stalled_seconds: Option<f64>,
    /// How the game ended, when it is no longer running and this orchestrator saw it go.
    last_exit: Option<Value>,
}

/// Everything the detail view shows about one endpoint, except its game log.
#[tauri::command]
fn instance_detail(state: State<'_, AppState>, instance: String) -> InstanceDetail {
    let activity = state.router.activity();
    let live = state.registry.get(&instance);
    let game = game_of(&state, &instance).ok();
    InstanceDetail {
        activity: activity.snapshot(Some(&instance)),
        messages: activity.logs(&instance),
        stalled_seconds: live
            .as_ref()
            .and_then(|handle| handle.stalled_for())
            .map(|stalled| stalled.as_secs_f64()),
        last_exit: game.and_then(|game| state.router.last_exit(&game)),
        instance,
    }
}

/// The tail of one endpoint's game log. Asked of the game, so only while it is connected.
#[tauri::command]
async fn game_log_tail(
    state: State<'_, AppState>,
    instance: String,
    lines: Option<u64>,
) -> Result<String, String> {
    state
        .router
        .game_log_tail(&instance, lines.unwrap_or(120))
        .await
        .map_err(|error| error.to_string())
}

/// Every task list, archived ones too: the panel shows them folded away rather than not at all.
#[tauri::command]
fn task_lists(state: State<'_, AppState>) -> Vec<TaskList> {
    state.router.tasks().all(true)
}

/// A person's edit to one task. Each field is optional; a person changes one thing at a time.
#[tauri::command]
fn task_update(
    state: State<'_, AppState>,
    list: String,
    task: String,
    status: Option<String>,
    note: Option<String>,
    title: Option<String>,
) -> Result<TaskList, String> {
    let status = match status.as_deref() {
        None => None,
        Some(name) => Some(Status::parse(name).ok_or_else(|| format!("'{name}' is not a status"))?),
    };
    let update = TaskUpdate {
        task,
        status,
        note,
        title,
    };
    let updated = state.router.tasks().update(&list, &[update], Authority::Human)?;
    note_task_change(&state, &updated, "edited a task");
    Ok(updated)
}

#[tauri::command]
fn task_add(state: State<'_, AppState>, list: String, title: String) -> Result<TaskList, String> {
    let updated = state.router.tasks().add(&list, &[title], Authority::Human)?;
    note_task_change(&state, &updated, "added a task");
    Ok(updated)
}

/// Moves a task up or down a list, as the person.
#[tauri::command]
fn task_move(
    state: State<'_, AppState>,
    list: String,
    task: String,
    position: usize,
) -> Result<TaskList, String> {
    let updated = state
        .router
        .tasks()
        .move_task(&list, &task, position, Authority::Human)?;
    note_task_change(&state, &updated, "reordered");
    Ok(updated)
}

#[tauri::command]
fn task_create(state: State<'_, AppState>, title: String) -> Result<TaskList, String> {
    let created = state.router.tasks().create(&title, &[], &[], Authority::Human)?;
    note_task_change(&state, &created, "created");
    Ok(created)
}

#[tauri::command]
fn task_archive(state: State<'_, AppState>, list: String, archived: bool) -> Result<TaskList, String> {
    let updated = state.router.tasks().set_archived(&list, archived)?;
    note_task_change(&state, &updated, if archived { "archived" } else { "restored" });
    Ok(updated)
}

/// Deletes a list and its file. The one task operation a model is never offered.
#[tauri::command]
fn task_delete(state: State<'_, AppState>, list: String) -> Result<(), String> {
    state.router.tasks().delete(&list, Authority::Human)?;
    state
        .events
        .note(Actor::Human, None, format!("task list '{list}': deleted"));
    Ok(())
}

fn note_task_change(state: &State<'_, AppState>, list: &TaskList, what: &str) {
    state.events.note(
        Actor::Human,
        None,
        format!("task list '{}': {what} ({})", list.id, list.progress()),
    );
}

// ----------------------------------------------------------------------------------
// Undo points
// ----------------------------------------------------------------------------------

/// A server endpoint's undo points, newest first, as `server_undo` lists them.
#[tauri::command]
async fn undo_points(state: State<'_, AppState>, instance: String) -> Result<Value, String> {
    let result = state
        .router
        .call_as_person(&instance, "server_undo", json!({ "op": "list", "limit": 50 }))
        .await
        .map_err(|error| error.to_string())?;
    Ok(result.get("structuredContent").cloned().unwrap_or(Value::Null))
}

/// Puts an undo point back, as the person: no gate, and noted in the log as theirs.
#[tauri::command]
async fn undo_restore(
    state: State<'_, AppState>,
    instance: String,
    id: String,
    force: bool,
) -> Result<Value, String> {
    let result = state
        .router
        .call_as_person(
            &instance,
            "server_undo",
            json!({ "op": "restore", "id": id, "force": force }),
        )
        .await
        .map_err(|error| error.to_string())?;
    let label = label_of(&state, &instance);
    state.events.note_about(
        Actor::Human,
        Some(instance.clone()),
        label,
        format!("restored undo point {id}{}", if force { " (forced)" } else { "" }),
    );
    Ok(result.get("structuredContent").cloned().unwrap_or(Value::Null))
}

/// An undo point's before or after map, as a data URI.
///
/// The path comes from the game, so only a PNG inside an `mcmcp-undo` folder is read: this cannot be
/// talked into returning any other file.
#[tauri::command]
fn undo_image(path: String) -> Option<String> {
    use base64::Engine;
    let path = std::path::Path::new(&path);
    let in_undo_folder = path
        .parent()
        .and_then(|parent| parent.file_name())
        .is_some_and(|name| name == "mcmcp-undo");
    if !in_undo_folder {
        return None;
    }
    let bytes = mcmcp_orchestrator_core::thumbnail::read_screenshot(path).ok()?;
    Some(format!(
        "data:image/png;base64,{}",
        base64::engine::general_purpose::STANDARD.encode(bytes)
    ))
}

// ----------------------------------------------------------------------------------
// Data
// ----------------------------------------------------------------------------------

/// Everything MCMCP keeps, here and in each connected game, with the ages set for each kind.
#[tauri::command]
async fn storage_overview(state: State<'_, AppState>) -> Result<Value, String> {
    let directory = paths::state_directory().map_err(|error| error.to_string())?;
    let archived = storage::archived_task_usage(&state.router, &directory);
    Ok(json!({
        "stateDirectory": directory.display().to_string(),
        "orchestrator": storage::usage(&directory, archived),
        "games": storage::game_usage(&state.router).await,
        "retention": storage::Retention::load(&directory).days,
    }))
}

/// Sets or clears (`days: null`) the age past which one kind is removed.
#[tauri::command]
fn set_retention(state: State<'_, AppState>, kind: String, days: Option<u32>) -> Result<(), String> {
    let directory = paths::state_directory().map_err(|error| error.to_string())?;
    let mut retention = storage::Retention::load(&directory);
    retention
        .set(&kind, days, Authority::Human)
        .map_err(|error| error.to_string())?;
    retention.save(&directory).map_err(|error| error.to_string())?;
    state.events.note(
        Actor::Human,
        None,
        match days {
            Some(days) if days > 0 => format!("set {kind} to expire after {days} days"),
            _ => format!("set {kind} to never expire"),
        },
    );
    Ok(())
}

/// Applies every age that is set, now, rather than at the next hourly pass.
#[tauri::command]
async fn clean_up_now(state: State<'_, AppState>) -> Result<Vec<String>, String> {
    let directory = paths::state_directory().map_err(|error| error.to_string())?;
    let done = storage::run_retention(&state.router, &directory).await;
    for line in &done {
        state.events.note(Actor::Human, None, format!("expired {line}"));
    }
    Ok(done)
}

// ----------------------------------------------------------------------------------
// Marks
// ----------------------------------------------------------------------------------

/// A client endpoint's marks, newest first.
#[tauri::command]
async fn marks(state: State<'_, AppState>, instance: String) -> Result<Value, String> {
    let result = state
        .router
        .call_as_person(&instance, "client_marks", json!({ "op": "list" }))
        .await
        .map_err(|error| error.to_string())?;
    Ok(result["structuredContent"]["marks"].clone())
}

/// Drops a pin at a column, as the person's own mark; the game puts it on the surface.
#[tauri::command]
async fn mark_add(
    state: State<'_, AppState>,
    instance: String,
    x: i64,
    z: i64,
    note: String,
) -> Result<Value, String> {
    let result = state
        .router
        .call_as_person(
            &instance,
            "client_marks",
            json!({ "op": "add", "x": x, "z": z, "note": note, "as_player": true }),
        )
        .await
        .map_err(|error| error.to_string())?;
    Ok(result["structuredContent"]["mark"].clone())
}

#[tauri::command]
async fn mark_clear(state: State<'_, AppState>, instance: String, id: String) -> Result<(), String> {
    state
        .router
        .call_as_person(&instance, "client_marks", json!({ "op": "clear", "id": id }))
        .await
        .map(|_| ())
        .map_err(|error| error.to_string())
}

/// A map of the player's surroundings to drop pins on: the image, and how its pixels map to blocks.
#[tauri::command]
async fn pin_map(state: State<'_, AppState>, instance: String) -> Result<Value, String> {
    let result = state
        .router
        .call_as_person(
            &instance,
            "client_render_map",
            json!({ "radius": 96, "max_dimension": 640, "inline": true }),
        )
        .await
        .map_err(|error| error.to_string())?;
    let image = result["content"]
        .as_array()
        .and_then(|blocks| {
            blocks
                .iter()
                .find(|block| block.get("type").and_then(Value::as_str) == Some("image"))
        })
        .ok_or("the map came back without an image")?;
    let mime = image["mimeType"].as_str().unwrap_or("image/png");
    let data = image["data"].as_str().unwrap_or_default();
    let structured = &result["structuredContent"];
    Ok(json!({
        "image": format!("data:{mime};base64,{data}"),
        "from": structured["from"],
        "scale": structured["scale"],
        "pixelsPerBlock": structured["pixelsPerBlock"],
        "width": structured["width"],
    }))
}

// ----------------------------------------------------------------------------------
// Flight recorder
// ----------------------------------------------------------------------------------

/// Most rows of a session handed to the window at once.
///
/// One session of 17,000 calls, drawn whole, was a 9.5 MB reply and nearly 3 GB of window. A few
/// hundred rows is more than a screen holds, and the rest are a click away.
const SESSION_PAGE: usize = 300;

/// Runs `work` on the event log off the main thread.
///
/// A synchronous command runs on the main thread, where reading the log — tens of thousands of
/// lines the first time — stalled the whole window, and the Sessions tab polls.
async fn with_log<T: Send + 'static>(
    state: &State<'_, AppState>,
    work: impl FnOnce(&[mcmcp_orchestrator_core::events::Event]) -> T + Send + 'static,
) -> Result<T, String> {
    let log = paths::event_log_path().map_err(|error| error.to_string())?;
    let cache = Arc::clone(&state.flight);
    tauri::async_runtime::spawn_blocking(move || {
        let mut cache = cache.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        work(cache.refresh(&log))
    })
    .await
    .map_err(|error| error.to_string())
}

/// Every session in the event log, newest first.
///
/// Read from the file rather than kept by the router: the log on disk outlives this process, and
/// yesterday's session is exactly the one somebody comes looking for.
#[tauri::command]
async fn flight_sessions(state: State<'_, AppState>) -> Result<Vec<SessionSummary>, String> {
    with_log(&state, flight::sessions).await
}

/// One page of a session, oldest first within it: the newest page unless `start` is given.
#[tauri::command]
async fn flight_session(
    state: State<'_, AppState>,
    id: String,
    instance: Option<String>,
    start: Option<usize>,
) -> Result<Value, String> {
    let page = with_log(&state, move |events| {
        flight::session_page(events, &id, instance.as_deref(), start, SESSION_PAGE)
    })
    .await?
    .ok_or("that session is no longer in the event log")?;
    let events: Vec<Value> = page
        .events
        .iter()
        .map(|event| {
            let mut json = serde_json::to_value(event).unwrap_or(Value::Null);
            json["summary"] = json!(event.summary());
            json
        })
        .collect();
    let mut json = serde_json::to_value(&page).map_err(|error| error.to_string())?;
    json["events"] = Value::Array(events);
    Ok(json)
}

/// A thumbnail as a data URI, or nothing if it is gone.
///
/// Only a name the router generates is accepted — digits, a dash, `.png` — so this cannot be talked
/// into reading any other file.
#[tauri::command]
async fn thumbnail(name: String) -> Option<String> {
    use base64::Engine;
    let valid = name.ends_with(".png")
        && name.len() < 64
        && name[..name.len() - 4]
            .chars()
            .all(|character| character.is_ascii_digit() || character == '-');
    if !valid {
        return None;
    }
    let path = paths::thumbnails_directory().ok()?.join(&name);
    let bytes = tauri::async_runtime::spawn_blocking(move || std::fs::read(path))
        .await
        .ok()?
        .ok()?;
    Some(format!(
        "data:image/png;base64,{}",
        base64::engine::general_purpose::STANDARD.encode(bytes)
    ))
}

/// Writes one session as a self-contained HTML page and returns where.
#[tauri::command]
async fn export_session(state: State<'_, AppState>, id: String, redact: bool) -> Result<String, String> {
    let thumbnails = paths::thumbnails_directory().map_err(|error| error.to_string())?;
    let session_id = id.clone();
    let html = with_log(&state, move |events| {
        let summary = flight::sessions(events)
            .into_iter()
            .find(|summary| summary.id == session_id)?;
        let session = flight::session_events(events, &session_id);
        Some(flight::render_report(&summary, &session, redact, |name| {
            std::fs::read(thumbnails.join(name)).ok()
        }))
    })
    .await?
    .ok_or_else(|| format!("there is no session {id} in the event log"))?;
    let directory = paths::reports_directory().map_err(|error| error.to_string())?;
    std::fs::create_dir_all(&directory).map_err(|error| error.to_string())?;
    let path = directory.join(format!(
        "mcmcp-session-{id}{}.html",
        if redact { "-redacted" } else { "" }
    ));
    std::fs::write(&path, html).map_err(|error| error.to_string())?;
    state.events.note(
        Actor::Human,
        None,
        format!("exported session {id} to {}", path.display()),
    );
    Ok(path.display().to_string())
}

#[tauri::command]
fn link_failure(state: State<'_, AppState>) -> Option<String> {
    state.link_failure.lock().ok().and_then(|failure| failure.clone())
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
            state.events.note_about(
                Actor::Human,
                Some(hello.instance_id.clone()),
                hello.label.clone(),
                "approved",
            );
        }
        ApprovalOutcome::Pending => {
            state.events.note_about(
                Actor::Human,
                Some(hello.instance_id.clone()),
                hello.label.clone(),
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
            state.events.note_about(
                Actor::Human,
                Some(hello.instance_id.clone()),
                hello.label.clone(),
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
    let label = label_of(&state, &gate.instance);
    state.events.note_about(
        Actor::Human,
        Some(gate.instance),
        label,
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
    let label = label_of(&state, &instance);
    state.events.record(
        mcmcp_orchestrator_core::events::Event::new(
            Actor::Human,
            Level::Info,
            Some(instance.clone()),
            mcmcp_orchestrator_core::events::EventKind::FocusChanged {
                from: previous,
                to: instance,
            },
        )
        .labelled(label),
    );
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
    // A label belongs to the game, not to one of its endpoints. Renaming a singleplayer world
    // through its client row while its server row kept the old name would be a bug, not a feature.
    let game = game_of(&state, &instance)?;

    let mut store = state.store.lock().map_err(|_| "the approval store is locked")?;
    if !store.set_label(&game, &label) {
        return Err(format!(
            "this orchestrator has never seen an instance called {instance}"
        ));
    }
    store.save().map_err(|error| error.to_string())?;
    drop(store);

    for handle in state.registry.all() {
        if handle.info().approval_id == game {
            handle.set_label(label.clone());
        }
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

    // Revocation is filed against the game, not one of its endpoints: withdrawing trust from a
    // singleplayer client while its integrated server stayed connected would not be a revocation.
    let game = game_of(&state, &instance)?;

    let mut store = state.store.lock().map_err(|_| "the approval store is locked")?;
    if !store.revoke(&game) {
        return Err(format!(
            "this orchestrator has never seen an instance called {instance}"
        ));
    }
    store.save().map_err(|error| error.to_string())?;
    drop(store);

    // Disconnect now rather than at its next attempt: a healthy link would never reconnect, so
    // "revoked" would mean "revoked, eventually, if it happens to drop".
    for handle in state.registry.all() {
        if handle.info().approval_id == game {
            let id = handle.id();
            handle.mark_closed();
            state.registry.remove(&id, &handle);
        }
    }
    let label = label_of(&state, &instance);
    state.events.record(
        mcmcp_orchestrator_core::events::Event::new(
            Actor::Human,
            Level::Warn,
            Some(instance),
            mcmcp_orchestrator_core::events::EventKind::InstanceRevoked,
        )
        .labelled(label),
    );
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

    state
        .events
        .note_about(Actor::Human, Some(instance), known.label, "approved");
    let _ = app.emit(CHANGED, ());
    Ok(())
}

#[derive(Serialize)]
struct Settings {
    /// The app's own version, which is the date it was built from.
    ///
    /// Worth showing rather than leaving to Add/Remove Programs: the orchestrator and the mod ship
    /// as one release and have to speak the same link protocol, so "which one am I running" is the
    /// first question when a game will not connect.
    version: String,
    strict_approval: bool,
    autostart: bool,
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
fn get_settings(app: AppHandle, state: State<'_, AppState>) -> Settings {
    let policy = state.policy.lock().expect("policy lock");
    let view = |rules: &mcmcp_orchestrator_core::policy::ClassRules| PolicyView {
        read_only: rule_name(rules.read_only),
        mutating: rule_name(rules.mutating),
        destructive: rule_name(rules.destructive),
    };
    Settings {
        version: env!("CARGO_PKG_VERSION").to_string(),
        strict_approval: state
            .store
            .lock()
            .map(|store| store.strict_approval())
            .unwrap_or(false),
        // Asked of the platform, not remembered here — see get_autostart.
        autostart: app.autolaunch().is_enabled().unwrap_or(false),
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

    let label = instance
        .as_deref()
        .map(|id| label_of(&state, id))
        .unwrap_or_default();
    state.events.note_about(
        Actor::Human,
        instance,
        label,
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

/// Whether the app is set to start when the user logs in.
///
/// Read from the platform rather than from a setting of our own — the registry Run key, a
/// LaunchAgent, or a `.desktop` entry, depending. Keeping our own copy would let the two disagree
/// the moment somebody removed the entry by hand, and the honest answer to "will this start with my
/// computer" is whatever the operating system thinks.
#[tauri::command]
fn get_autostart(app: AppHandle) -> bool {
    app.autolaunch().is_enabled().unwrap_or(false)
}

/// Turns start-at-login on or off.
///
/// **Off unless somebody ticks it.** A tool that adds itself to startup without being asked is a
/// tool people uninstall, and the shim already starts the app on demand — so this is a preference
/// for the case where you would rather the games connect and be approved while you are launching
/// them, instead of a few turns into a conversation.
#[tauri::command]
fn set_autostart(app: AppHandle, state: State<'_, AppState>, enabled: bool) -> Result<(), String> {
    let manager = app.autolaunch();
    let outcome = if enabled {
        manager.enable()
    } else {
        manager.disable()
    };
    outcome.map_err(|error| format!("could not change the login item: {error}"))?;

    state.events.note(
        Actor::Human,
        None,
        if enabled {
            "will start at login"
        } else {
            "will no longer start at login"
        },
    );
    let _ = app.emit(CHANGED, ());
    Ok(())
}

/// The MCP client entry that points at this orchestrator.
///
/// Returned for copying rather than written into anybody's configuration file. Editing an MCP
/// client's config behind its back is a destructive action on a file the user owns and may have
/// hand-tuned, and getting it wrong breaks every other server listed in it. A snippet on the
/// clipboard costs one paste and cannot corrupt anything.
///
/// Note what is absent: no port, no token, no URL. That is the point of the shim — the app can move,
/// restart, or change ports without this entry ever changing.
#[tauri::command]
fn mcp_client_config() -> Result<String, String> {
    let shim = std::env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(|directory| directory.join(shim_name())))
        .filter(|path| path.exists())
        .map(|path| path.display().to_string())
        .unwrap_or_else(|| "mcmcp-orchestrator".to_string());

    let snippet = json!({
        "mcpServers": {
            "minecraft": {
                "command": shim,
                "args": ["shim"],
            }
        }
    });
    serde_json::to_string_pretty(&snippet).map_err(|error| error.to_string())
}

fn shim_name() -> &'static str {
    if cfg!(windows) {
        "mcmcp-orchestrator.exe"
    } else {
        "mcmcp-orchestrator"
    }
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
    // Before the subscriber, because the log now lives in the state directory. Harmless where it
    // was while the only writer was stderr.
    paths::ensure_state_directory()?;

    // Tee, not replace. A desktop app has no terminal, so stderr-only logging discarded every
    // warning the orchestrator produced about its own workings. `logging` explains why neither
    // sink is allowed to fail.
    mcmcp_orchestrator_core::logging::init();

    let link_port: u16 = std::env::var("MCMCP_LINK_PORT")
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(25580);
    // The MCP side's port, overridable for the same reason as the link's: a second copy run for
    // development must not collide with the one a person is using.
    let mcp_port: u16 = std::env::var("MCMCP_MCP_PORT")
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(mcp_socket::DEFAULT_MCP_PORT);

    let store = Arc::new(Mutex::new(ApprovalStore::load(paths::approval_store_path()?)?));
    let policy_path = control::policy_path(&paths::state_directory()?);
    let policy = Arc::new(Mutex::new(control::load_policy(&policy_path)?));
    let events = EventLog::with_file(paths::event_log_path()?, EVENT_LOG_LIMIT_BYTES);
    let registry = Arc::new(Registry::new());

    let router = Arc::new(
        Router::new(Arc::clone(&registry), Arc::clone(&store))
            .with_events(events.clone())
            .with_policy(Arc::clone(&policy))
            .with_tasks(Arc::new(TaskStore::load(paths::tasks_directory()?)?))
            .with_thumbnails(paths::thumbnails_directory()?),
    );
    if let Some(cached) = catalogue::load_cache(&paths::catalogue_cache_path()?) {
        router.restore_cache(cached);
    }

    let approvals = Arc::new(PendingQueue::new());
    let gates = Arc::new(PendingQueue::new());

    let state = AppState {
        registry: Arc::clone(&registry),
        router: Arc::clone(&router),
        store: Arc::clone(&store),
        policy: Arc::clone(&policy),
        policy_path,
        events: events.clone(),
        approvals: Arc::clone(&approvals),
        gates: Arc::clone(&gates),
        link_port,
        link_failure: Mutex::new(None),
        flight: Arc::new(Mutex::new(LogCache::default())),
    };

    tauri::Builder::default()
        // First, as the plugin requires, and ahead of anything that would open a window. A second
        // copy is not hypothetical: login autostart and a shim's on-demand launch land within
        // seconds of each other, and so do two MCP clients starting their shims at once. The loser
        // could bind neither port, so every game and every shim was attached to the first copy
        // while the second sat in the tray with an empty roster — and whichever window a person
        // happened to open was as likely as not the one saying "no game connected". The second copy
        // now exits here and the first shows itself, which is what launching it again meant.
        .plugin(tauri_plugin_single_instance::init(
            |app, _arguments, _directory| {
                reveal(app);
            },
        ))
        // No arguments passed to the launched copy: started at login it should behave exactly as it
        // does when started by hand, and a flag here would be a second code path nobody exercises.
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .manage(state)
        .invoke_handler(tauri::generate_handler![
            list_instances,
            link_failure,
            activity_snapshot,
            instance_detail,
            game_log_tail,
            task_lists,
            task_update,
            task_add,
            task_move,
            task_create,
            task_archive,
            task_delete,
            undo_points,
            undo_restore,
            undo_image,
            storage_overview,
            set_retention,
            clean_up_now,
            marks,
            mark_add,
            mark_clear,
            pin_map,
            flight_sessions,
            flight_session,
            thumbnail,
            export_session,
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
            mcp_client_config,
            get_autostart,
            set_autostart,
        ])
        .setup(move |app| {
            install_tray(app.handle())?;

            let handle = app.handle().clone();
            spawn_background(
                handle, registry, store, router, events, approvals, gates, link_port, mcp_port,
            );
            Ok(())
        })
        .on_window_event(|window, event| {
            // Closing the window hides it. The orchestrator keeps running because the games are
            // still connected to it; Quit in the tray menu is what actually stops it.
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .run(tauri::generate_context!())?;
    Ok(())
}

/// Puts the orchestrator in the system tray, and makes closing the window mean "hide".
///
/// This is not decoration. The orchestrator has to keep accepting links and answering an MCP client
/// for as long as the games are running, and the window is only how a person looks at it — so
/// closing the window must not stop the thing. Without a tray icon that would be a process with no
/// way back to it and no way to stop it except the task manager, which is worse than either.
fn install_tray(app: &AppHandle) -> tauri::Result<()> {
    let show = MenuItem::with_id(app, "show", "Show orchestrator", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show, &quit])?;

    let icon = app
        .default_window_icon()
        .cloned()
        .ok_or_else(|| tauri::Error::UnknownPath)?;

    TrayIconBuilder::with_id("mcmcp")
        .icon(icon)
        .tooltip("MCMCP Orchestrator")
        .menu(&menu)
        // Left click shows the window; the menu is for everything else. Quitting from a left click
        // would be far too easy to do by accident to something several games are talking to.
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id().as_ref() {
            "show" => reveal(app),
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let tauri::tray::TrayIconEvent::Click {
                button: tauri::tray::MouseButton::Left,
                button_state: tauri::tray::MouseButtonState::Up,
                ..
            } = event
            {
                reveal(tray.app_handle());
            }
        })
        .build(app)?;
    Ok(())
}

fn reveal(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
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
    mcp_port: u16,
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
                            events.note_about(
                                Actor::System,
                                Some(summary.instance_id.clone()),
                                summary.label.clone(),
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
                            // Progress and log lines change nothing but activity, which has its own
                            // event. Treating each as "everything changed" re-read the whole window
                            // and rewrote the catalogue cache to disk many times a second during a
                            // batch.
                            let activity_only = is_activity_only(&event);
                            router.handle_upstream(event).await;
                            if activity_only {
                                continue;
                            }
                            if let Some(aggregate) = router.cached_aggregate()
                                && let Ok(path) = paths::catalogue_cache_path()
                            {
                                let _ = catalogue::save_cache(&path, &aggregate);
                            }
                            let _ = app.emit(CHANGED, ());
                        }
                    });
                }

                // Activity, coalesced: a watch keeps only the latest generation, so a burst of
                // progress while this sleeps becomes one emit rather than a queue of them.
                {
                    let activity = router.activity();
                    let mut changes = activity.subscribe();
                    let app = app.clone();
                    tokio::spawn(async move {
                        while changes.changed().await.is_ok() {
                            tokio::time::sleep(ACTIVITY_INTERVAL).await;
                            changes.borrow_and_update();
                            let snapshot = activity.snapshot_limited(None, ACTIVITY_RECENT);
                            let _ = app.emit(ACTIVITY, snapshot);
                        }
                    });
                }

                // Each game is told its current task, for the mod's in-game line.
                tokio::spawn(Arc::clone(&router).watch_tasks());

                // Whatever a person set to expire, hourly.
                if let Ok(directory) = paths::state_directory() {
                    tokio::spawn(storage::retention_loop(Arc::clone(&router), directory));
                }

                // Task lists, whoever changed them.
                {
                    let mut changes = router.tasks().subscribe();
                    let app = app.clone();
                    tokio::spawn(async move {
                        while changes.changed().await.is_ok() {
                            let _ = app.emit(TASKS, ());
                        }
                    });
                }

                // The MCP side, which a shim attaches to on an MCP client's behalf.
                {
                    let router = Arc::clone(&router);
                    let events = events.clone();
                    let app = app.clone();
                    tokio::spawn(async move {
                        let Ok(state) = paths::state_directory() else {
                            return;
                        };
                        let token = match mcp_socket::ensure_token(&state) {
                            Ok(token) => token,
                            Err(error) => {
                                warn!(%error, "could not prepare the MCP token");
                                return;
                            }
                        };
                        let address = format!("127.0.0.1:{mcp_port}");
                        match tokio::net::TcpListener::bind(&address).await {
                            Ok(listener) => {
                                if let Err(error) = mcp_socket::serve(listener, router, token).await {
                                    warn!(%error, "the MCP listener stopped");
                                }
                            }
                            Err(error) => {
                                warn!(%error, %address, "could not listen for MCP clients");
                                events.note(
                                    Actor::System,
                                    None,
                                    format!(
                                        "Could not listen for MCP clients on {address}: {error}. \
                                         Another orchestrator is probably already running."
                                    ),
                                );
                                let _ = app.emit(CHANGED, ());
                            }
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
                        let message = format!(
                            "Could not listen on {address}: {error}. Another orchestrator is \
                             probably already running."
                        );
                        if let Ok(mut failure) = app.state::<AppState>().link_failure.lock() {
                            *failure = Some(message.clone());
                        }
                        events.note(Actor::System, None, message);
                        let _ = app.emit(CHANGED, ());
                    }
                }
            });
        })
        .expect("spawning the orchestrator thread");
}

/// Whether an upstream event changes nothing but [`ACTIVITY`]: a progress or log notification.
fn is_activity_only(event: &UpstreamEvent) -> bool {
    match event {
        UpstreamEvent::Notification { message, .. } => matches!(
            jsonrpc::method_of(message),
            Some("notifications/progress" | "notifications/message")
        ),
        _ => false,
    }
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
