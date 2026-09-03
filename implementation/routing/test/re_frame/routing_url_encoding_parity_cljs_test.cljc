(ns re-frame.routing-url-encoding-parity-cljs-test
  "Per rf2-j3tud — component URL encoding is `encodeURIComponent` on BOTH
  hosts, byte for byte.

  `url-encode`'s JVM arm emulates `encodeURIComponent` on top of
  `java.net.URLEncoder`. It used to repair only ONE of the two
  differences — the form-urlencoded `+`-for-space — and left the second
  standing: `URLEncoder`'s unescaped set is narrower, so it escapes `!`
  `'` `(` `)` `~` where `encodeURIComponent` leaves them literal. The
  same route data therefore emitted different URL bytes on server and
  browser: a legitimate slug `draft~1` became `/articles/draft%7E1` from
  SSR and `/articles/draft~1` from the hydrated client.

  Both spellings decode to the same route, which is why nothing caught
  it — but Spec 012 §Bidirectional URL ↔ params promises the stronger
  invariant: component-wise `encodeURIComponent` emission, ONE
  host-independent canonical URL. `route-link`'s `:href`, SSR canonical
  head links, copied URLs, cache keys and snapshots all read those
  bytes, and an `:href` that differs between the server tree and the
  first client tree is the Spec 011 hydration-mismatch class.

  CLJS is normative: it IS `encodeURIComponent`, the de-facto browser
  reference the spec names. The JVM moved to match it — the same
  direction `url-decode` already took for `+` (rf2-9a9ix).

  Named `*-cljs-test.cljc` so it is discovered by BOTH the cognitect JVM
  runner (`.*-test$`) and the shadow-cljs `:node-test` build
  (`cljs-test$`). Every assertion below is asserted IDENTICALLY on both
  hosts against LITERAL expected strings — that host-symmetry IS the
  contract, so the expectations are never derived from `url-encode`
  itself. An encoder that changed on one host only cannot also rewrite
  what it is compared against.

  THE UNESCAPED SET WAS ONLY HALF OF IT. `encodeURIComponent` also
  REFUSES input it cannot encode — an unpaired surrogate — and the JVM
  arm silently substituted `%3F` there instead, so the same address
  emitted a URL from SSR that the browser threw on. That half is pinned
  in its own section below and is the reason this suite grew a
  fail-loud table alongside its literal-output ones.

  Reverting the JVM arm to `URLEncoder` plus the `+`→`%20` swap alone
  turns the JVM half of this namespace red on the boundary set, the
  production `route-url` case and the round-trip; removing the
  unpaired-surrogate guard turns the fail-loud table and the surrogate
  prism red. In BOTH sabotages every valid-input control — the escaped
  characters, the literal `?`, the well-formed surrogate pair — stays
  green, which is what proves the controls are not what carries the
  suite."
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

;; ---- the encodeURIComponent boundary ------------------------------------
;;
;; `encodeURIComponent` escapes everything except the unreserved set
;; (`A-Z a-z 0-9`) plus the nine RFC-2396 *mark* characters. Those nine
;; are the whole boundary; four of them (`- _ . *`) `URLEncoder` already
;; agreed on, and five (`! ' ( ) ~`) it did not. The table pins all nine
;; so a future encoder swap cannot half-move the line.

(def ^:private unescaped-marks
  "Every character `encodeURIComponent` leaves LITERAL, one row per
  character so a failure names the exact offender rather than a diff of
  a nine-character blob."
  [["!" "exclamation — URLEncoder escaped this to %21"]
   ["'" "apostrophe — URLEncoder escaped this to %27"]
   ["(" "open paren — URLEncoder escaped this to %28"]
   [")" "close paren — URLEncoder escaped this to %29"]
   ["~" "tilde — URLEncoder escaped this to %7E"]
   ["*" "asterisk — both encoders already agreed"]
   ["-" "hyphen — both encoders already agreed"]
   ["." "period — both encoders already agreed"]
   ["_" "underscore — both encoders already agreed"]])

(def ^:private escaped-controls
  "Characters that MUST stay percent-escaped. These are the controls: the
  suite cannot be passed by weakening `url-encode` towards `identity`,
  because every one of these would go literal if it were."
  [[" " "%20" "space — %20, never the form-urlencoded `+`"]
   ["/" "%2F" "slash — a raw `/` would forge an extra path segment"]
   ["%" "%25" "percent — a raw `%` makes the URL malformed on re-parse"]
   ["&" "%26" "ampersand — a raw `&` would forge an extra query pair"]
   ["=" "%3D" "equals — a raw `=` would forge a query key/value split"]
   ["?" "%3F" "question mark — a raw `?` would forge a query string"]
   ["#" "%23" "hash — a raw `#` would forge a fragment"]
   ["+" "%2B" "plus — escaped on emission so it survives as a literal"]])

(deftest url-encode-leaves-every-unescaped-mark-literal-on-both-hosts
  (testing "the complete encodeURIComponent unescaped boundary is literal
            on JVM and CLJS alike (rf2-j3tud)"
    (doseq [[ch why] unescaped-marks]
      (is (= ch (rf.routing.url/url-encode ch)) why)))
  (testing "the whole mark set at once, as one component"
    (is (= "!'()~*-._" (rf.routing.url/url-encode "!'()~*-._"))
        "no character in the mark set is escaped on this host")
    (is (= "draft~1" (rf.routing.url/url-encode "draft~1"))
        "the failure scenario from the finding: a `~`-bearing slug")))

(deftest url-encode-still-escapes-the-structural-characters-on-both-hosts
  (testing "the controls: characters that must NOT go literal, so the
            boundary test above cannot pass by disabling encoding"
    (doseq [[ch expected why] escaped-controls]
      (is (= expected (rf.routing.url/url-encode ch)) why)))
  (testing "non-ASCII still percent-encodes as UTF-8 on both hosts"
    (is (= "%C3%A9" (rf.routing.url/url-encode "é"))
        "`é` is two UTF-8 bytes, uppercase hex on both hosts")))

(deftest url-encode-splat-inherits-the-boundary-per-segment
  (testing "splat encoding preserves structural `/` while each segment
            gets the same literal mark set (Spec 012 §Splat)"
    (is (= "a~b/c!d/e(f)" (rf.routing.url/url-encode-splat "a~b/c!d/e(f)"))
        "marks stay literal inside each chunk; separators stay raw")
    (is (= "my%20file/50%25.txt" (rf.routing.url/url-encode-splat "my file/50%.txt"))
        "the controls still escape per chunk")))

;; ---- unpaired surrogates: the fail-LOUD half of the boundary -------------
;;
;; `encodeURIComponent` is not only a mapping, it is also a REFUSAL: a
;; code unit in U+D800–U+DFFF that is not half of a well-formed pair
;; spells no code point, has no UTF-8 encoding, and raises `URIError`.
;; Java's UTF-8 encoder SUBSTITUTES instead, and `URLEncoder` runs it
;; under the default REPLACE action, so the JVM arm emitted `%3F` — the
;; encoding of a literal `?`. Malformed route data was therefore ALIASED
;; onto a valid component that decodes back to a different string, and it
;; reached the SSR href / canonical link / cache key while the browser
;; refused the very same address (rf2-j3tud, audit of PR #8873).
;;
;; Every input below is built from NUMERIC code units rather than written
;; as a character literal. A lone surrogate does not survive every editor,
;; shell or file re-encoding intact, and a mangled one would quietly turn
;; these rows into assertions about some other string; `code-units` plus
;; the instrument control below is what keeps that honest.

(defn- code-units
  "Build a string from raw UTF-16 code-unit values. Host-symmetric: a
  Java `String` and a JavaScript string are both UTF-16 code-unit
  sequences, so the same integers name the same string on both."
  [codes]
  (apply str (map (fn [c] #?(:clj  (str (char c))
                             :cljs (js/String.fromCharCode c)))
                  codes)))

(defn- code-unit-at
  "The numeric UTF-16 code unit at index `i` of `s`."
  [s i]
  #?(:clj  (int (.charAt ^String s i))
     :cljs (.charCodeAt s i)))

