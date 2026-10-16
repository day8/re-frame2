#!/usr/bin/env node
/*
 * re-frame2-pair-mcp tool-descriptor manifest generator + drift-check
 * — Node wrapper.
 *
 * Compiles the `:descriptor-gen` shadow-cljs build (the CLJS generator
 * `re-frame2-pair-mcp.descriptor-manifest-gen`) and runs it, forwarding
 * any args (notably `--check`) verbatim. The generator reads the live
 * `registry/tool-descriptors` vector and emits / --check's the committed
 * `tool-descriptors.edn` via the shared
 * `re-frame.mcp-base.descriptor-manifest` serialiser.
 *
 * Why a wrapper (and not a plain `node out/descriptor-gen.js`): pair-mcp's
 * registry is CLJS-only, so the generator must be COMPILED before it can
 * run. CI / operators call this one script; it owns the compile-then-run
 * sequencing so callers don't have to remember the build step. Mirrors
 * the shape of `scripts/test-mcp-conformance.cjs`'s compile-then-test
 * orchestration.
 *
 * Usage (from tools/re-frame2-pair-mcp/):
 *   node scripts/descriptor-manifest-gen.cjs            # regenerate
 *   node scripts/descriptor-manifest-gen.cjs --check    # drift-check (CI)
 *
 * Exit codes are forwarded from the generator: 0 = ok / in-sync,
 * 1 = drift, 2 = generator error.
 */
'use strict';

const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { SHADOW_CLI } = require('../../../implementation/scripts/lib/shadow.cjs');

const HERE = __dirname;
const ROOT = path.resolve(HERE, '..');
const COMPILED = path.join(ROOT, 'out', 'descriptor-gen.js');
const passthrough = process.argv.slice(2);

// No shell anywhere: `process.execPath` can contain spaces (e.g.
// `C:\Program Files\nodejs\node.exe`) which a shell would split — spawn the
// binary directly with the args array instead.
function run(args) {
  const child = spawnSync(process.execPath, args, {
    cwd: ROOT,
    stdio: 'inherit',
    env: process.env,
  });
  return child.status === null ? 1 : child.status;
}

// 1. Compile the generator build through the shared shadow-cljs launcher,
// which takes the machine-wide build lane and deletes the previous bundle
// first, so a failed compile cannot leave a stale generator to run.
const compileStatus = run([SHADOW_CLI, '--clean', 'out/descriptor-gen.js', 'compile', 'descriptor-gen']);
if (compileStatus !== 0) {
  process.stderr.write('FAIL: shadow-cljs compile descriptor-gen exited ' + compileStatus + '\n');
  process.exit(compileStatus);
}

// 2. Run it, forwarding args (e.g. --check).
const runStatus = run([COMPILED, ...passthrough]);
process.exit(runStatus);
