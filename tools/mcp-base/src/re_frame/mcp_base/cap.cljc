(ns re-frame.mcp-base.cap
  "Wire-boundary token-budget cap pipeline.

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

  1. Sum the cumulative `token-estimate` across every wire payload
     string in the result — every serialized payload-bearing slot
     (`:content[*].text` PLUS any duplicated slot like
     `:structuredContent`), not only the `:content` vector.
  2. Compare against the per-call cap (`:max-tokens` MCP arg, default
     `overflow/default-max-tokens`, `0` disables).
  3. Under-budget responses pass through unchanged; over-budget
     responses are replaced with a fresh result carrying the marker.

  This pipeline is the single shared implementation both servers route
  through: re-frame2-pair-mcp (CLJS, `#js {:content #js [...]}`-shaped
  results) and story-mcp (CLJ, `{:content [...] :structuredContent
  ...}`-shaped results). The only structural difference between the two
  is the SHAPE of the result map and the platform-appropriate accessor
  used to read its `:text` slots; the algorithm is one design, factored
  behind the `ResultIO` protocol.

  ## Per-server specialisation hook — the `ResultIO` protocol

  Each consumer reifies `ResultIO` with two methods:

  - `(wire-payload-strings io result)` ⇒ seq of strings — ONE for EVERY
    serialized, payload-bearing wire slot in `result`, NOT only the
    `:text` slots. At minimum the `:text`-slot values inside `result`'s
    content vector (platform accessor `:text` / `j/get :text` lives
    behind this method), PLUS any duplicated payload slot the envelope
    also ships (most commonly `:structuredContent`). The name says
    payload-strings, not content-texts, precisely because a slot omitted
    here is a slot the cap cannot see (the ~50% structuredContent
    undercount — see the method docstring).
  - `(build-overflow-result io marker original-result)` ⇒ a fresh
    result, shaped per the consumer's wire convention, carrying
    `marker` as its sole content payload.

  The hint table (`{tool-name hint-string}`) stays consumer-side — the
  per-tool next-step prose is domain-specific. The cap value, token
  rule, and marker shape are cross-MCP conventions and stay here.

  ## Single strategy today: truncate-with-marker

  The only strategy wired is drop-the-payload-and-emit-the-overflow-
  marker. Future strategies (path-slicing, lazy summary, diff encoding)
  would add a pluggable hook here. For the single strategy `apply-cap`
  calls `build-overflow-result` directly — no `case` dispatcher
  pretending there's more than one branch (pre-alpha YAGNI).

  ## Cross-platform

  Pure CLJC. The `ResultIO` protocol resolves identically into the
  JVM (story-mcp) and CLJS (re-frame2-pair-mcp) — `defprotocol`
  reads the same way on both sides. mcp-base stays free of
  platform-specific deps (`js-interop`, JVM reflection, etc); those
  live in the consumer's IO instance."
  (:require [re-frame.mcp-base.args :as args]
            [re-frame.mcp-base.overflow :as overflow]
            [re-frame.mcp-base.vocab :as vocab]))

;; ---------------------------------------------------------------------------
;; ResultIO — the per-server specialisation hook.
;; ---------------------------------------------------------------------------

