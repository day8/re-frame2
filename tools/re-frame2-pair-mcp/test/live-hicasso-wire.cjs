// live-hicasso-wire.cjs — THE LIVE WIRE WITNESS for the three Hicasso
// evidence-door tools (rf2-hic-059, #7986 merged-PR audit).
//
// ## The gap this closes
//
// Two suites in this tree already look at these tools and neither one runs
// them:
//
//   - `hicasso_tool_test.cljs` stubs `nrepl/cljs-eval-value` with canned
//     envelopes, so it checks the emitter and the schema gate against
//     itself;
//   - `hicasso_wire_test.cljs` parses the emitted STRING and reads the
//     provider's own source, so it checks that the two sides agree on
//     names and on the schema literal.
//
// Both are valuable static seam checks and neither executes a Pair tool
// against a Hicasso provider. The claim that the privacy-projected
// evidence surface works THROUGH the MCP plumbing was therefore
// unwitnessed: the emitted form had never been compiled, sent, evaluated
// in a runtime, or had its result carried back through the gate.
//
// ## What one call actually traverses here
//
// Every assertion below rides the shipped path, end to end:
//
//   MCP `tools/call` frame on stdio
//     -> `out/server.js` (the SHIPPED bundle, `:simple` optimised)
//     -> `tools/invoke` (build resolution, precheck, cache, cap)
//     -> `hicasso-tool/<read>-tool`
//     -> `probe/eval-after-runtime!` (the `__re_frame2_pair_runtime`
//        preload probe + the JVM-side liveness re-check)
//     -> `nrepl/cljs-eval-value` — bencode frames on a REAL socket
//     -> shadow-cljs COMPILES the door-resolving form that
//        `hicasso-tool/projection-form` built, and evaluates it inside the
//        browser CLJS runtime
//     -> `re-frame.hicasso.tool/<read>` runs against the live application
//     -> the projection returns over the same socket
//     -> `versioned-envelope-result` GATES it against
//        `consumed-evidence-schema`
//     -> `probe/map-envelope-result` -> elision -> the token cap
//     -> the MCP result frame this script reads.
//
// Nothing on that list is stubbed, faked or re-implemented here.
//
// ## The application is the slice, booted by its own entry point
//
// The population is the `rf2-hic-025` slice application, started with its
// own `-main` — `rf/init!`, `make-frame!`, `h/root!` — over `eval-cljs`.
// This script registers no subscription, no event and no view, so every
// id that appears in an answer below was written by another bead for
// another purpose. The one thing it supplies the host page is a `<div
// id="app">` for the slice's root to mount into, because the page hosting
// the runtime is not the slice's own page.
//
// ## The negative control, and why it is not vacuous
//
// The door promises to carry NO read value at any classification. So the
// control seeds a value that exists nowhere else in the process into the
// slice's draft — through the slice's OWN `::events/edit` event — and then
// proves TWO things at the wire, in this order:
//
//   1. NON-VACUITY. `read-sub` (a different shipped Pair tool, same
//      socket, same server) returns the secret. The runtime really holds
//      it and the wire really can carry it.
//   2. ABSENCE. None of the three Hicasso envelopes contains it, while all
//      three name `::subs/draft` — the very read whose value it is.
//
// Without step 1 the absence assertion would pass just as happily against
// a runtime that never saw the value.
//
// ## The DOOR-ABSENT rung, witnessed first (rf2-t2ec)
//
// The tools document a degradation ladder whose first rung is an app that
// has no evidence door — a Reagent/UIx app, or a Hicasso app nothing
// pulled the door into. This witness in its first form (rf2-hic-059)
// found that the rung was unreachable: `cljs.core/exists?` guards a
// missing VAR, but the call it guarded was a var reference like any
// other, and shadow's analyzer rejects a var in a namespace the build
// has never loaded before any of the form runs. The rung answered
// `:reason :rf.error/eval-cljs-compile-error` and an analyzer warning
// where the load-the-door hint belonged. That is exactly the class of
// divergence a static seam check cannot see — the emitted string
// contained the branch, and the branch could not run.
//
// rf2-t2ec resolves the door at runtime instead (`find-ns-obj` on the
// namespace, `unchecked-get` on the munged read), and THIS is where the
// repair is proved: before anything loads the door, the three tools are
// called against the plain host build and must answer
// `:evidence-tier-unavailable` with its hint. The row runs FIRST, because
// it is the only assertion here whose population is destroyed by the rest
// of the script.
//
// **That makes the run order load-bearing, and it makes the witness a
// once-per-watch instrument.** `(require 're-frame.hicasso.tool)` below
// compiles the door into this build for the life of the `shadow-cljs
// watch` — a second run against the same watch finds the door already
// there. Rather than skip the row (a skip here is indistinguishable from
// a pass, which is the failure shape this bead is about), the script
// checks the precondition and FAILS with the remedy: restart the watch.
//
// ## Running it
//
// Opt-in, like its `live-nrepl.js` / `live-e2e-fixture.cjs` siblings, and
// it SOFT-SKIPS (exit 0 with a `SKIP` banner) when the infrastructure is
// absent, so it is safe to invoke anywhere.
//
//   # 1. a FRESH browser build carrying the pair preload, on the
//   #    implementation classpath (which is where the slice and the door
//   #    both live). Fresh matters: see the door-absent section above.
//   cd implementation
//   npx shadow-cljs watch hicasso/hmr-testbed \
//     --config-merge '{:devtools {:preloads [re-frame2-pair.runtime]}}'
//
//   # 2. build the server and run the witness
//   cd tools/re-frame2-pair-mcp && npm run build
//   npm run test:live-hicasso-wire
//
// `RF2_HICASSO_WIRE_URL` overrides the page (default http://localhost:8061)
// and `SHADOW_CLJS_NREPL_PORT` the port. Any page will do provided it
// carries the `re-frame2-pair.runtime` preload and its build can reach
// `re-frame.hicasso.tool` and the slice on its classpath.
//
// Exit code 0 = pass OR soft-skip; non-zero = a live assertion failed.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { spawn } = require('node:child_process');

