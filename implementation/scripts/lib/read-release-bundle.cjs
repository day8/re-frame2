// Shared release-bundle reader for the check-* scripts (rf2-qlk4w).
//
// shadow-cljs `:browser :release` writes the closure-compiled output as
// top-level *.js files in the configured `:output-dir` (e.g. `main.js`,
// `probe.js`, plus any sibling module files like `cljs_base.js`). All
// the builds these scripts measure are single-module, so the release
// artefact is exactly those top-level files.
//
// A previous dev-build `shadow-cljs compile` against the same output
// dir leaves a `cljs-runtime/` subdirectory of unoptimised dev sources;
// `shadow-cljs release` does NOT clean it (rf2-z9a06). Walking the
// output dir recursively (as the pre-rf2-qlk4w readers did) grep-ed
// those stale dev sources alongside the release artefact, producing
// false FAILs on dev-only sentinels. CI is unaffected because every
// CI run is on a clean dir; local repros after a dev compile tripped
// the trap. `check-reagent-slim-bundle-isolation.cjs` documented the trap
// inline and filtered to top-level; this module factors the same fix
// out so the four sibling scanners share one implementation.
//
// Two flavours:
//   readReleaseBlob(dir)     → concatenated string blob; null when
//                              `dir` doesn't exist. For substring /
//                              regex grep contracts.
//   listReleaseJsFiles(dir)  → array of absolute paths; null when
//                              `dir` doesn't exist. For per-file work
//                              (e.g. gzipped-size totalling).

const fs   = require('fs');
const path = require('path');

function listReleaseJsFiles(dir) {
  if (!fs.existsSync(dir)) {
    return null;
  }
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isFile() && entry.name.endsWith('.js')) {
      out.push(path.join(dir, entry.name));
    }
  }
  return out;
}

function readReleaseBlob(dir) {
  const files = listReleaseJsFiles(dir);
  if (files == null) {
    return null;
  }
  const out = [];
  for (const f of files) {
    out.push(fs.readFileSync(f, 'utf8'));
  }
  return out.join('\n');
}

module.exports = { readReleaseBlob, listReleaseJsFiles };
