(ns re-frame.resources-route-cljs-test
  "Route ↔ resource integration (rf2-vdyrls, Spec 016 §Route integration —
  EP-0003 slice 7). Cross-host (JVM + CLJS), so the routing/resources seam
  behaves identically server- and client-side.

  Exercises the cross-feature seam with BOTH artefacts loaded:

    1. accepted-key extension — `:resources` is an accepted bare route key
       once resources loads;
    2. on route entry — each `:resources` entry is ensured with owner
       `[:route route-id nav-token]` + cause `[:route-entry route-id
       nav-token]`;
    3. blocking? — a blocking resource keeps the route transition
       `:loading` past the on-match drain, draining to `:idle` only when
       it settles; a non-blocking resource fetches in the background
       without holding the transition;
    4. a blocking FIRST-load failure flips the route transition to
       `:error` + populates `:rf.route/error`;
    5. route leave / supersession releases the prior route's owner token;
    6. `:when` gates a resource out (NOT sentinel nil params);
    7. `:after` orders dependent resources by route-local id;
    8. a params PLANNING failure surfaces on the route slice's `:error`
       (not a silent cache miss);
    9. `:keep-previous?` projects the previous key's data while the new
       key first-loads WITHOUT polluting the new entry / its tags.

  Named `*-cljs-test.cljc` so it is discovered by BOTH the JVM runner
  (`.*-test$`) and the shadow-cljs `:node-test` build (`cljs-test$`). The
  managed-HTTP fx is stubbed (capturing no-op) so ensure's entry write +
  the reply-driven blocking drain are deterministic without a live fetch."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load-bearing side-effecting requires: register the routing + resources
   ;; events / subs and resources' late-bound :routing/* integration hooks.
   [re-frame.resources]
   [re-frame.resources.route :as route]
   [re-frame.resources.state :as state]
   [re-frame.resources.work-ledger :as work-ledger]
   [re-frame.resources.test-support]
   [re-frame.routing :as routing]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.test-support :as core-test-support]
   [re-frame.trace.tooling :as trace-tooling]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

;; ---- fixture --------------------------------------------------------------

(defn- init!
  "Per-test setup (runs after adapter install, registrar live): re-register
  `:rf/default` as the URL-owning app frame, reset the routing counters,
  re-publish the late-bound routing integration, and stub the managed-HTTP +
  push-url fx so ensure + navigation are deterministic without a fetch /
  browser.

  rf2-784223: the resources host-side caches (state / work-ledger / timers /
  revalidate-listeners) are cleared by the shared `make-reset-runtime-
  fixture`'s `:resources/reset-resources!` post-dispose hook, which runs
  BEFORE this `:init-fn` — so no `state/reset-cache!` is repeated here. The
  routing counter reset + late-bound integration re-publication stay (they
  are routing-suite setup, not resource cache hygiene)."
  []
  (rf/reg-frame :rf/default {:url-bound? true
                             :doc "Route-resource suite default app frame."})
  (routing/reset-counters!)
  (route/install-routing-integration!)
  (rf/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil)))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- slice []
  (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :current]))

(defn- entry [scoped-key]
  (get-in (rf/runtime-db-value :rf/default) (state/entry-path scoped-key)))

(defn- entries []
  (get-in (rf/runtime-db-value :rf/default) (state/entries-path)))

(defn- blocking-slot [nav-token]
  (get-in (rf/runtime-db-value :rf/default) (route/blocking-path nav-token)))

(defn- article-spec [overrides]
  (merge {:scope         :rf.scope/global
          :params-schema [:map [:slug :string]]
          :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
         overrides))

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

(defn- settle-success! [scoped-key data]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key scoped-key
                        :work/id      (:current-work e)
                        :generation   (:generation e)
                        :data         data}])))

(defn- settle-failure! [scoped-key error]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/failed
                       {:resource/key scoped-key
                        :work/id      (:current-work e)
                        :generation   (:generation e)
                        :error        error}])))