const HERE = __dirname;
const PAIR_MCP_DIR = path.resolve(HERE, '..');
const REPO_ROOT = path.resolve(PAIR_MCP_DIR, '..', '..');
const IMPL_DIR = path.join(REPO_ROOT, 'implementation');
const SERVER = path.join(PAIR_MCP_DIR, 'out', 'server.js');

const APP_URL = process.env.RF2_HICASSO_WIRE_URL || 'http://localhost:8061';

// Kept aligned with pair-mcp's own `port-file-candidates` and the two
// sibling live harnesses. The host build is served out of `implementation`,
// so that is where shadow writes the port file.
const NREPL_PORT_FILE_CANDIDATES = [
  path.join(IMPL_DIR, '.shadow-cljs', 'nrepl.port'),
  path.join(IMPL_DIR, 'target', 'shadow-cljs', 'nrepl.port'),
  path.join(IMPL_DIR, '.nrepl-port'),
];

// Same name and value as the sibling harnesses' preload budget.
const RUNTIME_PRELOAD_TIMEOUT_MS = 60000;

// The slice's own namespaces. Spelled once, here, because every form below
// calls them fully qualified — an alias would be this script's vocabulary
// rather than the application's.
const SLICE = 're-frame.hicasso.examples.slice';
const FRAME = `:${SLICE}.app/frame`;

// A value that exists nowhere else in this process, so finding it in a
// projection is proof of egress rather than a coincidence of spelling.
const THE_SECRET = 'RF2-HIC-059-WIRE-SECRET-4d2a1f';

// The consumer-owned schema literal `versioned-envelope-result` gates on.
// Asserted as a STRING because this is the wire, which is where the two
// sides have to agree.
const EXPECTED_SCHEMA = ':re-frame.hicasso.evidence/v2';

const DOOR_READS = ['read-mounted-boundaries', 'read-read-attribution', 'explain-render'];

// The evidence door itself. Spelled once because the door-absent row below
// asserts on it three ways — the presence probe, the hint text, and the
// require that ends the absent population.
const DOOR_NS = 're-frame.hicasso.tool';

function skip(reason) {
  // The same leading-line banner the mcp-conformance hermetic orchestrator
  // greps for, so a soft-skip is never mistaken for a passing run.
  console.log(`SKIP live-hicasso-wire — ${reason}`);
  process.exit(0);
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
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
      // try the next candidate
    }
  }
  return null;
}

