(ns day8.re-frame2-causa.panels.machine-inspector-cluster-helpers
  "Pure-data helpers for the Machine Inspector UC2 Mode C cluster view
  (rf2-juon8, Phase 3, parent rf2-5aw5v).

  ## Why a separate `.cljc` ns

  Same dual-target pattern every other Causa panel helper uses. This
  ns owns the algebra; the CLJS view in
  `machine_inspector_cluster.cljs` is the thin renderer. JVM-runnable
  so `clojure -M:test` exercises the projections without a CLJS
  runtime.

  ## What this panel adds (per spec/003-Machine-Inspector.md §UC2 Mode C)

  When the live-instance population of a machine exceeds Mode B's
  sweet spot (~3 instances), Mode B's per-instance tab strip stops
  scaling. Mode C is the Erlang-Observer-style aggregate view:

    1. **Cluster rows** — instances grouped by some key (default
       `:state`; user can pick `:context-key` or `:parent-machine`).
       Each cluster row shows the cluster value + the count + a tiny
       sparkline of recent state-change rate.

    2. **Expand-on-click** — clicking a cluster row toggles an inline
       expansion that lists the individual instances within the
       cluster. The expansion is purely visual (a flag in the panel's
       app-db slot); the instance projection stays untouched.

    3. **Shift-click multi-select** — Shift+clicking instances within
       expanded clusters accumulates them into a selection-set; a
       compare-table renders below the cluster list when the set
       contains ≥2 instances, diffing context + current-state cell-
       by-cell so divergence is visible at a glance.

  ## Instance shape

  Each instance is a map:

      {:instance-id     <keyword-or-string>     ;; unique per machine
       :machine-id      <keyword>               ;; the parent machine
       :state           <keyword-or-vector>     ;; current state
       :context         <map>                   ;; user-defined :data
       :age-ms          <int-or-nil>            ;; ms since spawn
       :spawn-source    <event-vector-or-nil>   ;; the spawning event
       :parent-machine  <keyword-or-nil>}       ;; parent for hierarchy

  Phase 1/2's `snapshots` map (`{machine-id {:state ... :data ...}}`)
  is widened into a multi-instance vector via `snapshots->instances`;
  when the framework eventually ships `:rf.machine/spawn`, the
  composite sub in `machine_inspector.cljs` swaps the synthetic
  projection for the spawn-aware one without touching this ns.

  ## Cluster grouping

  `cluster-instances` takes the instance vector + a `:cluster-by`
  selector and returns a deterministic vector of cluster rows. Each
  cluster row is:

      {:cluster-key    <value>     ;; the grouping value (e.g. :authing)
       :cluster-label  <str>       ;; user-visible label
       :count          <int>       ;; instances in this cluster
       :instances      [<inst>...] ;; the cluster's members
       :rate-samples   [<int>...]} ;; sparkline data (last N buckets)

  ## Selection-set

  The selection-set is a sorted-set of `instance-id`s. The helpers
  here treat it as an immutable value; the view dispatches add /
  remove / toggle / clear events that the registry's reducer applies
  in the panel ns.

  ## Sparkline data

  Per design §3.5, sparklines capture **recent state-change rate**
  bucketed into fixed-width windows. `sparkline-buckets` takes the
  trace buffer + a cluster key + the cluster-by selector + a now-ms
  origin and returns the per-bucket transition count for the rendered
  glyph. Pure data; the SVG glyph generation lives in
  `chart/svg.cljc`."
  (:require [clojure.string :as str]))

;; ---- threshold + mode classification -----------------------------------

(def default-mode-c-threshold
  "When instance count exceeds this, Mode C is auto-selected. Per the
  bead's locked decisions the threshold is 10; user can force Mode C
  at any count via the mode tab strip."
  10)

(defn mode-c-suggested?
  "True iff the panel should auto-switch to Mode C. Pure predicate.

  `instance-count` is the total live instances for the selected
  machine. `threshold` defaults to `default-mode-c-threshold` (10)."
  ([instance-count] (mode-c-suggested? instance-count default-mode-c-threshold))
  ([instance-count threshold]
   (and (integer? instance-count)
        (> instance-count (or threshold default-mode-c-threshold)))))

;; ---- instance projection -----------------------------------------------

