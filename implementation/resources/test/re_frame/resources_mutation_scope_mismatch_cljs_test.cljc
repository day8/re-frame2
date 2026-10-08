(ns re-frame.resources-mutation-scope-mismatch-cljs-test
  "`:rf.warning/mutation-scope-mismatch` (Spec 016 §Dev-mode write-side
  tripwire): a global-default invalidation that matches nothing in its own
  scope, while the tag's entry lives in another scope, warns once."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
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

(defn- reply-success! [args result]
  (rf/dispatch-sync (conj (:on-success args) {:status :ok :value result})))

(defn- session-feed-key [u] (rf.resources.state/scoped-resource-key [:rf.scope/session {:username u}] :r/feed {}))

(defn- reg-feed-resource! []
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
  "Leave jake's session feed :loaded and ownerless, so an invalidation is
  observable as :invalidated-at rather than a refetch."
  []
  (rf/dispatch-sync [:rf.resource/ensure {:resource :r/feed :scope {:from-db :t/session}
                                          :params {} :owner [:v :feed]}])
  (reply-success! @last-managed-args {:seed true})
  (rf/dispatch-sync [:rf.resource/release-owner {:resource :r/feed :scope {:from-db :t/session}
                                                 :params {} :owner [:v :feed]}])
  (reset! last-managed-args nil))

(defn- record-warnings! [body-fn]
  (let [seen (atom [])
        k    ::warn-recorder]
    (rf.trace.tooling/register-listener!
      k (fn [ev] (when (= :rf.warning/mutation-scope-mismatch (:operation ev))
                   (swap! seen conj ev))))
    (try (body-fn) (finally (rf.trace.tooling/unregister-listener! k)))
    @seen))

(deftest global-default-mutation-misses-session-scoped-resource-warns
  (reg-feed-resource!)
  (rf/dispatch-sync [:t/login "jake"])
  (seed-ownerless-session-feed!)
  ;; No :scope and a bare tag set: the invalidation runs in the global scope.
  (rf/reg-mutation :m/post
    {:params-schema [:map]
     :invalidates (fn [_p _result] #{[:feed]})}
    (fn [_p _] {:request {:method :post :url "/feed"}}))
  (let [warnings (record-warnings!
                   (fn []
                     (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/post
                                                              :params {} :instance :m1}])
                     (reply-success! @last-managed-args {:ok true})))]
    (is (nil? (:invalidated-at (entry (session-feed-key "jake")))))
    (is (= 1 (count warnings)))
    (let [w (first warnings)]
      (is (= {:mutation :m/post :instance :m1 :descriptor-scope :rf.scope/global
              :mutation-scope :rf.scope/global :other-scope [:rf.scope/session {:username "jake"}]
              :tags [[:feed]]}
             (select-keys (:tags w) [:mutation :instance :descriptor-scope :mutation-scope
                                     :other-scope :tags])))
      (is (= :fix-scope (or (:recovery (:tags w)) (:recovery w)))))))

(deftest tag-with-no-entry-anywhere-does-not-warn
  ;; Nothing in any scope is a true nothing-to-invalidate, not a mismatch.
  (reg-feed-resource!)
  (rf/dispatch-sync [:t/login "jake"])
  (rf/reg-mutation :m/post
    {:params-schema [:map]
     :invalidates (fn [_p _result] #{[:feed]})}
    (fn [_p _] {:request {:method :post :url "/feed"}}))
  (let [warnings (record-warnings!
                   (fn []
                     (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/post
                                                              :params {} :instance :n1}])
                     (reply-success! @last-managed-args {:ok true})))]
    (is (empty? warnings))))

(deftest cross-scope-descriptor-does-not-warn
  (reg-feed-resource!)
  (rf/dispatch-sync [:t/login "jake"])
  (seed-ownerless-session-feed!)
  (rf/reg-mutation :m/post
    {:params-schema [:map]
     :invalidates (fn [_p _result] [{:cross-scope? true :tags #{[:feed]}}])}
    (fn [_p _] {:request {:method :post :url "/feed"}}))
  (let [warnings (record-warnings!
                   (fn []
                     (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/post
                                                              :params {} :instance :c1}])
                     (reply-success! @last-managed-args {:ok true})))]
    (is (some? (:invalidated-at (entry (session-feed-key "jake")))))
    (is (empty? warnings))))

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
                     (doseq [n [:a1 :a2 :a3]]
                       (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/post
                                                                :params {} :instance n}])
                       (reply-success! @last-managed-args {:ok true}))))]
    (is (= 1 (count warnings)))))

(deftest per-target-descriptor-safe-pattern-does-not-warn
  ;; Each per-target descriptor hits its entry in its own scope.
  (reg-global-article-resource!)
  (reg-feed-resource!)
  (rf/dispatch-sync [:t/login "jake"])
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
    (is (some? (:invalidated-at (entry (rf.resources.state/scoped-resource-key
                                         :rf.scope/global :r/article {:slug "w"})))))
    (is (some? (:invalidated-at (entry (session-feed-key "jake")))))
    (is (empty? warnings))))
