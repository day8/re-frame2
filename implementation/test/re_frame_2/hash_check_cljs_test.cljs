(ns re-frame-2.hash-check-cljs-test
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame-2.ssr :as ssr]))

(deftest jvm-cljs-hash-parity
  (let [tree      [:div {:class "x"} [:p "hi"]]
        canonical (#'ssr/canonical-edn tree)
        h         (ssr/render-tree-hash tree)]
    (println :cljs-canonical canonical)
    (println :cljs-hash h)
    (is (= "9d7457ef" h)
        "CLJS hash should equal the JVM hash for the same render tree")))
