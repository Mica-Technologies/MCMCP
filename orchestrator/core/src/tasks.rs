//! Task lists an agent keeps while it works, and a person can watch and edit.
//!
//! # Why this exists
//!
//! A model working through a long job in a game — survey a corridor, build forty signals, test each
//! one — has a plan, and nobody watching can see it. The person sees one tool call after another and
//! has to infer from them what the model believes it is doing and how far through it is. A task list
//! puts the plan where both can see it: the model keeps it with `mcmcp_tasks`, and the app shows it
//! beside the games, live.
//!
//! # Why here, and on disk
//!
//! In the orchestrator rather than the mod, because one job often spans games — the client that
//! does the work and the server that checks it — and a list belongs to the job, not to a world. On
//! disk, one JSON file per list in the state directory, because a long job outlives a session: a
//! model resuming tomorrow, or after a context reset, reads the list back and carries on.
//!
//! # Who may do what
//!
//! A model may create lists, add and update tasks, and archive a list it has finished, which hides
//! it but keeps the file. Deleting a list outright is a person's decision, from the app: a model
//! that can delete the record of its own work can also erase the evidence of what it did not finish.

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use crate::control::Authority;
use crate::events::now_millis;

/// Most tasks one list may hold. A plan longer than this is several plans.
pub const MAX_TASKS: usize = 200;

/// Most characters in a title, a task or a note. Long enough for a sentence, short enough that a
/// list stays readable in a reply a model pays for.
pub const MAX_TITLE_CHARS: usize = 200;
pub const MAX_NOTE_CHARS: usize = 1000;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Status {
    Todo,
    Doing,
    Done,
    Blocked,
    Skipped,
}

impl Status {
    pub const NAMES: &'static [&'static str] = &["todo", "doing", "done", "blocked", "skipped"];

    pub fn parse(name: &str) -> Option<Self> {
        match name.trim().to_ascii_lowercase().as_str() {
            "todo" | "to_do" | "pending" => Some(Self::Todo),
            "doing" | "in_progress" | "active" => Some(Self::Doing),
            "done" | "complete" | "completed" => Some(Self::Done),
            "blocked" => Some(Self::Blocked),
            "skipped" | "skip" => Some(Self::Skipped),
            _ => None,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Self::Todo => "todo",
            Self::Doing => "doing",
            Self::Done => "done",
            Self::Blocked => "blocked",
            Self::Skipped => "skipped",
        }
    }

    /// Whether the task needs no more work: done, or deliberately not done.
    pub fn is_settled(self) -> bool {
        matches!(self, Self::Done | Self::Skipped)
    }
}

/// Who last changed something, for a person reading the list.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Editor {
    Model,
    Human,
}

