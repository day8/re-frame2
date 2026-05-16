(ns day8.re-frame2-causa-mcp.tools.unsubscribe
  "Tool: `unsubscribe` — bookend tear-down for a streaming subscription
  (rf2-8xzoe.27, T-Stream-2 of the causa-mcp streaming tranche).

  Drops the subscription from the runtime's `subscriptions` atom. The
  per-tick polling loop in an open `subscribe` `tools/call` observes
  the missing sub-id on its next drain and terminates its outer
  Promise with `:reason :sub-gone` (or, more precisely, with the
  drain-side `:gone?` signal — see `subscribe.cljs` controller).

  Idempotent — re-calling `unsubscribe` for an already-closed sub-id
  returns `:ok? true` with `:existed? false`, mirroring the runtime
  accessor's per-call ack shape.

  ## Wire-boundary contract

  - **B-1 privacy** — not applicable (ack envelope, not trace
    events).
  - **W-6 size elision** — counted defensively on the runtime
    envelope.
  - **W-1 token cap** — dispatcher-level via `tools.cljs/invoke`.

  ## Args

  | Arg | Type | Default | Notes |
  |---|---|---|---|
  | `:sub-id` | string | nil (REQUIRED) | the `sub-id` from a prior `subscribe` |
  | `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

  ## Return shape

      {:ok? true :sub-id <uuid> :existed? <bool>}

  ## Source-coord pin

  Cite: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
  bead #27. Catalogue entry lives in
  `tools/causa-mcp/spec/004-Tools-Catalogue.md`."
  (:require [clojure.string :as str]
            [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.eval-form :as ef]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.wire :as wire]))

(defn build-form
  "Build the eval-form addressing the runtime accessor. Pure."
  [sub-id]
  (-> (ef/rt-call 'unsubscribe! {:sub-id sub-id})
      (ef/emit)
      (ef/wrap-origin)))

(defn shape-envelope
  "Merge the runtime's ack into the envelope; count defensive
  elision markers. Pure — tests pin the indicator stamping."
  [runtime-envelope sub-id]
  (let [env (if (map? runtime-envelope) runtime-envelope {})
        merged (merge {:ok? true :sub-id sub-id} env)
        elided (elision/count-elided-markers merged)]
    (wire/with-indicators merged {:elided elided})))

(defn unsubscribe-tool [conn args]
  (let [build-id (wire/arg-build args)
        sub-id   (wire/arg args :sub-id)]
    (cond
      (or (nil? sub-id)
          (and (string? sub-id) (str/blank? sub-id)))
      (js/Promise.resolve
        (wire/err-text {:ok?    false
                        :reason :missing-sub-id
                        :hint   "Pass :sub-id <uuid> from a prior subscribe."}))

      :else
      (-> (probe/ensure-runtime! conn build-id)
          (.then (fn [_] (nrepl/cljs-eval-value conn build-id (build-form sub-id))))
          (.then (fn [runtime-env]
                   (wire/ok-text (shape-envelope runtime-env sub-id))))
          (.catch (fn [err] (probe/err->result :unsubscribe-failed err)))))))

(def descriptor
  {:name        "unsubscribe"
   :description (str "Close a streaming subscription by sub-id. "
                     "Idempotent: re-calling for an already-closed "
                     "sub-id returns :existed? false. An open "
                     "subscribe tools/call observes the missing "
                     "sub-id on its next polling tick and resolves "
                     "with :reason :unsubscribed. Required :sub-id.")
   :input-schema #js {:type "object"
                      :required #js ["sub-id"]
                      :properties #js {:sub-id     #js {:type "string"}
                                       :max-tokens #js {:type "integer"}}}})

(registry/register-tool! (:name descriptor) unsubscribe-tool descriptor)
