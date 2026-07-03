(ns re-frame.resources-mutation-scope-mismatch-cljs-test
  "Write-side mutation-scope-mismatch diagnostic (rf2-byl7bk.4 — Spec 016
   §Mutation scope is two distinct scopes / §Dev-mode write-side tripwire).

  Mandatory resource scope is a strength, but mutation invalidation becomes an
  ergonomic FOOTGUN if a global-default mutation's `:invalidates` consequence
  quietly misses scoped resources: a `:rf.scope/global`-resolved invalidation
  matches NO entry in the global scope while the affected resource's entries
  live under a session / tenant scope — the cached read is never refreshed and
  NO error is raised (a scoped invalidation matching nothing in its own scope is
  a legitimate 'no match here').

  The framework surfaces this at DEV time as `:rf.warning/mutation-scope-mismatch`
  — the WRITE-side complement of the read-side `:rf.warning/resource-sub-scope-
  mismatch`. These JVM+CLJS unit tests pin the diagnostic's behaviour:

    1. a scoped-invalidation HIT (the mutation scope MATCHES the resource scope)
       does NOT warn — the happy path is quiet;
    2. the FOOTGUN — a global-default mutation invalidating a tag whose only
       cache entry lives in a session scope — WARNS, carrying the descriptor
       scope, the mutation scope, the other-scope that DID hold the entry, and
       the tags;
    3. a tag with NO cache entry in ANY scope (a true nothing-to-invalidate)
       does NOT warn (no mismatch — it is not a footgun, just nothing to do);
    4. a `:cross-scope? true` descriptor (the audited deliberate escape) does
       NOT warn even when it spans scopes;
    5. the diagnostic is one-shot dedupe-keyed (a re-executed mutation warns
       once per genuine mismatch, never floods);
    6. the per-target descriptor form (the safe pattern) does NOT warn — it
       reaches the session entry in its own scope.

  The transport is exercised via the same capturing-stub idiom the descriptor
  tests use (synthesise the transport's reply-event-append shape)."
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
  ;; a named db-derived viewer-session resolver (EP-0016 D3 canonical form)
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

(defn- reply-success! [args result]
  (rf/dispatch-sync (conj (:on-success args) {:status :ok :value result})))

(defn- session-feed-key [u] (state/scoped-resource-key [:rf.scope/session {:username u}] :r/feed {}))

(defn- reg-feed-resource!
  "A SESSION-scoped resource (`{:from-db :t/session}`) producing the `[:feed]`
  and `[:article-list]` tags — the scoped resource a global-default mutation
  silently misses."
  []
  (rf/reg-resource :r/feed
    {:scope {:from-db :t/session}
     :params-schema [:map]
     :tags (fn [_p _] #{[:feed] [:article-list]})}
    (fn [_p _] {:request {:method :get :url "/feed"}})))

(defn- reg-global-article-resource! []
  (rf/reg-resource :r/article
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :tags (fn [{:keys [slug]} _] #{[:article slug] [:article-list]})}
    (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})))

(defn- seed-ownerless-session-feed!
  "Drive jake's SESSION feed entry to :loaded then release its owner, so it is
  a stale-observable ownerless entry living under `[:rf.scope/session {…jake}]`
  (NOT the global scope a global-default mutation invalidates in)."
  []
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/feed :scope {:from-db :t/session}
                                          :params {} :owner [:v :feed]}])
  (reply-success! @last-managed-args {:seed true})
  (rf/dispatch-sync [:rf.resource/release-owner {:resource :r/feed :scope {:from-db :t/session}
                                                 :params {} :owner [:v :feed]}])
  (reset! last-managed-args nil))

(defn- record-warnings!
  "Run `body-fn`; return the vector of every `:rf.warning/mutation-scope-mismatch`
  trace event emitted during it."
  [body-fn]
  (let [seen (atom [])
        k    ::warn-recorder]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= :rf.warning/mutation-scope-mismatch (:operation ev))
                   (swap! seen conj ev))))
    (try (body-fn) (finally (trace-tooling/unregister-listener! k)))
    @seen))

;; ===========================================================================
;; 1. A scoped-invalidation HIT does NOT warn (the happy path is quiet)
;; ===========================================================================

