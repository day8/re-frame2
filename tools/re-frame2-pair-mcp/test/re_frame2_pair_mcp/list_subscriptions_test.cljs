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
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [cljs.reader]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.list-streams :as ls]
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

;; ---------------------------------------------------------------------------
;; Degraded-eval contract (rf2-21vvfs) — a blank/non-map eval must NOT be
;; fabricated into a fake `{:ok? true :subs []}` "everything fine, zero
;; streams" answer on the very tools that diagnose a dead/quiet stream. A
;; non-map surfaces as `:unexpected-shape` err-text (isError:true); a
;; genuinely-empty read (the runtime's own `{:ok? true :subs []}` map) is
;; unaffected.
;; ---------------------------------------------------------------------------

(deftest list-streams-blank-eval-is-iserror-not-fabricated-empty
  ;; The narrow race: the browser tab closes/navigates between the
  ;; liveness re-check and the drain eval, so `cljs-eval-value` reads a
  ;; blank shadow result as nil. The tool MUST surface that degraded read
  ;; as an error, not manufacture "zero streams".
  (async done
    (-> (tu/with-stubbed-eval! nil
          (fn [] (ls/list-streams-tool (fresh-conn) #js {})))
        (.then (fn [r]
                 (is (true? (tu/error? r))
                     "a blank/non-map eval rides isError:true, not a fabricated empty success")
                 (let [edn (tu/extract-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :unexpected-shape (:reason edn))
                       "the degraded read is :unexpected-shape, not a fake :subs []"))
                 (done))))))

(deftest list-subscriptions-blank-eval-is-iserror-not-fabricated-empty
  (async done
    (-> (tu/with-stubbed-eval! nil
          (fn [] (lsub/list-subscriptions-tool (fresh-conn) #js {})))
        (.then (fn [r]
                 (is (true? (tu/error? r))
                     "a blank/non-map eval rides isError:true, not a fabricated empty success")
                 (let [edn (tu/extract-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :unexpected-shape (:reason edn))))
                 (done))))))

(deftest list-streams-genuine-empty-stays-ok
  ;; Non-regression: a genuinely-empty listing is the runtime's own
  ;; `{:ok? true :subs []}` MAP — a real success. `map-envelope-result`
  ;; only diverts a non-map or an explicit `:ok? false`, so real emptiness
  ;; must still ride as a non-error success.
  (async done
    (-> (tu/with-stubbed-eval! {:ok? true :subs []}
          (fn [] (ls/list-streams-tool (fresh-conn) #js {})))
        (.then (fn [r]
                 (is (not (tu/error? r))
                     "an empty-but-ok listing is a success, not an error")
                 (let [edn (tu/extract-edn r)]
                   (is (true? (:ok? edn)))
                   (is (= [] (:subs edn))))
                 (done))))))

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
