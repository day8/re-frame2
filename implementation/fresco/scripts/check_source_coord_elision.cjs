#!/usr/bin/env node
/*
 * THE SOURCE-COORDINATE SENTINEL for implementation/fresco/.
 *
 * `defview` and `defhost` bake an absolute on-disk file path into every
 * declaration in a dev build, so that a refusal can name the line the author
 * wrote. Spec SN 3.6 buys that with a production erasure: `:advanced` +
 * `goog.DEBUG=false` must carry no source-coordinate strings at all.
 *
 * The companion CLJS suite
 * (`re-frame.fresco.error-source-coord-elision-prod-test`) proves the
 * coordinate is not REACHABLE in that build — the ledger is empty, refusals
 * carry no `:source`. It cannot prove the stronger property, because a string
 * Closure kept but nothing reads is invisible from inside the page. That is
 * what this scan is for: the sentinel is the FILE NAME of the namespace that
 * carries the sentinel declarations, which is exactly the string the macro
 * would have baked into their coordinates, and this gate requires it to be
 * absent from the release bundle.
 *
 * WHY THE SENTINEL CANNOT PASS BY ACCIDENT. Two positive controls run first:
 *
 *   1. The sentinel file exists, so a renamed or deleted declaration source
 *      fails here rather than reporting a green absence for a file nobody
 *      compiles any more.
 *   2. The bundle contains the sentinel VIEW NAME. That name is passed
 *      to `mint-view!` unconditionally — it is the `:render` measure id and the
 *      React DevTools label — so its presence proves the scan is reading a
 *      bundle that really compiled the declarations whose coordinates it is
 *      then requiring to be absent. Without it, an empty or wrong bundle would
 *      pass the absence check trivially.
 *
 * Removing the `when debug-enabled?` gate from either macro's coordinate
 * emission makes this gate red.
 *
 * AN UNREFERENCED VIEW LEAVES NOTHING. The same bundle is also required to
 * lack the name of `dropped-row`, a `defview` in the same namespace that
 * nothing references, paired with control 2: two views differing by
 * reachability alone. A release `mint-view!` is a pure allocation, so
 * Closure drops a view nothing reaches (fresco/spec/authoring-contract.md
 * §The boundary ABI). A write in the mint that Closure cannot prove local
 * keeps every view in every bundle, and makes this row red.
 *
 * `check_production_erasure.cjs` owns the general, multi-sentinel
 * production-erasure proof; this is the one source-coordinate sentinel.
 *
 * THE SENTINEL IS NOT THE SUITE'S OWN FILE. `cljs.test` stamps `:file` into
 * the report map of every `deftest` and every `is`, so a sentinel view
 * declared inside the elision suite would find its own file name in the
 * bundle and go red on a build whose erasure is correct. The declarations
 * live in `re-frame.fresco.coord-sentinel-source`, which carries no
 * `deftest`, so nothing but a surviving coordinate can name it.
 */

'use strict';

const fs = require('fs');
const path = require('path');

const HERE = path.dirname(path.resolve(__filename));
const PACKAGE_ROOT = path.dirname(HERE);
const IMPL_ROOT = path.dirname(PACKAGE_ROOT);

const SUITE = path.join(PACKAGE_ROOT, 'test', 're_frame', 'fresco',
                        'coord_sentinel_source.cljs');
const BUNDLE = path.join(IMPL_ROOT, 'out', 'browser-test-prod-elision', 'js', 'test.js');

// The macro bakes an ABSOLUTE path, so the basename is the tail every platform
// agrees on — `C:/…/coord_sentinel_source.cljs` on Windows and
// `/home/…/coord_sentinel_source.cljs` on CI both contain it.
const SENTINEL = 'coord_sentinel_source.cljs';

// Unconditional, and therefore the proof that the bundle is the right one.
const POSITIVE_CONTROL = 're-frame.fresco.coord-sentinel-source/sentinel-row';

// The unreferenced view, and the declaration that must still exist for its
// absence to mean anything.
const DROPPED = 're-frame.fresco.coord-sentinel-source/dropped-row';
const DROPPED_DECLARATION = '(rf.fresco/defview dropped-row';

function fail(message) {
  process.stderr.write(`fresco source-coord elision: FAIL\n${message}\n`);
  process.exit(1);
}

if (!fs.existsSync(SUITE)) {
  fail(`missing sentinel source: ${path.relative(IMPL_ROOT, SUITE)}\n` +
       'The sentinel IS that file name; a scan for a file nobody compiles is ' +
       'a green that means nothing.');
}

if (!fs.readFileSync(SUITE, 'utf8').includes(DROPPED_DECLARATION)) {
  fail(`missing unreferenced declaration: ${DROPPED_DECLARATION} in ` +
       `${path.relative(IMPL_ROOT, SUITE)}\n` +
       'Its absence from the bundle means nothing for a view nobody declares.');
}

if (!fs.existsSync(BUNDLE)) {
  fail(`missing advanced release bundle: ${path.relative(IMPL_ROOT, BUNDLE)}\n` +
       'Run `npm run test:browser-prod-elision`, which compiles it first.');
}

const bundle = fs.readFileSync(BUNDLE, 'utf8');

if (!bundle.includes(POSITIVE_CONTROL)) {
  fail('positive control absent: the bundle does not carry the sentinel ' +
       `view name ${POSITIVE_CONTROL}.\n` +
       'Either the declarations did not compile into this build, or the ' +
       'view name stopped being emitted — in both cases the absence check ' +
       'below would ' +
       'pass for the wrong reason.');
}

if (bundle.includes(SENTINEL)) {
  fail(`the source-coordinate sentinel survived the advanced bundle: ${SENTINEL}\n` +
       'A `defview` / `defhost` coordinate reached production. The capture is ' +
       'emitted inside `(when re-frame.interop/debug-enabled? …)`; check that ' +
       'gate in re_frame/fresco.cljc.');
}

if (bundle.includes(DROPPED)) {
  fail(`an unreferenced defview survived the advanced bundle: ${DROPPED}\n` +
       'Something in a release `mint-view!` is no longer a write Closure can ' +
       'prove local, so every view ships whether referenced or not. Every ' +
       'own property has to be written straight onto the binding of the ' +
       '`fn` literal; see `mint-view!` in re_frame/fresco/impl/collector.cljs.');
}

process.stdout.write(
  'OK: no defview/defhost source coordinate, and no unreferenced defview, ' +
  'in the advanced bundle (positive control present).\n');
