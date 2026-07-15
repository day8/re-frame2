#!/usr/bin/env node
/*
 * Advanced-production proof for the mounted ViewCell override carriage.
 *
 * The source assertion is the positive control: the marker still names the
 * DEBUG-only branch containing the dynamic binding.  The release-bundle
 * assertion proves Closure removed that entire branch from the exact browser
 * artifact which mounts a compiled ui/sub ViewCell in the companion test.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const SOURCE = path.join(ROOT, 'ui', 'src', 're_frame', 'ui', 'viewcell.cljs');
const REACTIVE_SOURCE = path.join(ROOT, 'ui', 'src', 're_frame', 'ui',
                                  'reactive.cljc');
const ANALYZER_SOURCE = path.join(ROOT, 'ui', 'src', 're_frame', 'ui',
                                  'compiler', 'analyze.cljc');
const BUNDLE = path.join(ROOT, 'out', 'browser-test-prod-elision', 'js', 'test.js');
const SENTINEL = 'rf-ui-mounted-override-binding expects a map';

function fail(message) {
  process.stderr.write(`ui mounted production elision: FAIL\n${message}\n`);
  process.exit(1);
}

if (!fs.existsSync(BUNDLE)) {
  fail(`missing advanced release bundle: ${path.relative(ROOT, BUNDLE)}`);
}

const source = fs.readFileSync(SOURCE, 'utf8');
const occurrences = source.split(SENTINEL).length - 1;
if (occurrences !== 1) {
  fail(`expected exactly one source sentinel, found ${occurrences}`);
}

const reactiveSource = fs.readFileSync(REACTIVE_SOURCE, 'utf8');
if (!reactiveSource.includes('disconnect-provisional?')) {
  fail('provisional-disconnect source positive-control sentinel is absent');
}

const analyzerSource = fs.readFileSync(ANALYZER_SOURCE, 'utf8');
for (const expected of ['sid1-', ':expr-path']) {
  if (!analyzerSource.includes(expected)) {
    fail(`compiler source positive-control sentinel is absent: ${expected}`);
  }
}

const bundle = fs.readFileSync(BUNDLE, 'utf8');
if (bundle.includes(SENTINEL)) {
  fail('DEBUG-only override binding branch survived in the advanced bundle');
}

if (!/sid1-[A-Za-z0-9_-]+/.test(bundle)) {
  fail('compact compiler lexical site id is absent from the advanced hot path');
}

for (const forbidden of [
  'expr-path',
  'hmr-body-revision',
  'hmr-remount-generation',
  'hmr-descriptor',
  'hmr-inner',
  // rf2-vxgfnd.165: the advanced fixture roots make-cell, disconnect!, the
  // settle seam, and reconnect. Any unconditional state member OR reconnect
  // lookup therefore carries this keyword into the bundle and fails here.
  'disconnect-provisional'
]) {
  if (bundle.includes(forbidden)) {
    fail(`development site/HMR diagnostic survived advanced output: ${forbidden}`);
  }
}

/*
 * rf2-vxgfnd.75 — the tool-tier invalidation-evidence projection.
 *
 * The elision companion test requires re-frame.ui.tool.evidence into this
 * exact bundle, so these are positive proofs of erasure (the namespace is
 * present; its debug-gated projection is not), not absence-by-omission.
 * Source positive controls first: the sentinels still name the debug-only
 * accumulation (`:dropped-exact?` — the rf2-vxgfnd.74 honest-loss keyword,
 * minted only inside debug-gated folds in reactive.cljc + the projection)
 * and the projection's rejected-install diagnostic string.
 */
const TOOL_SOURCE = path.join(ROOT, 'ui', 'src', 're_frame', 'ui', 'tool',
                              'evidence.cljc');
const toolSource = fs.readFileSync(TOOL_SOURCE, 'utf8');
for (const expected of ['dropped-exact', 'invalidation-evidence projection']) {
  if (!toolSource.includes(expected)) {
    fail(`tool-evidence source positive-control sentinel is absent: ${expected}`);
  }
}

