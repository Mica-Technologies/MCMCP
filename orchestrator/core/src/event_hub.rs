//! One poll per game, however many WebSocket watchers there are.
//!
//! # Why
//!
//! Each watcher used to run its own [`crate::event_relay`], so N watchers held N long polls open in
//! every game. A game caps its waits, and a server reached through the companion allows only two per
//! player: a third watcher's polls came back busy, as `error` lines, for nothing it had done.
//!
//! The hub runs a single relay for the whole listener, unfiltered, and hands each line to every
//! watcher that wants it. Each watcher's filter is applied here instead of in the game, with the same
//! rules as the game's own filter (see [`matches`]). Notices about the stream itself — `link`,
//! `unsupported`, `reset`, `missed`, `error` — go to every watcher of that instance.
//!
//! # Joining late, and resuming
//!
//! The hub keeps the last [`RING_CAPACITY`] events of each game with their cursors, and how far back
//! that memory is complete. A watcher that joins with `resume` is served from it; one whose cursor is
//! older than the memory gets a `missed` notice saying at most how many it lost, then what there is.
//! `backlog` is served from the memory too.
//!
//! When the last watcher leaves, the hub keeps polling for [`LINGER`] and then stops. The linger is
//! for the commonest reconnect there is: a watch that expires and is re-armed with its last cursor a
//! moment later, which should lose nothing and cost no new polls. Once stopped, the hub forgets; the
//! next first watcher's cursors are where its polls start, so a watch re-armed after a long gap still
//! loses nothing the games remember.

use serde_json::{Value, json};
use std::collections::{BTreeMap, VecDeque};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use crate::event_relay::{
    self, Cursor, McpCall, POSITION_NOTICE, PollState, States, Subscription, parse_cursor,
};

/// Events kept per game for watchers that join late or resume.
pub const RING_CAPACITY: usize = 256;

/// How long polling carries on after the last watcher leaves.
pub const LINGER: Duration = Duration::from_secs(60);

/// Lines that may wait for one watcher. A watcher this far behind is dropped rather than letting
/// the hub hold an unbounded backlog for it; its socket closes and it can resume from its cursor.
const WATCHER_BUFFER: usize = 1024;

/// Whether an event passes a watcher's filter. The game's own rules, applied here:
///
/// - `types`: the event's `type`, exactly.
/// - `players`: its `player`, ignoring case. An event with no player never matches a players filter.
/// - `exclude_players`: never an event whose `player` is one of these, ignoring case.
/// - `contains`: its `text` contains this, ignoring case. An event with no text never matches.
pub fn matches(subscription: &Subscription, event: &Value) -> bool {
    let kind = event.get("type").and_then(Value::as_str).unwrap_or("");
    if !subscription.types.is_empty() && !subscription.types.iter().any(|wanted| wanted == kind) {
        return false;
    }
    let player = event.get("player").and_then(Value::as_str);
    if !subscription.players.is_empty() {
        let Some(player) = player else {
            return false;
        };
        if !subscription
            .players
            .iter()
            .any(|wanted| wanted.eq_ignore_ascii_case(player))
        {
            return false;
        }
    }
    if let Some(player) = player
        && subscription
            .exclude_players
            .iter()
            .any(|excluded| excluded.eq_ignore_ascii_case(player))
    {
        return false;
    }
    if let Some(needle) = &subscription.contains {
        let Some(text) = event.get("text").and_then(Value::as_str) else {
            return false;
        };
        if !text.to_lowercase().contains(&needle.to_lowercase()) {
            return false;
        }
    }
    true
}

/// What the hub knows of one game.
#[derive(Debug, Default)]
struct View {
    stream: Option<String>,
    /// Every event after this seq, up to the newest read, is in `ring`. `None` until known.
    covered_after: Option<u64>,
    /// Event lines, oldest first, as watchers receive them.
    ring: VecDeque<Value>,
    up: bool,
    unsupported: bool,
}

impl View {
    fn push(&mut self, event: Value, seq: u64) {
        if self.covered_after.is_none() {
            // Unfiltered polls return every event after where they started, so the first one seen
            // is where this memory begins.
            self.covered_after = Some(seq.saturating_sub(1));
        }
        self.ring.push_back(event);
        if self.ring.len() > RING_CAPACITY
            && let Some(evicted) = self.ring.pop_front()
        {
            self.covered_after = evicted.get("seq").and_then(Value::as_u64).or(self.covered_after);
        }
    }

