(ns re-frame.cold-serialized-drain-test
  "A cold `rf.frame/call-serialized-with-drain!` section holds the frame's
  `:drain-lock` without being a drainer. Its release must re-kick a queue that a
  `dispatch!` made during the hold left stranded, and a `dispatch-sync!` issued
  from inside the section on the same thread must re-enter the drain rather
  than spin on the lock it already holds. Both are JVM-only: CLJS is
  single-threaded."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.registrar :as rf.registrar]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (rf.frame/ensure-default-frame!)
  (binding [rf.frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

;; The JVM executor is single-threaded FIFO, so a task submitted through
;; `next-tick` runs after every `drain-try!` already scheduled. The timeout only
;; bounds a hang.
(defn- executor-barrier! []
  (let [latch (CountDownLatch. 1)]
    (rf.interop/next-tick (fn [] (.countDown latch)))
    (is (.await latch 5 TimeUnit/SECONDS) "executor barrier task ran")))

(deftest cold-serialized-release-rekicks-stranded-queue
  (testing "an event dispatched while a cold section holds the lock runs once the section releases"
    (let [ran (atom 0)]
      (rf/reg-event :bump (fn [_ _] (swap! ran inc) {}))
      (rf.frame/call-serialized-with-drain! :rf/default
        (fn []
          (rf/dispatch [:bump] {:frame :rf/default})
          ;; the scheduled drain-try! has run and lost the lock to this section
          (executor-barrier!)
          (is (zero? @ran))))
      (executor-barrier!)
      (is (= 1 @ran) "the release re-kicked the stranded drain")
      (rf/dispatch [:bump] {:frame :rf/default})
      (executor-barrier!)
      (is (= 2 @ran) "a later dispatch still drains"))))

(deftest dispatch-sync-inside-cold-serialized-thunk-reenters
  (testing "dispatch-sync from inside a cold serialized thunk runs the event instead of self-deadlocking"
    (let [ran    (atom 0)
          result (atom ::unset)]
      (rf/reg-event :inner
        (fn [{:keys [db]} _]
          (swap! ran inc)
          {:db (assoc db :inner? true)}))
      ;; A worker thread, so a self-deadlock shows as a join timeout rather
      ;; than a hung JVM.
      (let [worker (Thread.
                     ^Runnable
                     (fn []
                       (rf.frame/call-serialized-with-drain! :rf/default
                         (fn []
                           (rf/dispatch-sync [:inner] {:frame :rf/default})
                           (reset! result :returned)))))]
        (.start worker)
        (.join worker 5000))
      (is (= [:returned 1 true]
             [@result @ran (:inner? (rf/app-db-value :rf/default))])))))
