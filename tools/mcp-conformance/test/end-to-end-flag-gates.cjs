// End-to-end MCP-client conformance for the cross-MCP operator-opt-in
// CLI flag vocabulary (NAMING.md §"Operator-opt-in CLI flag
// vocabulary").
//
// ## What this guards
//
// NAMING.md pins a cross-server flag contract: same operator semantic ⇒
// same flag spelling, every authority-gate flag DEFAULT-OFF, and a
// "hard rename, no aliases" rule (a legacy / unrecognised spelling stops
// being recognised at the parser; it must NOT open the gate). This
// harness enforces the cross-server, observable-over-the-wire contract
// that each server's OWN unit fixtures cannot reach. A regression that
// renamed story-mcp's write gate to `--enable-writes`, or that flipped a
// default to ON, turns RED here.
//
// This harness drives the contract through the official MCP SDK client —
// the same path a real consumer (Claude Code, Continue, …) takes — for
// the flag whose gated posture is observable WITHOUT a live runtime:
// story-mcp's `--allow-writes`. (The pair-mcp eval-cljs gate is
// default-ON; the disabled envelope is reachable only with the
// `--no-eval` opt-out against a live nREPL — see "Coverage boundary"
// below; that wire check lives in `live-re-frame2-pair-subscribe.cjs`,
// which boots a non-degraded server with `--no-eval`.)
//
// ### story-mcp `--allow-writes` (default OFF, hard-rename rejection)
//
//   1. Boot WITHOUT the flag ⇒ ALL THREE gated write tools
//      (`register-variant`, `unregister-variant`, and
//      `record-as-variant` WITH `:write-back true`) return
//      `isError: true` + `structuredContent.gated === true` +
//      `structuredContent.tool` naming the tripping tool (the default-OFF
//      posture per spec/003-Write-Surface-Gating.md §"What's gated"). This
//      is the security-critical claim: a fresh / CI boot cannot mutate the
//      registry by ANY gated path. Probing all three default-off means a
//      dropped gate-check on any of them turns RED.
//   1b. Boot WITHOUT the flag, with the target variant pre-registered via
//      a boot-time `-e` seed (in-process `story/reg-variant*`, NOT the
//      gated MCP `register-variant` surface) ⇒ `record-as-variant`
//      WITHOUT `:write-back` SUCCEEDS — the intentionally UNGATED
//      snippet-only recording path, genuinely exercised under a
//      gate-CLOSED boot. A regression that added an unconditional gate
//      check to `record-as-variant` (dropping the `(when write-back? …)`
//      guard) turns RED here (rf2-j5ixcg). Together with probe 1's
//      write-back:true refusal this pins the gating boundary exactly on
//      `:write-back`.
//   2. Boot WITH `--allow-writes` ⇒ `register-variant` succeeds
//      (`isError: false`, registered) — the positive control proving the
//      flag spelling NAMING.md pins is the one the parser actually wires.
//   3. Boot with a legacy / near-miss spelling (`--enable-writes`,
//      `--allow-write`) ⇒ the gate stays CLOSED (`gated === true`). This
//      is the "hard rename, no aliases" rule observed end-to-end: an
//      unrecognised flag does NOT open the gate (the parser logs+ignores
//      it; no back-compat alias re-enables the surface).
//
// ## Coverage boundary (judgment)
//
// One tempting design is "boot each server WITHOUT its opt-in flag (no
// nREPL needed) and assert the gated tool returns the disabled-default
// envelope." That holds for story-mcp (JVM server, no
// degraded mode — `register-variant` always routes to the tool, which
// checks the gate first). It does NOT hold for re-frame2-pair-mcp:
// without a resolvable nREPL port pair-mcp boots in DEGRADED mode
// (`server.cljs` `degraded-handler`), where EVERY `tools/call` short-
// circuits to `:nrepl-port-not-found` before reaching the tool body — so
// `eval-cljs` never reaches its `--no-eval` gate and the
// `:rf.error/eval-cljs-disabled` envelope is unreachable degraded. The
// honest home for the pair-mcp eval-gate WIRE check is therefore the
// live path: `live-re-frame2-pair-subscribe.cjs` boots a non-degraded
// server WITH `--no-eval` (the eval opt-out), so it observes the disabled
// envelope over the wire when a runtime is attached. The pair-mcp parser
// rename-rejection itself (`--allow-raw-state` legacy spelling ⇒ gate
// stays closed) is pinned at the unit layer by
// `re-frame2-pair-mcp/test/.../raw_state_test.cljs`
// (`parse-launch-flags-old-name-rejected`) — duplicating that JVM-side
// unit test here would buy nothing the live wire check + unit pin don't
// already give.
//
// Run with: `node test/end-to-end-flag-gates.cjs` from this directory.
// Requires `clojure` on PATH (override via $STORY_MCP_CMD). Exits 0 on
// success, 1 on a conformance violation.

