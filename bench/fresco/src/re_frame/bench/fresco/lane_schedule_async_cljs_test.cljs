(ns re-frame.bench.fresco.lane-schedule-async-cljs-test
  "The asynchronous driver preserves the synchronous schedule and never overlaps visits."
  (:require [cljs.test :refer-macros [async deftest is]]
            [re-frame.bench.fresco.lane :as rf.bench.fresco.lane]))

(def ^:private sampling {:warmup 3 :samples 6})

(def ^:private rounds 5)

(def ^:private arm-counts
  "Two arms drop reflection; larger even and odd schedules retain it."
  [2 4 5])

(defn- arms [n]
  (mapv (fn [i] {:id (keyword (str "arm-" i))}) (range n)))

(defn- sync-run
  "`rounds!` over `n` arms; `:truth` is every execution in order."
  [n]
  (let [truth (atom [])
        out   (rf.bench.fresco.lane/rounds! (arms n) sampling rounds
                            (fn [arm]
                              (let [i (count @truth)]
                                (swap! truth conj (name (:id arm)))
                                i)))]
    (assoc out :truth @truth)))

(defn- async-run
  "`rounds-async!` over the same stub, answering one microtask late.
  `:overlaps` counts visits that began while another was in flight — a
  `Promise.all` fan-out would bank the same readings and be a different
  instrument, every arm measuring beside its siblings."
  [n]
  (let [truth    (atom [])
        in-fl    (atom 0)
        overlaps (atom 0)]
    (.then (rf.bench.fresco.lane/rounds-async! (arms n) sampling rounds
                               (fn [arm]
                                 (when (pos? @in-fl) (swap! overlaps inc))
                                 (swap! in-fl inc)
                                 (let [i (count @truth)]
                                   (swap! truth conj (name (:id arm)))
                                   (.then (js/Promise.resolve nil)
                                          (fn [_] (swap! in-fl dec) i)))))
           (fn [out] (assoc out :truth @truth :overlaps @overlaps)))))

(deftest the-async-loop-walks-the-synchronous-loops-plan
  (async done
    (.then
      (rf.bench.fresco.lane/chain nil arm-counts
                  (fn [_ n]
                    (.then (async-run n)
                           (fn [a]
                             (is (= (assoc (sync-run n) :overlaps 0) a)
                                 (str "arm count " n ": same visits, samples and readings, "
                                      "and no visit began while another was in flight"))
                             nil))))
      (fn [_] (done)))))
