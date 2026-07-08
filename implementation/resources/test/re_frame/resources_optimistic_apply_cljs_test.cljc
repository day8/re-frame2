(ns re-frame.resources-optimistic-apply-cljs-test
  "EP-0019 slice 2 — optimistic APPLY (phase 1.5) + snapshot-inverse recording
  + the `:optimistic` / `:optimistic-tags` API grammar (rf2-byl7bk.1).

  This slice applies the FORWARD optimistic patch to the resource cache BEFORE
  the request settles, recording the truthful INVERSE (a snapshot of each
  touched entry + its `:revision` at apply time) on the mutation instance row's
  reserved `:patch-summary` `:snapshot-id` / `:rollback` slots. The SETTLE
  protocol (commit / rollback / reconcile + the conflict rule) is the NEXT
  slice; these tests pin only the apply + recording contract:

    1. APPLY-BEFORE-SETTLE — the optimistic patch lands in the cache at execute
       time, before any reply (phase 1.5); the entry's `:revision` bumps.
    2. SNAPSHOT INVERSE — the instance row records `:before` (the whole entry as
       it stood, structural-shared) + the `:revision` observed at apply time.
    3. ABSENT-SEED — an optimistic patch over an ABSENT key seeds it `:loaded`
       and records the `:absent` sentinel inverse (so the settle can remove it).
    4. OPTIMISTIC REMOVE — a `nil` patch-fn vanishes the entry and records the
       full `:before` (so the settle can restore it).
    5. TAG-ADDRESSED — `:optimistic-tags` patches EVERY tag-matched entry across
       the resolved scope (the cross-view-consistency demand); each records its
       own inverse.
    6. FAIL-CLOSED — a `{:from-db …}` optimistic target that resolves nil is
       DROPPED (never written under an implicit global), recorded as
       `:target-unresolved`.
    7. OPT-OUT — `{:optimistic? false}` on the execute payload forces the
       pessimistic path (no optimistic apply) for one call.
    8. BEFORE-REQUEST INCOMPATIBILITY — `:optimistic` + `:invalidate-timing
       :before-request` is a loud registration error
       (`:rf.error/mutation-optimistic-before-request`).
    9. TRACE — `:rf.mutation/optimistic-applied` carries the snapshot id, the
       affected keys, and the per-key revision + forward op shape.

  The transport is a capturing stub: the optimistic apply runs at execute time,
  so most assertions read the cache immediately AFTER dispatching `:execute`,
  before any reply is synthesised."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load-bearing side-effecting requires: register the :rf.resource/* +
   ;; :rf.mutation/* events + subs + the generation cofx/fx.
   [re-frame.resources]
   [re-frame.resources.mutation-registry :as mreg]
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
  (rf/reg-resource-scope :t/session
    {:inputs {:username [:db [:auth :user :username]]}}
    (fn [{:keys [username]} _ctx]
      (when username [:rf.scope/session {:username username}])))
  (rf/reg-event :t/login (fn [{:keys [db]} [_ username]]
                           {:db (assoc-in db [:auth :user :username] username)})))

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
;; rf2-8iciw8 — `:rf.runtime/mutations` is keyed on the instance id's CEDN-1
;; byte `key-id` (`state/key-id`), not the raw id; resolve through it.
(defn- instance [instance-id] (get-in (runtime-db) [:rf.runtime/mutations (state/key-id instance-id)]))
(defn- patch-summary [instance-id] (:patch-summary (instance instance-id)))

(defn- reply-success! [args result]
  (rf/dispatch-sync (conj (:on-success args) {:status :ok :value result})))

(def ^:private article-key
  (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"}))

(defn- session-feed-key [u]
  (state/scoped-resource-key [:rf.scope/session {:username u}] :r/feed {}))

(defn- reg-article-resource! []
  (rf/reg-resource :r/article
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :tags (fn [{:keys [slug]} _] #{[:article slug] [:article-list]})}
    (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})))

(defn- own-loaded! [payload value]
  (rf/dispatch-sync [:rf.resource/ensure payload])
  (reply-success! @last-managed-args value)
  (reset! last-managed-args nil))

(defn- applied-trace
  "Run `body-fn`; return the LAST `:rf.mutation/optimistic-applied` trace
  event's top-level data map (facets ride under `:tags`)."
  [body-fn]
  (let [seen (atom [])
        k    ::applied-recorder]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= :rf.mutation/optimistic-applied (:operation ev)) (swap! seen conj ev))))
    (try (body-fn) (finally (trace-tooling/unregister-listener! k)))
    (:tags (last @seen))))

;; ===========================================================================
;; 1 + 2. Optimistic apply lands in the cache at execute time (phase 1.5),
;;        BEFORE any reply, and records the snapshot inverse.
;; ===========================================================================

(deftest optimistic-apply-patches-before-the-request-settles
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false :favoritesCount 9}})
  (let [rev-before (:revision (entry article-key))]
    (rf/reg-mutation :m/favorite
      {:scope :rf.scope/global
       :params-schema [:map [:slug :string]]
       :optimistic (fn [{:keys [slug]}]
                     {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                      (fn [a] (-> a
                                  (assoc-in [:article :favorited] true)
                                  (update-in [:article :favoritesCount] inc)))})}
      (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug "/fav")}}))
    ;; dispatch EXECUTE but DO NOT reply yet — the optimistic value must already
    ;; be in the cache.
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
    (testing "the optimistic forward patch is applied to the cache BEFORE the reply"
      (let [e (entry article-key)]
        (is (= true (get-in e [:data :article :favorited])) "heart flipped optimistically")
        (is (= 10 (get-in e [:data :article :favoritesCount])) "count incremented optimistically")
        (is (= :loaded (:status e)))))
    (testing "the optimistic apply bumped the entry's :revision (an authoritative
              durable write the rollback could clobber)"
      (is (= (inc rev-before) (:revision (entry article-key)))))
    (testing "the instance row records the snapshot inverse on :patch-summary"
      (let [ps (patch-summary :f1)
            [inv] (:rollback ps)]
        (is (some? (:snapshot-id ps)) ":snapshot-id is filled (was nil)")
        (is (= article-key (:resource/key inv)))
        (is (= rev-before (:revision inv))
            "the recorded revision is the one observed BEFORE the apply bump")
        (is (= :patch (:forward inv)))
        (testing "the recorded :before is the WHOLE entry as it stood (truthful inverse)"
          (is (= false (get-in (:before inv) [:data :article :favorited])))
          (is (= 9 (get-in (:before inv) [:data :article :favoritesCount]))))))
    (testing "the pending mutation reply has NOT yet been delivered (apply is not a reply)"
      (is (= :pending (:status (instance :f1)))))))

;; ===========================================================================
;; 3. Absent-seed — an optimistic patch over an ABSENT key seeds it and records
;;    the :absent sentinel inverse.
;; ===========================================================================

(deftest optimistic-apply-seeds-absent-key-with-absent-inverse
  (reg-article-resource!)
  (rf/reg-mutation :m/create
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     ;; optimistic SEED of a not-yet-cached article (patch-fn over nil).
     :optimistic (fn [{:keys [slug]}]
                   {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                    (fn [_absent] {:article {:slug slug :favorited false}})})}
    (fn [{:keys [slug]} _] {:request {:method :post :url "/a"}}))
  (is (nil? (entry article-key)) "no entry before the apply")
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/create :params {:slug "w"} :instance :c1}])
  (testing "the absent key is SEEDED :loaded with the optimistic value"
    (let [e (entry article-key)]
      (is (= :loaded (:status e)))
      (is (= {:article {:slug "w" :favorited false}} (:data e)))
      (is (= 1 (:revision e)) "a freshly-seeded optimistic entry bumps 0 -> 1")
      (is (= #{[:article "w"] [:article-list]} (:tags e))
          "a seeded entry carries its resource's tags (so invalidation can reach it)")))
  (testing "the recorded inverse is the :absent sentinel + :seed forward op"
    (let [[inv] (:rollback (patch-summary :c1))]
      (is (= :rf.optimistic/absent (:before inv)) "rollback removes the seeded entry")
      (is (= 0 (:revision inv)) "an absent key's recorded revision is 0")
      (is (= :seed (:forward inv))))))

;; ===========================================================================
;; 4. Optimistic REMOVE — a nil patch-fn vanishes the entry, recording the
;;    full :before so the settle can restore it.
;; ===========================================================================

(deftest optimistic-remove-vanishes-entry-and-records-before
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:slug "w" :title "Doomed"}})
  (rf/reg-mutation :m/delete
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     ;; a nil patch-fn is an optimistic REMOVE (Open Issue 6).
     :optimistic (fn [{:keys [slug]}]
                   {{:resource :r/article :params {:slug slug} :scope :rf.scope/global} nil})}
    (fn [{:keys [slug]} _] {:request {:method :delete :url (str "/a/" slug)}}))
  (is (some? (entry article-key)))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/delete :params {:slug "w"} :instance :d1}])
  (testing "the entry is optimistically REMOVED from the cache"
    (is (nil? (entry article-key))))
  (testing "the recorded inverse carries the full :before entry + :remove op"
    (let [[inv] (:rollback (patch-summary :d1))]
      (is (= :remove (:forward inv)))
      (is (= {:article {:slug "w" :title "Doomed"}} (:data (:before inv)))
          "the whole removed entry is snapshotted so the settle can restore it"))))

;; ===========================================================================
;; 5. Tag-addressed optimistic — :optimistic-tags patches EVERY tag-matched
;;    entry; each records its own inverse.
;; ===========================================================================

(deftest optimistic-tags-patches-every-tag-matched-entry
  (reg-article-resource!)
  (rf/reg-resource :r/article-list
    {:scope :rf.scope/global
     :params-schema [:map]
     :tags (fn [_p _] #{[:article "w"] [:article-list]})}
    (fn [_p _] {:request {:method :get :url "/articles"}}))
  ;; two entries both carrying [:article "w"] — a detail and a list.
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false}})
  (own-loaded! {:resource :r/article-list :scope :rf.scope/global :params {} :owner [:v :l]}
               {:list [{:favorited false}]})
  (rf/reg-mutation :m/favorite-everywhere
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     ;; patch EVERY cached entry carrying [:article slug] in global scope.
     :optimistic-tags (fn [{:keys [slug]}]
                        [{:scope :rf.scope/global
                          :tags  #{[:article slug]}
                          :patch (fn [d] (assoc d :touched true))}])}
    (fn [{:keys [slug]} _] {:request {:method :post :url "/fav"}}))
  (let [list-key (state/scoped-resource-key :rf.scope/global :r/article-list {})]
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite-everywhere
                                             :params {:slug "w"} :instance :fe1}])
    (testing "BOTH tag-matched entries were optimistically patched"
      (is (= true (get-in (entry article-key) [:data :touched])) "detail patched")
      (is (= true (get-in (entry list-key) [:data :touched])) "list patched"))
    (testing "each matched key recorded its own snapshot inverse"
      (let [ps (patch-summary :fe1)
            keys-recorded (set (map :resource/key (:rollback ps)))]
        (is (= #{article-key list-key} keys-recorded))
        (is (= 2 (count (:rollback ps))))))))

;; ===========================================================================
;; 6. Fail-closed — a {:from-db …} optimistic target that resolves nil is
;;    DROPPED (never written under an implicit global).
;; ===========================================================================

(deftest optimistic-from-db-target-resolving-nil-is-fail-closed
  (rf/reg-resource :r/feed
    {:scope {:from-db :t/session}
     :params-schema [:map]
     :tags (fn [_p _] #{[:feed]})}
    (fn [_p _] {:request {:method :get :url "/feed"}}))
  (rf/reg-mutation :m/touch-feed
    {:scope :rf.scope/global
     :params-schema [:map]
     :optimistic (fn [_p]
                   {{:resource :r/feed :params {} :scope {:from-db :t/session}}
                    (fn [d] (assoc d :touched true))})}
    (fn [_p _] {:request {:method :post :url "/feed/touch"}}))
  ;; NO :t/login — the {:from-db :t/session} resolver returns nil.
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/touch-feed :params {} :instance :tf1}])
  (testing "no session entry was written (fail-closed drop, never an implicit global)"
    (is (nil? (entry (session-feed-key "jake"))))
    (is (nil? (entry (state/scoped-resource-key :rf.scope/global :r/feed {})))))
  (testing "the dropped target is recorded as :target-unresolved; no inverse"
    (let [ps (patch-summary :tf1)]
      (is (= [:t/session] (:target-unresolved ps)))
      (is (empty? (:rollback ps))))))

;; ===========================================================================
;; 7. Per-call opt-out — {:optimistic? false} forces the pessimistic path.
;; ===========================================================================

(deftest optimistic-false-opts-out-of-the-apply
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false}})
  (let [rev-before (:revision (entry article-key))]
    (rf/reg-mutation :m/favorite
      {:scope :rf.scope/global
       :params-schema [:map [:slug :string]]
       :optimistic (fn [{:keys [slug]}]
                     {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                      (fn [a] (assoc-in a [:article :favorited] true))})}
      (fn [{:keys [slug]} _] {:request {:method :post :url "/fav"}}))
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"}
                                             :instance :f1 :optimistic? false}])
    (testing "the optimistic patch was NOT applied (the value is unchanged + no bump)"
      (let [e (entry article-key)]
        (is (= false (get-in e [:data :article :favorited])) "no optimistic flip")
        (is (= rev-before (:revision e)) "no revision bump — no optimistic write")))
    (testing "no snapshot inverse was recorded"
      (is (nil? (:snapshot-id (patch-summary :f1)))))))

;; ===========================================================================
;; 8. :optimistic + :before-request timing is a loud registration error.
;; ===========================================================================

(deftest optimistic-with-before-request-is-a-registration-error
  (testing ":optimistic + :invalidate-timing :before-request throws
            :rf.error/mutation-optimistic-before-request at registration"
    (let [ex (try
               (rf/reg-mutation :m/bad
                 {:scope :rf.scope/global
                  :params-schema [:map [:slug :string]]
                  :invalidate-timing :before-request
                  :optimistic (fn [_p] {})}
                 (fn [_p _] {:request {:method :post :url "/x"}}))
               nil
               (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? ex) "registration threw")
      (is (= :rf.error/mutation-optimistic-before-request
             (:rf.error/id (ex-data ex))))
      (is (nil? (mreg/mutation-meta :m/bad)) "the bad mutation was NOT registered")))
  (testing ":optimistic-tags + :before-request also throws"
    (let [ex (try
               (rf/reg-mutation :m/bad2
                 {:scope :rf.scope/global
                  :params-schema [:map]
                  :invalidate-timing :before-request
                  :optimistic-tags (fn [_p] [])}
                 (fn [_p _] {:request {:method :post :url "/x"}}))
               nil
               (catch #?(:clj Exception :cljs :default) e e))]
      (is (= :rf.error/mutation-optimistic-before-request
             (:rf.error/id (ex-data ex))))))
  (testing "an optimistic mutation with the DEFAULT timing registers fine"
    (is (= :m/ok
           (rf/reg-mutation :m/ok
             {:scope :rf.scope/global
              :params-schema [:map [:slug :string]]
              :optimistic (fn [_p] {})}
             (fn [_p _] {:request {:method :post :url "/x"}}))))))

;; ===========================================================================
;; 9. Trace — :rf.mutation/optimistic-applied carries the snapshot id + revisions.
;; ===========================================================================

(deftest optimistic-applied-trace-carries-snapshot-and-revisions
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false}})
  (let [rev-before (:revision (entry article-key))]
    (rf/reg-mutation :m/favorite
      {:scope :rf.scope/global
       :params-schema [:map [:slug :string]]
       :optimistic (fn [{:keys [slug]}]
                     {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                      (fn [a] (assoc-in a [:article :favorited] true))})}
      (fn [{:keys [slug]} _] {:request {:method :post :url "/fav"}}))
    (let [tr (applied-trace
               #(rf/dispatch-sync [:rf.mutation/execute
                                   {:mutation :m/favorite :params {:slug "w"} :instance :f1}]))]
      (testing "the optimistic-applied trace carries the snapshot id + affected keys"
        (is (some? (:snapshot-id tr)))
        (is (= [article-key] (:affected-keys tr))))
      (testing "the trace carries the per-key revision (observed at apply) + forward op"
        (is (= [{:resource/key article-key :revision rev-before :forward :patch}]
               (:revisions tr)))))))
