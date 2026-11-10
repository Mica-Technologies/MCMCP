//! Which instances are connected right now, and which one is in focus.
//!
//! # Focus, and the failure it exists to prevent
//!
//! A model addresses an instance by name, but making it name one on every call is a lot of ceremony
//! for a session spent working in a single game. So focus is sticky: set it once, and calls that
//! omit `instance` go there.
//!
//! That has exactly one dangerous failure mode, and it is worth stating plainly because everything
//! about how focus is reported follows from it: **a human changes focus in the app, the model does
//! not know, and its next `client_move` drives the wrong game.** Silently. The mitigations live
//! elsewhere — every tool result echoes the instance it acted on, and a focus change emits a
//! notification — but they exist because of this.
//!
//! When exactly one instance is connected, it is implicitly in focus. Requiring a choice between one
//! option is ceremony with no purchase.

use std::collections::HashMap;
use std::sync::{Arc, RwLock};

use crate::instance::Instance;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FocusResolution {
    /// The call named an instance, or focus resolved to exactly one.
    Resolved(String),
    /// Nothing is connected.
    NoInstances,
    /// More than one is connected and no focus is set. The caller must choose.
    Ambiguous(Vec<String>),
    /// A name was given that is not connected.
    Unknown(String),
}

#[derive(Default)]
pub struct Registry {
    instances: RwLock<HashMap<String, Arc<Instance>>>,
    focus: RwLock<Option<String>>,
    /// Links held open while a human decides whether to approve them, by `<instance>.<side>`.
    pending: RwLock<HashMap<String, PendingApproval>>,
    /// Games refused because another folder's game holds their instance id, by `<instance>.<side>`.
    conflicts: RwLock<HashMap<String, IdentityConflict>>,
}

/// A game refused because its instance id is already connected from another folder (#188).
///
/// Copying a game folder copies `config/mcmcp.cfg`, id and secret included, so the copy is the same
/// instance as far as a handshake can tell. Registered, it replaced the original in the roster, and
/// its client was shown as the other half of the original's server: a roster that looked right and
/// was wrong, which is the dangerous kind. So the newcomer is refused, and listed here with both
/// folders so a person or a model can see what to fix.
#[derive(Debug, Clone)]
pub struct IdentityConflict {
    pub instance_id: String,
    pub side: &'static str,
    pub label: String,
    /// The refused game's folder.
    pub game_directory: Option<String>,
    pub pid: Option<i64>,
    /// The folder of the game that holds the id.
    pub connected_directory: String,
    pub seen: std::time::Instant,
}

/// How long a refused game stays listed. It is not retrying (the refusal is final until its config
/// is fixed and it is restarted), so this is how long the explanation outlives the knock.
const CONFLICT_LINGER: std::time::Duration = std::time::Duration::from_secs(30 * 60);

/// Whether two game directories are the same folder, as far as their spelling can say: separators
/// and a trailing one aside, and letter case aside on Windows, where paths ignore it.
pub fn same_directory(a: &str, b: &str) -> bool {
    fn normal(path: &str) -> String {
        let unified = path.replace('\\', "/");
        let trimmed = unified.trim_end_matches('/');
        if cfg!(windows) {
            trimmed.to_lowercase()
        } else {
            trimmed.to_string()
        }
    }
    normal(a) == normal(b)
}

/// A link that reached the orchestrator and is waiting on a human (#108).
///
/// Not an instance: it has no tools and cannot be addressed. It is listed so that a model asked to
/// work in a game that "is running but not there" can tell the person exactly what to approve,
/// rather than concluding the game is not connected at all.
#[derive(Debug, Clone)]
pub struct PendingApproval {
    pub instance_id: String,
    pub label: String,
    pub side: &'static str,
    pub game_directory: Option<String>,
    pub pid: Option<i64>,
    /// When it first asked. Kept across its retries, so the wait reads as one wait.
    pub since: std::time::Instant,
    /// When a question for it was last open: now, while one is.
    pub seen: std::time::Instant,
    pub open: bool,
}