const fs = require('node:fs');
const path = require('node:path');
const { connectServer, closeQuietly, structured } = require('./_runner.cjs');
const { resolveTrustedExe } = require('../lib/exec-safety.cjs');

// `record-as-variant` ships its structuredContent wrapped in the
// `{:rf.mcp/dedup-table …}` envelope (recorder.cljc `:dedup-eligible?
// true`); a real MCP client decodes it via `de-dupe.core/expand` before
// reading semantic slots. The shared `structured` helper (_runner.cjs)
// mirrors that — idempotent on payloads without the marker (register /
// unregister envelopes pass through unchanged), so it is safe to route
// every structuredContent read through it.

const STORY_MCP_CWD = path.resolve(__dirname, '..', '..', 'story-mcp');
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..');

// Resolve `clojure` to a trusted absolute path outside the workspace, or
// honour the explicit STORY_MCP_CMD override (same posture as
// end-to-end-story.cjs — accident-gating fires only on the implicit-PATH
// path).
const CLOJURE = process.env.STORY_MCP_CMD
  ? process.env.STORY_MCP_CMD
  : resolveTrustedExe('clojure', { workspaceRoot: REPO_ROOT });

// A namespaced fixture variant id — `:story.<path>/<name>` grammar the
// registrar's `assert-id!` enforces. Distinct from the ids the sibling
// `end-to-end-story.cjs` and the upstream `stdio-roundtrip.js` register
// so the harnesses can't collide on a shared JVM.
const FIXTURE_VARIANT = 'story.mcp-conformance/flag-gate.probe';
const FIXTURE_BODY_EDN = '{:doc "flag-gate conformance probe." :tags #{:dev}}';

// Cold JVM boot is ~10-30s; each gate below boots a fresh JVM (the gate
// is a boot-time atom — we can't flip it on a running server through the
// MCP surface, which is exactly the point). The seven boots (default-off,
// snippet-only-ungated, record-as-variant write-back gate OFF + ON,
// opt-in, two legacy spellings) fit comfortably under the 300s cap on a
// cold CI worker.
//
// `$FLAG_GATE_WATCHDOG_MS` overrides the cap for the hermetic
// connect-hang regression harness ONLY (`runner-watchdog.test.cjs` drives
// this module with a stubbed never-resolving connect under a 200ms cap to
// prove the watchdog tears the hung JVM down). Production CI never sets
// it, so the 300s cap stands.
const WATCHDOG_MS = Number(process.env.FLAG_GATE_WATCHDOG_MS) || 300000;

