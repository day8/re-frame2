(ns re-frame.dispatch-later-frame-destroy-test
  "Regression: a `:dispatch-later` timer armed for a frame that is destroyed
  before the timer fires must be CANCELLED by `destroy-frame!` (rf2-uxz52g).

  FINDING (code review, worker-max session 2026-06-20): the `:dispatch-later`
  reserved-fx body armed a host-clock timer via `rf.interop/set-timeout!` but did
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
            [re-frame.fx :as rf.fx]
            [re-frame.frame :as rf.frame]
            [re-frame.flows :as rf.flows]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.schemas :as rf.schemas]
            [re-frame.registrar :as rf.registrar]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.error-emit/clear-error-listeners!)
  ;; Cancel + drop any pending :dispatch-later host timer so a sibling test
  ;; cannot leak one into this run (the same reset the fixture reset-hooks
  ;; table fires in the CLJS path).
  (rf.fx/reset-dispatch-later-timers!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf/make-frame {:id :rf/default})
  (test-fn)
  (rf.fx/reset-dispatch-later-timers!))

(use-fixtures :each reset-runtime)

(def ^:private test-frame :rf2-uxz52g/worker)

(defn- timers-for-frame
  "White-box read of the pending `:dispatch-later` host timers retained for
  `frame-id` (the private `re-frame.fx/dispatch-later-timers` side table is
  keyed by `[frame-id timer-id]`)."
  [frame-id]
  (->> @@(resolve 're-frame.fx/dispatch-later-timers)
       (filter (fn [[[fid _tid] _handle]] (= fid frame-id)))))

(defn- with-dispatch-stub
  "Run `body-fn` with the `:router/dispatch!` late-bind hook replaced by `stub`,
  restoring the real router hook afterward. Lets the deterministic two-phase
  publication tests COUNT the deferred dispatch synchronously without routing
  through the async router drain (whose scheduling would make the count timing-
  dependent). `stub` has the runtime dispatch signature `(fn [event opts] ...)`."
  [stub body-fn]
  (let [real (rf.late-bind/get-fn :router/dispatch!)]
    (try
      (rf.late-bind/set-fn! :router/dispatch! stub)
      (body-fn)
      (finally
        (rf.late-bind/set-fn! :router/dispatch! real)))))

;; ===========================================================================
;; (white-box) the armed handle is retained, then cancelled + dropped on
;; frame destroy.
;; ===========================================================================

(deftest dispatch-later-handle-retained-then-cancelled-on-destroy
  (testing "an armed :dispatch-later handle is retained under the frame's key
            and removed by destroy-frame! (rf2-uxz52g)"
    (rf/make-frame {:id test-frame :doc "rf2-uxz52g destroy-cancellation frame"})
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
      (rf/make-frame {:id test-frame :doc "rf2-uxz52g behavioural frame"})
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
      (rf/make-frame {:id test-frame :doc "rf2-uxz52g live-path frame"})
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

;; ===========================================================================
;; rf2-3fc89f.3 — TWO-PHASE atomic publication vs immediate-fire + destroy.
;;
;; The publish-AFTER-set-timeout! scheme retained a spent handle forever when a
;; zero/immediate host callback (the JVM ScheduledExecutorService may run the
;; thunk on its worker thread BEFORE `set-timeout!` returns) fired before the
;; handle was published, and could publish a handle PAST a frame destroy that
;; raced the schedule. These tests drive both interleavings DETERMINISTICALLY
;; through the `rf.interop/set-timeout!` seam (not a flaky probability stress).
;; ===========================================================================

(def ^:private race-frame :rf2-3fc89f3/race)

(deftest dispatch-later-immediate-fire-leaves-no-orphan-handle
  (testing "a zero/immediate host callback that fires BEFORE the handle is
            published removes the arming reservation and leaves NO orphan/spent
            handle in the side table; the deferred event dispatches exactly
            once and the spent handle is CANCELLED, not orphaned (rf2-3fc89f.3
            acceptance 1)"
    (let [dispatched   (atom [])
          cleared      (atom [])
          spent-handle ::spent-handle
          event        [:rf2-3fc89f3/target]
          opts         {:source :fx-dispatch-later :source-detail {:ms 0}}]
      (with-dispatch-stub
        (fn [ev op] (swap! dispatched conj [ev op]))
        (fn []
          ;; Force the pathological ordering: the host runs the timer thunk
          ;; SYNCHRONOUSLY (as the real executor may for a 0ms delay) BEFORE
          ;; returning its handle — the immediate-fire-before-publication race.
          (with-redefs [rf.interop/set-timeout!   (fn [f _ms] (f) spent-handle)
                        rf.interop/clear-timeout! (fn [h] (swap! cleared conj h) nil)]
            (#'rf.fx/arm-dispatch-later! race-frame 0 event opts))))
      (is (= [[event opts]] @dispatched)
          "the deferred event dispatched exactly once, with its event + opts
           (:source / :source-detail) propagated verbatim")
      (is (empty? (timers-for-frame race-frame))
          "no orphan/spent handle retained — the publish phase saw the vanished
           reservation and did NOT reinsert the spent handle (before the fix the
           table retained {[race-frame 1] ::spent-handle})")
      (is (= [spent-handle] @cleared)
          "the publish phase CANCELLED the spent handle (safe even though it had
           already completed) rather than leaking it forever"))))

(deftest dispatch-later-destroy-between-reserve-and-publish-cancels-handle
  (testing "a frame destroy that lands BETWEEN the reservation and the handle
            publication removes the visible reservation without cancelling a
            sentinel; the publish phase then CANCELS the returned handle, no
            event dispatches, and nothing is published past cleanup
            (rf2-3fc89f.3 acceptance 2 + 4)"
    (let [dispatched (atom [])
          cleared    (atom [])
          the-handle ::live-handle
          event      [:rf2-3fc89f3/target]
          opts       {:source :fx-dispatch-later :source-detail {:ms 600000}}]
      (with-dispatch-stub
        (fn [ev op] (swap! dispatched conj [ev op]))
        (fn []
          (with-redefs [rf.interop/set-timeout!
                        (fn [_f _ms]
                          ;; The arming reservation is VISIBLE here (phase 1 ran
                          ;; before set-timeout!). Destroy the frame mid-schedule:
                          ;; release-frame! must DROP the reservation but NEVER
                          ;; pass the arming SENTINEL to clear-timeout!. Then we
                          ;; return the real handle (publication happens next).
                          (is (= 1 (count (timers-for-frame race-frame)))
                              "the arming reservation is visible mid-set-timeout!")
                          (rf.fx/release-frame! race-frame)
                          (is (empty? @cleared)
                              "release-frame! did NOT cancel the arming sentinel
                               (nothing cleared yet)")
                          the-handle)
                        rf.interop/clear-timeout! (fn [h] (swap! cleared conj h) nil)]
            (#'rf.fx/arm-dispatch-later! race-frame 600000 event opts))))
      (is (= [the-handle] @cleared)
          "the publish phase found the reservation gone (destroy removed it) and
           CANCELLED the returned handle; the arming SENTINEL was NEVER passed to
           clear-timeout! (release-frame! skips it)")
      (is (empty? @dispatched)
          "no deferred event dispatched into the destroyed frame")
      (is (empty? (timers-for-frame race-frame))
          "no handle published after cleanup — the reservation was gone, so the
           publish phase declined to reinsert"))))

(deftest release-and-reset-never-cancel-the-arming-sentinel
  (testing "release-frame! and reset-dispatch-later-timers! DROP an in-progress
            arming reservation but NEVER pass the sentinel to clear-timeout!,
            while still cancelling real handles (rf2-3fc89f.3 acceptance 4)"
    (let [sentinel @#'rf.fx/arming-sentinel
          timers    @(resolve 're-frame.fx/dispatch-later-timers)
          cleared   (atom [])]
      ;; release-frame! over a frame whose only slot is an arming reservation.
      (reset! timers {[race-frame 1] sentinel})
      (with-redefs [rf.interop/clear-timeout! (fn [h] (swap! cleared conj h) nil)]
        (rf.fx/release-frame! race-frame))
      (is (empty? @cleared)
          "release-frame! dropped the arming reservation WITHOUT cancelling the
           sentinel (a sentinel is not a cancellable host object)")
      (is (empty? (timers-for-frame race-frame))
          "the reservation slot was removed")
      ;; reset-dispatch-later-timers! over a mixed table (sentinel + real handle).
      (reset! cleared [])
      (reset! timers {[race-frame 2] sentinel
                      [race-frame 3] ::real-handle})
      (with-redefs [rf.interop/clear-timeout! (fn [h] (swap! cleared conj h) nil)]
        (rf.fx/reset-dispatch-later-timers!))
      (is (= [::real-handle] @cleared)
          "reset cancelled the REAL handle but NEVER the arming sentinel")
      (is (empty? @timers)
          "the table is fully cleared"))))

(deftest dispatch-later-zero-delay-live-path-fires-once-no-residue
  (testing "a real 0ms :dispatch-later on a LIVE frame fires the deferred event
            exactly once and leaves no side-table residue — the immediate-fire
            path is leak-free through the real host executor, no stress needed
            (rf2-3fc89f.3 acceptance 3)"
    (let [target-ran (atom 0)]
      (rf/make-frame {:id test-frame :doc "rf2-3fc89f.3 zero-delay live-path frame"})
      (rf/reg-event :rf2-uxz52g/target
        (fn [{:keys [db]} _] (swap! target-ran inc) {:db db}))
      (rf/reg-event :rf2-uxz52g/arm-now
        (fn [_ _] {:fx [[:dispatch-later {:ms 0 :event [:rf2-uxz52g/target]}]]}))

      (rf/dispatch-sync [:rf2-uxz52g/arm-now] {:frame test-frame})
      ;; Let the 0ms host timer fire and the deferred event drain.
      (Thread/sleep 200)

      (is (= 1 @target-ran)
          "the 0ms timer fired the deferred target exactly once through the real
           executor")
      (is (zero? (count (timers-for-frame test-frame)))
          "the fired 0ms timer left no orphan/spent handle in the side table")
      (rf/destroy-frame! test-frame))))

;; ===========================================================================
;; rf2-j538f7.2 — CLEANUP-WINS-DURING-ARMING callback SUPPRESSION.
;;
;; A residual gap survives the rf2-3fc89f.3 two-phase reservation: when cleanup
;; (`release-frame!` / `reset-dispatch-later-timers!`) removes the reservation
;; DURING arming, yet the host executor STILL runs the timer thunk before
;; `set-timeout!` returns its handle, the OLD thunk unconditionally dispatched a
;; dead-on-arrival event into the torn-down frame. Host cancellation cannot
;; un-run an already-started thunk, so the SLOT is treated as the thunk's
;; DISPATCH AUTHORITY: the thunk dispatches ONLY when its winning `swap-vals!`
;; snapshot still held its slot. These tests drive the composed interleaving
;; DETERMINISTICALLY through the `rf.interop/set-timeout!` seam — cleanup FIRST,
;; then the callback, then the handle return.
;; ===========================================================================

(def ^:private authority-frame :rf2-j538f72/race)
(def ^:private authority-event [:rf2-j538f72/target])

(deftest dispatch-later-release-during-arming-suppresses-callback-dispatch
  (testing "release-frame! wins DURING arming (removes the reservation) and the
            host callback STILL fires before set-timeout! returns: the callback
            has LOST its dispatch authority and dispatches NOTHING; the returned
            handle is cancelled once and the side table is empty (rf2-j538f7.2
            acceptance 1)"
    (let [dispatched (atom [])
          cleared    (atom [])
          the-handle ::handle
          opts       {:source :fx-dispatch-later :source-detail {:ms 600000}
                      :frame  authority-frame}]
      (with-dispatch-stub
        (fn [ev op] (swap! dispatched conj [ev op]))
        (fn []
          (with-redefs [rf.interop/set-timeout!
                        (fn [f _ms]
                          ;; Cleanup wins DURING arming: destroy removes the
                          ;; reservation while the timer is still mid-schedule …
                          (rf.fx/release-frame! authority-frame)
                          ;; … yet the host executor STILL runs the captured
                          ;; callback before set-timeout! returns (a legal JVM
                          ;; ordering the two-phase publish alone cannot suppress).
                          (f)
                          the-handle)
                        rf.interop/clear-timeout! (fn [h] (swap! cleared conj h) nil)]
            (#'rf.fx/arm-dispatch-later! authority-frame 600000 authority-event opts))))
      (is (empty? @dispatched)
          "the callback fired AFTER cleanup removed its reservation, so it had NO
           dispatch authority and dispatched NOTHING (before the fix it dispatched
           a dead-on-arrival event into the torn-down frame)")
      (is (= [the-handle] @cleared)
          "the publish phase found the reservation gone and CANCELLED the returned
           handle exactly once (the arming sentinel was never passed to
           clear-timeout!)")
      (is (empty? (timers-for-frame authority-frame))
          "no side-table entry survives — neither the callback nor the publish
           phase reinstated a slot"))))

(deftest dispatch-later-reset-during-arming-suppresses-callback-dispatch
  (testing "the same composed interleaving through reset-dispatch-later-timers!
            (a test-isolation reset winning DURING arming): the callback cannot
            leak a dispatch into the next test/runtime generation, and the arming
            SENTINEL is never passed to clear-timeout! (rf2-j538f7.2 acceptance 2)"
    (let [dispatched (atom [])
          cleared    (atom [])
          the-handle ::handle
          opts       {:source :fx-dispatch-later :source-detail {:ms 600000}
                      :frame  authority-frame}]
      (with-dispatch-stub
        (fn [ev op] (swap! dispatched conj [ev op]))
        (fn []
          (with-redefs [rf.interop/set-timeout!
                        (fn [f _ms]
                          ;; A test-isolation reset fires DURING arming — the
                          ;; reset-hooks table clears every frame's timers between
                          ;; tests, dropping the arming sentinel …
                          (rf.fx/reset-dispatch-later-timers!)
                          ;; … but the captured callback still runs before
                          ;; set-timeout! returns.
                          (f)
                          the-handle)
                        rf.interop/clear-timeout! (fn [h] (swap! cleared conj h) nil)]
            (#'rf.fx/arm-dispatch-later! authority-frame 600000 authority-event opts))))
      (is (empty? @dispatched)
          "the reset removed the reservation, so the callback had no authority and
           could NOT leak a dispatch into the next test/runtime generation")
      (is (= [the-handle] @cleared)
          "the publish phase cancelled the returned handle; the arming SENTINEL was
           NEVER passed to clear-timeout! (reset skips it)")
      (is (empty? (timers-for-frame authority-frame))
          "no residue in the side table"))))

(deftest dispatch-later-armed-handle-destroy-then-late-callback-suppressed
  (testing "an ORDINARY armed :dispatch-later (handle already PUBLISHED) that is
            destroyed before it fires: release-frame! cancels the real host
            handle, and even if the host already started the callback
            (cancellation cannot un-run an in-progress thunk), the callback has
            NO authority and dispatches NOTHING — no post-destroy dispatch, so no
            :rf.error/frame-destroyed downstream (rf2-j538f7.2 acceptance 4)"
    (let [dispatched  (atom [])
          cleared     (atom [])
          the-handle  ::armed-handle
          captured-cb (atom nil)
          opts        {:source :fx-dispatch-later :source-detail {:ms 600000}
                       :frame  authority-frame}]
      (with-dispatch-stub
        (fn [ev op] (swap! dispatched conj [ev op]))
        (fn []
          (with-redefs [rf.interop/set-timeout!
                        (fn [f _ms]
                          ;; Ordinary arm: capture the callback and return a real
                          ;; handle WITHOUT firing — the handle publishes normally.
                          (reset! captured-cb f)
                          the-handle)
                        rf.interop/clear-timeout! (fn [h] (swap! cleared conj h) nil)]
            (#'rf.fx/arm-dispatch-later! authority-frame 600000 authority-event opts)
            (is (= 1 (count (timers-for-frame authority-frame)))
                "the armed handle is published under the frame's key")
            ;; Destroy the frame: release-frame! cancels + drops the real handle.
            (rf.fx/release-frame! authority-frame)
            (is (= [the-handle] @cleared)
                "release-frame! cancelled the published host handle")
            (is (empty? (timers-for-frame authority-frame))
                "the frame's slot was dropped by release-frame!")
            ;; The host executor already started the callback before cancellation
            ;; landed — it runs ANYWAY. Callback authority must suppress it.
            (@captured-cb))))
      (is (empty? @dispatched)
          "the post-destroy callback found no slot in its winning snapshot, so it
           dispatched NOTHING into the torn-down frame — no dead-on-arrival event,
           hence no :rf.error/frame-destroyed downstream (before the fix it
           dispatched the dead event)")
      (is (= [the-handle] @cleared)
          "the handle was cancelled exactly once (by release-frame!); the
           suppressed callback added no further cancellation"))))
