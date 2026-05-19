#!/usr/bin/env node
/**
 * Unit tests for bin/probe-mcp-path.cjs.
 *
 * Per the test-layering README in this directory: the probe is a
 * pure Node-side artefact (no CLJS source of truth), so its tests
 * live as `.cjs` siblings of the existing `stdio-roundtrip.js` /
 * `live-nrepl.js` Node integration suites.
 *
 * Each test writes a synthetic fixture file under a temp dir, points
 * the probe at it via `opts.configPath`, and asserts the returned
 * `{status, message, stale}` shape. No real `~/.claude.json` is ever
 * touched.
 *
 * Run with: node test/probe-mcp-path-test.cjs
 *
 * Exit code 0 on all-pass, 1 on any failure.
 */

'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');

const probeModule = require('../bin/probe-mcp-path.cjs');
const { probe, scanConfig, suggestedReplacement } = probeModule;

let passed = 0;
let failed = 0;
const failures = [];

function assert(cond, label) {
  if (cond) {
    passed += 1;
  } else {
    failed += 1;
    failures.push(label);
  }
}

function deepEqual(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

// Create a one-off temp dir for a fixture; caller removes it after.
function mkTempDir() {
  const base = fs.mkdtempSync(
    path.join(os.tmpdir(), 'probe-mcp-path-test-'),
  );
  return base;
}

function writeFixture(dir, name, payload) {
  const fp = path.join(dir, name);
  fs.writeFileSync(fp, typeof payload === 'string' ? payload : JSON.stringify(payload, null, 2));
  return fp;
}

function cleanup(dir) {
  try {
    fs.rmSync(dir, { recursive: true, force: true });
  } catch (_e) {
    /* best-effort */
  }
}

// ------------------------------------------------------------------
// Test 1 — stale global mcpServers entry: args carries legacy path
// ------------------------------------------------------------------
(function testStaleGlobalArgs() {
  const dir = mkTempDir();
  try {
    const fp = writeFixture(dir, '.claude.json', {
      mcpServers: {
        're-frame-pair2': {
          command: 'node',
          args: [
            'C:/Users/miket/code/re-frame2/tools/pair2-mcp/out/server.js',
          ],
          cwd: 'C:/Users/miket/code/re-frame2/implementation',
          env: { SHADOW_CLJS_NREPL_PORT: '58376' },
        },
      },
    });
    const result = probe({ configPath: fp });
    assert(result.status === 'stale', 'stale-global-args: status is stale');
    assert(result.stale.length === 1, 'stale-global-args: one stale entry');
    assert(
      result.stale[0].scope === 'global',
      'stale-global-args: scope is global',
    );
    assert(
      result.stale[0].server === 're-frame-pair2',
      'stale-global-args: server name is re-frame-pair2',
    );
    assert(
      result.stale[0].findings.length === 1,
      'stale-global-args: exactly one finding (the args entry)',
    );
    assert(
      result.stale[0].findings[0].kind === 'arg',
      'stale-global-args: finding kind is arg',
    );
    assert(
      result.message.includes('tools/re-frame2-pair-mcp/'),
      'stale-global-args: message suggests current path',
    );
    assert(
      result.message.includes('Stale references:'),
      'stale-global-args: message includes header',
    );
  } finally {
    cleanup(dir);
  }
})();

// ------------------------------------------------------------------
// Test 2 — clean global mcpServers entry: current path, no drift
// ------------------------------------------------------------------
(function testCleanGlobal() {
  const dir = mkTempDir();
  try {
    const fp = writeFixture(dir, '.claude.json', {
      mcpServers: {
        're-frame2-pair': {
          command: 'node',
          args: [
            'C:/Users/miket/code/re-frame2/tools/re-frame2-pair-mcp/out/server.js',
          ],
          cwd: 'C:/Users/miket/code/re-frame2/implementation',
        },
      },
    });
    const result = probe({ configPath: fp });
    assert(result.status === 'clean', 'clean-global: status is clean');
    assert(result.stale.length === 0, 'clean-global: no stale entries');
    assert(result.message === '', 'clean-global: empty message');
  } finally {
    cleanup(dir);
  }
})();

// ------------------------------------------------------------------
// Test 3 — absent ~/.claude.json: silent success
// ------------------------------------------------------------------
(function testAbsent() {
  const dir = mkTempDir();
  try {
    const fp = path.join(dir, '.claude.json'); // never written
    const result = probe({ configPath: fp });
    assert(result.status === 'absent', 'absent: status is absent');
    assert(result.stale.length === 0, 'absent: no stale entries');
    assert(result.message === '', 'absent: empty message');
  } finally {
    cleanup(dir);
  }
})();

// ------------------------------------------------------------------
// Test 4 — malformed JSON: non-blocking note, status reflects shape
// ------------------------------------------------------------------
(function testMalformed() {
  const dir = mkTempDir();
  try {
    const fp = writeFixture(dir, '.claude.json', '{not valid json,');
    const result = probe({ configPath: fp });
    assert(result.status === 'malformed', 'malformed: status is malformed');
    assert(result.stale.length === 0, 'malformed: no stale entries');
    assert(
      result.message.includes('not valid JSON'),
      'malformed: message describes the parse error',
    );
  } finally {
    cleanup(dir);
  }
})();

// ------------------------------------------------------------------
// Test 5 — stale per-project mcpServers entry
// ------------------------------------------------------------------
(function testStaleProjectScope() {
  const dir = mkTempDir();
  try {
    const fp = writeFixture(dir, '.claude.json', {
      projects: {
        'C:/Users/miket/code/re-frame2': {
          mcpServers: {
            'pair-old': {
              command:
                'C:/Users/miket/code/re-frame2/tools/pair2-mcp/out/server.js',
              args: [],
            },
          },
        },
        'C:/Users/miket/code/some-other-project': {
          mcpServers: {
            playwright: {
              command: 'npx',
              args: ['-y', '@playwright/mcp@latest'],
            },
          },
        },
      },
    });
    const result = probe({ configPath: fp });
    assert(
      result.status === 'stale',
      'stale-project: status is stale',
    );
    assert(
      result.stale.length === 1,
      'stale-project: only the one stale entry surfaces (playwright is clean)',
    );
    assert(
      result.stale[0].scope === 'project:C:/Users/miket/code/re-frame2',
      'stale-project: scope identifies the project path',
    );
    assert(
      result.stale[0].findings[0].kind === 'command',
      'stale-project: finding kind is command',
    );
  } finally {
    cleanup(dir);
  }
})();

// ------------------------------------------------------------------
// Test 6 — Windows backslash separators are normalised before check
// ------------------------------------------------------------------
(function testBackslashSeparators() {
  const dir = mkTempDir();
  try {
    const fp = writeFixture(dir, '.claude.json', {
      mcpServers: {
        'pair-win': {
          command: 'node',
          args: [
            'C:\\Users\\miket\\code\\re-frame2\\tools\\pair2-mcp\\out\\server.js',
          ],
        },
      },
    });
    const result = probe({ configPath: fp });
    assert(
      result.status === 'stale',
      'backslash-sep: status is stale (backslash form normalised)',
    );
    assert(
      result.stale.length === 1,
      'backslash-sep: one stale entry detected',
    );
  } finally {
    cleanup(dir);
  }
})();

// ------------------------------------------------------------------
// Test 7 — multiple stale references in one entry surface as separate
//          findings (e.g. command + args both point at the old path)
// ------------------------------------------------------------------
(function testMultipleFindings() {
  const dir = mkTempDir();
  try {
    const fp = writeFixture(dir, '.claude.json', {
      mcpServers: {
        'pair-double': {
          command: '/home/u/code/re-frame2/tools/pair2-mcp/out/server.js',
          args: [
            '--config',
            '/home/u/code/re-frame2/tools/pair2-mcp/conf.json',
          ],
          cwd: '/home/u/code/re-frame2/tools/pair2-mcp',
        },
      },
    });
    const result = probe({ configPath: fp });
    assert(
      result.stale.length === 1,
      'multi-finding: one server entry',
    );
    const findings = result.stale[0].findings;
    assert(
      findings.length === 3,
      'multi-finding: three distinct findings (command + arg + cwd)',
    );
    const kinds = findings.map((f) => f.kind).sort();
    assert(
      deepEqual(kinds, ['arg', 'command', 'cwd']),
      'multi-finding: kinds are command/arg/cwd',
    );
  } finally {
    cleanup(dir);
  }
})();

// ------------------------------------------------------------------
// Test 8 — config with empty mcpServers + no projects: clean
// ------------------------------------------------------------------
(function testEmptyConfig() {
  const dir = mkTempDir();
  try {
    const fp = writeFixture(dir, '.claude.json', { mcpServers: {} });
    const result = probe({ configPath: fp });
    assert(result.status === 'clean', 'empty-config: status is clean');
  } finally {
    cleanup(dir);
  }
})();

// ------------------------------------------------------------------
// Test 9 — env-value stale reference is detected
// ------------------------------------------------------------------
(function testStaleEnvValue() {
  const dir = mkTempDir();
  try {
    const fp = writeFixture(dir, '.claude.json', {
      mcpServers: {
        'pair-envy': {
          command: 'node',
          args: ['some/clean/path.js'],
          env: {
            MCP_HOME: '/u/code/re-frame2/tools/pair2-mcp/',
          },
        },
      },
    });
    const result = probe({ configPath: fp });
    assert(
      result.status === 'stale',
      'stale-env: status is stale',
    );
    assert(
      result.stale[0].findings[0].kind === 'env-value',
      'stale-env: finding kind is env-value',
    );
  } finally {
    cleanup(dir);
  }
})();

// ------------------------------------------------------------------
// Test 10 — suggestedReplacement swaps the fragment correctly
// ------------------------------------------------------------------
(function testSuggestedReplacement() {
  const input =
    'C:/Users/miket/code/re-frame2/tools/pair2-mcp/out/server.js';
  const out = suggestedReplacement(input);
  assert(
    out ===
      'C:/Users/miket/code/re-frame2/tools/re-frame2-pair-mcp/out/server.js',
    'suggested-replacement: legacy fragment swapped to current',
  );
  // Backslash input should also produce a normalised forward-slash
  // suggestion (best practice — Node accepts either on Windows).
  const win = suggestedReplacement(
    'C:\\Users\\miket\\code\\re-frame2\\tools\\pair2-mcp\\out\\server.js',
  );
  assert(
    win.includes('tools/re-frame2-pair-mcp/out/server.js'),
    'suggested-replacement: backslash input normalises to forward-slash suggestion',
  );
})();

// ------------------------------------------------------------------
// Test 11 — scanConfig on null / non-object returns []
// ------------------------------------------------------------------
(function testScanConfigDefensive() {
  assert(deepEqual(scanConfig(null), []), 'scan-config: null returns []');
  assert(deepEqual(scanConfig(undefined), []), 'scan-config: undefined returns []');
  assert(deepEqual(scanConfig('not-a-config'), []), 'scan-config: string returns []');
  assert(deepEqual(scanConfig(42), []), 'scan-config: number returns []');
})();

// ------------------------------------------------------------------
// Summary
// ------------------------------------------------------------------
process.stdout.write(`\nprobe-mcp-path-test: ${passed} passed, ${failed} failed\n`);
if (failed > 0) {
  process.stdout.write('\nFailures:\n');
  for (const f of failures) {
    process.stdout.write(`  - ${f}\n`);
  }
  process.exit(1);
}
process.exit(0);
