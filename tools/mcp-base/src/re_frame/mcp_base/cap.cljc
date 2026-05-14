(ns re-frame.mcp-base.cap
  "Wire-boundary token-budget cap pipeline (rf2-rvyzy / rf2-eyelu).

  Per `spec/Principles.md` §\"Tight token budget per response\", every
  MCP `tools/call` response is bounded at ~5,000 tokens by default. When
  the serialised response would exceed the cap, the wrapper replaces the
  payload with a structured `{:rf.mcp/overflow {...}}` marker and emits
  that instead. Silent truncation is unacceptable — it corrupts the
  agent's conversation without telling the agent.

  ## What this ns owns

  `re-frame.mcp-base.overflow` owns the SHAPE of the overflow marker
  (the `{:rf.mcp/overflow {:limit :reached :token-count … :cap-tokens
  … :tool … :hint …}}` map). This ns owns the ALGORITHM that drives the
  marker into a result:

  1. Sum the cumulative `token-estimate` across every `:text` slot in
     the result's `:content` vector.
  2. Compare against the per-call cap (`:max-tokens` MCP arg, default
     `overflow/default-max-tokens`, `0` disables).
  3. Under-budget responses pass through unchanged; over-budget
     responses are replaced with a fresh result carrying the marker.

  Until rf2-eyelu this pipeline was duplicated near-identically in
  pair2-mcp (CLJS, `#js {:content #js [...]}`-shaped results) and
  story-mcp (CLJ, `{:content [...] :structuredContent ...}`-shaped
  results). The only structural difference between the two
  implementations was the SHAPE of the result map and the platform-
  appropriate accessor used to read its `:text` slots. The algorithm
  itself was a single design.

  ## Per-server specialisation hook — the `ResultIO` protocol

  Each consumer reifies `ResultIO` with two methods:

  - `(content-texts io result)` ⇒ seq of strings, the `:text`-slot
    values inside `result`'s content vector. The platform-specific
    accessor (`:text` / `j/get :text`) lives behind this method.
  - `(build-overflow-result io marker original-result)` ⇒ a fresh
    result, shaped per the consumer's wire convention, carrying
    `marker` as its sole content payload.

  The hint table (`{tool-name hint-string}`) stays consumer-side — the
  per-tool next-step prose is domain-specific. The cap value, token
  rule, and marker shape are cross-MCP conventions and stay here.

  ## Single strategy today: truncate-with-marker

  The only strategy wired is drop-the-payload-and-emit-the-overflow-
  marker. When future strategies land (path-slicing rf2-tygdv, lazy
  summary rf2-u2029, diff encoding rf2-rl7y) the pluggable hook gets
  reintroduced here. Until then `apply-cap` calls
  `build-overflow-result` directly — no `case` dispatcher pretending
  there's more than one branch (pre-alpha YAGNI).

  ## Cross-platform

  Pure CLJC. The `ResultIO` protocol resolves identically into the
  JVM (story-mcp / causa-mcp) and CLJS (pair2-mcp) — `defprotocol`
  reads the same way on both sides. mcp-base stays free of
  platform-specific deps (`js-interop`, JVM reflection, etc); those
  live in the consumer's IO instance."
  (:require [re-frame.mcp-base.overflow :as overflow]))

;; ---------------------------------------------------------------------------
;; ResultIO — the per-server specialisation hook.
;; ---------------------------------------------------------------------------

