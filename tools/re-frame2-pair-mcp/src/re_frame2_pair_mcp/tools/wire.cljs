(ns re-frame2-pair-mcp.tools.wire
  "MCP result helpers + per-call argument extraction (rf2-vrbwx split).

  Every tool returns an MCP `{:content [{:type \"text\" :text <edn-string>}]}`
  envelope, success or error. This namespace owns that wire shape plus
  the tiny `arg` / `arg-build` accessors every tool uses to pluck named
  args out of the JS-shaped `args` object the MCP host passes.

  Build-id resolution lives here too — `default-build-id` reads
  `SHADOW_CLJS_BUILD_ID` from `process.env`, falling back to `:app`."
  (:require [applied-science.js-interop :as j]
            [re-frame.mcp-base.args :as base-args]
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
;; The `:structuredContent` value is a JSON-coercible projection of `v`
;; (keywords lose their `:`, sets become arrays, etc.). The text slot
;; remains the source of truth for the cljs-readable round-trip; the
;; structured slot is the SDK-friendly view.
;;
;; ## Namespaced keywords keep their namespace (rf2-t2n04)
;;
;; A bare `(clj->js v)` is namespace-LOSSY in two compounding ways, and
;; both silently corrupt key-bearing reads:
;;
;;   - For MAP KEYS, `clj->js`'s default `:keyword-fn` is `name`, which
;;     drops the namespace: `:rf/runtime` → `"runtime"`, not `"rf/runtime"`.
;;   - For keyword VALUES, `clj->js` ALWAYS uses `(name v)` (the
;;     `:keyword-fn` option is consulted for keys ONLY), so machine-id
;;     values `:door/main :traffic/light` collapse to `"main" "light"`.
;;
;; The damage is not cosmetic: a `snapshot` whose `(keys app-db)` carries
;; `:rf/runtime` projects to the name-only `"runtime"`, so an agent that
;; reads the structured slot and threads `[:runtime ...]` back through
;; `get-path` hits `nil` — the real key is `:rf/runtime`. (This sent a
;; live 2026-06-04 session down a false "snapshots are empty" path.)
;; Same defect class as the machines-viz `:door/open` → `"open"` tag
;; truncation (#2922): stringify keywords to their FULLY-QUALIFIED form
;; at the `clj->js` boundary so the namespace survives.
;;
;; The fix pre-walks `v`, replacing every keyword (key OR value) with
;; `(str (symbol kw))` — the colon-less fully-qualified token
;; (`:rf/runtime` → `"rf/runtime"`, `:ok?` → `"ok?"`) — BEFORE `clj->js`
;; sees it. `clj->js` then only structurally coerces the (now
;; keyword-free) tree; sets/vectors/maps coerce exactly as before. This
;; is also what the spec's documented projection already PROMISED —
;; `:structuredContent` "drops the leading colon — ["rf/default"]"
;; (003-Tool-Catalogue §Id representation, rf2-cg37y); the code was
;; merely failing to honour its own contract. The EDN text slot
;; (`pr-str v`) is untouched: it stays the canonical, fully-typed,
;; cljs-`read`-able round-trip.
;;
;; ## structuredContent must never be `null` (rf2-r5erl)
;;
;; Every tool descriptor declares a map-shaped `:outputSchema`
;; (`{:type "object" ...}`, rf2-3l3be). When a descriptor carries an
;; `:outputSchema`, the npm MCP SDK VALIDATES the result's
;; `structuredContent` against it — and a `null` is not an object, so
;; the SDK rejects the whole `tools/call` with a host-level
;; `Invalid tools/call result: expected record at structuredContent,
;; received null` BEFORE the EDN ever reaches the agent. That bypasses
;; re-frame2-pair's `{:ok? false ...}` error contract entirely.
;;
;; A `null` reaches here exactly when `v` is `nil` — e.g. a tool that
;; threads a blank nREPL eval result straight into `ok-text`
;; (`cljs-eval-value` returns `nil` for a blank value). `read-dom`
;; hit this in the wild (rf2-r5erl); `read-ui` did not, by luck of
;; always getting a map back. Rather than rely on every call-site
;; guarding nil, we make the wire shape TOTAL: a `nil` payload
;; projects to a structured `{:rf.mcp/null true}` sentinel object so
;; `structuredContent` is always a record the SDK accepts. The EDN
;; text slot still carries the verbatim `(pr-str v)` (`"nil"`) so the
;; cljs-readable round-trip is unchanged for hosts that read the text.
;; ---------------------------------------------------------------------------

(def ^:private null-structured-sentinel
  "JS object substituted for `structuredContent` when the EDN payload is
  `nil` (rf2-r5erl). A `null` structuredContent fails the SDK's
  outputSchema validation (`expected record ... received null`); this
  sub-object keeps the slot a record. Hosts that read the EDN text slot
  see the verbatim `nil`; hosts that read the structured slot see the
  sentinel and know the payload was empty."
  {"rf.mcp/null" true})

(defn- qualify-keywords
  "Recursively replace every keyword in `x` (map KEY or VALUE, at any
  depth, in maps / vectors / lists / sets) with its colon-less
  fully-qualified string token via `(str (symbol kw))`
  (`:rf/runtime` → `\"rf/runtime\"`, `:ok?` → `\"ok?\"`), leaving every
  non-keyword scalar and collection structure otherwise intact.

  Applied to a payload BEFORE `clj->js` so the structured projection
  preserves keyword namespaces (rf2-t2n04). `clj->js`'s built-in
  keyword handling is namespace-lossy for both keys (default
  `:keyword-fn` is `name`) and values (always `name`); pre-stringifying
  here is the single point that closes both holes. `clj->js` then sees
  no keywords and only coerces the surrounding structure
  (vectors → arrays, sets → arrays, maps → objects) unchanged.

  `(symbol kw)` carries the namespace into the symbol's `str`
  (`(str (symbol :a/b))` ⇒ `\"a/b\"`); a bare `(name kw)` would drop it.
  Hand-rolled (rather than `clojure.walk/postwalk`) so it stays a
  dependency-free, allocation-frugal single pass that touches only the
  collection branches that can contain keywords."
  [x]
  (cond
    (keyword? x) (str (symbol x))
    (map? x)     (persistent!
                  (reduce-kv (fn [m k v]
                               (assoc! m (qualify-keywords k) (qualify-keywords v)))
                             (transient {})
                             x))
    (set? x)     (into #{} (map qualify-keywords) x)
    (vector? x)  (mapv qualify-keywords x)
    (seq? x)     (map qualify-keywords x)
    :else        x))

(defn- structured-of
  "Project `v` to the `:structuredContent` JS value, guaranteeing a
  non-null record (rf2-r5erl) AND preserving keyword namespaces
  (rf2-t2n04).

  Keyword namespaces are kept by pre-walking `v` through
  `qualify-keywords` before `clj->js` — a bare `clj->js` drops the
  namespace on both map keys and keyword values. The EDN `:content`
  text slot (`pr-str v`) is the namespace-faithful canonical form;
  this slot is the SDK-friendly mirror.

  `(clj->js v)` is `null` exactly when `v` is `nil`; substitute the
  `:rf.mcp/null` sentinel object so the SDK's outputSchema validation
  (which requires an object) never rejects the result at the transport
  layer."
  [v]
  (if (nil? v)
    (clj->js null-structured-sentinel)
    (let [sc (clj->js (qualify-keywords v))]
      ;; clj->js of a non-nil scalar can still be a JS primitive (a
      ;; number / string / boolean), which is also not a record. Wrap
      ;; any non-object projection in the sentinel-shaped envelope so
      ;; the slot is always object-typed. (Tool payloads are maps in
      ;; practice; this is the total-function backstop.) The wrapped
      ;; value reuses the keyword-qualified projection so a bare-keyword
      ;; payload keeps its namespace inside the envelope too.
      (if (object? sc) sc (clj->js {"rf.mcp/value" (qualify-keywords v)})))))

(defn structured-content
  "Public projection of `v` to the SDK-friendly `:structuredContent` JS
  value — the SAME namespace-preserving, null-safe projection `ok-text`
  / `err-text` use (rf2-t2n04, rf2-r5erl).

  Exposed (rf2-or8s29) for the handful of result envelopes built OUTSIDE
  the per-tool callbacks — server-level handler-threw / discovery
  errors (server.cljs), the cache-hit marker (cache.cljs), and the
  overflow marker (cap.cljs). Those sites previously built
  `:structuredContent (clj->js payload)` directly, which is
  namespace-LOSSY: `:rf.mcp/cache-hit` → `\"cache-hit\"`,
  `:rf.mcp/overflow` → `\"overflow\"`, and `:rf.error/*` reason VALUES
  lose their namespace. Routing them through this single helper keeps
  the structured-content round-trip contract intact everywhere."
  [v]
  (structured-of v))

(defn result
  "Build an arbitrary MCP result envelope (rf2-or8s29) carrying both the
  pr-str EDN `:content` text and the namespace-preserving
  `:structuredContent` projection of the SAME `v`. Sets `:isError true`
  when `error?` is truthy.

  This is the single canonical envelope constructor; `ok-text` /
  `err-text` are the `(result v false)` / `(result v true)` shorthands.
  Result-envelope builders OUTSIDE the per-tool callbacks (server-level
  errors, cache-hit + overflow markers) route through here so they get
  the same namespace-faithful structured projection rather than a raw,
  namespace-lossy `clj->js`."
  [v error?]
  (if error?
    #js {:isError          true
         :content          #js [#js {:type "text" :text (pr-str v)}]
         :structuredContent (structured-of v)}
    #js {:content          #js [#js {:type "text" :text (pr-str v)}]
         :structuredContent (structured-of v)}))

(defn ok-text
  "Success result envelope. Always emits both `:content` (the
  pr-str EDN text) and `:structuredContent` (the JS-coerced
  projection of the same value). Agent hosts that recognise
  `:structuredContent` read the typed object; others fall back to
  parsing the text slot.

  `:structuredContent` is guaranteed a non-null record (rf2-r5erl) —
  a `nil` payload projects to the `:rf.mcp/null` sentinel object so the
  SDK's outputSchema validation never rejects the result for a `null`
  structuredContent."
  [v]
  (result v false))

