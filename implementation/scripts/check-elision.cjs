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
const { createGateReporter } = require('./lib/gate-report.cjs');
const { classifyReleaseBundle } = require('./lib/read-release-bundle.cjs');
const { assertSentinelSet } = require('./lib/sentinel-scan.cjs');

const ROOT = path.resolve(__dirname, '..');
const report = createGateReporter();

// ----- the elision contract --------------------------------------------------

// Each sentinel is a string that appears ONLY inside a
// (when interop/debug-enabled? ...) branch in the framework source.  If
// any of these appears in the production bundle, that branch survived
// dead-code elimination — i.e. the elision contract is broken.
//
// Sentinels are grouped by source so failures point at the leak.
const DEV_ONLY_SENTINELS = [
  // re-frame.schemas — validate-app-schema! reason string.
  { source: 're-frame.schemas/validate-app-schema!',
    sentinel: 'App-db at path ' },
  // re-frame.schemas — validate-event! reason string. Per rf2-dz71l
  // the distinctive per-surface slot-tail (" payload failed schema ")
  // is pinned at the call site to the centralised `reason-string`
  // builder — survives Closure as a single string literal inside
  // the (if interop/debug-enabled? ...) gated branch.
  { source: 're-frame.schemas/validate-event!',
    sentinel: ' payload failed schema ' },
  // re-frame.schemas — validate-sub! reason string.
  { source: 're-frame.schemas/validate-sub!',
    sentinel: ' return value failed schema ' },
  // re-frame.schemas — validate-fx! reason string.
  { source: 're-frame.schemas/validate-fx!',
    sentinel: ' args failed schema ' },
  // re-frame.machines.data-validation — :where :machine-data reason
  // string (rf2-jbbp7). The post-commit / spawn-time machine `:data`
  // validators both sit inside `(when interop/debug-enabled? ...)`
  // gates; the distinctive substring " :data failed schema at boundary
  // :where :machine-data " must elide under :advanced + goog.DEBUG=false.
  { source: 're-frame.machines.data-validation/emit-failure!',
    sentinel: ' :data failed schema at boundary :where :machine-data ' },
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
  // re-frame.registrar — :rf.warning/missing-doc trace op (Spec 001
  // §`:doc` is dev-warned when absent, rf2-45kaz). Emitted from
  // maybe-emit-missing-doc! when a public macro-path reg-* call
  // carries no usable :doc. The emit call sits inside the outermost
  // `(when interop/debug-enabled? ...)` gate in register!; under
  // :advanced + goog.DEBUG=false the consult+emit branch and the
  // operation keyword's string literal must elide.
  { source: 're-frame.registrar/register! (rf.warning/missing-doc)',
    sentinel: 'rf.warning/missing-doc' },
  // re-frame.registrar — :rf.warning/registration-collision trace op
  // (Spec 001 §Re-registration of a different function — collision
  // warning, rf2-45kaz). Emitted from maybe-emit-collision! when a
  // re-registration swaps in a different :handler-fn. Sits inside
  // the same gated branch as handler-replaced; the operation
  // keyword's string fragment must elide under :advanced +
  // goog.DEBUG=false.
  { source: 're-frame.registrar/register! (rf.warning/registration-collision)',
    sentinel: 'rf.warning/registration-collision' },
  // re-frame.router — :event/dispatched trace op (rf2-smee dispatch-id
  // correlation).  Emitted via trace/emit! whose body is gated; the
  // operation keyword should not survive.
  { source: 're-frame.router/emit-dispatched-trace! (event/dispatched)',
    sentinel: 'event/dispatched' },
  // re-frame.router — :rf.event/run-start trace op (rf2-9vx0jk). The
  // run-start TRACE emit (distinct from the always-on flat event-emit
  // record — Spec 009 §Emit-gate summary) rides `trace/emit!`, whose body
  // is gated on `interop/debug-enabled?`, so the whole emit DCEs under
  // :advanced + goog.DEBUG=false. This is the emit the dev-only
  // `:rf.interceptor/override-summary` tag rides on: with this sentinel
  // ABSENT in prod, the run-start emit — and the override-summary VALUE
  // construction that feeds its `:tags` (built only by
  // `re-frame.router/override-summary`) — is proven elided. (The
  // `:rf.interceptor/override-summary` KEYWORD itself legitimately survives
  // via the marks chokepoint — see the rf2-9vx0jk NOTE below — but the run-
  // start op keyword has no such always-reachable referent and elides
  // cleanly, so it is the load-bearing grep for the whole-emit DCE.) The
  // trailing `"` pins the op keyword exactly and avoids matching any
  // longer superstring.
  { source: 're-frame.router/run-handler-cascade! (rf.event/run-start)',
    sentinel: 'rf.event/run-start"' },
  // re-frame.http.managed — :rf.http/retry-attempt trace op (Spec 014
  // §Retry and backoff). Emitted from `(when interop/debug-enabled? ...)`
  // branches in maybe-retry! / maybe-retry-jvm!. Both transports' emit
  // sites must elide; the keyword's string fragment should not survive.
  { source: 're-frame.http.managed/maybe-retry! (rf.http/retry-attempt)',
    sentinel: 'rf.http/retry-attempt' },
  // re-frame.http.managed — :rf.http/aborted-on-actor-destroy trace op
  // (Spec 014 §Abort on actor destroy, rf2-wvkn). Emitted by
  // abort-on-actor-destroy when the cancellation-cascade hook fires.
  // The emit site is `(when interop/debug-enabled? ...)`; the string
  // fragment must elide in production.
  { source: 're-frame.http.managed/abort-on-actor-destroy (rf.http/aborted-on-actor-destroy)',
    sentinel: 'rf.http/aborted-on-actor-destroy' },
  //
  // NOTE — rf2-cdmle removed the canned-stub fx-id sentinels
  // (`rf.http/managed-canned-success`, `rf.http/managed-canned-failure`)
  // that used to live here. Earlier the gate was
  // `(when interop/debug-enabled? ...)` inside re-frame.http.managed,
  // and the probe rooted both branches via `:require [re-frame.http.managed]`
  // — the `goog.DEBUG=true` control build saw the literals, the
  // `goog.DEBUG=false` counter build saw them DCE'd.
  //
  // The new gate is the require boundary: the canned-stub fxs register
  // from the sibling `re-frame.http.test-support` namespace. The
  // elision probe MUST NOT require that namespace (doing so would
  // smuggle the canned-stub fx-id keyword literals into BOTH
  // bundles unconditionally). With the require absent, the
  // test-support module is unreferenced from any production module —
  // the `:advanced + goog.DEBUG=false` counter build trims it
  // wholesale, but the control build trims it too because nothing
  // references it. The elision check's positive-presence methodology
  // assertion no longer applies.
  //
  // The replacement contract:
  //   - JVM/SSR absence: pinned by re-frame.http-test-support-absent-test
  //     (negative assertion: requiring re-frame.http.managed alone does
  //     NOT register the canned-stub fxs).
  //   - JVM/SSR presence: pinned by re-frame.http-test-support-test
  //     (smoke: requiring re-frame.http.test-support DOES register them).
  //   - CLJS counter-bundle absence: pinned by check-bundle-isolation.cjs
  //     (the `rf.http/managed-canned-failure` sentinel must not appear
  //     in the no-feature counter bundle).
  //
  // Together these subsume what the two removed sentinels here used to
  // assert.
  // re-frame.epoch — :rf.epoch/snapshotted trace op (Tool-Pair §Time-
  // travel, Spec 009 §register-epoch-listener). Emitted by settle! after a
  // drain-settle commits a record. The whole settle! body sits inside
  // `(when interop/debug-enabled? ...)`; the string fragment must elide.
  { source: 're-frame.epoch/settle! (rf.epoch/snapshotted)',
    sentinel: 'rf.epoch/snapshotted' },
  // re-frame.epoch — :rf.epoch/outcome trace op (rf2-18g1w / rf2-jppad —
  // consumer-facing {:ok :blocked :error} summary paired with
  // :rf.epoch/snapshotted at the cascade trailer). Emitted at the same
  // call site as :rf.epoch/snapshotted (settle! commit-record! + the
  // listeners/on-frame-destroyed! mid-drain destroy path); the whole
  // emit body sits inside the same `(when interop/debug-enabled? ...)`
  // gate, so the string fragment must elide under :advanced + goog.DEBUG=false.
  { source: 're-frame.epoch/settle! (rf.epoch/outcome)',
    sentinel: 'rf.epoch/outcome' },
  // re-frame.epoch — :rf.epoch/restored trace op. Emitted on the happy
  // path of restore-epoch!. The entire restore-epoch! body is guarded by
  // an `(if-not interop/debug-enabled? false ...)` early-return so the
  // success arm and its string sentinel must elide in production.
  { source: 're-frame.epoch/restore-epoch! (rf.epoch/restored)',
    sentinel: 'rf.epoch/restored' },
  // re-frame.epoch — :rf.epoch/restore-during-drain failure mode.
  // Emitted via emit-restore-failure! when restore-epoch! is called
  // while a drain is in flight. Same elision gate as :rf.epoch/restored.
  { source: 're-frame.epoch/restore-epoch! (rf.epoch/restore-during-drain)',
    sentinel: 'rf.epoch/restore-during-drain' },
  // re-frame.epoch — :rf.epoch/restore-unknown-epoch failure mode.
  // Emitted when the requested epoch-id is no longer in the frame's
  // ring buffer. Must elide.
  { source: 're-frame.epoch/restore-epoch! (rf.epoch/restore-unknown-epoch)',
    sentinel: 'rf.epoch/restore-unknown-epoch' },
  // re-frame.epoch — :rf.epoch/restore-schema-mismatch failure mode.
  // Emitted when the recorded :db-after no longer validates against
  // every currently-registered app-schema. Must elide.
  { source: 're-frame.epoch/restore-epoch! (rf.epoch/restore-schema-mismatch)',
    sentinel: 'rf.epoch/restore-schema-mismatch' },
  // re-frame.epoch — :rf.epoch/restore-missing-handler failure mode.
  // Emitted when the recorded db references a registration (machine,
  // route) that is no longer present in the registrar. Must elide.
  { source: 're-frame.epoch/restore-epoch! (rf.epoch/restore-missing-handler)',
    sentinel: 'rf.epoch/restore-missing-handler' },
  // re-frame.epoch — :rf.epoch/restore-version-mismatch failure mode.
  // Emitted when a machine snapshot's :rf/snapshot-version differs
  // from the currently-registered machine's :version. Must elide.
  { source: 're-frame.epoch/restore-epoch! (rf.epoch/restore-version-mismatch)',
    sentinel: 'rf.epoch/restore-version-mismatch' },
  // re-frame.epoch — :rf.epoch/restore-non-ok-record failure mode
  // (rf2-v0jwt). Emitted when restore-epoch! targets an epoch record
  // whose :outcome is not :ok (a halted-cascade record). Same
  // elision gate as the other restore failure modes.
  { source: 're-frame.epoch/restore-epoch! (rf.epoch/restore-non-ok-record)',
    sentinel: 'rf.epoch/restore-non-ok-record' },
  // re-frame.epoch — :rf.epoch/db-replaced trace op (Tool-Pair §Pair-tool
  // writes). Emitted by replace-app-db! on the success path.
  // The whole replace-app-db! body is gated by an
  // `(if-not interop/debug-enabled? false ...)` early-return; the success
  // arm and its string sentinel must elide under :advanced.
  { source: 're-frame.epoch/replace-app-db! (rf.epoch/db-replaced)',
    sentinel: 'rf.epoch/db-replaced' },
  // re-frame.epoch — :rf.epoch/replace-during-drain failure mode
  // (EP-0001 rf2-tfepxu). Same elision gate as the success path.
  { source: 're-frame.epoch/replace-app-db! (rf.epoch/replace-during-drain)',
    sentinel: 'rf.epoch/replace-during-drain' },
  // re-frame.epoch — :rf.epoch/replace-schema-mismatch failure
  // mode (EP-0001 rf2-tfepxu). Same elision gate.
  { source: 're-frame.epoch/replace-app-db! (rf.epoch/replace-schema-mismatch)',
    sentinel: 'rf.epoch/replace-schema-mismatch' },
  // re-frame.epoch — :rf.epoch/replace-history-disabled failure mode
  // (rf2-unpldn). The four mutators refuse under depth 0 (the synthetic
  // undo-anchor cannot land in the disabled ring). Emitted from the shared
  // `check-replace-preconditions!` skeleton, reached via the same
  // debug-gated `(if-not interop/debug-enabled? false ...)` early-return as
  // the other two replace failure modes; the string sentinel must elide.
  { source: 're-frame.epoch/replace-app-db! (rf.epoch/replace-history-disabled)',
    sentinel: 'rf.epoch/replace-history-disabled' },
  // re-frame.epoch — :rf.epoch.cb/silenced-on-frame-destroy listener
  // silencing trace (Tool-Pair §Surface behaviour against destroyed
  // frames, rf2-d656). Emitted by on-frame-destroyed! once per
  // (frame-id, cb-id) pair when a previously-firing cb's observed
  // frame is destroyed. The entire on-frame-destroyed! body sits
  // inside `(when interop/debug-enabled? ...)`; the string fragment
  // must elide in production.
  { source: 're-frame.epoch/on-frame-destroyed! (rf.epoch.cb/silenced-on-frame-destroy)',
    sentinel: 'rf.epoch.cb/silenced-on-frame-destroy' },
  // re-frame.epoch — :rf.warning/epoch-redact-fn-exception (rf2-wp70d,
  // EP-0015 issue 6 rf2-ba0eum). EP-0015 RULED storage-side `:redact-fn`
  // mutation removed: the `:redact-fn` advanced override is now
  // PROJECTION-SIDE only — it runs at the OFF-BOX egress boundary inside
  // `re-frame.epoch.tool-pair/projected-record`, AFTER the frame/profile
  // `re-frame.projection/project-egress` projection, never at storage
  // time (settle! / replace-app-db! / on-frame-destroyed! deliver the
  // raw record UNMUTATED). The warning is emitted by
  // `re-frame.epoch.assembly/apply-redact-fn` when an installed
  // projection-side :redact-fn throws; the emit body sits inside the
  // universal `interop/debug-enabled?` gate (the projection helper is
  // itself gated), so this string literal must elide in :advanced +
  // goog.DEBUG=false.
  { source: 're-frame.epoch.assembly/apply-redact-fn (rf.warning/epoch-redact-fn-exception, projection-side)',
    sentinel: 'rf.warning/epoch-redact-fn-exception' },
  // re-frame.views — :rf.view/render trace op (Spec 004 §Render-tree
  // primitives, rf2-piag / rf2-t5tx). Emitted by the reg-view*
  // wrapper on every render of a registered view; the entire emit
  // body sits inside `(when interop/debug-enabled? ...)`. The
  // operation keyword's string fragment must elide in production —
  // along with the instance-token mint, the *render-key* binding,
  // and the late-bind lookup.
  //
  // The sentinel includes the trailing keyword-terminator quote
  // (`rf.view/render"`) so it matches ONLY the `:rf.view/render` op
  // keyword and NOT the longer `:rf.view/render-args` slot keyword
  // (rf2-rpgq8) that legitimately survives in production via the
  // always-reachable `re-frame.classification/project-trace-event` chokepoint.
  // A bare `view/render` substring would superstring-collide with
  // `rf.view/render-args` and fire a false production-leak positive.
  { source: 're-frame.views/reg-view* frame-aware-view (rf.view/render)',
    sentinel: 'rf.view/render"' },
  // re-frame.views — :rf.view/rendered cascade-attribution op (rf2-25zo2).
  // Emitted alongside :view/render from the same `(when interop/debug-
  // enabled? ...)`-gated emit-render-trace! body; carries :view-id,
  // :frame, :render-key, and (when available) :cause-event-id +
  // :cause-subs for Xray's Reactive panel cascade graphing. The
  // operation keyword's string fragment must elide under :advanced +
  // goog.DEBUG=false alongside the existing view/render sentinel.
  { source: 're-frame.views/reg-view* frame-aware-view (rf.view/rendered)',
    sentinel: 'rf.view/rendered' },
  // re-frame.views — :rf.view/render-args (rf2-rpgq8). The view's
  // positional render args/props are captured in the views.cljs
  // frame-aware-view wrapper under `(when interop/debug-enabled? args)`
  // and stamped under `:rf.view/render-args` inside the SAME gated
  // emit-view-rendered-trace! body as the `rf.view/rendered` op keyword
  // above. Both the capture and the assoc DCE under :advanced +
  // goog.DEBUG=false, so NO raw user render-args reach the production
  // bundle — that absence rides the existing `rf.view/rendered` sentinel
  // (same gated emit body, no separate sentinel needed, exactly like the
  // rf2-9hoos `:mount?` / `:deref-subs` slots).
  //
  // NOTE — we deliberately do NOT add a `rf.view/render-args` keyword
  // sentinel: the keyword literal LEGITIMATELY survives in production via
  // `re-frame.classification/project-trace-event` (the always-reachable marks
  // chokepoint, gated at the trace/emit! CALL site, not internally), the
  // same way `:rf.event/db` / `:rf.cofx/value` / `:rf.sub/run` keyword
  // literals survive there. The privacy guarantee is that the VALUES never
  // leak (capture DCEs in views.cljs; the in-dev marks projection elides
  // the slot value against the frame registry), not that the slot keyword
  // is absent. (Also, `rf.view/render-args` superstring-matches the
  // `view/render` sentinel, so a keyword sentinel here would be a
  // self-defeating false positive.)
  //
  // NOTE (rf2-9vx0jk) — the dev-only `:rf.interceptor/override-summary` tag on
  // `:rf.event/run-start` (Spec 009 §`:tags` interceptor family) follows the
  // SAME precedent as `:rf.view/render-args` above: we deliberately do NOT add
  // a keyword sentinel for it. The `:rf.interceptor/override-summary` keyword
  // literal LEGITIMATELY survives in production via the always-reachable marks
  // chokepoint `re-frame.classification/project-trace-event` (the fail-closed
  // `(contains? tags :rf.interceptor/override-summary)` projection branch),
  // exactly like `:rf.event/db` / `:rf.view/render-args` / `:rf.cofx/value`.
  // A keyword sentinel here would be a false production-leak positive.
  //
  // The privacy guarantee is that the summary VALUE (the id/count map
  // `{:matched … :replaced … :removed … :count …}`) is constructed ONLY inside
  // `re-frame.router/override-summary`, which feeds ONLY the dev-only
  // `:rf.event/run-start` `trace/emit!` call — whose whole body sits inside the
  // `(when interop/debug-enabled? ...)` gate in `re-frame.trace/emit!` and DCEs
  // under :advanced + goog.DEBUG=false. The `re-frame.elision-probe`
  // `touch-interceptor-override-summary!` roots that gated construction in the
  // reachability graph (it dispatches an event WITH per-call
  // `:interceptor-overrides` that removes + replaces a chain entry) so the
  // control build exercises the summary path. The summary carries id-keywords
  // only (never an interceptor value/fn/raw arg), and those ids — being the
  // user's own override-key keywords — would in any case already live in app
  // source; there is no separate raw VALUE for a string sentinel to catch.
  // Production absence of the summary DATA is therefore pinned by the same
  // whole-emit elision that drops the run-start trace body, not by a
  // standalone bundle-grep sentinel.
  // re-frame.views — :rf.view/unmounted teardown op (rf2-9hoos). Emitted
  // by `emit-view-unmounted!` (via the per-render-instance reaction
  // dispose installed by `install-unmount-hook!`) when a registered view
  // instance tears down; carries :view-id, :frame and the :render-key
  // tuple for Xray's Views table to label the `unmount` action. The
  // emit body, the reaction creation, the on-dispose registration, and
  // the in-render deref that arms it all sit inside
  // `(when interop/debug-enabled? ...)`; the operation keyword's string
  // fragment must elide under :advanced + goog.DEBUG=false. (The
  // rf2-9hoos `:mount?` flag and the `:deref-subs` per-view read-set
  // ride the existing `rf.view/rendered` sentinel above — same gated
  // emit body, no separate sentinel needed.)
  { source: 're-frame.views/emit-view-unmounted! (rf.view/unmounted)',
    sentinel: 'rf.view/unmounted' },
  // re-frame.views — source-coord DOM annotation (Spec 006 §Source-coord
  // annotation, rf2-z7f7 / rf2-z9n1). The reg-view* wrapper merges
  // `:data-rf2-source-coord` onto the rendered root DOM element when
  // `interop/debug-enabled?` is true. The format-source-coord helper
  // and the entire inject-source-coord-attr branch sit inside the
  // same gate; the literal "data-rf2-source-coord" string fragment
  // must NOT appear in :advanced + goog.DEBUG=false bundles.
  { source: 're-frame.views/reg-view* (data-rf2-source-coord injection)',
    sentinel: 'data-rf2-source-coord' },
  // re-frame.views — view-id DOM annotation (Spec 006 §View tagging
  // contract, rf2-01il5). The reg-view* wrapper ALSO merges
  // `:data-rf-view` onto the rendered root DOM element when
  // `interop/debug-enabled?` is true — the fallback path for runtime
  // view-hierarchy capture when the Fiber-walker primary path
  // (rf2-mxkq7) is unavailable. The injection rides the SAME gate as
  // data-rf2-source-coord (no separate code path or elision branch);
  // the literal "data-rf-view" string fragment must NOT appear in
  // :advanced + goog.DEBUG=false bundles.
  { source: 're-frame.views/reg-view* (data-rf-view injection)',
    sentinel: 'data-rf-view' },
  // Note (rf2-rohdn): the `_jsxFileName` sentinel was removed when
  // Option A dropped the JSX dev-source-coord prop injection (the
  // feature never worked — Reagent passed the props through as DOM
  // attributes triggering React warnings, and DevTools' "View source"
  // reads `__source` off React.createElement's third arg, not element
  // props). The injection branch is gone; `data-rf2-source-coord` +
  // `data-rf-view` (the real DOM API used by re-frame-pair and the
  // view-walker) ride the same wrapper unchanged and remain covered
  // by their own sentinels above.
  // re-frame.frame/safe-call-hook! — :rf.warning/teardown-hook-exception
  // per-hook DEV DIAGNOSTIC trace (EP-0008 R2, rf2-x3m8c / rf2-inkdqh).
  // When a late-bound cleanup hook throws during destroy-frame!,
  // safe-call-hook! emits this per-hook diagnostic AT ITS CAUSAL POSITION
  // via trace/emit-error!, whose body is gated on
  // `(when interop/debug-enabled? ...)`. The always-on side (the bounded
  // :rf.error/frame-teardown-failed report) intentionally SURVIVES prod;
  // this R2 per-hook diagnostic must DCE. The elision-probe's
  // `touch-teardown!` installs a throwing hook and calls destroy-frame! so
  // the gated emit body — including this operation keyword's string
  // fragment — is in the reachability graph: the control build
  // (DEBUG=true) contains it, the production build (DEBUG=false) must not.
  { source: 're-frame.frame/safe-call-hook! (rf.warning/teardown-hook-exception)',
    sentinel: 'rf.warning/teardown-hook-exception' },
  // re-frame.adapter.context — Context displayName for React DevTools'
  // Context inspector (Spec 006 §React DevTools support, rf2-fa4ly).
  // The "rf2-frame" literal sits inside `(when interop/debug-enabled?
  // (set! (.-displayName frame-context) "rf2-frame"))` and must elide
  // in production bundles. The literal is deliberately distinct from
  // the ns string "re-frame.frame" to avoid clashing with keyword
  // literals under that namespace.
  { source: 're-frame.adapter.context (frame-context displayName)',
    sentinel: 'rf2-frame' },
  // re-frame.core/reg-machine — co-located per-element + reference-site
  // source stamping (Spec 005 §Source-coord stamping, rf2-npvsx + rf2-vqja2,
  // supersedes rf2-8bp3 / rf2-ypu5i). The reg-machine macro emits an
  // `(if interop/debug-enabled? <dev> <prod>)` branch: the DEV arm co-locates
  // `{:fn .. :source-coords .. :source-code ..}` onto each `:guards` /
  // `:actions` entry AND co-locates a reference-site `:source-coords` onto
  // each `:states`-tree map node (state-node / transition map; rf2-vqja2
  // dropped the old flat `:rf.machine/state-coords` side-index); the PROD arm
  // collapses each element entry to `{:fn <fn>}` and runs NO state-source
  // splice. Under :advanced + goog.DEBUG=false the closure compiler constant-
  // folds the gate to false and DCEs the entire dev arm — every co-located
  // source literal and every coord (per-element AND state-node /
  // transition-map) must elide.
  //
  // Sentinels: the distinctive co-located `:source-code` fn-body strings the
  // probe machine mints (`(rf/reg-machine :rf.probe/machine {:guards {:never?
  // (fn probe-never-guard [_] false)} :actions {:noop (fn probe-noop-action
  // [_] {})} ...})`). Under DEBUG=true the macro co-locates the `pr-str` of
  // each fn-form as `:source-code`; under DEBUG=false the dev arm DCEs the
  // source-string literal entirely. The named-fn symbols make the byte
  // sequence unambiguous under a global grep. If either survives, the
  // per-element source-code strings are still reachable from the prod bundle.
  //
  // Note (rf2-vqja2): there is no state-specific sentinel keyword anymore —
  // `:rf.machine/state-coords` is gone, and the state-node / transition-map
  // `:source-coords` co-location rides the SAME dev arm as the guards/actions
  // co-location. The fn-body sentinels below therefore transitively prove the
  // state-source splice DCE'd (they share the one `interop/debug-enabled?`
  // gate); `:source-coords` itself is a keyword shared with the guards/actions
  // arm, so it is not a usable standalone sentinel.
  { source: 're-frame.core/reg-machine (:guards :source-code fn-body literal)',
    sentinel: '(fn probe-never-guard [_] false)' },
  { source: 're-frame.core/reg-machine (:actions :source-code fn-body literal)',
    sentinel: '(fn probe-noop-action [_] {})' },
  // re-frame.core/{dispatch,dispatch-sync,subscribe,inject-cofx} —
  // call-site source-coord stamping (rf2-ts1a, Q3=B dev-only elision).
  // Each macro emits an `(if interop/debug-enabled? <stamp-branch>
  // <no-stamp-branch>)` expansion; under :advanced + goog.DEBUG=false
  // the closure compiler constant-folds the gate to false and the
  // stamp branch DCEs. The `rf.trace/call-site` keyword's string
  // fragment must NOT survive in production bundles.
  { source: 're-frame.core/{dispatch,subscribe,inject-cofx} (rf.trace/call-site stamping)',
    sentinel: 'rf.trace/call-site' },
  // re-frame.core/reg-event — handler form-source capture (Spec 009
  // §`:rf.handler/source`, Xray Spec 021 §11.2 B.7 stretch, rf2-xgfuy).
  // EP-0018 collapsed public event registration to the ONE `reg-event`
  // macro (the per-kind `reg-event-{db,fx,ctx}` forms are removed —
  // calling them is a hard error). The defreg-event-macro emission wraps
  // the bound source
  // string in `(if interop/debug-enabled? ~src-string nil)` and the
  // events/merge-form-source body is gated on `interop/debug-enabled?`.
  // Under :advanced + goog.DEBUG=false, Closure constant-folds both
  // gates to false and DCEs the keyword's reachability from the assoc
  // slot AND the source-string bytes themselves. Two sentinels:
  //
  //   1. The slot keyword `rf.handler/source` — must NOT appear in
  //      the prod bundle (the assoc branch DCEs, dropping the literal
  //      from events.cljc's compiled output).
  //   2. A distinctive source-string fragment minted in the elision-
  //      probe. The probe registers (EP-0018 — the ONE reg-event form)
  //        (rf/reg-event :probe/cs-event (fn [{:keys [db]} _ev] {:db db}))
  //      under DEBUG=true the captured form-source carries the byte
  //      sequence `:probe/cs-event (fn [{:keys [db]} _ev]`; under DEBUG=false
  //      the macro-emitted `if interop/debug-enabled? ~src-string nil`
  //      gate DCEs the source-string literal entirely. The fragment is
  //      distinctive enough that a global grep is unambiguous.
  { source: 're-frame.events/merge-form-source (rf.handler/source slot keyword)',
    sentinel: 'rf.handler/source' },
  { source: 're-frame.core/reg-event macro (form-source pr-str literal)',
    sentinel: ':probe/cs-event (fn [{:keys [db]} _ev]' },
  // re-frame.core/reg-* macros — pure-documentation registration metadata
  // (`:doc`) elision (Spec 001 §Production elision contract, rf2-9wwkcm).
  // `:doc` is the one PURE-documentation registration-metadata key: zero
  // production runtime use, zero production observability use. Two mechanisms
  // pin its production absence:
  //   1. `re-frame.registrar/register!` strips the pure-documentation keys
  //      (`strip-pure-documentation`) from the STORED metadata under
  //      :advanced + goog.DEBUG=false, so `(rf/handler-meta kind id)` carries
  //      no `:doc` in production. (Asserted by the JVM/CLJS unit tests, not a
  //      bundle grep — a runtime strip cannot DCE a user-authored call-site
  //      string.)
  //   2. The reg-* macros (`defreg-macro` / `defreg-event-macro` via
  //      `gate-doc-args`) rewrite a LITERAL doc-bearing metadata-map argument
  //      into `(if interop/debug-enabled? <full-map> <stripped-map>)` — the
  //      outermost gate Closure constant-folds, DCEing the dev arm (with its
  //      `:doc` string literal) under :advanced + goog.DEBUG=false. THIS is
  //      what the bundle grep below asserts.
  //
  // The elision-probe's `touch-doc-metadata!` registers
  //   (rf/reg-event :probe/doc-event
  //     {:doc "rf2-9wwkcm-doc-elision-sentinel: …"} (fn [{:keys [db]} _ev] {:db db}))
  // Under DEBUG=true the literal-map gate's dev arm keeps the `:doc` string
  // (it also rides the form-source `pr-str`), so the sentinel lands in the
  // control bundle; under DEBUG=false BOTH the literal-map gate's dev arm and
  // the form-source gate DCE the string entirely. The sentinel is distinctive
  // enough that a global grep is unambiguous.
  { source: 're-frame.core/reg-* macros (:doc pure-documentation metadata literal)',
    sentinel: 'rf2-9wwkcm-doc-elision-sentinel' }
  // Note (rf2-7yqn39): the :rf.warning/plain-fn-under-non-default-frame-
  // once warning + its emit helper were RETIRED (EP-0002; superseded by
  // the always-on :rf.error/no-frame-context). There is no longer any
  // gated emit site to elide-probe; the browser-test
  // (re-frame.cross-spec-dom-cljs-test/plain-fn-under-non-default-frame)
  // pins the replacement no-frame-context contract.
];

