(ns re-frame.resources-optimistic-supersession-cljs-test
  "A superseded or cleared PENDING optimistic apply is rolled back, not
  forgotten (Spec 016 §Optimistic settle). A same-instance re-execute paints on
  the current value and inherits the superseded pre-paint :before; a rollback
  whose baseline came from an abandoned attempt restores it, marks the key
  stale and refetches it when owned, because the abandoned write may still
  have reached the server."
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
   [re-frame.trace.tooling :as rf.trace.tooling]
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

(def ^:private scope :rf.scope/global)
(def ^:private instance-id [:favorite "w"])
(def ^:private article-q {:resource :sp/article :scope scope :params {:slug "w"}})
(def ^:private article-key (rf.resources.state/scoped-resource-key scope :sp/article {:slug "w"}))
(def ^:private unpainted {:favorited false :favoritesCount 5})

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

(defn- succeed! [args value]
  (dispatch-at! (conj (:on-success args) {:status :ok :value value}) nil))

(defn- fail!
  ([args] (fail! args nil))
  ([args time-ms]
   (dispatch-at! (conj (:on-failure args)
                       {:status :error :error {:kind :rf.http/http-4xx :status 401}})
                 time-ms)))

(defn- toggle
  "The forward patch: set :favorited, nudge the count by 1 either way."
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
  "Register the article and the two mutations; load the article unpainted,
  owned unless `owner` is nil."
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
   (succeed! (last @requests) {:article unpainted})))

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

(deftest re-execute-both-rejected-restores-the-pre-paint-value-stale
  (setup!)
  (let [click-1 (click! :sp/favorite)
        _       (is (= {:favorited true :favoritesCount 6} (article)))
        click-2 (click! :sp/unfavorite)]
    (is (= unpainted (article)) "the successor paints on the current value: 5, not 4")
    (fail! click-1 7000)
    (is (= unpainted (article)) "the superseded reply is stale-suppressed: it writes nothing")
    (let [before-reads (reads)]
      (fail! click-2 8000)
      (is (= [unpainted true 8000 :error 1]
             [(article) (stale?) (:invalidated-at (entry)) (:status (instance)) (- (reads) before-reads)])
          "restored to the superseded pre-paint value, stale at the rejecting reply's time, refetched once"))))

(deftest clear-while-pending-rolls-back-and-stales
  (setup!)
  (click! :sp/favorite)
  (let [snapshot-id  (-> (instance) :patch-summary :snapshot-id)
        before-reads (reads)
        rolled       (traces-of :rf.mutation/optimistic-rolled-back
                       #(rf/dispatch-sync [:rf.mutation/clear {:instance instance-id}]))]
    (is (= [unpainted true nil 1] [(article) (stale?) (instance) (- (reads) before-reads)])
        "rolled back, stale, the instance row gone, the owned key refetched")
    (is (= [[snapshot-id] [article-key]] [(mapv :snapshot-id rolled) (:restored (first rolled))])
        "the rollback names the cleared apply's snapshot")))

(deftest a-non-optimistic-successor-rolls-the-superseded-apply-back-at-execute
  (setup!)
  (click! :sp/favorite)
  (let [before-reads (reads)]
    (click! :sp/unfavorite {:optimistic? false})
    (is (= [unpainted true 1] [(article) (stale?) (- (reads) before-reads)]))))

(deftest a-three-attempt-chain-restores-the-original-baseline
  (setup!)
  (click! :sp/favorite)
  (click! :sp/unfavorite)
  (let [click-3 (click! :sp/favorite)]
    (is (= {:favorited true :favoritesCount 6} (article)))
    (fail! click-3)
    (is (= [unpainted true] [(article) (stale?)]))))

(deftest c3-a-successful-successor-commits-without-a-stale-mark
  (setup!)
  (click! :sp/favorite)
  (let [click-2 (click! :sp/unfavorite)]
    (succeed! click-2 {:article unpainted})
    (is (= [unpainted false :success] [(article) (stale?) (:status (instance))])
        "no stale mark lands on a populated key")))

(deftest c4-no-blind-restore-across-an-authoritative-write
  (setup!)
  (click! :sp/favorite)
  ;; an authoritative read lands between the clicks: the server has click 1
  (rf/dispatch-sync [:rf.resource/refetch article-q])
  (succeed! (last @requests) {:article {:favorited true :favoritesCount 6}})
  (let [click-2 (click! :sp/unfavorite)]
    (is (= unpainted (article)))
    (fail! click-2)
    (is (= [{:favorited true :favoritesCount 6} true] [(article) (stale?)])
        "the successor restores its own post-write snapshot, never click 1's")))

(deftest c5-an-owner-free-restored-key-stays-stale-through-a-read-in-flight
  (setup! nil)
  (click! :sp/favorite)
  (rf/dispatch-sync [:rf.resource/refetch article-q])
  (let [read (last @requests)]
    (rf/dispatch-sync [:rf.mutation/clear {:instance instance-id}])
    (is (stale?))
    (succeed! read {:article unpainted})
    (is (stale?) "the read in flight when the mark landed does not clear it")))
