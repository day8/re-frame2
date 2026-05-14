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
    (fn [{:keys [db]} [_ request]]
      {:db (assoc db :request request)
       :fx [[:http/get {:url        "/api/articles"
                        :on-success [:articles/loaded]}]]}))

  (rf/reg-event-db :articles/loaded
    (fn [db [_ articles]]
      (assoc db :articles articles)))

  (rf/reg-sub :articles (fn [db _] (:articles db)))

  (rf/reg-view* :pages/articles
    (fn []
      (let [arts (rf/subscribe-value [:articles])]
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
                     :fx-overrides {:http/get :http/get.canned}})
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
                     :fx-overrides {:http/get :http/get.canned}})
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
                     :root-view [:pages/should-not-render]})
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
                      :root-view [:pages/cookied]})
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
      (fn [] (throw (ex-info "boom" {}))))

    (let [handler (ssr-ring/ssr-handler
                    {:on-create [:init/ok]
                     :root-view [:pages/broken]})
          response (handler {:uri "/broken" :request-method :get})]
      (is (= 500 (:status response))
          "the default :on-error returns 500")
      (is (str/includes? (:body response) "SSR error")
          "default :on-error body carries the error message"))))

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
                                   :body    (str "caught: " (.getMessage t))})})
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
                                     [:pages/once])})
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
                                     [:pages/noni (swap! counter inc)])})
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
                        :root-view [:pages/blank]})
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
                       :root-view [:pages/blank2]})]
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
                       :root-view [:pages/blank3]})]
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
                                   :public-error-id   :myapp/projector}})]
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
                      :root-view [:pages/blank-for-title]})
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
                      :root-view [:pages/blank-no-head]})
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
                      :root-view [:pages/blank-no-title]})
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
                      :root-view [:pages/blank-attrs]})
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
                      :root-view [:pages/blank-attrs]})
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
                      :lang      "ja"})
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
                      :root-view [:pages/blank-attrs]})
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
                            :match?       (fn [req] (= "/ssr" (:uri req)))})
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
                       :root-view [:pages/dashboard]})
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
