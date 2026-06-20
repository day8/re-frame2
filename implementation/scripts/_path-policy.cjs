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
 *     variable `RE_FRAME_ALLOW_OUT_OF_TREE_PATHS=1`. This one knob covers
 *     EVERY out-of-tree path `enforcePolicy` gates — whether the candidate
 *     is a write target (an output dir) or a read source (a custom
 *     `STORY_BUILD_INDEX_HTML` template) — because `enforcePolicy` is one
 *     generic allowed-root check applied to both. The flag is
 *     intentionally noisy to discourage accidental enablement;
 *     downstream consumers publishing into a sibling staging area
 *     (docs-site preview, etc.) or reading an out-of-tree HTML template
 *     set it explicitly.
 *
 * `enforcePolicy(label, candidate, opts)` returns the absolute,
 * normalised path if approved; throws (with a clear, actionable
 * error message) otherwise.
 */

'use strict';

const path = require('path');
const fs = require('fs');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');

const DEFAULT_OUT_ROOT = path.join(IMPL_ROOT, 'out');
const DEFAULT_HTML_ROOTS = [
  path.join(REPO_ROOT, 'examples'),
  // rf2-p8f2s — tool-owned testbeds may host index.html templates
  // (e.g. tools/story/testbeds/counter_with_stories/story_static.index.html).
  path.join(REPO_ROOT, 'tools'),
  IMPL_ROOT,
];

const OPT_IN_VAR = 'RE_FRAME_ALLOW_OUT_OF_TREE_PATHS';

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

// Resolve symlinks/junctions on an absolute path WITHOUT requiring the
// full path to exist (rf2-l9upzf). A lexical `path.resolve` alone lets a
// symlink/junction under an allowed root point outside the approved
// boundary while still passing the lexical prefix check — the writing
// scripts then follow the link and escape. To close this, walk up to the
// deepest existing ancestor, `fs.realpathSync` THAT (collapsing every
// symlink/junction in the existing prefix, including parent-directory
// links), then re-append the still-nonexistent tail lexically. The tail
// cannot itself be a symlink (it does not exist), so no escape hides
// there; any link in the path that DOES exist is canonicalised.
//
// Cross-platform: `fs.realpathSync` resolves POSIX symlinks AND Windows
// junctions/symlinks; we never assume a separator or drive shape — paths
// are split with `path.dirname` / `path.basename` and rejoined with
// `path.join`, so the same code runs on win32, darwin, and linux.
function realpathExistingPrefix(abs) {
  let existing = abs;
  const tail = [];
  // Walk up until we hit a path that exists (or the filesystem root).
  // `path.dirname` of a root returns the root itself, so the loop is
  // bounded.
  while (!fs.existsSync(existing)) {
    const parent = path.dirname(existing);
    if (parent === existing) {
      // Reached the root and nothing exists — nothing to canonicalise.
      return abs;
    }
    tail.unshift(path.basename(existing));
    existing = parent;
  }
  let realExisting;
  try {
    realExisting = fs.realpathSync(existing);
  } catch (_) {
    // realpath can fail on a broken link / permission edge — fall back to
    // the lexical absolute path (fail-closed: the lexical check still
    // applies, we just couldn't canonicalise further).
    return abs;
  }
  return tail.length === 0 ? realExisting : path.join(realExisting, ...tail);
}

function enforcePolicy(label, candidate, opts) {
  if (candidate == null || candidate === '') {
    throw new Error(`${label}: empty path`);
  }
  // The path the caller intends to read/write — returned on approval so
  // callers see the path they passed (lexically normalised), not a
  // surprise canonical rewrite.
  const lexicalAbs = path.resolve(candidate);
  // CONTAINMENT CHECK runs on REALPATHS (rf2-l9upzf): collapse any
  // existing symlink/junction in BOTH the candidate and the allowed
  // roots before comparing, so a link that resolves outside an approved
  // root is rejected even though its lexical path looked inside. Roots
  // are canonicalised too so a legitimate in-root path under a symlinked
  // checkout (e.g. macOS /tmp -> /private/tmp) still matches.
  const realCandidate = realpathExistingPrefix(lexicalAbs);
  const allowed = (opts && opts.allowedRoots) || [DEFAULT_OUT_ROOT];
  const realAllowed = allowed.map((r) => realpathExistingPrefix(path.resolve(r)));
  for (const root of realAllowed) {
    if (isInside(realCandidate, root)) {
      return lexicalAbs;
    }
  }
  if (isOptInEnabled()) {
    console.warn(
      `${label}: '${lexicalAbs}' is outside the approved roots, but ` +
        `${OPT_IN_VAR}=1 — proceeding. ` +
        `Approved roots: ${allowed.map((r) => `'${r}'`).join(', ')}.`,
    );
    return lexicalAbs;
  }
  throw new Error(
    `${label}: refusing to use '${lexicalAbs}' — path is outside the approved ` +
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
