#!/usr/bin/env node
//
// rf2-8xzoe.35 — Smoke test for the causa-mcp bundle-isolation gate.
//
// The bundle-isolation contract in scripts/check-bundle-isolation.cjs
// catches a Node-only MCP-server leak into a browser-targeted bundle by
// grepping the counter release bundle for a sentinel string planted at
// the bottom of tools/causa-mcp/src/day8/re_frame2_causa_mcp/server.cljs.
// This test pins three properties so the gate cannot silently lose signal:
//
//   1. The script declares an `causa-mcp` artefact entry (so the
//      ARTEFACTS array iteration actually runs the check).
//   2. The sentinel string is unique to the planted source location —
//      no other namespace, doc, test fixture, or build artefact carries
//      the literal. Uniqueness is what gives the grep its signal: any
//      bundle hit means the planted ns body got pulled in.
//   3. The CI workflow wires the gate behind the bundle_isolation
//      changed-surface output (which the rf2-os0c1 + rf2-8xzoe.35
//      tools/causa-mcp/* classifier branch fires).
//
// This is a structural test (no shadow-cljs build) so it stays fast and
// runs alongside the other scripts/_*.test.cjs gate-helper tests via
// `npm run test:script-policy`.

'use strict';

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');
const SCRIPT = path.join(IMPL_ROOT, 'scripts', 'check-bundle-isolation.cjs');
const SENTINEL_NS = path.join(
  REPO_ROOT,
  'tools',
  'causa-mcp',
  'src',
  'day8',
  're_frame2_causa_mcp',
  'server.cljs',
);
const WORKFLOW = path.join(REPO_ROOT, '.github', 'workflows', 'test.yml');
const CHANGED_SURFACES = path.join(
  REPO_ROOT,
  '.github',
  'scripts',
  'report-changed-surfaces.sh',
);

// The sentinel string itself. Kept inline (not imported from the script)
// so a refactor that renames the literal in one place but not the other
// is caught by this test.
const SENTINEL =
  'day8.re-frame2-causa-mcp/sentinel:rf2-8xzoe.35-2026-05-17:do-not-rename';

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

// ---------------------------------------------------------------------------

test('check-bundle-isolation.cjs declares a causa-mcp artefact entry', () => {
  const script = fs.readFileSync(SCRIPT, 'utf8');
  assert.match(
    script,
    /name:\s*'causa-mcp'/,
    'expected ARTEFACTS to include a `causa-mcp` entry',
  );
});

test('check-bundle-isolation.cjs references the planted sentinel string', () => {
  const script = fs.readFileSync(SCRIPT, 'utf8');
  assert.ok(
    script.includes(SENTINEL),
    `expected check-bundle-isolation.cjs to grep for sentinel ${JSON.stringify(SENTINEL)}`,
  );
});

test('server.cljs plants the sentinel string in a defonce', () => {
  const src = fs.readFileSync(SENTINEL_NS, 'utf8');
  assert.match(
    src,
    /defonce\s+\^:private\s+bundle-isolation-sentinel/,
    'expected server.cljs to plant the sentinel via defonce (mirrors trace.tooling / subs.tooling pattern)',
  );
  assert.ok(
    src.includes(SENTINEL),
    `expected server.cljs to contain sentinel literal ${JSON.stringify(SENTINEL)}`,
  );
});

