//! The structured event log.
//!
//! # Why structured, and not just log lines
//!
//! The thing this log is for is a person watching a model drive their game and asking "what did it
//! just do, and to which instance?" A text log answers that badly: you cannot filter it by instance,
//! you cannot pull one tool call out of it to paste back into a conversation, and you certainly
//! cannot hand somebody "everything instance alpha did between 14:02 and 14:09".
//!
//! So every entry is a record with the same shape — when, which instance, who acted, what happened —
//! and the human-readable rendering is a view of that rather than the storage.
//!
//! # Two sinks, on purpose
//!
//! A bounded ring in memory backs the live view: it is what a UI scrolls, and it must never grow
//! without limit inside a process that runs for days. A JSONL file on disk backs everything else,
//! because the most valuable moment for this log is *after* something went wrong, and a log that
//! only lived in a window that has since been closed is no log at all.
//!
//! # Secrets
//!
//! Tool arguments go in here, and tool arguments are model-generated. Nothing filters them, because
//! nothing sensibly could. What *is* guaranteed is that the log never records an instance secret:
//! the handshake is summarised by [`EventKind::InstanceLinked`], which carries the id and the label
//! and nothing else.

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::VecDeque;
use std::io::Write;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

/// How many entries the in-memory ring keeps.
///
/// Enough to scroll back through a long working session; small enough that it cannot be the reason
/// a process that has been up for a week runs out of memory.
pub const RING_CAPACITY: usize = 5_000;

/// Who caused an event.
///
/// The distinction is the whole reason a person can read this log and understand it. "Focus moved to
/// beta" means something very different depending on whether the model did it or they did.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Actor {
    /// The MCP client, and behind it a model.
    Model,
    /// A person, through the GUI or the CLI.
    Human,
    /// The orchestrator itself — a link opening, a catalogue refreshing.
    System,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Level {
    Debug,
    Info,
    Warn,
    Error,
}