// ----- EP-0023 image-loaded frames (rf2-32siq3.40) ---------------------------
//
// EP-0023 introduces TWO elision contracts that run in the OPPOSITE direction
// from the dev-only sentinels above (which must be ABSENT in production):
//
//   1. PROD_SURVIVING_SENTINELS — strings that MUST be PRESENT in the
//      production bundle. `:rf.provenance/ns` is a PRODUCTION descriptor field,
//      not optional debug metadata (EP-0023 §Namespace-Selected Images):
//      `:include-ns` image assembly reads it, so it MUST survive :advanced +
//      goog.DEBUG=false or namespace-selected images cannot work. Before the
//      `re-frame.elision-probe/touch-image-frame-provenance!` touch this was
//      only HAND-MODELED (source_store_cljs_test binds *pending-coords* and
//      hand-strips :ns); now it runs under a real elision gate. The probe roots
//      the keyword through the same `record-descriptor!` path `reg-*` walks.
//
//   2. PROD_ABSENT_WHEN_UNUSED_SENTINELS — strings inside EP-0023 image-ASSEMBLY
//      fns (make-frame / assemble / check-capabilities!) that MUST be ABSENT in
//      production WHEN THE PROBE DOES NOT ROOT THE IMAGE-LOADING PATH. These fns
//      carry NO debug gate; image_assembly.cljc's own docstring states "an app
//      that never assembles an image never reaches these fns (Closure DCE
//      removes them)." This is a REACHABILITY-DCE contract (not a goog.DEBUG
//      one): the probe touches `reg-*` (the source store) but never calls
//      make-frame/assemble, so the assembly-only literals DCE. A regression that
//      wired image assembly into an always-reachable boot path would surface the
//      string and fail this assertion. (No control-build check: with no debug
//      gate the string is reachability-absent in BOTH builds, so a mustContain
//      control assertion would be vacuous — the teeth are the prod-absence plus
//      the PROD_SURVIVING provenance counterpart proving the bundle is non-empty
//      and the EP-0023 source-store surface IS compiled in.)

