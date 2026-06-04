(ns re-frame.machines.parallel
  "Parallel-region machines. Per Spec 005 §Parallel regions (rf2-l67o /
  Stage 2).

  A machine declaring `:type :parallel` carries a `:regions` map of
  region-name → state-tree (each region a full state-node body with its
  own `:initial` + `:states` and optional `:on` / `:tags` / `:after` /
  `:spawn` / `:always` on each state node). All regions are active
  simultaneously when the machine is active; the snapshot's `:state`
  is a map of region-name → that region's keyword-or-vector-path;
  transitions are broadcast across regions; the macrostep drain settles
  every region before commit.

  Implementation strategy: each region is treated as a synthetic
  single-machine spec (`region-machine`) whose `:states` / `:initial`
  come from the region body, sharing `:guards` / `:actions` /
  `:on-spawn-actions` / `:rf/parent-id` / `:rf/platform` / `:rf/frame`
  with the parent. The standard `machine-transition-single` (in
  `re-frame.machines.transition`) is invoked against each region's
  slice; results are merged. Spawn / destroy / after-schedule /
  after-cancel fxs emitted by a region are post-processed to prefix
  the region name onto their `:rf/spawn-id` so per-region :spawn /
  :after slots scope correctly — one region's timer doesn't fire
  transitions in sibling regions.

  This namespace also exposes the public `machine-transition` dispatch
  (single vs parallel) so the transition engine doesn't need to know
  the parallel layer exists.

  `:raise` semantics (rf2-yi7ts / rf2-nr434 — XState v5 / SCXML gold
  standard). A flat / compound machine drains its own `:raise` queue FIFO
  inside `re-frame.machines.transition`. A PARALLEL machine owns the
  macrostep's single internal-event queue HERE: each region DEFERS its
  raises (its `machine-transition-single` leaves `:raise` fx un-drained —
  `transition/drain-or-defer-raises`), and `parallel-machine-transition`
  re-broadcasts each surfaced raise across EVERY region against the full
  evolving snapshot, FIFO, until the queue settles — then commits once.
  A region's `:raise` is therefore NOT region-local; it re-enters the
  parent macrostep, matching what a self-`[:dispatch [<self-id> …]]` would
  broadcast (pre-commit, in one macrostep)."
  (:require [clojure.set :as set]
            [re-frame.machines.result :as result
             #?@(:cljs [:include-macros true])]
            [re-frame.machines.transition :as transition]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

(defn parallel?
  "True iff the machine declares `:type :parallel` (root-level only)."
  [machine]
  (= :parallel (:type machine)))

(defn parallel-state-valid?
  "The ONE parallel-region snapshot-shape predicate (bz0ox.2 / x4s9t.2 —
  XState v5 / SCXML parity: a parallel state's configuration is EVERY region
  active simultaneously, never a subset). True iff `state` is:

   1. a map, AND
   2. carries EXACTLY the machine's declared region key set — no missing
      region (a partial snapshot like `{:left :done}` for a 2-region machine
      is malformed), and no extra/stale region (a hot-reload that dropped a
      region, or a corrupted restore), AND
   3. every region's active path resolves to a REAL, OCCUPIABLE leaf under
      that region's body — i.e. `transition/state-occupiable?` (non-nil node,
      not a `:type :history` pseudo-state).

  Reused by `all-regions-final?`, the snapshot-compatibility reconcile
  (`registration/state-resolves?`), and the defensive broadcast traversal
  (`reduce-regions`) so all three agree on what a live parallel configuration
  is. A snapshot that fails this resets through
  `:rf.error/machine-state-not-in-definition` rather than being driven —
  partially — through the engine."
  [machine state]
  (and (parallel? machine)
       (map? state)
       (let [declared (set (keys (:regions machine)))
             present  (set (keys state))]
         (and (= declared present)
              (every?
                (fn [[region-name region-state]]
                  (transition/state-occupiable?
                    (get-in machine [:regions region-name])
                    region-state))
                state)))))

(defn all-regions-final?
  "Per Spec 005 §Parallel regions + §Final states §The done-state signal
  (rf2-bnjb3): a parallel-region machine has reached its done configuration
  only when the snapshot is a VALID parallel configuration (every declared
  region present + occupiable — `parallel-state-valid?`) AND EVERY region's
  active leaf is `:final?`. Walk each region's body + active path and check
  the leaf node's `:final?` flag. Returns false when the snapshot is not a
  valid parallel configuration (missing/extra region, occupied-history leaf
  — bz0ox.2 / x4s9t.2), or when ANY present region's leaf isn't final. The
  validity gate means a partial map like `{:left :done}` for a 2-region
  machine can NEVER vacuously read as all-final and fire root `:on-done` /
  auto-destroy.

  Lives here (not in `lifecycle-fx.finalize`) so BOTH the parallel macrostep
  (`parallel-machine-transition`, which raises the parallel done-signal /
  fires the parallel root's `:on-done`) and the finalize cascade (whole-
  machine auto-destroy when no `:on-done` is declared) read one predicate —
  `finalize` requires `parallel`, never the reverse, so the home avoids the
  require cycle. XState v5 / SCXML §3.4: `done.state.<parallelId>` is raised
  only when all child regions are final."
  [machine state]
  (and (parallel-state-valid? machine state)
       (every?
         (fn [[region-name region-state]]
           (let [region-body (get-in machine [:regions region-name])
                 leaf-node   (transition/node-at region-body
                                                  (transition/state-path region-state))]
             (transition/final-state-node? leaf-node)))
         state)))

(defn- build-region-machine
  "Construct a synthetic single-machine spec for one region of
  `parent-machine`. See `region-machine` for the contract — this is the
  uncached compute path."
  [parent-machine region-name]
  (let [region-body (get-in parent-machine [:regions region-name])]
    (-> region-body
        (assoc :guards            (:guards parent-machine))
        (assoc :actions           (:actions parent-machine))
        (assoc :on-spawn-actions  (:on-spawn-actions parent-machine))
        (assoc :rf/parent-id      (:rf/parent-id parent-machine))
        (assoc :rf/platform       (:rf/platform parent-machine))
        (assoc :rf/frame          (:rf/frame parent-machine))
        (assoc :rf/region         region-name))))