/// How long a link told "pending" stays listed after its knock. A game retries about once a second,
/// so this outlives the gap between knocks; the headless orchestrator answers "pending" at once, and
/// without it the entry would never be there to see.
const PENDING_LINGER: std::time::Duration = std::time::Duration::from_secs(15);

/// Ends its pending entry when dropped, however the wait ends: a `finally` block the compiler
/// guarantees runs. Removed when the question was settled (approved or refused); left to linger
/// for a moment when the answer was "still pending", since the game will knock again.
pub struct PendingGuard {
    registry: Arc<Registry>,
    key: String,
    pub linger: bool,
}

impl Drop for PendingGuard {
    fn drop(&mut self) {
        if let Ok(mut pending) = self.registry.pending.write() {
            if self.linger {
                if let Some(entry) = pending.get_mut(&self.key) {
                    entry.open = false;
                    entry.seen = std::time::Instant::now();
                }
            } else {
                pending.remove(&self.key);
            }
        }
    }
}

impl Registry {
    pub fn new() -> Self {
        Self::default()
    }

    /// Adds a connected instance, replacing any earlier connection under the same id.
    ///
    /// Replacement is the common case, not an edge one: relaunching a game client is what a mod
    /// developer does all day, and the new link routinely arrives before the old socket has finished
    /// dying. The displaced instance is marked closed so anything still waiting on it fails now
    /// rather than at a timeout.
    pub fn insert(&self, instance: Arc<Instance>) -> Option<Arc<Instance>> {
        let id = instance.id();
        let displaced = self
            .instances
            .write()
            .expect("registry lock")
            .insert(id.clone(), instance);
        if let Some(old) = &displaced {
            old.mark_closed();
        }

        // First instance to connect takes focus. Nothing else would, and a model whose very first
        // call needs a focus it was never given has to spend a turn discovering that.
        let mut focus = self.focus.write().expect("focus lock");
        if focus.is_none() {
            *focus = Some(id);
        }
        displaced
    }

    /// Removes an instance, but only if it is still the one under that id.
    ///
    /// The guard matters during a fast relaunch: the old link's cleanup can run *after* the new link
    /// has registered, and an unguarded remove would delete the live connection.
    pub fn remove(&self, id: &str, only_if: &Arc<Instance>) -> bool {
        let mut instances = self.instances.write().expect("registry lock");
        match instances.get(id) {
            Some(current) if Arc::ptr_eq(current, only_if) => {
                instances.remove(id);
            }
            _ => return false,
        }
        drop(instances);

        // Focus follows: pointing at something gone would make every unqualified call fail with
        // "unknown instance" until somebody noticed.
        let mut focus = self.focus.write().expect("focus lock");
        if focus.as_deref() == Some(id) {
            *focus = self
                .instances
                .read()
                .expect("registry lock")
                .keys()
                .next()
                .cloned();
        }
        true
    }

    pub fn get(&self, id: &str) -> Option<Arc<Instance>> {
        self.instances.read().expect("registry lock").get(id).cloned()
    }

    pub fn all(&self) -> Vec<Arc<Instance>> {
        let mut instances: Vec<_> = self
            .instances
            .read()
            .expect("registry lock")
            .values()
            .cloned()
            .collect();
        // Sorted so `mcmcp_instances` and the tool schema's `instance` enum are stable between
        // calls. An order that shuffles per call makes for a needlessly noisy tool catalogue.
        instances.sort_by_key(|instance| instance.id());
        instances
    }

    pub fn ids(&self) -> Vec<String> {
        let mut ids: Vec<_> = self
            .instances
            .read()
            .expect("registry lock")
            .keys()
            .cloned()
            .collect();
        ids.sort();
        ids
    }

    pub fn count(&self) -> usize {
        self.instances.read().expect("registry lock").len()
    }

    pub fn focus(&self) -> Option<String> {
        self.focus.read().expect("focus lock").clone()
    }

    /// Sets focus, refusing an instance that is not connected.
    pub fn set_focus(&self, id: &str) -> bool {
        if self.get(id).is_none() {
            return false;
        }
        *self.focus.write().expect("focus lock") = Some(id.to_string());
        true
    }

