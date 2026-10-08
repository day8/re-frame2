(ns re-frame.resources-optimistic-settle-cljs-test
  "Optimistic settlement: an accepted :ok reply commits the optimistic value; an
  accepted :error reply restores the recorded :before unless a competing
  authoritative write moved the entry's :revision since the apply, in which case
  :on-conflict :invalidate marks it stale and refetches it by its exact key. A
  :pending optimistic write dangling on epoch restore rolls back inside the
  restore reconciler's own pass."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
   [re-frame.resources.ssr :as rf.resources.ssr]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.registrar :as rf.registrar]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   [re-frame.trace.tooling :as rf.trace.tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private last-managed-args (atom nil))

(defn- init! []
  (rf.registrar/clear-kind! :resource-scope)
  (rf/reg-resource-scope :t/session
    {:inputs {:username [:db [:auth :user :username]]}}
    (fn [{:keys [username]} _ctx]
      (when username [:rf.scope/session {:username username}])))
  (rf/reg-event :t/login (fn [{:keys [db]} [_ username]]
                           {:db (assoc-in db [:auth :user :username] username)})))

(defn- capturing-transport-fixture [f]
  (reset! last-managed-args nil)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ _] nil))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter :init-fn init!}
       :cljs {:adapter rf.adapter.reagent/adapter :init-fn init!}))
  capturing-transport-fixture)

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))
;; :rf.runtime/mutations is keyed on the instance id's CEDN-1 byte key-id.
(defn- instance [instance-id] (get-in (runtime-db) [:rf.runtime/mutations (rf.resources.state/key-id instance-id)]))
(defn- patch-summary [instance-id] (:patch-summary (instance instance-id)))

(defn- reply-success! [args result]
  (rf/dispatch-sync (conj (:on-success args) {:status :ok :value result})))

(defn- reply-failure! [args failure]
  (rf/dispatch-sync (conj (:on-failure args) {:status :error :error failure})))

(def ^:private http-500 {:kind :rf.http/http-5xx :status 500})

(def ^:private article-key
  (rf.resources.state/scoped-resource-key :rf.scope/global :r/article {:slug "w"}))

(def ^:private article-q {:resource :r/article :scope :rf.scope/global :params {:slug "w"}})

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

(defn- favorite-pending!
  "Load the article at 9 favourites under `owner`, then execute the optimistic
  favourite as instance :f1 (:on-conflict defaults to :invalidate)."
  [owner]
  (reg-article-resource!)
  (own-loaded! (assoc article-q :owner owner) {:article {:favorited false :favoritesCount 9}})
  (rf/reg-mutation :m/favorite favorite-plan favorite-plan-request)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}]))

(defn- traces-of
  "Run `body-fn`; return `{op -> last-event-tags}` for each op in `ops` seen."
  [ops body-fn]
  (let [op-set (set ops)
        seen   (atom {})
        k      ::multi]
    (rf.trace.tooling/register-listener!
      k (fn [ev] (when (op-set (:operation ev))
                   (swap! seen assoc (:operation ev) (:tags ev)))))
    (try (body-fn) (finally (rf.trace.tooling/unregister-listener! k)))
    @seen))

(defn- trace-of [op body-fn] (get (traces-of [op] body-fn) op))

(defn- competing-authoritative-write!
  "A second mutation's :populates lands NEWER server `value` on the article key
  while the mutation under test is in flight, moving the entry's :revision. The
  saved in-flight args are restored afterwards for the test to reply against."
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

(deftest success-commits-the-optimistic-apply-and-fills-the-patch-summary
  (reg-article-resource!)
  (own-loaded! (assoc article-q :owner [:v :d]) {:article {:favorited false :favoritesCount 9}})
  (rf/reg-mutation :m/favorite
    (assoc favorite-plan
           :populates (fn [{:keys [slug]} result]
                        {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                         result}))
    favorite-plan-request)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
  (is (= {:favorited true :favoritesCount 10} (get-in (entry article-key) [:data :article]))
      "the optimistic value is in the cache before the reply")
  (let [recon (trace-of :rf.mutation/optimistic-reconciled
                #(reply-success! @last-managed-args
                                 {:article {:favorited true :favoritesCount 42}}))
        ps    (patch-summary :f1)
        e     (entry article-key)]
    (is (= [42 :loaded] [(get-in e [:data :article :favoritesCount]) (:status e)])
        "the authoritative populate overwrote the optimistic value")
    (is (= :success (:status (instance :f1))))
    ;; the populate is authoritative, so the key is committed, not refetched
    (doseq [m [ps recon]]
      (is (some? (:snapshot-id m)))
      (is (= {:committed [article-key] :reconciliation-refetches []}
             (select-keys m [:committed :reconciliation-refetches]))))))

