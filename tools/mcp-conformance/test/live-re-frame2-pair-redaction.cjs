// Live-re-frame2-pair MCP-client conformance variant exercising egress
// redaction of a DECLARED-`:sensitive?` app-db slot through the pull-mode
// epoch tools (`trace-window` + `watch-epochs`).
// Source: rf2-q4o83 (regression net for rf2-6wvh5; correctness review
// finding rf2-5t8mr.18).
//
// ## The hole this closes
//
// rf2-6wvh5 was a HIGH-severity egress leak: the pull-mode epoch tools
// `trace-window` and `watch-epochs` egressed whole `:rf/epoch-record`s
// carrying `:db-before` / `:db-after` app-db snapshots over the MCP wire
// WITHOUT routing them through the framework's off-box projection. A
// schema-declared `:sensitive?` slot (e.g. `[:auth :password]`) rode the
// wire verbatim even with the `--allow-sensitive-reads` gate OFF (the
// published-build default). The fix routes each egressed record through
// `re-frame.core/projected-record` server-side — the single normative
// off-box-egress emission site (Spec Security.md §Epoch privacy posture).
//
// The leak shipped GREEN because the cross-server mcp-conformance gate
// had NO scenario that put a sensitive value into a live app-db and
// asserted it came back redacted over the wire through these tools. The
// only `:rf/redacted` coverage in tools/mcp-conformance was source-text
// greps (wire_vocab_test.clj) and a leaf-counter indicator gate — neither
// asserts the leaf VALUE was actually replaced on egress. This test is
// the missing end-to-end wire gate: it would have caught rf2-6wvh5 RED.
//
// ## What this test drives (across the MCP boundary, via the SDK Client)
//
//   1. Boot the pair-mcp server WITHOUT `--allow-sensitive-reads`
//      (the published default — redaction MUST hold).
//   2. Via `eval-cljs` against the live runtime:
//        a. declare a `:sensitive?` slot at `[:rf-conformance/secret]`
//           in the operating frame's elision registry (the same
//           registry `populate-sensitive-from-schemas!` writes — declared
//           directly here so the tiny fixture needs no schemas artefact);
//        b. dispatch an event that writes a recognisable SENTINEL string
//           into that slot, landing it in the next epoch's `:db-after`.
//   3. `tools/call trace-window` AND `tools/call watch-epochs` — the two
//      pull-mode epoch tools that leaked.
//   4. ASSERT the sentinel does NOT appear ANYWHERE in either egress
//      payload, AND that the `:rf/redacted` scalar sentinel IS present at
//      the sensitive slot (proving the redaction WALKER fired — not that
//      the slot happened to be empty or the window happened to exclude
//      the record).
//   5. Boot a SECOND server WITH `--allow-sensitive-reads`, call the same
//      tools with `:include-sensitive true`, and assert the sentinel DOES
//      cross the wire — pinning BOTH directions of the gate so the gate
//      itself can't silently invert.
//
// ## Why this is a true regression net
//
// Revert the rf2-6wvh5 redaction (drop the `mapv projected-record` wrap
// in `tools/epoch-egress.cljs`'s `project-page-src`) and step 4 goes RED:
// the raw `:db-after` ships the sentinel and the no-leak assertion trips.
// Verified by temporary revert during development (see the PR's revert-
// verification note).
//
// ## Gating
//
// **Skipped unless `$SHADOW_CLJS_NREPL_PORT` is set.** Same posture as
// the sibling `live-re-frame2-pair-overflow.cjs` / `-subscribe.cjs`:
// without a live nREPL the server runs degraded and every tool returns
// the same `:nrepl-port-not-found` envelope — no `:db-*` ever crosses, so
// the redaction surface is unreachable. The hermetic orchestrator
// (`scripts/run-live-re-frame2-pair-overflow-hermetic.cjs`) boots
// shadow-cljs + Chromium against `skills/re-frame2-pair/tests/fixture/`
// and wires the env so the live path fires on CI.

