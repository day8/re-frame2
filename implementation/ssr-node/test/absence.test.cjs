'use strict';
// CLIENT v0 IS UNAFFECTED WHEN THIS SERVICE IS ABSENT, in three readings of
// increasing strength (the package README states each in full):
//
//   1. no loader in a client-building tree can reach this package, and its
//      refusal-code namespace appears nowhere at all;
//   2. no build's source path or classpath reaches it;
//   3. it adds no dependency: `src/` requires only builtins, siblings and the
//      caller's own path, and its manifest declares nothing.
//
// A scan that returns zero cannot be told from one that looked in the wrong
// place, so each reading has a CONTROL row that plants the fault it must see
// in a scratch tree. The plants are realistic files, and where a position can
// vary the control sweeps it: a plant arranged so the bug cannot reach it
// proves nothing.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const PACKAGE_DIR = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(PACKAGE_DIR, '../..');

/** The trees that build or configure a client artefact; docs and the tracker are out. */
const SCANNED = ['implementation', 'examples', 'tools', 'scripts', '.github'];

/**
 * The package path and the refusal-code namespace. The boundaries matter: a
 * bare `ssr-node` substring would hit a `:fresco-ssr-node` build id that a
 * file names only to disclaim it.
 */
const PATH_REFERENCE = /(?<![\w:-])ssr-node(?![\w-])/g;
const CODE_NAMESPACE = /(?<![\w-]):rf\.ssr-node\//g;
const REFERENCES = [PATH_REFERENCE, CODE_NAMESPACE];

/**
 * Reading 1 is taken at LOADER POSITIONS rather than over raw text, because
 * the package's own CI lane names it in YAML, shell and an npm script, none of
 * which can resolve a module. No file or string is forgiven and the needles
 * are not widened. `CODE_NAMESPACE` keeps its absolute zero over raw text:
 * only code producing or consuming these refusals has a use for it.
 */

const SKIP_EXT = new Set([
  '.png', '.jpg', '.jpeg', '.gif', '.ico', '.webp', '.woff', '.woff2', '.ttf',
  '.eot', '.zip', '.gz', '.pdf', '.jar', '.class', '.wasm',
]);

/**
 * Documentation is out of scope by EXTENSION, never by path: Markdown cannot
 * be required, compiled or put on a source path, and `implementation/README.md`
 * is required by `scripts/check_readme_inventories.py` to name this directory.
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
 * A `require(` / `import(` whose argument is not a string literal. Such a
 * file fails CLOSED: every string literal in it is a candidate specifier.
 */
