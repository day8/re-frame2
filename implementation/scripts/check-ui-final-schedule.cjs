#!/usr/bin/env node
'use strict';

// rf2-vxgfnd.195 / rf2-ialoij — the FIFTH real-Shadow arm for final-schedule
// reconciliation, complementing the synthetic JVM fixtures that fabricate build
// state and outputs (compiler_digest_carrier_jvm_test.clj's
// later-prepare-hook-forced-recompile / colliding-and-backwards-stamps /
// metadata-only / missing-provenance arms). Those cannot prove Shadow hook
// deep-merge/order, an actual build-local :compile-prepare hook, real
// sequential/parallel scheduling, output-marker retention, or fail-loud
// behaviour — but here the FRESH `:js` object Shadow's real compile path
// allocates is genuine, not a hand-built stand-in.
//
// This gate drives ONE real Shadow 3.4.10 warm watch daemon per compile mode
// through:
//   1. cold accept — both app-a and app-b declare a view;
//   2. a CONTROL warm pass — an unrelated viewless trigger edit; re-frame.ui
//      sees app-a as an output-present cache hit and does NOT pre-touch it;
//      both accepted rows and the whole-build digest are unchanged;
//   3. a FORCED-EVICT warm pass — a build-local :compile-prepare hook (inherited
//      re-frame.ui hook runs FIRST, this hook deep-merged AFTER) removes app-a's
//      retained output and rewrites its source viewless, forcing Shadow to
//      recompile app-a AFTER re-frame.ui observed the schedule. app-a was NOT
//      pre-touched at prepare, so ONLY reconcile-final-schedule at :compile-
//      finish (reading Shadow's causal per-source compile evidence — app-a's
//      fresh, :cached false output carrying a new :js artifact object) can evict
//      it. The accepted row is evicted, the digest moves, and app-b (a true
//      cache hit) stays byte-identical;
//   4. a FAIL-LOUD pass — a build-local hook drops re-frame.ui's per-pass
//      :pass-output provenance from the open scratch; reconciliation must FAIL
//      the build rather than silently reconcile against an assumed-empty
//      schedule.
// Both the PARALLEL (:ui-final-schedule) and SEQUENTIAL
// (:ui-final-schedule-seq, :parallel-build false) compile schedules are proven.
//
// TEETH (documented mutation/revert; run at development time, NOT committed):
//   * Restore build-hook/compiled-this-pass?'s old wall-clock body (a
//     `(> now then)` on :compiled-at, rf2-ialoij) — a real-host recompile whose
//     fresh output lands in the SAME millisecond as its prepare snapshot is then
//     misread as untouched, so app-a's ghost can survive the FORCED-EVICT pass.
//     The deterministic same-/backwards-stamp misclassification is proven
//     unconditionally by the digest-carrier JVM arms.
//   * Delete the `(reconcile-final-schedule build-state)` call in
//     re-frame.ui.compiler.build-hook/hook's :compile-finish branch → the
//     FORCED-EVICT pass keeps app-a's ghost row (a-present stays true,
//     view-count stays 2, digest unchanged) → this gate goes RED.
//   * Make reconcile-final-schedule proceed silently when :pass-output is absent
//     (drop the missing-pass-provenance throw) → the FAIL-LOUD pass SUCCEEDS
//     instead of failing → this gate goes RED.
// Both were exercised red-before-green during development.

const fs = require('fs');
const path = require('path');
const {
  spawnHarnessProcess,
  terminateProcessTree,
} = require('./lib/local-browser-harness.cjs');

const IMPL = path.join(__dirname, '..');
const FIX = path.join(
  IMPL, 'ui', 'test', 're_frame', 'ui', 'digest_probe', 'final_schedule',
);
const APP_A = path.join(FIX, 'app_a.cljs');
const APP_B = path.join(FIX, 'app_b.cljs');
const TRIGGER = path.join(FIX, 'trigger.cljs');
const TARGET = path.join(IMPL, 'target', 'ui-final-schedule');
const FORCE_MARKER = path.join(TARGET, 'force-recompile-app-a');
const BLIND_MARKER = path.join(TARGET, 'blind-provenance');
const TIMEOUT = 120000;

