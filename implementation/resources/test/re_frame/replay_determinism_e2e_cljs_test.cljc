(ns re-frame.replay-determinism-e2e-cljs-test
  "EP-0010 §Replay Fixture end to end: one multi-event token log spanning
  app-db and runtime-db (an app-db create and touch, a resource ensure and
  success reply, an owner release and a tag invalidation) is replayed twice
  under different ambient clocks and RNGs, and the two durable
  `{:rf.db/app :rf.db/runtime}` projections must be equal. A durable write
  that folded an ambient read instead of the token would make them diverge,
  which the per-component timestamp tests cannot see."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.interop :as rf.interop]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private last-managed-args (atom nil))

(def ^:private reset-fixture
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(defn- capturing-transport-fixture [f]
  (reset! last-managed-args nil)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (f))

(use-fixtures :each reset-fixture capturing-transport-fixture)

(def ^:private frame-id :rf/default)
(def ^:private stale-after-ms 60000)

;; Every handler folds its durable facts from the token's :rf.cofx.
(defn- register-log! []
  (rf/reg-event :repl/create
    (fn [{:keys [db] cofx :rf.cofx} [_ text]]
      (let [id (:todo/id cofx)]
        {:db (assoc-in db [:todos id]
                       {:todo/id id :todo/color (:todo/color cofx)
                        :todo/text text :todo/created-at (:rf/time-ms cofx)})})))
  (rf/reg-event :repl/touch
    (fn [{:keys [db] cofx :rf.cofx} [_ id]]
      {:db (assoc-in db [:todos id :todo/touched-at] (:rf/time-ms cofx))}))
  (rf/reg-resource :repl/article
    {:scope          :rf.scope/global
     :params-schema  [:map [:slug :string]]
     :stale-after-ms stale-after-ms
     :tags           (fn [{:keys [slug]} _data] #{[:article slug]})}
    (fn [{:keys [slug]} _ctx]
      {:request {:method :get :url (str "/api/articles/" slug)}})))

(def ^:private todo-id #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d")

(def ^:private log
  {:create-time     1781078400000
   :ensure-time     1781078400100
   :reply-time      1781078400250
   :touch-time      1781078400400
   :release-time    1781078400700
   :invalidate-time 1781078400900})

(defn- at [t] {:frame frame-id :rf.cofx {:rf/time-ms (t log)}})

(defn- run-log!
  "Replay the token log once through the live router; return the durable projection."
  []
  (register-log!)
  (rf/dispatch-sync [:repl/create "buy milk"]
                    {:frame frame-id
                     :rf.cofx {:todo/id    todo-id
                               :todo/color :green
                               :rf/time-ms (:create-time log)}})
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :repl/article :scope :rf.scope/global
                      :params {:slug "w"} :owner [:app :repl 1]}]
                    (at :ensure-time))
  (rf/dispatch-sync (conj (:on-success @last-managed-args) {:status :ok :value {:title "Welcome"}})
                    (at :reply-time))
  (rf/dispatch-sync [:repl/touch todo-id] (at :touch-time))
  ;; Released first, so the invalidation leaves the entry stale instead of
  ;; spawning a live, freshly stamped refetch that no recorded log carries.
  (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :repl 1]}] (at :release-time))
  (rf/dispatch-sync [:rf.resource/invalidate-tags
                     {:scope :rf.scope/global :tags #{[:article "w"]}}]
                    (at :invalidate-time))
  {:rf.db/app     (rf/app-db-value frame-id)
   :rf.db/runtime (:rf.db/runtime (rf/frame-state-value frame-id))})

(defn- durable
  "Strip the diagnostic slot, which is free to vary between runs."
  [x]
  (cond
    (map? x)  (into {} (for [[k v] x :when (not= :rf.runtime/diagnostics k)] [k (durable v)]))
    (coll? x) (into (empty x) (map durable x))
    :else     x))

(deftest token-log-replays-deterministically-under-differing-clock-and-rng
  (let [run-a (with-redefs [rf.interop/now-ms       (constantly 111)
                            rf.interop/epoch-now-ms (constantly 111)
                            rand        (fn ([] 0.111) ([n] (* n 0.111)))
                            random-uuid (constantly #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")]
                (run-log!))]
    ;; A fresh runtime and host caches, so only the ambient clock and RNG differ.
    (reset-fixture
      #(capturing-transport-fixture
         (fn []
           (let [run-b (with-redefs [rf.interop/now-ms       (constantly 9999999999999)
                                     rf.interop/epoch-now-ms (constantly 9999999999999)
                                     rand        (fn ([] 0.999) ([n] (* n 0.999)))
                                     random-uuid (constantly #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")]
                         (run-log!))]
             (is (= (durable run-a) (durable run-b)))
             (testing "the durable facts are the token values, so the equality is not vacuous"
               (let [entry (get-in (:rf.db/runtime run-b)
                                   (rf.resources.state/entry-path
                                     (rf.resources.state/scoped-resource-key
                                       :rf.scope/global :repl/article {:slug "w"})))]
                 (is (= {:todo/id todo-id :todo/color :green :todo/text "buy milk"
                         :todo/created-at (:create-time log) :todo/touched-at (:touch-time log)}
                        (get-in (:rf.db/app run-b) [:todos todo-id])))
                 (is (= [{:title "Welcome"} (:reply-time log) (+ (:reply-time log) stale-after-ms)
                         (:invalidate-time log)]
                        ((juxt :data :loaded-at :stale-at :invalidated-at) entry)))
                 (is (true? (rf.resources.state/entry-stale? entry (:reply-time log))))))))))))
