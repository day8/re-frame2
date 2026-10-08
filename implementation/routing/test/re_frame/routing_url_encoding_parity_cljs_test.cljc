(ns re-frame.routing-url-encoding-parity-cljs-test
  "Component URL encoding is `encodeURIComponent` on BOTH hosts, byte for
  byte: Spec 012 §Bidirectional URL ↔ params promises ONE host-independent
  canonical URL. `route-link`'s `:href`, SSR canonical head links, copied
  URLs, cache keys and snapshots all read those bytes, and an `:href` that
  differs between the server tree and the first client tree is the Spec 011
  hydration-mismatch class.

  CLJS is normative: it IS `encodeURIComponent`. `url-encode`'s JVM arm
  emulates it on top of `java.net.URLEncoder`, which differs three ways: it
  emits `+` for a space; it escapes `! ' ( ) ~`, which `encodeURIComponent`
  leaves literal, so a slug `draft~1` would emit `/articles/draft%7E1` from
  SSR and `/articles/draft~1` from the browser; and it SUBSTITUTES `%3F` for
  an unpaired surrogate, which `encodeURIComponent` refuses with `URIError`.

  Named `*-cljs-test.cljc` so the JVM runner and the `:node-test` build both
  run it. Every expectation is a LITERAL string, never derived from
  `url-encode`, so an encoder that changed on one host cannot also rewrite
  what it is compared against."
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
  "A string built from raw UTF-16 code units — the only honest way to write an
  unpaired surrogate, which has no UTF-8 encoding and so cannot be a source
  literal."
  [codes]
  (apply str (map (fn [c] #?(:clj  (str (char c))
                             :cljs (js/String.fromCharCode c)))
                  codes)))

(deftest url-encode-still-escapes-the-structural-characters-on-both-hosts
  (testing "space is %20 (never the form-urlencoded `+`), every structural
            character escapes, and non-ASCII is uppercase-hex UTF-8"
    (is (= "%20%2F%25%26%3D%3F%23%2B%C3%A9" (rf.routing.url/url-encode " /%&=?#+é")))))

(deftest url-encode-splat-inherits-the-boundary-per-segment
  (testing "separators stay raw while each segment keeps the literal marks
            and the escapes of `url-encode` (Spec 012 §Splat)"
    (is (= "a~b/my%20file!/50%25.txt" (rf.routing.url/url-encode-splat "a~b/my file!/50%.txt")))))

(deftest url-encode-refuses-unpaired-surrogates-on-both-hosts
  (testing "an unpaired surrogate is REFUSED, not substituted with %3F — the
            encoding of a literal `?` it would be aliased onto"
    (doseq [[codes why] [[[0xD800]               "lone high surrogate at the end of the string"]
                         [[0x0061 0xD800 0x0062] "high surrogate followed by ASCII, not its low partner"]
                         [[0xDFFF]               "lone low surrogate"]
                         [[0xDFFF 0xD800]        "a REVERSED pair is two orphans, not a pair"]]]
      (is (thrown? #?(:clj Throwable :cljs :default)
                   (rf.routing.url/url-encode (code-units codes)))
          why)))
  (testing "the control: well-formed pairs at both ends of the astral range,
            and the literal `?` itself, still encode"
    (is (= "%F0%90%80%80%3F%F4%8F%BF%BF"
           (rf.routing.url/url-encode (code-units [0xD800 0xDC00 0x3F 0xDBFF 0xDFFF]))))))

(deftest route-url-emits-one-canonical-url-across-hosts
  (testing "the structural characters stay escaped in a path param, a query
            value and the fragment of the production prism"
    (rf/reg-route :parity/probe {:params [:map [:slug :string]]} "/p/:slug")
    (is (= "/p/a%20b?q=x%26y%3Dz#50%25%20done"
           (rf.routing/route-url {:to       :parity/probe
                                  :params   {:slug "a b"}
                                  :query    {"q" "x&y=z"}
                                  :fragment "50% done"})))))

(deftest route-url-round-trips-through-match-url-on-both-hosts
  (testing "the nine marks stay literal in every position, and match-url
            recovers the address exactly"
    (rf/reg-route :parity/round {:params [:map [:slug :string]]} "/r/:slug")
    (let [built (rf.routing/route-url {:to       :parity/round
                                       :params   {:slug "it's~a(test)!*-._"}
                                       :query    {"q!" "a(b)~c"}
                                       :fragment "~sec!(1)"})]
      (is (= "/r/it's~a(test)!*-._?q!=a(b)~c#~sec!(1)" built))
      (is (= {:route-id           :parity/round
              :params             {:slug "it's~a(test)!*-._"}
              :query              {"q!" "a(b)~c"}
              :fragment           "~sec!(1)"
              :validation-failed? false}
             (rf.routing/match-url built))))))

;; A splat value ENDING in `/` carries that slash as data, while the
;; incoming-URL normaliser strips RAW trailing slashes (`/cart` ≡ `/cart/`).
;; So the emitter spells a terminal slash run `%2F`, or rebuilding the URL
;; drops it — and the all-slash value would emit `/f/`, which cannot match.
;; An embedded separator stays structural.

(deftest splat-trailing-slash-data-survives-route-url-on-both-hosts
  (rf/reg-route :parity/files {} "/f/*rest")
  (doseq [[value emitted] [["a/"  "/f/a%2F"]
                           ["a//" "/f/a%2F%2F"]
                           ["/"   "/f/%2F"]
                           ["a/b" "/f/a/b"]]]
    (let [built (rf.routing/route-url {:to :parity/files :params {:rest value}})]
      (is (= [emitted value] [built (get-in (rf.routing/match-url built) [:params :rest])])
          (str (pr-str value) " emits the literal canonical URL and matches back to itself")))))
