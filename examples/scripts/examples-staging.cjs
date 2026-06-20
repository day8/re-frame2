/*
 * Shared staging helpers for the example runners (rf2-pdo5mx).
 *
 * Two scripts need to stage an example's hand-written `index.html` plus the
 * `examples/_shared/` design-system tree next to the compiled `main.js`:
 *
 *   - serve-and-run-adapter-smokes.cjs  (the adapter-smoke orchestrator)
 *   - serve-example.cjs                 (the standalone-example dev runner)
 *
 * The recursive copy + `_shared` fan-out logic used to live inline in the
 * orchestrator. It is hoisted here so the dev runner reuses the SAME hardened
 * staging rather than re-implementing an ad hoc copy. One source of truth for
 * "what lands in an example's output dir".
 *
 * This module ALSO derives the standalone-example manifest (build id ->
 * output dir + source folder + index.html) directly from shadow-cljs.edn, so
 * the dev runner has no hardcoded build->folder table that could drift. The
 * derivation reads shadow-cljs.edn ONLY (it is a hot-zone file — never edited
 * here), mirroring the read-only enumeration the compile gate already does.
 */

'use strict';

const fs = require('fs');
const path = require('path');

// __dirname is <repo>/examples/scripts. REPO_ROOT is <repo>.
const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const EXAMPLES_ROOT = path.join(REPO_ROOT, 'examples');
const SHARED_SRC = path.join(EXAMPLES_ROOT, '_shared');

// ---------------------------------------------------------------------------
// Recursive copy + _shared staging (hoisted from serve-and-run-adapter-smokes).
// ---------------------------------------------------------------------------

// Minimal recursive copy. Each file overwrites the destination
// unconditionally so repeat runs pick up re-compiled bundles.
function copyDirRecursive(src, dest) {
  fs.mkdirSync(dest, { recursive: true });
  for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, entry.name);
    const d = path.join(dest, entry.name);
    if (entry.isDirectory()) {
      copyDirRecursive(s, d);
    } else if (entry.isFile()) {
      fs.copyFileSync(s, d);
    }
  }
}

// Stage the shared design-system tree (one stylesheet + favicon + OG image)
// into `outDir/_shared`. The hand-written index.html files reference these via
// relative paths like `_shared/css/style.css`, so each example's output dir
// gets its own copy alongside main.js + index.html. No-op when _shared is
// absent (it always exists in-repo; the guard keeps this safe in a partial
// checkout).
function stageShared(outDir) {
  if (!fs.existsSync(SHARED_SRC)) return;
  copyDirRecursive(SHARED_SRC, path.join(outDir, '_shared'));
}

// ---------------------------------------------------------------------------
// Per-example static assets (rf2-cq6va5).
//
// `index.html` + `_shared` are the assets EVERY standalone example shares. A
// handful of examples additionally reference per-example static assets via flat
// (output-root-relative) hrefs / fetch URLs that the clean-stage boundary would
// otherwise drop:
//
//   - TodoMVC's `index.html` links `base.css` + `index.css` flat — the OFFICIAL
//     TodoMVC CSS packages, pinned in implementation/package.json and fetched
//     into node_modules/ by `npm install` (NOT vendored in-repo). Without them
//     the page renders unstyled.
//   - managed-http-counter `fetch`es `api/inc.json` on its success path — the
//     fixture lives colocated under the example source's `api/` folder. Without
//     it the success path 404s and the example exercises only its failure
//     branch.
//   - The RealWorld variants fall a nil/blank avatar back to `default-avatar.svg`
//     served from the app asset root (realworld_shared.avatar). The asset lives
//     colocated in each RealWorld source folder. Without it every fallback
//     avatar is a broken image.
//
// Each asset declares WHERE its source lives (`:src` = colocated under the
// example's source folder, the default; `:node-modules` = under
// implementation/node_modules) and the destination name under the output dir.
// Keyed by shadow-cljs build id (the colon-stripped coord, as listStandalone-
// Examples() returns). An example with no entry stages only index.html +
// _shared (the common case).
const PER_EXAMPLE_ASSETS = {
  'examples/todomvc': [
    { from: 'node-modules', src: 'todomvc-common/base.css', dest: 'base.css' },
    { from: 'node-modules', src: 'todomvc-app-css/index.css', dest: 'index.css' },
  ],
  'examples/managed-http-counter': [
    { from: 'src', src: path.join('api', 'inc.json'), dest: path.join('api', 'inc.json') },
  ],
  'examples/realworld': [
    { from: 'src', src: 'default-avatar.svg', dest: 'default-avatar.svg' },
  ],
  'examples/realworld-resources': [
    { from: 'src', src: 'default-avatar.svg', dest: 'default-avatar.svg' },
  ],
};

