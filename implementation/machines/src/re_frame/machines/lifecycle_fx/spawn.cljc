(ns re-frame.machines.lifecycle-fx.spawn
  "Spawn live-handler wiring: `:rf.machine/spawn` and
  `:rf.machine/spawn-all-init` fx handlers.

  `apply-transition-once` emits `[:rf.machine/spawn args]` into the fx
  vector whenever entry cascades cross a `:spawn`-bearing state. Per
  Spec 005 §Spawning, the spawned actor is itself an event handler
  whose id is the actor address; `spawn-fx` registers the live handler
  under the spawned id and seeds its initial snapshot at
  `[:rf/machines <id>]`.

  The two-tier registry described in Spec 005 (frame-local handlers
  that revert with the frame's snapshot) is not yet built — for v1 the
  registration goes through the global registrar via
  `events/reg-event-fx`. Frame isolation is preserved by the snapshot
  living at `[:rf/machines <id>]` inside the spawning frame's app-db.

  Per rf2-6vmw `invoke-all-init-fx` also lives here — the runtime
  emits `[:rf.machine/spawn-all-init args]` alongside per-child
  `:rf.machine/spawn` fxs on entry to a `:spawn-all`-bearing state to
  seed the join state at `[:rf/spawned <parent> <invoke-id>]`."
  (:require [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines.lifecycle-fx.registration :as registration]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.spawn-order :as spawn-order]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- id allocation --------------------------------------------------------

(defn- pre-allocated-actor-id
  "Resolve the pre-allocated actor id carried on the spawn args. Per Spec
  005 §Declarative :spawn Spec-spec keys: `:spawn-id` is an explicit
  literal (per-state singleton); `:rf/spawned-id` is stamped by the
  transition reducer (rf2-gr8q — allocated from the parent snapshot's
  `:rf/spawn-counter`). Returns nil for hand-emitted
  `[:rf.machine/spawn args]` fxs that bypass the transition reducer —
  the caller (`spawn-fx`) allocates such ids from the frame's app-db
  spawn-counter slot inside the spawn's db-swap so the allocation
  shares the same write."
  [args]
  (or (:spawn-id args)
      (:rf/spawned-id args)))

(defn- allocate-actor-id-in-db
  "Hand-emitted-spawn fallback allocator (rf2-gr8q). When the spawn args
  carry no pre-allocated id (no `:spawn-id`, no `:rf/spawned-id`), this
  fn bumps the frame's app-db counter at
  `[:rf/spawn-counter <machine-id>]` and returns `[new-db spawned-id]`.
  Per rf2-gr8q the global `spawn-counter` atom is gone; the allocator
  lives where the side-effect belongs — inside the fx-handler's app-db
  swap — so the pure transition layer stays effect-free."
  [db machine-id]
  (let [db' (update-in db [:rf/spawn-counter machine-id] (fnil inc 0))
        n   (get-in db' [:rf/spawn-counter machine-id])]
    [db' (keyword (namespace machine-id)
                  (str (name machine-id) "#" n))]))

(defn- resolve-spawn-machine
  "Resolve the machine spec to register for a spawn. `:machine-id`
  references a registered machine — read its spec back from the
  registrar via the `:rf/machine` metadata. `:definition` carries an
  inline spec map. Returns the spec map or nil if neither resolves.
  Per Spec 005 §Spawn-spec keys."
  [args]
  (let [machine-id (:machine-id args)
        defn       (:definition args)]
    (cond
      defn        defn
      machine-id  (let [m (registrar/lookup :event machine-id)]
                    (when (:rf/machine? m)
                      (:rf/machine m))))))

(defn- stamp-framework-data
  "Per rf2-ijm7: stamp framework-reserved keys into the spawned actor's
  initial `:data` so the actor knows its own address (`:rf/self-id`)
  and, for declarative-`:spawn` spawns, its parent's address +
  invoke-id."
  [spec spawned-id parent-id invoke-id]
  (when spec
    (let [base-data (or (:data spec) {})
          data'     (cond-> (assoc base-data :rf/self-id spawned-id)
                      parent-id (assoc :rf/parent-id parent-id)
                      invoke-id (assoc :rf/spawn-id invoke-id))]
      (assoc spec :data data'))))

;; Per rf2-fgqs4: the spawned actor's initial snapshot is built by
;; `parallel/build-initial-snapshot` — the single source of truth shared
;; with the singleton-registration path
;; (`lifecycle-fx.registration/make-machine-handler`). Pre-rf2-fgqs4
;; the spawn-path's local helper silently omitted `:rf/spawn-counter`
;; (so an `:entry`-declared `:spawn` fell to `allocate-spawned-id`'s
;; defensive `(fnil inc 0)` backstop) and `:meta` (so spawned actors
;; that declared `:meta` couldn't introspect it from the snapshot).
;; The spawn path passes `:bootstrap-pending? true` because the actor's
;; first dispatch must fire the initial-entry cascade (rf2-0z73).

(defn- install-spawn!
  "Atomically install the spawned actor's initial snapshot, system-id
  binding, and runtime-owned spawn registry slot into the frame's
  app-db. Returns nil — the side-effect IS the value. Emits the
  collision and system-id-bound traces when applicable.

  `db-after-alloc` is the post-id-allocation db computed by the caller
  (see `spawn-fx`); `swap-frame-db!`'s fn arg is discarded — the merge
  is applied on top of `db-after-alloc` so the caller's counter bump
  survives. Under Spec 002's single-drainer invariant the discarded
  re-read is value-equal to the snapshot the caller already had."
  [frame-id db-after-alloc spec spawned-id
   {:keys [system-id parent-id track?] invoke-id :spawn-id}]
  (let [initial-snap (when spec
                       (parallel/build-initial-snapshot
                         spec {:bootstrap-pending? true}))
        existing     (when system-id (get-in db-after-alloc [:rf/system-ids system-id]))]
    (when (and system-id existing (not= existing spawned-id))
      (trace/emit-error! :rf.error/system-id-collision
                         {:frame             frame-id
                          :system-id         system-id
                          :existing-machine  existing
                          :rebound-to        spawned-id
                          :reason            (str ":system-id " system-id
                                                  " was already bound to "
                                                  existing
                                                  "; rebinding to " spawned-id
                                                  " (last-write-wins).")
                          :recovery          :warned-and-replaced}))
    (frame/swap-frame-db! frame-id
                          (fn [_db]
                            (cond-> db-after-alloc
                              spec      (assoc-in [:rf/machines spawned-id] initial-snap)
                              system-id (assoc-in [:rf/system-ids system-id] spawned-id)
                              track?    (assoc-in [:rf/spawned parent-id invoke-id] spawned-id))))
    (when system-id
      (trace/emit! :machine :rf.machine/system-id-bound
                   {:frame      frame-id
                    :system-id  system-id
                    :machine-id spawned-id}))))

;; ---- :rf.machine/spawn -----------------------------------------------------

(defn spawn-fx
  "fx handler for `:rf.machine/spawn`. Per Spec 005 §Spawning, the spawned
  actor is itself an event handler at `<spawned-id>`; its snapshot lives
  at `[:rf/machines <spawned-id>]` in the spawning frame's app-db.

  Lifecycle wired here:
   1. Resolve the spawn's machine spec (`:machine-id` from the registrar
      OR an inline `:definition`).
   2. Register the live event handler under the spawned id via
      `make-machine-handler` / `reg-event-fx`. Re-spawn under the
      same id replaces — last-write-wins, matching standard
      re-registration.
   3. Initialise the actor's snapshot at `[:rf/machines <spawned-id>]`
      using the spec's `:initial` / `:data` (overridden by the spawn
      args' `:data`). Per rf2-ijm7 the runtime stamps `:rf/self-id`
      (the spawned actor's own address) and, when applicable,
      `:rf/parent-id` + `:rf/spawn-id` into the actor's initial
      `:data` under the framework-reserved `:rf/*` namespace.
   4. If `:system-id` present, bind it in the per-frame
      `[:rf/system-ids]` reverse index. Collisions emit
      `:rf.error/system-id-collision` and rebind (last-write-wins).
   5. If `:rf/parent-id` + `:rf/spawn-id` present (declarative `:spawn`
      desugar — rf2-t07u Option A revised), bind the spawned id at
      `[:rf/spawned <parent-id> <invoke-id>]`.
   6. If `:start` event-vector present, dispatch
      `[<spawned-id> <start>]`. When `:start` is absent (per rf2-ijm7),
      the runtime dispatches a synthetic `[<spawned-id>
      [:rf.machine/spawned]]` so generic child machines may declare a
      leaf-level `:on :rf.machine/spawned :target ...` transition."
  [{frame-id :frame :or {frame-id :rf/default}} args]
  (let [;; Per rf2-gr8q: prefer the pre-allocated id (declarative :spawn
        ;; routes through the transition reducer which bumps the parent
        ;; snapshot's `:rf/spawn-counter`). Hand-emitted spawn fxs carry
        ;; no pre-allocated id; the frame's app-db spawn-counter slot
        ;; serves as the fallback allocator, bumped inside the same
        ;; db-swap as the snapshot install / registry bind below.
        pre-id     (pre-allocated-actor-id args)
        spec       (resolve-spawn-machine args)
        spec'      (if (and spec (contains? args :data))
                     (assoc spec :data (:data args))
                     spec)
        system-id  (:system-id args)
        ;; Per rf2-t07u (Option A revised): the runtime tracks each
        ;; declarative-:spawn spawn at [:rf/spawned <parent-id>
        ;; <invoke-id>] — populated only when the spawn carries both.
        parent-id  (:rf/parent-id args)
        invoke-id  (:rf/spawn-id args)
        track?     (and parent-id invoke-id)
        ;; Resolve the final spawned id: pre-allocated when present;
        ;; else allocate from app-db inside the swap below. We pre-read
        ;; the db once so the trace event and reg-machine* call see the
        ;; same id the snapshot install / registry bind will use. The
        ;; db-swap re-applies the increment to the (potentially-newer)
        ;; db at write time — for the JVM atom container the read is
        ;; consistent because `frame/swap-frame-db!` is the only writer
        ;; during fx drain (Spec 002 §Single drainer per frame).
        old-db     (frame/frame-app-db-value frame-id)
        machine-id-for-alloc (or (:id-prefix args) (:machine-id args))
        [db-after-alloc spawned-id]
        (cond
          pre-id        [old-db pre-id]
          (and old-db machine-id-for-alloc)
                        (allocate-actor-id-in-db old-db machine-id-for-alloc)
          :else         [old-db nil])
        spec''     (stamp-framework-data spec' spawned-id parent-id invoke-id)]
    (trace/emit! :machine :rf.machine/spawned
                 {:frame      frame-id
                  :machine-id (:machine-id args)
                  :spawned-id spawned-id
                  :id-prefix  (:id-prefix args)
                  :start      (:start args)
                  :on-spawn   (:on-spawn args)
                  :system-id  system-id
                  :parent-id  parent-id
                  :spawn-id  invoke-id})
    (when spec''
      (registration/reg-machine* spawned-id spec''))
    ;; (3) Initialise the snapshot + (4) bind :system-id + (5) bind the
    ;; runtime-owned spawn registry (atomically under one app-db swap so
    ;; observers see consistent state). When the spawned id was allocated
    ;; from the frame's app-db (the hand-emitted-spawn fallback path),
    ;; `db-after-alloc` already carries the bumped counter — install the
    ;; snapshot on top of that.
    (when old-db
      (install-spawn! frame-id db-after-alloc spec'' spawned-id
                      {:system-id system-id
                       :parent-id parent-id
                       :spawn-id invoke-id
                       :track?    track?})
      ;; Per rf2-vsigt — record the spawned actor in the frame's
      ;; spawn-order channel so frame-destroy can walk in reverse-
      ;; creation order per Spec 005 §Cross-Spec Interactions §1.
      (spawn-order/record! frame-id spawned-id))
    ;; (6) Fire the :start event into the new actor. Per rf2-ijm7,
    ;; spawns that don't supply :start receive a synthetic
    ;; [:rf.machine/spawned] so generic child machines can declare their
    ;; first transition out of an :initial state at spec-write time.
    (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
      ;; Per rf2-t1lxr: machine-spawn :start dispatches are framework-
      ;; internal lifecycle events — tag :rf/dispatch-origin :internal
      ;; so Causa's L2 timeline can distinguish actor-bootstrap from
      ;; user-origin events.
      (let [start (:start args)
            opts  {:frame frame-id :rf/dispatch-origin :internal}]
        (if (some? start)
          (dispatch! [spawned-id start] opts)
          (dispatch! [spawned-id [:rf.machine/spawned]] opts))))
    spawned-id))

;; ---- :rf.machine/spawn-all-init -------------------------------------------

(defn invoke-all-init-fx
  "fx handler for `:rf.machine/spawn-all-init` (rf2-6vmw). Per Spec 005
  §Spawn-and-join via `:spawn-all`, on entry to a `:spawn-all`-bearing
  state the runtime emits this fx (alongside per-child `:rf.machine/spawn`
  fxs) to seed the join state at `[:rf/spawned <parent> <invoke-id>]` in
  the frame's app-db. The seed map shape is:

    {:children {<child-id> <spawned-id>, ...}
     :done      #{}
     :failed    #{}
     :resolved? false
     :spec      <invoke-all-spec>}

  Subsequent `:on-child-done` / `:on-child-error` events arrive at the
  parent's `make-machine-handler` boundary and are intercepted by
  `intercept-invoke-all-event` (in `lifecycle-fx.join`)."
  [{frame-id :frame :or {frame-id :rf/default}} args]
  (let [parent-id  (:rf/parent-id args)
        invoke-id  (:rf/spawn-id args)
        join-state (:join-state args)
        children   (:children join-state)]
    (frame/swap-frame-db! frame-id assoc-in
                          [:rf/spawned parent-id invoke-id] join-state)
    (trace/emit! :machine :rf.machine.spawn-all/started
                 {:machine-id parent-id
                  :spawn-id  invoke-id
                  :child-ids  (set (keys children))
                  :children   children
                  :frame      frame-id})
    nil))
