#!/usr/bin/env node
/*
 * `story:build` — the Story static-export driver (rf2-8wgpm).
 *
 * Per tools/story/spec/013-Static-Build.md the invocation:
 *
 *   1. Releases the `:story-static/counter-with-stories` shadow-cljs
 *      build (`:advanced` compile, `:closure-defines` with
 *      `re-frame.story.config/static-mode?` true).
 *
 *   2. Stages
 *      examples/reagent/counter_with_stories/story_static.index.html as
 *      `index.html` next to the bundled `main.js` so the output
 *      directory is publishable as-is (the `:asset-path "."` means
 *      every cljs_base / cljs-runtime sibling resolves relative to the
 *      page, so the bundle works under any prefix).
 *
 *   3. Writes a `manifest.json` next to the bundle declaring the build
 *      target, the source ns, and the bundle's intent so consumers /
 *      downstream tooling can introspect without re-parsing the shadow
 *      output.
 *
 * This is the canonical sanity-test rig for the static-export
 * surface — the counter_with_stories example is the worked example
 * the rest of the docs site points at. Downstream consumers point
 * the same invocation at their own stories ns by:
 *
 *   - Adding a `:story-static/<their-app>` build to their shadow-cljs.edn
 *     (mirroring the entry below).
 *   - Adding their `<app>.story-static/run` ns that requires their
 *     stories ns + calls `(story/install-canonical-vocabulary!)` +
 *     `(story/mount-shell! (js/document.getElementById "app"))`.
 *   - Re-running this script with `STORY_BUILD_TARGET=<their-target>`.
 */

'use strict';

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

// __dirname is <repo>/implementation/scripts. IMPL_ROOT is
// <repo>/implementation; REPO_ROOT is <repo>.
const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');

// The build target the driver compiles. Override via STORY_BUILD_TARGET
// if the consumer has their own static-export build.
const BUILD_TARGET =
  process.env.STORY_BUILD_TARGET || 'story-static/counter-with-stories';

// Match shadow-cljs's output-dir convention — `out/<build-target>/`
// (note: shadow strips the leading `:` from the keyword and keeps the
// slash). The driver computes this from the build target so a custom
// target maps to a custom output directory automatically.
const OUTPUT_DIR =
  process.env.STORY_BUILD_OUTPUT_DIR ||
  path.join(IMPL_ROOT, 'out', BUILD_TARGET);

// The HTML source to stage as index.html. The canonical example is
// the counter_with_stories story_static.index.html; override for
// downstream apps.
const HTML_SRC =
  process.env.STORY_BUILD_INDEX_HTML ||
  path.join(
    REPO_ROOT,
    'examples',
    'reagent',
    'counter_with_stories',
    'story_static.index.html',
  );

function release() {
  const isWin = process.platform === 'win32';
  const cmd = isWin ? 'npx.cmd' : 'npx';
  const args = ['shadow-cljs', 'release', BUILD_TARGET];
  console.log(`> ${cmd} ${args.join(' ')}`);
  const result = spawnSync(cmd, args, {
    cwd: IMPL_ROOT,
    stdio: 'inherit',
    shell: isWin,
  });
  if (result.status !== 0) {
    throw new Error(`shadow-cljs release ${BUILD_TARGET} failed (exit ${result.status})`);
  }
}

function stageIndexHtml() {
  if (!fs.existsSync(OUTPUT_DIR)) {
    throw new Error(`Build output dir missing: ${OUTPUT_DIR}`);
  }
  if (!fs.existsSync(HTML_SRC)) {
    throw new Error(`HTML source missing: ${HTML_SRC}`);
  }
  const dest = path.join(OUTPUT_DIR, 'index.html');
  fs.copyFileSync(HTML_SRC, dest);
  console.log(`Staged ${HTML_SRC} -> ${dest}`);
}

function writeManifest() {
  const manifest = {
    name: 're-frame2-story static export',
    target: BUILD_TARGET,
    bead: 'rf2-8wgpm',
    spec: 'tools/story/spec/013-Static-Build.md',
    sourceHtml: path.relative(REPO_ROOT, HTML_SRC).split(path.sep).join('/'),
    builtAt: new Date().toISOString(),
    closureDefines: {
      're-frame.story.config/static-mode?': true,
    },
    publishable: true,
  };
  const dest = path.join(OUTPUT_DIR, 'manifest.json');
  fs.writeFileSync(dest, JSON.stringify(manifest, null, 2) + '\n');
  console.log(`Wrote manifest ${dest}`);
}

function summarise() {
  const entries = fs.readdirSync(OUTPUT_DIR);
  const sizes = entries.map((e) => {
    const p = path.join(OUTPUT_DIR, e);
    let size = 0;
    try {
      const stat = fs.statSync(p);
      if (stat.isFile()) size = stat.size;
    } catch (_) {
      /* ignore */
    }
    return { name: e, size };
  });
  console.log('');
  console.log(`Static-export output at ${OUTPUT_DIR}:`);
  for (const { name, size } of sizes) {
    console.log(`  ${name}${size ? ` (${size} bytes)` : ''}`);
  }
  console.log('');
  console.log('Serve locally to verify:');
  console.log(
    `  npx http-server "${OUTPUT_DIR.split(path.sep).join('/')}" -p 8040 -c-1 -s`,
  );
  console.log('  Then visit http://127.0.0.1:8040/');
}

function main() {
  release();
  stageIndexHtml();
  writeManifest();
  summarise();
}

try {
  main();
} catch (err) {
  console.error(err.message || err);
  process.exit(1);
}
