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
                     :payload-policy :rf.ssr.payload/whole-app-db})
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
          _ (rf/register-trace-cb! ::destroy-watch
              (fn [ev]
                (when (= :frame/destroyed (:operation ev))
                  (swap! destroyed conj (:frame ev)))))
          handler (ssr-ring/ssr-handler
                    {:on-create    [:rf/server-init]
                     :root-view    [:pages/articles]
                     :fx-overrides {:http/get :http/get.canned}
                     :payload-policy :rf.ssr.payload/whole-app-db})
          frames-before (set (rf/frame-ids))
          response (handler {:uri "/" :request-method :get})
          frames-after (set (rf/frame-ids))]
      (rf/remove-trace-cb! ::destroy-watch)
      (is (= 200 (:status response)))
      ;; No per-request frame leaks into the global frame registry.
      ;; Some destroy-trace-side-channel frame-ids must have been
      ;; recorded — i.e. the handler did create + destroy at least one
      ;; per-request frame.
      (is (= frames-before frames-after)
          "the per-request frame is removed from the registry after destroy")
      (is (seq @destroyed)
          ":frame/destroyed trace fired for the per-request frame"))))

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
                     :payload-policy :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/secret" :request-method :get})]
      (is (= 302 (:status response)))
      (let [headers (:headers response)
            loc     (or (get headers "Location") (get headers "location"))]
        (is (= "/login" loc)))
      (is (= "" (:body response))
          "redirect short-circuits — no body, no payload script")
      (is (not (str/includes? (:body response) "should not render"))))))

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
                      :payload-policy :rf.ssr.payload/whole-app-db})
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

