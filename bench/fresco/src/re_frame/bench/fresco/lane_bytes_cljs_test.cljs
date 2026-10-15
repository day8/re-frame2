(ns re-frame.bench.fresco.lane-bytes-cljs-test
  "UTF-8 counting covers ASCII, multibyte characters and a lone surrogate; the CJS gate checks callers."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.bench.fresco.lane :as rf.bench.fresco.lane]))

(deftest utf8-bytes-answers-bytes-and-count-answers-code-units
  ;; Empty, ASCII, 2-, 3- and 4-byte (astral) characters, and a lone
  ;; surrogate — which a `subs` through an astral character produces, and
  ;; which `TextEncoder` writes as U+FFFD (3 bytes) rather than throwing.
  (is (= [0 3 4 5 6 3]
         (mapv rf.bench.fresco.lane/utf8-bytes
               ["" "abc" "a\u00A7b" "a\u2014b" "a\uD834\uDD1Eb" "\uD834"]))))
