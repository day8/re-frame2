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
    - `parallel/build-initial-snapshot` (rf2-fgqs4) — initial-state
      cascade, `:data` / `:meta` / `:rf/spawn-counter` seeding, tag union
      stamping (lazily, on first event). Single source of truth shared
      with the spawn path.
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
            [re-frame.machines.result :as result
             #?@(:cljs [:include-macros true])]
            [re-frame.machines.transition :as transition]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; Per rf2-fgqs4 the initial-snapshot builder lives in `parallel.cljc` as
;; `build-initial-snapshot` — single source of truth for both the
;; singleton-registration path (here) and the spawn path
;; (`lifecycle-fx.spawn/install-spawn!`). The two used to drift: the spawn
;; path silently omitted `:rf/spawn-counter` and `:meta`. See
;; `parallel/build-initial-snapshot` for the canonical 6-step shape.

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

;; ---- 4-step pipeline (rf2-2zzyg) ------------------------------------------
;;
;; The handler-fn returned by `create-machine-handler` decomposes into four
;; named pure-fn steps, each ≤ 30 LoC, that read onto Spec 005 §Drain
;; semantics §Level 3 directly:
;;
;;   1. `prepare-machine-ctx`  — stamp frame / platform / parent-id, look
;;      up the existing snapshot, decide `needs-bootstrap?`, route the
;;      inner event. Returns a `ctx` map carrying everything downstream
;;      steps read.
;;   2. `maybe-boot`           — if bootstrap-pending and not intercepted,
;;      run `apply-initial-entry-cascade`. Returns a Result whose `::snap`
;;      is the post-boot snapshot with `:rf/bootstrap-pending?` cleared.
;;   3. `run-step`             — call `machine-transition` on the
;;      post-boot snapshot + inner event. Returns the Result from the
;;      pure engine.
;;   4. `commit-or-finalize`   — emit transition / snapshot-updated traces,
;;      build new-db, route to `finalize-machine` if `finished?`.
;;
;; The intercept-invoke-all-event short-circuit is the visible top-level
;; branch in `create-machine-handler` itself — it must short-circuit before
;; boot / step / commit run.

(defn- prepare-machine-ctx
  "Step 1 of 4 (rf2-2zzyg). Stamp the live frame / platform / parent-id
  onto the machine def, look up the existing snapshot at
  `[:rf/machines <machine-id>]`, decide `needs-bootstrap?`, route the
  inner event. Returns a `ctx` map the remaining three steps read.

  Per rf2-0z73: detect 'first event for this machine' so the initial
  state's `:entry` actions fire as part of bringing the machine to life.
  Two flavours:
    - Singleton path: `(get-in db path)` is `nil` — the snapshot is
      being lazily synthesised right now.
    - Spawn path: `spawn-fx` pre-seeded the snapshot at
      `[:rf/machines <spawned-id>]` and stamped
      `:rf/bootstrap-pending? true` so the actor's first dispatch sees
      the marker and runs the cascade before processing the event."
  [db frame event machine base-initial]
  (let [machine-id    (first event)
        frame-id      (or frame :rf/default)
        platform      (or (:platform (frame/frame-meta frame-id)) :client)
        machine       (assoc machine
                             :rf/frame     frame-id
                             :rf/platform  platform
                             :rf/parent-id machine-id)
        path          [:rf/machines machine-id]
        existing-snap (get-in db path)]
    {:db               db
     :machine-id       machine-id
     :frame-id         frame-id
     :machine          machine
     :path             path
     :snapshot         (if (nil? existing-snap)
                         (assoc @base-initial :rf/bootstrap-pending? true)
                         existing-snap)
     :needs-bootstrap? (or (nil? existing-snap)
                           (true? (:rf/bootstrap-pending? existing-snap)))
     :inner-event      (route-inner-event event)}))

(defn- maybe-boot
  "Step 2 of 4 (rf2-2zzyg). If `ctx` is bootstrap-pending, run
  `apply-initial-entry-cascade` once before processing the user event.
  Bootstrap fx flow OUT of the handler ahead of any fx the user event
  produces — entry happens-before user-event handling. Returns a Result
  whose `::snap` has `:rf/bootstrap-pending?` cleared on success.

  Per rf2-0z73 the bootstrap cascade fires the initial state's `:entry`
  actions; per the Result ADT (rf2-aa2rw) a `:fail` short-circuits the
  rest of the pipeline."
  [ctx]
  (if (:needs-bootstrap? ctx)
    (let [r (parallel/apply-initial-entry-cascade (:machine ctx) (:snapshot ctx))]
      (if (result/fail? r)
        r
        (result/with-ok [snap fx] r
          (result/ok (dissoc snap :rf/bootstrap-pending?) fx))))
    (result/ok (:snapshot ctx) [])))

(defn- run-step
  "Step 3 of 4 (rf2-2zzyg). Run the pure macrostep against the post-boot
  snapshot + the routed inner event. Returns the Result from
  `parallel/machine-transition` — caller inspects `result/fail?` /
  `result/ok?` and projects accordingly."
  [ctx post-boot-snap]
  (parallel/machine-transition (:machine ctx) post-boot-snap (:inner-event ctx)))

(defn- commit-or-finalize
  "Step 4 of 4 (rf2-2zzyg). Emit `:rf.machine/transition` (and optional
  `:rf.machine/snapshot-updated`) traces, build the new app-db, and
  route to `finalize-machine` if the post-transition snapshot is on a
  final leaf / all regions final.

  Per Spec 005 §Final states (rf2-gn80): the finality flag is recomputed
  at the lifecycle-handler boundary against the post-transition
  snapshot. For single / compound machines, look up the leaf node and
  check `:final?`. For parallel-region machines, the parent is `:final?`
  only when every region's leaf is `:final?`. The pure-transition
  surface stays free of runtime-only metadata."
  [ctx step-result boot-fx]
  (result/with-ok [next-snapshot fx] step-result
    (let [{:keys [machine machine-id frame-id db path snapshot inner-event]} ctx
          merged-fx (vec (concat boot-fx fx))
          finished? (or (and (not (parallel/parallel? machine))
                             (transition/final-on-leaf? machine (:state next-snapshot)))
                        (finalize/all-regions-final? machine (:state next-snapshot)))
          new-db    (assoc-in db path next-snapshot)]
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
         :fx merged-fx}))))