/// What happened.
///
/// A closed set rather than free text: a UI filters on these, and "whatever string the calling code
/// felt like" is not something a filter can offer as a checkbox.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum EventKind {
    InstanceLinked {
        label: String,
        side: String,
        game_directory: Option<String>,
    },
    InstanceUnlinked,
    InstanceApproved {
        label: String,
    },
    InstanceRejected {
        reason: String,
    },
    InstanceRevoked,
    FocusChanged {
        from: Option<String>,
        to: String,
    },
    LabelChanged {
        from: String,
        to: String,
    },
    /// A tool call, recorded when it is answered so the outcome is in the same record.
    ToolCall {
        tool: String,
        arguments: Value,
        #[serde(default)]
        is_error: bool,
        duration_ms: u64,
    },
    /// A call the gating policy refused before it reached a game.
    ToolBlocked {
        tool: String,
        rule: String,
    },
    CatalogueChanged {
        tools: usize,
    },
    Note {
        message: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Event {
    /// Milliseconds since the Unix epoch. Stored as a number so a viewer can sort and range it
    /// without parsing anything.
    pub at: u64,
    pub actor: Actor,
    pub level: Level,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub instance: Option<String>,
    /// The instance's human-readable label at the time this happened.
    ///
    /// Recorded rather than resolved when the log is read, and that is the point: an id like
    /// `run-d0a639` is a directory slug plus a few bytes of entropy, and nobody auditing a trail
    /// knows which game it was. Labels also change, so a name looked up later would relabel history.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
    #[serde(flatten)]
    pub kind: EventKind,
}

impl Event {
    pub fn new(actor: Actor, level: Level, instance: Option<String>, kind: EventKind) -> Self {
        Self {
            at: now_millis(),
            actor,
            level,
            instance,
            label: None,
            kind,
        }
    }

    /// Names the instance this event is about, for the log line.
    ///
    /// Separate from [`Self::new`] because most callers have an id and no label to hand; whoever
    /// does have one chains this on. An event that never gets one still reads correctly, just by id.
    pub fn labelled(mut self, label: impl Into<String>) -> Self {
        let label = label.into();
        // A label equal to the id is what an instance nobody has renamed reports, and rendering
        // "run-d0a639 (run-d0a639)" helps nobody.
        if !label.is_empty() && Some(&label) != self.instance.as_ref() {
            self.label = Some(label);
        }
        self
    }

    /// A one-line rendering, for a terminal or a compact list.
    pub fn summary(&self) -> String {
        // "modB dev (run-d0a639.client)" — the name a person recognises, and the id they can act
        // on. Neither alone is enough: the id is unreadable, and labels are not unique.
        let named;
        let where_ = match (self.instance.as_deref(), self.label.as_deref()) {
            (Some(id), Some(label)) => {
                named = format!("{label} ({id})");
                named.as_str()
            }
            (Some(id), None) => id,
            (None, _) => "-",
        };
        match &self.kind {
            EventKind::InstanceLinked { label, side, .. } => {
                format!("{where_}: linked as \"{label}\" ({side})")
            }
            EventKind::InstanceUnlinked => format!("{where_}: unlinked"),
            EventKind::InstanceApproved { label } => format!("{where_}: approved as \"{label}\""),
            EventKind::InstanceRejected { reason } => format!("{where_}: refused ({reason})"),
            EventKind::InstanceRevoked => format!("{where_}: revoked"),
            EventKind::FocusChanged { from, to } => match from {
                Some(from) => format!("focus moved from {from} to {to}"),
                None => format!("focus set to {to}"),
            },
            EventKind::LabelChanged { from, to } => format!("{where_}: renamed \"{from}\" to \"{to}\""),
            EventKind::ToolCall {
                tool,
                is_error,
                duration_ms,
                ..
            } => {
                let outcome = if *is_error { "failed" } else { "ok" };
                format!("{where_}: {tool} {outcome} in {duration_ms}ms")
            }
            EventKind::ToolBlocked { tool, rule } => format!("{where_}: {tool} blocked by {rule}"),
            EventKind::CatalogueChanged { tools } => format!("{where_}: catalogue now {tools} tools"),
            EventKind::Note { message } => message.clone(),
        }
    }
}

/// What to filter a slice of the log by.
///
/// Every field is optional and they compose with AND. The one that earns this type is `instance`:
/// "everything that happened to alpha" is the question the log exists to answer, and it is the one
/// a flat file cannot.
#[derive(Debug, Default, Clone)]
pub struct Filter {
    pub instance: Option<String>,
    pub actor: Option<Actor>,
    pub min_level: Option<Level>,
    pub since: Option<u64>,
    pub until: Option<u64>,
    /// Case-insensitive substring match against the one-line summary.
    pub text: Option<String>,
}

impl Filter {
    fn matches(&self, event: &Event) -> bool {
        if let Some(instance) = &self.instance
            && event.instance.as_deref() != Some(instance.as_str())
        {
            return false;
        }
        if let Some(actor) = self.actor
            && event.actor != actor
        {
            return false;
        }
        if let Some(min) = self.min_level
            && event.level < min
        {
            return false;
        }
        if let Some(since) = self.since
            && event.at < since
        {
            return false;
        }
        if let Some(until) = self.until
            && event.at > until
        {
            return false;
        }
        if let Some(text) = &self.text
            && !event.summary().to_lowercase().contains(&text.to_lowercase())
        {
            return false;
        }
        true
    }
}

/// The log itself.
#[derive(Clone)]
pub struct EventLog {
    ring: Arc<Mutex<VecDeque<Event>>>,
    file: Option<Arc<Mutex<FileSink>>>,
}

struct FileSink {
    path: PathBuf,
    /// Bytes written since the last rotation check, so the file's size is not stat'ed per event.
    written: u64,
    limit: u64,
}

impl EventLog {
    /// A log that only lives in memory. For tests, and for a run with no state directory.
    pub fn in_memory() -> Self {
        Self {
            ring: Arc::new(Mutex::new(VecDeque::with_capacity(RING_CAPACITY))),
            file: None,
        }
    }

    /// A log that also appends to a rotating JSONL file.
    pub fn with_file(path: PathBuf, limit_bytes: u64) -> Self {
        let written = std::fs::metadata(&path).map(|meta| meta.len()).unwrap_or(0);
        Self {
            ring: Arc::new(Mutex::new(VecDeque::with_capacity(RING_CAPACITY))),
            file: Some(Arc::new(Mutex::new(FileSink {
                path,
                written,
                limit: limit_bytes,
            }))),
        }
    }

    pub fn record(&self, event: Event) {
        if let Some(file) = &self.file
            && let Ok(mut sink) = file.lock()
        {
            // A log that cannot be written is not worth taking the process down for, and the ring
            // still has the entry.
            let _ = sink.append(&event);
        }

        let mut ring = self.ring.lock().expect("event ring lock");
        if ring.len() == RING_CAPACITY {
            ring.pop_front();
        }
        ring.push_back(event);
    }

    /// Convenience for the common shapes.
    pub fn note(&self, actor: Actor, instance: Option<String>, message: impl Into<String>) {
        self.record(Event::new(
            actor,
            Level::Info,
            instance,
            EventKind::Note {
                message: message.into(),
            },
        ));
    }

    /// [`Self::note`], for a caller that knows the instance's human-readable label.
    ///
    /// Worth the second method rather than an `Option<String>` on the first: the callers that have
    /// a label and the callers that do not are different code paths, and a parameter everybody
    /// passes `None` to is a parameter everybody forgets.
    pub fn note_about(
        &self,
        actor: Actor,
        instance: Option<String>,
        label: impl Into<String>,
        message: impl Into<String>,
    ) {
        self.record(
            Event::new(
                actor,
                Level::Info,
                instance,
                EventKind::Note {
                    message: message.into(),
                },
            )
            .labelled(label),
        );
    }

    /// The most recent `limit` entries matching `filter`, oldest first.
    pub fn slice(&self, filter: &Filter, limit: usize) -> Vec<Event> {
        let ring = self.ring.lock().expect("event ring lock");
        let mut matched: Vec<Event> = ring
            .iter()
            .filter(|event| filter.matches(event))
            .cloned()
            .collect();
        if matched.len() > limit {
            // Keep the newest, which is what somebody looking at a live view wants.
            matched.drain(..matched.len() - limit);
        }
        matched
    }

    pub fn len(&self) -> usize {
        self.ring.lock().expect("event ring lock").len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

impl FileSink {
    fn append(&mut self, event: &Event) -> std::io::Result<()> {
        if self.written >= self.limit {
            self.rotate()?;
        }
        if let Some(parent) = self.path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut line = serde_json::to_vec(event).map_err(std::io::Error::other)?;
        line.push(b'\n');

        let mut handle = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.path)?;
        handle.write_all(&line)?;
        self.written += line.len() as u64;
        Ok(())
    }

    /// Keeps exactly one previous file.
    ///
    /// Not a generation ladder. Two files is enough to cover "it broke a moment ago" without turning
    /// a diagnostic aid into something that quietly consumes a disk.
    fn rotate(&mut self) -> std::io::Result<()> {
        let previous = self.path.with_extension("jsonl.1");
        let _ = std::fs::remove_file(&previous);
        if self.path.exists() {
            std::fs::rename(&self.path, &previous)?;
        }
        self.written = 0;
        Ok(())
    }
}

pub fn now_millis() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn tool_call(instance: &str, tool: &str, is_error: bool) -> Event {
        Event::new(
            Actor::Model,
            if is_error { Level::Warn } else { Level::Info },
            Some(instance.into()),
            EventKind::ToolCall {
                tool: tool.into(),
                arguments: json!({"x": 1}),
                is_error,
                duration_ms: 12,
            },
        )
    }

    #[test]
    fn keeps_the_newest_entries_once_full() {
        // A process that runs for days must not grow a log without limit, and somebody scrolling a
        // live view wants the recent end of it.
        let log = EventLog::in_memory();
        for index in 0..(RING_CAPACITY + 10) {
            log.note(Actor::System, None, format!("entry {index}"));
        }

        assert_eq!(log.len(), RING_CAPACITY);
        let all = log.slice(&Filter::default(), RING_CAPACITY);
        assert_eq!(
            all.last().unwrap().summary(),
            format!("entry {}", RING_CAPACITY + 9)
        );
    }

    #[test]
    fn a_summary_names_the_instance_as_well_as_identifying_it() {
        // The id alone is a directory slug plus entropy. Somebody auditing what a model did needs
        // to recognise which game it was without cross-referencing a roster.
        let event = Event::new(
            Actor::Model,
            Level::Info,
            Some("run-d0a639.client".into()),
            EventKind::Note {
                message: "did a thing".into(),
            },
        )
        .labelled("modB dev");

        assert_eq!(event.label.as_deref(), Some("modB dev"));

        let blocked = Event::new(
            Actor::Model,
            Level::Warn,
            Some("run-d0a639.client".into()),
            EventKind::ToolBlocked {
                tool: "server_set_block".into(),
                rule: "deny".into(),
            },
        )
        .labelled("modB dev");

        assert_eq!(
            blocked.summary(),
            "modB dev (run-d0a639.client): server_set_block blocked by deny"
        );
    }

    #[test]
    fn an_instance_nobody_renamed_is_not_named_twice() {
        // The mod's fallback label is the instance id. "run-d0a639 (run-d0a639)" helps nobody.
        let event = Event::new(
            Actor::Model,
            Level::Info,
            Some("run-d0a639".into()),
            EventKind::InstanceUnlinked,
        )
        .labelled("run-d0a639");

        assert_eq!(event.summary(), "run-d0a639: unlinked");
    }

    #[test]
    fn filters_to_one_instance() {
        // The question the log exists to answer, and the one a flat text file cannot.
        let log = EventLog::in_memory();
        log.record(tool_call("alpha", "client_move", false));
        log.record(tool_call("beta", "client_move", false));
        log.record(tool_call("alpha", "client_look", false));

        let filter = Filter {
            instance: Some("alpha".into()),
            ..Filter::default()
        };

        assert_eq!(log.slice(&filter, 100).len(), 2);
    }

    #[test]
    fn filters_by_who_acted() {
        // "Focus moved to beta" means something very different depending on who did it.
        let log = EventLog::in_memory();
        log.note(Actor::Model, None, "the model did this");
        log.note(Actor::Human, None, "a person did this");

        let filter = Filter {
            actor: Some(Actor::Human),
            ..Filter::default()
        };
        let matched = log.slice(&filter, 100);

        assert_eq!(matched.len(), 1);
        assert_eq!(matched[0].summary(), "a person did this");
    }

    #[test]
    fn filters_by_minimum_level() {
        let log = EventLog::in_memory();
        log.record(tool_call("alpha", "ok_tool", false));
        log.record(tool_call("alpha", "bad_tool", true));

        let filter = Filter {
            min_level: Some(Level::Warn),
            ..Filter::default()
        };
        let matched = log.slice(&filter, 100);

        assert_eq!(matched.len(), 1);
        assert!(matched[0].summary().contains("bad_tool"));
    }

    #[test]
    fn filters_to_a_time_range_so_a_slice_can_be_exported() {
        // "Everything alpha did between these two times", which is the shape of handing somebody a
        // reproduction.
        let log = EventLog::in_memory();
        let mut early = tool_call("alpha", "early", false);
        early.at = 1_000;
        let mut late = tool_call("alpha", "late", false);
        late.at = 9_000;
        log.record(early);
        log.record(late);

        let filter = Filter {
            since: Some(5_000),
            until: Some(10_000),
            ..Filter::default()
        };
        let matched = log.slice(&filter, 100);

        assert_eq!(matched.len(), 1);
        assert!(matched[0].summary().contains("late"));
    }

    #[test]
    fn searches_the_rendered_summary_case_insensitively() {
        let log = EventLog::in_memory();
        log.record(tool_call("alpha", "client_screenshot", false));
        log.record(tool_call("alpha", "server_set_block", false));

        let filter = Filter {
            text: Some("SCREENSHOT".into()),
            ..Filter::default()
        };

        assert_eq!(log.slice(&filter, 100).len(), 1);
    }

    #[test]
    fn a_tool_call_records_its_outcome_in_the_same_entry() {
        // Split across two entries, a failure and its call would have to be correlated by hand
        // exactly when somebody is least inclined to.
        let event = tool_call("alpha", "client_move", true);

        let summary = event.summary();
        assert!(summary.contains("client_move"));
        assert!(summary.contains("failed"));
        assert!(summary.contains("12ms"));
    }

    #[test]
    fn one_entry_serialises_to_something_worth_pasting_back() {
        // Copy-one-event-as-JSON is the feature this shape is for.
        let event = tool_call("alpha", "client_move", false);

        let json = serde_json::to_value(&event).unwrap();
        assert_eq!(json["kind"], "tool_call");
        assert_eq!(json["instance"], "alpha");
        assert_eq!(json["actor"], "model");
        assert_eq!(json["arguments"]["x"], 1);
    }

    #[test]
    fn an_instance_link_records_the_label_and_never_a_secret() {
        let event = Event::new(
            Actor::System,
            Level::Info,
            Some("alpha".into()),
            EventKind::InstanceLinked {
                label: "modB dev".into(),
                side: "client".into(),
                game_directory: Some("E:\\instances\\modB".into()),
            },
        );

        let text = serde_json::to_string(&event).unwrap();
        assert!(text.contains("modB dev"));
        assert!(!text.to_lowercase().contains("secret"));
    }

    #[test]
    fn writes_to_disk_and_rotates_rather_than_growing_without_limit() {
        let directory = std::env::temp_dir().join(format!("mcmcp-events-{}", std::process::id()));
        let _ = std::fs::create_dir_all(&directory);
        let path = directory.join("events.jsonl");

        // A limit small enough that a handful of entries crosses it.
        let log = EventLog::with_file(path.clone(), 200);
        for index in 0..40 {
            log.note(Actor::System, Some("alpha".into()), format!("entry {index}"));
        }

        assert!(path.exists(), "the log file should exist");
        assert!(
            path.with_extension("jsonl.1").exists(),
            "one previous file should be kept"
        );
        // The live file must be small; if rotation did nothing this would be the whole run.
        assert!(std::fs::metadata(&path).unwrap().len() < 2_000);
        // And the in-memory ring is unaffected by rotation.
        assert_eq!(log.len(), 40);

        let _ = std::fs::remove_dir_all(&directory);
    }
}