(def ^:private unpaired-surrogates
  "Code-unit sequences `encodeURIComponent` REFUSES. One row per shape so
  a failure names the exact offender."
  [[[0xD800]               "lone HIGH surrogate — the JVM emitted %3F"]
   [[0xDFFF]               "lone LOW surrogate — the JVM emitted %3F"]
   [[0x0061 0xD800 0x0062] "a lone high surrogate EMBEDDED between ASCII — the JVM emitted a%3Fb"]
   [[0xD800 0x0078]        "a high surrogate followed by ASCII instead of its low partner"]
   [[0x0078 0xDC00]        "a low surrogate preceded by ASCII instead of its high partner"]
   [[0xDFFF 0xD800]        "a REVERSED pair — low then high is two orphans, not a pair"]
   [[0xD83D 0x0062]        "the high half of a REAL pair (U+1F600), orphaned by its partner"]
   [[0xDE00]               "the low half of that same real pair, standing alone"]])

(def ^:private well-formed-code-unit-strings
  "Inputs that MUST still encode, with their literal expected output.
  These are the controls: the refusal table above cannot be satisfied by
  making `url-encode` throw more freely, because every one of these would
  start throwing if it did."
  [[[0xD83D 0xDE00]  "%F0%9F%98%80" "a WELL-FORMED surrogate pair — U+1F600, four UTF-8 bytes. The guard inspects pairing, not the surrogate range"]
   [[0xDBFF 0xDFFF]  "%F4%8F%BF%BF" "the LAST well-formed pair — U+10FFFF, the top of Unicode"]
   [[0xD800 0xDC00]  "%F0%90%80%80" "the FIRST well-formed pair — U+10000, built from two units each of which the table above refuses on its own"]
   [[0x003F]         "%3F"          "a LITERAL question mark — the exact output the JVM used to alias every lone surrogate onto, so it must still be reachable by legitimate input"]
   [[0x00E9]         "%C3%A9"       "é — an ordinary non-ASCII BMP character"]
   [[0x65E5]         "%E6%97%A5"    "日 — a three-byte BMP character"]
   [[0xFFFD]         "%EF%BF%BD"    "a real U+FFFD — the character just BELOW the surrogate-adjacent range, and the near-miss the decoder side turns on"]
   [[0x0061 0x0062]  "ab"           "plain ASCII"]])

