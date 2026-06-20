(ns re-frame2-pair-mcp.tools.snapshot-pipeline
  "Post-eval snapshot transforms — specialised dispatchee for
  `:kind :snapshot-map`.

  Three concerns are sequenced here:

  1. Path-slicing / summarisation of each frame's `:app-db` slice
     (`slice-app-db-in-snapshot`). The `:path` arg (when supplied)
     wins over the slice-level mode; an empty path `[]` is
     semantically equivalent to `:full`.
  2. Diff-encoding and structural dedup of
     each frame's `:epochs` slice (`diff-encode-epochs-in-snapshot`,
     `dedup-epochs-in-snapshot`).
  3. Lazy-summary default for non-app-db rich slices
     (`summarise-other-slices-in-snapshot`).

  ## Mode resolution

  The resolved mode for each slice is governed by:

    1. `:modes` per-slice override (highest priority).
    2. Global `:mode` arg (`:summary` (default) or `:full`).
    3. For `:app-db` specifically: a non-nil `:path` arg forces
       `:path-sliced` regardless of mode (path-slicer already gives
       a bounded subtree). An empty path `[]` means \"explicit full\".

  `:app-db` is summarised inside `slice-app-db-in-snapshot`;
  `summarise-other-slices-in-snapshot` skips it to avoid double-work.

  ## Role: specialised dispatchee (called from the orchestrator)

  This ns is the `:kind :snapshot-map` arm of the wire pipeline. It
  is NOT a top-level entry point; readers arriving via a wire
  payload land first in `re-frame2-pair-mcp.tools.wire-pipeline`
  (the general orchestrator that dispatches on `:kind`), which
  delegates here for snapshot-map payloads.

  ## Cross-file split (deliberate factoring)

  Path-slicing + slice-mode resolution are markedly more elaborate
  than other payload kinds need — `:epoch-vector` and
  `:scalar-value` never touch app-db slices and never resolve a
  mode. Hoisting that machinery into a dedicated namespace keeps
  the orchestrator's cross-kind invariants (ordering, indicator
  shape, envelope contract) uncluttered by concerns only one kind
  cares about.

  This is a deliberate factoring choice, not accidental drift:
  see `re-frame2-pair-mcp.tools.wire-pipeline` for the orchestrator
  + the other payload kinds (`:epoch-vector`, `:scalar-value`)."
  (:require [re-frame2-pair-mcp.tools.dedup :as dedup]
            [re-frame2-pair-mcp.tools.summary :as summary]))