const path = require('node:path');
const os = require('node:os');
const { runWithWatchdog, connectServer, closeQuietly } = require('./_runner.cjs');

const SERVER = path.resolve(__dirname, '..', '..', 're-frame2-pair-mcp', 'out', 'server.js');

// Recognisable sentinel written into the declared-sensitive slot. Long +
// unique so a substring search across the whole egress payload (EDN text)
// has zero false-negative risk and zero accidental collision with any
// framework key, hint string, or marker vocabulary.
const SENTINEL = 'rf2-q4o83-SECRET-do-not-leak-7f3a9c';

// The sensitive app-db path. Single segment under a `:rf-conformance/*`
// reserved-style key so it can't collide with any fixture slot.
const SECRET_KEY = ':rf-conformance/secret';

// Canonical redaction scalar (Spec Security.md §Epoch privacy posture /
// Conventions.md). projected-record substitutes the declared-sensitive
// leaf with this exact scalar keyword.
const REDACTED_SENTINEL = ':rf/redacted';

// trace-window's `:ms` window. An epoch's `:committed-at` is a MONOTONIC
// relative clock (sub-ms `performance.now()`-style float — observed ~900
// for a fresh epoch), NOT a `js/Date.now()` wall-clock timestamp.
// trace-window's window filter is `cutoff <= committed-at <= until-ms`
// with `until-ms = Date.now()` (~1.78e12) and `cutoff = until-ms - ms`. A
// modest `:ms` (e.g. 60000) yields `cutoff ≈ 1.78e12`, which excludes the
// tiny committed-at and returns `:epochs []` (the `:window-excludes-history`
// advisory fires). A `:ms` larger than wall-clock-now drives `cutoff`
// negative so every recorded epoch falls inside the window regardless of
// its relative committed-at. We want the FULL ring here, so use a window
// that always covers it. (watch-epochs has no time filter and needs no
// such widening.)
const TRACE_WINDOW_MS = 1_000_000_000_000_000;

// CLJS form (evaluated app-side via eval-cljs) that:
//   1. declares `[SECRET_KEY]` sensitive in the operating frame's elision
//      registry — the SAME `[:rf/runtime :elision :sensitive-declarations]`
//      slot `populate-sensitive-from-schemas!` writes, set directly via
//      the framework's registry helper so the fixture needs no Malli
//      schema (and no schemas artefact on its classpath);
//   2. registers + dispatch-syncs an event writing the SENTINEL into that
//      slot, landing it in the next epoch's `:db-after`.
// The secret is closed over in the handler so it never appears in the
// trigger-event vector — the schema-declared path is the only sensitive
// leaf, mirroring epoch_mcp_egress_conformance_test.clj's drive pattern.
//
// `re-frame.elision/swap-elision-slot!` is the framework's normative
// registry mutator (the same one `re-frame.marks` and the schema-populate
// path write through). We declare the path with `{:source :declared}` so
// the declaration is owned by us, not a schema refresh.
//
// Registration uses the fn-form `re-frame.events/reg-event-db` rather than
// the `re-frame.core/reg-event-db` MACRO: a runtime cljs-eval form cannot
// expand a macro the analyzer hasn't seen, so the macro call would yield
// nil. The fn-form is a plain call against the events ns the fixture
// already loads (transitively via re-frame.core). dispatch uses the
// fn-form `re-frame.core/dispatch-sync*` (the macro's runtime counterpart).
//
// Epoch recording must be ACTIVE for the pull-mode tools to have a record
// to egress. The tiny fixture (`counter.core`) neither requires
// `re-frame.epoch` nor configures a ring depth, so its dev build records
// nothing — `epoch-history` stays empty and trace-window / watch-epochs
// return `:epochs []` regardless of what we dispatch. We therefore
// (a) `(require 're-frame.epoch)` so the namespace loads and publishes the
// `:epoch/settle!` capture hook the router looks up at drain-settle, and
// (b) `(configure :epoch-history {:depth 50})` so the ring keeps records.
// This is runtime configuration, NOT a fixture-source change — it keeps
// the scenario self-contained under tools/mcp-conformance.
//
// CRITICAL: the `require`, the `configure`, and the dispatch MUST be
// THREE SEPARATE awaited eval-cljs calls. shadow's runtime `require`
// schedules an ASYNC module load; ANY form that references the
// `re-frame.epoch` ns (the `configure` knob's late-bound hook, or
// `current-config`) BEFORE that load settles fails to resolve. Within a
// single `(do (require ...) (configure ...) ...)` form the configure
// throws (`:repl/exception!`) and depth stays 0, so the dispatch records
// nothing. Splitting lets each prerequisite settle before the next call.
// Verified empirically (ai-local probe): single-form ⇒ :repl/exception! +
// :epoch-count 0; three separate calls ⇒ depth 50 + :epoch-count 1.
//
// Call 1: load the epoch namespace (publishes the `:epoch/settle!` capture
// hook the router looks up at drain-settle, and the `:epoch/configure!`
// knob the next call drives).
const ENABLE_REQUIRE_FORM = `(require 're-frame.epoch)`;

