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
// Two flavours of reader:
//   readReleaseBlob(dir)     → concatenated string blob; null when
//                              `dir` doesn't exist. For substring /
//                              regex grep contracts.
//   listReleaseJsFiles(dir)  → array of absolute paths; null when
//                              `dir` doesn't exist. For per-file work
//                              (e.g. gzipped-size totalling).
//
// Plus the grep primitives the sibling check-*-bundle scanners run
// against the blob. Each scanner used to carry its own verbatim copies
// of these (rf2-jkake.15 folded them here so the bundle-grep family
// shares one implementation):
//   escapeRe(s)              → escape a string for literal use in a
//                              RegExp.
//   countMatches(blob, re)   → number of global matches of a RegExp in
//                              the blob; 0 when blob is null. Resets the
//                              RegExp's lastIndex defensively (a /g
//                              literal carries match state across calls).
//   countSubstring(blob, s)  → number of occurrences of a literal
//                              substring (escapeRe + global count).

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

function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function countMatches(blob, re) {
  if (blob == null) {
    return 0;
  }
  // A caller-supplied /g RegExp literal carries `lastIndex` state across
  // calls; reset it so the count is independent of prior use.
  re.lastIndex = 0;
  const m = blob.match(re);
  return m ? m.length : 0;
}

function countSubstring(blob, needle) {
  return countMatches(blob, new RegExp(escapeRe(needle), 'g'));
}

module.exports = {
  readReleaseBlob,
  listReleaseJsFiles,
  escapeRe,
  countMatches,
  countSubstring,
};
