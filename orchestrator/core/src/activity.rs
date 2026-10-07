//! What every game is doing right now, and what it did a moment ago.
//!
//! # Why this exists
//!
//! The event log records a tool call once it has finished. Until then nothing in the orchestrator
//! knew a call was running beyond an id kept for cancellation — not its tool, not when it started,
//! not how far through it was. Progress notifications already arrived from the games, every one of
//! them, and were used only as a heartbeat for the call's timeout and then thrown away. A forty-step
//! `client_sequence` or a large `server_set_blocks` was a silent wait for anyone watching.
//!
//! This keeps that state: calls in flight with their latest progress, a bounded ring of recently
//! finished ones, and each instance's recent log messages. It lives in the core rather than the app,
//! so that anything built on the core can read it, and it is held in memory only: it describes the
//! present, and the durable record of the past is still the event log.
//!
//! # Shape
//!
//! `&self` with an internal lock, like [`crate::events::EventLog`], so the router and every task it
//! spawns can share one behind an `Arc`. Each change bumps a generation counter on a
//! `tokio::sync::watch`, which is how a display wakes up — and why a burst of progress becomes one
//! wake-up rather than a hundred: a watch holds only the latest value.

use serde::Serialize;
use serde_json::Value;
use std::collections::{BTreeMap, HashMap, VecDeque};
use std::sync::Mutex;

use crate::events::now_millis;

/// How many finished calls are kept, across every instance.
pub const RECENT_CAPACITY: usize = 200;

/// How many log messages are kept per instance.
pub const LOG_CAPACITY: usize = 200;

/// The longest arguments summary kept for a call, in characters.
///
/// `server_set_blocks` arguments can run to hundreds of kilobytes. A summary is for a person to
/// recognise the call by, and the event log already keeps the arguments in full.
pub const ARGUMENTS_SUMMARY_CHARS: usize = 200;

/// The longest log message or error kept, in characters.
const TEXT_CHARS: usize = 500;

/// A call's handle in the registry, returned by [`Activity::start`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize)]
pub struct CallId(pub u64);

/// The latest progress a call reported.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Progress {
    pub progress: f64,
    /// MCP makes this optional; a call that does not know its total reports progress alone.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub total: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
    pub at_ms: u64,
}

/// A call in flight.
#[derive(Debug, Clone, Serialize)]
pub struct RunningCall {
    pub id: CallId,
    pub instance: String,
    pub tool: String,
    pub arguments: String,
    pub started_at_ms: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub progress: Option<Progress>,
    /// Matched against incoming progress. Not shown: it is plumbing.
    #[serde(skip)]
    token: Option<String>,
}

/// How a call ended.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Outcome {
    /// The tool ran and reported success.
    Ok,
    /// The tool ran and reported failure (`isError: true`), or the game could not complete it.
    Error,
    /// The orchestrator stopped waiting.
    TimedOut,
    /// The client cancelled it.
    Cancelled,
}

/// A call that has finished.
#[derive(Debug, Clone, Serialize)]
pub struct FinishedCall {
    pub id: CallId,
    pub instance: String,
    pub tool: String,
    pub arguments: String,
    pub started_at_ms: u64,
    pub duration_ms: u64,
    pub outcome: Outcome,
    /// The last progress the call reported, which for a batch says how far it got.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_progress: Option<Progress>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

/// One `notifications/message` from a game.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct LogLine {
    pub at_ms: u64,
    pub level: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub logger: Option<String>,
    pub text: String,
}

/// Everything a display needs, copied out from under the lock.
#[derive(Debug, Clone, Serialize)]
pub struct Snapshot {
    /// Bumped on every change; a display can skip re-rendering an unchanged one.
    pub generation: u64,
    /// Oldest first.
    pub running: Vec<RunningCall>,
    /// Newest first.
    pub recent: Vec<FinishedCall>,
}

