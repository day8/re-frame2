(ns day8.re-frame2-causa-mcp.tools.get-machine-state
  "Tool: `get-machine-state` — per-machine snapshot (rf2-8xzoe.18,
  T-Insp-5 of the causa-mcp inspection tranche).

  Returns the current snapshot for a named machine — the registered
  FSM spec (transitions, initial-state, tags) plus, when the framework
  surfaces it on the runtime, the live per-frame FSM position. Path
  slicing via `:path` lets the agent drill into a subtree of the spec
  without shipping the full metadata. Summary mode is the default
  (`:mode :summary` returns the spec name + state names; `:mode :full`
  returns the entire metadata map).

  ## Wire-boundary contract

  - **W-6 size elision** — the runtime accessor already routed the
    spec through `re-frame.core/elide-wire-value`. This tool counts
    markers via `elision/count-elided-markers` and stamps
    `:elided-large`.
  - **B-1 privacy** — not directly applicable (machine spec is
    framework metadata); the `:include-sensitive?` arg threads through
    to the walker which honours declared-sensitive leaves at the spec
    boundary.
  - **W-1 token cap** — dispatcher-level. Per-tool cap-reached hint is
    `:slice` (drill in via `:path`) when `:mode :full`; `:switch-mode`
    (downshift to summary) when overflow happens in `:full` mode.

  ## Args

  | Arg | Type | Default | Notes |
  |---|---|---|---|
  | `:machine-id` | keyword | **required** | the registered machine id |
  | `:frame` | keyword | nil | scope to one frame; nil → resolve sole frame |
  | `:path` | vec | nil | slice into the spec, like `get-in` |
  | `:mode` | keyword | `:summary` | `:summary` or `:full` |
  | `:include-sensitive?` | bool | false | passes to the runtime walker |
  | `:include-large?` | bool | false | passes to the runtime walker |
  | `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

  ## Return shape

      {:ok? true
       :frame <kw>
       :machine-id <kw>
       :mode <:summary|:full>
       :state <map>           ; sliced when :path supplied
       :path <vec?>           ; only when :path supplied
       :elided-large <int?>}

  ## Source-coord pin

  Cite: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
  bead #18. Catalogue entry lives in
  `tools/causa-mcp/spec/004-Tools-Catalogue.md`."
  (:require [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.eval-form :as ef]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.privacy :as privacy]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.wire :as wire]))

(def ^:private summary-keys
  "Keys retained in `:mode :summary` — name, initial state, the keys
  of the transitions table (state names), and any spec-level tags. The
  heavy per-transition handler details (event vectors, guards,
  effects) are omitted so a top-level inspection call ships a small
  payload."
  [:initial-state :tags])

(defn build-form
  "Build the eval-form addressing the runtime accessor. Pure."
  [opts]
  (-> (ef/rt-call 'get-machine-state opts)
      (ef/emit)
      (ef/wrap-origin)))

(defn- summarise-state
  "Project the full machine spec down to the summary slots. Returns a
  map with `:initial-state`, `:tags`, and `:state-names` (the keys of
  the transitions table) — enough for an agent to know what the
  machine is without shipping per-transition detail."
  [state]
  (cond-> {}
    (contains? state :initial-state)
    (assoc :initial-state (:initial-state state))

    (contains? state :tags)
    (assoc :tags (:tags state))

    (map? (:transitions state))
    (assoc :state-names (vec (sort (keys (:transitions state)))))))

(defn- apply-path
  "Slice `state` by `path` using `get-in`. Returns the addressed
  subtree or nil if the path doesn't exist. nil/empty path is the
  identity."
  [state path]
  (if (seq path)
    (get-in state path)
    state))

(defn shape-envelope
  "Shape the runtime's `{:ok? true :frame :machine-id :state}` response
  into the MCP envelope. Pure — tests pin the shaping logic."
  [runtime-envelope {:keys [mode path]}]
  (let [{:keys [ok?] :as env} (if (map? runtime-envelope) runtime-envelope {})]
    (cond
      (false? ok?)
      env

      (not (true? ok?))
      {:ok?     false
       :reason  :unexpected-shape
       :runtime runtime-envelope
       :hint    "runtime/get-machine-state returned a non-envelope shape"}

      :else
      (let [state    (:state env)
            sliced   (apply-path state path)
            projected (if (and (= :summary mode) (map? sliced))
                        (summarise-state sliced)
                        sliced)
            elided   (elision/count-elided-markers projected)
            base     (cond-> {:ok?        true
                              :frame      (:frame env)
                              :machine-id (:machine-id env)
                              :mode       (or mode :summary)
                              :state      projected}
                       (seq path) (assoc :path (vec path)))]
        (wire/with-indicators base {:elided elided})))))

(defn- parse-path-arg
  "Coerce the `:path` MCP arg to a CLJS vector. Accepts an EDN-printed
  vector string (`\"[:cart :items]\"`); returns nil on parse failure /
  absent."
  [v]
  (cond
    (nil? v)     nil
    (vector? v)  v
    (string? v)  (try (let [parsed (cljs.reader/read-string v)]
                        (when (vector? parsed) parsed))
                      (catch :default _ nil))
    :else        nil))

(defn get-machine-state-tool
  "MCP handler for `get-machine-state`. Validates `:machine-id`
  pre-flight; runtime accessor validates again."
  [conn args]
  (let [build-id   (wire/arg-build args)
        machine-id (wire/arg-keyword args :machine-id)
        frame      (wire/arg-keyword args :frame)
        path       (parse-path-arg (wire/arg args :path))
        mode       (or (wire/arg-keyword args :mode) :summary)]
    (cond
      (nil? machine-id)
      (js/Promise.resolve
        (wire/ok-text {:ok?    false
                       :reason :missing-machine-id
                       :hint   "Pass :machine-id <keyword>, e.g. :checkout/fsm."}))

      (not (contains? #{:summary :full} mode))
      (js/Promise.resolve
        (wire/ok-text {:ok?    false
                       :reason :invalid-mode
                       :given  mode
                       :hint   "mode must be :summary or :full"}))

      :else
      (let [runtime-opts (cond-> {:machine-id         machine-id
                                  :include-sensitive? (privacy/parse-include-sensitive args)
                                  :include-large?     (elision/parse-include-large args)}
                           frame (assoc :frame frame))
            form         (build-form runtime-opts)]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [runtime-envelope]
                     (wire/ok-text (shape-envelope runtime-envelope
                                                   {:mode mode :path path}))))
            (.catch (fn [err] (probe/err->result :get-machine-state-failed err))))))))

(def descriptor
  {:name        "get-machine-state"
   :description (str "Per-machine snapshot — registered FSM spec for the "
                     "named machine. Default :mode :summary returns the "
                     "initial-state + tags + state-names; :mode :full "
                     "returns the entire metadata map. Path slicing via "
                     ":path drills into a subtree like get-in.")
   :input-schema #js {:type "object"
                      :required #js ["machine-id"]
                      :properties #js {:machine-id         #js {:type "string"}
                                       :frame              #js {:type "string"}
                                       :path               #js {:type "string"}
                                       :mode               #js {:type "string"}
                                       :include-sensitive? #js {:type "boolean"}
                                       :include-large?     #js {:type "boolean"}
                                       :max-tokens         #js {:type "integer"}}}})

(registry/register-tool! (:name descriptor) get-machine-state-tool descriptor)
