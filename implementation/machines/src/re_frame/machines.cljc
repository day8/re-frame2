;; day8/re-frame2-machines — state-machine artefact (rf2-xbtj,
;; second per-feature split per rf2-5vjj Strategy B).
;;
;; Per Spec 005 §State machines the machine grammar
;; (`reg-machine`, `create-machine-handler`, `machine-transition`,
;; the `:rf/machine` framework sub, the `:rf.machine/spawn` /
;; `:rf.machine/destroy` effect handlers) ships as a separate
;; Maven artefact so apps that don't register any machines don't drag
;; the namespace, the `:rf/machines` app-db slot's runtime support, or
;; the machine-transition engine onto the classpath.
;;
;; Consumers add this artefact alongside the core artefact:
;;
;;   {:deps {day8/re-frame2          {:mvn/version "..."}
;;           day8/re-frame2-machines {:mvn/version "..."}}}
;;
;; And require `re-frame.machines` in any namespace that calls
;; `rf/reg-machine` or relies on the `:rf/machine` framework sub —
;; loading the namespace registers its late-bind hooks and the
;; `:rf.machine/spawn` / `:rf.machine/destroy` reserved fxs.
;;
;; Per Spec 006 §Adapter shipping convention (and the
;; rf2-p7va extension to per-feature splits): this artefact depends on
;; core; core never depends on this artefact. The cross-references
;; from core to machines flow through `re-frame.late-bind` exactly as
;; the schemas / router / flows hooks already do.
;;
;; ---- file split (rf2-5hnn) ------------------------------------------------
;;
;; This namespace was 3152 LoC pre-split; it's now a thin façade.
;; The implementation is in four flat sub-namespaces (one each per
;; cohesive responsibility, per the X.Y convention shared with
;; `re-frame.story.runtime`, `re-frame.substrate.adapter`, and
;; `re-frame.core.flows`):
;;
;;   - `re-frame.machines.transition` — pure single-machine engine
;;     (deepest-wins resolution, LCA exit/entry cascade, `:raise` drain,
;;     `:always` microstep loop). `apply-transition-once` extracted into
;;     `build-after-fx` / `build-after-cancel-fx` / `build-destroy-fx` /
;;     `handle-invoke-spawn` / `handle-invoke-all-spawn` per rf2-g1s1.
;;   - `re-frame.machines.parallel` — parallel-region routing and the
;;     public `machine-transition` dispatch (single vs parallel). The
;;     bootstrap initial-entry cascade (`apply-initial-entry-cascade`)
;;     also lives here because the parallel layer owns region
;;     iteration.
;;   - `re-frame.machines.timer` — wall-clock `:after` scheduling
;;     (`:rf.machine/after-schedule` / `:rf.machine/after-cancel` fxs,
;;     sub-driven re-resolution, the per-frame timer table).
;;   - `re-frame.machines.lifecycle-fx` — registration boundary
;;     (`create-machine-handler`, `reg-machine*`), live-lifecycle fxs
;;     (`:rf.machine/spawn`, `:rf.machine/destroy`,
;;     `:rf.machine/invoke-all-init`), and the query API (`machines`,
;;     `machine-meta`, `machine-by-system-id`). `create-machine-handler`
;;     decomposed into `validate-machine!` + `synthesise-initial-
;;     snapshot` + the returned handler fn per rf2-f9tu.
;;
;; This façade re-exports the public surface of those sub-namespaces
;; and performs the artefact's load-time side-effects: the
;; `:rf.machine/*` fx registrations and the `late-bind/set-fn!` hook
;; publications that `re-frame.core` reaches through.

