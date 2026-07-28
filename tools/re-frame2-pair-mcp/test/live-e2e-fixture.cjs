// live-e2e-fixture.cjs — end-to-end fixture coverage for the pair-mcp
// server, driven over stdio/JSON-RPC against the SHIPPED server bundle.
//
// This is the migrated home of the connect / dispatch / trace / hot-reload
// coverage that formerly lived in `skills/re-frame2-pair/tests/e2e/`, where
// it drove the (now-removed) `scripts/ops.clj` bash transport. The Pair MCP
// server is the one implementation of those six operations, so the live
// coverage lives here — INSIDE tools/re-frame2-pair-mcp — and exercises the
// same three flows through the real MCP boundary:
//
//   1. connect  — `discover-app` finds the preloaded runtime and returns a
//                  healthy snapshot (`:ok?`, `:debug-enabled?`,
//                  `:frames [:rf/default]`).
//   2. dispatch — `dispatch {event "[:counter/inc]" sync true}` commits, the
//                  on-screen `#value` increments 5 → 6, and `trace-window`
//                  carries a matching `:counter/inc` epoch.
//   3. hot-reload — a probe value is captured, the fixture's `core.cljs` is
//                  touch-edited to trigger a shadow-cljs reload, and
//                  `tail-build {probe … wait-ms …}` reports `:soft? false`
//                  once the probe flips.
//
// Unlike the CLJS unit suite (`npm test`) — which stubs nREPL and never
// reaches `out/server.js` — this harness spawns the compiled server and
// talks real MCP frames to it, against a browser-hosted runtime. It is the
// pair-mcp-owned counterpart to the mcp-conformance hermetic live suite
// (which boots the fixture itself); this one attaches to an ALREADY-RUNNING
// fixture and SOFT-SKIPS when that infrastructure is absent, so it is safe
// to invoke anywhere (it exits 0 with a `SKIP` notice, never a false red).
//
// Run locally (two terminals):
//
//   # 1. boot the fixture
//   cd skills/re-frame2-pair/tests/fixture && npm install && npx shadow-cljs watch app
//   # (wait for "build completed" + open http://localhost:8030 so the
//   #  browser runtime registers — or let this harness open its own via
//   #  Playwright)
//
//   # 2. build the server + run the harness
//   cd tools/re-frame2-pair-mcp && npm run build
//   RE_FRAME2_PAIR_FIXTURE_URL=http://localhost:8030 node test/live-e2e-fixture.cjs
//
// Exit code 0 = pass OR soft-skip; non-zero = a live flow failed.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { spawn } = require('node:child_process');

const HERE = __dirname;
const PAIR_MCP_DIR = path.resolve(HERE, '..');
const REPO_ROOT = path.resolve(PAIR_MCP_DIR, '..', '..');
const SERVER = path.join(PAIR_MCP_DIR, 'out', 'server.js');

// The fixture is the same minimal counter the mcp-conformance hermetic
// suite boots (`skills/re-frame2-pair/tests/fixture/`). Overridable so the
// harness can point at a fixture booted elsewhere.
const FIXTURE_DIR =
  process.env.RE_FRAME2_PAIR_FIXTURE_DIR ||
  path.join(REPO_ROOT, 'skills', 're-frame2-pair', 'tests', 'fixture');
const FIXTURE_URL = process.env.RE_FRAME2_PAIR_FIXTURE_URL || 'http://localhost:8030';
// How long the browser gets to install `__re_frame2_pair_runtime`. Named
// (rf2-taj9b) rather than left an inline literal, so the navigation ceiling
// below can say it is NOT this budget. Same name and value as the hermetic
// orchestrator's `RUNTIME_PRELOAD_TIMEOUT_MS`.
const RUNTIME_PRELOAD_TIMEOUT_MS = 60000;

