(ns re-frame.machines.lifecycle-fx
  "Registration boundary and live-lifecycle fx handlers for state machines.

  Per rf2-3sbb6 the original 1439-LoC monolith was split along the four
  banner-line concerns the file's own opening docstring described, plus
  one further split between the `:invoke-all` join interceptor and the
  final-state orchestrator, and a final extraction of registration-time
  validation. Each leaf file is single-responsibility and addressable on
  its own:

    - `re-frame.machines.lifecycle-fx.validation` — registration-time
      validators (`validate-machine!` and friends — rf2-f9tu / rf2-l67o /
      rf2-6vmw / rf2-3y3y / rf2-oz9t / rf2-gn80).
    - `re-frame.machines.lifecycle-fx.join` — `:invoke-all` join-event
      interception (`intercept-invoke-all-event` — rf2-6vmw).
    - `re-frame.machines.lifecycle-fx.finalize` — final-state orchestration
      (`finalize-machine`, `all-regions-final?`, `abort-actor-in-flight-
      http!` — rf2-gn80 / rf2-wvkn).
    - `re-frame.machines.lifecycle-fx.registration` — handler factory
      (`create-machine-handler`) and `reg-machine*` plain-fn surface
      (rf2-f9tu / rf2-8bp3).
    - `re-frame.machines.lifecycle-fx.spawn` — `:rf.machine/spawn` and
      `:rf.machine/invoke-all-init` fx handlers.
    - `re-frame.machines.lifecycle-fx.destroy` — `:rf.machine/destroy` fx
      handler (the keyword/single-`:invoke` form AND the `:invoke-all`
      children-iteration form — rf2-t07u / rf2-6vmw).

  This namespace is the public façade — it re-exports the symbols the
  outer `re-frame.machines` façade and the late-bind table reach
  through, and it owns the query API (`machines`, `machine-meta`,
  `machine-by-system-id` — Spec 005 §Querying machines)."
  (:require [re-frame.frame :as frame]
            [re-frame.machines.lifecycle-fx.destroy :as destroy]
            [re-frame.machines.lifecycle-fx.registration :as registration]
            [re-frame.machines.lifecycle-fx.spawn :as spawn]
            [re-frame.registrar :as registrar]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- public-surface re-exports --------------------------------------------

(def create-machine-handler registration/create-machine-handler)
(def reg-machine*           registration/reg-machine*)
(def spawn-fx               spawn/spawn-fx)
(def invoke-all-init-fx     spawn/invoke-all-init-fx)
(def destroy-machine-fx     destroy/destroy-machine-fx)

;; ---- query API (Spec 005 §Querying machines) -----------------------------
;;
;; Three thin lookup fns over the existing event registry and the
;; runtime-owned `[:rf/system-ids]` reverse index — derived views, not a
;; new registry kind. `(rf/machines)` filters event handlers whose
;; registration metadata carries `:rf/machine? true`; `(rf/machine-meta
;; id)` returns the registered machine's spec map; `(rf/machine-by-
;; system-id sid)` resolves the spawned-machine id currently bound to
;; `sid` in the active frame's `[:rf/system-ids]` reverse index.

(defn machines
  "Return a sequence of machine-ids — every event handler whose
  registration metadata carries `:rf/machine? true`. Per Spec 005
  §Querying machines."
  []
  (->> (registrar/handlers :event)
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

;; Framework-shipped subscriptions (`:rf/machine`, `:rf/machine-has-tag?`)
;; are registered in the public-façade `re-frame.machines` namespace so
;; `(require 're-frame.machines :reload)` in a test fixture's reset path
;; re-installs them after `registrar/clear-all!`. Keeping the
;; `subs/reg-sub` calls at the façade level avoids the implicit
;; reload-locality trap — `:reload` is shallow, so a sub registered in
;; a sub-namespace wouldn't be re-fired by a façade-only reload.
