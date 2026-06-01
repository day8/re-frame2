(ns re-frame.ssr.ring-test
  "End-to-end tests for the Ring host adapter (rf2-ny6v7).

  Mirrors the shape of implementation/ssr/test/re_frame/ssr_end_to_end_test.clj
  — synthesises a Ring request, invokes the handler, asserts on the
  Ring response, then on the frame-lifecycle side-effects (destroyed?,
  trace events).

  Each test resets the runtime exactly the way the ssr artefact's
  test fixture does (registrar/clear-all! → reload ns)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.test-fixture :as tf]))

;; rf2-i3qc0 — the canonical reset-runtime fixture lives in
;; `re-frame.ssr.test-fixture` (loadable here via the ssr artefact's
;; test path; see this artefact's deps.edn :test alias `:extra-paths`).
(use-fixtures :each tf/reset-runtime)

;; ===========================================================================
;; Cookie serialisation (RFC 6265)
;; ===========================================================================

(deftest cookie-set-cookie-header-canonical
  (testing "minimal cookie: name + value only"
    (is (= "session=abc123"
           (ssr-ring/cookie->set-cookie-header
             {:name "session" :value "abc123"}))))

  (testing "value gets URL-encoded"
    (is (= "session=a%20b%26c"
           (ssr-ring/cookie->set-cookie-header
             {:name "session" :value "a b&c"}))))

  (testing "full attribute set in canonical order"
    (let [s (ssr-ring/cookie->set-cookie-header
              {:name      "session"
               :value     "abc"
               :max-age   3600
               :secure    true
               :http-only true
               :same-site :lax
               :path      "/"
               :domain    "example.com"})]
      (is (str/starts-with? s "session=abc"))
      (is (str/includes? s "Max-Age=3600"))
      (is (str/includes? s "Domain=example.com"))
      (is (str/includes? s "Path=/"))
      (is (str/includes? s "Secure"))
      (is (str/includes? s "HttpOnly"))
      (is (str/includes? s "SameSite=Lax"))))

  (testing "same-site tokens map to canonical strings"
    (is (str/includes? (ssr-ring/cookie->set-cookie-header
                         {:name "s" :value "" :same-site :strict})
                       "SameSite=Strict"))
    (is (str/includes? (ssr-ring/cookie->set-cookie-header
                         {:name "s" :value "" :same-site :none})
                       "SameSite=None")))

  (testing "missing :name raises a structured error"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ssr-ring/cookie->set-cookie-header {:value "abc"})))))

;; ===========================================================================
;; rf2-rpedl — CRLF injection in Set-Cookie attributes + cookie-name grammar
;; (security audit 2026-05-14 §P1.2 + §P2.4)
;;
;; Every attribute string that gets concatenated into the wire shape is
;; checked for CR/LF/NUL before emission — the framework rejects rather
;; than relying on host-side defence (Jetty 11+ defends, earlier Jetty
;; / HttpKit / Pedestal vary). Cookie :name is gated against the
;; RFC 6265 §4.1.1 token grammar (no CTLs, whitespace, separators).
;; ===========================================================================

(deftest cookie-attribute-crlf-injection-rejected
  (testing "rf2-rpedl §P1.2 — CR / LF / NUL in :domain throws
            :rf.error/cookie-invalid-attribute"
    (doseq [hostile [(str "evil.com\r\nSet-Cookie: admin=1; Path=/")
                     "evil.com\rinjected"
                     "evil.com\ninjected"
                     (str "evil.com" (char 0) "nul")]]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/cookie-invalid-attribute"
            (ssr-ring/cookie->set-cookie-header
              {:name "session" :value "abc" :domain hostile}))
          (str "hostile :domain " (pr-str hostile) " must be rejected"))))

  (testing "rf2-rpedl §P1.2 — CR / LF / NUL in :path throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/cookie-invalid-attribute"
          (ssr-ring/cookie->set-cookie-header
            {:name "session" :value "abc" :path "/\r\nSet-Cookie: x=1"}))))

  (testing "rf2-rpedl §P1.2 — CR / LF / NUL in :max-age (string-shaped) throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/cookie-invalid-attribute"
          (ssr-ring/cookie->set-cookie-header
            {:name "session" :value "abc" :max-age "3600\r\nbad"}))))

  (testing "rf2-rpedl §P1.2 — CR / LF / NUL in :same-site (string-shaped) throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/cookie-invalid-attribute"
          (ssr-ring/cookie->set-cookie-header
            {:name "session" :value "abc" :same-site "Lax\r\nbad"}))))

  (testing "rf2-rpedl §P1.2 — clean attributes still serialise (regression
            guard: validation doesn't reject valid input)"
    (let [s (ssr-ring/cookie->set-cookie-header
              {:name      "session"
               :value     "abc"
               :domain    "example.com"
               :path      "/articles"
               :max-age   3600
               :same-site :lax})]
      (is (str/includes? s "Domain=example.com"))
      (is (str/includes? s "Path=/articles")))))

(deftest cookie-name-rfc6265-token-grammar
  (testing "rf2-rpedl §P2.4 — cookie :name violating the RFC 6265 §4.1.1
            token grammar throws :rf.error/cookie-invalid-name"
    (testing "name with whitespace throws"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/cookie-invalid-name"
            (ssr-ring/cookie->set-cookie-header
              {:name "session id" :value "abc"}))))

    (testing "name with separator chars throws"
      (doseq [bad ["session;extra" "session=x" "session,x" "session/x"
                   "session\"x" "session(x" "session)x"]]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #":rf\.error/cookie-invalid-name"
              (ssr-ring/cookie->set-cookie-header
                {:name bad :value "abc"}))
            (str "separator-bearing name " (pr-str bad) " must be rejected"))))

    (testing "name with CR / LF / NUL throws"
      (doseq [bad ["session\r" "session\n" (str "session" (char 0))]]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #":rf\.error/cookie-invalid-name"
              (ssr-ring/cookie->set-cookie-header
                {:name bad :value "abc"}))
            (str "control-char name " (pr-str bad) " must be rejected"))))

    (testing "valid token-grammar names pass through"
      (doseq [ok ["session" "_csrf" "X-CSRF-TOKEN" "data-1.2.3" "a"]]
        (is (str/starts-with?
              (ssr-ring/cookie->set-cookie-header {:name ok :value "v"})
              (str ok "="))
            (str "valid name " (pr-str ok) " should serialise"))))))

;; ===========================================================================
;; ssr-handler — happy path (Ring-level smoke)
;; ===========================================================================

