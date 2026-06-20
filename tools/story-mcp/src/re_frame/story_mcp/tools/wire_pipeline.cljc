(ns re-frame.story-mcp.tools.wire-pipeline
  "Dispatcher + wire-boundary structural-dedup + token-budget cap.

  Per `spec/Cross-Cutting-Designs.md §3 Token budgets` every MCP
  `tools/call` response is bounded at ~5,000 tokens by default. The cap
  is enforced HERE (at the wire egress, after the handler runs), not in
  each handler — that keeps tool bodies free of token-accounting noise
  and pins one cross-MCP shape. When the serialised response would
  exceed the cap, the payload is replaced with a structured
  `{:rf.mcp/overflow {...}}` marker.

  ## Pipeline ordering

  Two transforms compose at the wire boundary, in this order:

  1. **Structural dedup** (`tools.dedup/dedup-value`) for tools that opt
     in via `:dedup-eligible?` on the descriptor. The
     `:structuredContent` slot is run through `day8/de-dupe` to
     collapse repeated subtrees into a flat cache map. Wrapped under
     `{:rf.mcp/dedup-table <cache>}`. The `:content[*].text` slot is
     re-stringified to match — both slots ride the same payload.

  2. **Token-cap** (`re-frame.mcp-base.cap/apply-cap`). The
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

  The cap-enforcement ALGORITHM lives in `re-frame.mcp-base.cap` —
  token-summing across `:text` slots, comparing against the per-call
  cap, and building the overflow result. This ns supplies the
  per-server specialisation:

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
            [re-frame.mcp-base.overflow :as overflow]
            [re-frame.story-mcp.protocol :as proto]
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

  HOT PATH: `text-result` writes the same payload into BOTH
  `:content[*].text` AND `:structuredContent` on nearly every structured
  tool (`preview-variant`, `run-variant`, `list-stories`, `get-story`,
  `get-variant`, `read-failures`, `record-as-variant`, `get-docs-markdown`).
  Cap accounting must size the structured slot too — otherwise the cap
  underestimates wire by ~50% and overflow replacement fires later than
  it should. We surface the structured payload as one extra `pr-str`-ed
  string in the `wire-payload-strings` seq; `sum-payload-tokens` then transduces
  it alongside the `:content[*].text` strings under one budget."
  (reify base-cap/ResultIO
    (wire-payload-strings [_ result]
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
  prefer text read it). Per `cap/result-io` the cap
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
    (let [structured (:structuredContent result)]
      (if (dedup/empty-payload? structured)
        result
        (let [deduped (dedup/dedup-value structured true)
              text    (result/pr-edn deduped)]
          (-> result
              (assoc :structuredContent deduped)
              (assoc-in [:content 0 :text] text)))))))

(defn- tool-allowed-arg-keys
  "The string-keyed argument names a tool advertises — the keys of its
  descriptor's `:inputSchema :properties`, stringified. The precise
  per-tool answer for an unknown-arg diagnostic (more actionable than the
  global `arg-keys` union), and the same shape `tools/list` advertises so
  an agent can cross-reference. Returns a sorted vector for stable
  diagnostic output."
  [t]
  (->> (-> t :inputSchema :properties keys)
       (map name)
       sort
       vec))

(defn- unknown-arg-error
  "Build the tool-level `isError: true` diagnostic for unknown top-level
  argument keys. `unknown` is the vec of RAW key STRINGS the caller sent
  outside the tool's allowed set; `t` is the tool descriptor. The result
  names the unknown keys, the tool, and that tool's allowed key set — so
  a non-schema-validating host or hand-rolled agent gets
  agent-recoverable feedback that its intended control knob was ignored
  rather than a successful-looking call that silently defaulted. The
  server is the authoritative backstop for the advertised
  `additionalProperties false` contract, independent of whether the
  client validates.

  Two call sites feed this builder, differing only in WHERE the unknown
  keys came from:

  - Keys outside the GLOBAL `protocol/arg-keys` allowlist (raw strings
    dropped + recorded as metadata at frame normalisation).
  - Keys inside the global allowlist (so they survived normalisation as
    keyword ENTRIES) but outside THIS tool's advertised `inputSchema`
    properties (e.g. `:body` on `get-variant`, `:write-back` on
    `run-variant`). Reported as their `name`-stringified form so the
    diagnostic shape matches the global-unknown case."
  [tool-name t unknown]
  (let [allowed (tool-allowed-arg-keys t)]
    (result/error-result
     (str "Unknown argument" (when (> (count unknown) 1) "s")
          " for tool `" tool-name "`: " (pr-str unknown)
          ". Allowed: " (pr-str allowed)
          ". (Unknown keys are not applied; fix the name or drop the key.)")
     {:rf.error    :rf.story-mcp/unknown-arguments
      :tool        tool-name
      :unknown     unknown
      :allowed     allowed})))

