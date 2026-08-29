#!/usr/bin/env node
'use strict';
// THE HICASSO PACKAGE'S WARNINGS-FATAL COMPILE — rf2-okhdf, rf2-peorl; its own
// script since rf2-6c12m.1 moved the bench lane out of the package.
//
//     npm run test:hicasso-compile        # from implementation/
//     node hicasso/scripts/check_modules_compile.cjs --list
//
// ## What it compiles, and why nothing else does
//
// Two entry sources, both PRODUCT concerns that no other warnings-fatal
// compile reaches:
//
//   1. THE OPTIONAL MODULES — motion, overlay, forms, native, server. They are
//      unreachable from the public door BY CONSTRUCTION (the invariant
//      `check_optional_module_reachability.py` enforces), so no compile that
//      starts at the door sees them. Their own tests do compile them — under
//      `:node-test-hicasso`, which sets `:infer-externs false`, and under
//      `:browser-test`, which infers and then exits 0 on the warnings like
//      every other shadow build. Compiled in two places and JUDGED in none:
//      four `:infer-warning`s on `(.. el -style -anchorName)` in
//      `impl/overlay.cljs` lived on main until a worker read them off a
//      browser build BY HAND (rf2-9zz0y). Under `:advanced` Closure renames a
//      property it cannot see an extern for, so the trigger claim would have
//      broken silently in every consumer that shipped an overlay.
//
//      The entries are READ FROM THE ROSTER — `check_optional_module_
//      reachability.py --module-namespaces` — never restated here. A
//      hand-copied list would leave the NEXT optional module compiled by
//      nothing while this gate went on reporting success; rowing a module in
//      that roster is already mandatory, so the same edit buys this coverage.
//      It fails CLOSED in four directions — emitter missing, emitter erroring,
//      an empty or collapsed list, a malformed namespace — because an entry
//      source that quietly contributed nothing would leave the gate green over
//      exactly the code it exists to cover. `check_modules_compile.test.cjs`
//      pins each refusal.
//
//   2. THE TWO RE-HOMED CORE INSTRUMENTS — `re-frame.bench.read-attribution-cljs`
//      and `re-frame.bench.write-attribution` under `core/test/re_frame/bench/`.
//      Neither is named `*-cljs-test`, so `:node-test` and `:browser-test` do
//      not select them, and nothing in the tree requires either one. Their own
//      gate was deleted with `implementation/freehand/` (PR #8322); these two
//      rows are the whole of the coverage it gave them (rf2-peorl). A stated
//      roster, and each row checked against disk: a moved, renamed or
//      re-namespaced file REDS the gate rather than dropping out of it.
//
// ## The build
//
// `:hicasso-modules-compile` in `implementation/shadow-cljs.edn` — a plain
// `:browser` module with `:infer-externs :auto`, entries merged in through
// `--config-merge`. `:browser` is MEASURED rather than assumed: the
// instruments' closure reads DOM-element properties in core's `spine.cljs`
// that only Closure's browser externs can infer, and the same rows under a
// `:node-script` id raised four `:infer-warning`s there (rf2-bl0j). A dev
// `compile`, not a release: the classes closed here — a deleted def, a renamed
// require, a dropped arity, an undeclared var, an un-externable property — are
// resolved by the analyser before optimisation, and `:infer-warning` is bound
// in both modes (mutation-proved on the overlay fault above).
//
// CONTROL, re-run whenever this gate is touched: read a property no extern
// declares on an UNTAGGED parameter. In `impl/overlay.cljs`, rewrite
// `claim-anchor!`'s `(when anchor-id` as `(when (.-anchorNameBogus anchor-id)`
// -> exit 1, `WARNING #1 - :infer-warning`, `Cannot infer target type in
// expression (. anchor-id -anchorNameBogus)`; restore -> exit 0. Dropping the
// `^js` on `claim-anchor!`'s `el` is NOT a control: that `el` is bound from
// `(.getElementById js/document ...)`, which the analyser already types `js`,
// so the hint is redundant and the mutation reads 0 warnings (measured on
// PR #8752 and again on the PR that replaced this instruction).
//
// ## Limits
//
// Anything that COMPILES. This proves the modules and the instruments still
// BUILD; it executes nothing, and for the two Node instruments it never
// claimed they still run under Node.