const BUILDS = [
  { id: 'ui-final-schedule', mode: 'parallel' },
  { id: 'ui-final-schedule-seq', mode: 'sequential' },
];

function fail(message) {
  throw new Error(`FAIL: ${message}`);
}

function observeFile(buildId) {
  return path.join(TARGET, `observe-${buildId}.jsonl`);
}

function readRecords(buildId) {
  const f = observeFile(buildId);
  if (!fs.existsSync(f)) return [];
  return fs.readFileSync(f, 'utf8').trim().split(/\r?\n/).filter(Boolean);
}

function bool(line, key) {
  const m = line.match(new RegExp(`:${key} (true|false)`));
  return m ? m[1] === 'true' : null;
}
function num(line, key) {
  const m = line.match(new RegExp(`:${key} (-?\\d+)`));
  return m ? Number(m[1]) : null;
}
function str(line, key) {
  const m = line.match(new RegExp(`:${key} "([^"]*)"`));
  return m ? m[1] : null;
}
function bFp(line) {
  // :b-fp is the LAST key; its value is a pr-str of app-b's [tf hs] vector,
  // i.e. a Clojure string with escaped quotes. Capture it verbatim up to the
  // record's closing brace, and compare the raw image across passes.
  const m = line.match(/:b-fp (.+)\}\s*$/);
  return m ? m[1] : null;
}

function lastOfStage(buildId, stage) {
  const recs = readRecords(buildId).filter((l) => l.includes(`:stage :${stage}`));
  return recs.length ? recs[recs.length - 1] : null;
}
function countStage(buildId, stage) {
  return readRecords(buildId).filter((l) => l.includes(`:stage :${stage}`)).length;
}

function editTrigger(marker) {
  const src = fs.readFileSync(TRIGGER, 'utf8');
  const next = src.replace(/final-schedule-trigger-marker:\S+/,
    `final-schedule-trigger-marker:${marker}`)
    .replace(/\(def trigger :\S+\)/, `(def trigger :${marker})`);
  if (next === src) fail(`trigger edit was a no-op for marker ${marker}`);
  fs.writeFileSync(TRIGGER, next);
}

function watch(buildId) {
  const child = spawnHarnessProcess(process.execPath, [
    require.resolve('shadow-cljs/cli/runner.js', { paths: [IMPL] }),
    'watch', buildId,
  ], { cwd: FIX, env: process.env, stdio: ['ignore', 'pipe', 'pipe'] });

  let successes = 0;
  let failures = 0;
  let transcript = '';
  let buf = '';
  const waiters = [];
  function settle() {
    for (let i = waiters.length - 1; i >= 0; i -= 1) {
      const w = waiters[i];
      const c = w.kind === 'success' ? successes : failures;
      if (c > w.after) { clearTimeout(w.timer); waiters.splice(i, 1); w.resolve(c); }
    }
  }
  function ingest(chunk) {
    const t = chunk.toString();
    transcript += t;
    process.stdout.write(t);
    const lines = (buf + t).split(/\r?\n/);
    buf = lines.pop();
    for (const l of lines) {
      if (l.includes(`[:${buildId}] Build completed.`)) successes += 1;
      if (l.includes(`[:${buildId}] Build failure:`)) failures += 1;
    }
    settle();
  }
  child.stdout.on('data', ingest);
  child.stderr.on('data', ingest);
  child.on('exit', (code, signal) => {
    const err = new Error(
      `shadow watch exited before proof completed (code=${code}, signal=${signal})`,
    );
    while (waiters.length) { const w = waiters.pop(); clearTimeout(w.timer); w.reject(err); }
  });
  function wait(kind, after) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(
        `timed out waiting for ${buildId} ${kind} after count ${after}\n${transcript.slice(-3000)}`,
      )), TIMEOUT);
      waiters.push({ kind, after, resolve, reject, timer });
      settle();
    });
  }
  return {
    child,
    waitSuccess: (after) => wait('success', after),
    waitFailure: (after) => wait('failure', after),
    successCount: () => successes,
    failureCount: () => failures,
    transcript: () => transcript,
  };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Poll until the observe file has a NEW finish (or prepare) record beyond the