const NODE_MODULES_ROOT = path.join(IMPL_ROOT, 'node_modules');

// Resolve an asset spec's absolute SOURCE path. `:src` assets live under the
// example's own source folder (`entry.srcDir`); `:node-modules` assets live
// under implementation/node_modules.
function assetSourcePath(entry, asset) {
  const root = asset.from === 'node-modules' ? NODE_MODULES_ROOT : entry.srcDir;
  return path.join(root, asset.src);
}

// Stage each declared per-example static asset into the output dir, FAIL-LOUD
// on a missing source: the runner advertises these builds as runnable, so a
// silently-absent asset (an unstyled TodoMVC, a 404ing success path, a broken
// avatar) is a correctness bug, not a no-op. node_modules assets carry an
// `npm install` hint because they are fetched, not vendored. No-op for an
// example with no declared assets.
function stagePerExampleAssets(entry) {
  const assets = PER_EXAMPLE_ASSETS[entry.build];
  if (!assets) return;
  for (const asset of assets) {
    const srcPath = assetSourcePath(entry, asset);
    if (!fs.existsSync(srcPath)) {
      const hint =
        asset.from === 'node-modules'
          ? ` Run \`npm install\` in ${IMPL_ROOT} to fetch it.`
          : '';
      throw new Error(
        `stageExample: required asset for '${entry.build}' is missing: ${srcPath}.${hint}`,
      );
    }
    const destPath = path.join(entry.outDir, asset.dest);
    fs.mkdirSync(path.dirname(destPath), { recursive: true });
    fs.copyFileSync(srcPath, destPath);
  }
}

// ---------------------------------------------------------------------------
// Clean-stage boundary (rf2-bf4vdy).
//
// The examples + Story browser harnesses all serve the SHARED
// implementation/out/examples root and previously OVERLAID their staged
// fixtures onto it — copyDirRecursive only creates dirs + overwrites the files
// it touches, so a file that a PREVIOUS run staged (a retired _shared asset, an
// extra static file the manifest no longer declares, an old generated subtree)
// stayed under the served root and could satisfy a browser request the current
// source no longer produces — a stale-file false green.
//
// The Xray feature gate avoids this by owning a dedicated root it recreates
// wholesale (serve-and-run-xray-feature-gate.cjs cleanAndStageRoot —
// rmSync(OUT_ROOT) + mkdir). The examples/Story harnesses CANNOT do that: they
// share out/examples and a narrow run stages only SOME builds, so blindly
// deleting the whole shared root would wipe sibling outputs another harness (or
// a parallel narrow run) may rely on. Instead, clean only the SELECTED output
// dirs — recreating each from empty before staging — so every served file is
// produced from the CURRENT source each run, with no stale residue.
//
// Path-guarded recursive delete: the resolved target MUST live strictly UNDER
// `outRoot` (never equal to it, never an ancestor, never outside it). A target
// that fails the guard throws rather than deleting — recursive deletion can
// only ever touch a selected output dir under OUT_ROOT, never the shared root
// itself or anything outside it.
// ---------------------------------------------------------------------------

// True iff `child` is strictly contained under `parent` (a proper descendant,
// not `parent` itself). Both are resolved to absolute paths first.
function isStrictlyUnder(child, parent) {
  const c = path.resolve(child);
  const p = path.resolve(parent);
  if (c === p) return false;
  const rel = path.relative(p, c);
  return rel !== '' && !rel.startsWith('..') && !path.isAbsolute(rel);
}

