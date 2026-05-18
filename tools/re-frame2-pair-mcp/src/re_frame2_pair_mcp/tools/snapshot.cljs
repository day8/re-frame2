(ns re-frame2-pair-mcp.tools.snapshot
  "Tool: snapshot — coarse-grained per-frame state read in one round-trip.

  Many investigate-X workflows chain 5-10 reads; each is a bencode
  round-trip plus Claude-think latency. This op composes the existing
  per-slice readers server-side and returns a per-frame map.

  Post-eval shrink pipeline lives in
  `re-frame2-pair-mcp.tools.wire-pipeline` (rf2-ae8ie). This tool body
  builds the eval form, awaits the runtime response, and routes the
  result through `run-wire-pipeline` with `:kind :snapshot-map`."
  (:require [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.dedup :as dedup]
            [re-frame2-pair-mcp.tools.elision :as elision]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]))

(defn snapshot-tool [conn raw-args]
  (let [build-id    (wire/arg-build raw-args)
        frames      (args/parse-frames-arg (wire/arg raw-args :frames))
        include     (args/parse-include-arg (wire/arg raw-args :include))
        ;; rf2-c2dtu — the `--allow-raw-state` boot gate forces both
        ;; `:include-sensitive` to false AND `:elision` to true when
        ;; OFF (the default). The per-call args are still parsed (so the
        ;; response envelope reports the effective post-gate value), but
        ;; the gate wins.
        incl?       (if (raw-state/force-redact?)
                      false
                      (args/parse-bool-arg raw-args :include-sensitive))
        path        (args/parse-path-arg (wire/arg raw-args :path))
        mode        (dedup/parse-epochs-mode (wire/arg raw-args :epochs-mode))
        ;; Global lazy-summary mode (rf2-u2029): `:summary` (default)
        ;; replaces every rich slice with a tree-summary marker;
        ;; `:full` ships the full payload. Per-slice override via
        ;; `:modes` map takes precedence over the global mode.
        slice-mode  (args/parse-mode-arg (wire/arg raw-args :mode))
        slice-modes (args/parse-modes-arg (wire/arg raw-args :modes))
        dedup?      (args/parse-bool-arg raw-args :dedup)
        elision?    (or (args/parse-bool-arg raw-args :elision)
                        (raw-state/force-elision?))
        opts        {:frames frames :include include}
        ;; Eval form composition (rf2-urjnc + rf2-e35a5 + rf2-vflrg).
        ;; The snapshot composer returns a per-frame map; we wrap each
        ;; frame's `:app-db` AND `:sub-cache` slices with
        ;; `re-frame.core/elide-wire-value` so large / sensitive slots
        ;; get the `:rf.size/large-elided` / `:rf/redacted` marker
        ;; server-side, before the EDN crosses the wire.
        ;;
        ;; The walker reads the `[:rf/elision]` registry from the live
        ;; app-db — it has to run app-side, where the registry is
        ;; reachable. When elision is disabled the eval form skips the
        ;; walk entirely (a value pass-through is cheaper than walking
        ;; with `:rf.size/include-large? true`).
        ;;
        ;; rf2-vflrg pins the Tool-Pair §`Direct-read privacy posture
        ;; for sub-cache and get-path` contract: BOTH the `:app-db` and
        ;; `:sub-cache` direct-read surfaces MUST honour
        ;; `:rf.size/include-sensitive?` (default false ⇒ sensitive
        ;; slots redact). The `include-sensitive` MCP arg threads into
        ;; the walker's opt of the same shape via
        ;; `elision-opts-edn`'s two-arity form. Off-box defaults apply.
        ;;
        ;; Per rf2-e35a5: the eval form now ALSO counts elision
        ;; markers server-side and returns `{:value <snap>
        ;; :elided-count N}`. The client-side wire-pipeline reads the
        ;; count from opts instead of re-walking the post-pipeline
        ;; payload — the walker is the only thing that inserts
        ;; markers, so it can hand the count back as a piggyback on
        ;; the same round-trip. Dedup never touches the `:app-db` /
        ;; `:sub-cache` slices (where elision fired) — it only
        ;; re-shapes `:epochs` — so the pre-dedup server count equals
        ;; the post-dedup client count.
        elision-opts-form (elision/elision-opts-edn elision? incl?)
        form     (if elision?
                   (ef/emit
                     (ef/rt-let
                       ['snap (ef/rt-call 'snapshot-state opts)
                        'walked (ef/rt-raw
                                  (str "(reduce-kv"
                                       " (fn [m fid fmap]"
                                       "   (if (map? fmap)"
                                       "     (let [opts (merge {:frame fid} " elision-opts-form ")"
                                       "           f    (fn [v] (re-frame.core/elide-wire-value v opts))"
                                       "           fmap (if (contains? fmap :app-db)"
                                       "                  (update fmap :app-db f) fmap)"
                                       "           fmap (if (contains? fmap :sub-cache)"
                                       "                  (update fmap :sub-cache f) fmap)]"
                                       "       (assoc m fid fmap))"
                                       "     (assoc m fid fmap)))"
                                       " {} snap)"))]
                       (ef/rt-raw
                         (str "{:value walked"
                              " :elided-count (count (filter #(and (map? %) (contains? % :rf.size/large-elided))"
                              "                              (tree-seq coll? seq walked)))}"))))
                   (ef/emit
                     (ef/rt-raw
                       (str "{:value "
                            (ef/emit (ef/rt-call 'snapshot-state opts))
                            " :elided-count 0}"))))]
    (-> (probe/ensure-runtime! conn build-id)
        (.then (fn [_] (raw-state/signal-runtime! conn build-id)))
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
        (.then (fn [resp]
                 ;; New eval-form shape (rf2-e35a5): `{:value <snap>
                 ;; :elided-count N}`. Defensively fall back to the
                 ;; bare-snap shape — a degraded runtime / stubbed
                 ;; eval might return the raw per-frame map.
                 ;; Recognised via the `:elided-count` marker key,
                 ;; not via `(map? resp)`, because the bare snapshot
                 ;; IS itself a map (`{<frame-id> {...}}`).
                 (let [new-shape?    (and (map? resp) (contains? resp :elided-count))
                       snap-value    (if new-shape? (:value resp) resp)
                       server-elided (when new-shape? (:elided-count resp))
                       {:keys [value indicators]}
                       (wp/run-wire-pipeline snap-value
                                             {:kind          :snapshot-map
                                              :incl?         incl?
                                              :mode          mode
                                              :dedup?        dedup?
                                              :path          path
                                              :slice-mode    slice-mode
                                              :slice-modes   slice-modes
                                              :server-elided server-elided})
                       {:keys [dropped elided path-status
                               resolved-modes app-db-mode]} indicators
                       response-mode (cond
                                       path                  :path-sliced
                                       (= :full app-db-mode) :full
                                       :else                 :summary)]
                   (wire/ok-text (wire/with-indicators
                                   (cond-> {:ok?         true
                                            :frames      (if (= :all frames) :all (vec frames))
                                            :include     include
                                            :mode        response-mode
                                            :slice-modes resolved-modes
                                            :epochs-mode mode
                                            :dedup       dedup?
                                            :elision     elision?
                                            :snapshot    value}
                                     path              (assoc :path path)
                                     (seq path-status) (assoc :path-not-found path-status))
                                   {:dropped dropped :elided elided})))))
        (.catch (fn [err] (probe/err->result :snapshot-failed err))))))
