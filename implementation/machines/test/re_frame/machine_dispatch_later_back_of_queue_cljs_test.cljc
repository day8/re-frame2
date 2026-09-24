(ns re-frame.machine-dispatch-later-back-of-queue-cljs-test
  "A `:dispatch-later` armed by a MACHINE handler is a timer
  callback. When it fires, its event joins the BACK of the queue, behind
  external events already waiting (Spec 005 Level 4 lists timer callbacks among
  the back-of-queue origins; Spec 002's `do-fx :dispatch-later` copies no
  `:rf.machine/internal?`). A delayed child that inherited the machine's
  front-of-queue flag would jump ahead of them.

  Deterministic harness, no sleeps: the host timer (`set-timeout!`) and the
  router's drain scheduling (`next-tick`) are captured instead of run, then
  fired in a chosen order — finish the machine's dispatch, queue an external
  event, fire the saved timer, drain.

  Named `*-cljs-test.cljc` so both the JVM runner and shadow-cljs's
  `cljs-test$` build discover it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.interop :as rf.interop]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(def ^:private run-log (atom []))

(defn- reg-markers! []
  (reset! run-log [])
  (rf/reg-event ::ext (fn [_ _] (swap! run-log conj :ext) {}))
  (rf/reg-event ::timer (fn [_ _] (swap! run-log conj :timer) {})))

(defn- run-race
  "Dispatch `start-event` synchronously (it arms one `:dispatch-later`), queue
  `::ext`, fire the captured timer, then drain. Returns the run order of
  `::ext` and `::timer`."
  [start-event]
  (let [timers (atom [])
        ticks  (atom [])]
    (with-redefs [rf.interop/set-timeout! (fn [f _ms] (swap! timers conj f) ::handle)
                  rf.interop/next-tick    (fn [f] (swap! ticks conj f) nil)]
      (rf/dispatch-sync start-event)
      (is (= 1 (count @timers)) "exactly one timer was armed")
      (rf/dispatch [::ext])
      ((first @timers))
      ;; Drain: run every captured drain callback, including any queued while
      ;; draining.
      (loop []
        (when-let [f (first @ticks)]
          (swap! ticks #(vec (rest %)))
          (f)
          (recur))))
    @run-log))

(defn- reg-machine-arming! [machine-id ms]
  (rf/reg-machine machine-id
    {:initial :idle
     :data    {}
     :states  {:idle  {:on {:go {:target :armed
                                 :action (fn [_]
                                           {:fx [[:dispatch-later {:ms ms :event [::timer]}]]})}}}
               :armed {}}}))

(defn- timer-dispatched
  "The `:rf.event/dispatched` traces for `::timer`. The trace hoists `:source`
  to the event root; the delay detail stays under `:tags`."
  []
  (->> (rf.machines.test-support/events-of :rf.event/dispatched)
       (filterv #(= [::timer] (get-in % [:tags :rf.event/v])))))

(deftest machine-dispatch-later-joins-the-back-of-the-queue
  (doseq [ms [100 0]]
    (testing (str "a machine action's :dispatch-later {:ms " ms "} fires behind
                   the external event that was already queued")
      (rf.machines.test-support/reset-captured!)
      (reg-markers!)
      (let [machine-id (keyword (namespace ::m) (str "m" ms))]
        (reg-machine-arming! machine-id ms)
        (is (= [:ext :timer] (run-race [machine-id [:go]]))))
      (testing "and keeps its machine-action source stamp and delay detail"
        (let [[ev & more] (timer-dispatched)]
          (is (nil? more))
          (is (= :machine-action (:source ev)))
          (is (= {:ms ms} (get-in ev [:tags :rf.event/source-detail]))))))))

(deftest plain-handler-dispatch-later-control
  (testing "control: the identical :dispatch-later from a plain handler also
            fires behind the queued external event"
    (reg-markers!)
    (rf/reg-event ::plain (fn [_ _] {:fx [[:dispatch-later {:ms 100 :event [::timer]}]]}))
    (is (= [:ext :timer] (run-race [::plain])))
    (is (= :fx-dispatch-later (:source (first (timer-dispatched)))))))

(deftest machine-immediate-dispatch-still-front-inserts
  (testing "control: a machine action's IMMEDIATE :dispatch is still a
            macrostep continuation and runs ahead of an earlier-queued
            external event"
    (reg-markers!)
    (rf/reg-machine ::immediate
      {:initial :idle
       :data    {}
       :states  {:idle  {:on {:go {:target :armed
                                   :action (fn [_] {:fx [[:dispatch [::timer]]]})}}}
                 :armed {}}})
    (rf/reg-event ::seed
      (fn [_ _] {:fx [[:dispatch [::immediate [:go]]] [:dispatch [::ext]]]}))
    (rf/dispatch-sync [::seed])
    (is (= [:timer :ext] @run-log))))
