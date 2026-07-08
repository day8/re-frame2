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
  | dispatch-dry-run | Simulate a cascade without committing it               |
  | restore-epoch | Time-travel undo — rewind a frame to a prior epoch        |
  |               | (gated behind --allow-writes)                            |
  | replace-app-db| State injection — replace a frame's app-db with EDN data  |
  |               | (gated behind --allow-writes)                            |
  | trace-window  | Epochs in the last N ms                                   |
  | watch-epochs  | Pull-mode live epoch streaming                            |
  | tail-build    | Wait for a hot-reload to land                             |
  | snapshot      | Coarse-grained per-frame state read (mega-op)             |
  | get-path      | Direct read-by-path against a frame's app-db             |
  | read-dom      | View-plane read — querySelector -> per-node text/attrs    |
  |               | EDN; read-only, text/node-capped                         |
  | record        | Install a background signal recorder, return a            |
  |               | :recording-id                                            |
  | read-recording| Read back a recorder's change-log; :drain / :stop         |
  | watch-until   | Block until a predicate over a signal holds              |
  | subscribe     | Streaming trace/epoch channel — push-mode counterpart to  |
  |               | watch-epochs                                             |
  | unsubscribe   | Close a streaming subscription                            |
  | list-subscriptions | List the live reactive sub-cache for a frame —       |
  |               | matches snapshot :sub-cache                              |
  | list-streams  | List active streaming-tap subscriptions + queue stats    |
  |               | (the streaming-diagnostic surface, wrapping              |
  |               | subscription-info)                                       |
  | handler-meta  | Registration metadata for a (kind, id) — source-coord +   |
  |               | :rf.source/uri                                           |
  | list-handlers | All registered ids under a kind                          |
  | get-re-frame2-pair-instructions | Inline agent-onboarding text             |

  ## Per-tool / per-concern layout

  This namespace is the public façade — `invoke` glue, internal
  dispatch, and re-exported descriptor surface. The tool bodies and the
  cross-cutting concerns each live in `tools/<concern>` or `tools/<tool>`
  files:

  - Concerns: `wire`, `probe`, `cap`, `dedup`, `elision`, `sensitive`,
    `cursor`, `args`, `summary`, `snapshot-pipeline`, `boundary-step`,
    `writes` (the --allow-writes gate).
  - Tools: `discover-app`, `eval-cljs`, `dispatch`, `dispatch-dry-run`,
    `restore-epoch`, `replace-app-db`, `trace-window`, `watch-epochs`,
    `tail-build`, `snapshot`, `get-path`, `read-dom`, `record`,
    `read-recording`, `watch-until`, `subscribe`, `unsubscribe`,
    `list-subscriptions`, `list-streams`, `handler-meta`, `list-handlers`,
    `get-re-frame2-pair-instructions`. The authoritative, ordered list
    (with handler + cacheable? + descriptor for each) is `registry/tools`.
  - Descriptors: `descriptors-knobs` (universal knob property data),
    `descriptors-data` (per-tool descriptor maps), `descriptors`
    (`tool-descriptors-js` + the knob splicers).
  - Registry: `registry` — the single map binding name →
    descriptor + handler + cacheable?; the three downstream views are
    derived from it.
  - Precheck: `precheck` (the cheap-hash short-circuit).

  ## Result shape

  Each MCP tool returns `{:content [{:type \"text\" :text <edn-string>}]}`
  on success, or `{:isError true :content [...]}` on failure."
  (:require [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.cache :as cache]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.boundary-step :as bs]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.cap :as cap]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.precheck :as precheck]
            [re-frame2-pair-mcp.tools.operating-frame :as operating-frame]
            [re-frame2-pair-mcp.tools.registry :as registry]
            [re-frame2-pair-mcp.tools.descriptors :as descriptors]))

;; Re-export the descriptor catalogue + JS-shape builder. Tests
;; (`list_subscriptions_test.cljs`, `typical_tokens_test.cljs`) and
;; `server.cljs` consume these names off the façade ns; the split must
;; not break their resolution.
(def tool-descriptors descriptors/tool-descriptors)
(def tool-descriptors-js descriptors/tool-descriptors-js)

