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
   [re-frame.fx :as fx]
   [re-frame.error :as error]
   [re-frame.interop :as interop]
   ;; load-bearing side-effecting requires: register the routing + resources
   ;; events / subs and resources' late-bound :routing/* integration hooks.
   [re-frame.resources]
   [re-frame.resources.route :as route]
   [re-frame.resources.ssr :as res-ssr]
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
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "Route-resource suite default app frame."})
  (routing/reset-counters!)
  (route/install-routing-integration!)
  (fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil)))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- slice []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current]))

(defn- entry [scoped-key]
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) (state/entry-path scoped-key)))

(defn- entries []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) (state/entries-path)))

(defn- blocking-slot
  "The live blocking slot for `nav-token`, projected to the SET of its scoped
  keys. The slot itself is the byte-keyed `{<key-id> <scoped-key>}` carrier
  (rf2-btdl1); these assertions ask a membership question that the projection
  answers, and the byte-exactness of the carrier is pinned directly off
  `route/blocking-path` by `r2-a-plan-holding-both-byte-distinct-twins-*`."
  [nav-token]
  (set (vals (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                     (route/blocking-path nav-token)))))

(defn- blocking-map
  "The byte-keyed blocking / plan-identity carrier `{<key-id> <scoped-key>}`
  over `ks` — the shape both routing slots hold (rf2-btdl1)."
  [& ks]
  (into {} (map (juxt state/key-id identity)) ks))

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

(defn- record-op-traces!
  "Run `body-fn` with a trace listener installed; return the vector of every
  trace event whose `:operation` is `op` (capture order). The sibling of
  `record-error-traces!` for an ordinary `:rf.event` row — used by the
  rf2-dlkou `:rf.resource/route-plan` plan-diff assertions."
  [op body-fn]
  (let [seen (atom [])
        k    ::route-op-recorder]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= op (:operation ev)) (swap! seen conj ev))))
    (try (body-fn) (finally (trace-tooling/unregister-listener! k)))
    @seen))

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
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
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
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
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
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
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
    (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
    (settle-success! scoped-key {:title "Intro"})
    (is (= :loaded (:status (entry scoped-key))) "entry is fresh + :loaded")
    ;; leave, then RE-ENTER the same route — the blocking ensure is now a
    ;; fresh-skip cache-hit, which must drain the new nav-token blocking slot.
    (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
    (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
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
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
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
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
  (let [token-1    (:nav-token (slice))
        scoped-key (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "intro"})]
    (is (contains? (:active-owners (entry scoped-key)) [:route :route/article token-1]))
    (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
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
  (work-ledger/get-record (:rf.db/runtime (rf/frame-state-value :rf/default)) (:current-work (entry scoped-key))))

(deftest route-resupersede-same-key-does-not-join-abort-requested-non-blocking
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource :article/by-slug
                              :params   (fn [route] {:slug (get-in route [:params :slug])})}]} "/articles/:slug")
  (rf/reg-route :route/home {} "/")
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "intro"})]
    ;; enter route A: the resource is in flight under token-1's route owner
    (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
    (let [token-1 (:nav-token (slice))
          wid1    (:current-work (entry scoped-key))
          gen1    (:generation (entry scoped-key))]
      (is (= :running (:status (work-record-for scoped-key))))
      ;; leave (route B = home): releases token-1's route owner → wid1 becomes
      ;; :abort-requested; the entry still points at wid1.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
      (is (= :abort-requested (:status (work-ledger/get-record (:rf.db/runtime (rf/frame-state-value :rf/default)) wid1)))
          "the superseded route's in-flight work is abort-requested")
      ;; re-enter the SAME route + same slug → re-ensure the same scoped key
      (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
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
    (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
    (let [wid1 (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
      (is (= :abort-requested (:status (work-ledger/get-record (:rf.db/runtime (rf/frame-state-value :rf/default)) wid1))))
      ;; re-enter the blocking route on the SAME key
      (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
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
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
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
    (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
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
  (rf/dispatch-sync [:rf.route/navigate {:to :route/secret :params {:slug "x"}}])
  (testing "a fail-closed scope/params resolution is a route PLANNING error"
    (is (= :rf.error/resource-route-plan
           (:rf.error/id (:error (slice))))
        ":rf.route/error carries the planning error, not a silent cache miss")
    ;; rf2-9g3qzi: the route-slice error map (built by plan-error) carries NO
    ;; :operation slot duplicating :rf.error/id — that dead-weight slot is gone.
    (is (not (contains? (:error (slice)) :operation))
        "the slice error map has no :operation slot shadowing :rf.error/id")
    (is (string? (:reason (:error (slice)))) "a human :reason sentence is present")
    (is (some? (:recovery (:error (slice)))) "a :recovery disposition is present")
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
  (rf/dispatch-sync [:rf.route/navigate {:to :route/list :query {:page 1}}])
  (let [k1 (state/scoped-resource-key :rf.scope/global :articles/list {:page 1})]
    (settle-success! k1 [{:id 1 :title "Old page"}]))
  (rf/dispatch-sync [:rf.route/navigate {:to :route/list :query {:page 2}}])
  (let [k1   (state/scoped-resource-key :rf.scope/global :articles/list {:page 1})
        k2   (state/scoped-resource-key :rf.scope/global :articles/list {:page 2})
        view @(rf/subscribe [:rf/resource {:resource :articles/list
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
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
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
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
  (let [token-1    (:nav-token (slice))
        scoped-key (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "intro"})]
    (testing "the blocking slot is populated on entry (resource never settles)"
      (is (contains? (blocking-slot token-1) scoped-key)))
    ;; leave WITHOUT the blocking resource ever settling
    (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
    (testing "the superseded nav-token's blocking slot is fully cleared on leave"
      (is (empty? (blocking-slot token-1))
          "old-token blocking state did not accumulate / leak"))))

(deftest superseded-blocking-slot-does-not-block-future-navigation
  ;; Prove the stale slot cannot bleed into the LIVE readiness projection for
  ;; a later navigation — old-token state must not gate new transitions.
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource  :article/by-slug
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? true}]} "/articles/:slug")
  ;; a plain (no-resources) route — its entry has nothing blocking
  (rf/reg-route :route/home {} "/")
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "a"}}])
  (let [token-1 (:nav-token (slice))]
    ;; supersede WITHOUT settling — then land on the plain route
    (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
    (let [token-2 (:nav-token (slice))]
      (testing "the plain route lands :idle — the stale token's slot does not block it"
        (is (not= token-1 token-2) "a new nav-token was minted")
        (is (= :idle (:transition (slice)))
            "the plain route is :idle; superseded blocking state is gone")
        (is (empty? (blocking-slot token-1)) "stale slot cleared")
        (is (empty? (blocking-slot token-2))
            "the live token has no blocking requirements of its own")
        (is (identical? (:rf.db/runtime (rf/frame-state-value :rf/default))
                        (route/reconcile-readiness
                          (:rf.db/runtime (rf/frame-state-value :rf/default))))
            "re-projecting is a structural no-op for the live token")))))

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
                   {:nav-token 1}))))
  ;; rf2-9g3qzi: the thrown planning-error routes through error/thrown-ex-info,
  ;; so its message LEADS with a human sentence and TRAILS with the
  ;; [:rf.error/resource-route-plan] greppability token, and the ex-data carries
  ;; the canonical :where / :recovery slots (the conformant shape its sibling
  ;; registry/registration-error already follows).
  (testing "the thrown planning-error carries the canonical thrown-error shape"
    (let [thrown (try (route/route-resource-plan
                        {:id :route/secret :resources []} nil {:nav-token 1})
                      nil
                      (catch #?(:clj clojure.lang.ExceptionInfo
                                :cljs cljs.core/ExceptionInfo) e e))
          data   (ex-data thrown)
          msg    (ex-message thrown)]
      (is (some? thrown))
      (is (= :rf.error/resource-route-plan (:rf.error/id data)))
      (is (error/message-has-id-token? msg)
          "message carries the trailing [:rf.error/resource-route-plan] token (rule 4)")
      (is (not (error/keyword-only-message? msg))
          "message is a human sentence, not a bare keyword (rule 1)")
      (is (= 'rf/route-resource-plan (:where data))
          ":where names the planning boundary helper")
      (is (= :fix-route-integration (:recovery data))
          ":recovery carries the site-specific disposition (overriding the default)")
      (is (not (contains? data :operation))
          "no dead :operation slot duplicating :rf.error/id"))))

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
                 #(rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "x"}}]))]
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
  (rf/dispatch-sync [:rf.route/navigate {:to :route/secret :params {:slug "x"}}])
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
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "x"}}])
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
                 #(rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "x"}}]))]
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
  (rf/dispatch-sync [:rf.route/navigate {:to :route/cyc}])
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
    (rf/dispatch-sync [:rf.route/navigate {:to :route/chain}])
    (testing ":after topologically orders the ensure dispatches by local id"
      (is (nil? (:error (slice))) "no planning error — all :after targets are valid")
      (is (= [:a :b :c] @order) "a (no dep) → b (after a) → c (after b)"))))

;; ===========================================================================
;; 14. EP-0037 R2 — effective parent-chain resource plans
;;     Composition parent to leaf, grouped identity dedupe + redundant-child
;;     advisory, collapse-cycle fail-loud, the plan diff (kept adopted / added
;;     ensured / removed released, attach-before-release), the partial-
;;     revalidation law, and fail-loud branch resolution.
;;     Spec 016 §Effective parent-chain resource plans.
;; ===========================================================================

(defn- plan-dispatches
  "The event vectors dispatched by a plan's fx, in fx order."
  [plan]
  (into [] (keep (fn [[fx-id ev]] (when (= :dispatch fx-id) ev))) (:fx plan)))

(defn- of-event [dispatches event-id]
  (filterv (fn [ev] (= event-id (first ev))) dispatches))

