#!/usr/bin/env node
'use strict';

// rf2-tm1xi (Arm 2 of rf2-4vm19) — a real Shadow warm-watch/file-edit fixture
// proving the re-frame.ui custom-element HARVEST TOPOLOGY survives real warm
// edits, that a removed/renamed saved source's `:build-sources` graph delta
// evicts (or re-owns) its runtime declarations, and (rf2-u53yy.1 S6) that a real
// daemon restart REUSES Shadow's disk cache for the UI-consuming sources without
// re-expanding them. It complements the
// synthetic JVM fixtures (compiler_harvest_hook_jvm_test / custom_element_
// warm_staleness_jvm_test), which hand-build build state and cannot exercise a
// real Shadow watch, real inherited-then-build-local hook deep-merge, or real
// file delete/rename/move.
//
// Per the rf2-4vm19 Arm-1 ruling the build-topology obligation is proving ONE
// real dev build's identity at the runtime seam — a non-::default build id, the
// inherited hook's discovery/order, and stage-annotation load-bearingness — NOT
// two-build isolation (a broken realm Shadow does not support). This gate reads
// only the accepted topology re-frame.ui's inherited hook establishes; a
// build-local OBSERVE hook (deep-merged AFTER, never mutating build state)
// records it at :compile-finish.
//
// ONE warm daemon is walked, via real file edits, through:
//   1. COLD ACCEPT — card declares :probe-card #{:model :size}, leaf declares
//      :probe-leaf #{:flag}, the no-require-edge view consumes :probe-card. The
//      accepted build id is :ui-warm-watch (never ::default): the inherited hook
//      ran and its stage annotation is load-bearing.
//   2. ZERO-DECLARATION warm pass — a viewless/declarationless trigger edit. The
//      manifest is unchanged, the consumer view is a warm cache hit (coarse
//      invalidation does NOT over-fire).
//   3. DECLARATION-SHRINK warm pass — card drops :size. The harvest re-reads card
//      (manifest :probe-card -> #{:model}) and the coarse manifest-change
//      invalidation RECOMPILES the no-require-edge consumer view so it re-bakes.
//      If the warm rebuild failed to re-harvest, the manifest would still read
//      #{:model :size} -> RED.
//   4. SAME-NAMESPACE FILE MOVE — leaf.cljs is relocated to the fixture's second
//      source-path root, keeping its namespace. Because re-frame.ui's eviction
//      unit is the declaring NAMESPACE (not the resource/file), :probe-leaf's
//      ownership survives the file move: it stays in the manifest and `…leaf`
//      stays in `:build-sources`.
//   5. RENAME — leaf's namespace becomes `…leaf-renamed` and the client `:require`
//      is repointed. The old namespace leaves `:build-sources`, the new one
//      enters, and :probe-leaf is re-owned by the renamed namespace.
//   6. DELETE — the renamed source and its client `:require` are removed. Its
//      namespace leaves `:build-sources` with no successor, so :probe-leaf is
//      EVICTED from the accepted manifest — the removed saved-source graph delta,
//      applied with no page reload.
//   7. FAILED COMPILE — card is rewritten to a syntax error. The build fails,
//      no candidate is finalized (no new :finish record), and the accepted
//      last-known-good manifest is preserved.
//   8. SUCCESSFUL RETRY — card is fixed. The build converges clean and the
//      manifest matches the last good state (:probe-card #{:model}).
//
// TEETH (documented mutation/revert; run at development time, NOT committed):
//   * Drop the `{:shadow.build/stages …}` annotation on
//     re-frame.ui.compiler.build-hook/hook -> Shadow never runs the inherited
//     hook -> the accepted build id falls back to ::default and no manifest is
//     established -> COLD ACCEPT goes RED (annotation load-bearingness).
//   * Delete the `build/set-shadow-element-manifest (harvest-all-seed …)` call (or
//     the whole harvest) -> :probe-card is never seeded, so COLD ACCEPT's
//     card-props read `nil`/attributes -> RED (the real source producer is
//     load-bearing).
//   * Delete the `(reconcile-declaration-edits build-state)` call -> the
//     DECLARATION-SHRINK pass no longer recompiles the no-require-edge view
//     (view-output-present stays true) and the stale manifest is not re-baked
//     -> RED (coarse invalidation).
//   * Break `keep-members`/membership eviction in shadow-finish-candidate -> the
//     DELETE pass keeps :probe-leaf's ghost declaration (leaf-tag-present stays
//     true) -> RED (the `:build-sources` delta).
// All were exercised red-before-green during development.

