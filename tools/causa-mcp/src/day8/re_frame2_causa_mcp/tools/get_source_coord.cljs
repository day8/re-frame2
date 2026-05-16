(ns day8.re-frame2-causa-mcp.tools.get-source-coord
  "Tool: `get-source-coord` — single-handler source-coord lookup
  (rf2-8xzoe.22, T-Insp-9 of the causa-mcp inspection tranche).

  Returns the source coord (`:ns :line :column :file`) for a given
  `(kind, id)` pair — the registration-metadata slot the framework
  stamps at `reg-*` macroexpand time per Spec 001 §The public
  registrar query API. The coord aligns with editor-URI substitution
  so an agent host can render a jump-to-definition link off the
  response (the wire-pipeline `:rf.source/uri` decoration is the
  cross-MCP convention).

  ## Wire-boundary contract

  - **W-6 size elision** — not exercised here in practice (source-coord
    is four scalar fields), but counted defensively in case a future
    extension carries large auxiliary metadata.
  - **B-1 privacy** — not applicable (source-coord is framework
    metadata).
  - **W-1 token cap** — dispatcher-level via `tools.cljs/invoke`.

  ## Args

  | Arg | Type | Default | Notes |
  |---|---|---|---|
  | `:kind` | keyword | nil (REQUIRED) | registrar kind (`:event`, `:sub`, …) |
  | `:id`   | keyword | nil (REQUIRED) | the registered id |
  | `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

  ## Return shape

      {:ok? true :kind <kw> :id <any> :source-coord {:ns :line :column :file}}

  Or on miss:

      {:ok? false :reason :no-source-coord :kind <kw> :id <any>}
      {:ok? false :reason :missing-kind :hint <s>}
      {:ok? false :reason :missing-id   :hint <s>}

  ## Source-coord pin

  Cite: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
  bead #22. Catalogue entry lives in
  `tools/causa-mcp/spec/004-Tools-Catalogue.md`."
  (:require [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.eval-form :as ef]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.wire :as wire]))

(defn build-form
  "Build the eval-form addressing the runtime accessor. Pure."
  [opts]
  (-> (ef/rt-call 'get-source-coord opts)
      (ef/emit)
      (ef/wrap-origin)))

(defn shape-envelope
  "Pass the runtime's envelope through largely unchanged; stamp
  `:elided-large` if the walker somehow inserted a marker on the
  source-coord payload (defensive — source-coord is scalar fields)."
  [runtime-envelope]
  (let [{:keys [ok?] :as env} (if (map? runtime-envelope) runtime-envelope {})]
    (cond
      ;; Structured failure (missing kind / id / coord) — pass verbatim.
      (false? ok?)
      env

      (not (true? ok?))
      {:ok?     false
       :reason  :unexpected-shape
       :runtime runtime-envelope
       :hint    "runtime/get-source-coord returned a non-envelope shape"}

      :else
      (let [elided (elision/count-elided-markers (:source-coord env))]
        (wire/with-indicators env {:elided elided})))))

(defn get-source-coord-tool
  "MCP handler for `get-source-coord`. Validates required args
  pre-flight; runtime accessor validates again (defence in depth)."
  [conn args]
  (let [build-id (wire/arg-build args)
        kind     (wire/arg-keyword args :kind)
        id       (wire/arg-keyword args :id)]
    (cond
      (nil? kind)
      (js/Promise.resolve
        (wire/ok-text {:ok?    false
                       :reason :missing-kind
                       :hint   "Pass :kind <registrar-kind>, e.g. :event."}))

      (nil? id)
      (js/Promise.resolve
        (wire/ok-text {:ok?    false
                       :reason :missing-id
                       :hint   "Pass :id <registered-id>, e.g. :cart/add."}))

      :else
      (let [form (build-form {:kind kind :id id})]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [runtime-envelope]
                     (wire/ok-text (shape-envelope runtime-envelope))))
            (.catch (fn [err] (probe/err->result :get-source-coord-failed err))))))))

(def descriptor
  {:name        "get-source-coord"
   :description (str "Return the source coord (:ns :line :column :file) "
                     "for a registered handler. Required args: :kind "
                     "(registrar kind keyword) and :id (registered id "
                     "keyword). On miss returns :no-source-coord.")
   :input-schema #js {:type "object"
                      :required #js ["kind" "id"]
                      :properties #js {:kind       #js {:type "string"}
                                       :id         #js {:type "string"}
                                       :max-tokens #js {:type "integer"}}}})

(registry/register-tool! (:name descriptor) get-source-coord-tool descriptor)