// nREPL port-file candidates — kept aligned with pair-mcp's own
// `port-file-candidates` and the hermetic orchestrator's list.
const NREPL_PORT_FILE_CANDIDATES = [
  path.join(FIXTURE_DIR, '.shadow-cljs', 'nrepl.port'),
  path.join(FIXTURE_DIR, 'target', 'shadow-cljs', 'nrepl.port'),
  path.join(FIXTURE_DIR, '.nrepl-port'),
];

// The probe used by the hot-reload flow (mirrors the retired e2e spec).
const PROBE = '(re-frame2-pair.runtime/registrar-handler-ref :event :counter/inc)';

function skip(reason) {
  // A leading-line `SKIP ` banner is the same shape the mcp-conformance
  // hermetic orchestrator greps for, so a soft-skip here is never mistaken
  // for a passing run.
  console.log(`SKIP live-e2e-fixture — ${reason}`);
  process.exit(0);
}

function readNreplPort() {
  if (process.env.SHADOW_CLJS_NREPL_PORT) {
    const n = parseInt(process.env.SHADOW_CLJS_NREPL_PORT, 10);
    if (Number.isFinite(n)) return n;
  }
  for (const p of NREPL_PORT_FILE_CANDIDATES) {
    try {
      const n = parseInt(fs.readFileSync(p, 'utf8').trim(), 10);
      if (Number.isFinite(n)) return n;
    } catch {
      // try next candidate
    }
  }
  return null;
}

function pingFixture(url, timeoutMs = 1500) {
  return new Promise((resolve) => {
    const req = http.get(url, (res) => {
      res.resume();
      resolve(res.statusCode >= 200 && res.statusCode < 500);
    });
    req.on('error', () => resolve(false));
    req.setTimeout(timeoutMs, () => {
      req.destroy();
      resolve(false);
    });
  });
}

// --------------------------------------------------------------------------
// Minimal EDN reader — adequate for the structured shapes the pair-mcp
// server emits (maps, vectors, keywords, strings, ints, booleans, nil).
// Ported from the retired e2e `_helpers.cjs` so the migrated assertions
// keep parsing the tool text structurally rather than by brittle substring.
// Pure-keyword-keyed maps become plain objects with the leading ':' stripped
// (`:ok?` -> `ok?`); keyword VALUES keep their leading ':' (`:rf/default`).
// --------------------------------------------------------------------------
function parseEdn(input) {
  const src = String(input || '').trim();
  if (src === '') return null;
  let i = 0;
  function skipWs() {
    while (i < src.length) {
      const c = src[i];
      if (c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === ',') i++;
      else if (c === ';') { while (i < src.length && src[i] !== '\n') i++; }
      else break;
    }
  }
  function readToken() {
    let j = i;
    while (j < src.length && !/[\s,\(\)\[\]\{\}]/.test(src[j])) j++;
    const tok = src.slice(i, j);
    i = j;
    return tok;
  }
  function readString() {
    i++; // opening quote
    let out = '';
    while (i < src.length && src[i] !== '"') {
      if (src[i] === '\\' && i + 1 < src.length) {
        out += src[i + 1] === 'n' ? '\n' : src[i + 1];
        i += 2;
      } else out += src[i++];
    }
    i++; // closing quote
    return out;
  }
  function readForm() {
    skipWs();
    if (i >= src.length) throw new Error('unexpected EOF');
    const c = src[i];
    if (c === '"') return readString();
    if (c === '[' || c === '(') {
      const close = c === '[' ? ']' : ')';
      i++;
      const out = [];
      while (true) {
        skipWs();
        if (src[i] === close) { i++; return out; }
        out.push(readForm());
      }
    }
    if (c === '{') {
      i++;
      const entries = new Map();
      while (true) {
        skipWs();
        if (src[i] === '}') {
          i++;
          let allKw = true;
          for (const k of entries.keys()) {
            if (typeof k !== 'string' || !k.startsWith(':')) { allKw = false; break; }
          }
          if (!allKw) return entries;
          const obj = {};
          for (const [k, v] of entries.entries()) obj[k.slice(1)] = v;
          return obj;
        }
        const k = readForm();
        const v = readForm();
        entries.set(k, v);
      }
    }
    if (c === ':') return readToken(); // keep leading ':'
    const tok = readToken();
    if (tok === 'nil') return null;
    if (tok === 'true') return true;
    if (tok === 'false') return false;
    if (/^-?\d+$/.test(tok)) return parseInt(tok, 10);
    if (/^-?\d+\.\d+$/.test(tok)) return parseFloat(tok);
    return tok;
  }
  return readForm();
}

