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
 * (`re-frame.testbed.config/repo-root`) that the affected shadow-cljs
 * builds seed from this env var via `#shadow/env`. So the on-disk root
 * baked into a testbed build is the ACTUAL repo root of whatever clone
 * ran the build — never a hardcoded personal path. 'Open in editor'
 * therefore works on a fresh clone at any path, on any OS, with no
 * `?project-root=` override needed.
 *
 * Usage:
 *
 *   npm run dev -- :examples/button-deck
 *   npm run dev -- :testbeds/panel-gallery
 *   npm run dev -- :examples/counter-with-stories :examples/login-form
 *
 * Any extra `shadow-cljs watch` args pass straight through. Launching
 * `npx shadow-cljs watch ...` directly still works — the env var is just
 * unset, so the helper falls back to the `?project-root=` query string
 * (or a graceful open-in-editor no-op).
 */

'use strict';

const { spawn } = require('child_process');
const { REPO_ROOT, IMPL_ROOT } = require('./_path-policy.cjs');

const builds = process.argv.slice(2);
if (builds.length === 0) {
  console.error(
    'Usage: npm run dev -- <build> [<build>...]\n' +
      '  e.g. npm run dev -- :examples/button-deck',
  );
  process.exit(1);
}

const isWin = process.platform === 'win32';
const cmd = isWin ? 'npx.cmd' : 'npx';
const args = ['shadow-cljs', 'watch', ...builds];

// Normalise to forward slashes so the value reads identically across
// platforms when it becomes a string prefix in `re-frame.testbed.config`.
const repoRoot = REPO_ROOT.split('\\').join('/');

console.log(`> RF2_TESTBED_PROJECT_ROOT=${repoRoot}`);
console.log(`> ${cmd} ${args.join(' ')}`);

const child = spawn(cmd, args, {
  cwd: IMPL_ROOT,
  stdio: 'inherit',
  shell: isWin,
  env: { ...process.env, RF2_TESTBED_PROJECT_ROOT: repoRoot },
});

child.on('exit', (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal);
    return;
  }
  process.exit(code == null ? 0 : code);
});
