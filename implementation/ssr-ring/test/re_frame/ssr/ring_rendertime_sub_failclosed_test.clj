(ns re-frame.ssr.ring-rendertime-sub-failclosed-test
  "The handler-level tripwire for the RENDER-TIME reactive
  sub-exception fail-closed contract through the REAL Ring handler order.

  ## What this pins

  A reactive sub that throws during `render-to-string`
  routes `:rf.error/sub-exception` through the ALWAYS-ON error-emit
  substrate so the SSR projection listener BUFFERS a fail-closed 500 onto
  pending-error-traces even under production hardening
  (`interop/debug-enabled? = false`). Reaching the wire through the
  reference Ring adapter takes one more step:

    1. `ssr-handler` reads `(ssr/flush-response-result! frame-id)` ONCE,
       BEFORE the render walk. The render has not run,
       so the buffer is empty and `:status` is the default 200.
    2. `build-full-response*` (`pipeline.clj`) runs the render walk inside
       `with-frame`. The reactive sub throws HERE. Under
       `debug-enabled? = false` the sub-run catch (`subs/memo.cljc`)
       routes the always-on `dispatch-on-error!` (which BUFFERS the 500)
       and then RETURNS nil — so `render-to-string` does NOT throw, and
       `build-full-response`'s outer try/catch never fires.
    3. `build-full-response*` therefore re-flushes the response
       accumulator AFTER the render walk (`ssr/flush-response-result!`),
       so the post-render-buffered 500 reaches the wire. Materialising the
       wire response from the STALE pre-render read (the 200 from step 1)
       would leave the buffered 500 in pending-error-traces until
       frame-destroy dropped it, and the wire would ship a SILENT 200 with
       the recovered-to-nil broken HTML — breaking the
       Spec 011 §View-time exceptions contract (\"fail-closed to a non-200 … never a
       silent 200 with the recovered-to-nil HTML\").

  This suite is the regression
  tripwire: it drives a render-time THROWING reactive sub through the real
  `ssr-handler` order and asserts the WIRE `:status` is 500, NOT a silent
  200.

  ## What the neighbouring tests do not cover

    - `ssr_sub_exception_two_frame_attribution_test`
      calls `subscribe-once` FIRST (running the sub body, buffering
      the 500) and `get-response` AFTER — the INVERSE of the handler order
      (sub-run-then-read vs the handler's read-then-render). It proves the
      core listener / accumulator / attribution layer, but not the Ring
      read-before-render wire path. (That suite carries an explicit note
      pointing here.)
    - `ring_test.clj` `handler-render-error-*` covers a root-VIEW that
      THROWS (the throwable propagates → the catch arm → projected 500).
      A reactive SUB-throw recovers-to-nil → never
      reaches that catch.
    - `ring_draintime_error_test` covers drain-time categories that fire
      BEFORE the pre-render read, so that single read sees them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

;; ===========================================================================
;; A root-view whose reactive sub THROWS during the render walk.
;; ===========================================================================

(defn- register-throwing-sub-view! []
  ;; The sub throws while computing its value. Under
  ;; `interop/debug-enabled? = false` the reactive sub-run catch
  ;; (subs/memo.cljc) routes the always-on `dispatch-on-error!`
  ;; (buffering the fail-closed 500) and RETURNS nil — so
  ;; `render-to-string` does NOT throw. This is precisely the
  ;; recover-to-nil case that bypasses build-full-response's render-time
  ;; catch arm.
  (rf/reg-sub :throwing-sub (fn [_db _] (throw (ex-info "sub-boom" {}))))
  ;; The root-view derefs the throwing sub during the render walk. The
  ;; deref returns nil (recovered), so the view renders without throwing —
  ;; the HTML is structurally "fine" but semantically broken (the sub's
  ;; intended content is missing). That is the exact "silent 200 with
  ;; recovered-to-nil broken HTML" the spec forbids.
  (rf/reg-view* :pages/uses-throwing-sub
    (fn []
      (let [v @(rf/subscribe [:throwing-sub])]
        [:main.broken
         [:h1 "header that renders"]
         [:p (str "value: " v)]]))))

;; ===========================================================================
;; Test 1 — render-time sub-throw fails closed to 500 on the wire (the
;;          tripwire). Without the post-render re-flush this would ship a
;;          silent 200; with it the buffered fail-closed 500 reaches the wire.
;; ===========================================================================

(deftest rendertime-sub-throw-fails-closed-to-500-on-the-wire
  (testing "a reactive subscription that THROWS during the
            render walk under production hardening
            (`interop/debug-enabled? = false`) must fail-closed to a 500
            on the wire — NOT a silent 200 with the recovered-to-nil
            broken HTML (Spec 011 §View-time exceptions). Driven through the REAL
            ssr-handler order (read-then-render), the order
            the two-frame attribution test inverts. Without the
            post-render re-flush this would ship 200 (the buffered 500 read
            BEFORE the render that buffers it, then dropped at
            frame-destroy)."
    (register-throwing-sub-view!)
    (rf/reg-event :init/ok {:platforms #{:server}} (fn [_ _] {}))
    (let [handler (rf.ssr.ring/ssr-handler
                    {:initial-events [[:init/ok]]
                     :root-view [(rf/view :pages/uses-throwing-sub)]
                     :ssr       {:public-error-id   :rf.ssr/default-error-projector
                                 :dev-error-detail? false}
                     :payload :rf.ssr.payload/whole-app-db})]
      ;; Production hardening — the dev-only trace listener elides; the
      ;; always-on error-emit substrate is the status source of truth.
      (with-redefs [rf.interop/debug-enabled? false]
        (testing "direct in-process handler call — render-time sub-throw
                  fails closed to 500 (the buffered fail-closed status,
                  re-flushed after the render walk), and
                  DIVERTS to the projected-error arm rather than
                  shipping the degraded body under a 500"
          (let [response (handler {:uri "/uses-throwing-sub" :request-method :get})
                body     (:body response)]
            (is (= 500 (:status response))
                "render-time sub-throw → buffered fail-closed 500 re-read
                 AFTER the render walk → ring :status 500. Without the
                 re-flush this would be a silent 200 (the stale
                 pre-render status materialised onto the wire).")
            (is (str/includes? body "Something went wrong")
                "the post-render 500 diverts to the projected-error
                 arm (locked default template), not the degraded HTML")
            (is (not (str/includes? body "header that renders"))
                "the degraded recovered-to-nil body is DISCARDED under the 500")
            (is (not (str/includes? body "__rf_payload"))
                "no hydration payload ships on the projected-error arm")))))))