(defn- record-error-traces!
  "Run `body-fn` with a trace listener installed; return the vector of every
  `:op-type :error` trace event emitted during it (capture order). The
  listener is unregistered in a `finally`. Used by the rf2-u5aj91 +
  rf2-ac71vm / rf2-xeb4l1 planning-error assertions (the structured error
  must reach the trace/error stream, not only route state)."
  [body-fn]
  (let [seen (atom [])
        k    ::route-error-recorder]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= :error (:op-type ev)) (swap! seen conj ev))))
    (try (body-fn) (finally (trace-tooling/unregister-listener! k)))
    @seen))

(defn- errors-of [traces op]
  (filterv #(= op (:operation %)) traces))

;; ===========================================================================
;; 1. Accepted-key extension
;; ===========================================================================

(deftest resources-route-key-is-accepted-when-both-artefacts-load
  (testing ":resources is an accepted bare route key once resources loads"
    (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
    (is (= :route/article
           (rf/reg-route :route/article
                         {:params    [:map [:slug :string]]
                          :resources [{:resource :article/by-slug
                                       :params   (fn [route] {:slug (get-in route [:params :slug])})}]} "/articles/:slug"))
        "reg-route with :resources does not throw — the key is accepted")))

;; ===========================================================================
;; 2. On route entry — owner + cause
;; ===========================================================================

(deftest route-entry-ensures-with-route-owner-and-cause
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource :article/by-slug
                              :params   (fn [route] {:slug (get-in route [:params :slug])})}]} "/articles/:slug")
  (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "intro"}])
  (let [nav-token  (:nav-token (slice))
        scoped-key (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "intro"})
        e          (entry scoped-key)]
    (testing "route entry ensured the resource (a :loading entry exists)"
      (is (some? e) "the route :resources entry was ensured on entry")
      (is (= :loading (:status e)) "first load → :loading"))
    (testing "the resource is owned by the route nav-token owner"
      (is (contains? (:active-owners e) [:route :route/article nav-token])
          "owner is [:route route-id nav-token]"))))

;; ===========================================================================
;; 3. blocking? — keeps transition :loading, drains on settle
;; ===========================================================================

(deftest blocking-resource-holds-route-transition-until-it-settles
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource  :article/by-slug
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? true}]} "/articles/:slug")
  (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "intro"}])
  (let [nav-token  (:nav-token (slice))
        scoped-key (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "intro"})]
    (testing "a blocking resource keeps the route transition :loading"
      (is (= :loading (:transition (slice)))
          "transition stays :loading while the blocking resource is pending")
      (is (contains? (blocking-slot nav-token) scoped-key)
          "the blocking scoped key is tracked under the nav-token"))
    (testing "the route lands :idle only when the blocking resource settles"
      (settle-success! scoped-key {:title "Intro"})
      (is (= :idle (:transition (slice)))
          "blocking resource settled → transition lands :idle")
      (is (empty? (blocking-slot nav-token))
          "the blocking slot drained on settle"))))

(deftest non-blocking-resource-does-not-hold-the-transition
  (rf/reg-resource :comments/list (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource  :comments/list
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? false}]} "/articles/:slug")
  (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "intro"}])
  (testing "a non-blocking resource fetches in the background; the route is :idle"
    (is (= :idle (:transition (slice)))
        "no blocking resource → transition is :idle immediately")
    (let [scoped-key (state/scoped-resource-key :rf.scope/global :comments/list {:slug "intro"})]
      (is (= :loading (:status (entry scoped-key)))
          "the non-blocking resource is still ensured (background fetch)"))))

