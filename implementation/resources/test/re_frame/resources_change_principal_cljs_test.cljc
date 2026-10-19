(ns re-frame.resources-change-principal-cljs-test
  "`[:rf.resource/change-principal {:from [<scope> …] :cause …}]` — the one
  framework event for an identity switch (Spec 016 §Changing principal). It
  clears every scope the departing principal held, then replans the active
  route under the identity the app has already committed.

  The hazard it closes is a cross-credential refetch: an old-scope entry that is
  still owned, refetched by focus, reconnect, a poll tick or an invalidation
  while HTTP sends the NEW principal's credentials, writes the new user's bytes
  under the old user's scope. Each trigger is exercised twice — fired after the
  event, and queued AHEAD of it in the same drain, where the trigger's refetch
  dispatch lands behind the clear."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.events :as rf.resources.events]
   [re-frame.resources.route :as rf.resources.route]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   [re-frame.routing :as rf.routing]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.registrar :as rf.registrar]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

;; ---- fixture --------------------------------------------------------------

(def ^:private requests
  "Every managed-HTTP request lowered during a test, in order."
  (atom []))

(def ^:private aborts (atom []))

(defn- init! []
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "change-principal suite default app frame."})
  (rf.routing/reset-counters!)
  (rf.resources.route/install-routing-integration!)
  (rf.registrar/clear-kind! :resource-scope)
  (reset! requests [])
  (reset! aborts [])
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! requests conj args) nil))
  (rf.fx/reg-fx :rf.http/managed-abort (fn [_ctx work-id] (swap! aborts conj work-id) nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ctx _args] nil))
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/reg-resource-scope :t/viewer
    {:inputs {:username [:db [:auth :user :username]]}}
    (fn [{:keys [username]} _ctx]
      (when username [:rf.scope/viewer {:username username}])))
  ;; stale the instant it loads, polled, tagged — every trigger's precondition
  (rf/reg-resource :t/doc
    {:scope            {:from-db :t/viewer}
     :params-schema    [:map [:page :string]]
     :stale-after-ms   0
     :poll-interval-ms 1000
     :tags             (fn [{:keys [page]} _data] #{[:doc page]})}
    (fn [{:keys [page]} _] {:request {:method :get :url (str "/docs/" page)}}))
  (rf/reg-route :t/docs
    {:params    [:map [:page :string]]
     :resources [{:resource :t/doc
                  :params   (fn [route] {:page (get-in route [:params :page])})}]}
    "/docs/:page")
  (rf/reg-event :t/fire
    (fn [_ [_ events]] {:fx (mapv (fn [ev] [:dispatch ev]) events)}))
  (rf/reg-event :t/login
    (fn [{:keys [db]} [_ u]] {:db (assoc-in db [:auth :user :username] u)}))
  ;; the app-side half of an account switch: resolve the departing scope from
  ;; the coeffect db, commit the new identity, hand the transition over
  (rf/reg-event :t/switch-to
    (fn [{:keys [db]} [_ u]]
      {:db (assoc-in db [:auth :user :username] u)
       :fx [[:dispatch [:rf.resource/change-principal
                        {:from  [(rf/resolve-resource-scope db :t/viewer)]
                         :cause :account-switch}]]]})))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [k] (get-in (runtime-db) (rf.resources.state/entry-path k)))
(defn- slice [] (get-in (runtime-db) [:rf.runtime/routing :current]))
(defn- plan-slot [] (get-in (runtime-db) [:rf.runtime/routing :resource-plan (:nav-token (slice))]))
(defn- owner-index [owner] (get-in (runtime-db) (conj (rf.resources.state/owner-index-path) owner)))

(defn- viewer [u] [:rf.scope/viewer {:username u}])
(defn- doc-key [u page] (rf.resources.state/scoped-resource-key (viewer u) :t/doc {:page page}))

(def ^:private app-owner [:app :dashboard 1])

