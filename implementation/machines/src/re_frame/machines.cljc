(ns re-frame.machines
  "State machines. Per Spec 005.

  Implements the v1 grammar:
    - Transition tables with :on, :entry, :exit, :guard, :action.
    - Flat states (single keyword) AND hierarchical states (vector path)
      with deepest-wins resolution and LCA exit/entry cascade.
    - :always microsteps with bounded depth and atomic rollback on
      depth-exceeded.
    - :after delayed transitions with per-scheduling-node :rf/after-epoch
      tracking (a {<decl-path> <int>} map, so a parent's :after survives a
      child-only sibling transition per Spec 005 §Hierarchy interaction);
      the synthetic :rf.machine.timer/after-elapsed event carries the
      node's epoch + decl-path and fires the transition only when the
      scheduling node is still active and the carried epoch matches.
      `:after` delays admit three forms — pos-int? literal, subscription vector
      ([:sub-id & args]; re-resolves on sub change), and
      (fn [snapshot] ms) computed once at state entry.
    - Declarative :spawn that desugars into [:rf.machine/spawn args]
      on entry and [:rf.machine/destroy actor-id] on exit; deterministic
      actor ids via the in-snapshot :rf/spawn-counter (declarative) / the
      frame's runtime-db [:rf.runtime/machines :spawn-counter <machine-id>]
      slot (hand-emitted) — no process-global state.
    - Declarative :spawn-all — spawn-and-join sugar over N parallel
      :spawn's plus a join condition (:all / :any / {:n N} / {:fn pred}).
    - The :raise reserved fx-id (machine-internal pre-commit dispatch).
    - The :rf.machine/dispatch-to-system reserved fx-id — a machine
      action sends a message to its spawned child actor by :system-id
      (the fx counterpart to the dispatch-to-system FN).
    - Snapshot at [:rf.runtime/machines :snapshots <id>] in runtime-db.
    - Pure machine-transition fn (JVM- and CLJS-runnable, deterministic).

  Public surface re-exported from the sub-namespaces:
    - `reg-machine*`, `make-machine-handler` —
      `re-frame.machines.lifecycle-fx.registration`
    - `validate-machine!` — `re-frame.machines.lifecycle-fx.validation`
      (the pure registration-time validator; the conformance corpus's
      `:reg-machine` Mode-B op pins the registration-error taxonomy
      against it)
    - `machine-transition` — `re-frame.machines.parallel` (the public
      dispatch; flat / compound delegates to
      `re-frame.machines.transition`'s `machine-transition-single`)
    - `machines`, `machine-meta`, `machine-by-system-id` — owned
      directly on this façade (Spec 005 §Querying machines)
    - `spawn-fx`, `spawn-all-init-fx` —
      `re-frame.machines.lifecycle-fx.spawn`
    - `destroy-machine-fx` — `re-frame.machines.lifecycle-fx.destroy`
    - `after-schedule-fx`, `after-cancel-fx` — `re-frame.machines.timer`"
  (:require [re-frame.frame :as frame]
            [re-frame.fx :as fx]
            [re-frame.image-assembly :as image-assembly]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines.data-validation :as data-validation]
            [re-frame.machines.lifecycle-fx.destroy :as destroy]
            [re-frame.machines.lifecycle-fx.frame-destroy :as frame-destroy]
            [re-frame.machines.lifecycle-fx.registration :as registration]
            [re-frame.machines.lifecycle-fx.resolver :as resolver]
            [re-frame.machines.lifecycle-fx.spawn :as spawn]
            [re-frame.machines.lifecycle-fx.update-snapshot :as update-snapshot]
            [re-frame.machines.lifecycle-fx.validation :as validation]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.paths :as paths]
            [re-frame.machines.spawn-order :as spawn-order]
            [re-frame.machines.ssr :as machines-ssr]
            [re-frame.machines.timer :as timer]
            [re-frame.registrar :as registrar]
            [re-frame.subs :as subs]
            ;; The JVM-only require of the machines tooling sibling that backs
            ;; the `machine-algebra-view` / `machine-instance-algebra-view` /
            ;; `machine-selector?` aliases at the foot of this ns. CLJS
            ;; deliberately OMITS this require so a CLJS app that loads the
            ;; machines artefact but never attaches a tool DCEs the tooling body
            ;; wholesale — the facade never reaches it. JVM has no bundle to
            ;; protect; the aliases give JVM tools / conformance fixtures the
            ;; ergonomic `re-frame.machines/<name>` shape. Mirrors the
            ;; `re-frame.flows` → `re-frame.flows.tooling` JVM-only require.
            #?@(:clj [[re-frame.machines.tooling :as machines-tooling]])))

#?(:clj (set! *warn-on-reflection* true))

;; ---- public-surface re-exports --------------------------------------------
;;
;; These `def`s make the sub-namespace fns reachable as
;; `re-frame.machines/<name>` so consumers (the `re-frame.core` late-bind
;; bridge, the conformance corpus, the test fixtures, examples that
;; `:require [re-frame.machines :as machines]`) all see one surface.

;; Declarative-`:spawn` spawns allocate ids inside the parent
;; snapshot's `:rf/spawn-counter` slot via
;; `re-frame.machines.transition/allocate-spawned-id`; hand-emitted
;; spawn fxs allocate from the frame's app-db slot at
;; `[:rf.runtime/machines :spawn-counter <machine-id>]` inside the
;; spawn-fx db-swap (the single-reserved-root contract).
;; `machine-transition` is a pure function — no module-level mutable
;; state, deterministic from its (machine snapshot event) arguments.

(def reg-machine*           registration/reg-machine*)
(def make-machine-handler registration/make-machine-handler)
;; Boundary-validation surface for the `[:schemas :data]` schema on `reg-machine`
;; (Spec 005 §Schema validation, Spec 010 §Per-step recovery row 7). The
;; post-commit walker validates every snapshot's `:data` against its
;; registered machine's `[:schemas :data]` schema; the spawn-time sibling
;; validates a spawned actor's initial `:data` before install.
(def validate-machine-data! data-validation/validate-machine-data!)
(def validate-spawn-data!   data-validation/validate-spawn-data!)
;; The `:rf.machine/update-snapshot` escape-hatch sibling validates the
;; would-be-merged snapshot's `:data` BEFORE the fx writes it, so the
;; `[:schemas :data]` boundary covers the escape hatch too.
(def validate-update-snapshot-data! data-validation/validate-update-snapshot-data!)
;; The pure registration-time validator (Spec 005 §registration validators).
;; Re-exported so the conformance corpus's `:reg-machine` Mode-B call op can
;; pin the registration-error taxonomy (Spec 009 §thrown-error shape)
;; directly against the leaf fn — no registrar/substrate fixture.
(def validate-machine!      validation/validate-machine!)
(def spawn-fx               spawn/spawn-fx)
(def spawn-all-init-fx      spawn/spawn-all-init-fx)
(def destroy-machine-fx     destroy/destroy-machine-fx)
(def machine-transition     parallel/machine-transition)
(def after-schedule-fx      timer/after-schedule-fx)
(def after-cancel-fx        timer/after-cancel-fx)

;; ---- query API (Spec 005 §Querying machines) -----------------------------
;;
;; Three thin lookup fns over the existing event registry and the
;; runtime-owned `[:rf.runtime/machines :system-ids]` reverse index — derived views, not a
;; new registry kind. `(rf/machines)` filters event handlers whose
;; registration metadata carries `:rf/machine? true`; `(rf/machine-meta
;; id)` returns the registered machine's spec map; `(rf/machine-by-
;; system-id sid)` resolves the spawned-machine id currently bound to
;; `sid` in the active frame's `[:rf.runtime/machines :system-ids]` reverse index.
;;
;; These query fns live on the public artefact surface (not a level
;; below) since they're how Spec 005 §Querying machines is reached.

(defn machines
  "Return a sequence of machine-ids — every event handler whose
  registration metadata carries `:rf/machine? true`. Per Spec 005
  §Querying machines."
  []
  (->> (registrar/registrations :event)
       (keep (fn [[id m]] (when (:rf/machine? m) id)))
       (vec)))

(defn machine-meta
  "Return the registered machine's spec map (`:initial`, `:data`,
  `:schemas`, `:guards`, `:actions`, `:states`, `:doc`, source
  coords) for `machine-id`, or nil if no machine is registered under
  that id. Per Spec 005 §Querying machines."
  [machine-id]
  (resolver/spec-from-registry machine-id))

(defn machine-by-system-id
  "Look up the spawned-machine id currently bound to `system-id` in the
  active frame's `[:rf.runtime/machines :system-ids]` reverse index, or nil.

  The 1-arity ambient form resolves the frame through the scope/hold chain
  via `frame/require-current-frame!` — a lookup issued under no established
  scope raises `:rf.error/no-frame-context` rather than reading an invented
  default's reverse index. Pass the 2-arity `(machine-by-system-id
  system-id frame-id)` to look up a named frame from outside any scope
  (async callbacks / tools / cross-frame lookups).

  Per Spec 005 §Named addressing via :system-id + Spec 002 §Resolver
  surface."
  ([system-id]
   (machine-by-system-id
     system-id
     (frame/require-current-frame!
       :machine-by-system-id
       {:where 're-frame.machines/machine-by-system-id})))
  ([system-id frame-id]
   ;; The system-ids reverse index is durable machine runtime-db state —
   ;; read it off the runtime-db partition.
   (get-in (frame/frame-runtime-db-value frame-id) (paths/system-id-path system-id))))

;; ---- derivation/process algebra views -------------------------------------
;;
;; The derivation/process algebra view of registered machines
;; (`machine-algebra-view`), their live instances / spawned actors
;; (`machine-instance-algebra-view`), and the machine-selector recognizer
;; (`machine-selector?`). A machine is the canonical `:process` member of the
;; algebra (a derivation WITH state, lifecycle, and commands over time); its
;; snapshot materializes into runtime-db, evaluated `:on-transition`.
;;
;; JVM convenience aliases: the bodies live in `re-frame.machines.tooling` so a
;; CLJS app that loads the machines artefact but attaches no tool DCEs them (the
;; CLJS facade never `:require`s the tooling sibling — the require above is
;; `#?@(:clj ...)`-gated). CLJS consumers (Xray + conformance) call
;; `re-frame.machines.tooling/<name>` directly. No `re-frame.core` facade
;; export. Mirrors the `re-frame.flows/flow-algebra-view` JVM alias.
#?(:clj
   (do
     (def machine-algebra-view          machines-tooling/machine-algebra-view)
     (def machine-instance-algebra-view machines-tooling/machine-instance-algebra-view)
     (def machine-selector?             machines-tooling/machine-selector?)
     (def machine-selector-targets      machines-tooling/machine-selector-targets)))

