(ns re-frame.interop-dispose-registry-test
  "JVM on-dispose callbacks ride ON the `rf.interop/make-reaction` `Reaction`
  (a mutable field), mirroring the CLJS plain-atom adapter. A process-wide
  strong registry keyed by reaction would pin every reaction that never reached
  `dispose!`, with its input chain, for the life of the process: a real leak
  for Spec 011's frame-per-request SSR server."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.interop :as rf.interop])
  (:import [java.lang.ref WeakReference]))

(deftest callbacks-fire-on-dispose-in-registration-order
  ;; The set is cleared BEFORE it fires, so a re-entrant `dispose!` from a
  ;; callback, and a later second `dispose!`, fire nothing again.
  (let [r     (rf.interop/make-reaction (fn [] :v))
        fired (atom [])]
    (rf.interop/add-on-dispose! r (fn [] (swap! fired conj :a) (rf.interop/dispose! r)))
    (rf.interop/add-on-dispose! r (fn [] (swap! fired conj :b)))
    (rf.interop/add-on-dispose! r (fn [] (swap! fired conj :c)))
    (rf.interop/dispose! r)
    (rf.interop/dispose! r)
    (is (= [:a :b :c] @fired))))

(defn- gc-reclaimed?
  "True when `wref`'s referent is reclaimed within a bounded number of GC
  nudges (GC cannot be forced deterministically)."
  [^WeakReference wref]
  (loop [attempts 50]
    (cond
      (nil? (.get wref)) true
      (zero? attempts)   false
      :else              (do
                           (System/gc)
                           (Thread/sleep 20)
                           (recur (dec attempts))))))

(deftest orphaned-reaction-is-gc-reclaimable
  ;; The callback closes over the reaction, as the sub-cache's identity-guard
  ;; closure does; a global strong registry would pin the reaction through it.
  (let [wref (let [r (rf.interop/make-reaction (fn [] :leaky))]
               (rf.interop/add-on-dispose! r (fn [] (identical? r r)))
               (WeakReference. r))]
    (is (gc-reclaimed? wref) "an un-disposed reaction is reclaimed by GC")))
