(ns re-frame.machines.parallel
  "Parallel-region machines. Per Spec 005 §Parallel regions.

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
  with the parent. The parent selects complete regional sets, invokes the
  single-transition APPLY seam against each slice, and merges results. Spawn /
  destroy / after-schedule /
  after-cancel fxs emitted by a region are post-processed to prefix
  the region name onto their `:rf/invoke-id` so per-region :spawn /
  :after slots scope correctly — one region's timer doesn't fire
  transitions in sibling regions.

  This namespace also exposes the public `machine-transition` dispatch
  (single vs parallel) so the transition engine doesn't need to know
  the parallel layer exists.

  `:raise` semantics (XState v5 / SCXML). A flat / compound machine drains its own `:raise` queue FIFO
  inside `re-frame.machines.transition`. A PARALLEL machine owns the
  macrostep's single internal-event queue HERE. Event and eventless sets are
  selected against frozen whole-machine snapshots and applied in canonical
  order without local regional settling. `parallel-machine-transition`
  re-broadcasts surfaced raises across every region FIFO, preferring complete
  eventless rounds before each dequeue, then commits once.
  A region's `:raise` is therefore NOT region-local; it re-enters the
  parent macrostep, matching what a self-`[:dispatch [<self-id> …]]` would
  broadcast (pre-commit, in one macrostep)."
  (:require [clojure.set :as set]
            [re-frame.error :as rf.error]
            [re-frame.machines.choice :as rf.machines.choice]
            [re-frame.machines.result :as rf.machines.result
             #?@(:cljs [:include-macros true])]
            [re-frame.machines.timeout :as rf.machines.timeout]
            [re-frame.machines.transition :as rf.machines.transition]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

(defn parallel?
  "True iff the machine declares `:type :parallel` (root-level only)."
  [machine]
  (= :parallel (:type machine)))

;; ---- region declaration order — the explicit registration contract --------
;;
;; Per Spec 005 §Parallel regions, a parallel machine SELECTS every region's
;; enabled transition against ONE frozen pre-event snapshot, then APPLIES the
;; selected transitions in region DECLARATION order. That apply order governs
;; sequential `:data` accumulation, effect / cascade ordering, spawn-counter
;; allocation, birth/destroy entry-exit order, root multi-target application,
;; and the finalization first-region tie-break.
;;
;; Declaration order is an EXPLICIT, IMMUTABLE part of the registration
;; contract — the canonical `:region-order` vector — normalised ONCE at
;; registration (`normalise-region-order`, via `install-region-cache`) and at
;; the pure-fn engine seams. Runtime code NEVER recovers order from
;; `(keys (:regions …))` / state-map iteration: a `:regions` map with more
;; than eight entries is a PersistentHashMap whose key iteration is HASH order
;; (source order is already gone at read time, and hash order even differs
;; between CLJ and CLJS), so map iteration cannot carry authored order. This
;; changes the apply-ordering REPRESENTATION only; the frozen select-then-apply
;; SELECTION semantics are unchanged.

(defn- order-preserving-map?
  "True iff `m`'s key-iteration order equals its authored/insertion order —
  i.e. `m` is a PersistentArrayMap (the ≤8-entry representation in both CLJ
  and CLJS). A PersistentHashMap (>8 entries) iterates in HASH order, which is
  NOT authored order and diverges between CLJ and CLJS."
  [m]
  #?(:clj  (instance? clojure.lang.PersistentArrayMap m)
     :cljs (instance? cljs.core/PersistentArrayMap m)))

(defn- region-order-ok?
  "True iff `order` is a vector that is an exact permutation of
  `region-keyset` — same cardinality and same set (which together forbid
  duplicates and any missing / extra region)."
  [order region-keyset]
  (and (vector? order)
       (= (count order) (count region-keyset))
       (= (set order) region-keyset)))

(defn normalise-region-order
  "Normalise a parallel `machine`'s region declaration order into the
  canonical immutable `:region-order` vector — the ONE order source the
  runtime reads. Non-parallel machines pass through untouched. Idempotent: a
  machine already carrying a canonical `:region-order` is returned unchanged,
  so re-registration and the pure-fn engine seams may re-run it freely.

  Resolution (registration-time, exactly once):
   1. An explicit author-supplied `:region-order` is VALIDATED — a vector that
      is an exact permutation of the `:regions` keyset (unique entries, no
      missing / extra region). A malformed / mismatched order throws
      `:rf.error/machine-parallel-region-order-mismatch`.
   2. Otherwise the order is DERIVED from `:regions` iteration ONLY when the
      map is order-preserving (a PersistentArrayMap — ≤8 entries, whose key
      order IS the authored order in both CLJ and CLJS). This is the sole
      small-machine convenience path; it captures the authored order ONCE into
      the canonical vector and the runtime never re-reads map iteration.
   3. A `:regions` map that is NOT order-preserving (a PersistentHashMap — >8
      entries) with NO explicit `:region-order` throws
      `:rf.error/machine-parallel-region-order-required`: a >8-entry map
      literal has already lost authored order (hash iteration, CLJ/CLJS-
      divergent), so the author MUST declare an explicit `:region-order`
      vector (or keep the region count at/below the array-map threshold)."
  [machine]
  (if-not (parallel? machine)
    machine
    (let [regions (:regions machine)]
      (if-not (map? regions)
        machine                          ; malformed shape — validate-parallel! reports it
        (let [keyset   (set (keys regions))
              declared (:region-order machine)]
          (cond
            (region-order-ok? declared keyset)
            machine                       ; already canonical — idempotent re-run

            (some? declared)
            (rf.error/throw-error!
              :rf.error/machine-parallel-region-order-mismatch
              'rf/reg-machine
              (str ":region-order must be a vector that is an exact permutation of "
                   "the :regions keyset — unique entries, one per region, no missing "
                   "or extra region. Declared " (pr-str declared) " for regions "
                   (pr-str keyset) ".")
              {:recovery :fix-registration
               :extra    {:region-order declared :regions keyset}})

            (order-preserving-map? regions)
            (assoc machine :region-order (vec (keys regions)))

            :else
            (rf.error/throw-error!
              :rf.error/machine-parallel-region-order-required
              'rf/reg-machine
              (str "a :type :parallel machine with more than eight regions must "
                   "declare an explicit :region-order vector: a >8-entry :regions "
                   "map literal is a PersistentHashMap whose key order is hash order "
                   "(authored declaration order is already lost at read time, and "
                   "hash order diverges between CLJ and CLJS). Add "
                   ":region-order [<region> …] listing every region in declaration "
                   "order.")
              {:recovery :fix-registration
               :extra    {:regions keyset :region-count (count regions)}})))))))

(defn region-order
  "The canonical region declaration-order vector for a parallel `machine` —
  the SINGLE order source every order-sensitive parallel operation routes
  through (initial-snapshot construction, `reduce-regions`, root multi-target
  apply, birth / destroy cascades, fx / spawn allocation, the finalization
  first-region tie-break). Reads the canonical `:region-order` normalised once
  at registration; self-normalises a machine that reaches this accessor
  un-normalised (a raw pure-fn caller) so order is NEVER silently taken from
  map / state-map iteration — an order-preserving small map is captured, a >8
  hash-map without an explicit order throws."
  [machine]
  (or (:region-order machine)
      (:region-order (normalise-region-order machine))))

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
      that region's body — i.e. `rf.machines.transition/state-occupiable?` (non-nil node,
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
                  (rf.machines.transition/state-occupiable?
                    (get-in machine [:regions region-name])
                    region-state))
                state)))))

(defn all-regions-final?
  "Per Spec 005 §Parallel regions + §Final states §The done-state signal:
  a parallel-region machine has reached its done configuration
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
                 leaf-node   (rf.machines.transition/node-at region-body
                                                  (rf.machines.transition/state-path region-state))]
             (rf.machines.transition/final-state-node? leaf-node)))
         state)))

(defn- build-region-machine
  "Construct a synthetic single-machine spec for one region of
  `parent-machine`. See `region-machine` for the contract — this is the
  uncached compute path.

  The region body is DESUGARED here — `:timeout` / `:on-timeout` lowered onto
  `:after`, `:type :choice` / `:choice` onto `:always` — so the synthetic
  region-spec is ALWAYS in the lowered form the engine drives, regardless of
  whether `parent-machine` was desugared. This is the single choke-point where
  region-specs are born (`region-machine` memoises the result), so the cache
  holds the desugared form and BOTH the birth paths that call
  `apply-transition-once` directly (`bootstrap-step`, `apply-root-region-target`
  — which BYPASS the per-dispatch `machine-transition` desugar seam) and the
  event path see identically-lowered region bodies. Without this, a
  region-initial `:timeout` never armed at birth and a region-initial
  `:type :choice` stayed stuck at its transient node (rf2-x76af2.7): the raw
  region body was faulted into the cache during `build-initial-snapshot`'s tag
  computation, then served — still raw — on those direct-apply paths. Both
  desugars are idempotent and short-circuit for the common rf.machines.timeout/choice-free
  region, so this costs one structural scan on the once-per-region cache miss.
  A region-ROOT `:after` / `:timeout` is rejected at registration
  (`validate-non-parallel-root-after!`, rf2-x76af2.10), so only region STATE
  nodes carry lowerable `:timeout` / `:choice` by the time a body reaches here."
  [parent-machine region-name]
  (let [region-body (rf.machines.choice/desugar-choices
                      (rf.machines.timeout/desugar-timeouts
                       (get-in parent-machine [:regions region-name])))]
    (-> region-body
        (assoc :guards            (:guards parent-machine))
        (assoc :actions           (:actions parent-machine))
        (assoc :on-spawn-actions  (:on-spawn-actions parent-machine))
        (assoc :rf/parent-id      (:rf/parent-id parent-machine))
        (assoc :rf/platform       (:rf/platform parent-machine))
        (assoc :rf/frame          (:rf/frame parent-machine))
        ;; The causal
        ;; recordable-coeffect token is transition-local, NOT registration-time
        ;; data — `prepare-machine-ctx` stamps `:rf/cofx` per dispatch. The cache
        ;; is populated at registration time, so we must NOT bake a value (or even
        ;; the key) in here: a stale `:rf/cofx` cached from the priming dispatch
        ;; would otherwise leak into a later transition whose parent carries none.
        ;; `region-spec-overlaid` is the single choke-point that
        ;; overlays the LIVE `:rf/cofx` onto the cached spec — present iff the
        ;; current parent carries it, dissoc'd otherwise — so the absent path
        ;; leaves no stale slot for `callback-ctx`'s presence check to read.
        (assoc :rf/region         region-name))))

