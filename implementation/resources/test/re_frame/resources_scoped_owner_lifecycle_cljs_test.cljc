(ns re-frame.resources-scoped-owner-lifecycle-cljs-test
  "The owner lifecycle across two simultaneously-live scopes (Spec 016 §The
  scoped-cache owner lifecycle). An owner is a hold on one resolved scoped key,
  so tenant A and tenant B hold independent owners on independent entries, and
  acquiring, releasing or collecting one can neither pin nor collect the
  other. Single-scope GC mechanics are `resources_invalidation_gc_cljs_test`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.registry]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.registrar :as rf.registrar]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(defn- init!
  "A tenant-scoped feed whose :scope is {:from-db :t/tenant}, resolved from the
  viewer's tenant id, which :t/switch-tenant writes."
  []
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "scoped-owner-lifecycle suite default app frame."})
  (rf.registrar/clear-kind! :resource-scope)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf/reg-resource-scope :t/tenant
    {:inputs {:tenant [:db [:viewer :tenant-id]]}}
    (fn [{:keys [tenant]} _ctx]
      (when tenant [:rf.scope/tenant {:tenant-id tenant}])))
  (rf/reg-resource :t/feed
    {:scope         {:from-db :t/tenant}
     :params-schema [:map [:page :int]]
     :gc-after-ms   60000
     :tags          (fn [_p _v] #{[:feed]})}
    (fn [{:keys [page]} _ctx]
      {:request {:method :get :url "/feed" :params {:page page}}}))
  (rf/reg-event :t/switch-tenant
    (fn [{:keys [db]} [_ tenant]] {:db (assoc-in db [:viewer :tenant-id] tenant)})))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))

(defn- entries
  "The entries table re-keyed from byte key-ids to scoped-key vectors."
  []
  (into {} (map (fn [[_k-id e]] [(:resource/key e) e]))
        (get-in (runtime-db) (rf.resources.state/entries-path))))

(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))

(defn- owner-index
  "The owner index with its byte key-id members mapped back to scoped keys."
  []
  (let [rdb    (runtime-db)
        es     (get-in rdb (rf.resources.state/entries-path))
        id->sk (into {} (map (fn [[k-id e]] [k-id (:resource/key e)])) es)]
    (into {} (map (fn [[owner members]]
                    [owner (into #{} (map #(get id->sk % %)) members)]))
          (get-in rdb (rf.resources.state/owner-index-path)))))

(defn- tenant-key [t page]
  (rf.resources.state/scoped-resource-key [:rf.scope/tenant {:tenant-id t}] :t/feed {:page page}))

(defn- ensure-feed!
  "Make `t` the viewer's tenant, then ensure the feed with no :scope, so the
  {:from-db} policy resolves tenant `t`'s scope."
  [t page owner]
  (rf/dispatch-sync [:t/switch-tenant t])
  (rf/dispatch-sync [:rf.resource/ensure {:resource :t/feed :params {:page page}
                                          :owner owner}]))

(defn- settle-loaded! [scoped-key data]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key scoped-key
                        :work/id      (:current-work e)
                        :generation   (:generation e)
                        :data         data}])))

(defn- two-live-tenants!
  "Tenant A (acme, owner [:app :a 1]) and tenant B (globex, owner
  [:app :b 1]), both loaded; the viewer is left on B. Returns [ka kb]."
  []
  (ensure-feed! "acme" 1 [:app :a 1])
  (settle-loaded! (tenant-key "acme" 1) {:for "acme"})
  (ensure-feed! "globex" 1 [:app :b 1])
  (settle-loaded! (tenant-key "globex" 1) {:for "globex"})
  [(tenant-key "acme" 1) (tenant-key "globex" 1)])

(deftest acquire-two-scopes-are-independent-owners
  (let [[ka kb] (two-live-tenants!)]
    (is (= {ka [{:for "acme"} #{[:app :a 1]}]
            kb [{:for "globex"} #{[:app :b 1]}]}
           (into {} (map (fn [[k e]] [k [(:data e) (:active-owners e)]])) (entries)))
        "each tenant's entry carries its own data and only its own owner")))

(deftest release-is-scoped-to-its-own-entry
  ;; the release names the owner, not the live resolver output, which is B's
  (let [[ka kb] (two-live-tenants!)]
    (is (= "globex" (get-in (rf/app-db-value :rf/default) [:viewer :tenant-id]))
        "precondition: the live viewer input is tenant B")
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :a 1]}])
    (is (= [true true true] [(empty? (:active-owners (entry ka))) (nil? (get (owner-index) [:app :a 1]))
                             (some? (entry ka))])
        "A's owner is released from its entry and the index; the entry waits for GC")
    (is (= [true true] [(contains? (:active-owners (entry kb)) [:app :b 1])
                        (contains? (get (owner-index) [:app :b 1]) kb)])
        "B's owner and its index entry are untouched")))

(deftest gc-on-last-release-collects-only-the-unowned-scope
  (let [[ka kb] (two-live-tenants!)]
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :a 1]}])
    ;; the same collection pass runs over both tenants
    (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key ka}])
    (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key kb}])
    (is (= {kb {:for "globex"}} (into {} (map (fn [[k e]] [k (:data e)])) (entries)))
        "A was collected; B, still owned, survives intact")))
