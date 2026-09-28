#!/usr/bin/env node
'use strict';
// THE LANE'S CLJS TESTS, RUN.
//
//     npm test          # from bench/fresco/
//
// Two `:node-test` builds from `shadow-cljs.edn`, each compiled through
// `lane_build.cjs` (a warning is a failure) and then executed under Node:
//
//   :fresco-bench-test       every `re-frame.bench.fresco.*-cljs-test` suite
//   :fresco-bench-test-perf  the `-emit-nightly-test` suite, with the perf
//                            brackets compiled in
//
// CI runs this nightly, in `expensive-tests.yml`'s `fresco-bench-compile`
// job; no per-PR lane runs it, for the reason README.md gives.
//
// ## What a pass means
//
// A build passes only when all three hold:
//
//   1. shadow's runner exits 0;
//   2. its output carries the `Ran N tests containing M assertions.` summary
//      with zero failures and zero errors. A run that ends without one has
//      verified nothing: an async test whose `done` is never called lets
//      Node's event loop drain, and the process exits 0 with no summary;
//   3. the namespaces that printed a `Testing` banner are exactly the lane
//      namespaces under `src/` that the build's selector names. That is what
//      makes the counts this lane's: a selector that loosened would sweep the
//      package suites on the source paths into them, and one that tightened
//      would drop suites in silence.
//
// The bundle is deleted before each compile, so a failed compile cannot leave
// the previous bundle in place to be run.
//
// ## Why `NODE_PATH`
//
// A `:node-test` bundle `require`s npm packages by name at run time, and the
// lane has no `node_modules` of its own: `:node-modules-dir` points the
// COMPILE at `implementation/node_modules`, and `NODE_PATH` points the RUN at
// the same install.

const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');

const { shadowBuild } = require('./lane_build.cjs');
const { laneNamespaces } = require('./compile_gate.cjs');

const PROJECT = path.resolve(__dirname, '../../../..');
const NODE_MODULES = path.resolve(PROJECT, '../../implementation/node_modules');
const TAG = 'fresco-test';

// Each build with the selector its `:ns-regexp` spells in `shadow-cljs.edn`.
// Check 3 compares the two, so a drift between them reds the run by name.
const BUILDS = [
  {
    buildId: 'fresco-bench-test',
    outputTo: 'out/fresco-bench-test.js',
    selects: /^re-frame\.bench\.fresco\..+-cljs-test$/,
  },
  {
    buildId: 'fresco-bench-test-perf',
    outputTo: 'out/fresco-bench-test-perf.js',
    selects: /^re-frame\.bench\.fresco\..+-emit-nightly-test$/,
  },
];

const SUMMARY_RE = /Ran (\d+) tests containing (\d+) assertions\.\s*\n\s*(\d+) failures, (\d+) errors\./;
const BANNER_RE = /^Testing (\S+)\s*$/gm;

/**
 * Judge one run from its exit status, its stdout and the namespaces its
 * selector names in the lane. Pure.
 */
function judgeRun({ status, signal, error, output, expected }) {
  const problems = [];
  if (error) problems.push(`could not run the bundle: ${error.message}`);
  else if (status !== 0) problems.push(signal ? `the run was killed by ${signal}` : `the run exited ${status}`);

  const m = SUMMARY_RE.exec(output);
  const summary = m && { tests: +m[1], assertions: +m[2], failures: +m[3], errors: +m[4] };
  if (!summary) {
    problems.push('no "Ran N tests containing M assertions." summary: the run did not finish, so it verified nothing');
  } else {
    if (summary.failures + summary.errors > 0) {
      problems.push(`${summary.failures} failure(s), ${summary.errors} error(s)`);
    }
    if (summary.tests === 0) problems.push('zero tests ran');
  }

  if (expected.length === 0) problems.push('the selector names no namespace under src/');
  const ran = new Set([...output.matchAll(BANNER_RE)].map((x) => x[1]));
  const missing = expected.filter((ns) => !ran.has(ns));
  const extra = [...ran].filter((ns) => !expected.includes(ns)).sort();
  if (missing.length > 0) {
    problems.push(`${missing.length} selected namespace(s) printed no Testing banner: ${missing.join(', ')}`);
  }
  if (extra.length > 0) {
    problems.push(`${extra.length} namespace(s) outside the lane ran: ${extra.join(', ')}`);
  }

  return { ok: problems.length === 0, summary, namespaces: ran.size, problems };
}

/** Run a compiled bundle, echoing its stdout as it arrives and keeping it. */
function runBundle(bundle) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [bundle], {
      cwd: PROJECT,
      env: {
        ...process.env,
        NODE_PATH: [NODE_MODULES, process.env.NODE_PATH].filter(Boolean).join(path.delimiter),
      },
      stdio: ['ignore', 'pipe', 'inherit'],
    });
    let output = '';
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
      output += chunk;
      process.stdout.write(chunk);
    });
    child.on('error', (error) => resolve({ status: null, signal: null, error, output }));
    child.on('close', (status, signal) => resolve({ status, signal, output }));
  });
}

async function main() {
  const { namespaces, unreadable } = laneNamespaces();
  if (unreadable.length > 0) {
    console.error(`[${TAG}] lane source(s) with no readable (ns ...) form: ${unreadable.join(', ')}`);
    process.exit(1);
  }

  let failed = false;
  for (const { buildId, outputTo, selects } of BUILDS) {
    const expected = namespaces.filter((ns) => selects.test(ns));
    const bundle = path.join(PROJECT, outputTo);
    fs.rmSync(bundle, { force: true });

    console.error(`[${TAG}] compiling :${buildId} (${expected.length} namespaces) -> ${outputTo}`);
    shadowBuild({ project: PROJECT, mode: 'compile', buildId, tag: TAG });

    console.error(`[${TAG}] running ${outputTo}`);
    const verdict = judgeRun({ ...(await runBundle(bundle)), expected });
    const s = verdict.summary;
    const counts = s
      ? `${s.tests} tests, ${s.assertions} assertions, ${s.failures} failures, ${s.errors} errors`
      : 'no summary';
    console.error(`[${TAG}] :${buildId} ${verdict.ok ? 'ok' : 'FAILED'} - ${counts}, ${verdict.namespaces} namespaces`);
    for (const p of verdict.problems) console.error(`[${TAG}]   ${p}`);
    if (!verdict.ok) failed = true;
  }
  process.exit(failed ? 1 : 0);
}

if (require.main === module) main();

module.exports = { BUILDS, judgeRun };
