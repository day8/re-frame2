(ns re-frame2-pair-mcp.tools
  "MCP tools — one per re-frame2-pair op. Each tool builds an nREPL eval request,
  sends it over the persistent connection, and returns the result as an
  MCP `tools/call` result.

  ## Tool catalogue

  | MCP tool name | What it does                                              |
  |---------------|-----------------------------------------------------------|
  | discover-app  | Verify nREPL + confirm the preloaded runtime + health     |
  | eval-cljs     | Eval a CLJS form, return the value                        |
  | dispatch      | Fire a re-frame2 event with :origin :pair                 |
  | trace-window  | Epochs in the last N ms                                   |
  | watch-epochs  | Pull-mode live epoch streaming                            |
  | tail-build    | Wait for a hot-reload to land                             |
  | snapshot      | Coarse-grained per-frame state read (mega-op)             |
  | get-path      | Direct read-by-path against a frame's app-db (rf2-tygdv)  |
  | subscribe     | Streaming trace/epoch channel — push-mode replacement for |
  |               | watch-epochs (rf2-hq49)                                   |
  | unsubscribe   | Close a streaming subscription                            |
  | subscription-info | List active streaming subscriptions + queue stats     |
  |               | (rf2-zjz9q)                                               |
  | handler-meta  | Registration metadata for a (kind, id) — source-coord +   |
  |               | :rf.source/uri (rf2-pctf8)                                |
  | registry-list | All registered ids under a kind (rf2-pctf8)               |
  | get-re-frame2-pair-instructions | Inline agent-onboarding text (rf2-fnpqg)         |

  ## Per-tool / per-concern layout (rf2-vrbwx, rf2-47g8l)

  This namespace is the public façade — `invoke` glue, internal
  dispatch, and re-exported descriptor surface. The fourteen tool
  bodies and the seven cross-cutting concerns each live in
  `tools/<concern>` or `tools/<tool>` files:

  - Concerns: `wire`, `probe`, `cap`, `dedup`, `elision`, `sensitive`,
    `cursor`, `args`, `summary`, `snapshot-pipeline`, `boundary-step`.
  - Tools: `discover-app`, `eval-cljs`, `dispatch`, `trace-window`,
    `watch-epochs`, `tail-build`, `snapshot`, `get-path`, `subscribe`,
    `unsubscribe`, `subscription-info`, `handler-meta`,
    `registry-list`, `get-re-frame2-pair-instructions`.
  - Descriptors: `descriptors-knobs` (universal knob property data),
    `descriptors-data` (per-tool descriptor maps), `descriptors`
    (`tool-descriptors-js` + the knob splicers).
  - Registry (rf2-47g8l): `registry` — the single map binding name →
    descriptor + handler + cacheable?; the three downstream views are
    derived from it.
  - Precheck: `precheck` (the rf2-36xod cheap-hash short-circuit).

  ## Result shape

  Each MCP tool returns `{:content [{:type \"text\" :text <edn-string>}]}`
  on success, or `{:isError true :content [...]}` on failure."
  (:require [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.cache :as cache]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.boundary-step :as bs]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.cap :as cap]
            [re-frame2-pair-mcp.tools.precheck :as precheck]
            [re-frame2-pair-mcp.tools.registry :as registry]
            [re-frame2-pair-mcp.tools.descriptors :as descriptors]))

;; Re-export the descriptor catalogue + JS-shape builder. Tests
;; (`subscription_info_test.cljs`, `typical_tokens_test.cljs`) and
;; `server.cljs` consume these names off the façade ns; the split must
;; not break their resolution.
(def tool-descriptors descriptors/tool-descriptors)
(def tool-descriptors-js descriptors/tool-descriptors-js)

(defn- dispatch-tool*
  "Route a `tools/call` to the per-tool implementation. Unknown tools
  resolve to an isError result rather than throwing — keeps the server
  loop simple.

  Lookup is a single `(get registry/handler-for name)` — the registry
  is the only place tool names are enumerated (rf2-47g8l). Every
  registered handler is a uniform 3-arity `(fn [conn args extra])`; the
  registry adapts 2-arity per-tool handlers internally."
  [conn name args extra]
  (if-let [handler (get registry/handler-for name)]
    (handler conn args extra)
    (js/Promise.resolve
      (wire/err-text {:ok? false :reason :unknown-tool :tool name}))))

;; ---------------------------------------------------------------------------
;; Wire-boundary pipeline (rf2-3z0zi).
;;
;; Four steps thread through `boundary-step/run-step-pipeline`. Each
;; step's `:run` receives the live context (carrying `:result` and
;; `:precheck-hash`) and returns a Promise of the next context. The
;; `:short-circuit?` predicates encode the per-step skip rules
;; declaratively — no inline conditionals in the orchestrator.
;;
;; Adding a future step (request-level redaction, metrics, path-prefix
;; slicing, per-call elision toggle) is one map-entry addition here
;; plus the step's `:run` body. The orchestration loop and the test
;; seam stay unchanged.
;; ---------------------------------------------------------------------------

