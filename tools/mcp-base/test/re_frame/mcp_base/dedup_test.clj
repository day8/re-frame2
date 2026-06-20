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
