(ns re-frame.machines.lifecycle-fx.resolver
  "Spawned-actor SPEC resolution from runtime-db — the leaf helper
  beneath the lazy actor-handler resolver.

  Per Spec 005 §Spawning §Liveness is derived from runtime-db: a spawned
  actor has NO per-instance event-handler registration. Spawn is a pure
  runtime-db write (install the snapshot + the spawn-registry slot); destroy
  is a pure runtime-db remove. An actor's liveness IS exactly the presence
  of its snapshot at `[:rf.runtime/machines :snapshots <actor-id>]` in
  the frame's value — so `restore-epoch!` (which reverts the WHOLE
  frame-state, both the app-db AND runtime-db partitions, via
  `replace-frame-state!` against the epoch's `:frame-state-after`) reverts
  liveness perfectly, with ZERO registrar drift. Revertibility holds
  exactly: rewinding past a spawn leaves no orphaned handler, and rewinding
  past a destroy re-materialises a working handler from the restored
  snapshot.

  The actor's TYPE rides the snapshot at `[:rf/runtime :machine-type]` (per
  Spec 005 §Reserved snapshot-internal keys): the registered TYPE keyword. The type is registered like a singleton
  (`reg-machine`) and outlives every instance, so the resolver reads the live
  spec back from the registrar, and the snapshot stays EDN.

  This namespace is a LEAF over the machine-core grammar — it requires
  `registrar` + `paths` plus the pure `grammar` (state-tree descent) and
  `parallel` (the `:type :parallel` predicate) for `spawn-spec-at`. None of
  those require any `lifecycle-fx` namespace (`grammar` is require-free;
  `parallel` requires only engine-core `choice` / `result` / `timeout` /
  `transition` / `trace`), so the destroy-path consumers (`exit-cascade`,
  `finalize`, `spawn-error`) may require it without a load cycle through
  `registration`. The handler-meta side of the resolver, which wraps the
  type registration's handler for a spawned actor, lives in
  `lifecycle-fx.registration/resolve-actor-handler-meta`, the late-bound
  `:machines/resolve-actor-handler-meta` hook body."
  (:require [re-frame.interop :as rf.interop]
            [re-frame.machines.error-emit :as rf.machines.error-emit]
            [re-frame.machines.grammar :as rf.machines.grammar]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.registrar :as rf.registrar]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

(defn registration-from-registry
  "The `:event` registrar entry of the registered machine `machine-id` — the
  map `reg-machine*` installed, its `:handler-fn` beside the `:rf/machine?` /
  `:rf/machine` stamp — or nil when `machine-id` does not name a registered
  machine (a plain event handler, or no entry at all)."
  [machine-id]
  (let [m (rf.registrar/lookup :event machine-id)]
    (when (:rf/machine? m)
      m)))

(defn spec-from-registry
  "Resolve a registered machine's SPEC from `machine-id` via the `:event`
  registrar's `:rf/machine?` / `:rf/machine` stamp (Spec 005 §Querying
  machines). Returns the spec map, or nil when `machine-id` does not name
  a registered machine (a plain event handler, or no entry at all).

  This is the canonical one-liner over the
  `(let [m (rf.registrar/lookup :event id)] (when (:rf/machine? m) (:rf/machine m)))`
  idiom that several lifecycle-fx + querying call sites repeat — the
  registered-TYPE leg of spawned-actor / parent / singleton spec
  resolution."
  [machine-id]
  (:rf/machine (registration-from-registry machine-id)))

(defn registration-from-snapshot
  "The registrar entry of a spawned actor's TYPE, named by its `snapshot`'s
  `[:rf/runtime :machine-type]` slot — the registration whose handler every
  message to the actor runs (`lifecycle-fx.registration/resolve-actor-handler-meta`).
  Nil exactly when `spec-from-snapshot` is nil."
  [snapshot]
  (let [t (get-in snapshot [:rf/runtime :machine-type])]
    (when (keyword? t)
      (registration-from-registry t))))

(defn spec-from-snapshot
  "Resolve the machine SPEC for a spawned actor from its `snapshot`'s
  `[:rf/runtime :machine-type]` slot (per Spec 005 §Reserved
  snapshot-internal keys): the registered TYPE keyword, read back through the
  registrar's `:rf/machine` metadata (the type is registered like a singleton
  and outlives instances), or nil.

  Returns nil when the snapshot carries no `[:rf/runtime :machine-type]` (a singleton
  snapshot) or when the type no longer names a registered machine (the type
  was cleared — a genuine missing reference)."
  [snapshot]
  (:rf/machine (registration-from-snapshot snapshot)))

