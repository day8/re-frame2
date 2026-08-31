// Live-re-frame2-pair MCP-client conformance variant exercising the
// TURN-SHAPED observation workflow (rf2-ahjbc).
//
// ## What this test guards
//
// rf2-ahjbc retired Pair-MCP's push-streaming subsystem (`subscribe` /
// `unsubscribe` / `list-streams` / `get-stream-controls` and their
// `notifications/progress` machinery). The one supported live-app
// observation workflow is TURN-SHAPED: every observation reaches the
// agent as a COMPLETED tool result — no concurrent MCP calls, no
// progress callbacks, no out-of-band teardown. This harness is the live
// end-to-end witness for that workflow (acceptance criterion 4 of the
// retirement):
//
//   1. `dispatch` an event and observe its RETURNED consequence in the
//      completed tool result (the settled epoch's summary — not a
//      progress frame).
//   2. `watch-epochs` reads the RETAINED epoch history and returns the
//      cascade we caused — causally attributed via a per-run NONCE
//      riding the trigger-event vector.
//   3. `watch-until` blocks server-side on a DATA predicate over an
//      app-db signal and returns the satisfying sample as the tool
//      result.
//   4. `record` + `read-recording` capture a change driven while the
//      recorder observes (the human/browser-driven capture path — here
//      the change is driven by a dispatch between the two calls) and
//      return the change-log as a completed read.
//
// Every call above is awaited to completion before the next fires —
// the sequential shape an ordinary coding agent drives. A regression
// that reintroduced a wait-for-notification contract, broke the
// consequence path, or emptied the retained ring turns this RED.
//
// ## Flag-gate WIRE riders (eval + writes)
//
// A second server boot (`--no-eval`, no `--allow-writes`) carries the
// two operator-gate WIRE probes that have no other honest home
// (end-to-end-flag-gates.cjs covers only story-mcp; the degraded
// end-to-end short-circuits to :nrepl-port-not-found before either
// gate runs):
//
//   - `eval-cljs` with `--no-eval` ⇒ isError + `:rf.error/eval-cljs-disabled`.
//   - `restore-epoch` / `replace-app-db` without `--allow-writes` ⇒
//     isError + `:rf.error/writes-disabled` naming the refused tool.
//
// ## Epoch recording
//
// The tiny fixture (`counter.core`) neither requires `re-frame.epoch`
// nor configures a ring depth, so out of the box the consequence path
// reports `:no-epoch-recorded` and `watch-epochs` returns `:epochs []`.
// Same posture as live-re-frame2-pair-redaction.cjs: enable recording at
// runtime via TWO SEPARATE awaited eval calls (shadow's runtime
// `require` schedules an async module load; a single `(do ...)` form
// throws — see the redaction harness header for the empirical account).
//
// ## Gating
//
// **Skipped unless `$SHADOW_CLJS_NREPL_PORT` is set.** Same posture as
// the sibling live harnesses: without a live nREPL the server runs
// degraded and every observation tool returns `:nrepl-port-not-found`.
// On CI the gate is unset by default → exits 0 with a SKIP marker. The
// hermetic orchestrator (`scripts/run-re-frame2-pair-live-hermetic-suite.cjs`)
// wires the env when run as part of the hermetic suite.

const crypto = require('node:crypto');
const path = require('node:path');
const os = require('node:os');
const {
  runWithWatchdog,
  connectServer,
  closeQuietly,
  registerAuxClient,
  unregisterAuxClient,
  responseText,
} = require('./_runner.cjs');

const SERVER = path.resolve(__dirname, '..', '..', 're-frame2-pair-mcp', 'out', 'server.js');

// Per-run unique nonce. We dispatch `[:counter/inc "<NONCE>"]` — the
// `:counter/inc` handler ignores its event args, so the extra string is
// inert, but the FULL event vector rides into the recorded epoch's
// trigger-event slot. Asserting the watch-epochs pull carries this nonce
// makes the retained-history gate CAUSAL: it proves the returned epoch
// is the cascade from OUR dispatch, not a pre-existing ring entry.
const NONCE = 'rf2-turn-probe-' + crypto.randomUUID();
const PROBE_EVENT = '[:counter/inc "' + NONCE + '"]';

