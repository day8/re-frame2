(ns re-frame.performance-emit-nightly-test
  "Spec 009 §Performance instrumentation — runtime call-site emission
  (rf2-e3j8l, migrated from `tools/causa/testbeds/perf_counter/spec.cjs`).

  This file replaces the Playwright spec at
  `tools/causa/testbeds/perf_counter/spec.cjs` (deleted in the same
  commit). The Playwright assertion was: drive a real click in a perf-
  enabled browser bundle, then check that
  `performance.getEntriesByType('measure')` carries at least one entry
  per `rf:` bucket (`rf:event:*`, `rf:sub:*`, `rf:fx:*`, `rf:render:*`).

  Migrated here as pure CLJS because the User-Timing emission is a CORE
  concern — every call site lives in `implementation/core/src/re_frame/`
  (router.cljc / subs/memo.cljc / fx.cljc / views.cljs) — and the
  emission contract is fully exercisable at the unit-test level once
  the compile-time `re-frame.performance/enabled?` flag is flipped on.

  ## Run gate — NIGHTLY ONLY

  Per the spec catalogue, perf-timing assertions are noisy under per-PR
  CI runners (variable load, GC pauses, slow VMs). This file is excluded
  from the per-PR `:node-test` build by NAMING CONVENTION: shadow-cljs's
  `:node-test` build uses `:ns-regexp \"cljs-test$\"`, and this ns ends
  in `-nightly-test` (NOT `-cljs-test`), so the per-PR runner does NOT
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
  grep in `scripts/check-perf-bundle.cjs`, plus the per-call macro
  round-trip in `re-frame.performance-cljs-test`). The naming
  convention and macro shape for `:render` is already locked by
  `performance-cljs-test/build-name-shape` —
  `(performance/build-name :render :my.app/page)` returns
  `\"rf:render:my.app/page\"`. The bracketing macro itself is identical
  across all four call sites, so per-call-site assertion would be
  redundant once event/sub/fx are covered."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.performance :as performance]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter plain-atom/adapter}))

(defn- clear-measures!
  []
  (when (exists? js/performance.clearMeasures)
    (.clearMeasures js/performance))
  (when (exists? js/performance.clearMarks)
    (.clearMarks js/performance)))

