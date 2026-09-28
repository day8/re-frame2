(ns re-frame2-pair-mcp.list-subscriptions-test
  "Unit tests for the `list-subscriptions` MCP tool.

  `list-subscriptions` reads the LIVE reactive sub-cache (via the
  runtime's `sub-cache-info` fn, which reads the SAME
  `re-frame.subs.tooling/sub-cache-snapshot` source that `snapshot`'s
  `:sub-cache` slice reads), so a frame with live reactive
  subscriptions reports them accurately and the two surfaces agree by
  construction.

  These tests pin the descriptor (shape + arg contracts) so an
  accidental rename / arg-name slip breaks the test rather than
  silently shipping a broken tool, the retired tool name staying out of
  tools/list, and a runtime refusal riding isError. The blank-eval and
  genuinely-empty envelopes are the conformance corpus's
  `:list-subscriptions/degraded-blank` and `:list-subscriptions/empty`
  fixtures.

  The live end-to-end coverage runs against a real shadow-cljs runtime
  (`test/stdio-roundtrip.js`, the cross-server conformance harness)."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.list-subscriptions :as lsub]))

(defn- descriptor-named [nm]
  (some #(when (= nm (:name %)) %) tools/tool-descriptors))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

;; ---------------------------------------------------------------------------
;; list-subscriptions — reactive sub-cache descriptor
;; ---------------------------------------------------------------------------

(deftest list-subscriptions-descriptor-present
  (testing "`list-subscriptions` is registered in tool-descriptors"
    (let [d (descriptor-named "list-subscriptions")]
      (is (some? d) "descriptor exists")
      (is (string? (:description d)))
      (is (integer? (:typicalTokens d)))
      (is (pos? (:typicalTokens d)))
      (is (nil? (:required (:inputSchema d)))
          "descriptor has no required args — frame defaults to operating frame")
      (let [props (:properties (:inputSchema d))]
        (is (contains? props :frame)
            "reactive-sub-cache read takes a :frame arg")
        (is (contains? props :include-values)
            "optional :include-values arg toggles value+ref-count payload")
        (is (not (contains? props :topic))
            "reactive list-subscriptions has NO :topic arg")
        (is (not (contains? props :sub-id))
            "reactive list-subscriptions has NO :sub-id arg")))))

(deftest list-subscriptions-description-names-reactive-source
  (testing "the description points at the reactive sub-cache"
    (let [desc (:description (descriptor-named "list-subscriptions"))]
      (is (re-find #"reactive" desc))
      (is (re-find #"sub-cache" desc))
      (is (re-find #"never disagree" desc)))))

;; ---------------------------------------------------------------------------
;; tools/list surface + naming hygiene
;; ---------------------------------------------------------------------------

(deftest old-name-not-present
  (testing "no `subscription-info` tool name is registered"
    (let [arr   (tools/tool-descriptors-js)
          names (set (for [i (range (alength arr))]
                       (j/get (aget arr i) :name)))]
      (is (not (contains? names "subscription-info"))
          "`subscription-info` is not a tool name (no back-compat shim)"))))

;; ---------------------------------------------------------------------------
;; Degraded-eval contract — a runtime `:ok? false` refusal rides isError,
;; never a success-shaped listing.
;; ---------------------------------------------------------------------------

(deftest list-subscriptions-runtime-ok-false-is-iserror
  ;; A runtime `{:ok? false :reason :ambiguous-frame}` refusal (multi-frame
  ;; session, no selection) must ride isError too — not the old
  ;; ok-text-wraps-any-map behaviour.
  (async done
    (-> (tu/with-stubbed-eval! {:ok? false :reason :ambiguous-frame}
          (fn [] (lsub/list-subscriptions-tool (fresh-conn) #js {})))
        (.then (fn [r]
                 (is (true? (tu/error? r))
                     "an :ambiguous-frame refusal rides isError:true")
                 (let [edn (tu/extract-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :ambiguous-frame (:reason edn))))
                 (done))))))