(defprotocol ResultIO
  "Per-consumer accessors over the MCP `tools/call` result shape. Each
  MCP server (pair2-mcp, story-mcp, causa-mcp) reifies this protocol
  once over its native result shape — `#js {:content #js [...]}` for
  pair2-mcp's npm-SDK JS objects, `{:content [...]}` CLJ maps for
  story-mcp / causa-mcp. The cap pipeline is then algorithm-only and
  shape-agnostic."

  (content-texts [_io result]
    "Return a seq of strings, the `:text`-slot values from every entry
    in `result`'s content vector. Non-string `:text` slots and entries
    without a `:text` slot are skipped. Implementations stay nil-safe:
    a nil `result` or empty content yields an empty seq.")

  (build-overflow-result [_io marker original-result]
    "Build a fresh result of the consumer's native shape carrying
    `marker` as its sole content payload. `marker` is the
    `{:rf.mcp/overflow {...}}` map built by
    `overflow/overflow-payload`. `original-result` is provided for
    implementations that need to preserve sibling slots (e.g.
    `:isError` flags); the default-shape consumer ignores it. The
    returned result MUST itself be under any reasonable cap — the
    marker is a small fixed-shape payload, so this falls out
    naturally."))

;; ---------------------------------------------------------------------------
;; max-tokens — per-call cap resolver.
;; ---------------------------------------------------------------------------

(defn max-tokens
  "Resolve the per-call cap from an ALREADY-EXTRACTED raw value.
  Returns the integer cap in tokens, `nil` when the cap is disabled
  (caller passed `0`), or `overflow/default-max-tokens` when absent or
  not a number.

  Each consumer extracts the raw value from its platform-specific args
  object — pair2-mcp uses `(j/get args \"max-tokens\")` against a JS
  object; story-mcp uses `(get args :max-tokens)` against a CLJ map —
  and feeds the result here. The coercion rules (zero disables,
  non-number falls back to default) are the cross-MCP convention.

  ## Disambiguation

  - `nil` return ⇒ caller disabled the cap (`max-tokens 0`).
    `apply-cap` skips enforcement entirely.
  - `default-max-tokens` (5000) return ⇒ caller didn't supply a value
    OR supplied an unrecognised value. The default applies.
  - positive integer return ⇒ caller supplied a custom cap."
  [raw]
  (cond
    (nil? raw)                       overflow/default-max-tokens
    (and (number? raw) (zero? raw))  nil
    (number? raw)                    (long raw)
    :else                            overflow/default-max-tokens))

;; ---------------------------------------------------------------------------
;; sum-text-tokens — cumulative token count across the result's :text slots.
;; ---------------------------------------------------------------------------

(defn sum-text-tokens
  "Sum `overflow/token-estimate` across every `:text` slot in `result`'s
  content vector, accessed via `io`. The serialised response's wire size
  is dominated by these slots; the JSON envelope is bounded and ignored.

  Multi-part responses share one cumulative budget rather than per-key
  — a single oversize slot or an aggregate of many small slots both
  trip the cap."
  [io result]
  (transduce (comp (filter string?)
                   (map overflow/token-estimate))
             +
             0
             (content-texts io result)))

(defn sum-text-chars
  "Sum the character count across every `:text` slot in `result`'s
  content vector, accessed via `io`. Used by the secondary byte cap
  (rf2-ih7g4) — the primary `sum-text-tokens` divides by 4 (Anthropic's
  English-rule-of-thumb), which materially undercounts CJK, emoji,
  base64, and dense code. A char-count secondary gate catches payloads
  that escape the quotient heuristic.

  Returns the cumulative `(count s)` across string `:text` slots. The
  caller decides the multiplier (`cap * 8` in `apply-cap` — generous
  enough for English / EDN to pass through unchanged, tight enough
  that a payload doubled by undercount still trips the cap)."
  [io result]
  (transduce (comp (filter string?)
                   (map count))
             +
             0
             (content-texts io result)))

(def ^:const byte-cap-multiplier
  "Secondary byte-cap multiplier (rf2-ih7g4). `cap * multiplier` is the
  hard char-count ceiling — a defence-in-depth gate against payloads
  that escape the primary `(quot count 4)` token heuristic (CJK,
  emoji, base64, dense code). Set high enough that an English / EDN
  payload at the token cap passes (1 char ≈ 4 tokens ⇒ 4×); set low
  enough that a payload doubled by undercount trips (≥2×). 8× is the
  compromise — generous to the common path, conservative on the
  pathological one."
  8)

;; ---------------------------------------------------------------------------
;; apply-cap — the wire-boundary enforcement entry point.
;; ---------------------------------------------------------------------------

(defn apply-cap
  "Wire-boundary cap enforcement. Returns either `result` unchanged
  (when under the cap or cap disabled via `:max-tokens 0`) or a fresh
  result carrying the overflow marker, built via
  `build-overflow-result` against `io`.

  `opts` keys:

    :tool — string tool name carried in the marker for the agent's
            pattern match.
    :cap  — integer cap in tokens, or `nil` to disable. Usually the
            output of `max-tokens` against the consumer's
            `:max-tokens` arg.
    :hint — string next-step hint embedded in the marker. The
            consumer's per-tool hint table resolves this from `tool`;
            absent ⇒ `overflow/overflow-hint-fallback`."
  [io result {:keys [tool cap hint]}]
  (cond
    (nil? cap)    result
    (nil? result) result
    :else
    ;; Two-stage check (rf2-ih7g4):
    ;;
    ;; 1. Primary token cap — `(quot count 4)` per Anthropic's English
    ;;    rule-of-thumb. The published contract pinned by every
    ;;    consumer and documented in spec.
    ;;
    ;; 2. Secondary char-byte cap — `cap * byte-cap-multiplier`.
    ;;    Defence-in-depth against payloads where the (count s)/4
    ;;    heuristic undercounts: CJK, emoji, base64, dense code. Trips
    ;;    independently of the token sum; reports the char count as
    ;;    the `:token-count` so the agent's overflow handler sees an
    ;;    actionable number (not zero, not nil).
    ;;
    ;; Either gate trips the same truncate-with-marker fallback.
    ;;
    ;; ## Single-pass token+char sum (rf2-hyp0z / F9-F10)
    ;;
    ;; The previous implementation called `sum-text-tokens` and
    ;; `sum-text-chars` separately, each invoking `content-texts` on
    ;; `io` once. For story-mcp's reify that materialised the result
    ;; twice (including a `(pr-str (:structuredContent result))` on
    ;; each call — the dominant cost for structured-content responses
    ;; up to 30K chars). The single-pass transduce below folds both
    ;; sums in one walk of the content-texts seq, materialising the
    ;; expensive accessor exactly once per response.
    (let [{:keys [tokens chars]}
          (transduce (filter string?)
                     (completing
                       (fn [{:keys [tokens chars]} s]
                         {:tokens (+ tokens (overflow/token-estimate s))
                          :chars  (+ chars (count s))}))
                     {:tokens 0 :chars 0}
                     (content-texts io result))
          byte-cap (* cap byte-cap-multiplier)
          over?    (or (> tokens cap) (> chars byte-cap))]
      (if-not over?
        result
        (let [reported (if (> chars byte-cap) chars tokens)
              marker   (overflow/overflow-payload
                         {:tool        tool
                          :token-count reported
                          :cap         cap
                          :hint        hint})]
          (build-overflow-result io marker result))))))
