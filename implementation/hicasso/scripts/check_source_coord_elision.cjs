#!/usr/bin/env node
/*
 * THE SOURCE-COORDINATE SENTINEL for implementation/hicasso/ (rf2-hic-007).
 *
 * `defview` and `defhost` bake an absolute on-disk file path into every
 * declaration in a dev build, so that a refusal can name the line the author
 * wrote. Spec SN 3.6 buys that with a production erasure: `:advanced` +
 * `goog.DEBUG=false` must carry no source-coordinate strings at all.
 *
 * The companion CLJS suite
 * (`re-frame.hicasso.error-source-coord-elision-prod-test`) proves the
 * coordinate is not REACHABLE in that build — the ledger is empty, refusals
 * carry no `:source`. It cannot prove the stronger property, because a string
 * Closure kept but nothing reads is invisible from inside the page. That is
 * what this scan is for: the sentinel is the elision suite's own FILE NAME,
 * which is exactly the string the macro would have baked into the coordinate,
 * and this gate requires it to be absent from the release bundle.
 *
 * WHY THE SENTINEL CANNOT PASS BY ACCIDENT. Two positive controls run first:
 *
 *   1. The sentinel string is present in the SOURCE this scan is about, so a
 *      renamed or deleted suite fails here rather than reporting a green
 *      absence for a file that no longer exists.
 *   2. The bundle contains the sentinel suite's VIEW NAME. That name is passed
 *      to `mint-view!` unconditionally — it is the `:render` measure id and the
 *      React DevTools label — so its presence proves the scan is reading a
 *      bundle that really compiled the declarations whose coordinates it is
 *      then requiring to be absent. Without it, an empty or wrong bundle would
 *      pass the absence check trivially.
 *
 * Removing the `when debug-enabled?` gate from either macro's coordinate
 * emission makes this gate red. That was verified by doing it (rf2-hic-007),
 * and the verification is quoted in the PR that landed this file.
 *
 * rf2-hic-024 owns the general, multi-sentinel production-erasure proof; this
 * is the one sentinel rf2-hic-007's own acceptance clause requires.
 */

'use strict';

const fs = require('fs');
const path = require('path');

const HERE = path.dirname(path.resolve(__filename));
const PACKAGE_ROOT = path.dirname(HERE);
const IMPL_ROOT = path.dirname(PACKAGE_ROOT);

const SUITE = path.join(PACKAGE_ROOT, 'test', 're_frame', 'hicasso',
                        'error_source_coord_elision_prod_test.cljs');
const BUNDLE = path.join(IMPL_ROOT, 'out', 'browser-test-prod-elision', 'js', 'test.js');

// The macro bakes an ABSOLUTE path, so the basename is the tail every platform
// agrees on — `C:/…/error_source_coord_elision_prod_test.cljs` on Windows and
// `/home/…/error_source_coord_elision_prod_test.cljs` on CI both contain it.
const SENTINEL = 'error_source_coord_elision_prod_test.cljs';

// Unconditional, and therefore the proof that the bundle is the right one.
const POSITIVE_CONTROL =
  're-frame.hicasso.error-source-coord-elision-prod-test/prod-sentinel-row';

function fail(message) {
  process.stderr.write(`hicasso source-coord elision: FAIL\n${message}\n`);
  process.exit(1);
}

if (!fs.existsSync(SUITE)) {
  fail(`missing sentinel suite: ${path.relative(IMPL_ROOT, SUITE)}\n` +
       'The sentinel IS that file name; a scan for a file nobody compiles is ' +
       'a green that means nothing.');
}

if (!fs.existsSync(BUNDLE)) {
  fail(`missing advanced release bundle: ${path.relative(IMPL_ROOT, BUNDLE)}\n` +
       'Run `npm run test:browser-prod-elision`, which compiles it first.');
}

const bundle = fs.readFileSync(BUNDLE, 'utf8');

if (!bundle.includes(POSITIVE_CONTROL)) {
  fail('positive control absent: the bundle does not carry the sentinel ' +
       `view name ${POSITIVE_CONTROL}.\n` +
       'Either the suite did not compile into this build, or the view name ' +
       'stopped being emitted — in both cases the absence check below would ' +
       'pass for the wrong reason.');
}

if (bundle.includes(SENTINEL)) {
  fail(`the source-coordinate sentinel survived the advanced bundle: ${SENTINEL}\n` +
       'A `defview` / `defhost` coordinate reached production. The capture is ' +
       'emitted inside `(when re-frame.interop/debug-enabled? …)`; check that ' +
       'gate in re_frame/hicasso.cljc.');
}

process.stdout.write(
  'OK: no defview/defhost source coordinate in the advanced bundle ' +
  '(positive control present).\n');
