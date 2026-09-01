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
  proving those controls are not what carries the suite."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.routing :as routing]
   [re-frame.routing.url :as url]
   [re-frame.test-support :as test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn routing/reset-counters!}))

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
   ["%80"       "bare continuation byte — 80 with no lead"]])

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
      (is (nil? (url/safe-url-decode in)) why)))
  (testing "structurally malformed escapes stay nil on both hosts, as they
            already did — the fix must not disturb this half"
    (doseq [[in why] structurally-malformed]
      (is (nil? (url/safe-url-decode in)) why))))

(deftest safe-url-decode-still-decodes-every-valid-input-on-both-hosts
  (testing "the controls: valid input must NOT be rejected, so the
            fail-closed table above cannot pass by nil-ing everything"
    (doseq [[in expected why] valid-decodes]
      (is (= expected (url/safe-url-decode in)) why)))
  (testing "a legitimately-encoded U+FFFD survives, stated on its own
            because conflating it with a SUBSTITUTED one is the specific
            fresh bug a naive fix introduces"
    (is (= "�" (url/safe-url-decode "%EF%BF%BD"))
        "the valid encoding of U+FFFD decodes to U+FFFD")
    (is (= 1 (count (url/safe-url-decode "%EF%BF%BD")))
        "exactly one character — not an escape that leaked through raw")
    (is (nil? (url/safe-url-decode "%FF"))
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
      (is (= (if (= in "%41é") "Aé" in) (url/safe-url-decode in)) why)))
  (testing "a literal adjacent to a TRUNCATED escape still fails closed —
            the literal's UTF-8 begins with a lead byte, so it can never
            complete the truncated run"
    (is (nil? (url/safe-url-decode "%C3é"))
        "decodeURIComponent throws here on both hosts")
    (is (nil? (url/safe-url-decode "%E2%82é"))
        "same, with a two-byte truncation")))

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
      (is (true? (routing/malformed-url? u)) why)))
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
      (is (false? (routing/malformed-url? u)) why)))
  (testing "the structural case still flips it, unchanged (rf2-4ic0f)"
    (is (true? (routing/malformed-url? "/p/%")))))

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
      (is (nil? (routing/match-url u))
          (str why " — fails the whole match closed, per Spec 012 §Route-miss ¶5"))))
  (testing "the control: the SAME route matches when the escape is valid,
            so the case above cannot pass by breaking the route table"
    (rf/reg-route :decode-parity/probe2 {:params [:map [:slug :string]]} "/p/:slug")
    (let [matched (routing/match-url "/p/%EF%BF%BD")]
      (is (some? matched)
          "a legitimately-encoded U+FFFD is a MATCH, not a route-miss")
      (is (= "�" (get-in matched [:params :slug]))
          "and the capture is the one replacement character it encodes"))
    (let [matched (routing/match-url "/p/caf%C3%A9")]
      (is (some? matched) "an ordinary non-ASCII capture still matches")
      (is (= "café" (get-in matched [:params :slug]))
          "decoded byte-exactly"))))

(deftest route-url-round-trips-through-match-url-after-the-decoder-moved
  (testing "the encode/decode pair is still an inverse — including over a
            value containing a REAL U+FFFD, which `url-encode` emits as
            %EF%BF%BD and the strict decoder must read back"
    (rf/reg-route :decode-parity/round {:params [:map [:slug :string]]} "/r/:slug")
    (doseq [slug ["café" "日本" "a�b" "it's~a(test)!" "50% done"]]
      (let [built  (routing/route-url {:to :decode-parity/round :params {:slug slug}})
            parsed (routing/match-url built)]
        (is (some? parsed)
            (str "the URL re-frame2 itself emitted for " (pr-str slug)
                 " must not be a malformed-URL route-miss"))
        (is (= slug (get-in parsed [:params :slug]))
            (str "and recovers byte-exactly: " (pr-str slug)))))))
