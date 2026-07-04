// Live-re-frame2-pair MCP-client conformance variant exercising the
// `notifications/progress` streaming wire surface.
//
// ## What this test guards
//
// The sibling `end-to-end-re-frame2-pair.cjs` runs `subscribe` only in
// *degraded* mode (no nREPL → every response is the same
// `:nrepl-port-not-found` error envelope). The live overflow harness
// (`live-re-frame2-pair-overflow.cjs`) only exercises `eval-cljs`. This
// variant is the one that observes a real `notifications/progress` frame
// from the server — pinning re-frame2-pair-mcp's streaming surface (per
// NAMING.md §"subscribe / unsubscribe": one progress frame per matching
// batch) on the wire.
//
// With a real nREPL connected:
//
//   1. We pass a request-scoped `onprogress` callback to the SDK
//      `tools/call` — the same progress path a real MCP-aware
//      consumer (Claude Code, Continue, …) would use.
//   2. We `tools/call subscribe` to `:trace` with a short
//      `max-ms` (1500ms) so the tool returns promptly with a
//      terminal summary.
//   3. While subscribe streams, we `tools/call dispatch` the fixture's
//      `[:counter/inc "<NONCE>"]` event — repeatedly, on a short cadence,
//      until a progress frame carrying that per-run NONCE is observed.
//      The trace bus emission for the cascade WE caused flows back through
//      subscribe's drain loop. Re-dispatching removes the timing race:
//      the subscription is installed asynchronously
//      (ensure-runtime! → signal-runtime! → subscribe! eval), so the
//      FIRST probe to land after registration produces the causal frame —
//      readiness is PROVEN by the observed nonce, never assumed by a clock.
//   4. We assert (a) the observed cascade is CAUSALLY ours — at least one
//      frame's `:message` carries the dispatched NONCE, so an unrelated bus
//      emission cannot false-green the gate — and (b) every collected
//      progress frame passes the canonical
//      `ReFrame2PairProgressNotificationParams` shape pinned by `wire-vocab/`
//      (the JVM-side schema). A drift between the JS-side assertion
//      below and the Malli schema trips the cross-encoding gate in
//      `wire_vocab_test.clj/js-assertProgressParams-pins-every-re-frame2-pair-
//      progress-required-field`.
//
// ## Catches
//
//   - `notifications/progress` method-name drift (the SDK progress
//     router rejects a rename before invoking `onprogress`).
//   - `progressToken` slot rename / removal (agent-host correlation
//     break) — caught INDIRECTLY: the SDK routes progress frames by
//     numeric `progressToken` correlation, so a renamed / dropped /
//     mangled token fails to route and zero frames arrive, tripping the
//     "at least one frame" gate. The slot is unobservable in the
//     `onprogress` callback (the SDK strips it before invoking us), so a
//     direct JS-side presence check would be vacuous.
//   - `_meta.data` slot shape drift (`:dropped-events`, `:dropped-bytes`,
//     `:overflow-reason` are the documented contract slots).
//   - subscribe entirely failing to emit a progress frame on a
//     well-formed dispatch (e.g. drain loop regression).
//   - the subscribe→dispatch→drain CAUSAL path silently breaking: a frame
//     that does NOT correspond to our dispatched event (e.g. the cascade
//     for `:counter/inc` never reaches the sub's queue) does not pass,
//     because the NONCE assertion requires the observed frame to be the
//     cascade we caused.
//   - a refused / failed dispatch silently passing: the probe's `isError`
//     is FATAL, so a dispatch that never landed fails the gate rather
//     than riding alongside an unrelated frame.
//
// ## Flag-gate WIRE riders (eval + writes)
//
// This server boots non-degraded (live nREPL) with `--no-eval` and
// WITHOUT `--allow-writes` — the only configuration where pair-mcp's
// default-gated refusals are reachable on the wire (degraded mode
// short-circuits to `:nrepl-port-not-found` BEFORE the tool-body gate; a
// gate-ON boot executes the call). So alongside the streaming surface we
// ride two flag-gate WIRE conformance probes that have no other honest
// home (end-to-end-flag-gates.cjs covers only story-mcp; the degraded
// end-to-end never reaches these gates):
//
//   - `eval-cljs` with `--no-eval` ⇒ isError + `:rf.error/eval-cljs-disabled`
//     (the opt-out eval gate; eval-cljs is ON by default).
//   - `restore-epoch` / `replace-app-db` with `--allow-writes` ABSENT ⇒
//     isError + `:rf.error/writes-disabled` (the default-OFF write gate).
//     This pins the pair-mcp half of the NAMING.md cross-server write-gate
//     contract — a default-flip / flag-rename / dropped gate-check for
//     either state-mutating tool ships RED.
//
// ## Gating
//
// **Skipped unless `$SHADOW_CLJS_NREPL_PORT` is set.** Same posture as
// `live-re-frame2-pair-overflow.cjs`: without a live nREPL the server runs
// degraded and `subscribe` returns `isError: true` rather than
// streaming. On CI the gate is unset by default → exits 0 with a SKIP
// marker. The hermetic orchestrator
// (`scripts/run-re-frame2-pair-live-hermetic-suite.cjs`) wires the env when
// run as part of the hermetic suite.