(deftest blocking-resource-already-fresh-settles-route-immediately
  ;; rf2-hsa0sv: a fresh ensure no longer fetches (fresh-skip / cache-hit).
  ;; A route blocked on an already-FRESH resource MUST settle the nav
  ;; IMMEDIATELY on the cache-hit (no fetch, no reply will ever drain the
  ;; blocking slot) — otherwise the route hangs forever.
  ;; no :stale-after-ms → the entry is always fresh once loaded
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource  :article/by-slug
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? true}]} "/articles/:slug")
  (rf/reg-route :route/home {} "/")
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "intro"})]
    ;; first entry: blocking resource fetches, then settles :loaded (fresh)
    (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "intro"}])
    (settle-success! scoped-key {:title "Intro"})
    (is (= :loaded (:status (entry scoped-key))) "entry is fresh + :loaded")
    ;; leave, then RE-ENTER the same route — the blocking ensure is now a
    ;; fresh-skip cache-hit, which must drain the new nav-token blocking slot.
    (rf/dispatch-sync [:rf.route/navigate :route/home])
    (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "intro"}])
    (let [nav-token-2 (:nav-token (slice))]
      (testing "the re-entry ensure was a fresh-skip cache-hit (no new fetch)"
        (is (= :loaded (:status (entry scoped-key)))
            "the entry stayed :loaded — no refetch on the fresh re-entry")
        (is (nil? (:current-work (entry scoped-key)))
            "no in-flight work record — the cache served the value"))
      (testing "the route settles :idle IMMEDIATELY (the fresh blocking
                resource drained its slot on the cache-hit — no hang)"
        (is (= :idle (:transition (slice)))
            "a route blocked on a fresh resource lands :idle at once")
        (is (empty? (blocking-slot nav-token-2))
            "the new nav-token's blocking slot drained on the cache-hit")))))

;; ===========================================================================
;; 4. blocking FIRST-load failure → route :error
;; ===========================================================================

(deftest blocking-first-load-failure-flips-route-to-error
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource  :article/by-slug
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? true}]} "/articles/:slug")
  (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "intro"}])
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "intro"})]
    (settle-failure! scoped-key {:status 503 :message "upstream down"})
    (testing "a blocking first-load failure flips the route transition to :error"
      (is (= :error (:transition (slice))))
      (is (= :rf.error/resource-route-blocking
             (:rf.error/id (:error (slice))))
          ":rf.route/error carries the structured blocking-failure error"))))

;; ===========================================================================
;; 5. route leave / supersession releases the prior owner
;; ===========================================================================

(deftest route-leave-releases-prior-route-owner
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource :article/by-slug
                              :params   (fn [route] {:slug (get-in route [:params :slug])})}]} "/articles/:slug")
  (rf/reg-route :route/home {} "/")
  (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "intro"}])
  (let [token-1    (:nav-token (slice))
        scoped-key (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "intro"})]
    (is (contains? (:active-owners (entry scoped-key)) [:route :route/article token-1]))
    (rf/dispatch-sync [:rf.route/navigate :route/home])
    (testing "leaving the route releases its nav-token owner from the entry"
      (is (not (contains? (:active-owners (entry scoped-key))
                          [:route :route/article token-1]))
          "the prior route owner was released on leave"))))

;; ---- rf2-v4ygg5: route A→B (same scoped key) does not join abort-requested -
;; A route leave releases the prior nav-token owner, which marks an in-flight
;; attempt :abort-requested while the entry still points at it. An immediate
;; re-entry of the SAME scoped key must NOT join that doomed work — it starts
;; a fresh attempt, and a blocking re-entry must drain on the FRESH reply (not
;; hang waiting on a reply the aborted work will never send).

(defn- work-record-for [scoped-key]
  (work-ledger/get-record (rf/runtime-db-value :rf/default) (:current-work (entry scoped-key))))

