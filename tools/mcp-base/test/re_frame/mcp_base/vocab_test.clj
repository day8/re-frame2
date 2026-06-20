(ns re-frame.mcp-base.vocab-test
  "Pins the wire-vocabulary constants. The marker keys are
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
  (is (= :rf.mcp/summary       vocab/summary-key))
  ;; These two reserved wire-vocabulary markers are pinned too, so a
  ;; rename cannot slip past the base unit gate. :rf.mcp/invalid-arg is
  ;; the per-call arg-rejection wrapper; :rf.mcp/result is the
  ;; wire-fidelity typed eval/handler result envelope that pair-mcp
  ;; actively emits — both are cross-MCP convention an agent learns once.
  (is (= :rf.mcp/invalid-arg   vocab/invalid-arg-key))
  (is (= :rf.mcp/result        vocab/result-key)))

(deftest rf-size-elision-keys-pinned
  (is (= :rf.size/large-elided        vocab/large-elided-key))
  (is (= :rf/redacted                 vocab/redacted-sentinel))
  (is (= :rf.elision/at               vocab/elision-handle-key))
  (is (= :rf.size/include-large?      vocab/include-large-opt))
  (is (= :rf.size/include-sensitive?  vocab/include-sensitive-opt))
  (is (= :rf.size/include-digests?    vocab/include-digests-opt))
  (is (= :rf.size/threshold-bytes     vocab/threshold-bytes-opt)))

(deftest envelope-indicator-slots-pinned
  ;; Cross-MCP indicator-field vocabulary per Conventions §Cross-MCP
  ;; indicator-field vocabulary. The two slots are
  ;; **unqualified** — they ride the tool's own envelope, not under a
  ;; reserved namespace. A drift to `:rf.size/elided-large` or
  ;; `:elided-large?` is a wire-protocol break.
  (is (= :dropped-sensitive vocab/dropped-sensitive-key))
  (is (= :elided-large      vocab/elided-large-key)))

(deftest jsonrpc-error-codes-pinned
  (is (= -32700 vocab/code-parse-error))
  (is (= -32600 vocab/code-invalid-request))
  (is (= -32601 vocab/code-method-not-found))
  (is (= -32602 vocab/code-invalid-params))
  (is (= -32603 vocab/code-internal-error)))
