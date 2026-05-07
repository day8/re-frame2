(ns re-frame-2.machines
  "State machines. Per Spec 005.

  This is a FIRST-PASS implementation covering the v1 core grammar:
    - Transition tables with :on, :entry, :exit, :guard, :action.
    - Flat states. Hierarchical states are TODO (filed as a bead).
    - The :raise reserved fx-id (machine-internal pre-commit dispatch).
    - Snapshot at [:rf/machines <id>] in app-db.
    - Pure machine-transition fn (JVM-runnable, deterministic).

  TODO (filed as beads):
    - Hierarchical compound states with LCA exit/entry cascade.
    - :always microsteps with depth-limit.
    - :after delayed transitions with epoch-based stale detection.
    - :invoke declarative actor spawn / destroy.
    - :spawn dynamic actor lifecycle.
    - sub-machine for reading snapshots reactively."
  (:require [re-frame-2.registrar :as registrar]
            [re-frame-2.events :as events]
            [re-frame-2.trace :as trace]))

;; ---- pure machine-transition ----------------------------------------------

(defn- resolve-guard
  "Look up a guard reference. If keyword, it must appear in the machine's
  :guards map. If a fn, use directly."
  [machine guard]
  (cond
    (fn? guard)      guard
    (keyword? guard) (or (get-in machine [:guards guard])
                         (throw (ex-info ":rf.error/machine-unresolved-guard"
                                         {:guard guard :machine-id (:id machine)})))
    (nil? guard)     (constantly true)
    :else            (throw (ex-info ":rf.error/machine-bad-guard-form"
                                     {:guard guard}))))

(defn- resolve-action
  [machine action]
  (cond
    (fn? action)      action
    (keyword? action) (or (get-in machine [:actions action])
                          (throw (ex-info ":rf.error/machine-unresolved-action"
                                          {:action action :machine-id (:id machine)})))
    (nil? action)     (constantly nil)
    :else             (throw (ex-info ":rf.error/machine-bad-action-form"
                                      {:action action}))))

(defn- pick-transition
  "Given a state's :on map and an event, find the first matching transition
  whose guard passes. Returns the transition spec or nil."
  [machine state-node event snapshot]
  (let [event-id   (first event)
        candidates (or (get-in state-node [:on event-id])
                       (get-in state-node [:on :*]))
        candidates (cond
                     (nil? candidates) []
                     (vector? candidates) candidates  ;; multiple guarded variants
                     :else [candidates])]
    (reduce
      (fn [_ t]
        (let [guard-fn (resolve-guard machine (:guard t))]
          (if (guard-fn snapshot event)
            (reduced t)
            nil)))
      nil
      candidates)))

(defn- run-action [machine snap action-ref event]
  (if action-ref
    (let [f (resolve-action machine action-ref)
          result (f snap event)]
      (or result {}))
    {}))

(defn- apply-transition-once
  "Apply one transition (action group + state change). Returns
  [new-snapshot fx-vec]. Used by both event-driven transitions and
  :always microsteps."
  [machine snapshot event transition states-map state-node]
  (let [target-state  (:target transition (:state snapshot))
        target-node   (get states-map target-state)
        exit-result   (run-action machine snapshot (:exit state-node)   event)
        action-result (run-action machine snapshot (:action transition) event)
        entry-result  (run-action machine snapshot (:entry target-node) event)
        data-merged   (-> (:data snapshot)
                          (merge (or (:data exit-result) {}))
                          (merge (or (:data action-result) {}))
                          (merge (or (:data entry-result) {})))
        fx-vec        (vec (concat (or (:fx exit-result) [])
                                   (or (:fx action-result) [])
                                   (or (:fx entry-result) [])))
        new-snapshot  {:state target-state :data data-merged}]
    [new-snapshot fx-vec]))

(defn- pick-always-transition
  "Per Spec 005 §Eventless :always transitions: a state may have an :always
  vector of {:guard :target :action} maps. Pick the first whose guard
  passes against the current snapshot."
  [machine state-node snapshot]
  (when-let [always-vec (:always state-node)]
    (reduce
      (fn [_ t]
        (let [guard-fn (resolve-guard machine (:guard t))]
          (if (guard-fn snapshot nil)
            (reduced t)
            nil)))
      nil
      (if (vector? always-vec) always-vec [always-vec]))))

(def ^:private always-depth-limit-default 16)
(def ^:private raise-depth-limit-default  16)

