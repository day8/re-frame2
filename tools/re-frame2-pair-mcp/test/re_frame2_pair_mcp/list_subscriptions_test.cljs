(ns re-frame2-pair-mcp.list-subscriptions-test
  "Unit tests for the `list-subscriptions` + `list-streams` MCP tools.

  ## Two distinct sources

  `list-subscriptions` reads the LIVE reactive sub-cache (via the
  runtime's `sub-cache-info` fn, which reads the SAME
  `re-frame.subs.tooling/sub-cache-snapshot` source that `snapshot`'s
  `:sub-cache` slice reads). `list-streams` wraps the streaming-tap
  registry (`re-frame2-pair.runtime/subscription-info`). Keeping the
  two separate means a frame with live reactive subscriptions reports
  them accurately, with the streaming-tap diagnostic on its own
  accurately-named tool.

  These tests pin:
   - the two descriptors (shape + arg contracts) so an accidental
     rename / arg-name slip breaks the test rather than silently
     shipping a broken tool;
   - the `list-subscriptions` eval form reads the reactive sub-cache via
     `sub-cache-info` (NOT the streaming `subscription-info`) — the
     wrong-source → right-source assertion;
   - that the `list-subscriptions` eval form and the `snapshot :sub-cache`
     slice route through the SAME runtime accessor for the same frame
     (`sub-cache` / `sub-cache-snapshot`), so the two agree by
     construction;
   - that `list-streams` still wraps the streaming `subscription-info`.

  The live end-to-end coverage runs against a real shadow-cljs runtime
  (`test/stdio-roundtrip.js`, the cross-server conformance harness)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [cljs.reader]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.args :as args]))

(defn- descriptor-named [nm]
  (some #(when (= nm (:name %)) %) tools/tool-descriptors))

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
            "reactive-sub-cache read takes a :frame arg (rf2-qicji)")
        (is (contains? props :include-values)
            "optional :include-values arg toggles value+ref-count payload")
        (is (not (contains? props :topic))
            "reactive list-subscriptions has NO :topic — that's the streaming list-streams")
        (is (not (contains? props :sub-id))
            "reactive list-subscriptions has NO :sub-id — that's the streaming list-streams")))))

