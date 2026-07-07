(ns re-frame.ssr-sub-exception-two-frame-attribution-test
  "Per rf2-vdk33 / rf2-vvwmi — the two-server-frame attribution proof for
  the ALWAYS-ON error-emit projection path, exercised through a reactive
  subscription that throws under production hardening.

  Context. rf2-7d30s proved per-frame attribution (route solely by
  `[:tags :frame]`; no single-frame fallback) at the core ssr level — but
  only for ONE of the two listener substrates:

    - `ssr_error_two_frame_attribution_test` drives TWO server frames, but
      exclusively through `:rf.error/schema-validation-failure` — the
      DEV-only `error-projection-listener` path. It DCEs under
      `interop/debug-enabled? = false` and so does not cover the channel
      that survives production hardening.
    - `ssr_error_projector_substrate_test` exercises the ALWAYS-ON
      `error-emit-projection-listener`, but with a SINGLE frame — so it
      cannot catch a per-frame mis-attribution regression.

  The production-survivable channel for 500-class errors is the always-on
  `error-emit-projection-listener` (the one that fires under
  `-Dre-frame.debug=false` per Spec 011 §Substrate). The canonical
  concurrent shape — >1 live server frame, an exception in one — was
  UNPROVEN for that path. A future change to
  `error-emit-projection-listener`'s frame-routing could re-introduce the
  single-frame-fallback class of bug on the PRODUCTION path and every
  existing test would stay green.

  This suite closes that gap, using the rf2-vvwmi sub-exception fix as the
  vehicle: a reactive subscription that throws now routes through the
  always-on `error-emit/dispatch-on-error!` substrate (subs/memo.cljc), so
  under `with-redefs [interop/debug-enabled? false]` (production-hardening
  posture per rf2-vnjfg) a sub-throw in ONE of two live server frames
  must:

    1. fail-closed to a 500 on THAT frame's response accumulator, and
    2. leave the SIBLING frame's response untouched (no cross-frame
       bleed) — provable only with >1 server frame live, the exact shape
       the removed single-frame fallback could not handle, and
    3. project an ELIDED public body (the locked four keys only — no
       `:exception` / message / internal detail across the HTTP
       boundary).

  ## SCOPE — core/accumulator layer ONLY (rf2-c0bq1)

  IMPORTANT: this suite drives `(rf/subscribe-once …)` FIRST — which runs
  the sub body SYNCHRONOUSLY and buffers the projected status — and reads
  `(ssr/get-response …)` AFTER. That is the INVERSE of the reference Ring
  handler order, which reads `get-response` ONCE *before* the render walk
  (the walk being where a reactive sub actually throws). So this suite
  proves the listener / accumulator / per-frame-attribution layer is
  correct, but it does NOT — and must not be read as — proof that the
  fail-closed 500 reaches the WIRE. rf2-c0bq1 documented exactly that
  false-confidence trap: the reference adapter shipped a silent 200 for a
  render-time sub-throw despite every assertion here being green, because
  the adapter read the (empty) buffer before the render that fills it.

  The WIRE/end-to-end fail-closed contract is pinned by the handler-level
  tripwire `re-frame.ssr.ring-rendertime-sub-failclosed-test` (ssr-ring
  artefact), which drives a render-time throwing sub through the REAL
  read-then-render handler order and asserts a non-200 on the wire. Do not
  extend THIS suite to cover the wire path — the layering split is
  deliberate (this = core attribution; that = adapter wire ordering).

  Companion suites:
    - `ssr_error_two_frame_attribution_test`     — two-frame, DEV path.
    - `ssr_error_projector_substrate_test`       — always-on path, single frame.
    - `re-frame.trace-test`                      — the core sub-exception emit site.
    - `re-frame.ssr.ring-rendertime-sub-failclosed-test` (ssr-ring) — the
      WIRE-level fail-closed tripwire that this suite's inverse ordering
      could NOT prove (rf2-c0bq1)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

(def ^:private frame-a :ssr/sub-req-a)
(def ^:private frame-b :ssr/sub-req-b)

(defn- register-subs! []
  ;; A subscription that throws while computing. The reactive sub-run
  ;; catch (subs/memo.cljc) recovers the value to nil but, per rf2-vvwmi,
  ;; routes `:rf.error/sub-exception` through the ALWAYS-ON
  ;; error-emit/dispatch-on-error! substrate — frame-attributed by the
  ;; running frame's id, the `[:tags :frame]` the projection listener
  ;; routes on.
  (rf/reg-sub :throwing-sub (fn [_db _] (throw (ex-info "sub-boom" {}))))
  ;; A clean subscription — the sibling frame derefs this and must NOT be
  ;; tainted by frame-a's throw.
  (rf/reg-sub :clean-sub (fn [_db _] :ok)))

(defn- make-server-frame [frame-id]
  ;; `reg-frame` (not `make-frame`) so the two frames carry STABLE, named
  ;; ids. Both are `:platform :server` so `error-projector/server-frame?`
  ;; recognises them. The default projector maps `:rf.error/sub-exception`
  ;; → 500 (Spec 011 §Default projector).
  (rf/reg-frame frame-id
    {:platform :server
     :ssr      {:public-error-id   :rf.ssr/default-error-projector
                :dev-error-detail? false}}))

