(ns re-frame.resources-from-db-scope-cljs-test
  "Named-resolver scope integration: a `{:from-db <id>}` reference resolves
  against app-db at use time, to the same scoped key, at every site that
  accepts one — event ensure, route entry, subscription read, invalidate-tags
  and mutation execute (Spec 016 §Resolver references — `{:from-db <id>}`,
  §Route integration, §Subscription-side scope resolution). A nil resolution
  fails closed (an event or operation throws, a route surfaces a planning
  error, a sub raises), never a silent global; a sub re-keys reactively when
  the resolver's inputs change; `clear-scope` takes a concrete scope only."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   ;; load-bearing side-effecting requires: register the resources + routing
   ;; events / subs and resources' late-bound :routing/* integration hooks.
   [re-frame.resources]
   [re-frame.resources.events :as rf.resources.events]
   [re-frame.resources.mutation-events :as rf.resources.mutation-events]
   [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
   [re-frame.resources.registry :as rf.resources.registry]
   [re-frame.resources.route :as rf.resources.route]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.subs :as rf.resources.subs]
   [re-frame.resources.scope-registry :as rf.resources.scope-registry]
   [re-frame.resources.test-support]
   [re-frame.routing :as rf.routing]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.registrar :as rf.registrar]
   [re-frame.trace.tooling :as rf.trace.tooling]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(defn- init!
  "A URL-owning frame with routing integration, the `:t/session` resolver over
  the logged-in username (written by `:t/login`, removed by `:t/logout`), and a
  feed whose spec :scope is `{:from-db :t/session}`; the transport and push-url
  are stubbed."
  []
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "from-db scope suite default app frame."})
  (rf.routing/reset-counters!)
  (rf.resources.route/install-routing-integration!)
  (rf.registrar/clear-kind! :resource-scope)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/reg-resource-scope :t/session
    {:inputs {:username [:db [:auth :user :username]]}}
    (fn [{:keys [username]} _ctx]
      (when username [:rf.scope/session {:username username}])))
  (rf/reg-resource :t/feed
    {:scope         {:from-db :t/session}
     :params-schema [:map [:page :int]]
     :tags          (fn [_p _v] #{[:feed]})}
    (fn [{:keys [page]} _ctx]
      {:request {:method :get :url "/feed" :params {:page page}}}))
  (rf/reg-event :t/login (fn [{:keys [db]} [_ username]] {:db (assoc-in db [:auth :user :username] username)}))
  (rf/reg-event :t/logout (fn [{:keys [db]} _] {:db (update db :auth dissoc :user)})))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))

(defn- entries [] (get-in (runtime-db) (rf.resources.state/entries-path)))

(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))

(defn- slice [] (get-in (runtime-db) [:rf.runtime/routing :current]))

(defn- session-key [u page]
  (rf.resources.state/scoped-resource-key [:rf.scope/session {:username u}] :t/feed {:page page}))

(def ^:private jake-scope [:rf.scope/session {:username "jake"}])

(defn- settle-loaded!
  "Drive the just-ensured entry at `scoped-key` to :loaded."
  [scoped-key data]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key scoped-key
                        :work/id      (:current-work e)
                        :generation   (:generation e)
                        :data         data}])))

(defn- load-feed-as!
  "Log in as `user`, then ensure and load page 1 of the feed under `owner`."
  [user owner data]
  (rf/dispatch-sync [:t/login user])
  (rf/dispatch-sync [:rf.resource/ensure {:resource :t/feed :params {:page 1} :owner owner}])
  (settle-loaded! (session-key user 1) data))

(defn- record-scope-resolved!
  "Run `body-fn`; return every :rf.resource/scope-resolved trace's tags for
  the :t/session resolver."
  [body-fn]
  (let [seen (atom [])
        k    ::scope-resolved-recorder]
    (rf.trace.tooling/register-listener!
      k (fn [ev] (when (and (= :rf.resource/scope-resolved (:operation ev))
                            (= :t/session (:resource-id (:tags ev))))
                   (swap! seen conj (:tags ev)))))
    (try (body-fn) (finally (rf.trace.tooling/unregister-listener! k)))
    @seen))