// ---------------------------------------------------------------------------
// Boot one story-mcp server with the given extra CLI args, run `body`
// against the connected SDK client, and tear the transport down. Returns
// whatever `body` resolves to. Throws on connect / body failure.
//
// Each call spawns a fresh JVM so the boot-time gate atom reflects ONLY
// the args passed here — no cross-contamination between the default-OFF,
// opt-in, and rename-rejection probes. The spawn+connect+teardown
// ceremony is the shared `connectServer` / `closeQuietly` primitive —
// `runWithWatchdog`'s single-boot-per-process form can't serve this gate,
// which needs several fresh in-process JVM boots.
// ---------------------------------------------------------------------------
// The watchdog (module scope below) must OWN whichever story-mcp JVM is
// currently booted so a timeout tears it down instead of orphaning it
// (the same orphan class the shared `_runner.cjs` watchdog and the
// hermetic orchestrator guard). `withStoryServer` publishes its connected
// client here for the spawn's lifetime and clears it on teardown; the
// boots are sequential so at most one is live at once.
//
// Publish the handle via `connectServer`'s `onClient` callback — fired
// the INSTANT the client is constructed, BEFORE the `await
// client.connect()` handshake — not after `connectServer` resolves. The
// transport spawns the JVM during construction, so a story-mcp child that
// boots and then HANGS mid-`initialize` (connect never resolving, never
// throwing) leaves `await connectServer(...)` blocked forever; capturing
// the handle only post-resolve would leave the module-scope watchdog with
// `activeFlagGateClient === null`, and its `else` branch would
// `process.exit(2)` WITHOUT tearing the hung JVM down — orphaning exactly
// the child the watchdog exists to reap. `onClient` closes that window.
// (This is the bare-caller analogue of the `onClient` capture in
// `runWithWatchdog`, which these callers bypass.)
let activeFlagGateClient = null;

// Shared spawn+connect+teardown ceremony. `clojureArgs` is the FULL arg
// vector after `clojure` (both `withStoryServer`'s `-M -m … extraArgs` form
// and `withGateClosedSeededStoryServer`'s `-M -e seedForm -m …` form route
// through here) so the watchdog-handle capture + teardown logic has one
// home.
async function bootStoryServer(clojureArgs, body) {
  const { client } = await connectServer({
    clientName: 'mcp-conformance-flag-gates',
    transportSpec: {
      command: CLOJURE,
      args: clojureArgs,
      cwd: STORY_MCP_CWD,
      env: { ...process.env },
    },
    // Capture the teardown handle BEFORE connect awaits, so a connect that
    // hangs mid-handshake is still reachable by the module-scope watchdog.
    // The post-resolve assignment below is redundant on the happy path but
    // kept belt-and-braces.
    onClient: (c) => { activeFlagGateClient = c; },
  });
  activeFlagGateClient = client;
  try {
    return await body(client);
  } finally {
    await closeQuietly(client);
    activeFlagGateClient = null;
  }
}

async function withStoryServer(extraArgs, body) {
  return bootStoryServer(['-M', '-m', 're-frame.story-mcp.server', ...extraArgs], body);
}

// rf2-j5ixcg: boot the server with the write gate CLOSED (no
// `--allow-writes`, no sysprop, no env var) but with `FIXTURE_VARIANT`
// ALREADY REGISTERED — seeded via a `clojure -e` form evaluated BEFORE
// `-main` runs, calling `story/reg-variant*` directly. `reg-variant*` is
// the registrar's in-process primitive; the write GATE
// (`write/assert-writes-allowed`) lives only in the MCP tool layer
// (`write.cljc` / `recorder.cljc`), never in the registrar itself — so
// seeding this way is a legitimate boot-time fixture, genuinely
// independent of `--allow-writes`, NOT a detour around the contract this
// harness exists to pin.
//
// This is what makes `assertSnippetOnlyRecordIsUngated` discriminating: a
// registered target under a CLOSED gate is unreachable through the MCP
// surface alone (`register-variant` is itself gated), which is exactly
// why an earlier version of that probe seeded the fixture by booting
// gate-OPEN — indistinguishable from a regression that gated
// `record-as-variant` unconditionally, since `assert-writes-allowed`
// passes identically whether skipped or invoked-and-open. Seeding
// out-of-band gives the probe a registered target UNDER A CLOSED GATE,
// so the real guard (`(when write-back? …)`) is what determines the
// outcome.
function seedVariantEvalForm() {
  return (
    "(require '[re-frame.story :as story]) " +
    '(story/reg-variant* :' + FIXTURE_VARIANT + ' ' + FIXTURE_BODY_EDN + ')'
  );
}

