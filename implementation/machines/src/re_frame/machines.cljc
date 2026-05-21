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
      actor ids via a per-process counter.
    - Declarative :spawn-all — spawn-and-join sugar over N parallel
      :spawn's plus a join condition (:all / :any / {:n N} / {:fn pred}).
    - The :raise reserved fx-id (machine-internal pre-commit dispatch).
    - Snapshot at [:rf/machines <id>] in app-db.
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
    - `spawn-fx`, `invoke-all-init-fx` —
      `re-frame.machines.lifecycle-fx.spawn`
    - `destroy-machine-fx` — `re-frame.machines.lifecycle-fx.destroy`
    - `after-schedule-fx`, `after-cancel-fx` — `re-frame.machines.timer`"
  (:require [re-frame.frame :as frame]
            [re-frame.fx :as fx]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines.lifecycle-fx.destroy :as destroy]
            [re-frame.machines.lifecycle-fx.frame-destroy :as frame-destroy]
            [re-frame.machines.lifecycle-fx.registration :as registration]
            [re-frame.machines.lifecycle-fx.spawn :as spawn]
            [re-frame.machines.lifecycle-fx.validation :as validation]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.spawn-order :as spawn-order]
            [re-frame.machines.timer :as timer]
            [re-frame.registrar :as registrar]
            [re-frame.subs :as subs]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- public-surface re-exports --------------------------------------------
;;
;; These `def`s make the sub-namespace fns reachable as
;; `re-frame.machines/<name>` so consumers (the `re-frame.core` late-bind
;; bridge, the conformance corpus, the test fixtures, examples that
;; `:require [re-frame.machines :as machines]`) see the same surface
;; they did pre-split.

;; Declarative-`:spawn` spawns allocate ids inside the parent
;; snapshot's `:rf/spawn-counter` slot via
;; `re-frame.machines.transition/allocate-spawned-id`; hand-emitted
;; spawn fxs allocate from the frame's app-db slot at
;; `[:rf/spawn-counter <machine-id>]` inside the spawn-fx db-swap.
;; `machine-transition` is a pure function — no module-level mutable
;; state, deterministic from its (machine snapshot event) arguments.

(def reg-machine*           registration/reg-machine*)
(def make-machine-handler registration/make-machine-handler)
;; The pure registration-time validator (Spec 005 §registration validators,
;; rf2-f9tu). Re-exported so the conformance corpus's `:reg-machine` Mode-B
;; call op can pin the registration-error taxonomy (Spec 009 §thrown-error
;; shape) directly against the leaf fn — no registrar/substrate fixture.
(def validate-machine!      validation/validate-machine!)
(def spawn-fx               spawn/spawn-fx)
(def invoke-all-init-fx     spawn/invoke-all-init-fx)
(def destroy-machine-fx     destroy/destroy-machine-fx)
(def machine-transition     parallel/machine-transition)
(def after-schedule-fx      timer/after-schedule-fx)
(def after-cancel-fx        timer/after-cancel-fx)