// Call 2: configure a ring depth so the buffer keeps records (default for
// this fixture is effectively disabled — no config ⇒ empty ring), then
// echo the live depth + hook state so seedRuntime can assert both landed.
const ENABLE_CONFIGURE_FORM = `
(do
  (re-frame.core/configure! :epoch-history {:depth 50})
  {:hook-installed? (some? (re-frame.late-bind/get-fn :epoch/settle!))
   :depth (:depth (re-frame.epoch/current-config))})`;

// Call 3: declare-sensitive + register + dispatch the sentinel write.
const SEED_FORM = `
(let [fid (re-frame2-pair.runtime/current-frame)]
  ;; Declare the sensitive slot on the SAME frame the epoch tools read
  ;; (the runtime operating frame the dispatch below targets), so
  ;; projected-record — which reads the record's :frame elision registry
  ;; — matches the leaf at egress.
  (re-frame.elision/swap-elision-slot!
    fid
    (fn [reg]
      (assoc-in (or reg {})
                [:sensitive-declarations [${SECRET_KEY}]]
                {:source :declared})))
  (re-frame.events/reg-event-db
    :rf-conformance/write-secret
    (fn [db _] (assoc db ${SECRET_KEY} "${SENTINEL}")))
  (re-frame.core/dispatch-sync* [:rf-conformance/write-secret] {:frame fid})
  {:frame fid
   :declared (re-frame.elision/sensitive-declarations fid)
   :epoch-count (count (re-frame.core/epoch-history fid))
   :db-secret (get (re-frame2-pair.runtime/snapshot fid) ${SECRET_KEY})})`;

// A defensive sanity form: confirm the live app-db genuinely carries the
// sentinel at the secret slot (so a green no-leak assertion can't be a
// false-pass from the write silently failing). Reads through the runtime
// `snapshot` fn the pair tools already use — but unredacted, because this
// is an internal eval, not a wire egress.
const VERIFY_WRITE_FORM =
  `(get (re-frame2-pair.runtime/snapshot (re-frame2-pair.runtime/current-frame)) ${SECRET_KEY})`;

// ---- helpers ---------------------------------------------------------------

// Extract the concatenated text of every content block from an SDK
// callTool response. The egress payload is EDN text inside
// `content[].text`; we search the whole thing for the sentinel rather
// than parsing — a leak ANYWHERE in the payload (a nested `:db-before`,
// `:trace-events`, a stray `:trigger-event`) must trip the gate.
function responseText(resp) {
  if (!resp || !Array.isArray(resp.content)) return '';
  return resp.content.map((c) => (c && typeof c.text === 'string' ? c.text : '')).join('\n');
}

