(ns re-frame.late-bind-chain-test
  "`chain-fn!` composition: the last-registered step runs first, with the same
  args, and every step's throw propagates.

  Synthetic `:test/*` keys only; the fixture restores the private atom."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.late-bind :as rf.late-bind]))

(defn isolate-hook-state [test-fn]
  (let [hooks-before @(deref #'rf.late-bind/hooks)]
    (try
      (test-fn)
      (finally
        (reset! (deref #'rf.late-bind/hooks) hooks-before)))))

(use-fixtures :each isolate-hook-state)

(deftest chain-fn!-runs-the-last-registered-step-first-with-the-same-args
  (let [k    :test/g2-args
        seen (atom [])]
    (rf.late-bind/chain-fn! k (fn [a b] (swap! seen conj [:inner a b])))
    (rf.late-bind/chain-fn! k (fn [a b] (swap! seen conj [:outer a b])))
    ((rf.late-bind/get-fn k) 1 2)
    (is (= [[:outer 1 2] [:inner 1 2]] @seen))))

(deftest chain-fn!-propagates-per-step-throws
  (let [k          :test/g2-throw-outer
        inner-ran? (atom false)]
    (rf.late-bind/chain-fn! k (fn [_] (reset! inner-ran? true)))
    (rf.late-bind/chain-fn! k (fn [_] (throw (ex-info "boom-outer" {}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom-outer"
          ((rf.late-bind/get-fn k) :arg)))
    (is (false? @inner-ran?) "the throw stops the chain")))

(deftest chain-fn!-propagates-throw-from-a-previous-step
  (let [k :test/g2-throw-inner]
    (rf.late-bind/chain-fn! k (fn [_] (throw (ex-info "boom-inner" {}))))
    (rf.late-bind/chain-fn! k (fn [_] nil))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom-inner"
          ((rf.late-bind/get-fn k) :arg))
        "the outer step does not swallow the inner step's throw")))
