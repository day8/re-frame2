(ns re-frame.resources-invalidation-descriptors-cljs-test
  "Scoped invalidation descriptors (rf2-hwc2ev, EP-0016 D2 / slice 5 — Spec 016
  §Scoped invalidation descriptors).

  A mutation's `:invalidates` arm declares which resource `(tags, scope)` pairs
  to mark stale after the write settles. Two PUBLIC input forms lower to ONE
  scoped invalidation engine:

    - the bare tag-set shorthand `#{[:article slug] …}` — invalidate those tags
      in the mutation's resolved (execution) scope (`:rf.scope/same`);
    - the per-target DESCRIPTOR form `{:scope … :tags #{…}}` (a single
      descriptor or a vector) — each descriptor names its OWN scope, so one
      mutation can precisely invalidate global facts AND viewer-relative
      (session-scoped) facts in one execution, without a blunt cross-scope blast.

  These JVM+CLJS unit tests pin the slice's semantics:

    1. a bare tag-set still invalidates the mutation's resolved scope;
    2. a descriptor invalidates EXACTLY the resolved scoped keys — a
       `:rf.scope/global` descriptor + a `{:from-db …}` session descriptor in
       ONE mutation reach both, and only the resolved scope (not a global blast);
    3. re-fetch (active owner) vs mark-stale (ownerless) variants;
    4. the descriptors compose with the slice-4 `:reply-to` completion
       continuation (the continuation fires after the invalidation);
    5. a stale / superseded settle does NOT invalidate (the mandatory
       stale-suppression boundary the descriptor path inherits);
    6. a `{:from-db …}` descriptor scope resolved against the SETTLE-time
       app-db; a nil-resolving reference is FAIL-CLOSED (no invalidation, a loud
       diagnostic — never an implicit global blast);
    7. `:rf.scope/same` is the default when a descriptor omits `:scope`;
    8. a malformed `:invalidates` result fails CLOSED at settle time.

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
   [re-frame.resources.mutation-runtime :as mstate]
   [re-frame.registrar :as registrar]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   [re-frame.error-emit :as error-emit]
   [re-frame.trace.tooling :as trace-tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- capturing transport ---------------------------------------------------

(def ^:private last-managed-args (atom nil))

(defn- init! []
  (registrar/clear-kind! :resource-scope)
  ;; the named db-derived viewer-session resolver (EP-0016 D3 canonical form)
  (rf/reg-resource-scope :t/session
    {:inputs {:username [:db [:auth :user :username]]}}
    (fn [{:keys [username]} _ctx]
      (when username [:rf.scope/session {:username username}])))
  ;; an app event that writes / removes the logged-in user (the resolver input)
  (rf/reg-event :t/login (fn [{:keys [db]} [_ username]] {:db (assoc-in db [:auth :user :username] username)}))
  (rf/reg-event :t/logout (fn [{:keys [db]} _] {:db (update db :auth dissoc :user)})))

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

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [scoped-key] (get-in (runtime-db) (state/entry-path scoped-key)))
(defn- entries [] (get-in (runtime-db) (state/entries-path)))

(defn- reply-success!
  ([args result] (rf/dispatch-sync (conj (:on-success args) {:status :ok :value result})))
  ([args result opts] (rf/dispatch-sync (conj (:on-success args) {:status :ok :value result}) opts)))

(def ^:private global-key (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"}))
(defn- session-feed-key [u] (state/scoped-resource-key [:rf.scope/session {:username u}] :r/feed {}))

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
  "Ensure + load an entry under `payload` so it has an ACTIVE owner (so a
  subsequent invalidation REFETCHES it). Resets `last-managed-args` after."
  [payload]
  (rf/dispatch-sync [:rf.resource/ensure payload])
  (reply-success! @last-managed-args {:seed true})
  (reset! last-managed-args nil))

(defn- ownerless-stale-load!
  "Drive an entry to :loaded with NO active owner (ensure with an owner, then
  release it) so an invalidation leaves it stale (observable via
  :invalidated-at) rather than refetching it."
  [{:keys [resource params scope] :as payload}]
  (rf/dispatch-sync [:rf.resource/ensure payload])
  (reply-success! @last-managed-args {:seed true})
  (rf/dispatch-sync [:rf.resource/release-owner
                     (select-keys (assoc payload :owner (:owner payload))
                                  [:resource :params :scope :owner])])
  (reset! last-managed-args nil))

(defn- record-invalidations!
  "Run `body-fn`; return the vector of every `:rf.resource/invalidated` trace
  event emitted during it (one per descriptor dispatch)."
  [body-fn]
  (let [seen (atom [])
        k    ::inv-recorder]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= :rf.resource/invalidated (:operation ev)) (swap! seen conj ev))))
    (try (body-fn) (finally (trace-tooling/unregister-listener! k)))
    @seen))

;; ===========================================================================
;; 1. The bare tag-set shorthand invalidates the mutation's resolved scope
;; ===========================================================================

