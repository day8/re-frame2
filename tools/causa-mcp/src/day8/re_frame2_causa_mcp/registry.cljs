(ns day8.re-frame2-causa-mcp.registry
  "Per-tool registration table for causa-mcp (rf2-8xzoe T-Insp tranche
  shared infra).

  Each tool ns under `day8.re-frame2-causa-mcp.tools.<tool-name>`
  registers itself at load-time via `(register-tool! \"<name>\" handler
  descriptor)`. The dispatcher in `tools.cljs` looks up the handler by
  string name on every `tools/call`; the catalogue ns serves the
  descriptor map under `tools/list`.

  ## Why a separate registry ns rather than `tools.cljs` itself

  Circular-dep avoidance — every per-tool ns needs to register, and
  the tools-façade ns needs to read the registry; if both surfaces
  lived together each per-tool ns would `:require` the façade and the
  façade would `:require` each tool, producing a cycle. The registry
  is the cycle-breaker: tools depend on it, the façade depends on it,
  neither side depends on the other.

  ## Registration shape

      (register-tool!
        \"get-trace-buffer\"
        get-trace-buffer-tool
        {:name \"get-trace-buffer\"
         :description \"...\"
         :input-schema #js {:type \"object\" ...}})

  Handler is `(fn [conn args] -> Promise<MCPResult>)`. Descriptor is a
  CLJS map with the three SDK-expected slots; `:input-schema` rides as
  a JS object so the SDK serialises it as JSON Schema without an extra
  conversion step."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Registration state.
;; ---------------------------------------------------------------------------

(defonce ^:private state
  ;; Two parallel maps + an order vector. The order vector preserves
  ;; insertion order so `tools/list` is deterministic across reloads
  ;; (every load re-evaluates the per-tool `register-tool!` calls in
  ;; their `:require` order from the dispatcher ns).
  (atom {:handlers    {}
         :descriptors {}
         :order       []}))

(defn register-tool!
  "Add `tool-name` (a string — the MCP wire identity) to the registry
  with `handler` (a `(fn [conn args] -> Promise<MCPResult>)`) and
  `descriptor` (a CLJS map with `:name :description :input-schema`).

  Idempotent: re-registering the same name replaces the handler /
  descriptor without growing the order vector. Hot-reload friendly."
  [tool-name handler descriptor]
  (when (str/blank? tool-name)
    (throw (ex-info "register-tool!: tool-name must be a non-blank string"
                    {:tool-name tool-name})))
  (swap! state
    (fn [s]
      (let [present? (contains? (:handlers s) tool-name)]
        (-> s
            (assoc-in [:handlers    tool-name] handler)
            (assoc-in [:descriptors tool-name] descriptor)
            (cond-> (not present?) (update :order conj tool-name))))))
  tool-name)

(defn handler-for
  "Resolve the per-tool handler for `tool-name`. Returns nil for
  unknown names — the dispatcher surfaces `:unknown-tool`."
  [tool-name]
  (get-in @state [:handlers tool-name]))

(defn descriptor-for
  "Resolve the descriptor map for `tool-name`. Returns nil for unknown
  names."
  [tool-name]
  (get-in @state [:descriptors tool-name]))

(defn all-descriptors
  "All registered descriptor maps in insertion order. Used by the
  catalogue façade to serve `tools/list`."
  []
  (let [{:keys [order descriptors]} @state]
    (mapv descriptors order)))

(defn registered-names
  "All registered tool names in insertion order. Public so tests can
  pin the catalogue size + ordering."
  []
  (:order @state))

(defn reset-for-test!
  "Wipe the registry for fixture isolation. Test-only — never call from
  production code."
  []
  (reset! state {:handlers {} :descriptors {} :order []}))
