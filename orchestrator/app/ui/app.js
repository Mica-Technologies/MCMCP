/*
 * The whole UI.
 *
 * No framework and no build step: this is a roster, a log and two kinds of prompt, and a bundler
 * would cost a second toolchain in the release pipeline for state management there is not enough
 * state to need. `withGlobalTauri` puts the API on `window.__TAURI__`, so there is no package to
 * install either.
 *
 * The refresh model is deliberately dumb: the backend emits one "something changed" event and this
 * re-reads what is on screen. Keeping a client-side model in step with a server-side one is where
 * bugs live, and this is a panel somebody uses to decide whether to let a model reshape their world
 * — being obviously correct beats being clever.
 */

const invoke = window.__TAURI__.core.invoke;
const listen = window.__TAURI__.event.listen;

/* Only the log filters hold state, because they are the one thing a refresh must not clobber. */
const filters = { text: "", instance: "", actor: "", level: "" };
let activePanel = "instances";

/* The latest activity snapshot, pushed by the backend on every change (at most ten a second). The
 * one place this file keeps a model of server state, and only because progress moves too fast to
 * re-read everything for: cards and the detail view paint from it without touching the rest. */
let activity = { running: [], recent: [] };
/* The endpoint the detail view is showing, and the roster as last read, for its header. */
let detailInstance = null;
let lastInstances = [];

const $ = (id) => document.getElementById(id);

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[character]);
}

function toast(message) {
  const existing = document.querySelector(".toast");
  if (existing) existing.remove();
  const element = document.createElement("div");
  element.className = "toast";
  element.textContent = message;
  document.body.appendChild(element);
  setTimeout(() => element.remove(), 2600);
}

/* Errors from a command are shown, never swallowed. A revoke that quietly failed would leave
 * somebody believing they had disconnected a game that is still being driven. */
async function call(command, args) {
  try {
    return await invoke(command, args);
  } catch (error) {
    toast(String(error));
    throw error;
  }
}

/* A colour per game, from its id: stable across restarts, shared by both endpoints of one
 * singleplayer world, and different enough between games to tell three cards apart at a glance. */
function colourFor(game) {
  let hash = 0;
  for (const character of String(game ?? "")) hash = (hash * 31 + character.codePointAt(0)) >>> 0;
  return `hsl(${hash % 360} 62% 64%)`;
}

/* The game an endpoint belongs to, from the roster; the endpoint itself when it is not listed. */
function gameOf(instance) {
  return lastInstances.find((candidate) => candidate.instance === instance)?.game ?? instance;
}

function formatDuration(millis) {
  if (millis < 1000) return `${Math.round(millis)} ms`;
  if (millis < 60_000) return `${(millis / 1000).toFixed(1)} s`;
  const minutes = Math.floor(millis / 60_000);
  return `${minutes} m ${Math.round((millis % 60_000) / 1000)} s`;
}

