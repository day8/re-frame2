// Live-re-frame2-pair MCP-client conformance variant pinning the EP-0017
// recordable-coeffects (`cofx`) paths over the real MCP SDK boundary.
// Source: rf2-jyxmtq (senior-review coverage gap, tracking rf2-skhlw2).
//
// ## The hole this closes
//
// Before this gate, `tools/mcp-conformance` never exercised the EP-0017
// reproducible-dispatch / `cofx` MCP paths against a LIVE runtime:
//
//   - `end-to-end-re-frame2-pair.cjs` runs DEGRADED — the server's
//     `degraded-handler` short-circuits `dispatch` (and every other
//     live-runtime tool) to the shared `:nrepl-port-not-found` envelope
//     BEFORE the tool body's `cofx` shape-check runs, so neither the
//     malformed-`cofx` refusal NOR the positive "supplied `:rf/time-ms`
//     reaches the resulting state" affordance was observable on the wire.
//     (It DOES now prove a well-formed `cofx` arg is ACCEPTED into the
//     request envelope — but that is descriptor/arg-shape coverage, not
//     the EP-0017 behaviour.)
//   - The pair-mcp unit tests (`dispatch_test.cljs`) pin the `cofx`
//     parsing / threading under `:rf.cofx`, but that is the server-side
//     unit layer, NOT the SDK/client conformance boundary.
//
// EP-0017 makes reproducible dispatch (and Tool-Pair §Replay's strict
// re-dispatch with recorded `:rf.cofx`) a load-bearing agent affordance:
// an agent scripts `:rf/time-ms` and asserts the SAME app-db results. A
// regression in the descriptor/wire path for that affordance could ship
// GREEN through every existing conformance gate while breaking the
// agent-facing replay surface. This gate is the live SDK-boundary net.
//
// ## What this test drives (across the MCP boundary, via the SDK Client)
//
//   1. POSITIVE — supplied `:rf/time-ms` reaches the resulting state.
//      `tools/call dispatch` the fixture's `[:counter/stamp]` event (a
//      `reg-event` declaring `:rf.cofx/requires [:rf/time-ms]` that folds
//      the recorded wall-clock fact, read FLAT as `time-ms`, into app-db
//      under `:stamped-at`) WITH a scripted `cofx "{:rf/time-ms <N>}"`.
//      Then read `[:stamped-at]` back via `eval-cljs` and assert it equals
//      the SCRIPTED `<N>` — proving the supplied coeffect threaded through
//      `:rf.cofx` and the router preserved it verbatim (not a freshly
//      stamped Date.now()). Dispatch TWICE with two different scripted
//      times and assert each lands — so a server that ignored `cofx` and
//      stamped a live clock fails (a live clock cannot equal two distinct
//      pinned past timestamps).
//   2. NEGATIVE — a malformed `cofx` returns the documented structured
//      error BEFORE dispatching (the live tool body reaches the
//      shape-check the degraded handler hides): non-map ⇒ `:invalid-cofx`,
//      unreadable EDN ⇒ `:invalid-cofx`, non-integer `:rf/time-ms` ⇒
//      `:invalid-cofx-time-ms`. Each MUST isError and MUST NOT mutate the
//      stamped state (asserted: `:stamped-at` still reads the last GOOD
//      scripted value after the malformed calls).
//   3. TOOLING VISIBILITY — `list-handlers {kind "cofx"}` discovers the
//      framework's one provided recordable coeffect `:rf/time-ms`, and
//      `handler-meta {kind "cofx" id ":rf/time-ms"}` surfaces its
//      registration metadata (`:ok? true`, `:kind :cofx`, `:id
//      :rf/time-ms`, `:recordable? true`, `:provided? true` — the EP-0017
//      grade). And an EVENT fixture's `handler-meta {kind "event" id
//      ":counter/stamp"}` exposes the AUTHORED `:rf.cofx/requires
//      [:rf/time-ms]` declaration (EP-0017 §4 / Spec 001 — the metadata
//      key `register-event!` retains verbatim so tooling surfaces it).
//
// ## Catches
//
//   - the router dropping / overwriting a supplied `:rf/time-ms` (the
//     headline replay-determinism regression) — step 1 goes RED.
//   - the `cofx` arg silently ignored at the descriptor/wire/parse path —
//     step 1 (state unchanged from a default live clock) goes RED.
//   - the malformed-cofx shape-check renamed / dropped / reordered after
//     the dispatch — step 2 goes RED (a malformed cofx would either
//     dispatch anyway or return a different reason).
//   - `:rf/time-ms` dropped from the `cofx` registrar, or its recordable
//     grade metadata regressed — step 3 goes RED.
//   - the authored `:rf.cofx/requires` no longer surviving onto event
//     metadata (an EP-0017/EP-0018 inspectability regression) — step 3
//     goes RED.
//
// ## Gating
//
// **Skipped unless `$SHADOW_CLJS_NREPL_PORT` is set.** Same posture as the
// sibling live-* variants: without a live nREPL the server runs degraded
// and `dispatch` / `handler-meta` / `list-handlers` return the
// `:nrepl-port-not-found` envelope, so the EP-0017 surface is unreachable.
// The hermetic orchestrator
// (`scripts/run-re-frame2-pair-live-hermetic-suite.cjs`) boots
// shadow-cljs + Chromium against `skills/re-frame2-pair/tests/fixture/`
// and wires the env so this gate fires on CI.

