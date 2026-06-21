(ns re-frame.ssr.ring-rendertime-sub-failclosed-test
  "Per rf2-c0bq1 — the handler-level tripwire for the RENDER-TIME reactive
  sub-exception fail-closed contract through the REAL Ring handler order.

  ## The gap this closes (and the bug it pins)

  rf2-vvwmi made a reactive sub that throws during `render-to-string`
  route `:rf.error/sub-exception` through the ALWAYS-ON error-emit
  substrate so the SSR projection listener BUFFERS a fail-closed 500 onto
  pending-error-traces even under production hardening
  (`interop/debug-enabled? = false`). That fix is correct at the
  core/accumulator layer — but it did NOT reach the wire through the
  reference Ring adapter:

    1. `ssr-handler` reads `(ssr/get-response frame-id)` ONCE at
       `ring.clj:343` — BEFORE the render walk. The render has not run,
       so the buffer is empty and `:status` is the default 200.
    2. `build-full-response*` (`pipeline.clj`) runs the render walk inside
       `with-frame`. The reactive sub throws HERE. Under
       `debug-enabled? = false` the sub-run catch (`subs/memo.cljc`)
       routes the always-on `dispatch-on-error!` (which BUFFERS the 500)
       and then RETURNS nil — so `render-to-string` does NOT throw, and
       `build-full-response`'s outer try/catch never fires.
    3. Before rf2-c0bq1, `build-full-response*` materialised the wire
       response from the STALE pre-render `resp` (the 200 from step 1).
       The buffered 500 sat in pending-error-traces until frame-destroy
       dropped it. The wire shipped a SILENT 200 with the recovered-to-nil
       broken HTML — defeating rf2-vvwmi end-to-end and breaking the
       Spec 011 §744 / §750 contract (\"fail-closed to a non-200 … never a
       silent 200 with the recovered-to-nil HTML\").

  The rf2-c0bq1 fix re-flushes the response accumulator AFTER the render
  walk (`ssr/flush-response!` in `build-full-response*`), so the
  post-render-buffered 500 reaches the wire. This suite is the regression
  tripwire: it drives a render-time THROWING reactive sub through the real
  `ssr-handler` order and asserts the WIRE `:status` is 500, NOT a silent
  200.

  ## Why the existing tests missed it

    - `ssr_sub_exception_two_frame_attribution_test` (the rf2-vvwmi/vdk33
      proof) calls `subscribe-once` FIRST (running the sub body, buffering
      the 500) and `get-response` AFTER — the INVERSE of the handler order
      (sub-run-then-read vs the handler's read-then-render). It proves the
      core listener / accumulator / attribution layer, but never the Ring
      read-before-render wire path, so it gave false end-to-end
      confidence. (That suite now carries an explicit note pointing here.)
    - `ring_test.clj` `handler-render-error-*` covers a root-VIEW that
      THROWS (the throwable propagates → the catch arm → projected 500).
      That path works. A reactive SUB-throw recovers-to-nil → never
      reaches the catch — the untested gap.
    - `ring_draintime_error_test` covers drain-time categories that fire
      BEFORE `get-response`, so the single pre-render read sees them.

  TEST + SOURCE: the source fix lives in `pipeline.clj`
  (`build-full-response*` re-flush); this suite is its acceptance test.
  Mirrors `ring_draintime_error_test.clj`'s harness: direct in-process
  handler call AND bytes-on-the-wire through Jetty + `java.net.http`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.ring.test-support :as ts]))

(use-fixtures :each ts/reset-runtime)

;; ===========================================================================
;; Jetty + java.net.http harness
;; ===========================================================================
;;
;; rf2-l1qgjw — the ephemeral Jetty host (`ts/with-jetty`) + `java.net.http`
;; client / GET now live in `re-frame.ssr.ring.test-support` (aliased `ts`).
;; The 30s read timeout stays explicit at the call site via `ts/http-get`.

(def ^:private read-timeout-secs 30)

(defn- http-get
  "Issue a real HTTP GET and return `{:status :body}` observed on the
  wire (30s read-timeout pinned via the shared helper)."
  [client port path]
  (ts/http-get client port path read-timeout-secs))

;; ===========================================================================
;; A root-view whose reactive sub THROWS during the render walk.
;; ===========================================================================

