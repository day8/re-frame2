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
 *       that the bundle actually installs, PROVEN BY EVALUATING THE BUNDLE;
 *   A3  the staged directory afterwards contains exactly the pair a static host
 *       needs, so "serve that directory" is a complete instruction.
 *
 * The namespace is never written down here.  Both sides come out of the two
 * artefacts, so a rename that keeps them in step passes and a rename that
 * breaks the page fails — which is the only useful behaviour.
 *
 * WHY A2 EVALUATES RATHER THAN GREPS.  It used to search the bundle text for
 * the export-path literal, and a substring search cannot tell an export from a
 * coincidence: `var x=["day8","re_frame2_machines_viz","viewer","run"];`
 * installs nothing and matched, so the job could report the page's boot global
 * present in a bundle that installed no global at all.  A gate whose green is
 * unearned is worse than no gate, because it is the reason nobody looks.  So
 * A2 now runs the bundle in an isolated `vm` context holding the few globals a
 * module-init path touches, and asks the only question that matters: after
 * evaluation, is `window.<the path the page calls>` a callable function?  That
 * is not a proxy for the coupling, it IS the coupling.
 *
 * It is deliberately NOT a render smoke.  `run()` is never called — the harness
 * exists to let the bundle finish loading, not to mount the app, whose render
 * path the artefact's `*-dom-cljs-test` chart suites already cover in a real
 * browser.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

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

/**
 * The globals a browser bundle may touch while its modules initialise.
 *
 * Minimal on purpose. Everything here is present because module-scope code in
 * React / Reagent / the flow-graph libraries reads it on load; nothing here
 * pretends to be a DOM, because nothing renders — `run()` is not called.
 */
function browserlikeGlobals() {
  const noop = () => {};
  const element = () => ({
    style: {},
    classList: { add: noop, remove: noop, contains: () => false },
    childNodes: [],
    nodeType: 1,
    setAttribute: noop,
    appendChild: noop,
    removeChild: noop,
    addEventListener: noop,
    removeEventListener: noop
  });

  const globals = {
    console: { log: noop, warn: noop, error: noop, info: noop, debug: noop, group: noop, groupEnd: noop },
    setTimeout: () => 0,
    clearTimeout: noop,
    setInterval: () => 0,
    clearInterval: noop,
    queueMicrotask: noop,
    navigator: { userAgent: 'stage-viewer-page' },
    location: { href: 'http://localhost/', protocol: 'http:', host: 'localhost', hash: '', search: '' },
    document: {
      createElement: element,
      createElementNS: element,
      createTextNode: element,
      createDocumentFragment: element,
      documentElement: element(),
      head: element(),
      body: element(),
      querySelector: () => null,
      querySelectorAll: () => [],
      getElementById: () => null,
      addEventListener: noop,
      removeEventListener: noop
    }
  };

  // A browser bundle reaches its global through `window` / `self` as readily as
  // through the implicit `this`, and all three have to be the same object or an
  // export installed via one is invisible through another.
  globals.window = globals;
  globals.self = globals;
  return globals;
}

/**
 * Does evaluating `source` install a CALLABLE at `segments`?
 *
 * The bundle is run, not read. Anything short of "the page's boot call would
 * resolve to a function" is a failure, and `why` says which kind, because the
 * three kinds want different repairs: a bundle that throws is broken, a bundle
 * missing the path has drifted from the page, and a bundle whose path holds a
 * non-function has an `^:export` on something that is not the entry.
 */
function installsGlobal(source, segments, filename) {
  const sandbox = browserlikeGlobals();
  vm.createContext(sandbox);

  try {
    vm.runInContext(source, sandbox, { filename, timeout: 60000 });
  } catch (err) {
    return { ok: false, why: `evaluating it threw ${err && err.name}: ${err && err.message}` };
  }

  let found = sandbox;
  for (const segment of segments) found = found == null ? undefined : found[segment];

  if (typeof found === 'function') return { ok: true, why: 'installed and callable' };
  return {
    ok: false,
    why: `window.${segments.join('.')} is ${found === undefined ? 'undefined' : typeof found}, not a function`
  };
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
  const verdict = installsGlobal(fs.readFileSync(bundlePath, 'utf8'), segments, src);
  if (!verdict.ok) {
    failures.push(
      `A2 viewer.html boots by calling window.${segments.join('.')}(), but after evaluating ${src}, ` +
        `${verdict.why}. The page would load and do nothing.`
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

const SELF_TEST_PATH = ['a', 'b', 'viewer', 'run'];

/**
 * The export idiom `:advanced` really emits, for arbitrary segments.
 *
 * Copied in shape from the compiled viewer bundle — Closure inlines
 * `goog.exportPath_` and constant-folds the dotted name into the segment array,
 * then walks it installing each level. Parameterised here so the positive cases
 * exercise the real thing rather than a convenient stand-in.
 */
function closureExport(segments, valueExpr) {
  return (
    '(function(){\n' +
    '  var p=' + JSON.stringify(segments) + ',g=this,v=' + valueExpr + ';\n' +
    '  for(var k;p.length&&(k=p.shift());)\n' +
    '    p.length?g=g[k]&&g[k]!==Object.prototype[k]?g[k]:g[k]={}:g[k]=v;\n' +
    '}).call(this);\n'
  );
}

function selfTest() {
  const cases = [];
  const expect = (label, ok) => cases.push([label, ok]);
  const installs = (source) => installsGlobal(source, SELF_TEST_PATH, 'self-test.js').ok;

  expect('reads the <script src>', scriptSrc(SELF_TEST_HTML) === 'viewer.js');
  expect(
    'reads the boot call, not the guard',
    JSON.stringify(bootGlobalPath(SELF_TEST_HTML)) === JSON.stringify(SELF_TEST_PATH)
  );

  // Positive: the real :advanced export idiom, and the plain assignment a dev
  // build leaves behind. Both genuinely install; both must pass.
  expect('accepts the :advanced export idiom', installs(closureExport(SELF_TEST_PATH, 'function(){}')));
  expect(
    'accepts a plainly assigned global',
    installs('window.a={b:{viewer:{run:function(){}}}};')
  );

  // Negative: the false positives a substring search accepted. Each contains
  // the exact text the old check looked for and installs nothing.
  expect(
    'REJECTS a decoy array literal that installs nothing',
    !installs('var x=["a","b","viewer","run"],y=1;')
  );
  expect(
    'REJECTS a decoy dotted occurrence that installs nothing',
    !installs('var name="a.b.viewer.run";')
  );

  // Negative: the drifts this assertion exists to catch.
  expect(
    'REJECTS a bundle that installs a DIFFERENT global',
    !installs(closureExport(['a', 'b', 'page', 'run'], 'function(){}'))
  );
  expect(
    'REJECTS a path that holds a non-function — the boot call would throw',
    !installs(closureExport(SELF_TEST_PATH, '42'))
  );
  expect('REJECTS a bundle that throws while loading', !installs('throw new Error("boom");'));

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
