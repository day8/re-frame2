#!/usr/bin/env node

'use strict';

/*
 * Source-policy gate: every `implementation/scripts/**` launcher that
 * spawns a child process must use the HARDENED, shell-free posture
 * (rf2-wn4o1). Two accident classes are gated here, both static (no
 * process is spawned by this suite — it reads the script sources and
 * asserts on their text):
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

const SCRIPTS_DIR = __dirname;

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

// Strip line- and block-comments so the policy assertions match only
// EXECUTABLE source, not the explanatory comments that necessarily
// quote the very strings we forbid (`shell: true`, `npx.cmd`, ...).
// Deliberately simple: this is a residue gate over our own first-party
// scripts, not a general-purpose JS parser. String literals are left
// intact so a real `shell: true` hidden in a literal would still trip.
function stripComments(src) {
  let out = '';
  let i = 0;
  const n = src.length;
  let inLine = false;
  let inBlock = false;
  let inStr = false;
  let strQuote = '';
  while (i < n) {
    const c = src[i];
    const c2 = src[i + 1];
    if (inLine) {
      if (c === '\n') { inLine = false; out += c; }
      i += 1;
      continue;
    }
    if (inBlock) {
      if (c === '*' && c2 === '/') { inBlock = false; i += 2; continue; }
      i += 1;
      continue;
    }
    if (inStr) {
      out += c;
      if (c === '\\') { out += c2 == null ? '' : c2; i += 2; continue; }
      if (c === strQuote) { inStr = false; strQuote = ''; }
      i += 1;
      continue;
    }
    if (c === '/' && c2 === '/') { inLine = true; i += 2; continue; }
    if (c === '/' && c2 === '*') { inBlock = true; i += 2; continue; }
    if (c === '"' || c === "'" || c === '`') {
      inStr = true; strQuote = c; out += c; i += 1; continue;
    }
    out += c;
    i += 1;
  }
  return out;
}

// Every `.cjs` launcher in the scripts dir (excludes the test suites and
// the lib/ helpers — the harness lib carries its own taskkill/spawn that
// is exercised by _local-browser-harness.test.cjs).
function gateScriptFiles() {
  return fs
    .readdirSync(SCRIPTS_DIR)
    .filter((f) => f.endsWith('.cjs') && !f.endsWith('.test.cjs'))
    .map((f) => path.join(SCRIPTS_DIR, f));
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

let failed = 0;
for (const { name, fn } of tests) {
  try {
    fn();
  } catch (err) {
    failed += 1;
    console.error(`FAIL ${name}`);
    console.error(err && err.stack ? err.stack : err);
  }
}

if (failed > 0) {
  console.error(`script-spawn-policy tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`script-spawn-policy tests: ${tests.length} passed.`);