(defn- precheck-step
  "Step 0 — rf2-36xod cheap-hash short-circuit. For precheck-eligible
  tools (cache enabled AND tool registers a precheck-target), fetches
  the runtime-side hash via one bencode round-trip and consults the
  cache. On a hit, writes the marker to `:result` (which trips
  subsequent steps' `:short-circuit?` predicates). On a miss, records
  the fetched hash in `:precheck-hash` so `apply-cache` can attach
  it to the future entry."
  [{:keys [conn name args cache-opts] :as ctx}]
  (if-let [target (and (:enabled? cache-opts)
                       (precheck/precheck-target name args))]
    (-> (precheck/fetch-precheck-hash conn args target)
        (.then (fn [h]
                 (assoc ctx
                   :precheck-hash h
                   :result        (cache/precheck cache-opts h)))))
    (js/Promise.resolve ctx)))

(defn- dispatch-step
  "Step 1 — per-tool dispatch. Runs the actual tool implementation; its
  Promise resolves to the JS-shape MCP result, which becomes the
  context's `:result`."
  [{:keys [conn name args extra] :as ctx}]
  (-> (dispatch-tool* conn name args extra)
      (.then (fn [result-js] (assoc ctx :result result-js)))))

(defn- apply-cache-step
  "Step 2 — rf2-3rt1f post-eval result-hash cache. On a hash match
  replaces `:result` with the cache-hit marker; on a miss stores the
  new hash (plus the precheck-hash from step 0 if present) and leaves
  `:result` unchanged."
  [{:keys [result cache-opts precheck-hash] :as ctx}]
  (->> (cache/apply-cache result (assoc cache-opts :precheck-hash precheck-hash))
       (assoc ctx :result)))

(defn- apply-cap-step
  "Step 3 — rf2-rvyzy wire-boundary token-budget enforcement. When
  `:result` exceeds the per-call cap, replaces it with the
  `:rf.mcp/overflow` marker."
  [{:keys [result cap-opts] :as ctx}]
  (assoc ctx :result (cap/apply-cap result cap-opts)))

(defn- isError? [result-js]
  (and (some? result-js)
       (boolean (j/get result-js :isError))))

(def wire-boundary-pipeline
  "The four-step wire-boundary pipeline. Order matters — see step
  docstrings for the per-step semantics.

  | Step              | When the step is skipped (`:short-circuit?`)        |
  |-------------------|-----------------------------------------------------|
  | `:precheck`       | never — runs unconditionally; produces a marker     |
  |                   | result ONLY for eligible cacheable tools            |
  | `:dispatch`       | a prior step already produced a `:result` (i.e.     |
  |                   | precheck hit)                                       |
  | `:apply-cache`    | `:isError` result (errors must not poison cache) OR |
  |                   | result is already a wire-bounded marker             |
  | `:apply-cap`      | result is a wire-bounded marker (cache-hit /        |
  |                   | overflow are sub-cap by construction — rf2-gktyn)   |

  Cache before cap is the right order: a cache hit emits a sub-100-
  byte marker that's trivially under any reasonable cap, so flipping
  the order would never change behaviour but would waste a token
  walk on the hit path."
  [{:name           :precheck
    :run            precheck-step}
   {:name           :dispatch
    :run            dispatch-step
    :short-circuit? (fn [{:keys [result]}] (some? result))}
   {:name           :apply-cache
    :run            apply-cache-step
    :short-circuit? (fn [{:keys [result]}]
                      (or (isError? result) (wire/marker? result)))}
   {:name           :apply-cap
    :run            apply-cap-step
    :short-circuit? (fn [{:keys [result]}] (wire/marker? result))}])

(defn invoke
  "Dispatch a `tools/call` invocation through the wire-boundary
  pipeline. Returns a Promise resolving to the MCP result object.

  ## Wire-boundary pipeline

  The four-step pipeline lives in `wire-boundary-pipeline` and is
  threaded by `boundary-step/run-step-pipeline`:

  0. **`:precheck`** (`precheck/fetch-precheck-hash`, rf2-36xod) —
     for precheck-eligible tools, issue one cheap nREPL eval to
     compute the runtime-side hash and compare to the stored
     `:precheck-hash`. On a match, write the
     `{:rf.mcp/cache-hit ... :via :precheck}` marker to `:result`
     — the tool eval is SKIPPED entirely. Saves the full pipeline
     cost. On a miss (or for tools without precheck wiring),
     records the fetched hash in `:precheck-hash` for step 2 to
     attach to the next entry.

  1. **`:dispatch`** — per-tool implementation. Skipped if the
     precheck step already produced a result.

  2. **`:apply-cache`** (`cache/apply-cache`, rf2-3rt1f) —
     post-eval per-session response cache keyed on a hash of the
     result's text payload. On a hit the result is replaced with
     `{:rf.mcp/cache-hit ... :via :result-hash}` — the agent host
     already has the byte-identical bytes from the prior call. Read-
     only tools only; `:isError` results bypass entirely; already-
     marker results bypass entirely (a precheck hit is already the
     cache-hit envelope). When a precheck-hash was fetched in step
     0, it's recorded alongside the result hash so the NEXT call
     can short-circuit via the precheck path.

  3. **`:apply-cap`** (`cap/apply-cap`, rf2-rvyzy) — responses
     whose serialised size exceeds the per-call cap (default 5,000
     tokens, configurable via the `max-tokens` MCP arg) are replaced
     with `{:rf.mcp/overflow ...}`. Silent truncation is not
     allowed. Already-marker results bypass — the marker envelopes
     are sub-cap by construction (rf2-gktyn).

  `extra` carries the MCP `extra` payload (signal + sendNotification +
  _meta.progressToken) for streaming tools. Non-streaming tools ignore
  it."
  [conn name args extra]
  (let [enabled? (args/parse-bool-arg args :cache)
        ctx      {:conn       conn
                  :name       name
                  :args       args
                  :extra      extra
                  :result     nil
                  :cache-opts {:tool name :args args :enabled? enabled?}
                  :cap-opts   {:tool name :cap (cap/max-tokens-arg args)}}]
    (bs/run-and-extract wire-boundary-pipeline ctx)))
