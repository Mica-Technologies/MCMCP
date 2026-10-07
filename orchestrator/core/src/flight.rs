//! The flight recorder: a session's history, read back from the event log.
//!
//! # Why this exists
//!
//! "What did the agent do while I was away?" had no good answer. The event log held every call, but
//! as one flat stream across days, with nothing marking where a piece of work began or ended and no
//! way to see what the agent saw. This groups the log into sessions, keeps a thumbnail of every
//! screenshot beside the call that took it, and renders a session as one self-contained HTML page —
//! for reading later, or attaching to a bug report.
//!
//! # A session is a stretch of work
//!
//! Events by the model or a person, with no gap longer than [`SESSION_GAP_MS`] between them. The
//! orchestrator's own events — links coming and going, catalogues refreshing — never start or extend
//! a session, because a game relinking every hour overnight is not work; they are shown inside a
//! session when they fall within it.
//!
//! Nothing new is stored for this. The log already keeps every call with its arguments, task
//! changes and focus moves; this is a way of reading it.

use std::collections::BTreeSet;
use std::path::Path;

use base64::Engine;
use serde::Serialize;
use serde_json::Value;

use crate::events::{Actor, Event, EventKind, Level};

/// The longest quiet stretch inside one session.
pub const SESSION_GAP_MS: u64 = 30 * 60 * 1000;

/// One session, summarised for a list.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct SessionSummary {
    /// The first event's time, as text: stable, sortable, and what the app asks for again.
    pub id: String,
    pub start_ms: u64,
    pub end_ms: u64,
    pub calls: usize,
    pub errors: usize,
    pub screenshots: usize,
    pub instances: Vec<String>,
}

/// Reads the event log and its rotated predecessor, oldest first. Lines that will not parse are
/// skipped: a half-written last line is normal in a file a live process is appending to.
pub fn read_events(log: &Path) -> Vec<Event> {
    let mut events = Vec::new();
    for path in [log.with_extension("jsonl.1"), log.to_path_buf()] {
        let Ok(text) = std::fs::read_to_string(&path) else {
            continue;
        };
        events.extend(
            text.lines()
                .filter(|line| !line.trim().is_empty())
                .filter_map(|line| serde_json::from_str::<Event>(line).ok()),
        );
    }
    events.sort_by_key(|event| event.at);
    events
}

/// Every session in `events`, newest first. `events` must be in time order.
pub fn sessions(events: &[Event]) -> Vec<SessionSummary> {
    let mut spans: Vec<(u64, u64)> = Vec::new();
    for event in events.iter().filter(|event| event.actor != Actor::System) {
        match spans.last_mut() {
            Some((_, end)) if event.at.saturating_sub(*end) <= SESSION_GAP_MS => *end = event.at,
            _ => spans.push((event.at, event.at)),
        }
    }
    let mut summaries: Vec<SessionSummary> = spans
        .into_iter()
        .map(|(start, end)| summarise(start, end, in_window(events, start, end)))
        .collect();
    summaries.reverse();
    summaries
}

/// The events of the session starting at `id`, oldest first; empty if there is none.
pub fn session_events(events: &[Event], id: &str) -> Vec<Event> {
    let Some(summary) = sessions(events).into_iter().find(|summary| summary.id == id) else {
        return Vec::new();
    };
    in_window(events, summary.start_ms, summary.end_ms)
        .into_iter()
        .cloned()
        .collect()
}

fn in_window(events: &[Event], start: u64, end: u64) -> Vec<&Event> {
    events
        .iter()
        .filter(|event| event.at >= start && event.at <= end)
        .collect()
}

fn summarise(start: u64, end: u64, events: Vec<&Event>) -> SessionSummary {
    let mut calls = 0;
    let mut errors = 0;
    let mut screenshots = 0;
    let mut instances = BTreeSet::new();
    for event in &events {
        if let EventKind::ToolCall {
            is_error, thumbnail, ..
        } = &event.kind
        {
            calls += 1;
            errors += usize::from(*is_error);
            screenshots += usize::from(thumbnail.is_some());
            if let Some(instance) = &event.instance {
                instances.insert(instance.clone());
            }
        }
        if matches!(event.kind, EventKind::ToolBlocked { .. }) {
            errors += 1;
        }
    }
    SessionSummary {
        id: start.to_string(),
        start_ms: start,
        end_ms: end,
        calls,
        errors,
        screenshots,
        instances: instances.into_iter().collect(),
    }
}

