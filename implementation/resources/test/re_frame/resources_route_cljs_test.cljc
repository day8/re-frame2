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
   [re-frame.resources.test-support]
   [re-frame.routing :as routing]
   [re-frame.schemas]
   [re-frame.http-managed]
   [re-frame.test-support :as core-test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

;; ---- fixture --------------------------------------------------------------

(defn- init!
  "Per-test setup (runs after adapter install, registrar live): re-register
  `:rf/default` as the URL-owning app frame, reset the routing counters +
  the resources host-side generation cache, re-publish the late-bound
  routing integration, and stub the managed-HTTP + push-url fx so ensure +
  navigation are deterministic without a fetch / browser."
  []
  (rf/reg-frame :rf/default {:url-bound? true
                             :doc "Route-resource suite default app frame."})
  (routing/reset-counters!)
  (state/reset-cache!)
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
          :request       (fn [{:keys [slug]} _ctx]
                           {:request {:method :get :url (str "/api/articles/" slug)}})
          :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
         overrides))

(defn- settle-success! [scoped-key data]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource-key scoped-key
                        :work-id      (:current-work e)
                        :generation   (:generation e)
                        :data         data}])))

(defn- settle-failure! [scoped-key error]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/failed
                       {:resource-key scoped-key
                        :work-id      (:current-work e)
                        :generation   (:generation e)
                        :error        error}])))

;; ===========================================================================
;; 1. Accepted-key extension
;; ===========================================================================

(deftest resources-route-key-is-accepted-when-both-artefacts-load
  (testing ":resources is an accepted bare route key once resources loads"
    (rf/reg-resource :article/by-slug (article-spec {}))
    (is (= :route/article
           (rf/reg-route :route/article
                         {:path      "/articles/:slug"
                          :params    [:map [:slug :string]]
                          :resources [{:resource :article/by-slug
                                       :params   (fn [route] {:slug (get-in route [:params :slug])})}]}))
        "reg-route with :resources does not throw — the key is accepted")))

;; ===========================================================================
;; 2. On route entry — owner + cause
;; ===========================================================================

(deftest route-entry-ensures-with-route-owner-and-cause
  (rf/reg-resource :article/by-slug (article-spec {}))
  (rf/reg-route :route/article
                {:path      "/articles/:slug"
                 :params    [:map [:slug :string]]
                 :resources [{:resource :article/by-slug
                              :params   (fn [route] {:slug (get-in route [:params :slug])})}]})
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
  (rf/reg-resource :article/by-slug (article-spec {}))
  (rf/reg-route :route/article
                {:path      "/articles/:slug"
                 :params    [:map [:slug :string]]
                 :resources [{:resource  :article/by-slug
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? true}]})
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
  (rf/reg-resource :comments/list (article-spec {}))
  (rf/reg-route :route/article
                {:path      "/articles/:slug"
                 :params    [:map [:slug :string]]
                 :resources [{:resource  :comments/list
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? false}]})
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
  (rf/reg-resource :article/by-slug (article-spec {}))
  (rf/reg-route :route/article
                {:path      "/articles/:slug"
                 :params    [:map [:slug :string]]
                 :resources [{:resource  :article/by-slug
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? true}]})
  (rf/reg-route :route/home {:path "/"})
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
  (rf/reg-resource :article/by-slug (article-spec {}))
  (rf/reg-route :route/article
                {:path      "/articles/:slug"
                 :params    [:map [:slug :string]]
                 :resources [{:resource  :article/by-slug
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? true}]})
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
  (rf/reg-resource :article/by-slug (article-spec {}))
  (rf/reg-route :route/article
                {:path      "/articles/:slug"
                 :params    [:map [:slug :string]]
                 :resources [{:resource :article/by-slug
                              :params   (fn [route] {:slug (get-in route [:params :slug])})}]})
  (rf/reg-route :route/home {:path "/"})
  (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "intro"}])
  (let [token-1    (:nav-token (slice))
        scoped-key (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "intro"})]
    (is (contains? (:active-owners (entry scoped-key)) [:route :route/article token-1]))
    (rf/dispatch-sync [:rf.route/navigate :route/home])
    (testing "leaving the route releases its nav-token owner from the entry"
      (is (not (contains? (:active-owners (entry scoped-key))
                          [:route :route/article token-1]))
          "the prior route owner was released on leave"))))

;; ===========================================================================
;; 6. :when gates the resource out
;; ===========================================================================

(deftest when-false-gates-the-resource-out
  (rf/reg-resource :comments/list (article-spec {}))
  (rf/reg-route :route/article
                {:path      "/articles/:slug"
                 :params    [:map [:slug :string]]
                 :resources [{:resource :comments/list
                              :params   (fn [route] {:slug (get-in route [:params :slug])})
                              :when     (fn [_route _ctx] false)}]})
  (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "intro"}])
  (testing ":when false admits no resource (NOT sentinel nil params)"
    (is (empty? (entries)) "the gated-out resource was not ensured")))

;; ===========================================================================
;; 7. :after orders dependent resources by route-local id
;; ===========================================================================

(deftest after-orders-dependent-resources-by-local-id
  (let [order (atom [])]
    (rf/reg-resource :article/by-slug
                     (article-spec {:request (fn [_p _]
                                               (swap! order conj :article)
                                               {:request {:method :get :url "/a"}})}))
    (rf/reg-resource :comments/list
                     (article-spec {:request (fn [_p _]
                                               (swap! order conj :comments)
                                               {:request {:method :get :url "/c"}})}))
    (rf/reg-route :route/article
                  {:path      "/articles/:slug"
                   :params    [:map [:slug :string]]
                   :resources [{:resource :comments/list
                                :id       :comments
                                :params   (fn [route] {:slug (get-in route [:params :slug])})
                                :after    #{:article}}
                               {:resource :article/by-slug
                                :id       :article
                                :params   (fn [route] {:slug (get-in route [:params :slug])})}]})
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
  (rf/reg-resource :secret/doc (article-spec {:scope :rf.scope/from-caller}))
  (rf/reg-route :route/secret
                {:path      "/secret/:slug"
                 :params    [:map [:slug :string]]
                 :resources [{:resource :secret/doc
                              :params   (fn [route] {:slug (get-in route [:params :slug])})}]})
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
                                                 :tags (fn [{:keys [page]} _] #{[:list page]})}))
  (rf/reg-route :route/list
                {:path      "/list"
                 :query     [:map [:page :int]]
                 :resources [{:resource       :articles/list
                              :params         (fn [route] {:page (get-in route [:query :page])})
                              :keep-previous? true}]})
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
