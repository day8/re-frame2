(ns re-frame.resources-inflight-invalidation-cljs-test
  "An invalidation that lands while a read is in flight is NOT satisfied by
  that read's reply (Spec 016 §Race and in-flight semantics). A mark on the
  entry's current tags survives the success of the attempt it landed during
  (judged by attempt identity, never milliseconds); an unmatched one is
  resolved against the tags the reply produces. An owned entry left stale gets
  one follow-up refetch; an owner-free one keeps the mark only."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private requests (atom []))

(defn- capturing-fixture [f]
  (reset! requests [])
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! requests conj args) nil))
  (rf.fx/reg-fx :rf.http/managed-abort (fn [_ctx _work-id] nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ _] nil))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-fixture)

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [k] (get-in (runtime-db) (rf.resources.state/entry-path k)))
(defn- stale? [k] (rf.resources.state/entry-stale? (entry k) 0))
(defn- request-count [] (count @requests))

(defn- dispatch-at!
  "Dispatch `ev`, pinning the causal `:rf/time-ms` when `time-ms` is given."
  [ev time-ms]
  (if time-ms
    (rf/dispatch-sync ev {:rf.cofx {:rf/time-ms time-ms}})
    (rf/dispatch-sync ev)))

(defn- succeed!
  ([args data] (succeed! args data nil))
  ([args data time-ms]
   (dispatch-at! (conj (:on-success args) {:status :ok :value data}) time-ms)))

(def ^:private scope :rf.scope/global)