(deftest bare-tag-set-invalidates-resolved-scope
  (reg-article-resource!)
  (rf/reg-mutation :m/save
    {:params-schema [:map [:slug :string]]
     :invalidates (fn [{:keys [slug]} _result] #{[:article slug]})}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  ;; ownerless article — the invalidation leaves it stale (observable)
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/article :scope :rf.scope/global
                                          :params {:slug "w"} :owner [:v :a]}])
  (reply-success! @last-managed-args {:title "old"})
  (rf/dispatch-sync [:rf.resource/release-owner {:resource :r/article :scope :rf.scope/global
                                                 :params {:slug "w"} :owner [:v :a]}])
  (reset! last-managed-args nil)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :b1}])
  (reply-success! @last-managed-args {:title "new"})
  (testing "the bare tag-set invalidated the global article entry (the
            mutation's resolved scope), marking it stale"
    (is (some? (:invalidated-at (entry global-key))))))

;; ===========================================================================
;; 2. A descriptor reaches BOTH global and session scopes — exactly, no blast
;; ===========================================================================

(deftest descriptor-invalidates-global-and-session-exactly
  ;; Validation rule 6: a mutation invalidates global AND session-scoped
  ;; targets in ONE execution — and ONLY the resolved scoped keys (not a
  ;; global blast across all scopes). This is the RealWorld favorite/feed case.
  (reg-article-resource!)
  (reg-feed-resource!)
  (rf/dispatch-sync [:t/login "jake"])
  ;; a DIFFERENT user's feed must NOT be touched (the precise-vs-blast proof)
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/feed :scope [:rf.scope/session {:username "abel"}]
                                          :params {} :owner [:v :feed-abel]}])
  (reply-success! @last-managed-args {:seed true})
  (rf/dispatch-sync [:rf.resource/release-owner {:resource :r/feed :scope [:rf.scope/session {:username "abel"}]
                                                 :params {} :owner [:v :feed-abel]}])
  ;; jake's feed (session) + the global article, both ownerless + stale-observable
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/feed :scope {:from-db :t/session}
                                          :params {} :owner [:v :feed-jake]}])
  (reply-success! @last-managed-args {:seed true})
  (rf/dispatch-sync [:rf.resource/release-owner {:resource :r/feed :scope {:from-db :t/session}
                                                 :params {} :owner [:v :feed-jake]}])
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/article :scope :rf.scope/global
                                          :params {:slug "w"} :owner [:v :a]}])
  (reply-success! @last-managed-args {:title "old"})
  (rf/dispatch-sync [:rf.resource/release-owner {:resource :r/article :scope :rf.scope/global
                                                 :params {:slug "w"} :owner [:v :a]}])
  (reset! last-managed-args nil)
  (rf/reg-mutation :m/favorite
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :invalidates (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global :tags #{[:article slug]}}
                     {:scope {:from-db :t/session} :tags #{[:feed]}}])}
    (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug "/fav")}}))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
  (reply-success! @last-managed-args {:favorited true})
  (testing "the GLOBAL article entry was invalidated by the global descriptor"
    (is (some? (:invalidated-at (entry global-key)))))
  (testing "jake's SESSION feed was invalidated by the {:from-db} descriptor
            (resolved against the settle-time app-db)"
    (is (some? (:invalidated-at (entry (session-feed-key "jake"))))))
  (testing "abel's session feed was NOT touched — the descriptor invalidated
            EXACTLY the resolved scoped keys, not a global blast across scopes"
    (is (nil? (:invalidated-at (entry (session-feed-key "abel")))))))

;; ===========================================================================
;; 3. re-fetch (active owner) vs mark-stale (ownerless) variants
;; ===========================================================================

