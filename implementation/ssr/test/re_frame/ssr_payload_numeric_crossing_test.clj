(ns re-frame.ssr-payload-numeric-crossing-test
  "rf2-3x7nj.13.3 — the hydration payload, and every streaming delta, obey the
  numeric crossing rule the root manifest and the ssr-node render-state wire
  already enforce.

  On a JVM host the payload is `pr-str`'d and read back by the browser's EDN
  reader, and for a Long past 2^53, a BigInt, a BigDecimal, a Ratio or a Float
  that read SUCCEEDS WITH A DIFFERENT VALUE (`9007199254740993` reads back as
  `9007199254740992`, `1/3` as `0.3333333333333333`). The server reads its
  own value back perfectly and the render hash often agrees, so nothing else
  catches it. `build-payload` (shared by both SSR paths) and `project-delta`
  now refuse such a number with `:rf.error/ssr-hydration-payload-invalid`,
  naming the partition, the path and the class — fail closed, always on.

  NaN, infinities, `#inst` and `#uuid` read back as what they were and keep
  riding: the rule is the manifest's TYPE / RANGE rule, not its NaN clause
  and not `edn-carryable?` wholesale."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.streaming :as rf.ssr.streaming]
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
  (testing "each of these used to ship untouched and narrow silently in the
            browser"
    (doseq [[v class-name] [[10.50M               "java.math.BigDecimal"]
                            [9007199254740993    "java.lang.Long"]
                            [-9007199254740993   "java.lang.Long"]
                            [9007199254740993N   "clojure.lang.BigInt"]
                            [(biginteger 5)      "java.math.BigInteger"]
                            [1/3                 "clojure.lang.Ratio"]
                            [(float 0.1)         "java.lang.Float"]]]
      (let [data (app-db-refusal {:ok 1 :price v})]
        (is (= :rf.error/ssr-hydration-payload-invalid (:rf.error/id data))
            (str (pr-str v) " was not refused"))
        (is (= :rf/app-db (:partition data)))
        (is (= [:price] (:path data)))
        (is (= class-name (:class data)))
        (is (= :narrow-the-value-or-drop-the-key (:recovery data)))
        (testing "the message names the partition, the path and the class —
                  on the streaming path only the message survives"
          (is (re-find #":rf/app-db" (::message data)))
          (is (re-find #"\[:price\]" (::message data)))
          (is (.contains ^String (::message data) class-name))
          (is (.endsWith ^String (::message data)
                         "[:rf.error/ssr-hydration-payload-invalid]")
              "the greppability token survives into the writer-failed record"))))))

(deftest the-path-runs-from-the-partition-root
  (testing "a nested value names its full path"
    (let [data (app-db-refusal {:orders {37 {:lines [{:unit-price 1.5}
                                                     {:unit-price 2.0}
                                                     {:unit-price 3.25M}]}}})]
      (is (= [:orders 37 :lines 2 :unit-price] (:path data)))
      (is (= "java.math.BigDecimal" (:class data)))
      (is (not (contains? data :half)))))

  (testing "a map KEY names the map holding it, with :half :key — an app-db
            keyed by a wide entity id narrows just as silently"
    (let [data (app-db-refusal {:orders-by-id {9007199254740993 {:x 1}}})]
      (is (= :rf.error/ssr-hydration-payload-invalid (:rf.error/id data)))
      (is (= [:orders-by-id] (:path data)))
      (is (= :key (:half data)))))

  (testing "a set member names the set holding it"
    (let [data (app-db-refusal {:shares #{1/2}})]
      (is (= [:shares] (:path data)))
      (is (= "clojure.lang.Ratio" (:class data))))))

(deftest the-runtime-db-slice-is-walked-too
  (let [data (refusal #(rf.ssr.payload-policy/build-payload
                         nil {} nil
                         {:runtime-db {:rf.runtime/machines {:m {:data {:total 1.5M}}}}}))]
    (is (= :rf.error/ssr-hydration-payload-invalid (:rf.error/id data)))
    (is (= :rf/runtime-db (:partition data)))
    (is (= [:rf.runtime/machines :m :data :total] (:path data)))))

(deftest numbers-the-browser-reads-back-unchanged-ride
  (testing "controls — the in-domain twins, the NaN and infinities the manifest
            refuses only for round-trip EQUALITY, and the tagged literals the
            reader reconstructs"
    (let [inst #inst "2026-09-24T00:00:00.000-00:00"
          uuid #uuid "00000000-0000-0000-0000-000000000001"
          db   {:price    10.5
                :id       9007199254740991
                :neg-id   -9007199254740991
                :money    "10.50"
                :int      (int 7)
                :nan      ##NaN
                :inf      ##Inf
                :ninf     ##-Inf
                :at       inst
                :uid      uuid
                :by-id    {42 {:n 1}}
                :tags     #{1 2}
                :rows     [1 2.5 "3"]
                :nested   (list {:a 1})}
          payload (rf.ssr.payload-policy/build-payload nil db nil {:runtime-db {:n 1}})]
      (is (identical? db (:rf/app-db payload))
          "the slice rides unchanged, not a copy")
      (is (= {:n 1} (:rf/runtime-db payload))))

    (testing "a redacted slice (the fail-closed frame path) is a keyword"
      (is (= :rf/redacted
             (:rf/app-db (rf.ssr.payload-policy/build-payload nil :rf/redacted nil {})))))))

;; ---- the streaming delta ----------------------------------------------------

(def ^:private sframe :rf.numeric-crossing/server)

(defn- reg-server-frame! []
  (rf/reg-event :rf.numeric-crossing/seed (fn [_ _] {:db {}}))
  (rf/make-frame {:id sframe :platform :server
                  :initial-events [[:rf.numeric-crossing/seed]]}))

(deftest a-streaming-delta-obeys-the-same-rule
  (testing "project-delta, driven with a live server frame and a real
            allowlist, refuses a wide id and a decimal on an allowlisted key"
    (reg-server-frame!)
    (let [data (refusal #(rf/with-frame sframe
                           (rf.ssr.streaming/project-delta
                             {:order {:id 9007199254740993 :price 10.50M}}
                             sframe {:payload [:order]})))]
      (is (= :rf.error/ssr-hydration-payload-invalid (:rf.error/id data)))
      (is (= :rf/app-db (:partition data)))
      (is (contains? #{[:order :id] [:order :price]} (:path data)))))

  (testing "the allowlist still runs first — an off-allowlist key never
            reaches the check, so it cannot fail the delta"
    (reg-server-frame!)
    (is (= {:public {:n 1}}
           (rf/with-frame sframe
             (rf.ssr.streaming/project-delta
               {:public {:n 1} :internal {:big 9007199254740993N}}
               sframe {:payload [:public]}))))))
