(ns re-frame.cross-frame-dispatch-sync-warn-test
  "Per rf2-fp97 — emit `:rf.warning/cross-frame-dispatch-sync-during-drain`
  when `dispatch-sync!` is called against a target frame while a DIFFERENT
  frame is mid-drain. Per Mike's 2026-05-13 Option B decision:

    1. Same-frame reentry is already rejected with
       `:rf.error/dispatch-sync-in-handler` (covered by drain_test.clj).
    2. Cross-frame reentry is NOT rejected — frames are independent
       state machines per Spec 002 §Rules rule 1 — but the cascades
       interleave (target frame drains to settled while caller's frame
       is still in flight), which is rarely the caller's intent.
    3. The runtime emits
       `:rf.warning/cross-frame-dispatch-sync-during-drain` so
       observability tools spot the pattern; the dispatch proceeds.

  Per Spec 002 §Cross-frame `dispatch-sync` during a sibling drain
  warns but proceeds, Spec 009 §Error categories, and rf2-fp97."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces!
  "Attach a recording listener and return its atom."
  [listener-id]
  (let [a (atom [])]
    (rf/register-listener! listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- cross-frame-warnings
  [recorded]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= :rf.warning/cross-frame-dispatch-sync-during-drain
                     (:operation ev))))
           @recorded))

(defn- dsih-errors
  [recorded]
  (filterv (fn [ev]
             (and (= :error (:op-type ev))
                  (= :rf.error/dispatch-sync-in-handler (:operation ev))))
           @recorded))

;; ---- tests ----------------------------------------------------------------

