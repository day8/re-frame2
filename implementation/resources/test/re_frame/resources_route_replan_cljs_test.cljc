(ns re-frame.resources-route-replan-cljs-test
  "`[:rf.route/replan-resources {:cause …}]`, the RESOURCES half: same-token
  reconciliation through the one canonical planner
  (`re-frame.resources.route/route-resource-plan` in plan mode `:replan`,
  published as `:routing/on-route-replan`), driven end to end through routing
  on a URL-owning frame (Spec 016 §Route-plan replan — same-token
  reconciliation). Retained identities keep their owner, their in-flight work
  and issue no request; new ones are ensured under the caller cause verbatim;
  dropped ones lose the route owner and only that, by a same-owner subset
  release; the durable plan and blocking slots equal the new plan, an empty
  one clearing both. A failing replan ensures nothing, releases the whole
  owner and installs `:plan-cause :replan` with the nav-token present.

  The routing-side contract is `re-frame.routing-replan-test`; the corpus rows
  are the `ep-0037-replan-*` fixtures."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   ;; load-bearing side-effecting requires: register the resources + routing
   ;; events / subs and resources' late-bound :routing/* integration hooks.
   [re-frame.resources]
   [re-frame.resources.route :as rf.resources.route]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   [re-frame.resources.test-support]
   [re-frame.routing :as rf.routing]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.registrar :as rf.registrar]
   [re-frame.trace.tooling :as rf.trace.tooling]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

;; ---- fixture --------------------------------------------------------------

(def ^:private requests
  "Every managed-HTTP request lowered during a test, in order."
  (atom []))

(def ^:private extra-admitted?
  "The conditional occurrence's admission switch: `:when` receives the route and
  the reserved ctx, never app-db, so a test-owned atom makes an occurrence enter
  and leave the plan between two replans."
  (atom false))

(defn- init!
  "A URL-owning frame with routing integration, the viewer resolver, four
  resources (two viewer-scoped, two global), a parent shell with a leaf under
  it, a route whose only occurrence is conditional, and login / logout events
  that move the resolver's input."
  []
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "replan suite default app frame."})
  (rf.routing/reset-counters!)
  (rf.resources.route/install-routing-integration!)
  (rf.registrar/clear-kind! :resource-scope)
  (reset! requests [])
  (reset! extra-admitted? false)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! requests conj args) nil))
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/reg-resource-scope :t/viewer
    {:inputs {:username [:db [:auth :user :username]]}}
    (fn [{:keys [username]} _ctx]
      (when username [:rf.scope/viewer {:username username}])))
  (rf/reg-resource :t/shell
    {:scope {:from-db :t/viewer} :params-schema [:map]}
    (fn [_ _] {:request {:method :get :url "/shell"}}))
  (rf/reg-resource :t/docs
    {:scope {:from-db :t/viewer} :params-schema [:map [:page :string]]}
    (fn [{:keys [page]} _] {:request {:method :get :url (str "/docs/" page)}}))
  (rf/reg-resource :t/global
    {:scope :rf.scope/global :params-schema [:map]}
    (fn [_ _] {:request {:method :get :url "/global"}}))
  (rf/reg-resource :t/extra
    {:scope :rf.scope/global :params-schema [:map]}
    (fn [_ _] {:request {:method :get :url "/extra"}}))
  (rf/reg-route :t/shell
    {:resources [{:resource :t/shell :blocking? true}]} "/")
  (rf/reg-route :t/docs
    {:parent    :t/shell
     :params    [:map [:page :string]]
     :resources [{:resource  :t/docs
                  :params    (fn [route] {:page (get-in route [:params :page])})
                  :blocking? false}
                 {:resource :t/global :blocking? true}
                 {:resource :t/extra
                  :when     (fn [_route _ctx] @extra-admitted?)}]} "/docs/:page")
  (rf/reg-route :t/cond
    {:resources [{:resource  :t/global
                  :blocking? true
                  :when      (fn [_route _ctx] @extra-admitted?)}]} "/cond")
  (rf/reg-event :t/login  (fn [{:keys [db]} [_ u]] {:db (assoc-in db [:auth :user :username] u)}))
  (rf/reg-event :t/logout (fn [{:keys [db]} _]     {:db (update db :auth dissoc :user)})))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [k] (get-in (runtime-db) (rf.resources.state/entry-path k)))