(deftest route-resupersede-same-key-does-not-join-abort-requested-non-blocking
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource :article/by-slug
                              :params   (fn [route] {:slug (get-in route [:params :slug])})}]} "/articles/:slug")
  (rf/reg-route :route/home {} "/")
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "intro"})]
    ;; enter route A: the resource is in flight under token-1's route owner
    (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "intro"}])
    (let [token-1 (:nav-token (slice))
          wid1    (:current-work (entry scoped-key))
          gen1    (:generation (entry scoped-key))]
      (is (= :running (:status (work-record-for scoped-key))))
      ;; leave (route B = home): releases token-1's route owner → wid1 becomes
      ;; :abort-requested; the entry still points at wid1.
      (rf/dispatch-sync [:rf.route/navigate :route/home])
      (is (= :abort-requested (:status (work-ledger/get-record (rf/runtime-db-value :rf/default) wid1)))
          "the superseded route's in-flight work is abort-requested")
      ;; re-enter the SAME route + same slug → re-ensure the same scoped key
      (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "intro"}])
      (testing "rf2-v4ygg5 — the re-entry started a FRESH attempt, not a join
                onto the abort-requested work"
        (let [e (entry scoped-key)]
          (is (= (inc gen1) (:generation e)) "fresh generation on re-entry")
          (is (not= wid1 (:current-work e)) "a new work id (not the abort-requested one)")
          (is (= :running (:status (work-record-for scoped-key))) "the new attempt is live")
          (is (contains? (:active-owners e) [:route :route/article (:nav-token (slice))])
              "the re-entry nav-token owns the fresh attempt"))))))

(deftest route-resupersede-same-key-blocking-transition-drains
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource  :article/by-slug
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? true}]} "/articles/:slug")
  (rf/reg-route :route/home {} "/")
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "intro"})]
    (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "intro"}])
    (let [wid1 (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.route/navigate :route/home])
      (is (= :abort-requested (:status (work-ledger/get-record (rf/runtime-db-value :rf/default) wid1))))
      ;; re-enter the blocking route on the SAME key
      (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "intro"}])
      (let [token-2 (:nav-token (slice))]
        (testing "rf2-v4ygg5 — the blocking re-entry holds :loading on a FRESH
                  attempt (it did not join the abort-requested work)"
          (is (= :loading (:transition (slice))) "blocking transition is loading on a fresh attempt")
          (is (not= wid1 (:current-work (entry scoped-key))) "fresh work id, not the aborted one")
          (is (contains? (blocking-slot token-2) scoped-key) "the new token's blocking slot tracks the key"))
        (testing "the FRESH attempt's reply drains the blocking slot → :idle
                  (the route does not hang on the aborted work's missing reply)"
          (settle-success! scoped-key {:title "Intro"})
          (is (= :idle (:transition (slice))) "blocking transition drained on the fresh reply")
          (is (empty? (blocking-slot token-2)) "the blocking slot drained"))))))

;; ===========================================================================
;; 6. :when gates the resource out
;; ===========================================================================

(deftest when-false-gates-the-resource-out
  (rf/reg-resource :comments/list (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource :comments/list
                              :params   (fn [route] {:slug (get-in route [:params :slug])})
                              :when     (fn [_route _ctx] false)}]} "/articles/:slug")
  (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "intro"}])
  (testing ":when false admits no resource (NOT sentinel nil params)"
    (is (empty? (entries)) "the gated-out resource was not ensured")))

;; ===========================================================================
;; 7. :after orders dependent resources by route-local id
;; ===========================================================================

(deftest after-orders-dependent-resources-by-local-id
  (let [order (atom [])]
    (rf/reg-resource :article/by-slug
                     (article-spec {})
                     (fn [_p _]
                       (swap! order conj :article)
                       {:request {:method :get :url "/a"}}))
    (rf/reg-resource :comments/list
                     (article-spec {})
                     (fn [_p _]
                       (swap! order conj :comments)
                       {:request {:method :get :url "/c"}}))
    (rf/reg-route :route/article
                  {:params    [:map [:slug :string]]
                   :resources [{:resource :comments/list
                                :id       :comments
                                :params   (fn [route] {:slug (get-in route [:params :slug])})
                                :after    #{:article}}
                               {:resource :article/by-slug
                                :id       :article
                                :params   (fn [route] {:slug (get-in route [:params :slug])})}]} "/articles/:slug")
    (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "intro"}])
    (testing ":after #{local-id} orders the dependent resource AFTER its dep"
      (is (= [:article :comments] @order)
          "the :article dep ensures before the :comments dependent"))))

