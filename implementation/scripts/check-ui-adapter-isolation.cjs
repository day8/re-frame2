#!/usr/bin/env node
/*
 * re-frame.ui focused-build dependency isolation (G-12).
 *
 * The artefact `day8/re-frame2-ui` is the WRAPPER-FREE core: it must stay
 * independent of every view-stack wrapper AND of the four
 * `re-frame.adapter.<reagent|reagent-slim|uix|helix>` adapter namespaces. The
 * view stacks themselves are not all "retiring" — Reagent, UIx, and
 * reagent-slim remain SUPPORTED SIBLING artefacts (`day8/re-frame2-reagent`,
 * `day8/re-frame2-uix`, `day8/reagent-slim`); only Helix is retired. What the
 * gate forbids is any of those wrappers being baked into the wrapper-free core:
 * re-frame.ui pulls in NONE of them, so a consumer picks a substrate as a
 * separate sibling dependency. This is a bounded artefact-isolation contract —
 * NOT a denylist of "bad" libraries and NOT a general supply-chain policy.
 *
 * The property is proven along two arms.
 *
 * Arm 1 — compiler-selected module closure.
 *   `out/node-test-ui.js` is Shadow's loader for the focused `:node-test-ui`
 *   build. Its SHADOW_IMPORT rows are the compiler-computed transitive
 *   dependency closure, a stronger and more truthful boundary than a runtime
 *   assertion inside a `*-cljs-test` namespace (that same namespace is also
 *   loaded by the consolidated `:node-test` build, where the sibling adapters
 *   are intentionally present because their own tests still run).
 *
 *   The closure is rejected against namespace ROOTS, not only the four
 *   `re_frame.adapter.*` adapter roots. A DIRECT wrapper import such as
 *   `uix.core`, `reagent.core`, `reagent2.core` (reagent-slim's real module
 *   root — the Maven coord is `day8/reagent-slim` but the import paths are
 *   `reagent2.*`), or `helix.core` is munged to a module name (`uix.core.js`,
 *   `reagent2.core.js`, ...) that does NOT begin with `re_frame.adapter.*`, so
 *   a prefix-only check let it through. Listing the wrapper roots — INCLUDING
 *   `reagent2` — closes that false-green. Note: the adapter-NEUTRAL core
 *   namespaces `re_frame.adapter.context`, `re_frame.adapter.resource_lease`,
 *   and `re_frame.adapter.sub_override_context` are the shared substrate spine
 *   and are legitimately present — only the four VIEW-STACK adapter roots are
 *   forbidden.
 *
 * Arm 2 — resolved dependency graph.
 *   The module closure only sees code Closure SELECTED. A forbidden
 *   coordinate that the artefact DECLARES but does not yet use is invisible
 *   to Arm 1 (Closure never selects it, so no SHADOW_IMPORT row changes) yet
 *   still bloats every consumer's classpath. Arm 2 resolves the published
 *   `ui/deps.edn` graph with `clojure -Stree` and rejects any wrapper
 *   coordinate. A full pass additionally requires a POSITIVE control — the
 *   resolved graph must carry re-frame.ui's own core coordinate `day8/re-frame2`
 *   as a GENUINE resolved row (the coordinate followed by version/local-root
 *   evidence), not merely as a token string — so a vacuous exit-0 (empty /
 *   whitespace / malformed / core-absent stdout) OR token-shaped garbage (the
 *   bare coordinate, or the coordinate trailed by prose) fails closed instead of
 *   masquerading as a "0 resolved coordinates" PASS. Resolving the JVM/Clojure graph also proves
 *   the JVM/headless emitter entry stays browser-wrapper-free without forcing
 *   browser-only libraries onto the JVM classpath.
 *
 *   Arm 2 needs the Clojure CLI. A full G-12 pass REQUIRES Arm 2, so the
 *   default command FAILS CLOSED when `clojure` is unavailable or Arm 2 cannot
 *   execute — an absent/unverifiable toolchain is never a silent green. A
 *   reduced Arm-1-only run is available, but ONLY when explicitly requested
 *   with `--modules-only`, and it is reported as a reduced diagnostic, never
 *   as a full G-12 PASS.
 */

'use strict';

const fs = require('node:fs');
const path = require('node:path');

const repoRoot = path.resolve(__dirname, '..', '..');
const loaderPath = path.resolve(__dirname, '..', 'out', 'node-test-ui.js');
const uiArtefactDir = path.resolve(__dirname, '..', 'ui');
const requiredImport = 're_frame.ui.substrate.js';

// --- Arm 1: compiler-selected module closure -------------------------------

