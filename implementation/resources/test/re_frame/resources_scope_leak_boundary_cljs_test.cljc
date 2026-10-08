(ns re-frame.resources-scope-leak-boundary-cljs-test
  "The scoped-cache LEAK BOUNDARY as an executable security guarantee (guide
  §\"Scope — the leak boundary other libraries do not have\"): the resolved
  scope is IN the cache key, so no principal's live subscription can observe
  another principal's cached data — across logout `clear-scope`, a wrong but
  valid scope, and a scoped invalidation. Asserted at the live `rf/subscribe`
  read, the seam a real leak would show at. The resolution mechanics are
  `resources_from_db_scope_cljs_test`'s; owner non-interference across scopes
  is `resources_scoped_owner_lifecycle_cljs_test`'s."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   ;; load-bearing side-effecting requires: register the :rf.resource/* events
   ;; + subs and the named-resolver scope plumbing.
   [re-frame.resources]
   [re-frame.resources.registry]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.subs :as rf.resources.subs]
   [re-frame.resources.test-support]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.registrar :as rf.registrar]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(defn- init!
  "A tenant-scoped feed whose spec :scope is the `{:from-db :t/tenant}`
  reference over the viewer's tenant id, which `:t/login` writes and
  `:t/logout` removes; the transport never fetches."
  []
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "scope-leak-boundary suite default app frame."})
  (rf.registrar/clear-kind! :resource-scope)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf/reg-resource-scope :t/tenant
    {:inputs {:tenant [:db [:viewer :tenant-id]]}}
    (fn [{:keys [tenant]} _ctx]
      (when tenant [:rf.scope/tenant {:tenant-id tenant}])))
  (rf/reg-resource :t/feed
    {:scope         {:from-db :t/tenant}
     :params-schema [:map [:page :int]]
     :tags          (fn [_p _v] #{[:feed]})}
    (fn [{:keys [page]} _ctx]
      {:request {:method :get :url "/feed" :params {:page page}}}))
  (rf/reg-event :t/login  (fn [{:keys [db]} [_ tenant]] {:db (assoc-in db [:viewer :tenant-id] tenant)}))
  (rf/reg-event :t/logout (fn [{:keys [db]} _] {:db (update db :viewer dissoc :tenant-id)})))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))

(defn- cached-keys
  "The scoped keys of every cached entry."
  []
  (set (map :resource/key (vals (get-in (runtime-db) (rf.resources.state/entries-path))))))

(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))

(defn- tenant-key [t page]
  (rf.resources.state/scoped-resource-key [:rf.scope/tenant {:tenant-id t}] :t/feed {:page page}))

(defn- tenant-scope [t] [:rf.scope/tenant {:tenant-id t}])

(defn- ensure-feed!
  "Log in as tenant `t`, so the named resolver yields its scope, then ensure
  and load its feed page `page` under `owner` with `data`."
  [t page owner data]
  (rf/dispatch-sync [:t/login t])
  (rf/dispatch-sync [:rf.resource/ensure {:resource :t/feed :params {:page page} :owner owner}])
  (let [e (entry (tenant-key t page))]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key (tenant-key t page)
                        :work/id      (:current-work e)
                        :generation   (:generation e)
                        :data         data}])))