#[derive(Default)]
struct Inner {
    next_id: u64,
    running: BTreeMap<CallId, RunningCall>,
    recent: VecDeque<FinishedCall>,
    logs: HashMap<String, VecDeque<LogLine>>,
    generation: u64,
}

pub struct Activity {
    inner: Mutex<Inner>,
    changed: tokio::sync::watch::Sender<u64>,
}

impl Default for Activity {
    fn default() -> Self {
        Self::new()
    }
}

impl Activity {
    pub fn new() -> Self {
        let (changed, _) = tokio::sync::watch::channel(0);
        Self {
            inner: Mutex::new(Inner::default()),
            changed,
        }
    }

    /// A receiver that wakes whenever anything here changes. Its value is the generation.
    pub fn subscribe(&self) -> tokio::sync::watch::Receiver<u64> {
        self.changed.subscribe()
    }

    /// Records a call that has reached a game.
    ///
    /// `token` is the progress token the call carries, if any. It is compared in its JSON form,
    /// as the instance's own progress watches compare it, so the two never disagree about whether a
    /// notification belongs to a call.
    pub fn start(&self, instance: &str, tool: &str, arguments: &Value, token: Option<&Value>) -> CallId {
        let mut inner = self.inner.lock().expect("activity lock");
        inner.next_id += 1;
        let id = CallId(inner.next_id);
        inner.running.insert(
            id,
            RunningCall {
                id,
                instance: instance.to_string(),
                tool: tool.to_string(),
                arguments: summarize(arguments),
                started_at_ms: now_millis(),
                progress: None,
                token: token.map(Value::to_string),
            },
        );
        self.bump(&mut inner);
        id
    }

    /// Records a progress notification. Returns whether it matched a running call.
    ///
    /// One that matches nothing is ignored: progress can cross a call's answer on the wire and
    /// arrive after the call has finished, and a client's own token may belong to a call this
    /// orchestrator is not tracking.
    pub fn progress(&self, instance: &str, params: &Value) -> bool {
        let Some(token) = params.get("progressToken").filter(|token| !token.is_null()) else {
            return false;
        };
        let Some(progress) = params.get("progress").and_then(Value::as_f64) else {
            return false;
        };
        let token = token.to_string();
        let mut inner = self.inner.lock().expect("activity lock");
        let Some(call) = inner
            .running
            .values_mut()
            .find(|call| call.instance == instance && call.token.as_deref() == Some(token.as_str()))
        else {
            return false;
        };
        call.progress = Some(Progress {
            progress,
            total: params.get("total").and_then(Value::as_f64),
            message: params
                .get("message")
                .and_then(Value::as_str)
                .map(|message| truncate(message, TEXT_CHARS)),
            at_ms: now_millis(),
        });
        self.bump(&mut inner);
        true
    }

    /// Records how a call ended and moves it to the recent ring. A second finish is ignored.
    pub fn finish(&self, id: CallId, outcome: Outcome, error: Option<&str>) {
        let mut inner = self.inner.lock().expect("activity lock");
        let Some(call) = inner.running.remove(&id) else {
            return;
        };
        let finished = FinishedCall {
            id,
            duration_ms: now_millis().saturating_sub(call.started_at_ms),
            instance: call.instance,
            tool: call.tool,
            arguments: call.arguments,
            started_at_ms: call.started_at_ms,
            outcome,
            last_progress: call.progress,
            error: error.map(|error| truncate(error, TEXT_CHARS)),
        };
        inner.recent.push_front(finished);
        inner.recent.truncate(RECENT_CAPACITY);
        self.bump(&mut inner);
    }

