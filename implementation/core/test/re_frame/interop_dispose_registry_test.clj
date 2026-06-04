(ns re-frame.interop-dispose-registry-test
  "Per rf2-tnnln — the JVM on-dispose callback storage must not leak
  reactions in long-lived SSR processes.

  The prior design held on-dispose callbacks in a process-wide
  strong-ref `atom`-of-map keyed by reaction identity. Correctness
  relied on PERFECT dispose symmetry: every `add-on-dispose!`'d
  reaction had to reach `dispose!`, or the reaction (and its whole
  layer-2+ input chain + compute-fn) was pinned in the registry
  forever — the GC could not reclaim it even when nothing else
  referenced it. For Spec 011's frame-per-request SSR profile (a JVM
  server that churns frames continuously) that is a real leak surface.

  The fix carries the callbacks ON the reaction object (an
  `interop/make-reaction` `Reaction` deftype with a mutable callbacks
  field), mirroring the CLJS plain-atom adapter. An orphaned reaction
  is therefore GC-reclaimable along with its callbacks.

  This suite pins:
   (a) dispose symmetry / ordering / idempotency behaviour is unchanged;
   (b) an un-disposed (orphaned) reaction is reclaimed by GC — the
       behavioural delta the weak storage exists to deliver."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.interop :as interop])
  (:import [java.lang.ref WeakReference]))

;; ---- dispose symmetry / ordering / idempotency ----------------------------

(deftest callbacks-fire-on-dispose-in-registration-order
  (testing "add-on-dispose! callbacks fire in registration order on dispose!"
    (let [r     (interop/make-reaction (fn [] :v))
          fired (atom [])]
      (interop/add-on-dispose! r (fn [] (swap! fired conj :a)))
      (interop/add-on-dispose! r (fn [] (swap! fired conj :b)))
      (interop/add-on-dispose! r (fn [] (swap! fired conj :c)))
      (is (= :v @r) "the reaction still derefs to its computed value")
      (interop/dispose! r)
      (is (= [:a :b :c] @fired)
          "callbacks fired in registration order"))))

(deftest dispose-is-idempotent
  (testing "a second dispose! is a no-op — callbacks cleared on first dispose"
    (let [r     (interop/make-reaction (fn [] nil))
          fired (atom 0)]
      (interop/add-on-dispose! r (fn [] (swap! fired inc)))
      (interop/dispose! r)
      (interop/dispose! r)
      (interop/dispose! r)
      (is (= 1 @fired)
          "callback fired exactly once across three dispose! calls"))))

(deftest dispose-clears-before-fire
  (testing "callbacks are cleared BEFORE firing — a re-entrant dispose! from
            inside a callback does not re-fire the set"
    (let [r     (interop/make-reaction (fn [] nil))
          fired (atom 0)]
      (interop/add-on-dispose! r (fn []
                                   (swap! fired inc)
                                   ;; Re-entrant dispose of the same reaction
                                   ;; must find an empty callback set.
                                   (interop/dispose! r)))
      (interop/dispose! r)
      (is (= 1 @fired)
          "the re-entrant dispose! did not re-fire the callback"))))

(deftest dispose-with-no-callbacks-is-a-noop
  (testing "dispose! on a reaction that never registered a callback is safe"
    (let [r (interop/make-reaction (fn [] 1))]
      (is (nil? (interop/dispose! r))
          "dispose! returns nil and does not throw"))))

;; ---- fallback path for non-Reaction deref-ables ---------------------------

(deftest bare-deref-able-still-participates-via-fallback
  (testing "a bare reify IDeref (test double) still gets add-on-dispose! /
            dispose! via the weak-keyed fallback registry"
    (let [bare  (reify clojure.lang.IDeref (deref [_] nil))
          fired (atom [])]
      (interop/add-on-dispose! bare (fn [] (swap! fired conj :x)))
      (interop/add-on-dispose! bare (fn [] (swap! fired conj :y)))
      (interop/dispose! bare)
      (is (= [:x :y] @fired)
          "fallback registry fires callbacks in registration order")
      ;; idempotent on the fallback path too
      (interop/dispose! bare)
      (is (= [:x :y] @fired)
          "second dispose! on the fallback path is a no-op"))))

;; ---- the leak fix: an orphaned reaction is GC-reclaimable -----------------

(defn- gc-reclaimed?
  "Return true when the referent of `wref` is reclaimed within a few GC
  nudges. We can't force GC deterministically, so we loop a bounded
  number of times with `System/gc` + a short pause, returning early on
  reclamation."
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
  (testing "Per rf2-tnnln: a reaction registered with an on-dispose callback
            (including the production sub-cache shape, where the callback
            CLOSES OVER the reaction) but NEVER explicitly disposed is still
            reclaimed by GC. Pre-fix the global strong-ref registry pinned it
            forever; on-object storage ties its lifetime to the reaction."
    (let [wref (let [r (interop/make-reaction (fn [] :leaky))]
                 ;; Register a callback that closes over the reaction itself
                 ;; — the exact shape `re-frame.subs`'s sub-cache disposal
                 ;; closure has (it captures `reaction` for an identity
                 ;; guard). Under the old global strong-ref registry this
                 ;; pinned the reaction: registry value -> closure -> reaction.
                 (interop/add-on-dispose! r (fn [] (identical? r r)))
                 ;; Deliberately DO NOT dispose! — simulate an orphaned
                 ;; reaction (partial-teardown bug / exception before the
                 ;; eventual dispose!). Hand back only a weak reference.
                 (WeakReference. r))]
      ;; Local strong ref `r` is now out of scope; nothing else references
      ;; the reaction. It must be reclaimable.
      (is (gc-reclaimed? wref)
          "the un-disposed reaction (with a self-referencing on-dispose
           callback) was reclaimed by GC — no permanent leak"))))

(deftest reaction-strongly-held-is-not-reclaimed
  (testing "control: while a strong reference is retained, the reaction is
            NOT reclaimed — confirms `gc-reclaimed?` actually observes
            reachability rather than always reporting reclamation."
    (let [r    (interop/make-reaction (fn [] :held))
          wref (WeakReference. r)]
      (interop/add-on-dispose! r (fn [] nil))
      (is (false? (gc-reclaimed? wref))
          "a live reaction is retained across GC")
      ;; Keep `r` strongly reachable past the assertion.
      (is (= :held @r)))))
