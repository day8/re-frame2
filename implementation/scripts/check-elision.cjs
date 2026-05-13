#!/usr/bin/env node
/*
 * Production-elision verifier (Spec 009 §Production builds, bead rf2-11hn).
 *
 * Runs after `shadow-cljs release elision-probe` (and optionally
 * `release elision-probe-control`) and asserts:
 *
 *   1. The :elision-probe bundle (goog.DEBUG=false) does NOT contain any
 *      of the DEV_ONLY_SENTINELS — strings from re-frame.schemas /
 *      re-frame.trace / re-frame.registrar that only appear inside
 *      branches gated on `re-frame.interop/debug-enabled?`. If any
 *      sentinel survives, dead-code elimination has failed somewhere
 *      and the elision contract is broken.
 *
 *   2. The :elision-probe-control bundle (goog.DEBUG=true), if present,
 *      DOES contain those sentinels. This confirms the grep
 *      methodology has signal — without the control, a refactor that
 *      moved the strings somewhere else would silently turn the
 *      negative assertion into a vacuous pass.
 *
 * Strategy: grep, not parse. The closure compiler may rename symbols
 * but does not rewrite string literals.  String literals from a
 * gated branch are eliminated when the branch's `(when ...)` is dead.
 *
 * Sentinels are chosen because they:
 *   - appear ONLY inside an interop/debug-enabled?-gated branch
 *   - are textual fragments not synthesised from keywords
 *   - are unique enough that a global grep is unambiguous
 *
 * Exit 0 on PASS, 1 on FAIL.
 */

'use strict';

const fs   = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');

// ----- the elision contract --------------------------------------------------

