(ns re-frame.machines.lifecycle-fx.exit-cascade
  "Destroy-time `:exit` cascade runner.

  Per Spec 005 §Declarative `:spawn` §Composition with explicit
  `:entry` / `:exit`: when a `:spawn`-spawned actor is destroyed, the
  user's `:exit` action gets to read the actor's final snapshot before
  the auto-destroy clears it. Per §Final states §Composition with
  `:entry` / `:exit`: a final state's `:exit` runs from the auto-
  destroy teardown, same ordering convention.

  Centralises the four destroy entry-points — explicit
  `:rf.machine/destroy`, declarative-`:spawn` exit-cascade destroy,
  `:spawn-all` per-child teardown, and final-state auto-destroy —
  so every destroy path runs through `run-child-exit!` and the
  active configuration's `:exit` actions fire before the snapshot
  is torn down.

  The pure exit cascade (path resolution + action collection) lives in
  `re-frame.machines.parallel/run-active-exit-cascade` (which dispatches
  flat/compound to `re-frame.machines.transition`, and parallel to the
  per-region reduce). This file is the side-effect wrapper that:
    1. resolves the actor's snapshot from the frame's app-db,
    2. resolves the actor's machine spec from the registrar,
    3. runs the pure cascade,
    4. writes the post-cascade snapshot back to app-db so any caller
       reading the snapshot AFTER `:exit` (e.g. `finalize-machine`'s
       `:on-done` projection — though that read happens before this
       runs, the write here is for tools observing the snapshot
       between `:exit` and teardown),
    5. fires the cascade's fx vector through `re-frame.fx/do-fx` so
       `:exit`-time side effects (HTTP requests, dispatches, logs)
       actually run,
    6. surfaces a `:rf.error/machine-action-exception` trace if any
       `:exit` action threw (consistent with the standard exit-cascade
       failure handling in `apply-transition-once`).

  Idempotent / safe to call when the actor has no live snapshot or no
  registered machine spec — both reduce to a benign no-op (the destroy
  proceeds as before)."
  (:require [re-frame.fx :as fx]
            [re-frame.frame :as frame]
            [re-frame.machines.lifecycle-fx.traces :as traces]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.paths :as paths]
            [re-frame.machines.result :as result]
            [re-frame.registrar :as registrar]))

#?(:clj (set! *warn-on-reflection* true))

(defn- resolve-machine-spec
  "Look up `actor-id`'s machine spec via the registrar's `:rf/machine`
  metadata (per `re-frame.machines/machine-meta`). Returns
  nil if no machine is registered under `actor-id` — the actor was
  already torn down, or the destroy targets a non-machine event id."
  [actor-id]
  (when actor-id
    (let [m (registrar/lookup :event actor-id)]
      (when (:rf/machine? m)
        (:rf/machine m)))))

(defn run-child-exit!
  "Run the destroy-time `:exit` cascade for the actor identified by
  `actor-id` in frame `frame-id`. No-op when the actor has no live
  snapshot at `[:rf/runtime :machines :snapshots actor-id]` or no registered machine spec.

  On a successful cascade: writes the post-cascade snapshot back to
  app-db (so callers reading the snapshot between `:exit` and the
  teardown projection see `:exit`-time `:data` writes) and fires the
  emitted fx vector via `re-frame.fx/do-fx`. On a thrown action,
  surfaces a `:rf.error/machine-action-exception` trace (matching the
  standard transition-time exit-cascade failure category) and skips
  the fx fire — fail-soft, the destroy proceeds.

  Returns nil. Per Spec 005 §Final states §Composition with `:entry` /
  `:exit` — `:exit` runs BEFORE the auto-destroy teardown; per
  §Declarative `:spawn` §Composition — `:exit` reads the actor's
  final snapshot before clearing."
  [frame-id actor-id]
  (when actor-id
    (let [db       (frame/frame-app-db-value frame-id)
          snapshot (when db (get-in db (paths/snapshot-path actor-id)))
          machine  (resolve-machine-spec actor-id)]
      (when (and snapshot machine)
        (let [r (parallel/run-active-exit-cascade machine snapshot)]
          (if (result/fail? r)
            ;; Match `apply-transition-once`'s exit-cascade failure
            ;; trace shape — same category, with a destroy-time
            ;; discriminator so consumers can disambiguate. Shared with
            ;; `finalize-machine`'s final-state auto-destroy via
            ;; `traces/emit-destroy-exit-failure!`.
            (traces/emit-destroy-exit-failure! actor-id frame-id (result/info r))
            (result/with-ok [new-snap exit-fx] r
              ;; (4) Write the post-exit snapshot back. The write is
              ;; transient — the unified teardown projection runs
              ;; immediately after this and dissocs `[:rf/runtime :machines :snapshots
              ;; actor-id]`. Tools that observe the app-db between
              ;; `:exit` and teardown see the `:exit`-time `:data`
              ;; writes; the production-runtime cost is one extra
              ;; swap-frame-db! per destroy.
              (when (not= snapshot new-snap)
                (frame/swap-frame-db! frame-id
                                      (fn [d] (assoc-in d (paths/snapshot-path actor-id) new-snap))))
              ;; (5) Fire the `:exit`-emitted fx via the standard fx
              ;; interpreter. Use the frame's `:platform` (defaults to
              ;; :client) so platform-gated fx behave consistently with
              ;; transition-time fx fires.
              (when (seq exit-fx)
                (let [platform (or (:platform (frame/frame-meta frame-id)) :client)]
                  (fx/do-fx frame-id (vec exit-fx) platform))))))))
    nil))
