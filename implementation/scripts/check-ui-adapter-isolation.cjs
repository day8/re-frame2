#!/usr/bin/env node
/*
 * re-frame.ui focused-build dependency isolation (G-12).
 *
 * The artefact `day8/re-frame2-ui` must be wrapper-free: independent of the
 * retiring view stacks (stock Reagent, reagent-slim, UIx, Helix) AND of the
 * four retiring `re-frame.adapter.<reagent|reagent-slim|uix|helix>`
 * namespaces. This gate proves that property along two arms.
 *
 * Arm 1 — compiler-selected module closure.
 *   `out/node-test-ui.js` is Shadow's loader for the focused `:node-test-ui`
 *   build. Its SHADOW_IMPORT rows are the compiler-computed transitive
 *   dependency closure, a stronger and more truthful boundary than a runtime
 *   assertion inside a `*-cljs-test` namespace (that same namespace is also
 *   loaded by the consolidated `:node-test` build, where the retiring
 *   adapters are intentionally present because their own tests still run).
 *
 *   The closure is rejected against namespace ROOTS, not only the four
 *   `re_frame.adapter.*` adapter roots. A DIRECT wrapper import such as
 *   `uix.core`, `reagent.core`, or `helix.core` is munged to a module name
 *   (`uix.core.js`, ...) that does NOT begin with `re_frame.adapter.*`, so a
 *   prefix-only check let it through. Adding the wrapper roots closes that
 *   false-green. Note: the adapter-NEUTRAL core namespaces
 *   `re_frame.adapter.context`, `re_frame.adapter.resource_lease`, and
 *   `re_frame.adapter.sub_override_context` are the shared substrate spine
 *   and are legitimately present — only the four retiring VIEW-STACK adapter
 *   roots are forbidden.
 *
 * Arm 2 — resolved dependency graph.
 *   The module closure only sees code Closure SELECTED. A forbidden
 *   coordinate that the artefact DECLARES but does not yet use is invisible
 *   to Arm 1 (Closure never selects it, so no SHADOW_IMPORT row changes) yet
 *   still bloats every consumer's classpath. Arm 2 resolves the published
 *   `ui/deps.edn` graph with `clojure -Stree` and rejects any retiring
 *   wrapper coordinate. Resolving the JVM/Clojure graph also proves the
 *   JVM/headless emitter entry stays browser-wrapper-free without forcing
 *   browser-only libraries onto the JVM classpath.
 *
 * This is a bounded artefact-isolation contract for the known retiring view
 * stacks — NOT a general supply-chain policy framework.
 */

'use strict';

const fs = require('node:fs');
const path = require('node:path');

const repoRoot = path.resolve(__dirname, '..', '..');
const loaderPath = path.resolve(__dirname, '..', 'out', 'node-test-ui.js');
const uiArtefactDir = path.resolve(__dirname, '..', 'ui');
const requiredImport = 're_frame.ui.substrate.js';

// --- Arm 1: compiler-selected module closure -------------------------------

// Forbidden module-namespace roots: the four retiring adapter namespaces PLUS
// the direct wrapper libraries they wrap. A direct `uix.core` / `reagent.core`
// / `helix.core` import does not begin with `re_frame.adapter.*`; listing the
// bare wrapper roots is what a prefix-only check was missing.
const forbiddenModuleRoots = [
  're_frame.adapter.reagent',
  're_frame.adapter.reagent_slim',
  're_frame.adapter.uix',
  're_frame.adapter.helix',
  'reagent',
  'uix',
  'helix',
];

