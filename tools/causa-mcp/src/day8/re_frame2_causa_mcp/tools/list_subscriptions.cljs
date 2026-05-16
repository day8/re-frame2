(ns day8.re-frame2-causa-mcp.tools.list-subscriptions
  "Tool: `list-subscriptions` — diagnostic enumerating active streaming
  subscriptions (rf2-8xzoe.28, T-Stream-3 of the causa-mcp streaming
  tranche).

  The **eighteenth** tool added by DESIGN-RATIONALE Lock #12
  (rf2-3we2k, 2026-05-14). Wraps the runtime's `list-subscriptions`
  diagnostic so an agent doesn't need an `eval-cljs` round-trip to ask
  \"what streams are currently open?\". Useful when a streaming probe
  has gone quiet (confirm the sub is still registered) or when
  pruning leaked subs across crashed `subscribe` calls.

  Optional `:topic` / `:sub-id` filters narrow the enumeration. The
  runtime accessor returns per-sub metadata (`:id`, `:topic`,
  `:filter`, `:origin`, `:created-at`); the queue-depth / overflow
  fields documented in the runtime ns docstring will arrive when the
  server-side pump tranche grows them (per
  `tools/causa-mcp/spec/000-Vision.md` §Two namespaces, two sides).

  ## Wire-boundary contract

  - **B-1 privacy** — not applicable (subscription metadata is not
    trace-event-shaped; `:filter` may contain user-supplied keywords
    but doesn't carry user data).
  - **W-6 size elision** — counted defensively on the runtime
    envelope.
  - **W-1 token cap** — dispatcher-level via `tools.cljs/invoke`.

  ## Args

  | Arg | Type | Default | Notes |
  |---|---|---|---|
  | `:topic` | keyword | nil | filter to one topic |
  | `:sub-id` | string | nil | filter to one sub-id |
  | `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

  ## Return shape

      {:ok? true
       :subs [{:id <uuid> :topic <kw> :filter <map>
               :origin <kw> :created-at <ms>} ...]
       :count <int>
       :elided-large <int?>}

  ## Source-coord pin

  Cite: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
  bead #28; DESIGN-RATIONALE.md Lock #12 (rf2-3we2k). Catalogue entry
  lives in `tools/causa-mcp/spec/004-Tools-Catalogue.md`."
  (:require [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.eval-form :as ef]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.wire :as wire]))

(defn build-form
  "Build the eval-form addressing the runtime accessor. Pure."
  [opts]
  (-> (ef/rt-call 'list-subscriptions opts)
      (ef/emit)
      (ef/wrap-origin)))

(defn shape-envelope
  "Pass the runtime's `{:ok? true :subs <vec> :count <int>}` through;
  count defensive elision markers. Pure — tests pin the indicator
  stamping."
  [runtime-envelope]
  (let [{:keys [ok?] :as env} (if (map? runtime-envelope) runtime-envelope {})]
    (cond
      (false? ok?) env

      (not (true? ok?))
      {:ok?     false
       :reason  :unexpected-shape
       :runtime runtime-envelope
       :hint    "runtime/list-subscriptions returned a non-envelope shape"}

      :else
      (let [subs   (vec (or (:subs env) []))
            elided (elision/count-elided-markers subs)]
        (wire/with-indicators
          {:ok?   true
           :subs  subs
           :count (count subs)}
          {:elided elided})))))

(defn list-subscriptions-tool [conn args]
  (let [build-id (wire/arg-build args)
        topic    (wire/arg-keyword args :topic)
        sub-id   (wire/arg args :sub-id)
        opts     (cond-> {}
                   topic  (assoc :topic  topic)
                   sub-id (assoc :sub-id sub-id))]
    (-> (probe/ensure-runtime! conn build-id)
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id (build-form opts))))
        (.then (fn [runtime-env] (wire/ok-text (shape-envelope runtime-env))))
        (.catch (fn [err] (probe/err->result :list-subscriptions-failed err))))))

(def descriptor
  {:name        "list-subscriptions"
   :description (str "Enumerate active streaming subscriptions. Returns "
                     "per-sub metadata (id, topic, filter, origin, "
                     "created-at). Optional :topic / :sub-id filters "
                     "narrow the enumeration. Diagnostic — useful when "
                     "a streaming probe seems quiet (confirm the sub "
                     "is still registered) or when pruning leaked "
                     "subs.")
   :input-schema #js {:type "object"
                      :properties #js {:topic      #js {:type "string"}
                                       :sub-id     #js {:type "string"}
                                       :max-tokens #js {:type "integer"}}}})

(registry/register-tool! (:name descriptor) list-subscriptions-tool descriptor)