    fn forget(&mut self) {
        self.ring.clear();
        self.covered_after = None;
    }
}

struct Watcher {
    subscription: Subscription,
    sender: mpsc::Sender<Value>,
}

struct Session {
    relay: JoinHandle<anyhow::Result<()>>,
    pump: JoinHandle<()>,
}

#[derive(Default)]
struct Inner {
    watchers: BTreeMap<u64, Watcher>,
    next_watcher: u64,
    views: BTreeMap<String, View>,
    session: Option<Session>,
    /// Bumped whenever a pending stop must not happen: a watcher joined, or another stop was set.
    generation: u64,
}

/// The shared poller behind the WebSocket listener.
pub struct Hub {
    call: Arc<dyn McpCall>,
    inner: Mutex<Inner>,
}

/// A watcher's place in the hub. Its lines arrive on `lines`; dropping it leaves.
pub struct Subscribed {
    id: u64,
    hub: Arc<Hub>,
    pub lines: mpsc::Receiver<Value>,
}

impl Drop for Subscribed {
    fn drop(&mut self) {
        self.hub.unsubscribe(self.id);
    }
}

impl Hub {
    pub fn new(call: Arc<dyn McpCall>) -> Arc<Self> {
        Arc::new(Hub {
            call,
            inner: Mutex::new(Inner::default()),
        })
    }

    /// How many watchers are connected.
    pub fn watchers(&self) -> usize {
        self.inner.lock().expect("hub lock").watchers.len()
    }

    /// Whether the hub is polling.
    pub fn polling(&self) -> bool {
        self.inner.lock().expect("hub lock").session.is_some()
    }

    /// Adds a watcher, catching it up from what the hub remembers, and starts polling if it was not.
    pub fn subscribe(self: &Arc<Self>, subscription: Subscription) -> Subscribed {
        let (sender, lines) = mpsc::channel(WATCHER_BUFFER);
        let mut inner = self.inner.lock().expect("hub lock");
        inner.generation += 1;
        let id = inner.next_watcher;
        inner.next_watcher += 1;

        if inner.session.is_none() {
            // Nothing remembered: the first watcher's cursors are where the polls start.
            inner.views.clear();
            let states = States::default();
            for (instance, cursor) in &subscription.resume {
                states.lock().expect("states lock").insert(
                    instance.clone(),
                    PollState {
                        stream: Some(cursor.stream.clone()),
                        since: Some(cursor.seq),
                    },
                );
                inner.views.insert(
                    instance.clone(),
                    View {
                        stream: Some(cursor.stream.clone()),
                        covered_after: Some(cursor.seq),
                        ..View::default()
                    },
                );
            }
            let polled = Subscription {
                resume: subscription.resume.clone(),
                backlog: subscription.backlog,
                positions: true,
                ..Subscription::default()
            };
            inner.session = Some(self.start(polled, states));
        } else {
            for (instance, view) in &inner.views {
                if subscription.wants(instance) {
                    for line in catch_up(&subscription, instance, view) {
                        let _ = sender.try_send(line);
                    }
                }
            }
        }
        inner.watchers.insert(id, Watcher { subscription, sender });
        Subscribed {
            id,
            hub: Arc::clone(self),
            lines,
        }
    }

    fn start(self: &Arc<Self>, polled: Subscription, states: States) -> Session {
        let (sink, mut lines) = mpsc::channel::<Value>(WATCHER_BUFFER);
        let relay = tokio::spawn(event_relay::run_with(
            Arc::clone(&self.call),
            polled,
            sink,
            states,
        ));
        let hub = Arc::downgrade(self);
        let pump = tokio::spawn(async move {
            while let Some(line) = lines.recv().await {
                let Some(hub) = hub.upgrade() else {
                    return;
                };
                hub.dispatch(line);
            }
        });
        Session { relay, pump }
    }

    fn unsubscribe(self: &Arc<Self>, id: u64) {
        let generation = {
            let mut inner = self.inner.lock().expect("hub lock");
            if inner.watchers.remove(&id).is_none() || !inner.watchers.is_empty() || inner.session.is_none() {
                return;
            }
            inner.generation += 1;
            inner.generation
        };
        let hub = Arc::downgrade(self);
        tokio::spawn(async move {
            tokio::time::sleep(LINGER).await;
            if let Some(hub) = hub.upgrade() {
                hub.stop_if_idle(generation);
            }
        });
    }

