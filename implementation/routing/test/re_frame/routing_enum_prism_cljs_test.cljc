(ns re-frame.routing-enum-prism-cljs-test
  "Cross-host round-trip tests for the keyword-enum leg of the route PRISM
  (EP-0012 §Route Prism Laws; Spec 000 Goal 2 requires the leg to hold
  identically on both hosts). Named `*-cljs-test.cljc` so the JVM runner and
  the `:node-test` build both run it.

  `match-url`'s enum decoder recognises only the declared TOKEN NAMES, so a
  keyword-enum value emitted with host `(str v)` — `:asc` as `%3Aasc` — would
  decode back to the STRING `\":asc\"`. Spec 012 pins `[:enum :asc :desc]` to
  the wire form `sort=desc` decoded to `{:sort :desc}`, so `route-url` emits a
  declared keyword-enum value as its token name, in the query and the path.

  An UNBOUNDED `:keyword` slot has no such inverse — `match-url` keeps the
  segment a string, which then fails the route's own `:keyword` schema — so
  `reg-route` rejects it fail-loud.

  `re-frame.schemas` is required so the late-bind validation hooks are
  published on both hosts, which the out-of-enum assertion needs."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.routing :as rf.routing]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn rf.routing/reset-counters!}))

(defn- thrown
  "Call `f` and return the ExceptionInfo it throws, or nil."
  [f]
  (try (f) nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e)))

(deftest keyword-enum-query-round-trips-cross-host
  (rf/reg-route :route/sorted {:query [:map [:sort [:enum :asc :desc]]]} "/items")
  (testing "a keyword-enum :query value emits its token name and round-trips
            back to the canonical keyword"
    (let [u (rf.routing/route-url {:to :route/sorted :query {:sort :desc}})]
      (is (= "/items?sort=desc" u) "the token name, not a host-stringified %3Adesc")
      (is (= {:route-id :route/sorted :query {:sort :desc} :validation-failed? false}
             (select-keys (rf.routing/match-url u) [:route-id :query :validation-failed?])))))
  (testing "an out-of-enum keyword fails validation instead of being stringified"
    (is (= :rf.error/route-url-validation
           (:rf.error/id (ex-data (thrown #(rf.routing/route-url {:to    :route/sorted
                                                                   :query {:sort :sideways}}))))))))

(deftest keyword-enum-path-round-trips-cross-host
  (rf/reg-route :route/sort-path {:params [:map [:dir [:enum :asc :desc]]]} "/items/:dir")
  (let [u (rf.routing/route-url {:to :route/sort-path :params {:dir :desc}})]
    (is (= "/items/desc" u))
    (is (= {:route-id :route/sort-path :params {:dir :desc} :validation-failed? false}
           (select-keys (rf.routing/match-url u) [:route-id :params :validation-failed?])))))

(deftest qualified-keyword-enum-round-trips-cross-host
  (rf/reg-route :route/q-sorted {:query [:map [:sort {:optional true} [:enum :sort/asc :sort/desc]]]} "/items")
  (rf/reg-route :route/q-tab {:params [:map [:tab [:enum :tab/info :tab/edit]]]} "/t/:tab")
  (rf/reg-route :route/q-twin {:query [:map [:pick [:enum :a/x :b/x]]]} "/twin")
  (testing "a qualified choice keeps its namespace on the wire — percent-encoded
            like a qualified query key — and decodes back to itself, so two
            choices sharing a bare name stay distinct"
    (doseq [[address url slot]
            [[{:to :route/q-sorted :query {:sort :sort/desc}} "/items?sort=sort%2Fdesc" :query]
             [{:to :route/q-tab :params {:tab :tab/edit}}     "/t/tab%2Fedit"           :params]
             [{:to :route/q-twin :query {:pick :a/x}}         "/twin?pick=a%2Fx"        :query]
             [{:to :route/q-twin :query {:pick :b/x}}         "/twin?pick=b%2Fx"        :query]]]
      (is (= [url {:route-id (:to address) slot (get address slot) :validation-failed? false}]
             (let [u (rf.routing/route-url address)]
               [u (select-keys (rf.routing/match-url u) [:route-id slot :validation-failed?])]))))))

(deftest bare-keyword-route-slot-rejected-at-reg-route-rf2-qot6ii
  (testing "an unbounded :keyword slot is rejected at reg-route in either schema
            slot, bare, optioned or wrapped in [:maybe]"
    (doseq [[id metadata path slot param]
            [[:route/kw1 {:params [:map [:x :keyword]]}                "/kw/:x" :params :x]
             [:route/kw2 {:query  [:map [:sort :keyword]]}             "/kw"    :query  :sort]
             [:route/kw3 {:params [:map [:x [:keyword {:min 1}]]]}     "/kw/:x" :params :x]
             [:route/kw4 {:query  [:map [:sort [:maybe :keyword]]]}    "/kw"    :query  :sort]]]
      (is (= {:rf.error/id :rf.error/route-keyword-unbounded-unsupported
              :route-id    id
              :slot        slot
              :param       param}
             (select-keys (ex-data (thrown #(rf/reg-route id metadata path)))
                          [:rf.error/id :route-id :slot :param]))
          (pr-str metadata)))))
