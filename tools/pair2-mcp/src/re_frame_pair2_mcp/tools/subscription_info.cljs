(ns re-frame-pair2-mcp.tools.subscription-info
  "Tool: subscription-info — list active streaming subscriptions (rf2-zjz9q).

  Wraps the `re-frame-pair2.runtime/subscription-info` diagnostic so AI
  clients don't need an `eval-cljs` round-trip just to ask \"what
  streams are currently open?\". One cheap nREPL eval — the runtime fn
  is a pure read over the in-memory `subscriptions` atom and does NOT
  drain queues. Useful when a streaming probe seems to have gone quiet
  (confirm the sub is still registered, check `:queue-depth` /
  `:overflow-reason` for evidence of a dead consumer).

  Args (all optional):
    :topic   keyword or string — filter to a single topic
             (`:trace` / `:epoch` / `:fx` / `:error`).
    :sub-id  string uuid — return only the matching sub. Convenient
             for \"is this specific stream still alive?\" checks.

  Returns `{:ok? true :subs [{:id :topic :filter :queue-depth
  :queue-bytes :dropped-events :dropped-bytes :overflow-reason
  :created-at}]}`. Empty `:subs` vector when no streams are open (or
  when the filter matches nothing)."
  (:require [re-frame-pair2-mcp.nrepl :as nrepl]
            [re-frame-pair2-mcp.tools.wire :as wire]
            [re-frame-pair2-mcp.tools.probe :as probe]))

(defn subscription-info-tool [conn args]
  (let [build-id (wire/arg-build args)
        topic    (some-> (wire/arg args :topic) keyword)
        sub-id   (wire/arg args :sub-id)
        form     (str "(let [r (re-frame-pair2.runtime/subscription-info)"
                      "      subs (:subs r)"
                      "      f1 (if " (pr-str topic)
                      "           (filterv #(= (:topic %) " (pr-str topic) ") subs)"
                      "           subs)"
                      "      f2 (if " (pr-str sub-id)
                      "           (filterv #(= (:id %) " (pr-str sub-id) ") f1)"
                      "           f1)]"
                      "  (assoc r :subs f2))")]
    (-> (probe/ensure-runtime! conn build-id)
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
        (.then (fn [v] (wire/ok-text (if (map? v) v {:ok? true :subs []}))))
        (.catch (fn [err] (probe/err->result :subscription-info-failed err))))))