(defn region-machine
  "Synthetic single-machine spec for a region of `parent-machine`.

  Inherits `:guards` / `:actions` / `:on-spawn-actions` from the parent so
  region transitions can reference the parent's named guards / actions
  without redeclaring them. Inherits `:rf/parent-id` / `:rf/platform` /
  `:rf/frame` so the post-action `:rf.machine/spawn` / `:after-schedule`
  fxs the region emits carry the parent's identity (the region name is
  prepended onto the `:rf/spawn-id` separately).

  Per round-2 P-r2-1 (rf2-s83iu): region-specs are registration-time
  data, not transition-time data. The result is memoised in metadata
  on `parent-machine` itself — re-registration replaces the entire
  machine map, so the old cache becomes garbage automatically and no
  invalidation logic is needed. Per-region misses fall through to
  `build-region-machine` and CAS the result into the metadata atom."
  [parent-machine region-name]
  (let [cache (-> parent-machine meta ::region-cache)]
    (if-let [hit (and cache (get @cache region-name))]
      hit
      (let [built (build-region-machine parent-machine region-name)]
        (when cache
          (swap! cache assoc region-name built))
        built))))

(defn install-region-cache
  "Attach an empty region-machine cache to `parent-machine`'s metadata.
  Called at registration time for `:type :parallel` machines so the
  hot-path `region-machine` lookups hit the cache instead of allocating
  a fresh map per call."
  [parent-machine]
  (if (parallel? parent-machine)
    (vary-meta parent-machine assoc ::region-cache (atom {}))
    parent-machine))

(defn- region-initial-state
  "Compute the initial-state value for one region — applying that region's
  `:initial` cascade through any compound chain. Returns the region's
  state value (keyword for flat regions, vector path for compound regions)."
  [region-body]
  (let [decl      (:initial region-body)
        ;; `region-body` already carries `:states` — `initial-cascade`
        ;; reads it through `node-at`, so pass it directly (the prior
        ;; `(assoc region-body :states (:states region-body))` re-assoc'd
        ;; `:states` with the value it already held — a no-op layer).
        full-path (transition/initial-cascade region-body (transition/state-path decl))]
    (transition/denormalise-state full-path decl)))

(defn- compute-tags-parallel
  "Per Spec 005 §Tags compose across regions: union every active state's
  `:tags` across every active region."
  [machine state-map]
  (transduce
    (map (fn [[region-name region-state]]
           (transition/compute-tags (region-machine machine region-name) region-state)))
    set/union
    #{}
    state-map))

(defn- commit-tags-parallel
  "Variant of `commit-tags` that dispatches on `(:type machine)`. For
  parallel-region machines, recomputes the union across every region.
  For flat/compound machines, defers to the standard `compute-tags`."
  [machine snapshot]
  (let [tags (if (parallel? machine)
               (compute-tags-parallel machine (:state snapshot))
               (transition/compute-tags machine (:state snapshot)))]
    ;; Per rf2 clarity dedup: the empty→dissoc / else→assoc elision lives
    ;; once in `transition/stamp-tags`; both tag-commit fns delegate to it.
    (transition/stamp-tags snapshot tags)))

