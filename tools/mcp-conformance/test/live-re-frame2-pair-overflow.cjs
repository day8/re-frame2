// Live-re-frame2-pair MCP-client conformance variant exercising the wire-cap
// overflow marker (:rf.mcp/overflow) under real over-budget conditions.
//
// ## What this test guards
//
// The sibling `end-to-end-re-frame2-pair.cjs` runs re-frame2-pair-mcp in *degraded* mode
// (no nREPL on $SHADOW_CLJS_NREPL_PORT) and validates the protocol
// shape against the SDK's strict CallToolResultSchema. That harness
// never actually trips the wire-cap because every degraded response is
// a sub-100-byte `:nrepl-port-not-found` error.
//
// This variant fills that gap. With a real nREPL connected:
//
//   1. The cap-trigger code path (`apply-cap` in
//      `tools/re-frame2-pair-mcp/src/.../tools.cljs`) runs against a *real* live
//      tool response — not a synthetic fixture.
//
//   2. The emitted `{:rf.mcp/overflow ...}` envelope passes through
//      the official `@modelcontextprotocol/sdk` `Client`'s
//      `CallToolResultSchema` validation. A schema-rejection on
//      cap-marker payloads would otherwise only surface when a real
//      consumer (Claude Code, Continue, …) attaches to the server.
//
//   3. The marker body matches the canonical cross-MCP wire vocabulary
//      pinned by `tools/mcp-conformance/wire-vocab/` (Malli schema for
//      `:rf.mcp/overflow`). A rename / shape drift at the live
//      emission site surfaces here.
//
// ## Catches
//
//   - cap-trigger threshold drift (e.g. someone bumps the default to
//     50K tokens and forgets to update spec/code-paths in lockstep)
//   - marker shape regressions that only fire on real payloads
//   - client-side parse failures on cap-marker shapes the SDK's strict
//     CallToolResultSchema doesn't yet recognise
//   - keyword renames (`:cap-tokens` → `:cap_tokens`,
//     `:rf.mcp/overflow` → `:rf.mcp/overflows`) at the live emission
//     site (the wire-vocab unit test catches them in source text; this
//     catches them on the wire)
//
// ## Gating
//
// **Skipped unless `$SHADOW_CLJS_NREPL_PORT` is set.** Without a real
// nREPL the server runs in degraded mode and the cap can't be tripped
// naturally (every response is the same tiny error envelope). On CI
// the gate is unset by default → the script exits 0 with a SKIP
// marker. Locally, Mike's running shadow-cljs sets the env, the
// re-frame2-pair-mcp server attaches, and this test runs the real-overflow
// path.
//
// A follow-on bead tracks the "live overflow via a worked example +
// auto-spawned shadow-cljs" variant. That gives fully-hermetic CI
// coverage; this script gives Mike a one-command local-runtime guard
// today.
//
// ## How the cap is naturally tripped
//
// The test calls `eval-cljs` with the form
//
//     (apply str (repeat 25000 "x"))
//
// which evaluates to a 25,000-char string. The re-frame2-pair-mcp wire-cap
// uses `token-estimate = (quot chars 4)`, so the serialised response
// estimate is ~6,250 tokens — comfortably over the 5,000-token
// default cap. The server's `apply-cap` at the egress boundary
// replaces the payload with the canonical `:rf.mcp/overflow` marker
// before it crosses the wire. No fixture; no synthetic cap.
//
// Run with: `node test/live-re-frame2-pair-overflow.cjs` from this directory.
// Requires `cd ../re-frame2-pair-mcp && shadow-cljs compile server` first
// (same as the sibling harness). Exits 0 on success or SKIP. Exits 1
// on any conformance violation.

