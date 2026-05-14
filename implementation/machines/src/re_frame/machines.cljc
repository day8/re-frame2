(ns re-frame.machines
  "State machines. Per Spec 005.

  Public façade for `day8/re-frame2-machines`. The implementation lives
  in `re-frame.machines.{transition, parallel, timer, lifecycle-fx}`
  (plus the `lifecycle_fx/*` sub-namespaces); this ns re-exports their
  public surface and runs the artefact's load-time side-effects —
  registering the `:rf.machine/*` reserved fxs and publishing the
  `late-bind/set-fn!` hooks that `re-frame.core` reaches through.

  Implements the v1 grammar:
    - Transition tables with :on, :entry, :exit, :guard, :action.
    - Flat states (single keyword) AND hierarchical states (vector path)
      with deepest-wins resolution and LCA exit/entry cascade.
    - :always microsteps with bounded depth and atomic rollback on
      depth-exceeded.
    - :after delayed transitions with per-machine :rf/after-epoch
      tracking; delays admit three forms — pos-int? literal,
      subscription vector ([:sub-id & args]; re-resolves on sub change),
      and (fn [snapshot] ms) computed once at state entry.
    - Declarative :invoke that desugars into [:rf.machine/spawn args]
      on entry and [:rf.machine/destroy actor-id] on exit; deterministic
      actor ids via a per-process counter.
    - Declarative :invoke-all — spawn-and-join sugar over N parallel
      :invoke's plus a join condition (:all / :any / {:n N} / {:fn pred}).
    - The :raise reserved fx-id (machine-internal pre-commit dispatch).
    - Snapshot at [:rf/machines <id>] in app-db.
    - Pure machine-transition fn (JVM- and CLJS-runnable, deterministic)."
  (:require [re-frame.fx :as fx]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines.lifecycle-fx :as lifecycle-fx]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.timer :as timer]
            [re-frame.machines.transition :as transition]
            [re-frame.subs :as subs]))

;; ---- public-surface re-exports --------------------------------------------
;; Declarative-:invoke spawns allocate ids inside the parent snapshot's
;; `:rf/spawn-counter` slot via `transition/allocate-spawned-id`;
;; hand-emitted spawn fxs allocate from the frame's app-db slot at
;; `[:rf/spawn-counter <machine-id>]` inside the spawn-fx db-swap, so
;; `machine-transition` is a pure function — deterministic from
;; (machine snapshot event).

(def reg-machine*           lifecycle-fx/reg-machine*)
(def create-machine-handler lifecycle-fx/create-machine-handler)
(def machines               lifecycle-fx/machines)
(def machine-meta           lifecycle-fx/machine-meta)
(def machine-by-system-id   lifecycle-fx/machine-by-system-id)
(def spawn-fx               lifecycle-fx/spawn-fx)
(def destroy-machine-fx     lifecycle-fx/destroy-machine-fx)
(def invoke-all-init-fx     lifecycle-fx/invoke-all-init-fx)
(def machine-transition     parallel/machine-transition)
(def after-schedule-fx      timer/after-schedule-fx)
(def after-cancel-fx        timer/after-cancel-fx)

;; Per Spec 005 §3-arity escape hatch — `:state` / `:meta` introspection:
;; opt in via the `:rf.machine/wants-ctx` metadata flag. The `wants-ctx`
;; helper is the wrapper form for anonymous fns where the
;; `^:rf.machine/wants-ctx` reader-macro form is awkward.
(def wants-ctx              transition/wants-ctx)

(defn reset-timers!
  "Cancel in-flight `:after` timers.

  0-arity: every frame's timers — the fixture-teardown shape used by
  `re-frame.test-support`'s `reset-runtime` and per-feature artefact test
  fixtures. Clears the entire frame-scoped table.

  1-arity: just the given frame's timers — the `frame/destroy-frame!`
  hook shape used to release a destroyed frame's host-clock handles and
  subscription watchers without touching sibling frames.

  The wall-clock timer table is frame-scoped, so the 0-arity / 1-arity
  split aligns with the test-fixture and frame-destroy call sites
  respectively. Spawn-id allocation lives inside the parent snapshot's
  `:rf/spawn-counter` slot (or in the frame's app-db at
  `[:rf/spawn-counter <machine-id>]` for hand-emitted spawns) and resets
  automatically via the per-test snapshot rollback / fresh fixture input."
  ([]
   (timer/cancel-all-timers!))
  ([frame-id]
   (timer/cancel-all-timers! frame-id)))

