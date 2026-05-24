(ns re-frame2-pair-mcp.tools.wire
  "MCP result helpers + per-call argument extraction (rf2-vrbwx split).

  Every tool returns an MCP `{:content [{:type \"text\" :text <edn-string>}]}`
  envelope, success or error. This namespace owns that wire shape plus
  the tiny `arg` / `arg-build` accessors every tool uses to pluck named
  args out of the JS-shaped `args` object the MCP host passes.

  Build-id resolution lives here too — `default-build-id` reads
  `SHADOW_CLJS_BUILD_ID` from `process.env`, falling back to `:app`."
  (:require [applied-science.js-interop :as j]
            [re-frame.mcp-base.envelope :as base-envelope]))

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

  Pure-data passthrough to `re-frame.mcp-base.envelope/with-indicators`
  (rf2-ee38b.18 / rf2-ee38b.19) — the slot keys are
  `base-vocab/dropped-sensitive-key` / `elided-large-key`, pinned once
  in the shared vocab so a key change can't drift across the pair. This
  thin re-export keeps the per-tool call-sites reading
  `wire/with-indicators` (the pair-local namespace they already
  require) while the rule body lives in the base.

  [1]: spec/Conventions.md#cross-mcp-indicator-field-vocabulary-suppression-counters
  [2]: spec/009-Instrumentation.md#size-elision-in-traces"
  [envelope counts]
  (base-envelope/with-indicators envelope counts))

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

(defn mark-resolved-build-id!
  "Record the build-id that `discover-app` just resolved on the conn-atom
  (rf2-l9ixp). Subsequent tool calls without an explicit `:build` arg
  default to this id instead of the `SHADOW_CLJS_BUILD_ID` / `:app`
  env-var fallback. Defensive against a non-atom `conn` (test stubs)."
  [conn build-id]
  (when (and (some? conn) (satisfies? IDeref conn))
    (swap! conn assoc :resolved-build-id build-id)))

(defn- conn-resolved-build-id
  "Read the session-scoped resolved-build-id cache from `conn` (rf2-l9ixp).
  Defensive against `nil` / non-atom conn — conformance tests pass a stub
  conn that doesn't carry the cache. Returns the cached keyword or nil."
  [conn]
  (when (and (some? conn) (satisfies? IDeref conn))
    (:resolved-build-id @conn)))

(defn arg-build
  "Resolve the build-id for this tool call. Precedence (highest first):

    1. Explicit `:build` MCP arg — operator override always wins
       (no surprise from the cache).
    2. Session-scoped resolved-build-id cache on the conn-atom — populated
       by `discover-app` after a successful preload probe (rf2-l9ixp).
       Removes the friction of repeating `build: foo` on every tool call
       after a successful discover-app. Cache resets on nREPL reconnect
       (same lifecycle as `:probed-builds`).
    3. `SHADOW_CLJS_BUILD_ID` env var, defaulting to `:app`.

  1-arity (`(arg-build args)`) is the legacy entry — used by call sites
  that have no `conn` in scope (notably `args.cljs`'s frame/build
  parsing). It SKIPS the conn cache and falls straight through to the
  env-var default. Production tool dispatch threads `conn` and uses the
  2-arity."
  ([args] (arg-build nil args))
  ([conn args]
   (or (arg-keyword args :build)
       (conn-resolved-build-id conn)
       (default-build-id))))

(defn arg-build-explicit?
  "True iff a deliberate build-id is available without falling back to
  the bare `SHADOW_CLJS_BUILD_ID` / `:app` env default. The eval-path
  build resolver (rf2-ivlb3) auto-detects the running build ONLY when
  the build is the bare default — a deliberate choice gets honoured
  verbatim (footgun-and-all; preflight catches typos).

  Two sources count as deliberate:

    - An explicit `:build` MCP arg on this call.
    - A session-scoped resolved-build-id cached on `conn` (rf2-l9ixp) —
      populated by a prior successful `discover-app`. Treating the cache
      as deliberate means a subsequent eval-cljs without `:build` routes
      to the resolved build instead of being auto-detect-rejected on a
      multi-build workspace.

  1-arity (`(arg-build-explicit? args)`) is the legacy form for callers
  with no `conn` in scope; it sees only the per-call arg."
  ([args] (arg-build-explicit? nil args))
  ([conn args]
   (or (some? (arg-keyword args :build))
       (some? (conn-resolved-build-id conn)))))

;; ---------------------------------------------------------------------------
;; Wire-bounded marker detection (rf2-gktyn, rf2-3z0zi; lifted to
;; mcp-base.envelope in rf2-ee38b.19 / rf2-ee38b.18).
;;
;; The `:rf.mcp/cache-hit` and `:rf.mcp/overflow` envelopes are
;; replacement results emitted by the wire-boundary steps themselves.
;; By construction they are sub-cap size — the cache-hit marker is
;; ~100 bytes and the overflow marker is the cap-respecting
;; replacement for an over-budget payload. Re-applying the cap walk
;; to either is wasted work and the cache check on a hit-marker
;; would compute a hash of the marker, not the original payload.
;;
;; The prefix-match logic + the marker KEYS (`base-vocab/cache-hit-key`
;; / `overflow-key`) live in `re-frame.mcp-base.envelope/marker-text?`
;; so a vocab change can't drift the detector. This fn owns only the
;; pair's JS-shape content accessor — it pulls the `:text` string off
;; the npm-SDK `#js` result and hands it to the shared detector.
;; ---------------------------------------------------------------------------

(defn marker?
  "Is `result-js` a wire-bounded `:rf.mcp/*` marker envelope?

  Returns true for `:rf.mcp/cache-hit` and `:rf.mcp/overflow`
  results — the two envelopes the cache + cap steps emit
  themselves. Such envelopes are sub-cap by construction and must
  not be re-walked by later boundary steps.

  Pulls the rendered text off the JS result shape; the prefix match is
  `re-frame.mcp-base.envelope/marker-text?`."
  [result-js]
  (let [content (when result-js (j/get result-js :content))
        item    (when (array? content) (aget content 0))
        text    (when item (j/get item :text))]
    (base-envelope/marker-text? text)))
