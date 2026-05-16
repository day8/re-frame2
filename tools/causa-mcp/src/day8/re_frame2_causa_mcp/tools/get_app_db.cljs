(ns day8.re-frame2-causa-mcp.tools.get-app-db
  "Tool: `get-app-db` — direct-read app-db with elide-wire-value
  (rf2-8xzoe.16, T-Insp-3 of the causa-mcp inspection tranche).

  Returns the current `app-db` value at a frame, optionally scoped by
  `:path` (per `get-in` semantics) and projected through summary mode
  by default. Per the MUST inventory in
  `tools/causa-mcp/spec/004-Wire-Pipeline.md` §Direct-read:

  - **MUST 8** — path slicing is the default; the full app-db is
    intentionally not the no-arg shape (it would blow the cap for
    every non-trivial app).
  - **MUST 9** — summary mode is the default; `:mode :summary` ships
    a `{:type :map :top-keys [...] :count <int>}` overview; `:mode
    :full` ships the addressed value verbatim.
  - **MUST 19** — direct-read privacy posture: both
    `:include-sensitive?` and `:include-large?` default `false`. The
    runtime accessor routes through `re-frame.core/elide-wire-value`
    which substitutes the `:rf/redacted` sentinel at declared-
    sensitive leaves and the `{:rf.size/large-elided ...}` marker at
    over-threshold leaves.

  ## Wire-boundary contract

  - **W-6 size elision** — counted on the kept payload; the runtime
    walker already substituted markers server-side per MUST 17 (single
    normative emission site).
  - **B-1 privacy** — not directly applicable here (the payload is
    user data, not trace events with the top-level `:sensitive?` flag;
    declared-sensitive leaves are redacted by the runtime walker via
    MUST 19).
  - **W-1 token cap** — dispatcher-level. Cap-reached hint is
    `:slice` (drill deeper via `:path`) for `:mode :full`;
    `:switch-mode` (downshift to `:summary`) for `:mode :full`
    without a path.

  ## Args

  | Arg | Type | Default | Notes |
  |---|---|---|---|
  | `:frame` | keyword | nil | scope to one frame; nil → resolve sole frame |
  | `:path` | EDN-vec str | `[]` | slice into app-db, like `get-in` |
  | `:mode` | keyword | `:summary` | `:summary` or `:full` |
  | `:include-sensitive?` | bool | false | passes to the runtime walker |
  | `:include-large?` | bool | false | passes to the runtime walker |
  | `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

  ## Return shape

      {:ok? true
       :frame <kw>
       :path <vec>
       :mode <:summary|:full>
       :value <edn>
       :elided-large <int?>}

  ## Source-coord pin

  Cite: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
  bead #16. Catalogue entry lives in
  `tools/causa-mcp/spec/004-Tools-Catalogue.md`."
  (:require [cljs.reader :as edn]
            [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.eval-form :as ef]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.privacy :as privacy]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.wire :as wire]))

(def ^:const summary-keys-cap
  "Top-N keys included verbatim in a summary marker. Above this, the
  marker truncates and attaches `:keys-truncated? true` so the marker
  itself stays bounded — a 5,000-entry map's key list alone would
  exceed the wire cap otherwise. 64 mirrors pair2-mcp's summary
  primitive (rf2-qta8j) so cross-MCP agents recognise the cap."
  64)

(defn build-form
  "Build the eval-form addressing the runtime accessor. Pure."
  [opts]
  (-> (ef/rt-call 'get-app-db opts)
      (ef/emit)
      (ef/wrap-origin)))

(defn- summarise-value
  "Project `v` down to a top-level summary marker. Cheap — one pass
  over the top-level structure, no deep walk. Returns the value
  unchanged for scalars (they already fit the wire cap by definition;
  wrapping in a summary marker would add tokens without saving any).

  Marker shape mirrors pair2-mcp's `tools/summary/tree-summary` so a
  cross-MCP agent reads the same shape from both servers."
  [v]
  (cond
    (map? v)
    (let [ks    (vec (keys v))
          n     (count ks)
          shown (if (> n summary-keys-cap)
                  (subvec ks 0 summary-keys-cap)
                  ks)]
      {:rf.mcp/summary
       (cond-> {:type   :map
                :top-keys shown
                :count  n}
         (> n summary-keys-cap) (assoc :keys-truncated? true))})

    (vector? v)
    {:rf.mcp/summary {:type :vector :count (count v)}}

    (set? v)
    {:rf.mcp/summary {:type :set :count (count v)}}

    (sequential? v)
    {:rf.mcp/summary {:type :seq :count (count v)}}

    :else
    ;; Scalars (numbers, strings, keywords, nil, boolean) ride through
    ;; unchanged — wrapping them adds tokens without saving any.
    v))

(defn- parse-path-arg
  "Coerce the `:path` MCP arg to a CLJS vector. Accepts a CLJS vector
  passthrough, an EDN-printed vector string (`\"[:cart :items 0]\"`),
  or nil. Returns `nil` for absent / unparseable input (the runtime
  accessor treats nil as 'no slice', i.e. the full root)."
  [v]
  (cond
    (nil? v)     nil
    (vector? v)  v
    (string? v)  (try (let [parsed (edn/read-string v)]
                        (when (vector? parsed) parsed))
                      (catch :default _ nil))
    :else        nil))

