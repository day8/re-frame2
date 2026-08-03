#!/usr/bin/env node
//
// BUILD THE TWO ARMS AS js-framework-benchmark ENTRIES (rf2-rguy1).
//
// Produces `frameworks/keyed/rf2-reagent/` and `frameworks/keyed/rf2-hicasso/`
// inside a CLONE of krausest/js-framework-benchmark. It never writes inside
// this repository, and the clone is never committed here: it is somebody
// else's repository and it stays outside our tree.
//
//   node freehand/test/re_frame/bench/hicasso/jsfb_build.cjs --dest <clone>
//
// ## Why both arms are built here rather than taken from upstream
//
// Upstream already ships `frameworks/keyed/reagent` (Reagent 0.10, lein,
// React 16-era) and `frameworks/keyed/re-frame` (re-frame 1.4.3). Either
// would give a Reagent number, and neither would give a COMPARABLE one:
// they are a different Reagent, a different React, a different compiler and
// a different optimisation level from the candidate. A ratio across that
// gap would measure five years of React as much as it measured a
// substrate.
//
// So both arms are compiled here, from one repository, at one React, by one
// shadow-cljs, at `:advanced` with `goog.DEBUG false` — the `:hicasso-bench`
// build the clock harness uses, reached through `--config-merge` only, so
// `implementation/shadow-cljs.edn` is untouched and no build id is added.
//
// ## One build id, two arms — the cache is cleared between them
//
// `:hicasso-bench` is a single build id and each arm overrides its
// `:init-fn` and `:output-dir`. shadow-cljs caches per build id, so building
// the second arm without clearing would risk serving the first arm's
// analysis. rf2-2rtt6.20 records that trap in this lane. The cache directory
// is removed before EACH build, so neither arm can inherit the other's.
//
// Every bundle is hashed and the digest printed, because a stale bundle
// silently measured is this session's rf2-6t03c and the cheapest guard
// against it is a digest the run log carries.

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

// shadow-cljs exits 0 on WARNINGS, so a status check is not a gate. The
// lane's one build door refuses a warned build (rf2-2rtt6.73).
const { shadowBuildVerdict, reportRefusal } = require('./lane_build.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');

const BUILD_ID = 'hicasso-bench';

const ARMS = [
  {
    dir: 'rf2-reagent',
    initFn: 're-frame.bench.hicasso.jsfb-reagent-app/-main',
    title: 're-frame2 Reagent-on-subs',
  },
  {
    dir: 'rf2-hicasso',
    initFn: 're-frame.bench.hicasso.jsfb-hicasso-app/-main',
    title: 're-frame2 Hicasso Arm 1',
  },
  // Added after the first two had run, because the contested bulk-broad
  // row is `UIx / Reagent` and a Reagent-and-Hicasso pair cannot speak to
  // it. See `jsfb_uix_app`'s docstring.
  {
    dir: 'rf2-uix',
    initFn: 're-frame.bench.hicasso.jsfb-uix-app/-main',
    title: 're-frame2 UIx-on-subs',
  },
];

