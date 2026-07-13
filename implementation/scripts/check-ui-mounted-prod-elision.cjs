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

const bundle = fs.readFileSync(BUNDLE, 'utf8');
if (bundle.includes(SENTINEL)) {
  fail('DEBUG-only override binding branch survived in the advanced bundle');
}

process.stdout.write('ui mounted production elision: PASS\n');
