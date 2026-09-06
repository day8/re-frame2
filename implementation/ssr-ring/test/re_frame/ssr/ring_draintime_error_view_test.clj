(ns re-frame.ssr.ring-draintime-error-view-test
  "rf2-oytx7j — the projected-error PRE-COMMIT contract at the ssr-ring
  handler boundary. Classification is SEMANTIC by projected status:

    - projected 4xx (routing miss / bad client input) with a renderable app
      → the app root body + hydration payload; `:error-view` NOT called (the
      app renders its own not-found / bad-request UI, hydrates into a working
      SPA);
    - projected 5xx before the body commits (a drain-time handler/fx
      exception, a custom 5xx projection, a post-render recovered-to-nil sub)
      → the projected error page (`:error-view`, or the locked default
      template); NO root-view / hydration payload / app-db;
    - an unrenderable root/shell throw → the error page regardless of the
      projected status (it cannot supply a body) — covered by `ring_test`
      (the 418 teapot) and `ring_streaming_test` (shell-walk throw);
    - a manually-set status 500 with NO projection stays on the app arm
      (status alone is not proof that an error was projected);
    - post-commit streaming failure → telemetry + truncation only (covered by
      the streaming writer-trace suite).

  Containment is ONE-WAY: a failing `:error-view` (a throw, OR a reactive sub
  that recovered to nil inside it) falls back ONCE to the locked template,
  emits `:rf.error/ssr-ring-error-view-failed`, and does NOT re-project.

  Runtime-side pins (`flush-response-result!`, `:rf/public-error` validation
  tightening) live in `re-frame.ssr-flush-response-result-test`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support])
  (:import [java.io ByteArrayOutputStream InputStream]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- body->str
  "Read a Ring response body to a string — a plain String (the error arm and
  redirects) or a drained InputStream (the streaming happy path)."
  [body]
  (cond
    (string? body) body
    (instance? InputStream body)
    (let [^InputStream is body
          out (ByteArrayOutputStream.)
          buf (byte-array 8192)]
      (loop []
        (let [n (.read is buf)]
          (when (pos? n) (.write out buf 0 n) (recur))))
      (.close is)
      (String. (.toByteArray out) "UTF-8"))
    :else (str body)))

(defn- capture-always-on!
  "Register an always-on error-emit listener recording every `:error` slot,
  the production off-box shipper stand-in. Returns the seen-categories atom."
  []
  (let [seen (atom [])]
    (rf/register-listener! :errors ::draintime-error-view-recorder
      (fn [record] (swap! seen conj (:error record))))
    seen))

