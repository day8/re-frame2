(ns re-frame.resources-route-replan-cljs-test
  "rf2-y8jjk — `[:rf.route/replan-resources {:cause …}]`, the RESOURCES half:
  same-token reconciliation through the ONE canonical planner
  (`re-frame.resources.route/route-resource-plan` in plan mode `:replan`,
  published as `:routing/on-route-replan`), driven END TO END through routing
  on a URL-owning frame.

  What is pinned here, and only here (Spec 016 §Route-plan replan — same-token
  reconciliation):

    - A → B same-route scope change: the unchanged identity is RETAINED with no
      request (the work ledger, not only the entry); the B identities are
      ensured + owned with the caller cause VERBATIM; the A-only identities lose
      the route owner and ONLY the owner (the entry survives); the durable plan /
      blocking slots equal the newly materialized plan; the token is unchanged.
    - unresolved → resolved REPAIR on a composed route: the inherited parent
      read is ensured, the slice error is cleared, exact membership is stored.
    - a FAILING replan: no partial ensure; the standing owner gone from EVERY
      prior identity (the whole-owner release); both slots cleared; `:error`
      installed with `:plan-cause :replan` and the nav-token PRESENT.
    - a conditional occurrence ENTERING and LEAVING the plan — the leave is a
      same-owner SUBSET release naming only the dropped identity.
    - retained in-flight work keeps its owner and is not aborted.
    - an EMPTY next plan clears both slots (the activation commit's `cond->`
      trap: a fresh token has no old slot, the replan's token does).
    - the `:rf.resource.internal/release-owner-identities` primitive itself: only the
      named identities, only this owner, no abort while another owner remains.

  The routing-side contract (the request gate, the unconditional slot writes
  against a stub plan, the absence of every navigation effect) is
  `re-frame.routing-replan-test`; the flagship receipt is the RealWorld
  acceptance test; the corpus row is the `ep-0037-replan-*` fixtures.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex; Shadow's `:node-test` build via `cljs-test$`. The
  `-cljs-test` suffix is load-bearing (rf2-dn6v7)."
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
  "Every managed-HTTP request lowered during a test, in order — the ledger the
  'no second request' assertions read."
  (atom []))

(def ^:private extra-admitted?
  "The conditional occurrence's admission switch. `:when` receives the route and
  the reserved ctx — never app-db — so a test-owned atom is the honest way to
  make an occurrence enter and leave the plan between two replans."
  (atom false))

(defn- init!
  "Per-test setup: a URL-owning default frame, the late-bound routing
  integration, the viewer resolver, four resources (two viewer-scoped through
  the resolver, two global), a parent shell + a leaf under it, a conditional
  route, and the login / logout events that move the resolver's input."
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
  ;; the parent shell owns the viewer-scoped shell read; the leaf inherits it
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
  ;; a route whose ONLY occurrence is conditional — the empty-next-plan case
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
  "Drive the just-ensured entry at `k` to :loaded by dispatching the internal
  success reply for its current work."
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
  "Run `body-fn` with a trace listener installed; return the vector of every
  event whose `:operation` is in `ops`, in capture order."
  [ops body-fn]
  (let [seen (atom [])
        k    ::replan-recorder]
    (rf.trace.tooling/register-listener!
      k (fn [ev] (when (contains? ops (:operation ev)) (swap! seen conj ev))))
    (try (body-fn)
         (finally (rf.trace.tooling/unregister-listener! k)))
    @seen))

(defn- op [traces operation]
  (first (filter #(= operation (:operation %)) traces)))

;; ===========================================================================
;; 1. A → B same-route scope change
;; ===========================================================================

(deftest replan-a-to-b-scope-change-reconciles-under-the-same-owner
  (rf/dispatch-sync [:t/login "ann"])
  (rf/dispatch-sync [:rf.route/navigate {:to :t/docs :params {:page "intro"}}])
  (let [tok      (token)
        own      (route-owner)
        ann-shell (shell-key "ann")
        ann-docs  (docs-key "ann" "intro")
        bob-shell (shell-key "bob")
        bob-docs  (docs-key "bob" "intro")]
    (is (= (by-id ann-shell ann-docs global-key) (plan-slot))
        "the activation recorded the three-identity plan under the token")
    ;; settle everything so the retained identity is genuinely reusable (own
    ;; usable data) and the counters below are unambiguous
    (settle-loaded! ann-shell {:s 1})
    (settle-loaded! ann-docs  {:d 1})
    (settle-loaded! global-key {:g 1})
    (is (= :idle (:transition (slice))))
    (let [n      (request-count)
          traces (record-traces! #{:rf.resource/route-plan :rf.resource/owner-released}
                   (fn []
                     ;; the principal switch: an app-db write, NO navigation
                     (rf/dispatch-sync [:t/login "bob"])
                     (replan! [:account-switch])))]
      (testing "UNCHANGED identities are retained with NO request — the work ledger says so"
        (is (= 2 (- (request-count) n))
            "exactly two requests were lowered — bob's shell and bob's docs; the global read was NOT re-issued")
        (is (every? (fn [args] (not= "/global" (get-in args [:request :url])))
                    (drop n @requests))
            "…and neither of them targets the retained global identity")
        (is (owned? global-key) "the retained identity keeps the route owner")
        (is (= :loaded (:status (entry global-key)))))
      (testing "the B identities are ensured + owned, with the caller cause VERBATIM"
        (is (owned? bob-shell))
        (is (owned? bob-docs))
        (is (= :loading (:status (entry bob-shell))))
        (is (= [[:account-switch]] (:causes (work-record bob-shell))))
        (is (= [[:account-switch]] (:causes (work-record bob-docs)))))
      (testing "the A-only identities lose the owner — and ONLY the owner"
        (is (some? (entry ann-shell)) "the entry survives (GC decides its fate, not the replan)")
        (is (some? (entry ann-docs)))
        (is (not (owned? ann-shell)))
        (is (not (owned? ann-docs)))
        (is (= :loaded (:status (entry ann-shell))) "…with its data intact"))
      (testing "the durable slots equal the newly materialized plan; the token is unchanged"
        (is (= tok (token)))
        (is (= own (route-owner)))
        (is (= (by-id bob-shell bob-docs global-key) (plan-slot)))
        (is (= (by-id bob-shell) (blocking-slot))
            "the blocking shell is pending under bob; the global read already has usable data")
        (is (= :loading (:transition (slice))))
        (is (nil? (:error (slice)))))
      (testing "the trace evidence: ONE planner row, discriminated, with the exact partition"
        (let [tags (:tags (op traces :rf.resource/route-plan))]
          (is (= :replan (:plan-cause tags)))
          (is (= [:account-switch] (:replan-cause tags)))
          (is (= tok (:nav-token tags)) "the nav-token is PRESENT on a replan row")
          (is (= [:t/shell :t/docs] (:branch tags)))
          (is (= 2 (:ensured tags)))
          (is (= 1 (:kept tags)))
          (is (= 2 (:removed tags)))
          (is (= [global-key] (:kept-identities tags)))
          (is (= #{ann-shell ann-docs} (set (:removed-identities tags)))))
        (let [tags (:tags (op traces :rf.resource/owner-released))]
          (is (= own (:owner tags)) "the SAME owner was released — from a subset")
          (is (= #{ann-shell ann-docs} (set (:released tags)))
              "…naming exactly the dropped identities, by scoped key"))))))

;; ===========================================================================
;; 2. unresolved → resolved REPAIR on a composed route
;; ===========================================================================

(deftest replan-repairs-an-unresolved-scope-plan-on-a-composed-route
  ;; no login: the viewer resolver yields nil, so the parent shell's spec scope
  ;; fails closed and the WHOLE plan fails at activation
  (rf/dispatch-sync [:rf.route/navigate {:to :t/docs :params {:page "intro"}}])
  (let [tok (token)]
    (is (= :rf.error/resource-route-plan (:rf.error/id (:error (slice)))))
    (is (nil? (:plan-cause (:error (slice)))) "an ACTIVATION failure carries no plan-cause")
    (is (= :error (:transition (slice))))
    (is (not (has-plan-slot?)) "a failed activation writes no plan slot")
    (is (empty? (entries)) "no entry under any scope — no partial ensure")
    (rf/dispatch-sync [:t/login "ann"])
    (let [n      (request-count)
          traces (record-traces! #{:rf.resource/route-plan} #(replan! [:session-restore]))]
      (is (= tok (token)) "no navigation — the same token")
      (is (owned? (shell-key "ann")) "the INHERITED parent read is ensured under the route owner")
      (is (owned? (docs-key "ann" "intro")))
      (is (owned? global-key))
      (is (= 3 (- (request-count) n)))
      (is (= (by-id (shell-key "ann") (docs-key "ann" "intro") global-key) (plan-slot))
          "exact membership is stored")
      (is (= (by-id (shell-key "ann") global-key) (blocking-slot))
          "both blocking requirements are pending")
      (is (nil? (:error (slice))) "the planning error is REPAIRED")
      (is (= :loading (:transition (slice))))
      (let [tags (:tags (op traces :rf.resource/route-plan))]
        (is (= :replan (:plan-cause tags)))
        (is (= 3 (:ensured tags)))
        (is (= 0 (:kept tags)))
        (is (= 0 (:removed tags)) "there was no prior plan to drop from")))
    (settle-loaded! (shell-key "ann") {})
    (settle-loaded! global-key {})
    (is (= :idle (:transition (slice))) "readiness lands as the blocking reads settle")))

;; ===========================================================================
;; 3. a FAILING replan — no partial ensure, whole-owner release, slots cleared
;; ===========================================================================

(deftest failing-replan-releases-the-whole-owner-and-clears-the-slots
  (rf/dispatch-sync [:t/login "ann"])
  (rf/dispatch-sync [:rf.route/navigate {:to :t/docs :params {:page "intro"}}])
  (let [tok       (token)
        own       (route-owner)
        ann-shell (shell-key "ann")
        ann-docs  (docs-key "ann" "intro")
        ks        [ann-shell ann-docs global-key]]
    (is (every? owned? ks))
    (is (every? live? ks) "everything is in flight")
    (is (= 3 (count (entries))))
    ;; the identity input goes transiently UNRESOLVED (a logout with no route
    ;; change) — the shell's spec scope now fails closed
    (rf/dispatch-sync [:t/logout])
    (let [n      (request-count)
          traces (record-traces! #{:rf.resource/route-plan :rf.resource/owner-released}
                                 #(replan! [:logout]))]
      (testing "no partial ensure"
        (is (= n (request-count)) "no request was lowered")
        (is (= 3 (count (entries))) "no entry was created"))
      (testing "the standing owner is released from EVERY prior identity (deliberately destructive)"
        (is (every? #(not (owned? %)) ks))
        (is (every? #(some? (entry %)) ks) "the entries themselves survive")
        (is (nil? (owner-index own)) "the owner-index row is gone")
        (is (every? #(not (live? %)) ks) "orphaned in-flight work is abort-requested — no other owner needed it")
        (let [tags (:tags (op traces :rf.resource/owner-released))]
          (is (= own (:owner tags)))
          (is (= (set ks) (set (:released tags))))))
      (testing "both slots are cleared and the failure is installed on the slice"
        (is (not (has-plan-slot?)))
        (is (not (has-blocking-slot?)))
        (is (= tok (token)) "the token itself stays — this is a committed failed REPLAN")
        (is (= :error (:transition (slice))))
        (let [err (:error (slice))]
          (is (= :rf.error/resource-route-plan (:rf.error/id err)))
          (is (= :replan (:plan-cause err)))
          (is (= [:logout] (:replan-cause err)) "the caller cause rides under :replan-cause …")
          (is (= tok (:nav-token err)) "… the nav-token is PRESENT …")
          (is (= :t/shell (:resource-id err)) "… and the first failing contributor is named")))
      (testing "the planner row reports the atomicity rule directly"
        (let [tags (:tags (op traces :rf.resource/route-plan))]
          (is (true? (:plan-error tags)))
          (is (= :replan (:plan-cause tags)))
          (is (= 0 (:ensured tags)))
          (is (= 0 (:kept tags)))
          (is (= 3 (:removed tags)))
          (is (= [] (:identities tags))))))
    (testing "…and a later resolution + replan repairs it from scratch"
      (rf/dispatch-sync [:t/login "ann"])
      (replan! [:session-restore])
      (is (nil? (:error (slice))))
      (is (= :loading (:transition (slice))))
      (is (every? owned? ks))
      (is (= (by-id ann-shell ann-docs global-key) (plan-slot))))))

;; ===========================================================================
;; 4. a conditional occurrence ENTERING and LEAVING the plan (subset release)
;; ===========================================================================

(deftest replan-admits-and-drops-a-conditional-occurrence-with-a-subset-release
  (rf/dispatch-sync [:t/login "ann"])
  (rf/dispatch-sync [:rf.route/navigate {:to :t/docs :params {:page "intro"}}])
  (let [tok  (token)
        base (by-id (shell-key "ann") (docs-key "ann" "intro") global-key)]
    (is (= base (plan-slot)))
    (is (nil? (entry extra-key)) "the conditional occurrence is not admitted yet")
    (testing "ENTERING: the newly admitted occurrence is ensured + owned + recorded"
      (reset! extra-admitted? true)
      (replan! [:flag-on])
      (is (owned? extra-key))
      (is (= [[:flag-on]] (:causes (work-record extra-key))))
      (is (= (assoc base (rf.resources.state/key-id extra-key) extra-key) (plan-slot)))
      (is (= tok (token))))
    (testing "LEAVING: a same-owner SUBSET release names ONLY the dropped identity"
      (reset! extra-admitted? false)
      (let [n      (request-count)
            traces (record-traces! #{:rf.resource/owner-released :rf.resource/route-plan}
                                   #(replan! [:flag-off]))]
        (is (= n (request-count)) "kept identities are adopted, not re-requested")
        (is (not (owned? extra-key)) "the dropped occurrence lost the owner")
        (is (some? (entry extra-key)) "…but its entry survives")
        (is (every? owned? [(shell-key "ann") (docs-key "ann" "intro") global-key])
            "every kept identity keeps the owner — the release was a SUBSET")
        (is (every? live? [(shell-key "ann") (docs-key "ann" "intro") global-key])
            "…and their in-flight work was never orphaned")
        (is (= base (plan-slot)))
        (is (= tok (token)))
        (is (= [extra-key] (:released (:tags (op traces :rf.resource/owner-released)))))
        (let [tags (:tags (op traces :rf.resource/route-plan))]
          (is (= 3 (:kept tags)))
          (is (= 1 (:removed tags)))
          (is (= [extra-key] (:removed-identities tags))))))))

;; ===========================================================================
;; 5. retained in-flight work keeps its owner and is not aborted
;; ===========================================================================

(deftest replan-keeps-retained-in-flight-work-owned-and-unaborted
  (rf/dispatch-sync [:t/login "ann"])
  (rf/dispatch-sync [:rf.route/navigate {:to :t/docs :params {:page "intro"}}])
  (let [ks     [(shell-key "ann") (docs-key "ann" "intro") global-key]
        n      (request-count)
        wids   (mapv #(:current-work (entry %)) ks)
        traces (record-traces! #{:rf.resource/route-plan :rf.resource/owner-released}
                               #(replan! [:no-change]))]
    (is (= n (request-count)) "no request — every identity is retained and ADOPTED")
    (is (every? owned? ks))
    (is (= wids (mapv #(:current-work (entry %)) ks)) "the same in-flight attempts")
    (is (every? live? ks) "…still live — none abort-requested")
    (is (nil? (op traces :rf.resource/owner-released)) "nothing was dropped, so no release fx at all")
    (let [tags (:tags (op traces :rf.resource/route-plan))]
      (is (= 3 (:kept tags)))
      (is (= 0 (:ensured tags)))
      (is (= 0 (:removed tags)))
      (is (= :replan (:plan-cause tags))))
    (is (= (apply by-id ks) (plan-slot)))
    (is (= (by-id (shell-key "ann") global-key) (blocking-slot)))
    (is (= :loading (:transition (slice))))))

;; ===========================================================================
;; 6. an EMPTY next plan clears both slots (the cond-> trap)
;; ===========================================================================

(deftest replan-to-an-empty-plan-clears-both-slots
  (reset! extra-admitted? true)
  (rf/dispatch-sync [:rf.route/navigate {:to :t/cond}])
  (let [tok (token)]
    (is (= (by-id global-key) (plan-slot)))
    (is (= (by-id global-key) (blocking-slot)))
    (is (= :loading (:transition (slice))))
    (reset! extra-admitted? false)
    (let [traces (record-traces! #{:rf.resource/owner-released} #(replan! [:flag-off]))]
      (is (= tok (token)))
      (is (not (has-plan-slot?)) "the plan slot is REMOVED — not left holding the prior identity")
      (is (not (has-blocking-slot?)) "the blocking slot is REMOVED")
      (is (not (owned? global-key)) "the only identity lost the owner")
      (is (some? (entry global-key)))
      (is (= :idle (:transition (slice))) "nothing blocking → :idle")
      (is (nil? (:error (slice))))
      (is (= [global-key] (:released (:tags (op traces :rf.resource/owner-released))))))))

;; ===========================================================================
;; 7. the :rf.resource.internal/release-owner-identities primitive
;; ===========================================================================

(deftest release-owner-identities-releases-only-the-named-identities-and-only-this-owner
  (let [A  [:app :a]
        B  [:app :b]
        k1 global-key
        k2 extra-key]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :t/global :params {} :owner A :cause [:a1]}])
    (rf/dispatch-sync [:rf.resource/ensure {:resource :t/extra  :params {} :owner A :cause [:a2]}])
    ;; B joins k1's in-flight work
    (rf/dispatch-sync [:rf.resource/ensure {:resource :t/global :params {} :owner B :cause [:b1]}])
    (is (= #{A B} (:active-owners (entry k1))))
    (is (= #{A} (:active-owners (entry k2))))
    (is (= #{(rf.resources.state/key-id k1) (rf.resources.state/key-id k2)} (owner-index A)))
    (testing "only the NAMED identity, only THIS owner, and no abort while another owner remains"
      (let [traces (record-traces! #{:rf.resource/owner-released}
                     #(rf/dispatch-sync [:rf.resource.internal/release-owner-identities
                                         {:owner A :identities (by-id k1)}]))]
        (is (= #{B} (:active-owners (entry k1))) "A released from k1; B survives")
        (is (= #{A} (:active-owners (entry k2))) "A still owns k2 — it was not named")
        (is (rf.resources.work-ledger/live-work? (runtime-db) (:current-work (entry k1)))
            "k1's in-flight work is NOT aborted — B still needs it")
        (is (= #{(rf.resources.state/key-id k2)} (owner-index A)) "the owner-index drops only k1")
        (let [tags (:tags (op traces :rf.resource/owner-released))]
          (is (= A (:owner tags)))
          (is (= [k1] (:released tags)))
          (is (= [] (:aborted tags))))))
    (testing "an identity the owner does not hold is a no-op"
      (rf/dispatch-sync [:rf.resource.internal/release-owner-identities {:owner A :identities (by-id k1)}])
      (is (= #{B} (:active-owners (entry k1))))
      (is (= #{(rf.resources.state/key-id k2)} (owner-index A))))
    (testing "releasing the LAST owner from an in-flight identity aborts it and drops the index row"
      (let [traces (record-traces! #{:rf.resource/owner-released}
                     #(rf/dispatch-sync [:rf.resource.internal/release-owner-identities
                                         {:owner A :identities (by-id k2)}]))]
        (is (empty? (:active-owners (entry k2))))
        (is (not (rf.resources.work-ledger/live-work? (runtime-db) (:current-work (entry k2))))
            "orphaned → abort-requested")
        (is (nil? (owner-index A)) "A's index row is gone once it holds nothing")
        (is (= [(:current-work (entry k2))] (:aborted (:tags (op traces :rf.resource/owner-released)))))))
    (testing "the whole-owner release is unchanged (regression over the shared core)"
      (rf/dispatch-sync [:rf.resource/release-owner {:owner B}])
      (is (empty? (:active-owners (entry k1))))
      (is (nil? (owner-index B)))
      (is (not (rf.resources.work-ledger/live-work? (runtime-db) (:current-work (entry k1))))))))
