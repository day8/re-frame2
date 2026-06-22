(ns day8.re-frame2-xray.panels.app-db-diff-helpers
  "Pure-data helpers for Xray's App-DB Diff panel (Phase 5, rf2-jps1o).

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

  EP-0001 (rf2-vzld77 / rf2-tj6w9l): the runtime subsystems (machines /
  routing / elision) moved out of app-db's `:rf/runtime` into a SEPARATE
  runtime-db partition (`:rf.runtime/*`). The `[runtime]` group is built
  from that partition via the `runtime-areas` table + `reserved-summary`;
  `partition-reserved` / `reserved-path?` now key on the reserved `:rf*`
  NAMESPACE family (a framework-internal slot a host might stash at the
  app-db root) — a normal app-db diff triple is never reserved.

  ## rf2-e9tb0 — pin-store helpers dropped

  `pin-path` / `unpin-path` / `reorder-paths` / `slice-pins-for-frame`
  / `live-pinned-slices` and their `pinned-slices-store` slot were
  removed when path-segment click-to-inspect replaced the pinned-
  watches strip (Mike 2026-05-19 Q13). The matching subs / events
  were pulled in lockstep from `app_db_diff_subs.cljs` and
  `app_db_diff_events.cljs`.

  ## 'Show me when this changed' — `epochs-touching-path`

  Walks the epoch-history, diffing each epoch's `:db-before` and
  `:db-after` and filtering to those that touched the focused path.
  Pure data → vector of hit maps. Per spec §'Show me when this
  changed'."
  (:require [clojure.string :as str]))

;; ---- reserved keys --------------------------------------------------------

(defn reserved-namespace-key?
  "True when `k` is a qualified keyword in the reserved `:rf/*` or
  `:rf.<subns>/*` namespace family per spec/Conventions.md §Reserved
  namespaces. Catches any framework-internal key the runtime / framework
  stashes at the app-db root (e.g. a transient `:rf.machine/*` slot). The
  user-domain TOP section must hide all of these — they're framework
  plumbing, not user-domain state. Pure data → bool."
  [k]
  (boolean
    (when (qualified-keyword? k)
      (let [ns (namespace k)]
        (or (= ns "rf")
            (str/starts-with? ns "rf."))))))

