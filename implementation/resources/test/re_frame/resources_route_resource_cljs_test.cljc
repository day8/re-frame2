(ns re-frame.resources-route-resource-cljs-test
  "`[:rf/route-resource <resource-id>]` — the route-bound read (Spec 016
  §Route-bound reads). It reads the identity the ACTIVE route's plan ensured
  for a resource id, so a view restates none of the route's params: a view
  that rebuilds them can drift from what the route computed and read an
  identity nothing ensured, at `:idle`, with no error anywhere.

  One resource id can sit in a plan under several identities; that read
  throws, naming them, and never picks one. An id the active plan does not
  hold reads `:status :unplanned`. Alongside, `:params-schema` is optional: a
  resource without one caches and reads normally, and its params still refuse
  host values at the cache-key boundary."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.registry :as rf.resources.registry]
   [re-frame.resources.route :as rf.resources.route]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.subs :as rf.resources.subs]
   [re-frame.resources.test-support]
   [re-frame.routing :as rf.routing]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.registrar :as rf.registrar]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

;; ---- fixture --------------------------------------------------------------

(defn- init! []
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "route-resource suite default app frame."})
  (rf.routing/reset-counters!)
  (rf.resources.route/install-routing-integration!)
  (rf.registrar/clear-kind! :resource-scope)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ctx _args] nil))
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/reg-resource :t/article
    {:scope :rf.scope/global :params-schema [:map [:slug :string]]}
    (fn [{:keys [slug]} _] {:request {:method :get :url (str "/articles/" slug)}}))
  ;; no :params-schema — the params are taken as given
  (rf/reg-resource :t/comments
    {:scope :rf.scope/global}
    (fn [{:keys [slug]} _] {:request {:method :get :url (str "/articles/" slug "/comments")}}))
  (rf/reg-resource :t/profile
    {:scope :rf.scope/global :params-schema [:map [:username :string]]}
    (fn [{:keys [username]} _] {:request {:method :get :url (str "/profiles/" username)}}))
  ;; the route normalises the slug; a view that rebuilds `{:slug …}` from the
  ;; raw route params does not
  (rf/reg-route :t/article
    {:params    [:map [:slug :string]]
     :resources [{:resource :t/article
                  :params   (fn [route] {:slug (str/lower-case (get-in route [:params :slug]))})}
                 {:resource :t/comments
                  :params   (fn [route] {:slug (str/lower-case (get-in route [:params :slug]))})}]}
    "/article/:slug")
  ;; one resource, two identities: the author's profile beside the reader's own
  (rf/reg-route :t/author
    {:params    [:map [:username :string]]
     :resources [{:resource :t/profile
                  :params   (fn [route] {:username (get-in route [:params :username])})}
                 {:resource :t/profile
                  :params   (fn [_route] {:username "me"})}]}
    "/author/:username"))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [k] (get-in (runtime-db) (rf.resources.state/entry-path k)))

(defn- article-key [slug] (rf.resources.state/scoped-resource-key :rf.scope/global :t/article {:slug slug}))
(defn- comments-key [slug] (rf.resources.state/scoped-resource-key :rf.scope/global :t/comments {:slug slug}))
(defn- profile-key [u] (rf.resources.state/scoped-resource-key :rf.scope/global :t/profile {:username u}))

(defn- settle-loaded! [k data]
  (let [e (entry k)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key k :work/id (:current-work e)
                        :generation (:generation e) :data data}])))

(defn- navigate! [to params]
  (rf/dispatch-sync [:rf.route/navigate {:to to :params params}]))

(defn- route-read [resource-id]
  (rf.resources.subs/route-resource-sub-fn (rf/frame-state-value :rf/default)
                                           [:rf/route-resource resource-id]))

(defn- thrown-data [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e))))

;; ===========================================================================
;; the read follows the plan, so it cannot drift from it
;; ===========================================================================

