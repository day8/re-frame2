#!/usr/bin/env node
'use strict';

/*
 * Bounded, EDN-aware publishable-artefact discovery authority (rf2-zef0e).
 *
 * The single STRUCTURAL source of truth for "is implementation/<path> a
 * separately publishable artefact?" — a deps.edn whose real `:aliases` map
 * carries a `:clein/build` key. It reads EDN STRUCTURE (via scripts/lib/edn.cjs)
 * rather than grepping text, so the fail-closed enrolment claim actually holds:
 *
 *   - a genuine `:aliases/:clein/build` survives `;` inside EDN strings
 *     (the old `stripEdnComments` regex truncated the line at the first `;`,
 *     silently dropping a real alias — a publishable runtime slipped the gate);
 *   - a `:clein/build` token inside a string, a `;` comment, or a `#_`
 *     reader-discarded form is NOT mistaken for a build alias (the old
 *     `/:clein\/build/` search invented aliases from prose).
 *
 * Consumed by:
 *   - check-bundle-isolation.cjs — via `pathDeclaresBuildAlias`, to enrol every
 *     browser-optional publishable runtime in the isolation gate; and
 *   - .github/scripts/verify-version-lockstep.sh — via the CLI below, as its
 *     release-inventory authority,
 * so BOTH gates prove the identical parsed `:aliases/:clein/build` fact instead
 * of maintaining two drifting textual greps.
 *
 * This is not a general EDN / discovery framework: it is a thin predicate over
 * the existing edn.cjs reader plus one bounded flat-plus-nested traversal.
 */

const fs = require('fs');
const path = require('path');
const { readEdn, isMap, mapGetKeyword, isKeyword } = require('./edn.cjs');

// True iff `depsText` declares a REAL `:aliases/:clein/build` alias.
//
// Reads EDN structure: the top-level form must be a map, its `:aliases` value
// must be a map, and that map must carry a `:clein/build` KEY. Occurrences of
// the token inside strings, `;` comments, or `#_` discard forms are structure
// the reader skips, so they never satisfy the check.
//
// On UNPARSEABLE EDN the reader throws; we then fail CLOSED — if the raw text
// mentions `:clein/build` at all, treat the directory as publishable (forcing
// enrolment) rather than silently omitting it. A well-formed deps.edn never
// reaches this fallback, so the structure-aware rejection of string / comment /
// discard mentions above is not weakened for any real artefact.
function depsDeclaresBuildAlias(depsText) {
  let top;
  try {
    top = readEdn(depsText);
  } catch (_e) {
    return /:clein\/build/.test(depsText); // fail-closed on unreadable EDN
  }
  if (!isMap(top)) return false;
  const aliases = mapGetKeyword(top, 'aliases');
  if (aliases === undefined || aliases === null || !isMap(aliases)) return false;
  return aliases.entries.some(([k]) => isKeyword(k, 'clein/build'));
}

// True iff <root>/<relPath>/deps.edn declares a real `:clein/build` alias. A
// missing/unreadable deps.edn file is not a publishable artefact directory.
function pathDeclaresBuildAlias(root, relPath) {
  let depsText;
  try {
    depsText = fs.readFileSync(path.join(root, relPath, 'deps.edn'), 'utf8');
  } catch (_e) {
    return false;
  }
  return depsDeclaresBuildAlias(depsText);
}

// Every publishable artefact under `implRoot`, mirroring the release lockstep's
// bounded `find -mindepth 2 -maxdepth 3 -name deps.edn` reach: every
// implementation/<name>/deps.edn (depth 2) and implementation/<name>/<sub>/deps.edn
// (depth 3 — the adapters live here). Returns `{ relPath }` records sorted by
// relPath. No exclusions: callers filter for their own surface (the lockstep
// keeps them all; the bundle-isolation gate drops the always-present / JVM-only
// runtimes and re-groups adapters).
function listPublishableRuntimes(implRoot) {
  const out = [];
  let top;
  try {
    top = fs.readdirSync(implRoot, { withFileTypes: true });
  } catch (_e) {
    return out;
  }
  for (const ent of top) {
    if (!ent.isDirectory()) continue;
    if (pathDeclaresBuildAlias(implRoot, ent.name)) out.push({ relPath: ent.name });
    let subs;
    try {
      subs = fs.readdirSync(path.join(implRoot, ent.name), { withFileTypes: true });
    } catch (_e) {
      subs = [];
    }
    for (const sub of subs) {
      if (!sub.isDirectory()) continue;
      const relPath = `${ent.name}/${sub.name}`;
      if (pathDeclaresBuildAlias(implRoot, relPath)) out.push({ relPath });
    }
  }
  out.sort((a, b) => a.relPath.localeCompare(b.relPath));
  return out;
}

module.exports = {
  depsDeclaresBuildAlias,
  pathDeclaresBuildAlias,
  listPublishableRuntimes,
};

// CLI: `node publishable-runtimes.cjs <implementation-root>` prints each
// publishable artefact's implementation-relative subpath, one per line. The
// release lockstep (verify-version-lockstep.sh) consumes this as its inventory
// authority, so it proves the same parsed EDN fact the bundle-isolation gate
// consumes in-process. Exit 0 on success; non-zero (fail-closed) on error, so
// the lockstep hard-fails rather than skipping the inventory guard.
if (require.main === module) {
  const implRoot = process.argv[2];
  if (!implRoot) {
    process.stderr.write('usage: publishable-runtimes.cjs <implementation-root>\n');
    process.exit(2);
  }
  try {
    for (const rt of listPublishableRuntimes(implRoot)) {
      process.stdout.write(`${rt.relPath}\n`);
    }
  } catch (err) {
    process.stderr.write(`publishable-runtimes: ${err.message}\n`);
    process.exit(2);
  }
}
