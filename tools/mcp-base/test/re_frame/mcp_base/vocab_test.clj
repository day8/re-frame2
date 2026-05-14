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
  (is (= :rf/redacted                 vocab/redacted-sentinel))
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

(deftest jsonrpc-error-codes-pinned
  (is (= -32700 vocab/code-parse-error))
  (is (= -32600 vocab/code-invalid-request))
  (is (= -32601 vocab/code-method-not-found))
  (is (= -32602 vocab/code-invalid-params))
  (is (= -32603 vocab/code-internal-error)))
