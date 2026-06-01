// Live-re-frame2-pair MCP-client conformance variant exercising the
// `notifications/progress` streaming wire surface.
// Source: rf2-zb5z6 (rf2-i3ffz F-GAP-1 follow-on).
//
// ## What this test guards
//
// The sibling `end-to-end-re-frame2-pair.cjs` runs `subscribe` only in
// *degraded* mode (no nREPL → every response is the same
// `:nrepl-port-not-found` error envelope). The live overflow harness
// (`live-re-frame2-pair-overflow.cjs`) only exercises `eval-cljs`. Before this
// gate landed, **no test ever observed a real
// `notifications/progress` frame from the server** — re-frame2-pair-mcp's
// streaming surface (per NAMING.md §"subscribe / unsubscribe": one
// progress frame per matching batch) had zero wire conformance.
//
// This variant fills that gap. With a real nREPL connected:
//
//   1. We pass a request-scoped `onprogress` callback to the SDK
//      `tools/call` — the same progress path a real MCP-aware
//      consumer (Claude Code, Continue, …) would use.
//   2. We `tools/call subscribe` to `:trace` with a short
//      `max-ms` (1500ms) so the tool returns promptly with a
//      terminal summary.
//   3. While subscribe streams, we `tools/call dispatch` a known
//      event from a concurrent task — the trace bus emission flows
//      back through subscribe's drain loop and produces at least one
//      `notifications/progress` tick.
//   4. We assert the collected progress frames pass the canonical
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
//     direct JS-side presence check would be vacuous (rf2-ee38b.20).
//   - `_meta.data` slot shape drift (`:dropped-events`, `:dropped-bytes`,
//     `:overflow-reason` are the documented contract slots).
//   - subscribe entirely failing to emit a progress frame on a
//     well-formed dispatch (e.g. drain loop regression).
//
// ## Gating
//
// **Skipped unless `$SHADOW_CLJS_NREPL_PORT` is set.** Same posture as
// `live-re-frame2-pair-overflow.cjs`: without a live nREPL the server runs
// degraded and `subscribe` returns `isError: true` rather than
// streaming. On CI the gate is unset by default → exits 0 with a SKIP
// marker. The hermetic orchestrator
// (`scripts/run-live-re-frame2-pair-overflow-hermetic.cjs`) wires the env when
// run as part of the hermetic suite.

// ## DRY-on-3 trigger (rf2-1bwph)
//
// This file and its sibling `live-re-frame2-pair-overflow.cjs` share
// LIVE-specific fixture setup (nREPL gating via $SHADOW_CLJS_NREPL_PORT,
// SDK Client construction against the spawned server, watchdog ceremony).
// The shared `_runner.cjs` already covers the common SDK-spawn ceremony.
// We deliberately do NOT factor the LIVE-specific setup at 2 files —
// factoring at 2 is premature; the right shape only emerges at 3. When a
// 3rd `live-*` script lands, lift the shared bits per rf2-1bwph.

const path = require('node:path');
const os = require('node:os');
const { runWithWatchdog } = require('./_runner.cjs');

const SERVER = path.resolve(__dirname, '..', '..', 're-frame2-pair-mcp', 'out', 'server.js');

