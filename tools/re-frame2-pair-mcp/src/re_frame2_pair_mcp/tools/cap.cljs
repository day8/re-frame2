(ns re-frame2-pair-mcp.tools.cap
  "Wire-boundary token-budget cap (rf2-rvyzy / rf2-eyelu).

  Per `spec/Principles.md` §\"Tight token budget per response\", every
  MCP `tools/call` response is bounded at ~5,000 tokens by default.
  The cap is enforced here, not just documented: when the serialised
  response would exceed the cap, the wrapper replaces the payload with
  a structured `{:rf.mcp/overflow {...}}` marker and emits that
  instead. Silent truncation is unacceptable — it corrupts the agent's
  conversation without telling the agent.

  ## Pipeline ownership

  The cap-enforcement ALGORITHM lives in `re-frame.mcp-base.cap`
  (rf2-eyelu) — token-summing across `:text` slots, comparing against
  the per-call cap, and building the overflow result. This ns supplies
  the per-server specialisation:

  - `result-io` reifies `mcp-base.cap/ResultIO` over re-frame2-pair-mcp's
    `#js {:content #js [...]}` shape (JS-native, npm MCP SDK).
  - `overflow-hints` is the local hint table — the per-tool next-step
    prose stays here because the surfaces are domain-specific.
  - `max-tokens-arg` is the JS-side adapter that lifts the
    `\"max-tokens\"` slot off the args object before delegating to the
    base resolver.

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
  - **Pluggable strategy**: `base-cap/apply-cap` dispatches on a
    strategy keyword. Today only `:truncate-with-marker` is
    implemented — replace the payload with the overflow marker.
    Future strategies (path-slicing rf2-tygdv, lazy summary
    rf2-u2029, diff encoding rf2-rl7y, etc.) compose in the base
    without rebuilding the wrapper here.
  - **Centralised**: applied as the final step in `invoke`. Per-tool
    functions are untouched; they emit the same shapes they always
    did. The wire-cap is a property of the egress boundary, not of
    each tool's internals."
  (:require [applied-science.js-interop :as j]
            [re-frame.mcp-base.cap :as base-cap]
            [re-frame.mcp-base.overflow :as base-overflow]
            [re-frame2-pair-mcp.tools.wire :as wire]))

;; `default-max-tokens` and the character→token approximation
;; (`base-overflow/token-estimate`) come from `re-frame.mcp-base.overflow`
;; (rf2-vw4sq) — both are cross-MCP conventions pinned once in the base.
;; `default-max-tokens` is re-exported here because production call sites
;; reference `cap/default-max-tokens`; the test-only `token-estimate`
;; re-export moved to `test-utils` (rf2-ttspi7) — production code calls
;; `base-overflow/token-estimate` directly.
(def default-max-tokens base-overflow/default-max-tokens)

(defn max-tokens-arg
  "Resolve the per-call cap from MCP args. Returns the integer cap in
  tokens, or `nil` when the cap is disabled (caller passed `0`).
  Defaults to `default-max-tokens` when absent or not a number.

  Reads the JS-side `\"max-tokens\"` slot off the args object, then
  delegates the coercion to `base-cap/max-tokens` (the cross-MCP
  resolver)."
  [args]
  (let [raw (when args (j/get args "max-tokens"))]
    (base-cap/max-tokens (when (and (not (undefined? raw)) (some? raw)) raw))))

(defn invalid-arg?
  "True when `max-tokens-arg` rejected the per-call `:max-tokens` arg
  (rf2-5rdit) — a negative value resolves to a
  `{:rf.mcp/invalid-arg {...}}` rejection rather than a cap. The
  `invoke` chokepoint tests this and short-circuits into an
  `isError: true` result via `wire/err-text` instead of threading the
  malformed cap into the wire-boundary pipeline. Delegates to
  `base-cap/invalid-arg?` (the cross-MCP predicate)."
  [x]
  (base-cap/invalid-arg? x))