// ## DRY-on-3 resolution
//
// This file and its `live-re-frame2-pair-*.cjs` siblings (subscribe,
// redaction) share the nREPL SKIP-gate (via $SHADOW_CLJS_NREPL_PORT) and
// the SDK-spawn ceremony. Both are now factored: the SKIP gate routes
// through `runWithWatchdog.skip`, and the spawn+connect+teardown ceremony
// is `_runner.cjs`'s `connectServer` / `closeQuietly` (the redaction arm's
// second-server boot uses it directly). What is NOT shared — by design —
// is each variant's data-driven field validator: this variant's overflow
// parsing/validation lives in the overflow-SPECIFIC `lib/overflow-marker.cjs`
// (`REQUIRED_FIELDS` + `assertOverflowBody` + the closed-wrapper guard),
// while subscribe keeps `REQUIRED_PARAMS` + `assertProgressParams` inline.
// Each pins a DISTINCT Malli schema (`ReFrame2PairOverflowBody` vs
// `ReFrame2PairProgressNotificationParams`) and the JVM cross-encoding gate
// in `wire_vocab_test.clj` greps each validator's literal source rows by
// name — collapsing them into one GENERIC helper would dissolve that
// per-schema attribution and weaken the conformance contract. (The overflow
// helper was extracted so its pure logic is unit-testable off the live
// path; it is not shared with subscribe.)

const path = require('node:path');
const os = require('node:os');
const { runWithWatchdog } = require('./_runner.cjs');
// Pure overflow parsing/validation lives in `lib/overflow-marker.cjs` so
// it is unit-testable without booting a server (this file's top-level
// SKIP path exits before any function is reachable). It owns the
// CLOSED single-key wrapper enforcement, the `REQUIRED_FIELDS` body table,
// the token-count invariant, and the dual-slot agreement check. The JVM
// cross-encoding grep gate (`js-assertOverflowBody-pins-every-re-frame2-pair-
// overflow-required-field`) slurps that helper's `REQUIRED_FIELDS` rows.
const {
  validateOverflowText,
  validateOverflowWrapper,
  assertBodiesAgree,
} = require('../lib/overflow-marker.cjs');

const SERVER = path.resolve(__dirname, '..', '..', 're-frame2-pair-mcp', 'out', 'server.js');

// Default cap pinned by tools/re-frame2-pair-mcp/src/.../tools.cljs
// `default-max-tokens`. Sourced here as a compile-time constant so a
// future bump to the default surfaces as a test-failure forcing the
// reviewer to update both pins in lockstep (re. the bead description's
// "cap-trigger threshold drift" risk).
const DEFAULT_MAX_TOKENS = 5000;

// Payload generator: a CLJS form that evaluates to a string large
// enough to push the response over the default cap.
//
// Sizing rationale: re-frame2-pair-mcp's token-estimate is `(quot chars 4)`,
// so an N-char string is ~N/4 tokens. We want >5000 tokens with
// margin against the EDN-quoting overhead of pr-str. 25,000 chars ⇒
// 6,250 token-estimate ⇒ 1.25× over cap. Safe for both directions
// (large enough to trip, not so large that an unrelated overflow
// path kicks in first).
const FORM_OVER_BUDGET = '(apply str (repeat 25000 "x"))';

// Pre-flight SKIP: route through the runner's shared skip helper so we
// don't spawn a child or install a watchdog. Same posture as the sibling
// live-re-frame2-pair-subscribe.cjs. The skip helper prints the canonical
// `SKIP <reason>` banner and exits 0.
if (!process.env.SHADOW_CLJS_NREPL_PORT) {
  runWithWatchdog.skip(
    'live-re-frame2-pair-overflow: $SHADOW_CLJS_NREPL_PORT not set.\n' +
      '      This variant requires a live shadow-cljs nREPL — without\n' +
      '      one the server runs degraded and the wire-cap cannot be\n' +
      '      tripped naturally. The sibling end-to-end-re-frame2-pair.cjs covers\n' +
      '      degraded-mode protocol conformance; this variant adds\n' +
      '      cap-marker conformance under real over-budget conditions.',
  );
}

