(ns re-frame.machines.lifecycle-fx.registration
  "Registration boundary: handler factory + `reg-machine*` (rf2-f9tu).

  `create-machine-handler` is the event-fx handler factory beneath the
  `reg-machine` macro; `reg-machine*` is the plain-fn surface used by
  the late-bind table and by REPL workflows. Per rf2-f9tu the factory
  is decomposed into:

    - `validate-machine!` — every registration-time check (extracted to
      `re-frame.machines.lifecycle-fx.validation`: parallel shape,
      `:invoke-all` shape, dropped `:timeout-ms` slots, guard/action
      ref resolution, final-state shape).
    - `synthesise-initial-snapshot` — initial-state cascade, `:data` /
      `:meta` seeding, tag union stamping (lazily, on first event).
    - the returned handler fn — frame stamping, `intercept-invoke-all-
      event` branch (in `lifecycle-fx.join`), bootstrap-pending
      detection + initial-entry cascade, machine-transition dispatch,
      action-failure projection, finalize delegation (in
      `lifecycle-fx.finalize`)."
  (:require [re-frame.events :as events]
            [re-frame.frame :as frame]
            [re-frame.machines.lifecycle-fx.finalize :as finalize]
            [re-frame.machines.lifecycle-fx.join :as join]
            [re-frame.machines.lifecycle-fx.validation :as validation]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.result :as result]
            [re-frame.machines.transition :as transition]
            [re-frame.trace :as trace]))

(defn- synthesise-initial-snapshot
  "Build the lazily-synthesised initial snapshot for `machine` (rf2-f9tu).

  Per Spec 005 §Initial-state cascading and §Parallel regions: for flat /
  compound machines, descends the root `:initial` cascade to a leaf path;
  for parallel-region machines, `:state` becomes a map of region-name →
  that region's cascaded initial.

  Per Spec 005 §Snapshot shape (`{:state :data :meta?}`): the spec's
  optional `:meta` propagates onto the snapshot so the 3-arity ctx and
  any downstream version-check see the same `:meta` the spec declares.

  Per Spec 005 §State tags (rf2-ee0d) / §Tags compose across regions
  (rf2-l67o): the initial tag union is stamped via
  `commit-tags-parallel`; the slot is elided when the union is empty."
  [machine]
  (let [initial-state (cond
                        (parallel/parallel? machine)
                        (into {}
                              (for [[rn region-body] (:regions machine)]
                                [rn (parallel/region-initial-state region-body)]))

                        :else
                        (let [decl (:initial machine)]
                          (transition/denormalise-state
                            (transition/initial-cascade machine (transition/state-path decl))
                            decl)))
        initial    (cond-> {:state            initial-state
                            :data             (or (:data machine) {})
                            ;; Per rf2-gr8q: the spawn-id allocator lives
                            ;; in-snapshot at `:rf/spawn-counter` so that
                            ;; `machine-transition` is an honest pure
                            ;; function. The slot is always present on
                            ;; live snapshots (seeded here at registration
                            ;; time); pure-call snapshots (the conformance
                            ;; harness) may omit it — the reducer treats
                            ;; absent slots as 0 via fnil-update.
                            :rf/spawn-counter {}}
                     (some? (:meta machine)) (assoc :meta (:meta machine)))]
    (parallel/commit-tags-parallel machine initial)))

;; ---- handler factory ------------------------------------------------------

(defn- route-inner-event
  "Sub-event routing per Spec 005 §Registration. The outer event is
  `[:machine-id <inner-event> & extra-args]`. Extra args are conj'd onto
  the inner event — the convention http-style fx callbacks rely on:

    :on-success [:machine-id [:inner-id]]   →
    (conj on-success response) yields
    [:machine-id [:inner-id] response]      →
    inner-event = [:inner-id response]"
  [event]
  (if (and (vector? event)
           (>= (count event) 2)
           (vector? (second event)))
    (let [inner (second event)
          extra (drop 2 event)]
      (if (seq extra)
        (vec (concat inner extra))
        inner))
    event))