(deftest scoped-invalidation-hit-does-not-warn
  (reg-feed-resource!)
  (rf/dispatch-sync [:t/login "jake"])
  (seed-ownerless-session-feed!)
  ;; a mutation that CORRECTLY targets the session scope (the safe pattern):
  ;; a per-target descriptor naming `{:from-db :t/session}`.
  (rf/reg-mutation :m/post-to-feed
    {:params-schema [:map]
     :invalidates (fn [_p _result]
                    [{:scope {:from-db :t/session} :tags #{[:feed]}}])}
    (fn [_p _] {:request {:method :post :url "/feed"}}))
  (let [warnings (record-warnings!
                   (fn []
                     (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/post-to-feed
                                                              :params {} :instance :h1}])
                     (reply-success! @last-managed-args {:ok true})))]
    (testing "the session feed entry was invalidated (the descriptor HIT)"
      (is (some? (:invalidated-at (entry (session-feed-key "jake"))))))
    (testing "no mutation-scope-mismatch warning fired — the scope matched"
      (is (empty? warnings)))))

;; ===========================================================================
;; 2. The FOOTGUN — a global-default mutation misses a session-scoped resource
;; ===========================================================================

(deftest global-default-mutation-misses-session-scoped-resource-warns
  (reg-feed-resource!)
  (rf/dispatch-sync [:t/login "jake"])
  (seed-ownerless-session-feed!)
  ;; the FOOTGUN mutation: NO :scope declared (execution scope fail-opens to
  ;; :rf.scope/global) and a BARE tag-set :invalidates (inherits :rf.scope/same
  ;; = the resolved global scope). The [:feed] tag's only cache entry lives in
  ;; jake's SESSION scope, so the global invalidation matches NOTHING.
  (rf/reg-mutation :m/post
    {:params-schema [:map]
     :invalidates (fn [_p _result] #{[:feed]})}
    (fn [_p _] {:request {:method :post :url "/feed"}}))
  (let [warnings (record-warnings!
                   (fn []
                     (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/post
                                                              :params {} :instance :m1}])
                     (reply-success! @last-managed-args {:ok true})))]
    (testing "the session feed was NOT invalidated (the global invalidation
              silently missed — the cached read is never refreshed)"
      (is (nil? (:invalidated-at (entry (session-feed-key "jake"))))))
    (testing "the write-side scope-mismatch warning fired exactly once"
      (is (= 1 (count warnings))))
    (testing "the warning carries the diagnostic facts naming the footgun"
      ;; the trace event's payload map rides under the event's `:tags` field
      ;; (the trace-bus envelope shape — same as the descriptor tests read).
      (let [w   (first warnings)
            pay (:tags w)]
        (is (= :rf.warning/mutation-scope-mismatch (:operation w)))
        (is (= :m/post (:mutation pay)))
        (is (= :m1 (:instance pay)))
        (is (= :rf.scope/global (:descriptor-scope pay)))
        (is (= :rf.scope/global (:mutation-scope pay)))
        (is (= [:rf.scope/session {:username "jake"}] (:other-scope pay)))
        (is (= [[:feed]] (:tags pay)))
        ;; `:recovery` + `:hint` are promoted onto the trace event envelope
        ;; (the trace tooling lifts them out of the payload).
        (is (= :fix-scope (or (:recovery pay) (:recovery w))))
        (is (string? (or (:hint pay) (:hint w))))))))

;; ===========================================================================
;; 3. A tag with NO entry in ANY scope does NOT warn (nothing to invalidate)
;; ===========================================================================

(deftest tag-with-no-entry-anywhere-does-not-warn
  (reg-feed-resource!)
  (rf/dispatch-sync [:t/login "jake"])
  ;; NO entries seeded at all. A global-default mutation invalidates [:feed] —
  ;; it matches nothing in the global scope AND nothing in any other scope, so
  ;; this is a true nothing-to-invalidate, NOT a mismatch footgun.
  (rf/reg-mutation :m/post
    {:params-schema [:map]
     :invalidates (fn [_p _result] #{[:feed]})}
    (fn [_p _] {:request {:method :post :url "/feed"}}))
  (let [warnings (record-warnings!
                   (fn []
                     (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/post
                                                              :params {} :instance :n1}])
                     (reply-success! @last-managed-args {:ok true})))]
    (testing "no warning — there is no other-scope entry to mismatch against"
      (is (empty? warnings)))))

;; ===========================================================================
;; 4. A :cross-scope? true descriptor (the audited escape) does NOT warn
;; ===========================================================================

(deftest cross-scope-descriptor-does-not-warn
  (reg-feed-resource!)
  (rf/dispatch-sync [:t/login "jake"])
  (seed-ownerless-session-feed!)
  ;; a deliberate cross-scope sweep — it ignores the scope filter by
  ;; construction, so 'no match in this scope' is impossible / intentional.
  (rf/reg-mutation :m/post
    {:params-schema [:map]
     :invalidates (fn [_p _result] [{:cross-scope? true :tags #{[:feed]}}])}
    (fn [_p _] {:request {:method :post :url "/feed"}}))
  (let [warnings (record-warnings!
                   (fn []
                     (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/post
                                                              :params {} :instance :c1}])
                     (reply-success! @last-managed-args {:ok true})))]
    (testing "the cross-scope sweep DID reach jake's session feed"
      (is (some? (:invalidated-at (entry (session-feed-key "jake"))))))
    (testing "no scope-mismatch warning — cross-scope is the audited escape"
      (is (empty? warnings)))))

;; ===========================================================================
;; 5. The diagnostic is one-shot dedupe-keyed (a re-executed mutation warns once)
;; ===========================================================================

(deftest warning-is-one-shot-dedupe-keyed
  (reg-feed-resource!)
  (rf/dispatch-sync [:t/login "jake"])
  (seed-ownerless-session-feed!)
  (rf/reg-mutation :m/post
    {:params-schema [:map]
     :invalidates (fn [_p _result] #{[:feed]})}
    (fn [_p _] {:request {:method :post :url "/feed"}}))
  (let [warnings (record-warnings!
                   (fn []
                     ;; execute the SAME footgun mutation three times — each is a
                     ;; settled global-scope invalidation that misses the session
                     ;; feed. The dedupe-key is identical, so only the FIRST warns.
                     (doseq [n [:a1 :a2 :a3]]
                       (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/post
                                                                :params {} :instance n}])
                       (reply-success! @last-managed-args {:ok true}))))]
    (testing "three identical-mismatch executions produced exactly ONE warning"
      (is (= 1 (count warnings))))))

;; ===========================================================================
;; 6. The per-target descriptor SAFE PATTERN does not warn (it reaches both)
;; ===========================================================================

(deftest per-target-descriptor-safe-pattern-does-not-warn
  ;; the safe pattern from the guide: a global-default mutation invalidates a
  ;; GLOBAL fact AND a SESSION fact, each via its own per-target descriptor —
  ;; both reach a real entry in their own scope, so neither mismatches.
  (reg-global-article-resource!)
  (reg-feed-resource!)
  (rf/dispatch-sync [:t/login "jake"])
  ;; an ownerless global article + an ownerless session feed, both observable
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/article :scope :rf.scope/global
                                          :params {:slug "w"} :owner [:v :a]}])
  (reply-success! @last-managed-args {:title "old"})
  (rf/dispatch-sync [:rf.resource/release-owner {:resource :r/article :scope :rf.scope/global
                                                 :params {:slug "w"} :owner [:v :a]}])
  (seed-ownerless-session-feed!)
  (rf/reg-mutation :m/favorite
    {:params-schema [:map [:slug :string]]
     :invalidates (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global :tags #{[:article slug]}}
                     {:scope {:from-db :t/session} :tags #{[:feed]}}])}
    (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug "/fav")}}))
  (let [warnings (record-warnings!
                   (fn []
                     (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite
                                                              :params {:slug "w"} :instance :s1}])
                     (reply-success! @last-managed-args {:favorited true})))]
    (testing "both targets HIT their own-scope entries"
      (is (some? (:invalidated-at (entry (state/scoped-resource-key
                                           :rf.scope/global :r/article {:slug "w"})))))
      (is (some? (:invalidated-at (entry (session-feed-key "jake"))))))
    (testing "no scope-mismatch warning — the safe pattern matched every scope"
      (is (empty? warnings)))))
