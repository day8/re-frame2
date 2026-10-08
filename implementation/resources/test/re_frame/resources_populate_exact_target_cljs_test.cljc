(ns re-frame.resources-populate-exact-target-cljs-test
  "Populate is an authoritative load, and `:populates` / `:patches` take
  map-form exact targets (Spec 016 §Populate is an authoritative load,
  §Map-form exact resource targets). A `{:from-db …}` target scope resolves at
  settle time and fails closed on nil; a populated key is exempt from the same
  mutation's invalidation refetch unless a descriptor sets
  `:refetch-populated? true`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
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
  (rf/reg-event :t/login (fn [{:keys [db]} [_ username]] {:db (assoc-in db [:auth :user :username] username)})))

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
(defn- instance [instance-id]
  (get-in (runtime-db) [:rf.runtime/mutations (rf.resources.state/key-id instance-id)]))

(defn- reply-success! [args result]
  (rf/dispatch-sync (conj (:on-success args) {:status :ok :value result})))

(def ^:private global-article-key
  (rf.resources.state/scoped-resource-key :rf.scope/global :r/article {:slug "w"}))

(defn- session-feed-key [u]
  (rf.resources.state/scoped-resource-key [:rf.scope/session {:username u}] :r/feed {}))

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

(defn- populates-article [{:keys [slug]} result]
  {{:resource :r/article :params {:slug slug} :scope :rf.scope/global} result})

(defn- own-loaded!
  "Ensure and load an entry with an active owner, so an invalidation would refetch it."
  [payload]
  (rf/dispatch-sync [:rf.resource/ensure payload])
  (reply-success! @last-managed-args {:seed true})
  (reset! last-managed-args nil))

(defn- own-loaded-article! []
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :detail]}))

(defn- succeeded-trace
  "Run `body-fn`; return the last `:rf.mutation/succeeded` trace's `:tags`."
  [body-fn]
  (let [seen (atom [])
        k    ::succeeded-recorder]
    (rf.trace.tooling/register-listener!
      k (fn [ev] (when (= :rf.mutation/succeeded (:operation ev)) (swap! seen conj ev))))
    (try (body-fn) (finally (rf.trace.tooling/unregister-listener! k)))
    (:tags (last @seen))))

(defn- execute-and-settle! [mutation-id instance-id result]
  (rf/dispatch-sync [:rf.mutation/execute {:mutation mutation-id :params {:slug "w"} :instance instance-id}])
  (reply-success! @last-managed-args result))

(deftest map-form-from-db-targets-resolve-at-settle
  ;; The session switches between execute (zed) and settle (yan), so both
  ;; writes land under yan's session.
  (reg-feed-resource!)
  (rf/reg-resource :r/profile
    {:scope {:from-db :t/session}
     :params-schema [:map]}
    (fn [_p _] {:request {:method :get :url "/profile"}}))
  (doseq [u ["zed" "yan"]]
    (own-loaded! {:resource :r/profile :scope [:rf.scope/session {:username u}]
                  :params {} :owner [:v :profile u]}))
  (rf/reg-mutation :m/save-feed
    {:scope :rf.scope/global
     :params-schema [:map]
     :patches   (fn [_p _result]
                  {{:resource :r/profile :params {} :scope {:from-db :t/session}}
                   (fn [old _] (assoc old :saved true))})
     :populates (fn [_p result]
                  {{:resource :r/feed :params {} :scope {:from-db :t/session}} result})}
    (fn [_p _] {:request {:method :put :url "/feed"}}))
  (rf/dispatch-sync [:t/login "zed"])
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save-feed :params {} :instance :pf1}])
  (let [reply-args @last-managed-args]
    (rf/dispatch-sync [:t/login "yan"])
    (reply-success! reply-args {:articles [:x]}))
  (let [profile-key (fn [u] (rf.resources.state/scoped-resource-key
                              [:rf.scope/session {:username u}] :r/profile {}))]
    (is (= {:status :loaded :data {:articles [:x]}}
           (select-keys (entry (session-feed-key "yan")) [:status :data])))
    (is (nil? (entry (session-feed-key "zed"))))
    (is (= [{:seed true :saved true} {:seed true}]
           [(:data (entry (profile-key "yan"))) (:data (entry (profile-key "zed")))]))))