(def ^:private wire-managed-arg-keys
  "Cross-cutting argument keys CONSUMED at the wire boundary
  (`invoke-tool`) rather than by a tool handler — so the per-tool schema
  check must tolerate them on EVERY tool even when a given tool's
  descriptor doesn't advertise them.

  - `:max-tokens` is injected on every tool (`schemas/with-max-tokens`),
    so it is always in the advertised set; listed here only for symmetry
    with the dispatcher's read.
  - `:dedup` is advertised ONLY on the three dedup-eligible tools
    (`schemas/with-dedup`), but is DOCUMENTED as silently ignored on
    ineligible tools (`schemas/with-dedup` docstring — the dispatcher
    gates dedup on `:dedup-eligible?`, not on the arg's presence). A
    caller leaving `:dedup` at its default on a non-eligible tool is a
    benign no-op, not a typo'd control knob — so it must NOT trip the
    per-tool unknown-argument diagnostic."
  #{:max-tokens :dedup})

(defn- tool-invalid-arg-keys
  "The keyword arg keys in `args` that are GLOBALLY known (they survived
  `protocol/normalize-frame` as keyword entries against
  `protocol/arg-keys`) but are NOT valid for the SELECTED tool `t`: not
  in the tool's advertised `inputSchema` properties AND not a
  wire-managed cross-cutting knob (`wire-managed-arg-keys`). Returns the
  offending keys `name`-stringified + sorted (matching the global-unknown
  diagnostic shape); empty when every key is tool-valid.

  This is the server-side backstop for each descriptor's
  `additionalProperties false` contract at the PER-TOOL granularity: the
  global normalisation only rejects keys outside the UNION of every
  tool's args, so a key valid for ANOTHER tool (`:body`, `:write-back`,
  `:dedup` on a non-eligible tool) is caught here rather than slipping
  through to be silently ignored. No-intern is preserved — every key
  here is already an interned keyword (it passed the global allowlist),
  so `name` mints nothing."
  [t args]
  (let [advertised (set (-> t :inputSchema :properties keys))]
    (->> (keys args)
         (remove advertised)
         (remove wire-managed-arg-keys)
         (map name)
         sort
         vec)))

(defn- cap-error
  "Route a pre-dispatch error envelope through the SAME wire-boundary
  token cap (`base-cap/apply-cap`) that bounds every normal tool
  response. Per `spec/Principles.md §Tight token budget`, EVERY MCP tool
  response is bounded, error results included — including the
  pre-dispatch rejection branches (`unknown-arg-error`, the
  invalid-`:max-tokens` rejection, the per-tool unknown-argument
  diagnostic). Without this, a caller could pack many long unknown keys
  inside the 4 MB frame cap and receive an UNCAPPED diagnostic echoing
  them all back — violating the response-cap contract. Capping these
  envelopes closes that.

  `cap` is the resolved per-call cap (the output of `base-cap/max-tokens`):
  an integer ceiling, `nil` (caller disabled the cap with `:max-tokens 0`),
  OR — for the invalid-`:max-tokens` branch — a
  `{:rf.mcp/invalid-arg {...}}` REJECTION marker. A rejection marker is
  NOT a usable cap (feeding it to `apply-cap` would crash the gate), so we
  fall back to the convention default (`overflow/default-max-tokens`) —
  the malformed cap can't be honoured, but the error envelope must still
  be bounded. A genuine `nil` (cap disabled) is preserved: a caller who
  explicitly opted out of the cap opted out for errors too."
  [tool-name cap err-result]
  (let [effective-cap (if (base-cap/invalid-arg? cap)
                        overflow/default-max-tokens
                        cap)]
    (base-cap/apply-cap result-io err-result
                        {:tool tool-name
                         :cap  effective-cap
                         :hint (get overflow-hints tool-name)})))