;; Re-export the registry's closed-world predicate onto the façade so
;; `server.cljs` (which already requires this ns) can route the
;; server-local reads AROUND `ensure-connection!` at the pre-connection
;; boundary — see `registry/closed-world-tool?` (rf2-6amhbt).
(def closed-world-tool? registry/closed-world-tool?)

(defn- edit-distance
  "Cheap Levenshtein distance between two strings — used only to surface a
  `did you mean` candidate in the `:unknown-tool` hint. Runs over the
  ~25-name catalogue on the cold error path, so the naive O(m·n) row-DP
  is plenty; no dependency for one off-the-happy-path string compare."
  [a b]
  (let [m (count a) n (count b)]
    (loop [i 0 prev (vec (range (inc n)))]
      (if (> i m)
        (peek prev)
        (recur
          (inc i)
          (loop [j 0 row [i] p prev]
            (if (> j n)
              row
              (let [cost (if (= (nth a (dec i) nil) (nth b (dec j) nil)) 0 1)
                    v    (if (zero? j)
                           i
                           (min (inc (peek row))           ;; deletion
                                (inc (nth p j))            ;; insertion
                                (+ cost (nth p (dec j))))) ;; substitution
                    row  (if (zero? j) row (conj row v))]
                (recur (inc j) row p)))))))))

(defn- nearest-tool
  "Closest registered tool name to `name` by edit distance, or nil when
  nothing is within a sensible threshold (avoid an actively-misleading
  suggestion for a wildly-off name). Threshold scales loosely with the
  typo'd length so short names need a near-exact match."
  [name]
  (when (and (string? name) (seq name))
    (let [[best d] (reduce (fn [[_ bd :as acc] cand]
                             (let [cd (edit-distance name cand)]
                               (if (< cd bd) [cand cd] acc)))
                           [nil js/Infinity]
                           registry/tool-names)
          cutoff   (max 2 (quot (count name) 2))]
      (when (and best (<= d cutoff)) best))))