(deftest r2-branch-composes-parent-to-leaf
  ;; A declared :parent composes the ancestor :resources with the leaf, parent-
  ;; most first — the child never restates the shell read.
  (rf/reg-resource :shell/viewer (article-spec {}) article-spec-request)
  (rf/reg-resource :leaf/settings (article-spec {}) article-spec-request)
  (let [branch [{:route-id   :route/account
                 :route-meta {:resources [{:resource :shell/viewer :params (fn [_] {:slug "v"}) :blocking? true}]}}
                {:route-id   :route/account.settings
                 :route-meta {:resources [{:resource :leaf/settings :params (fn [_] {:slug "s"}) :blocking? true}]}}]
        plan (route/route-resource-plan
               {:id :route/account.settings :params {} :query {}}
               {}
               {:nav-token 1 :branch branch})
        ensures (of-event (plan-dispatches plan) :rf.resource/ensure)]
    (testing "both parent + leaf resources are ensured, parent-most first"
      (is (nil? (:plan-error plan)))
      (is (= [:shell/viewer :leaf/settings] (mapv #(:resource (second %)) ensures)))
      (is (= 2 (count (:blocking plan))) "both blocking requirements enter the blocking set"))))

(deftest r2-identity-dedupe-and-redundant-child-advisory
  ;; Parent + child declare the SAME identity (same resource + scope + params):
  ;; dedupe to ONE ensure fixed at the earliest (parent) position; blocking? is
  ;; OR across contributors; the redundant child copy surfaces as an advisory.
  (rf/reg-resource :shell/banner (article-spec {}) article-spec-request)
  (rf/reg-resource :leaf/list (article-spec {}) article-spec-request)
  (let [banner {:resource :shell/banner :params (fn [_] {:slug "u"})}
        branch [{:route-id   :route/profile
                 :route-meta {:resources [(assoc banner :blocking? true)]}}
                {:route-id   :route/profile.favorites
                 :route-meta {:resources [(assoc banner :blocking? false)     ;; redundant copy
                                          {:resource :leaf/list :params (fn [_] {:slug "f"})}]}}]
        plan (route/route-resource-plan
               {:id :route/profile.favorites :params {} :query {}}
               {}
               {:nav-token 1 :branch branch})
        ensures (of-event (plan-dispatches plan) :rf.resource/ensure)
        banner-key (state/scoped-resource-key* :rf.scope/global :shell/banner {:slug "u"})]
    (testing "the duplicated banner dedupes to one ensure at the parent position"
      (is (nil? (:plan-error plan)))
      (is (= [:shell/banner :leaf/list] (mapv #(:resource (second %)) ensures))
          "banner deduped + fixed earliest; leaf list follows"))
    (testing "blocking? is OR across contributors — the parent marked it blocking"
      (is (contains? (:blocking plan) (state/key-id banner-key))))
    (testing "the redundant child declaration surfaces as an advisory"
      (is (= 1 (count (:advisories plan))))
      (let [adv (first (:advisories plan))]
        (is (= :route/profile (get-in adv [:ancestor :route-id])))
        (is (= :route/profile.favorites (get-in adv [:child :route-id])))
        (is (= :shell/banner (:resource adv)))))))

(deftest r2-collapse-cycle-fails-the-whole-plan
  ;; A and C resolve to one identity; B :after A; C :after B. Collapse produces
  ;; identity(A,C) -> B and B -> identity(A,C): a cycle. The plan fails and
  ;; dispatches no ensures (empty next ownership).
  (rf/reg-resource :cyc/shared (article-spec {}) article-spec-request)
  (rf/reg-resource :cyc/mid (article-spec {}) article-spec-request)
  (let [branch [{:route-id   :route/cyc
                 :route-meta {:resources [{:resource :cyc/shared :id :a :params (fn [_] {:slug "k"})}
                                          {:resource :cyc/mid    :id :b :params (fn [_] {:slug "m"}) :after #{:a}}
                                          {:resource :cyc/shared :id :c :params (fn [_] {:slug "k"}) :after #{:b}}]}}]
        plan (route/route-resource-plan {:id :route/cyc :params {} :query {}} {}
                                        {:nav-token 1 :branch branch})]
    (testing "the collapse-created cycle is a planning error"
      (is (some? (:plan-error plan)))
      (is (= :rf.error/resource-route-plan (:rf.error/id (:plan-error plan)))))
    (testing "no ensures are dispatched on the failed plan"
      (is (empty? (of-event (plan-dispatches plan) :rf.resource/ensure))))))

(deftest r2-plan-diff-kept-adopted-added-ensured-release-last
  ;; Sibling-leaf navigation: the parent identity is KEPT (adopt-owner, no
  ;; re-ensure — the partial-revalidation law); the new leaf is ADDED (ensure);
  ;; the prior owner release is dispatched LAST (attach-before-release).
  ;;
  ;; rf2-kqxe6.6 — "kept" is prior-plan membership AND a genuinely reusable
  ;; entry, so this threads the AT-COMMIT `:runtime-db` carrying the loaded
  ;; parent identity (routing always threads it; membership alone would adopt
  ;; into a void). `r2-retained-identity-is-adopted-only-when-genuinely-reusable`
  ;; below pins the unusable cases.
  (rf/reg-resource :sh/v (article-spec {}) article-spec-request)
  (rf/reg-resource :lf/a (article-spec {}) article-spec-request)
  (rf/reg-resource :lf/b (article-spec {}) article-spec-request)
  (let [parent-meta {:resources [{:resource :sh/v :params (fn [_] {:slug "v"}) :blocking? true}]}
        branch1 [{:route-id :route/p   :route-meta parent-meta}
                 {:route-id :route/p.a :route-meta {:resources [{:resource :lf/a :params (fn [_] {:slug "a"})}]}}]
        plan1 (route/route-resource-plan {:id :route/p.a :params {} :query {}} {}
                                         {:nav-token 1 :branch branch1})
        ids1  (:identities plan1)
        shared-key (state/scoped-resource-key* :rf.scope/global :sh/v {:slug "v"})
        ;; plan1's shared identity has since LOADED — the reusable kept case.
        rdb   {:rf.runtime/resources
               {:entries {(state/key-id shared-key)
                          {:resource/id :sh/v :resource/key shared-key
                           :status :loaded :data {:n 1} :attempt 1}}}}
        branch2 [{:route-id :route/p   :route-meta parent-meta}
                 {:route-id :route/p.b :route-meta {:resources [{:resource :lf/b :params (fn [_] {:slug "b"})}]}}]
        plan2 (route/route-resource-plan {:id :route/p.b :params {} :query {}} {}
                                         {:nav-token 2 :prev-id :route/p.a :prev-nav-token 1
                                          :prev-identities ids1 :branch branch2
                                          :runtime-db rdb})
        ds    (plan-dispatches plan2)
        adopts  (of-event ds :rf.resource/adopt-owner)
        ensures (of-event ds :rf.resource/ensure)]
    (testing "the kept parent identity is ADOPTED, not re-ensured (partial revalidation)"
      (is (nil? (:plan-error plan2)))
      (is (= 1 (count adopts)))
      (is (= :sh/v (:resource (second (first adopts))))))
    (testing "only the added leaf is ensured"
      (is (= 1 (count ensures)))
      (is (= :lf/b (:resource (second (first ensures))))))
    (testing "the prior plan owner release is dispatched LAST (attach-before-release)"
      (is (= :rf.resource/release-owner (first (last ds))))
      (is (= [:route :route/p.a 1] (:owner (second (last ds)))) "releases the superseded owner"))))

(deftest r2-plan-diff-trace-carries-the-identity-partition
  ;; rf2-dlkou (the rf2-9sluz ruling) — the SAME sibling-leaf navigation as the
  ;; test above, read off the `:rf.resource/route-plan` TRACE rather than the
  ;; returned fx: one row must answer "which identity was ensured / kept /
  ;; removed on this navigation" without diffing two consecutive rows. The
  ;; counts stay as the compact headline and keep their existing values.
  (rf/reg-resource :sh/v (article-spec {}) article-spec-request)
  (rf/reg-resource :lf/a (article-spec {}) article-spec-request)
  (rf/reg-resource :lf/b (article-spec {}) article-spec-request)
  (let [parent-meta {:resources [{:resource :sh/v :params (fn [_] {:slug "v"}) :blocking? true}]}
        shared-key  (state/scoped-resource-key* :rf.scope/global :sh/v {:slug "v"})
        a-key       (state/scoped-resource-key* :rf.scope/global :lf/a {:slug "a"})
        b-key       (state/scoped-resource-key* :rf.scope/global :lf/b {:slug "b"})
        branch1 [{:route-id :route/p   :route-meta parent-meta}
                 {:route-id :route/p.a :route-meta {:resources [{:resource :lf/a :params (fn [_] {:slug "a"})}]}}]
        branch2 [{:route-id :route/p   :route-meta parent-meta}
                 {:route-id :route/p.b :route-meta {:resources [{:resource :lf/b :params (fn [_] {:slug "b"})}]}}]
        plan1 (route/route-resource-plan {:id :route/p.a :params {} :query {}} {}
                                         {:nav-token 1 :branch branch1})
        ;; plan1's shared identity has since LOADED — the reusable kept case.
        rdb   {:rf.runtime/resources
               {:entries {(state/key-id shared-key)
                          {:resource/id :sh/v :resource/key shared-key
                           :status :loaded :data {:n 1} :attempt 1}}}}
        traces (record-op-traces!
                 :rf.resource/route-plan
                 (fn [] (route/route-resource-plan
                          {:id :route/p.b :params {} :query {}} {}
                          {:nav-token 2 :prev-id :route/p.a :prev-nav-token 1
                           :prev-identities (:identities plan1) :branch branch2
                           :runtime-db rdb})))]
    ;; rf2-o5dbf — `trace/emit!` sits behind `interop/debug-enabled?`, so the
    ;; row exists only in the dev posture. Under `-Dre-frame.debug=false` there
    ;; is no row to read and the plan-diff assertions are vacuous by design.
    (when interop/debug-enabled?
      (let [tags (:tags (first traces))]
        (is (some? tags) "the activation emits one :rf.resource/route-plan row")
        (testing "the counts are unchanged — the compact headline still reads
                  1 ensured / 1 kept / 1 removed"
          (is (= 1 (:ensured tags)))
          (is (= 1 (:kept tags)))
          (is (= 1 (:removed tags))))
        (testing "and the row names WHICH identity took each path"
          (is (= [b-key] (:ensured-identities tags))
              "the added leaf took the real ensure path")
          (is (= [shared-key] (:kept-identities tags))
              "the shared parent was adopted without a fetch")
          (is (= [a-key] (:removed-identities tags))
              "the departed leaf is the prior-plan identity this plan drops"))
        (testing "each vector agrees with the count beside it"
          (is (= (:ensured tags) (count (:ensured-identities tags))))
          (is (= (:kept tags) (count (:kept-identities tags))))
          (is (= (:removed tags) (count (:removed-identities tags)))))
        (testing ":identities carries the planner's GROUPED PLAN ORDER —
                  parent-most first, one entry per collapsed identity"
          (is (= [shared-key b-key] (:identities tags))))))))

(deftest r2-removed-identities-is-membership-not-caller-order
  ;; rf2-dlkou (merged-PR audit) — the test above removes exactly ONE identity,
  ;; and a one-element vector is ordered under every implementation, so it
  ;; cannot tell one apart from another. THREE removals can.
  ;;
  ;; `:removed-identities` answers WHICH prior identities the plan dropped. It
  ;; promises no order and none leaks in from the caller: removal is not an
  ;; ordered operation (the whole prior owner goes in ONE release effect), and
  ;; no prior-plan order is available to report anyway — the live routing
  ;; handoff records `(:identities plan)` as an UNORDERED MAP under
  ;; `[:rf.runtime/routing :resource-plan <token>]` (rf2-btdl1) and hands that
  ;; same map back as the next activation's `:prev-identities`. Filtering the
  ;; caller's collection in place would republish carrier-iteration order while
  ;; CLAIMING the prior plan's.
  ;;
  ;; Dropping to that carrier is necessary but not sufficient, which is exactly
  ;; what this test caught: a small CLJS set/map is backed by an ARRAY map and
  ;; iterates in INSERTION order, so the caller's sequence walked straight back
  ;; out under CLJS while the JVM's hash iteration order hid the leak. The row is
  ;; sorted by the CEDN-1 `key-id`, which is what makes it a pure function of the
  ;; removal membership on BOTH hosts. Per Spec 009 §Where trace emission lives.
  (rf/reg-resource :rm/v (article-spec {}) article-spec-request)
  (rf/reg-resource :rm/a (article-spec {}) article-spec-request)
  (rf/reg-resource :rm/b (article-spec {}) article-spec-request)
  (rf/reg-resource :rm/c (article-spec {}) article-spec-request)
  (rf/reg-resource :rm/n (article-spec {}) article-spec-request)
  (let [key-of      (fn [id slug] (state/scoped-resource-key* :rf.scope/global id {:slug slug}))
        v-key       (key-of :rm/v "v")
        a-key       (key-of :rm/a "a")
        b-key       (key-of :rm/b "b")
        c-key       (key-of :rm/c "c")
        n-key       (key-of :rm/n "n")
        parent-meta {:resources [{:resource :rm/v :params (fn [_] {:slug "v"}) :blocking? true}]}
        branch      [{:route-id :route/q   :route-meta parent-meta}
                     {:route-id :route/q.n :route-meta {:resources [{:resource :rm/n :params (fn [_] {:slug "n"})}]}}]
        ;; the shared ancestor has LOADED, so it is genuinely adoptable (the
        ;; kept case) — leaving a / b / c as the three this plan drops.
        rdb         {:rf.runtime/resources
                     {:entries {(state/key-id v-key)
                                {:resource/id :rm/v :resource/key v-key
                                 :status :loaded :data {:n 1} :attempt 1}}}}
        tags-for    (fn [prev-identities]
                      (:tags (first (record-op-traces!
                                      :rf.resource/route-plan
                                      (fn [] (route/route-resource-plan
                                               {:id :route/q.n :params {} :query {}} {}
                                               {:nav-token       2
                                                :prev-id         :route/q.a
                                                :prev-nav-token  1
                                                :prev-identities prev-identities
                                                :branch          branch
                                                :runtime-db      rdb}))))))
        ;; the SAME four prior identities, supplied four ways: a vector, its
        ;; exact REVERSE, the SET the live routing handoff actually threads, and
        ;; a duplicate-bearing sequential.
        forward     (tags-for [v-key a-key b-key c-key])
        backward    (tags-for [c-key b-key a-key v-key])
        as-set      (tags-for #{v-key a-key b-key c-key})
        duplicated  (tags-for [a-key a-key v-key b-key b-key c-key c-key])]
    ;; rf2-o5dbf — `trace/emit!` sits behind `interop/debug-enabled?`, so the
    ;; row exists only in the dev posture. Under `-Dre-frame.debug=false` there
    ;; is no row to read and these assertions are vacuous by design.
    (when interop/debug-enabled?
      (testing "three prior identities are dropped, and the row names all three
                however the caller supplied them"
        (doseq [[label tags] [["a vector"                        forward]
                              ["its reverse"                     backward]
                              ["a SET (a de-duplicated caller)"   as-set]
                              ["a duplicate-bearing vector"      duplicated]]]
          (is (some? tags) (str label ": the activation emits one :rf.resource/route-plan row"))
          (is (= 3 (:removed tags))
              (str label ": three prior identities are dropped"))
          (is (= #{a-key b-key c-key} (set (:removed-identities tags)))
              (str label ": :removed-identities names exactly the dropped three"))
          (is (= (:removed tags) (count (:removed-identities tags)))
              (str label ": :removed is the SIZE of :removed-identities"))))
      (testing ":removed-identities is a pure function of the removal MEMBERSHIP
                — no caller-supplied ordering leaks into the row"
        (is (= (:removed-identities forward) (:removed-identities backward))
            (str "reversing the caller's :prev-identities MUST NOT reorder "
                 ":removed-identities — the row promises membership, not the "
                 "prior plan's order (Spec 009 §Where trace emission lives)"))
        (is (= (:removed-identities forward) (:removed-identities as-set))
            (str "a de-duplicated caller collection and a sequential one "
                 "must get the byte-identical row"))
        (is (= (:removed-identities forward) (:removed-identities duplicated))
            (str "a duplicate in :prev-identities can neither duplicate an "
                 ":removed-identities entry nor put :removed out of step with it")))
      (testing "the ORDERED vectors are untouched — they still ride the
                planner's grouped plan order"
        (is (= [v-key n-key] (:identities forward)))
        (is (= [n-key] (:ensured-identities forward)))
        (is (= [v-key] (:kept-identities forward)))))))

(deftest r2-identity-membership-is-byte-exact-not-clojure-equal
  ;; rf2-dlkou (merged-PR audit of #7228) — the identity partition is keyed on
  ;; the CEDN-1 BYTE key-id, not on Clojure `=`.
  ;;
  ;; Resource identity is `state/key-id`, and it is collection-KIND sensitive
  ;; (rf2-wgutc2): `{:slug "s" :tags ["a"]}` and `{:slug "s" :tags '("a")}` live
  ;; at two `state/entry-path`s, and yet the two scoped keys are `=` to Clojure
  ;; AND hash alike. Every `=`-keyed carrier therefore collapses the pair, and
  ;; the row's canonical `key-id` ordering runs AFTER the loss rather than
  ;; before it — so sorting could not save it.
  ;;
  ;; The defect this pins: a navigation whose prior plan held the LIST-bearing
  ;; identity and whose next plan holds the VECTOR-bearing one reported
  ;; `:removed 0` with an EMPTY `:removed-identities`, because the prior identity
  ;; tested as still-present against a set that only knows `=`. The removal was
  ;; real — the entry sits at its own byte path and the prior owner's release did
  ;; let it go — so the row contradicted the runtime, which is exactly what this
  ;; bead's acceptance forbids.
  ;;
  ;; The pair is `=` under `clojure.core/=`, so an assertion written with `=`
  ;; would pass on the WRONG key. Every claim below is therefore made on
  ;; `state/key-id` of the emitted value.
  (rf/reg-resource :bx/v (article-spec {}) article-spec-request)
  (rf/reg-resource :bx/n (article-spec {}) article-spec-request)
  (rf/reg-resource :bx/p (article-spec {}) article-spec-request)
  (let [vec-key     (state/scoped-resource-key* :rf.scope/global :bx/p {:slug "p" :tags ["a"]})
        list-key    (state/scoped-resource-key* :rf.scope/global :bx/p {:slug "p" :tags '("a")})
        v-key       (state/scoped-resource-key* :rf.scope/global :bx/v {:slug "v"})
        n-key       (state/scoped-resource-key* :rf.scope/global :bx/n {:slug "n"})
        parent-meta {:resources [{:resource :bx/v :params (fn [_] {:slug "v"}) :blocking? true}]}
        leaf-with-p {:resources [{:resource :bx/n :params (fn [_] {:slug "n"})}
                                 {:resource :bx/p :params (fn [_] {:slug "p" :tags ["a"]})}]}
        leaf-sans-p {:resources [{:resource :bx/n :params (fn [_] {:slug "n"})}]}
        ;; `branch+p` plans the VECTOR-bearing identity; `branch-p` plans
        ;; neither member of the pair, so both are dropped.
        branch+p    [{:route-id :route/b :route-meta parent-meta}
                     {:route-id :route/b.n :route-meta leaf-with-p}]
        branch-p    [{:route-id :route/b :route-meta parent-meta}
                     {:route-id :route/b.n :route-meta leaf-sans-p}]
        loaded      (fn [k rid] [(state/key-id k)
                                 {:resource/id rid :resource/key k
                                  :status :loaded :data {:n 1} :attempt 1}])
        ;; the shared ancestor is LOADED, so it is genuinely adoptable.
        rdb         {:rf.runtime/resources {:entries (into {} [(loaded v-key :bx/v)])}}
        ;; …and here the VECTOR-bearing identity is loaded too, so adoption
        ;; across the pair is REACHABLE if membership were `=`-keyed.
        rdb+p       {:rf.runtime/resources {:entries (into {} [(loaded v-key :bx/v)
                                                               (loaded vec-key :bx/p)])}}
        tags-for    (fn [branch runtime-db prev-identities]
                      (:tags (first (record-op-traces!
                                      :rf.resource/route-plan
                                      (fn [] (route/route-resource-plan
                                               {:id :route/b.n :params {} :query {}} {}
                                               {:nav-token       2
                                                :prev-id         :route/b.a
                                                :prev-nav-token  1
                                                :prev-identities prev-identities
                                                :branch          branch
                                                :runtime-db      runtime-db}))))))
        ids         (fn [ks] (mapv state/key-id ks))]
    (testing "premise: the two params shapes are ONE value to Clojure and TWO
              identities to the cache"
      (is (= vec-key list-key)
          "clojure.core/= cannot tell them apart, which is why every =-keyed
           carrier collapsed them")
      (is (= 1 (count (set [vec-key list-key])))
          "…and neither can a set: the collapse is in the carrier, not in a
           comparison this code could have written differently")
      (is (not= (state/key-id vec-key) (state/key-id list-key))
          "premise: while the CEDN-1 byte identities differ (rf2-wgutc2)")
      (is (not= (state/entry-path vec-key) (state/entry-path list-key))
          "premise: so the cache holds two entries, and dropping one IS a
           removal"))
    (when interop/debug-enabled?
      (testing "the prior plan's LIST-bearing identity is reported REMOVED when
                the next plan holds only its VECTOR-bearing twin"
        (let [tags (tags-for branch+p rdb [v-key list-key])]
          (is (= 1 (:removed tags))
              "one prior identity was dropped, and the row says so — it read
               `:removed 0` before this repair")
          (is (= (ids [list-key]) (ids (:removed-identities tags)))
              "…and names the LIST-bearing key, asserted on its byte identity
               because `=` would accept the vector-bearing one here")
          (is (= (ids [n-key vec-key]) (ids (:ensured-identities tags)))
              "the VECTOR-bearing identity is ENSURED — it has no entry of its
               own, and membership no longer matches its twin")
          (is (= (ids [v-key]) (ids (:kept-identities tags)))
              "…while the genuinely adoptable ancestor is still kept")))
      (testing "ADOPTION does not cross the pair either: a LIST-bearing prior
                identity must not hand its owner to the VECTOR-bearing twin,
                even when that twin's own entry is adoptable"
        (let [tags (tags-for branch+p rdb+p [v-key list-key])]
          (is (= (ids [n-key vec-key]) (ids (:ensured-identities tags)))
              "the twin is ENSURED — it is loaded and adoptable, so ONLY the
               byte-exact membership test keeps it out of the kept vector")
          (is (= (ids [v-key]) (ids (:kept-identities tags))))
          (is (= (ids [list-key]) (ids (:removed-identities tags))))))
      (testing "…while a prior identity that IS the planned one is adopted, so
                the claim above is about the pair and not about adoption
                being broken"
        (let [tags (tags-for branch+p rdb+p [v-key vec-key])]
          (is (= (ids [v-key vec-key]) (ids (:kept-identities tags)))
              "both adoptable prior identities are kept")
          (is (= (ids [n-key]) (ids (:ensured-identities tags))))
          (is (= 0 (:removed tags)) "and nothing was dropped")))
      (testing "both members are dropped together when NEITHER is planned"
        (let [gone (tags-for branch-p rdb [list-key vec-key])]
          (is (= 2 (:removed gone))
              "both byte identities are removed — a set-backed carrier reported
               ONE, having already thrown the other away")
          (is (= (sort (ids [list-key vec-key])) (sort (ids (:removed-identities gone))))
              "…and both are named")
          (is (= (:removed-identities gone)
                 (:removed-identities (tags-for branch-p rdb [vec-key list-key])))
              "the row is still a pure function of the removal MEMBERSHIP —
               swapping the caller's order does not move it")
          (is (= (:removed gone) (count (:removed-identities gone)))
              "and :removed is the SIZE of the vector, so the count cannot
               drift from the membership it summarizes"))))))

(deftest r2-navigating-between-byte-distinct-twins-reports-the-removal
  ;; rf2-dlkou (merged-PR audit of #7228) — the same defect END TO END, through
  ;; the real `:rf.route/navigate` path and the real routing handoff, rather
  ;; than a direct planner call.
  ;;
  ;; Two sibling routes declare the SAME resource under the SAME scope, with
  ;; params that differ only in the KIND of one collection: `{:tags ["a"]}` vs
  ;; `{:tags '("a")}`. Those are two cache entries at two `state/entry-path`s
  ;; and one value to Clojure `=`. Navigating between them therefore removes one
  ;; identity and ensures the other — and the row said `:removed 0`.
  ;;
  ;; Each plan here holds ONE member of the pair. The case where a SINGLE plan
  ;; holds BOTH — which the old set-shaped `[:rf.runtime/routing :resource-plan]`
  ;; slot could not carry at all — is rf2-btdl1's twin regression below.
  (rf/reg-resource :tw/feed (article-spec {}) article-spec-request)
  (rf/reg-route :route/tw-vec
                {:resources [{:resource :tw/feed :params (fn [_] {:slug "f" :tags ["a"]})}]}
                "/tw/vec")
  (rf/reg-route :route/tw-list
                {:resources [{:resource :tw/feed :params (fn [_] {:slug "f" :tags '("a")})}]}
                "/tw/list")
  (let [vec-key  (state/scoped-resource-key* :rf.scope/global :tw/feed {:slug "f" :tags ["a"]})
        list-key (state/scoped-resource-key* :rf.scope/global :tw/feed {:slug "f" :tags '("a")})]
    (rf/dispatch-sync [:rf.route/navigate {:to :route/tw-list}])
    (settle-success! list-key [{:id 1}])
    (testing "premise: the first navigation created the LIST-bearing entry, and
              the VECTOR-bearing twin does not exist"
      (is (some? (entry list-key)))
      (is (nil? (entry vec-key))
          "the cache keys on canonical bytes, so the twin is genuinely absent
           even though its scoped key is `=` to the one that is present"))
    (let [tags (:tags (first (record-op-traces!
                               :rf.resource/route-plan
                               (fn [] (rf/dispatch-sync
                                        [:rf.route/navigate {:to :route/tw-vec}])))))]
      (testing "the sibling navigation ensures the twin"
        (is (some? (entry vec-key))
            "a second entry now exists at the VECTOR-bearing byte path — the
             navigation really did dispatch an ensure rather than adopt across
             the pair"))
      (when interop/debug-enabled?
        (testing "…and the row reports the removal it performed"
          (is (= 1 (:removed tags))
              "the LIST-bearing identity left the plan — the row read
               `:removed 0` before this repair, because the prior identity
               tested as still-present against an `=`-keyed set")
          (is (= [(state/key-id list-key)] (mapv state/key-id (:removed-identities tags)))
              "…and it is named, asserted on the byte identity because `=`
               would have accepted the vector-bearing key here")
          (is (= [(state/key-id vec-key)] (mapv state/key-id (:ensured-identities tags)))
              "while the twin is ENSURED")
          (is (empty? (:kept-identities tags))
              "and nothing was kept — the two are not one identity"))))))

(deftest r2-a-plan-holding-both-byte-distinct-twins-plans-blocks-and-drains-both
  ;; rf2-btdl1 — THE twin regression. One plan requires BOTH members of an
  ;; `=`-equal but byte-DISTINCT pair, through the real `:rf.route/navigate`
  ;; path and the real routing handoff.
  ;;
  ;; Before this repair the pair could not survive the round trip at all, and
  ;; it failed twice over: `collapse-and-order` grouped occurrences by scoped
  ;; key under Clojure `=`, so two route entries requiring the two identities
  ;; produced ONE dedup-req — one ensure dispatched, the second byte identity
  ;; never fetched (a DISPATCH defect); and the `[:rf.runtime/routing
  ;; :resource-plan]` / `:resource-blocking` slots were SETS, which cannot hold
  ;; both members however carefully the planner counted.
  ;;
  ;; Every claim below is asserted on `state/key-id`, never on `=`: the two
  ;; scoped keys are `=` and hash alike, so an `=`-written assertion would pass
  ;; on the WRONG key and prove nothing.
  (rf/reg-resource :tw2/feed (article-spec {}) article-spec-request)
  (rf/reg-route :route/tw2-both
                {:resources [{:id        :vec-entry
                              :resource  :tw2/feed
                              :params    (fn [_] {:slug "f" :tags ["a"]})
                              :blocking? true}
                             {:id        :list-entry
                              :resource  :tw2/feed
                              :params    (fn [_] {:slug "f" :tags (list "a")})
                              :blocking? true}]}
                "/tw2/both")
  (let [vec-key  (state/scoped-resource-key* :rf.scope/global :tw2/feed {:slug "f" :tags ["a"]})
        list-key (state/scoped-resource-key* :rf.scope/global :tw2/feed {:slug "f" :tags (list "a")})
        ids      (fn [ks] (vec (sort (mapv state/key-id ks))))
        rdb      (fn [] (:rf.db/runtime (rf/frame-state-value :rf/default)))]
    (testing "premise: ONE value to Clojure, TWO identities to the cache"
      (is (= vec-key list-key))
      (is (= 1 (count (set [vec-key list-key]))))
      (is (not= (state/key-id vec-key) (state/key-id list-key)))
      (is (not= (state/entry-path vec-key) (state/entry-path list-key))))
    (let [tags      (:tags (first (record-op-traces!
                                    :rf.resource/route-plan
                                    (fn [] (rf/dispatch-sync
                                             [:rf.route/navigate {:to :route/tw2-both}])))))
          token     (:nav-token (slice))
          ;; the blocking carrier AS COMMITTED — the SSR drain reads exactly
          ;; this and pumps until every member settles.
          blocking0 (get-in (rdb) (route/blocking-path token))]
      (testing "TWO dedup-reqs, TWO ensures — the second identity is really fetched"
        (is (some? (entry vec-key)) "the vector-bearing identity was ensured")
        (is (some? (entry list-key)) "…and so was its list-bearing twin")
        (is (= 2 (count (select-keys (entries) [(state/key-id vec-key)
                                                (state/key-id list-key)])))
            "two cache entries at two byte paths — a collapsed plan leaves one"))
      (testing "the handoff slot carries BOTH identities, byte-keyed"
        (is (= (blocking-map vec-key list-key) (get-in (rdb) (route/plan-path token)))
            "[:rf.runtime/routing :resource-plan <token>] is {key-id scoped-key}
             and holds the pair — a set held exactly one of them"))
      (testing "…and so does the blocking slot: two independent wait points"
        (is (= (blocking-map vec-key list-key) blocking0))
        (is (= :loading (:transition (slice)))
            "both blocking first loads are outstanding"))
      (testing "the SSR drain sees both, and only both, as unsettled"
        (is (false? (res-ssr/blocking-settled? (entries) blocking0)))
        (is (= (ids [vec-key list-key])
               (ids (vals (res-ssr/unsettled-blocking-keys (entries) blocking0))))))
      (testing "each twin settles INDEPENDENTLY — one settle prunes one wait point"
        (settle-success! vec-key [{:id 1}])
        (is (= (blocking-map list-key) (get-in (rdb) (route/blocking-path token)))
            "only the LIST-bearing requirement is still outstanding; an
             `=`-keyed prune could not have told the two apart")
        (is (= :loading (:transition (slice)))
            "the route is still :loading — the twin has not settled")
        (is (false? (res-ssr/blocking-settled? (entries) blocking0))
            "the SSR drain agrees: one member of the pair is still in flight")
        (is (= (ids [list-key])
               (ids (vals (res-ssr/unsettled-blocking-keys (entries) blocking0))))))
      (testing "…and when the twin settles too the route lands and the drain ends"
        (settle-success! list-key [{:id 2}])
        (is (empty? (get-in (rdb) (route/blocking-path token))))
        (is (= :idle (:transition (slice))))
        (is (true? (res-ssr/blocking-settled? (entries) blocking0))))
      (when interop/debug-enabled?
        (testing "the plan row reports two ensures over two identities"
          (is (= 2 (:ensured tags)) "two real ensures, not one deduped ensure")
          (is (= 0 (:kept tags)))
          (is (= 0 (:removed tags)))
          (is (= (ids [vec-key list-key]) (ids (:identities tags))))
          (is (= (ids [vec-key list-key]) (ids (:ensured-identities tags))))
          (is (= (ids [vec-key list-key]) (ids (:blocking tags)))))))))

(deftest r2-a-transition-keeping-one-twin-and-removing-the-other-reports-both
  ;; rf2-btdl1 — the other half of the twin regression: a navigation AWAY from
  ;; the both-twins plan to one that keeps the VECTOR-bearing identity and
  ;; drops its LIST-bearing twin. The prior plan's handoff slot now carries
  ;; both, so the diff has both to reason about — under the old set-shaped slot
  ;; the prior plan arrived holding ONE identity and the removal was invisible.
  (rf/reg-resource :tw3/feed (article-spec {}) article-spec-request)
  (rf/reg-route :route/tw3-both
                {:resources [{:id :vec-entry  :resource :tw3/feed
                              :params (fn [_] {:slug "g" :tags ["a"]})}
                             {:id :list-entry :resource :tw3/feed
                              :params (fn [_] {:slug "g" :tags (list "a")})}]}
                "/tw3/both")
  (rf/reg-route :route/tw3-vec
                {:resources [{:resource :tw3/feed
                              :params (fn [_] {:slug "g" :tags ["a"]})}]}
                "/tw3/vec")
  (let [vec-key  (state/scoped-resource-key* :rf.scope/global :tw3/feed {:slug "g" :tags ["a"]})
        list-key (state/scoped-resource-key* :rf.scope/global :tw3/feed {:slug "g" :tags (list "a")})
        ids      (fn [ks] (mapv state/key-id ks))
        rdb      (fn [] (:rf.db/runtime (rf/frame-state-value :rf/default)))]
    (rf/dispatch-sync [:rf.route/navigate {:to :route/tw3-both}])
    ;; both twins LOAD, so the retained one is genuinely adoptable at commit.
    (settle-success! vec-key [{:id 1}])
    (settle-success! list-key [{:id 2}])
    (testing "premise: the first plan owns BOTH byte identities"
      (is (= (blocking-map vec-key list-key)
             (get-in (rdb) (route/plan-path (:nav-token (slice)))))))
    (let [tags  (:tags (first (record-op-traces!
                                :rf.resource/route-plan
                                (fn [] (rf/dispatch-sync
                                         [:rf.route/navigate {:to :route/tw3-vec}])))))
          token (:nav-token (slice))]
      (testing "the NEXT handoff carries exactly the surviving identity"
        (is (= (blocking-map vec-key) (get-in (rdb) (route/plan-path token)))))
      (when interop/debug-enabled?
        (testing "one KEPT, one REMOVED, nothing ensured — and the row names which"
          (is (= 1 (:kept tags)) "the vector-bearing twin was adopted, not re-fetched")
          (is (= 0 (:ensured tags)))
          (is (= 1 (:removed tags))
              "the list-bearing twin left the plan — a set-shaped prior slot
               reported :removed 0 here, having never carried it")
          (is (= (ids [vec-key]) (ids (:kept-identities tags)))
              "asserted on the byte identity: `=` would accept the twin")
          (is (= (ids [list-key]) (ids (:removed-identities tags))))
          (is (empty? (:ensured-identities tags)))
          (is (= (:kept tags) (count (:kept-identities tags))))
          (is (= (:removed tags) (count (:removed-identities tags)))))))))

(deftest r2-plan-order-is-witnessed-not-merely-membership
  ;; rf2-dlkou — `:identities` / `:ensured-identities` / `:kept-identities` carry
  ;; the planner's GROUPED PLAN ORDER, and order is the whole claim: a test that
  ;; checked set membership would pass on a shuffled vector. So the branch is
  ;; built so that plan order is NOT the order any other structure would produce
  ;; — the leaf declares its two resources in REVERSE alphabetical order, and the
  ;; parent's identity must still come first because the branch is walked
  ;; parent-most first.
  (rf/reg-resource :po/mid (article-spec {}) article-spec-request)
  (rf/reg-resource :po/zulu (article-spec {}) article-spec-request)
  (rf/reg-resource :po/alpha (article-spec {}) article-spec-request)
  (let [mid-key   (state/scoped-resource-key* :rf.scope/global :po/mid {:slug "m"})
        zulu-key  (state/scoped-resource-key* :rf.scope/global :po/zulu {:slug "z"})
        alpha-key (state/scoped-resource-key* :rf.scope/global :po/alpha {:slug "a"})
        branch    [{:route-id :route/p
                    :route-meta {:resources [{:resource :po/mid :params (fn [_] {:slug "m"})}]}}
                   {:route-id :route/p.leaf
                    :route-meta {:resources [{:resource :po/zulu  :params (fn [_] {:slug "z"})}
                                             {:resource :po/alpha :params (fn [_] {:slug "a"})}]}}]
        tags      (:tags (first (record-op-traces!
                                  :rf.resource/route-plan
                                  (fn [] (route/route-resource-plan
                                           {:id :route/p.leaf :params {} :query {}} {}
                                           {:nav-token 2 :branch branch :runtime-db {}})))))]
    (when interop/debug-enabled?
      (testing "the vectors carry PLAN order, which is neither declaration-name
                order nor the canonical byte order the removal vector uses"
        (is (= [mid-key zulu-key alpha-key] (:identities tags))
            (str "parent-most first, then the leaf's two in DECLARATION order — "
                 (pr-str (:identities tags))))
        (is (= (:identities tags) (:ensured-identities tags))
            "nothing is adoptable here, so the ensured vector is the whole plan
             in the same order")
        (is (not= (sort-by state/key-id (:identities tags)) (:identities tags))
            "and plan order is DISTINGUISHABLE from key-id order on this
             branch — otherwise the assertion above could not tell them apart")
        (is (not= (vec (sort-by (comp name second) (:identities tags))) (:identities tags))
            "…nor is it alphabetical by resource-id, which the leaf's reversed
             declaration order rules out")))))

(deftest r2-branch-resolve-fails-loud
  ;; A :parent naming an unregistered route aborts the plan (a committed failed
  ;; activation): empty next ownership, no partial ensure/adopt, prior owner
  ;; released.
  (let [plan (route/route-resource-plan
               {:id :route/leaf :params {} :query {}} {}
               {:nav-token 2 :prev-id :route/prev :prev-nav-token 1
                :prev-identities #{[:rf.scope/global :old/res {}]}
                :branch-error {:kind :unknown-parent :route-id* :route/ghost}})
        ds (plan-dispatches plan)]
    (testing "an unresolved :parent is a planning error"
      (is (some? (:plan-error plan)))
      (is (= :rf.error/resource-route-plan (:rf.error/id (:plan-error plan)))))
    (testing "no partial next owner is attached; the prior owner is released"
      (is (empty? (of-event ds :rf.resource/ensure)))
      (is (empty? (of-event ds :rf.resource/adopt-owner)))
      (is (= 1 (count (of-event ds :rf.resource/release-owner))))
      (is (empty? (:identities plan)) "empty next-ownership set"))))

(deftest r2-navigation-composes-registered-parent-chain
  ;; End-to-end: reg-route with :parent -> navigate to the child -> both the
  ;; parent shell resource AND the leaf resource are ensured, proving routing
  ;; fail-loud branch walk + the resources composition seam wire together.
  (rf/reg-resource :acct/viewer (article-spec {}) article-spec-request)
  (rf/reg-resource :acct/settings (article-spec {}) article-spec-request)
  (rf/reg-route :route/account
                {:resources [{:resource :acct/viewer :params (fn [_] {:slug "v"}) :blocking? true}]}
                "/account")
  (rf/reg-route :route/account.settings
                {:parent    :route/account
                 :resources [{:resource :acct/settings :params (fn [_] {:slug "s"}) :blocking? true}]}
                "/account/settings")
  (rf/dispatch-sync [:rf.route/navigate {:to :route/account.settings}])
  (let [viewer-key   (state/scoped-resource-key* :rf.scope/global :acct/viewer {:slug "v"})
        settings-key (state/scoped-resource-key* :rf.scope/global :acct/settings {:slug "s"})]
    (testing "activating the child composes the ancestor resource too"
      (is (some? (entry viewer-key)) "the parent shell resource is ensured on child activation")
      (is (some? (entry settings-key)) "the leaf resource is ensured"))))

(deftest r2-sibling-nav-adopts-kept-parent-without-refetch
  ;; End-to-end partial revalidation: navigate to one tab, settle the shared
  ;; banner, then navigate to the sibling tab. The banner is KEPT — its
  ;; generation is unchanged (no refetch), it keeps its data, and the removed
  ;; tab owner is released.
  (rf/reg-resource :prof/banner (article-spec {}) article-spec-request)
  (rf/reg-resource :prof/tab-one (article-spec {}) article-spec-request)
  (rf/reg-resource :prof/tab-two (article-spec {}) article-spec-request)
  (rf/reg-route :route/prof
                {:resources [{:resource :prof/banner :params (fn [_] {:slug "b"}) :blocking? true}]}
                "/prof")
  (rf/reg-route :route/prof.one
                {:parent    :route/prof
                 :resources [{:resource :prof/tab-one :params (fn [_] {:slug "one"})}]}
                "/prof/one")
  (rf/reg-route :route/prof.two
                {:parent    :route/prof
                 :resources [{:resource :prof/tab-two :params (fn [_] {:slug "two"})}]}
                "/prof/two")
  (let [banner-key (state/scoped-resource-key* :rf.scope/global :prof/banner {:slug "b"})
        tab1-key   (state/scoped-resource-key* :rf.scope/global :prof/tab-one {:slug "one"})]
    (rf/dispatch-sync [:rf.route/navigate {:to :route/prof.one}])
    (settle-success! banner-key {:name "Ada"})
    (settle-success! tab1-key [{:id 1}])
    (let [gen-before (:generation (entry banner-key))]
      (rf/dispatch-sync [:rf.route/navigate {:to :route/prof.two}])
      (testing "the kept banner is not refetched by the sibling navigation"
        (is (= gen-before (:generation (entry banner-key))) "generation unchanged — no revalidation")
        (is (state/has-data? (entry banner-key)) "banner keeps its loaded data"))
      (testing "the removed tab route owner is released"
        (let [t1 (entry tab1-key)]
          (is (or (nil? t1)
                  (not (some (fn [o] (and (vector? o) (= :route (first o)) (= :route/prof.one (second o))))
                             (:active-owners t1))))
              "tab-one [:route :route/prof.one _] owner is gone")))
      (testing "the route lands :idle (kept blocking banner already had data)"
        (is (= :idle (:transition (slice))))))))

;; ===========================================================================
;; 15. rf2-kqxe6.17 — EP-0037 R1 completion: the ONE readiness projector
;;
;;     Route readiness is a PURE projection over the active plan's blocking
;;     requirements (Spec 012 §Route readiness is a resource projection). These
;;     pin the table itself, then the paths that must project through it:
;;     activation commit, retained-owner adoption, resource settle, SSR
;;     hydration, and epoch restore.
;; ===========================================================================

;; ---- the table ------------------------------------------------------------

(deftest requirement-state-reads-spec-016-facts-not-a-settle-signal
  (testing "an absent entry is pending — its ensure has not been applied yet"
    (is (= :pending (route/requirement-state nil))))
  (testing "own usable data is :ready"
    (is (= :ready (route/requirement-state {:status :loaded :data {:a 1}}))))
  (testing "a BACKGROUND-refresh failure keeps its data and stays :ready"
    ;; `entry-failed` returns a :fetching-with-data entry to :loaded and records
    ;; :refresh-error — it must NOT read as a failed first load, so it never
    ;; makes the route :error.
    (is (= :ready (route/requirement-state
                    {:status        :loaded :data {:a 1}
                     :refresh-error {:kind :rf.http/server :status 503}}))))
  (testing "a FIRST-load failure (no usable data, :error) is :failed"
    (is (= :failed (route/requirement-state
                     {:status :error :data nil :attempt 1
                      :error  {:kind :rf.http/server :status 503}}))))
  (testing "work in flight is :pending"
    (is (= :pending (route/requirement-state {:status :loading :data nil :attempt 1})))
    (is (= :pending (route/requirement-state {:status :fetching :data nil :attempt 1}))))
  (testing "an enqueued but never-attempted entry is :pending (its load is coming)"
    (is (= :pending (route/requirement-state {:status :idle :data nil :attempt 0}))))
  (testing "settled with no data and nothing left to settle it is :inert"
    ;; an ABORTED first load — it neither completes nor fails the route.
    (is (= :inert (route/requirement-state {:status :idle :data nil :attempt 1}))))
  (testing "previous-data can never complete a newly-keyed first load"
    ;; `:previous-key` is a projection POINTER; the new key's own `:data` is
    ;; still nil, so the requirement is a pending FIRST load.
    (is (= :pending (route/requirement-state
                      {:status       :loading :data nil :attempt 1
                       :previous-key [:rf.scope/global :article/by-slug {:slug "a"}]})))))

;; ---- the projector, as a pure function ------------------------------------

(defn- runtime-db-with
  "Hand-build a runtime-db carrying a route slice at `nav-token` with
  `transition`, a blocking slot naming every key in `entries-by-key`, and those
  durable cache entries. The pure-projection fixture."
  [nav-token transition entries-by-key]
  {:rf.runtime/routing   {:current           {:route-id   :route/article
                                              :nav-token  nav-token
                                              :transition transition
                                              :error      nil}
                          :resource-blocking {nav-token (apply blocking-map (keys entries-by-key))}}
   :rf.runtime/resources {:entries (into {} (map (fn [[k e]] [(state/key-id k) e]))
                                         entries-by-key)}})

(def ^:private req-a (state/scoped-resource-key* :rf.scope/global :article/by-slug {:slug "a"}))
(def ^:private req-b (state/scoped-resource-key* :rf.scope/global :article/by-slug {:slug "b"}))

(deftest reconcile-readiness-projects-the-spec-012-table
  (testing "all requirements ready → :idle, and the slot is pruned empty"
    (let [rdb (route/reconcile-readiness
                (runtime-db-with "nav-1" :loading {req-a {:status :loaded :data {:x 1}}}))]
      (is (= :idle (get-in rdb [:rf.runtime/routing :current :transition])))
      (is (nil? (get-in rdb [:rf.runtime/routing :current :error])))
      (is (empty? (get-in rdb (route/blocking-path "nav-1")))
          "a resolved requirement is pruned, so a later invalidation cannot re-block")))
  (testing "one still pending → :loading"
    (let [rdb (route/reconcile-readiness
                (runtime-db-with "nav-1" :idle {req-a {:status :loaded :data {:x 1}}
                                                req-b {:status :loading :data nil :attempt 1}}))]
      (is (= :loading (get-in rdb [:rf.runtime/routing :current :transition])))
      (is (= (blocking-map req-b) (get-in rdb (route/blocking-path "nav-1")))
          "only the outstanding requirement remains")))
  (testing "a failed blocking first load → :error carrying the structured error"
    (let [rdb (route/reconcile-readiness
                (runtime-db-with "nav-1" :loading
                                 {req-a {:resource/id :article/by-slug
                                         :status      :error :data nil :attempt 1
                                         :error       {:kind :rf.http/server :status 503}}}))
          err (get-in rdb [:rf.runtime/routing :current :error])]
      (is (= :error (get-in rdb [:rf.runtime/routing :current :transition])))
      (is (= :rf.error/resource-route-blocking (:rf.error/id err)))
      (is (= :article/by-slug (:resource-id err)))
      (is (= (blocking-map req-a) (get-in rdb (route/blocking-path "nav-1")))
          "the failed requirement is NOT pruned — a later successful load re-projects :idle")))
  (testing "an ABORTED first load un-blocks rather than erroring the route"
    (let [rdb (route/reconcile-readiness
                (runtime-db-with "nav-1" :loading {req-a {:status :idle :data nil :attempt 1}}))]
      (is (= :idle (get-in rdb [:rf.runtime/routing :current :transition]))
          "settled-but-empty with nothing left to settle it neither completes nor fails")
      (is (nil? (get-in rdb [:rf.runtime/routing :current :error])))))
  (testing "no blocking slot for the live token is a structural no-op"
    ;; This is what keeps a committed PLANNING error (:error on the slice, no
    ;; blocking slot written) from being clobbered back to :idle.
    (let [rdb {:rf.runtime/routing
               {:current {:nav-token  "nav-1"
                          :transition :error
                          :error      {:rf.error/id :rf.error/resource-route-plan}}}}]
      (is (identical? rdb (route/reconcile-readiness rdb))))))

;; ---- the error trace is EDGE-triggered (rf2-kqxe6.17) ----------------------

(def ^:private pending-req
  {:resource/id :article/by-slug :status :loading :data nil :attempt 1})

(defn- failed-req
  "A blocking FIRST-load failure whose envelope `:status` identifies it."
  [http-status]
  {:resource/id :article/by-slug :status :error :data nil :attempt 1
   :error       {:kind :rf.http/server :status http-status}})

(defn- blocking-error-traces [traces]
  (errors-of traces :rf.error/resource-route-blocking))

(deftest reconcile-readiness-emits-the-blocking-error-once-per-edge-into-error
  ;; rf2-kqxe6.17 — `reconcile-readiness` re-picks the deterministic first
  ;; failure over the CURRENT outstanding set on EVERY settle. When a second
  ;; blocking requirement fails LATER but sorts canonically EARLIER, that pick
  ;; legitimately moves — but the route never left `:error`, so there is no new
  ;; transition to report. The trace is gated on the transition EDGE, not on
  ;; value-inequality with the slice.
  (testing "a second failure while ALREADY :error re-picks silently — one trace"
    (let [[early late] (sort-by state/key-id [req-a req-b])
          final  (volatile! nil)
          traces (record-error-traces!
                   (fn []
                     ;; settle 1 — the canonically LATER requirement fails first,
                     ;; taking the route from :loading INTO :error.
                     (let [rdb1 (route/reconcile-readiness
                                  (runtime-db-with "nav-1" :loading
                                                   {early pending-req
                                                    late  (failed-req 503)}))]
                       ;; settle 2 — the canonically EARLIER one fails too. It
                       ;; becomes the deterministic first failure.
                       (vreset! final
                                (route/reconcile-readiness
                                  (assoc-in rdb1 (state/entry-path early)
                                            (failed-req 500)))))))
          rdb2   @final]
      (is (= :error (get-in rdb2 [:rf.runtime/routing :current :transition])))
      (is (= 500 (get-in rdb2 [:rf.runtime/routing :current :error :error :status]))
          (str "the slice reports the CURRENT deterministic first failure — "
               ":error is a pure function of the live outstanding set, NOT a "
               "latched first observation that could go stale on refetch"))
      (is (= (blocking-map early late) (get-in rdb2 (route/blocking-path "nav-1")))
          "neither failed requirement is pruned")
      (is (= 1 (count (blocking-error-traces traces)))
          "ONE trace per transition INTO :error, not one per settle")))
  (testing "a GENUINE re-entry into :error still emits — the edge gate does not over-suppress"
    ;; The failed identity refetches successfully (route → :idle), then fails
    ;; again. That is a real second transition into :error and must be reported.
    (let [traces (record-error-traces!
                   (fn []
                     (let [rdb1 (route/reconcile-readiness
                                  (runtime-db-with "nav-1" :loading
                                                   {req-a (failed-req 503)}))
                           ;; the retry lands: :error → :idle (and the now-ready
                           ;; requirement is pruned from the slot)
                           rdb2 (route/reconcile-readiness
                                  (assoc-in rdb1 (state/entry-path req-a)
                                            {:resource/id :article/by-slug
                                             :status :loaded :data {:x 1}}))]
                       (is (= :idle (get-in rdb2 [:rf.runtime/routing :current :transition]))
                           "a successful load re-projects :idle — no stale error survives")
                       ;; a fresh activation re-blocks on the same identity, which
                       ;; fails again
                       (route/reconcile-readiness
                         (-> rdb2
                             (assoc-in (route/blocking-path "nav-1") (blocking-map req-a))
                             (assoc-in (state/entry-path req-a) (failed-req 500)))))))]
      (is (= 2 (count (blocking-error-traces traces)))
          "two distinct transitions INTO :error are two traces"))))

;; ---- 1. activation commit reads the facts AT COMMIT ------------------------

(deftest fresh-blocking-resource-commits-idle-with-no-transient-loading
  ;; A blocking route resource whose identity ALREADY has usable data must
  ;; commit :idle. It is recorded in NO blocking slot, so the commit's own
  ;; readiness seed is :idle — the route never passes through :loading and no
  ;; later drain is needed to rescue it.
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource  :article/by-slug
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? true}]} "/articles/:slug")
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "intro"})]
    ;; warm the identity OUTSIDE any navigation (an ownerless preload)
    (rf/dispatch-sync [:rf.resource/ensure {:resource :article/by-slug :params {:slug "intro"}}])
    (settle-success! scoped-key {:title "Intro"})
    (is (state/has-data? (entry scoped-key)) "precondition: the identity is warm")
    (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
    (let [nav-token (:nav-token (slice))]
      (testing "the already-fresh blocking requirement is recorded nowhere"
        (is (empty? (blocking-slot nav-token))
            "no blocking slot ⇒ the commit seed itself projected :idle"))
      (testing "the route commits :idle"
        (is (= :idle (:transition (slice))))
        (is (nil? (:error (slice))))))))

(deftest a-cold-blocking-resource-still-commits-loading
  ;; The contrast guard for the test above: with no usable data at commit the
  ;; requirement IS recorded and the route commits :loading, exactly as before.
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource  :article/by-slug
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? true}]} "/articles/:slug")
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "cold"}}])
  (let [nav-token  (:nav-token (slice))
        scoped-key (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "cold"})]
    (is (= #{scoped-key} (blocking-slot nav-token)))
    (is (= :loading (:transition (slice))))))

;; ---- 2. a background-refresh failure never errors the route ----------------

(deftest background-refresh-failure-keeps-the-route-idle
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource  :article/by-slug
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? true}]} "/articles/:slug")
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "intro"}}])
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "intro"})]
    (settle-success! scoped-key {:title "Intro"})
    (is (= :idle (:transition (slice))) "precondition: the blocking first load landed")
    ;; a REFRESH over the loaded entry, which then fails
    (rf/dispatch-sync [:rf.resource/refetch {:resource :article/by-slug :params {:slug "intro"}}])
    (is (= :fetching (:status (entry scoped-key))) "precondition: refreshing over usable data")
    (settle-failure! scoped-key {:kind :rf.http/server :status 503 :message "upstream down"})
    (testing "the failure lands on the resource's :refresh-error channel"
      (let [e (entry scoped-key)]
        (is (= :loaded (:status e)) "the entry keeps its data")
        (is (some? (:refresh-error e)))
        (is (nil? (:error e)) "NOT the first-load :error channel")))
    (testing "the route stays :idle — a refresh failure is not a route error"
      (is (= :idle (:transition (slice))))
      (is (nil? (:error (slice)))))))

;; ---- 3. previous data does not complete a newly-keyed first load -----------

(deftest keep-previous-projection-does-not-complete-the-new-first-load
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource       :article/by-slug
                              :params         (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking?      true
                              :keep-previous? true}]} "/articles/:slug")
  (let [key-a (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "a"})
        key-b (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "b"})]
    (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "a"}}])
    (settle-success! key-a {:title "A"})
    (is (= :idle (:transition (slice))) "precondition: slug a landed")
    (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "b"}}])
    (let [b (entry key-b)]
      (testing "the new key projects the previous key's data but owns none"
        (is (= key-a (:previous-key b)) "the projection pointer is set")
        (is (nil? (:data b)) "previous data is NEVER inserted into the new entry"))
      (testing "the route stays :loading — previous pixels are not a completed load"
        (is (= :pending (route/requirement-state b)))
        (is (= :loading (:transition (slice))))
        (is (contains? (blocking-slot (:nav-token (slice))) key-b))))))

;; ---- 4/5. hydration + epoch restore reconcile a contradicted cache ---------

(deftest restore-recomputes-a-readiness-the-restored-resources-contradict
  (testing "a snapshot's :loading whose requirement restored WITH data lands :idle"
    (let [rdb (res-ssr/reconcile-on-restore
                (runtime-db-with "nav-1" :loading
                                 {req-a {:resource/id  :article/by-slug
                                         :resource/key req-a
                                         :status       :loaded :data {:x 1} :attempt 1}}))]
      (is (= :idle (get-in rdb [:rf.runtime/routing :current :transition]))
          "restore must not preserve a :loading the restored resource state contradicts")))
  (testing "a snapshot captured MID-LOAD does not restore a :loading nothing can settle"
    ;; The in-flight attempt did not survive the restore (its work row is
    ;; dangled and the entry settles to last-stable :idle), so a preserved
    ;; :loading would hang the route forever.
    (let [rdb (res-ssr/reconcile-on-restore
                (runtime-db-with "nav-1" :loading
                                 {req-a {:resource/id  :article/by-slug
                                         :resource/key req-a
                                         :status       :loading :data nil :attempt 1
                                         :current-work "work-1"}}))]
      (is (= :idle (get-in rdb [:rf.runtime/routing :current :transition])))
      (is (empty? (get-in rdb (route/blocking-path "nav-1"))))))
  (testing "a restored FAILED blocking requirement projects the route :error"
    (let [rdb (res-ssr/reconcile-on-restore
                (runtime-db-with "nav-1" :loading
                                 {req-a {:resource/id  :article/by-slug
                                         :resource/key req-a
                                         :status       :error :data nil :attempt 1
                                         :error        {:kind :rf.http/server :status 503}}}))]
      (is (= :error (get-in rdb [:rf.runtime/routing :current :transition])))
      (is (= :rf.error/resource-route-blocking
             (:rf.error/id (get-in rdb [:rf.runtime/routing :current :error])))))))

(deftest hydration-recomputes-a-readiness-the-hydrated-resources-contradict
  (rf/reg-resource :article/by-slug (article-spec {}) article-spec-request)
  (let [rdb (res-ssr/hydrate-runtime-db
              (runtime-db-with "nav-1" :loading
                               {req-a {:resource/id  :article/by-slug
                                       :resource/key req-a
                                       :status       :loaded :data {:x 1} :attempt 1}}))]
    (is (= :idle (get-in rdb [:rf.runtime/routing :current :transition]))
        "hydration must not preserve a :loading the hydrated entries contradict")
    (is (empty? (get-in rdb (route/blocking-path "nav-1"))))))

;; ===========================================================================
;; 16. rf2-kqxe6.6 — EP-0037 R2 follow-through
;;
;;     (a) RETAINED-ENTRY LOSS. Prior-plan MEMBERSHIP alone does not make an
;;         identity adoptable. `:rf.resource/adopt-owner` issues no fetch, so
;;         adopting an identity whose entry has vanished (clear / remove / GC /
;;         a hydration-like mismatch) or cannot progress (settled with no data
;;         and no live work) commits a blocking slot nothing can ever drain —
;;         a permanent `:loading`. A retained identity is adopted only when it
;;         is genuinely REUSABLE (own usable data, or genuinely live work);
;;         anything else takes the ordinary ensure/readiness path.
;;
;;     (b) CONTRIBUTOR ATTRIBUTION. A parent-chain plan resolves every
;;         contributor's declarations against the LEAF target, so the leaf
;;         `:route-id` alone cannot say WHICH declaration failed. Spec 016
;;         §Effective parent-chain resource plans rule 3 requires the error to
;;         identify both the contributor route id and the resource declaration.
;; ===========================================================================

;; ---- (a) the adoptability predicate ---------------------------------------

(defn- ledger-with
  "A `:rf.runtime/work-ledger` map carrying `work-id` at `status`, keyed the way
  the runtime keys it (the CEDN-1 byte identity, not the work-id itself)."
  [work-id status]
  {(work-ledger/work-id-id work-id) {:work/id work-id :status status}})

(deftest adoptable-splits-reusable-from-unusable-retained-identities
  ;; Derived from the ONE projector's `requirement-state` classification — this
  ;; is not a second readiness table. The only split it adds is INSIDE
  ;; `:pending`, which deliberately conflates "work is in flight" with "no work
  ;; yet, the plan is about to ensure it": readiness treats both as "the route
  ;; waits", adoption must not.
  (let [live-work {:rf.runtime/work-ledger (ledger-with "w-live" :running)}
        dead-work {:rf.runtime/work-ledger (ledger-with "w-dead" :cancelled)}
        doomed    {:rf.runtime/work-ledger (ledger-with "w-doom" :abort-requested)}]
    (testing "own usable data is reusable — adopt, never revalidate"
      (is (route/adoptable? {} {:status :loaded :data {:a 1} :attempt 1})))
    (testing "genuinely live work is reusable — its own settle will drain the slot"
      (is (route/adoptable? live-work {:status :loading :data nil :attempt 1
                                       :current-work "w-live"})))
    (testing "an ABSENT entry is not adoptable — adopt-owner would be a no-op"
      (is (not (route/adoptable? {} nil))))
    (testing "an enqueued but never-attempted entry is not adoptable — no work exists"
      (is (not (route/adoptable? {} {:status :idle :data nil :attempt 0}))))
    (testing "settled with no data and nothing left to settle it is not adoptable"
      (is (not (route/adoptable? {} {:status :idle :data nil :attempt 1}))))
    (testing "a failed first load is not adoptable — there is nothing to reuse"
      (is (not (route/adoptable? {} {:status :error :data nil :attempt 1
                                     :error {:kind :rf.http/server}}))))
    (testing "an in-flight-LOOKING entry whose work is dead is not adoptable"
      ;; `:current-work` alone is not proof of work — the LINKED RECORD'S status
      ;; is (the same liveness `ensure`'s dedupe gate reads).
      (is (not (route/adoptable? dead-work {:status :loading :data nil :attempt 1
                                            :current-work "w-dead"})))
      (is (not (route/adoptable? doomed {:status :loading :data nil :attempt 1
                                         :current-work "w-doom"})))
      (is (not (route/adoptable? {} {:status :loading :data nil :attempt 1
                                     :current-work "w-pruned"}))
          "a pointer with no record at all is dead work too"))))

;; ---- (a) the planner routes unusable retained identities to ensure ---------

(defn- rdb-with-entries
  "A runtime-db carrying only durable cache `entries-by-key` (plus an optional
  work ledger) — the AT-COMMIT facts routing threads into the plan hook."
  ([entries-by-key] (rdb-with-entries entries-by-key nil))
  ([entries-by-key ledger]
   (cond-> {:rf.runtime/resources {:entries (into {} (map (fn [[k e]] [(state/key-id k) e]))
                                                  entries-by-key)}}
     ledger (assoc :rf.runtime/work-ledger ledger))))

(deftest r2-retained-identity-is-adopted-only-when-genuinely-reusable
  (rf/reg-resource :sh/v (article-spec {}) article-spec-request)
  (rf/reg-resource :lf/b (article-spec {}) article-spec-request)
  (let [parent-meta {:resources [{:resource :sh/v :params (fn [_] {:slug "v"}) :blocking? true}]}
        branch      [{:route-id :route/p   :route-meta parent-meta}
                     {:route-id :route/p.b :route-meta {:resources [{:resource :lf/b :params (fn [_] {:slug "b"})}]}}]
        shared-key  (state/scoped-resource-key* :rf.scope/global :sh/v {:slug "v"})
        plan-for    (fn [runtime-db]
                      (route/route-resource-plan
                        {:id :route/p.b :params {} :query {}} {}
                        {:nav-token 2 :prev-id :route/p.a :prev-nav-token 1
                         :prev-identities #{shared-key} :branch branch
                         :runtime-db runtime-db}))]
    (testing "a LOADED retained identity is adopted — the partial-revalidation law"
      (let [plan (plan-for (rdb-with-entries {shared-key {:resource/id :sh/v :status :loaded
                                                          :data {:n 1} :attempt 1}}))
            ds   (plan-dispatches plan)]
        (is (= [:sh/v] (mapv #(:resource (second %)) (of-event ds :rf.resource/adopt-owner))))
        (is (= [:lf/b] (mapv #(:resource (second %)) (of-event ds :rf.resource/ensure))))
        (is (not (contains? (:blocking plan) (state/key-id shared-key)))
            "already has usable data — nothing left to wait for")))
    (testing "an IN-FLIGHT retained identity is adopted — its own settle drains the slot"
      (let [plan (plan-for (rdb-with-entries
                             {shared-key {:resource/id :sh/v :status :loading :data nil
                                          :attempt 1 :current-work "w-1"}}
                             (ledger-with "w-1" :running)))
            ds   (plan-dispatches plan)]
        (is (= [:sh/v] (mapv #(:resource (second %)) (of-event ds :rf.resource/adopt-owner))))
        (is (contains? (:blocking plan) (state/key-id shared-key)) "still outstanding")))
    (testing "a MISSING retained identity takes the ordinary ensure path"
      ;; The bead's repro: prior-plan membership alone dispatched adopt-owner,
      ;; which is a NO-OP on an absent entry — the committed blocking slot then
      ;; had nothing that could ever drain it.
      (let [plan (plan-for (rdb-with-entries {}))
            ds   (plan-dispatches plan)]
        (is (empty? (of-event ds :rf.resource/adopt-owner)))
        (is (= [:sh/v :lf/b] (mapv #(:resource (second %)) (of-event ds :rf.resource/ensure))))
        (is (contains? (:blocking plan) (state/key-id shared-key))
            "recorded blocking — and an ensure now exists to drain it")))
    (testing "an UNUSABLE retained identity (settled, no data, no work) is ensured"
      (let [plan (plan-for (rdb-with-entries {shared-key {:resource/id :sh/v :status :idle
                                                          :data nil :attempt 1}}))
            ds   (plan-dispatches plan)]
        (is (empty? (of-event ds :rf.resource/adopt-owner)))
        (is (= [:sh/v :lf/b] (mapv #(:resource (second %)) (of-event ds :rf.resource/ensure))))
        (is (contains? (:blocking plan) (state/key-id shared-key))
            "a blocking requirement with no usable data at commit must hold the route")))
    (testing "a retained identity whose work is DEAD is ensured, not adopted"
      (let [plan (plan-for (rdb-with-entries
                             {shared-key {:resource/id :sh/v :status :loading :data nil
                                          :attempt 1 :current-work "w-doomed"}}
                             (ledger-with "w-doomed" :abort-requested)))
            ds   (plan-dispatches plan)]
        (is (empty? (of-event ds :rf.resource/adopt-owner)))
        (is (= [:sh/v :lf/b] (mapv #(:resource (second %)) (of-event ds :rf.resource/ensure))))))
    (testing "attach-before-release holds on every route — the release is LAST"
      (doseq [rdb [(rdb-with-entries {shared-key {:resource/id :sh/v :status :loaded
                                                  :data {:n 1} :attempt 1}})
                   (rdb-with-entries {})]]
        (let [ds (plan-dispatches (plan-for rdb))]
          (is (= :rf.resource/release-owner (first (last ds))))
          (is (= [:route :route/p.a 1] (:owner (second (last ds))))))))))

;; ---- (a) end-to-end liveness: the route must actually settle ---------------

(defn- abort-current-work!
  "Abort the entry's live attempt through the internal abort reply. The first
  load settles to a non-error `:idle` with `:current-work` cleared — the
  `idle / no data / no work` retained entry the bead names."
  [scoped-key]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/aborted
                       {:resource/key scoped-key
                        :work/id      (:current-work e)
                        :generation   (:generation e)}])))

(defn- reg-shell-branch! []
  (rf/reg-resource :prof/banner (article-spec {}) article-spec-request)
  (rf/reg-resource :prof/tab-one (article-spec {}) article-spec-request)
  (rf/reg-resource :prof/tab-two (article-spec {}) article-spec-request)
  (rf/reg-route :route/prof
                {:resources [{:resource :prof/banner :params (fn [_] {:slug "b"}) :blocking? true}]}
                "/prof")
  (rf/reg-route :route/prof.one
                {:parent :route/prof
                 :resources [{:resource :prof/tab-one :params (fn [_] {:slug "one"})}]}
                "/prof/one")
  (rf/reg-route :route/prof.two
                {:parent :route/prof
                 :resources [{:resource :prof/tab-two :params (fn [_] {:slug "two"})}]}
                "/prof/two"))

(deftest r2-sibling-nav-recovers-a-retained-identity-that-vanished
  ;; LIVENESS. The shared parent banner is removed out from under the plan diff
  ;; (a public `:rf.resource/remove` — GC / clear-scope / reconciliation have
  ;; the same shape). The sibling navigation still sees it in the previous
  ;; plan's identity set. Before this fix it dispatched a no-op adopt-owner
  ;; against an absent entry while committing a blocking slot for it, so the
  ;; route stayed :loading with no entry, no work and no reply that could ever
  ;; drain it.
  (reg-shell-branch!)
  (let [banner-key (state/scoped-resource-key* :rf.scope/global :prof/banner {:slug "b"})
        tab1-key   (state/scoped-resource-key* :rf.scope/global :prof/tab-one {:slug "one"})]
    (rf/dispatch-sync [:rf.route/navigate {:to :route/prof.one}])
    (settle-success! banner-key {:name "Ada"})
    (settle-success! tab1-key [{:id 1}])
    (is (= :idle (:transition (slice))) "precondition: the first activation landed")
    (rf/dispatch-sync [:rf.resource/remove {:resource :prof/banner :params {:slug "b"}}])
    (is (nil? (entry banner-key)) "precondition: the retained identity is gone")

    (rf/dispatch-sync [:rf.route/navigate {:to :route/prof.two}])
    (let [nav-token (:nav-token (slice))]
      (testing "the vanished identity is re-ensured, not adopted into the void"
        (is (some? (entry banner-key)) "an entry exists again — the ensure path ran")
        (is (= :loading (:status (entry banner-key))))
        (is (some? (:current-work (entry banner-key)))
            "live work exists, so the committed blocking slot can drain"))
      (testing "the blocking slot settles — no permanent :loading"
        (is (= :loading (:transition (slice))) "the route legitimately waits")
        (is (contains? (blocking-slot nav-token) banner-key))
        (settle-success! banner-key {:name "Ada"})
        (is (= :idle (:transition (slice))))
        (is (empty? (blocking-slot nav-token)))
        (is (state/has-data? (entry banner-key)))))))

(deftest r2-sibling-nav-recovers-a-retained-identity-that-cannot-progress
  ;; LIVENESS. Same branch, but the retained banner EXISTS and is unusable: its
  ;; first load was aborted, so it sits `:idle` with no data and no current
  ;; work. Adopting it attaches an owner to a dead entry and issues no fetch —
  ;; the blocking requirement is silently never satisfied.
  (reg-shell-branch!)
  (let [banner-key (state/scoped-resource-key* :rf.scope/global :prof/banner {:slug "b"})
        tab1-key   (state/scoped-resource-key* :rf.scope/global :prof/tab-one {:slug "one"})]
    (rf/dispatch-sync [:rf.route/navigate {:to :route/prof.one}])
    (settle-success! tab1-key [{:id 1}])
    (abort-current-work! banner-key)
    (let [aborted (entry banner-key)]
      (is (= :idle (:status aborted)) "precondition: settled with no data")
      (is (nil? (:current-work aborted)) "precondition: no work left")
      (is (not (state/has-data? aborted)))
      (is (= :inert (route/requirement-state aborted))))

    (rf/dispatch-sync [:rf.route/navigate {:to :route/prof.two}])
    (let [nav-token (:nav-token (slice))
          banner    (entry banner-key)]
      (testing "the unusable retained identity takes the ordinary ensure path"
        (is (= :loading (:status banner)) "a fresh load started")
        (is (some? (:current-work banner)))
        (is (some (fn [o] (= [:route :route/prof.two nav-token] o)) (:active-owners banner))
            "the next owner is attached"))
      (testing "and the ordinary readiness path — it blocks, then settles"
        (is (contains? (blocking-slot nav-token) banner-key))
        (is (= :loading (:transition (slice))))
        (settle-success! banner-key {:name "Ada"})
        (is (= :idle (:transition (slice))))
        (is (state/has-data? (entry banner-key)))))))

(deftest r2-adoption-of-in-flight-work-neither-revalidates-nor-aborts
  ;; The counterweight to the two liveness regressions: a genuinely reusable
  ;; retained identity is still adopted WITHOUT revalidation, and releasing the
  ;; prior owner cannot abort work the next plan still needs.
  (reg-shell-branch!)
  (let [banner-key (state/scoped-resource-key* :rf.scope/global :prof/banner {:slug "b"})]
    (rf/dispatch-sync [:rf.route/navigate {:to :route/prof.one}])
    (let [before (entry banner-key)
          work   (:current-work before)]
      (is (= :loading (:status before)) "precondition: the banner is in flight")
      (rf/dispatch-sync [:rf.route/navigate {:to :route/prof.two}])
      (let [after (entry banner-key)]
        (testing "the in-flight identity is adopted, not restarted"
          (is (= (:generation before) (:generation after)) "no new generation")
          (is (= work (:current-work after)) "the same work record — no refetch"))
        (testing "releasing the prior owner did not abort the shared work"
          (is (not (work-ledger/terminal? (:status (work-ledger/get-record
                                                     (:rf.db/runtime (rf/frame-state-value :rf/default))
                                                     work))))))
        (testing "the adopted work's own settle lands the route"
          (settle-success! banner-key {:name "Ada"})
          (is (= :idle (:transition (slice))))))
      (testing "a LOADED retained identity is likewise never revalidated"
        (let [gen (:generation (entry banner-key))]
          (rf/dispatch-sync [:rf.route/navigate {:to :route/prof.one}])
          (is (= gen (:generation (entry banner-key))) "generation unchanged")
          (is (= :idle (:transition (slice)))))))))

;; ---- (b) contributor attribution on an ancestor planning failure -----------

(defn- ancestor-branch
  "A two-segment branch whose ANCESTOR carries `anc-entry` and whose leaf
  carries a plain resource. The leaf is the plan target."
  [anc-entry]
  [{:route-id :route/ancestor :route-meta {:resources [anc-entry]}}
   {:route-id :route/leaf
    :route-meta {:resources [{:resource :audit/leaf :id :lf :params (fn [_] {:slug "l"})}]}}])

(defn- ancestor-plan-error
  "Plan the leaf over `branch`; return `[plan error-traces]`."
  [branch]
  (let [plan   (atom nil)
        traces (record-error-traces!
                 (fn [] (reset! plan (route/route-resource-plan
                                       {:id :route/leaf :params {} :query {}} {}
                                       {:nav-token 1 :branch branch}))))]
    [@plan traces]))

(deftest r2-ancestor-planning-failure-names-the-contributing-declaration
  (rf/reg-resource :audit/ancestor (article-spec {}) article-spec-request)
  (rf/reg-resource :audit/leaf (article-spec {}) article-spec-request)
  (testing "an ancestor :params resolver returning nil"
    (let [[plan traces] (ancestor-plan-error
                          (ancestor-branch {:resource :audit/ancestor :id :anc
                                            :params (fn [_] nil)}))
          err (:plan-error plan)]
      (is (= :rf.error/resource-route-plan (:rf.error/id err)))
      (testing "the LEAF target and the resource are named (unchanged)"
        (is (= :route/leaf (:route-id err)))
        (is (= :audit/ancestor (:resource-id err))))
      (testing "and so is the CONTRIBUTING route + local declaration"
        (is (= {:route-id :route/ancestor :local-id :anc} (:contributor err))))
      (testing "the error TRACE carries the same attribution"
        (let [tags (:tags (first (errors-of traces :rf.error/resource-route-plan)))]
          (is (some? tags))
          (is (= :route/leaf (:route-id tags)) "the leaf target")
          (is (= :audit/ancestor (:resource-id tags)))
          (is (= {:route-id :route/ancestor :local-id :anc} (:contributor tags)))))
      (testing "the plan stays fail-closed — no partial ensures or adoptions"
        (let [ds (plan-dispatches plan)]
          (is (empty? (of-event ds :rf.resource/ensure)))
          (is (empty? (of-event ds :rf.resource/adopt-owner)))
          (is (empty? (:identities plan)))))))
  (testing "an ancestor :scope resolver returning nil"
    (let [[plan _] (ancestor-plan-error
                     (ancestor-branch {:resource :audit/ancestor :id :anc
                                       :params (fn [_] {:slug "a"})
                                       :scope  (fn [_ _] nil)}))]
      (is (= {:route-id :route/ancestor :local-id :anc} (:contributor (:plan-error plan))))
      (is (= :fix-scope (:recovery (:plan-error plan))) "the specific recovery survives")))
  (testing "an ancestor :when predicate that throws"
    (let [[plan _] (ancestor-plan-error
                     (ancestor-branch {:resource :audit/ancestor :id :anc
                                       :params (fn [_] {:slug "a"})
                                       :when   (fn [_ _] (throw (ex-info "boom" {})))}))]
      (is (= {:route-id :route/ancestor :local-id :anc} (:contributor (:plan-error plan))))))
  (testing "an ancestor :after naming an id no contributor declares"
    ;; The local `:after` validation runs over the contributor's WHOLE declared
    ;; vector before `:when` filters it, so the contributor route is the only
    ;; thing the leaf-shaped error was missing.
    (let [[plan _] (ancestor-plan-error
                     (ancestor-branch {:resource :audit/ancestor :id :anc
                                       :params (fn [_] {:slug "a"})
                                       :after  #{:not-a-local-id}}))
          err (:plan-error plan)]
      (is (= :route/ancestor (get-in err [:contributor :route-id])))
      (is (= :fix-after (:recovery err)))))
  (testing "a LEAF failure is attributed to the leaf — attribution is not ancestor-only"
    (let [[plan _] (ancestor-plan-error
                     [{:route-id :route/ancestor
                       :route-meta {:resources [{:resource :audit/ancestor :id :anc
                                                 :params (fn [_] {:slug "a"})}]}}
                      {:route-id :route/leaf
                       :route-meta {:resources [{:resource :audit/leaf :id :lf
                                                 :params (fn [_] nil)}]}}])]
      (is (= {:route-id :route/leaf :local-id :lf} (:contributor (:plan-error plan))))))
  (testing "a resolver throwing its OWN :contributor cannot publish a FALSE one"
    ;; rf2-kqxe6.6 — `:contributor` is the PLANNER's key. A `:when` / `:params`
    ;; / `:scope` resolver is arbitrary programmer code and may throw any
    ;; `ex-info`, including one carrying an unnamespaced `:contributor` of its
    ;; own. Treating that as authoritative published a fabricated attribution
    ;; on BOTH the route slice and the error trace, defeating the whole point
    ;; of Spec 016 §Effective parent-chain resource plans rule 3. The planner
    ;; knows the actual contributor and always wins.
    (let [[plan traces] (ancestor-plan-error
                          (ancestor-branch
                            {:resource :audit/ancestor :id :anc
                             :params   (fn [_]
                                         (throw (ex-info "boom"
                                                  {:contributor {:route-id :wrong
                                                                 :local-id :wrong}})))}))
          err  (:plan-error plan)
          tags (:tags (first (errors-of traces :rf.error/resource-route-plan)))]
      (is (= {:route-id :route/ancestor :local-id :anc} (:contributor err))
          "the ACTUAL contributing declaration, not the resolver's claim")
      (is (some? tags))
      (is (= {:route-id :route/ancestor :local-id :anc} (:contributor tags))
          "the trace agrees with the slice — one source of truth")
      (is (= :route/leaf (:route-id err)) "the leaf target is unchanged"))))

(deftest r2-warm-prefetch-planning-failure-is-attributed-too
  ;; The warm plan shares `materialize-occurrences`, so the same attribution
  ;; rides its planning error (with :plan-cause :prefetch and no nav-token).
  (rf/reg-resource :audit/ancestor (article-spec {}) article-spec-request)
  (rf/reg-resource :audit/leaf (article-spec {}) article-spec-request)
  (let [plan (route/route-resource-warm-plan
               {:id :route/leaf :params {} :query {}}
               {:branch (ancestor-branch {:resource :audit/ancestor :id :anc
                                          :params (fn [_] nil)})})
        err  (:plan-error plan)]
    (is (= :prefetch (:plan-cause err)))
    (is (= {:route-id :route/ancestor :local-id :anc} (:contributor err)))
    (is (empty? (:fx plan)) "fail-closed — no partial warm ensures")))