const fs = require('fs');
const path = require('path');
const {
  spawnHarnessProcess,
  terminateProcessTree,
} = require('./lib/local-browser-harness.cjs');

const IMPL = path.join(__dirname, '..');
const FIX = path.join(
  IMPL, 'ui', 'test', 're_frame', 'ui', 'digest_probe', 'warm_watch',
);
const CLIENT = path.join(FIX, 'client.cljs');
const CARD = path.join(FIX, 'card.cljs');
const VIEW = path.join(FIX, 'view.cljs');
const TRIGGER = path.join(FIX, 'trigger.cljs');
const LEAF = path.join(FIX, 'leaf.cljs');
const ALT_LEAF = path.join(
  FIX, 'alt_src', 're_frame', 'ui', 'digest_probe', 'warm_watch', 'leaf.cljs',
);
const LEAF_RENAMED = path.join(FIX, 'leaf_renamed.cljs');
const TARGET = path.join(IMPL, 'target', 'ui-warm-watch');
const OUT = path.join(IMPL, 'out', 'ui-warm-watch');
const BUILD_ID = 'ui-warm-watch';
const TIMEOUT = 120000;

function fail(message) {
  throw new Error(`FAIL: ${message}`);
}

function observeFile() {
  return path.join(TARGET, `observe-${BUILD_ID}.jsonl`);
}
function readRecords() {
  const f = observeFile();
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
function lastOfStage(stage) {
  const recs = readRecords().filter((l) => l.includes(`:stage :${stage}`));
  return recs.length ? recs[recs.length - 1] : null;
}
function countStage(stage) {
  return readRecords().filter((l) => l.includes(`:stage :${stage}`)).length;
}

// --- real source edits ------------------------------------------------------

function editTrigger(marker) {
  const src = fs.readFileSync(TRIGGER, 'utf8');
  const next = src
    .replace(/warm-watch-trigger-marker:\S+/, `warm-watch-trigger-marker:${marker}`)
    .replace(/\(def trigger :\S+\)/, `(def trigger :${marker})`);
  if (next === src) fail(`trigger edit was a no-op for marker ${marker}`);
  fs.writeFileSync(TRIGGER, next);
}

// Rewrite the whole card source. A full write (not a regex patch) is used so the
// same edit can both SHRINK a well-formed declaration and REPAIR the broken card
// on the successful-retry pass.
function writeCard(image, marker) {
  fs.writeFileSync(CARD,
    '(ns re-frame.ui.digest-probe.warm-watch.card\n'
    + '  (:require [re-frame.ui :as ui]))\n'
    + `;; warm-watch-card-properties:${marker}\n`
    + `(ui/custom-element :probe-card {:properties ${image}})\n`);
}

function breakCard() {
  fs.writeFileSync(CARD,
    '(ns re-frame.ui.digest-probe.warm-watch.card\n'
    + '  (:require [re-frame.ui :as ui]))\n'
    + ';; warm-watch-card-properties:broken\n'
    + '(ui/custom-element :probe-card {:properties #{:model\n'); // unbalanced
}

// Replace the client's require block between the sentinel comments.
function setClientLeaf(mode) {
  const src = fs.readFileSync(CLIENT, 'utf8');
  const lines = {
    leaf: '            [re-frame.ui.digest-probe.warm-watch.leaf]',
    'leaf-renamed': '            [re-frame.ui.digest-probe.warm-watch.leaf-renamed]',
    none: null,
  };
  const body = [
    '            ;; warm-watch-client-requires:start',
    '            [re-frame.ui.digest-probe.warm-watch.card]',
    '            [re-frame.ui.digest-probe.warm-watch.view]',
    ...(lines[mode] ? [lines[mode]] : []),
    '            ;; warm-watch-client-requires:end',
  ].join('\n');
  const next = src.replace(
    /\s*;; warm-watch-client-requires:start[\s\S]*?;; warm-watch-client-requires:end/,
    `\n${body}`,
  );
  if (next === src) fail(`client require edit was a no-op for mode ${mode}`);
  fs.writeFileSync(CLIENT, next);
}

// --- shadow watch driver -----------------------------------------------------

function watch() {
  const child = spawnHarnessProcess(process.execPath, [
    require.resolve('shadow-cljs/cli/runner.js', { paths: [IMPL] }),
    'watch', BUILD_ID,
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
      if (l.includes(`[:${BUILD_ID}] Build completed.`)) successes += 1;
      if (l.includes(`[:${BUILD_ID}] Build failure:`)) failures += 1;
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
        `timed out waiting for ${kind} after count ${after}\n${transcript.slice(-3000)}`,
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

async function awaitRecord(stage, priorCount) {
  for (let i = 0; i < 100; i += 1) {
    if (countStage(stage) > priorCount) return;
    await sleep(50);
  }
  fail(`no new :${stage} observe record appeared`);
}

// --- the proof --------------------------------------------------------------

async function coldAccept(w) {
  await w.waitSuccess(0);
  await awaitRecord('finish', 0);
  const fin = lastOfStage('finish');
  if (str(fin, 'accepted-build-id') !== ':ui-warm-watch') {
    fail(`cold accept: accepted build id is not the real dev build id (${str(fin, 'accepted-build-id')})`);
  }
  if (str(fin, 'card-props') !== '[:model :size]') {
    fail(`cold accept: :probe-card did not harvest #{:model :size} (${str(fin, 'card-props')})`);
  }
  if (str(fin, 'leaf-props') !== '[:flag]' || bool(fin, 'leaf-tag-present') !== true) {
    fail(`cold accept: :probe-leaf not declared (${fin})`);
  }
  if (!(bool(fin, 'member-card') && bool(fin, 'member-leaf') && bool(fin, 'member-view'))) {
    fail(`cold accept: a probe namespace is missing from :build-sources (${fin})`);
  }
  if (bool(fin, 'view-present') !== true) fail(`cold accept: the consumer view is missing (${fin})`);
  console.log(`  cold accept: build id ${str(fin, 'accepted-build-id')}, :probe-card #{:model :size}, :probe-leaf #{:flag}`);
}

async function zeroDeclarationPass(w) {
  const sc = w.successCount();
  const fc = countStage('finish');
  editTrigger('control');
  await w.waitSuccess(sc);
  await awaitRecord('finish', fc);
  const prep = lastOfStage('prepare');
  const fin = lastOfStage('finish');
  if (bool(prep, 'view-output-present') !== true) {
    fail(`zero-declaration: coarse invalidation over-fired on a non-declaration edit (${prep})`);
  }
  if (str(fin, 'card-props') !== '[:model :size]' || str(fin, 'leaf-props') !== '[:flag]') {
    fail(`zero-declaration: a viewless edit perturbed the manifest (${fin})`);
  }
  console.log('  zero-declaration pass: manifest stable, consumer view a warm cache hit');
}

async function declarationShrinkPass(w) {
  const sc = w.successCount();
  const fc = countStage('finish');
  writeCard('#{:model}', 'model');
  await w.waitSuccess(sc);
  await awaitRecord('finish', fc);
  const prep = lastOfStage('prepare');
  const fin = lastOfStage('finish');
  if (str(fin, 'card-props') !== '[:model]') {
    fail(`declaration-shrink: the warm rebuild did not re-harvest :probe-card (${str(fin, 'card-props')})`);
  }
  if (bool(prep, 'view-output-present') !== false) {
    fail(`declaration-shrink: coarse invalidation did not recompile the no-require-edge consumer view (${prep})`);
  }
  console.log('  declaration-shrink pass: :probe-card -> #{:model}, consumer view re-baked (coarse invalidation)');
}

async function sameNamespaceMovePass(w) {
  const sc = w.successCount();
  const fc = countStage('finish');
  fs.mkdirSync(path.dirname(ALT_LEAF), { recursive: true });
  fs.renameSync(LEAF, ALT_LEAF); // same namespace, new source-path root
  await w.waitSuccess(sc);
  await awaitRecord('finish', fc);
  const fin = lastOfStage('finish');
  if (bool(fin, 'leaf-tag-present') !== true || str(fin, 'leaf-props') !== '[:flag]') {
    fail(`same-namespace move: :probe-leaf's ownership did not survive the file move (${fin})`);
  }
  if (bool(fin, 'member-leaf') !== true) {
    fail(`same-namespace move: the moved namespace left :build-sources (${fin})`);
  }
  console.log('  same-namespace file move: :probe-leaf ownership preserved (eviction unit is the namespace, not the file)');
}

async function renamePass(w) {
  const sc = w.successCount();
  const fc = countStage('finish');
  fs.writeFileSync(LEAF_RENAMED,
    '(ns re-frame.ui.digest-probe.warm-watch.leaf-renamed\n'
    + '  (:require [re-frame.ui :as ui]))\n'
    + '(ui/custom-element :probe-leaf {:properties #{:flag}})\n');
  setClientLeaf('leaf-renamed');
  fs.rmSync(ALT_LEAF, { force: true });
  await w.waitSuccess(sc);
  await awaitRecord('finish', fc);
  const fin = lastOfStage('finish');
  if (bool(fin, 'member-leaf') !== false) {
    fail(`rename: the old namespace was not removed from :build-sources (${fin})`);
  }
  if (bool(fin, 'member-leaf-renamed') !== true) {
    fail(`rename: the renamed namespace did not enter :build-sources (${fin})`);
  }
  if (bool(fin, 'leaf-tag-present') !== true) {
    fail(`rename: :probe-leaf was lost across a namespace rename (${fin})`);
  }
  console.log('  rename: `…leaf` -> `…leaf-renamed` in :build-sources, :probe-leaf re-owned by the renamed namespace');
}

async function deletePass(w) {
  const sc = w.successCount();
  const fc = countStage('finish');
  setClientLeaf('none');
  fs.rmSync(LEAF_RENAMED, { force: true });
  await w.waitSuccess(sc);
  await awaitRecord('finish', fc);
  const fin = lastOfStage('finish');
  if (bool(fin, 'member-leaf-renamed') !== false) {
    fail(`delete: the deleted namespace remained in :build-sources (${fin})`);
  }
  if (bool(fin, 'leaf-tag-present') !== false) {
    fail(`delete: :probe-leaf's ghost declaration survived (the :build-sources delta did not evict it) (${fin})`);
  }
  if (str(fin, 'card-props') !== '[:model]') {
    fail(`delete: an unrelated declaration was disturbed (${fin})`);
  }
  console.log('  delete: `…leaf-renamed` left :build-sources, :probe-leaf evicted from the manifest with no page reload');
}

async function failedCompileThenRetry(w) {
  // FAILED COMPILE — last-known-good manifest preserved.
  const failBefore = w.failureCount();
  const finBefore = countStage('finish');
  breakCard();
  await w.waitFailure(failBefore);
  await sleep(300);
  if (countStage('finish') !== finBefore) {
    fail('failed-compile: a candidate was finalized despite a compile failure');
  }
  console.log('  failed compile: build failed, no candidate finalized, last-known-good manifest preserved');

  // SUCCESSFUL RETRY — converges clean at the last good manifest.
  const sc = w.successCount();
  writeCard('#{:model}', 'model'); // rewrites the broken file to the good #{:model} form
  await w.waitSuccess(sc);
  await awaitRecord('finish', finBefore);
  const fin = lastOfStage('finish');
  if (str(fin, 'card-props') !== '[:model]') {
    fail(`retry: the fixed build did not converge to the last good manifest (${str(fin, 'card-props')})`);
  }
  console.log('  successful retry: build converged clean, :probe-card #{:model}');
}

// WARM DISK-CACHE REUSE — the S6 binary acceptance (rf2-u53yy.1). Stop the watch
// daemon and start a FRESH one with the same cache-root and untouched sources: a
// real cold-daemon start over a primed disk cache. Every UI-consuming source is a
// genuine disk-cache HIT (`:view-cached true`), yet the view registry and the
// custom-element manifest are RESTORED intact from the cache-durable analyzer
// descriptors WITHOUT the macros re-running. This is the path the old
// `:cache-blockers #{re-frame.ui}` tax used to disable; dropping it is only
// correct if this stays green. TEETH: revert the S6 build-hook cut-over (restore
// the cache blocker) and `:view-cached` reads false — the sources recompile and
// reuse never happens; break the compile-finish analyzer harvest and
// `:view-present` reads false on the cache-hit restart.
async function warmRestartReuse(w) {
  await terminateProcessTree(w.child, { timeoutMs: 5000 });
  await sleep(1500); // let the JVM release the cache-root / output locks (Windows)
  const finBefore = countStage('finish'); // observe records accumulate across daemons
  const w2 = watch();
  try {
    await w2.waitSuccess(0);
    await awaitRecord('finish', finBefore);
    const fin = lastOfStage('finish');
    if (str(fin, 'accepted-build-id') !== ':ui-warm-watch') {
      fail(`warm restart: accepted build id regressed (${str(fin, 'accepted-build-id')})`);
    }
    if (bool(fin, 'view-cached') !== true) {
      fail(`warm restart: the consumer view was RE-COMPILED, not served from the disk cache — warm-cache reuse is not happening (${fin})`);
    }
    if (bool(fin, 'view-present') !== true) {
      fail(`warm restart: the view registry was NOT restored from the analyzer descriptor on a disk-cache hit (${fin})`);
    }
    if (str(fin, 'card-props') !== '[:model]') {
      fail(`warm restart: the custom-element manifest was not intact after the disk-cache restart (${str(fin, 'card-props')})`);
    }
  } catch (e) {
    await terminateProcessTree(w2.child, { timeoutMs: 5000 });
    throw e;
  }
  console.log('  warm restart: UI sources served from disk cache (:view-cached true), view registry + manifest RESTORED without re-expansion — WARM-CACHE REUSE PROVEN');
  return w2;
}

async function drive() {
  console.log(`\n=== ${BUILD_ID} (real warm watch, parallel compile schedule) ===`);
  let active = watch();
  try {
    await coldAccept(active);
    await zeroDeclarationPass(active);
    await declarationShrinkPass(active);
    await sameNamespaceMovePass(active);
    await renamePass(active);
    await deletePass(active);
    await failedCompileThenRetry(active);
    active = await warmRestartReuse(active);
    console.log(`  PASS ${BUILD_ID}`);
  } finally {
    await terminateProcessTree(active.child, { timeoutMs: 5000 });
  }
}

let originals;

function snapshot() {
  originals = {
    [CLIENT]: fs.readFileSync(CLIENT, 'utf8'),
    [CARD]: fs.readFileSync(CARD, 'utf8'),
    [VIEW]: fs.readFileSync(VIEW, 'utf8'),
    [TRIGGER]: fs.readFileSync(TRIGGER, 'utf8'),
    [LEAF]: fs.readFileSync(LEAF, 'utf8'),
  };
}

function restore() {
  for (const [p, content] of Object.entries(originals)) fs.writeFileSync(p, content);
  fs.rmSync(ALT_LEAF, { force: true });
  fs.rmSync(LEAF_RENAMED, { force: true });
}

async function run() {
  snapshot();
  if (!/warm-watch-trigger-marker:v1/.test(originals[TRIGGER])) {
    fail('trigger fixture is not at its checked-in v1 marker');
  }
  // Fresh slate: a prior run's cache/output/markers must not mask a regression.
  fs.rmSync(TARGET, { recursive: true, force: true });
  fs.rmSync(OUT, { recursive: true, force: true });
  fs.mkdirSync(TARGET, { recursive: true });
  try {
    await drive();
    console.log('\nui warm-watch harvest topology: PASS');
  } finally {
    restore();
  }
}

module.exports = { run };

if (require.main === module) {
  run().catch((error) => {
    console.error(error.stack || error.message || String(error));
    process.exitCode = 1;
  });
}
