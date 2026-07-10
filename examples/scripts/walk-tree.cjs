/*
 * `walk-tree` — a FAIL-CLOSED directory-walk primitive shared by the examples
 * script scanners (rf2-3fc89f.31).
 *
 * The bug this closes
 * -------------------
 * Three examples-script enumerations each hand-rolled the SAME depth-first walk
 * with the SAME fail-OPEN flaw: a `readdirSync` that threw (EACCES / ENOENT /
 * ENOTDIR / a torn checkout) was caught and the directory silently skipped —
 *
 *   - check-examples-assets.cjs   listExampleIndexHtml
 *   - check-reagent-slim-boundary.cjs  listStockReagentSources
 *   - examples-staging.cjs        buildNsIndex
 *
 * so a partial walk returned an ordinary SMALLER array, indistinguishable from a
 * complete walk. Those scripts are trust gates guarded only by a global count
 * floor (10 host pages / 20 stock sources); a read failure affecting one subtree
 * could stay above the floor and green the scan while an entire promised
 * partition went unexamined (measured: a synthetic EACCES on examples/core drops
 * 13 of 35 host pages / 18 of 69 stock sources, both still above their floors).
 *
 * The contract
 * ------------
 * `walkDir` NEVER erases a directory-read failure: every unreadable path is
 * recorded in `walkErrors` (with its cause) instead of being dropped from the
 * result. The caller's enumeration is therefore only trustworthy when
 * `walkErrors` is empty — `assertWalkComplete` turns a non-empty `walkErrors`
 * into a loud throw that names each unreadable path, so an infra / layout /
 * permissions failure fails CLOSED rather than shrinking the scan silently.
 *
 * INTENTIONAL skips (`node_modules`, `_shared`, `.shadow-cljs`, …) are a POLICY
 * expressed via `skipDir`, NOT an error: a skipped directory is never visited
 * and never appears in `walkErrors`. The distinction is the whole point — a
 * deliberate prune stays silent, an unexpected read failure fails loud.
 *
 * The walk is filesystem-INJECTABLE (`io`, default the real `fs`) so the failure
 * paths are deterministic in tests: an injected io whose `readdirSync` throws for
 * one subtree reproduces a partial-walk condition without touching real disk
 * permissions.
 */

'use strict';

const fs = require('fs');
const path = require('path');

// Depth-first walk of one or more root directories, collecting the FILE entries
// the caller accepts. FAIL-CLOSED: a directory that cannot be enumerated is
// recorded in `walkErrors` (never silently dropped from `items`).
//
// opts:
//   roots      : string[]                         starting directories
//   io         : { readdirSync }                  injectable fs (default: fs)
//   skipDir    : (name, fullPath) => boolean       POLICY prune for a directory
//                                                  (node_modules / _shared / …);
//                                                  a pruned dir is NOT an error
//   acceptFile : (name, fullPath, dirent) => bool  collect this file into items
//
// Returns { items: string[], walkErrors: [{ path, code, message }] }.
// `items` is returned in walk order (unsorted) — callers that need a stable
// order sort it themselves.
function walkDir({ roots, io = fs, skipDir = () => false, acceptFile = () => false }) {
  const items = [];
  const walkErrors = [];
  // Resolve up front so a caller passing a relative root gets an absolute path
  // in any recorded walkError.
  const stack = roots.map((r) => path.resolve(r));
  while (stack.length > 0) {
    const dir = stack.pop();
    let entries;
    try {
      entries = io.readdirSync(dir, { withFileTypes: true });
    } catch (err) {
      // Fail CLOSED: record the unreadable path + cause instead of dropping it.
      // This includes an unreadable/missing ROOT (each root is enumerated
      // independently, so a single bad root among several is named here), and
      // any unreadable nested directory.
      walkErrors.push({
        path: dir,
        code: (err && err.code) || null,
        message: (err && err.message) || String(err),
      });
      continue;
    }
    for (const entry of entries) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        // POLICY prune — a deliberately skipped dir is never an error.
        if (skipDir(entry.name, full)) continue;
        stack.push(full);
      } else if (entry.isFile() && acceptFile(entry.name, full, entry)) {
        items.push(full);
      }
    }
  }
  return { items, walkErrors };
}

// Build the human-readable, actionable report for a non-empty `walkErrors` set:
// the count, the context (which enumeration), and every unreadable path + cause.
function walkErrorReport(walkErrors, context) {
  const lines = walkErrors
    .map((e) => `    - ${e.path}: ${e.message}`)
    .join('\n');
  return (
    `${context}: directory enumeration FAILED for ${walkErrors.length} ` +
    `path(s) — refusing to proceed on an incomplete walk. A partial scan can ` +
    `hide a real violation behind a smaller-but-above-floor result, so this ` +
    `fails CLOSED rather than advertising an incomplete set:\n${lines}`
  );
}

// Fail-closed guard: throw when any directory could not be enumerated, so an
// incomplete walk can never be treated as a complete one. No-op when the walk
// was clean. The thrown Error carries `.walkErrors` (and `.actionable = true`,
// matching the serve-example convention for a message printed without a stack).
function assertWalkComplete(walkErrors, context) {
  if (!walkErrors || walkErrors.length === 0) return;
  const err = new Error(walkErrorReport(walkErrors, context));
  err.walkErrors = walkErrors;
  err.actionable = true;
  throw err;
}

module.exports = { walkDir, walkErrorReport, assertWalkComplete };
