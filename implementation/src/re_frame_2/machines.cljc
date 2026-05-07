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

(defn machine-transition
  "Pure function. Given a machine definition, the current snapshot, and
  an event, return [new-snapshot effects].

  Snapshot shape: {:state <keyword> :data <map>} per Spec 005 §Snapshot shape.
  Effects shape: a vector of [fx-id args] pairs, with :raise routed locally
  (we drain any :raise inside this fn) and the rest forwarded to do-fx."
  [machine snapshot event]
  (let [{:keys [state data]} snapshot
        states-map           (:states machine)
        state-node           (get states-map state)
        transition           (pick-transition machine state-node event snapshot)]
    (if (nil? transition)
      ;; No matching transition: snapshot unchanged.
      [snapshot []]
      (let [target-state (:target transition state)
            target-node  (get states-map target-state)
            ;; Run :exit, :action, :entry in order.
            run-action (fn [snap action-ref event]
                         (if action-ref
                           (let [f (resolve-action machine action-ref)
                                 result (f snap event)]
                             (or result {}))
                           {}))
            exit-result   (run-action snapshot (:exit state-node)         event)
            action-result (run-action snapshot (:action transition)       event)
            entry-result  (run-action snapshot (:entry target-node)       event)
            ;; Merge :data updates (last write wins on collisions).
            data-merged (-> data
                            (merge (or (:data exit-result) {}))
                            (merge (or (:data action-result) {}))
                            (merge (or (:data entry-result) {})))
            ;; Concatenate :fx in slot order.
            fx-vec (vec (concat (or (:fx exit-result) [])
                                (or (:fx action-result) [])
                                (or (:fx entry-result) [])))
            new-snapshot {:state target-state :data data-merged}]
        ;; Drain :raise pre-commit: any [:raise <ev>] in fx is processed
        ;; inline against the in-flight snapshot. Other fx-ids passed through.
        (loop [pending fx-vec
               accum-fx []
               snap     new-snapshot]
          (if (empty? pending)
            [snap accum-fx]
            (let [[fx-id args] (first pending)
                  rest-pending (rest pending)]
              (case fx-id
                :raise
                (let [[snap-after raised-fx] (machine-transition machine snap args)]
                  (recur (concat raised-fx rest-pending)
                         accum-fx
                         snap-after))

                ;; default: forward to do-fx
                (recur rest-pending
                       (conj accum-fx [fx-id args])
                       snap)))))))))

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
