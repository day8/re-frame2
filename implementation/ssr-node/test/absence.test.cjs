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
//   1. NO LOADER CAN REACH IT. In the source trees that build or configure
//      client artefacts, no module specifier, no ns `:require`, no
//      classpath entry and no package-manifest link names this package —
//      and its refusal-code namespace appears nowhere at all.
//   2. IT IS ON NO BUILD'S SOURCE PATH. `implementation/shadow-cljs.edn`
//      names no build reaching it and the top-level `implementation/
//      deps.edn` has no entry for it, so it is in no module graph and
//      there is no bundle it could be in. This is the strong one: absence
//      from the graph is not a property anyone has to maintain by care.
//   3. IT ADDS NO DEPENDENCY. Every `require` in `src/` names a `node:`
//      builtin or a sibling file here, and the one whose specifier is
//      COMPUTED takes it from the caller, so the package contributes
//      nothing to `implementation/package.json` and nothing to any
//      consumer's dependency closure.
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
//
// AND THE PLANT MUST NOT BE ARRANGED SO THE BUG CANNOT REACH IT. That is
// one level deeper than "the check has a control", and it is the failure
// this file actually shipped. The ns control planted the forbidden
// namespace as the FIRST AND ONLY libspec of a `:require`, while the
// scanner read only the first form after the key — so every realistic ns
// form, two libspecs with the second forbidden, passed. The control could
// not see that, because a single-libspec plant exercises exactly the path
// that worked. Reading 1 was green about a class it did not cover and the
// proof of coverage was itself the blind spot (rf2-6ovv, from the
// merged-PR audit of #8049). So the plants below are realistic files, and
// where a position can vary the control SWEEPS it instead of picking one.

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
const PATH_REFERENCE = /(?<![\w:-])ssr-node(?![\w-])/g;
const CODE_NAMESPACE = /(?<![\w-]):rf\.ssr-node\//g;
const REFERENCES = [PATH_REFERENCE, CODE_NAMESPACE];

/**
 * THERE IS STILL NO ALLOWANCE LIST. THE READING MOVED INSTEAD — rf2-6ovv.
 *
 * ## What this block used to say, and why it could not survive contact
 *
 * Reading 1 was once an absolute zero over raw file text: nothing outside
 * this package MENTIONS it, full stop. An earlier draft had added a
 * `test:ssr-node` script to `implementation/package.json` and carried a
 * pinned one-string exception for it. The script was dropped for an
 * unrelated and better reason — it arms eleven expensive CI lanes that the
 * package's own files arm none of — and the exception went with it, because
 * an allowance mechanism with nothing in it is the first entry of an
 * allowance list. This block then ruled that if the npm script were ever
 * wired up, the answer was one PINNED EXACT STRING deleted from that file
 * before the remainder was scanned — never a broadened pattern and never a
 * skipped file.
 *
 * That ruling anticipated the right event and mis-sized it. The CI lane
 * (rf2-n8vp, PR #8042) arrived armed by this package's own surface, exactly
 * as wanted, and it necessarily writes the package's path into FOUR files:
 * `implementation/package.json`, `.github/workflows/test.yml`,
 * `.github/scripts/report-changed-surfaces.sh` and
 * `implementation/scripts/_changed-surfaces.test.cjs`. Not one line but 23
 * of them, most of which are the prose that explains WHY the lane has its
 * own output. Pinning 23 exact lines would red this suite on every comment
 * reflow in four files nobody here owns — a gate people learn to route
 * around, which is the failure this block was written to prevent.
 *
 * ## What moved
 *
 * The absolute zero was a PROXY, and the collision showed which part of it
 * was load-bearing. What the claim needs is that no client can LOAD this
 * package — that no resolver, compiler or classpath can be led here. What
 * the old scan asserted was that no file may NAME it, which is a much
 * larger statement, and the extra territory is prose, shell `case` arms, CI
 * job names and an npm script that spawns a separate `node` process. None
 * of those is a way into a module graph.
 *
 * So Reading 1 now tests the needles at LOADER POSITIONS: the static
 * specifier of a `require` / `import`, the body of a ClojureScript ns
 * `:require`, the classpath and coordinate keys of an EDN build config, and
 * the linking fields of a package manifest. A format that cannot resolve a
 * module — YAML, shell, Markdown — offers no loader position and therefore
 * cannot host a hit.
 *
 * ## Why this is not the widened pattern the old ruling forbade
 *
 * Because it is the same move this file had ALREADY made, one paragraph
 * down, and for the same reason. `DOC_EXT` excludes Markdown on the ground
 * that "a Markdown file cannot be required, compiled, or put on a source
 * path" — a statement about what the FORMAT can do, never about which paths
 * are forgiven. `.yml` and `.sh` cannot be required, compiled or put on a
 * source path either. The old reading had carried the category error for
 * every format except the one that forced the issue; this finishes the
 * move rather than starting a new one.
 *
 * The prohibitions it was guarding are both intact. There is no allowance
 * list: no file, path or string is named as forgiven, and the four wiring
 * files are read exactly as any other file of their format. And the needles
 * were not widened by a character — `PATH_REFERENCE` and `CODE_NAMESPACE`
 * are unchanged, and the rows below plant a fault at every loader position
 * this file knows about and require each one to be found.
 *
 * ## What did NOT move
 *
 * `CODE_NAMESPACE` keeps the absolute zero, over raw text, everywhere. A
 * path is a token that legitimately appears in prose and in commands; the
 * refusal-code namespace is a token that only code producing or consuming
 * this service's refusals has any use for, so a client that names it has
 * been changed by this package's existence — which is precisely the claim
 * under test. Nothing inert has ever spelled it: at the time of writing it
 * appears in three files inside this package and zero outside.
 *
 * Readings 2 and 3 are untouched and remain the strong net.
 */

