'use strict';
// CLIENT v0 IS UNAFFECTED WHEN THIS SERVICE IS ABSENT — the acceptance
// clause on rf2-hic-056 that asks for a witness rather than an assertion.
//
//     node implementation/ssr-node/test/absence.test.cjs
//
// The claim is that a re-frame2 client which never starts this service is
// byte-for-byte the client it would have been had this package never
// existed. Three independent readings, in increasing strength:
//
//   1. NOTHING REFERENCES IT. No tracked file in the source trees that
//      build or configure client artefacts mentions this package.
//   2. IT IS ON NO BUILD'S SOURCE PATH. `implementation/shadow-cljs.edn`
//      names no build reaching it and the top-level `implementation/
//      deps.edn` has no entry for it, so it is in no module graph and
//      there is no bundle it could be in. This is the strong one: absence
//      from the graph is not a property anyone has to maintain by care.
//   3. IT ADDS NO DEPENDENCY. Every `require` in `src/` is a `node:`
//      builtin or a sibling file here, so the package contributes nothing
//      to `implementation/package.json` and nothing to any consumer's
//      dependency closure.
//
// ## EVERY CHECK PLANTS ITS OWN FAULT, EVERY RUN
//
// A scan that returns zero is indistinguishable from a scan that looked in
// the wrong place, so each reading below is paired with a row that plants
// the exact fault it is supposed to see and requires it to be found. Those
// rows are ordinary rows rather than a `--self-test` flag, so they cannot
// be skipped by anyone running the file the usual way.
//
// The fault is planted in a scratch directory and never in the repo: the
// scanners take their file list as an argument precisely so the control
// can hand them a different tree.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const PACKAGE_DIR = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(PACKAGE_DIR, '../..');

/**
 * The source trees that build or configure a CLIENT artefact. The claim is
 * about what can reach a browser bundle, so the scope is the trees that
 * produce or wire one — not the tracker, the design records or the docs,
 * where naming this package is exactly what those files are for.
 */
const SCANNED = ['implementation', 'examples', 'tools', 'scripts', '.github'];

/**
 * Spellings that would mean something in the repo had reached for us: the
 * package directory in any path or coordinate, and the refusal-code
 * namespace.
 *
 * THE BOUNDARIES ARE NOT DECORATION. A bare `ssr-node` substring is a
 * FALSE POSITIVE generator, and it fired on the first run: the SSR spike
 * driver's header explains at length that it deliberately did NOT mint a
 * `:hicasso-ssr-node` build id, and a substring scan reads that sentence
 * as the very coupling it is disclaiming. So the pattern refuses a
 * preceding word character, colon or hyphen, and a following word
 * character or hyphen — which still matches every form that would be a
 * real reference (`"ssr-node"` in a deps coordinate, `ssr-node/src` in a
 * source path, `../ssr-node/src/service.cjs` in a require) and none of the
 * forms that are somebody else's compound name.
 */