const PROD_SURVIVING_SENTINELS = [
  // re-frame.source-store/record-descriptor! + canonical-ns — the
  // `:rf.provenance/ns` descriptor field (EP-0023 §Namespace-Selected Images).
  // A PRODUCTION field every `reg-*` descriptor carries as a canonical string;
  // `:include-ns` selection reads it, so it MUST survive :advanced. The probe's
  // touch-image-frame-provenance! records a descriptor through the production
  // provenance-derivation path (*pending-coords* fallback) and reads the
  // keyword back, rooting the literal in the reachability graph. If this is
  // ABSENT in production, namespace-selected image assembly is broken.
  { source: 're-frame.source-store/record-descriptor! (rf.provenance/ns survives :advanced)',
    sentinel: 'rf.provenance/ns' },
];

const PROD_ABSENT_WHEN_UNUSED_SENTINELS = [
  // re-frame.image-assembly/check-capabilities! — the fail-loud diagnostic
  // string (EP-0023 §Public API / §Image — the frame-boundary capability
  // check). The probe records into the source store but NEVER calls
  // make-frame/assemble/check-capabilities!, so this assembly-only literal must
  // DCE from the production bundle (reachability DCE — image_assembly.cljc
  // docstring: "an app that never assembles an image never reaches these fns").
  // The fragment is the distinctive head of the error message, unambiguous
  // under a global grep.
  { source: 're-frame.image-assembly/check-capabilities! (assembly-only, DCE when unused)',
    sentinel: 'rf/make-frame: the image requires capabilities the frame does not' },
];