function assertOk(resp, name) {
  if (resp.isError) {
    throw new Error(
      name + ' returned isError; the redaction gate cannot assert on an ' +
        'error envelope. Got: ' + responseText(resp).slice(0, 400),
    );
  }
}

// The load-bearing assertion: the SENTINEL must be absent AND the
// :rf/redacted scalar must be present at the sensitive slot. Present-
// redacted (not merely absent-sentinel) is what distinguishes "the
// redaction walker fired" from "the record fell outside the window" /
// "the slot was empty" — a window-excludes-everything regression would
// pass a bare absence check while shipping nothing.
function assertRedacted(resp, name) {
  assertOk(resp, name);
  const text = responseText(resp);
  if (text.includes(SENTINEL)) {
    throw new Error(
      name + ' LEAKED the sensitive sentinel over the MCP wire with the ' +
        '--allow-sensitive-reads gate OFF (default). The declared-sensitive ' +
        'slot ' + SECRET_KEY + ' MUST be redacted to ' + REDACTED_SENTINEL +
        ' by projected-record before egress (rf2-6wvh5 / Spec Security.md ' +
        '§Epoch privacy posture).\nPayload (first 600 chars): ' +
        text.slice(0, 600),
    );
  }
  if (!text.includes(REDACTED_SENTINEL)) {
    throw new Error(
      name + ' egress payload does NOT carry the ' + REDACTED_SENTINEL +
        ' scalar at the sensitive slot. A bare sentinel-absence check would ' +
        'false-pass if the epoch fell outside the window or the slot were ' +
        'empty; this assertion proves the redaction WALKER fired. Payload ' +
        '(first 600 chars): ' + text.slice(0, 600),
    );
  }
}

// Enable epoch recording (require → configure, two awaited calls) +
// declare-sensitive + write-sentinel (a third call) against `client`'s
// runtime. The three-call split is load-bearing — see the form comments
// above. Asserts each step landed: the hook is installed, the ring depth
// is configured, an epoch was recorded, and the sentinel is live in
// app-db. Shared by both the gate-OFF and gate-ON arms — both need an
// epoch carrying the secret.
async function seedRuntime(client, label) {
  const req = await client.callTool({ name: 'eval-cljs', arguments: { form: ENABLE_REQUIRE_FORM } });
  assertOk(req, label + ' eval-cljs require-epoch');

  const enable = await client.callTool({ name: 'eval-cljs', arguments: { form: ENABLE_CONFIGURE_FORM } });
  assertOk(enable, label + ' eval-cljs configure-epoch');
  if (!responseText(enable).includes('true')) {
    throw new Error(
      label + ' eval-cljs configure-epoch did not install the :epoch/settle! hook ' +
        '(epoch recording would stay disabled and no epoch would be recorded). ' +
        'Got: ' + responseText(enable).slice(0, 300),
    );
  }

  const seed = await client.callTool({ name: 'eval-cljs', arguments: { form: SEED_FORM } });
  assertOk(seed, label + ' eval-cljs seed');
  const seedText = responseText(seed);
  // The seed return echoes `:epoch-count` — a 0 here means the dispatch
  // recorded no epoch (recording still disabled / async require not yet
  // settled), so the pull-mode tools would egress an empty ring and the
  // no-leak assertion would be a vacuous false-pass. Pin it > 0.
  if (/:epoch-count\s+0\b/.test(seedText)) {
    throw new Error(
      label + ' eval-cljs seed recorded ZERO epochs (:epoch-count 0). The ' +
        'pull-mode tools would egress an empty ring and the no-leak assertion ' +
        'would be vacuous. Got: ' + seedText.slice(0, 300),
    );
  }
  if (!seedText.includes('sensitive-declarations') && !seedText.includes(':declared')) {
    throw new Error(
      label + ' eval-cljs seed did not confirm the sensitive declaration ' +
        'landed; got: ' + seedText.slice(0, 300),
    );
  }
  return seed;
}