(deftest fires-on-cross-frame-dispatch-sync-during-drain
  (testing "frame A mid-drain calling dispatch-sync! on frame B emits the warning, continues, and frame B's handler runs"
    (rf/reg-frame :cfx.test/a {:doc "caller frame"})
    (rf/reg-frame :cfx.test/b {:doc "target frame"})

    (let [b-ran (atom false)]
      (rf/reg-event-db :b/leaf
        {:frame :cfx.test/b}
        (fn [db _]
          (reset! b-ran true)
          (assoc db :b-ran? true)))

      (rf/reg-event-fx :a/cross
        {:frame :cfx.test/a}
        (fn [_ _]
          ;; A is mid-drain here; this dispatch-sync! lands on B.
          (rf/dispatch-sync [:b/leaf] {:frame :cfx.test/b})
          {}))

      (let [recorded (record-traces! ::cfx)]
        (rf/dispatch-sync [:a/cross] {:frame :cfx.test/a})

        (testing "exactly one cross-frame warning fires"
          (let [warns (cross-frame-warnings recorded)]
            (is (= 1 (count warns))
                (str "expected exactly one cross-frame warning, got "
                     (count warns)))
            (let [w (first warns)
                  t (:tags w)]
              (is (= :cfx.test/a (:caller-frame t))
                  ":caller-frame should be the frame whose drain is in flight")
              (is (= :cfx.test/b (:target-frame t))
                  ":target-frame should be the dispatch-sync!'s :frame opt")
              (is (= :cfx.test/a (:other-frame t))
                  ":other-frame is the sibling that is mid-drain (here, the caller)")
              (is (= [:b/leaf] (:event t)))
              (is (string? (:reason t)))
              (is (re-find #"mid-drain|interleave|cross-frame" (:reason t))
                  "reason should describe the interleave pattern")
              (is (= :no-recovery (:recovery w))))))

        (testing "the dispatch proceeded — frame B's handler ran"
          (is (true? @b-ran) "frame B's :b/leaf handler ran (warning did NOT refuse)")
          (is (true? (:b-ran? (rf/get-frame-db :cfx.test/b)))
              "frame B's app-db reflects the handler's effect"))

        (testing "no `:rf.error/dispatch-sync-in-handler` fires for the cross-frame case"
          ;; Per Mike's Option B: cross-frame is intentionally distinct
          ;; from same-frame reentry. The cross-frame warning is the
          ;; ONLY surface; the same-frame error should NOT fire.
          (is (empty? (dsih-errors recorded))
              "cross-frame dispatch-sync! is a warning, not an error"))))))

(deftest same-frame-dispatch-sync-still-errors
  (testing "the existing same-frame reentry contract is unchanged — error fires, no cross-frame warning"
    ;; This is the negative test the bead's brief calls out: the same-
    ;; frame case must keep its existing :rf.error/dispatch-sync-in-handler
    ;; behaviour and NOT pick up the cross-frame warning by accident.
    (rf/reg-event-db :leaf (fn [db _] (assoc db :leaf? true)))
    (rf/reg-event-fx :nested-same-frame
      (fn [_ _]
        ;; Same frame (:rf/default), no :frame opt — hits the same-frame
        ;; reentry guard.
        (rf/dispatch-sync [:leaf])
        {}))

    (let [recorded (record-traces! ::same-frame-still-errors)]
      (rf/dispatch-sync [:nested-same-frame])

      (is (= 1 (count (dsih-errors recorded)))
          "same-frame reentry still raises :rf.error/dispatch-sync-in-handler")
      (is (empty? (cross-frame-warnings recorded))
          "same-frame case must NOT emit the cross-frame warning"))))

(deftest no-warning-when-no-other-frame-is-mid-drain
  (testing "ordinary cross-frame dispatch-sync! (outside any drain) does NOT warn"
    ;; Tests / REPL callers routinely call dispatch-sync! with explicit
    ;; :frame opts. That use case must not produce spurious warnings —
    ;; the warning is specifically for the IN-FLIGHT-DRAIN case.
    (rf/reg-frame :cfx.test/a {})
    (rf/reg-frame :cfx.test/b {})
    (rf/reg-event-db :b/leaf {:frame :cfx.test/b}
      (fn [db _] (assoc db :b-ran? true)))

    (let [recorded (record-traces! ::no-drain-no-warn)]
      ;; Plain top-level dispatch-sync against B — no frame is mid-drain.
      (rf/dispatch-sync [:b/leaf] {:frame :cfx.test/b})

      (is (empty? (cross-frame-warnings recorded))
          "no frame is mid-drain when the dispatch-sync! fires — no warning expected")
      (is (true? (:b-ran? (rf/get-frame-db :cfx.test/b)))
          ":b/leaf still ran successfully"))))

(deftest fires-on-cross-frame-dispatch-sync-during-async-drain
  (testing "warning fires for cross-frame dispatch-sync! while caller frame's :in-drain? is true (not just :in-sync-drain?)"
    ;; The implementation checks BOTH :in-sync-drain? AND :in-drain? so
    ;; the warning fires regardless of whether the outer drain came from
    ;; dispatch-sync! or an ordinary async dispatch's scheduled drain.
    ;; Here we set up an async-drain scenario by forcing the outer drain
    ;; through dispatch-sync (which sets :in-sync-drain?=true AND, inside
    ;; drain!, also :in-drain?=true). Either flag's truthiness should be
    ;; sufficient to trigger the warning.
    (rf/reg-frame :cfx.test/a {})
    (rf/reg-frame :cfx.test/b {})

    (let [b-ran (atom false)]
      (rf/reg-event-db :b/leaf {:frame :cfx.test/b}
        (fn [db _]
          (reset! b-ran true)
          db))
      (rf/reg-event-fx :a/touch-b
        {:frame :cfx.test/a}
        (fn [_ _]
          (rf/dispatch-sync [:b/leaf] {:frame :cfx.test/b})
          {}))

      (let [recorded (record-traces! ::async-drain)]
        (rf/dispatch-sync [:a/touch-b] {:frame :cfx.test/a})

        (is (= 1 (count (cross-frame-warnings recorded)))
            "the cross-frame warning fires whenever any sibling frame is mid-drain")
        (is (true? @b-ran))))))
