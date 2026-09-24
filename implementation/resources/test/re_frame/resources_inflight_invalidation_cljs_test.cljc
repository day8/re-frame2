(ns re-frame.resources-inflight-invalidation-cljs-test
  "An invalidation that lands while a read is in flight is NOT satisfied by
  that read's reply (Spec 016 §Race and in-flight semantics).

  The request in flight was served before the invalidation, and no coverage
  policy exists, so its success must leave the entry stale:

    - DEFINITE — the invalidation matched the entry's current tags and marked
      it stale: the mark survives the success of the attempt it landed during
      (judged by attempt identity, never by milliseconds);
    - TENTATIVE — the tags did not match (a first load has none yet; a refresh's
      current tags describe its OLD data): the invalidation is recorded against
      the attempt and resolved against the tags its reply PRODUCES.

  A success that leaves an OWNED entry stale dispatches ONE follow-up refetch;
  an owner-free entry keeps the stale mark only. Controls pin precision (an
  unrelated tag, another scope) and that the follow-up does not loop."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
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

;; ---- capturing transport ----------------------------------------------------

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

;; ---- helpers ----------------------------------------------------------------

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

;; ===========================================================================
;; R1 — an owned FIRST load in flight (no tags yet) is invalidated
;; ===========================================================================

(deftest owned-first-load-invalidated-in-flight-stays-stale-and-refetches-once
  (reg-article!)
  (rf/dispatch-sync [:rf.resource/ensure (assoc article-q :owner [:test :r1])])
  (let [first-load (last @requests)]
    (is (= #{} (:tags (entry article-key))) "no tags until the first success")
    (invalidate! #{[:article "a"]})
    (testing "nothing is observably stale yet, and nothing refetches at invalidation time"
      (is (= 1 (request-count)))
      (is (nil? (:invalidated-at (entry article-key)))))
    (succeed! first-load {:title "pre-mutation"})
    (testing "the pre-invalidation reply does NOT satisfy the invalidation"
      (is (stale? article-key))
      (is (= {:title "pre-mutation"} (:data (entry article-key))))
      (is (= 2 (request-count)) "exactly ONE follow-up refetch for the owned entry"))
    (testing "a later ensure does not fresh-skip — it joins the follow-up"
      (rf/dispatch-sync [:rf.resource/ensure (assoc article-q :owner [:test :r1b])])
      (is (= 2 (request-count)))
      (is (= :fetching (:status (entry article-key)))))
    (testing "C3 no loop: the follow-up settles fresh and issues nothing further"
      (succeed! (last @requests) {:title "post-mutation"})
      (is (not (stale? article-key)))
      (is (= {:title "post-mutation"} (:data (entry article-key))))
      (is (= 2 (request-count))))))

;; ===========================================================================
;; R2 — an OWNER-FREE loaded entry with a refresh in flight is invalidated
;; ===========================================================================

(deftest owner-free-refresh-invalidated-in-flight-keeps-the-mark-only
  (reg-article!)
  (rf/dispatch-sync [:rf.resource/ensure article-q])
  (succeed! (last @requests) {:title "v1"})
  (rf/dispatch-sync [:rf.resource/refetch article-q])
  (let [refresh (last @requests)]
    (invalidate! #{[:article "a"]})
    (is (stale? article-key) "a matched entry is marked stale at once")
    (is (= 2 (request-count)) "owner-free: left stale, no refetch")
    (succeed! refresh {:title "pre-mutation"})
    (testing "the mark written DURING the attempt survives its success"
      (is (stale? article-key))
      (is (= 2 (request-count)) "owner-free: the stale mark only, no follow-up"))
    (testing "the next ensure does not fresh-skip"
      (rf/dispatch-sync [:rf.resource/ensure article-q])
      (is (= 3 (request-count))))))

;; ===========================================================================
;; R3 — the reply PRODUCES the invalidated tag (data-dependent tags)
;; ===========================================================================

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
      (is (not (stale? k)) "no match on the OLD tags: nothing marked now")
      (is (= 2 (request-count)))
      (succeed! refresh {:id 2})
      (is (= #{[:item 2]} (:tags (entry k))))
      (is (stale? k) "the tags the reply produced intersect the recorded invalidation")
      (is (= 3 (request-count)) "one follow-up refetch for the owned entry"))))

;; ===========================================================================
;; R4 — an infinite feed's FIRST page (page-succeeded-handler)
;; ===========================================================================

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
      (is (= 1 (request-count)))
      (succeed! page-0 [:pre-mutation])
      (is (stale? k))
      (is (= [[:pre-mutation]] (:data (entry k))))
      (is (= 2 (request-count)) "one follow-up refetch for the owned feed"))))

;; ===========================================================================
;; R5 — a rollback-driven mark (EP-0019 conflict) on an owner-free key with a
;; read in flight survives that read's success
;; ===========================================================================

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
      ;; :revision, so the mutation's rollback meets a conflict (:invalidate).
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:test :r5]}])
      (rf/dispatch-sync (conj (:on-failure mutation)
                              {:status :error :error {:kind :rf.http/http-5xx :status 500}}))
      (is (stale? article-key) "the conflict rollback marked the key stale")
      (let [n (request-count)]
        (succeed! read {:title "Old"})
        (testing "the read in flight when the mark landed does not clear it"
          (is (stale? article-key))
          (is (= n (request-count)) "owner-free: no follow-up"))))))

;; ===========================================================================
;; Controls
;; ===========================================================================

(deftest c1-an-unrelated-tag-does-not-stale-an-in-flight-load
  (reg-article!)
  (rf/dispatch-sync [:rf.resource/ensure (assoc article-q :owner [:test :c1])])
  (let [first-load (last @requests)]
    (invalidate! #{[:article "zzz"]})
    (succeed! first-load {:title "v1"})
    (is (not (stale? article-key)) "a tag the reply does not produce is discarded")
    (is (= 1 (request-count)))))

(deftest c2-an-invalidation-in-another-scope-has-no-effect
  (reg-article!)
  (rf/dispatch-sync [:rf.resource/ensure (assoc article-q :owner [:test :c2])])
  (let [first-load (last @requests)]
    (invalidate! [:rf.scope/session {:username "someone-else"}] #{[:article "a"]} nil)
    (succeed! first-load {:title "v1"})
    (is (not (stale? article-key)))
    (is (= 1 (request-count)))))

(deftest c4-a-same-millisecond-invalidation-still-counts
  (reg-article!)
  (dispatch-at! [:rf.resource/ensure article-q] 1000)
  (succeed! (last @requests) {:title "v1"} 1000)
  (dispatch-at! [:rf.resource/refetch article-q] 2000)
  (let [refresh (last @requests)]
    (invalidate! scope #{[:article "a"]} 2000)
    (succeed! refresh {:title "pre-mutation"} 2000)
    (is (stale? article-key)
        "attempt identity, not ms: an invalidation at the attempt's own start time is not covered")
    (is (= 2000 (:invalidated-at (entry article-key))))))