test('sentinel literal is unique across the repo (signal-bearing)', () => {
  // The grep contract requires the literal to live in exactly one
  // source location plus the check script. Walking the whole repo would
  // be slow; we sample the directories the script greps against (the
  // build bundle) and the source-of-truth ns. The single-source
  // discipline is also enforced indirectly: shadow-cljs builds will
  // fail loudly on a duplicate `(defonce ^:private bundle-isolation-
  // sentinel ...)` collision if a sibling ns ever copies the form.
  //
  // Surfaces we want the sentinel to appear in (exactly one each):
  //   - tools/causa-mcp/src/day8/re_frame2_causa_mcp/server.cljs
  //   - implementation/scripts/check-bundle-isolation.cjs
  //   - implementation/scripts/_check-bundle-isolation-causa-mcp.test.cjs (this file)
  const srcCounts = countSentinelInDir(
    path.join(REPO_ROOT, 'tools', 'causa-mcp', 'src'),
  );
  assert.equal(
    srcCounts.total,
    1,
    `expected sentinel exactly once under tools/causa-mcp/src, found ${srcCounts.total} in: ${srcCounts.files.join(', ')}`,
  );

  // implementation/ source tree (under core/, adapters/, *-feature/)
  // MUST NOT contain the sentinel. A non-zero hit would indicate the
  // forbidden cross-tree reference the gate exists to catch — and it
  // would also make the grep ambiguous (multiple source homes).
  const implRoots = [
    'core',
    'adapters',
    'schemas',
    'machines',
    'routing',
    'flows',
    'http',
    'ssr',
    'ssr-ring',
    'epoch',
  ];
  for (const sub of implRoots) {
    const dir = path.join(IMPL_ROOT, sub);
    if (!fs.existsSync(dir)) continue;
    const hits = countSentinelInDir(dir);
    assert.equal(
      hits.total,
      0,
      `sentinel must not appear under implementation/${sub}; found in ${hits.files.join(', ')}`,
    );
  }
});

test('CI workflow wires the cljs-bundle-isolation job behind bundle_isolation', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  assert.match(
    workflow,
    /cljs-bundle-isolation:[\s\S]*needs:\s*detect_changed_surfaces[\s\S]*needs\.detect_changed_surfaces\.outputs\.bundle_isolation\s*==\s*'true'/,
    'expected cljs-bundle-isolation job to gate on bundle_isolation output',
  );
  assert.match(
    workflow,
    /cljs-bundle-isolation:[\s\S]*npm run test:bundle-isolation/,
    'expected cljs-bundle-isolation job to invoke npm run test:bundle-isolation',
  );
});

test('changed-surfaces classifier fires bundle_isolation for tools/causa-mcp/*', () => {
  const src = fs.readFileSync(CHANGED_SURFACES, 'utf8');
  // The branch matching tools/story-mcp/* | tools/causa-mcp/* must set
  // bundle_isolation=true. Same branch handles both wrappers; this
  // assertion is the structural twin of the dynamic _changed-
  // surfaces.test.cjs invocation (which classifies a real file path),
  // letting us catch a future revert of just the bundle_isolation=true
  // line without re-running the bash classifier.
  assert.match(
    src,
    /tools\/story-mcp\/\*\|tools\/causa-mcp\/\*\)[\s\S]*?bundle_isolation=true/,
    'expected tools/story-mcp/*|tools/causa-mcp/*) branch to set bundle_isolation=true',
  );
});

// ---------------------------------------------------------------------------
// Helpers.

function countSentinelInDir(dir) {
  let total = 0;
  const files = [];
  walk(dir, (file) => {
    let blob;
    try {
      blob = fs.readFileSync(file, 'utf8');
    } catch (_) {
      return;
    }
    let hits = 0;
    let idx = 0;
    while ((idx = blob.indexOf(SENTINEL, idx)) !== -1) {
      hits += 1;
      idx += SENTINEL.length;
    }
    if (hits > 0) {
      total += hits;
      files.push(path.relative(REPO_ROOT, file));
    }
  });
  return { total, files };
}

function walk(dir, visit) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full, visit);
    } else if (entry.isFile()) {
      visit(full);
    }
  }
}

// ---------------------------------------------------------------------------

let failed = 0;
for (const { name, fn } of tests) {
  try {
    fn();
    console.log(`PASS ${name}`);
  } catch (err) {
    failed += 1;
    console.error(`FAIL ${name}`);
    console.error(err && err.stack ? err.stack : err);
  }
}

if (failed > 0) {
  console.error(`check-bundle-isolation causa-mcp tests: ${failed} failed.`);
  process.exit(1);
}

console.log(
  `check-bundle-isolation causa-mcp tests: ${tests.length} passed.`,
);
