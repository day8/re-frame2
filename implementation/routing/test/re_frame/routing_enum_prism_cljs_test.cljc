(ns re-frame.routing-enum-prism-cljs-test
  "Cross-host round-trip tests for the keyword-enum leg of the route PRISM
  (rf2-dcmkke, EP-0012 §Route Prism Laws). The companion JVM-only example
  cases live in `routing_registry_test.clj` (`rf2-dcmkke-*`); THIS file is
  `*-cljs-test.cljc` so the shadow-cljs `:node-test` build (`cljs-test$`)
  ALSO exercises the keyword-enum round-trip on the CLJS host — the bug was a
  host-`(str :asc)` -> `%3Aasc` emission, and the cross-host conformance bar
  (Spec 000 Goal 2) requires the prism leg to hold identically on both hosts.

  ## The defect (rf2-dcmkke)

  `route-url` serialized a keyword enum value with host `(str v)`, so a route
  declaring `:query [:map [:sort [:enum :asc :desc]]]` emitted `:asc` as
  `%3Aasc`. `match-url`'s enum decoder
  (`[:rf.route/enum-keyword #{\"asc\" \"desc\"}]`) recognises only the declared
  TOKEN NAMES (`asc`, `desc`), so `%3Aasc` decoded back to the STRING `\":asc\"`
  — `match-url(route-url(...))` did NOT recover the canonical enum keyword.
  Spec 012 §924-936 pins `[:enum :asc :desc]` to the wire form `sort=desc`
  decoded to `{:sort :desc}`. The fix maps a declared keyword-enum value to its
  schema token name on emission (query AND path), the exact inverse of the
  decode, so `:asc` emits `asc` and round-trips.

  `re-frame.schemas` is required so the late-bind validation hooks are
  published on BOTH hosts (schemas/src is on the node-test classpath), letting
  the invalid-value-fails-validation assertion fire identically."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.routing :as routing]
   [re-frame.schemas]
   [re-frame.test-support :as test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn routing/reset-counters!}))

(deftest keyword-enum-query-round-trips-cross-host
  (testing "a keyword-enum :query value emits its token name and round-trips
            back to the canonical keyword on BOTH hosts"
    (rf/reg-route :route/sorted
                  {:query [:map [:sort [:enum :asc :desc]]]} "/items")
    (doseq [kw [:asc :desc]]
      (let [u (routing/route-url :route/sorted {} {:sort kw})
            m (routing/match-url u)]
        (is (= (str "/items?sort=" (name kw)) u)
            (str "enum keyword " kw " emits its token name (not %3A…)"))
        (is (= kw (get-in m [:query :sort]))
            (str "match-url recovers the canonical enum keyword " kw))
        (is (false? (:validation-failed? m))
            "the round-tripped value conforms to the [:enum …] schema")))))

(deftest keyword-enum-path-round-trips-cross-host
  (testing "a keyword-enum PATH param emits its token name and round-trips
            on BOTH hosts"
    (rf/reg-route :route/sort-path
                  {:params [:map [:dir [:enum :asc :desc]]]} "/items/:dir")
    (let [u (routing/route-url :route/sort-path {:dir :desc})
          m (routing/match-url u)]
      (is (= "/items/desc" u)
          "path enum keyword :desc emits `desc`, not %3Adesc")
      (is (= :route/sort-path (:route-id m)))
      (is (= :desc (get-in m [:params :dir]))
          "match-url recovers the canonical enum keyword on the path side")
      (is (false? (:validation-failed? m))))))

(deftest keyword-enum-query-retain-round-trips-cross-host
  (testing "a `:query-retain` enum value carried as a KEYWORD into a target
            route-url round-trips (match-url interned it; route-url re-emits
            its token name) on BOTH hosts"
    (rf/reg-route :route/list
                  {:query        [:map [:sort [:enum :asc :desc]]]
                   :query-retain [:sort]} "/list")
    (let [retained (get-in (routing/match-url "/list?sort=asc") [:query :sort])]
      (is (= :asc retained)
          "the retained value is the coerced KEYWORD, not a string")
      (let [u (routing/route-url :route/list {} {:sort retained})]
        (is (= "/list?sort=asc" u)
            "the retained keyword re-emits as its token name")
        (is (= :asc (get-in (routing/match-url u) [:query :sort])))))))

(deftest invalid-keyword-enum-fails-validation-cross-host
  (testing "an INVALID keyword-enum value is NOT stringified into a URL —
            route-url fails validation on BOTH hosts (the schema bites)"
    (rf/reg-route :route/sorted2
                  {:query [:map [:sort [:enum :asc :desc]]]} "/items")
    (let [ex (try (routing/route-url :route/sorted2 {} {:sort :sideways})
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e))]
      (is (some? ex)
          "an out-of-enum keyword value raises rather than emitting a URL")
      (is (= :rf.error/route-url-validation (:rf.error/id (ex-data ex)))
          "the structured validation error fires (not a stringified URL)"))))
