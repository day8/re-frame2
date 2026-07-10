#!/usr/bin/env node

'use strict';

/*
 * Source-policy gate: every `implementation/scripts/**` launcher AND
 * every executable `examples/scripts/**` browser-gate launcher that
 * spawns a child process must use the HARDENED, shell-free posture
 * (rf2-wn4o1; examples/scripts coverage added by rf2-y9o5e3). Two
 * accident classes are gated here, both static (no process is spawned by
 * this suite — it reads the script sources and asserts on their text):
 *
 *   1. No `shell: true` / `shell: isWin` on any spawn in these scripts.
 *      On Windows `shell:true` + a bare exe name (`npx`/`npm`/`clojure`)
 *      + a repo-controlled `cwd` resolves a workspace-local `.cmd` ahead
 *      of PATH — the rf2-33vvc command-hijack accident class. It also
 *      re-introduces the DEP0190 args-concatenation warning/quoting
 *      class on every Windows launch.
 *
 *   2. No bare `npx` / `npx.cmd` passed as the spawn EXECUTABLE. The
 *      hardened forms spawn the resolved JS entry-point of the tool
 *      (`require.resolve('shadow-cljs/cli/runner.js')`,
 *      `require.resolve('http-server/bin/http-server')`) under THIS node
 *      binary (`process.execPath`), so the only thing the OS interprets
 *      is an absolute path that is outside the workspace by
 *      construction. A user-facing copy/paste hint string that mentions
 *      `npx http-server ...` is fine — it is text the developer types in
 *      their own shell, never a spawn argument.
 *
 *      EXCEPTION: a bare `'npx'` token that is fed to `resolveTrustedExe`
 *      and spawned via `cross-spawn` (the test-mcp-conformance.cjs
 *      posture) is the OTHER blessed hardened form — the name is
 *      resolved to a single trusted absolute path OUTSIDE the workspace
 *      before any spawn, and cross-spawn dispatches it shell-free. So a
 *      `'npx'` literal is only a violation in a file that does NOT route
 *      through resolveTrustedExe + cross-spawn. A `.cmd`-suffixed literal
 *      (`'npx.cmd'`) is always a violation: the `.cmd` shim only matters
 *      when you spawn it directly under a shell, which both hardened
 *      forms avoid.
 *
 * The hardened reference patterns already live in dev-testbed.cjs
 * (shadow-cljs via require.resolve + process.execPath) and
 * test-mcp-conformance.cjs (resolveTrustedExe + cross-spawn for
 * system binaries). New launchers must follow one of those.
 *
 * Wired into `package.json` via `test:script-policy`.
 */

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
// Shared executable-source filtering, loopback matcher, and test harness.
const {
  stripComments,
  loopbackBindRe,
  createPolicyTestSuite,
} = require('./_policy-test-util.cjs');

const SCRIPTS_DIR = __dirname;
// Examples browser-gate launchers share the same shell-free spawn policy.
const EXAMPLES_SCRIPTS_DIR = path.resolve(
  __dirname,
  '..',
  '..',
  'examples',
  'scripts',
);
// The adapter-smoke harness is colocated with the adapters it drives and is
// subject to the same policy.
const ADAPTERS_SCRIPTS_DIR = path.resolve(__dirname, '..', 'adapters', 'scripts');

const { test, run } = createPolicyTestSuite('script-spawn-policy');

// Every `.cjs` launcher in a scanned scripts dir (excludes the test
// suites and the lib/ helpers — the harness lib carries its own
// taskkill/spawn that is exercised by _local-browser-harness.test.cjs).
function cjsFilesIn(dir) {
  return fs
    .readdirSync(dir)
    .filter((f) => f.endsWith('.cjs') && !f.endsWith('.test.cjs'))
    .map((f) => path.join(dir, f));
}

// Scan every executable launcher in the implementation, examples, and adapter
// script surfaces.
function gateScriptFiles() {
  return [
    ...cjsFilesIn(SCRIPTS_DIR),
    ...cjsFilesIn(EXAMPLES_SCRIPTS_DIR),
    ...cjsFilesIn(ADAPTERS_SCRIPTS_DIR),
  ];
}

