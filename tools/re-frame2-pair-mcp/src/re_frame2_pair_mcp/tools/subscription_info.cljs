(ns re-frame2-pair-mcp.tools.subscription-info
  "Tool: subscription-info — list active streaming subscriptions (rf2-zjz9q).

  Wraps the `re-frame2-pair.runtime/subscription-info` diagnostic so AI
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
  (:require [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(defn- filter-form
  "Build the per-sub `filterv` body — `subs` when no filters apply, a
  one-predicate `filterv` when exactly one, an `and`-combined chain
  when both. The form is composed Clojure-side (rf2-ambfv) so the
  runtime never sees `(if nil ...)` no-op branches for absent
  filters."
  [topic sub-id]
  (let [preds (cond-> []
                topic  (conj (str "(= (:topic %) " (pr-str topic) ")"))
                sub-id (conj (str "(= (:id %) "    (pr-str sub-id) ")")))]
    (case (count preds)
      0 "subs"
      1 (str "(filterv #" (first preds) " subs)")
      (str "(filterv #(and " (str/join " " preds) ") subs)"))))

(defn subscription-info-tool [conn args]
  (let [build-id (wire/arg-build args)
        topic    (wire/arg-keyword args :topic)
        sub-id   (wire/arg args :sub-id)
        form     (ef/emit
                   (ef/rt-let ['r    (ef/rt-call 'subscription-info)
                               'subs (ef/rt-raw "(:subs r)")]
                              (ef/rt-raw
                                (str "(assoc r :subs " (filter-form topic sub-id) ")"))))]
    (-> (probe/ensure-runtime! conn build-id)
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
        (.then (fn [v] (wire/ok-text (if (map? v) v {:ok? true :subs []}))))
        (.catch (fn [err] (probe/err->result :subscription-info-failed err))))))
