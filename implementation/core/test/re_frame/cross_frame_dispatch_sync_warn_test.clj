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
  warns but proceeds, Spec 009 §Error categories, and rf2-fp97.

  ## Posture split (rf2-d2841)

  \"WARNS BUT PROCEEDS\" IS TWO CLAIMS, AND ONLY THE FIRST IS DEV-ONLY. Both
  categories here are bare `trace/emit!` / `trace/emit-error!` sites with no
  always-on twin — `:rf.error/dispatch-sync-in-handler` is emitted from
  `router.cljc` through `trace/emit-error!` alone — so under
  `-Dre-frame.debug=false` neither is observable, and every assertion about
  them is kept verbatim inside a `(when interop/debug-enabled? …)` arm marked
  `rf2-d2841`.

  PROCEEDS is production behaviour, and it is the half worth protecting: the
  whole Option-B decision is that a cross-frame `dispatch-sync` is NOT
  rejected. Each deftest asserts the target frame's handler ran and its
  app-db reflects the effect, unguarded, so the production lane pins the
  no-rejection contract even where the diagnostic is gone.

  The negatives — `no-warning-when-no-other-frame-is-mid-drain`, the
  \"same-frame must NOT pick up the cross-frame warning\" pair, and \"no
  dispatch-sync-in-handler for the cross-frame case\" — move inside the arms
  with their positives. Over the gate's empty stream every one of them passes
  without the guard they describe ever running, which is exactly the
  same-frame-vs-cross-frame distinction this file exists to police."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
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
    (rf/register-listener! :trace listener-id (fn [ev] (swap! a conj ev)))
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
    (rf/make-frame {:id :cfx.test/a :doc "caller frame"})
    (rf/make-frame {:id :cfx.test/b :doc "target frame"})

    (let [b-ran (atom false)]
      (rf/reg-event :b/leaf
        {:frame :cfx.test/b}
        (fn [{:keys [db]} _]
          (reset! b-ran true)
          {:db (assoc db :b-ran? true)}))

      (rf/reg-event :a/cross
        {:frame :cfx.test/a}
        (fn [_ _]
          ;; A is mid-drain here; this dispatch-sync! lands on B.
          (rf/dispatch-sync [:b/leaf] {:frame :cfx.test/b})
          {}))

      (let [recorded (record-traces! ::cfx)]
        (rf/dispatch-sync [:a/cross] {:frame :cfx.test/a})

        ;; ALWAYS-ON (rf2-d2841): PROCEEDS is the production half of "warns but
        ;; proceeds", and it is the substance of Option B — cross-frame
        ;; reentry is deliberately NOT rejected.
        (testing "the dispatch proceeded — frame B's handler ran"
          (is (true? @b-ran) "frame B's :b/leaf handler ran (warning did NOT refuse)")
          (is (true? (:b-ran? (rf/app-db-value :cfx.test/b)))
              "frame B's app-db reflects the handler's effect"))

        ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture split).
        (when interop/debug-enabled?
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

          (testing "no `:rf.error/dispatch-sync-in-handler` fires for the cross-frame case"
            ;; Per Mike's Option B: cross-frame is intentionally distinct
            ;; from same-frame reentry. The cross-frame warning is the
            ;; ONLY surface; the same-frame error should NOT fire. Inside the
            ;; arm because its sibling positive above is what proves the
            ;; stream is live — over an empty stream it says nothing.
            (is (empty? (dsih-errors recorded))
                "cross-frame dispatch-sync! is a warning, not an error")))))))