// ## DRY-on-3 resolution
//
// This file and its `live-re-frame2-pair-*.cjs` siblings (overflow,
// redaction) share the nREPL SKIP-gate (via $SHADOW_CLJS_NREPL_PORT) and
// the SDK-spawn ceremony. Both are now factored: the SKIP gate routes
// through `runWithWatchdog.skip`, and the spawn+connect+teardown ceremony
// is `_runner.cjs`'s `connectServer` / `closeQuietly` (the redaction arm's
// second-server boot uses it directly). What is NOT shared — by design —
// is each variant's data-driven field validator (`REQUIRED_PARAMS` /
// `REQUIRED_DATA` + `assertProgressParams` here; `REQUIRED_FIELDS` +
// `assertOverflowBody` in overflow). Each pins a DISTINCT Malli schema
// (`ReFrame2PairProgressNotificationParams` vs `ReFrame2PairOverflowBody`)
// and the JVM cross-encoding gate in `wire_vocab_test.clj` greps each
// function's literal source rows by name — collapsing them into one
// generic helper would dissolve that per-schema attribution and weaken
// the conformance contract.

const crypto = require('node:crypto');
const path = require('node:path');
const os = require('node:os');
const { runWithWatchdog } = require('./_runner.cjs');

const SERVER = path.resolve(__dirname, '..', '..', 're-frame2-pair-mcp', 'out', 'server.js');

// Topic to subscribe to. `:trace` is the universal bus — every
// `dispatch` lands on it, so we get a guaranteed emission without
// needing to wait for the runtime to push an unrelated frame. Per
// re-frame2-pair-mcp's spec the four valid topics are `trace | epoch | fx | error`.
const TOPIC = 'trace';

// Per-run unique nonce. We dispatch `[:counter/inc
// "<NONCE>"]` — the `:counter/inc` event-db handler ignores its event
// args, so the extra string is inert, but the FULL event vector rides
// into the trace cascade's `:event` slot and is `pr-str`'d into the
// progress frame's `:message`. Asserting at least one progress frame's
// `message` carries this nonce makes the gate CAUSAL: it proves the
// observed frame is the cascade from OUR dispatch, not an unrelated bus
// emission that happened to arrive during the window. A bare
// `frames.length > 0` check would false-green on a spurious emission
// while our dispatch raced / failed; requiring the nonce rules that out.
const NONCE = 'rf2-subscribe-probe-' + crypto.randomUUID();
const PROBE_EVENT = '[:counter/inc "' + NONCE + '"]';

// Subscribe duration. Short enough that the harness completes well
// under the runner's 60s watchdog; long enough that a single dispatch
// has time to flow through the trace bus and drain into a progress
// frame. The runtime's default poll cadence is ~250ms so 1500ms gives
// us several drain ticks.
const MAX_MS = 1500;