(defn- entries [] (get-in (runtime-db) (rf.resources.state/entries-path)))
(defn- slice [] (get-in (runtime-db) [:rf.runtime/routing :current]))
(defn- token [] (:nav-token (slice)))
(defn- route-owner [] [:route (:route-id (slice)) (token)])
(defn- plan-slot [] (get-in (runtime-db) [:rf.runtime/routing :resource-plan (token)]))
(defn- blocking-slot [] (get-in (runtime-db) [:rf.runtime/routing :resource-blocking (token)]))
(defn- has-plan-slot? [] (contains? (get-in (runtime-db) [:rf.runtime/routing :resource-plan]) (token)))
(defn- has-blocking-slot? [] (contains? (get-in (runtime-db) [:rf.runtime/routing :resource-blocking]) (token)))
(defn- owner-index [owner] (get-in (runtime-db) (conj (rf.resources.state/owner-index-path) owner)))

(defn- by-id
  "The byte-keyed `{<key-id> <scoped-key>}` carrier the slots hold."
  [& ks]
  (into {} (map (juxt rf.resources.state/key-id identity)) ks))

(defn- viewer [u] [:rf.scope/viewer {:username u}])
(defn- shell-key [u] (rf.resources.state/scoped-resource-key (viewer u) :t/shell {}))
(defn- docs-key [u page] (rf.resources.state/scoped-resource-key (viewer u) :t/docs {:page page}))
(def ^:private global-key (rf.resources.state/scoped-resource-key :rf.scope/global :t/global {}))
(def ^:private extra-key  (rf.resources.state/scoped-resource-key :rf.scope/global :t/extra {}))

(defn- owned? [k] (contains? (:active-owners (entry k)) (route-owner)))
(defn- work-record [k] (rf.resources.work-ledger/get-record (runtime-db) (:current-work (entry k))))
(defn- live? [k] (rf.resources.work-ledger/live-work? (runtime-db) (:current-work (entry k))))
(defn- request-count [] (count @requests))

(defn- settle-loaded!
  "Drive the just-ensured entry at `k` to :loaded."
  [k data]
  (let [e (entry k)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key k
                        :work/id      (:current-work e)
                        :generation   (:generation e)
                        :data         data}])))

(defn- replan! [cause]
  (rf/dispatch-sync [:rf.route/replan-resources {:cause cause}]))

(defn- record-traces!
  "Run `body-fn`; return every trace event whose `:operation` is in `ops`."
  [ops body-fn]
  (let [seen (atom [])
        k    ::replan-recorder]
    (rf.trace.tooling/register-listener!
      k (fn [ev] (when (contains? ops (:operation ev)) (swap! seen conj ev))))
    (try (body-fn)
         (finally (rf.trace.tooling/unregister-listener! k)))
    @seen))