async function withGateClosedSeededStoryServer(body) {
  return bootStoryServer(
    ['-M', '-e', seedVariantEvalForm(), '-m', 're-frame.story-mcp.server'],
    body,
  );
}

function callRegisterVariant(client) {
  return client.callTool({
    name: 'register-variant',
    arguments: { 'variant-id': FIXTURE_VARIANT, body: FIXTURE_BODY_EDN },
  });
}

// The story-mcp write-gated tools whose gate is reachable in a CLEAN
// gate-OFF boot, per tools/story-mcp/spec/003-Write-Surface-Gating.md
// §"What's gated". `register-variant` and `unregister-variant` check the
// `--allow-writes` gate as their FIRST line (write.cljc:
// `(or (assert-writes-allowed …) …)`), BEFORE resolving any variant — so
// with the gate closed they refuse with a tool-execution error (NOT a
// JSON-RPC protocol error — the agent's conversation survives) carrying
// `structuredContent.gated === true` AND `structuredContent.tool` naming
// the tripping tool (the `:tool` slot is per-tool, not hardcoded).
//
// Both tools are probed default-off here so a dropped gate-check on
// either turns RED, not just `register-variant`. The THIRD gated tool,
// `record-as-variant` (gated only when `:write-back true`), has a
// different gate-ORDERING and is handled separately by
// `assertRecordAsVariantWriteBackGate` — see the long note there.
const GATED_WRITE_PROBES = [
  {
    name: 'register-variant',
    arguments: { 'variant-id': FIXTURE_VARIANT, body: FIXTURE_BODY_EDN },
  },
  {
    name: 'unregister-variant',
    arguments: { 'variant-id': FIXTURE_VARIANT },
  },
];

// ---------------------------------------------------------------------------
// The three story-mcp `--allow-writes` conformance probes.
// ---------------------------------------------------------------------------

async function assertWriteGateClosedByDefault() {
  // One fresh gate-OFF boot exercises ALL THREE gated write tools. The
  // spec gates all three, so each gets default-off wire coverage here —
  // `register-variant`, `unregister-variant`, and `record-as-variant`
  // (write-back) — and a dropped gate-check on any of them turns RED.
  await withStoryServer([], async (client) => {
    for (const probe of GATED_WRITE_PROBES) {
      const resp = await client.callTool(probe);
      // Default-OFF: the write tool MUST refuse with a tool-execution
      // error (NOT a JSON-RPC protocol error — the agent's conversation
      // survives) carrying the documented `:gated true` structuredContent.
      if (!resp.isError) {
        throw new Error(
          'story-mcp ' + probe.name + ' MUST isError when booted WITHOUT ' +
            '--allow-writes (default-OFF posture per NAMING.md ' +
            '§"Operator-opt-in CLI flag vocabulary" + ' +
            'spec/003-Write-Surface-Gating.md); got: ' + JSON.stringify(resp),
        );
      }
      const struct = resp.structuredContent || {};
      if (struct.gated !== true) {
        throw new Error(
          'story-mcp ' + probe.name + ' write-gate-closed envelope MUST carry ' +
            'structuredContent.gated === true; got: ' + JSON.stringify(resp),
        );
      }
      // The `:tool` slot names the tripping tool (per-tool, not
      // hardcoded). Assert it matches the tool we called so a copy-paste
      // regression that returned a sibling tool's refusal (still carrying
      // `:gated true`) is caught.
      if (struct.tool !== probe.name) {
        throw new Error(
          'story-mcp ' + probe.name + ' gated envelope MUST name the tripping ' +
            'tool in structuredContent.tool (per spec/003 §"How the gate ' +
            'fails"); expected "' + probe.name + '", got: ' +
            JSON.stringify(struct.tool) + ' — full: ' + JSON.stringify(resp),
        );
      }
      const text = resp.content?.[0]?.text || '';
      if (!/write surface disabled/i.test(text)) {
        throw new Error(
          'story-mcp ' + probe.name + ' write-gate-closed text MUST mention ' +
            '"Write surface disabled"; got: ' + text.slice(0, 200),
        );
      }
      console.log(
        'OK   story-mcp --allow-writes default-OFF -> ' + probe.name +
          ' isError + structuredContent.gated=true + tool="' + probe.name + '"',
      );
    }
  });
}

