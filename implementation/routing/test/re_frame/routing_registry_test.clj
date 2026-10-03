(ns re-frame.routing-registry-test
  "Registry / match / URL-construction tests for re-frame.routing
  (reg-route, match-url, route-url, the match/registry primitives, query
  coercion + the keyword-interning cap, optional groups, splats, pattern
  parsing, and metadata validation).

  ## Posture split

  The registry is production-real and almost all of this namespace carries no
  posture guard: `reg-route` validation and its fail-loud throws, `match-url`
  / `route-url` and their round trip, the ranking algorithm, optional groups,
  splats, query coercion and the keyword-interning cap all run in the ordinary
  `clojure -M:test` suite AND in `scripts/test-routing-prod-gate.sh` (the
  `-Dre-frame.debug=false` lane).

  Four deftests also observe registry facts through the DEV TRACE: the
  `:rf.route/registered` and `:rf.route/cleared` lifecycle ops and, in two of
  them, the `:rf.warning/route-shadowed-by-equal-score` advisory. All
  three emit through `trace/emit!`, gated on `rf.interop/debug-enabled?` and read
  once at load time. Their trace assertions sit inside
  `(when rf.interop/debug-enabled? …)` arms.

  Three of the shadow-advisory blocks are NEGATIVE (`(is (= [] warns))` for the
  every-app static case, the disjoint param-prefix pair and the registration
  benchmark) and would pass vacuously under the gate. Outside the arm each of
  the four asserts the REGISTRY fact the trace reports on — read off
  `rf/handler-meta` and `match-url`, which are always-on. In particular the
  shadow advisory's whole
  claim is about which route wins at match time, so `match-url` is the natural
  witness: `/a/7` really does resolve to the earlier registration and really
  does carry ITS capture name, and the three disjoint static routes really are
  all reachable."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [clojure.string :as string]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.identity :as rf.identity]
            [re-frame.interop :as rf.interop]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rf.routing-test-support]
            [re-frame.routing.match :as rf.routing.match]
            [re-frame.routing.registry :as rf.routing.registry]
            [re-frame.source-store :as rf.source-store]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

;; ---- route-url missing-required-param raises clear error -----------------
;;
;; `route-url` must not silently emit a malformed URL when a required
;; path param is absent.