const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const IMPL = path.resolve(__dirname, '../..');
const BUILD_ID = 'hicasso-modules-compile';
const OUT_DIR = 'out/hicasso-modules-compile';
const TAG = 'hicasso-compile';

/**
 * The two core attribution instruments — see the header. `file` is relative
 * to `implementation/`, and both halves are verified: the file must exist AND
 * declare that exact namespace.
 */
const REHOMED_BENCH_ENTRIES = [
  {
    ns: 're-frame.bench.read-attribution-cljs',
    file: 'core/test/re_frame/bench/read_attribution_cljs.cljs',
    why: "the READ path priced on the host re-frame2 ships to — read_attribution.clj's CLJS counterpart, arm for arm",
  },
  {
    ns: 're-frame.bench.write-attribution',
    file: 'core/test/re_frame/bench/write_attribution.cljs',
    why: "where the bytes of a NARROW WRITE go — the decomposition of B8's 457,181-byte write leg",
  },
];

// The optional modules' roster, and the flag that makes it answer. It is the
// SAME file that forbids anything outside a module from requiring it.
const MODULE_ROSTER = 'hicasso/scripts/check_optional_module_reachability.py';
const MODULE_ROSTER_FLAG = '--module-namespaces';

// Five optional modules today, eight namespaces between them. The floor is a
// COLLAPSE detector and not a count: it catches an emitter that has started
// answering nothing while still exiting 0. Growth is the roster's business,
// and this number is deliberately NOT raised to meet it.
const MIN_MODULE_NAMESPACES = 4;

// A CLJS namespace, as the emitter is contracted to print them. Anything else
// is a refusal rather than an entry: shadow-cljs would take a malformed token
// into `:entries` and fail with a resolution error naming a file nobody wrote.
const NAMESPACE_RE = /^[A-Za-z][A-Za-z0-9._*+!?<>=$%&|-]*$/;

/**
 * Decide the optional-module entry list from the emitter's result. Pure, so
 * the self-test can prove each refusal fires without spawning Python.
 *
 * @returns {{ok: true, namespaces: string[]} | {ok: false, reason: string, detail: string[]}}
 */
function decideModuleNamespaces({ error, status, stdout, stderr }) {
  const cmd = `python ${MODULE_ROSTER} ${MODULE_ROSTER_FLAG}`;
  if (error) {
    return {
      ok: false,
      reason: `could not run \`${cmd}\` — the optional-module roster could not be asked`,
      detail: [String(error.message || error)],
    };
  }
  if (status !== 0) {
    return {
      ok: false,
      reason: `\`${cmd}\` exited ${status}`,
      detail: String(stderr || '').split('\n').filter(Boolean),
    };
  }
  const namespaces = String(stdout || '')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);
  const malformed = namespaces.filter(
    (ns) => !NAMESPACE_RE.test(ns) || !ns.includes('.'),
  );
  if (malformed.length > 0) {
    return {
      ok: false,
      reason: `\`${cmd}\` emitted ${malformed.length} token(s) that are not namespaces`,
      detail: malformed.map((ns) => JSON.stringify(ns)),
    };
  }
  if (namespaces.length < MIN_MODULE_NAMESPACES) {
    return {
      ok: false,
      reason:
        `\`${cmd}\` emitted only ${namespaces.length} namespace(s) ` +
        `(floor ${MIN_MODULE_NAMESPACES}) — the roster emission has collapsed, ` +
        `and compiling the survivors would report success over the modules it dropped`,
      detail: namespaces,
    };
  }
  return { ok: true, namespaces: [...new Set(namespaces)].sort() };
}

/** Ask the roster which namespaces the optional modules own. */
function optionalModuleNamespaces(impl = IMPL) {
  const python = process.env.PYTHON || 'python';
  const r = spawnSync(python, [MODULE_ROSTER, MODULE_ROSTER_FLAG], {
    cwd: impl,
    encoding: 'utf8',
  });
  return decideModuleNamespaces(r);
}

