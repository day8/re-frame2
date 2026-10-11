#!/usr/bin/env node
/*
 * `npm run build`: compile the shipped `:server` build with the git commit
 * of the source it was built from baked in.
 *
 * The commit sets the `built-from` closure define, which
 * `get-re-frame2-pair-instructions` returns as `:built-from`. An MCP host
 * keeps the server process it launched, and `:tool-contract` moves only with
 * tool names and argument keys, so this commit is how an agent tells a server
 * that predates a behaviour change from a current one. Without git the define
 * is "unknown", its default.
 *
 * The commit travels as a `--config-merge` argument, not an environment
 * variable, because a shadow-cljs server already running for this package
 * compiles in its own JVM: it never sees this process's environment, but it
 * merges each invocation's `--config-merge` into the build config.
 *
 * Spawns shadow-cljs's own JS entry point under this node binary, with no
 * shell, so one command works on Windows and POSIX alike.
 */
'use strict';

const path = require('node:path');
const { execFileSync, spawnSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '..');
const BUILT_FROM_DEFINE =
  're-frame2-pair-mcp.tools.get-re-frame2-pair-instructions/built-from';

function headCommit() {
  try {
    const sha = execFileSync('git', ['rev-parse', 'HEAD'], {
      cwd: ROOT,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    }).trim();
    return /^[0-9a-f]{40,64}$/.test(sha) ? sha : 'unknown';
  } catch {
    return 'unknown';
  }
}

const builtFrom = headCommit();
const configMerge =
  `{:compiler-options {:closure-defines {${BUILT_FROM_DEFINE} "${builtFrom}"}}}`;
const runner = require.resolve('shadow-cljs/cli/runner.js', { paths: [ROOT] });
console.log(`re-frame2-pair-mcp: building out/server.js from ${builtFrom}`);
const child = spawnSync(
  process.execPath,
  [runner, 'compile', 'server', '--config-merge', configMerge],
  { cwd: ROOT, stdio: 'inherit' },
);
process.exit(child.status === null ? 1 : child.status);
