#!/usr/bin/env node
/*
 * re-frame.ui focused-build dependency isolation (G-12).
 *
 * The artefact `day8/re-frame2-ui` is the WRAPPER-FREE core: it must stay
 * independent of every view-stack wrapper AND of the three
 * `re-frame.adapter.<reagent|reagent-slim|uix>` adapter namespaces. The
 * view stacks themselves are not "retiring" — Reagent, UIx, and
 * reagent-slim remain SUPPORTED SIBLING artefacts (`day8/re-frame2-reagent`,
 * `day8/re-frame2-uix`, `day8/reagent-slim`); only Helix was removed
 * (S7/W13, rf2-d6epb). What the
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
 *   The closure is rejected against namespace ROOTS, not only the
 *   `re_frame.adapter.*` adapter roots. A DIRECT wrapper import such as
 *   `uix.core`, `reagent.core`, or `reagent2.core` (reagent-slim's real module
 *   root — the Maven coord is `day8/reagent-slim` but the import paths are
 *   `reagent2.*`) is munged to a module name (`uix.core.js`,
 *   `reagent2.core.js`, ...) that does NOT begin with `re_frame.adapter.*`, so
 *   a prefix-only check let it through. Listing the wrapper roots — INCLUDING
 *   `reagent2` — closes that false-green. Note: the adapter-NEUTRAL core
 *   namespaces `re_frame.adapter.context` and
 *   `re_frame.adapter.sub_override_context` are the shared substrate spine
 *   and are legitimately present — only the VIEW-STACK adapter roots are
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
 *   as a GENUINE resolved row that RESOLVED TO THE CONFIGURED LOCAL ROOT: the
 *   coordinate at the HEAD of the row, followed by evidence naming the very
 *   directory `ui/deps.edn` declares for it (`{:local/root "../core"}`, i.e.
 *   `implementation/core`), compared as canonical paths. Anything else — a
 *   vacuous exit-0 (empty / whitespace / malformed / core-absent stdout),
 *   token-shaped garbage, or a plausibly-shaped coordinate summary that points
 *   somewhere ELSE (`C:\not\the\repo`, a numeric `404`, a Maven version) — fails
 *   closed instead of masquerading as a "0 resolved coordinates" PASS. Binding
 *   the row to the configured root is what makes this a control that CAN fail:
 *   shape alone only proves "some coordinate summary exists", never "the
 *   dependency under test resolved". Resolving the JVM/Clojure graph also proves
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

// Forbidden module-namespace roots: the three view-stack adapter namespaces PLUS
// the direct wrapper libraries they wrap. A direct `uix.core` / `reagent.core`
// / `reagent2.core` import does not begin with
// `re_frame.adapter.*`; listing the bare wrapper roots is what a prefix-only
// check was missing. `reagent2` is the real module root of `day8/reagent-slim`
// (its Maven coord carries the brand; its import paths are `reagent2.*`) and is
// listed alongside stock Reagent's `reagent`.
const forbiddenModuleRoots = [
  're_frame.adapter.reagent',
  're_frame.adapter.reagent_slim',
  're_frame.adapter.uix',
  'reagent',
  'reagent2',
  'uix',
];

function importsFrom(loader) {
  return [...loader.matchAll(/SHADOW_IMPORT\("([^"]+)"\);/g)].map((match) => match[1]);
}

// A module name (e.g. `reagent2.core.js`) is forbidden when its namespace
// equals a forbidden root or sits under one on a dotted boundary. The boundary
// check keeps `reagent`/`reagent2`/`uix` from matching an unrelated
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
  /^day8\/re-frame2-(reagent(-slim)?|uix)$/,
];

// Arm-2 POSITIVE CONTROL (rf2-xgfpq, hardened rf2-tutg8, anchored rf2-rbget,
// BOUND rf2-5e3ic). An exit-0 `clojure -Stree` only counts as trustworthy
// evidence that the graph actually resolved when re-frame.ui's own required core
// dependency, `day8/re-frame2`, appears as a resolved-graph row THAT RESOLVED TO
// THE ROOT ui/deps.edn CONFIGURES FOR IT. Each earlier round loosened one notch
// short of that: token PRESENCE (rf2-xgfpq) accepted a bare `day8/re-frame2` or
// the coordinate trailed by prose; "trailing text contains a digit or separator"
// (rf2-tutg8) accepted `... diagnostic-123`, `... this/is/not/a/tree`,
// `... error C404`; and matching the generic `ext/coord-summary` SHAPE
// (rf2-rbget) still accepted `day8/re-frame2 C:\not\the\repo` (any absolute
// path) and `day8/re-frame2 404` (any Maven-version-shaped text). Every one of
// those proves only that SOME coordinate summary exists — a misconfigured, or
// entirely absent, local root still reported success, so the control could not
// fail. Binding the row to the configured root is the whole point of the arm.
const requiredCoordinate = 'day8/re-frame2';

// The AUTHORITY for that binding. `implementation/ui/deps.edn` declares
//
//   :deps {day8/re-frame2 {:local/root "../core"}}
//
// resolved relative to the artefact dir, and tools.deps `ext/canonicalize
// :local` rewrites `:local/root` to `(.getCanonicalPath ...)` before the tree is
// printed — so the evidence half of the required row IS this directory. Real row
// from this repo (captured 2026-07-18, tools.deps 0.29.1598):
//
//   day8/re-frame2 C:\Users\me\code\re-frame2\implementation\core
//
// This mirrors the declared relative root rather than reading deps.edn (no EDN
// reader — rf2-5e3ic fence); the self-test pins the two together, so changing
// the declaration WITHOUT updating this constant reds the gate. That is
// deliberate: switching the core dependency to a Maven or git coordinate is a
// change of coordinate KIND and must be made intentionally here, not absorbed
// silently by a permissive matcher.
const requiredCoordinateDeclaredRoot = '../core';
const requiredCoordinateLocalRoot = path.resolve(uiArtefactDir, requiredCoordinateDeclaredRoot);

// Each `clojure -Stree` line is `<glyphs?> group/artifact <version|path...>`.
// Strip the tree glyphs, take the first token as the coordinate.
function coordinatesFrom(treeText) {
  return treeText
    .split(/\r?\n/)
    .map((line) => line.replace(/^[.\s]+/, '').trim())
    .filter(Boolean)
    .map((line) => line.split(/\s+/)[0]);
}

// `clojure -Stree` prints each row through `clojure.tools.deps.tree/print-node`,
// whose payload is exactly `(ext/coord-summary lib coord)` — i.e.
// `"<lib> <evidence>"`. The evidence half comes from the procurer that resolved
// the coordinate, and for a `:local/root` dependency that is
// `extensions/local.clj` -> `(str lib " " (:local/root coord))`, printed AFTER
// canonicalization. So for THIS coordinate the evidence is not merely
// path-SHAPED, it is one exact directory: `requiredCoordinateLocalRoot`.
//
// Note the two coordinate kinds NOT accepted here. `day8/re-frame2` is a
// `:local/root` dependency, so a Maven-version row (`day8/re-frame2 1.2.3`) or a
// git tag/SHA row (`day8/re-frame2 a1b2c3d`) for it does not describe the
// configured graph and reds — which is also what rejects the numeric `404`
// diagnostic spoof, since it entered through the generic Maven branch. Should
// the core dependency ever be re-declared as a Maven or git coordinate, this
// predicate is the intended place to say so.
//
// Path comparison is canonical, not textual, because the gate runs on POSIX CI
// and on Windows locally: Java's `getCanonicalPath` resolves symlinks/junctions
// and Node's `path.resolve` does not, so BOTH sides go through `realpathSync`
// where the directory exists (a synthetic fixture path simply stays as written).
// Separators are then unified and drive-lettered paths lower-cased, because
// Windows paths are case-insensitive and may legitimately arrive with either
// separator. POSIX comparison stays case-SENSITIVE. The tail is never
// constrained: a canonical root may contain spaces (`C:\Program Files\...`).
function canonicalizeExisting(p) {
  try {
    return fs.realpathSync(p);
  } catch {
    return p; // not present on this host (synthetic fixture) — compare as written
  }
}

function normalizeRootForCompare(p) {
  let s = String(p).trim().replace(/\\/g, '/');
  const unc = s.startsWith('//');
  s = s.replace(/\/+/g, '/');
  if (unc) s = `/${s}`;
  if (s.length > 1) s = s.replace(/\/+$/, '');
  if (/^[A-Za-z]:(\/|$)/.test(s)) s = s.toLowerCase();
  return s;
}

// Is `evidence` the SAME directory as the configured local root?
function isConfiguredLocalRoot(evidence, expectedLocalRoot) {
  const actual = normalizeRootForCompare(canonicalizeExisting(evidence));
  const expected = normalizeRootForCompare(canonicalizeExisting(expectedLocalRoot));
  return actual !== '' && actual === expected;
}

// The row furniture `print-node` may wrap around that payload, and nothing else:
// leading indent plus the `. ` glyph for a `:new-dep`/`:same-version`/
// `:newer-version` row, and — on a `:newer-version` row only — the reason keyword
// appended after the summary. (The `X `/`? ` glyph rows are NOT accepted as
// positive-control evidence: they mark a coordinate that was superseded, excluded,
// or omitted, i.e. NOT in the resolved classpath. Their top-level counterpart row
// is what carries the real evidence.)
const RESOLVED_ROW_PREFIX = /^\s*(?:\.\s+)?/;
const RESOLVED_ROW_REASON = /\s+:newer-version$/;

// Does `treeText` carry a resolved-graph row proving `coordinate` resolved to
// `expectedLocalRoot`? The row must BE one — the coordinate at the head, followed
// by evidence naming that exact directory. Matching the generic coord-summary
// SHAPE instead (the pre-rf2-5e3ic predicate) accepted any absolute path and any
// version-shaped text, so `day8/re-frame2 C:\not\the\repo` and `day8/re-frame2
// 404` both reported a full G-12 PASS: the control confirmed that a row
// MENTIONING the coordinate existed, never that the coordinate RESOLVED to the
// configured root. This stays a single required-row predicate — it does NOT parse
// the dependency tree, read EDN, or model coordinates generally (rf2-xgfpq /
// rf2-tutg8 / rf2-5e3ic fence).
function hasResolvedCoordinateRow(treeText, coordinate, expectedLocalRoot = requiredCoordinateLocalRoot) {
  const head = `${coordinate} `;
  return treeText.split(/\r?\n/).some((line) => {
    const row = line.replace(RESOLVED_ROW_PREFIX, '').trimEnd().replace(RESOLVED_ROW_REASON, '');
    if (!row.startsWith(head)) return false;
    return isConfiguredLocalRoot(row.slice(head.length).trim(), expectedLocalRoot);
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
//     empty / whitespace / malformed / carries no `day8/re-frame2` row resolved
//     to the configured local root (missing entirely, token-shaped garbage, or a
//     coordinate summary pointing elsewhere), i.e. no trustworthy Arm-2 evidence
//     (exit 2).
//   { outcome: 'ran', leaks, graphSize }      — Arm 2 ran; inspect leaks.
//
// `expectedLocalRoot` is defaulted to the configured root and injectable so the
// self-test can drive Windows- and POSIX-shaped fixtures on either host.
function classifyArm2(tree, expectedLocalRoot = requiredCoordinateLocalRoot) {
  if (tree.skipped) {
    return { outcome: 'unavailable', reason: tree.reason };
  }
  if (tree.error || tree.status !== 0 || typeof tree.stdout !== 'string') {
    return { outcome: 'error', tree };
  }
  // POSITIVE CONTROL (rf2-xgfpq, hardened rf2-tutg8, bound rf2-5e3ic). An exit-0
  // spawn alone is NOT evidence the graph resolved: empty, whitespace-only,
  // malformed, and core-absent stdout all exit 0 yet carry no dependency tree —
  // and looser checks additionally let token-shaped garbage, then any
  // plausibly-shaped coordinate summary, through. Require re-frame.ui's own core
  // dependency to appear as a row RESOLVED TO ITS CONFIGURED LOCAL ROOT before
  // accepting the arm. This single invariant folds every vacuous-exit-0,
  // garbage-row, and wrong-root shape into one fail-closed outcome, and never
  // lets a "0 resolved coordinates" run — or a row that merely mentions the
  // coordinate — masquerade as a full G-12 PASS.
  const coordinates = coordinatesFrom(tree.stdout);
  if (!hasResolvedCoordinateRow(tree.stdout, requiredCoordinate, expectedLocalRoot)) {
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
// The existence probe and the read are two syscalls, and the gap between them
// is the one place a moved bundle can still reach this gate (rf2-1wyyb).
// `npm run test:ui-isolation` compiles through `compile-node-test.cjs`, which
// UNLINKS `:output-to` before every compile so a failed one leaves nothing
// stale (rf2-6t03c) — so a second compile of `node-test-ui` starting in that
// gap deletes the loader after `existsSync` said it was there. An ENOENT
// escaping here would be an UNCAUGHT throw, and node exits 1 for that: the
// code this gate reserves for a real wrapper leak. That is the sibling gate's
// three-causes-one-verdict defect in miniature (rf2-v8561, PR #7608) — an
// ENVIRONMENT cause wearing the ISOLATION verdict's exit code. The bundle
// vanishing is the `missing` case however late it is noticed, so route it
// there and let main() report it as the setup error (exit 2) it is. Any OTHER
// read error (EACCES, EISDIR) is not this class and still throws.
function readFocusedLoaderImports() {
  if (!fs.existsSync(loaderPath)) return { missing: true };
  try {
    return { imports: importsFrom(fs.readFileSync(loaderPath, 'utf8')) };
  } catch (err) {
    if (err && err.code === 'ENOENT') return { missing: true };
    throw err;
  }
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
  ];
  const legacyForbidden = (imports) =>
    imports.filter((name) => legacyPrefixes.some((prefix) => name.startsWith(prefix)));

  const clean = [requiredImport, 're_frame.core.js', 're_frame.adapter.context.js'];

  // The gate must serve BOTH hosts it runs on — POSIX CI and Windows locally —
  // so the fixtures pin both path shapes and drive each against its own
  // configured root (`classifyArm2`/`main` take it injected). Neither synthetic
  // root exists on the running host, which is exactly the case
  // `canonicalizeExisting` leaves as-written. The real checkout's own root is
  // exercised separately below, on whichever platform is running.
  const POSIX_ROOT = '/repo/implementation/core';
  const WINDOWS_ROOT = 'C:\\proj\\re-frame2\\implementation\\core';

  const cleanTree = [
    'org.clojure/clojure 1.12.4',
    `day8/re-frame2 ${POSIX_ROOT}`,
    '  . org.clojure/clojurescript 1.12.145',
  ].join('\n');

  // `clojure -Stree` output from this repo's `implementation/ui` artefact
  // (captured 2026-07-18, tools.deps 0.29.1598). Reproduced row-for-row EXCEPT
  // the `day8/re-frame2` local root, which is re-rooted onto the neutral
  // `C:\proj\...` placeholder: that path is checkout-specific (every runner
  // resolves a different absolute path) and a personal home path may not be
  // committed — see scripts/check-no-hardcoded-paths.sh. The row SHAPE, which is
  // all the predicate keys on, is unchanged. This is the shape the anchored
  // positive control MUST keep accepting — an over-tightened predicate that reds
  // this would break the G-12 gate against the real graph.
  const realUiTree = [
    'org.clojure/clojure 1.12.4',
    '  . org.clojure/spec.alpha 0.5.238',
    '  . org.clojure/core.specs.alpha 0.4.74',
    `day8/re-frame2 ${WINDOWS_ROOT}`,
    '  . org.clojure/clojurescript 1.12.145',
    '    . com.google.javascript/closure-compiler v20250820',
    '    . org.clojure/google-closure-library 0.0-20250515-f04e4c0e',
    '      . org.clojure/google-closure-library-third-party 0.0-20250515-f04e4c0e',
    '    . com.cognitect/transit-java 1.0.362',
    '      . com.fasterxml.jackson.core/jackson-core 2.8.7',
    '      . org.msgpack/msgpack 0.6.12',
    '        . com.googlecode.json-simple/json-simple 1.1.1',
    '        . org.javassist/javassist 3.18.1-GA',
    '      . javax.xml.bind/jaxb-api 2.3.0',
    '    . org.clojure/tools.reader 1.3.6',
  ].join('\n');

  // The five prose spoofs rf2-rbget names: a matching FIRST TOKEN trailed by
  // junk that merely carries a digit, a `/`, or a `\`. Each satisfied the
  // rf2-tutg8 contains-a-character check and reported a full G-12 PASS.
  const proseSpoofs = [
    ['digit-bearing prose', 'day8/re-frame2 diagnostic-123'],
    ['relative path-shaped prose', 'day8/re-frame2 this/is/not/a/tree'],
    ['error prose with a digit', 'day8/re-frame2 error C404'],
    ['backslash-bearing prose', 'day8/re-frame2 nope\\x'],
    ['bracketed failure prose', 'day8/re-frame2 [FAILED 123]'],
  ];

  // rf2-5e3ic SPOOFS — evidence with a perfectly PLAUSIBLE coord-summary shape
  // that nevertheless does not identify the configured local root. Every one of
  // these classified as `ran` (full G-12 PASS) under the shape-only predicate:
  // a misconfigured — or entirely absent — local root reported success.
  const wrongRootSpoofs = [
    ['wrong Windows absolute root', 'day8/re-frame2 C:\\not\\the\\repo', WINDOWS_ROOT],
    ['numeric diagnostic text', 'day8/re-frame2 404', WINDOWS_ROOT],
    ['wrong POSIX absolute root', 'day8/re-frame2 /not/the/repo', POSIX_ROOT],
    ['sibling directory, not the root', 'day8/re-frame2 /repo/implementation/core-extras', POSIX_ROOT],
    ['parent of the configured root', 'day8/re-frame2 /repo/implementation', POSIX_ROOT],
    // POSIX paths are case-SENSITIVE; only drive-lettered paths fold case.
    ['POSIX case mismatch', 'day8/re-frame2 /repo/implementation/CORE', POSIX_ROOT],
    // `day8/re-frame2` is declared `:local/root`, so a Maven/git summary for it
    // describes a graph this artefact is not configured for. Re-declaring the
    // dependency as either kind is a deliberate change to make in the predicate.
    ['maven version row (coordinate kind changed)', 'day8/re-frame2 1.2.3', POSIX_ROOT],
    ['maven v-prefixed version row', 'day8/re-frame2 v20250820', POSIX_ROOT],
    ['git short-sha row (coordinate kind changed)', 'day8/re-frame2 a1b2c3d', POSIX_ROOT],
    ['git tag row', 'day8/re-frame2 v1.2.3', POSIX_ROOT],
  ];

  // MUTATION CONTROL (rf2-5e3ic). The pre-fix predicate, reconstructed: evidence
  // was accepted whenever it matched a generic coord-summary SHAPE — any Maven
  // version, any git SHA, any absolute path. Reverting the binding to this is the
  // regression these teeth exist to catch, so first prove the control is REAL by
  // showing the legacy shape check ACCEPTS the wrong-root spoofs the bound
  // predicate rejects. If a future edit makes these assertions pass under the
  // legacy shape too, the spoofs have stopped discriminating and the teeth are
  // no longer mutation-proof.
  const legacyShapeEvidence = (evidence) =>
    /^v?[0-9][0-9A-Za-z._+-]*$/.test(evidence) ||
    /^[0-9a-f]{7,40}$/.test(evidence) ||
    /^(?:\/|\\\\|[A-Za-z]:[\\/])\S/.test(evidence);
  const legacyAcceptsRow = (stdout) =>
    stdout.split(/\r?\n/).some((line) => {
      const row = line.replace(RESOLVED_ROW_PREFIX, '').trimEnd().replace(RESOLVED_ROW_REASON, '');
      const head = `${requiredCoordinate} `;
      return row.startsWith(head) && legacyShapeEvidence(row.slice(head.length).trim());
    });
  for (const [label, stdout] of wrongRootSpoofs) {
    if (!legacyAcceptsRow(stdout)) {
      return fail(`mutation control invalid: legacy shape check should accept "${label}" (it was the false-green)`);
    }
  }

  // The configured local root is DECLARED in ui/deps.edn; the predicate mirrors
  // that declaration as a constant rather than reading EDN. Pin the two together
  // so a change of the declared root (or of the coordinate KIND) cannot drift
  // past the gate silently — it reds here and must be updated deliberately.
  const uiDepsEdn = fs.readFileSync(path.join(uiArtefactDir, 'deps.edn'), 'utf8');
  const declaredRoot = /day8\/re-frame2\s+\{\s*:local\/root\s+"([^"]+)"\s*\}/.exec(uiDepsEdn);
  if (!declaredRoot) {
    return fail(
      `ui/deps.edn no longer declares ${requiredCoordinate} as a {:local/root ...} dependency — the ` +
        'Arm-2 positive control is bound to that coordinate kind and must be updated deliberately'
    );
  }
  if (declaredRoot[1] !== requiredCoordinateDeclaredRoot) {
    return fail(
      `ui/deps.edn declares ${requiredCoordinate} :local/root "${declaredRoot[1]}" but the positive ` +
        `control is bound to "${requiredCoordinateDeclaredRoot}"`
    );
  }

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
  for (const lookalike of ['reagent2foo.core.js', 'reagentx.core.js', 'uixfoo.js']) {
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
    'day8/re-frame2-uix 0.0.1',
  ].join('\n');
  const wrapperCoords = forbiddenCoordinates(everyWrapper);
  if (wrapperCoords.length !== 3) {
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
  if (classifyArm2({ status: 0, stdout: cleanTree }, POSIX_ROOT).outcome !== 'ran') {
    return fail('classifyArm2 must map clean tree -> ran');
  }

  // POSITIVE CONTROL (rf2-xgfpq, hardened rf2-tutg8, bound rf2-5e3ic) — every
  // exit-0 stdout that lacks a `day8/re-frame2` row resolved to the CONFIGURED
  // local root maps to `no-graph`, NOT `ran`. That covers the vacuous shapes
  // (empty / whitespace-only / malformed / nonempty-but-core-absent), the
  // rf2-tutg8 token-shaped garbage, and — the rf2-5e3ic teeth — evidence of
  // entirely plausible coord-summary SHAPE that points somewhere other than the
  // configured root. Loosening the predicate back to any of those stages
  // re-accepts these as `ran` and fails here.
  for (const [label, stdout, root = POSIX_ROOT] of [
    ['empty', ''],
    ['whitespace-only', '   \n\t\n  '],
    ['malformed garbage', 'this is not a -Stree dependency tree'],
    ['core-absent nonempty', 'org.clojure/clojure 1.12.4\nsome.other/lib 1.0.0'],
    ['bare core token (no evidence)', 'day8/re-frame2'],
    ['core token + diagnostic prose', 'day8/re-frame2 this-is-not-a-tree'],
    ['core token as substring of prose line', 'resolved day8/re-frame2 not a tree'],
    // rf2-rbget TEETH — a matching first token trailed by prose that merely
    // CONTAINS a digit / `/` / `\`. Reverting to the contains-a-character check
    // re-accepts every one of these as `ran` and fails here.
    ...proseSpoofs,
    // Also anchored: a coordinate whose evidence is a superseded/excluded `X `
    // row (not in the resolved classpath), and prose that merely starts with the
    // coordinate as a longer lib name.
    ['excluded X-glyph row only', `X day8/re-frame2 ${POSIX_ROOT} :excluded`],
    ['longer lib name, not the coordinate', `day8/re-frame2-extras ${POSIX_ROOT}`],
    // rf2-5e3ic TEETH — plausible coord-summary shape, wrong (or no) root.
    ...wrongRootSpoofs,
  ]) {
    const outcome = classifyArm2({ status: 0, stdout }, root).outcome;
    if (outcome !== 'no-graph') {
      return fail(`classifyArm2 must map ${label} exit-0 stdout -> no-graph (got ${outcome})`);
    }
  }
  // A row resolved to the CONFIGURED local root is `ran`. These pin the true
  // positive on BOTH hosts the gate runs on — the captured real
  // `implementation/ui` graph (Windows-shaped), a POSIX-shaped graph, and THIS
  // checkout's own root on whatever platform is running — plus the benign
  // variations a canonical path may legitimately arrive in. An over-tightened
  // predicate that reds any of these breaks G-12 against correct configurations,
  // which is worse than the false-green it replaced.
  for (const [label, stdout, root = POSIX_ROOT] of [
    ['REAL implementation/ui -Stree output (Windows-shaped)', realUiTree, WINDOWS_ROOT],
    ['posix local-root row', `day8/re-frame2 ${POSIX_ROOT}`],
    ['windows local-root row', `day8/re-frame2 ${WINDOWS_ROOT}`, WINDOWS_ROOT],
    // THE row this checkout's own `clojure -Stree` prints, on this host.
    ['this checkout\'s real configured root', `day8/re-frame2 ${requiredCoordinateLocalRoot}`, requiredCoordinateLocalRoot],
    // Windows paths are case-insensitive and accept either separator.
    ['windows root, folded case', 'day8/re-frame2 c:\\PROJ\\re-frame2\\implementation\\CORE', WINDOWS_ROOT],
    ['windows root, forward separators', 'day8/re-frame2 C:/proj/re-frame2/implementation/core', WINDOWS_ROOT],
    ['root with a trailing separator', `day8/re-frame2 ${POSIX_ROOT}/`],
    ['root containing a space', 'day8/re-frame2 C:\\Program Files\\re-frame2\\core', 'C:\\Program Files\\re-frame2\\core'],
    ['glyph-nested root row', `org.clojure/clojure 1.12.4\n  . day8/re-frame2 ${POSIX_ROOT}`],
    ['newer-version reason row', `org.clojure/clojure 1.12.4\n  . day8/re-frame2 ${POSIX_ROOT} :newer-version`],
    ['core positive control among extra rows', `${cleanTree}\n  some.other/lib 1.0.0`],
  ]) {
    if (classifyArm2({ status: 0, stdout }, root).outcome !== 'ran') {
      return fail(`classifyArm2 must accept a genuine ${label} as ran`);
    }
  }

  // --- Loader read under a mid-flight unlink (rf2-1wyyb) -------------------
  // The bundle moving underneath the gate is an ENVIRONMENT cause and must
  // never wear the ISOLATION verdict's exit code. Every other way that can
  // happen already lands on exit 2 (absent loader, zero SHADOW_IMPORT rows,
  // positive control missing); the ENOENT thrown by a `readFileSync` whose
  // file was unlinked after `existsSync` did not, because an uncaught throw
  // exits 1. Drive the interleaving directly — `existsSync` true, the file
  // gone one syscall later, which is exactly what a concurrent
  // `compile-node-test.cjs node-test-ui` produces (rf2-6t03c).
  const withStubbedLoaderRead = (readImpl, fn) => {
    const saved = { existsSync: fs.existsSync, readFileSync: fs.readFileSync };
    fs.existsSync = () => true;
    fs.readFileSync = readImpl;
    try {
      return fn();
    } finally {
      Object.assign(fs, saved);
    }
  };
  const throwing = (code) => () => {
    throw Object.assign(new Error(`${code}: synthetic loader read failure`), { code });
  };

  const unlinkedRead = withStubbedLoaderRead(throwing('ENOENT'), () => {
    try {
      return readFocusedLoaderImports();
    } catch (err) {
      return { threw: err };
    }
  });
  if (unlinkedRead.threw) {
    return fail(
      'a loader unlinked between existsSync and readFileSync must be reported as missing, not thrown ' +
        '(an uncaught ENOENT exits 1 — the wrapper-leak code — for an environment cause)'
    );
  }
  if (!unlinkedRead.missing) {
    return fail('a loader unlinked mid-read must classify as missing');
  }

  // Only THIS class is routed. A read error that does not mean "the bundle
  // moved" (EACCES, EISDIR) is not silently converted into a setup error.
  const otherRead = withStubbedLoaderRead(throwing('EACCES'), () => {
    try {
      return { value: readFocusedLoaderImports() };
    } catch (err) {
      return { threw: err };
    }
  });
  if (!otherRead.threw || otherRead.threw.code !== 'EACCES') {
    return fail('a non-ENOENT loader read error must still propagate, not be swallowed as missing');
  }

  // --- End-to-end gate decisions (main() with injected IO) -----------------
  // Prove the previously-false-green conditions now go RED, and that genuinely
  // passing conditions stay green. Console is silenced for the intentional
  // red-path probes so the self-test output stays clean.

  // Fixture graphs name synthetic roots, so main() is driven with the matching
  // configured root injected (POSIX-shaped by default; overridden per case).
  const runMain = (argv, io) =>
    withSilencedConsole(() => main(argv, { coreLocalRoot: POSIX_ROOT, ...io }));

  // rf2-1wyyb, end-to-end: a loader unlinked between `existsSync` and
  // `readFileSync` reaches main() as the setup error (exit 2) it is, NOT as a
  // leak accusation. `readLoaderImports` is deliberately NOT injected here —
  // this drives the real reader, which is where the ENOENT arises. Removing
  // that routing makes the throw uncaught, and node's exit 1 for an uncaught
  // throw is the code this gate reserves for a real wrapper leak.
  const unlinkedExit = withStubbedLoaderRead(throwing('ENOENT'), () =>
    runMain([], { resolveTree: () => ({ status: 0, stdout: cleanTree }) })
  );
  if (unlinkedExit !== 2) {
    return fail(`a loader unlinked mid-read must fail closed (exit 2), got exit ${unlinkedExit}`);
  }

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
  for (const [label, stdout, root = POSIX_ROOT] of [
    ['empty', ''],
    ['whitespace-only', '   \n\t\n  '],
    ['malformed garbage', 'this is not a -Stree dependency tree'],
    ['core-absent nonempty', 'org.clojure/clojure 1.12.4\nsome.other/lib 1.0.0'],
    ['bare core token (no evidence)', 'day8/re-frame2'],
    ['core token + diagnostic prose', 'day8/re-frame2 this-is-not-a-tree'],
    ['core token as substring of prose line', 'resolved day8/re-frame2 not a tree'],
    // rf2-rbget TEETH, end-to-end: on origin/main each of these injected exit-0
    // stdouts classified as `ran` and made main() print a full PASS.
    ...proseSpoofs,
    // rf2-5e3ic TEETH, end-to-end: likewise for plausible-shape/wrong-root
    // evidence — a misconfigured or absent local root printed a full PASS.
    ...wrongRootSpoofs,
  ]) {
    const exit = runMain([], {
      readLoaderImports: () => ({ imports: [requiredImport, 're_frame.core.js'] }),
      resolveTree: () => ({ status: 0, stdout }),
      coreLocalRoot: root,
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

  // POSITIVE CONTROL, end-to-end (rf2-rbget, rf2-5e3ic) — a graph whose core row
  // DID resolve to the configured root still passes, on both host path shapes and
  // on this checkout's own real root. These are the assertions that fail if the
  // bound predicate is ever over-tightened past what tools.deps actually emits: a
  // gate that reds on correct configurations is worse than the false-green.
  for (const [label, stdout, root] of [
    ['the captured real implementation/ui -Stree graph (Windows-shaped)', realUiTree, WINDOWS_ROOT],
    ['a POSIX-shaped resolved graph', cleanTree, POSIX_ROOT],
    [
      "this checkout's own configured root",
      `org.clojure/clojure 1.12.4\nday8/re-frame2 ${requiredCoordinateLocalRoot}`,
      requiredCoordinateLocalRoot,
    ],
  ]) {
    const exit = runMain([], {
      readLoaderImports: () => ({ imports: clean }),
      resolveTree: () => ({ status: 0, stdout }),
      coreLocalRoot: root,
    });
    if (exit !== 0) return fail(`${label} must pass (exit 0), got exit ${exit}`);
  }

  console.log(
    '[ui-adapter-isolation] self-test PASS (module-closure incl. reagent2 + resolved-graph ' +
      'controls red on synthetic leaks; vacuous exit-0 / token-shaped garbage / digit- and ' +
      'separator-bearing prose / plausible-shape WRONG-ROOT evidence all fail closed; missing / ' +
      'untrusted clojure fails closed; the positive control requires day8/re-frame2 to have ' +
      'resolved to the local root ui/deps.edn configures, and the real implementation/ui graph ' +
      'still passes on Windows- and POSIX-shaped roots; a loader unlinked mid-read is the setup ' +
      'error it is, never a leak; --modules-only is explicit)'
  );
  return 0;
}

// --- main ------------------------------------------------------------------

function main(argv, io = {}) {
  if (argv.includes('--self-test')) return selfTest();

  const modulesOnly = argv.includes('--modules-only');
  const readLoaderImports = io.readLoaderImports || readFocusedLoaderImports;
  const resolveTree = io.resolveTree || ((cwd) => resolveDependencyTree(cwd));
  const coreLocalRoot = io.coreLocalRoot || requiredCoordinateLocalRoot;

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
    const arm2 = classifyArm2(resolveTree(uiArtefactDir), coreLocalRoot);
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
          `graph carries no row showing the ${requiredCoordinate} core positive control resolved ` +
          `to its CONFIGURED local root (empty or malformed output, the coordinate absent, present ` +
          `only as token-shaped garbage, or resolved somewhere else; ${arm2.graphSize} ` +
          'coordinate(s) parsed). A row that merely mentions the coordinate is NOT trustworthy ' +
          `Arm-2 evidence and must never report a full pass — investigate the ${uiArtefactDir} ` +
          `ui/deps.edn resolution (a clean run resolves ${requiredCoordinate}, declared ` +
          `{:local/root "${requiredCoordinateDeclaredRoot}"}, to ${coreLocalRoot}).`
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
  isConfiguredLocalRoot,
  normalizeRootForCompare,
  requiredCoordinate,
  requiredCoordinateLocalRoot,
  forbiddenCoordinates,
  clojureOnPath,
  resolveDependencyTree,
  classifyArm2,
  readFocusedLoaderImports,
  main,
  forbiddenModuleRoots,
  forbiddenCoordinatePatterns,
};
