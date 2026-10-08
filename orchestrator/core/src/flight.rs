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
//!
//! # Read once, then from where it left off
//!
//! The log runs to tens of thousands of lines, and a window showing it polls. Parsing all of it on
//! every poll cost a few hundred milliseconds each time, so [`LogCache`] parses it once and then
//! reads only what was appended since. A session is likewise handed out a page at a time
//! ([`session_page`]): one session of 17,000 calls was a 9.5 MB reply, and drawing every row of it
//! left a window holding nearly 3 GB.

use std::collections::BTreeSet;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;
use std::time::SystemTime;

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

/// One page of a session, for a window that shows a few hundred rows at a time.
#[derive(Debug, Clone, Serialize)]
pub struct SessionPage {
    /// Events in the session, after the instance filter.
    pub total: usize,
    /// Where `events` starts within those.
    pub start: usize,
    pub events: Vec<Event>,
    /// Every instance the session touched, for the filter.
    pub instances: Vec<String>,
    /// Where each warning and error sits within the filtered events, so a window can go to one it
    /// has not loaded.
    pub problems: Vec<usize>,
}

/// Reads the event log and its rotated predecessor, oldest first. Lines that will not parse are
/// skipped.
pub fn read_events(log: &Path) -> Vec<Event> {
    let mut cache = LogCache::default();
    cache.refresh(log);
    cache.events
}

/// The event log, parsed once and then read on from where it left off.
///
/// The log is only ever appended to, until it rotates: the live file is renamed over the old one
/// and a fresh one begun. So a change to the rotated file, or a live file shorter than what has
/// been read, means start again; anything else means read the new bytes.
#[derive(Debug, Default)]
pub struct LogCache {
    events: Vec<Event>,
    /// The rotated file's length and modification time when it was read.
    rotated: Option<(u64, SystemTime)>,
    /// How far into the live file has been read. Always at the end of a line.
    offset: u64,
}

impl LogCache {
    /// Brings the cache up to date with the files and returns every event, oldest first.
    pub fn refresh(&mut self, log: &Path) -> &[Event] {
        let previous = log.with_extension("jsonl.1");
        let rotated = stamp(&previous);
        let live = std::fs::metadata(log).map(|meta| meta.len()).unwrap_or(0);
        if rotated != self.rotated || live < self.offset {
            self.events = std::fs::read(&previous)
                .map(|bytes| parse_lines(&bytes))
                .unwrap_or_default();
            self.events.sort_by_key(|event| event.at);
            self.rotated = rotated;
            self.offset = 0;
        }
        if live > self.offset {
            self.read_on(log);
        }
        &self.events
    }

    /// Reads the live file from `offset`, as far as its last complete line. A half-written last
    /// line is normal in a file a live process is appending to; it is read whole next time.
    fn read_on(&mut self, log: &Path) {
        let mut bytes = Vec::new();
        let read = std::fs::File::open(log).and_then(|mut file| {
            file.seek(SeekFrom::Start(self.offset))?;
            file.read_to_end(&mut bytes)
        });
        if read.is_err() {
            return;
        }
        let Some(end) = bytes.iter().rposition(|byte| *byte == b'\n') else {
            return;
        };
        let added = parse_lines(&bytes[..=end]);
        self.offset += end as u64 + 1;
        self.events.extend(added);
        if !self.events.is_sorted_by_key(|event| event.at) {
            self.events.sort_by_key(|event| event.at);
        }
    }
}

fn stamp(path: &Path) -> Option<(u64, SystemTime)> {
    let meta = std::fs::metadata(path).ok()?;
    Some((meta.len(), meta.modified().ok()?))
}

fn parse_lines(bytes: &[u8]) -> Vec<Event> {
    String::from_utf8_lossy(bytes)
        .lines()
        .filter(|line| !line.trim().is_empty())
        .filter_map(|line| serde_json::from_str::<Event>(line).ok())
        .collect()
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
    in_window(events, summary.start_ms, summary.end_ms).to_vec()
}