;; ===========================================================================
;; 8. params PLANNING failure surfaces on the route slice
;; ===========================================================================

(deftest params-planning-failure-surfaces-on-route-slice
  ;; a :rf.scope/from-caller scope with no route resolver is a fail-closed
  ;; planning error at route entry (no silent cache miss).
  (rf/reg-resource :secret/doc (article-spec {:scope :rf.scope/from-caller}) article-spec-request)
  (rf/reg-route :route/secret
                {:params    [:map [:slug :string]]
                 :resources [{:resource :secret/doc
                              :params   (fn [route] {:slug (get-in route [:params :slug])})}]} "/secret/:slug")
  (rf/dispatch-sync [:rf.route/navigate :route/secret {:slug "x"}])
  (testing "a fail-closed scope/params resolution is a route PLANNING error"
    (is (= :rf.error/resource-route-plan
           (:rf.error/id (:error (slice))))
        ":rf.route/error carries the planning error, not a silent cache miss")
    (is (empty? (entries)) "no entry was written for the unplannable resource")))

;; ===========================================================================
;; 9. :keep-previous? projects prior-key data WITHOUT polluting the new key
;; ===========================================================================

(deftest keep-previous-projects-prior-key-without-polluting-new-key
  (rf/reg-resource :articles/list (article-spec {:params-schema [:map [:page :int]]
                                                 :tags (fn [{:keys [page]} _] #{[:list page]})})
                   article-spec-request)
  (rf/reg-route :route/list
                {:query     [:map [:page :int]]
                 :resources [{:resource       :articles/list
                              :params         (fn [route] {:page (get-in route [:query :page])})
                              :keep-previous? true}]} "/list")
  (rf/dispatch-sync [:rf.route/navigate :route/list {} {:query {:page 1}}])
  (let [k1 (state/scoped-resource-key :rf.scope/global :articles/list {:page 1})]
    (settle-success! k1 [{:id 1 :title "Old page"}]))
  (rf/dispatch-sync [:rf.route/navigate :route/list {} {:query {:page 2}}])
  (let [k1   (state/scoped-resource-key :rf.scope/global :articles/list {:page 1})
        k2   (state/scoped-resource-key :rf.scope/global :articles/list {:page 2})
        view @(rf/subscribe [:rf.resource/state {:resource :articles/list
                                                 :scope   :rf.scope/global
                                                 :params  {:page 2}}])]
    (testing "the new key shows the previous key's data while it loads"
      (is (true? (:previous? view)) "the state view flags :previous?")
      (is (= k1 (:previous-key view)) "the previous-key points at the prior page")
      (is (= [{:id 1 :title "Old page"}] (:previous-data view))
          "previous-data is projected from the prior key")
      (is (nil? (:data view)) "the new key has no data of its own yet"))
    (testing "previous data does NOT pollute the new key's cache entry or tags"
      (is (nil? (:data (entry k2))) "the new entry's :data is NOT the previous data")
      (is (empty? (:tags (entry k2))) "the new entry borrows none of the prior key's tags"))))

;; ===========================================================================
;; 10. rf2-u5aj91 — a blocking first-load failure ALSO emits an error trace
;; ===========================================================================

(deftest blocking-first-load-failure-emits-error-trace
  ;; The route slice already carries the structured :error (section 4); this
  ;; proves the SAME failure is ALSO published on the trace/error stream as
  ;; `:rf.error/resource-route-blocking` with ResourceRouteBlockingTags shape.
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource  :article/by-slug
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? true}]} "/articles/:slug")
  (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "intro"}])
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "intro"})
        nav-token  (:nav-token (slice))
        traces     (record-error-traces!
                     #(settle-failure! scoped-key {:status 503 :message "upstream down"}))
        evs        (errors-of traces :rf.error/resource-route-blocking)]
    (testing "exactly one :rf.error/resource-route-blocking error trace is emitted"
      (is (= 1 (count evs)) "one blocking-failure error trace on the stream"))
    (testing "the error-trace tags conform to ResourceRouteBlockingTags"
      (let [ev   (first evs)
            tags (:tags ev)]
        (is (= :error (:op-type ev)) "it rides the error channel")
        (is (= :rf.error/resource-route-blocking (:category tags))
            ":category is stamped from the operation")
        (is (= :article/by-slug (:resource-id tags)) ":resource-id tag present")
        (is (= nav-token (:nav-token tags)) ":nav-token tag present")
        (is (= {:status 503 :message "upstream down"} (:error tags))
            ":error carries the resource's first-load failure envelope")
        (is (string? (:reason tags)) ":reason present")))
    (testing "route state STILL carries the structured error (both surfaces)"
      (is (= :error (:transition (slice))))
      (is (= :rf.error/resource-route-blocking (:rf.error/id (:error (slice))))))))

