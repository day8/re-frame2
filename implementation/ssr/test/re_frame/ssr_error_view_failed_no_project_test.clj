(ns re-frame.ssr-error-view-failed-no-project-test
  "`:rf.error/ssr-ring-error-view-failed` is a RECOVERABLE DEGRADATION,
  not a request failure, and MUST NOT project a non-200 status onto the
  response accumulator. It is a member of the
  `non-projection-eligible-errors` skip set, beside
  `:rf.error/ssr-head-resolution-failed`; this suite is the sibling
  tripwire that pins it at the same core-level chokepoint.

  CONTRACT (ssr-ring's `resolve-error-body`): when a caller's registered
  `:error-view` itself THROWS, the host must NOT let the buggy error page
  bypass the error boundary — it falls back to the locked default error
  template AND emits `:rf.error/ssr-ring-error-view-failed`
  (frame-stamped, on the dev trace bus via `trace/emit-error!` and on the
  always-on error-emit axis) for observability. The wire status was already
  stamped by `project-render-exception!` for the ORIGINAL render-time
  throw that drove us into the error path; the error-view's own failure
  is a degradation of the error PRESENTATION, not a second request
  failure, so it must ship its trace without re-projecting a status.

  WHY THIS SUITE EXISTS. The emit fires INSIDE `build-full-response`'s
  render-time catch, AFTER `project-render-exception!` has already
  stamped the projected status and cleared the buffer
  (`consume-pending-traces!`). Without the skip the frame-stamped trace
  would be re-buffered into `pending-error-traces` and left there until
  frame-destroy. That would be harmless only while nothing re-reads
  `get-response` / `flush-response!` on the frame after that point —
  the post-render re-flush lives on the HAPPY path inside
  `build-full-response*`, which the error-view-failed catch arm never
  reaches, so the skip composes with it: the re-flush cannot re-read
  this category because the category never co-occurs with the re-flush.
  But a CUSTOM projector that mapped the render exception to a 4xx, plus
  a post-error re-flush, would let the buffered trace re-project → its
  generic 5xx and silently flip 4xx→5xx — correctness incidental to call
  ordering rather than enforced.

  The skip ENFORCES the contract at the projection BUFFERING chokepoint:
  `re-frame.ssr.error-listener` lists the category in
  `non-projection-eligible-errors` and both projection listeners (the
  dev-only `error-projection-listener` AND the always-on
  `error-emit-projection-listener`) skip it — so the trace is never
  buffered for status projection, regardless of call ordering or which
  substrate carried it. The Ring-level wire pin (the boundary still
  ships a 500 with the default template) lives in
  `re-frame.ssr.ring-test/handler-error-view-throw-falls-back-to-default-
  template`; this suite pins the SKIP directly at the core level.

  ## Posture split

  Tests (1) and (2) drive the DEV bus through `trace/emit-error!`, whose
  emit site sits inside the load-time `interop/debug-enabled?` gate. Under
  `-Dre-frame.debug=false` nothing is emitted, so (1) — a NEGATIVE, 'not
  buffered, still 200' — passes for the wrong reason (it would pass with
  the listener deleted) and (2)'s control reds on an empty buffer. Both
  sit inside `(when interop/debug-enabled? …)` arms: they
  are assertions ABOUT the dev listener, which is the surface their names
  and docstrings claim.

  Tests (3) and (4) are the production-visible counterparts and run under
  BOTH postures, calling `error-emit-projection-listener` DIRECTLY — the
  always-on path a `-Dre-frame.debug=false` JVM SSR host uses. (4) is what
  stops the guard costing coverage: `:rf.error/sub-exception` genuinely
  reaches production on that axis, so the no-over-skip control is
  adjudicated for real under the gate rather than only in dev."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.error-listener :as rf.ssr.error-listener]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.trace :as rf.trace]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private server-frame :ssr/error-view-failed-no-project-frame)

(defn- make-server-frame []
  ;; `:platform :server` so `error-projector/server-frame?` recognises it;
  ;; the default projector would map any PROJECTED `:rf.error/*` → 500, so
  ;; a 200 below proves the error-view-failed trace was NOT projected.
  (rf/make-frame {:id server-frame :platform :server
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
  (testing "firing `:rf.error/ssr-ring-error-view-failed` the
            same way `resolve-error-body` does (frame-stamped, via
            `trace/emit-error!`) does NOT buffer the trace for projection
            and does NOT flip the response status off 200 — the dev-only
            `error-projection-listener` skips the category by design."
    (let [fid (make-server-frame)]
      ;; DEV ARM. `trace/emit-error!` no-ops under
      ;; `-Dre-frame.debug=false`, so both assertions below would hold with
      ;; the listener ripped out: a negative over a bus that emitted
      ;; nothing. The production counterpart is test (3), which calls the
      ;; always-on listener directly and is posture-independent.
      (when rf.interop/debug-enabled?
        ;; Stamp the frame exactly as resolve-error-body's catch arm does.
        (rf.trace/emit-error! :rf.error/ssr-ring-error-view-failed
                           {:frame     fid
                            :exception "synthetic error-view failure"
                            :ex-class  "clojure.lang.ExceptionInfo"
                            :recovery  :fell-back-to-default-error-template})
        (is (empty? (get @rf.ssr.error-listener/pending-error-traces fid))
            "the error-view-failed trace was SKIPPED — not buffered into
             pending-error-traces (so a later get-response / re-flush can't
             re-project it and flip the already-stamped status)")
        (is (= 200 (:status (rf.ssr/get-response fid)))
            "status stays 200 — the error-view failure degraded gracefully
             to the locked default template; the default projector (→ 500)
             never ran for this category")))))

;; ===========================================================================
;; (2) NO OVER-SKIP — a GENUINE drain-time error fired through the same
;;     dev listener IS buffered and projects its non-200. Proves the skip
;;     is specific to the recoverable-degradation categories, not a
;;     blanket no-op that would swallow a real failure's status.
;; ===========================================================================

(deftest dev-path-genuine-drain-time-error-still-projects-non-200
  (testing "no over-skip: a frame-stamped GENUINE drain-time
            failure (`:rf.error/sub-exception` — a reactive sub throwing
            mid-render is unusable-page, fail-closed) fired
            through the same dev listener IS buffered and projects a
            non-200 — confirming the error-view-failed skip is targeted,
            not a listener that silently drops everything."
    (let [fid (make-server-frame)]
      ;; DEV ARM: the emit is `trace/emit-error!`, so under the
      ;; production gate this control observes an empty buffer. The category
      ;; itself DOES reach production, so the control is not lost — it is
      ;; re-run on the always-on axis by test (4).
      (when rf.interop/debug-enabled?
        (rf.trace/emit-error! :rf.error/sub-exception
                           {:frame     fid
                            :exception (ex-info "sub boom" {})
                            :recovery  :no-recovery})
        (is (seq (get @rf.ssr.error-listener/pending-error-traces fid))
            "a genuine drain-time failure IS buffered for projection")
        (is (not= 200 (:status (rf.ssr/get-response fid)))
            "and it projects a non-200 — the listener is doing real work;
             the error-view-failed skip in (1) is therefore meaningful and
             does NOT over-skip a real failure's fail-closed status")))))

;; ===========================================================================
;; (3) Always-on substrate — symmetry guard. `resolve-error-body` emits the
;;     error-view-failed record on the always-on error-emit axis as well as
;;     the dev bus, and non-Ring host adapters MUST emit the same
;;     recoverable-degradation categories (Spec 011 §1070). The skip is
;;     symmetric, so the degraded-200 contract holds under production
;;     hardening too — mirroring the always-on head-category pin in
;;     `re-frame.ssr-head-resolution-no-project-test`.
;; ===========================================================================

(deftest always-on-path-error-view-failed-record-is-not-buffered-or-projected
  (testing "an error-view-failed record delivered to the
            ALWAYS-ON `error-emit-projection-listener` (the production-
            survivable substrate, exercised under
            `interop/debug-enabled? = false`) is ALSO skipped — neither
            buffered nor projected. Symmetric with the dev path so the
            contract holds whichever substrate carries the trace."
    (let [fid (make-server-frame)]
      ;; No `with-redefs [interop/debug-enabled? false]` wraps this test:
      ;; `debug-enabled?` is read ONCE at namespace-load time, so a rebind
      ;; cannot reach the gate, and this test calls the always-on listener
      ;; DIRECTLY anyway — there is no gate on the path. The namespace runs in
      ;; `scripts/test-ssr-prod-gate.sh`, where the property is false for
      ;; real; that is the evidence.
      ;;
      ;; The error-emit record shape per `error-emit/dispatch-on-error!`.
      (rf.ssr.error-listener/error-emit-projection-listener
        {:error      :rf.error/ssr-ring-error-view-failed
         :event      nil
         :event-id   nil
         :frame      fid
         :time       0
         :exception  (ex-info "synthetic error-view failure" {})
         :elapsed-ms 0})
      (is (empty? (get @rf.ssr.error-listener/pending-error-traces fid))
          "the always-on listener also skips the error-view-failed
           category — not buffered")
      (is (= 200 (:status (rf.ssr/get-response fid)))
          "status stays 200 on the production substrate too"))))

;; ===========================================================================
;; (4) Always-on NO-OVER-SKIP control — the production counterpart of (2).
;;     On a `-Dre-frame.debug=false` JVM the dev bus is silent,
;;     so "the error-view-failed record was not buffered" is only meaningful
;;     next to "a genuine drain-time record on the SAME listener WAS".
;; ===========================================================================

(deftest always-on-path-genuine-drain-time-error-still-projects-non-200
  (testing "no over-skip, always-on:
            `:rf.error/sub-exception` — a reactive sub throwing mid-render,
            fail-closed — delivered to the ALWAYS-ON
            `error-emit-projection-listener` IS buffered and projects a
            non-200. The category rides `dispatch-on-error!` in production,
            so unlike the dev control this one has a live producer in the
            posture that ships: an over-broad skip would silently turn an
            unusable page into a 200 on a real server."
    (let [fid (make-server-frame)]
      (rf.ssr.error-listener/error-emit-projection-listener
        {:error      :rf.error/sub-exception
         :event      nil
         :event-id   nil
         :frame      fid
         :time       0
         :exception  (ex-info "sub boom" {})
         :elapsed-ms 0})
      (is (seq (get @rf.ssr.error-listener/pending-error-traces fid))
          "a genuine drain-time failure IS buffered by the always-on listener")
      (is (= 500 (:status (rf.ssr/get-response fid)))
          "and it projects the default projector's fail-closed 500 — the
           always-on listener is doing real work in this posture, so the
           error-view-failed skip in (3) is targeted and does not swallow a
           real failure's status"))))