function pingApp(url, timeoutMs = 1500) {
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

// Playwright is lazy-required and its absence is a soft-skip: the browser
// is infrastructure that hosts the CLJS runtime, not the unit under test.
function resolvePlaywright() {
  for (const root of [PAIR_MCP_DIR, IMPL_DIR]) {
    try {
      return require(require.resolve('playwright', { paths: [root] }));
    } catch {
      // try the next root
    }
  }
  return null;
}

// --------------------------------------------------------------------------
// Stdio MCP client — spawn the SHIPPED server bundle and drive it with
// line-delimited JSON-RPC frames. Modelled on `live-e2e-fixture.cjs`.
// --------------------------------------------------------------------------
function spawnMcpClient(nreplPort) {
  const env = { ...process.env, SHADOW_CLJS_NREPL_PORT: String(nreplPort) };
  // cwd = implementation so the server's port-file discovery has a fallback
  // even if the env var were ever cleared.
  const child = spawn(process.execPath, [SERVER], {
    stdio: ['pipe', 'pipe', 'pipe'],
    cwd: IMPL_DIR,
    env,
  });

  let stderrBuf = '';
  child.stderr.on('data', (d) => {
    stderrBuf += d.toString();
  });

  const waitForStderr = (pattern, { timeoutMs = 20000, intervalMs = 25 } = {}) =>
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
        // pending id surfaces as a call timeout instead.
      }
    }
  });

  const rawCall = (method, params) => {
    const id = next++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error(`MCP call ${method} (id ${id}) timed out`));
      }, 60000);
      pending.set(id, (frame) => {
        clearTimeout(timer);
        resolve(frame);
      });
      child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    });
  };
  const notify = (method, params) =>
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method, params }) + '\n');

  return { child, waitForStderr, rawCall, notify, stderr: () => stderrBuf };
}

async function callTool(client, name, args) {
  const resp = await client.rawCall('tools/call', { name, arguments: args });
  const text = resp.result?.content?.[0]?.text || '';
  return { isError: !!resp.result?.isError, text };
}