const path = require('node:path');
const os = require('node:os');
const { runWithWatchdog, responseText } = require('./_runner.cjs');

const SERVER = path.resolve(__dirname, '..', '..', 're-frame2-pair-mcp', 'out', 'server.js');

// Two distinct, pinned-PAST wall-clock epoch-ms values. Distinct so a
// server that ignored `cofx` and stamped a live `Date.now()` cannot match
// BOTH (a live clock can coincidentally near one fixed value but never
// equal two different pinned pasts on two dispatches). Pinned in the past
// (2026-06-09 / 2025-01-01) so they are visibly NOT a live clock.
const SCRIPTED_TIME_A = 1781078400123;
const SCRIPTED_TIME_B = 1735689600000;

// Read the fixture's `:stamped-at` app-db slot back through the runtime
// snapshot — the slot `:counter/stamp` folds the recorded `:rf/time-ms`
// into. Uses `eval-cljs` (the same internal-read seam the redaction gate
// uses) so we observe the COMMITTED state, not the dispatch echo. Returns
// the parsed integer (or NaN if absent / unreadable).
async function readStampedAt(client) {
  const resp = await client.callTool({
    name: 'eval-cljs',
    arguments: {
      form:
        '(get (re-frame2-pair.runtime/snapshot ' +
        '(re-frame2-pair.runtime/current-frame)) :stamped-at)',
    },
  });
  if (resp.isError) {
    throw new Error(
      'eval-cljs read of :stamped-at returned isError — cannot verify the ' +
        'cofx reached state. Got: ' + responseText(resp).slice(0, 300),
    );
  }
  const text = responseText(resp);
  // The eval result rides in the structured `:value` slot (an integer
  // literal). Pull the first long integer out of the text.
  const m = text.match(/-?\d{10,}/);
  return m ? Number(m[0]) : NaN;
}

// Dispatch `[:counter/stamp]` with a scripted `cofx`, queued so the
// fixture's depth-0 epoch-recording posture does not surface a
// `:no-epoch-recorded` isError (same rationale as the subscribe gate's
// `:queued`). The db commit is synchronous regardless of epoch recording,
// so the subsequent `:stamped-at` read sees the folded value.
async function dispatchStamp(client, timeMs) {
  const resp = await client.callTool({
    name: 'dispatch',
    arguments: {
      event: '[:counter/stamp]',
      cofx: '{:rf/time-ms ' + timeMs + '}',
      queued: true,
    },
  });
  if (resp.isError) {
    throw new Error(
      'dispatch [:counter/stamp] (:queued) with scripted cofx {:rf/time-ms ' +
        timeMs + '} returned isError — the reproducible dispatch did not ' +
        'land. Got: ' + responseText(resp).slice(0, 300),
    );
  }
  return resp;
}

