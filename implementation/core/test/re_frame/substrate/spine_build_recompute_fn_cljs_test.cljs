(ns re-frame.substrate.spine-build-recompute-fn-cljs-test
  "`re-frame.substrate.spine/build-recompute-fn`: every arity derefs its
  sources afresh on each call and hands the values to `compute-fn` in
  source-vector order, inside the recompute.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.substrate.spine :as rf.substrate.spine]))

(deftest each-arity-derefs-its-sources-afresh-and-in-order
  ;; `-` is non-commutative, so each row also pins argument order.
  (let [s0  (atom 100)
        s1  (atom 1)
        s2  (atom 1000)
        fs  [(rf.substrate.spine/build-recompute-fn [] (constantly :seed))
             (rf.substrate.spine/build-recompute-fn [s0] -)
             (rf.substrate.spine/build-recompute-fn [s0 s1] -)
             (rf.substrate.spine/build-recompute-fn [s0 s1 s2] -)]
        run (fn [] (mapv #(%) fs))]
    (is (= [:seed -100 99 -901] (run)))
    (reset! s0 200)
    (is (= [:seed -200 199 -801] (run)))))

(deftest n-arity-derefs-every-source-inside-the-recompute
  ;; A lazy `map deref` realises in 32-element chunks and `apply` forces only
  ;; the head of its arg seq, so with 33 sources and a compute-fn that returns
  ;; its args unread, a lazy recompute would defer the 33rd deref past the
  ;; recompute boundary.
  (let [derefs  (atom 0)
        sources (mapv (fn [i] (reify IDeref (-deref [_] (swap! derefs inc) i)))
                      (range 33))]
    ((rf.substrate.spine/build-recompute-fn sources (fn [& args] args)))
    (is (= 33 @derefs))))