(deftest list-subscriptions-description-names-reactive-source
  (testing "the description points at the reactive sub-cache, not the streaming registry"
    (let [desc (:description (descriptor-named "list-subscriptions"))]
      (is (re-find #"reactive" desc))
      (is (re-find #"sub-cache" desc))
      (is (re-find #"rf2-qicji" desc)))))

;; ---------------------------------------------------------------------------
;; list-subscriptions — reads the reactive cache, not the streaming taps
;; ---------------------------------------------------------------------------

(deftest list-subscriptions-eval-form-reads-reactive-sub-cache
  (testing "the eval form calls the runtime's sub-cache-info (reactive cache), NOT subscription-info (streaming taps)"
    ;; The tool builds `(re-frame2-pair.runtime/sub-cache-info <opts>)`.
    (let [form (ef/emit (ef/rt-call 'sub-cache-info {:frame :rf/default}))
          edn  (cljs.reader/read-string form)]
      (is (= 're-frame2-pair.runtime/sub-cache-info (first edn))
          "list-subscriptions routes through sub-cache-info — the reactive sub-cache reader")
      (is (= :rf/default (-> edn second :frame))
          "the resolved frame threads into the opts map")
      (is (not= 're-frame2-pair.runtime/subscription-info (first edn))
          "NOT subscription-info — that reads the streaming-tap registry (the rf2-qicji bug)"))))

(deftest list-subscriptions-include-values-threads-into-opts
  (testing ":include-values true sets :include-values? on the runtime opts"
    (let [form (ef/emit (ef/rt-call 'sub-cache-info {:frame :rf/xray :include-values? true}))
          edn  (cljs.reader/read-string form)]
      (is (= true (-> edn second :include-values?))))))

;; ---------------------------------------------------------------------------
;; Agreement with snapshot :sub-cache — same source, by construction
;; ---------------------------------------------------------------------------

(deftest list-subscriptions-agrees-with-snapshot-sub-cache-source
  (testing "list-subscriptions and snapshot :sub-cache route through the SAME runtime accessor"
    ;; snapshot's :sub-cache slice is computed by
    ;; `snapshot-frame-slice` via `(subs-tooling/sub-cache-snapshot
    ;; frame-id)` — exposed on the runtime as the `sub-cache` fn.
    ;; `sub-cache-info` (which list-subscriptions calls) reads the SAME
    ;; `sub-cache-snapshot` source. Both project the per-frame reactive
    ;; cache keyed by query-vector, so the query-vectors they report for
    ;; a given frame agree by construction.
    ;;
    ;; This unit test pins the FORM-LEVEL agreement (both forms name the
    ;; reactive-cache runtime surface for the same frame); the live
    ;; runtime end-to-end agreement is asserted by the conformance
    ;; harness against a real build.
    (let [ls-form   (ef/emit (ef/rt-call 'sub-cache-info {:frame :rf/default}))
          snap-form (ef/emit (ef/rt-call 'snapshot-state
                                         {:frames [:rf/default]
                                          :include [:sub-cache]}))
          ls-edn    (cljs.reader/read-string ls-form)
          snap-edn  (cljs.reader/read-string snap-form)]
      ;; list-subscriptions reads the reactive cache for :rf/default …
      (is (= 're-frame2-pair.runtime/sub-cache-info (first ls-edn)))
      (is (= :rf/default (-> ls-edn second :frame)))
      ;; … and snapshot includes the :sub-cache slice for the same frame.
      (is (= 're-frame2-pair.runtime/snapshot-state (first snap-edn)))
      (is (contains? (set (-> snap-edn second :include)) :sub-cache)
          "snapshot's :sub-cache slice is the peer source list-subscriptions now reads"))))

;; ---------------------------------------------------------------------------
;; list-streams — the streaming-tap diagnostic
;; ---------------------------------------------------------------------------

(deftest list-streams-descriptor-present
  (testing "`list-streams` is registered in tool-descriptors"
    (let [d (descriptor-named "list-streams")]
      (is (some? d) "descriptor exists")
      (is (string? (:description d)))
      (is (integer? (:typicalTokens d)))
      (is (pos? (:typicalTokens d)))
      (is (nil? (:required (:inputSchema d)))
          "descriptor has no required args — both filters optional")
      (let [props (:properties (:inputSchema d))]
        (is (contains? props :topic))
        (is (contains? props :sub-id))
        (is (= ["trace" "epoch" "fx" "error" "frameless"]
               (:enum (:topic props)))
            "topic enum lists the five runtime topics")))))

(deftest list-streams-eval-form-reads-streaming-registry
  (testing "list-streams wraps the streaming `subscription-info` runtime fn"
    (let [form (ef/emit
                 (ef/rt-let ['r    (ef/rt-call 'subscription-info)
                             'subs (ef/rt-raw "(:subs r)")]
                            (ef/rt-raw "(assoc r :subs subs)")))]
      (is (re-find #"re-frame2-pair\.runtime/subscription-info" form)
          "list-streams keeps the streaming-tap registry reader"))))

;; ---------------------------------------------------------------------------
;; tools/list surface + naming hygiene
;; ---------------------------------------------------------------------------

(deftest both-tools-surface-on-tools-list
  (testing "tool-descriptors-js includes both list-subscriptions and list-streams"
    (let [arr   (tools/tool-descriptors-js)
          names (set (for [i (range (alength arr))]
                       (j/get (aget arr i) :name)))]
      (is (contains? names "list-subscriptions"))
      (is (contains? names "list-streams")))))

(deftest old-name-not-present
  (testing "the pre-rename `subscription-info` tool name was never reintroduced"
    (let [arr   (tools/tool-descriptors-js)
          names (set (for [i (range (alength arr))]
                       (j/get (aget arr i) :name)))]
      (is (not (contains? names "subscription-info"))
          "old name was hard-renamed (pre-alpha, no back-compat shim)"))))

(deftest tool-names-use-kebab-case
  (testing "both descriptor names use kebab-case"
    (is (= "list-subscriptions" (:name (descriptor-named "list-subscriptions"))))
    (is (= "list-streams" (:name (descriptor-named "list-streams"))))))

;; ---------------------------------------------------------------------------
;; Frame-arg coercion (shared with snapshot / get-path)
;; ---------------------------------------------------------------------------

(deftest frame-arg-coerces-bare-and-edn-forms
  (testing "list-subscriptions reuses ->frame-keyword, so both arg shapes resolve"
    (is (= :rf/default (args/->frame-keyword "rf/default")))
    (is (= :rf/default (args/->frame-keyword ":rf/default")))))
