/*
 * Single source of truth for the adapter-smoke example set AND the
 * filter-selection logic shared by the orchestrator
 * (serve-and-run-examples-tests.cjs) and the Playwright runner
 * (run-examples-tests.cjs).
 *
 * Why this module exists (rf2-l72e2)
 * ----------------------------------
 * The two scripts used to apply the same `EXAMPLES_FILTER` value to two
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
 * identity for an entry (its build id, its repo-relative spec path, and
 * its absolute spec path). A pattern selects an entry when, so
 * normalized, it is a substring of ANY of those identities. This makes
 * build-id-shaped filters (`adapters/reagent-testbed`, `reagent-testbed`)
 * and path-shaped filters (`adapters/reagent/testbed`, `reagent/testbed`)
 * deliberately equivalent, and guarantees the orchestrator's
 * compile/stage set and the runner's spec set are identical for any
 * filter shape.
 *
 * The substring-trap protection that motivated the original
 * `\`->`/` normalization (a bare `shop` mustn't be shadowed by a
 * worktree-name substring — see the saved-memory note) is preserved:
 * matching is still substring-based and the user can still scope with a
 * path-separator-bearing form; that form just now works identically in
 * both phases.
 */

'use strict';

const path = require('path');

// __dirname is <repo>/examples/scripts. REPO_ROOT is <repo>.
const REPO_ROOT = path.resolve(__dirname, '..', '..');

// The canonical example set: the three adapter smokes. Each entry pairs
// a shadow-cljs build id with the hand-written spec.cjs that drives it
// and the HTML/output staging coordinates the orchestrator needs.
//
// Policy: the `examples/` tree is TEST-FREE. Every entry here MUST pair a
// build with an existing spec.cjs under SPEC_ROOTS; never add a build
// whose only purpose is "compile + stage with no spec to drive it" (dead
// CI weight). Real regressions are caught by substrate contract tests,
// the Xray feature-matrix gate, bundle-isolation, the perf-bundle gate,
// and mcp-conformance — not by per-example Playwright specs.
//
// Adding a new adapter smoke: append an entry with all four fields
// (build, htmlSrc, outDir, specPath). `specPath` is the absolute path to
// the spec.cjs the runner will execute; it MUST exist on disk.
const ADAPTERS = ['reagent', 'uix', 'helix'];
const OUT_ROOT = path.join(REPO_ROOT, 'implementation', 'out', 'examples');

const EXAMPLES = ADAPTERS.map((name) => ({
  build: `adapters/${name}-testbed`,
  htmlSrc: path.join(REPO_ROOT, 'implementation', 'adapters', name, 'testbed', 'index.html'),
  outDir: path.join(OUT_ROOT, 'adapter-testbeds', name),
  specPath: path.join(REPO_ROOT, 'implementation', 'adapters', name, 'testbed', 'spec.cjs'),
}));

// Spec discovery roots — the per-adapter smoke root only (examples/ is
// test-free). Exported so the runner can keep a discover-then-reconcile
// sanity check against the declared EXAMPLES manifest.
const SPEC_ROOTS = [path.join(REPO_ROOT, 'implementation', 'adapters')];

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
// the build id, the repo-relative spec path, and the absolute spec path.
// All are normalized into the canonical separator space.
function entryIdentities(entry) {
  const relSpec = path.relative(REPO_ROOT, entry.specPath);
  return [entry.build, relSpec, entry.specPath].map(normalizeForFilter);
}

function entryMatches(entry, patterns) {
  if (patterns.length === 0) return true;
  const identities = entryIdentities(entry);
  return patterns.some((p) => {
    const np = normalizeForFilter(p);
    return identities.some((id) => id.includes(np));
  });
}

// Select the subset of EXAMPLES whose identities match any pattern.
// Empty patterns => the full set. This is the single selection function
// both the orchestrator and the runner call, guaranteeing an identical
// selected set for any filter shape.
function selectEntries(patterns, examples = EXAMPLES) {
  return examples.filter((e) => entryMatches(e, patterns));
}

module.exports = {
  EXAMPLES,
  SPEC_ROOTS,
  REPO_ROOT,
  normalizeForFilter,
  parseFilterPatterns,
  entryIdentities,
  entryMatches,
  selectEntries,
};
