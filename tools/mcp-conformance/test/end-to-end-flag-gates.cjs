// End-to-end MCP-client conformance for the cross-MCP operator-opt-in
// CLI flag vocabulary (NAMING.md §"Operator-opt-in CLI flag
// vocabulary"). Source: rf2-ee38b.20 (review-remediation; the P1 gap the
// completeness lens flagged — the flag vocabulary had no conformance
// gate at all).
//
// ## What this guards
//
// NAMING.md pins a cross-server flag contract: same operator semantic ⇒
// same flag spelling, every authority-gate flag DEFAULT-OFF, and a
// "hard rename, no aliases" rule (a legacy / unrecognised spelling stops
// being recognised at the parser; it must NOT open the gate). Before this
// harness those claims were pinned only by each server's OWN unit
// fixtures — the cross-server, observable-over-the-wire contract was
// unenforced. A regression that renamed story-mcp's write gate to
// `--enable-writes`, or that flipped a default to ON, would have passed
// every conformance test here.
//
// This harness drives the contract through the official MCP SDK client —
// the same path a real consumer (Claude Code, Continue, …) takes — for
// the flag whose gated posture is observable WITHOUT a live runtime:
// story-mcp's `--allow-writes`. (The pair-mcp eval-cljs gate flipped
// to default-ON per rf2-a0z0h; the disabled envelope is reachable only
// with the `--no-eval` opt-out against a live nREPL — see "Coverage
// boundary" below; that wire check lives in
// `live-re-frame2-pair-subscribe.cjs`, which boots a non-degraded
// server with `--no-eval`.)
//
// ### story-mcp `--allow-writes` (default OFF, hard-rename rejection)
//
//   1. Boot WITHOUT the flag ⇒ `register-variant` returns
//      `isError: true` + `structuredContent.gated === true`
//      (the default-OFF posture). This is the security-critical claim:
//      a fresh / CI boot cannot mutate the registry.
//   2. Boot WITH `--allow-writes` ⇒ `register-variant` succeeds
//      (`isError: false`, registered) — the positive control proving the
//      flag spelling NAMING.md pins is the one the parser actually wires.
//   3. Boot with a legacy / near-miss spelling (`--enable-writes`,
//      `--allow-write`) ⇒ the gate stays CLOSED (`gated === true`). This
//      is the "hard rename, no aliases" rule observed end-to-end: an
//      unrecognised flag does NOT open the gate (the parser logs+ignores
//      it; no back-compat alias re-enables the surface).
//
// ## Coverage boundary (judgment, rf2-ee38b.20)
//
// The completeness lens suggested "boot each server WITHOUT its opt-in
// flag (no nREPL needed) and assert the gated tool returns the
// disabled-default envelope." That holds for story-mcp (JVM server, no
// degraded mode — `register-variant` always routes to the tool, which
// checks the gate first). It does NOT hold for re-frame2-pair-mcp:
// without a resolvable nREPL port pair-mcp boots in DEGRADED mode
// (`server.cljs` `degraded-handler`), where EVERY `tools/call` short-
// circuits to `:nrepl-port-not-found` before reaching the tool body — so
// `eval-cljs` never reaches its `--no-eval` gate and the
// `:rf.error/eval-cljs-disabled` envelope is unreachable degraded. The
// honest home for the pair-mcp eval-gate WIRE check is therefore the
// live path: `live-re-frame2-pair-subscribe.cjs` already boots a
// non-degraded server WITH `--no-eval` (the opt-out post-rf2-a0z0h),
// so it observes the disabled envelope over the wire when a runtime is
// attached. The pair-mcp parser
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
const {
  Client,
} = require('@modelcontextprotocol/sdk/client/index.js');
const {
  StdioClientTransport,
} = require('@modelcontextprotocol/sdk/client/stdio.js');
const { resolveTrustedExe } = require('../lib/exec-safety.cjs');

const STORY_MCP_CWD = path.resolve(__dirname, '..', '..', 'story-mcp');
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..');

