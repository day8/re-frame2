#!/usr/bin/env node
/**
 * Stage the read-only viewer page and prove the two halves fit (rf2-8m344).
 *
 * WHY THIS EXISTS.  The viewer ships as a PAGE — `public/viewer.html` plus the
 * compiled `viewer.js` beside it — and per DESIGN-RATIONALE Lock #7 the
 * CONSUMER hosts it: there is no Day8 instance, `share/encode-share-url` has no
 * default `:host`, and `release-machines-viz.yml` publishes the jar and nothing
 * else.  All of that is now true and documented.
 *
 * What was still only a claim is that the documented recipe WORKS.  The
 * `:machines-viz-viewer` shadow build was compiled by no workflow, no npm
 * script and no gate, so README.md §Building and hosting the viewer page and
 * spec/API.md §Hosting were telling consumers to run two commands nothing had
 * run since the build was declared.  "The page is buildable" was the same shape
 * of promise as the 404 host it replaced: plausible, unverified, and load-
 * bearing for anyone trying to self-host.
 *
 * This script IS those two commands' second half, plus the assertions that make
 * the pair meaningful.  It stages the checked-in HTML beside the freshly
 * compiled bundle and then checks the coupling between them — which is the part
 * that can rot silently, because nothing about renaming a namespace, dropping
 * an `^:export`, or renaming a shadow module tells you the page stopped
 * booting.
 *
 *   node tools/machines-viz/scripts/stage-viewer-page.cjs [--out <dir>]
 *   node tools/machines-viz/scripts/stage-viewer-page.cjs --self-test
 *
 * THE THREE ASSERTIONS, all DERIVED from the HTML rather than hardcoded:
 *
 *   A1  the `<script src="...">` the page loads names a file the build emitted;
 *   A2  the page's boot script calls a global path (`window.day8.…viewer.run`)
 *       that the bundle actually installs — under `:advanced` that is the
 *       `["day8","re_frame2_machines_viz","viewer","run"]` export-path literal,
 *       under a dev compile the dotted `goog.exportSymbol` spelling;
 *   A3  the staged directory afterwards contains exactly the pair a static host
 *       needs, so "serve that directory" is a complete instruction.
 *
 * The namespace is never written down here.  Both sides come out of the two
 * artefacts, so a rename that keeps them in step passes and a rename that
 * breaks the page fails — which is the only useful behaviour.
 */

'use strict';

const fs = require('fs');
const path = require('path');

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..');
const VIEWER_HTML = path.join(REPO_ROOT, 'tools', 'machines-viz', 'public', 'viewer.html');
const DEFAULT_OUT = path.join(REPO_ROOT, 'implementation', 'out', 'machines-viz-viewer');

/** The filename the page's `<script src="...">` loads. */
function scriptSrc(html) {
  const match = html.match(/<script\s+src="([^"]+)"/i);
  if (!match) throw new Error('viewer.html has no <script src="..."> — the page cannot load a bundle');
  return match[1];
}

/**
 * The global path the page's boot script calls, as segments.
 *
 * Read off the CALL rather than the guard, because the call is what has to
 * resolve: `window.a.b.c.run()` -> ['a','b','c','run'].
 */
