// Live-pair2 MCP-client conformance variant exercising the wire-cap
// overflow marker (:rf.mcp/overflow) under real over-budget conditions.
// Source: rf2-ynaoc, follow-on from rf2-cum40 (PR #702).
//
// ## What this test guards
//
// The sibling `end-to-end-pair2.cjs` runs pair2-mcp in *degraded* mode
// (no nREPL on $SHADOW_CLJS_NREPL_PORT) and validates the protocol
// shape against the SDK's strict CallToolResultSchema. That harness
// never actually trips the wire-cap because every degraded response is
// a sub-100-byte `:nrepl-port-not-found` error.
//
// This variant fills that gap. With a real nREPL connected:
//
//   1. The cap-trigger code path (`apply-cap` in
//      `tools/pair2-mcp/src/.../tools.cljs`) runs against a *real* live
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
// pair2-mcp server attaches, and this test runs the real-overflow
// path.
//
// A follow-on bead (filed by rf2-ynaoc) tracks the "live overflow via
// a worked example + auto-spawned shadow-cljs" variant. That gives
// fully-hermetic CI coverage; this script gives Mike a one-command
// local-runtime guard today.
//
// ## How the cap is naturally tripped
//
// The test calls `eval-cljs` with the form
//
//     (apply str (repeat 25000 "x"))
//
// which evaluates to a 25,000-char string. The pair2-mcp wire-cap
// uses `token-estimate = (quot chars 4)`, so the serialised response
// estimate is ~6,250 tokens — comfortably over the 5,000-token
// default cap. The server's `apply-cap` at the egress boundary
// replaces the payload with the canonical `:rf.mcp/overflow` marker
// before it crosses the wire. No fixture; no synthetic cap.
//
// Run with: `node test/live-pair2-overflow.cjs` from this directory.
// Requires `cd ../pair2-mcp && shadow-cljs compile server` first
// (same as the sibling harness). Exits 0 on success or SKIP. Exits 1
// on any conformance violation.

const path = require('node:path');
const os = require('node:os');
const { runWithWatchdog } = require('./_runner.cjs');

const SERVER = path.resolve(__dirname, '..', '..', 'pair2-mcp', 'out', 'server.js');

// Default cap pinned by tools/pair2-mcp/src/.../tools.cljs
// `default-max-tokens`. Sourced here as a compile-time constant so a
// future bump to the default surfaces as a test-failure forcing the
// reviewer to update both pins in lockstep (re. the bead description's
// "cap-trigger threshold drift" risk).
const DEFAULT_MAX_TOKENS = 5000;

// Payload generator: a CLJS form that evaluates to a string large
// enough to push the response over the default cap.
//
// Sizing rationale: pair2-mcp's token-estimate is `(quot chars 4)`,
// so an N-char string is ~N/4 tokens. We want >5000 tokens with
// margin against the EDN-quoting overhead of pr-str. 25,000 chars ⇒
// 6,250 token-estimate ⇒ 1.25× over cap. Safe for both directions
// (large enough to trip, not so large that an unrelated overflow
// path kicks in first).
const FORM_OVER_BUDGET = '(apply str (repeat 25000 "x"))';

// Canonical schema for `:rf.mcp/overflow`. Mirrors the Malli
// `Pair2OverflowBody` schema pinned by `tools/mcp-conformance/wire-vocab/`
// (the JVM-side vocabulary conformance test) — we re-encode it here as
// plain JS assertions because this is a Node-side harness and the SDK
// doesn't link Malli. A drift between the two pins is a vocabulary bug:
// the marker MUST validate against both encodings identically.
//
// Cross-encoding sanity (rf2-0zqox): the JVM test
// `js-assertOverflowBody-pins-every-pair2-overflow-required-field`
// in `wire-vocab/test/.../wire_vocab_test.clj` grep-asserts every
// required field on `Pair2OverflowBody` against the substrings in this
// function. Adding / removing a check below MUST keep that gate happy;
// adding / removing a Malli field on the JVM side MUST update the
// `pair2-overflow-js-required-grep-markers` table there in lockstep.
function assertOverflowBody(body, ctx) {
  if (!body || typeof body !== 'object') {
    throw new Error(ctx + ': overflow body is not a map: ' + JSON.stringify(body));
  }
  // Required: :limit :reached
  if (body[':limit'] !== ':reached') {
    throw new Error(
      ctx +
        ': :limit MUST be :reached; got ' +
        JSON.stringify(body[':limit']) +
        '. (Schema: OverflowBody.limit ∈ {:reached})',
    );
  }
  // pair2-mcp form: requires :cap-tokens + :token-count + :tool + :hint
  if (typeof body[':cap-tokens'] !== 'number') {
    throw new Error(
      ctx +
        ': :cap-tokens MUST be int; got ' +
        JSON.stringify(body[':cap-tokens']) +
        '. (Schema: OverflowBody.cap-tokens : int)',
    );
  }
  if (typeof body[':token-count'] !== 'number') {
    throw new Error(
      ctx +
        ': :token-count MUST be int; got ' +
        JSON.stringify(body[':token-count']) +
        '. (Schema: OverflowBody.token-count : int)',
    );
  }
  if (typeof body[':tool'] !== 'string') {
    throw new Error(
      ctx +
        ': :tool MUST be string; got ' +
        JSON.stringify(body[':tool']) +
        '. (Schema: OverflowBody.tool : string|keyword)',
    );
  }
  if (typeof body[':hint'] !== 'string') {
    throw new Error(
      ctx +
        ': :hint MUST be string; got ' +
        JSON.stringify(body[':hint']) +
        '. (Schema: OverflowBody.hint : string|keyword)',
    );
  }
  if (body[':token-count'] <= body[':cap-tokens']) {
    throw new Error(
      ctx +
        ': :token-count MUST exceed :cap-tokens for a tripped cap; got ' +
        body[':token-count'] +
        ' ≤ ' +
        body[':cap-tokens'],
    );
  }
}