(defn snapshot->instance
  "Widen a Phase 1/2 snapshot (`{:state :data}`) into a Mode-C
  instance map. The `instance-id` defaults to the `machine-id` itself
  — there's only one snapshot per machine-id today, so the synthetic
  instance is the machine. When the spawn surface ships, the
  composite sub feeds richer instance vectors directly and this
  helper drops out of the hot path."
  [machine-id snapshot]
  (when (map? snapshot)
    {:instance-id    machine-id
     :machine-id     machine-id
     :state          (:state snapshot)
     :context        (or (:data snapshot) {})
     :age-ms         (:age-ms snapshot)
     :spawn-source   (:spawn-source snapshot)
     :parent-machine (:parent-machine snapshot)}))

(defn snapshots->instances
  "Project the snapshots map for a single machine-id into an instance
  vector. Returns `[]` when the snapshot is missing. The shape is the
  Mode-C-friendly representation; the composite sub feeds it through
  `cluster-instances`.

  Phase 3 ships the single-snapshot path. A follow-on bead will swap
  this for the multi-instance projection that consumes
  `:rf/machine-instances` (when spawn ships)."
  [machine-id snapshots]
  (if-let [inst (snapshot->instance machine-id (get snapshots machine-id))]
    [inst]
    []))

(defn normalise-instances
  "Normalise an instance vector to ensure every map carries the slots
  the cluster + compare helpers expect. Tolerates partial maps
  (test fixtures, future extensions). Pure fn."
  [instances]
  (->> (or instances [])
       (map (fn [inst]
              (cond-> {:instance-id    (or (:instance-id inst)
                                           (:machine-id inst))
                       :machine-id     (:machine-id inst)
                       :state          (:state inst)
                       :context        (or (:context inst) {})
                       :age-ms         (:age-ms inst)
                       :spawn-source   (:spawn-source inst)
                       :parent-machine (:parent-machine inst)})))
       vec))

;; ---- cluster grouping --------------------------------------------------

(def cluster-by-options
  "The valid `:cluster-by` selectors per design §3.3. Order matters
  for the UI selector — the view renders these in order."
  [:state :context-key :parent-machine])

(defn cluster-by-valid?
  [k]
  (or (= k :state)
      (= k :parent-machine)
      ;; `:context-key` is invoked with a key sub-selector — the panel
      ;; ns models the sub-selector as a separate slot. Either way the
      ;; predicate accepts the canonical id.
      (= k :context-key)))

(defn- group-key-of
  "Resolve the group key for `inst` under the given `cluster-by` +
  optional `context-key` sub-selector."
  [inst cluster-by context-key]
  (case cluster-by
    :state          (:state inst)
    :parent-machine (:parent-machine inst)
    :context-key    (get-in inst [:context context-key])
    ;; Unknown selector falls back to :state so the cluster view
    ;; always renders something rather than a blank.
    (:state inst)))

