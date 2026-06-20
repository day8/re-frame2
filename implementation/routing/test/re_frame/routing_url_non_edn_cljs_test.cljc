(ns re-frame.routing-url-non-edn-cljs-test
  "Adversarial fail-closed tests for the `route-url` URL-emission boundary
  (rf2-94o54l.1 + .3, EP-0012). The companion CLJ-only cases live in
  `routing_registry_test.clj`; THIS file is `*-cljs-test.cljc` so the
  shadow-cljs `:node-test` build (`cljs-test$`) ALSO exercises the boundary on
  the CLJS host — where a RAW JS OBJECT (`#js {…}`) and a `js/Date` are the
  native host values a hostile / careless caller would smuggle into a route
  param, and where host `(str v)` (`[object Object]`) would otherwise invent a
  URL identity. The `.cljc` reader conditionals also cover the host-agnostic
  function / atom cases on BOTH runners.

  ## What is pinned (EP-0012 §Canonical EDN identity)

  docs/EP/EP-0012-path-optics-and-canonical-forms.md §893-896 +
  spec/Conventions.md §584-592: \"If a route param value cannot be represented
  as canonical EDN after schema coercion, route matching or URL printing MUST
  fail closed at the relevant boundary. It MUST NOT use host `str`, JS object
  stringification, or object identity to invent a cache or route identity.\"

  Before rf2-94o54l.1 the query KEY side was CEDN-guarded (the canonical-order
  sort runs each key through `re-frame.identity/canonical-bytes`), but path
  param values and query VALUES went straight to `url/url-encode`'s host
  `(str v)`. These tests assert that a function / atom / raw JS object / host
  `Date` / non-portable number in a path param or a (non-nil) query value now
  raises `:rf.error/route-url-non-edn-value` BEFORE any URL string is returned
  — never `route-url` returning a `/items/[object Object]`-style URL.

  Why the `js/Date` case FAILS (not coerces): a `js/Date` IS a portable EDN
  identity for a resource cache key (`re-frame.identity` canonicalizes it to
  UTC text), but its host `(str v)` is HOST-DIVERGENT (`Thu Jun 12 …` on JS vs
  an `#inst` token on the JVM) and `match-url` has no instant coercion
  vocabulary to read it back — so a URL segment cannot round-trip an instant.
  The URL-emission boundary is deliberately NARROWER than the general CEDN-1
  identity domain (it admits strings / keywords / booleans / portable integers
  / UUIDs as URL scalars), and rejects instants / host dates at this seam."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string]
   [re-frame.core :as rf]
   [re-frame.routing :as routing]
   [re-frame.test-support :as test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn routing/reset-counters!}))

(defn- thrown-route-url
  "Call `route-url` and return the thrown ExceptionInfo (or nil)."
  [route-id path-params query-params]
  (try
    (routing/route-url route-id path-params query-params)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e)))

;; Host-adversarial values. Function + atom are host-agnostic; the raw JS
;; object (`#js {}`) and `js/Date` are the CLJS-native host values, and a
;; `java.util.Date` / arbitrary `Object` are the JVM counterparts, so the SAME
;; deftest exercises the boundary with host-appropriate inputs on each runner.
(def ^:private adversarial-path-values
  [[:function (fn [_])]
   [:atom     (atom 1)]
   [:float    1.5]
   #?(:clj  [:host-object (Object.)]
      :cljs [:raw-js-object #js {:a 1}])
   #?(:clj  [:host-date (java.util.Date.)]
      :cljs [:js-date     (js/Date.)])])