// Resolve `clojure` to a trusted absolute path outside the workspace, or
// honour the explicit STORY_MCP_CMD override (same posture as
// end-to-end-story.cjs — rf2-33vvc accident-gating fires only on the
// implicit-PATH path).
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
// MCP surface, which is exactly the point). Three sequential boots fit
// comfortably under the watchdog with margin.
const WATCHDOG_MS = 180000;
const CLIENT_VERSION = '0.1.0';

// ---------------------------------------------------------------------------
// Boot one story-mcp server with the given extra CLI args, run `body`
// against the connected SDK client, and tear the transport down. Returns
// whatever `body` resolves to. Throws on connect / body failure.
//
// Each call spawns a fresh JVM so the boot-time gate atom reflects ONLY
// the args passed here — no cross-contamination between the default-OFF,
// opt-in, and rename-rejection probes.
// ---------------------------------------------------------------------------
async function withStoryServer(extraArgs, body) {
  const transport = new StdioClientTransport({
    command: CLOJURE,
    args: ['-M', '-m', 're-frame.story-mcp.server', ...extraArgs],
    cwd: STORY_MCP_CWD,
    env: { ...process.env },
    stderr: 'pipe',
  });
  const client = new Client(
    { name: 'mcp-conformance-flag-gates', version: CLIENT_VERSION },
    { capabilities: {} },
  );
  transport.stderr?.on('data', (d) =>
    process.stderr.write('[server] ' + d.toString()),
  );
  await client.connect(transport);
  try {
    return await body(client);
  } finally {
    try {
      await client.close();
    } catch (closeErr) {
      console.error(
        'NOTE: client.close() raised during teardown:',
        closeErr.message,
      );
    }
  }
}

function callRegisterVariant(client) {
  return client.callTool({
    name: 'register-variant',
    arguments: { 'variant-id': FIXTURE_VARIANT, body: FIXTURE_BODY_EDN },
  });
}

// ---------------------------------------------------------------------------
// The three story-mcp `--allow-writes` conformance probes.
// ---------------------------------------------------------------------------

async function assertWriteGateClosedByDefault() {
  await withStoryServer([], async (client) => {
    const resp = await callRegisterVariant(client);
    // Default-OFF: the write tool MUST refuse with a tool-execution
    // error (NOT a JSON-RPC protocol error — the agent's conversation
    // survives) carrying the documented `:gated true` structuredContent.
    if (!resp.isError) {
      throw new Error(
        'story-mcp register-variant MUST isError when booted WITHOUT ' +
          '--allow-writes (default-OFF posture per NAMING.md ' +
          '§"Operator-opt-in CLI flag vocabulary"); got: ' +
          JSON.stringify(resp),
      );
    }
    const struct = resp.structuredContent || {};
    if (struct.gated !== true) {
      throw new Error(
        'story-mcp write-gate-closed envelope MUST carry ' +
          'structuredContent.gated === true; got: ' + JSON.stringify(resp),
      );
    }
    const text = resp.content?.[0]?.text || '';
    if (!/write surface disabled/i.test(text)) {
      throw new Error(
        'story-mcp write-gate-closed text MUST mention "Write surface ' +
          'disabled"; got: ' + text.slice(0, 200),
      );
    }
    console.log(
      'OK   story-mcp --allow-writes default-OFF -> register-variant ' +
        'isError + structuredContent.gated=true',
    );
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
    if (struct.registered !== true && struct['registered?'] !== true) {
      throw new Error(
        'story-mcp register-variant with --allow-writes MUST report ' +
          'registered; got: ' + JSON.stringify(resp),
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
  await assertWriteGateOpensWithFlag();
  await assertLegacyFlagSpellingRejected();

  console.log('\nMCP CLI FLAG-VOCABULARY CONFORMANCE GREEN');
}

// Watchdog so a hung JVM can't wedge CI. Three sequential cold boots +
// one register round-trip each fit well under this.
const watchdog = setTimeout(() => {
  console.error('FAIL: watchdog timeout (' + WATCHDOG_MS + 'ms)');
  process.exit(2);
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
