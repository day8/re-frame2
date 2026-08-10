#!/usr/bin/env node
/*
 * `dev` — cross-platform testbed dev launcher (rf2-5dphw).
 *
 * Resolves the repo root from THIS script's own location (via node's
 * `path` module, which behaves identically on Windows / macOS / Linux),
 * exports it as the `RF2_TESTBED_PROJECT_ROOT` environment variable, and
 * spawns `shadow-cljs watch <build...>` with the remaining CLI args.
 *
 * Why: the Xray / Story dev testbeds turn a source-coord into an editor
 * URI by prepending an on-disk project-root. The shared helper
 * `re-frame.testbed.config` reads that root from a `goog-define`
 * (`re-frame.testbed.config/checkout-root`) that the affected shadow-cljs
 * builds seed from this env var via `#shadow/env`. So the on-disk root
 * baked into a testbed build is the ACTUAL repo root of whatever clone
 * ran the build — never a hardcoded personal path. 'Open in editor'
 * therefore works on a fresh clone at any path, on any OS, with no
 * `?checkout-root=` override needed.
 *
 * EXPLICIT BUILD-IDS ONLY (rf2-trlj7). The launcher takes one or more
 * explicit shadow-cljs build-ids — name the build(s) you actually want to
 * watch:
 *
 *   npm run dev -- :examples/standard-epochs
 *   npm run dev -- :testbeds/panel-gallery
 *   npm run dev -- :examples/standard-epochs :examples/routes-epochs
 *   npm run dev -- :examples/login-form --verbose
 *
 * Group aliases (`xray` / `stories` / `epochs` / `all`) used to expand a
 * single short name to a whole surface, but a group fired SIX builds into
 * a single `shadow-cljs watch`, and six concurrent Closure externs-
 * prebuilds raced on the shared externs.zip — intermittent build failures
 * ("Exception parsing externs.zip", "this.contents is null"). They were
 * removed (Mike-ruled 2026-06-03): name explicit build-ids so nobody
 * accidentally fires a mass-parallel compile. Watch a handful at once by
 * listing them — keep the count modest to avoid the race.
 *
 * Build-ids and extra `shadow-cljs watch` flags pass through unchanged.
 * Duplicate build-ids are de-duplicated while PRESERVING first-seen order
 * (so `dev -- :examples/login-form :examples/login-form` watches it once).
 * Non-build flags (anything not starting with `:`) pass through in place
 * and are never treated as builds for URL printing.
 *
 * Launching `npx shadow-cljs watch ...` directly still works — the env
 * var is just unset, so the helper falls back to the `?checkout-root=`
 * query string (or a graceful open-in-editor no-op).
 *
 * SPAWN FORM (rf2-1ggkn). We resolve shadow-cljs's own JS entry-point
 * (`shadow-cljs/cli/runner.js`) and spawn it under THIS `node` binary
 * (`process.execPath`) with `shell:false` — never `npx`/`npx.cmd` under a
 * shell. Two reasons:
 *   1. Passing an args array with `shell:true` triggers Node's DEP0190
 *      DeprecationWarning (args are concatenated, not escaped) — so the
 *      old `shell: isWin` form printed a warning on every Windows launch.
 *   2. Simply dropping `shell:true` is NOT a fix on Windows: spawning a
 *      `.cmd` (npx.cmd) without a shell throws `EINVAL` since the
 *      CVE-2024-27980 mitigation. Resolving the runner's `.js` and
 *      spawning `node` on it sidesteps the `.cmd` entirely, so the same
 *      warning-clean, shell-free form works on Windows AND POSIX.
 * The colon-prefixed build-ids and any extra `shadow-cljs watch` flags are
 * just elements of the args array — they pass through unescaped and
 * unconcatenated, exactly as before.
 *
 * URL DISCOVERABILITY. For every watched build that has a `:dev-http`
 * port, the launcher prints `http://localhost:<port>/` on start (Story
 * builds also print the `/#/stories` shell URL) so "how do I see them" is
 * answered before the compile even finishes. The build->port map below is
 * kept in sync with the `:dev-http` map in
 * `implementation/shadow-cljs.edn` (READ-only here — shadow-cljs.edn is
 * a hot-zone file).
 */

'use strict';

const { spawn } = require('child_process');
const { REPO_ROOT, IMPL_ROOT } = require('./_path-policy.cjs');

