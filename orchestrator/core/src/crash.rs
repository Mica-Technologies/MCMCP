//! What a game left behind when it stopped.
//!
//! # Why the orchestrator looks, rather than the game saying
//!
//! A game that crashes cannot report its own crash over a link that died with it. The first sign an
//! agent had of a client killed by `OutOfMemoryError: Direct buffer memory` was `connection refused`
//! on its next call, and the cause turned up only when somebody opened the game's `crash-reports`
//! folder by hand. The orchestrator knows the game directory, the process id and when the session
//! began, which is everything needed to find the same files and say what they contain.
//!
//! Two files are worth finding. Minecraft writes `crash-reports/crash-<time>-<side>.txt` for anything
//! its own handler catches; the JVM writes `hs_err_pid<pid>.log` into the working directory when the
//! process dies underneath Minecraft — a native crash, or an allocation failure the game never saw.
//!
//! A third, when the game keeps one: MCMCP's request journal (`mcmcp/journal/<side>-requests.log`),
//! which says which tool call was running when the process went. A game can die with neither of the
//! other two files, and then the journal is the only thing that rules MCMCP in or out.

use serde_json::{Value, json};
use std::path::{Path, PathBuf};
use std::time::SystemTime;

/// A crash report Minecraft wrote, reduced to the two lines that say what happened.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CrashReport {
    pub path: PathBuf,
    /// The report's `Description:` line, e.g. "Exception in server tick loop".
    pub description: Option<String>,
    /// The first line of the stack trace under it — the exception and its message.
    pub exception: Option<String>,
}

impl CrashReport {
    pub fn to_json(&self) -> Value {
        json!({
            "path": self.path.to_string_lossy(),
            "description": self.description,
            "exception": self.exception,
        })
    }

    /// One line for an error message: what crashed and where the full report is.
    pub fn summary(&self) -> String {
        let what = match (&self.description, &self.exception) {
            (Some(description), Some(exception)) => format!("{description} — {exception}"),
            (Some(description), None) => description.clone(),
            (None, Some(exception)) => exception.clone(),
            (None, None) => "see the report".to_string(),
        };
        format!("{what} ({})", self.path.display())
    }
}

/// The newest crash report in `game_directory` written at or after `since`.
///
/// `since` is when this orchestrator saw the session begin. Anything older belongs to an earlier
/// run, and reporting last week's crash as the reason a game just went away would be worse than
/// reporting nothing.
pub fn newest_crash_report(game_directory: &Path, since: SystemTime) -> Option<CrashReport> {
    let entries = std::fs::read_dir(game_directory.join("crash-reports")).ok()?;
    let newest = entries
        .filter_map(Result::ok)
        .filter(|entry| {
            let name = entry.file_name().to_string_lossy().to_string();
            name.starts_with("crash-") && name.ends_with(".txt")
        })
        .filter_map(|entry| {
            let modified = entry.metadata().ok()?.modified().ok()?;
            (modified >= since).then(|| (modified, entry.path()))
        })
        .max_by_key(|(modified, _)| *modified)?;

    let text = std::fs::read_to_string(&newest.1).unwrap_or_default();
    let (description, exception) = describe(&text);
    Some(CrashReport {
        path: newest.1,
        description,
        exception,
    })
}

/// The JVM's own fatal-error log for `pid`, if the process left one.
pub fn jvm_error_log(game_directory: &Path, pid: Option<i64>) -> Option<PathBuf> {
    let path = game_directory.join(format!("hs_err_pid{}.log", pid?));
    path.is_file().then_some(path)
}

/// Pulls the description and the exception line out of a crash report's text.
fn describe(text: &str) -> (Option<String>, Option<String>) {
    let mut lines = text.lines();
    let description = lines
        .by_ref()
        .find_map(|line| line.strip_prefix("Description:"))
        .map(|rest| rest.trim().to_string())
        .filter(|rest| !rest.is_empty());
    if description.is_none() {
        return (None, None);
    }
    let exception = lines
        .map(str::trim)
        .find(|line| !line.is_empty())
        .map(str::to_string);
    (description, exception)
}

/// The calls the game's request journal recorded as started and never finished, for the run that
/// `pid` was.
///
/// Reads both sides' journals. Only a run whose open line names `pid` counts: the journal outlives
/// the process, and a run from before this session must not be blamed for its end. `None` when the
/// game keeps no journal (it is off by default on a client), or none of it is this process's.
pub fn journal_last_run(game_directory: &Path, pid: Option<i64>) -> Option<Value> {
    let pid = pid?;
    let mut sides = Vec::new();
    for side in ["client", "server"] {
        let path = game_directory
            .join("mcmcp")
            .join("journal")
            .join(format!("{side}-requests.log"));
        let Ok(text) = std::fs::read_to_string(&path) else {
            continue;
        };
        if let Some(run) = scan_journal(&text, pid) {
            sides.push(json!({
                "side": side,
                "endedCleanly": run.ended_cleanly,
                "unfinishedCount": run.unfinished.len(),
                // The newest is the one that matters; the rest are counted, not listed.
                "lastUnfinished": run.unfinished.last(),
                "path": path.to_string_lossy(),
            }));
        }
    }
    if sides.is_empty() {
        None
    } else {
        Some(Value::Array(sides))
    }
}

struct JournalRun {
    ended_cleanly: bool,
    unfinished: Vec<Value>,
}