    fn stop_if_idle(&self, generation: u64) {
        let mut inner = self.inner.lock().expect("hub lock");
        if inner.generation != generation || !inner.watchers.is_empty() {
            return;
        }
        if let Some(session) = inner.session.take() {
            // Ending the pump closes the relay's sink, which is what its polls watch for.
            session.pump.abort();
            session.relay.abort();
        }
        inner.views.clear();
    }

    /// Takes one line from the relay: remembers it, and hands it to every watcher that wants it.
    fn dispatch(&self, line: Value) {
        let mut inner = self.inner.lock().expect("hub lock");
        let Some(instance) = line.get("instance").and_then(Value::as_str).map(str::to_string) else {
            return;
        };
        let kind = line.get("type").and_then(Value::as_str).unwrap_or("");
        let seq = line.get("seq").and_then(Value::as_u64);
        let view = inner.views.entry(instance.clone()).or_default();

        let is_event = match (kind, seq) {
            (POSITION_NOTICE, _) => {
                if let Some(stream) = line.get("stream").and_then(Value::as_str) {
                    view.stream = Some(stream.to_string());
                }
                if view.covered_after.is_none() {
                    view.covered_after = line.get("next").and_then(Value::as_u64);
                }
                return;
            }
            ("link", _) => {
                view.up = line.get("state").and_then(Value::as_str) == Some("up");
                view.unsupported = false;
                false
            }
            ("unsupported", _) => {
                view.unsupported = true;
                false
            }
            ("reset", _) | ("missed", _) => {
                // What the memory held no longer joins up with what comes next.
                view.forget();
                if let Some(stream) = line.get("stream").and_then(Value::as_str) {
                    view.stream = Some(stream.to_string());
                }
                false
            }
            (_, Some(seq)) => {
                if let Some(stream) = line
                    .get("cursor")
                    .and_then(Value::as_str)
                    .and_then(|cursor| parse_cursor(cursor).ok())
                    .map(|(_, cursor)| cursor.stream)
                {
                    if view.stream.as_deref() != Some(stream.as_str()) {
                        view.forget();
                    }
                    view.stream = Some(stream);
                }
                view.push(line.clone(), seq);
                true
            }
            _ => false,
        };

        let mut dropped = Vec::new();
        for (id, watcher) in &inner.watchers {
            if !watcher.subscription.wants(&instance) || (is_event && !matches(&watcher.subscription, &line))
            {
                continue;
            }
            if watcher.sender.try_send(line.clone()).is_err() {
                dropped.push(*id);
            }
        }
        for id in dropped {
            // Closed, or too far behind to keep up: its socket ends, and it can resume.
            inner.watchers.remove(&id);
        }
    }
}

