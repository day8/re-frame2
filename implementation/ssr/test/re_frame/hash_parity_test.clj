(ns re-frame.hash-parity-test
  "JVM side of the render-tree-hash cross-host parity pins (Spec 011
  §Hydration-mismatch detection). `re-frame.ssr.hash-parity-cljs-test` pins the
  same literals from the same `.cljc` fixtures on CLJS."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.ssr.hash :as rf.ssr.hash]
            [re-frame.ssr.hash-parity-fixtures :as fixtures]))

(deftest jvm-render-tree-hash-matches-canonical-literal
  (doseq [{:keys [label input expected]} fixtures/all-fixtures]
    (is (= expected (rf.ssr.hash/render-tree-hash input))
        (str label " — canonical " (pr-str (rf.ssr.hash/canonical-edn input))))))

(deftest jvm-render-tree-hash-prunes-nil-to-canonical-literal
  (doseq [{:keys [label input-with-nil input-without-nil expected]} fixtures/nil-prune-pairs]
    (is (= expected (rf.ssr.hash/render-tree-hash input-without-nil)) label)
    (is (= expected (rf.ssr.hash/render-tree-hash input-with-nil)) label)))

(deftest jvm-render-tree-hash-honours-key-order-invariants
  (doseq [{:keys [label input-a input-b]} fixtures/equality-pairs]
    (is (= (rf.ssr.hash/render-tree-hash input-a)
           (rf.ssr.hash/render-tree-hash input-b))
        label)))