(deftest route-url-missing-required-path-param-throws
  (testing "route-url with a missing required :id path param raises
            :rf.error/missing-route-param"
    (rf/reg-route :route/article {} "/articles/:id")
    ;; No :id supplied — must throw the structured error.
    (let [ex (try
               (rf.routing/route-url {:to :route/article :params {}})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "route-url with absent required param raises")
      ;; Anchor on the canonical :rf.error/id discriminator
      ;; (the message is a human sentence + trailing token).
      (is (= :rf.error/missing-route-param (:rf.error/id (ex-data ex)))
          "the structured error id is :rf.error/missing-route-param")
      (let [data (ex-data ex)]
        (is (= :id (:param data))
            "ex-data names the absent param")
        (is (= :route/article (:route-id data))
            "ex-data names the route-id"))))

  (testing "splat params raise the same structured error when absent"
    (rf/reg-route :route/files {} "/files/*path")
    (let [ex (try
               (rf.routing/route-url {:to :route/files :params {}})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "absent splat raises")
      ;; Anchor on the canonical :rf.error/id discriminator.
      (is (= :rf.error/missing-route-param (:rf.error/id (ex-data ex)))
          "splat absence uses the same structured error id"))))

(deftest route-url-no-such-route-throws
  (testing "route-url against an unregistered route id raises
            :rf.error/no-such-route"
    (let [ex (try
               (rf.routing/route-url {:to :route/no-such-route :params {}})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      ;; Anchor on the canonical :rf.error/id discriminator.
      (is (= :rf.error/no-such-route (:rf.error/id (ex-data ex)))
          ":rf.error/no-such-route is the structured error for an unregistered id"))))

;; ---- route-url fails closed on host-stringified values -------------------
;;
;; EP-0012 §Canonical EDN identity (docs/EP/EP-0012 §893-896; Conventions
;; §584-592): "If a route param value cannot be represented as canonical EDN
;; after schema coercion, route matching or URL printing MUST fail closed at
;; the relevant boundary. It MUST NOT use host `str`, JS object stringification,
;; or object identity to invent a cache or route identity."
;;
;; The query KEY side is CEDN-guarded (the canonical-order sort runs each key
;; through `rf.identity/canonical-bytes`); path-param values and query VALUES
;; need the same guard, because `url/url-encode`'s host `(str v)` would
;; host-stringify a function / atom / arbitrary host object / non-portable
;; number into a fabricated URL identity, and a host `Date` / instant into a
;; HOST-DIVERGENT, un-round-trippable segment. `route-url` fails closed with
;; `:rf.error/route-url-non-edn-value` BEFORE any URL string is returned.

(defn- route-url-throws-non-edn?
  "Call `route-url` and return the thrown ExceptionInfo (or nil). A helper
  so each adversarial value reads as one assertion."
  [route-id path-params query-params]
  (try
    (rf.routing/route-url {:to route-id :params path-params :query query-params})
    nil
    (catch clojure.lang.ExceptionInfo e e)))

(deftest route-url-optional-group-host-value-fails-closed-rf2-94o54l
  (testing "a host value in an OPTIONAL-GROUP inner path param also fails
            closed (the group is entered because the param is present)"
    (rf/reg-route :route/doc {} "/docs{/:section}?")
    (let [ex (route-url-throws-non-edn? :route/doc {:section (fn [_])} {})]
      (is (some? ex) "a fn in an entered optional group fails closed")
      (is (= :rf.error/route-url-non-edn-value (:rf.error/id (ex-data ex)))
          "structured :rf.error/id (primary)")
      ;; Assert the [:rf.error/<id>] token, not exact equality.
      (is (re-find #"\[:rf\.error/route-url-non-edn-value\]" (ex-message ex)) "message token (secondary)")
      (is (= :section (:param (ex-data ex)))))))

;; ---- match-url malformed-input edge cases --------------------------------
;;
;; Edge cases for the URL parser.

(deftest match-url-empty-string
  (testing "match-url \"\" matches the root '/' route (the compiled regex
            is `^/?$`, so the leading slash is optional)"
    (rf/reg-route :route/home {} "/")
    ;; Pin the actual behaviour: the compiled regex treats the leading
    ;; slash as optional, so both "" and "/" match the root.
    (let [m (rf.routing/match-url "")]
      (is (some? m)
          "empty string matches the root '/' route (leading slash optional)")
      (is (= :route/home (:route-id m))))))

(deftest match-url-missing-leading-slash
  (testing "URLs without a leading slash STILL match the corresponding
            route (the compiled regex is `^/?...`, leading slash optional).
            Documenting the actual lenient behaviour."
    (rf/reg-route :route/home {} "/home")
    (is (some? (rf.routing/match-url "/home"))
        "the canonical /home matches")
    (let [m (rf.routing/match-url "home")]
      (is (some? m)
          "no-leading-slash also matches — the compiled regex permits it")
      (is (= :route/home (:route-id m))))))

(deftest match-url-repeated-query-key-last-wins
  (testing "repeated query keys — last value wins (the parser's array-map
            reduce assoc's left-to-right, so a later key replaces an
            earlier one). The route declares no :query
            vocabulary, so the key stays a string."
    (rf/reg-route :route/search {} "/search")
    (let [m (rf.routing/match-url "/search?x=1&x=2")]
      (is (some? m) "the route matches")
      (is (= "2" (get-in m [:query "x"]))
          "repeated key — last value wins (left-to-right reduce)"))))

;; ---- match-url :query is in CEDN-1 canonical KEY order -------------------
;;
;; The inbound URL's query string
;; carries keys in whatever left-to-right order the author of THAT URL chose,
;; but the route slice's `:query` is route DATA — an identity fact the
;; `:rf.route/query` sub, no-op detection, and SSR-hydration parity key off.
;; Two inbound URLs spelling the same query in different key orders MUST yield
;; the SAME `:query` identity (and the same key ORDER), so match-url reorders
;; the surviving entries into CEDN-1 canonical key order. This is the inbound
;; mirror of route-url's canonical query emission: per
;; Conventions §Routes are prisms, both prism legs share ONE canonical order.

(defn- canonical-key-order
  "The CEDN-1 canonical order of `ks` (the order match-url's :query must
  use), computed via the SAME shared identity rule the implementation sorts
  by — re-frame.identity/canonical-bytes."
  [ks]
  (vec (sort-by rf.identity/canonical-bytes ks)))

(deftest match-url-query-is-canonical-key-order
  (testing "match-url :query keys are emitted in CEDN-1 canonical order,
            independent of the inbound URL's key order. Declared :query
            vocabulary keys are promoted to keywords and ordered canonically."
    (rf/reg-route :route/search {:query [:map
                                         [:b {:optional true} :string]
                                         [:a {:optional true} :string]
                                         [:c {:optional true} :string]]} "/search")
    (let [m1 (rf.routing/match-url "/search?b=2&a=1&c=3")
          m2 (rf.routing/match-url "/search?c=3&b=2&a=1")
          expected-order (canonical-key-order [:a :b :c])]
      (is (= (:query m1) (:query m2))
          "the same query spelled in two inbound key orders yields = :query")
      (is (= expected-order (vec (keys (:query m1))))
          "m1 :query keys are in CEDN-1 canonical order, not inbound URL order")
      (is (= expected-order (vec (keys (:query m2))))
          "m2 :query keys are in CEDN-1 canonical order regardless of spelling")
      (is (= {:a "1" :b "2" :c "3"} (:query m1))
          "membership + values are unchanged — only key ORDER is canonicalised"))))

(deftest match-url-query-defaults-participate-in-canonical-order
  (testing ":query-defaults-populated keys are interleaved into the SAME
            canonical key order as parsed keys, not appended after them"
    (rf/reg-route :route/listing {:query          [:map
                                                   [:sort {:optional true} :string]
                                                   [:page {:optional true} :int]]
                                  :query-defaults {:page 1}} "/listing")
    ;; `:sort` arrives from the URL; `:page` is filled from defaults. Both
    ;; must sit in canonical key order in the final :query.
    (let [m (rf.routing/match-url "/listing?sort=name")
          expected-order (canonical-key-order [:page :sort])]
      (is (= expected-order (vec (keys (:query m))))
          "default-filled :page and parsed :sort share one canonical order")
      (is (= {:page 1 :sort "name"} (:query m))
          "default applied; values intact"))))

(deftest match-url-trailing-slash-normalizes
  (testing "trailing-slash equivalence is implicit — /foo and /foo/
            resolve to the same route per Spec 012"
    (rf/reg-route :route/foo {} "/foo")
    (is (some? (rf.routing/match-url "/foo"))
        "the canonical /foo matches")
    (let [canonical (rf.routing/match-url "/foo")
          trailing  (rf.routing/match-url "/foo/")]
      (is (= (:route-id canonical) (:route-id trailing))
          "/foo/ resolves to the same route-id as /foo")
      (is (= (:params canonical) (:params trailing))
          "/foo/ carries the same path params as /foo"))))

;; ---- malformed percent-encoding fails closed -----------------------------
;;
;; Per Spec 012 §Routing failure semantics. The percent decoders
;; (`decode-percent-escapes` on the JVM, `decodeURIComponent` on CLJS) throw
;; on malformed `%` sequences. Hostile
;; URLs, partner integrations with broken escaping, and back-button to a
;; malformed link must produce a route-miss (404 path), never a request-
;; handler crash.
;;
;; Contract: uniform fail-closed across path / query /
;; fragment — `match-url` returns nil regardless of which portion is
;; malformed. The runtime refuses any URL whose %-encoding cannot be
;; uniformly decoded.
;;
;; For example `(rf.routing/match-url "/search?x=%")`
;; resolves to nil (route-miss → `:rf.route/not-found` with
;; `:reason :malformed-url` at `:rf.route/handle-url-change`).

(deftest match-url-malformed-percent-in-path-is-route-miss
  (testing "a bare `%` in the path returns nil (route-miss), does not throw"
    (rf/reg-route :route/articles {} "/articles/:slug")
    (is (nil? (rf.routing/match-url "/articles/%"))
        "/articles/% is a route-miss, not an exception")
    (is (nil? (rf.routing/match-url "/articles/x%a"))
        "/articles/x%a (incomplete pair) is a route-miss")
    (is (nil? (rf.routing/match-url "/articles/x%XX"))
        "/articles/x%XX (non-hex pair) is a route-miss"))
  (testing "bare-`%` URL with no path-pattern match also returns nil"
    ;; No route registered; even a malformed URL must not throw.
    (is (nil? (rf.routing/match-url "/%"))
        "/% with no matching route is a route-miss, not an exception")))

(deftest match-url-malformed-percent-in-query-fails-closed
  (testing "malformed %-encoding in a query VALUE fails closed —
            the WHOLE URL is a route-miss, not just the bad pair"
    (rf/reg-route :route/search {} "/search")
    (is (nil? (rf.routing/match-url "/search?x=%"))
        "single-pair malformed query → route-miss, no partial slice")
    (is (nil? (rf.routing/match-url "/search?good=1&bad=%&also=2"))
        "good neighbours do NOT keep the URL routable when one pair is malformed"))
  (testing "malformed %-encoding in a query KEY fails closed"
    (rf/reg-route :route/search2 {} "/search2")
    (is (nil? (rf.routing/match-url "/search2?%=v"))
        "malformed key → route-miss, not a dropped pair")
    (is (nil? (rf.routing/match-url "/search2?ok=1&%=bad&also=2"))
        "bad-key with good neighbours still fails the whole URL")))

(deftest match-url-malformed-percent-in-fragment-fails-closed
  (testing "malformed %-encoding in the `#fragment` portion
            fails closed — `match-url` returns nil"
    (rf/reg-route :route/page {} "/page")
    (is (nil? (rf.routing/match-url "/page#%"))
        "bare `%` in fragment → route-miss")
    (is (nil? (rf.routing/match-url "/page#good%a"))
        "incomplete %-pair in fragment → route-miss"))
  (testing "well-formed and empty fragments are unaffected"
    (rf/reg-route :route/page2 {} "/page2")
    (let [m (rf.routing/match-url "/page2#section-1")]
      (is (some? m) "well-formed fragment matches")
      (is (= "section-1" (:fragment m))
          "well-formed fragment surfaces decoded into the slice"))
    (let [m (rf.routing/match-url "/page2#hello%20world")]
      (is (some? m) "well-formed %-encoded fragment matches")
      (is (= "hello world" (:fragment m))
          "well-formed %-encoded fragment is decoded into the slice"))
    (let [m (rf.routing/match-url "/page2#")]
      (is (some? m) "bare-trailing-`#` URL matches")
      (is (= "" (:fragment m)) "bare `#` decodes to empty string"))))

(deftest match-url-no-rank-in-result
  (testing "match-url result does NOT carry the internal :rank key"
    (rf/reg-route :route/home {} "/")
    (let [m (rf.routing/match-url "/")]
      (is (some? m))
      (is (not (contains? m :rank))
          ":rank is internal routing-table state; not part of the
          documented match-url result shape"))))

;; ---- route-url query-string emission -------------------------------------
;;
;; Per Spec 012 §Bidirectional URL ↔ params. The qs builder
;; (registry.cljc) joins `(name k)=url-encode(v)` pairs with `&`.
;; A single-pair query can't observe pair ORDERING or query-VALUE
;; percent-encoding. These are the two behaviours
;; the multi-pair `&`-join + per-value `url-encode` exist for.
;;
;; Query keys are emitted
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
           (rf.routing/route-url {:to :route/list :params {} :query (array-map :a "1" :b "2")}))
        "two query pairs join with `&` in canonical key order")
    (is (= "/list?a=1&b=2"
           (rf.routing/route-url {:to :route/list :params {} :query (array-map :b "2" :a "1")}))
        "the SAME URL regardless of caller insertion order (a before b)")
    (is (= "/list?x=1&y=2&z=3"
           (rf.routing/route-url {:to :route/list :params {} :query (array-map :x "1" :y "2" :z "3")}))
        "three query pairs join with `&` in canonical key order")
    (is (= "/list?x=1&y=2&z=3"
           (rf.routing/route-url {:to :route/list :params {} :query (array-map :z "3" :x "1" :y "2")}))
        "three pairs: canonical order is construction-order independent")
    (is (= (rf.routing/route-url {:to :route/list :params {} :query (array-map :z "3" :y "2" :x "1")})
           (rf.routing/route-url {:to :route/list :params {} :query (array-map :x "1" :y "2" :z "3")}))
        "any two permutations of one query map build the byte-identical URL"))

  (testing "query VALUES are percent-encoded (encodeURIComponent semantics)"
    (rf/reg-route :route/search {} "/search")
    (is (= "/search?q=x%20y"
           (rf.routing/route-url {:to :route/search :params {} :query {:q "x y"}}))
        "a space in a query value encodes to %20 (not '+')")
    (is (= "/search?a=1&b=x%20y"
           (rf.routing/route-url {:to :route/search :params {} :query (array-map :a "1" :b "x y")}))
        "multi-pair ordering AND value %-encoding together")
    (is (= "/search?filter=a%26b%3Dc"
           (rf.routing/route-url {:to :route/search :params {} :query {:filter "a&b=c"}}))
        "`&` and `=` in a value are encoded so they cannot inject extra pairs"))

  (testing "query KEYS are percent-encoded too"
    (rf/reg-route :route/k {} "/k")
    (is (= "/k?a%20b=v"
           (rf.routing/route-url {:to :route/k :params {} :query {(keyword "a b") "v"}}))
        "a space in a query key encodes to %20")))

(deftest route-url-drops-nil-query-values-keeps-falsy
  (testing "a nil-valued query key is ELIDED from the URL
            (not emitted as a bare `?key=`), while present-but-falsy
            values (false / 0 / \"\") round-trip"
    (rf/reg-route :route/list {} "/list")
    (is (= "/list"
           (rf.routing/route-url {:to :route/list :params {} :query {:page nil}}))
        "a sole nil-valued query key is dropped → no query string at all")
    (is (= "/list?a=1"
           (rf.routing/route-url {:to :route/list :params {} :query (array-map :a "1" :b nil)}))
        "nil-valued keys are dropped; the rest of the query survives")
    (is (= "/list?flag=false"
           (rf.routing/route-url {:to :route/list :params {} :query {:flag false}}))
        "present-but-falsy `false` is a legitimate value and round-trips")
    (is (= "/list?n=0"
           (rf.routing/route-url {:to :route/list :params {} :query {:n 0}}))
        "`0` round-trips (falsy, not absent)")
    (is (= "/list?s="
           (rf.routing/route-url {:to :route/list :params {} :query {:s ""}}))
        "empty-string is a present value → `?s=` (distinct from nil/absent)")))

;; ---- match-url :fragment + route-url 4-arity round-trip ------------------
;;
;; Per Spec 012 §Bidirectional URL ↔ params and §Fragments §Programmatic
;; navigation with fragments. match-url surfaces the URL's `#fragment`
;; portion on its result map; route-url's 4-arity rebuilds the URL with
;; the fragment appended. The two are inverses: a URL parsed with
;; match-url and rebuilt with route-url's 4-arity recovers the original
;; (modulo route-id resolution).

(deftest match-url-route-url-round-trip-with-fragment
  (testing "URL → match-url → route-url 4-arity → URL recovers the original
            (the full bidirectional contract including #fragment).
            Unknown query keys stay as strings; route-url accepts both
            keyword + string keys via `(name k)` so the round-trip holds."
    (rf/reg-route :route/docs {} "/docs/:page")
    (let [original "/docs/routing?lang=en#scroll-restoration"
          parsed   (rf.routing/match-url original)
          rebuilt  (rf.routing/route-url {:to (:route-id parsed) :params (:params parsed) :query (:query parsed) :fragment (:fragment parsed)})]
      (is (= :route/docs (:route-id parsed)))
      (is (= {:page "routing"} (:params parsed)))
      (is (= {"lang" "en"}     (:query parsed)))
      (is (= "scroll-restoration" (:fragment parsed)))
      (is (= original rebuilt)
          "the rebuilt URL equals the original — fragment round-trips"))))

(deftest match-url-flags-validation-failure
  (testing "match-url surfaces :validation-failed? + :validation-error
            when the route declares :params and the parsed value rejects"
    (let [restore (rf.routing-test-support/with-stub-validator)]
      (try
        ;; A schema that requires :id to be a non-empty string starting "a".
        (rf/reg-route :route/article
                      {:params (fn [{:keys [id]}] (clojure.string/starts-with? (or id "") "a"))} "/articles/:id")
        (let [m (rf.routing/match-url "/articles/zoo")]
          (is (some? m) "the route still matches structurally")
          (is (true? (:validation-failed? m))
              ":validation-failed? flips when the schema rejects")
          (is (some? (:validation-error m))
              ":validation-error carries the explainer payload"))
        (let [m2 (rf.routing/match-url "/articles/aardvark")]
          (is (false? (:validation-failed? m2))
              "a conforming value clears the flag")
          (is (nil? (:validation-error m2))
              "no error key when conformant"))
        (finally (restore))))))

(deftest route-url-throws-on-invalid-path-params
  (testing "route-url throws :rf.error/route-url-validation when
            path-params don't conform to the route's :params schema"
    (let [restore (rf.routing-test-support/with-stub-validator)]
      (try
        (rf/reg-route :route/article
                      {:params (fn [{:keys [id]}] (clojure.string/starts-with? (or id "") "a"))} "/articles/:id")
        (let [ex (try (rf.routing/route-url {:to :route/article :params {:id "zoo"}})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex)
              "non-conformant path-params raise")
          ;; The message is a human sentence + the trailing
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
               (rf.routing/route-url {:to :route/article :params {:id "aardvark"}}))
            "conformant params still produce a URL")
        (finally (restore))))))

(deftest route-url-throws-on-invalid-query-params
  (testing "route-url throws :rf.error/route-url-validation when
            query-params don't conform to the route's :query schema"
    (let [restore (rf.routing-test-support/with-stub-validator)]
      (try
        (rf/reg-route :route/search
                      {:query (fn [m] (and (string? (:q m))
                                           (pos? (count (:q m)))))} "/search")
        (let [ex (try (rf.routing/route-url {:to :route/search :params {} :query {:q ""}})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex)
              "empty :q rejects against the query schema")
          ;; Anchor on :rf.error/id; assert the token, not equality.
          (is (= :rf.error/route-url-validation (:rf.error/id (ex-data ex))))
          (is (re-find #"\[:rf\.error/route-url-validation\]" (ex-message ex)))
          (is (= :query (:slot (ex-data ex)))))
        (let [ex (try (rf.routing/route-url {:to :route/search :params {} :query {}})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex)
              "an empty query map still validates, so required query params reject")
          (is (= :query (:slot (ex-data ex)))))
        (finally (restore))))))

(deftest route-url-elides-nil-query-before-validation-rf2-w3qgc
  (testing "nil-valued query keys are elided BEFORE :query-schema
            validation, so `{:sort nil}` against a
            `:query [:map [:sort {:optional true} :string]]` route returns
            `/search` (the key is omitted) rather than throwing
            :rf.error/route-url-validation. Non-nil invalid values STILL fail."
    (let [restore (rf.routing-test-support/with-stub-validator)]
      (try
        ;; Predicate modelling an OPTIONAL :sort restricted to "name" / "date":
        ;; absent is OK, but when PRESENT it must be one of the two. Because nil
        ;; is elided before validation, `{:sort nil}` reaches the predicate as
        ;; `{}` (key absent) and conforms.
        ;;
        ;; A PREDICATE schema declares no query vocabulary (only
        ;; a `[:map …]` vector does), so `:sort` is an undeclared key and reaches
        ;; the predicate spelled the way the URL spells it — the string key
        ;; "sort" carrying a string value.
        (rf/reg-route :route/search
                      {:query (fn [m] (or (not (contains? m "sort"))
                                          (contains? #{"name" "date"} (get m "sort"))))}
                      "/search")
        ;; (1) route-url: nil omits the key, no throw, returns /search.
        (is (= "/search"
               (rf.routing/route-url {:to :route/search :params {} :query {:sort nil}}))
            "route-url with {:sort nil} elides the key BEFORE validation → /search")
        ;; A valid string still emits the pair.
        (is (= "/search?sort=name"
               (rf.routing/route-url {:to :route/search :params {} :query {:sort "name"}}))
            "a present, valid :sort string still round-trips into the query")
        ;; (2) A non-nil INVALID value STILL fails validation (the elision
        ;; covers only nil; it does not weaken the schema gate).
        (let [ex (try (rf.routing/route-url {:to :route/search :params {} :query {:sort 123}})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex)
              "a non-nil invalid :sort (a number outside the allowlist) STILL fails validation")
          ;; Anchor on :rf.error/id; assert the token, not equality.
          (is (= :rf.error/route-url-validation (:rf.error/id (ex-data ex))))
          (is (re-find #"\[:rf\.error/route-url-validation\]" (ex-message ex)))
          (is (= :query (:slot (ex-data ex))))
          (is (= {"sort" "123"} (:value (ex-data ex)))
              ":value reports the elided map actually validated (nil-free)"))
        (finally (restore)))))

  (testing "programmatic `:rf.route/navigate` with `{:sort nil}` pushes
            `/search` without a validation error (the same nil-elision
            path through route-url)"
    (let [restore (rf.routing-test-support/with-stub-validator)]
      (try
        (rf/reg-route :route/search
                      {:query (fn [m] (or (not (contains? m :sort))
                                          (string? (:sort m))))} "/search")
        (let [pushed (atom nil)]
          (rf.fx/reg-fx :rf.nav/push-url
                     {:platforms #{:server :client}}
                     (fn [_ url] (reset! pushed url)))
          ;; Programmatic navigate with a nil optional query value: the
          ;; navigate handler resolves the target URL via route-url, which
          ;; must elide :sort and emit /search (no validation throw).
          (rf/dispatch-sync [:rf.route/navigate {:to :route/search :query {:sort nil}}])
          (is (= "/search" @pushed)
              "programmatic navigate with {:sort nil} pushes /search (no throw)"))
        (finally (restore))))))

;; ============================================================================
;; Pattern grammar, shadowing, coercion, defaults, optional groups and splats
;; ============================================================================

(deftest invalid-route-patterns-fail-at-registration
  ;; One fixture per `match/validate-route-pattern!` rejection
  ;; branch (match.cljc:151-253 + its helpers). The validator is the fail-loud
  ;; reg-route authoring-boundary grammar gate; Spec 012 relies on it to stop a
  ;; malformed `:path` producing surprising matcher / URL-emitter behaviour
  ;; later. Each fixture drives the REAL boundary (`rf/reg-route`) with the
  ;; malformed pattern class the branch is meant to reject and asserts the
  ;; canonical thrown-error shape PLUS the specific `:reason` naming the
  ;; offending construct — so a single-pass-loop refactor that drops a check or
  ;; emits the wrong `:reason` fails here.
  (testing "grammar-violating :path patterns raise :rf.error/invalid-route-pattern
            at reg-route, each naming the offending construct via :reason"
    (doseq [[route-id pattern reason-substr]
            [;; ---- top-level shape (match.cljc:174-184) ----
             [:route/empty-path               ""                  ":path must not be empty"]
             [:route/no-leading-slash         "cart"              ":path must start with"]
             ;; ---- empty path segments (match.cljc:201-203). A TRAILING `/` is
             ;; canonicalised away before validation, so the empty-segment branch
             ;; is reached via an INTERIOR double slash, not a trailing one.
             [:route/interior-double-slash    "/x//y"             "empty path segments are not allowed"]
             ;; ---- reserved `}` / `?` in the main loop (match.cljc:209-213) ----
             [:route/bare-close-brace         "/a}b"              "`}` appears without a matching optional-group opener"]
             [:route/bare-question            "/a?b"              "`?` is reserved for the optional-group suffix"]
             ;; ---- params / splats not occupying a whole segment ----
             [:route/param-mid-segment        "/a:id"             "named params must occupy a whole path segment"]
             [:route/splat-mid-segment        "/a*rest"           "splats must occupy a whole path segment"]
             ;; ---- bad param / splat identifier names (route-name-re) ----
             [:route/bad-param-name           "/:1bad"            "param name must be a bare identifier"]
             [:route/bad-splat-name           "/*1bad"            "splat name must be a bare identifier"]
             ;; ---- splat not final ----
             [:route/splat-not-final          "/files/*rest/more" "splats must be the final path segment"]
             ;; ---- optional-group grammar (validate-optional-group!,
             ;;      match.cljc:98-149) ----
             [:route/group-unclosed           "/a{/:b"            "optional groups must close with `}?`"]
             [:route/group-no-suffix          "/a{/:b}"           "optional groups must end with `}?`"]
             [:route/group-empty              "/a{}?"             "optional groups must not be empty"]
             [:route/group-empty-segment      "/a{//}?"           "optional groups may not contain empty segments"]
             [:route/group-not-slash-prefixed "/articles{:id}?"   "optional groups must wrap a slash-prefixed sub-pattern"]
             [:route/group-nested             "/a{/:b{/:c}?}?"    "nested optional groups are not part of the grammar"]
             [:route/group-splat              "/a{/*rest}?"       "splats are not allowed inside optional groups"]
             [:route/group-bad-param-name     "/a{/:1bad}?"       "param name must be a bare identifier"]
             [:route/group-reserved-literal   "/shop{/a:b}?"      "literal path segments must percent-encode reserved characters"]]]
      (let [ex (try
                 (rf/reg-route route-id {} pattern)
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) (str (pr-str pattern) " should be rejected at reg-route"))
        (when ex
          (let [data (ex-data ex)]
            (is (= :rf.error/invalid-route-pattern (:rf.error/id data))
                (str (pr-str pattern) " → :rf.error/invalid-route-pattern"))
            (is (= route-id (:route-id data))
                (str (pr-str pattern) " → :route-id slot names the route"))
            (is (= pattern (:pattern data))
                (str (pr-str pattern) " → :pattern slot echoes the offending pattern"))
            (is (integer? (:index data))
                (str (pr-str pattern) " → :index slot names the offending position"))
            (is (and (string? (:reason data))
                     (string/includes? (:reason data) reason-substr))
                (str (pr-str pattern) " → :reason must name the offending construct "
                     (pr-str reason-substr) " (was " (pr-str (:reason data)) ")")))))))

  (testing "a non-string :path is the one rejection branch with NO positional
            :index — a type error, not a position in the string
            (match.cljc:171-172)"
    (let [ex (try
               (rf/reg-route :route/non-string-path {} 42)
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "a non-string :path is rejected at reg-route")
      (when ex
        (let [data (ex-data ex)]
          (is (= :rf.error/invalid-route-pattern (:rf.error/id data)))
          (is (= :route/non-string-path (:route-id data)))
          (is (= 42 (:pattern data)) ":pattern echoes the non-string value")
          (is (nil? (:index data))
              "a type error carries no positional :index (the pattern is not a string)")
          (is (and (string? (:reason data))
                   (string/includes? (:reason data) ":path is required and must be a string"))
              ":reason names the type violation")))))

  (testing "trailing slashes in registered patterns are canonicalized away"
    (rf/reg-route :route/cart {} "/cart/")
    (is (= "/cart" (:path (rf/handler-meta {:source :store :kind :route :id :route/cart}))))
    (is (= "/cart" (rf.routing/route-url {:to :route/cart :params {}})))))

;; ---- :rf.warning/route-shadowed-by-equal-score warning -------------------
;; The warning fires iff the rules-1-5 structural rank ties
;; AND the two patterns are co-matchable (some URL matches both — Spec 012
;; §Route ranking algorithm rule 6's "same URL family"). Equal rank alone
;; is NOT a conflict: rank tuples ignore literal text, so every same-shape
;; pair ties regardless of literals; a warning on the bare tie floods every
;; ordinary multi-page app.

(defn- shadow-warnings
  "Run `f` under a trace listener; return the captured
  `:rf.warning/route-shadowed-by-equal-score` events."
  [f]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::shadow (fn [ev] (swap! traces conj ev)))
    (try (f)
         (finally (rf/unregister-listener! :trace ::shadow)))
    (filterv #(= :rf.warning/route-shadowed-by-equal-score (:operation %))
             @traces)))

(deftest route-shadowed-by-equal-score-warning
  (testing "a TRUE rule-6 conflict — identical literals, different param
            names (/a/:x vs /a/:y) — warns with the reconciled payload:
            earlier registration wins the match-time tiebreak, so the NEW
            route is the shadowed one (:route-id), :shadowed-by names the
            existing winner, :rank carries the tied structural tuple"
    (let [warns (shadow-warnings
                  (fn []
                    (rf/reg-route :route/a {} "/a/:x")
                    (rf/reg-route :route/b {} "/a/:y")))]
      ;; SEMANTIC, posture-independent: the SHADOWING the warning
      ;; describes is real match-time behaviour. `/a/7` matches both patterns
      ;; structurally; the earlier registration wins the rule-6 tiebreak, so
      ;; :route/b really is unreachable for this URL family. That is the fact
      ;; the diagnostic reports, and it survives -Dre-frame.debug=false.
      (is (= :route/a (:route-id (rf.routing/match-url "/a/7")))
          "the earlier registration wins the rule-6 tiebreak at match time")
      (is (= {:x "7"} (:params (rf.routing/match-url "/a/7")))
          "…so the capture name is :x (:route/a's), not :y — :route/b is shadowed")
      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= 1 (count warns))
            "exactly one warning for the conflicting pair")
        (let [t (:tags (first warns))]
          (is (= :route/b (:route-id t))
              ":route-id names the NEW route — the shadowed one")
          (is (= :route/a (:shadowed-by t))
              ":shadowed-by names the existing winner (earlier registration
              wins the rule-6 tiebreak)")
          (is (= [1 1 2 1 1] (:rank t))
              ":rank carries the tied rules-1-5 structural tuple")))))

  (testing "equal rank alone does NOT warn — distinct single-segment static
            routes (the every-app case) register with ZERO shadow warnings"
    ;; /home, /about, /contact all rank [1 1 1 1 1] but never co-match.
    (let [warns (shadow-warnings
                  (fn []
                    (rf/reg-route :route/home    {} "/home")
                    (rf/reg-route :route/about   {} "/about")
                    (rf/reg-route :route/contact {} "/contact")))]
      ;; SEMANTIC, posture-independent: there is nothing to warn
      ;; ABOUT — all three routes are reachable, so no URL co-matches a pair.
      ;; Without this the `(= [] warns)` leg is vacuous under the gate.
      (is (= [:route/home :route/about :route/contact]
             (mapv #(:route-id (rf.routing/match-url %)) ["/home" "/about" "/contact"]))
          "every one of the three is reachable — the families are disjoint")
      ;; Dev-instrumentation arm (see ns docstring); NEGATIVE over
      ;; the trace ring, hence guarded.
      (when rf.interop/debug-enabled?
        (is (= [] warns)
            "N distinct static routes emit zero spurious shadow warnings"))))

  (testing "equal rank alone does NOT warn — distinct-literal param-prefix
            pair (/x/:id vs /y/:slug, same rank but no shared URL)
            registers with ZERO shadow warnings"
    (let [warns (shadow-warnings
                  (fn []
                    (rf/reg-route :route/x {} "/x/:id")
                    (rf/reg-route :route/y {} "/y/:slug")))]
      ;; SEMANTIC, posture-independent: both are reachable under
      ;; their OWN capture names — the pair
      ;; genuinely does not shadow. Without this the
      ;; `(= [] warns)` leg is vacuous under the gate.
      (is (= {:id "7"} (:params (rf.routing/match-url "/x/7")))
          "/x/:id keeps its own capture name")
      (is (= {:slug "7"} (:params (rf.routing/match-url "/y/7")))
          "/y/:slug keeps its own — neither shadows the other")
      ;; Dev-instrumentation arm (see ns docstring); NEGATIVE over
      ;; the trace ring, hence guarded.
      (when rf.interop/debug-enabled?
        (is (= [] warns)
            "same-shape routes on disjoint URL families never warn"))))

  (testing "several co-matchable equal-rank ties name the route that
            actually wins at match time — the earliest-registered"
    ;; A fresh /m/* URL family: the /a/* routes from the first testing
    ;; block are still registered (the reset fixture is per-deftest) and
    ;; tie these on rank, but a distinct literal prefix keeps the families
    ;; disjoint, so only the intra-family conflicts warn.
    (let [warns (shadow-warnings
                  (fn []
                    (rf/reg-route :route/first  {} "/m/:x")
                    (rf/reg-route :route/second {} "/m/:y")
                    (rf/reg-route :route/third  {} "/m/:z")))]
      ;; SEMANTIC, posture-independent: the warning's claim about
      ;; WHICH route wins is a claim about `match-url`, so check it there.
      (is (= :route/first (:route-id (rf.routing/match-url "/m/7")))
          "the earliest-registered intersecting tie is the match-time winner")
      (is (= {:x "7"} (:params (rf.routing/match-url "/m/7")))
          "…and it is :route/first's capture name that survives, not :z's")
      ;; Dev-instrumentation arm (see ns docstring).
      ;; :route/second warns against :route/first; :route/third ties with
      ;; BOTH but the warning names :route/first — the match-time winner.
      (when rf.interop/debug-enabled?
        (is (= 2 (count warns)))
        (is (= {:route-id :route/third :shadowed-by :route/first}
               (select-keys (:tags (peek warns)) [:route-id :shadowed-by]))
            "the named winner is the earliest-registered intersecting tie")))))

(deftest route-shadow-scan-registration-benchmark
  (testing "registering a large table of same-rank distinct-literal static
            routes — the worst case for the rank prefilter (every pair
            ties, so the co-matchability check runs O(N²/2) times) — stays
            cheap and emits ZERO warnings"
    (let [n     300
          start (System/nanoTime)
          warns (shadow-warnings
                  (fn []
                    (dotimes [i n]
                      (rf/reg-route (keyword "bench" (str "static-" i)) {}
                                    (str "/bench-" i)))
                    ;; A second same-rank family with a param segment —
                    ;; the automata walk two segments per tie here.
                    (dotimes [i 50]
                      (rf/reg-route (keyword "bench" (str "param-" i)) {}
                                    (str "/p" i "/:id")))))
          elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
      ;; SEMANTIC, posture-independent: there is nothing to warn ABOUT — every
      ;; route resolves to itself. Without this the `(= [] warns)` leg is
      ;; vacuous under the gate.
      (is (every? #(= (keyword "bench" (str "static-" %))
                      (:route-id (rf.routing/match-url (str "/bench-" %))))
                  (range n))
          "every distinct-literal static route is reachable")
      (is (every? #(= (keyword "bench" (str "param-" %))
                      (:route-id (rf.routing/match-url (str "/p" % "/7"))))
                  (range 50))
          "…and every param-family route")
      ;; Dev-instrumentation arm (see ns docstring); NEGATIVE over
      ;; the trace ring, hence guarded.
      (when rf.interop/debug-enabled?
        (is (= [] warns)
            "distinct-literal same-rank tables emit zero shadow warnings"))
      ;; Generous CI bound — locally this is tens of milliseconds; the
      ;; assertion guards against an accidental exponential blow-up in the
      ;; product-automaton walk, not micro-performance.
      (is (< elapsed-ms 10000)
          (str "registration scan stayed bounded (took " elapsed-ms " ms)")))))

;; ---- :int / :keyword / :boolean query coercion --------------------------

(deftest query-coercion-vocabulary
  (testing "coerce-by-type-form honours :int / :boolean for query-string
            values, and a BOUNDED `[:enum …]` keyword slot interns declared
            choices. A bare (unbounded) `:keyword` type-form is NOT usable —
            it is rejected fail-loud at reg-route; `[:enum …]` is
            the keyword path (the DoS-safe allowlist)."
    (rf/reg-route :route/search
                  {:query [:map
                           [:count    :int]
                           [:sort     [:enum :asc :desc]]
                           [:archived :boolean]
                           [:plain    :string]]} "/search")
    (let [m (rf.routing/match-url "/search?count=42&sort=desc&archived=true&plain=hello")]
      (is (= 42 (get-in m [:query :count]))
          ":int coerces to a Long")
      (is (= :desc (get-in m [:query :sort]))
          "a declared `[:enum …]` choice interns to its keyword —
          the bounded allowlist; a bare `:keyword` slot cannot round-trip
          and is rejected at reg-route")
      (is (= true (get-in m [:query :archived]))
          ":boolean coerces \"true\" to true")
      (is (= "hello" (get-in m [:query :plain]))
          ":string / unknown type-form passes through unchanged")))

  (testing ":boolean \"false\" coerces to false; non-true/non-false
            strings pass through unchanged"
    (rf/reg-route :route/page {:query [:map [:flag :boolean]]} "/p")
    (is (false? (get-in (rf.routing/match-url "/p?flag=false") [:query :flag]))
        "\"false\" coerces to false")
    (is (= "maybe" (get-in (rf.routing/match-url "/p?flag=maybe") [:query :flag]))
        "non-vocabulary strings pass through unchanged"))

  (testing ":int on a non-numeric string passes through unchanged
            (no throw — graceful degradation)"
    (rf/reg-route :route/page2 {:query [:map [:n :int]]} "/p2")
    (is (= "abc" (get-in (rf.routing/match-url "/p2?n=abc") [:query :n]))
        "non-numeric :int input is left as-is (no exception)"))

  ;; Strict + host-IDENTICAL :int coercion. The whole string
  ;; must be an integer literal (`^-?\d+$`) to coerce; otherwise it stays a
  ;; string on BOTH hosts. A lenient parse would diverge on partial-numeric
  ;; input: `Long/parseLong "12abc"` throws -> string passthrough (JVM),
  ;; while `js/parseInt "12abc" 10` -> 12 (CLJS) — a Spec 011 hydration-
  ;; mismatch hazard. The cross-host conformance
  ;; vehicle is fixtures/routing-query-string-coercion.edn (run by both the
  ;; JVM and CLJS corpus harnesses); this is the artefact-local pin.
  (testing ":int coerces only whole integer literals; partial-
            numeric and radix-prefixed input stays a string identically on
            JVM and CLJS"
    (rf/reg-route :route/page3 {:query [:map [:page :int]]} "/p3")
    (is (= 12 (get-in (rf.routing/match-url "/p3?page=12") [:query :page]))
        "clean integer literal coerces to a Long")
    (is (= -7 (get-in (rf.routing/match-url "/p3?page=-7") [:query :page]))
        "signed integer literal coerces")
    (is (= "12abc" (get-in (rf.routing/match-url "/p3?page=12abc") [:query :page]))
        "partial-numeric input stays a STRING on both hosts — a lenient
         parse would read 12 on CLJS and \"12abc\" on JVM")
    (is (= "0x10" (get-in (rf.routing/match-url "/p3?page=0x10") [:query :page]))
        "radix-prefixed input stays a string on both hosts")
    (is (= " 12" (get-in (rf.routing/match-url "/p3?page=%2012") [:query :page]))
        "leading-whitespace input stays a string on both hosts")))

;; ---- OPTIONED Malli scalar schemas coerce like bare forms ----------------
;;
;; A scalar slot carrying ordinary Malli properties — `[:int {:min 1}]`,
;; `[:uuid {...}]`, `[:double {...}]`, `[:boolean {...}]`, or an optioned
;; enum `[:enum {...} :a :b]` — must coerce the URL string identically to
;; its bare form (`:int`, `:uuid`, ...). Taking the raw vector type-form
;; would hand `coerce-by-type-form` `[:int {:min 1}]` (not `:int`), skip
;; coercion, and leave the string value `"2"` failing the route's
;; `[:int {:min 1}]` schema → `:validation-failed? true` → every valid deep
;; link 404s. `[:maybe inner]` wrappers coerce the inner type.

(deftest rf2-fwz29i-optioned-scalar-query-coercion
  (testing "optioned scalar :query schemas coerce equivalently to bare forms"
    (rf/reg-route :route/items
                  {:query [:map
                           [:page [:int {:min 1}]]
                           [:id [:uuid {}]]
                           [:archived [:boolean {}]]]} "/items")
    (let [uuid-str "550e8400-e29b-41d4-a716-446655440000"
          m (rf.routing/match-url
              (str "/items?page=2&id=" uuid-str "&archived=true"))]
      (is (= :route/items (:route-id m)))
      (is (= 2 (get-in m [:query :page]))
          "[:int {:min 1}] coerces \"2\" to 2 (a string \"2\" would fail validation)")
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
    (let [m (rf.routing/match-url "/p?page=2")]
      (is (= 2 (get-in m [:query :page]))
          "the string coerces to the number 2 (coercion is unconditional)")
      (is (true? (:validation-failed? m))
          "2 < :min 5 → validation still fails (the option is enforced)")))

  (testing "a non-numeric value for an optioned :int stays a string and fails"
    (rf/reg-route :route/min-page2
                  {:query [:map [:page [:int {:min 1}]]]} "/p2")
    (let [m (rf.routing/match-url "/p2?page=abc")]
      (is (= "abc" (get-in m [:query :page]))
          "non-integer-literal stays a string (host-symmetric passthrough)")
      (is (true? (:validation-failed? m))
          "the string fails the :int schema — fail-closed, not a crash"))))

(deftest rf2-fwz29i-optioned-scalar-path-coercion
  (testing "optioned scalar :params (path) schemas coerce like bare forms"
    (rf/reg-route :route/page    {:params [:map [:n [:int {:min 1}]]]} "/page/:n")
    (rf/reg-route :route/article {:params [:map [:id [:uuid {}]]]} "/articles/:id")

    (testing "[:int {:min 1}] path param coerces; validation passes"
      (let [m (rf.routing/match-url "/page/2")]
        (is (= :route/page (:route-id m)))
        (is (= 2 (get-in m [:params :n])) "\"2\" coerced to 2 (a string would 404)")
        (is (false? (:validation-failed? m)))))

    (testing "[:uuid {}] path param coerces to a #uuid; canonical route matches"
      (let [uuid-str "550e8400-e29b-41d4-a716-446655440000"
            m        (rf.routing/match-url (str "/articles/" uuid-str))]
        (is (= :route/article (:route-id m)))
        (is (= (parse-uuid uuid-str) (get-in m [:params :id])))
        (is (false? (:validation-failed? m)))))

    (testing "an optioned :int path value violating the option still fails"
      (rf/reg-route :route/minp {:params [:map [:n [:int {:min 5}]]]} "/m/:n")
      (let [m (rf.routing/match-url "/m/2")]
        (is (= 2 (get-in m [:params :n])) "coerced to the number 2")
        (is (true? (:validation-failed? m)) "2 < :min 5 → validation fails")))))

(deftest rf2-fwz29i-optioned-enum-keyword-allowlist
  (testing "an optioned `[:enum {...} :asc :desc]` keeps the keyword
            allowlist gate (opts map skipped); declared values intern,
            others stay strings"
    (rf/reg-route :route/sorted
                  {:query [:map [:sort [:enum {:default :asc} :asc :desc]]]} "/items")
    (let [m1 (rf.routing/match-url "/items?sort=asc")
          m3 (rf.routing/match-url "/items?sort=hostile-value")]
      (is (= :asc (get-in m1 [:query :sort]))
          "declared enum value interns to :asc even with an opts map")
      (is (= "hostile-value" (get-in m3 [:query :sort]))
          "value outside the allowlist stays a string — no unbounded intern"))))

;; ---- :double / decimal route types are rejected at reg-route --------------
;;
;; A floating-point value has no canonical-EDN identity (`rf.identity/bad-number?`
;; rejects floats / ratios / NaN / ∞), so a `:double`-typed :params / :query key
;; breaks the route prism (EP-0012): `match-url` would coerce a URL segment to a
;; FLOAT, but `route-url` REFUSES to emit that same float — `assert-url-value!`
;; routes it through `rf.identity/canonical-bytes`, which throws
;; :rf.error/route-url-non-edn-value (the `[:float 1.5]` case pinned in
;; routing_url_non_edn_cljs_test). The two prism legs disagree — a URL-driven vs
;; programmatic-navigation split — and a float diverges across the JVM/CLJS hosts
;; (an integer-valued `2.0` is even ADMITTED by CEDN on CLJS but rejected on the
;; JVM), the Spec 011 hydration-mismatch class the `:int` strictness rule already
;; guards. Rather than silently produce un-round-trippable float route data, we
;; fail LOUD at the authoring boundary. These tests pin the rejection across the
;; bare / optioned / :maybe-wrapped shapes, on both the :params and :query slots.

(deftest route-double-decimal-rejected-at-reg-route-rf2-5s7l6d
  (letfn [(reg-throws [metadata]
            (try
              (rf/reg-route :route/dec metadata "/dec/:x")
              nil
              (catch clojure.lang.ExceptionInfo e e)))]

    (testing "a bare :double path param is rejected fail-loud at reg-route"
      (let [ex (reg-throws {:params [:map [:x :double]]})]
        (is (some? ex) "reg-route with a :double :params key must throw")
        (is (= :rf.error/route-decimal-unsupported (:rf.error/id (ex-data ex)))
            "the structured discriminator is :rf.error/route-decimal-unsupported")
        (is (re-find #"\[:rf\.error/route-decimal-unsupported\]" (ex-message ex))
            "the message carries the greppable [:rf.error/…] token")
        (let [data (ex-data ex)]
          (is (= :route/dec (:route-id data)))
          (is (= :params (:slot data)))
          (is (= :x (:param data))))))

    (testing "an OPTIONED [:double {…}] path param is rejected the same way
              (the properties map does not launder the decimal type)"
      (let [ex (reg-throws {:params [:map [:x [:double {:min 0.0}]]]})]
        (is (= :rf.error/route-decimal-unsupported (:rf.error/id (ex-data ex))))))

    (testing "a [:maybe :double] path param is rejected (the wrapper is unwrapped)"
      (let [ex (reg-throws {:params [:map [:x [:maybe :double]]]})]
        (is (= :rf.error/route-decimal-unsupported (:rf.error/id (ex-data ex))))))

    (testing "a :double :query key is rejected too — the :query slot is scanned"
      (let [ex (reg-throws {:query [:map [:ratio :double]]})]
        (is (= :rf.error/route-decimal-unsupported (:rf.error/id (ex-data ex))))
        (is (= :query (:slot (ex-data ex))))
        (is (= :ratio (:param (ex-data ex))))))))

(deftest route-prism-has-no-double-asymmetry-rf2-5s7l6d
  (testing "with :double rejected, no route can produce float route data, so
            the match-url/route-url prism can never hit the float asymmetry —
            the admitted scalar types (:int / :uuid / :string / enum) all
            round-trip through both legs byte-stably"
    (rf/reg-route :route/prism
                  {:params [:map [:id :int]]
                   :query  [:map [:sort [:enum :asc :desc]] [:q :string]]}
                  "/prism/:id")
    (let [url (rf.routing/route-url {:to :route/prism :params {:id 42} :query {:sort :desc :q "milk"}})
          m   (rf.routing/match-url url)]
      (is (= 42 (get-in m [:params :id])) ":int path param round-trips (no float)")
      (is (= :desc (get-in m [:query :sort])) "enum keyword round-trips")
      (is (= "milk" (get-in m [:query :q])) ":string query round-trips")
      ;; route-url ∘ match-url ∘ route-url is the identity on the canonical URL —
      ;; the EP-0012 prism law, which holds because no un-round-trippable float
      ;; can enter.
      (is (= url (rf.routing/route-url {:to (:route-id m) :params (:params m) :query (:query m)}))
          "the prism round-trips byte-stably with only CEDN-admitted types"))))

(deftest rf2-fwz29i-maybe-wrapper-coercion
  (testing "[:maybe inner] coerces the present value against the inner
            type (query side); a coerced value conforms to the :maybe schema"
    (rf/reg-route :route/opt
                  {:query [:map
                           [:page [:maybe :int]]
                           [:size [:maybe [:int {:min 1}]]]]} "/opt")
    (let [m (rf.routing/match-url "/opt?page=7&size=3")]
      (is (= 7 (get-in m [:query :page]))
          "[:maybe :int] coerces \"7\" to 7")
      (is (= 3 (get-in m [:query :size]))
          "[:maybe [:int {:min 1}]] coerces through both wrapper and option")
      (is (false? (:validation-failed? m))
          "coerced values conform to the :maybe schemas")))

  (testing "[:maybe :uuid] path param coerces; absent optional key is absent"
    (rf/reg-route :route/maybe-art {:params [:map [:id [:maybe :uuid]]]} "/a/:id")
    (let [uuid-str "550e8400-e29b-41d4-a716-446655440000"
          m        (rf.routing/match-url (str "/a/" uuid-str))]
      (is (= (parse-uuid uuid-str) (get-in m [:params :id]))
          "[:maybe :uuid] coerces the present capture to a UUID")
      (is (false? (:validation-failed? m))))))

;; ---- keyword-interning defence on query keys ------------------------------
;;
;; The keyword-interning DoS (an attacker-influenced URL stream with
;; N-unique query keys choosing N interns, each retained as long as a slice
;; holds it, on a long-running SSR JVM) is closed at the source by SELECTIVE KEYWORDING — `coerce-query`
;; promotes ONLY keys declared by the route's `:query` schema /
;; `:query-defaults` to keyword keys; every undeclared
;; URL key passes through as a **string**, so a hostile URL of N-unique
;; undeclared keys interns ZERO keywords. No raw-query-size cap is needed,
;; and there is none.
;; The defences:
;;
;; 1. Selective keywording — only keys declared by the route's `:query`
;;    schema / `:query-defaults` are promoted to keyword
;;    keys. Unknown keys stay as **string** keys. (This IS the closure.)
;; 2. `:keyword`-typed value gate — `[:enum :a :b ...]` is the bounded
;;    keyword-allowlist path (values matching a declared choice intern,
;;    others stay string). A bare / unbounded `:keyword` slot is REJECTED
;;    fail-loud at reg-route (it cannot round-trip the URL prism) rather
;;    than silently accepted as a string.

(deftest rf2-x0ngkv-many-undeclared-keys-stay-strings-no-cap
  (testing "a route declaring NO query vocabulary keeps EVERY URL key as a
            string regardless of cardinality — no per-URL key cap, the
            keyword table is never extended"
    (rf/reg-route :route/search {} "/search")
    ;; Far more unique keys than a 10000-key cap would permit:
    ;; the parse succeeds (no throw) and every key is a STRING.
    (let [n   15000
          q   (clojure.string/join "&" (map #(str "k" % "=v") (range n)))
          url (str "/search?" q)
          m   (rf.routing/match-url url)]
      (is (some? m) "a high-cardinality URL parses without throwing — no cap")
      (is (= :route/search (:route-id m)))
      (is (= n (count (:query m))) "every unique key survives")
      (is (every? string? (keys (:query m)))
          "EVERY undeclared key is a STRING — zero keywords interned"))))

(deftest rf2-3k3o7-undeclared-query-keys-stay-as-strings
  (testing "query keys NOT declared by the route's `:query` schema or
            `:query-defaults` stay as **string** keys in the parsed
            :query map — no keyword is interned on their behalf"
    (rf/reg-route :route/search
                  {:query [:map [:q :string]]} "/search")
    (let [m (rf.routing/match-url "/search?q=clojure&unknown1=foo&unknown2=bar")]
      (is (some? m))
      (is (= {:q "clojure" "unknown1" "foo" "unknown2" "bar"} (:query m))
          "declared :q is keyword-keyed; undeclared `unknown1` / `unknown2` are keyed by STRING, with no keyword twin"))))

;; ---- :query-defaults populates absent keys -----------------------------

(deftest query-defaults-populates-absent-keys
  (testing ":query-defaults supplies values for absent query keys; URL-
            supplied values win on conflict (Spec 012 §Query-string
            coercion §Defaults)"
    (rf/reg-route :route/list
                  {:query-defaults {:page 1 :per-page 20 :sort "asc"}} "/list")
    (is (= {:page 1 :per-page 20 :sort "asc"}
           (:query (rf.routing/match-url "/list")))
        ":query-defaults populates every absent key")
    (is (= {:page "3" :per-page 20 :sort "desc"}
           (:query (rf.routing/match-url "/list?page=3&sort=desc")))
        "URL-supplied :page and :sort win over their defaults (no :query schema, so :page stays the string \"3\"); the absent :per-page still takes its default")))

;; ---- route-url optional-group elision when inner params absent --------

(deftest route-url-optional-group-elision
  (testing "route-url with absent optional-group params elides the group
            (Spec 012 §Bidirectional URL ↔ params §Optional groups)"
    (rf/reg-route :route/articles
                  {} "/articles{/:id}?")
    (is (= "/articles"
           (rf.routing/route-url {:to :route/articles :params {}}))
        "absent :id → optional group elides; bare /articles emits")
    (is (= "/articles/intro"
           (rf.routing/route-url {:to :route/articles :params {:id "intro"}}))
        "present :id → optional group emits including the leading /"))

  (testing "deeper optional-group elision: an inner param's absence
            collapses the whole group (every? over inner-names)"
    (rf/reg-route :route/articles2
                  {} "/articles{/:id/:slug}?")
    (is (= "/articles"
           (rf.routing/route-url {:to :route/articles2 :params {}}))
        "both absent → group elides")
    (is (= "/articles"
           (rf.routing/route-url {:to :route/articles2 :params {:id "intro"}}))
        "ONE inner param absent → group still elides (every? requires all)")
    (is (= "/articles/intro/welcome"
           (rf.routing/route-url {:to :route/articles2 :params {:id "intro" :slug "welcome"}}))
        "all inner params present → group emits")))

;; ---- empty-string path param inside an OPTIONAL GROUP is
;; rejected on emission, exactly like a top-level required path param.
;;
;; The optional-group gate enters a group when every inner param is
;; `some?`. `(some? "")` is TRUE, so `{:id ""}` ENTERS the group, and writing
;; the value directly would emit a zero-length segment (`/articles/`) that
;; `match-url`'s trailing-slash normalisation erases (`/articles/` →
;; `/articles`) before matching. The URL would then round-trip back as the
;; param ABSENT, diverging the committed route slice from the address bar
;; (reload / popstate / SSR-hydration drift).
;; Spec 012 §`route-url` nil-policy makes `""` a HARD ERROR for ANY path
;; segment — the optional-group path must apply the same invariant as the
;; top-level `require-param`. `false` / `0` are non-empty
;; legitimate segments and still round-trip on either side.

(deftest route-url-optional-group-empty-string-rejected
  (testing "an empty-string param inside an optional group is
            REJECTED on emission — it cannot emit an un-round-trippable
            trailing slash (`/articles/`)"
    (rf/reg-route :route/og-articles {} "/articles{/:id}?")
    ;; The hazard: `(some? "")` enters the group, which would emit `/articles/`.
    (let [ex (try
               (rf.routing/route-url {:to :route/og-articles :params {:id ""}})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex)
          "\"\" optional-group param throws (it would emit \"/articles/\", which match-url normalises to \"/articles\" and re-parses with :id ABSENT)")
      ;; Anchor on the canonical :rf.error/id discriminator.
      (is (= :rf.error/missing-route-param (:rf.error/id (ex-data ex)))
          "reuses the missing/empty-required-param error id")
      (is (= "" (:value (ex-data ex)))
          "ex-data carries the offending empty-string value")))

  (testing "empty-string rejection holds for an optional group
            trailing a REQUIRED top-level param (/articles/:id{/:slug}?)"
    (rf/reg-route :route/og-slug {} "/articles/:id{/:slug}?")
    ;; The required :id still round-trips; absent :slug elides.
    (is (= "/articles/5"
           (rf.routing/route-url {:to :route/og-slug :params {:id "5"}}))
        "required :id present, optional :slug absent → group elides")
    (is (= "/articles/5/welcome"
           (rf.routing/route-url {:to :route/og-slug :params {:id "5" :slug "welcome"}}))
        "both present → group emits")
    ;; Empty optional-group :slug rejected (would emit "/articles/5/").
    (let [ex (try
               (rf.routing/route-url {:to :route/og-slug :params {:id "5" :slug ""}})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex)
          "\"\" optional-group :slug throws — it would emit the un-round-trippable \"/articles/5/\"")
      ;; Anchor on the canonical :rf.error/id discriminator.
      (is (= :rf.error/missing-route-param (:rf.error/id (ex-data ex))))
      (is (= "" (:value (ex-data ex))))))

  (testing "false and 0 inside an optional group STILL round-trip —
            only the empty string is rejected (non-empty falsy is legitimate)"
    (rf/reg-route :route/og-flag {} "/items{/:flag}?")
    (is (= "/items/false"
           (rf.routing/route-url {:to :route/og-flag :params {:flag false}}))
        "false → non-empty segment \"false\"")
    (is (= "/items/0"
           (rf.routing/route-url {:to :route/og-flag :params {:flag 0}}))
        "0 → non-empty segment \"0\"")
    (is (= {:flag "false"}
           (:params (rf.routing/match-url (rf.routing/route-url {:to :route/og-flag :params {:flag false}}))))
        "false round-trips through match-url")
    (is (= {:flag "0"}
           (:params (rf.routing/match-url (rf.routing/route-url {:to :route/og-flag :params {:flag 0}}))))
        "0 round-trips through match-url")))

;; ---- optional-group elision: the canonical slash-INSIDE spelling ---------
;;
;; The grammar has ONE optional-group spelling: slash-INSIDE the braces
;; (`{/:id}?`, `{/:base}?`). The group OWNS its leading slash, so
;; eliding the whole group when its param is absent drops that slash cleanly
;; and can NEVER orphan a bracketing literal `/` — the `//about`
;; protocol-relative-URL footgun (a modifier-click escapes the app;
;; programmatic `pushState` diverges slice from address bar) is structurally
;; impossible for the slash-inside form. A leading group (`{/:base}?/about`)
;; and a root-only group (`{/:base}?`) both elide to a single clean slash.
;;
;; The slash-OUTSIDE inline spelling (`{:base}?` between literal `/`s, e.g.
;; `/{:base}?/about`) is REJECTED at registration
;; (`validate-optional-group!`), so no valid pattern needs an emitter
;; separator-repair.

(deftest route-url-optional-group-no-double-slash
  (testing "a LEADING optional group ({/:base}?/about) emits a
            single separator when absent — never `//`"
    (rf/reg-route :route/inline-about {} "{/:base}?/about")
    (is (= "/about"
           (rf.routing/route-url {:to :route/inline-about :params {}}))
        "absent :base → single leading separator, NOT the protocol-relative `//about`")
    (is (= "/docs/about"
           (rf.routing/route-url {:to :route/inline-about :params {:base "docs"}}))
        "present :base → /docs/about")
    ;; Round-trip: emitted absent URL re-parses to the same route, no params.
    (let [m (rf.routing/match-url (rf.routing/route-url {:to :route/inline-about :params {}}))]
      (is (= :route/inline-about (:route-id m))
          "absent emission round-trips through match-url to the same route")
      (is (= {} (:params m))
          "no :base param survives the round-trip")))

  (testing "a MID-PATH optional group (/docs{/:section}?/about)
            does not orphan a separator on either side"
    (rf/reg-route :route/docs-about {} "/docs{/:section}?/about")
    (is (= "/docs/about"
           (rf.routing/route-url {:to :route/docs-about :params {}}))
        "absent :section → /docs/about, NOT /docs//about")
    (is (= "/docs/api/about"
           (rf.routing/route-url {:to :route/docs-about :params {:section "api"}}))
        "present :section → /docs/api/about")))

;; ---- match-url optional-group param is ABSENT, not nil-valued -----------
;;
;; Per Spec 012 §Path-pattern grammar (Optional
;; segment group): "param present only if matched." The canonical example
;; route /articles/:id{/:slug}? declares :params with {:optional true} on
;; :slug. Malli {:optional true} governs KEY PRESENCE, not nil values, so a
;; present {:slug nil} is REJECTED. Zipmapping the unmatched optional group
;; as :slug nil would make a legitimate /articles/<id> URL fail :params
;; validation and route to not-found, so match-against strips nil-valued
;; keys after the zipmap and the unmatched param is absent.
;;
;; The stub :params predicate below mirrors Malli {:optional true}: :slug,
;; *when present*, must be a non-nil string; an ABSENT :slug is fine.

(deftest match-url-optional-group-param-absent-not-nil
  (testing "an unmatched optional-group param is ABSENT from :params, not
            nil-valued, so a route carrying a {:optional true} :params
            schema still matches a URL that omits the optional segment
            (Spec 012 §Path-pattern grammar §Optional segment group)"
    (let [restore (rf.routing-test-support/with-stub-validator)
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
          (let [m (rf.routing/match-url "/articles/5")]
            (is (some? m) "the route matches structurally")
            (is (= {:id "5"} (:params m))
                ":slug is ABSENT (not nil-valued) when the optional group is unmatched")
            (is (false? (:validation-failed? m))
                "a present {:slug nil} would reject the {:optional true} schema; an absent :slug validates cleanly")
            (is (nil? (:validation-error m))
                "no validation error for the absent optional param")))

        (testing "/articles/5/intro — optional :slug supplied"
          (let [m (rf.routing/match-url "/articles/5/intro")]
            (is (some? m) "the route matches when the optional segment is present")
            (is (= {:id "5" :slug "intro"} (:params m))
                "the optional key is present with its captured value when supplied")
            (is (false? (:validation-failed? m))
                "the supplied :slug conforms")))
        (finally (restore))))))

;; ============================================================================
;; match-url coerces PATH params against the :params schema
;; (mirror of the query side) before validating them. The canonical
;; Spec 012 :uuid route's round trip, on both hosts, is
;; routing_url_non_edn_cljs_test's uuid-path-mixed-case-coerces-host-symmetric.
;; Exercised with a real Malli validator (re-frame.schemas is required by
;; this ns's fixture), so :validation-failed? actually runs.
;; ============================================================================

(deftest path-param-coercion-against-params-schema
  (testing "a typed PATH param coerces against the route's
            :params schema BEFORE validation, and a capture its type
            cannot read stays the raw string, which the schema rejects"
    (rf/reg-route :route/page    {:params [:map [:n :int]]} "/page/:n")
    (rf/reg-route :route/article {:params [:map [:id :uuid]]} "/articles/:id")
    (rf/reg-route :route/str     {:params [:map [:v :string]]} "/s/:v")

    (testing ":int path param coerces to a number; validation passes"
      (let [m (rf.routing/match-url "/page/42")]
        (is (= :route/page (:route-id m)))
        (is (= 42 (get-in m [:params :n])) "string \"42\" coerced to the number 42")
        (is (false? (:validation-failed? m))
            "coerced :int conforms to [:n :int] — no validation failure")))

    (testing ":string path param stays a string (no coercion); a non-UUID
              for a :uuid route stays a string and fails validation"
      (is (= "hello" (get-in (rf.routing/match-url "/s/hello") [:params :v])))
      (let [m (rf.routing/match-url "/articles/not-a-uuid")]
        ;; not-a-uuid stays a string (parse-uuid → nil → passthrough), so
        ;; the :uuid schema flags it — fail-closed, not a crash.
        (is (= "not-a-uuid" (get-in m [:params :id])))
        (is (true? (:validation-failed? m))
            "a non-UUID value for a :uuid route fails validation (string passthrough)")))))

;; ============================================================================
;; :int coercion is HOST-SYMMETRIC and TOTAL on oversized
;; integers. The JVM half of the cross-host parity pin; the CLJS half lives
;; in routing_history_cljs_test.cljs. Both hosts must agree EXACTLY.
;; ============================================================================

(deftest int-coercion-oversized-host-parity-jvm
  (testing "an integer literal above the cross-host
            safe-integer ceiling (2^53-1) PASSES THROUGH AS A STRING on
            the JVM (an exact Long would be a lossy double on CLJS — a
            Spec 011 hydration mismatch), and a >2^63 literal does NOT
            throw (no NumberFormatException escapes match-url)"
    (rf/reg-route :route/items {:query [:map [:page :int]]} "/items")

    (testing "values within the safe-integer range still coerce"
      (is (= 42 (get-in (rf.routing/match-url "/items?page=42") [:query :page])))
      (is (= 9007199254740991
             (get-in (rf.routing/match-url "/items?page=9007199254740991") [:query :page]))
          "2^53-1 (MAX_SAFE_INTEGER) coerces — the ceiling is inclusive"))

    (testing "values ABOVE the safe-integer ceiling pass through as strings (both hosts agree)"
      (is (= "9007199254740992"
             (get-in (rf.routing/match-url "/items?page=9007199254740992") [:query :page]))
          "2^53 exceeds MAX_SAFE_INTEGER → string passthrough (CLJS would be lossy)")
      (is (= "9007199254740993"
             (get-in (rf.routing/match-url "/items?page=9007199254740993") [:query :page]))
          "the canonical lossy-double case (2^53+1) → string on BOTH hosts")
      (is (= "-9007199254740993"
             (get-in (rf.routing/match-url "/items?page=-9007199254740993") [:query :page]))
          "negative oversized literal also passes through"))

    (testing "a literal beyond 2^63 does NOT throw (parse-long is total)"
      (is (= "99999999999999999999999"
             (get-in (rf.routing/match-url "/items?page=99999999999999999999999") [:query :page]))
          "no NumberFormatException escapes — direct match-url callers see a clean string"))))

;; ============================================================================
;; Route lifecycle trace ops
;; ============================================================================

(deftest route-registered-trace-on-first-time-reg
  (testing ":rf.route/registered fires on FIRST-TIME reg-route.
            Re-registration with the same id rides the cross-kind
            `:rf.registry/handler-replaced` trace; not re-emitted here.
            Mirrors the `:rf.flow/registered` symmetry."
    (let [traces (atom [])]
      (rf/register-listener! :trace ::reg-trace (fn [ev] (swap! traces conj ev)))
      (rf/reg-route :route/home {} "/")
      (rf/reg-route :route/home {} "/") ;; re-register (no trace)
      (rf/unregister-listener! :trace ::reg-trace)
      ;; SEMANTIC, posture-independent: the registration the trace
      ;; announces really landed, and the re-registration really was idempotent
      ;; rather than additive — one route row, one path.
      (is (= "/" (:path (rf/handler-meta {:source :store :kind :route :id :route/home})))
          "the route is registered at / after both calls")
      (is (= :route/home (:route-id (rf.routing/match-url "/")))
          "…and / resolves to it")
      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (let [reg-events (filter #(= :rf.route/registered (:operation %)) @traces)]
          (is (= 1 (count reg-events))
              "first-time reg-route emits :rf.route/registered exactly once")
          (is (= :route/home (-> reg-events first :tags :route-id))
              ":route-id rides in :tags")
          (is (= "/" (-> reg-events first :tags :path))
              ":path rides in :tags"))))))

(deftest route-cleared-trace-on-unregister
  (testing "clear-route emits :rf.route/cleared"
    (rf/reg-route :route/transient {} "/transient")
    (let [traces (atom [])]
      (rf/register-listener! :trace ::cleared-trace (fn [ev] (swap! traces conj ev)))
      (rf/clear :route :route/transient)
      (rf/clear :route :route/transient) ;; idempotent, no trace
      (rf/unregister-listener! :trace ::cleared-trace)
      ;; SEMANTIC, posture-independent: the clear the trace
      ;; announces really happened, and the SECOND clear really was idempotent
      ;; — it neither threw nor resurrected the row.
      (is (nil? (rf/handler-meta {:source :store :kind :route :id :route/transient}))
          "the route row is gone after clear-route")
      (is (nil? (:route-id (rf.routing/match-url "/transient")))
          "…and /transient no longer resolves to it")
      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (let [cleared-events (filter #(= :rf.route/cleared (:operation %)) @traces)]
          (is (= 1 (count cleared-events))
              "clear-route emits :rf.route/cleared exactly once")
          (is (= :route/transient (-> cleared-events first :tags :route-id))
              ":route-id rides in :tags"))))))

;; ===========================================================================
;; match-against direct function-boundary tests
;;
;; `match-against` is the pattern-matcher fn consumed by
;; `re-frame.routing/match-url` (the owning-namespace export — there is no
;; `re-frame.core` façade one). The match-url-level tests above exercise it
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

(deftest match-against-captures-and-anchors-both-ends
  (testing "match-against returns the captured params map for a URL its
            compiled pattern matches, anchored at both ends, and nil (no
            throw) for one it does not"
    (are [pattern url expected]
         (= expected (rf.routing.match/match-against (rf.routing.match/parse-pattern pattern) url))
      ;; a literal pattern matches only its exact URL, with no captures
      "/foo/bar"                  "/foo/bar"            {}
      "/foo/bar"                  "/foo/baz"            nil
      "/foo/bar"                  "/foo"                nil
      "/foo/bar"                  "/foo/bar/extra"      nil
      ;; a `:id` segment captures the raw, non-empty URL segment
      "/users/:id"                "/users/42"           {:id "42"}
      "/users/:id"                "/users/alice"        {:id "alice"}
      "/users/:id"                "/users/"             nil
      "/users/:id"                "/users"              nil
      ;; a `*path` splat captures the whole non-empty tail, slashes kept
      "/files/*path"              "/files/a"            {:path "a"}
      "/files/*path"              "/files/a/b/c"        {:path "a/b/c"}
      "/files/*path"              "/files/"             nil
      "/files/*path"              "/files"              nil
      ;; the root pattern matches `/` and the empty string, nothing deeper
      "/"                         "/"                   {}
      "/"                         ""                    {}
      "/"                         "/foo"                nil
      ;; a miss is nil, and the same pattern still matches when both captures are present
      "/users/:id/posts/:post-id" "/unrelated/path"     nil
      "/users/:id/posts/:post-id" "/users/42/posts"     nil
      "/users/:id/posts/:post-id" "/users/42/posts/9"   {:id "42" :post-id "9"})))

;; ---- named splat out-ranks the bare catch-all --------------------------
;;
;; Spec 012 §Route ranking algorithm rule 2: "The bare catch-all `/*` is
;; demoted below every other matching route." The catch-all is EXACTLY
;; the bare `/*` pattern (`is-catch-all? (= pattern "/*")` in the spec
;; pseudocode). A NAMED splat (`/*rest`) is a rest param, so it must
;; out-rank `/*`. A classifier flagging ANY single-splat-only pattern as
;; catch-all would tie `/*rest` with `/*` at the catch-all rank element
;; instead of letting it win. The catch-all element sits at index 1 —
;; ahead of total-length — so the bare `/*` loses to the root `/` too.

(deftest match-url-named-splat-wins-over-bare-catch-all
  (testing "when both `/*rest` and `/*` are registered, a
            multi-segment URL resolves to the named-splat route, not the
            catch-all (Spec 012 §Route ranking rule 2)"
    (rf/reg-route :route/catch-all {} "/*")
    (rf/reg-route :route/rest      {} "/*rest")
    (let [m (rf.routing/match-url "/some/deep/path")]
      (is (= :route/rest (:route-id m))
          "named-splat route wins against the bare catch-all (rule-2 catch-all demotion)")
      (is (= {:rest "some/deep/path"} (:params m))
          "the named splat captures the whole tail under :rest"))))

;; ---- root `/` wins over the bare catch-all `/*` for URL "/" -------------
;;
;; Spec 012 §Route ranking algorithm rule 2: the bare catch-all `/*` is
;; demoted below every other matching route. `/*` ALSO matches
;; the root URL "/" (the unnamed splat captures the literal "/"), and a
;; home route `{:path "/"}` parses to total-length 0 while `/*` is
;; length 1. Comparing total-length (rule 3) BEFORE the catch-all
;; demotion would let `/*` out-length the root and win for "/" — shadowing
;; the home route registration-order-independently. So the catch-all
;; discriminator (rank elem 1) sits ahead of total-length (rank elem 2),
;; and the root (and every concrete route) wins over `/*`.

(deftest match-url-root-wins-over-catch-all-rf2-1ugs5u
  (testing "match-url \"/\" returns the ROOT route, not the
            catch-all, when both `/` and `/*` are registered
            (registration-order-independent)"
    ;; catch-all registered FIRST so a rank defect (if present) can't hide
    ;; behind registration order — the rank cascade, not order, must win.
    (rf/reg-route :route/catch-all {} "/*")
    (rf/reg-route :route/home      {} "/")
    (let [m (rf.routing/match-url "/")]
      (is (= :route/home (:route-id m))
          "the root route wins match-url \"/\" over the catch-all")
      (is (= {} (:params m))
          "the root match carries an empty params map (no splat capture)")))

  (testing "the result is order-independent: home registered
            first ALSO resolves \"/\" to the home route"
    (rf/reg-route :route/home      {} "/")
    (rf/reg-route :route/catch-all {} "/*")
    (is (= :route/home (:route-id (rf.routing/match-url "/")))
        "home wins regardless of registration order"))

  (testing "the catch-all still wins a NON-root URL that the
            home route cannot match (the demotion only loses the root)"
    (rf/reg-route :route/home      {} "/")
    (rf/reg-route :route/catch-all {} "/*")
    (is (= :route/catch-all (:route-id (rf.routing/match-url "/anything/deep")))
        "catch-all still catches URLs no concrete route matches")))

;; ---- rank rule 5 is a boolean bit, not the optional-group ---------------
;; COUNT (and rule 4 the same shape for splats)
;;
;; Spec 012 §Route ranking algorithm rule 5: "Exact routes beat
;; optional-group routes" — the pseudocode's discriminator is
;; `(if has-optional? 0 1)`, a BOOLEAN bit. Ranking rule 5 (and rule 4,
;; "named params beat rest params") by the raw segment COUNT
;; (`(- optional)` / `(- splat)`) would rank two routes differing ONLY in
;; HOW MANY optional groups (or splats) they declare differently where the
;; spec ties them — a divergence from any conforming implementation that
;; follows the spec pseudocode.

(deftest parse-pattern-optional-group-count-does-not-affect-rank-rf2-dqlfty
  (testing "one optional group and two optional groups rank
            IDENTICALLY at rule 5 (rank element 4): both simply `have` an
            optional group, and the spec discriminator is has-optional?,
            not a magnitude"
    (let [one-group  (:rank (rf.routing.match/parse-pattern "/docs{/a}?"))
          two-groups (:rank (rf.routing.match/parse-pattern "/docs{/a}?{/b}?"))]
      (is (= one-group two-groups)
          "one optional group and two optional groups rank IDENTICALLY —
           the spec ties them (rule 5 is has-optional?, not a magnitude);
           a count-based rank would read [1 1 1 0 -1] vs [1 1 1 0 -2] — NOT tied")
      (is (= 0 (nth one-group 4))
          "rank element 4 (rule 5) is the boolean bit: 0 = has an optional group")))

  (testing "an EXACT route (no optional group) still out-ranks
            both optional-group routes (rule 5 keeps discriminating exact
            vs optional — only the WITHIN-optional-group magnitude is
            collapsed)"
    (let [exact      (:rank (rf.routing.match/parse-pattern "/docs"))
          one-group  (:rank (rf.routing.match/parse-pattern "/docs{/a}?"))]
      (is (pos? (compare exact one-group))
          "the exact route out-ranks the optional-group route (rule 5)"))))

(deftest parse-pattern-named-param-outranks-splat-rf2-dqlfty
  (testing "rule 4 (\"named params beat rest params\") is the
            same boolean shape, and the bit keeps the cascade: a named
            param beats a splat"
    (let [named (:rank (rf.routing.match/parse-pattern "/files/:name"))
          splat (:rank (rf.routing.match/parse-pattern "/files/*rest"))]
      (is (pos? (compare named splat))
          "named param out-ranks the splat at rule 4 (rank element 3)")
      (is (= 1 (nth named 3)) "named: rule-4 bit is 1 (no splat)")
      (is (= 0 (nth splat 3)) "splat: rule-4 bit is 0 (has a splat)"))))

(deftest match-url-optional-group-count-tie-breaks-on-registration-order-rf2-dqlfty
  (testing "two routes differing ONLY in optional-group COUNT
            are a genuine structural TIE per Spec 012 rule 5; match-url
            resolves the tie via rule 6 (registration order) exactly as it
            would for any other structurally-identical pair. Register the
            route with MORE optional groups FIRST: a count-based rank would
            let the route with FEWER optional groups win regardless of
            registration order (the raw count shadowing rule 6), and with
            :route/one-group registered first both rankings would pick it,
            so the two-groups-first order is the one that discriminates them"
    (rf/reg-route :route/two-groups {} "/docs{/a}?{/b}?")
    (rf/reg-route :route/one-group  {} "/docs{/a}?")
    (is (= :route/two-groups (:route-id (rf.routing/match-url "/docs")))
        "the FIRST-registered route (:route/two-groups, despite having
         MORE optional groups) wins the tie for a URL both match — rule 6
         registration-order tiebreak, not rule 5's group count")))

;; ---- sequential optional groups form a prefix chain ----------------------
;;
;; The match-time regex reads adjacent optional groups POSITIONALLY, so
;; supplying only the LATER param elides the earlier group but emits the later
;; one into the earlier group's capture slot — `route-url {:page "5"}` on
;; `/docs{/:section}?{/:page}?` would build `/docs/5`, which `match-url` reads
;; back as `{:section "5"}`: a SILENT prism-law break (both keys optional, so
;; both legs pass validation). So route-url fails CLOSED on emission (the
;; prefix rule: a later optional group is reachable only when every earlier
;; group is present), and every prefix-respecting combination round-trips.

(deftest sequential-optional-groups-prefix-rule-rf2-rpjb5i
  (rf/reg-route :route/docs
                {:params [:map
                          [:section {:optional true} :string]
                          [:page    {:optional true} :string]]}
                "/docs{/:section}?{/:page}?")
  (testing "the confirmed corruption: supplying ONLY the later param throws
            :rf.error/route-url-validation instead of silently emitting a URL
            that match-url reads into the earlier group's slot"
    (let [ex (try (rf.routing.registry/route-url {:to :route/docs :params {:page "5"}})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "route-url {:page \"5\"} throws")
      (is (= :rf.error/route-url-validation (:rf.error/id (ex-data ex))))
      (is (= [:page] (:group (ex-data ex)))
          "the offending later group is named in the ex-data")))
  (testing "the PRISM LAW holds for every prefix-respecting combination:
            match-url(route-url(x)) recovers x"
    (doseq [params [{} {:section "a"} {:section "a" :page "b"}]]
      (let [url (rf.routing.registry/route-url {:to :route/docs :params params})
            round (:params (rf.routing/match-url url))]
        (is (= params round)
            (str "round-trip for " (pr-str params) " via " (pr-str url)
                 " recovered " (pr-str round))))))
  (testing "sanity: the ambiguous URL a page-only emission would build
            (/docs/5) reads back as the FIRST group — proving the
            positional ambiguity the emission-side reject exists to prevent"
    (is (= {:section "5"} (:params (rf.routing/match-url "/docs/5")))
        "/docs/5 matches section, not page — so emitting page-only would corrupt")))

;; ---- reg-route authoring-boundary metadata validation --------------------
;;
;; Spec 012 §Reserved route-metadata keys: reg-route has the largest
;; registration shape in the v2 surface (twelve reserved keys). Unguarded, a
;; typo'd key (:on-matched for :on-match) would pass silently at registration
;; and fail later at nav-time, or never. The authoring-boundary guardrail
;; rejects bare keys outside the reserved set LOUDLY at
;; registration, naming the bad key; namespaced host/app keys pass.

(deftest reg-route-rejects-unknown-bare-metadata-key
  (testing "a typo'd bare metadata key (:on-matched for
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

(deftest reg-route-rejects-retired-on-error-key
  (testing "EP-0037 R1: route metadata :on-error is RETIRED with no alias — a
            route declaring it is rejected at registration as an unknown bare
            key (:rf.error/route-bad-metadata), not silently accepted"
    (let [ex (try
               (rf/reg-route :route/legacy-on-error
                             {:on-match [[:load]] :on-error [:oops]} "/legacy")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex)
          "reg-route THROWS on the retired :on-error key")
      (is (= :rf.error/route-bad-metadata (:rf.error/id (ex-data ex)))
          "the canonical thrown-error id discriminates the failure")
      (is (= [:on-error] (:keys (ex-data ex)))
          ":keys names exactly the retired key"))))

(deftest reg-route-rejects-retired-query-retain-key
  (testing "EP-0037 R5: route metadata :query-retain is RETIRED with no alias —
            a route declaring it is rejected at registration as an unknown bare
            key (:rf.error/route-bad-metadata), not silently accepted. Carrying
            query state across routes is an APPLICATION policy spelled as a pure
            function over the destination address (Spec 012 §Carrying query
            state across routes); a stale route cannot keep the donor semantics."
    (let [ex (try
               (rf/reg-route :route/legacy-query-retain
                             {:query-retain #{:theme :locale}} "/legacy")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex)
          "reg-route THROWS on the retired :query-retain key")
      (is (= :rf.error/route-bad-metadata (:rf.error/id (ex-data ex)))
          "the canonical thrown-error id discriminates the failure")
      (is (= [:query-retain] (:keys (ex-data ex)))
          ":keys names exactly the retired key")
      (is (not (contains? (set (:reserved (ex-data ex))) :query-retain))
          "the reserved vocabulary the error prints does not offer :query-retain")
      (is (contains? (set (:reserved (ex-data ex))) :query-defaults)
          ":query-defaults is reserved — only the retain slot is retired"))))

(deftest reg-route-refuses-a-single-event-on-match
  (testing ":on-match is a vector of event vectors; a single event vector is
            refused AT REGISTRATION with :rf.error/route-bad-metadata, rather
            than registering and throwing a raw host error at the first
            navigation"
    (let [ex (try
               (rf/reg-route :route/one-event {:on-match [:app/load]} "/one-event")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex)
          "reg-route THROWS on a single-event :on-match")
      (is (= :rf.error/route-bad-metadata (:rf.error/id (ex-data ex)))
          "the canonical thrown-error id discriminates the failure")
      (is (= [:on-match] (:keys (ex-data ex)))
          ":keys names the offending key")
      (is (= [:app/load] (:value (ex-data ex)))
          ":value carries the refused value")
      (is (clojure.string/includes? (:reason (ex-data ex)) "[[:app/load]]")
          "the :reason shows the wrapped form to write instead")
      (is (nil? (rf/handler-meta {:source :store :kind :route :id :route/one-event}))
          "the refused route is not registered")))
  (testing "a vector of event vectors registers, and navigating dispatches each event"
    (let [loaded (atom [])]
      (rf/reg-event :app/load (fn [_ [_ & args]] (swap! loaded conj (vec args)) {}))
      (rf/reg-route :route/two-events {:on-match [[:app/load 1] [:app/load 2]]} "/two-events")
      (rf/dispatch-sync [:rf.route/navigate {:to :route/two-events}])
      (is (= [[1] [2]] @loaded)
          "both :on-match events ran, in order")))
  (testing "a nil :on-match declares no events"
    (is (= :route/no-events (rf/reg-route :route/no-events {:on-match nil} "/no-events")))))

(deftest reg-route-accepts-valid-and-namespaced-metadata
  (testing "a route using only reserved keys + namespaced
            host/app keys registers fine (no false positives)"
    (is (= :route/ok
           (rf/reg-route :route/ok
                         {:doc            "fine"
                          :params         [:map [:id :string]]
                          :query          [:map [:q {:optional true} :string]]
                          :query-defaults {:q "x"}
                          :tags           #{:public}
                          :on-match       [[:load]]
                          :scroll         :top
                          ;; namespaced host/app extension keys always pass
                          :myapp/layout   :wide
                          :myapp/analytics-id "abc"} "/ok/:id"))
        "a route with every reserved key + namespaced extension keys registers")
    (is (some? (rf/handler-meta {:source :store :kind :route :id :route/ok}))
        "the route is queryable via handler-meta after a clean registration")))

;; ---- the :ns image-selection stamp on reg-route --------------------------
;;
;; Spec 001 §Production elision contract: a PROGRAMMATIC registration leaves
;; the macro's source-coord capture unbound, so its descriptor carries no
;; `:rf.provenance/ns` and `:select-ns` cannot see it. Stamping `:ns` in the
;; registration metadata is the documented remedy on every registrar-backed
;; kind — and `reg-route` is one, so `:ns` is accepted BARE on top of the
;; twelve routing-owned keys (Spec 012 §Reserved route-metadata keys). It is
;; not a routing key: it names the registration's provenance namespace for
;; image selection, exactly as on `reg-event`.
;;
;; Read the STORE, not a rendering: `source-store/descriptors-for` returns the
;; `provenance-ns-string → descriptor` map for `(kind, id)`, and a key of
;; "probe.ns" IS `:select-ns` selectability.

(deftest reg-route-accepts-and-honours-the-ns-provenance-stamp
  (testing "(a): a bare `:ns` registers and reaches the source store.
            Outside `reserved-route-keys`, `:ns` would make the authoring guard
            throw :rf.error/route-bad-metadata naming it, and NOTHING would be
            registered. Note `rf/reg-route` here is the MACRO
            (this is a .clj namespace), which binds *pending-coords* to THIS
            test ns — the guard runs on the user map BEFORE merge-coords, so it
            would throw under the macro too. The store reading \"probe.ns\" rather
            than \"re-frame.routing-registry-test\" therefore proves BOTH that
            the key is accepted AND that a user `:ns` overrides the captured
            one, per merge-coords' user-overrides-captured rule."
    (is (= :route/with-ns
           (rf/reg-route :route/with-ns {:ns 'probe.ns :doc "stamped"} "/with-ns"))
        "reg-route accepts a bare :ns and returns the route id")
    (is (= ["probe.ns"]
           (vec (keys (rf.source-store/descriptors-for :route :route/with-ns))))
        "the stamped :ns is the route descriptor's provenance — :select-ns-selectable"))

  (testing "(b): the typo guard's printed vocabulary offers :ns"
    (let [ex (try
               (rf/reg-route :route/still-typo {:on-matched [[:load]]} "/still-typo")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (contains? (set (:reserved (ex-data ex))) :ns)
          ":ns is in the reserved vocabulary the error prints")
      (is (not-any? (set (:reserved (ex-data ex))) [:file :line :column])
          "and ONLY :ns — :file / :line / :column stay out of the bare-key set")))

  (testing "(c) CONTROL: the QUALIFIED spelling registers and is honoured —
            qualified keys are never checked by the guard, so the bare `:ns`
            adds CONSISTENCY, not a new capability."
    (is (= :route/qualified-ns
           (rf/reg-route :route/qualified-ns
                         {:rf.provenance/ns "probe.qualified"} "/qualified-ns"))
        "a qualified :rf.provenance/ns passes the bare-key guard")
    (is (= ["probe.qualified"]
           (vec (keys (rf.source-store/descriptors-for :route :route/qualified-ns))))
        "and reaches the store")))

(deftest reg-route-rejects-non-map-metadata
  (testing "non-map metadata is rejected at the authoring
            boundary naming the route (no downstream NPE)"
    (let [ex (try (rf/reg-route :route/bad "/not-a-map" "/bad")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :rf.error/route-bad-metadata (:rf.error/id (ex-data ex)))
          "non-map metadata surfaces the same canonical error id"))))

(deftest reg-route-rejects-path-inside-metadata-map
  (testing "under the canonical 3-slot grammar the
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
