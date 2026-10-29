(ns re-frame2-pair-mcp.tools.cap
  "Wire-boundary token-budget cap.

  Per `spec/Principles.md` §\"Tight token budget per response\", every
  MCP `tools/call` response is bounded at ~5,000 tokens by default.
  The cap is enforced here, not just documented: when the serialised
  response would exceed the cap, the wrapper replaces the payload with
  a structured `{:rf.mcp/overflow {...}}` marker and emits that
  instead. Silent truncation is unacceptable — it corrupts the agent's
  conversation without telling the agent.

  ## Pipeline ownership

  The cap-enforcement ALGORITHM lives in `re-frame.mcp-base.cap`
  — token-summing across `:text` slots, comparing against
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
    integer cap, `0` disables. Default `5000`.
  - **One strategy**: an over-cap payload is replaced whole with the
    overflow marker.
  - **Centralised**: applied as the final step in `invoke`. Per-tool
    functions emit their own shapes and the wire-cap is a property of
    the egress boundary. The one exception is a paginated read, whose
    page can be cut at any record: `trace-window` and `watch-epochs`
    hand [[fit-page]] a page builder, so an over-cap page comes back as
    the longest prefix of its records that fits, with a cursor to the
    rest, rather than as the marker. They measure with [[fits?]], the
    cap's own gate, so a fitted page always passes it."
  (:require [applied-science.js-interop :as j]
            [re-frame.mcp-base.cap :as rf.mcp-base.cap]
            [re-frame.mcp-base.overflow :as rf.mcp-base.overflow]
            [re-frame2-pair-mcp.tools.wire :as wire]))

;; `default-max-tokens` and the character→token approximation
;; (`rf.mcp-base.overflow/token-estimate`) come from `re-frame.mcp-base.overflow`
;; — both are cross-MCP conventions pinned once in the base.
;; `default-max-tokens` is re-exported here because production call sites
;; reference `cap/default-max-tokens`; production code calls
;; `rf.mcp-base.overflow/token-estimate` directly.
(def default-max-tokens rf.mcp-base.overflow/default-max-tokens)

(defn max-tokens-arg
  "Resolve the per-call cap from MCP args. Returns the integer cap in
  tokens, or `nil` when the cap is disabled (caller passed `0`).
  Defaults to `default-max-tokens` when absent or not a number.

  Reads the JS-side `\"max-tokens\"` slot off the args object, then
  delegates the coercion to `rf.mcp-base.cap/max-tokens` (the cross-MCP
  resolver)."
  [args]
  (let [raw (when args (j/get args "max-tokens"))]
    (rf.mcp-base.cap/max-tokens (when (and (not (undefined? raw)) (some? raw)) raw))))

(defn invalid-arg?
  "True when `max-tokens-arg` rejected the per-call `:max-tokens` arg
  — a negative value resolves to a
  `{:rf.mcp/invalid-arg {...}}` rejection rather than a cap. The
  `invoke` chokepoint tests this and short-circuits into an
  `isError: true` result via `wire/err-text` instead of threading the
  malformed cap into the wire-boundary pipeline. Delegates to
  `rf.mcp-base.cap/invalid-arg?` (the cross-MCP predicate)."
  [x]
  (rf.mcp-base.cap/invalid-arg? x))

(def overflow-hints
  "Tool-specific next-step hints for the overflow marker. Generic
  fallback when a tool isn't listed."
  {"snapshot"      "Narrow scope: pass `path [:k1 :k2]` to slice the :app-db slice, `frames` to a single frame, or `include` to a single slice (one of app-db, sub-cache, machines, epochs, traces). Default mode is :summary — drill down via `get-path` once you know the key."
   "get-path"      "Narrow the path further — pass a deeper segment so the addressed subtree is smaller. Or call `snapshot` with no `path` first for a tree-summary, then re-aim."
   "trace-window"  "This read already cuts its page to the records that fit, so the marker means `max-tokens` is below even an empty page. Raise `max-tokens` (0 disables the cap)."
   "watch-epochs"  "This read already cuts its page to the records that fit, so the marker means `max-tokens` is below even an empty page. Raise `max-tokens` (0 disables the cap)."
   "eval-cljs"     "Slice the value at the call-site (`get-in`, `take`, project to fewer keys) before returning."
   "discover-app"  "Unusual — the health summary should be small. Inspect `(re-frame2-pair.runtime/health)` directly via `eval-cljs` with a projection."
   "orient"        "orient takes no narrowing argument and already caps each id list at 20 examples, so the bulk is frame lists or wide app-db top levels. Read ids with `list-handlers` (`kind`, optional `frame`), one frame's state shape with `snapshot` (`path`), or re-call orient with a larger `max-tokens` (0 disables the cap)."
   "dispatch"       "The event was dispatched and already ran — do NOT dispatch it again. Read its epoch with `trace-window` or `watch-epochs`, which page their records to fit `max-tokens`; pass `trace false` next time to keep the reply small."
   "replay-epoch"   "The replay already ran — do NOT replay again. Read the new epoch with `trace-window` or `watch-epochs`, which page their records to fit `max-tokens`."
   "restore-epoch"  "The restore already ran — do NOT restore again. Read the state with `get-path` or `snapshot` (default summary mode)."
   "replace-app-db" "app-db was already replaced — do NOT replace it again. Read the state with `get-path` or `snapshot` (default summary mode)."})

