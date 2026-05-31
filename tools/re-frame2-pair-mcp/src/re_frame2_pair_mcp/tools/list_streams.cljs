(ns re-frame2-pair-mcp.tools.list-streams
  "Tool: list-streams — list active STREAMING-tap subscriptions opened
  via `subscribe` (rf2-qicji; the streaming diagnostic formerly carried
  by `list-subscriptions`).

  ## What this answers

  \"What streams are currently open?\" / \"is my probe still alive?\" —
  the trace / epoch / fx / error / frameless queues opened by the
  `subscribe` tool and torn down by `unsubscribe`. Pure read over the
  runtime's `subscriptions` atom; does NOT drain any queues.

  Wraps the `re-frame2-pair.runtime/subscription-info` diagnostic so AI
  clients don't need an `eval-cljs` round-trip just to ask \"what
  streams are currently open?\". One cheap nREPL eval — the runtime fn
  is a pure read over the in-memory `subscriptions` atom and does NOT
  drain queues. Useful when a streaming probe seems to have gone quiet
  (confirm the sub is still registered, check `:queue-depth` /
  `:overflow-reason` for evidence of a dead consumer).

  ## rf2-qicji rename

  This tool is the streaming-tap diagnostic that `list-subscriptions`
  used to be. rf2-qicji repointed `list-subscriptions` at the LIVE
  reactive sub-cache (the answer to \"what reactive subscriptions are
  active?\", matching `snapshot :sub-cache`); the streaming-tap
  diagnostic kept its behaviour and moved here under an accurate name.
  The two distinct concepts — reactive sub-cache vs streaming tap — no
  longer share one name. No back-compat shim (pre-alpha).

  Args (all optional):
    :topic   keyword or string — filter to a single topic
             (`:trace` / `:epoch` / `:fx` / `:error` / `:frameless`).
    :sub-id  string uuid — return only the matching sub. Convenient
             for \"is this specific stream still alive?\" checks.

  Returns `{:ok? true :subs [{:id :topic :filter :queue-depth
  :queue-bytes :dropped-events :dropped-bytes :overflow-reason
  :created-at}]}`. Empty `:subs` vector when no streams are open (or
  when the filter matches nothing)."
  (:require [clojure.string :as str]
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

(defn list-streams-tool [conn args]
  (let [build-id (wire/arg-build conn args)
        topic    (wire/arg-keyword args :topic)
        sub-id   (wire/arg args :sub-id)
        form     (ef/emit
                   (ef/rt-let ['r    (ef/rt-call 'subscription-info)
                               'subs (ef/rt-raw "(:subs r)")]
                              (ef/rt-raw
                                (str "(assoc r :subs " (filter-form topic sub-id) ")"))))]
    (probe/eval-after-runtime!
      conn build-id form :list-streams-failed
      (fn [v] (wire/ok-text (if (map? v) v {:ok? true :subs []}))))))
