//! What the orchestrator and its games keep on disk, how much room it takes, and expiry by age.
//!
//! # Why this exists
//!
//! Thumbnails, reports, task lists and logs accumulate in the orchestrator's state directory, and
//! dumps, screenshots, undo points and marks in every game's folder. Nothing showed how much, and
//! the only bounds were counts and size caps chosen for you. The Data tab shows each kind's size and
//! age, and lets a person set an age past which a kind is removed. Every kind starts at "never": a
//! person chooses what goes, not this file.
//!
//! The logs are not offered for expiry: both are already capped by size and rotate themselves, and
//! the event log is what the flight recorder reads.

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use crate::control::Authority;
use crate::events::now_millis;
use crate::router::Router;

const DAY_MS: u64 = 86_400_000;

/// Orchestrator kinds a person may set an age for.
pub const THUMBNAILS: &str = "thumbnails";
pub const REPORTS: &str = "reports";
pub const ARCHIVED_TASKS: &str = "archived_tasks";

/// The game kinds, as `game_storage` names them. Stored in [`Retention`] as `game.<kind>`.
pub const GAME_KINDS: &[&str] = &["dumps", "screenshots", "undo", "marks"];

/// Ages, in days, past which each kind is removed. Absent means never.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Retention {
    #[serde(default)]
    pub days: BTreeMap<String, u32>,
}

impl Retention {
    pub fn path(state: &Path) -> PathBuf {
        state.join("storage.json")
    }

    /// Reads the settings; a missing or unreadable file is "keep everything", never an error.
    pub fn load(state: &Path) -> Self {
        std::fs::read_to_string(Self::path(state))
            .ok()
            .and_then(|text| serde_json::from_str(&text).ok())
            .unwrap_or_default()
    }

    pub fn save(&self, state: &Path) -> Result<()> {
        let path = Self::path(state);
        let temporary = path.with_extension("json.tmp");
        std::fs::write(&temporary, serde_json::to_vec_pretty(self)?)?;
        std::fs::rename(&temporary, &path).with_context(|| format!("replacing {}", path.display()))?;
        Ok(())
    }

    /// Sets or clears (`None`) one kind's age. A person's operation: it decides what is deleted.
    pub fn set(&mut self, kind: &str, days: Option<u32>, authority: Authority) -> Result<()> {
        authority.require_human("Changing what MCMCP deletes")?;
        let known = [THUMBNAILS, REPORTS, ARCHIVED_TASKS].contains(&kind)
            || kind
                .strip_prefix("game.")
                .is_some_and(|game| GAME_KINDS.contains(&game));
        anyhow::ensure!(known, "there is no kind of data called {kind}");
        match days {
            Some(days) if days > 0 => {
                self.days.insert(kind.to_string(), days);
            }
            _ => {
                self.days.remove(kind);
            }
        }
        Ok(())
    }
}

/// One kind's footprint.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct KindUsage {
    pub kind: String,
    pub label: String,
    pub bytes: u64,
    pub items: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub oldest_ms: Option<u64>,
    /// Whether a person may set an age for it.
    pub expirable: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
}