(defn- reg-article! []
  (rf/reg-resource :fi/article
    {:scope scope
     :params-schema [:map [:slug :string]]
     :tags (fn [{:keys [slug]} _data] #{[:article slug]})}
    (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})))

(def ^:private article-q {:resource :fi/article :scope scope :params {:slug "a"}})
(def ^:private article-key (rf.resources.state/scoped-resource-key scope :fi/article {:slug "a"}))

(defn- invalidate!
  ([tags] (invalidate! scope tags nil))
  ([s tags time-ms]
   (dispatch-at! [:rf.resource/invalidate-tags {:scope s :tags tags}] time-ms)))

(deftest owned-first-load-invalidated-in-flight-stays-stale-and-refetches-once
  (reg-article!)
  (rf/dispatch-sync [:rf.resource/ensure (assoc article-q :owner [:test :r1])])
  (let [first-load (last @requests)]
    (invalidate! #{[:article "a"]})
    (is (= [#{} nil 1] [(:tags (entry article-key)) (:invalidated-at (entry article-key)) (request-count)])
        "no tags yet, so nothing is marked or refetched at invalidation time")
    (succeed! first-load {:title "pre-mutation"})
    (is (= [true {:title "pre-mutation"} 2]
           [(stale? article-key) (:data (entry article-key)) (request-count)])
        "the pre-invalidation reply leaves it stale, with one follow-up refetch")
    (rf/dispatch-sync [:rf.resource/ensure (assoc article-q :owner [:test :r1b])])
    (is (= [2 :fetching] [(request-count) (:status (entry article-key))])
        "a later ensure joins the follow-up rather than fresh-skipping")
    (succeed! (last @requests) {:title "post-mutation"})
    (is (= [false {:title "post-mutation"} 2]
           [(stale? article-key) (:data (entry article-key)) (request-count)])
        "no loop: the follow-up settles fresh and issues nothing further")))

(deftest owner-free-refresh-invalidated-in-flight-keeps-the-mark-only
  (reg-article!)
  (rf/dispatch-sync [:rf.resource/ensure article-q])
  (succeed! (last @requests) {:title "v1"})
  (rf/dispatch-sync [:rf.resource/refetch article-q])
  (let [refresh (last @requests)]
    (invalidate! #{[:article "a"]})
    (is (= [true 2] [(stale? article-key) (request-count)]) "marked stale at once, not refetched")
    (succeed! refresh {:title "pre-mutation"})
    (is (= [true 2] [(stale? article-key) (request-count)])
        "the mark written during the attempt survives its success; no follow-up")
    (rf/dispatch-sync [:rf.resource/ensure article-q])
    (is (= 3 (request-count)) "the next ensure does not fresh-skip")))

(deftest a-reply-that-produces-the-invalidated-tag-is-stale
  (rf/reg-resource :fi/item
    {:scope scope
     :params-schema [:map [:slot :string]]
     :tags (fn [_params data] #{[:item (:id data)]})}
    (fn [_ _] {:request {:method :get :url "/item"}}))
  (let [q {:resource :fi/item :scope scope :params {:slot "x"} :owner [:test :r3]}
        k (rf.resources.state/scoped-resource-key scope :fi/item {:slot "x"})]
    (rf/dispatch-sync [:rf.resource/ensure q])
    (succeed! (last @requests) {:id 1})
    (is (= #{[:item 1]} (:tags (entry k))))
    (rf/dispatch-sync [:rf.resource/refetch q])
    (let [refresh (last @requests)]
      (invalidate! #{[:item 2]})
      (is (= [false 2] [(stale? k) (request-count)]) "no match on the old tags: nothing marked now")
      (succeed! refresh {:id 2})
      (is (= [#{[:item 2]} true 3] [(:tags (entry k)) (stale? k) (request-count)])
          "the tags the reply produced match the recorded invalidation; one follow-up"))))

(deftest infinite-first-page-invalidated-in-flight-stays-stale
  (rf/reg-resource :fi/feed
    {:scope scope
     :infinite true
     :params-schema [:map [:filter :keyword]]
     :next-page-param (fn [_last-page _all] nil)
     :tags (fn [{:keys [filter]} _data] #{[:feed filter]})}
    (fn [_ _] {:request {:method :get :url "/feed"}}))
  (let [q {:resource :fi/feed :scope scope :params {:filter :recent} :owner [:test :r4]}
        k (rf.resources.state/scoped-resource-key scope :fi/feed {:filter :recent})]
    (rf/dispatch-sync [:rf.resource/ensure q])
    (let [page-0 (last @requests)]
      (invalidate! #{[:feed :recent]})
      (succeed! page-0 [:pre-mutation])
      (is (= [true [[:pre-mutation]] 2] [(stale? k) (:data (entry k)) (request-count)])
          "stale, with one follow-up refetch for the owned feed"))))

(deftest a-conflict-rollback-mark-survives-the-read-in-flight
  (reg-article!)
  (rf/reg-mutation :fi/rename
    {:scope scope
     :params-schema [:map [:slug :string]]
     :optimistic (fn [{:keys [slug]}]
                   {{:resource :fi/article :params {:slug slug} :scope scope}
                    (fn [a] (assoc a :title "BAD"))})}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.resource/ensure (assoc article-q :owner [:test :r5])])
  (succeed! (last @requests) {:title "Old"})
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :fi/rename :params {:slug "a"} :instance :r5}])
  (let [mutation (last @requests)]
    (rf/dispatch-sync [:rf.resource/refetch article-q])
    (let [read (last @requests)]
      ;; the last owner goes: the entry is owner-free, and the release moves its
      ;; :revision, so the mutation's rollback meets a conflict (:invalidate)
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:test :r5]}])
      (rf/dispatch-sync (conj (:on-failure mutation)
                              {:status :error :error {:kind :rf.http/http-5xx :status 500}}))
      (is (stale? article-key) "the conflict rollback marked the key stale")
      (let [n (request-count)]
        (succeed! read {:title "Old"})
        (is (= [true n] [(stale? article-key) (request-count)])
            "the read in flight when the mark landed does not clear it; owner-free, no follow-up")))))

(deftest c1-an-unrelated-tag-does-not-stale-an-in-flight-load
  (reg-article!)
  (rf/dispatch-sync [:rf.resource/ensure (assoc article-q :owner [:test :c1])])
  (let [first-load (last @requests)]
    (invalidate! #{[:article "zzz"]})
    (succeed! first-load {:title "v1"})
    (is (= [false 1] [(stale? article-key) (request-count)]) "a tag the reply does not produce is discarded")))

(deftest c2-an-invalidation-in-another-scope-has-no-effect
  (reg-article!)
  (rf/dispatch-sync [:rf.resource/ensure (assoc article-q :owner [:test :c2])])
  (let [first-load (last @requests)]
    (invalidate! [:rf.scope/session {:username "someone-else"}] #{[:article "a"]} nil)
    (succeed! first-load {:title "v1"})
    (is (= [false 1] [(stale? article-key) (request-count)]))))

(deftest c4-a-same-millisecond-invalidation-still-counts
  (reg-article!)
  (dispatch-at! [:rf.resource/ensure article-q] 1000)
  (succeed! (last @requests) {:title "v1"} 1000)
  (dispatch-at! [:rf.resource/refetch article-q] 2000)
  (let [refresh (last @requests)]
    (invalidate! scope #{[:article "a"]} 2000)
    (succeed! refresh {:title "pre-mutation"} 2000)
    (is (= [true 2000] [(stale? article-key) (:invalidated-at (entry article-key))])
        "attempt identity, not ms: an invalidation at the attempt's own start time is not covered")))