(def overflow-hints
  "Tool-specific next-step hints for the overflow marker. Generic
  fallback when a tool isn't listed."
  {"snapshot"      "Narrow scope: pass `path [:k1 :k2]` to slice the :app-db slice, `frames` to a single frame, or `include` to a single slice (one of app-db, sub-cache, machines, epochs, traces). Default mode is :summary — drill down via `get-path` once you know the key."
   "get-path"      "Narrow the path further — pass a deeper segment so the addressed subtree is smaller. Or call `snapshot` with no `path` first for a tree-summary, then re-aim."
   "trace-window"  "Reduce `ms` to a smaller window, narrow with `frame`, or fetch incrementally via `watch-epochs` + `since-id`."
   "watch-epochs"  "Narrow `pred` (e.g. `:event-id-prefix`, `:effects`), pass `frame`, or stream via `subscribe` with `max-events`."
   "subscribe"     "Tighten `filter`, lower `max-buffered-events` / `max-buffered-bytes`, set `max-events` so each tick stays small."
   "eval-cljs"     "Slice the value at the call-site (`get-in`, `take`, project to fewer keys) before returning."
   "discover-app"  "Unusual — the health summary should be small. Inspect `(re-frame2-pair.runtime/health)` directly via `eval-cljs` with a projection."
   "dispatch"      "Trace mode is returning a full epoch — re-run with `trace false` and read the epoch via `watch-epochs`/`snapshot` with a narrower path."})

;; The fallback string lives in `re-frame.mcp-base.overflow` so the
;; cross-MCP marker presents identically. Production reads it via
;; `base-overflow/overflow-hint-fallback` (or, more usually, lets
;; `base-cap/apply-cap` apply the fallback when a tool has no specific
;; hint). The test-only `overflow-hint-fallback` re-export moved to
;; `test-utils` (rf2-ttspi7).

