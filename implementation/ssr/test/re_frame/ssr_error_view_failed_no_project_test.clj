(ns re-frame.ssr-error-view-failed-no-project-test
  "Per rf2-sccp5 — `:rf.error/ssr-ring-error-view-failed` is a RECOVERABLE
  DEGRADATION, not a request failure, and MUST NOT project a non-200
  status onto the response accumulator. It is the SECOND member of the
  `non-projection-eligible-errors` skip set (the first being
  `:rf.error/ssr-head-resolution-failed`, rf2-lia3i); this suite is the
  sibling tripwire that pins the new member at the same core-level
  chokepoint.

  CONTRACT (ssr-ring's `resolve-error-body`, pipeline.clj:228): when a
  caller's registered `:error-view` itself THROWS, the host must NOT let
  the buggy error page bypass the error boundary — it falls back to the
  locked default error template AND emits
  `:rf.error/ssr-ring-error-view-failed` (frame-stamped, via
  `trace/emit-error!`) for observability. The wire status was already
  stamped by `project-render-exception!` for the ORIGINAL render-time
  throw that drove us into the error path; the error-view's own failure
  is a degradation of the error PRESENTATION, not a second request
  failure, so it must ship its trace without re-projecting a status.

  WHY THIS SUITE EXISTS. The emit fires INSIDE `build-full-response`'s
  render-time catch, AFTER `project-render-exception!` has already
  stamped the projected status and cleared the buffer
  (`consume-pending-traces!`). Without the skip the frame-stamped trace
  is re-buffered into `pending-error-traces` and left there until
  frame-destroy. Safe TODAY only because nothing re-reads
  `get-response` / `flush-response!` on the frame after that point —
  the rf2-c0bq1 post-render re-flush (pipeline.clj:331) lives on the
  HAPPY path inside `build-full-response*`, which the error-view-failed
  catch arm never reaches, so the skip composes with c0bq1: the re-flush
  cannot re-read this category because the category never co-occurs with
  the re-flush. But a CUSTOM projector that mapped the render exception
  to a 4xx, plus a future post-error re-flush, would let the buffered
  trace re-project → its generic 5xx and silently flip 4xx→5xx — the
  exact 'incidental to call ordering, not enforced' property rf2-lia3i
  declared unacceptable.

  rf2-sccp5 ENFORCES the contract at the projection BUFFERING chokepoint:
  `re-frame.ssr.error-listener` lists the category in
  `non-projection-eligible-errors` and both projection listeners (the
  dev-only `error-projection-listener` AND the always-on
  `error-emit-projection-listener`) skip it — so the trace is never
  buffered for status projection, regardless of call ordering or which
  substrate carried it. The Ring-level wire pin (the boundary still
  ships a 500 with the default template) lives in
  `re-frame.ssr.ring-test/handler-error-view-throw-falls-back-to-default-
  template`; this suite pins the SKIP directly at the core level."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.error-listener :as error-listener]
            [re-frame.ssr.test-fixture :as tf]
            [re-frame.trace :as trace]))

(use-fixtures :each tf/reset-runtime)

(def ^:private server-frame :ssr/error-view-failed-no-project-frame)

(defn- make-server-frame []
  ;; `:platform :server` so `error-projector/server-frame?` recognises it;
  ;; the default projector would map any PROJECTED `:rf.error/*` → 500, so
  ;; a 200 below proves the error-view-failed trace was NOT projected.
  (rf/reg-frame server-frame
    {:platform :server
     :ssr      {:public-error-id   :rf.ssr/default-error-projector
                :dev-error-detail? false}})
  server-frame)

;; ===========================================================================
;; (1) Dev path — the actual `resolve-error-body` emit route.
;;     `resolve-error-body` calls `trace/emit-error!`, which (under
;;     `interop/debug-enabled?`) delivers to the dev trace bus feeding
;;     `error-projection-listener`. The error-view-failed trace must be
;;     SKIPPED there: status stays 200 and nothing is buffered.
;; ===========================================================================