/// The orchestrator's own kinds of data in `state`.
pub fn usage(state: &Path, archived_tasks: (u64, u64, Option<u64>)) -> Vec<KindUsage> {
    let files = |folder: &Path| -> Vec<(PathBuf, u64, u64)> {
        std::fs::read_dir(folder)
            .into_iter()
            .flatten()
            .filter_map(|entry| entry.ok())
            .filter_map(|entry| {
                let meta = entry.metadata().ok()?;
                meta.is_file()
                    .then(|| (entry.path(), meta.len(), modified_ms(&meta)))
            })
            .collect()
    };
    let sum =
        |kind: &str, label: &str, found: Vec<(PathBuf, u64, u64)>, expirable: bool, note: Option<&str>| {
            KindUsage {
                kind: kind.into(),
                label: label.into(),
                bytes: found.iter().map(|(_, size, _)| size).sum(),
                items: found.len() as u64,
                oldest_ms: found.iter().map(|(_, _, at)| *at).min(),
                expirable,
                note: note.map(str::to_string),
            }
        };
    let top = files(state);
    let named = |prefix: &str| -> Vec<(PathBuf, u64, u64)> {
        top.iter()
            .filter(|(path, _, _)| {
                path.file_name()
                    .and_then(|name| name.to_str())
                    .is_some_and(|name| name.starts_with(prefix))
            })
            .cloned()
            .collect()
    };
    let settings: Vec<_> = top
        .iter()
        .filter(|(path, _, _)| {
            let name = path.file_name().and_then(|name| name.to_str()).unwrap_or("");
            !name.starts_with("events.jsonl") && !name.starts_with("orchestrator.log")
        })
        .cloned()
        .collect();
    vec![
        sum(
            "events",
            "Event log (calls, approvals, task changes)",
            named("events.jsonl"),
            false,
            Some("Kept to about 16 MB by rotation; the Sessions tab reads it"),
        ),
        sum(
            "diagnostic",
            "Diagnostic log",
            named("orchestrator.log"),
            false,
            Some("Kept to about 16 MB by rotation"),
        ),
        sum(
            THUMBNAILS,
            "Screenshot thumbnails",
            files(&state.join("thumbnails")),
            true,
            None,
        ),
        sum(
            REPORTS,
            "Exported session reports",
            files(&state.join("reports")),
            true,
            None,
        ),
        KindUsage {
            kind: ARCHIVED_TASKS.into(),
            label: "Archived task lists".into(),
            bytes: archived_tasks.0,
            items: archived_tasks.1,
            oldest_ms: archived_tasks.2,
            expirable: true,
            note: Some("Open lists are never expired".into()),
        },
        sum(
            "settings",
            "Approvals, policy and settings",
            settings,
            false,
            None,
        ),
    ]
}

/// Removes files in `folder` older than `days`, judging age by `age_of`. Returns (removed, bytes).
fn expire_files(
    folder: &Path,
    days: u32,
    now: u64,
    age_of: impl Fn(&Path, &std::fs::Metadata) -> u64,
) -> (u64, u64) {
    let cutoff = now.saturating_sub(days as u64 * DAY_MS);
    let mut removed = (0, 0);
    for entry in std::fs::read_dir(folder)
        .into_iter()
        .flatten()
        .filter_map(|entry| entry.ok())
    {
        let Ok(meta) = entry.metadata() else { continue };
        if !meta.is_file() || age_of(&entry.path(), &meta) >= cutoff {
            continue;
        }
        if std::fs::remove_file(entry.path()).is_ok() {
            removed.0 += 1;
            removed.1 += meta.len();
        }
    }
    removed
}

/// A thumbnail's age from its name (`<ms>-<n>.png`), else its file time.
fn thumbnail_age(path: &Path, meta: &std::fs::Metadata) -> u64 {
    path.file_name()
        .and_then(|name| name.to_str())
        .and_then(|name| name.split('-').next())
        .and_then(|ms| ms.parse().ok())
        .unwrap_or_else(|| modified_ms(meta))
}

fn modified_ms(meta: &std::fs::Metadata) -> u64 {
    meta.modified()
        .ok()
        .and_then(|time| time.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|age| age.as_millis() as u64)
        .unwrap_or(0)
}

/// Applies one orchestrator kind's age now. Returns (removed, bytes).
pub fn expire_orchestrator(router: &Router, state: &Path, kind: &str, days: u32, now: u64) -> (u64, u64) {
    match kind {
        THUMBNAILS => expire_files(&state.join("thumbnails"), days, now, thumbnail_age),
        REPORTS => expire_files(&state.join("reports"), days, now, |_, meta| modified_ms(meta)),
        ARCHIVED_TASKS => {
            let cutoff = now.saturating_sub(days as u64 * DAY_MS);
            let store = router.tasks();
            let mut removed = (0, 0);
            for list in store.all(true) {
                if list.archived
                    && list.updated_ms < cutoff
                    && store.delete(&list.id, Authority::Human).is_ok()
                {
                    removed.0 += 1;
                }
            }
            removed
        }
        _ => (0, 0),
    }
}

