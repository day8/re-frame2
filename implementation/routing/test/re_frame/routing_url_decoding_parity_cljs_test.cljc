(ns re-frame.routing-url-decoding-parity-cljs-test
  "Per rf2-k2d2t — component URL decoding is `decodeURIComponent` on BOTH
  hosts, INCLUDING its UTF-8 validity check.

  `url-decode`'s JVM arm emulates `decodeURIComponent` on top of
  `java.net.URLDecoder`. It used to repair only ONE of the two
  differences — the form-urlencoded `+`-for-space (rf2-9a9ix) — and left
  the second standing: `URLDecoder` decodes bytes with the REPLACE
  malformed-input action, so an INVALID UTF-8 sequence was silently
  rewritten to U+FFFD and a string came back, where
  `decodeURIComponent` throws `URIError`.

  So for `%C0%80`, `%ED%A0%80`, `%FF` or `%E0%80%80` the JVM returned a
  value and CLJS returned the nil sentinel, and the public predicate
  read `(malformed-url? \"/p/%FF\")` => FALSE on JVM, TRUE on CLJS.

  The consequence was worse than the attribute-level divergence
  rf2-j3tud fixed, and the SERVER was the permissive side: a hostile URL
  failed closed to a route-miss in the browser but MATCHED under SSR
  with replacement characters standing in for the bytes, so the two
  hosts disagreed about whether the request was routable at all — a
  whole-tree Spec 011 hydration mismatch on the sink whose own docstring
  names \"hostile URLs, partner integrations with broken escaping\" as
  its motivation.

  CLJS is normative: it IS `decodeURIComponent`, the de-facto browser
  reference Spec 012 §`+` is a literal names, and §Route-miss ¶5 already
  requires malformed percent-encoding to fail the whole match closed.
  The JVM moved to match — the same direction rf2-9a9ix took for `+` and
  rf2-j3tud took for the encoder.

  THE NEAR-MISS CONTROL IS THE POINT OF THIS SUITE. `%EF%BF%BD` is the
  valid three-byte encoding of a REAL U+FFFD and MUST still decode. A
  naive \"does the decoded output contain U+FFFD?\" check would reject
  it, trading this bug for a fresh one — and could not do better, since
  the substituted and the legitimate character are identical. The fix
  discriminates at the BYTE level instead, under the UTF-8 decoder's own
  validity rules, which is why that row can pass alongside the four
  invalid ones.

  Named `*-cljs-test.cljc` so it is discovered by BOTH the cognitect JVM
  runner (`.*-test$`) and the shadow-cljs `:node-test` build
  (`cljs-test$`). Every assertion below is asserted IDENTICALLY on both
  hosts against LITERAL expected values — that host-symmetry IS the
  contract, so no expectation is ever derived from `url-decode` itself.
  A decoder that changed on one host only cannot also rewrite what it is
  compared against.

  Reverting the JVM arm to `URLDecoder` under UTF-8 turns the JVM half
  of this namespace red on the invalid-UTF-8 table, on the
  `malformed-url?` positions and on the production `match-url` prism,
  while every valid-input control — `%EF%BF%BD` included — stays green,
  proving those controls are not what carries the suite.

  THE SECOND HALF: LITERAL UTF-16 CODE UNITS. The first strict-decoder
  fix reached the byte level by percent-escaping every literal non-ASCII
  character with `String.getBytes(UTF_8)`, which silently REPLACES an
  unpaired surrogate code unit with the byte `0x3F` — a literal `?`.
  Java strings, like JavaScript strings, can legally hold one, and
  `decodeURIComponent` copies a literal to its output untouched rather
  than encoding it, so a lone U+D800 decoded to `?` on the JVM and to
  the raw code unit in the browser: the same host divergence again, and
  aliased onto a legitimate character. The JVM arm now segments the
  input, strict-decoding only the bytes the percent escapes contribute.

  THOSE TWO ROWS PULL IN OPPOSITE DIRECTIONS AND BOTH ARE ASSERTED HERE.
  A LITERAL lone surrogate must SURVIVE; a PERCENT-ENCODED one
  (`%ED%A0%80`) must still be nil, because UTF-8 has no encoding for a
  surrogate. A fix that merely passed everything through would satisfy
  the first and break the second, so the valid surrogate PAIR and the
  escaped-lone-surrogate rows are what keep the seam honest in both
  directions.

  EVERY SURROGATE CASE IS BUILT FROM NUMERIC CODE UNITS AND ASSERTED AS
  A VECTOR OF CODE UNITS. An unpaired surrogate cannot be written as a
  source literal at all (it has no UTF-8 encoding, which is the bug),
  and on output a lone surrogate, a `?` and a U+FFFD are all mojibake in
  a terminal and indistinguishable by eye. Comparing rendered strings
  would answer \"no divergence\" in the same confident voice as a real
  all-clear."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.routing :as rf.routing]
   [re-frame.routing.url :as rf.routing.url]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn rf.routing/reset-counters!}))