// Forbidden module-namespace roots: the four view-stack adapter namespaces PLUS
// the direct wrapper libraries they wrap. A direct `uix.core` / `reagent.core`
// / `reagent2.core` / `helix.core` import does not begin with
// `re_frame.adapter.*`; listing the bare wrapper roots is what a prefix-only
// check was missing. `reagent2` is the real module root of `day8/reagent-slim`
// (its Maven coord carries the brand; its import paths are `reagent2.*`) and is
// listed alongside stock Reagent's `reagent`.
const forbiddenModuleRoots = [
  're_frame.adapter.reagent',
  're_frame.adapter.reagent_slim',
  're_frame.adapter.uix',
  're_frame.adapter.helix',
  'reagent',
  'reagent2',
  'uix',
  'helix',
];

function importsFrom(loader) {
  return [...loader.matchAll(/SHADOW_IMPORT\("([^"]+)"\);/g)].map((match) => match[1]);
}

// A module name (e.g. `reagent2.core.js`) is forbidden when its namespace
// equals a forbidden root or sits under one on a dotted boundary. The boundary
// check keeps `reagent`/`reagent2`/`uix`/`helix` from matching an unrelated
// `re_frame.adapter.context.js`, keeps `reagent` from swallowing the distinct
// `reagent2` root, and keeps a lookalike (`reagent2foo.js`, `uixfoo.js`) clear.
function isForbiddenModule(importName) {
  const ns = importName.replace(/\.js$/, '');
  return forbiddenModuleRoots.some((root) => ns === root || ns.startsWith(`${root}.`));
}

function forbiddenModules(imports) {
  return imports.filter(isForbiddenModule);
}

// --- Arm 2: resolved dependency graph --------------------------------------

// Forbidden Maven/Clojars coordinates: the view-stack wrapper libraries and
// their adapter artefacts. `day8/re-frame2` (core) and `day8/re-frame2-ui`
// itself deliberately do NOT match. `day8/reagent-slim` is the reagent-slim
// coordinate (its import root is `reagent2.*`, caught by Arm 1).
const forbiddenCoordinatePatterns = [
  /^reagent\/reagent$/,
  /^day8\/reagent-slim$/,
  /^com\.pitch\/uix(\.|$)/,
  /^lilactown\/helix$/,
  /^day8\/re-frame2-(reagent(-slim)?|uix|helix)$/,
];

// Arm-2 POSITIVE CONTROL (rf2-xgfpq, hardened rf2-tutg8). An exit-0
// `clojure -Stree` only counts as trustworthy evidence that the graph actually
// resolved when re-frame.ui's own required core dependency, `day8/re-frame2`
// (declared `:local/root "../core"` in ui/deps.edn), appears as a GENUINE
// resolved-graph row — the coordinate followed by real version/local-root
// evidence — not merely as a token string. Requiring only the token's PRESENCE
// (rf2-xgfpq) still accepted token-shaped garbage: a bare `day8/re-frame2` with
// no evidence, or `day8/re-frame2 this-is-not-a-tree` where the coordinate is
// trailed by diagnostic prose, both exit 0 and satisfied a presence-only check.
// Requiring a plausible resolved ROW closes that, while empty, whitespace-only,
// malformed, and core-absent stdout still lack the row entirely. It is the
// resolved-graph analogue of Arm 1's `requiredImport` positive control.
const requiredCoordinate = 'day8/re-frame2';

// Each `clojure -Stree` line is `<glyphs?> group/artifact <version|path...>`.
// Strip the tree glyphs, take the first token as the coordinate.
function coordinatesFrom(treeText) {
  return treeText
    .split(/\r?\n/)
    .map((line) => line.replace(/^[.\s]+/, '').trim())
    .filter(Boolean)
    .map((line) => line.split(/\s+/)[0]);
}

// Does `treeText` carry a GENUINE resolved-graph row for `coordinate`? A real
// `clojure -Stree` row resolves a coordinate with nonempty evidence: a version
// (`1.12.4`, `v20250820`, a git SHA) or a local-root path (`../core`,
// `/repo/.../core`, `C:\...\core`). Structurally, every Maven/git version
// carries a digit and every resolved local-root carries a path separator, so a
// row is genuine when the tokens AFTER the coordinate contain a digit or a `/`
// or `\`. Token-shaped garbage — the coordinate alone, or trailed by prose like
// `this-is-not-a-tree` — carries neither and fails. This is the smallest
// invariant that separates a resolved row from a line that merely contains the
// coordinate string; it is deliberately NOT a dependency-tree parser (rf2-xgfpq
// fence).
function hasResolvedCoordinateRow(treeText, coordinate) {
  return treeText.split(/\r?\n/).some((line) => {
    const [coord, ...rest] = line.replace(/^[.\s]+/, '').trim().split(/\s+/);
    if (coord !== coordinate) return false;
    const evidence = rest.join(' ');
    return /[0-9]/.test(evidence) || /[\\/]/.test(evidence);
  });
}

function forbiddenCoordinates(treeText) {
  return coordinatesFrom(treeText).filter((coord) =>
    forbiddenCoordinatePatterns.some((re) => re.test(coord))
  );
}

// Pure existence probe: does a `clojure` executable file exist ANYWHERE on
// PATH (regardless of trust)? Mirrors the PATH + PATHEXT walk that
// resolveTrustedExe uses, but only stats candidates — it never executes, so it
// carries no hijack risk itself. This is the authoritative discriminator
// between the two failure modes of a `clojure` resolution: a candidate that
// exists but resolves untrusted (hijack — must hard-fail) versus no candidate
// at all (the CLI is genuinely absent from this environment).
function clojureOnPath() {
  const pathStr = process.env.PATH || process.env.Path || process.env.path || '';
  const dirs = pathStr.split(path.delimiter).filter(Boolean);
  const isWin = process.platform === 'win32';
  const exts = isWin
    ? [
        '',
        ...(process.env.PATHEXT || '.COM;.EXE;.BAT;.CMD')
          .split(';')
          .map((s) => s.trim())
          .filter(Boolean),
      ]
    : [''];
  for (const dir of dirs) {
    for (const ext of exts) {
      try {
        if (fs.statSync(path.join(dir, `clojure${ext}`)).isFile()) return true;
      } catch {
        /* not this candidate; keep scanning */
      }
    }
  }
  return false;
}

// Default trusted resolution of the `clojure` executable (rf2-wn4o1 /
// rf2-33vvc): a single TRUSTED absolute path OUTSIDE the workspace, gating the
// Windows command-hijack accident class. Split out so the self-test can inject
// a fake without a real toolchain or a real PATH walk.
function defaultResolveClojureExe() {
  const { resolveTrustedExe } = require(
    path.join(repoRoot, 'tools', 'mcp-conformance', 'lib', 'exec-safety.cjs')
  );
  return resolveTrustedExe('clojure', { workspaceRoot: repoRoot });
}

// Resolve the re-frame.ui artefact's dependency graph via `clojure -Stree`,
// returning one of three shapes for classifyArm2()/main() to interpret:
//
//   { skipped: true, reason }  — `clojure` is genuinely absent from this
//     environment (no candidate on PATH at all). Arm 2 cannot apply; the FULL
//     gate fails closed on this (Arm 2 is mandatory for a full pass), while an
//     explicit `--modules-only` run degrades to Arm 1 only. Never a silent
//     pass.
//   { error }                  — a hard failure: a `clojure` candidate exists
//     but resolves only to an untrusted/workspace path (the rf2-33vvc hijack
//     accident class), cross-spawn is not installed, or the spawn itself
//     failed. Always exit 2.
//   a cross-spawn result        — Arm 2 ran; `status`/`stdout` carry the graph.
//
// Hardened, shell-free spawn posture (mirroring
// scripts/test-mcp-conformance.cjs): resolve the bare name to a single TRUSTED
// absolute path OUTSIDE the workspace, then dispatch it via cross-spawn with an
// args ARRAY and NO shell — gating the Windows command-hijack accident class
// and avoiding the DEP0190 args-concatenation warning. cross-spawn is lazily
// required only when we will actually spawn.
//
// `deps` seams (all defaulted; the self-test injects them to exercise the
// absent-CLI, present-but-untrusted-CLI, and spawn-failure paths WITHOUT ever
// spawning a shell or touching a real toolchain):
//   deps.resolveExe()   -> trusted absolute path, or throws if none is trusted.
//   deps.onPath()       -> boolean: is a `clojure` candidate present at all?
//   deps.spawnSync(...)  -> cross-spawn.sync-shaped result.
function resolveDependencyTree(cwd, deps = {}) {
  const resolveExe = deps.resolveExe || defaultResolveClojureExe;
  const onPath = deps.onPath || clojureOnPath;

  let clojureExe;
  try {
    clojureExe = resolveExe();
  } catch (err) {
    // A trusted `clojure` could not be resolved. Distinguish the two cases:
    //   - genuinely absent  -> Arm 2 is N/A; the FULL gate fails closed on it,
    //     `--modules-only` degrades to Arm 1.
    //   - present-but-untrusted (a workspace-relative candidate existed) ->
    //     the hijack accident the trust check exists to catch; hard-fail.
    if (!onPath()) {
      return { skipped: true, reason: err.message };
    }
    return { error: err };
  }

  let spawnSync = deps.spawnSync;
  if (!spawnSync) {
    try {
      spawnSync = require('cross-spawn').sync;
    } catch (err) {
      if (err && err.code === 'MODULE_NOT_FOUND') {
        return {
          error: new Error(
            'cross-spawn is not installed — run `npm install`/`npm ci` in ' +
              'implementation/ first (it is the devDependency used for the ' +
              'shell-free clojure spawn).'
          ),
        };
      }
      throw err;
    }
  }

  return spawnSync(clojureExe, ['-Stree'], {
    cwd,
    encoding: 'utf8',
    maxBuffer: 16 * 1024 * 1024,
  });
}

// Interpret a resolveDependencyTree() result into an Arm-2 outcome. Pure — no
// I/O, no toolchain — so the self-test can drive every branch with synthetic
// results:
//   { outcome: 'unavailable', reason }        — clojure genuinely absent.
//   { outcome: 'error', tree }                — a hard failure (exit 2).
//   { outcome: 'no-graph', graphSize }        — exit 0 but the resolved graph is
//     empty / whitespace / malformed / carries no GENUINE `day8/re-frame2`
//     resolved row (missing entirely, or only token-shaped garbage), i.e. no
//     trustworthy Arm-2 evidence (exit 2).
//   { outcome: 'ran', leaks, graphSize }      — Arm 2 ran; inspect leaks.
function classifyArm2(tree) {
  if (tree.skipped) {
    return { outcome: 'unavailable', reason: tree.reason };
  }
  if (tree.error || tree.status !== 0 || typeof tree.stdout !== 'string') {
    return { outcome: 'error', tree };
  }
  // POSITIVE CONTROL (rf2-xgfpq, hardened rf2-tutg8). An exit-0 spawn alone is
  // NOT evidence the graph resolved: empty, whitespace-only, malformed, and
  // core-absent stdout all exit 0 yet carry no dependency tree — and a
  // presence-only check additionally let token-shaped garbage through (a bare
  // `day8/re-frame2`, or the coordinate trailed by prose). Require re-frame.ui's
  // own core dependency to appear as a GENUINE resolved row — the coordinate
  // plus version/local-root evidence — before accepting the arm. This single
  // invariant folds every vacuous-exit-0 and garbage-row shape into one
  // fail-closed outcome and never lets a "0 resolved coordinates" run (or a line
  // that merely contains the coordinate string) masquerade as a full G-12 PASS.
  const coordinates = coordinatesFrom(tree.stdout);
  if (!hasResolvedCoordinateRow(tree.stdout, requiredCoordinate)) {
    return { outcome: 'no-graph', graphSize: coordinates.length };
  }
  return {
    outcome: 'ran',
    leaks: forbiddenCoordinates(tree.stdout),
    graphSize: coordinates.length,
  };
}

// Read the focused loader's SHADOW_IMPORT closure. Split out (and injectable
// via main's io seam) so the self-test can drive main() end-to-end with a
// synthetic closure — no shadow-cljs build artefact required.
function readFocusedLoaderImports() {
  if (!fs.existsSync(loaderPath)) return { missing: true };
  return { imports: importsFrom(fs.readFileSync(loaderPath, 'utf8')) };
}

// --- self-test (hermetic; no build artefacts, no JVM, no shell) ------------

function withSilencedConsole(fn) {
  const saved = { log: console.log, warn: console.warn, error: console.error };
  console.log = console.warn = console.error = () => {};
  try {
    return fn();
  } finally {
    Object.assign(console, saved);
  }
}

function selfTest() {
  const fail = (msg) => {
    console.error(`[ui-adapter-isolation] self-test: ${msg}`);
    return 1;
  };

  // The legacy prefix-only check, reconstructed to prove the false-greens it
  // let through and that the strengthened arm now reds on them.
  const legacyPrefixes = [
    're_frame.adapter.reagent',
    're_frame.adapter.reagent_slim',
    're_frame.adapter.uix',
    're_frame.adapter.helix',
  ];
  const legacyForbidden = (imports) =>
    imports.filter((name) => legacyPrefixes.some((prefix) => name.startsWith(prefix)));

  const clean = [requiredImport, 're_frame.core.js', 're_frame.adapter.context.js'];
  const cleanTree = [
    'org.clojure/clojure 1.12.4',
    'day8/re-frame2 /repo/implementation/core',
    '  . org.clojure/clojurescript 1.12.145',
  ].join('\n');

  // --- Arm 1 (module closure) ---------------------------------------------

  // Clean closure (incl. the legitimate adapter-neutral spine module) passes.
  if (forbiddenModules(clean).length !== 0) return fail('clean closure was rejected');

  // Retiring adapter module is rejected.
  if (!forbiddenModules([...clean, 're_frame.adapter.uix.js']).includes('re_frame.adapter.uix.js')) {
    return fail('forbidden adapter module was not detected');
  }

  // Direct wrapper module is rejected — the leak a prefix-only check MISSED.
  const uixLeak = [...clean, 'uix.core.js'];
  if (legacyForbidden(uixLeak).length !== 0) {
    return fail('legacy control invalid (prefix check should be blind to uix.core.js)');
  }
  if (!forbiddenModules(uixLeak).includes('uix.core.js')) {
    return fail('direct wrapper module was not detected');
  }

  // reagent-slim's REAL module root `reagent2.*` is rejected — the central
  // false-green this bead closes (rf2-o3a93). Stock Reagent's `reagent.core.js`
  // was already caught; `reagent2.core.js` slipped through because `reagent2`
  // does not sit under the `reagent` root. Prove: caught by stock reagent,
  // MISSED by the pre-fix roots, now caught.
  if (!forbiddenModules(['reagent.core.js']).includes('reagent.core.js')) {
    return fail('stock reagent module root regressed');
  }
  for (const mod of ['reagent2.core.js', 'reagent2.ratom.js', 'reagent2.dom.client.js']) {
    if (legacyForbidden([mod]).length !== 0) {
      return fail(`legacy control invalid (prefix check should be blind to ${mod})`);
    }
    if (!forbiddenModules([mod]).includes(mod)) {
      return fail(`reagent-slim real module root ${mod} was not detected`);
    }
  }

  // Exact-boundary matching — lookalikes stay green (no `reagent2` swallowing
  // `reagent2foo`, no `reagent` swallowing `reagent2`).
  for (const lookalike of ['reagent2foo.core.js', 'reagentx.core.js', 'uixfoo.js', 'helixir.js']) {
    if (forbiddenModules([lookalike]).length !== 0) {
      return fail(`lookalike ${lookalike} was wrongly rejected (boundary match broken)`);
    }
  }

  // --- Arm 2 (resolved coordinate graph) ----------------------------------

  if (forbiddenCoordinates(cleanTree).length !== 0) return fail('clean dependency graph was rejected');

  // An unused forbidden coordinate (Closure never selects it) reds.
  if (!forbiddenCoordinates(`${cleanTree}\n  com.pitch/uix.core 1.4.4`).includes('com.pitch/uix.core')) {
    return fail('forbidden coordinate was not detected');
  }

  // The full wrapper denylist is covered.
  const everyWrapper = [
    'reagent/reagent 2.0.1',
    'day8/reagent-slim /repo/implementation/adapters/reagent-slim',
    'lilactown/helix 0.2.2',
    'day8/re-frame2-uix 0.0.1',
  ].join('\n');
  const wrapperCoords = forbiddenCoordinates(everyWrapper);
  if (wrapperCoords.length !== 4) {
    console.error(`  detected: ${wrapperCoords.join(', ') || '(none)'}`);
    return fail('wrapper coordinate denylist incomplete');
  }

  // --- Subprocess resolution fixtures (no shell, no toolchain) -------------
  // The three `clojure` resolution outcomes, driven with injected fakes.

  // (a) absent CLI: nothing on PATH -> Arm 2 is N/A (skipped), NOT an error.
  const absent = resolveDependencyTree('/x', {
    resolveExe: () => {
      throw new Error('resolveTrustedExe: could not find "clojure" on PATH.');
    },
    onPath: () => false,
  });
  if (!absent.skipped || absent.error) return fail('absent CLI must classify as skipped, not error');

  // (b) present-but-untrusted CLI: a candidate exists but resolves untrusted
  // (workspace-relative hijack) -> HARD error, never a skip.
  const untrusted = resolveDependencyTree('/x', {
    resolveExe: () => {
      throw new Error('resolveTrustedExe: no candidate for "clojure" could be trusted.');
    },
    onPath: () => true,
  });
  if (untrusted.skipped || !untrusted.error) {
    return fail('present-but-untrusted CLI must classify as error, not skipped');
  }

  // (c) spawn failure: a trusted path resolves but the spawn itself fails
  // (ENOENT / spawn error) -> error result, mapped to a hard fail.
  const spawnFail = resolveDependencyTree('/x', {
    resolveExe: () => '/host/bin/clojure',
    spawnSync: () => ({ error: Object.assign(new Error('spawn ENOENT'), { code: 'ENOENT' }), status: null }),
  });
  if (!spawnFail.error) return fail('spawn failure must surface an error result');
  if (classifyArm2(spawnFail).outcome !== 'error') return fail('spawn-failure result must classify as error');

  // classifyArm2 branch coverage.
  if (classifyArm2({ skipped: true, reason: 'absent' }).outcome !== 'unavailable') {
    return fail('classifyArm2 must map skipped -> unavailable');
  }
  if (classifyArm2({ status: 1, stdout: '' }).outcome !== 'error') {
    return fail('classifyArm2 must map non-zero status -> error');
  }
  if (classifyArm2({ status: 0, stdout: cleanTree }).outcome !== 'ran') {
    return fail('classifyArm2 must map clean tree -> ran');
  }

  // POSITIVE CONTROL (rf2-xgfpq, hardened rf2-tutg8) — every exit-0 stdout shape
  // that lacks a GENUINE `day8/re-frame2` resolved row maps to `no-graph`, NOT
  // `ran`. That covers the vacuous shapes (empty / whitespace-only / malformed /
  // nonempty-but-core-absent) AND — the rf2-tutg8 teeth — token-shaped garbage
  // where the coordinate is PRESENT but not a real row: the bare coordinate with
  // no evidence, and the coordinate trailed by diagnostic prose. A
  // presence-only check accepted the last two as `ran`; the resolved-row
  // invariant reds them. Reverting to presence-only restores the false-green and
  // fails these assertions.
  for (const [label, stdout] of [
    ['empty', ''],
    ['whitespace-only', '   \n\t\n  '],
    ['malformed garbage', 'this is not a -Stree dependency tree'],
    ['core-absent nonempty', 'org.clojure/clojure 1.12.4\nsome.other/lib 1.0.0'],
    ['bare core token (no evidence)', 'day8/re-frame2'],
    ['core token + diagnostic prose', 'day8/re-frame2 this-is-not-a-tree'],
    ['core token as substring of prose line', 'resolved day8/re-frame2 not a tree'],
  ]) {
    const outcome = classifyArm2({ status: 0, stdout }).outcome;
    if (outcome !== 'no-graph') {
      return fail(`classifyArm2 must map ${label} exit-0 stdout -> no-graph (got ${outcome})`);
    }
  }
  // A GENUINE resolved row for the positive control is `ran` — whether the
  // evidence is a version, a POSIX local-root path, or a Windows local-root
  // path (the real UI graph resolves `day8/re-frame2` to its `../core` local
  // root, printed as an absolute path), and still `ran` with extra rows around
  // it. These pin that the tightened invariant does not red the real graph.
  for (const [label, stdout] of [
    ['version-form row', 'day8/re-frame2 1.2.3'],
    ['posix local-root row', 'day8/re-frame2 /repo/implementation/core'],
    ['windows local-root row', 'day8/re-frame2 C:\\Users\\me\\code\\core'],
    ['glyph-nested version row', 'org.clojure/clojure 1.12.4\n  . day8/re-frame2 1.2.3'],
    ['core positive control among extra rows', `${cleanTree}\n  some.other/lib 1.0.0`],
  ]) {
    if (classifyArm2({ status: 0, stdout }).outcome !== 'ran') {
      return fail(`classifyArm2 must accept a genuine ${label} as ran`);
    }
  }

  // --- End-to-end gate decisions (main() with injected IO) -----------------
  // Prove the previously-false-green conditions now go RED, and that genuinely
  // passing conditions stay green. Console is silenced for the intentional
  // red-path probes so the self-test output stays clean.

  const runMain = (argv, io) => withSilencedConsole(() => main(argv, io));

  // MUTATION 1 — a direct `reagent2.core` import in the focused first-party UI
  // closure reds the gate (exit 1) EVEN WHEN no forbidden coordinate is
  // declared (Arm 2 graph is clean). This is the central mutation class the
  // old coordinate-only self-test never exercised.
  const reagent2Mutation = runMain([], {
    readLoaderImports: () => ({ imports: [requiredImport, 're_frame.core.js', 'reagent2.core.js'] }),
    resolveTree: () => ({ status: 0, stdout: cleanTree }),
  });
  if (reagent2Mutation !== 1) {
    return fail(`reagent2.core closure leak must red the gate (exit 1), got exit ${reagent2Mutation}`);
  }

  // MUTATION 2 — the default full command FAILS CLOSED (exit 2) when the
  // clojure CLI is unavailable, even with a clean Arm 1 closure. The old gate
  // printed PASS and exited 0 here.
  const missingCli = runMain([], {
    readLoaderImports: () => ({ imports: [requiredImport, 're_frame.core.js'] }),
    resolveTree: () => ({ skipped: true, reason: 'clojure absent' }),
  });
  if (missingCli !== 2) {
    return fail(`missing clojure CLI must fail closed (exit 2), got exit ${missingCli}`);
  }

  // A present-but-untrusted CLI also fails closed (exit 2) via main().
  const untrustedMain = runMain([], {
    readLoaderImports: () => ({ imports: [requiredImport, 're_frame.core.js'] }),
    resolveTree: () => ({ error: new Error('untrusted clojure candidate') }),
  });
  if (untrustedMain !== 2) {
    return fail(`present-but-untrusted CLI must fail closed (exit 2), got exit ${untrustedMain}`);
  }

  // MUTATION 3 — an exit-0 `clojure -Stree` whose stdout carries NO genuine
  // `day8/re-frame2` resolved row FAILS CLOSED (exit 2) even over a clean Arm 1
  // closure. That covers the vacuous shapes (empty / whitespace-only /
  // malformed / nonempty-but-core-absent) AND — the rf2-tutg8 teeth —
  // token-shaped garbage where the coordinate is present but not a real row (the
  // bare coordinate, the coordinate trailed by prose, the coordinate as a mere
  // substring of a prose line). Before rf2-xgfpq the vacuous shapes printed a
  // full `PASS (... 0 resolved coordinates)` and exited 0; after rf2-xgfpq but
  // before rf2-tutg8 the token-shaped-garbage shapes still exited 0 — the exact
  // false-green this bead closes. A mutation that re-accepts either as a pass
  // fails.
  for (const [label, stdout] of [
    ['empty', ''],
    ['whitespace-only', '   \n\t\n  '],
    ['malformed garbage', 'this is not a -Stree dependency tree'],
    ['core-absent nonempty', 'org.clojure/clojure 1.12.4\nsome.other/lib 1.0.0'],
    ['bare core token (no evidence)', 'day8/re-frame2'],
    ['core token + diagnostic prose', 'day8/re-frame2 this-is-not-a-tree'],
    ['core token as substring of prose line', 'resolved day8/re-frame2 not a tree'],
  ]) {
    const exit = runMain([], {
      readLoaderImports: () => ({ imports: [requiredImport, 're_frame.core.js'] }),
      resolveTree: () => ({ status: 0, stdout }),
    });
    if (exit !== 2) {
      return fail(`exit-0 graph with no genuine core row (${label}) must fail closed (exit 2), got exit ${exit}`);
    }
  }

  // CONTROL — a forbidden wrapper COORDINATE still reds the gate (exit 1) when
  // the positive control IS present (the real graph always resolves the core
  // coordinate alongside any leak). Proves the new invariant tightens the
  // vacuous-exit-0 path WITHOUT masking a genuine Arm-2 coordinate leak.
  const coordinateLeak = runMain([], {
    readLoaderImports: () => ({ imports: [requiredImport, 're_frame.core.js'] }),
    resolveTree: () => ({ status: 0, stdout: `${cleanTree}\n  com.pitch/uix.core 1.4.4` }),
  });
  if (coordinateLeak !== 1) {
    return fail(`forbidden coordinate with the positive control present must red the gate (exit 1), got exit ${coordinateLeak}`);
  }

  // CONTROL — an explicit `--modules-only` run over a clean Arm 1 closure exits
  // 0 (green) WITHOUT invoking Arm 2 (resolveTree throws if touched), and is
  // NOT reported as a full G-12 pass.
  const modulesOnly = runMain(['--modules-only'], {
    readLoaderImports: () => ({ imports: [requiredImport, 're_frame.core.js'] }),
    resolveTree: () => {
      throw new Error('modules-only must not invoke Arm 2');
    },
  });
  if (modulesOnly !== 0) {
    return fail(`explicit --modules-only over a clean closure must exit 0, got exit ${modulesOnly}`);
  }

  // CONTROL — a genuinely clean full run (clean closure + clean graph) exits 0.
  const fullPass = runMain([], {
    readLoaderImports: () => ({ imports: clean }),
    resolveTree: () => ({ status: 0, stdout: cleanTree }),
  });
  if (fullPass !== 0) {
    return fail(`clean full run must pass (exit 0), got exit ${fullPass}`);
  }

  console.log(
    '[ui-adapter-isolation] self-test PASS (module-closure incl. reagent2 + resolved-graph ' +
      'controls red on synthetic leaks; vacuous exit-0 / token-shaped garbage / missing / ' +
      'untrusted clojure fails closed; positive control requires a GENUINE day8/re-frame2 ' +
      'resolved row; --modules-only is explicit)'
  );
  return 0;
}

// --- main ------------------------------------------------------------------

function main(argv, io = {}) {
  if (argv.includes('--self-test')) return selfTest();

  const modulesOnly = argv.includes('--modules-only');
  const readLoaderImports = io.readLoaderImports || readFocusedLoaderImports;
  const resolveTree = io.resolveTree || ((cwd) => resolveDependencyTree(cwd));

  // Arm 1 — compiler-selected module closure.
  const loader = readLoaderImports();
  if (loader.missing) {
    console.error(`[ui-adapter-isolation] missing focused loader: ${loaderPath}`);
    return 2;
  }
  const imports = loader.imports;
  if (imports.length === 0) {
    console.error('[ui-adapter-isolation] focused loader contains no SHADOW_IMPORT rows');
    return 2;
  }
  if (!imports.includes(requiredImport)) {
    console.error(`[ui-adapter-isolation] positive control missing: ${requiredImport}`);
    return 2;
  }
  const moduleLeaks = forbiddenModules(imports);

  // Arm 2 — resolved dependency graph. A full G-12 pass REQUIRES Arm 2; the
  // default command therefore fails closed if Arm 2 cannot run. `--modules-only`
  // is an EXPLICIT reduced diagnostic that skips Arm 2 entirely and is never
  // reported as a full pass.
  let coordinateLeaks = [];
  let arm2Ran = false;
  let graphSize = 0;
  if (!modulesOnly) {
    const arm2 = classifyArm2(resolveTree(uiArtefactDir));
    if (arm2.outcome === 'unavailable') {
      console.error(
        '[ui-adapter-isolation] G-12 FAIL-CLOSED: the Clojure CLI is unavailable, so the ' +
          'resolved-graph Arm 2 could not run — a full G-12 pass REQUIRES Arm 2. Provision the ' +
          'Clojure CLI, or re-run with --modules-only for an EXPLICIT Arm-1-only diagnostic ' +
          '(a reduced check, NOT a full G-12 pass).'
      );
      if (arm2.reason) console.error(`  (${String(arm2.reason).split('\n')[0]})`);
      return 2;
    }
    if (arm2.outcome === 'error') {
      console.error(
        `[ui-adapter-isolation] could not resolve ${uiArtefactDir} dependency graph (clojure -Stree)`
      );
      const t = arm2.tree || {};
      if (t.stderr) console.error(String(t.stderr).trim());
      if (t.error) console.error(String(t.error.message || t.error));
      return 2;
    }
    if (arm2.outcome === 'no-graph') {
      console.error(
        '[ui-adapter-isolation] G-12 FAIL-CLOSED: `clojure -Stree` exited 0 but its resolved ' +
          `graph carries no genuine resolved row for the ${requiredCoordinate} core positive ` +
          `control (empty, malformed, or the coordinate present only as token-shaped garbage; ` +
          `${arm2.graphSize} coordinate(s) parsed). An exit-0 with no resolved ${requiredCoordinate} ` +
          'row (coordinate plus version/local-root evidence) is NOT trustworthy Arm-2 evidence and ' +
          `must never report a full pass — investigate the ${uiArtefactDir} ui/deps.edn resolution ` +
          `(a clean run resolves ${requiredCoordinate} to its ../core local root).`
      );
      return 2;
    }
    coordinateLeaks = arm2.leaks;
    graphSize = arm2.graphSize;
    arm2Ran = true;
  } else {
    console.warn(
      '[ui-adapter-isolation] --modules-only: resolved-graph Arm 2 intentionally NOT run; this ' +
        'is a REDUCED Arm-1-only diagnostic, NOT a full G-12 pass.'
    );
  }

  let failed = false;
  if (moduleLeaks.length > 0) {
    console.error('[ui-adapter-isolation] view-stack adapter/wrapper code entered the focused UI module closure:');
    for (const name of moduleLeaks) console.error(`  ${name}`);
    failed = true;
  }
  if (coordinateLeaks.length > 0) {
    console.error('[ui-adapter-isolation] view-stack wrapper coordinate resolved in the re-frame.ui dependency graph:');
    for (const coord of coordinateLeaks) console.error(`  ${coord}`);
    failed = true;
  }
  if (failed) return 1;

  if (arm2Ran) {
    console.log(
      `[ui-adapter-isolation] PASS (${imports.length} compiler-selected imports, ${graphSize} resolved coordinates; wrapper-free)`
    );
  } else {
    console.log(
      `[ui-adapter-isolation] MODULES-ONLY OK (${imports.length} compiler-selected imports; Arm 1 ` +
        'enforced, resolved-graph Arm 2 NOT run — reduced diagnostic, NOT a full G-12 pass)'
    );
  }
  return 0;
}

if (require.main === module) {
  process.exitCode = main(process.argv.slice(2));
}

module.exports = {
  importsFrom,
  isForbiddenModule,
  forbiddenModules,
  coordinatesFrom,
  hasResolvedCoordinateRow,
  forbiddenCoordinates,
  clojureOnPath,
  resolveDependencyTree,
  classifyArm2,
  readFocusedLoaderImports,
  main,
  forbiddenModuleRoots,
  forbiddenCoordinatePatterns,
};