(deftest route-url-path-param-host-value-fails-closed
  (testing "a host value in a required path param fails closed with
            :rf.error/route-url-non-edn-value before any URL is built"
    (rf/reg-route :route/item {} "/items/:id")
    (doseq [[label v] adversarial-path-values]
      (let [ex (thrown-route-url :route/item {:id v} {})]
        (is (some? ex)
            (str "a " (name label) " path value must throw, not host-stringify"))
        ;; rf2-du585y: assert the STRUCTURED :rf.error/id (Spec 009 §the
        ;; stable discriminator), not just the message string.
        (is (= :rf.error/route-url-non-edn-value (:rf.error/id (ex-data ex)))
            (str "structured :rf.error/id for a " (name label) " path value"))
        ;; rf2-vvixub — message is a human sentence + the trailing
        ;; [:rf.error/<id>] token; assert the token, not exact equality.
        (is (re-find #"\[:rf\.error/route-url-non-edn-value\]" (ex-message ex))
            (str "message token (secondary) for a " (name label) " path value"))
        (let [data (ex-data ex)]
          (is (= :route/item (:route-id data)))
          (is (= :params (:slot data)))
          (is (= :id (:param data))))))))

(deftest route-url-query-value-host-value-fails-closed
  (testing "a host value in a (non-nil) query value fails closed the same way
            the path side and the query-key side do"
    (rf/reg-route :route/search {} "/search")
    (doseq [[label v] adversarial-path-values]
      (let [ex (thrown-route-url :route/search {} {:q v})]
        (is (some? ex)
            (str "a " (name label) " query value must throw"))
        (is (= :rf.error/route-url-non-edn-value (:rf.error/id (ex-data ex)))
            (str "structured :rf.error/id for a " (name label) " query value"))
        ;; rf2-vvixub — message is a human sentence + the trailing
        ;; [:rf.error/<id>] token; assert the token, not exact equality.
        (is (re-find #"\[:rf\.error/route-url-non-edn-value\]" (ex-message ex))
            (str "message token (secondary) for a " (name label) " query value"))
        (let [data (ex-data ex)]
          (is (= :route/search (:route-id data)))
          (is (= :query (:slot data)))
          (is (= :q (:param data))))))))

(deftest route-url-admitted-url-scalars-still-emit
  (testing "the guard is a host-value gate, NOT a string-only gate — strings,
            booleans, portable integers, and UUIDs remain admitted so the
            happy path is unaffected"
    (rf/reg-route :route/scalar {} "/s/:v")
    (is (= "/s/hello" (routing/route-url :route/scalar {:v "hello"})))
    (is (= "/s/false" (routing/route-url :route/scalar {:v false}))
        "a present-but-falsy boolean round-trips (existing contract)")
    (is (= "/s/0"     (routing/route-url :route/scalar {:v 0}))
        "a present-but-falsy integer round-trips (existing contract)")
    (is (= "/s/x?n=1" (routing/route-url :route/scalar {:v "x"} {:n 1}))
        "a portable-integer query value is admitted")
    (let [uuid #?(:clj  (java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440000")
                  :cljs (uuid "550e8400-e29b-41d4-a716-446655440000"))]
      (is (= "/s/550e8400-e29b-41d4-a716-446655440000"
             (routing/route-url :route/scalar {:v uuid}))
          "a UUID host-stringifies to its canonical, host-stable, round-trippable form"))))

(deftest route-url-host-value-throws-before-url-built
  (testing "the failure raises a structured error, never a half-built or
            host-stringified URL string (the path guard fires during the
            pattern walk, before path-out is assembled)"
    (rf/reg-route :route/order {:query [:map [:note :string]]} "/orders/:id")
    (let [ex (thrown-route-url :route/order {:id (atom :x)} {:note "ok"})]
      (is (= :rf.error/route-url-non-edn-value (:rf.error/id (ex-data ex)))
          "a structured :rf.error/id, never a string return value"))))

;; ===========================================================================
;; rf2-jlufhn — namespaced query keys round-trip through the route prism
;; ===========================================================================
;;
;; EP-0012 §Route Prism Laws: match-url(route-url(...)) recovers the canonical
;; route data, and a namespaced keyword is a DISTINCT canonical EDN fact
;; (Conventions §Canonical EDN identity). The prior emission used `(name k)`,
;; which DROPS the namespace — `:user/id` and `:account/id` both became the
;; URL key `id`, so the prism could neither round-trip a single namespaced key
;; nor distinguish two keys sharing a name across namespaces. The fix emits the
;; reversible token `query-key->url-token` (`:user/id` -> `user/id`, percent-
;; encoded `user%2Fid`) and recovers the EXACT declared keyword on the match
;; side.

(deftest route-url-namespaced-query-key-round-trips
  (testing "a single namespaced declared query key round-trips through the
            route-url/match-url prism with its namespace intact"
    (rf/reg-route :route/np {:query [:map [:user/id :string]]} "/np")
    (let [url (routing/route-url :route/np {} {:user/id "u-7"})]
      ;; the namespace survives into the URL token (percent-encoded `/`).
      (is (= "/np?user%2Fid=u-7" url)
          "the namespace is emitted in the reversible URL token, not dropped")
      (let [m (routing/match-url url)]
        (is (= :route/np (:route-id m)))
        (is (= {:user/id "u-7"} (:query m))
            "match-url recovers the EXACT declared keyword, namespace included")))))

(deftest route-url-distinct-namespaces-same-name-do-not-collide
  (testing "ADVERSARIAL: two declared query keys that share a NAME across
            different namespaces (:user/id + :account/id) emit DISTINCT URL
            keys and both round-trip — the prior (name k) collapsed both to a
            single `id=` pair, losing data and emitting a duplicate key"
    (rf/reg-route :route/two {:query [:map [:user/id :string] [:account/id :string]]} "/two")
    (let [url (routing/route-url :route/two {} {:user/id "u" :account/id "a"})]
      ;; both namespaced keys are present and DISTINCT in the emitted URL —
      ;; no `id=` collapse, no duplicate bare key.
      (is (clojure.string/includes? url "user%2Fid=u"))
      (is (clojure.string/includes? url "account%2Fid=a"))
      (let [m (routing/match-url url)]
        (is (= {:account/id "a" :user/id "u"} (:query m))
            "both namespaced keys round-trip to their EXACT declared keywords"))
      ;; the round-trip is byte-stable (the EP-0012 prism inverse over the
      ;; canonical emitted URL).
      (is (= url (routing/route-url (:route-id (routing/match-url url))
                                    (:params (routing/match-url url))
                                    (:query (routing/match-url url))))
          "route-url ∘ match-url ∘ route-url is the identity on the canonical URL"))))

(deftest route-url-namespaced-query-defaults-and-retain-round-trip
  (testing "a namespaced :query-defaults key is recovered with its namespace
            (the declared-vocabulary token map covers defaults + retain, not
            just the :query schema)"
    (rf/reg-route :route/dflt {:query-defaults {:user/page 1}} "/dflt")
    ;; an inbound URL carrying the namespaced key recovers the declared keyword
    (let [m (routing/match-url "/dflt?user%2Fpage=3")]
      (is (= {:user/page "3"} (:query m))
          "the inbound namespaced key promotes to the declared keyword"))
    ;; absent → the default fills in under the declared (namespaced) keyword
    (let [m (routing/match-url "/dflt")]
      (is (= {:user/page 1} (:query m))
          "the default is filled under the namespaced declared keyword"))))

;; ===========================================================================
;; rf2-jlufhn — fragment fails closed for non-string values
;; ===========================================================================
;;
;; Spec 012 §Fragments: match-url returns a <string-or-nil> fragment, so a
;; non-string fragment has no round-trippable form. The prior 4-arity gate
;; `(and fragment (not= "" fragment))` host-stringified numbers/keywords into
;; bogus URL identity and SILENTLY ELIDED a `false` fragment (it is falsy).

(defn- thrown-route-url-frag
  [route-id path-params query-params fragment]
  (try
    (routing/route-url route-id path-params query-params fragment)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e)))

