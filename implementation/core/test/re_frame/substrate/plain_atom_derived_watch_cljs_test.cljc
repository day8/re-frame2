(ns re-frame.substrate.plain-atom-derived-watch-cljs-test
  "A plain-atom derived value takes a watch, on both runtimes. Spec 006
  §`make-derived-value` says `subscribe-container` works on a derived container
  as on a base container: the observer hears one `(on-change old new)` per
  change of the computed value, nothing for a source write that leaves the
  computed value unchanged, and nothing once it unsubscribes or the value is
  disposed. The SSR adapter, which reuses this derived value, is pinned by
  `re-frame.ssr.derived-value-watch-cljs-test`."
  (:require [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            #?(:clj [re-frame.interop :as rf.interop])
            #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(def ^:private A rf.substrate.plain-atom/adapter)

(defn- container [v] ((:make-state-container A) v))
(defn- derived [sources f] ((:make-derived-value A) sources f))
(defn- write! [c v] ((:replace-container! A) c v))
(defn- value [c] ((:read-container A) c))

(defn- observe!
  "Subscribe to `c` through the adapter, recording each `[old new]` call.
  Returns `[calls unsubscribe]`."
  [c]
  (let [calls (atom [])]
    [calls ((:subscribe-container A) c (fn [old nu] (swap! calls conj [old nu])))]))

(defn- dispose! [c]
  #?(:clj  (rf.interop/dispose! c)
     :cljs ((:dispose! A) c)))

(deftest an-observer-hears-each-change-of-the-computed-value-once
  (let [db              (container {:n 1 :noise 0})
        tens            (derived [db] (fn [{:keys [n]}] (* 10 n)))
        [calls unsub]   (observe! tens)]
    (write! db {:n 2 :noise 0})
    (is (= [[10 20]] @calls) "one call, carrying the old and new computed values")
    (write! db {:n 2 :noise 1})
    (is (= [[10 20]] @calls) "a source write that leaves the computed value unchanged makes no call")
    (unsub)
    (write! db {:n 3 :noise 1})
    (is (= [[10 20]] @calls) "an unsubscribed observer hears nothing more")
    (is (= 30 (value tens)) "the value still recomputes on read")))

(deftest a-layered-derived-value-hears-its-sources-through-its-input
  (let [db            (container {:n 1})
        n             (derived [db] :n)
        n+1           (derived [n] inc)
        [calls unsub] (observe! n+1)]
    (write! db {:n 5})
    (is (= [[2 6]] @calls))
    (unsub)
    (write! db {:n 7})
    (is (= [[2 6]] @calls) "unsubscribing from the outer value releases the inner one's watch too")))

(deftest disposing-a-derived-value-removes-its-observers-source-watches
  (let [db          (container {:n 1})
        n           (derived [db] :n)
        [calls _]   (observe! n)]
    (dispose! n)
    (write! db {:n 2})
    (is (= [] @calls))))
