(ns re-frame2-pair-mcp.tools.list-subscriptions
  "Tool: list-subscriptions — list the LIVE reactive subscriptions
  materialised in a frame's per-frame sub-cache (rf2-qicji).

  ## What this answers

  \"What subscriptions are currently active in this frame?\" — the
  reactive sub-cache, the same source the `snapshot` tool's `:sub-cache`
  slice reads. Routes through the runtime's `sub-cache-info` fn, which
  reads `re-frame.subs.tooling/sub-cache-snapshot` (via the runtime's
  `sub-cache` fn) — the SAME accessor `snapshot :sub-cache` uses, so the
  two never disagree.

  ## rf2-qicji — wrong-source → right-source

  Before rf2-qicji this tool wrapped `re-frame2-pair.runtime/
  subscription-info`, which reads the STREAMING-tap registry (the
  trace / epoch / fx / error queues opened by `subscribe`). That
  registry is empty unless a streaming `subscribe` is open, so
  `list-subscriptions {frame :rf/default}` returned `{:subs []}` even
  when the frame had live reactive subscriptions — a false-empty
  correctness bug (the live evidence: `snapshot :sub-cache` showed
  `[[\"mounted?\"]]` for the same frame while this tool said `[]`).

  The fix routes `list-subscriptions` through `sub-cache-info` so it
  reports the live reactive cache. The streaming-tap diagnostic was NOT
  lost — it moved to the accurately-named `list-streams` tool (which
  still wraps `subscription-info`). The two distinct concepts no longer
  share one name.

  ## Disposal

  The reactive sub-cache is ref-counted and live: an entry appears the
  moment a view subscribes and DISAPPEARS when the last consumer
  disposes the reaction. So a sub that's been disposed (its view
  unmounted, no other subscribers) no longer shows up here — the
  acceptance contract per rf2-qicji.

  ## Args (all optional)

    :frame           keyword / string — the frame to read. Defaults to
                     the operating frame (per the runtime's frame
                     resolution); a multi-frame session with no
                     selection returns `{:ok? false :reason
                     :ambiguous-frame}` rather than silently reading
                     `:rf/default`.
    :include-values  boolean (default false) — when false, only the
                     query-vectors ride the wire (the cheap \"what's
                     subscribed\" read); when true each entry also
                     carries `:value` (current deref) and `:ref-count`.

  Returns `{:ok? true :frame <id> :count N :subs [<query-v> ...]}`
  (or, with `:include-values true`,
  `:subs [{:query-v <v> :value v :ref-count n} ...]`). Empty `:subs`
  vector when nothing is subscribed in the frame."
  (:require [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(defn list-subscriptions-tool [conn raw-args]
  (let [build-id (wire/arg-build conn raw-args)
        frame    (some-> (wire/arg raw-args :frame) args/->frame-keyword)
        ;; `:include-values` rides the shared bool-args accept-shape
        ;; contract (rf2-c4fmh) — `true` / `"true"` / `"yes"` / ... all
        ;; resolve; default false.
        incl?    (args/parse-bool-arg raw-args :include-values)
        opts     (cond-> {}
                   frame (assoc :frame frame)
                   incl? (assoc :include-values? true))
        form     (ef/emit (ef/rt-call 'sub-cache-info opts))]
    (-> (probe/ensure-runtime! conn build-id)
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
        (.then (fn [v] (wire/ok-text (if (map? v) v {:ok? true :subs []}))))
        (.catch (fn [err] (probe/err->result :list-subscriptions-failed err))))))