(defn- trace-action-failure!
  "Emit `:rf.error/machine-action-exception` for a `result/fail` Result's
  `::result/info` map. Returns `{}` so the handler short-circuits."
  [machine-id event frame-id info reason]
  (let [ex         (:exception info)
        ex-msg     #?(:clj  (when ex (.getMessage ^Throwable ex))
                      :cljs (when ex (.-message ex)))
        ex-data    (when ex (ex-data ex))
        action-ref (:action-ref info)]
    (trace/emit-error! :rf.error/machine-action-exception
                       {:machine-id        machine-id
                        :action-id         action-ref
                        :state-path        (:state-path info)
                        :transition        (:transition info)
                        :event             event
                        :failing-id        machine-id
                        :handler-id        machine-id
                        :frame             frame-id
                        :exception         ex
                        :exception-message ex-msg
                        :exception-data    ex-data
                        :reason            reason
                        :recovery          :no-recovery})
    {}))

(defn create-machine-handler
  "Returns a function suitable for registration with `reg-event-fx`.

  Per Spec 005 §Registration — the machine IS the event handler. The
  machine spec MUST NOT carry `:id`; the machine's id is the surrounding
  registration's event-id, derived at handler-call time from the
  dispatched event vector's first element.

  Per rf2-f9tu the body is decomposed into:
    - `validation/validate-machine!` — every registration-time check.
    - `synthesise-initial-snapshot` — initial-state cascade, `:data` /
      `:meta` seeding, tag union stamping.
    - the returned handler fn — frame stamping, intercept-invoke-all-
      event branch (in `lifecycle-fx.join`), bootstrap-pending detection
      + initial-entry cascade, machine-transition dispatch, action-failure
      projection, finalize delegation (in `lifecycle-fx.finalize`)."
  [machine]
  (validation/validate-machine! machine)
  ;; Per rf2-f9tu — `synthesise-initial-snapshot` runs lazily INSIDE the
  ;; returned handler, not at registration time. The initial-state
  ;; computation reaches through `:initial` / `:states` / `:regions`;
  ;; running it at registration time would force every registered spec
  ;; (including the stub child machines the conformance corpus declares
  ;; without `:initial` for `:invoke` targets, and any spec whose
  ;; `:initial` derives from a fn-form computed at dispatch time) to
  ;; satisfy the snapshot shape at reg-machine call time. The original
  ;; (pre-split) implementation deferred this work; preserve that.
  (let [base-initial (delay (synthesise-initial-snapshot machine))]
    (fn [{:keys [db frame] :as _cofx} event]
      (let [machine-id (first event)
            frame-id   (or frame :rf/default)
            ;; Per Spec 009 §:op-type vocabulary:
            ;; `:rf.machine/event-received` fires at the top of the
            ;; handler so consumers see the inbound event before any
            ;; state derivation.
            _ (trace/emit! :rf.machine/event-received :rf.machine/event-received
                           {:machine-id machine-id
                            :event      event
                            :frame      frame-id})
            ;; Stamp the live frame + platform + parent-id onto the
            ;; machine def so spawn-id allocation (frame-scoped per Spec
            ;; 002) gets the right key, `:after` scheduling can gate on
            ;; `:server`, and `apply-transition-once` can emit spawn /
            ;; destroy fx whose args carry `:rf/parent-id` (used by the
            ;; fx handlers to address the runtime-owned
            ;; `[:rf/spawned <parent-id> <invoke-id>]` registry slot).
            platform   (or (:platform (frame/frame-meta frame-id)) :client)
            machine    (assoc machine
                              :rf/frame     frame-id
                              :rf/platform  platform
                              :rf/parent-id machine-id)
            path       [:rf/machines machine-id]
            ;; Per rf2-0z73: detect "first event for this machine" so the
            ;; initial-state's `:entry` actions fire as part of bringing
            ;; the machine to life. Two flavours:
            ;;
            ;;   - Singleton path: `(get-in db path)` is `nil` — the
            ;;     snapshot is being lazily synthesised right now.
            ;;
            ;;   - Spawn path: `spawn-fx` pre-seeded the snapshot at
            ;;     `[:rf/machines <spawned-id>]` and stamped
            ;;     `:rf/bootstrap-pending? true` so the actor's first
            ;;     dispatch sees the marker and runs the cascade before
            ;;     processing the event.
            existing-snap    (get-in db path)
            needs-bootstrap? (or (nil? existing-snap)
                                 (true? (:rf/bootstrap-pending? existing-snap)))
            snapshot         (if (nil? existing-snap)
                               (assoc @base-initial :rf/bootstrap-pending? true)
                               existing-snap)
            inner-event      (route-inner-event event)
            intercepted      (join/intercept-invoke-all-event machine db path snapshot machine-id inner-event)
            ;; Per rf2-0z73: when the snapshot is bootstrap-pending, run
            ;; the initial-state entry cascade once before processing the
            ;; user event. Bootstrap fx flow OUT of the handler ahead of
            ;; any fx the user event produces — entry happens-before user
            ;; event handling.
            boot-result
            (if (and needs-bootstrap? (not intercepted))
              (parallel/apply-initial-entry-cascade machine snapshot)
              (result/ok snapshot []))
            boot-failed?     (result/fail? boot-result)
            post-boot-snap   (when-not boot-failed?
                               (dissoc (::result/snap boot-result)
                                       :rf/bootstrap-pending?))
            boot-fx          (when-not boot-failed?
                               (::result/fx boot-result))]
        (cond
          intercepted
          intercepted

          boot-failed?
          (trace-action-failure! machine-id [:rf.machine/bootstrap] frame-id
                                 (::result/info boot-result)
                                 "Machine initial-entry action threw.")

          :else
          (let [step-result (parallel/machine-transition machine post-boot-snap inner-event)]
            (if (result/fail? step-result)
              (trace-action-failure! machine-id inner-event frame-id
                                     (::result/info step-result)
                                     "Machine action threw.")
              (let [{next-snapshot ::result/snap fx ::result/fx} step-result
                    merged-fx (vec (concat boot-fx fx))
                    ;; Per Spec 005 §Final states (rf2-gn80): recompute
                    ;; the finality flag at the lifecycle-handler
                    ;; boundary against the post-transition snapshot.
                    ;; For single / compound machines, look up the leaf
                    ;; node and check `:final?`. For parallel-region
                    ;; machines, the parent is `:final?` only when every
                    ;; region's leaf is `:final?`. The pure-transition
                    ;; surface stays free of runtime-only metadata.
                    finished? (or (and (not (parallel/parallel? machine))
                                       (transition/final-on-leaf? machine (:state next-snapshot)))
                                  (finalize/all-regions-final? machine (:state next-snapshot)))
                    new-db (assoc-in db path next-snapshot)]
                (trace/emit! :machine :rf.machine/transition
                             {:machine-id machine-id
                              :event      inner-event
                              :before     snapshot
                              :after      next-snapshot})
                (when (not= snapshot next-snapshot)
                  (trace/emit! :rf.machine/snapshot-updated :rf.machine/snapshot-updated
                               {:machine-id machine-id
                                :path       path
                                :before     snapshot
                                :after      next-snapshot
                                :frame      frame-id}))
                (if finished?
                  (finalize/finalize-machine machine machine-id frame-id
                                             new-db next-snapshot inner-event merged-fx)
                  {:db new-db
                   :fx merged-fx})))))))))

