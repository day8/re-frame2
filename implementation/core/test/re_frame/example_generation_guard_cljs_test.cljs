(ns re-frame.example-generation-guard-cljs-test
  "Framework-tree regression for the `:dispatch-later` GENERATION-GUARD idiom —
   a self-rescheduling tick chain with NO cancel API, retired via a generation
   token. rf2-jo4oqv.

   The idiom: `:dispatch-later` is fire-and-forget (no handle to cancel a tick
   in flight). To retire a chain you BUMP a `:tick-gen` counter; every scheduled
   tick carries the generation it was armed under, and a tick whose generation
   no longer matches app-db just declines to act — no state change, no
   reschedule. So the old chain quietly dies on its next fire and a fresh arming
   leaves exactly ONE live chain.

   This test pins the idiom against `seven-guis.timer.core` — the canonical
   Reagent precedent the substrate `helix.process-monitor.core` example's
   README cites for its own tick loop (examples/substrates/helix/process_monitor
   uses the identical guard). timer.core is requireable + node-drivable and its
   `:tick-gen` guard is the pure form of the pattern (a tick either advances +
   reschedules or no-ops), so pinning it protects the shared idiom. It belongs
   in the framework test tree (examples stay test-free, rf2-8cevm) and runs
   under `:node-test` (`../examples/core` is on its source-paths).

   Neither this idiom was tested behaviourally before
   (`git grep 'tick-gen' -- implementation/` found no test): the frame-scoping
   test only asserts timer.core's ns-load app-schema, and the resources/machine
   timer tests cover a DIFFERENT mechanism (cancel-then-arm via a real timer
   registry). A regression — stale ticks that stop no-op'ing, or a bump that
   fails to retire — would spawn overlapping chains with no guard.

   The scheduled follow-ups are observed by capturing the `:dispatch-later` fx
   via a function-value `:fx-overrides` entry on the frame (spec/002
   §`:fx-overrides`, rf2-nrpj1): the override runs in place of the reserved
   `:dispatch-later` body, so NO real host timer is ever armed (deterministic,
   no wall-clock flakiness) and we read back exactly what each handler tried to
   schedule."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            ;; timer.core registers an app-schema on :rf/default at ns-load via
            ;; `with-frame`; it pulls re-frame.schemas transitively. Require here
            ;; so the ns is self-sufficient.
            [re-frame.schemas]
            [seven-guis.timer.core :as timer])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- tick-gen [f]
  (get-in (rf/app-db-value f) [:timer :tick-gen]))

(defn- elapsed-ms [f]
  (get-in (rf/app-db-value f) [:timer :elapsed-ms]))

(defn- scheduled-events
  "The `:event`s of every captured `:dispatch-later` in `captured` (an atom of
   the `:dispatch-later` arg maps)."
  [captured]
  (mapv :event @captured))

;; ---------------------------------------------------------------------------
;; Generation guard: stale ticks die, a bump leaves exactly one live chain
;; ---------------------------------------------------------------------------

(deftest generation-guard-retires-stale-chains-keeps-one-live
  (testing "rf2-jo4oqv — the :dispatch-later generation guard: a stale-gen tick
            no-ops (no state change, no reschedule) while a live-gen tick
            advances + reschedules under the SAME gen; a bump (reset) retires
            the old chain and arms exactly ONE fresh chain"
    (let [captured      (atom [])
          capture-later (fn [_ctx args] (swap! captured conj args))]
      (with-new-frame [f (frame/make-anon-frame-record!
                           {:fx-overrides {:dispatch-later capture-later}})]

        ;; ---- boot: :initialise arms exactly one chain under gen 0 ----
        (rf/dispatch-sync [:timer/initialise] {:frame f})
        (is (= 0 (tick-gen f)) ":initialise seeded generation 0")
        (is (= [[:timer/tick 0]] (scheduled-events captured))
            ":initialise armed exactly one tick chain, carrying gen 0")

        ;; ---- (a) a STALE-gen tick no-ops ----
        (reset! captured [])
        (let [db-before (rf/app-db-value f)]
          (rf/dispatch-sync [:timer/tick 999] {:frame f})
          (is (= db-before (rf/app-db-value f))
              "a tick carrying a retired generation leaves app-db UNCHANGED
               (no elapsed bump)")
          (is (empty? @captured)
              "a stale-gen tick schedules NO follow-up — declining to act is
               how the old chain dies"))

        ;; ---- a LIVE-gen tick advances and reschedules under the same gen ----
        (reset! captured [])
        (let [before (elapsed-ms f)]
          (rf/dispatch-sync [:timer/tick 0] {:frame f})
          (is (= (+ before timer/tick-ms) (elapsed-ms f))
              "a live-gen tick advanced elapsed by exactly one tick")
          (is (= [[:timer/tick 0]] (scheduled-events captured))
              "a live-gen tick rescheduled exactly one follow-up, under the
               SAME live generation"))

        ;; ---- (b) reset BUMPS the generation → exactly one live chain ----
        (reset! captured [])
        (rf/dispatch-sync [:timer/reset] {:frame f})
        (is (= 1 (tick-gen f)) "reset bumped the generation to 1")
        (is (zero? (elapsed-ms f)) "reset zeroed elapsed")
        (is (= [[:timer/tick 1]] (scheduled-events captured))
            "reset armed exactly ONE fresh chain, under the new generation 1")

        ;; the OLD chain (gen 0) is now stale — its next fire no-ops
        (reset! captured [])
        (let [db-before (rf/app-db-value f)]
          (rf/dispatch-sync [:timer/tick 0] {:frame f})
          (is (= db-before (rf/app-db-value f))
              "the pre-reset gen-0 tick is now stale — no state change")
          (is (empty? @captured)
              "the stale gen-0 chain schedules nothing — retired by the bump"))

        ;; ...while the NEW chain (gen 1) proceeds — proving EXACTLY ONE live
        ;; chain survives the bump
        (reset! captured [])
        (rf/dispatch-sync [:timer/tick 1] {:frame f})
        (is (= [[:timer/tick 1]] (scheduled-events captured))
            "the gen-1 chain is the single live chain and keeps ticking")))))
