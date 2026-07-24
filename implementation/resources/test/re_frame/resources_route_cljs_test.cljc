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

(defn- blocking-slot [nav-token]
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) (route/blocking-path nav-token)))

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
      (is (contains? (:blocking plan) banner-key)))
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
  (rf/reg-resource :sh/v (article-spec {}) article-spec-request)
  (rf/reg-resource :lf/a (article-spec {}) article-spec-request)
  (rf/reg-resource :lf/b (article-spec {}) article-spec-request)
  (let [parent-meta {:resources [{:resource :sh/v :params (fn [_] {:slug "v"}) :blocking? true}]}
        branch1 [{:route-id :route/p   :route-meta parent-meta}
                 {:route-id :route/p.a :route-meta {:resources [{:resource :lf/a :params (fn [_] {:slug "a"})}]}}]
        plan1 (route/route-resource-plan {:id :route/p.a :params {} :query {}} {}
                                         {:nav-token 1 :branch branch1})
        ids1  (:identities plan1)
        branch2 [{:route-id :route/p   :route-meta parent-meta}
                 {:route-id :route/p.b :route-meta {:resources [{:resource :lf/b :params (fn [_] {:slug "b"})}]}}]
        plan2 (route/route-resource-plan {:id :route/p.b :params {} :query {}} {}
                                         {:nav-token 2 :prev-id :route/p.a :prev-nav-token 1
                                          :prev-identities ids1 :branch branch2})
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
                          :resource-blocking {nav-token (set (keys entries-by-key))}}
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
      (is (= #{req-b} (get-in rdb (route/blocking-path "nav-1")))
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
      (is (= #{req-a} (get-in rdb (route/blocking-path "nav-1")))
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