(defn shape-envelope
  "Shape the runtime's `{:ok? true :frame :path :value}` response into
  the MCP envelope. Pure — tests pin the summary / elision logic.

  `mode` selects the projection: `:summary` (default) wraps the value
  in a `:rf.mcp/summary` marker; `:full` ships the value unchanged.
  Marker count for W-6 indicator runs on the projected value so
  summary-mode ships a small payload even when the underlying
  app-db has many elision markers."
  [runtime-envelope {:keys [mode]}]
  (let [{:keys [ok?] :as env} (if (map? runtime-envelope) runtime-envelope {})]
    (cond
      (false? ok?)
      env

      (not (true? ok?))
      {:ok?     false
       :reason  :unexpected-shape
       :runtime runtime-envelope
       :hint    "runtime/get-app-db returned a non-envelope shape"}

      :else
      (let [raw-value  (:value env)
            projected  (if (= :full mode) raw-value (summarise-value raw-value))
            elided     (elision/count-elided-markers projected)]
        (wire/with-indicators
          {:ok?   true
           :frame (:frame env)
           :path  (vec (:path env))
           :mode  (or mode :summary)
           :value projected}
          {:elided elided})))))

(defn get-app-db-tool
  "MCP handler for `get-app-db`. Validates `:mode`; defaults `:path`
  to nil (runtime resolves to the full root). Note: the spec MUST 8
  says path slicing is the default — agents should ALWAYS pass
  `:path` for non-trivial app-dbs. The default `:mode :summary`
  protects the wire cap when an agent omits the path; the operator
  hint surfaces in the `:rf.mcp/overflow` continuation when needed."
  [conn args]
  (let [build-id (wire/arg-build args)
        frame    (wire/arg-keyword args :frame)
        path     (parse-path-arg (wire/arg args :path))
        mode     (or (wire/arg-keyword args :mode) :summary)
        incl?    (privacy/parse-include-sensitive args)
        incl-large? (elision/parse-include-large args)]
    (cond
      (not (contains? #{:summary :full} mode))
      (js/Promise.resolve
        (wire/ok-text {:ok?    false
                       :reason :invalid-mode
                       :given  mode
                       :hint   "mode must be :summary or :full"}))

      :else
      (let [runtime-opts (cond-> {:include-sensitive? incl?
                                  :include-large?     incl-large?}
                           frame (assoc :frame frame)
                           (seq path) (assoc :path path))
            form         (build-form runtime-opts)]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [runtime-envelope]
                     (wire/ok-text (shape-envelope runtime-envelope
                                                   {:mode mode}))))
            (.catch (fn [err] (probe/err->result :get-app-db-failed err))))))))

(def descriptor
  {:name        "get-app-db"
   :description (str "Direct-read app-db at a frame, optionally scoped "
                     "by :path (get-in semantics). Default :mode "
                     ":summary returns a top-level overview marker; "
                     ":mode :full returns the addressed value verbatim. "
                     "Sensitive/large values are redacted/elided by "
                     "default; pass :include-sensitive? true / "
                     ":include-large? true to opt back in. Path "
                     "slicing is the recommended default — for "
                     "non-trivial app-dbs, pass :path to bound the "
                     "response.")
   :input-schema #js {:type "object"
                      :properties #js {:frame              #js {:type "string"}
                                       :path               #js {:type "string"}
                                       :mode               #js {:type "string"}
                                       :include-sensitive? #js {:type "boolean"}
                                       :include-large?     #js {:type "boolean"}
                                       :max-tokens         #js {:type "integer"}}}})

(registry/register-tool! (:name descriptor) get-app-db-tool descriptor)
