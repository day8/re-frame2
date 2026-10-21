#!/usr/bin/env node

'use strict';

/*
 * Source policy for every launcher in implementation/scripts, examples/scripts and
 * adapters/scripts that spawns a child process: shell-free spawns only. On Windows
 * `shell: true` with a bare exe name and a repo-controlled cwd resolves a
 * workspace-local `.cmd` ahead of PATH (command hijack), so neither `shell:` nor a
 * bare `npx` / `npx.cmd` executable is allowed. The hardened forms spawn a tool's
 * resolved JS entry point under process.execPath, or (test-mcp-conformance.cjs
 * alone) resolve the name through resolveTrustedExe and spawn it with cross-spawn.
 * The listed http-serving launchers start their server through the shared
 * startLocalHttpServer, which binds loopback and proves it owns the server it
 * probes. Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
const {
  stripComments,
  createPolicyTestSuite,
} = require('./_policy-test-util.cjs');

const SCRIPTS_DIR = __dirname;
const EXAMPLES_SCRIPTS_DIR = path.resolve(
  __dirname,
  '..',
  '..',
  'examples',
  'scripts',
);
const ADAPTERS_SCRIPTS_DIR = path.resolve(__dirname, '..', 'adapters', 'scripts');

const { test, run } = createPolicyTestSuite('script-spawn-policy');

// Launchers only: the test suites are excluded.
function cjsFilesIn(dir) {
  return fs
    .readdirSync(dir)
    .filter((f) => f.endsWith('.cjs') && !f.endsWith('.test.cjs'))
    .map((f) => path.join(dir, f));
}

function gateScriptFiles() {
  return [
    ...cjsFilesIn(SCRIPTS_DIR),
    ...cjsFilesIn(EXAMPLES_SCRIPTS_DIR),
    ...cjsFilesIn(ADAPTERS_SCRIPTS_DIR),
  ];
}

// Any `shell:` value but the literal `false`: a dynamic value can hide a platform shell.
const SHELL_OPT_RE = /\bshell\s*:\s*(?!false\b)\S/;
const NPX_CMD_LITERAL_RE = /(['"])npx\.cmd\1/;
const NPX_BARE_LITERAL_RE = /(['"])npx\1/;
const TRUSTED_EXE_RE = /resolveTrustedExe/;
const CROSS_SPAWN_RE = /cross-spawn|crossSpawn/;
// The bare-'npx' exemption: the one launcher whose whole spawn path is trusted.
const TRUSTED_RESOLUTION_FILES = new Set(['test-mcp-conformance.cjs']);

for (const file of gateScriptFiles()) {
  const base = path.basename(file);
  const code = stripComments(fs.readFileSync(file, 'utf8'));
  const usesTrustedResolution =
    TRUSTED_RESOLUTION_FILES.has(base) &&
    TRUSTED_EXE_RE.test(code) &&
    CROSS_SPAWN_RE.test(code);

  test(`${base}: spawns shell-free, with no npx.cmd or bare npx executable (rf2-wn4o1 / rf2-33vvc)`, () => {
    assert.doesNotMatch(code, SHELL_OPT_RE, `${base} spawns with shell:true/shell:isWin`);
    assert.doesNotMatch(code, NPX_CMD_LITERAL_RE, `${base} names 'npx.cmd' as a spawn executable`);
    if (!usesTrustedResolution) {
      assert.doesNotMatch(
        code,
        NPX_BARE_LITERAL_RE,
        `${base} names a bare 'npx' executable outside the resolveTrustedExe + cross-spawn posture`,
      );
    }
  });
}

// These launchers start http-server through the shared owner, whose loopback
// bind, ownership token and owned readiness are covered functionally in
// _local-browser-harness.test.cjs.
const START_LOCAL_HTTP_SERVER_CALLERS = [
  ['serve-and-run-adapter-smokes.cjs', ADAPTERS_SCRIPTS_DIR],
  ['serve-example.cjs', EXAMPLES_SCRIPTS_DIR],
  ['serve-and-run-browser-tests.cjs', SCRIPTS_DIR],
  ['serve-and-run-tenant-switcher-testbed.cjs', SCRIPTS_DIR],
  ['serve-and-run-xray-feature-gate.cjs', SCRIPTS_DIR],
  ['check-story-static.cjs', SCRIPTS_DIR],
];
const START_LOCAL_HTTP_SERVER_RE = /\bstartLocalHttpServer\s*\(/;
for (const [base, dir] of START_LOCAL_HTTP_SERVER_CALLERS) {
  test(`${base}: delegates http-server startup to the shared startLocalHttpServer owner`, () => {
    const code = stripComments(fs.readFileSync(path.join(dir, base), 'utf8'));
    assert.match(
      code,
      START_LOCAL_HTTP_SERVER_RE,
      `${base} must start its http-server via the shared startLocalHttpServer(...) owner`,
    );
  });
}

// The gate's teeth: each policy regex matches a real violation, and a string
// containing `//` is code, not a comment that could mask one.
test('the policy regexes catch real violations through stripComments', () => {
  assert.match(stripComments('spawn({ shell: true });'), SHELL_OPT_RE);
  assert.match(stripComments("spawn('npx.cmd', args);"), NPX_CMD_LITERAL_RE);
  assert.match(stripComments("exe: 'npx'"), NPX_BARE_LITERAL_RE);
  assert.equal(stripComments('const s = "keep // me";'), 'const s = "keep // me";');
});

run();