const DYNAMIC_SPECIFIER = /\b(?:require|import)\s*\(\s*(?!['"])/;
const STRING_LITERAL = /(['"`])((?:[^'"`\\\n]|\\.)*)\1/g;

/** ns clause keys that put a namespace on the compiler's load path. */
const CLJ_LOAD_KEYS = [':require', ':require-macros', ':import', ':use', ':load'];

/** EDN keys whose value is a source path or a dependency coordinate. */
const EDN_LOAD_KEYS = [
  ':source-paths', ':paths', ':extra-paths', ':replace-paths',
  ':deps', ':extra-deps', ':replace-deps', ':local/root', ':dependencies',
];

/**
 * The `package.json` fields a resolver reads. Not `scripts`: a script is a
 * command line, and the process it spawns has its own module graph.
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

/** The ONE value form after each key: the shape of an EDN map entry. */
function valuesAfterKeys(text, keys) {
  return keyPositions(text, keys).map(({ at, from }) => ({
    text: balancedFormAt(text, from),
    at,
  }));
}

/**
 * The sibling forms from `i` to the closer of the enclosing form. Not a
 * reader: a walk that knows balanced delimiters, strings and line comments.
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
 * EVERY form in each clause body: `(:require [a] [b])` takes sibling
 * libspecs, and reading only the first would pass a forbidden second one.
 */
function clauseFormsAfterKeys(text, keys) {
  return keyPositions(text, keys).flatMap(({ from }) => formsInClause(text, from));
}

/**
 * Every loader position in one file, as `[{text, at}]`. A format that cannot
 * resolve a module contributes none.
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
 * text — the broader shape, used for `CODE_NAMESPACE`.
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
 * Requires whose specifier is COMPUTED, as `[{file, arg}]`. `foreignRequires`
 * cannot see one, and `worker.cjs` has one by design: it loads the bundle the
 * caller pointed it at.
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
  // The absolute zero, over raw text.
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

/**
 * One realistic plant per loader position. A minimal plant can be the one
 * arrangement a bug does not reach, so `app/core.cljs` puts the forbidden
 * namespace LAST of three libspecs.
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

/** Ordinary company for the forbidden entry, per ns clause key. */
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
  // The fault is swept through EVERY position of every clause key: a scanner
  // reading only the first form passes a fault planted first and alone, and
  // fails here at position 2 of the first key.
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

test('CONTROL — the refusal-code needle finds a refusal code, and only that', () => {
  // `CODE_NAMESPACE`'s own control. The near-miss is a PATH_REFERENCE hit with
  // no leading colon, so the two needles are shown to differ.
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

/**
 * Read at loader positions, as Reading 1 is: `implementation/shadow-cljs.edn`
 * names this package in a comment, which is not a source path. Reading 1's
 * repo-wide scan covers these files too; this row adds failing CLOSED on a
 * config that is missing, unparseable or key-less.
 */
test('no shadow-cljs build and no classpath entry reaches this package', () => {
  // `scanLoaderPositions` skips a missing file silently, so check presence first.
  for (const rel of BUILD_CONFIGS) {
    const text = readTracked(REPO_ROOT, rel);
    assert.ok(text !== null, `${rel} is missing — the scan has nothing to read`);
    assert.ok(
      loaderPositions(rel, text).length > 0,
      `${rel} yields no loader position at all — the scan has gone blind`,
    );
  }
  const hits = scanLoaderPositions(REPO_ROOT, BUILD_CONFIGS, REFERENCES, null);
  assert.deepStrictEqual(
    hits,
    [],
    `a build config puts this package on a source path or a classpath:\n${hits
      .map((h) => `  ${h.file}:${h.line} (${h.needle})`)
      .join('\n')}`,
  );
});

test('CONTROL — a doctored build config is caught, and a comment about one is not', () => {
  const dir = scratch({
    // Both spellings, at both kinds of loader position an EDN config has.
    'shadow-cljs.edn': '{:builds {:app {:target :browser :source-paths ["ssr-node/src"]}}}\n',
    'deps.edn': '{:deps {day8/ssr-node {:local/root "../ssr-node"}}}\n',
    // The near-miss: the shape `implementation/shadow-cljs.edn` carries.
    'commented.edn':
      ';; the module `implementation/ssr-node`\'s sidecar loads — prose, not a\n'
      + ';; source path, and the refusal code :rf.ssr-node/render-threw is not\n'
      + ';; one either.\n'
      + '{:builds {:app {:target :browser :source-paths ["src"]}}}\n',
  });
  try {
    const found = scanLoaderPositions(dir, ['shadow-cljs.edn', 'deps.edn'], REFERENCES, null);
    assert.deepStrictEqual(
      [...new Set(found.map((h) => h.file))],
      ['shadow-cljs.edn', 'deps.edn'],
      'the check must see a planted source path and a planted coordinate',
    );
    assert.deepStrictEqual(
      scanLoaderPositions(dir, ['commented.edn'], REFERENCES, null),
      [],
      'and a comment naming the package is not a way into a build — the narrowing this row is',
    );
    // And the comment must be there to be missed: the raw scan finds both needles.
    assert.strictEqual(
      scanForReferences(dir, ['commented.edn'], REFERENCES, null).length,
      REFERENCES.length,
      'the near-miss fixture must carry both needles, or the scoped scan proves nothing',
    );
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
  // A computed specifier is allowed only from `workerData`: the caller's own path.
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
    assert.deepStrictEqual(foreignRequires([path.join(dir, 'bad.cjs')]), [{ file: 'bad.cjs', spec: 'react' }]);
    // A module name hidden behind a binding is invisible to `foreignRequires`.
    assert.deepStrictEqual(foreignRequires([path.join(dir, 'sneaky.cjs')]), []);
    assert.deepStrictEqual(computedRequires([path.join(dir, 'sneaky.cjs')]), [{ file: 'sneaky.cjs', arg: 'NAME' }]);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ---------------------------------------------------------------------------
// 3b. The manifest — what it makes checkable
// ---------------------------------------------------------------------------

/** Manifest fields that DECLARE a dependency; `workspaces` links sibling packages. */
const DEPENDENCY_FIELDS = [
  'dependencies', 'devDependencies', 'peerDependencies', 'optionalDependencies',
  'bundleDependencies', 'bundledDependencies', 'workspaces',
];

/** The manifest fields whose leaves are PATHS a resolver follows. */
const PATH_LINK_FIELDS = ['exports', 'imports', 'main', 'module', 'browser', 'bin', 'types', 'typings'];

/** Every string leaf under a field value — `exports` nests, and `bin` may. */
const leaves = (v) =>
  typeof v === 'string' ? [v] : v && typeof v === 'object' ? Object.values(v).flatMap(leaves) : [];

/**
 * What is wrong with a manifest, as sentences: a declared dependency, or a
 * path link that leaves the package or names a file it does not ship.
 */
function manifestFaults(manifest, packageDir) {
  const faults = [];
  for (const field of DEPENDENCY_FIELDS) {
    if (manifest[field] !== undefined) {
      faults.push(`${field} is declared: ${JSON.stringify(manifest[field])}`);
    }
  }
  for (const field of PATH_LINK_FIELDS) {
    for (const leaf of leaves(manifest[field])) {
      const rel = path.relative(packageDir, path.resolve(packageDir, leaf));
      if (!leaf.startsWith('./') || rel.startsWith('..') || path.isAbsolute(rel)) {
        faults.push(`${field} links outside the package: ${leaf}`);
      } else if (!fs.existsSync(path.join(packageDir, rel))) {
        faults.push(`${field} links to a file the package does not ship: ${leaf}`);
      }
    }
  }
  return faults;
}

test('the manifest declares no dependency of any kind, and links only to files it ships', () => {
  // Checked against the real manifest, not against there being none.
  const manifest = JSON.parse(fs.readFileSync(path.join(PACKAGE_DIR, 'package.json'), 'utf8'));
  assert.deepStrictEqual(
    manifestFaults(manifest, PACKAGE_DIR),
    [],
    'the manifest reaches outside the package, or declares a dependency',
  );
});

test('nothing in implementation/package.json reaches this package', () => {
  // The manifest a client install reads, by the package's own name too.
  const pkg = JSON.parse(
    fs.readFileSync(path.join(REPO_ROOT, 'implementation', 'package.json'), 'utf8'),
  );
  for (const dep of Object.keys(pkg.devDependencies ?? {})) {
    assert.ok(!dep.includes('ssr-node'), `${dep} should not exist`);
  }
  assert.strictEqual(pkg.dependencies, undefined, 'the top-level manifest must add no runtime dependency');
});

test('CONTROL — a manifest that grows a dependency, or links past its own tree, is caught', () => {
  const dir = scratch({ 'src/service.cjs': 'module.exports = {};\n' });
  try {
    const clean = { name: 'x', exports: { './service': './src/service.cjs' }, bin: { x: './src/service.cjs' } };
    assert.deepStrictEqual(manifestFaults(clean, dir), [], 'the control must be clean, or the zero above proves nothing');

    // Each fault alone, so the sentence names the field and not a neighbour.
    const cases = [
      [{ dependencies: { react: '^19' } }, 'dependencies is declared: {"react":"^19"}'],
      [{ devDependencies: {} }, 'devDependencies is declared: {}'],
      [{ main: '../core/src/index.cjs' }, 'main links outside the package: ../core/src/index.cjs'],
      [{ exports: { './service': 'react' } }, 'exports links outside the package: react'],
      [
        { exports: { './service': { require: './src/../../escape.cjs' } } },
        'exports links outside the package: ./src/../../escape.cjs',
      ],
      [{ bin: { x: './bin/absent.cjs' } }, 'bin links to a file the package does not ship: ./bin/absent.cjs'],
    ];
    assert.deepStrictEqual(
      cases.map(([fault]) => manifestFaults({ ...clean, ...fault }, dir)),
      cases.map(([, sentence]) => [sentence]),
    );
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
