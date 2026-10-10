#!/usr/bin/env node
'use strict';
// THE FRESCO PACKAGE'S WARNINGS-FATAL COMPILE.
//
//     npm run test:fresco-compile        # from implementation/
//     node fresco/scripts/check_modules_compile.cjs --list
//
// ## What it compiles, and why nothing else does
//
// Two entry sources, both PRODUCT concerns that no other warnings-fatal
// compile reaches:
//
//   1. THE OPTIONAL MODULES — motion, overlay, forms, native, server, substrate. They are
//      unreachable from the public door BY CONSTRUCTION (the invariant
//      `check_optional_module_reachability.py` enforces), so no compile that
//      starts at the door sees them. Their own tests compile them under
//      `:node-test-fresco`, which sets `:infer-externs false` and so never
//      raises the `:infer-warning` class, and under `:browser-test` only as
//      far as a `-dom-cljs-test` requires them. Here every module compiles
//      with inference on, so an `:infer-warning` on `(.. el -style
//      -anchorName)` in `impl/overlay.cljs` fails this gate whichever tests
//      exist. Under `:advanced` Closure renames a property it cannot see an
//      extern for, so the trigger claim would break silently in every
//      consumer that ships an overlay.
//
//      The entries are READ FROM THE ROSTER — `check_optional_module_
//      reachability.py --module-namespaces` — never restated here. A
//      hand-copied list would leave the NEXT optional module compiled by
//      nothing while this gate went on reporting success; rowing a module in
//      that roster is mandatory, so the same edit buys this coverage.
//      It fails CLOSED in four directions — emitter missing, emitter erroring,
//      an empty or collapsed list, a malformed namespace — because an entry
//      source that quietly contributed nothing would leave the gate green over
//      exactly the code it exists to cover. `check_modules_compile.test.cjs`
//      pins each refusal.
//
//   2. THE TWO CORE ATTRIBUTION INSTRUMENTS — `re-frame.bench.read-attribution-cljs`
//      and `re-frame.bench.write-attribution` under `core/test/re_frame/bench/`.
//      Neither is named `*-cljs-test`, so `:node-test` and `:browser-test` do
//      not select them, and nothing in the tree requires either one, so these
//      two rows are the whole of their compile coverage. A stated
//      roster, and each row checked against disk: a moved, renamed or
//      re-namespaced file REDS the gate rather than dropping out of it.
//
// ## The build
//
// `:fresco-modules-compile` in `implementation/shadow-cljs.edn` — a plain
// `:browser` module with `:infer-externs :auto` and `:warnings-as-errors`,
// entries merged in through `--config-merge`, which shadow-cljs deep-merges
// into the build, so the flag holds in both passes below and a warning
// arrives here as a nonzero exit. `:browser` is MEASURED rather than
// assumed: the instruments' closure reads DOM-element properties in core's
// `spine.cljs` that only Closure's browser externs can infer, and the same
// rows under a `:node-script` id raise four `:infer-warning`s there. A dev
// `compile`, not a release: the classes closed here — a deleted def, a renamed
// require, a dropped arity, an undeclared var, an un-externable property — are
// resolved by the analyser before optimisation, and `:infer-warning` is bound
// in both modes (mutation-proved on the overlay shape above).
//
// CONTROL, re-run whenever this gate is touched: read a property no extern
// declares on an UNTAGGED parameter. In `impl/overlay.cljs`, rewrite
// `claim-anchor!`'s `(when anchor-id` as `(when (.-anchorNameBogus anchor-id)`
// -> exit 1, the `:infer-warning` raised as an error (`Cannot infer target
// type in expression (. anchor-id -anchorNameBogus)`); restore -> exit 0.
// Dropping the `^js` on `claim-anchor!`'s `el` is NOT a control: that `el` is
// bound from `(.getElementById js/document ...)`, which the analyser already
// types `js`, so the hint is redundant and the mutation reads 0 warnings.
//
// ## Limits
//
// Anything that COMPILES. This proves the modules and the instruments
// BUILD; it executes nothing, and for the two Node instruments it makes no
// claim that they run under Node.

const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const IMPL = path.resolve(__dirname, '../..');
const BUILD_ID = 'fresco-modules-compile';
const OUT_DIR = 'out/fresco-modules-compile';
const TAG = 'fresco-compile';