// Pre-flight SKIP — same posture as the sibling live-* variants.
if (!process.env.SHADOW_CLJS_NREPL_PORT) {
  runWithWatchdog.skip(
    'live-re-frame2-pair-cofx: $SHADOW_CLJS_NREPL_PORT not set.\n' +
      '      This variant requires a live shadow-cljs nREPL + browser\n' +
      '      runtime — without one dispatch / handler-meta / list-handlers\n' +
      '      return the degraded :nrepl-port-not-found envelope, so the\n' +
      '      EP-0017 cofx surface (reproducible dispatch + cofx tooling\n' +
      '      visibility) is unreachable. The hermetic orchestrator boots\n' +
      '      the fixture runtime and wires the env so this gate fires on CI.',
  );
}

runWithWatchdog(
  {
    watchdogMs: 30000,
    clientName: 'mcp-conformance-re-frame2-pair-live-cofx',
    transportSpec: {
      command: process.execPath,
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

    // ---- Step 1: POSITIVE — supplied :rf/time-ms reaches the state ------
    // Dispatch [:counter/stamp] with scripted time A, read :stamped-at,
    // assert it equals A — then again with a DIFFERENT scripted time B, so
    // a server that ignores `cofx` and stamps a live clock fails (a live
    // clock cannot equal two distinct pinned pasts).
    await dispatchStamp(client, SCRIPTED_TIME_A);
    const afterA = await readStampedAt(client);
    if (afterA !== SCRIPTED_TIME_A) {
      throw new Error(
        ':counter/stamp dispatched with scripted cofx {:rf/time-ms ' +
          SCRIPTED_TIME_A + '} but :stamped-at read back ' + afterA + '. The ' +
          'supplied recordable coeffect did NOT reach the resulting state — ' +
          'the router dropped/overwrote :rf/time-ms (EP-0017 reproducible ' +
          'dispatch regression, rf2-jyxmtq).',
      );
    }
    console.log(
      'OK   dispatch [:counter/stamp] cofx {:rf/time-ms ' + SCRIPTED_TIME_A +
        '} -> :stamped-at == scripted time (cofx reached state)',
    );

    await dispatchStamp(client, SCRIPTED_TIME_B);
    const afterB = await readStampedAt(client);
    if (afterB !== SCRIPTED_TIME_B) {
      throw new Error(
        'second :counter/stamp dispatch with scripted cofx {:rf/time-ms ' +
          SCRIPTED_TIME_B + '} read back :stamped-at ' + afterB + '. A second ' +
          'distinct scripted time MUST also land verbatim — a server stamping ' +
          'a live clock could never match two distinct pinned pasts. ' +
          '(rf2-jyxmtq replay-determinism.)',
      );
    }
    console.log(
      'OK   dispatch [:counter/stamp] cofx {:rf/time-ms ' + SCRIPTED_TIME_B +
        '} -> :stamped-at == scripted time (two distinct times each land — ' +
        'not a live clock)',
    );

    // ---- Step 2: NEGATIVE — malformed cofx refused BEFORE dispatch ------
    // The live tool body reaches the cofx shape-check the degraded handler
    // hides. Each malformed value MUST isError + carry the documented
    // structured reason, and MUST NOT mutate state (asserted by re-reading
    // :stamped-at — it must still read the last GOOD scripted value B).
    const MALFORMED = [
      { cofx: '[:not :a :map]',    reason: ':invalid-cofx',         why: 'a non-map cofx' },
      { cofx: '{:rf/time-ms 1',    reason: ':invalid-cofx',         why: 'unreadable cofx EDN' },
      { cofx: '{:rf/time-ms "now"}', reason: ':invalid-cofx-time-ms', why: 'a non-integer :rf/time-ms' },
    ];
    for (const m of MALFORMED) {
      const resp = await client.callTool({
        name: 'dispatch',
        arguments: { event: '[:counter/stamp]', cofx: m.cofx, queued: true },
      });
      if (!resp.isError) {
        throw new Error(
          'dispatch [:counter/stamp] with ' + m.why + ' MUST isError ' +
            '(EP-0017 cofx shape-check, rf2-q6s1nb); got: ' +
            responseText(resp).slice(0, 300),
        );
      }
      const text = responseText(resp);
      if (!text.includes(m.reason)) {
        throw new Error(
          'dispatch [:counter/stamp] with ' + m.why + ' MUST carry the ' +
            'documented structured error ' + m.reason + ' (EP-0017 cofx ' +
            'contract); got: ' + text.slice(0, 300),
        );
      }
      // The malformed-cofx refusal is reachable here ONLY because we have a
      // live runtime; it must be the SHAPE-CHECK refusal, not the degraded
      // :nrepl-port-not-found (which would mean the runtime detached).
      if (text.includes('nrepl-port-not-found')) {
        throw new Error(
          'dispatch malformed-cofx returned :nrepl-port-not-found — the ' +
            'runtime is not attached, so this gate is testing degraded mode, ' +
            'not the live EP-0017 cofx shape-check. (rf2-jyxmtq.)',
        );
      }
      console.log(
        'OK   dispatch [:counter/stamp] (' + m.why + ') -> isError + ' +
          m.reason + ' (live cofx shape-check)',
      );
    }
    // A malformed cofx must NOT have dispatched — :stamped-at still reads
    // the last GOOD scripted value (B). A refusal that dispatched anyway
    // (or stamped a live clock) would move it.
    const afterMalformed = await readStampedAt(client);
    if (afterMalformed !== SCRIPTED_TIME_B) {
      throw new Error(
        'after three malformed-cofx refusals :stamped-at read back ' +
          afterMalformed + ' but MUST still be the last GOOD scripted value ' +
          SCRIPTED_TIME_B + ' — a malformed cofx MUST short-circuit WITHOUT ' +
          'dispatching (rf2-jyxmtq / rf2-q6s1nb).',
      );
    }
    console.log(
      'OK   malformed cofx did NOT dispatch — :stamped-at unchanged ' +
        '(short-circuit before dispatch verified)',
    );

    // ---- Step 3: TOOLING VISIBILITY — cofx kind + authored requires -----
    // 3a. list-handlers {kind "cofx"} discovers :rf/time-ms (the
    // framework's one provided recordable coeffect, cofx.cljc).
    const listed = await client.callTool({
      name: 'list-handlers',
      arguments: { kind: 'cofx' },
    });
    if (listed.isError) {
      throw new Error(
        'list-handlers {kind "cofx"} returned isError — the cofx registrar ' +
          'is not enumerable over the wire. Got: ' +
          responseText(listed).slice(0, 300),
      );
    }
    const listedText = responseText(listed);
    if (!/:kind\s+:cofx\b/.test(listedText)) {
      throw new Error(
        'list-handlers {kind "cofx"} response MUST report :kind :cofx; got: ' +
          listedText.slice(0, 300),
      );
    }
    if (!listedText.includes(':rf/time-ms')) {
      throw new Error(
        'list-handlers {kind "cofx"} MUST discover the framework recordable ' +
          'coeffect :rf/time-ms (EP-0017); got: ' + listedText.slice(0, 300),
      );
    }
    console.log(
      'OK   list-handlers {kind "cofx"} -> :rf/time-ms discoverable',
    );

    // 3b. handler-meta {kind "cofx" id ":rf/time-ms"} surfaces the
    // framework registration metadata: :ok? true, :kind :cofx, :id
    // :rf/time-ms, and the EP-0017 recordable grade
    // (:recordable? true :provided? true — cofx.cljc:1032).
    const cofxMeta = await client.callTool({
      name: 'handler-meta',
      arguments: { kind: 'cofx', id: ':rf/time-ms' },
    });
    if (cofxMeta.isError) {
      throw new Error(
        'handler-meta {kind "cofx" id ":rf/time-ms"} returned isError — the ' +
          'framework recordable-coeffect registration is not inspectable. ' +
          'Got: ' + responseText(cofxMeta).slice(0, 300),
      );
    }
    const cofxMetaText = responseText(cofxMeta);
    for (const [needle, desc] of [
      [/:ok\?\s+true\b/, ':ok? true'],
      [/:kind\s+:cofx\b/, ':kind :cofx'],
      [/:id\s+:rf\/time-ms\b/, ':id :rf/time-ms'],
      [/:recordable\?\s+true\b/, ':recordable? true (EP-0017 grade)'],
      [/:provided\?\s+true\b/, ':provided? true (EP-0017 grade)'],
    ]) {
      if (!needle.test(cofxMetaText)) {
        throw new Error(
          'handler-meta {kind "cofx" id ":rf/time-ms"} MUST surface ' + desc +
            '; got: ' + cofxMetaText.slice(0, 400),
        );
      }
    }
    console.log(
      'OK   handler-meta {kind "cofx" id ":rf/time-ms"} -> :ok? true + ' +
        ':kind :cofx + :id :rf/time-ms + recordable/provided grade',
    );

    // 3c. An EVENT fixture's handler-meta exposes the AUTHORED
    // :rf.cofx/requires declaration. `:counter/stamp` declares
    // `:rf.cofx/requires [:rf/time-ms]`; `register-event!` retains the
    // authored key verbatim (events.cljc — EP-0017 §4 / Spec 001) so the
    // tool surfaces it. This is the EP-0017 declaration-inspectability
    // promise on the wire.
    const evMeta = await client.callTool({
      name: 'handler-meta',
      arguments: { kind: 'event', id: ':counter/stamp' },
    });
    if (evMeta.isError) {
      throw new Error(
        'handler-meta {kind "event" id ":counter/stamp"} returned isError — ' +
          'the fixture event is not inspectable. Got: ' +
          responseText(evMeta).slice(0, 300),
      );
    }
    const evMetaText = responseText(evMeta);
    if (!/:ok\?\s+true\b/.test(evMetaText) || !/:kind\s+:event\b/.test(evMetaText)) {
      throw new Error(
        'handler-meta {kind "event" id ":counter/stamp"} MUST be :ok? true ' +
          'with :kind :event; got: ' + evMetaText.slice(0, 400),
      );
    }
    if (!evMetaText.includes(':rf.cofx/requires')) {
      throw new Error(
        'handler-meta {kind "event" id ":counter/stamp"} MUST surface the ' +
          'AUTHORED :rf.cofx/requires declaration (EP-0017 §4 declaration ' +
          'inspectability — register-event! retains it verbatim); got: ' +
          evMetaText.slice(0, 400),
      );
    }
    // The declared requirement names :rf/time-ms — pin the value, not just
    // the key, so a requires that survives empty/wrong is caught.
    if (!/:rf\/time-ms\b/.test(evMetaText)) {
      throw new Error(
        ':counter/stamp handler-meta carries :rf.cofx/requires but does NOT ' +
          'name :rf/time-ms in it — the authored declaration was dropped or ' +
          'mangled. Got: ' + evMetaText.slice(0, 400),
      );
    }
    console.log(
      'OK   handler-meta {kind "event" id ":counter/stamp"} -> authored ' +
        ':rf.cofx/requires [:rf/time-ms] surfaced',
    );

    console.log('\nRE-FRAME2-PAIR-MCP LIVE COFX CONFORMANCE GREEN');
  },
);
