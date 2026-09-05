(ns re-frame.example-generation-guard-cljs-test
  "Framework-tree regression for two properties of `seven-guis.timer.core`:
   the `:dispatch-later` GENERATION-GUARD idiom (rf2-jo4oqv) and the
   MEASURED-NOT-COUNTED elapsed-time rule (rf2-4s8g).

   The generation-guard idiom: `:dispatch-later` is fire-and-forget (no handle
   to cancel a tick in flight). To retire a chain you BUMP a `:tick-gen`
   counter; every scheduled tick carries the generation it was armed under, and
   a tick whose generation no longer matches app-db just declines to act — no
   state change, no reschedule. So the old chain quietly dies on its next fire
   and a fresh arming leaves exactly ONE live chain.

   The elapsed-time rule: `:dispatch-later` promises to fire NOT BEFORE the
   delay it was given, never exactly on it — the CLJS effect delegates to
   `js/setTimeout`, whose late-firing under load, throttling, or a backgrounded
   tab is explicit in the HTML Living Standard. A handler that adds a fixed
   `tick-ms` per callback is therefore counting callbacks, not measuring time,
   and runs permanently slow the moment the host is busy. timer.core instead
   declares `:rf.cofx/requires [:rf/time-ms]`, anchors on `:sampled-at-ms`, and
   advances elapsed by the interval that ACTUALLY passed between samples. The
   100ms is a sampling cadence only.

   This test pins both against `seven-guis.timer.core` — the canonical Reagent
   precedent (formerly also cited by the Helix process-monitor example, removed
   at S7/W13). timer.core is requireable + node-drivable and its `:tick-gen`
   guard is the pure form of the pattern (a tick either advances + reschedules
   or no-ops), so pinning it protects the shared idiom. It belongs in the
   framework test tree (examples stay test-free, rf2-8cevm) and runs under
   `:node-test` (`../examples/core` is on its source-paths).

   Neither property was tested behaviourally before this file
   (`git grep 'tick-gen' -- implementation/` found no test): the frame-scoping
   test only asserts timer.core's ns-load app-schema, and the resources/machine
   timer tests cover a DIFFERENT mechanism (cancel-then-arm via a real timer
   registry). A regression — stale ticks that stop no-op'ing, a bump that fails
   to retire, or elapsed drifting back to one-callback-equals-one-tick — would
   spawn overlapping chains with no guard, or a clock that silently loses time.

   Two injections make every assertion below exact, with no wall clock and no
   host timer involved:

   - The scheduled follow-ups are observed by capturing the `:dispatch-later`
     fx via a function-value `:fx-overrides` entry on the frame (spec/002
     §`:fx-overrides`, rf2-nrpj1): the override runs in place of the reserved
     `:dispatch-later` body, so NO real host timer is ever armed and we read
     back exactly what each handler tried to schedule.
   - The clock is supplied as data — `{:rf.cofx {:rf/time-ms t}}` on the
     dispatch pins the recordable coeffect the handlers declare (EP-0017
     declared-only delivery; docs/core/testing/pipeline-runs.md). Nothing here
     depends on how long the test itself takes to run."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            ;; timer.core registers an app-schema on :rf/default at ns-load via
            ;; `with-frame`; it pulls re-frame.schemas transitively. Require here
            ;; so the ns is self-sufficient.
            [re-frame.schemas]
            [seven-guis.timer.core :as timer])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- tick-gen [f]
  (get-in (rf/app-db-value f) [:timer :tick-gen]))

(defn- elapsed-ms [f]
  (get-in (rf/app-db-value f) [:timer :elapsed-ms]))

(defn- sampled-at-ms [f]
  (get-in (rf/app-db-value f) [:timer :sampled-at-ms]))

(defn- duration-ms [f]
  (get-in (rf/app-db-value f) [:timer :duration-ms]))

(defn- scheduled-events
  "The `:event`s of every captured `:dispatch-later` in `captured` (an atom of
   the `:dispatch-later` arg maps)."
  [captured]
  (mapv :event @captured))

;; An arbitrary but FIXED epoch reading to measure from. Every timestamp below
;; is `t0` plus an explicit offset, so each assertion states the interval it
;; expects rather than a bare magic number.
(def ^:private t0 1781078400000)

(defn- dispatch-at!
  "Dispatch `event` into frame `f` with the recorded clock reading pinned to
   `t`. The timer handlers declare `:rf.cofx/requires [:rf/time-ms]`, so
   supplying it here fully determines what they measure — this is the whole
   reason the elapsed assertions can be exact equalities."
  [f t event]
  (rf/dispatch-sync event {:frame f :rf.cofx {:rf/time-ms t}}))

