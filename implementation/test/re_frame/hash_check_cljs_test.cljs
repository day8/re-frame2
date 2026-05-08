(ns re-frame.hash-check-cljs-test
  "JVM ↔ CLJS canonical-edn / render-tree-hash parity smoke. The fixture
  uses re-frame.test-support/reset-runtime-fixture for symmetry with
  the rest of the CLJS suite (rf2-am9d) — even though this test only
  reads pure ssr fns, fixture uniformity makes it harder to accidentally
  re-introduce registrar pollution if the test grows."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.ssr :as ssr]
            [re-frame.test-support :as test-support]))

(use-fixtures :each (test-support/reset-runtime-fixture))

(deftest jvm-cljs-hash-parity
  (let [tree      [:div {:class "x"} [:p "hi"]]
        canonical (#'ssr/canonical-edn tree)
        h         (ssr/render-tree-hash tree)]
    (println :cljs-canonical canonical)
    (println :cljs-hash h)
    (is (= "9d7457ef" h)
        "CLJS hash should equal the JVM hash for the same render tree")))