(deftest handler-render-error-projects-to-500
  (testing "exception raised during render is caught and routed through :on-error"
    (rf/reg-event-fx :init/ok
      {:platforms #{:server}}
      (fn [_ _] {}))

    (rf/reg-view* :pages/broken
      (fn [] (throw (ex-info "boom-internal-jdbc-url-secret" {}))))

    (let [handler (ssr-ring/ssr-handler
                    {:on-create [:init/ok]
                     :root-view [:pages/broken]
                     :payload-policy :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/broken" :request-method :get})]
      (is (= 500 (:status response))
          "the default :on-error returns 500")
      ;; rf2-kzvwq / security audit §P2.1 — the default body MUST NOT
      ;; carry the exception's message. .getMessage is documented as
      ;; carrying internal topology (JDBC URLs, file paths, SQL
      ;; fragments); leaking it publicly is the bug.
      (is (= "Internal error" (:body response))
          "rf2-kzvwq: default :on-error body is the fixed generic
           'Internal error' — no .getMessage leak")
      (is (not (str/includes? (:body response) "boom-internal-jdbc-url-secret"))
          "the throwable's message text MUST NOT appear in the
           default 500 body (topology disclosure surface)"))))

(deftest handler-custom-on-error
  (testing ":on-error opt overrides the default 500 path"
    (rf/reg-event-fx :init/ok
      {:platforms #{:server}}
      (fn [_ _] {}))

    (rf/reg-view* :pages/broken-2
      (fn [] (throw (ex-info "boom-2" {:custom true}))))

    (let [handler (ssr-ring/ssr-handler
                    {:on-create [:init/ok]
                     :root-view [:pages/broken-2]
                     :on-error  (fn [_req t]
                                  {:status  418
                                   :headers {"Content-Type" "text/plain"}
                                   :body    (str "caught: " (.getMessage t))})
                     :payload-policy :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/broken" :request-method :get})]
      (is (= 418 (:status response)))
      (is (str/includes? (:body response) "caught: boom-2")))))

;; ===========================================================================
;; ssr-handler — fn-form :root-view invoked exactly once per request (rf2-6t36h)
;;
;; The fn-form branch of `:root-view` is not guaranteed to be idempotent —
;; unsorted-map iteration order, gensym'd react keys, and time-of-day
;; props all vary between calls. Pre-rf2-6t36h the adapter invoked the
;; root-view fn twice per request (once for the wire HTML / its embedded
;; data-rf-render-hash, once for the payload's :rf/render-hash). Two
;; non-identical trees → two non-equal hashes → spurious
;; :rf.ssr/hydration-mismatch on the client for an otherwise successful
;; hydration.
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
                        :payload-policy :rf.ssr.payload/whole-app-db})
            response (handler {:uri "/once" :request-method :get})]
        (is (= 200 (:status response)))
        (is (= 1 @call-count)
            "fn-form :root-view must be invoked exactly once per request
             (rf2-6t36h: was 2 — once for wire HTML, once for hash)")))))

(deftest fn-form-root-view-non-idempotent-still-hashes-consistently
  (testing "rf2-6t36h: a non-idempotent fn-form :root-view produces a wire
            hash that matches the payload hash — the same tree is used for
            both, so the client mismatch check does NOT fire spuriously"
    (let [counter (atom 0)]
      (rf/reg-event-fx :init/ok-noni
        {:platforms #{:server}}
        (fn [_ _] {}))

      ;; A view fn that produces a DIFFERENT tree on every call — its
      ;; key includes a monotonic counter. Pre-rf2-6t36h the wire HTML
      ;; would carry hash(tree_1) and the payload would carry
      ;; hash(tree_2); they would differ and the client's hydration
      ;; verifier would fire a spurious mismatch.
      (rf/reg-view* :pages/noni
        (fn [n] [:div {:data-call n} "noni"]))

      (let [handler  (ssr-ring/ssr-handler
                       {:on-create [:init/ok-noni]
                        :root-view (fn []
                                     [:pages/noni (swap! counter inc)])
                        :payload-policy :rf.ssr.payload/whole-app-db})
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
                        :payload-policy :rf.ssr.payload/whole-app-db})
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
                       :payload-policy :rf.ssr.payload/whole-app-db})]
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
                       :payload-policy :rf.ssr.payload/whole-app-db})]
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
                       :payload-policy :rf.ssr.payload/whole-app-db})]
        (handler {:uri "/" :request-method :get})
        (is (= {:dev-error-detail? true
                :public-error-id   :myapp/projector}
               @captured)
            "the :ssr opt reaches the per-request frame's :ssr metadata
             — the destructure matches the documented key")))))

;; ===========================================================================
;; ssr-handler — payload-keys slice
;; ===========================================================================

(deftest handler-payload-keys-slices-app-db
  (testing ":payload-keys ships a subset of app-db in the hydration payload"
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
                     :payload-keys [:public/articles]})
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
          ":payload-keys omits server-only slices from the wire payload")
      (is (not (str/includes? body "admin-flag"))))))

;; ===========================================================================
;; ssr-handler — explicit fail-closed payload policy (rf2-gtgf9)
;;
;; The wire-level proof that the policy contract is fail-closed:
;;
;;   1. A handler constructed with NEITHER `:payload-keys` NOR
;;      `:payload-policy` throws at construction time
;;      (`:rf.error/ssr-missing-payload-policy`). Misconfigured
;;      deployments fail at boot rather than at first request.
;;
;;   2. **The fail-closed proof** — when a handler is constructed
;;      with an allowlist that does NOT include a server-only key,
;;      that key MUST NOT appear in the hydration payload on the
;;      wire. This is the regression-bite: pre-rf2-gtgf9 the absence
;;      of `:payload-keys` defaulted to whole-app-db, so a server-
;;      only key on app-db rode the wire silently. The new contract
;;      requires the allowlist; an un-permitted slot is provably
;;      excluded by inspection of the wire payload.
;;
;;   3. The opt-in branch — `:payload-policy
;;      :rf.ssr.payload/whole-app-db` ships the whole app-db verbatim
;;      (apps that genuinely want it can opt in explicitly).
;;
;;   4. A typo'd `:payload-policy` keyword surfaces as
;;      `:rf.error/ssr-unknown-payload-policy` — distinct from the
;;      missing-policy bucket so the developer can tell the two
;;      failure modes apart.
;; ===========================================================================

(deftest handler-construction-fails-closed-when-no-policy-supplied
  (testing "rf2-gtgf9: ssr-handler with neither :payload-keys nor
            :payload-policy throws :rf.error/ssr-missing-payload-policy
            at construction time — the canonical fail-closed pattern.
            Misconfigured deployments fail at boot, not at first request."
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

(deftest fail-closed-proof-unpermitted-slot-not-on-wire
  (testing "rf2-gtgf9 FAIL-CLOSED PROOF: a server-only app-db key NOT in
            the :payload-keys allowlist MUST NOT appear in the
            hydration-payload script tag's EDN body. This is the
            regression-bite — pre-rf2-gtgf9, absence of :payload-keys
            shipped the whole app-db, so an unaudited new app-db key
            silently rode the wire on every request."
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
                       :payload-keys [:public/articles]})
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
      ;; NOT appear in the wire payload. If pre-rf2-gtgf9 behaviour
      ;; were still in force, the leak-probe string would be present
      ;; (whole-app-db default). Under rf2-gtgf9 the allowlist is
      ;; load-bearing — this assertion is what the security audit
      ;; asked for.
      (is (not (str/includes? payload-edn "RF2_GTGF9_FAIL_CLOSED_PROBE_xyz789"))
          "rf2-gtgf9 fail-closed proof: an un-permitted server-only
           key's value does NOT appear in the wire hydration payload.
           Pre-rf2-gtgf9 the absence of :payload-keys defaulted to
           whole-app-db — this string would have leaked.")
      (is (not (str/includes? payload-edn "feature-flag"))
          "rf2-gtgf9: the un-permitted key NAME also does not leak —
           neither key nor value reaches the wire")
      (is (not (str/includes? payload-edn "admin-uid"))
          "rf2-gtgf9: belt-and-braces over multiple un-permitted slots"))))

(deftest payload-policy-whole-app-db-opt-in-ships-everything
  (testing "rf2-gtgf9: explicit :payload-policy
            :rf.ssr.payload/whole-app-db is the documented opt-in for
            apps whose entire app-db is intended for the wire"
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
                       :payload-policy :rf.ssr.payload/whole-app-db})
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
  (testing "rf2-gtgf9: a typo'd :payload-policy surfaces as a distinct
            error so the developer can tell the failure modes apart"
    (rf/reg-event-fx :init/typo-policy {:platforms #{:server}} (fn [_ _] {}))
    (rf/reg-view* :pages/typo-policy (fn [] [:div]))

    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/ssr-unknown-payload-policy"
          (ssr-ring/ssr-handler
            {:on-create      [:init/typo-policy]
             :root-view      [:pages/typo-policy]
             :payload-policy :rf.ssr.payload/whole-db})) ; typo
        "typo'd policy keyword does NOT silently fall into the
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
                     :payload-keys [:public/x]}]

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
                     :payload-keys [:public/x]}]
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
        (assoc db :rf/route {:id :route/x})))
    (rf/reg-view* :pages/blank-for-title (fn [] [:div]))

    (let [handler  (ssr-ring/ssr-handler
                     {:on-create [:init/seed-route]
                      :root-view [:pages/blank-for-title]
                      :payload-policy :rf.ssr.payload/whole-app-db})
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
                      :payload-policy :rf.ssr.payload/whole-app-db})
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
        (assoc db :rf/route {:id :route/no-title})))
    (rf/reg-view* :pages/blank-no-title (fn [] [:div]))

    (let [handler  (ssr-ring/ssr-handler
                     {:on-create [:init/seed-no-title]
                      :root-view [:pages/blank-no-title]
                      :payload-policy :rf.ssr.payload/whole-app-db})
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
    (fn [db _] (assoc db :rf/route {:id :route/with-attrs})))
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
                      :payload-policy :rf.ssr.payload/whole-app-db})
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
            is the pre-rf2-h2ujj shape and the default for routes that
            don't opt in."
    (register-attrs-app! {:title "T"}) ; no attr bags

    (let [handler  (ssr-ring/ssr-handler
                     {:on-create [:init/seed-attrs-route]
                      :root-view [:pages/blank-attrs]
                      :payload-policy :rf.ssr.payload/whole-app-db})
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
                      :payload-policy :rf.ssr.payload/whole-app-db})
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
                      :payload-policy :rf.ssr.payload/whole-app-db})
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
                            :payload-policy :rf.ssr.payload/whole-app-db})
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
;; explicit `:payload-keys` filtering, the hydration payload at
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
                       :payload-policy :rf.ssr.payload/whole-app-db})
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
      ;; This is the regression-bite assertion: pre-rf2-jbcmt these strings
      ;; rode the wire as part of `[:rf/response :cookies ...]` /
      ;; `[:rf/response :headers ...]` inside the app-db slice.
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
;; rf2-depii — ensure-content-type case-insensitive normalisation
;;
;; The helper documents itself as case-insensitive but the prior check
;; only matched "content-type" / "Content-Type", so any other casing
;; (CONTENT-TYPE, CoNtEnT-TyPe) would duplicate the header. RFC 7230 §3.2
;; — header names are tokens; tokens are case-insensitive.
;; ===========================================================================

