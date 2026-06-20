// Live-re-frame2-pair MCP-client conformance variant pinning the
// universal `:ok? false ⇒ isError:true` contract on the read-family
// GENUINE error envelopes (read-dom / read-ui bad-selector).
//
// ## What this test guards
//
// pair-mcp's `probe/map-result-or-blank` (the shared read-dom/read-ui
// `on-value` projection) branches its map arm on `:ok?`: a GENUINE
// runtime failure envelope — `{:ok? false :reason
// :rf.error/read-dom-bad-selector}` (a malformed CSS selector makes
// `querySelectorAll` throw), the `:rf.error/ui-read-bad-selector`
// equivalent, `:no-document`, `:no-element` — ships with
// `isError:true`. This honours spec/003-Tool-Catalogue.md §381 ("every
// `:ok? false` response is `isError:true`") AND keeps the failure
// CACHE-INELIGIBLE (cache eligibility bypasses isError), so a transient
// failure cannot be cached and mask a later success.
//
// This is the live counterpart to the degraded-mode coverage: read-dom /
// read-ui in DEGRADED mode return the `:nrepl-port-not-found` envelope
// (which routes through `wire/err-text` directly and is isError by
// construction). This test drives the GENUINE live error path — a
// `querySelectorAll` throw inside the runtime fn — through the SDK
// boundary, so a server returning `:ok? false` WITHOUT `isError:true` on
// the read-family error path is caught.
//
// With a real nREPL + browser runtime connected (the hermetic
// orchestrator boots both), it:
//
//   1. `tools/call read-dom` with a malformed CSS selector (`>>>`) so
//      the runtime's `querySelectorAll` throws and the runtime fn
//      returns `{:ok? false :reason :rf.error/read-dom-bad-selector}`.
//   2. `tools/call read-ui` with the same malformed selector ⇒
//      `{:ok? false :reason :rf.error/ui-read-bad-selector}`.
//   3. ASSERTS each response carries BOTH the structured failure reason
//      (proving we reached the GENUINE runtime error path, not the
//      degraded `:nrepl-port-not-found` envelope) AND `isError === true`.
//      Revert the `map-result-or-blank` map arm back to a bare
//      `wire/ok-text` and both arms go RED — the reason still rides, but
//      `isError` is false.
//
// This is the SDK-boundary counterpart to the unit
// `bad-selector-error-forwarded` tests in
// `tools/re-frame2-pair-mcp/test/read_{dom,ui}_test.cljs`: those pin the
// projection in isolation, this pins it on the REAL wire against the REAL
// runtime fn that produces the failure.
//
// ## Gating
//
// **Skipped unless `$SHADOW_CLJS_NREPL_PORT` is set.** Same posture as
// the sibling live-* variants: without a live nREPL the server runs
// degraded and read-dom/read-ui return the `:nrepl-port-not-found`
// envelope (which IS isError, but is NOT the genuine runtime error path
// this gate must exercise). The hermetic orchestrator
// (`scripts/run-re-frame2-pair-live-hermetic-suite.cjs`) boots
// shadow-cljs + Chromium against `skills/re-frame2-pair/tests/fixture/`
// and wires the env so this gate fires on CI.

const path = require('node:path');
const os = require('node:os');
const { runWithWatchdog, responseText } = require('./_runner.cjs');

const SERVER = path.resolve(__dirname, '..', '..', 're-frame2-pair-mcp', 'out', 'server.js');

// A genuinely malformed CSS selector. `querySelectorAll('>>>')` throws a
// DOMException SyntaxError in every browser — the runtime fn catches it
// and returns the structured bad-selector failure envelope. (A `body`
// selector that simply matches nothing returns `:ok? true :count 0`, so
// we must use a selector the parser REJECTS, not one that merely misses.)
const BAD_SELECTOR = '>>>';

