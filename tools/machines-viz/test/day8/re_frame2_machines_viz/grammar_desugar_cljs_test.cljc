(ns day8.re-frame2-machines-viz.grammar-desugar-cljs-test
  "`desugar-grammar` is the ingestion seam all three emitters share: it lowers
  `:timeout` / `:on-timeout` to `:after` (EP-0029 A4) and `:type :choice` to
  `:always` (A5). Each desugar, and the duration resolver, is pinned against
  the engine in `engine-grammar-parity-test`; this pins their composition."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test    :refer-macros [deftest is]])
            [day8.re-frame2-machines-viz.grammar :as g]))

(deftest desugar-grammar-applies-both-desugars
  (is (= {:initial :a
          :states  {:a    {:after {1000 :b}}
                    :gate {:always [{:target :a}]}
                    :b    {}}}
         (g/desugar-grammar {:initial :a
                             :states  {:a    {:timeout 1000 :on-timeout :b}
                                       :gate {:type :choice :choice [{:target :a}]}
                                       :b    {}}}))))

(deftest desugar-grammar-nil-safe
  (is (nil? (g/desugar-grammar nil)) "a nil definition stays the chart's empty state"))
