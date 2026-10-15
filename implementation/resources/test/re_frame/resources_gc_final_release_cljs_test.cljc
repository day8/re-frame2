(ns re-frame.resources-gc-final-release-cljs-test
  "An owner-free entry stays cached for a full `:gc-after-ms` after its last
  owner is released, however much of the settle's GC window had already run,
  so a quick re-attach inside that window reuses the cached data (Spec 016
  §The scoped-cache owner lifecycle, §Stale and GC scheduling). An entry no
  owner ever held is measured from its settle, and `:gc-after-ms :never` arms
  nothing. The host clock is virtual: the real timer side table arms through a
  redefined `schedule-after!`, and advancing the clock runs each due timer's
  real thunk, whose re-check dispatch is delivered synchronously."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.interop :as rf.interop]
   [re-frame.late-bind :as rf.late-bind]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.resources.timers :as rf.resources.timers]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(def ^:private gc-ms 1000)

(def ^:private now
  "The virtual host clock, in ms."
  (atom 0))

(def ^:private pending
  "Armed host timers: handle → `{:due <ms> :thunk <fn>}`."
  (atom {}))

(def ^:private requests
  "How many managed-HTTP requests the runtime started."
  (atom 0))

(defn- init! []
  (rf/make-frame {:id :rf/default :platform :client
                  :doc "GC final-release timing frame."})
  (reset! now 0)
  (reset! pending {})
  (reset! requests 0)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] (swap! requests inc) nil))
  (rf.fx/reg-fx :rf.http/managed-abort (fn [_ctx _work-id] nil)))

(defn- virtual-clock [f]
  (let [get-fn         rf.late-bind/get-fn
        sync-dispatch! (fn [event {:keys [frame]}] (rf/dispatch-sync event {:frame frame}))]
    (with-redefs [rf.interop/schedule-after!   (fn [thunk ms]
                                                 (let [h (gensym "timer")]
                                                   (swap! pending assoc h {:due (+ @now ms) :thunk thunk})
                                                   h))
                  rf.interop/cancel-scheduled! (fn [h] (swap! pending dissoc h) nil)
                  rf.late-bind/get-fn          (fn [hook-key]
                                                 (if (= :router/dispatch! hook-key)
                                                   sync-dispatch!
                                                   (get-fn hook-key)))]
      (try (f) (finally (rf.resources.timers/reset-cache!))))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!})
  virtual-clock)

(defn- advance-to!
  "Move the virtual clock to `t`, firing every timer due by then in due order."
  [t]
  (loop []
    (when-let [[h {:keys [due thunk]}] (->> @pending
                                            (filter #(<= (:due (val %)) t))
                                            (sort-by (comp :due val))
                                            first)]
      (swap! pending dissoc h)
      (reset! now due)
      (thunk)
      (recur)))
  (reset! now t))

(def ^:private k
  (rf.resources.state/scoped-resource-key :rf.scope/global :gcf/article {:slug "intro"}))

(defn- reg-article!
  ([] (reg-article! gc-ms))
  ([gc-after-ms]
   (rf/reg-resource :gcf/article
                    {:scope         :rf.scope/global
                     :params-schema [:map [:slug :string]]
                     :gc-after-ms   gc-after-ms}
                    (fn [{:keys [slug]} _ctx]
                      {:request {:method :get :url (str "/api/articles/" slug)}}))))

(defn- entry []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          (rf.resources.state/entry-path k)))

(defn- ensure!
  ([] (ensure! nil))
  ([owner]
   (rf/dispatch-sync [:rf.resource/ensure
                      (cond-> {:resource :gcf/article :scope :rf.scope/global
                               :params {:slug "intro"}}
                        owner (assoc :owner owner))])))

(defn- succeed! []
  (let [e (entry)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key k :work/id (:current-work e)
                        :generation (:generation e) :data {:title "Intro"}}])))

(defn- release! [owner]
  (rf/dispatch-sync [:rf.resource/release-owner {:owner owner}]))

(deftest released-late-in-the-window-stays-a-full-gc-after-ms
  ;; the settle at t=0 armed GC for t=1000; the last owner leaves at t=900
  (reg-article!)
  (ensure! [:app :gcf 1])
  (succeed!)
  (advance-to! 900)
  (release! [:app :gcf 1])
  (advance-to! (+ 900 gc-ms -1))
  (is (some? (entry)) "kept for the whole window after the final release")
  (advance-to! (+ 900 gc-ms))
  (is (nil? (entry)) "collected :gc-after-ms after the final release"))

(deftest a-re-attach-inside-the-window-reuses-the-entry
  (reg-article!)
  (ensure! [:app :gcf 1])
  (succeed!)
  (advance-to! 900)
  (release! [:app :gcf 1])
  (advance-to! 1500)
  (ensure! [:app :gcf 2])
  (let [e (entry)]
    (is (= [:loaded {:title "Intro"} nil #{[:app :gcf 2]} 1]
           [(:status e) (:data e) (:current-work e) (:active-owners e) @requests])
        "served from cache under the new owner, with no second request")))

(deftest an-ownerless-prefetch-is-collected-gc-after-ms-after-its-settle
  (reg-article!)
  (ensure!)
  (advance-to! 200)
  (succeed!)
  (advance-to! (+ 200 gc-ms -1))
  (is (some? (entry)) "kept until :gc-after-ms after the settle")
  (advance-to! (+ 200 gc-ms))
  (is (nil? (entry)) "collected :gc-after-ms after the settle"))

(deftest gc-after-ms-never-arms-nothing-on-settle-or-release
  (reg-article! :never)
  (ensure! [:app :gcf 1])
  (succeed!)
  (release! [:app :gcf 1])
  (is (empty? @pending) "neither the settle nor the release armed a timer")
  (advance-to! 100000000)
  (is (some? (entry)) "the owner-free entry is kept"))