// Topic to subscribe to. `:trace` is the universal bus — every
// `dispatch` lands on it, so we get a guaranteed emission without
// needing to wait for the runtime to push an unrelated frame. Per
// re-frame2-pair-mcp's spec the four valid topics are `trace | epoch | fx | error`.
const TOPIC = 'trace';

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
// `progressToken` is deliberately ABSENT from this table (rf2-ee38b.20
// correctness fix). The MCP SDK's `_onprogress` destructures
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
      // gate. We boot with `--no-eval` (the opt-out post-rf2-a0z0h)
      // because the eval-cljs WIRE check below asserts the
      // disabled-envelope crosses the wire when eval has been opted
      // OUT — that is the only configuration where the disabled gate
      // is reachable post-default-flip. The streaming bus surface is
      // the load-bearing test target; the eval probe rides alongside.
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
      // `progressToken` presence check would be vacuous (rf2-ee38b.20).
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

    // Yield briefly so subscribe has a chance to install its drain
    // loop before the dispatch lands. The runtime's poll cadence is
    // ~250ms — a 100ms head-start guarantees the first tick window
    // catches our event.
    await new Promise((r) => setTimeout(r, 100));

    const dispatchPromise = client.callTool({
      name: 'dispatch',
      // Per re-frame2-pair-mcp's `dispatch` schema the arg slot is `event` (a
      // single EDN-vector string, parsed server-side per rf2-vflrg).
      // An earlier draft used `event-v` (the runtime-side `pair-dispatch!`
      // ARG name); that doesn't match the MCP tool's `inputSchema` —
      // the server rejected with `:reason :missing-event` and the
      // streaming trace bus never fired (no event → no trace frame →
      // 0 ticks → :max-ms-reached with delivered 0).
      //
      // The event MUST be a handler that is REGISTERED in the fixture
      // (rf2-3bu3d.3): `dispatch` now validates the event-id against the
      // LIVE registry at the wire boundary and REFUSES an unknown id with
      // `:ok? false :dispatched? false :nearest [...]` — it does NOT enter
      // the dispatch loop, so an unregistered probe event fires NO trace
      // cascade and the streaming gate sees 0 ticks. The earlier
      // `[:rf-conformance/subscribe-probe :hello]` probe pre-dated that
      // call-time validation; under the old contract any event vector
      // entered the loop and emitted a `:rf.event/dispatched` trace even
      // with no handler. We dispatch the fixture's real `:counter/inc`
      // event-db handler (counter/core.cljs) so the dispatch actually
      // lands and the trace bus emits — the precondition for the
      // `notifications/progress` frame this test exists to observe. The
      // handler is a pure `(update :count inc)`.
      //
      // `:queued true` routes through the runtime `pair-dispatch!`
      // transport-ack path rather than the default `dispatch-consequence!`
      // (rf2-3bu3d.2) — see the NOTE block below for why (the fixture
      // boots with epoch recording at depth 0, so the consequence read
      // would report `:no-epoch-recorded`; the trace cascade fires
      // regardless of epoch recording).
      arguments: { event: '[:counter/inc]', queued: true },
    });

    // Wait for both calls to settle. subscribe returns the terminal
    // summary; dispatch returns whatever the runtime echoed.
    const [subResp, dispatchResp] = await Promise.all([subscribePromise, dispatchPromise]);

    // Surface the dispatch result for diagnostics. We do NOT fail on it:
    // this gate's load-bearing assertion is the `notifications/progress`
    // frame, which the trace bus emits whenever the event runs. The
    // dispatch's OWN result is a separate concern.
    //
    // Note on the dispatch envelope under the new contract: we send
    // `:queued true` (below) so the dispatch routes through the runtime's
    // `pair-dispatch!` transport-ack path rather than the default
    // `dispatch-consequence!` (rf2-3bu3d.2). `dispatch-consequence!`
    // reads back the RECORDED EPOCH to report `:db-changed?` /
    // `:changed-paths`; the re-frame2-pair fixture boots with epoch
    // recording at depth 0 (it never enables it), so the consequence path
    // returns `:ok? false :reason :no-epoch-recorded` even though the
    // event dispatched and the TRACE cascade fired. The trace bus is
    // independent of epoch recording, so `:queued` gives us the cascade
    // (the progress-frame precondition) without coupling this streaming
    // gate to the fixture's epoch-recording posture. `:counter/inc` is a
    // registered fixture handler, so the rf2-3bu3d.3 event-id validation
    // passes regardless of path.
    if (dispatchResp && dispatchResp.isError) {
      console.log(
        'NOTE dispatch returned isError (non-fatal — the trace cascade is ' +
          'what this gate needs): ' +
          (dispatchResp.content?.[0]?.text || '').slice(0, 160),
      );
    } else {
      console.log('OK   tools/call dispatch [:counter/inc] (:queued) acked');
    }

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

    // Now the load-bearing assertion: at least one
    // `notifications/progress` frame arrived from the streaming
    // window. Zero frames means subscribe's drain loop never delivered
    // — either the bus emission never made it (regression) or the
    // notification handler installation never reached the server
    // (SDK regression).
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

    // ---- Operator-opt-out flag-gate WIRE conformance (rf2-a0z0h) ----
    //
    // This server booted WITH `--no-eval` (see transportSpec above) AND
    // has a live runtime attached — so it is NOT in degraded mode. The
    // eval-cljs gate flipped to default-ON in rf2-a0z0h; the disabled
    // envelope is now reachable only when the operator explicitly opts
    // OUT. The call reaches the tool body, the gate (flipped OFF by
    // --no-eval) short-circuits BEFORE touching nREPL, and the
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

    console.log('\nRE-FRAME2-PAIR-MCP LIVE SUBSCRIBE CONFORMANCE GREEN');
  },
);