(deftest descriptor-refetches-active-owner-marks-stale-ownerless
  (reg-article-resource!)
  (reg-feed-resource!)
  (rf/dispatch-sync [:t/login "jake"])
  ;; the global article has an ACTIVE owner -> the descriptor must REFETCH it
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :a]})
  ;; jake's feed is OWNERLESS -> the descriptor must leave it stale (no refetch)
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/feed :scope {:from-db :t/session}
                                          :params {} :owner [:v :feed]}])
  (reply-success! @last-managed-args {:seed true})
  (rf/dispatch-sync [:rf.resource/release-owner {:resource :r/feed :scope {:from-db :t/session}
                                                 :params {} :owner [:v :feed]}])
  (reset! last-managed-args nil)
  (rf/reg-mutation :m/save
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :invalidates (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global :tags #{[:article slug]}}
                     {:scope {:from-db :t/session} :tags #{[:feed]}}])}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :v1}])
  (reply-success! @last-managed-args {:title "new"})
  (testing "the ACTIVE-owner global article REFETCHED (back in flight, a fresh GET lowered)"
    (is (contains? #{:loading :fetching} (:status (entry global-key))))
    (is (some? @last-managed-args))
    (is (= {:method :get :url "/a/w"} (:request @last-managed-args))))
  (testing "the OWNERLESS session feed was left STALE (not refetched — its
            :invalidated-at fact is set, no fetch started)"
    (let [e (entry (session-feed-key "jake"))]
      (is (some? (:invalidated-at e)))
      (is (not (contains? #{:loading :fetching} (:status e)))))))

;; ===========================================================================
;; 4. Composes with the slice-4 :reply-to completion continuation
;; ===========================================================================

(deftest descriptor-composes-with-reply-to-continuation
  (let [replied (atom [])]
    (reg-article-resource!)
    (reg-feed-resource!)
    (rf/reg-event :test/saved (fn [_ event] (swap! replied conj event) {}))
    (rf/dispatch-sync [:t/login "jake"])
    (rf/dispatch-sync [:rf.resource/ensure {:resource :r/feed :scope {:from-db :t/session}
                                            :params {} :owner [:v :feed]}])
    (reply-success! @last-managed-args {:seed true})
    (rf/dispatch-sync [:rf.resource/release-owner {:resource :r/feed :scope {:from-db :t/session}
                                                   :params {} :owner [:v :feed]}])
    (reset! last-managed-args nil)
    (rf/reg-mutation :m/save
      {:scope :rf.scope/global
       :params-schema [:map [:slug :string]]
       :invalidates (fn [_p _r] [{:scope {:from-db :t/session} :tags #{[:feed]}}])}
      (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :c1
                                             :reply-to [:test/saved]}])
    (reply-success! @last-managed-args {:title "new"})
    (testing "the {:from-db} descriptor invalidated jake's feed"
      (is (some? (:invalidated-at (entry (session-feed-key "jake"))))))
    (testing "the :reply-to continuation fired exactly once, after the invalidation"
      (is (= 1 (count @replied)))
      (is (= :ok (:status (second (first @replied))))))))

;; ===========================================================================
;; 5. A stale / superseded settle does NOT invalidate
;; ===========================================================================

(deftest stale-settle-does-not-invalidate
  ;; Validation: a superseded mutation reply NEVER applies cache consequences —
  ;; the descriptor invalidation is gated behind the live-instance acceptance.
  (reg-article-resource!)
  (rf/reg-mutation :m/save
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :invalidates (fn [{:keys [slug]} _result] #{[:article slug]})}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  ;; ownerless article (stale-observable)
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/article :scope :rf.scope/global
                                          :params {:slug "w"} :owner [:v :a]}])
  (reply-success! @last-managed-args {:title "old"})
  (rf/dispatch-sync [:rf.resource/release-owner {:resource :r/article :scope :rf.scope/global
                                                 :params {:slug "w"} :owner [:v :a]}])
  (reset! last-managed-args nil)
  ;; execute the mutation, CAPTURE its reply args, then SUPERSEDE it by a
  ;; re-execute under the SAME instance id (a new generation / work-id) — the
  ;; first reply is now stale.
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :s1}])
  (let [stale-args @last-managed-args]
    (reset! last-managed-args nil)
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :s1}])
    ;; deliver the STALE first reply — it must be suppressed, no invalidation
    (let [invs (record-invalidations! #(reply-success! stale-args {:title "stale"}))]
      (testing "the superseded reply fired NO invalidation"
        (is (empty? invs)))
      (testing "the article entry was NOT marked stale by the suppressed reply"
        (is (nil? (:invalidated-at (entry global-key))))))))

;; ===========================================================================
;; 6. {:from-db} resolved at settle time; nil-resolving is FAIL-CLOSED
;; ===========================================================================

(deftest from-db-descriptor-resolved-at-settle-time
  ;; Validation rule 7: a descriptor referencing a named scope resolver
  ;; resolves against db AT SETTLE TIME. We log in as "zed" only AFTER the
  ;; execute but before the reply settles — the descriptor must resolve to
  ;; zed's session at settle.
  (reg-article-resource!)
  (reg-feed-resource!)
  (rf/reg-mutation :m/save
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :invalidates (fn [_p _r] [{:scope {:from-db :t/session} :tags #{[:feed]}}])}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:t/login "zed"])
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/feed :scope {:from-db :t/session}
                                          :params {} :owner [:v :feed]}])
  (reply-success! @last-managed-args {:seed true})
  (rf/dispatch-sync [:rf.resource/release-owner {:resource :r/feed :scope {:from-db :t/session}
                                                 :params {} :owner [:v :feed]}])
  (reset! last-managed-args nil)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :z1}])
  (reply-success! @last-managed-args {:title "new"})
  (testing "the {:from-db} descriptor resolved zed's session at settle time and
            invalidated zed's feed"
    (is (some? (:invalidated-at (entry (session-feed-key "zed")))))))

(deftest from-db-descriptor-nil-fails-closed
  ;; A {:from-db} descriptor that resolves NIL (no logged-in user) produces NO
  ;; invalidation and a loud diagnostic — never an implicit global blast.
  (reg-article-resource!)
  (reg-feed-resource!)
  ;; NOT logged in — the resolver's :inputs are absent. A global article entry
  ;; exists; a global-blast bug would wrongly invalidate it via the [:feed]/
  ;; [:article-list] tag overlap.
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/article :scope :rf.scope/global
                                          :params {:slug "w"} :owner [:v :a]}])
  (reply-success! @last-managed-args {:title "old"})
  (rf/dispatch-sync [:rf.resource/release-owner {:resource :r/article :scope :rf.scope/global
                                                 :params {:slug "w"} :owner [:v :a]}])
  (reset! last-managed-args nil)
  (rf/reg-mutation :m/save
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :invalidates (fn [_p _r] [{:scope {:from-db :t/session} :tags #{[:article-list]}}])}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (let [seen (atom [])
        k    ::succeeded-recorder]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= :rf.mutation/succeeded (:operation ev)) (swap! seen conj ev))))
    (try
      (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :n1}])
      (reply-success! @last-managed-args {:title "new"})
      (finally (trace-tooling/unregister-listener! k)))
    (testing "the nil-resolving {:from-db} descriptor is recorded as fail-closed
              :unresolved evidence on the settlement trace's :invalidation facet"
      (is (= 1 (count @seen)))
      (let [inv (:invalidation (:tags (first @seen)))]
        (is (= [:t/session] (:unresolved inv)) "the unresolved resolver id is named")
        (is (empty? (:dispatched inv)) "no descriptor dispatched")))
    (testing "FAIL-CLOSED — the nil-resolving descriptor produced NO
              invalidation (the global article entry was NOT blasted)"
      (is (nil? (:invalidated-at (entry global-key)))))))