// Required-field table for `notifications/progress` params. Each row
// pins one slot on the Malli `ReFrame2PairProgressNotificationParams` schema
// in `wire_vocab_test.clj`; the cross-encoding gate
// `js-assertProgressParams-pins-every-re-frame2-pair-progress-required-field`
// greps for each row's literal source form. A row renamed/deleted
// trips the JVM gate.
//
// `progressToken` is deliberately ABSENT from this table. The MCP SDK's
// `_onprogress` destructures
// `progressToken` OUT of `notification.params` before invoking this
// callback (`@modelcontextprotocol/sdk/.../shared/protocol.js`:
// `const { progressToken, ...params } = notification.params`), so the
// callback NEVER sees the slot — a JS-side `params.progressToken !==
// undefined` check would only ever observe a value the test injected
// itself, never a server regression. The token IS verified, just below
// the SDK's surface: the SDK routes progress frames by `Number(
// progressToken)` correlation against the outgoing call's token, so a
// server that renamed / dropped / mangled the slot fails to route → zero
// frames arrive → the "at least one frame" gate at the bottom of this
// file catches it. The Malli schema keeps `:progressToken` (the WIRE
// genuinely carries it; the fixture test pins that shape JVM-side); only
// the JS-callback assertion is dropped, because at THAT layer the slot is
// unobservable.
const REQUIRED_PARAMS = [
  ['progress',       (v) => typeof v === 'number',   'int'],
  ['message',        (v) => typeof v === 'string',   'string'],
  ['_meta',          (v) => v && typeof v === 'object', 'map'],
];

const REQUIRED_DATA = [
  ['dropped-events', (v) => typeof v === 'number' && v >= 0, 'nat-int'],
  ['dropped-bytes',  (v) => typeof v === 'number' && v >= 0, 'nat-int'],
];

function assertProgressParams(params, ctx) {
  if (!params || typeof params !== 'object') {
    throw new Error(ctx + ': params is not a map: ' + JSON.stringify(params));
  }
  for (const [field, ok, desc] of REQUIRED_PARAMS) {
    if (!ok(params[field])) {
      throw new Error(
        ctx + ': params.' + field + ' MUST be ' + desc +
          '; got ' + JSON.stringify(params[field]) +
          '. (Schema: ReFrame2PairProgressNotificationParams.' + field + ')',
      );
    }
  }
  const data = params._meta && params._meta.data;
  if (!data || typeof data !== 'object') {
    throw new Error(
      ctx + ': params._meta.data MUST be map; got ' + JSON.stringify(data) +
        '. (Schema: ReFrame2PairProgressNotificationParams._meta.data)',
    );
  }
  for (const [field, ok, desc] of REQUIRED_DATA) {
    if (!ok(data[field])) {
      throw new Error(
        ctx + ': params._meta.data.' + field + ' MUST be ' + desc +
          '; got ' + JSON.stringify(data[field]) +
          '. (Schema: ReFrame2PairProgressNotificationParams._meta.data.' + field + ')',
      );
    }
  }
  // `:overflow-reason` is `[:maybe :string]` — present-and-string OR
  // null/undefined (server's `(when overflow-reason ...)` suppresses
  // when nil; the Malli `:maybe` covers both).
  const ov = data['overflow-reason'];
  if (ov !== null && ov !== undefined && typeof ov !== 'string') {
    throw new Error(
      ctx + ": params._meta.data['overflow-reason'] MUST be string|nil; got " +
        JSON.stringify(ov),
    );
  }
}

// Pre-flight SKIP — same posture as live-re-frame2-pair-overflow.cjs.
if (!process.env.SHADOW_CLJS_NREPL_PORT) {
  runWithWatchdog.skip(
    'live-re-frame2-pair-subscribe: $SHADOW_CLJS_NREPL_PORT not set.\n' +
      '      This variant requires a live shadow-cljs nREPL — without\n' +
      '      one subscribe runs degraded and no notifications/progress\n' +
      '      frame ever fires. The sibling end-to-end-re-frame2-pair.cjs covers\n' +
      "      degraded-mode protocol conformance; this variant adds the\n" +
      '      streaming wire-shape under real dispatch traffic.',
  );
}