const SKIP_EXT = new Set([
  '.png', '.jpg', '.jpeg', '.gif', '.ico', '.webp', '.woff', '.woff2', '.ttf',
  '.eot', '.zip', '.gz', '.pdf', '.jar', '.class', '.wasm',
]);

/**
 * DOCUMENTATION IS OUT OF SCOPE — the code finally matching the stated
 * scope above, rather than an exception being carved out under pressure.
 *
 * A Markdown file cannot be required, compiled, or put on a source path,
 * so it is not a way this package could reach a client artefact. Naming
 * the package in prose is what prose is for.
 *
 * The argument is not merely that it is harmless. `implementation/
 * README.md` is REQUIRED to name this directory: `scripts/
 * check_readme_inventories.py` (rf2-198k3) reds when a directory appears
 * under `implementation/` without a line in that README's layout map, and
 * it did exactly that when this package landed. A scan that then reds on
 * the line the other gate demands is not a strict scan, it is two repo
 * invariants pulling against each other — and the one that has to give is
 * the one whose own header already said documentation was not its
 * subject.
 *
 * The exclusion is by EXTENSION and not by path, so it cannot quietly
 * cover a source file; the control row below puts the same text in a
 * `.cjs` and requires it to be found.
 */
const DOC_EXT = new Set(['.md', '.markdown']);