    /// Records a `notifications/message` from a game.
    ///
    /// MCP's `data` may be any JSON; a string is kept as it is and anything else in its compact
    /// form, which is what a person reading a log line would want either way.
    pub fn log(&self, instance: &str, params: &Value) {
        let text = match params.get("data") {
            Some(Value::String(text)) => text.clone(),
            // The mod sends {"message": "..."}; a person wants the sentence, not the wrapper.
            Some(Value::Object(map))
                if map.len() == 1 && map.get("message").is_some_and(Value::is_string) =>
            {
                map["message"].as_str().unwrap_or_default().to_string()
            }
            Some(other) => other.to_string(),
            None => return,
        };
        let line = LogLine {
            at_ms: now_millis(),
            level: params
                .get("level")
                .and_then(Value::as_str)
                .unwrap_or("info")
                .to_string(),
            logger: params.get("logger").and_then(Value::as_str).map(str::to_string),
            text: truncate(&text, TEXT_CHARS),
        };
        let mut inner = self.inner.lock().expect("activity lock");
        let lines = inner.logs.entry(instance.to_string()).or_default();
        lines.push_back(line);
        while lines.len() > LOG_CAPACITY {
            lines.pop_front();
        }
        self.bump(&mut inner);
    }

    /// Calls in flight and recently finished, optionally for one instance only.
    pub fn snapshot(&self, instance: Option<&str>) -> Snapshot {
        self.snapshot_limited(instance, RECENT_CAPACITY)
    }

    /// As [`Self::snapshot`], with at most `recent_limit` finished calls.
    ///
    /// For a display that is pushed a snapshot on every change: it needs what is running and the
    /// last few calls, not the whole ring ten times a second.
    pub fn snapshot_limited(&self, instance: Option<&str>, recent_limit: usize) -> Snapshot {
        let inner = self.inner.lock().expect("activity lock");
        let wanted = |candidate: &str| instance.is_none_or(|instance| instance == candidate);
        Snapshot {
            generation: inner.generation,
            running: inner
                .running
                .values()
                .filter(|call| wanted(&call.instance))
                .cloned()
                .collect(),
            recent: inner
                .recent
                .iter()
                .filter(|call| wanted(&call.instance))
                .take(recent_limit)
                .cloned()
                .collect(),
        }
    }

    /// One instance's recent log messages, oldest first.
    pub fn logs(&self, instance: &str) -> Vec<LogLine> {
        let inner = self.inner.lock().expect("activity lock");
        inner
            .logs
            .get(instance)
            .map(|lines| lines.iter().cloned().collect())
            .unwrap_or_default()
    }

    fn bump(&self, inner: &mut Inner) {
        inner.generation += 1;
        // A send with no receiver is not an error: nothing is displaying this yet.
        let _ = self.changed.send(inner.generation);
    }
}

/// The arguments in compact JSON, cut to [`ARGUMENTS_SUMMARY_CHARS`].
fn summarize(arguments: &Value) -> String {
    match arguments {
        Value::Object(map) if map.is_empty() => String::new(),
        Value::Null => String::new(),
        other => truncate(&other.to_string(), ARGUMENTS_SUMMARY_CHARS),
    }
}

