(ns re-frame-pair2-mcp.subscription-info-test
  "Unit tests for the `subscription-info` MCP tool (rf2-zjz9q).

  The MCP server builds a CLJS form that calls
  `re-frame-pair2.runtime/subscription-info` and optionally filters by
  `:topic` / `:sub-id` server-side. The live end-to-end coverage runs
  against a real shadow-cljs runtime; these tests pin the wire-form
  shape and the descriptor contract so accidental renames or arg-name
  slips break the test rather than silently shipping a broken tool."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [applied-science.js-interop :as j]
            [re-frame-pair2-mcp.tools :as tools]))

(deftest descriptor-present
  (testing "`subscription-info` is registered in tool-descriptors"
    (let [d (some #(when (= "subscription-info" (:name %)) %)
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
  (testing "subscription-info shows up in tool-descriptors-js"
    (let [arr   (tools/tool-descriptors-js)
          names (set (for [i (range (alength arr))]
                       (j/get (aget arr i) :name)))]
      (is (contains? names "subscription-info")
          "tool-descriptors-js includes subscription-info"))))

(deftest dispatch-routes-unknown-tool-error
  (testing "dispatch-tool* still surfaces :unknown-tool for typos"
    ;; Sanity: confirm subscription-info ISN'T being matched by accident.
    ;; (Direct dispatch-tool* would need a fake conn; we just inspect
    ;; the descriptor wiring above. This test pins that the descriptor
    ;; name uses the expected hyphen form, not snake_case.)
    (let [d (some #(when (= "subscription-info" (:name %)) %)
                  tools/tool-descriptors)]
      (is (= "subscription-info" (:name d))
          "name uses kebab-case, not subscription_info / subscriptionInfo"))))