;; ---- code-unit instruments ----------------------------------------------
;;
;; A UTF-16 code unit is the unit of both a Java string and a JavaScript
;; string, and it is the unit this suite's surrogate half is about. Both
;; helpers below are index-based on purpose: `count`/`.length` and
;; `.charAt`/`.charCodeAt` agree on both hosts that the index runs over
;; CODE UNITS, whereas iterating a JS string with `Array.from` or `for
;; ... of` walks CODE POINTS and silently collapses a surrogate pair to
;; one element. A census run through the wrong instrument reports "no
;; divergence" exactly as convincingly as a real one.

(defn- code-units
  "`s` as a vector of its UTF-16 code units; nil passes through so the
  fail-closed sentinel stays distinguishable from an empty decode."
  [s]
  (when (some? s)
    (mapv (fn [i]
            #?(:clj  (int (.charAt ^String s i))
               :cljs (.charCodeAt s i)))
          (range (count s)))))

(defn- from-code-units
  "A string built from literal UTF-16 code units. The only way to write
  an UNPAIRED surrogate: it has no UTF-8 encoding, so it cannot appear
  as a character literal in this (UTF-8) source file."
  [& ns]
  (apply str (map char ns)))

;; ---- the UTF-8 validity boundary ----------------------------------------
;;
;; Every escape below is STRUCTURALLY well-formed — each `%` is followed
;; by two hex digits, so the structural validation both arms already had
;; passes them. What they spell is not legal UTF-8. This is the whole of
;; the divergence: it is invisible to a `%`-shape check.

(def ^:private invalid-utf8
  "Percent-escapes that parse as escapes but spell invalid UTF-8. One row
  per sequence so a failure names the exact offender."
  [["%C0%80"    "overlong NUL — C0 80 encodes U+0000 in two bytes; the shortest form is mandatory"]
   ["%ED%A0%80" "lone surrogate — D800 has no UTF-8 encoding at all"]
   ["%FF"       "invalid lead byte — FF can never begin a UTF-8 sequence"]
   ["%E0%80%80" "overlong — a three-byte encoding of a code point that needs one"]
   ["%C3"       "truncated — a two-byte lead with no continuation byte"]
   ["%80"       "bare continuation byte — 80 with no lead"]
   ["%ED%BF%BF" "lone LOW surrogate U+DFFF, escaped — the other half of the range"]
   ["%ED%A0%BD%ED%B8%80"
    "CESU-8: U+1F600's two surrogate halves escaped INDIVIDUALLY rather than as the astral code point. Each half is a surrogate, so each is refused — a pair is only a pair in UTF-16"]])

(def ^:private valid-decodes
  "Inputs that MUST still decode, with their literal expected output.
  These are the controls: the table above cannot be satisfied by
  weakening `url-decode` towards `(constantly nil)`, because every one of
  these would go nil if it were."
  [["%C3%A9"    "é"       "valid two-byte sequence — the ordinary non-ASCII case"]
   ["%EF%BF%BD" "�"  "THE NEAR-MISS: the valid encoding of a REAL U+FFFD. An output-inspecting check would reject this, since its result is character-identical to a substitution"]
   ["%E6%97%A5" "日"       "valid three-byte sequence"]
   ["%F0%9F%92%A9" "💩" "valid four-byte sequence — an astral code point"]
   ["%20"       " "       "space"]
   ["a+b"       "a+b"     "`+` stays a literal on both hosts (rf2-9a9ix)"]
   ["%2B"       "+"       "an escaped `+` decodes to `+`"]
   ["caf%C3%A9" "café"    "escapes mixed with ASCII"]])