// The load-bearing assertion: a GENUINE `:ok? false` runtime failure
// MUST carry `isError === true`. Three conditions, each load-bearing:
//
//   - the structured `reason` is the expected bad-selector reason: proves
//     we drove the GENUINE runtime error path (a `querySelectorAll`
//     throw), NOT the degraded `:nrepl-port-not-found` envelope (which
//     would false-pass a bare isError check, since it is isError too).
//   - `:ok? false`: the envelope is a fault, not a legitimate empty answer.
//   - `isError === true`: the universal contract. This is the assertion
//     that goes RED if the shared projection routes every map through
//     `ok-text`.
function assertReadFailureIsError(resp, name, expectedReason) {
  const text = responseText(resp);
  if (!text.includes(expectedReason)) {
    throw new Error(
      name + ' did not reach the GENUINE runtime error path: expected the ' +
        'structured reason `' + expectedReason + '` (a `querySelectorAll` ' +
        'throw on the malformed selector), but the envelope was: ' +
        text.slice(0, 400) + '. If this is `:nrepl-port-not-found`, the ' +
        'runtime is not attached and the gate is testing degraded mode, ' +
        'not the read-family error path (rf2-87h71e).',
    );
  }
  if (!/:ok\?\s+false\b/.test(text)) {
    throw new Error(
      name + ' envelope is not `:ok? false` — expected a structured failure. ' +
        'Got: ' + text.slice(0, 400),
    );
  }
  if (resp.isError !== true) {
    throw new Error(
      name + ' returned a `:ok? false` failure WITHOUT `isError:true` ' +
        '(isError = ' + JSON.stringify(resp.isError) + '). This violates the ' +
        'universal spec/003-Tool-Catalogue.md §381 contract ("every `:ok? ' +
        'false` is `isError:true`") and makes the transient failure ' +
        'cache-eligible (cache eligibility bypasses isError), masking a ' +
        'later success. This is exactly the rf2-q7cavs gap — `map-result-' +
        'or-blank` must branch its map arm on `:ok?`. Envelope: ' +
        text.slice(0, 400),
    );
  }
}

// Pre-flight SKIP — same posture as the sibling live-* variants.
if (!process.env.SHADOW_CLJS_NREPL_PORT) {
  runWithWatchdog.skip(
    'live-re-frame2-pair-iserror: $SHADOW_CLJS_NREPL_PORT not set.\n' +
      '      This variant requires a live shadow-cljs nREPL + browser\n' +
      '      runtime — without one read-dom/read-ui return the degraded\n' +
      '      :nrepl-port-not-found envelope (isError, but NOT the genuine\n' +
      '      runtime error path this gate must exercise). The hermetic\n' +
      '      orchestrator boots the fixture runtime and wires the env so\n' +
      '      this gate fires on CI.',
  );
}

runWithWatchdog(
  {
    watchdogMs: 60000,
    clientName: 'mcp-conformance-re-frame2-pair-iserror',
    transportSpec: {
      command: process.execPath,
      args: [SERVER],
      cwd: os.tmpdir(),
      env: { ...process.env },
    },
  },
  async (client) => {
    console.log('OK   connect -> server attached on nREPL', process.env.SHADOW_CLJS_NREPL_PORT);

    // 1. read-dom with a malformed selector ⇒ the runtime's
    // querySelectorAll throws ⇒ {:ok? false :reason
    // :rf.error/read-dom-bad-selector}. The SDK response MUST be isError.
    const dom = await client.callTool({
      name: 'read-dom',
      arguments: { selector: BAD_SELECTOR },
    });
    assertReadFailureIsError(
      dom,
      'read-dom (malformed selector)',
      ':rf.error/read-dom-bad-selector',
    );
    console.log(
      'OK   read-dom (bad selector) -> :ok? false + isError:true (rf2-q7cavs)',
    );

    // 2. read-ui with the same malformed selector ⇒ {:ok? false :reason
    // :rf.error/ui-read-bad-selector}. The sibling read-family error path
    // through the SAME shared `map-result-or-blank` projection — pinned at
    // its INDEPENDENT call site so a regression on either tool is caught.
    const ui = await client.callTool({
      name: 'read-ui',
      arguments: { selector: BAD_SELECTOR },
    });
    assertReadFailureIsError(
      ui,
      'read-ui (malformed selector)',
      ':rf.error/ui-read-bad-selector',
    );
    console.log(
      'OK   read-ui (bad selector) -> :ok? false + isError:true (rf2-q7cavs)',
    );

    console.log('\nRE-FRAME2-PAIR-MCP LIVE ISERROR-ON-OK-FALSE CONFORMANCE GREEN');
  },
);
