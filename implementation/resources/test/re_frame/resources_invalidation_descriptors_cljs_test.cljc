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
   [re-frame.http-managed]
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
  ;; an app event that writes / removes the logged-in user (the resolver input)
  (rf/reg-event-db :t/login (fn [db [_ username]] (assoc-in db [:auth :user :username] username)))
  (rf/reg-event-db :t/logout (fn [db _] (update db :auth dissoc :user))))

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
(defn- entries [] (get-in (runtime-db) (state/entries-path)))

(defn- reply-success!
  ([args result] (rf/dispatch-sync (conj (:on-success args) {:kind :success :value result})))
  ([args result opts] (rf/dispatch-sync (conj (:on-success args) {:kind :success :value result}) opts)))

(def ^:private global-key (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"}))
(defn- session-feed-key [u] (state/scoped-resource-key [:rf.scope/session {:username u}] :r/feed {}))

(defn- reg-article-resource! []
  (rf/reg-resource :r/article
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :request (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})
     :tags (fn [{:keys [slug]} _] #{[:article slug] [:article-list]})}))

(defn- reg-feed-resource! []
  (rf/reg-resource :r/feed
    {:scope {:from-db :t/session}
     :params-schema [:map]
     :request (fn [_p _] {:request {:method :get :url "/feed"}})
     :tags (fn [_p _] #{[:feed] [:article-list]})}))

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
     :request (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
     :invalidates (fn [{:keys [slug]} _result] #{[:article slug]})})
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
     :request (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug "/fav")}})
     :invalidates (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global :tags #{[:article slug]}}
                     {:scope {:from-db :t/session} :tags #{[:feed]}}])})
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
     :request (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
     :invalidates (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global :tags #{[:article slug]}}
                     {:scope {:from-db :t/session} :tags #{[:feed]}}])})
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
    (rf/reg-event-fx :test/saved (fn [_ event] (swap! replied conj event) {}))
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
       :request (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
       :invalidates (fn [_p _r] [{:scope {:from-db :t/session} :tags #{[:feed]}}])})
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
     :request (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
     :invalidates (fn [{:keys [slug]} _result] #{[:article slug]})})
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
     :request (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
     :invalidates (fn [_p _r] [{:scope {:from-db :t/session} :tags #{[:feed]}}])})
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
     :request (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
     :invalidates (fn [_p _r] [{:scope {:from-db :t/session} :tags #{[:article-list]}}])})
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
     :request (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug "/fav")}})
     :invalidates (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global :tags #{[:article slug]}}
                     {:scope {:from-db :t/session} :tags #{[:feed]}}])})
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

;; ===========================================================================
;; 7. :rf.scope/same is the default when a descriptor omits :scope
;; ===========================================================================

(deftest descriptor-omitting-scope-defaults-to-same
  (reg-article-resource!)
  (rf/reg-mutation :m/save
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :request (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
     ;; a descriptor with NO :scope -> :rf.scope/same -> the mutation's
     ;; resolved (:rf.scope/global) scope.
     :invalidates (fn [{:keys [slug]} _result] [{:tags #{[:article slug]}}])})
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