(ns re-frame.machines
  "State machines. Per Spec 005.

  Implements the v1 grammar:
    - Transition tables with :on, :entry, :exit, :guard, :action.
    - Flat states (single keyword) AND hierarchical states (vector path)
      with deepest-wins resolution and LCA exit/entry cascade.
    - :always microsteps with bounded depth and atomic rollback on
      depth-exceeded.
    - :after delayed transitions with per-machine :rf/after-epoch
      tracking; the synthetic :rf.machine.timer/after-elapsed event
      fires the transition only when the carried epoch matches. Per
      rf2-3y3y, `:after` delays admit three forms — pos-int? literal,
      subscription vector ([:sub-id & args]; re-resolves on sub change),
      and (fn [snapshot] ms) computed once at state entry.
    - Declarative :invoke that desugars into [:rf.machine/spawn args]
      on entry and [:rf.machine/destroy actor-id] on exit; deterministic
      actor ids via a per-process counter.
    - Declarative :invoke-all (rf2-6vmw) — spawn-and-join sugar over N
      parallel :invoke's plus a join condition (:all / :any / {:n N} /
      {:fn pred}).
    - The :raise reserved fx-id (machine-internal pre-commit dispatch).
    - Snapshot at [:rf/machines <id>] in app-db.
    - Pure machine-transition fn (JVM- and CLJS-runnable, deterministic).

  Public surface re-exported from the sub-namespaces:
    - `reg-machine*`, `create-machine-handler` —
      `re-frame.machines.lifecycle-fx`
    - `machine-transition` — `re-frame.machines.parallel` (the public
      dispatch; flat / compound delegates to
      `re-frame.machines.transition`'s `machine-transition-single`)
    - `machines`, `machine-meta`, `machine-by-system-id` —
      `re-frame.machines.lifecycle-fx`
    - `spawn-fx`, `destroy-machine-fx`, `invoke-all-init-fx` —
      `re-frame.machines.lifecycle-fx`
    - `after-schedule-fx`, `after-cancel-fx` — `re-frame.machines.timer`

  Conformance fixtures cover all of the above (machine-transition,
  hierarchical-{compound,cross-level,parent-fallthrough}-transition,
  always-{single-microstep,depth-exceeded}, after-{single-delay,
  stale-detection,hierarchy}, invoke-spawn-on-entry-destroy-on-exit,
  invoke-all-{join-all-completes,join-any-fails-cancels,n-of-cancels-extras})."
  (:require [re-frame.fx :as fx]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines.lifecycle-fx :as lifecycle-fx]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.timer :as timer]
            [re-frame.machines.transition :as transition]
            [re-frame.subs :as subs]))

;; ---- public-surface re-exports --------------------------------------------
;;
;; These `def`s make the sub-namespace fns reachable as
;; `re-frame.machines/<name>` so consumers (the `re-frame.core` late-bind
;; bridge, the conformance corpus, the test fixtures, examples that
;; `:require [re-frame.machines :as machines]`) see the same surface
;; they did pre-split.

;; Per rf2-gr8q the global `spawn-counter` atom is gone. Declarative-
;; :invoke spawns allocate ids inside the parent snapshot's
;; `:rf/spawn-counter` slot via `re-frame.machines.transition/
;; allocate-spawned-id`; hand-emitted spawn fxs allocate from the frame's
;; app-db slot at `[:rf/spawn-counter <machine-id>]` inside the spawn-fx
;; db-swap. `machine-transition` is now an honest pure function — no
;; module-level mutable state, deterministic from its (machine snapshot
;; event) arguments.

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

(defn reset-timers!
  "Cancel every in-flight `:after` timer the runtime is currently tracking.

  Per rf2-gr8q the per-process `spawn-counter` atom is gone — declarative-
  :invoke spawn-id allocation lives inside the parent snapshot's
  `:rf/spawn-counter` slot, and hand-emitted-spawn allocation lives inside
  the frame's app-db at `[:rf/spawn-counter <machine-id>]`. Both reset
  automatically: per-test snapshot rollback (via test-support's registrar
  snapshot/restore + frame reset) clears the app-db; per-fixture snapshot
  input in the conformance harness is hand-built fresh on each call. The
  only remaining per-process state this fn resets is the wall-clock timer
  table in `re-frame.machines.timer`."
  []
  (timer/cancel-all-timers!))

;; ---- machine-internal effect handlers ------------------------------------
;;
;; Per Spec 005 §Declarative :invoke (sugar over spawn) the runtime
;; effects `:rf.machine/spawn` and `:rf.machine/destroy` are emitted
;; into the fx vector by `apply-transition-once` whenever entry/exit
;; cascades cross an :invoke-bearing state. Per rf2-xbtj these handlers
;; live in this namespace (rather than `re-frame.fx`'s reserved
;; case-block) so an app that doesn't pull in
;; `day8/re-frame2-machines` carries neither the trace strings
;; (`:rf.machine/spawned`, `:rf.machine/destroyed`) nor the handler
;; symbols on its production-elision bundle.

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
;; Per Spec 005 §reg-machine vs reg-machine* (rf2-8bp3): the late-bind
;; hook key is `:machines/reg-machine` and points at `reg-machine*` —
;; the plain-fn surface. The `reg-machine` macro at the `re-frame.core`
;; boundary is import-time-only (CLJS macroexpansion runs before
;; ns-load); the runtime always reaches through this hook to the
;; plain-fn surface.

(late-bind/set-fn! :machines/reg-machine            reg-machine*)
(late-bind/set-fn! :machines/create-machine-handler create-machine-handler)
(late-bind/set-fn! :machines/machine-transition     machine-transition)
(late-bind/set-fn! :machines/machines               machines)
(late-bind/set-fn! :machines/machine-meta           machine-meta)
(late-bind/set-fn! :machines/machine-by-system-id   machine-by-system-id)
(late-bind/set-fn! :machines/reset-timers!          reset-timers!)
(late-bind/set-fn! :machines/spawn-fx               spawn-fx)
(late-bind/set-fn! :machines/destroy-machine-fx     destroy-machine-fx)
(late-bind/set-fn! :machines/invoke-all-init-fx     invoke-all-init-fx)
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