(defn- register-throwing-sub-view! []
  ;; The sub throws while computing its value. Under
  ;; `interop/debug-enabled? = false` the reactive sub-run catch
  ;; (subs/memo.cljc) routes the always-on `dispatch-on-error!`
  ;; (buffering the fail-closed 500 per rf2-vvwmi) and RETURNS nil — so
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
;;          tripwire). Without the rf2-c0bq1 re-flush this ships a silent
;;          200; with it the buffered fail-closed 500 reaches the wire.
;; ===========================================================================

(deftest rendertime-sub-throw-fails-closed-to-500-on-the-wire
  (testing "rf2-c0bq1: a reactive subscription that THROWS during the
            render walk under production hardening
            (`interop/debug-enabled? = false`) must fail-closed to a 500
            on the wire — NOT a silent 200 with the recovered-to-nil
            broken HTML (Spec 011 §744 / §750). Driven through the REAL
            ssr-handler order (read-get-response-then-render), the order
            the rf2-vvwmi/vdk33 attribution test inverted. Before the
            rf2-c0bq1 re-flush this shipped 200 (the buffered 500 was read
            BEFORE the render that buffers it, then dropped at
            frame-destroy)."
    (register-throwing-sub-view!)
    (rf/reg-event :init/ok {:platforms #{:server}} (fn [_ _] {}))
    (let [handler (ssr-ring/ssr-handler
                    {:initial-events [[:init/ok]]
                     :root-view [:pages/uses-throwing-sub]
                     :ssr       {:public-error-id   :rf.ssr/default-error-projector
                                 :dev-error-detail? false}
                     :payload :rf.ssr.payload/whole-app-db})]
      ;; Production hardening — the dev-only trace listener elides; the
      ;; always-on error-emit substrate is the status source of truth.
      (with-redefs [interop/debug-enabled? false]
        (testing "direct in-process handler call — render-time sub-throw
                  fails closed to 500 (the buffered fail-closed status,
                  re-flushed after the render walk per rf2-c0bq1)"
          (let [response (handler {:uri "/uses-throwing-sub" :request-method :get})]
            (is (= 500 (:status response))
                "render-time sub-throw → buffered fail-closed 500 re-read
                 AFTER the render walk → ring :status 500. Before the
                 rf2-c0bq1 re-flush this was a silent 200 (the stale
                 pre-render status materialised onto the wire).")))

        (testing "bytes-on-the-wire through Jetty — the 500 survives the
                  full round-trip"
          (ts/with-jetty [port handler]
            (let [client (ts/new-http-client)
                  {:keys [status]} (http-get client port "/uses-throwing-sub")]
              (is (= 500 status)
                  "the render-time sub-throw fail-closed 500 rides the
                   full Jetty round-trip — never a silent 200"))))))))

;; ===========================================================================
;; Test 2 — the happy path is unaffected (last-write-wins / empty-buffer
;;          no-op). A clean reactive sub renders 200 through the same
;;          re-flushing path.
;; ===========================================================================

(deftest happy-path-clean-sub-stays-200-after-reflush
  (testing "rf2-c0bq1: the post-render re-flush is benign for the happy
            path — a render walk with NO buffered error leaves the buffer
            empty, the drain is a no-op, and the response keeps its
            default 200. Confirms the fix is last-write-wins, not a
            blanket status rewrite."
    (rf/reg-sub :clean-sub (fn [_db _] :ok))
    (rf/reg-view* :pages/uses-clean-sub
      (fn []
        (let [v @(rf/subscribe [:clean-sub])]
          [:main [:h1 "clean"] [:p (str "value: " v)]])))
    (rf/reg-event :init/ok {:platforms #{:server}} (fn [_ _] {}))
    (let [handler (ssr-ring/ssr-handler
                    {:initial-events [[:init/ok]]
                     :root-view [:pages/uses-clean-sub]
                     :ssr       {:public-error-id   :rf.ssr/default-error-projector
                                 :dev-error-detail? false}
                     :payload :rf.ssr.payload/whole-app-db})]
      (with-redefs [interop/debug-enabled? false]
        (let [response (handler {:uri "/uses-clean-sub" :request-method :get})]
          (is (= 200 (:status response))
              "clean render → empty error buffer → re-flush no-op → 200")
          (is (str/includes? (:body response) "value: :ok")
              "the clean sub's value rendered into the wire HTML"))))))
