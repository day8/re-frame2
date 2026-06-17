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
// Shared stripComments (EXECUTABLE-source-only matching) + loopbackBindRe
// factory + framework-free test harness (rf2-j552l2).
const {
  stripComments,
  loopbackBindRe,
  createPolicyTestSuite,
} = require('./_policy-test-util.cjs');

const SCRIPTS_DIR = __dirname;
// rf2-y9o5e3 — the executable browser-gate launchers under
// examples/scripts/ run the SAME shadow-cljs compile + http-server spawn
// posture as the implementation/scripts launchers, and `npm run
// test:examples` / `test:story-feature-load` / `test:story-play-scripts`
// drive them on Windows local runs. They were previously OUTSIDE this
// policy gate (it scanned only implementation/scripts/), so a forbidden
// npx.cmd/cmd.exe/shell posture survived there. Pull them in here.
const EXAMPLES_SCRIPTS_DIR = path.resolve(
  __dirname,
  '..',
  '..',
  'examples',
  'scripts',
);

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

// implementation/scripts/** (the original rf2-wn4o1 scope) PLUS the
// executable launchers under examples/scripts/** (rf2-y9o5e3). The
// examples/scripts dir holds the three browser-gate launchers, their
// Playwright runners, port resolvers, helpers, and static scanners; none
// may carry a shell:true/npx.cmd posture, so the whole dir is scanned.
function gateScriptFiles() {
  return [...cjsFilesIn(SCRIPTS_DIR), ...cjsFilesIn(EXAMPLES_SCRIPTS_DIR)];
}

const SHELL_OPT_RE = /\bshell\s*:\s*(true|isWin)\b/;
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

for (const file of gateScriptFiles()) {
  const base = path.basename(file);
  const code = stripComments(fs.readFileSync(file, 'utf8'));
  const usesTrustedResolution =
    TRUSTED_EXE_RE.test(code) && CROSS_SPAWN_RE.test(code);

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

// Positive guards: the three scripts the audit (rf2-wn4o1) hardened must
// keep their resolved-entry-point + process.execPath posture. These pin
// the fix so a future edit can't quietly regress to npx-under-a-shell.
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

// rf2-y9o5e3 — positive guards for the three examples/scripts browser-gate
// launchers. Each must resolve the shadow-cljs JS entry-point and spawn it
// shell-free under process.execPath (the same hardened posture as the
// implementation/scripts launchers above), so a future edit can't quietly
// regress to the npx.cmd/cmd.exe/shell posture this bead removed.
const EXAMPLES_LAUNCHERS = [
  'serve-and-run-examples-tests.cjs',
  'serve-and-run-story-feature-load-tests.cjs',
  'serve-and-run-story-play-scripts.cjs',
];
for (const base of EXAMPLES_LAUNCHERS) {
  test(`${base} resolves shadow-cljs runner + spawns the compile under process.execPath (rf2-y9o5e3)`, () => {
    const src = fs.readFileSync(path.join(EXAMPLES_SCRIPTS_DIR, base), 'utf8');
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

// Loopback-bind policy (rf2-utvst): every implementation http-server
// launcher only ever serves a loopback consumer (the readiness probe +
// the headless browser both hit 127.0.0.1), so each must spawn
// http-server with an explicit `-a 127.0.0.1` rather than relying on
// http-server's broad 0.0.0.0 default — otherwise the generated app
// bundle and the per-run `.rf-harness-token` endpoint are reachable on
// non-loopback interfaces during a test run. This mirrors the examples-
// side guard in _story-script-runners-policy.test.cjs (rf2-wf5al.2). The
// regex matches the http-server bin token followed (within a short
// window) by the `'-a', '127.0.0.1'` pair. NB `[\s\S]{0,80}?` not `[^]`
// (the JS-regex `[^]`-any-char gotcha).
const LOOPBACK_BIND_RE = loopbackBindRe('HTTP_SERVER_BIN');
const IMPL_LOOPBACK_LAUNCHERS = [
  'serve-and-run-browser-tests.cjs',
  'check-story-static.cjs',
  'serve-and-run-xray-feature-gate.cjs',
];
for (const base of IMPL_LOOPBACK_LAUNCHERS) {
  test(`${base}: http-server is bound to 127.0.0.1 explicitly (rf2-utvst)`, () => {
    const src = fs.readFileSync(path.join(SCRIPTS_DIR, base), 'utf8');
    assert.match(
      src,
      LOOPBACK_BIND_RE,
      `${base} must spawn http-server with '-a', '127.0.0.1' (loopback only) — ` +
        `http-server's default is 0.0.0.0, and this launcher only ever serves ` +
        `127.0.0.1 (readiness probe + headless browser). Match ` +
        `serve-and-run-examples-tests.cjs.`,
    );
  });
}

// rf2-vtp2er — runner-consolidation guard. The browser-test and
// story-static launchers were migrated off their bespoke copies of the
// ownership-token / port-fallback protocol onto the shared
// local-browser-harness.cjs primitives (the same API the Xray gate
// consumes). Pin that: each must (a) import resolveServePort +
// waitForOwnedHttpReady from the shared helper, and (b) NOT redefine the
// local copies that previously lived inline (`function fetchToken`,
// `function waitForReady`, `function isPortFree`, `function findFreePort`,
// `function probe`). A future edit re-introducing a bespoke copy — the
// exact three-implementations-in-one-surface drift the bead removed —
// trips here. (Scanned over stripped source so a comment mentioning the
// old names is not a false positive.)
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
  test(`${base}: consumes the shared local-browser-harness port/token primitives (rf2-vtp2er)`, () => {
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

  test(`${base}: does NOT redefine the consolidated harness primitives (rf2-vtp2er)`, () => {
    const code = stripComments(
      fs.readFileSync(path.join(SCRIPTS_DIR, base), 'utf8'),
    );
    for (const re of LOCAL_HARNESS_DEFN_RES) {
      assert.doesNotMatch(code, re,
        `${base} redefines a harness primitive that now lives in ` +
          `./lib/local-browser-harness.cjs — import it instead of keeping ` +
          `a bespoke copy (rf2-vtp2er consolidation).`);
    }
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