(deftest route-url-non-string-fragment-fails-closed
  (testing "a non-string fragment (number, keyword, boolean, function, host
            object) fails closed with :rf.error/route-url-non-edn-value before
            any URL is built — it is NOT host-stringified or truthiness-elided"
    (rf/reg-route :route/frag {} "/frag")
    (doseq [[label v] [[:number 42]
                       [:keyword :section]
                       ;; the motivating trap: `false` is a non-string the
                       ;; prior truthiness gate SILENTLY elided as if nil.
                       [:false-boolean false]
                       [:true-boolean true]
                       [:function (fn [_])]
                       [:atom (atom 1)]
                       #?(:clj  [:host-object (Object.)]
                          :cljs [:raw-js-object #js {:a 1}])]]
      (let [ex (thrown-route-url-frag :route/frag {} {} v)]
        (is (some? ex)
            (str "a " (name label) " fragment must throw, not host-stringify/elide"))
        (is (= :rf.error/route-url-non-edn-value (:rf.error/id (ex-data ex)))
            (str "structured :rf.error/id for a " (name label) " fragment"))
        (is (= :fragment (:slot (ex-data ex)))
            (str ":slot is :fragment for a " (name label) " fragment"))))))

(deftest route-url-string-fragment-round-trips
  (testing "the guard is string-only, not no-fragment — a string fragment
            (including one with %-significant characters) round-trips, and nil
            / empty-string fragments remain elided (existing contract)"
    (rf/reg-route :route/fr {} "/fr")
    ;; nil + empty-string elide (no #)
    (is (= "/fr" (routing/route-url :route/fr {} {} nil)))
    (is (= "/fr" (routing/route-url :route/fr {} {} "")))
    ;; a plain string fragment emits + round-trips
    (let [url (routing/route-url :route/fr {} {} "top")]
      (is (= "/fr#top" url))
      (is (= "top" (:fragment (routing/match-url url)))))
    ;; a fragment with spaces / % round-trips byte-exact (the rf2-ede1h.1
    ;; percent-encode/decode symmetry, unchanged by the new guard)
    (let [url (routing/route-url :route/fr {} {} "50% done")]
      (is (= "50% done" (:fragment (routing/match-url url)))
          "a %-significant string fragment round-trips through encode/decode"))))