;; ---- the spawned-actor identity envelope ---------------------------------
;;
;; The canonical catalogue of the `:rf/runtime` slots that constitute a
;; spawned actor's IDENTITY, as distinct from its authored state/data and the
;; runtime's counters. Spawn stamps them (`install-spawn!`); snapshot
;; compatibility recovery (`lifecycle-fx.registration/rebuild-incompatible-snapshot`)
;; carries them across. Both sides read this ONE catalogue so they cannot
;; disagree about which values are durable actor identity.
;;
;; `:machine-type` in particular is what makes a spawned actor's liveness a
;; pure function of runtime-db (Spec 005 §Liveness is derived from
;; runtime-db): it is the ONLY key `registration-from-snapshot` can resolve an
;; actor's handler from, since a spawned actor has no per-instance registrar
;; entry. An actor stops being addressable by being DESTROYED — never by
;; having its definition change underneath it.
;;
;; Deliberately NOT here: the counters (`:spawn-counter`, `:spawn-attempts`,
;; `:after-epoch`, `:after-epoch-by-region`) and the SPAWNING side's
;; `:spawned` map. Those are bookkeeping tied to the definition that produced
;; them, and a compatibility reset means restarting the machine — they reset
;; with the snapshot.

(def actor-identity-keys
  "The `:rf/runtime` slots that carry a spawned actor's identity and lineage —
  its registered TYPE, its own address, its owner's address, the invocation
  path it was spawned from, (for a `:spawn-all` child) its private
  exact-attempt join membership, and (for a single `:spawn` child) the spawn
  attempt it belongs to and that attempt's work generation. Each is present
  only on the spawn flavour that stamps it; a singleton carries none."
  [:machine-type :self-id :parent-id :invoke-id :join-child :invoke-attempt
   :work-generation])

(defn carry-actor-identity
  "Copy the spawned-actor identity envelope from `prev`'s `:rf/runtime` onto
  `next`'s. `next` is a freshly-derived initial snapshot; `prev` is the
  snapshot being replaced. A SINGLETON `prev` carries none of these keys, so
  this is exactly a no-op there.

  This preserves identity ONLY — authored `:state` / `:data` and the runtime
  counters come from `next`, so a compatibility reset still means 'restart
  this machine', not 'patch it'."
  [prev next]
  (let [identity (select-keys (:rf/runtime prev) actor-identity-keys)]
    (cond-> next
      (seq identity) (update :rf/runtime merge identity))))

(defn spec-from-id-or-snapshot
  "Resolve a machine SPEC for `id` (a singleton's registered handler key,
  a spawned actor's instance address, or a spawning parent's id) preferring
  the registered TYPE, falling back to the `snapshot`'s `[:rf/runtime :machine-type]`:

    (or (spec-from-registry id) (spec-from-snapshot snapshot)).

  The two legs are mutually exclusive for any one id — a registered
  singleton has a registrar entry but no `[:rf/runtime :machine-type]` on
  its snapshot, while a spawned actor carries no per-instance registrar
  entry but stamps `[:rf/runtime :machine-type]` on its snapshot — so the `or` order
  is behaviourally irrelevant and either leg resolves at most one spec.
  Returns nil when neither resolves (the actor was already torn down, or
  the id names a non-machine event)."
  [id snapshot]
  (or (spec-from-registry id)
      (spec-from-snapshot snapshot)))

(defn resolvable?
  "True iff the actor identified by `actor-id` in `db` resolves to a live
  machine spec via its snapshot's `[:rf/runtime :machine-type]` — i.e. its liveness
  can be re-materialised purely from `db`. Used by the epoch restore
  precondition check (`:rf.epoch/restore-missing-handler`): a spawned
  actor whose TYPE is still registered is a VALID restore target even though no
  per-instance handler is registered. Returns false when the actor has
  no snapshot, or its snapshot carries no resolvable `[:rf/runtime :machine-type]`."
  [db actor-id]
  (boolean
    (some-> (get-in db (rf.machines.paths/snapshot-path actor-id))
            (spec-from-snapshot))))

(defn- invoke-node-at
  "Walk `parent-spec`'s state tree to the node at `invoke-id` (the absolute
  prefix-path stamped at spawn time), resolving flat AND region-prefixed
  invoke paths through `rf.machines.grammar/node-at`. For a parallel-region
  parent the first element of `invoke-id` is the region name; strip it and
  descend into that region's body. The empty `invoke-id` names the machine
  root. Returns nil if `parent-spec` is absent, `invoke-id` is not a vector,
  or the path doesn't resolve."
  [parent-spec invoke-id]
  (cond
    (not (and parent-spec (vector? invoke-id))) nil
    (empty? invoke-id)                          parent-spec
    :else
    (let [[head & tail] invoke-id
          [tree path]   (if (and (rf.machines.parallel/parallel? parent-spec)
                                 (contains? (:regions parent-spec) head))
                          [(get-in parent-spec [:regions head]) (vec tail)]
                          [parent-spec invoke-id])]
      (rf.machines.grammar/node-at (:states tree) path))))