(def reserved-app-db-keys
  "EP-0001 (rf2-vzld77 / rf2-tj6w9l): the framework's durable subsystem
  state — machine snapshots, the route slice, the spawn registry, the
  elision registry — moved OUT of app-db's `:rf/runtime` container into a
  SEPARATE runtime-db partition (the reserved `:rf.runtime/*` roots). So
  app-db no longer carries a `:rf/runtime` slot, and a normal user-domain
  app-db roots NO key in the reserved `:rf*` namespace family. This set is
  kept EMPTY: the App-DB panel sources its reserved AREAS from the
  runtime-db partition (the `runtime-areas` table below + the
  `:rf.xray/target-frame-runtime-db` sub), not from app-db diff triples,
  so nothing in an app-db diff is runtime-owned.

  The broader reserved-NAMESPACE family (`reserved-namespace-key?` —
  `:rf/*` / `:rf.<subns>/*`) is still used by `user-domain-db` to hide any
  framework-internal slot a host might stash at the app-db root; that is a
  separate, namespace-level filter from this (now-empty) root-key set."
  #{})

(defn reserved-path?
  "True when `path`'s root key is in the reserved-NAMESPACE family
  (`:rf/*` / `:rf.<subns>/*`, per `reserved-namespace-key?`) — i.e. a
  framework-internal slot the user-domain TOP section must not surface as
  a slice mini-panel. EP-0001 (rf2-tj6w9l): the runtime subsystems
  (machines / routing / elision) no longer live in app-db, so a normal
  app-db diff triple is never reserved; this predicate now catches only a
  framework-internal `:rf*`-namespaced root a host might stash in app-db.
  Pure data → bool."
  [path]
  (boolean (and (sequential? path)
                (seq path)
                (reserved-namespace-key? (first path)))))

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

  Pure data → data. JVM-runnable.

  Performance note (rf2-etwtm / audit 2c): the final sort caches
  `(pr-str :path)` onto each triple under `::sort-key` before the
  comparator runs, so `pr-str` runs O(N) (once per triple) instead of
  O(N log N) (once per comparator invocation) — measurable on large
  diffs. The `::sort-key` slot is dissoc'd after sorting so callers
  see the original triple shape."
  ([before after]
   (let [triples       (diff-paths before after [])
         with-keys     (mapv (fn [t] (assoc t ::sort-key (pr-str (:path t))))
                             triples)
         sorted        (sort-by ::sort-key with-keys)]
     (mapv #(dissoc % ::sort-key) sorted)))
  ([before after path]
   (cond
     ;; Pointer-equal subtrees — no recursion (structural-sharing
     ;; short-circuit). Mirrors clojure.core's `=` short-circuit on
     ;; PersistentHashMap pointer-equality.
     (identical? before after)
     []

     ;; Both maps, not identical — walk the union of keys without
     ;; allocating two intermediate sets per recursion (rf2-etwtm /
     ;; audit 2b). Walk `(keys after)` first, then `(keys before)`
     ;; skipping any key already seen.
     (and (map-like? before) (map-like? after))
     (let [walk-key (fn [acc k seen?]
                      (let [bv (get before k ::missing)
                            av (get after k ::missing)]
                        (cond
                          (and (= bv ::missing) (not= av ::missing))
                          [(conj acc {:op :added :path (conj path k)
                                      :before nil :after av})
                           (conj seen? k)]

                          (and (not= bv ::missing) (= av ::missing))
                          [(conj acc {:op :removed :path (conj path k)
                                      :before bv :after nil})
                           (conj seen? k)]

                          (identical? bv av)
                          [acc (conj seen? k)]

                          (and (map-like? bv) (map-like? av))
                          [(into acc (diff-paths bv av (conj path k)))
                           (conj seen? k)]

                          :else
                          [(conj acc {:op :modified :path (conj path k)
                                      :before bv :after av})
                           (conj seen? k)])))
           [acc-1 seen-1] (reduce (fn [[acc seen] k]
                                    (walk-key acc k seen))
                                  [[] #{}]
                                  (keys after))]
       (first (reduce (fn [[acc seen] k]
                        (if (contains? seen k)
                          [acc seen]
                          (walk-key acc k seen)))
                      [acc-1 seen-1]
                      (keys before))))

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

(defn- triple-path
  "Project the `:path` off a diff row. Polymorphic — supports both the
  legacy map shape `{:op :path :before :after}` (still produced by
  `diff-paths` for the trace panel) AND the universal 4-tuple shape
  `[path before after op]` (produced by the migrated App-DB + HANDLER
  `:db` paths post-rf2-xuyac, derived from
  `day8.re-frame2-xray.diff.engine/project`'s `:flat-rows`). Pure data."
  [row]
  (cond
    (map? row)        (:path row)
    (sequential? row) (first row)
    :else             nil))

(defn partition-reserved
  "Split a vector of diff rows into two groups:

      {:reserved     [rows-whose-path-roots-in-reserved-key]
       :non-reserved [the-rest]}

  Accepts either the map shape (`{:op :path :before :after}`) or the
  4-tuple shape (`[path before after op]`) — `triple-path` projects the
  path uniformly. Pure data → data. Used by the view to render the
  changed-slice stack + the `[runtime]` group separately per spec
  §Reserved-keys group."
  [triples]
  (let [{:keys [reserved non-reserved]}
        (group-by (fn [t] (if (reserved-path? (triple-path t)) :reserved :non-reserved))
                  triples)]
    {:reserved     (vec reserved)
     :non-reserved (vec non-reserved)}))

;; ---- runtime subsystem area table ---------------------------------------
;;
;; EP-0001 (rf2-vzld77 / rf2-tj6w9l): the framework's durable subsystem
;; state — machine snapshots, the route slice, the spawn registry, the
;; elision registry — moved OUT of app-db's `:rf/runtime` container into a
;; SEPARATE runtime-db partition (`:rf.db/runtime` in the frame-state),
;; whose top-level keys are the reserved `:rf.runtime/*` namespace family
;; (`:rf.runtime/machines` / `:rf.runtime/routing` / `:rf.runtime/elision`).
;; The App-DB panel surfaces these as separate operator-facing sections;
;; this table maps the logical area-id (the operator-facing label) to the
;; sub-path UNDER THE RUNTIME-DB PARTITION VALUE that carries the area's
;; value — sourced from `:rf.xray/target-frame-runtime-db` (the live
;; partition) and each focused epoch's runtime-db pre/post-image (the
;; `:rf.db/runtime` projection of `:frame-state-before` / `-after`),
;; mirroring the Machines inspector + Routing tab which already read
;; runtime-db at these paths.

(def runtime-areas
  "Logical area-id → sub-path under the RUNTIME-DB partition value. The
  area-ids are the operator-facing labels carried in the panel's section
  model (stable across the EP-0001 migration so caller / test code that
  uses `:area :rf/machines` etc. still reads as before). The paths point
  into the runtime-db partition's reserved `:rf.runtime/*` roots (per
  spec/Conventions.md §Reserved runtime-db keys + spec/002-Frames.md §The
  two-partition frame contract; EP-0001 rf2-vzld77)."
  {:rf/machines           [:rf.runtime/machines :snapshots]
   :rf/spawned            [:rf.runtime/machines :spawned]
   :rf/route              [:rf.runtime/routing :current]
   :rf/system-ids         [:rf.runtime/machines :system-ids]
   :rf/pending-navigation [:rf.runtime/routing :pending-navigation]
   :rf/elision            [:rf.runtime/elision]})

(defn reserved-summary
  "Project the current runtime subsystem slots out of the RUNTIME-DB
  partition value `runtime-db` into a sorted vector of `[area-id value]`
  pairs for the panel's `[runtime]` group. Drops areas with no live value
  so the group is sized by what's actually populated.

  Logical area-ids (`:rf/machines`, `:rf/route`, …) are the operator-
  facing labels per the `runtime-areas` table; the values are read from
  the `[:rf.runtime/...]` sub-paths (EP-0001 rf2-vzld77 — runtime-db
  partition, no longer app-db `:rf/runtime`).

  Pure data → data."
  [runtime-db]
  (vec
    (for [area-id (sort (keys runtime-areas))
          :let    [v (get-in runtime-db (get runtime-areas area-id))]
          :when   (some? v)]
      [area-id v])))

;; ---- current-state sectioning (rf2-okvit) -------------------------------
;;
;; The app-db tab is a CURRENT-STATE inspector (re-frame-10x style), not
;; a diff. Its layout splits the live `app-db` value into:
;;
;;   - a TOP section — the app-db MINUS every reserved `:rf/*` key (the
;;     user-domain app-db).
;;   - one section per reserved `:rf/*` area (per spec/Conventions.md
;;     §Reserved app-db keys).
;;
;; Each reserved area is one of two shapes:
;;
;;   - **map-of-instances** — a registry keyed by id whose values are
;;     per-instance structured state. These FAN OUT to one named
;;     sub-section per instance (section title = the instance id). The
;;     canonical example is `:rf/machines` (`{<machine-id> →
;;     :rf/machine-snapshot}`), so each machine renders under its own
;;     id (`:title/flow`, …) rather than piling into one combined
;;     "machines" blob. `:rf/spawned` (`{<parent-machine-id> → …}`) is
;;     the same shape and fans out per parent-machine id.
;;
;;   - **singleton slice** — one current-value slice rendered as a
;;     single section. `:rf/route` (the SINGULAR current-route slice
;;     `{:id :params :query :fragment :transition :error :nav-token}`,
;;     schema `:rf/route-slice`, spec/012 §The `:rf/route` slice),
;;     `:rf/system-ids`, `:rf/pending-navigation`, and `:rf/elision`
;;     are singletons.
;;
;; Empty / absent reserved areas are FILTERED at projection time
;; (rf2-jcdvo) — `current-state-sections` omits any area that is
;; absent / nil / present-but-empty. The operator sees only areas
;; that actually carry state; the panel is no longer cluttered with
;; six labelled "No machines registered." / "No active route." /
;; etc. placeholder cards. The TOP user-domain section ALWAYS renders
;; (it is the panel's anchor; an empty user-domain app-db is itself
;; meaningful operator information).

(def reserved-area-order
  "Render order for the reserved-area sections (after the user-domain
  TOP section). Machines + spawned (the map-of-instances registries)
  lead, then the singleton slices. Pure data."
  [:rf/machines
   :rf/spawned
   :rf/route
   :rf/system-ids
   :rf/pending-navigation
   :rf/elision])

(def map-of-instances-areas
  "Reserved areas whose value is a map-of-instances keyed by id — each
  fans out to one section per instance (section title = the instance
  id). `:rf/machines` (`{<machine-id> → snapshot}`) and `:rf/spawned`
  (`{<parent-machine-id> → …}`) are both id-keyed registries of
  structured per-id state. The remaining reserved areas are singleton
  slices rendered as one section. Pure data."
  #{:rf/machines :rf/spawned})

(defn user-domain-db
  "Return `db` with every reserved `:rf*`-namespaced key removed —
  the user-domain app-db that heads the inspector's TOP section. The
  filter catches any framework-internal slot the framework stashes at the
  app-db root under the reserved-namespace family (`:rf/*` /
  `:rf.<subns>/*`, e.g. a transient `:rf.machine/*` slot). EP-0001
  (rf2-tj6w9l) — the runtime subsystems no longer live in app-db (they
  moved to the runtime-db partition), so in practice a normal app-db has
  no reserved key to strip; the filter remains for any host that does
  stash one. Pure data → map. nil-safe (nil db → empty map)."
  [db]
  (into {} (remove (fn [[k _v]] (reserved-namespace-key? k))) (or db {})))

(def no-diff
  "Sentinel `:before` value meaning 'no pre-image available, render
  current-state (no `← changed` annotation)'. Distinct from a real
  `nil` before-image (a slot that was genuinely absent / nil before the
  cascade) so the renderer can tell 'don't diff' apart from 'diff
  against nil'. Pure data."
  ::no-diff)

(def added
  "Sentinel `:before` value meaning 'diff mode IS on, but this whole
  section / instance / singleton slice is ABSENT in the focused epoch's
  pre-image — it just came into existence this epoch'. Distinct from
  `no-diff` (no pre-image at all → render plain) AND from a real prior
  value (a genuine before → annotate the change in place).

  rf2-227cz: an instance / singleton present in `:value` but absent in
  `:before` MUST read `:added` (the whole subtree lights up green),
  NOT plain current-state. Previously such a slot was tagged `no-diff`
  and rendered identically to an unchanged slice, so the one thing that
  should make each event visually distinct — the newly-created machine /
  spawn / route appearing — was invisible. The renderer translates this
  sentinel to the edn-inspector's `:added? true` first-run signal
  (rf2-kp7bw), which synthesises the prior side as
  `engine/missing-sentinel` and washes the whole subtree `:added`.

  Note: this is ONLY emitted in diff mode (a real pre-image is present
  for the focused epoch). The 1-arity / cold-boot path still uses
  `no-diff` everywhere — with no focused epoch there is no 'this epoch
  added it' claim to make. Pure data."
  ::added)

(defn- instances-of
  "Decompose a map-of-instances reserved-area value into an ordered
  vector of `{:id <instance-id> :value <per-instance-state>
  :before <prior-per-instance-state-or-no-diff>}` maps, sorted by
  `(pr-str id)` for stable render order. Returns `[]` when the value is
  absent / not a map / empty.

  `before-area` is the SAME reserved-area value from the cascade's
  `db-before` (or `no-diff` when no pre-image is threaded). Each
  instance's `:before` is the prior value at that instance id, so the
  renderer can carry the inline `← changed` annotation (spec/021 §4.3).
  When `before-area` is `no-diff` every instance is tagged `no-diff` —
  current-state only.

  rf2-227cz: in DIFF mode (`before-area` is NOT `no-diff`) an instance
  id present now but ABSENT in `before-area` is tagged the `added`
  sentinel, NOT `no-diff` — the freshly-created machine / spawn must
  light up `:added` (green) rather than render identically to an
  unchanged instance. Pure data → data."
  [area-value before-area]
  (if (and (map? area-value) (seq area-value))
    (->> area-value
         (map (fn [[id v]]
                {:id     id
                 :value  v
                 :before (if (= no-diff before-area)
                           no-diff
                           ;; Diff mode: a known prior snapshot diffs in
                           ;; place; an instance absent from `before-area`
                           ;; is `:added` (rf2-227cz), not `no-diff`.
                           (get before-area id added))}))
         (sort-by (comp pr-str :id))
         vec)
    []))

(defn current-state-sections
  "Decompose the frame's TWO partitions — the `app-db` value + the
  `runtime-db` partition value — into the app-db tab's current-state
  section model (rf2-okvit; EP-0001 rf2-vzld77 / rf2-tj6w9l):

      {:top   <app-db-minus-reserved-keys>      ;; user-domain app-db
       :areas [{:area  :rf/machines
                :kind  :instances
                :empty? false
                :instances [{:id :title/flow :value {…}} …]}
               {:area  :rf/route
                :kind  :singleton
                :empty? false
                :value {…}}
               …]}

  The TOP user-domain section is the `app-db` value minus the reserved
  `:rf*`-namespaced keys; the reserved AREAS are read from the SEPARATE
  `runtime-db` partition value at the `[:rf.runtime/...]` `runtime-areas`
  paths (EP-0001 — the framework's durable subsystem state moved out of
  app-db's `:rf/runtime` into the runtime-db partition; this panel sources
  it the same way the Machines inspector + Routing tab do).

  One area entry per POPULATED reserved subsystem (in
  `reserved-area-order`). Map-of-instances areas
  (`map-of-instances-areas`) carry `:kind :instances` + an `:instances`
  vector (one entry per id); every other reserved area is
  `:kind :singleton` + a `:value`.

  ## Empty-area filtering (rf2-jcdvo)

  Empty / absent reserved areas are OMITTED from `:areas` entirely. An
  area is empty when:

    - the reserved key is absent from the db, OR
    - the value is `nil`, OR
    - the value is a present-but-empty collection (`{}` pending-nav,
      `{}` registry, `#{}` system-ids), OR
    - (for `:rf/machines` / `:rf/spawned`) the registry contains no
      instance ids.

  The renderer is the only consumer that needs the `:empty?` flag, and
  it never draws an empty section now (the placeholder cards added
  visual noise — six labelled 'No X' cards mostly saying 'nothing
  here'). Populated areas still carry `:empty? false` for callers that
  inspect the model shape; the slot is preserved for symmetry.

  The TOP user-domain section is NOT filtered — it always appears in
  the renderer's output, even when the user-domain app-db is empty
  (it's the panel's anchor; an empty user-domain app-db is itself
  meaningful operator information).

  ## Inline diff (spec/021 §4.3, rf2-ad7zx.11)

  Every section ALSO carries a `:before` slot — the SAME slice from the
  cascade's `db-before` (the TOP user-domain section, each instance, each
  singleton). The renderer threads `:before` + `:value` into the shared
  §10 diff renderer so changed nodes carry the inline `← was X`
  annotation in place (ancestor chain force-expanded). When no pre-image
  is threaded — the 1-arity form, or LIVE-at-boot with no prior epoch —
  `:before` is the `no-diff` sentinel and the renderer falls back to the
  plain current-state inspector (no annotation). A `:before` that equals
  `:value` is a real (non-diff) match — the renderer skips the
  annotation but stays on the diff engine, which renders identically to
  current-state for unchanged trees.

  rf2-227cz — a third `:before` value, the `added` sentinel, marks a
  whole instance / singleton slice that is present in `:value` but
  ABSENT in the focused epoch's pre-image (it came into existence this
  epoch). The renderer translates `added` to the edn-inspector's
  `:added? true` first-run signal so the whole subtree washes `:added`
  (green) rather than rendering as plain unchanged state. (The TOP
  user-domain section needs no sentinel — `:before-top` is the whole
  prior user-domain map, so a NEW user-domain key already classifies
  `:added` per-key inside the diff engine.)

  ## Arities (EP-0001 rf2-tj6w9l)

    (current-state-sections app-db runtime-db)
      — current state, no diff (`:before-top` + each `:before` are the
        `no-diff` sentinel).
    (current-state-sections app-db runtime-db
                            {:app <app-db-before> :runtime <runtime-db-before>})
      — diff mode: the TOP diffs against the app-db pre-image, each
        reserved area diffs against the runtime-db pre-image. Pass `nil`
        / `no-diff` for the before-image map to stay in no-diff mode.

  Pure data → data. JVM-runnable. nil-safe throughout (absent / empty
  partitions, absent reserved keys, empty registries, absent before-images)."
  ([app-db runtime-db] (current-state-sections app-db runtime-db no-diff))
  ([app-db runtime-db before]
   (let [;; Egress fail-closed (rf2-cra0nq): when the section model is fed a
         ;; value the local-render seam redacted WHOLE (an unreachable /
         ;; nil observed frame ⇒ the `:rf/redacted` sentinel, NOT a map),
         ;; there is no decomposable structure — treat it as the empty
         ;; partition rather than iterate the scalar sentinel (which would
         ;; throw). Mirrors the existing nil-safety; a whole-redacted value
         ;; carries no user-domain content to show.
         demap         (fn [v] (if (map? v) v {}))
         app-db        (demap (or app-db {}))
         runtime-db    (demap (or runtime-db {}))
         diff?         (and (some? before) (not= no-diff before))
         ;; A whole-redacted pre-image (rf2-cra0nq) is likewise non-map —
         ;; `demap` it so the diff-mode user-domain / runtime walks see an
         ;; empty pre-image (everything reads `:added`) rather than throw.
         app-before    (if diff? (demap (or (:app before) {})) no-diff)
         runtime-before (if diff? (demap (or (:runtime before) {})) no-diff)
         before-area   (fn [area-id]
                         (if diff?
                           (let [path (get runtime-areas area-id)
                                 v    (get-in runtime-before path ::absent)]
                             ;; rf2-227cz — in diff mode a singleton slice
                             ;; absent in the runtime-db pre-image is `:added`
                             ;; (the slot appeared this epoch — e.g. first
                             ;; navigation populating `:rf/route`), NOT
                             ;; `no-diff`. Only a slot genuinely present (incl.
                             ;; present-nil) diffs in place.
                             (if (= ::absent v) added v))
                           no-diff))]
     {:top   (user-domain-db app-db)
      :before-top (if diff?
                    (user-domain-db app-before)
                    no-diff)
      :areas (vec
               (for [area reserved-area-order
                     :let [path        (get runtime-areas area)
                           area-value  (get-in runtime-db path ::absent)
                           present?    (not= ::absent area-value)
                           area-value  (when present? area-value)
                           entry       (if (contains? map-of-instances-areas area)
                                         (let [instances (instances-of area-value
                                                                        (before-area area))]
                                           {:area      area
                                            :kind      :instances
                                            :empty?    (empty? instances)
                                            :instances instances})
                                         {:area   area
                                          :kind   :singleton
                                          ;; A singleton is empty when the key is absent, or
                                          ;; present-but-nil, or present-but-empty-collection
                                          ;; (e.g. `{}` pending-nav). Scalars / non-empty
                                          ;; collections are non-empty.
                                          :empty? (or (not present?)
                                                      (nil? area-value)
                                                      (and (coll? area-value)
                                                           (empty? area-value)))
                                          :value  area-value
                                          :before (before-area area)})]
                     ;; rf2-jcdvo — empty areas are omitted from :areas;
                     ;; the renderer never draws labelled "No X" placeholder
                     ;; cards. The TOP user-domain section (above) is the
                     ;; only always-rendered slot.
                     :when (not (:empty? entry))]
                 entry))})))

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
              (when (and (= :rf.event (:op-type ev))
                         (= :rf.event/dispatched (:operation ev)))
                (get-in ev [:tags :rf.event/v])))
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

  Used by the `:rf.xray/show-me-when-this-changed-result` sub."
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
