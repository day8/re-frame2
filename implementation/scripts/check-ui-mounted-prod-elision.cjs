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

const ROOT = path.resolve(__dirname, '..');
const SOURCE = path.join(ROOT, 'ui', 'src', 're_frame', 'ui', 'viewcell.cljs');
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
  'hmr-inner'
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

for (const forbidden of ['dropped-exact', 'invalidation-evidence projection']) {
  if (bundle.includes(forbidden)) {
    fail(`invalidation-evidence projection survived advanced output: ${forbidden}`);
  }
}

process.stdout.write('ui mounted production elision: PASS\n');