/// Renders a session as one self-contained HTML page: styles inline, thumbnails as data URIs, no
/// script, nothing fetched. `thumbnail` returns a thumbnail's PNG bytes by name, if it still exists.
/// With `redact`, tool arguments are left out — for a report going somewhere the arguments should
/// not.
pub fn render_report(
    summary: &SessionSummary,
    events: &[Event],
    redact: bool,
    thumbnail: impl Fn(&str) -> Option<Vec<u8>>,
) -> String {
    let mut rows = String::new();
    for event in events {
        let class = match event.level {
            Level::Error => "error",
            Level::Warn => "warn",
            _ => "",
        };
        let mut detail = String::new();
        if let EventKind::ToolCall {
            arguments,
            detail: call_detail,
            thumbnail: name,
            ..
        } = &event.kind
        {
            if let Some(text) = call_detail {
                detail.push_str(&format!("<div class=\"detail\">{}</div>", escape(text)));
            }
            if !redact && !is_empty(arguments) {
                detail.push_str(&format!(
                    "<pre class=\"args\">{}</pre>",
                    escape(&arguments.to_string())
                ));
            }
            if let Some(png) = name.as_deref().and_then(&thumbnail) {
                detail.push_str(&format!(
                    "<img src=\"data:image/png;base64,{}\" alt=\"screenshot\">",
                    base64::engine::general_purpose::STANDARD.encode(png)
                ));
            }
        }
        rows.push_str(&format!(
            "<tr class=\"{class}\"><td class=\"time\">{}</td><td class=\"who\">{}</td><td>{}{detail}</td></tr>\n",
            clock(event.at),
            match event.actor {
                Actor::Model => "agent",
                Actor::Human => "you",
                Actor::System => "system",
            },
            escape(&event.summary()),
        ));
    }

    let minutes = (summary.end_ms.saturating_sub(summary.start_ms)) as f64 / 60_000.0;
    format!(
        "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\
         <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\
         <title>MCMCP session {start}</title><style>{STYLE}</style></head><body>\
         <h1>MCMCP session</h1>\
         <p class=\"meta\">{start} · {minutes:.0} min · {calls} · {errors} · {shots} · {instances}{redacted}</p>\
         <table>{rows}</table>\
         <p class=\"meta\">Written by the MCMCP Orchestrator from its event log.</p></body></html>\n",
        start = escape(&date_time(summary.start_ms)),
        calls = plural(summary.calls, "call"),
        errors = plural(summary.errors, "problem"),
        shots = plural(summary.screenshots, "screenshot"),
        instances = escape(&summary.instances.join(", ")),
        redacted = if redact { " · arguments left out" } else { "" },
    )
}

const STYLE: &str = "body{font:14px/1.5 -apple-system,'Segoe UI',system-ui,sans-serif;\
    background:#16171f;color:#e6e8f0;margin:24px auto;max-width:1100px;padding:0 16px}\
    h1{font-size:18px}.meta{color:#9aa0b4;font-size:13px}table{border-collapse:collapse;width:100%}\
    td{padding:6px 8px;border-bottom:1px solid #2b2e3b;vertical-align:top}\
    .time,.who{color:#9aa0b4;white-space:nowrap;font-family:Consolas,monospace;font-size:12.5px}\
    tr.warn td:last-child{color:#e6b455}tr.error td:last-child{color:#e57373}\
    .detail{color:#9aa0b4;font-size:12.5px}\
    pre.args{white-space:pre-wrap;word-break:break-all;color:#9aa0b4;font-size:12px;margin:4px 0}\
    img{display:block;max-width:320px;margin-top:6px;border-radius:6px;border:1px solid #2b2e3b}\
    @media (prefers-color-scheme: light){body{background:#fff;color:#1d1f27}td{border-color:#ddd}}";

fn plural(count: usize, noun: &str) -> String {
    format!("{count} {noun}{}", if count == 1 { "" } else { "s" })
}

fn is_empty(value: &Value) -> bool {
    match value {
        Value::Null => true,
        Value::Object(map) => map.is_empty(),
        _ => false,
    }
}

fn escape(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for character in text.chars() {
        match character {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&#39;"),
            other => out.push(other),
        }
    }
    out
}

/// HH:MM:SS in UTC. A report is read elsewhere and later; local time would be a guess.
fn clock(millis: u64) -> String {
    let seconds = millis / 1000;
    format!(
        "{:02}:{:02}:{:02}",
        (seconds / 3600) % 24,
        (seconds / 60) % 60,
        seconds % 60
    )
}

/// YYYY-MM-DD HH:MM UTC, from a civil-from-days conversion (no date crate for one line).
fn date_time(millis: u64) -> String {
    let seconds = millis / 1000;
    let days = (seconds / 86_400) as i64;
    let (year, month, day) = civil_from_days(days);
    format!(
        "{year:04}-{month:02}-{day:02} {:02}:{:02} UTC",
        (seconds / 3600) % 24,
        (seconds / 60) % 60
    )
}

