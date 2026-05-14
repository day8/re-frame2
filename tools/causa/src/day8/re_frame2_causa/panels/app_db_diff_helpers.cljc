(ns day8.re-frame2-causa.panels.app-db-diff-helpers
  "Pure-data helpers for Causa's App-DB Diff panel (Phase 5, rf2-jps1o).

  ## Why a separate `.cljc` ns

  The panel view in `app_db_diff.cljs` touches DOM event handlers
  (right-click affordances, pin buttons). The *logic* — the
  structural-sharing diff, the reserved-keys partitioning, the pin-
  store transitions, the 'Show me when this changed' walker — is
  pure data → data. Splitting that logic into `.cljc` so it runs
  under the JVM unit-test target (`clojure -M:test`) is required by
  the standing rule `feedback_jvm_interop_must_work.md`.

  ## Diff algorithm — `diff-paths`

  Per spec/004-App-DB-Diff.md §Changed-paths derivation the diff is
  **structural-sharing**:

    - **Map-pointer-equality at each level.** When two sub-maps are
      `identical?` the subtree is unchanged; skip.
    - **Recurse only where pointers differ.** O(changed paths), not
      O(db size).
    - **Emit a sorted vector of `{:op :path :before :after}`** triples
      where `:op` is `:added` / `:modified` / `:removed`.

  `identical?` is the same predicate `clojure.core` uses for pointer
  equality. On the JVM it's `==` over object references; in CLJS
  it's `===`. PersistentHashMap instances produced by `assoc-in`
  share structure with their predecessors — every untouched sub-map
  is `identical?` to the predecessor's sub-map at the same path.

  ## Reserved-keys partition — `partition-reserved`

  Per spec §Reserved-keys group the runtime owns five top-level keys
  (`:rf/machines`, `:rf/route`, `:rf/system-ids`,
  `:rf/pending-navigation`, `:rf/spawned`). The diff triples for
  those keys render in a separate `[runtime]` group; the rest render
  as slice mini-panels.

  ## Pin store — `pin-path` / `unpin-path` / `reorder-paths`

  The pin store is per-frame: `{frame-id [path-1 path-2 ...]}`. Order
  preserved by vector. Pins are paths (vectors of keys), not values —
  the live value derefs against the current target-frame on each
  recompute (per spec §Performance — O(pins) per epoch, not O(db)).

  ## 'Show me when this changed' — `epochs-touching-path`

  Walks the epoch-history, diffing each epoch's `:db-before` and
  `:db-after` and filtering to those that touched the focused path.
  Pure data → vector of hit maps. Per spec §'Show me when this
  changed'."
  (:require [clojure.set :as set]))

;; ---- reserved keys --------------------------------------------------------

(def reserved-app-db-keys
  "Per spec/Conventions.md §Reserved app-db keys the runtime owns six
  top-level slots in `app-db`. Diff triples whose path roots in one
  of these keys render in the `[runtime]` group rather than as a
  slice mini-panel.

  - `:rf/machines` — machine snapshots (per spec/005-StateMachines.md)
  - `:rf/system-ids` — system-id reverse index
  - `:rf/route` — current route slice (per spec/012-Routing.md)
  - `:rf/pending-navigation` — pending-nav slot
  - `:rf/spawned` — spawned-actor table
  - `:rf/elision` — wire-elision declaration registry (per spec/009-Instrumentation.md §Size elision)

  Lockstep contract: this set MUST match the rows of
  spec/Conventions.md §Reserved app-db keys exactly. The drift-detector
  test `reserved-app-db-keys-matches-conventions-md` in
  `app_db_diff_helpers_cljs_test.cljc` enforces the invariant."
  #{:rf/machines
    :rf/route
    :rf/system-ids
    :rf/pending-navigation
    :rf/spawned
    :rf/elision})

(defn reserved-path?
  "True when `path`'s root key is a reserved-app-db key. Pure data →
  bool."
  [path]
  (boolean (and (sequential? path)
                (seq path)
                (contains? reserved-app-db-keys (first path)))))

;; ---- diff algorithm -------------------------------------------------------

(defn- map-like?
  "True when `x` is a map. Recursion-friendly — only walks into nested
  maps; vectors / lists / sets are leaf values for diff purposes (per
  spec §Changed-paths derivation — the diff bottoms out at the first
  non-map level, so a slice's `before` / `after` is the whole nested
  value)."
  [x]
  (map? x))