function bootGlobalPath(html) {
  const match = html.match(/window\.((?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*)\(\)/);
  if (!match) throw new Error('viewer.html has no window.<path>() boot call — nothing invokes the bundle');
  return match[1].split('.');
}

/** Does `bundle` install the global at `segments`? */
function bundleExports(bundle, segments) {
  const advanced = '[' + segments.map((s) => JSON.stringify(s)).join(',') + ']';
  return bundle.includes(advanced) || bundle.includes(segments.join('.'));
}

function stage(outDir) {
  const failures = [];
  const html = fs.readFileSync(VIEWER_HTML, 'utf8');
  const src = scriptSrc(html);
  const bundlePath = path.join(outDir, src);

  // A1 — the build emitted the file the page asks for.
  if (!fs.existsSync(bundlePath)) {
    failures.push(
      `A1 viewer.html loads <script src="${src}"> but ${path.relative(REPO_ROOT, bundlePath)} does not exist. ` +
        'Run `npx shadow-cljs release machines-viz-viewer` from implementation/ first.'
    );
    return { failures, outDir, src, segments: [] };
  }

  // A2 — the global the page calls is the global the bundle installs.
  const segments = bootGlobalPath(html);
  const bundle = fs.readFileSync(bundlePath, 'utf8');
  if (!bundleExports(bundle, segments)) {
    failures.push(
      `A2 viewer.html boots by calling window.${segments.join('.')}(), but ${src} installs no such global. ` +
        "The page's entry and the bundle's ^:export have drifted — the page would load and do nothing."
    );
  }

  // The documented staging step: the HTML goes beside the bundle.
  fs.copyFileSync(VIEWER_HTML, path.join(outDir, path.basename(VIEWER_HTML)));

  // A3 — what a static host now has to serve.
  for (const required of [path.basename(VIEWER_HTML), src]) {
    if (!fs.existsSync(path.join(outDir, required))) {
      failures.push(`A3 ${required} is missing from the staged directory ${path.relative(REPO_ROOT, outDir)}`);
    }
  }

  return { failures, outDir, src, segments };
}

// ---------------------------------------------------------------------------

const SELF_TEST_HTML =
  '<!doctype html><html><body><main id="app"></main>\n' +
  '<script src="viewer.js"></script>\n' +
  '<script>if (window.a && window.a.b) { window.a.b.viewer.run(); }</script>\n' +
  '</body></html>\n';

function selfTest() {
  const cases = [];
  const expect = (label, ok) => cases.push([label, ok]);

  expect('reads the <script src>', scriptSrc(SELF_TEST_HTML) === 'viewer.js');
  expect(
    'reads the boot call, not the guard',
    JSON.stringify(bootGlobalPath(SELF_TEST_HTML)) === JSON.stringify(['a', 'b', 'viewer', 'run'])
  );
  expect(
    'accepts the :advanced export-path literal',
    bundleExports('var x=["a","b","viewer","run"],y=aa;', ['a', 'b', 'viewer', 'run'])
  );
  expect(
    'accepts the dotted dev spelling',
    bundleExports('goog.exportSymbol("a.b.viewer.run", f);', ['a', 'b', 'viewer', 'run'])
  );
  expect(
    'rejects a bundle that installs a DIFFERENT global (the drift this exists for)',
    !bundleExports('var x=["a","b","page","run"];', ['a', 'b', 'viewer', 'run'])
  );
  expect('a page with no <script src> is an error', (() => {
    try { scriptSrc('<html></html>'); return false; } catch { return true; }
  })());
  expect('a page that never calls the bundle is an error', (() => {
    try { bootGlobalPath('<script src="viewer.js"></script>'); return false; } catch { return true; }
  })());

  const ok = cases.every(([, passed]) => passed);
  for (const [label, passed] of cases) console.log(`  ${passed ? 'ok  ' : 'FAIL'} ${label}`);
  console.log(
    `${ok ? 'PASS' : 'FAIL'} stage-viewer-page self-test: ` +
      `${cases.filter(([, p]) => p).length}/${cases.length} cases`
  );
  return ok ? 0 : 1;
}

function main() {
  const argv = process.argv.slice(2);
  if (argv.includes('--self-test')) return selfTest();

  const outIdx = argv.indexOf('--out');
  const outDir = outIdx === -1 ? DEFAULT_OUT : path.resolve(argv[outIdx + 1]);

  if (!fs.existsSync(outDir)) {
    console.error(
      `FAIL machines-viz viewer page: ${path.relative(REPO_ROOT, outDir)} does not exist. ` +
        'Run `npx shadow-cljs release machines-viz-viewer` from implementation/ first.'
    );
    return 1;
  }

  const { failures, src, segments } = stage(outDir);
  if (failures.length) {
    console.error(`FAIL machines-viz viewer page: ${failures.length} problem(s)`);
    for (const failure of failures) console.error(`  ${failure}`);
    return 1;
  }

  const bytes = fs.statSync(path.join(outDir, src)).size;
  console.log(
    `PASS machines-viz viewer page: staged viewer.html + ${src} (${(bytes / 1024).toFixed(0)} KB) in ` +
      `${path.relative(REPO_ROOT, outDir)}; the page boots window.${segments.join('.')}(), which the bundle installs. ` +
      'Serve that directory as static files.'
  );
  return 0;
}

process.exit(main());
