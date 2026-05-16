(ns day8.re-frame2-causa-mcp.tools.dispatch
  "Tool: `dispatch` — Class-1 named mutation (rf2-8xzoe.23,
  T-Mut-1 of the causa-mcp mutation tranche).

  Routes a re-frame event vector through the frame's normal dispatch
  cascade; the framework stamps `:tags :origin :causa-mcp` on the
  emitted trace via the runtime's `*current-origin*` binding (B-3 of
  the rf2-8xzoe wire-pipeline tranche). Per `004-Wire-Pipeline.md`
  §Authority classes, Class-1 mutations carry **no per-call consent
  gate** — consent is the server-launch enable signal — so this tool
  fires without a confirmation handshake.

  ## Wire-boundary contract

  - **B-3 origin stamp** — `ef/wrap-origin` binds the runtime's
    `*current-origin*` dynamic var to `:causa-mcp` for the synchronous
    extent of the dispatched event so any trace event the cascade
    emits carries `:tags :origin :causa-mcp` (Lock #4 / I6: async
    handlers fired AFTER the synchronous extent do NOT inherit the
    tag — that's the documented async-tagging gap).
  - **B-1 privacy** — not applicable here (the response envelope is
    structured ack metadata, not trace events).
  - **W-6 size elision** — counted defensively on the runtime envelope
    in case a future extension carries auxiliary metadata.
  - **W-1 token cap** — dispatcher-level via `tools.cljs/invoke`.

  ## Modes

  - `:queued` (default) — non-blocking `rf/dispatch`. Event is added to
    the per-frame queue; the cascade runs asynchronously on the next
    JS microtask tick. The tool resolves once the dispatch hand-off
    completes; downstream cascade traces are surfaced through
    `subscribe :trace` (T-Stream-1) rather than the mutation ack.
  - `:sync` — `rf/dispatch-sync`. The full cascade (interceptor chain,
    effect handlers, view re-render hand-off) runs synchronously
    before this tool resolves. Use sparingly — sync dispatch defeats
    the queue's batching guarantees and risks re-entrancy across
    frames.

  ## Args

  | Arg | Type | Default | Notes |
  |---|---|---|---|
  | `:event` | EDN-vec str | nil (REQUIRED) | event vector (e.g. `\"[:cart/add 42]\"`) |
  | `:frame` | keyword | nil | scope to one frame; nil → sole frame |
  | `:sync?` | bool | false | `:sync` (true) vs `:queued` (false) |
  | `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

  ## Return shape

      {:ok? true
       :event-id <kw>
       :frame    <kw>
       :origin   :causa-mcp
       :mode     <:queued|:sync>}

  ## Failure envelopes

  - `{:ok? false :reason :missing-event :hint ...}` — `:event` absent.
  - `{:ok? false :reason :event-malformed :given <s> :hint ...}` —
    `:event` couldn't be parsed as an EDN vector.
  - `{:ok? false :reason :not-an-event-vector :hint ...}` — parsed
    value isn't a vector.
  - `{:ok? false :reason :no-frame-resolved :hint ...}` — multi-frame
    app without an explicit `:frame` arg.
  - `{:ok? false :reason :runtime-not-preloaded :hint <setup>}` —
    Causa-the-panel preload isn't loaded.

  ## Source-coord pin

  Cite: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
  bead #23. Catalogue entry lives in
  `tools/causa-mcp/spec/004-Tools-Catalogue.md`."
  (:require [cljs.reader :as edn]
            [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.eval-form :as ef]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.wire :as wire]))

(defn parse-event-arg
  "Coerce the `:event` MCP arg to a CLJS vector. Accepts a CLJS vector
  passthrough or an EDN-printed vector string. Returns `::malformed`
  for unparseable input so the caller can surface a structured
  envelope; returns `nil` for absent input."
  [v]
  (cond
    (nil? v)    nil
    (vector? v) v
    (string? v) (try (let [parsed (edn/read-string v)]
                       (if (vector? parsed) parsed ::malformed))
                     (catch :default _ ::malformed))
    :else       ::malformed))

(defn build-form
  "Build the eval-form addressing the runtime accessor. Pure.

  The runtime accessor is `dispatch!` (arity-2 with opts map); the
  `wrap-origin` outer binding stamps `*current-origin* :causa-mcp` so
  the framework's trace cascade tags the synchronous extent."
  [event-vec opts]
  (-> (ef/rt-call 'dispatch! event-vec opts)
      (ef/emit)
      (ef/wrap-origin)))

(defn shape-envelope
  "Shape the runtime's `{:ok? true :event-id :frame :origin :mode}`
  response into the MCP envelope. Pure — tests pin the indicator
  stamping logic."
  [runtime-envelope]
  (let [{:keys [ok?] :as env} (if (map? runtime-envelope) runtime-envelope {})]
    (cond
      (false? ok?)
      env

      (not (true? ok?))
      {:ok?     false
       :reason  :unexpected-shape
       :runtime runtime-envelope
       :hint    "runtime/dispatch! returned a non-envelope shape"}

      :else
      (let [elided (elision/count-elided-markers env)]
        (wire/with-indicators env {:elided elided})))))

(defn dispatch-tool
  "MCP handler for `dispatch`. Validates `:event` pre-flight; runtime
  accessor validates again (defence in depth)."
  [conn args]
  (let [build-id  (wire/arg-build args)
        frame     (wire/arg-keyword args :frame)
        sync?     (wire/arg-bool args :sync? false)
        event-raw (wire/arg args :event)
        event     (parse-event-arg event-raw)]
    (cond
      (nil? event)
      (js/Promise.resolve
        (wire/ok-text {:ok?    false
                       :reason :missing-event
                       :hint   "Pass :event \"[:foo/bar 42]\" (EDN-vec string)."}))

      (= ::malformed event)
      (js/Promise.resolve
        (wire/ok-text {:ok?    false
                       :reason :event-malformed
                       :given  event-raw
                       :hint   "Could not parse :event as an EDN vector."}))

      (not (vector? event))
      (js/Promise.resolve
        (wire/ok-text {:ok?    false
                       :reason :not-an-event-vector
                       :given  event
                       :hint   "Event must be a vector, e.g. [:cart/add 42]."}))

      :else
      (let [runtime-opts (cond-> {:sync? sync?}
                           frame (assoc :frame frame))
            form         (build-form event runtime-opts)]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [runtime-envelope]
                     (wire/ok-text (shape-envelope runtime-envelope))))
            (.catch (fn [err] (probe/err->result :dispatch-failed err))))))))

(def descriptor
  {:name        "dispatch"
   :description (str "Fire a re-frame event through the frame's normal "
                     "dispatch cascade. Class-1 mutation: server-enable "
                     "is the consent gate, no per-call confirmation. "
                     "The event's trace is stamped :origin :causa-mcp. "
                     ":sync? true uses dispatch-sync (the full cascade "
                     "runs before this tool resolves); :sync? false "
                     "(default) is the standard queued dispatch. Required "
                     ":event arg is an EDN vector string, e.g. "
                     "\"[:cart/add 42]\".")
   :input-schema #js {:type "object"
                      :required #js ["event"]
                      :properties #js {:event      #js {:type "string"}
                                       :frame      #js {:type "string"}
                                       :sync?      #js {:type "boolean"}
                                       :max-tokens #js {:type "integer"}}}})

(registry/register-tool! (:name descriptor) dispatch-tool descriptor)