;; ===========================================================================
;; 11. rf2-l2gofj — superseded route-resource blocking slots are cleared
;; ===========================================================================

(deftest superseded-blocking-slot-is-cleared-on-route-leave
  ;; A BLOCKING route resource that NEVER settles (no reply — e.g. aborted /
  ;; orphaned in-flight on supersession) used to leave its old-nav-token
  ;; blocking entry forever, because reply-driven drain only fires on a
  ;; settle that still names the old owner. Leaving the route releases the
  ;; prior owner, which MUST now deterministically clear the stale slot.
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource  :article/by-slug
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? true}]} "/articles/:slug")
  (rf/reg-route :route/home {} "/")
  (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "intro"}])
  (let [token-1    (:nav-token (slice))
        scoped-key (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "intro"})]
    (testing "the blocking slot is populated on entry (resource never settles)"
      (is (contains? (blocking-slot token-1) scoped-key)))
    ;; leave WITHOUT the blocking resource ever settling
    (rf/dispatch-sync [:rf.route/navigate :route/home])
    (testing "the superseded nav-token's blocking slot is fully cleared on leave"
      (is (empty? (blocking-slot token-1))
          "old-token blocking state did not accumulate / leak"))))

(deftest superseded-blocking-slot-does-not-block-future-navigation
  ;; Prove the stale slot cannot bleed into the LIVE route-blocking? predicate
  ;; for a later navigation — old-token state must not gate new transitions.
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource  :article/by-slug
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? true}]} "/articles/:slug")
  ;; a plain (no-resources) route — its entry has nothing blocking
  (rf/reg-route :route/home {} "/")
  (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "a"}])
  (let [token-1 (:nav-token (slice))]
    ;; supersede WITHOUT settling — then land on the plain route
    (rf/dispatch-sync [:rf.route/navigate :route/home])
    (let [token-2 (:nav-token (slice))]
      (testing "the plain route lands :idle — the stale token's slot does not block it"
        (is (not= token-1 token-2) "a new nav-token was minted")
        (is (= :idle (:transition (slice)))
            "the plain route is :idle; superseded blocking state is gone")
        (is (empty? (blocking-slot token-1)) "stale slot cleared")
        (is (false? (route/route-blocking? (rf/runtime-db-value :rf/default)))
            "route-blocking? is false for the live token")))))

;; ===========================================================================
;; 12. rf2-ac71vm — fail-closed ctx + nil planning inputs
;; ===========================================================================