(defn- register-articles-app! [articles]
  (rf/reg-fx :http/get
    {:platforms #{:server :client}}
    (fn [_ _] nil))

  (rf/reg-fx :http/get.canned
    {:platforms #{:server :client}}
    (fn [{:keys [frame]} {:keys [on-success]}]
      (when on-success
        (rf/dispatch (conj on-success articles) {:frame frame}))))

  (rf/reg-event-fx :rf/server-init
    {:platforms #{:server}}
    [(rf/inject-cofx :rf.server/request)]
    (fn [{:keys [db rf.server/request]} _]
      {:db (assoc db :request request)
       :fx [[:http/get {:url        "/api/articles"
                        :on-success [:articles/loaded]}]]}))

  (rf/reg-event-db :articles/loaded
    (fn [db [_ articles]]
      (assoc db :articles articles)))

  (rf/reg-sub :articles (fn [db _] (:articles db)))

  (rf/reg-view* :pages/articles
    (fn []
      (let [arts (rf/subscribe-once [:articles])]
        [:div.page
         [:h1 "Articles"]
         (into [:ul]
               (for [{:keys [id title]} arts]
                 ^{:key id} [:li title]))]))))

(deftest handler-renders-html-with-status-and-headers
  (testing "handler emits 200, content-type, and HTML body carrying view content"
    (register-articles-app! [{:id "a" :title "Article A"}
                             {:id "b" :title "Article B"}])

    (let [handler (ssr-ring/ssr-handler
                    {:on-create    [:rf/server-init]
                     :root-view    [:pages/articles]
                     :fx-overrides {:http/get :http/get.canned}
                     :payload :rf.ssr.payload/whole-app-db})
          request {:uri            "/articles"
                   :request-method :get
                   :headers        {"user-agent" "test"}}
          response (handler request)]

      (is (= 200 (:status response))
          "default response status is 200")
      (let [headers (:headers response)
            ct      (or (get headers "content-type")
                        (get headers "Content-Type"))]
        (is (some? ct))
        (is (str/includes? ct "text/html"))
        (is (str/includes? ct "utf-8")))
      (let [body (:body response)]
        (is (string? body))
        (is (str/includes? body "<!DOCTYPE html>"))
        (is (str/includes? body "Article A"))
        (is (str/includes? body "Article B"))
        (is (str/includes? body "<script id=\"__rf_payload\""))
        (is (str/includes? body "data-rf-render-hash")
            "root element carries the structural hash for hydration-mismatch check")))))

;; ===========================================================================
;; ssr-handler — frame lifecycle (create + destroy)
;; ===========================================================================

(deftest handler-creates-and-destroys-per-request-frame
  (testing "every request creates a fresh frame and destroys it before returning"
    (register-articles-app! [{:id "x" :title "Article X"}])
    (let [destroyed (atom [])
          _ (rf/register-listener! ::destroy-watch
              (fn [ev]
                (when (= :rf.frame/destroyed (:operation ev))
                  (swap! destroyed conj (:frame ev)))))
          handler (ssr-ring/ssr-handler
                    {:on-create    [:rf/server-init]
                     :root-view    [:pages/articles]
                     :fx-overrides {:http/get :http/get.canned}
                     :payload :rf.ssr.payload/whole-app-db})
          frames-before (set (rf/frame-ids))
          response (handler {:uri "/" :request-method :get})
          frames-after (set (rf/frame-ids))]
      (rf/unregister-listener! ::destroy-watch)
      (is (= 200 (:status response)))
      ;; No per-request frame leaks into the global frame registry.
      ;; Some destroy-trace-side-channel frame-ids must have been
      ;; recorded — i.e. the handler did create + destroy at least one
      ;; per-request frame.
      (is (= frames-before frames-after)
          "the per-request frame is removed from the registry after destroy")
      (is (seq @destroyed)
          ":rf.frame/destroyed trace fired for the per-request frame"))))

;; ===========================================================================
;; ssr-handler — redirect short-circuit
;; ===========================================================================

(deftest handler-redirect-short-circuits
  (testing ":rf.server/redirect emits status + Location with empty body"
    (rf/reg-event-fx :init/redirect
      {:platforms #{:server}}
      (fn [_ _]
        {:fx [[:rf.server/redirect {:status 302 :location "/login"}]]}))

    (rf/reg-view* :pages/should-not-render
      (fn [] [:div "should not render under redirect"]))

    (let [handler (ssr-ring/ssr-handler
                    {:on-create [:init/redirect]
                     :root-view [:pages/should-not-render]
                     :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/secret" :request-method :get})]
      (is (= 302 (:status response)))
      (let [headers (:headers response)
            loc     (or (get headers "Location") (get headers "location"))]
        (is (= "/login" loc)))
      (is (= "" (:body response))
          "redirect short-circuits — no body, no payload script")
      (is (not (str/includes? (:body response) "should not render"))))))

(deftest handler-redirect-no-target-warns
  (testing "rf2-ee38b.11: a :rf.server/redirect with no :location/:url/:to
            produces a 3xx with no Location header (malformed redirect —
            the runtime accepts a target-less redirect since location is
            caller-trusted/optional at the fx boundary). The adapter is
            the last line: it emits :rf.ssr/ssr-redirect-no-target so the
            defect is observable rather than silently shipping a broken
            redirect."
    (rf/reg-event-fx :init/redirect-no-target
      {:platforms #{:server}}
      (fn [_ _]
        {:fx [[:rf.server/redirect {:status 302}]]}))
    (rf/reg-view* :pages/noop-rt (fn [] [:div]))
    (let [traces (atom [])]
      (rf/register-listener! ::redirect-no-target-watch
        (fn [ev] (when (= :rf.ssr/ssr-redirect-no-target (:operation ev))
                   (swap! traces conj ev))))
      (let [handler  (ssr-ring/ssr-handler
                       {:on-create [:init/redirect-no-target]
                        :root-view [:pages/noop-rt]
                        :payload :rf.ssr.payload/whole-app-db})
            response (handler {:uri "/secret" :request-method :get})
            headers  (:headers response)]
        (rf/unregister-listener! ::redirect-no-target-watch)
        (is (= 302 (:status response)) "still emits the redirect status")
        (is (nil? (or (get headers "Location") (get headers "location")))
            "no Location header — there is no target to write")
        (is (seq @traces)
            ":rf.ssr/ssr-redirect-no-target warning fired so the malformed
             redirect is observable")))))

;; ===========================================================================
;; ssr-handler — cookies serialise to Set-Cookie headers
;; ===========================================================================

(deftest handler-cookies-become-set-cookie-headers
  (testing "structured cookies materialise to Set-Cookie wire form"
    (rf/reg-event-fx :init/with-cookies
      {:platforms #{:server}}
      (fn [_ _]
        {:fx [[:rf.server/set-cookie {:name      "session"
                                      :value     "abc123"
                                      :max-age   3600
                                      :http-only true
                                      :same-site :lax
                                      :path      "/"}]
              [:rf.server/set-cookie {:name  "theme"
                                      :value "dark"
                                      :path  "/"}]]}))

    (rf/reg-view* :pages/cookied
      (fn [] [:div "cookied"]))

    (let [handler  (ssr-ring/ssr-handler
                     {:on-create [:init/with-cookies]
                      :root-view [:pages/cookied]
                      :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})
          headers  (:headers response)
          set-cookie (or (get headers "Set-Cookie") (get headers "set-cookie"))]
      (is (= 200 (:status response)))
      (is (some? set-cookie))
      ;; Two cookies → vector form.
      (is (vector? set-cookie)
          "multi-valued Set-Cookie collapses into a vector")
      (is (= 2 (count set-cookie)))
      (is (some #(and (str/includes? % "session=abc123")
                      (str/includes? % "Max-Age=3600")
                      (str/includes? % "HttpOnly")
                      (str/includes? % "SameSite=Lax"))
                set-cookie)
          "session cookie carries all its attributes")
      (is (some #(str/includes? % "theme=dark") set-cookie)))))

;; ===========================================================================
;; ssr-handler — error path
;; ===========================================================================

(deftest handler-render-error-projects-through-projector
  (testing "rf2-zwgsv — exception raised during render flows through the
            SSR error projector (Spec 011 §View-time exceptions), not
            through the Ring :on-error hook. Wire body carries the
            projector's :message / :code, never .getMessage."
    (rf/reg-event-fx :init/ok
      {:platforms #{:server}}
      (fn [_ _] {}))

    (rf/reg-view* :pages/broken
      (fn [] (throw (ex-info "boom-internal-jdbc-url-secret" {}))))

    (let [handler (ssr-ring/ssr-handler
                    {:on-create [:init/ok]
                     :root-view [:pages/broken]
                     :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/broken" :request-method :get})]
      (is (= 500 (:status response))
          "rf2-zwgsv: default projector's `:internal-error` shape →
           status 500. Same status the drain-time projector path
           produces for fx/handler exceptions — uniform contract.")
      ;; rf2-kzvwq / security audit §P2.1 — the default body MUST NOT
      ;; carry the exception's message. .getMessage is documented as
      ;; carrying internal topology (JDBC URLs, file paths, SQL
      ;; fragments); leaking it publicly is the bug. The projector is
      ;; the security boundary.
      (is (not (str/includes? (:body response) "boom-internal-jdbc-url-secret"))
          "rf2-kzvwq: the throwable's message text MUST NOT appear in
           the projector body (topology disclosure surface)")
      ;; rf2-zwgsv: the projector-driven body carries the default
      ;; projector's `:message` ("Something went wrong") so the
      ;; client / crawler / monitoring stack sees the public surface.
      (is (str/includes? (:body response) "Something went wrong")
          "rf2-zwgsv: wire body carries the projector's `:message`
           (default = 'Something went wrong' per
           `error-projector/fallback-public-error`)")
      (is (str/includes? (:body response) "internal-error")
          "rf2-zwgsv: wire body carries the projector's `:code`
           (default = `:internal-error`) as a stable category that
           response-page templates can branch on.")
      (is (not (= "Internal error" (:body response)))
          "rf2-zwgsv: render-time throws go through the projector,
           not the Ring :on-error fallback — the fixed
           'Internal error' string is never the wire body."))))

(deftest handler-render-error-custom-projector-shapes-body
  (testing "rf2-zwgsv — caller customises the render-time error wire
            body via a custom :rf.error/* projector mapping, NOT via
            the :on-error Ring hook (which now only catches
            transport/projector-undeliverable failures)."
    (rf/reg-event-fx :init/ok
      {:platforms #{:server}}
      (fn [_ _] {}))

    (rf/reg-view* :pages/broken-2
      (fn [] (throw (ex-info "boom-2" {:custom true}))))

    (rf/reg-error-projector
      :myapp/teapot
      (fn [_trace-event]
        {:status     418
         :code       :teapot
         :message    "I'm a teapot"
         :retryable? false}))

    (let [handler (ssr-ring/ssr-handler
                    {:on-create [:init/ok]
                     :root-view [:pages/broken-2]
                     :ssr       {:public-error-id :myapp/teapot}
                     :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/broken" :request-method :get})]
      (is (= 418 (:status response))
          "the custom projector's :status overrides the default 500
           — same surface as the drain-time projector path.")
      ;; The apostrophe in "I'm" is HTML-escaped to `&#39;` per
      ;; `escape-html` — the body's `:message` slot is rendered as
      ;; an HTML text-node so untrusted custom-projector output
      ;; cannot break out of the envelope.
      (is (str/includes? (:body response) "I&#39;m a teapot")
          "the custom projector's :message rides the wire body
           (HTML-escaped — `escape-html` over the message text)")
      (is (str/includes? (:body response) "teapot")
          "the custom projector's :code rides the wire body."))))

;; ===========================================================================
;; rf2-ee38b.11 — :error-view caller hook (Spec 011 §Server error
;; projection step 5 — "a registered view (or the host's default error
;; template) … receiving the public-error map as its prop")
;; ===========================================================================

(deftest handler-render-error-renders-registered-error-view
  (testing "rf2-ee38b.11: a caller-registered :error-view (keyword) is
            rendered through the SSR emitter, receiving the public-error
            map as its single prop — replacing the hardcoded default
            error template. The public-error's :message rides the wire
            HTML-escaped (the emitter escapes text-node children)."
    (rf/reg-event-fx :init/ok {:platforms #{:server}} (fn [_ _] {}))
    (rf/reg-view* :pages/broken-ev
      (fn [] (throw (ex-info "boom-internal" {}))))
    (rf/reg-view* :myapp/error-page
      (fn [{:keys [status code message]}]
        [:div.error-page
         [:h1 "Custom error page"]
         [:p.code (name code)]
         [:p.status (str status)]
         [:p.msg message]]))
    (let [handler  (ssr-ring/ssr-handler
                     {:on-create  [:init/ok]
                      :root-view  [:pages/broken-ev]
                      :error-view :myapp/error-page
                      :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/broken" :request-method :get})
          body     (:body response)]
      (is (= 500 (:status response)) "projector status rides the response")
      (is (str/includes? body "Custom error page")
          "the caller's :error-view rendered, not the default template")
      (is (str/includes? body "class=\"error-page\"")
          "the error view's own markup / styling reaches the wire")
      (is (str/includes? body "internal-error")
          "the public-error :code flows to the error view")
      (is (str/includes? body "Something went wrong")
          "the public-error :message flows to the error view")
      (is (not (str/includes? body "boom-internal"))
          "rf2-kzvwq: the throwable message text MUST NOT reach the wire"))))

(deftest handler-render-error-view-as-fn
  (testing "rf2-ee38b.11: :error-view accepts a 1-arity fn receiving the
            public-error map and returning hiccup"
    (rf/reg-event-fx :init/ok {:platforms #{:server}} (fn [_ _] {}))
    (rf/reg-view* :pages/broken-evf
      (fn [] (throw (ex-info "boom" {}))))
    (let [handler  (ssr-ring/ssr-handler
                     {:on-create  [:init/ok]
                      :root-view  [:pages/broken-evf]
                      :error-view (fn [public]
                                    [:section.fn-error
                                     [:h2 "fn error view"]
                                     [:span (:message public)]])
                      :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/broken" :request-method :get})
          body     (:body response)]
      (is (= 500 (:status response)))
      (is (str/includes? body "fn error view") "fn-form error view rendered")
      (is (str/includes? body "class=\"fn-error\"") "fn view markup on the wire"))))

(deftest handler-error-view-throw-falls-back-to-default-template
  (testing "rf2-ee38b.11: a buggy :error-view (itself throwing) MUST NOT
            bypass the error boundary — the host falls back to the
            default error template and emits
            :rf.error/ssr-ring-error-view-failed on the trace bus."
    (rf/reg-event-fx :init/ok {:platforms #{:server}} (fn [_ _] {}))
    (rf/reg-view* :pages/broken-evt
      (fn [] (throw (ex-info "boom" {}))))
    (let [traces (atom [])]
      (rf/register-listener! ::error-view-throw-watch
        (fn [ev] (when (= :rf.error/ssr-ring-error-view-failed (:operation ev))
                   (swap! traces conj ev))))
      (let [handler  (ssr-ring/ssr-handler
                       {:on-create  [:init/ok]
                        :root-view  [:pages/broken-evt]
                        :error-view (fn [_public]
                                      (throw (ex-info "error-view itself broke" {})))
                        :payload :rf.ssr.payload/whole-app-db})
            response (handler {:uri "/broken" :request-method :get})
            body     (:body response)]
        (rf/unregister-listener! ::error-view-throw-watch)
        (is (= 500 (:status response)) "still a 500 — boundary holds")
        (is (str/includes? body "Something went wrong")
            "fell back to the default error template's public :message")
        (is (not (str/includes? body "error-view itself broke"))
            "the error-view's own throwable message MUST NOT reach the wire")
        (is (seq @traces)
            ":rf.error/ssr-ring-error-view-failed trace fired for the
             buggy error view")))))

;; ===========================================================================
;; ssr-handler — fn-form :root-view invoked exactly once per request (rf2-6t36h)
;;
;; The fn-form branch of `:root-view` is not guaranteed to be idempotent —
;; unsorted-map iteration order, gensym'd react keys, and time-of-day
;; props all vary between calls. The adapter therefore invokes the
;; root-view fn ONCE per request and reuses the same tree for the wire
;; HTML / its embedded data-rf-render-hash AND for the payload's
;; :rf/render-hash. Two invocations would produce two non-identical trees
;; → two non-equal hashes → spurious :rf.ssr/hydration-mismatch on the
;; client for an otherwise successful hydration.
;;
;; These tests pin the contract: ONE invocation per request, and the wire
;; hash equals the payload hash even when the fn is technically
;; non-idempotent.
;; ===========================================================================

(deftest fn-form-root-view-invoked-exactly-once-per-request
  (testing "rf2-6t36h: a 0-arity fn :root-view fires exactly once per request"
    (let [call-count (atom 0)]
      (rf/reg-event-fx :init/ok-once
        {:platforms #{:server}}
        (fn [_ _] {}))

      (rf/reg-view* :pages/once
        (fn [] [:div.page "once"]))

      (let [handler  (ssr-ring/ssr-handler
                       {:on-create [:init/ok-once]
                        :root-view (fn []
                                     (swap! call-count inc)
                                     [:pages/once])
                        :payload :rf.ssr.payload/whole-app-db})
            response (handler {:uri "/once" :request-method :get})]
        (is (= 200 (:status response)))
        (is (= 1 @call-count)
            "fn-form :root-view must be invoked exactly once per request
             (rf2-6t36h: ONE call covers both the wire HTML and the
             payload :rf/render-hash)")))))

(deftest fn-form-root-view-non-idempotent-still-hashes-consistently
  (testing "rf2-6t36h: a non-idempotent fn-form :root-view produces a wire
            hash that matches the payload hash — the same tree is used for
            both, so the client mismatch check does NOT fire spuriously"
    (let [counter (atom 0)]
      (rf/reg-event-fx :init/ok-noni
        {:platforms #{:server}}
        (fn [_ _] {}))

      ;; A view fn that produces a DIFFERENT tree on every call — its
      ;; key includes a monotonic counter. With two invocations the wire
      ;; HTML would carry hash(tree_1) and the payload would carry
      ;; hash(tree_2); rf2-6t36h's single-invocation contract closes that
      ;; spurious-mismatch gap.
      (rf/reg-view* :pages/noni
        (fn [n] [:div {:data-call n} "noni"]))

      (let [handler  (ssr-ring/ssr-handler
                       {:on-create [:init/ok-noni]
                        :root-view (fn []
                                     [:pages/noni (swap! counter inc)])
                        :payload :rf.ssr.payload/whole-app-db})
            response (handler {:uri "/noni" :request-method :get})
            body     (:body response)
            wire-hash (second (re-find #"data-rf-render-hash=\"([0-9a-f]{8})\""
                                       body))
            payload-edn (second (re-find
                                  #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>"
                                  body))
            ;; Payload EDN uses the `#:rf{...}` namespace-map shorthand
            ;; (pr-str's default rendering for a map whose keys all share
            ;; the `:rf/` namespace) — so `:rf/render-hash` collapses to
            ;; `:render-hash` inside that block. Match either rendering
            ;; so the test is robust against the pr-str-shape choice.
            payload-hash (second (re-find
                                   #":(?:rf/)?render-hash \"([0-9a-f]{8})\""
                                   payload-edn))]
        (is (= 200 (:status response)))
        (is (some? wire-hash)
            "data-rf-render-hash present on wire root element")
        (is (some? payload-hash)
            ":rf/render-hash present in hydration payload")
        (is (= wire-hash payload-hash)
            "rf2-6t36h: wire hash MUST equal payload hash — both derive
             from the same single invocation of the fn-form root-view")
        (is (= 1 @counter)
            "side-effect counter confirms the fn was invoked exactly once")))))

;; ===========================================================================
;; ssr-handler — Ring → :rf.server/request cofx (rf2-afxhv)
;;
;; The canonical Ring-adapter ↔ cofx boundary. Per Spec 011 §Request
;; storage substrate, the host adapter populates the per-frame request
;; slot before drain; the `:rf.server/request` cofx reads from the slot
;; via `(inject-cofx :rf.server/request)`. This test pins the wiring
;; end-to-end — if the adapter forgets to call `set-request!`, the
;; cofx surfaces `nil` and this assertion fails.
;; ===========================================================================

(deftest handler-surfaces-request-via-cofx
  (testing "the Ring request map flows through to handlers via :rf.server/request"
    (let [captured (atom ::not-captured)]
      (rf/reg-event-fx :init/capture-request-cofx
        {:platforms #{:server}}
        [(rf/inject-cofx :rf.server/request)]
        (fn [{:keys [rf.server/request]} _]
          (reset! captured request)
          {}))

      (rf/reg-view* :pages/blank (fn [] [:div]))

      (let [handler  (ssr-ring/ssr-handler
                       {:on-create [:init/capture-request-cofx]
                        :root-view [:pages/blank]
                        :payload :rf.ssr.payload/whole-app-db})
            request  {:uri            "/articles/42"
                      :request-method :get
                      :headers        {"user-agent" "ring-adapter-test"
                                       "cookie"     "session=abc123"}}
            _        (handler request)]
        (is (= request @captured)
            "the cofx surfaced the Ring request — Spec 011 §Request storage substrate")))))

(deftest handler-clears-request-slot-after-response
  (testing "the per-frame request slot is dropped when the request frame is destroyed"
    (let [captured-fid (atom nil)]
      (rf/reg-event-fx :init/capture-frame-id
        {:platforms #{:server}}
        (fn [{:keys [frame]} _]
          (reset! captured-fid frame)
          {}))

      (rf/reg-view* :pages/blank2 (fn [] [:div]))

      (let [handler (ssr-ring/ssr-handler
                      {:on-create [:init/capture-frame-id]
                       :root-view [:pages/blank2]
                       :payload :rf.ssr.payload/whole-app-db})]
        (handler {:uri "/x" :request-method :get})
        (is (some? @captured-fid)
            "the on-create handler captured the per-request frame-id")
        (is (nil? (ssr/get-request @captured-fid))
            "the request slot was cleared on frame teardown — no leak across requests")))))

(deftest handler-isolates-request-slots-across-requests
  (testing "two sequential requests carry independent request data — no slot bleed"
    (let [observed (atom [])]
      (rf/reg-event-fx :init/observe-request
        {:platforms #{:server}}
        [(rf/inject-cofx :rf.server/request)]
        (fn [{:keys [rf.server/request]} _]
          (swap! observed conj (:uri request))
          {}))

      (rf/reg-view* :pages/blank3 (fn [] [:div]))

      (let [handler (ssr-ring/ssr-handler
                      {:on-create [:init/observe-request]
                       :root-view [:pages/blank3]
                       :payload :rf.ssr.payload/whole-app-db})]
        (handler {:uri "/a" :request-method :get})
        (handler {:uri "/b" :request-method :get})
        (is (= ["/a" "/b"] @observed)
            "each request's handler saw its own URI — no slot bleed")))))

;; ===========================================================================
;; ssr-handler — :ssr opt reaches the per-request frame's :ssr metadata
;;
;; Regression (rf2-8cx3y): the docstring documented `:ssr` but the
;; destructure read `:ssr-config`, so any caller passing
;; `{:ssr {:dev-error-detail? true ...}}` had it silently dropped —
;; `cond->` never asserted the key onto the per-request frame config,
;; and the default projector ran regardless of the caller's intent.
;; Pre-alpha: no back-compat — `:ssr` is now the canonical name and
;; matches both `rf/make-frame`'s frame-config key and Spec 011.
;; ===========================================================================

(deftest handler-passes-through-ssr-opt-to-frame-meta
  (testing ":ssr opt → per-request frame's :ssr metadata (rf2-8cx3y)"
    (let [captured (atom :unset)]
      (rf/reg-event-fx :init/capture-ssr-meta
        {:platforms #{:server}}
        (fn [{:keys [frame]} _]
          (reset! captured (:ssr (rf/frame-meta frame)))
          {}))

      (rf/reg-view* :pages/blank-for-ssr-opt (fn [] [:div]))

      (let [handler (ssr-ring/ssr-handler
                      {:on-create [:init/capture-ssr-meta]
                       :root-view [:pages/blank-for-ssr-opt]
                       :ssr       {:dev-error-detail? true
                                   :public-error-id   :myapp/projector}
                       :payload :rf.ssr.payload/whole-app-db})]
        (handler {:uri "/" :request-method :get})
        (is (= {:dev-error-detail? true
                :public-error-id   :myapp/projector}
               @captured)
            "the :ssr opt reaches the per-request frame's :ssr metadata
             — the destructure matches the documented key")))))

;; ===========================================================================
;; ssr-handler — :payload allowlist slice
;; ===========================================================================

(deftest handler-payload-keys-slices-app-db
  (testing ":payload allowlist ships a subset of app-db in the hydration payload"
    (rf/reg-event-fx :init/many-keys
      {:platforms #{:server}}
      (fn [_ _]
        {:db {:public/articles [:a :b :c]
              :server-only/secret-token "very-secret"
              :server-only/admin-flag true}}))

    (rf/reg-view* :pages/echo (fn [] [:div "echo"]))

    (let [handler (ssr-ring/ssr-handler
                    {:on-create    [:init/many-keys]
                     :root-view    [:pages/echo]
                     :payload [:public/articles]})
          response (handler {:uri "/" :request-method :get})
          body     (:body response)]
      (is (= 200 (:status response)))
      ;; pr-str emits namespace-keyed maps in the `#:public{...}`
      ;; reader shorthand for namespace-shared keys (:public/articles).
      ;; Match the shorthand here; the structural content is what
      ;; matters — that the payload carries the public slice and
      ;; omits the server-only slices.
      (is (or (str/includes? body ":public/articles")
              (str/includes? body "#:public{:articles"))
          "payload carries the public/articles key (either as
           qualified-keyword or as namespace-map shorthand)")
      (is (not (str/includes? body "secret-token"))
          ":payload allowlist omits server-only slices from the wire payload")
      (is (not (str/includes? body "admin-flag"))))))

;; ===========================================================================
;; ssr-handler — explicit fail-closed payload policy (rf2-gtgf9)
;;
;; The wire-level proof that the policy contract is fail-closed:
;;
;;   1. A handler constructed with NO `:payload` opt throws at
;;      construction time (`:rf.error/ssr-missing-payload-policy`).
;;      Misconfigured deployments fail at boot rather than at first
;;      request.
;;
;;   2. **The fail-closed proof** — when a handler is constructed
;;      with an allowlist that does NOT include a server-only key,
;;      that key MUST NOT appear in the hydration payload on the
;;      wire. The contract requires the allowlist; an un-permitted
;;      slot is provably excluded by inspection of the wire payload
;;      (rf2-gtgf9: no default-whole-app-db fallback exists, so
;;      server-only keys cannot ride the wire silently).
;;
;;   3. The opt-in branch — `:payload :rf.ssr.payload/whole-app-db`
;;      ships the whole app-db verbatim (apps that genuinely want it
;;      can opt in explicitly).
;;
;;   4. A typo'd `:payload` keyword surfaces as
;;      `:rf.error/ssr-unknown-payload-policy` — distinct from the
;;      missing-policy bucket so the developer can tell the two
;;      failure modes apart.
;; ===========================================================================

(deftest handler-construction-fails-closed-when-no-policy-supplied
  (testing "rf2-gtgf9: ssr-handler with no :payload opt throws
            :rf.error/ssr-missing-payload-policy at construction time —
            the canonical fail-closed pattern. Misconfigured deployments
            fail at boot, not at first request."
    (rf/reg-event-fx :init/no-policy {:platforms #{:server}} (fn [_ _] {}))
    (rf/reg-view* :pages/no-policy (fn [] [:div "no policy"]))

    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/ssr-missing-payload-policy"
          (ssr-ring/ssr-handler
            {:on-create [:init/no-policy]
             :root-view [:pages/no-policy]}))
        "ssr-handler MUST throw at construction time when the policy
         is unset — Spec 011 §Payload scope (canonical boundary) +
         rf2-gtgf9 fail-closed contract")))

(deftest stream-handler-construction-fails-closed-when-no-policy-supplied
  (testing "rf2-gtgf9: stream-handler shares the policy contract —
            mirror of ssr-handler-construction test for the chunked
            host adapter"
    (rf/reg-event-fx :init/no-policy-stream {:platforms #{:server}} (fn [_ _] {}))
    (rf/reg-view* :pages/no-policy-stream (fn [] [:div "no policy stream"]))

    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/ssr-missing-payload-policy"
          (ssr-ring/stream-handler
            {:on-create [:init/no-policy-stream]
             :root-view [:pages/no-policy-stream]}))
        "stream-handler MUST throw at construction time when the
         policy is unset — same fail-closed contract as ssr-handler")))

;; rf2-ee38b.11 — stream-handler MUST run the SAME required-opt
;; (:on-create / :root-view) construction-time validation ssr-handler
;; does. Pre-fix it only checked the policy + trusted-shell opts, so a
;; misconfigured streaming handler shipped and failed per-request
;; (missing :on-create → 500; missing :root-view → silently-truncated
;; chunked response from the writer thread) instead of refusing to
;; construct. Now both share `lifecycle/validate-required-opts!`.

(deftest ssr-handler-construction-fails-closed-when-missing-on-create
  (testing "rf2-ee38b.11: ssr-handler with no :on-create throws
            :rf.error/ssr-ring-missing-on-create at construction"
    (rf/reg-view* :pages/no-oncreate (fn [] [:div]))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/ssr-ring-missing-on-create"
          (ssr-ring/ssr-handler
            {:root-view [:pages/no-oncreate]
             :payload :rf.ssr.payload/whole-app-db})))))

(deftest stream-handler-construction-fails-closed-when-missing-on-create
  (testing "rf2-ee38b.11: stream-handler with no :on-create throws
            :rf.error/ssr-ring-missing-on-create at construction — same
            fail-closed contract as ssr-handler (was missing pre-fix)"
    (rf/reg-view* :pages/no-oncreate-stream (fn [] [:div]))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/ssr-ring-missing-on-create"
          (ssr-ring/stream-handler
            {:root-view [:pages/no-oncreate-stream]
             :payload :rf.ssr.payload/whole-app-db})))))

(deftest stream-handler-construction-fails-closed-when-missing-root-view
  (testing "rf2-ee38b.11: stream-handler with no :root-view throws
            :rf.error/ssr-ring-missing-root-view at construction — pre-fix
            this surfaced only inside the writer thread, truncating the
            chunked response instead of failing at boot"
    (rf/reg-event-fx :init/no-rootview-stream {:platforms #{:server}} (fn [_ _] {}))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/ssr-ring-missing-root-view"
          (ssr-ring/stream-handler
            {:on-create [:init/no-rootview-stream]
             :payload :rf.ssr.payload/whole-app-db})))))

(deftest fail-closed-proof-unpermitted-slot-not-on-wire
  (testing "rf2-gtgf9 FAIL-CLOSED PROOF: a server-only app-db key NOT in
            the :payload allowlist MUST NOT appear in the
            hydration-payload script tag's EDN body. Without the
            allowlist gate an unaudited new app-db key would silently
            ride the wire on every request — the allowlist is load-
            bearing."
    (rf/reg-event-fx :init/with-secret
      {:platforms #{:server}}
      (fn [_ _]
        {:db {:public/articles          [{:id "a" :title "A"}]
              ;; The leak-probe value: this key is NOT in the
              ;; allowlist below, so it MUST NOT appear in the
              ;; hydration payload. The string is unique enough that
              ;; the str/includes? check below is unambiguous.
              :server-only/auth-token   "RF2_GTGF9_FAIL_CLOSED_PROBE_xyz789"
              :server-only/feature-flag :flag/internal-only
              :server-only/admin-uid    "admin-42"}}))

    (rf/reg-view* :pages/policy-probe
      (fn [] [:div.page "policy probe"]))

    (let [handler   (ssr-ring/ssr-handler
                      ;; The allowlist names ONLY the public slice.
                      ;; Every other server-only key on app-db is
                      ;; un-permitted and MUST be dropped.
                      {:on-create    [:init/with-secret]
                       :root-view    [:pages/policy-probe]
                       :payload [:public/articles]})
          response  (handler {:uri "/" :request-method :get})
          body      (:body response)
          payload-m (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>"
                             body)
          payload-edn (when payload-m (second payload-m))]

      ;; (sanity) the request succeeded and the payload script tag
      ;; exists — we're observing the actual wire shape.
      (is (= 200 (:status response)))
      (is (some? payload-edn)
          "hydration payload script tag is present — observation surface
           is the wire payload, not a pre-serialisation map")

      ;; (sanity) the public slice IS on the wire — proves the policy
      ;; isn't a no-op that happens to drop everything.
      (is (or (str/includes? payload-edn ":public/articles")
              (str/includes? payload-edn "#:public{:articles"))
          "the public slice is present (sanity — the policy isn't
           dropping everything by accident)")

      ;; THE FAIL-CLOSED PROOF: the un-permitted slot's value MUST
      ;; NOT appear in the wire payload. Under rf2-gtgf9 the allowlist
      ;; is load-bearing — this assertion is what the security audit
      ;; asked for.
      (is (not (str/includes? payload-edn "RF2_GTGF9_FAIL_CLOSED_PROBE_xyz789"))
          "rf2-gtgf9 fail-closed proof: an un-permitted server-only
           key's value does NOT appear in the wire hydration payload.")
      (is (not (str/includes? payload-edn "feature-flag"))
          "rf2-gtgf9: the un-permitted key NAME also does not leak —
           neither key nor value reaches the wire")
      (is (not (str/includes? payload-edn "admin-uid"))
          "rf2-gtgf9: belt-and-braces over multiple un-permitted slots"))))

(deftest payload-policy-whole-app-db-opt-in-ships-everything
  (testing "rf2-gtgf9: explicit :payload :rf.ssr.payload/whole-app-db is
            the documented opt-in for apps whose entire app-db is intended
            for the wire"
    (rf/reg-event-fx :init/wholeapp
      {:platforms #{:server}}
      (fn [_ _]
        {:db {:public/articles [:a :b :c]
              :public/user-id  "u-99"
              :public/theme    :dark}}))

    (rf/reg-view* :pages/wholeapp
      (fn [] [:div.page "whole app"]))

    (let [handler   (ssr-ring/ssr-handler
                      {:on-create      [:init/wholeapp]
                       :root-view      [:pages/wholeapp]
                       :payload :rf.ssr.payload/whole-app-db})
          response  (handler {:uri "/" :request-method :get})
          body      (:body response)
          payload-m (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>"
                             body)
          payload-edn (when payload-m (second payload-m))]
      (is (= 200 (:status response)))
      (is (some? payload-edn))
      ;; All three keys present — opt-in shipped the whole app-db.
      (is (str/includes? payload-edn "u-99")
          ":public/user-id reached the wire under the whole-app-db opt-in")
      (is (str/includes? payload-edn ":dark")
          ":public/theme reached the wire under the whole-app-db opt-in"))))

(deftest handler-construction-rejects-unknown-policy-keyword
  (testing "rf2-gtgf9 / rf2-pffil: a typo'd :payload keyword surfaces as a
            distinct error so the developer can tell the failure modes apart"
    (rf/reg-event-fx :init/typo-policy {:platforms #{:server}} (fn [_ _] {}))
    (rf/reg-view* :pages/typo-policy (fn [] [:div]))

    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/ssr-unknown-payload-policy"
          (ssr-ring/ssr-handler
            {:on-create [:init/typo-policy]
             :root-view [:pages/typo-policy]
             :payload   :rf.ssr.payload/whole-db})) ; typo
        "typo'd :payload keyword does NOT silently fall into the
         missing-policy bucket — the developer sees a distinct
         :rf.error/ssr-unknown-payload-policy")))

;; ===========================================================================
;; ssr-handler / stream-handler — trusted shell-hook contract (rf2-o6ndb)
;;
;; The four shell-envelope opts that get injected RAW into the rendered
;; HTML response — `:head`, `:body-end`, `:script-src`, `:app-element-id`
;; — are TRUSTED STRINGS per Spec 011 §Trusted shell hook contract. The
;; framework names the trust boundary (so apps reading any of them from
;; untrusted input know they are opting into an arbitrary-script-
;; injection XSS vector) AND structurally validates the shape at
;; handler-construction time so a structural mistake (passing a map, a
;; vector, a symbol, a number) surfaces as a clean structured error at
;; boot, not as a ClassCastException deep in the rendering path on the
;; first request.
;;
;; Strings pass through unchanged — the framework names the boundary
;; but does not gate the content (per the trusted-string contract).
;; Nil is fine (means "no override, use the default").
;; ===========================================================================

(deftest handler-construction-rejects-non-string-trusted-shell-opts
  (testing "rf2-o6ndb: ssr-handler structural-shape-checks the four
            trusted-shell-hook opts at construction time"
    (rf/reg-event-fx :init/trusted-opt-test
                     {:platforms #{:server}} (fn [_ _] {}))
    (rf/reg-view* :pages/trusted-opt-test (fn [] [:div]))

    (let [base-opts {:on-create    [:init/trusted-opt-test]
                     :root-view    [:pages/trusted-opt-test]
                     :payload [:public/x]}]

      (testing "each of the four trusted-string opts is rejected when
                supplied a non-string non-nil value (per the bead's
                'validate STRINGS, not maps/symbols/etc' requirement)"
        (doseq [[opt-k bad-vals]
                {:head            [{:title "x"}             ; map
                                   [:head [:title "x"]]     ; vector (hiccup)
                                   'some-sym                ; symbol
                                   42]                      ; number
                 :body-end        [{:script "x"}
                                   ['x]
                                   'sym
                                   3.14]
                 :script-src      [:main.js                 ; keyword
                                   ["/main.js"]             ; vector
                                   {:src "/main.js"}        ; map
                                   123]
                 :app-element-id  [:app                     ; keyword (very common mistake)
                                   {:id "app"}
                                   ['app]
                                   0]}
                bad-val bad-vals]
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #":rf\.error/ssr-trusted-shell-opt-invalid"
                (ssr-ring/ssr-handler (assoc base-opts opt-k bad-val)))
              (str "ssr-handler MUST reject " (pr-str opt-k) " = "
                   (pr-str bad-val)
                   " with :rf.error/ssr-trusted-shell-opt-invalid"))))

      (testing "ex-data carries the structured diagnostic shape — :opt-key
                names the offending opt; :got carries the rejected value;
                :got-type names the type"
        (let [ex (try (ssr-ring/ssr-handler
                        (assoc base-opts :body-end {:script "evil"}))
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "an exception was thrown")
          (is (= ":rf.error/ssr-trusted-shell-opt-invalid"
                 (ex-message ex)))
          (is (= :body-end (:opt-key (ex-data ex))))
          (is (= {:script "evil"} (:got (ex-data ex))))
          (is (= :supply-string-or-nil (:recovery (ex-data ex))))))

      (testing "strings pass through unchanged — the framework names
                the boundary but does not gate the content; the
                trust call itself remains the caller's"
        (is (fn? (ssr-ring/ssr-handler
                   (assoc base-opts
                          :head           "<title>OK</title>"
                          :body-end       "<script src=\"/analytics.js\"></script>"
                          :script-src     "/custom-bootstrap.js"
                          :app-element-id "root")))
            "all four opts passed as strings — handler construction succeeds"))

      (testing "nil values pass — explicit nil signals 'no override,
                use the default'; the construction-time check accepts it"
        (is (fn? (ssr-ring/ssr-handler
                   (assoc base-opts
                          :head           nil
                          :body-end       nil
                          :script-src     nil
                          :app-element-id nil)))))

      (testing "absent keys pass (regression guard — the check is
                contains?-aware so absent opts don't trip on the
                non-nil branch)"
        (is (fn? (ssr-ring/ssr-handler base-opts)))))))

(deftest stream-handler-construction-rejects-non-string-trusted-shell-opts
  (testing "rf2-o6ndb: stream-handler shares the trusted-shell-hook
            structural contract — mirror of the ssr-handler test for the
            chunked host adapter. The streaming prefix/suffix injects
            the same four opts RAW into the rendered HTML envelope."
    (rf/reg-event-fx :init/trusted-opt-stream
                     {:platforms #{:server}} (fn [_ _] {}))
    (rf/reg-view* :pages/trusted-opt-stream (fn [] [:div]))

    (let [base-opts {:on-create    [:init/trusted-opt-stream]
                     :root-view    [:pages/trusted-opt-stream]
                     :payload [:public/x]}]
      (testing "stream-handler rejects non-string non-nil values on
                each of the four trusted-string opts"
        (doseq [[opt-k bad-val] [[:head            {:title "x"}]
                                 [:body-end        ['x]]
                                 [:script-src      :main.js]
                                 [:app-element-id  {:id "app"}]]]
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #":rf\.error/ssr-trusted-shell-opt-invalid"
                (ssr-ring/stream-handler (assoc base-opts opt-k bad-val)))
              (str "stream-handler MUST reject " (pr-str opt-k) " = "
                   (pr-str bad-val)))))

      (testing "stream-handler accepts strings on all four"
        (is (fn? (ssr-ring/stream-handler
                   (assoc base-opts
                          :head           "<meta name=\"x\">"
                          :body-end       "<script src=\"/x.js\"></script>"
                          :script-src     "/boot.js"
                          :app-element-id "root"))))))))

;; ===========================================================================
;; default-html-shell — title is sourced from the head fragment, never the
;; shell. Two <title> tags per document is malformed HTML (rf2-3z841).
;; ===========================================================================

(deftest default-shell-emits-exactly-one-title-when-route-declares-head
  (testing "the shell must not duplicate the head fragment's <title>. The
            head/meta contract (Spec 011 §Head/meta) is the canonical
            source — the shell defers."
    (rf/reg-head :head/main
                 (fn [_db _route] {:title "From head fragment"}))
    (rf/reg-route :route/x
                  {:doc  "Route x"
                   :path "/"
                   :head :head/main})
    (rf/reg-event-db :init/seed-route
      (fn [db _]
        (assoc-in db [:rf/runtime :routing :current] {:id :route/x})))
    (rf/reg-view* :pages/blank-for-title (fn [] [:div]))

    (let [handler  (ssr-ring/ssr-handler
                     {:on-create [:init/seed-route]
                      :root-view [:pages/blank-for-title]
                      :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})
          body     (:body response)
          opens    (count (re-seq #"<title" body))
          closes   (count (re-seq #"</title>" body))]
      (is (= 200 (:status response)))
      (is (= 1 opens)
          "exactly one <title> opening tag — head fragment is the canonical source")
      (is (= 1 closes)
          "exactly one </title> closing tag")
      (is (str/includes? body "<title>From head fragment</title>")
          "the head fragment's title is what reaches the wire"))))

(deftest default-shell-emits-default-head-title-when-no-route-head
  (testing "no route :head → active-head returns default-head, which rolls
            the frame's :doc into :title; still exactly one <title> tag."
    (rf/reg-view* :pages/blank-no-head (fn [] [:div]))
    (rf/reg-event-db :init/noop (fn [db _] db))

    (let [handler  (ssr-ring/ssr-handler
                     {:on-create [:init/noop]
                      :root-view [:pages/blank-no-head]
                      :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})
          body     (:body response)
          opens    (count (re-seq #"<title" body))]
      (is (= 200 (:status response)))
      ;; default-head pulls :title from :doc; ssr-ring per-request frame's
      ;; :doc is "ssr-ring per-request frame". The presence (or absence)
      ;; of a non-empty :doc-derived title is contract-dependent; the
      ;; tight invariant is "no more than one <title>".
      (is (<= opens 1)
          "no duplicate <title> when head fragment carries (or omits) a title"))))

(deftest default-shell-emits-no-title-when-head-fragment-empty
  (testing "head fragment with no :title → shell emits zero <title> tags.
            The head fragment is the sole title source; an empty model
            yields a titleless document, not a fallback shell title."
    (rf/reg-head :head/no-title
                 (fn [_db _route] {})) ; no :title key
    (rf/reg-route :route/no-title
                  {:doc  "Route no-title"
                   :path "/"
                   :head :head/no-title})
    (rf/reg-event-db :init/seed-no-title
      (fn [db _]
        (assoc-in db [:rf/runtime :routing :current] {:id :route/no-title})))
    (rf/reg-view* :pages/blank-no-title (fn [] [:div]))

    (let [handler  (ssr-ring/ssr-handler
                     {:on-create [:init/seed-no-title]
                      :root-view [:pages/blank-no-title]
                      :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})
          body     (:body response)]
      (is (= 200 (:status response)))
      (is (zero? (count (re-seq #"<title" body)))
          "head fragment with no :title key → no <title> in the document
           (no shell fallback)"))))

;; ===========================================================================
;; default-html-shell — :html-attrs / :body-attrs honoured (rf2-h2ujj).
;;
;; Per Spec 011 §Head/meta (line 478, line 516): the head model carries
;; `:html-attrs` / `:body-attrs` bags; the host shell stamps them on the
;; opening `<html>` / `<body>` tags. Serialisation goes through the
;; shared `re-frame.ssr.html-helpers/attr-string` helper — boolean `true`
;; → bare attribute name, `false` / `nil` → omitted, all other values
;; `escape-attr`-escaped (`&` and `"` only, since the bag is emitted
;; inside double-quoted attribute values).
;; ===========================================================================

(defn- register-attrs-app! [head-model]
  (rf/reg-head :head/with-attrs (fn [_db _route] head-model))
  (rf/reg-route :route/with-attrs
                {:doc  "Route exercising :html-attrs / :body-attrs"
                 :path "/"
                 :head :head/with-attrs})
  (rf/reg-event-db :init/seed-attrs-route
    (fn [db _] (assoc-in db [:rf/runtime :routing :current] {:id :route/with-attrs})))
  (rf/reg-view* :pages/blank-attrs (fn [] [:div])))

(deftest default-shell-stamps-html-attrs-and-body-attrs
  (testing "head model with :html-attrs + :body-attrs → both bags reach
            the wire on the opening tags; :html-attrs :lang wins over the
            :lang opt (Spec 011 §Head/meta line 478, line 516)."
    (register-attrs-app! {:html-attrs {:lang "fr" :data-theme "dark"}
                          :body-attrs {:class "page-article"}})

    (let [handler  (ssr-ring/ssr-handler
                     {:on-create [:init/seed-attrs-route]
                      :root-view [:pages/blank-attrs]
                      :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})
          body     (:body response)]
      (is (= 200 (:status response)))
      (is (str/includes? body "<html lang=\"fr\" data-theme=\"dark\">")
          ":html-attrs stamped on <html> verbatim; :lang in the bag
           takes precedence over the :lang opt default")
      (is (str/includes? body "<body class=\"page-article\">")
          ":body-attrs stamped on <body>"))))

(deftest default-shell-html-and-body-bare-when-attrs-absent
  (testing "head model with no :html-attrs / :body-attrs → <html> falls
            back to the :lang opt (default \"en\"); <body> is bare. This
            is the default for routes that don't opt into attr bags."
    (register-attrs-app! {:title "T"}) ; no attr bags

    (let [handler  (ssr-ring/ssr-handler
                     {:on-create [:init/seed-attrs-route]
                      :root-view [:pages/blank-attrs]
                      :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})
          body     (:body response)]
      (is (= 200 (:status response)))
      (is (str/includes? body "<html lang=\"en\">")
          ":html-attrs absent → :lang opt fallback (default \"en\")")
      (is (str/includes? body "<body>")
          ":body-attrs absent → <body> emitted bare"))))

(deftest default-shell-html-attrs-fills-missing-lang-from-opt
  (testing ":html-attrs present but :lang absent → :lang opt fills it in.
            The bag still wins for every other attribute it declares."
    (register-attrs-app! {:html-attrs {:data-theme "dark"}})

    (let [handler  (ssr-ring/ssr-handler
                     {:on-create [:init/seed-attrs-route]
                      :root-view [:pages/blank-attrs]
                      :lang      "ja"
                      :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})
          body     (:body response)]
      (is (= 200 (:status response)))
      (is (str/includes? body "lang=\"ja\"")
          ":lang opt (\"ja\") fills in for :html-attrs missing :lang")
      (is (str/includes? body "data-theme=\"dark\"")
          ":html-attrs :data-theme reaches <html>"))))

(deftest default-shell-attr-string-handles-booleans-and-nil
  (testing "attr-string serialisation contract — boolean true → bare
            attribute name, boolean false / nil → omitted, other values
            → escape-attr-escaped (`&` and `\"`). Mixed valid + invalid
            values in one bag exercise every branch."
    (register-attrs-app! {:html-attrs {:lang        "en"
                                       :data-flag   true     ; bare
                                       :data-off    false    ; omitted
                                       :data-nil    nil      ; omitted
                                       :data-quote  "a \"b\" c"  ; escaped
                                       :data-amp    "x & y"}      ; escaped
                          :body-attrs {:class       "ok"
                                       :data-hidden false}})

    (let [handler  (ssr-ring/ssr-handler
                     {:on-create [:init/seed-attrs-route]
                      :root-view [:pages/blank-attrs]
                      :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})
          body     (:body response)]
      (is (= 200 (:status response)))
      (is (str/includes? body "data-flag")
          "boolean true → bare attribute name on <html>")
      (is (not (str/includes? body "data-off"))
          "boolean false → attribute omitted entirely from <html>")
      (is (not (str/includes? body "data-nil"))
          "nil → attribute omitted entirely from <html>")
      (is (str/includes? body "data-quote=\"a &quot;b&quot; c\"")
          "\" escaped to &quot; in attribute values")
      (is (str/includes? body "data-amp=\"x &amp; y\"")
          "& escaped to &amp; in attribute values")
      (is (str/includes? body "<body class=\"ok\">")
          "<body> opens with :class only — :data-hidden false omitted"))))

;; ===========================================================================
;; ssr-middleware — match? predicate
;; ===========================================================================

(deftest middleware-falls-through-on-non-match
  (testing "non-matching requests fall through to the wrapped handler"
    (register-articles-app! [])

    (let [wrapped-called (atom false)
          wrapped        (fn [_req]
                           (reset! wrapped-called true)
                           {:status 204 :headers {} :body ""})
          mw             (ssr-ring/ssr-middleware
                           {:on-create    [:rf/server-init]
                            :root-view    [:pages/articles]
                            :fx-overrides {:http/get :http/get.canned}
                            :match?       (fn [req] (= "/ssr" (:uri req)))
                            :payload :rf.ssr.payload/whole-app-db})
          app            (mw wrapped)]

      (let [fall-through-response (app {:uri "/api" :request-method :get})]
        (is @wrapped-called)
        (is (= 204 (:status fall-through-response))))

      (reset! wrapped-called false)
      (let [ssr-response (app {:uri "/ssr" :request-method :get})]
        (is (not @wrapped-called))
        (is (= 200 (:status ssr-response)))
        (is (str/includes? (:body ssr-response) "<!DOCTYPE html>"))))))

;; ===========================================================================
;; hydration-payload-omits-rf-response-accumulator (rf2-jbcmt)
;;
;; Privacy regression — per Spec 011 §Response storage substrate the HTTP
;; response accumulator MUST NOT ride `app-db`. Earlier drafts stored the
;; accumulator at `[:rf/response]` in the request frame's app-db; without
;; explicit `:payload` allowlist filtering, the hydration payload at
;; `build-payload` shipped the whole app-db (with the accumulator's
;; server-only Set-Cookie / X-* headers / redirect locations) onto the
;; client wire. The rf2-jbcmt fix moved storage to a framework-private
;; side-channel atom keyed by frame-id (`re-frame.ssr/response-slots`)
;; so the accumulator can NEVER reach the hydration payload — the
;; privacy boundary is self-enforcing at the storage layer rather than
;; caller-vigilance at every host adapter.
;;
;; This test exercises a full login-flow shape (set-status + secret
;; header + leak-probe cookie + append-header) and asserts:
;;   (a) the hydration payload script tag contains the public app-db,
;;   (b) the payload's serialised contents do NOT contain `:rf/response`,
;;   (c) the payload's serialised contents do NOT contain the secret
;;       values (cookie auth token, internal header value),
;;   (d) the Set-Cookie and X-Secret-Header DO appear on response headers
;;       (the wire shape — i.e. they reach the wire via the response
;;       headers, NOT via the hydration payload).
;; ===========================================================================

(deftest hydration-payload-omits-rf-response-accumulator
  (testing "rf2-jbcmt: the :rf/response accumulator is side-channel — it
            never appears in the hydration payload, even when a login-shape
            request sets server-only cookies / headers / status"
    (rf/reg-event-fx :auth/login
      {:platforms #{:server}}
      (fn [{:keys [db]} _]
        {:db (assoc db :public/article-title "Public Title"
                       :public/user-id "u-42")
         :fx [;; A login-flow shape — every public surface of the
              ;; response API touched, with server-only PII /
              ;; auth-token values to leak-probe.
              [:rf.server/set-status 200]
              [:rf.server/set-cookie {:name      "session"
                                      :value     "SUPER_SECRET_AUTH_TOKEN_xyz"
                                      :http-only true
                                      :secure    true
                                      :same-site :lax
                                      :path      "/"}]
              [:rf.server/delete-cookie {:name "stale-session" :path "/"}]
              [:rf.server/set-header {:name  "X-Secret-Header"
                                      :value "INTERNAL_ONLY_VALUE_abc"}]
              [:rf.server/append-header {:name  "X-Audit"
                                         :value "v1"}]
              [:rf.server/append-header {:name  "X-Audit"
                                         :value "v2"}]]}))

    (rf/reg-view* :pages/dashboard
      (fn [] [:div.page [:h1 "Public dashboard"]]))

    (let [handler   (ssr-ring/ssr-handler
                      {:on-create [:auth/login]
                       :root-view [:pages/dashboard]
                       :payload :rf.ssr.payload/whole-app-db})
          response  (handler {:uri            "/dashboard"
                              :request-method :get
                              :headers        {"user-agent" "rf2-jbcmt-leak-probe"}})
          body      (:body response)
          ;; The payload script tag's contents are EDN per the default
          ;; html-shell — `<script id="__rf_payload" type="application/edn">
          ;; ...edn... </script>`.
          payload-m (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>"
                             body)
          payload-edn (when payload-m (second payload-m))]

      ;; (sanity) the request reached 200 — drain settled cleanly.
      (is (= 200 (:status response)))

      ;; (a) Public state IS on the wire — the payload carries the app-db slice.
      (is (some? payload-edn)
          "hydration payload script tag is present in the body")
      (is (str/includes? payload-edn "Public Title")
          "payload's app-db carries the public state (sanity)")
      (is (str/includes? payload-edn "u-42")
          "payload's app-db carries the public user id (sanity)")

      ;; (b) The :rf/response accumulator key itself MUST NOT appear in
      ;; the payload — the side-channel substrate guarantees this.
      (is (not (str/includes? payload-edn ":rf/response"))
          "rf2-jbcmt: payload does NOT carry :rf/response — the accumulator
           lives in a framework-private side-channel atom, not in app-db")

      ;; (c) The server-only secret values MUST NOT appear in the payload.
      ;; rf2-jbcmt strips `[:rf/response :cookies ...]` /
      ;; `[:rf/response :headers ...]` from the app-db slice before it
      ;; reaches the wire — Set-Cookie / X-Secret-Header values live in
      ;; the response headers ONLY, not also in the hydration payload.
      (is (not (str/includes? payload-edn "SUPER_SECRET_AUTH_TOKEN_xyz"))
          "rf2-jbcmt: the Set-Cookie auth token does NOT leak into the
           hydration payload")
      (is (not (str/includes? payload-edn "INTERNAL_ONLY_VALUE_abc"))
          "rf2-jbcmt: the internal X-Secret-Header value does NOT leak
           into the hydration payload")
      (is (not (str/includes? payload-edn "stale-session"))
          "rf2-jbcmt: even delete-cookie metadata stays server-side")

      ;; (d) The wire response DOES carry the Set-Cookie + X-Secret-Header.
      ;; The privacy contract is "side-channel only" — the values reach
      ;; the wire as response headers (where they're supposed to be), and
      ;; ONLY as response headers (NOT also via the payload).
      (let [headers    (:headers response)
            set-cookie (or (get headers "Set-Cookie")
                           (get headers "set-cookie"))
            secret-hdr (or (get headers "X-Secret-Header")
                           (get headers "x-secret-header"))
            audit-hdr  (or (get headers "X-Audit")
                           (get headers "x-audit"))]
        (is (some? set-cookie)
            "Set-Cookie header IS on the wire response (the place it belongs)")
        (let [sc-str (if (vector? set-cookie) (str/join "; " set-cookie) (str set-cookie))]
          (is (str/includes? sc-str "session=SUPER_SECRET_AUTH_TOKEN_xyz")
              "the session cookie's auth token IS in the Set-Cookie response header"))
        (is (= "INTERNAL_ONLY_VALUE_abc" secret-hdr)
            "X-Secret-Header value IS on the wire response (header surface)")
        (is (some? audit-hdr)
            "X-Audit append-header reached the wire (multi-valued)")))))

;; ===========================================================================
;; rf2-depii — Content-Type default is case-insensitive (no duplicate)
;;
;; `headers->ring-map+default-content-type` defaults Content-Type only
;; when the pairs don't already declare one — and the detection is
;; case-insensitive, so any casing (CONTENT-TYPE, CoNtEnT-TyPe) of a
;; caller-supplied header suppresses the default. RFC 7230 §3.2 —
;; header names are tokens; tokens are case-insensitive.
;;
;; rf2-ee38b.11 — re-pointed at the live single-pass fn (the prior
;; `ensure-content-type` / `headers->ring-map` helpers were orphaned by
;; the rf2-uj9z8 single-pass refactor and have been deleted). The live
;; fn is the production path, so the test now gives real confidence.
;; ===========================================================================

(deftest content-type-default-no-duplicate-on-mixed-case
  (testing "rf2-depii: a preexisting Content-Type pair in ANY casing
            suppresses the default — exactly one content-type key in the
            folded Ring header map, carrying the CALLER's value"
    (let [fold (requiring-resolve
                 're-frame.ssr.ring.headers/headers->ring-map+default-content-type)]
      (doseq [casing ["content-type"
                      "Content-Type"
                      "CONTENT-TYPE"
                      "CoNtEnT-TyPe"
                      "content-Type"]]
        (let [pairs  [[casing "application/json"]]
              result (fold pairs "text/html; charset=utf-8")
              ct-keys (filter (fn [k] (= "content-type" (str/lower-case (str k))))
                              (keys result))]
          (is (= 1 (count ct-keys))
              (str "casing " (pr-str casing)
                   " — exactly one content-type key after the fold, no duplicate"))
          (is (= "application/json" (get result (first ct-keys)))
              (str "casing " (pr-str casing)
                   " — the caller's Content-Type value wins, not the default")))))))

;; ===========================================================================
;; rf2-7ksyr — hydration payload </script> injection (security audit §P1)
;;
;; A user-controlled string round-tripping through app-db that contains
;; `</script>` would close the `<script id="__rf_payload" ...>` envelope
;; and let attacker HTML follow — XSS via the standard hydration boot
;; surface. The shell pre-escapes every `<` in the EDN string as `<`
;; via `html-helpers/escape-script-body-string`. The EDN reader accepts
;; `<` as a string escape for `<`, so the payload round-trips
;; through `clojure.edn/read-string` on the client.
;; ===========================================================================

(deftest hydration-payload-escapes-script-close-and-round-trips
  (testing "rf2-7ksyr: a `</script>` substring in app-db cannot close the
            hydration payload script tag; the EDN reader still recovers
            the original string verbatim"
    (let [hostile "</script><script>alert('xss')</script>"]
      (rf/reg-event-fx :init/hostile
        {:platforms #{:server}}
        (fn [_ _]
          {:db {:public/article-title hostile}}))

      (rf/reg-view* :pages/hostile-page (fn [] [:div "ok"]))

      (let [handler  (ssr-ring/ssr-handler
                       {:on-create [:init/hostile]
                        :root-view [:pages/hostile-page]
                        :payload :rf.ssr.payload/whole-app-db})
            response (handler {:uri "/" :request-method :get})
            body     (:body response)]
        (is (= 200 (:status response)))

        ;; The wire HTML MUST NOT carry the raw `</script><script>` —
        ;; that pattern would terminate the payload envelope and
        ;; introduce a second <script> in document context.
        (is (not (str/includes? body "</script><script>alert"))
            "the closing-tag pattern is broken — no raw </script> escape
             into the document context")

        ;; The escape sequence is what reaches the wire. Two `<` chars
        ;; in the hostile literal → two < escapes.
        (is (str/includes? body "\\u003c/script>\\u003cscript>alert")
            "`<` chars in the payload EDN are escaped as `\\u003c`")

        ;; The EDN reader on the client side must still recover the
        ;; original string from the escaped payload. Extract just the
        ;; specific value's quoted literal and read it. `<` must be
        ;; transparent to clojure.edn/read-string.
        (let [payload-edn (second
                           (re-find
                             #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>"
                             body))
              ;; Match the quoted EDN string literal for :article-title.
              ;; `pr-str` may emit either the qualified key
              ;; (`:public/article-title`) or the `#:public{...}`
              ;; namespace-map shorthand (`:article-title`) — match
              ;; either rendering for robustness.
              literal     (or (second
                                (re-find
                                  #":(?:public/)?article-title (\"[^\"]*\")"
                                  payload-edn)))
              recovered   (when literal (clojure.edn/read-string literal))]
          (is (some? literal)
              "the article-title's EDN string literal is locatable in the payload")
          (is (= hostile recovered)
              "rf2-7ksyr: the EDN reader recovers the original hostile
               string verbatim — the `\\u003c` escape is transparent to
               clojure.edn/read-string"))))))

(deftest hydration-payload-shell-helper-escapes-direct-call
  (testing "rf2-7ksyr: default-html-shell directly — feeding it a payload
            containing </script> must produce a body where the closing
            tag is broken, even before any runtime path. Pin the helper
            contract independent of the handler lifecycle."
    (let [payload-edn "{:greeting \"</script><script>x()</script>\"}"
          html        (ssr-ring/default-html-shell "body" payload-edn {})]
      (is (not (str/includes? html "</script><script>x()"))
          "shell-level: closing-tag pattern is broken")
      (is (str/includes? html "\\u003c/script>\\u003cscript>x()")
          "shell-level: `<` chars escape as `\\u003c`")
      ;; Sanity — the envelope-closing </script> for the payload itself
      ;; is still present (it's the genuine terminator).
      (is (str/includes? html "</script>")))))

;; ===========================================================================
;; rf2-hyk9j TC-3 — destroy-frame-failed trace surfaces on per-request teardown
;; ===========================================================================
;;
;; Per audit rf2-asmj1 R6 / cluster rf2-sljs1, `destroy-frame-quietly!`
;; emits a `:rf.ssr/destroy-frame-failed` warning trace when destroy
;; throws. The behaviour shipped; no test pinned it.

(deftest destroy-frame-quietly-emits-trace-on-throwing-destroy
  (testing "rf2-hyk9j: a frame whose destroy throws → :rf.ssr/destroy-frame-failed
            warning trace fires (audit rf2-asmj1 R6 / rf2-sljs1)

            Most paths inside `destroy-frame!` swallow their own
            exceptions (the on-destroy event runs through the router's
            dispatch-sync which traces handler-exception; the late-bind
            cleanup hooks funnel through `safe-call-hook!` which
            swallows). To exercise destroy-quietly's catch arm we
            simulate a hard runtime failure — `with-redefs` makes
            `rf/destroy-frame!` itself throw. This pins the wire
            contract: when destroy throws for any reason, the trace
            surfaces."
    (let [destroy-quietly! (requiring-resolve
                            're-frame.ssr.ring.lifecycle/destroy-frame-quietly!)
          traces           (atom [])
          f                :rf.frame/test-destroy-throws]
      (rf/register-listener! ::dfq (fn [ev] (swap! traces conj ev)))
      (try
        (with-redefs [rf/destroy-frame!
                      (fn [_] (throw (ex-info "synthetic destroy failure"
                                              {:reason :test})))]
          (destroy-quietly! f))
        (finally
          (rf/unregister-listener! ::dfq)))

      (let [hits (filterv #(= :rf.ssr/destroy-frame-failed (:operation %)) @traces)]
        (is (= 1 (count hits))
            (str "expected one :rf.ssr/destroy-frame-failed trace; saw: "
                 (pr-str (mapv :operation @traces))))
        (when (seq hits)
          (let [ev (first hits)]
            (is (= :warning (:op-type ev)))
            (is (= f (-> ev :tags :frame))
                ":frame tag identifies the frame that failed to destroy")
            (is (string? (-> ev :tags :reason))
                ":reason carries the throwable's message")
            (is (string? (-> ev :tags :ex-class))
                ":ex-class carries the throwable's class name")
            (is (= :warned-and-skipped (:recovery ev))
                ":recovery names the policy")))))))

;; ===========================================================================
;; rf2-2n5gg — :body-end CSP-host allowlist warning (security audit 2026-05-14 §P3.4)
;;
;; `:body-end` is the documented escape hatch for analytics / third-party
;; scripts the shell injects raw. When the caller supplies
;; `:csp-script-src-allowlist`, the shell scans for `<script src=...>` and
;; emits `:rf.ssr/csp-allowlist-violation` for hosts not in the allowlist.
;; The check is debug-gated; no allowlist → no scan.
;; ===========================================================================

(defn- capture-traces
  "Run `body-fn` with a trace listener attached; return the captured trace
  events. Mirrors the `register-listener!` pattern used by the
  :rf.ssr/destroy-frame-failed test above."
  [body-fn]
  (let [traces (atom [])]
    (rf/register-listener! ::csp-watch (fn [ev] (swap! traces conj ev)))
    (try (body-fn)
         (finally (rf/unregister-listener! ::csp-watch)))
    @traces))

(deftest body-end-csp-allowlist-warns-on-disallowed-host
  (testing "rf2-2n5gg: a :body-end <script src=\"...\"> whose host is NOT in
            :csp-script-src-allowlist triggers :rf.ssr/csp-allowlist-violation"
    (let [check! (requiring-resolve 're-frame.ssr.ring.shell/check-body-end-csp-hosts!)
          body-end (str "<script src=\"https://evil.example.com/track.js\"></script>"
                        "<script src=\"https://cdn.allowed.com/ok.js\"></script>")
          traces (capture-traces
                   #(check! body-end #{"cdn.allowed.com"}))
          hits   (filterv #(= :rf.ssr/csp-allowlist-violation (:operation %)) traces)]
      (is (= 1 (count hits))
          (str "expected exactly one :rf.ssr/csp-allowlist-violation trace; saw: "
               (pr-str (mapv :operation traces))))
      (when (seq hits)
        (let [ev (first hits)]
          (is (= "evil.example.com" (-> ev :tags :host))
              "tags :host names the disallowed origin")
          (is (str/includes? (-> ev :tags :message) "rf2-2n5gg")
              ":message references the bead for traceability"))))))

(deftest body-end-csp-allowlist-no-warning-on-allowed-host
  (testing "rf2-2n5gg: every <script src> host IS in the allowlist → no warning"
    (let [check! (requiring-resolve 're-frame.ssr.ring.shell/check-body-end-csp-hosts!)
          body-end "<script src=\"https://cdn.allowed.com/ok.js\"></script>"
          traces (capture-traces
                   #(check! body-end #{"cdn.allowed.com"}))]
      (is (zero? (count (filterv #(= :rf.ssr/csp-allowlist-violation (:operation %))
                                 traces)))
          "no violation when every script-src host is allowlisted"))))

(deftest body-end-csp-allowlist-skip-when-allowlist-absent
  (testing "rf2-2n5gg: no :csp-script-src-allowlist supplied → check is a
            no-op even when :body-end carries scripts; preserves the
            opt-in nature of the feature"
    (let [check! (requiring-resolve 're-frame.ssr.ring.shell/check-body-end-csp-hosts!)
          body-end "<script src=\"https://anything.example.com/track.js\"></script>"
          traces (capture-traces
                   #(check! body-end nil))]
      (is (zero? (count (filterv #(= :rf.ssr/csp-allowlist-violation (:operation %))
                                 traces)))
          "nil allowlist short-circuits the scan"))))

(deftest body-end-csp-allowlist-relative-srcs-pass
  (testing "rf2-2n5gg: relative / same-origin <script src> URLs have no
            host — they can't violate a remote-host allowlist by
            definition, so the scanner skips them"
    (let [check! (requiring-resolve 're-frame.ssr.ring.shell/check-body-end-csp-hosts!)
          body-end "<script src=\"/main.js\"></script><script src=\"./app.js\"></script>"
          traces (capture-traces
                   #(check! body-end #{"cdn.allowed.com"}))]
      (is (zero? (count (filterv #(= :rf.ssr/csp-allowlist-violation (:operation %))
                                 traces)))
          "relative srcs skip the host check"))))

(deftest body-end-csp-allowlist-multiple-violations
  (testing "rf2-2n5gg: a :body-end carrying multiple disallowed-host
            scripts produces one warning per violating script — each
            host is independently flagged"
    (let [check! (requiring-resolve 're-frame.ssr.ring.shell/check-body-end-csp-hosts!)
          body-end (str "<script src=\"https://bad1.example.com/a.js\"></script>"
                        "<script src=\"https://bad2.example.com/b.js\"></script>")
          traces (capture-traces
                   #(check! body-end #{"good.example.com"}))
          hits   (filterv #(= :rf.ssr/csp-allowlist-violation (:operation %)) traces)
          hosts  (mapv #(-> % :tags :host) hits)]
      (is (= 2 (count hits))
          "two violating scripts → two traces")
      (is (= #{"bad1.example.com" "bad2.example.com"} (set hosts))
          "each violating host is flagged independently"))))

;; ===========================================================================
;; rf2-axq1y / parent rf2-bof8i — resolve-head emits
;; :rf.error/ssr-head-resolution-failed before falling back
;; ===========================================================================
;;
;; Per rf2-bof8i (Mike decision, Option B — observability over silent
;; fallback) `resolve-head` wraps the `:head` walk in try/catch and emits
;; `:rf.error/ssr-head-resolution-failed` with the throw before degrading
;; to the empty fragment. Earlier the helper's docstring promised "the
;; trace surface still carries the throw" but the impl was silent — a
;; broken `:head` fn produced a visually-broken page with zero diagnostic.
;; The trace category is catalogued in Spec 009 §Error event catalogue.

(deftest resolve-head-emits-trace-on-throwing-head-fn
  (testing "rf2-axq1y: a throwing :head fn → :rf.error/ssr-head-resolution-failed
            trace fires AND the resolver returns the empty fallback shape
            (preserves rf2-h2ujj's degrade-gracefully contract)"
    (let [resolve-head! (requiring-resolve
                          're-frame.ssr.ring.lifecycle/resolve-head)
          traces        (atom [])
          frame-id      :rf.frame/test-head-throws]
      (rf/register-listener! ::rh
                             (fn [ev]
                               (when (= :rf.error/ssr-head-resolution-failed
                                        (:operation ev))
                                 (swap! traces conj ev))))
      (let [result (try
                     (with-redefs [rf/active-head
                                   (fn [_]
                                     (throw (ex-info "synthetic head failure"
                                                     {:reason :test})))]
                       (resolve-head! frame-id))
                     (finally
                       (rf/unregister-listener! ::rh)))]
        (testing "fallback shape preserved (rf2-h2ujj contract)"
          (is (= "" (:head-html result))
              "empty fragment so a buggy head fn can't take down the request")
          (is (nil? (:html-attrs result))
              "no html-attrs in the fallback shape")
          (is (nil? (:body-attrs result))
              "no body-attrs in the fallback shape"))

        (testing "trace emitted (rf2-bof8i Option B contract)"
          (is (= 1 (count @traces))
              (str "expected one :rf.error/ssr-head-resolution-failed trace;"
                   " saw " (count @traces)))
          (when (seq @traces)
            (let [ev (first @traces)]
              (is (= :error (:op-type ev))
                  "trace is severity :error per Spec 009 §Error event catalogue")
              (is (= :rf.error/ssr-head-resolution-failed (:operation ev))
                  ":operation names the category")
              (is (= frame-id (-> ev :tags :frame))
                  ":frame tag identifies which request the head fn failed for")
              (is (instance? Throwable (-> ev :tags :exception))
                  ":exception tag carries the caught throwable verbatim")
              (is (= "synthetic head failure"
                     (some-> ev :tags :exception .getMessage))
                  "the throwable preserves the underlying failure message")
              (is (= :no-recovery (:recovery ev))
                  ":recovery names the policy — empty fallback is not a recovery"))))))))

(deftest resolve-head-happy-path-no-trace
  (testing "rf2-axq1y: a non-throwing :head fn (mocked to return nil
            so head-model->html produces an empty fragment) → no
            :rf.error/ssr-head-resolution-failed trace fires.
            Belt-and-braces against accidentally emitting the trace on
            the success path."
    (let [resolve-head! (requiring-resolve
                          're-frame.ssr.ring.lifecycle/resolve-head)
          traces        (atom [])
          frame-id      :rf.frame/test-head-ok]
      (rf/register-listener! ::rh-ok
                             (fn [ev]
                               (when (= :rf.error/ssr-head-resolution-failed
                                        (:operation ev))
                                 (swap! traces conj ev))))
      (try
        (with-redefs [rf/active-head (fn [_] nil)]
          (let [result (resolve-head! frame-id)]
            (is (= "" (:head-html result))
                "nil head-model → empty fragment (canonical empty-head shape)")
            (is (nil? (:html-attrs result)))
            (is (nil? (:body-attrs result)))))
        (finally
          (rf/unregister-listener! ::rh-ok)))
      (is (zero? (count @traces))
          "success path must NOT emit the head-resolution-failed trace"))))

;; ===========================================================================
;; rf2-joibj — per-request frame-id keyword is EDN-spec-compliant
;;
;; `setup-request-frame!` builds the frame-id via
;;     (keyword "rf.frame" (str (gensym "f")))
;; The `"f"` prefix is load-bearing: per the EDN spec
;; (https://github.com/edn-format/edn) identifier names cannot begin
;; with a digit, and spec-strict readers (`clojure.edn/read-string`,
;; `cljs.tools.reader.edn/read-string`) reject `:rf.frame/<digits>`.
;; The frame-id ships into the wire payload (Spec 011 §Hydration boot)
;; where the browser's strict EDN reader pulls it back during
;; hydration — without the prefix, the very first call to
;; `cljs.reader/read-string` on the embedded payload would crash.
;; `clojure.core/keyword` does not validate, so a regression here is
;; only observable through a round-trip — which is exactly what this
;; test asserts.
;; ===========================================================================

(deftest hydration-payload-frame-id-keyword-is-edn-readable
  (testing "rf2-joibj: the per-request `:rf.frame/<gensym>` keyword
            embedded in the wire payload survives a round-trip through
            the strict EDN reader. Pre-fix the gensym produced a
            digit-only local-part (`:rf.frame/5401`) which the spec-
            strict reader correctly rejects."
    (register-articles-app! [{:id "a" :title "Article A"}])

    (let [handler     (ssr-ring/ssr-handler
                        {:on-create      [:rf/server-init]
                         :root-view      [:pages/articles]
                         :fx-overrides   {:http/get :http/get.canned}
                         :payload :rf.ssr.payload/whole-app-db})
          response    (handler {:uri "/articles" :request-method :get})
          body        (:body response)
          payload-m   (re-find
                        #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>"
                        body)
          payload-edn (when payload-m (second payload-m))]
      (is (= 200 (:status response)))
      (is (some? payload-edn)
          "hydration payload script tag is present — observation
           surface is the wire payload, not a pre-serialisation map")
      ;; THE PIN: the full payload reads cleanly. Without the `"f"`
      ;; prefix on the gensym, the EDN reader would throw
      ;; `Invalid token: :rf.frame/<digits>` here.
      (let [recovered (clojure.edn/read-string payload-edn)]
        (is (map? recovered)
            "rf2-joibj: payload-EDN round-trips through
             clojure.edn/read-string — the per-request frame-id
             keyword complies with the EDN identifier grammar")
        ;; And the recovered frame-id is the right shape — namespaced,
        ;; local-part starts with a letter.
        (let [fid (:rf/frame-id recovered)]
          (is (keyword? fid)
              "the recovered :rf/frame-id is a keyword")
          (is (= "rf.frame" (namespace fid))
              "the per-request frame-id keeps its `rf.frame` namespace")
          (is (re-matches #"[^\d].*" (name fid))
              (str "the frame-id local-part starts with a non-numeric "
                   "character (saw " (pr-str fid) ")")))))))

;; ===========================================================================
;; rf2-zev2h — default-on-error direct positive assertion
;; (follow-on from rf2-q1z1u F7)
;;
;; `default-on-error` is the Ring-layer fallback used when the SSR
;; error projector cannot see the exception (handler-constructor throw,
;; middleware throw ahead of the projector). The pre-existing
;; render-time-throw tests (`handler-render-error-*` above) pin the
;; projector path with negative assertions of the form
;; `(not= "Internal error" body)` — they verify those paths DON'T fall
;; through to this fixed shape.
;;
;; The literal `"Internal error"` body produced by `default-on-error`
;; itself had no positive pin. A future refactor that changed the
;; literal string (e.g. an i18n table, a richer template) would not
;; surface today. This test pins the literal exact-string contract +
;; the no-leak guarantee at the function boundary directly.
;;
;; rf2-kzvwq / security audit 2026-05-14 §P2.1 — the body MUST NOT
;; carry .getMessage (JDBC URLs, file paths, SQL fragments).
;; ===========================================================================

(deftest default-on-error-fallback-shape-pinned
  (testing "rf2-zev2h — default-on-error returns the fixed-shape 500
            response with the literal `\"Internal error\"` body. The
            throwable's .getMessage MUST NOT appear anywhere in the
            response (status, headers, or body)."
    (let [secret-msg "boom-jdbc-secret:postgres://internal-db.svc:5432/auth"
          t          (ex-info secret-msg {})
          req        {:uri "/anything" :request-method :get}
          response   (ssr-ring/default-on-error req t)]
      (is (= 500 (:status response))
          "status pinned to 500 — Ring-layer fallback default")
      (is (= {"Content-Type" "text/plain; charset=utf-8"}
             (:headers response))
          "headers pinned to text/plain with UTF-8 charset (no HTML,
           no JSON, no leaked Server header)")
      (is (= "Internal error" (:body response))
          "body is the literal `\"Internal error\"` — exact string match
           (a refactor that changes the literal surfaces here)")
      ;; rf2-kzvwq no-leak — the throwable's message MUST NOT appear in
      ;; ANY slot of the response. Asserted on every channel that could
      ;; carry it (status is a number, but headers + body are strings).
      (is (not (str/includes? (:body response) secret-msg))
          "rf2-kzvwq §P2.1: body does NOT carry the throwable's
           .getMessage text — topology disclosure surface closed")
      (is (not (str/includes? (str (:headers response)) secret-msg))
          "rf2-kzvwq §P2.1: headers do NOT carry the throwable's
           .getMessage text either"))))

;; ===========================================================================
;; rf2-c1tac — make-default-on-error templates the body without leaking
;; ===========================================================================
;;
;; Per rf2-c1tac the locked `default-on-error` body ("Internal error",
;; plaintext) is the safe default. Apps that want a branded plaintext-
;; or-HTML default error page without writing a full `:on-error` fn pass
;; a `:on-error-fallback {:body … :content-type …}` opt — the resulting
;; fn is built by `make-default-on-error`, ignores the throwable, and
;; preserves the rf2-kzvwq no-leak contract.

(deftest make-default-on-error-templates-body-and-content-type
  (testing "rf2-c1tac — make-default-on-error returns a fn that responds
            with the configured body + content-type + locked 500 status.
            The fn ignores the throwable, so .getMessage cannot leak
            (rf2-kzvwq §P2.1 contract preserved)."
    (let [secret-msg "boom-jdbc-secret:postgres://internal-db.svc:5432/auth"
          t          (ex-info secret-msg {})
          req        {:uri "/anything" :request-method :get}
          custom-body "<!DOCTYPE html><html><body><h1>Service unavailable</h1></body></html>"
          on-err     (ssr-ring/make-default-on-error
                       {:body custom-body
                        :content-type "text/html; charset=utf-8"})
          response   (on-err req t)]
      (is (= 500 (:status response))
          "status pinned to 500 — the templating surface does NOT let
           callers change the locked status")
      (is (= {"Content-Type" "text/html; charset=utf-8"}
             (:headers response))
          "configured :content-type rides through verbatim")
      (is (= custom-body (:body response))
          "configured :body rides through verbatim — caller-supplied
           HTML can replace the plaintext default")
      (is (not (str/includes? (:body response) secret-msg))
          "rf2-kzvwq §P2.1: configured body does NOT carry the
           throwable's .getMessage — make-default-on-error never
           touches the throwable, only the caller's static template"))))

(deftest make-default-on-error-zero-args-matches-default-on-error
  (testing "rf2-c1tac — `(make-default-on-error)` with no opts produces
            the SAME shape as the host-locked `default-on-error`. The
            locked default stays constant; the templating surface
            extends it without breaking the no-args baseline."
    (let [req       {:uri "/x" :request-method :get}
          t         (ex-info "boom" {})
          via-ctor  ((ssr-ring/make-default-on-error) req t)
          via-host  (ssr-ring/default-on-error req t)]
      (is (= via-ctor via-host)
          "`(make-default-on-error)` returns a fn equivalent to the host
           `default-on-error` — same status, same headers, same body"))))

(deftest ssr-handler-on-error-fallback-opt-templates-default-body
  (testing "rf2-c1tac — ssr-handler resolves `:on-error-fallback` into
            a templated default-on-error fn when the caller didn't
            supply `:on-error`. The handler's transport-failure path
            then emits the templated body (not the locked
            \"Internal error\" string).

            Drive the transport-failure path with a non-vector
            `:on-create` — `validate-on-create!` throws inside
            `setup-request-frame!`'s try/catch, which surfaces the
            short-circuit on-error response. This path is the
            framework's last-resort net for setup-frame failures
            the projector can't see."
    (let [custom-body "<!DOCTYPE html><html><body>Service offline</body></html>"
          handler     (ssr-ring/ssr-handler
                        ;; Non-vector :on-create passes the truthy
                        ;; presence check at handler construction
                        ;; (`validate-required-opts!`) but fails the
                        ;; vector? check inside `validate-on-create!`,
                        ;; which runs inside the per-request setup
                        ;; try/catch — exactly the on-error path.
                        {:on-create '(:rf/server-init)
                         :root-view [:div]
                         :payload [:x]
                         :on-error-fallback {:body custom-body
                                             :content-type "text/html; charset=utf-8"}})
          response    (handler {:uri "/x" :request-method :get})]
      (is (= 500 (:status response))
          "status still pinned to 500 — the templating surface does not
           let callers change the locked status")
      (is (= custom-body (:body response))
          ":on-error-fallback :body templated into the transport-failure
           response, replacing the locked \"Internal error\" default")
      (is (= "text/html; charset=utf-8"
             (get (:headers response) "Content-Type"))
          ":on-error-fallback :content-type templated into the response
           headers"))))

(deftest ssr-handler-explicit-on-error-overrides-on-error-fallback
  (testing "rf2-c1tac — when BOTH `:on-error` and `:on-error-fallback`
            are supplied, the explicit `:on-error` fn wins. The
            templating shortcut is for callers who do NOT want to write
            a Ring fn; an explicit-fn caller has already opted into the
            full surface. Same non-vector-:on-create trigger as the
            previous test."
    (let [explicit-body "EXPLICIT"
          template-body "TEMPLATED"
          handler       (ssr-ring/ssr-handler
                          {:on-create '(:rf/server-init)
                           :root-view [:div]
                           :payload [:x]
                           :on-error (fn [_req _t]
                                       {:status 503
                                        :headers {"Content-Type" "text/plain"}
                                        :body    explicit-body})
                           :on-error-fallback {:body template-body
                                               :content-type "text/html"}})
          response      (handler {:uri "/x" :request-method :get})]
      (is (= 503 (:status response))
          "explicit :on-error fn drove status (503), not the
           templated default's locked 500 — :on-error wins")
      (is (= explicit-body (:body response))
          "explicit :on-error fn body wins over :on-error-fallback :body"))))

;; ===========================================================================
;; rf2-ljjh0 — a throwing caller :on-error must be CONTAINED, not escape
;;
;; `:on-error` is the handler's last-resort transport-failure net. A
;; caller-supplied `:on-error` that ITSELF throws must not propagate out
;; of the handler: an uncaught throwable handed to the Ring server
;; (Jetty / http-kit) surfaces as a raw container 500 with a stack
;; trace, defeating the rf2-kzvwq topology-leak contract the boundary
;; otherwise upholds. The exposure is symmetric — the setup-failure path
;; (`setup-request-frame!`, which runs OUTSIDE the handler's own
;; try/catch) AND the success path both invoke `:on-error`. Both now
;; route through `lifecycle/safe-on-error`, which falls back to the
;; locked `default-on-error` when the caller's fn throws.
;; ===========================================================================

(deftest ssr-handler-throwing-on-error-during-setup-is-contained
  (testing "rf2-ljjh0 — a caller :on-error that THROWS during a
            setup-failure is contained: the handler returns the locked
            default-on-error 500 rather than letting the throwable
            escape as a raw container 500 with leaked internals.

            Setup failure is driven by a non-vector :on-create
            (`validate-on-create!` throws inside `setup-request-frame!`,
            which runs OUTSIDE the handler body's try/catch). The
            caller's :on-error then throws — exercising the one
            :on-error call site that the handler body's catch does not
            wrap."
    (let [throwing-on-error
          (fn [_req _t]
            (throw (ex-info "boom in the caller's on-error — leaks JDBC URL etc."
                            {:internal :topology})))
          handler (ssr-ring/ssr-handler
                    {:on-create '(:rf/server-init) ;; non-vector → setup throws
                     :root-view [:div]
                     :payload [:x]
                     :on-error throwing-on-error})
          ;; Before the fix this call THREW (the throwing on-error
          ;; escaped `setup-request-frame!` uncaught). After the fix it
          ;; returns the contained locked-default 500.
          response (handler {:uri "/x" :request-method :get})]
      (is (map? response)
          "the handler RETURNED a Ring response — the throwing
           :on-error did not escape uncaught")
      (is (= 500 (:status response))
          "contained via the locked default-on-error (500)")
      (is (= "Internal error" (:body response))
          "locked generic body — the secondary throw's internals never
           reach the wire (rf2-kzvwq topology-leak contract held)")
      (is (= "text/plain; charset=utf-8"
             (get (:headers response) "Content-Type"))
          "locked default-on-error content-type"))))

(deftest ssr-handler-throwing-on-error-on-success-path-is-contained
  (testing "rf2-ljjh0 — symmetric: a caller :on-error that throws on the
            SUCCESS path is also contained. Driven by a cookie whose
            :max-age carries CR/LF — it passes the fx boundary
            (`validate-cookie!` gates only :name/:value/:path/:domain)
            but throws at head materialisation. That throw escapes
            `build-full-response`'s own catch (its error-path materialise
            re-folds the SAME bad cookie and throws again), reaching the
            handler body's :on-error catch — the success-path call site."
    (rf/reg-event-fx :rf.test/init-bad-cookie
      {:platforms #{:server}}
      (fn [_ _]
        ;; CR/LF in :max-age escapes the fx boundary (`validate-cookie!`
        ;; gates only :name/:value/:path/:domain) and throws at host
        ;; materialise time in `cookie->set-cookie-header`.
        {:db {}
         :fx [[:rf.server/set-cookie {:name "s" :value "v" :max-age "1\r\n2"}]]}))
    (let [throwing-on-error
          (fn [_req _t]
            (throw (ex-info "boom in the caller's on-error" {:internal :topology})))
          handler (ssr-ring/ssr-handler
                    {:on-create [:rf.test/init-bad-cookie]
                     :root-view [:div "hello"]
                     :payload :rf.ssr.payload/whole-app-db
                     :on-error throwing-on-error})
          ;; Before the fix the throwing :on-error escaped the handler
          ;; body's catch uncaught; after the fix `safe-on-error`
          ;; contains it → locked default 500.
          response (handler {:uri "/x" :request-method :get})]
      (is (map? response)
          "handler returned a Ring response — throwing :on-error on the
           success path did not escape uncaught")
      (is (= 500 (:status response))
          "contained via the locked default-on-error (500)")
      (is (= "Internal error" (:body response))
          "locked generic body — the secondary throw's internals never
           reach the wire (rf2-kzvwq topology-leak contract held)"))))
