(ns re-frame.hash-check-cljs-test
  "CLJS `render-tree-hash` and `fnv-1a-32` reproduce the JVM-computed values
  for the same input, so a server-rendered hash and a client-rendered hash
  agree."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.ssr :as rf.ssr]))

(deftest jvm-cljs-hash-parity
  (let [tree      [:div {:class "x"} [:p "hi"]]
        canonical (#'rf.ssr/canonical-edn tree)
        h         (rf.ssr/render-tree-hash tree)]
    (is (= "9d7457ef" h)
        (str "CLJS hash should equal the JVM hash for the same render tree"
             "  canonical=" (pr-str canonical) "  hash=" h))))

(deftest jvm-cljs-hash-parity-non-ascii
  ;; Both sides hash UTF-8 bytes; hashing UTF-16 code units instead would
  ;; diverge only on multi-byte content.
  (let [h (#'rf.ssr/fnv-1a-32 "café")]
    (is (= "a82b5049" h)
        (str "CLJS UTF-8 byte hash of 'café' must equal JVM UTF-8 byte hash"
             "  hash=" h))))