(deftest scope-resolved-from-ctx-uses-the-ctx-seam
  ;; The ctx seam is REAL: a :scope resolver reads (:current-session-scope ctx)
  ;; and the resolved scope is used as the cache scope (not a global fallback).
  (rf/reg-resource :secret/doc (article-spec {:scope :rf.scope/from-caller}) article-spec-request)
  (let [plan (route/route-resource-plan
               {:id :route/secret :params {:slug "x"}
                :resources [{:resource :secret/doc
                             :params   (fn [route] {:slug (get-in route [:params :slug])})
                             :scope    (fn [_route ctx] (:current-session-scope ctx))}]}
               {:current-session-scope {:tenant "acme" :user 7}}
               {:nav-token 1 :prev-id nil :prev-nav-token nil})]
    (testing "the ctx-resolved session scope is threaded into the ensure"
      (is (nil? (:plan-error plan)) "no planning error — the ctx resolved a scope")
      (let [ensure (->> (:fx plan)
                        (some (fn [[fx-id ev]] (when (= :dispatch fx-id) ev))))]
        (is (= :rf.resource/ensure (first ensure)))
        (is (= {:tenant "acme" :user 7} (:scope (second ensure)))
            "the cache scope came from ctx, NOT a global / spec fallback")))))

(deftest nil-ctx-fails-closed
  ;; A nil ctx (a routing↔resources seam bug) must throw — not silently
  ;; proceed with an empty ctx that a session-scope resolver would read as nil.
  (rf/reg-resource :secret/doc (article-spec {:scope :rf.scope/from-caller}) article-spec-request)
  (testing "route-resource-plan throws on a nil ctx"
    (is (thrown? #?(:clj Throwable :cljs :default)
                 (route/route-resource-plan
                   {:id :route/secret :resources []}
                   nil
                   {:nav-token 1})))))

(deftest missing-nav-token-fails-closed
  ;; The nav-token IS the route owner identity; planning without one would
  ;; mint an unreleasable owner — fail closed.
  (testing "route-resource-plan throws on a missing nav-token"
    (is (thrown? #?(:clj Throwable :cljs :default)
                 (route/route-resource-plan
                   {:id :route/x :resources []}
                   {}
                   {:nav-token nil})))))

(deftest nil-params-resolver-is-a-planning-error
  ;; A PRESENT :params resolver returning nil is NOT a silent empty-param read
  ;; — it is a fail-closed planning error (conditional resources use :when).
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource :article/by-slug
                              ;; resolver INTENDS params but returns nil
                              :params   (fn [_route] nil)}]} "/articles/:slug")
  (let [traces (record-error-traces!
                 #(rf/dispatch-sync [:rf.route/navigate :route/article {:slug "x"}]))]
    (testing "the nil-params resolver surfaces a route planning error"
      (is (= :rf.error/resource-route-plan (:rf.error/id (:error (slice))))
          "route slice carries the planning error, not a silent empty-param read")
      (is (= :fix-params (:recovery (:error (slice)))) "carries :fix-params recovery")
      (is (seq (errors-of traces :rf.error/resource-route-plan))
          "the planning error is ALSO on the trace/error stream")
      (is (empty? (entries)) "no entry was ensured for the unplannable resource"))))

(deftest nil-scope-resolver-is-a-planning-error
  ;; A PRESENT :scope resolver returning nil must NOT silently fall through to
  ;; the spec policy / a global read — the scope is the leak boundary.
  (rf/reg-resource :secret/doc (article-spec {:scope :rf.scope/from-caller}) article-spec-request)
  (rf/reg-route :route/secret
                {:params    [:map [:slug :string]]
                 :resources [{:resource :secret/doc
                              :params   (fn [route] {:slug (get-in route [:params :slug])})
                              :scope    (fn [_route _ctx] nil)}]} "/secret/:slug")  ;; resolver returns nil
  (rf/dispatch-sync [:rf.route/navigate :route/secret {:slug "x"}])
  (testing "a nil :scope resolver result is a fail-closed planning error"
    (is (= :rf.error/resource-route-plan (:rf.error/id (:error (slice))))
        "no silent fallback to spec scope / global read")
    (is (= :fix-scope (:recovery (:error (slice)))) "carries :fix-scope recovery")
    (is (empty? (entries)) "no entry was ensured")))

(deftest throwing-when-predicate-is-a-planning-error
  ;; A :when predicate that THROWS must be a planning error caught at the
  ;; fail-closed boundary, not an escape that crashes the whole commit.
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource :article/by-slug
                              :params   (fn [route] {:slug (get-in route [:params :slug])})
                              :when     (fn [_route _ctx] (throw (ex-info "boom" {})))}]} "/articles/:slug")
  (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "x"}])
  (testing "a throwing :when is a route planning error"
    (is (= :rf.error/resource-route-plan (:rf.error/id (:error (slice)))))
    (is (= :fix-when (:recovery (:error (slice)))) "carries :fix-when recovery")
    (is (empty? (entries)) "no entry was ensured")))

