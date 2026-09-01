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

  Reverting the JVM arm to `URLEncoder` plus the `+`→`%20` swap alone
  turns the JVM half of this namespace red on the boundary set, the
  production `route-url` case and the round-trip, while the
  escaped-character controls stay green — proving those controls are not
  what carries the suite."
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
      (is (= ch (url/url-encode ch)) why)))
  (testing "the whole mark set at once, as one component"
    (is (= "!'()~*-._" (url/url-encode "!'()~*-._"))
        "no character in the mark set is escaped on this host")
    (is (= "draft~1" (url/url-encode "draft~1"))
        "the failure scenario from the finding: a `~`-bearing slug")))

(deftest url-encode-still-escapes-the-structural-characters-on-both-hosts
  (testing "the controls: characters that must NOT go literal, so the
            boundary test above cannot pass by disabling encoding"
    (doseq [[ch expected why] escaped-controls]
      (is (= expected (url/url-encode ch)) why)))
  (testing "non-ASCII still percent-encodes as UTF-8 on both hosts"
    (is (= "%C3%A9" (url/url-encode "é"))
        "`é` is two UTF-8 bytes, uppercase hex on both hosts")))

(deftest url-encode-splat-inherits-the-boundary-per-segment
  (testing "splat encoding preserves structural `/` while each segment
            gets the same literal mark set (Spec 012 §Splat)"
    (is (= "a~b/c!d/e(f)" (url/url-encode-splat "a~b/c!d/e(f)"))
        "marks stay literal inside each chunk; separators stay raw")
    (is (= "my%20file/50%25.txt" (url/url-encode-splat "my file/50%.txt"))
        "the controls still escape per chunk")))

;; ---- production route-url: one canonical URL on both hosts ---------------

(deftest route-url-emits-one-canonical-url-across-hosts
  (testing "punctuation in an ordinary path param, a query key, a query
            value AND the fragment all emit the SAME literal URL on JVM
            and CLJS — this is the production prism, not a re-derivation"
    (rf/reg-route :parity/probe {:params [:map [:slug :string]]} "/p/:slug")
    (is (= "/p/~?!=()#~!"
           (routing/route-url {:to     :parity/probe
                               :params {:slug "~"}
                               :query  {"!" "()"}
                               :fragment "~!"}))
        "the finding's own reproduction: JVM used to emit /p/%7E?%21=%28%29#%7E%21")
    (is (= "/p/draft~1"
           (routing/route-url {:to :parity/probe :params {:slug "draft~1"}}))
        "the failure scenario: SSR used to emit /p/draft%7E1")
    (is (= "/p/it's~a(test)!"
           (routing/route-url {:to :parity/probe :params {:slug "it's~a(test)!"}}))
        "the whole divergent set inside one ordinary path param"))
  (testing "the structural controls still escape inside the production
            prism, so this case cannot pass by disabling encoding"
    (rf/reg-route :parity/probe2 {:params [:map [:slug :string]]} "/p/:slug")
    (is (= "/p/a%20b?q=x%26y%3Dz#50%25%20done"
           (routing/route-url {:to     :parity/probe2
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
          built (routing/route-url {:to       :parity/round
                                    :params   {:slug slug}
                                    :query    {"q!" "a(b)~c"}
                                    :fragment frag})]
      (is (= "/r/it's~a(test)!*-._?q!=a(b)~c#~sec!(1)" built)
          "the built URL is the literal canonical form on this host")
      (let [parsed (routing/match-url built)]
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