/// Up to `limit` events of the session starting at `id`, from `start` within those `instance`
/// leaves, or the last `limit` when `start` is not given. `None` if there is no such session.
pub fn session_page(
    events: &[Event],
    id: &str,
    instance: Option<&str>,
    start: Option<usize>,
    limit: usize,
) -> Option<SessionPage> {
    let summary = sessions(events).into_iter().find(|summary| summary.id == id)?;
    let window = in_window(events, summary.start_ms, summary.end_ms);
    let instances: BTreeSet<&str> = window
        .iter()
        .filter_map(|event| event.instance.as_deref())
        .collect();
    let chosen: Vec<&Event> = window
        .iter()
        .filter(|event| instance.is_none_or(|wanted| event.instance.as_deref() == Some(wanted)))
        .collect();
    let total = chosen.len();
    let start = start.unwrap_or_else(|| total.saturating_sub(limit)).min(total);
    let end = start.saturating_add(limit).min(total);
    Some(SessionPage {
        total,
        start,
        events: chosen[start..end].iter().map(|event| (*event).clone()).collect(),
        instances: instances.into_iter().map(str::to_string).collect(),
        problems: chosen
            .iter()
            .enumerate()
            .filter(|(_, event)| is_problem(event))
            .map(|(index, _)| index)
            .collect(),
    })
}

/// A warning, an error, a call that failed or one that was blocked: what "Next problem" stops at.
fn is_problem(event: &Event) -> bool {
    matches!(event.level, Level::Warn | Level::Error)
        || matches!(event.kind, EventKind::ToolBlocked { .. })
        || matches!(event.kind, EventKind::ToolCall { is_error: true, .. })
}

/// The events from `start` to `end` inclusive. `events` is in time order, so this is two binary
/// searches rather than a pass over the whole log for every session.
fn in_window(events: &[Event], start: u64, end: u64) -> &[Event] {
    let from = events.partition_point(|event| event.at < start);
    let to = events.partition_point(|event| event.at <= end);
    &events[from..to.max(from)]
}

fn summarise(start: u64, end: u64, events: &[Event]) -> SessionSummary {
    let mut calls = 0;
    let mut errors = 0;
    let mut screenshots = 0;
    let mut instances = BTreeSet::new();
    for event in events {
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

    #[test]
    fn the_cache_reads_only_what_was_appended_and_starts_again_after_a_rotation() {
        let directory = std::env::temp_dir().join(format!("mcmcp-flight-cache-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&directory);
        std::fs::create_dir_all(&directory).unwrap();
        let log = directory.join("events.jsonl");
        let line = |at| format!("{}\n", serde_json::to_string(&call(at, false, None)).unwrap());
        let times = |cache: &mut LogCache| {
            cache
                .refresh(&log)
                .iter()
                .map(|event| event.at)
                .collect::<Vec<_>>()
        };

        let mut cache = LogCache::default();
        std::fs::write(&log, format!("{}{{ half a", line(1))).unwrap();
        assert_eq!(times(&mut cache), vec![1]);

        // The half line is finished, and another written: both are picked up, once each.
        std::fs::write(&log, format!("{}{}{}", line(1), line(2), line(3))).unwrap();
        assert_eq!(times(&mut cache), vec![1, 2, 3]);

        // Rotation: the live file becomes the old one and a new one begins.
        std::fs::rename(&log, log.with_extension("jsonl.1")).unwrap();
        std::fs::write(&log, line(4)).unwrap();
        assert_eq!(times(&mut cache), vec![1, 2, 3, 4]);
        let _ = std::fs::remove_dir_all(&directory);
    }

    #[test]
    fn a_session_is_handed_out_a_page_at_a_time_from_its_newest_end() {
        let mut events: Vec<Event> = (0..10).map(|at| call(at * MINUTE, at == 2, None)).collect();
        events[7].instance = Some("beta.client".into());
        let id = sessions(&events)[0].id.clone();

        let page = session_page(&events, &id, None, None, 4).unwrap();
        assert_eq!((page.total, page.start), (10, 6));
        assert_eq!(
            page.events
                .iter()
                .map(|event| event.at / MINUTE)
                .collect::<Vec<_>>(),
            vec![6, 7, 8, 9]
        );
        assert_eq!(page.instances, vec!["alpha.client", "beta.client"]);
        assert_eq!(page.problems, vec![2]);

        let earlier = session_page(&events, &id, None, Some(2), 4).unwrap();
        assert_eq!(earlier.events.first().map(|event| event.at), Some(2 * MINUTE));

        let filtered = session_page(&events, &id, Some("beta.client"), None, 4).unwrap();
        assert_eq!((filtered.total, filtered.events.len()), (1, 1));
        assert!(session_page(&events, "nope", None, None, 4).is_none());
    }
}
