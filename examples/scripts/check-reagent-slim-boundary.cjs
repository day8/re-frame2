#!/usr/bin/env node
/*
 * `check-reagent-slim-boundary` — STATIC source-boundary gate over the stock
 * Reagent example tree (rf2-hekqp; follow-up to rf2-2bih3 / PR #3137).
 *
 * The boundary this guards
 * ------------------------
 * re-frame2 ships TWO Reagent substrates:
 *
 *   STOCK Reagent  — the examples in core/ capabilities/ patterns/ real-apps/.
 *                    Mounts via `reagent.dom.client` + the stock adapter
 *                    `re-frame.adapter.reagent`.
 *   SLIM           — the day8/reagent-slim substrate, in
 *                    examples/substrates/reagent_slim/. Mounts via `reagent2.*`
 *                    + `re-frame.adapter.reagent-slim`.
 *
 * The slim substrate's whole point is bundle isolation: its user-facing import
 * point is `reagent2.*` (not stock `reagent.*`) and it wires
 * `re-frame.adapter.reagent-slim`. That wiring belongs ONLY to the
 * examples/substrates/reagent_slim/ tree. rf2-2bih3 (PR #3137) confirmed the stock tree
 * was clean of slim wiring at the time, but shipped WITHOUT a guard keeping it
 * so — a future edit could silently re-cross the boundary (e.g. a copy/paste
 * from the slim counter), and no automated gate would catch it.
 *
 * What this gate does (STATIC — no browser, no Playwright, no compile)
 * -------------------------------------------------------------------
 * It walks every tracked Clojure(Script) source in the stock-Reagent buckets and
 * FAILS if any of them requires a slim-substrate namespace:
 *
 *   - `reagent2.*`                    (the slim substrate's user-facing import)
 *   - `re-frame.adapter.reagent-slim` (the slim adapter Var)
 *
 * The stock adapter `re-frame.adapter.reagent` and stock `reagent.*` (note: NO
 * trailing `2`) are the LEGITIMATE wiring for this tree and are never flagged —
 * the detector is anchored so `re-frame.adapter.reagent` does NOT match the
 * `re-frame.adapter.reagent-slim` rule, and `reagent.core` does NOT match the
 * `reagent2.*` rule.
 *
 * This is NOT a per-example *.spec.cjs — examples/ stays test-free (rf2-8cevm).
 * It is a pure static scanner, wired into the always-run `test:script-policy`
 * gate (see implementation/scripts/check-reagent-slim-boundary.test.cjs) so a
 * slim require leaking into the stock-Reagent examples turns that gate RED in CI without
 * a new .github workflow job.
 *
 * CLI
 * ---
 *   node examples/scripts/check-reagent-slim-boundary.cjs           # scan, exit 0/1
 *   node examples/scripts/check-reagent-slim-boundary.cjs --list    # print files scanned, exit 0
 *
 * The pure scan logic is exported for check-reagent-slim-boundary.test.cjs,
 * which pins the detector (slim hits flagged, stock wiring NOT flagged) AND
 * gives the gate teeth by running the real scan over the repo.
 */

'use strict';

const fs = require('fs');
const path = require('path');

// __dirname is <repo>/examples/scripts. REPO_ROOT is <repo>.
const REPO_ROOT = path.resolve(__dirname, '..', '..');
const EXAMPLES_ROOT = path.join(REPO_ROOT, 'examples');
// The stock-Reagent examples this gate guards now live across the concept
// buckets (core/, capabilities/, patterns/, real-apps/). The slim example is
// the legitimate home of slim wiring and lives under
// examples/substrates/reagent_slim/ — part of the substrates/ tree, which this
// gate NEVER walks (substrates/ holds the non-stock-Reagent ports: uix, helix,
// reagent-slim).
const STOCK_REAGENT_ROOTS = [
  path.join(EXAMPLES_ROOT, 'core'),
  path.join(EXAMPLES_ROOT, 'capabilities'),
  path.join(EXAMPLES_ROOT, 'patterns'),
  path.join(EXAMPLES_ROOT, 'real-apps'),
];

// ---------------------------------------------------------------------------
// Forbidden slim-substrate namespaces. Each rule matches a namespace SYMBOL as
// it appears in a `:require` / `:require-macros` form — a leading `[` or
// whitespace, then the namespace, then a word boundary (whitespace, `]`, `:`
// for an alias keyword, or end). Anchoring on the leading boundary is what
// keeps the stock wiring clean:
//
//   reagent2.*  matches `reagent2.core`, `reagent2.dom.client`, … but NOT
//               stock `reagent.core` (no trailing `2`).
//   re-frame.adapter.reagent-slim matches the slim adapter but NOT the stock
//               `re-frame.adapter.reagent` (the `-slim` suffix is required,
//               and the trailing boundary forbids `re-frame.adapter.reagent`
//               from matching the slim rule).
// ---------------------------------------------------------------------------
const FORBIDDEN = [
  {
    id: 'reagent2',
    label: 'reagent2.* (slim substrate user-facing import)',
    // (^|[\s[(]) leading boundary; reagent2 then `.` or a trailing boundary.
    re: /(^|[\s[(]):?reagent2(?=[.\s\][):]|$)/m,
  },
  {
    id: 'reagent-slim-adapter',
    label: 're-frame.adapter.reagent-slim (slim adapter Var)',
    re: /(^|[\s[(]):?re-frame\.adapter\.reagent-slim(?=[\s\][):]|$)/m,
  },
];

// Clojure(Script) source extensions a runnable example ships.
const SOURCE_EXTS = new Set(['.clj', '.cljs', '.cljc']);

// ---------------------------------------------------------------------------
// Source enumeration. Walk the stock-Reagent tree for .clj/.cljs/.cljc files,
// skipping node_modules / build-output dirs. The slim tree is a SIBLING
// (examples/reagent-slim/) and is never reached by this prefix walk.
// ---------------------------------------------------------------------------

function listStockReagentSources(roots = STOCK_REAGENT_ROOTS) {
  const out = [];
  const stack = [...roots];
  while (stack.length > 0) {
    const dir = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (entry.name === 'node_modules' || entry.name === '.shadow-cljs') continue;
        stack.push(full);
      } else if (entry.isFile() && SOURCE_EXTS.has(path.extname(entry.name))) {
        out.push(full);
      }
    }
  }
  return out.sort();
}