// The intentional UNGATED path (spec/003-Write-Surface-Gating.md
// §"What's gated"): `record-as-variant` WITHOUT `:write-back` is a
// snippet-only recording — it returns the `(reg-variant …)` text snippet
// and does NOT mutate the registry, so it is deliberately NOT gated
// (recorder.cljc gates only `(when write-back? …)`). A regression that
// wrongly extended the write gate to the snippet-only path would break the
// read-only recording loop for every default (gate-OFF) deploy.
//
// This probe + `assertRecordAsVariantWriteBackGate` together pin the
// gating boundary EXACTLY on `:write-back`:
//
//   - write-back:FALSE ⇒ ALWAYS succeeds, snippet only, NO mutation,
//     REGARDLESS of the write gate's boot posture (this probe).
//   - write-back:TRUE  ⇒ requires the gate open; gate-closed refuses
//     once a target is registered (the other probe).
//
// rf2-j5ixcg: an earlier version of this probe booted WITH
// `--allow-writes` to seed the fixture (`register-variant` itself needs
// the gate open to register through the MCP surface), then called
// `record-as-variant` under that SAME gate-open boot. That made the probe
// blind to its own regression: `write/assert-writes-allowed` returns nil
// (pass) whenever the gate is open, identically whether
// `tool-record-as-variant` correctly SKIPS the check for `write-back:
// false` or a regression added an UNCONDITIONAL gate check — under a
// gate-OPEN boot the two are indistinguishable, so a dropped
// `(when write-back? …)` guard would have shipped green.
//
// Fix (bead option (b) — the gate-closed fixture path IS feasible): seed
// the target variant via `withGateClosedSeededStoryServer`, which
// registers it in-process via `story/reg-variant*` through a boot-time
// `-e` form, BEFORE `-main` ever runs — bypassing the gated MCP
// `register-variant` tool entirely. The server then boots with the write
// gate CLOSED (no `--allow-writes`), and this probe calls
// `record-as-variant` WITHOUT `:write-back` against the pre-seeded
// target. Now a regression that added an unconditional gate check DOES
// turn this probe RED (the closed gate refuses), while the correct
// `(when write-back? …)` guard still lets it through — the probe is
// finally discriminating on the exact regression it claims to net.
async function assertSnippetOnlyRecordIsUngated() {
  await withGateClosedSeededStoryServer(async (client) => {
    // Snippet-only record: NO `:write-back` — the ungated path, probed
    // against a GATE-CLOSED boot (the target was seeded in-process, not
    // through the gated MCP surface). MUST succeed and return a
    // `:play-snippet`, and MUST NOT report a write-back
    // (`:written-back? false`).
    const rec = await client.callTool({
      name: 'record-as-variant',
      arguments: { 'variant-id': FIXTURE_VARIANT },
    });
    if (rec.isError) {
      throw new Error(
        'record-as-variant snippet-only (no :write-back) MUST succeed even ' +
          'with the write gate CLOSED — it is the INTENTIONALLY UNGATED ' +
          'recording path (spec/003 §"What\'s gated"); a regression that ' +
          'added an unconditional gate check to record-as-variant ' +
          '(dropping the `(when write-back? …)` guard — rf2-j5ixcg) would ' +
          'surface here as isError; got: ' + JSON.stringify(rec),
      );
    }
    const struct = structured(rec);
    if (struct.gated === true) {
      throw new Error(
        'record-as-variant snippet-only MUST NOT be gated (write-back ' +
          'absent) even with the write gate CLOSED; got a gated envelope: ' +
          JSON.stringify(rec),
      );
    }
    if (typeof struct['play-snippet'] !== 'string') {
      throw new Error(
        'record-as-variant snippet-only MUST emit a :play-snippet string; ' +
          'got: ' + JSON.stringify(rec),
      );
    }
    if (struct['written-back?'] !== false) {
      throw new Error(
        'record-as-variant snippet-only MUST report :written-back? false ' +
          '(no registry mutation on the ungated path); got: ' + JSON.stringify(rec),
      );
    }
    console.log(
      'OK   story-mcp record-as-variant snippet-only (no --write-back) -> ' +
        'ungated success + :play-snippet, :written-back? false (write gate ' +
        'CLOSED — genuinely discriminating, rf2-j5ixcg)',
    );
    // No teardown: the gate is closed (unregister-variant is itself
    // gated), and the fresh-JVM-per-boot shape means the seeded
    // registration dies with this process.
  });
}

