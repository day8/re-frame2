(ns re-frame.machines.lifecycle-fx.finalize
  "Final-state orchestration (rf2-gn80).

  Per Spec 005 §Final states (rf2-gn80): when a machine enters a `:final?`
  state, the runtime fires the parent's `:on-done` (if any) and auto-
  destroys the actor SYNCHRONOUSLY (D4). The orchestration:

    1. Resolve the final state-node from the post-transition snapshot
       (single / compound / parallel-all-regions-final).
    2. Read the child's `:data` slot designated by the final state's
       `:output-key` — call it `result`. Absent `:output-key` ⇒ nil.
    3. Look up the parent's spec at `[:rf/machines <parent-id>]` and find
       the `:invoke` map at `:rf/invoke-id` (the prefix-path the runtime
       stamped on the child's `:data` at spawn time). Extract `:on-done`.
    4. Run `:on-done` against the parent's `:data` with `result`.
    5. Emit `:rf.machine/done` trace (D6).
    6. Tear down the child: dissoc snapshot, clear `[:rf/spawned ...]`
       slot, clear `[:rf/system-ids <sid>]` (D8 — AFTER `:on-done` ran),
       emit `:rf.machine/destroyed` with `:reason :rf.machine/finished`
       (D6 enrichment), abort in-flight HTTP, unregister handler (D4).

  For singleton machines (no `:rf/parent-id` on `:data`): skip steps 3-4
  and emit a `:rf.machine/done` with `:parent-id nil` (D7 — singleton
  symmetry). The teardown still runs — the snapshot is dissoc'd, the
  `:system-id` reverse-index entry is cleared, and the handler is
  unregistered.

  This namespace also owns `abort-actor-in-flight-http!` — the late-bind
  hook into the http-managed artefact (rf2-wvkn) — because both the
  finalize cascade and the spawn-destroy teardowns invoke it.

  Per rf2-lha2t the actor-teardown app-db dance lives in
  `re-frame.machines.lifecycle-fx.teardown` — one helper, three (now
  unified) call-sites."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.machines.lifecycle-fx.teardown :as teardown]
            [re-frame.machines.lifecycle-fx.traces :as traces]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.result :as result]
            [re-frame.machines.transition :as transition]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- in-flight HTTP abort cascade (rf2-wvkn) ------------------------------
;;
;; Per Spec 005 §Cancellation cascade — in-flight `:rf.http/managed`
;; aborts: when a spawned state-machine actor is destroyed, every
;; in-flight `:rf.http/managed` request the actor had issued is
;; aborted. The abort is fired through the late-bind hook
;; `:http/abort-on-actor-destroy` — re-frame.machines does NOT
;; statically `:require` re-frame.http-managed; the destroy path
;; looks up this fn at call time.

(defn abort-actor-in-flight-http!
  "Fire the late-bind hook that aborts every in-flight `:rf.http/managed`
  request for the destroyed actor. Idempotent and safe to call when
  the http artefact is absent (returns nil)."
  [actor-id]
  (when actor-id
    (when-let [abort! (late-bind/get-fn :http/abort-on-actor-destroy)]
      (try (abort! actor-id)
           (catch #?(:clj Throwable :cljs :default) _ nil))))
  nil)

;; ---- final-state resolution -----------------------------------------------

(defn all-regions-final?
  "Per Spec 005 §Final states §Parallel regions and `:final?` (rf2-gn80):
  a parallel-region machine is `:final?` only when EVERY region's active
  leaf is `:final?`. Walk each region's body + active path and check the
  leaf-node's `:final?` flag. Returns false when ANY region's leaf isn't
  final, or when `:state` is not a parallel state-map."
  [machine state]
  (and (parallel/parallel? machine)
       (map? state)
       (every?
         (fn [[region-name region-state]]
           (let [region-body (get-in machine [:regions region-name])
                 leaf-node   (transition/node-at region-body
                                                  (transition/state-path region-state))]
             (transition/final-state-node? leaf-node)))
         state)))

(defn- find-invoke-spec-at
  "Walk `parent-spec`'s state tree to the node at `invoke-id` (the
  absolute prefix-path stamped at spawn time) and return that node's
  `:invoke` map. For a parallel-region parent, the first element of
  `invoke-id` is the region name (per rf2-l67o); strip and descend
  into that region's body. Returns nil if the path doesn't resolve
  or the node doesn't declare `:invoke`."
  [parent-spec invoke-id]
  (when (and parent-spec (vector? invoke-id) (seq invoke-id))
    (let [[head & tail] invoke-id
          [tree path]   (if (and (parallel/parallel? parent-spec)
                                 (contains? (:regions parent-spec) head))
                          [(get-in parent-spec [:regions head]) (vec tail)]
                          [parent-spec invoke-id])
          node          (transition/node-at tree path)]
      (:invoke node))))

;; ---- the orchestrator ------------------------------------------------------

(defn finalize-machine
  "Per Spec 005 §Final states (rf2-gn80): orchestrate the `:on-done` +
  auto-destroy cascade. Returns `{:db new-db :fx fx}` — the handler's
  return value when the post-transition snapshot is tagged
  `:rf/finished? true`.

  Arguments:
    machine        — the runtime-stamped machine spec (the finishing actor's)
    machine-id     — the finishing actor's id (its event-handler key)
    frame-id       — the frame the actor runs in
    db             — the app-db AT the time the handler was invoked
    next-snapshot  — the post-transition snapshot (carries `:rf/finished?`)
    _inner-event   — the event that caused the finish (for diagnostics)
    extra-fx       — the fx vector from the transition (passed through)"
  [machine machine-id frame-id db next-snapshot _inner-event extra-fx]
  ;; (rf2-nahfm) Run the actor's active configuration `:exit` cascade
  ;; FIRST so the final state's `:exit` actions fire from the auto-
  ;; destroy teardown (Spec 005 §Final states §Composition with
  ;; `:entry` / `:exit`). The cascade is pure — it returns a new
  ;; snapshot + fx vector; we project the `:data` writes onto
  ;; `next-snapshot` so `:on-done`'s `result` computation observes
  ;; them, and we append `exit-fx` to the returned `:fx` so any
  ;; `:exit`-emitted dispatches / HTTP / etc. run.
  ;;
  ;; If any `:exit` action threw, we emit the standard exit-cascade
  ;; failure trace and proceed with the pre-cascade snapshot (fail-
  ;; soft — the destroy must complete to keep the registrar +
  ;; spawn-slot consistent).
  (let [exit-result   (parallel/run-active-exit-cascade machine next-snapshot)
        exit-ok?      (result/ok? exit-result)
        next-snapshot (if exit-ok? (result/snap exit-result) next-snapshot)
        exit-fx       (if exit-ok? (vec (result/fx exit-result)) [])
        db            (if exit-ok?
                        ;; Project the post-`:exit` snapshot back into
                        ;; the db so any reader between `:exit` and the
                        ;; teardown projection (e.g. `:on-done` reading
                        ;; the child via `(:rf/machines)`) sees the
                        ;; final state's writes.
                        (assoc-in db [:rf/machines machine-id] next-snapshot)
                        db)
        _             (when (not exit-ok?)
                        (trace/emit-error! :rf.error/machine-action-exception
                                           {:machine-id machine-id
                                            :frame      frame-id
                                            :phase      :rf.machine/destroy-exit
                                            :reason     "An :exit action threw during destroy-time cascade."
                                            :recovery   :skipped
                                            :info       (result/info exit-result)}))]
  (let [child-data (:data next-snapshot)
        ;; Resolve the final state-node so we can extract `:output-key`.
        final-node (cond
                     (parallel/parallel? machine)
                     ;; All regions are final per `all-regions-final?` — pick
                     ;; any region's leaf for the output-key resolution. The
                     ;; conventional choice is the first region's leaf; apps
                     ;; with cross-region output needs declare `:output-key`
                     ;; on every region's terminal leaf consistently.
                     (let [state (:state next-snapshot)
                           [region-name region-state] (first state)
                           region-body (get-in machine [:regions region-name])]
                       (transition/node-at region-body
                                            (transition/state-path region-state)))

                     :else
                     (transition/node-at machine
                                          (transition/state-path (:state next-snapshot))))
        output-key  (:output-key final-node)
        result      (when output-key (get child-data output-key))
        parent-id   (:rf/parent-id child-data)
        invoke-id   (:rf/invoke-id child-data)
        ;; (1) Find parent's `:on-done`, if this is an `:invoke`-spawned
        ;; actor. The parent's spec carries the `:invoke` map at
        ;; `invoke-id`.
        parent-meta (when parent-id
                      (let [m (registrar/lookup :event parent-id)]
                        (when (:rf/machine? m) (:rf/machine m))))
        invoke-spec (when (and parent-meta invoke-id)
                      (find-invoke-spec-at parent-meta invoke-id))
        on-done-fn  (:on-done invoke-spec)
        parent-path [:rf/machines parent-id]
        ;; (2) Emit `:rf.machine/done` trace BEFORE the destroy cascade
        ;; (D6 ordering).
        _ (trace/emit! :machine :rf.machine/done
                       {:machine-id machine-id
                        :output     result
                        :parent-id  parent-id
                        :frame      frame-id})
        ;; (3) Apply :on-done to the parent's `:data`. The parent's
        ;; snapshot lives at [:rf/machines <parent-id>]; we read it,
        ;; pass `:data` + `result` to `:on-done`, and write the new
        ;; `:data` back. If the parent has no snapshot (spawned a
        ;; child but was itself destroyed) or no `:on-done`, this
        ;; reduces to identity.
        db-after-on-done
        (if (and on-done-fn parent-id)
          (let [parent-snap     (get-in db parent-path)
                parent-data     (:data parent-snap)
                new-parent-data (try
                                  (on-done-fn parent-data result)
                                  (catch #?(:clj Throwable :cljs :default) e
                                    (trace/emit-error! :rf.error/machine-action-exception
                                                       {:machine-id machine-id
                                                        :action-id  :rf.invoke/on-done
                                                        :parent-id  parent-id
                                                        :invoke-id  invoke-id
                                                        :exception  e
                                                        :reason     ":on-done callback threw."
                                                        :recovery   :no-recovery})
                                    parent-data))]
            (if (and parent-snap (some? new-parent-data))
              (assoc-in db (conj parent-path :data) new-parent-data)
              db))
          db)
        ;; (4) Apply the unified teardown projection (per rf2-lha2t):
        ;; dissoc the child's snapshot, release any `:system-id`
        ;; reverse-index entry (D8 — after on-done ran), and clear the
        ;; parent's `[:rf/spawned <parent-id> <invoke-id>]` slot with
        ;; the lazy-allocation prune. Returns `[new-db released-sid]`;
        ;; `released-sid` is resolved against db-after-on-done before
        ;; the reverse index is mutated.
        [db-after-destroy released-sid]
        (teardown/teardown-actor db-after-on-done
                                 {:actor-id  machine-id
                                  :parent-id parent-id
                                  :invoke-id invoke-id})
        ;; (5) Emit :rf.machine/destroyed with :reason :rf.machine/finished
        ;; (D6 enrichment) BEFORE the registrar unregister so any in-flight
        ;; trace consumers see the destroy signal while the handler still
        ;; resolves.
        _ (traces/emit-destroyed! {:frame     frame-id
                                   :actor-id  machine-id
                                   :system-id released-sid
                                   :parent-id parent-id
                                   :invoke-id invoke-id
                                   :reason    :rf.machine/finished})]
    ;; (6) Synchronous side effects: abort in-flight HTTP, emit
    ;; system-id-released trace (when applicable), unregister handler.
    (abort-actor-in-flight-http! machine-id)
    (traces/emit-system-id-released! frame-id released-sid machine-id)
    (registrar/unregister! :event machine-id)
    {:db db-after-destroy
     ;; rf2-nahfm — append the destroy-time `:exit` cascade's fx to
     ;; the transition's fx vector so any `:exit`-emitted dispatches /
     ;; HTTP / etc. fire as part of the same epoch.
     :fx (vec (concat extra-fx exit-fx))})))