// Enable epoch recording — two separate awaited evals (see header).
const ENABLE_REQUIRE_FORM = `(require 're-frame.epoch)`;
const ENABLE_CONFIGURE_FORM = `
(do
  (re-frame.core/configure! {:epoch-history {:depth 50}})
  {:hook-installed? (some? (re-frame.late-bind/get-fn :epoch/settle!))
   :depth (:depth (re-frame.epoch/current-config))})`;

// Extract the FIRST `:value <int>` slot from a get-path result's EDN
// text. get-path returns `{:ok? true :path [:count] :value <n> ...}`.
function parseCountValue(text, ctx) {
  const m = /:value (\d+)/.exec(text);
  if (!m) {
    throw new Error(ctx + ': could not parse :value <int> out of get-path result: ' + text.slice(0, 300));
  }
  return parseInt(m[1], 10);
}

function fatalIfError(label, resp) {
  if (resp.isError) {
    throw new Error(
      label + ' MUST NOT isError — the turn-shaped workflow depends on ' +
        'this completed result. Got: ' +
        (responseText(resp) || JSON.stringify(resp)).slice(0, 400),
    );
  }
}

// Pre-flight SKIP — same posture as the sibling live harnesses.
if (!process.env.SHADOW_CLJS_NREPL_PORT) {
  runWithWatchdog.skip(
    'live-re-frame2-pair-turn-observation: $SHADOW_CLJS_NREPL_PORT not set.\n' +
      '      This variant requires a live shadow-cljs nREPL — without\n' +
      '      one every observation tool runs degraded. The sibling\n' +
      '      end-to-end-re-frame2-pair.cjs covers degraded-mode protocol\n' +
      '      conformance; this variant proves the turn-shaped observation\n' +
      '      workflow (dispatch consequence, retained-history read,\n' +
      '      watch-until sample, recorder read-back) under a real runtime.',
  );
}