// `record-as-variant` write-back gate — ORDERING ASYMMETRY (flagged for
// the server owner as a possible consistency fix).
//
// A DEFAULT-OFF gated refusal (`gated:true` + `tool:"record-as-variant"`)
// for `record-as-variant` with `:write-back true` is NOT reachable in a
// clean gate-OFF boot, because `record-as-variant`'s gate ORDERING
// differs from its two siblings:
//
//   - register-variant / unregister-variant (write.cljc) check
//     `assert-writes-allowed` as their FIRST expression — BEFORE any
//     variant resolution — so a gate-OFF boot refuses with `gated:true`
//     regardless of whether any variant exists.
//   - record-as-variant (recorder.cljc `tool-record-as-variant`) wraps
//     the whole body in `(targs/with-variant arguments (fn [vk body] …))`,
//     which resolves `:variant-id` against the LIVE registered-variant
//     set FIRST; the `(when write-back? (write/assert-writes-allowed …))`
//     gate is NESTED inside that callback. The canonical-vocabulary boot
//     registers NO variants, and a gate-OFF boot cannot register one
//     through the MCP surface, so a gate-OFF `record-as-variant`
//     write-back:true call short-circuits to `Variant not found` BEFORE
//     the gate is ever consulted — the `gated:true` envelope is
//     unreachable default-off.
//
// So the literal default-off `gated:true` probe is IMPOSSIBLE via the MCP
// surface without either (a) a server change reordering the gate ahead of
// variant resolution (consistent with the two siblings — FLAGGED), or
// (b) a registered fixture under a closed gate (no single boot provides
// both). Rather than fake it, this function pins what IS reachable and
// adversarial, on both gate states:
//
//   1. gate OFF, write-back:true, unregistered target ⇒ `Variant not
//      found` (NOT `gated:true`). This documents + REGRESSION-LOCKS the
//      ordering: if the server is later fixed to gate-first (the flagged
//      change), this assertion flips to expect `gated:true` and
//      `tool:"record-as-variant"` — the test is the executable record of
//      the current contract and the seam for the fix.
//   2. gate ON, write-back:true ⇒ the positive control: the write-back
//      path SUCCEEDS, reports `:written-back? true`, and mints the new
//      variant id. This is the load-bearing write-back coverage (a
//      regression that broke the write-back registration, or that gated
//      it even with the flag ON, ships RED here) — just on the reachable
//      gate state.
async function assertRecordAsVariantWriteBackGate() {
  const NEW_VID = 'story.mcp-conformance/flag-gate.recorded';

  // 1. gate OFF — write-back:true against an unregistered target. The
  // gate ordering means this surfaces `Variant not found`, NOT a gated
  // envelope. We assert that precisely so the asymmetry is locked.
  await withStoryServer([], async (client) => {
    const resp = await client.callTool({
      name: 'record-as-variant',
      arguments: {
        'variant-id': FIXTURE_VARIANT,
        'write-back': true,
        'new-variant-id': NEW_VID,
      },
    });
    if (!resp.isError) {
      throw new Error(
        'record-as-variant (write-back, gate OFF, unregistered target) MUST ' +
          'isError; got: ' + JSON.stringify(resp),
      );
    }
    const struct = structured(resp);
    const text = resp.content?.[0]?.text || '';
    // The VERIFIED current contract: variant resolution precedes the gate,
    // so an unregistered target short-circuits to not-found and the gate
    // is never reached. If a future server change reorders the gate ahead
    // of variant resolution (the flagged consistency fix), update this to
    // expect `struct.gated === true` + `struct.tool === 'record-as-variant'`.
    if (struct.gated === true) {
      throw new Error(
        'record-as-variant write-back gate ORDERING changed: this gate-OFF ' +
          'probe now sees `gated:true` (the server was reordered to ' +
          'gate-first — the rf2-ke5n56 flagged consistency fix). Update this ' +
          "assertion to pin `tool:\"record-as-variant\"` like its register/" +
          'unregister siblings. got: ' + JSON.stringify(resp),
      );
    }
    if (!/not found/i.test(text)) {
      throw new Error(
        'record-as-variant (write-back, gate OFF, unregistered target) MUST ' +
          'surface `Variant not found` under the verified variant-resolution-' +
          'before-gate ordering (rf2-ke5n56); got: ' + text.slice(0, 200),
      );
    }
    console.log(
      'OK   story-mcp record-as-variant (write-back, gate OFF, unregistered) ' +
        '-> Variant not found (verified gate-ordering asymmetry, rf2-ke5n56)',
    );
  });

  // 2. gate ON — positive control: write-back:true SUCCEEDS, writes back.
  await withStoryServer(['--allow-writes'], async (client) => {
    const seed = await client.callTool({
      name: 'register-variant',
      arguments: { 'variant-id': FIXTURE_VARIANT, body: FIXTURE_BODY_EDN },
    });
    if (seed.isError) {
      throw new Error(
        'record-as-variant write-back setup: register-variant failed: ' +
          JSON.stringify(seed),
      );
    }
    const rec = await client.callTool({
      name: 'record-as-variant',
      arguments: {
        'variant-id': FIXTURE_VARIANT,
        'write-back': true,
        'new-variant-id': NEW_VID,
      },
    });
    if (rec.isError) {
      throw new Error(
        'record-as-variant (write-back, gate ON) MUST succeed — the gate is ' +
          'open and the target is registered; got: ' + JSON.stringify(rec),
      );
    }
    const struct = structured(rec);
    if (struct.gated === true) {
      throw new Error(
        'record-as-variant (write-back, gate ON) MUST NOT be gated; got a ' +
          'gated envelope: ' + JSON.stringify(rec),
      );
    }
    if (struct['written-back?'] !== true) {
      throw new Error(
        'record-as-variant (write-back, gate ON) MUST report ' +
          ':written-back? true (the registry mutation happened); got: ' +
          JSON.stringify(rec),
      );
    }
    console.log(
      'OK   story-mcp record-as-variant (write-back, gate ON) -> success + ' +
        ':written-back? true (positive control)',
    );
    // Teardown both the source and the written-back variant.
    for (const vid of [FIXTURE_VARIANT, NEW_VID]) {
      try {
        await client.callTool({
          name: 'unregister-variant',
          arguments: { 'variant-id': vid },
        });
      } catch (_e) {
        // best-effort; fresh-JVM-per-boot makes a stray registration harmless.
      }
    }
  });
}