(defn- drain-raises
  "Drain the :raise queue inside fx-vec. Each :raise becomes an inline
  recursive machine-transition call; non-:raise fx pass through to the
  accumulator. Returns [new-snapshot accum-fx]."
  [machine snapshot fx-vec depth-limit]
  (loop [pending fx-vec
         accum   []
         snap    snapshot
         depth   0]
    (cond
      (> depth depth-limit)
      (do (trace/emit-error! :rf.error/machine-raise-depth-exceeded
                             {:machine-id (:id machine) :depth depth
                              :recovery :no-recovery})
          [snap accum])

      (empty? pending)
      [snap accum]

      :else
      (let [[fx-id args] (first pending)
            rest-pending (rest pending)]
        (case fx-id
          :raise
          ;; Recursive call into the FULL machine-transition (including
          ;; its own raise-drain + always-microstep loop). The result's
          ;; fx vector is appended to the pending list (NOT prepended)
          ;; per Spec 005 §Drain semantics §Level 3 step 3 — the raise
          ;; queue is drained depth-first, but new fx land at the end.
          (let [[snap2 fx2] ((resolve `machine-transition) machine snap args)]
            (recur (concat fx2 rest-pending)
                   accum
                   snap2
                   (inc depth)))

          ;; default: forward to do-fx
          (recur rest-pending
                 (conj accum [fx-id args])
                 snap
                 depth))))))

(defn machine-transition
  "Pure function. Given a machine definition, current snapshot, and event,
  return [new-snapshot effects].

  Per Spec 005 §Drain semantics §Level 3, this is the macrostep:
   1. Pick the matching transition for the event (guards LtR, first wins).
   2. Run :exit / :action / :entry slots in order; collect data + fx.
   3. Drain the local :raise queue depth-first, recursing through
      machine-transition.
   4. Microstep loop — check :always on the current state; if a guard
      matches, apply the always-transition; loop back to step 3.
   5. Commit (return) the snapshot once :always reaches fixed point.

  Bounded by :raise-depth-limit and :always-depth-limit (both default 16)."
  [machine snapshot event]
  (let [states-map (:states machine)
        always-limit (get machine :always-depth-limit always-depth-limit-default)
        raise-limit  (get machine :raise-depth-limit  raise-depth-limit-default)]
    ;; Step 1: pick event-driven transition (or no-op).
    (let [event-transition (pick-transition
                             machine
                             (get states-map (:state snapshot))
                             event
                             snapshot)
          [snap-after-event fx-after-event]
          (if event-transition
            (apply-transition-once machine snapshot event event-transition
                                   states-map (get states-map (:state snapshot)))
            [snapshot []])
          ;; Step 3: drain :raise.
          [snap-after-raise fx-after-raise]
          (drain-raises machine snap-after-event fx-after-event raise-limit)]
      ;; Step 4: :always microstep loop.
      (loop [snap snap-after-raise
             fx   fx-after-raise
             depth 0]
        (cond
          (> depth always-limit)
          (do (trace/emit-error! :rf.error/machine-always-depth-exceeded
                                 {:machine-id (:id machine) :depth depth
                                  :recovery :no-recovery})
              [snap fx])

          :else
          (let [state-node (get states-map (:state snap))
                always-t   (pick-always-transition machine state-node snap)]
            (if (nil? always-t)
              ;; Fixed point reached.
              [snap fx]
              ;; Apply the always-transition, then drain raises, then loop.
              (let [[snap2 fx2] (apply-transition-once
                                  machine snap nil always-t
                                  states-map state-node)
                    [snap3 fx3] (drain-raises machine snap2 fx2 raise-limit)]
                (recur snap3 (vec (concat fx fx3)) (inc depth))))))))))

;; ---- create-machine-handler -----------------------------------------------

(defn- snapshot-path [machine-id]
  [:rf/machines machine-id])

(defn create-machine-handler
  "Returns a function suitable for registration with reg-event-fx.

  The handler reads the current snapshot at [:rf/machines <id>] in app-db,
  runs machine-transition, writes the new snapshot back, and returns
  the resulting effects map for the standard fx pipeline.

  Per Spec 005 §Registration — the machine IS the event handler."
  [machine]
  (let [machine-id (:id machine)
        ;; Validate guard/action references at registration time.
        _ (doseq [[s state-node] (:states machine)]
            (let [transitions (mapcat
                               (fn [[_ t]]
                                 (if (vector? t) t [t]))
                               (:on state-node))]
              (doseq [t transitions]
                (when-let [g (:guard t)]
                  (when (and (keyword? g)
                             (not (contains? (:guards machine) g)))
                    (throw (ex-info ":rf.error/machine-unresolved-guard"
                                    {:guard g :machine-id machine-id :state s}))))
                (when-let [a (:action t)]
                  (when (and (keyword? a)
                             (not (contains? (:actions machine) a)))
                    (throw (ex-info ":rf.error/machine-unresolved-action"
                                    {:action a :machine-id machine-id :state s})))))))]
    (fn [{:keys [db]} event]
      (let [path     (snapshot-path machine-id)
            initial  {:state (:initial machine) :data (or (:data machine) {})}
            snapshot (or (get-in db path) initial)
            inner-event (if (and (vector? event) (= 2 (count event)))
                          (second event)  ;; the [:machine-id [:inner-ev ...]] form
                          event)
            [next-snapshot fx] (machine-transition machine snapshot inner-event)
            new-db (assoc-in db path next-snapshot)]
        (trace/emit! :machine :rf.machine/transition
                     {:machine-id  machine-id
                      :event       inner-event
                      :before      snapshot
                      :after       next-snapshot})
        {:db new-db
         :fx fx}))))

;; ---- reg-machine convenience ----------------------------------------------

(defn reg-machine
  "Convenience: register a machine as an event handler. Equivalent to
  (reg-event-fx machine-id (create-machine-handler machine))."
  [machine]
  (let [machine-id (:id machine)
        handler-fn (create-machine-handler machine)]
    (events/reg-event-fx machine-id handler-fn)
    machine-id))
