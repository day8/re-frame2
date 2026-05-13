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
            [re-frame.frame :as frame]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.ring :as ssr-ring]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! ssr/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

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