;; ===========================================================================
;; (1) Two live server frames, production hardening — a sub-throw in frame-a
;;     stamps 500 on frame-a ONLY; frame-b stays 200.
;; ===========================================================================

(deftest two-server-frames-sub-exception-fails-closed-on-emitting-frame-only
  (testing "rf2-vdk33/rf2-vvwmi: under `interop/debug-enabled? = false`
            (production hardening), a reactive subscription that throws in
            frame-a routes `:rf.error/sub-exception` through the ALWAYS-ON
            error-emit substrate → the default projector stamps 500 on
            frame-a's response accumulator. frame-b (which derefs a clean
            sub) stays at the default 200. With >1 server frame live this
            can only succeed if the trace carries the emitting frame's
            `:frame`; the removed single-frame fallback would have no-op'd,
            shipping a silent 200 for the broken render."
    (register-subs!)
    (let [fa (make-server-frame frame-a)
          fb (make-server-frame frame-b)]
      (with-redefs [interop/debug-enabled? false]
        ;; Both frames are live registered server frames — the exact
        ;; >1-server-frame shape the removed fallback could not handle.
        ;; frame-a derefs the throwing sub; frame-b derefs the clean one.
        ;;
        ;; rf2-c0bq1 — NOTE THE ORDERING: subscribe-once (runs the sub,
        ;; buffers the status) THEN get-response. This is the INVERSE of
        ;; the Ring handler (get-response-then-render). It proves the core
        ;; attribution layer, NOT the wire. The wire fail-closed contract
        ;; lives in `ring-rendertime-sub-failclosed-test`.
        (rf/subscribe-once [:throwing-sub] {:frame fa})
        (rf/subscribe-once [:clean-sub] {:frame fb})

        (is (= 500 (:status (ssr/get-response fa)))
            "frame-a's sub-exception fails closed to 500 on frame-a's
             response — the always-on substrate carried the projection
             under the disabled dev gate (the dev-only trace path is
             elided here), and the `:frame` stamp routed it to the
             emitting frame even with a sibling server frame live")
        (is (= 200 (:status (ssr/get-response fb)))
            "frame-b's response stays at the default 200 — frame-a's
             sub-throw did not bleed onto the sibling. The removed
             single-frame fallback would have no-op'd with >1 server
             frame, masking the per-frame contract this asserts")))))

;; ===========================================================================
;; (2) Symmetry — a throw in frame-b stamps frame-b only; frame-a stays clean.
;; ===========================================================================

(deftest two-server-frames-sub-exception-attributes-each-frame-independently
  (testing "rf2-vdk33: the mirror of (1) — a sub-throw in frame-b fails
            closed on frame-b while frame-a stays 200. Proves attribution
            follows the EMITTING frame in both directions on the
            production path, not a fixed/first-registered server frame."
    (register-subs!)
    (let [fa (make-server-frame frame-a)
          fb (make-server-frame frame-b)]
      (with-redefs [interop/debug-enabled? false]
        (rf/subscribe-once [:throwing-sub] {:frame fb})
        (rf/subscribe-once [:clean-sub] {:frame fa})
        (is (= 500 (:status (ssr/get-response fb)))
            "frame-b (the emitting frame) fails closed to 500")
        (is (= 200 (:status (ssr/get-response fa)))
            "frame-a stays at the default 200 — clean")))))

;; ===========================================================================
;; (3) The projected 500 body is ELIDED — the locked four keys only, no
;;     exception / message / internal detail crosses the HTTP boundary.
;; ===========================================================================

(deftest sub-exception-projected-body-is-elided
  (testing "rf2-vvwmi (Mike's threat-model ruling): the 5xx the sub-throw
            projects under production hardening carries ONLY the locked
            public-error shape — `:status` / `:code` / `:message` /
            `:retryable?`. The exception, its message, the sub query, and
            any other internal detail never cross the HTTP boundary
            (Spec 011 §Where sanitisation happens). `:dev-error-detail?`
            is false (the prod default) so no `:details` slot leaks the
            raw trace."
    (register-subs!)
    (let [project-error ssr/project-error
          fa            (make-server-frame frame-a)]
      (with-redefs [interop/debug-enabled? false]
        (rf/subscribe-once [:throwing-sub] {:frame fa})
        (let [resp (ssr/get-response fa)]
          (is (= 500 (:status resp))
              "sub-exception fails closed to 500 under production hardening")
          ;; The wire response carries the public-error status, not the
          ;; internal trace. Project the (default-projector) public shape
          ;; for the sub-exception category and assert it is exactly the
          ;; locked generic-500 — no leak surface.
          (let [public (project-error fa {:operation :rf.error/sub-exception
                                          :tags      {:exception         (ex-info "sub-boom" {})
                                                      :exception-message "sub-boom"
                                                      :sub-query         [:throwing-sub]}})]
            (is (= {:status     500
                    :code       :internal-error
                    :message    "Something went wrong"
                    :retryable? false}
                   public)
                "default projector's prod shape carries exactly the four
                 locked keys — the elided fail-closed payload")
            (is (not (contains? public :details))
                "prod shape (:dev-error-detail? false) — :details is absent
                 so no exception / message / sub-query leaks across the
                 HTTP boundary (the AI/human-facing egress threat model)")
            (is (not-any? #{:exception :exception-message :sub-query :reason}
                          (keys public))
                "no internal error slot survives into the public body")))))))
