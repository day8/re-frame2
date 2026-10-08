(ns re-frame.ssr.hash-parity-cljs-test
  "CLJS side of the render-tree-hash cross-host parity pins (Spec 011
  §Hydration-mismatch detection): the `TextEncoder` + `Math.imul` FNV-1a path
  must reproduce the literals `re-frame.hash-parity-test` pins on the JVM, from
  the same `.cljc` fixtures."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.ssr.hash :as rf.ssr.hash]
            [re-frame.ssr.hash-parity-fixtures :as rf.ssr.hash-parity-fixtures]))

(deftest cljs-render-tree-hash-matches-canonical-literal
  (doseq [{:keys [label input expected]} rf.ssr.hash-parity-fixtures/all-fixtures]
    (is (= expected (rf.ssr.hash/render-tree-hash input))
        (str label " — canonical " (pr-str (rf.ssr.hash/canonical-edn input))))))

(deftest cljs-render-tree-hash-prunes-nil-to-canonical-literal
  (doseq [{:keys [label input-with-nil input-without-nil expected]} rf.ssr.hash-parity-fixtures/nil-prune-pairs]
    (is (= expected (rf.ssr.hash/render-tree-hash input-without-nil)) label)
    (is (= expected (rf.ssr.hash/render-tree-hash input-with-nil)) label)))

(deftest cljs-render-tree-hash-honours-key-order-invariants
  (doseq [{:keys [label input-a input-b]} rf.ssr.hash-parity-fixtures/equality-pairs]
    (is (= (rf.ssr.hash/render-tree-hash input-a)
           (rf.ssr.hash/render-tree-hash input-b))
        label)))