async function assertWriteGateOpensWithFlag() {
  await withStoryServer(['--allow-writes'], async (client) => {
    const resp = await callRegisterVariant(client);
    // Positive control: the EXACT flag spelling NAMING.md pins MUST open
    // the gate. If the parser wired a different spelling this fails.
    if (resp.isError) {
      throw new Error(
        'story-mcp register-variant MUST succeed when booted WITH ' +
          '--allow-writes (the canonical flag spelling per NAMING.md); ' +
          'got isError: ' + JSON.stringify(resp),
      );
    }
    const struct = resp.structuredContent || {};
    // Pin the SINGLE canonical key story-mcp emits: `:registered?`
    // (Cheshire keeps the `?` → JSON key `registered?`; pinned JVM-side
    // by tools_test.clj and by the sibling end-to-end-story.cjs). Pinning
    // exactly one spelling catches the envelope-key drift this harness
    // family pins single-spelling; a drift to bare `registered` trips RED
    // here, in agreement with its sibling. A tolerated second spelling
    // would mask it.
    if (struct['registered?'] !== true) {
      throw new Error(
        'story-mcp register-variant with --allow-writes MUST report ' +
          ':registered? true; got: ' + JSON.stringify(resp),
      );
    }
    console.log(
      'OK   story-mcp --allow-writes opt-in -> register-variant succeeds ' +
        '(canonical flag spelling wired)',
    );
    // Symmetric teardown so a shared-JVM future run starts clean.
    try {
      await client.callTool({
        name: 'unregister-variant',
        arguments: { 'variant-id': FIXTURE_VARIANT },
      });
    } catch (_e) {
      // best-effort cleanup; the fresh-JVM-per-boot shape makes a stray
      // registration harmless, but tidy up anyway.
    }
  });
}