(defn build-initial-snapshot
  "Build the freshly-derived initial snapshot for `machine` (rf2-fgqs4).
  The single source of truth used by both the singleton-registration path
  (`lifecycle-fx.registration/make-machine-handler`) and the spawn path
  (`lifecycle-fx.spawn/install-spawn!`). Steps:

   1. Compute `:state` — for parallel-region machines, a map of
      region-name → that region's cascaded initial; for flat / compound
      machines, the root `:initial` cascade denormalised to a leaf path.
      Per Spec 005 §Initial-state cascading and §Parallel regions.
   2. Seed `:data` — `(:data machine)` or `{}`.
   3. Seed `:rf/spawn-counter {}` — per rf2-gr8q the in-snapshot
      allocator MUST be present on live snapshots so
      `:entry`-declared `:spawn`s allocate ids through the contract path
      (not `allocate-spawned-id`'s defensive `(fnil inc 0)` backstop).
   4. Propagate `:meta` when the spec declares it — per Spec 005 §Snapshot
      shape, so the 3-arity ctx and downstream version checks see the
      same `:meta` the spec declares. Spawned actors that declare `:meta`
      MUST carry it through to the snapshot.
   5. Stamp the initial tag union via `commit-tags-parallel` — per Spec
      005 §State tags (rf2-ee0d) / §Tags compose across regions
      (rf2-l67o); the slot is elided when the union is empty.
   6. Optionally stamp `:rf/bootstrap-pending? true` (per rf2-0z73) when
      `bootstrap-pending?` is truthy — the spawn path needs this so the
      actor's first dispatch fires the initial-entry cascade. The
      singleton-registration path stamps the marker lazily inside
      `prepare-machine-ctx` instead (when `existing-snap` is nil), so it
      passes `bootstrap-pending? false` here."
  [machine {:keys [bootstrap-pending?]}]
  (let [initial-state (if (parallel? machine)
                        (into {}
                              (for [[rn region-body] (:regions machine)]
                                [rn (region-initial-state region-body)]))
                        (let [decl (:initial machine)]
                          (transition/denormalise-state
                            (transition/initial-cascade machine (transition/state-path decl))
                            decl)))
        base          (cond-> {:state            initial-state
                               :data             (or (:data machine) {})
                               :rf/spawn-counter {}}
                        (some? (:meta machine)) (assoc :meta (:meta machine)))
        tagged        (commit-tags-parallel machine base)]
    (cond-> tagged
      bootstrap-pending? (assoc :rf/bootstrap-pending? true))))

(defn- prefix-region-spawn-id
  "Per Spec 005 §Per-region `:spawn` / `:after` / `:always` scoping:
  spawn / destroy / after-schedule / after-cancel fxs emitted by a region
  carry an `:rf/spawn-id` that's the in-region prefix-path. To keep the
  runtime-owned `[:rf/runtime :machines :spawned <parent-id> <invoke-id>]` slot unique
  per-region (and per-region `:after` epoch tracking distinct from sibling
  regions), prepend the region name onto the `:rf/spawn-id`."
  [region-name fx]
  (let [[fx-id args] fx]
    (cond
      (and (map? args) (contains? args :rf/spawn-id))
      [fx-id (update args :rf/spawn-id #(vec (cons region-name %)))]

      :else fx)))

;; ---- initial-state entry cascade (rf2-0z73) -------------------------------
;;
;; Per Spec 005 §Initial cascading and §Entry/exit cascading along the
;; LCA, every state's `:entry` action fires when its state is entered.
;; The "initial state" is no exception — when a machine first comes into
;; existence (singleton on first dispatch, or spawned actor on
;; `:rf.machine/spawn`), the initial state's `:entry` actions fire as
;; part of bringing the machine to life. For a compound initial cascade,
;; EVERY state along that cascade fires its `:entry` shallowest-first.
;;
;; The cascade re-uses `apply-transition-once` by synthesising a
;; "transition from the empty path to the initial leaf" — ghost-snap
;; with `:state []` driving a synthetic transition whose `:target` is
;; the initial leaf path.

(defn- bootstrap-step
  "Single bootstrap step for one (flat or per-region) machine. Returns
  a `result/ok` Result carrying the post-cascade snapshot + fx, or a
  `result/fail` Result if any `:entry` action threw.

  Per rf2-82a0u: every initial-entry cascade action carries `:phase
  :initial-entry` on its `:rf.machine/action-ran` emit so the Handler
  section's LIFECYCLE rendering distinguishes the bootstrap entry
  burst from a later `:on`-driven entry-cascade."
  [machine initial-snapshot]
  (let [original-state (:state initial-snapshot)
        boot-target    (if (keyword? original-state)
                         original-state
                         (vec (transition/state-path original-state)))]
    (transition/apply-transition-once
      machine
      (assoc initial-snapshot :state [])
      [transition/start-marker]
      {:target    boot-target
       :decl-path []}
      :initial-entry)))

;; ---- parallel-region broadcast invariant (rf2-vqubp) ----------------------
;;
;; Per Spec 005 §Parallel regions, every reducer that broadcasts ONE
;; per-region computation across a parallel-region machine MUST:
;;   - iterate regions in declaration order (declaration order = the
;;     order in which `:regions` was authored; falls back to state-map
;;     key order when the spec has been mutated post-registration),
;;   - thread shared `:data` sequentially through regions so a later
;;     region's step sees earlier regions' writes,
;;   - thread the in-snapshot `:rf/spawn-counter` (rf2-gr8q) so any
;;     declarative `:spawn` fired in a region bumps the SAME shared
;;     counter,
;;   - run each region as a synthetic single-machine spec via
;;     `region-machine`,
;;   - prefix per-region fx with the region name via
;;     `prefix-region-spawn-id` so per-region `[:rf/runtime :machines :spawned ...]` /
;;     `:after`-epoch tracking slots stay distinct from siblings,
;;   - short-circuit to a `result/fail` if any region's step fails,
;;   - commit `:tags` via `commit-tags-parallel` AFTER every region has
;;     transitioned, since `:tags` is the union across active leaves.
;;
;; `reduce-regions` names this invariant once — every broadcast
;; reducer in the parallel layer delegates here.

(defn- reduce-regions
  "Apply `step-fn` to each region of `parent-machine` in declaration
  order, threading `:data` + `:rf/spawn-counter` between regions and
  prefixing per-region fx with the region name. `step-fn` receives the
  synthetic region-spec and the per-region snapshot
  (`{:state ... :data ... ?:rf/spawn-counter ...}`) and returns a
  `re-frame.machines.result/Result`.

  Returns a `result/ok` Result carrying the merged snapshot (post-
  `commit-tags-parallel`) + accumulated fx, or the first region's
  `result/fail` (cascade short-circuits).

  This helper assumes `parent-machine` is `:type :parallel`. Flat /
  compound callers run their step directly — the broadcast invariant
  doesn't apply."
  [parent-machine snapshot step-fn]
  (let [state-map   (:state snapshot)
        ;; Declaration-order iteration. Snapshot-shape validity (exactly the
        ;; declared region key set — `parallel-state-valid?`) is enforced
        ;; UPSTREAM at handler entry (`registration/state-resolves?` ->
        ;; `reconcile-snapshot`), so a live snapshot reaching here always
        ;; carries every declared region; the `contains?` filter is then a
        ;; no-op. It is retained as a defensive guard against a pure-fn caller
        ;; that synthesises a partial map directly — skipping an absent region
        ;; is safer than a `state-path nil` throw, and `all-regions-final?` now
        ;; independently rejects a partial map so a skipped region can never
        ;; vacuously read as done (bz0ox.2 / x4s9t.2).
        ordered     (filterv #(contains? state-map %)
                             (or (vec (keys (:regions parent-machine)))
                                 (vec (keys state-map))))]
    (loop [pending      ordered
           cur-data     (:data snapshot)
           cur-counter  (:rf/spawn-counter snapshot)
           ;; Per Spec 005 §Composition with parallel regions — per-region
           ;; history (rf2-mle6e.3): thread the shared `:rf/history` map
           ;; through regions like `:data`. The slot's keys are REGION-
           ;; QUALIFIED (head = region name), so per-region recordings never
           ;; collide — a later region only writes its own qualified keys
           ;; and never overwrites a sibling's. Seeded from the outer
           ;; snapshot (so a prior epoch's recordings survive) and merged
           ;; back below.
           cur-history  (:rf/history snapshot)
           new-states   state-map
           acc-fx       []
           any-handled? false
           micro-total  0
           cascade      []]
      (if (empty? pending)
        (let [merged (cond-> (-> snapshot
                                 (assoc :state new-states)
                                 (assoc :data  cur-data))
                       (some? cur-counter)
                       (assoc :rf/spawn-counter cur-counter)
                       (some? cur-history)
                       (assoc :rf/history cur-history))]
          ;; Per Spec 005 §Parallel regions (005:1168-1171): carry the
          ;; aggregate handled flag (true iff at least one region resolved
          ;; the event) so `parallel-machine-transition` warns exactly once
          ;; only when EVERY region declined. Per §Trace events the
          ;; macrostep `:microsteps` count is the sum across regions. Per
          ;; rf2-n9f4z the structured `::cascade` is the per-region step
          ;; sequences concatenated in declaration order — each step
          ;; already carries `:region` (stamped by the single-machine
          ;; engine from the synthetic region-spec's `:rf/region`), so the
          ;; flat concatenation stays per-region addressable for the
          ;; consumer (rf2-52u5n).
          (-> (result/ok (commit-tags-parallel parent-machine merged) acc-fx)
              (result/with-handled any-handled?)
              (result/with-microsteps micro-total)
              (result/with-cascade cascade)))
        (let [rn          (first pending)
              ;; Per rf2-r09fc: stamp the REAL parent machine-id onto the
              ;; synthetic region-spec so any declarative `:spawn` / `:after`
              ;; the region fires keys its
              ;; `[:rf/runtime :machines :spawned <parent> <invoke-id>]` slot
              ;; (and the child's `:data :rf/parent-id`) under the actual
              ;; spawning parent — NOT the `:rf/transition-pure` fallback
              ;; `run-spawn-phase` / `build-after-fx` apply when
              ;; `(:rf/parent-id machine)` is unset.
              ;;
              ;; `region-machine` memoises the region-spec at REGISTRATION
              ;; time (via `build-initial-snapshot` forcing the lazy
              ;; `base-initial`), BEFORE `prepare-machine-ctx` stamps
              ;; `:rf/parent-id` onto the live machine — so the cached
              ;; region-spec's `:rf/parent-id` is the registration-time nil.
              ;; The live `parent-machine` threaded into this broadcast DOES
              ;; carry the parent-id (`prepare-machine-ctx` stamps it = the
              ;; parent's own registration / spawned id), so we re-stamp it
              ;; here, at the single choke-point both transition callers
              ;; (`broadcast-once`, `run-initial-cascade`'s `bootstrap-step`)
              ;; funnel through. `(:id parent-machine)` is a defensive
              ;; fallback for pure-fn callers that synthesise a parent spec
              ;; with `:id` but no `:rf/parent-id`.
              parent-id   (or (:rf/parent-id parent-machine)
                              (:id parent-machine))
              region-spec (cond-> (region-machine parent-machine rn)
                            (some? parent-id) (assoc :rf/parent-id parent-id))
              region-snap (cond-> {:state (get state-map rn)
                                   :data  cur-data
                                   ;; Per rf2-46ly6 / rf2-69d1n: thread the
                                   ;; cross-region coordination context into
                                   ;; every region's guard/action ctx — the
                                   ;; XState v5 `stateIn` / SCXML `In()`
                                   ;; equivalent.
                                   ;;
                                   ;; `:all-state` is the full region-name →
                                   ;; active-state map (the PRECISE sibling-
                                   ;; state read); `:tags` is the MACHINE-WIDE
                                   ;; tag union across every region (the coarse
                                   ;; tag-as-stateIn substitute — rf2-69d1n).
                                   ;;
                                   ;; Both are computed from `new-states`, the
                                   ;; EVOLVING macrostep snapshot: regions
                                   ;; processed earlier in THIS broadcast
                                   ;; already carry their post-transition
                                   ;; state, so a sibling transition earlier in
                                   ;; the same broadcast is visible here; this
                                   ;; region (and later siblings) still carry
                                   ;; their current (pre-transition) state,
                                   ;; which is what a guard evaluated BEFORE the
                                   ;; transition fires must see. Across the FIFO
                                   ;; raise drain, every re-broadcast rebuilds
                                   ;; these from the latest `cur-snap` via a
                                   ;; fresh `reduce-regions`, so the union stays
                                   ;; current through the macrostep (consistent
                                   ;; with the rf2-yi7ts broadcast rework).
                                   ;;
                                   ;; `:all-state` doubles as the parallel-
                                   ;; region marker `call-guard`/`call-action`
                                   ;; key off (transition.cljc) — flat/compound
                                   ;; machines never set it, so their ctx is
                                   ;; unchanged.
                                   :all-state new-states
                                   :tags      (compute-tags-parallel
                                                parent-machine new-states)}
                            (some? cur-counter)
                            (assoc :rf/spawn-counter cur-counter)
                            ;; Seed the region snapshot with the shared
                            ;; (region-qualified) `:rf/history` so a restore
                            ;; reads the prior recording and a record writes
                            ;; the region's own key (rf2-mle6e.3).
                            (some? cur-history)
                            (assoc :rf/history cur-history))
              step-result (step-fn region-spec region-snap)]
          (if (result/fail? step-result)
            step-result
            (let [region-handled? (result/handled? step-result)
                  region-micro    (result/microsteps step-result)
                  region-cascade  (result/cascade step-result)]
              (result/with-ok [reg-snap reg-fx] step-result
                ;; Per rf2-3h1pf: accumulate fx via `into` so the region
                ;; loop doesn't rebuild the accumulator as a fresh vector
                ;; on every region step (the old shape was
                ;; `(vec (concat acc-fx prefixed-fx))` — O(N²·M) copying
                ;; for N regions × M fx; `into` uses a transient
                ;; internally — O(N·M) amortised). The prefix-fn is
                ;; folded into the transducer position so we don't
                ;; materialise the intermediate `prefixed-fx` vector.
                (recur (rest pending)
                       (:data reg-snap)
                       (:rf/spawn-counter reg-snap)
                       ;; Carry forward this region's `:rf/history` writes
                       ;; (region-qualified keys) so later regions + the
                       ;; merge see them (rf2-mle6e.3).
                       (:rf/history reg-snap)
                       (assoc new-states rn (:state reg-snap))
                       (into acc-fx
                             (map (partial prefix-region-spawn-id rn))
                             reg-fx)
                       (or any-handled? region-handled?)
                       (long (+ micro-total region-micro))
                       (into cascade region-cascade))))))))))

(defn- run-initial-cascade
  "Synthesise the bootstrap ENTRY cascade for `machine` against the
  freshly-synthesised `initial-snapshot` — the initial-descent `:entry`
  actions only. Returns a `result/ok` Result carrying the post-cascade
  snapshot + fx (+ `::cascade`), or a `result/fail` Result if any
  `:entry` action threw.

  For parallel-region machines (`:type :parallel`), delegates to
  `reduce-regions` so the per-region step sees the broadcast invariant
  (declaration-order iteration, threaded `:data` + `:rf/spawn-counter`,
  per-region fx-prefix, `commit-tags-parallel` on commit). For flat /
  compound machines, runs `bootstrap-step` directly — the broadcast
  invariant doesn't apply.

  Per rf2-505ic the `:always` fixed-point + raise drain that settles the
  initial macrostep is a SEPARATE phase — `apply-initial-entry-cascade`
  composes this entry cascade with `settle-birth`."
  [machine initial-snapshot]
  (if (parallel? machine)
    (reduce-regions machine initial-snapshot bootstrap-step)
    (bootstrap-step machine initial-snapshot)))

(declare machine-transition)

;; ---- parallel macrostep internal-event queue (rf2-yi7ts) ------------------
;;
;; XState v5 / SCXML gold standard: `raise` enqueues on the machine's ONE
;; internal event queue; the macrostep pops the front and broadcasts that
;; internal event to every active region, FIFO, until the queue drains —
;; then commits once. A parallel-region `:raise` is therefore NOT
;; region-local: a region that raises an event re-enters the PARENT
;; macrostep, which re-broadcasts the raised event across ALL sibling
;; regions against the FULL EVOLVING snapshot (so a sibling region's guard
;; sees the raise; the originating region also re-sees it).
;;
;; Mechanism: each per-region step DEFERS its raises (the region's
;; `machine-transition-single` leaves `:raise` fx un-drained — see
;; `transition/drain-or-defer-raises`). `parallel-machine-transition`
;; harvests those surfaced raises off the merged broadcast result, splits
;; them from the real (do-fx-bound) fx, and feeds them — FIFO — back through
;; another full broadcast. The loop terminates at the parent
;; `:raise-depth-limit` (default 16, same constant as the flat drain), and
;; on exceed rolls the WHOLE macrostep back atomically (original snapshot,
;; no fx) exactly like the single-machine drain.

(defn- split-raises
  "Partition a merged broadcast's `fx` into `[raised-events real-fx]`.
  `raised-events` is the vector of raised event-vectors (the `args` of each
  `[:raise <event-vec>]`), in broadcast order — region-declaration order
  within one broadcast, which is the order regions surfaced them. `real-fx`
  is every non-`:raise` fx entry, preserved in order, to flow on to `do-fx`.
  `:raise` entries are never region-prefixed (`prefix-region-spawn-id` only
  touches `:rf/spawn-id`), so they arrive here verbatim."
  [fx]
  (reduce (fn [[raises real] [fx-id args :as entry]]
            (if (= :raise fx-id)
              [(conj raises args) real]
              [raises (conj real entry)]))
          [[] []]
          fx))

(defn- broadcast-once
  "One full broadcast of `event` across every region of `machine` against
  `snapshot`, via the `reduce-regions` invariant. Regions DEFER their
  raises, so the returned Result's `fx` may carry surfaced `[:raise …]`
  entries for the parent loop to harvest. Returns the merged `result/ok`
  (carrying `::handled?` / `::microsteps` / `::cascade`) or the first
  region's `result/fail`."
  [machine snapshot event]
  (reduce-regions machine snapshot
                  (fn [region-spec region-snap]
                    (machine-transition region-spec region-snap event))))

(defn- drain-parent-queue
  "Drain the parent's single internal-event queue (region-surfaced
  `:raise`s, re-broadcast FIFO across all regions) to a fixed point,
  starting from the seed broadcast `first-r`, and return the merged
  Result. Shared by the parallel event MACROSTEP (`parallel-machine-
  transition`, seed = the external event's first broadcast) and the
  parallel BIRTH settle (`settle-birth`, seed = the post-initial-cascade
  per-region `:always` settle) — rf2-505ic factored this out so birth
  reuses the identical queue drain rather than duplicating it.

  `acc-fx` accumulates only the real (non-`:raise`) fx; `acc-micro` /
  `acc-cascade` roll up the totals across every (re-)broadcast.
  `external-handled?` reflects the SEED broadcast alone — re-broadcast
  internal events are continuation and never re-arm the all-regions-
  declined no-op (which keys off the inbound `event`).

  `event` gates the all-regions-declined benign no-op trace; pass `nil`
  on the birth path (a birth never declines an external event — there is
  none — so it must never emit the unhandled-no-op). `snapshot` is the
  atomic-rollback target on `:raise-depth-limit` abort (the input
  snapshot — for birth, the pre-settle post-cascade snapshot).

  Returns a `result/ok` (snapshot + fx, with `::handled?` / `::microsteps`
  / `::cascade`) or the first region's `result/fail`."
  [machine snapshot event first-r]
  (let [raise-limit (get machine :raise-depth-limit
                         transition/raise-depth-limit-default)]
    (if (result/fail? first-r)
      first-r
      (result/with-ok [snap0 fx0] first-r
        (let [[raises0 real0] (split-raises fx0)
              external-handled? (result/handled? first-r)]
          (loop [cur-snap   snap0
                 pending    (vec raises0)
                 acc-fx     real0
                 ;; `depth` counts internal events re-broadcast off the
                 ;; queue — symmetric with the flat drain's per-raise count
                 ;; and bounded by the SAME limit / `>=` boundary.
                 depth      0
                 acc-micro  (result/microsteps first-r)
                 acc-casc   (result/cascade first-r)]
            (cond
              (empty? pending)
              (let [result (-> (result/ok cur-snap acc-fx)
                               (result/with-handled external-handled?)
                               (result/with-microsteps acc-micro)
                               (result/with-cascade acc-casc))]
                ;; Per Spec 005 §Transition broadcast: when EVERY region
                ;; declines the EXTERNAL event the machine emits a SINGLE
                ;; benign `:rf.machine.event/unhandled-no-op` (op-type
                ;; `:rf.machine`, info-grade, NOT an error — xstate-v5
                ;; parity; canonical id / op-type / tags per Spec 009
                ;; §`:op-type` vocabulary). Gated on the inbound `event`
                ;; (not any re-broadcast raise) and on
                ;; `transition/unhandled-event-no-op?` so reserved-`:rf/*`
                ;; framework lifecycle traffic — the synthetic bootstrap,
                ;; the spawn kick-off, the stories-runtime pings — is NOT
                ;; classified as an unknown-user-event no-op (rf2-ugdas /
                ;; rf2-t4582). On the BIRTH path `event` is `nil`, so the
                ;; `unhandled-event-no-op?` gate is never reached — birth
                ;; never emits the no-op. Mirrors the flat-machine emission
                ;; in `transition/machine-transition-single`.
                (when (and (not external-handled?)
                           (some? event)
                           (transition/unhandled-event-no-op? event))
                  (trace/emit! :rf.machine :rf.machine.event/unhandled-no-op
                               {:machine-id (or (:rf/parent-id machine) (:id machine))
                                :event      event
                                :state      (:state snapshot)
                                :frame      (:rf/frame machine)}))
                result)

              ;; `>=` boundary parity with the flat drain (rf2-r26e2): the
              ;; loop re-broadcasts internal events at depths 0..limit-1
              ;; then aborts at `depth == limit`. Atomic rollback — the
              ;; WHOLE macrostep is discarded; no partial snapshot / fx
              ;; survives (Spec 005 §Drain semantics: bounded depth halts
              ;; with the snapshot uncommitted, `:no-recovery`).
              (>= depth raise-limit)
              (do (trace/emit-error! :rf.error/machine-raise-depth-exceeded
                                     {:machine-id (or (:rf/parent-id machine)
                                                      (:id machine))
                                      :depth      depth
                                      :frame      (:rf/frame machine)
                                      :recovery   :no-recovery})
                  (result/ok snapshot []))

              :else
              (let [ev   (first pending)
                    step (broadcast-once machine cur-snap ev)]
                (if (result/fail? step)
                  step
                  (result/with-ok [snap2 fx2] step
                    (let [[new-raises real-fx] (split-raises fx2)]
                      (recur snap2
                             ;; FIFO (rf2-nr434): drop the just-processed
                             ;; front, APPEND this broadcast's own raises to
                             ;; the BACK — behind the still-pending queue.
                             (into (vec (rest pending)) new-raises)
                             (into acc-fx real-fx)
                             (inc depth)
                             (+ acc-micro (result/microsteps step))
                             (into acc-casc (result/cascade step))))))))))))))

;; ---- parallel done-state / `:on-done` signal (rf2-bnjb3) ------------------
;;
;; Per Spec 005 §Final states §The done-state signal: when EVERY region of a
;; parallel machine reaches a `:final?` leaf, the parallel state is DONE —
;; XState v5 `onDone` / SCXML §3.4 `done.state.<parallelId>`. The author
;; declares `:on-done` ON THE PARALLEL ROOT (reading like `:spawn`'s
;; `:on-done`); the runtime fires it the moment all regions settle final,
;; WITHOUT tearing the machine down — the "do these axes in parallel, then
;; continue" pattern.
;;
;; Structural scope (substrate-honest). A `:type :parallel` machine is
;; ROOT-ONLY (no nested parallel; the root carries `:regions`, not `:states`),
;; so the parallel root has NO sibling flat state to land an in-machine
;; `:target` on. The parallel root's `:on-done` therefore runs its `:action`
;; (a `:data` write) + emits its `:fx` — the "then continue" is expressed as a
;; dispatch / raise in that fx to a coordinator (the re-frame2-idiomatic
;; effects-as-data continuation), NOT an in-machine `:target`. Registration
;; rejects a `:target` on a parallel root's `:on-done`
;; (`:rf.error/machine-parallel-on-done-target`). The machine stays in the
;; all-final configuration — the natural stable "complete" resting state.
;;
;; D7 reconciliation. A parallel root that declares NO `:on-done` keeps the
;; existing whole-machine finality (the lifecycle boundary's
;; `commit-or-finalize` recomputes `all-regions-final?` and routes to
;; `finalize-machine` — singleton auto-destroy, or the SPAWNING parent's
;; `:spawn :on-done`). The parallel root's OWN `:on-done` is the transitionable
;; signal; the spawning parent's `:spawn :on-done` is the actor-teardown
;; signal — distinct hooks, distinct purposes.

(defn fire-parallel-on-done
  "Per Spec 005 §Final states §The done-state signal (rf2-bnjb3): if the
  parallel `machine`'s settled `result` snapshot has NEWLY reached its
  all-regions-final done configuration this macrostep AND the parallel root
  declares `:on-done`, run that `:on-done` transition's `:action` against
  `:data` and append its `:fx`, marking the Result so the lifecycle boundary
  does NOT auto-destroy. Returns the (possibly enriched) Result; a
  `result/fail` (the `:on-done` action threw) short-circuits. A `result/fail`
  input passes through. When the machine is not parallel, was NOT newly made
  all-final this macrostep, or declares no `:on-done`, returns `result`
  unchanged (the whole-machine finalize path then runs at the lifecycle
  boundary).

  `newly-reached?` is the false→true EDGE guard (h3wca.1 — XState v5 `onDone`
  / SCXML `done.state.<parallelId>` fire EXACTLY ONCE, on ENTERING the done
  configuration, never re-firing on a later event delivered while resting
  there). The caller passes `true` only when this macrostep CROSSED into the
  all-final config — i.e. the after-snapshot is all-final AND the
  before-snapshot was NOT. On the BIRTH path it is always `true` (birth is the
  single macrostep that enters the initial configuration; a machine born
  all-final crosses the edge at birth). Without this guard the `:on-done`
  `:action` + `:fx` re-fired on every no-op event the regions decline while
  resting all-final — a coordinator's continuation re-dispatched repeatedly,
  any `:data` accumulation drifting upward."
  [machine result newly-reached?]
  (if (result/fail? result)
    result
    (let [on-done (:on-done machine)]
      (if (or (not (parallel? machine))
              (nil? on-done)
              (not newly-reached?)
              (not (all-regions-final? machine (:state (result/snap result)))))
        result
        (result/with-ok [snap fx] result
          ;; `:on-done` is an `:on`-shaped transition spec; a parallel root's
          ;; `:on-done` carries only `:action` / `:guard` / `:fx` (registration
          ;; rejects an in-machine `:target`). Run its `:action` against the
          ;; settled `:data` and append its fx, threading the standard
          ;; effects-map contract (`{:data .. :fx ..}`). A bare keyword /
          ;; map without `:action` is a no-op data-wise but still suppresses
          ;; auto-destroy (the author opted into "stay done, don't destroy").
          (let [on-done-r (transition/apply-on-done-action machine snap on-done)]
            (if (result/fail? on-done-r)
              on-done-r
              (result/with-ok [snap2 fx2] on-done-r
                (-> (result/ok snap2 (into (vec fx) fx2))
                    (result/with-parallel-done)
                    (result/with-handled (result/handled? result))
                    (result/with-microsteps (result/microsteps result))
                    (result/with-cascade (result/cascade result)))))))))))

(defn- parallel-machine-transition
  "Pure function. Given a parallel-region machine, current snapshot, and
  event, run the parallel MACROSTEP — broadcast the event to every region,
  then drain the parent's single internal-event queue (region-surfaced
  `:raise`s, re-broadcast FIFO across all regions) to a fixed point, and
  return the merged result. Returns a `result/ok` Result on success or a
  `result/fail` Result if any region's action threw.

  Per Spec 005 §Transition broadcast: each region resolves the event
  through its own active state's deepest-wins lookup; resolved regions
  transition, undeclined regions stay put. The `:data` slot is shared —
  each region's actions see the prior region's `:data` writes in
  declaration order. Per rf2-vqubp the broadcast invariant lives in
  `reduce-regions`.

  Per Spec 005 §Parallel-region `:raise` broadcast (rf2-yi7ts — XState v5
  parity): a `:raise` emitted by ANY region is NOT region-local. It enters
  this macrostep's internal-event queue and is re-broadcast across EVERY
  region against the full evolving snapshot — exactly what an equivalent
  self-`[:dispatch [<self-id> …]]` would broadcast, but pre-commit and
  inside the one macrostep. Raises are drained FIFO (rf2-nr434): a raise
  surfaced earlier is re-broadcast before one surfaced later, and a raise
  emitted *while handling* a re-broadcast goes to the BACK of the queue.
  The whole macrostep — external event + every re-broadcast internal event
  + every region's `:always` microsteps — commits ONCE, atomically.
  Bounded by the parent `:raise-depth-limit` (default 16); on exceed the
  macrostep rolls back wholesale (original snapshot, no fx) and emits
  `:rf.error/machine-raise-depth-exceeded`, matching the single-machine
  drain.

  For the synthetic `[:rf.machine.timer/after-elapsed ...]` event,
  delivery is region-scoped — the broadcast routes to the bearing region
  only, identified by the region-name prefix on the in-flight timer's
  `:rf/spawn-id`."
  [machine snapshot event]
  (let [first-r       (broadcast-once machine snapshot event)
        settled       (drain-parent-queue machine snapshot event first-r)
        ;; h3wca.1 — false→true EDGE guard. `:on-done` fires ONCE, on the
        ;; macrostep that CROSSES into the all-regions-final done config
        ;; (XState v5 `onDone` / SCXML `done.state.<id>`), NOT on every later
        ;; event delivered while resting there. A no-op event to an already-
        ;; all-final machine has before == all-final, so the edge is false and
        ;; `:on-done` does not re-fire.
        was-final?    (all-regions-final? machine (:state snapshot))
        newly-final?  (and (not was-final?)
                           (not (result/fail? settled))
                           (all-regions-final? machine (:state (result/snap settled))))]
    ;; Per Spec 005 §Final states §The done-state signal (rf2-bnjb3): when this
    ;; macrostep NEWLY makes every region final and the parallel root declares
    ;; `:on-done`, fire it (run its action + emit its fx) WITHOUT auto-
    ;; destroying — the transitionable parallel completion signal. No
    ;; `:on-done` ⇒ the Result passes through and the lifecycle boundary's
    ;; whole-machine finalize runs (D7).
    (fire-parallel-on-done machine settled newly-final?)))

(defn machine-transition
  "Pure function. Given a machine definition, current snapshot, and event,
  return a `re-frame.machines.result/Result` — either a `result/ok`
  carrying the new snapshot and effects vector, or a `result/fail`
  carrying diagnostic info if any action / `:data`-fn threw. Use
  `result/ok?` / `result/fail?` to discriminate and the `::result/snap`
  / `::result/fx` / `::result/info` keys to destructure (or the
  `result/with-ok` macro for the pair-destructure-after-fail-check
  pattern, and the plain `result/snap` / `result/fx` / `result/info`
  fns for single-slot reads).

  Per Spec 005 §Drain semantics §Level 3, this is the macrostep:
   1. Pick the matching transition for the event using deepest-wins
      resolution along the state path.
   2. Run the exit cascade → transition's action → entry cascade.
   3. Drain the local :raise queue FIFO (rf2-nr434 — XState/SCXML parity).
   4. :always microstep loop — walk path leaf→root for any matching
      :always; apply, drain raises, loop.
   5. Commit (return) the snapshot once :always reaches fixed point.

  Bounded by :raise-depth-limit and :always-depth-limit (both default 16).
  Parallel-region machines (`:type :parallel`) are dispatched into
  `parallel-machine-transition`, where the event is broadcast across
  regions per Spec 005 §Parallel regions (rf2-l67o) and region-emitted
  raises re-broadcast through the parent macrostep's internal-event queue
  (rf2-yi7ts). Flat / compound machines drop straight into the
  single-machine engine in `re-frame.machines.transition`."
  [machine snapshot event]
  (if (parallel? machine)
    (parallel-machine-transition machine snapshot event)
    (transition/machine-transition-single machine snapshot event)))

;; ---- birth-time `:always` + raise settle (rf2-505ic) ----------------------
;;
;; XState v5 / SCXML gold standard: the INITIAL macrostep is initial-entry
;; + the eventless (`always`) drain. `createActor(m).start()` evaluates
;; `always` on the entered initial state(s); if an `always` guard already
;; holds, the actor settles PAST the initial leaf with NO external event —
;; the transient initial leaf is never externally observed. re-frame2's own
;; Spec 005 promises this ("fire as soon as the machine starts" = the
;; initial cascade into a leaf whose `:always` fires); `settle-birth` runs
;; the SAME raise-drain + `:always` fixed-point loop the event macrostep
;; uses, immediately after the entry cascade and BEFORE the birth commit,
;; for BOTH birth paths (eager `[:rf.machine/start]` + lazy first-event).

(defn- settle-region-birth
  "Per-region birth settle step for `reduce-regions`: feed the region's
  post-cascade snapshot through the single-machine `drain-to-fixed-point`
  with an EMPTY seed cascade (the entry cascade was already captured by
  `run-initial-cascade`; this phase contributes only the `:always`
  microsteps). The region DEFERS its raises (`region-spec` carries
  `:rf/region`, so `drain-to-fixed-point`'s `defer?` is true) — they
  surface back to the parent queue, which `settle-birth` re-broadcasts."
  [region-spec region-snap]
  (transition/drain-to-fixed-point
    region-spec
    (result/ok region-snap [])
    region-snap                         ; atomic-rollback target = post-cascade
    0                                   ; raise-depth seed
    true))                              ; defer raises to the parent queue

(defn- settle-birth
  "Run the birth-time settle — raise drain + `:always` fixed-point loop —
  against the POST-initial-cascade Result, BEFORE the machine is committed
  (rf2-505ic). The shared settling tail of the macrostep, reused so a
  transient initial leaf whose `:always` guard already holds is settled
  past — unobserved — on start, exactly as XState v5 / SCXML do.

  `entry-r` is the WHOLE `run-initial-cascade` Result — its snapshot AND its
  fx. The entry fx is the drain SEED so a `[:raise ...]` emitted by an
  initial `:entry` action drains to a fixed point INSIDE the birth macrostep
  (bz0ox.1 / x4s9t — XState v5 / SCXML internal-event-queue parity; Spec 005
  §birth includes region-emitted raises). The settle therefore returns the
  entry's NON-raise fx (preserved in order) ++ any settle/raise-target fx;
  the caller (`apply-initial-entry-cascade`) does NOT re-prepend the entry fx.

  For flat / compound machines, seeds the single-machine
  `transition/drain-to-fixed-point` directly with `entry-r` (its boot
  snapshot + entry fx).

  For parallel-region machines, each region first settles its OWN `:always`
  (deferring raises) via `settle-region-birth` against the post-cascade
  snapshot, then `drain-parent-queue` re-broadcasts every region-surfaced
  raise — INCLUDING the initial-`:entry` raises harvested off the merged
  entry fx — FIFO across all regions to a fixed point (the same parent queue
  the event macrostep uses), seeded with `event nil` so the all-regions-
  declined no-op never fires on birth. The entry fx is folded into the seed
  Result so its raises enter the parent queue alongside the per-region
  `:always` raises (preserving entry-fx order ahead of settle fx).

  Returns a `result/ok` carrying the settled snapshot + fx (with
  `::microsteps` = the count of `:always` iterations and `::cascade` = the
  `:always`-microstep steps; the caller prepends the entry cascade), or a
  `result/fail` if an `:always` action or a drained raise threw. The
  atomic-rollback target on `:always`/`:raise`-depth abort is the
  post-cascade `boot-snapshot` (the initial configuration is the committed
  state — only a runaway settle is abandoned)."
  [machine entry-r]
  (result/with-ok [boot-snapshot entry-fx] entry-r
    (if (parallel? machine)
      (let [;; Per-region `:always` settle (deferring raises) against the
            ;; post-cascade snapshot. Its `acc-fx` is empty (regions seed with
            ;; `[]`); the entry-fx is folded into the seed BELOW so the
            ;; entry-`:entry` raises + per-region `:always` raises share the
            ;; one parent queue, with entry fx ordered ahead of settle fx.
            always-r (reduce-regions machine boot-snapshot settle-region-birth)
            ;; Fold the merged entry fx in FRONT of the per-region settle fx so
            ;; `drain-parent-queue`'s `split-raises` harvests every initial-
            ;; `:entry` `[:raise ...]` (bz0ox.1) — the non-raise entry fx keep
            ;; their position ahead of the `:always` fx.
            first-r  (if (result/fail? always-r)
                       always-r
                       (result/with-ok [snap0 settle-fx0] always-r
                         (result/with-handled
                           (result/with-microsteps
                             (result/with-cascade
                               (result/ok snap0 (into (vec entry-fx) settle-fx0))
                               (result/cascade always-r))
                             (result/microsteps always-r))
                           (result/handled? always-r))))]
        ;; Per rf2-bnjb3: a parallel machine BORN all-regions-final (each
        ;; region's initial+`:always` settles onto a `:final?` leaf) fires the
        ;; parallel root's `:on-done` on birth too — same transitionable signal
        ;; as the event-driven macrostep (XState v5 treats such an actor as done
        ;; at start). No `:on-done` ⇒ the lifecycle boundary's birth finalize
        ;; runs. `newly-reached? true`: birth is the single macrostep that
        ;; ENTERS the initial configuration, so a born-all-final machine crosses
        ;; the done edge here (h3wca.1) — fired once, never re-fired (a born
        ;; machine settles in one macrostep; subsequent events route through
        ;; `parallel-machine-transition`'s edge guard).
        (fire-parallel-on-done machine
                               (drain-parent-queue machine boot-snapshot nil first-r)
                               true))
      (transition/drain-to-fixed-point
        machine
        ;; Seed with the entry Result's fx so an initial-`:entry` `[:raise ...]`
        ;; drains FIFO inside the birth macrostep instead of escaping to the
        ;; outbound fx layer (bz0ox.1).
        (result/ok boot-snapshot (vec entry-fx))
        boot-snapshot
        0
        false))))

(defn apply-initial-entry-cascade
  "The machine's INITIAL MACROSTEP (rf2-505ic — XState v5 / SCXML parity):
  the initial-entry cascade THEN the eventless (`:always`) + raise settle,
  composed and returned as ONE Result. The single birth site for BOTH
  paths (eager `[:rf.machine/start]` and lazy first-event); the lifecycle
  handler's `maybe-boot` calls it once.

   1. `run-initial-cascade` — the initial-descent `:entry` actions
      (per-region broadcast for parallel machines).
   2. `settle-birth` — the raise drain + `:always` fixed-point loop on the
      post-cascade snapshot, BEFORE commit, so a transient initial leaf
      whose `:always` guard already holds settles past, unobserved.

  Returns a `result/ok` carrying the settled snapshot + the (drained) entry
  fx ++ any settle fx, with `::cascade` = the entry cascade ++ the `:always`
  microsteps (so the `:rf.machine/transition` / `:rf.machine/started`
  trace surfaces both). A `result/fail` from either phase short-circuits.

  `settle-birth` is seeded with the WHOLE `run-initial-cascade` Result so any
  `[:raise ...]` emitted by an initial `:entry` action drains FIFO inside this
  birth macrostep rather than escaping to the outbound fx layer as a reserved
  `:raise` fx (bz0ox.1 — would otherwise trip `:rf.error/no-such-fx`). The
  returned `settle-fx` therefore ALREADY carries the entry's non-raise fx (in
  order) ++ the settle/raise-target fx — so this fn does NOT re-prepend
  `entry-fx`.

  A no-`:always`, no-`:raise` machine settles in zero microsteps —
  `settle-birth` finds no matching `:always`, drains no raises, and returns
  the post-cascade snapshot + the entry fx verbatim with the tag union
  re-stamped (identical to the pre-rf2-505ic birth)."
  [machine initial-snapshot]
  (let [entry-r (run-initial-cascade machine initial-snapshot)]
    (if (result/fail? entry-r)
      entry-r
      (let [settle-r (settle-birth machine entry-r)]
        (if (result/fail? settle-r)
          settle-r
          (result/with-ok [settled-snap settle-fx] settle-r
            ;; `settle-fx` already = entry (non-raise) fx ++ settle fx, with
            ;; the initial-`:entry` raises drained internally (bz0ox.1).
            ;; Cascade: entry cascade ++ the `:always` microstep cascade.
            ;; `::microsteps` rides from the settle (the entry cascade ran no
            ;; `:always`). Per rf2-bnjb3 the settle's `parallel-done-handled?`
            ;; flag (set when a parallel machine born all-final fired its root
            ;; `:on-done`) rides through so the birth finalize gate sees it.
            (cond-> (-> (result/ok settled-snap (vec settle-fx))
                        (result/with-cascade
                          (into (vec (result/cascade entry-r))
                                (result/cascade settle-r)))
                        (result/with-microsteps (result/microsteps settle-r)))
              (result/parallel-done-handled? settle-r) (result/with-parallel-done))))))))

;; ---- destroy-time exit cascade (rf2-nahfm) --------------------------------
;;
;; Per Spec 005 §Final states §Composition with `:entry` / `:exit` and
;; §Declarative `:spawn` §Composition: the active configuration's
;; `:exit` actions run BEFORE the destroy cascade tears the snapshot
;; down. The single-machine helper lives in `re-frame.machines.transition`
;; (`run-active-exit-cascade`); for parallel-region machines every
;; region's active leaf path contributes its own exit cascade, ordered
;; by region declaration via `reduce-regions` so the broadcast invariant
;; (shared `:data` threading, per-region fx prefix) holds during destroy
;; the same way it holds during transition.

(defn run-active-exit-cascade
  "Synthesise the destroy-time exit cascade for `machine` against its
  active `snapshot`. For parallel-region machines, runs the exit cascade
  for every region in declaration order via `reduce-regions` (the
  per-region step delegates to `transition/run-active-exit-cascade`).
  For flat / compound machines, drops straight into the single-machine
  helper.

  Returns a `re-frame.machines.result/Result` carrying the post-cascade
  snapshot + accumulated fx, or a `result/fail` if any region's `:exit`
  action threw."
  [machine snapshot]
  (if (parallel? machine)
    (reduce-regions machine snapshot transition/run-active-exit-cascade)
    (transition/run-active-exit-cascade machine snapshot)))
