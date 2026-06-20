(ns re-frame2-pair-mcp.tools.list-subscriptions
  "Tool: list-subscriptions — list the LIVE reactive subscriptions
  materialised in a frame's per-frame sub-cache.

  ## What this answers

  \"What subscriptions are currently active in this frame?\" — the
  reactive sub-cache, the same source the `snapshot` tool's `:sub-cache`
  slice reads. Routes through the runtime's `sub-cache-info` fn, which
  reads `re-frame.subs.tooling/sub-cache-snapshot` (via the runtime's
  `sub-cache` fn) — the SAME accessor `snapshot :sub-cache` uses, so the
  two never disagree.

  ## Relationship to list-streams

  `list-subscriptions` routes through `sub-cache-info`, reporting the
  live reactive cache — the SAME source `snapshot :sub-cache` reads, so
  the two never disagree. The complementary streaming-tap diagnostic
  (the trace / epoch / fx / error queues opened by `subscribe`, read via
  `subscription-info`) lives in the accurately-named `list-streams`
  tool. The two are distinct concepts — reactive sub-cache vs streaming
  tap — each with its own tool.

  ## Disposal

  The reactive sub-cache is ref-counted and live: an entry appears the
  moment a view subscribes and DISAPPEARS when the last consumer
  disposes the reaction. So a sub that's been disposed (its view
  unmounted, no other subscribers) no longer shows up here.

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
                     carries `:value` (current deref), `:ref-count`,
                     `:input-kind`, and `:realized-inputs`.

  Returns `{:ok? true :frame <id> :count N :subs [<query-v> ...]}`
  (or, with `:include-values true`,
  `:subs [{:query-v <v> :value v :ref-count n
           :input-kind k :realized-inputs [...]} ...]`). Empty `:subs`
  vector when nothing is subscribed in the frame.

  ## Realized parametric input egress

  `:include-values true` also surfaces each live entry's `:input-kind`
  (`:db` / `:static` / `:parametric`) and `:realized-inputs` — the
  REALIZED input query-vectors for the concrete outer query-v (the
  literal `:<-` list for `:static`, the `(input-fn query-v)` result for
  `:parametric`, `[]` for layer-1). This is the runtime counterpart to
  the static `sub-topology` surface: the static topology reports
  `:inputs :parametric` for an `input-fn` sub (the edge set is not
  statically enumerable), and the live sub-cache here resolves the
  concrete realized edges per materialized entry. The realized inputs
  are QUERY-VECTORS (sub-id + args), not computed values, so they ride
  raw — only the per-sub `:value` slot is run through the egress
  redaction walker below.

  ## `:include-values` egress redaction

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
        ;; contract — `true` / `"true"` / `"yes"` / ... all
        ;; resolve; default false.
        incl-vals? (args/parse-bool-arg raw-args :include-values)
        ;; The `--allow-sensitive-reads` boot gate forces
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
        ;; Fail-CLOSED: walk UNLESS the caller opted into
        ;; both raw axes (`:elision false` AND `:include-sensitive true`).
        ;; A bare `:elision false` still walks so a declared-sensitive sub
        ;; `:value` redacts to `:rf/redacted` while large content passes.
        walk?         (elision/walk-required? (not elision?) incl-sensitive?)
        base-call     (ef/emit (ef/rt-call 'sub-cache-info opts))
        ;; When values ride the wire AND the walker is
        ;; required, map each `:subs` entry's `:value` through
        ;; `elide-wire-value` server-side before the result ships. The
        ;; query-vectors-only shape (`:include-values false`) carries no
        ;; `:value` slots, so the wrap is skipped entirely there.
        form     (if (and incl-vals? walk?)
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
