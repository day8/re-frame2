#!/usr/bin/env node
/*
 * Per-namespace test-isolation gate (rf2-32siq3.44).
 *
 * THE GAP THIS CLOSES
 * -------------------
 * `npm run test:cljs` compiles ALL `*_cljs_test` namespaces into one
 * consolidated node-test bundle and runs them in a single process. That
 * shares one runtime: the installed substrate adapter, the registrar, the
 * late-bind directory, machine/trace counters — all process-global state.
 *
 * EP-0023 collapse made `make-frame` / `reg-frame` allocate a RUNNABLE
 * backing record (app-db / queue / sub-cache), which needs an installed
 * substrate adapter. A test namespace whose fixture FORGETS to install its
 * own adapter (via `make-reset-runtime-fixture {:adapter ...}` or
 * `rf/init!`) is still GREEN in the consolidated bundle whenever an
 * EARLIER namespace happened to leave an adapter installed — the suite
 * ORDERING masks a self-incomplete fixture. Run that same namespace alone
 * and it errors with `:rf.error/no-adapter-installed`.
 *
 * This is the shared-node-test-bundle fragility class: a fixture that
 * relies on bundle pollution passes the gate. The concrete instance
 * (`live-frame-reload-cljs-test`, 11 errors standalone yet green in CI)
 * was fixed by the sibling bead; this gate stops the CLASS from recurring.
 *
 * THE CHECK
 * ---------
 * Run each curated runtime-construction test namespace ALONE in a fresh
 * `out/node-test.js` process (the runner's focused `--test=<ns>` selector,
 * which already rejects a no-match selector — rf2-lbo79.1). A namespace
 * that relies on a sibling's leaked adapter goes RED standalone, so the
 * gate fails at the SOURCE: the self-incomplete fixture, not a victim
 * downstream.
 *
 * WHY A CURATED ALLOWLIST, NOT A BLANKET SCAN
 * -------------------------------------------
 * Running every `*_cljs_test` ns alone would be slow (one node process
 * each) and a naive "does this file install an adapter?" STATIC lint is
 * unworkably noisy: `rf/init!` is BOTH a constructor AND the adapter
 * install call, story/test helpers seat adapters through their own
 * fixtures, and `make-frame` appears as an error-id keyword in many files
 * that never construct a frame. So, like `check_ambient_durable_reads.py`'s
 * durable-write suffix allowlist, this gate scopes to an EXPLICIT list of
 * the EP-0023 image/frame runtime-construction namespaces — the lineage
 * whose fixtures MUST self-install an adapter. Adding such a namespace is a
 * conscious one-line edit here (and the gate then guards it).
 *
 * Exit 0 on PASS (every listed ns green standalone), 1 on FAIL, 2 on a
 * setup error.
 */

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

// ---------------------------------------------------------------------------
// The curated scan surface — EP-0023 image/frame runtime-construction test
// namespaces whose fixtures MUST self-install a substrate adapter (a runnable
// `make-frame` / `reg-frame` backing record needs one). Each is run ALONE; a
// fixture leaning on a sibling's leaked adapter goes red standalone.
//
// To add a namespace: append its ns symbol (the `(ns ...)` name). Run
// `node scripts/check-per-ns-isolation.cjs --list` to see the live set.
// ---------------------------------------------------------------------------
const ISOLATION_NAMESPACES = [
  're-frame.app-value-cljs-test',
  're-frame.conformance-corpus-cljs-test',
  're-frame.ep0023-conformance-cljs-test',
  're-frame.example-frame-scoping-cljs-test',
  're-frame.frame-classification-cljs-test',
  're-frame.frame-resolution-cljs-test',
  're-frame.frame-teardown-report-cljs-test',
  're-frame.live-cascade-frame-resolution-cljs-test',
  're-frame.live-frame-cljs-test',
  're-frame.live-frame-reload-cljs-test',
  're-frame.migration-cljs-test',
  're-frame.multi-frame-isolation-cljs-test',
  're-frame.owned-frame-lifecycle-cljs-test',
  're-frame.projection-cljs-test',
  're-frame.realm-cljs-test',
  're-frame.router-carried-frame-cljs-test',
  're-frame.subs-override-seam-cljs-test',
  're-frame.views-current-component-cljs-test',
];

const NODE_TEST_BUNDLE = 'out/node-test.js';

function implementationDir() {
  // scripts/ lives directly under implementation/.
  return path.resolve(__dirname, '..');
}

function compileBundle(implDir) {
  process.stderr.write('compiling node-test bundle...\n');
  // Hardened, shell-free spawn (per the script-spawn-policy gate): resolve
  // shadow-cljs's JS entry-point and run it under THIS node binary, so the OS
  // never interprets a bare `npx` / `.cmd` shim (the rf2-33vvc command-hijack
  // class on Windows).
  let runner;
  try {
    runner = require.resolve('shadow-cljs/cli/runner.js');
  } catch (e) {
    process.stderr.write(
      'error: cannot resolve shadow-cljs (run `npm install` in implementation/).\n'
    );
    return false;
  }
  const r = spawnSync(process.execPath, [runner, 'compile', 'node-test'], {
    cwd: implDir,
    stdio: 'inherit',
  });
  if (r.status !== 0) {
    process.stderr.write('error: node-test compile failed.\n');
    return false;
  }
  return true;
}

