(ns re-frame.bench.hicasso.lane-bytes-cljs-test
  "THE LANE'S BYTE COUNT, WITNESSED (rf2-2rtt6.121).

  `rf2-2rtt6.114` repaired five sites in `ssr/driver.cjs` that published
  `String.prototype.length` — UTF-16 code units — under a bytes label, and
  pinned the repair with `ssr/bake_bytes.test.cjs`. This is that witness's
  counterpart for the CLJS half of the lane: the same defect lived in eight
  page-side instruments, they now share
  [[re-frame.bench.hicasso.lane/utf8-bytes]], and this file is what stops the
  ruler going soft again.

  ## Why an ASCII-only test cannot see the bug

  Because on ASCII the wrong expression prints the right number. That is the
  whole shape of the defect and it is why it survived: `count` and a UTF-8
  byte count agree exactly until content grows a dash, and then they diverge
  by an amount that keeps growing. So every fixture below is asserted to be
  DISCRIMINATING — `count` and `utf8-bytes` must DISAGREE on it — before it is
  asserted to be correct. A fixture that stopped discriminating would fail
  here rather than quietly stop testing anything.

  For the same reason the non-ASCII fixtures are `\\u` ESCAPES and not literal
  characters. An editor or a tool that normalised this file's encoding could
  otherwise ASCII-fy them and leave a green gate measuring nothing.

  ## The three-different-numbers case

  `U+1D11E` (MUSICAL SYMBOL G CLEF) is here because its code units (2), its
  codepoints (1) and its bytes (4) are three different numbers, so a repair
  that reached for `(count (seq s))` — codepoints — would be caught too. The
  BMP fixtures cannot catch that one; each is 1 codepoint and 1 code unit.

  ## Where the WIRING half lives, and why it is not here

  In `bench_bytes.test.cjs`, a Node harness, because it reads the repaired
  sources off disk and **a lane namespace may not require `fs`**. Every
  namespace in this directory is also compiled by `npm run
  test:hicasso-compile`, which rides `:hicasso-bench` — a BROWSER build — so a
  Node module here refuses the whole lane (`ssr/node.cljs` states the rule;
  `ssr/spike_cljs_test/sha256-hex` is why that entry reaches for
  `crypto.subtle` rather than `node:crypto`). This file therefore holds only
  what a browser can run: the behaviour of the helper itself."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.bench.hicasso.lane :as lane]))

;; ---------------------------------------------------------------------------
;; The fixtures
;; ---------------------------------------------------------------------------

(def ^:private cases
  "`{:what :s :units :codepoints :bytes}` — one ASCII control and four
  characters whose UTF-8 width is 2, 3, 3 and 4 bytes."
  [{:what "ASCII control"              :s "abc"          :units 3 :codepoints 3 :bytes 3}
   {:what "U+00A7 SECTION SIGN"        :s "a\u00A7b"     :units 3 :codepoints 3 :bytes 4}
   {:what "U+2014 EM DASH"             :s "a\u2014b"     :units 3 :codepoints 3 :bytes 5}
   {:what "U+2026 HORIZONTAL ELLIPSIS" :s "a\u2026b"     :units 3 :codepoints 3 :bytes 5}
   {:what "U+1D11E MUSICAL SYMBOL G CLEF (astral)"
    :s "a\uD834\uDD1Eb" :units 4 :codepoints 3 :bytes 6}])

(defn- codepoints
  "How many CODEPOINTS `s` holds — the third ruler, which is neither of the
  two this bead is about and is here so a repair cannot land on it by
  mistake."
  [s]
  (alength (.from js/Array s)))

;; ---------------------------------------------------------------------------
;; The helper
;; ---------------------------------------------------------------------------

(deftest every-non-ascii-fixture-can-tell-the-defect-apart
  (testing "**the anti-vacuity guard, first.** Each non-ASCII fixture is
           asserted to make `count` and `utf8-bytes` DISAGREE, so a fixture
           that lost its non-ASCII character — an encoding normalisation, a
           well-meaning tidy — fails here rather than turning the rows below
           into a test of nothing. The ASCII control is asserted the other
           way: on ASCII the two MUST agree, which is exactly why an
           ASCII-only suite could never have caught this."
    (doseq [{:keys [what s]} (rest cases)]
      (is (not= (count s) (lane/utf8-bytes s))
          (str what ": cannot distinguish the defect")))
    (let [ascii (:s (first cases))]
      (is (= (count ascii) (lane/utf8-bytes ascii))
          "and on pure ASCII the old expression and the new one agree"))))

(deftest utf8-bytes-answers-bytes-and-count-answers-code-units
  (testing "The two rulers, stated side by side on every fixture. `count`
           answers the UTF-16 code units the defect published; `utf8-bytes`
           answers the bytes the label claims. The astral row is the one
           where a third answer — codepoints — is also distinct, so a repair
           that reached for codepoints instead of bytes is caught here too."
    (doseq [{:keys [what s units bytes] cps :codepoints} cases]
      (is (= units (count s)) (str what ": the fixture is not the string this row describes"))
      (is (= cps (codepoints s)) (str what ": codepoints"))
      (is (= bytes (lane/utf8-bytes s)) (str what ": bytes")))))

(deftest the-error-grows-with-the-content
  (testing "**why this is not a rounding difference.** The gap is not a
           constant to be waved through: it is one byte per two-byte
           character and two per three-byte one, so it scales with the page.
           A thousand em dashes is two thousand bytes of understatement, and
           an instrument understating by a growing margin reads plausible for
           ever."
    (let [dashes (str/join (repeat 1000 "\u2014"))]
      (is (= 3 (lane/utf8-bytes "\u2014")))
      (is (= 1000 (count dashes)))
      (is (= 3000 (lane/utf8-bytes dashes)))
      (is (= 2000 (- (lane/utf8-bytes dashes) (count dashes)))
          "the understatement is 2 bytes per em dash and there are a thousand"))))

(deftest utf8-bytes-is-total-over-the-degenerate-inputs
  (testing "The empty string weighs nothing, and a lone surrogate — which a
           `subs` through the middle of an astral character can produce —
           still answers a number rather than throwing. `TextEncoder`
           substitutes U+FFFD, three bytes, which is what a UTF-8 encoder
           writing that string to a socket would also do."
    (is (= 0 (lane/utf8-bytes "")))
    (is (= 3 (lane/utf8-bytes "\uD834")) "an unpaired high surrogate encodes as U+FFFD")))

(deftest utf8-bytes-agrees-with-the-encoder-the-lane-already-trusts
  (testing "**a second derivation, not a second call to the same function.**
           `ssr/spike_cljs_test/sha256-hex` has been feeding
           `(.encode (js/TextEncoder.) s)` to `crypto.subtle` since that
           witness was written, so the lane already stakes a published SHA-256
           on this encoder's output. Deriving the length from a
           `Uint8Array` built the same way — and comparing it against
           `utf8-bytes` on every fixture — ties the new helper to the one the
           lane was already trusting, rather than to itself."
    (doseq [{:keys [what s bytes]} cases]
      (let [^js buf (.encode (js/TextEncoder.) s)]
        (is (= bytes (.-byteLength buf))
            (str what ": the encoder the digest rows use agrees"))
        (is (= (lane/utf8-bytes s) (.-byteLength buf))
            (str what ": and utf8-bytes agrees with it"))))))
