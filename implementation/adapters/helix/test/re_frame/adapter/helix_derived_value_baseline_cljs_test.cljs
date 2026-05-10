(ns re-frame.adapter.helix-derived-value-baseline-cljs-test
  "Regression coverage for rf2-66hb — Helix adapter's derived-value
  watch baseline.

  The bug: in re-frame.adapter.helix/make-derived-value the watch
  baseline (`prev-state`) was seeded from the *raw source* container
  (`(deref s)`) instead of from the *derived* value the watch callback
  later compares against. On the first source update, any derived
  projection that stays value-equal but differs in identity from the
  raw source — e.g. `(odd? x)`, `(count xs)`, `(:k m)`, `(boolean
  ...)`, vector projections — would spuriously notify subscribers.

  These tests drive `make-derived-value` directly through the adapter
  map. They count notifications received by a `subscribe-container`
  callback wired to the derived container, replace the source via
  `replace-container!`, and assert that when the derived value remains
  `=` the subscriber receives ZERO notifications. They also confirm
  that real changes still notify exactly once.

  Note: this file is intentionally identical in shape to the matching
  UIx test (re-frame.adapter.uix), with the namespace hoisted under a
  distinct file name. Both adapters share the same internals shape for
  derived-value watch baselines (rf2-66hb)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.helix :as adapter]))

;; ---- helpers --------------------------------------------------------------

(defn- make-source [v]
  ((:make-state-container adapter/adapter) v))

(defn- write! [c v]
  ((:replace-container! adapter/adapter) c v))

(defn- derive [sources f]
  ((:make-derived-value adapter/adapter) sources f))

(defn- subscribe [container]
  (let [calls (atom [])
        unsub ((:subscribe-container adapter/adapter)
               container
               (fn [prev nu] (swap! calls conj [prev nu])))]
    {:calls calls :unsub unsub}))

;; ---- tests ----------------------------------------------------------------

(deftest helix-first-update-no-op-odd?-cljs-test
  (testing "source moves 1 → 3; (odd? x) stays true; ZERO notifications"
    (let [src     (make-source 1)
          derived (derive [src] odd?)
          {:keys [calls unsub]} (subscribe derived)]
      (write! src 3)
      (is (= [] @calls)
          "first source update where derived stays = must NOT notify")
      (write! src 4)
      (is (= 1 (count @calls)) "real change notifies once")
      (is (= [true false] (first @calls)))
      (unsub))))

(deftest helix-first-update-no-op-count-cljs-test
  (testing "source moves [1 2 3] → [4 5 6]; (count xs) stays 3; ZERO notifications"
    (let [src     (make-source [1 2 3])
          derived (derive [src] count)
          {:keys [calls unsub]} (subscribe derived)]
      (write! src [4 5 6])
      (is (= [] @calls)
          "first source update where count stays = must NOT notify")
      (write! src [4 5 6 7])
      (is (= 1 (count @calls)) "real change notifies once")
      (is (= [3 4] (first @calls)))
      (unsub))))

(deftest helix-first-update-no-op-key-projection-cljs-test
  (testing "source moves {:k 1 :other 2} → {:k 1 :other 99}; (:k m) stays 1; ZERO notifications"
    (let [src     (make-source {:k 1 :other 2})
          derived (derive [src] :k)
          {:keys [calls unsub]} (subscribe derived)]
      (write! src {:k 1 :other 99})
      (is (= [] @calls)
          "first source update where (:k m) stays = must NOT notify")
      (write! src {:k 2 :other 99})
      (is (= 1 (count @calls)) "real change notifies once")
      (is (= [1 2] (first @calls)))
      (unsub))))

(deftest helix-first-update-no-op-boolean-projection-cljs-test
  (testing "source moves {:logged-in? true} → {:logged-in? true :name \"x\"}; (boolean ...) stays true; ZERO notifications"
    (let [src     (make-source {:logged-in? true})
          derived (derive [src] (fn [m] (boolean (:logged-in? m))))
          {:keys [calls unsub]} (subscribe derived)]
      (write! src {:logged-in? true :name "x"})
      (is (= [] @calls)
          "first source update where boolean projection stays = must NOT notify")
      (write! src {:logged-in? false})
      (is (= 1 (count @calls)) "real change notifies once")
      (is (= [true false] (first @calls)))
      (unsub))))

(deftest helix-first-update-no-op-vector-projection-cljs-test
  (testing "source moves {:items [1 2] :n 5} → {:items [1 2] :n 6}; (:items m) stays [1 2]; ZERO notifications"
    (let [src     (make-source {:items [1 2] :n 5})
          derived (derive [src] :items)
          {:keys [calls unsub]} (subscribe derived)]
      (write! src {:items [1 2] :n 6})
      (is (= [] @calls)
          "first source update where vector projection stays = must NOT notify")
      (write! src {:items [1 2 3] :n 6})
      (is (= 1 (count @calls)) "real change notifies once")
      (is (= [[1 2] [1 2 3]] (first @calls)))
      (unsub))))

(deftest helix-first-update-no-op-then-change-cljs-test
  (testing "the contract holds across a sequence of updates: only real = changes emit"
    (let [src     (make-source 0)
          derived (derive [src] odd?)
          {:keys [calls unsub]} (subscribe derived)]
      (write! src 2)   ;; even → even, no emit
      (write! src 4)   ;; even → even, no emit
      (write! src 5)   ;; even → odd, emit once
      (write! src 7)   ;; odd → odd, no emit
      (write! src 8)   ;; odd → even, emit once
      (is (= 2 (count @calls)))
      (is (= [false true]  (first @calls)))
      (is (= [true false]  (second @calls)))
      (unsub))))

(deftest helix-multi-source-derived-cljs-test
  (testing "multi-source derived: each source's update recomputes; only = changes emit"
    (let [a       (make-source 1)
          b       (make-source 2)
          derived (derive [a b] (fn [x y] (+ x y)))
          {:keys [calls unsub]} (subscribe derived)]
      ;; baseline derived = 3
      (write! a 2)    ;; new sum 4, prev 3 → emit
      (write! b 1)    ;; new sum 3, prev 4 → emit
      (is (= 2 (count @calls)))
      (is (= [3 4] (first @calls)))
      (is (= [4 3] (second @calls)))
      (unsub))))