(deftest populated-key-exempt-from-same-mutation-refetch
  ;; The mutation populates the detail key, then invalidates a tag that matches
  ;; it: the detail stays fresh, the other tagged entry refetches.
  (reg-article-resource!)
  (own-loaded-article!)
  (rf/reg-resource :r/article-list
    {:scope :rf.scope/global
     :params-schema [:map]
     :tags (fn [_p _] #{[:article-list]})}
    (fn [_p _] {:request {:method :get :url "/articles"}}))
  (own-loaded! {:resource :r/article-list :scope :rf.scope/global :params {} :owner [:v :list]})
  (rf/reg-mutation :m/favorite
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :populates populates-article
     :invalidates (fn [_p _r] #{[:article-list]})}
    (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug "/fav")}}))
  (let [trace (succeeded-trace
                #(execute-and-settle! :m/favorite :f1 {:slug "w" :title "fav'd" :favorited true}))
        e     (entry global-article-key)]
    (is (= {:status :loaded :data {:slug "w" :title "fav'd" :favorited true}}
           (select-keys e [:status :data])))
    (is (nil? (:invalidated-at e)))
    (is (contains? #{:loading :fetching}
                   (:status (entry (rf.resources.state/scoped-resource-key :rf.scope/global :r/article-list {})))))
    (is (= [global-article-key] (:populate-exempt (:invalidation trace))))))

(deftest refetch-populated-opts-back-into-same-mutation-refetch
  (reg-article-resource!)
  (own-loaded-article!)
  (rf/reg-mutation :m/save
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :populates populates-article
     :invalidates (fn [{:keys [slug]} _r]
                    [{:scope :rf.scope/global :tags #{[:article slug]} :refetch-populated? true}])}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (execute-and-settle! :m/save :rp1 {:slug "w" :title "partial"})
  (is (contains? #{:loading :fetching} (:status (entry global-article-key))))
  (is (= {:method :get :url "/a/w"} (:request @last-managed-args))))

(deftest mixed-descriptor-populate-exempt-not-collapsed-by-one-opt-in
  ;; Exemption is per descriptor: the default descriptor still spares the
  ;; populated key, so the evidence must not collapse to [] because another
  ;; descriptor opted in.
  (reg-article-resource!)
  (own-loaded-article!)
  (rf/reg-mutation :m/save
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :populates populates-article
     :invalidates (fn [{:keys [slug]} _r]
                    [{:scope :rf.scope/global :tags #{[:article-list]} :refetch-populated? true}
                     {:scope :rf.scope/global :tags #{[:article slug]}}])}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (let [inv (:invalidation (succeeded-trace #(execute-and-settle! :m/save :mx1 {:slug "w" :title "fresh"})))]
    (is (= {true [] false [global-article-key]}
           (into {} (map (juxt (comp boolean :refetch-populated?) :exempt-keys)) (:dispatched inv))))
    (is (= [global-article-key] (:populate-exempt inv)))))

(deftest stale-settle-does-not-populate
  (reg-article-resource!)
  (rf/reg-mutation :m/save
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :populates populates-article}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :s1}])
  (let [stale-args @last-managed-args]
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :s1}])
    (reply-success! stale-args {:slug "w" :title "stale"})
    (is (nil? (entry global-article-key)))))

(deftest from-db-populate-target-nil-fails-closed
  ;; Not logged in, so {:from-db :t/session} resolves nil: the target is
  ;; dropped, never seeded under an implicit global.
  (reg-feed-resource!)
  (rf/reg-mutation :m/save-feed
    {:scope :rf.scope/global
     :params-schema [:map]
     :populates (fn [_p result]
                  {{:resource :r/feed :params {} :scope {:from-db :t/session}} result})}
    (fn [_p _] {:request {:method :put :url "/feed"}}))
  (let [trace (succeeded-trace
                #(do (rf/dispatch-sync [:rf.mutation/execute
                                        {:mutation :m/save-feed :params {} :instance :n1}])
                     (reply-success! @last-managed-args {:articles [:x]})))]
    (is (nil? (entry (rf.resources.state/scoped-resource-key :rf.scope/global :r/feed {}))))
    (is (= [:t/session] (:target-unresolved (:patch-summary trace))))))

(deftest same-key-patch-populate-overlap-populate-wins
  ;; The success plan applies patches, then populates: a key written by both
  ;; ends with the populate's seed, not a patch-transformed one.
  (reg-article-resource!)
  (rf/reg-mutation :m/patch-and-populate
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :patches (fn [{:keys [slug]} _result]
                {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                 (fn [old _r] (assoc old :winner "patch"))})
     :populates (fn [{:keys [slug]} result]
                  {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                   (assoc result :winner "populate")})}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/article :scope :rf.scope/global
                                          :params {:slug "w"} :owner [:v :a]}])
  (reply-success! @last-managed-args {:slug "w" :title "seeded" :winner "seed"})
  (execute-and-settle! :m/patch-and-populate :ov1 {:slug "w"})
  (is (= {:status :loaded :data {:slug "w" :winner "populate"}}
         (select-keys (entry global-article-key) [:status :data])))
  (is (= {:patched [global-article-key] :populated [global-article-key]}
         (select-keys (:patch-summary (instance :ov1)) [:patched :populated]))
      "both arms engaged the same key"))