/// Howard Hinnant's days-to-civil algorithm.
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let day_of_era = z.rem_euclid(146_097);
    let year_of_era = (day_of_era - day_of_era / 1460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_index = (5 * day_of_year + 2) / 153;
    let day = (day_of_year - (153 * month_index + 2) / 5 + 1) as u32;
    let month = if month_index < 10 {
        month_index + 3
    } else {
        month_index - 9
    } as u32;
    let year = year_of_era + era * 400 + i64::from(month <= 2);
    (year, month, day)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn event(at: u64, actor: Actor, kind: EventKind) -> Event {
        let mut event = Event::new(actor, Level::Info, Some("alpha.client".into()), kind);
        event.at = at;
        event
    }

    fn call(at: u64, is_error: bool, thumbnail: Option<&str>) -> Event {
        event(
            at,
            Actor::Model,
            EventKind::ToolCall {
                tool: "client_look".into(),
                arguments: json!({ "yaw": 90 }),
                is_error,
                duration_ms: 5,
                result_bytes: 100,
                detail: is_error.then(|| "it failed".to_string()),
                thumbnail: thumbnail.map(str::to_string),
            },
        )
    }

    fn note(at: u64, actor: Actor) -> Event {
        event(
            at,
            actor,
            EventKind::Note {
                message: "linked".into(),
            },
        )
    }

    const MINUTE: u64 = 60_000;

    #[test]
    fn a_long_quiet_stretch_starts_a_new_session() {
        let events = vec![
            call(0, false, None),
            call(10 * MINUTE, false, None),
            call(60 * MINUTE, true, None),
        ];
        let found = sessions(&events);
        assert_eq!(found.len(), 2);
        // Newest first.
        assert_eq!((found[0].calls, found[0].errors), (1, 1));
        assert_eq!(
            (found[1].calls, found[1].start_ms, found[1].end_ms),
            (2, 0, 10 * MINUTE)
        );
    }

    #[test]
    fn the_orchestrators_own_events_neither_start_nor_bridge_a_session() {
        // A game relinking every twenty minutes overnight is not work.
        let events = vec![
            call(0, false, None),
            note(20 * MINUTE, Actor::System),
            note(40 * MINUTE, Actor::System),
            call(55 * MINUTE, false, None),
            note(300 * MINUTE, Actor::System),
        ];
        let found = sessions(&events);
        assert_eq!(found.len(), 2);
        assert!(found.iter().all(|session| session.calls == 1));
    }

    #[test]
    fn a_session_includes_system_events_that_fall_inside_it() {
        let events = vec![
            call(0, false, None),
            note(MINUTE, Actor::System),
            call(2 * MINUTE, false, None),
        ];
        assert_eq!(session_events(&events, "0").len(), 3);
        assert!(session_events(&events, "12345").is_empty());
    }

    #[test]
    fn a_persons_edits_count_as_work() {
        let events = vec![note(0, Actor::Human)];
        assert_eq!(sessions(&events).len(), 1);
    }

    #[test]
    fn a_report_is_self_contained_and_escapes_what_it_shows() {
        let mut risky = call(0, false, Some("0-1.png"));
        if let EventKind::ToolCall { arguments, .. } = &mut risky.kind {
            *arguments = json!({ "text": "<script>alert(1)</script>" });
        }
        let events = vec![risky, call(MINUTE, true, None)];
        let summary = sessions(&events).remove(0);
        let html = render_report(&summary, &events, false, |name| {
            (name == "0-1.png").then(|| vec![137, 80, 78, 71])
        });
        assert!(html.starts_with("<!doctype html>"));
        assert!(!html.contains("<script>"), "arguments must be escaped");
        assert!(html.contains("&lt;script&gt;"));
        assert!(html.contains("data:image/png;base64,iVBORw=="));
        assert!(html.contains("it failed"));
        assert!(!html.contains("http"), "nothing is fetched from anywhere");
    }

    #[test]
    fn a_redacted_report_leaves_the_arguments_out_and_says_so() {
        let events = vec![call(0, false, None)];
        let summary = sessions(&events).remove(0);
        let html = render_report(&summary, &events, true, |_| None);
        assert!(!html.contains("yaw"));
        assert!(html.contains("arguments left out"));
    }

    #[test]
    fn dates_are_rendered_in_utc() {
        assert_eq!(date_time(0), "1970-01-01 00:00 UTC");
        // 2026-10-07 15:49 UTC.
        assert_eq!(date_time(1_791_388_140_000), "2026-10-07 15:49 UTC");
        assert_eq!(clock(1_791_388_140_000 + 37_000), "15:49:37");
    }

    #[test]
    fn the_log_and_its_rotated_predecessor_are_read_in_order() {
        let directory = std::env::temp_dir().join(format!("mcmcp-flight-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&directory);
        std::fs::create_dir_all(&directory).unwrap();
        let log = directory.join("events.jsonl");
        let line = |at| serde_json::to_string(&call(at, false, None)).unwrap();
        std::fs::write(log.with_extension("jsonl.1"), format!("{}\n", line(1))).unwrap();
        std::fs::write(&log, format!("{}\n{{ half a line", line(2))).unwrap();
        let events = read_events(&log);
        assert_eq!(
            events.iter().map(|event| event.at).collect::<Vec<_>>(),
            vec![1, 2]
        );
        let _ = std::fs::remove_dir_all(&directory);
    }
}