// ===========================================================================
// OWNED-RANGE PORT MAP (rf2-ot0lv). Single source of truth for who-owns-
// which-localhost-band across the dev/test tooling, so the next port
// addition can't silently collide. Each owner keeps to its band; adding a
// surface picks the next free slot WITHIN the owner's band.
//
//   Band        Owner                     Notes
//   ----------  ------------------------  ----------------------------------
//   8030-8036   Xray testbeds             :dev-http (shadow-cljs.edn):
//                                           8030 two-frame-isolation
//                                           8031 standard-epochs
//                                           8032 routes-epochs
//                                           8033 machine-epochs
//                                           8034 edn-inspector
//                                           8035 managed-http
//                                           8036 freehand-views (rf2-6pohj)
//   8037        xray-feature-gate         NOT a :dev-http port, and the one
//                                         hole inside the Xray band above:
//                                         PREFERRED_PORT in implementation/
//                                         scripts/serve-and-run-xray-feature-
//                                         gate.cjs (XRAY_FEATURE_GATE_PORT),
//                                         the gate's own http-server. Called
//                                         out because "next slot after the
//                                         last testbed" is the natural way to
//                                         pick a new Xray port and would land
//                                         here — a collision that only shows
//                                         up when the gate and a watch run at
//                                         once. The next free Xray slot is
//                                         8038.
//   8040-8044   Story showcases +         :dev-http (shadow-cljs.edn):
//               worked examples             8040 nine-states-with-stories
//                                           8041 login-with-stories
//                                           8042 counter-with-stories
//                                           8043 login-form
//                                           8044 linearlite (plain example)
//   805x        examples orchestrator     DEFAULT_PORT in
//                                           examples/scripts/examples-port.cjs
//                                           (8050; pre-flight + forward scan).
//   806x        Top-level testbeds        :dev-http (shadow-cljs.edn):
//                                           8060 tenant-switcher (rf2-5e22yc)
//                                           8061 hicasso HMR (rf2-vsgq) —
//                                         the one :dev-http port a GATE
//                                         depends on, because the contract it
//                                         witnesses is what a real hot reload
//                                         does and only `watch` can deliver
//                                         one. Fixed rather than resolved at
//                                         runtime for that reason: the page
//                                         and shadow's devtools websocket
//                                         must share an origin.
//   8765        Xray panel-gallery        :dev-http (shadow-cljs.edn).
//
// The :dev-http bands (8030-8036 / 8040-8044 / 8765) are mirrored in the
// DEV_HTTP map below (READ-only — shadow-cljs.edn is hot-zone). The 805x
// examples band is NOT a :dev-http port — it is the test-orchestrator's
// http-server default, resolved at runtime — so it lives only here + in
// examples-port.cjs, not in DEV_HTTP.
// ===========================================================================

// ---------------------------------------------------------------------------
// build-id -> dev-http info. Kept in sync with shadow-cljs.edn :dev-http
// (READ-only — shadow-cljs.edn is hot-zone). `story: true` marks builds
// whose Story shell lives at /#/stories.
// ---------------------------------------------------------------------------
const DEV_HTTP = {
  ':examples/two-frame-isolation': { port: 8030 },
  ':examples/standard-epochs': { port: 8031 },
  ':examples/routes-epochs': { port: 8032 },
  ':examples/machine-epochs': { port: 8033 },
  ':examples/edn-inspector': { port: 8034 },
  ':examples/managed-http': { port: 8035 },
  // rf2-6pohj — the Freehand-hosted Views deck, the only Xray testbed whose
  // views are Freehand views (803x band).
  ':testbeds/freehand-views': { port: 8036 },
  ':testbeds/panel-gallery': { port: 8765 },
  ':examples/nine-states-with-stories': { port: 8040, story: true },
  ':examples/login-with-stories': { port: 8041, story: true },
  ':examples/counter-with-stories': { port: 8042, story: true },
  ':examples/login-form': { port: 8043, story: true },
  // rf2-tideyl — Linearlite optimistic-board example (804x band, plain
  // build: no Story shell, so no `story: true`).
  ':examples/linearlite': { port: 8044 },
  // rf2-5e22yc — top-level tenant-switcher testbed (806x band).
  ':testbeds/tenant-switcher': { port: 8060 },
  // rf2-vsgq — the Hicasso HMR testbed (806x band). Unlike every other
  // entry here this build is not primarily a developer surface: it is the
  // one build in the repo driven by a GATE that needs `watch`, because the
  // contract it witnesses is what a real hot reload does to a live page
  // (scripts/serve-and-run-hicasso-hmr-testbed.cjs). `npx shadow-cljs watch
  // :hicasso/hmr-testbed` still opens it by hand at the URL below, which is
  // how the witnesses were developed.
  ':hicasso/hmr-testbed': { port: 8061 },
};