;; ---- query API (Spec 005 §Querying machines) -----------------------------
;;
;; Three thin lookup fns over the existing event registry and the
;; runtime-owned `[:rf/system-ids]` reverse index — derived views, not a
;; new registry kind. `(rf/machines)` filters event handlers whose
;; registration metadata carries `:rf/machine? true`; `(rf/machine-meta
;; id)` returns the registered machine's spec map; `(rf/machine-by-
;; system-id sid)` resolves the spawned-machine id currently bound to
;; `sid` in the active frame's `[:rf/system-ids]` reverse index.
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
  `:guards`, `:actions`, `:states`, `:doc`, source coords) for
  `machine-id`, or nil if no machine is registered under that id. Per
  Spec 005 §Querying machines."
  [machine-id]
  (let [m (registrar/lookup :event machine-id)]
    (when (:rf/machine? m)
      (:rf/machine m))))

(defn machine-by-system-id
  "Look up the spawned-machine id currently bound to `system-id` in the
  active frame's `[:rf/system-ids]` reverse index, or nil. The `frame`
  arg defaults to the current frame (per `frame/current-frame`); pass
  an explicit frame-id for cross-frame lookups.

  Per Spec 005 §Named addressing via :system-id."
  ([system-id]
   (machine-by-system-id system-id (frame/current-frame)))
  ([system-id frame-id]
   (get-in (frame/frame-app-db-value frame-id) [:rf/system-ids system-id])))

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
  app-db at `[:rf/spawn-counter <machine-id>]` (hand-emitted spawn);
  both reset automatically with the registrar snapshot/restore + frame
  reset, so this hook only handles the frame-scoped wall-clock timer
  table. The 0-arity / 1-arity split aligns with the test-fixture and
  frame-destroy call sites respectively."
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
;; neither the trace strings (`:rf.machine/spawned`,
;; `:rf.machine/destroyed`) nor the handler symbols on its production-
;; elision bundle.

(fx/reg-fx :rf.machine/spawn
  {:doc "Spawn a machine instance. Per Spec 005 §Declarative :spawn (sugar over spawn). Args carry `:machine-id`, optional `:system-id`, and optional `:initial-data`."}
  spawn-fx)

(fx/reg-fx :rf.machine/destroy
  {:doc "Destroy a spawned machine instance and clear its `[:rf/machines machine-id]` slot. Per Spec 005 §Declarative :spawn."}
  destroy-machine-fx)

(fx/reg-fx :rf.machine/spawn-all-init
  {:doc "Machine-internal: fire `:initial-entry` cascades for every machine spawned at app boot. Per Spec 005 §Initial entry. Not for direct application use."}
  invoke-all-init-fx)

(fx/reg-fx :rf.machine/after-schedule
  {:doc "Machine-internal: schedule an `:after` timer event for a machine state. Per Spec 005 §Timed transitions. Not for direct application use."}
  after-schedule-fx)

(fx/reg-fx :rf.machine/after-cancel
  {:doc "Machine-internal: cancel a previously-scheduled `:after` timer. Per Spec 005 §Timed transitions. Not for direct application use."}
  after-cancel-fx)

;; ---- framework-shipped subs -----------------------------------------------
;;
;; Per Spec 005 §Subscribing to machines via sub-machine: the framework
;; ships `:rf/machine` as the canonical entry point. `(rf/sub-machine id)`
;; in `re-frame.core` is sugar over `(subscribe [:rf/machine id])`.
;;
;; Registered at the façade (rather than in `re-frame.machines.lifecycle-
;; fx`) so the smoke-test fixture's `(require 're-frame.machines :reload)`
;; re-installs the subs after `registrar/clear-all!`. `:reload` is
;; shallow — a sub registered inside the sub-namespace wouldn't re-fire.

(subs/reg-sub :rf/machine
  {:doc "Subscribe to a machine's current snapshot `{:state <kw> :data <map> :tags <set>}`. Returns nil for an unknown or not-yet-initialised machine. Per Spec 005 §Subscribing to machines via sub-machine."}
  (fn [db [_ machine-id]]
    (get-in db [:rf/machines machine-id])))

;; Per Spec 005 §State tags (rf2-ee0d / Nine States Stage 1): the
;; `:rf/machine-has-tag?` framework sub returns `true` iff the named
;; machine's current snapshot's `:tags` set contains the queried tag.
;; A machine that hasn't been initialised yet (no snapshot at
;; `[:rf/machines <id>]`) returns `false`.
;;
;; Derived sub — reads the snapshot via `get-in` rather than chaining
;; off `:rf/machine` — so a view that only cares about whether a specific
;; tag is present re-renders only when the containment-bit flips.
(subs/reg-sub :rf/machine-has-tag?
  {:doc "Subscribe to a machine's `:fsm/tags` containment-bit for `tag`. Returns `true` iff the named machine's snapshot's `:tags` set contains `tag`, `false` otherwise (including unknown / not-yet-initialised machines). Per Spec 005 §State tags (rf2-ee0d / Nine States Stage 1)."}
  (fn [db [_ machine-id tag]]
    (contains? (get-in db [:rf/machines machine-id :tags]) tag)))

;; ---- late-bind hook registration ------------------------------------------
;;
;; Per rf2-xbtj the machines surface ships in
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
;; Per rf2-vsigt — frame-destroy machine-cascade hook. `frame/destroy-frame!`
;; calls this hook BEFORE the sub-cache / adapter teardown so each active
;; machine's `:exit` cascade runs against a live container in reverse-
;; creation order per Spec 005 §Cross-Spec Interactions §1.
(late-bind/set-fn! :machines/teardown-on-frame-destroy!
                   frame-destroy/teardown-on-frame-destroy!)
;; Per rf2-vsigt — test-isolation hook fired by
;; `re-frame.test-support/make-reset-runtime-fixture`. Drops the per-frame
;; spawn-order vectors so a stale entry from a sibling test cannot
;; contaminate a frame-destroy walk in the next test.
(late-bind/set-fn! :machines/reset-spawn-order!
                   spawn-order/reset-all!)
(late-bind/set-fn! :machines/spawn-fx               spawn-fx)
(late-bind/set-fn! :machines/destroy-machine-fx     destroy-machine-fx)
(late-bind/set-fn! :machines/spawn-all-init-fx      invoke-all-init-fx)
(late-bind/set-fn! :machines/after-schedule-fx      after-schedule-fx)
(late-bind/set-fn! :machines/after-cancel-fx        after-cancel-fx)

;; rf2-ijm7 — load-order resilience for the `:rf.http/managed` machine-shape
;; wrapper. The wrapper is registered by re-frame.http-managed via the
;; `:machines/reg-machine` hook published above; but if http-managed loaded
;; BEFORE this namespace (the load-order is determined by the consuming app's
;; require graph, not by either artefact), the wrapper's bottom-of-ns call
;; found a nil hook and skipped its registration. We close that race by
;; re-invoking the http artefact's `:http/register-managed-machine!` hook
;; from here — if http-managed is on the classpath the hook is set and the
;; wrapper registers now; if it isn't, the hook is nil and this is a no-op.
(when-let [reg-fn (late-bind/get-fn :http/register-managed-machine!)]
  (reg-fn))