;; ---- reg-machine* — plain-fn surface (rf2-8bp3) ---------------------------

(defn reg-machine*
  "Plain-fn surface beneath the `reg-machine` macro. Registers a machine
  as an event handler under `machine-id`. Equivalent to
  `(reg-event-fx machine-id (create-machine-handler machine))`.

  Per Spec 005 §reg-machine vs reg-machine*: the macro `reg-machine`
  walks the literal spec form at expansion time and stamps per-element
  source coordinates onto the spec's `:rf.machine/source-coords` key.
  This fn assumes no such walking — it accepts whatever spec map the
  caller has already constructed. Use this fn for runtime registration
  with computed ids, fixture-synthesised specs, or REPL workflows.

  Per Spec 005 §Querying machines, the registration metadata is stamped
  with `:rf/machine? true` and `:rf/machine` (the spec map).
  `(rf/machines)` filters the `:event` registry by `:rf/machine?`;
  `(rf/machine-meta id)` reads the spec back out via the standard
  registrar query API.

  Per Spec 001 §Source-coordinate capture, the call-site `:ns` /
  `:line` / `:file` carried by `re-frame.source-coords/*pending-coords*`
  (set by the `reg-machine` macro) is merged into the registration
  metadata via the `reg-event-fx` defn's `merge-coords` call."
  [machine-id machine]
  (let [handler-fn (create-machine-handler machine)]
    (events/reg-event-fx machine-id
                         {:rf/machine? true
                          :rf/machine  machine}
                         handler-fn)
    (trace/emit! :rf.machine.lifecycle/created :rf.machine.lifecycle/created
                 {:machine-id machine-id
                  :initial    (:initial machine)})
    machine-id))
