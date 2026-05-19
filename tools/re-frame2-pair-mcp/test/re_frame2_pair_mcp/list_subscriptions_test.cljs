(ns re-frame2-pair-mcp.list-subscriptions-test
  "Unit tests for the `list-subscriptions` MCP tool (rf2-zjz9q;
  renamed from `subscription-info` per rf2-4y595 — NAMING.md
  `list-<things>` shape).

  The MCP server builds a CLJS form that calls
  `re-frame2-pair.runtime/subscription-info` (the runtime fn keeps its
  historical name) and optionally filters by `:topic` / `:sub-id`
  server-side. The live end-to-end coverage runs against a real
  shadow-cljs runtime; these tests pin the wire-form shape and the
  descriptor contract so accidental renames or arg-name slips break
  the test rather than silently shipping a broken tool."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.tools :as tools]))

(deftest descriptor-present
  (testing "`list-subscriptions` is registered in tool-descriptors"
    (let [d (some #(when (= "list-subscriptions" (:name %)) %)
                  tools/tool-descriptors)]
      (is (some? d) "descriptor exists")
      (is (string? (:description d)))
      (is (integer? (:typicalTokens d)))
      (is (pos? (:typicalTokens d)))
      ;; Optional args — no `:required` slot.
      (is (nil? (:required (:inputSchema d)))
          "descriptor has no required args — both filters optional")
      (let [props (:properties (:inputSchema d))]
        (is (contains? props :topic))
        (is (contains? props :sub-id))
        (is (= ["trace" "epoch" "fx" "error"]
               (:enum (:topic props)))
            "topic enum lists the four runtime topics")))))

(deftest descriptor-surfaces-on-tools-list
  (testing "list-subscriptions shows up in tool-descriptors-js"
    (let [arr   (tools/tool-descriptors-js)
          names (set (for [i (range (alength arr))]
                       (j/get (aget arr i) :name)))]
      (is (contains? names "list-subscriptions")
          "tool-descriptors-js includes list-subscriptions"))))

(deftest old-name-not-present
  (testing "the pre-rename `subscription-info` tool no longer exists"
    (let [arr   (tools/tool-descriptors-js)
          names (set (for [i (range (alength arr))]
                       (j/get (aget arr i) :name)))]
      (is (not (contains? names "subscription-info"))
          "old name was hard-renamed (pre-alpha, no back-compat shim)"))))

(deftest tool-name-uses-kebab-case
  (testing "the descriptor name uses kebab-case"
    (let [d (some #(when (= "list-subscriptions" (:name %)) %)
                  tools/tool-descriptors)]
      (is (= "list-subscriptions" (:name d))
          "name uses kebab-case, not list_subscriptions / listSubscriptions"))))