(defn- invalidate-feed-in! [t]
  (rf/dispatch-sync [:rf.resource/invalidate-tags
                     {:scope (tenant-scope t) :tags #{[:feed]} :cause [:test :scoped-invalidate]}]))

;; ===========================================================================
;; logout clear-scope
;; ===========================================================================

(deftest next-principal-sub-cannot-read-the-logged-out-principals-data
  ;; THE CROSS-USER LEAK TEST: acme loads, logs out (scope cleared), and globex
  ;; logs in on the same frame, reading through the {:from-db} spec policy
  (ensure-feed! "acme" 1 [:app :acme 1] {:secret "acme-only"})
  (rf/dispatch-sync [:rf.resource/clear-scope
                     {:scope (rf/resolve-resource-scope (rf/app-db-value :rf/default) :t/tenant) :cause :logout}])
  (rf/dispatch-sync [:t/logout])
  (rf/dispatch-sync [:t/login "globex"])
  (let [q  {:resource :t/feed :params {:page 1}}
        st @(rf/subscribe [:rf/resource q])]
    (is (= [:idle false nil #{}]
           [(:status st) (:has-data? st) @(rf/subscribe [:rf.resource/data q]) (cached-keys)])
        "globex reads its own un-ensured idle key, never acme's data, and acme's entry is gone")))

(deftest clear-scope-isolates-the-cleared-principal-other-scopes-survive
  (ensure-feed! "acme"   1 [:app :acme 1]   {:for "acme"})
  (ensure-feed! "globex" 1 [:app :globex 1] {:for "globex"})
  (is (= #{(tenant-key "acme" 1) (tenant-key "globex" 1)} (cached-keys)) "FIXTURE — both tenants cached at once")
  (rf/dispatch-sync [:rf.resource/clear-scope {:scope (tenant-scope "acme") :cause :logout}])
  (is (= [#{(tenant-key "globex" 1)} {:for "globex"}]
         [(cached-keys) (:data (entry (tenant-key "globex" 1)))])
      "clearing acme's scope removes only acme's entries; globex's survives with its data"))

;; ===========================================================================
;; a wrong but valid scope reads its own entry
;; ===========================================================================

(deftest wrong-scope-sub-reads-its-own-empty-entry-never-the-other-principals
  (ensure-feed! "acme" 1 [:app :acme 1] {:secret "acme-only"})
  (let [wrong-q {:resource :t/feed :params {:page 1} :scope (tenant-scope "globex")}]
    (is (= [:idle nil true]
           [(:status @(rf/subscribe [:rf/resource wrong-q])) @(rf/subscribe [:rf.resource/data wrong-q])
            (some? (entry (tenant-key "acme" 1)))])
        "an explicit wrong scope addresses its own empty key: idle, never acme's data, acme untouched")))

(deftest two-explicit-scope-subs-keep-distinct-cache-entries
  ;; The positive half of the wrong-scope test, which a runtime resolving the
  ;; override to nothing would also pass: two overrides over one resource and
  ;; one params map address two POPULATED keys, neither borrowing the other's
  ;; data. The browser witness is `testbeds/tenant_switcher`'s cache-witness
  ;; panel; this runs in the always-on unit lanes beside it.
  (ensure-feed! "acme"   1 [:app :acme 1]   {:motto "acme-only"})
  (ensure-feed! "globex" 1 [:app :globex 1] {:motto "globex-only"})
  ;; a THIRD tenant is ambient, so a read falling back to the spec policy would
  ;; go idle, and a read collapsing both queries would return one motto twice
  (rf/dispatch-sync [:t/login "initech"])
  (let [q-acme    {:resource :t/feed :params {:page 1} :scope (tenant-scope "acme")}
        q-globex  {:resource :t/feed :params {:page 1} :scope (tenant-scope "globex")}
        st-acme   (rf/subscribe [:rf/resource q-acme])
        st-globex (rf/subscribe [:rf/resource q-globex])
        d-acme    (rf/subscribe [:rf.resource/data q-acme])
        d-globex  (rf/subscribe [:rf.resource/data q-globex])
        db        (rf/app-db-value :rf/default)]
    (is (= [true #{(tenant-key "acme" 1) (tenant-key "globex" 1)}]
           [(not= (rf.resources.subs/resolve-scoped-key q-acme db) (rf.resources.subs/resolve-scoped-key q-globex db))
            (cached-keys)])
        "the overrides resolve two distinct keys, both live in the cache")
    ;; the re-derefs interleave, so neither read displaces the other's value
    (is (= [:loaded :loaded {:motto "acme-only"} {:motto "globex-only"} {:motto "acme-only"} {:motto "globex-only"}]
           [(:status @st-acme) (:status @st-globex) @d-acme @d-globex (:data @st-acme) (:data @st-globex)])
        "both subs are live at once, each reading its own loaded value")
    (let [st-policy @(rf/subscribe [:rf/resource {:resource :t/feed :params {:page 1}}])]
      (is (= [:idle nil] [(:status st-policy) (:data st-policy)])
          "CONTROL — with no override the spec policy reads initech's un-ensured key"))))

;; ===========================================================================
;; a scoped invalidation reaches exactly the resolved scope
;; ===========================================================================

(deftest scoped-invalidate-tags-reaches-only-the-named-principal
  ;; both entries are ownerless, so the :invalidated-at stale mark is observable
  ;; rather than cleared by the refetch an owned entry would start
  (ensure-feed! "acme"   1 [:app :acme 1]   {:for "acme"})
  (ensure-feed! "globex" 1 [:app :globex 1] {:for "globex"})
  (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :acme 1]}])
  (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :globex 1]}])
  (invalidate-feed-in! "acme")
  (is (= [true nil]
         [(some? (:invalidated-at (entry (tenant-key "acme" 1)))) (:invalidated-at (entry (tenant-key "globex" 1)))])
      "acme's feed goes stale; globex's same-tagged feed is untouched"))