/// Cuts on a character boundary and marks the cut, so a summary never ends mid-codepoint.
fn truncate(text: &str, limit: usize) -> String {
    if text.chars().count() <= limit {
        return text.to_string();
    }
    let mut cut: String = text.chars().take(limit).collect();
    cut.push('…');
    cut
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn progress(token: Value, value: f64, total: Option<f64>, message: Option<&str>) -> Value {
        let mut params = json!({ "progressToken": token, "progress": value });
        if let Some(total) = total {
            params["total"] = json!(total);
        }
        if let Some(message) = message {
            params["message"] = json!(message);
        }
        params
    }

    #[test]
    fn a_running_call_shows_its_latest_progress() {
        let activity = Activity::new();
        let token = json!("orch-progress-1");
        activity.start("alpha.client", "client_sequence", &json!({}), Some(&token));

        assert!(activity.progress(
            "alpha.client",
            &progress(token.clone(), 3.0, Some(40.0), Some("look"))
        ));
        assert!(activity.progress("alpha.client", &progress(token, 4.0, Some(40.0), Some("use"))));

        let snapshot = activity.snapshot(None);
        let latest = snapshot.running[0].progress.as_ref().expect("progress");
        assert_eq!(latest.progress, 4.0);
        assert_eq!(latest.total, Some(40.0));
        assert_eq!(latest.message.as_deref(), Some("use"));
    }

    #[test]
    fn progress_without_a_total_is_kept_as_it_came() {
        let activity = Activity::new();
        let token = json!(7);
        activity.start("alpha.client", "client_wait", &json!({}), Some(&token));
        assert!(activity.progress("alpha.client", &progress(token, 12.0, None, None)));
        let latest = activity.snapshot(None).running[0]
            .progress
            .clone()
            .expect("progress");
        assert_eq!(latest.total, None);
        assert_eq!(latest.message, None);
    }

    #[test]
    fn progress_that_arrives_after_its_call_finished_changes_nothing() {
        let activity = Activity::new();
        let token = json!("orch-progress-2");
        let id = activity.start("alpha.client", "client_wait", &json!({}), Some(&token));
        activity.finish(id, Outcome::Ok, None);

        assert!(!activity.progress("alpha.client", &progress(token, 9.0, Some(10.0), None)));
        let snapshot = activity.snapshot(None);
        assert!(snapshot.running.is_empty());
        assert_eq!(snapshot.recent[0].last_progress, None);
    }

    #[test]
    fn the_same_token_on_two_instances_reaches_only_its_own_call() {
        // A client's own tokens are its to choose, and two games can be sent the same one.
        let activity = Activity::new();
        let token = json!("shared");
        activity.start("alpha.client", "client_wait", &json!({}), Some(&token));
        activity.start("beta.server", "server_set_blocks", &json!({}), Some(&token));

        assert!(activity.progress("beta.server", &progress(token, 5.0, Some(10.0), None)));
        let snapshot = activity.snapshot(None);
        let alpha = snapshot
            .running
            .iter()
            .find(|call| call.instance == "alpha.client")
            .unwrap();
        let beta = snapshot
            .running
            .iter()
            .find(|call| call.instance == "beta.server")
            .unwrap();
        assert!(alpha.progress.is_none());
        assert!(beta.progress.is_some());
    }

    #[test]
    fn a_string_token_and_a_number_token_with_the_same_digits_are_different_calls() {
        let activity = Activity::new();
        activity.start("alpha.client", "client_wait", &json!({}), Some(&json!("1")));
        assert!(!activity.progress("alpha.client", &progress(json!(1), 1.0, None, None)));
    }

    #[test]
    fn a_finished_call_keeps_how_far_it_got_and_why_it_failed() {
        let activity = Activity::new();
        let token = json!("t");
        let id = activity.start(
            "alpha.client",
            "client_sequence",
            &json!({ "steps": [] }),
            Some(&token),
        );
        activity.progress(
            "alpha.client",
            &progress(token, 12.0, Some(40.0), Some("step 12")),
        );
        activity.finish(id, Outcome::Error, Some("step 12 failed"));

        let finished = &activity.snapshot(None).recent[0];
        assert_eq!(finished.outcome, Outcome::Error);
        assert_eq!(finished.error.as_deref(), Some("step 12 failed"));
        assert_eq!(finished.last_progress.as_ref().map(|p| p.progress), Some(12.0));
    }

    #[test]
    fn finishing_a_call_twice_records_it_once() {
        let activity = Activity::new();
        let id = activity.start("alpha.client", "client_look", &json!({}), None);
        activity.finish(id, Outcome::Ok, None);
        activity.finish(id, Outcome::Cancelled, None);
        let snapshot = activity.snapshot(None);
        assert_eq!(snapshot.recent.len(), 1);
        assert_eq!(snapshot.recent[0].outcome, Outcome::Ok);
    }

    #[test]
    fn the_recent_ring_keeps_the_newest_calls_first_and_stays_bounded() {
        let activity = Activity::new();
        for _ in 0..RECENT_CAPACITY + 25 {
            let id = activity.start("alpha.client", "client_look", &json!({}), None);
            activity.finish(id, Outcome::Ok, None);
        }
        let recent = activity.snapshot(None).recent;
        assert_eq!(recent.len(), RECENT_CAPACITY);
        assert!(recent[0].id > recent[1].id);
    }

    #[test]
    fn a_snapshot_can_be_narrowed_to_one_instance() {
        let activity = Activity::new();
        activity.start("alpha.client", "client_look", &json!({}), None);
        activity.start("beta.server", "server_get_block", &json!({}), None);
        let snapshot = activity.snapshot(Some("beta.server"));
        assert_eq!(snapshot.running.len(), 1);
        assert_eq!(snapshot.running[0].tool, "server_get_block");
    }

    #[test]
    fn a_limited_snapshot_keeps_every_running_call_and_only_the_newest_finished_ones() {
        let activity = Activity::new();
        for _ in 0..10 {
            let id = activity.start("alpha.client", "client_look", &json!({}), None);
            activity.finish(id, Outcome::Ok, None);
        }
        activity.start("alpha.client", "client_wait", &json!({}), None);
        let snapshot = activity.snapshot_limited(None, 3);
        assert_eq!(snapshot.running.len(), 1);
        assert_eq!(snapshot.recent.len(), 3);
        assert_eq!(snapshot.recent[0].id, CallId(10));
    }

    #[test]
    fn huge_arguments_are_summarised_not_copied() {
        let activity = Activity::new();
        let blocks: Vec<Value> = (0..10_000).map(|i| json!([i, 64, i])).collect();
        activity.start(
            "beta.server",
            "server_set_blocks",
            &json!({ "blocks": blocks }),
            None,
        );
        let arguments = &activity.snapshot(None).running[0].arguments;
        assert_eq!(arguments.chars().count(), ARGUMENTS_SUMMARY_CHARS + 1);
        assert!(arguments.ends_with('…'));
    }

    #[test]
    fn truncation_never_splits_a_character() {
        let text = "é".repeat(ARGUMENTS_SUMMARY_CHARS + 5);
        let cut = truncate(&text, ARGUMENTS_SUMMARY_CHARS);
        assert_eq!(cut.chars().count(), ARGUMENTS_SUMMARY_CHARS + 1);
    }

    #[test]
    fn log_lines_are_kept_per_instance_and_bounded() {
        let activity = Activity::new();
        for i in 0..LOG_CAPACITY + 10 {
            activity.log(
                "alpha.client",
                &json!({ "level": "info", "data": format!("line {i}") }),
            );
        }
        activity.log(
            "beta.server",
            &json!({ "level": "warning", "data": { "chunk": [1, 2] } }),
        );

        let alpha = activity.logs("alpha.client");
        assert_eq!(alpha.len(), LOG_CAPACITY);
        assert_eq!(alpha[0].text, "line 10");
        let beta = activity.logs("beta.server");
        assert_eq!(beta[0].text, r#"{"chunk":[1,2]}"#);
        assert_eq!(beta[0].level, "warning");
    }

    #[test]
    fn a_message_wrapped_the_way_the_mod_sends_it_is_kept_as_its_sentence() {
        let activity = Activity::new();
        activity.log(
            "alpha.client",
            &json!({ "level": "warning", "data": { "message": "step 3 failed" } }),
        );
        assert_eq!(activity.logs("alpha.client")[0].text, "step 3 failed");
    }

    #[test]
    fn every_change_wakes_a_subscriber_with_a_newer_generation() {
        let activity = Activity::new();
        let mut receiver = activity.subscribe();
        let before = *receiver.borrow_and_update();
        activity.start("alpha.client", "client_look", &json!({}), None);
        assert!(receiver.has_changed().expect("sender alive"));
        assert!(*receiver.borrow_and_update() > before);
    }
}