// --------------------------------------------------------------------------
// Playwright is lazy-required: the harness needs a live browser so the
// fixture's `re-frame2-pair.runtime` preload runs and shadow-cljs has a
// connected CLJS runtime to eval against. If Playwright isn't installed we
// soft-skip rather than fail — the browser is infrastructure, not the unit
// under test.
// --------------------------------------------------------------------------
function resolvePlaywright() {
  const candidates = [PAIR_MCP_DIR, path.join(REPO_ROOT, 'implementation')];
  for (const root of candidates) {
    try {
      return require(require.resolve('playwright', { paths: [root] }));
    } catch {
      // try next
    }
  }
  return null;
}

// --------------------------------------------------------------------------
// Stdio MCP client — spawn the compiled server and drive it with
// line-delimited JSON-RPC frames. Modelled on `stdio-roundtrip.js`.
// --------------------------------------------------------------------------
function spawnMcpClient(nreplPort) {
  const env = { ...process.env, SHADOW_CLJS_NREPL_PORT: String(nreplPort) };
  // cwd = fixture dir so the server's port-file discovery has a fallback
  // even if the env var were ever cleared.
  const child = spawn(process.execPath, [SERVER], {
    stdio: ['pipe', 'pipe', 'pipe'],
    cwd: FIXTURE_DIR,
    env,
  });

  let stderrBuf = '';
  child.stderr.on('data', (d) => {
    const s = d.toString();
    stderrBuf += s;
    process.stderr.write('[server] ' + s);
  });

  const waitForStderr = (pattern, { timeoutMs = 8000, intervalMs = 25 } = {}) =>
    new Promise((res, rej) => {
      const deadline = Date.now() + timeoutMs;
      const tick = () => {
        if (pattern.test(stderrBuf)) return res();
        if (child.exitCode !== null) {
          return rej(new Error(`server exited (code=${child.exitCode}) before matching ${pattern}`));
        }
        if (Date.now() >= deadline) {
          return rej(new Error(`timed out waiting for ${pattern} on stderr; saw:\n${stderrBuf}`));
        }
        setTimeout(tick, intervalMs);
      };
      tick();
    });

  let next = 1;
  const pending = new Map();
  let buf = '';
  child.stdout.on('data', (chunk) => {
    buf += chunk.toString('utf8');
    let idx;
    while ((idx = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, idx).trim();
      buf = buf.slice(idx + 1);
      if (!line) continue;
      try {
        const frame = JSON.parse(line);
        if (frame.id && pending.has(frame.id)) {
          pending.get(frame.id)(frame);
          pending.delete(frame.id);
        }
      } catch {
        // Non-JSON stdout noise is ignored; a malformed response for a
        // pending id would surface as a call() timeout instead.
      }
    }
  });

  const rawCall = (method, params) => {
    const id = next++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error(`MCP call ${method} (id ${id}) timed out`));
      }, 30000);
      pending.set(id, (frame) => { clearTimeout(timer); resolve(frame); });
      child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    });
  };
  const notify = (method, params) =>
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method, params }) + '\n');

  return { child, waitForStderr, rawCall, notify };
}

