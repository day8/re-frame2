(ns re-frame-pair2-mcp.tools.snapshot-pipeline
  "Post-eval snapshot transforms.

  Three concerns are sequenced here:

  1. Path-slicing / summarisation of each frame's `:app-db` slice
     (`slice-app-db-in-snapshot`). The `:path` arg (when supplied)
     wins over the slice-level mode; an empty path `[]` is
     semantically equivalent to `:full`.
  2. Diff-encoding (rf2-1wdzp) and structural dedup (rf2-obpa9) of
     each frame's `:epochs` slice (`diff-encode-epochs-in-snapshot`,
     `dedup-epochs-in-snapshot`).
  3. Lazy-summary default for non-app-db rich slices
     (`summarise-other-slices-in-snapshot`).

  ## Mode resolution (rf2-u2029)

  The resolved mode for each slice is governed by:

    1. `:modes` per-slice override (highest priority).
    2. Global `:mode` arg (`:summary` (default) or `:full`).
    3. For `:app-db` specifically: a non-nil `:path` arg forces
       `:path-sliced` regardless of mode (path-slicer already gives
       a bounded subtree). An empty path `[]` means \"explicit full\".

  `:app-db` is summarised inside `slice-app-db-in-snapshot`;
  `summarise-other-slices-in-snapshot` skips it to avoid double-work."
  (:require [re-frame-pair2-mcp.tools.dedup :as dedup]
            [re-frame-pair2-mcp.tools.summary :as summary]))

(def summarisable-slices
  "Slices for which a summary marker is a meaningful budget win.
  `:app-db` is omitted — `slice-app-db-in-snapshot` already handles it
  and respects the `:path` arg. The rest are vectors or maps that can
  grow unbounded with runtime state."
  #{:sub-cache :machines :epochs :traces})

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
    (let [status* (atom {})
          missing (js-obj)
          full?   (= :full app-db-mode)
          process-frame
          (fn [frame-id frame-map]
            (if-not (and (map? frame-map) (contains? frame-map :app-db))
              frame-map
              (let [db (:app-db frame-map)]
                (cond
                  ;; No path + summary mode (rf2-tygdv default): summarise.
                  (and (nil? path) (not full?))
                  (update frame-map :app-db summary/tree-summary)
                  ;; No path + full mode: full slice (rf2-u2029 opt-in).
                  (nil? path)
                  frame-map
                  ;; Root path (`[]`): return full db (agent opted in
                  ;; explicitly). Equivalent to legacy default behaviour.
                  (empty? path)
                  frame-map
                  ;; Path supplied: get-in with missing sentinel.
                  :else
                  (let [v (get-in db path missing)]
                    (if (identical? v missing)
                      (do (swap! status* assoc frame-id
                                 {:exists? false
                                  :deepest-valid-prefix
                                  (summary/deepest-valid-prefix db path)})
                          (assoc frame-map :app-db nil))
                      (assoc frame-map :app-db v)))))))
          processed (reduce-kv (fn [m fid fmap]
                                 (assoc m fid (process-frame fid fmap)))
                               {} snapshot)]
      [processed @status*])))

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

(defn diff-encode-epochs-in-snapshot
  "Walk the per-frame snapshot map and diff-encode every frame's
  `:epochs` slice (rf2-1wdzp). `mode :full` short-circuits — the
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
  (rf2-obpa9) to every frame's `:epochs` slice. Dedup is per-frame —
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