;; ---- an authoritative write supersedes a read in flight ---------------------
;; Otherwise the read's reply, answered before the server committed the write,
;; would pass the generation gate and revert the written value.

(defn- write-over-read-in-flight!
  "Load the article as v1, refetch so a read is in flight, then settle
  `mutation-id`'s write (v2) while that read is pending."
  [mutation-id]
  (let [aborts (atom [])]
    (rf.fx/reg-fx :rf.http/managed-abort
                  (fn [_ctx request-id] (swap! aborts conj request-id) nil))
    (rf/dispatch-sync [:rf.resource/ensure {:resource :r/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:v :a]}])
    (reply-success! @last-managed-args {:slug "w" :title "v1"})
    (rf/dispatch-sync [:rf.resource/refetch {:resource :r/article :scope :rf.scope/global
                                             :params {:slug "w"}}])
    (let [read-args @last-managed-args
          in-flight (:status (entry global-article-key))]
      (execute-and-settle! mutation-id :race1 {:slug "w" :title "v2"})
      {:read-args read-args :in-flight-status in-flight :aborts aborts})))

(defn- read-superseded-by-write? [{:keys [read-args in-flight-status aborts]}]
  (is (= :fetching in-flight-status) "precondition: a read was in flight")
  (is (= ["v2" :loaded nil]
         ((juxt (comp :title :data) :status :current-work) (entry global-article-key)))
      "coherent: :loaded with no read owning the entry")
  (is (= 1 (count @aborts)) "one best-effort abort, for the superseded read")
  (reply-success! read-args {:slug "w" :title "v1"})
  (let [e (entry global-article-key)]
    (is (= ["v2" :loaded nil] [(:title (:data e)) (:status e) (:invalidated-at e)]))))

(deftest populate-supersedes-a-read-in-flight
  (reg-article-resource!)
  (rf/reg-mutation :m/save
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :populates populates-article}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (read-superseded-by-write? (write-over-read-in-flight! :m/save)))

(deftest patch-supersedes-a-read-in-flight
  (reg-article-resource!)
  (rf/reg-mutation :m/patch
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :patches (fn [{:keys [slug]} _result]
                {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                 (fn [old result] (merge old result))})}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (read-superseded-by-write? (write-over-read-in-flight! :m/patch)))
