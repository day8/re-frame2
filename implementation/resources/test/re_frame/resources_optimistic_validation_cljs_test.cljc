(ns re-frame.resources-optimistic-validation-cljs-test
  "Optimistic-mutation laws beyond apply and settle: :on-conflict :force, stale
  replies, concurrent writes, per-entry tag rollback, fail-closed :from-db
  targets, :reply-to timing, trace evidence, the :optimistic? sub flag and
  malformed optimistic plans."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.error-emit :as rf.error-emit]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
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

(def ^:private article-owned
  {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]})

(defn- count-of [scoped-key] (get-in (entry scoped-key) [:data :article :favoritesCount]))

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

(defn- populating [plan]
  (assoc plan :populates (fn [{:keys [slug]} result]
                           {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                            result})))

(defn- execute! [mutation instance-id & {:as extra}]
  (rf/dispatch-sync [:rf.mutation/execute (merge {:mutation mutation :params {:slug "w"}
                                                  :instance instance-id}
                                                 extra)]))

(defn- mutation-state [instance-id]
  @(rf/subscribe [:rf/mutation {:instance instance-id}]))

(defn- traces-of
  "Run `body-fn`; return `{op -> last-event-tags}` for each op in `ops`."
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

(defn- populate-write!
  "A second mutation's :populates lands `value` on `resource`'s key while the
  mutation under test is in flight, moving the entry's :revision. The saved
  in-flight args are restored afterwards for the test to reply against."
  [resource params value]
  (let [saved @last-managed-args]
    (rf/reg-mutation :m/competing
      {:scope :rf.scope/global
       :params-schema [:map]
       :populates (fn [_p result] {{:resource resource :params params :scope :rf.scope/global} result})}
      (fn [_p _] {:request {:method :put :url "/competing"}}))
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/competing :params params
                                             :instance :competing}])
    (reply-success! @last-managed-args value)
    (reset! last-managed-args saved)))