// count captured before the pass, so a slow disk flush cannot race the assert.
async function awaitRecord(buildId, stage, priorCount) {
  for (let i = 0; i < 100; i += 1) {
    if (countStage(buildId, stage) > priorCount) return;
    await sleep(50);
  }
  fail(`no new :${stage} observe record appeared for ${buildId}`);
}

async function driveBuild({ id, mode }) {
  console.log(`\n=== ${id} (${mode} compile schedule) ===`);
  fs.mkdirSync(TARGET, { recursive: true });
  fs.rmSync(observeFile(id), { force: true });
  fs.rmSync(FORCE_MARKER, { force: true });
  fs.rmSync(BLIND_MARKER, { force: true });

  const w = watch(id);
  try {
    // 1. Cold accept.
    await w.waitSuccess(0);
    await awaitRecord(id, 'finish', 0);
    const cold = lastOfStage(id, 'finish');
    if (!(bool(cold, 'a-present') && bool(cold, 'b-present') && num(cold, 'view-count') === 2)) {
      fail(`${id}: cold accept did not declare both views (${cold})`);
    }
    const digest0 = str(cold, 'digest');
    const bFp0 = bFp(cold);
    if (!/^bd1-[0-9a-f]{16}$/.test(digest0)) fail(`${id}: cold digest invalid (${digest0})`);
    console.log(`  cold accept: both views, digest ${digest0}`);

    // 2. CONTROL warm pass — viewless trigger edit, no markers.
    let sc = w.successCount();
    let fc = countStage(id, 'finish');
    editTrigger('control');
    await w.waitSuccess(sc);
    await awaitRecord(id, 'finish', fc);
    const ctlPrep = lastOfStage(id, 'prepare');
    const ctlFin = lastOfStage(id, 'finish');
    if (bool(ctlPrep, 'app-a-output-present') !== true) {
      fail(`${id}: control — app-a was not an output-present cache hit (${ctlPrep})`);
    }
    if (bool(ctlPrep, 'app-a-pretouched') !== false) {
      fail(`${id}: control — re-frame.ui pre-touched a cache-hit source (${ctlPrep})`);
    }
    if (!(bool(ctlFin, 'a-present') && bool(ctlFin, 'b-present') && num(ctlFin, 'view-count') === 2)) {
      fail(`${id}: control pass lost a view (${ctlFin})`);
    }
    if (str(ctlFin, 'digest') !== digest0) {
      fail(`${id}: viewless trigger edit moved the digest (${str(ctlFin, 'digest')} != ${digest0})`);
    }
    console.log('  control warm pass: both views survive, digest stable, app-a not pre-touched');

    // 3. FORCED-EVICT warm pass — build-local hook forces a viewless recompile.
    fs.writeFileSync(FORCE_MARKER, 'x');
    sc = w.successCount();
    fc = countStage(id, 'finish');
    editTrigger('force');
    await w.waitSuccess(sc);
    await awaitRecord(id, 'finish', fc);
    const forcePrep = lastOfStage(id, 'prepare');
    const forceFin = lastOfStage(id, 'finish');
    if (bool(forcePrep, 'app-a-output-present') !== true) {
      fail(`${id}: forced-evict — app-a was not output-present at re-frame.ui prepare (${forcePrep})`);
    }
    // The load-bearing proof: app-a is NOT pre-touched at prepare, so the ONLY
    // mechanism that can evict it is final-schedule reconciliation at finish.
    if (bool(forcePrep, 'app-a-pretouched') !== false) {
      fail(`${id}: forced-evict — app-a was pre-touched at prepare; reconcile would be moot (${forcePrep})`);
    }
    if (bool(forceFin, 'a-present') !== false) {
      fail(`${id}: forced-evict — app-a's ghost view survived (reconcile-final-schedule did not evict it) (${forceFin})`);
    }
    if (bool(forceFin, 'b-present') !== true || num(forceFin, 'view-count') !== 1) {
      fail(`${id}: forced-evict — expected exactly app-b to remain (${forceFin})`);
    }
    const digest1 = str(forceFin, 'digest');
    if (digest1 === digest0) {
      fail(`${id}: forced-evict — evicting app-a did not change the whole-build digest (${digest1})`);
    }
    if (!/^bd1-[0-9a-f]{16}$/.test(digest1)) fail(`${id}: forced-evict digest invalid (${digest1})`);
    if (bFp(forceFin) !== bFp0) {
      fail(`${id}: forced-evict — app-b's cache-hit row was not byte-identical (${bFp(forceFin)} != ${bFp0})`);
    }
    console.log(`  forced-evict pass: app-a evicted via reconcile, app-b byte-identical, digest ${digest0} -> ${digest1}`);
    fs.rmSync(FORCE_MARKER, { force: true });

    // 4. FAIL-LOUD pass — build-local hook destroys per-pass provenance.
    fs.writeFileSync(BLIND_MARKER, 'x');
    const failBefore = w.failureCount();
    const finBefore = countStage(id, 'finish');
    editTrigger('blind');
    await w.waitFailure(failBefore);
    await sleep(300);
    const tx = w.transcript();
    if (!/refusing to reconcile against an assumed-empty compile schedule/.test(tx) &&
        !/missing-pass-provenance/.test(tx)) {
      fail(`${id}: fail-loud — build failed but not with the missing-provenance diagnostic\n${tx.slice(-1500)}`);
    }
    if (countStage(id, 'finish') !== finBefore) {
      fail(`${id}: fail-loud — a candidate was finalized despite unavailable schedule evidence`);
    }
    console.log('  fail-loud pass: unavailable schedule evidence failed the build before publication');
    fs.rmSync(BLIND_MARKER, { force: true });

    console.log(`  PASS ${id}`);
  } finally {
    fs.writeFileSync(TRIGGER, triggerOriginal);
    fs.rmSync(FORCE_MARKER, { force: true });
    fs.rmSync(BLIND_MARKER, { force: true });
    await terminateProcessTree(w.child, { timeoutMs: 5000 });
  }
}

