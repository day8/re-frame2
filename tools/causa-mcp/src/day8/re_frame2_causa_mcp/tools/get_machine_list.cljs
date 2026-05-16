(ns day8.re-frame2-causa-mcp.tools.get-machine-list
  "Tool: `get-machine-list` — registered-machine enumeration
  (rf2-8xzoe.19, T-Insp-6 of the causa-mcp inspection tranche).

  Returns the registered machines per frame with their current
  metadata (the registered FSM spec — transitions, initial-state,
  tags). Bounded by the per-frame machine count which is typically
  small (single-digit per frame in practice), so this tool has no
  per-call pagination — the full machine roster rides on every call.

  ## Wire-boundary contract

  - **W-6 size elision** — the runtime accessor already routes each
    machine's metadata through `re-frame.core/elide-wire-value`, so
    the marker count on the kept payload reflects whatever the walker
    emitted. This tool counts markers via
    `elision/count-elided-markers` and stamps `:elided-large`.
  - **B-1 privacy default-suppress** — not directly applicable here
    (machine metadata isn't trace-event-shaped), but the
    `:include-sensitive?` arg is honoured by routing through the
    runtime walker (which substitutes the `:rf/redacted` sentinel at
    declared-sensitive leaves per Spec 009 §Privacy).
  - **W-1 token cap** — dispatcher-level via `tools.cljs/invoke`.

  ## Args

  | Arg                   | Type    | Default | Notes                                  |
  |-----------------------|---------|---------|----------------------------------------|
  | `:include-sensitive?` | bool    | false   | passes to the runtime walker           |
  | `:include-large?`     | bool    | false   | passes to the runtime walker           |
  | `:max-tokens`         | int     | 5000    | per-call cap (`[500, 50000]`)          |

  ## Return shape

      {:ok? true
       :machines {<machine-id> <meta-map> ...}
       :count <int>
       :elided-large <int?>}

  ## Source-coord pin

  Cite: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
  bead #19. Catalogue entry lives in
  `tools/causa-mcp/spec/004-Tools-Catalogue.md`."
  (:require [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.eval-form :as ef]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.privacy :as privacy]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.wire :as wire]))

(defn build-form
  "Build the CLJS eval-form string the runtime evaluates. Pure (no I/O
  / Promise), so tests pin the form shape directly."
  [opts]
  (-> (ef/rt-call 'get-machine-list opts)
      (ef/emit)
      (ef/wrap-origin)))

(defn shape-envelope
  "Shape the runtime's `{:ok? true :machines <map> :count <int>}`
  response into the MCP envelope. Pure — tests pin the shaping logic
  without I/O.

  The runtime walker already elided sensitive / large slots inside the
  per-machine metadata; this fn counts the resulting markers and
  stamps `:elided-large` on the envelope when non-zero."
  [runtime-envelope]
  (let [{:keys [ok?] :as env} (if (map? runtime-envelope) runtime-envelope {})]
    (cond
      (false? ok?)
      env

      (not (true? ok?))
      {:ok?     false
       :reason  :unexpected-shape
       :runtime runtime-envelope
       :hint    "runtime/get-machine-list returned a non-envelope shape"}

      :else
      (let [machines (or (:machines env) {})
            elided   (elision/count-elided-markers machines)]
        (wire/with-indicators
          {:ok?      true
           :machines machines
           :count    (count machines)}
          {:elided elided})))))

(defn get-machine-list-tool
  "MCP handler for `get-machine-list`. Returns a Promise of the
  JS-shape MCP result."
  [conn args]
  (let [build-id (wire/arg-build args)
        opts     {:include-sensitive? (privacy/parse-include-sensitive args)
                  :include-large?     (elision/parse-include-large args)}
        form     (build-form opts)]
    (-> (probe/ensure-runtime! conn build-id)
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
        (.then (fn [runtime-envelope]
                 (wire/ok-text (shape-envelope runtime-envelope))))
        (.catch (fn [err] (probe/err->result :get-machine-list-failed err))))))

(def descriptor
  "MCP tool descriptor for `get-machine-list`."
  {:name        "get-machine-list"
   :description (str "Enumerate registered re-frame2 machines with their "
                     "current metadata (transitions, initial-state, tags). "
                     "Bounded by per-frame machine count; no pagination. "
                     "Pass :include-sensitive? true to opt back in to "
                     "declared-sensitive metadata leaves.")
   :input-schema #js {:type "object"
                      :properties #js {:include-sensitive? #js {:type "boolean"}
                                       :include-large?     #js {:type "boolean"}
                                       :max-tokens         #js {:type "integer"}}}})

(registry/register-tool! (:name descriptor) get-machine-list-tool descriptor)
