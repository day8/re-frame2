(ns re-frame.dispatch-later-frame-destroy-test
  "Regression: a `:dispatch-later` timer armed for a frame that is destroyed
  before the timer fires must be CANCELLED by `destroy-frame!` (rf2-uxz52g).

  FINDING (code review, worker-max session 2026-06-20): the `:dispatch-later`
  reserved-fx body armed a host-clock timer via `interop/set-timeout!` but did
  NOT retain the host handle anywhere, so `destroy-frame!` could not cancel a
  still-pending `:dispatch-later` timer. A frame torn down before its timer
  fired leaked the armed timer + its captured closure until the delay elapsed,
  and the deferred dispatch was dead-on-arrival — it landed in a destroyed
  frame, surfacing `:rf.error/frame-destroyed` on the always-on axis (the
  drain recovers, but the timer fired needlessly and held resources).

  FIX (rf2-uxz52g): `:dispatch-later` retains each armed handle in a host-side
  side table keyed by frame (`re-frame.fx/dispatch-later-timers`), mirroring
  the resources stale/GC/poll timer table; `destroy-frame!` cancels + drops the
  frame's slice via the `:fx/on-frame-destroyed!` late-bind hook.

  Two legs, both fail before the fix / pass after:

    (white-box) the armed handle is retained under the frame's key, then the
                key is gone after `destroy-frame!` (the table is cancelled +
                cleared for that frame).
    (behavioural) the deferred event NEVER dispatches into the destroyed frame
                  — no `:rf.error/frame-destroyed` is surfaced and the target
                  handler never runs, even after waiting well past the delay.
                  Before the fix the timer fired into the dead frame and
                  emitted `:rf.error/frame-destroyed`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.frame :as frame]
            [re-frame.flows :as flows]
            [re-frame.schemas :as schemas]
            [re-frame.registrar :as registrar]
            [re-frame.error-emit :as error-emit]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (error-emit/clear-error-listeners!)
  ;; Cancel + drop any pending :dispatch-later host timer so a sibling test
  ;; cannot leak one into this run (the same reset the fixture reset-hooks
  ;; table fires in the CLJS path).
  (fx/reset-dispatch-later-timers!)
  (rf/init! plain-atom/adapter)
  (rf/reg-frame :rf/default {})
  (test-fn)
  (fx/reset-dispatch-later-timers!))

(use-fixtures :each reset-runtime)

(def ^:private test-frame :rf2-uxz52g/worker)

