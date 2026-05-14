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
    ;; Silent-on-success (rf2-try1x): canonical-edn + hash are now
    ;; surfaced in the `is` message so they only appear on failure.
    (is (= "9d7457ef" h)
        (str "CLJS hash should equal the JVM hash for the same render tree"
             "  canonical=" (pr-str canonical) "  hash=" h))))

(deftest jvm-cljs-hash-parity-non-ascii
  ;; Per rf2-t7ktb: fnv-1a-32 now hashes the UTF-8 byte sequence on
  ;; BOTH sides (JVM via String.getBytes(UTF_8); CLJS via TextEncoder).
  ;; UTF-8 is byte-deterministic so both sides MUST agree on non-ASCII
  ;; content. The expected value below is the JVM-computed hash for the
  ;; canonical-EDN of the tree containing 'café'; CLJS must reproduce it
  ;; byte-for-byte. Without this pin a future regression that flipped
  ;; one side back to UTF-16 code units would only fail on multi-byte
  ;; codepoints — silently shipping a hash mismatch in production for
  ;; non-English content.
  (let [h (#'ssr/fnv-1a-32 "café")]
    (is (= "a82b5049" h)
        (str "CLJS UTF-8 byte hash of 'café' must equal JVM UTF-8 byte hash"
             "  hash=" h))))