(defn invoke-tool
  "Invoke `tool-name` with `arguments` (a map of keyword-keyed args).
  Returns the tool's result map, or nil if no such tool. The caller
  serialises the result into a `tools/call` JSON-RPC response.

  ## Wire-boundary pipeline

  1. Dispatch the handler with `arguments`.
  2. `apply-dedup` — selective: runs only when the descriptor carries
     `:dedup-eligible? true` AND the `:dedup` arg is true (the default).
     The eligible set is
     `{preview-variant, run-variant, record-as-variant}` — the three
     surfaces where repeated subtrees dominate the wire cost. The
     `:structuredContent` slot is run through `day8/de-dupe` to
     collapse repeated subtrees, and the `:content[*].text` slot is
     re-stringified to match. Per `tools.dedup`.
  3. `base-cap/apply-cap` — when the (post-dedup) response exceeds the
     per-call cap (`:max-tokens` arg, default
     `mcp-base.overflow/default-max-tokens`, `0` disables), the payload
     is replaced with a structured `{:rf.mcp/overflow ...}` marker. Per
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
    (let [args    (or arguments {})
          cap     (base-cap/max-tokens (get args :max-tokens))
          ;; RAW string keys the caller sent outside the top-level
          ;; allowlist, recorded as metadata by
          ;; `protocol/normalize-frame` (never interned, never a map
          ;; entry). A non-empty set means the agent typo'd a control
          ;; knob; we diagnose before dispatch rather than silently drop.
          unknown (get (meta args) proto/unknown-arg-keys-meta)
          ;; Keys that ARE in the global allowlist (so they survived
          ;; normalisation as keyword entries) but are NOT valid for THIS
          ;; tool (e.g. `:body` on `get-variant`). The global check above
          ;; only catches keys outside the UNION of every tool's args;
          ;; this is the per-tool backstop.
          tool-invalid (tool-invalid-arg-keys t args)]
      (cond
        (base-cap/invalid-arg? cap)
        ;; A negative `:max-tokens` resolves to a
        ;; `{:rf.mcp/invalid-arg {...}}` rejection rather than a negative
        ;; cap. Surface it as an `isError: true` tool-result so the agent
        ;; gets an actionable, recoverable error — NOT a silent lock-out
        ;; where the bad cap over-trips `apply-cap`'s `over-cap?` and
        ;; every response is replaced by the overflow marker. The handler
        ;; is never dispatched; the malformed cap never reaches the gate.
        ;; The rejection envelope rides the response cap too (via the
        ;; default cap, since the caller's cap was malformed).
        (cap-error tool-name cap (result/error-result (result/pr-edn cap) cap))

        ;; An unknown top-level argument key is an agent-recoverable
        ;; diagnostic, not a silent drop. The handler is never
        ;; dispatched; the response names the unknown keys, the tool, and
        ;; that tool's allowed key set so the model can retry with the
        ;; corrected arg name. (The no-intern invariant holds — the
        ;; unknown keys arrive as strings via metadata and are never
        ;; keywordised.) The diagnostic rides the response cap so a
        ;; caller packing many long unknown keys can't bypass the budget
        ;; with an uncapped echo.
        (seq unknown)
        (cap-error tool-name cap (unknown-arg-error tool-name t unknown))

        ;; A globally-known but tool-invalid argument key is the per-tool
        ;; backstop for each descriptor's `additionalProperties false`
        ;; contract. A key valid for ANOTHER tool (`:body`, `:write-back`,
        ;; `:dedup` on a non-eligible tool) survives global normalisation;
        ;; without this branch the selected handler would silently ignore
        ;; it. Diagnose it with the same `:rf.story-mcp/unknown-arguments`
        ;; shape before dispatch, capped on the same response cap.
        (seq tool-invalid)
        (cap-error tool-name cap (unknown-arg-error tool-name t tool-invalid))

        :else
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
