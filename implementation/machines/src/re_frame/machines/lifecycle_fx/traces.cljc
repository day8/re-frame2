(ns re-frame.machines.lifecycle-fx.traces
  "Shared trace-emit helpers for the actor-destroy cascade.

  Per Spec 009 §`:op-type` vocabulary, `:rf.machine/destroyed` and
  `:rf.machine/system-id-released` ARE the contract surface that tools
  (Xray, story-mcp, re-frame-10x) key on for the actor-destroy signal.
  All destroy sites emit through this namespace to keep their argument-map
  keys and reply facts aligned.

  This namespace is the natural home (rather than `teardown.cljc`)
  because `teardown.cljc` is the unified pure runtime-db projection; it
  deliberately emits NO traces so the projection stays a value→value
  function. These helpers ARE side effects, so they live separately."
  (:require [re-frame.interop :as rf.interop]
            [re-frame.machines.error-emit :as rf.machines.error-emit]
            [re-frame.machines.reply :as rf.machines.reply]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

(defn emit-destroyed!
  "Fire `:rf.machine/destroyed` against the canonical argument map.

  Per the destroyed-trace-shape contract (assertion test in
  `re-frame.destroyed-trace-shape-test`), the permitted site-provided
  keys are `#{:frame :actor-id :system-id :parent-id :invoke-id
  :child-id :reason :work-bearing-path :work-generation}`. The final two are PRIVATE
  reply-correlation inputs and never become bare public trace tags.
  `:actor-id` is the destroyed actor's live INSTANCE
  address (`:machine-id` is reserved for the registered TYPE);
  `:invoke-id` is the declarative invocation path. Callers pass only the
  slots their site populates —
  `nil` values are stamped through, matching pre-consolidation behaviour
  (e.g. `destroy-single!` always stamps `:system-id`, even when nil;
  `destroy-spawn-all-children!` per-child fires omit `:system-id` by NOT
  passing the key).

  `reason` is the discriminator — `:explicit` for direct-destroy
  cascades, `:rf.machine/finished` for final-state auto-destroy.

  Only an `:explicit` destroy is a CANCELLATION of an IN-PROGRESS actor work
  attempt (the actor was torn down before reaching a terminal outcome), so
  ONLY it closes the work attempt the reply-envelope way: a
  `:status :cancelled` reply (cancellation as DATA, not the absence of a
  reply — Managed-Effects §Cancellation; EP-0011 §Cancellation). The
  reply-envelope facts (`:rf.reply/work-id` keyed on the destroyed actor instance,
  `:rf.reply/status :cancelled`, `:rf.reply/work-status :cancelled`,
  `:rf.reply/cancel-reason`) ride ADDITIVELY so the cancelled completion joins the
  same uniform work/reply row the spawn started, classified the same way
  the single-`:spawn` `:rf.machine/done` reply is.

  A NON-`:explicit` destroy is POST-COMPLETION cleanup, NOT a cancellation,
  so it carries NO cancelled reply facts (the second-terminal contradiction
  the `cancelled?` gate below avoids):

    - `:rf.machine/finished` — the actor already closed its attempt through
      `finalize-machine`'s `:rf.machine/done` reply. This covers a `:spawn-all`
      join child too: completion IS finality, so a child that folded into a
      join reached a `:final?` state and closed its own attempt on the way
      out. Only SURVIVORS remain for the join to tear down at resolution, and
      their teardown is a genuine `:explicit` cancellation.

  The public destroyed-trace shape is unchanged."
  [{:keys [frame actor-id system-id parent-id invoke-id child-id reason
           work-bearing-path work-generation]
    :or {reason :explicit}
    :as args}]
  (let [cancelled? (= reason :explicit)
        reply-ctx (cond-> {:actor-id          actor-id
                           :parent-id         parent-id
                           :work-bearing-path (or work-bearing-path invoke-id)
                           :frame             frame
                           :reason            reason}
                    (contains? args :work-generation)
                    (assoc :work-generation work-generation))
        summary    (when cancelled?
                     (rf.machines.reply/trace-reply
                       (rf.machines.reply/cancelled-actor-reply reply-ctx)
                       {:frame frame}))]
    (rf.trace/emit! :rf.machine :rf.machine/destroyed
                 (cond-> {:frame    frame
                          :actor-id actor-id
                          :reason   reason}
                   (contains? args :system-id) (assoc :system-id system-id)
                   (contains? args :parent-id) (assoc :parent-id parent-id)
                   (contains? args :invoke-id) (assoc :invoke-id invoke-id)
                   (contains? args :child-id)  (assoc :child-id  child-id)
                   cancelled? (assoc :rf.reply/work-kind            (:rf.reply/work-kind summary)
                                     :rf.reply/status      (:status summary)
                                     :rf.reply/work-id     (:rf.reply/work-id summary)
                                     :rf.reply/work-status (:rf.reply/work-status summary)
                                     :rf.reply/cancelled?  (:cancelled? summary)
                                     :rf.reply/cancel-reason (:rf.reply/cancel-reason summary)
                                     :rf.reply/correlation (:correlation summary))))))

(defn emit-destroy-bad-arg!
  "Fail loud when a `:rf.machine/destroy` fx receives a MAP arg the runtime
  cannot honour. The actor is NOT torn down and NO `:rf.machine/destroyed`
  trace fires — the fx refuses rather than silently choosing a semantic
  reason (rf2-3lyqzu).

  The closed `cause` vocabulary:

    - `:slot-shape-mismatch` — a well-formed tracked / spawn-all form whose
      addressed `[:spawned p i]` slot holds the OTHER form's shape
      (rf2-3phait): the tracked single-`:spawn` form resolving a `:spawn-all`
      join-state MAP (consuming it as an actor id would clear the join slot
      and orphan every live child), or the spawn-all form resolving an
      actor-id KEYWORD.

    - `:unknown-shape` — a map matching NONE of the known destroy shapes
      (the keyword `actor-id` form, the tracked `{:rf/parent-id :rf/invoke-id}`
      `:spawn` exit-cascade form, or the `:rf/spawn-all` form). Notably this is
      the pre-auth forgery
      `{:rf/actor-id … :rf/reason …}`, which used to mint a CALLER-chosen
      destroyed reason and thereby suppress the cancellation terminal of an
      in-progress actor at the public reserved-fx boundary — and every
      false-valued / wrong-typed discriminator, missing or wrongly-typed
      coordinate, and overlapping discriminator set (rf2-3phait).

  Diagnostic channel (DCE'd in production), matching the sibling forgery
  guard `:rf.error/machine-spawn-all-bad-child-id`: the SAFE behaviour (no
  teardown, no dishonest terminal) holds in every build; the loud dev trace
  names the offending arg. Per Spec 009 §Error event catalogue."
  [frame-id cause args]
  (rf.trace/emit-error! :rf.error/machine-destroy-bad-arg
                     {:cause    cause
                      :arg      args
                      :frame    frame-id
                      :recovery :no-teardown})
  nil)

(defn emit-system-id-released!
  "Per Spec 005 §Cancellation cascade D8 — fire
  `:rf.machine/system-id-released` when an actor's `:system-id` binding
  was released as part of teardown. No-op when `sid` is nil."
  [frame-id sid actor-id]
  (when sid
    (rf.trace/emit! :rf.machine :rf.machine/system-id-released
                 {:frame      frame-id
                  :system-id  sid
                  ;; the released actor's live INSTANCE address;
                  ;; `:machine-id` is reserved for the registered TYPE.
                  :actor-id   actor-id})))

(defn emit-destroy-exit-failure!
  "Fire the destroy-time exit-cascade failure trace
  (`:rf.error/machine-action-exception`, `:phase :rf.machine/destroy-exit`)
  when an actor's active-configuration `:exit` cascade threw during
  teardown. Both destroy-time `:exit` runners — the explicit-destroy
  wrapper (`lifecycle-fx.exit-cascade/run-child-exit!`) and the
  final-state auto-destroy (`lifecycle-fx.finalize/finalize-machine`) —
  fire the SAME diagnostic shape; centralising it here keeps the
  `:phase` discriminator + reason string one-edit-touches-both, matching
  `apply-transition-once`'s transition-time exit-cascade failure category
  with a destroy-time discriminator. Fail-soft: the destroy proceeds with
  the pre-cascade snapshot."
  [actor-id frame-id result-info]
  ;; A destroy-time `:exit` action throw uses both error channels. `:failing-id`
  ;; is the throwing `:exit` action's keyword when named (else the actor
  ;; instance); `:state` is the active state path off the failure info.
  (let [action-ref (:action-ref result-info)
        failing-id (if (keyword? action-ref) action-ref actor-id)
        state-path (:state-path result-info)
        exception  (:exception result-info)]
    ;; Axis 1 — STRUCTURAL-ONLY always-on record (no prose; see error-emit).
    (rf.machines.error-emit/emit-machine-action-exception!
      {:actor-id   actor-id
       :failing-id failing-id
       :state      state-path
       :frame      frame-id
       :phase      :rf.machine/destroy-exit
       :recovery   :skipped
       :exception  exception})
    ;; Axis 2 — dev-only trace. Wrapped in an EXPLICIT `rf.interop/debug-enabled?`
    ;; call-site gate so Closure constant-folds the whole form — including the
    ;; dev-only diagnostic PROSE `:reason` — away under :advanced +
    ;; goog.DEBUG=false (009 elision probe: `emit-destroy-exit-failure!` prose
    ;; sentinel). The internal gate inside `rf.trace/emit-error!` is not enough on
    ;; its own here because this function also contains the live always-on call;
    ;; the explicit gate lets Closure eliminate the dev-only map and prose.
    (when rf.interop/debug-enabled?
      (rf.trace/emit-error! :rf.error/machine-action-exception
                         {:actor-id   actor-id
                          :machine-id actor-id
                          :failing-id failing-id
                          :state      state-path
                          :frame      frame-id
                          :phase      :rf.machine/destroy-exit
                          :exception  exception
                          :reason     "An :exit action threw during destroy-time cascade."
                          :recovery   :skipped
                          :info       result-info}))))