(deftest a-route-bound-read-cannot-drift-from-what-the-route-ensured
  (navigate! :t/article {:slug "Welcome"})
  (settle-loaded! (article-key "welcome") {:title "Welcome"})
  (let [restated @(rf/subscribe [:rf/resource {:resource :t/article
                                               :params   {:slug (:slug @(rf/subscribe [:rf.route/params]))}}])
        bound    @(rf/subscribe [:rf/route-resource :t/article])]
    (is (= [:idle nil] ((juxt :status :data) restated))
        "a view that rebuilds the params reads an identity nothing ensured")
    (is (= [:loaded {:title "Welcome"}] ((juxt :status :data) bound))
        "the route-bound read reads the identity the route ensured")))

(deftest a-route-bound-read-follows-the-route
  (navigate! :t/article {:slug "welcome"})
  (settle-loaded! (article-key "welcome") {:title "Welcome"})
  (let [sub (rf/subscribe [:rf/route-resource :t/article])]
    (is (= :loaded (:status @sub)))
    (navigate! :t/article {:slug "second"})
    (is (= :loading (:status @sub)) "the same sub reads the next article's identity")
    (settle-loaded! (article-key "second") {:title "Second"})
    (is (= [:loaded {:title "Second"}] ((juxt :status :data) @sub)))))

(deftest an-ambiguous-route-bound-read-fails-loudly-naming-every-identity
  (navigate! :t/author {:username "ann"})
  (is (= (set [(profile-key "ann") (profile-key "me")])
         (set (vals (get-in (runtime-db) (rf.resources.route/plan-path
                                           (get-in (runtime-db) [:rf.runtime/routing :current :nav-token]))))))
      "FIXTURE — the plan holds the profile under two identities")
  (let [data (thrown-data #(route-read :t/profile))]
    (is (= {:rf.error/id :rf.error/resource-route-read-ambiguous :resource-id :t/profile}
           (select-keys data [:rf.error/id :resource-id])))
    (is (= (set [(profile-key "ann") (profile-key "me")]) (set (:identities data)))
        "the error names both identities and the read picks neither")))

(deftest a-resource-the-active-plan-does-not-hold-reads-unplanned
  (testing "with no active route"
    (is (= :unplanned (:status (route-read :t/article)))))
  (navigate! :t/article {:slug "welcome"})
  (testing "a registered resource this route does not plan"
    (is (= {:status :unplanned :data nil :error nil :refresh-error nil
            :loading? false :fetching? false :stale? false :has-data? false :previous? false}
           (route-read :t/profile))
        "the unplanned empty shape — not :idle, which names an identity nobody ensured"))
  (testing "an id with no registration"
    (is (= :rf.error/resource-not-registered
           (:rf.error/id (thrown-data #(route-read :t/nope)))))))

;; ===========================================================================
;; :params-schema is optional
;; ===========================================================================

(deftest a-resource-without-a-params-schema-caches-and-reads-normally
  (is (not (contains? (rf.resources.registry/resource-meta :t/comments) :params-schema))
      "FIXTURE — registered without :params-schema")
  (navigate! :t/article {:slug "welcome"})
  (settle-loaded! (comments-key "welcome") {:comments []})
  (is (= [:loaded {:comments []}]
         ((juxt :status :data) @(rf/subscribe [:rf/resource {:resource :t/comments :params {:slug "welcome"}}]))
         ((juxt :status :data) @(rf/subscribe [:rf/route-resource :t/comments])))
      "the route ensures it and both reads find it")
  (testing "the cache-key boundary still refuses a host value"
    (is (= :rf.error/resource-non-edn-params
           (:rf.error/id (thrown-data #(rf.resources.subs/resolve-scoped-key
                                         {:resource :t/comments :params {:slug 1.5}} {})))))))

(deftest a-declared-params-schema-still-makes-an-invalid-read-loud
  (is (= :rf.error/resource-invalid-params
         (:rf.error/id (thrown-data #(rf.resources.subs/resolve-scoped-key
                                       {:resource :t/article :params {:slug 42}} {}))))))
