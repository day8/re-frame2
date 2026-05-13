(ns re-frame-pair2-mcp.tools.snapshot
  "Tool: snapshot — coarse-grained per-frame state read in one round-trip.

  Many investigate-X workflows chain 5-10 reads; each is a bencode
  round-trip plus Claude-think latency. This op composes the existing
  per-slice readers server-side and returns a per-frame map.

  Post-eval shrink pipeline lives in
  `re-frame-pair2-mcp.tools.wire-pipeline` (rf2-ae8ie). This tool body
  builds the eval form, awaits the runtime response, and routes the
  result through `run-wire-pipeline` with `:kind :snapshot-map`."
  (:require [re-frame-pair2-mcp.nrepl :as nrepl]
            [re-frame-pair2-mcp.tools.eval-form :as ef]
            [re-frame-pair2-mcp.tools.wire :as wire]
            [re-frame-pair2-mcp.tools.wire-pipeline :as wp]
            [re-frame-pair2-mcp.tools.probe :as probe]
            [re-frame-pair2-mcp.tools.args :as args]
            [re-frame-pair2-mcp.tools.dedup :as dedup]
            [re-frame-pair2-mcp.tools.elision :as elision]))

(defn snapshot-tool [conn raw-args]
  (let [build-id    (wire/arg-build raw-args)
        frames      (args/parse-frames-arg (wire/arg raw-args :frames))
        include     (args/parse-include-arg (wire/arg raw-args :include))
        incl?       (args/parse-bool-arg raw-args :include-sensitive?)
        path        (args/parse-path-arg (wire/arg raw-args :path))
        mode        (dedup/parse-epochs-mode (wire/arg raw-args :epochs-mode))
        ;; Global lazy-summary mode (rf2-u2029): `:summary` (default)
        ;; replaces every rich slice with a tree-summary marker;
        ;; `:full` ships the full payload. Per-slice override via
        ;; `:modes` map takes precedence over the global mode.
        slice-mode  (args/parse-mode-arg (wire/arg raw-args :mode))
        slice-modes (args/parse-modes-arg (wire/arg raw-args :modes))
        dedup?      (args/parse-bool-arg raw-args :dedup)
        elision?    (args/parse-bool-arg raw-args :elision)
        opts        {:frames frames :include include}
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
                   (ef/emit
                     (ef/rt-let
                       ['snap (ef/rt-call 'snapshot-state opts)]
                       (ef/rt-raw
                         (str "(reduce-kv"
                              " (fn [m fid fmap]"
                              "   (assoc m fid"
                              "          (if (and (map? fmap) (contains? fmap :app-db))"
                              "            (update fmap :app-db"
                              "                    (fn [db] (re-frame.core/elide-wire-value db"
                              "                               (merge {:frame fid} "
                              elision-opts-form
                              "))))"
                              "            fmap)))"
                              " {} snap)"))))
                   (ef/emit (ef/rt-call 'snapshot-state opts)))]
    (-> (probe/ensure-runtime! conn build-id)
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
        (.then (fn [v]
                 (let [{:keys [value indicators]}
                       (wp/run-wire-pipeline v
                                             {:kind        :snapshot-map
                                              :incl?       incl?
                                              :mode        mode
                                              :dedup?      dedup?
                                              :path        path
                                              :slice-mode  slice-mode
                                              :slice-modes slice-modes})
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
