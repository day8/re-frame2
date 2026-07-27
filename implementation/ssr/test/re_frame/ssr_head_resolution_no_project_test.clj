(ns re-frame.ssr-head-resolution-no-project-test
  "Per rf2-lia3i — `:rf.error/ssr-head-resolution-failed` is a RECOVERABLE
  DEGRADATION, not a request failure, and MUST NOT project a non-200
  status onto the response accumulator.

  CONTRACT (Spec 011 §1070 — `resolve-head` emits the category before
  fallback; rf2-bof8i Option B): a route `:head` fn that throws degrades
  to an empty `<head>` fragment so 'a buggy head fn can't take down the
  request', AND emits `:rf.error/ssr-head-resolution-failed` for
  observability. This is the deliberate counterpoint to the view/sub
  FAIL-CLOSED posture (rf2-vvwmi / rf2-7d30s, Spec 011 §744/§748-751):
  a view or reactive sub that throws mid-render projects a non-200 (the
  page is unusable); a head fn that throws ships a 200 (only the metadata
  is missing — the body still renders + hydrates).

  WHY THIS SUITE EXISTS. Pre-rf2-lia3i the degraded-200 outcome was safe
  by TIMING ONLY: the Ring `ssr-handler` reads `get-response` (drains +
  reads status) BEFORE `build-full-response` → `resolve-head` fires the
  head trace, so the buffered head trace was never projected onto the
  already-captured status. The immunity was incidental to call ordering;
  a reorder (head resolution before `get-response`, a second flush after
  the render, a re-read of `get-response`) would have let the default
  projector map the head trace → 500 and silently flip the degraded 200.

  rf2-lia3i ENFORCES the contract at the projection BUFFERING chokepoint:
  `re-frame.ssr.error-listener` lists the category in
  `non-projection-eligible-errors` and both projection listeners (the
  dev-only `error-projection-listener` AND the always-on
  `error-emit-projection-listener`) skip it — so the head trace is never
  buffered for status projection, regardless of call ordering or which
  substrate carried it. This suite pins the skip directly at the core
  level, independent of the Ring handler (whose own wire-level pin lives
  in `re-frame.ssr.ring-test/handler-throwing-head-fn-ships-degraded-200-
  not-projected-error`).

  ## Posture split (rf2-lwtlk)

  Tests (1) and (2) drive the DEV bus through `trace/emit-error!`, whose
  emit site sits inside the load-time `interop/debug-enabled?` gate. Under
  `-Dre-frame.debug=false` nothing is emitted, so (1) — a NEGATIVE, 'not
  buffered, still 200' — passes for the wrong reason (it would pass with
  the listener deleted), and (2)'s control observes an empty buffer and
  reds. Both are kept VERBATIM inside `(when interop/debug-enabled? …)`
  arms: they are assertions ABOUT the dev listener, and (2) additionally
  uses `:rf.error/schema-validation-failure`, a category that is dev-only
  by design (boundary validation is itself production-elided, Spec 010
  §Production builds).

  Tests (3) and (4) are the production-visible counterparts and run under
  BOTH postures: they call `error-emit-projection-listener` DIRECTLY — the
  always-on path a `-Dre-frame.debug=false` JVM SSR host actually uses —
  so the skip AND its control are adjudicated for real under the gate. (4)
  is the one that stops the guard costing coverage: without a positive
  control on the always-on axis, (3) alone would be a negative nobody had
  proved could ever be positive."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.error-listener :as error-listener]
            [re-frame.ssr.test-fixture :as tf]
            [re-frame.trace :as trace]))

(use-fixtures :each tf/reset-runtime)

(def ^:private server-frame :ssr/head-no-project-frame)

(defn- make-server-frame []
  ;; `:platform :server` so `error-projector/server-frame?` recognises it;
  ;; the default projector would map any PROJECTED `:rf.error/*` → 500, so
  ;; a 200 below proves the head trace was NOT projected.
  (rf/make-frame {:id server-frame :platform :server
                  :ssr      {:public-error-id   :rf.ssr/default-error-projector
                             :dev-error-detail? false}})
  server-frame)

;; ===========================================================================
;; (1) Dev path — the actual `resolve-head` emit route.
;;     `resolve-head` calls `trace/emit-error!`, which (under
;;     `interop/debug-enabled?`) delivers to the dev trace bus feeding
;;     `error-projection-listener`. The head trace must be SKIPPED there:
;;     status stays 200 and nothing is buffered.
;; ===========================================================================

