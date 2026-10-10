(ns re-frame.bench.fresco.lane-bytes-cljs-test
  "[[re-frame.bench.fresco.lane/utf8-bytes]] answers UTF-8 BYTES, not the
  UTF-16 code units `count` answers — the two agree on ASCII, so every row
  past the first is non-ASCII. The fixtures are `\\u` escapes so an editor
  that normalised this file's encoding cannot ASCII-fy them into a green
  gate measuring nothing. The wiring half — which instruments call it —
  is `bench_bytes.test.cjs` (no byte label beside a bare `count`), because
  a lane namespace may not require `fs`."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.bench.fresco.lane :as rf.bench.fresco.lane]))

(deftest utf8-bytes-answers-bytes-and-count-answers-code-units
  ;; Empty, ASCII, 2-, 3- and 4-byte (astral) characters, and a lone
  ;; surrogate — which a `subs` through an astral character produces, and
  ;; which `TextEncoder` writes as U+FFFD (3 bytes) rather than throwing.
  (is (= [0 3 4 5 6 3]
         (mapv rf.bench.fresco.lane/utf8-bytes
               ["" "abc" "a\u00A7b" "a\u2014b" "a\uD834\uDD1Eb" "\uD834"]))))
