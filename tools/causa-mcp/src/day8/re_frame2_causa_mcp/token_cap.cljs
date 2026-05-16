(ns day8.re-frame2-causa-mcp.token-cap
  "Wire-pipeline mechanism W-1 at the Causa-MCP boundary (rf2-8xzoe.5).
  Token-cap with structured overflow marker — per
  `tools/causa-mcp/spec/004-Wire-Pipeline.md` §Tight token budget per
  response §1 (Token budget cap).

  ## What this gates

  Every `tools/call` response is bounded at **≤ 5,000 tokens** by
  default. Each call MAY override via a `max-tokens` integer JSON-RPC
  argument; the server clamps to `[500, 50000]`. A tool that would
  exceed the cap MUST NOT silently truncate (MUST 6 of the MUST
  inventory); it MUST instead return a structured `:rf.mcp/overflow`
  marker at the top of the payload (MUST 7).

  This ns is the per-call wrapper that lives at the egress boundary
  between the tool dispatcher and the MCP SDK's `tools/call` reply. It
  measures the rendered payload (MUST 5), compares against the cap
  (MUST 3), and either passes the payload through unchanged (under-
  budget) or replaces it with the overflow marker (over-budget).

  ## Causa-specific marker shape — divergence from pair2-mcp

  Causa's normative marker (spec/004 §1, L244-249):

      {:rf.mcp/overflow {:cap          5000
                         :would-be     ~12400
                         :hint         :switch-mode  ; or :paginate, :slice, :narrow-filter
                         :continuation {:cursor \"opaque…\" :next-args {...}}}}

  This is **deliberately divergent** from pair2-mcp's older marker
  shape (which uses `:cap-tokens`, `:token-count`, `:limit :reached`,
  and a string `:hint`). Pair2-mcp's shape predates the causa-spec
  pin; the causa shape is the forward-going contract — `:hint` is a
  keyword from a closed vocabulary the agent can pattern-match
  exhaustively, and `:continuation` is a structured re-call affordance
  rather than a free-text suggestion. The cross-MCP key
  (`:rf.mcp/overflow`) stays byte-identical; only the value shape
  evolves. When pair2-mcp aligns (future bead), the two consumers
  collapse on this shape.

  Because of this divergence we don't reuse
  `re-frame.mcp-base.overflow/overflow-payload` (which still builds
  the pair2-shape). We DO reuse `re-frame.mcp-base.cap`'s
  measurement primitives — token estimate, char count, the two-stage
  cap check (rf2-ih7g4) — those are platform-agnostic.

  ## Per-call clamp `[500, 50000]`

  Causa-mcp clamps the per-call override to `[500, 50000]` per
  spec/004 §1 (L237). This is causa-specific — pair2-mcp's resolver
  accepts any positive integer (and treats `0` as disable). Causa
  intentionally rejects:

    - Caps below 500 tokens (no useful payload fits — a tool would
      always overflow, masking real over-budget conditions).
    - Caps above 50,000 tokens (a single response would consume 25%
      of a typical 200K-token context window — the agent host's
      working session would be starved of room for follow-up tools).
    - `0` is NOT a disable hatch on causa-mcp. The spec is a
      normative MUST, not a per-call opt-out; the `[500, 50000]`
      range pins the floor. A caller that passes `0` lands at `500`
      (clamped to the floor).

  ## Algorithm shape — single-pass measurement

  `apply-cap` walks the result's content texts exactly once,
  accumulating both the token sum (`(quot count 4)` per Anthropic's
  English rule-of-thumb) and the char sum. The dual-gate check
  (rf2-ih7g4): the primary token cap trips on the quotient; a
  secondary byte cap (`cap * 8`) trips on raw char count to catch
  payloads that escape the quotient heuristic (CJK, emoji, base64,
  dense code).

  Either gate trips the same overflow path. The reported `:would-be`
  is the cap-relative measurement — token count when the token gate
  trips, char count when the byte gate trips — so the agent's
  overflow handler sees an actionable number.

  ## Hint vocabulary — closed set

  `:hint` is a keyword from `{:switch-mode :paginate :slice
  :narrow-filter}`. Tools declare their per-overflow hint at the
  catalogue site (rf2-8xzoe later F-tranches register the eighteen
  catalogue entries); `hint-for-tool` looks it up and falls back to
  `:narrow-filter` (the generic re-call-with-narrower-args hint)
  when a tool isn't listed. The fallback is deliberate — a missing
  table entry is a programmer bug; defaulting to a closed-set value
  keeps the agent surface uniform while the gap is fixed.

  ## Continuation slot

  The `:continuation {:cursor … :next-args …}` slot is causa's
  structured re-call affordance. The cursor (when present) is the
  opaque pagination cursor for sequence-returning tools that the
  agent passes back to resume mid-stream. `:next-args` is the
  suggested per-call args delta (e.g. `{:mode :sample}` for a tool
  whose `:full` mode overflowed, suggesting downshift to a bounded
  sample). The slot is optional — `apply-cap` populates it only when
  the call-site supplies it via opts."
  (:require [applied-science.js-interop :as j]
            [re-frame.mcp-base.cap :as base-cap]
            [re-frame.mcp-base.overflow :as base-overflow]))

;; ---------------------------------------------------------------------------
;; Constants — cap range and default.
;; ---------------------------------------------------------------------------

(def ^:const default-max-tokens
  "Default per-response cap in tokens. 5,000 per spec/004 §1 — sized
  for the typical MCP response envelope after diff-encode + dedup. The
  constant is reified at the causa-mcp boundary (rather than imported
  unchanged from `mcp-base.overflow`) so a downstream reader sees the
  causa-side surface as a single self-contained unit. Today the value
  is identical to the cross-MCP default; if causa ever diverges (it
  shouldn't — the cap is a cross-server invariant) the change lands
  here."
  base-overflow/default-max-tokens)

(def ^:const min-max-tokens
  "Lower clamp on the per-call `max-tokens` override. Per spec/004 §1
  (L237) — `[500, 50000]`. Below 500 tokens no useful tool response
  fits; the cap would always trip, masking real over-budget
  conditions."
  500)

(def ^:const max-max-tokens
  "Upper clamp on the per-call `max-tokens` override. Per spec/004 §1
  (L237) — `[500, 50000]`. Above 50,000 tokens a single response
  consumes ~25% of a typical 200K-token context window, starving the
  agent's working session of room for follow-up tool calls."
  50000)

;; ---------------------------------------------------------------------------
;; max-tokens — per-call cap resolver with `[500, 50000]` clamp.
;; ---------------------------------------------------------------------------

(defn clamp-max-tokens
  "Clamp `n` into the `[min-max-tokens, max-max-tokens]` range. Returns
  an integer. `nil` / non-numeric inputs collapse to `default-max-tokens`.

  The clamp is causa-specific — pair2-mcp's resolver passes any
  positive integer through unchanged. Causa's spec/004 §1 pins the
  range; a caller that supplies `200` lands at `500` (floor), one that
  supplies `100000` lands at `50000` (ceiling).

  Disambiguation:
    - `nil` / `0` / non-number ⇒ `default-max-tokens` (5000). Causa
      doesn't honour pair2-mcp's `0`-disables-cap escape hatch — the
      cap is a normative MUST, not a per-call opt-out.
    - any positive integer ⇒ clamped into `[500, 50000]`."
  [n]
  (cond
    (nil? n)         default-max-tokens
    (not (number? n)) default-max-tokens
    (zero? n)        default-max-tokens
    :else
    (-> n long
        (max min-max-tokens)
        (min max-max-tokens))))

(defn max-tokens-arg
  "Resolve the per-call cap from the raw MCP args object. Returns an
  integer in `[min-max-tokens, max-max-tokens]`.

  Accepts both the JS-side args object (the npm MCP SDK shape) and a
  CLJS map (already-coerced upstream). The slot name is the cross-
  server `max-tokens` (string key for JS, `:max-tokens` for CLJS) per
  spec/004 §1 (L237) — `the corresponding parsed CLJS keyword is
  :max-tokens`.

  Unrecognised / absent inputs collapse to `default-max-tokens`."
  [args]
  (let [raw (cond
              (or (nil? args) (undefined? args))
              nil

              (map? args)
              (or (get args :max-tokens)
                  (get args "max-tokens"))

              :else
              (let [v (j/get args "max-tokens")]
                (when-not (or (nil? v) (undefined? v)) v)))]
    (clamp-max-tokens raw)))

;; ---------------------------------------------------------------------------
;; Hint table — per-tool overflow hint keyword (closed vocabulary).
;; ---------------------------------------------------------------------------

(def ^:const hint-vocabulary
  "The closed set of `:hint` values per spec/004 §1 (L247). An agent
  pattern-matches on this set; an out-of-vocab value would break the
  agent's exhaustive case. Reified as a set so a downstream lint can
  assert the table only emits in-vocab keywords."
  #{:switch-mode :paginate :slice :narrow-filter})

(def default-hint
  "Fallback when a tool isn't listed in `hints-by-tool`. `:narrow-filter`
  is the generic re-call-with-narrower-args hint — applicable to any
  tool whose response can be tightened by an extra filter or a smaller
  scope. The fallback is deliberate: a missing table entry is a
  programmer bug, and defaulting to a closed-set value keeps the agent
  surface uniform while the gap is fixed."
  :narrow-filter)

(def hints-by-tool
  "Per-tool overflow `:hint` keyword. Populated incrementally as the
  causa-mcp catalogue lands (subsequent rf2-8xzoe F-tranche beads):
  each tool's catalogue entry declares its `:cap-reached` hint and the
  table entry mirrors it. Today (W-1 landing) the table is the
  starting shape — the eighteen-tool catalogue lands in subsequent
  F-tranche beads (per the F-2 server.cljs note on the empty
  catalogue).

  Hints chosen per spec/004 §1 + §4 (lazy summary as the default mode
  for rich values):

    - `get-app-db` / `get-machine-state` — `:slice`: drill in via
      `:path` to a subtree.
    - `get-trace-buffer` / `get-epoch-history` / `subscribe` —
      `:paginate`: re-call with `:cursor` + `:limit`.
    - Tools with a `:mode` knob (`:summary` / `:sample` / `:full`)
      where `:full` overflowed — `:switch-mode`: downshift to
      `:sample` or `:summary`.

  Tools not yet in the catalogue (or not registered here) fall back
  to `default-hint` (`:narrow-filter`)."
  {})

(defn hint-for-tool
  "Resolve the overflow hint keyword for `tool`. Returns a keyword
  from `hint-vocabulary` — `(hints-by-tool tool)` when registered,
  `default-hint` otherwise."
  [tool]
  (get hints-by-tool tool default-hint))

;; ---------------------------------------------------------------------------
;; ResultIO — causa-mcp specialisation over `#js {:content #js [...]}`.
;; ---------------------------------------------------------------------------

(def ^:private result-io
  "ResultIO reify over causa-mcp's `#js {:content #js [...]}` shape
  (npm MCP SDK; parallel to pair2-mcp's). Reads `:text` slots via
  `js-interop` and builds the overflow result with the same JS shape
  so the MCP SDK serialises it as a `tools/call` reply unchanged.

  Note that `build-overflow-result` is NOT called by causa's
  `apply-cap` below — causa's marker shape diverges from
  `re-frame.mcp-base.overflow/overflow-payload`, so we bypass
  `base-cap/apply-cap` and build the marker inline. The reify is kept
  for symmetry with pair2-mcp's surface and to satisfy the protocol
  contract should a future bead lift causa's `apply-cap` back through
  `base-cap`."
  (reify base-cap/ResultIO
    (content-texts [_ result]
      (let [content (j/get result :content)
            n       (if (array? content) (.-length content) 0)]
        (loop [i 0 acc (transient [])]
          (if (< i n)
            (let [item (aget content i)
                  text (when item (j/get item :text))]
              (recur (inc i) (conj! acc text)))
            (persistent! acc)))))
    (build-overflow-result [_ marker _original]
      #js {:content #js [#js {:type "text"
                              :text (pr-str marker)}]})))

;; ---------------------------------------------------------------------------
;; Marker builder — causa-specific shape per spec/004 §1.
;; ---------------------------------------------------------------------------

(defn overflow-payload
  "Build the causa-spec-shaped `:rf.mcp/overflow` marker map. Spec/004
  §1 (L244-249) + the cross-MCP wire-vocab conformance schema
  (`tools/mcp-conformance/wire-vocab/test/.../wire_vocab_test.clj`
  `CausaOverflowBody`):

    {:rf.mcp/overflow {:limit        :reached
                       :cap          <int>
                       :would-be     <int>
                       :hint         <:switch-mode|:paginate|:slice|:narrow-filter>
                       :continuation {:cursor … :next-args …}}}

  The `:limit :reached` sentinel is shared with pair2-mcp's older
  marker shape — the conformance schema requires it on both shapes
  so an agent's first-pass pattern-match (`(get-in marker [:rf.mcp/overflow
  :limit])`) lands identically across the triplet. The causa-spec
  snippet omits the sentinel from the example for brevity; the
  conformance test is the load-bearing pin.

  Opts:
    :cap          - the cap in tokens that the response would exceed.
    :would-be     - the measured token count of the over-budget payload
                    (or char count when the secondary byte cap trips —
                    `apply-cap` picks the gate-relevant number).
    :hint         - one of `:switch-mode :paginate :slice :narrow-filter`
                    (validated against `hint-vocabulary`; out-of-vocab
                    inputs collapse to `default-hint`).
    :continuation - optional `{:cursor … :next-args …}` map. Omitted
                    when absent. Callers populate it only when the
                    tool has a cursor mid-stream or a meaningful
                    `:next-args` delta to suggest.

  The `:cursor` slot inside `:continuation` is an opaque server-
  managed string per spec/004 §3 — the agent does not inspect it,
  only passes it back. `:next-args` is the suggested per-call arg
  delta to retry with."
  [{:keys [cap would-be hint continuation]}]
  (let [hint*   (if (contains? hint-vocabulary hint) hint default-hint)
        marker  (cond-> {:limit    :reached
                         :cap      cap
                         :would-be would-be
                         :hint     hint*}
                  (some? continuation) (assoc :continuation continuation))]
    {:rf.mcp/overflow marker}))

;; ---------------------------------------------------------------------------
;; apply-cap — the wire-boundary enforcement entry point.
;; ---------------------------------------------------------------------------

(defn apply-cap
  "Wire-boundary cap enforcement. Returns either `result-js` unchanged
  (when under the cap) or a fresh JS-shape result carrying the
  `:rf.mcp/overflow` marker as its sole `:content[0].text` slot.

  ## Two-stage measurement (rf2-ih7g4)

  1. Primary token cap — `(quot count 4)` per Anthropic's English
     rule-of-thumb. The published contract pinned by the spec.

  2. Secondary char-byte cap — `cap * base-cap/byte-cap-multiplier`
     (8×). Defence-in-depth against payloads where the quotient
     undercounts: CJK, emoji, base64, dense code. Trips
     independently of the token sum; the gate-relevant measurement
     becomes `:would-be` on the marker.

  Either gate trips the same overflow path.

  ## Single-pass walk

  `content-texts` is materialised once; the token sum and char sum
  are accumulated in a single transduce over the resulting seq. The
  npm-SDK JS-shape `:text` slots are read via `j/get` exactly once
  per text node.

  ## Opts

    :tool         - string tool name. Today used as the lookup key
                    against `hints-by-tool`; carried on the marker
                    only implicitly via the chosen `:hint` keyword
                    (the agent doesn't need to know which tool
                    overflowed — the next-action is the cap-reached
                    behaviour, not blame-tracking).
    :cap          - integer cap in tokens. Usually the output of
                    `max-tokens-arg`. Required — pass
                    `default-max-tokens` for the standard default.
    :hint         - optional override of the per-tool hint table.
                    When supplied, must be in `hint-vocabulary`;
                    falls back to `default-hint` otherwise.
    :continuation - optional `{:cursor … :next-args …}` map spliced
                    onto the marker when present. Cursor-bearing
                    tools that overflow mid-stream supply the active
                    cursor here so the agent can resume.

  ## Nil-safety

  `nil` `result-js` passes through unchanged. `nil` `cap` is treated
  as a programmer error (the cap is normative; a caller that means
  `default-max-tokens` should pass that constant explicitly). The
  guard falls back to `default-max-tokens` rather than throwing so
  the wire stays bounded even on a buggy call-site."
  [result-js {:keys [tool cap hint continuation]}]
  (cond
    (nil? result-js) result-js
    :else
    (let [cap*     (or cap default-max-tokens)
          {:keys [tokens chars]}
          (transduce (filter string?)
                     (completing
                       (fn [{:keys [tokens chars]} s]
                         {:tokens (+ tokens (base-overflow/token-estimate s))
                          :chars  (+ chars (count s))}))
                     {:tokens 0 :chars 0}
                     (base-cap/content-texts result-io result-js))
          byte-cap (* cap* base-cap/byte-cap-multiplier)
          over?    (or (> tokens cap*) (> chars byte-cap))]
      (if-not over?
        result-js
        (let [would-be (if (> chars byte-cap) chars tokens)
              hint*    (cond
                         (and hint (contains? hint-vocabulary hint)) hint
                         hint                                        default-hint
                         :else                                       (hint-for-tool tool))
              marker   (overflow-payload {:cap          cap*
                                          :would-be     would-be
                                          :hint         hint*
                                          :continuation continuation})]
          (base-cap/build-overflow-result result-io marker result-js))))))
