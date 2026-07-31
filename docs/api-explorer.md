# API explorer

An interactive reference for MCMCP's HTTP surface, in the spirit of a Swagger UI page. Point it at a
running instance and it will complete the handshake, list the tools, resources and prompts that
instance actually exposes, and execute calls against it.

Everything runs in your browser. This page is static; there is no backend, and your token is never
sent anywhere except to the URL you type in. It is stored in `localStorage` on this origin so you do
not have to paste it on every visit — clear it with **Forget**.

!!! warning "You must allow-list this origin first"

    This page is served from `https://mica-technologies.github.io`, so your browser attaches that as
    the `Origin` header. MCMCP rejects cross-origin requests from origins that are not allow-listed —
    that check is its [DNS-rebinding defence](guide/security.md#origin-validation), and without it any
    web page you visited could drive your game.

    To use this explorer, add the origin in `config/mcmcp.cfg` and run `/mcmcp restart`:

    ```ini
    endpoints {
        S:allowedOrigins <
            http://localhost
            http://127.0.0.1
            https://mica-technologies.github.io
         >
    }
    ```

    **This is a real trade-off.** Allow-listing it means any page served from that origin can reach
    your instance. Reasonable on a development instance; worth removing when you are done. If you
    would rather not, save this page (++ctrl+s++) and open it from `file://` — or use the
    [curl walkthrough](guide/connecting.md#the-handshake-by-hand) instead.

    A blocked request shows as a network failure in the browser, not as a `403` — the browser refuses
    to expose the response. If **Connect** fails with "Network error" and the endpoint is definitely
    up, this is why.

<div id="mcmcp-explorer" class="mcmcp-explorer">
  <section class="mx-panel">
    <h2 class="mx-h">Connection</h2>
    <div class="mx-grid">
      <label class="mx-field">
        <span>Endpoint URL</span>
        <input id="mx-url" type="text" spellcheck="false" placeholder="http://127.0.0.1:25585/mcp" value="http://127.0.0.1:25585/mcp">
      </label>
      <label class="mx-field">
        <span>Bearer token <em>(from <code>/mcmcp token</code>)</em></span>
        <input id="mx-token" type="password" spellcheck="false" placeholder="3f9a1c8e5b7d204f6a1e9c3b8d5f2a70">
      </label>
    </div>
    <div class="mx-row">
      <button id="mx-connect" class="mx-btn mx-btn-primary" type="button">Connect</button>
      <button id="mx-health" class="mx-btn" type="button">Health probe</button>
      <button id="mx-disconnect" class="mx-btn" type="button" disabled>Disconnect</button>
      <button id="mx-forget" class="mx-btn mx-btn-quiet" type="button">Forget saved token</button>
      <span id="mx-status" class="mx-pill mx-pill-idle">Not connected</span>
    </div>
    <div id="mx-session" class="mx-session" hidden></div>
  </section>
  <section class="mx-panel" id="mx-ops">
    <h2 class="mx-h">Operations</h2>
    <p class="mx-note">Connect to populate the tool, resource and prompt lists from your instance. The handshake operations below work without connecting first.</p>
    <div class="mx-group" data-group="handshake">
      <h3 class="mx-group-h">Handshake &amp; session <span class="mx-count" id="mx-count-handshake">4</span></h3>
      <div class="mx-list" id="mx-list-handshake"></div>
    </div>
    <div class="mx-group" data-group="tools">
      <h3 class="mx-group-h">Tools <span class="mx-count" id="mx-count-tools">—</span></h3>
      <div class="mx-list" id="mx-list-tools"><p class="mx-empty">Not loaded.</p></div>
    </div>
    <div class="mx-group" data-group="resources">
      <h3 class="mx-group-h">Resources <span class="mx-count" id="mx-count-resources">—</span></h3>
      <div class="mx-list" id="mx-list-resources"><p class="mx-empty">Not loaded.</p></div>
    </div>
    <div class="mx-group" data-group="prompts">
      <h3 class="mx-group-h">Prompts <span class="mx-count" id="mx-count-prompts">—</span></h3>
      <div class="mx-list" id="mx-list-prompts"><p class="mx-empty">Not loaded.</p></div>
    </div>
    <div class="mx-group" data-group="raw">
      <h3 class="mx-group-h">Raw JSON-RPC</h3>
      <div class="mx-list" id="mx-list-raw"></div>
    </div>
  </section>
</div>
<style>
.mcmcp-explorer{--mx-border:var(--md-default-fg-color--lightest);--mx-bg:var(--md-code-bg-color);--mx-muted:var(--md-default-fg-color--light);font-size:.78rem;margin:1.2rem 0}
.mcmcp-explorer .mx-panel{border:1px solid var(--mx-border);border-radius:.35rem;padding:1rem 1.1rem;margin-bottom:1.2rem}
.mcmcp-explorer .mx-h{margin:0 0 .8rem;font-size:.95rem;font-weight:700;letter-spacing:.01em}
.mcmcp-explorer .mx-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:.8rem;margin-bottom:.8rem}
.mcmcp-explorer .mx-field{display:flex;flex-direction:column;gap:.3rem}
.mcmcp-explorer .mx-field>span{font-weight:600;color:var(--mx-muted)}
.mcmcp-explorer .mx-field em{font-weight:400;font-style:normal;opacity:.75}
.mcmcp-explorer input[type=text],.mcmcp-explorer input[type=password],.mcmcp-explorer textarea{width:100%;box-sizing:border-box;font-family:var(--md-code-font-family,monospace);font-size:.75rem;padding:.45rem .55rem;border:1px solid var(--mx-border);border-radius:.25rem;background:var(--mx-bg);color:var(--md-default-fg-color)}
.mcmcp-explorer textarea{resize:vertical;min-height:5.5rem;line-height:1.45}
.mcmcp-explorer .mx-row{display:flex;flex-wrap:wrap;align-items:center;gap:.5rem}
.mcmcp-explorer .mx-btn{font:inherit;font-weight:600;cursor:pointer;padding:.4rem .8rem;border:1px solid var(--mx-border);border-radius:.25rem;background:transparent;color:var(--md-default-fg-color)}
.mcmcp-explorer .mx-btn:hover:not(:disabled){border-color:var(--md-accent-fg-color);color:var(--md-accent-fg-color)}
.mcmcp-explorer .mx-btn:disabled{opacity:.45;cursor:not-allowed}
.mcmcp-explorer .mx-btn-primary{background:var(--md-primary-fg-color);border-color:var(--md-primary-fg-color);color:var(--md-primary-bg-color)}
.mcmcp-explorer .mx-btn-primary:hover:not(:disabled){opacity:.88;color:var(--md-primary-bg-color)}
.mcmcp-explorer .mx-btn-quiet{opacity:.7;font-weight:400}
.mcmcp-explorer .mx-pill{display:inline-block;padding:.2rem .6rem;border-radius:1rem;font-size:.7rem;font-weight:700;letter-spacing:.02em}
.mcmcp-explorer .mx-pill-idle{background:var(--mx-bg);color:var(--mx-muted)}
.mcmcp-explorer .mx-pill-ok{background:#1b5e2010;color:#2e7d32;border:1px solid #2e7d3255}
.mcmcp-explorer .mx-pill-err{background:#b71c1c10;color:#c62828;border:1px solid #c6282855}
.mcmcp-explorer .mx-pill-busy{background:var(--mx-bg);color:var(--md-accent-fg-color)}
.mcmcp-explorer .mx-session{margin-top:.8rem;padding:.7rem .8rem;border-radius:.25rem;background:var(--mx-bg);font-family:var(--md-code-font-family,monospace);font-size:.72rem;line-height:1.6;overflow-x:auto}
.mcmcp-explorer .mx-session b{font-family:var(--md-text-font-family,inherit);font-size:.72rem}
.mcmcp-explorer .mx-note,.mcmcp-explorer .mx-empty{color:var(--mx-muted);margin:.2rem 0 .9rem;font-size:.75rem}
.mcmcp-explorer .mx-group{margin-bottom:1.3rem}
.mcmcp-explorer .mx-group:last-child{margin-bottom:0}
.mcmcp-explorer .mx-group-h{margin:0 0 .5rem;font-size:.8rem;font-weight:700;text-transform:uppercase;letter-spacing:.06em;color:var(--mx-muted)}
.mcmcp-explorer .mx-count{display:inline-block;margin-left:.4rem;padding:.05rem .45rem;border-radius:1rem;background:var(--mx-bg);font-size:.68rem;letter-spacing:0}
.mcmcp-explorer .mx-op{border:1px solid var(--mx-border);border-radius:.25rem;margin-bottom:.4rem;overflow:hidden}
.mcmcp-explorer .mx-op-head{display:flex;align-items:center;gap:.6rem;width:100%;padding:.5rem .7rem;background:transparent;border:0;font:inherit;text-align:left;cursor:pointer;color:var(--md-default-fg-color)}
.mcmcp-explorer .mx-op-head:hover{background:var(--mx-bg)}
.mcmcp-explorer .mx-verb{flex:0 0 auto;min-width:4.6rem;text-align:center;padding:.15rem .4rem;border-radius:.2rem;font-family:var(--md-code-font-family,monospace);font-size:.66rem;font-weight:700;letter-spacing:.03em;color:#fff}
.mcmcp-explorer .mx-verb-post{background:#2e7d32}
.mcmcp-explorer .mx-verb-get{background:#1565c0}
.mcmcp-explorer .mx-verb-del{background:#c62828}
.mcmcp-explorer .mx-verb-tool{background:#6a1b9a}
.mcmcp-explorer .mx-verb-res{background:#00838f}
.mcmcp-explorer .mx-verb-prompt{background:#ef6c00}
.mcmcp-explorer .mx-op-name{font-family:var(--md-code-font-family,monospace);font-weight:700;font-size:.74rem}
.mcmcp-explorer .mx-op-summary{flex:1 1 auto;color:var(--mx-muted);font-size:.72rem;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.mcmcp-explorer .mx-tag{flex:0 0 auto;padding:.1rem .4rem;border:1px solid var(--mx-border);border-radius:.2rem;font-size:.62rem;font-weight:700;text-transform:uppercase;letter-spacing:.04em;color:var(--mx-muted)}
.mcmcp-explorer .mx-op-body{padding:.2rem .7rem .8rem;border-top:1px solid var(--mx-border)}
.mcmcp-explorer .mx-op-desc{white-space:pre-wrap;margin:.6rem 0;line-height:1.55}
.mcmcp-explorer table.mx-params{width:100%;border-collapse:collapse;margin:.6rem 0;font-size:.72rem}
.mcmcp-explorer table.mx-params th{text-align:left;padding:.3rem .5rem;border-bottom:1px solid var(--mx-border);color:var(--mx-muted);font-size:.68rem;text-transform:uppercase;letter-spacing:.04em}
.mcmcp-explorer table.mx-params td{padding:.35rem .5rem;border-bottom:1px solid var(--mx-border);vertical-align:top}
.mcmcp-explorer table.mx-params code{font-size:.7rem}
.mcmcp-explorer .mx-req{color:#c62828;font-weight:700}
.mcmcp-explorer .mx-sub{display:block;margin-top:.7rem;margin-bottom:.25rem;font-weight:600;color:var(--mx-muted);font-size:.7rem;text-transform:uppercase;letter-spacing:.04em}
.mcmcp-explorer pre.mx-out{margin:.3rem 0 0;padding:.6rem .7rem;border-radius:.25rem;background:var(--mx-bg);font-size:.71rem;line-height:1.5;max-height:26rem;overflow:auto;white-space:pre-wrap;word-break:break-word}
.mcmcp-explorer .mx-meta{display:flex;gap:.6rem;flex-wrap:wrap;align-items:center;margin-top:.5rem;font-size:.7rem;color:var(--mx-muted)}
.mcmcp-explorer .mx-code-ok{color:#2e7d32;font-weight:700}
.mcmcp-explorer .mx-code-err{color:#c62828;font-weight:700}
</style>
<script>
(function () {
  'use strict';
  var STORE_URL = 'mcmcp.explorer.url';
  var STORE_TOKEN = 'mcmcp.explorer.token';
  var state = { sessionId: null, connected: false, nextId: 1, protocol: null };
  var $ = function (id) { return document.getElementById(id); };
  var urlInput = $('mx-url');
  var tokenInput = $('mx-token');
  var statusPill = $('mx-status');
  var sessionBox = $('mx-session');
  /* Restore the previous endpoint and token. localStorage is per-origin, so this never leaks to
     another site; Forget clears it. */
  try {
    var savedUrl = localStorage.getItem(STORE_URL);
    var savedToken = localStorage.getItem(STORE_TOKEN);
    if (savedUrl) { urlInput.value = savedUrl; }
    if (savedToken) { tokenInput.value = savedToken; }
  } catch (e) { /* private browsing: not worth reporting */ }
  function persist() {
    try {
      localStorage.setItem(STORE_URL, urlInput.value.trim());
      localStorage.setItem(STORE_TOKEN, tokenInput.value.trim());
    } catch (e) { /* ignore */ }
  }
  function baseUrl() {
    var value = urlInput.value.trim();
    return value.replace(/\/+$/, '');
  }
  function setStatus(text, kind) {
    statusPill.textContent = text;
    statusPill.className = 'mx-pill mx-pill-' + kind;
  }
  function escapeHtml(text) {
    return String(text).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }
  function pretty(value) {
    try { return JSON.stringify(value, null, 2); } catch (e) { return String(value); }
  }
  /* Every request funnels through here so the auth header, session header and error shape are
     defined in exactly one place. */
  function send(method, options) {
    var opts = options || {};
    var headers = { 'Accept': 'application/json' };
    var token = tokenInput.value.trim();
    if (token) { headers['Authorization'] = 'Bearer ' + token; }
    if (state.sessionId && !opts.noSession) { headers['Mcp-Session-Id'] = state.sessionId; }
    if (opts.body) { headers['Content-Type'] = 'application/json'; }
    var started = performance.now();
    return fetch(baseUrl() + (opts.path || ''), {
      method: method,
      headers: headers,
      body: opts.body ? JSON.stringify(opts.body) : undefined,
      mode: 'cors'
    }).then(function (response) {
      return response.text().then(function (text) {
        var parsed = null;
        if (text) { try { parsed = JSON.parse(text); } catch (e) { parsed = text; } }
        return {
          ok: response.ok,
          status: response.status,
          sessionHeader: response.headers.get('Mcp-Session-Id'),
          body: parsed,
          raw: text,
          millis: Math.round(performance.now() - started)
        };
      });
    });
  }
  function rpc(method, params, options) {
    var opts = options || {};
    var message = { jsonrpc: '2.0', method: method };
    if (!opts.notification) { message.id = state.nextId++; }
    if (params !== undefined && params !== null) { message.params = params; }
    return send('POST', { body: message, noSession: opts.noSession }).then(function (result) {
      result.request = message;
      return result;
    });
  }
  function renderResult(target, result) {
    var codeClass = result.ok ? 'mx-code-ok' : 'mx-code-err';
    var meta = '<div class="mx-meta"><span class="' + codeClass + '">HTTP ' + result.status +
      '</span><span>' + result.millis + ' ms</span>' +
      (result.sessionHeader ? '<span>Mcp-Session-Id: ' + escapeHtml(result.sessionHeader) + '</span>' : '') +
      '</div>';
    var body = result.raw ? pretty(result.body) : '(empty body)';
    target.innerHTML = meta + '<pre class="mx-out">' + escapeHtml(body) + '</pre>';
  }
  function renderError(target, error) {
    var hint = '';
    if (error && /NetworkError|Failed to fetch|Load failed/i.test(String(error.message || error))) {
      hint = '\n\nThe browser blocked or could not complete the request. Common causes:\n' +
        '  - This origin is not in allowedOrigins on the instance (see the note above this explorer).\n' +
        '  - The endpoint is not listening. Check /mcmcp status in game.\n' +
        '  - The URL is wrong, or the page is HTTPS and the endpoint is plain HTTP on a\n' +
        '    non-loopback host, which browsers block as mixed content.';
    }
    target.innerHTML = '<div class="mx-meta"><span class="mx-code-err">Network error</span></div>' +
      '<pre class="mx-out">' + escapeHtml(String(error && error.message ? error.message : error) + hint) + '</pre>';
  }
  /* ---- operation rows ------------------------------------------------- */
  function buildOp(spec) {
    var op = document.createElement('div');
    op.className = 'mx-op';
    var head = document.createElement('button');
    head.type = 'button';
    head.className = 'mx-op-head';
    head.innerHTML = '<span class="mx-verb mx-verb-' + spec.verbClass + '">' + escapeHtml(spec.verb) + '</span>' +
      '<span class="mx-op-name">' + escapeHtml(spec.name) + '</span>' +
      '<span class="mx-op-summary">' + escapeHtml(spec.summary || '') + '</span>' +
      (spec.tags || []).map(function (tag) { return '<span class="mx-tag">' + escapeHtml(tag) + '</span>'; }).join('');
    var body = document.createElement('div');
    body.className = 'mx-op-body';
    body.hidden = true;
    head.addEventListener('click', function () { body.hidden = !body.hidden; });
    op.appendChild(head);
    op.appendChild(body);
    if (spec.description) {
      var desc = document.createElement('div');
      desc.className = 'mx-op-desc';
      desc.textContent = spec.description;
      body.appendChild(desc);
    }
    if (spec.schema) { body.appendChild(buildParamTable(spec.schema)); }
    var editor = null;
    if (spec.editable !== false) {
      var label = document.createElement('span');
      label.className = 'mx-sub';
      label.textContent = spec.editorLabel || 'Arguments (JSON)';
      body.appendChild(label);
      editor = document.createElement('textarea');
      editor.spellcheck = false;
      editor.value = spec.initial !== undefined ? spec.initial : '{}';
      body.appendChild(editor);
    }
    var controls = document.createElement('div');
    controls.className = 'mx-row';
    controls.style.marginTop = '.6rem';
    var run = document.createElement('button');
    run.type = 'button';
    run.className = 'mx-btn mx-btn-primary';
    run.textContent = spec.action || 'Execute';
    controls.appendChild(run);
    body.appendChild(controls);
    var output = document.createElement('div');
    body.appendChild(output);
    run.addEventListener('click', function () {
      var payload;
      if (editor) {
        try {
          payload = editor.value.trim() ? JSON.parse(editor.value) : {};
        } catch (e) {
          output.innerHTML = '<div class="mx-meta"><span class="mx-code-err">Invalid JSON</span></div>' +
            '<pre class="mx-out">' + escapeHtml(e.message) + '</pre>';
          return;
        }
      }
      run.disabled = true;
      output.innerHTML = '<div class="mx-meta"><span>Sending…</span></div>';
      spec.run(payload)
        .then(function (result) { renderResult(output, result); })
        .catch(function (error) { renderError(output, error); })
        .then(function () { run.disabled = false; });
    });
    return op;
  }
  /* Renders a tool's inputSchema as a parameter table, the way an OpenAPI page would. */
  function buildParamTable(schema) {
    var table = document.createElement('table');
    table.className = 'mx-params';
    var props = (schema && schema.properties) || {};
    var required = (schema && schema.required) || [];
    var names = Object.keys(props);
    if (!names.length) {
      var none = document.createElement('p');
      none.className = 'mx-empty';
      none.textContent = 'Takes no arguments.';
      return none;
    }
    var rows = names.map(function (name) {
      var prop = props[name] || {};
      var type = prop.type || 'any';
      if (prop['enum']) { type = prop['enum'].map(function (v) { return JSON.stringify(v); }).join(' | '); }
      var bounds = [];
      if (prop.minimum !== undefined) { bounds.push('min ' + prop.minimum); }
      if (prop.maximum !== undefined) { bounds.push('max ' + prop.maximum); }
      return '<tr><td><code>' + escapeHtml(name) + '</code>' +
        (required.indexOf(name) >= 0 ? ' <span class="mx-req">*</span>' : '') +
        '</td><td><code>' + escapeHtml(type) + '</code>' +
        (bounds.length ? '<br><span style="opacity:.7">' + escapeHtml(bounds.join(', ')) + '</span>' : '') +
        '</td><td>' + escapeHtml(prop.description || '') + '</td></tr>';
    }).join('');
    table.innerHTML = '<thead><tr><th>Name</th><th>Type</th><th>Description</th></tr></thead><tbody>' +
      rows + '</tbody>';
    return table;
  }
  /* Prefills the argument editor with the required properties, so a tool is one click from running. */
  function initialArguments(schema) {
    var props = (schema && schema.properties) || {};
    var required = (schema && schema.required) || [];
    if (!required.length) { return '{}'; }
    var seed = {};
    required.forEach(function (name) {
      var prop = props[name] || {};
      if (prop['enum'] && prop['enum'].length) { seed[name] = prop['enum'][0]; }
      else if (prop.type === 'integer' || prop.type === 'number') { seed[name] = prop.minimum !== undefined ? prop.minimum : 0; }
      else if (prop.type === 'boolean') { seed[name] = false; }
      else if (prop.type === 'array') { seed[name] = []; }
      else { seed[name] = ''; }
    });
    return JSON.stringify(seed, null, 2);
  }
  /* ---- handshake group ------------------------------------------------ */
  var handshakeList = $('mx-list-handshake');
  handshakeList.appendChild(buildOp({
    verb: 'GET', verbClass: 'get', name: '/health', tags: ['no auth'],
    summary: 'Liveness probe. Confirms the port is MCMCP and which side it is.',
    description: 'Needs no authentication and deliberately reports nothing beyond service, side and status. It is the only endpoint reachable without a token.',
    editable: false, action: 'Send',
    run: function () { return send('GET', { path: '/health', noSession: true }); }
  }));
  handshakeList.appendChild(buildOp({
    verb: 'POST', verbClass: 'post', name: 'initialize',
    summary: 'Negotiates the protocol version and mints a session.',
    description: 'The session id comes back in the Mcp-Session-Id response header, not the body. Every subsequent request must echo it. A second initialize always creates a fresh session.',
    initial: JSON.stringify({ protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'mcmcp-wiki-explorer', version: '1.0' } }, null, 2),
    editorLabel: 'params (JSON)',
    run: function (params) {
      return rpc('initialize', params, { noSession: true }).then(function (result) {
        if (result.sessionHeader) { state.sessionId = result.sessionHeader; }
        return result;
      });
    }
  }));
  handshakeList.appendChild(buildOp({
    verb: 'POST', verbClass: 'post', name: 'ping',
    summary: 'Liveness check. Allowed before initialization.',
    description: 'Returns an empty object. One of only two methods legal before the handshake completes; the other is initialize.',
    initial: 'null', editorLabel: 'params (JSON, or null)',
    run: function (params) { return rpc('ping', params); }
  }));
  handshakeList.appendChild(buildOp({
    verb: 'DELETE', verbClass: 'del', name: 'session',
    summary: 'Terminates the session and releases its state.',
    description: 'Returns 204. Sessions also expire after 30 minutes of silence, because clients crash without saying goodbye.',
    editable: false, action: 'Delete session',
    run: function () {
      return send('DELETE').then(function (result) { state.sessionId = null; return result; });
    }
  }));
  $('mx-list-raw').appendChild(buildOp({
    verb: 'POST', verbClass: 'post', name: 'any method',
    summary: 'Send an arbitrary JSON-RPC message to the endpoint.',
    description: 'The escape hatch, for methods this page does not model and for reproducing a client bug by hand. The envelope is sent exactly as written — including the id, or its absence for a notification.',
    initial: JSON.stringify({ jsonrpc: '2.0', id: 99, method: 'tools/list' }, null, 2),
    editorLabel: 'Full JSON-RPC message',
    run: function (message) { return send('POST', { body: message }); }
  }));
  /* ---- catalogue rendering -------------------------------------------- */
  function toolTags(tool) {
    var tags = [];
    var ann = tool.annotations || {};
    if (ann.readOnlyHint) { tags.push('read-only'); }
    if (ann.destructiveHint) { tags.push('destructive'); }
    if (ann.idempotentHint && !ann.readOnlyHint) { tags.push('idempotent'); }
    return tags;
  }
  function renderTools(tools) {
    var list = $('mx-list-tools');
    list.innerHTML = '';
    $('mx-count-tools').textContent = tools.length;
    if (!tools.length) { list.innerHTML = '<p class="mx-empty">This endpoint exposes no tools.</p>'; return; }
    tools.forEach(function (tool) {
      list.appendChild(buildOp({
        verb: 'TOOL', verbClass: 'tool', name: tool.name, tags: toolTags(tool),
        summary: (tool.description || '').split('\n')[0],
        description: tool.description,
        schema: tool.inputSchema,
        initial: initialArguments(tool.inputSchema),
        action: 'Call tool',
        run: function (args) { return rpc('tools/call', { name: tool.name, arguments: args || {} }); }
      }));
    });
  }
  function renderResources(resources, templates) {
    var list = $('mx-list-resources');
    list.innerHTML = '';
    var total = resources.length + templates.length;
    $('mx-count-resources').textContent = total;
    if (!total) { list.innerHTML = '<p class="mx-empty">This endpoint exposes no resources.</p>'; return; }
    resources.forEach(function (resource) {
      list.appendChild(buildOp({
        verb: 'RES', verbClass: 'res', name: resource.uri,
        tags: resource.mimeType ? [resource.mimeType] : [],
        summary: resource.description || resource.name,
        description: resource.description,
        initial: JSON.stringify({ uri: resource.uri }, null, 2),
        editorLabel: 'params (JSON)', action: 'Read resource',
        run: function (params) { return rpc('resources/read', params); }
      }));
    });
    templates.forEach(function (template) {
      list.appendChild(buildOp({
        verb: 'RES', verbClass: 'res', name: template.uriTemplate, tags: ['template'],
        summary: template.description || template.name,
        description: (template.description || '') + '\n\nReplace the {braced} segments with concrete values before reading. Variables never match across a "/".',
        initial: JSON.stringify({ uri: template.uriTemplate }, null, 2),
        editorLabel: 'params (JSON)', action: 'Read resource',
        run: function (params) { return rpc('resources/read', params); }
      }));
    });
  }
  function renderPrompts(prompts) {
    var list = $('mx-list-prompts');
    list.innerHTML = '';
    $('mx-count-prompts').textContent = prompts.length;
    if (!prompts.length) { list.innerHTML = '<p class="mx-empty">This endpoint exposes no prompts.</p>'; return; }
    prompts.forEach(function (prompt) {
      var seed = {};
      (prompt.arguments || []).forEach(function (arg) { if (arg.required) { seed[arg.name] = ''; } });
      var schema = { properties: {}, required: [] };
      (prompt.arguments || []).forEach(function (arg) {
        schema.properties[arg.name] = { type: 'string', description: arg.description };
        if (arg.required) { schema.required.push(arg.name); }
      });
      list.appendChild(buildOp({
        verb: 'PROMPT', verbClass: 'prompt', name: prompt.name,
        summary: prompt.description,
        description: prompt.description,
        schema: schema,
        initial: JSON.stringify(seed, null, 2),
        editorLabel: 'arguments (JSON)', action: 'Get prompt',
        run: function (args) { return rpc('prompts/get', { name: prompt.name, arguments: args || {} }); }
      }));
    });
  }
  function describeSession(initResult) {
    var body = initResult.body || {};
    var result = body.result || {};
    var info = result.serverInfo || {};
    var caps = Object.keys(result.capabilities || {}).join(', ') || '(none)';
    sessionBox.hidden = false;
    sessionBox.innerHTML =
      '<b>Server</b>: ' + escapeHtml((info.title || info.name || 'unknown') + ' ' + (info.version || '')) + '<br>' +
      '<b>Protocol</b>: ' + escapeHtml(result.protocolVersion || 'unknown') + '<br>' +
      '<b>Capabilities</b>: ' + escapeHtml(caps) + '<br>' +
      '<b>Session</b>: ' + escapeHtml(state.sessionId || 'none') +
      (result.instructions ? '<br><br><b>Instructions</b><br>' + escapeHtml(result.instructions).replace(/\n/g, '<br>') : '');
  }
  /* ---- connect flow ---------------------------------------------------- */
  function connect() {
    persist();
    state.sessionId = null;
    setStatus('Connecting…', 'busy');
    $('mx-connect').disabled = true;
    rpc('initialize', {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'mcmcp-wiki-explorer', version: '1.0' }
    }, { noSession: true }).then(function (result) {
      if (!result.ok) {
        var detail = result.body && result.body.error ? result.body.error.message : ('HTTP ' + result.status);
        throw new Error(detail);
      }
      state.sessionId = result.sessionHeader;
      state.protocol = result.body && result.body.result ? result.body.result.protocolVersion : null;
      describeSession(result);
      /* The session is only usable for anything beyond ping once this notification is sent. */
      return rpc('notifications/initialized', null, { notification: true }).then(function () {
        return Promise.all([rpc('tools/list'), rpc('resources/list'),
          rpc('resources/templates/list'), rpc('prompts/list')]);
      });
    }).then(function (results) {
      var pick = function (result, key) {
        return (result && result.body && result.body.result && result.body.result[key]) || [];
      };
      renderTools(pick(results[0], 'tools'));
      renderResources(pick(results[1], 'resources'), pick(results[2], 'resourceTemplates'));
      renderPrompts(pick(results[3], 'prompts'));
      state.connected = true;
      setStatus('Connected · ' + (state.protocol || 'unknown'), 'ok');
      $('mx-disconnect').disabled = false;
    }).catch(function (error) {
      setStatus('Failed', 'err');
      sessionBox.hidden = false;
      var message = String(error && error.message ? error.message : error);
      if (/NetworkError|Failed to fetch|Load failed/i.test(message)) {
        message += '\n\nThe browser blocked or could not complete the request. Most often this means ' +
          'this page\'s origin is not in allowedOrigins on the instance — see the note above. It can ' +
          'also mean the endpoint is not listening, or the URL is wrong.';
      }
      sessionBox.innerHTML = '<pre class="mx-out">' + escapeHtml(message) + '</pre>';
    }).then(function () { $('mx-connect').disabled = false; });
  }
  $('mx-connect').addEventListener('click', connect);
  $('mx-disconnect').addEventListener('click', function () {
    send('DELETE').catch(function () { /* best effort; the session expires anyway */ });
    state.sessionId = null;
    state.connected = false;
    sessionBox.hidden = true;
    setStatus('Not connected', 'idle');
    $('mx-disconnect').disabled = true;
    ['tools', 'resources', 'prompts'].forEach(function (group) {
      $('mx-list-' + group).innerHTML = '<p class="mx-empty">Not loaded.</p>';
      $('mx-count-' + group).textContent = '—';
    });
  });
  $('mx-health').addEventListener('click', function () {
    persist();
    setStatus('Probing…', 'busy');
    send('GET', { path: '/health', noSession: true }).then(function (result) {
      sessionBox.hidden = false;
      sessionBox.innerHTML = '<pre class="mx-out">' + escapeHtml(pretty(result.body)) + '</pre>';
      setStatus(result.ok ? 'Endpoint reachable' : 'HTTP ' + result.status, result.ok ? 'ok' : 'err');
    }).catch(function (error) {
      sessionBox.hidden = false;
      renderError(sessionBox, error);
      setStatus('Unreachable', 'err');
    });
  });
  $('mx-forget').addEventListener('click', function () {
    try { localStorage.removeItem(STORE_TOKEN); localStorage.removeItem(STORE_URL); } catch (e) { /* ignore */ }
    tokenInput.value = '';
    setStatus('Saved token cleared', 'idle');
  });
  urlInput.addEventListener('change', persist);
  tokenInput.addEventListener('change', persist);
})();
</script>

## How this maps onto MCP

MCP is JSON-RPC 2.0, not REST, so the parallel with an OpenAPI page is deliberate but imperfect. The
translation:

| OpenAPI concept | MCP equivalent |
| --- | --- |
| Path + method | A JSON-RPC `method` name, always sent as `POST` to one URL |
| Path/query parameters | The `params` object |
| Request body schema | A tool's `inputSchema` (real JSON Schema, so the parameter tables above are generated from it) |
| Response schema | A tool's `outputSchema`, where declared |
| Tags | The MCP feature — tools, resources, prompts |
| Security scheme | `Authorization: Bearer`, plus the `Mcp-Session-Id` header |
| Server variables | The endpoint URL field above |

The largest difference is that **the API is discovered at runtime**. There is no static document
describing which tools exist: `tools/list` is the schema, and it varies with the endpoint, the
configuration, and which other mods have registered into the catalogue. That is why this page has to
connect before it can show you anything — and why what it shows is your instance's real surface, not
a copy of the documentation.

For the complete written reference, see [Tools](reference/tools.md),
[Resources](reference/resources.md), [Prompts](reference/prompts.md) and
[Protocol support](reference/protocol.md).