    /// Works out which instance a call is for.
    pub fn resolve(&self, requested: Option<&str>) -> FocusResolution {
        if let Some(name) = requested {
            return match self.get(name) {
                Some(_) => FocusResolution::Resolved(name.to_string()),
                None => FocusResolution::Unknown(name.to_string()),
            };
        }

        let ids = self.ids();
        match ids.len() {
            0 => FocusResolution::NoInstances,
            // Exactly one connected: it is implicitly in focus, whatever focus says. Requiring a
            // choice between one option is ceremony with no purchase.
            1 => FocusResolution::Resolved(ids.into_iter().next().expect("one id")),
            _ => match self.focus() {
                Some(focused) if self.get(&focused).is_some() => FocusResolution::Resolved(focused),
                _ => FocusResolution::Ambiguous(ids),
            },
        }
    }

    /// Records a link waiting for approval until the returned guard is dropped.
    pub fn add_pending(self: &Arc<Self>, mut approval: PendingApproval) -> PendingGuard {
        let key = format!(
            "{}{}{}",
            approval.instance_id,
            crate::instance::ENDPOINT_SEPARATOR,
            approval.side
        );
        let mut pending = self.pending.write().expect("pending lock");
        if let Some(earlier) = pending.get(&key) {
            if earlier.seen.elapsed() < PENDING_LINGER || earlier.open {
                approval.since = earlier.since;
            }
        }
        approval.open = true;
        approval.seen = std::time::Instant::now();
        pending.insert(key.clone(), approval);
        PendingGuard {
            registry: Arc::clone(self),
            key,
            linger: true,
        }
    }

    /// The folder a live game holds `instance_id` from, when that is not `directory`: the sign that
    /// the game now knocking is a copy of it (#188). Either side of that game counts, since a copy's
    /// client beside the original's server is the same clash. `None` when nothing holds the id, when
    /// it is held from the same folder (a relaunch, which replaces the old link as it always has),
    /// or when either folder is unknown and so nothing can be said.
    pub fn conflicting_directory(&self, instance_id: &str, directory: Option<&str>) -> Option<String> {
        let directory = directory?;
        self.instances
            .read()
            .expect("registry lock")
            .values()
            .filter(|instance| instance.is_alive())
            .map(|instance| instance.info())
            .filter(|info| info.approval_id == instance_id)
            .filter_map(|info| info.game_directory)
            .find(|held| !same_directory(held, directory))
    }

    /// Remembers a game refused for holding someone else's instance id, to list it.
    pub fn note_conflict(&self, conflict: IdentityConflict) {
        let key = format!(
            "{}{}{}",
            conflict.instance_id,
            crate::instance::ENDPOINT_SEPARATOR,
            conflict.side
        );
        self.conflicts
            .write()
            .expect("conflicts lock")
            .insert(key, conflict);
    }

    /// The games refused for an instance id another folder's game holds, oldest first. One leaves
    /// the list after [`CONFLICT_LINGER`], or as soon as a game is connected from its folder: fixed
    /// and restarted it comes back under an id of its own, and this entry is history.
    pub fn conflicts(&self) -> Vec<IdentityConflict> {
        let connected_from: Vec<String> = self
            .all()
            .iter()
            .filter(|instance| instance.is_alive())
            .filter_map(|instance| instance.info().game_directory)
            .collect();
        let mut map = self.conflicts.write().expect("conflicts lock");
        map.retain(|_, conflict| {
            let back = conflict
                .game_directory
                .as_deref()
                .is_some_and(|refused| connected_from.iter().any(|held| same_directory(held, refused)));
            !back && conflict.seen.elapsed() < CONFLICT_LINGER
        });
        let mut all: Vec<IdentityConflict> = map.values().cloned().collect();
        all.sort_by_key(|conflict| conflict.seen);
        all
    }