(defn spawn-spec-at
  "The `:spawn` map of the node at `invoke-id` in `parent-spec` (see
  `invoke-node-at` for flat and region-prefixed paths; the empty `invoke-id`
  names the machine root, whose `:spawn` is its machine-lifetime child).
  Returns nil if the path doesn't resolve or the node declares no `:spawn`.

  Shared owner for the spawn-spec-at-invoke-id lookup used by the parent
  boundary's `:on-done` routing (`lifecycle-fx.registration`). The `:on-error`
  routing needs no lookup here: the failure carrier is dispatched whatever the
  parent declares, and the parent's engine resolves
  `:on-error` itself (`transition/pick-spawn-error-transition`)."
  [parent-spec invoke-id]
  (:spawn (invoke-node-at parent-spec invoke-id)))

(defn spawn-all-spec-at
  "The `:spawn-all` map of the node at `invoke-id` in `parent-spec` — the
  join's `:children`, `:join` and resolution events as the parent's CURRENT
  definition declares them — or nil when the path doesn't resolve or the node
  declares no `:spawn-all`. The `:spawn-all` sibling of `spawn-spec-at`, read
  by the join fold (`lifecycle-fx.join`), so join callbacks and configuration
  follow hot reload exactly as a single `:spawn`'s `:on-done` does."
  [parent-spec invoke-id]
  (:spawn-all (invoke-node-at parent-spec invoke-id)))

(defn apply-on-done
  "Run ONE `:on-done` completion fold against a parent's `:data`, returning the
  new `:data` map.

  This is the SINGLE application site for the `:on-done` contract, shared by
  both spawn forms — a `:spawn` parent's `:on-done` (applied at the parent's
  handler boundary when the reserved completion carrier arrives) and a
  `:spawn-all` child spec's per-child `:on-done` (applied by the join fold at
  that child's successful finality only). Per Spec 005 §Final states the
  callback receives one context-map arg and returns the new `:data` map:

      (fn [{:keys [data result]}] new-data)

  `on-done` may be nil (the common case — no fold declared), in which case
  `data` rides through untouched.

  A throwing fold is CONTAINED: the parent's `:data` is left exactly as it was
  and the completion still lands, because a presentation callback must not be
  able to hang a parent phase or a join. The throw is reported on the two
  standard axes — a STRUCTURAL always-on `:rf.error/machine-action-exception`
  record whose `:failing-id` is the reserved `:rf.machine.spawn/on-done` slot
  id, plus a dev-only prose trace behind an explicit call-site
  `rf.interop/debug-enabled?` gate so Closure constant-folds the prose away
  under `:advanced` + `goog.DEBUG=false`. `err-ctx` supplies the reporting
  coordinates (`:actor-id` — the PARENT actor whose `:data` was being folded —
  plus optional `:state`, `:frame`, `:child-id`)."
  [on-done data result {:keys [actor-id state frame child-id]}]
  (if on-done
    (let [new-data (try
                     (on-done {:data data :result result})
                     (catch #?(:clj Throwable :cljs :default) e
                       ;; Axis 1 — STRUCTURAL-ONLY always-on record (no prose;
                       ;; see error-emit). `:state` is the state the parent
                       ;; rests on as its `:on-done` fired.
                       (rf.machines.error-emit/emit-machine-action-exception!
                         {:actor-id   actor-id
                          :failing-id :rf.machine.spawn/on-done
                          :state      state
                          :frame      frame
                          :recovery   :no-recovery
                          :exception  e})
                       ;; Axis 2 — dev-only trace, call-site gated so the
                       ;; PROSE `:reason` / `:exception-message` are elided
                       ;; from a production build rather than merely unused.
                       (when rf.interop/debug-enabled?
                         (rf.trace/emit-error! :rf.error/machine-action-exception
                           (cond-> {:actor-id   actor-id
                                    :machine-id actor-id
                                    :action-id  :rf.machine.spawn/on-done
                                    :failing-id :rf.machine.spawn/on-done
                                    :state      state
                                    :frame      frame
                                    :exception  e
                                    :exception-message
                                    #?(:clj  (.getMessage ^Throwable e)
                                       :cljs (.-message e))
                                    :reason     ":on-done callback threw."
                                    :recovery   :no-recovery}
                             (some? child-id) (assoc :child-id child-id))))
                       nil))]
      (if (some? new-data) new-data data))
    data))