/*
 * Compiled behavioural positive control (rf2-vxgfnd.149): the advanced test
 * roots the PRIVATE state var and asserts its value is nil before and after
 * force-release!. The marker must survive in this bundle, so a mutant which
 * merely renames/minifies an unconditional atom/reset path still fails at
 * runtime; this is deliberately stronger than another bundle substring.
 */
const TOOL_PROD_TEST = path.join(ROOT, 'ui', 'test', 're_frame', 'ui',
                                'tool_evidence_elision_prod_test.cljs');
const toolProdTest = fs.readFileSync(TOOL_PROD_TEST, 'utf8');
const REGISTRY_CONTROL = 'rf-ui-tool-evidence-registry-state-absent-v1';
for (const expected of ["@#'evidence/state*", REGISTRY_CONTROL]) {
  if (!toolProdTest.includes(expected)) {
    fail(`tool-evidence compiled state-elision control is absent: ${expected}`);
  }
}
if (!bundle.includes(REGISTRY_CONTROL)) {
  fail('tool-evidence private-state assertion was omitted from advanced bundle');
}

for (const forbidden of ['dropped-exact', 'invalidation-evidence projection']) {
  if (bundle.includes(forbidden)) {
    fail(`invalidation-evidence projection survived advanced output: ${forbidden}`);
  }
}

/*
 * rf2-vxgfnd.94.8 — REAL compiled mutation control. A second advanced build
 * flips a private goog-define which roots an unrelatedly named atom/reset path
 * inside re-frame.ui.tool.evidence. The same production test must turn red on
 * that exact minified artefact. This proves the runtime private-root oracle has
 * teeth beyond source strings; it is intentionally scoped to evidence rather
 * than pretending to be a general JavaScript heap analyser.
 */
for (const expected of ['elision-negative-control?', 'cacheline*']) {
  if (!toolSource.includes(expected)) {
    fail(`tool-evidence renamed-state mutation control is absent: ${expected}`);
  }
}

const MUTANT_ROOT = path.join(ROOT, 'out', 'browser-test-prod-elision-mutant');
const shadowRunner = require.resolve('shadow-cljs/cli/runner.js');
const configMerge = [
  '{:test-dir "out/browser-test-prod-elision-mutant"',
  ':devtools {:http-root "out/browser-test-prod-elision-mutant" :http-port 8024}',
  ':compiler-options {:closure-defines',
  '{goog.DEBUG false',
  're-frame.ui.tool.evidence/elision-negative-control? true}}}',
].join(' ');
const mutantBuild = spawnSync(
  process.execPath,
  [shadowRunner, 'release', 'browser-test-prod-elision',
   '--config-merge', configMerge],
  { cwd: ROOT, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 },
);
if (mutantBuild.status !== 0) {
  fail(`renamed-state mutant build failed to compile:\n${mutantBuild.error || ''}\n${mutantBuild.stdout || ''}\n${mutantBuild.stderr || ''}`);
}

const mutantMarker = 'rf-ui-tool-evidence-renamed-root-mutation-v1';
const mutantRun = spawnSync(
  process.execPath,
  [path.join(ROOT, 'scripts', 'serve-and-run-browser-tests.cjs'),
   '--root', MUTANT_ROOT, '--port', '8024'],
  {
    cwd: ROOT,
    encoding: 'utf8',
    maxBuffer: 8 * 1024 * 1024,
    env: { ...process.env, BROWSER_TEST_TIMEOUT_MS: '120000' },
  },
);
const mutantOutput = `${mutantRun.stdout || ''}\n${mutantRun.stderr || ''}`;
if (mutantRun.status === 0) {
  fail('renamed rooted evidence-state mutation was NOT rejected');
}
if (!mutantOutput.includes(mutantMarker)) {
  fail(`mutant failed for the wrong reason; missing runtime marker ${mutantMarker}:\n${mutantOutput}`);
}

process.stdout.write('ui mounted production elision: PASS\n');
