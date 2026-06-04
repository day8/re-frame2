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
  not-projected-error`)."
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
  (rf/reg-frame server-frame
    {:platform :server
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
           default projector (→ 500) never ran for this category"))))

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
      (trace/emit-error! :rf.error/schema-validation-failure
                         {:frame     fid
                          :exception (ex-info "schema boom" {})
                          :recovery  :no-recovery})
      (is (seq (get @error-listener/pending-error-traces fid))
          "a non-degradation category IS buffered for projection")
      (is (not= 200 (:status (ssr/get-response fid)))
          "and it projects a non-200 — the listener is doing real work;
           the head-category skip in (1) is therefore meaningful"))))

;; ===========================================================================
;; (3) Always-on substrate — symmetry guard. The head trace rides only the
;;     dev bus today (`trace/emit-error!`), but non-Ring host adapters MUST
;;     emit the same category (Spec 011 §1070), and a future change could
;;     route it through the always-on `register-error-listener!` substrate.
;;     The skip is symmetric, so the degraded-200 contract holds under
;;     production hardening too.
;; ===========================================================================

(deftest always-on-path-head-failure-record-is-not-buffered-or-projected
  (testing "rf2-lia3i: a head-failure record delivered to the ALWAYS-ON
            `error-emit-projection-listener` (the production-survivable
            substrate, exercised under `interop/debug-enabled? = false`)
            is ALSO skipped — neither buffered nor projected. Symmetric
            with the dev path so the contract holds whichever substrate
            carries the trace."
    (let [fid (make-server-frame)]
      (with-redefs [interop/debug-enabled? false]
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
            "status stays 200 on the production substrate too")))))