(deftest code-unit-helper-builds-the-strings-these-tables-claim
  (testing "the instrument control: assert on NUMBERS, so a file
            re-encoding that mangled a surrogate into U+FFFD (or into a
            `?`) makes this red rather than quietly rewriting the
            subject of every row below"
    (let [high (code-units [0xD800])]
      (is (= 1 (count high)) "a lone high surrogate is ONE code unit")
      (is (= 0xD800 (code-unit-at high 0)) "and it really is D800"))
    (let [low (code-units [0xDFFF])]
      (is (= 1 (count low)) "a lone low surrogate is ONE code unit")
      (is (= 0xDFFF (code-unit-at low 0)) "and it really is DFFF"))
    (let [pair (code-units [0xD83D 0xDE00])]
      (is (= 2 (count pair)) "U+1F600 is TWO code units in UTF-16")
      (is (= 0xD83D (code-unit-at pair 0)) "high half")
      (is (= 0xDE00 (code-unit-at pair 1)) "low half"))
    (is (= 0x003F (code-unit-at (code-units [0x003F]) 0))
        "the literal `?` control is a real question mark, not a
         substituted surrogate")))

(deftest url-encode-refuses-unpaired-surrogates-on-both-hosts
  (testing "an unpaired surrogate is REFUSED, not substituted. CLJS
            throws `URIError`; the JVM used to return %3F and now throws
            too (rf2-j3tud)"
    (doseq [[codes why] unpaired-surrogates]
      (is (thrown? #?(:clj Throwable :cljs :default)
                   (rf.routing.url/url-encode (code-units codes)))
          why)))
  (testing "the aliasing itself, stated directly: whatever `url-encode`
            does with a lone surrogate, it must not be what it does with
            a literal `?` — those two inputs are different strings"
    (is (= "%3F" (rf.routing.url/url-encode (code-units [0x003F])))
        "a literal `?` still encodes to %3F on both hosts")
    (is (thrown? #?(:clj Throwable :cljs :default)
                 (rf.routing.url/url-encode (code-units [0xD800])))
        "while the lone surrogate that used to produce the SAME bytes is
         now refused on both hosts")))

(deftest url-encode-still-encodes-every-well-formed-string-on-both-hosts
  (testing "the controls: valid input must NOT be refused, so the table
            above cannot pass by throwing on everything. A surrogate PAIR
            is legitimate input and still encodes to its astral code
            point's UTF-8 bytes"
    (doseq [[codes expected why] well-formed-code-unit-strings]
      (is (= expected (rf.routing.url/url-encode (code-units codes))) why)))
  (testing "the whole ASCII mark set and the escaped controls are
            untouched by the surrogate guard"
    (is (= "!'()~*-._" (rf.routing.url/url-encode "!'()~*-._")))
    (is (= "a%20b%26c" (rf.routing.url/url-encode "a b&c")))))

(deftest url-encode-splat-refuses-unpaired-surrogates-per-chunk
  (testing "the splat encoder composes `url-encode` per chunk, so the
            refusal arrives per segment — it used to emit `a/%3F`"
    (is (thrown? #?(:clj Throwable :cljs :default)
                 (rf.routing.url/url-encode-splat (str "a/" (code-units [0xD800]))))
        "a lone surrogate in a LATER segment still fails the whole call")
    (is (thrown? #?(:clj Throwable :cljs :default)
                 (rf.routing.url/url-encode-splat (str (code-units [0xDFFF]) "/b")))
        "and in the FIRST segment"))
  (testing "the control: a well-formed pair is NOT split apart by the
            per-segment encoding — U+002F is outside the surrogate range,
            so no split point can fall between the halves of a pair"
    (is (= "a/%F0%9F%98%80/b"
           (rf.routing.url/url-encode-splat (str "a/" (code-units [0xD83D 0xDE00]) "/b")))
        "the pair survives the split and encodes as one astral code point")))

;; ---- production route-url: one canonical URL on both hosts ---------------

(deftest route-url-emits-one-canonical-url-across-hosts
  (testing "punctuation in an ordinary path param, a query key, a query
            value AND the fragment all emit the SAME literal URL on JVM
            and CLJS — this is the production prism, not a re-derivation"
    (rf/reg-route :parity/probe {:params [:map [:slug :string]]} "/p/:slug")
    (is (= "/p/~?!=()#~!"
           (rf.routing/route-url {:to     :parity/probe
                               :params {:slug "~"}
                               :query  {"!" "()"}
                               :fragment "~!"}))
        "the finding's own reproduction: JVM used to emit /p/%7E?%21=%28%29#%7E%21")
    (is (= "/p/draft~1"
           (rf.routing/route-url {:to :parity/probe :params {:slug "draft~1"}}))
        "the failure scenario: SSR used to emit /p/draft%7E1")
    (is (= "/p/it's~a(test)!"
           (rf.routing/route-url {:to :parity/probe :params {:slug "it's~a(test)!"}}))
        "the whole divergent set inside one ordinary path param"))
  (testing "the structural controls still escape inside the production
            prism, so this case cannot pass by disabling encoding"
    (rf/reg-route :parity/probe2 {:params [:map [:slug :string]]} "/p/:slug")
    (is (= "/p/a%20b?q=x%26y%3Dz#50%25%20done"
           (rf.routing/route-url {:to     :parity/probe2
                               :params {:slug "a b"}
                               :query  {"q" "x&y=z"}
                               :fragment "50% done"}))
        "space, `&`, `=` and `%` stay escaped across all three positions")))

(deftest route-url-round-trips-through-match-url-on-both-hosts
  (testing "match-url(route-url(address)) recovers the address exactly —
            the encode/decode pair is still an inverse after the JVM
            encoder moved to the encodeURIComponent boundary"
    (rf/reg-route :parity/round {:params [:map [:slug :string]]} "/r/:slug")
    (let [slug  "it's~a(test)!*-._"
          frag  "~sec!(1)"
          built (rf.routing/route-url {:to       :parity/round
                                    :params   {:slug slug}
                                    :query    {"q!" "a(b)~c"}
                                    :fragment frag})]
      (is (= "/r/it's~a(test)!*-._?q!=a(b)~c#~sec!(1)" built)
          "the built URL is the literal canonical form on this host")
      (let [parsed (rf.routing/match-url built)]
        (is (some? parsed)
            "the canonical URL is NOT a malformed-URL route-miss")
        (is (= :parity/round (:route-id parsed))
            "it matches back to the route it was built from")
        (is (= slug (get-in parsed [:params :slug]))
            "the path param recovers byte-exactly, punctuation included")
        (is (= "a(b)~c" (get-in parsed [:query "q!"]))
            "the punctuation-bearing query key AND value both recover")
        (is (= frag (:fragment parsed))
            "the fragment recovers byte-exactly")))))

(deftest route-url-refuses-unpaired-surrogates-in-every-position-on-both-hosts
  (testing "the production prism, not a re-derivation: an unpaired
            surrogate anywhere in the address fails the whole call on
            both hosts. The JVM used to emit a URL here — with %3F
            standing in for the surrogate — while the browser threw, so
            the SSR href, the canonical head link and the cache key
            carried a spelling the client could not produce (rf2-j3tud)"
    (rf/reg-route :parity/surrogate {:params [:map [:slug :string]]} "/p/:slug")
    (let [high (code-units [0xD800])
          low  (code-units [0xDFFF])]
      (is (thrown? #?(:clj Throwable :cljs :default)
                   (rf.routing/route-url {:to :parity/surrogate :params {:slug high}}))
          "path param — the JVM used to emit /p/%3F")
      (is (thrown? #?(:clj Throwable :cljs :default)
                   (rf.routing/route-url {:to     :parity/surrogate
                                       :params {:slug "ok"}
                                       :query  {"q" low}}))
          "query VALUE — the JVM used to emit /p/ok?q=%3F")
      (is (thrown? #?(:clj Throwable :cljs :default)
                   (rf.routing/route-url {:to     :parity/surrogate
                                       :params {:slug "ok"}
                                       :query  {high "v"}}))
          "query KEY")
      (is (thrown? #?(:clj Throwable :cljs :default)
                   (rf.routing/route-url {:to       :parity/surrogate
                                       :params   {:slug "ok"}
                                       :fragment high}))
          "fragment")
      (is (thrown? #?(:clj Throwable :cljs :default)
                   (rf.routing/route-url {:to     :parity/surrogate
                                       :params {:slug (str "draft" high "1")}}))
          "embedded mid-slug, where a substituting encoder is least visible")))
  (testing "the control: the SAME route emits one canonical URL for an
            astral code point, so the assertions above cannot pass by
            breaking the prism. A surrogate PAIR is legitimate route data"
    (rf/reg-route :parity/surrogate2 {:params [:map [:slug :string]]} "/p/:slug")
    (let [emoji (code-units [0xD83D 0xDE00])
          built (rf.routing/route-url {:to :parity/surrogate2 :params {:slug emoji}})]
      (is (= "/p/%F0%9F%98%80" built)
          "the literal canonical URL, identical on JVM and CLJS")
      (let [parsed (rf.routing/match-url built)]
        (is (some? parsed) "it is not a malformed-URL route-miss")
        (is (= emoji (get-in parsed [:params :slug]))
            "and round-trips back to the same string")
        (is (= [0xD83D 0xDE00]
               (let [s (get-in parsed [:params :slug])]
                 [(code-unit-at s 0) (code-unit-at s 1)]))
            "asserted as CODE UNITS, so a U+FFFD substitution anywhere in
             the round trip cannot read as a pass")))
    (let [built (rf.routing/route-url {:to :parity/surrogate2 :params {:slug "?"}})]
      (is (= "/p/%3F" built)
          "and a literal `?` still emits %3F — the alias target stays
           reachable by the legitimate input that owns it"))))
