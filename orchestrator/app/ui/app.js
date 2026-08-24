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

  document.querySelector(".dot").classList.toggle("is-live", connected.length > 0);
  $("subtitle").textContent =
    connected.length === 0
      ? "no game connected"
      : `${connected.length} connected · ${connected.reduce((sum, i) => sum + i.tools, 0)} tools`;

  const container = $("instances");
  container.innerHTML = "";
  $("instances-empty").hidden = instances.length > 0;

  for (const instance of instances) {
    const card = document.createElement("div");
    card.className = "card";
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
      </dl>
      <div class="actions"></div>`;

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

  // The log's instance filter is populated from the same list, so it never offers a stale id.
  const select = $("log-instance");
  const chosen = select.value;
  select.innerHTML = '<option value="">All instances</option>';
  for (const instance of instances) {
    const option = document.createElement("option");
    option.value = instance.instance;
    option.textContent = instance.label;
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
    row.innerHTML = `
      <span class="time">${formatTime(event.at)}</span>
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
  const settings = await invoke("get_settings");

  $("strict-approval").checked = settings.strict_approval;
  $("require-explicit").checked = settings.require_explicit_instance_for_destructive;
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

  // The roster feeds the header and the log's filter, so it is refreshed even when not visible.
  if (activePanel !== "instances") await renderInstances();
}

function showPanel(name) {
  activePanel = name;
  for (const tab of document.querySelectorAll(".tab")) {
    tab.classList.toggle("is-active", tab.dataset.panel === name);
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

$("strict-approval").addEventListener("change", (event) =>
  call("set_strict_approval", { strict: event.target.checked }),
);
$("require-explicit").addEventListener("change", (event) =>
  call("set_require_explicit_instance", { require: event.target.checked }),
);

listen("mcmcp://changed", refresh);

/* A slow poll behind the event. Events are the fast path; this is what keeps the window honest if
 * one is ever missed, which is cheap insurance for a panel somebody trusts to tell them what is
 * happening to their game. */
setInterval(refresh, 4000);

refresh();