// Remove + recreate each of `dirs` (empty), guarding that every target lives
// strictly UNDER `outRoot`. Returns the list of cleaned absolute dirs. Throws
// on a target that escapes the guard (so a misconfigured caller can never
// recursively delete the shared root or an out-of-tree path). A dir that does
// not yet exist is simply (re)created — the build may not have emitted into it
// on a first run.
function cleanStageDirs(dirs, outRoot, { io = fs } = {}) {
  const cleaned = [];
  for (const dir of dirs) {
    const target = path.resolve(dir);
    if (!isStrictlyUnder(target, outRoot)) {
      throw new Error(
        `cleanStageDirs: refusing to clean '${target}' — it is not strictly ` +
          `under the owned staging root '${path.resolve(outRoot)}'. ` +
          `Recursive deletion may only target a selected output dir under ` +
          `OUT_ROOT (rf2-bf4vdy).`,
      );
    }
    io.rmSync(target, { recursive: true, force: true });
    io.mkdirSync(target, { recursive: true });
    cleaned.push(target);
  }
  return cleaned;
}

// Stage one example's hand-written index.html + the _shared tree + any declared
// per-example static assets into its output dir. Creates the output dir if the
// build hasn't emitted into it yet (so the dev runner can stage before the first
// watch compile lands). Throws with the offending path when the HTML source —
// or any declared per-example asset — is missing (fail-loud, rf2-cq6va5).
//
// `entry` shape: { build, outDir, htmlSrc, srcDir } — as produced by
// listStandaloneExamples() (and compatible with the orchestrator's EXAMPLES
// entries, which also carry outDir + htmlSrc + srcDir).
function stageExample(entry) {
  if (!fs.existsSync(entry.htmlSrc)) {
    throw new Error(`HTML source missing: ${entry.htmlSrc}`);
  }
  fs.mkdirSync(entry.outDir, { recursive: true });
  fs.copyFileSync(entry.htmlSrc, path.join(entry.outDir, 'index.html'));
  stageShared(entry.outDir);
  stagePerExampleAssets(entry);
}

// ---------------------------------------------------------------------------
// Standalone-example manifest, derived from shadow-cljs.edn.
//
// We hand-roll a focused scan rather than pull in an EDN dependency — the only
// shapes we read are the `:examples/<name> { ... :output-dir "..." ...
// :init-fn <ns>/run }` build defs. This mirrors the parser style the compile
// gate (check-examples-compile.cjs) already uses on the same file.
// ---------------------------------------------------------------------------

function readShadowEdn() {
  return fs.readFileSync(path.join(IMPL_ROOT, 'shadow-cljs.edn'), 'utf8');
}

// Strip `;`-to-EOL line comments so a commented-out build def can't be read as
// live (same approach as check-examples-compile.cjs).
function stripEdnComments(edn) {
  return edn
    .split('\n')
    .map((line) => {
      const i = line.indexOf(';');
      return i === -1 ? line : line.slice(0, i);
    })
    .join('\n');
}

