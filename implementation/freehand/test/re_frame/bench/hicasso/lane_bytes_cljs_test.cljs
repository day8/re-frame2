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

  ## The wiring half

  A correct helper nobody calls repairs nothing, so the second half of this
  file reads the repaired sources and asserts each site now goes through it.
  Node-only (`fs`), which is where this namespace runs: the `:node-test`
  build's `cljs-test$` selects it and the `:browser-test` build's
  `-dom-cljs-test$` does not. Paths are relative to `implementation/`, where
  `npm run test:cljs` runs — the arrangement
  [[re-frame.freehand.bench.b5-matched-builds-cljs-test]] already uses — and
  every one is asserted to EXIST, so a moved file fails loudly instead of
  passing over an empty string."
  (:require ["fs" :as fs]
            [cljs.test :refer-macros [deftest is testing]]
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
  (count (seq (.from js/Array s))))

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
    (let [one   (lane/utf8-bytes "\u2014")
          many  (lane/utf8-bytes (str/join (repeat 1000 "\u2014")))]
      (is (= 3 one))
      (is (= 3000 many))
      (is (= 1000 (count (str/join (repeat 1000 "\u2014")))))
      (is (= 2000 (- many (count (str/join (repeat 1000 "\u2014")))))
          "the understatement is 2 bytes per em dash and there are a thousand"))))

(deftest utf8-bytes-is-total-over-the-degenerate-inputs
  (testing "The empty string weighs nothing, and a lone surrogate — which a
           `subs` through the middle of an astral character can produce —
           still answers a number rather than throwing. `TextEncoder`
           substitutes U+FFFD, three bytes, which is what a UTF-8 encoder
           writing that string to a socket would also do."
    (is (= 0 (lane/utf8-bytes "")))
    (is (= 3 (lane/utf8-bytes "\ud834")) "an unpaired high surrogate encodes as U+FFFD")))

;; ---------------------------------------------------------------------------
;; The wiring — `utf8-bytes` is load-bearing, not decorative
;; ---------------------------------------------------------------------------

(def ^:private hicasso
  "The lane's source directory, relative to `implementation/`."
  "freehand/test/re_frame/bench/hicasso")

(defn- src [rel]
  (let [p (str hicasso "/" rel)]
    (is (.existsSync fs p)
        (str "the repaired source must be at " p
             " — this lane runs from implementation/ (cwd " (.cwd js/process) ")"))
    (.readFileSync fs p "utf8")))

(def ^:private converted
  "The sites whose figure is a SIZE, and which therefore had to be converted
  rather than relabelled — file, and the expression that must now be there."
  {"clock_app.cljs"                              ":bytes (lane/utf8-bytes s)"
   "hd8_clock_app.cljs"                          ":bytes   (lane/utf8-bytes s)"
   "shapes/census_clock_app.cljs"                ":bytes   (lane/utf8-bytes s)"
   "walk_profile_app.cljs"                       ":bytes (lane/utf8-bytes canon-real)"
   "walk_vs_reagent_app.cljs"                    ":bytes    (lane/utf8-bytes canon)"
   "ssr/spike_cljs_test.cljs"                    ":bytes        (lane/utf8-bytes (:document a))"
   "ssr/spike_dom_cljs_test.cljs"                ":canonical-bytes  (lane/utf8-bytes hydrated-dom)"
   "ssr/instance_key_payload_dom_cljs_test.cljs" ":green-edn-bytes (lane/utf8-bytes (:payload-edn green))"})

(deftest every-published-byte-figure-goes-through-utf8-bytes
  (testing "Each site that publishes a figure under a bytes label now counts
           bytes. Asserted on the source text rather than on behaviour
           because most of these sites need a browser to reach, and a
           behavioural pin that only runs in Chromium is a pin that a Node
           gate cannot hold."
    (doseq [[file expr] converted]
      (is (str/includes? (src file) expr)
          (str file " must read `" expr "`")))))

(deftest no-published-byte-figure-still-reads-count
  (testing "**the other direction.** The expressions above could be added
           beside the old ones rather than in place of them, so this asserts
           the absence: no line in these files pairs a `bytes` key with a
           bare `count`. Line-scoped on purpose — `count` is everywhere in
           this lane and legitimately so; what is banned is `count` sitting
           under a byte label."
    (doseq [file (keys converted)]
      (doseq [line (str/split-lines (src file))]
        (when (and (re-find #"(?i)bytes" line) (re-find #"\(count\b" line))
          (is false (str file ": a bytes label over `count` — " (str/trim line))))))))

(deftest the-two-relabelled-diagnostics-no-longer-claim-bytes
  (testing "Two sites were RELABELLED rather than converted, because code
           units are genuinely what they wanted: `parity_probe_app` prints
           its lengths beside a `.charAt` offset, and `inpage_ladder_app`
           reports ours-against-reference on one refusal. A true value under
           a true name is a repair; what must not survive is the word
           `bytes` over either of them."
    (let [probe  (src "parity_probe_app.cljs")
          ladder (src "inpage_ladder_app.cljs")]
      (is (str/includes? probe "uix-code-units"))
      (is (str/includes? probe "hicasso-code-units"))
      (is (not (str/includes? probe "uix-bytes")))
      (is (not (str/includes? probe "hicasso-bytes")))
      (is (str/includes? ladder ":code-units-ours"))
      (is (str/includes? ladder ":code-units-reference"))
      (is (not (str/includes? ladder ":bytes-ours")))
      (is (not (str/includes? ladder ":bytes-reference"))))))

(deftest the-driver-side-bundle-size-asks-the-file-system
  (testing "`keywarn_elision_run.cjs` is Node, not the page, and the string
           it was measuring had just been read from a file — so the honest
           answer is the file's own size and not a re-derivation from the
           decoded string."
    (let [run (src "keywarn_elision_run.cjs")]
      (is (str/includes? run "${fs.statSync(bundle).size} bytes"))
      (is (not (str/includes? run "${blob.length} bytes"))))))

(deftest the-helper-is-utf8-by-construction
  (testing "`TextEncoder` and not a call carrying an encoding argument. The
           SSR driver's `utf8Bytes` has to name `'utf8'` explicitly and its
           witness pins that spelling, because `Buffer.byteLength` takes an
           encoding a later edit could drop. `TextEncoder` cannot be given
           one: it is UTF-8 or it is nothing, so there is no argument here to
           pin and no way to silently switch the ruler."
    (let [ln (src "lane.cljs")]
      (is (str/includes? ln "(js/TextEncoder.)"))
      (is (not (str/includes? ln "Buffer.byteLength"))))))