/** Tracked files under `roots`, relative to `cwd`. */
function trackedFiles(cwd, roots) {
  const out = execFileSync('git', ['ls-files', '-z', '--', ...roots], {
    cwd,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  return out.split('\0').filter(Boolean);
}

// ---------------------------------------------------------------------------
// Loader positions — the places a resolver, compiler or classpath turns a
// spelling into a module. Everything Reading 1 asserts, it asserts here.
// ---------------------------------------------------------------------------

const JS_EXT = new Set(['.js', '.cjs', '.mjs', '.jsx', '.ts', '.tsx', '.mts', '.cts']);
const CLJ_EXT = new Set(['.clj', '.cljs', '.cljc']);

/** A static module specifier: `require('x')`, `import('x')`, `from 'x'`, `import 'x'`. */
const SPECIFIER = /(?:\b(?:require|import)\s*\(\s*|\bfrom\s+|\bimport\s+)(['"])((?:[^'"\\]|\\.)*)\1/g;

/**
 * A `require(` / `import(` whose argument is NOT a string literal. The
 * specifier is then computed, so it cannot be read off the call — and
 * `require(SERVICE)` two lines under `const SERVICE = '../ssr-node/src/
 * service.cjs'` is a module-graph edge that a specifier scan alone would
 * walk straight past. Such a file FAILS CLOSED: every string literal in it
 * is treated as a candidate specifier.
 */
const DYNAMIC_SPECIFIER = /\b(?:require|import)\s*\(\s*(?!['"])/;
const STRING_LITERAL = /(['"`])((?:[^'"`\\\n]|\\.)*)\1/g;

/**
 * ns forms that put a namespace on the compiler's load path. Each opens a
 * CLAUSE whose body is any number of sibling forms — `(:require [a] [b] [c])`
 * — which is why these are read with `clauseFormsAfterKeys` and the EDN keys
 * below with `valuesAfterKeys`.
 */
const CLJ_LOAD_KEYS = [':require', ':require-macros', ':import', ':use', ':load'];

/** EDN keys whose value is a source path or a dependency coordinate. */
const EDN_LOAD_KEYS = [
  ':source-paths', ':paths', ':extra-paths', ':replace-paths',
  ':deps', ':extra-deps', ':replace-deps', ':local/root', ':dependencies',
];

/**
 * The `package.json` fields a resolver reads. `scripts` is deliberately not
 * among them and is not an omission: a script is a COMMAND LINE, and the
 * process it spawns has its own module graph rooted wherever it is rooted.
 * `npm run test:ssr-node` no more links this package into a client than
 * typing the same command into a terminal does.
 */
const MANIFEST_LINK_FIELDS = [
  'dependencies', 'devDependencies', 'peerDependencies', 'optionalDependencies',
  'bundleDependencies', 'bundledDependencies', 'workspaces', 'imports',
  'exports', 'main', 'module', 'browser', 'files', 'types', 'typings', 'bin',
];

const CLOSERS = { '[': ']', '{': '}', '(': ')' };

/** The one balanced form (or quoted string, or bare token) starting at `i`. */
function balancedFormAt(text, i) {
  while (i < text.length && /\s/.test(text[i])) i += 1;
  if (i >= text.length) return '';
  if (text[i] === '"') {
    for (let j = i + 1; j < text.length; j += 1) {
      if (text[j] === '\\') j += 1;
      else if (text[j] === '"') return text.slice(i, j + 1);
    }
    return text.slice(i);
  }
  if (CLOSERS[text[i]]) {
    let depth = 0;
    let inString = false;
    for (let j = i; j < text.length; j += 1) {
      const c = text[j];
      if (inString) {
        if (c === '\\') j += 1;
        else if (c === '"') inString = false;
      } else if (c === '"') inString = true;
      else if (CLOSERS[c]) depth += 1;
      else if (c === ']' || c === '}' || c === ')') {
        depth -= 1;
        if (depth === 0) return text.slice(i, j + 1);
      }
    }
    return text.slice(i);
  }
  const bare = /^[^\s()[\]{},]+/.exec(text.slice(i));
  return bare ? bare[0] : '';
}

/** Each standalone occurrence of each key, as `[{at, from}]`. */
function keyPositions(text, keys) {
  const out = [];
  for (const key of keys) {
    let from = 0;
    for (;;) {
      const at = text.indexOf(key, from);
      if (at === -1) break;
      from = at + key.length;
      // `:paths` must not fire on `:source-paths`, nor `:deps` on `:extra-deps`.
      if (at > 0 && /[\w:./-]/.test(text[at - 1])) continue;
      if (/[\w:./-]/.test(text[from] ?? '')) continue;
      out.push({ at, from });
    }
  }
  return out;
}

/**
 * The ONE value form after each key — the right shape for an EDN MAP ENTRY,
 * where `:paths [...]` and `:deps {...}` each take a single collection and
 * every element of it is already inside that one form.
 */
function valuesAfterKeys(text, keys) {
  return keyPositions(text, keys).map(({ at, from }) => ({
    text: balancedFormAt(text, from),
    at,
  }));
}

/**
 * The sibling forms from `i` up to the closer of the ENCLOSING form — an ns
 * clause body, not one value. Still deliberately naive: it is not a Clojure
 * reader, only a walk that knows balanced delimiters, strings and line
 * comments, and a shape it cannot parse leaves extra text inside a form
 * rather than dropping any.
 */
function formsInClause(text, i) {
  const out = [];
  while (i < text.length) {
    const c = text[i];
    if (/[\s,]/.test(c)) {
      i += 1;
    } else if (c === ';') {
      const nl = text.indexOf('\n', i); // a line comment holds no form
      if (nl === -1) break;
      i = nl + 1;
    } else if (c === ')' || c === ']' || c === '}') {
      break; // the clause closed
    } else {
      const form = balancedFormAt(text, i);
      if (!form) break;
      out.push({ text: form, at: i });
      i += form.length;
    }
  }
  return out;
}

/**
 * EVERY form in the clause body each key opens — the right shape for an ns
 * CLAUSE, where `(:require [a :as a] [b :as b])` takes as many libspecs as it
 * likes and they are SIBLINGS rather than nested.
 *
 * READING ONLY THE FIRST FORM WAS A FAIL-OPEN, AND ITS CONTROL WAS BUILT SO
 * IT COULD NOT SEE THAT — rf2-6ovv, from the merged-PR audit of #8049.
 * `valuesAfterKeys` on `(:require [app.first :as first] [rf.ssr-node/service
 * :as svc])` yields `[app.first :as first]` and stops, so an ordinary
 * two-line ns form hid the reference completely. The planted fault put the
 * forbidden namespace FIRST AND ONLY — the single position that worked — so
 * the row stayed green over a shape it never reached. The sweep below plants
 * at every position of every clause key instead.
 */
function clauseFormsAfterKeys(text, keys) {
  return keyPositions(text, keys).flatMap(({ from }) => formsInClause(text, from));
}

/**
 * Every loader position in one file, as `[{text, at}]` where `at` is an
 * offset into `text` good enough to name a line. A format with no way to
 * resolve a module contributes none — which is the whole of the narrowing,
 * and is the `DOC_EXT` argument applied to `.yml` and `.sh` as well.
 */
function loaderPositions(rel, text) {
  const ext = path.extname(rel).toLowerCase();
  const out = [];
  if (JS_EXT.has(ext)) {
    for (const m of text.matchAll(SPECIFIER)) out.push({ text: m[2], at: m.index });
    if (DYNAMIC_SPECIFIER.test(text)) {
      for (const m of text.matchAll(STRING_LITERAL)) out.push({ text: m[2], at: m.index });
    }
  }
  if (CLJ_EXT.has(ext)) out.push(...clauseFormsAfterKeys(text, CLJ_LOAD_KEYS));
  if (ext === '.edn') out.push(...valuesAfterKeys(text, EDN_LOAD_KEYS));
  if (path.basename(rel).toLowerCase() === 'package.json') {
    let manifest = null;
    try {
      manifest = JSON.parse(text);
    } catch {
      out.push({ text, at: 0 }); // unparseable manifest: fail closed
    }
    for (const field of manifest === null ? [] : MANIFEST_LINK_FIELDS) {
      if (manifest[field] === undefined) continue;
      const at = text.indexOf(`"${field}"`);
      out.push({ text: JSON.stringify(manifest[field]), at: at === -1 ? 0 : at });
    }
  }
  return out;
}

/** Read a tracked file, or `null` if this checkout has no such file. */
function readTracked(cwd, rel) {
  const abs = path.join(cwd, rel);
  let stat;
  try {
    stat = fs.statSync(abs);
  } catch {
    return null; // a tracked file not present in this checkout
  }
  if (!stat.isFile() || stat.size > 2 * 1024 * 1024) return null;
  return fs.readFileSync(abs, 'utf8');
}

/**
 * Files under `files` whose LOADER POSITIONS mention any of `needles`,
 * excluding anything inside `excludePrefix`. Returns `[{file, needle, line}]`.
 */
function scanLoaderPositions(cwd, files, needles, excludePrefix) {
  const hits = [];
  for (const rel of files) {
    if (excludePrefix && rel.startsWith(excludePrefix)) continue;
    const ext = path.extname(rel).toLowerCase();
    if (SKIP_EXT.has(ext) || DOC_EXT.has(ext)) continue;
    const text = readTracked(cwd, rel);
    if (text === null) continue;
    for (const position of loaderPositions(rel, text)) {
      for (const needle of needles) {
        needle.lastIndex = 0;
        if (!needle.test(position.text)) continue;
        hits.push({
          file: rel,
          needle: String(needle),
          line: text.slice(0, position.at).split('\n').length,
        });
      }
    }
  }
  return hits;
}

/**
 * Files under `files` mentioning any of `needles` ANYWHERE in their raw
 * text — the older, broader shape, kept for `CODE_NAMESPACE`.
 */
function scanForReferences(cwd, files, needles, excludePrefix) {
  const hits = [];
  for (const rel of files) {
    if (excludePrefix && rel.startsWith(excludePrefix)) continue;
    const ext = path.extname(rel).toLowerCase();
    if (SKIP_EXT.has(ext) || DOC_EXT.has(ext)) continue;
    const text = readTracked(cwd, rel);
    if (text === null) continue;
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

/**
 * Requires whose specifier is COMPUTED — `require(x)`, not `require('x')` —
 * returning the argument text.
 *
 * `foreignRequires` cannot see one, and `src/` HAS one: `worker.cjs` loads
 * the application bundle it was pointed at, which is the entire design.
 * So Reading 3 was asserting "every require here is a builtin or a sibling"
 * over a set that excluded its only counter-example, and the row would have
 * stayed green if that line had computed a package name instead. Same shape
 * as the ns fail-open this file was reopened for (rf2-6ovv): a check green
 * about a case it could not reach. Reading 1 had already drawn this
 * distinction for itself — see `DYNAMIC_SPECIFIER` — and Reading 3 had not.
 */
function computedRequires(files) {
  const hits = [];
  for (const abs of files) {
    const text = fs.readFileSync(abs, 'utf8');
    for (const m of text.matchAll(/\brequire\(\s*(?!['"])([^)]*)\)/g)) {
      hits.push({ file: path.basename(abs), arg: m[1].trim() });
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
// 1. No loader can reach it
// ---------------------------------------------------------------------------

test('no loader in a client-building tree can reach this package', () => {
  const files = trackedFiles(REPO_ROOT, SCANNED);
  assert.ok(files.length > 500, `only ${files.length} tracked files scanned — the scope looks wrong`);
  const hits = scanLoaderPositions(REPO_ROOT, files, REFERENCES, 'implementation/ssr-node/');
  assert.deepStrictEqual(
    hits,
    [],
    `something outside the package can load it:\n${hits
      .map((h) => `  ${h.file}:${h.line} (${h.needle})`)
      .join('\n')}`,
  );
});

test('the refusal-code namespace appears nowhere outside the package', () => {
  // The absolute zero that survives, and the reason the narrowing above is
  // safe rather than merely smaller — see the long note on REFERENCES.
  const files = trackedFiles(REPO_ROOT, SCANNED);
  const hits = scanForReferences(REPO_ROOT, files, [CODE_NAMESPACE], 'implementation/ssr-node/');
  assert.deepStrictEqual(
    hits,
    [],
    `something outside the package names our refusal codes:\n${hits
      .map((h) => `  ${h.file}:${h.line}`)
      .join('\n')}`,
  );
});

test('the layout map DOES name the package, as the README ratchet requires', () => {
  // The counterpart to excluding documentation: the exclusion is only
  // legitimate because the naming is an obligation somewhere else. If this
  // row ever went red, `check_readme_inventories.py` would be red too.
  const readme = fs.readFileSync(path.join(REPO_ROOT, 'implementation', 'README.md'), 'utf8');
  const needle = PATH_REFERENCE;
  needle.lastIndex = 0;
  assert.strictEqual(needle.test(readme), true, 'the layout map must carry an ssr-node entry');
});

/**
 * ONE PLANTED FAULT PER LOADER POSITION. If a shape below stops being
 * found, Reading 1 has a blind spot in exactly that shape — which is the
 * only way a narrowed reading can fail quietly.
 *
 * EVERY PLANT HERE IS A REALISTIC FILE, and that is a correctness property
 * rather than a matter of taste. `app/core.cljs` used to carry the forbidden
 * namespace as the ns form's FIRST AND ONLY libspec, and a scanner that read
 * only the first form passed it (rf2-6ovv). A minimal plant does not merely
 * under-test: it can be the one arrangement the bug does not reach.
 */
const PLANTED_LOADER_FAULTS = {
  'app/boot.cjs': "require('../../implementation/ssr-node/src/service.cjs');\n",
  'app/entry.mjs': "import svc from '../implementation/ssr-node/src/service.cjs';\n",
  'app/lazy.cjs': "const P = 'implementation/ssr-node/src/service.cjs';\nrequire(P);\n",
  'app/re_export.mjs': "export { render } from '../ssr-node/src/service.cjs';\n",
  'app/core.cljs':
    '(ns app.core\n  (:require [reagent.core :as r]\n'
    + '            [re-frame.core :refer [dispatch subscribe]]\n'
    + '            [rf.ssr-node/service :as svc]))\n',
  'build/shadow-cljs.edn': '{:builds {:app {:target :browser\n :source-paths ["ssr-node/src"]}}}\n',
  'build/deps.edn': '{:deps {day8/ssr-node {:mvn/version "0.1.0"}}}\n',
  'build/local.edn': '{:aliases {:x {:extra-deps {a/b {:local/root "../ssr-node"}}}}}\n',
  'build/paths.edn': '{:paths ["src" "../ssr-node/src"]}\n',
  'pkg/package.json': '{"name": "x", "dependencies": {"svc": "file:../ssr-node"}}\n',
};

test('CONTROL — every loader position this file knows about finds its planted fault', () => {
  for (const [rel, body] of Object.entries(PLANTED_LOADER_FAULTS)) {
    const dir = scratch({ [rel]: body, 'inert/README.md': body });
    try {
      const hits = scanLoaderPositions(dir, [rel, 'inert/README.md'], REFERENCES, null);
      assert.ok(hits.length > 0, `the planted ${rel} fault was NOT found — a blind spot`);
      for (const hit of hits) {
        assert.strictEqual(hit.file, rel, `${rel}: documentation must never be a loader position`);
      }
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  }
});

/**
 * ORDINARY COMPANY FOR THE FORBIDDEN ENTRY, one set per ns clause key. The
 * fault is swept through every position among them, so no single arrangement
 * can be the one the scanner happens to reach.
 */
const NS_CLAUSE_NEIGHBOURS = {
  ':require': [
    '[reagent.core :as r]',
    '[re-frame.core :refer [dispatch subscribe]]',
    '[clojure.string :as str]',
  ],
  ':require-macros': ['[app.macros :as m]', '[cljs.core.async.macros :refer [go]]'],
  ':import': ['(java.util Date UUID)', '(java.io File)'],
  ':use': ['[clojure.set :only [union]]', '[clojure.walk :only [keywordize-keys]]'],
  ':load': ['"app/first"', '"app/second"'],
};

/** The forbidden entry, spelled the way each clause key spells one. */
const NS_CLAUSE_FAULTS = {
  ':require': '[rf.ssr-node/service :as svc]',
  ':require-macros': '[rf.ssr-node/macros :refer [with-service]]',
  ':import': '(rf.ssr-node Service)',
  ':use': '[rf.ssr-node/service :only [render]]',
  ':load': '"../ssr-node/src/service"',
};

test('CONTROL — an ns clause is scanned WHOLE, and not just its first form', () => {
  // THE ROW rf2-6ovv EXISTS FOR, AND THE SHAPE THE PROJECT SHOULD FEAR MOST.
  // Reading 1 read the ONE form after `:require`, so
  // `(:require [app.first :as first] [rf.ssr-node/service :as svc])` — an
  // unremarkable two-line ns form — yielded `[app.first :as first]` and
  // passed. The gate was green about a class it did not cover.
  //
  // What made it survive a merged-PR review is that the CONTROL could not
  // fail: the planted fault sat FIRST AND ALONE, which is precisely the
  // position the broken scan handled. So this row does not plant one
  // arrangement. It sweeps the fault through EVERY position of EVERY clause
  // key the file claims, and a scanner that reads one form fails it at
  // position 2 of the very first key.
  for (const [key, neighbours] of Object.entries(NS_CLAUSE_NEIGHBOURS)) {
    const fault = NS_CLAUSE_FAULTS[key];
    for (let i = 0; i <= neighbours.length; i += 1) {
      const entries = [...neighbours.slice(0, i), fault, ...neighbours.slice(i)];
      const indent = ' '.repeat(key.length + 4);
      const body = `(ns app.core\n  (${key} ${entries.join(`\n${indent}`)}))\n`;
      const dir = scratch({ 'app/core.cljs': body });
      try {
        const hits = scanLoaderPositions(dir, ['app/core.cljs'], REFERENCES, null);
        assert.ok(
          hits.length > 0,
          `${key}: the fault at position ${i + 1} of ${entries.length} was NOT found:\n${body}`,
        );
      } finally {
        fs.rmSync(dir, { recursive: true, force: true });
      }
    }
  }
});

test('CONTROL — the whole-clause walk stops at the clause, not at the file', () => {
  // The counterpart the sweep above cannot give: reading MORE than one form
  // is only right if it stops reading at the closing paren. A walk that ran
  // on would drag the whole file into the `:require` position and red on any
  // mention anywhere — the absolute zero this reading was narrowed away from,
  // reintroduced by accident.
  const dir = scratch({
    'app/core.cljs':
      '(ns app.core\n  (:require [app.first :as first])) ; not (ssr-node) either\n\n'
      + '(def note "see implementation/ssr-node/src/service.cjs for the protocol")\n'
      + '(defn render [] (str "ssr-node"))\n',
  });
  try {
    assert.deepStrictEqual(
      scanLoaderPositions(dir, ['app/core.cljs'], REFERENCES, null),
      [],
      'text after the clause is not in the clause',
    );
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('CONTROL — a format that cannot resolve a module hosts no loader position', () => {
  // The `DOC_EXT` argument, generalised: the SAME text that reds in a
  // `.cjs` is inert in Markdown, YAML and shell, because none of the three
  // has a `require`. This is the narrowing, stated as a control.
  const body = "require('implementation/ssr-node/src/service.cjs')\n";
  const files = ['notes.md', 'pipeline.yml', 'run.sh', 'boot.cjs'];
  const dir = scratch(Object.fromEntries(files.map((f) => [f, body])));
  try {
    const hits = scanLoaderPositions(dir, files, REFERENCES, null);
    assert.strictEqual(hits.length, 1, `exactly one of the ${files.length} must be found`);
    assert.strictEqual(hits[0].file, 'boot.cjs', 'and it must be the one a loader reads');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

/**
 * THE SHAPES A CI LANE WRITES (rf2-n8vp, PR #8042), copied by shape rather
 * than referenced by path: an npm script, a workflow job name and run step,
 * a shell `case` arm classifying a changed path, and a gate test asserting
 * on that mapping. All four name this package. None of the four is a way to
 * load it.
 */
const CI_WIRING_SHAPES = {
  'implementation/package.json':
    '{\n  "name": "re-frame2",\n  "scripts": {\n'
    + '    "test:ssr-node": "node ssr-node/test/run.cjs"\n  }\n}\n',
  '.github/workflows/test.yml':
    'jobs:\n  node-ssr-node:\n'
    + '    name: Node implementation/ssr-node (bounded SSR service suite)\n'
    + '    steps:\n      - name: Run the ssr-node suite\n'
    + '        run: node implementation/ssr-node/test/run.cjs\n',
  '.github/scripts/report-changed-surfaces.sh':
    '# the ssr-node package\'s own lane — see implementation/ssr-node/\ncase "$file" in\n'
    + '  implementation/ssr-node/*)\n    ssr_node=true ;;\nesac\n',
  'implementation/scripts/_changed-surfaces.test.cjs':
    "const { execFileSync } = require('node:child_process');\n"
    + "// the ssr-node lane arms on its own tree — implementation/ssr-node/\n"
    + "const SSR_NODE = {\n  dir: 'implementation/ssr-node',\n"
    + "  runner: 'implementation/ssr-node/test/run.cjs',\n"
    + "  src: 'implementation/ssr-node/src/service.cjs',\n};\n"
    + 'module.exports = { SSR_NODE, execFileSync };\n',
};

test('CONTROL — a CI lane naming the package is inert, and IS NOT inert once it links', () => {
  // BOTH DIRECTIONS IN ONE ROW, because the reconciliation is only correct
  // if both hold. A reading that stopped failing would be worse than the
  // collision it was narrowed to resolve.
  const files = Object.keys(CI_WIRING_SHAPES);

  const inert = scratch(CI_WIRING_SHAPES);
  try {
    assert.deepStrictEqual(
      scanLoaderPositions(inert, files, REFERENCES, null),
      [],
      'the four CI wiring shapes must not be loader positions',
    );
  } finally {
    fs.rmSync(inert, { recursive: true, force: true });
  }

  // The same four files, each additionally given a real edge of the kind
  // its own format supports. Every one of the four must now be found.
  const linked = scratch({
    ...CI_WIRING_SHAPES,
    'implementation/package.json':
      '{\n  "name": "re-frame2",\n  "scripts": {\n'
      + '    "test:ssr-node": "node ssr-node/test/run.cjs"\n  },\n'
      + '  "dependencies": {\n    "ssr-node": "file:./ssr-node"\n  }\n}\n',
    // A workflow cannot resolve a module, so the edge a CI tree can really
    // grow is an EDN one beside it — the shape Reading 2 pins for two named
    // files and this row pins for every other build config in the trees.
    '.github/build/deps.edn': '{:paths ["../../implementation/ssr-node/src"]}\n',
    '.github/scripts/report-changed-surfaces.sh': CI_WIRING_SHAPES['.github/scripts/report-changed-surfaces.sh'],
    'implementation/scripts/_changed-surfaces.test.cjs':
      CI_WIRING_SHAPES['implementation/scripts/_changed-surfaces.test.cjs']
      + "require('../ssr-node/src/service.cjs');\n",
    // Multi-libspec on purpose: a single-libspec ns is the one arrangement
    // the pre-rf2-6ovv scanner handled, so pinning it here would have made
    // this row green for the wrong reason too.
    'implementation/scripts/boot.cljs':
      '(ns boot\n  (:require [clojure.string :as str]\n            [rf.ssr-node/service]))\n',
  });
  try {
    const linkedFiles = [...files, '.github/build/deps.edn', 'implementation/scripts/boot.cljs'];
    const hits = scanLoaderPositions(linked, linkedFiles, REFERENCES, null);
    const seen = new Set(hits.map((h) => h.file));
    for (const rel of [
      'implementation/package.json',
      '.github/build/deps.edn',
      'implementation/scripts/_changed-surfaces.test.cjs',
      'implementation/scripts/boot.cljs',
    ]) {
      assert.ok(seen.has(rel), `${rel} grew a real loader edge and Reading 1 missed it`);
    }
  } finally {
    fs.rmSync(linked, { recursive: true, force: true });
  }
});

test('CONTROL — the exclusion really is what keeps the package itself quiet', () => {
  // The package's own files are full of the spellings above. If the
  // exclusion prefix were wrong, the real scan would be red rather than
  // green — but a scan pointed at a tree with nothing in it would be green
  // too, so this row shows the package DOES match when it is not excluded.
  // It runs on the raw-text reading because that is the one still asserting
  // an absolute zero, and so the one an exclusion bug would silently pass.
  const files = trackedFiles(REPO_ROOT, ['implementation/ssr-node']);
  assert.ok(files.length > 5, 'the package should have tracked files by now');
  const unexcluded = scanForReferences(REPO_ROOT, files, REFERENCES, null);
  assert.ok(unexcluded.length > 0, 'the needles must match inside the package');
  const excluded = scanForReferences(REPO_ROOT, files, REFERENCES, 'implementation/ssr-node/');
  assert.deepStrictEqual(excluded, [], 'and the exclusion must silence exactly those');
});

test('CONTROL — the refusal-code needle finds a refusal code, and only that', () => {
  // THE SECOND NEEDLE HAD NO CONTROL AT ALL, and the audit that found the ns
  // fail-open (rf2-6ovv) is the reason to say so here. This file's header
  // promises every reading a row that plants the exact fault it must see, but
  // `CODE_NAMESPACE`'s absolute zero had none: the row above runs both
  // needles together and `assert.ok(unexcluded.length > 0)` is satisfied by
  // `PATH_REFERENCE` alone, so nothing ever required `CODE_NAMESPACE` to
  // match anything. A typo in it would have read as a clean scan.
  //
  // The near-miss is the point of the pairing. `[rf.ssr-node/service]` is a
  // PATH_REFERENCE hit with no leading colon, so it proves the two needles
  // are genuinely different rather than one an alias of the other.
  const dir = scratch({
    'app/handler.cljs':
      '(ns app.handler)\n(defn refused? [r] (= (:code r) :rf.ssr-node/render-threw))\n',
    'app/near_miss.cljs':
      '(ns app.near-miss\n  (:require [rf.ssr-node/service :as svc]))\n'
      + '(def other :rf.ssr-nodes/render-threw)\n',
  });
  try {
    const files = ['app/handler.cljs', 'app/near_miss.cljs'];
    assert.deepStrictEqual(
      scanForReferences(dir, files, [CODE_NAMESPACE], null).map((h) => h.file),
      ['app/handler.cljs'],
      'the refusal-code needle must find a planted code and nothing else',
    );
    assert.strictEqual(
      scanForReferences(dir, ['app/near_miss.cljs'], [PATH_REFERENCE], null).length,
      1,
      'and the near-miss must be a PATH_REFERENCE hit, or it proves nothing',
    );
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
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

test('every require in src/ is a builtin, a sibling, or the caller\'s own path', () => {
  const files = srcFiles();
  assert.ok(files.length >= 6, `only ${files.length} source files found — the scan looks wrong`);
  assert.deepStrictEqual(
    foreignRequires(files),
    [],
    'a third-party require would put this package into a dependency closure',
  );
  // And the requires the line above cannot read. The property is not that
  // there are none — `worker.cjs` needs one — but that THIS PACKAGE NEVER
  // NAMES A MODULE IT COMPUTES: every computed specifier comes from
  // `workerData`, so it is the caller's path, and nothing it resolves to can
  // reach a manifest we ship.
  const computed = computedRequires(files);
  assert.ok(computed.length > 0, 'no computed require found at all — the scan has gone blind');
  for (const hit of computed) {
    assert.match(
      hit.arg,
      /^workerData\./,
      `${hit.file}: require(${hit.arg}) computes a specifier from something other than the caller`,
    );
  }
});

test('CONTROL — a third-party require is caught, spelled out or computed', () => {
  const dir = scratch({
    'bad.cjs': "const React = require('react');\n",
    'sneaky.cjs': "const NAME = 'react';\nconst R = require(NAME);\n",
  });
  try {
    const hits = foreignRequires([path.join(dir, 'bad.cjs')]);
    assert.strictEqual(hits.length, 1);
    assert.strictEqual(hits[0].spec, 'react');
    // The half `foreignRequires` is blind to, and the reason the row above
    // needs a second scan: this file declares its own module name and hides
    // it behind a binding.
    assert.deepStrictEqual(foreignRequires([path.join(dir, 'sneaky.cjs')]), []);
    const computed = computedRequires([path.join(dir, 'sneaky.cjs')]);
    assert.deepStrictEqual(computed, [{ file: 'sneaky.cjs', arg: 'NAME' }]);
    assert.doesNotMatch(computed[0].arg, /^workerData\./, 'and it is not a caller-supplied path');
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
