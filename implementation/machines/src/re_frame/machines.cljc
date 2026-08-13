(ns re-frame.machines
  "State machines. Per Spec 005.

  Implements the Spec 005 grammar:
    - Transition tables with :on, :entry, :exit, :guard, :action.
    - Flat states (single keyword) AND hierarchical states (vector path)
      with deepest-wins resolution and LCA exit/entry cascade.
    - :always microsteps with bounded depth and atomic rollback on
      depth-exceeded.
    - :after delayed transitions with per-node epoch staleness checks;
      delays may be positive integer literals, subscription vectors, or
      functions computed at state entry.
    - :timeout and :choice authoring forms lowered onto :after and :always.
    - Declared :internal-events that only the machine's raise queue may drive.
    - Declarative :spawn that desugars into [:rf.machine/spawn args]
      on entry and [:rf.machine/destroy actor-id] on exit; deterministic
      actor ids via the in-snapshot :rf/spawn-counter (declarative) / the
      frame's runtime-db [:rf.runtime/machines :spawn-counter <machine-id>]
      slot (hand-emitted) — no process-global state.
    - Declarative :spawn-all — spawn-and-join sugar over N parallel
      :spawn's plus a closed two-member join condition (:all / :any).
    - The :raise reserved fx-id (machine-internal pre-commit dispatch).
    - Named actor messaging through :rf.machine/dispatch-to-system.
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
            [re-frame.machines.lifecycle-fx.join :as join]
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
            ;; Keep tooling out of CLJS production bundles. CLJS tools require
            ;; the tooling namespace directly; JVM callers get facade aliases.
            #?@(:clj [[re-frame.machines.tooling :as machines-tooling]])))

#?(:clj (set! *warn-on-reflection* true))

;; ---- public-surface re-exports --------------------------------------------
;;
;; These `def`s make the sub-namespace fns reachable as
;; `re-frame.machines/<name>` so consumers (the `re-frame.core` late-bind
;; bridge, the conformance corpus, the test fixtures, examples that
;; `:require [re-frame.machines :as machines]`) all see one surface.

;; Declarative spawns allocate ids in the parent snapshot's
;; `:rf/spawn-counter`; hand-emitted spawns use the frame's runtime-db
;; `[:rf.runtime/machines :spawn-counter <machine-id>]` slot.
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
  default's reverse index. Pass the public opts form `(machine-by-system-id
  system-id {:frame target})` to look up a named frame from outside any
  scope (async callbacks / tools / cross-frame lookups); `target` is a
  frame-id keyword or a live frame value.

  The 2-arity distinguishes an opts map from the internal frame-last form.
  Because a live frame value is also a map, only a map that is not a frame
  value is treated as opts. An opts map without `:frame` uses the ambient
  frame.

  Per Spec 005 §Named addressing via :system-id + Spec 002 §Resolver
  surface."
  ([system-id]
   (machine-by-system-id
     system-id
     (frame/require-current-frame!
       :machine-by-system-id
       {:where 're-frame.machines/machine-by-system-id})))
  ([system-id frame-or-opts]
   ;; A live frame is map-shaped, so exclude frame values from the opts branch.
   (if (and (map? frame-or-opts) (not (frame/frame-value? frame-or-opts)))
     (if-some [target (:frame frame-or-opts)]
       (machine-by-system-id system-id target)
       (machine-by-system-id system-id))
     ;; The reverse index is runtime-db state keyed by the bare frame id.
     (get-in (frame/frame-runtime-db-value (frame/frame-target->id frame-or-opts))
             (paths/system-id-path system-id)))))

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
;; dropped, so the fx form is how an action sends a message to a NAMED
;; actor. Retained as the one named-addressing escape (advanced/parity
;; tier): zero in-repo consumers as of 2026-07-10, kept for XState v6
;; actor-system parity (systemId addressing — behavioural parity); the
;; facade audit at API-freeze rules on deletion with full information. The
;; redundant `dispatch-to-system` call-site FN twin was deleted (pre-alpha;
;; no alias, no tombstone) — the everyday send story is plain dispatch to
;; the id you hold (a machine IS an event handler).
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

(defn dispatch-to-system-fx
  "fx handler for `:rf.machine/dispatch-to-system`. Resolves `system-id`
  through the emitting frame's `[:rf.runtime/machines :system-ids]`
  reverse index and dispatches `event` to the bound actor. No-op when the
  system-id is unbound. Per Spec 005 §Cross-machine messaging by name."
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
  {:doc "Spawn a machine instance. Per Spec 005 §Declarative :spawn (sugar over spawn). Args carry `:machine-id`, optional `:system-id`, and optional `:data`."}
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
  {:doc "Dispatch an event to a spawned actor addressed by `:system-id`. Emit `[:rf.machine/dispatch-to-system [<system-id> <event-vector>]]` from a machine action's `:fx` vector. No-op when the system-id is unbound. The single named-addressing escape (advanced/parity tier); zero in-repo consumers, retained for XState v6 actor-system parity. Per Spec 005 §Cross-machine messaging by name."}
  dispatch-to-system-fx)

(fx/reg-fx :rf.machine/join-dispatch
  {:doc "Machine-internal (rf2-t154jx): the recordable transport for a `:spawn-all` child's completion carrier. The runtime rewrites a member child's own outbound `:dispatch` / `:dispatch-later` completion into this fx so the exact-attempt coordinate rides the recordable `:rf.cofx` `:rf.machine/join-attempt` fact (surviving strict replay + delayed dispatch), not event metadata. The coordinate is a recordable correlation record, not authentication; the fold gate accepts it only on exact-current equality (rf2-cpbjfp). Reserved / non-overridable / non-redirectable to protect the framework path from capture or suppression; direct app emission is UNSUPPORTED but not security-prohibited. Per Spec 005 §Spawn-and-join via :spawn-all."}
  join/join-dispatch-fx)

;; ---- framework-shipped subs -----------------------------------------------
;;
;; Per Spec 005 §Subscribing to machines via the :rf/machine sub: the
;; framework ships `:rf/machine` as the canonical entry point — read a
;; machine's snapshot with the subscription vector `(subscribe [:rf/machine
;; id])`.
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
  {:doc "Subscribe to a machine's current snapshot `{:state <kw> :data <map> :tags <set>}`. Returns nil for an unknown or not-yet-initialised machine. Per Spec 005 §Subscribing to machines via the :rf/machine sub."}
  (fn [runtime-db [_ machine-id]]
    (get-in runtime-db (paths/snapshot-path machine-id))))

;; Per Spec 005 §State tags: the `:rf.machine/has-tag?` framework sub
;; returns `true` iff the named machine's current snapshot's `:tags` set
;; contains the queried tag. A machine that hasn't been initialised yet (no
;; snapshot at `[:rf.runtime/machines :snapshots <id>]`) returns `false`.
;;
;; Derived sub — reads the snapshot via `get-in` rather than chaining
;; off `:rf/machine` — so a view that only cares about whether a specific
;; tag is present re-renders only when the containment-bit flips.
(subs/reg-runtime-sub :rf.machine/has-tag?
  {:doc "Subscribe to a machine's `:tags` containment-bit for `tag`. Returns `true` iff the named machine's snapshot's `:tags` set contains `tag`, `false` otherwise (including unknown / not-yet-initialised machines). Per Spec 005 §State tags."}
  (fn [runtime-db [_ machine-id tag]]
    (contains? (get-in runtime-db (paths/snapshot-path machine-id :tags)) tag)))

;; ---- framework-standard registration -------------------------------------
;;
;; Reserved machine effects and subscriptions must resolve in every image
;; generation. They have no namespace provenance, so image selection cannot
;; discover them; the framework-standard union is their generation path.
;; Standards are non-replaceable and use the same descriptors as the regular
;; registrar. Capture those descriptors at namespace load so reset can restore
;; both registries after `registrar/clear-all!`.

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
           [:fx  :rf.machine/join-dispatch]
           [:sub :rf/machine]
           [:sub :rf.machine/has-tag?]])))

(defn install-machine-runtime!
  "Re-register machine runtime effects and subscriptions in both the regular
  registrar and framework-standard registry. Idempotent.

  The regular registrar serves no-generation/default-image lookups. The
  standard registry supplies the same handlers to sealed image generations.

  Uses descriptors captured at namespace load, so it still works after the
  registrar has been cleared."
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
;; Managed HTTP ownership is machines-owned because it depends on the private
;; snapshot shape. A request belongs to `event-id` exactly when that id has a
;; live snapshot carrying `:rf/machine-type`. This includes declarative and
;; imperative spawns, excludes singleton machines, and matches the destroy
;; side's actor discriminator.

(defn owning-actor-id
  "Return `event-id` when it names a live spawned actor in `frame-id`, else nil.

  Spawned actors are identified by `:rf/machine-type` on their live snapshot;
  singleton machines do not carry that marker.

  Published through late-bind so HTTP does not depend on this optional
  artefact or duplicate its runtime-db shape. The lookup is a direct snapshot
  path read; it does not scan the actor table."
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
;; its revertible runtime-db snapshot, so epoch restore reverts actor
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
;; keeping classified machine data out of the hydration payload. Machine
;; declarations are lowered per actor under `:source :machine`.
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