impl From<Authority> for Editor {
    fn from(authority: Authority) -> Self {
        match authority {
            Authority::Human => Self::Human,
            Authority::Model => Self::Model,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Task {
    /// Short and stable within its list: "1", "2", … — what a model passes back to update it.
    pub id: String,
    pub title: String,
    pub status: Status,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
    pub updated_ms: u64,
    pub updated_by: Editor,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct TaskList {
    pub id: String,
    pub title: String,
    pub created_ms: u64,
    pub updated_ms: u64,
    #[serde(default)]
    pub archived: bool,
    /// Instances the work happens on, so a display can show their activity beside the list.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub instances: Vec<String>,
    pub tasks: Vec<Task>,
    /// The next task id to hand out. Never reused, so an id a model remembers keeps meaning one task.
    #[serde(default)]
    next_task: u32,
}

impl TaskList {
    /// "3/8 done", counting skipped tasks as settled.
    pub fn progress(&self) -> String {
        let settled = self.tasks.iter().filter(|task| task.status.is_settled()).count();
        format!("{settled}/{} done", self.tasks.len())
    }

    /// The task being worked on: the first `doing`, else the first not yet settled.
    pub fn current(&self) -> Option<&Task> {
        self.tasks
            .iter()
            .find(|task| task.status == Status::Doing)
            .or_else(|| self.tasks.iter().find(|task| !task.status.is_settled()))
    }

    /// A one-line summary, for listing lists without sending every task.
    pub fn summary(&self) -> serde_json::Value {
        let mut counts = serde_json::Map::new();
        for status in [
            Status::Todo,
            Status::Doing,
            Status::Done,
            Status::Blocked,
            Status::Skipped,
        ] {
            let count = self.tasks.iter().filter(|task| task.status == status).count();
            if count > 0 {
                counts.insert(status.name().into(), count.into());
            }
        }
        let mut summary = serde_json::json!({
            "id": self.id,
            "title": self.title,
            "progress": self.progress(),
            "counts": counts,
            "updatedMs": self.updated_ms,
        });
        if let Some(current) = self.current() {
            summary["current"] = serde_json::json!({ "id": current.id, "title": current.title });
        }
        if !self.instances.is_empty() {
            summary["instances"] = serde_json::json!(self.instances);
        }
        if self.archived {
            summary["archived"] = serde_json::json!(true);
        }
        summary
    }

    fn push(&mut self, title: &str, editor: Editor) -> Result<(), String> {
        if self.tasks.len() >= MAX_TASKS {
            return Err(format!(
                "'{}' already has {MAX_TASKS} tasks, the most one list holds. Start another list for \
                 the rest.",
                self.id
            ));
        }
        self.next_task += 1;
        self.tasks.push(Task {
            id: self.next_task.to_string(),
            title: clean(title, MAX_TITLE_CHARS, "a task")?,
            status: Status::Todo,
            note: None,
            updated_ms: now_millis(),
            updated_by: editor,
        });
        Ok(())
    }
}

/// One change to one task.
#[derive(Debug, Clone, Default)]
pub struct TaskUpdate {
    pub task: String,
    pub status: Option<Status>,
    /// `Some("")` clears the note.
    pub note: Option<String>,
    pub title: Option<String>,
}

pub struct TaskStore {
    /// `None` keeps lists in memory only, which is what tests and a store with nowhere to write use.
    directory: Option<PathBuf>,
    lists: Mutex<BTreeMap<String, TaskList>>,
    changed: tokio::sync::watch::Sender<u64>,
}

impl TaskStore {
    pub fn in_memory() -> Self {
        let (changed, _) = tokio::sync::watch::channel(0);
        Self {
            directory: None,
            lists: Mutex::new(BTreeMap::new()),
            changed,
        }
    }

    /// Loads every list in `directory`, creating it if needed.
    ///
    /// A file that will not parse is skipped with a warning rather than failing the load: one
    /// damaged list must not stop the orchestrator starting, and the file is left where it is for a
    /// person to look at.
    pub fn load(directory: impl Into<PathBuf>) -> Result<Self> {
        let directory = directory.into();
        std::fs::create_dir_all(&directory).with_context(|| format!("creating {}", directory.display()))?;
        let mut lists = BTreeMap::new();
        for entry in std::fs::read_dir(&directory)? {
            let path = entry?.path();
            if path.extension().and_then(|extension| extension.to_str()) != Some("json") {
                continue;
            }
            match std::fs::read_to_string(&path)
                .map_err(anyhow::Error::from)
                .and_then(|text| serde_json::from_str::<TaskList>(&text).map_err(Into::into))
            {
                Ok(list) => {
                    lists.insert(list.id.clone(), list);
                }
                Err(error) => {
                    tracing::warn!(path = %path.display(), %error, "skipped a task list that would not load")
                }
            }
        }
        let (changed, _) = tokio::sync::watch::channel(0);
        Ok(Self {
            directory: Some(directory),
            lists: Mutex::new(lists),
            changed,
        })
    }

    /// Wakes whenever a list changes.
    pub fn subscribe(&self) -> tokio::sync::watch::Receiver<u64> {
        self.changed.subscribe()
    }

    /// Every list, newest change first; archived ones only when asked for.
    pub fn all(&self, include_archived: bool) -> Vec<TaskList> {
        let lists = self.lists.lock().expect("tasks lock");
        let mut all: Vec<TaskList> = lists
            .values()
            .filter(|list| include_archived || !list.archived)
            .cloned()
            .collect();
        all.sort_by_key(|list| std::cmp::Reverse(list.updated_ms));
        all
    }

    /// One list, by id or by its exact title (ignoring case), which is how a person names it.
    pub fn get(&self, key: &str) -> Result<TaskList, String> {
        let lists = self.lists.lock().expect("tasks lock");
        resolve(&lists, key).map(|id| lists[&id].clone())
    }

    pub fn create(
        &self,
        title: &str,
        tasks: &[String],
        instances: &[String],
        authority: Authority,
    ) -> Result<TaskList, String> {
        let title = clean(title, MAX_TITLE_CHARS, "the list's title")?;
        if tasks.len() > MAX_TASKS {
            return Err(format!(
                "That is {} tasks; one list holds at most {MAX_TASKS}.",
                tasks.len()
            ));
        }
        let editor = Editor::from(authority);
        let now = now_millis();
        let mut lists = self.lists.lock().expect("tasks lock");
        let id = unique_id(&lists, &title);
        let mut list = TaskList {
            id: id.clone(),
            title,
            created_ms: now,
            updated_ms: now,
            archived: false,
            instances: instances.to_vec(),
            tasks: Vec::new(),
            next_task: 0,
        };
        for task in tasks {
            list.push(task, editor)?;
        }
        self.save(&list).map_err(|error| error.to_string())?;
        lists.insert(id, list.clone());
        self.bump();
        Ok(list)
    }

    pub fn add(&self, key: &str, tasks: &[String], authority: Authority) -> Result<TaskList, String> {
        if tasks.is_empty() {
            return Err("'tasks' must name at least one task to add.".into());
        }
        self.edit(key, |list| {
            for task in tasks {
                list.push(task, Editor::from(authority))?;
            }
            Ok(())
        })
    }

    /// Applies every update or none: a typo in the third must not leave the first two applied.
    pub fn update(
        &self,
        key: &str,
        updates: &[TaskUpdate],
        authority: Authority,
    ) -> Result<TaskList, String> {
        if updates.is_empty() {
            return Err("'updates' must hold at least one change.".into());
        }
        let editor = Editor::from(authority);
        self.edit(key, |list| {
            let mut staged = list.tasks.clone();
            for update in updates {
                let Some(task) = staged.iter_mut().find(|task| task.id == update.task.trim()) else {
                    let ids: Vec<&str> = list.tasks.iter().map(|task| task.id.as_str()).collect();
                    return Err(format!(
                        "'{}' has no task '{}'. Its tasks are: {}. Nothing was changed.",
                        list.id,
                        update.task,
                        if ids.is_empty() {
                            "none".into()
                        } else {
                            ids.join(", ")
                        }
                    ));
                };
                if let Some(status) = update.status {
                    task.status = status;
                }
                if let Some(note) = &update.note {
                    task.note = if note.trim().is_empty() {
                        None
                    } else {
                        Some(clean(note, MAX_NOTE_CHARS, "a note")?)
                    };
                }
                if let Some(title) = &update.title {
                    task.title = clean(title, MAX_TITLE_CHARS, "a task")?;
                }
                task.updated_ms = now_millis();
                task.updated_by = editor;
            }
            list.tasks = staged;
            Ok(())
        })
    }

    /// Hides a list, or brings it back. The file stays.
    pub fn set_archived(&self, key: &str, archived: bool) -> Result<TaskList, String> {
        self.edit(key, |list| {
            list.archived = archived;
            Ok(())
        })
    }

    /// Removes a list and its file. A person's decision only; see the module notes.
    pub fn delete(&self, key: &str, authority: Authority) -> Result<(), String> {
        authority
            .require_human("Deleting a task list")
            .map_err(|error| error.to_string())?;
        let mut lists = self.lists.lock().expect("tasks lock");
        let id = resolve(&lists, key)?;
        if let Some(path) = self.path_of(&id) {
            match std::fs::remove_file(&path) {
                Ok(()) => {}
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => return Err(format!("could not delete {}: {error}", path.display())),
            }
        }
        lists.remove(&id);
        drop(lists);
        self.bump();
        Ok(())
    }

    fn edit(
        &self,
        key: &str,
        change: impl FnOnce(&mut TaskList) -> Result<(), String>,
    ) -> Result<TaskList, String> {
        let mut lists = self.lists.lock().expect("tasks lock");
        let id = resolve(&lists, key)?;
        // Changed on a copy and saved before it replaces the original, so a failed write leaves the
        // list as it was in memory too, rather than ahead of the file.
        let mut list = lists[&id].clone();
        change(&mut list)?;
        list.updated_ms = now_millis();
        self.save(&list).map_err(|error| error.to_string())?;
        lists.insert(id, list.clone());
        drop(lists);
        self.bump();
        Ok(list)
    }

    fn path_of(&self, id: &str) -> Option<PathBuf> {
        self.directory
            .as_ref()
            .map(|directory| directory.join(format!("{id}.json")))
    }

    /// Write-then-rename, as the approval store does, so a crash mid-write cannot truncate a list.
    fn save(&self, list: &TaskList) -> Result<()> {
        let Some(path) = self.path_of(&list.id) else {
            return Ok(());
        };
        write_atomically(&path, &serde_json::to_vec_pretty(list)?)
    }

    fn bump(&self) {
        self.changed.send_modify(|generation| *generation += 1);
    }
}

fn write_atomically(path: &Path, bytes: &[u8]) -> Result<()> {
    let temporary = path.with_extension("json.tmp");
    std::fs::write(&temporary, bytes).with_context(|| format!("writing {}", temporary.display()))?;
    std::fs::rename(&temporary, path).with_context(|| format!("replacing {}", path.display()))?;
    Ok(())
}

/// A list's id from an id or a title, or why neither matched.
fn resolve(lists: &BTreeMap<String, TaskList>, key: &str) -> Result<String, String> {
    let key = key.trim();
    if lists.contains_key(key) {
        return Ok(key.to_string());
    }
    if let Some(list) = lists.values().find(|list| list.title.eq_ignore_ascii_case(key)) {
        return Ok(list.id.clone());
    }
    let active: Vec<&str> = lists
        .values()
        .filter(|list| !list.archived)
        .map(|list| list.id.as_str())
        .collect();
    Err(format!(
        "There is no task list '{key}'. {}",
        if active.is_empty() {
            "There are none yet; create one with op 'create'.".to_string()
        } else {
            format!("Lists: {}.", active.join(", "))
        }
    ))
}

/// A readable id from a title: lower case, dashes, unique among the lists that exist.
fn unique_id(lists: &BTreeMap<String, TaskList>, title: &str) -> String {
    let mut slug = String::new();
    for character in title.chars() {
        if character.is_ascii_alphanumeric() {
            slug.push(character.to_ascii_lowercase());
        } else if !slug.ends_with('-') && !slug.is_empty() {
            slug.push('-');
        }
        if slug.len() >= 40 {
            break;
        }
    }
    let slug = slug.trim_end_matches('-');
    let base = if slug.is_empty() { "tasks" } else { slug };
    if !lists.contains_key(base) {
        return base.to_string();
    }
    (2..)
        .map(|n| format!("{base}-{n}"))
        .find(|candidate| !lists.contains_key(candidate))
        .expect("an unbounded range finds a free id")
}

/// Trims, refuses empty text, and caps the length — saying which was wrong.
fn clean(text: &str, limit: usize, what: &str) -> Result<String, String> {
    let text = text.trim();
    if text.is_empty() {
        return Err(format!("{what} cannot be empty."));
    }
    if text.chars().count() > limit {
        return Err(format!("{what} is longer than {limit} characters; shorten it."));
    }
    Ok(text.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tasks(titles: &[&str]) -> Vec<String> {
        titles.iter().map(|title| title.to_string()).collect()
    }

    #[test]
    fn a_new_list_gets_a_readable_id_and_numbered_tasks() {
        let store = TaskStore::in_memory();
        let list = store
            .create(
                "Survey the road corridor!",
                &tasks(&["load chunks", "find frames"]),
                &[],
                Authority::Model,
            )
            .unwrap();
        assert_eq!(list.id, "survey-the-road-corridor");
        assert_eq!(list.tasks[0].id, "1");
        assert_eq!(list.tasks[1].id, "2");
        assert_eq!(list.progress(), "0/2 done");
    }

    #[test]
    fn two_lists_with_one_title_get_different_ids() {
        let store = TaskStore::in_memory();
        let first = store.create("Build", &[], &[], Authority::Model).unwrap();
        let second = store.create("Build", &[], &[], Authority::Model).unwrap();
        assert_eq!((first.id.as_str(), second.id.as_str()), ("build", "build-2"));
    }

    #[test]
    fn updates_apply_all_or_nothing() {
        let store = TaskStore::in_memory();
        store
            .create("Job", &tasks(&["a", "b"]), &[], Authority::Model)
            .unwrap();
        let updates = vec![
            TaskUpdate {
                task: "1".into(),
                status: Some(Status::Done),
                ..TaskUpdate::default()
            },
            TaskUpdate {
                task: "9".into(),
                status: Some(Status::Done),
                ..TaskUpdate::default()
            },
        ];
        let error = store.update("job", &updates, Authority::Model).unwrap_err();
        assert!(error.contains("no task '9'") && error.contains("1, 2"), "{error}");
        assert_eq!(store.get("job").unwrap().tasks[0].status, Status::Todo);
    }

    #[test]
    fn progress_counts_skipped_tasks_as_settled_and_current_prefers_doing() {
        let store = TaskStore::in_memory();
        store
            .create("Job", &tasks(&["a", "b", "c", "d"]), &[], Authority::Model)
            .unwrap();
        let list = store
            .update(
                "job",
                &[
                    TaskUpdate {
                        task: "1".into(),
                        status: Some(Status::Done),
                        ..TaskUpdate::default()
                    },
                    TaskUpdate {
                        task: "2".into(),
                        status: Some(Status::Skipped),
                        ..TaskUpdate::default()
                    },
                    TaskUpdate {
                        task: "4".into(),
                        status: Some(Status::Doing),
                        ..TaskUpdate::default()
                    },
                ],
                Authority::Model,
            )
            .unwrap();
        assert_eq!(list.progress(), "2/4 done");
        assert_eq!(list.current().unwrap().id, "4");
    }

    #[test]
    fn a_list_can_be_named_by_its_title() {
        let store = TaskStore::in_memory();
        store
            .create("Signal Tests", &tasks(&["x"]), &[], Authority::Model)
            .unwrap();
        assert_eq!(store.get("signal tests").unwrap().id, "signal-tests");
    }

    #[test]
    fn an_unknown_list_names_the_ones_that_exist() {
        let store = TaskStore::in_memory();
        store.create("Alpha", &[], &[], Authority::Model).unwrap();
        let error = store.get("beta").unwrap_err();
        assert!(error.contains("Lists: alpha."), "{error}");
    }

    #[test]
    fn a_task_id_is_never_reused() {
        let store = TaskStore::in_memory();
        store
            .create("Job", &tasks(&["a", "b"]), &[], Authority::Model)
            .unwrap();
        let list = store.add("job", &tasks(&["c"]), Authority::Model).unwrap();
        assert_eq!(list.tasks.last().unwrap().id, "3");
    }

    #[test]
    fn a_model_may_archive_but_only_a_person_may_delete() {
        let store = TaskStore::in_memory();
        store.create("Job", &[], &[], Authority::Model).unwrap();
        assert!(store.set_archived("job", true).unwrap().archived);
        assert!(store.all(false).is_empty());
        assert_eq!(store.all(true).len(), 1);

        let refusal = store.delete("job", Authority::Model).unwrap_err();
        assert!(refusal.contains("only be done by a person"), "{refusal}");
        store.delete("job", Authority::Human).unwrap();
        assert!(store.all(true).is_empty());
    }

    #[test]
    fn an_empty_note_clears_it() {
        let store = TaskStore::in_memory();
        store
            .create("Job", &tasks(&["a"]), &[], Authority::Model)
            .unwrap();
        let with_note = TaskUpdate {
            task: "1".into(),
            note: Some("halfway".into()),
            ..TaskUpdate::default()
        };
        let cleared = TaskUpdate {
            task: "1".into(),
            note: Some(" ".into()),
            ..TaskUpdate::default()
        };
        assert_eq!(
            store.update("job", &[with_note], Authority::Model).unwrap().tasks[0]
                .note
                .as_deref(),
            Some("halfway")
        );
        assert_eq!(
            store.update("job", &[cleared], Authority::Human).unwrap().tasks[0].note,
            None
        );
        assert_eq!(store.get("job").unwrap().tasks[0].updated_by, Editor::Human);
    }

    #[test]
    fn oversized_input_is_refused_with_the_limit() {
        let store = TaskStore::in_memory();
        let long = "x".repeat(MAX_TITLE_CHARS + 1);
        assert!(
            store
                .create(&long, &[], &[], Authority::Model)
                .unwrap_err()
                .contains("200")
        );
        let many: Vec<String> = (0..=MAX_TASKS).map(|i| i.to_string()).collect();
        assert!(store.create("Big", &many, &[], Authority::Model).is_err());
    }

    #[test]
    fn lists_survive_a_restart_and_a_damaged_file_does_not_stop_the_load() {
        let directory = std::env::temp_dir().join(format!("mcmcp-tasks-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&directory);
        {
            let store = TaskStore::load(&directory).unwrap();
            store
                .create(
                    "Long job",
                    &tasks(&["a", "b"]),
                    &["alpha.client".into()],
                    Authority::Model,
                )
                .unwrap();
            store
                .update(
                    "long-job",
                    &[TaskUpdate {
                        task: "1".into(),
                        status: Some(Status::Done),
                        ..TaskUpdate::default()
                    }],
                    Authority::Model,
                )
                .unwrap();
        }
        std::fs::write(directory.join("broken.json"), "{ not json").unwrap();

        let reloaded = TaskStore::load(&directory).unwrap();
        let list = reloaded.get("long-job").unwrap();
        assert_eq!(list.progress(), "1/2 done");
        assert_eq!(list.instances, vec!["alpha.client".to_string()]);
        // The id counter came back too: the next task is 3, not a reused 2.
        assert_eq!(
            reloaded
                .add("long-job", &tasks(&["c"]), Authority::Model)
                .unwrap()
                .tasks[2]
                .id,
            "3"
        );
        let _ = std::fs::remove_dir_all(&directory);
    }

    #[test]
    fn statuses_accept_the_words_a_model_reaches_for() {
        assert_eq!(Status::parse("in_progress"), Some(Status::Doing));
        assert_eq!(Status::parse("Completed"), Some(Status::Done));
        assert_eq!(Status::parse("finished"), None);
    }
}