/// Archived task lists' (bytes, count, oldest) for [`usage`].
pub fn archived_task_usage(router: &Router, state: &Path) -> (u64, u64, Option<u64>) {
    let archived: Vec<_> = router
        .tasks()
        .all(true)
        .into_iter()
        .filter(|list| list.archived)
        .collect();
    let bytes = archived
        .iter()
        .filter_map(|list| std::fs::metadata(state.join("tasks").join(format!("{}.json", list.id))).ok())
        .map(|meta| meta.len())
        .sum();
    (
        bytes,
        archived.len() as u64,
        archived.iter().map(|list| list.updated_ms).min(),
    )
}

/// One endpoint per connected game, preferring the client: both endpoints of a singleplayer game
/// share its folder, and asking both would count it twice.
pub fn one_endpoint_per_game(router: &Router) -> Vec<(String, String, String)> {
    let mut chosen: BTreeMap<String, (String, String, bool)> = BTreeMap::new();
    for instance in router.registry().all() {
        let info = instance.info();
        if !instance.catalogue().has_tool("game_storage") {
            continue;
        }
        let is_client = info.side.as_str() == "client";
        let keep = match chosen.get(&info.approval_id) {
            Some((_, _, existing_client)) => is_client && !existing_client,
            None => true,
        };
        if keep {
            chosen.insert(
                info.approval_id.clone(),
                (info.id.clone(), info.label.clone(), is_client),
            );
        }
    }
    chosen
        .into_iter()
        .map(|(game, (endpoint, label, _))| (game, endpoint, label))
        .collect()
}

/// Each connected game's MCMCP data, as `game_storage usage` reports it.
pub async fn game_usage(router: &Router) -> Vec<Value> {
    let mut games = Vec::new();
    for (game, endpoint, label) in one_endpoint_per_game(router) {
        let answer = router
            .call_as_person(&endpoint, "game_storage", json!({ "op": "usage" }))
            .await;
        games.push(match answer {
            Ok(result) => json!({
                "game": game, "instance": endpoint, "label": label,
                "directory": result["structuredContent"]["gameDirectory"],
                "kinds": result["structuredContent"]["kinds"],
            }),
            Err(error) => {
                json!({ "game": game, "instance": endpoint, "label": label, "error": error.to_string() })
            }
        });
    }
    games
}

/// Applies every age that is set, to the orchestrator and to each connected game. Returns a line per
/// kind that removed something, for the log and the app.
pub async fn run_retention(router: &Router, state: &Path) -> Vec<String> {
    let retention = Retention::load(state);
    let now = now_millis();
    let mut done = Vec::new();
    for (kind, days) in &retention.days {
        if kind.starts_with("game.") {
            continue;
        }
        let (removed, bytes) = expire_orchestrator(router, state, kind, *days, now);
        if removed > 0 {
            done.push(format!(
                "{kind}: removed {removed} ({bytes} bytes) older than {days} days"
            ));
        }
    }
    let game_kinds: BTreeSet<(&str, u32)> = retention
        .days
        .iter()
        .filter_map(|(kind, days)| kind.strip_prefix("game.").map(|game| (game, *days)))
        .collect();
    if !game_kinds.is_empty() {
        for (_, endpoint, label) in one_endpoint_per_game(router) {
            for (kind, days) in &game_kinds {
                let answer = router
                    .call_as_person(
                        &endpoint,
                        "game_storage",
                        json!({ "op": "expire", "kind": kind, "older_than_days": days }),
                    )
                    .await;
                if let Ok(result) = answer {
                    let removed = result["structuredContent"]["removed"].as_u64().unwrap_or(0);
                    if removed > 0 {
                        done.push(format!(
                            "{label}: {kind}: removed {removed} older than {days} days"
                        ));
                    }
                }
            }
        }
    }
    done
}