(deftest descriptor-trace-evidence-records-resolved-scopes
  ;; Validation rule 14 / Spec 016 §Trace evidence for invalidation: the
  ;; settlement trace's :invalidation facet records the resolved scope per
  ;; descriptor + the descriptor count.
  (reg-article-resource!)
  (reg-feed-resource!)
  (rf/dispatch-sync [:t/login "jake"])
  (rf/reg-mutation :m/favorite
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :invalidates (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global :tags #{[:article slug]}}
                     {:scope {:from-db :t/session} :tags #{[:feed]}}])}
    (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug "/fav")}}))
  (let [seen (atom [])
        k    ::succeeded-recorder]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= :rf.mutation/succeeded (:operation ev)) (swap! seen conj ev))))
    (try
      (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :t1}])
      (reply-success! @last-managed-args {:favorited true})
      (finally (trace-tooling/unregister-listener! k)))
    (testing "the :invalidation facet records both resolved descriptor scopes"
      (let [inv (:invalidation (:tags (first @seen)))]
        (is (= 2 (:descriptor-count inv)))
        (is (= 2 (count (:dispatched inv))))
        (is (= #{:rf.scope/global [:rf.scope/session {:username "jake"}]}
               (set (map :scope (:dispatched inv))))
            "the global descriptor + the {:from-db}-resolved session scope")
        (is (empty? (:unresolved inv)))))))

;; ---- rf2-700p0r: a typo'd CONCRETE literal scope in an :invalidates --------
;; DESCRIPTOR fails closed at settle through the SAME canonicalize-scope path.
;; The reserved-scope-typo rejection is covered elsewhere via OTHER surfaces
;; (the scope-resolver path: scope_registry/resolved-scope-routes-through-
;; canonicalization; the :patches/:populates target-map path:
;; mutation/validate-target-key-rejects-reserved-scope-typo). This pins it
;; through the :invalidates DESCRIPTOR wiring specifically — a regression that
;; bypassed canonicalize-scope HERE would turn a typo into a silent wrong /
;; no-op cache-scope invalidation, the exact fail-closed promise the EP makes.

(defn- record-surfaced-errors!
  "Run `body-fn`; return the vector of always-on error-emit records surfaced
  during it (the production-survivable error stream the dispatched settle-time
  throw fans out through, carrying the underlying ex-info as `:exception`)."
  [body-fn]
  (let [seen (atom [])
        k    ::err-recorder]
    (error-emit/register-error-listener! k (fn [rec] (swap! seen conj rec)))
    (try (body-fn) (finally (error-emit/unregister-error-listener! k)))
    @seen))

(defn- error-id-of
  "The `:rf.error/id` an error-emit record carries — either directly on the
  record's `:error` (a typed category record) or on the underlying
  `:exception`'s ex-data (a dispatched handler throw wrapping the boundary
  ex-info)."
  [rec]
  (or (some-> rec :exception ex-data :rf.error/id)
      (:error rec)))