(def ^:private result-io
  "ResultIO reify over re-frame2-pair-mcp's `#js {:content #js [...]}` shape
  (npm MCP SDK). Reads `:text` slots via `js-interop` and builds the
  overflow result with the same JS shape so the SDK can serialise it
  as a `tools/call` reply unchanged.

  HOT PATH (rf2-ycfu1; mirrors story-mcp's rf2-mzndx fix): `wire/ok-text`
  / `wire/err-text` write the SAME payload into BOTH `:content[*].text`
  (the pr-str EDN) AND `:structuredContent` (the `clj->js` JSON
  projection) on EVERY result. Both slots ride the wire — the
  `:structuredContent` JSON is the npm SDK's serialised tool-result
  body just as much as the text slot is. Cap accounting MUST size the
  structured slot too; otherwise the cap undercounts wire by ~50% and a
  small-`:content` / huge-`:structuredContent` response slips past the
  overflow marker and busts the MCP token budget. We surface the
  structured slot as one extra string — `js/JSON.stringify` of the JS
  object, the exact wire bytes — appended to the `wire-payload-strings` seq;
  `sum-payload-tokens` then transduces it alongside the `:text` strings
  under one budget. (story-mcp uses `pr-edn` because its structured slot
  is a CLJ map; pair-mcp's is a JS object, so `JSON.stringify` is the
  byte-faithful equivalent.)"
  (reify base-cap/ResultIO
    (wire-payload-strings [_ result]
      (let [content (j/get result :content)
            n       (if (array? content) (.-length content) 0)
            sc      (when result (j/get result :structuredContent))]
        ;; Seq over the JS array's :text slots, plus a stringified
        ;; :structuredContent when present. `apply-cap` transduces with
        ;; a `(filter string?)` upstream, so any missing slot passes
        ;; through as nil and is dropped.
        (loop [i 0 acc (transient [])]
          (if (< i n)
            (let [item (aget content i)
                  text (when item (j/get item :text))]
              (recur (inc i) (conj! acc text)))
            (persistent!
             (cond-> acc
               (and (some? sc) (not (undefined? sc)))
               (conj! (js/JSON.stringify sc))))))))
    (build-overflow-result [_ marker _original]
      ;; rf2-or8s29 — route through `wire/result` so the overflow marker's
      ;; structuredContent keeps its namespace: a raw `clj->js` truncated
      ;; the `:rf.mcp/overflow` marker key to `"overflow"`, so SDK-friendly
      ;; hosts reading structuredContent missed the marker.
      (wire/result marker false))))

(defn sum-payload-tokens
  "Sum `token-estimate` across every wire-bearing slot in the MCP
  `{:content [{:type \"text\" :text ...} ...] :structuredContent ...}`
  result — both the `:content[*].text` strings AND the
  `:structuredContent` JSON projection (rf2-ycfu1). Both ride the wire
  as the serialised tool-result body; the bounded JSON envelope keys
  are ignored.

  Delegates to `base-cap/sum-payload-tokens` against re-frame2-pair-mcp's
  JS-shape `result-io`, whose `wire-payload-strings` surfaces the structured
  slot as `js/JSON.stringify` of the JS object."
  [result-js]
  (base-cap/sum-payload-tokens result-io result-js))

(defn apply-cap
  "Wire-boundary cap enforcement. Returns either `result-js` unchanged
  (when under the cap or cap disabled) or a fresh result carrying the
  overflow marker.

  Pluggable on `strategy` — see `base-cap/apply-cap`. Today only
  `:truncate-with-marker` is wired; unknown strategies degrade safely.

  Adds re-frame2-pair-mcp's `overflow-hints` table lookup before delegating to
  `base-cap/apply-cap`.

  Short-circuits on a wire-bounded marker (`:rf.mcp/cache-hit`,
  `:rf.mcp/overflow` — rf2-gktyn). Such envelopes are sub-cap by
  construction; the token-sum walk would be wasted work. The
  `invoke` pipeline (rf2-3z0zi) already short-circuits before this
  fn runs in the orchestrated path, but the guard here makes the
  invariant local to `apply-cap` too — direct callers (tests, future
  consumers) get the same skip.

  ## `:isError` is NOT a short-circuit (intentional asymmetry vs cache)

  Unlike `cache/apply-cache` — which passes `:isError` through
  untouched so a transient failure can't poison the cache —
  `apply-cap` measures and (if over budget) wraps an `:isError`
  result in `:rf.mcp/overflow` like any other payload. An error
  response can itself carry an oversize `:message` blob (e.g. a
  stack trace pretty-printed from a deep CLJS exception) and silent
  over-budget egress would violate the rf2-rvyzy contract that
  every response is the wire-cap-respecting marker OR a sub-cap
  payload — never a verbatim over-budget body. The cache only
  cares about identity; the cap cares about byte count, and an
  error's bytes count just like a success's."
  [result-js {:keys [tool cap strategy]
              :or   {strategy :truncate-with-marker}}]
  (if (wire/marker? result-js)
    result-js
    (base-cap/apply-cap result-io result-js
                        {:tool     tool
                         :cap      cap
                         :hint     (get overflow-hints tool)
                         :strategy strategy})))

(defn cap-message
  "Per-notification wire-cap for a single serialised EDN message string
  (rf2-wz66k7). Returns `message` unchanged when under the cap (or the
  cap is disabled via `nil`), else the `pr-str` of the same
  `{:rf.mcp/overflow {...}}` marker the result path emits.

  Unlike `apply-cap` — which walks an MCP result's `:content` / structured
  slots — this caps the ONE pre-serialised `:message` string the
  `subscribe` streaming loop ships in a `notifications/progress`
  notification. The MCP `tools/call` RESULT path is capped at the `invoke`
  chokepoint (`apply-cap`), but a progress NOTIFICATION never crosses that
  chokepoint, so the per-tick payload would otherwise egress un-capped —
  busting the per-notification token budget the spec pins
  (`spec/Principles.md` §Streaming over batch: \"the cap applies per
  notification\"; §Subscribe streaming: progress frames apply the same
  wrap per-tick; `003-Tool-Catalogue.md` §Universal dedup: the per-tick
  `:events` vector participates in the wire-cap discipline). This is the
  same cap-completeness class as rf2-ycfu1 (count `:structuredContent`
  toward the cap) — a wire-bearing payload that bypassed the gate.

  Uses the SAME two-stage gate (`base-cap/over-cap?` — token sum OR the
  secondary char gate) and the SAME `overflow/overflow-payload` shape as
  the result path, so an agent that learned the `:rf.mcp/overflow` marker
  on a `tools/call` result recognises it identically on the progress
  channel. The overflow marker carries the `subscribe` per-tool hint so
  the agent's next step (tighten `filter`, lower the buffer caps, set
  `max-events`) is specific."
  [message {:keys [tool cap]}]
  (if (or (nil? cap) (nil? message))
    message
    (let [tokens (base-overflow/token-estimate message)
          chars  (count message)]
      (if-not (base-cap/over-cap? tokens chars cap)
        message
        (pr-str (base-overflow/overflow-payload
                  {:tool        tool
                   :token-count (base-cap/reported-count tokens chars cap)
                   :cap         cap
                   :hint        (get overflow-hints tool)}))))))
