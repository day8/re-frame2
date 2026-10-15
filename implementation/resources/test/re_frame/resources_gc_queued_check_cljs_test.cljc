(ns re-frame.resources-gc-queued-check-cljs-test
  "A fired GC timer's re-check travels the router queue, so an event queued
  before the timer fired is handled before the check. When that event arms a
  newer GC window — the entry's final owner release, or the settle of a
  refetch that was in flight — the older check that lands after it must not
  collect the entry: it stays cached for that whole window and is collected by
  the window's own check (Spec 016 §The scoped-cache owner lifecycle, §Stale
  and GC scheduling). That holds whether the released owner attached through
  an `ensure` or through `adopt-owner`, which arms no window of its own. An
  entry no owner ever held is still collected `:gc-after-ms` after its settle.

  The host clock is virtual and delivery is queued: each due timer runs its
  real thunk, which dispatches through the real router, and the router's drain
  runs only when the test drains the captured `next-tick`s."
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

(def ^:private ticks
  "Captured `next-tick` callbacks — the router's pending drains."
  (atom []))

(def ^:private fifo
  "The ids of the events queued on the router, in queue order."
  (atom []))

(defn- init! []
  (rf/make-frame {:id :rf/default :platform :client
                  :doc "GC queued re-check frame."})
  (reset! now 0)
  (reset! pending {})
  (reset! ticks [])
  (reset! fifo [])
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf.fx/reg-fx :rf.http/managed-abort (fn [_ctx _work-id] nil)))

(defn- queued-delivery [f]
  (let [get-fn rf.late-bind/get-fn]
    (with-redefs [rf.interop/schedule-after!   (fn [thunk ms]
                                                 (let [h (gensym "timer")]
                                                   (swap! pending assoc h {:due (+ @now ms) :thunk thunk})
                                                   h))
                  rf.interop/cancel-scheduled! (fn [h] (swap! pending dissoc h) nil)
                  rf.interop/next-tick         (fn [tick] (swap! ticks conj tick) nil)
                  rf.late-bind/get-fn          (fn [hook-key]
                                                 (let [hook (get-fn hook-key)]
                                                   (if (and hook (= :router/dispatch! hook-key))
                                                     (fn [event opts]
                                                       (swap! fifo conj (first event))
                                                       (hook event opts))
                                                     hook)))]
      (try (f) (finally (rf.resources.timers/reset-cache!))))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!})
  queued-delivery)

