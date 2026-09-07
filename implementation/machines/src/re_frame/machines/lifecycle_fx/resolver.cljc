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

  The actor's TYPE rides the snapshot under the reserved root key
  `:rf/machine-type` (per Spec 005 §Reserved snapshot-internal keys):

    - a `:machine-id` spawn stores the registered TYPE keyword — the type
      is registered like a singleton (`reg-machine`) and outlives every
      instance, so the resolver reads the live spec back from the
      registrar;
    - an inline `:definition` spawn stores the spec map directly on the
      snapshot — there is no registered type, so the snapshot is the only
      source of truth (and it is fully revertible).

  This namespace is a LEAF over the machine-core grammar — it requires
  `registrar` + `paths` plus the pure `grammar` (state-tree descent) and
  `parallel` (the `:type :parallel` predicate) for `spawn-spec-at`. None of
  those require any `lifecycle-fx` namespace (`grammar` is require-free;
  `parallel` requires only engine-core `choice` / `result` / `timeout` /
  `transition` / `trace`), so the destroy-path consumers (`exit-cascade`,
  `finalize`, `spawn-error`) may require it without a load cycle through
  `registration`. The handler-MATERIALISING side of the resolver (which needs
  `make-machine-handler`) lives in
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
  (let [m (rf.registrar/lookup :event machine-id)]
    (when (:rf/machine? m)
      (:rf/machine m))))

(defn spec-from-snapshot
  "Resolve the machine SPEC for a spawned actor from its `snapshot`'s
  `:rf/machine-type` reserved slot (per Spec 005 §Reserved
  snapshot-internal keys), or nil.

    - keyword type → read the registered TYPE's spec back from the
      registrar's `:rf/machine` metadata (the type is registered like a
      singleton and outlives instances);
    - map type     → an inline-`:definition` spawn carried its spec on
      the snapshot; return it verbatim.

  Returns nil when the snapshot carries no `:rf/machine-type` (a
  singleton snapshot) or when a keyword type
  no longer names a registered machine (the type was cleared — a genuine
  missing reference)."
  [snapshot]
  (let [t (:rf/machine-type snapshot)]
    (cond
      (map? t)     t
      (keyword? t) (spec-from-registry t))))

;; ---- the spawned-actor identity envelope ---------------------------------
;;
;; The canonical catalogue of the framework-owned slots that constitute a
;; spawned actor's IDENTITY, as distinct from its authored state/data. Spawn
;; stamps them (`lifecycle-fx.spawn/machine-type-ref` +
;; `stamp-framework-data`); snapshot compatibility recovery
;; (`lifecycle-fx.registration/rebuild-incompatible-snapshot`) carries them
;; across. Both sides read this ONE catalogue so they cannot disagree about
;; which values are durable actor identity (rf2-2dk0).
;;
;; Per Spec 005 §Reserved snapshot-internal keys §Persistence posture the only
;; TRANSIENT snapshot-root slot is `:rf/bootstrap-pending?` — every other
;; reserved slot rides the snapshot. `:rf/machine-type` in particular is what
;; makes a spawned actor's liveness a pure function of runtime-db
;; (§Liveness is derived from runtime-db): it is the ONLY key
;; `spec-from-snapshot` can re-materialise an actor's handler from, since a
;; spawned actor has no per-instance registrar entry. An actor stops being
;; addressable by being DESTROYED — never by having its definition change
;; underneath it.
;;
;; Deliberately NOT here: the transient runtime counters (`:rf/spawn-counter`,
;; `:rf/after-epoch`, `:rf/after-epoch-by-region`) and the SPAWNING side's
;; `:rf/spawned` map. Those are bookkeeping tied to the definition that
;; produced them, and a compatibility reset means restarting the machine —
;; they reset with the snapshot exactly as before.

(def actor-identity-root-keys
  "Framework-owned snapshot-ROOT slots that carry a spawned actor's identity.
  Absent on singleton snapshots (which resolve through the registrar)."
  [:rf/machine-type])

(def actor-identity-data-keys
  "Framework-owned `:data` slots that carry a spawned actor's identity and
  lineage — its own address, its owner's address, the invocation path it was
  spawned from, and (for a `:spawn-all` child) its private exact-attempt join
  membership. Each is present only on the spawn flavour that stamps it."
  [:rf/self-id :rf/parent-id :rf/invoke-id :rf/join-child])