/**
 * De-duplicate build-ids in `argv` while preserving first-seen order.
 * Tokens pass through unchanged (explicit build-ids AND extra shadow-cljs
 * flags), but duplicate build-ids (tokens starting with `:`) are dropped.
 * Flags and other non-build tokens are passed through verbatim (never
 * deduped, so e.g. a repeated `--verbose` is the caller's call to make).
 *
 * @param {string[]} argv  the raw CLI args (after `node script.cjs`).
 * @returns {string[]}     the order-preserving, build-deduped args.
 */
function resolveArgs(argv) {
  const out = [];
  const seenBuilds = new Set();
  for (const token of argv) {
    if (token.startsWith(':')) {
      if (seenBuilds.has(token)) continue;
      seenBuilds.add(token);
    }
    out.push(token);
  }
  return out;
}

/**
 * The URLs to print for a watched build, or [] if it has no dev-http
 * port. Story builds get both the live-app `/` URL and the `/#/stories`
 * shell URL.
 *
 * @param {string} build  a build-id token (e.g. ':examples/login-form').
 * @returns {string[]}    zero, one, or two URLs.
 */
function urlsForBuild(build) {
  const info = DEV_HTTP[build];
  if (!info) return [];
  const base = `http://localhost:${info.port}/`;
  return info.story ? [base, `${base}#/stories`] : [base];
}

module.exports = { DEV_HTTP, resolveArgs, urlsForBuild };

// ---------------------------------------------------------------------------
// CLI entry-point (skipped when this module is require()'d by a test).
// ---------------------------------------------------------------------------
if (require.main === module) {
  const rawArgs = process.argv.slice(2);
  if (rawArgs.length === 0) {
    console.error(
      'Usage: npm run dev -- <build-id> [<build-id>...] [shadow-cljs flags]\n' +
        '  e.g. npm run dev -- :examples/standard-epochs\n' +
        '       npm run dev -- :testbeds/panel-gallery\n' +
        '       npm run dev -- :examples/standard-epochs :examples/routes-epochs\n' +
        '       npm run dev -- :examples/login-form --verbose',
    );
    process.exit(1);
  }

  const builds = resolveArgs(rawArgs);

  // Resolve shadow-cljs's own JS entry-point and run it under THIS node
  // binary (shell-free, .cmd-free) — see SPAWN FORM in the header comment
  // (rf2-1ggkn). Resolution is rooted at IMPL_ROOT so it finds the
  // implementation's local install regardless of this launcher's own cwd.
  let shadowRunner;
  try {
    shadowRunner = require.resolve('shadow-cljs/cli/runner.js', { paths: [IMPL_ROOT] });
  } catch {
    console.error(
      'dev: could not resolve shadow-cljs. Run `npm install` in ' +
        `${IMPL_ROOT} first.`,
    );
    process.exit(1);
  }
  const cmd = process.execPath;
  const args = [shadowRunner, 'watch', ...builds];

  // Normalise to forward slashes so the value reads identically across
  // platforms when it becomes a string prefix in `re-frame.testbed.config`.
  const repoRoot = REPO_ROOT.split('\\').join('/');

  console.log(`> RF2_TESTBED_PROJECT_ROOT=${repoRoot}`);
  console.log(`> shadow-cljs watch ${builds.join(' ')}`);

  // Print the served URL(s) for every watched build that has a dev-http
  // port — answers "how do I see them" up front (rf2-jooy3).
  const urlLines = [];
  for (const build of builds) {
    for (const url of urlsForBuild(build)) {
      urlLines.push(`>   ${build.padEnd(36)} ${url}`);
    }
  }
  if (urlLines.length > 0) {
    console.log('> Serving:');
    for (const line of urlLines) console.log(line);
  }

  const child = spawn(cmd, args, {
    cwd: IMPL_ROOT,
    stdio: 'inherit',
    env: { ...process.env, RF2_TESTBED_PROJECT_ROOT: repoRoot },
  });

  child.on('exit', (code, signal) => {
    if (signal) {
      process.kill(process.pid, signal);
      return;
    }
    process.exit(code == null ? 0 : code);
  });
}