// Parse every `:examples/<name>` build def into { build, outputDir, initFn }.
// `build` is the colon-stripped coord shadow-cljs's CLI expects
// (`examples/counter-uix`); `outputDir` is the build's `:output-dir`; `initFn`
// is the `<ns>/run` symbol the build mounts.
//
// Line-based block scan: a build def opens with a two-space-indented
// `  :examples/<name>` key (the opening `{` is on the SAME or the FOLLOWING
// line in shadow-cljs.edn). Its body runs until the NEXT two-space top-level
// key (`  :something`) or EOF. This avoids the consume-the-delimiter bug a
// single non-greedy regex with a lookahead hits (it silently skips every
// other entry when the lookahead eats the next key's opening char).
function parseExampleBuilds(edn) {
  const lines = stripEdnComments(edn).split('\n');
  // Any two-space-indented keyword key ends the previous build's body.
  const topKeyRe = /^\s{2}:[\w.-]+(?:\/[\w.-]+)?\b/;
  const exampleKeyRe = /^\s{2}:(examples\/[\w.-]+)\s*$|^\s{2}:(examples\/[\w.-]+)\s*\{/;
  const builds = [];

  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(exampleKeyRe);
    if (!m) continue;
    const build = m[1] || m[2];
    // Gather the body: this line plus every following line until the next
    // two-space top-level key (a sibling build / map entry) or EOF.
    const bodyLines = [lines[i]];
    for (let j = i + 1; j < lines.length; j++) {
      if (topKeyRe.test(lines[j])) break;
      bodyLines.push(lines[j]);
    }
    const body = bodyLines.join('\n');
    const outMatch = body.match(/:output-dir\s+"([^"]+)"/);
    const initMatch = body.match(/:init-fn\s+([\w.\-]+\/[\w.\-]+)/);
    builds.push({
      build,
      outputDir: outMatch ? outMatch[1] : null,
      initFn: initMatch ? initMatch[1] : null,
    });
  }
  return builds;
}

// Locate the example source folder that declares `ns` by scanning the
// examples/ tree for a .cljs/.cljc file whose `(ns <ns> ...)` form matches.
// Returns the absolute folder path, or null. We cache a ns->folder index on
// first use so a multi-build run scans the tree once.
let _nsIndex = null;

function buildNsIndex(root = EXAMPLES_ROOT) {
  const index = new Map();
  const stack = [root];
  const nsRe = /\(ns\s+([\w.\-]+)/;
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
        if (entry.name === 'node_modules' || entry.name === '_shared') continue;
        stack.push(full);
      } else if (entry.isFile() && /\.clj[sc]$/.test(entry.name)) {
        let head;
        try {
          head = fs.readFileSync(full, 'utf8').slice(0, 4096);
        } catch {
          continue;
        }
        const m = head.match(nsRe);
        if (m && !index.has(m[1])) index.set(m[1], path.dirname(full));
      }
    }
  }
  return index;
}

function folderForNs(ns) {
  if (_nsIndex == null) _nsIndex = buildNsIndex();
  return _nsIndex.get(ns) || null;
}

// The set of standalone examples that are RUNNABLE by the dev server: a build
// def whose init-fn namespace resolves to an examples/ source folder that
// carries a hand-written index.html. Builds whose source lives outside
// examples/ (none today) or that lack a colocated index.html are skipped — the
// dev server can only serve a page that exists.
//
// Each returned entry: { build, outDir, htmlSrc, srcDir } — `outDir` is the
// absolute output dir (IMPL_ROOT-relative `:output-dir`), `htmlSrc` the
// colocated index.html, `srcDir` the source folder.
function listStandaloneExamples({ io = fs } = {}) {
  const edn = readShadowEdn();
  const out = [];
  for (const def of parseExampleBuilds(edn)) {
    if (!def.outputDir || !def.initFn) continue;
    const ns = def.initFn.replace(/\/[^/]+$/, ''); // strip `/run`
    const srcDir = folderForNs(ns);
    if (!srcDir) continue;
    const htmlSrc = path.join(srcDir, 'index.html');
    if (!io.existsSync(htmlSrc)) continue;
    out.push({
      build: def.build,
      outDir: path.resolve(IMPL_ROOT, def.outputDir),
      htmlSrc,
      srcDir,
    });
  }
  return out.sort((a, b) => a.build.localeCompare(b.build));
}

module.exports = {
  REPO_ROOT,
  IMPL_ROOT,
  EXAMPLES_ROOT,
  SHARED_SRC,
  NODE_MODULES_ROOT,
  PER_EXAMPLE_ASSETS,
  copyDirRecursive,
  stageShared,
  stagePerExampleAssets,
  stageExample,
  isStrictlyUnder,
  cleanStageDirs,
  readShadowEdn,
  stripEdnComments,
  parseExampleBuilds,
  listStandaloneExamples,
};
