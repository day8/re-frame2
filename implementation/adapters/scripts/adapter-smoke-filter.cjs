/*
 * The adapter-smoke set, and the selection both the orchestrator
 * (serve-and-run-adapter-smokes.cjs) and the Playwright runner
 * (run-adapter-smokes.cjs) call, so the two phases always pick the same
 * smokes.
 */

'use strict';

const path = require('path');
const fs = require('fs');

// __dirname is <repo>/implementation/adapters/scripts. REPO_ROOT is <repo>.
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..');

// One smoke per shipped adapter. Adding one: add the adapter's name here,
// with `index.html` + `spec.cjs` under implementation/adapters/<name>/testbed/
// and the `:adapters/<name>-testbed` build in shadow-cljs.edn.
const ADAPTERS = ['reagent', 'uix'];
const OUT_ROOT = path.join(REPO_ROOT, 'implementation', 'out', 'examples');

const ADAPTER_SMOKES = ADAPTERS.map((name) => {
  const testbed = path.join(REPO_ROOT, 'implementation', 'adapters', name, 'testbed');
  return {
    build: `adapters/${name}-testbed`,
    htmlSrc: path.join(testbed, 'index.html'),
    outDir: path.join(OUT_ROOT, 'adapter-testbeds', name),
    specPath: path.join(testbed, 'spec.cjs'),
  };
});

// Where the runner looks for spec files, to reconcile them against the set.
const ADAPTER_SMOKE_SPEC_ROOTS = [
  path.join(REPO_ROOT, 'implementation', 'adapters'),
];

// Split a comma-separated filter into trimmed, non-empty patterns. An empty
// filter gives no patterns, which selects every smoke.
function parseFilterPatterns(raw) {
  if (!raw) return [];
  return String(raw)
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}

// The smokes whose build id contains any pattern: `adapters/` selects both,
// `reagent` or `uix-testbed` one. Matching the build id alone, never a path,
// means where the repo is checked out cannot change the selection.
function selectEntries(patterns, smokes = ADAPTER_SMOKES) {
  if (patterns.length === 0) return smokes;
  return smokes.filter((e) => patterns.some((p) => e.build.includes(p)));
}

// A spec file is `spec.cjs` (the testbed convention) or any `*.spec.cjs`.
function isSpecFile(name) {
  return name === 'spec.cjs' || name.endsWith('.spec.cjs');
}

// Every spec file under `roots`, resolved and sorted, skipping node_modules.
// A missing root is skipped.
function listSpecFiles(roots) {
  const out = [];
  for (const root of roots) {
    if (!fs.existsSync(root)) continue;
    const stack = [root];
    while (stack.length > 0) {
      const dir = stack.pop();
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
          if (entry.name === 'node_modules') continue;
          stack.push(full);
        } else if (entry.isFile() && isSpecFile(entry.name)) {
          out.push(path.resolve(full));
        }
      }
    }
  }
  return out.sort();
}

// Declared spec paths against discovered ones, both resolved first:
// `missing` is declared but absent on disk, `undeclared` is on disk but not
// in the set. The runner refuses to run when either is non-empty, so a spec
// added without its adapter name, or a set entry whose spec was removed,
// cannot silently drop out of the run.
function reconcile(declared, discovered) {
  const dcl = declared.map((p) => path.resolve(p));
  const dsc = discovered.map((p) => path.resolve(p));
  return {
    missing: dcl.filter((p) => !dsc.includes(p)),
    undeclared: dsc.filter((p) => !dcl.includes(p)),
  };
}

module.exports = {
  ADAPTER_SMOKES,
  ADAPTER_SMOKE_SPEC_ROOTS,
  REPO_ROOT,
  parseFilterPatterns,
  selectEntries,
  listSpecFiles,
  reconcile,
};