;; ---- machine-internal effect handlers ------------------------------------
;; The handlers live here (rather than `re-frame.fx`'s reserved
;; case-block) so an app that doesn't pull in `day8/re-frame2-machines`
;; carries neither the trace strings nor the handler symbols on its
;; production-elision bundle.

(fx/reg-fx :rf.machine/spawn
  {:doc "Spawn a machine instance. Per Spec 005 §Declarative :invoke (sugar over spawn). Args carry `:machine-id`, optional `:system-id`, and optional `:initial-data`."}
  spawn-fx)

(fx/reg-fx :rf.machine/destroy
  {:doc "Destroy a spawned machine instance and clear its `[:rf/machines machine-id]` slot. Per Spec 005 §Declarative :invoke."}
  destroy-machine-fx)

(fx/reg-fx :rf.machine/invoke-all-init
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

;; Per Spec 005 §State tags: derived sub reading the snapshot via
;; `get-in` rather than chaining off `:rf/machine`, so a view that
;; only cares about whether a specific tag is present re-renders
;; only when the containment-bit flips.
(subs/reg-sub :rf/machine-has-tag?
  {:doc "Subscribe to a machine's `:fsm/tags` containment-bit for `tag`. Returns `true` iff the named machine's snapshot's `:tags` set contains `tag`, `false` otherwise (including unknown / not-yet-initialised machines). Per Spec 005 §State tags."}
  (fn [db [_ machine-id tag]]
    (contains? (get-in db [:rf/machines machine-id :tags]) tag)))

;; ---- late-bind hook registration ------------------------------------------
;; The `:machines/reg-machine` hook points at `reg-machine*` — the
;; plain-fn surface. The `reg-machine` macro at the `re-frame.core`
;; boundary is import-time-only; the runtime always reaches through
;; this hook to the plain-fn surface.

(late-bind/set-fn! :machines/reg-machine            reg-machine*)
(late-bind/set-fn! :machines/create-machine-handler create-machine-handler)
(late-bind/set-fn! :machines/machine-transition     machine-transition)
(late-bind/set-fn! :machines/machines               machines)
(late-bind/set-fn! :machines/machine-meta           machine-meta)
(late-bind/set-fn! :machines/machine-by-system-id   machine-by-system-id)
(late-bind/set-fn! :machines/reset-timers!          reset-timers!)
;; Per-frame timer-table cleanup wired into `frame/destroy-frame!`.
;; Without this hook the destroyed frame's inner table lingers as
;; dead bookkeeping and any in-flight host-clock handles survive
;; teardown.
(late-bind/set-fn! :machines/on-frame-destroyed!
                   (fn [frame-id] (timer/cancel-all-timers! frame-id)))
(late-bind/set-fn! :machines/spawn-fx               spawn-fx)
(late-bind/set-fn! :machines/destroy-machine-fx     destroy-machine-fx)
(late-bind/set-fn! :machines/invoke-all-init-fx     invoke-all-init-fx)
(late-bind/set-fn! :machines/after-schedule-fx      after-schedule-fx)
(late-bind/set-fn! :machines/after-cancel-fx        after-cancel-fx)

;; Load-order resilience: if http-managed loaded BEFORE this ns the
;; `:rf.http/managed` machine-shape wrapper would have skipped its
;; registration on a then-nil `:machines/reg-machine`. Re-invoke its
;; hook from here so either load order resolves correctly. No-op when
;; http-managed is absent.
(when-let [reg-fn (late-bind/get-fn :http/register-managed-machine!)]
  (reg-fn))