(defn- nil-resolution-rows
  "The scope-resolved rows `handler!` emits when it throws on a nil resolution."
  [handler!]
  (record-scope-resolved! #(try (handler!) (catch #?(:clj Throwable :cljs :default) _ nil))))

;; ===========================================================================
;; event ensure, route entry, subscription read
;; ===========================================================================

(deftest event-ensure-from-db-payload-reference
  ;; a {:from-db …} PAYLOAD scope overrides the resource's declared policy
  (rf/dispatch-sync [:t/login "abel"])
  (rf/reg-resource :t/notes
    {:scope :rf.scope/global :params-schema [:map]}
    (fn [_p _ctx] {:request {:method :get :url "/notes"}}))
  (rf/dispatch-sync [:rf.resource/ensure {:resource :t/notes :params {}
                                          :scope {:from-db :t/session}
                                          :owner [:app :n 1]}])
  (is (some? (entry (rf.resources.state/scoped-resource-key
                      [:rf.scope/session {:username "abel"}] :t/notes {})))))

(deftest event-ensure-from-db-nil-fails-closed
  (is (thrown-with-msg?
        #?(:clj Throwable :cljs js/Error) #"resource-scope-unresolved-reference"
        (rf.resources.registry/resolve-scope-for-event
          :t/feed (:rf/resource (rf/handler-meta {:source :store :kind :resource :id :t/feed})) {:db {}} 'test))
      "with no logged-in user the reference resolves nil, and an event throws")
  (rf/dispatch-sync [:rf.resource/ensure {:resource :t/feed :params {:page 1}}])
  (is (empty? (entries)) "dispatched, the runtime error path creates no entry under any scope"))

(deftest route-leave-releases-owner-under-resolved-scope
  (rf/dispatch-sync [:t/login "jake"])
  (rf/reg-route :t/home
    {:resources [{:resource :t/feed :params (fn [_] {:page 1})
                  :scope {:from-db :t/session}}]} "/")
  (rf/reg-route :t/other {} "/other")
  (rf/dispatch-sync [:rf.route/navigate {:to :t/home}])
  (let [k     (session-key "jake" 1)
        owner [:route :t/home (:nav-token (slice))]]
    (is (contains? (:active-owners (entry k)) owner)
        "route entry resolved the scope and attached its owner under it")
    (rf/dispatch-sync [:rf.route/navigate {:to :t/other}])
    (is (not (contains? (:active-owners (entry k)) owner))
        "route leave releases the owner acquired under the resolved scope")))

(deftest route-entry-from-db-nil-is-a-planning-error
  (rf/reg-route :t/home
    {:resources [{:resource :t/feed :params (fn [_] {:page 1})
                  :scope {:from-db :t/session}}]} "/")
  (rf/dispatch-sync [:rf.route/navigate {:to :t/home}])
  (is (= [:rf.error/resource-route-plan {}]
         [(:rf.error/id (:error (slice))) (or (entries) {})])
      "a nil route scope is a planning error on the slice, and nothing is ensured"))

(deftest sub-from-db-reads-the-same-entry-the-event-ensured
  (load-feed-as! "jake" [:app :s 1] {:articles [:a :b]})
  (let [q {:resource :t/feed :params {:page 1}}]
    (is (= [:loaded {:articles [:a :b]}]
           [(:status @(rf/subscribe [:rf/resource q])) @(rf/subscribe [:rf.resource/data q])])
        "a spec-policy sub resolves the same session key and reads the loaded entry")))

(deftest sub-re-keys-on-mid-session-account-switch
  (load-feed-as! "jake" [:app :s 1] {:for "jake"})
  (let [sub (rf/subscribe [:rf/resource {:resource :t/feed :params {:page 1}}])]
    (is (= [:loaded {:for "jake"}] ((juxt :status :data) @sub)))
    (rf/dispatch-sync [:t/login "abel"])
    (is (= [:idle nil false] ((juxt :status :data :has-data?) @sub))
        "switching the user re-keys the SAME sub to abel's un-ensured key, never jake's stale data")
    (rf/dispatch-sync [:rf.resource/ensure {:resource :t/feed :params {:page 1} :owner [:app :s 2]}])
    (settle-loaded! (session-key "abel" 1) {:for "abel"})
    (is (= [:loaded {:for "abel"}] ((juxt :status :data) @sub))
        "and the same sub reads abel's entry once it loads")))

;; ===========================================================================
;; clear-scope takes a CONCRETE scope only
;; ===========================================================================
;;
;; A map is a valid literal scope, so without the refusal a `{:from-db …}`
;; payload would canonicalize as a literal map scope, key nothing and clear
;; nothing, silently. The refusal is in the shared concrete-scope guard
;; (`state/canonicalize-scope`), so every concrete-scope boundary inherits it.

(deftest clear-scope-refuses-a-from-db-reference-map-loud
  (load-feed-as! "jake" [:app :c 1] {:for "jake"})
  (is (thrown-with-msg?
        #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
        (rf.resources.events/clear-scope-handler
          {:rf.db/runtime {} :rf.frame/id :rf/default :db {} :rf/time-ms 1}
          [:rf.resource/clear-scope {:scope {:from-db :t/session} :cause :logout}])))
  (rf/dispatch-sync [:rf.resource/clear-scope {:scope {:from-db :t/session} :cause :logout}])
  (is (= 1 (count (entries))) "dispatched, the refused clear leaves the cache as it was")
  ;; the supported idiom: pre-resolve the concrete scope with the pure helper
  (let [concrete (rf/resolve-resource-scope {:auth {:user {:username "jake"}}} :t/session)]
    (rf/dispatch-sync [:rf.resource/clear-scope {:scope concrete :cause :logout}])
    (is (= [jake-scope nil] [concrete (seq (entries))])
        "the concrete scope still clears, so the refusal is about the reference shape")))

;; ===========================================================================
;; the :rf.resource/scope-resolved trace: causal sites emit, pure reads do not
;; ===========================================================================

(deftest scope-resolved-trace-emitted-on-event-ensure-with-full-shape
  (rf/dispatch-sync [:t/login "jake"])
  (let [row (first (record-scope-resolved!
                     #(rf/dispatch-sync [:rf.resource/ensure {:resource :t/feed :params {:page 1}
                                                              :owner [:app :sr 1]}])))]
    (is (= {:kind :resource-scope :inputs [:username] :input-values {:username "jake"}
            :whole-db? false :scope jake-scope :resolved-nil? false}
           (select-keys row [:kind :inputs :input-values :whole-db? :scope :resolved-nil?]))
        "the declared input names, their resolved values, the derived mark and the resolved scope")))

(deftest scope-resolved-trace-records-resolved-nil-on-absent-input
  ;; the event ensure fails closed, but the trace fires first with the evidence
  (rf/reg-resource :t/notes
    {:scope :rf.scope/global :params-schema [:map]}
    (fn [_p _ctx] {:request {:method :get :url "/notes"}}))
  (let [row (first (nil-resolution-rows
                     #(rf/dispatch-sync [:rf.resource/ensure {:resource :t/notes :params {}
                                                              :scope {:from-db :t/session}
                                                              :owner [:app :sn 1]}])))]
    (is (= [true nil] [(:resolved-nil? row) (:scope row)]))))