runWithWatchdog(
  {
    watchdogMs: 45000,
    clientName: 'mcp-conformance-re-frame2-pair-live-turn-observation',
    transportSpec: {
      command: process.execPath,
      // No gate flags: eval-cljs stays ON (the epoch-enable evals need
      // it); writes stay OFF (default) — the flag-gate riders run on
      // the second server below.
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

    // ---- Enable epoch recording (two separate awaited evals) ----
    const reqResp = await client.callTool({
      name: 'eval-cljs',
      arguments: { form: ENABLE_REQUIRE_FORM },
    });
    fatalIfError('eval-cljs (require re-frame.epoch)', reqResp);
    const cfgResp = await client.callTool({
      name: 'eval-cljs',
      arguments: { form: ENABLE_CONFIGURE_FORM },
    });
    fatalIfError('eval-cljs (configure epoch depth)', cfgResp);
    const cfgText = responseText(cfgResp) || '';
    if (!cfgText.includes(':depth 50') || !cfgText.includes(':hook-installed? true')) {
      throw new Error(
        'epoch-recording enable did not land (need :depth 50 + ' +
          ':hook-installed? true); got: ' + cfgText.slice(0, 300),
      );
    }
    console.log('OK   epoch recording enabled (depth 50, settle hook installed)');

    // ---- 1. dispatch -> consequence in the COMPLETED result ----
    //
    // Default (consequence) mode with `sync: true`: the tool result IS
    // the observation — the settled epoch's cascade summary. No
    // progress callback, no second call needed to learn what happened.
    const dispResp = await client.callTool({
      name: 'dispatch',
      arguments: { event: PROBE_EVENT, sync: true },
    });
    fatalIfError('dispatch ' + PROBE_EVENT + ' (sync)', dispResp);
    const dispText = responseText(dispResp) || '';
    if (!dispText.includes(':ok? true')) {
      throw new Error(
        'dispatch consequence result MUST carry :ok? true; got: ' +
          dispText.slice(0, 400),
      );
    }
    // The consequence must carry the settled epoch's summary — the
    // returned observation this whole workflow rests on. `:cascade-summary`
    // (with its `:epoch-id` slot) is the documented consequence shape once
    // epoch recording is on; `:no-epoch-recorded` here means the enable
    // above silently failed.
    if (!dispText.includes(':cascade-summary') || !dispText.includes(':epoch-id')) {
      throw new Error(
        'dispatch consequence MUST carry the settled epoch summary ' +
          '(:cascade-summary with :epoch-id) — the turn-shaped observation ' +
          'contract. Got: ' + dispText.slice(0, 400),
      );
    }
    console.log(
      'OK   dispatch (sync) -> completed result carries :cascade-summary + :epoch-id',
    );

    // ---- 2. watch-epochs -> the RETAINED epoch, causally ours ----
    //
    // A bounded pull over retained history: one completed call returns
    // the matching epochs. `epochs-mode "full"` ships the whole record
    // so the trigger-event (carrying our NONCE — not a declared-
    // sensitive path, so it rides raw) is visible in the result text.
    const weResp = await client.callTool({
      name: 'watch-epochs',
      arguments: { pred: { 'event-id': ':counter/inc' }, 'epochs-mode': 'full' },
    });
    fatalIfError('watch-epochs {pred {:event-id :counter/inc}}', weResp);
    const weText = responseText(weResp) || '';
    if (!weText.includes(NONCE)) {
      throw new Error(
        'watch-epochs pull MUST return the retained epoch for OUR dispatch ' +
          '(trigger-event carrying nonce ' + NONCE + '). A pull without the ' +
          'nonce is a pre-existing ring entry, not proof the retained-history ' +
          'read observes the cascade we caused. Got: ' + weText.slice(0, 500),
      );
    }
    console.log(
      'OK   watch-epochs -> completed pull carries the nonce-tagged retained epoch',
    );

    // ---- 3. watch-until -> the satisfying sample as the result ----
    //
    // Read the live count, drive one more increment, then ask the
    // server to block until [:count] equals the post-increment value.
    // The predicate holds on the first poll; the completed result
    // carries the satisfying sample. Sequential calls only — the
    // asynchronous condition is the app's, not the MCP client's.
    const gpResp = await client.callTool({
      name: 'get-path',
      arguments: { path: '[:count]' },
    });
    fatalIfError('get-path [:count]', gpResp);
    const before = parseCountValue(responseText(gpResp) || '', 'get-path [:count]');
    const incResp = await client.callTool({
      name: 'dispatch',
      arguments: { event: '[:counter/inc]', sync: true },
    });
    fatalIfError('dispatch [:counter/inc] (watch-until arm)', incResp);
    const target = before + 1;
    const wuResp = await client.callTool({
      name: 'watch-until',
      arguments: {
        signals: '[{:app-db [:count]}]',
        pred: { signal: 0, equals: target },
        'timeout-ms': 5000,
      },
    });
    fatalIfError('watch-until [:count] == ' + target, wuResp);
    const wuText = responseText(wuResp) || '';
    if (!wuText.includes(':held? true') || !wuText.includes(':sample')) {
      throw new Error(
        'watch-until MUST resolve with :held? true and the satisfying ' +
          ':sample in the completed result; got: ' + wuText.slice(0, 400),
      );
    }
    if (!wuText.includes('{0 ' + target + '}')) {
      throw new Error(
        'watch-until :sample MUST carry the matched value {0 ' + target +
          '}; got: ' + wuText.slice(0, 400),
      );
    }
    console.log(
      'OK   watch-until -> completed result carries :held? true + matching :sample {0 ' +
        target + '}',
    );

    // ---- 4. record -> drive a change -> read-recording ----
    //
    // The recorder observes while the app is driven (in production use a
    // human/browser drives it; here a dispatch between the two calls
    // stands in). The change-log comes back as a completed read.
    const recResp = await client.callTool({
      name: 'record',
      arguments: { signals: '[{:app-db [:count]}]' },
    });
    fatalIfError('record {signals [{:app-db [:count]}]}', recResp);
    const recText = responseText(recResp) || '';
    const idMatch = /:recording-id "([^"]+)"/.exec(recText);
    if (!idMatch) {
      throw new Error(
        'record MUST return a :recording-id; got: ' + recText.slice(0, 300),
      );
    }
    const recordingId = idMatch[1];
    console.log('OK   record -> recording installed (' + recordingId + ')');

    const recIncResp = await client.callTool({
      name: 'dispatch',
      arguments: { event: '[:counter/inc]', sync: true },
    });
    fatalIfError('dispatch [:counter/inc] (recorder arm)', recIncResp);

    // The recorder samples per animation frame; poll the read-back on a
    // short cadence until the change lands (bounded — the outer watchdog
    // caps the whole harness). Each poll is itself a completed tool call.
    const READ_DEADLINE = Date.now() + 8000;
    let sawChange = false;
    let lastReadText = '';
    while (Date.now() < READ_DEADLINE && !sawChange) {
      const readResp = await client.callTool({
        name: 'read-recording',
        arguments: { 'recording-id': recordingId },
      });
      fatalIfError('read-recording ' + recordingId, readResp);
      lastReadText = responseText(readResp) || '';
      // At least one change entry sampled — `:count <n>` with n >= 1.
      const cm = /:count (\d+)/.exec(lastReadText);
      if (cm && parseInt(cm[1], 10) >= 1) sawChange = true;
      if (!sawChange) await new Promise((r) => setTimeout(r, 250));
    }
    if (!sawChange) {
      throw new Error(
        'read-recording never returned a captured change for the ' +
          'dispatch driven while recording (waited 8s): ' +
          lastReadText.slice(0, 400),
      );
    }
    // Stop + tear down the recorder.
    const stopResp = await client.callTool({
      name: 'read-recording',
      arguments: { 'recording-id': recordingId, stop: true },
    });
    fatalIfError('read-recording (stop)', stopResp);
    console.log(
      'OK   record + read-recording -> driven change captured and returned as a completed read',
    );

    // ---- Flag-gate WIRE riders on a second, opt-out server ----
    //
    // Boot a SECOND server WITH `--no-eval` and WITHOUT `--allow-writes`
    // against the same live runtime — the only configuration where the
    // operator-gate refusals are reachable on the wire (degraded mode
    // short-circuits to :nrepl-port-not-found before either gate; a
    // gate-ON boot executes the call). Registered with the outer
    // watchdog the instant it is constructed so a hang here is reaped.
    const aux = await connectServer({
      clientName: 'mcp-conformance-re-frame2-pair-turn-observation-gates',
      stderrPrefix: '[server:gates]',
      transportSpec: {
        command: process.execPath,
        args: [SERVER, '--no-eval'],
        cwd: os.tmpdir(),
        env: { ...process.env },
      },
      onClient: registerAuxClient,
    });
    try {
      const evalResp = await aux.client.callTool({
        name: 'eval-cljs',
        arguments: { form: '(+ 1 2)' },
      });
      if (!evalResp.isError) {
        throw new Error(
          'eval-cljs MUST isError when the server booted WITH --no-eval ' +
            '(opt-out post-rf2-a0z0h); got: ' +
            JSON.stringify(evalResp).slice(0, 300),
        );
      }
      const evalText = responseText(evalResp) || '';
      if (!evalText.includes(':rf.error/eval-cljs-disabled')) {
        throw new Error(
          'eval-cljs --no-eval envelope MUST carry ' +
            ':rf.error/eval-cljs-disabled (NAMING.md flag-vocabulary ' +
            'contract); got: ' + evalText.slice(0, 300),
        );
      }
      console.log(
        'OK   eval-cljs --no-eval -> isError + :rf.error/eval-cljs-disabled (live, non-degraded)',
      );

      // Default-OFF write gate: both state-mutating tools MUST refuse
      // pre-nREPL with the canonical envelope naming the refused tool.
      // Args are well-formed but inert — the gate fires before the arg
      // is read.
      for (const probe of [
        { name: 'restore-epoch', arguments: { 'epoch-id': '0' } },
        { name: 'replace-app-db', arguments: { db: '{}' } },
      ]) {
        const resp = await aux.client.callTool(probe);
        if (!resp.isError) {
          throw new Error(
            probe.name + ' MUST isError when the server booted WITHOUT ' +
              '--allow-writes (default-OFF write gate, rf2-ee38b.18); got: ' +
              JSON.stringify(resp).slice(0, 300),
          );
        }
        const text = responseText(resp) || '';
        if (!text.includes(':rf.error/writes-disabled')) {
          throw new Error(
            probe.name + ' default-OFF write-gate envelope MUST carry ' +
              ':rf.error/writes-disabled; got: ' + text.slice(0, 300),
          );
        }
        if (!text.includes('"' + probe.name + '"')) {
          throw new Error(
            probe.name + ' write-gate envelope MUST name the refused tool ' +
              '(:tool "' + probe.name + '"); got: ' + text.slice(0, 300),
          );
        }
        console.log(
          'OK   ' + probe.name + ' (no --allow-writes) -> isError + ' +
            ':rf.error/writes-disabled (live, non-degraded)',
        );
      }
    } finally {
      unregisterAuxClient(aux.client);
      await closeQuietly(aux.client);
    }

    console.log('\nRE-FRAME2-PAIR-MCP LIVE TURN-OBSERVATION CONFORMANCE GREEN');
  },
);