(defn- op-tags [traces operation]
  (:tags (first (filter #(= operation (:operation %)) traces))))

(defn- plan-counts [tags]
  (select-keys tags [:plan-cause :ensured :kept :removed]))

(defn- navigate-to-docs-as! [user]
  (rf/dispatch-sync [:t/login user])
  (rf/dispatch-sync [:rf.route/navigate {:to :t/docs :params {:page "intro"}}]))

;; ===========================================================================
;; A → B same-route scope change
;; ===========================================================================

(deftest replan-a-to-b-scope-change-reconciles-under-the-same-owner
  (navigate-to-docs-as! "ann")
  (let [tok       (token)
        own       (route-owner)
        ann-shell (shell-key "ann")
        ann-docs  (docs-key "ann" "intro")
        bob-shell (shell-key "bob")
        bob-docs  (docs-key "bob" "intro")]
    (is (= (by-id ann-shell ann-docs global-key) (plan-slot)) "FIXTURE — the activation's three-identity plan")
    ;; settled, so the retained identity is genuinely reusable
    (settle-loaded! ann-shell {:s 1})
    (settle-loaded! ann-docs  {:d 1})
    (settle-loaded! global-key {:g 1})
    (is (= :idle (:transition (slice))) "FIXTURE — everything settled")
    (let [n      (request-count)
          traces (record-traces! #{:rf.resource/route-plan :rf.resource/owner-released}
                   (fn []
                     ;; the principal switch: an app-db write, no navigation
                     (rf/dispatch-sync [:t/login "bob"])
                     (replan! [:account-switch])))]
      (is (= [["/docs/intro" "/shell"] true :loaded]
             [(sort (map #(get-in % [:request :url]) (drop n @requests))) (owned? global-key)
              (:status (entry global-key))])
          "only bob's two reads are requested; the retained global keeps its owner and data, unrequested")
      (is (= [true true :loading [[:account-switch]] [[:account-switch]]]
             [(owned? bob-shell) (owned? bob-docs) (:status (entry bob-shell))
              (:causes (work-record bob-shell)) (:causes (work-record bob-docs))])
          "the B identities are ensured and owned under the caller cause verbatim")
      (is (= [false false :loaded :loaded]
             [(owned? ann-shell) (owned? ann-docs) (:status (entry ann-shell)) (:status (entry ann-docs))])
          "the A-only identities lose the owner and only the owner")
      (is (= [tok own (by-id bob-shell bob-docs global-key) (by-id bob-shell) :loading nil]
             [(token) (route-owner) (plan-slot) (blocking-slot) (:transition (slice)) (:error (slice))])
          "the slots equal the new plan (the global read already has data, so only bob's shell blocks) under the same token")
      (let [tags (op-tags traces :rf.resource/route-plan)]
        (is (= [{:plan-cause :replan :ensured 2 :kept 1 :removed 2} [:account-switch] tok [:t/shell :t/docs]
                [global-key] #{ann-shell ann-docs}]
               [(plan-counts tags) (:replan-cause tags) (:nav-token tags) (:branch tags)
                (:kept-identities tags) (set (:removed-identities tags))])
            "one planner row, discriminated as a replan with the nav-token present, carries the exact partition"))
      (is (= [own #{ann-shell ann-docs}]
             ((juxt :owner (comp set :released)) (op-tags traces :rf.resource/owner-released)))
          "the SAME owner is released from exactly the dropped identities, by scoped key"))))

;; ===========================================================================
;; unresolved → resolved repair on a composed route
;; ===========================================================================

(deftest replan-repairs-an-unresolved-scope-plan-on-a-composed-route
  ;; no login: the parent shell's spec scope fails closed, so the whole
  ;; activation plan fails
  (rf/dispatch-sync [:rf.route/navigate {:to :t/docs :params {:page "intro"}}])
  (let [tok (token)]
    (is (= [:rf.error/resource-route-plan nil :error false nil]
           [(:rf.error/id (:error (slice))) (:plan-cause (:error (slice))) (:transition (slice))
            (has-plan-slot?) (seq (entries))])
        "an activation failure carries no plan-cause, writes no plan slot and ensures nothing")
    (rf/dispatch-sync [:t/login "ann"])
    (let [n      (request-count)
          traces (record-traces! #{:rf.resource/route-plan} #(replan! [:session-restore]))]
      (is (= [tok true true true 3]
             [(token) (owned? (shell-key "ann")) (owned? (docs-key "ann" "intro")) (owned? global-key)
              (- (request-count) n)])
          "under the same token the inherited parent read and both leaf reads are ensured and owned")
      (is (= [(by-id (shell-key "ann") (docs-key "ann" "intro") global-key) (by-id (shell-key "ann") global-key)
              nil :loading]
             [(plan-slot) (blocking-slot) (:error (slice)) (:transition (slice))])
          "exact membership is stored, both blocking reads pending, and the planning error repaired")
      (is (= {:plan-cause :replan :ensured 3 :kept 0 :removed 0}
             (plan-counts (op-tags traces :rf.resource/route-plan)))
          "there was no prior plan to keep or drop from"))
    (settle-loaded! (shell-key "ann") {})
    (settle-loaded! global-key {})
    (is (= :idle (:transition (slice))) "readiness lands as the blocking reads settle")))

;; ===========================================================================
;; a failing replan
;; ===========================================================================

(deftest failing-replan-releases-the-whole-owner-and-clears-the-slots
  (navigate-to-docs-as! "ann")
  (let [tok       (token)
        own       (route-owner)
        ann-shell (shell-key "ann")
        ann-docs  (docs-key "ann" "intro")
        ks        [ann-shell ann-docs global-key]]
    (is (= [true true 3] [(every? owned? ks) (every? live? ks) (count (entries))])
        "FIXTURE — everything owned and in flight")
    ;; the identity input goes transiently unresolved, with no route change
    (rf/dispatch-sync [:t/logout])
    (let [n      (request-count)
          traces (record-traces! #{:rf.resource/route-plan :rf.resource/owner-released}
                                 #(replan! [:logout]))]
      (is (= [n 3] [(request-count) (count (entries))]) "no partial ensure")
      (is (= [true true nil true [own (set ks)]]
             [(not-any? owned? ks) (every? #(some? (entry %)) ks) (owner-index own) (not-any? live? ks)
              ((juxt :owner (comp set :released)) (op-tags traces :rf.resource/owner-released))])
          "the standing owner is released from every prior identity (the entries survive) and its orphaned work abort-requested")
      (is (= [false false tok :error]
             [(has-plan-slot?) (has-blocking-slot?) (token) (:transition (slice))])
          "both slots are cleared on the token of a committed failed replan")
      (is (= [:rf.error/resource-route-plan :replan [:logout] tok :t/shell]
             ((juxt :rf.error/id :plan-cause :replan-cause :nav-token :resource-id) (:error (slice))))
          "the error carries the plan cause, the caller cause, the nav-token and the first failing contributor")
      (let [tags (op-tags traces :rf.resource/route-plan)]
        (is (= [true {:plan-cause :replan :ensured 0 :kept 0 :removed 3} []]
               [(:plan-error tags) (plan-counts tags) (:identities tags)])
            "the planner row reports the atomicity rule")))
    (rf/dispatch-sync [:t/login "ann"])
    (replan! [:session-restore])
    (is (= [nil :loading true (by-id ann-shell ann-docs global-key)]
           [(:error (slice)) (:transition (slice)) (every? owned? ks) (plan-slot)])
        "a later resolution and replan repairs it from scratch")))

;; ===========================================================================
;; a conditional occurrence entering and leaving the plan
;; ===========================================================================

(deftest replan-admits-and-drops-a-conditional-occurrence-with-a-subset-release
  (navigate-to-docs-as! "ann")
  (let [tok  (token)
        kept [(shell-key "ann") (docs-key "ann" "intro") global-key]
        base (apply by-id kept)]
    (is (= [base nil] [(plan-slot) (entry extra-key)]) "FIXTURE — the conditional occurrence is not admitted yet")
    (reset! extra-admitted? true)
    (replan! [:flag-on])
    (is (= [true [[:flag-on]] (assoc base (rf.resources.state/key-id extra-key) extra-key) tok]
           [(owned? extra-key) (:causes (work-record extra-key)) (plan-slot) (token)])
        "ENTERING: the admitted occurrence is ensured, owned and recorded")
    (reset! extra-admitted? false)
    (let [n      (request-count)
          traces (record-traces! #{:rf.resource/owner-released :rf.resource/route-plan}
                                 #(replan! [:flag-off]))
          tags   (op-tags traces :rf.resource/route-plan)]
      (is (= [n false true true true base tok]
             [(request-count) (owned? extra-key) (some? (entry extra-key)) (every? owned? kept) (every? live? kept)
              (plan-slot) (token)])
          "LEAVING: the dropped occurrence loses the owner, its entry surviving; the kept ones keep owner and live work, unrequested")
      (is (= [[extra-key] 3 1 [extra-key]]
             [(:released (op-tags traces :rf.resource/owner-released)) (:kept tags) (:removed tags)
              (:removed-identities tags)])
          "a same-owner SUBSET release names only the dropped identity"))))

;; ===========================================================================
;; retained in-flight work, and an empty next plan
;; ===========================================================================

(deftest replan-keeps-retained-in-flight-work-owned-and-unaborted
  (navigate-to-docs-as! "ann")
  (let [ks     [(shell-key "ann") (docs-key "ann" "intro") global-key]
        n      (request-count)
        wids   (mapv #(:current-work (entry %)) ks)
        traces (record-traces! #{:rf.resource/route-plan :rf.resource/owner-released}
                               #(replan! [:no-change]))]
    (is (= [n true wids true nil]
           [(request-count) (every? owned? ks) (mapv #(:current-work (entry %)) ks) (every? live? ks)
            (op-tags traces :rf.resource/owner-released)])
        "every identity is adopted: no request, the same live attempts, and no release fx at all")
    (is (= [{:plan-cause :replan :ensured 0 :kept 3 :removed 0}
            (apply by-id ks) (by-id (shell-key "ann") global-key) :loading]
           [(plan-counts (op-tags traces :rf.resource/route-plan)) (plan-slot) (blocking-slot) (:transition (slice))]))))

(deftest replan-to-an-empty-plan-clears-both-slots
  ;; a fresh activation token has no old slot to clear, a replan's token does
  (reset! extra-admitted? true)
  (rf/dispatch-sync [:rf.route/navigate {:to :t/cond}])
  (let [tok (token)]
    (is (= [(by-id global-key) (by-id global-key) :loading] [(plan-slot) (blocking-slot) (:transition (slice))])
        "FIXTURE — the conditional occurrence is planned and blocking")
    (reset! extra-admitted? false)
    (let [traces (record-traces! #{:rf.resource/owner-released} #(replan! [:flag-off]))]
      (is (= [tok false false false true :idle nil [global-key]]
             [(token) (has-plan-slot?) (has-blocking-slot?) (owned? global-key) (some? (entry global-key))
              (:transition (slice)) (:error (slice)) (:released (op-tags traces :rf.resource/owner-released))])
          "both slots are REMOVED, the only identity loses the owner, and nothing blocking means :idle"))))

;; ===========================================================================
;; the :rf.resource.internal/release-owner-identities primitive
;; ===========================================================================

(deftest release-owner-identities-releases-only-the-named-identities-and-only-this-owner
  (let [A   [:app :a]
        B   [:app :b]
        k1  global-key
        k2  extra-key
        rel #(record-traces! #{:rf.resource/owner-released}
                             (fn [] (rf/dispatch-sync [:rf.resource.internal/release-owner-identities
                                                       {:owner A :identities (by-id %)}])))]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :t/global :params {} :owner A :cause [:a1]}])
    (rf/dispatch-sync [:rf.resource/ensure {:resource :t/extra  :params {} :owner A :cause [:a2]}])
    ;; B joins k1's in-flight work
    (rf/dispatch-sync [:rf.resource/ensure {:resource :t/global :params {} :owner B :cause [:b1]}])
    (is (= [#{A B} #{A} #{(rf.resources.state/key-id k1) (rf.resources.state/key-id k2)}]
           [(:active-owners (entry k1)) (:active-owners (entry k2)) (owner-index A)])
        "FIXTURE")
    (let [traces (rel k1)]
      (is (= [#{B} #{A} true #{(rf.resources.state/key-id k2)} [A [k1] []]]
             [(:active-owners (entry k1)) (:active-owners (entry k2)) (live? k1) (owner-index A)
              ((juxt :owner :released :aborted) (op-tags traces :rf.resource/owner-released))])
          "only the named identity, only this owner, and no abort while B still needs it"))
    (rel k1)
    (is (= [#{B} #{(rf.resources.state/key-id k2)}] [(:active-owners (entry k1)) (owner-index A)])
        "an identity the owner does not hold is a no-op")
    (let [traces (rel k2)]
      (is (= [true false nil [(:current-work (entry k2))]]
             [(empty? (:active-owners (entry k2))) (live? k2) (owner-index A)
              (:aborted (op-tags traces :rf.resource/owner-released))])
          "releasing the LAST owner aborts the in-flight identity and drops the index row"))))