(defn err-text
  "Error result envelope. Same dual-slot shape as `ok-text` plus
  `:isError true` so the agent client surfaces the failure to the
  LLM without aborting the conversation (per MCP §Error Handling).
  `:structuredContent` is non-null by construction (rf2-r5erl)."
  [v]
  (result v true))

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

(defn- canonicalize-via-alias
  "Map `build-id` through the conn's `:build-alias` forgiving-resolution
  cache (rf2-qda59). When the requested id has a recorded
  suffix→canonical alias (populated by `probe/canonicalize-build!` at the
  pipeline's first step), return the canonical running id; otherwise
  return `build-id` unchanged. Defensive against a nil / non-atom conn
  (test stubs carry no cache) and a nil `build-id`."
  [conn build-id]
  (if (and (some? build-id)
           (some? conn)
           (satisfies? IDeref conn))
    (get (:build-alias @conn {}) build-id build-id)
    build-id))

(defn arg-build
  "Resolve the build-id for this tool call. Precedence (highest first):

    1. Explicit `:build` MCP arg — operator override always wins
       (no surprise from the cache).
    2. Session-scoped resolved-build-id cache on the conn-atom — populated
       by `discover-app` after a successful preload probe (rf2-l9ixp) AND
       by `stick-build!` after an explicit `:build` on any non-discover
       tool call (rf2-lbm21). Removes the friction of repeating
       `build: foo` on every tool call. Cache resets on nREPL reconnect
       (same lifecycle as `:probed-builds`).
    3. `SHADOW_CLJS_BUILD_ID` env var, defaulting to `:app`.

  `arg-build` is a PURE READ — it never writes the cache. The
  session-sticky write happens at the `invoke` boundary via
  `stick-build!` (rf2-lbm21), deliberately separated so `discover-app`
  (which resolves a build via `arg-build` to PROBE it, and caches only
  on a successful health check) is never forced to cache a build that
  turned out unusable.

  The explicit `:build` arg is coerced via
  `re-frame.mcp-base.args/fresh-keyword` (rf2-8ohwv), which strips a
  leading colon: `\"examples/step-deck\"` and `\":examples/step-deck\"`
  resolve identically. A bare `keyword` on the colon form would mint the
  malformed `::examples/step-deck` (`cljs-eval` then renders it
  `\"::examples/step-deck\"` and probes a build that doesn't exist) — the
  failed-round-trip footgun the human-facing hint's colon form invited.
  `fresh-keyword` is the right primitive here: the build-id is a
  runtime-bounded read path resolving against shadow's finite running-
  build registry, so the per-id intern cost is capped.

  The resolved id is finally mapped through the conn's `:build-alias`
  forgiving-resolution cache (rf2-qda59): when the requested id named a
  running build by a unique SUFFIX (`:machine-epochs` ⇒
  `:examples/machine-epochs`), the pipeline's first step recorded the
  canonical alias, so every op — explicit-arg or sticky-default —
  resolves to the SAME canonical running id. An un-aliased id passes
  through unchanged (so a typo still reaches the diagnostic ladder).

  1-arity (`(arg-build args)`) is the no-`conn` entry — it SKIPS the
  conn cache and falls straight through to the env-var default, for any
  caller with no `conn` in scope. Production tool dispatch threads
  `conn` and uses the 2-arity; the 1-arity is exercised directly by the
  build-id cache tests."
  ([args] (arg-build nil args))
  ([conn args]
   (->> (or (base-args/fresh-keyword (arg args :build))
            (conn-resolved-build-id conn)
            (default-build-id))
        (canonicalize-via-alias conn))))

(defn requested-build
  "The build-id this call would resolve to BEFORE the forgiving
  suffix→canonical alias is applied (rf2-qda59). Mirrors `arg-build`'s
  precedence (explicit `:build` arg → sticky `:resolved-build-id` →
  env/`:app` default) but does NOT route through the alias cache — it is
  the INPUT to `probe/canonicalize-build!`, which the pipeline's first
  step runs to populate the alias. Returns a keyword."
  [conn args]
  (or (base-args/fresh-keyword (arg args :build))
      (conn-resolved-build-id conn)
      (default-build-id)))

(defn stick-build!
  "Session-sticky operating build (rf2-lbm21). When `args` carries an
  explicit `:build`, write it into the `:resolved-build-id` cache on the
  conn-atom so subsequent no-`:build` tool calls inherit it. No-op when
  no explicit `:build` arg is present (an omitted build must not clobber
  a previously-stuck one) or when `conn` isn't an atom (test stubs).

  Called from `invoke` for every tool EXCEPT `discover-app` — that tool
  owns its own success-gated cache lifecycle (it caches via
  `mark-resolved-build-id!` only after a passing health probe, and must
  NOT cache a build whose precondition check failed). Here the explicit
  `:build` is the operator's deliberate operating choice; the tool runs
  against it regardless, so sticking it eagerly is the right default.

  Returns the conn unchanged for threading convenience."
  [conn args]
  (when-let [explicit (base-args/fresh-keyword (arg args :build))]
    (mark-resolved-build-id! conn explicit))
  conn)

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

  1-arity (`(arg-build-explicit? args)`) is the no-`conn` fallback —
  it sees only the per-call arg. Kept symmetric with `arg-build`'s
  no-`conn` arity; production dispatch threads `conn` and uses the
  2-arity."
  ([args] (arg-build-explicit? nil args))
  ([conn args]
   (or (some? (arg args :build))
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