// Each sentinel is a string that appears ONLY inside a
// (when interop/debug-enabled? ...) branch in the framework source.  If
// any of these appears in the production bundle, that branch survived
// dead-code elimination — i.e. the elision contract is broken.
//
// Sentinels are grouped by source so failures point at the leak.
const DEV_ONLY_SENTINELS = [
  // re-frame.schemas — validate-app-db! reason string.
  { source: 're-frame.schemas/validate-app-db!',
    sentinel: 'App-db at path ' },
  // re-frame.schemas — validate-event! reason string.
  { source: 're-frame.schemas/validate-event!',
    sentinel: ' payload failed schema ' },
  // re-frame.schemas — validate-sub-return! reason string.
  { source: 're-frame.schemas/validate-sub-return!',
    sentinel: ' return value failed schema ' },
  // re-frame.schemas — validate-cofx! reason string.
  { source: 're-frame.schemas/validate-cofx!',
    sentinel: ' injected value failed schema ' },
  // re-frame.registrar — handler-replaced trace op (only emitted from a
  // gated branch in registrar/register!).  Keywords survive :advanced
  // as string literals; this is a structural sentinel.
  { source: 're-frame.registrar/register! (handler-replaced)',
    sentinel: 'rf.registry/handler-replaced' },
  // re-frame.registrar — handler-registered trace op.
  { source: 're-frame.registrar/register! (handler-registered)',
    sentinel: 'rf.registry/handler-registered' },
  // re-frame.registrar — handler-cleared trace op.
  { source: 're-frame.registrar/unregister! / clear-kind! (handler-cleared)',
    sentinel: 'rf.registry/handler-cleared' },
  // re-frame.router — :event/dispatched trace op (rf2-smee dispatch-id
  // correlation).  Emitted via trace/emit! whose body is gated; the
  // operation keyword should not survive.
  { source: 're-frame.router/emit-dispatched-trace! (event/dispatched)',
    sentinel: 'event/dispatched' },
  // re-frame.http-managed — :rf.http/retry-attempt trace op (Spec 014
  // §Retry and backoff). Emitted from `(when interop/debug-enabled? ...)`
  // branches in maybe-retry! / maybe-retry-jvm!. Both transports' emit
  // sites must elide; the keyword's string fragment should not survive.
  { source: 're-frame.http-managed/maybe-retry! (rf.http/retry-attempt)',
    sentinel: 'rf.http/retry-attempt' },
  // re-frame.http-managed — :rf.warning/decode-defaulted trace op (Spec
  // 014 §:auto). Emitted only when the user did NOT supply :decode AND
  // interop/debug-enabled?. The string fragment must elide in production.
  { source: 're-frame.http-managed/maybe-emit-decode-defaulted! (rf.warning/decode-defaulted)',
    sentinel: 'rf.warning/decode-defaulted' },
  // re-frame.http-managed — :rf.http/aborted-on-actor-destroy trace op
  // (Spec 014 §Abort on actor destroy, rf2-wvkn). Emitted by
  // abort-on-actor-destroy when the cancellation-cascade hook fires.
  // The emit site is `(when interop/debug-enabled? ...)`; the string
  // fragment must elide in production.
  { source: 're-frame.http-managed/abort-on-actor-destroy (rf.http/aborted-on-actor-destroy)',
    sentinel: 'rf.http/aborted-on-actor-destroy' },
  // re-frame.http-managed — :rf.http/managed-canned-success canned-stub
  // fx (Spec 014 §Testing, rf2-omsae). The fx registration sits inside
  // `(when interop/debug-enabled? ...)` so the fx-id keyword's string
  // fragment, the doc string, and the handler-var reference must all
  // elide in `:advanced + goog.DEBUG=false` bundles. The probe does NOT
  // reference this fx-id as a literal keyword in its own code path —
  // the gated registration body is the only source of reachability for
  // the literal, which is what the elision assertion turns on.
  { source: 're-frame.http-managed (rf.http/managed-canned-success registration)',
    sentinel: 'rf.http/managed-canned-success' },
  // re-frame.http-managed — :rf.http/managed-canned-failure canned-stub
  // fx (Spec 014 §Testing, rf2-omsae). Same elision gate and same
  // probe contract as `:rf.http/managed-canned-success` above.
  { source: 're-frame.http-managed (rf.http/managed-canned-failure registration)',
    sentinel: 'rf.http/managed-canned-failure' },
  // re-frame.epoch — :rf.epoch/snapshotted trace op (Tool-Pair §Time-
  // travel, Spec 009 §register-epoch-cb). Emitted by settle! after a
  // drain-settle commits a record. The whole settle! body sits inside
  // `(when interop/debug-enabled? ...)`; the string fragment must elide.
  { source: 're-frame.epoch/settle! (rf.epoch/snapshotted)',
    sentinel: 'rf.epoch/snapshotted' },
  // re-frame.epoch — :rf.epoch/restored trace op. Emitted on the happy
  // path of restore-epoch. The entire restore-epoch body is guarded by
  // an `(if-not interop/debug-enabled? false ...)` early-return so the
  // success arm and its string sentinel must elide in production.
  { source: 're-frame.epoch/restore-epoch (rf.epoch/restored)',
    sentinel: 'rf.epoch/restored' },
  // re-frame.epoch — :rf.epoch/restore-during-drain failure mode.
  // Emitted via emit-restore-failure! when restore-epoch is called
  // while a drain is in flight. Same elision gate as :rf.epoch/restored.
  { source: 're-frame.epoch/restore-epoch (rf.epoch/restore-during-drain)',
    sentinel: 'rf.epoch/restore-during-drain' },
  // re-frame.epoch — :rf.epoch/restore-unknown-epoch failure mode.
  // Emitted when the requested epoch-id is no longer in the frame's
  // ring buffer. Must elide.
  { source: 're-frame.epoch/restore-epoch (rf.epoch/restore-unknown-epoch)',
    sentinel: 'rf.epoch/restore-unknown-epoch' },
  // re-frame.epoch — :rf.epoch/restore-schema-mismatch failure mode.
  // Emitted when the recorded :db-after no longer validates against
  // every currently-registered app-schema. Must elide.
  { source: 're-frame.epoch/restore-epoch (rf.epoch/restore-schema-mismatch)',
    sentinel: 'rf.epoch/restore-schema-mismatch' },
  // re-frame.epoch — :rf.epoch/restore-missing-handler failure mode.
  // Emitted when the recorded db references a registration (machine,
  // route) that is no longer present in the registrar. Must elide.
  { source: 're-frame.epoch/restore-epoch (rf.epoch/restore-missing-handler)',
    sentinel: 'rf.epoch/restore-missing-handler' },
  // re-frame.epoch — :rf.epoch/restore-version-mismatch failure mode.
  // Emitted when a machine snapshot's :rf/snapshot-version differs
  // from the currently-registered machine's :version. Must elide.
  { source: 're-frame.epoch/restore-epoch (rf.epoch/restore-version-mismatch)',
    sentinel: 'rf.epoch/restore-version-mismatch' },
  // re-frame.epoch — :rf.epoch/db-replaced trace op (Tool-Pair §Pair-tool
  // writes, rf2-zq55). Emitted by reset-frame-db! on the success path.
  // The whole reset-frame-db! body is gated by an
  // `(if-not interop/debug-enabled? false ...)` early-return; the success
  // arm and its string sentinel must elide under :advanced.
  { source: 're-frame.epoch/reset-frame-db! (rf.epoch/db-replaced)',
    sentinel: 'rf.epoch/db-replaced' },
  // re-frame.epoch — :rf.epoch/reset-frame-db-during-drain failure mode
  // (rf2-zq55). Same elision gate as the success path.
  { source: 're-frame.epoch/reset-frame-db! (rf.epoch/reset-frame-db-during-drain)',
    sentinel: 'rf.epoch/reset-frame-db-during-drain' },
  // re-frame.epoch — :rf.epoch/reset-frame-db-schema-mismatch failure
  // mode (rf2-zq55). Same elision gate.
  { source: 're-frame.epoch/reset-frame-db! (rf.epoch/reset-frame-db-schema-mismatch)',
    sentinel: 'rf.epoch/reset-frame-db-schema-mismatch' },
  // re-frame.epoch — :rf.epoch.cb/silenced-on-frame-destroy listener
  // silencing trace (Tool-Pair §Surface behaviour against destroyed
  // frames, rf2-d656). Emitted by on-frame-destroyed! once per
  // (frame-id, cb-id) pair when a previously-firing cb's observed
  // frame is destroyed. The entire on-frame-destroyed! body sits
  // inside `(when interop/debug-enabled? ...)`; the string fragment
  // must elide in production.
  { source: 're-frame.epoch/on-frame-destroyed! (rf.epoch.cb/silenced-on-frame-destroy)',
    sentinel: 'rf.epoch.cb/silenced-on-frame-destroy' },
  // re-frame.views — :view/render trace op (Spec 004 §Render-tree
  // primitives, rf2-piag / rf2-t5tx). Emitted by the reg-view*
  // wrapper on every render of a registered view; the entire emit
  // body sits inside `(when interop/debug-enabled? ...)`. The
  // operation keyword's string fragment must elide in production —
  // along with the instance-token mint, the *render-key* binding,
  // and the late-bind lookup.
  { source: 're-frame.views/reg-view* frame-aware-view (view/render)',
    sentinel: 'view/render' },
  // re-frame.views — source-coord DOM annotation (Spec 006 §Source-coord
  // annotation, rf2-z7f7 / rf2-z9n1). The reg-view* wrapper merges
  // `:data-rf2-source-coord` onto the rendered root DOM element when
  // `interop/debug-enabled?` is true. The format-source-coord helper
  // and the entire inject-source-coord-attr branch sit inside the
  // same gate; the literal "data-rf2-source-coord" string fragment
  // must NOT appear in :advanced + goog.DEBUG=false bundles.
  { source: 're-frame.views/reg-view* (data-rf2-source-coord injection)',
    sentinel: 'data-rf2-source-coord' },
  // re-frame.core/reg-machine — per-element source-coord stamping
  // (Spec 005 §Source-coord stamping, rf2-8bp3). The reg-machine macro
  // emits an `(if interop/debug-enabled? (assoc spec :rf.machine/
  // source-coords {...}) spec)` branch; under :advanced +
  // goog.DEBUG=false the closure compiler constant-folds the gate to
  // false and DCEs the entire literal coord index. The keyword's
  // `rf.machine/source-coords` string fragment must NOT survive in
  // production bundles.
  { source: 're-frame.core/reg-machine (rf.machine/source-coords stamping)',
    sentinel: 'rf.machine/source-coords' },
  // re-frame.core/{dispatch,dispatch-sync,subscribe,inject-cofx} —
  // call-site source-coord stamping (rf2-ts1a, Q3=B dev-only elision).
  // Each macro emits an `(if interop/debug-enabled? <stamp-branch>
  // <no-stamp-branch>)` expansion; under :advanced + goog.DEBUG=false
  // the closure compiler constant-folds the gate to false and the
  // stamp branch DCEs. The `rf.trace/call-site` keyword's string
  // fragment must NOT survive in production bundles.
  { source: 're-frame.core/{dispatch,subscribe,inject-cofx} (rf.trace/call-site stamping)',
    sentinel: 'rf.trace/call-site' }
  // Note (rf2-d3k3): re-frame.views/maybe-warn-plain-fn-under-non-
  // default-frame! emits :rf.warning/plain-fn-under-non-default-frame-
  // once gated on interop/debug-enabled?. We do NOT add a sentinel
  // entry here because the elision-probe runs outside Reagent's render
  // cycle — `(r/current-component)` is nil — and closure compiler's
  // dataflow analysis under :advanced determines the inner emit branch
  // is unreachable, so the keyword's literal string is dropped from
  // the control build too. The methodology check would be vacuous.
  // The browser-test (re-frame.cross-spec-cljs-test/plain-fn-under-
  // non-default-frame) is the load-bearing assertion that the gate is
  // wired up under DEBUG=true; under :advanced + goog.DEBUG=false the
  // gate is constant-folded false and the body DCE's regardless.
];

