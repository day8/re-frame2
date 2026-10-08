(ns re-frame.resources-optimistic-apply-cljs-test
  "Optimistic apply: the forward patch lands in the cache at execute time,
  before any reply, and the instance row records the inverse (each touched
  entry as it stood, plus its :revision) on :patch-summary. Settlement is
  `resources-optimistic-settle-cljs-test`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.mutation-registry :as rf.resources.mutation-registry]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.registrar :as rf.registrar]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private last-managed-args (atom nil))

(defn- init! []
  (rf.registrar/clear-kind! :resource-scope))

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

(def ^:private article-key
  (rf.resources.state/scoped-resource-key :rf.scope/global :r/article {:slug "w"}))

(def ^:private article-owned
  {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]})

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

(deftest optimistic-apply-patches-before-the-request-settles
  (reg-article-resource!)
  (own-loaded! article-owned {:article {:favorited false :favoritesCount 9}})
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
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
    (let [e     (entry article-key)
          ps    (patch-summary :f1)
          [inv] (:rollback ps)]
      (is (= [{:favorited true :favoritesCount 10} :loaded (inc rev-before)]
             [(get-in e [:data :article]) (:status e) (:revision e)])
          "patched before any reply, and the apply moved :revision")
      (is (= :pending (:status (instance :f1))) "an apply is not a reply")
      (is (some? (:snapshot-id ps)))
      (is (= {:resource/key article-key :revision rev-before :forward :patch}
             (select-keys inv [:resource/key :revision :forward]))
          "the inverse records the revision observed before the apply"))))

(deftest optimistic-apply-seeds-absent-key-with-absent-inverse
  (reg-article-resource!)
  (rf/reg-mutation :m/create
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :optimistic (fn [{:keys [slug]}]
                   {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                    (fn [_absent] {:article {:slug slug :favorited false}})})}
    (fn [{:keys [slug]} _] {:request {:method :post :url "/a"}}))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/create :params {:slug "w"} :instance :c1}])
  (is (= [:loaded {:article {:slug "w" :favorited false}} 1 #{[:article "w"] [:article-list]}]
         ((juxt :status :data :revision :tags) (entry article-key)))
      "the absent key is seeded :loaded at revision 1, carrying its resource's tags")
  (is (= {:before :rf.optimistic/absent :revision 0 :forward :seed}
         (select-keys (first (:rollback (patch-summary :c1))) [:before :revision :forward]))
      "the absent sentinel inverse, so the settle can remove the seed"))

(deftest optimistic-remove-tombstones-the-entry-in-place-and-records-before
  ;; A nil patch-fn writes a tombstone in place rather than dissocing the entry,
  ;; so the entry still carries the owner facts the settle needs: a dissoc'd
  ;; entry cannot record an owner releasing mid-flight.
  (reg-article-resource!)
  (own-loaded! article-owned {:article {:slug "w" :title "Doomed"}})
  (rf/reg-mutation :m/delete
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :optimistic (fn [{:keys [slug]}]
                   {{:resource :r/article :params {:slug slug} :scope :rf.scope/global} nil})}
    (fn [{:keys [slug]} _] {:request {:method :delete :url (str "/a/" slug)}}))
  (let [before-revision (:revision (entry article-key))]
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/delete :params {:slug "w"} :instance :d1}])
    (let [e (entry article-key)]
      (is (some? e) "the entry survives the optimistic remove")
      (is (= [nil :idle nil nil nil nil]
             ((juxt :data :status :error :loaded-at :stale-at :invalidated-at) e))
          "an empty, non-stale, non-error tombstone")
      (is (= [#{[:v :d]} true (inc before-revision)]
             [(:active-owners e) (boolean (seq (:tags e))) (:revision e)])
          "owners and tags ride through, and the tombstone moves :revision")))
  (let [[inv] (:rollback (patch-summary :d1))]
    (is (= [:remove {:article {:slug "w" :title "Doomed"}} (:revision (entry article-key))]
           [(:forward inv) (:data (:before inv)) (:applied-revision inv)])
        "the inverse snapshots the removed entry; its baseline is the tombstone's revision")))

(deftest optimistic-tags-patches-every-tag-matched-entry
  (reg-article-resource!)
  (rf/reg-resource :r/article-list
    {:scope :rf.scope/global
     :params-schema [:map]
     :tags (fn [_p _] #{[:article "w"] [:article-list]})}
    (fn [_p _] {:request {:method :get :url "/articles"}}))
  (own-loaded! article-owned {:article {:favorited false}})
  (own-loaded! {:resource :r/article-list :scope :rf.scope/global :params {} :owner [:v :l]}
               {:list [{:favorited false}]})
  (rf/reg-mutation :m/favorite-everywhere
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :optimistic-tags (fn [{:keys [slug]}]
                        [{:scope :rf.scope/global
                          :tags  #{[:article slug]}
                          :patch (fn [d] (assoc d :touched true))}])}
    (fn [{:keys [slug]} _] {:request {:method :post :url "/fav"}}))
  (let [list-key (rf.resources.state/scoped-resource-key :rf.scope/global :r/article-list {})]
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite-everywhere
                                             :params {:slug "w"} :instance :fe1}])
    (is (= [true true] [(get-in (entry article-key) [:data :touched])
                        (get-in (entry list-key) [:data :touched])])
        "both tag-matched entries were patched")
    (is (= {article-key 1 list-key 1}
           (frequencies (map :resource/key (:rollback (patch-summary :fe1)))))
        "each matched key recorded its own inverse")))

(deftest optimistic-false-opts-out-of-the-apply
  (reg-article-resource!)
  (own-loaded! article-owned {:article {:favorited false}})
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
    (let [e (entry article-key)]
      (is (= [false rev-before] [(get-in e [:data :article :favorited]) (:revision e)])
          "no optimistic write"))
    (is (nil? (:snapshot-id (patch-summary :f1))) "no inverse recorded")))

(deftest optimistic-with-before-request-is-a-registration-error
  (doseq [[id plan] [[:m/bad {:optimistic (fn [_p] {})}]
                     [:m/bad2 {:optimistic-tags (fn [_p] [])}]]]
    (let [ex (try
               (rf/reg-mutation id
                 (merge {:scope :rf.scope/global
                         :params-schema [:map]
                         :invalidate-timing :before-request}
                        plan)
                 (fn [_p _] {:request {:method :post :url "/x"}}))
               nil
               (catch #?(:clj Exception :cljs :default) e e))]
      (is (= :rf.error/mutation-optimistic-before-request (:rf.error/id (ex-data ex))) (str id))
      (is (nil? (rf.resources.mutation-registry/mutation-meta id)) (str id)))))