(def summarisable-slices
  "Slices for which a summary marker is a meaningful budget win.
  `:app-db` is omitted — `slice-app-db-in-snapshot` already handles it
  and respects the `:path` arg. The rest are vectors or maps that can
  grow unbounded with runtime state."
  #{:sub-cache :machines :epochs :traces})

(def ^:const full-epochs-cap
  "Default record cap on the `:epochs` slice when it resolves to `:full`
  mode. `:full` mode ships each epoch record VERBATIM —
  including its `:db-before` / `:db-after` app-db pair — so an
  unbounded 50-record history pr-strs to ~50× the app-db (a
  multi-100K-token overflow). Capping to the most-recent N
  records keeps full mode usable for the common 'what just happened?'
  read while the wire-cap stays a backstop for the rest. An agent that
  needs the full history pages via `trace-window` / `watch-epochs`
  (cursor-bounded) or `restore-epoch` (one record by id). 10 is the
  recent-history window a debugging read actually wants; more and the
  slice is a paging job, not a snapshot."
  10)

(defn resolve-slice-mode
  "Resolve the effective mode for a slice. `slice-modes` is the
  per-slice override map; `global-mode` is the snapshot-wide `:mode`
  arg. Falls back to `:summary` when nothing pins the slice. Always
  returns `:summary` or `:full`."
  [slice slice-modes global-mode]
  (let [m (or (get slice-modes slice) global-mode :summary)]
    (case m
      :full :full
      :summary)))

(defn slice-app-db-in-snapshot
  "Post-process the raw snapshot map: for each frame's `:app-db` slice,
  apply path-slicing (when `path` is present), summarisation (when
  `path` is nil and the resolved app-db mode is `:summary`), or pass
  the slice through (when the resolved mode is `:full`).

  Returns `[processed-snapshot per-frame-path-status]` where
  `per-frame-path-status` is `{<frame-id> {:exists? bool
                                            :deepest-valid-prefix [...]}}`
  populated only when `path` is supplied and at least one frame's
  path didn't resolve. Empty map when path is nil."
  [snapshot path app-db-mode]
  (if-not (map? snapshot)
    [snapshot {}]
    (let [missing (js-obj)
          full?   (= :full app-db-mode)
          ;; Pure two-output transform: returns `[new-frame-map
          ;; status-entry-or-nil]`. The status entry is non-nil only on
          ;; a path miss; the fold below threads it into the `:status`
          ;; map. No side-channel atom — both outputs flow through the
          ;; accumulator.
          process-frame
          (fn [frame-map]
            (if-not (and (map? frame-map) (contains? frame-map :app-db))
              [frame-map nil]
              (let [db (:app-db frame-map)]
                (cond
                  ;; No path + summary mode (the default): summarise.
                  (and (nil? path) (not full?))
                  [(update frame-map :app-db summary/tree-summary) nil]
                  ;; No path + full mode: full slice (opt-in).
                  (nil? path)
                  [frame-map nil]
                  ;; Root path (`[]`): return full db (agent opted in
                  ;; explicitly).
                  (empty? path)
                  [frame-map nil]
                  ;; Path supplied: get-in with missing sentinel.
                  :else
                  (let [v (get-in db path missing)]
                    (if (identical? v missing)
                      [(assoc frame-map :app-db nil)
                       {:exists? false
                        :deepest-valid-prefix
                        (summary/deepest-valid-prefix db path)}]
                      [(assoc frame-map :app-db v) nil]))))))
          {:keys [processed status]}
          (reduce-kv (fn [acc fid fmap]
                       (let [[new-fmap status-entry] (process-frame fmap)
                             acc' (assoc-in acc [:processed fid] new-fmap)]
                         (if status-entry
                           (assoc-in acc' [:status fid] status-entry)
                           acc')))
                     {:processed {} :status {}} snapshot)]
      [processed status])))

(defn summarise-other-slices-in-snapshot
  "Apply `tree-summary` to every frame's non-app-db slice whose
  resolved mode is `:summary`. `:full` slices pass through unchanged.
  Returns `{:snapshot processed :resolved-modes {<slice> :summary|:full}}`
  so the snapshot response can echo back which slices were summarised —
  agents pattern-match on the resolved-modes map without re-deriving
  the slice list."
  [snapshot slice-modes global-mode]
  (if-not (map? snapshot)
    {:snapshot snapshot :resolved-modes {}}
    (let [resolved (into {} (map (fn [s]
                                   [s (resolve-slice-mode s slice-modes global-mode)]))
                         summarisable-slices)
          process-frame
          (fn [frame-map]
            (if-not (map? frame-map)
              frame-map
              (reduce-kv
                (fn [m k v]
                  (assoc m k
                         (if (and (contains? summarisable-slices k)
                                  (= :summary (get resolved k))
                                  (some? v))
                           (summary/tree-summary v)
                           v)))
                {} frame-map)))
          processed (reduce-kv (fn [m fid fmap]
                                 (assoc m fid (process-frame fmap)))
                               {} snapshot)]
      {:snapshot processed :resolved-modes resolved})))

(defn cap-full-epochs-in-snapshot
  "Bound the `:epochs` slice to the most-recent `cap` records on every
  frame whose `:epochs` resolved to `:full` mode.

  Only fires in `:full` mode — in `:summary` mode the slice is already a
  one-line `{:rf.mcp/summary ...}` marker, and `:diff` epochs-mode has
  collapsed each `:db-after` to a small patch, so neither needs the cap.
  Full mode is the overflow path: each record carries a verbatim
  `:db-before` / `:db-after` app-db pair, so an unbounded history blows
  the wire budget.

  Keeps the LAST `cap` records (epoch-history is chronological,
  oldest→newest, so the tail is 'what just happened'). The `:epochs`
  slot stays a plain (truncated) vector — diff-encode and dedup
  downstream operate on it unchanged. When records are dropped, a
  SIBLING `:rf.mcp/epochs-capped {:shown <n> :total <m> :dropped <d>
  :kept :most-recent :hint ...}` key is added to the frame map so the
  agent sees the truncation explicitly (never silent) and the next-step
  (page older history via `watch-epochs` / `trace-window`). A slice
  already at/under the cap passes through untouched, no sibling key.

  Runs FIRST among the epochs transforms (before diff-encode + dedup) so
  those operate on the already-bounded set. `cap` is the record limit
  (`full-epochs-cap` by default)."
  [snapshot slice-modes global-mode cap]
  (if (or (not (map? snapshot))
          (not= :full (resolve-slice-mode :epochs slice-modes global-mode)))
    snapshot
    (reduce-kv
      (fn [m fid fmap]
        (assoc m fid
               (if-not (and (map? fmap)
                            (contains? fmap :epochs)
                            (sequential? (:epochs fmap)))
                 fmap
                 (let [epochs (:epochs fmap)
                       total  (count epochs)]
                   (if (<= total cap)
                     fmap
                     (let [kept (vec (take-last cap epochs))]
                       (assoc fmap
                              :epochs kept
                              :rf.mcp/epochs-capped
                              {:shown   (count kept)
                               :total   total
                               :dropped (- total (count kept))
                               :kept    :most-recent
                               :hint    (str "full-mode epoch-history capped to the most-recent "
                                             cap " records — page older history via "
                                             "watch-epochs / trace-window (cursor-bounded) or "
                                             "fetch one by id via restore-epoch")})))))))
      {} snapshot)))

(defn diff-encode-epochs-in-snapshot
  "Walk the per-frame snapshot map and diff-encode every frame's
  `:epochs` slice. `mode :full` short-circuits — the
  snapshot passes through unchanged. Other slices are untouched; only
  the `:epochs` slot of each frame map is rewritten."
  [snapshot mode]
  (cond
    (or (= mode :full) (not (map? snapshot)))
    snapshot
    :else
    (reduce-kv
      (fn [m fid fmap]
        (assoc m fid
               (if (and (map? fmap) (contains? fmap :epochs))
                 (update fmap :epochs dedup/diff-encode-epochs mode)
                 fmap)))
      {} snapshot)))

(defn dedup-epochs-in-snapshot
  "Walk the per-frame snapshot map and apply structural dedup
  to every frame's `:epochs` slice. Dedup is per-frame —
  cross-frame share-pooling would require a single table spanning
  every frame's slice, which is a follow-on optimisation; per-frame
  is the safe default and matches the table-reset policy
  (per-call, not per-stream)."
  [snapshot enabled?]
  (cond
    (or (not enabled?) (not (map? snapshot)))
    snapshot
    :else
    (reduce-kv
      (fn [m fid fmap]
        (assoc m fid
               (if (and (map? fmap) (contains? fmap :epochs))
                 (update fmap :epochs dedup/dedup-value enabled?)
                 fmap)))
      {} snapshot)))
