(ns re-frame.schemas-concurrency-stress-test
  "JVM stress test: registrations racing from several threads all land.
  Every frame's schemas live in one global registry atom, and only the JVM
  can register from more than one thread."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture])
  (:import [java.util.concurrent CountDownLatch]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(def ^:private n-threads 8)
(def ^:private stress-iters 5000)
(def ^:private stress-frame :stress/main)

(defn- entry
  "Thread `t`'s `m`th registration: a distinct path and a distinct schema."
  [t m]
  [[:stress (keyword (str "t" t)) (keyword (str "k" m))]
   [:enum (keyword (str "t" t "-m" m))]])

(deftest reg-app-schemas-race-stress
  (testing (str n-threads " threads x " stress-iters
                " reg-app-schemas on one frame drop no registration")
    (rf/make-frame {:id stress-frame})
    (let [latch   (CountDownLatch. 1)
          futures (vec (for [t (range n-threads)]
                         (future
                           (.await latch)
                           (dotimes [m stress-iters]
                             (rf/reg-app-schemas (into {} [(entry t m)])
                                                 {:frame stress-frame})))))]
      (.countDown latch)
      (is (every? #(not= ::timeout (deref % 120000 ::timeout)) futures)
          "every registering thread finished within 120s")
      (let [registered (update-vals (rf.schemas/app-schemas {:frame stress-frame}) :schema)
            lost       (for [t (range n-threads)
                             m (range stress-iters)
                             :let [[path schema] (entry t m)]
                             :when (not= schema (registered path))]
                         path)]
        (is (empty? lost)
            (str (count lost) " registrations lost, e.g. " (vec (take 3 lost))))))))