(defn- with-captured-schedules
  "Run `body-fn` against a fresh frame whose `:dispatch-later` is captured
   rather than armed. `body-fn` receives [frame captured-atom]."
  [body-fn]
  (let [captured      (atom [])
        capture-later (fn [_ctx args] (swap! captured conj args))]
    (with-new-frame [f (rf.frame/make-anon-frame-record!
                         {:fx-overrides {:dispatch-later capture-later}})]
      (body-fn f captured))))

;; ---------------------------------------------------------------------------
;; Generation guard: stale ticks die, a bump leaves exactly one live chain
;; ---------------------------------------------------------------------------

(deftest generation-guard-retires-stale-chains-keeps-one-live
  (testing "rf2-jo4oqv — the :dispatch-later generation guard: a stale-gen tick
            no-ops (no state change, no reschedule) while a live-gen tick
            advances + reschedules under the SAME gen; a bump (reset) retires
            the old chain and arms exactly ONE fresh chain"
    (with-captured-schedules
      (fn [f captured]

        ;; ---- boot: :initialise arms exactly one chain under gen 0 ----
        (dispatch-at! f t0 [:timer/initialise])
        (is (= 0 (tick-gen f)) ":initialise seeded generation 0")
        (is (= [[:timer/tick 0]] (scheduled-events captured))
            ":initialise armed exactly one tick chain, carrying gen 0")

        ;; ---- (a) a STALE-gen tick no-ops ----
        ;; Note the injected reading MOVES here (t0 + 5000) while the expected
        ;; app-db does not: a retired tick must not even re-anchor the clock,
        ;; which a whole-db comparison now also pins.
        (reset! captured [])
        (let [db-before (rf/app-db-value f)]
          (dispatch-at! f (+ t0 5000) [:timer/tick 999])
          (is (= db-before (rf/app-db-value f))
              "a tick carrying a retired generation leaves app-db UNCHANGED
               (no elapsed bump, and no re-anchor of :sampled-at-ms either)")
          (is (empty? @captured)
              "a stale-gen tick schedules NO follow-up — declining to act is
               how the old chain dies"))

        ;; ---- a LIVE-gen tick advances and reschedules under the same gen ----
        (reset! captured [])
        (let [before (elapsed-ms f)]
          (dispatch-at! f (+ t0 100) [:timer/tick 0])
          (is (= (+ before 100) (elapsed-ms f))
              "a live-gen tick advanced elapsed by the 100ms that passed")
          (is (= [[:timer/tick 0]] (scheduled-events captured))
              "a live-gen tick rescheduled exactly one follow-up, under the
               SAME live generation"))

        ;; ---- (b) reset BUMPS the generation → exactly one live chain ----
        (reset! captured [])
        (dispatch-at! f (+ t0 200) [:timer/reset])
        (is (= 1 (tick-gen f)) "reset bumped the generation to 1")
        (is (zero? (elapsed-ms f)) "reset zeroed elapsed")
        (is (= [[:timer/tick 1]] (scheduled-events captured))
            "reset armed exactly ONE fresh chain, under the new generation 1")

        ;; the OLD chain (gen 0) is now stale — its next fire no-ops
        (reset! captured [])
        (let [db-before (rf/app-db-value f)]
          (dispatch-at! f (+ t0 300) [:timer/tick 0])
          (is (= db-before (rf/app-db-value f))
              "the pre-reset gen-0 tick is now stale — no state change")
          (is (empty? @captured)
              "the stale gen-0 chain schedules nothing — retired by the bump"))

        ;; ...while the NEW chain (gen 1) proceeds — proving EXACTLY ONE live
        ;; chain survives the bump
        (reset! captured [])
        (dispatch-at! f (+ t0 400) [:timer/tick 1])
        (is (= [[:timer/tick 1]] (scheduled-events captured))
            "the gen-1 chain is the single live chain and keeps ticking")))))

;; ---------------------------------------------------------------------------
;; Elapsed time is MEASURED, not counted (rf2-4s8g)
;; ---------------------------------------------------------------------------

(deftest elapsed-follows-recorded-time-not-callback-count
  (testing "rf2-4s8g — elapsed advances by the interval that ACTUALLY passed
            between samples, never by a fixed tick-ms per callback: a late
            callback contributes its real interval, and a sample past the
            deadline clamps exactly once and stops the chain"
    (with-captured-schedules
      (fn [f captured]

        (dispatch-at! f t0 [:timer/initialise])
        (is (zero? (elapsed-ms f)) "boots at zero elapsed")
        (is (= t0 (sampled-at-ms f))
            ":initialise anchored on the RECORDED reading, not an ambient
             clock read inside the handler")
        (is (= 10000 (duration-ms f)) "the example's default 10s duration")

        ;; ---- THE CONTROL: a tick that arrives LATE ----
        ;; The chain asked for 100ms; the host delivered at +450ms — an
        ;; ordinary outcome under load, throttling, or a background tab.
        ;; A callback-counter credits 100ms here and loses the other 350ms
        ;; permanently. This assertion is what the pre-rf2-4s8g implementation
        ;; fails: `(min (+ elapsed-ms tick-ms) duration-ms)` yields 100.
        (reset! captured [])
        (dispatch-at! f (+ t0 450) [:timer/tick 0])
        (is (= 450 (elapsed-ms f))
            "a callback 450ms after the last sample advanced elapsed by 450ms
             — NOT by the 100ms of sampling cadence it was scheduled with")
        (is (= (+ t0 450) (sampled-at-ms f))
            "the sample re-anchored, so the next interval is measured from
             here — no interval double-counted, none dropped")
        (is (= [[:timer/tick 0]] (scheduled-events captured))
            "still inside the duration, so exactly one follow-up was armed")

        ;; ---- an ON-TIME tick contributes exactly its own interval ----
        (reset! captured [])
        (dispatch-at! f (+ t0 550) [:timer/tick 0])
        (is (= 550 (elapsed-ms f))
            "a punctual 100ms sample adds 100ms — measuring is not a penalty
             for the well-behaved case, it is the same rule")

        ;; ---- a sample far past the deadline clamps ONCE and stops ----
        ;; The tab was suspended for a minute and a half. The timer is over;
        ;; elapsed must land exactly on the duration, never past it.
        (reset! captured [])
        (dispatch-at! f (+ t0 90000) [:timer/tick 0])
        (is (= 10000 (elapsed-ms f))
            "a hugely delayed sample clamped EXACTLY to duration-ms, not past
             it — the bar fills, it does not overrun")
        (is (empty? @captured)
            "a completed timer schedules no further tick — the chain ends")

        ;; and the retired chain stays retired even if a straggler lands
        (reset! captured [])
        (let [db-before (rf/app-db-value f)]
          (dispatch-at! f (+ t0 95000) [:timer/tick 0])
          (is (= 10000 (elapsed-ms f))
              "a straggler after completion cannot push elapsed past duration")
          (is (empty? @captured) "and arms nothing")
          ;; :sampled-at-ms does move on this accepted-but-clamped sample; the
          ;; user-visible slice is what must hold still.
          (is (= (dissoc (:timer db-before) :sampled-at-ms)
                 (dissoc (:timer (rf/app-db-value f)) :sampled-at-ms))
              "elapsed, duration, active flag and generation all unchanged"))))))

(deftest reset-and-rearm-establish-a-fresh-time-baseline
  (testing "rf2-4s8g — Reset and a post-completion re-arm both re-anchor the
            clock, so the first sample afterwards counts from that USER EVENT
            rather than from the last sample before it: time the timer spent
            stopped is never charged to the new run"
    (with-captured-schedules
      (fn [f captured]

        ;; ---- run it to completion ----
        (dispatch-at! f t0 [:timer/initialise])
        (reset! captured [])
        (dispatch-at! f (+ t0 10000) [:timer/tick 0])
        (is (= 10000 (elapsed-ms f)) "reached the 10s duration")
        (is (empty? @captured) "and stopped scheduling")

        ;; ---- the user leaves it sitting finished for a minute, THEN drags
        ;;      the slider up. The revive must not bill that idle minute. ----
        (reset! captured [])
        (dispatch-at! f (+ t0 70000) [:timer/set-duration 20000])
        (is (= 1 (tick-gen f)) "the re-arm bumped the generation")
        (is (= (+ t0 70000) (sampled-at-ms f))
            "the baseline moved to the drag — the 60s spent stopped is not
             time the run should inherit")
        (is (= [[:timer/tick 1]] (scheduled-events captured))
            "the re-arm armed exactly ONE fresh chain")

        (reset! captured [])
        (dispatch-at! f (+ t0 70100) [:timer/tick 1])
        (is (= 10100 (elapsed-ms f))
            "the first resumed sample added the 100ms since the DRAG. Without
             the re-anchor it would have added 60100 and completed instantly")

        ;; ---- Reset zeroes AND re-anchors ----
        (reset! captured [])
        (dispatch-at! f (+ t0 80000) [:timer/reset])
        (is (zero? (elapsed-ms f)) "Reset produced an observable zero")
        (is (= 2 (tick-gen f)) "Reset bumped the generation again")
        (is (= (+ t0 80000) (sampled-at-ms f)) "Reset re-anchored the clock")
        (is (= [[:timer/tick 2]] (scheduled-events captured))
            "Reset armed exactly one chain")

        (reset! captured [])
        (dispatch-at! f (+ t0 80100) [:timer/tick 2])
        (is (= 100 (elapsed-ms f))
            "the sample after Reset measured from the Reset — 100ms. Zeroing
             elapsed without re-anchoring would have jumped it to ~9900")))))