/// The last run in one journal file, if it was `pid`'s. A half-written last line is skipped.
fn scan_journal(text: &str, pid: i64) -> Option<JournalRun> {
    let lines: Vec<Value> = text
        .lines()
        .filter_map(|line| serde_json::from_str::<Value>(line.trim()).ok())
        .filter(Value::is_object)
        .collect();
    let open = lines
        .iter()
        .rposition(|line| line.get("ev").and_then(Value::as_str) == Some("open"))?;
    if lines[open].get("pid").and_then(Value::as_i64) != Some(pid) {
        return None;
    }
    let mut running: Vec<(i64, Value)> = Vec::new();
    let mut ended_cleanly = false;
    for line in &lines[open + 1..] {
        let id = line.get("id").and_then(Value::as_i64).unwrap_or(-1);
        match line.get("ev").and_then(Value::as_str) {
            Some("start") => running.push((
                id,
                json!({
                    "tool": line.get("tool"),
                    "startedAt": line.get("t"),
                    "args": line.get("args"),
                }),
            )),
            Some("end") => running.retain(|(started, _)| *started != id),
            Some("close") => ended_cleanly = true,
            _ => {}
        }
    }
    Some(JournalRun {
        ended_cleanly,
        unfinished: running.into_iter().map(|(_, call)| call).collect(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    fn scratch(name: &str) -> PathBuf {
        let unique = SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!("mcmcp-crash-{name}-{unique}"));
        std::fs::create_dir_all(path.join("crash-reports")).unwrap();
        path
    }

    const REPORT: &str = "---- Minecraft Crash Report ----\n// Uh... Did I do that?\n\n\
        Time: 9/21/26 10:00 AM\nDescription: Unexpected error\n\n\
        java.lang.OutOfMemoryError: Direct buffer memory\n\tat java.nio.Bits.reserveMemory\n";

    #[test]
    fn a_crash_report_written_during_the_session_is_found_and_described() {
        let directory = scratch("found");
        let since = SystemTime::now() - Duration::from_secs(5);
        std::fs::write(
            directory.join("crash-reports/crash-2026-09-21_10.00.00-client.txt"),
            REPORT,
        )
        .unwrap();

        let report = newest_crash_report(&directory, since).expect("a report");
        assert_eq!(report.description.as_deref(), Some("Unexpected error"));
        assert_eq!(
            report.exception.as_deref(),
            Some("java.lang.OutOfMemoryError: Direct buffer memory")
        );
        assert!(
            report
                .summary()
                .contains("Unexpected error — java.lang.OutOfMemoryError")
        );
        let _ = std::fs::remove_dir_all(directory);
    }

    #[test]
    fn a_crash_report_older_than_the_session_is_not_blamed_for_it() {
        let directory = scratch("stale");
        std::fs::write(directory.join("crash-reports/crash-old-client.txt"), REPORT).unwrap();
        let since = SystemTime::now() + Duration::from_secs(60);
        assert_eq!(newest_crash_report(&directory, since), None);
        let _ = std::fs::remove_dir_all(directory);
    }

    #[test]
    fn a_jvm_error_log_is_found_only_for_the_process_that_left_it() {
        let directory = scratch("hserr");
        std::fs::write(directory.join("hs_err_pid4242.log"), "# A fatal error").unwrap();
        assert!(jvm_error_log(&directory, Some(4242)).is_some());
        assert!(jvm_error_log(&directory, Some(1)).is_none());
        assert!(jvm_error_log(&directory, None).is_none());
        let _ = std::fs::remove_dir_all(directory);
    }

    #[test]
    fn a_report_with_no_description_line_is_still_listed_without_one() {
        assert_eq!(describe("nothing useful\n"), (None, None));
    }
    #[test]
    fn a_call_the_journal_saw_start_and_never_end_is_reported_for_that_process() {
        let directory = scratch("journal");
        std::fs::create_dir_all(directory.join("mcmcp/journal")).unwrap();
        std::fs::write(
            directory.join("mcmcp/journal/client-requests.log"),
            concat!(
                "{\"t\":\"a\",\"ev\":\"open\",\"pid\":41}\n",
                "{\"t\":\"b\",\"ev\":\"start\",\"id\":1,\"tool\":\"old\"}\n",
                "{\"t\":\"c\",\"ev\":\"open\",\"pid\":42}\n",
                "{\"t\":\"d\",\"ev\":\"start\",\"id\":1,\"tool\":\"client_wait\"}\n",
                "{\"t\":\"e\",\"ev\":\"end\",\"id\":1,\"status\":\"ok\"}\n",
                "{\"t\":\"f\",\"ev\":\"start\",\"id\":2,\"tool\":\"client_get_blocks\"}\n",
                "{\"t\":\"g\",\"ev\":\"end\",\"id\":2,\"sta",
            ),
        )
        .unwrap();

        let runs = journal_last_run(&directory, Some(42)).expect("this process's run");
        let client = &runs[0];
        assert_eq!(client["side"], "client");
        assert_eq!(client["endedCleanly"], false);
        assert_eq!(client["unfinishedCount"], 1);
        assert_eq!(client["lastUnfinished"]["tool"], "client_get_blocks");
    }

    #[test]
    fn a_journal_from_another_process_is_not_blamed() {
        let directory = scratch("journal-other");
        std::fs::create_dir_all(directory.join("mcmcp/journal")).unwrap();
        std::fs::write(
            directory.join("mcmcp/journal/server-requests.log"),
            "{\"t\":\"a\",\"ev\":\"open\",\"pid\":7}\n{\"t\":\"b\",\"ev\":\"start\",\"id\":1,\"tool\":\"x\"}\n",
        )
        .unwrap();
        assert!(journal_last_run(&directory, Some(8)).is_none());
        assert!(journal_last_run(&directory, None).is_none());
    }
}
