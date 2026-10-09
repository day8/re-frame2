(ns re-frame.hot-reload-test
  "Spec 001 §Hot-reload semantics on the JVM: re-registering an event handler
  leaves in-flight work alone, re-registering a sub evicts its cached reaction
  and its declared-input dependents in every frame, and re-registering a frame
  keeps its live runtime state. View re-registration is covered by
  `hot_reload_cljs_test.cljs`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; publishes the late-bind hook `rf/reg-machine` lowers onto
            [re-frame.machines]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf/make-frame {:id :rf/default})
  ;; restore the ns-load registrations clear-all! wiped, and the reg-machine hook
  (require 're-frame.routing :reload)
  (require 're-frame.ssr    :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(deftest event-handler-re-register-non-destructive
  (testing "the handler in flight finishes with the fn it started with when
            another thread re-registers its id; the next dispatch uses the new fn"
    (let [observations  (atom [])
          enter-latch   (CountDownLatch. 1)
          proceed-latch (CountDownLatch. 1)
          v1-fn (fn [{:keys [db]} [_ tag]]
                  (swap! observations conj [:v1-start tag])
                  (.countDown enter-latch)
                  (.await proceed-latch 5 TimeUnit/SECONDS)
                  (swap! observations conj [:v1-end tag])
                  {:db (assoc db :ran-version :v1)})
          v2-fn (fn [{:keys [db]} [_ tag]]
                  (swap! observations conj [:v2 tag])
                  {:db (assoc db :ran-version :v2)})]
      (rf/reg-event :step v1-fn)
      (let [drain-thread (Thread.
                           ^Runnable (fn []
                                       (rf/dispatch-sync [:step :a] {:frame :rf/default})))]
        (.start drain-thread)
        (.await enter-latch 5 TimeUnit/SECONDS)
        (rf/reg-event :step v2-fn)
        (.countDown proceed-latch)
        (.join drain-thread 5000))
      (is (= [[[:v1-start :a] [:v1-end :a]] :v1]
             [@observations (:ran-version (rf/app-db-value :rf/default))]))
      (rf/dispatch-sync [:step :b] {:frame :rf/default})
      (is (= [[[:v1-start :a] [:v1-end :a] [:v2 :b]] :v2]
             [@observations (:ran-version (rf/app-db-value :rf/default))])))))

(deftest sub-re-register-evicts-cache-cross-frame
  (testing "re-registering a :sub drops its cached reaction in every frame"
    (rf/make-frame {:id :left})
    (rf/make-frame {:id :right})
    (rf/reg-event :seed (fn [_ [_ n]] {:db {:n n}}))
    (rf/dispatch-sync [:seed 3] {:frame :left})
    (rf/dispatch-sync [:seed 5] {:frame :right})
    (rf/reg-sub :answer (fn [db _] (:n db)))
    ;; Held subscriptions keep each frame's cache slot alive across the
    ;; re-registration; a subscribe-once would release it by ref-count.
    (let [pin-left  (rf/subscribe [:answer] {:frame :left})
          pin-right (rf/subscribe [:answer] {:frame :right})]
      (is (= [3 5] [@pin-left @pin-right]))
      (rf/reg-sub :answer (fn [db _] (* 100 (:n db))))
      (is (= [300 500] [(rf/subscribe-once [:answer] {:frame :left})
                        (rf/subscribe-once [:answer] {:frame :right})])))))

(deftest sub-re-register-evicts-transitive-dependents
  (testing "re-registering an upstream sub also evicts the cached subs that
            declare it as an input, so they recompute against the new body"
    (rf/reg-event :seed (fn [_ _] {:db {:n 10}}))
    (rf/dispatch-sync [:seed] {:frame :rf/default})
    (rf/reg-sub :a (fn [db _] (:n db)))
    (rf/reg-sub :sum {:inputs [[:a]]} (fn [[a] _] (+ a 0)))
    ;; Holding [:sum] pins it and its [:a] input in the cache.
    (let [pin-sum (rf/subscribe [:sum] {:frame :rf/default})]
      (is (= 10 @pin-sum))
      (rf/reg-sub :a (fn [db _] (* 2 (:n db))))
      (is (= 20 (rf/subscribe-once [:sum] {:frame :rf/default}))
          "a stale cached [:sum] or [:a] would still read 10"))))

(deftest frame-re-register-preserves-snapshot
  (testing "make-frame on a live id keeps its runtime-db, so an active machine
            resumes from its snapshot"
    (rf/make-frame {:id :tenant :doc "v1 metadata"})
    (rf/reg-machine :traffic-light
      {:initial :red
       :data    {:ticks 0}
       :actions {:tick-action (fn [{data :data}] {:data (update data :ticks inc)})}
       :states  {:red    {:on {:tick {:target :green  :action :tick-action}}}
                 :green  {:on {:tick {:target :yellow :action :tick-action}}}
                 :yellow {:on {:tick {:target :red    :action :tick-action}}}}})
    (let [snapshot #(get-in (rf/frame-state-value :tenant)
                            [:rf.db/runtime :rf.runtime/machines :snapshots :traffic-light])
          tick!    #(rf/dispatch-sync [:traffic-light [:tick]] {:frame :tenant})]
      (tick!)
      (tick!)
      (let [before (snapshot)]
        (is (= [:yellow 2] [(:state before) (get-in before [:data :ticks])]))
        (rf/make-frame {:id :tenant :doc "v2 metadata"})
        (is (= before (snapshot)))
        (tick!)
        (is (= [:red 3] [(:state (snapshot)) (get-in (snapshot) [:data :ticks])]))))))