// Hard cap so a hung server doesn't wedge CI. nREPL connect + one
// eval round-trip + tear-down should comfortably fit in 60s.
runWithWatchdog(
  {
    watchdogMs: 60000,
    clientName: 'mcp-conformance-re-frame2-pair-live-overflow',
    transportSpec: {
      command: process.execPath,
      // eval-cljs is ON by default. This live-overflow harness's whole
      // purpose is to trip the wire cap on a real eval response, so we
      // boot without `--no-eval` — the default-ON state is exactly
      // what we want. The disabled-envelope contract is pinned by the
      // unit fixture `:eval-cljs/disabled-via-no-eval` in
      // tools/re-frame2-pair-mcp's conformance corpus and by the live
      // probe in live-re-frame2-pair-subscribe.cjs (which boots WITH
      // --no-eval).
      args: [SERVER],
      cwd: os.tmpdir(),
      env: { ...process.env },
    },
  },
  async (client) => {
    // 1. The runner already ran the SDK initialize handshake. If that
    // had thrown it means the server's initialize envelope itself
    // drifted; not the bug this test is hunting, but a load-bearing
    // pre-condition.
    console.log('OK   connect -> server attached on nREPL', process.env.SHADOW_CLJS_NREPL_PORT);

    // 2. Fire the over-budget eval. The form returns a 25,000-char
    // string (~6,250 token-estimate) which exceeds the 5,000-token
    // default cap. The server's wire-boundary `apply-cap` MUST
    // replace the payload with `:rf.mcp/overflow`.
    //
    // We do NOT pass `max-tokens`: the test deliberately exercises
    // the *default* cap to catch threshold drift. If a future change
    // bumps the default above 6,250 the test fails loud and forces
    // the reviewer to retune `FORM_OVER_BUDGET` (and document why
    // the default moved).
    const callResp = await client.callTool({
      name: 'eval-cljs',
      arguments: { form: FORM_OVER_BUDGET },
    });

    // 3. The SDK's CallToolResultSchema already accepted the
    // envelope. If it hadn't, `callTool` would have thrown above.
    // Confirm the response is a non-error MCP result — the overflow
    // marker is a *structured signal*, not an error condition.
    if (callResp.isError) {
      throw new Error(
        'eval-cljs returned isError; the overflow marker must NOT set isError.\n' +
          'Got: ' +
          JSON.stringify(callResp),
      );
    }
    console.log('OK   tools/call eval-cljs -> SDK accepts envelope, isError=false');

    // 4. The `content[0].text` slot must hold the canonical
    // `:rf.mcp/overflow` marker. We read the EDN text and validate it as
    // the CLOSED single-key wrapper — a surrounding `{:ok? true :value
    // "xxxxx..."}` (apply-cap missed) OR a mixed envelope smuggling a
    // sibling top-level key past the wrapper (the rf2-3fc89f.20 hole) is
    // rejected. The friendly presence check first distinguishes "cap
    // didn't trigger" from "cap triggered but shape wrong".
    const text = callResp.content?.[0]?.text;
    if (typeof text !== 'string') {
      throw new Error('content[0].text is not a string: ' + JSON.stringify(callResp));
    }
    if (!text.includes(':rf.mcp/overflow')) {
      throw new Error(
        'Response text MUST contain `:rf.mcp/overflow` marker.\n' +
          'Got (first 500 chars): ' +
          text.slice(0, 500),
      );
    }
    console.log('OK   response text carries :rf.mcp/overflow marker key');

    // 5. Validate the text-slot marker: closed single-key wrapper +
    // canonical `ReFrame2PairOverflowBody` body + the token-count >
    // cap-tokens invariant. This is the cross-server vocabulary
    // assertion: re-frame2-pair-mcp's wire emission MUST satisfy the same
    // shape as the wire-vocab fixture AND the JVM `Overflow` schema's
    // `{:closed true}` single-key posture.
    const textBody = validateOverflowText(text, 'eval-cljs live overflow (content[0].text)');
    console.log('OK   content[0].text validates as the CLOSED :rf.mcp/overflow wrapper + body');

    // 5b. Validate the SECOND result slot — `structuredContent`. Pair
    // MCP writes the SAME marker into BOTH `content[0].text` (pr-str EDN)
    // and `structuredContent` (the namespace-preserving `clj->js`
    // projection, see `tools/cap.cljs build-overflow-result` →
    // `wire/result`). Before rf2-3fc89f.20 the live gate never inspected
    // structuredContent, so it could lose the namespace, gain siblings,
    // or carry a different body while text stayed canonical and CI still
    // reported green. `structuredContent` arrives as an already-parsed JS
    // object whose top-level key is the bare string `"rf.mcp/overflow"`,
    // so the SAME closed-wrapper validator applies.
    const structured = callResp.structuredContent;
    const structuredBody = validateOverflowWrapper(
      structured,
      'eval-cljs live overflow (structuredContent)',
    );
    console.log('OK   structuredContent validates as the CLOSED :rf.mcp/overflow wrapper + body');

    // 5c. Prove the two slots agree. A drift (a field present in one but
    // not the other, or a differing value) means the dual representations
    // diverged — a client reading structuredContent would see a different
    // marker than one reading the text.
    assertBodiesAgree(
      textBody,
      structuredBody,
      'eval-cljs live overflow (dual-slot)',
    );
    console.log('OK   content[0].text and structuredContent marker bodies agree');

    // The semantic pins below read the text-slot body (byte-identical to
    // the structured body per 5c).
    const body = textBody;

    // 6. Pin the load-bearing semantic facts about the marker:
    //   - :cap-tokens MUST equal the documented default (5000).
    //     This catches the "default bumped silently" drift the bead
    //     description flagged.
    //   - :tool MUST equal "eval-cljs" (the offending tool name).
    //   - :hint MUST match the per-tool entry (the wire-cap test
    //     pins the fallback path; this pins the per-tool path).
    //
    // Field-access shape: `edn-data` with `keywordAs: 'string'` parses
    // EDN keywords as bare strings (no `:` prefix), so `body.foo` reads
    // directly. The Malli-side schema names the same fields as kebab-
    // case keywords; the cross-encoding gate in `wire_vocab_test.clj`
    // greps the `REQUIRED_FIELDS` rows in `lib/overflow-marker.cjs`.
    if (body['cap-tokens'] !== DEFAULT_MAX_TOKENS) {
      throw new Error(
        ':cap-tokens MUST equal default ' +
          DEFAULT_MAX_TOKENS +
          ' (no per-call override sent); got ' +
          body['cap-tokens'] +
          '. If the default has changed in re-frame2-pair-mcp `tools.cljs`, ' +
          'update DEFAULT_MAX_TOKENS in this file and refresh the spec ' +
          '§"Tight token budget per response" reference together.',
      );
    }
    if (body['tool'] !== 'eval-cljs') {
      throw new Error(
        ':tool MUST equal "eval-cljs"; got ' + JSON.stringify(body['tool']),
      );
    }
    if (!body['hint'] || !body['hint'].includes('Slice')) {
      // The re-frame2-pair-mcp `overflow-hints` table maps "eval-cljs" → "Slice
      // the value at the call-site (`get-in`, `take`, project to fewer
      // keys) before returning." A rename of the per-tool hint surfaces
      // here. We match on a stable substring rather than the whole
      // hint string so a copy-edit doesn't break the test.
      throw new Error(
        ':hint MUST contain "Slice" (per-tool re-frame2-pair-mcp hint for ' +
          'eval-cljs); got ' +
          JSON.stringify(body['hint']),
      );
    }
    console.log(
      'OK   marker body pins: :cap-tokens=' +
        body['cap-tokens'] +
        ', :tool=' +
        body['tool'] +
        ', :token-count=' +
        body['token-count'],
    );

    // 7. Belt-and-braces: the wire response itself must fit under
    // the cap. This is the recursion-safety property pinned by
    // `wire_cap_test.cljs/apply-cap-overflow-payload-is-itself-under-cap`.
    // The marker is small by construction (a hint string plus four
    // scalar keys) — < 1KB / ~250 tokens — but a future change to
    // the hint table or marker shape could blow that. Catch it here.
    const responseTokens = Math.floor(text.length / 4);
    if (responseTokens >= DEFAULT_MAX_TOKENS) {
      throw new Error(
        'Overflow marker itself is over budget: ~' +
          responseTokens +
          ' tokens >= cap ' +
          DEFAULT_MAX_TOKENS +
          '.\nThis is the recursion-safety property; the replacement marker ' +
          'MUST fit under the cap it replaced.',
      );
    }
    console.log(
      'OK   overflow marker fits under cap (~' +
        responseTokens +
        ' tokens < ' +
        DEFAULT_MAX_TOKENS +
        ')',
    );

    // 8. Clean disconnect — runner handles client.close() on success.
    console.log('\nRE-FRAME2-PAIR-MCP LIVE OVERFLOW CONFORMANCE GREEN');
  },
);
