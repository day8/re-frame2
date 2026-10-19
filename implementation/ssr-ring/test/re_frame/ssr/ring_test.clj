(ns re-frame.ssr.ring-test
  "Ring host adapter: cookie serialisation, ssr-handler and ssr-middleware
  end to end, and the default HTML shell."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.lifecycle :as rf.ssr.ring.lifecycle]
            [re-frame.ssr.ring.pipeline :as rf.ssr.ring.pipeline]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(def ^:private req {:uri "/" :request-method :get})

(def ^:private minimal {:frame {:initial-events []} :root-view [:div] :payload [:x]})

(defn- seeded
  "`minimal`, its request frame booted by `events`."
  [events]
  (assoc-in minimal [:frame :initial-events] events))

(defn- serve [opts] ((rf.ssr.ring/ssr-handler opts) req))

(defn- thrown-data
  "The ex-data of the ExceptionInfo `f` throws, or nil when it returns."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- error-id [f] (:rf.error/id (thrown-data f)))

(defn- header-values
  "Every value of header `header-name` on a Ring response, in any casing."
  [response header-name]
  (keep (fn [[k v]] (when (= header-name (str/lower-case k)) v)) (:headers response)))

(defn- payload-edn [body]
  (second (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>" body)))

(defn- payload [body] (some-> (payload-edn body) edn/read-string))

(defn- route-with-head!
  "Register `route-id` with a `:head` fn and return the :initial-events that
  make it the current route."
  [route-id head-fn]
  (let [head-id (keyword "head" (name route-id))]
    (rf/reg-head head-id head-fn)
    (rf/reg-route route-id {:doc "route" :head head-id} "/"))
  (rf/reg-event :init/route
    (fn [{rt :rf.db/runtime} [_ id]]
      {:rf.db/runtime (assoc-in rt [:rf.runtime/routing :current] {:route-id id})}))
  [[:init/route route-id]])

;; ===========================================================================
;; Cookie serialisation (RFC 6265)
;; ===========================================================================

(deftest cookie-set-cookie-header-canonical
  ;; The value is percent-encoded (space as %20, and a `;` stays data).
  (is (= "s=a%20b%26c%3Bd; Max-Age=3600; Domain=example.com; Path=/; Secure; HttpOnly; SameSite=Lax"
         (rf.ssr.ring/cookie->set-cookie-header
           {:name "s" :value "a b&c;d" :max-age 3600 :secure true :http-only true
            :same-site :lax :path "/" :domain "example.com"})))
  (is (= :rf.error/cookie-missing-name
         (error-id #(rf.ssr.ring/cookie->set-cookie-header {:value "abc"})))))

(deftest cookie-attribute-injection-rejected
  ;; Attributes are concatenated verbatim, so CR/LF/NUL would split the
  ;; header and a `;` would forge extra attributes (SameSite=None, Secure …).
  (is (= [] (remove #(= :rf.error/cookie-invalid-attribute
                        (error-id (fn [] (rf.ssr.ring/cookie->set-cookie-header
                                           (merge {:name "sid" :value "v"} %)))))
                    [{:domain "evil.com\rx"}
                     {:domain "evil.com\nx"}
                     {:domain (str "evil.com" (char 0))}
                     {:domain "evil.com; Secure"}
                     {:path "/; SameSite=None; Secure"}
                     {:max-age "3600; Secure"}
                     {:same-site "Lax; Secure"}]))
      "lists any hostile attribute that serialised")
  (is (= {:attribute :path :value "/; Secure"}
         (select-keys (thrown-data #(rf.ssr.ring/cookie->set-cookie-header
                                      {:name "sid" :value "v" :path "/; Secure"}))
                      [:attribute :value]))))

(deftest cookie-name-rfc6265-token-grammar
  (is (= [] (remove #(= :rf.error/cookie-invalid-name
                        (error-id (fn [] (rf.ssr.ring/cookie->set-cookie-header {:name % :value "v"}))))
                    ["session id" "session;x" "session\r"]))
      "lists any invalid name that serialised")
  (is (= ["X-CSRF_tok.1=v" "session=v"]
         (map #(rf.ssr.ring/cookie->set-cookie-header {:name % :value "v"}) ["X-CSRF_tok.1" :session])))
  (is (= {:rf.error/id :rf.error/cookie-invalid-name :name 42}
         (select-keys (thrown-data #(rf.ssr.ring/cookie->set-cookie-header {:name 42 :value "v"}))
                      [:rf.error/id :name]))
      "a non-Named name is a structured error, not a ClassCastException"))

;; ===========================================================================
;; ssr-handler — the page, the frame, redirects
;; ===========================================================================

(deftest handler-renders-html-with-status-and-headers
  (rf/reg-fx :http/get {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/reg-fx :http/get.canned {:platforms #{:server :client}}
    (fn [{:keys [frame]} {:keys [on-success]}]
      (rf/dispatch (conj on-success [{:title "Article A"}]) {:frame frame})))
  (rf/reg-event :init/fetch {:platforms #{:server}}
    (fn [_ _] {:fx [[:http/get {:on-success [:articles/loaded]}]]}))
  (rf/reg-event :articles/loaded (fn [{:keys [db]} [_ articles]] {:db (assoc db :articles articles)}))
  (rf/reg-sub :articles (fn [db _] (:articles db)))
  (rf/reg-view* :pages/articles
    (fn [] (into [:ul] (for [{:keys [title]} (rf/subscribe-once [:articles])] [:li title]))))
  (let [response (serve {:frame     {:initial-events [[:init/fetch]]
                                     :fx-overrides   {:http/get :http/get.canned}}
                         :root-view (fn [] ((rf/view :pages/articles)))
                         :payload   :rf.ssr.payload/whole-app-db})
        body     (:body response)]
    (is (= 200 (:status response)))
    (is (= ["text/html; charset=utf-8"] (header-values response "content-type")))
    (is (str/starts-with? body "<!DOCTYPE html>"))
    (is (str/includes? body "<li>Article A</li>"))
    (is (re-find #"<head[^>]*><meta charset=\"utf-8\">" body)
        "the shell owns the charset, first in the head")
    (is (= 1 (count (re-seq #"(?i)<meta\s+charset" body))) "and the default head adds none")))

(deftest handler-per-request-teardown-is-incarnation-exact
  ;; Teardown destroys the make-frame VALUE, whose incarnation token keeps it
  ;; from reaping a same-id successor seated during the request.
  (let [destroyed    (atom [])
        real-destroy rf/destroy-frame!
        before       (set (rf/frame-ids))]
    (with-redefs [rf/destroy-frame! (fn [target & more]
                                      (swap! destroyed conj target)
                                      (apply real-destroy target more))]
      (serve minimal))
    (is (= before (set (rf/frame-ids))) "no request frame outlives the request")
    (is (some? (rf.frame/frame-value-incarnation-token (first @destroyed))))))

(deftest handler-redirect-short-circuits
  (rf/reg-event :init/redirect {:platforms #{:server}}
    (fn [_ _] {:fx [[:rf.server/redirect {:status 302 :location "/login"}]]}))
  (let [response (serve (assoc (seeded [[:init/redirect]])
                                :root-view [:div "should not render"]))]
    (is (= [302 ["/login"] ""]
           [(:status response) (header-values response "location") (:body response)]))))

(deftest handler-redirect-no-target-warns
  (rf/reg-event :init/redirect {:platforms #{:server}}
    (fn [_ _] {:fx [[:rf.server/redirect {:status 302}]]}))
  (let [ops (atom [])]
    (rf/register-listener! :trace ::no-target #(swap! ops conj (:operation %)))
    (let [response (serve (seeded [[:init/redirect]]))]
      (rf/unregister-listener! :trace ::no-target)
      (is (= [302 []] [(:status response) (header-values response "location")]))
      (is (some #{:rf.ssr/ssr-redirect-no-target} @ops)))))

;; ===========================================================================
;; ssr-handler — render errors
;; ===========================================================================

(defn- throwing-root [] (throw (ex-info "root boom" {})))

(deftest handler-render-error-custom-projector-shapes-body
  ;; A render throw takes the error page even when the projector picks a 4xx.
  (rf/reg-error-projector :myapp/teapot
    (fn [_] {:status 418 :code :teapot :message "I'm a teapot" :retryable? false}))
  (let [response (serve (-> minimal
                            (assoc :root-view throwing-root)
                            (assoc-in [:frame :ssr] {:public-error-id :myapp/teapot})))]
    (is (= 418 (:status response)))
    (is (str/includes? (:body response) "<h1>I&#39;m a teapot</h1>"))))

(deftest handler-render-error-renders-registered-error-view
  ;; The error page is the whole body (so it carries its own doctype) and sees
  ;; only the public error; a throwing :error-view falls back once to the
  ;; default template.
  (rf/reg-view* :myapp/error-page (fn [{:keys [code message]}] [:main.kw (name code) " " message]))
  (let [failures (atom 0)]
    (rf/register-listener! :trace ::error-view-failed
      #(when (= :rf.error/ssr-ring-error-view-failed (:operation %)) (swap! failures inc)))
    (doseq [[error-view marker]
            [[:myapp/error-page "<main class=\"kw\""]
             [(fn [{:keys [code message]}] [:main.fn (name code) " " message]) "<main class=\"fn\""]
             [nil "<h1>"]
             [(fn [_] (throw (ex-info "error-view boom" {}))) "<h1>"]]]
      (let [{:keys [status body]} (serve (assoc minimal :root-view throwing-root
                                                        :error-view error-view))]
        (is (= 500 status))
        (is (str/starts-with? body "<!DOCTYPE html>"))
        (is (= [] (remove #(str/includes? body %) [marker "internal-error" "Something went wrong"])))
        (is (not (str/includes? body "boom")) "no throwable text on the wire")))
    (rf/unregister-listener! :trace ::error-view-failed)
    (is (= 1 @failures) "only the throwing error view reports")))

(deftest handler-payload-number-refusal-is-a-projected-500
  ;; ssr-handler builds the payload before committing anything, so a refused
  ;; payload is an ordinary projected 500.
  (rf/reg-event :init/wide-id {:platforms #{:server}}
    (fn [_ _] {:db {:order {:id 9007199254740993}}}))
  (let [{:keys [status body]} (serve (assoc (seeded [[:init/wide-id]])
                                             :payload [:order]))]
    (is (= 500 status))
    (is (str/includes? body "Something went wrong"))
    (is (= [] (filter #(str/includes? body %) ["9007199254740993" "__rf_payload"])))))

;; ===========================================================================
;; ssr-handler — root-view resolution and the hydrate! round trip
;; ===========================================================================

(deftest fn-form-root-view-is-resolved-once-per-request
  (let [calls (atom 0)]
    (rf/reg-view* :pages/noni (fn [n] [:div {:data-call n} "noni"]))
    (let [body (:body (serve (assoc minimal :root-view
                                    (fn [] ((rf/view :pages/noni) (swap! calls inc))))))]
      (is (re-find #"<div data-call=\"1\"[^>]*>noni</div>" body))
      (is (= 1 @calls)))))

(deftest ssr-handler-payload-hydrates-the-client-frame
  ;; The real wire payload into the documented client boot, with a stable
  ;; frame id; `hydrate!` throws on a frame-id mismatch. A route head is on
  ;; the page.
  (rf/reg-event :init/count (fn [{:keys [db]} _] {:db (assoc db :count 42)}))
  (rf/reg-view* :app/root (fn [] [:div.app "stable"]))
  (let [serve-payload (fn [opts]
                        (payload (:body (serve (merge {:frame
                                                       {:initial-events
                                                        (into [[:init/count]]
                                                              (route-with-head! :route/r (fn [_ _] {:title "Page"})))}
                                                       :root-view (fn [] ((rf/view :app/root)))
                                                       :payload   :rf.ssr.payload/whole-app-db}
                                                      opts)))))
        hydrate       (fn [frame-id p]
                        (rf/make-frame {:id frame-id :platform :client})
                        (rf.ssr/hydrate! {:frame frame-id :payload p}))]
    (let [p (serve-payload {})]
      (is (= p (hydrate :app/main p)))
      (is (= 42 (:count (rf/app-db-value :app/main)))))
    (testing "a configured :client-frame-id rides the wire and hydrates the frame it names"
      (let [p (serve-payload {:client-frame-id :app/stamped})]
        (is (= :app/stamped (:rf/frame-id p)))
        (is (= p (hydrate :app/stamped p)))))))

;; ===========================================================================
;; ssr-handler — the request, :frame and its :initial-events
;; ===========================================================================

(deftest handler-surfaces-the-request-via-cofx-and-clears-its-slot
  (let [seen    (atom nil)
        request {:uri "/a" :request-method :get :headers {"cookie" "session=abc"}}]
    (rf/reg-event :init/capture {:platforms #{:server} :rf.cofx/requires [:rf.server/request]}
      (fn [{frame :rf.frame/id request :rf.server/request} _] (reset! seen [frame request]) {}))
    ((rf.ssr.ring/ssr-handler (seeded [[:init/capture]])) request)
    (is (= request (second @seen)))
    (is (some? (first @seen)) "a nil frame id would make the slot read below vacuous")
    (is (nil? (rf.ssr/get-request (first @seen))) "the slot dies with the request frame")))

(deftest initial-events-fn-form-derives-vector-from-request
  ;; Called once per request, with that request; its result is the boot.
  (let [calls (atom []) booted (atom [])]
    (rf/reg-event :init/uri {:platforms #{:server}} (fn [_ [_ uri]] (swap! booted conj uri) {}))
    (let [handler (rf.ssr.ring/ssr-handler
                    (seeded (fn [{:keys [uri]}]
                              (swap! calls conj uri)
                              [[:init/uri uri]])))]
      (handler {:uri "/p" :request-method :get})
      (handler {:uri "/q" :request-method :get})
      (is (= [["/p" "/q"] ["/p" "/q"]] [@calls @booted])))))

(deftest initial-events-resolve-fn-unit-contract
  (doseq [[bad recovery] [[(fn [_] {:nope true}) :return-an-initial-events-vector-from-the-fn]
                          ['(:list-not-vector) :supply-a-vector-or-a-fn-of-the-request]]]
    (is (= {:rf.error/id :rf.error/invalid-initial-events :recovery recovery}
           (select-keys (thrown-data #(rf.ssr.ring.lifecycle/resolve-initial-events! bad req))
                        [:rf.error/id :recovery])))))

(deftest resolve-root-view-invalid-shape-unit-contract
  (is (= {:rf.error/id :rf.error/invalid-root-view
          :where       'rf.ssr/ssr-handler
          :recovery    :supply-a-hiccup-vector-or-0-arity-fn
          :received    :app/root}
         (select-keys (thrown-data #(rf.ssr.ring.lifecycle/resolve-root-view :app/root))
                      [:rf.error/id :where :recovery :received]))))

;; ===========================================================================
;; Construction-time validation and the payload allowlist
;; ===========================================================================

(deftest handler-construction-fails-closed-on-a-missing-or-unknown-required-opt
  (is (= [:rf.error/ssr-missing-payload-policy
          :rf.error/ssr-unknown-payload-policy
          :rf.error/ssr-ring-missing-initial-events]
         (for [[construct opts] [[rf.ssr.ring/ssr-handler (dissoc minimal :payload)]
                                 [rf.ssr.ring/ssr-handler (assoc minimal :payload :rf.ssr.payload/whole-db)]
                                 [rf.ssr.ring/ssr-handler (update minimal :frame dissoc :initial-events)]]]
           (error-id #(construct opts))))))

(deftest handler-takes-its-frame-config-as-one-frame-map
  (testing "the four frame-config keys refuse at the handler's top level, naming :frame"
    (doseq [[k v] [[:initial-events [[:init/x]]]
                   [:fx-overrides {:http/get :http/get.canned}]
                   [:ssr {:public-error-id :myapp/teapot}]
                   [:url-strategy nil]]]
      (let [e (try (rf.ssr.ring/ssr-handler (assoc minimal k v)) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= {:rf.error/id :rf.error/ssr-ring-frame-opt-invalid
                :recovery    :move-the-frame-config-into-frame
                :keys        [k]}
               (select-keys (ex-data e) [:rf.error/id :recovery :keys]))
            (pr-str k))
        (is (str/includes? (str (ex-message e)) ":frame") (pr-str k)))))
  (testing "the handler owns the request frame's :id and :platform"
    (doseq [k [:id :platform]]
      (is (= {:rf.error/id :rf.error/ssr-ring-frame-opt-invalid
              :recovery    :remove-the-handler-owned-key
              :keys        [k]}
             (select-keys (thrown-data #(rf.ssr.ring/ssr-handler
                                          (assoc-in minimal [:frame k] :app/main)))
                          [:rf.error/id :recovery :keys]))
          (pr-str k))))
  (testing ":frame is a map"
    (is (= :rf.error/ssr-ring-frame-opt-invalid
           (error-id #(rf.ssr.ring/ssr-handler (assoc minimal :frame [[:init/x]])))))))

(deftest handler-construction-rejects-non-string-trusted-shell-opts
  ;; Strings or nil only; `:script-src false` (no bootstrap script) is the one
  ;; exception, and it is :script-src's alone.
  (doseq [[k bad] [[:head {:title "x"}] [:script-src :main.js] [:app-element-id false]]]
    (is (= {:rf.error/id :rf.error/ssr-trusted-shell-opt-invalid
            :opt-key k :got bad :recovery :supply-string-or-nil}
           (select-keys (thrown-data #(rf.ssr.ring/ssr-handler (assoc minimal k bad)))
                        [:rf.error/id :opt-key :got :recovery])))))

;; ===========================================================================
;; The default shell
;; ===========================================================================

(defn- script-srcs [html] (mapv second (re-seq #"src=\"([^\"]*)\"" html)))

(deftest explicit-nil-shell-opts-render-the-defaults
  ;; `{:script-src (:script-src cfg)}` with the key absent from cfg is an
  ;; explicit nil, and must render exactly as the absent key does.
  (let [body (:body (serve (assoc minimal :head nil :body-end nil :script-src nil
                                  :app-element-id nil :lang nil :html-shell nil)))]
    (is (= ["/main.js"] (script-srcs body)))
    (is (str/includes? body "<div id=\"app\""))
    (is (str/includes? body "<html lang=\"en\">"))))

(deftest script-src-false-emits-no-bootstrap-script
  (let [body (:body (serve (assoc minimal :script-src false
                                  :body-end "<script type=\"module\" src=\"/module.js\"></script>")))]
    (is (= ["/module.js"] (script-srcs body)))
    (is (some? (payload-edn body)))))

(deftest default-html-shell-escapes-its-attribute-hooks
  (let [opts  {:app-element-id "ro\"ot"
               :script-src     "/boot.js?a=1&b=2"}
        shell (rf.ssr.ring/default-html-shell "<main>Hi</main>" "{:x 1}" opts)]
    (is (str/includes? shell "<div id=\"ro&quot;ot\">"))
    (is (str/includes? shell "src=\"/boot.js?a=1&amp;b=2\""))))

(deftest a-page-title-comes-from-the-route-head-or-the-head-string
  ;; The shell never titles a page and the request frame has no :doc, so a
  ;; document has at most the one title its head declares.
  (doseq [[opts titles]
          [[{:frame {:initial-events []}} []]
           [{:frame {:initial-events (route-with-head! :route/titled (fn [_ _] {:title "My Page"}))}}
            ["<title>My Page</title>"]]
           [{:frame {:initial-events []} :head "<title>X</title>"} ["<title>X</title>"]]]
          :let [body (:body (serve (merge minimal opts)))]]
    (is (= titles (vec (re-seq #"<title.*?</title>" body))) (pr-str titles))))

(deftest default-shell-stamps-the-head-model-attr-bags
  ;; The bags go through the hiccup emitter's attr-string: values escaped, nil
  ;; omitted, data-* booleans stringified, true boolean attributes by presence.
  ;; A bag's :lang wins; the :lang opt fills a bag without one.
  (doseq [[head-model opts expected]
          [[{:html-attrs {:lang "fr" :data-q "a \"b\" & c" :data-off false :data-nil nil}
             :body-attrs {:class "ok" :hidden false :inert true}}
            {}
            ["<html lang=\"fr\" data-q=\"a &quot;b&quot; &amp; c\" data-off=\"false\">"
             "<body class=\"ok\" inert>"]]
           [{:html-attrs {:data-theme "dark"}} {:lang "ja"} ["lang=\"ja\"" "data-theme=\"dark\""]]]]
    (let [body (:body (serve (merge minimal opts
                                    {:frame {:initial-events (route-with-head! :route/attrs (fn [_ _] head-model))}})))]
      (is (= [] (remove #(str/includes? body %) expected))))))

(deftest middleware-falls-through-on-non-match
  (let [app ((rf.ssr.ring/ssr-middleware (assoc minimal :match? #(= "/ssr" (:uri %))))
             (fn [_] {:status 204 :headers {} :body ""}))]
    (is (= [204 200] (map #(:status (app {:uri % :request-method :get})) ["/api" "/ssr"])))))

;; ===========================================================================
;; Response headers and the hydration payload
;; ===========================================================================

(deftest hydration-payload-omits-rf-response-accumulator
  ;; Response metadata lives in a side channel, never app-db, so even a
  ;; whole-app-db payload cannot carry cookies or server-only headers.
  (rf/reg-event :auth/login {:platforms #{:server}}
    (fn [{:keys [db]} _]
      {:db (assoc db :public/title "Public Title")
       :fx [[:rf.server/set-cookie {:name "session" :value "SECRET_TOKEN_xyz" :http-only true}]
            [:rf.server/set-header {:name "X-Secret" :value "INTERNAL_abc"}]]}))
  (let [response (serve (assoc (seeded [[:auth/login]])
                                :payload :rf.ssr.payload/whole-app-db))
        p        (payload-edn (:body response))]
    (is (str/includes? p "Public Title"))
    (is (= [] (filter #(str/includes? p %) [":rf/response" "SECRET_TOKEN_xyz" "INTERNAL_abc"])))
    (is (= [["session=SECRET_TOKEN_xyz; HttpOnly"] ["INTERNAL_abc"]]
           (map #(header-values response %) ["set-cookie" "x-secret"])))))

(deftest ssr-handler-honors-custom-content-type-opt
  ;; The opt replaces the runtime's seeded text/html; absent, an app-set
  ;; Content-Type stays in control.
  (rf/reg-event :init/json {:platforms #{:server}}
    (fn [_ _] {:fx [[:rf.server/set-header {:name "Content-Type" :value "application/json"}]]}))
  (doseq [[opts expected] [[{:content-type "application/xhtml+xml"} ["application/xhtml+xml"]]
                           [{:frame {:initial-events [[:init/json]]}} ["application/json"]]]]
    (is (= expected (header-values (serve (merge minimal opts)) "content-type")))))

(deftest ssr-handler-strips-stale-app-set-content-length
  ;; The body is assembled after the drain, so an app-set length can never
  ;; match it; the server would truncate or stall the page.
  (rf/reg-event :init/cl {:platforms #{:server}}
    (fn [_ _] {:fx [[:rf.server/set-header {:name "Content-Length" :value "7"}]
                    [:rf.server/append-header {:name "content-length" :value "13"}]]}))
  (let [response (serve (seeded [[:init/cl]]))]
    (is (= [200 []] [(:status response) (header-values response "content-length")]))))

(deftest hydration-payload-edn-token-with-angle-round-trips
  ;; A `</script>` in a string cannot close the payload element, and a `<` in
  ;; a keyword token survives (a whole-string escape would corrupt it).
  (let [value {:a<b 1 :tag :< :title "</script><script>alert(1)</script>"}]
    (is (= value (payload (rf.ssr.ring/default-html-shell "body" (pr-str value) {}))))))

(deftest hydration-payload-edn-token-breakout-fails-loud
  ;; A symbol prints a bare `</` that no in-token EDN escape can neutralise.
  (is (= :rf.error/ssr-edn-script-breakout
         (error-id #(rf.ssr.ring/default-html-shell "body" (pr-str {:k (symbol "a</script>b")}) {})))))

;; ===========================================================================
;; Degradation and containment
;; ===========================================================================

(deftest destroy-frame-quietly-emits-trace-on-throwing-destroy
  (let [traces (atom [])]
    (rf/register-listener! :trace ::dfq #(swap! traces conj %))
    (with-redefs [rf/destroy-frame! (fn [_] (throw (ex-info "destroy boom" {})))]
      (is (nil? (rf.ssr.ring.lifecycle/destroy-frame-quietly! :rf.frame/f)) "swallowed"))
    (rf/unregister-listener! :trace ::dfq)
    (is (= [[:warning :warned-and-skipped
             {:frame :rf.frame/f :reason "destroy boom" :ex-class "clojure.lang.ExceptionInfo"}]]
           (for [ev @traces :when (= :rf.ssr/destroy-frame-failed (:operation ev))]
             [(:op-type ev) (:recovery ev) (select-keys (:tags ev) [:frame :reason :ex-class])])))))

(deftest resolve-head-emits-trace-on-throwing-head-fn
  ;; A broken head fn degrades to an empty head and reports on the dev trace
  ;; and on the always-on axis.
  (let [traces  (atom [])
        records (atom [])
        boom    (ex-info "head boom" {})]
    (rf/register-listener! :trace ::rh #(swap! traces conj %))
    (rf.error-emit/register-error-listener! ::rh #(swap! records conj %))
    (is (= {:head-html "" :html-attrs nil :body-attrs nil}
           (with-redefs [rf.ssr/head-model (fn [_] (throw boom))]
             (rf.ssr.ring.lifecycle/resolve-head :rf.frame/f))))
    (rf/unregister-listener! :trace ::rh)
    (rf.error-emit/unregister-error-listener! ::rh)
    (is (= [[:error :no-recovery {:frame :rf.frame/f :exception boom}]]
           (for [ev @traces :when (= :rf.error/ssr-head-resolution-failed (:operation ev))]
             [(:op-type ev) (:recovery ev) (select-keys (:tags ev) [:frame :exception])])))
    (is (= [[:rf.frame/f boom]]
           (for [r @records :when (= :rf.error/ssr-head-resolution-failed (:error r))]
             [(:frame r) (:exception r)])))))

(deftest handler-throwing-head-fn-ships-degraded-200-not-projected-error
  ;; The handler re-flushes the accumulator after rendering, so a buffered
  ;; head failure would project a 500; it must stay a degraded 200.
  (let [{:keys [status body]}
        (serve (assoc (seeded (route-with-head! :route/broken-head
                                                (fn [_ _] (throw (ex-info "head boom" {})))))
                      :root-view [:div "Body rendered fine"]))]
    (is (= 200 status))
    (is (str/includes? body "Body rendered fine"))
    (is (not (str/includes? body "head boom")))))

(deftest ssr-handler-throwing-on-error-is-contained
  ;; A throwing caller :on-error falls back to the locked default instead of
  ;; escaping to the server as a raw 500 that leaks its internals.
  (rf/reg-event :init/bad-cookie {:platforms #{:server}}
    (fn [_ _] {:fx [[:rf.server/set-cookie {:name "s" :value "v" :expires "not-an-int"}]]}))
  (doseq [initial-events ['(:not-a-vector)       ; fails in setup, outside the body's catch
                          [[:init/bad-cookie]]]]  ; fails materialising, inside it
    (is (= {:status 500 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "Internal error"}
           (serve (assoc (seeded initial-events)
                         :on-error (fn [_ _] (throw (ex-info "on-error boom" {})))))))))

(deftest setup-failure-catch-does-not-reap-same-id-successor
  ;; make-frame rolls a failed incarnation back itself; a same-id successor
  ;; seated in that window must survive the setup-failure catch.
  (let [successor (atom nil)
        real-make rf/make-frame]
    (with-redefs [rf/make-frame (fn [{:keys [id]}]
                                  (real-make {:id id :platform :server})
                                  (reset! successor [id (rf.frame/frame-incarnation-token id)])
                                  (throw (ex-info "incarnation A failed" {})))]
      (rf.ssr.ring.pipeline/setup-request-frame! {:frame {:initial-events []} :on-error (fn [_ _] {:status 500})}
                                                 req))
    (is (apply rf.frame/frame-incarnation-live? @successor))
    (rf/destroy-frame! (first @successor))))