function arg(name, fallback) {
  const i = process.argv.indexOf(name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const DEST = arg('--dest', process.env.JSFB_REPO);
if (!DEST) {
  console.error('[jsfb-build] --dest <js-framework-benchmark clone> is required');
  process.exit(2);
}
if (!fs.existsSync(path.join(DEST, 'frameworks'))) {
  console.error(`[jsfb-build] ${DEST} does not look like a js-framework-benchmark clone`);
  process.exit(2);
}

// The reference implementation's markup, element for element. The two arms
// render into `#main`; everything outside it is the benchmark's own
// chrome and is identical in both files.
function indexHtml(title) {
  return `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8"/>
    <title>${title}</title>
    <link href="/css/currentStyle.css" rel="stylesheet"/>
</head>
<body>
<div id="main"></div>
<script src='dist/main.js'></script>
</body>
</html>
`;
}

// `frameworkVersion` is a fixed string with no range, which is the
// requirement upstream states. It names THIS repository's version rather
// than Reagent's or React's, because what is being measured is a
// re-frame2 arm and a reader who sees "0.10" would reasonably think it
// was upstream's entry.
function packageJson(dir, title) {
  const version = fs.readFileSync(path.join(IMPL, '..', 'VERSION'), 'utf8').trim();
  return (
    JSON.stringify(
      {
        name: `js-framework-benchmark-${dir}`,
        version: '1.0.0',
        private: true,
        description: `${title} — local cross-check arm, not an upstream entry (rf2-rguy1)`,
        'js-framework-benchmark': {
          frameworkVersion: version,
          frameworkHomeURL: 'https://github.com/day8/re-frame',
          language: 'ClojureScript',
        },
        scripts: {
          // The build is driven from the re-frame2 repository, because the
          // sources live there. These exist so the directory is shaped like
          // a framework entry; they are NOT an upstream-conformant build and
          // the message says so rather than exiting 0 and looking built.
          dev: 'echo "build from the re-frame2 repo: node freehand/test/re_frame/bench/hicasso/jsfb_build.cjs" && exit 1',
          'build-prod':
            'echo "build from the re-frame2 repo: node freehand/test/re_frame/bench/hicasso/jsfb_build.cjs" && exit 1',
        },
      },
      null,
      2
    ) + '\n'
  );
}

// The server's `isFrameworkDir` requires BOTH `package.json` and
// `package-lock.json` to be present before a directory appears in `/ls`,
// and a directory that does not appear in `/ls` cannot be benchmarked —
// silently, with no error anywhere. Verified by reading
// `server/src/frameworks/frameworksServices.ts`, after the first run
// listed 248 frameworks and neither of these.
//
// There are no npm dependencies to lock: both bundles are compiled by
// shadow-cljs from this repository and the directory ships only the
// emitted JavaScript. So this is the empty-but-valid lockfile that
// satisfies the check without claiming a dependency tree that does not
// exist.
function packageLockJson(dir) {
  return (
    JSON.stringify(
      {
        name: `js-framework-benchmark-${dir}`,
        version: '1.0.0',
        lockfileVersion: 3,
        requires: true,
        packages: {
          '': { name: `js-framework-benchmark-${dir}`, version: '1.0.0' },
        },
      },
      null,
      2
    ) + '\n'
  );
}

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

function rmrf(p) {
  fs.rmSync(p, { recursive: true, force: true });
}

let failed = false;
const built = [];

for (const arm of ARMS) {
  const frameworkDir = path.join(DEST, 'frameworks', 'keyed', arm.dir);
  const outDir = path.join(frameworkDir, 'dist');

  // Clear BOTH the previous output and the build id's cache. The output so
  // that a failed compile cannot leave the last good bundle in place and be
  // measured as though it were this one; the cache for the one-build-id
  // reason above.
  rmrf(outDir);
  rmrf(path.join(IMPL, '.shadow-cljs', 'builds', BUILD_ID));
  fs.mkdirSync(outDir, { recursive: true });

  const configMerge =
    `{:output-dir "${outDir.replace(/\\/g, '/')}" :asset-path "." ` +
    `:modules {:main {:init-fn ${arm.initFn}}}}`;

  console.error(`[jsfb-build] ${arm.dir}: :advanced release -> ${outDir}`);
  // The verdict form, not the exiting one: this loop reports every arm before
  // it gives up. A WARNED build fails here too — shadow-cljs exits 0 on
  // warnings, so the old `r.status !== 0` let a renamed def through with the
  // arm still publishing a number (rf2-2rtt6.73).
  const verdict = shadowBuildVerdict({
    impl: IMPL,
    mode: 'release',
    buildId: BUILD_ID,
    configMerge,
  });
  if (!verdict.ok) {
    reportRefusal(`jsfb-build ${arm.dir}`, verdict);
    failed = true;
    continue;
  }

  const main = path.join(outDir, 'main.js');
  if (!fs.existsSync(main)) {
    console.error(`[jsfb-build] ${arm.dir}: no main.js emitted`);
    failed = true;
    continue;
  }

  fs.writeFileSync(path.join(frameworkDir, 'index.html'), indexHtml(arm.title));
  fs.writeFileSync(path.join(frameworkDir, 'package.json'), packageJson(arm.dir, arm.title));
  fs.writeFileSync(path.join(frameworkDir, 'package-lock.json'), packageLockJson(arm.dir));

  const bytes = fs.statSync(main).size;
  const digest = sha256(main);
  built.push({ dir: arm.dir, bytes, digest });
  console.error(`[jsfb-build] ${arm.dir}: main.js ${bytes} bytes sha256 ${digest}`);
}

console.log('');
console.log(';; BUILT ARMS — the digest is the anti-stale-bundle guard (rf2-6t03c)');
console.log(';; arm            bytes      sha256');
for (const b of built) {
  console.log(`;; ${b.dir.padEnd(14)} ${String(b.bytes).padStart(9)}  ${b.digest}`);
}

if (failed || built.length !== ARMS.length) {
  console.error('[jsfb-build] FAILED — not every arm built');
  process.exit(1);
}
process.exit(0);
