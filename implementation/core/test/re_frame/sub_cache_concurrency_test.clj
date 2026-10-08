(ns re-frame.sub-cache-concurrency-test
  "JVM-only contention tests for the sub-cache (CLJS is single-threaded).

  `swap!` may retry its fn under CAS contention, so the cache's swap-fns are
  pure and every side effect (disposal) is computed after the CAS from the
  `swap-vals!` pre/post snapshots; a side effect inside a swap-fn would replay
  on a discarded retry. Threads race from one latch, as in
  `router_drain_race_test.clj`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.subs :as rf.subs]
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
  ;; A `with-frame` binding does not convey into worker threads, so the calls
  ;; below name `:rf/default` explicitly.
  (rf.frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(deftest unsubscribe-drop-to-zero-no-spurious-fire-under-contention
  ;; One subscriber, six racing unsubscribes: exactly one CAS winner drives
  ;; the 1 -> 0 edge, so each trial disposes the slot exactly once.
  (rf/reg-event :seed (fn [_ _] {:db {:n 7}}))
  (rf/reg-sub :n (fn [db _] (:n db)))
  (rf/dispatch-sync [:seed] {:frame :rf/default})
  (let [orig-dispose! rf.interop/dispose!
        per-trial     (atom [])]
    (dotimes [_ 200]
      (let [trial-counter (atom 0)]
        (with-redefs [rf.interop/dispose! (fn [r] (swap! trial-counter inc) (orig-dispose! r))]
          (rf/subscribe [:n] {:frame :rf/default})
          (let [latch   (CountDownLatch. 1)
                threads (mapv (fn [_]
                                (Thread.
                                  ^Runnable
                                  (fn []
                                    (.await latch 5 TimeUnit/SECONDS)
                                    (rf/unsubscribe :rf/default [:n]))))
                              (range 6))]
            (doseq [t threads] (.start t))
            (.countDown latch)
            (doseq [t threads] (.join t 5000))))
        (swap! per-trial conj @trial-counter)))
    (is (= {1 200} (frequencies @per-trial)))))

(deftest concurrent-miss-install-resolves-to-one-reaction
  ;; Two cache-miss builds of one query-v (two direct `compute-and-cache!`
  ;; calls model the race deterministically). An unconditional install would
  ;; let the second build stomp the first: one reaction orphaned with its
  ;; input refs leaked, and the count reset to 1 under two holders.
  (rf/reg-event :seed (fn [_ _] {:db {:a 1 :b 2}}))
  (rf/reg-sub :a (fn [db _] (:a db)))
  (rf/reg-sub :b (fn [db _] (:b db)))
  (rf/reg-sub :sum {:inputs [[:a] [:b]]} (fn [[a b] _] (+ a b)))
  (rf/dispatch-sync [:seed] {:frame :rf/default})
  (let [cache      (:sub-cache (rf.frame/frame :rf/default))
        ref-counts #(into {} (map (fn [[k v]] [k (:ref-count v)])) @cache)
        cc!        #'rf.subs/compute-and-cache!
        r1         (cc! :rf/default [:sum])
        r2         (cc! :rf/default [:sum])]
    (is (identical? r1 r2) "the loser adopts the winner")
    (is (identical? r1 (get-in @cache [[:sum] :reaction])))
    (is (= {[:sum] 2 [:a] 1 [:b] 1} (ref-counts)))
    (rf/unsubscribe :rf/default [:sum])
    (rf/unsubscribe :rf/default [:sum])
    (is (= {} (ref-counts)))))
