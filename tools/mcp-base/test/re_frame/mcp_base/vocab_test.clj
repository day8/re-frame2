(ns re-frame.mcp-base.vocab-test
  "Pins the wire-vocabulary constants (rf2-vw4sq). The marker keys are
  the cross-MCP convention that an agent learns once — a rename here
  is a wire-protocol break. These tests fail loud when that happens."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.mcp-base.vocab :as vocab]))

(deftest rf-mcp-marker-keys-pinned
  (is (= :rf.mcp/overflow      vocab/overflow-key))
  (is (= :rf.mcp/dedup-table   vocab/dedup-table-key))
  (is (= :rf.mcp/diff-from     vocab/diff-from-key))
  (is (= :rf.mcp/cursor-stale  vocab/cursor-stale-reason))
  (is (= :rf.mcp/cache-hit     vocab/cache-hit-key))
  (is (= :rf.mcp/summary       vocab/summary-key)))

(deftest rf-size-elision-keys-pinned
  (is (= :rf.size/large-elided        vocab/large-elided-key))
  (is (= :rf.elision/at               vocab/elision-handle-key))
  (is (= :rf.size/include-large?      vocab/include-large-opt))
  (is (= :rf.size/include-sensitive?  vocab/include-sensitive-opt))
  (is (= :rf.size/include-digests?    vocab/include-digests-opt))
  (is (= :rf.size/threshold-bytes     vocab/threshold-bytes-opt)))

(deftest envelope-indicator-slots-pinned
  ;; Cross-MCP indicator-field vocabulary per Conventions §Cross-MCP
  ;; indicator-field vocabulary (rf2-2499j). The two slots are
  ;; **unqualified** — they ride the tool's own envelope, not under a
  ;; reserved namespace. A drift to `:rf.size/elided-large` or
  ;; `:elided-large?` is a wire-protocol break.
  (is (= :dropped-sensitive vocab/dropped-sensitive-key))
  (is (= :elided-large      vocab/elided-large-key)))

(deftest count-elided-markers-walks-the-payload
  (let [marker {:rf.size/large-elided
                {:path   [:user :pdf]
                 :bytes  102400
                 :type   :string
                 :reason :declared
                 :handle [:rf.elision/at [:user :pdf]]}}]
    ;; Empty / leaf cases — nothing to count.
    (is (= 0 (vocab/count-elided-markers nil)))
    (is (= 0 (vocab/count-elided-markers {})))
    (is (= 0 (vocab/count-elided-markers [])))
    (is (= 0 (vocab/count-elided-markers "string")))
    (is (= 0 (vocab/count-elided-markers 42)))
    (is (= 0 (vocab/count-elided-markers {:ok? true :payload {:a 1 :b [2 3]}})))

    ;; Marker counted at every depth and shape.
    (is (= 1 (vocab/count-elided-markers marker))
        "Top-level single marker counts once.")
    (is (= 1 (vocab/count-elided-markers {:value marker}))
        "Marker nested in a map counts once.")
    (is (= 1 (vocab/count-elided-markers [marker]))
        "Marker nested in a vector counts once.")
    (is (= 2 (vocab/count-elided-markers {:a marker :b marker}))
        "Sibling markers both count.")
    (is (= 3 (vocab/count-elided-markers
               {:slice1 marker
                :slice2 {:nested marker}
                :slice3 [{:deep marker}]}))
        "Markers at mixed depths all count.")

    ;; The marker BODY is not recursed into (marker bodies carry
    ;; `:handle` / `:path` / metadata, not another marker).
    (let [body-with-collision {:rf.size/large-elided
                               {:path [:a :b]
                                :bytes 100
                                :type :string
                                :reason :declared
                                :handle [:rf.elision/at [:a :b]]
                                ;; A pathological marker-shaped value
                                ;; lodged inside the body would still
                                ;; only count the OUTER marker.
                                :extra {:rf.size/large-elided
                                        {:bytes 1}}}}]
      (is (= 1 (vocab/count-elided-markers body-with-collision))
          "Marker body is opaque; nested marker-shape isn't double-counted."))))

(deftest jsonrpc-error-codes-pinned
  (is (= -32700 vocab/code-parse-error))
  (is (= -32600 vocab/code-invalid-request))
  (is (= -32601 vocab/code-method-not-found))
  (is (= -32602 vocab/code-invalid-params))
  (is (= -32603 vocab/code-internal-error)))