(defn- timers-for-frame
  "White-box read of the pending `:dispatch-later` host timers retained for
  `frame-id` (the private `re-frame.fx/dispatch-later-timers` side table is
  keyed by `[frame-id timer-id]`)."
  [frame-id]
  (->> @@(resolve 're-frame.fx/dispatch-later-timers)
       (filter (fn [[[fid _tid] _handle]] (= fid frame-id)))))

;; ===========================================================================
;; (white-box) the armed handle is retained, then cancelled + dropped on
;; frame destroy.
;; ===========================================================================

(deftest dispatch-later-handle-retained-then-cancelled-on-destroy
  (testing "an armed :dispatch-later handle is retained under the frame's key
            and removed by destroy-frame! (rf2-uxz52g)"
    (rf/reg-frame test-frame {:doc "rf2-uxz52g destroy-cancellation frame"})
    (rf/reg-event :rf2-uxz52g/target (fn [{:keys [db]} _] {:db db}))
    (rf/reg-event :rf2-uxz52g/arm-later
      ;; A long delay so the timer never fires on its own during the test —
      ;; the only ways its slot leaves the table are (a) destroy-frame!
      ;; cancellation (the fix) or (b) the timer firing (which we never wait
      ;; for here).
      (fn [_ _] {:fx [[:dispatch-later {:ms 600000 :event [:rf2-uxz52g/target]}]]}))

    (rf/dispatch-sync [:rf2-uxz52g/arm-later] {:frame test-frame})

    (is (= 1 (count (timers-for-frame test-frame)))
        "the armed :dispatch-later host handle is RETAINED in the side table
         keyed by the arming frame (before the fix nothing was retained)")

    (rf/destroy-frame! test-frame)

    (is (zero? (count (timers-for-frame test-frame)))
        "destroy-frame! cancelled + dropped the frame's pending :dispatch-later
         timer (before the fix the handle was never retained, so it could not
         be cancelled — the timer stayed armed until the delay elapsed)")))

;; ===========================================================================
;; (behavioural) the deferred event never dispatches into the destroyed frame.
;; ===========================================================================

(deftest dispatch-later-does-not-fire-into-destroyed-frame
  (testing "a :dispatch-later scheduled then destroyed before it fires NEVER
            dispatches into the dead frame — no :rf.error/frame-destroyed, and
            the target handler never runs (rf2-uxz52g)"
    (let [target-ran (atom 0)
          errors     (atom [])]
      (rf/register-listener! :errors ::recorder (fn [record] (swap! errors conj record)))
      (rf/reg-frame test-frame {:doc "rf2-uxz52g behavioural frame"})
      (rf/reg-event :rf2-uxz52g/target
        ;; Records into an EXTERNAL atom (not frame-db) so a would-be dead
        ;; dispatch is observable independent of the destroyed-frame recovery.
        (fn [{:keys [db]} _] (swap! target-ran inc) {:db db}))
      (rf/reg-event :rf2-uxz52g/arm-short
        ;; A short delay: long enough to arm + destroy first, short enough to
        ;; fire well within the test's wait window IF it is not cancelled.
        (fn [_ _] {:fx [[:dispatch-later {:ms 40 :event [:rf2-uxz52g/target]}]]}))

      (rf/dispatch-sync [:rf2-uxz52g/arm-short] {:frame test-frame})
      ;; Destroy the frame BEFORE the 40ms timer can fire.
      (rf/destroy-frame! test-frame)

      ;; Wait well past the delay (and past any async drain) so a leaked timer
      ;; would have fired by now.
      (Thread/sleep 300)

      (rf/unregister-listener! :errors ::recorder)

      (is (zero? @target-ran)
          "the deferred target handler never ran — the timer was cancelled on
           destroy, so it never dispatched into the dead frame")
      (is (empty? (filter #(= :rf.error/frame-destroyed (:error %)) @errors))
          "no :rf.error/frame-destroyed surfaced — the cancelled timer never
           enqueued a dead-on-arrival dispatch into the destroyed frame (before
           the fix the timer fired and the dead dispatch emitted this error)"))))

;; ===========================================================================
;; A live frame's :dispatch-later still fires normally — the fix must not
;; perturb the ordinary (non-destroyed) path.
;; ===========================================================================

(deftest dispatch-later-still-fires-for-a-live-frame
  (testing "a :dispatch-later for a frame that stays alive still dispatches its
            event, and the fired timer leaves no residue in the side table"
    (let [target-ran (atom 0)]
      (rf/reg-frame test-frame {:doc "rf2-uxz52g live-path frame"})
      (rf/reg-event :rf2-uxz52g/target
        (fn [{:keys [db]} _] (swap! target-ran inc) {:db db}))
      (rf/reg-event :rf2-uxz52g/arm-short
        (fn [_ _] {:fx [[:dispatch-later {:ms 20 :event [:rf2-uxz52g/target]}]]}))

      (rf/dispatch-sync [:rf2-uxz52g/arm-short] {:frame test-frame})
      ;; Do NOT destroy — let the timer fire and the deferred event drain.
      (Thread/sleep 300)

      (is (= 1 @target-ran)
          "the live frame's :dispatch-later fired and the deferred target ran")
      (is (zero? (count (timers-for-frame test-frame)))
          "a fired timer drops its own side-table slot — no leak on the live
           path either")
      (rf/destroy-frame! test-frame))))