(defn- rf-measures
  "Return the names of every `performance.getEntriesByType('measure')`
  entry whose name starts with `rf:`. Order preserved (insertion order
  per the User-Timing API spec)."
  []
  (->> (.getEntriesByType js/performance "measure")
       (map #(.-name %))
       (filter #(some-> % (.startsWith "rf:")))
       vec))

(defn- bucket-of
  "Extract the `<bucket>` segment from an `rf:<bucket>:<id>` entry name."
  [nm]
  (second (.split nm ":")))

(defn- bucket-counts
  "Group the rf: measure names by `<bucket>` and return name counts as a
  map of `<bucket-string> -> count`."
  []
  (->> (rf-measures)
       (group-by bucket-of)
       (reduce-kv (fn [m k v] (assoc m k (count v))) {})))

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
    (when performance/enabled?
      (clear-measures!)
      (rf/reg-event-db :perf.emit-test/inc
                       (fn [db _] (update db :n (fnil inc 0))))
      (rf/dispatch-sync [:perf.emit-test/inc])
      (let [counts (bucket-counts)]
        (is (pos? (get counts "event" 0))
            (str "at least one rf:event:* entry — got " counts))
        (let [names (rf-measures)]
          (is (some #(= "rf:event:perf.emit-test/inc" %) names)
              (str "the rf:event entry name carries the registered "
                   "event-id verbatim — saw " names)))))))

(deftest subscribe-emits-rf-sub-measure-when-perf-enabled
  (testing "Per Spec 009 §Performance instrumentation: every sub
            recompute emits one `rf:sub:<sub-id>` measure entry (the
            bracket lives in `re-frame.subs.memo/run-compute-validate!`).
            A `subscribe-once` deref forces an immediate compute, which
            is enough for the bracket to fire."
    (when performance/enabled?
      (clear-measures!)
      (rf/reg-event-db :perf.emit-test/seed
                       (fn [_ _] {:n 7}))
      (rf/reg-sub :perf.emit-test/n
                  (fn [db _] (:n db)))
      (rf/dispatch-sync [:perf.emit-test/seed])
      (is (= 7 (rf/subscribe-once :rf/default [:perf.emit-test/n]))
          "subscribe-once delivered the seeded value")
      (let [counts (bucket-counts)
            names  (rf-measures)]
        (is (pos? (get counts "sub" 0))
            (str "at least one rf:sub:* entry — got " counts))
        (is (some #(= "rf:sub:perf.emit-test/n" %) names)
            (str "the rf:sub entry name carries the registered "
                 "sub-id verbatim — saw " names))))))

(deftest fx-walk-emits-rf-fx-measure-when-perf-enabled
  (testing "Per Spec 009 §Performance instrumentation: every fx walk-
            step emits one `rf:fx:<fx-id>` measure entry — covers user
            fx (the bracket sits at the top of `handle-one-fx`, ahead
            of the reserved-fx dispatch table, so reserved + user fx
            both produce entries).

            Drive a `reg-event-fx` handler whose `:fx` vector carries
            one user-registered fx-id; the walk produces an
            `rf:fx:perf.emit-test/log` entry."
    (when performance/enabled?
      (clear-measures!)
      (let [calls (atom [])]
        (rf/reg-fx :perf.emit-test/log
                   (fn [_ctx args] (swap! calls conj args)))
        (rf/reg-event-fx :perf.emit-test/run-fx
                        (fn [_ctx _event]
                          {:fx [[:perf.emit-test/log :hello]]}))
        (rf/dispatch-sync [:perf.emit-test/run-fx])
        (is (= [:hello] @calls)
            "the user fx ran with the expected args")
        (let [counts (bucket-counts)
              names  (rf-measures)]
          (is (pos? (get counts "fx" 0))
              (str "at least one rf:fx:* entry — got " counts))
          (is (some #(= "rf:fx:perf.emit-test/log" %) names)
              (str "the rf:fx entry name carries the registered "
                   "fx-id verbatim — saw " names)))))))

(deftest single-dispatch-populates-all-three-headless-buckets
  (testing "Per Spec 009 §Performance instrumentation: one dispatch
            that touches every headless surface (event handler emits
            :db + :fx; downstream subscribe forces a sub recompute)
            produces at least one entry in EACH of the three headless
            buckets (event / sub / fx). Mirrors the integration
            assertion that the deleted `tools/causa/testbeds/perf_counter/
            spec.cjs` used to make against a live browser bundle."
    (when performance/enabled?
      (clear-measures!)
      (let [log-calls (atom [])]
        (rf/reg-fx :perf.emit-test/counter-log
                   (fn [_ctx args] (swap! log-calls conj args)))
        (rf/reg-event-fx :perf.emit-test/initialise
                        (fn [_ctx _event]
                          {:db {:count 5}
                           :fx [[:perf.emit-test/counter-log :initialised]]}))
        (rf/reg-sub :perf.emit-test/count
                    (fn [db _] (:count db)))
        (rf/dispatch-sync [:perf.emit-test/initialise])
        (is (= 5 (rf/subscribe-once :rf/default [:perf.emit-test/count])))
        (let [counts (bucket-counts)]
          (doseq [bucket ["event" "sub" "fx"]]
            (is (pos? (get counts bucket 0))
                (str "headless bucket rf:" bucket ":* must be populated "
                     "by the integration drain — counts=" counts))))))))

;; ---- build-config sanity ---------------------------------------------------

(deftest enabled-flag-is-flipped-under-the-nightly-build
  (testing "The nightly runner (`:node-test-perf-nightly`) sets
            `:closure-defines {re-frame.performance/enabled? true}` so
            the call-site brackets actually fire. If this assertion
            ever fails on the nightly runner, the build's closure-
            defines map drifted — the integration assertions above
            would silently no-op (their `when performance/enabled?`
            guard short-circuits) so this canary catches it."
    (is (true? performance/enabled?)
        "re-frame.performance/enabled? MUST be true under this build")))