// Minimal EDN tokeniser for the canonical pair2-mcp marker shape. The
// server's `pr-str` emits the marker as:
//
//   {:rf.mcp/overflow {:limit :reached :token-count 6250
//                      :cap-tokens 5000 :tool "eval-cljs"
//                      :hint "..."}}
//
// We do not want to pull a full EDN reader into a Node-only harness;
// the parse here is keyed on the specific top-level shape of the
// overflow marker and is robust to interior key order changes. If the
// emitted shape grows new top-level keys this parser ignores them
// (the schema assertion above is what enforces the contract).
function parseOverflowMarker(text) {
  // Strip outer `{:rf.mcp/overflow ` ... ` }`.
  const TOP = ':rf.mcp/overflow';
  const topIdx = text.indexOf(TOP);
  if (topIdx < 0) return null;
  // Find the inner-map opening `{` after the top key.
  let i = topIdx + TOP.length;
  while (i < text.length && text[i] !== '{') i++;
  if (i >= text.length) return null;
  // Walk to the matching close-brace; respect quoted strings so we
  // don't trip on a `}` embedded in :hint.
  let depth = 0;
  let start = i;
  let end = -1;
  let inStr = false;
  for (let j = i; j < text.length; j++) {
    const c = text[j];
    if (inStr) {
      if (c === '\\') {
        j++; // skip escaped char
        continue;
      }
      if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') {
      inStr = true;
      continue;
    }
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) {
        end = j;
        break;
      }
    }
  }
  if (end < 0) return null;
  const inner = text.slice(start + 1, end);

  // Tokenise `:key value :key value ...` where value is one of:
  //   - :keyword
  //   - "string" (handle escapes)
  //   - integer
  const out = {};
  let p = 0;
  function skipWs() {
    while (p < inner.length && /\s|,/.test(inner[p])) p++;
  }
  function readKey() {
    skipWs();
    if (inner[p] !== ':') return null;
    let k = p;
    while (p < inner.length && !/\s|,/.test(inner[p])) p++;
    return inner.slice(k, p);
  }
  function readValue() {
    skipWs();
    if (p >= inner.length) return undefined;
    const c = inner[p];
    if (c === '"') {
      // string
      let s = '';
      p++; // past opening "
      while (p < inner.length) {
        const ch = inner[p++];
        if (ch === '\\') {
          const next = inner[p++];
          if (next === 'n') s += '\n';
          else if (next === 't') s += '\t';
          else if (next === 'r') s += '\r';
          else s += next;
          continue;
        }
        if (ch === '"') return s;
        s += ch;
      }
      return s;
    }
    if (c === ':') {
      // keyword
      let k = p;
      while (p < inner.length && !/\s|,/.test(inner[p])) p++;
      return inner.slice(k, p);
    }
    // number or bare token (e.g. `nil`)
    let k = p;
    while (p < inner.length && !/\s|,/.test(inner[p])) p++;
    const tok = inner.slice(k, p);
    if (/^-?\d+$/.test(tok)) return parseInt(tok, 10);
    if (tok === 'nil') return null;
    return tok;
  }
  while (p < inner.length) {
    skipWs();
    if (p >= inner.length) break;
    const k = readKey();
    if (!k) break;
    const v = readValue();
    out[k] = v;
  }
  return out;
}