// Call a tool and return { isError, text, edn }.
async function callTool(client, name, args) {
  const resp = await client.rawCall('tools/call', { name, arguments: args });
  const text = resp.result?.content?.[0]?.text || '';
  let edn = null;
  try { edn = parseEdn(text); } catch { /* leave null; assertions handle it */ }
  return { isError: !!resp.result?.isError, text, edn };
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

async function main() {
  // ---- Preconditions / soft-skips --------------------------------------
  if (!fs.existsSync(SERVER)) {
    skip(`server bundle missing at ${SERVER} — run \`npm run build\` first`);
  }
  if (!fs.existsSync(path.join(FIXTURE_DIR, 'shadow-cljs.edn'))) {
    skip(`fixture not found at ${FIXTURE_DIR}`);
  }
  const fixtureUp = await pingFixture(FIXTURE_URL);
  if (!fixtureUp) {
    skip(
      `fixture not reachable at ${FIXTURE_URL} — boot it with ` +
        '`cd skills/re-frame2-pair/tests/fixture && npx shadow-cljs watch app` ' +
        '(set RE_FRAME2_PAIR_FIXTURE_URL to override)',
    );
  }
  const nreplPort = readNreplPort();
  if (!nreplPort) {
    skip(
      'nREPL port file not found under the fixture — is `shadow-cljs watch app` ' +
        'up? (set SHADOW_CLJS_NREPL_PORT to override)',
    );
  }
  const playwright = resolvePlaywright();
  if (!playwright) {
    skip('playwright not resolvable — `npm install` in tools/re-frame2-pair-mcp or implementation/');
  }

  console.log(`[live-e2e] fixture ${FIXTURE_URL}, nREPL :${nreplPort}`);

  // ---- Boot a browser so the runtime is live ----------------------------
  const browser = await playwright.chromium.launch({ headless: true });
  let client = null;
  try {
    const context = await browser.newContext();
    const page = await context.newPage();
    page.on('pageerror', (err) => process.stderr.write(`[browser:pageerror] ${err.message}\n`));
    // rf2-taj9b — the navigation carried no timeout and so took Playwright's
    // 30s default, a ceiling below the 60s preload wait immediately below it
    // and reported in a form that reads like that wait. `'load'` cannot fire
    // until the shadow-cljs `:app` bundle has arrived and run its synchronous
    // portion — which is where the preload installs
    // `__re_frame2_pair_runtime`, i.e. exactly what the poll below waits for.
    // Commit instead, and let the one budget that names the sentinel own the
    // wait. (The fixture's reachability is already checked before this point,
    // so a dead fixture fails earlier, by name.)
    try {
      await page.goto(FIXTURE_URL, {
        waitUntil: 'commit', timeout: RUNTIME_PRELOAD_TIMEOUT_MS,
      });
    } catch (err) {
      throw new Error(
        'NAVIGATION FAILED — this is the page.goto ceiling (waitUntil: ' +
          `'commit', timeout: ${RUNTIME_PRELOAD_TIMEOUT_MS}ms), NOT the ` +
          'runtime-preload wait that carries the same number and had not yet ' +
          `started (rf2-taj9b). Underlying: ${err.message}`,
      );
    }
    // The preload mirrors itself onto js/globalThis at load time; wait for
    // it so shadow-cljs cljs-eval has a runtime to answer.
    await page.waitForFunction(
      () => typeof window.__re_frame2_pair_runtime !== 'undefined',
      undefined,
      { timeout: RUNTIME_PRELOAD_TIMEOUT_MS },
    );
    await page.waitForSelector('#value');
    const initial = (await page.textContent('#value')) || '';
    assert(initial.trim() === '5', `fixture did not initialise to 5: got "${initial}"`);

    // ---- Boot the MCP server + handshake --------------------------------
    client = spawnMcpClient(nreplPort);
    await client.waitForStderr(/\bready\b/);
    const init = await client.rawCall('initialize', {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'live-e2e-fixture', version: '0' },
    });
    assert(init.result?.protocolVersion, 'initialize failed: ' + JSON.stringify(init));
    client.notify('notifications/initialized', {});
    console.log('OK   initialize ->', init.result.serverInfo);

    // ---- 1. connect: discover-app ---------------------------------------
    const disc = await callTool(client, 'discover-app', {});
    assert(!disc.isError, 'discover-app returned isError: ' + disc.text);
    const dv = disc.edn;
    assert(dv && typeof dv === 'object', 'discover-app did not return a map: ' + disc.text);
    assert(dv['ok?'] === true, 'discover-app :ok? not true: ' + disc.text);
    assert(dv['debug-enabled?'] === true, 'discover-app :debug-enabled? not true: ' + disc.text);
    assert(
      Array.isArray(dv['frames']) && dv['frames'].includes(':rf/default'),
      'discover-app :frames missing :rf/default: ' + disc.text,
    );
    console.log('OK   discover-app -> :ok? true, :debug-enabled? true, :rf/default present');

    // ---- 2. dispatch + trace --------------------------------------------
    const disp = await callTool(client, 'dispatch', { event: '[:counter/inc]', sync: true });
    assert(!disp.isError, 'dispatch returned isError: ' + disp.text);
    // Reagent re-renders on a later tick after the sync commit; wait for the
    // DOM to reflect the new state rather than reading eagerly.
    await page.waitForFunction(
      () => (document.querySelector('#value')?.textContent || '').trim() === '6',
      undefined,
      { timeout: 5000 },
    ).catch(() => {
      throw new Error('counter did not read "6" after :counter/inc dispatch');
    });
    console.log('OK   dispatch [:counter/inc] sync -> counter reads 6');

    const trace = await callTool(client, 'trace-window', { ms: 10000 });
    assert(!trace.isError, 'trace-window returned isError: ' + trace.text);
    assert(
      trace.text.includes(':counter/inc'),
      'trace-window did not carry a :counter/inc epoch: ' + trace.text,
    );
    console.log('OK   trace-window -> carries :counter/inc epoch');

    // ---- 3. hot-reload probe --------------------------------------------
    const before = await callTool(client, 'eval-cljs', { form: PROBE });
    assert(!before.isError, 'probe eval(before) returned isError: ' + before.text);
    const beforeVal = before.edn && before.edn['value'];

    const fixtureSrc = path.join(FIXTURE_DIR, 'src', 'counter', 'core.cljs');
    const original = fs.readFileSync(fixtureSrc, 'utf8');
    const marker = `\n;; live-e2e touch ${Date.now()}\n`;
    fs.writeFileSync(fixtureSrc, original + marker);
    try {
      const tail = await callTool(client, 'tail-build', { probe: PROBE, 'wait-ms': 15000 });
      assert(!tail.isError, 'tail-build returned isError: ' + tail.text);
      const tv = tail.edn;
      assert(tv && tv['ok?'] === true, 'tail-build did not return :ok? true: ' + tail.text);
      assert(
        tv['soft?'] !== true,
        'tail-build reported :soft? true — the hot-reload probe never flipped: ' + tail.text,
      );

      const after = await callTool(client, 'eval-cljs', { form: PROBE });
      const afterVal = after.edn && after.edn['value'];
      assert(
        afterVal !== beforeVal,
        `probe value did not change after reload: ${beforeVal} === ${afterVal}`,
      );
    } finally {
      fs.writeFileSync(fixtureSrc, original);
    }
    console.log('OK   tail-build --probe -> :soft? false, probe flipped after reload');

    console.log('\nRE-FRAME2-PAIR STDIO LIVE E2E GREEN');
  } finally {
    if (client && client.child) {
      try { client.child.kill(); } catch { /* best effort */ }
    }
    await browser.close().catch(() => {});
  }
}

main()
  .then(() => process.exit(0))
  .catch((err) => {
    console.error('FAIL:', err && err.message ? err.message : err);
    if (err && err.stack) console.error(err.stack);
    process.exit(1);
  });