let triggerOriginal;

async function run() {
  const appAOriginal = fs.readFileSync(APP_A, 'utf8');
  const appBOriginal = fs.readFileSync(APP_B, 'utf8');
  triggerOriginal = fs.readFileSync(TRIGGER, 'utf8');
  if (!/final-schedule-trigger-marker:v1/.test(triggerOriginal)) {
    fail('trigger fixture is not at its checked-in v1 marker');
  }
  // Fresh slate: a prior run's cache/output/markers must not mask a regression.
  fs.rmSync(TARGET, { recursive: true, force: true });
  fs.rmSync(path.join(IMPL, 'out', 'ui-final-schedule'), { recursive: true, force: true });
  fs.rmSync(path.join(IMPL, 'out', 'ui-final-schedule-seq'), { recursive: true, force: true });
  try {
    for (const build of BUILDS) {
      await driveBuild(build);
    }
    console.log('\nui final-schedule reconciliation: PASS (parallel + sequential)');
  } finally {
    fs.writeFileSync(APP_A, appAOriginal);
    fs.writeFileSync(APP_B, appBOriginal);
    fs.writeFileSync(TRIGGER, triggerOriginal);
  }
}

module.exports = { run };

if (require.main === module) {
  run().catch((error) => {
    console.error(error.stack || error.message || String(error));
    process.exitCode = 1;
  });
}