async function assertLegacyFlagSpellingRejected() {
  // "Hard rename, no aliases" (NAMING.md §Rules): a legacy / near-miss
  // flag spelling MUST NOT open the gate. Boot with two unrecognised
  // spellings and assert the gate stays CLOSED. The parser logs+ignores
  // unknown flags (no back-compat alias path), so the disabled-default
  // envelope is the observable proof the rename is hard.
  for (const legacyFlag of ['--enable-writes', '--allow-write']) {
    await withStoryServer([legacyFlag], async (client) => {
      const resp = await callRegisterVariant(client);
      if (!resp.isError || (resp.structuredContent || {}).gated !== true) {
        throw new Error(
          'story-mcp booted with the unrecognised flag `' + legacyFlag +
            '` MUST keep the write gate CLOSED (hard-rename / no-alias ' +
            'rule per NAMING.md §Rules); the gate opened (no isError / ' +
            'gated). got: ' + JSON.stringify(resp),
        );
      }
      console.log(
        'OK   story-mcp unrecognised flag `' + legacyFlag +
          '` rejected -> write gate stays closed (no alias)',
      );
    });
  }
}

async function main() {
  // Sanity: story-mcp must be launchable. resolveTrustedExe throws if
  // `clojure` isn't on PATH; surface a clear message early.
  if (!fs.existsSync(path.join(STORY_MCP_CWD, 'deps.edn'))) {
    throw new Error('story-mcp deps.edn missing at ' + STORY_MCP_CWD);
  }

  await assertWriteGateClosedByDefault();
  await assertSnippetOnlyRecordIsUngated();
  await assertRecordAsVariantWriteBackGate();
  await assertWriteGateOpensWithFlag();
  await assertLegacyFlagSpellingRejected();

  console.log('\nMCP CLI FLAG-VOCABULARY CONFORMANCE GREEN');
}

// Watchdog so a hung JVM can't wedge CI. The sequential cold boots +
// one register round-trip each fit well under this.
//
// On a timeout, tear the currently-booted story-mcp JVM down BEFORE
// exiting rather than calling `process.exit(2)` directly — a bare exit
// would orphan whichever JVM was live, and a child that inherited the
// step's stdio can keep the CI log pipes open past the node exit (the
// same orphan class the shared `_runner.cjs` watchdog and the hermetic
// orchestrator guard). `closeQuietly` closes the client, which closes its
// transport and kills the stdio JVM child; a hard-exit fallback fires
// after a short grace window so a hung close can't keep us alive.
const watchdog = setTimeout(() => {
  console.error('FAIL: watchdog timeout (' + WATCHDOG_MS + 'ms)');
  const hardExit = setTimeout(() => process.exit(2), 2000);
  hardExit.unref();
  if (activeFlagGateClient) {
    closeQuietly(activeFlagGateClient).finally(() => process.exit(2));
  } else {
    process.exit(2);
  }
}, WATCHDOG_MS);

main()
  .then(() => {
    clearTimeout(watchdog);
    process.exit(0);
  })
  .catch((err) => {
    clearTimeout(watchdog);
    console.error('FAIL:', err && err.message ? err.message : err);
    if (err && err.stack) console.error(err.stack);
    process.exit(1);
  });
