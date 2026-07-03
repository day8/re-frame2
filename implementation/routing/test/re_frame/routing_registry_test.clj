(ns re-frame.routing-registry-test
  "Registry / match / URL-construction tests for re-frame.routing
  (reg-route, match-url, route-url, the match/registry primitives, query
  coercion + the keyword-interning cap, optional groups, splats, pattern
  parsing, and metadata validation). Split from routing_test.clj per
  rf2-u8qe7y finding 3."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.identity :as identity]
            [re-frame.routing :as routing]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]
            [re-frame.routing.match :as routing.match]
            [re-frame.routing.registry :as registry]))

(use-fixtures :each rts/reset-runtime)

;; ---- rf2-hra3: route-url missing-required-param raises clear error -------
;;
;; Per test-coverage-review-2026-05-12 P3-14. Hardening: ensure
;; `route-url` doesn't silently emit a malformed URL when a required
;; path param is absent.

(deftest route-url-missing-required-path-param-throws
  (testing "route-url with a missing required :id path param raises
            :rf.error/missing-route-param"
    (rf/reg-route :route/article {} "/articles/:id")
    ;; No :id supplied — must throw the structured error.
    (let [ex (try
               (routing/route-url :route/article {})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "route-url with absent required param raises")
      ;; rf2-vvixub — anchor on the canonical :rf.error/id discriminator
      ;; (the message is now a human sentence + trailing token).
      (is (= :rf.error/missing-route-param (:rf.error/id (ex-data ex)))
          "the structured error id is :rf.error/missing-route-param")
      (let [data (ex-data ex)]
        (is (= :id (:param data))
            "ex-data names the absent param")
        (is (= :route/article (:route-id data))
            "ex-data names the route-id"))))

  (testing "supplying nil for the param has the same shape as omitting it"
    (rf/reg-route :route/article2 {} "/articles/:id")
    (let [ex (try
               (routing/route-url :route/article2 {:id nil})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex)
          "nil :id behaves like an absent :id — same structured error")
      ;; rf2-vvixub — anchor on the canonical :rf.error/id discriminator.
      (is (= :rf.error/missing-route-param (:rf.error/id (ex-data ex))))))

  (testing "providing the param works — sanity check the happy path"
    (rf/reg-route :route/article3 {} "/articles/:id")
    (is (= "/articles/intro"
           (routing/route-url :route/article3 {:id "intro"}))
        "supplying the required param renders the URL"))

  (testing "splat params raise the same structured error when absent"
    (rf/reg-route :route/files {} "/files/*path")
    (let [ex (try
               (routing/route-url :route/files {})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "absent splat raises")
      ;; rf2-vvixub — anchor on the canonical :rf.error/id discriminator.
      (is (= :rf.error/missing-route-param (:rf.error/id (ex-data ex)))
          "splat absence uses the same structured error id"))))

;; ---- rf2-6iam6 + rf2-ede1h.2: falsy path params (false / 0) round-trip;
;; the empty string is rejected
;;
;; Per Spec 012 §Bidirectional URL ↔ params an absent or nil path param
;; raises :rf.error/missing-route-param; a present-but-falsy NON-EMPTY
;; value (`false` / `0`) is a legitimate segment that round-trips through
;; url-encode (the pre-rf2-6iam6 `(or v throw)` form mis-classified them
;; as missing). The EMPTY STRING is the exception (rf2-ede1h.2): it would
;; emit a zero-length segment (`/articles/`) which match-url's
;; trailing-slash normalisation erases before matching, so it cannot
;; round-trip — the path side rejects it on emission, like nil/absent.

(deftest route-url-accepts-falsy-path-params
  (testing "route-url accepts false and 0 path params —
            present-but-falsy (non-empty) is NOT the same as absent"
    (rf/reg-route :route/page {} "/page/:flag")
    (is (= "/page/false"
           (routing/route-url :route/page {:flag false}))
        "false renders as the literal segment \"false\"")
    (is (= "/page/0"
           (routing/route-url :route/page {:flag 0}))
        "0 renders as the literal segment \"0\""))

  (testing "false and 0 path params round-trip through match-url"
    (rf/reg-route :route/page2 {} "/page/:flag")
    (is (= {:flag "false"}
           (:params (routing/match-url (routing/route-url :route/page2 {:flag false}))))
        "false → \"/page/false\" → {:flag \"false\"}")
    (is (= {:flag "0"}
           (:params (routing/match-url (routing/route-url :route/page2 {:flag 0}))))
        "0 → \"/page/0\" → {:flag \"0\"}"))

  (testing "rf2-ede1h.2: an empty-string required path param is REJECTED on
            emission — a zero-length segment cannot round-trip"
    (rf/reg-route :route/slug {} "/articles/:slug")
    (let [ex (try
               (routing/route-url :route/slug {:slug ""})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex)
          "\"\" path param throws (it would emit \"/articles/\", which match-url normalises to \"/articles\" and fails to match)")
      ;; rf2-vvixub — anchor on the canonical :rf.error/id discriminator.
      (is (= :rf.error/missing-route-param (:rf.error/id (ex-data ex)))
          "the empty-string rejection reuses the missing-required-param error id")
      (is (= "" (:value (ex-data ex)))
          "the ex-data carries the offending empty-string value"))))

(deftest route-url-no-such-route-throws
  (testing "route-url against an unregistered route id raises
            :rf.error/no-such-route"
    (let [ex (try
               (routing/route-url :route/no-such-route {})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      ;; rf2-vvixub — anchor on the canonical :rf.error/id discriminator.
      (is (= :rf.error/no-such-route (:rf.error/id (ex-data ex)))
          ":rf.error/no-such-route is the structured error for an unregistered id"))))

;; ---- rf2-94o54l.1/.3: route-url fails closed on host-stringified values --
;;
;; EP-0012 §Canonical EDN identity (docs/EP/EP-0012 §893-896; Conventions
;; §584-592): "If a route param value cannot be represented as canonical EDN
;; after schema coercion, route matching or URL printing MUST fail closed at
;; the relevant boundary. It MUST NOT use host `str`, JS object stringification,
;; or object identity to invent a cache or route identity."
;;
;; Before this fix the query KEY side was CEDN-guarded (the canonical-order
;; sort runs each key through `identity/canonical-bytes`), but path-param
;; values and query VALUES went straight to `url/url-encode`'s host `(str v)`.
;; A function / atom / arbitrary host object / non-portable number would have
;; been host-stringified into a fabricated URL identity; a host `Date` /
;; instant would have been host-stringified into a HOST-DIVERGENT, un-round-
;; trippable segment. `route-url` now fails closed with
;; `:rf.error/route-url-non-edn-value` BEFORE any URL string is returned.

(defn- route-url-throws-non-edn?
  "Call `route-url` and return the thrown ExceptionInfo (or nil). A helper
  so each adversarial value reads as one assertion."
  [route-id path-params query-params]
  (try
    (routing/route-url route-id path-params query-params)
    nil
    (catch clojure.lang.ExceptionInfo e e)))

(deftest route-url-path-param-host-value-fails-closed-rf2-94o54l
  (testing "a HOST value (fn / atom / arbitrary host object / non-portable
            number / instant) in a REQUIRED path param fails closed with
            :rf.error/route-url-non-edn-value BEFORE any URL string is built"
    (rf/reg-route :route/item {} "/items/:id")
    (doseq [[label v] [[:function    (fn [_])]
                       [:atom        (atom 1)]
                       [:host-object (Object.)]
                       [:float       1.5]
                       [:ratio       2/3]
                       [:instant     (java.time.Instant/now)]
                       [:host-date   (java.util.Date.)]]]
      (let [ex (route-url-throws-non-edn? :route/item {:id v} {})]
        (is (some? ex)
            (str "a " (name label) " path-param value must fail closed, never "
                 "host-stringify into a URL"))
        ;; rf2-du585y: assert the STRUCTURED :rf.error/id (Spec 009 §the
        ;; stable discriminator) as the primary check; the message is secondary.
        (is (= :rf.error/route-url-non-edn-value (:rf.error/id (ex-data ex)))
            (str "the structured :rf.error/id for a " (name label) " path value"))
        ;; rf2-vvixub — message is a human sentence + the trailing
        ;; [:rf.error/<id>] token; assert the token, not exact equality.
        (is (re-find #"\[:rf\.error/route-url-non-edn-value\]" (ex-message ex))
            (str "the message token (secondary) for a " (name label) " path value"))
        (let [data (ex-data ex)]
          (is (= :route/item (:route-id data)) "ex-data names the route-id")
          (is (= :params (:slot data)) "ex-data names the offending slot")
          (is (= :id (:param data)) "ex-data names the offending param"))))))

(deftest route-url-query-value-host-value-fails-closed-rf2-94o54l
  (testing "a HOST value in a (non-nil) QUERY value fails closed the same way
            the path side and the query-KEY side do"
    (rf/reg-route :route/search {} "/search")
    (doseq [[label v] [[:function    (fn [_])]
                       [:atom        (atom 1)]
                       [:host-object (Object.)]
                       [:float       1.5]
                       [:instant     (java.time.Instant/now)]
                       [:host-date   (java.util.Date.)]]]
      (let [ex (route-url-throws-non-edn? :route/search {} {:q v})]
        (is (some? ex)
            (str "a " (name label) " query value must fail closed"))
        (is (= :rf.error/route-url-non-edn-value (:rf.error/id (ex-data ex)))
            (str "the structured :rf.error/id for a " (name label) " query value"))
        ;; rf2-vvixub — message is a human sentence + the trailing
        ;; [:rf.error/<id>] token; assert the token, not exact equality.
        (is (re-find #"\[:rf\.error/route-url-non-edn-value\]" (ex-message ex))
            (str "the message token (secondary) for a " (name label) " query value"))
        (let [data (ex-data ex)]
          (is (= :route/search (:route-id data)) "ex-data names the route-id")
          (is (= :query (:slot data)) "ex-data names the :query slot")
          (is (= :q (:param data)) "ex-data names the offending query key"))))))

(deftest route-url-optional-group-host-value-fails-closed-rf2-94o54l
  (testing "a host value in an OPTIONAL-GROUP inner path param also fails
            closed (the group is entered because the param is present)"
    (rf/reg-route :route/doc {} "/docs{/:section}?")
    (let [ex (route-url-throws-non-edn? :route/doc {:section (fn [_])} {})]
      (is (some? ex) "a fn in an entered optional group fails closed")
      (is (= :rf.error/route-url-non-edn-value (:rf.error/id (ex-data ex)))
          "structured :rf.error/id (primary)")
      ;; rf2-vvixub — assert the [:rf.error/<id>] token, not exact equality.
      (is (re-find #"\[:rf\.error/route-url-non-edn-value\]" (ex-message ex)) "message token (secondary)")
      (is (= :section (:param (ex-data ex)))))))

(deftest route-url-admitted-scalars-still-emit-rf2-94o54l
  (testing "the guard ADMITS portable URL scalars — strings, keywords,
            booleans, portable integers, UUIDs — so the happy path is
            unaffected (the guard is a host-value gate, not a string-only gate)"
    (rf/reg-route :route/scalar {} "/s/:v")
    (is (= "/s/hello"  (routing/route-url :route/scalar {:v "hello"})))
    (is (= "/s/%3Akw"  (routing/route-url :route/scalar {:v :kw}))
        "a keyword is admitted and host-stably stringifies (the leading `:`
         percent-encodes to %3A — admitted, not rejected)")
    (is (= "/s/false"  (routing/route-url :route/scalar {:v false}))
        "a present-but-falsy boolean round-trips (existing contract)")
    (is (= "/s/0"      (routing/route-url :route/scalar {:v 0}))
        "a present-but-falsy integer round-trips (existing contract)")
    (let [uuid (java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440000")]
      (is (= "/s/550e8400-e29b-41d4-a716-446655440000"
             (routing/route-url :route/scalar {:v uuid}))
          "a UUID host-stringifies to its canonical, host-stable, round-trippable form"))
    (is (= "/s/a?x=1" (routing/route-url :route/scalar {:v "a"} {:x 1}))
        "a portable-integer QUERY value is admitted")))

(deftest route-url-host-value-throws-before-url-built-rf2-94o54l
  (testing "the failure happens BEFORE any URL string escapes — the structured
            error is raised, not a half-built or host-stringified URL string"
    (rf/reg-route :route/order {:query [:map [:note :string]]} "/orders/:id")
    ;; both a bad path value AND a bad query value present: the path guard
    ;; (which runs during the pattern walk, before path-out is assembled)
    ;; fires first, so no URL fragment is ever produced.
    (let [ex (route-url-throws-non-edn? :route/order
                                        {:id (atom :x)}
                                        {:note "ok"})]
      ;; rf2-vvixub — anchor on the canonical :rf.error/id discriminator
      ;; (a structured error, never a string return value).
      (is (= :rf.error/route-url-non-edn-value (:rf.error/id (ex-data ex)))
          "a structured error, never a string return value"))))

;; ---- rf2-u1na: match-url malformed-input edge cases ----------------------
;;
;; Per test-coverage-review-2026-05-12 P3-13. Hardening for the URL parser.

(deftest match-url-empty-string
  (testing "match-url \"\" matches the root '/' route (the compiled regex
            is `^/?$`, so the leading slash is optional)"
    (rf/reg-route :route/home {} "/")
    ;; Pin the actual behaviour: the compiled regex treats the leading
    ;; slash as optional, so both "" and "/" match the root.
    (let [m (routing/match-url "")]
      (is (some? m)
          "empty string matches the root '/' route (leading slash optional)")
      (is (= :route/home (:route-id m))))))

(deftest match-url-missing-leading-slash
  (testing "URLs without a leading slash STILL match the corresponding
            route (the compiled regex is `^/?...`, leading slash optional).
            Documenting the actual lenient behaviour."
    (rf/reg-route :route/home {} "/home")
    (is (some? (routing/match-url "/home"))
        "the canonical /home matches")
    (let [m (routing/match-url "home")]
      (is (some? m)
          "no-leading-slash also matches — the compiled regex permits it")
      (is (= :route/home (:route-id m))))))

(deftest match-url-repeated-query-key-last-wins
  (testing "repeated query keys — last value wins (the parser's array-map
            reduce assoc's left-to-right, so a later key replaces an
            earlier one). rf2-5ifai: the route declares no :query
            vocabulary, so the key stays a string."
    (rf/reg-route :route/search {} "/search")
    (let [m (routing/match-url "/search?x=1&x=2")]
      (is (some? m) "the route matches")
      (is (= "2" (get-in m [:query "x"]))
          "repeated key — last value wins (left-to-right reduce)"))))

(deftest match-url-unterminated-query
  (testing "a query pair without an '=' value parses as an empty string.
            rf/2-5ifai: the route declares no :query vocabulary, so the
            key stays a string."
    (rf/reg-route :route/search {} "/search")
    (let [m (routing/match-url "/search?foo=")]
      (is (some? m) "the route still matches")
      (is (= "" (get-in m [:query "foo"]))
          "an unterminated `?foo=` parses to (get :query \"foo\") \"\""))))

;; ---- rf2-t3cfil: match-url :query is in CEDN-1 canonical KEY order --------
;;
;; EP-0012 tier-2 routing consumer sweep. The inbound URL's query string
;; carries keys in whatever left-to-right order the author of THAT URL chose,
;; but the route slice's `:query` is route DATA — an identity fact the
;; `:rf.route/query` sub, no-op detection, and SSR-hydration parity key off.
;; Two inbound URLs spelling the same query in different key orders MUST yield
;; the SAME `:query` identity (and the same key ORDER), so match-url reorders
;; the surviving entries into CEDN-1 canonical key order. This is the inbound
;; mirror of route-url's already-canonical query emission (rf2-wgutc2): per
;; Conventions §Routes are prisms, both prism legs share ONE canonical order.

(defn- canonical-key-order
  "The CEDN-1 canonical order of `ks` (the order match-url's :query must
  use), computed via the SAME shared identity rule the implementation sorts
  by — re-frame.identity/canonical-bytes."
  [ks]
  (vec (sort-by identity/canonical-bytes ks)))

(deftest match-url-query-is-canonical-key-order
  (testing "match-url :query keys are emitted in CEDN-1 canonical order,
            independent of the inbound URL's key order. Declared :query
            vocabulary keys are promoted to keywords and ordered canonically."
    (rf/reg-route :route/search {:query [:map
                                         [:b {:optional true} :string]
                                         [:a {:optional true} :string]
                                         [:c {:optional true} :string]]} "/search")
    (let [m1 (routing/match-url "/search?b=2&a=1&c=3")
          m2 (routing/match-url "/search?c=3&b=2&a=1")
          expected-order (canonical-key-order [:a :b :c])]
      (is (= (:query m1) (:query m2))
          "the same query spelled in two inbound key orders yields = :query")
      (is (= expected-order (vec (keys (:query m1))))
          "m1 :query keys are in CEDN-1 canonical order, not inbound URL order")
      (is (= expected-order (vec (keys (:query m2))))
          "m2 :query keys are in CEDN-1 canonical order regardless of spelling")
      (is (= {:a "1" :b "2" :c "3"} (:query m1))
          "membership + values are unchanged — only key ORDER is canonicalised"))))

(deftest match-url-undeclared-query-keys-canonical-order
  (testing "undeclared query keys (string keys, no :query vocabulary) are
            ALSO emitted in CEDN-1 canonical order — the order is total over
            the mixed keyword/string keys a :query may carry"
    (rf/reg-route :route/raw {} "/raw")
    (let [m1 (routing/match-url "/raw?z=1&a=2&m=3")
          m2 (routing/match-url "/raw?m=3&z=1&a=2")
          expected-order (canonical-key-order ["a" "m" "z"])]
      (is (= (:query m1) (:query m2))
          "two inbound key orders yield = :query for undeclared string keys")
      (is (= expected-order (vec (keys (:query m1))))
          "string keys ordered by CEDN-1 bytes, not inbound URL order")
      (is (= {"a" "2" "m" "3" "z" "1"} (:query m1))
          "values preserved; keys stay strings (no vocabulary declared)"))))

(deftest match-url-query-defaults-participate-in-canonical-order
  (testing ":query-defaults-populated keys are interleaved into the SAME
            canonical key order as parsed keys, not appended after them"
    (rf/reg-route :route/listing {:query          [:map
                                                   [:sort {:optional true} :string]
                                                   [:page {:optional true} :int]]
                                  :query-defaults {:page 1}} "/listing")
    ;; `:sort` arrives from the URL; `:page` is filled from defaults. Both
    ;; must sit in canonical key order in the final :query.
    (let [m (routing/match-url "/listing?sort=name")
          expected-order (canonical-key-order [:page :sort])]
      (is (= expected-order (vec (keys (:query m))))
          "default-filled :page and parsed :sort share one canonical order")
      (is (= {:page 1 :sort "name"} (:query m))
          "default applied; values intact"))))

(deftest match-url-trailing-slash-normalizes
  (testing "trailing-slash equivalence is implicit — /foo and /foo/
            resolve to the same route per Spec 012"
    (rf/reg-route :route/foo {} "/foo")
    (is (some? (routing/match-url "/foo"))
        "the canonical /foo matches")
    (let [canonical (routing/match-url "/foo")
          trailing  (routing/match-url "/foo/")]
      (is (= (:route-id canonical) (:route-id trailing))
          "/foo/ resolves to the same route-id as /foo")
      (is (= (:params canonical) (:params trailing))
          "/foo/ carries the same path params as /foo"))))

(deftest match-url-url-encoded-path-round-trip
  (testing "URL-encoded characters in the path round-trip with route-url"
    (rf/reg-route :route/articles {} "/articles/:slug")
    ;; A slug with characters that get percent-encoded.
    (let [slug   "hello world"
          built  (routing/route-url :route/articles {:slug slug})
          parsed (routing/match-url built)]
      (is (some? built)
          "route-url built a URL for the encoded slug")
      ;; The built URL must NOT contain a raw space.
      (is (not (clojure.string/includes? built " "))
          "the built URL contains no raw space — encoding was applied")
      (is (some? parsed) "match-url parses the built URL")
      ;; The decoded slug should round-trip back.
      (is (= {:slug slug}
             (:params parsed))
          "the slug round-trips through route-url → match-url"))))

;; ---- rf2-wbvme + rf2-4ic0f: malformed percent-encoding fails closed -------
;;
;; Per Spec 012 §Routing failure semantics. `URLDecoder/decode` (JVM) and
;; `decodeURIComponent` (CLJS) throw on malformed `%` sequences. Hostile
;; URLs, partner integrations with broken escaping, and back-button to a
;; malformed link must produce a route-miss (404 path), never a request-
;; handler crash.
;;
;; Contract (rf2-4ic0f): uniform fail-closed across path / query /
;; fragment — `match-url` returns nil regardless of which portion is
;; malformed. The runtime refuses any URL whose %-encoding cannot be
;; uniformly decoded.
;;
;; Reproducer from the security audit: `(routing/match-url "/search?x=%")`
;; resolves to nil (route-miss → `:rf.route/not-found` with
;; `:reason :malformed-url` at `:rf.route/transitioned`).

(deftest match-url-malformed-percent-in-path-is-route-miss
  (testing "a bare `%` in the path returns nil (route-miss), does not throw"
    (rf/reg-route :route/articles {} "/articles/:slug")
    (is (nil? (routing/match-url "/articles/%"))
        "/articles/% is a route-miss, not an exception")
    (is (nil? (routing/match-url "/articles/x%a"))
        "/articles/x%a (incomplete pair) is a route-miss")
    (is (nil? (routing/match-url "/articles/x%XX"))
        "/articles/x%XX (non-hex pair) is a route-miss"))
  (testing "bare-`%` URL with no path-pattern match also returns nil"
    ;; No route registered; even a malformed URL must not throw.
    (is (nil? (routing/match-url "/%"))
        "/% with no matching route is a route-miss, not an exception")))

(deftest match-url-malformed-percent-in-query-fails-closed
  (testing "rf2-4ic0f: malformed %-encoding in a query VALUE fails closed —
            the WHOLE URL is a route-miss, not just the bad pair"
    (rf/reg-route :route/search {} "/search")
    (is (nil? (routing/match-url "/search?x=%"))
        "single-pair malformed query → route-miss, no partial slice")
    (is (nil? (routing/match-url "/search?good=1&bad=%&also=2"))
        "good neighbours do NOT keep the URL routable when one pair is malformed"))
  (testing "rf2-4ic0f: malformed %-encoding in a query KEY fails closed"
    (rf/reg-route :route/search2 {} "/search2")
    (is (nil? (routing/match-url "/search2?%=v"))
        "malformed key → route-miss, not a dropped pair")
    (is (nil? (routing/match-url "/search2?ok=1&%=bad&also=2"))
        "bad-key with good neighbours still fails the whole URL")))

(deftest match-url-malformed-percent-in-fragment-fails-closed
  (testing "rf2-4ic0f: malformed %-encoding in the `#fragment` portion
            fails closed — `match-url` returns nil"
    (rf/reg-route :route/page {} "/page")
    (is (nil? (routing/match-url "/page#%"))
        "bare `%` in fragment → route-miss")
    (is (nil? (routing/match-url "/page#good%a"))
        "incomplete %-pair in fragment → route-miss"))
  (testing "well-formed and empty fragments are unaffected"
    (rf/reg-route :route/page2 {} "/page2")
    (let [m (routing/match-url "/page2#section-1")]
      (is (some? m) "well-formed fragment matches")
      (is (= "section-1" (:fragment m))
          "well-formed fragment surfaces decoded into the slice"))
    (let [m (routing/match-url "/page2#hello%20world")]
      (is (some? m) "well-formed %-encoded fragment matches")
      (is (= "hello world" (:fragment m))
          "well-formed %-encoded fragment is decoded into the slice"))
    (let [m (routing/match-url "/page2#")]
      (is (some? m) "bare-trailing-`#` URL matches")
      (is (= "" (:fragment m)) "bare `#` decodes to empty string"))))

(deftest malformed-url?-predicate-discriminates-decode-failures
  (testing "rf2-4ic0f: `malformed-url?` returns true for any URL whose
            %-encoding cannot be uniformly decoded; false otherwise.
            `:rf.route/transitioned` uses this to write `:reason :malformed-url`
            on the `:rf.route/not-found` slice."
    (is (false? (routing/malformed-url? "/")))
    (is (false? (routing/malformed-url? "/articles/intro")))
    (is (false? (routing/malformed-url? "/search?q=clojure&page=2")))
    (is (false? (routing/malformed-url? "/page#section-1")))
    (is (false? (routing/malformed-url? "/page#hello%20world"))
        "well-formed %-encoded fragment is not malformed")
    (is (false? (routing/malformed-url? "/page2#"))
        "bare-trailing-`#` (empty fragment) is not malformed")
    ;; Path
    (is (true? (routing/malformed-url? "/articles/%")))
    (is (true? (routing/malformed-url? "/articles/x%a")))
    ;; Query
    (is (true? (routing/malformed-url? "/search?x=%")))
    (is (true? (routing/malformed-url? "/search?good=1&bad=%")))
    (is (true? (routing/malformed-url? "/search?%=v")))
    ;; Fragment
    (is (true? (routing/malformed-url? "/page#%")))
    (is (true? (routing/malformed-url? "/page#good%a")))))

;; ---- rf2-070jt: match-url :fragment + route-url 4-arity round-trip --------
;;
;; Per Spec 012 §Bidirectional URL ↔ params and §Fragments §Programmatic
;; navigation with fragments. match-url surfaces the URL's `#fragment`
;; portion on its result map; route-url's 4-arity rebuilds the URL with
;; the fragment appended. The two are inverses: a URL parsed with
;; match-url and rebuilt with route-url's 4-arity recovers the original
;; (modulo route-id resolution).

(deftest match-url-returns-fragment-from-url
  (testing "match-url surfaces the URL's `#fragment` as :fragment"
    (rf/reg-route :route/docs {} "/docs/:page")
    (let [m (routing/match-url "/docs/routing#scroll-restoration")]
      (is (some? m) "the route matches")
      (is (= :route/docs (:route-id m)))
      (is (= {:page "routing"} (:params m))
          "path params parsed as usual; fragment did not pollute :page")
      (is (= "scroll-restoration" (:fragment m))
          ":fragment carries the URL's #fragment portion"))))

(deftest match-url-fragment-absent-is-nil
  (testing "URLs without a #fragment yield :fragment nil"
    (rf/reg-route :route/home {} "/home")
    (let [m (routing/match-url "/home")]
      (is (some? m))
      (is (nil? (:fragment m))
          "absent #fragment → :fragment nil"))))

(deftest match-url-fragment-with-query
  (testing ":fragment is independent of the query string. rf2-5ifai: no
            :query vocabulary declared, so the key stays a string."
    (rf/reg-route :route/search {} "/search")
    (let [m (routing/match-url "/search?q=clojure#results")]
      (is (some? m))
      (is (= {"q" "clojure"} (:query m))
          "query parsed without the fragment polluting it")
      (is (= "results" (:fragment m))
          "fragment captured after the query string"))))

(deftest match-url-empty-fragment
  (testing "a bare trailing '#' yields :fragment \"\""
    (rf/reg-route :route/page {} "/page")
    (let [m (routing/match-url "/page#")]
      (is (some? m))
      (is (= "" (:fragment m))
          "URL ending with bare '#' yields empty-string fragment"))))

(deftest match-url-no-rank-in-result
  (testing "match-url result does NOT carry the internal :rank key"
    (rf/reg-route :route/home {} "/")
    (let [m (routing/match-url "/")]
      (is (some? m))
      (is (not (contains? m :rank))
          ":rank is internal routing-table state; not part of the
          documented match-url result shape"))))

(deftest route-url-4-arity-appends-fragment
  (testing "the 4-arity form appends `#fragment` when non-nil and non-empty"
    (rf/reg-route :route/docs {} "/docs/:page")
    (is (= "/docs/routing#scroll-restoration"
           (routing/route-url :route/docs {:page "routing"} {} "scroll-restoration"))
        "fragment is appended after the path")
    (is (= "/docs/routing?lang=en#scroll-restoration"
           (routing/route-url :route/docs {:page "routing"} {:lang "en"} "scroll-restoration"))
        "fragment is appended after the query string"))

  (testing "nil and empty-string fragments are not appended"
    (rf/reg-route :route/docs2 {} "/docs/:page")
    (is (= "/docs/routing"
           (routing/route-url :route/docs2 {:page "routing"} {} nil))
        "nil fragment → no `#` suffix")
    (is (= "/docs/routing"
           (routing/route-url :route/docs2 {:page "routing"} {} ""))
        "empty-string fragment → no `#` suffix"))

  (testing "the 3-arity form delegates to the 4-arity with fragment nil"
    (rf/reg-route :route/docs3 {} "/docs/:page")
    (is (= "/docs/routing"
           (routing/route-url :route/docs3 {:page "routing"} {}))
        "3-arity produces the same URL as 4-arity with nil fragment"))

  (testing "the 2-arity form delegates the same way"
    (rf/reg-route :route/docs4 {} "/docs/:page")
    (is (= "/docs/routing"
           (routing/route-url :route/docs4 {:page "routing"}))
        "2-arity → no query, no fragment")))

;; ---- route-url query-string emission -------------------------------------
;;
;; Per Spec 012 §Bidirectional URL ↔ params. The qs builder
;; (registry.cljc) joins `(name k)=url-encode(v)` pairs with `&`.
;; Coverage elsewhere only hits the single-pair `{:lang "en"}` case
;; (route-url-4-arity-appends-fragment:918), which can't observe pair
;; ORDERING or query-VALUE percent-encoding. These are the two behaviours
;; the multi-pair `&`-join + per-value `url-encode` exist for.
;;
;; rf2-wgutc2 (EP-0012 correctness review item 2): query keys are emitted
;; in DETERMINISTIC CANONICAL ORDER (by CEDN-1 key bytes), NOT the caller's
;; insertion order — so the same query map spelled in different key orders
;; builds the BYTE-IDENTICAL URL (Conventions §The `:rf/path` algebra: "query
;; keys are emitted in deterministic canonical order").
(deftest route-url-query-string-emission
  (testing "multi-pair query: pairs joined with `&` in CANONICAL key order
            (construction-order independent)"
    (rf/reg-route :route/list {} "/list")
    ;; both spellings of {:a … :b …} build the same URL — canonical order,
    ;; not insertion order.
    (is (= "/list?a=1&b=2"
           (routing/route-url :route/list {} (array-map :a "1" :b "2")))
        "two query pairs join with `&` in canonical key order")
    (is (= "/list?a=1&b=2"
           (routing/route-url :route/list {} (array-map :b "2" :a "1")))
        "the SAME URL regardless of caller insertion order (a before b)")
    (is (= "/list?x=1&y=2&z=3"
           (routing/route-url :route/list {} (array-map :x "1" :y "2" :z "3")))
        "three query pairs join with `&` in canonical key order")
    (is (= "/list?x=1&y=2&z=3"
           (routing/route-url :route/list {} (array-map :z "3" :x "1" :y "2")))
        "three pairs: canonical order is construction-order independent")
    (is (= (routing/route-url :route/list {} (array-map :z "3" :y "2" :x "1"))
           (routing/route-url :route/list {} (array-map :x "1" :y "2" :z "3")))
        "any two permutations of one query map build the byte-identical URL"))

  (testing "query VALUES are percent-encoded (encodeURIComponent semantics)"
    (rf/reg-route :route/search {} "/search")
    (is (= "/search?q=x%20y"
           (routing/route-url :route/search {} {:q "x y"}))
        "a space in a query value encodes to %20 (not '+')")
    (is (= "/search?a=1&b=x%20y"
           (routing/route-url :route/search {} (array-map :a "1" :b "x y")))
        "multi-pair ordering AND value %-encoding together")
    (is (= "/search?filter=a%26b%3Dc"
           (routing/route-url :route/search {} {:filter "a&b=c"}))
        "`&` and `=` in a value are encoded so they cannot inject extra pairs"))

  (testing "query KEYS are percent-encoded too"
    (rf/reg-route :route/k {} "/k")
    (is (= "/k?a%20b=v"
           (routing/route-url :route/k {} {(keyword "a b") "v"}))
        "a space in a query key encodes to %20")))

(deftest route-url-drops-nil-query-values-keeps-falsy
  (testing "rf2-ee38b.8: a nil-valued query key is ELIDED from the URL
            (not emitted as a bare `?key=`), while present-but-falsy
            values (false / 0 / \"\") round-trip"
    (rf/reg-route :route/list {} "/list")
    (is (= "/list"
           (routing/route-url :route/list {} {:page nil}))
        "a sole nil-valued query key is dropped → no query string at all")
    (is (= "/list?a=1"
           (routing/route-url :route/list {} (array-map :a "1" :b nil)))
        "nil-valued keys are dropped; the rest of the query survives")
    (is (= "/list?flag=false"
           (routing/route-url :route/list {} {:flag false}))
        "present-but-falsy `false` is a legitimate value and round-trips")
    (is (= "/list?n=0"
           (routing/route-url :route/list {} {:n 0}))
        "`0` round-trips (falsy, not absent)")
    (is (= "/list?s="
           (routing/route-url :route/list {} {:s ""}))
        "empty-string is a present value → `?s=` (distinct from nil/absent)")))

(deftest match-url-route-url-round-trip-with-fragment
  (testing "URL → match-url → route-url 4-arity → URL recovers the original
            (the full bidirectional contract including #fragment). rf2-5ifai:
            unknown query keys stay as strings; route-url accepts both
            keyword + string keys via `(name k)` so the round-trip holds."
    (rf/reg-route :route/docs {} "/docs/:page")
    (let [original "/docs/routing?lang=en#scroll-restoration"
          parsed   (routing/match-url original)
          rebuilt  (routing/route-url (:route-id parsed)
                                      (:params parsed)
                                      (:query parsed)
                                      (:fragment parsed))]
      (is (= :route/docs (:route-id parsed)))
      (is (= {:page "routing"} (:params parsed)))
      (is (= {"lang" "en"}     (:query parsed)))
      (is (= "scroll-restoration" (:fragment parsed)))
      (is (= original rebuilt)
          "the rebuilt URL equals the original — fragment round-trips"))))

;; ---- rf2-ede1h.1: route-url percent-encodes the fragment ------------------
;;
;; Per Spec 012 §Bidirectional URL ↔ params / §Fragments. `match-url`
;; decodes the `#fragment` portion through decodeURIComponent semantics
;; (`split-fragment` → `safe-url-decode`), so `route-url` MUST encode it
;; symmetrically. Appending the raw value produced a `#fragment` that
;; `match-url` rejected as malformed the moment the value carried a literal
;; `%` (the bare `%` fails to decode → whole-URL route-miss), breaking the
;; bidirectional contract for programmatic fragments.

(deftest route-url-percent-encodes-fragment
  (testing "a fragment with a literal `%` is percent-encoded on emission
            and round-trips through match-url (rf2-ede1h.1)"
    (rf/reg-route :route/docs {} "/docs/:page")
    (let [built (routing/route-url :route/docs {:page "routing"} {} "50% done")]
      (is (not (clojure.string/includes? built "% "))
          "the bare `% ` is not emitted raw — it was percent-encoded")
      (is (= "/docs/routing#50%25%20done" built)
          "the `%` encodes to %25 and the space to %20 (encodeURIComponent)")
      (let [parsed (routing/match-url built)]
        (is (some? parsed)
            "the built URL is NOT a malformed-URL route-miss")
        (is (= "50% done" (:fragment parsed))
            "the fragment decodes back to the original literal-`%` value"))))

  (testing "a fragment with reserved characters (`/`, `:`) round-trips"
    (rf/reg-route :route/docs2 {} "/docs/:page")
    (let [frag   "section/sub:1"
          built  (routing/route-url :route/docs2 {:page "x"} {} frag)
          parsed (routing/match-url built)]
      (is (some? parsed) "the encoded fragment matches")
      (is (= frag (:fragment parsed))
          "the reserved-character fragment round-trips byte-exact")))

  (testing "a plain fragment is unchanged (no over-encoding of safe chars)"
    (rf/reg-route :route/docs3 {} "/docs/:page")
    (is (= "/docs/x#scroll-restoration"
           (routing/route-url :route/docs3 {:page "x"} {} "scroll-restoration"))
        "safe characters (letters, digits, `-`) are not percent-encoded")))

;; ---- route-url ignores path-params not named by the pattern --------------
;;
;; route-url's emitter consumes only the `:`/`*` segments it walks in the
;; pattern. Extra keys in path-params (a common call-site over-supply, e.g.
;; passing the whole slice's :params back through) must be silently ignored,
;; not leaked into the URL. Pinned here so a future emitter refactor can't
;; start round-tripping stray keys into the path.

(deftest route-url-ignores-extra-path-params
  (testing "path-params keys not named by the route pattern are ignored"
    (rf/reg-route :route/article {} "/articles/:id")
    (is (= "/articles/intro"
           (routing/route-url :route/article {:id "intro" :stray "ignored" :extra 99}))
        "only the pattern's :id segment is emitted; :stray / :extra are dropped"))

  (testing "a route with no path params ignores any supplied path-params"
    (rf/reg-route :route/home {} "/")
    (is (= "/"
           (routing/route-url :route/home {:anything "here"}))
        "a param-less pattern emits its literal path regardless of supplied params")))

(deftest match-url-flags-validation-failure
  (testing "match-url surfaces :validation-failed? + :validation-error
            when the route declares :params and the parsed value rejects"
    (let [restore (rts/with-stub-validator)]
      (try
        ;; A schema that requires :id to be a non-empty string starting "a".
        (rf/reg-route :route/article
                      {:params (fn [{:keys [id]}] (clojure.string/starts-with? (or id "") "a"))} "/articles/:id")
        (let [m (routing/match-url "/articles/zoo")]
          (is (some? m) "the route still matches structurally")
          (is (true? (:validation-failed? m))
              ":validation-failed? flips when the schema rejects")
          (is (some? (:validation-error m))
              ":validation-error carries the explainer payload"))
        (let [m2 (routing/match-url "/articles/aardvark")]
          (is (false? (:validation-failed? m2))
              "a conforming value clears the flag")
          (is (nil? (:validation-error m2))
              "no error key when conformant"))
        (finally (restore))))))

(deftest route-url-throws-on-invalid-path-params
  (testing "route-url throws :rf.error/route-url-validation when
            path-params don't conform to the route's :params schema"
    (let [restore (rts/with-stub-validator)]
      (try
        (rf/reg-route :route/article
                      {:params (fn [{:keys [id]}] (clojure.string/starts-with? (or id "") "a"))} "/articles/:id")
        (let [ex (try (routing/route-url :route/article {:id "zoo"})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex)
              "non-conformant path-params raise")
          ;; rf2-vvixub — message is a human sentence + the trailing
          ;; [:rf.error/<id>] token; anchor on the canonical :rf.error/id
          ;; and assert the token substring, not exact equality.
          (is (= :rf.error/route-url-validation (:rf.error/id (ex-data ex)))
              "structured error id is :rf.error/route-url-validation")
          (is (re-find #"\[:rf\.error/route-url-validation\]" (ex-message ex))
              "the message carries the [:rf.error/route-url-validation] token")
          (let [data (ex-data ex)]
            (is (= :route/article (:route-id data)))
            (is (= :params (:slot data)))
            (is (= {:id "zoo"} (:value data)))
            (is (some? (:error data))
                "ex-data carries the explainer payload under :error")))
        ;; Conformant path-params round-trip happily.
        (is (= "/articles/aardvark"
               (routing/route-url :route/article {:id "aardvark"}))
            "conformant params still produce a URL")
        (finally (restore))))))

(deftest route-url-throws-on-invalid-query-params
  (testing "route-url throws :rf.error/route-url-validation when
            query-params don't conform to the route's :query schema"
    (let [restore (rts/with-stub-validator)]
      (try
        (rf/reg-route :route/search
                      {:query (fn [m] (and (string? (:q m))
                                           (pos? (count (:q m)))))} "/search")
        (let [ex (try (routing/route-url :route/search {} {:q ""})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex)
              "empty :q rejects against the query schema")
          ;; rf2-vvixub — anchor on :rf.error/id; assert the token, not equality.
          (is (= :rf.error/route-url-validation (:rf.error/id (ex-data ex))))
          (is (re-find #"\[:rf\.error/route-url-validation\]" (ex-message ex)))
          (is (= :query (:slot (ex-data ex)))))
        (let [ex (try (routing/route-url :route/search {} {})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex)
              "an empty query map still validates, so required query params reject")
          (is (= :query (:slot (ex-data ex)))))
        (finally (restore))))))

(deftest route-url-elides-nil-query-before-validation-rf2-w3qgc
  (testing "rf2-w3qgc: nil-valued query keys are elided BEFORE :query-schema
            validation, so `{:sort nil}` against a
            `:query [:map [:sort {:optional true} :string]]` route returns
            `/search` (the key is omitted) rather than throwing
            :rf.error/route-url-validation. Non-nil invalid values STILL fail."
    (let [restore (rts/with-stub-validator)]
      (try
        ;; Predicate modelling `:query [:map [:sort {:optional true} :string]]`:
        ;; :sort is OPTIONAL (absent OK), but when PRESENT it must be a
        ;; string. Because nil is elided before validation, `{:sort nil}`
        ;; reaches the predicate as `{}` (key absent) and conforms.
        (rf/reg-route :route/search
                      {:query (fn [m] (or (not (contains? m :sort))
                                          (string? (:sort m))))} "/search")
        ;; (1) route-url: nil omits the key, no throw, returns /search.
        (is (= "/search"
               (routing/route-url :route/search {} {:sort nil}))
            "route-url with {:sort nil} elides the key BEFORE validation → /search")
        ;; A valid string still emits the pair.
        (is (= "/search?sort=name"
               (routing/route-url :route/search {} {:sort "name"}))
            "a present, valid :sort string still round-trips into the query")
        ;; (2) A non-nil INVALID value STILL fails validation (the fix
        ;; narrows only nil; it does not weaken the schema gate).
        (let [ex (try (routing/route-url :route/search {} {:sort 123})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex)
              "a non-nil invalid :sort (a number) STILL fails validation")
          ;; rf2-vvixub — anchor on :rf.error/id; assert the token, not equality.
          (is (= :rf.error/route-url-validation (:rf.error/id (ex-data ex))))
          (is (re-find #"\[:rf\.error/route-url-validation\]" (ex-message ex)))
          (is (= :query (:slot (ex-data ex))))
          (is (= {:sort 123} (:value (ex-data ex)))
              ":value reports the elided map actually validated (nil-free)"))
        (finally (restore)))))

  (testing "rf2-w3qgc: programmatic `:rf.route/navigate` and SSR route-link
            with `{:sort nil}` push/produce `/search` without a validation
            error (the same nil-elision path through route-url)"
    (let [restore (rts/with-stub-validator)]
      (try
        (rf/reg-route :route/search
                      {:query (fn [m] (or (not (contains? m :sort))
                                          (string? (:sort m))))} "/search")
        (let [pushed (atom nil)]
          (rf/reg-fx :rf.nav/push-url
                     {:platforms #{:server :client}}
                     (fn [_ url] (reset! pushed url)))
          ;; Programmatic navigate with a nil optional query value: the
          ;; navigate handler resolves the target URL via route-url, which
          ;; must elide :sort and emit /search (no validation throw).
          (rf/dispatch-sync [:rf.route/navigate :route/search {} {:query {:sort nil}}])
          (is (= "/search" @pushed)
              "programmatic navigate with {:sort nil} pushes /search (no throw)"))
        ;; SSR route-link emission (server render path) drives the same
        ;; route-url; the link href must be /search, not a thrown error.
        (is (= "/search"
               (routing/route-url :route/search {} {:sort nil}))
            "SSR route-link href derives from route-url → /search for {:sort nil}")
        (finally (restore))))))

;; ============================================================================
;; rf2-andwd — meaningful test gaps from the routing audit
;; ============================================================================
;;
;; Audit reference: ai/findings/refactor-audit-r2-routing-2026-05-14.md
;; Lens 5 T1-T8.

(deftest invalid-route-patterns-fail-at-registration
  (testing "non-canonical path patterns raise actionable errors at reg-route"
    (doseq [[route-id pattern]
            [[:route/no-leading-slash "cart"]
             [:route/splat-not-final "/files/*rest/more"]
             [:route/nested-optional "/a{/:b{/:c}?}?"]
             [:route/optional-not-slash-prefixed "/articles{:id}?"]]]
      (let [ex (try
                 (rf/reg-route route-id {} pattern)
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) (str pattern " should be rejected"))
        (is (= :rf.error/invalid-route-pattern (:rf.error/id (ex-data ex))))
        (is (= route-id (:route-id (ex-data ex))))
        (is (= pattern (:pattern (ex-data ex))))
        (is (some? (:reason (ex-data ex)))
            "ex-data includes an actionable reason"))))

  (testing "canonical optional groups and final splats still register"
    (is (= :route/articles
           (rf/reg-route :route/articles {} "/articles{/:id}?")))
    (is (= :route/files
           (rf/reg-route :route/files {} "/files/*rest"))))

  (testing "trailing slashes in registered patterns are canonicalized away"
    (rf/reg-route :route/cart {} "/cart/")
    (is (= "/cart" (:path (rf/handler-meta :route :route/cart))))
    (is (= "/cart" (routing/route-url :route/cart {})))))

;; ---- T1: :rf.warning/route-shadowed-by-equal-score warning ---------------

(deftest route-shadowed-by-equal-score-warning
  (testing "registering two routes with structurally-equal rank emits
            :rf.warning/route-shadowed-by-equal-score (Spec 012 §Route
            ranking algorithm rule 6)"
    ;; Two routes with the same shape — same number of static / named /
    ;; splat / optional segments — score equal under rules 1-5. Rule 6
    ;; (reg-index tiebreak) keeps matching deterministic, but the
    ;; warning surfaces the conflict for tooling.
    (let [traces (atom [])]
      (rf/register-listener! :trace ::shadow (fn [ev] (swap! traces conj ev)))
      (rf/reg-route :route/a {} "/x/:id")
      (rf/reg-route :route/b {} "/y/:slug")
      (rf/unregister-listener! :trace ::shadow)
      (is (some (fn [ev]
                  (and (= :rf.warning/route-shadowed-by-equal-score
                          (:operation ev))
                       (= :route/b (-> ev :tags :route-id))
                       (= :route/a (-> ev :tags :shadowed))))
                @traces)
          "second equal-rank registration emits the warning naming both routes"))))

;; ---- T2: :int / :keyword / :boolean query coercion ----------------------

(deftest query-coercion-vocabulary
  (testing "coerce-by-type-form honours :int / :boolean for query-string
            values. Per rf2-3k3o7 a bare `:keyword` type-form is
            unbounded — the value stays as a string rather than
            interning an attacker-controllable string as a Clojure
            keyword. The narrowing-via-enum path is exercised by
            `query-coercion-keyword-enum-allowlist` below."
    (rf/reg-route :route/search
                  {:query [:map
                           [:count    :int]
                           [:sort     :keyword]
                           [:archived :boolean]
                           [:plain    :string]]} "/search")
    (let [m (routing/match-url "/search?count=42&sort=desc&archived=true&plain=hello")]
      (is (= 42 (get-in m [:query :count]))
          ":int coerces to a Long")
      (is (= "desc" (get-in m [:query :sort]))
          "rf2-3k3o7: bare `:keyword` stays as string — the JVM keyword-
          interning DoS surface is closed by requiring an `[:enum ...]`
          allowlist for keyword values")
      (is (= true (get-in m [:query :archived]))
          ":boolean coerces \"true\" to true")
      (is (= "hello" (get-in m [:query :plain]))
          ":string / unknown type-form passes through unchanged")))

  (testing ":boolean \"false\" coerces to false; non-true/non-false
            strings pass through unchanged"
    (rf/reg-route :route/page {:query [:map [:flag :boolean]]} "/p")
    (is (false? (get-in (routing/match-url "/p?flag=false") [:query :flag]))
        "\"false\" coerces to false")
    (is (= "maybe" (get-in (routing/match-url "/p?flag=maybe") [:query :flag]))
        "non-vocabulary strings pass through unchanged"))

  (testing ":int on a non-numeric string passes through unchanged
            (no throw — graceful degradation)"
    (rf/reg-route :route/page2 {:query [:map [:n :int]]} "/p2")
    (is (= "abc" (get-in (routing/match-url "/p2?n=abc") [:query :n]))
        "non-numeric :int input is left as-is (no exception)"))

  ;; rf2-oyw04: strict + host-IDENTICAL :int coercion. The whole string
  ;; must be an integer literal (`^-?\d+$`) to coerce; otherwise it stays a
  ;; string on BOTH hosts. The predecessor diverged on partial-numeric
  ;; input: `Long/parseLong "12abc"` threw -> string passthrough (JVM),
  ;; while `js/parseInt "12abc" 10` -> 12 (CLJS) — a Spec 011 hydration-
  ;; mismatch hazard. Now both hosts agree. The cross-host conformance
  ;; vehicle is fixtures/routing-query-string-coercion.edn (run by both the
  ;; JVM and CLJS corpus harnesses); this is the artefact-local pin.
  (testing "rf2-oyw04: :int coerces only whole integer literals; partial-
            numeric and radix-prefixed input stays a string identically on
            JVM and CLJS"
    (rf/reg-route :route/page3 {:query [:map [:page :int]]} "/p3")
    (is (= 12 (get-in (routing/match-url "/p3?page=12") [:query :page]))
        "clean integer literal coerces to a Long")
    (is (= -7 (get-in (routing/match-url "/p3?page=-7") [:query :page]))
        "signed integer literal coerces")
    (is (= "12abc" (get-in (routing/match-url "/p3?page=12abc") [:query :page]))
        "partial-numeric input stays a STRING — the JVM/CLJS asymmetry
         rf2-oyw04 closes (was 12 on CLJS, \"12abc\" on JVM)")
    (is (= "0x10" (get-in (routing/match-url "/p3?page=0x10") [:query :page]))
        "radix-prefixed input stays a string on both hosts")
    (is (= " 12" (get-in (routing/match-url "/p3?page=%2012") [:query :page]))
        "leading-whitespace input stays a string on both hosts")))

;; ---- rf2-fwz29i: OPTIONED Malli scalar schemas coerce like bare forms ----
;;
;; A scalar slot carrying ordinary Malli properties — `[:int {:min 1}]`,
;; `[:uuid {...}]`, `[:double {...}]`, `[:boolean {...}]`, or an optioned
;; enum `[:enum {...} :a :b]` — must coerce the URL string identically to
;; its bare form (`:int`, `:uuid`, ...). The pre-fix table took the raw
;; vector type-form, so `coerce-by-type-form` saw `[:int {:min 1}]` (not
;; `:int`), skipped coercion, and the still-string value `"2"` failed the
;; route's `[:int {:min 1}]` schema → `:validation-failed? true` → every
;; valid deep link 404'd. `[:maybe inner]` wrappers coerce the inner type.

(deftest rf2-fwz29i-optioned-scalar-query-coercion
  (testing "optioned scalar :query schemas coerce equivalently to bare forms"
    (rf/reg-route :route/items
                  {:query [:map
                           [:page [:int {:min 1}]]
                           [:ratio [:double {:min 0.0}]]
                           [:id [:uuid {}]]
                           [:archived [:boolean {}]]]} "/items")
    (let [uuid-str "550e8400-e29b-41d4-a716-446655440000"
          m (routing/match-url
              (str "/items?page=2&ratio=1.5&id=" uuid-str "&archived=true"))]
      (is (= :route/items (:route-id m)))
      (is (= 2 (get-in m [:query :page]))
          "[:int {:min 1}] coerces \"2\" to 2 (was \"2\" → validation-failed)")
      (is (= 1.5 (get-in m [:query :ratio]))
          "[:double {...}] coerces to a number")
      (is (= (parse-uuid uuid-str) (get-in m [:query :id]))
          "[:uuid {...}] coerces to a UUID object")
      (is (true? (get-in m [:query :archived]))
          "[:boolean {...}] coerces \"true\" to true")
      (is (false? (:validation-failed? m))
          "coerced typed values conform to their optioned schemas — no 404")))

  (testing "an optioned :int slot still FAILS validation for a value that
            violates the option (coercion happens, the option still bites)"
    (rf/reg-route :route/min-page
                  {:query [:map [:page [:int {:min 5}]]]} "/p")
    (let [m (routing/match-url "/p?page=2")]
      (is (= 2 (get-in m [:query :page]))
          "the string coerces to the number 2 (coercion is unconditional)")
      (is (true? (:validation-failed? m))
          "2 < :min 5 → validation still fails (the option is enforced)")))

  (testing "a non-numeric value for an optioned :int stays a string and fails"
    (rf/reg-route :route/min-page2
                  {:query [:map [:page [:int {:min 1}]]]} "/p2")
    (let [m (routing/match-url "/p2?page=abc")]
      (is (= "abc" (get-in m [:query :page]))
          "non-integer-literal stays a string (host-symmetric passthrough)")
      (is (true? (:validation-failed? m))
          "the string fails the :int schema — fail-closed, not a crash"))))

(deftest rf2-fwz29i-optioned-scalar-path-coercion
  (testing "optioned scalar :params (path) schemas coerce like bare forms"
    (rf/reg-route :route/page    {:params [:map [:n [:int {:min 1}]]]} "/page/:n")
    (rf/reg-route :route/article {:params [:map [:id [:uuid {}]]]} "/articles/:id")
    (rf/reg-route :route/double  {:params [:map [:x [:double {:min 0.0}]]]} "/d/:x")

    (testing "[:int {:min 1}] path param coerces; validation passes"
      (let [m (routing/match-url "/page/2")]
        (is (= :route/page (:route-id m)))
        (is (= 2 (get-in m [:params :n])) "\"2\" coerced to 2 (was string → 404)")
        (is (false? (:validation-failed? m)))))

    (testing "[:uuid {}] path param coerces to a #uuid; canonical route matches"
      (let [uuid-str "550e8400-e29b-41d4-a716-446655440000"
            m        (routing/match-url (str "/articles/" uuid-str))]
        (is (= :route/article (:route-id m)))
        (is (= (parse-uuid uuid-str) (get-in m [:params :id])))
        (is (uuid? (get-in m [:params :id])))
        (is (false? (:validation-failed? m)))))

    (testing "[:double {:min 0.0}] path param coerces to a number"
      (let [m (routing/match-url "/d/3.14")]
        (is (= 3.14 (get-in m [:params :x])))
        (is (false? (:validation-failed? m)))))

    (testing "an optioned :int path value violating the option still fails"
      (rf/reg-route :route/minp {:params [:map [:n [:int {:min 5}]]]} "/m/:n")
      (let [m (routing/match-url "/m/2")]
        (is (= 2 (get-in m [:params :n])) "coerced to the number 2")
        (is (true? (:validation-failed? m)) "2 < :min 5 → validation fails")))))

(deftest rf2-fwz29i-optioned-enum-keyword-allowlist
  (testing "an optioned `[:enum {...} :asc :desc]` keeps the keyword
            allowlist gate (opts map skipped); declared values intern,
            others stay strings"
    (rf/reg-route :route/sorted
                  {:query [:map [:sort [:enum {:default :asc} :asc :desc]]]} "/items")
    (let [m1 (routing/match-url "/items?sort=asc")
          m3 (routing/match-url "/items?sort=hostile-value")]
      (is (= :asc (get-in m1 [:query :sort]))
          "declared enum value interns to :asc even with an opts map")
      (is (= "hostile-value" (get-in m3 [:query :sort]))
          "value outside the allowlist stays a string — no unbounded intern"))))

;; ---- rf2-dcmkke: keyword-enum route params round-trip through the prism ----
;;
;; `route-url` emitted a keyword enum value with host `(str v)`, so `:asc`
;; became `%3Aasc` on the wire. `match-url`'s enum-keyword decoder
;; (`[:rf.route/enum-keyword #{"asc" "desc"}]`) only recognises the declared
;; TOKEN NAMES (`asc`, `desc`), so `%3Aasc` decoded to the STRING `":asc"`,
;; not the keyword `:asc` — `match-url(route-url(...))` did not recover the
;; canonical enum keyword route data. EP-0012 §Route Prism Laws and Spec 012
;; §924-936 (`/items?sort=desc` ↔ `{:sort :desc}`) require the round-trip.
;; The fix maps a declared keyword-enum value to its schema token name on
;; emission (query AND path), so `:asc` emits `asc` and round-trips.

(deftest rf2-dcmkke-keyword-enum-query-round-trips
  (testing "a keyword-enum :query value emits its schema TOKEN NAME (not
            `:asc` → %3Aasc) and round-trips back to the canonical keyword"
    (rf/reg-route :route/sorted
                  {:query [:map [:sort [:enum :asc :desc]]]} "/items")
    (let [u (routing/route-url :route/sorted {} {:sort :asc})]
      (is (= "/items?sort=asc" u)
          "the enum keyword `:asc` emits the declared token `asc`, NOT %3Aasc")
      (let [m (routing/match-url u)]
        (is (= :asc (get-in m [:query :sort]))
            "match-url recovers the canonical enum keyword :asc")
        (is (false? (:validation-failed? m))
            "the round-tripped value conforms to the [:enum :asc :desc] schema"))))

  (testing "the other enum choice round-trips too"
    (rf/reg-route :route/sorted2
                  {:query [:map [:sort [:enum :asc :desc]]]} "/items")
    (let [u (routing/route-url :route/sorted2 {} {:sort :desc})]
      (is (= "/items?sort=desc" u))
      (is (= :desc (get-in (routing/match-url u) [:query :sort]))))))

(deftest rf2-dcmkke-keyword-enum-path-round-trips
  (testing "a keyword-enum PATH param emits its token name and round-trips"
    (rf/reg-route :route/sort-path
                  {:params [:map [:dir [:enum :asc :desc]]]} "/items/:dir")
    (let [u (routing/route-url :route/sort-path {:dir :desc})]
      (is (= "/items/desc" u)
          "the path enum keyword `:desc` emits `desc`, NOT %3Adesc")
      (let [m (routing/match-url u)]
        (is (= :route/sort-path (:route-id m)))
        (is (= :desc (get-in m [:params :dir]))
            "match-url recovers the canonical enum keyword on the path side")
        (is (false? (:validation-failed? m)))))))

(deftest rf2-dcmkke-keyword-enum-query-retain-round-trips
  (testing "a `:query-retain` enum value (carried as a KEYWORD from a prior
            match-url into a target route-url) round-trips. match-url coerces
            `sort=asc` to `:asc`; route-url must then re-emit `:asc` as `asc`."
    (rf/reg-route :route/list
                  {:query         [:map [:sort [:enum :asc :desc]]]
                   :query-retain  [:sort]} "/list")
    ;; Simulate the retain flow: the retained value arrives as a KEYWORD
    ;; (match-url already interned it from the source URL's `sort=asc`).
    (let [retained (get-in (routing/match-url "/list?sort=asc") [:query :sort])]
      (is (= :asc retained)
          "the retained value is the coerced KEYWORD, not a string")
      (let [u (routing/route-url :route/list {} {:sort retained})]
        (is (= "/list?sort=asc" u)
            "the retained keyword re-emits as its token name (round-trips)")
        (is (= :asc (get-in (routing/match-url u) [:query :sort])))))))

(deftest rf2-dcmkke-invalid-keyword-enum-still-fails-validation
  (testing "an INVALID keyword-enum value is NOT stringified into a URL —
            route-url still fails validation (the schema bites before emit)"
    (rf/reg-route :route/sorted3
                  {:query [:map [:sort [:enum :asc :desc]]]} "/items")
    (let [ex (try (routing/route-url :route/sorted3 {} {:sort :sideways})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex)
          "an out-of-enum keyword value raises rather than emitting a URL")
      (is (= :rf.error/route-url-validation (:rf.error/id (ex-data ex)))
          "the structured validation error fires (not a stringified URL)"))))

(deftest rf2-fwz29i-maybe-wrapper-coercion
  (testing "[:maybe inner] coerces the present value against the inner
            type (query side); a coerced value conforms to the :maybe schema"
    (rf/reg-route :route/opt
                  {:query [:map
                           [:page [:maybe :int]]
                           [:size [:maybe [:int {:min 1}]]]]} "/opt")
    (let [m (routing/match-url "/opt?page=7&size=3")]
      (is (= 7 (get-in m [:query :page]))
          "[:maybe :int] coerces \"7\" to 7")
      (is (= 3 (get-in m [:query :size]))
          "[:maybe [:int {:min 1}]] coerces through both wrapper and option")
      (is (false? (:validation-failed? m))
          "coerced values conform to the :maybe schemas")))

  (testing "[:maybe :uuid] path param coerces; absent optional key is absent"
    (rf/reg-route :route/maybe-art {:params [:map [:id [:maybe :uuid]]]} "/a/:id")
    (let [uuid-str "550e8400-e29b-41d4-a716-446655440000"
          m        (routing/match-url (str "/a/" uuid-str))]
      (is (= (parse-uuid uuid-str) (get-in m [:params :id]))
          "[:maybe :uuid] coerces the present capture to a UUID")
      (is (false? (:validation-failed? m))))))

;; ---- rf2-3k3o7 / rf2-x0ngkv: keyword-interning defence on query keys -------
;;
;; The keyword-interning DoS (an attacker-influenced URL stream with
;; N-unique query keys burning N permanent slots on a long-running SSR
;; JVM) is closed at the source by SELECTIVE KEYWORDING — `coerce-query`
;; promotes ONLY keys declared by the route's `:query` schema /
;; `:query-defaults` / `:query-retain` to keyword keys; every undeclared
;; URL key passes through as a **string**, so a hostile URL of N-unique
;; undeclared keys interns ZERO keywords. No raw-query-size cap is needed
;; (rf2-x0ngkv removed the redundant `default-max-decoded-keys` ceiling).
;; The remaining defences:
;;
;; 1. Selective keywording — only keys declared by the route's `:query`
;;    schema / `:query-defaults` / `:query-retain` are promoted to keyword
;;    keys. Unknown keys stay as **string** keys. (This IS the closure.)
;; 2. `:keyword`-typed value gate — a bare `:keyword` type-form stays
;;    as a string; `[:enum :a :b ...]` is the bounded-allowlist path.

(deftest rf2-3k3o7-repeated-query-key-last-wins-stays-string
  (testing "rf2-5ifai: no :query vocabulary declared, so a repeated key
            stays a STRING and collapses last-wins — never interns a
            keyword, no cap involved"
    (rf/reg-route :route/search {} "/search")
    (let [n   5000
          q   (clojure.string/join "&" (repeat n "q=v"))
          m   (routing/match-url (str "/search?" q))]
      (is (= :route/search (:route-id m)))
      (is (= {"q" "v"} (:query m))
          "many repeated pairs for one undeclared key collapse last-wins as a string")
      (is (contains? (:query m) "q")
          "the surviving key is a STRING, not an interned keyword")
      (is (not (contains? (:query m) :q))))))

(deftest rf2-x0ngkv-many-undeclared-keys-stay-strings-no-cap
  (testing "a route declaring NO query vocabulary keeps EVERY URL key as a
            string regardless of cardinality — no per-URL key cap, the
            keyword table is never extended (rf2-x0ngkv removed the cap)"
    (rf/reg-route :route/search {} "/search")
    ;; Far more unique keys than the old 10000 cap would have permitted:
    ;; the parse succeeds (no throw) and every key is a STRING.
    (let [n   15000
          q   (clojure.string/join "&" (map #(str "k" % "=v") (range n)))
          url (str "/search?" q)
          m   (routing/match-url url)]
      (is (some? m) "a high-cardinality URL parses without throwing — no cap")
      (is (= :route/search (:route-id m)))
      (is (= n (count (:query m))) "every unique key survives")
      (is (every? string? (keys (:query m)))
          "EVERY undeclared key is a STRING — zero keywords interned")
      (is (not-any? keyword? (keys (:query m)))))))

(deftest rf2-3k3o7-undeclared-query-keys-stay-as-strings
  (testing "query keys NOT declared by the route's `:query` schema or
            `:query-defaults` stay as **string** keys in the parsed
            :query map — no permanent keyword-table slot is burned on
            their behalf"
    (rf/reg-route :route/search
                  {:query [:map [:q :string]]} "/search")
    (let [m (routing/match-url "/search?q=clojure&unknown1=foo&unknown2=bar")]
      (is (some? m))
      (is (= "clojure" (get-in m [:query :q]))
          "declared :q (keyword key) is present and typed per schema")
      (is (= "foo" (get-in m [:query "unknown1"]))
          "undeclared `unknown1` is keyed by STRING, not keyword")
      (is (= "bar" (get-in m [:query "unknown2"]))
          "undeclared `unknown2` is keyed by STRING, not keyword")
      (is (not (contains? (:query m) :unknown1))
          "no `:unknown1` keyword in the result map")
      (is (not (contains? (:query m) :unknown2))
          "no `:unknown2` keyword in the result map"))))

(deftest match-url-fail-closed-passes-through-match-and-miss
  (testing "rf2-6t1xb: `match-url-fail-closed` is the generic
            navigation-resilience wrapper — a clean match and a bare miss
            both pass through with no `:throw-reason`"
    (rf/reg-route :route/search {} "/search")
    (testing "a clean match passes through with no throw-reason"
      (let [{:keys [match throw-reason]}
            (registry/match-url-fail-closed "/search?q=clojure")]
        (is (= :route/search (:route-id match)))
        (is (nil? throw-reason))))
    (testing "a bare miss passes through as nil match, no throw-reason"
      (let [{:keys [match throw-reason]}
            (registry/match-url-fail-closed "/no/such/path")]
        (is (nil? match))
        (is (nil? throw-reason)
            "a bare miss is NOT a throw — no discriminator")))))

;; ---- rf2-5ifai: no :query vocabulary -> all string keys ------------------
;;
;; Per Spec 012 §Query strings and fragments and the rf2-tfgdv security
;; review: a route declaring NO query vocabulary at all (no `:query` /
;; `:query-defaults` / `:query-retain`) keeps every URL key as a string.
;; Authors who want keyword keys declare them via `:query` /
;; `:query-defaults` / `:query-retain` — author-named intent is the
;; trust boundary. Symmetrical to the rf2-3k3o7 value-side fix: hostile
;; URLs composed of N-unique keys would otherwise burn N permanent JVM
;; keyword slots, and a bare `(reg-route :route/x {} "/x")` is the
;; high-cardinality public-surface case where the DoS hits hardest.

(deftest rf2-5ifai-no-vocabulary-route-keeps-all-keys-as-strings
  (testing "rf2-5ifai: a route declaring NO :query vocabulary keeps
            every URL query key as a string. The legacy keyword-all
            fallback is gone (pre-alpha — no back-compat shim)."
    (rf/reg-route :route/bare {} "/bare")
    (let [m (routing/match-url "/bare?foo=1&bar=2&baz=3")]
      (is (some? m))
      (is (= {"foo" "1" "bar" "2" "baz" "3"} (:query m))
          "all URL keys remain strings — no keyword promotion at all")
      (doseq [k [:foo :bar :baz]]
        (is (not (contains? (:query m) k))
            (str "no `" k "` keyword in the result map")))))
  (testing "rf2-5ifai: even single-key URLs do not get a keyword promotion"
    (rf/reg-route :route/single {} "/single")
    (let [m (routing/match-url "/single?x=1")]
      (is (some? m))
      (is (= {"x" "1"} (:query m)))
      (is (not (contains? (:query m) :x))
          "single :x key stays a string — no special case for cardinality 1"))))

(deftest rf2-3k3o7-defaults-extend-declared-universe
  (testing "keys declared via `:query-defaults` (without a `:query`
            schema) widen the keyword universe — they get keyword
            keys; non-declared URL keys stay string-keyed"
    (rf/reg-route :route/list
                  {:query-defaults {:page 1 :sort "asc"}} "/list")
    (let [m (routing/match-url "/list?page=3&unknown=x")]
      (is (= "3" (get-in m [:query :page]))
          ":page from defaults → declared → keyword-keyed (no schema coerce → stays string)")
      (is (= "asc" (get-in m [:query :sort]))
          "absent :sort filled from defaults")
      (is (= "x" (get-in m [:query "unknown"]))
          "undeclared `unknown` stays string-keyed"))))

(deftest rf2-3k3o7-keyword-enum-allowlist
  (testing "a `[:enum :asc :desc]` schema constrains the keyword
            universe — values matching declared choices intern, others
            stay as strings (bounded by construction)"
    (rf/reg-route :route/sorted
                  {:query [:map [:sort [:enum :asc :desc]]]} "/items")
    (let [m1 (routing/match-url "/items?sort=asc")
          m2 (routing/match-url "/items?sort=desc")
          m3 (routing/match-url "/items?sort=hostile-value")]
      (is (= :asc  (get-in m1 [:query :sort]))
          "declared enum value `asc` interns to :asc")
      (is (= :desc (get-in m2 [:query :sort]))
          "declared enum value `desc` interns to :desc")
      (is (= "hostile-value" (get-in m3 [:query :sort]))
          "value outside the enum allowlist stays as string — no intern"))))

(deftest rf2-3k3o7-bare-keyword-stays-string
  (testing "rf2-3k3o7: bare `:keyword` type-form (no enum allowlist) is
            an unbounded-intern site — value stays as a string"
    (rf/reg-route :route/page
                  {:query [:map [:tag :keyword]]} "/page")
    (let [m (routing/match-url "/page?tag=arbitrary-value")]
      (is (= "arbitrary-value" (get-in m [:query :tag]))
          "bare :keyword preserves the URL value as a string — no
          unbounded intern site"))))

;; ---- T3: :query-defaults populates absent keys -------------------------

(deftest query-defaults-populates-absent-keys
  (testing ":query-defaults supplies values for absent query keys; URL-
            supplied values win on conflict (Spec 012 §Query-string
            coercion §Defaults)"
    (rf/reg-route :route/list
                  {:query-defaults {:page 1 :per-page 20 :sort "asc"}} "/list")
    (let [m (routing/match-url "/list")]
      (is (= 1     (get-in m [:query :page]))
          ":query-defaults populates :page when absent")
      (is (= 20    (get-in m [:query :per-page]))
          ":query-defaults populates :per-page when absent")
      (is (= "asc" (get-in m [:query :sort]))
          ":query-defaults populates :sort when absent"))
    (let [m (routing/match-url "/list?page=3&sort=desc")]
      (is (= "3"    (get-in m [:query :page]))
          "URL-supplied :page wins over default (note: no :query schema → string)")
      (is (= 20     (get-in m [:query :per-page]))
          "default still applied for the absent key")
      (is (= "desc" (get-in m [:query :sort]))
          "URL-supplied :sort wins over default"))))

;; ---- T4: route-url optional-group elision when inner params absent ----

(deftest route-url-optional-group-elision
  (testing "route-url with absent optional-group params elides the group
            (Spec 012 §Bidirectional URL ↔ params §Optional groups)"
    (rf/reg-route :route/articles
                  {} "/articles{/:id}?")
    (is (= "/articles"
           (routing/route-url :route/articles {}))
        "absent :id → optional group elides; bare /articles emits")
    (is (= "/articles/intro"
           (routing/route-url :route/articles {:id "intro"}))
        "present :id → optional group emits including the leading /"))

  (testing "deeper optional-group elision: an inner param's absence
            collapses the whole group (every? over inner-names)"
    (rf/reg-route :route/articles2
                  {} "/articles{/:id/:slug}?")
    (is (= "/articles"
           (routing/route-url :route/articles2 {}))
        "both absent → group elides")
    (is (= "/articles"
           (routing/route-url :route/articles2 {:id "intro"}))
        "ONE inner param absent → group still elides (every? requires all)")
    (is (= "/articles/intro/welcome"
           (routing/route-url :route/articles2 {:id "intro" :slug "welcome"}))
        "all inner params present → group emits")))

;; ---- rf2-8xvyo: empty-string path param inside an OPTIONAL GROUP is
;; rejected on emission, exactly like a top-level required path param.
;;
;; The optional-group gate enters a group when every inner param is
;; `some?`. `(some? "")` is TRUE, so `{:id ""}` ENTERS the group and the
;; pre-fix emitter wrote the value directly — emitting a zero-length
;; segment (`/articles/`) that `match-url`'s trailing-slash normalisation
;; erases (`/articles/` → `/articles`) before matching. The URL then
;; round-trips back as the param ABSENT, diverging the committed route
;; slice from the address bar (reload / popstate / SSR-hydration drift).
;; Spec 012 §`route-url` nil-policy makes `""` a HARD ERROR for ANY path
;; segment — the optional-group path must apply the same invariant as the
;; top-level `require-param` (rf2-ede1h.2). `false` / `0` are non-empty
;; legitimate segments and still round-trip on either side.

(deftest route-url-optional-group-empty-string-rejected
  (testing "rf2-8xvyo: an empty-string param inside an optional group is
            REJECTED on emission — it cannot emit an un-round-trippable
            trailing slash (`/articles/`)"
    (rf/reg-route :route/og-articles {} "/articles{/:id}?")
    ;; Sanity: absent elides, present-non-empty emits, as before.
    (is (= "/articles"
           (routing/route-url :route/og-articles {}))
        "absent :id → group elides; bare /articles")
    (is (= "/articles/intro"
           (routing/route-url :route/og-articles {:id "intro"}))
        "present non-empty :id → group emits")
    ;; The bug: `(some? "")` enters the group, then emits `/articles/`.
    (let [ex (try
               (routing/route-url :route/og-articles {:id ""})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex)
          "\"\" optional-group param throws (it would emit \"/articles/\", which match-url normalises to \"/articles\" and re-parses with :id ABSENT)")
      ;; rf2-vvixub — anchor on the canonical :rf.error/id discriminator.
      (is (= :rf.error/missing-route-param (:rf.error/id (ex-data ex)))
          "reuses the missing/empty-required-param error id")
      (is (= "" (:value (ex-data ex)))
          "ex-data carries the offending empty-string value")))

  (testing "rf2-8xvyo: empty-string rejection holds for an optional group
            trailing a REQUIRED top-level param (/articles/:id{/:slug}?)"
    (rf/reg-route :route/og-slug {} "/articles/:id{/:slug}?")
    ;; The required :id still round-trips; absent :slug elides.
    (is (= "/articles/5"
           (routing/route-url :route/og-slug {:id "5"}))
        "required :id present, optional :slug absent → group elides")
    (is (= "/articles/5/welcome"
           (routing/route-url :route/og-slug {:id "5" :slug "welcome"}))
        "both present → group emits")
    ;; Empty optional-group :slug rejected (would emit "/articles/5/").
    (let [ex (try
               (routing/route-url :route/og-slug {:id "5" :slug ""})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex)
          "\"\" optional-group :slug throws — it would emit the un-round-trippable \"/articles/5/\"")
      ;; rf2-vvixub — anchor on the canonical :rf.error/id discriminator.
      (is (= :rf.error/missing-route-param (:rf.error/id (ex-data ex))))
      (is (= "" (:value (ex-data ex))))))

  (testing "rf2-8xvyo: false and 0 inside an optional group STILL round-trip —
            only the empty string is rejected (non-empty falsy is legitimate)"
    (rf/reg-route :route/og-flag {} "/items{/:flag}?")
    (is (= "/items/false"
           (routing/route-url :route/og-flag {:flag false}))
        "false → non-empty segment \"false\"")
    (is (= "/items/0"
           (routing/route-url :route/og-flag {:flag 0}))
        "0 → non-empty segment \"0\"")
    (is (= {:flag "false"}
           (:params (routing/match-url (routing/route-url :route/og-flag {:flag false}))))
        "false round-trips through match-url")
    (is (= {:flag "0"}
           (:params (routing/match-url (routing/route-url :route/og-flag {:flag 0}))))
        "0 round-trips through match-url")))

;; ---- optional-group elision: the canonical slash-INSIDE spelling ---------
;;
;; rf2-5u1r6a pins ONE optional-group spelling: slash-INSIDE the braces
;; (`{/:id}?`, `{/:base}?` — rf2-av1). The group OWNS its leading slash, so
;; eliding the whole group when its param is absent drops that slash cleanly
;; and can NEVER orphan a bracketing literal `/` — the `//about`
;; protocol-relative-URL footgun (a modifier-click escapes the app;
;; programmatic `pushState` diverges slice from address bar) is structurally
;; impossible for the slash-inside form. A leading group (`{/:base}?/about`)
;; and a root-only group (`{/:base}?`) both elide to a single clean slash.
;;
;; The slash-OUTSIDE inline spelling (`{:base}?` between literal `/`s, e.g.
;; `/{:base}?/about`) that rf2-8zvajk once accepted is now REJECTED at
;; registration (`validate-optional-group!`), so no emitter separator-repair
;; is needed — the drift is closed at the source.

(deftest route-url-optional-group-no-double-slash
  (testing "rf2-5u1r6a: a LEADING optional group ({/:base}?/about) emits a
            single separator when absent — never `//`"
    (rf/reg-route :route/inline-about {} "{/:base}?/about")
    (is (= "/about"
           (routing/route-url :route/inline-about {}))
        "absent :base → single leading separator, NOT the protocol-relative `//about`")
    (is (= "/docs/about"
           (routing/route-url :route/inline-about {:base "docs"}))
        "present :base → /docs/about")
    (is (not (clojure.string/includes? (subs (routing/route-url :route/inline-about {}) 1) "//"))
        "no protocol-relative double slash anywhere in the absent emission")
    ;; Round-trip: emitted absent URL re-parses to the same route, no params.
    (let [m (routing/match-url (routing/route-url :route/inline-about {}))]
      (is (= :route/inline-about (:route-id m))
          "absent emission round-trips through match-url to the same route")
      (is (= {} (:params m))
          "no :base param survives the round-trip")))

  (testing "rf2-5u1r6a: a MID-PATH optional group (/docs{/:section}?/about)
            does not orphan a separator on either side"
    (rf/reg-route :route/docs-about {} "/docs{/:section}?/about")
    (is (= "/docs/about"
           (routing/route-url :route/docs-about {}))
        "absent :section → /docs/about, NOT /docs//about")
    (is (= "/docs/api/about"
           (routing/route-url :route/docs-about {:section "api"}))
        "present :section → /docs/api/about"))

  (testing "rf2-5u1r6a: a TRAILING optional group (/articles{/:id}?)
            does not leave a dangling slash"
    (rf/reg-route :route/articles-id {} "/articles{/:id}?")
    (is (= "/articles"
           (routing/route-url :route/articles-id {}))
        "absent :id → /articles, NOT the dangling /articles/")
    (is (= "/articles/5"
           (routing/route-url :route/articles-id {:id "5"}))
        "present :id → /articles/5"))

  (testing "rf2-5u1r6a: a ROOT-only optional group ({/:base}?) resolves to `/`
            when absent — never the empty string"
    (rf/reg-route :route/root-base {} "{/:base}?")
    (is (= "/"
           (routing/route-url :route/root-base {}))
        "absent :base → root `/`, never the empty string")
    (is (= "/x"
           (routing/route-url :route/root-base {:base "x"}))
        "present :base → /x"))

  (testing "rf2-5u1r6a: the slash-OUTSIDE spelling (/{:base}?/about) is now
            REJECTED at registration — one canonical spelling only"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"slash-prefixed"
          (rf/reg-route :route/outside {} "/{:base}?/about"))
        "the slash-outside inline form throws :rf.error/invalid-route-pattern")

    (rf/reg-route :route/owning {} "/articles{/:id}?")
    (is (= "/articles" (routing/route-url :route/owning {})))
    (is (= "/articles/5" (routing/route-url :route/owning {:id "5"}))))

  (testing "rf2-8zvajk: a splat value carrying embedded `//` is PRESERVED —
            the fix collapses elision separators, NOT every `//` globally"
    (rf/reg-route :route/files {} "/files/*rest")
    (is (= "/files/a//b"
           (routing/route-url :route/files {:rest "a//b"}))
        "an embedded double slash inside a splat value survives untouched")))

;; ---- T4b: match-url optional-group param is ABSENT, not nil-valued ------
;;
;; Regression for rf2-yejde. Per Spec 012 §Path-pattern grammar (Optional
;; segment group): "param present only if matched." The canonical example
;; route /articles/:id{/:slug}? declares :params with {:optional true} on
;; :slug. Malli {:optional true} governs KEY PRESENCE, not nil values, so a
;; present {:slug nil} is REJECTED. Pre-fix, match-against zipmap'd the
;; unmatched optional group as :slug nil → a legitimate /articles/<id> URL
;; failed :params validation and routed to not-found. The fix strips
;; nil-valued keys after the zipmap so the unmatched param is absent.
;;
;; The stub :params predicate below mirrors Malli {:optional true}: :slug,
;; *when present*, must be a non-nil string; an ABSENT :slug is fine.

(deftest match-url-optional-group-param-absent-not-nil
  (testing "an unmatched optional-group param is ABSENT from :params, not
            nil-valued, so a route carrying a {:optional true} :params
            schema still matches a URL that omits the optional segment
            (Spec 012 §Path-pattern grammar §Optional segment group)"
    (let [restore (rts/with-stub-validator)
          ;; Mirrors [:map [:id :string] [:slug {:optional true} :string]]:
          ;; :id must be a non-nil string; :slug, when the KEY is present,
          ;; must be a non-nil string (a present nil rejects, as Malli does).
          slug-optional-schema
          (fn [{:keys [id] :as params}]
            (and (string? id)
                 (or (not (contains? params :slug))
                     (string? (:slug params)))))]
      (try
        (rf/reg-route :route/article-slug
                      {:params slug-optional-schema} "/articles/:id{/:slug}?")

        (testing "bare /articles/5 — optional :slug unmatched"
          (let [m (routing/match-url "/articles/5")]
            (is (some? m) "the route matches structurally")
            (is (= {:id "5"} (:params m))
                ":slug is ABSENT (not nil-valued) when the optional group is unmatched")
            (is (not (contains? (:params m) :slug))
                "the unmatched optional key is omitted entirely")
            (is (false? (:validation-failed? m))
                "a present {:slug nil} would reject the {:optional true} schema; an absent :slug validates cleanly")
            (is (nil? (:validation-error m))
                "no validation error for the absent optional param")))

        (testing "/articles/5/intro — optional :slug supplied"
          (let [m (routing/match-url "/articles/5/intro")]
            (is (some? m) "the route matches when the optional segment is present")
            (is (= {:id "5" :slug "intro"} (:params m))
                "the optional key is present with its captured value when supplied")
            (is (false? (:validation-failed? m))
                "the supplied :slug conforms")))
        (finally (restore))))))

;; ---- T5: splat /files/*rest matches multi-segment paths ----------------

(deftest splat-multi-segment-match
  (testing "splat /files/*rest matches /files/a/b/c with :params
            {:rest \"a/b/c\"} (Spec 012 §Bidirectional URL ↔ params §Splat)"
    (rf/reg-route :route/files {} "/files/*rest")
    (let [m (routing/match-url "/files/a/b/c")]
      (is (some? m)              "splat matches multi-segment path")
      (is (= :route/files (:route-id m)))
      (is (= "a/b/c" (get-in m [:params :rest]))
          "splat preserves literal '/' inside the captured value"))

    (testing "single-segment splat input"
      (is (= {:rest "only"}
             (:params (routing/match-url "/files/only")))
          "splat captures a single segment too"))

    (testing "splat round-trips through route-url"
      (let [built (routing/route-url :route/files {:rest "a/b/c"})]
        (is (= "/files/a/b/c" built)
            "route-url emits the splat segments preserving '/'")))))

;; ---- url-encode-splat per-segment encoding -------------------------------
;;
;; Per Spec 012 §Bidirectional URL ↔ params §Splat. `url-encode-splat`
;; (url.cljc:27-31) splits on `/`, encodes EACH segment with
;; encodeURIComponent semantics, and re-joins with literal `/`. The
;; ASCII-safe round-trip ("a/b/c") above can't observe the encode step
;; at all — it only proves `/` is preserved. This pins the actual reason
;; the per-segment join exists: a segment that needs encoding is encoded,
;; while the `/` separators between segments stay literal (NOT %2F).
(deftest splat-segment-percent-encoding
  (testing "a splat segment needing encoding is encoded; literal '/' is preserved"
    (rf/reg-route :route/files {} "/files/*rest")
    (is (= "/files/a/b%20c"
           (routing/route-url :route/files {:rest "a/b c"}))
        "the space inside a segment encodes to %20; the segment '/' stays literal")
    (is (= "/files/a%20b/c%20d"
           (routing/route-url :route/files {:rest "a b/c d"}))
        "every segment is encoded individually; both '/' separators stay literal")
    (is (= "/files/x%26y/z"
           (routing/route-url :route/files {:rest "x&y/z"}))
        "an `&` inside a segment is encoded so it cannot leak into a query"))

  (testing "splat encode round-trips back through match-url (decode is the inverse)"
    (rf/reg-route :route/files2 {} "/files/*rest")
    (let [built (routing/route-url :route/files2 {:rest "a/b c"})]
      (is (= "/files/a/b%20c" built))
      (is (= "a/b c" (get-in (routing/match-url built) [:params :rest]))
          "match-url decodes each segment back, recovering the original splat value"))))

;; ============================================================================
;; rf2-cylse.5 — match-url coerces PATH params against the :params schema
;; (mirror of the query side). The canonical Spec 012 :uuid route must
;; round-trip a real UUID URL to {:id #uuid ...}, not 404. Exercised with
;; a real Malli validator (re-frame.schemas is required by this ns's
;; fixture), so :validation-failed? actually runs.
;; ============================================================================

(deftest path-param-coercion-against-params-schema
  (testing "rf2-cylse.5: a typed PATH param coerces against the route's
            :params schema BEFORE validation — :int / :uuid round-trip,
            validation passes, and the canonical Spec 012 :uuid route
            matches a real UUID URL instead of 404ing"
    (rf/reg-route :route/page    {:params [:map [:n :int]]} "/page/:n")
    (rf/reg-route :route/article {:params [:map [:id :uuid]]} "/articles/:id")
    (rf/reg-route :route/double  {:params [:map [:x :double]]} "/d/:x")
    (rf/reg-route :route/str     {:params [:map [:v :string]]} "/s/:v")

    (testing ":int path param coerces to a number; validation passes"
      (let [m (routing/match-url "/page/42")]
        (is (= :route/page (:route-id m)))
        (is (= 42 (get-in m [:params :n])) "string \"42\" coerced to the number 42")
        (is (false? (:validation-failed? m))
            "coerced :int conforms to [:n :int] — no validation failure (was true)")))

    (testing ":uuid path param coerces to a #uuid object; canonical route matches"
      (let [uuid-str "550e8400-e29b-41d4-a716-446655440000"
            m        (routing/match-url (str "/articles/" uuid-str))]
        (is (= :route/article (:route-id m)))
        (is (= (parse-uuid uuid-str) (get-in m [:params :id]))
            "string coerced to a real #uuid object (Spec 012:269)")
        (is (uuid? (get-in m [:params :id])) "the slice carries a UUID object, not a string")
        (is (false? (:validation-failed? m))
            "coerced :uuid conforms to [:id :uuid] — the canonical route matches (was 404)")))

    (testing ":double path param coerces to a number"
      (let [m (routing/match-url "/d/3.14")]
        (is (= 3.14 (get-in m [:params :x])))
        (is (false? (:validation-failed? m)))))

    (testing ":string path param stays a string (no coercion); a non-UUID
              for a :uuid route stays a string and fails validation"
      (is (= "hello" (get-in (routing/match-url "/s/hello") [:params :v])))
      (let [m (routing/match-url "/articles/not-a-uuid")]
        ;; not-a-uuid stays a string (parse-uuid → nil → passthrough), so
        ;; the :uuid schema flags it — fail-closed, not a crash.
        (is (= "not-a-uuid" (get-in m [:params :id])))
        (is (true? (:validation-failed? m))
            "a non-UUID value for a :uuid route fails validation (string passthrough)")))

    (testing "round-trips: route-url rebuilds the URL from the coerced params"
      (let [uuid-str "550e8400-e29b-41d4-a716-446655440000"
            m        (routing/match-url (str "/articles/" uuid-str))]
        (is (= (str "/articles/" uuid-str)
               (routing/route-url :route/article (:params m)))
            "URL → coerced params → URL is byte-identical")))))

;; ============================================================================
;; rf2-cylse.1 — :int coercion is HOST-SYMMETRIC and TOTAL on oversized
;; integers. The JVM half of the cross-host parity pin; the CLJS half lives
;; in routing_history_cljs_test.cljs. Both hosts must agree EXACTLY.
;; ============================================================================

(deftest int-coercion-oversized-host-parity-jvm
  (testing "rf2-cylse.1: an integer literal above the cross-host
            safe-integer ceiling (2^53-1) PASSES THROUGH AS A STRING on
            the JVM (was: exact Long, which CLJS rounds to a lossy double
            — a Spec 011 hydration mismatch), and a >2^63 literal does NOT
            throw (was: NumberFormatException escaping match-url)"
    (rf/reg-route :route/items {:query [:map [:page :int]]} "/items")

    (testing "values within the safe-integer range still coerce"
      (is (= 42 (get-in (routing/match-url "/items?page=42") [:query :page])))
      (is (= 9007199254740991
             (get-in (routing/match-url "/items?page=9007199254740991") [:query :page]))
          "2^53-1 (MAX_SAFE_INTEGER) coerces — the ceiling is inclusive"))

    (testing "values ABOVE the safe-integer ceiling pass through as strings (both hosts agree)"
      (is (= "9007199254740992"
             (get-in (routing/match-url "/items?page=9007199254740992") [:query :page]))
          "2^53 exceeds MAX_SAFE_INTEGER → string passthrough (CLJS would be lossy)")
      (is (= "9007199254740993"
             (get-in (routing/match-url "/items?page=9007199254740993") [:query :page]))
          "the rf2-cylse.1 canonical lossy-double case → string on BOTH hosts")
      (is (= "-9007199254740993"
             (get-in (routing/match-url "/items?page=-9007199254740993") [:query :page]))
          "negative oversized literal also passes through"))

    (testing "a literal beyond 2^63 does NOT throw (parse-long is total)"
      (is (= "99999999999999999999999"
             (get-in (routing/match-url "/items?page=99999999999999999999999") [:query :page]))
          "no NumberFormatException escapes — direct match-url callers see a clean string"))))

;; ============================================================================
;; rf2-dn26r — route lifecycle trace ops
;; ============================================================================

(deftest route-registered-trace-on-first-time-reg
  (testing ":rf.route/registered fires on FIRST-TIME reg-route (rf2-dn26r).
            Re-registration with the same id rides the cross-kind
            `:rf.registry/handler-replaced` trace; not re-emitted here.
            Mirrors the `:rf.flow/registered` symmetry."
    (let [traces (atom [])]
      (rf/register-listener! :trace ::reg-trace (fn [ev] (swap! traces conj ev)))
      (rf/reg-route :route/home {} "/")
      (rf/reg-route :route/home {} "/") ;; re-register (no trace)
      (rf/unregister-listener! :trace ::reg-trace)
      (let [reg-events (filter #(= :rf.route/registered (:operation %)) @traces)]
        (is (= 1 (count reg-events))
            "first-time reg-route emits :rf.route/registered exactly once")
        (is (= :route/home (-> reg-events first :tags :route-id))
            ":route-id rides in :tags")
        (is (= "/" (-> reg-events first :tags :path))
            ":path rides in :tags")))))

(deftest route-cleared-trace-on-unregister
  (testing "clear-route emits :rf.route/cleared (rf2-dn26r)"
    (rf/reg-route :route/transient {} "/transient")
    (let [traces (atom [])]
      (rf/register-listener! :trace ::cleared-trace (fn [ev] (swap! traces conj ev)))
      (routing/clear-route :route/transient)
      (routing/clear-route :route/transient) ;; idempotent, no trace
      (rf/unregister-listener! :trace ::cleared-trace)
      (let [cleared-events (filter #(= :rf.route/cleared (:operation %)) @traces)]
        (is (= 1 (count cleared-events))
            "clear-route emits :rf.route/cleared exactly once")
        (is (= :route/transient (-> cleared-events first :tags :route-id))
            ":route-id rides in :tags")))))

;; ===========================================================================
;; rf2-aleg9 — match-against direct function-boundary tests
;; (follow-on from rf2-q1z1u F8)
;;
;; `match-against` is the pattern-matcher fn consumed by the routing
;; facade's match-url. The facade-level tests above exercise it
;; transitively via `:rf.route/navigate`, but a `match-against`-only
;; regression that happens to be neutralised by the facade's URL
;; normalisation (canonical-route-pattern, query-string parse,
;; trailing-slash handling) would slip through the facade tests.
;;
;; These tests call `match-against` directly with a `parse-pattern`
;; output and a path string. Pins:
;;   - literal segments (exact match + non-match)
;;   - :param capture
;;   - :splat capture across multi-segment paths
;;   - empty-pattern edge — `/` matches `/`
;;   - non-matching URL returns nil cleanly (no throw, no error)
;; ===========================================================================

(deftest match-against-literal-segment-exact-match
  (testing "rf2-aleg9 — literal-segment pattern matches its exact URL
            and returns an empty params map; a sibling URL with the
            same shape but different literal returns nil"
    (let [compiled (routing.match/parse-pattern "/foo/bar")]
      (is (= {} (routing.match/match-against compiled "/foo/bar"))
          "exact literal URL → empty params map (no captures registered)")
      (is (nil? (routing.match/match-against compiled "/foo/baz"))
          "sibling literal that differs on last segment → nil (no-match)")
      (is (nil? (routing.match/match-against compiled "/foo"))
          "partial-prefix URL → nil (no-match; re-matches anchors both ends)")
      (is (nil? (routing.match/match-against compiled "/foo/bar/extra"))
          "URL longer than pattern → nil (anchored end)"))))

(deftest match-against-named-param-extraction
  (testing "rf2-aleg9 — a `:id` segment captures the URL segment value
            into the params map under the keyword key"
    (let [compiled (routing.match/parse-pattern "/users/:id")]
      (is (= {:id "42"} (routing.match/match-against compiled "/users/42"))
          "param captured under :id, value is the raw URL segment")
      (is (= {:id "alice"} (routing.match/match-against compiled "/users/alice"))
          "alphabetic param value captured")
      (is (nil? (routing.match/match-against compiled "/users/"))
          "empty param segment → nil (regex requires non-empty capture)")
      (is (nil? (routing.match/match-against compiled "/users"))
          "missing param segment → nil"))))

(deftest match-against-optional-group-omits-unmatched-param
  (testing "rf2-yejde — a param inside an UNMATCHED optional group is
            absent from the params map (not nil-valued), so downstream
            {:optional true} :params schemas accept the match (Spec 012
            §Path-pattern grammar §Optional segment group: 'param present
            only if matched')"
    (let [compiled (routing.match/parse-pattern "/articles/:id{/:slug}?")]
      (is (= {:id "5"} (routing.match/match-against compiled "/articles/5"))
          "optional group unmatched → :slug ABSENT, not {:slug nil}")
      (is (not (contains? (routing.match/match-against compiled "/articles/5") :slug))
          "the unmatched optional key is omitted entirely")
      (is (= {:id "5" :slug "intro"}
             (routing.match/match-against compiled "/articles/5/intro"))
          "optional group matched → :slug present with its captured value")))

  (testing "rf2-yejde — the nil-strip drops ONLY regex-unmatched (nil)
            captures; a legitimately matched capture survives. (Named param
            and splat regexes require a non-empty capture, so a captured
            value is always non-nil and is never dropped.)"
    (let [compiled (routing.match/parse-pattern "/u/:a{/:b}?")]
      ;; :a is always matched (non-nil capture); :b only when present.
      ;; Neither matched capture is ever stripped.
      (is (= {:a "x"}       (routing.match/match-against compiled "/u/x")))
      (is (= {:a "x" :b "y"} (routing.match/match-against compiled "/u/x/y"))))))

(deftest match-against-splat-captures-multi-segment-tail
  (testing "rf2-aleg9 — a `*rest` splat captures the entire trailing
            path (slashes preserved) into the params map"
    (let [compiled (routing.match/parse-pattern "/files/*path")]
      (is (= {:path "a"}
             (routing.match/match-against compiled "/files/a"))
          "single-segment splat captured under :path")
      (is (= {:path "a/b/c"}
             (routing.match/match-against compiled "/files/a/b/c"))
          "multi-segment splat captured with slashes preserved")
      (is (nil? (routing.match/match-against compiled "/files/"))
          "empty splat tail → nil (regex requires non-empty capture)")
      (is (nil? (routing.match/match-against compiled "/files"))
          "missing splat tail → nil"))))

;; ---- rf2-yjali: named splat out-ranks the bare catch-all ----------------
;;
;; Spec 012 §Route ranking algorithm rule 2: "The bare catch-all `/*` is
;; demoted below every other matching route." The catch-all is EXACTLY
;; the bare `/*` pattern (`is-catch-all? (= pattern "/*")` in the spec
;; pseudocode). A NAMED splat (`/*rest`) is a rest param, so it must
;; out-rank `/*`. Before the rf2-yjali fix `parse-pattern`'s classifier
;; flagged ANY single-splat-only pattern as catch-all, so `/*rest` tied
;; with `/*` at the catch-all rank element instead of beating it.
;; (rf2-1ugs5u lifted that catch-all element from index 3 to index 1 —
;; ahead of total-length — so the bare `/*` loses to the root `/` too.)

(deftest parse-pattern-named-splat-outranks-bare-catch-all
  (testing "rf2-yjali — only the bare `/*` is catch-all; a named splat
            `/*rest` ranks above it at the catch-all rank element
            (Spec 012 rule 2)"
    (let [catch-all (:rank (routing.match/parse-pattern "/*"))
          rest-splat (:rank (routing.match/parse-pattern "/*rest"))]
      ;; Rank element 1 (0-indexed) is the catch-all discriminator
      ;; (Spec 012 rule 2, lifted ahead of total-length by rf2-1ugs5u):
      ;; 0 = catch-all (less specific), 1 = not catch-all (more specific).
      (is (= 0 (nth catch-all 1))
          "bare `/*` is classified as the catch-all (rank elem 1 = 0)")
      (is (= 1 (nth rest-splat 1))
          "named `/*rest` is NOT the catch-all (rank elem 1 = 1)")
      ;; The two patterns are identical on every other rank element
      ;; (statics, length, splat, optional) — the catch-all discriminator
      ;; is the ONLY difference, and it must make `/*rest` win.
      (is (= (assoc catch-all 1 :x) (assoc rest-splat 1 :x))
          "the two ranks differ ONLY at the catch-all element")
      (is (pos? (compare rest-splat catch-all))
          "lexicographic compare: `/*rest` out-ranks `/*` (rule 2)"))))

(deftest match-url-named-splat-wins-over-bare-catch-all
  (testing "rf2-yjali — when both `/*rest` and `/*` are registered, a
            multi-segment URL resolves to the named-splat route, not the
            catch-all (Spec 012 §Route ranking rule 2)"
    (rf/reg-route :route/catch-all {} "/*")
    (rf/reg-route :route/rest      {} "/*rest")
    (let [m (routing/match-url "/some/deep/path")]
      (is (= :route/rest (:route-id m))
          "named-splat route wins the rule-4 tiebreak against bare catch-all")
      (is (= {:rest "some/deep/path"} (:params m))
          "the named splat captures the whole tail under :rest"))))

;; ---- rf2-1ugs5u: root `/` wins over the bare catch-all `/*` for URL "/" --
;;
;; Spec 012 §Route ranking algorithm rule 2: the bare catch-all `/*` is
;; demoted below every other matching route. The bug: `/*` ALSO matches
;; the root URL "/" (the unnamed splat captures the literal "/"), and a
;; home route `{:path "/"}` parses to total-length 0 while `/*` is
;; length 1. With total-length (rule 3) compared BEFORE the catch-all
;; demotion, `/*` out-lengthed the root and won for "/" — shadowing the
;; home route registration-order-independently. The fix lifts the
;; catch-all discriminator (rank elem 1) ahead of total-length (rank
;; elem 2) so the root (and every concrete route) wins over `/*`.

(deftest parse-pattern-root-outranks-bare-catch-all-rf2-1ugs5u
  (testing "rf2-1ugs5u — the root `/` out-ranks the bare catch-all `/*`
            even though `/*` is the longer pattern; the catch-all
            discriminator (rank elem 1) is consulted before total-length
            (rank elem 2)"
    (let [root      (:rank (routing.match/parse-pattern "/"))
          catch-all (:rank (routing.match/parse-pattern "/*"))]
      ;; rank elem 1 (0-indexed) is the catch-all discriminator:
      ;; root = 1 (not catch-all), `/*` = 0 (is catch-all).
      (is (= 1 (nth root 1))
          "root `/` is NOT the catch-all (rank elem 1 = 1)")
      (is (= 0 (nth catch-all 1))
          "bare `/*` IS the catch-all (rank elem 1 = 0)")
      ;; `/*` is the LONGER pattern (total-length 1 vs the root's 0) yet
      ;; STILL loses — proving the catch-all demotion precedes length.
      (is (= 0 (nth root 2))
          "root `/` has total-length 0 (parse loop never runs for `/`)")
      (is (= 1 (nth catch-all 2))
          "bare `/*` has total-length 1 (the lone splat segment)")
      (is (pos? (compare root catch-all))
          "lexicographic compare: root `/` out-ranks the catch-all `/*`
           DESPITE being shorter — the catch-all demotion wins first"))))

(deftest match-url-root-wins-over-catch-all-rf2-1ugs5u
  (testing "rf2-1ugs5u — match-url \"/\" returns the ROOT route, not the
            catch-all, when both `/` and `/*` are registered
            (registration-order-independent)"
    ;; catch-all registered FIRST so the bug (if present) can't hide
    ;; behind registration order — the rank cascade, not order, must win.
    (rf/reg-route :route/catch-all {} "/*")
    (rf/reg-route :route/home      {} "/")
    (let [m (routing/match-url "/")]
      (is (= :route/home (:route-id m))
          "the root route wins match-url \"/\" over the catch-all")
      (is (= {} (:params m))
          "the root match carries an empty params map (no splat capture)")))

  (testing "rf2-1ugs5u — the result is order-independent: home registered
            first ALSO resolves \"/\" to the home route"
    (rf/reg-route :route/home      {} "/")
    (rf/reg-route :route/catch-all {} "/*")
    (is (= :route/home (:route-id (routing/match-url "/")))
        "home wins regardless of registration order"))

  (testing "rf2-1ugs5u — the catch-all still wins a NON-root URL that the
            home route cannot match (the demotion only loses the root)"
    (rf/reg-route :route/home      {} "/")
    (rf/reg-route :route/catch-all {} "/*")
    (is (= :route/catch-all (:route-id (routing/match-url "/anything/deep")))
        "catch-all still catches URLs no concrete route matches")))

(deftest match-against-root-pattern-matches-root-path
  (testing "rf2-aleg9 — the special `/` pattern matches the root URL
            and returns an empty params map; a deeper URL returns nil"
    (let [compiled (routing.match/parse-pattern "/")]
      (is (= {} (routing.match/match-against compiled "/"))
          "root pattern matches root path → empty params map")
      (is (= {} (routing.match/match-against compiled ""))
          "root pattern also matches the empty string (leading `/?` in regex)")
      (is (nil? (routing.match/match-against compiled "/foo"))
          "root pattern does NOT match a deeper path"))))

(deftest match-against-no-match-returns-nil
  (testing "rf2-aleg9 — when re-matches misses, match-against returns
            nil cleanly (no throw, no exception)"
    (let [compiled (routing.match/parse-pattern "/users/:id/posts/:post-id")]
      (is (nil? (routing.match/match-against compiled "/unrelated/path"))
          "completely unrelated URL → nil")
      (is (nil? (routing.match/match-against compiled "/users/42/posts"))
          "URL missing trailing capture segment → nil")
      (is (= {:id "42" :post-id "9"}
             (routing.match/match-against
               compiled "/users/42/posts/9"))
          "the same pattern DOES match when both captures are present —
           sanity-check the test isn't accepting only the negative cases"))))

;; ---- rf2-45b95: reg-route authoring-boundary metadata validation ----------
;;
;; Spec 012 §Reserved route-metadata keys: reg-route has the largest
;; registration shape in the v2 surface (twelve reserved keys). A typo'd
;; key (:on-matched for :on-match) used to pass silently at registration
;; and fail later at nav-time, or never. The authoring-boundary guardrail
;; (rf2-45b95) rejects bare keys outside the reserved set LOUDLY at
;; registration, naming the bad key; namespaced host/app keys pass.

(deftest reg-route-rejects-unknown-bare-metadata-key
  (testing "rf2-45b95: a typo'd bare metadata key (:on-matched for
            :on-match) throws :rf.error/route-bad-metadata at
            registration, naming the bad key"
    (let [ex (try
               (rf/reg-route :route/typo {:on-matched [[:load]]} "/typo")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex)
          "reg-route THROWS on an unknown bare metadata key (no silent accept)")
      (is (= :rf.error/route-bad-metadata (:rf.error/id (ex-data ex)))
          "the canonical thrown-error id discriminates the failure")
      (is (= 'rf/reg-route (:where (ex-data ex)))
          ":where names the public surface fn")
      (is (= [:on-matched] (:keys (ex-data ex)))
          ":keys names exactly the offending key so the message is actionable")
      (is (clojure.string/includes? (:reason (ex-data ex)) ":on-matched")
          "the human-readable :reason names the bad key"))))

(deftest reg-route-accepts-valid-and-namespaced-metadata
  (testing "rf2-45b95: a route using only reserved keys + namespaced
            host/app keys registers fine (no false positives)"
    (is (= :route/ok
           (rf/reg-route :route/ok
                         {:doc            "fine"
                          :params         [:map [:id :string]]
                          :query          [:map [:q {:optional true} :string]]
                          :query-defaults {:q "x"}
                          :query-retain   #{:theme}
                          :tags           #{:public}
                          :on-match       [[:load]]
                          :on-error       [:oops]
                          :scroll         :top
                          ;; namespaced host/app extension keys always pass
                          :myapp/layout   :wide
                          :myapp/analytics-id "abc"} "/ok/:id"))
        "a route with every reserved key + namespaced extension keys registers")
    (is (some? (rf/handler-meta :route :route/ok))
        "the route is queryable via handler-meta after a clean registration")))

(deftest reg-route-rejects-non-map-metadata
  (testing "rf2-45b95: non-map metadata is rejected at the authoring
            boundary naming the route (no downstream NPE)"
    (let [ex (try (rf/reg-route :route/bad "/not-a-map" "/bad")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :rf.error/route-bad-metadata (:rf.error/id (ex-data ex)))
          "non-map metadata surfaces the same canonical error id"))))

(deftest reg-route-rejects-path-inside-metadata-map
  (testing "rf2-wvh95f F1 / rf2-2u6s4b: under the canonical 3-slot grammar the
            path pattern is the THIRD slot, so a `:path` LEFT INSIDE the
            metadata map is a mislocated key and MUST be rejected loudly with
            the structured `:rf.error/route-bad-metadata` — NOT silently
            accepted, downgraded to the generic unknown-bare-key path, or
            allowed to throw a raw exception. Pins registry.cljc's
            `(contains? metadata :path)` guard so a later refactor cannot
            silently degrade it. The 3rd-slot value is intentionally distinct
            from the metadata `:path` so the test proves the guard fires on the
            METADATA key, not on the value slot."
    (let [ex (try
               (rf/reg-route :route/bad {:path "/bad"} "/ignored")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex)
          "reg-route THROWS when :path is left inside the metadata map")
      (let [data (ex-data ex)]
        (is (= :rf.error/route-bad-metadata (:rf.error/id data))
            "the canonical structured error id (not the generic unknown-key path)")
        (is (= 'rf/reg-route (:where data))
            ":where names the public surface fn")
        (is (= :route/bad (:route-id data))
            ":route-id names the offending route")
        (is (= [:path] (:keys data))
            ":keys names exactly the mislocated key")
        (is (= "/bad" (:value data))
            ":value carries the misplaced path verbatim (the metadata :path, not the value slot)")))))