// Pre-flight SKIP — same posture as the sibling live-* variants.
if (!process.env.SHADOW_CLJS_NREPL_PORT) {
  runWithWatchdog.skip(
    'live-re-frame2-pair-redaction: $SHADOW_CLJS_NREPL_PORT not set.\n' +
      '      This variant requires a live shadow-cljs nREPL — without one\n' +
      '      every tool returns the degraded :nrepl-port-not-found envelope\n' +
      '      and no :db-* ever crosses the wire, so the egress-redaction\n' +
      '      surface is unreachable. The hermetic orchestrator boots the\n' +
      '      fixture runtime and wires the env so this gate fires on CI.',
  );
}

// ---- gate-ON arm (second server) -------------------------------------------
//
// The runner manages a single client/transport (the gate-OFF arm — the
// load-bearing regression net). The gate-ON arm needs a SECOND server
// booted WITH `--allow-sensitive-reads`, so we stand up an independent
// SDK client/transport here and tear it down explicitly. Pinning the
// gate-ON direction stops the gate itself silently inverting: a
// regression that forced redaction ON even with the operator's opt-in
// would pass a gate-OFF-only test but break the operator's deliberate
// raw-state read.
async function runGateOnArm() {
  // Stand up the second server via the shared spawn+connect primitive
  // (rf2-0ogn7). The `[server:gate-on]` stderr prefix keeps this
  // concurrent boot's logs distinguishable from the runner-managed
  // gate-OFF server's `[server]` lines.
  const { client } = await connectServer({
    clientName: 'mcp-conformance-re-frame2-pair-redaction-gate-on',
    stderrPrefix: '[server:gate-on]',
    transportSpec: {
      command: process.execPath,
      args: [SERVER, '--allow-sensitive-reads'],
      cwd: os.tmpdir(),
      env: { ...process.env },
    },
  });
  try {
    // Same seed: enable epoch recording, declare the sensitive slot, and
    // write the sentinel into app-db on this server's runtime.
    await seedRuntime(client, 'gate-on');

    // With the gate ON, `:include-sensitive true` is HONOURED — the
    // operator's explicit opt-in ships raw state. The sentinel MUST cross.
    const tw = await client.callTool({
      name: 'trace-window',
      arguments: { ms: TRACE_WINDOW_MS, 'include-sensitive': true },
    });
    assertOk(tw, 'gate-on trace-window');
    if (!responseText(tw).includes(SENTINEL)) {
      throw new Error(
        'gate-on trace-window did NOT ship the sentinel with ' +
          '--allow-sensitive-reads + :include-sensitive true. The operator\'s ' +
          'explicit raw-state opt-in MUST be honoured (rf2-c2dtu gate parity); ' +
          'forcing redaction ON regardless would break the deliberate read. ' +
          'Payload (first 400 chars): ' + responseText(tw).slice(0, 400),
      );
    }
    console.log('OK   gate-ON trace-window (+:include-sensitive) -> sentinel SHIPS (opt-in honoured)');

    const we = await client.callTool({
      name: 'watch-epochs',
      arguments: { 'include-sensitive': true },
    });
    assertOk(we, 'gate-on watch-epochs');
    if (!responseText(we).includes(SENTINEL)) {
      throw new Error(
        'gate-on watch-epochs did NOT ship the sentinel with ' +
          '--allow-sensitive-reads + :include-sensitive true. Payload ' +
          '(first 400 chars): ' + responseText(we).slice(0, 400),
      );
    }
    console.log('OK   gate-ON watch-epochs (+:include-sensitive) -> sentinel SHIPS (opt-in honoured)');
  } finally {
    await closeQuietly(client);
  }
}