(deftest case-05-force-restores-stale-inverse-with-a-warning
  (reg-article-resource!)
  (own-loaded! article-owned {:article {:favorited false :favoritesCount 9}})
  (rf/reg-mutation :m/favorite (assoc favorite-plan :on-conflict :force) favorite-plan-request)
  (execute! :m/favorite :f1)
  (populate-write! :r/article {:slug "w"} {:article {:favorited false :favoritesCount 100}})
  (let [traces (traces-of [:rf.mutation/optimistic-rolled-back
                           :rf.warning/optimistic-force-clobber]
                 #(reply-failure! @last-managed-args http-500))]
    (is (= 9 (count-of article-key)) ":force restored the recorded inverse despite the conflict")
    (is (= {:restored [article-key] :conflicted [article-key] :on-conflict :force}
           (select-keys (:rf.mutation/optimistic-rolled-back traces) [:restored :conflicted :on-conflict])))
    (is (= [article-key] (:forced-keys (:rf.warning/optimistic-force-clobber traces))))))

(deftest case-06-stale-superseded-reply-discards-its-inverse
  ;; A re-execute under the same instance re-applies (10 -> 11); the first
  ;; execute's reply is now stale, and replaying its inverse would write 9.
  (reg-article-resource!)
  (own-loaded! article-owned {:article {:favorited false :favoritesCount 9}})
  (rf/reg-mutation :m/favorite favorite-plan favorite-plan-request)
  (execute! :m/favorite :f1)
  (let [stale-args @last-managed-args]
    (reset! last-managed-args nil)
    (execute! :m/favorite :f1)
    (is (= 11 (count-of article-key)) "the newer apply owns the entry")
    (let [stale-tr (trace-of :rf.mutation/stale-suppressed #(reply-failure! stale-args http-500))]
      (is (some? stale-tr) "the superseded reply was stale-suppressed")
      (is (= [11 :pending] [(count-of article-key) (:status (instance :f1))])
          "its inverse was discarded and the live instance untouched"))))

(deftest case-07-two-concurrent-optimistic-writes-compose-deterministically
  ;; :b commits authoritatively, moving the revision past :a's baseline, so
  ;; :a's failure defers to the read path rather than restoring its stale 9.
  (reg-article-resource!)
  (own-loaded! article-owned {:article {:favoritesCount 9}})
  (rf/reg-mutation :m/inc
    (populating
      {:scope :rf.scope/global
       :params-schema [:map [:slug :string]]
       :optimistic (fn [{:keys [slug]}]
                     {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                      (fn [a] (update-in a [:article :favoritesCount] inc))})})
    (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug "/inc")}}))
  (execute! :m/inc :a)
  (let [a-args @last-managed-args
        a-inv  (first (:rollback (patch-summary :a)))]
    (reset! last-managed-args nil)
    (execute! :m/inc :b)
    (let [b-args @last-managed-args
          b-inv  (first (:rollback (patch-summary :b)))]
      (is (not= (:revision a-inv) (:revision b-inv)) "each write recorded the revision it observed")
      (is (= [9 10] (map #(get-in (:before %) [:data :article :favoritesCount]) [a-inv b-inv]))
          ":b's recorded :before is :a's optimistic value")
      (reply-success! b-args {:article {:favoritesCount 50}})
      (is (= 50 (count-of article-key)) ":b committed authoritatively")
      (rf/reg-fx :rf.resource/refetch (fn [_ _] nil))
      (let [rb (trace-of :rf.mutation/optimistic-rolled-back #(reply-failure! a-args http-500))]
        (is (not= 9 (count-of article-key)) ":a's stale inverse did not clobber :b's value")
        (is (= {:conflicted [article-key] :on-conflict :invalidate}
               (select-keys rb [:conflicted :on-conflict])))
        (is (= [:error :success] [(:status (instance :a)) (:status (instance :b))]))))))

(defn- reg-article-and-feed! []
  (rf/reg-resource :r/article
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :tags (fn [{:keys [slug]} _] #{[:article slug]})}
    (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}}))
  (rf/reg-resource :r/feed
    {:scope :rf.scope/global
     :params-schema [:map]
     :tags (fn [_p _] #{[:feed-w]})}
    (fn [_p _] {:request {:method :get :url "/feed"}}))
  (own-loaded! article-owned {:article {:favorited false}})
  (own-loaded! {:resource :r/feed :scope :rf.scope/global :params {} :owner [:v :f]} {:items 1}))

(def ^:private feed-key (rf.resources.state/scoped-resource-key :rf.scope/global :r/feed {}))

(defn- touched [scoped-key] (get-in (entry scoped-key) [:data :touched]))

(deftest case-08-optimistic-tags-rolls-back-each-matched-entry-independently
  ;; Disjoint tags, so a conflict on one entry cannot cross-stale the other; a
  ;; competing write then moves only the feed.
  (reg-article-and-feed!)
  (let [detail-before (entry article-key)]
    (rf/reg-mutation :m/favorite-everywhere
      {:scope :rf.scope/global
       :params-schema [:map [:slug :string]]
       :optimistic-tags (fn [{:keys [slug]}]
                          [{:scope :rf.scope/global :tags #{[:article slug]}
                            :patch (fn [d] (assoc d :touched true))}
                           {:scope :rf.scope/global :tags #{[:feed-w]}
                            :patch (fn [d] (assoc d :touched true))}])}
      (fn [_ _] {:request {:method :post :url "/fav"}}))
    (execute! :m/favorite-everywhere :fe1)
    (is (= [true true] [(touched article-key) (touched feed-key)]) "both matched entries patched")
    (populate-write! :r/feed {} {:items 99 :server true})
    (rf/reg-fx :rf.resource/refetch (fn [_ _] nil))
    (let [rb (trace-of :rf.mutation/optimistic-rolled-back #(reply-failure! @last-managed-args http-500))]
      (is (= detail-before (entry article-key)) "the unmoved detail restored to its exact :before")
      (is (= 99 (get-in (entry feed-key) [:data :items])) "the moved feed was not restored")
      (is (= {:restored [article-key] :conflicted [feed-key] :refetched [feed-key]}
             (select-keys rb [:restored :conflicted :refetched]))
          "each entry disposed independently in one settle"))))

(deftest case-09-from-db-target-resolving-nil-is-fail-closed
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
  ;; no :t/login, so {:from-db :t/session} resolves nil
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/touch-feed :params {} :instance :tf1}])
  (is (nil? (entry (rf.resources.state/scoped-resource-key :rf.scope/global :r/feed {})))
      "no entry written under an implicit global")
  (is (= [[:t/session] true] ((juxt :target-unresolved (comp empty? :rollback)) (patch-summary :tf1)))
      "the dropped target is recorded as unresolved, with no inverse"))

(deftest case-11-reply-to-fires-once-after-settle-not-on-the-apply
  (let [replied (atom [])]
    (rf/reg-event :test/replied (fn [_ event] (swap! replied conj event) {}))
    (reg-article-resource!)
    (own-loaded! article-owned {:article {:favorited false :favoritesCount 9}})
    (rf/reg-mutation :m/favorite (populating favorite-plan) favorite-plan-request)
    (execute! :m/favorite :f1 :reply-to [:test/replied])
    (is (= [true []] [(get-in (entry article-key) [:data :article :favorited]) @replied])
        "the apply landed and dispatched no continuation")
    (reply-success! @last-managed-args {:article {:favorited true :favoritesCount 42}})
    (is (= [[:test/replied :ok :f1]]
           (mapv (fn [[ev-id reply]] [ev-id (:status reply) (:instance reply)]) @replied))
        "the continuation fired exactly once, after settle")))

(deftest case-12-optimistic-applied-trace-carries-snapshot-and-revisions
  (reg-article-resource!)
  (own-loaded! article-owned {:article {:favorited false :favoritesCount 9}})
  (let [rev-before (:revision (entry article-key))]
    (rf/reg-mutation :m/favorite favorite-plan favorite-plan-request)
    (let [applied (trace-of :rf.mutation/optimistic-applied #(execute! :m/favorite :f1))]
      (is (some? (:snapshot-id applied)))
      (is (= {:affected-keys [article-key]
              :revisions [{:resource/key article-key :revision rev-before :forward :patch}]}
             (select-keys applied [:affected-keys :revisions]))))))

(deftest rider1-optimistic-flag-true-between-apply-and-settle
  (reg-article-resource!)
  (own-loaded! article-owned {:article {:favorited false :favoritesCount 9}})
  (rf/reg-mutation :m/favorite (populating favorite-plan) favorite-plan-request)
  (let [idle    (mutation-state :f1)
        _       (execute! :m/favorite :f1)
        pending (mutation-state :f1)
        _       (reply-success! @last-managed-args {:article {:favorited true :favoritesCount 42}})
        settled (mutation-state :f1)]
    (is (= [false [true true] [false true]]
           [(:optimistic? idle)
            ((juxt :optimistic? :pending?) pending)
            ((juxt :optimistic? :success?) settled)])
        ":optimistic? is true only between the apply and the settle")))

(deftest rider1-optimistic-flag-false-after-opt-out
  (reg-article-resource!)
  (own-loaded! article-owned {:article {:favorited false}})
  (rf/reg-mutation :m/favorite favorite-plan favorite-plan-request)
  (execute! :m/favorite :f1 :optimistic? false)
  (is (= [true false] ((juxt :pending? :optimistic?) (mutation-state :f1)))))

(deftest malformed-optimistic-tags-descriptor-does-not-block-the-write
  ;; A tags descriptor is a best-effort broadcast, so a malformed one is
  ;; warn-and-skipped and the authoritative write still lowers.
  (reg-article-and-feed!)
  (testing "a descriptor with no :patch is skipped; its well-formed sibling applies"
    (rf/reg-mutation :m/bad-patch
      {:scope :rf.scope/global
       :params-schema [:map [:slug :string]]
       :optimistic-tags (fn [{:keys [slug]}]
                          [{:scope :rf.scope/global :tags #{[:article slug]}}
                           {:scope :rf.scope/global :tags #{[:feed-w]}
                            :patch (fn [d] (assoc d :touched true))}])}
      (fn [_p _] {:request {:method :post :url "/fav"}}))
    (let [warn (trace-of :rf.warning/optimistic-tags-descriptor-skipped #(execute! :m/bad-patch :bp1))]
      (is (= [:pending true] [(:status (instance :bp1)) (some? @last-managed-args)]) "the write lowered")
      (is (= :m/bad-patch (:mutation warn)) "the warning names the mutation")
      (is (= [nil true] [(touched article-key) (touched feed-key)]))))
  (doseq [[id descriptors] [[:m/bad-tags [{:scope :rf.scope/global :tags :not-a-coll
                                            :patch (fn [d] (assoc d :touched2 true))}]]
                            [:m/bad-shape [:not-a-map]]]]
    (reset! last-managed-args nil)
    (rf/reg-mutation id
      {:scope :rf.scope/global
       :params-schema [:map [:slug :string]]
       :optimistic-tags (fn [_p] descriptors)}
      (fn [_p _] {:request {:method :post :url "/fav2"}}))
    (execute! id id)
    (is (= [:pending true] [(:status (instance id)) (some? @last-managed-args)]) (str id))))

(defn- record-error-records!
  "Run `body-fn` with an always-on `:errors` listener installed; return every
  error record fanned during it."
  [body-fn]
  (let [seen (atom [])
        k    ::error-record-recorder]
    (rf.error-emit/register-error-listener! k (fn [rec] (swap! seen conj rec)))
    (try (body-fn)
         (finally (rf.error-emit/unregister-error-listener! k)))
    @seen))

(deftest vector-shaped-optimistic-target-refuses-readably
  ;; An exact target is an assertion of cache identity, so the [id params]
  ;; vector spelling is refused before the request lowers or an instance is
  ;; minted (unlike a tags descriptor, which warn-and-skips). The cache-state
  ;; rows alone cannot tell a refusal from a silent no-op, so the always-on
  ;; error record is read too.
  (reg-article-resource!)
  (own-loaded! article-owned {:article {:favorited false :favoritesCount 9}})
  (rf/reg-mutation :m/fav-vector
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :optimistic (fn [{:keys [slug]}]
                   {[:r/article {:slug slug}]
                    (fn [a] (update-in a [:article :favoritesCount] inc))})}
    favorite-plan-request)
  (let [recs (record-error-records! #(execute! :m/fav-vector :fv1))
        rec  (first (filterv #(= :rf.error/handler-exception (:error %)) recs))
        data (ex-data (:exception rec))]
    (is (= [nil nil :idle] [@last-managed-args (instance :fv1) (:status (mutation-state :fv1))])
        "nothing sent, no instance minted, the passive read answers :idle")
    (is (= [9 nil] [(count-of article-key) (entry [:r/article {:slug "w"}])])
        "no paint under a guessed identity, and the vector is not a cache key of its own")
    (is (= :rf.mutation/execute (:event-id rec)) "an always-on error record was fanned")
    (is (= {:rf.error/id :rf.error/mutation-invalid-target :arm :optimistic
            :recovery :fix-mutation-target}
           (select-keys data [:rf.error/id :arm :recovery])))
    (is (str/includes? (str (:target data)) ":r/article") "it quotes the target as typed")))