(defn- reg-safe-header+cookie-event! []
  ;; A prior initial-event that accumulates a safe header + cookie BEFORE the
  ;; drain fails, so we can prove the projected-error arm preserves them.
  (rf/reg-event :init/set-safe
    {:platforms #{:server}}
    (fn [_ _]
      {:fx [[:rf.server/set-header {:name "X-Safe" :value "safe-value"}]
            [:rf.server/set-cookie {:name "sid" :value "keep-me"}]]})))

(defn- reg-drain-boom-event! []
  ;; A drain-time FX exception → :rf.error/fx-handler-exception → default
  ;; projector 500. A throwing FX is CAUGHT by the fx executor, buffered, and
  ;; projected (the proven ring_e2e_validator CRLF path) — unlike a throwing
  ;; EVENT handler, which propagates out of the synchronous drain to
  ;; setup-request-frame!'s catch (the :on-error transport net), never
  ;; reaching the projector.
  (rf/reg-fx :test/boom-fx
    {:platforms #{:server}}
    (fn [_ _] (throw (ex-info "drain-boom-internal-detail" {}))))
  (rf/reg-event :init/boom
    {:platforms #{:server}}
    (fn [_ _] {:fx [[:test/boom-fx nil]]})))

(def ^:private root-calls (atom 0))
(def ^:private error-view-calls (atom 0))

(defn- reg-counting-root! []
  (reset! root-calls 0)
  (rf/reg-view* :pages/counting-root
    (fn [] (swap! root-calls inc)
      [:main.the-real-root "ROOT-RENDERED-MARKER"])))

(defn- header-values [response]
  (set (vals (:headers response))))

;; ===========================================================================
;; Non-streaming — drain-time projected 5xx → error arm
;; ===========================================================================

(deftest draintime-500-invokes-error-view-once-never-root
  (testing "a drain-time handler exception (500) invokes the custom error
            view ONCE, never invokes the root, ships NO hydration payload or
            internal exception text, and preserves pre-failure safe
            headers/cookies."
    (reg-safe-header+cookie-event!)
    (reg-drain-boom-event!)
    (reg-counting-root!)
    (reset! error-view-calls 0)
    (let [handler  (rf.ssr.ring/ssr-handler
                     {:initial-events [[:init/set-safe] [:init/boom]]
                      :root-view  [(rf/view :pages/counting-root)]
                      :error-view (fn [{:keys [status message]}]
                                    (swap! error-view-calls inc)
                                    [:div.branded-error
                                     [:h1 "BRANDED-ERROR-MARKER"]
                                     [:p (str status)]
                                     [:span message]])
                      :payload    :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/boom" :request-method :get})
          body     (body->str (:body response))]
      (is (= 500 (:status response)) "drain-time 500 rides the wire")
      (is (= 1 @error-view-calls) "the error view rendered exactly once")
      (is (zero? @root-calls) "the root view was NEVER invoked")
      (is (str/includes? body "BRANDED-ERROR-MARKER")
          "the branded error page is the body")
      (is (not (str/includes? body "ROOT-RENDERED-MARKER"))
          "the half-drained root body was discarded")
      (is (not (str/includes? body "__rf_payload"))
          "NO hydration payload ships on the error arm")
      (is (not (str/includes? body "drain-boom-internal-detail"))
          "the internal exception text never crosses the wire")
      (is (contains? (header-values response) "safe-value")
          "the pre-failure X-Safe header survives onto the error response")
      (is (some #(str/includes? (str %) "sid=keep-me") (vals (:headers response)))
          "the pre-failure Set-Cookie survives onto the error response"))))

(deftest draintime-500-no-error-view-uses-default-template-not-root
  (testing "the same drain-time 500 with NO custom view uses the locked
            default template (\"Something went wrong\"), never the root, and
            ships no payload."
    (reg-drain-boom-event!)
    (reg-counting-root!)
    (let [handler  (rf.ssr.ring/ssr-handler
                     {:initial-events [[:init/boom]]
                      :root-view [(rf/view :pages/counting-root)]
                      :payload   :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/boom" :request-method :get})
          body     (body->str (:body response))]
      (is (= 500 (:status response)))
      (is (zero? @root-calls) "the root view was NEVER invoked")
      (is (str/includes? body "Something went wrong")
          "the locked default error template rendered")
      (is (not (str/includes? body "ROOT-RENDERED-MARKER")))
      (is (not (str/includes? body "__rf_payload"))
          "no hydration payload on the default error template arm"))))

(deftest draintime-4xx-keeps-root-and-payload-never-error-view
  (testing "a projected 404 (route miss) keeps the app root + hydration
            payload and NEVER invokes the error view — the app's own
            not-found UI renders and hydrates into a working SPA (the 4xx
            app arm; explicit, not incidental)."
    (rf/reg-route :route/home {} "/")
    (rf/reg-event :init/route-to-missing
      {:platforms #{:server}}
      (fn [_ _] {:fx [[:dispatch [:rf.route/handle-url-change "/no-such-page"]]]}))
    (rf/reg-view* :pages/not-found
      (fn [] [:div.not-found "APP-NOT-FOUND-UI"]))
    (reset! error-view-calls 0)
    (let [handler  (rf.ssr.ring/ssr-handler
                     {:initial-events [[:init/route-to-missing]]
                      :root-view  [(rf/view :pages/not-found)]
                      :error-view (fn [_] (swap! error-view-calls inc)
                                    [:div "SHOULD-NOT-APPEAR"])
                      :payload    :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/no-such-page" :request-method :get})
          body     (body->str (:body response))]
      (is (= 404 (:status response)) "the projected 404 rides the wire")
      (is (zero? @error-view-calls)
          "a 4xx does NOT route through :error-view (client-fault arm)")
      (is (str/includes? body "APP-NOT-FOUND-UI")
          "the app's own not-found root renders under the 404")
      (is (not (str/includes? body "SHOULD-NOT-APPEAR")))
      (is (str/includes? body "__rf_payload")
          "the 4xx app arm ships the hydration payload — a working SPA"))))

(deftest custom-503-error-arm-vs-appwritten-500-app-arm
  (testing "a custom projected 503 takes the error arm, while an
            app-written status 500 with NO projection stays on the app arm."
    (testing "custom 503 → error arm"
      (rf/reg-error-projector :myapp/degraded
        (fn [_] {:status 503 :code :unavailable
                 :message "SERVICE-DEGRADED-MARKER" :retryable? true}))
      (reg-drain-boom-event!)
      (reg-counting-root!)
      (let [handler  (rf.ssr.ring/ssr-handler
                       {:initial-events [[:init/boom]]
                        :root-view [(rf/view :pages/counting-root)]
                        :ssr       {:public-error-id :myapp/degraded}
                        :payload   :rf.ssr.payload/whole-app-db})
            response (handler {:uri "/boom" :request-method :get})
            body     (body->str (:body response))]
        (is (= 503 (:status response)) "the custom 503 rides the wire")
        (is (zero? @root-calls) "the root was not invoked on the 5xx error arm")
        (is (str/includes? body "SERVICE-DEGRADED-MARKER")
            "the projected 503 error template rendered")
        (is (not (str/includes? body "__rf_payload")))))
    (testing "app-written 500 with NO projection → app arm"
      (rf/reg-event :init/set-500
        {:platforms #{:server}}
        (fn [_ _] {:fx [[:rf.server/set-status 500]]}))
      (reg-counting-root!)
      (let [handler  (rf.ssr.ring/ssr-handler
                       {:initial-events [[:init/set-500]]
                        :root-view [(rf/view :pages/counting-root)]
                        :payload   :rf.ssr.payload/whole-app-db})
            response (handler {:uri "/manual-500" :request-method :get})
            body     (body->str (:body response))]
        (is (= 500 (:status response)) "the app's manual 500 rides the wire")
        (is (= 1 @root-calls)
            "no projection fired → the app arm renders the root (status alone
             is not proof of a projected error)")
        (is (str/includes? body "ROOT-RENDERED-MARKER")
            "the app body renders under its own 500")
        (is (str/includes? body "__rf_payload")
            "the app arm ships the hydration payload")))))

(deftest error-view-fn-and-keyword-run-under-request-frame
  (testing "both the fn- and keyword-form error views run under the request
            frame — each derefs a working reactive sub and sees its value."
    (rf/reg-sub :err/detail (fn [_db _] "SUB-VALUE-UNDER-FRAME"))
    (reg-drain-boom-event!)
    (testing "keyword form"
      (rf/reg-view* :myapp/kw-error
        (fn [_public] [:div.kw-error @(rf/subscribe [:err/detail])]))
      (let [handler  (rf.ssr.ring/ssr-handler
                       {:initial-events [[:init/boom]]
                        :root-view  [:div "root"]
                        :error-view :myapp/kw-error
                        :payload    :rf.ssr.payload/whole-app-db})
            response (handler {:uri "/boom" :request-method :get})]
        (is (= 500 (:status response)))
        (is (str/includes? (body->str (:body response)) "SUB-VALUE-UNDER-FRAME")
            "keyword error view resolved its sub under the request frame")))
    (testing "fn form"
      (let [handler  (rf.ssr.ring/ssr-handler
                       {:initial-events [[:init/boom]]
                        :root-view  [:div "root"]
                        :error-view (fn [_public]
                                      [:div.fn-error @(rf/subscribe [:err/detail])])
                        :payload    :rf.ssr.payload/whole-app-db})
            response (handler {:uri "/boom" :request-method :get})]
        (is (= 500 (:status response)))
        (is (str/includes? (body->str (:body response)) "SUB-VALUE-UNDER-FRAME")
            "fn error view resolved its sub under the request frame")))))

;; ===========================================================================
;; Non-streaming — error-view containment (one-way, no re-projection)
;; ===========================================================================

(deftest error-view-throw-falls-back-once-no-reproject
  (testing "a throwing error view on the 500 error arm falls back ONCE to the
            locked template, keeps the ORIGINAL 500, emits
            :rf.error/ssr-ring-error-view-failed, and never re-projects —
            under debug ON and OFF. The error arm is reached via a
            debug-independent render-time root throw (the projector runs at
            the render seam regardless of the trace surface)."
    (doseq [debug? [true false]]
      (rf/reg-event :init/ok {:platforms #{:server}} (fn [_ _] {}))
      (rf/reg-view* :pages/render-throws-ev
        (fn [] (throw (ex-info "root-throw-detail" {}))))
      (rf.error-emit/clear-error-listeners!)
      (let [calls (atom 0)
            seen  (capture-always-on!)
            traces (atom [])]
        (rf/register-listener! :trace ::ev-throw
          (fn [ev] (when (= :rf.error/ssr-ring-error-view-failed (:operation ev))
                     (swap! traces conj ev))))
        (with-redefs [rf.interop/debug-enabled? debug?]
          (let [handler  (rf.ssr.ring/ssr-handler
                           {:initial-events [[:init/ok]]
                            :root-view  [(rf/view :pages/render-throws-ev)]
                            :error-view (fn [_public]
                                          (swap! calls inc)
                                          (throw (ex-info "error-view-broke-detail" {})))
                            :payload    :rf.ssr.payload/whole-app-db})
                response (handler {:uri "/boom" :request-method :get})
                body     (body->str (:body response))]
            (is (= 500 (:status response))
                (str "boundary holds at the ORIGINAL 500 (debug=" debug? ")"))
            (is (= 1 @calls) "the buggy error view was invoked exactly once")
            (is (str/includes? body "Something went wrong")
                "fell back to the locked default template")
            (is (not (str/includes? body "error-view-broke-detail"))
                "the error-view throwable never reaches the wire")
            ;; The category reaches the observability axes: dev :trace under
            ;; debug-on, always-on :errors under debug-off.
            (is (or (seq @traces)
                    (some #{:rf.error/ssr-ring-error-view-failed} @seen))
                ":rf.error/ssr-ring-error-view-failed emitted for containment")))
        (rf/unregister-listener! :trace ::ev-throw)
        (rf.error-emit/clear-error-listeners!)))))

(deftest error-view-recovered-nil-sub-falls-back-to-template
  (testing "OPEN PROOF (rf2-oytx7j): an error view whose reactive sub RECOVERS
            to nil under production hardening does NOT throw — the try/catch
            cannot see it — but it falls back ONCE to the locked template,
            keeps the ORIGINAL 500, emits :rf.error/ssr-ring-error-view-failed,
            and does NOT re-project. The error arm is reached via a
            render-time root throw (debug-independent)."
    (rf/reg-event :init/ok {:platforms #{:server}} (fn [_ _] {}))
    (rf/reg-view* :pages/render-throws-rn
      (fn [] (throw (ex-info "root-throw-detail" {}))))
    (rf/reg-sub :err/throwing (fn [_db _] (throw (ex-info "sub-in-error-view-boom" {}))))
    (rf/reg-view* :myapp/sub-error-view
      (fn [_public]
        ;; The sub recovers to nil under debug-off; the view renders a
        ;; "degraded" body that MUST NOT ship as the error page.
        [:div.degraded-error-view
         [:span "DEGRADED-ERROR-VIEW-MARKER"]
         [:span (str "sub: " @(rf/subscribe [:err/throwing]))]]))
    ;; Do NOT clear error listeners here: the recovered-to-nil sub relies on
    ;; the SSR always-on `::error-projection` buffering listener (installed by
    ;; the reset fixture) to BUFFER its fail-closed trace during the error-view
    ;; render — that buffered trace is exactly what `pending-error-trace?`
    ;; detects. `capture-always-on!` adds a recorder ALONGSIDE it.
    (let [seen (capture-always-on!)]
      (with-redefs [rf.interop/debug-enabled? false]
        (let [handler  (rf.ssr.ring/ssr-handler
                         {:initial-events [[:init/ok]]
                          :root-view  [(rf/view :pages/render-throws-rn)]
                          :error-view :myapp/sub-error-view
                          :ssr        {:public-error-id   :rf.ssr/default-error-projector
                                       :dev-error-detail? false}
                          :payload    :rf.ssr.payload/whole-app-db})
              response (handler {:uri "/boom" :request-method :get})
              body     (body->str (:body response))]
          (is (= 500 (:status response))
              "the ORIGINAL projected 500 is preserved — the recovered-to-nil
               sub inside the error view did NOT re-project (no 4xx→5xx or
               status flip)")
          (is (str/includes? body "Something went wrong")
              "fell back ONCE to the locked default template")
          (is (not (str/includes? body "DEGRADED-ERROR-VIEW-MARKER"))
              "the degraded error-view body was DISCARDED — a reactive sub
               cannot bypass the boundary any more than a throw can")
          (is (= 1 (count (filter #{:rf.error/ssr-ring-error-view-failed} @seen)))
              "exactly one :rf.error/ssr-ring-error-view-failed record (the
               fallback fired once, no re-projection loop)")))
      (rf.error-emit/clear-error-listeners!))))

(deftest redirect-beats-pending-projection
  (testing "a redirect set during the drain beats a pending projection — the
            handler ships a bodiless redirect, never the error page."
    (rf/reg-event :init/redirect-then-boom
      {:platforms #{:server}}
      (fn [_ _]
        {:fx [[:rf.server/redirect {:location "/login"}]
              [:dispatch [:init/boom]]]}))
    (reg-drain-boom-event!)
    (reg-counting-root!)
    (let [handler  (rf.ssr.ring/ssr-handler
                     {:initial-events [[:init/redirect-then-boom]]
                      :root-view  [(rf/view :pages/counting-root)]
                      :error-view (fn [_] [:div "SHOULD-NOT-APPEAR"])
                      :payload    :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/gated" :request-method :get})
          body     (body->str (:body response))]
      (is (= 302 (:status response)) "the redirect status wins over the 500")
      (is (= "/login" (get-in response [:headers "Location"]))
          "the Location header rides the redirect")
      (is (= "" body) "a redirect is bodiless — no error page, no root")
      (is (zero? @root-calls) "no root render on a redirect")
      (is (not (str/includes? body "SHOULD-NOT-APPEAR"))))))

(deftest post-render-recovered-sub-500-takes-error-arm
  (testing "a root-view whose reactive sub recovers-to-nil buffers a
            fail-closed 500 AFTER the render walk — the non-streaming handler
            DISCARDS the degraded body + payload and ships the projected-error
            arm (rf2-oytx7j supersedes the ship-the-degraded-body behaviour)."
    (rf/reg-sub :render/throwing (fn [_db _] (throw (ex-info "render-sub-boom" {}))))
    (rf/reg-view* :pages/uses-throwing-render-sub
      (fn []
        [:main.broken
         [:h1 "DEGRADED-ROOT-MARKER"]
         [:p (str "v: " @(rf/subscribe [:render/throwing]))]]))
    (rf/reg-event :init/ok {:platforms #{:server}} (fn [_ _] {}))
    (with-redefs [rf.interop/debug-enabled? false]
      (let [handler  (rf.ssr.ring/ssr-handler
                       {:initial-events [[:init/ok]]
                        :root-view  [(rf/view :pages/uses-throwing-render-sub)]
                        :ssr        {:public-error-id   :rf.ssr/default-error-projector
                                     :dev-error-detail? false}
                        :payload    :rf.ssr.payload/whole-app-db})
            response (handler {:uri "/render-sub" :request-method :get})
            body     (body->str (:body response))]
        (is (= 500 (:status response)) "fail-closed 500 on the wire")
        (is (str/includes? body "Something went wrong")
            "the projected-error arm rendered (locked default template)")
        (is (not (str/includes? body "DEGRADED-ROOT-MARKER"))
            "the degraded root body was DISCARDED")
        (is (not (str/includes? body "__rf_payload"))
            "no hydration payload ships under the post-render 500")))))

;; ===========================================================================
;; Streaming — projected 5xx → non-streamed error arm (no writer thread)
;; ===========================================================================

(deftest stream-draintime-500-non-streamed-error-arm
  (testing "a drain-time 500 through stream-handler ships a NON-STREAMED
            projected-error response (a plain String, not an InputStream) with
            NO writer thread and NO hydration payload."
    (reg-drain-boom-event!)
    (reg-counting-root!)
    (let [handler  (rf.ssr.ring/stream-handler
                     {:initial-events [[:init/boom]]
                      :root-view  [(rf/view :pages/counting-root)]
                      :error-view (fn [{:keys [message]}]
                                    [:div.branded-stream-error
                                     "STREAM-BRANDED-ERROR" [:span message]])
                      :payload    :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/boom" :request-method :get})
          body     (body->str (:body response))]
      (is (= 500 (:status response)))
      (is (not (instance? InputStream (:body response)))
          "the drain-time 5xx fails closed to a plain-String body — no
           chunked InputStream, no writer thread spawned")
      (is (zero? @root-calls) "the shell/root was never rendered")
      (is (str/includes? body "STREAM-BRANDED-ERROR")
          "the branded error page is the body")
      (is (not (str/includes? body "__rf_payload"))
          "no hydration payload on the streaming error arm"))))

(deftest stream-post-shell-recovered-sub-500-non-streamed-error-arm
  (testing "a recovered-to-nil sub during the streaming shell render buffers a
            fail-closed 500; the post-shell re-read diverts to the
            NON-STREAMED error arm — NOT the degraded shell under a 500
            (rf2-oytx7j supersedes the old stream-the-degraded-shell pin)."
    (rf/reg-sub :shell/throwing (fn [_db _] (throw (ex-info "shell-sub-boom" {}))))
    (rf/reg-view* :pages/shell-uses-throwing-sub
      (fn []
        [:main.broken
         [:h1 "DEGRADED-SHELL-MARKER"]
         [:p (str "v: " @(rf/subscribe [:shell/throwing]))]]))
    (rf/reg-event :init/ok {:platforms #{:server}} (fn [_ _] {}))
    (with-redefs [rf.interop/debug-enabled? false]
      (let [handler  (rf.ssr.ring/stream-handler
                       {:initial-events [[:init/ok]]
                        :root-view [(rf/view :pages/shell-uses-throwing-sub)]
                        :ssr       {:public-error-id   :rf.ssr/default-error-projector
                                    :dev-error-detail? false}
                        :payload   :rf.ssr.payload/whole-app-db})
            response (handler {:uri "/shell-sub" :request-method :get})
            body     (body->str (:body response))]
        (is (= 500 (:status response)) "fail-closed 500 on the committed head")
        (is (not (instance? InputStream (:body response)))
            "the recovered-to-nil 5xx fails closed to a plain-String error
             body — no writer thread, no partial-state shell")
        (is (str/includes? body "Something went wrong")
            "the projected-error arm rendered")
        (is (not (str/includes? body "DEGRADED-SHELL-MARKER"))
            "the degraded shell was DISCARDED, not streamed under a 500")
        (is (not (str/includes? body "__rf_payload")))))))