// ----- helpers ---------------------------------------------------------------

// Bundle reading is shared with the sibling check-* scripts via
// scripts/lib/read-release-bundle.cjs (rf2-qlk4w). Top-level *.js
// only; a stale dev-build `cljs-runtime/` subdir is skipped.

function checkBundle(label, bundlePath, mustContain) {
  const { status, blob } = classifyReleaseBundle(bundlePath);
  if (status === 'missing') {
    console.error(`[elision] ${label}: bundle path missing — ${bundlePath}`);
    console.error('         Did you run "shadow-cljs release elision-probe"?');
    return { ok: false, checked: 0, passed: 0, bytes: null, missing: true };
  }
  if (status === 'empty') {
    // Non-vacuous floor (rf2-utvst): a present-but-empty bundle satisfies
    // every sentinel-absence check and would false-GREEN the production
    // elision assertion.
    console.error(`[elision] ${label}: bundle present but empty (zero top-level JS) — ${bundlePath}`);
    console.error('         The release emitted no inspectable bundle; an empty bundle');
    console.error('         proves only that nothing got elided-checked. Rebuild with');
    console.error('         "shadow-cljs release elision-probe" or clear the stale dir.');
    return { ok: false, checked: 0, passed: 0, bytes: 0, empty: true };
  }
  report.detail(`[elision] ${label}: ${bundlePath}`);
  report.detail(`          bundle size: ${blob.length} chars`);

  const { ok, passed } = assertSentinels(blob, DEV_ONLY_SENTINELS, mustContain);
  return {
    ok,
    checked: DEV_ONLY_SENTINELS.length,
    passed,
    bytes: blob.length,
    bundlePath,
    missing: false,
  };
}