(deftest ensure-content-type-no-duplicate-on-mixed-case
  (testing "rf2-depii: a preexisting Content-Type pair in ANY casing
            short-circuits ensure-content-type — no duplicate appended"
    (let [headers-ns (requiring-resolve 're-frame.ssr.ring.headers/ensure-content-type)]
      (doseq [casing ["content-type"
                      "Content-Type"
                      "CONTENT-TYPE"
                      "CoNtEnT-TyPe"
                      "content-Type"]]
        (let [pairs   [[casing "application/json"]]
              result  (headers-ns pairs "text/html; charset=utf-8")]
          (is (= pairs result)
              (str "casing " (pr-str casing)
                   " short-circuits — pairs returned unchanged, no duplicate"))
          (is (= 1 (count (filter
                            (fn [[k _v]]
                              (= "content-type" (str/lower-case (str k))))
                            result)))
              (str "casing " (pr-str casing)
                   " — exactly one content-type pair after ensure-content-type")))))))

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
                        :payload-policy :rf.ssr.payload/whole-app-db})
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
        ;; specific value's quoted literal and read it — the full
        ;; payload contains a generated `:rf.frame/<numeric-gensym>`
        ;; keyword that the strict EDN reader (correctly) rejects, and
        ;; isn't what this test is about anyway. `<` must be
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
      (rf/register-trace-cb! ::dfq (fn [ev] (swap! traces conj ev)))
      (try
        (with-redefs [rf/destroy-frame!
                      (fn [_] (throw (ex-info "synthetic destroy failure"
                                              {:reason :test})))]
          (destroy-quietly! f))
        (finally
          (rf/remove-trace-cb! ::dfq)))

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
  events. Mirrors the `register-trace-cb!` pattern used by the
  :rf.ssr/destroy-frame-failed test above."
  [body-fn]
  (let [traces (atom [])]
    (rf/register-trace-cb! ::csp-watch (fn [ev] (swap! traces conj ev)))
    (try (body-fn)
         (finally (rf/remove-trace-cb! ::csp-watch)))
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
      (rf/register-trace-cb! ::rh
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
                       (rf/remove-trace-cb! ::rh)))]
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
      (rf/register-trace-cb! ::rh-ok
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
          (rf/remove-trace-cb! ::rh-ok)))
      (is (zero? (count @traces))
          "success path must NOT emit the head-resolution-failed trace"))))
