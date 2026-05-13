(ns re-frame-pair2-mcp.tools
  "MCP tools — one per pair2 op. Each tool builds an nREPL eval request,
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

  ## Per-tool / per-concern layout (rf2-vrbwx)

  This namespace is the public façade — `invoke` glue, internal
  dispatch, and re-exported descriptor surface. The eleven tool bodies
  and the seven cross-cutting concerns each live in `tools/<concern>`
  or `tools/<tool>` files:

  - Concerns: `wire`, `probe`, `cap`, `dedup`, `elision`, `sensitive`,
    `cursor`, `args`, `summary`, `snapshot-pipeline`.
  - Tools: `discover-app`, `eval-cljs`, `dispatch`, `trace-window`,
    `watch-epochs`, `tail-build`, `snapshot`, `get-path`, `subscribe`
    (+ `subscribe-emit`), `unsubscribe`, `subscription-info`.
  - Descriptors: `descriptors-knobs` (the universal knob property defs
    + the `with-*-knob` splicers) and `descriptors` (the catalogue of
    eleven tool descriptors + `tool-descriptors-js`).
  - Precheck: `precheck` (the rf2-36xod cheap-hash short-circuit).

  ## Result shape

  Each MCP tool returns `{:content [{:type \"text\" :text <edn-string>}]}`
  on success, or `{:isError true :content [...]}` on failure."
  (:require [applied-science.js-interop :as j]
            [re-frame-pair2-mcp.cache :as cache]
            [re-frame-pair2-mcp.tools.wire :as wire]
            [re-frame-pair2-mcp.tools.cap :as cap]
            [re-frame-pair2-mcp.tools.precheck :as precheck]
            [re-frame-pair2-mcp.tools.discover-app :as discover-app]
            [re-frame-pair2-mcp.tools.eval-cljs :as eval-cljs]
            [re-frame-pair2-mcp.tools.dispatch :as dispatch]
            [re-frame-pair2-mcp.tools.trace-window :as trace-window]
            [re-frame-pair2-mcp.tools.watch-epochs :as watch-epochs]
            [re-frame-pair2-mcp.tools.tail-build :as tail-build]
            [re-frame-pair2-mcp.tools.snapshot :as snapshot]
            [re-frame-pair2-mcp.tools.get-path :as get-path]
            [re-frame-pair2-mcp.tools.subscribe :as subscribe]
            [re-frame-pair2-mcp.tools.unsubscribe :as unsubscribe]
            [re-frame-pair2-mcp.tools.subscription-info :as subscription-info]
            [re-frame-pair2-mcp.tools.descriptors :as descriptors]))

;; Re-export the descriptor catalogue + JS-shape builder. Tests
;; (`subscription_info_test.cljs`, `typical_tokens_test.cljs`) and
;; `server.cljs` consume these names off the façade ns; the split must
;; not break their resolution.
(def tool-descriptors descriptors/tool-descriptors)
(def tool-descriptors-js descriptors/tool-descriptors-js)

(defn- dispatch-tool*
  "Route a `tools/call` to the per-tool implementation. Unknown tools
  resolve to an isError result rather than throwing — keeps the server
  loop simple."
  [conn name args extra]
  (case name
    "discover-app"      (discover-app/discover-app conn args)
    "eval-cljs"         (eval-cljs/eval-cljs-tool conn args)
    "dispatch"          (dispatch/dispatch-tool conn args)
    "trace-window"      (trace-window/trace-window-tool conn args)
    "watch-epochs"      (watch-epochs/watch-epochs-tool conn args)
    "tail-build"        (tail-build/tail-build-tool conn args)
    "snapshot"          (snapshot/snapshot-tool conn args)
    "get-path"          (get-path/get-path-tool conn args)
    "subscribe"         (subscribe/subscribe-tool conn args extra)
    "unsubscribe"       (unsubscribe/unsubscribe-tool conn args)
    "subscription-info" (subscription-info/subscription-info-tool conn args)
    (js/Promise.resolve
      (wire/err-text {:ok? false :reason :unknown-tool :tool name}))))

(defn invoke
  "Dispatch a `tools/call` invocation to the right tool implementation.
  Returns a Promise resolving to the MCP result object.

  ## Wire-boundary pipeline

  When the per-call `cache` arg is enabled, the invocation runs the
  following:

  0. `precheck/fetch-precheck-hash` (rf2-36xod) — for precheck-eligible
     tools (`snapshot` single-frame; `get-path`) issue one cheap nREPL
     eval `(hash (re-frame-pair2.runtime/snapshot frame))` and compare
     to the stored `:precheck-hash` for `(tool, args)`. On a match,
     short-circuit with `{:rf.mcp/cache-hit ... :via :precheck}` — the
     tool eval is SKIPPED entirely. Saves the full pipeline cost. On a
     miss (or for tools without precheck wiring), fall through.

  1. `cache/apply-cache` (rf2-3rt1f) — per-session response cache
     keyed on a hash of the result's text payload. On a hit (same hash
     for `(tool, args)` as the prior call) the full payload is replaced
     with a tiny `{:rf.mcp/cache-hit ... :via :result-hash}` marker;
     the agent host already has the byte-identical bytes from the prior
     `tools/call`. Read-only tools only; `:isError` results bypass
     entirely. See `cache/apply-cache` for the LRU policy. When a
     precheck-hash was fetched in step 0, it's recorded alongside the
     result hash so the NEXT call can short-circuit via the precheck
     path.

  2. `cap/apply-cap` (rf2-rvyzy) — responses whose serialised size
     exceeds the per-call cap (default 5,000 tokens, configurable via
     the `max-tokens` MCP arg) are replaced with an
     `{:rf.mcp/overflow ...}` marker. Silent truncation is not allowed;
     see the `apply-cap` docstring for the pluggable-strategy design.

  Cache before cap is the right order: a cache hit emits a sub-100-
  byte marker that's trivially under any reasonable cap, so flipping
  the order would never change behaviour but would waste the
  `sum-text-tokens` walk on the hit path.

  `extra` carries the MCP `extra` payload (signal + sendNotification +
  _meta.progressToken) for streaming tools. Non-streaming tools ignore
  it."
  [conn name args extra]
  (let [cap-opts    {:tool     name
                     :cap      (cap/max-tokens-arg args)
                     :strategy :truncate-with-marker}
        enabled?    (cache/parse-cache-arg
                      (when args (j/get args "cache")))
        cache-opts  {:tool     name
                     :args     args
                     :enabled? enabled?}
        ;; Step 0: precheck — only fetch a hash when cache is enabled
        ;; AND the tool has precheck wiring. Otherwise resolve a
        ;; sentinel pair `[nil nil]` immediately and skip the round-trip.
        precheck-pr (if-let [frame (and enabled? (precheck/precheck-frame name args))]
                      (-> (precheck/fetch-precheck-hash conn args frame)
                          (.then (fn [h]
                                   [h (cache/precheck cache-opts h)])))
                      (js/Promise.resolve [nil nil]))]
    (-> precheck-pr
        (.then (fn [[h hit]]
                 (if hit
                   ;; Short-circuit — skip the tool entirely.
                   hit
                   ;; Miss / no precheck — run the tool and feed the
                   ;; result + (any) precheck-hash into apply-cache.
                   (-> (dispatch-tool* conn name args extra)
                       (.then (fn [result]
                                (cache/apply-cache
                                  result
                                  (assoc cache-opts :precheck-hash h))))))))
        (.then (fn [result] (cap/apply-cap result cap-opts))))))
