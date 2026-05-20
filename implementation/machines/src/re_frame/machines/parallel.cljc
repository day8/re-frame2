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
  the parallel layer exists. `re-frame.machines.transition`'s
  `drain-raises` reaches the single-machine engine directly via
  `machine-transition-single` — `:raise` always runs against an already-
  resolved single (or region) machine context, so the parallel layer
  is bypassed for the recursive macrostep call."
  (:require [clojure.set :as set]
            [re-frame.machines.result :as result
             #?@(:cljs [:include-macros true])]
            [re-frame.machines.transition :as transition]))

#?(:clj (set! *warn-on-reflection* true))

(defn parallel?
  "True iff the machine declares `:type :parallel` (root-level only)."
  [machine]
  (= :parallel (:type machine)))

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
  (let [rm        (assoc region-body :states (:states region-body))
        decl      (:initial region-body)
        full-path (transition/initial-cascade rm (transition/state-path decl))]
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
    (if (empty? tags)
      (dissoc snapshot :tags)
      (assoc snapshot :tags tags))))

(defn build-initial-snapshot
  "Build the freshly-derived initial snapshot for `machine` (rf2-fgqs4).
  The single source of truth used by both the singleton-registration path
  (`lifecycle-fx.registration/create-machine-handler`) and the spawn path
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

(defn- prefix-region-invoke-id
  "Per Spec 005 §Per-region `:spawn` / `:after` / `:always` scoping:
  spawn / destroy / after-schedule / after-cancel fxs emitted by a region
  carry an `:rf/spawn-id` that's the in-region prefix-path. To keep the
  runtime-owned `[:rf/spawned <parent-id> <invoke-id>]` slot unique
  per-region (and per-region `:after` epoch tracking distinct from sibling
  regions), prepend the region name onto the invoke-id."
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
  `result/fail` Result if any `:entry` action threw."
  [machine initial-snapshot]
  (let [original-state (:state initial-snapshot)
        boot-target    (if (keyword? original-state)
                         original-state
                         (vec (transition/state-path original-state)))]
    (transition/apply-transition-once
      machine
      (assoc initial-snapshot :state [])
      [:rf.machine/bootstrap]
      {:target    boot-target
       :decl-path []})))

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
;;     `prefix-region-invoke-id` so per-region `[:rf/spawned ...]` /
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
        ordered     (filterv #(contains? state-map %)
                             (or (vec (keys (:regions parent-machine)))
                                 (vec (keys state-map))))]
    (loop [pending     ordered
           cur-data    (:data snapshot)
           cur-counter (:rf/spawn-counter snapshot)
           new-states  state-map
           acc-fx      []]
      (if (empty? pending)
        (let [merged (cond-> (-> snapshot
                                 (assoc :state new-states)
                                 (assoc :data  cur-data))
                       (some? cur-counter)
                       (assoc :rf/spawn-counter cur-counter))]
          (result/ok (commit-tags-parallel parent-machine merged) acc-fx))
        (let [rn          (first pending)
              region-spec (region-machine parent-machine rn)
              region-snap (cond-> {:state (get state-map rn)
                                   :data  cur-data}
                            (some? cur-counter)
                            (assoc :rf/spawn-counter cur-counter))
              step-result (step-fn region-spec region-snap)]
          (if (result/fail? step-result)
            step-result
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
                     (assoc new-states rn (:state reg-snap))
                     (into acc-fx
                           (map (partial prefix-region-invoke-id rn))
                           reg-fx)))))))))

(defn apply-initial-entry-cascade
  "Synthesise the bootstrap entry cascade for `machine` against the
  freshly-synthesised `initial-snapshot`. Returns a `result/ok` Result
  carrying the new snapshot + fx, or a `result/fail` Result if any
  `:entry` action threw.

  For parallel-region machines (`:type :parallel`), delegates to
  `reduce-regions` so the per-region step sees the broadcast invariant
  (declaration-order iteration, threaded `:data` + `:rf/spawn-counter`,
  per-region fx-prefix, `commit-tags-parallel` on commit). For flat /
  compound machines, runs `bootstrap-step` directly — the broadcast
  invariant doesn't apply."
  [machine initial-snapshot]
  (if (parallel? machine)
    (reduce-regions machine initial-snapshot bootstrap-step)
    (bootstrap-step machine initial-snapshot)))

(declare machine-transition)

(defn- parallel-machine-transition
  "Pure function. Given a parallel-region machine, current snapshot, and
  event, broadcast the event to each region and merge the results.
  Returns a `result/ok` Result on success or a `result/fail` Result if
  any region's action threw.

  Per Spec 005 §Transition broadcast: each region resolves the event
  through its own active state's deepest-wins lookup; resolved regions
  transition, undeclined regions stay put. The `:data` slot is shared —
  each region's actions see the prior region's `:data` writes in
  declaration order. Per rf2-vqubp the broadcast invariant lives in
  `reduce-regions`; this function closes over `event` to produce a
  unary step-fn the helper can apply per region.

  For the synthetic `[:rf.machine.timer/after-elapsed ...]` event,
  delivery is region-scoped — the broadcast routes to the bearing region
  only, identified by the region-name prefix on the in-flight timer's
  invoke-id."
  [machine snapshot event]
  (reduce-regions machine snapshot
                  (fn [region-spec region-snap]
                    (machine-transition region-spec region-snap event))))

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
   3. Drain the local :raise queue depth-first.
   4. :always microstep loop — walk path leaf→root for any matching
      :always; apply, drain raises, loop.
   5. Commit (return) the snapshot once :always reaches fixed point.

  Bounded by :raise-depth-limit and :always-depth-limit (both default 16).
  Parallel-region machines (`:type :parallel`) are dispatched into
  `parallel-machine-transition`, where the event is broadcast across
  regions per Spec 005 §Parallel regions (rf2-l67o). Flat / compound
  machines drop straight into the single-machine engine in
  `re-frame.machines.transition`."
  [machine snapshot event]
  (if (parallel? machine)
    (parallel-machine-transition machine snapshot event)
    (transition/machine-transition-single machine snapshot event)))

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