(defn diff-paths
  "Diff two app-db values. Returns a vector of triples:

      [{:op :added    :path [:k1 :k2 ...] :before nil      :after v}
       {:op :modified :path [:k1 :k2 ...] :before v-old    :after v-new}
       {:op :removed  :path [:k1 :k2 ...] :before v        :after nil}
       ...]

  Triples are sorted by path-as-pr-str so the order is stable across
  re-renders (the spec doesn't dictate an order; lexical-by-path is
  the obvious choice and gives consistent test snapshots).

  ## Structural sharing

  When `before` and `after` are both maps and have `identical?` keys
  at level N, that subtree is skipped — pure pointer-equality short-
  circuit. This is the O(changed paths) guarantee per spec §Changed-
  paths derivation.

  ## Per-key diff classification

    - `(contains? after k)` and not `(contains? before k)` → `:added`
    - not `(contains? after k)` and `(contains? before k)` → `:removed`
    - both contain k:
      - `identical?` values → no diff (structural-sharing short-circuit)
      - both maps, not `identical?` → recurse with extended path
      - otherwise (one or both non-map; not identical) → `:modified`
        leaf at this path

  Non-map sub-trees (e.g., a vector slice that changed from `[a b]`
  to `[a b c]`) are emitted as a single `:modified` triple at the
  parent path — the slice mini-panel's `before` / `after` shows the
  whole nested value side-by-side.

  Pure data → data. JVM-runnable."
  ([before after]
   (-> (diff-paths before after [])
       (->> (sort-by (comp pr-str :path)))
       vec))
  ([before after path]
   (cond
     ;; Pointer-equal subtrees — no recursion (structural-sharing
     ;; short-circuit). Mirrors clojure.core's `=` short-circuit on
     ;; PersistentHashMap pointer-equality.
     (identical? before after)
     []

     ;; Both maps, not identical — recurse on the union of keys.
     (and (map-like? before) (map-like? after))
     (let [all-keys (set/union (set (keys before)) (set (keys after)))]
       (reduce
         (fn [acc k]
           (let [bv (get before k ::missing)
                 av (get after k ::missing)]
             (cond
               (and (= bv ::missing) (not= av ::missing))
               (conj acc {:op :added :path (conj path k) :before nil :after av})

               (and (not= bv ::missing) (= av ::missing))
               (conj acc {:op :removed :path (conj path k) :before bv :after nil})

               (identical? bv av)
               acc

               (and (map-like? bv) (map-like? av))
               (into acc (diff-paths bv av (conj path k)))

               :else
               (conj acc {:op :modified :path (conj path k)
                          :before bv :after av}))))
         []
         all-keys))

     ;; One side is missing (top-level non-map invocation: caller asks
     ;; for diff between nil and a map, or vice versa).
     (and (nil? before) (some? after))
     [{:op :added :path path :before nil :after after}]

     (and (some? before) (nil? after))
     [{:op :removed :path path :before before :after nil}]

     ;; Both non-map, not identical — :modified leaf.
     :else
     [{:op :modified :path path :before before :after after}])))

;; ---- reserved-keys partition --------------------------------------------

(defn partition-reserved
  "Split a vector of diff triples into two groups:

      {:reserved     [triples-whose-path-roots-in-reserved-key]
       :non-reserved [the-rest]}

  Pure data → data. Used by the view to render the changed-slice
  stack + the `[runtime]` group separately per spec §Reserved-keys
  group."
  [triples]
  (let [{:keys [reserved non-reserved]}
        (group-by (fn [t] (if (reserved-path? (:path t)) :reserved :non-reserved))
                  triples)]
    {:reserved     (vec reserved)
     :non-reserved (vec non-reserved)}))

(defn reserved-summary
  "Project the current `:rf/*` reserved-key slots of `db` into a sorted
  vector of `[key value]` pairs for the panel's `[runtime]` group.
  Drops keys absent from `db` so the group is sized by what's
  actually populated.

  Pure data → data."
  [db]
  (vec
    (for [k (sort reserved-app-db-keys)
          :when (contains? db k)]
      [k (get db k)])))

;; ---- pin store -----------------------------------------------------------

(defn slice-pins-for-frame
  "Return the slice-path pin vector for `frame-id` in `store`, or `[]`
  when none. The slice-pin store is `{frame-id [path-1 path-2 ...]}`;
  vector order is the user's drag-reorder order. Sibling helper
  `time-travel-helpers/epoch-pins-for-frame` returns the per-frame
  epoch-pin vector for the Time-Travel scrubber; both helpers
  destructure a different `store` shape so they aren't
  interchangeable."
  [store frame-id]
  (vec (get store frame-id [])))

