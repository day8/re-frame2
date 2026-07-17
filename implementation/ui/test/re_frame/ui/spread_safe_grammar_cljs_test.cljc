(ns re-frame.ui.spread-safe-grammar-cljs-test
  "rf2-izep3 — the ONE validated author-space caller-key grammar for
  `ui/spread-safe`, exercised DIRECTLY (host-agnostic). `assert-safe-caller!`,
  `spread-safe-denied-key?`, and `caller-key-name` are pure `.cljc`, so the
  canonicalization + validation + deny law are proven IDENTICAL on the JVM and
  CLJS hosts: this suite runs under both `clojure -M:test` and the node
  `-cljs-test` build. The render-level integration (the deny reached through the
  actual host converters) lives in react-render-cljs-test / parity-corpus-jvm-
  test; the advanced-production reachability rides the -deny-elision-prod-test."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ui.rules :as rules]))

(defn- deny-data [caller owned]
  (try (rules/assert-safe-caller! caller owned)
       nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e))))

(defn- denied? [caller owned]
  (= :rf.error/ui-tree-malformed (:rf.error/id (deny-data caller owned))))

(deftest caller-key-name-canonicalizes
  (testing "nameable keys canonicalize to (name k); namespace dropped"
    (is (= "ref" (rules/caller-key-name :ref)))
    (is (= "ref" (rules/caller-key-name :caller/ref)))
    (is (= "ref" (rules/caller-key-name "ref")))
    (is (= "ref" (rules/caller-key-name 'ref)))
    (is (= "on-change" (rules/caller-key-name :x/on-change))))
  (testing "non-nameable keys have no canonical name"
    (is (nil? (rules/caller-key-name nil)))
    (is (nil? (rules/caller-key-name false)))
    (is (nil? (rules/caller-key-name 5)))))

(deftest spread-safe-denied-key?-compares-canonical-names
  (testing "structural/controlled aliases are denied by canonical name"
    (doseq [k [:key :ref :value :checked
               :caller/ref "ref" 'ref :some/value "value" "checked" :ns/key]]
      (is (rules/spread-safe-denied-key? k #{})
          (str "denied by canonical name: " (pr-str k)))))
  (testing "owned-handler aliases are denied by canonical name"
    (doseq [k [:on-change "on-change" :x/on-change]]
      (is (rules/spread-safe-denied-key? k #{:on-change})
          (str "owned-handler alias denied: " (pr-str k)))))
  (testing "allowed + non-nameable keys are not 'denied' here"
    (doseq [k [:aria-label "data-x" :title :class :style nil false 5]]
      (is (not (rules/spread-safe-denied-key? k #{:on-change}))))))

(deftest assert-safe-caller!-rejects-alternate-spellings
  (testing "structural/controlled aliases (namespaced/string/symbol) are rejected"
    (doseq [k [:key :ref :value :checked
               :caller/ref "ref" 'ref :some/value "value" "checked" :ns/key 'checked]]
      (let [data (deny-data {k "x"} #{})]
        (is (= :rf.error/ui-tree-malformed (:rf.error/id data))
            (str "alias rejected: " (pr-str k)))
        (is (= 're-frame.ui/spread-safe (:where data)))
        (is (= k (:key data)) "the offending author key is carried through"))))
  (testing "owned-handler aliases are rejected against canonical owned names"
    (doseq [k [:on-change "on-change" :x/on-change]]
      (is (denied? {k [:e]} #{:on-change}) (str "owned alias rejected: " (pr-str k))))))

(deftest assert-safe-caller!-rejects-non-map-and-non-nameable
  (testing "a non-map caller (a pair sequence / vector / string) is rejected"
    (is (denied? [[:aria-label "x"]] #{}))
    (is (denied? '([:aria-label "x"]) #{}))
    (is (denied? "not-a-map" #{})))
  (testing "non-nameable keys are rejected"
    (doseq [k [nil false 5 3.0]]
      (is (denied? {k "x"} #{}) (str "non-nameable key rejected: " (pr-str k))))))

(deftest assert-safe-caller!-passes-allowed-callers
  (testing "exact allowed keys pass; the caller is returned unchanged"
    (is (= {:aria-label "n" :data-x "d" :title "t" :class "c" :style {:color "red"}}
           (rules/assert-safe-caller!
            {:aria-label "n" :data-x "d" :title "t" :class "c" :style {:color "red"}}
            #{:on-change}))))
  (testing "nil caller = the empty caller (returns nil, no throw)"
    (is (nil? (rules/assert-safe-caller! nil #{:on-change}))))
  (testing "an empty map passes"
    (is (= {} (rules/assert-safe-caller! {} #{:on-change}))))
  (testing "a caller may name an owned handler that is NOT among owned-handler-keys"
    (is (= {:on-click [:e]} (rules/assert-safe-caller! {:on-click [:e]} #{:on-change})))))