(deftest failure-rolls-back-to-the-recorded-before-verbatim
  (reg-article-resource!)
  (own-loaded! (assoc article-q :owner [:v :d]) {:article {:favorited false :favoritesCount 9}})
  (let [before (entry article-key)]
    (rf/reg-mutation :m/favorite favorite-plan favorite-plan-request)
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
    (is (= true (get-in (entry article-key) [:data :article :favorited])) "applied before the reply")
    (let [rb (trace-of :rf.mutation/optimistic-rolled-back
               #(reply-failure! @last-managed-args http-500))]
      (is (= before (entry article-key)) "the whole entry, freshness and :revision included, is restored")
      (is (= :error (:status (instance :f1))))
      (is (= {:restored [article-key] :conflicted [] :refetched []
              :dispositions [{:resource/key article-key :restored true :conflict false}]}
             (select-keys rb [:restored :conflicted :refetched :dispositions]))))))

(deftest conflict-rollback-invalidates-instead-of-restoring-a-stale-inverse
  (favorite-pending! [:v :d])
  (competing-authoritative-write! {:article {:favorited false :favoritesCount 100}})
  (let [muta @last-managed-args
        _    (reset! last-managed-args nil)
        rb   (trace-of :rf.mutation/optimistic-rolled-back #(reply-failure! muta http-500))]
    (is (not= 9 (get-in (entry article-key) [:data :article :favoritesCount]))
        "the stale inverse (9) was not restored over the concurrent write")
    (is (= {:method :get :url "/a/w"} (:request @last-managed-args))
        "the owned conflicted key is refetched by its exact key")
    (is (= {:conflicted [article-key] :refetched [article-key] :restored [] :on-conflict :invalidate
            :dispositions [{:resource/key article-key :restored false :conflict true
                            :on-conflict :invalidate}]}
           (select-keys rb [:conflicted :refetched :restored :on-conflict :dispositions])))
    (is (= [article-key] (:reconciliation-refetches (patch-summary :f1))))))

;; ---- restore dangle ----------------------------------------------------------

(defn- article-entry [rev fav count]
  (merge (rf.resources.state/empty-entry :r/article article-key)
         {:status :loaded :data {:article {:favorited fav :favoritesCount count}}
          :loaded-at 1000 :stale-at 9.0e15 :revision rev
          :tags #{[:article "w"] [:article-list]}}))

(defn- reconcile-dangle
  "Reconcile, on epoch restore, a cache holding `cached` beside a :pending :f1
  whose recorded inverse is the article at revision 5 (the apply moved it to 6)."
  [cached]
  (let [rollback [(rf.resources.mutation-runtime/record-optimistic-entry
                    article-key (article-entry 5 false 9) :patch)]
        pending  (-> (rf.resources.mutation-runtime/empty-instance :m/favorite :f1
                       {:scope :rf.scope/global :params {:slug "w"} :generation 3
                        :work-id [:rf.work/resource [:rf.mutation :f1 3] 3] :started-at 1000})
                     (assoc :patch-summary {:snapshot-id [:rf.mutation/snapshot :f1 3]
                                            :rollback rollback
                                            :reconciliation-refetches nil}))]
    (rf.resources.ssr/reconcile-on-restore
      {rf.resources.state/resources-key {:entries {(rf.resources.state/key-id article-key) cached}
                                         :tag-index {} :owner-index {}}
       rf.resources.mutation-runtime/mutations-key
       {(rf.resources.mutation-runtime/instance-key-id :f1) pending}}
      :app/main {:restore-time-ms 7777})))

(defn- out-entry [out]
  (get-in out [rf.resources.state/resources-key :entries (rf.resources.state/key-id article-key)]))

(defn- out-instance [out]
  (get-in out [rf.resources.mutation-runtime/mutations-key (rf.resources.mutation-runtime/instance-key-id :f1)]))

(deftest restore-dangle-rolls-back-the-optimistic-apply-inside-the-reconciler
  ;; registered so the dangle can read its :on-conflict (default :invalidate)
  (rf/reg-mutation :m/favorite favorite-plan favorite-plan-request)
  (testing "no conflict: the recorded :before is restored in the reconcile pass"
    (let [out (reconcile-dangle (article-entry 6 true 10))]
      (is (= {:favorited false :favoritesCount 9} (get-in (out-entry out) [:data :article])))
      (is (= [:error :dangling-on-restore nil]
             ((juxt :status (comp :reason :error) :current-work) (out-instance out))))))
  (testing "conflict: a moved revision keeps the newer value and marks it stale in the same pass"
    (let [out (reconcile-dangle (article-entry 8 false 100))]
      (is (= [100 7777] ((juxt #(get-in % [:data :article :favoritesCount]) :invalidated-at)
                         (out-entry out))))
      (is (= :error (:status (out-instance out)))))))

;; ---- owner changes and reads that land mid-flight ----------------------------

(def ^:private route-owner [:route :r/home :nav1])

(defn- stub-lifecycle-fx! []
  (rf.fx/reg-fx :rf.resource/schedule-timers   (fn [_ _] nil))
  (rf.fx/reg-fx :rf.resource/cancel-timers     (fn [_ _] nil))
  (rf.fx/reg-fx :rf.resource/cancel-poll-timers (fn [_ _] nil))
  (rf/reg-fx :rf.resource/refetch           (fn [_ _] nil)))

(def ^:private delete-plan
  {:scope :rf.scope/global
   :params-schema [:map [:slug :string]]
   ;; a nil patch-fn is an optimistic REMOVE
   :optimistic (fn [{:keys [slug]}]
                 {{:resource :r/article :params {:slug slug} :scope :rf.scope/global} nil})})

(def ^:private delete-plan-request
  (fn [{:keys [slug]} _] {:request {:method :delete :url (str "/a/" slug)}}))

(defn- ledger-rows-for
  "Work-ledger rows linked to `scoped-key`, read off the durable slot rather
  than through the ledger's own index."
  [scoped-key]
  (->> (:rf.runtime/work-ledger (runtime-db))
       vals
       (filter #(= scoped-key (:resource/key %)))
       count))

(deftest remove-mid-flight-release-does-not-resurrect-the-owner-on-rollback
  ;; Delete a card, navigate away before the DELETE reply, and the reply fails.
  ;; The remove tombstones the entry, so the release bumps its :revision and
  ;; the rollback sees a conflict; restoring the snapshot's owner set would
  ;; leave an owner nobody holds, and an entry that never GCs.
  (stub-lifecycle-fx!)
  (reg-article-resource!)
  (own-loaded! (assoc article-q :owner route-owner) {:article {:slug "w" :title "Doomed"}})
  (rf/reg-mutation :m/delete delete-plan delete-plan-request)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/delete :params {:slug "w"} :instance :d1}])
  (testing "the optimistic remove tombstones the entry rather than dissocing it"
    ;; load-bearing for the owner checks below, which read green on a nil entry
    (is (= [true nil :idle] ((juxt some? :data :status) (entry article-key)))))
  (let [before-release (rf.resources.state/entry-revision (entry article-key))]
    (rf/dispatch-sync [:rf.resource/release-owner {:owner route-owner}])
    (testing "precondition: the release dropped the owner and moved :revision"
      (let [e (entry article-key)]
        (is (= [true false (inc before-release)]
               [(some? e) (contains? (:active-owners e) route-owner) (:revision e)])))))
  (reply-failure! @last-managed-args http-500)
  (testing "the rollback does not resurrect the departed owner"
    (is (some? (entry article-key)))
    (is (empty? (:active-owners (entry article-key))))
    (is (nil? (get-in (runtime-db) (conj (rf.resources.state/owner-index-path) route-owner)))))
  (testing "the owner-free tombstone GCs and takes its work-ledger rows with it"
    (is (pos? (ledger-rows-for article-key)) "precondition: there are rows to drop")
    (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key article-key}])
    (is (nil? (entry article-key)))
    (is (zero? (ledger-rows-for article-key)))))

(def ^:private profile-key
  (rf.resources.state/scoped-resource-key :rf.scope/global :r/profile {}))

(def ^:private profile-q
  {:resource :r/profile :scope :rf.scope/global :params {}})

(defn- reg-profile-resources!
  "An exact-target resource with no :tags (it legitimately declares none) and
  an optimistic save of it."
  []
  (rf/reg-resource :r/profile
    {:scope :rf.scope/global :params-schema [:map]}
    (fn [_p _] {:request {:method :get :url "/profile"}}))
  (rf/reg-mutation :m/save-profile
    {:scope :rf.scope/global
     :params-schema [:map]
     :optimistic (fn [_p]
                   {{:resource :r/profile :params {} :scope :rf.scope/global}
                    (fn [p] (assoc p :saved? true))})}
    (fn [_p _] {:request {:method :post :url "/profile"}})))

(deftest tagless-conflict-rollback-recovers-by-the-exact-key
  ;; Recovery is keyed by the carried exact key; rediscovery through the
  ;; optional :tags would leave the failed value fresh with no request.
  (reg-profile-resources!)
  (own-loaded! (assoc profile-q :owner [:v :a]) {:saved? false})
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save-profile :params {} :instance :s1}])
  ;; a second owner attaches mid-flight, which moves :revision
  (rf/dispatch-sync [:rf.resource/ensure (assoc profile-q :owner [:v :b])])
  (let [muta @last-managed-args
        _    (reset! last-managed-args nil)
        rb   (trace-of :rf.mutation/optimistic-rolled-back #(reply-failure! muta http-500))
        e    (entry profile-key)]
    (is (= [#{[:v :a] [:v :b]} :fetching true]
           [(:active-owners e) (:status e) (some? (:current-work e))])
        "both owners survive; the failed value is not left fresh, a recovery is in flight")
    (is (= {:method :get :url "/profile"} (:request @last-managed-args)))
    (is (= {:refetched [profile-key] :conflicted [profile-key] :restored []}
           (select-keys rb [:refetched :conflicted :restored])))
    (is (= [profile-key] (:reconciliation-refetches (patch-summary :s1))))
    (reply-success! @last-managed-args {:saved? false})
    (is (= {:saved? false} @(rf/subscribe [:rf.resource/data profile-q])))
    (is (= :loaded (:status @(rf/subscribe [:rf/resource profile-q]))))))

(deftest owner-free-conflict-leaves-the-exact-entry-durably-stale
  ;; With no active owner the conflicted entry stays stale in place, with no
  ;; fetch, until its next ensure. The stale-marked key is still affected
  ;; (Spec 016 §Mutation completion continuations).
  (reg-profile-resources!)
  (let [replied (atom nil)]
    (rf/reg-event :t/save-settled (fn [_ event] (reset! replied (last event)) {}))
    (own-loaded! (assoc profile-q :owner [:v :a]) {:saved? false})
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save-profile :params {} :instance :s1
                                             :reply-to [:t/save-settled]}])
    ;; the sole owner leaves mid-flight; detach-owner moves :revision
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:v :a]}])
    (let [muta @last-managed-args
          _    (reset! last-managed-args nil)
          trs  (traces-of [:rf.mutation/optimistic-rolled-back :rf.mutation/failed]
                 #(reply-failure! muta http-500))
          e    (entry profile-key)]
      (is (some? (:invalidated-at e)) "durably stale in place")
      (is (= [true nil] [(empty? (:active-owners e)) (:current-work e)])
          "no owner resurrected, no fetch started")
      (is (true? (:stale? @(rf/subscribe [:rf/resource profile-q]))))
      (is (nil? @last-managed-args) "no recovery request for the owner-free entry")
      (is (= {:conflicted [profile-key] :refetched [] :restored []}
             (select-keys (:rf.mutation/optimistic-rolled-back trs) [:conflicted :refetched :restored])))
      (is (= [] (:reconciliation-refetches (patch-summary :s1))))
      (is (= [[profile-key] [profile-key] #{profile-key}]
             [(:affected-keys (instance :s1))
              (:affected-keys (:rf.mutation/failed trs))
              (:affected-keys @replied)])
          "instance row, failed trace and :reply-to reply all carry the stale-marked key")
      (rf/dispatch-sync [:rf.resource/ensure (assoc profile-q :owner [:v :c])])
      (is (= {:method :get :url "/profile"} (:request @last-managed-args))
          "the next owned ensure starts recovery"))))

