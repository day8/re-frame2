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
      (let [u (routing/route-url {:to :route/sorted :params {} :query {:sort kw}})
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
    (let [u (routing/route-url {:to :route/sort-path :params {:dir :desc}})
          m (routing/match-url u)]
      (is (= "/items/desc" u)
          "path enum keyword :desc emits `desc`, not %3Adesc")
      (is (= :route/sort-path (:route-id m)))
      (is (= :desc (get-in m [:params :dir]))
          "match-url recovers the canonical enum keyword on the path side")
      (is (false? (:validation-failed? m))))))

(deftest keyword-enum-carried-query-value-round-trips-cross-host
  (testing "an enum query value carried as a KEYWORD into a target route-url
            round-trips (match-url interned it; route-url re-emits its token
            name) on BOTH hosts. EP-0037 R5: the carry is the application's
            explicit fold over the destination address."
    (rf/reg-route :route/list
                  {:query [:map [:sort [:enum :asc :desc]]]} "/list")
    (let [carried (get-in (routing/match-url "/list?sort=asc") [:query :sort])]
      (is (= :asc carried)
          "the carried value is the coerced KEYWORD, not a string")
      (let [u (routing/route-url {:to :route/list :params {} :query {:sort carried}})]
        (is (= "/list?sort=asc" u)
            "the carried keyword re-emits as its token name")
        (is (= :asc (get-in (routing/match-url u) [:query :sort])))))))

(deftest invalid-keyword-enum-fails-validation-cross-host
  (testing "an INVALID keyword-enum value is NOT stringified into a URL —
            route-url fails validation on BOTH hosts (the schema bites)"
    (rf/reg-route :route/sorted2
                  {:query [:map [:sort [:enum :asc :desc]]]} "/items")
    (let [ex (try (routing/route-url {:to :route/sorted2 :params {} :query {:sort :sideways}})
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e))]
      (is (some? ex)
          "an out-of-enum keyword value raises rather than emitting a URL")
      (is (= :rf.error/route-url-validation (:rf.error/id (ex-data ex)))
          "the structured validation error fires (not a stringified URL)"))))

;; ---- the BARE (unbounded) :keyword sibling: rejected at reg-route ---------
;;
;; The deftests above pin the BOUNDED `[:enum …]` keyword prism (interns via
;; the allowlist, round-trips). A BARE (unbounded) `:keyword`-typed :params /
;; :query slot is the un-round-trippable sibling (rf2-qot6ii): `route-url`
;; host-stringifies the keyword value (`:asc` → `%3Aasc`), but `match-url`
;; keeps the URL segment a STRING (the rf2-3k3o7 keyword-interning guard),
;; which then FAILS the route's own `:keyword` schema — so `route-url` builds
;; a URL that fails the SAME route's re-match. Like the `:double` precedent,
;; it is rejected fail-loud at reg-route (`reject-keyword-route-schema!`), NOT
;; silently accepted. `[:enum …]` keyword slots stay supported.

(deftest bare-keyword-route-slot-rejected-at-reg-route-rf2-qot6ii
  (testing "a bare / optioned (unbounded) :keyword :params or :query slot is
            rejected fail-loud at reg-route on BOTH hosts; a bounded [:enum …]
            keyword slot is admitted"
    (letfn [(reg-throws [id metadata path]
              (try
                (rf/reg-route id metadata path)
                nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e)))]

      (testing "a bare :keyword PATH param is rejected"
        (let [ex (reg-throws :route/kw1 {:params [:map [:x :keyword]]} "/kw/:x")]
          (is (some? ex) "reg-route with a bare :keyword :params key must throw")
          (is (= :rf.error/route-keyword-unbounded-unsupported
                 (:rf.error/id (ex-data ex)))
              "the structured discriminator is :rf.error/route-keyword-unbounded-unsupported")
          (is (re-find #"\[:rf\.error/route-keyword-unbounded-unsupported\]"
                       (ex-message ex))
              "the message carries the greppable [:rf.error/…] token")
          (is (re-find #"\[:enum" (ex-message ex))
              "the message steers the author to [:enum …]")
          (let [data (ex-data ex)]
            (is (= :route/kw1 (:route-id data)))
            (is (= :params (:slot data)))
            (is (= :x (:param data))))))

      (testing "a bare :keyword QUERY key is rejected — the :query slot is scanned"
        (let [ex (reg-throws :route/kw2 {:query [:map [:sort :keyword]]} "/kw")]
          (is (= :rf.error/route-keyword-unbounded-unsupported
                 (:rf.error/id (ex-data ex))))
          (is (= :query (:slot (ex-data ex))))
          (is (= :sort (:param (ex-data ex))))))

      (testing "an OPTIONED [:keyword {…}] slot is rejected the same way — the
                properties map does not launder the unbounded type"
        (let [ex (reg-throws :route/kw3 {:params [:map [:x [:keyword {:min 1}]]]} "/kw/:x")]
          (is (= :rf.error/route-keyword-unbounded-unsupported
                 (:rf.error/id (ex-data ex))))))

      (testing "a [:maybe :keyword] slot is rejected (the wrapper is unwrapped)"
        (let [ex (reg-throws :route/kw4 {:query [:map [:sort [:maybe :keyword]]]} "/kw")]
          (is (= :rf.error/route-keyword-unbounded-unsupported
                 (:rf.error/id (ex-data ex))))))

      (testing "a BOUNDED [:enum :a :b] keyword slot is NOT rejected — it
                round-trips via the enum prism (the deftests above)"
        (is (nil? (reg-throws :route/kw5 {:query [:map [:sort [:enum :asc :desc]]]} "/kw5"))
            "[:enum …] :query reg-route does not throw")
        (is (nil? (reg-throws :route/kw6 {:params [:map [:x [:enum :asc :desc]]]} "/kw6/:x"))
            "[:enum …] :params reg-route does not throw")))))