(def ^:private structurally-malformed
  "The pre-existing structural failures. Both hosts ALREADY agreed here;
  the rows are present so a regression in the structural half — which the
  fix re-routes through ISO-8859-1 — cannot pass unnoticed."
  [["%"   "a bare `%` with nothing after it"]
   ["%a"  "a `%` with one hex digit"]
   ["%zz" "a `%` with two non-hex characters"]
   ["%C3%ZZ" "a valid lead byte followed by a structurally bad escape"]])

(deftest safe-url-decode-fails-closed-on-invalid-utf8-on-both-hosts
  (testing "invalid UTF-8 yields the nil sentinel on JVM and CLJS alike —
            the JVM used to return a U+FFFD-bearing string here (rf2-k2d2t)"
    (doseq [[in why] invalid-utf8]
      (is (nil? (rf.routing.url/safe-url-decode in)) why)))
  (testing "structurally malformed escapes stay nil on both hosts, as they
            already did — the fix must not disturb this half"
    (doseq [[in why] structurally-malformed]
      (is (nil? (rf.routing.url/safe-url-decode in)) why))))

(deftest safe-url-decode-still-decodes-every-valid-input-on-both-hosts
  (testing "the controls: valid input must NOT be rejected, so the
            fail-closed table above cannot pass by nil-ing everything"
    (doseq [[in expected why] valid-decodes]
      (is (= expected (rf.routing.url/safe-url-decode in)) why)))
  (testing "a legitimately-encoded U+FFFD survives, stated on its own
            because conflating it with a SUBSTITUTED one is the specific
            fresh bug a naive fix introduces"
    (is (= "�" (rf.routing.url/safe-url-decode "%EF%BF%BD"))
        "the valid encoding of U+FFFD decodes to U+FFFD")
    (is (= 1 (count (rf.routing.url/safe-url-decode "%EF%BF%BD")))
        "exactly one character — not an escape that leaked through raw")
    (is (nil? (rf.routing.url/safe-url-decode "%FF"))
        "while the sequence whose U+FFFD would be INVENTED by the decoder
         is refused — the two are character-identical on output, so this
         pair is what proves the check is made over bytes")))

(deftest url-decode-leaves-literal-non-ascii-alone-on-both-hosts
  (testing "an unescaped non-ASCII character is legal input and passes
            through unchanged on both hosts — the JVM fix routes bytes
            through a STRICT decoder, and must not fail closed on this"
    (doseq [[in why] [["café"    "literal é in a bare string"]
                      ["/p/café" "literal é in a path segment"]
                      ["日本"     "literal three-byte characters"]
                      ["a©b"     "a literal latin-1-range character between ASCII"]
                      ["%41é"    "a valid escape ADJACENT to a literal — decodeURIComponent yields \"Aé\""]]]
      (is (= (if (= in "%41é") "Aé" in) (rf.routing.url/safe-url-decode in)) why)))
  (testing "a literal adjacent to a TRUNCATED escape still fails closed —
            the literal's UTF-8 begins with a lead byte, so it can never
            complete the truncated run"
    (is (nil? (rf.routing.url/safe-url-decode "%C3é"))
        "decodeURIComponent throws here on both hosts")
    (is (nil? (rf.routing.url/safe-url-decode "%E2%82é"))
        "same, with a two-byte truncation")))

;; ---- literal UTF-16 code units: the surrogate seam ------------------------
;;
;; Everything above is about the BYTES a percent escape contributes.
;; This section is about the CODE UNITS a literal contributes, which the
;; decoder must never route through a byte encoder at all.

(def ^:private literal-code-unit-passthrough
  "Inputs carrying LITERAL (unescaped) UTF-16 code units, with the exact
  code-unit vector `decodeURIComponent` returns for each. Every
  expectation is a literal vector measured against native
  `decodeURIComponent`, never derived from `url-decode` itself — a
  decoder that changed on one host only must not be able to rewrite what
  it is compared against."
  [[[0xD800]                 [55296]
    "LONE HIGH surrogate — no UTF-8 encoding exists, so the previous JVM arm's String.getBytes(UTF_8) substituted 0x3F and it decoded to `?`"]
   [[0xDFFF]                 [57343]
    "LONE LOW surrogate — the other half of the range, same substitution"]
   [[97 0xD800 98]           [97 55296 98]
    "EMBEDDED lone surrogate — `a`, U+D800, `b`; the JVM returned [97 63 98]"]
   [[0xD83D 0xDE00]          [55357 56832]
    "THE PAIR CONTROL: a WELL-FORMED surrogate pair (U+1F600) must survive as its two code units. This is what stops the fix from being `pass everything through` — it was already correct, and must stay correct"]
   [[0xDFFF 0xD800]          [57343 55296]
    "a low unit followed by a high unit — adjacent but REVERSED, so still two unpaired halves rather than a pair"]])

(deftest url-decode-preserves-literal-utf16-code-units-on-both-hosts
  (testing "a literal code unit is COPIED to the output, never encoded.
            `decodeURIComponent` only ever decodes the bytes percent
            escapes contribute; routing a literal through a UTF-8 encoder
            to reach the strict decoder replaced an unpaired surrogate
            with `?` on the JVM (rf2-k2d2t)"
    (doseq [[in-units expected-units why] literal-code-unit-passthrough]
      (is (= expected-units
             (code-units (rf.routing.url/safe-url-decode (apply from-code-units in-units))))
          why)))

  (testing "a literal lone surrogate ADJACENT to a valid escape run: the
            seam keeps them apart, so neither corrupts the other"
    (is (= [55296 233]
           (code-units (rf.routing.url/safe-url-decode (str (from-code-units 0xD800) "%C3%A9"))))
        "literal U+D800 then %C3%A9 — the escape still decodes to é")
    (is (= [233 55296]
           (code-units (rf.routing.url/safe-url-decode (str "%C3%A9" (from-code-units 0xD800)))))
        "and in the other order")
    (is (= [65 55296]
           (code-units (rf.routing.url/safe-url-decode (str "%41" (from-code-units 0xD800)))))
        "an ASCII escape beside one, too"))

  (testing "THE COUNTERWEIGHT. A PERCENT-ENCODED lone surrogate must
            still fail closed: UTF-8 has no encoding for a surrogate, so
            those bytes are malformed however they arrived. A fix that
            preserved literals by weakening the escaped-byte check would
            red here"
    (is (nil? (rf.routing.url/safe-url-decode "%ED%A0%80"))
        "escaped lone HIGH surrogate stays nil")
    (is (nil? (rf.routing.url/safe-url-decode "%ED%BF%BF"))
        "escaped lone LOW surrogate stays nil")
    (is (nil? (rf.routing.url/safe-url-decode "%ED%A0%BD%ED%B8%80"))
        "and CESU-8 — U+1F600's halves escaped individually — stays nil")
    (is (= [55357 56832] (code-units (rf.routing.url/safe-url-decode "%F0%9F%98%80")))
        "while the SAME code point escaped properly, as one four-byte
         UTF-8 sequence, decodes to the same pair the literal control
         carries — so the three rows above are refusing the encoding, not
         the code point")
    (is (nil? (rf.routing.url/safe-url-decode (str "%C3" (from-code-units 0xD800))))
        "a TRUNCATED escape run flushed against a literal surrogate is
         still malformed — the seam does not rescue it"))

  (testing "`malformed-url?` agrees across hosts for a literal code unit
            in a path segment. It reads FALSE on both — a lone surrogate
            is not malformed PERCENT-ENCODING — where the escaped form
            reads TRUE on both"
    (is (false? (rf.routing/malformed-url? (str "/p/" (from-code-units 0xD800))))
        "literal lone surrogate in a path segment: decodable on both hosts")
    (is (false? (rf.routing/malformed-url? (str "/p/x?q=" (from-code-units 0xDFFF))))
        "and in a query value")
    (is (true? (rf.routing/malformed-url? "/p/%ED%A0%80"))
        "while the percent-encoded form is malformed on both hosts")))

;; ---- the public predicate, in all four URL positions ---------------------

(deftest malformed-url?-agrees-across-hosts-in-every-url-position
  (testing "an invalid-UTF-8 escape flips the predicate wherever it sits.
            `malformed-url?` discriminates the fail-closed route-miss
            ({:url url :reason :malformed-url}) from a bare miss, and it
            read FALSE on JVM / TRUE on CLJS for every one of these"
    (doseq [[u why] [["/p/%FF"          "path segment — the finding's own reproduction"]
                     ["/p/%C0%80"       "path segment, overlong NUL"]
                     ["/p/x?%FF=1"      "query KEY"]
                     ["/p/x?q=%FF"      "query VALUE"]
                     ["/p/x#%FF"        "fragment"]
                     ["/p/%ED%A0%80"    "path segment, lone surrogate"]
                     ["/a/b/%E0%80%80"  "a later path segment"]]]
      (is (true? (rf.routing/malformed-url? u)) why)))
  (testing "the controls: a URL whose escapes are all VALID is not
            malformed, in the same four positions — so the assertions
            above cannot pass by flagging every URL"
    (doseq [[u why] [["/p/%EF%BF%BD"    "path segment carrying a legitimate U+FFFD"]
                     ["/p/%C3%A9"       "path segment carrying é"]
                     ["/p/x?%C3%A9=1"   "query key carrying é"]
                     ["/p/x?q=%EF%BF%BD" "query value carrying a legitimate U+FFFD"]
                     ["/p/x#%EF%BF%BD"  "fragment carrying a legitimate U+FFFD"]
                     ["/search?q=clojure&page=2" "an ordinary URL"]
                     ["/p/café"         "a literal non-ASCII path segment"]]]
      (is (false? (rf.routing/malformed-url? u)) why)))
  (testing "the structural case still flips it, unchanged (rf2-4ic0f)"
    (is (true? (rf.routing/malformed-url? "/p/%")))))

;; ---- production prism: the SSR/browser disagreement itself ---------------

(deftest hostile-url-is-a-route-miss-on-both-hosts
  (testing "the whole-tree divergence, through production `match-url`: a
            registered route whose path param carries an invalid-UTF-8
            escape must NOT match on either host. The JVM used to match
            it, binding the param to replacement characters, so the SSR
            tree and the hydrating client tree rendered different routes"
    (rf/reg-route :decode-parity/probe {:params [:map [:slug :string]]} "/p/:slug")
    (doseq [[u why] [["/p/%FF"       "invalid lead byte in the capture"]
                     ["/p/%C0%80"    "overlong NUL in the capture"]
                     ["/p/%ED%A0%80" "lone surrogate in the capture"]]]
      (is (nil? (rf.routing/match-url u))
          (str why " — fails the whole match closed, per Spec 012 §Route-miss ¶5"))))
  (testing "the control: the SAME route matches when the escape is valid,
            so the case above cannot pass by breaking the route table"
    (rf/reg-route :decode-parity/probe2 {:params [:map [:slug :string]]} "/p/:slug")
    (let [matched (rf.routing/match-url "/p/%EF%BF%BD")]
      (is (some? matched)
          "a legitimately-encoded U+FFFD is a MATCH, not a route-miss")
      (is (= "�" (get-in matched [:params :slug]))
          "and the capture is the one replacement character it encodes"))
    (let [matched (rf.routing/match-url "/p/caf%C3%A9")]
      (is (some? matched) "an ordinary non-ASCII capture still matches")
      (is (= "café" (get-in matched [:params :slug]))
          "decoded byte-exactly"))))

(deftest route-url-round-trips-through-match-url-after-the-decoder-moved
  (testing "the encode/decode pair is still an inverse — including over a
            value containing a REAL U+FFFD, which `url-encode` emits as
            %EF%BF%BD and the strict decoder must read back"
    (rf/reg-route :decode-parity/round {:params [:map [:slug :string]]} "/r/:slug")
    (doseq [slug ["café" "日本" "a�b" "it's~a(test)!" "50% done"
                  ;; a WELL-FORMED surrogate pair, built from code units:
                  ;; `url-encode` emits it as the astral code point's
                  ;; four UTF-8 bytes and the segmenting decoder must
                  ;; read those back as the same two code units.
                  (from-code-units 0xD83D 0xDE00)]]
      (let [built  (rf.routing/route-url {:to :decode-parity/round :params {:slug slug}})
            parsed (rf.routing/match-url built)]
        (is (some? parsed)
            (str "the URL re-frame2 itself emitted for " (pr-str slug)
                 " must not be a malformed-URL route-miss"))
        (is (= slug (get-in parsed [:params :slug]))
            (str "and recovers byte-exactly: " (pr-str slug)))))))
