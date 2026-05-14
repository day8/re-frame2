/*
 * Shared path-policy helper (rf2-o38lb, policy-confirmed by rf2-21rfv).
 *
 * The implementation root carries several scripts that accept env-var
 * overrides for filesystem paths they then read or write:
 *
 *   - `serve-and-run-browser-tests.cjs` reads `BROWSER_TEST_ROOT` and
 *     may write `${ROOT}/index.html` and `${ROOT}/.rf-harness-token`.
 *   - `story-build.cjs` reads `STORY_BUILD_OUTPUT_DIR` and
 *     `STORY_BUILD_INDEX_HTML`, then writes `${OUTPUT_DIR}/index.html`
 *     and `${OUTPUT_DIR}/manifest.json`.
 *
 * Per rf2-21rfv (pragmatic stance, 2026-05-14): these env vars are
 * **CI-internal knobs**, not a stable public configuration surface.
 * re-frame2 reserves the right to rename / drop them between releases.
 * The path-policy check below is a safety net against accidents — a
 * mistyped path or unset env var that would otherwise turn a normal
 * build run into a `rm -rf` outside the repo — not a hardened sandbox.
 * The devs running these scripts already control the process.
 *
 * Policy:
 *
 *   - Approved roots for OUTPUT paths default to `implementation/out`.
 *   - Approved roots for INDEX-HTML source paths default to
 *     `<repo>/examples` (the canonical staging source) and
 *     `<repo>/implementation` (per-feature index.html templates).
 *   - Out-of-tree paths require explicit opt-in via the environment
 *     variable `RE_FRAME_ALLOW_OUT_OF_TREE_WRITES=1`. The flag is
 *     intentionally noisy to discourage accidental enablement;
 *     downstream consumers publishing into a sibling staging area
 *     (docs-site preview, etc.) set it explicitly.
 *
 * `enforcePolicy(label, candidate, opts)` returns the absolute,
 * normalised path if approved; throws (with a clear, actionable
 * error message) otherwise.
 */

'use strict';

const path = require('path');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');

const DEFAULT_OUT_ROOT = path.join(IMPL_ROOT, 'out');
const DEFAULT_HTML_ROOTS = [
  path.join(REPO_ROOT, 'examples'),
  IMPL_ROOT,
];

const OPT_IN_VAR = 'RE_FRAME_ALLOW_OUT_OF_TREE_WRITES';

function isOptInEnabled() {
  const raw = process.env[OPT_IN_VAR];
  if (raw == null) return false;
  const v = String(raw).trim().toLowerCase();
  return v === '1' || v === 'true' || v === 'yes' || v === 'on';
}

// Is `candidate` (resolved) inside `root` (resolved)? path.relative
// returns "" for "same path" and a non-traversal-prefixed string for
// "inside"; ".." or absolute path means "outside".
function isInside(candidate, root) {
  const rel = path.relative(root, candidate);
  if (rel === '') return true;
  if (rel.startsWith('..')) return false;
  if (path.isAbsolute(rel)) return false;
  return true;
}

function enforcePolicy(label, candidate, opts) {
  if (candidate == null || candidate === '') {
    throw new Error(`${label}: empty path`);
  }
  const abs = path.resolve(candidate);
  const allowed = (opts && opts.allowedRoots) || [DEFAULT_OUT_ROOT];
  for (const root of allowed) {
    if (isInside(abs, root)) {
      return abs;
    }
  }
  if (isOptInEnabled()) {
    console.warn(
      `${label}: '${abs}' is outside the approved roots, but ` +
        `${OPT_IN_VAR}=1 — proceeding. ` +
        `Approved roots: ${allowed.map((r) => `'${r}'`).join(', ')}.`,
    );
    return abs;
  }
  throw new Error(
    `${label}: refusing to use '${abs}' — path is outside the approved ` +
      `roots (${allowed.map((r) => `'${r}'`).join(', ')}). ` +
      `To allow out-of-tree paths (downstream-consumer use), set ` +
      `${OPT_IN_VAR}=1 in the environment. Per rf2-o38lb security audit.`,
  );
}

module.exports = {
  IMPL_ROOT,
  REPO_ROOT,
  DEFAULT_OUT_ROOT,
  DEFAULT_HTML_ROOTS,
  OPT_IN_VAR,
  enforcePolicy,
  isOptInEnabled,
};