;; ---- :rf.machine/dispatch-to-system — action→spawned-actor messaging fx --
;;
;; Per Spec 005 §Cross-machine messaging by name + §Named addressing via
;; `:system-id`: a machine ACTION addresses its spawned child actor by
;; `:system-id`. Actions can't read app-db and `:on-spawn`'s return is
;; dropped, so the fx form is the spec-blessed way an action sends a
;; message to a named actor. This is the fx counterpart to the
;; `dispatch-to-system` FN (`re-frame.core`); the fn is sugar for direct
;; (queued) call sites, the fx is what a machine action emits from `:fx`.
;;
;; Args shape `[<system-id> <event-vector>]` — the framework fx contract
;; is a 2-element `[fx-id args]` pair (the `do-fx` walk drops arity-≥3
;; entries with `:rf.error/effect-map-shape`), so the system-id and event
;; ride together in the single `args` slot. This mirrors `:rf.machine/spawn`
;; (args is a single spec map) and `:dispatch` (args is a single event
;; vector). Frame-aware: the fx-ctx's `:frame` resolves the binding in the
;; emitting frame's `[:rf.runtime/machines :system-ids]` reverse index and
;; targets the queued dispatch at the same frame — consistent with
;; `spawn-fx` / `update-snapshot-fx`.