// Pre-flight SKIP: route through the runner's shared skip helper so we
// don't spawn a child or install a watchdog. Same posture as the sibling
// end-to-end-causa.cjs placeholder. The skip helper prints the canonical
// `SKIP <reason>` banner and exits 0.
if (!process.env.SHADOW_CLJS_NREPL_PORT) {
  runWithWatchdog.skip(
    'live-pair2-overflow: $SHADOW_CLJS_NREPL_PORT not set.\n' +
      '      This variant requires a live shadow-cljs nREPL — without\n' +
      '      one the server runs degraded and the wire-cap cannot be\n' +
      '      tripped naturally. The sibling end-to-end-pair2.cjs covers\n' +
      '      degraded-mode protocol conformance; this variant adds\n' +
      '      cap-marker conformance under real over-budget conditions.',
  );
}

// Hard cap so a hung server doesn't wedge CI. nREPL connect + one
// eval round-trip + tear-down should comfortably fit in 60s.
runWithWatchdog(
  {
    watchdogMs: 60000,
    clientName: 'mcp-conformance-pair2-live-overflow',
    transportSpec: {
      command: process.execPath,
      // `--allow-eval` opts the spawned pair2-mcp server into the
      // eval-cljs tool, which is default-OFF in published builds per
      // rf2-cxx5s (cascade from rf2-czv3p). Without the flag,
      // eval-cljs returns `{:ok? false :reason :rf.error/eval-cljs-disabled}`
      // with `isError: true` before ever reaching nREPL — which means
      // the wire-cap path can't be exercised because no payload is
      // produced. This live-overflow harness's *whole purpose* is to
      // trip the cap on a real eval response, so it must opt in. The
      // default-OFF security contract is pinned by the unit fixture
      // `:eval-cljs/disabled-default` in tools/pair2-mcp's conformance
      // corpus — that's the right layer for the gate; here we want the
      // post-gate logical path.
      args: [SERVER, '--allow-eval'],
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

    // 4. The content slot must hold the canonical `:rf.mcp/overflow`
    // marker. We read the EDN text and parse the inner map. Any
    // surrounding `{:ok? true :value "xxxxx..."}` reaching the wire
    // would indicate `apply-cap` failed to trigger — the load-bearing
    // bug this variant guards against.
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

    // 5. Schema-validate the marker body against the canonical
    // OverflowBody shape pinned by wire-vocab. This is the
    // cross-server vocabulary assertion: pair2-mcp's wire emission
    // MUST satisfy the same shape as the wire-vocab fixture.
    const body = parseOverflowMarker(text);
    if (!body) {
      throw new Error(
        'Could not parse `:rf.mcp/overflow` body from response text.\n' +
          'First 500 chars: ' +
          text.slice(0, 500),
      );
    }
    assertOverflowBody(body, 'eval-cljs live overflow');
    console.log('OK   marker body validates against canonical OverflowBody schema');

    // 6. Pin the load-bearing semantic facts about the marker:
    //   - :cap-tokens MUST equal the documented default (5000).
    //     This catches the "default bumped silently" drift the bead
    //     description flagged.
    //   - :tool MUST equal "eval-cljs" (the offending tool name).
    //   - :hint MUST match the per-tool entry (the wire-cap test
    //     pins the fallback path; this pins the per-tool path).
    if (body[':cap-tokens'] !== DEFAULT_MAX_TOKENS) {
      throw new Error(
        ':cap-tokens MUST equal default ' +
          DEFAULT_MAX_TOKENS +
          ' (no per-call override sent); got ' +
          body[':cap-tokens'] +
          '. If the default has changed in pair2-mcp `tools.cljs`, ' +
          'update DEFAULT_MAX_TOKENS in this file and refresh the spec ' +
          '§"Tight token budget per response" reference together.',
      );
    }
    if (body[':tool'] !== 'eval-cljs') {
      throw new Error(
        ':tool MUST equal "eval-cljs"; got ' + JSON.stringify(body[':tool']),
      );
    }
    if (!body[':hint'] || !body[':hint'].includes('Slice')) {
      // The pair2-mcp `overflow-hints` table maps "eval-cljs" → "Slice
      // the value at the call-site (`get-in`, `take`, project to fewer
      // keys) before returning." A rename of the per-tool hint surfaces
      // here. We match on a stable substring rather than the whole
      // hint string so a copy-edit doesn't break the test.
      throw new Error(
        ':hint MUST contain "Slice" (per-tool pair2-mcp hint for ' +
          'eval-cljs); got ' +
          JSON.stringify(body[':hint']),
      );
    }
    console.log(
      'OK   marker body pins: :cap-tokens=' +
        body[':cap-tokens'] +
        ', :tool=' +
        body[':tool'] +
        ', :token-count=' +
        body[':token-count'],
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
    console.log('\nPAIR2-MCP LIVE OVERFLOW CONFORMANCE GREEN');
  },
);