;; A read START does not move :revision (it must not false-conflict), so the
;; settle restores. An optimistic apply writes only payload and freshness, so
;; the restore keeps the live entry's read-work and ownership facts: restoring
;; them from the snapshot would suppress the read's own reply and drop the
;; owner it attached.

(deftest a-refetch-started-mid-flight-survives-the-rollback
  (stub-lifecycle-fx!)
  (favorite-pending! [:v :first])
  (is (= {:favorited true :favoritesCount 10} (get-in (entry article-key) [:data :article]))
      "precondition: the optimistic value is applied")
  (let [mutation-args @last-managed-args]
    (rf/dispatch-sync [:rf.resource/refetch (assoc article-q :owner [:v :first])])
    (let [read-args @last-managed-args
          reading   (entry article-key)]
      (is (some? (:current-work reading)) "precondition: a newer read is in flight")
      (is (= {:method :get :url "/a/w"} (:request read-args)))
      (reply-failure! mutation-args http-500)
      (is (= [{:favorited false :favoritesCount 9} (:current-work reading) (:generation reading) :fetching]
             ((juxt (comp :article :data) :current-work :generation :status) (entry article-key)))
          "the payload rolled back; the read's work facts survived, so its status is :fetching")
      (reply-success! read-args {:article {:favorited false :favoritesCount 42}})
      (is (= [:loaded nil] ((juxt :status :current-work) (entry article-key)))
          "the read's own reply lands")
      (is (= {:article {:favorited false :favoritesCount 42}}
             @(rf/subscribe [:rf.resource/data article-q]))))))