(defn dispatch-to-system
  "Implementation-tier sugar: dispatch `event` to the spawned-machine
  bound to `system-id` in the active frame. Equivalent to
  `(when-let [m (machine-by-system-id system-id)] (dispatch [m event]))`,
  with a no-op fall-through when the system-id is unbound.

  An implementation-tier helper, not an app-facing front-porch API: the
  CANONICAL action-side messaging surface is the reserved
  `[:rf.machine/dispatch-to-system [system-id event]]` fx tuple (see
  `dispatch-to-system-fx`); this direct-call FN is the call-site twin.
  Per Spec 005 §Cross-machine messaging by name."
  ([system-id event]
   (when-let [machine-id (machine-by-system-id system-id)]
     (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
       (dispatch! [machine-id event]))))
  ([system-id event frame-id]
   (when-let [machine-id (machine-by-system-id system-id frame-id)]
     (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
       (dispatch! [machine-id event] {:frame frame-id})))))

(defn dispatch-to-system-fx
  "fx handler for `:rf.machine/dispatch-to-system`. Resolves `system-id`
  through the emitting frame's `[:rf.runtime/machines :system-ids]`
  reverse index and dispatches `event` to the bound actor. No-op when the
  system-id is unbound (symmetric with the `dispatch-to-system` FN's
  no-op fall-through). Per Spec 005 §Cross-machine messaging by name."
  [{frame-id :frame} [system-id event]]
  ;; The cascade envelope frame is the fx-context `:frame`; a nil stamp is
  ;; an invariant failure (`:rf.error/no-frame-context`), never a
  ;; synthesised `:rf/default`.
  (let [frame-id (frame/require-frame-stamp!
                   frame-id :rf.machine/dispatch-to-system
                   {:where 'rf.machine/dispatch-to-system :event-id system-id})]
    (when-let [machine-id (machine-by-system-id system-id frame-id)]
      (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
        (dispatch! [machine-id event] {:frame frame-id}))))
  nil)

(defn reset-timers!
  "Cancel in-flight `:after` timers.

  0-arity: every frame's timers — the fixture-teardown shape used by
  `re-frame.test-support`'s `reset-runtime` and per-feature artefact test
  fixtures. Clears the entire frame-scoped table.

  1-arity: just the given frame's timers — the `frame/destroy-frame!`
  hook shape used to release a destroyed frame's host-clock handles and
  subscription watchers without touching sibling frames.

  Spawn-id allocation lives inside the parent snapshot's
  `:rf/spawn-counter` slot (declarative `:spawn`) or the frame's
  runtime-db at `[:rf.runtime/machines :spawn-counter <machine-id>]`
  (hand-emitted spawn); both reset automatically with the registrar
  snapshot/restore + frame reset, so this hook only handles the
  frame-scoped wall-clock timer table. The 0-arity / 1-arity split
  aligns with the test-fixture and frame-destroy call sites
  respectively."
  ([]
   (timer/cancel-all-timers!))
  ([frame-id]
   (timer/cancel-all-timers! frame-id)))

;; ---- machine-internal effect handlers ------------------------------------
;;
;; Per Spec 005 §Declarative :spawn (sugar over spawn) the runtime
;; effects `:rf.machine/spawn` and `:rf.machine/destroy` are emitted
;; into the fx vector by `apply-transition-once` whenever entry/exit
;; cascades cross a :spawn-bearing state. These handlers live in
;; this namespace (rather than `re-frame.fx`'s reserved case-block) so
;; an app that doesn't pull in `day8/re-frame2-machines` carries
;; neither the trace strings (`:rf.machine.spawn/spawned`,
;; `:rf.machine/destroyed`) nor the handler symbols on its production-
;; elision bundle.

(fx/reg-fx :rf.machine/spawn
  {:doc "Spawn a machine instance. Per Spec 005 §Declarative :spawn (sugar over spawn). Args carry `:machine-id`, optional `:system-id`, and optional `:initial-data`."}
  spawn-fx)

(fx/reg-fx :rf.machine/destroy
  {:doc "Destroy a spawned machine instance and clear its `[:rf.runtime/machines :snapshots machine-id]` slot. Per Spec 005 §Declarative :spawn."}
  destroy-machine-fx)

(fx/reg-fx :rf.machine/spawn-all-init
  {:doc "Machine-internal: fire `:initial-entry` cascades for every machine spawned at app boot. Per Spec 005 §Initial entry. Not for direct application use."}
  spawn-all-init-fx)

(fx/reg-fx :rf.machine/after-schedule
  {:doc "Machine-internal: schedule an `:after` timer event for a machine state. Per Spec 005 §Timed transitions. Not for direct application use."}
  after-schedule-fx)

(fx/reg-fx :rf.machine/after-cancel
  {:doc "Machine-internal: cancel a previously-scheduled `:after` timer. Per Spec 005 §Timed transitions. Not for direct application use."}
  after-cancel-fx)

(fx/reg-fx :rf.machine/update-snapshot
  {:doc "Snapshot-level escape hatch. Emit `[:rf.machine/update-snapshot {:rf/machine-id <id> :rf/patch {:data {...}}}]` from a callback's `:fx` vector to touch `:state` / `:meta` / `:data` atomically. Per Spec 005 §Snapshot-level escape hatch."}
  update-snapshot/update-snapshot-fx)

(fx/reg-fx :rf.machine/dispatch-to-system
  {:doc "Dispatch an event to a spawned actor addressed by `:system-id`. Emit `[:rf.machine/dispatch-to-system [<system-id> <event-vector>]]` from a machine action's `:fx` vector. No-op when the system-id is unbound. The fx counterpart to the `dispatch-to-system` fn. Per Spec 005 §Cross-machine messaging by name."}
  dispatch-to-system-fx)

;; ---- framework-shipped subs -----------------------------------------------
;;
;; Per Spec 005 §Subscribing to machines via sub-machine: the framework
;; ships `:rf/machine` as the canonical entry point — read a machine's
;; snapshot with the subscription vector `(subscribe [:rf/machine id])`.
;;
;; Registered at the façade (rather than in `re-frame.machines.lifecycle-
;; fx`) so the smoke-test fixture's `(require 're-frame.machines :reload)`
;; re-installs the subs after `registrar/clear-all!`. `:reload` is
;; shallow — a sub registered inside the sub-namespace wouldn't re-fire.

;; Machine snapshots are durable runtime-db state, so the framework machine
;; subs read the frame's RUNTIME-DB projection (`reg-runtime-sub`) — the
;; `db`-position arg is the runtime-db value (Spec 002 §Subscriptions read
;; the partition they belong to).
(subs/reg-runtime-sub :rf/machine
  {:doc "Subscribe to a machine's current snapshot `{:state <kw> :data <map> :tags <set>}`. Returns nil for an unknown or not-yet-initialised machine. Per Spec 005 §Subscribing to machines via sub-machine."}
  (fn [runtime-db [_ machine-id]]
    (get-in runtime-db (paths/snapshot-path machine-id))))

;; Per Spec 005 §State tags: the `:rf/machine-has-tag?` framework sub
;; returns `true` iff the named machine's current snapshot's `:tags` set
;; contains the queried tag. A machine that hasn't been initialised yet (no
;; snapshot at `[:rf.runtime/machines :snapshots <id>]`) returns `false`.
;;
;; Derived sub — reads the snapshot via `get-in` rather than chaining
;; off `:rf/machine` — so a view that only cares about whether a specific
;; tag is present re-renders only when the containment-bit flips.
(subs/reg-runtime-sub :rf/machine-has-tag?
  {:doc "Subscribe to a machine's `:fsm/tags` containment-bit for `tag`. Returns `true` iff the named machine's snapshot's `:tags` set contains `tag`, `false` otherwise (including unknown / not-yet-initialised machines). Per Spec 005 §State tags."}
  (fn [runtime-db [_ machine-id tag]]
    (contains? (get-in runtime-db (paths/snapshot-path machine-id :tags)) tag)))

;; ---- framework-standard registration (EP-0026 §Framework Standard Registrations)
;;
;; The machine runtime effects + subs above are FRAMEWORK STANDARDS: reserved
;; `:rf.machine/*` / `:rf/machine*` ids encoding the execution invariants of the
;; spec/005 machine subsystem (spawn / destroy / timed transitions / the
;; snapshot reader subs). Like `:rf/set-db` + the standard `:rf.interceptor/*`
;; interceptors, they must be UNIONED INTO EVERY resolved image generation —
;; otherwise an image-loaded frame (one created with an explicit `:images`
;; `:select-ns` scope, e.g. a Story variant frame scoped to its app namespace)
;; resolving `[:rf.machine/spawn …]` / `[:rf/machine …]` under a bound
;; `*generation*` could NOT resolve it (generation-routed `registrar/lookup`
;; reads ONLY the generation's resolver — no fallback to the registrar atom), so
;; the machine never spawns and any hosted machine (incl. the Story lifecycle
;; machine) silently stalls. These ids register via the runtime `reg-fx` /
;; `reg-runtime-sub` FNs (not the provenance-stamping macros), so they carry NIL
;; `:rf.provenance/ns` and a `:select-ns` image cannot reach them by namespace —
;; the framework-standard union is the ONLY mechanism that keeps them live in
;; every generation.
;;
;; The standards are NON-replaceable (default): a public app image MUST NOT
;; shadow a reserved machine id — a collision FAILS LOUD
;; (`:rf.error/image-standard-replacement-forbidden`). The standard descriptor is
;; the SAME runnable descriptor the regular registrar stores, so a
;; generation-routed resolution returns a value byte-shape-identical to the
;; registrar path.
;;
;; The descriptors are CAPTURED at ns-load (immediately after the `reg-fx` /
;; `reg-runtime-sub` forms above wrote them) into `machine-runtime-descriptors`
;; so `install-machine-runtime!` can re-register BOTH the regular registrar AND
;; the standard registry AFTER a `registrar/clear-all!` — which wipes the
;; registrar slots, so reading them back at re-install time would find nothing.
;; This is the machine analogue of `events/register-set-db-standard!` (re-seeds
;; both the registrar and the standard registry on every `init!` / reset).

(def ^:private machine-runtime-descriptors
  "Captured at ns-load: the `[kind id descriptor]` triples for every machine
  runtime effect + sub registered above. `descriptor` is the full registration
  metadata map (carrying `:handler-fn`) read off the registrar right after the
  `reg-fx` / `reg-runtime-sub` form ran. `install-machine-runtime!` re-registers
  from these so the machine runtime survives a `registrar/clear-all!`."
  (vec
    (keep (fn [[kind id]]
            (when-let [desc (registrar/lookup kind id)]
              [kind id desc]))
          [[:fx  :rf.machine/spawn]
           [:fx  :rf.machine/destroy]
           [:fx  :rf.machine/spawn-all-init]
           [:fx  :rf.machine/after-schedule]
           [:fx  :rf.machine/after-cancel]
           [:fx  :rf.machine/update-snapshot]
           [:fx  :rf.machine/dispatch-to-system]
           [:sub :rf/machine]
           [:sub :rf/machine-has-tag?]])))

(defn install-machine-runtime!
  "Re-register the machine runtime effects + subs into BOTH the regular
  registrar AND the EP-0023 framework-standard registry. Idempotent.

  - The REGULAR registrar registration is the no-generation / default-image
    resolution path — `registrar/lookup` reads the atom directly, so a
    no-generation caller (e.g. `rf/compute-sub [:rf/machine …]` in a test, or a
    default-image frame) resolves the machine runtime here.
  - The STANDARD registration is unioned into EVERY resolved image generation,
    so an image-loaded frame (a Story variant frame scoped to its app namespace)
    resolves `[:rf.machine/spawn …]` / `[:rf/machine …]` through its sealed
    generation (which carries ONLY the standard union + the image selection — no
    registrar fallback).

  Re-registers from the ns-load-captured `machine-runtime-descriptors` so it
  works even after a `registrar/clear-all!` has wiped the registrar slots (the
  machine analogue of `events/register-set-db-standard!`). Called at ns load
  (below), from the `:machines/install-runtime!` late-bind hook the reset fixture
  fires, and directly by tests that wipe the registrar with `clear-all!`."
  []
  (doseq [[kind id desc] machine-runtime-descriptors]
    (registrar/register! kind id desc)
    (image-assembly/register-standard! kind id desc))
  nil)

;; Register at namespace load so an image-loaded frame created by a standalone
;; require'r (no `init!`) resolves the machine runtime through its generation,
;; and a no-generation caller resolves it through the registrar.
(install-machine-runtime!)

;; Publish a late-bind hook so `re-frame.test-support`'s reset fixture can
;; re-install the machine runtime (registrar + standards) after a sibling ns's
;; `registrar/clear-all!` / `image-assembly/clear-standards!` wipes them — the
;; SAME re-seed-on-reset contract `:rf/set-db` carries. test_support ships in
;; core and must not static-require machines (separate Maven coordinate), so the
;; re-install is reached through this hook (a no-op when machines is not loaded).
(late-bind/set-fn! :machines/install-runtime! install-machine-runtime!)

;; ---- spawned-actor ownership ----------------------------------------------
;;
;; Per Spec 014 §Abort on actor destroy: a managed `:rf.http/managed`
;; request "belongs to" a spawned actor when its originating event-id is
;; that actor's address. The ownership decision is MACHINES-OWNED and
;; published through the `:machines/owning-actor-id` hook, so the structural
;; read lives next to the runtime-db shape it depends on. http reaches it
;; via `late-bind/get-fn` and falls back to nil when machines is absent.
;;
;; SET SEMANTICS: the owning set is SNAPSHOT MEMBERSHIP — `event-id` owns a
;; request iff a SPAWNED actor's snapshot lives at
;; `[:rf.runtime/machines :snapshots <event-id>]`, where "spawned" is the
;; durable `:rf/machine-type`-at-root discriminator `install-spawn!` stamps
;; on EVERY spawned actor (declarative `:spawn` / `:spawn-all` AND
;; imperative `[:rf.machine/spawn ...]`), and a singleton's snapshot never
;; carries it. Snapshot membership covers imperatively-spawned actors as
;; well as declarative ones: imperative spawns install a snapshot WITHOUT a
;; `:spawned` registry slot (the slot is gated on the declarative-desugar
;; `:rf/parent-id` + `:rf/invoke-id`, per `lifecycle-fx.spawn/spawn-fx`'s
;; `track?`), so keying on the snapshot rather than the registry catches
;; them and their managed HTTP is aborted on destroy. The destroy side
;; fires `:http/abort-on-actor-destroy` for imperative destroys (via
;; `lifecycle-fx.finalize` / `lifecycle-fx.frame-destroy`, which key on the
;; SAME `:rf/machine-type` marker); the recording side keys on the SAME
;; discriminator `frame-destroy/spawned-snapshot?` uses, so recording and
;; teardown agree on exactly which ids are actors.

(defn owning-actor-id
  "Resolve the spawned-actor-id that OWNS `event-id` in `frame-id`, or nil.

  Returns `event-id` (a keyword — the spawned actor's machine address)
  when a SPAWNED actor's snapshot is currently installed at
  `[:rf.runtime/machines :snapshots <event-id>]`, otherwise nil — meaning
  the event is NOT owned by a spawned actor (it came from an ordinary
  event handler, or from a singleton machine whose snapshot carries no
  `:rf/machine-type` marker).

  Published as the `:machines/owning-actor-id` late-bind hook so the http
  artefact can ask machines \"who owns this request's originating event?\"
  without statically `:require`ing this artefact or re-stating its private
  runtime-db shape. When machines is absent the hook is unregistered and
  http's caller falls back to nil.

  Set semantics: SNAPSHOT MEMBERSHIP via the durable `:rf/machine-type`-at-
  root discriminator — declarative `:spawn` / `:spawn-all` AND imperative
  `[:rf.machine/spawn ...]` actors, since `install-spawn!` stamps that
  marker on every spawned actor's snapshot and a singleton's snapshot never
  carries it. This is the SAME discriminator the destroy side
  (`frame-destroy/spawned-snapshot?`) keys on, so recording and teardown
  classify ids identically (see the section comment above).

  PERF: `frame/frame-runtime-db-value` is a substrate `deref` returning the
  persistent runtime-db map BY REFERENCE (no copy, no scan), so the lookup
  is a direct `get-in` to the candidate id's snapshot root — O(1), no scan
  of the snapshot table. An app with no live spawned actors pays one
  by-reference deref + one path descent that misses."
  [frame-id event-id]
  (let [rt (frame/frame-runtime-db-value frame-id)]
    (when (map? rt)
      (let [snapshot (get-in rt (paths/snapshot-path event-id))]
        (when (some? (:rf/machine-type snapshot))
          event-id)))))

;; ---- late-bind hook registration ------------------------------------------
;;
;; The machines surface ships in
;; `day8/re-frame2-machines`. `re-frame.core` and `re-frame.test-support`
;; MUST NOT `:require [re-frame.machines]` — the artefact is optional, and
;; a static require would force every consumer of the core artefact to
;; drag the namespace's `:rf/machine` sub registration onto the classpath.
;; The public-API re-exports and the test-support reset helper are
;; published through the late-bind table; consumers without the
;; machines artefact see the hooks unregistered and the surface
;; throws / returns safe defaults cleanly.
;;
;; Per Spec 005 §reg-machine vs reg-machine*: the late-bind hook key
;; `:machines/reg-machine` points at `reg-machine*` — the plain-fn
;; surface. The `reg-machine` macro at the `re-frame.core` boundary is
;; import-time-only (CLJS macroexpansion runs before ns-load); the
;; runtime always reaches through this hook to the plain-fn surface.

(late-bind/set-fn! :machines/reg-machine            reg-machine*)
(late-bind/set-fn! :machines/make-machine-handler make-machine-handler)
(late-bind/set-fn! :machines/machine-transition     machine-transition)
(late-bind/set-fn! :machines/machines               machines)
(late-bind/set-fn! :machines/machine-meta           machine-meta)
(late-bind/set-fn! :machines/machine-by-system-id   machine-by-system-id)
(late-bind/set-fn! :machines/reset-timers!          reset-timers!)
;; Per-frame timer-table cleanup wired into `frame/destroy-frame!`.
;; The timer table is partitioned `{<frame-id> {…}}`; without this
;; hook a destroyed frame's inner table would linger as dead
;; bookkeeping and in-flight host-clock handles would survive teardown.
;; Late-bound so core never statically requires the machines artefact.
(late-bind/set-fn! :machines/on-frame-destroyed!
                   (fn [frame-id] (timer/cancel-all-timers! frame-id)))
;; Epoch-restore host-transient quiesce. The epoch restore boundary
;; (`perform-restore!`) installs the captured durable frame-state WHOLESALE,
;; but the in-flight `:after` host-clock timers the unwound epochs armed are
;; NOT frame-state — the pure per-path epoch invariant stale-drops a fired
;; timer, but its wall-clock handle would still fire unless released. This
;; hook releases the restored frame's orphaned timer handles eagerly (one
;; `:rf.machine.timer/cancelled :reason :on-restore` per entry), so a restore
;; carries no leaked timers from the discarded epochs (Managed-Effects §restore:
;; "epoch restore MUST NOT revive host work"). Late-bound so core / epoch never
;; statically require the machines artefact.
(late-bind/set-fn! :machines/on-frame-restored!
                   (fn [frame-id] (timer/cancel-frame-timers-on-restore! frame-id)))
;; Frame-destroy machine-cascade hook. `frame/destroy-frame!` calls this
;; hook BEFORE the sub-cache / adapter teardown so each active machine's
;; `:exit` cascade runs against a live container in reverse-creation order
;; per Spec 005 §Cross-Spec Interactions §1.
(late-bind/set-fn! :machines/teardown-on-frame-destroy!
                   frame-destroy/teardown-on-frame-destroy!)
;; Test-isolation hook fired by
;; `re-frame.test-support/make-reset-runtime-fixture`. Drops the per-frame
;; spawn-order vectors so a stale entry from a sibling test cannot
;; contaminate a frame-destroy walk in the next test.
(late-bind/set-fn! :machines/reset-spawn-order!
                   spawn-order/reset-all!)
(late-bind/set-fn! :machines/spawn-fx               spawn-fx)
(late-bind/set-fn! :machines/destroy-machine-fx     destroy-machine-fx)
;; The lazy actor-handler resolver. Core's dispatch consults this hook on
;; `:rf.error/no-such-handler` BEFORE erroring:
;; given an unresolved event-id (a spawned actor-id with no per-instance
;; registration), the resolver materialises the actor's handler-meta from
;; its (revertible) app-db snapshot, so `restore-epoch!` reverts actor
;; liveness with ZERO registrar drift. Returns nil when no live snapshot
;; exists → core surfaces the genuine `:no-such-handler` (correct: the
;; actor is not alive in this frame value). The companion
;; `:machines/actor-resolvable?` lets the epoch restore precondition
;; treat a spawned-actor snapshot whose TYPE still resolves as a VALID
;; restore target (not a `:rf.epoch/restore-missing-handler`).
(late-bind/set-fn! :machines/resolve-actor-handler-meta
                   registration/resolve-actor-handler-meta)
(late-bind/set-fn! :machines/actor-resolvable?      resolver/resolvable?)
;; The epoch restore version-drift precondition resolves a SPAWNED actor's
;; CURRENT definition the same way dispatch does: from the
;; snapshot's `:rf/machine-type` (a registered TYPE keyword resolved through
;; the registrar, or an inline `:definition` spec map carried verbatim).
;; `machine-version-mismatch` reads the resolved spec's
;; `[:meta :rf/snapshot-version]` so a spawned actor whose TYPE was hot-reloaded
;; forward surfaces `:rf.epoch/restore-version-mismatch` instead of silently
;; accepting an incompatible older snapshot (the snapshot key is an instance id,
;; not a registered handler, so the singleton registrar probe never matched it).
(late-bind/set-fn! :machines/spec-from-snapshot     resolver/spec-from-snapshot)
;; The post-commit walker the router AND-conjoins with
;; `validate-app-schema!` to gate the `:db` commit on the
;; `:where :machine-data` boundary (Spec 005 §Schema validation, Spec
;; 010 §Per-step recovery row 7).
(late-bind/set-fn! :machines/validate-machine-data! validate-machine-data!)
;; The SSR hydration projector for durable machine snapshots.
;; `re-frame.ssr.payload-policy/project-runtime-db` consults this hook to project
;; the `:rf.runtime/machines` slice so each snapshot's `:data` is redacted /
;; elided per the per-frame elision registry's classified `:sensitive` /
;; `:large` paths under `:rf.egress/ssr-hydration` BEFORE it rides the wire —
;; keeping classified machine data out of the hydration payload. EP-0025:
;; machine `:data` classification rides the projection-relative subsystem
;; declaration (lowered per actor under `:source :effect`); the commit-plane
;; effects are the general app-db mechanism.
;; Late-bound so SSR never statically requires the machines artefact.
(late-bind/set-fn! :machines/project-ssr-runtime-db
                   machines-ssr/project-ssr-runtime-db)
;; Spawned-actor ownership resolver. The http artefact consults this to
;; classify whether a managed request's originating event-id belongs to a
;; spawned actor (so an actor-destroy cascade can abort it). Machines OWNS
;; the registry shape, so the structural walk lives here; http reaches it
;; via late-bind and falls back to nil when machines is absent (apps without
;; state machines pay nothing). See the `owning-actor-id` docstring for the
;; set semantics (snapshot `:rf/machine-type` membership — declarative AND
;; imperative spawns).
(late-bind/set-fn! :machines/owning-actor-id        owning-actor-id)
(late-bind/set-fn! :machines/spawn-all-init-fx      spawn-all-init-fx)
(late-bind/set-fn! :machines/after-schedule-fx      after-schedule-fx)
(late-bind/set-fn! :machines/after-cancel-fx        after-cancel-fx)
(late-bind/set-fn! :machines/update-snapshot-fx     update-snapshot/update-snapshot-fx)

;; Load-order resilience for the `:rf.http/managed` machine-shape wrapper.
;; The wrapper is registered by re-frame.http.managed via the
;; `:machines/reg-machine` hook published above; but if http-managed loads
;; BEFORE this namespace (the load-order is determined by the consuming app's
;; require graph, not by either artefact), the wrapper's bottom-of-ns call
;; finds a nil hook and skips its registration. We close that race by
;; re-invoking the http artefact's `:http/register-managed-machine!` hook
;; from here — if http-managed is on the classpath the hook is set and the
;; wrapper registers now; if it isn't, the hook is nil and this is a no-op.
(when-let [reg-fn (late-bind/get-fn :http/register-managed-machine!)]
  (reg-fn))