/** The namespace a source declares — the `ns` form at column 0. */
function namespaceOf(file) {
  const src = fs.readFileSync(file, 'utf8');
  const m = /^\(ns\s+(?:\^\{[\s\S]*?\}\s+)?([A-Za-z0-9._*+!?<>=$%&|-]+)/m.exec(src);
  return m ? m[1] : null;
}

/**
 * A stated roster, verified. A row whose file has moved, been renamed or been
 * deleted — or whose file no longer declares the namespace claimed for it — is
 * a FAILURE, not a skip: a stated list can only be honest if saying something
 * untrue stops the gate. `rows` and `impl` are parameters so the self-test can
 * watch each refusal fire against a fixture tree.
 */
function verifyRoster(rows, impl = IMPL) {
  const namespaces = [];
  const broken = [];
  for (const row of rows) {
    const full = path.join(impl, row.file);
    if (!fs.existsSync(full)) {
      broken.push(`${row.file} — no such file (roster claims ${row.ns})`);
      continue;
    }
    const declared = namespaceOf(full);
    if (declared !== row.ns) {
      broken.push(
        `${row.file} — declares ${declared ?? '(no readable ns form)'}, roster claims ${row.ns}`,
      );
      continue;
    }
    namespaces.push(row.ns);
  }
  return { namespaces, broken };
}

// ---------------------------------------------------------------------------
// The warnings-fatal build door. shadow-cljs exits 0 when a build emits
// warnings, so the exit status alone checks the one condition that does not
// happen; the verdict is read off the `Build completed.` summary instead, and
// a summary the parser cannot find is a REFUSAL rather than a pass. The same
// judgement the bench lane's `lane_build.cjs` makes, carried here rather than
// required across the boundary — the lane is off this package's classpath on
// purpose (rf2-6c12m.1). The two other readers of this line,
// `scripts/check-examples-compile.cjs` and `scripts/compile-node-test.cjs`,
// are deliberately not unified with it (rf2-040s1).
// ---------------------------------------------------------------------------

const ANSI_RE = /\x1B\[[0-9;]*m/g;
const COMPLETED_RE = /\[(:[^\]\s]+)\]\s+Build completed\.[^\n]*?(\d+)\s+warnings?/g;
const FAILED_RE = /\[(:[^\]\s]+)\]\s+Build failed/g;
const WARNING_MARKER_RE = /-{2,}\s*WARNING #/;
const WARNING_HEADLINE_RE = /-{2,}\s*(WARNING #[^\n]*?)\s*-{3,}\s*$/gm;

function stripAnsi(s) {
  return String(s).replace(ANSI_RE, '');
}

/** Decide a build from shadow's exit status and its captured output. */
function judgeBuild({ status, output }) {
  const src = stripAnsi(output);
  const completed = [];
  const failed = [];
  let m;
  COMPLETED_RE.lastIndex = 0;
  while ((m = COMPLETED_RE.exec(src)) !== null) {
    completed.push({ build: m[1], warnings: Number(m[2]) });
  }
  FAILED_RE.lastIndex = 0;
  while ((m = FAILED_RE.exec(src)) !== null) failed.push(m[1]);

  if (status !== 0) {
    return {
      ok: false,
      reason: `shadow-cljs exited ${status}`,
      detail: failed.length > 0 ? [`failed build(s): ${failed.join(', ')}`] : ['see the compiler output above'],
    };
  }
  if (completed.length === 0) {
    return {
      ok: false,
      reason: 'shadow-cljs exited 0 but NO parsable "Build completed." summary was found in its output',
      detail: [
        'the warning count is read from that line, so its absence means this',
        'build was NOT checked for warnings — refusing to pass it green.',
      ],
    };
  }
  const warned = completed.filter((b) => b.warnings > 0);
  if (warned.length > 0) {
    const headlines = [];
    WARNING_HEADLINE_RE.lastIndex = 0;
    while ((m = WARNING_HEADLINE_RE.exec(src)) !== null) headlines.push(m[1].trim());
    return {
      ok: false,
      reason: warned.map((b) => `${b.build} compiled with ${b.warnings} warning(s)`).join('; '),
      detail: [
        ...headlines.map((h) => `  ${h}`),
        'A warning is a FAILURE here. Under :advanced an un-externable property',
        'is renamed and an undeclared var is `undefined`. Fix the source; do',
        'not lower the warning.',
      ],
    };
  }
  if (WARNING_MARKER_RE.test(src)) {
    return {
      ok: false,
      reason: 'a "------ WARNING" block appears in the compiler output but every parsed summary reads 0 warnings',
      detail: ['the summary parser has drifted past real warning evidence.'],
    };
  }
  return { ok: true };
}

/** Build `buildId` through shadow-cljs's own JS entry-point, warnings-fatal. */
function shadowBuild({ impl, mode, buildId, configMerge, tag }) {
  const runner = path.join(impl, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const args = [runner, mode, buildId];
  if (configMerge) args.push('--config-merge', configMerge);
  const r = spawnSync(process.execPath, args, {
    cwd: impl,
    encoding: 'utf8',
    maxBuffer: 256 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const output = `${r.stdout || ''}${r.stderr || ''}`;
  // Echo FIRST and in full, before any verdict.
  if (output) process.stderr.write(output);
  const verdict = r.error
    ? { ok: false, reason: `could not run shadow-cljs: ${r.error.message}`, detail: [] }
    : judgeBuild({ status: r.status, output });
  if (!verdict.ok) {
    console.error(`\n[${tag}] BUILD REFUSED — ${verdict.reason}`);
    for (const line of verdict.detail) console.error(`[${tag}] ${line}`);
    process.exit(1);
  }
}

if (require.main === module) {
  const listOnly = process.argv.slice(2).includes('--list');
  const rehomed = verifyRoster(REHOMED_BENCH_ENTRIES);
  const modules = optionalModuleNamespaces();

  if (!modules.ok) {
    console.error(
      `[${TAG}] the optional-module entry source failed (rf2-okhdf): ${modules.reason}. ` +
        `Refusing to compile a set the optional modules may be missing from — ` +
        `nothing else in this repository compiles them where warnings are fatal.`,
    );
    for (const d of modules.detail) console.error(`  ${d}`);
    process.exit(1);
  }

  if (rehomed.broken.length > 0) {
    console.error(
      `[${TAG}] ${rehomed.broken.length} roster row(s) in ${path.relative(IMPL, __filename)} no ` +
        `longer name a real namespace — refusing to compile a set they have silently ` +
        `dropped out of (rf2-peorl):`,
    );
    for (const b of rehomed.broken) console.error(`  ${b}`);
    process.exit(1);
  }

  const namespaces = [...new Set([...rehomed.namespaces, ...modules.namespaces])].sort();

  if (listOnly) {
    for (const ns of namespaces) console.log(ns);
    process.exit(0);
  }

  console.error(
    `[${TAG}] compiling ${namespaces.length} namespaces ` +
      `(${modules.namespaces.length} optional-module namespaces read from ${MODULE_ROSTER}, ` +
      `${rehomed.namespaces.length} re-homed core attribution instruments) -> ${OUT_DIR}`,
  );

  // ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
  // whitespace once the EDN contains a newline.
  const configMerge =
    `{:output-dir "${OUT_DIR}" :asset-path "." ` +
    `:modules {:main {:entries [${namespaces.join(' ')}]}}}`;

  shadowBuild({ impl: IMPL, mode: 'compile', buildId: BUILD_ID, configMerge, tag: TAG });

  console.error(`[${TAG}] ok — ${namespaces.length} namespaces compiled with zero warnings`);
}

module.exports = {
  decideModuleNamespaces,
  optionalModuleNamespaces,
  namespaceOf,
  verifyRoster,
  judgeBuild,
  MIN_MODULE_NAMESPACES,
  MODULE_ROSTER,
  MODULE_ROSTER_FLAG,
  REHOMED_BENCH_ENTRIES,
};