(defn carry-actor-identity
  "Copy the spawned-actor identity envelope from `prev` onto `next`,
  key by key, skipping any key `prev` does not carry.

  `next` is a freshly-derived initial snapshot; `prev` is the snapshot being
  replaced. A SINGLETON `prev` carries none of these keys, so this is exactly
  a no-op there and the singleton reset semantics are untouched.

  This preserves identity ONLY — authored `:state` / `:data` and the transient
  runtime counters come from `next`, so a compatibility reset still means
  'restart this machine', not 'patch it' (rf2-2dk0)."
  [prev next]
  (let [carry-root (fn [snap k]
                     (if-some [v (get prev k)]
                       (assoc snap k v)
                       snap))
        carry-data (fn [snap k]
                     (if-some [v (get-in prev [:data k])]
                       (assoc-in snap [:data k] v)
                       snap))]
    (as-> next snap
      (reduce carry-root snap actor-identity-root-keys)
      (reduce carry-data snap actor-identity-data-keys))))

(defn spec-from-id-or-snapshot
  "Resolve a machine SPEC for `id` (a singleton's registered handler key,
  a spawned actor's instance address, or a spawning parent's id) preferring
  the registered TYPE, falling back to the `snapshot`'s `:rf/machine-type`:

    (or (spec-from-registry id) (spec-from-snapshot snapshot)).

  The two legs are mutually exclusive for any one id — a registered
  singleton has a registrar entry but no `:rf/machine-type` on
  its snapshot, while a spawned actor carries no per-instance registrar
  entry but stamps `:rf/machine-type` on its snapshot — so the `or` order
  is behaviourally irrelevant and either leg resolves at most one spec.
  Returns nil when neither resolves (the actor was already torn down, or
  the id names a non-machine event)."
  [id snapshot]
  (or (spec-from-registry id)
      (spec-from-snapshot snapshot)))

(defn resolvable?
  "True iff the actor identified by `actor-id` in `db` resolves to a live
  machine spec via its snapshot's `:rf/machine-type` — i.e. its liveness
  can be re-materialised purely from `db`. Used by the epoch restore
  precondition check (`:rf.epoch/restore-missing-handler`): a spawned
  actor whose TYPE is still registered (or whose snapshot carries an
  inline `:definition`) is a VALID restore target even though no
  per-instance handler is registered. Returns false when the actor has
  no snapshot, or its snapshot carries no resolvable `:rf/machine-type`."
  [db actor-id]
  (boolean
    (some-> (get-in db (rf.machines.paths/snapshot-path actor-id))
            (spec-from-snapshot))))

(defn spawn-spec-at
  "Walk `parent-spec`'s state tree to the `:spawn`-bearing node at `invoke-id`
  (the absolute prefix-path stamped at spawn time) and return that node's
  `:spawn` map, resolving flat AND region-prefixed invoke paths through
  `rf.machines.grammar/node-at`. For a parallel-region parent the first element of
  `invoke-id` is the region name; strip it and descend into that region's
  body. Returns nil if `parent-spec` is absent, `invoke-id` is not a
  non-empty vector, the path doesn't resolve, or the node declares no
  `:spawn`.

  Shared owner for the spawn-spec-at-invoke-id lookup used by the parent
  boundary's `:on-done` routing (`lifecycle-fx.registration`) and the
  spawn-error `:on-error` routing (`spawn-error/parent-declares-on-error?`)."
  [parent-spec invoke-id]
  (when (and parent-spec (vector? invoke-id) (seq invoke-id))
    (let [[head & tail] invoke-id
          [tree path]   (if (and (rf.machines.parallel/parallel? parent-spec)
                                 (contains? (:regions parent-spec) head))
                          [(get-in parent-spec [:regions head]) (vec tail)]
                          [parent-spec invoke-id])]
      (:spawn (rf.machines.grammar/node-at (:states tree) path)))))

(defn apply-on-done
  "Run ONE `:on-done` completion fold against a parent's `:data`, returning the
  new `:data` map.

  This is the SINGLE application site for the `:on-done` contract, shared by
  both spawn forms — a `:spawn` parent's `:on-done` (applied at the parent's
  handler boundary when the reserved completion carrier arrives) and a
  `:spawn-all` child spec's per-child `:on-done` (applied by the join fold at
  that child's finality). Per Spec 005 §Final states the callback receives one
  context-map arg and returns the new `:data` map:

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
