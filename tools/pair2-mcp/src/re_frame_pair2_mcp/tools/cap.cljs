(ns re-frame-pair2-mcp.tools.cap
  "Wire-boundary token-budget cap (rf2-rvyzy).

  Per `spec/Principles.md` §\"Tight token budget per response\", every
  MCP `tools/call` response is bounded at ~5,000 tokens by default.
  The cap is enforced here, not just documented: when the serialised
  response would exceed the cap, the wrapper replaces the payload with
  a structured `{:rf.mcp/overflow {...}}` marker and emits that
  instead. Silent truncation is unacceptable — it corrupts the agent's
  conversation without telling the agent.

  Design notes:

  - **Token rule**: `token-estimate s = (quot (count s) 4)`. Cheap
    character→token approximation aligned with Anthropic's
    rule-of-thumb for English / EDN. Not exact; the goal is a
    bounded wire payload, not a precise meter.
  - **Per serialised response**: the cap is applied AFTER pr-str on
    the assembled `{:content [...]}` shape's text slots. Multi-part
    responses share one cumulative budget rather than per-key.
  - **Per-call override**: every tool accepts a `max-tokens` arg —
    integer cap, `0` disables (escape hatch for callers that have
    already paginated). Default `5000`.
  - **Pluggable strategy**: `apply-cap` dispatches on a strategy
    keyword. Today only `:truncate-with-marker` is implemented —
    replace the payload with the overflow marker. Future strategies
    (path-slicing rf2-tygdv, lazy summary rf2-u2029, diff encoding
    rf2-rl7y, etc.) compose here without rebuilding the wrapper.
  - **Centralised**: applied as the final step in `invoke`. Per-tool
    functions are untouched; they emit the same shapes they always
    did. The wire-cap is a property of the egress boundary, not of
    each tool's internals."
  (:require [applied-science.js-interop :as j]
            [re-frame.mcp-base.overflow :as base-overflow]))

;; `default-max-tokens` and `token-estimate` come from
;; `re-frame.mcp-base.overflow` (rf2-vw4sq) — the cap value and the
;; character→token approximation are cross-MCP conventions, pinned once
;; in the base.
(def default-max-tokens base-overflow/default-max-tokens)

(defn token-estimate
  "Delegates to `base-overflow/token-estimate`."
  [s]
  (base-overflow/token-estimate s))

(defn max-tokens-arg
  "Resolve the per-call cap from MCP args. Returns the integer cap in
  tokens, or `nil` when the cap is disabled (caller passed `0`).
  Defaults to `default-max-tokens` when absent or not a number."
  [args]
  (let [raw (when args (j/get args "max-tokens"))]
    (cond
      (or (nil? raw) (undefined? raw)) default-max-tokens
      (and (number? raw) (zero? raw))  nil
      (number? raw)                    (long raw)
      :else                            default-max-tokens)))

(def overflow-hints
  "Tool-specific next-step hints for the overflow marker. Generic
  fallback when a tool isn't listed."
  {"snapshot"      "Narrow scope: pass `path [:k1 :k2]` to slice the :app-db slice, `frames` to a single frame, or `include` to a single slice (one of app-db, sub-cache, machines, epochs, traces). Default mode is :summary — drill down via `get-path` once you know the key."
   "get-path"      "Narrow the path further — pass a deeper segment so the addressed subtree is smaller. Or call `snapshot` with no `path` first for a tree-summary, then re-aim."
   "trace-window"  "Reduce `ms` to a smaller window, narrow with `frame`, or fetch incrementally via `watch-epochs` + `since-id`."
   "watch-epochs"  "Narrow `pred` (e.g. `:event-id-prefix`, `:effects`), pass `frame`, or stream via `subscribe` with `max-events`."
   "subscribe"     "Tighten `filter`, lower `max-buffered-events` / `max-buffered-bytes`, set `max-events` so each tick stays small."
   "eval-cljs"     "Slice the value at the call-site (`get-in`, `take`, project to fewer keys) before returning."
   "discover-app"  "Unusual — the health summary should be small. Inspect `(re-frame-pair2.runtime/health)` directly via `eval-cljs` with a projection."
   "dispatch"      "Trace mode is returning a full epoch — re-run with `trace false` and read the epoch via `watch-epochs`/`snapshot` with a narrower path."})

;; The fallback string lives in `re-frame.mcp-base.overflow` so the
;; cross-MCP marker presents identically. Re-exported here to avoid
;; touching the every-call-site `get overflow-hints` usage.
(def overflow-hint-fallback base-overflow/overflow-hint-fallback)

(defn overflow-payload
  "Build the structured overflow marker. Shape lives in
  `re-frame.mcp-base.overflow/overflow-payload` (rf2-vw4sq); per-tool
  hint table stays local."
  [{:keys [tool token-count cap]}]
  (base-overflow/overflow-payload
    {:tool        tool
     :token-count token-count
     :cap         cap
     :hint        (get overflow-hints tool overflow-hint-fallback)}))

(defn sum-text-tokens
  "Sum `token-estimate` across every `:text` slot in the MCP
  `{:content [{:type \"text\" :text ...} ...]}` result. The
  serialised response's wire size is dominated by these slots; the
  JSON envelope is bounded and ignored."
  [result-js]
  (let [content (j/get result-js :content)
        n      (if (array? content) (.-length content) 0)]
    (loop [i 0 sum 0]
      (if (< i n)
        (let [item (aget content i)
              text (when item (j/get item :text))
              t    (if (string? text) (token-estimate text) 0)]
          (recur (inc i) (+ sum t)))
        sum))))

(defn overflow-result
  "Build a fresh MCP result carrying the overflow marker, preserving
  the `:isError` flag of the original result (an over-budget error
  stays an error; an over-budget success becomes a non-error overflow
  signal — the marker is itself a structured response)."
  [tool token-count cap]
  #js {:content #js [#js {:type "text"
                          :text (pr-str (overflow-payload
                                          {:tool        tool
                                           :token-count token-count
                                           :cap         cap}))}]})

(defn apply-cap
  "Wire-boundary cap enforcement. Returns either `result-js` unchanged
  (when under the cap or cap disabled) or a fresh result carrying the
  overflow marker.

  Pluggable on `strategy`:
  - `:truncate-with-marker` (default, today the only option): drop
    the payload, emit `{:rf.mcp/overflow ...}` instead.

  Future strategies — path-slicing (rf2-tygdv), lazy summary
  (rf2-u2029), diff encoding (rf2-rl7y) — slot in here without
  touching per-tool functions or the `invoke` glue."
  [result-js {:keys [tool cap strategy]
              :or   {strategy :truncate-with-marker}}]
  (cond
    (nil? cap)        result-js
    (nil? result-js)  result-js
    :else
    (let [tokens (sum-text-tokens result-js)]
      (if (<= tokens cap)
        result-js
        (case strategy
          :truncate-with-marker
          (overflow-result tool tokens cap)
          ;; Unknown strategy: degrade safely.
          (overflow-result tool tokens cap))))))