(deftest dev-path-head-failure-trace-is-not-buffered-or-projected
  (testing "rf2-lia3i: firing `:rf.error/ssr-head-resolution-failed` the
            same way `resolve-head` does (frame-stamped, via
            `trace/emit-error!`) does NOT buffer the trace for projection
            and does NOT flip the response status off 200 — the dev-only
            `error-projection-listener` skips the category by design."
    (let [fid (make-server-frame)]
      ;; rf2-lwtlk — DEV ARM. `trace/emit-error!` no-ops under
      ;; `-Dre-frame.debug=false`, so both assertions below would hold with
      ;; the listener ripped out: a negative over a bus that emitted
      ;; nothing. The production counterpart is test (3), which calls the
      ;; always-on listener directly and is posture-independent.
      (when interop/debug-enabled?
        ;; Stamp the frame exactly as resolve-head's catch arm does.
        (trace/emit-error! :rf.error/ssr-head-resolution-failed
                           {:frame     fid
                            :exception (ex-info "synthetic head failure" {:reason :test})
                            :recovery  :no-recovery})
        (is (empty? (get @error-listener/pending-error-traces fid))
            "the head trace was SKIPPED — not buffered into
             pending-error-traces (so a later get-response can't project it)")
        (is (= 200 (:status (ssr/get-response fid)))
            "status stays 200 — the head failure degraded gracefully; the
             default projector (→ 500) never ran for this category")))))

;; ===========================================================================
;; (2) A buffering chokepoint contrast — a NON-degradation category fired
;;     the same way DOES buffer + project. Proves the skip is specific to
;;     the head category, not a blanket no-op of the listener.
;; ===========================================================================

(deftest dev-path-control-non-head-error-still-projects
  (testing "rf2-lia3i (control): a frame-stamped NON-degradation error
            category (`:rf.error/schema-validation-failure`) fired through
            the same dev listener IS buffered and projects a non-200 —
            confirming the head-category skip is targeted, not a listener
            that silently drops everything."
    (let [fid (make-server-frame)]
      ;; rf2-lwtlk — DEV ARM, on two counts. `trace/emit-error!` is
      ;; dev-gated, AND `:rf.error/schema-validation-failure` is a
      ;; DELIBERATELY dev-only category: boundary validation is itself
      ;; production-elided (Spec 010 §Production builds), so no production
      ;; reject exists for this control to observe. The always-on control
      ;; is test (4).
      (when interop/debug-enabled?
        (trace/emit-error! :rf.error/schema-validation-failure
                           {:frame     fid
                            :exception (ex-info "schema boom" {})
                            :recovery  :no-recovery})
        (is (seq (get @error-listener/pending-error-traces fid))
            "a non-degradation category IS buffered for projection")
        (is (not= 200 (:status (ssr/get-response fid)))
            "and it projects a non-200 — the listener is doing real work;
             the head-category skip in (1) is therefore meaningful")))))

;; ===========================================================================
;; (3) Always-on substrate — symmetry guard. Post-rf2-hhutya the head trace
;;     rides the always-on `register-error-listener!` substrate (via
;;     `dispatch-error-record!`) ALONGSIDE the dev bus (`trace/emit-error!`),
;;     so the EP-0008 promotion ships the off-box record on a
;;     `-Dre-frame.debug=false` JVM SSR host. The category stays NON-
;;     PROJECTING: the always-on `error-emit-projection-listener` skips it
;;     symmetrically with the dev listener, so the degraded-200 contract
;;     holds whichever substrate carries the trace — promotion changed what
;;     SHIPPERS see, NOT what the WIRE does.
;; ===========================================================================

(deftest always-on-path-head-failure-record-is-not-buffered-or-projected
  (testing "rf2-lia3i: a head-failure record delivered to the ALWAYS-ON
            `error-emit-projection-listener` (the production-survivable
            substrate, exercised under `interop/debug-enabled? = false`)
            is ALSO skipped — neither buffered nor projected. Symmetric
            with the dev path so the contract holds whichever substrate
            carries the trace."
    (let [fid (make-server-frame)]
      ;; rf2-lwtlk — the `with-redefs [interop/debug-enabled? false]` this
      ;; test used to wrap has been dropped, not replaced. It never bought
      ;; anything: `interop/debug-enabled?` is read ONCE at namespace-load
      ;; time, so a rebind cannot reach the gate, and this test calls the
      ;; always-on listener DIRECTLY anyway — there is no gate on the path.
      ;; The namespace now runs in `scripts/test-ssr-prod-gate.sh`, where
      ;; the property is false for real; that is the evidence.
      ;;
      ;; The error-emit record shape per `error-emit/dispatch-on-error!`.
      (error-listener/error-emit-projection-listener
        {:error      :rf.error/ssr-head-resolution-failed
         :event      nil
         :event-id   nil
         :frame      fid
         :time       0
         :exception  (ex-info "synthetic head failure" {})
         :elapsed-ms 0})
      (is (empty? (get @error-listener/pending-error-traces fid))
          "the always-on listener also skips the head category — not
           buffered")
      (is (= 200 (:status (ssr/get-response fid)))
          "status stays 200 on the production substrate too"))))

;; ===========================================================================
;; (4) Always-on CONTROL — the production counterpart of (2). rf2-lwtlk.
;;     Without this, the guard on (2) would leave the always-on skip in (3)
;;     as a negative nobody had proved could ever be positive: on a
;;     `-Dre-frame.debug=false` JVM the dev bus is silent, so "the head
;;     record was not buffered" is only meaningful next to "a
;;     non-degradation record on the SAME listener WAS".
;; ===========================================================================

(deftest always-on-path-control-non-head-error-still-projects
  (testing "rf2-lia3i / rf2-lwtlk (control, always-on): a frame-stamped
            NON-degradation category delivered to the ALWAYS-ON
            `error-emit-projection-listener` IS buffered and projects a
            non-200. `:rf.error/handler-exception` is used deliberately —
            unlike the dev control's `:rf.error/schema-validation-failure`
            it really does reach production (via `dispatch-on-error!`), so
            this control has a live producer in the posture that ships."
    (let [fid (make-server-frame)]
      (error-listener/error-emit-projection-listener
        {:error      :rf.error/handler-exception
         :event      [:load/article]
         :event-id   :load/article
         :frame      fid
         :time       0
         :exception  (ex-info "handler boom" {})
         :elapsed-ms 0})
      (is (seq (get @error-listener/pending-error-traces fid))
          "a non-degradation category IS buffered by the always-on listener")
      (is (= 500 (:status (ssr/get-response fid)))
          "and it projects the default projector's generic 500 — the
           always-on listener is doing real work in this posture, so the
           head-category skip in (3) is a targeted skip and not a listener
           that drops everything"))))