(defn pin-path
  "Add `path` to the pin vector for `frame-id` in `store`. Duplicates
  are dropped (re-pinning an existing path is a no-op). Pure data →
  updated store.

  Refusing duplicates is the spec's implied contract — the pin list
  is a vector of paths, and the user's mental model is 'pin this once'
  not 'pin this every time I click'."
  [store frame-id path]
  (let [existing (vec (get store frame-id []))]
    (if (some #(= path %) existing)
      store
      (assoc store frame-id (conj existing path)))))

(defn unpin-path
  "Remove `path` from the pin vector for `frame-id`. Pure data →
  updated store. No-op when `path` is absent."
  [store frame-id path]
  (update store frame-id
          (fn [pins]
            (vec (remove #(= path %) (or pins []))))))

(defn reorder-paths
  "Replace the pin vector for `frame-id` with `new-order`. The caller
  is responsible for supplying a vector that's a permutation of the
  current pin vector — this fn is the thin write-through; the drag-
  reorder UI computes the permutation.

  Pure data → updated store."
  [store frame-id new-order]
  (assoc store frame-id (vec new-order)))

(defn live-pinned-slices
  "Project the pin vector for `frame-id` into a vector of
  `{:path :value}` maps where `:value` is the current value of the
  path in `db`. Used by the panel's Pinned-slices group.

  Pure data → data. JVM-runnable."
  [store frame-id db]
  (mapv (fn [path] {:path path :value (get-in db path)})
        (slice-pins-for-frame store frame-id)))

;; ---- 'Show me when this changed' walker ---------------------------------

(defn path-touched?
  "True when the diff between `db-before` and `db-after` produces a
  triple at `path` (or anywhere beneath `path`). Pure data → bool.

  Uses pointer-equality for the prefix walk so unchanged subtrees
  short-circuit without recursion. When we reach the end of `path`
  we compare leaves directly — a leaf change registers as 'touched'.

  This is the per-epoch test the 'Show me when this changed' walker
  applies across epoch-history."
  [db-before db-after path]
  (loop [bv db-before
         av db-after
         path path]
    (cond
      (identical? bv av) false
      (empty? path) (not (identical? bv av))
      :else
      (recur (when (map-like? bv) (get bv (first path)))
             (when (map-like? av) (get av (first path)))
             (rest path)))))

(defn- path-exists?
  "True when `path` resolves to an existing slot in `db` (the final
  key is `contains?`-true in its parent map). Pure data → bool.

  Distinguishes 'key absent' from 'key present with nil value' — the
  diff classifier needs that distinction to label `:removed` (key
  gone) vs `:modified` (key now nil)."
  [db path]
  (cond
    (empty? path) (some? db)
    (not (map-like? db)) false
    (not (contains? db (first path))) false
    :else (recur (get db (first path)) (rest path))))

(defn op-at-path
  "Classify the change at `path` between `db-before` and `db-after`.
  Returns one of `:added` / `:removed` / `:modified` / nil (when the
  path is untouched). Pure data → keyword or nil."
  [db-before db-after path]
  (when (path-touched? db-before db-after path)
    (let [had? (path-exists? db-before path)
          has? (path-exists? db-after  path)]
      (cond
        (and (not had?) has?) :added
        (and had? (not has?)) :removed
        :else                 :modified))))

(defn- event-of-epoch
  "Extract the dispatched event-vector off an epoch-record, for the
  hit-row label. Mirrors `time-travel-helpers/dispatch-id-from-epoch`
  in shape but returns the event vector, not the id."
  [epoch-record]
  (or (:trigger-event epoch-record)
      (some (fn [ev]
              (when (and (= :event (:op-type ev))
                         (= :event/dispatched (:operation ev)))
                (get-in ev [:tags :event])))
            (:trace-events epoch-record))))

(defn epochs-touching-path
  "Walk `history` and return a vector of hit maps for epochs that
  touched `path`:

      [{:epoch-id <id> :event <vec> :op :added|:removed|:modified
        :before  <prior-value-at-path>
        :after   <new-value-at-path>}
       ...]

  Newest-first order so the list reads as a reverse chronological
  audit. Pure data → data. Per spec §'Show me when this changed'.

  Used by the `:rf.causa/show-me-when-this-changed-result` sub."
  [history path]
  (vec
    (reverse
      (keep (fn [{:keys [epoch-id db-before db-after] :as record}]
              (when-let [op (op-at-path db-before db-after path)]
                {:epoch-id epoch-id
                 :event    (event-of-epoch record)
                 :op       op
                 :before   (get-in db-before path)
                 :after    (get-in db-after  path)}))
            history))))

;; ---- diff caching --------------------------------------------------------

(defn cache-key-of
  "Return the cache key for a diff between an epoch's `:db-before`
  and `:db-after`. Per spec §Performance §Diff caching the cache is
  keyed by `:epoch-id` and the same epoch never re-diffs.

  Pure-data helper so the sub layer can build a `{epoch-id triples}`
  lookup without duplicating the key shape."
  [epoch-record]
  (:epoch-id epoch-record))
