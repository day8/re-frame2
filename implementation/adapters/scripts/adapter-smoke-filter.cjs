/*
 * Single source of truth for the adapter-smoke set AND the
 * filter-selection logic shared by the orchestrator
 * (serve-and-run-adapter-smokes.cjs) and the Playwright runner
 * (run-adapter-smokes.cjs).
 *
 * Why this module exists (rf2-l72e2)
 * ----------------------------------
 * The two scripts used to apply the same `ADAPTER_SMOKE_FILTER` value to two
 * *different string spaces*:
 *
 *   - the orchestrator substring-matched the filter against shadow-cljs
 *     build ids        (`adapters/reagent-testbed`), while
 *   - the runner substring-matched the same filter against absolute
 *     spec.cjs paths   (`implementation/adapters/reagent/testbed/spec.cjs`).
 *
 * After only `_`->`-` and `\`->`/` normalization, a perfectly valid
 * build-id-shaped filter such as `reagent-testbed` selected the build in
 * the orchestrator (compile + stage ran) but then matched ZERO specs in
 * the runner (the segment `reagent-testbed` never appears in the
 * slash-separated path `reagent/testbed`). The narrow run compiled the
 * intended surface and then failed with "matched zero specs".
 *
 * Fix: declare each example once here, with BOTH its build id and the
 * spec.cjs path it pairs with, and expose ONE `selectEntries(patterns)`
 * that both scripts call. Selection normalizes `_`, `\`, and `/` all to a
 * single `-` separator on both the filter pattern and every candidate
 * identity for an entry (its build id and its repo-relative spec path). A
 * pattern selects an entry when, so normalized, it is a substring of
 * EITHER of those identities. This makes build-id-shaped filters
 * (`adapters/reagent-testbed`, `reagent-testbed`) and path-shaped filters
 * (`adapters/reagent/testbed`, `reagent/testbed`) deliberately equivalent,
 * and guarantees the orchestrator's compile/stage set and the runner's
 * spec set are identical for any filter shape.
 *
 * The candidate identities are deliberately limited to REPO-STABLE forms
 * (build id, repo-relative spec path). The absolute spec path is NOT a
 * match candidate (rf2-n4nc2o): an absolute path embeds the
 * workspace/worktree directory name, so a filter term that happened to be
 * a substring of that prefix (a bare `shop` under a `…/shop-testbed/…`
 * checkout) would over-select EVERY entry — silently running the wrong
 * smoke set. Matching only repo-stable identities makes selection
 * independent of where the repo is checked out, and preserves the
 * substring-trap protection that motivated the original `\`->`/`
 * normalization (see the saved-memory note): matching is still
 * substring-based and the user can still scope with a
 * path-separator-bearing form; that form just now works identically in
 * both phases and can never be shadowed by a filesystem prefix.
 */

'use strict';

const path = require('path');
const fs = require('fs');

// __dirname is <repo>/implementation/adapters/scripts. REPO_ROOT is <repo>.
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..');

// The canonical adapter-smoke set: the three adapter smokes. Each entry
// pairs a shadow-cljs build id with the hand-written spec.cjs that drives
// it and the HTML/output staging coordinates the orchestrator needs.
//
// Policy: the `examples/` tree is TEST-FREE. Every entry here MUST pair a
// build with an existing spec.cjs under ADAPTER_SMOKE_SPEC_ROOTS; never
// add a build whose only purpose is "compile + stage with no spec to
// drive it" (dead CI weight). Real regressions are caught by substrate
// contract tests, the Xray feature-matrix gate, bundle-isolation, the
// perf-bundle gate, and mcp-conformance — not by per-example Playwright
// specs.
//
// Adding a new adapter smoke: append an entry with all four fields
// (build, htmlSrc, outDir, specPath). `specPath` is the absolute path to
// the spec.cjs the runner will execute; it MUST exist on disk.
const ADAPTERS = ['reagent', 'uix', 'helix'];
const OUT_ROOT = path.join(REPO_ROOT, 'implementation', 'out', 'examples');

const ADAPTER_SMOKES = ADAPTERS.map((name) => ({
  build: `adapters/${name}-testbed`,
  htmlSrc: path.join(REPO_ROOT, 'implementation', 'adapters', name, 'testbed', 'index.html'),
  outDir: path.join(OUT_ROOT, 'adapter-testbeds', name),
  specPath: path.join(REPO_ROOT, 'implementation', 'adapters', name, 'testbed', 'spec.cjs'),
}));

