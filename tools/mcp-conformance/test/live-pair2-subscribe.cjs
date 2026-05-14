// Live-pair2 MCP-client conformance variant exercising the
// `notifications/progress` streaming wire surface.
// Source: rf2-zb5z6 (rf2-i3ffz F-GAP-1 follow-on).
//
// ## What this test guards
//
// The sibling `end-to-end-pair2.cjs` runs `subscribe` only in
// *degraded* mode (no nREPL → every response is the same
// `:nrepl-port-not-found` error envelope). The live overflow harness
// (`live-pair2-overflow.cjs`) only exercises `eval-cljs`. Before this
// gate landed, **no test ever observed a real
// `notifications/progress` frame from the server** — pair2-mcp's
// streaming surface (per NAMING.md §"subscribe / unsubscribe": one
// progress frame per matching batch) had zero wire conformance.
//
// This variant fills that gap. With a real nREPL connected:
//
//   1. We register an MCP notification handler for the SDK's
//      `ProgressNotificationSchema` — the same handler shape a real
//      MCP-aware consumer (Claude Code, Continue, …) would install.
//   2. We `tools/call subscribe` to `:trace` with a short
//      `max-ms` (1500ms) so the tool returns promptly with a
//      terminal summary.
//   3. While subscribe streams, we `tools/call dispatch` a known
//      event from a concurrent task — the trace bus emission flows
//      back through subscribe's drain loop and produces at least one
//      `notifications/progress` tick.
//   4. We assert the collected progress frames pass the canonical
//      `Pair2ProgressNotificationParams` shape pinned by `wire-vocab/`
//      (the JVM-side schema). A drift between the JS-side assertion
//      below and the Malli schema trips the cross-encoding gate in
//      `wire_vocab_test.clj/js-assertProgressParams-pins-every-pair2-
//      progress-required-field`.
//
// ## Catches
//
//   - `notifications/progress` method-name drift (the SDK rejects a
//     rename via its `ProgressNotificationSchema` parse step).
//   - `progressToken` slot rename / removal (agent-host correlation
//     break).
//   - `:data` slot shape drift (`:dropped-events`, `:dropped-bytes`,
//     `:overflow-reason` are the documented contract slots).
//   - subscribe entirely failing to emit a progress frame on a
//     well-formed dispatch (e.g. drain loop regression).
//
// ## Gating
//
// **Skipped unless `$SHADOW_CLJS_NREPL_PORT` is set.** Same posture as
// `live-pair2-overflow.cjs`: without a live nREPL the server runs
// degraded and `subscribe` returns `isError: true` rather than
// streaming. On CI the gate is unset by default → exits 0 with a SKIP
// marker. The hermetic orchestrator
// (`scripts/run-live-pair2-overflow-hermetic.cjs`) wires the env when
// run as part of the hermetic suite.

const path = require('node:path');
const os = require('node:os');
const { ProgressNotificationSchema } = require('@modelcontextprotocol/sdk/types.js');
const { runWithWatchdog } = require('./_runner.cjs');

const SERVER = path.resolve(__dirname, '..', '..', 'pair2-mcp', 'out', 'server.js');

// Topic to subscribe to. `:trace` is the universal bus — every
// `dispatch` lands on it, so we get a guaranteed emission without
// needing to wait for the runtime to push an unrelated frame. Per
// pair2-mcp's spec the four valid topics are `trace | epoch | fx | error`.
const TOPIC = 'trace';

// Subscribe duration. Short enough that the harness completes well
// under the runner's 60s watchdog; long enough that a single dispatch
// has time to flow through the trace bus and drain into a progress
// frame. The runtime's default poll cadence is ~250ms so 1500ms gives
// us several drain ticks.
const MAX_MS = 1500;

// Required-field table for `notifications/progress` params. Each row
// pins one slot on the Malli `Pair2ProgressNotificationParams` schema
// in `wire_vocab_test.clj`; the cross-encoding gate
// `js-assertProgressParams-pins-every-pair2-progress-required-field`
// greps for each row's literal source form. A row renamed/deleted
// trips the JVM gate.
const REQUIRED_PARAMS = [
  ['progressToken',  (v) => v !== undefined,         'present (opaque)'],
  ['progress',       (v) => typeof v === 'number',   'int'],
  ['message',        (v) => typeof v === 'string',   'string'],
  ['data',           (v) => v && typeof v === 'object', 'map'],
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
          '. (Schema: Pair2ProgressNotificationParams.' + field + ')',
      );
    }
  }
  for (const [field, ok, desc] of REQUIRED_DATA) {
    if (!ok(params.data[field])) {
      throw new Error(
        ctx + ': params.data.' + field + ' MUST be ' + desc +
          '; got ' + JSON.stringify(params.data[field]) +
          '. (Schema: Pair2ProgressNotificationParams.data.' + field + ')',
      );
    }
  }
  // `:overflow-reason` is `[:maybe :string]` — present-and-string OR
  // null/undefined (server's `(when overflow-reason ...)` suppresses
  // when nil; the Malli `:maybe` covers both).
  const ov = params.data['overflow-reason'];
  if (ov !== null && ov !== undefined && typeof ov !== 'string') {
    throw new Error(
      ctx + ": params.data['overflow-reason'] MUST be string|nil; got " +
        JSON.stringify(ov),
    );
  }
}