// `eval-cljs` is how the population is arranged: a shipped Pair tool,
// evaluating the application's OWN entry point and OWN events in the
// runtime. Anything it refuses is a setup failure and says so.
async function evalCljs(client, form, what) {
  const r = await callTool(client, 'eval-cljs', { form });
  assert(!r.isError, `${what} — eval-cljs returned isError: ${r.text}`);
  assert(/^\{:ok\? true\b/.test(r.text.trim()), `${what} — eval-cljs was not :ok? true: ${r.text}`);
  return r.text;
}

// The envelope's own header, asserted structurally on the wire text: the
// `:ok? true` the schema gate forwards, and the schema literal it forwards
// it AGAINST. A gate that let an unrecognised producer stamp through would
// fail here rather than downstream.
const OK_AND_SCHEMA = new RegExp(
  `^\\{:ok\\? true, :schema ${EXPECTED_SCHEMA.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')},`,
);

async function main() {
  // ---- Preconditions / soft-skips --------------------------------------
  if (!fs.existsSync(SERVER)) {
    skip(`server bundle missing at ${SERVER} — run \`npm run build\` first`);
  }
  const appUp = await pingApp(APP_URL);
  if (!appUp) {
    skip(
      `no runtime host reachable at ${APP_URL} — boot one with ` +
        "`cd implementation && npx shadow-cljs watch hicasso/hmr-testbed " +
        "--config-merge '{:devtools {:preloads [re-frame2-pair.runtime]}}'` " +
        '(set RF2_HICASSO_WIRE_URL to override)',
    );
  }
  const nreplPort = readNreplPort();
  if (!nreplPort) {
    skip('nREPL port file not found under implementation/ (set SHADOW_CLJS_NREPL_PORT to override)');
  }
  const playwright = resolvePlaywright();
  if (!playwright) {
    skip('playwright not resolvable — `npm install` in tools/re-frame2-pair-mcp or implementation/');
  }

  console.log(`[live-hicasso-wire] host ${APP_URL}, nREPL :${nreplPort}`);

  const browser = await playwright.chromium.launch({ headless: true });
  let client = null;
  try {
    const context = await browser.newContext();
    const page = await context.newPage();
    page.on('pageerror', (err) => process.stderr.write(`[browser:pageerror] ${err.message}\n`));
    await page.goto(APP_URL, { waitUntil: 'commit', timeout: RUNTIME_PRELOAD_TIMEOUT_MS });
    await page.waitForFunction(
      () => typeof window.__re_frame2_pair_runtime !== 'undefined',
      undefined,
      { timeout: RUNTIME_PRELOAD_TIMEOUT_MS },
    );
    console.log('OK   runtime preload present');

    // ---- The MCP server, over real stdio --------------------------------
    client = spawnMcpClient(nreplPort);
    await client.waitForStderr(/\bready\b/);
    const init = await client.rawCall('initialize', {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'live-hicasso-wire', version: '0' },
    });
    assert(init.result?.protocolVersion, 'initialize failed: ' + JSON.stringify(init));
    client.notify('notifications/initialized', {});
    console.log('OK   initialize ->', JSON.stringify(init.result.serverInfo));

    // The host build is whatever `shadow-cljs watch` is running, and the
    // server names it. Read it once and pass it to every call rather than
    // spelling a build id here: `read-sub` defaults to `:app` and would
    // otherwise refuse with `:build-not-running` against any other host.
    const disc = await callTool(client, 'discover-app', {});
    assert(!disc.isError, 'discover-app returned isError: ' + disc.text);
    const buildMatch = /:auto-selected-build (:[^\s,}]+)/.exec(disc.text);
    assert(buildMatch, 'discover-app did not name a build: ' + disc.text.slice(0, 400));
    const build = buildMatch[1];
    console.log(`OK   discover-app -> build ${build}`);

    // ---- THE DOOR-ABSENT RUNG, before anything loads the door -----------
    // Nothing in `re-frame.hicasso` requires `re-frame.hicasso.tool`, so a
    // freshly-watched build has not compiled it and this host is, for the
    // moment, indistinguishable from a Reagent/UIx app: the population the
    // `:evidence-tier-unavailable` rung is written for.
    const doorLoaded = await callTool(client, 'eval-cljs', {
      form: `(cljs.core/some? (cljs.core/find-ns-obj "${DOOR_NS}"))`,
      build,
    });
    assert(!doorLoaded.isError, 'the door-presence probe returned isError: ' + doorLoaded.text);
    assert(
      /:value false\b/.test(doorLoaded.text),
      `${DOOR_NS} is ALREADY loaded in this build, so the door-absent rung cannot ` +
        'be witnessed. This script loads the door itself further down, for the ' +
        'life of the watch — restart `shadow-cljs watch hicasso/hmr-testbed` and ' +
        're-run. Probe answered: ' + doorLoaded.text,
    );
    for (const read of DOOR_READS) {
      const r = await callTool(client, read, { build, 'max-tokens': 0 });
      // Every `:ok? false` rides isError:true, so a degraded read is never
      // cached and never reads as a successful answer.
      assert(r.isError, `${read} answered a door-less app without isError: ${r.text}`);
      assert(
        r.text.includes(':reason :evidence-tier-unavailable'),
        `${read} did not reach the documented absent-door rung against a build ` +
          `that has never loaded ${DOOR_NS}. rf2-t2ec: a form that REFERENCES a ` +
          'var in an unloaded namespace is rejected by shadow\'s analyzer before ' +
          'any branch of it runs, and the answer is a raw compile warning ' +
          `instead. Got: ${r.text.slice(0, 500)}`,
      );
      assert(
        !r.text.includes(':rf.error/eval-cljs-compile-error'),
        `${read} answered the absent door with an analyzer compile error — the ` +
          `exact rf2-t2ec regression: ${r.text.slice(0, 500)}`,
      );
      assert(
        r.text.includes(`Load ${DOOR_NS} into the running build and retry`),
        `${read} reached the rung without the hint an operator acts on: ${r.text.slice(0, 500)}`,
      );
      console.log(`OK   ${read} (door absent) -> :evidence-tier-unavailable + load-the-door hint`);
    }

    // ---- Boot the slice through its own entry point ----------------------
    await evalCljs(client, `(require '${SLICE}.app)`, 'require the slice');
    // The slice mounts into `#app`, and the page hosting the runtime is
    // somebody else's, so the container is supplied rather than assumed —
    // the only thing this script contributes to the application. It is
    // created in the SAME form as the mount: shadow pushes modules to the
    // page as the requires above are compiled, and a container planted from
    // the Playwright side minutes earlier is not guaranteed to still be
    // there when `-main` looks for it.
    await evalCljs(
      client,
      '(do (when-not (js/document.getElementById "app")' +
        ' (let [d (js/document.createElement "div")]' +
        ' (set! (.-id d) "app") (.appendChild js/document.body d)))' +
        ` (${SLICE}.app/-main) :booted)`,
      'boot the slice',
    );
    // Nothing in `re-frame.hicasso` requires the door — that is how a
    // production build never loads it — so a Hicasso app has it only once
    // something pulls it in. Xray does; here this line does. It is also the
    // line that ends the door-absent population above, for the life of the
    // watch rather than of this process.
    await evalCljs(client, `(require '${DOOR_NS})`, 'load the evidence door');
    console.log('OK   slice booted by its own -main; evidence door loaded');

    // ---- Seed the secret through the slice's OWN events ------------------
    await evalCljs(
      client,
      `(re-frame.core/with-frame ${FRAME}` +
        ` (re-frame.core/dispatch-sync [:rf.route/navigate {:to :${SLICE}.routes/article :params {:slug "hicasso"}}])` +
        ` (re-frame.core/dispatch-sync [:${SLICE}.events/edit "hicasso" :title "${THE_SECRET}"])` +
        ' :seeded)',
      'seed the secret',
    );
    // React commits the article route's boundaries on a later tick, and the
    // rosters below are about what is MOUNTED. The readiness signal is the
    // roster itself rather than a DOM selector: this witness is about the
    // projection, and coupling its setup to the editor's markup would make
    // an unrelated view change read as an evidence failure.
    const mountDeadline = Date.now() + 30000;
    let ready = '';
    while (Date.now() < mountDeadline) {
      const r = await callTool(client, 'read-mounted-boundaries', { build, 'max-tokens': 0 });
      ready = r.text;
      if (!r.isError && ready.includes(`:${SLICE}.subs/draft`)) break;
      await new Promise((res) => setTimeout(res, 500));
    }
    assert(
      ready.includes(`:${SLICE}.subs/draft`),
      'the article route never appeared in the mounted roster, so the secret ' +
        'is not being read by any boundary: ' + ready.slice(0, 600),
    );
    console.log('OK   article route mounted, secret seeded into the draft');

    // ---- NON-VACUITY: the wire really can carry this value ---------------
    const sub = await callTool(client, 'read-sub', {
      sub: `[:${SLICE}.subs/draft "hicasso"]`,
      frame: FRAME,
      build,
      'max-tokens': 0,
    });
    assert(!sub.isError, 'read-sub returned isError: ' + sub.text);
    assert(
      sub.text.includes(THE_SECRET),
      'NON-VACUITY FAILED: read-sub did not carry the seeded secret, so the ' +
        'absence assertions below would be passing against a runtime that ' +
        'never held it: ' + sub.text,
    );
    console.log('OK   read-sub carries the secret — the absence rows below are about the DOOR');

    // ---- The three door reads, live -------------------------------------
    for (const read of DOOR_READS) {
      // `max-tokens: 0` disables the cap: a capped response is an overflow
      // marker, not the projection, and there is nothing to assert on one.
      const r = await callTool(client, read, { build, 'max-tokens': 0 });
      assert(!r.isError, `${read} returned isError: ${r.text}`);
      assert(
        OK_AND_SCHEMA.test(r.text.trim()),
        `${read} did not answer :ok? true with the schema this build consumes ` +
          `(${EXPECTED_SCHEMA}) — head was: ${r.text.slice(0, 200)}`,
      );
      // Non-empty, and about the SLICE: every id here was registered by
      // rf2-hic-025 for another purpose.
      //
      // The frame id is asserted UNQUALIFIED by its key because the three
      // reads do not spell it the same way — the two rosters carry
      // `:frame`, attribution's edges carry `:frame-id`, and a boundary KEY
      // carries it positionally. That is a wire-shape qualifier a consumer
      // meets, and it was found by writing the assertion the other way and
      // watching `read-read-attribution` fail it.
      assert(
        r.text.includes(`${SLICE}.app/frame`),
        `${read} answered without a single ${SLICE} boundary — the projection ` +
          `is not reading the booted application: ${r.text.slice(0, 400)}`,
      );
      assert(
        r.text.includes(`:${SLICE}.subs/draft`),
        `${read} did not name ::subs/draft, the read whose value the secret is`,
      );
      // THE NEGATIVE CONTROL. The door promises no read value at any
      // classification; `read-sub` above proved the value is there to leak.
      assert(
        !r.text.includes(THE_SECRET),
        `EGRESS: the ${read} envelope carried the seeded secret at the wire`,
      );
      console.log(
        `OK   ${read} -> :ok? true, ${EXPECTED_SCHEMA}, slice boundaries present, secret absent`,
      );
    }

    console.log('\nRE-FRAME2-PAIR-MCP LIVE HICASSO WIRE WITNESS GREEN');
  } finally {
    if (client && client.child) {
      try {
        client.child.kill();
      } catch {
        // best effort
      }
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