(defn- unknown-tool-envelope
  "Build the `:unknown-tool` error envelope: `{:ok? false :reason
  :unknown-tool :tool name}` plus a
  recovery-shaped `:hint` (the surface's honest-error standard — cf.
  cache.cljs `:rf.mcp/cache-hit` :hint, roots_discovery.cljs discovery
  hints, server.cljs port hints) pointing the agent at `tools/list` plus
  the live catalogue, and a `:did-you-mean` when a near name exists."
  [name]
  (let [near (nearest-tool name)
        base (str "unknown tool" (when name (str " `" name "`")) "; "
                  (when near (str "did you mean `" near "`? "))
                  "call `tools/list` to see the available tools.")]
    (cond-> {:ok?           false
             :reason        :unknown-tool
             :tool          name
             :hint          base
             :available-tools registry/tool-names}
      near (assoc :did-you-mean near))))

(defn- dispatch-tool*
  "Route a `tools/call` to the per-tool implementation. Unknown tools
  resolve to an isError result rather than throwing — keeps the server
  loop simple.

  Lookup is a single `(get registry/handler-for name)` — the registry
  is the only place tool names are enumerated. Every
  registered handler is a uniform 3-arity `(fn [conn args extra])`; the
  registry adapts 2-arity per-tool handlers internally.

  An unknown name returns the recovery-shaped `:unknown-tool` envelope
  (`unknown-tool-envelope`) — a `:hint` pointing at `tools/list` plus the
  live `:available-tools` catalogue and a `:did-you-mean` near-match, so a
  model that typo'd a name can recover without a dead end."
  [conn name args extra]
  (if-let [handler (get registry/handler-for name)]
    (handler conn args extra)
    (js/Promise.resolve
      (wire/err-text (unknown-tool-envelope name)))))

(defn refuse-unknown-tool
  "Server-boundary pre-dispatch guard for unknown tool names.
  Returns the recovery-shaped `:unknown-tool` `wire/err-text` envelope
  when `name` is NOT a registered tool, so `server.cljs/handle-call` can
  reject a typo or unrecognised name LOCALLY — BEFORE `ensure-connection!`
  runs any nREPL discovery. Returns `nil` for a registered name (it must
  proceed to normal dispatch).

  Mirrors `writes/refuse-pre-connection`: the server runs
  `ensure-connection!` for EVERY tool before the dispatcher is reached,
  so on a stock / misconfigured install with no nREPL port the discovery
  step REJECTS with `:nrepl-port-not-found` and an unknown name never
  reaches `dispatch-tool*`'s `:unknown-tool` branch — the recovery
  affordances (`:hint` → tools/list, `:available-tools`, `:did-you-mean`)
  are masked behind a confusing transport error. A typo such as
  `registry-list` should be diagnosable without a live app/nREPL.

  The envelope is the SAME `unknown-tool-envelope` the dispatch path
  emits, routed through `wire/err-text` so the dual-slot wire shape
  (pr-str EDN text + namespace-preserving `:structuredContent`,
  `isError: true`) is identical at either entry point. The
  `dispatch-tool*` branch STAYS as defence in depth (direct
  `tools/invoke` callers + the conformance corpus)."
  [name]
  (when-not (contains? registry/handler-for name)
    (wire/err-text (unknown-tool-envelope name))))

;; ---------------------------------------------------------------------------
;; Wire-boundary pipeline.
;;
;; Steps thread through `boundary-step/run-step-pipeline`. Each
;; step's `:run` receives the live context (carrying `:result` and
;; `:precheck-hash`) and returns a Promise of the next context. The
;; `:skip-when?` predicates encode the per-step skip rules
;; declaratively — no inline conditionals in the orchestrator. The
;; predicate's semantics are skip-this-step, not halt-the-chain.
;;
;; Adding a future step (request-level redaction, metrics, path-prefix
;; slicing, per-call elision toggle) is one map-entry addition here
;; plus the step's `:run` body. The orchestration loop and the test
;; seam stay unchanged.
;; ---------------------------------------------------------------------------

(defn- canonicalize-build-step
  "Step -1 — forgiving suffix→canonical build resolution.
  Runs BEFORE every other step so the conn's `:build-alias` cache is
  populated before any per-tool body reads `wire/arg-build`. Resolves the
  requested build (`wire/requested-build` — explicit `:build` arg, else
  the sticky default) against shadow's running builds by exact-or-unique-
  suffix match. The alias write happens inside `probe/canonicalize-build!`;
  here we ALSO rewrite the sticky `:resolved-build-id` to the canonical id
  so a no-`:build` follow-up call inherits the canonical form rather than
  the suffix `stick-build!` just stored.

  ## Bare-default fast path

  A no-`:build` call that falls through to the bare env / `:app` default
  (no explicit `:build` arg AND no sticky `:resolved-build-id`) is NOT
  canonicalized — it returns `ctx` synchronously with NO `active-builds`
  round-trip. Two reasons:

    1. **Correctness, not just speed.** The env default
       (`SHADOW_CLJS_BUILD_ID` / `:app`) is a full build id by
       construction — never a suffix an operator typed — so it needs no
       suffix→canonical mapping. A default that isn't running surfaces via
       the downstream `ensure-runtime!` diagnostic ladder; the alias step
       would only round-trip to re-derive the id it was already handed.
    2. **It keeps the streaming subscribe gate fast.** Prepending a
       serial `active-builds` jvm-eval to EVERY first tool call —
       including `subscribe`'s registration path — would, on a slow
       hermetic CI runner, push `subscribe!` registration past the
       live-subscribe conformance test's dispatch head-start, so the
       dispatched cascade lands before the subscription exists and zero
       `notifications/progress` frames arrive (the bus only queues for
       already-registered subs). Suffix-forgiveness targets an operator
       who TYPES a build (`:build machine-epochs`); the no-arg default
       needs no trip.

  Suffix-forgiveness is fully preserved for the case it was built for:
  an explicit `:build` arg, or a sticky suffix stuck from a prior
  explicit `:build`, still routes through `canonicalize-build!`.

  Pays one `active-builds` round-trip only the first time a not-yet-probed
  EXPLICIT build name is seen this session; an exact / already-aliased id
  (or the bare default) resolves with no round-trip. Pure-fail-safe: a
  stub conn (no caches) or a `running-builds` hiccup degrades to the
  requested id unchanged, so the diagnostic ladder still fires.

  `discover-app` is INCLUDED here (unlike `stick-build!`): its explicit
  `:build` should be just as forgiving as the read ops', and the alias
  write is harmless — discover-app owns its own success-gated
  `:resolved-build-id` lifecycle separately."
  [{:keys [conn args] :as ctx}]
  (if-not (wire/arg-build-explicit? conn args)
    ;; Bare env / `:app` default — already canonical; skip the round-trip
    ;; so latency-sensitive paths (subscribe registration) are not
    ;; delayed. See the docstring's bare-default fast path.
    (js/Promise.resolve ctx)
    (let [requested (wire/requested-build conn args)]
      (-> (probe/canonicalize-build! conn requested)
          (.then (fn [canonical]
                   ;; Keep the sticky default canonical too — only when a
                   ;; build was already stuck (don't mint one for a no-build
                   ;; call that fell through to the env default).
                   (when (and (some? conn) (satisfies? IDeref conn)
                              (some? (:resolved-build-id @conn)))
                     (swap! conn assoc :resolved-build-id canonical))
                   ctx))
          (.catch (fn [_] ctx))))))

(defn- precheck-step
  "Step 0 — cheap-hash short-circuit. For precheck-eligible
  tools (cache enabled AND tool registers a precheck-target), fetches
  the runtime-side hash via one bencode round-trip and consults the
  cache. On a hit, writes the marker to `:result` (which trips
  subsequent steps' `:skip-when?` predicates). On a miss, records
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
  "Step 2 — post-eval result-hash cache. On a hash match
  replaces `:result` with the cache-hit marker; on a miss stores the
  new hash (plus the precheck-hash from step 0 if present) and leaves
  `:result` unchanged."
  [{:keys [result cache-opts precheck-hash] :as ctx}]
  (->> (cache/apply-cache result (assoc cache-opts :precheck-hash precheck-hash))
       (assoc ctx :result)))

(defn- apply-cap-step
  "Step 3 — wire-boundary token-budget enforcement. When
  `:result` exceeds the per-call cap, replaces it with the
  `:rf.mcp/overflow` marker."
  [{:keys [result cap-opts] :as ctx}]
  (assoc ctx :result (cap/apply-cap result cap-opts)))

(defn- isError? [result-js]
  (and (some? result-js)
       (boolean (j/get result-js :isError))))

(def wire-boundary-pipeline
  "The five-step wire-boundary pipeline. Order matters — see step
  docstrings for the per-step semantics.

  | Step                 | When the step is skipped (`:skip-when?`)         |
  |----------------------|--------------------------------------------------|
  | `:canonicalize-build`| never — populates the forgiving suffix→canonical |
  |                      | build alias before any per-tool `arg-build` read |
  | `:precheck`       | never — runs unconditionally; produces a marker     |
  |                   | result ONLY for eligible cacheable tools            |
  | `:dispatch`       | a prior step already produced a `:result` (i.e.     |
  |                   | precheck hit)                                       |
  | `:apply-cache`    | `:isError` result (errors must not poison cache) OR |
  |                   | result is already a wire-bounded marker             |
  | `:apply-cap`      | result is a wire-bounded marker (cache-hit /        |
  |                   | overflow are sub-cap by construction)               |

  Cache before cap is the right order: a cache hit emits a sub-100-
  byte marker that's trivially under any reasonable cap, so flipping
  the order would never change behaviour but would waste a token
  walk on the hit path.

  `:skip-when?` is the per-step skip predicate: when it returns true the
  step is skipped and the chain continues to the next step's own
  predicate (skip-this-step, not halt-the-chain)."
  [{:name       :canonicalize-build
    :run        canonicalize-build-step}
   {:name       :precheck
    :run        precheck-step}
   {:name       :dispatch
    :run        dispatch-step
    :skip-when? (fn [{:keys [result]}] (some? result))}
   {:name       :apply-cache
    :run        apply-cache-step
    :skip-when? (fn [{:keys [result]}]
                  (or (isError? result) (wire/marker? result)))}
   {:name       :apply-cap
    :run        apply-cap-step
    :skip-when? (fn [{:keys [result]}] (wire/marker? result))}])

(defn invoke
  "Dispatch a `tools/call` invocation through the wire-boundary
  pipeline. Returns a Promise resolving to the MCP result object.

  ## Wire-boundary pipeline

  The four-step pipeline lives in `wire-boundary-pipeline` and is
  threaded by `boundary-step/run-step-pipeline`:

  0. **`:precheck`** (`precheck/fetch-precheck-hash`) —
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

  2. **`:apply-cache`** (`cache/apply-cache`) —
     post-eval per-session response cache keyed on a hash of the
     result's text payload. On a hit the result is replaced with
     `{:rf.mcp/cache-hit ... :via :result-hash}` — the agent host
     already has the byte-identical bytes from the prior call. Read-
     only tools only; `:isError` results bypass entirely; already-
     marker results bypass entirely (a precheck hit is already the
     cache-hit envelope). When a precheck-hash was fetched in step
     0, it's recorded alongside the result hash so the NEXT call
     can short-circuit via the precheck path.

  3. **`:apply-cap`** (`cap/apply-cap`) — responses
     whose serialised size exceeds the per-call cap (default 5,000
     tokens, configurable via the `max-tokens` MCP arg) are replaced
     with `{:rf.mcp/overflow ...}`. Silent truncation is not
     allowed. Already-marker results bypass — the marker envelopes
     are sub-cap by construction.

  `extra` carries the MCP `extra` payload (signal + sendNotification +
  _meta.progressToken) for streaming tools. Non-streaming tools ignore
  it."
  [conn name args extra]
  ;; Session-sticky operating build. An explicit `:build` on
  ;; any tool call (except `discover-app`, which owns its success-gated
  ;; cache lifecycle) becomes the sticky default for subsequent
  ;; no-`:build` calls. `arg-build` stays a pure read; the write lives
  ;; here at the single dispatch chokepoint.
  (when (not= name "discover-app")
    (wire/stick-build! conn args))
  (let [cap (cap/max-tokens-arg args)]
    (if (cap/invalid-arg? cap)
      ;; A negative `:max-tokens` resolves to a
      ;; `{:rf.mcp/invalid-arg {...}}` rejection rather than a negative
      ;; cap. Short-circuit into an `isError: true` result BEFORE the
      ;; pipeline runs: the tool is never dispatched and the malformed
      ;; cap never reaches `apply-cap` (where a negative ceiling would
      ;; over-trip `over-cap?` and replace every response — even a tiny
      ;; one — with the overflow marker, locking the agent out). The
      ;; agent reads the actionable rejection and corrects the arg.
      (js/Promise.resolve (wire/err-text cap))
      (let [enabled? (args/parse-bool-arg args :cache)
            ;; Fold the RESOLVED build into the
            ;; cache identity so a precheck-hash collision across two
            ;; builds on the one nREPL connection can't serve the wrong
            ;; build's payload. `arg-build` is a pure read (runs AFTER
            ;; `stick-build!` above so the sticky default is current);
            ;; the build-alias canonicalisation runs in the pipeline's
            ;; first step, but the raw resolved id is a sufficient
            ;; discriminator for the cache key (a suffix vs canonical id
            ;; only ever maps to ONE running build).
            build    (wire/arg-build conn args)
            ctx      {:conn       conn
                      :name       name
                      :args       args
                      :extra      extra
                      :result     nil
                      :cache-opts {:tool name :args args :enabled? enabled? :build build}
                      :cap-opts   {:tool name :cap cap}}]
        (-> (bs/run-and-extract wire-boundary-pipeline ctx)
            ;; A successful operating-frame mutation
            ;; (set / reset) shifts where every omitted-`:frame` read
            ;; resolves, so any cached payload keyed only on `(tool, build,
            ;; args)` would be served against the WRONG frame (the
            ;; identical-app-db-hash multi-frame case). Flush the whole
            ;; response cache here at the chokepoint — keeping the cache ns
            ;; free of an operating-frame require cycle. Skipped on an
            ;; error result (the pin didn't change).
            (.then (fn [result-js]
                     (when (and (operating-frame/operating-frame-mutating? name)
                                (not (isError? result-js)))
                       (cache/clear!))
                     result-js)))))))