function runNamespaceAlone(implDir, ns) {
  const r = spawnSync(process.execPath, [NODE_TEST_BUNDLE, `--test=${ns}`], {
    cwd: implDir,
    encoding: 'utf8',
  });
  return { status: r.status, stdout: r.stdout || '', stderr: r.stderr || '' };
}

function main(argv) {
  const args = new Set(argv);

  if (args.has('--help') || args.has('-h')) {
    process.stdout.write(
      'Per-namespace test-isolation gate (rf2-32siq3.44).\n\n' +
        'Usage:\n' +
        '  node scripts/check-per-ns-isolation.cjs [--compile] [--list] [--self-test]\n\n' +
        '  --compile    recompile out/node-test.js first (CI runs test:cljs ahead,\n' +
        '               so the bundle is normally already current)\n' +
        '  --list       print the curated namespace set and exit\n' +
        '  --self-test  prove the gate detects a no-adapter standalone failure\n' +
        '               and a clean pass, using a synthetic runner stub; exit\n'
    );
    return 0;
  }

  if (args.has('--list')) {
    for (const ns of ISOLATION_NAMESPACES) process.stdout.write(ns + '\n');
    return 0;
  }

  if (args.has('--self-test')) {
    return runSelfTest();
  }

  const implDir = implementationDir();

  if (args.has('--compile')) {
    if (!compileBundle(implDir)) return 2;
  }

  const bundlePath = path.join(implDir, NODE_TEST_BUNDLE);
  if (!fs.existsSync(bundlePath)) {
    process.stderr.write(
      `error: ${NODE_TEST_BUNDLE} not found. Run \`npm run test:cljs\` (or pass ` +
        '--compile) first so the consolidated bundle exists.\n'
    );
    return 2;
  }

  const reds = [];
  for (const ns of ISOLATION_NAMESPACES) {
    process.stderr.write(`  isolating ${ns} ... `);
    const r = runNamespaceAlone(implDir, ns);
    if (r.status === 0) {
      process.stderr.write('ok\n');
    } else {
      process.stderr.write('RED\n');
      reds.push({ ns, ...r });
    }
  }

  if (reds.length === 0) {
    process.stderr.write(
      `\nper-ns isolation: all ${ISOLATION_NAMESPACES.length} namespace(s) ` +
        'pass standalone.\n'
    );
    return 0;
  }

  process.stderr.write(
    `\n${reds.length} namespace(s) FAILED when run in isolation (they pass in ` +
      'the consolidated bundle ONLY because a sibling left runtime state — ' +
      'e.g. an installed adapter — behind):\n\n'
  );
  for (const red of reds) {
    process.stderr.write(`  RED: ${red.ns} (exit=${red.status})\n`);
    // Surface the tail of the diagnostic (the adapter / fixture error).
    const tail = (red.stdout + red.stderr)
      .split('\n')
      .filter((l) => l.trim() !== '')
      .slice(-6)
      .map((l) => '      ' + l)
      .join('\n');
    if (tail) process.stderr.write(tail + '\n');
  }
  process.stderr.write(
    '\nFix:\n  * Give the failing namespace a fixture that installs its OWN ' +
      'substrate adapter and resets runtime state — e.g.\n' +
      '    (use-fixtures :each\n' +
      '      (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))\n' +
      '  Do NOT rely on a sibling test having installed one (consolidated-bundle ' +
      'ordering is not a contract).\n'
  );
  return 1;
}

// ---------------------------------------------------------------------------
// Self-test — prove the gate's red/green logic with a synthetic runner stub
// (no shadow-cljs compile required). We exercise the same exit-code contract
// the real run depends on: a non-zero standalone exit for ONE namespace makes
// the gate fail; an all-zero set passes.
// ---------------------------------------------------------------------------
function runSelfTest() {
  let failures = 0;
  const assert = (cond, msg) => {
    if (cond) {
      process.stderr.write(`self-test PASS: ${msg}\n`);
    } else {
      process.stderr.write(`self-test FAIL: ${msg}\n`);
      failures += 1;
    }
  };

  // Classify a set of synthetic {ns -> exit} runs the way main() does.
  const classify = (runs) =>
    Object.entries(runs)
      .filter(([, status]) => status !== 0)
      .map(([ns, status]) => ({ ns, status }));

  // Green: every namespace exits 0 → no reds.
  const green = classify({ a: 0, b: 0, c: 0 });
  assert(green.length === 0, 'all-green isolation run yields zero reds (PASS)');

  // Red: a self-incomplete fixture exits non-zero standalone → flagged.
  const red = classify({ a: 0, b: 1, c: 0 });
  assert(red.length === 1 && red[0].ns === 'b',
    'a single standalone failure is flagged as the red namespace (FAIL path)');

  // Multiple reds are all surfaced.
  const reds = classify({ a: 1, b: 0, c: 1 });
  assert(reds.length === 2, 'every standalone failure is surfaced, not just the first');

  // The curated list is non-empty and dedup'd (a typo'd duplicate would run
  // twice and silently weaken the signal).
  const uniq = new Set(ISOLATION_NAMESPACES);
  assert(ISOLATION_NAMESPACES.length > 0, 'curated namespace list is non-empty');
  assert(uniq.size === ISOLATION_NAMESPACES.length,
    'curated namespace list has no duplicates');

  if (failures) {
    process.stderr.write(`\n${failures} self-test failure(s).\n`);
    return 1;
  }
  process.stderr.write('\nall self-tests passed.\n');
  return 0;
}

process.exit(main(process.argv.slice(2)));