(deftest pure-resolve-resource-scope-emits-no-trace
  ;; the logout idiom resolves the old scope from the cofx db, and a pure read
  ;; must not mutate observability state
  (let [resolved (atom nil)
        rows     (record-scope-resolved!
                   #(reset! resolved [(rf/resolve-resource-scope {:auth {:user {:username "jake"}}} :t/session)
                                      (rf/resolve-resource-scope {} :t/session)]))]
    (is (= [[jake-scope nil] []] [@resolved rows])
        "the helper resolves (nil failing closed to nil) and emits no scope-resolved row")))

(deftest pure-subscription-key-resolution-emits-no-trace
  ;; a sub re-keys on every frame-state change, so it must not flood the bus
  (let [resolved (atom nil)
        rows     (record-scope-resolved!
                   #(reset! resolved (rf.resources.subs/resolve-scoped-key
                                       {:resource :t/feed :params {:page 1}}
                                       {:auth {:user {:username "jake"}}})))]
    (is (= [(session-key "jake" 1) []] [@resolved rows]))))

(deftest scope-resolved-off-box-egress-redacts-resolver-values
  ;; the resolved values are owner-local db reads the value-path walk cannot
  ;; classify, so the off-box projection redacts them unconditionally (there is
  ;; no declassification escape hatch) and keeps the structural slots
  (is (= {:resource-id :t/session :kind :resource-scope :inputs [:username] :input-values :rf/redacted
          :whole-db? false :scope :rf/redacted :resolved-nil? false :sensitive? true}
         (rf.resources.scope-registry/project-scope-resolved-egress
           {:resource-id   :t/session
            :kind          :resource-scope
            :inputs        [:username]
            :input-values  {:username "jake"}
            :whole-db?     false
            :scope         jake-scope
            :resolved-nil? false}))))