(defn- settle-loaded! [k data]
  (let [e (entry k)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key k :work/id (:current-work e)
                        :generation (:generation e) :data data}])))

(defn- owned-loaded!
  "Ensure `page` under `u`'s viewer scope with the app owner and settle it, so
  it is owned, loaded and (stale-after-ms 0) stale. Returns its scoped key."
  [u page]
  (let [k (doc-key u page)]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :t/doc :scope (viewer u)
                                            :params {:page page} :owner app-owner}])
    (settle-loaded! k {:page page :for u})
    k))

(defn- change-principal [from]
  [:rf.resource/change-principal {:from from :cause :logout}])

(defn- requested-urls [n] (mapv #(get-in % [:request :url]) (drop n @requests)))

;; ===========================================================================
;; the clear half
;; ===========================================================================

(deftest change-principal-clears-every-departing-scope-and-nothing-else
  (let [ann-a (owned-loaded! "ann" "a")
        ann-b (owned-loaded! "ann" "b")
        sid-a (owned-loaded! "sid" "a")
        cid   (:current-work (entry sid-a))]
    (rf/dispatch-sync [:rf.resource/refetch {:resource :t/doc :scope (viewer "ann")
                                             :params {:page "b"} :cause :manual}])
    (let [in-flight (:current-work (entry ann-b))]
      (rf/dispatch-sync (change-principal [(viewer "ann") nil (viewer "nobody")]))
      (is (= [nil nil] [(entry ann-a) (entry ann-b)])
          "every entry in a departing scope is removed")
      (is (= [:loaded #{app-owner} cid] [(:status (entry sid-a)) (:active-owners (entry sid-a))
                                         (:current-work (entry sid-a))])
          "another principal's entry keeps its data and its owner")
      (is (= #{(rf.resources.state/key-id sid-a)} (owner-index app-owner))
          "the owner's hold on the departing scope is retired; its hold elsewhere stays")
      (is (= [(rf.resources.work-ledger/managed-request-id :rf/default in-flight)] @aborts)
          "the in-flight refetch in the departing scope is aborted")
      (is (nil? (rf.resources.work-ledger/get-record (runtime-db) in-flight))
          "and its work row leaves the ledger with the entry"))))

(deftest change-principal-refuses-a-malformed-from-before-clearing-anything
  (let [k   (owned-loaded! "ann" "a")
        cofx {:rf.db/runtime (runtime-db) :rf.frame/id :rf/default :db {} :rf/time-ms 1}]
    (testing "a scope tuple passed bare reads as its members, and the reserved head is refused"
      (is (thrown-with-msg?
            #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
            (rf.resources.events/change-principal-handler
              cofx [:rf.resource/change-principal {:from (viewer "ann") :cause :logout}]))))
    (testing "a non-vector :from is refused"
      (is (thrown-with-msg?
            #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
            (rf.resources.events/change-principal-handler
              cofx [:rf.resource/change-principal {:from :rf.scope/global :cause :logout}]))))
    (testing "a later invalid member stops the whole event, so the valid one before it is not cleared"
      (is (thrown-with-msg?
            #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
            (rf.resources.events/change-principal-handler
              cofx [:rf.resource/change-principal {:from [(viewer "ann") {:from-db :t/viewer}]
                                                   :cause :logout}]))))
    (is (= :loaded (:status (entry k))) "nothing was cleared")))

;; ===========================================================================
;; no automatic refetch touches an old-scope key — one test per trigger
;; ===========================================================================

(deftest no-focus-refetch-touches-an-old-scope-key
  (let [ann (owned-loaded! "ann" "a")
        sid (owned-loaded! "sid" "a")
        n   (count @requests)]
    ;; queued AHEAD of the event: the scan's refetch dispatch lands behind the clear
    (rf/dispatch-sync [:t/fire [[:rf.resource/window-focused] (change-principal [(viewer "ann")])]])
    (is (nil? (entry ann)) "the queued focus refetch does not bring the cleared entry back")
    (is (= ["/docs/a"] (requested-urls n)) "only the other principal's stale entry was refetched")
    (is (= :fetching (:status (entry sid))))
    (let [n (count @requests)]
      (rf/dispatch-sync [:rf.resource/window-focused])
      (is (and (nil? (entry ann)) (= [] (requested-urls n)))
          "a focus after the event finds nothing of the old scope"))))

(deftest no-reconnect-refetch-touches-an-old-scope-key
  (let [ann (owned-loaded! "ann" "a")
        n   (count @requests)]
    (rf/dispatch-sync [:t/fire [[:rf.resource/network-reconnected] (change-principal [(viewer "ann")])]])
    (is (and (nil? (entry ann)) (= [] (requested-urls n)))
        "the queued reconnect refetch does not recreate the cleared entry")
    (rf/dispatch-sync [:rf.resource/network-reconnected])
    (is (and (nil? (entry ann)) (= [] (requested-urls n)))
        "a reconnect after the event finds nothing of the old scope")))

(deftest no-poll-refetch-touches-an-old-scope-key
  (let [ann  (owned-loaded! "ann" "a")
        n    (count @requests)
        tick [:rf.resource.internal/poll-fired {:resource/key ann :hidden? false}]]
    (rf/dispatch-sync [:t/fire [tick (change-principal [(viewer "ann")])]])
    (is (and (nil? (entry ann)) (= [] (requested-urls n)))
        "the queued poll tick's refetch does not recreate the cleared entry")
    (rf/dispatch-sync tick)
    (is (and (nil? (entry ann)) (= [] (requested-urls n)))
        "a poll tick after the event finds no entry and stops")))

(deftest no-invalidation-refetch-touches-an-old-scope-key
  (let [ann        (owned-loaded! "ann" "a")
        n          (count @requests)
        invalidate [:rf.resource/invalidate-tags {:cross-scope? true :tags #{[:doc "a"]}
                                                  :cause [:mutation :t/save 1]}]]
    (rf/dispatch-sync [:t/fire [invalidate (change-principal [(viewer "ann")])]])
    (is (and (nil? (entry ann)) (= [] (requested-urls n)))
        "the queued invalidation refetch does not recreate the cleared entry")
    (rf/dispatch-sync invalidate)
    (is (and (nil? (entry ann)) (= [] (requested-urls n)))
        "an invalidation after the event matches nothing of the old scope")))

;; ===========================================================================
;; the replan half
;; ===========================================================================

(deftest change-principal-replans-the-active-route-under-the-new-identity
  (rf/dispatch-sync [:t/login "ann"])
  (rf/dispatch-sync [:rf.route/navigate {:to :t/docs :params {:page "intro"}}])
  (let [ann   (doc-key "ann" "intro")
        bob   (doc-key "bob" "intro")
        owner [:route :t/docs (:nav-token (slice))]]
    (settle-loaded! ann {:for "ann"})
    (let [n (count @requests)]
      (rf/dispatch-sync [:t/switch-to "bob"])
      (is (nil? (entry ann)) "the departing principal's route read is gone")
      (is (= [:loading #{owner}] [(:status (entry bob)) (:active-owners (entry bob))])
          "the new principal's read is ensured under the unchanged route owner")
      (is (= [[:account-switch]]
             [(:causes (rf.resources.work-ledger/get-record (runtime-db) (:current-work (entry bob))))])
          "the event's cause rides the replan's ensure verbatim")
      (is (= {(rf.resources.state/key-id bob) bob} (plan-slot)) "the plan names the new identity only")
      (is (= ["/docs/intro"] (requested-urls n)) "one request, for the new principal"))))

(deftest change-principal-without-an-active-route-only-clears
  (let [ann (owned-loaded! "ann" "a")
        n   (count @requests)]
    (rf/dispatch-sync (change-principal [(viewer "ann")]))
    (is (and (nil? (entry ann)) (nil? (slice)) (= [] (requested-urls n)))
        "with no route there is nothing to replan, and no error")))
