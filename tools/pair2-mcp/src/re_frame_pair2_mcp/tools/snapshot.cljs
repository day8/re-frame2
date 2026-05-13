(ns re-frame-pair2-mcp.tools.snapshot
  "Tool: snapshot — coarse-grained per-frame state read in one round-trip.

  Many investigate-X workflows chain 5-10 reads; each is a bencode
  round-trip plus Claude-think latency. This op composes the existing
  per-slice readers server-side and returns a per-frame map."
  (:require [re-frame.mcp-base.vocab :as base-vocab]
            [re-frame-pair2-mcp.nrepl :as nrepl]
            [re-frame-pair2-mcp.tools.wire :as wire]
            [re-frame-pair2-mcp.tools.probe :as probe]
            [re-frame-pair2-mcp.tools.args :as args]
            [re-frame-pair2-mcp.tools.dedup :as dedup]
            [re-frame-pair2-mcp.tools.elision :as elision]
            [re-frame-pair2-mcp.tools.sensitive :as sensitive]
            [re-frame-pair2-mcp.tools.snapshot-pipeline :as pipeline]))

(defn snapshot-tool [conn raw-args]
  (let [build-id   (wire/arg-build raw-args)
        frames     (args/parse-frames-arg (wire/arg raw-args :frames))
        include    (args/parse-include-arg (wire/arg raw-args :include))
        incl?      (sensitive/include-sensitive? raw-args)
        path       (args/parse-path-arg (wire/arg raw-args :path))
        mode       (dedup/parse-epochs-mode (wire/arg raw-args :epochs-mode))
        ;; Global lazy-summary mode (rf2-u2029): `:summary` (default)
        ;; replaces every rich slice with a tree-summary marker;
        ;; `:full` ships the full payload. Per-slice override via
        ;; `:modes` map takes precedence over the global mode.
        slice-mode  (args/parse-mode-arg (wire/arg raw-args :mode))
        slice-modes (args/parse-modes-arg (wire/arg raw-args :modes))
        dedup?     (dedup/parse-dedup-arg (wire/arg raw-args :dedup))
        elision?   (elision/parse-elision-arg (wire/arg raw-args :elision))
        opts       {:frames frames :include include}
        ;; Eval form composition (rf2-urjnc). The snapshot composer
        ;; returns a per-frame map; we wrap each frame's `:app-db`
        ;; slice with `re-frame.core/elide-wire-value` so large /
        ;; sensitive slots get the `:rf.size/large-elided` marker
        ;; server-side, before the EDN crosses the wire. The walker
        ;; reads the `[:rf/elision]` registry from the live app-db
        ;; — it has to run app-side, where the registry is reachable.
        ;; When elision is disabled the eval form skips the walk
        ;; entirely (a value pass-through is cheaper than walking
        ;; with `:rf.size/include-large? true`).
        elision-opts-form (elision/elision-opts-edn elision?)
        form     (if elision?
                   (str "(let [snap (re-frame-pair2.runtime/snapshot-state "
                        (pr-str opts) ")]"
                        "  (reduce-kv"
                        "    (fn [m fid fmap]"
                        "      (assoc m fid"
                        "             (if (and (map? fmap) (contains? fmap :app-db))"
                        "               (update fmap :app-db"
                        "                       (fn [db] (re-frame.core/elide-wire-value db"
                        "                                  (merge {:frame fid} "
                        elision-opts-form
                        "))))"
                        "               fmap)))"
                        "    {} snap))")
                   (str "(re-frame-pair2.runtime/snapshot-state "
                        (pr-str opts) ")"))]
    (-> (probe/ensure-runtime! conn build-id)
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
        (.then (fn [v]
                 (let [app-db-mode (pipeline/resolve-slice-mode :app-db slice-modes slice-mode)
                       [scrubbed dropped]    (sensitive/scrub-snapshot-sensitive v incl?)
                       [sliced path-status]  (pipeline/slice-app-db-in-snapshot scrubbed path app-db-mode)
                       diff-encoded          (pipeline/diff-encode-epochs-in-snapshot sliced mode)
                       deduped               (pipeline/dedup-epochs-in-snapshot diff-encoded dedup?)
                       ;; Lazy-summary default for non-app-db rich
                       ;; slices (rf2-u2029). Runs LAST in the pipeline
                       ;; so summary `:bytes` hints reflect the
                       ;; post-shrink wire cost of each slice.
                       {summarised :snapshot
                        other-modes :resolved-modes} (pipeline/summarise-other-slices-in-snapshot
                                                       deduped slice-modes slice-mode)
                       resolved-modes (assoc other-modes :app-db
                                             (cond
                                               path :path-sliced
                                               :else app-db-mode))
                       response-mode  (cond
                                        path                  :path-sliced
                                        (= :full app-db-mode) :full
                                        :else                 :summary)
                       ;; :elided-large parity per Conventions §Cross-MCP
                       ;; indicator-field vocabulary (rf2-2499j). The
                       ;; snapshot eval form runs `re-frame.core/elide-
                       ;; wire-value` server-side over each frame's
                       ;; `:app-db` slice, substituting
                       ;; `:rf.size/large-elided` markers in place of
                       ;; declared / over-threshold leaves. Counting them
                       ;; AFTER the dedup pass (where the rich slices
                       ;; haven't yet been summarised) gives the agent
                       ;; the real elision footprint of the call; the
                       ;; later summary pass replaces non-app-db slices
                       ;; with bounded markers, but its own bytes are
                       ;; not elision-markers and so don't inflate the
                       ;; count. Omitted when zero per Conventions.
                       elided                (base-vocab/count-elided-markers deduped)]
                   (wire/ok-text (cond-> {:ok?            true
                                          :frames         (if (= :all frames) :all (vec frames))
                                          :include        include
                                          :mode           response-mode
                                          :slice-modes    resolved-modes
                                          :epochs-mode    mode
                                          :dedup          dedup?
                                          :elision        elision?
                                          :snapshot       summarised}
                                   path                  (assoc :path path)
                                   (seq path-status)     (assoc :path-not-found path-status)
                                   (pos? dropped)        (assoc :dropped-sensitive dropped)
                                   (pos? elided)         (assoc :elided-large elided))))))
        (.catch (fn [err] (probe/err->result :snapshot-failed err))))))