// Pre-flight SKIP — same posture as live-pair2-overflow.cjs.
if (!process.env.SHADOW_CLJS_NREPL_PORT) {
  runWithWatchdog.skip(
    'live-pair2-subscribe: $SHADOW_CLJS_NREPL_PORT not set.\n' +
      '      This variant requires a live shadow-cljs nREPL — without\n' +
      '      one subscribe runs degraded and no notifications/progress\n' +
      '      frame ever fires. The sibling end-to-end-pair2.cjs covers\n' +
      "      degraded-mode protocol conformance; this variant adds the\n" +
      '      streaming wire-shape under real dispatch traffic.',
  );
}

runWithWatchdog(
  {
    watchdogMs: 30000,
    clientName: 'mcp-conformance-pair2-live-subscribe',
    transportSpec: {
      command: process.execPath,
      // pair2-mcp's subscribe tool itself is not gated behind
      // `--allow-eval`; we boot without that flag because the dispatch
      // event we fire below does NOT exercise the eval surface. The
      // streaming bus surface is the load-bearing test target.
      args: [SERVER],
      cwd: os.tmpdir(),
      env: { ...process.env },
    },
  },
  async (client) => {
    console.log(
      'OK   connect -> server attached on nREPL',
      process.env.SHADOW_CLJS_NREPL_PORT,
    );

    // Collect every `notifications/progress` frame the server emits
    // while subscribe is alive. The SDK's handler validates each frame
    // against `ProgressNotificationSchema` BEFORE invoking our
    // callback — a malformed envelope throws inside the SDK and the
    // frame never reaches us. That's the load-bearing protocol gate;
    // the shape assertions below cover the cross-MCP contract on top.
    const frames = [];
    client.setNotificationHandler(ProgressNotificationSchema, async (notification) => {
      frames.push(notification.params);
    });

    // Fire `subscribe` and `dispatch` concurrently. subscribe blocks
    // until max-ms; dispatch lands on the trace bus while it's alive
    // and produces a frame.
    const subscribePromise = client.callTool({
      name: 'subscribe',
      arguments: {
        topic: TOPIC,
        'max-ms': MAX_MS,
      },
    });

    // Yield briefly so subscribe has a chance to install its drain
    // loop before the dispatch lands. The runtime's poll cadence is
    // ~250ms — a 100ms head-start guarantees the first tick window
    // catches our event.
    await new Promise((r) => setTimeout(r, 100));

    const dispatchPromise = client.callTool({
      name: 'dispatch',
      arguments: { 'event-v': '[:rf-conformance/subscribe-probe :hello]' },
    });

    // Wait for both calls to settle. subscribe returns the terminal
    // summary; dispatch returns whatever the runtime echoed.
    const [subResp, dispatchResp] = await Promise.all([subscribePromise, dispatchPromise]);

    // Surface dispatch result for diagnostics (don't fail the test on
    // its result — the trace-bus emission is what we care about, and
    // a `:rf-conformance/*` event with no handler is a no-op).
    if (dispatchResp && dispatchResp.isError) {
      console.log(
        'NOTE dispatch returned isError (no handler is expected): ' +
          (dispatchResp.content?.[0]?.text || '').slice(0, 120),
      );
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
    // `Pair2ProgressNotificationParams` in wire-vocab. The SDK
    // already gates protocol-shape (method name, envelope wrapper);
    // this gate pins the params slot vocabulary.
    for (let i = 0; i < frames.length; i++) {
      assertProgressParams(frames[i], 'progress frame #' + i);
    }
    console.log(
      'OK   every frame validates against Pair2ProgressNotificationParams',
    );

    console.log('\nPAIR2-MCP LIVE SUBSCRIBE CONFORMANCE GREEN');
  },
);