(defn- cluster-label-for
  "Render a cluster value as a display label. Keywords keep their
  `:`; nil becomes `(none)`; everything else falls back to `pr-str`."
  [v]
  (cond
    (nil? v)         "(none)"
    (keyword? v)     (str v)
    (string? v)      v
    :else            (try (pr-str v) (catch #?(:clj Throwable :cljs :default) _ (str v)))))

(defn- sort-cluster-keys
  "Deterministic sort for cluster keys. Numbers first, then strings,
  then keywords, then everything else by `pr-str`. Pure fn."
  [ks]
  (sort-by (fn [k]
             [(cond (nil? k)     4
                    (number? k)  0
                    (string? k)  1
                    (keyword? k) 2
                    :else        3)
              (try (pr-str k) (catch #?(:clj Throwable :cljs :default) _ (str k)))])
           ks))

(defn cluster-instances
  "Group `instances` by the given `cluster-by` selector. Returns a
  deterministic vector of cluster rows sorted by cluster-key.

  Options map:

    :cluster-by    — one of `cluster-by-options` (default `:state`)
    :context-key   — when `:cluster-by` is `:context-key`, the
                     sub-key in `(:context instance)` to group on.
                     Required for `:context-key`; ignored otherwise.

  Each row carries:

    :cluster-key    — the raw grouping value
    :cluster-label  — display label
    :count          — number of instances in this cluster
    :instances      — the cluster's member instances (input order)
    :rate-samples   — empty vector here; the panel composes
                      `sparkline-buckets` separately and merges
                      via `attach-sparkline-samples`."
  ([instances]
   (cluster-instances instances {}))
  ([instances {:keys [cluster-by context-key] :or {cluster-by :state}}]
   (let [norm    (normalise-instances instances)
         groups  (group-by #(group-key-of % cluster-by context-key) norm)
         ks      (sort-cluster-keys (keys groups))]
     (->> ks
          (mapv (fn [k]
                  (let [members (vec (get groups k))]
                    {:cluster-key   k
                     :cluster-label (cluster-label-for k)
                     :count         (count members)
                     :instances     members
                     :rate-samples  []})))))))

;; ---- sparkline (state-change rate) -------------------------------------

(def default-sparkline-buckets 12)
(def default-sparkline-bucket-ms 5000)

(defn- transition-event?
  "Local mirror of the machine-inspector-helpers predicate to avoid a
  cross-ns dep ordering edge case in CLJC tests."
  [ev]
  (and (map? ev)
       (or (= :rf.machine/transition (:operation ev))
           (= :rf.machine.microstep/transition (:operation ev)))))

(defn sparkline-buckets
  "Compute per-bucket transition counts for a cluster.

  Inputs:

    `trace-buffer` — Causa's ring buffer (nil-safe).
    `machine-id`   — the focused machine (filters the buffer).
    `cluster-pred` — a predicate `(fn [ev] boolean)` matched against
                     the **trace event**. Typically `(fn [ev] (= (-> ev :tags :to) cluster-key))`
                     for cluster-by-state, but the helper stays
                     generic.
    `now-ms`       — wall-clock origin; the buckets walk backwards
                     from here. Tests pass an explicit value for
                     determinism.
    `opts`         — `{:bucket-count <int> :bucket-ms <int>}` —
                     defaults from the `default-sparkline-*` Vars.

  Returns a vector of `bucket-count` ints, **oldest first** (left to
  right in the rendered sparkline). Counts the transition events
  whose `:time` falls in each bucket window.

  Pure fn — JVM-runnable."
  ([trace-buffer machine-id cluster-pred now-ms]
   (sparkline-buckets trace-buffer machine-id cluster-pred now-ms {}))
  ([trace-buffer machine-id cluster-pred now-ms
    {:keys [bucket-count bucket-ms]
     :or   {bucket-count default-sparkline-buckets
            bucket-ms    default-sparkline-bucket-ms}}]
   (let [buf      (or trace-buffer [])
         filtered (filter (fn [ev]
                            (and (transition-event? ev)
                                 (or (nil? machine-id)
                                     (= machine-id
                                        (or (get-in ev [:tags :machine-id])
                                            (get-in ev [:tags :handler-id]))))
                                 (cluster-pred ev)))
                          buf)
         oldest   (- now-ms (* bucket-count bucket-ms))
         init     (vec (repeat bucket-count 0))]
     (reduce
       (fn [acc ev]
         (let [t  (or (:time ev) 0)
               dt (- t oldest)]
           (if (and (>= dt 0)
                    (< t now-ms))
             (let [idx (quot dt bucket-ms)]
               (if (and (>= idx 0) (< idx bucket-count))
                 (update acc idx inc)
                 acc))
             acc)))
       init
       filtered))))

(defn cluster-pred-for
  "Build the per-cluster predicate that `sparkline-buckets` consumes.
  Pure factory — keeps the cluster-by → trace-event-match mapping in
  one place."
  [cluster-by cluster-key context-key]
  (case cluster-by
    :state          (fn [ev]
                      (= cluster-key
                         (or (get-in ev [:tags :to])
                             (get-in ev [:tags :to-state]))))
    :parent-machine (fn [ev]
                      (= cluster-key (get-in ev [:tags :parent-machine])))
    :context-key    (fn [ev]
                      (= cluster-key (get-in ev [:tags :context context-key])))
    (constantly false)))

(defn attach-sparkline-samples
  "Compose `cluster-instances` output with `sparkline-buckets` per
  cluster. Each cluster row gets `:rate-samples` populated. Pure fn —
  the trace buffer is the only dynamic input."
  [clusters {:keys [trace-buffer machine-id cluster-by context-key now-ms opts]
             :or   {opts {}}}]
  (mapv (fn [cluster]
          (let [pred (cluster-pred-for cluster-by
                                       (:cluster-key cluster)
                                       context-key)]
            (assoc cluster
              :rate-samples
              (sparkline-buckets trace-buffer machine-id pred
                                 (or now-ms 0) opts))))
        clusters))

;; ---- selection-set ------------------------------------------------------

(defn empty-selection
  "Canonical empty selection-set. Pure — returned as a set so the
  reducers below stay structural."
  []
  #{})

(defn selection-add
  "Add `instance-id` to `selection`. Idempotent (returns the same set
  when already present). Pure."
  [selection instance-id]
  (if (nil? instance-id)
    (or selection (empty-selection))
    (conj (or selection (empty-selection)) instance-id)))

(defn selection-remove
  "Drop `instance-id` from `selection`. Idempotent. Pure."
  [selection instance-id]
  (if (nil? instance-id)
    (or selection (empty-selection))
    (disj (or selection (empty-selection)) instance-id)))

(defn selection-toggle
  "Toggle `instance-id` in `selection` — add when absent, remove when
  present. Pure. The canonical UI primitive driven by shift-click."
  [selection instance-id]
  (let [sel (or selection (empty-selection))]
    (if (contains? sel instance-id)
      (disj sel instance-id)
      (conj sel instance-id))))

(defn selection-clear
  "Drop every entry from `selection`. Returns the canonical empty set."
  [_selection]
  (empty-selection))

(defn selection-contains?
  [selection instance-id]
  (boolean (and selection (contains? selection instance-id))))

(defn selection-count
  [selection]
  (count (or selection (empty-selection))))

;; ---- compare-table projection ------------------------------------------

(defn- merge-context-keys
  "Union of all keys present across the selected instances' `:context`
  maps. Sorted deterministically for stable table column order."
  [instances]
  (->> instances
       (mapcat #(keys (or (:context %) {})))
       distinct
       (sort-by str)
       vec))

(defn- value-set
  "All distinct values for one column across `instances`. Used to flag
  cells that differ from the rest of the row."
  [instances col-key]
  (->> instances
       (map #(get (or (:context %) {}) col-key))
       set))

(defn compare-table
  "Build the compare-table projection for the selected instances.
  Returns nil when the selection is empty or has only one instance
  (per design, the compare-table only renders when ≥2 instances are
  selected — one instance is just the instance itself).

  Inputs:

    `instances`  — the full instance vector (all instances for the
                   focused machine).
    `selection`  — the selection-set (set of instance-ids).

  Returns:

      {:instances    [<inst> ...]      ;; the selected instances, in
                                        ;; selection-order then id-order
       :columns      [<col-def> ...]   ;; the column metadata
       :rows         [<row> ...]       ;; one row per column with the
                                        ;; per-instance values + diff flag
       :state-row    {<col-def with
                       :diff? + values}}
                                       ;; the special :state row,
                                        ;; surfaced separately so the
                                        ;; view can pin it first}

  Each column-def is `{:key <ctx-key> :label <str>}`. Each row is
  `{:column <col-def> :values [<v> ...] :diff? <bool>}`. The `:diff?`
  flag is true when not every value is equal — those cells get the
  divergence highlight."
  [instances selection]
  (let [norm        (normalise-instances instances)
        sel-inst    (->> norm
                         (filter #(selection-contains? selection (:instance-id %)))
                         vec)]
    (when (>= (count sel-inst) 2)
      (let [ctx-keys  (merge-context-keys sel-inst)
            cols      (mapv (fn [k] {:key k :label (str k)}) ctx-keys)
            state-vs  (mapv :state sel-inst)
            state-row {:column {:key :state :label ":state"}
                       :values state-vs
                       :diff?  (> (count (set state-vs)) 1)}
            rows      (mapv
                        (fn [col]
                          (let [vs (mapv #(get (or (:context %) {})
                                               (:key col))
                                          sel-inst)]
                            {:column col
                             :values vs
                             :diff?  (> (count (set vs)) 1)}))
                        cols)]
        {:instances sel-inst
         :columns   cols
         :rows      rows
         :state-row state-row}))))

;; ---- expand state-set --------------------------------------------------
;;
;; The "which clusters are expanded inline" set is per-machine; the
;; helpers below treat it as an immutable value. Reducers live here so
;; the event handlers in the panel ns stay one-liners.

(defn empty-expanded
  []
  #{})

(defn expanded-toggle
  "Toggle `cluster-key` in `expanded`. Pure."
  [expanded cluster-key]
  (let [s (or expanded (empty-expanded))]
    (if (contains? s cluster-key)
      (disj s cluster-key)
      (conj s cluster-key))))

(defn expanded-contains?
  [expanded cluster-key]
  (boolean (and expanded (contains? expanded cluster-key))))

(defn expanded-clear
  [_expanded]
  (empty-expanded))

;; ---- formatting helpers (view-consumed) --------------------------------

(defn format-instance-id
  [id]
  (cond
    (nil? id)     ""
    (keyword? id) (str id)
    :else         (str/trim (str id))))

(defn format-context-value
  "Compact one-line representation of a context cell. Strings stay
  quoted (via `pr-str`) so empty / whitespace values are visible."
  [v]
  (cond
    (nil? v)     "—"
    (string? v)  (pr-str v)
    (keyword? v) (str v)
    :else        (try (pr-str v)
                      (catch #?(:clj Throwable :cljs :default) _ (str v)))))
