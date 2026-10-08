(ns re-frame.resources-route-infinite-blocking-cljs-test
  "A route blocking on an infinite feed (Spec 016 §Route integration, §Infinite
  resources and load-more feeds). The feed drains through the page reply
  handlers, not the scalar ones: page-0 success drains the route to :idle, and
  a page-0 failure is a first load, so it settles the :error channel (never
  :page-error) and flips the route to :error exactly as a blocking scalar
  resource would."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.route :as rf.resources.route]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.routing :as rf.routing]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(def ^:private last-managed-args (atom nil))

(defn- init! []
  (reset! last-managed-args nil)
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "Route-infinite-blocking suite default app frame."})
  (rf.routing/reset-counters!)
  (rf.resources.route/install-routing-integration!)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

(defn- slice []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current]))

(defn- entry [scoped-key]
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) (rf.resources.state/entry-path scoped-key)))

(defn- blocking-slot
  "The scoped keys in the live blocking slot for `nav-token`."
  [nav-token]
  (set (vals (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                     (rf.resources.route/blocking-path nav-token)))))

(defn- page [items next-c] {:items items :page-info {:next-cursor next-c}})

(def ^:private k
  (rf.resources.state/scoped-resource-key :rf.scope/global :feed/articles {:slug "intro"}))

(defn- reply-page!
  "Reply to the captured page-0 request through `callback` (:on-success or
  :on-failure), first checking it is addressed at the page handler `handler`."
  [callback handler reply]
  (let [args @last-managed-args]
    (is (= handler (first (callback args))) "page 0 is addressed at the page reply handler")
    (rf/dispatch-sync (conj (callback args) reply))))

(defn- navigate-to-blocking-feed!
  "Register a route blocking on an infinite feed and navigate to it; return
  the nav-token after asserting the route holds :loading on page 0."
  []
  (rf/reg-resource :feed/articles
    {:scope           :rf.scope/global
     :infinite        true
     :params-schema   [:map [:slug :string]]
     :next-page-param (fn [last-page _all-pages] (get-in last-page [:page-info :next-cursor]))
     :page->items     :items
     :tags            (fn [{:keys [slug]} _data] #{[:feed slug]})}
    (fn [{:keys [slug]} {:rf.resource/keys [page-param page-index]}]
      {:request {:method :get :url (str "/api/feed/" slug)
                 :params (cond-> {:page-index page-index}
                           page-param (assoc :cursor page-param))}}))
  (rf/reg-route :route/feed
                {:params    [:map [:slug :string]]
                 :resources [{:resource  :feed/articles
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? true}]} "/feed/:slug")
  (rf/dispatch-sync [:rf.route/navigate {:to :route/feed :params {:slug "intro"}}])
  (let [nav-token (:nav-token (slice))]
    (is (= [:loading true] [(:transition (slice)) (contains? (blocking-slot nav-token) k)])
        "the route holds :loading while the blocking page 0 is in flight")
    nav-token))

(deftest blocking-infinite-route-holds-then-drains-on-page-0-success
  (let [nav-token (navigate-to-blocking-feed!)
        e         (entry k)]
    (is (= [true :loading [] true]
           [(rf.resources.state/infinite-entry? e) (:status e) (:data e)
            (contains? (:active-owners e) [:route :route/feed nav-token])])
        "an infinite first-loading entry owned by the route")
    (reply-page! :on-success :rf.resource.internal/page-succeeded {:status :ok :value (page [:a :b] "c1")})
    (is (= [:loaded [(page [:a :b] "c1")] :idle #{}]
           [(:status (entry k)) (:data (entry k)) (:transition (slice)) (blocking-slot nav-token)])
        "page 0 landed and the route drained to :idle")))

(deftest blocking-infinite-route-page-0-failure-errors-route
  (let [nav-token (navigate-to-blocking-feed!)
        failure   {:kind :rf.http/server :status 503 :message "upstream down"}]
    (reply-page! :on-failure :rf.resource.internal/page-failed {:status :error :error failure})
    (is (= [:error failure nil nil nil]
           ((juxt :status :error :page-error :refresh-error :data) (entry k)))
        "a page-0 failure is a first-load :error, not a :page-error or :refresh-error")
    ;; the failed requirement stays outstanding: it holds the route at :error,
    ;; and route leave clears the whole nav-token slot
    (is (= [:error :rf.error/resource-route-blocking true]
           [(:transition (slice)) (:rf.error/id (:error (slice))) (contains? (blocking-slot nav-token) k)])
        "the blocking route flips to :error")))
