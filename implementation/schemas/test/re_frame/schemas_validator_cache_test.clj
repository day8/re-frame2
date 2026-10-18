(ns re-frame.schemas-validator-cache-test
  "The default Malli bundle compiles each schema once and keys the compiled
  validator weakly on the schema value, so a cache entry lives only while
  something else holds its schema. Each test installs a bundle over a cache of
  its own, which it can then count."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.malli :as rf.schemas.malli]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture])
  (:import (java.lang.ref WeakReference)
           (java.util Map)))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- install-counted-bundle!
  "Install a Malli bundle over a fresh cache and return that cache."
  []
  (let [cache (#'rf.schemas.malli/weak-cache)]
    (rf.schemas/set-schema-fns! (#'rf.schemas.malli/bundle-over cache))
    cache))

(defn- fresh-schema
  "A schema no other value equals: its predicate is a new closure."
  [limit]
  [:fn (fn [x] (<= x limit))])

(defn- collect!
  "Run the collector until every reference in `refs` has cleared, or give up."
  [refs]
  (loop [attempt 0]
    (System/gc)
    (when (and (< attempt 50) (some #(.get ^WeakReference %) refs))
      (Thread/sleep 20)
      (recur (inc attempt)))))

(defn- settled-size
  "The cache's size once the collector's cleared references have reached the
  reference queue. A `WeakHashMap` drops an entry only when it finds the
  entry's reference queued, which trails the clearing `collect!` waits for,
  so poll briefly before reading a size."
  [^Map cache]
  (loop [attempt 0]
    (let [n (.size cache)]
      (if (or (zero? n) (<= 50 attempt))
        n
        (do (System/gc)
            (Thread/sleep 20)
            (recur (inc attempt)))))))

(defn- register-replace-destroy!
  "One cycle: register a fresh schema at [:n] and validate a write against
  it, replace it with another fresh schema and validate again, then
  unregister the path. Returns weak references to both schemas."
  [i]
  (let [first-schema  (fresh-schema i)
        second-schema (fresh-schema (inc i))]
    (rf/reg-app-schema [:n] first-schema)
    (rf.schemas/validate-app-schema! {:n i} :cache/write)
    (rf/reg-app-schema [:n] second-schema)
    (rf.schemas/validate-app-schema! {:n i} :cache/write)
    (rf.registrar/unregister! :app-schema [:n])
    [(WeakReference. first-schema) (WeakReference. second-schema)]))

(deftest a-schema-compiles-once
  (testing "repeated checks against one schema value reuse one cache entry"
    (let [cache  (install-counted-bundle!)
          schema [:map [:id :int]]
          check  (:validate (rf.schemas/schema-fns))]
      (is (= [true false true] [(check schema {:id 1}) (check schema {:id "x"}) (check schema {:id 2})]))
      (is (= 1 (.size ^Map cache))))))

(deftest no-cache-entry-outlives-its-schema
  (testing "after register / replace / destroy cycles with fresh closure-bearing
            schemas, every schema is collectable and no entry remains"
    (let [cache (install-counted-bundle!)
          refs  (into [] (mapcat register-replace-destroy!) (range 200))]
      (is (= 400 (count refs)))
      (collect! refs)
      (is (every? #(nil? (.get ^WeakReference %)) refs)
          "nothing the cache holds keeps a schema alive")
      (is (zero? (settled-size cache))
          "no entry for a collected schema remains"))))

(deftest swapping-the-port-drops-the-cache
  (testing "once another bundle is installed, nothing holds the old cache"
    (let [cache-ref (WeakReference. (install-counted-bundle!))]
      (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
      (collect! [cache-ref])
      (is (nil? (.get cache-ref))))))
