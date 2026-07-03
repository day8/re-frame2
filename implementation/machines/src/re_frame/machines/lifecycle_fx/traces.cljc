(ns re-frame.machines.lifecycle-fx.traces
  "Shared trace-emit helpers for the actor-destroy cascade.

  Per Spec 009 §`:op-type` vocabulary, `:rf.machine/destroyed` and
  `:rf.machine/system-id-released` ARE the contract surface that tools
  (Xray, story-mcp, re-frame-10x) key on for the actor-destroy signal.
  Independent emission sites = independent chances to drift in the
  argument-map keys; the three `:rf.machine/destroyed` sites and the two
  `:rf.machine/system-id-released` sites emit through this single home so a
  key rename is one-edit-touches-all.

  This namespace is the natural home (rather than `teardown.cljc`)
  because `teardown.cljc` is the unified pure app-db projection — it
  deliberately emits NO traces so the projection stays a value→value
  function. These helpers ARE side effects, so they live separately."
  (:require [re-frame.machines.error-emit :as machine-error-emit]
            [re-frame.machines.reply :as m-reply]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

(defn emit-destroyed!
  "Fire `:rf.machine/destroyed` against the canonical argument map.

  Per the destroyed-trace-shape contract (assertion test in
  `re-frame.destroyed-trace-shape-test`), the permitted site-provided
  keys are `#{:frame :actor-id :system-id :parent-id :invoke-id
  :child-id :reason}`. `:actor-id` is the destroyed actor's live INSTANCE
  address (`:machine-id` is reserved for the registered TYPE);
  `:invoke-id` is the declarative invocation path. Callers pass only the
  slots their site populates —
  `nil` values are stamped through, matching pre-consolidation behaviour
  (e.g. `destroy-single!` always stamps `:system-id`, even when nil;
  `destroy-spawn-all-children!` per-child fires omit `:system-id` by NOT
  passing the key).

  `reason` is the discriminator — `:explicit` for direct-destroy
  cascades, `:rf.machine/finished` for final-state auto-destroy.

  An `:explicit` destroy is a CANCELLATION of an in-progress actor work
  attempt (the actor was torn down before reaching a `:final?` leaf), so
  it closes the work attempt the reply-envelope way: a
  `:status :cancelled` reply (cancellation as DATA, not the absence of a
  reply — Managed-Effects §Cancellation; EP-0011 §Cancellation). The
  reply-envelope facts (`:work/id` keyed on the destroyed actor instance,
  `:rf.reply/status :cancelled`, `:rf.reply/work-status :cancelled`,
  `:cancel/reason`) ride ADDITIVELY so the cancelled completion joins the
  same uniform work/reply row the spawn started, classified the same way
  the single-`:spawn` `:rf.machine/done` reply is. A `:rf.machine/finished`
  destroy is NOT a cancellation — the actor already closed its attempt
  through `finalize-machine`'s `:rf.machine/done` reply — so it carries no
  cancelled reply facts. The public destroyed-trace shape is unchanged."
  [{:keys [frame actor-id system-id parent-id invoke-id child-id reason]
    :or {reason :explicit}
    :as args}]
  (let [cancelled? (= reason :explicit)
        summary    (when cancelled?
                     (m-reply/trace-reply
                       (m-reply/cancelled-actor-reply
                         {:actor-id          actor-id
                          :parent-id         parent-id
                          :work-bearing-path invoke-id
                          :frame             frame
                          :reason            reason})
                       {:frame frame}))]
    (trace/emit! :rf.machine :rf.machine/destroyed
                 (cond-> {:frame    frame
                          :actor-id actor-id
                          :reason   reason}
                   (contains? args :system-id) (assoc :system-id system-id)
                   (contains? args :parent-id) (assoc :parent-id parent-id)
                   (contains? args :invoke-id) (assoc :invoke-id invoke-id)
                   (contains? args :child-id)  (assoc :child-id  child-id)
                   cancelled? (assoc :work/id              (:work/id summary)
                                     :work/kind            (:work/kind summary)
                                     :rf.reply/status      (:status summary)
                                     :rf.reply/work-id     (:work/id summary)
                                     :rf.reply/work-status (:work/status summary)
                                     :rf.reply/cancelled?  (:cancelled? summary)
                                     :rf.reply/cancel-reason (:cancel/reason summary)
                                     :rf.reply/correlation (:correlation summary))))))

(defn emit-system-id-released!
  "Per Spec 005 §Cancellation cascade D8 — fire
  `:rf.machine/system-id-released` when an actor's `:system-id` binding
  was released as part of teardown. No-op when `sid` is nil."
  [frame-id sid actor-id]
  (when sid
    (trace/emit! :rf.machine :rf.machine/system-id-released
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
  ;; A destroy-time `:exit` action throw is a machine action exception, so it
  ;; rides the same always-on-plus-dev-trace fan-out (rf2-cprm0q). `:failing-id`
  ;; is the throwing `:exit` action's keyword when named (else the actor
  ;; instance); `:state` is the active state path off the failure info.
  (let [action-ref (:action-ref result-info)]
    (machine-error-emit/emit-machine-action-exception!
      {:actor-id   actor-id
       :machine-id actor-id
       :failing-id (if (keyword? action-ref) action-ref actor-id)
       :state      (:state-path result-info)
       :frame      frame-id
       :phase      :rf.machine/destroy-exit
       :exception  (:exception result-info)
       :reason     "An :exit action threw during destroy-time cascade."
       :recovery   :skipped
       :info       result-info})))
