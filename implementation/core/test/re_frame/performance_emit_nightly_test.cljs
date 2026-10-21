(ns re-frame.performance-emit-nightly-test
  "Spec 009 §Performance instrumentation — runtime call-site emission.

  Drives real dispatches through a perf-enabled build, then checks that
  `performance.getEntriesByType('measure')` carries at least one entry
  per headless `rf:` bucket (`rf:event:*`, `rf:sub:*`, `rf:fx:*`).

  It is pure CLJS because the User-Timing emission is a CORE
  concern — every call site lives in `implementation/core/src/re_frame/`
  (router.cljc / subs/memo.cljc / fx.cljc / views.cljs) — and the
  emission contract is fully exercisable at the unit-test level once
  the compile-time `re-frame.performance/enabled?` flag is flipped on.

  ## Run gate — NIGHTLY ONLY

  Per the spec catalogue, perf-timing assertions are noisy under per-PR
  CI runners (variable load, GC pauses, slow VMs). This file is excluded
  from the per-PR `:node-test` build by NAMING CONVENTION: shadow-cljs's
  `:node-test` build's `:ns-regexp` excludes namespaces ending
  `-emit-nightly-test`, as this one does, so the per-PR runner does NOT
  pick it up.

  The companion `:node-test-perf-nightly` build (in
  `implementation/shadow-cljs.edn`) is the runner that includes this
  file. It mirrors `:node-test` but adds
  `:closure-defines {re-frame.performance/enabled? true}` so the
  call-site brackets are LIVE at runtime, and uses
  `:ns-regexp \"-emit-nightly-test$\"` so it picks up this file (and
  any future perf-emission nightly companions).

  Invocation (nightly / manual):

      cd implementation
      npx shadow-cljs compile node-test-perf-nightly && \\
        node out/node-test-perf-nightly.js

  ## What this exercises

  Three of the four buckets directly (event / sub / fx) plus the
  measure-naming convention. The `:render` bucket fires inside the
  Reagent `frame-aware-view` wrapper — driving it requires a live React
  render tree, which is browser-only (covered by the bundle-presence
  grep in `scripts/check-perf-bundle.cjs`). The naming
  convention and macro shape for `:render` is locked by
  `performance-cljs-test/build-name-shape` —
  `(rf.performance/build-name :render :my.app/page)` returns
  `\"rf:render:my.app/page\"`. The bracketing macro itself is identical
  across all four call sites, so per-call-site assertion would be
  redundant once event/sub/fx are covered."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.performance :as rf.performance]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

;; Buffer retention for this runner. The bracket CLEARS
;; each measure right after emit (`retain-entries?` default off), so a
;; synchronous `getEntriesByType('measure')` snapshot taken after a drain
;; finds an empty buffer — that's the clear-after-emit contract working. This nightly
;; runner sets `:closure-defines {re-frame.performance/retain-entries?
;; true}` (alongside `enabled? true`) so the entries PERSIST in the
;; retained buffer and these emission assertions can read them
;; synchronously. Retention is the robust way to assert the entry
;; NAMES / BUCKETS here, because an observer's callback runs later, as a
;; queued task. It also means this runner cannot observe clear-after-emit;
;; `re-frame.performance-cljs-test` grades that in the per-PR `:node-test`
;; lane.
(defn- clear-measures!
  []
  (when (exists? js/performance.clearMeasures)
    (.clearMeasures js/performance))
  (when (exists? js/performance.clearMarks)
    (.clearMarks js/performance)))

(defn- rf-measures
  "Return the names of every `performance.getEntriesByType('measure')`
  entry whose name starts with `rf:`. Order preserved (insertion order
  per the User-Timing API spec). Reads the retained buffer, which this
  runner keeps populated via `retain-entries? true` (see above)."
  []
  (->> (.getEntriesByType js/performance "measure")
       (map #(.-name %))
       (filter #(some-> % (.startsWith "rf:")))
       vec))

;; Gate every assertion below behind `enabled?` so a smoke build that
;; runs this file without flipping the goog-define (e.g. an ad-hoc
;; debugging invocation) does not produce false failures. The
;; canonical runner is the `:node-test-perf-nightly` build, which
;; flips the flag; if you see ALL of these tests no-op'ing, you forgot
;; to flip the flag.

(deftest dispatch-emits-rf-event-measure-when-perf-enabled
  (testing "Per Spec 009 §Performance instrumentation: every successful
            event dispatch through the chain emits exactly one
            `rf:event:<event-id>` measure entry (the bracket lives in
            `re-frame.router/run-chain`)."
    (when rf.performance/enabled?
      (clear-measures!)
      (rf/reg-event :perf.emit-test/inc
                       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (rf/dispatch-sync [:perf.emit-test/inc])
      (let [names (rf-measures)]
        (is (some #(= "rf:event:perf.emit-test/inc" %) names)
            (str "the rf:event entry name carries the registered "
                 "event-id verbatim — saw " names))))))

(deftest subscribe-emits-rf-sub-measure-when-perf-enabled
  (testing "Per Spec 009 §Performance instrumentation: every sub
            recompute emits one `rf:sub:<sub-id>` measure entry (the
            bracket lives in `re-frame.subs.memo/run-compute-validate!`).
            A `subscribe-once` deref forces an immediate compute, which
            is enough for the bracket to fire."
    (when rf.performance/enabled?
      (clear-measures!)
      (rf/reg-event :perf.emit-test/seed
                       (fn [_ _] {:db {:n 7}}))
      (rf/reg-sub :perf.emit-test/n
                  (fn [db _] (:n db)))
      (rf/dispatch-sync [:perf.emit-test/seed])
      (rf/subscribe-once [:perf.emit-test/n] {:frame :rf/default})
      (let [names (rf-measures)]
        (is (some #(= "rf:sub:perf.emit-test/n" %) names)
            (str "the rf:sub entry name carries the registered "
                 "sub-id verbatim — saw " names))))))

(deftest fx-walk-emits-rf-fx-measure-when-perf-enabled
  (testing "Per Spec 009 §Performance instrumentation: every fx walk-
            step emits one `rf:fx:<fx-id>` measure entry — covers user
            fx (the bracket sits at the top of `handle-one-fx`, ahead
            of the reserved-fx dispatch table, so reserved + user fx
            both produce entries).

            Drive a `reg-event` handler whose `:fx` vector carries
            one user-registered fx-id; the walk produces an
            `rf:fx:perf.emit-test/log` entry."
    (when rf.performance/enabled?
      (clear-measures!)
      (rf/reg-fx :perf.emit-test/log (fn [_ctx _args] nil))
      (rf/reg-event :perf.emit-test/run-fx
                      (fn [_ctx _event]
                        {:fx [[:perf.emit-test/log :hello]]}))
      (rf/dispatch-sync [:perf.emit-test/run-fx])
      (let [names (rf-measures)]
        (is (some #(= "rf:fx:perf.emit-test/log" %) names)
            (str "the rf:fx entry name carries the registered "
                 "fx-id verbatim — saw " names))))))

;; ---- build-config sanity ---------------------------------------------------

(deftest enabled-flag-is-flipped-under-the-nightly-build
  (testing "The nightly runner (`:node-test-perf-nightly`) sets
            `:closure-defines {re-frame.performance/enabled? true
                               re-frame.performance/retain-entries? true}`
            so the call-site brackets actually fire AND the emitted
            measures persist in the retained buffer for the synchronous
            getEntriesByType reads above. If either assertion fails on the
            nightly runner, the build's closure-defines map drifted — the
            emission assertions would silently no-op (`enabled?` guard) or
            read an empty buffer (`retain-entries?` off → cleared after
            emit), so this canary catches both."
    (is (true? rf.performance/enabled?)
        "re-frame.performance/enabled? MUST be true under this build")
    (is (true? rf.performance/retain-entries?)
        "re-frame.performance/retain-entries? MUST be true under this build")))
