(ns re-frame.mcp-base.vocab-test
  "Pins the wire vocabulary. Agents learn these spellings once, so a
  rename is a wire-protocol break."
  (:require [clojure.test :refer [are deftest]]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]))

(deftest wire-vocabulary-pinned
  (are [expected actual] (= expected actual)
    :rf.mcp/overflow               rf.mcp-base.vocab/overflow-key
    :rf.mcp/dedup-table            rf.mcp-base.vocab/dedup-table-key
    :rf.mcp/diff-from              rf.mcp-base.vocab/diff-from-key
    :rf.mcp/cursor-stale           rf.mcp-base.vocab/cursor-stale-reason
    :rf.mcp/cache-hit              rf.mcp-base.vocab/cache-hit-key
    :rf.mcp/summary                rf.mcp-base.vocab/summary-key
    :rf.mcp/invalid-arg            rf.mcp-base.vocab/invalid-arg-key
    :rf.mcp/result                 rf.mcp-base.vocab/result-key
    :rf.size/large-elided          rf.mcp-base.vocab/large-elided-key
    :rf/redacted                   rf.mcp-base.vocab/redacted-sentinel
    :rf.elision/at                 rf.mcp-base.vocab/elision-handle-key
    :rf.egress/include-large?      rf.mcp-base.vocab/include-large-opt
    :rf.egress/include-sensitive?  rf.mcp-base.vocab/include-sensitive-opt
    :rf.egress/include-digests?    rf.mcp-base.vocab/include-digests-opt
    :rf.egress/threshold-bytes     rf.mcp-base.vocab/threshold-bytes-opt
    ;; The two indicator slots are unqualified: they ride the tool's own
    ;; envelope, not a reserved namespace.
    :dropped-sensitive             rf.mcp-base.vocab/dropped-sensitive-key
    :elided-large                  rf.mcp-base.vocab/elided-large-key
    -32700                         rf.mcp-base.vocab/code-parse-error
    -32600                         rf.mcp-base.vocab/code-invalid-request
    -32601                         rf.mcp-base.vocab/code-method-not-found
    -32602                         rf.mcp-base.vocab/code-invalid-params
    -32603                         rf.mcp-base.vocab/code-internal-error))