(defn region-machine
  "Synthetic single-machine spec for a region of `parent-machine`.

  Inherits `:guards` / `:actions` / `:on-spawn-actions` from the parent so
  region transitions can reference the parent's named guards / actions
  without redeclaring them. Inherits `:rf/parent-id` / `:rf/platform` /
  `:rf/frame` so the post-action `:rf.machine/spawn` / `:after-schedule`
  fxs the region emits carry the parent's identity (the region name is
  prepended onto the `:rf/invoke-id` separately).

  Region-specs are registration-time
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
  "Normalise the region declaration order (`normalise-region-order` — the
  canonical `:region-order` vector, the ONE registration-time step that makes
  authored order explicit) and attach an empty region-machine cache to
  `parent-machine`'s metadata. Called at registration time for `:type
  :parallel` machines so the hot-path `region-machine` lookups hit the cache
  instead of allocating a fresh map per call, and so every downstream
  order-sensitive site reads the canonical order rather than map iteration."
  [parent-machine]
  (if (parallel? parent-machine)
    (-> parent-machine
        normalise-region-order
        (vary-meta assoc ::region-cache (atom {})))
    parent-machine))

(defn- region-initial-state
  "Compute the initial-state value for one region — applying that region's
  `:initial` cascade through any compound chain. Returns the region's
  state value (keyword for flat regions, vector path for compound regions)."
  [region-body]
  (let [decl      (:initial region-body)
        ;; `region-body` already carries `:states` — `initial-cascade`
        ;; reads it through `node-at`, so pass it directly.
        full-path (rf.machines.transition/initial-cascade region-body (rf.machines.transition/state-path decl))]
    (rf.machines.transition/denormalise-state full-path decl)))

(defn- compute-tags-parallel
  "Per Spec 005 §Tags compose across regions: union every active state's
  `:tags` across every active region."
  [machine state-map]
  (transduce
    (map (fn [[region-name region-state]]
           (rf.machines.transition/compute-tags (region-machine machine region-name) region-state)))
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
               (rf.machines.transition/compute-tags machine (:state snapshot)))]
    ;; The empty→dissoc / else→assoc elision lives
    ;; once in `rf.machines.transition/stamp-tags`; both tag-commit fns delegate to it.
    (rf.machines.transition/stamp-tags snapshot tags)))

(defn build-initial-snapshot
  "Build the freshly-derived initial snapshot for `machine`.
  The single source of truth used by both the singleton-registration path
  (`lifecycle-fx.registration/make-machine-handler`) and the spawn path
  (`lifecycle-fx.spawn/install-spawn!`). Steps:

   1. Compute `:state` — for parallel-region machines, a map of
      region-name → that region's cascaded initial; for flat / compound
      machines, the root `:initial` cascade denormalised to a leaf path.
      Per Spec 005 §Initial-state cascading and §Parallel regions.
   2. Seed `:data` — `(:data machine)` or `{}`.
   3. Seed `:rf/spawn-counter {}` — the in-snapshot
      allocator MUST be present on live snapshots so
      `:entry`-declared `:spawn`s allocate ids through the contract path
      (not `allocate-spawned-id`'s defensive `(fnil inc 0)` backstop).
   4. Propagate `:meta` when the spec declares it — per Spec 005 §Snapshot
      shape, so the 3-arity ctx and downstream version checks see the
      same `:meta` the spec declares. Spawned actors that declare `:meta`
      MUST carry it through to the snapshot.
   5. Stamp the initial tag union via `commit-tags-parallel` — per Spec
      005 §State tags / §Tags compose across regions;
      the slot is elided when the union is empty.
   6. Optionally stamp `:rf/bootstrap-pending? true` when
      `bootstrap-pending?` is truthy — the spawn path needs this so the
      actor's first dispatch fires the initial-entry cascade. The
      singleton-registration path stamps the marker lazily inside
      `prepare-machine-ctx` instead (when `existing-snap` is nil), so it
      passes `bootstrap-pending? false` here."
  [machine {:keys [bootstrap-pending?]}]
  (let [initial-state (if (parallel? machine)
                        ;; Build the region→initial map in canonical
                        ;; declaration order (`region-order`), NOT `:regions`
                        ;; map iteration — so the initial `:state` reads in
                        ;; authored order and never inherits hash iteration.
                        (into {}
                              (map (fn [rn]
                                     [rn (region-initial-state
                                           (get-in machine [:regions rn]))]))
                              (region-order machine))
                        (let [decl (:initial machine)]
                          (rf.machines.transition/denormalise-state
                            (rf.machines.transition/initial-cascade machine (rf.machines.transition/state-path decl))
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
  carry an `:rf/invoke-id` (the declarative invocation path) that's the
  in-region prefix-path. To keep the
  runtime-owned `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` slot unique
  per-region (and per-region `:after` epoch tracking distinct from sibling
  regions), prepend the region name onto the `:rf/invoke-id`.

  A `:spawn-all` PER-CHILD spawn fx carries the same invocation path under
  `:rf/spawn-all-id` (its join-slot key — deliberately NOT `:rf/invoke-id`,
  which would make `spawn-fx` track the child in a per-child slot). It must
  be region-prefixed IDENTICALLY, or the child-side join reads
  (`spawn-all-invoke-rejected?`, the `:rf/join-child` membership record —
  rf2-nvxehu) address a slot the region's `spawn-all-init-fx` never seeded."
  [region-name fx]
  (let [[fx-id args] fx
        prefix-path  #(vec (cons region-name %))]
    (if (and (map? args)
             (or (contains? args :rf/invoke-id)
                 (contains? args :rf/spawn-all-id)))
      [fx-id (cond-> args
               (contains? args :rf/invoke-id)
               (update :rf/invoke-id prefix-path)

               (contains? args :rf/spawn-all-id)
               (update :rf/spawn-all-id prefix-path))]
      fx)))

;; ---- initial-state entry cascade ------------------------------------------
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
  a `rf.machines.result/ok` Result carrying the post-cascade snapshot + fx, or a
  `rf.machines.result/fail` Result if any `:entry` action threw.

  Every initial-entry cascade action carries `:phase
  :initial-entry` on its `:rf.machine/action-ran` emit so the Handler
  section's LIFECYCLE rendering distinguishes the bootstrap entry
  burst from a later `:on`-driven entry-cascade."
  [machine initial-snapshot]
  (let [original-state (:state initial-snapshot)
        boot-target    (if (keyword? original-state)
                         original-state
                         (vec (rf.machines.transition/state-path original-state)))]
    (rf.machines.transition/apply-transition-once
      machine
      (assoc initial-snapshot :state [])
      [rf.machines.transition/start-marker]
      {:target    boot-target
       :decl-path []}
      :initial-entry)))

;; ---- parallel-region broadcast invariant ----------------------------------
;;
;; Per Spec 005 §Parallel regions, every reducer that broadcasts ONE
;; per-region computation across a parallel-region machine MUST:
;;   - iterate regions in declaration order — the canonical `region-order`
;;     vector (the order in which the regions were authored, made explicit and
;;     normalised ONCE at registration); NEVER `:regions` / state-map key
;;     iteration, which is hash order past the array-map threshold,
;;   - thread shared `:data` sequentially through regions so a later
;;     region's step sees earlier regions' writes,
;;   - thread the in-snapshot `:rf/spawn-counter` so any
;;     declarative `:spawn` fired in a region bumps the SAME shared
;;     counter,
;;   - run each region as a synthetic single-machine spec via
;;     `region-machine`,
;;   - prefix per-region fx with the region name via
;;     `prefix-region-invoke-id` so per-region `[:rf.runtime/machines :spawned ...]` /
;;     `:after`-epoch tracking slots stay distinct from siblings,
;;   - short-circuit to a `rf.machines.result/fail` if any region's step fails,
;;   - commit `:tags` via `commit-tags-parallel` AFTER every region has
;;     transitioned, since `:tags` is the union across active leaves.
;;
;; `reduce-regions` names this invariant once — every broadcast
;; reducer in the parallel layer delegates here.

(defn- region-spec-overlaid
  "Return the synthetic region-spec for region `rn` of `parent-machine` with
  the LIVE runtime dynamic keys overlaid. `region-machine` memoises the spec
  at REGISTRATION time — before `prepare-machine-ctx` stamps `:rf/parent-id`
  / `:rf/platform` / `:rf/frame` onto the live machine — so the cached spec
  carries stale registration-time values for all three. The live
  `parent-machine` threaded into a broadcast DOES carry the current values, so
  overlay them here so region pure logic (`build-after-fx`'s server-skip gate,
  trace `:frame` attribution, declarative-`:spawn` parent attribution) always
  runs against the live runtime context.
  `(:id parent-machine)` is the defensive `:rf/parent-id` fallback for pure-fn
  callers. The single overlay choke-point shared by the broadcast invariant
  (`reduce-regions`) and the root-parallel `:on` region-target apply
  (`apply-root-parallel-transition`)."
  [parent-machine rn]
  (let [parent-id (or (:rf/parent-id parent-machine) (:id parent-machine))]
    (cond-> (region-machine parent-machine rn)
      (some? parent-id) (assoc :rf/parent-id parent-id)
      ;; Overlay live platform/frame unconditionally — an explicit nil from the
      ;; live parent is still the correct current value (a `:client` / nil-frame
      ;; runtime) and must replace any stale cached value.
      true (assoc :rf/platform (:rf/platform parent-machine)
                  :rf/frame    (:rf/frame parent-machine))
      ;; Overlay the live causal
      ;; recordable-coeffect token so a region guard / action reads the CURRENT
      ;; dispatch's `:rf.cofx`. `:rf/cofx` is transition-local — `callback-ctx`
      ;; keys off its PRESENCE — so the overlay must MATCH the live parent
      ;; exactly: assoc when the parent carries it, DISSOC otherwise. Dissoc on
      ;; the absent path guarantees the
      ;; cached spec can never surface a coeffect the current caller did not
      ;; carry — even if the cache faulted in under a coeffect-carrying parent.
      (contains? parent-machine :rf/cofx)
      (assoc :rf/cofx (:rf/cofx parent-machine))

      (not (contains? parent-machine :rf/cofx))
      (dissoc :rf/cofx)

      ;; Overlay the live effective cofx MINT POLICY the same way
      ;; as `:rf/cofx` (match the parent's presence exactly: assoc when carried,
      ;; dissoc otherwise) so a region's in-engine raised-event ensure
      ;; mints under the SAME `:strict` / `:live` policy the parent
      ;; dispatch resolved, never a stale cached one.
      (contains? parent-machine :rf/cofx-mint-policy)
      (assoc :rf/cofx-mint-policy (:rf/cofx-mint-policy parent-machine))

      (not (contains? parent-machine :rf/cofx-mint-policy))
      (dissoc :rf/cofx-mint-policy))))

(defn- reduce-regions
  "The APPLY fold: apply `step-fn` to each region of `parent-machine` in
  declaration order, threading `:data` + `:rf/spawn-counter` + `:rf/history`
  between regions and prefixing per-region fx with the region name. `step-fn`
  receives the synthetic region-spec and the per-region snapshot
  (`{:state ... :data ... :all-state ... :tags ... ?:rf/spawn-counter ...}`)
  and returns a `re-frame.machines.result/Result`.

  Transition callers are APPLY-only: `broadcast-once` and
  `apply-always-round` select complete regional sets against one frozen view
  before entering this fold. Initial-entry and lifecycle cascades have no
  transition-selection phase but retain the same deterministic action/fx
  ordering and shared-data threading.

  Returns a `rf.machines.result/ok` Result carrying the merged snapshot (post-
  `commit-tags-parallel`) + accumulated fx, or the first region's
  `rf.machines.result/fail` (cascade short-circuits).

  This helper assumes `parent-machine` is `:type :parallel`. Flat /
  compound callers run their step directly — the broadcast invariant
  doesn't apply."
  [parent-machine snapshot step-fn]
  (let [state-map   (:state snapshot)
        ;; Canonical declaration-order iteration via `region-order` — the ONE
        ;; order source, normalised once at registration; NEVER `(keys …)` map
        ;; iteration (hash order past the array-map threshold). Snapshot-shape
        ;; validity (exactly the declared region key set —
        ;; `parallel-state-valid?`) is enforced UPSTREAM at handler entry
        ;; (`registration/state-resolves?` -> `reconcile-snapshot`), so a live
        ;; snapshot reaching here always carries every declared region; the
        ;; `contains?` filter is then a no-op. It is retained as a defensive
        ;; guard against a pure-fn caller that synthesises a partial map
        ;; directly — skipping an absent region is safer than a `state-path
        ;; nil` throw, and `all-regions-final?` now independently rejects a
        ;; partial map so a skipped region can never vacuously read as done
        ;; (bz0ox.2 / x4s9t.2).
        ordered     (filterv #(contains? state-map %)
                             (region-order parent-machine))
        ;; FROZEN pre-broadcast cross-region snapshot. The
        ;; `:all-state` / `:tags` a region's guard OR action reads are computed
        ;; ONCE here, from the pre-event `state-map`, and threaded UNCHANGED
        ;; into every region's step ctx — NOT rebuilt per region from the
        ;; evolving `new-states`. Statechart atomicity: the configuration
        ;; changes atomically old->new, so there is no intermediate
        ;; configuration some transitions observe and others do not. The frozen
        ;; view reaches every ACTION ctx. Transition selection never occurs in
        ;; this fold; event and eventless callers preselect the complete set.
        ;;
        ;; `:data` is the ONE value that flows: it is threaded (evolving) so a
        ;; region's ACTION accumulates in declaration order. Note the EVENT-
        ;; guard SELECTION is NOT resolved in this fold — `broadcast-once` runs
        ;; a dedicated SELECT pass FIRST, resolving every region's match against
        ;; a frozen pre-event view that ALSO freezes `:data`, so a region's
        ;; event guard never sees an earlier region's same-macrostep `:data`
        ;; write; this fold only APPLIES the pre-selected transitions
        ;; (rf2-lq5yo3). `commit-snapshot` preserves the `:all-state` / `:tags`
        ;; slots through each preselected transition's cascade, so actions read
        ;; one frozen sibling view while shared `:data` accumulates.
        ;;
        ;; A region guard / action reads a
        ;; sibling's state via `:all-state` (precise) and `:tags` (coarse
        ;; stateIn substitute), and those keys resolve against the frozen
        ;; pre-event view. Same-event
        ;; cross-region coordination retimes to the NEXT microstep via the
        ;; statechart-idiomatic path (a region `:raise`s / writes `:data`; the
        ;; FIFO re-broadcast re-selects against the now-updated config — a
        ;; fresh `broadcast-once` recomputes a fresh frozen SELECT view per
        ;; re-broadcast), bounded by `:always-depth-limit` / `:raise-depth-limit`.
        frozen-all-state state-map
        frozen-tags      (compute-tags-parallel parent-machine state-map)]
    (loop [pending      ordered
           cur-data     (:data snapshot)
           cur-counter  (:rf/spawn-counter snapshot)
           ;; Per Spec 005 §Composition with parallel regions — per-region
           ;; history: thread the shared `:rf/history` map
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
          ;; only when EVERY region declined. The caller owns parent-round
          ;; accounting; this generic fold merely combines any step counts. The
          ;; structured `::cascade` is the per-region step
          ;; sequences concatenated in declaration order — each step
          ;; already carries `:region` (stamped by the single-machine
          ;; engine from the synthetic region-spec's `:rf/region`), so the
          ;; flat concatenation stays per-region addressable for the
          ;; consumer.
          (-> (rf.machines.result/ok (commit-tags-parallel parent-machine merged) acc-fx)
              (rf.machines.result/with-handled any-handled?)
              (rf.machines.result/with-microsteps micro-total)
              (rf.machines.result/with-cascade cascade)))
        (let [rn          (first pending)
              ;; Stamp the REAL parent machine-id onto the
              ;; synthetic region-spec so any declarative `:spawn` / `:after`
              ;; the region fires keys its
              ;; `[:rf.runtime/machines :spawned <parent> <invoke-id>]` slot
              ;; (and the child's `:data :rf/parent-id`) under the actual
              ;; spawning parent — NOT the `:rf/transition-pure` fallback
              ;; `run-spawn-phase` / `build-after-fx` apply when
              ;; `(:rf/parent-id machine)` is unset.
              ;;
              ;; `region-machine` memoises the region-spec at REGISTRATION
              ;; time (via `build-initial-snapshot` forcing the lazy
              ;; `base-initial`), BEFORE `prepare-machine-ctx` stamps the
              ;; runtime-only dynamic keys (`:rf/parent-id`, `:rf/platform`,
              ;; `:rf/frame`) onto the live machine — so the CACHED region-spec
              ;; carries the registration-time values for ALL THREE: a nil
              ;; `:rf/parent-id`, and a stale/missing `:rf/platform` /
              ;; `:rf/frame` (whatever the parent spec held when the cache was
              ;; first populated, which can be the unstamped registration-time
              ;; machine if a snapshot/tag computation faulted the cache in
              ;; before `prepare-machine-ctx` ran).
              ;;
              ;; The live `parent-machine` threaded into this broadcast DOES
              ;; carry the current runtime values (`prepare-machine-ctx`
              ;; stamps them: `:rf/parent-id` = the parent's own
              ;; registration / spawned id; `:rf/platform` = the frame's
              ;; platform; `:rf/frame` = the operating frame id). The OVERLAY
              ;; of all three live dynamic keys onto the cached region-spec is
              ;; the single `region-spec-overlaid` choke-point both transition
              ;; callers (`broadcast-once`, `run-initial-cascade`'s
              ;; `bootstrap-step`) funnel through, so region pure logic
              ;; (`build-after-fx`'s server-skip gate, trace `:frame`
              ;; attribution) always runs against the LIVE runtime context —
              ;; never the cached snapshot.
              region-spec (region-spec-overlaid parent-machine rn)
              region-snap (cond-> {:state (get state-map rn)
                                   :data  cur-data
                                   ;; Thread the
                                   ;; cross-region coordination context into
                                   ;; every region's guard/action ctx — the
                                   ;; XState v5 `stateIn` / SCXML `In()`
                                   ;; equivalent.
                                   ;;
                                   ;; `:all-state` is the full region-name →
                                   ;; active-state map (the PRECISE sibling-
                                   ;; state read); `:tags` is the MACHINE-WIDE
                                   ;; tag union across every region (the coarse
                                   ;; tag-as-stateIn substitute).
                                   ;;
                                   ;; Both resolve against the
                                   ;; FROZEN pre-broadcast snapshot (`state-map`
                                   ;; → `frozen-all-state` / `frozen-tags`,
                                   ;; computed ONCE above), NOT the evolving
                                   ;; `new-states`. So a region's guard / action
                                   ;; sees siblings' PRE-EVENT states regardless
                                   ;; of declaration order — the
                                   ;; SELECT-then-APPLY two-phase model in
                                   ;; substrate-honest form (only `cur-data`
                                   ;; flows; the cross-region view is frozen).
                                   ;; This is exact v5/SCXML parity and
                                   ;; declaration-order-independent. Across the
                                   ;; FIFO raise drain, each re-broadcast runs a
                                   ;; FRESH `reduce-regions` that recomputes a
                                   ;; fresh frozen view from the latest
                                   ;; `cur-snap`, so same-event coordination
                                   ;; settles on the NEXT microstep
                                   ;; (statechart-idiomatic), not the same pass.
                                   ;;
                                   ;; `:all-state` doubles as the parallel-
                                   ;; region marker `call-guard`/`call-action`
                                   ;; key off (transition.cljc) — flat/compound
                                   ;; machines never set it, so their ctx is
                                   ;; unchanged.
                                   :all-state frozen-all-state
                                   :tags      frozen-tags}
                            (some? cur-counter)
                            (assoc :rf/spawn-counter cur-counter)
                            ;; Seed the region snapshot with the shared
                            ;; (region-qualified) `:rf/history` so a restore
                            ;; reads the prior recording and a record writes
                            ;; the region's own key.
                            (some? cur-history)
                            (assoc :rf/history cur-history))
              step-result (step-fn region-spec region-snap)]
          (if (rf.machines.result/fail? step-result)
            step-result
            (let [region-handled? (rf.machines.result/handled? step-result)
                  region-micro    (rf.machines.result/microsteps step-result)
                  region-cascade  (rf.machines.result/cascade step-result)]
              (rf.machines.result/with-ok [reg-snap reg-fx] step-result
                ;; Accumulate fx via `into` so the region
                ;; loop doesn't rebuild the accumulator as a fresh vector
                ;; on every region step: `into` uses a transient
                ;; internally — O(N·M) amortised for N regions × M fx. The
                ;; prefix-fn is
                ;; folded into the transducer position so we don't
                ;; materialise the intermediate `prefixed-fx` vector.
                (recur (rest pending)
                       (:data reg-snap)
                       (:rf/spawn-counter reg-snap)
                       ;; Carry forward this region's `:rf/history` writes
                       ;; (region-qualified keys) so later regions + the
                       ;; merge see them.
                       (:rf/history reg-snap)
                       (assoc new-states rn (:state reg-snap))
                       (into acc-fx
                             (map (partial prefix-region-invoke-id rn))
                             reg-fx)
                       (or any-handled? region-handled?)
                       (long (+ micro-total region-micro))
                       (into cascade region-cascade))))))))))

(defn- run-initial-cascade
  "Synthesise the bootstrap ENTRY cascade for `machine` against the
  freshly-synthesised `initial-snapshot` — the initial-descent `:entry`
  actions only. Returns a `rf.machines.result/ok` Result carrying the post-cascade
  snapshot + fx (+ `::cascade`), or a `rf.machines.result/fail` Result if any
  `:entry` action threw.

  For parallel-region machines (`:type :parallel`), delegates to
  `reduce-regions` so the per-region step sees the broadcast invariant
  (declaration-order iteration, threaded `:data` + `:rf/spawn-counter`,
  per-region fx-prefix, `commit-tags-parallel` on commit). For flat /
  compound machines, runs `bootstrap-step` directly — the broadcast
  invariant doesn't apply.

  The `:always` fixed-point + raise drain that settles the
  initial macrostep is a SEPARATE phase — `apply-initial-entry-cascade`
  composes this entry cascade with `settle-birth`.

  Per Spec 005 §Root parallel `:after`: a `:type :parallel` root
  declaring `:after` is ROOT-OWNED — its timers are scheduled HERE, at machine
  birth (when the parallel root is entered), folded onto the per-region entry
  cascade's Result. `reduce-regions` walks each REGION's `:after`; the root's
  own `:after` lives on the machine map itself (decl-path `[]`) and is never an
  entered region node, so it is scheduled explicitly via
  `rf.machines.transition/schedule-root-after-fx` (which bumps the root's per-path epoch on
  the snapshot and emits the same `:scheduled` trace + `:after-schedule` fx a
  state's `:after` emits)."
  [machine initial-snapshot]
  (if (parallel? machine)
    (let [regions-r (reduce-regions machine initial-snapshot bootstrap-step)]
      (if (rf.machines.result/fail? regions-r)
        regions-r
        (rf.machines.result/with-ok [snap fx] regions-r
          (let [[snap' root-after-fx]
                (rf.machines.transition/schedule-root-after-fx machine snap false)]
            (-> (rf.machines.result/ok snap' (into (vec fx) root-after-fx))
                (rf.machines.result/with-handled (rf.machines.result/handled? regions-r))
                (rf.machines.result/with-microsteps (rf.machines.result/microsteps regions-r))
                (rf.machines.result/with-cascade (rf.machines.result/cascade regions-r)))))))
    (bootstrap-step machine initial-snapshot)))

(declare machine-transition)
;; `drain-parent-queue` consults the parallel root `:on` ancestor fallback for a
;; re-broadcast raise declined by every region (rf2-x76af2.8); `root-fallback-
;; seed` is defined below it (alongside the external-seed path it mirrors).
(declare root-fallback-seed)

;; ---- parallel macrostep internal-event queue ------------------------------
;;
;; XState v5 / SCXML: `raise` enqueues on the machine's ONE
;; internal event queue; the macrostep pops the front and broadcasts that
;; internal event to every active region, FIFO, until the queue drains —
;; then commits once. A parallel-region `:raise` is therefore NOT
;; region-local: a region that raises an event re-enters the PARENT
;; macrostep, which re-broadcasts the raised event across ALL sibling
;; regions against the FULL EVOLVING snapshot (so a sibling region's guard
;; sees the raise; the originating region also re-sees it).
;;
;; Mechanism: regional transitions apply without local draining, leaving
;; `:raise` as fx for `parallel-machine-transition` to harvest. The parent
;; first runs frozen eventless select/apply rounds; only at quiescence does it
;; feed the queue front FIFO through another full broadcast. The loop
;; terminates at the parent
;; `:raise-depth-limit` (default 16, same constant as the flat drain), and
;; on exceed rolls the WHOLE macrostep back atomically (original snapshot,
;; no fx) exactly like the single-machine drain.

(defn- split-raises
  "Partition a merged broadcast's `fx` into `[raised-events real-fx]`.
  `raised-events` is the vector of raised event-vectors (the `args` of each
  `[:raise <event-vec>]`), in broadcast order — region-declaration order
  within one broadcast, which is the order regions surfaced them. `real-fx`
  is every non-`:raise` fx entry, preserved in order, to flow on to `do-fx`.
  `:raise` entries are never region-prefixed (`prefix-region-invoke-id` only
  touches `:rf/invoke-id`), so they arrive here verbatim."
  [fx]
  (reduce (fn [[raises real] [fx-id args :as entry]]
            (if (= :raise fx-id)
              [(conj raises args) real]
              [raises (conj real entry)]))
          [[] []]
          fx))

(defn- apply-region-transition
  "APPLY phase for ONE region: run `region-spec`'s
  PRE-SELECTED `match` (resolved in `broadcast-once`'s SELECT pass against the
  FROZEN pre-event view) against `region-snap`, whose `:data` has ACCUMULATED
  earlier regions' same-macrostep writes in declaration order — so the
  transition's ACTION sees the evolving `:data` while its GUARD selection was
  already frozen (rf2-lq5yo3).

  Crucially this applies ONE transition WITHOUT a region-local `:always`
  drain. The parallel parent owns eventless select-then-apply rounds across
  the complete configuration; raises remain ordinary fx for that parent to
  harvest."
  [region-spec region-snap event match phase]
  (try
    (rf.machines.transition/apply-preselected-transition
      region-spec region-snap event match phase)
    (catch #?(:clj Throwable :cljs :default) e
      (if (rf.machines.transition/guard-threw-signal? e)
        (rf.machines.result/fail (rf.machines.transition/guard-threw->fail-info e))
        (throw e)))))

(defn- broadcast-once
  "One full broadcast of `event` across every region of `machine` against
  `snapshot`, in two phases per Spec 005 §Transition broadcast (SELECT then
  APPLY — XState v5 / SCXML parity):

    SELECT — resolve EVERY region's enabled transition
    (`rf.machines.transition/pick-transition`) against ONE frozen pre-event view: `:data`,
    `:all-state` and `:tags` are all taken from `snapshot` BEFORE any region
    applies. So a later region's guard SELECTION never observes an earlier
    region's same-macrostep `:data` write — selection is DECLARATION-ORDER-
    INDEPENDENT (rf2-lq5yo3). Guard bodies run here, so their
    `:rf.machine/guard-evaluated` traces cluster ahead of the APPLY-phase
    action traces within the macrostep.

    APPLY — `reduce-regions` runs each region's PRE-SELECTED transition in
    declaration order, threading `:data` so a region's ACTION sees the values
    earlier regions wrote (the one value that flows). No region settles
    `:always` here; `drain-parent-queue` selects the complete eventless set
    from the fully-applied broadcast result.

  Regions DEFER their raises, so the returned Result's `fx` may carry surfaced
  `[:raise …]` entries for the parent loop to harvest. Returns the merged
  `rf.machines.result/ok` (carrying `::handled?` / `::microsteps` / `::cascade`) or the
  first region's `rf.machines.result/fail`. Each raised-event re-broadcast re-enters here
  with a FRESH `snapshot`, so it re-freezes the selection view against the
  config as of that microstep's start."
  [machine snapshot event]
  (let [state-map (:state snapshot)
        ;; Canonical declaration-order region iteration (never map `keys`) —
        ;; the SAME `ordered` `reduce-regions` folds over, so every region the
        ;; APPLY pass visits has a SELECT-pass match entry.
        ordered   (filterv #(contains? state-map %)
                           (region-order machine))
        ;; FROZEN pre-event SELECTION view — computed ONCE, shared by every
        ;; region's SELECT. `:data` joins `:all-state` / `:tags` in the freeze
        ;; (rf2-lq5yo3): the pre-event `:data` is what every region's guard
        ;; resolves against regardless of declaration order.
        frozen-data      (:data snapshot)
        frozen-all-state state-map
        frozen-tags      (compute-tags-parallel machine state-map)
        sc               (:rf/spawn-counter snapshot)
        hist             (:rf/history snapshot)
        ;; SELECT pass — per-region match against the frozen view.
        matches   (persistent!
                    (reduce
                      (fn [acc rn]
                        (let [region-spec (region-spec-overlaid machine rn)
                              region-snap (cond-> {:state     (get state-map rn)
                                                   :data      frozen-data
                                                   :all-state frozen-all-state
                                                   :tags      frozen-tags}
                                            (some? sc)   (assoc :rf/spawn-counter sc)
                                            (some? hist) (assoc :rf/history hist))]
                          (assoc! acc rn
                                  (rf.machines.transition/pick-transition
                                    region-spec
                                    (rf.machines.transition/state-path (get state-map rn))
                                    event region-snap))))
                      (transient {})
                      ordered))]
    ;; APPLY pass — declaration-order via `reduce-regions` (threads `:data` +
    ;; freezes `:all-state` / `:tags` into each region's ACTION ctx). Each
    ;; region applies its PRE-SELECTED match; it never re-selects the event
    ;; guard against the evolving `:data`.
    (reduce-regions machine snapshot
                    (fn [region-spec region-snap]
                      (apply-region-transition
                        region-spec region-snap event
                        (get matches (:rf/region region-spec)) nil)))))

(defn- select-always-matches
  "SELECT one eventless round for every active region against the SAME frozen
  whole-machine snapshot. Returns a region-name -> match map containing only
  enabled matches. No action runs here; in particular, no later region can
  observe an earlier region's writes while choosing this round."
  [machine snapshot]
  (let [state-map        (:state snapshot)
        frozen-data      (:data snapshot)
        frozen-all-state state-map
        frozen-tags      (compute-tags-parallel machine state-map)
        sc               (:rf/spawn-counter snapshot)
        hist             (:rf/history snapshot)]
    (persistent!
      (reduce
        (fn [acc rn]
          (let [region-spec (region-spec-overlaid machine rn)
                region-snap (cond-> {:state     (get state-map rn)
                                     :data      frozen-data
                                     :all-state frozen-all-state
                                     :tags      frozen-tags}
                              (some? sc)   (assoc :rf/spawn-counter sc)
                              (some? hist) (assoc :rf/history hist))
                match       (rf.machines.transition/pick-always-transition
                              region-spec
                              (rf.machines.transition/state-path (get state-map rn))
                              region-snap)]
            (cond-> acc match (assoc! rn match))))
        (transient {})
        (filterv #(contains? state-map %) (region-order machine))))))

(defn- apply-always-round
  "APPLY one preselected cross-region eventless round. Matches were selected
  by `select-always-matches` against one frozen snapshot; actions now run in
  canonical declaration order with `:data` accumulation. No region drains a
  local `:always` tail.

  Every taken regional transition gets its own rf.trace/cascade row, but all
  co-selected transitions share `round-index`; the returned Result counts the
  SET as exactly one `::microsteps` round."
  [machine snapshot matches round-index]
  (let [round-r
        (reduce-regions
          machine snapshot
          (fn [region-spec region-snap]
            (let [rn    (:rf/region region-spec)
                  match (get matches rn)
                  from  (:state region-snap)
                  step  (apply-region-transition
                          region-spec region-snap nil match :always)]
              (if (or (nil? match) (rf.machines.result/fail? step))
                step
                (rf.machines.result/with-ok [snap2 _] step
                  (rf.trace/emit! :rf.machine :rf.machine.microstep/transition
                               {:actor-id        (or (:rf/parent-id region-spec)
                                                     (:id region-spec))
                                :from            from
                                :to              (:state snap2)
                                :region          rn
                                :microstep-index round-index
                                :source          :always
                                :frame           (:rf/frame region-spec)})
                  (-> step
                      ;; The parent owns the count. `reduce-regions` must not
                      ;; sum one per selected region.
                      (rf.machines.result/with-microsteps 0)
                      (rf.machines.result/with-cascade
                        [{:kind            :microstep
                          :region          rn
                          :microstep-index round-index
                          :from            from
                          :to              (:state snap2)
                          :steps           (rf.machines.result/cascade step)}])))))))]
    (if (rf.machines.result/fail? round-r)
      round-r
      (rf.machines.result/with-microsteps round-r 1))))

;; ---- root parallel `:on` — the ancestor fallback --------------------------
;;
;; XState v5 / SCXML: a transition declared on a `<parallel>`
;; node is the ANCESTOR FALLBACK for its regions — deepest-wins with parent
;; fallthrough, the parallel analog of the machine-root `:on` fallback every
;; flat / compound machine already has (`rf.machines.transition/pick-transition` steps
;; 6-7) and of re-frame2's own compound-root `:on` fallthrough. The selection
;; semantic (verified against xstate@5.32.0):
;;
;;   1. The root parallel transition is selected ONLY when NO region-local
;;      transition was selected for the event. If ANY region handled it, the
;;      root transition is SUPPRESSED ENTIRELY (atomic, all-or-nothing — NOT
;;      "fire root for the regions that did not handle it"). This atomic
;;      ancestor-fallback suppression is the load-bearing semantic: it is the
;;      one behaviour a per-region-`:on` broadcast CANNOT decompose into (a
;;      multi-region root default that fires UNLESS any region competes, in
;;      which case the untargeted siblings stay UNCHANGED).
;;   2. If selected, the root transition atomically updates one OR MORE
;;      region-qualified targets, leaving UNtargeted regions UNCHANGED, after
;;      running the root `:action` ONCE against the shared `:data`.
;;   3. Target grammar: targetless / action-only; a single region-qualified
;;      target `[<region> & <in-region-path>]`; or multiple region-qualified
;;      targets `[[<region> …] [<region> …]]` (the XState `target ['.a.x',
;;      '.b.y']` analog).
;;
;; A user-written root-level `:on` on a `:type :parallel` machine is validated
;; and executed as the ancestor fallback: when no region handles the event, the
;; broadcast falls through to the root `:on`.
;;
;; COORDINATION: the root transition's GUARD is resolved against the
;; FROZEN pre-event `snapshot` (the value `parallel-machine-transition` was
;; called with) — NOT an evolving per-region snapshot — so root-parallel guard
;; selection is aligned to the same two-phase frozen-selection model the
;; region guards use.

(defn- normalise-root-targets
  "Normalise a root parallel transition's `:target` into a vector of
  region-qualified absolute targets `[[<region> & <in-region-path>] …]`. Per
  the grammar:
    - nil / absent  → `[]` (targetless / action-only).
    - a vector of KEYWORDS (`[:a :two]`) → ONE region-qualified target
      (head = region name, rest = the in-region path). Wrapped to `[[:a :two]]`.
    - a vector of VECTORS (`[[:a :x] [:b :y]]`) → MULTIPLE region-qualified
      targets, returned as-is.
  Shape is validated at registration (`validate-parallel!`); this is the
  runtime resolver mirror."
  [target]
  (cond
    (nil? target)             []
    (and (vector? target)
         (every? vector? target)) (vec target)
    (vector? target)          [target]
    :else                     []))

(defn- apply-root-region-target
  "Apply ONE region-qualified target `[<region> & <in-region-path>]` to the
  parallel machine: synthesise a root-relative `:target`-only transition
  against region `<region>`'s `region-machine` and run it through the
  single-machine `apply-transition-once`, so the region's exit / entry
  cascade, `:after` (re)scheduling, declarative `:spawn` / history all fire
  exactly as a region-local transition to that target would. The transition
  carries NO `:action` — the root action already ran once at the root level
  (`rf.machines.transition/run-root-transition-action`); a region target is a pure
  configuration change. Threads the shared `:data` / `:rf/spawn-counter` /
  `:rf/history` through `acc` `{:data :rf/spawn-counter :rf/history :state-map
  :fx}` (the same flow `reduce-regions` uses) and prefixes per-region fx with
  the region name. Returns the updated `acc`, or a `rf.machines.result/fail` (a region
  `:entry` / `:exit` action threw)."
  [parent-machine event acc [region-name & in-region-path]]
  (if (rf.machines.result/fail? acc)
    acc
    (let [region-spec (region-spec-overlaid parent-machine region-name)
          region-snap (cond-> {:state (get-in acc [:state-map region-name])
                               :data  (:data acc)}
                        (some? (:rf/spawn-counter acc))
                        (assoc :rf/spawn-counter (:rf/spawn-counter acc))
                        (some? (:rf/history acc))
                        (assoc :rf/history (:rf/history acc)))
          ;; Root-relative target WITHIN the region (`:decl-path []` → a
          ;; keyword/vector target resolves against the region root, exactly
          ;; like a region-root `:on` target). A single-element in-region path
          ;; is passed as a KEYWORD so `commit-snapshot` collapses the region's
          ;; new `:state` to a keyword (matching a flat region's snapshot
          ;; shape); a deeper path stays a vector (a compound region's path).
          in-region   (vec in-region-path)
          synthetic   {:target    (if (= 1 (count in-region))
                                    (first in-region)
                                    in-region)
                       :decl-path []}
          step        (rf.machines.transition/apply-transition-once
                        region-spec region-snap event synthetic :transition)]
      (if (rf.machines.result/fail? step)
        step
        (rf.machines.result/with-ok [reg-snap reg-fx] step
          (-> acc
              (assoc :data (:data reg-snap))
              (cond-> (some? (:rf/spawn-counter reg-snap))
                (assoc :rf/spawn-counter (:rf/spawn-counter reg-snap)))
              (cond-> (some? (:rf/history reg-snap))
                (assoc :rf/history (:rf/history reg-snap)))
              (assoc-in [:state-map region-name] (:state reg-snap))
              (update :fx into
                      (map (partial prefix-region-invoke-id region-name))
                      reg-fx)))))))

(defn- apply-root-parallel-transition
  "Apply the parallel machine ROOT's selected `:on` transition as the
  ancestor fallback: run the root `:action` ONCE against the
  shared `:data`, then atomically apply each region-qualified `:target` to
  its named region (leaving untargeted regions unchanged), and re-stamp the
  tag union. `transition` is the transition map `root-on-match` selected.
  Targets are applied in REGION-DECLARATION order (the canonical
  `region-order`, filtered to the targeted regions) so `:data` accumulation
  across multiple region targets is deterministic, consistent with
  `reduce-regions`. Returns
  a `rf.machines.result/ok` carrying `[merged-snapshot fx]` stamped `::handled? true` (the
  root transition resolved the event), or a `rf.machines.result/fail` if the root action
  or any region cascade threw."
  [machine snapshot event transition]
  (let [action-r (rf.machines.transition/run-root-transition-action machine snapshot transition event)]
    (if (rf.machines.result/fail? action-r)
      action-r
      (rf.machines.result/with-ok [snap-after-action action-fx] action-r
        (let [targets        (normalise-root-targets (:target transition))
              ;; Apply in canonical region-declaration order (`region-order`,
              ;; filtered to targeted regions) for deterministic `:data`
              ;; accumulation — never `:regions` / state-map key iteration.
              decl-order     (region-order machine)
              target-by-rn   (into {} (map (fn [t] [(first t) t])) targets)
              ordered-targets (->> decl-order
                                   (keep target-by-rn)
                                   vec)
              seed           {:data            (:data snap-after-action)
                              :rf/spawn-counter (:rf/spawn-counter snapshot)
                              :rf/history      (:rf/history snapshot)
                              :state-map       (:state snapshot)
                              :fx              (vec action-fx)}
              acc            (reduce (partial apply-root-region-target machine event)
                                     seed
                                     ordered-targets)]
          (if (rf.machines.result/fail? acc)
            acc
            (let [merged (cond-> (-> snapshot
                                     (assoc :state (:state-map acc))
                                     (assoc :data  (:data acc)))
                           (some? (:rf/spawn-counter acc))
                           (assoc :rf/spawn-counter (:rf/spawn-counter acc))
                           (some? (:rf/history acc))
                           (assoc :rf/history (:rf/history acc)))]
              (-> (rf.machines.result/ok (commit-tags-parallel machine merged) (:fx acc))
                  (rf.machines.result/with-handled true)))))))))

(defn- drain-parent-queue
  "Stabilize a parallel macrostep under the parent's unified eventless /
  internal-event loop. Enabled regional `:always` transitions are selected as
  one frozen set and applied as one round before the next region-surfaced
  `:raise` is re-broadcast FIFO across all regions,
  starting from the seed broadcast `first-r`, and return the merged
  Result. Shared by the parallel event MACROSTEP (`parallel-machine-
  transition`, seed = the external event's first broadcast) and the
  parallel BIRTH settle (`settle-birth`, seed = the post-initial-cascade
  configuration) — both reuse the identical parent loop.

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

  Returns a `rf.machines.result/ok` (snapshot + fx, with `::handled?` / `::microsteps`
  / `::cascade`) or the first region's `rf.machines.result/fail`."
  [machine snapshot event first-r]
  (let [raise-limit  (get machine :raise-depth-limit
                          rf.machines.transition/raise-depth-limit-default)
        always-limit (get machine :always-depth-limit
                          rf.machines.transition/always-depth-limit-default)]
    (if (rf.machines.result/fail? first-r)
      first-r
      (rf.machines.result/with-ok [snap0 fx0] first-r
        (let [[raises0 real0] (split-raises fx0)
              external-handled? (rf.machines.result/handled? first-r)]
          ;; `m` is the live parent machine threaded through the
          ;; re-broadcast loop. Each dequeued raise's `ensure-raised-cofx`
          ;; re-stamps it with an augmented `:rf/cofx` so the raised event's
          ;; region guards/actions read the ensured / generated facts (overlaid
          ;; onto each region spec by `region-spec-overlaid`). No-op for the
          ;; pure-fn engine (no `:rf/cofx`).
          (loop [cur-snap   snap0
                 m          machine
                 pending    (vec raises0)
                 acc-fx     real0
                 ;; `depth` counts internal events re-broadcast off the
                 ;; queue — symmetric with the flat drain's per-raise count
                 ;; and bounded by the SAME limit / `>=` boundary.
                 depth      0
                 acc-micro  (rf.machines.result/microsteps first-r)
                 acc-casc   (rf.machines.result/cascade first-r)
                 visited    [(:state snap0)]]
            (let [always-matches (select-always-matches m cur-snap)]
             (cond
               ;; Eventless transitions always win over the internal-event
               ;; queue. SELECT the complete enabled regional set against one
               ;; frozen snapshot, then APPLY it as one parent microstep.
               (seq always-matches)
               (if (>= acc-micro always-limit)
                 (let [info {:error-id :rf.error/machine-always-depth-exceeded
                             :actor-id (or (:rf/parent-id m) (:id m))
                             :depth acc-micro
                             :path visited
                             :frame (:rf/frame m)
                             :recovery :no-recovery}]
                   (rf.trace/emit-error! :rf.error/machine-always-depth-exceeded info)
                   (rf.machines.result/depth-abort info))
                 (let [round-r (apply-always-round
                                 m cur-snap always-matches acc-micro)]
                   (if (rf.machines.result/fail? round-r)
                     round-r
                     (rf.machines.result/with-ok [snap2 fx2] round-r
                       (let [[new-raises real-fx] (split-raises fx2)]
                         (recur snap2
                                m
                                (into pending new-raises)
                                (into acc-fx real-fx)
                                depth
                                (inc acc-micro)
                                (into acc-casc (rf.machines.result/cascade round-r))
                                (conj visited (:state snap2))))))))

               (empty? pending)
              (let [result (-> (rf.machines.result/ok cur-snap acc-fx)
                               (rf.machines.result/with-handled external-handled?)
                               (rf.machines.result/with-microsteps acc-micro)
                               (rf.machines.result/with-cascade acc-casc))]
                ;; Per Spec 005 §Transition broadcast: when EVERY region
                ;; declines the EXTERNAL event the machine emits a SINGLE
                ;; benign `:rf.machine.event/unhandled-no-op` (op-type
                ;; `:rf.machine`, info-grade, NOT an error — xstate-v5
                ;; parity; canonical id / op-type / tags per Spec 009
                ;; §`:op-type` vocabulary). Gated on the inbound `event`
                ;; (not any re-broadcast raise) and on
                ;; `rf.machines.transition/unhandled-event-no-op?` so reserved-`:rf/*`
                ;; framework lifecycle traffic — the synthetic bootstrap,
                ;; the spawn kick-off, the stories-runtime pings — is NOT
                ;; classified as an unknown-user-event no-op. On the BIRTH
                ;; path `event` is `nil`, so the
                ;; `unhandled-event-no-op?` gate is never reached — birth
                ;; never emits the no-op. Mirrors the flat-machine emission
                ;; in `rf.machines.transition/machine-transition-single`.
                (when (and (not external-handled?)
                           (some? event)
                           (rf.machines.transition/unhandled-event-no-op? event))
                  (rf.trace/emit! :rf.machine :rf.machine.event/unhandled-no-op
                               ;; A LIVE actor (every region
                               ;; declined) received the unknown event; address
                               ;; it by `:actor-id`, not `:machine-id` (the
                               ;; registered TYPE). Mirrors the flat emission.
                               {:actor-id   (or (:rf/parent-id machine) (:id machine))
                                :event      event
                                :state      (:state snapshot)
                                :frame      (:rf/frame machine)}))
                result)

              ;; `>=` boundary parity with the flat drain: the
              ;; loop re-broadcasts internal events at depths 0..limit-1
              ;; then aborts at `depth == limit`. Atomic rollback — the
              ;; WHOLE macrostep is discarded; no partial snapshot / fx
              ;; survives (Spec 005 §Drain semantics: bounded depth halts
              ;; with the snapshot uncommitted, `:no-recovery`).
              (>= depth raise-limit)
              ;; A tripped re-broadcast `:raise` depth limit is a
              ;; FAILED macrostep, not a benign no-op (parity with the flat
              ;; drain in `rf.machines.transition/drain-to-fixed-point`). Emit the precise
              ;; category, then return a `rf.machines.result/fail` carrying the
              ;; `::depth-abort?` sentinel so the runaway region raise cycle
              ;; routes through the handler's failure path (atomic rollback
              ;; preserved — no snapshot write reaches runtime-db) instead of
              ;; surfacing as a silent no-op that swallows the triggering event.
              (let [info {;; The aborting actor is a LIVE INSTANCE;
                          ;; address it by `:actor-id` (mirrors the flat drain),
                          ;; not `:machine-id` (the registered TYPE).
                          :error-id   :rf.error/machine-raise-depth-exceeded
                          :actor-id   (or (:rf/parent-id machine)
                                          (:id machine))
                          :depth      depth
                          :frame      (:rf/frame machine)
                          :recovery   :no-recovery}]
                (rf.trace/emit-error! :rf.error/machine-raise-depth-exceeded info)
                (rf.machines.result/depth-abort info))

              :else
              (let [ev   (first pending)
                    ;; Ensure THIS raised event's declared cofx
                    ;; onto the parent BEFORE re-broadcasting it across the
                    ;; regions. `ensure-set-for` unions every region's scope (and
                    ;; the parallel root's own `:on` / `:after`), so a region
                    ;; guard/action a raised event selects has its `:rf.cofx/
                    ;; requires` satisfied under the resolved mint policy. The
                    ;; augmented `:rf/cofx` overlays onto each region spec via
                    ;; `region-spec-overlaid`, and threads forward so a later
                    ;; re-broadcast re-presents the generated value.
                    m'    (rf.machines.transition/ensure-raised-cofx m cur-snap ev)
                    bstep (broadcast-once m' cur-snap ev)
                    ;; A raised internal event declined by EVERY region consults
                    ;; the parallel root's own `:on` as the ancestor fallback —
                    ;; mirroring the EXTERNAL-seed path (`root-fallback-seed` in
                    ;; `parallel-machine-transition`), the flat/compound drain
                    ;; (each dequeued raise selects against the machine-root `:on`
                    ;; fallback), and XState v6 / SCXML (a raised event selects
                    ;; against the FULL configuration incl. the parallel
                    ;; ancestor). Selection is against `cur-snap` — the config as
                    ;; of this microstep's start — the frozen view a raise sees.
                    ;; `root-fallback-seed` returns `bstep` UNCHANGED when a
                    ;; region handled it (atomic ancestor suppression), when the
                    ;; root declares no matching `:on`, or for reserved `:rf/*`
                    ;; framework traffic; when the root fires it applies the
                    ;; transition without local settling. The next parent-loop
                    ;; iteration selects `:always` across the complete moved
                    ;; configuration before any surfaced raises drain FIFO
                    ;; (rf2-x76af2.8). A `rf.machines.result/fail` short-circuits.
                    step  (if (rf.machines.result/fail? bstep)
                            bstep
                            (root-fallback-seed m' cur-snap ev bstep))]
                (if (rf.machines.result/fail? step)
                  step
                  (rf.machines.result/with-ok [snap2 fx2] step
                    (let [[new-raises real-fx] (split-raises fx2)
                          ;; rf2-nb8nj — group the rebroadcast's rows under ONE
                          ;; `:kind :raised-transition` boundary instead of
                          ;; flattening them straight into the accumulator.
                          ;; The geometry always survived here, but with no
                          ;; boundary and no trigger event its exit / action /
                          ;; entry rows were indistinguishable from the EXTERNAL
                          ;; event's — which is worse than losing them, because
                          ;; Xray's `handled-regions-from-cascade` treats any
                          ;; non-`:microstep` row's `:region` as evidence that
                          ;; the region handled the DISPATCHED event. A region
                          ;; that declined the external event and moved only on
                          ;; the raise was therefore misattributed and lit a
                          ;; phantom event edge.
                          ;;
                          ;; Identical shape and gate to the flat/compound drain
                          ;; (`rf.machines.transition/drain-to-fixed-point`) — one schema-
                          ;; approved wrapper across both engines, so a consumer
                          ;; traverses one thing. `:region` is nil: a raise is
                          ;; re-broadcast across EVERY region, so the boundary
                          ;; belongs to no single one; the nested `:steps` keep
                          ;; their own per-region stamps. `:from` / `:to` are the
                          ;; whole composite region-MAPs, matching the parallel
                          ;; trace's `:before` / `:after` shape.
                          ;;
                          ;; The synthetic `[:rf.machine/done <path>]` region
                          ;; completion signal enters this same queue, so it is
                          ;; represented by the same mechanism — no parallel
                          ;; done-state dialect.
                          acc-casc' (if (rf.machines.result/handled? step)
                                      (conj acc-casc
                                            {:kind   :raised-transition
                                             :region nil
                                             :event  ev
                                             :from   (:state cur-snap)
                                             :to     (:state snap2)
                                             :steps  (rf.machines.result/cascade step)})
                                      acc-casc)]
                      (recur snap2
                             m'
                             ;; FIFO: drop the just-processed
                             ;; front, APPEND this broadcast's own raises to
                             ;; the BACK — behind the still-pending queue.
                             (into (vec (rest pending)) new-raises)
                             (into acc-fx real-fx)
                             (inc depth)
                             (+ acc-micro (rf.machines.result/microsteps step))
                             acc-casc'
                             (conj visited (:state snap2)))))))))))))))

;; ---- parallel done-state / `:on-done` signal ------------------------------
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
  "Per Spec 005 §Final states §The done-state signal: if the
  parallel `machine`'s settled `result` snapshot has NEWLY reached its
  all-regions-final done configuration this macrostep AND the parallel root
  declares `:on-done`, run that `:on-done` transition's `:action` against
  `:data` and append its `:fx`, marking the Result so the lifecycle boundary
  does NOT auto-destroy. Returns the (possibly enriched) Result; a
  `rf.machines.result/fail` (the `:on-done` action threw) short-circuits. A `rf.machines.result/fail`
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
  all-final crosses the edge at birth). The guard ensures the `:on-done`
  `:action` + `:fx` fire once and do NOT re-fire on every no-op event the
  regions decline while resting all-final — so a coordinator's continuation
  is dispatched exactly once and `:data` does not accumulate on resting
  macrosteps."
  [machine result newly-reached?]
  (if (rf.machines.result/fail? result)
    result
    (let [on-done (:on-done machine)]
      (if (or (not (parallel? machine))
              (nil? on-done)
              (not newly-reached?)
              (not (all-regions-final? machine (:state (rf.machines.result/snap result)))))
        result
        (rf.machines.result/with-ok [snap fx] result
          ;; `:on-done` is an `:on`-shaped transition spec; a parallel root's
          ;; `:on-done` carries only `:action` / `:guard` / `:fx` (registration
          ;; rejects an in-machine `:target`). Run its `:action` against the
          ;; settled `:data` and append its fx, threading the standard
          ;; effects-map contract (`{:data .. :fx ..}`). A bare keyword /
          ;; map without `:action` is a no-op data-wise but still suppresses
          ;; auto-destroy (the author opted into "stay done, don't destroy").
          (let [on-done-r (rf.machines.transition/apply-on-done-action machine snap on-done)]
            (if (rf.machines.result/fail? on-done-r)
              on-done-r
              (rf.machines.result/with-ok [snap2 fx2] on-done-r
                (-> (rf.machines.result/ok snap2 (into (vec fx) fx2))
                    (rf.machines.result/with-parallel-done)
                    (rf.machines.result/with-handled (rf.machines.result/handled? result))
                    (rf.machines.result/with-microsteps (rf.machines.result/microsteps result))
                    (rf.machines.result/with-cascade (rf.machines.result/cascade result)))))))))))

(defn- root-fallback-seed
  "Per Spec 005 §Transition broadcast §Root parallel `:on`: when
  NO region handled `event` (the first broadcast `first-r` is not handled),
  consult the parallel ROOT's own `:on` as the ancestor fallback. The root
  transition is resolved against the FROZEN pre-event `snapshot`
  (frozen-selection coordination — the root guard sees the pre-event config),
  and only for a genuine UNKNOWN USER event (`unhandled-event-no-op?` —
  reserved `:rf/*` framework lifecycle traffic is NOT a candidate; its
  done / error / timer routing already runs through the region resolvers).

  Returns the SEED Result the macrostep should settle from:
   - the applied root-transition Result (handled, region targets moved, root
     `:action` run once) when the root `:on` matches; or
   - `first-r` UNCHANGED when no region handled the event and the root has no
     matching `:on` (the existing all-regions-declined no-op path then runs).

  Eventless stabilization is deliberately NOT run here. The parent loop sees
  the complete post-root configuration and owns the same frozen
  select-then-apply rounds used after regional events and at birth."
  [machine snapshot event first-r]
  (if (or (rf.machines.result/fail? first-r)
          (rf.machines.result/handled? first-r)
          (not (rf.machines.transition/unhandled-event-no-op? event)))
    first-r
    (if-let [t (rf.machines.transition/root-on-match machine event snapshot)]
      (apply-root-parallel-transition machine snapshot event t)
      first-r)))

(defn- root-after-seed
  "Per Spec 005 §Root parallel `:after`: resolve a parallel-ROOT
  `:after` firing — the synthetic `[:rf.machine.timer/after-elapsed delay-key
  epoch []]` event whose decl-path is the empty root path (NOT region-name
  prefixed). The root `:after` is the timer-driven analog of the root `:on`
  ancestor fallback: it reuses the SAME region-qualified target grammar
  (`apply-root-parallel-transition`), runs its `:action` once against the
  shared `:data`, atomically moves its region-qualified target(s), and leaves
  untargeted regions unchanged — but it is ROOT-OWNED (scheduled at machine
  birth, stale-gated by the root's own per-path epoch), so a region's
  transition never cancels it.

  Emits the same timer traces a state's `:after` does (`:rf.machine.timer/
  fired` on a live fire, `/stale-after` on an epoch-stale drop, `/fired
  :fired? false` on a guard-suppressed drop) via `rf.machines.transition/emit-pick-
  traces!`. Returns the SEED Result the macrostep drains from:
   - the applied root-`:after` transition Result (handled) on a live fire;
   - a no-op `(ok snapshot [])` on a stale / guard-suppressed timer (the
     transition does not fire — matching the single-machine `:after` drop).

  The returned seed is stabilized by the same parent-owned eventless/raise
  loop as every other parallel transition."
  [machine snapshot event]
  (let [match (rf.machines.transition/root-after-match machine event snapshot)]
    ;; Trace the firing / staleness / guard-suppression BEFORE applying, so
    ;; listeners observe events in occurrence order (single-machine parity).
    ;; Thread the causal completion timestamp (the firing
    ;; dispatch's router-stamped `:rf/time-ms` off `:rf.cofx`) so the
    ;; parallel-root `:after` completion carries `:completed-at` like the
    ;; single-machine path.
    (rf.machines.transition/emit-pick-traces! (:rf/frame machine) match
                                  (get-in machine [:rf/cofx :rf/time-ms]))
    (cond
      (or (nil? match) (:stale? match) (:guard-suppressed? match))
      (rf.machines.result/ok snapshot [])

      :else
      (apply-root-parallel-transition machine snapshot event
                                      (:transition match)))))

(defn- parallel-machine-transition
  "Pure function. Given a parallel-region machine, current snapshot, and
  event, run the parallel MACROSTEP — broadcast the event to every region,
  then run the parent's eventless-round / internal-event loop (region-
  surfaced `:raise`s re-broadcast FIFO across all regions) to a fixed point, and
  return the merged result. Returns a `rf.machines.result/ok` Result on success or a
  `rf.machines.result/fail` Result if any region's action threw.

  Per Spec 005 §Transition broadcast §Root parallel `:on`: when
  NO region selects a transition for the event, the parallel ROOT's own `:on`
  is consulted as the ANCESTOR FALLBACK (deepest-wins with parent fallthrough
  — the parallel analog of the flat / compound machine-root `:on` fallback).
  A region match SUPPRESSES the root transition entirely (atomic ancestor
  fallback — `root-fallback-seed` keys off `first-r`'s `::handled?`). When the
  root transition fires it runs its `:action` once and atomically moves one or
  more region-qualified targets, leaving untargeted regions unchanged; the
  complete moved configuration then settles through the same parent loop. The
  root transition's GUARD is selected against the
  frozen pre-event snapshot (frozen-selection coordination).

  Per Spec 005 §Transition broadcast (select-then-apply): each
  region's enabled transition is SELECTED against ONE frozen pre-broadcast
  snapshot (its own deepest-wins lookup, reading siblings via the frozen
  `:all-state` / `:tags`), then the selected transitions are APPLIED in
  region-declaration order; resolved regions transition, undeclined regions
  stay put. Declaration order governs only the deterministic apply (action /
  fx order + `:data` accumulation), NEVER which sibling transitions are
  selected. After the complete event set applies, parent-owned `:always`
  rounds repeat that frozen SELECT / ordered APPLY law to quiescence. The
  `:data` slot is the one value that flows — each region's
  actions see the prior region's `:data` writes in declaration order; the
  cross-region `:all-state` / `:tags` a guard OR action reads stay frozen
  (statechart atomicity). The broadcast invariant lives in
  `reduce-regions`.

  Per Spec 005 §Parallel-region `:raise` broadcast (XState v5
  parity): a `:raise` emitted by ANY region is NOT region-local. It enters
  this macrostep's internal-event queue and is re-broadcast across EVERY
  region — exactly what an equivalent self-`[:dispatch [<self-id> …]]` would
  broadcast, but pre-commit and inside the one macrostep. Each re-broadcast
  re-selects against the config as of that queue step's start (a fresh frozen
  `:all-state` / `:tags` view that reflects prior work) while the in-flight
  `:data` flows. Eventless rounds run before the next dequeue. Raises are drained FIFO: a raise
  surfaced earlier is re-broadcast before one surfaced later, and a raise
  emitted *while handling* a re-broadcast goes to the BACK of the queue.
  The whole macrostep — external event + every re-broadcast internal event
  + every parent-owned `:always` round — commits ONCE, atomically.
  Bounded by the parent `:raise-depth-limit` (default 16); on exceed the
  macrostep rolls back wholesale (original snapshot, no fx) and emits
  `:rf.error/machine-raise-depth-exceeded`, matching the single-machine
  drain.

  For the synthetic `[:rf.machine.timer/after-elapsed ...]` event,
  delivery is region-scoped — the broadcast routes to the bearing region
  only, identified by the region-name prefix on the in-flight timer's
  `:rf/invoke-id`. The exception is a ROOT-OWNED `:after`,
  whose carried decl-path is the empty root path `[]` (no region prefix):
  `root-after-elapsed?` detects it and routes it to `root-after-seed` (the
  timer-driven analog of the root `:on` ancestor fallback) instead of
  broadcasting it to the regions."
  [machine snapshot event]
  (let [;; A parallel-ROOT `:after` timer (`[]` decl-path) is
        ;; root-owned, not region-scoped: resolve it through the root `:on`
        ;; apply path rather than broadcasting it to the regions (which key off
        ;; a region-name-prefixed decl-path and would all decline it).
        root-after?   (rf.machines.transition/root-after-elapsed? event)
        first-r       (if root-after?
                        (root-after-seed machine snapshot event)
                        (broadcast-once machine snapshot event))
        ;; Root parallel `:on` ancestor fallback. When no region
        ;; handled the event, consult the root `:on`; if it fires, its result
        ;; (handled, region targets moved) BECOMES the macrostep seed and is
        ;; settled through the same parent queue (so a moved region's `:always`
        ;; / `:raise` continue the macrostep). When no region handled AND the
        ;; root declines, `seed` == `first-r` and the all-regions-declined
        ;; no-op path runs.
        ;; A root-`:after` firing already IS the root-owned seed, so the `:on`
        ;; fallback is skipped (the `:after` reserved-namespace event is not an
        ;; unhandled user event `root-fallback-seed` would consult `:on` for).
        seed          (if root-after?
                        first-r
                        (root-fallback-seed machine snapshot event first-r))
        settled       (drain-parent-queue machine snapshot event seed)
        ;; false→true EDGE guard. `:on-done` fires ONCE, on the
        ;; macrostep that CROSSES into the all-regions-final done config
        ;; (XState v5 `onDone` / SCXML `done.state.<id>`), NOT on every later
        ;; event delivered while resting there. A no-op event to an already-
        ;; all-final machine has before == all-final, so the edge is false and
        ;; `:on-done` does not re-fire.
        was-final?    (all-regions-final? machine (:state snapshot))
        newly-final?  (and (not was-final?)
                           (not (rf.machines.result/fail? settled))
                           (all-regions-final? machine (:state (rf.machines.result/snap settled))))]
    ;; Per Spec 005 §Final states §The done-state signal: when this
    ;; macrostep NEWLY makes every region final and the parallel root declares
    ;; `:on-done`, fire it (run its action + emit its fx) WITHOUT auto-
    ;; destroying — the transitionable parallel completion signal. No
    ;; `:on-done` ⇒ the Result passes through and the lifecycle boundary's
    ;; whole-machine finalize runs (D7).
    (fire-parallel-on-done machine settled newly-final?)))

(defn machine-transition
  "Pure function. Given a machine definition, current snapshot, and event,
  return the engine's `re-frame.machines.result` Result — `rf.machines.result/ok`
  carrying the new snapshot and effects vector (plus the engine-internal
  `::handled?` / `::microsteps` / `::cascade` riders the lifecycle handler
  reads), or `rf.machines.result/fail` carrying diagnostic info if any guard / action
  / `:data`-fn threw or a depth limit tripped. This is the engine seam;
  the PUBLIC `re-frame.machines/machine-transition` wraps it and projects
  the Result onto the plain Spec 005 §Level 1 map.

  Per Spec 005 §Drain semantics §Level 3, this is the macrostep:
   1. Pick the matching transition for the event using deepest-wins
      resolution along the state path.
   2. Run the exit cascade → transition's action → entry cascade.
   3-5. Settle in the unified SCXML microstep loop — after the taken
      transition PREFER enabled :always (eventless) transitions and only
      dequeue the next raised internal event (FIFO) once :always is
      quiescent; commit at the fixed point.

  Bounded by :raise-depth-limit and :always-depth-limit (both default 16).
  Parallel-region machines (`:type :parallel`) are dispatched into
  `parallel-machine-transition`, where the event is broadcast across
  regions per Spec 005 §Parallel regions and region-emitted
  raises re-broadcast through the parent macrostep's internal-event queue.
  Flat / compound machines drop straight into the
  single-machine engine in `re-frame.machines.transition`.

  ## Guard-throw → `rf.machines.result/fail` (XState v5 alignment)

  A user GUARD body that throws during transition selection surfaces the
  error and ABORTS the macrostep (XState v5 does not swallow guard
  exceptions). The throw rides up as a tagged signal
  (`rf.machines.transition/guard-threw-signal?`) from the candidate-walk; this single
  pure-engine boundary catches it ONCE and converts it to a `rf.machines.result/fail`,
  so a guard throw routes through the SAME failed-macrostep / atomic-
  rollback surface a thrown ACTION (and a bounded-depth abort) already
  takes. The original exception rides in the failure `::info` so the
  lifecycle handler emits the machine-scoped error trace and rolls back —
  the guard throw is never demoted to a lower-priority candidate."
  [machine snapshot event]
  ;; Desugar `:timeout` / `:on-timeout` into equivalent `:after` entries and
  ;; `:type :choice` / `:choice` into the
  ;; equivalent `:always` candidate vector BEFORE the engine sees the spec —
  ;; both are distinct authoring concepts that LOWER onto an existing
  ;; mechanism (`:timeout` → `:after`; `:choice` → `:always`). Idempotent,
  ;; so a spec already desugared at registration is unaffected; this seam
  ;; also covers the conformance `:machine-transition` op, which passes the
  ;; RAW (pre-registration) definition straight here — so normalise the region
  ;; declaration order (idempotent for an already-registered machine) alongside
  ;; the desugars, so `region-order` is canonical before the parallel engine
  ;; reads it.
  (let [machine (normalise-region-order
                  (rf.machines.choice/desugar-choices (rf.machines.timeout/desugar-timeouts machine)))]
    (try
      (if (parallel? machine)
        (parallel-machine-transition machine snapshot event)
        (rf.machines.transition/machine-transition-single machine snapshot event))
      (catch #?(:clj Throwable :cljs :default) e
        (if (rf.machines.transition/guard-threw-signal? e)
          (rf.machines.result/fail (rf.machines.transition/guard-threw->fail-info e))
          (throw e))))))

;; ---- birth-time `:always` + raise settle ----------------------------------
;;
;; XState v5 / SCXML: the INITIAL macrostep is initial-entry
;; + the eventless (`always`) drain. `createActor(m).start()` evaluates
;; `always` on the entered initial state(s); if an `always` guard already
;; holds, the actor settles PAST the initial leaf with NO external event —
;; the transient initial leaf is never externally observed. re-frame2's own
;; Spec 005 promises this ("fire as soon as the machine starts" = the
;; initial cascade into a leaf whose `:always` fires); `settle-birth` runs
;; the SAME raise-drain + `:always` fixed-point loop the event macrostep
;; uses, immediately after the entry cascade and BEFORE the birth commit,
;; for BOTH birth paths (eager `[:rf.machine/start]` + lazy first-event).

(defn- settle-birth
  "Run the birth-time settle — raise drain + `:always` fixed-point loop —
  against the POST-initial-cascade Result, BEFORE the machine is committed.
  The shared settling tail of the macrostep, reused so a
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
  `rf.machines.transition/drain-to-fixed-point` directly with `entry-r` (its boot
  snapshot + entry fx).

  For parallel-region machines, `drain-parent-queue` starts directly from the
  complete post-entry configuration. It runs the identical parent-owned
  eventless select-then-apply loop used after an event: every enabled region
  is selected against one frozen whole-machine snapshot, the set applies in
  declaration order, and the loop repeats before any queued initial-entry
  raise is dequeued.

  Returns a `rf.machines.result/ok` carrying the settled snapshot + fx (with
  `::microsteps` = the count of `:always` iterations and `::cascade` = the
  `:always`-microstep steps; the caller prepends the entry cascade), or a
  `rf.machines.result/fail` if an `:always` action or a drained raise threw. The
  atomic-rollback target on `:always`/`:raise`-depth abort is the
  post-cascade `boot-snapshot` (the initial configuration is the committed
  state — only a runaway settle is abandoned)."
  [machine entry-r]
  (rf.machines.result/with-ok [boot-snapshot entry-fx] entry-r
    (if (parallel? machine)
      (let [;; The entry cascade is prepended by the caller. Seed this settle
            ;; with only its snapshot/fx so it contributes eventless rows once.
            first-r (-> (rf.machines.result/ok boot-snapshot (vec entry-fx))
                        (rf.machines.result/with-handled false)
                        (rf.machines.result/with-microsteps 0)
                        (rf.machines.result/with-cascade []))]
        ;; A parallel machine BORN all-regions-final (each
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
      (rf.machines.transition/drain-to-fixed-point
        machine
        ;; Seed with the entry Result's fx so an initial-`:entry` `[:raise ...]`
        ;; drains FIFO inside the birth macrostep instead of escaping to the
        ;; outbound fx layer (bz0ox.1).
        (rf.machines.result/ok boot-snapshot (vec entry-fx))
        0
        false))))

(declare apply-initial-entry-cascade*)

(defn apply-initial-entry-cascade
  "The machine's INITIAL MACROSTEP (XState v5 / SCXML parity):
  the initial-entry cascade THEN the eventless (`:always`) + raise settle,
  composed and returned as ONE Result. The single birth site for BOTH
  paths (eager `[:rf.machine/start]` and lazy first-event); the lifecycle
  handler's `maybe-boot` calls it once.

   1. `run-initial-cascade` — the initial-descent `:entry` actions
      (per-region broadcast for parallel machines).
   2. `settle-birth` — the raise drain + `:always` fixed-point loop on the
      post-cascade snapshot, BEFORE commit, so a transient initial leaf
      whose `:always` guard already holds settles past, unobserved.

  Returns a `rf.machines.result/ok` carrying the settled snapshot + the (drained) entry
  fx ++ any settle fx, with `::cascade` = the entry cascade ++ the `:always`
  microsteps (so the `:rf.machine/transition` / `:rf.machine/started`
  trace surfaces both). A `rf.machines.result/fail` from either phase short-circuits.

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
  re-stamped.

  Guard-throw → `rf.machines.result/fail` (XState v5 alignment): a guard
  body that throws during the birth-time `:always` selection surfaces the
  error and aborts the birth macrostep through the SAME failed-macrostep
  surface a thrown initial-`:entry` action takes. The tagged guard-throw
  signal (`rf.machines.transition/guard-threw-signal?`) is caught at this birth
  boundary — the second pure-engine entry point alongside
  `machine-transition` — and converted to a `rf.machines.result/fail`."
  [machine initial-snapshot]
  ;; Desugar `:timeout` / `:on-timeout` so an initial state's
  ;; timeout arms at birth via the same `:after`-schedule fx the cascade
  ;; emits, and `:type :choice` / `:choice` so a transient
  ;; INITIAL choice leaf resolves on start via the birth-time `:always`
  ;; settle. Idempotent — a spec already desugared at registration is
  ;; unaffected. Mirrors the desugar + region-order normalisation at the
  ;; `machine-transition` entry so the birth cascade reads canonical order.
  (let [machine (normalise-region-order
                  (rf.machines.choice/desugar-choices (rf.machines.timeout/desugar-timeouts machine)))]
    (try
      (apply-initial-entry-cascade* machine initial-snapshot)
      (catch #?(:clj Throwable :cljs :default) e
        (if (rf.machines.transition/guard-threw-signal? e)
          (rf.machines.result/fail (rf.machines.transition/guard-threw->fail-info e))
          (throw e))))))

(defn- apply-initial-entry-cascade*
  "Inner body of `apply-initial-entry-cascade` — wrapped by it for the
  guard-throw → `rf.machines.result/fail` conversion at the birth boundary."
  [machine initial-snapshot]
  (let [entry-r (run-initial-cascade machine initial-snapshot)]
    (if (rf.machines.result/fail? entry-r)
      entry-r
      (let [settle-r (settle-birth machine entry-r)]
        (if (rf.machines.result/fail? settle-r)
          settle-r
          (rf.machines.result/with-ok [settled-snap settle-fx] settle-r
            ;; `settle-fx` already = entry (non-raise) fx ++ settle fx, with
            ;; the initial-`:entry` raises drained internally (bz0ox.1).
            ;; Cascade: entry cascade ++ the `:always` microstep cascade.
            ;; `::microsteps` rides from the settle (the entry cascade ran no
            ;; `:always`). The settle's `parallel-done-handled?`
            ;; flag (set when a parallel machine born all-final fired its root
            ;; `:on-done`) rides through so the birth finalize gate sees it.
            (cond-> (-> (rf.machines.result/ok settled-snap (vec settle-fx))
                        (rf.machines.result/with-cascade
                          (into (vec (rf.machines.result/cascade entry-r))
                                (rf.machines.result/cascade settle-r)))
                        (rf.machines.result/with-microsteps (rf.machines.result/microsteps settle-r)))
              (rf.machines.result/parallel-done-handled? settle-r) (rf.machines.result/with-parallel-done))))))))

;; ---- destroy-time exit cascade --------------------------------------------
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
  per-region step delegates to `rf.machines.transition/run-active-exit-cascade`).
  For flat / compound machines, drops straight into the single-machine
  helper.

  Returns a `re-frame.machines.result/Result` carrying the post-cascade
  snapshot + accumulated fx, or a `rf.machines.result/fail` if any region's `:exit`
  action threw."
  [machine snapshot]
  (if (parallel? machine)
    ;; Normalise region order (idempotent for a registered machine) — the
    ;; destroy exit cascade is a public engine entry NOT behind the transition
    ;; desugar seam, so canonicalise here too before `reduce-regions` reads it.
    (reduce-regions (normalise-region-order machine) snapshot
                    rf.machines.transition/run-active-exit-cascade)
    (rf.machines.transition/run-active-exit-cascade machine snapshot)))
