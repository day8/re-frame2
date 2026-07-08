(ns re-frame.resources-optimistic-settle-cljs-test
  "EP-0019 slice 3 — the optimistic SETTLE protocol (commit / rollback /
  reconcile + the `:on-conflict` conflict rule), rf2-byl7bk.1.

  Slice 2 applied the FORWARD optimistic patch at phase 1.5 and recorded the
  truthful snapshot-inverse on the instance row. This slice consumes that
  recorded inverse + slice-1's `revision-conflict?` to DETERMINISTICALLY dispose
  each optimistic apply when its mutation reply settles:

    1. SUCCESS-COMMIT — an accepted `:ok` reply settles the optimistic value
       authoritatively (`:populates` / `:patches` overwrite it); the recorded
       inverse is discarded, the reserved `:patch-summary` slots fill, and
       `:rf.mutation/optimistic-reconciled` fires.
    2. FAILURE-ROLLBACK (no conflict) — an accepted `:error` reply restores the
       recorded `:before` entry verbatim (the truthful, conflict-free rollback);
       `:rf.mutation/optimistic-rolled-back` fires with `:restored`.
    3. CONFLICT → INVALIDATE (default) — when a competing authoritative write
       moved the entry's `:revision` since the apply, the failure does NOT
       restore the stale inverse; it marks the entry stale + refetches the
       authoritative value (`:on-conflict :invalidate`).
    4. CONFLICT → FORCE — `:on-conflict :force` restores the (stale) inverse even
       on conflict (single-writer last-write-wins) + emits the clobber warning.
    5. RESTORE-DANGLE-INSIDE-RECONCILER (Q3 GUARD) — a `:pending` optimistic
       write dangles on epoch restore and rolls back INSIDE the restore
       reconciler's single pure pass (NOT a racing post-restore event).

  The transport is a capturing stub; a reply is synthesised by dispatching the
  captured `:on-success` / `:on-failure` internal reply event."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load-bearing side-effecting requires: register the :rf.resource/* +
   ;; :rf.mutation/* events + subs + the generation cofx/fx.
   [re-frame.resources]
   [re-frame.resources.mutation-runtime :as mstate]
   [re-frame.resources.ssr :as ssr]
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

(defn- reply-failure! [args failure]
  (rf/dispatch-sync (conj (:on-failure args) {:status :error :error failure})))

(def ^:private article-key
  (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"}))

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

(def ^:private favorite-plan
  {:scope :rf.scope/global
   :params-schema [:map [:slug :string]]
   :optimistic (fn [{:keys [slug]}]
                 {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                  (fn [a] (-> a
                              (assoc-in [:article :favorited] true)
                              (update-in [:article :favoritesCount] inc)))})})

(def ^:private favorite-plan-request
  (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug "/fav")}}))

(defn- trace-of
  "Run `body-fn`; return the LAST trace event with `op` (its top-level data map;
  facets ride under `:tags`), or nil."
  [op body-fn]
  (let [seen (atom [])
        k    ::recorder]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= op (:operation ev)) (swap! seen conj ev))))
    (try (body-fn) (finally (trace-tooling/unregister-listener! k)))
    (:tags (last @seen))))

(defn- traces-of
  "Run `body-fn`; return `{op -> last-event-tags}` for each op in `ops` seen
  during the body (a single listener, so nesting never drops an op)."
  [ops body-fn]
  (let [op-set (set ops)
        seen   (atom {})
        k      ::multi]
    (trace-tooling/register-listener!
      k (fn [ev] (when (op-set (:operation ev))
                   (swap! seen assoc (:operation ev) (:tags ev)))))
    (try (body-fn) (finally (trace-tooling/unregister-listener! k)))
    @seen))

(defn- competing-authoritative-write!
  "Simulate a CONCURRENT authoritative write landing on the article key between
  the in-flight mutation's optimistic apply and its reply — a SECOND mutation's
  `:populates` seeds the entry with a NEWER server `value`, which bumps the
  entry's `:revision` (`populate-entry`), exactly the move the conflict check
  catches. The competing mutation settles synchronously (its own captured args
  are used + restored), so the original in-flight mutation's `:on-success` /
  `:on-failure` args (saved here) survive for the test to reply against
  afterward. Registered + executed under a distinct instance so it never
  collides with the mutation under test."
  [value]
  (let [saved @last-managed-args]
    (rf/reg-mutation :m/competing
      {:scope :rf.scope/global
       :params-schema [:map [:slug :string]]
       :populates (fn [{:keys [slug]} result]
                    {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                     result})}
      (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/competing :params {:slug "w"}
                                             :instance :competing}])
    (reply-success! @last-managed-args value)
    (reset! last-managed-args saved)))

;; ===========================================================================
;; 1. SUCCESS-COMMIT — an accepted :ok reply settles the optimistic value
;;    authoritatively; the recorded inverse is discarded; the reserved
;;    :patch-summary slots fill; :rf.mutation/optimistic-reconciled fires.
;; ===========================================================================

(deftest success-commits-the-optimistic-apply-and-discards-the-inverse
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false :favoritesCount 9}})
  (rf/reg-mutation :m/favorite
    (assoc favorite-plan
           ;; the authoritative populate overwrites the optimistic value.
           :populates (fn [{:keys [slug]} result]
                        {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                         result}))
    favorite-plan-request)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
  (testing "the optimistic value is in the cache before the reply"
    (is (= true (get-in (entry article-key) [:data :article :favorited])))
    (is (= 10 (get-in (entry article-key) [:data :article :favoritesCount]))))
  (let [recon (trace-of :rf.mutation/optimistic-reconciled
                #(reply-success! @last-managed-args
                                 {:article {:favorited true :favoritesCount 42}}))]
    (testing "the authoritative populate OVERWROTE the optimistic value (commit)"
      (let [e (entry article-key)]
        (is (= 42 (get-in e [:data :article :favoritesCount]))
            "the SERVER count won, not the optimistic 10")
        (is (= :loaded (:status e)))))
    (testing "the instance settled :success and FILLED the reserved patch-summary slots"
      (let [ps (patch-summary :f1)]
        (is (= :success (:status (instance :f1))))
        (is (some? (:snapshot-id ps)) ":snapshot-id filled (was nil)")
        (is (= [article-key] (:committed ps))
            "the optimistic key was committed by the authoritative populate")
        ;; the populate is authoritative (Rider 1) — the key is NOT refetched.
        (is (= [] (:reconciliation-refetches ps)))))
    (testing "the optimistic-reconciled trace carries the snapshot id + committed keys"
      (is (some? (:snapshot-id recon)))
      (is (= [article-key] (:committed recon)))
      (is (= [] (:reconciliation-refetches recon))))))

;; ===========================================================================
;; 2. FAILURE-ROLLBACK (no conflict) — an accepted :error reply restores the
;;    recorded :before entry VERBATIM (including freshness).
;; ===========================================================================

(deftest failure-rolls-back-to-the-recorded-before-verbatim
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false :favoritesCount 9}})
  (let [before (entry article-key)]
    (rf/reg-mutation :m/favorite favorite-plan favorite-plan-request)
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
    (testing "the optimistic value is applied before the reply"
      (is (= true (get-in (entry article-key) [:data :article :favorited]))))
    (let [rb (trace-of :rf.mutation/optimistic-rolled-back
               #(reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 500}))]
      (testing "the accepted :error reply RESTORES the exact :before entry"
        (let [e (entry article-key)]
          (is (= false (get-in e [:data :article :favorited])) "heart un-flipped")
          (is (= 9 (get-in e [:data :article :favoritesCount])) "count reverted")
          (is (= before e) "the WHOLE entry (incl. freshness + :revision) is restored verbatim")))
      (testing "the instance settled :error"
        (is (= :error (:status (instance :f1)))))
      (testing "the rolled-back trace reports the key RESTORED, no conflict"
        (is (= [article-key] (:restored rb)))
        (is (= [] (:conflicted rb)))
        (is (= [] (:refetched rb)))
        (is (= [{:resource/key article-key :restored true :conflict false}]
               (:dispositions rb)))))))

;; ===========================================================================
;; 3. CONFLICT → INVALIDATE (default) — a competing authoritative write moved
;;    the entry's :revision since the apply, so the failure does NOT restore the
;;    stale inverse; it marks the entry stale + refetches.
;; ===========================================================================

(deftest conflict-rollback-invalidates-instead-of-restoring-a-stale-inverse
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false :favoritesCount 9}})
  (rf/reg-mutation :m/favorite favorite-plan favorite-plan-request)        ;; :on-conflict defaults to :invalidate
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
  (testing "the optimistic value is applied; its recorded revision is captured"
    (is (= true (get-in (entry article-key) [:data :article :favorited]))))
  ;; a CONCURRENT authoritative write lands (a populate-mutation returns NEWER
  ;; server truth) — it bumps the entry's :revision past the apply baseline.
  (competing-authoritative-write! {:article {:favorited false :favoritesCount 100}})
  ;; the :invalidate conflict rule dispatches a scoped invalidate-tags that marks
  ;; the moved entry stale (an active owner then refetches) — capture any refetch
  ;; fx + the invalidated trace so we prove the read-path-recovery path ran.
  (let [refetched? (atom false)]
    (rf/reg-fx :rf.resource/refetch (fn [_ _] (reset! refetched? true) nil))
    (rf/reg-fx :rf.resource/schedule-timers (fn [_ _] nil))
    (let [traces (traces-of [:rf.mutation/optimistic-rolled-back
                             :rf.resource/invalidated]
                   #(reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 500}))
          rb     (:rf.mutation/optimistic-rolled-back traces)
          inval  (:rf.resource/invalidated traces)]
      (testing "the rollback did NOT restore the stale inverse (the optimistic 9
                inverse never clobbered the concurrent authoritative value)"
        (let [e (entry article-key)]
          ;; the conflict rule defers to the read path: the entry is NOT the
          ;; restored stale inverse (9). It is either the concurrent value (100)
          ;; or a fresh in-flight refetch — never the stale snapshot.
          (is (not= 9 (get-in e [:data :article :favoritesCount]))
              "the stale inverse (9) was NOT restored over the concurrent write")))
      (testing "the conflicted entry's tags were INVALIDATED → the read path
                recovers authoritative truth (the article key was matched stale)"
        (is (some? inval) "the conflict :invalidate dispatched a scoped invalidate-tags")
        (is (contains? (set (:matched inval)) article-key)
            "the moved article entry was marked stale by the conflict invalidation"))
      (testing "the rolled-back trace reports the conflict + :invalidate + refetch"
        (is (= [article-key] (:conflicted rb)))
        (is (= [article-key] (:refetched rb)))
        (is (= [] (:restored rb)))
        (is (= :invalidate (:on-conflict rb)))
        (is (= [{:resource/key article-key :restored false :conflict true
                 :on-conflict :invalidate}]
               (:dispositions rb))))
      (testing "the instance row's :reconciliation-refetches records the refetched key"
        (is (= [article-key] (:reconciliation-refetches (patch-summary :f1))))))))

;; ===========================================================================
;; 4. CONFLICT → FORCE — :on-conflict :force restores the (stale) inverse even
;;    on conflict + emits the clobber warning.
;; ===========================================================================

(deftest conflict-rollback-force-restores-stale-inverse-with-a-warning
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false :favoritesCount 9}})
  (rf/reg-mutation :m/favorite (assoc favorite-plan :on-conflict :force) favorite-plan-request)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
  ;; competing authoritative write moves the revision.
  (competing-authoritative-write! {:article {:favorited false :favoritesCount 100}})
  (let [traces (traces-of [:rf.mutation/optimistic-rolled-back
                           :rf.warning/optimistic-force-clobber]
                 #(reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 500}))
        rb     (:rf.mutation/optimistic-rolled-back traces)
        warn   (:rf.warning/optimistic-force-clobber traces)]
    (testing ":force RESTORED the recorded (stale) inverse despite the conflict"
      (is (= 9 (get-in (entry article-key) [:data :article :favoritesCount]))
          "the stale inverse (9) clobbered the concurrent value (100)")
      (is (= false (get-in (entry article-key) [:data :article :favorited]))))
    (testing "the trace marks the key restored AND conflicted (the forced clobber)"
      (is (= [article-key] (:restored rb)))
      (is (= [article-key] (:conflicted rb)))
      (is (= :force (:on-conflict rb))))
    (testing "a :force clobber over a conflicted key emits the loud tooling warning"
      (is (some? warn) ":rf.warning/optimistic-force-clobber fired")
      (is (= [article-key] (:forced-keys warn))))))

;; ===========================================================================
;; 5. RESTORE-DANGLE-INSIDE-RECONCILER (Q3 GUARD) — a :pending optimistic write
;;    dangles on epoch restore and rolls back INSIDE the restore reconciler's
;;    single pure pass (no racing post-restore event).
;; ===========================================================================

(defn- optimistic-entry
  "A restored cache entry carrying the OPTIMISTIC value (the heart already
  flipped, no in-flight write to confirm it), at `:revision` `rev`."
  [rev fav count]
  (merge (state/empty-entry :r/article article-key)
         {:status :loaded :data {:article {:favorited fav :favoritesCount count}}
          :loaded-at 1000 :stale-at 9.0e15 :revision rev
          :tags #{[:article "w"] [:article-list]}}))

(defn- pending-optimistic-instance
  "A restored :pending mutation instance whose `:patch-summary` `:rollback`
  records the snapshot-inverse the dangle must replay."
  [rollback]
  (-> (mstate/empty-instance :m/favorite :f1
        {:scope :rf.scope/global :params {:slug "w"} :generation 3
         :work-id [:rf.work/resource [:rf.mutation :f1 3] 3] :started-at 1000})
      (assoc :patch-summary {:snapshot-id [:rf.mutation/snapshot :f1 3]
                             :rollback rollback
                             :reconciliation-refetches nil})))

(deftest restore-dangle-rolls-back-the-optimistic-apply-inside-the-reconciler
  ;; the mutation must be registered so the dangle can read its :on-conflict.
  (rf/reg-mutation :m/favorite favorite-plan favorite-plan-request)        ;; defaults :invalidate
  (testing "NO CONFLICT — the recorded :before is restored INSIDE the reconcile pass"
    (let [before (merge (state/empty-entry :r/article article-key)
                        {:status :loaded :data {:article {:favorited false :favoritesCount 9}}
                         :loaded-at 1000 :stale-at 9.0e15 :revision 5
                         :tags #{[:article "w"] [:article-list]}})
          ;; the apply left the entry at `before.revision + 1` (= 6); the cache
          ;; shows the optimistic value at that applied revision (no competing
          ;; write since the apply) → no conflict.
          rdb {state/resources-key {:entries {(state/key-id article-key)
                                              (optimistic-entry 6 true 10)}
                                    :tag-index {} :owner-index {}}
               mstate/mutations-key
               {(mstate/instance-key-id :f1)
                (pending-optimistic-instance
                  [(mstate/record-optimistic-entry article-key before :patch)])}}
          out (ssr/reconcile-on-restore rdb :app/main {:restore-time-ms 7777})
          e   (get-in out [state/resources-key :entries (state/key-id article-key)])]
      (testing "the optimistic value was ROLLED BACK to the recorded :before"
        (is (= 9 (get-in e [:data :article :favoritesCount])) "count reverted")
        (is (= false (get-in e [:data :article :favorited])) "heart un-flipped"))
      (testing "the instance is terminally dangled (the same pass)"
        (is (= :error (get-in out [mstate/mutations-key (mstate/instance-key-id :f1) :status])))
        (is (= :dangling-on-restore (:reason (get-in out [mstate/mutations-key (mstate/instance-key-id :f1) :error]))))
        (is (nil? (get-in out [mstate/mutations-key (mstate/instance-key-id :f1) :current-work]))))))
  (testing "CONFLICT — a moved revision marks the entry durably STALE in the pass
            (NOT a racing dispatch), the read path refetches on next ensure"
    (let [before (merge (state/empty-entry :r/article article-key)
                        {:status :loaded :data {:article {:favorited false :favoritesCount 9}}
                         :loaded-at 1000 :stale-at 9.0e15 :revision 5
                         :tags #{[:article "w"] [:article-list]}})
          ;; the cache entry's revision (8) MOVED past the recorded one (5) — a
          ;; competing authoritative write landed before the snapshot was taken.
          rdb {state/resources-key {:entries {(state/key-id article-key)
                                              (optimistic-entry 8 false 100)}
                                    :tag-index {} :owner-index {}}
               mstate/mutations-key
               {(mstate/instance-key-id :f1)
                (pending-optimistic-instance
                  [(mstate/record-optimistic-entry article-key before :patch)])}}
          out (ssr/reconcile-on-restore rdb :app/main {:restore-time-ms 7777})
          e   (get-in out [state/resources-key :entries (state/key-id article-key)])]
      (testing "the conflicted entry is NOT restored — the newer truth (100) is kept"
        (is (= 100 (get-in e [:data :article :favoritesCount]))
            "the stale inverse (9) did NOT clobber the moved entry (100)"))
      (testing "the moved entry is marked durably STALE in the SAME pass (no dispatch)"
        (is (= 7777 (:invalidated-at e))
            ":invalidated-at stamped from the restore causal time, in the reconcile pass"))
      (testing "the instance is still terminally dangled"
        (is (= :error (get-in out [mstate/mutations-key (mstate/instance-key-id :f1) :status])))))))

;; ===========================================================================
;; 6. OWNER-CHANGE ROLLBACK (rf2-cxwuhl) — an owner attach / release that lands
;;    BETWEEN the optimistic apply and the FAILED reply is an authoritative
;;    durable write (it mutates the snapshot-captured `:active-owners`), so it
;;    MUST move `:revision`. Otherwise a revision-keyed conflict check is BLIND
;;    to it and a no-conflict `restore-before` clobbers the CURRENT owner set
;;    with the snapshot's — RESURRECTING a released owner (a lease no live caller
;;    holds → the entry never GCs) or DROPPING a mid-flight-attached owner (a
;;    live lease vanishes → premature GC).
;; ===========================================================================

(def ^:private route-owner [:route :r/home :nav1])

(defn- stub-lifecycle-fx! []
  ;; no-op the host-timer / refetch fx the rollback + release + GC paths emit,
  ;; so the pure durable-state assertions run without wall-clock side effects.
  (rf/reg-fx :rf.resource/schedule-timers   (fn [_ _] nil))
  (rf/reg-fx :rf.resource/cancel-timers     (fn [_ _] nil))
  (rf/reg-fx :rf.resource/cancel-poll-timers (fn [_ _] nil))
  (rf/reg-fx :rf.resource/refetch           (fn [_ _] nil)))

(deftest release-mid-flight-does-not-resurrect-the-owner-on-rollback
  ;; THE LEAK (bead rf2-cxwuhl): a route owns an article; an optimistic favorite
  ;; is in flight; the route navigates away (release) BEFORE the reply; the reply
  ;; FAILS. Before the fix, the release did not bump `:revision`, so the rollback
  ;; saw no conflict and restored the pre-apply snapshot verbatim — putting the
  ;; departed route back into `:active-owners` and re-adding it to the
  ;; owner-index, pinning the entry `:has-owner` forever (it can never GC and its
  ;; poll re-arms for the frame's life).
  (stub-lifecycle-fx!)
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"}
                :owner route-owner}
               {:article {:favorited false :favoritesCount 9}})
  (is (contains? (:active-owners (entry article-key)) route-owner)
      "precondition: the route owns the entry")
  (rf/reg-mutation :m/favorite favorite-plan favorite-plan-request)  ;; :on-conflict defaults :invalidate
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
  (testing "the optimistic value is applied (the in-flight window is open)"
    (is (= true (get-in (entry article-key) [:data :article :favorited]))))
  ;; MID-FLIGHT owner release — the route left before the reply settled.
  (rf/dispatch-sync [:rf.resource/release-owner {:owner route-owner}])
  (is (empty? (:active-owners (entry article-key)))
      "the release dropped the owner from the live entry")
  ;; the reply FAILS → conflict-aware rollback runs.
  (reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 500})
  (testing "the departed owner is NOT resurrected onto the entry"
    (let [owners (:active-owners (entry article-key))]
      (is (not (contains? owners route-owner))
          "the pre-release snapshot's owner set did NOT clobber the current one")
      (is (empty? owners) "the entry is still owner-free after the rollback")))
  (testing "the derived owner-index carries no phantom membership for the departed owner"
    (is (nil? (get-in (runtime-db) (conj (state/owner-index-path) route-owner)))
        "reindex did not re-add a phantom owner from a resurrected :active-owners"))
  (testing "the now-owner-free, idle entry is GC-eligible and gc-fired COLLECTS it"
    (let [e (entry article-key)]
      (is (empty? (:active-owners e)) "no owner pins it")
      (is (nil? (:current-work e)) "no in-flight work pins it"))
    (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key article-key}])
    (is (nil? (entry article-key))
        "GC removed the entry — the lease leak is fixed (was pinned :has-owner forever)")))

(deftest attach-mid-flight-is-not-dropped-by-rollback
  ;; THE MIRROR CASE (rf2-cxwuhl): an owner ATTACHED between the apply and the
  ;; failed reply (via a fresh-skip cache-hit ensure — the entry is fresh after
  ;; the optimistic apply) must survive the rollback. Before the fix the attach
  ;; did not bump `:revision`, so the no-conflict restore dropped the freshly
  ;; attached lease (its live owner would then see the entry GC out from under
  ;; it). The bump makes it a conflict → `:invalidate` keeps the CURRENT owner
  ;; set (both leases) and recovers via the read path.
  (stub-lifecycle-fx!)
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"}
                :owner [:v :first]}
               {:article {:favorited false :favoritesCount 9}})
  (rf/reg-mutation :m/favorite favorite-plan favorite-plan-request)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
  ;; MID-FLIGHT: a second component ensures the (now fresh) entry, attaching a
  ;; NEW lease via the fresh-skip cache-hit path — no work, no reply.
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/article :scope :rf.scope/global
                                          :params {:slug "w"} :owner [:v :second]}])
  (is (= #{[:v :first] [:v :second]} (:active-owners (entry article-key)))
      "both leases are live before the reply settles")
  ;; the reply FAILS → conflict-aware rollback.
  (reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 500})
  (testing "the freshly-attached lease is NOT dropped by the rollback"
    (let [owners (:active-owners (entry article-key))]
      (is (contains? owners [:v :second])
          "the mid-flight attach survived (was dropped by the blind restore before the fix)")
      (is (contains? owners [:v :first]) "the original lease is intact too")))
  (testing "the owner-index still routes both owners to the entry"
    (is (contains? (get-in (runtime-db) (conj (state/owner-index-path) [:v :second]))
                   (state/key-id article-key)))
    (is (contains? (get-in (runtime-db) (conj (state/owner-index-path) [:v :first]))
                   (state/key-id article-key)))))