(deftest descriptor-typo-concrete-scope-fails-closed-at-settle
  ;; A mutation :invalidates DESCRIPTOR carrying a typo'd CONCRETE reserved
  ;; scope (:rf.scope/glabal) is resolved at settle time through
  ;; resolve-descriptor-scope -> state/canonicalize-scope — the SAME
  ;; typo-rejecting path. It fails closed LOUD (no silent wrong-scope blast).
  (reg-article-resource!)
  (rf/reg-mutation :m/save
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     ;; the descriptor's CONCRETE :scope is a reserved-namespace typo
     :invalidates (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/glabal :tags #{[:article slug]}}])}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  ;; an ownerless GLOBAL article entry exists; a regression that dropped
  ;; canonicalization (resolving the typo to itself, or blasting global) would
  ;; be observable as this entry wrongly marked stale.
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/article :scope :rf.scope/global
                                          :params {:slug "w"} :owner [:v :a]}])
  (reply-success! @last-managed-args {:title "old"})
  (rf/dispatch-sync [:rf.resource/release-owner {:resource :r/article :scope :rf.scope/global
                                                 :params {:slug "w"} :owner [:v :a]}])
  (reset! last-managed-args nil)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :ty1}])
  (let [errs (record-surfaced-errors! #(reply-success! @last-managed-args {:title "new"}))]
    (testing "the typo'd descriptor scope failed closed at settle through the
              shared canonicalize-scope path — surfaced as
              :rf.error/resource-invalid-scope (never a silent wrong cache
              scope)"
      (is (some (fn [rec] (= :rf.error/resource-invalid-scope (error-id-of rec))) errs)
          ":rf.error/resource-invalid-scope was surfaced at the resolution boundary"))
    (testing "FAIL-CLOSED — no entry was blasted: the global article was NOT
              marked stale by the typo'd descriptor (the invalidation never
              dispatched)"
      (is (nil? (:invalidated-at (entry global-key)))))))

;; ---- rf2-kjpq3f: :cross-scope? true on an :invalidates DESCRIPTOR settles --
;; end-to-end (the scope-agnostic fan-out reaches entries in MULTIPLE scopes,
;; and the mutation-supplied [:mutation …] cause satisfies the cause-required
;; gate). The cross-scope cause-required REJECTION is covered on the raw
;; :rf.resource/invalidate-tags event (invalidation_gc/invalidate-tags-cross-
;; scope-*); the descriptor path was covered ONLY by the pure normalize
;; round-trip (normalize-lowers-the-public-forms), which proves the flag is
;; carried but does NOT settle a mutation, prove the fan-out, or pin the
;; [:mutation …] cause wiring. This is the end-to-end pin.

