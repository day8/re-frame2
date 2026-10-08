(ns re-frame.routing-url-decoding-parity-cljs-test
  "Component URL decoding is `decodeURIComponent` on BOTH hosts, INCLUDING
  its UTF-8 validity check (Spec 012 §`+` is a literal; §Route-miss ¶5
  requires malformed percent-encoding to fail the whole match closed).

  `java.net.URLDecoder` differs from it twice: it turns `+` into a space, and
  it decodes with the REPLACE action, so invalid UTF-8 (`%FF`, an overlong
  `%C0%80`, an escaped surrogate `%ED%A0%80`) comes back as U+FFFD where
  `decodeURIComponent` throws. A hostile URL would then fail closed in the
  browser but MATCH under SSR — a whole-tree Spec 011 hydration mismatch with
  the server as the permissive side.

  The near-miss control is the point: `%EF%BF%BD` is the valid encoding of a
  REAL U+FFFD and must decode, which no check of the decoded OUTPUT can tell
  from a substitution. The JVM arm discriminates at the byte level instead.

  The second half is the literal seam: a LITERAL lone surrogate passes
  through untouched (`decodeURIComponent` copies literals), while a
  PERCENT-ENCODED one fails, because UTF-8 has no encoding for a surrogate.
  Reaching the byte level through `String.getBytes(UTF_8)` would turn the
  literal into `?`. Surrogate cases are built from numeric code units and
  compared as code-unit vectors, since a lone surrogate, a `?` and a U+FFFD
  all print alike.

  Named `*-cljs-test.cljc` so the JVM runner and the `:node-test` build both
  run it. Every expectation is a literal value, never derived from
  `url-decode` itself."
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

(defn- code-units
  "`s` as a vector of its UTF-16 code units, indexed on purpose: iterating a
  JS string walks code POINTS and would collapse a pair. nil passes through."
  [s]
  (when (some? s)
    (mapv (fn [i]
            #?(:clj  (int (.charAt ^String s i))
               :cljs (.charCodeAt s i)))
          (range (count s)))))

(defn- from-code-units [& ns] (apply str (map char ns)))

(deftest safe-url-decode-fails-closed-on-invalid-utf8-on-both-hosts
  (testing "escapes spelling invalid UTF-8, and structurally malformed escapes,
            yield the nil sentinel — a REPLACE-action decoder would return a
            U+FFFD-bearing string for the first five"
    (doseq [in ["%FF"        ; invalid lead byte
                "%C0%80"     ; overlong NUL
                "%ED%A0%80"  ; an escaped lone surrogate
                "%C3"        ; truncated at the end of the string
                "%C3é"       ; truncated against a literal, which cannot complete it
                "%"          ; no hex digit
                "%a"         ; one hex digit
                "%zz"]]      ; non-hex characters
      (is (nil? (rf.routing.url/safe-url-decode in)) in))))

(deftest safe-url-decode-still-decodes-every-valid-input-on-both-hosts
  (testing "valid input is never rejected, so the table above cannot pass by
            nil-ing everything: the near-miss U+FFFD, escapes beside literals
            in both orders, and `+` kept literal"
    (is (= ["�" "café" "Aé" "a+b" "+" " "]
           (mapv rf.routing.url/safe-url-decode
                 ["%EF%BF%BD" "caf%C3%A9" "%41é" "a+b" "%2B" "%20"])))))

(deftest url-decode-preserves-literal-utf16-code-units-on-both-hosts
  (testing "literal lone surrogates are copied to the output, never encoded —
            a byte round trip would return [97 63 98 63]"
    (is (= [97 55296 98 57343]
           (code-units (rf.routing.url/safe-url-decode (from-code-units 97 0xD800 98 0xDFFF)))))))

(deftest malformed-url?-agrees-across-hosts-in-every-url-position
  (testing "an undecodable piece flips the predicate in every position the scan
            reads, including a later path segment and a later query pair"
    (doseq [u ["/p/%FF" "/a/b/%E0%80%80" "/p/x?%FF=1" "/p/x?q=%FF" "/search?good=1&bad=%" "/p/x#%FF"]]
      (is (true? (rf.routing/malformed-url? u)) u)))
  (testing "the control: valid escapes in the same positions are not malformed"
    (is (false? (rf.routing/malformed-url? "/p/%C3%A9?%C3%A9=%EF%BF%BD&page=2#%EF%BF%BD")))))

(deftest hostile-url-is-a-route-miss-on-both-hosts
  (testing "through production match-url: invalid UTF-8 in a capture fails the
            whole match closed, while a legitimately-encoded U+FFFD matches"
    (rf/reg-route :decode-parity/probe {:params [:map [:slug :string]]} "/p/:slug")
    (is (nil? (rf.routing/match-url "/p/%FF")))
    (is (= "�" (get-in (rf.routing/match-url "/p/%EF%BF%BD") [:params :slug])))))