function formatAgo(millis) {
  const seconds = Math.max(0, Math.round((Date.now() - millis) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  return `${Math.floor(seconds / 3600)}h ago`;
}

/* "12 / 40" or "12" — and a bar that fills when the total is known and sweeps when it is not. */
function progressText(progress) {
  if (!progress) return "";
  const done = Number.isInteger(progress.progress) ? progress.progress : progress.progress.toFixed(1);
  return progress.total != null ? `${done} / ${progress.total}` : `${done}`;
}

function progressBar(progress) {
  const known = progress && progress.total != null && progress.total > 0;
  const percent = known ? Math.min(100, (progress.progress / progress.total) * 100) : 0;
  return `<div class="bar-track"><div class="bar-fill ${known ? "" : "is-unknown"}"
    style="width:${percent}%"></div></div>`;
}

// ---------------------------------------------------------------------------------
// Prompts
// ---------------------------------------------------------------------------------

async function renderPrompts() {
  const [approvals, gates] = await Promise.all([
    invoke("pending_approvals"),
    invoke("pending_gates"),
  ]);
  const container = $("prompts");
  container.hidden = approvals.length === 0 && gates.length === 0;
  container.innerHTML = "";

  for (const approval of approvals) {
    const element = document.createElement("div");
    element.className = "prompt";
    element.innerHTML = `
      <h3>A Minecraft instance wants to connect</h3>
      <p class="muted">Approve it only if you recognise it. It will be remembered.</p>
      <dl>
        <dt>Name</dt><dd>${escapeHtml(approval.label)}</dd>
        <dt>Id</dt><dd>${escapeHtml(approval.instance)}</dd>
        <dt>Folder</dt><dd>${escapeHtml(approval.game_directory ?? "(not reported)")}</dd>
        <dt>Side</dt><dd>${escapeHtml(approval.side)}</dd>
        <dt>Versions</dt><dd>Minecraft ${escapeHtml(approval.minecraft_version)} · MCMCP ${escapeHtml(approval.mod_version)}</dd>
      </dl>
      <div class="actions">
        <button class="primary" data-answer="approve">Approve</button>
        <button data-answer="later">Not now</button>
        <button class="danger" data-answer="never">Never</button>
      </div>`;
    for (const button of element.querySelectorAll("[data-answer]")) {
      button.addEventListener("click", async () => {
        await call("answer_approval", { id: approval.id, answer: button.dataset.answer });
      });
    }
    container.appendChild(element);
  }

  for (const gate of gates) {
    const element = document.createElement("div");
    element.className = "prompt is-gate";
    element.innerHTML = `
      <h3>Allow <code>${escapeHtml(gate.tool)}</code>?</h3>
      <p class="muted">${escapeHtml(gate.reason)}</p>
      <dl>
        <dt>Instance</dt><dd>${escapeHtml(gate.instance)}</dd>
        <dt>Arguments</dt><dd>${escapeHtml(JSON.stringify(gate.arguments))}</dd>
      </dl>
      <div class="actions">
        <button class="primary" data-allow="true">Allow once</button>
        <button class="danger" data-allow="false">Block</button>
      </div>`;
    for (const button of element.querySelectorAll("[data-allow]")) {
      button.addEventListener("click", async () => {
        await call("answer_gate", { id: gate.id, allow: button.dataset.allow === "true" });
      });
    }
    container.appendChild(element);
  }
}

// ---------------------------------------------------------------------------------
// Instances
// ---------------------------------------------------------------------------------

async function renderInstances() {
  const instances = await invoke("list_instances");
  const connected = instances.filter((instance) => instance.connected);

  // An orchestrator that could not bind the link port has an empty roster for a different reason
  // than one nobody has connected to: the games are all connected — to whichever process did bind
  // it. Saying "no game connected" there is true of this window and wrong about the machine.
  const failure = await invoke("link_failure");
  const dot = document.querySelector(".dot");
  dot.classList.toggle("is-live", !failure && connected.length > 0);
  dot.classList.toggle("is-down", Boolean(failure));
  $("subtitle").textContent = failure
    ? "not listening — another orchestrator has the port"
    : connected.length === 0
      ? "no game connected"
      : `${connected.length} connected · ${connected.reduce((sum, i) => sum + i.tools, 0)} tools`;
  $("subtitle").title = failure ?? "";
  $("link-failure").hidden = !failure;
  $("link-failure-detail").textContent = failure ?? "";

  const container = $("instances");
  container.innerHTML = "";
  $("instances-empty").hidden = instances.length > 0;

  for (const instance of instances) {
    const card = document.createElement("div");
    card.className = "card";
    card.dataset.instance = instance.instance;
    card.style.setProperty("--instance-colour", colourFor(instance.game));
    if (instance.focused) card.classList.add("is-focused");
    if (!instance.connected) card.classList.add("is-offline");

    const badges = [];
    if (instance.connected) badges.push('<span class="badge is-live">connected</span>');
    else if (instance.revoked) badges.push('<span class="badge is-revoked">revoked</span>');
    else badges.push('<span class="badge">not running</span>');
    if (instance.focused) badges.push('<span class="badge is-focused">focused</span>');

    card.innerHTML = `
      <div class="card-head">
        <div>
          <div class="card-name">${escapeHtml(instance.label)}</div>
          <div class="card-id">${escapeHtml(instance.instance)}</div>
        </div>
        <div>${badges.join(" ")}</div>
      </div>
      <dl>
        <dt>Folder</dt><dd>${escapeHtml(instance.game_directory ?? "—")}</dd>
        ${instance.connected ? `<dt>Side</dt><dd>${escapeHtml(instance.side)}</dd>` : ""}
        ${instance.connected ? `<dt>Tools</dt><dd>${instance.tools}</dd>` : ""}
        ${instance.http_endpoint ? `<dt>Direct</dt><dd>${escapeHtml(instance.http_endpoint)}</dd>` : ""}
        ${instance.pid != null ? `<dt>Process</dt><dd>${instance.pid}${instance.started_at ? ` · up since ${escapeHtml(instance.started_at)}` : ""}</dd>` : ""}
      </dl>
      ${instance.connected ? '<div class="activity-line"></div>' : ""}
      <div class="actions"></div>`;

    // Anywhere on the card but its controls opens the detail view.
    card.addEventListener("click", (event) => {
      if (event.target.closest("button, input")) return;
      openDetail(instance.instance);
    });

    const actions = card.querySelector(".actions");

    if (instance.connected && !instance.focused) {
      const focus = document.createElement("button");
      focus.textContent = "Focus";
      focus.title = "Send calls that do not name an instance to this one";
      focus.addEventListener("click", () => call("set_focus", { instance: instance.instance }));
      actions.appendChild(focus);
    }

    const rename = document.createElement("button");
    rename.textContent = "Rename";
    rename.addEventListener("click", async () => {
      // prompt() is blocked in a Tauri webview, so the rename happens in place.
      const field = document.createElement("input");
      field.value = instance.label;
      field.className = "rename";
      const commit = async () => {
        const label = field.value.trim();
        field.replaceWith(rename);
        if (label && label !== instance.label) {
          await call("set_label", { instance: instance.instance, label });
        }
      };
      field.addEventListener("keydown", (event) => {
        if (event.key === "Enter") commit();
        if (event.key === "Escape") field.replaceWith(rename);
      });
      field.addEventListener("blur", commit);
      rename.replaceWith(field);
      field.focus();
      field.select();
    });
    actions.appendChild(rename);

    if (instance.revoked) {
      const approve = document.createElement("button");
      approve.className = "primary";
      approve.textContent = "Un-revoke";
      approve.addEventListener("click", () => call("approve_known", { instance: instance.instance }));
      actions.appendChild(approve);
    } else {
      const revoke = document.createElement("button");
      revoke.className = "danger";
      revoke.textContent = "Revoke";
      revoke.title = "Disconnect it now and refuse it until you approve it again";
      revoke.addEventListener("click", () => call("revoke", { instance: instance.instance }));
      actions.appendChild(revoke);
    }

    container.appendChild(card);
  }
  lastInstances = instances;
  paintCardActivity();

  // The log's instance filter is populated from the same list, so it never offers a stale id.
  const select = $("log-instance");
  const chosen = select.value;
  select.innerHTML = '<option value="">All instances</option>';
  for (const instance of instances) {
    const option = document.createElement("option");
    option.value = instance.instance;
    // Labelled by side as well as name: a singleplayer world is two rows sharing one label, and a
    // filter offering the same word twice is a filter nobody can use.
    option.textContent = instance.side
      ? `${instance.label} (${instance.side})`
      : instance.label;
    select.appendChild(option);
  }
  select.value = chosen;
}

// ---------------------------------------------------------------------------------
// Log
// ---------------------------------------------------------------------------------

function formatTime(millis) {
  const date = new Date(millis);
  const pad = (value) => String(value).padStart(2, "0");
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

async function renderLog() {
  const events = await invoke("list_events", {
    query: {
      instance: filters.instance || null,
      actor: filters.actor || null,
      text: filters.text || null,
      min_level: filters.level || null,
      limit: 400,
    },
  });

  const container = $("log");
  container.innerHTML = "";
  $("log-empty").hidden = events.length > 0;

  for (const event of events) {
    const row = document.createElement("div");
    row.className = `row actor-${event.actor} level-${event.level}`;
    if (event.instance) row.style.setProperty("--instance-colour", colourFor(gameOf(event.instance)));
    row.innerHTML = `
      <span class="time">${event.instance ? '<span class="swatch"></span>' : ""}${formatTime(event.at)}</span>
      <span class="who">${escapeHtml(event.actor)}</span>
      <span class="summary">${escapeHtml(event.summary)}</span>`;

    // The one people actually reach for: something misbehaved and they want to paste it back into
    // the conversation that caused it.
    const copy = document.createElement("button");
    copy.className = "copy";
    copy.textContent = "copy";
    copy.addEventListener("click", async () => {
      const { summary, ...record } = event;
      await navigator.clipboard.writeText(JSON.stringify(record, null, 2));
      toast("Event copied as JSON");
    });
    row.appendChild(copy);
    container.appendChild(row);
  }

  // Pinned to the newest unless the reader has scrolled up to look at something.
  const nearBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 80;
  if (nearBottom) container.scrollTop = container.scrollHeight;
}

// ---------------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------------

/* Each card's one line: what is running, or the last thing that ran. */
function paintCardActivity() {
  for (const line of document.querySelectorAll(".card .activity-line")) {
    const instance = line.closest(".card").dataset.instance;
    const running = activity.running.filter((call) => call.instance === instance);
    if (running.length > 0) {
      const call = running[running.length - 1];
      const more = running.length > 1 ? ` (+${running.length - 1} more)` : "";
      const message = call.progress?.message ? ` · ${escapeHtml(call.progress.message)}` : "";
      line.innerHTML = `
        <div class="now"><span class="tool">${escapeHtml(call.tool)}</span>
          ${escapeHtml(progressText(call.progress))}${message}${more}</div>
        ${progressBar(call.progress)}`;
      continue;
    }
    const last = activity.recent.find((call) => call.instance === instance);
    line.innerHTML = last
      ? `last: ${escapeHtml(last.tool)} · ${formatDuration(last.duration_ms)} ·
         <span class="outcome-${last.outcome}">${last.outcome.replace("_", " ")}</span> ·
         ${formatAgo(last.started_at_ms + last.duration_ms)}`
      : "idle";
  }
}

function onActivity(snapshot) {
  const newestBefore = activity.recent[0]?.id;
  // The detail view's complete list survives a push, which carries only the newest calls.
  activity = { ...snapshot, detailRecent: activity.detailRecent };
  if (activePanel === "instances") paintCardActivity();
  if (activePanel === "detail") renderDetailActivity();
  if (activePanel === "tasks") paintTaskActivity();
  // A finished call is a new line in the log. The log is otherwise re-read on the slow poll, which
  // left a call that had plainly finished missing from it for up to four seconds.
  if (activePanel === "log" && snapshot.recent[0]?.id !== newestBefore) renderLog();
}

// ---------------------------------------------------------------------------------
// Detail
// ---------------------------------------------------------------------------------

function openDetail(instance) {
  detailInstance = instance;
  activity = { ...activity, detailRecent: [] };
  $("detail-gamelog").textContent = "";
  showPanel("detail");
  loadGameLog();
}

/* The header, alerts, messages and the full recent list: what changes rarely. */
async function renderDetail() {
  if (!detailInstance) return;
  const view = lastInstances.find((candidate) => candidate.instance === detailInstance);
  const detail = await invoke("instance_detail", { instance: detailInstance });

  const head = document.querySelector(".detail-head");
  head.style.setProperty("--instance-colour", colourFor(view?.game ?? detailInstance));
  $("detail-name").textContent = view?.label ?? detailInstance;
  $("detail-id").textContent = view?.side ? `${detailInstance} · ${view.side}` : detailInstance;
  $("detail-badges").innerHTML = view?.connected
    ? `<span class="badge is-live">connected</span>${view.focused ? ' <span class="badge is-focused">focused</span>' : ""}`
    : '<span class="badge">not running</span>';

  const alert = $("detail-alert");
  if (detail.stalled_seconds != null) {
    alert.hidden = false;
    alert.textContent = `The game thread has been stalled for ${formatDuration(detail.stalled_seconds * 1000)}. ` +
      "Calls that need it will wait or time out; off-thread tools still answer.";
  } else if (detail.last_exit) {
    const exit = detail.last_exit;
    const report = exit.crashReport;
    const crash = report
      ? ` It crashed: ${[report.description, report.exception].filter(Boolean).join(" — ") || "see the report"} (${report.path}).`
      : "";
    alert.hidden = false;
    alert.textContent = `This game ended ${exit.endedSecondsAgo}s ago.${crash}`;
  } else {
    alert.hidden = true;
  }

  const messages = $("detail-messages");
  messages.innerHTML = "";
  $("detail-messages-empty").hidden = detail.messages.length > 0;
  for (const line of detail.messages.slice(-100)) {
    const row = document.createElement("div");
    const level = line.level === "warning" ? "warn" : line.level === "error" ? "error" : "info";
    row.className = `row message-row level-${level}`;
    row.innerHTML = `
      <span class="time">${formatTime(line.at_ms)}</span>
      <span class="who">${escapeHtml(line.level)}</span>
      <span class="summary">${escapeHtml(line.text)}</span>`;
    messages.appendChild(row);
  }

  // The pushed snapshot carries only the newest calls across every instance; this one is complete.
  activity = {
    ...activity,
    detailRecent: detail.activity.recent,
  };
  renderDetailActivity();
}

/* What is running and what just finished: repainted on every activity event. */
function renderDetailActivity() {
  if (!detailInstance) return;
  const running = activity.running.filter((call) => call.instance === detailInstance);
  const container = $("detail-running");
  container.innerHTML = running.length === 0 ? '<p class="muted">Nothing is running.</p>' : "";
  for (const call of running) {
    const element = document.createElement("div");
    element.className = "running-call";
    element.innerHTML = `
      <div class="head">
        <span class="tool">${escapeHtml(call.tool)}</span>
        <span>${escapeHtml(progressText(call.progress))} · ${formatDuration(Date.now() - call.started_at_ms)}</span>
      </div>
      ${progressBar(call.progress)}
      ${call.progress?.message ? `<div class="message">${escapeHtml(call.progress.message)}</div>` : ""}
      ${call.arguments ? `<div class="args">${escapeHtml(call.arguments)}</div>` : ""}`;
    container.appendChild(element);
  }

  // Newest pushed calls first, then the rest of the full list read when the view opened.
  const pushed = activity.recent.filter((call) => call.instance === detailInstance);
  const seen = new Set(pushed.map((call) => call.id));
  const recent = pushed.concat((activity.detailRecent ?? [])
    .filter((call) => call.instance === detailInstance && !seen.has(call.id)));

  const list = $("detail-recent");
  const open = new Set([...list.querySelectorAll(".recent-row.is-open")].map((row) => row.dataset.id));
  list.innerHTML = "";
  $("detail-recent-empty").hidden = recent.length > 0;
  for (const call of recent) {
    const row = document.createElement("div");
    row.className = `row recent-row ${call.outcome === "ok" ? "" : "level-error"}`;
    row.dataset.id = call.id;
    const detail = call.error ?? (call.last_progress
      ? `${progressText(call.last_progress)}${call.last_progress.message ? ` · ${call.last_progress.message}` : ""}`
      : "");
    row.innerHTML = `
      <span class="time">${formatTime(call.started_at_ms)}</span>
      <span class="who-tool">${escapeHtml(call.tool)}</span>
      <span>${formatDuration(call.duration_ms)}</span>
      <span class="outcome-${call.outcome}">${call.outcome.replace("_", " ")}</span>
      <span class="detail">${escapeHtml(detail)}</span>`;
    const toggle = (expand) => {
      row.classList.toggle("is-open", expand);
      row.querySelector(".args")?.remove();
      if (expand && call.arguments) {
        const args = document.createElement("span");
        args.className = "args";
        args.textContent = call.arguments;
        row.appendChild(args);
      }
    };
    row.addEventListener("click", () => toggle(!row.classList.contains("is-open")));
    if (open.has(String(call.id))) toggle(true);
    list.appendChild(row);
  }
}

async function loadGameLog() {
  if (!detailInstance) return;
  const view = lastInstances.find((candidate) => candidate.instance === detailInstance);
  const target = $("detail-gamelog");
  if (view && !view.connected) {
    target.textContent = "Not running, so its log cannot be read through the link.";
    return;
  }
  try {
    target.textContent = (await invoke("game_log_tail", { instance: detailInstance, lines: 150 })) || "(empty)";
    target.scrollTop = target.scrollHeight;
  } catch (error) {
    target.textContent = `Could not read the log: ${error}`;
  }
}

// ---------------------------------------------------------------------------------
// Tasks
// ---------------------------------------------------------------------------------

const STATUSES = ["todo", "doing", "done", "blocked", "skipped"];
let taskLists = [];
/* A delete needs a second click within a few seconds: browser dialogs block the webview, and a
 * list deleted by one stray click is gone for good. */
let armedDelete = null;

async function renderTasks() {
  taskLists = await invoke("task_lists");
  const showArchived = $("task-show-archived").checked;
  const instanceFilter = $("task-instance").value;

  // The instance filter offers every instance any list names, plus the connected ones.
  const select = $("task-instance");
  const chosen = select.value;
  const names = new Set(lastInstances.filter((i) => i.connected).map((i) => i.instance));
  for (const list of taskLists) for (const instance of list.instances ?? []) names.add(instance);
  select.innerHTML = '<option value="">All instances</option>';
  for (const name of [...names].sort()) {
    const option = document.createElement("option");
    option.value = name;
    option.textContent = name;
    select.appendChild(option);
  }
  select.value = names.has(chosen) ? chosen : "";

  // A re-render must not swallow what somebody is typing into an "add a task" box.
  const focused = document.activeElement?.id?.startsWith("task-add-") ? document.activeElement : null;
  const typing = focused ? { id: focused.id, value: focused.value } : null;

  const visible = taskLists.filter((list) =>
    (showArchived || !list.archived) &&
    (!select.value || (list.instances ?? []).includes(select.value)));
  const container = $("task-lists");
  container.innerHTML = "";
  $("tasks-empty").hidden = visible.length > 0;

  for (const list of visible) container.appendChild(renderTaskList(list));

  if (typing) {
    const field = $(typing.id);
    if (field) {
      field.value = typing.value;
      field.focus();
    }
  }
  paintTaskActivity();
}

function renderTaskList(list) {
  const settled = list.tasks.filter((task) => task.status === "done" || task.status === "skipped").length;
  const element = document.createElement("div");
  element.className = `task-list${list.archived ? " is-archived" : ""}`;
  element.dataset.list = list.id;
  const chips = (list.instances ?? []).map((instance) =>
    `<span class="chip" style="--instance-colour:${colourFor(gameOf(instance))}"><span class="swatch"></span>${escapeHtml(instance)}</span>`);
  element.innerHTML = `
    <div class="task-list-head">
      <div>
        <div class="task-list-title">${escapeHtml(list.title)}</div>
        <div class="task-list-meta">
          <span>${settled}/${list.tasks.length} done</span>
          <span>updated ${formatAgo(list.updated_ms)}</span>
          <code>${escapeHtml(list.id)}</code>
          ${chips.join("")}
        </div>
      </div>
      <div class="actions"></div>
    </div>
    ${list.tasks.length ? progressBar({ progress: settled, total: list.tasks.length }) : ""}
    <div class="tasks"></div>
    ${list.archived ? "" : `<div class="task-add">
      <input id="task-add-${escapeHtml(list.id)}" type="text" placeholder="Add a task…" maxlength="200" />
      <button>Add</button></div>`}`;

  const actions = element.querySelector(".actions");
  const archive = document.createElement("button");
  archive.textContent = list.archived ? "Restore" : "Archive";
  archive.addEventListener("click", () => call("task_archive", { list: list.id, archived: !list.archived }));
  actions.appendChild(archive);

  const remove = document.createElement("button");
  remove.className = "danger";
  remove.textContent = armedDelete === list.id ? "Really delete?" : "Delete";
  remove.classList.toggle("is-armed", armedDelete === list.id);
  remove.title = "Delete the list and its file. Agents cannot do this.";
  remove.addEventListener("click", async () => {
    if (armedDelete !== list.id) {
      armedDelete = list.id;
      renderTasks();
      setTimeout(() => {
        if (armedDelete === list.id) {
          armedDelete = null;
          renderTasks();
        }
      }, 3500);
      return;
    }
    armedDelete = null;
    await call("task_delete", { list: list.id });
    renderTasks();
  });
  actions.appendChild(remove);

  const rows = element.querySelector(".tasks");
  for (const task of list.tasks) {
    const row = document.createElement("div");
    row.className = `task status-${task.status}`;
    row.dataset.status = task.status;
    row.innerHTML = `
      <span class="task-id">${escapeHtml(task.id)}</span>
      <select>${STATUSES.map((status) =>
        `<option value="${status}"${status === task.status ? " selected" : ""}>${status}</option>`).join("")}</select>
      <span class="task-title" title="${task.updated_by === "human" ? "last changed by you" : "last changed by the agent"}">${escapeHtml(task.title)}</span>
      ${task.note ? `<span class="task-note">${escapeHtml(task.note)}</span>` : ""}
      <span class="task-activity"></span>`;
    row.querySelector("select").addEventListener("change", (event) =>
      call("task_update", { list: list.id, task: task.id, status: event.target.value }));
    rows.appendChild(row);
  }

  const add = element.querySelector(".task-add");
  if (add) {
    const field = add.querySelector("input");
    const submit = async () => {
      const title = field.value.trim();
      if (!title) return;
      field.value = "";
      await call("task_add", { list: list.id, title });
    };
    add.querySelector("button").addEventListener("click", submit);
    field.addEventListener("keydown", (event) => { if (event.key === "Enter") submit(); });
  }
  return element;
}

/* Beneath a task being worked on: what its list's instances are running right now. This is what
 * ties the plan to the work without the agent doing anything. */
function paintTaskActivity() {
  for (const element of document.querySelectorAll(".task-list")) {
    const list = taskLists.find((candidate) => candidate.id === element.dataset.list);
    const instances = list?.instances ?? [];
    for (const row of element.querySelectorAll(".task")) {
      const slot = row.querySelector(".task-activity");
      const call = row.dataset.status === "doing"
        ? activity.running.find((running) => instances.includes(running.instance))
        : null;
      slot.innerHTML = call
        ? `<span>${escapeHtml(call.tool)} ${escapeHtml(progressText(call.progress))}${call.progress?.message ? ` · ${escapeHtml(call.progress.message)}` : ""}</span>${progressBar(call.progress)}`
        : "";
    }
  }
}

// ---------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------

const CLASSES = ["read_only", "mutating", "destructive"];
const RULES = ["allow", "ask", "deny"];

function gatingRow(label, instance, rules) {
  const row = document.createElement("tr");
  const name = document.createElement("td");
  name.textContent = label;
  row.appendChild(name);

  for (const className of CLASSES) {
    const cell = document.createElement("td");
    const select = document.createElement("select");
    for (const rule of RULES) {
      const option = document.createElement("option");
      option.value = rule;
      option.textContent = rule;
      select.appendChild(option);
    }
    select.value = rules[className];
    select.addEventListener("change", () =>
      call("set_policy_rule", { instance, class: className, rule: select.value }),
    );
    cell.appendChild(select);
    row.appendChild(cell);
  }
  return row;
}

async function renderSettings() {
  const [settings, config] = await Promise.all([
    invoke("get_settings"),
    invoke("mcp_client_config"),
  ]);
  $("mcp-config").textContent = config;

  $("strict-approval").checked = settings.strict_approval;
  $("autostart").checked = settings.autostart;
  $("require-explicit").checked = settings.require_explicit_instance_for_destructive;
  // Both places, from one source. The header is what somebody glances at; the Settings row is
  // what they copy into a bug report.
  $("app-version").textContent = settings.version;
  $("app-version-detail").textContent = settings.version;
  $("state-dir").textContent = settings.state_directory;
  $("link-port").textContent = `127.0.0.1:${settings.link_port}`;
  $("empty-port").textContent = `127.0.0.1:${settings.link_port}`;

  const body = $("gating");
  body.innerHTML = "";
  body.appendChild(gatingRow("Every instance", null, settings.defaults));
  for (const [instance, rules] of Object.entries(settings.per_instance)) {
    body.appendChild(gatingRow(instance, instance, rules));
  }
}

// ---------------------------------------------------------------------------------
// Wiring
// ---------------------------------------------------------------------------------

async function refresh() {
  // Prompts always, whichever panel is showing: something is blocked while one is up.
  await renderPrompts();
  if (activePanel === "instances") await renderInstances();
  else if (activePanel === "log") await renderLog();
  else if (activePanel === "settings") await renderSettings();
  else if (activePanel === "tasks") await renderTasks();
  else if (activePanel === "detail") {
    // The header needs the roster, so it is read first.
    await renderInstances();
    await renderDetail();
    return;
  }

  // The roster feeds the header and the log's filter, so it is refreshed even when not visible.
  if (activePanel !== "instances") await renderInstances();
}

function showPanel(name) {
  activePanel = name;
  for (const tab of document.querySelectorAll(".tab")) {
    // The detail view belongs to the Instances tab: it is one of them, looked at closely.
    tab.classList.toggle("is-active", tab.dataset.panel === (name === "detail" ? "instances" : name));
  }
  for (const panel of document.querySelectorAll(".panel")) {
    panel.hidden = panel.id !== `panel-${name}`;
  }
  refresh();
}

for (const tab of document.querySelectorAll(".tab")) {
  tab.addEventListener("click", () => showPanel(tab.dataset.panel));
}

$("log-search").addEventListener("input", (event) => {
  filters.text = event.target.value;
  renderLog();
});
for (const [id, key] of [["log-instance", "instance"], ["log-actor", "actor"], ["log-level", "level"]]) {
  $(id).addEventListener("change", (event) => {
    filters[key] = event.target.value;
    renderLog();
  });
}

$("log-export").addEventListener("click", async () => {
  const path = await call("export_events", { instance: filters.instance || null });
  toast(`Written to ${path}`);
});

$("copy-config").addEventListener("click", async () => {
  await navigator.clipboard.writeText($("mcp-config").textContent);
  toast("Configuration copied");
});

$("strict-approval").addEventListener("change", (event) =>
  call("set_strict_approval", { strict: event.target.checked }),
);
$("require-explicit").addEventListener("change", (event) =>
  call("set_require_explicit_instance", { require: event.target.checked }),
);
$("autostart").addEventListener("change", async (event) => {
  try {
    await invoke("set_autostart", { enabled: event.target.checked });
  } catch (error) {
    // Put the box back to what the system actually says, rather than leaving it showing a state
    // that was never applied.
    toast(String(error));
    event.target.checked = await invoke("get_autostart");
  }
});

listen("mcmcp://changed", refresh);
listen("mcmcp://activity", (event) => onActivity(event.payload));
listen("mcmcp://tasks", () => { if (activePanel === "tasks") renderTasks(); });

$("task-new-create").addEventListener("click", async () => {
  const title = $("task-new").value.trim();
  if (!title) return;
  $("task-new").value = "";
  await call("task_create", { title });
});
$("task-new").addEventListener("keydown", (event) => {
  if (event.key === "Enter") $("task-new-create").click();
});
$("task-show-archived").addEventListener("change", renderTasks);
$("task-instance").addEventListener("change", renderTasks);

$("detail-back").addEventListener("click", () => {
  detailInstance = null;
  showPanel("instances");
});
$("detail-log-refresh").addEventListener("click", loadGameLog);

/* Elapsed times on running calls tick even when no progress arrives. */
setInterval(() => {
  if (activePanel === "detail" && activity.running.length > 0) renderDetailActivity();
}, 1000);

/* The game log is asked of the game, so it is re-read slowly and only while somebody is looking. */
setInterval(() => {
  if (activePanel === "detail") loadGameLog();
}, 10_000);

invoke("activity_snapshot").then(onActivity);

/* A slow poll behind the event. Events are the fast path; this is what keeps the window honest if
 * one is ever missed, which is cheap insurance for a panel somebody trusts to tell them what is
 * happening to their game. */
setInterval(refresh, 4000);

refresh();
