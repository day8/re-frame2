(ns re-frame.resources-timer-rearm-cljs-test
  "The :stale, :gc and :poll timers share one host side table. The
  schedule-timers effect reconciles only the kinds its :timers map names (a
  positive delay arms, nil cancels) and preserves absent ones, so a poll tick
  or a GC skip re-arms its own kind without cancelling the others (Spec 016
  §Stale and GC scheduling / §Polling)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.interop :as rf.interop]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.resources.timers :as rf.resources.timers]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

;; The real timer side table is driven here; delays are long enough never to fire.
(def ^:private long-ms 1000000)

(defn- capturing-fixture [f]
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf.fx/reg-fx :rf.http/managed-abort (fn [_ctx _work-id] nil))
  ;; ensures here pass an explicit :scope; the resolver's slot stays unwritten
  (rf/reg-resource-scope :t/caller-scope
    {:inputs {:scope [:db [:t/scope]]}}
    (fn [{:keys [scope]} _ctx] scope))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-fixture)

(def ^:private frame-id :rf/default)
(def ^:private stale rf.resources.timers/stale-kind)
(def ^:private gc rf.resources.timers/gc-kind)
(def ^:private poll rf.resources.timers/poll-kind)

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value frame-id)))
(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))

(defn- tkey [scoped-key kind] [frame-id (rf.resources.state/key-id scoped-key) kind])
(defn- timer-handle [scoped-key kind] (get @rf.resources.timers/timer-table (tkey scoped-key kind)))
(defn- armed? [scoped-key kind] (contains? @rf.resources.timers/timer-table (tkey scoped-key kind)))

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx] {:request {:method :get :url (str "/api/articles/" slug)}}))

(defn- loaded-polling!
  "Register `resource` with poll and GC policies and load it under an owner;
  return its scoped key."
  [resource]
  (rf/reg-resource resource
                   {:scope            {:from-db :t/caller-scope}
                    :params-schema    [:map [:slug :string]]
                    :poll-interval-ms long-ms
                    :gc-after-ms      long-ms}
                   article-spec-request)
  (let [scope {:user "u"}
        k     (rf.resources.state/scoped-resource-key scope resource {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource resource :scope scope :params {:slug "w"} :owner [:route :r 1]}])
    (let [e (entry k)]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource/key k :work/id (:current-work e)
                          :generation (:generation e) :data {:title "W"}}]))
    k))

(defn- reconcile! [scoped-key timers-map]
  (rf.resources.timers/schedule-timers-handler
    nil {:frame-id frame-id :resource/key scoped-key :timers timers-map :server? false}))

(deftest poll-only-rearm-preserves-sibling-stale-and-gc
  (rf.resources.timers/reset-cache!)
  (let [k [:rf.scope/global :tr/combo {:id 1}]]
    (reconcile! k {stale long-ms gc long-ms poll long-ms})
    (is (= [true true true] (map #(armed? k %) [stale gc poll])))
    (let [stale-h (timer-handle k stale)
          gc-h    (timer-handle k gc)
          poll-h  (timer-handle k poll)]
      (reconcile! k {poll long-ms})
      (is (= [stale-h gc-h] [(timer-handle k stale) (timer-handle k gc)])
          "the unnamed stale and GC handles are untouched")
      (is (= [true true] [(armed? k poll) (not= poll-h (timer-handle k poll))])
          "the named poll kind is replaced"))
    (rf.resources.timers/cancel-for-key! frame-id k)))

(deftest full-reconcile-cancels-a-kind-whose-policy-was-removed
  ;; a later settle whose resource no longer declares a poll policy names
  ;; :poll with a nil delay
  (rf.resources.timers/reset-cache!)
  (let [k [:rf.scope/global :tr/hotreload {:id 1}]]
    (reconcile! k {stale long-ms gc long-ms poll long-ms})
    (is (armed? k poll) "precondition: poll armed")
    (reconcile! k {stale long-ms gc long-ms poll nil})
    (is (= [false true true] (map #(armed? k %) [poll stale gc]))
        "the named nil-delay kind is cancelled; the declared kinds stay armed")
    (rf.resources.timers/cancel-for-key! frame-id k)))

(deftest positive-rearm-cancels-the-prior-host-handle
  ;; The fresh token already stops the old callback from dispatching, so only
  ;; the host's cancel calls show whether the old host timer was released.
  (rf.resources.timers/reset-cache!)
  (let [k          [:rf.scope/global :tr/rearm-cancel {:id 1}]
        sibling-k  [:rf.scope/global :tr/rearm-cancel {:id 2}]
        cancelled  (atom [])
        cancelled? (fn [h] (boolean (some #(identical? h %) @cancelled)))]
    (with-redefs [rf.interop/schedule-after!   (fn [_thunk _ms] #?(:clj (Object.) :cljs #js {}))
                  rf.interop/cancel-scheduled! (fn [h] (swap! cancelled conj h) nil)]
      (rf.resources.timers/schedule! frame-id k stale long-ms)
      (rf.resources.timers/schedule! frame-id k gc long-ms)
      (rf.resources.timers/schedule! frame-id sibling-k stale long-ms)
      (let [original  (:handle (timer-handle k stale))
            gc-h      (:handle (timer-handle k gc))
            sibling-h (:handle (timer-handle sibling-k stale))]
        (is (empty? @cancelled) "precondition: arming a fresh slot cancels nothing")
        (rf.resources.timers/schedule! frame-id k stale long-ms)
        (let [replacement (:handle (timer-handle k stale))]
          (is (= {:fresh-handle true :original true :replacement false :same-key-gc false :sibling-key false}
                 {:fresh-handle (not (identical? original replacement))
                  :original     (cancelled? original)
                  :replacement  (cancelled? replacement)
                  :same-key-gc  (cancelled? gc-h)
                  :sibling-key  (cancelled? sibling-h)})
              "only the replaced host handle is cancelled")))
      ;; release the fake handles while the stub is still installed
      (rf.resources.timers/cancel-for-key! frame-id k)
      (rf.resources.timers/cancel-for-key! frame-id sibling-k))))

(deftest poll-tick-preserves-the-gc-timer
  ;; were the poll re-arm to cancel GC, an owner-free entry would never be collected
  (let [k (loaded-polling! :tre/pg)]
    (is (= [true true] [(armed? k gc) (armed? k poll)]) "precondition: the settle armed GC and poll")
    (let [gc-h (timer-handle k gc)]
      (rf/dispatch-sync [:rf.resource.internal/poll-fired {:resource/key k :hidden? false}])
      (is (= [gc-h true] [(timer-handle k gc) (armed? k poll)])
          "the GC handle is unchanged and poll re-armed"))))

(deftest gc-skip-while-owned-preserves-the-poll-timer
  ;; were the GC re-arm to cancel poll, an owned entry would silently stop refreshing
  (let [k (loaded-polling! :tre/gp)]
    (is (= [true true] [(armed? k poll) (armed? k gc)]) "precondition: the settle armed poll and GC")
    (let [poll-h (timer-handle k poll)]
      (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key k}])
      (is (= [true poll-h true] [(some? (entry k)) (timer-handle k poll) (armed? k gc)])
          "the owned entry is kept, the poll handle unchanged, GC re-armed"))))
