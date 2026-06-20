(ns re-frame.resources-populate-exact-target-cljs-test
  "Populate-as-authoritative-load + map-form exact targets (rf2-h6n40v,
  EP-0016 Riders 1 + 2 / slice 6 — Spec 016 §Populate is an authoritative load
  + §Map-form exact resource targets).

  Slice 6 completes two riders the earlier slices set up but did not consume:

    R2 — the MAP-FORM exact target `{:resource :params :scope}` is the ONLY
         public input form for `:populates` / `:patches` (no tuple migration
         window; the tuple is the internal STORAGE key). The map's `:scope` may
         be concrete, `:rf.scope/same` (the default), or a `{:from-db …}` named
         resolver reference resolved against the settle-time app-db; a
         nil-resolving reference is FAIL-CLOSED (the target is dropped, never
         written under an implicit global).

    R1 — a `:populates` is an AUTHORITATIVE load: the populated key becomes
         `:loaded`/fresh with the resource's stored shape, and is EXEMPT from
         the SAME mutation's invalidation refetch (even when an `:invalidates`
         tag matches it) UNLESS a descriptor opts in with
         `:refetch-populated? true`.

  These JVM+CLJS unit tests pin the slice's semantics:

    1. a map-form populate writes the EXACT canonical scoped key
       authoritatively (loaded/fresh, the resource's stored shape, its tags);
    2. a map-form `:scope {:from-db …}` populate resolves the scoped key
       against the settle-time app-db (the session feed case);
    3. a populated key is EXEMPT from the same mutation's invalidation refetch
       by default — even when the invalidation tag matches it;
    4. `:refetch-populated? true` re-enables the same-mutation refetch of the
       populated key (the partial-reply case);
    5. the populate-exempt composes with the slice-5 invalidation descriptors
       AND the slice-4 `:reply-to` continuation (the continuation still fires);
    6. a STALE / superseded settle does NOT populate (the mandatory
       stale-suppression boundary);
    7. a map-form `:patches` updates the exact key only;
    8. a `{:from-db …}` populate target that resolves NIL is FAIL-CLOSED
       (dropped, recorded in the settlement trace — never an implicit global).

  The transport is exercised end-to-end by overriding `:rf.http/managed` with a
  capturing stub that synthesises the transport's reply-event-append shape."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load-bearing side-effecting requires: register the :rf.resource/* +
   ;; :rf.mutation/* events + subs + the generation cofx/fx.
   [re-frame.resources]
   [re-frame.resources.state :as state]
   [re-frame.registrar :as registrar]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   [re-frame.trace.tooling :as trace-tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- capturing transport ---------------------------------------------------

(def ^:private last-managed-args (atom nil))

(defn- init! []
  (registrar/clear-kind! :resource-scope)
  ;; the named db-derived viewer-session resolver (EP-0016 D3 canonical form)
  (rf/reg-resource-scope :t/session
    {:inputs  {:username [:db [:auth :user :username]]}
     :resolve (fn [{:keys [username]} _ctx]
                (when username [:rf.scope/session {:username username}]))})
  (rf/reg-event :t/login (fn [{:keys [db]} [_ username]] {:db (assoc-in db [:auth :user :username] username)})))

(defn- capturing-transport-fixture [f]
  (reset! last-managed-args nil)
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf/reg-fx :rf.resource/schedule-timers (fn [_ _] nil))
  (f))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter :init-fn init!}
       :cljs {:adapter reagent-adapter/adapter :init-fn init!}))
  capturing-transport-fixture)

;; ---- helpers ---------------------------------------------------------------

(defn- runtime-db [] (rf/runtime-db-value :rf/default))
(defn- entry [scoped-key] (get-in (runtime-db) (state/entry-path scoped-key)))
;; rf2-8iciw8 — `:rf.runtime/mutations` is keyed on the instance id's CEDN-1
;; byte `key-id` (`state/key-id`), not the raw id; resolve through it.
(defn- instance [instance-id]
  (get-in (runtime-db) [:rf.runtime/mutations (state/key-id instance-id)]))

(defn- reply-success! [args result]
  (rf/dispatch-sync (conj (:on-success args) {:kind :success :value result})))

(def ^:private global-article-key
  (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"}))

(defn- session-feed-key [u]
  (state/scoped-resource-key [:rf.scope/session {:username u}] :r/feed {}))

(defn- reg-article-resource! []
  (rf/reg-resource :r/article
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :tags (fn [{:keys [slug]} _] #{[:article slug] [:article-list]})}
    (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})))

(defn- reg-feed-resource! []
  (rf/reg-resource :r/feed
    {:scope {:from-db :t/session}
     :params-schema [:map]
     :tags (fn [_p _] #{[:feed] [:article-list]})}
    (fn [_p _] {:request {:method :get :url "/feed"}})))

(defn- own-loaded!
  "Ensure + load an entry so it has an ACTIVE owner (so a subsequent
  invalidation would REFETCH it). Resets `last-managed-args` after."
  [payload]
  (rf/dispatch-sync [:rf.resource/ensure payload])
  (reply-success! @last-managed-args {:seed true})
  (reset! last-managed-args nil))

(defn- succeeded-trace
  "Run `body-fn`; return the LAST `:rf.mutation/succeeded` trace event's
  top-level data map (its facets ride under `:tags`)."
  [body-fn]
  (let [seen (atom [])
        k    ::succeeded-recorder]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= :rf.mutation/succeeded (:operation ev)) (swap! seen conj ev))))
    (try (body-fn) (finally (trace-tooling/unregister-listener! k)))
    (:tags (last @seen))))

;; ===========================================================================
;; 1. A map-form populate writes the EXACT canonical scoped key authoritatively
;; ===========================================================================

(deftest map-form-populate-writes-exact-key-authoritatively
  ;; Validation 11/12: the public input is the map form {:resource :params
  ;; :scope}; it writes the EXACT canonical storage key, loaded/fresh, with the
  ;; resource's stored shape + tags — identical to a fetched entry.
  (reg-article-resource!)
  (rf/reg-mutation :m/save
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     ;; map-form target with a concrete :scope (EP-0016 Rider 2 — the only
     ;; public input form). No prior ensure — the populate SEEDS the entry.
     :populates (fn [{:keys [slug]} result]
                  {{:resource :r/article :params {:slug slug} :scope :rf.scope/global} result})}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :p1}])
  (reply-success! @last-managed-args {:slug "w" :title "Fresh"})
  (testing "the map-form populate seeded the EXACT canonical scoped key,
            :loaded with the result as :data + the resource's own tags"
    (let [e (entry global-article-key)]
      (is (= :loaded (:status e)))
      (is (= {:slug "w" :title "Fresh"} (:data e)))
      (is (nil? (:invalidated-at e)) "fresh — not stale")
      (is (= #{[:article "w"] [:article-list]} (:tags e)))))
  (testing "the populated key is recorded on the instance patch-summary (the
            canonical STORAGE tuple, not the input map)"
    (is (= [global-article-key]
           (:populated (:patch-summary (instance :p1)))))))

;; ===========================================================================
;; 2. A {:from-db …} populate :scope resolves the scoped key at settle time
;; ===========================================================================

(deftest map-form-populate-from-db-scope-resolves-at-settle
  ;; Validation 7/9: a map-form target whose :scope is a {:from-db …} resolver
  ;; reference resolves the EXACT scoped key against the settle-time app-db (the
  ;; session feed case) — the populated key lands under the resolved session.
  (reg-feed-resource!)
  (rf/reg-mutation :m/save-feed
    {:scope :rf.scope/global
     :params-schema [:map]
     :populates (fn [_p result]
                  {{:resource :r/feed :params {} :scope {:from-db :t/session}} result})}
    (fn [_p _] {:request {:method :put :url "/feed"}}))
  (rf/dispatch-sync [:t/login "jake"])
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save-feed :params {} :instance :pf1}])
  (reply-success! @last-managed-args {:articles [:x]})
  (testing "the {:from-db} populate seeded jake's SESSION-scoped feed key"
    (let [e (entry (session-feed-key "jake"))]
      (is (= :loaded (:status e)))
      (is (= {:articles [:x]} (:data e)))))
  (testing "no OTHER session was touched (the exact resolved key only)"
    (is (nil? (entry (session-feed-key "abel"))))))

;; ===========================================================================
;; 3. A populated key is EXEMPT from the same mutation's invalidation refetch
;; ===========================================================================

(deftest populated-key-exempt-from-same-mutation-refetch
  ;; Validation 11 (the core slice-6 rule): a mutation that POPULATES an
  ;; article-detail key and then INVALIDATES a tag that matches that same key
  ;; must NOT immediately refetch the key it just learned from the reply (a
  ;; populate is an authoritative load). The detail entry stays fresh; only the
  ;; OTHER tagged entry (a list) refetches.
  (reg-article-resource!)
  ;; the detail key has an ACTIVE owner — WITHOUT the exemption it would refetch
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :detail]})
  ;; a SEPARATE list entry (also :article-list tagged, but a different key) is
  ;; owned too — it MUST refetch (the populate did not seed it).
  (rf/reg-resource :r/article-list
    {:scope :rf.scope/global
     :params-schema [:map]
     :tags (fn [_p _] #{[:article-list]})}
    (fn [_p _] {:request {:method :get :url "/articles"}}))
  (own-loaded! {:resource :r/article-list :scope :rf.scope/global :params {} :owner [:v :list]})
  (rf/reg-mutation :m/favorite
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     ;; populate the detail key authoritatively from the reply…
     :populates (fn [{:keys [slug]} result]
                  {{:resource :r/article :params {:slug slug} :scope :rf.scope/global} result})
     ;; …then invalidate the broad article-list tag (which ALSO matches the
     ;; just-populated detail key, since the article resource carries it).
     :invalidates (fn [_p _r] #{[:article-list]})}
    (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug "/fav")}}))
  (let [list-key (state/scoped-resource-key :rf.scope/global :r/article-list {})
        trace    (succeeded-trace
                   #(do (rf/dispatch-sync [:rf.mutation/execute
                                           {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
                        (reply-success! @last-managed-args {:slug "w" :title "fav'd" :favorited true})))]
    (testing "the POPULATED detail key stayed FRESH (loaded, the populated
              value, NOT re-staled or refetched) — populate is authoritative"
      (let [e (entry global-article-key)]
        (is (= :loaded (:status e)))
        (is (= {:slug "w" :title "fav'd" :favorited true} (:data e)))
        (is (nil? (:invalidated-at e)) "the populated key was exempt — not re-staled")
        (is (not (contains? #{:loading :fetching} (:status e))) "no refetch armed")))
    (testing "the OTHER :article-list-tagged key (NOT populated) DID refetch"
      (let [e (entry list-key)]
        (is (contains? #{:loading :fetching} (:status e)))))
    (testing "the settlement trace records the populate-exempt key"
      (is (= [global-article-key] (:populate-exempt (:invalidation trace)))
          "the populated key is named as exempt from same-mutation refetch"))))

;; ===========================================================================
;; 4. :refetch-populated? true re-enables the same-mutation refetch
;; ===========================================================================

(deftest refetch-populated-opts-back-into-same-mutation-refetch
  ;; Validation 11 (the opt-in half): when the reply is PARTIAL relative to the
  ;; full GET, a descriptor sets :refetch-populated? true so the just-populated
  ;; key IS refetched by this same mutation's invalidation pass.
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :detail]})
  (rf/reg-mutation :m/save
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :populates (fn [{:keys [slug]} result]
                  {{:resource :r/article :params {:slug slug} :scope :rf.scope/global} result})
     ;; the descriptor opts the populated key BACK into the same-mutation refetch
     :invalidates (fn [{:keys [slug]} _r]
                    [{:scope :rf.scope/global :tags #{[:article slug]} :refetch-populated? true}])}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (let [trace (succeeded-trace
                #(do (rf/dispatch-sync [:rf.mutation/execute
                                        {:mutation :m/save :params {:slug "w"} :instance :rp1}])
                     (reply-success! @last-managed-args {:slug "w" :title "partial"})))]
    (testing ":refetch-populated? true — the populated key WAS refetched by this
              same mutation's invalidation pass (a fresh GET lowered)"
      (let [e (entry global-article-key)]
        (is (contains? #{:loading :fetching} (:status e)))
        (is (= {:method :get :url "/a/w"} (:request @last-managed-args)))))
    (testing "the trace records an EMPTY populate-exempt set (the opt-in cleared it)"
      (is (= [] (:populate-exempt (:invalidation trace)))))))

;; ===========================================================================
;; 4b. rf2-fi6tda.3 finding 2 — MIXED-descriptor populate-exempt evidence
;; ===========================================================================

(deftest mixed-descriptor-populate-exempt-not-collapsed-by-one-opt-in
  ;; rf2-fi6tda.3 finding 2: a mixed plan where ONE descriptor opts into
  ;; :refetch-populated? true and ANOTHER default descriptor matches the same
  ;; populated key. The runtime exempts per descriptor (plan->fx), so the
  ;; default descriptor's pass SPARES the populated key — the settlement
  ;; evidence MUST reflect that, NOT collapse to [] just because one descriptor
  ;; opted in. Both the per-descriptor :exempt-keys AND the top-level
  ;; :populate-exempt union are pinned.
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :detail]})
  (rf/reg-mutation :m/save
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :populates (fn [{:keys [slug]} result]
                  {{:resource :r/article :params {:slug slug} :scope :rf.scope/global} result})
     ;; descriptor 0 (opt-in, refetches the populated key) on [:article-list];
     ;; descriptor 1 (DEFAULT, spares the populated key) on [:article w] — both
     ;; tags match the populated detail key (the article resource carries both).
     :invalidates (fn [{:keys [slug]} _r]
                    [{:scope :rf.scope/global :tags #{[:article-list]} :refetch-populated? true}
                     {:scope :rf.scope/global :tags #{[:article slug]}}])}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (let [trace (succeeded-trace
                #(do (rf/dispatch-sync [:rf.mutation/execute
                                        {:mutation :m/save :params {:slug "w"} :instance :mx1}])
                     (reply-success! @last-managed-args {:slug "w" :title "fresh"})))
        inv   (:invalidation trace)
        dispatched (:dispatched inv)]
    (testing "two descriptors dispatched"
      (is (= 2 (:descriptor-count inv)))
      (is (= 2 (count dispatched))))
    (testing "the OPT-IN descriptor spared NO key (its own :exempt-keys is empty)"
      (let [opt-in (first (filter :refetch-populated? dispatched))]
        (is (= [] (:exempt-keys opt-in)))))
    (testing "the DEFAULT descriptor SPARED the populated key (its own :exempt-keys)"
      (let [default (first (remove :refetch-populated? dispatched))]
        (is (= [global-article-key] (:exempt-keys default)))))
    (testing "the top-level :populate-exempt is the UNION — NOT collapsed to []
              by the one opt-in descriptor (the bug rf2-fi6tda.3 flagged)"
      (is (= [global-article-key] (:populate-exempt inv))))))

;; ===========================================================================
;; 5. Composes with slice-5 descriptors AND the slice-4 :reply-to continuation
;; ===========================================================================

(deftest populate-exempt-composes-with-descriptors-and-reply-to
  ;; The populate-exempt pass composes with a per-descriptor scoped invalidation
  ;; (the favorite/feed case) AND the :reply-to completion continuation: the
  ;; populated detail stays fresh, the session feed is invalidated by a
  ;; {:from-db} descriptor, and the continuation still fires after.
  (let [replied (atom [])]
    (reg-article-resource!)
    (reg-feed-resource!)
    (rf/reg-event :test/saved (fn [_ ev] (swap! replied conj ev) {}))
    (rf/dispatch-sync [:t/login "jake"])
    ;; jake's feed is ownerless (so a {:from-db} descriptor leaves it stale)
    (rf/dispatch-sync [:rf.resource/ensure {:resource :r/feed :scope {:from-db :t/session}
                                            :params {} :owner [:v :feed]}])
    (reply-success! @last-managed-args {:seed true})
    (rf/dispatch-sync [:rf.resource/release-owner {:resource :r/feed :scope {:from-db :t/session}
                                                   :params {} :owner [:v :feed]}])
    ;; the detail key has an active owner (so WITHOUT the exemption it'd refetch)
    (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :detail]})
    (rf/reg-mutation :m/favorite
      {:scope :rf.scope/global
       :params-schema [:map [:slug :string]]
       :populates (fn [{:keys [slug]} result]
                    {{:resource :r/article :params {:slug slug} :scope :rf.scope/global} result})
       :invalidates (fn [{:keys [slug]} _r]
                      [{:scope :rf.scope/global :tags #{[:article slug] [:article-list]}}
                       {:scope {:from-db :t/session} :tags #{[:feed]}}])}
      (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug "/fav")}}))
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :c1
                                             :reply-to [:test/saved]}])
    (reply-success! @last-managed-args {:slug "w" :favorited true})
    (testing "the populated detail key stayed fresh (exempt from the global
              [:article …] descriptor that matches it)"
      (let [e (entry global-article-key)]
        (is (= :loaded (:status e)))
        (is (nil? (:invalidated-at e)))))
    (testing "the {:from-db} descriptor still invalidated jake's session feed"
      (is (some? (:invalidated-at (entry (session-feed-key "jake"))))))
    (testing "the :reply-to continuation fired exactly once, after the consequences"
      (is (= 1 (count @replied)))
      (is (= :ok (:status (second (first @replied))))))))

;; ===========================================================================
;; 6. A stale / superseded settle does NOT populate
;; ===========================================================================

(deftest stale-settle-does-not-populate
  ;; The populate path is gated behind the live-instance acceptance — a
  ;; superseded reply applies NO cache consequence, so it never seeds a key.
  (reg-article-resource!)
  (rf/reg-mutation :m/save
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :populates (fn [{:keys [slug]} result]
                  {{:resource :r/article :params {:slug slug} :scope :rf.scope/global} result})}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  ;; execute, capture its reply args, then SUPERSEDE by re-executing under the
  ;; same instance id (new generation / work-id) — the first reply is now stale.
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :s1}])
  (let [stale-args @last-managed-args]
    (reset! last-managed-args nil)
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :s1}])
    ;; deliver the STALE first reply — it must be suppressed, no populate
    (reply-success! stale-args {:slug "w" :title "stale"})
    (testing "the superseded reply did NOT populate the key"
      (is (nil? (entry global-article-key))))))

;; ===========================================================================
;; 7. A map-form :patches updates the exact key only (no tag targeting)
;; ===========================================================================

(deftest map-form-patch-updates-exact-key-only
  ;; Validation 12: a map-form :patches target updates exactly the resolved key
  ;; (transforms its existing :data); a key with no entry is a no-op (a patch
  ;; transforms; populate seeds).
  (reg-article-resource!)
  (rf/reg-mutation :m/patch
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :patches (fn [{:keys [slug]} result]
                {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                 (fn [old _r] (merge old result))})}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  ;; seed real data on the owned entry (a single ensure + reply)
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/article :scope :rf.scope/global
                                          :params {:slug "w"} :owner [:v :a]}])
  (reply-success! @last-managed-args {:title "old" :views 5})
  (reset! last-managed-args nil)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/patch :params {:slug "w"} :instance :pt1}])
  (reply-success! @last-managed-args {:title "new"})
  (testing "the map-form patch transformed the EXACT key's :data in place"
    (let [e (entry global-article-key)]
      (is (= {:title "new" :views 5} (:data e)))
      (is (= :loaded (:status e))))))

;; ===========================================================================
;; 8. A {:from-db …} populate target that resolves NIL is FAIL-CLOSED
;; ===========================================================================

(deftest from-db-populate-target-nil-fails-closed
  ;; A map-form populate whose {:from-db …} :scope resolves NIL (no logged-in
  ;; user) is DROPPED — no key is seeded under an implicit global, and the
  ;; settlement trace names the unresolved resolver id.
  (reg-feed-resource!)
  (rf/reg-mutation :m/save-feed
    {:scope :rf.scope/global
     :params-schema [:map]
     :populates (fn [_p result]
                  {{:resource :r/feed :params {} :scope {:from-db :t/session}} result})}
    (fn [_p _] {:request {:method :put :url "/feed"}}))
  ;; NOT logged in — the resolver's :inputs are absent, so {:from-db :t/session}
  ;; resolves nil.
  (let [trace (succeeded-trace
                #(do (rf/dispatch-sync [:rf.mutation/execute
                                        {:mutation :m/save-feed :params {} :instance :n1}])
                     (reply-success! @last-managed-args {:articles [:x]})))]
    (testing "FAIL-CLOSED — NO session feed key was seeded under any scope"
      (is (nil? (entry (session-feed-key "jake"))))
      (is (nil? (entry (state/scoped-resource-key :rf.scope/global :r/feed {})))
          "and never under an implicit global"))
    (testing "the dropped target's resolver id is recorded as :target-unresolved"
      (is (= [:t/session] (:target-unresolved (:patch-summary trace)))))))