function importsFrom(loader) {
  return [...loader.matchAll(/SHADOW_IMPORT\("([^"]+)"\);/g)].map((match) => match[1]);
}

// A module name (e.g. `uix.core.js`) is forbidden when its namespace equals a
// forbidden root or sits under one on a dotted boundary. The boundary check
// keeps `reagent`/`uix`/`helix` from matching an unrelated
// `re_frame.adapter.context.js` and keeps a hypothetical `uixfoo.js` clear.
function isForbiddenModule(importName) {
  const ns = importName.replace(/\.js$/, '');
  return forbiddenModuleRoots.some((root) => ns === root || ns.startsWith(`${root}.`));
}

function forbiddenModules(imports) {
  return imports.filter(isForbiddenModule);
}

// --- Arm 2: resolved dependency graph --------------------------------------

// Forbidden Maven/Clojars coordinates: the retiring view-stack wrapper
// libraries and their adapter artefacts. `day8/re-frame2` (core) and
// `day8/re-frame2-ui` itself deliberately do NOT match.
const forbiddenCoordinatePatterns = [
  /^reagent\/reagent$/,
  /^day8\/reagent-slim$/,
  /^com\.pitch\/uix(\.|$)/,
  /^lilactown\/helix$/,
  /^day8\/re-frame2-(reagent(-slim)?|uix|helix)$/,
];

// Each `clojure -Stree` line is `<glyphs?> group/artifact <version|path...>`.
// Strip the tree glyphs, take the first token as the coordinate.
function coordinatesFrom(treeText) {
  return treeText
    .split(/\r?\n/)
    .map((line) => line.replace(/^[.\s]+/, '').trim())
    .filter(Boolean)
    .map((line) => line.split(/\s+/)[0]);
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
// at all (the CLI is genuinely absent from this environment — Arm 2 cannot
// apply here).
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

// Resolve the re-frame.ui artefact's dependency graph via `clojure -Stree`,
// returning one of three shapes for main() to interpret:
//
//   { skipped: true, reason }  — `clojure` is genuinely absent from this
//     environment (e.g. the node-test CI job sets up JDK + Node but no
//     setup-clojure). Arm 2 cannot apply; main() emits a LOUD notice and lets
//     Arm 1 (module closure) still pass/fail the gate. NOT a silent pass.
//   { error }                  — a hard failure that must exit 2: a `clojure`
//     candidate exists but resolves only to an untrusted/workspace path (the
//     rf2-33vvc hijack accident class), or cross-spawn is not installed.
//   a cross-spawn result        — Arm 2 ran; `status`/`stdout` carry the graph.
//
// Hardened, shell-free spawn posture (rf2-wn4o1 / rf2-33vvc, mirroring
// scripts/test-mcp-conformance.cjs): resolve the bare name to a single TRUSTED
// absolute path OUTSIDE the workspace, then dispatch it via cross-spawn with an
// args ARRAY and NO shell — gating the Windows command-hijack accident class
// and avoiding the DEP0190 args-concatenation warning. cross-spawn is lazily
// required only when we will actually spawn, so the absent-clojure path (and
// the hermetic `--self-test`) needs neither cross-spawn nor a toolchain.
function resolveDependencyTree(cwd) {
  const { resolveTrustedExe } = require(
    path.join(repoRoot, 'tools', 'mcp-conformance', 'lib', 'exec-safety.cjs')
  );

  let clojureExe;
  try {
    clojureExe = resolveTrustedExe('clojure', { workspaceRoot: repoRoot });
  } catch (err) {
    // A trusted `clojure` could not be resolved. Distinguish the two cases:
    //   - genuinely absent  -> Arm 2 is N/A here; degrade to Arm-1-only.
    //   - present-but-untrusted (a workspace-relative candidate existed) ->
    //     the hijack accident the trust check exists to catch; hard-fail.
    if (!clojureOnPath()) {
      return { skipped: true, reason: err.message };
    }
    return { error: err };
  }

  let crossSpawn;
  try {
    crossSpawn = require('cross-spawn');
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

  return crossSpawn.sync(clojureExe, ['-Stree'], {
    cwd,
    encoding: 'utf8',
    maxBuffer: 16 * 1024 * 1024,
  });
}

// --- self-test (hermetic; no build artefacts, no JVM) ----------------------

function selfTest() {
  // The legacy prefix-only check, reconstructed to prove the false-green it let
  // through and that the strengthened arm now reds on it.
  const legacyPrefixes = [
    're_frame.adapter.reagent',
    're_frame.adapter.reagent_slim',
    're_frame.adapter.uix',
    're_frame.adapter.helix',
  ];
  const legacyForbidden = (imports) =>
    imports.filter((name) => legacyPrefixes.some((prefix) => name.startsWith(prefix)));

  // Arm 1 — clean closure (incl. the legitimate adapter-neutral spine module).
  const clean = [requiredImport, 're_frame.core.js', 're_frame.adapter.context.js'];
  if (forbiddenModules(clean).length !== 0) {
    console.error('[ui-adapter-isolation] self-test: clean closure was rejected');
    return 1;
  }

  // Arm 1 (a) — retiring adapter module is rejected.
  const adapterLeak = [...clean, 're_frame.adapter.uix.js'];
  if (!forbiddenModules(adapterLeak).includes('re_frame.adapter.uix.js')) {
    console.error('[ui-adapter-isolation] self-test: forbidden adapter module was not detected');
    return 1;
  }

  // Arm 1 (b) — direct wrapper module is rejected. This is the leak a
  // prefix-only check MISSED: assert the legacy check is blind to it while the
  // strengthened check reds.
  const wrapperLeak = [...clean, 'uix.core.js'];
  if (legacyForbidden(wrapperLeak).length !== 0) {
    console.error('[ui-adapter-isolation] self-test: legacy control invalid (prefix check should be blind to uix.core.js)');
    return 1;
  }
  if (!forbiddenModules(wrapperLeak).includes('uix.core.js')) {
    console.error('[ui-adapter-isolation] self-test: direct wrapper module was not detected');
    return 1;
  }

  // Arm 2 — clean resolved graph passes; each forbidden coordinate reds.
  const cleanTree = [
    'org.clojure/clojure 1.12.4',
    'day8/re-frame2 /repo/implementation/core',
    '  . org.clojure/clojurescript 1.12.145',
  ].join('\n');
  if (forbiddenCoordinates(cleanTree).length !== 0) {
    console.error('[ui-adapter-isolation] self-test: clean dependency graph was rejected');
    return 1;
  }

  // Arm 2 (a) — an unused forbidden coordinate (Closure never selects it) reds.
  const coordLeak = `${cleanTree}\n  com.pitch/uix.core 1.4.4`;
  if (!forbiddenCoordinates(coordLeak).includes('com.pitch/uix.core')) {
    console.error('[ui-adapter-isolation] self-test: forbidden coordinate was not detected');
    return 1;
  }

  // Arm 2 (b) — the full retiring denylist is covered.
  const everyWrapper = [
    'reagent/reagent 2.0.1',
    'day8/reagent-slim /repo/implementation/adapters/reagent-slim',
    'lilactown/helix 0.2.2',
    'day8/re-frame2-uix 0.0.1',
  ].join('\n');
  const wrapperCoords = forbiddenCoordinates(everyWrapper);
  if (wrapperCoords.length !== 4) {
    console.error('[ui-adapter-isolation] self-test: retiring-wrapper coordinate denylist incomplete');
    console.error(`  detected: ${wrapperCoords.join(', ') || '(none)'}`);
    return 1;
  }

  console.log('[ui-adapter-isolation] self-test PASS (module-closure + resolved-graph controls red on synthetic leaks)');
  return 0;
}

// --- main ------------------------------------------------------------------

function main(argv) {
  if (argv.includes('--self-test')) return selfTest();

  let failed = false;

  // Arm 1 — compiler-selected module closure.
  if (!fs.existsSync(loaderPath)) {
    console.error(`[ui-adapter-isolation] missing focused loader: ${loaderPath}`);
    return 2;
  }
  const imports = importsFrom(fs.readFileSync(loaderPath, 'utf8'));
  if (imports.length === 0) {
    console.error('[ui-adapter-isolation] focused loader contains no SHADOW_IMPORT rows');
    return 2;
  }
  if (!imports.includes(requiredImport)) {
    console.error(`[ui-adapter-isolation] positive control missing: ${requiredImport}`);
    return 2;
  }
  const moduleLeaks = forbiddenModules(imports);

  // Arm 2 — resolved dependency graph. Three outcomes:
  //   - skipped: `clojure` genuinely absent from this environment (e.g. the
  //     node-test CI job has JDK + Node but no setup-clojure). Arm 2 cannot
  //     apply; emit a LOUD, auditable notice and let Arm 1 decide the gate.
  //     This is NOT a silent pass — Arm 1 still enforces isolation, and the
  //     resolved-graph arm runs wherever `clojure` IS present (local + any
  //     clojure-provisioned job).
  //   - error / non-zero: a hard failure (exit 2) — a workspace-relative
  //     `clojure` hijack candidate, a missing cross-spawn, or a `clojure`
  //     invocation error. An unverifiable-but-present toolchain is never a
  //     silent pass.
  //   - ran: inspect the resolved coordinates against the denylist.
  const tree = resolveDependencyTree(uiArtefactDir);
  let coordinateLeaks = [];
  let arm2Ran = false;
  if (tree.skipped) {
    console.warn(
      '[ui-adapter-isolation] G-12 Arm 2 skipped: clojure CLI not available in ' +
        'this environment; module-closure Arm 1 enforced'
    );
    if (tree.reason) {
      console.warn(`  (${String(tree.reason).split('\n')[0]})`);
    }
  } else if (tree.error || tree.status !== 0 || typeof tree.stdout !== 'string') {
    console.error(`[ui-adapter-isolation] could not resolve ${uiArtefactDir} dependency graph (clojure -Stree)`);
    if (tree.stderr) console.error(String(tree.stderr).trim());
    if (tree.error) console.error(String(tree.error.message || tree.error));
    return 2;
  } else {
    coordinateLeaks = forbiddenCoordinates(tree.stdout);
    arm2Ran = true;
  }

  if (moduleLeaks.length > 0) {
    console.error('[ui-adapter-isolation] retiring adapter/wrapper code entered the focused UI module closure:');
    for (const name of moduleLeaks) console.error(`  ${name}`);
    failed = true;
  }
  if (coordinateLeaks.length > 0) {
    console.error('[ui-adapter-isolation] retiring wrapper coordinate resolved in the re-frame.ui dependency graph:');
    for (const coord of coordinateLeaks) console.error(`  ${coord}`);
    failed = true;
  }
  if (failed) return 1;

  if (arm2Ran) {
    const graphSize = coordinatesFrom(tree.stdout).length;
    console.log(
      `[ui-adapter-isolation] PASS (${imports.length} compiler-selected imports, ${graphSize} resolved coordinates; wrapper-free)`
    );
  } else {
    console.log(
      `[ui-adapter-isolation] PASS (${imports.length} compiler-selected imports; ` +
        'module-closure Arm 1 enforced, resolved-graph Arm 2 skipped — clojure CLI absent)'
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
  forbiddenCoordinates,
  clojureOnPath,
  resolveDependencyTree,
  forbiddenModuleRoots,
  forbiddenCoordinatePatterns,
};