const REFERENCES = [/(?<![\w:-])ssr-node(?![\w-])/g, /(?<![\w-]):rf\.ssr-node\//g];

/**
 * THERE IS NO ALLOWANCE LIST, AND THAT IS A DECISION RATHER THAN AN
 * OVERSIGHT.
 *
 * The reading below is an absolute zero: nothing outside this package
 * mentions it, full stop. An earlier draft added a `test:ssr-node` script
 * to `implementation/package.json` and carried a pinned one-string
 * exception for it. The script was dropped for an unrelated and better
 * reason — it arms eleven expensive CI lanes that the package's own files
 * arm none of — and the exception went with it, because an allowance
 * mechanism with nothing in it is the first entry of an allowance list.
 *
 * If the npm script is wired up later, the answer is one PINNED EXACT
 * STRING deleted from that file before the remainder is scanned — never a
 * broadened pattern and never a skipped file. A pinned string reds on a
 * second reference and reds again if the script value drifts; a widened
 * pattern is how a scan like this stops meaning anything.
 */

const SKIP_EXT = new Set([
  '.png', '.jpg', '.jpeg', '.gif', '.ico', '.webp', '.woff', '.woff2', '.ttf',
  '.eot', '.zip', '.gz', '.pdf', '.jar', '.class', '.wasm',
]);

/** Tracked files under `roots`, relative to `cwd`. */
function trackedFiles(cwd, roots) {
  const out = execFileSync('git', ['ls-files', '-z', '--', ...roots], {
    cwd,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  return out.split('\0').filter(Boolean);
}

/**
 * Files under `files` that mention any of `needles`, excluding anything
 * inside `excludePrefix`. Returns `[{file, needle, line}]`.
 */
function scanForReferences(cwd, files, needles, excludePrefix) {
  const hits = [];
  for (const rel of files) {
    if (excludePrefix && rel.startsWith(excludePrefix)) continue;
    if (SKIP_EXT.has(path.extname(rel).toLowerCase())) continue;
    const abs = path.join(cwd, rel);
    let stat;
    try {
      stat = fs.statSync(abs);
    } catch {
      continue; // a tracked file not present in this checkout
    }
    if (!stat.isFile() || stat.size > 2 * 1024 * 1024) continue;
    const text = fs.readFileSync(abs, 'utf8');
    for (const needle of needles) {
      needle.lastIndex = 0;
      const m = needle.exec(text);
      if (m) {
        hits.push({
          file: rel,
          needle: String(needle),
          line: text.slice(0, m.index).split('\n').length,
        });
      }
    }
  }
  return hits;
}

/** Non-builtin, non-relative requires in a set of files. */
function foreignRequires(files) {
  const hits = [];
  for (const abs of files) {
    const text = fs.readFileSync(abs, 'utf8');
    for (const m of text.matchAll(/require\(\s*['"]([^'"]+)['"]\s*\)/g)) {
      const spec = m[1];
      if (spec.startsWith('node:') || spec.startsWith('./') || spec.startsWith('../')) continue;
      hits.push({ file: path.basename(abs), spec });
    }
  }
  return hits;
}

const srcFiles = () =>
  fs
    .readdirSync(path.join(PACKAGE_DIR, 'src'))
    .filter((f) => f.endsWith('.cjs'))
    .map((f) => path.join(PACKAGE_DIR, 'src', f));

/** A throwaway tree for the fault-planting rows. */
function scratch(files) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-ssr-node-absence-'));
  for (const [rel, text] of Object.entries(files)) {
    const abs = path.join(dir, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, text, 'utf8');
  }
  return dir;
}

// ---------------------------------------------------------------------------
// 1. Nothing references it
// ---------------------------------------------------------------------------

test('no client-building tree references this package', () => {
  const files = trackedFiles(REPO_ROOT, SCANNED);
  assert.ok(files.length > 500, `only ${files.length} tracked files scanned — the scope looks wrong`);
  const hits = scanForReferences(REPO_ROOT, files, REFERENCES, 'implementation/ssr-node/');
  assert.deepStrictEqual(
    hits,
    [],
    `something outside the package reaches for it:\n${hits
      .map((h) => `  ${h.file}:${h.line} (${h.needle})`)
      .join('\n')}`,
  );
});

test('CONTROL — the reference scan finds a planted reference', () => {
  const dir = scratch({
    'app/core.cljs': '(ns app.core)\n',
    'app/boot.cjs': "require('../../implementation/ssr-node/src/service.cjs');\n",
  });
  try {
    const hits = scanForReferences(dir, ['app/core.cljs', 'app/boot.cjs'], REFERENCES, null);
    assert.strictEqual(hits.length, 1, 'the planted reference must be found');
    assert.strictEqual(hits[0].file, 'app/boot.cjs');
    assert.match(hits[0].needle, /ssr-node/);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('CONTROL — the exclusion really is what keeps the package itself quiet', () => {
  // The package's own files are full of the spellings above. If the
  // exclusion prefix were wrong, the real scan would be red rather than
  // green — but a scan pointed at a tree with nothing in it would be green
  // too, so this row shows the package DOES match when it is not excluded.
  const files = trackedFiles(REPO_ROOT, ['implementation/ssr-node']);
  assert.ok(files.length > 5, 'the package should have tracked files by now');
  const unexcluded = scanForReferences(REPO_ROOT, files, REFERENCES, null);
  assert.ok(unexcluded.length > 0, 'the needles must match inside the package');
  const excluded = scanForReferences(REPO_ROOT, files, REFERENCES, 'implementation/ssr-node/');
  assert.deepStrictEqual(excluded, [], 'and the exclusion must silence exactly those');
});

// ---------------------------------------------------------------------------
// 2. On no build's source path — the strong reading
// ---------------------------------------------------------------------------

const BUILD_CONFIGS = ['implementation/shadow-cljs.edn', 'implementation/deps.edn'];

test('no shadow-cljs build and no classpath entry reaches this package', () => {
  for (const rel of BUILD_CONFIGS) {
    const text = fs.readFileSync(path.join(REPO_ROOT, rel), 'utf8');
    for (const needle of REFERENCES) {
      needle.lastIndex = 0;
      assert.strictEqual(
        needle.test(text),
        false,
        `${rel} mentions ${needle} — the package would be on a build's source path`,
      );
    }
  }
});

test('CONTROL — a doctored build config is caught', () => {
  const dir = scratch({
    'shadow-cljs.edn': '{:builds {:app {:target :browser :source-paths ["ssr-node/src"]}}}\n',
  });
  try {
    const text = fs.readFileSync(path.join(dir, 'shadow-cljs.edn'), 'utf8');
    const needle = REFERENCES[0];
    needle.lastIndex = 0;
    assert.strictEqual(needle.test(text), true, 'the check must see a planted source path');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('the package is pure JavaScript — no CLJS a build could pick up', () => {
  const walk = (dir) =>
    fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
      const abs = path.join(dir, e.name);
      return e.isDirectory() ? walk(abs) : [abs];
    });
  const suspect = walk(PACKAGE_DIR).filter((f) => /\.clj[sc]?$/.test(f));
  assert.deepStrictEqual(suspect, [], 'a .clj/.cljs/.cljc file here could be compiled into something');
});

// ---------------------------------------------------------------------------
// 3. It adds no dependency
// ---------------------------------------------------------------------------

test('every require in src/ is a node builtin or a sibling', () => {
  const files = srcFiles();
  assert.ok(files.length >= 6, `only ${files.length} source files found — the scan looks wrong`);
  assert.deepStrictEqual(
    foreignRequires(files),
    [],
    'a third-party require would put this package into a dependency closure',
  );
});

test('CONTROL — a third-party require is caught', () => {
  const dir = scratch({ 'bad.cjs': "const React = require('react');\n" });
  try {
    const hits = foreignRequires([path.join(dir, 'bad.cjs')]);
    assert.strictEqual(hits.length, 1);
    assert.strictEqual(hits[0].spec, 'react');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('the package declares no npm dependency of its own', () => {
  // No package.json here at all: the service is meant to be droppable into
  // a deployment without an install step, and a manifest would be the
  // first place a dependency would appear.
  assert.strictEqual(
    fs.existsSync(path.join(PACKAGE_DIR, 'package.json')),
    false,
    'this package deliberately carries no manifest — see the README',
  );
  const pkg = JSON.parse(
    fs.readFileSync(path.join(REPO_ROOT, 'implementation', 'package.json'), 'utf8'),
  );
  for (const dep of Object.keys(pkg.devDependencies ?? {})) {
    assert.ok(!dep.includes('ssr-node'), `${dep} should not exist`);
  }
  assert.strictEqual(pkg.dependencies, undefined, 'this package must add no runtime dependency');
});