    /// The links waiting for approval, oldest first.
    pub fn pending(&self) -> Vec<PendingApproval> {
        let connected: Vec<String> = self.ids();
        let mut map = self.pending.write().expect("pending lock");
        // A game approved since its last knock connects without asking again, so it leaves here by
        // appearing among the connected rather than through its guard.
        map.retain(|key, approval| {
            !connected.contains(key) && (approval.open || approval.seen.elapsed() < PENDING_LINGER)
        });
        let mut all: Vec<PendingApproval> = map.values().cloned().collect();
        all.sort_by_key(|approval| approval.since);
        all
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::instance::InstanceInfo;
    use crate::link::protocol::Side;
    use tokio::sync::mpsc;

    fn instance(id: &str) -> Arc<Instance> {
        let (sender, receiver) = mpsc::channel(4);
        // Kept alive so the channel does not close and make the instance look dead.
        Box::leak(Box::new(receiver));
        Arc::new(Instance::new(
            InstanceInfo {
                id: id.into(),
                approval_id: id.into(),
                label: id.into(),
                side: Side::Client,
                game_directory: None,
                mod_version: "test".into(),
                minecraft_version: "1.12.2".into(),
                endpoint_url: None,
                pid: None,
                started_at: None,
                via: None,
            },
            sender,
        ))
    }

    #[test]
    fn the_first_instance_to_connect_takes_focus() {
        // Otherwise nothing would, and a model whose first call needs a focus it was never given
        // has to spend a turn discovering that.
        let registry = Registry::new();
        registry.insert(instance("alpha"));

        assert_eq!(registry.focus().as_deref(), Some("alpha"));
    }

    #[test]
    fn a_second_instance_does_not_steal_focus() {
        let registry = Registry::new();
        registry.insert(instance("alpha"));
        registry.insert(instance("beta"));

        assert_eq!(registry.focus().as_deref(), Some("alpha"));
    }

    #[test]
    fn one_connected_instance_resolves_without_focus_or_a_name() {
        let registry = Registry::new();
        registry.insert(instance("alpha"));

        assert_eq!(registry.resolve(None), FocusResolution::Resolved("alpha".into()));
    }

    #[test]
    fn two_instances_with_no_focus_are_ambiguous_rather_than_guessed() {
        // Guessing is the one thing that must not happen: the wrong guess silently drives the wrong
        // game, and the caller has no way to tell.
        let registry = Registry::new();
        registry.insert(instance("alpha"));
        registry.insert(instance("beta"));
        *registry.focus.write().unwrap() = None;

        assert_eq!(
            registry.resolve(None),
            FocusResolution::Ambiguous(vec!["alpha".into(), "beta".into()])
        );
    }

    #[test]
    fn an_explicit_name_beats_focus() {
        let registry = Registry::new();
        registry.insert(instance("alpha"));
        registry.insert(instance("beta"));

        assert_eq!(
            registry.resolve(Some("beta")),
            FocusResolution::Resolved("beta".into())
        );
    }

    #[test]
    fn a_name_that_is_not_connected_is_reported_rather_than_falling_back_to_focus() {
        // Falling back would run the call somewhere the caller did not ask for. A typo would then
        // move the wrong player, and the result would look like it worked.
        let registry = Registry::new();
        registry.insert(instance("alpha"));

        assert_eq!(
            registry.resolve(Some("typo")),
            FocusResolution::Unknown("typo".into())
        );
    }

    #[test]
    fn nothing_connected_is_its_own_answer() {
        assert_eq!(Registry::new().resolve(None), FocusResolution::NoInstances);
    }

    #[test]
    fn the_two_endpoints_of_one_game_coexist_instead_of_displacing_each_other() {
        // The singleplayer bug in one test. Both sides dial in with the same instanceId, so keying
        // on it meant the integrated server — which links at FMLServerStartingEvent, long after the
        // client links at postInit — replaced the client and marked it closed. The game showed up
        // as a server with only the server tool set.
        let registry = Registry::new();
        let client = instance("modb-dev.client");
        let server = instance("modb-dev.server");

        registry.insert(Arc::clone(&client));
        assert!(
            registry.insert(Arc::clone(&server)).is_none(),
            "nothing is displaced"
        );

        assert_eq!(registry.count(), 2);
        assert!(
            client.is_alive(),
            "the client link must survive the server linking"
        );
        assert_eq!(registry.focus().as_deref(), Some("modb-dev.client"));
    }

    #[test]
    fn reconnecting_replaces_the_old_link_and_closes_it() {
        // Relaunching a client is constant in a mod-development loop, and the new link routinely
        // arrives before the old socket has finished dying.
        let registry = Registry::new();
        let first = instance("alpha");
        registry.insert(Arc::clone(&first));
        let second = instance("alpha");

        let displaced = registry
            .insert(Arc::clone(&second))
            .expect("the old link is displaced");

        assert!(Arc::ptr_eq(&displaced, &first));
        assert!(
            !first.is_alive(),
            "the displaced link must be closed, not left waiting"
        );
        assert_eq!(registry.count(), 1);
    }

    #[test]
    fn a_late_cleanup_cannot_remove_the_link_that_replaced_it() {
        // The old link's cleanup can run after the new one registered. An unguarded remove would
        // delete the live connection and leave the instance invisible until it reconnected again.
        let registry = Registry::new();
        let first = instance("alpha");
        registry.insert(Arc::clone(&first));
        let second = instance("alpha");
        registry.insert(Arc::clone(&second));

        assert!(!registry.remove("alpha", &first), "the stale handle must not win");
        assert_eq!(registry.count(), 1);
        assert!(registry.remove("alpha", &second));
        assert_eq!(registry.count(), 0);
    }

    #[test]
    fn focus_moves_on_when_the_focused_instance_disconnects() {
        let registry = Registry::new();
        let alpha = instance("alpha");
        registry.insert(Arc::clone(&alpha));
        registry.insert(instance("beta"));
        assert_eq!(registry.focus().as_deref(), Some("alpha"));

        registry.remove("alpha", &alpha);

        assert_eq!(registry.focus().as_deref(), Some("beta"));
    }

    #[test]
    fn focus_cannot_be_set_to_something_that_is_not_connected() {
        let registry = Registry::new();
        registry.insert(instance("alpha"));

        assert!(!registry.set_focus("ghost"));
        assert_eq!(registry.focus().as_deref(), Some("alpha"));
    }

    #[test]
    fn lists_instances_in_a_stable_order() {
        // The `instance` enum in every tool schema is built from this. An order that shuffles per
        // call would churn the tool catalogue for no reason.
        let registry = Registry::new();
        registry.insert(instance("zulu"));
        registry.insert(instance("alpha"));

        assert_eq!(registry.ids(), vec!["alpha".to_string(), "zulu".to_string()]);
    }

    fn instance_in(id: &str, game: &str, directory: Option<&str>) -> Arc<Instance> {
        let (sender, receiver) = mpsc::channel(4);
        Box::leak(Box::new(receiver));
        Arc::new(Instance::new(
            InstanceInfo {
                id: id.into(),
                approval_id: game.into(),
                label: game.into(),
                side: Side::Server,
                game_directory: directory.map(str::to_string),
                mod_version: "test".into(),
                minecraft_version: "1.12.2".into(),
                endpoint_url: None,
                pid: None,
                started_at: None,
                via: None,
            },
            sender,
        ))
    }

    #[test]
    fn a_copied_game_folder_is_told_apart_from_a_relaunch_by_where_it_runs_from() {
        let registry = Registry::new();
        registry.insert(instance_in(
            "run-85bcdf.server",
            "run-85bcdf",
            Some("E:\\dev\\run"),
        ));

        // The copy's client, beside the original's server: a different side of the same id.
        assert_eq!(
            registry
                .conflicting_directory("run-85bcdf", Some("E:\\dev\\run\\players\\bob"))
                .as_deref(),
            Some("E:\\dev\\run")
        );
        // The same game relaunched, however its folder is spelled, replaces its old link as before.
        assert_eq!(
            registry.conflicting_directory("run-85bcdf", Some("E:/dev/run/")),
            None
        );
        // Another game entirely, and a hello too old to say where it runs from.
        assert_eq!(
            registry.conflicting_directory("alice-f12b5a", Some("E:\\dev\\alice")),
            None
        );
        assert_eq!(registry.conflicting_directory("run-85bcdf", None), None);
    }

    #[test]
    fn a_game_that_has_gone_no_longer_holds_its_id_against_a_copy() {
        let registry = Registry::new();
        let original = instance_in("run-85bcdf.server", "run-85bcdf", Some("E:/dev/run"));
        registry.insert(Arc::clone(&original));
        original.mark_closed();
        assert_eq!(
            registry.conflicting_directory("run-85bcdf", Some("E:/dev/copy")),
            None
        );
    }

    #[test]
    fn a_refused_copy_is_listed_with_both_folders() {
        let registry = Registry::new();
        registry.note_conflict(IdentityConflict {
            instance_id: "run-85bcdf".into(),
            side: "client",
            label: "run".into(),
            game_directory: Some("E:/dev/run/players/bob".into()),
            pid: Some(7),
            connected_directory: "E:/dev/run".into(),
            seen: std::time::Instant::now(),
        });
        // Knocking again replaces its entry rather than adding one.
        registry.note_conflict(IdentityConflict {
            instance_id: "run-85bcdf".into(),
            side: "client",
            label: "run".into(),
            game_directory: Some("E:/dev/run/players/bob".into()),
            pid: Some(8),
            connected_directory: "E:/dev/run".into(),
            seen: std::time::Instant::now(),
        });
        let listed = registry.conflicts();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].pid, Some(8));
        assert_eq!(listed[0].connected_directory, "E:/dev/run");
        assert!(registry.ids().is_empty(), "refused is not connected");