runWithWatchdog(
  {
    watchdogMs: 30000,
    clientName: 'mcp-conformance-re-frame2-pair-live-subscribe',
    transportSpec: {
      command: process.execPath,
      // re-frame2-pair-mcp's subscribe tool is unaffected by the eval
      // gate. We boot with `--no-eval` (the opt-out; eval-cljs is ON by
      // default) because the eval-cljs WIRE check below asserts the
      // disabled-envelope crosses the wire when eval has been opted
      // OUT — that is the only configuration where the disabled gate
      // is reachable. The streaming bus surface is the load-bearing
      // test target; the eval probe rides alongside.
      args: [SERVER, '--no-eval'],
      cwd: os.tmpdir(),
      env: { ...process.env },
    },
  },
  async (client) => {
    console.log(
      'OK   connect -> server attached on nREPL',
      process.env.SHADOW_CLJS_NREPL_PORT,
    );

    // Collect every `notifications/progress` frame emitted while
    // subscribe is alive. `onprogress` is request-scoped: the SDK
    // creates a progressToken, stamps it onto the outgoing tools/call
    // `_meta`, then routes matching progress notifications here.
    const frames = [];
    const onProgress = (params) => {
      // The SDK strips `progressToken` out of `notification.params`
      // before invoking this callback (and routes by it internally), so
      // `params` here carries `progress` / `message` / `_meta` only. We
      // store params verbatim — `progressToken` correlation is asserted
      // implicitly by the "at least one frame arrived" gate below (a
      // renamed / dropped token fails the SDK's numeric correlation →
      // zero frames). See the REQUIRED_PARAMS comment for why a JS-side
      // `progressToken` presence check would be vacuous.
      frames.push(params);
    };

    // Fire `subscribe` and `dispatch` concurrently. subscribe blocks
    // until max-ms; dispatch lands on the trace bus while it's alive
    // and produces a frame.
    const subscribePromise = client.callTool(
      {
        name: 'subscribe',
        arguments: {
          topic: TOPIC,
          'max-ms': MAX_MS,
        },
      },
      undefined,
      { onprogress: onProgress },
    );

    // ---- Causality-based readiness, NOT a fixed-sleep proxy ----
    //
    // The server registers the runtime subscription only after
    // `ensure-runtime!` + `signal-runtime!` + the `subscribe!` nREPL eval
    // all complete — on a cold runtime that can exceed any fixed sleep, and
    // a single dispatch that raced ahead of registration would lose its
    // cascade (zero frames, or a false-green on an unrelated frame).
    //
    // So we drive readiness CAUSALLY: re-dispatch the nonce-tagged probe
    // event on a short cadence until a progress frame carrying our NONCE is
    // observed (`sawNonceFrame()`), or the subscribe window is nearly closed.
    // Each `[:counter/inc "<nonce>"]` is a harmless idempotent increment, so
    // repeating it is safe; the FIRST one to land after registration produces
    // the causal frame and the loop stops. There is no fixed-time assumption
    // — registration completing late just means a later probe is the one that
    // lands. This is the deterministic "subscription installed" signal: the
    // installed state is proven by the observed cascade, not assumed by a
    // clock.
    const sawNonceFrame = () =>
      frames.some(
        (f) => typeof f.message === 'string' && f.message.includes(NONCE),
      );

    // The event arg slot is `event` (a single EDN-vector string parsed
    // server-side). The event-id MUST be a REGISTERED fixture
    // handler — `:counter/inc` (counter/core.cljs, a pure `(update :count
    // inc)`) — so the dispatch actually lands and the trace bus emits. An
    // unregistered id would be refused (no cascade). `:queued true` routes
    // through the runtime `pair-dispatch!` transport-ack path rather than the
    // default `dispatch-consequence!`: the re-frame2-pair fixture
    // boots with epoch recording at depth 0, so the consequence path would
    // report `:no-epoch-recorded` even though the event dispatched and the
    // TRACE cascade fired. The trace bus is independent of epoch recording, so
    // `:queued` gives us the cascade without coupling this gate to the
    // fixture's epoch-recording posture.
    const dispatchProbe = () =>
      client.callTool({
        name: 'dispatch',
        arguments: { event: PROBE_EVENT, queued: true },
      });

    // First probe + retry loop. Each dispatch is FATAL on refusal (see
    // below): a refused dispatch means our cascade never ran, so we must not
    // limp on toward a frame assertion that an unrelated emission could
    // satisfy. We retry until the causal frame appears or we approach the
    // subscribe window's end (leave a ~300ms margin so the final probe still
    // has a drain tick to flow through before subscribe terminates).
    const RETRY_CADENCE_MS = 200;
    const RETRY_DEADLINE = Date.now() + MAX_MS - 300;
    let dispatchCount = 0;
    let lastDispatchResp = null;
    while (Date.now() < RETRY_DEADLINE && !sawNonceFrame()) {
      lastDispatchResp = await dispatchProbe();
      dispatchCount++;
      // ---- Dispatch result is FATAL on refusal ----
      //
      // A REFUSED dispatch (parse error, `:runtime-not-preloaded`, nREPL
      // flap, an unknown event-id) means OUR cascade never ran, so treating
      // `isError` as fatal stops the gate from limping on toward a frame
      // assertion an unrelated emission could satisfy. `:queued` returns
      // `{:ok? true :queued? true ...}` on a real enqueue; the server maps a
      // runtime `:ok? false` (or any failure) to an `isError` envelope
      // (`runtime-envelope->result`). So `isError` here is exactly
      // "the dispatch did not land" — fatal. (The benign
      // `:no-epoch-recorded`/`:settled? false` posture does NOT surface as
      // isError under `:queued`, so we stay decoupled from the fixture's
      // epoch-recording depth.)
      if (lastDispatchResp && lastDispatchResp.isError) {
        throw new Error(
          'dispatch ' + PROBE_EVENT + ' (:queued) returned isError — the ' +
            'probe dispatch did NOT land, so the trace cascade this gate ' +
            'exists to observe never fired. Failing rather than false-green ' +
            'on an unrelated frame (rf2-x0pr0). Got: ' +
            (lastDispatchResp.content?.[0]?.text ||
              JSON.stringify(lastDispatchResp)).slice(0, 300),
        );
      }
      if (sawNonceFrame()) break;
      await new Promise((r) => setTimeout(r, RETRY_CADENCE_MS));
    }
    console.log(
      'OK   tools/call dispatch ' + PROBE_EVENT + ' (:queued) acked x' +
        dispatchCount,
    );

    // Wait for subscribe to return its terminal summary (it blocks until
    // max-ms). Dispatches already settled in the loop above.
    const subResp = await subscribePromise;

    // subscribe MUST return a terminal `ok? true` summary — a
    // streaming error (e.g. nREPL flap mid-stream) would isError and
    // we'd never have observed the in-stream contract.
    if (subResp.isError) {
      throw new Error(
        'subscribe returned isError; the live streaming gate cannot ' +
          'assert wire shape on an error envelope.\n' +
          'Got: ' + JSON.stringify(subResp).slice(0, 300),
      );
    }
    console.log('OK   tools/call subscribe -> isError=false, terminal summary received');

    // First gate: at least one `notifications/progress` frame arrived
    // from the streaming window. Zero frames means subscribe's drain
    // loop never delivered — either the bus emission never made it
    // (regression) or the notification handler installation never
    // reached the server (SDK regression).
    if (frames.length === 0) {
      throw new Error(
        'no notifications/progress frame arrived during ' + MAX_MS + 'ms ' +
          'subscribe window. subscribe terminal summary: ' +
          JSON.stringify(subResp.content?.[0]?.text || '').slice(0, 300),
      );
    }
    console.log(
      'OK   notifications/progress -> ' + frames.length + ' frame(s) received',
    );

    // ---- LOAD-BEARING CAUSALITY ASSERTION ----
    //
    // "At least one frame arrived" alone is NOT proof the frame is the
    // cascade we caused: a spurious, unrelated bus emission during the
    // window could satisfy it even if OUR dispatch raced ahead of
    // subscription registration or failed silently, because the `:trace`
    // topic carries EVERY dispatch on the runtime.
    //
    // So we require at least ONE frame whose `:message` (the EDN-printed
    // batch — on `:trace` an `:event-bundles` vector, each bundle carrying its
    // `:event` slot = the dispatched event vector) contains our per-run
    // NONCE. Since we dispatched `[:counter/inc "<NONCE>"]`, the cascade's
    // `:event` slot is that exact vector and the nonce string appears in
    // the `pr-str`'d message. A frame from any OTHER source cannot carry
    // this freshly-minted UUID. This makes the gate genuinely causal:
    // it fails unless the observed progress frame corresponds to the
    // specific event we dispatched.
    const causalFrame = frames.find(
      (f) => typeof f.message === 'string' && f.message.includes(NONCE),
    );
    if (!causalFrame) {
      const messages = frames
        .map((f, i) => '#' + i + ': ' + String(f.message || '').slice(0, 200))
        .join('\n      ');
      throw new Error(
        'received ' + frames.length + ' progress frame(s) but NONE carried ' +
          'the dispatched nonce ' + JSON.stringify(NONCE) + ' in its ' +
          '`message`. The streaming gate cannot confirm the observed ' +
          'frame is the cascade from OUR `[:counter/inc <nonce>]` dispatch ' +
          '— a frame without the nonce is an unrelated bus emission, not ' +
          'proof the subscribe→dispatch→drain path delivered our event ' +
          '(rf2-x0pr0). Frame messages:\n      ' + messages,
      );
    }
    console.log(
      'OK   notifications/progress frame carries the dispatched nonce — ' +
        'cascade causally attributed to [:counter/inc <nonce>]',
    );

    // Every frame MUST satisfy the cross-MCP shape pinned by
    // `ReFrame2PairProgressNotificationParams` in wire-vocab. The SDK
    // already gates protocol-shape (method name, envelope wrapper);
    // this gate pins the params slot vocabulary.
    for (let i = 0; i < frames.length; i++) {
      assertProgressParams(frames[i], 'progress frame #' + i);
    }
    console.log(
      'OK   every frame validates against ReFrame2PairProgressNotificationParams',
    );

    // ---- Operator-opt-out flag-gate WIRE conformance ----
    //
    // This server booted WITH `--no-eval` (see transportSpec above) AND
    // has a live runtime attached — so it is NOT in degraded mode. The
    // eval-cljs gate is default-ON; the disabled envelope is reachable
    // only when the operator explicitly opts OUT. The call reaches the
    // tool body, the gate (flipped OFF by --no-eval) short-circuits BEFORE
    // touching nREPL, and the
    // canonical `:rf.error/eval-cljs-disabled` envelope crosses the
    // wire. (In degraded mode the server's `degraded-handler`
    // short-circuits every tool to `:nrepl-port-not-found` before the
    // gate runs, so the disabled envelope is unreachable there — see
    // end-to-end-flag-gates.cjs "Coverage boundary" for why the
    // pair-mcp wire check lives here, not in the no-runtime degraded
    // harness.) This pins the cross-MCP NAMING.md §"Operator-opt-in
    // CLI flag vocabulary" contract for the inverted gate: `--no-eval`
    // ⇒ documented refusal reason.
    const evalResp = await client.callTool({
      name: 'eval-cljs',
      arguments: { form: '(+ 1 2)' },
    });
    if (!evalResp.isError) {
      throw new Error(
        'eval-cljs MUST isError when the server booted WITH ' +
          '--no-eval (opt-out post-rf2-a0z0h); got: ' +
          JSON.stringify(evalResp).slice(0, 300),
      );
    }
    const evalText = evalResp.content?.[0]?.text || '';
    if (!evalText.includes(':rf.error/eval-cljs-disabled')) {
      throw new Error(
        'eval-cljs --no-eval envelope MUST carry ' +
          ':rf.error/eval-cljs-disabled (NAMING.md flag-vocabulary ' +
          'contract); got: ' + evalText.slice(0, 300),
      );
    }
    console.log(
      'OK   eval-cljs --no-eval -> isError + ' +
        ':rf.error/eval-cljs-disabled (live, non-degraded)',
    );

    // ---- Default-OFF write-gate WIRE conformance ----
    //
    // The pair-mcp `--allow-writes` gate guards the two
    // state-mutating tools `restore-epoch` (time-travel undo) and
    // `replace-app-db` (state injection). It is DEFAULT-OFF — the
    // published-build posture — and this server booted WITHOUT
    // `--allow-writes`, so the gate is closed. Each tool body checks
    // `writes/writes-allowed?` FIRST (restore_epoch.cljs:59,
    // replace_app_db.cljs) and short-circuits to the canonical
    // `{:ok? false :reason :rf.error/writes-disabled :tool "<name>"}`
    // envelope WITHOUT touching the nREPL socket.
    //
    // This probe is the wire-level conformance coverage for that refusal:
    // NAMING.md §"Operator-opt-in CLI flag vocabulary" pins it as a
    // cross-server contract (pair-mcp's half of the same gate story
    // story-mcp's --allow-writes covers in end-to-end-flag-gates.cjs).
    // The other gates do not reach it:
    //   - end-to-end-re-frame2-pair.cjs runs DEGRADED — the
    //     degraded-handler short-circuits every tools/call to
    //     :nrepl-port-not-found in `ensure-connection!` BEFORE the tool
    //     body's write-gate runs, so the writes-disabled envelope is
    //     unreachable there (it also never calls these two tools).
    //   - end-to-end-flag-gates.cjs covers ONLY story-mcp's --allow-writes.
    //   - the live tests boot gate-OFF but never CALLED these tools.
    // A regression that flipped the default to ON, renamed the gate flag,
    // or dropped the writes.cljs gate-check for either tool would have
    // shipped GREEN through every conformance gate.
    //
    // This is the SAME shape as the eval-cljs probe directly above: a
    // non-degraded, gate-OFF live server is the ONLY configuration where
    // the disabled envelope is reachable on the wire (degraded
    // short-circuits before it; gate-ON executes the write). It is the
    // honest home for the pair-mcp WRITE-gate wire check for the exact
    // reason end-to-end-flag-gates.cjs's "Coverage boundary" comment
    // routes the pair-mcp EVAL-gate check here. We probe BOTH gated tools
    // — `restore-epoch` AND `replace-app-db` — so a gate-check dropped
    // from one tool but not the other is caught.
    //
    // The `arguments` are well-formed (a parseable epoch-id / db EDN
    // string) but inert: the gate short-circuits before the arg is ever
    // read or the nREPL touched, so neither call can mutate the live
    // app-db. Each MUST isError + carry :rf.error/writes-disabled AND name
    // the refused tool in the envelope.
    const WRITE_GATE_PROBES = [
      { name: 'restore-epoch', arguments: { 'epoch-id': '0' } },
      { name: 'replace-app-db', arguments: { db: '{}' } },
    ];
    for (const probe of WRITE_GATE_PROBES) {
      const resp = await client.callTool(probe);
      if (!resp.isError) {
        throw new Error(
          probe.name + ' MUST isError when the server booted WITHOUT ' +
            '--allow-writes (default-OFF write gate, rf2-ee38b.18); the ' +
            'state-mutating surface is reachable unauthorised. got: ' +
            JSON.stringify(resp).slice(0, 300),
        );
      }
      const text = resp.content?.[0]?.text || '';
      if (!text.includes(':rf.error/writes-disabled')) {
        throw new Error(
          probe.name + ' default-OFF write-gate envelope MUST carry ' +
            ':rf.error/writes-disabled (NAMING.md cross-server flag-vocabulary ' +
            'contract; pair-mcp writes.cljs/disabled-result); got: ' +
            text.slice(0, 300),
        );
      }
      // The envelope names the refused tool (`:tool "<name>"`) so an
      // agent's hint is specific — assert it matches the tool we called,
      // catching a copy-paste regression that returned the wrong tool's
      // refusal (which would still carry the reason).
      if (!text.includes('"' + probe.name + '"')) {
        throw new Error(
          probe.name + ' write-gate envelope MUST name the refused tool ' +
            '(:tool "' + probe.name + '" per writes.cljs/disabled-result); ' +
            'got: ' + text.slice(0, 300),
        );
      }
      console.log(
        'OK   ' + probe.name + ' (no --allow-writes) -> isError + ' +
          ':rf.error/writes-disabled (live, non-degraded)',
      );
    }

    console.log('\nRE-FRAME2-PAIR-MCP LIVE SUBSCRIBE CONFORMANCE GREEN');
  },
);