/// What a watcher joining now is owed from one game: its state, then what the memory holds after the
/// watcher's cursor, or all of it for a backlog.
fn catch_up(subscription: &Subscription, instance: &str, view: &View) -> Vec<Value> {
    let mut lines = Vec::new();
    if view.up {
        lines.push(json!({"type": "link", "instance": instance, "state": "up"}));
    }
    if view.unsupported {
        lines.push(json!({
            "type": "unsupported",
            "instance": instance,
            "message": "this game's MCMCP has no game_events_wait; update the mod to get its events",
        }));
    }
    let after = match subscription.resume.get(instance) {
        Some(Cursor { stream, seq }) => {
            if view.stream.as_deref().is_some_and(|known| known != stream) {
                lines.push(json!({
                    "type": "reset",
                    "instance": instance,
                    "stream": view.stream,
                    "message": "the game restarted since the cursor; here is what is remembered since",
                }));
                0
            } else {
                match view.covered_after {
                    Some(covered) if *seq < covered => {
                        lines.push(json!({
                            "type": "missed",
                            "instance": instance,
                            "count": covered - seq,
                            "message": "the cursor is older than the events remembered; at most this \
                                        many were lost",
                        }));
                        0
                    }
                    _ => *seq,
                }
            }
        }
        None if subscription.backlog => 0,
        None => return lines,
    };
    lines.extend(
        view.ring
            .iter()
            .filter(|event| event.get("seq").and_then(Value::as_u64).unwrap_or(0) > after)
            .filter(|event| matches(subscription, event))
            .cloned(),
    );
    lines
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::event_relay::BoxFuture;
    use std::sync::atomic::{AtomicUsize, Ordering};

    fn chat(seq: u64, player: Option<&str>, text: &str) -> Value {
        let mut event = json!({
            "instance": "a.client", "seq": seq, "type": "chat", "text": text,
            "cursor": format!("a.client@s1:{seq}"),
        });
        if let Some(player) = player {
            event["player"] = json!(player);
        }
        event
    }

    #[test]
    fn the_local_filter_follows_the_games_rules() {
        let any = Subscription::default();
        let join = json!({"type": "join", "player": "Akselhok", "text": "joined"});
        assert!(matches(&any, &join));

        let types = Subscription {
            types: vec!["chat".into()],
            ..Default::default()
        };
        assert!(!matches(&types, &join));
        assert!(matches(&types, &chat(1, Some("P"), "hi")));

        let players = Subscription {
            players: vec!["akselhok".into()],
            ..Default::default()
        };
        assert!(matches(&players, &join), "players match ignoring case");
        assert!(
            !matches(&players, &chat(1, None, "server says hi")),
            "an event with no player never matches a players filter"
        );

        let excluded = Subscription {
            exclude_players: vec!["AKSELHOK".into()],
            ..Default::default()
        };
        assert!(!matches(&excluded, &join));
        assert!(
            matches(&excluded, &chat(1, None, "hi")),
            "excluding players keeps events with none"
        );

        let contains = Subscription {
            contains: Some("@claude".into()),
            ..Default::default()
        };
        assert!(matches(&contains, &chat(1, Some("P"), "@Claude the door")));
        assert!(!matches(&contains, &chat(1, Some("P"), "the door")));
        assert!(!matches(&contains, &json!({"type": "leave", "player": "P"})));
    }

    /// A pretend orchestrator with one game, whose polls are answered from a channel the test feeds.
    struct Fed {
        polls: AtomicUsize,
        answers: tokio::sync::Mutex<mpsc::Receiver<Value>>,
    }

    impl McpCall for Fed {
        fn call(&self, _method: &str, params: Value) -> BoxFuture<'_, anyhow::Result<Value>> {
            let listing = params["name"] == "mcmcp_instances";
            if !listing {
                self.polls.fetch_add(1, Ordering::SeqCst);
            }
            Box::pin(async move {
                if listing {
                    return Ok(json!({"structuredContent": {"connected": [
                        {"instance": "a.client", "ready": true}]}}));
                }
                match self.answers.lock().await.recv().await {
                    Some(answer) => Ok(answer),
                    None => {
                        tokio::time::sleep(Duration::from_secs(3600)).await;
                        anyhow::bail!("unreachable")
                    }
                }
            })
        }
    }

    fn fed() -> (Arc<Fed>, mpsc::Sender<Value>) {
        let (feed, answers) = mpsc::channel(16);
        (
            Arc::new(Fed {
                polls: AtomicUsize::new(0),
                answers: tokio::sync::Mutex::new(answers),
            }),
            feed,
        )
    }

    fn poll_answer(next: u64, events: Value) -> Value {
        json!({"structuredContent": {"stream": "s1", "next": next, "events": events}})
    }

    async fn next_line(subscribed: &mut Subscribed) -> Value {
        tokio::time::timeout(Duration::from_secs(5), subscribed.lines.recv())
            .await
            .expect("a line in time")
            .expect("the hub is still serving")
    }

    #[tokio::test]
    async fn two_watchers_share_one_poll_and_each_gets_only_what_it_asked_for() {
        let (call, feed) = fed();
        let hub = Hub::new(call.clone());
        let mut everything = hub.subscribe(Subscription::default());
        let mut tagged = hub.subscribe(Subscription {
            contains: Some("@claude".into()),
            ..Default::default()
        });

        assert_eq!(next_line(&mut everything).await["type"], "link");
        assert_eq!(next_line(&mut tagged).await["type"], "link");

        feed.send(poll_answer(
            2,
            json!([
                {"seq": 1, "type": "chat", "player": "P", "text": "hello"},
                {"seq": 2, "type": "chat", "player": "P", "text": "@Claude look"},
            ]),
        ))
        .await
        .unwrap();
        assert_eq!(next_line(&mut everything).await["seq"], 1);
        assert_eq!(next_line(&mut everything).await["seq"], 2);
        let only = next_line(&mut tagged).await;
        assert_eq!(only["seq"], 2);
        assert_eq!(only["cursor"], "a.client@s1:2");

        // One game, two watchers, one poll in flight: the second one is waiting for the next answer.
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert_eq!(call.polls.load(Ordering::SeqCst), 2);
        assert!(
            tokio::time::timeout(Duration::from_millis(50), tagged.lines.recv())
                .await
                .is_err(),
            "the filtered watcher got nothing more"
        );
    }

    #[tokio::test]
    async fn a_late_watcher_resumes_from_the_memory_and_is_told_what_it_cannot_have() {
        let (call, feed) = fed();
        let hub = Hub::new(call);
        let mut first = hub.subscribe(Subscription::default());
        next_line(&mut first).await;
        feed.send(poll_answer(
            12,
            json!([
                {"seq": 10, "type": "chat", "player": "P", "text": "ten"},
                {"seq": 11, "type": "chat", "player": "P", "text": "eleven"},
                {"seq": 12, "type": "join", "player": "Q"},
            ]),
        ))
        .await
        .unwrap();
        for _ in 0..3 {
            next_line(&mut first).await;
        }

        // Resuming after 10: link up, then 11 and 12.
        let mut resumed = Subscription::default();
        resumed.add_resume("a.client@s1:10").unwrap();
        let mut late = hub.subscribe(resumed);
        assert_eq!(next_line(&mut late).await["state"], "up");
        assert_eq!(next_line(&mut late).await["seq"], 11);
        assert_eq!(next_line(&mut late).await["seq"], 12);

        // A cursor from before the memory begins: told how many at most, then everything there is.
        let mut old = Subscription {
            types: vec!["chat".into()],
            ..Default::default()
        };
        old.add_resume("a.client@s1:4").unwrap();
        let mut older = hub.subscribe(old);
        assert_eq!(next_line(&mut older).await["type"], "link");
        let missed = next_line(&mut older).await;
        assert_eq!(missed["type"], "missed");
        assert_eq!(missed["count"], 5);
        assert_eq!(next_line(&mut older).await["seq"], 10);
        assert_eq!(next_line(&mut older).await["seq"], 11);

        // A cursor from a game since restarted: a reset, then what is remembered.
        let mut restarted = Subscription::default();
        restarted.add_resume("a.client@s0:99").unwrap();
        let mut stale = hub.subscribe(restarted);
        next_line(&mut stale).await;
        assert_eq!(next_line(&mut stale).await["type"], "reset");
        assert_eq!(next_line(&mut stale).await["seq"], 10);
    }

    #[tokio::test]
    async fn the_first_watchers_cursor_is_where_polling_starts() {
        let (call, _feed) = fed();
        struct Spy(Arc<Fed>, Mutex<Vec<Value>>);
        impl McpCall for Spy {
            fn call(&self, method: &str, params: Value) -> BoxFuture<'_, anyhow::Result<Value>> {
                self.1.lock().unwrap().push(params.clone());
                self.0.call(method, params)
            }
        }
        let spy = Arc::new(Spy(call, Mutex::new(Vec::new())));
        let hub = Hub::new(spy.clone());
        let mut resumed = Subscription {
            types: vec!["chat".into()],
            ..Default::default()
        };
        resumed.add_resume("a.client@s1:41").unwrap();
        let mut watcher = hub.subscribe(resumed);
        next_line(&mut watcher).await;
        tokio::time::sleep(Duration::from_millis(50)).await;
        let calls = spy.1.lock().unwrap().clone();
        let poll = calls
            .iter()
            .find(|call| call["name"] == crate::router::EVENTS_WAIT_TOOL)
            .expect("a poll");
        assert_eq!(poll["arguments"]["since"], 41);
        assert_eq!(poll["arguments"]["stream"], "s1");
        assert!(
            poll["arguments"].get("types").is_none(),
            "the hub polls unfiltered, whatever its watchers want"
        );
    }

    #[tokio::test(start_paused = true)]
    async fn polling_stops_a_while_after_the_last_watcher_leaves_and_not_before() {
        let (call, _feed) = fed();
        let hub = Hub::new(call);
        let mut watcher = hub.subscribe(Subscription::default());
        next_line(&mut watcher).await;
        assert!(hub.polling());

        drop(watcher);
        assert_eq!(hub.watchers(), 0);
        tokio::time::sleep(LINGER / 2).await;
        assert!(hub.polling(), "still polling within the linger");

        // Coming back within it keeps the same polls.
        let back = hub.subscribe(Subscription::default());
        tokio::time::sleep(LINGER * 2).await;
        assert!(hub.polling());
        drop(back);
        tokio::time::sleep(LINGER + Duration::from_secs(1)).await;
        assert!(!hub.polling(), "stopped once nobody came back");
    }
}