(deftest same-frame-dispatch-sync-still-errors
  (testing "the existing same-frame reentry contract is unchanged — error fires, no cross-frame warning"
    ;; This is the negative test the bead's brief calls out: the same-
    ;; frame case must keep its existing :rf.error/dispatch-sync-in-handler
    ;; behaviour and NOT pick up the cross-frame warning by accident.
    ;;
    ;; EP-0002 (rf2-9wa0lf): a frame is registered and BOTH dispatches
    ;; carry it explicitly so the inner dispatch hits the same-frame
    ;; reentry guard (rather than raising :rf.error/no-frame-context for a
    ;; frameless call — there is no longer a :rf/default floor).
    (rf/make-frame {:id :cfx.test/a :doc "same-frame reentry frame"})
    (rf/reg-event :leaf {:frame :cfx.test/a} (fn [{:keys [db]} _] {:db (assoc db :leaf? true)}))
    (rf/reg-event :nested-same-frame
      {:frame :cfx.test/a}
      (fn [_ _]
        ;; Same frame, explicit :frame opt — hits the same-frame reentry
        ;; guard (the cascade is mid-drain on :cfx.test/a).
        (rf/dispatch-sync [:leaf] {:frame :cfx.test/a})
        {}))

    (let [recorded (record-traces! ::same-frame-still-errors)]
      (rf/dispatch-sync [:nested-same-frame] {:frame :cfx.test/a})

      ;; ALWAYS-ON (rf2-d2841): same-frame reentry is REFUSED — the inner
      ;; `:leaf` handler never commits. That is the production-visible
      ;; contrast with the cross-frame case above, where the target's handler
      ;; does run, and it is what makes "the two cases are distinct" a claim
      ;; the production lane can still check.
      (is (nil? (:leaf? (rf/app-db-value :cfx.test/a)))
          "same-frame reentry did NOT run the inner handler (contrast: cross-frame does)")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture split).
      (when interop/debug-enabled?
        (is (= 1 (count (dsih-errors recorded)))
            "same-frame reentry still raises :rf.error/dispatch-sync-in-handler")
        (is (empty? (cross-frame-warnings recorded))
            "same-frame case must NOT emit the cross-frame warning")))))

(deftest no-warning-when-no-other-frame-is-mid-drain
  (testing "ordinary cross-frame dispatch-sync! (outside any drain) does NOT warn"
    ;; Tests / REPL callers routinely call dispatch-sync! with explicit
    ;; :frame opts. That use case must not produce spurious warnings —
    ;; the warning is specifically for the IN-FLIGHT-DRAIN case.
    (rf/make-frame {:id :cfx.test/a})
    (rf/make-frame {:id :cfx.test/b})
    (rf/reg-event :b/leaf {:frame :cfx.test/b}
      (fn [{:keys [db]} _] {:db (assoc db :b-ran? true)}))

    (let [recorded (record-traces! ::no-drain-no-warn)]
      ;; Plain top-level dispatch-sync against B — no frame is mid-drain.
      (rf/dispatch-sync [:b/leaf] {:frame :cfx.test/b})

      ;; ALWAYS-ON (rf2-d2841).
      (is (true? (:b-ran? (rf/app-db-value :cfx.test/b)))
          ":b/leaf still ran successfully")
      ;; rf2-d2841 — dev-instrumentation arm. A NEGATIVE over the trace
      ;; stream: under the gate no frame-drain state produces a warning, so
      ;; outside the arm "no frame is mid-drain" would be certified for free.
      (when interop/debug-enabled?
        (is (empty? (cross-frame-warnings recorded))
            "no frame is mid-drain when the dispatch-sync! fires — no warning expected")))))

(deftest fires-on-cross-frame-dispatch-sync-during-async-drain
  (testing "warning fires for cross-frame dispatch-sync! while caller frame's :in-drain? is true (not just :in-sync-drain?)"
    ;; The implementation checks BOTH :in-sync-drain? AND :in-drain? so
    ;; the warning fires regardless of whether the outer drain came from
    ;; dispatch-sync! or an ordinary async dispatch's scheduled drain.
    ;; Here we set up an async-drain scenario by forcing the outer drain
    ;; through dispatch-sync (which sets :in-sync-drain?=true AND, inside
    ;; drain!, also :in-drain?=true). Either flag's truthiness should be
    ;; sufficient to trigger the warning.
    (rf/make-frame {:id :cfx.test/a})
    (rf/make-frame {:id :cfx.test/b})

    (let [b-ran (atom false)]
      (rf/reg-event :b/leaf {:frame :cfx.test/b}
        (fn [{:keys [db]} _]
          (reset! b-ran true)
          {:db db}))
      (rf/reg-event :a/touch-b
        {:frame :cfx.test/a}
        (fn [_ _]
          (rf/dispatch-sync [:b/leaf] {:frame :cfx.test/b})
          {}))

      (let [recorded (record-traces! ::async-drain)]
        (rf/dispatch-sync [:a/touch-b] {:frame :cfx.test/a})

        ;; ALWAYS-ON (rf2-d2841): the cross-frame dispatch proceeded.
        (is (true? @b-ran))
        ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture split).
        (when interop/debug-enabled?
          (is (= 1 (count (cross-frame-warnings recorded)))
              "the cross-frame warning fires whenever any sibling frame is mid-drain"))))))
