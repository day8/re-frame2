(ns day8.re-frame2-causa-mcp.tools.get-handlers
  "Tool: `get-handlers` — registrar enumeration grouped by kind
  (rf2-8xzoe.21, T-Insp-8 of the causa-mcp inspection tranche).

  Returns the registered handlers (events, subs, fxs, machines, flows,
  …) projected as a `{:kind <kw> :id <any> :meta <map>}` record
  vector. Default mode groups results by `:kind` so an agent can read
  the registry surface as a one-call discovery aid (`registry-list`
  in pair2-mcp parlance). Optional `:kind` arg narrows to a single
  registrar kind for a focused query.

  ## Wire-boundary contract

  - **W-6 size elision** — counts elision markers in the handler-meta
    payload. The runtime accessor leaves the registry metadata as-is
    (registrar metadata is generally small; no per-handler
    elide-wire-value walk in the runtime); a future bead may add
    walker plumbing for handlers whose `:meta` slot carries large
    blobs, at which point this count surfaces them.
  - **B-1 privacy** — not directly applicable here; the registry is
    framework metadata, not user-data.
  - **W-1 token cap** — dispatcher-level via `tools.cljs/invoke`.
    Per-tool cap-reached hint is `:narrow-filter` (default) — narrow
    via `:kind` to slice.

  ## Args

  | Arg | Type | Default | Notes |
  |---|---|---|---|
  | `:kind` | keyword | nil | narrow to one registrar kind (`:event`, `:sub`, `:fx`, `:machine`, `:flow`, …) |
  | `:group-by-kind?` | bool | true | shape result as `{<kind> [<handler> ...]}`; `false` ⇒ flat vector |
  | `:include-sensitive?` | bool | false | passes to the runtime walker |
  | `:include-large?` | bool | false | passes to the runtime walker |
  | `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

  ## Return shape (default — grouped by kind)

      {:ok? true
       :handlers {<kind> [{:id <any> :meta <map>} ...] ...}
       :count <int>
       :kinds <vec of kw>
       :elided-large <int?>}

  ## Source-coord pin

  Cite: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
  bead #21. Catalogue entry lives in
  `tools/causa-mcp/spec/004-Tools-Catalogue.md`."
  (:require [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.eval-form :as ef]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.privacy :as privacy]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.wire :as wire]))

(defn build-form
  "Build the eval-form string targeting the runtime accessor. Pure."
  [opts]
  (-> (ef/rt-call 'get-handlers opts)
      (ef/emit)
      (ef/wrap-origin)))

(defn- group-by-kind
  "Group the flat `[{:kind :id :meta} ...]` handler vector by `:kind`.
  The per-kind vector preserves source order so a downstream `:sub` or
  `:flow` lookup mirrors the registrar's insertion order."
  [handlers]
  (reduce
    (fn [acc {:keys [kind] :as h}]
      (update acc kind (fnil conj []) (dissoc h :kind)))
    {}
    handlers))

(defn shape-envelope
  "Shape the runtime's `{:ok? true :handlers <vec> :count <int>}`
  response into the MCP envelope. Pure — tests pin the shaping logic.

  `group?` controls the response shape:
    - true (default) ⇒ `:handlers` is `{<kind> [{:id :meta} ...]}`
      and `:kinds` enumerates the kinds present.
    - false ⇒ `:handlers` is the flat vector the runtime returned."
  [runtime-envelope group?]
  (let [{:keys [ok?] :as env} (if (map? runtime-envelope) runtime-envelope {})]
    (cond
      (false? ok?)
      env

      (not (true? ok?))
      {:ok?     false
       :reason  :unexpected-shape
       :runtime runtime-envelope
       :hint    "runtime/get-handlers returned a non-envelope shape"}

      :else
      (let [handlers (vec (:handlers env))
            elided   (elision/count-elided-markers handlers)
            shaped   (if group?
                       (let [grouped (group-by-kind handlers)]
                         {:handlers grouped
                          :kinds    (vec (sort (keys grouped)))
                          :count    (count handlers)})
                       {:handlers handlers
                        :count    (count handlers)})]
        (wire/with-indicators
          (assoc shaped :ok? true)
          {:elided elided})))))

(defn get-handlers-tool
  "MCP handler for `get-handlers`. Returns a Promise of the JS-shape
  MCP result."
  [conn args]
  (let [build-id (wire/arg-build args)
        kind     (wire/arg-keyword args :kind)
        ;; Default group-by-kind? = true. The arg-bool helper falls
        ;; back to the supplied default when the slot is absent.
        group?   (wire/arg-bool args :group-by-kind? true)
        opts     (cond-> {:include-sensitive? (privacy/parse-include-sensitive args)
                          :include-large?     (elision/parse-include-large args)}
                   kind (assoc :kind kind))
        form     (build-form opts)]
    (-> (probe/ensure-runtime! conn build-id)
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
        (.then (fn [runtime-envelope]
                 (wire/ok-text (shape-envelope runtime-envelope group?))))
        (.catch (fn [err] (probe/err->result :get-handlers-failed err))))))

(def descriptor
  {:name        "get-handlers"
   :description (str "Enumerate registered re-frame2 handlers grouped by "
                     "kind (event / sub / fx / cofx / machine / flow / "
                     "frame / view / reg-machine). Narrow via :kind. "
                     "Default :group-by-kind? true returns a kind-keyed "
                     "map; false returns a flat vector.")
   :input-schema #js {:type "object"
                      :properties #js {:kind               #js {:type "string"}
                                       :group-by-kind?     #js {:type "boolean"}
                                       :include-sensitive? #js {:type "boolean"}
                                       :include-large?     #js {:type "boolean"}
                                       :max-tokens         #js {:type "integer"}}}})

(registry/register-tool! (:name descriptor) get-handlers-tool descriptor)