(deftest descriptor-cross-scope-fans-out-and-supplies-mutation-cause-at-settle
  ;; a FROM-CALLER article so the two entries can live under two concrete
  ;; (distinct) scopes; a :rf.scope/global-policy resource could not.
  (rf/reg-resource :rx/article
    {:scope :rf.scope/from-caller
     :params-schema [:map [:slug :string]]
     :tags (fn [{:keys [slug]} _] #{[:article slug]})}
    (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}}))
  ;; two same-tag article entries in DIFFERENT scopes, both ownerless +
  ;; stale-observable. A scoped (non-cross-scope) invalidation would reach at
  ;; most one; only the scope-agnostic fan-out reaches BOTH.
  (let [sa {:user "amy"}
        sb {:user "bo"}
        ka (state/scoped-resource-key sa :rx/article {:slug "w"})
        kb (state/scoped-resource-key sb :rx/article {:slug "w"})]
    (ownerless-stale-load! {:resource :rx/article :scope sa :params {:slug "w"} :owner [:v :a]})
    (ownerless-stale-load! {:resource :rx/article :scope sb :params {:slug "w"} :owner [:v :b]})
    (rf/reg-mutation :m/purge
      {:scope :rf.scope/global
       :params-schema [:map [:slug :string]]
       ;; a :cross-scope? true descriptor with NO explicit :cause — the
       ;; mutation runtime supplies the [:mutation <id> <instance>] cause by
       ;; construction, satisfying the rf2-7r8kgd cause-required gate.
       :invalidates (fn [{:keys [slug]} _result]
                      [{:tags #{[:article slug]} :cross-scope? true}])}
      (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
    (let [seen (atom [])
          k    ::succeeded-recorder]
      (trace-tooling/register-listener!
        k (fn [ev] (when (= :rf.mutation/succeeded (:operation ev)) (swap! seen conj ev))))
      (try
        (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/purge :params {:slug "w"} :instance :x1}])
        (reply-success! @last-managed-args {:purged true})
        (finally (trace-tooling/unregister-listener! k)))
      (testing "the scope-agnostic fan-out reached the same tag in BOTH scopes
                (a scoped descriptor would have reached at most one)"
        (is (some? (:invalidated-at (entry ka))) "amy's session entry marked stale")
        (is (some? (:invalidated-at (entry kb))) "bo's session entry marked stale"))
      (testing "the settlement :invalidation trace facet records the
                cross-scope dispatch as the audited escape (the :cross-scope?
                flag rides the dispatched descriptor evidence)"
        (let [inv (:invalidation (:tags (first @seen)))]
          (is (= 1 (:descriptor-count inv)))
          (is (= 1 (count (:dispatched inv))))
          (let [d (first (:dispatched inv))]
            (is (true? (:cross-scope? d)) "the descriptor is recorded cross-scope")
            (is (nil? (:scope d)) "a cross-scope dispatch is scope-agnostic (no concrete scope)")
            (is (= #{[:article "w"]} (set (:tags d)))))
          (is (empty? (:unresolved inv)) "no unresolved descriptor"))))))

;; ===========================================================================
;; 7. :rf.scope/same is the default when a descriptor omits :scope
;; ===========================================================================

(deftest descriptor-omitting-scope-defaults-to-same
  (reg-article-resource!)
  (rf/reg-mutation :m/save
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     ;; a descriptor with NO :scope -> :rf.scope/same -> the mutation's
     ;; resolved (:rf.scope/global) scope.
     :invalidates (fn [{:keys [slug]} _result] [{:tags #{[:article slug]}}])}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/article :scope :rf.scope/global
                                          :params {:slug "w"} :owner [:v :a]}])
  (reply-success! @last-managed-args {:title "old"})
  (rf/dispatch-sync [:rf.resource/release-owner {:resource :r/article :scope :rf.scope/global
                                                 :params {:slug "w"} :owner [:v :a]}])
  (reset! last-managed-args nil)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :sm1}])
  (reply-success! @last-managed-args {:title "new"})
  (testing "a scopeless descriptor invalidated the mutation's resolved scope"
    (is (some? (:invalidated-at (entry global-key))))))

;; ===========================================================================
;; 8. Pure normalization + fail-closed malformed result
;; ===========================================================================

(deftest normalize-lowers-the-public-forms
  (testing "a bare tag-set lowers to ONE :rf.scope/same descriptor"
    (let [[d & more] (mstate/normalize-invalidation-descriptors
                       #{[:article "w"] [:article-list]} 'test)]
      (is (nil? more))
      (is (= :rf.scope/same (:scope d)))
      (is (= #{[:article "w"] [:article-list]} (:tags d)))))
  (testing "a single descriptor map lowers to a one-element vector"
    (let [ds (mstate/normalize-invalidation-descriptors
               {:scope :rf.scope/global :tags #{[:x]}} 'test)]
      (is (= 1 (count ds)))
      (is (= :rf.scope/global (:scope (first ds))))))
  (testing "a vector of descriptors lowers each, defaulting omitted :scope to :rf.scope/same"
    (let [ds (mstate/normalize-invalidation-descriptors
               [{:scope :rf.scope/global :tags #{[:a]}}
                {:tags #{[:b]} :cross-scope? true}] 'test)]
      (is (= 2 (count ds)))
      (is (= :rf.scope/global (:scope (first ds))))
      (is (= :rf.scope/same (:scope (second ds))))
      (is (true? (:cross-scope? (second ds))))))
  (testing "a nil / empty result lowers to an empty vector (invalidates nothing)"
    (is (= [] (mstate/normalize-invalidation-descriptors nil 'test)))
    (is (= [] (mstate/normalize-invalidation-descriptors #{} 'test)))))

(deftest normalize-fails-closed-on-malformed
  (testing "a non-collection scalar result is a loud fail-closed error"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-invalidation"
          (mstate/normalize-invalidation-descriptors :nonsense 'test))))
  (testing "a descriptor missing :tags is a loud fail-closed error"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-invalidation"
          (mstate/normalize-invalidation-descriptors {:scope :rf.scope/global} 'test)))))

;; ===========================================================================
;; rf2-ru73k6 F1 — a LONE vector tag is ONE tag, not a scalar tag-set
;; ===========================================================================

(deftest lone-vector-tag-normalizes-to-one-tag
  ;; A naive `(set raw)` on a lone vector tag `[:article "w"]` would split it
  ;; into the scalar set `#{:article "w"}` — a tag-set that names nothing and
  ;; silently matches nothing. The shared `state/normalize-tag-set` (and the
  ;; mutation `:invalidates` bare shorthand that uses it) MUST treat a lone
  ;; vector tag as the ONE tag `#{[:article "w"]}`.
  (testing "the shared normalizer wraps a lone vector tag as one tag"
    (is (= #{[:article "w"]} (state/normalize-tag-set [:article "w"])))
    (is (= #{[:article-list]} (state/normalize-tag-set [:article-list]))
        "a single-element marker tag is still one tag, not #{:article-list}"))
  (testing "an existing tag-SET form lowers UNCHANGED (no regression)"
    (is (= #{[:article "w"]} (state/normalize-tag-set #{[:article "w"]})))
    (is (= #{[:article "w"]} (state/normalize-tag-set [[:article "w"]]))
        "a vector wrapping one tag is the set of that tag")
    (is (= #{[:article "w"] [:article-list]}
           (state/normalize-tag-set #{[:article "w"] [:article-list]}))))
  (testing "the mutation :invalidates bare shorthand lowers a lone vector tag
            to one :rf.scope/same descriptor carrying the one tag"
    (let [[d & more] (mstate/normalize-invalidation-descriptors [:article "w"] 'test)]
      (is (nil? more))
      (is (= :rf.scope/same (:scope d)))
      (is (= #{[:article "w"]} (:tags d))
          "the lone tag is the single tag, NOT #{:article \"w\"}"))))

(deftest lone-vector-tag-matches-the-right-resource-end-to-end
  ;; The adversarial end-to-end: a mutation whose :invalidates returns a LONE
  ;; vector tag `[:article slug]` must invalidate the entry carrying that tag.
  ;; Wrapping it with `(set raw)` would make it `#{:article "w"}`, matching
  ;; NOTHING — the ownerless article would stay fresh (a silent no-op the
  ;; author never sees). This pins the tag is treated as a single tag.
  (reg-article-resource!)
  (rf/reg-mutation :m/save
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     ;; a LONE vector tag written directly (not wrapped in a set) — the natural
     ;; single-tag shorthand the bead flags as silently failing.
     :invalidates (fn [{:keys [slug]} _result] [:article slug])}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/article :scope :rf.scope/global
                                          :params {:slug "w"} :owner [:v :a]}])
  (reply-success! @last-managed-args {:title "old"})
  (rf/dispatch-sync [:rf.resource/release-owner {:resource :r/article :scope :rf.scope/global
                                                 :params {:slug "w"} :owner [:v :a]}])
  (reset! last-managed-args nil)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :lv1}])
  (reply-success! @last-managed-args {:title "new"})
  (testing "the lone vector tag matched + invalidated the [:article \"w\"] entry"
    (is (some? (:invalidated-at (entry global-key)))
        "the article carrying tag [:article \"w\"] was marked stale")))

(deftest lone-vector-tag-direct-invalidate-tags-event-matches
  ;; The SAME tag semantics on the DIRECT :rf.resource/invalidate-tags event:
  ;; a lone vector `:tags [:article "w"]` invalidates the [:article "w"] entry
  ;; (events.cljc routes through the same shared state/normalize-tag-set).
  (reg-article-resource!)
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/article :scope :rf.scope/global
                                          :params {:slug "w"} :owner [:v :a]}])
  (reply-success! @last-managed-args {:title "old"})
  (rf/dispatch-sync [:rf.resource/release-owner {:resource :r/article :scope :rf.scope/global
                                                 :params {:slug "w"} :owner [:v :a]}])
  (reset! last-managed-args nil)
  ;; a lone vector tag on the public event :tags
  (rf/dispatch-sync [:rf.resource/invalidate-tags
                     {:scope :rf.scope/global :tags [:article "w"]
                      :cause [:manual :t/inv]}])
  (testing "the direct event's lone vector :tags matched the [:article \"w\"] entry"
    (is (some? (:invalidated-at (entry global-key)))
        "the article was marked stale by the lone-vector :tags")))

;; ===========================================================================
;; rf2-ypgayg — the DESCRIPTOR-MAP arm missed the shared lone-vector-tag fix
;; ===========================================================================

(deftest lone-vector-tag-descriptor-map-normalizes-to-one-tag
  ;; rf2-ru73k6 F1 fixed the bare tag-set shorthand and the direct
  ;; `:rf.resource/invalidate-tags` `:tags`, but `normalize-one-descriptor`
  ;; (the per-target DESCRIPTOR MAP arm) still lowered `:tags` through a naive
  ;; `(set tags)` — a lone vector tag `{:tags [:article "w"]}` split into the
  ;; scalar set `#{:article "w"}`, silently matching nothing. It must route
  ;; through the SAME `state/normalize-tag-set` normalizer.
  (testing "a single descriptor map with a lone vector :tags normalizes to one tag"
    (let [[d & more] (mstate/normalize-invalidation-descriptors
                       {:tags [:article "w"]} 'test)]
      (is (nil? more))
      (is (= :rf.scope/same (:scope d)))
      (is (= #{[:article "w"]} (:tags d))
          "the lone tag is the single tag, NOT #{:article \"w\"}")))
  (testing "a vector of descriptor maps also normalizes each lone vector :tags"
    (let [[d1 d2] (mstate/normalize-invalidation-descriptors
                    [{:scope :rf.scope/global :tags [:article "w"]}
                     {:tags [:article-list]}] 'test)]
      (is (= #{[:article "w"]} (:tags d1)))
      (is (= #{[:article-list]} (:tags d2))))))

(deftest lone-vector-tag-descriptor-map-matches-end-to-end
  ;; The adversarial end-to-end for the descriptor-map arm: a mutation whose
  ;; :invalidates returns a DESCRIPTOR MAP `{:tags [:article slug]}` — the
  ;; per-target form, not the bare shorthand — must still invalidate the
  ;; entry carrying that lone vector tag.
  (reg-article-resource!)
  (rf/reg-mutation :m/save-descriptor
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     ;; a descriptor MAP whose :tags is a LONE vector tag written directly
     ;; (not wrapped in a set) — the bug this bead fixes.
     :invalidates (fn [{:keys [slug]} _result] {:tags [:article slug]})}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/article :scope :rf.scope/global
                                          :params {:slug "w"} :owner [:v :a]}])
  (reply-success! @last-managed-args {:title "old"})
  (rf/dispatch-sync [:rf.resource/release-owner {:resource :r/article :scope :rf.scope/global
                                                 :params {:slug "w"} :owner [:v :a]}])
  (reset! last-managed-args nil)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save-descriptor :params {:slug "w"} :instance :lv2}])
  (reply-success! @last-managed-args {:title "new"})
  (testing "the descriptor-map lone vector tag matched + invalidated the [:article \"w\"] entry"
    (is (some? (:invalidated-at (entry global-key)))
        "the article carrying tag [:article \"w\"] was marked stale")))

;; ===========================================================================
;; rf2-fi6tda.4 finding 3 — pin the per-pass :rf.resource/invalidated EP fields
;; ===========================================================================

(deftest invalidated-trace-pins-ep0016-diagnostic-fields
  ;; rf2-fi6tda.4 finding 3: capture a REAL invalidation pass and assert the
  ;; EP-0016 diagnostic fields a behavior test would not pin — :matched,
  ;; :refetched, :left-stale, :exempt (the populate Rider-1 spare), and
  ;; :any-tag-match-other-scope?. Active (refetch), ownerless (left-stale),
  ;; populated-exempt, and same-tag-other-scope entries are all exercised in
  ;; one pass so each field carries a non-trivial value.
  (reg-article-resource!)
  (rf/reg-resource :r/article-list
    {:scope :rf.scope/global
     :params-schema [:map]
     :tags (fn [_p _] #{[:article-list]})}
    (fn [_p _] {:request {:method :get :url "/articles"}}))
  (rf/dispatch-sync [:t/login "jake"])
  ;; ACTIVE-owner list entry (global) → REFETCH on invalidation
  (own-loaded! {:resource :r/article-list :scope :rf.scope/global :params {} :owner [:v :list]})
  ;; OWNERLESS article detail (global) under tag [:article w] → left-stale,
  ;; BUT this same mutation POPULATES it → EXEMPT (Rider 1 spares it)
  (ownerless-stale-load! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :w]})
  ;; a SAME-TAG entry in ANOTHER scope ([:article-list] on jake's feed) so
  ;; :any-tag-match-other-scope? is observable — the global descriptor's
  ;; [:article-list] tag also lives on jake's session feed.
  (reg-feed-resource!) ;; r/feed carries #{[:feed] [:article-list]}
  (ownerless-stale-load! {:resource :r/feed :scope {:from-db :t/session} :params {} :owner [:v :feed]})
  (rf/reg-mutation :m/favorite
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     ;; populate the detail key authoritatively…
     :populates (fn [{:keys [slug]} result]
                  {{:resource :r/article :params {:slug slug} :scope :rf.scope/global} result})
     ;; …then a GLOBAL descriptor invalidating BOTH [:article w] (matches the
     ;; populated-exempt detail) AND [:article-list] (matches the active list
     ;; here AND jake's feed in ANOTHER scope).
     :invalidates (fn [{:keys [slug]} _r]
                    [{:scope :rf.scope/global :tags #{[:article slug] [:article-list]}}])}
    (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug "/fav")}}))
  (let [invs (record-invalidations!
               #(do (rf/dispatch-sync [:rf.mutation/execute
                                       {:mutation :m/favorite :params {:slug "w"} :instance :iv1}])
                    (reply-success! @last-managed-args {:slug "w" :favorited true})))
        ;; one pass (one descriptor) → one :rf.resource/invalidated row
        row (:tags (first invs))]
    (testing "exactly one invalidation pass row"
      (is (= 1 (count invs))))
    (testing ":matched names the global list key (NOT the exempt detail key)"
      (let [list-key (state/scoped-resource-key :rf.scope/global :r/article-list {})]
        (is (= [list-key] (:matched row)) "the populate-exempt detail is excluded from :matched")))
    (testing ":refetched / :left-stale reflect the active-owner decision"
      (is (= 1 (:refetched row)) "the active-owner list refetched")
      (is (= 0 (:left-stale row)) "no ownerless matched key (the detail was exempt)"))
    (testing ":exempt names the populated detail key the Rider-1 spare kept fresh"
      (is (= [global-key] (:exempt row))))
    (testing ":any-tag-match-other-scope? is true ([:article-list] lives on
              jake's session feed too — distinguishes 'no match HERE' from
              'no resource provides this tag in any scope')"
      (is (true? (:any-tag-match-other-scope? row))))))
