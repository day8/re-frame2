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
  vector when nothing is subscribed in the frame.

  ## rf2-f1ose — `:include-values` egress redaction

  `:include-values true` ships each subscription's current `:value`
  (the deref) over the MCP wire. That value reads the SAME reactive
  sub-cache source `snapshot`'s `:sub-cache` slice reads, and a sub
  whose value derives from a declared-sensitive app-db slot would
  otherwise ride off-box verbatim. The eval form wraps each entry's
  `:value` through `re-frame.core/elide-wire-value` server-side — the
  SAME redaction `snapshot` applies to its `:sub-cache` slice — so a
  declared-sensitive value redacts to `:rf/redacted` and a declared-
  large value elides to `:rf.size/large-elided` when the
  `--allow-sensitive-reads` gate is OFF (the published-build default).
  When the gate is ON, the per-call `:include-sensitive` arg wins and
  raw values may pass — parity with snapshot / get-path / the epoch
  tools. The cheap query-vectors-only read (`:include-values false`)
  ships no values and needs no elision."
  (:require [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.elision :as elision]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(defn list-subscriptions-tool [conn raw-args]
  (let [build-id (wire/arg-build conn raw-args)
        frame    (some-> (wire/arg raw-args :frame) args/->frame-keyword)
        ;; `:include-values` rides the shared bool-args accept-shape
        ;; contract (rf2-c4fmh) — `true` / `"true"` / `"yes"` / ... all
        ;; resolve; default false.
        incl-vals? (args/parse-bool-arg raw-args :include-values)
        ;; rf2-f1ose — the `--allow-sensitive-reads` boot gate forces
        ;; `:elision true` + `:include-sensitive false` when OFF (the
        ;; default), mirroring snapshot / get-path. `incl-sensitive?`
        ;; threads into the walker's `:rf.size/include-sensitive?` opt;
        ;; `elision?` decides whether the server-side `:value` walk fires
        ;; at all (a verbatim pass-through is only reachable when the
        ;; operator opted in via `--allow-sensitive-reads` AND passed
        ;; `:elision false`).
        incl-sensitive? (if (raw-state/raw-state-allowed?)
                          (args/parse-bool-arg raw-args :include-sensitive)
                          false)
        elision?        (if (raw-state/raw-state-allowed?)
                          (args/parse-bool-arg raw-args :elision)
                          true)
        opts     (cond-> {}
                   frame      (assoc :frame frame)
                   incl-vals? (assoc :include-values? true))
        ;; Frame source for the elision walker's `:frame` opt — a quoted
        ;; keyword when pinned, else the runtime's `current-frame`
        ;; resolution (same shape get-path / the epoch tools use).
        frame-edn     (if frame
                        (pr-str frame)
                        (ef/emit (ef/rt-call 'current-frame)))
        elision-opts  (elision/elision-opts-edn (not elision?) incl-sensitive?)
        base-call     (ef/emit (ef/rt-call 'sub-cache-info opts))
        ;; rf2-f1ose — when values ride the wire AND the gate forces
        ;; elision, map each `:subs` entry's `:value` through
        ;; `elide-wire-value` server-side before the result ships. The
        ;; query-vectors-only shape (`:include-values false`) carries no
        ;; `:value` slots, so the wrap is skipped entirely there.
        form     (if (and incl-vals? elision?)
                   (ef/emit
                     (ef/rt-let
                       ['res (ef/rt-raw base-call)]
                       (ef/rt-raw
                         (str "(if (and (map? res) (vector? (:subs res)))"
                              "  (update res :subs (fn [ss] (mapv "
                              (elision/elide-sub-value-src frame-edn elision-opts)
                              " ss)))"
                              "  res)"))))
                   base-call)]
    (probe/eval-after-runtime!
      conn build-id form :list-subscriptions-failed
      (fn [v] (wire/ok-text (if (map? v) v {:ok? true :subs []}))))))