// ----- helpers ---------------------------------------------------------------

function readAllJs(dir) {
  // Collect every *.js file under dir into a single concatenated blob.
  // shadow-cljs :browser :advanced may split the runtime across files
  // (cljs_base, the module's own file); a global grep is the right
  // shape for the elision contract.
  if (!fs.existsSync(dir)) {
    return null;
  }
  const out = [];
  const walk = (d) => {
    for (const entry of fs.readdirSync(d, { withFileTypes: true })) {
      const full = path.join(d, entry.name);
      if (entry.isDirectory()) {
        walk(full);
      } else if (entry.isFile() && entry.name.endsWith('.js')) {
        out.push(fs.readFileSync(full, 'utf8'));
      }
    }
  };
  walk(dir);
  return out.join('\n');
}

function checkBundle(label, bundlePath, mustContain) {
  const blob = readAllJs(bundlePath);
  if (blob == null) {
    console.error(`[elision] ${label}: bundle path missing — ${bundlePath}`);
    console.error('         Did you run "shadow-cljs release elision-probe"?');
    return false;
  }
  console.log(`[elision] ${label}: ${bundlePath}`);
  console.log(`          bundle size: ${blob.length} chars`);

  let ok = true;
  for (const { source, sentinel } of DEV_ONLY_SENTINELS) {
    const present = blob.includes(sentinel);
    const expected = mustContain ? 'PRESENT' : 'ABSENT';
    const actual   = present     ? 'PRESENT' : 'ABSENT';
    const passed   = present === mustContain;
    const tag      = passed ? 'OK' : 'FAIL';
    console.log(`          [${tag}] ${source}: sentinel ${JSON.stringify(sentinel)} expected ${expected}, was ${actual}`);
    if (!passed) ok = false;
  }
  return ok;
}