// The SAME roster again under `:advanced`. Two gates look as though they
// cover this and neither does: the pass above is a dev
// `compile`, and `npm run build:fresco-release` starts at the PUBLIC DOOR,
// which by construction cannot reach an optional module (the invariant
// `check_optional_module_reachability.py` enforces is exactly that no
// door-rooted graph contains one: the release build's
// entry `re-frame.fresco.consumer-app` requires `re-frame.core`,
// `re-frame.adapter.uix` and `re-frame.fresco`, and nothing reachable from
// the door requires `impl.overlay`). Without this pass the modules would be
// compiled twice and OPTIMISED never, and `:advanced` is where a different class lives from the
// analyser's: Closure renames a property no extern declares, DCE drops a
// binding reached only through interop, and an npm default-vs-namespace
// import mistake becomes `undefined` at runtime rather than a warning at
// compile time. `impl/overlay.cljs` reaches `react/useLayoutEffect`,
// `react/useContext` and `react/useRef` and writes `(.. el -style
// -anchorName)`, which is precisely that shape.
//
// A second pass rather than a second BUILD ID: the roster, the source paths
// and the refusals are already right here, and an id in the hot-zone
// `shadow-cljs.edn` would be one more thing to keep in step for no coverage
// this does not give. `goog.DEBUG false` mirrors the release build, so what
// is judged is the code a consumer would actually ship.
const RELEASE_OUT_DIR = 'out/fresco-modules-release';

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
const MODULE_ROSTER = 'fresco/scripts/check_optional_module_reachability.py';
const MODULE_ROSTER_FLAG = '--module-namespaces';

// The floor is a
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
// The build door. `:fresco-modules-compile` sets `:warnings-as-errors`, so
// shadow-cljs fails the compile on a warning itself, in both passes, and the
// exit status is the whole verdict: there is no output left to parse.
// ---------------------------------------------------------------------------

/** Build `buildId` through shadow-cljs's own JS entry-point; any failure ends the process. */
function shadowBuild({ impl, mode, buildId, configMerge, tag }) {
  const runner = path.join(impl, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const args = [runner, mode, buildId];
  if (configMerge) args.push('--config-merge', configMerge);
  const r = spawnSync(process.execPath, args, { cwd: impl, stdio: 'inherit' });
  if (r.error || r.status !== 0) {
    const reason = r.error
      ? `could not run shadow-cljs: ${r.error.message}`
      : `shadow-cljs exited ${r.status ?? r.signal}`;
    console.error(`\n[${tag}] BUILD REFUSED — ${reason}`);
    process.exit(1);
  }
}

if (require.main === module) {
  const listOnly = process.argv.slice(2).includes('--list');
  const rehomed = verifyRoster(REHOMED_BENCH_ENTRIES);
  const modules = optionalModuleNamespaces();

  if (!modules.ok) {
    console.error(
      `[${TAG}] the optional-module entry source failed: ${modules.reason}. ` +
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
        `dropped out of:`,
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

  // The same roster under `:advanced`. See RELEASE_OUT_DIR above
  // for why this is not reachable from `build:fresco-release`.
  console.error(`[${TAG}] compiling the same ${namespaces.length} namespaces under :advanced -> ${RELEASE_OUT_DIR}`);

  const releaseConfigMerge =
    `{:output-dir "${RELEASE_OUT_DIR}" :asset-path "." ` +
    `:compiler-options {:optimizations :advanced :infer-externs :auto ` +
    `:closure-defines {goog.DEBUG false}} ` +
    `:modules {:main {:entries [${namespaces.join(' ')}]}}}`;

  shadowBuild({ impl: IMPL, mode: 'release', buildId: BUILD_ID, configMerge: releaseConfigMerge, tag: TAG });

  console.error(`[${TAG}] ok — and the same ${namespaces.length} namespaces optimised under :advanced with zero warnings`);
}

module.exports = {
  decideModuleNamespaces,
  optionalModuleNamespaces,
  namespaceOf,
  verifyRoster,
  MIN_MODULE_NAMESPACES,
  MODULE_ROSTER,
  MODULE_ROSTER_FLAG,
  REHOMED_BENCH_ENTRIES,
};