// Assert a sentinel set against an already-read bundle blob. `mustContain`
// true ⇒ every sentinel must be PRESENT; false ⇒ every sentinel must be
// ABSENT. Returns { ok, passed, checked }. Shared by the dev-only ABSENT
// check and the EP-0023 PROD-SURVIVING (present) / PROD-ABSENT-WHEN-UNUSED
// (absent) checks (rf2-32siq3.40). The loop + tally are the shared
// `assertSentinelSet` (lib/sentinel-scan.cjs, rf2-j552l2); this wrapper
// supplies the elision gate's exact diagnostic line format.
function assertSentinels(blob, sentinels, mustContain) {
  return assertSentinelSet(blob, sentinels, {
    mustContain,
    emit: (line) => report.detail(line),
    formatLine: ({ source, sentinel, present, tag }) => {
      const expected = mustContain ? 'PRESENT' : 'ABSENT';
      const actual   = present     ? 'PRESENT' : 'ABSENT';
      return `          [${tag}] ${source}: sentinel ${JSON.stringify(sentinel)} expected ${expected}, was ${actual}`;
    },
  });
}

// ----- main ------------------------------------------------------------------

function main() {
  report.detail('=== Production elision probe (Spec 009 §Production builds) ===');

  const probeDir   = path.join(ROOT, 'out', 'elision-probe');
  const controlDir = path.join(ROOT, 'out', 'elision-probe-control');

  // Production bundle: dev-only sentinels MUST be absent.
  const prod = checkBundle('production (goog.DEBUG=false)', probeDir, false);

  // EP-0023 image-loaded frames (rf2-32siq3.40) — the two OPPOSITE-direction
  // contracts, asserted against the SAME production bundle blob.
  let ep23 = { ok: true, surviving: { passed: 0, checked: 0 }, absent: { passed: 0, checked: 0 } };
  if (!prod.missing && !prod.empty) {
    const { blob } = classifyReleaseBundle(probeDir);
    report.detail('[elision] EP-0023 image-loaded frames (rf2-32siq3.40):');
    report.detail('          PROD_SURVIVING — :rf.provenance/ns must be PRESENT (survives :advanced):');
    const surviving = assertSentinels(blob, PROD_SURVIVING_SENTINELS, true);
    report.detail('          PROD_ABSENT_WHEN_UNUSED — image-assembly fns must DCE when unused:');
    const absent = assertSentinels(blob, PROD_ABSENT_WHEN_UNUSED_SENTINELS, false);
    ep23 = { ok: surviving.ok && absent.ok, surviving, absent };
  }

  // Control bundle: dev-only sentinels MUST be present (if compiled).
  let control = { ok: true, skipped: true };
  if (fs.existsSync(controlDir)) {
    control = checkBundle('control    (goog.DEBUG=true) ', controlDir, true);
  } else {
    report.detail('[elision] control bundle not built — skipping methodology check.');
    report.detail('          Run "npx shadow-cljs release elision-probe-control"');
    report.detail('          to enable the methodology assertion.');
  }

  if (prod.ok && ep23.ok && control.ok) {
    const controlSummary = control.skipped
      ? 'control skipped'
      : `control ${control.passed}/${control.checked} present (${control.bytes} chars)`;
    report.pass(
      'elision',
      `production ${prod.passed}/${prod.checked} absent (${prod.bytes} chars); ` +
      `EP-0023 ${ep23.surviving.passed}/${ep23.surviving.checked} provenance-survives + ` +
      `${ep23.absent.passed}/${ep23.absent.checked} assembly-DCE; ` +
      `${controlSummary}; bundle=${probeDir}`
    );
    process.exit(0);
  } else {
    report.flushDetails();
    console.error('=== FAIL ===');
    if (!prod.ok) {
      console.error('Production elision broke: at least one dev-only sentinel');
      console.error('survived advanced compilation with goog.DEBUG=false.');
      console.error('Per Spec 009 §Production builds, every emit / schema / ');
      console.error('registrar dev-only branch must be gated on');
      console.error('re-frame.interop/debug-enabled? so DCE removes it.');
    }
    if (!ep23.ok) {
      console.error('EP-0023 elision contract broke (rf2-32siq3.40):');
      console.error('  - PROD_SURVIVING: :rf.provenance/ns MUST survive :advanced —');
      console.error('    it is a production descriptor field :include-ns assembly reads');
      console.error('    (EP-0023 §Namespace-Selected Images). If absent, namespace-');
      console.error('    selected images cannot work.');
      console.error('  - PROD_ABSENT_WHEN_UNUSED: image-assembly fns MUST DCE when the');
      console.error('    probe does not root make-frame/assemble. If present, an');
      console.error('    assembly fn became reachable from an always-run path.');
    }
    if (!control.ok) {
      console.error('Control bundle missing dev-only sentinels — the grep test');
      console.error('would be vacuous.  A sentinel string was likely renamed,');
      console.error('moved out of a gated branch, or removed.  Update');
      console.error('DEV_ONLY_SENTINELS in this script to track the change.');
    }
    process.exit(1);
  }
}

main();