(defprotocol ResultIO
  "Per-consumer accessors over the MCP `tools/call` result shape. Each
  MCP server (re-frame2-pair-mcp, story-mcp) reifies this protocol
  once over its native result shape — `#js {:content #js [...]}` for
  re-frame2-pair-mcp's npm-SDK JS objects, `{:content [...]}` CLJ maps for
  story-mcp. The cap pipeline is then algorithm-only and
  shape-agnostic."

  (wire-payload-strings [_io result]
    "Return a seq of strings — ONE for EVERY serialized, payload-bearing
    slot that rides the wire in `result`. This is the cap's measurement
    surface: `apply-cap` sums tokens + chars across exactly these
    strings, so a slot omitted here is a slot the cap CANNOT see.

    At minimum that means the `:text`-slot values from every entry in
    `result`'s content vector. Non-string `:text` slots and entries
    without a `:text` slot are skipped. Implementations stay nil-safe:
    a nil `result` or empty content yields an empty seq.

    CONTRACT — count every payload-bearing slot, not only `:content`:
    a consumer whose result envelope DUPLICATES
    the payload into a second wire slot — most commonly a
    `:structuredContent` JSON projection emitted alongside the
    `:content[*].text` EDN on EVERY result (both re-frame2-pair-mcp's
    `wire/ok-text` / `err-text` and story-mcp's `text-result` do this) —
    MUST surface a stable string representation of THAT slot here too
    (e.g. `pr-str` / `JSON.stringify` of the structured value). Both
    copies ride the wire, so both count toward the one budget; omitting
    the second copy undercounts the response by ~50% and lets an
    over-budget payload slip past the overflow marker. The unit suites
    pin this with both a dual-coding reify (counts both) and a
    single-slot reify (counts one).")

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

(def ^:const invalid-arg-hint
  "Recovery hint embedded in the `:rf.mcp/invalid-arg` rejection when a
  caller supplies an out-of-domain `:max-tokens` — a negative,
  a fractional positive in (0,1) that would floor to a 0-cap lockout,
  or a non-finite / out-of-range value (`##Inf`, `##NaN`,
  `1.0E20`) that would crash or truncate `(long raw)`.
  States the valid domain AND the disable sentinel so the agent's next
  call is correct."
  "max-tokens must be an integer >= 1; 0 disables the cap")

(defn invalid-arg-marker
  "Build the `{:rf.mcp/invalid-arg {...}}` rejection payload
  for a malformed per-call argument. `arg` is the offending arg keyword,
  `value` the rejected value, `hint` the recovery prose.

  The consumer wraps this in its own `isError: true` tool-result shape
  (story-mcp `result/error-result`, re-frame2-pair-mcp `wire/err-text`) so
  the agent receives an actionable, recoverable error rather than a
  server crash or — in the negative-`:max-tokens` case — a silent
  lock-out where every response is replaced by the overflow marker."
  [arg value hint]
  {vocab/invalid-arg-key {:arg   arg
                          :value value
                          :hint  hint}})

(defn invalid-arg?
  "True when `x` is an `{:rf.mcp/invalid-arg {...}}` rejection — the
  sentinel `max-tokens` returns for an out-of-domain arg. Consumers
  call this on the `max-tokens` result and, when true, surface `x` as
  an `isError: true` tool-result INSTEAD of feeding it to `apply-cap`
  as a `:cap` (a malformed cap must never reach the gate)."
  [x]
  (and (map? x) (contains? x vocab/invalid-arg-key)))

(defn max-tokens
  "Resolve the per-call cap from an ALREADY-EXTRACTED raw value.
  Returns the integer cap in tokens, `nil` when the cap is disabled
  (caller passed `0`), `overflow/default-max-tokens` when absent or
  not a number, or — for an out-of-domain number (a NEGATIVE;
  a fractional positive in (0,1) that would floor to a 0-cap lockout;
  or a NON-FINITE / OUT-OF-RANGE value — `##Inf`, `##NaN`,
  `1.0E20` — that would crash or truncate `(long raw)`) — an
  `{:rf.mcp/invalid-arg {...}}` REJECTION marker.

  Each consumer extracts the raw value from its platform-specific args
  object — re-frame2-pair-mcp uses `(j/get args \"max-tokens\")` against a JS
  object; story-mcp uses `(get args :max-tokens)` against a CLJ map —
  and feeds the result here. The coercion rules (zero disables,
  negative rejects, non-number falls back to default) are the cross-MCP
  convention.

  ## Disambiguation

  - `nil` return ⇒ caller disabled the cap (`max-tokens 0`).
    `apply-cap` skips enforcement entirely.
  - `default-max-tokens` (5000) return ⇒ caller didn't supply a value
    OR supplied a non-number. The default applies.
  - positive integer return ⇒ caller supplied a custom cap.
  - `{:rf.mcp/invalid-arg {...}}` return ⇒ caller supplied an out-of-domain
    number: a NEGATIVE, a fractional positive in (0,1) that
    would floor to a real 0-cap lockout, or a NON-FINITE /
    OUT-OF-RANGE value (`##Inf` / `##NaN` / past the safe-integer
    window). The consumer surfaces this as an `isError: true` result
    (test via `invalid-arg?`) and MUST NOT
    pass it to `apply-cap`.

  ## Why negatives are rejected, not clamped

  A negative cap, left unguarded, would fall through to `(long raw)`,
  producing a negative ceiling. `apply-cap`'s `over-cap?` then trips on
  ANY non-negative token count, so every response — even a 2-character
  one — would be replaced by the `:rf.mcp/overflow` marker, locking the
  agent out of all tool data until it changed the arg, and emitting a
  nonsensical `:cap-tokens -1`. So the resolver makes an honest,
  recoverable rejection at the AI/MCP boundary rather than silently
  clamping or treating-as-default — the masterpiece CORRECTNESS
  posture: a malformed cap is an agent error worth telling the agent
  about, not a value to paper over."
  [raw]
  (cond
    (nil? raw)                       overflow/default-max-tokens
    (not (number? raw))              overflow/default-max-tokens
    (zero? raw)                      nil
    (neg? raw)                       (invalid-arg-marker :max-tokens raw invalid-arg-hint)
    ;; A fractional positive in (0,1) — e.g. 0.5 — would `(long …)` floor
    ;; to a REAL 0 cap (non-nil, NOT the disable sentinel). `apply-cap`
    ;; then over-trips on EVERY non-empty payload, locking the agent out —
    ;; the same lockout class negatives hit. Reject it honestly rather
    ;; than silently flooring to a 0-cap lockout.
    (< raw 1)                        (invalid-arg-marker :max-tokens raw invalid-arg-hint)
    ;; Finite/range guard BEFORE `(long raw)`. `##Inf` and a
    ;; finite magnitude past the safe-integer window (`1.0E20`) THROW
    ;; `IllegalArgumentException` on `(long raw)` (JVM); `##NaN` truncates
    ;; to a real `0` cap — the exact 0-cap lockout this resolver exists to
    ;; refuse. (`##NaN`/`##-Inf` are also already excluded by `(neg? raw)`
    ;; / `(< raw 1)` being false for them, so they reach here.) Coerce
    ;; through the shared cross-runtime guard: an out-of-domain value
    ;; yields `nil` ⇒ reject honestly, identically on JVM and CLJS, rather
    ;; than crashing the tool or minting a 0-cap.
    :else
    (if-let [n (args/coerce-finite-long raw)]
      n
      (invalid-arg-marker :max-tokens raw invalid-arg-hint))))

;; ---------------------------------------------------------------------------
;; sum-payload-tokens — cumulative token count across the result's wire
;; payload strings (every serialized payload-bearing slot, not only :text).
;; ---------------------------------------------------------------------------

(defn- sum-payload-by
  "Sum `(f s)` across every string the consumer's `wire-payload-strings`
  surfaces for `result` — every serialized payload-bearing wire slot, not
  only the `:text` slots — accessed via `io`. The shared fold body under
  `sum-payload-tokens` / `sum-payload-chars` — the only thing that differs
  between them is the per-string measure `f` (`token-estimate` vs
  `count`). Non-string slots are skipped; a nil / empty content vector
  folds to zero."
  [io result f]
  (transduce (comp (filter string?)
                   (map f))
             +
             0
             (wire-payload-strings io result)))

(defn sum-payload-tokens
  "Sum `overflow/token-estimate` across every wire payload string the
  consumer's `wire-payload-strings` surfaces for `result` (every
  serialized payload-bearing slot, not only `:text`), accessed via `io`.
  The serialised response's wire size is dominated by these slots; the
  JSON envelope is bounded and ignored.

  Multi-part responses share one cumulative budget rather than per-key
  — a single oversize slot or an aggregate of many small slots both
  trip the cap."
  [io result]
  (sum-payload-by io result overflow/token-estimate))

(defn sum-payload-chars
  "Sum the character count across every wire payload string the
  consumer's `wire-payload-strings` surfaces for `result` (every
  serialized payload-bearing slot, not only `:text`), accessed via `io`.
  Used by the secondary byte cap — the primary
  `sum-payload-tokens` divides by 4 (Anthropic's English-rule-of-thumb),
  which materially undercounts CJK, emoji, base64, and dense code. A
  char-count secondary gate catches payloads that escape the quotient
  heuristic.

  Returns the cumulative `(count s)` across the payload strings. The
  caller decides the multiplier (`cap * 8` in `apply-cap` — generous
  enough for English / EDN to pass through unchanged, tight enough
  that a payload doubled by undercount still trips the cap)."
  [io result]
  (sum-payload-by io result count))

(def ^:const byte-cap-multiplier
  "Secondary byte-cap multiplier. `cap * multiplier` is the
  hard char-count ceiling — a defence-in-depth gate against payloads
  that escape the primary `(quot count 4)` token heuristic (CJK,
  emoji, base64, dense code). Set high enough that an English / EDN
  payload at the token cap passes (1 char ≈ 4 tokens ⇒ 4×); set low
  enough that a payload doubled by undercount trips (≥2×). 8× is the
  compromise — generous to the common path, conservative on the
  pathological one."
  8)

;; ---------------------------------------------------------------------------
;; Over-budget decision — extracted from `apply-cap`'s inline `let` so the
;; two-stage gate is unit-trippable in isolation.
;;
;; ## Why the secondary char gate is load-bearing today
;;
;; `apply-cap` derives BOTH sums from the SAME `wire-payload-strings` seq:
;; `tokens = Σ (token-estimate s)`, `chars = Σ (count s)`. A NAIVE reading
;; argues the char gate can never be the SOLE trip-reason: for a SINGLE
;; string of length L, `chars = L` and `tokens = (quot L 4)`, so `chars >
;; cap*8` ⇒ `tokens = (quot L 4) > 2·cap > cap` — the token gate would
;; have already tripped. That single-string argument is REAL but does NOT
;; generalise to the multi-slot sum the pipeline actually folds.
;;
;; `token-estimate` floors PER STRING (`quot` truncates), so the sum of
;; floors is NOT the floor of the sum:
;;
;;     Σ (quot len_i 4)  ≤  quot (Σ len_i) 4    — and strictly less when
;;                                                 the lengths leave
;;                                                 sub-4-char remainders.
;;
;; A content vector of MANY short strings drives this gap to its extreme:
;; 3000 slots of 3 chars each give `tokens = Σ (quot 3 4) = 0` while
;; `chars = 9000`. At `cap = 1` the token gate is QUIET (`0 > 1` is false)
;; yet the char gate FIRES (`9000 > 8`) — `apply-cap` correctly replaces
;; the over-budget payload with the overflow marker. The secondary char
;; gate is therefore reachable through the LIVE `apply-cap` path today,
;; not merely a future-proofing branch. (`watch-epochs` / `trace-window`
;; slices are exactly this shape: a long vector of small per-record text
;; slots, each well under 4 chars of net token contribution per fragment.)
;;
;; The branch is doubly justified: load-bearing now AND defence-in-depth
;; against a FUTURE `token-estimate` refinement (one recognising base64 /
;; CJK at a lower per-char cost) that would decouple the two sums further.
;; The decision is extracted as a pure predicate over already-summed
;; `tokens`/`chars` so BOTH disjuncts and the `reported` selector are
;; exercised directly in `cap_test.clj` (decoupled sums, isolation) AND
;; through the live `apply-cap` many-short-strings path. See `over-cap?`
;; / `reported-count` tests + `apply-cap-many-short-strings-trips-char-gate`.
;; ---------------------------------------------------------------------------

(defn over-cap?
  "Two-stage over-budget predicate. True when EITHER the
  token sum exceeds `cap` OR the char sum exceeds `cap *
  byte-cap-multiplier`. Pure over already-summed counts so the
  secondary char gate is unit-trippable in isolation (see the comment
  block above for why the per-string floor in `token-estimate` makes
  this gate load-bearing through the live `apply-cap` path today —
  not merely a future-proofing branch)."
  [tokens chars cap]
  (or (> tokens cap)
      (> chars (* cap byte-cap-multiplier))))

(defn reported-count
  "The `:token-count` reported in the overflow marker: the char count
  when the secondary char gate tripped (so the agent sees an actionable
  number for the payload that escaped the token heuristic), else the
  token sum. Pure companion to `over-cap?` — unit-tested directly in
  isolation AND reached through the live `apply-cap` path by a content
  vector of many sub-4-char strings."
  [tokens chars cap]
  (if (> chars (* cap byte-cap-multiplier))
    chars
    tokens))

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
    ;; Two-stage gate (`over-cap?`) + single-pass token/char sum — see
    ;; the `over-cap?` comment block above for why the char disjunct is
    ;; load-bearing through this very path (the per-string `token-estimate`
    ;; floor lets many sub-4-char slots trip the char gate while the token
    ;; sum stays quiet). Both sums fold in ONE walk to avoid materialising
    ;; story-mcp's `:structuredContent` twice.
    (let [{:keys [tokens chars]}
          (transduce (filter string?)
                     (completing
                       (fn [{:keys [tokens chars]} s]
                         {:tokens (+ tokens (overflow/token-estimate s))
                          :chars  (+ chars (count s))}))
                     {:tokens 0 :chars 0}
                     (wire-payload-strings io result))]
      (if-not (over-cap? tokens chars cap)
        result
        (let [marker (overflow/overflow-payload
                       {:tool        tool
                        :token-count (reported-count tokens chars cap)
                        :cap         cap
                        :hint        hint})]
          (build-overflow-result io marker result))))))