// ---- gate-OFF arm (primary — the regression net) ---------------------------
//
// Hard cap so a hung server doesn't wedge CI. nREPL connect + a few eval
// round-trips + two tool calls + the gate-on arm comfortably fit in 60s.
runWithWatchdog(
  {
    watchdogMs: 60000,
    clientName: 'mcp-conformance-re-frame2-pair-redaction',
    transportSpec: {
      command: process.execPath,
      // No `--allow-sensitive-reads` — the published-build default. This
      // is the configuration the leak shipped under and the only one
      // where the redaction MUST hold regardless of per-call args.
      args: [SERVER],
      cwd: os.tmpdir(),
      env: { ...process.env },
    },
  },
  async (client) => {
    console.log('OK   connect -> server attached on nREPL', process.env.SHADOW_CLJS_NREPL_PORT);

    // 1. Seed: enable epoch recording, declare the sensitive slot, dispatch
    // the sentinel write. The seed's own return value (`:declared` +
    // `:db-secret`) is an INTERNAL eval result, not a wire egress — it
    // legitimately echoes the raw secret back. That is expected and NOT a
    // leak (the gate is the tool-egress path, not the eval primitive).
    // `seedRuntime` asserts the hook installed, an epoch recorded, and the
    // declaration landed — so a silent failure can't mask a false-pass.
    await seedRuntime(client, 'gate-off');
    console.log('OK   eval-cljs seed -> epoch recorded; sensitive slot declared + sentinel written');

    // 2. Sanity: the live app-db genuinely carries the sentinel at the
    // secret slot. A green no-leak assertion below is only meaningful if
    // the secret is actually present to leak.
    const verify = await client.callTool({
      name: 'eval-cljs',
      arguments: { form: VERIFY_WRITE_FORM },
    });
    assertOk(verify, 'eval-cljs verify-write');
    if (!responseText(verify).includes(SENTINEL)) {
      throw new Error(
        'eval-cljs verify-write: the live app-db does NOT carry the sentinel ' +
          'at ' + SECRET_KEY + ' — the write never landed, so the no-leak ' +
          'assertion would be a vacuous false-pass. Got: ' +
          responseText(verify).slice(0, 400),
      );
    }
    console.log('OK   eval-cljs verify-write -> sentinel confirmed live in app-db (raw, on-box)');

    // 3a. trace-window — the first pull-mode tool that leaked. Wide window
    // (60s) so the just-dispatched epoch is comfortably inside it.
    const tw = await client.callTool({ name: 'trace-window', arguments: { ms: TRACE_WINDOW_MS } });
    assertRedacted(tw, 'trace-window');
    console.log('OK   trace-window (gate OFF) -> sentinel ABSENT + :rf/redacted PRESENT');

    // 3b. watch-epochs — the second pull-mode tool that leaked. No
    // :since-id → the full ring (which includes the sentinel epoch).
    const we = await client.callTool({ name: 'watch-epochs', arguments: {} });
    assertRedacted(we, 'watch-epochs');
    console.log('OK   watch-epochs (gate OFF) -> sentinel ABSENT + :rf/redacted PRESENT');

    // 3c. Hostile per-call opt-in MUST NOT defeat the gate. A caller
    // passing `:include-sensitive true` to a server booted WITHOUT
    // `--allow-sensitive-reads` cannot talk it into shipping raw state —
    // the boot gate forces `incl? false` regardless (rf2-c2dtu). Pin that
    // the per-call arg is powerless when the operator didn't opt in.
    const twHostile = await client.callTool({
      name: 'trace-window',
      arguments: { ms: TRACE_WINDOW_MS, 'include-sensitive': true },
    });
    assertRedacted(twHostile, 'trace-window (hostile :include-sensitive)');
    console.log(
      'OK   trace-window (gate OFF + hostile :include-sensitive true) -> still REDACTED',
    );

    // 4. Gate-ON arm: pin the other direction so the gate can't invert.
    await runGateOnArm();

    console.log('\nRE-FRAME2-PAIR-MCP LIVE EGRESS-REDACTION CONFORMANCE GREEN');
  },
);
