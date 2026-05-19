(ns re-frame2-pair-mcp.tools.wire
  "MCP result helpers + per-call argument extraction (rf2-vrbwx split).

  Every tool returns an MCP `{:content [{:type \"text\" :text <edn-string>}]}`
  envelope, success or error. This namespace owns that wire shape plus
  the tiny `arg` / `arg-build` accessors every tool uses to pluck named
  args out of the JS-shaped `args` object the MCP host passes.

  Build-id resolution lives here too — `default-build-id` reads
  `SHADOW_CLJS_BUILD_ID` from `process.env`, falling back to `:app`."
  (:require [applied-science.js-interop :as j]))

;; ---------------------------------------------------------------------------
;; Config — build id.
;; ---------------------------------------------------------------------------

(def ^:private cached-default-build-id
  "Cached at namespace load — the `SHADOW_CLJS_BUILD_ID` env var
  doesn't change at runtime, and re-reading it on every tool
  dispatch (11+ reads per call across `arg-build` and friends) is
  wasted cycles. A delay defers the read until the first call —
  important for shadow-cljs hot-reload paths where the file may
  load before `js/process.env` is fully populated."
  (delay
    (or (some-> (j/get-in js/process [:env :SHADOW_CLJS_BUILD_ID])
                keyword)
        :app)))

(defn default-build-id [] @cached-default-build-id)

;; ---------------------------------------------------------------------------
;; MCP result helpers.
;;
;; Every result envelope carries BOTH the pr-str-rendered EDN text
;; (the wire-canonical form — `:content [{:type \"text\" :text ...}]`)
;; AND a `:structuredContent` slot carrying the same value as a JS
;; object (rf2-hj3pi; mcp-builder canonical pattern). Agent hosts that
;; understand `:structuredContent` read the typed object directly; the
;; rest fall back to the EDN text. The two slots always agree on the
;; payload by construction — same `v`, two projections.
;;
;; The `:structuredContent` value is `clj->js v` — a JSON-coercible
;; projection (keywords lose their `:`, sets become arrays, etc.).
;; The text slot remains the source of truth for cljs-readable round-
;; trip; the structured slot is the SDK-friendly view.
;; ---------------------------------------------------------------------------

(defn ok-text
  "Success result envelope. Always emits both `:content` (the
  pr-str EDN text) and `:structuredContent` (the JS-coerced
  projection of the same value). Agent hosts that recognise
  `:structuredContent` read the typed object; others fall back to
  parsing the text slot."
  [v]
  #js {:content          #js [#js {:type "text" :text (pr-str v)}]
       :structuredContent (clj->js v)})

(defn err-text
  "Error result envelope. Same dual-slot shape as `ok-text` plus
  `:isError true` so the agent client surfaces the failure to the
  LLM without aborting the conversation (per MCP §Error Handling)."
  [v]
  #js {:isError          true
       :content          #js [#js {:type "text" :text (pr-str v)}]
       :structuredContent (clj->js v)})

(defn with-indicators
  "Splice the cross-MCP indicator-field slots (`:dropped-sensitive`,
  `:elided-large`) onto a tool's envelope map.

  Centralises the MUST-level \"omit when zero\" rule from
  [Conventions §Cross-MCP indicator-field vocabulary][1] and
  [Spec 009 §Indicator field on tool responses][2]. Every tool that
  walks a tree-typed payload (`snapshot`, `get-path`, `trace-window`,
  `watch-epochs`, `subscribe`) routes its envelope-tail through here so
  the rule lives in one place — drift across emit sites can no longer
  silently violate the MUST.

  [1]: spec/Conventions.md#cross-mcp-indicator-field-vocabulary-suppression-counters
  [2]: spec/009-Instrumentation.md#size-elision-in-traces"
  [envelope {:keys [dropped elided]}]
  (cond-> envelope
    (pos? (or dropped 0)) (assoc :dropped-sensitive dropped)
    (pos? (or elided  0)) (assoc :elided-large      elided)))

(defn arg
  "Extract an MCP tool argument by name. Returns nil if absent."
  [args k]
  (let [v (j/get args (name k))]
    (when-not (or (nil? v) (undefined? v)) v)))

(defn arg-keyword
  "Pluck an arg slot and coerce to a keyword via `(some-> v keyword)`.
  Returns nil when the slot is absent. Compresses the
  `(some-> (wire/arg args :foo) keyword)` pattern that recurs across
  the per-tool bodies (topic / frame plucks) — single source of truth
  for the str→kw coercion shape callers don't need to spell out."
  [args k]
  (some-> (arg args k) keyword))

(defn arg-build [args]
  (or (arg-keyword args :build)
      (default-build-id)))

;; ---------------------------------------------------------------------------
;; Wire-bounded marker detection (rf2-gktyn, rf2-3z0zi).
;;
;; The `:rf.mcp/cache-hit` and `:rf.mcp/overflow` envelopes are
;; replacement results emitted by the wire-boundary steps themselves.
;; By construction they are sub-cap size — the cache-hit marker is
;; ~100 bytes and the overflow marker is the cap-respecting
;; replacement for an over-budget payload. Re-applying the cap walk
;; to either is wasted work and the cache check on a hit-marker
;; would compute a hash of the marker, not the original payload.
;;
;; Substring-match on the rendered text is the cheap detector — the
;; markers always serialise with the namespaced key as the first
;; key of the outer map, so a `starts-with?` on the trimmed text is
;; fast and tight. False positives would require an agent-supplied
;; payload that ALSO renders as `{:rf.mcp/...` at the top level —
;; not a realistic shape for any tool's result.
;; ---------------------------------------------------------------------------

(def ^:private marker-prefixes
  ["{:rf.mcp/cache-hit"
   "{:rf.mcp/overflow"])

(defn marker?
  "Is `result-js` a wire-bounded `:rf.mcp/*` marker envelope?

  Returns true for `:rf.mcp/cache-hit` and `:rf.mcp/overflow`
  results — the two envelopes the cache + cap steps emit
  themselves. Such envelopes are sub-cap by construction and must
  not be re-walked by later boundary steps."
  [result-js]
  (let [content (when result-js (j/get result-js :content))
        item    (when (array? content) (aget content 0))
        text    (when item (j/get item :text))]
    (and (string? text)
         (boolean (some #(.startsWith text %) marker-prefixes)))))