// ---------------------------------------------------------------------------
// The pure detector. Given a source string, return the list of forbidden-rule
// ids it matches. Exported so the test can pin the boundary (slim hits flagged,
// stock wiring NOT flagged) without touching disk.
// ---------------------------------------------------------------------------

function detectForbidden(source) {
  const hits = [];
  for (const rule of FORBIDDEN) {
    if (rule.re.test(source)) hits.push(rule);
  }
  return hits;
}

function readFileSafe(io, p) {
  try {
    return io.readFileSync(p, 'utf8');
  } catch {
    return null;
  }
}

function scanFile(io, absPath) {
  const rel = path.relative(REPO_ROOT, absPath).split(path.sep).join('/');
  const source = readFileSafe(io, absPath);
  if (source == null) {
    return { rel, errors: [`${rel}: could not read source`] };
  }
  const errors = [];
  for (const rule of detectForbidden(source)) {
    errors.push(
      `${rel}: requires ${rule.label} — slim wiring is forbidden in the ` +
        `stock-Reagent examples (core/ capabilities/ patterns/ real-apps/). ` +
        `The slim substrate belongs ONLY to examples/substrates/reagent_slim/. ` +
        `Use stock Reagent (reagent.* + re-frame.adapter.reagent) here, or ` +
        `move the example under examples/substrates/reagent_slim/.`,
    );
  }
  return { rel, errors };
}

// Run the full scan over every stock-Reagent source.
function scanAll(opts = {}) {
  const io = opts.io || fs;
  const files = opts.files || listStockReagentSources();
  const results = files.map((f) => scanFile(io, f));
  const errors = results.flatMap((r) => r.errors);
  return { results, errors };
}

module.exports = {
  REPO_ROOT,
  EXAMPLES_ROOT,
  STOCK_REAGENT_ROOTS,
  FORBIDDEN,
  SOURCE_EXTS,
  listStockReagentSources,
  detectForbidden,
  scanFile,
  scanAll,
};

// ---------------------------------------------------------------------------
// CLI entry-point (skipped when require()'d by the test suite).
// ---------------------------------------------------------------------------
if (require.main === module) {
  const listOnly = process.argv.slice(2).includes('--list');
  const files = listStockReagentSources();

  // Non-vacuous guard: a walk that silently recovers nothing must NOT let the
  // gate pass green having scanned zero files. The stock Reagent tree ships
  // dozens of sources; require a sane floor so a layout/walk drift fails loud
  // rather than turning the boundary check into a vacuous pass.
  if (files.length < 20) {
    console.error(
      `check-reagent-slim-boundary: only ${files.length} source file(s) ` +
        `found under the stock-Reagent buckets (core/ capabilities/ patterns/ ` +
        `real-apps/) — expected the full set. Walk or layout drift; refusing ` +
        `to pass a vacuous gate.`,
    );
    process.exit(1);
  }

  if (listOnly) {
    console.log(
      `check-reagent-slim-boundary: ${files.length} stock-Reagent source(s):`,
    );
    for (const f of files) {
      console.log(`  ${path.relative(REPO_ROOT, f).split(path.sep).join('/')}`);
    }
    process.exit(0);
  }

  const { errors } = scanAll({ files });

  console.log(
    `check-reagent-slim-boundary: scanning ${files.length} stock-Reagent ` +
      `source(s) for slim wiring.`,
  );

  if (errors.length > 0) {
    console.error(
      `\ncheck-reagent-slim-boundary: ${errors.length} boundary violation(s):`,
    );
    for (const e of errors) console.error(`  - ${e}`);
    console.error(
      `\nThe stock-Reagent examples (core/ capabilities/ patterns/ real-apps/) ` +
        `must use stock Reagent wiring only (reagent.* + ` +
        `re-frame.adapter.reagent). reagent2.* and re-frame.adapter.reagent-slim ` +
        `belong ONLY to examples/substrates/reagent_slim/. Fix the require, or ` +
        `move the example under examples/substrates/reagent_slim/.`,
    );
    process.exit(1);
  }

  console.log(
    `check-reagent-slim-boundary: all ${files.length} stock-Reagent sources ` +
      `are slim-free (no reagent2.* / re-frame.adapter.reagent-slim require). ` +
      `Boundary intact.`,
  );
}