        // Its config fixed, it comes back from the same folder as a game of its own.
        registry.insert(instance_in(
            "bob-e9e031.client",
            "bob-e9e031",
            Some("E:\\dev\\run\\players\\bob"),
        ));
        assert!(registry.conflicts().is_empty());
    }

    #[test]
    fn folders_are_compared_by_where_they_are_not_how_they_are_written() {
        assert!(same_directory("E:\\dev\\run", "E:/dev/run/"));
        assert!(!same_directory("E:/dev/run", "E:/dev/run/players/bob"));
        assert_eq!(same_directory("E:/Dev/Run", "e:/dev/run"), cfg!(windows));
    }

    fn waiting(id: &str) -> PendingApproval {
        let now = std::time::Instant::now();
        PendingApproval {
            instance_id: id.into(),
            label: "UIA Server Utility Mod".into(),
            side: "client",
            game_directory: Some("E:/run".into()),
            pid: Some(42),
            since: now,
            seen: now,
            open: true,
        }
    }

    #[test]
    fn a_link_waiting_for_approval_is_listed_until_its_question_is_settled() {
        let registry = Arc::new(Registry::new());
        let mut guard = registry.add_pending(waiting("run-ccd2f3"));
        let pending = registry.pending();
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].instance_id, "run-ccd2f3");
        // Pending is not connected: nothing can be routed to it.
        assert!(registry.ids().is_empty());
        guard.linger = false;
        drop(guard);
        assert!(registry.pending().is_empty());
    }

    #[test]
    fn a_link_told_pending_stays_listed_between_knocks_and_keeps_its_first_time() {
        let registry = Arc::new(Registry::new());
        let first = registry.add_pending(waiting("run-ccd2f3"));
        let since = registry.pending()[0].since;
        drop(first);
        let listed = registry.pending();
        assert_eq!(
            listed.len(),
            1,
            "a refused-as-pending knock lingers until the next one"
        );
        assert!(!listed[0].open);
        let again = registry.add_pending(waiting("run-ccd2f3"));
        assert_eq!(registry.pending()[0].since, since);
        drop(again);
        registry.insert(instance("run-ccd2f3.client"));
        assert!(
            registry.pending().is_empty(),
            "once it is connected it is no longer waiting"
        );
    }
}