;; ===========================================================================
;; invalidate-tags resolves a {:from-db} scope symmetrically with ensure
;; ===========================================================================

(deftest invalidate-tags-from-db-marks-only-the-matching-principal
  ;; owners are released so the invalidation marks stale rather than refetching
  (load-feed-as! "jake" [:app :j 1] {:for "jake"})
  (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :j 1]}])
  (load-feed-as! "abel" [:app :a 1] {:for "abel"})
  (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :a 1]}])
  (rf/dispatch-sync [:t/login "jake"])
  (rf/dispatch-sync [:rf.resource/invalidate-tags {:scope {:from-db :t/session} :tags #{[:feed]}}])
  (is (= [true nil]
         [(some? (:invalidated-at (entry (session-key "jake" 1)))) (:invalidated-at (entry (session-key "abel" 1)))])
      "only jake's resolved-scope entry goes stale; abel's equal-tag entry is untouched"))

(deftest invalidate-tags-from-db-active-owner-refetch-carries-concrete-scope
  ;; asserted at the handler boundary so the returned :fx is inspectable
  (load-feed-as! "jake" [:app :j 1] {:for "jake"})
  (let [{:keys [fx]} (rf.resources.events/invalidate-tags-handler
                       {:rf.db/runtime (runtime-db) :rf.frame/id :rf/default
                        :db {:auth {:user {:username "jake"}}} :rf/time-ms 1}
                       [:rf.resource/invalidate-tags {:scope {:from-db :t/session} :tags #{[:feed]}}])
        refetch (some (fn [[fx-id [ev-id p]]] (when (and (= :dispatch fx-id) (= :rf.resource/refetch ev-id)) p))
                      fx)]
    (is (= jake-scope (:scope refetch))
        "the active owner's refetch carries the resolved concrete scope, never the reference")))

(deftest invalidate-tags-from-db-nil-fails-closed-like-ensure
  (let [handler! #(rf.resources.events/invalidate-tags-handler
                    {:rf.db/runtime {} :rf.frame/id :rf/default :db {} :rf/time-ms 1}
                    [:rf.resource/invalidate-tags {:scope {:from-db :t/session} :tags #{[:feed]}}])
        rows     (nil-resolution-rows handler!)]
    (is (thrown-with-msg? #?(:clj Throwable :cljs js/Error) #"resource-scope-unresolved-reference" (handler!)))
    (is (= [1 true nil] [(count rows) (:resolved-nil? (first rows)) (:scope (first rows))])
        "exactly one scope-resolved row records the nil resolution before the throw"))
  (load-feed-as! "jake" [:app :j 1] {:for "jake"})
  (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :j 1]}])
  (rf/dispatch-sync [:t/logout])
  (rf/dispatch-sync [:rf.resource/invalidate-tags {:scope {:from-db :t/session} :tags #{[:feed]}}])
  (is (nil? (:invalidated-at (entry (session-key "jake" 1))))
      "dispatched, nothing is invalidated under any scope"))

;; ===========================================================================
;; mutation execute resolves a {:from-db} scope symmetrically with ensure;
;; an ABSENT :scope takes the global default, a supplied nil reference throws
;; ===========================================================================

(defn- minstance [instance-id]
  (get-in (runtime-db) (rf.resources.mutation-runtime/instance-path instance-id)))

(defn- reg-save-mutation! [extra]
  (rf/reg-mutation :t/save
    (merge {:params-schema [:map]} extra)
    (fn [_p _ctx] {:request {:method :post :url "/save"}})))

(deftest execute-from-db-resolves-identically-to-ensure
  ;; :before-request timing fires the bare tag-set (:rf.scope/same) at execute
  (load-feed-as! "jake" [:app :m 1] {:for "jake"})
  (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :m 1]}])
  (reg-save-mutation! {:invalidates (fn [_p _r] #{[:feed]}) :invalidate-timing :before-request})
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :t/save :params {} :instance :save-1
                                           :scope {:from-db :t/session}}])
  (is (= [jake-scope true]
         [(:scope (minstance :save-1)) (some? (:invalidated-at (entry (session-key "jake" 1))))])
      "the instance records the resolved scope, and the default invalidation matched in it"))

(deftest execute-descriptor-scope-still-overrides-resolved-default
  (rf/dispatch-sync [:t/login "jake"])
  (rf/reg-resource :t/gnotes
    {:scope :rf.scope/global :params-schema [:map] :tags (fn [_p _v] #{[:gfact]})}
    (fn [_p _ctx] {:request {:method :get :url "/gnotes"}}))
  (let [gkey (rf.resources.state/scoped-resource-key :rf.scope/global :t/gnotes {})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :t/gnotes :params {} :owner [:app :g 1]}])
    (settle-loaded! gkey {:g 1})
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :g 1]}])
    (reg-save-mutation! {:invalidates       (fn [_p _r] [{:scope :rf.scope/global :tags #{[:gfact]}}])
                         :invalidate-timing :before-request})
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :t/save :params {} :instance :save-g1
                                             :scope {:from-db :t/session}}])
    (is (= [jake-scope true] [(:scope (minstance :save-g1)) (some? (:invalidated-at (entry gkey)))])
        "the execution scope resolves to jake's session, but the descriptor's global scope governs its match")))

(deftest execute-from-db-nil-fails-closed-like-ensure
  (reg-save-mutation! {})
  (let [handler! #(rf.resources.mutation-events/execute-handler
                    {:rf.db/runtime {} :rf.frame/id :rf/default :db {} :rf/time-ms 1
                     :rf.resource/generation-allocation {:generation 1 :counter 1}}
                    [:rf.mutation/execute {:mutation :t/save :params {} :instance :save-nil
                                           :scope {:from-db :t/session}}])
        rows     (nil-resolution-rows handler!)]
    (is (thrown-with-msg? #?(:clj Throwable :cljs js/Error) #"resource-scope-unresolved-reference" (handler!)))
    (is (= [1 true nil] [(count rows) (:resolved-nil? (first rows)) (:scope (first rows))])
        "exactly one scope-resolved row records the nil resolution before the throw"))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :t/save :params {} :instance :save-nil
                                           :scope {:from-db :t/session}}])
  (is (= [nil nil] [(minstance :save-nil) (seq (entries))])
      "dispatched, no instance is minted and no entry touched: never a global-default write"))

(deftest execute-absent-and-concrete-scopes-unchanged
  (reg-save-mutation! {})
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :t/save :params {} :instance :save-abs}])
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :t/save :params {} :instance :save-conc
                                           :scope [:rf.scope/session {:username "abel"}]}])
  (is (= [:rf.scope/global [:rf.scope/session {:username "abel"}]]
         [(:scope (minstance :save-abs)) (:scope (minstance :save-conc))])
      "an absent :scope takes the global default; a concrete one passes through unchanged"))