// Spec discovery roots — the per-adapter smoke root only (examples/ is
// test-free). Exported so the runner can keep a discover-then-reconcile
// sanity check against the declared ADAPTER_SMOKES manifest.
const ADAPTER_SMOKE_SPEC_ROOTS = [path.join(REPO_ROOT, 'implementation', 'adapters')];

// Collapse the three cosmetic separators (`_`, `\`, `/`) to a single
// canonical `-` so build-id form and spec-path form land in the same
// string space before substring-matching. This is the key bridge that
// makes `reagent-testbed` (build form) and `reagent/testbed` (path form)
// equivalent.
function normalizeForFilter(s) {
  return String(s).replace(/[\\/_]/g, '-');
}

// Split a comma-separated filter into trimmed, non-empty patterns. Empty
// filter => empty array, which `selectEntries` treats as "select all".
function parseFilterPatterns(raw) {
  if (!raw) return [];
  return String(raw)
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}

// The candidate identity strings a pattern may match against, per entry:
// the build id and the repo-relative spec path. Both are stable repo
// identities, normalized into the canonical separator space.
//
// The absolute spec path is DELIBERATELY excluded (rf2-n4nc2o). It used to
// be a match candidate, but an absolute path embeds the workspace/worktree
// directory name, so any filter term that happened to be a substring of
// that prefix (e.g. a bare `shop` under a `…/shop-testbed/…` worktree)
// over-selected EVERY entry — silently running the wrong smoke set with no
// signal that the match came from the filesystem prefix rather than an
// example identity. Matching only repo-stable identities makes selection
// independent of where the repo is checked out.
function entryIdentities(entry) {
  const relSpec = path.relative(REPO_ROOT, entry.specPath);
  return [entry.build, relSpec].map(normalizeForFilter);
}

function entryMatches(entry, patterns) {
  if (patterns.length === 0) return true;
  const identities = entryIdentities(entry);
  return patterns.some((p) => {
    const np = normalizeForFilter(p);
    return identities.some((id) => id.includes(np));
  });
}

// Select the subset of ADAPTER_SMOKES whose identities match any pattern.
// Empty patterns => the full set. This is the single selection function
// both the orchestrator and the runner call, guaranteeing an identical
// selected set for any filter shape.
function selectEntries(patterns, smokes = ADAPTER_SMOKES) {
  return smokes.filter((e) => entryMatches(e, patterns));
}

// ---- spec-file discovery + manifest reconciliation ----------------------
// The Playwright runner (run-adapter-smokes.cjs) reconciles this manifest
// against the spec.cjs files actually on disk before running, failing loud
// on drift in either direction. The walker + the missing/undeclared
// partition used to live ONLY in the runner, so the fast JS tier could only
// exercise a RE-IMPLEMENTED copy of them (rf2-qf45gu): the copies could
// drift, and a bug in the runner's OWN walk/partition (node_modules skip,
// sort, the set-difference) surfaced only at Playwright-run time, never at
// the JS gate. Own them here — next to the manifest they reconcile against —
// so the runner AND its fast-tier test import the SAME real code.

// A spec file is the unprefixed `spec.cjs` (the adapter-testbed convention)
// or any `*.spec.cjs`. `examples/` is test-free, but the walker accepts the
// suffixed form defensively.
function isSpecFile(name) {
  return name === 'spec.cjs' || name.endsWith('.spec.cjs');
}

// Recursively walk `roots`, returning the resolved absolute paths of every
// spec file found, sorted. Skips `node_modules` dirs (defensive — none live
// under a spec root today). A missing root is skipped, not an error, so a
// not-yet-created root doesn't crash discovery.
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

// Pure set-difference of declared (manifest) vs discovered (on-disk) spec
// paths. Both sides are resolved to absolute before diffing, so callers may
// pass either form. `missing` = declared-but-absent-on-disk (a renamed or
// removed smoke still listed in the manifest); `undeclared` =
// present-on-disk-but-not-declared (a new spec added under a spec root with
// no manifest entry). Either list non-empty is a fail-loud drift.
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
  normalizeForFilter,
  parseFilterPatterns,
  entryIdentities,
  entryMatches,
  selectEntries,
  isSpecFile,
  listSpecFiles,
  reconcile,
};