(deftest an-owner-attached-by-that-refetch-survives-the-rollback
  (stub-lifecycle-fx!)
  (favorite-pending! [:v :first])
  (let [mutation-args @last-managed-args]
    (rf/dispatch-sync [:rf.resource/refetch (assoc article-q :owner [:v :second])])
    (let [read-args @last-managed-args]
      (reply-failure! mutation-args http-500)
      (is (= #{[:v :first] [:v :second]} (:active-owners (entry article-key))))
      (is (contains? (get-in (runtime-db) (conj (rf.resources.state/owner-index-path) [:v :second]))
                     (rf.resources.state/key-id article-key))
          "the owner index still carries the attached owner")
      (reply-success! read-args {:article {:favorited false :favoritesCount 42}})
      (is (= 42 (get-in (entry article-key) [:data :article :favoritesCount]))))))

(deftest an-absent-restore-keeps-a-live-read-rather-than-dissocing-it
  ;; Rolling back an optimistic seed restores the absence, unless a read started
  ;; on the key meanwhile: then the empty pre-seed entry is seated carrying it.
  (let [disp    {:resource/key article-key :disposition :restore
                 :before rf.resources.mutation-runtime/absent-snapshot :forward :seed}
        path    (rf.resources.state/entry-path article-key)
        restore #(get-in (rf.resources.mutation-runtime/restore-before (assoc-in {} path %) disp) path)
        e       (restore (-> (rf.resources.state/empty-entry :r/article article-key)
                             (rf.resources.state/entry-start-load
                               {:generation 7 :work-id [:r/article 7]
                                :request-id :rq7 :owner [:v :reader]})))]
    (is (= [[:r/article 7] 7 true nil :loading]
           [(:current-work e) (:generation e) (contains? (:active-owners e) [:v :reader])
            (:data e) (:status e)])
        "the read's work, generation and owner survive; the seeded data is gone")
    (is (nil? (restore (rf.resources.state/empty-entry :r/article article-key)))
        "control: with no read in flight the seeded key is removed")))

(deftest optimistic-seed-uncovered-by-the-reply-arms-its-gc-timer
  ;; A seeded entry is ownerless and no read path armed its timers. When the
  ;; reply's :patches/:populates/:removes do not cover the key, the settle must
  ;; arm them itself, or nothing ever collects the entry.
  (let [armed (atom [])]
    (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ args] (swap! armed conj args) nil))
    (rf/reg-resource :r/detail
      {:scope          :rf.scope/global
       :params-schema  [:map [:id :string]]
       :stale-after-ms 60000
       :gc-after-ms    120000
       :tags           (fn [{:keys [id]} _] #{[:detail id]})}
      (fn [{:keys [id]} _] {:request {:method :get :url (str "/d/" id)}}))
    (rf/reg-mutation :m/create
      {:scope         :rf.scope/global
       :params-schema [:map [:id :string]]
       :optimistic    (fn [{:keys [id]}]
                        {{:resource :r/detail :params {:id id} :scope :rf.scope/global}
                         (fn [_absent] {:detail {:id id :pending true}})})}
      (fn [_params _ctx] {:request {:method :post :url "/d"}}))
    (let [k (rf.resources.state/scoped-resource-key :rf.scope/global :r/detail {:id "7"})]
      (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/create :params {:id "7"}
                                               :instance :c1}])
      (is (= [:seed] (mapv :forward (:rollback (patch-summary :c1))))
          "precondition: execute seeded the key")
      (reset! armed [])
      (reply-success! @last-managed-args {:id "7"})
      (let [ps (patch-summary :c1)]
        (is (and (empty? (:patched ps)) (empty? (:populated ps)) (empty? (:removed ps)))
            "precondition: the reply writes nothing authoritative"))
      (is (some? (entry k)) "the seeded entry survives the settle")
      (is (empty? (:active-owners (entry k))) "and is ownerless")
      (is (= [{:gc 120000 :stale 60000}]
             (->> @armed
                  (filter #(= k (:resource/key %)))
                  (mapv #(select-keys (:timers %) [:gc :stale]))))
          "exactly one schedule-timers fx for the seeded key, carrying its policy"))))