(deftest dev-path-error-view-failed-trace-is-not-buffered-or-projected
  (testing "rf2-sccp5: firing `:rf.error/ssr-ring-error-view-failed` the
            same way `resolve-error-body` does (frame-stamped, via
            `trace/emit-error!`) does NOT buffer the trace for projection
            and does NOT flip the response status off 200 — the dev-only
            `error-projection-listener` skips the category by design."
    (let [fid (make-server-frame)]
      ;; Stamp the frame exactly as resolve-error-body's catch arm does.
      (trace/emit-error! :rf.error/ssr-ring-error-view-failed
                         {:frame     fid
                          :exception "synthetic error-view failure"
                          :ex-class  "clojure.lang.ExceptionInfo"
                          :recovery  :fell-back-to-default-error-template})
      (is (empty? (get @error-listener/pending-error-traces fid))
          "the error-view-failed trace was SKIPPED — not buffered into
           pending-error-traces (so a later get-response / re-flush can't
           re-project it and flip the already-stamped status)")
      (is (= 200 (:status (ssr/get-response fid)))
          "status stays 200 — the error-view failure degraded gracefully
           to the locked default template; the default projector (→ 500)
           never ran for this category"))))

;; ===========================================================================
;; (2) NO OVER-SKIP — a GENUINE drain-time error fired through the same
;;     dev listener IS buffered and projects its non-200. Proves the skip
;;     is specific to the recoverable-degradation categories, not a
;;     blanket no-op that would swallow a real failure's status.
;; ===========================================================================

(deftest dev-path-genuine-drain-time-error-still-projects-non-200
  (testing "rf2-sccp5 (no over-skip): a frame-stamped GENUINE drain-time
            failure (`:rf.error/sub-exception` — a reactive sub throwing
            mid-render is unusable-page, fail-closed per rf2-vvwmi) fired
            through the same dev listener IS buffered and projects a
            non-200 — confirming the error-view-failed skip is targeted,
            not a listener that silently drops everything."
    (let [fid (make-server-frame)]
      (trace/emit-error! :rf.error/sub-exception
                         {:frame     fid
                          :exception (ex-info "sub boom" {})
                          :recovery  :no-recovery})
      (is (seq (get @error-listener/pending-error-traces fid))
          "a genuine drain-time failure IS buffered for projection")
      (is (not= 200 (:status (ssr/get-response fid)))
          "and it projects a non-200 — the listener is doing real work;
           the error-view-failed skip in (1) is therefore meaningful and
           does NOT over-skip a real failure's fail-closed status"))))

;; ===========================================================================
;; (3) Always-on substrate — symmetry guard. The error-view-failed trace
;;     rides only the dev bus today (`trace/emit-error!`), but non-Ring
;;     host adapters MUST emit the same recoverable-degradation categories
;;     (Spec 011 §1070), and a future change could route it through the
;;     always-on `register-error-listener!` substrate. The skip is
;;     symmetric, so the degraded-200 contract holds under production
;;     hardening too — mirroring rf2-lia3i's always-on head-category pin.
;; ===========================================================================

(deftest always-on-path-error-view-failed-record-is-not-buffered-or-projected
  (testing "rf2-sccp5: an error-view-failed record delivered to the
            ALWAYS-ON `error-emit-projection-listener` (the production-
            survivable substrate, exercised under
            `interop/debug-enabled? = false`) is ALSO skipped — neither
            buffered nor projected. Symmetric with the dev path so the
            contract holds whichever substrate carries the trace."
    (let [fid (make-server-frame)]
      (with-redefs [interop/debug-enabled? false]
        ;; The error-emit record shape per `error-emit/dispatch-on-error!`.
        (error-listener/error-emit-projection-listener
          {:error      :rf.error/ssr-ring-error-view-failed
           :event      nil
           :event-id   nil
           :frame      fid
           :time       0
           :exception  (ex-info "synthetic error-view failure" {})
           :elapsed-ms 0})
        (is (empty? (get @error-listener/pending-error-traces fid))
            "the always-on listener also skips the error-view-failed
             category — not buffered")
        (is (= 200 (:status (ssr/get-response fid)))
            "status stays 200 on the production substrate too")))))