(defn- drain!
  "Run the router's pending drains, in order, until none is left."
  []
  (loop []
    (when-let [tick (first @ticks)]
      (swap! ticks #(vec (rest %)))
      (tick)
      (recur))))

(defn- advance-to!
  "Move the virtual clock to `t`, running every timer due by then in due
  order. A fired timer only QUEUES its re-check; `drain!` delivers it."
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

(defn- run-to!
  "Advance to `t` and deliver everything queued by then."
  [t]
  (advance-to! t)
  (drain!))

(def ^:private k
  (rf.resources.state/scoped-resource-key :rf.scope/global :gcq/article {:slug "intro"}))

(defn- reg-article! []
  (rf/reg-resource :gcq/article
                   {:scope         :rf.scope/global
                    :params-schema [:map [:slug :string]]
                    :gc-after-ms   gc-ms}
                   (fn [{:keys [slug]} _ctx]
                     {:request {:method :get :url (str "/api/articles/" slug)}})))

(defn- entry []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          (rf.resources.state/entry-path k)))

(defn- ensure!
  ([] (ensure! nil))
  ([owner]
   (rf/dispatch-sync [:rf.resource/ensure
                      (cond-> {:resource :gcq/article :scope :rf.scope/global
                               :params {:slug "intro"}}
                        owner (assoc :owner owner))])))

(defn- refetch! []
  (rf/dispatch-sync [:rf.resource/refetch
                     {:resource :gcq/article :scope :rf.scope/global
                      :params {:slug "intro"}}]))

(defn- adopt! [owner]
  (rf/dispatch-sync [:rf.resource.internal/adopt-owner
                     {:resource :gcq/article :scope :rf.scope/global
                      :params {:slug "intro"} :owner owner}]))

(defn- succeeded
  "The success reply for the entry's current attempt."
  []
  (let [e (entry)]
    [:rf.resource.internal/succeeded
     {:resource/key k :work/id (:current-work e)
      :generation (:generation e) :data {:title "Intro"}}]))

(defn- succeed! []
  (rf/dispatch-sync (succeeded)))

(defn- queue! [event]
  (swap! fifo conj (first event))
  (rf/dispatch event))

(deftest a-check-queued-behind-the-final-release-waits-for-its-window
  ;; the owned entry settles at t=0, arming GC for t=1000
  (reg-article!)
  (ensure! [:app :gcq 1])
  (succeed!)
  (drain!)
  (reset! fifo [])
  (advance-to! 900)
  (queue! [:rf.resource/release-owner {:owner [:app :gcq 1]}])
  ;; the settle's timer fires before the release is delivered
  (advance-to! gc-ms)
  (is (= [:rf.resource/release-owner :rf.resource.internal/gc-fired] @fifo)
      "the older check is queued behind the final release")
  (drain!)
  (is (some? (entry)) "the older check does not collect the released entry")
  (run-to! (+ gc-ms gc-ms -1))
  (is (some? (entry)) "kept for the whole window the release armed")
  (run-to! (+ gc-ms gc-ms))
  (is (nil? (entry)) "collected by that window's check"))

(deftest a-check-queued-behind-an-adopted-owners-release-waits-for-its-window
  ;; the ownerless settle at t=0 arms GC for t=1000; the adopted owner arms none
  (reg-article!)
  (ensure!)
  (succeed!)
  (drain!)
  (adopt! [:app :gcq 2])
  (reset! fifo [])
  (advance-to! 900)
  (queue! [:rf.resource/release-owner {:owner [:app :gcq 2]}])
  ;; the settle's timer fires before the release is delivered
  (advance-to! gc-ms)
  (is (= [:rf.resource/release-owner :rf.resource.internal/gc-fired] @fifo)
      "the settle window's check is queued behind the final release")
  (drain!)
  (is (some? (entry)) "the older check does not collect the released entry")
  (run-to! (+ gc-ms gc-ms -1))
  (is (some? (entry)) "kept for the whole window the release armed")
  (run-to! (+ gc-ms gc-ms))
  (is (nil? (entry)) "collected by that window's check"))

(deftest a-check-queued-behind-a-refetch-settle-waits-for-its-window
  ;; the ownerless settle at t=0 arms GC for t=1000
  (reg-article!)
  (ensure!)
  (succeed!)
  (drain!)
  (advance-to! 500)
  (refetch!)
  (reset! fifo [])
  (advance-to! 900)
  (queue! (succeeded))
  ;; the first settle's timer fires before the refetch's reply is delivered
  (advance-to! gc-ms)
  (is (= [:rf.resource.internal/succeeded :rf.resource.internal/gc-fired] @fifo)
      "the first settle's check is queued behind the refetch's settle")
  (drain!)
  (is (some? (entry)) "the older check does not collect the re-settled entry")
  (run-to! (+ gc-ms gc-ms -1))
  (is (some? (entry)) "kept for the whole window the refetch's settle armed")
  (run-to! (+ gc-ms gc-ms))
  (is (nil? (entry)) "collected by that window's check"))

(deftest an-ownerless-prefetch-is-collected-at-its-settle-window
  (reg-article!)
  (ensure!)
  (succeed!)
  (drain!)
  (run-to! (dec gc-ms))
  (is (some? (entry)) "kept until :gc-after-ms after the settle")
  (run-to! gc-ms)
  (is (nil? (entry)) "collected by the settle window's queued check"))
