(ns re-frame.resources-optimistic-supersession-cljs-test
  "A superseded or cleared PENDING optimistic apply is rolled back, not
  forgotten (Spec 016 §Optimistic settle; EP-0019 Decision 3 amendment).

  Flagship-shaped: favorite then unfavorite under ONE instance id, each an
  optimistic toggle of `:favorited` plus a ±1 nudge of `:favoritesCount`.

    - a same-instance RE-EXECUTE paints on the CURRENT value (the in-flight
      delta stays right) and INHERITS the superseded pre-paint `:before` for
      the keys it re-touches; keys it does not re-touch roll back at once;
    - `:rf.mutation/clear` of a pending apply rolls it back;
    - a rollback whose baseline came from an abandoned attempt restores the
      pre-paint snapshot, marks the key STALE, and refetches it when owned —
      the abandoned write may still have reached the server. An ordinary
      single-attempt rollback stays exact.

  This is recovery, not write ordering: a re-execute does not abort the
  earlier request."
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
   [re-frame.trace.tooling :as rf.trace.tooling]
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

(def ^:private scope :rf.scope/global)
(def ^:private instance-id [:favorite "w"])
(def ^:private article-q {:resource :sp/article :scope scope :params {:slug "w"}})
(def ^:private article-key (rf.resources.state/scoped-resource-key scope :sp/article {:slug "w"}))

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [] (get-in (runtime-db) (rf.resources.state/entry-path article-key)))
(defn- article [] (:article (:data (entry))))
(defn- stale? [] (rf.resources.state/entry-stale? (entry) 0))
(defn- instance [] (get-in (runtime-db) [:rf.runtime/mutations (rf.resources.state/key-id instance-id)]))
(defn- reads
  "How many resource GETs the capturing transport has seen."
  []
  (count (filter #(= :get (get-in % [:request :method])) @requests)))

(defn- dispatch-at! [ev time-ms]
  (if time-ms
    (rf/dispatch-sync ev {:rf.cofx {:rf/time-ms time-ms}})
    (rf/dispatch-sync ev)))

(defn- succeed!
  ([args value] (succeed! args value nil))
  ([args value time-ms]
   (dispatch-at! (conj (:on-success args) {:status :ok :value value}) time-ms)))

(defn- fail!
  ([args] (fail! args nil))
  ([args time-ms]
   (dispatch-at! (conj (:on-failure args)
                       {:status :error :error {:kind :rf.http/http-4xx :status 401}})
                 time-ms)))

(defn- toggle
  "The flagship's forward patch: set `:favorited`, nudge the count by ±1."
  [favorited? data]
  (update data :article
          #(-> % (assoc :favorited favorited?)
               (update :favoritesCount (fn [n] (+ n (if favorited? 1 -1)))))))

(defn- fav-mutation [favorited?]
  {:scope scope
   :params-schema [:map [:slug :string]]
   :optimistic-tags (fn [{:keys [slug]}]
                      [{:scope scope :tags #{[:article slug]} :patch #(toggle favorited? %)}])
   :populates (fn [{:keys [slug]} result]
                {{:resource :sp/article :params {:slug slug} :scope scope} result})})

(defn- setup!
  "Register the article + the two mutations; load the article
  `{:favorited false :favoritesCount 5}`, owned unless `owner` is nil."
  ([] (setup! [:view :detail]))
  ([owner]
   (rf/reg-resource :sp/article
     {:scope scope
      :params-schema [:map [:slug :string]]
      :tags (fn [{:keys [slug]} _] #{[:article slug]})}
     (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}}))
   (rf/reg-mutation :sp/favorite (fav-mutation true)
     (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug "/fav")}}))
   (rf/reg-mutation :sp/unfavorite (fav-mutation false)
     (fn [{:keys [slug]} _] {:request {:method :delete :url (str "/a/" slug "/fav")}}))
   (rf/dispatch-sync [:rf.resource/ensure (cond-> article-q owner (assoc :owner owner))])
   (succeed! (last @requests) {:article {:favorited false :favoritesCount 5}})))

(defn- click!
  "Execute `mutation` under the shared instance id; return its captured request."
  ([mutation] (click! mutation {}))
  ([mutation extra]
   (rf/dispatch-sync [:rf.mutation/execute (merge {:mutation mutation :params {:slug "w"}
                                                   :instance instance-id}
                                                  extra)])
   (last @requests)))

(defn- traces-of
  "Run `body-fn`; return every trace with `op` (its `:tags`), in order."
  [op body-fn]
  (let [seen (atom [])
        k    ::recorder]
    (rf.trace.tooling/register-listener!
      k (fn [ev] (when (= op (:operation ev)) (swap! seen conj (:tags ev)))))
    (try (body-fn) (finally (rf.trace.tooling/unregister-listener! k)))
    @seen))

;; ===========================================================================
;; R1 / C1 — favorite then unfavorite, both rejected, the stale reply first
;; ===========================================================================

(deftest re-execute-both-rejected-restores-the-pre-paint-value-stale
  (setup!)
  (let [click-1 (click! :sp/favorite)
        _       (is (= {:favorited true :favoritesCount 6} (article)))
        click-2 (click! :sp/unfavorite)]
    (testing "C1 delta: the successor paints on the CURRENT value — 5, not 4"
      (is (= {:favorited false :favoritesCount 5} (article))))
    (fail! click-1 7000)
    (is (= {:favorited false :favoritesCount 5} (article))
        "the superseded reply is still stale-suppressed: it writes nothing")
    (let [before-reads (reads)]
      (fail! click-2 8000)
      (testing "the successor's rollback restores the SUPERSEDED pre-paint value, stale"
        (is (= {:favorited false :favoritesCount 5} (article)))
        (is (stale?))
        (is (= 8000 (:invalidated-at (entry))) "stamped with the rejecting reply's time")
        (is (= :error (:status (instance)))))
      (testing "the owned key refetches exactly once"
        (is (= 1 (- (reads) before-reads)))))))

;; ===========================================================================
;; R2 — the superseded write succeeded (200, suppressed); the successor fails
;; ===========================================================================

(deftest superseded-success-then-successor-failure-ends-stale-and-refetches
  (setup!)
  (let [click-1 (click! :sp/favorite)
        click-2 (click! :sp/unfavorite)]
    (succeed! click-1 {:article {:favorited true :favoritesCount 6}})
    (is (= {:favorited false :favoritesCount 5} (article)) "the 200 is suppressed")
    (let [before-reads (reads)]
      (fail! click-2)
      (is (= {:favorited false :favoritesCount 5} (article)))
      (is (stale?) "the server may hold the superseded write: the cache revalidates")
      (is (= 1 (- (reads) before-reads))))))

;; ===========================================================================
;; R3 — clear while pending
;; ===========================================================================

(deftest clear-while-pending-rolls-back-and-stales
  (setup!)
  (click! :sp/favorite)
  (let [snapshot-id  (-> (instance) :patch-summary :snapshot-id)
        before-reads (reads)
        rolled       (traces-of :rf.mutation/optimistic-rolled-back
                       #(rf/dispatch-sync [:rf.mutation/clear {:instance instance-id}]))]
    (is (= {:favorited false :favoritesCount 5} (article)))
    (is (stale?))
    (is (nil? (instance)) "the instance row is gone")
    (is (= [snapshot-id] (mapv :snapshot-id rolled))
        "the rollback names the CLEARED apply's snapshot")
    (is (= [article-key] (:restored (first rolled))))
    (is (= 1 (- (reads) before-reads)) "the owned key refetches")))

;; ===========================================================================
;; R4 — a non-optimistic successor under the same instance
;; ===========================================================================

(deftest a-non-optimistic-successor-rolls-the-superseded-apply-back-at-execute
  (setup!)
  (click! :sp/favorite)
  (let [before-reads (reads)]
    (click! :sp/unfavorite {:optimistic? false})
    (testing "the superseded keys are restored and staled at execute time"
      (is (= {:favorited false :favoritesCount 5} (article)))
      (is (stale?))
      (is (= 1 (- (reads) before-reads))))))

;; ===========================================================================
;; R5 — a three-attempt chain restores the EARLIEST baseline
;; ===========================================================================

(deftest a-three-attempt-chain-restores-the-original-baseline
  (setup!)
  (click! :sp/favorite)
  (click! :sp/unfavorite)
  (let [click-3 (click! :sp/favorite)]
    (is (= {:favorited true :favoritesCount 6} (article)))
    (fail! click-3)
    (is (= {:favorited false :favoritesCount 5} (article)))
    (is (stale?))))

;; ===========================================================================
;; Controls
;; ===========================================================================

(deftest c2-a-single-attempt-rollback-stays-exact
  (setup!)
  (let [click-1      (click! :sp/favorite)
        before-reads (reads)]
    (fail! click-1)
    (is (= {:favorited false :favoritesCount 5} (article)))
    (is (not (stale?)) "an ordinary reply-driven rollback is exact, not staled")
    (is (= 0 (- (reads) before-reads)))))

(deftest c3-a-successful-successor-commits-as-today
  (setup!)
  (click! :sp/favorite)
  (let [click-2 (click! :sp/unfavorite)]
    (succeed! click-2 {:article {:favorited false :favoritesCount 5}})
    (is (= {:favorited false :favoritesCount 5} (article)))
    (is (not (stale?)) "no stale mark lands on a populated key")
    (is (= :success (:status (instance))))))

(deftest c4-no-blind-restore-across-an-authoritative-write
  (setup!)
  (click! :sp/favorite)
  ;; an authoritative read lands between the clicks: the server has click 1.
  (rf/dispatch-sync [:rf.resource/refetch article-q])
  (succeed! (last @requests) {:article {:favorited true :favoritesCount 6}})
  (let [click-2 (click! :sp/unfavorite)]
    (is (= {:favorited false :favoritesCount 5} (article)))
    (fail! click-2)
    (is (= {:favorited true :favoritesCount 6} (article))
        "the successor keeps its own post-write snapshot — never click 1's")
    (is (stale?))))

(deftest c5-an-owner-free-restored-key-stays-stale-through-a-read-in-flight
  (setup! nil)
  (click! :sp/favorite)
  (rf/dispatch-sync [:rf.resource/refetch article-q])
  (let [read (last @requests)]
    (rf/dispatch-sync [:rf.mutation/clear {:instance instance-id}])
    (is (stale?))
    (succeed! read {:article {:favorited false :favoritesCount 5}})
    (is (stale?) "the read in flight when the mark landed does not clear it")))
