(ns re-frame.mcp-base.dedup-test
  "Tests for the shared wire-boundary dedup encode step.

  Pins the byte-identical forward direction both MCP servers
  delegate to: the `empty-payload?` short-circuit, the cross-MCP wrap
  shape, the opt-out, and round-trip exactness via `de-dupe.core/expand`
  (the inverse the agent host calls — NOT a base surface)."
  (:require [clojure.test :refer [deftest is testing]]
            [de-dupe.core :as dd]
            [re-frame.mcp-base.dedup :as dedup]
            [re-frame.mcp-base.vocab :as vocab]))

(deftest empty-payload?-true-for-no-win-values
  (testing "nil, empty colls, and scalars yield no dedup win"
    (is (true? (boolean (dedup/empty-payload? nil))))
    (is (true? (boolean (dedup/empty-payload? []))))
    (is (true? (boolean (dedup/empty-payload? {}))))
    (is (true? (boolean (dedup/empty-payload? #{}))))
    (is (true? (boolean (dedup/empty-payload? ""))))
    (is (true? (boolean (dedup/empty-payload? 42))))
    (is (true? (boolean (dedup/empty-payload? :a-keyword))))))

(deftest empty-payload?-false-for-non-empty-colls
  (is (false? (boolean (dedup/empty-payload? [1 2 3]))))
  (is (false? (boolean (dedup/empty-payload? {:a 1}))))
  (is (false? (boolean (dedup/empty-payload? #{:x})))))

(deftest dedup-value-disabled-returns-input-verbatim
  (let [v [{:a 1} {:a 1}]]
    (is (identical? v (dedup/dedup-value v false))
        "enabled? false is a verbatim passthrough")))

(deftest dedup-value-empty-payload-returns-input-verbatim
  ;; No dedup opportunity ⇒ skip the wrap (the one-entry cache would be
  ;; slightly LARGER than the input).
  (is (= [] (dedup/dedup-value [] true)))
  (is (= {} (dedup/dedup-value {} true)))
  (is (nil? (dedup/dedup-value nil true)))
  (is (= 42 (dedup/dedup-value 42 true))))

(deftest no-substitutions?-detects-one-entry-root-only-cache
  ;; `de-dupe-eq` always emits `cache-0` for the root; every substituted
  ;; subtree adds a further `cache-N`. A one-entry cache therefore made
  ;; NO substitutions.
  (testing "a no-repeat non-empty collection ⇒ one-entry root-only cache"
    (is (true? (boolean (dedup/no-substitutions? (dd/de-dupe-eq {:a 1 :b 2})))))
    (is (true? (boolean (dedup/no-substitutions? (dd/de-dupe-eq [1 2 3])))))
    (is (true? (boolean (dedup/no-substitutions? (dd/de-dupe-eq {:a {:x 1} :b {:y 2}}))))))
  (testing "a repeated-subtree cache has >1 entry ⇒ substitutions happened"
    (is (false? (boolean (dedup/no-substitutions? (dd/de-dupe-eq [{:x 1} {:x 1}]))))))
  (testing "non-map / empty inputs are not a root-only cache"
    (is (false? (boolean (dedup/no-substitutions? nil))))
    (is (false? (boolean (dedup/no-substitutions? {}))))))

(deftest dedup-value-no-repeats-non-empty-returns-input-verbatim
  ;; The headline fix (rf2-fwaolt): a NON-EMPTY collection with no
  ;; repeated subtrees must stay RAW on the wire — `de-dupe-eq` yields a
  ;; one-entry root-only cache whose wrapped shape is strictly larger than
  ;; the input, violating the documented no-repeat-payloads-stay-raw
  ;; contract. `empty-payload?` does NOT catch these (they're non-empty),
  ;; so before the fix they wrapped and grew.
  (testing "a flat no-repeat map is returned identical, NOT wrapped"
    (let [v {:a 1 :b 2 :c 3}
          out (dedup/dedup-value v true)]
      (is (identical? v out)
          "no dedup opportunity ⇒ verbatim passthrough, not a dedup-table wrap")
      (is (not (contains? out vocab/dedup-table-key)))))
  (testing "a nested no-repeat structure is returned identical"
    (let [v {:user {:name "ada"} :session {:state :idle} :items [1 2 3]}]
      (is (identical? v (dedup/dedup-value v true)))))
  (testing "a no-repeat vector is returned identical"
    (let [v [{:id 1} {:id 2} {:id 3}]]      ; all distinct ⇒ no substitution
      (is (identical? v (dedup/dedup-value v true))))))

(deftest dedup-value-repeated-subtree-still-wraps
  ;; The adversarial complement to the no-op skip: a payload that DOES
  ;; carry a repeated subtree must STILL wrap (the skip must not swallow a
  ;; genuine dedup win). Its cache has >1 entry, so `no-substitutions?` is
  ;; false and the wrap fires.
  (let [shared {:big [:repeated :subtree]}
        v      [shared shared shared]
        out    (dedup/dedup-value v true)]
    (is (map? out))
    (is (contains? out vocab/dedup-table-key)
        "a repeated subtree is a real dedup win — the wrap must fire")
    (is (= v (dd/expand (get out vocab/dedup-table-key)))
        "and the wrap still round-trips")))

(deftest dedup-value-wraps-in-cross-mcp-marker
  (let [shared {:repeated [:big :subtree :here]}
        v      {:a shared :b shared :c shared}
        out    (dedup/dedup-value v true)]
    (testing "the wire shape is the cross-MCP dedup-table marker"
      (is (map? out))
      (is (contains? out vocab/dedup-table-key))
      (is (= #{vocab/dedup-table-key} (set (keys out)))
          "exactly one top-level slot, the dedup-table marker"))))

(deftest dedup-value-round-trips-exactly
  ;; The agent host reconstructs by calling de-dupe.core/expand on the
  ;; cache-map value — pin that the wrap is losslessly reversible.
  (let [shared {:repeated (vec (range 50))}
        v      {:a shared :b shared :c shared :scalars [1 2 3]}
        out    (dedup/dedup-value v true)
        cache  (get out vocab/dedup-table-key)]
    (is (= v (dd/expand cache))
        "expand on the cache-map reconstructs the original structure")))

(deftest dedup-value-uses-equality-not-identity
  ;; Values reconstructed from EDN (re-frame2-pair-mcp) or synthesised
  ;; fresh per call (story-mcp) are equality-shared, not identity-shared.
  ;; `de-dupe-eq` must pool them; build two EQUAL-but-not-IDENTICAL
  ;; subtrees and assert the round-trip still holds (the share fires).
  (let [a   {:k (vec (range 30))}
        b   {:k (vec (range 30))}            ; equal to a, distinct object
        _   (is (not (identical? a b)))
        v   {:left a :right b}
        out (dedup/dedup-value v true)]
    (is (contains? out vocab/dedup-table-key))
    (is (= v (dd/expand (get out vocab/dedup-table-key)))
        "equality-shared subtrees round-trip through the wrap")))