;; ===========================================================================
;; 13. rf2-xeb4l1 — :after is dispatch-order, fail-closed on missing/cyclic
;; ===========================================================================

(deftest after-missing-target-is-a-planning-error
  ;; :after naming an id no entry declares is a typo'd dependency — a
  ;; planning error, NOT silent declaration-order fallthrough.
  (rf/reg-resource :comments/list (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource :comments/list
                              :id       :comments
                              :params   (fn [route] {:slug (get-in route [:params :slug])})
                              :after    #{:nope}}]} "/articles/:slug")  ;; :nope is declared by no entry
  (let [traces (record-error-traces!
                 #(rf/dispatch-sync [:rf.route/navigate :route/article {:slug "x"}]))]
    (testing "a missing :after target surfaces a planning error"
      (is (= :rf.error/resource-route-plan (:rf.error/id (:error (slice)))))
      (is (= :fix-after (:recovery (:error (slice)))) "carries :fix-after recovery")
      (is (seq (errors-of traces :rf.error/resource-route-plan))
          "also on the trace/error stream")
      (is (empty? (entries)) "no entry ensured — the plan failed closed"))))

(deftest after-cycle-is-a-planning-error
  ;; A cyclic :after dependency degrades NEITHER silently nor into an infinite
  ;; loop — it is a fail-closed planning error.
  (rf/reg-resource :a/res (article-spec {}) article-spec-request)
  (rf/reg-resource :b/res (article-spec {}) article-spec-request)
  (rf/reg-route :route/cyc
                {:resources [{:resource :a/res :id :a :params (fn [_] {:slug "a"}) :after #{:b}}
                             {:resource :b/res :id :b :params (fn [_] {:slug "b"}) :after #{:a}}]} "/cyc")
  (rf/dispatch-sync [:rf.route/navigate :route/cyc])
  (testing "a cyclic :after is a planning error (no hang, no silent fallthrough)"
    (is (= :rf.error/resource-route-plan (:rf.error/id (:error (slice)))))
    (is (= :fix-after (:recovery (:error (slice)))))
    (is (empty? (entries)) "no entry ensured")))

(deftest after-orders-multiple-deps-by-local-id
  ;; The kept dispatch-order semantics: a dependent's ensure is dispatched
  ;; AFTER every id it names (a 3-node chain, declared out of order).
  (let [order (atom [])]
    (rf/reg-resource :a/res (article-spec {}) (fn [_ _] (swap! order conj :a)
                                               {:request {:method :get :url "/a"}}))
    (rf/reg-resource :b/res (article-spec {}) (fn [_ _] (swap! order conj :b)
                                               {:request {:method :get :url "/b"}}))
    (rf/reg-resource :c/res (article-spec {}) (fn [_ _] (swap! order conj :c)
                                               {:request {:method :get :url "/c"}}))
    (rf/reg-route :route/chain
                  {;; declared c, a, b — :after must reorder to a → b → c
                   :resources [{:resource :c/res :id :c :params (fn [_] {:slug "c"}) :after #{:b}}
                               {:resource :a/res :id :a :params (fn [_] {:slug "a"})}
                               {:resource :b/res :id :b :params (fn [_] {:slug "b"}) :after #{:a}}]} "/chain")
    (rf/dispatch-sync [:rf.route/navigate :route/chain])
    (testing ":after topologically orders the ensure dispatches by local id"
      (is (nil? (:error (slice))) "no planning error — all :after targets are valid")
      (is (= [:a :b :c] @order) "a (no dep) → b (after a) → c (after b)"))))
