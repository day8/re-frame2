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
    (rf/reg-route :route/item {:path "/items/:id"})
    (doseq [[label v] adversarial-path-values]
      (let [ex (thrown-route-url :route/item {:id v} {})]
        (is (some? ex)
            (str "a " (name label) " path value must throw, not host-stringify"))
        (is (= ":rf.error/route-url-non-edn-value" (ex-message ex))
            (str "structured error id for a " (name label) " path value"))
        (let [data (ex-data ex)]
          (is (= :route/item (:route-id data)))
          (is (= :params (:slot data)))
          (is (= :id (:param data))))))))

(deftest route-url-query-value-host-value-fails-closed
  (testing "a host value in a (non-nil) query value fails closed the same way
            the path side and the query-key side do"
    (rf/reg-route :route/search {:path "/search"})
    (doseq [[label v] adversarial-path-values]
      (let [ex (thrown-route-url :route/search {} {:q v})]
        (is (some? ex)
            (str "a " (name label) " query value must throw"))
        (is (= ":rf.error/route-url-non-edn-value" (ex-message ex))
            (str "structured error id for a " (name label) " query value"))
        (let [data (ex-data ex)]
          (is (= :route/search (:route-id data)))
          (is (= :query (:slot data)))
          (is (= :q (:param data))))))))

(deftest route-url-admitted-url-scalars-still-emit
  (testing "the guard is a host-value gate, NOT a string-only gate — strings,
            booleans, portable integers, and UUIDs remain admitted so the
            happy path is unaffected"
    (rf/reg-route :route/scalar {:path "/s/:v"})
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
    (rf/reg-route :route/order {:path "/orders/:id" :query [:map [:note :string]]})
    (let [ex (thrown-route-url :route/order {:id (atom :x)} {:note "ok"})]
      (is (= ":rf.error/route-url-non-edn-value" (ex-message ex))
          "a structured error, never a string return value"))))
