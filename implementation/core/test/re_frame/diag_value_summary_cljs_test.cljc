(ns re-frame.diag-value-summary-cljs-test
  "`re-frame.error/diag-value-summary` is what framework exception messages
  and ex-data carry instead of a raw application value (Spec 015
  §Data-Classification): a message is captured off-box before any projector
  can classify a path. Callers hand it session tokens and attacker-chosen key
  sets, so a summary must carry SHAPE and nothing else — a `:type` from a
  closed vocabulary and, for a counted collection or string, an integer
  `:count`. No `:head` prefix, no map `:keys`, no `(str v)`.

  Dual-runtime `-cljs-test`; pure data."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.error :as rf.error]))

(defn- exploding-scalar
  "A host object outside every known shape whose `toString` THROWS: a
  diagnostic that explodes while describing a failure destroys the failure."
  []
  #?(:clj  (reify Object (toString [_] (throw (Exception. "boom"))))
     :cljs (let [o (js-obj)]
             (set! (.-toString o) (fn [] (throw (js/Error. "boom"))))
             o)))

(deftest every-summary-is-content-free-and-fixed-size
  (testing "each arm returns exactly its shape, so any slot derived from the
            content breaks the equality"
    (doseq [[v expected]
            [[nil                                          {:type :nil}]
             [{"SENTINEL-key" "SENTINEL-value" :b 2 :c 3}  {:type :map :count 3}]
             [{}                                           {:type :map :count 0}]
             [[:div {:on-click (fn [] nil)} "child text"]  {:type :vector :count 3}]
             [#{"SENTINEL" 2 3}                            {:type :set :count 3}]
             ["super-secret-bearer-token-value-1234567890" {:type :string :count 42}]
             [(keyword "SENTINEL")                         {:type :keyword}]
             [(symbol "SENTINEL")                          {:type :symbol}]
             [4111111111111111                             {:type :number}]
             [true                                         {:type :boolean}]
             ;; Lazy, so uncounted: realising it on the failure path is its
             ;; own hazard.
             [(map inc [1 2 3])                            {:type :seq}]
             [(fn [] nil)                                  {:type :fn}]
             [(exploding-scalar)                           {:type :scalar}]]]
      (is (= expected (rf.error/diag-value-summary v))))))
