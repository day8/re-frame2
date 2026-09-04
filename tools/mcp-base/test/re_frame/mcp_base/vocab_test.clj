(ns re-frame.mcp-base.vocab-test
  "Pins the wire-vocabulary constants. The marker keys are
  the cross-MCP convention that an agent learns once — a rename here
  is a wire-protocol break. These tests fail loud when that happens."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]))

(deftest rf-mcp-marker-keys-pinned
  (is (= :rf.mcp/overflow      rf.mcp-base.vocab/overflow-key))
  (is (= :rf.mcp/dedup-table   rf.mcp-base.vocab/dedup-table-key))
  (is (= :rf.mcp/diff-from     rf.mcp-base.vocab/diff-from-key))
  (is (= :rf.mcp/cursor-stale  rf.mcp-base.vocab/cursor-stale-reason))
  (is (= :rf.mcp/cache-hit     rf.mcp-base.vocab/cache-hit-key))
  (is (= :rf.mcp/summary       rf.mcp-base.vocab/summary-key))
  ;; These two reserved wire-vocabulary markers are pinned too, so a
  ;; rename cannot slip past the base unit gate. :rf.mcp/invalid-arg is
  ;; the per-call arg-rejection wrapper; :rf.mcp/result is the
  ;; wire-fidelity typed eval/handler result envelope that pair-mcp
  ;; actively emits — both are cross-MCP convention an agent learns once.
  (is (= :rf.mcp/invalid-arg   rf.mcp-base.vocab/invalid-arg-key))
  (is (= :rf.mcp/result        rf.mcp-base.vocab/result-key)))

(deftest rf-size-elision-keys-pinned
  (is (= :rf.size/large-elided        rf.mcp-base.vocab/large-elided-key))
  (is (= :rf/redacted                 rf.mcp-base.vocab/redacted-sentinel))
  (is (= :rf.elision/at               rf.mcp-base.vocab/elision-handle-key))
  (is (= :rf.size/include-large?      rf.mcp-base.vocab/include-large-opt))
  (is (= :rf.size/include-sensitive?  rf.mcp-base.vocab/include-sensitive-opt))
  (is (= :rf.size/include-digests?    rf.mcp-base.vocab/include-digests-opt))
  (is (= :rf.size/threshold-bytes     rf.mcp-base.vocab/threshold-bytes-opt)))

(deftest envelope-indicator-slots-pinned
  ;; Cross-MCP indicator-field vocabulary per Conventions §Cross-MCP
  ;; indicator-field vocabulary. The two slots are
  ;; **unqualified** — they ride the tool's own envelope, not under a
  ;; reserved namespace. A drift to `:rf.size/elided-large` or
  ;; `:elided-large?` is a wire-protocol break.
  (is (= :dropped-sensitive rf.mcp-base.vocab/dropped-sensitive-key))
  (is (= :elided-large      rf.mcp-base.vocab/elided-large-key)))

(deftest jsonrpc-error-codes-pinned
  (is (= -32700 rf.mcp-base.vocab/code-parse-error))
  (is (= -32600 rf.mcp-base.vocab/code-invalid-request))
  (is (= -32601 rf.mcp-base.vocab/code-method-not-found))
  (is (= -32602 rf.mcp-base.vocab/code-invalid-params))
  (is (= -32603 rf.mcp-base.vocab/code-internal-error)))
