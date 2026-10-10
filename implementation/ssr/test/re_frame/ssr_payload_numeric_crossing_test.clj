(ns re-frame.ssr-payload-numeric-crossing-test
  "The hydration payload obeys the numeric crossing rule the root manifest
  enforces. On a JVM host the payload is `pr-str`'d and
  read back by the browser's EDN reader, which reads a Long past 2^53, a
  BigInt, a BigDecimal, a Ratio or a Float back as a DIFFERENT value — and the
  server reads its own value back perfectly, so nothing else catches it.
  `build-payload` refuses such a number with
  `:rf.error/ssr-hydration-payload-invalid`, always on.

  The per-class verdicts belong to `wire/portable-number?` and are pinned
  in `re-frame.ssr.wire-cljs-test`; NaN, infinities, `#inst` and
  `#uuid` read back as what they were and ride."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(defn- refusal
  "The ex-data `thunk` throws, or nil when it returns."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (assoc (ex-data e) ::message (ex-message e)))))

(defn- app-db-refusal [db]
  (refusal #(rf.ssr.payload-policy/build-payload nil db nil {})))

(deftest a-jvm-only-number-is-refused-in-the-app-db-slice
  (let [data (app-db-refusal {:ok 1 :price 10.50M})]
    (is (= {:rf.error/id :rf.error/ssr-hydration-payload-invalid
            :partition   :rf/app-db
            :path        [:price]
            :class       "java.math.BigDecimal"
            :recovery    :narrow-the-value-or-drop-the-key}
           (select-keys data [:rf.error/id :partition :path :class :recovery])))
    ;; The message names the partition, the path and the class too.
    (is (every? #(.contains ^String (::message data) %)
                [":rf/app-db" "[:price]" "java.math.BigDecimal"])))
  (testing "a BigInteger, which the manifest's tests do not reach"
    (is (= {:rf.error/id :rf.error/ssr-hydration-payload-invalid :class "java.math.BigInteger"}
           (select-keys (app-db-refusal {:n (biginteger 5)}) [:rf.error/id :class])))))

(deftest the-path-runs-from-the-partition-root
  (testing "a nested value names its full path"
    (is (= {:path [:orders 37 :lines 2 :unit-price] :class "java.math.BigDecimal"}
           (select-keys (app-db-refusal {:orders {37 {:lines [{:unit-price 1.5}
                                                             {:unit-price 2.0}
                                                             {:unit-price 3.25M}]}}})
                        [:path :class :half]))))
  (testing "a map KEY names the map holding it, with :half :key"
    (is (= {:rf.error/id :rf.error/ssr-hydration-payload-invalid :path [:orders-by-id] :half :key}
           (select-keys (app-db-refusal {:orders-by-id {9007199254740993 {:x 1}}})
                        [:rf.error/id :path :half]))))
  (testing "a set member names the set holding it"
    (is (= {:path [:shares] :class "clojure.lang.Ratio"}
           (select-keys (app-db-refusal {:shares #{1/2}}) [:path :class])))))

(deftest the-runtime-db-slice-is-walked-too
  (is (= {:rf.error/id :rf.error/ssr-hydration-payload-invalid
          :partition   :rf/runtime-db
          :path        [:rf.runtime/machines :m :data :total]}
         (select-keys (refusal #(rf.ssr.payload-policy/build-payload
                                  nil {} nil
                                  {:runtime-db {:rf.runtime/machines {:m {:data {:total 1.5M}}}}}))
                      [:rf.error/id :partition :path]))))

(deftest numbers-the-browser-reads-back-unchanged-ride
  (let [db      {:price  10.5
                 :id     9007199254740991
                 :int    (int 7)
                 :nan    ##NaN
                 :inf    ##Inf
                 :at     #inst "2026-09-24T00:00:00.000-00:00"
                 :uid    #uuid "00000000-0000-0000-0000-000000000001"
                 :by-id  {42 {:n 1}}
                 :tags   #{1 2}
                 :rows   [1 2.5 "3"]
                 :nested (list {:a 1})}
        payload (rf.ssr.payload-policy/build-payload nil db nil {:runtime-db {:n 1}})]
    ;; `identical?`, because NaN is not `=` to itself.
    (is (identical? db (:rf/app-db payload)))
    (is (= {:n 1} (:rf/runtime-db payload)))))
