(ns re-frame.story-mcp.tools.wire-pipeline
  "Dispatcher + wire-boundary structural-dedup + token-budget cap
  (rf2-rvyzy / rf2-zavp5 / rf2-eyelu / rf2-90eft).

  Per `spec/Cross-Cutting-Designs.md §3 Token budgets` every MCP
  `tools/call` response is bounded at ~5,000 tokens by default. The cap
  is enforced HERE (at the wire egress, after the handler runs), not in
  each handler — that keeps tool bodies free of token-accounting noise
  and pins one cross-MCP shape. When the serialised response would
  exceed the cap, the payload is replaced with a structured
  `{:rf.mcp/overflow {...}}` marker.

  ## Pipeline ordering (rf2-90eft mirroring pair-mcp's rf2-rvyzy → rf2-obpa9)

  Two transforms compose at the wire boundary, in this order:

  1. **Structural dedup** (`tools.dedup/dedup-value`, rf2-90eft) for
     tools that opt in via `:dedup-eligible?` on the descriptor. The
     `:structuredContent` slot is run through `day8/de-dupe` to
     collapse repeated subtrees into a flat cache map. Wrapped under
     `{:rf.mcp/dedup-table <cache>}`. The `:content[*].text` slot is
     re-stringified to match — both slots ride the same payload.

  2. **Token-cap** (`re-frame.mcp-base.cap/apply-cap`, rf2-rvyzy). The
     deduped payload is sized; if still over the per-call cap, the
     payload is replaced with a `{:rf.mcp/overflow ...}` marker.

  Order matters: dedup shrinks first, so the cap-honest size is post-
  dedup. Mirrors pair-mcp's wire-pipeline invariant — dedup is always
  the last transform before the cap check.

  ## Why selective opt-in (rather than universal)

  Mirrors pair-mcp's `wire-pipeline` posture: dedup is applied only on
  surfaces where repeated subtrees dominate the wire cost. For story-
  mcp those are `preview-variant` / `run-variant` / `record-as-variant`
  — the three tools whose payload re-keys the same value into multiple
  derived slots (`:app-db` + `:rendered-hiccup` + `:snapshot`; or
  repeated event records in the recorder's `:captured` vector). For
  every other tool dedup is a net loss: `list-stories` /
  `get-story` / `register-variant` and friends ship small bespoke
  shapes where the cache-of-one wrap adds bytes for zero compression.

  Selective opt-in keeps the wire shape predictable: a tool that
  inherits `:dedup-eligible? true` from the registry passes through
  the wrap; everything else passes through unwrapped. Same posture as
  pair-mcp's `dedup-property` knob assignment in
  `descriptors_data.cljs` — only `snapshot` / `trace-window` /
  `watch-epochs` / `subscribe` carry the `:dedup` slot there.

  ## Pipeline ownership

  The cap-enforcement ALGORITHM lives in `re-frame.mcp-base.cap`
  (rf2-eyelu) — token-summing across `:text` slots, comparing against
  the per-call cap, and building the overflow result. This ns supplies
  the per-server specialisation:

  - `result-io` reifies `mcp-base.cap/ResultIO` over the story-mcp
    result shape (`{:content [...] :structuredContent ...}` CLJ maps).
  - `overflow-hints` is the local hint table — the per-tool next-step
    prose stays here because the surfaces are domain-specific.

  Sized via `overflow/token-estimate` (the `(quot (count s) 4)` rule
  aligned with Anthropic's character→token rule-of-thumb). The cap is
  cumulative across every `:text` slot in the response's `:content`
  vector (multi-part responses share one budget, mirroring re-frame2-pair-mcp).

  `:max-tokens` per-call override is read from `arguments`: integer
  cap, `0` disables (escape hatch when the caller has already
  paginated), absent ⇒ default. Lives on every tool's input schema
  via `registry/with-max-tokens`.

  `:dedup` per-call override is read from `arguments`: boolean, default
  `true`. Pass `false` to skip dedup — useful for ad-hoc reads when the
  agent host hasn't been taught to call `de-dupe.core/expand`. Lives
  on dedup-eligible tools' input schema via `schemas/with-dedup`."
  (:require [re-frame.mcp-base.args :as args]
            [re-frame.mcp-base.cap :as base-cap]
            [re-frame.story-mcp.tools.dedup :as dedup]
            [re-frame.story-mcp.tools.registry :as registry]
            [re-frame.story-mcp.tools.result :as result]))

(def ^:private overflow-hints
  "Tool-specific next-step hints for the overflow marker. Generic
  fallback (from `mcp-base.overflow`) when a tool isn't listed.

  Mirrors re-frame2-pair-mcp's local hint table — the hint is the agent's
  shortest path back into budget."
  {"preview-variant"   "Tighten scope: drop `:cell-overrides` or pass a smaller `:active-modes`. The :app-db / :rendered-hiccup slots dominate; raise `max-tokens` (0 disables) if the full payload is genuinely needed."
   "run-variant"       "Tighten scope: pass `:cell-overrides` to shrink the run, or omit `:active-modes`. The :app-db / :rendered-hiccup slots dominate; raise `max-tokens` (0 disables) if the full payload is genuinely needed."
   "get-story"         "Story body is large — request `list-stories` for a slimmer overview, or raise `max-tokens` (0 disables)."
   "get-variant"       "Variant body is large — request `variant->edn` if you want EDN-only, or raise `max-tokens` (0 disables)."
   "variant->edn"      "Variant EDN body is large — narrow the variant or raise `max-tokens` (0 disables)."
   "list-stories"      "Story registry is large — narrow with `:tags`, or raise `max-tokens` (0 disables)."
   "read-failures"     "Failure log is large — assertions accumulator may be deep; clear with a fresh `run-variant`, or raise `max-tokens` (0 disables)."
   "record-as-variant" "Captured event stream is large — shorten `:duration-ms`, or raise `max-tokens` (0 disables)."})

(def ^:private result-io
  "ResultIO reify over story-mcp's CLJ-map result shape. The
  `:structuredContent` slot mirrors the wire conventions docs (an
  agent client that prefers JSON data reads it directly without
  re-parsing the text).

  HOT PATH (rf2-mzndx): `text-result` writes the same payload into BOTH
  `:content[*].text` AND `:structuredContent` on nearly every structured
  tool (`preview-variant`, `run-variant`, `list-stories`, `get-story`,
  `get-variant`, `read-failures`, `record-as-variant`, `get-docs-markdown`).
  Cap accounting must size the structured slot too — otherwise the cap
  underestimates wire by ~50% and overflow replacement fires later than
  it should. We surface the structured payload as one extra `pr-str`-ed
  string in the `content-texts` seq; `sum-text-tokens` then transduces
  it alongside the `:content[*].text` strings under one budget."
  (reify base-cap/ResultIO
    (content-texts [_ result]
      (cond-> (mapv :text (:content result))
        (some? (:structuredContent result))
        (conj (result/pr-edn (:structuredContent result)))))
    (build-overflow-result [_ marker _original]
      {:content          [{:type "text" :text (result/pr-edn marker)}]
       :structuredContent marker})))

(defn apply-dedup
  "Run structural dedup over the `:structuredContent` slot of `result`,
  re-stringifying the `:content[*].text` slot so the two slots stay
  consistent. Returns `result` unchanged when `enabled?` is false or
  when the slot is missing / empty (no-op short-circuit).

  ## Why both slots get the deduped value

  story-mcp's result envelope dual-codes the payload: the
  `:structuredContent` slot is the structured JSON projection (agent
  hosts that prefer JSON data read it directly) and the
  `:content[*].text` slot is the `pr-str`-ed EDN (agent hosts that
  prefer text read it). Per `cap/result-io` (rf2-mzndx) the cap
  pipeline sums BOTH slots — if we deduped only `:structuredContent`
  the text slot would still ship the raw EDN at full size, and the
  cap would fire on a payload the agent only sees once. Re-stringifying
  the text slot keeps the dual-coding consistent and lets the cap see
  the real post-dedup wire size.

  ## Why a fresh `pr-edn` rather than rebuild via `text-result`

  `result/text-result` is the entry-point that handlers call before
  the wire-boundary transforms; rebuilding through it would re-stamp
  any sibling slots the handler set (e.g. `:isError`). Mutating only
  `:content[0].text` + `:structuredContent` preserves everything else
  the handler emitted.

  Mirrors pair-mcp's wire-boundary `dedup-value` post-step (in
  `wire-pipeline.cljs`) — same algorithm, same ordering, same opt-out
  semantics."
  [result enabled?]
  (if (or (not enabled?) (nil? result))
    result
    (let [sc (:structuredContent result)]
      (if (dedup/empty-payload? sc)
        result
        (let [deduped (dedup/dedup-value sc true)
              text    (result/pr-edn deduped)]
          (-> result
              (assoc :structuredContent deduped)
              (assoc-in [:content 0 :text] text)))))))

(defn invoke-tool
  "Invoke `tool-name` with `arguments` (a map of keyword-keyed args).
  Returns the tool's result map, or nil if no such tool. The caller
  serialises the result into a `tools/call` JSON-RPC response.

  ## Wire-boundary pipeline (rf2-90eft)

  1. Dispatch the handler with `arguments`.
  2. `apply-dedup` (rf2-90eft) — selective: runs only when the
     descriptor carries `:dedup-eligible? true` AND the `:dedup` arg
     is true (the default). Today the eligible set is
     `{preview-variant, run-variant, record-as-variant}` — the three
     surfaces where repeated subtrees dominate the wire cost. The
     `:structuredContent` slot is run through `day8/de-dupe` to
     collapse repeated subtrees, and the `:content[*].text` slot is
     re-stringified to match. Per `tools.dedup`.
  3. `base-cap/apply-cap` (rf2-rvyzy / rf2-zavp5 / rf2-eyelu) — when
     the (post-dedup) response exceeds the per-call cap (`:max-tokens`
     arg, default `mcp-base.overflow/default-max-tokens`, `0`
     disables), the payload is replaced with a structured
     `{:rf.mcp/overflow ...}` marker. Per
     `spec/Cross-Cutting-Designs.md §3 Token budgets`.

  Ordering invariant: dedup BEFORE cap. Dedup shrinks the payload first
  so the cap-honest size is post-dedup. Mirrors pair-mcp's wire-pipeline
  ordering.

  Catches any throw from the handler and returns it as a tool-execution
  error (`isError: true`) per MCP §Error Handling — handlers SHOULD
  return error-results themselves, but this is the belt-and-braces
  catch. The cap applies to error results too (large `:data` slots in
  an `ex-data` blow-up shouldn't bypass the budget). Error results
  skip dedup because their structured payload is small + bespoke (the
  flat `:rf.error` / `:tool` / `:exception` shape) — wrapping it under
  `:rf.mcp/dedup-table` would lose the friendly inspection shape for
  zero compression win."
  [tool-name arguments]
  (when-let [t (registry/tool-by-name tool-name)]
    (let [args (or arguments {})
          cap  (base-cap/max-tokens (get args :max-tokens))]
      (if (base-cap/invalid-arg? cap)
        ;; rf2-5rdit — a negative `:max-tokens` resolves to a
        ;; `{:rf.mcp/invalid-arg {...}}` rejection rather than a negative
        ;; cap. Surface it as an `isError: true` tool-result so the agent
        ;; gets an actionable, recoverable error — NOT a silent lock-out
        ;; where the bad cap over-trips `apply-cap`'s `over-cap?` and
        ;; every response is replaced by the overflow marker. The handler
        ;; is never dispatched; the malformed cap never reaches the gate.
        (result/error-result (result/pr-edn cap) cap)
        (let [;; Dedup runs only on tools that opt in via `:dedup-eligible?`
              ;; on the descriptor — and only when the caller leaves the
              ;; `:dedup` arg at its default `true`. Ineligible tools never
              ;; carry the `:dedup` slot in their input schema (per
              ;; `with-dedup` on the descriptor), so any caller-supplied
              ;; value is silently ignored.
              eligible? (true? (:dedup-eligible? t))
              dedup?    (and eligible?
                             (args/parse-boolean (get args :dedup) true))
              result    (try
                          ((:handler t) args)
                          (catch Throwable e
                            (result/error-result (str "Tool handler threw: " (ex-message e))
                                            {:tool      tool-name
                                             :exception (.getName (class e))
                                             :data      (ex-data e)})))
              ;; Skip dedup on error envelopes (small bespoke payload; the
              ;; flat `:rf.error` shape is more useful unwrapped than under
              ;; a cache-of-one marker).
              deduped   (if (true? (:isError result))
                          result
                          (apply-dedup result dedup?))]
          (base-cap/apply-cap result-io deduped
                              {:tool tool-name
                               :cap  cap
                               :hint (get overflow-hints tool-name)}))))))