// Any `shell:` option whose RHS is NOT the literal `false` is a
// violation. Only `shell: false` (or omission) is allowed; dynamic values can
// hide a platform-specific shell spawn. The negative lookahead lets the literal
// `false` pass while rejecting any other non-whitespace token.
const SHELL_OPT_RE = /\bshell\s*:\s*(?!false\b)\S/;
// A `.cmd`-suffixed npx literal — `'npx.cmd'` / `"npx.cmd"`. Always a
// violation: the Windows `.cmd` shim only ever needs naming when you
// spawn it directly under a shell, which every hardened form avoids.
const NPX_CMD_LITERAL_RE = /(['"])npx\.cmd\1/;
// A bare `'npx'` / `"npx"` literal (no `.cmd`). Only a violation when the
// file does NOT use the resolveTrustedExe + cross-spawn posture — that
// posture resolves the name to a trusted absolute path outside the
// workspace before any shell-free spawn (test-mcp-conformance.cjs).
const NPX_BARE_LITERAL_RE = /(['"])npx\1/;
const TRUSTED_EXE_RE = /resolveTrustedExe/;
const CROSS_SPAWN_RE = /cross-spawn|crossSpawn/;
// The bare-'npx' exemption is limited to the one launcher whose whole spawn
// path uses trusted resolution and cross-spawn.
const TRUSTED_RESOLUTION_FILES = new Set(['test-mcp-conformance.cjs']);

for (const file of gateScriptFiles()) {
  const base = path.basename(file);
  const code = stripComments(fs.readFileSync(file, 'utf8'));
  // Both the audited filename and the trusted-resolution calls are required.
  const usesTrustedResolution =
    TRUSTED_RESOLUTION_FILES.has(base) &&
    TRUSTED_EXE_RE.test(code) &&
    CROSS_SPAWN_RE.test(code);

  test(`${base}: no shell:true / shell:isWin spawn (rf2-wn4o1 / rf2-33vvc)`, () => {
    assert.doesNotMatch(
      code,
      SHELL_OPT_RE,
      `${base} uses shell:true/shell:isWin — spawn shell-free via ` +
        `process.execPath + a resolved JS entry-point instead (see ` +
        `dev-testbed.cjs / serve-and-run-xray-feature-gate.cjs).`,
    );
  });

  test(`${base}: no 'npx.cmd' spawn executable (rf2-wn4o1)`, () => {
    assert.doesNotMatch(
      code,
      NPX_CMD_LITERAL_RE,
      `${base} names 'npx.cmd' as a spawn executable — resolve the ` +
        `tool's JS entry-point with require.resolve(...) and spawn it ` +
        `under process.execPath (shell-free) instead.`,
    );
  });

  test(`${base}: no bare 'npx' spawn executable outside the resolveTrustedExe+cross-spawn posture (rf2-wn4o1)`, () => {
    if (usesTrustedResolution) return; // blessed: test-mcp-conformance.cjs
    assert.doesNotMatch(
      code,
      NPX_BARE_LITERAL_RE,
      `${base} names a bare 'npx' executable without the ` +
        `resolveTrustedExe + cross-spawn posture — either resolve the ` +
        `tool's JS entry-point and spawn under process.execPath, or ` +
        `route the name through resolveTrustedExe + cross-spawn.`,
    );
  });
}

// Pin resolved entry points and process.execPath for representative launchers.
test('serve-and-run-browser-tests.cjs resolves http-server + spawns under process.execPath', () => {
  const src = fs.readFileSync(
    path.join(SCRIPTS_DIR, 'serve-and-run-browser-tests.cjs'),
    'utf8',
  );
  assert.match(src, /require\.resolve\(\s*['"]http-server\/bin\/http-server['"]/);
  assert.match(src, /spawnHarnessProcess\(\s*process\.execPath/);
});

test('story-build.cjs resolves shadow-cljs runner + spawns under process.execPath', () => {
  const src = fs.readFileSync(path.join(SCRIPTS_DIR, 'story-build.cjs'), 'utf8');
  assert.match(src, /require\.resolve\(\s*['"]shadow-cljs\/cli\/runner\.js['"]/);
  assert.match(src, /spawnSync\(\s*process\.execPath/);
});

test('serve-and-run-xray-feature-gate.cjs resolves shadow-cljs runner + spawns it under process.execPath', () => {
  const src = fs.readFileSync(
    path.join(SCRIPTS_DIR, 'serve-and-run-xray-feature-gate.cjs'),
    'utf8',
  );
  assert.match(src, /require\.resolve\(\s*['"]shadow-cljs\/cli\/runner\.js['"]/);
  // The compile step spawns the resolved runner constant under node.
  assert.match(src, /spawnSync\(\s*process\.execPath,\s*args/);
});

// Browser-gate launchers resolve shadow-cljs and spawn it under the current
// Node executable.
const GATE_LAUNCHERS = [
  ['serve-and-run-adapter-smokes.cjs', ADAPTERS_SCRIPTS_DIR],
  ['serve-and-run-story-feature-load-tests.cjs', EXAMPLES_SCRIPTS_DIR],
  ['serve-and-run-story-play-scripts.cjs', EXAMPLES_SCRIPTS_DIR],
];
for (const [base, dir] of GATE_LAUNCHERS) {
  test(`${base} resolves shadow-cljs runner + spawns the compile under process.execPath (rf2-y9o5e3)`, () => {
    const src = fs.readFileSync(path.join(dir, base), 'utf8');
    assert.match(
      src,
      /require\.resolve\(\s*['"]shadow-cljs\/cli\/runner\.js['"]/,
      `${base} must resolve shadow-cljs's JS entry-point via require.resolve(...).`,
    );
    assert.match(
      src,
      /spawnSync\(\s*process\.execPath/,
      `${base} must spawn the shadow-cljs compile under process.execPath (shell-free).`,
    );
  });
}

// Every implementation http-server
// launcher only ever serves a loopback consumer (the readiness probe +
// the headless browser both hit 127.0.0.1), so each must spawn
// http-server with an explicit `-a 127.0.0.1` rather than relying on
// http-server's broad 0.0.0.0 default — otherwise the generated app
// bundle and the per-run `.rf-harness-token` endpoint are reachable on
// non-loopback interfaces during a test run. The
// regex matches the http-server bin token followed (within a short
// window) by the `'-a', '127.0.0.1'` pair. NB `[\s\S]{0,80}?` not `[^]`
// (the JS-regex `[^]`-any-char gotcha).
const LOOPBACK_BIND_RE = loopbackBindRe('HTTP_SERVER_BIN');
// These launchers compose the http-server argv inline: they publish + verify a
// per-run /.rf-harness-token via waitForOwnedHttpReady directly, rather than
// routing through startLocalHttpServer. (startLocalHttpServer now performs that
// same owned-readiness handshake by default for ITS callers — rf2-3fc89f.14 —
// but these three don't call it.) Each serves its bundle on loopback only
// (readiness probe + headless browser both hit 127.0.0.1), so a future edit
// dropping the explicit `-a 127.0.0.1` would silently re-expose the bundle on
// 0.0.0.0 and trip no test. startLocalHttpServer callers delegate this policy
// to the shared owner and are covered by the caller-use guard below.
const IMPL_LOOPBACK_LAUNCHERS = [
  ['serve-and-run-browser-tests.cjs', SCRIPTS_DIR],
  ['check-story-static.cjs', SCRIPTS_DIR],
  ['serve-and-run-xray-feature-gate.cjs', SCRIPTS_DIR],
];
for (const [base, dir] of IMPL_LOOPBACK_LAUNCHERS) {
  test(`${base}: http-server is bound to 127.0.0.1 explicitly (rf2-utvst)`, () => {
    const src = fs.readFileSync(path.join(dir, base), 'utf8');
    assert.match(
      src,
      LOOPBACK_BIND_RE,
      `${base} must spawn http-server with '-a', '127.0.0.1' (loopback only) — ` +
        `http-server's default is 0.0.0.0, and this launcher only ever serves ` +
        `127.0.0.1 (readiness probe + headless browser). The canonical loopback ` +
        `bind now lives in startLocalHttpServer (local-browser-harness.cjs).`,
    );
  });
}

// Tokenless launchers delegate spawn, tracking, early-exit detection, and
// readiness to startLocalHttpServer. Its loopback/static/no-cache behavior is
// covered functionally in _local-browser-harness.test.cjs; Story callers are
// pinned by _story-script-runners-policy.test.cjs.
const START_LOCAL_HTTP_SERVER_CALLERS = [
  ['serve-and-run-adapter-smokes.cjs', ADAPTERS_SCRIPTS_DIR],
  ['serve-example.cjs', EXAMPLES_SCRIPTS_DIR],
];
const HARNESS_IMPORT_RE = /require\(\s*['"][^'"]*local-browser-harness\.cjs['"]\s*\)/;
const START_LOCAL_HTTP_SERVER_RE = /\bstartLocalHttpServer\s*\(/;
for (const [base, dir] of START_LOCAL_HTTP_SERVER_CALLERS) {
  test(`${base}: delegates http-server startup to the shared startLocalHttpServer owner`, () => {
    const code = stripComments(fs.readFileSync(path.join(dir, base), 'utf8'));
    assert.match(
      code,
      HARNESS_IMPORT_RE,
      `${base} must require local-browser-harness.cjs (the startLocalHttpServer owner).`,
    );
    assert.match(
      code,
      START_LOCAL_HTTP_SERVER_RE,
      `${base} must start its http-server via the shared startLocalHttpServer(...) ` +
        `owner rather than re-inlining a bespoke http-server spawn.`,
    );
  });
}

// Browser-test and Story-static launchers must consume the shared port and
// ownership-token primitives without redefining them locally.
const CONSOLIDATED_LAUNCHERS = [
  'serve-and-run-browser-tests.cjs',
  'check-story-static.cjs',
];
const SHARED_HARNESS_IMPORT_RE = /require\(\s*['"]\.\/lib\/local-browser-harness\.cjs['"]\s*\)/;
const RESOLVE_SERVE_PORT_RE = /\bresolveServePort\b/;
const WAIT_OWNED_RE = /\bwaitForOwnedHttpReady\b/;
const LOCAL_HARNESS_DEFN_RES = [
  /function\s+fetchToken\b/,
  /function\s+waitForReady\b/,
  /function\s+isPortFree\b/,
  /function\s+findFreePort\b/,
  /function\s+probe\b/,
];
for (const base of CONSOLIDATED_LAUNCHERS) {
  test(`${base}: consumes the shared local-browser-harness port/token primitives`, () => {
    const code = stripComments(
      fs.readFileSync(path.join(SCRIPTS_DIR, base), 'utf8'),
    );
    assert.match(code, SHARED_HARNESS_IMPORT_RE,
      `${base} must require ./lib/local-browser-harness.cjs.`);
    assert.match(code, RESOLVE_SERVE_PORT_RE,
      `${base} must resolve its serve port via the shared resolveServePort.`);
    assert.match(code, WAIT_OWNED_RE,
      `${base} must wait for owned readiness via the shared waitForOwnedHttpReady.`);
  });

  test(`${base}: does not redefine the consolidated harness primitives`, () => {
    const code = stripComments(
      fs.readFileSync(path.join(SCRIPTS_DIR, base), 'utf8'),
    );
    for (const re of LOCAL_HARNESS_DEFN_RES) {
      assert.doesNotMatch(code, re,
        `${base} redefines a harness primitive that now lives in ` +
          `./lib/local-browser-harness.cjs — import it instead of keeping ` +
          `a bespoke copy.`);
    }
  });
}

// Ownership-token launchers delegate nonce creation, publication, and
// concurrency-safe cleanup to publishOwnershipToken. They must not retain a
// local token lifecycle or token-only crypto import.
const TOKEN_LIFECYCLE_LAUNCHERS = [
  'serve-and-run-browser-tests.cjs',
  'check-story-static.cjs',
  'serve-and-run-reagent-slim-smoke.cjs',
  'serve-and-run-tenant-switcher-testbed.cjs',
  'serve-and-run-xray-feature-gate.cjs',
];
const PUBLISH_TOKEN_RE = /\bpublishOwnershipToken\b/;
const LOCAL_TOKEN_DEFN_RES = [
  /function\s+writeOwnershipToken\b/,
  /function\s+removeOwnershipToken\b/,
];
const CRYPTO_REQUIRE_RE = /require\(\s*['"]crypto['"]\s*\)/;
for (const base of TOKEN_LIFECYCLE_LAUNCHERS) {
  test(`${base}: delegates its ownership-token lifecycle to the shared helper`, () => {
    const code = stripComments(
      fs.readFileSync(path.join(SCRIPTS_DIR, base), 'utf8'),
    );
    assert.match(code, PUBLISH_TOKEN_RE,
      `${base} must publish its ownership token via the shared ` +
        `publishOwnershipToken(root) from ./lib/local-browser-harness.cjs.`);
    for (const re of LOCAL_TOKEN_DEFN_RES) {
      assert.doesNotMatch(code, re,
        `${base} defines a local ownership-token lifecycle fn that now lives ` +
          `in ./lib/local-browser-harness.cjs (publishOwnershipToken) — import ` +
          `it instead of keeping a bespoke copy.`);
    }
    assert.doesNotMatch(code, CRYPTO_REQUIRE_RE,
      `${base} still imports 'crypto' — the per-run token nonce now comes ` +
        `from the shared publishOwnershipToken; drop the token-only crypto ` +
        `import.`);
  });
}

// stripComments sanity: it must remove a forbidden token that appears
// only in a comment, but keep one that appears in real code. Guards the
// gate itself against false-negatives (a comment masking a live spawn)
// and false-positives (a comment tripping the policy).
test('stripComments removes comment text but preserves code', () => {
  assert.doesNotMatch(stripComments('// shell: true\nconst x = 1;'), SHELL_OPT_RE);
  assert.doesNotMatch(stripComments('/* npx.cmd */\nconst y = 2;'), NPX_CMD_LITERAL_RE);
  assert.match(stripComments('spawn({ shell: true });'), SHELL_OPT_RE);
  assert.match(stripComments("spawn('npx.cmd', args);"), NPX_CMD_LITERAL_RE);
  assert.match(stripComments("exe: 'npx'"), NPX_BARE_LITERAL_RE);
  // A quoted literal that merely CONTAINS the substring is preserved.
  assert.equal(stripComments('const s = "keep // me";'), 'const s = "keep // me";');
});

run();
