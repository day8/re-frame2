(ns re-frame.hash-check-cljs-test
  "CLJS `fnv-1a-32` and `data-hash` reproduce the JVM-computed values for the
  same input (`re-frame.ssr-hash-test` pins the same literals there), so two
  roots reading one payload agree on its install-ledger digest on either
  host."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.ssr.hash :as rf.ssr.hash]))

(deftest jvm-cljs-data-hash-parity
  (let [value     {:b [nil 7.0] :a #{2 1}}
        canonical (rf.ssr.hash/canonical-data-edn value)
        h         (rf.ssr.hash/data-hash value)]
    (is (= ["{:a #{1 2},:b [nil 7]}" "0f358b10"] [canonical h])
        (str "CLJS digest should equal the JVM digest for the same value"
             "  canonical=" (pr-str canonical) "  hash=" h))))

(deftest jvm-cljs-hash-parity-non-ascii
  ;; Both sides hash UTF-8 bytes; hashing UTF-16 code units instead would
  ;; diverge only on multi-byte content.
  (let [h (rf.ssr.hash/fnv-1a-32 "café")]
    (is (= "a82b5049" h)
        (str "CLJS UTF-8 byte hash of 'café' must equal JVM UTF-8 byte hash"
             "  hash=" h))))