// ----- main ------------------------------------------------------------------

function main() {
  console.log('=== Production elision probe (Spec 009 §Production builds) ===');

  const probeDir   = path.join(ROOT, 'out', 'elision-probe');
  const controlDir = path.join(ROOT, 'out', 'elision-probe-control');

  // Production bundle: sentinels MUST be absent.
  const prodOk = checkBundle('production (goog.DEBUG=false)', probeDir, false);

  // Control bundle: sentinels MUST be present (if compiled).
  let controlOk = true;
  if (fs.existsSync(controlDir)) {
    controlOk = checkBundle('control    (goog.DEBUG=true) ', controlDir, true);
  } else {
    console.log('[elision] control bundle not built — skipping methodology check.');
    console.log('          Run "npx shadow-cljs release elision-probe-control"');
    console.log('          to enable the methodology assertion.');
  }

  if (prodOk && controlOk) {
    console.log('=== PASS ===');
    process.exit(0);
  } else {
    console.error('=== FAIL ===');
    if (!prodOk) {
      console.error('Production elision broke: at least one dev-only sentinel');
      console.error('survived advanced compilation with goog.DEBUG=false.');
      console.error('Per Spec 009 §Production builds, every emit / schema / ');
      console.error('registrar dev-only branch must be gated on');
      console.error('re-frame.interop/debug-enabled? so DCE removes it.');
    }
    if (!controlOk) {
      console.error('Control bundle missing dev-only sentinels — the grep test');
      console.error('would be vacuous.  A sentinel string was likely renamed,');
      console.error('moved out of a gated branch, or removed.  Update');
      console.error('DEV_ONLY_SENTINELS in this script to track the change.');
    }
    process.exit(1);
  }
}

main();