/// Runs [`run_retention`] an hour apart for as long as the process lives, noting what it removed.
pub async fn retention_loop(router: Arc<Router>, state: PathBuf) {
    tokio::time::sleep(std::time::Duration::from_secs(60)).await;
    loop {
        for line in run_retention(&router, &state).await {
            router
                .events()
                .note(crate::events::Actor::System, None, format!("expired {line}"));
        }
        tokio::time::sleep(std::time::Duration::from_secs(3600)).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nothing_expires_until_a_person_sets_it() {
        assert!(Retention::default().days.is_empty());
        let mut retention = Retention::default();
        assert!(retention.set(THUMBNAILS, Some(30), Authority::Model).is_err());
        retention.set(THUMBNAILS, Some(30), Authority::Human).unwrap();
        retention.set("game.undo", Some(7), Authority::Human).unwrap();
        assert!(
            retention
                .set("game.everything", Some(7), Authority::Human)
                .is_err()
        );
        retention.set(THUMBNAILS, None, Authority::Human).unwrap();
        assert_eq!(retention.days.keys().collect::<Vec<_>>(), vec!["game.undo"]);
    }

    #[test]
    fn settings_survive_a_restart_and_a_damaged_file_means_keep_everything() {
        let state = std::env::temp_dir().join(format!("mcmcp-storage-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&state);
        std::fs::create_dir_all(&state).unwrap();
        let mut retention = Retention::default();
        retention.set(REPORTS, Some(14), Authority::Human).unwrap();
        retention.save(&state).unwrap();
        assert_eq!(Retention::load(&state), retention);
        std::fs::write(Retention::path(&state), "{ broken").unwrap();
        assert_eq!(Retention::load(&state), Retention::default());
        let _ = std::fs::remove_dir_all(&state);
    }

    #[test]
    fn old_thumbnails_go_by_the_time_in_their_names() {
        let folder = std::env::temp_dir().join(format!("mcmcp-storage-thumbs-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&folder);
        std::fs::create_dir_all(&folder).unwrap();
        let now = 1_800_000_000_000u64;
        std::fs::write(folder.join(format!("{}-1.png", now - 10 * DAY_MS)), b"old").unwrap();
        std::fs::write(folder.join(format!("{}-2.png", now - DAY_MS)), b"new").unwrap();
        assert_eq!(expire_files(&folder, 5, now, thumbnail_age), (1, 3));
        assert_eq!(std::fs::read_dir(&folder).unwrap().count(), 1);
        let _ = std::fs::remove_dir_all(&folder);
    }

    #[test]
    fn usage_reports_each_kind_and_which_may_expire() {
        let state = std::env::temp_dir().join(format!("mcmcp-storage-usage-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&state);
        std::fs::create_dir_all(state.join("reports")).unwrap();
        std::fs::write(state.join("events.jsonl"), vec![0u8; 100]).unwrap();
        std::fs::write(state.join("events.jsonl.1"), vec![0u8; 50]).unwrap();
        std::fs::write(state.join("policy.json"), b"{}").unwrap();
        std::fs::write(state.join("reports").join("a.html"), vec![0u8; 7]).unwrap();
        let rows = usage(&state, (0, 0, None));
        let row = |kind: &str| rows.iter().find(|row| row.kind == kind).unwrap().clone();
        assert_eq!((row("events").bytes, row("events").expirable), (150, false));
        assert_eq!(
            (row(REPORTS).bytes, row(REPORTS).items, row(REPORTS).expirable),
            (7, 1, true)
        );
        assert_eq!(row("settings").bytes, 2);
        let _ = std::fs::remove_dir_all(&state);
    }
}