(defn create-machine-handler
  "Returns a function suitable for registration with `reg-event-fx`.

  Per Spec 005 §Registration — the machine IS the event handler. The
  machine spec MUST NOT carry `:id`; the machine's id is the surrounding
  registration's event-id, derived at handler-call time from the
  dispatched event vector's first element.

  Per rf2-f9tu the body is decomposed into:
    - `validation/validate-machine!` — every registration-time check.
    - `parallel/build-initial-snapshot` — initial-state cascade, `:data` /
      `:meta` seeding, `:rf/spawn-counter` seeding, tag union stamping
      (rf2-fgqs4 unified this with the spawn path).
    - the returned handler fn — frame stamping, intercept-invoke-all-
      event branch (in `lifecycle-fx.join`), bootstrap-pending detection
      + initial-entry cascade, machine-transition dispatch, action-failure
      projection, finalize delegation (in `lifecycle-fx.finalize`).

  Per rf2-2zzyg the returned handler fn is further decomposed into a
  four-step pipeline — `prepare-machine-ctx` → `maybe-boot` →
  `run-step` → `commit-or-finalize` — with the intercept-invoke-all-
  event short-circuit branching off after step 1."
  [machine]
  (validation/validate-machine! machine)
  ;; Per rf2-f9tu — `build-initial-snapshot` runs lazily INSIDE the
  ;; returned handler, not at registration time. The initial-state
  ;; computation reaches through `:initial` / `:states` / `:regions`;
  ;; running it at registration time would force every registered spec
  ;; (including the stub child machines the conformance corpus declares
  ;; without `:initial` for `:invoke` targets, and any spec whose
  ;; `:initial` derives from a fn-form computed at dispatch time) to
  ;; satisfy the snapshot shape at reg-machine call time. The original
  ;; (pre-split) implementation deferred this work; preserve that.
  ;;
  ;; Pass `:bootstrap-pending? false` — the singleton path stamps the
  ;; marker lazily inside `prepare-machine-ctx` (when `existing-snap` is
  ;; nil); only the spawn path needs it stamped here.
  (let [base-initial (delay (parallel/build-initial-snapshot
                              machine {:bootstrap-pending? false}))]
    (fn [{:keys [db frame] :as _cofx} event]
      ;; Per Spec 009 §:op-type vocabulary: `:rf.machine/event-received`
      ;; fires at the top of the handler so consumers see the inbound
      ;; event before any state derivation.
      (trace/emit! :rf.machine/event-received :rf.machine/event-received
                   {:machine-id (first event)
                    :event      event
                    :frame      (or frame :rf/default)})
      (let [ctx         (prepare-machine-ctx db frame event machine base-initial)
            intercepted (join/intercept-invoke-all-event
                          (:machine ctx) db (:path ctx) (:snapshot ctx)
                          (:machine-id ctx) (:inner-event ctx))]
        (if intercepted
          intercepted
          (let [boot-result (maybe-boot ctx)]
            (if (result/fail? boot-result)
              (trace-action-failure! (:machine-id ctx) [:rf.machine/bootstrap]
                                     (:frame-id ctx)
                                     (result/info boot-result)
                                     "Machine initial-entry action threw.")
              (result/with-ok [post-boot-snap boot-fx] boot-result
                (let [step-result (run-step ctx post-boot-snap)]
                  (if (result/fail? step-result)
                    (trace-action-failure! (:machine-id ctx) (:inner-event ctx)
                                           (:frame-id ctx)
                                           (result/info step-result)
                                           "Machine action threw.")
                    (commit-or-finalize ctx step-result boot-fx)))))))))))

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
  ;; Per rf2-s83iu: install a per-machine region-machine cache before
  ;; the machine value is threaded through the handler closure and
  ;; published to the registrar. Re-registration replaces the machine
  ;; map and its attached cache atom, so no separate invalidation step
  ;; is needed.
  (let [machine    (parallel/install-region-cache machine)
        handler-fn (create-machine-handler machine)]
    (events/reg-event-fx machine-id
                         {:rf/machine? true
                          :rf/machine  machine}
                         handler-fn)
    (trace/emit! :rf.machine.lifecycle/created :rf.machine.lifecycle/created
                 {:machine-id machine-id
                  :initial    (:initial machine)})
    machine-id))
