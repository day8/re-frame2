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
 * Every launcher that resolves the http-server package starts its server through
 * the shared startLocalHttpServer, which binds loopback and proves it owns the
 * server it probes. Discovered by `npm run test:scripts`.
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
// A launcher naming the http-server package serves with it, so the set of
// launchers that must delegate to startLocalHttpServer is derived from the tree
// and a new one is pinned the moment it lands. The helper's loopback bind,
// ownership token and owned readiness are covered functionally in
// _local-browser-harness.test.cjs.
const HTTP_SERVER_PACKAGE_RE = /(['"`])http-server(?:\/|\1)/;
const START_LOCAL_HTTP_SERVER_RE = /\bstartLocalHttpServer\s*\(/;

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

  // Both directions, so the derivation cannot go blind silently: the helper
  // needs the resolved bin, so a caller the package regex cannot see is a
  // regex that has stopped matching, not a launcher to skip.
  const namesHttpServer = HTTP_SERVER_PACKAGE_RE.test(code);
  const callsHelper = START_LOCAL_HTTP_SERVER_RE.test(code);
  if (namesHttpServer || callsHelper) {
    test(`${base}: delegates http-server startup to the shared startLocalHttpServer owner`, () => {
      assert.ok(
        callsHelper,
        `${base} names the http-server package but does not start it via the shared startLocalHttpServer(...) owner`,
      );
      assert.ok(
        namesHttpServer,
        `${base} calls startLocalHttpServer(...) but HTTP_SERVER_PACKAGE_RE cannot see how it resolves http-server; extend the regex`,
      );
    });
  }
}

// The gate's teeth: each policy regex matches a real violation, and a string
// containing `//` is code, not a comment that could mask one.
test('the policy regexes catch real violations through stripComments', () => {
  assert.match(stripComments('spawn({ shell: true });'), SHELL_OPT_RE);
  assert.match(stripComments("spawn('npx.cmd', args);"), NPX_CMD_LITERAL_RE);
  assert.match(stripComments("exe: 'npx'"), NPX_BARE_LITERAL_RE);
  assert.match(
    stripComments("const bin = require.resolve('http-server/bin/http-server', { paths });"),
    HTTP_SERVER_PACKAGE_RE,
  );
  assert.match(stripComments('await startLocalHttpServer({ cleanup });'), START_LOCAL_HTTP_SERVER_RE);
  assert.equal(stripComments('const s = "keep // me";'), 'const s = "keep // me";');
});

run();