;; The fallback string lives in `re-frame.mcp-base.overflow` so the
;; cross-MCP marker presents identically. Production reads it via
;; `rf.mcp-base.overflow/overflow-hint-fallback` (or, more usually, lets
;; `rf.mcp-base.cap/apply-cap` apply the fallback when a tool has no specific
;; hint).

(def ^:private result-io
  "ResultIO reify over re-frame2-pair-mcp's `#js {:content #js [...]}` shape
  (npm MCP SDK). Reads `:text` slots via `js-interop` and builds the
  overflow result with the same JS shape so the SDK can serialise it
  as a `tools/call` reply unchanged.

  HOT PATH (mirrors story-mcp's structured-slot accounting): `wire/ok-text`
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
  (reify rf.mcp-base.cap/ResultIO
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
    (build-overflow-result [_ marker original]
      ;; Route through `wire/result` so the overflow marker's
      ;; structuredContent keeps its namespace: a raw `clj->js` would
      ;; truncate the `:rf.mcp/overflow` marker key to `"overflow"`, so
      ;; SDK-friendly hosts reading structuredContent would miss the marker.
      ;; The original's `isError` crosses too: an over-cap FAILED call
      ;; must not read as an over-cap success.
      (wire/result marker (true? (j/get original :isError))))))

(defn sum-payload-tokens
  "Sum `token-estimate` across every wire-bearing slot in the MCP
  `{:content [{:type \"text\" :text ...} ...] :structuredContent ...}`
  result — both the `:content[*].text` strings AND the
  `:structuredContent` JSON projection. Both ride the wire
  as the serialised tool-result body; the bounded JSON envelope keys
  are ignored.

  Delegates to `rf.mcp-base.cap/sum-payload-tokens` against re-frame2-pair-mcp's
  JS-shape `result-io`, whose `wire-payload-strings` surfaces the structured
  slot as `js/JSON.stringify` of the JS object."
  [result-js]
  (rf.mcp-base.cap/sum-payload-tokens result-io result-js))

(defn apply-cap
  "Wire-boundary cap enforcement. Returns either `result-js` unchanged
  (when under the cap or cap disabled) or a fresh result carrying the
  overflow marker.

  Adds re-frame2-pair-mcp's `overflow-hints` table lookup before delegating to
  `rf.mcp-base.cap/apply-cap`.

  Short-circuits on a wire-bounded marker (`:rf.mcp/cache-hit`,
  `:rf.mcp/overflow`). Such envelopes are sub-cap by
  construction; the token-sum walk would be wasted work. The
  `invoke` pipeline already short-circuits before this
  fn runs in the orchestrated path, but the guard here makes the
  invariant local to `apply-cap` too — direct callers (tests, future
  consumers) get the same skip.

  ## `:isError` is NOT a short-circuit (intentional asymmetry vs cache)

  Unlike `cache/apply-cache` — which passes `:isError` through
  untouched so a failure never becomes a success-shaped cache-hit —
  `apply-cap` measures and (if over budget) wraps an `:isError`
  result in `:rf.mcp/overflow` like any other payload — and the
  replacement keeps `isError: true`, so the failure stays visible.
  An error
  response can itself carry an oversize `:message` blob (e.g. a
  stack trace pretty-printed from a deep CLJS exception) and silent
  over-budget egress would violate the wire-cap contract that
  every response is the wire-cap-respecting marker OR a sub-cap
  payload — never a verbatim over-budget body. The cache only
  cares about identity; the cap cares about byte count, and an
  error's bytes count just like a success's."
  [result-js {:keys [tool cap]}]
  (if (wire/marker? result-js)
    result-js
    (rf.mcp-base.cap/apply-cap result-io result-js
                               {:tool tool
                                :cap  cap
                                :hint (get overflow-hints tool)})))

(defn fits?
  "True when `result-js` passes [[apply-cap]] unchanged at `cap` — the cap's
  own token and character gates, over the same wire slots. A nil `cap` (the
  cap disabled) always fits."
  [result-js cap]
  (or (nil? cap)
      (identical? result-js (rf.mcp-base.cap/apply-cap result-io result-js {:cap cap}))))

(defn page-cap
  "The cap a paginated read fits its page to: the call's resolved
  `max-tokens`, or nil when the cap is disabled. An out-of-domain value is
  refused at `invoke` before any tool runs, so it reads as nil here."
  [args]
  (let [cap (max-tokens-arg args)]
    (when-not (invalid-arg? cap) cap)))

(defn fit-page
  "The wire result for the longest prefix of a paginated read's `n` records
  that fits `cap`. `page-result` maps a record count `k` (0..n) to the whole
  result for a page of the first `k` records, including its continuation
  cursor. A page that fits whole costs one call; an over-cap one is bisected
  for the largest fitting `k`, on the premise that a page never shrinks as it
  gains a record. When no `k` above 0 fits, the result is `(page-result 0)`,
  which the caller shapes to say why the page is empty."
  [n cap page-result]
  (let [render (memoize page-result)]
    (if (or (zero? n) (fits? (render n) cap))
      (render n)
      (loop [lo 0 hi (dec n)]
        (if (>= lo hi)
          (render lo)
          (let [mid (quot (+ lo hi 1) 2)]
            (if (fits? (render mid) cap)
              (recur mid hi)
              (recur lo (dec mid)))))))))

