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

(defn- chase-ref
  "Follow a keyword reference chain through the machine's named-bindings
  map until it hits a fn (or fails). Tolerates one level of indirection
  like {:short-name :registered-id} where :registered-id resolves to a fn."
  [registry ref]
  (loop [r ref seen #{}]
    (cond
      (fn? r)              r
      (contains? seen r)   nil
      (keyword? r)         (if-let [nxt (get registry r)]
                             (recur nxt (conj seen r))
                             nil)
      :else                nil)))

(defn- resolve-guard
  "Look up a guard reference. If keyword, follow the chain in the machine's
  :guards map. If a fn, use directly."
  [machine guard]
  (cond
    (fn? guard)      guard
    (keyword? guard) (or (chase-ref (:guards machine) guard)
                         (throw (ex-info ":rf.error/machine-unresolved-guard"
                                         {:guard guard :machine-id (:id machine)})))
    (nil? guard)     (constantly true)
    :else            (throw (ex-info ":rf.error/machine-bad-guard-form"
                                     {:guard guard}))))

(defn- resolve-action
  [machine action]
  (cond
    (fn? action)      action
    (keyword? action) (or (chase-ref (:actions machine) action)
                          (throw (ex-info ":rf.error/machine-unresolved-action"
                                          {:action action :machine-id (:id machine)})))
    (nil? action)     (constantly nil)
    :else             (throw (ex-info ":rf.error/machine-bad-action-form"
                                      {:action action}))))

;; ---- state-path helpers (hierarchical) ------------------------------------
;;
;; Per Spec 005 §State paths and §Entry/exit cascading along the LCA, the
;; snapshot's :state is a vector path from root to leaf (e.g.
;; [:authenticated :cart :paying]). Flat machines used :state :foo for
;; compactness; we accept both and normalise internally.

(defn- state-path
  "Coerce a snapshot's :state — either a keyword or a vector path — into
  a normalised vector path."
  [state]
  (cond
    (vector? state) state
    (keyword? state) [state]
    :else (throw (ex-info ":rf.error/machine-bad-state-form" {:state state}))))

(defn- denormalise-state
  "Re-shape a vector path back to the same form as the input snapshot's
  :state. If `original` was a keyword and the path is length-1, return
  the keyword; otherwise return the vector."
  [path original]
  (cond
    (and (keyword? original) (= 1 (count path))) (first path)
    :else (vec path)))

(defn- node-at
  "Walk machine.:states down `path` returning the leaf state-node (or nil
  if path doesn't resolve)."
  [machine path]
  (loop [m  (:states machine)
         p  path]
    (cond
      (empty? p) nil
      :else
      (let [n (get m (first p))]
        (cond
          (nil? n) nil
          (= 1 (count p)) n
          :else (recur (:states n) (rest p)))))))

(defn- nodes-along-path
  "Return [[prefix-path node] ...] from root down to leaf. Skips nodes
  that don't resolve (defensive)."
  [machine path]
  (loop [m   (:states machine)
         p   path
         acc []
         pre []]
    (if (empty? p)
      acc
      (let [k     (first p)
            n     (get m k)
            pre'  (conj pre k)]
        (if (nil? n)
          acc
          (recur (:states n) (rest p) (conj acc [pre' n]) pre'))))))

(defn- initial-cascade
  "Given a target path landing on a possibly-compound node, descend
  through :initial chain until we reach a leaf. Returns the leaf path."
  [machine path]
  (loop [p path]
    (let [n (node-at machine p)]
      (if (and (map? n) (:initial n) (:states n))
        (recur (conj p (:initial n)))
        p))))

(defn- normalise-on-clause
  "The value at [:on event-id] may be:
    a keyword              -> treat as {:target <kw>}
    a vector of state ids  -> treat as {:target <vec>}  (absolute path)
    a vector of maps       -> multiple guarded transitions
    a single transition map
   Returns a vector of transition maps."
  [v]
  (cond
    (nil? v)                        []
    (keyword? v)                    [{:target v}]
    (and (vector? v)
         (every? map? v)
         (seq v))                   v
    (vector? v)                     [{:target v}]
    (map? v)                        [v]
    :else (throw (ex-info ":rf.error/machine-bad-on-clause" {:value v}))))

(defn- pick-transition
  "Walk path leaf→root looking for a transition that matches event-id and
  whose guard passes. Per Spec 005 §Transition resolution — deepest-wins
  with parent fallthrough.

  Returns {:transition t :decl-path prefix} or nil."
  [machine path event snapshot]
  (let [event-id (first event)]
    (loop [i (dec (count path))]
      (when (>= i 0)
        (let [prefix (vec (take (inc i) path))
              n      (node-at machine prefix)
              cands  (normalise-on-clause
                       (or (get-in n [:on event-id])
                           (get-in n [:on :*])))
              hit    (some (fn [t]
                             (let [g (resolve-guard machine (:guard t))]
                               (when (g snapshot event) t)))
                           cands)]
          (if hit
            {:transition hit :decl-path prefix}
            (recur (dec i))))))))

(defn- target-path
  "Compute the absolute target path for a transition. Per Spec 005:
   - keyword target → sibling at decl-path's level
       e.g. decl-path [:auth :cart], target :browsing
            → [:auth :cart :browsing]?  No — sibling means replace the
            last element. So [:auth :browsing]. But Spec 005 says when
            decl-path's node is a compound, keyword targets a child
            of THAT compound. Both readings appear in the corpus; we
            implement the practical one: keyword target replaces the
            NEXT level under decl-path.
   - vector target → absolute path from root.
   - nil target (internal transition) → source path unchanged."
  [decl-path source-path target]
  (cond
    (nil? target)        nil    ;; internal transition signal
    (vector? target)     target ;; absolute path
    (keyword? target)
    ;; The transition was declared at decl-path. The target keyword names
    ;; a sibling at the level where decl-path was found — i.e. replace
    ;; what decl-path's leaf level chose with the target. Concretely:
    ;; if decl-path = [:auth :cart], and source-path was
    ;; [:auth :cart :paying] (i.e. :cart's child :paying), then
    ;; "target :browsing" means [:auth :cart :browsing] (sibling of
    ;; :paying inside :cart).
    (let [parent (vec (drop-last decl-path))]
      (conj parent target))))

(defn- common-prefix-length [a b]
  (count (take-while true? (map = a b))))

(defn- run-action [machine snap action-ref event]
  (if action-ref
    (let [f (resolve-action machine action-ref)
          result (f snap event)]
      (or result {}))
    {}))

(defn- collect-actions
  "Walk action-refs in order, calling each with snap+event and threading
  the resulting :data updates forward (so each action sees the previous
  one's data). Returns [final-snapshot fx-vec]."
  [machine snap event action-refs]
  (reduce
    (fn [[s fx] aref]
      (if aref
        (let [r          (run-action machine s aref event)
              new-data   (cond-> (:data s)
                           (contains? r :data) (merge (:data r)))
              new-snap   (assoc s :data new-data)
              new-fx     (vec (concat fx (or (:fx r) [])))]
          [new-snap new-fx])
        [s fx]))
    [snap []]
    action-refs))

(defn- apply-transition-once
  "Apply one transition (exit cascade → action → entry cascade → state
  change). Returns [new-snapshot fx-vec].

  Per Spec 005 §Entry/exit cascading along the LCA:
   1. Compute LCA of source-path and target-leaf-path.
   2. Exit each ancestor's :exit from leaf up to (but not including) LCA.
   3. Run the transition's :action at the LCA level.
   4. Enter each ancestor's :entry from (LCA depth + 1) down to target leaf.

  Internal transitions (no :target) skip exit/entry; the action fires
  and the state path is unchanged.

  `transition` is the transition map with a synthetic :decl-path key
  recording where in the state-path tree the transition was declared."
  [machine snapshot event transition]
  (let [src-path     (state-path (:state snapshot))
        decl-path    (:decl-path transition (vec (take 1 src-path)))
        raw-target   (:target transition)
        target-leaf  (some->> (target-path decl-path src-path raw-target)
                              (initial-cascade machine))
        internal?    (nil? raw-target)
        lca-len      (if internal?
                       (count src-path)
                       (common-prefix-length src-path target-leaf))
        exit-refs    (when-not internal?
                       (->> (nodes-along-path machine src-path)
                            (drop lca-len)
                            (reverse)
                            (map (fn [[_ n]] (:exit n)))))
        entry-refs   (when-not internal?
                       (->> (nodes-along-path machine target-leaf)
                            (drop lca-len)
                            (map (fn [[_ n]] (:entry n)))))
        action-refs  [(:action transition)]
        all-refs     (concat exit-refs action-refs entry-refs)
        [snap-after fx] (collect-actions machine snapshot event all-refs)
        new-state    (if internal?
                       (:state snapshot)
                       (denormalise-state target-leaf (:state snapshot)))]
    [(assoc snap-after :state new-state) fx]))

(defn- pick-always-transition
  "Per Spec 005 §Eventless :always transitions: walk path leaf→root
  for an :always whose guard passes. Returns {:transition t :decl-path p}
  or nil."
  [machine path snapshot]
  (loop [i (dec (count path))]
    (when (>= i 0)
      (let [prefix (vec (take (inc i) path))
            n      (node-at machine prefix)
            always (:always n)
            always (cond
                     (nil? always)     []
                     (vector? always)  always
                     :else             [always])
            hit    (some (fn [t]
                           (let [g (resolve-guard machine (:guard t))]
                             (when (g snapshot nil) t)))
                         always)]
        (if hit
          {:transition (assoc hit :decl-path prefix) :decl-path prefix}
          (recur (dec i)))))))

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
   1. Pick the matching transition for the event using deepest-wins
      resolution along the state path.
   2. Run the exit cascade (deepest-first up to LCA) → transition's
      action (at LCA) → entry cascade (shallowest-first down to leaf).
   3. Drain the local :raise queue depth-first, recursing through
      machine-transition.
   4. :always microstep loop — walk path leaf→root for any matching
      :always; apply, drain raises, loop.
   5. Commit (return) the snapshot once :always reaches fixed point.

  Bounded by :raise-depth-limit and :always-depth-limit (both default 16).
  Hierarchical states are supported: :state may be a single keyword
  (flat machine) or a vector path (compound machine)."
  [machine snapshot event]
  (let [always-limit (get machine :always-depth-limit always-depth-limit-default)
        raise-limit  (get machine :raise-depth-limit  raise-depth-limit-default)
        ;; Step 1: pick the event-driven transition.
        path             (state-path (:state snapshot))
        match            (pick-transition machine path event snapshot)
        [snap-after-event fx-after-event]
        (if match
          (apply-transition-once
            machine snapshot event
            (assoc (:transition match) :decl-path (:decl-path match)))
          [snapshot []])
        ;; Step 3: drain :raise.
        [snap-after-raise fx-after-raise]
        (drain-raises machine snap-after-event fx-after-event raise-limit)]
    ;; Step 4: :always microstep loop. Track visited state-paths so that,
    ;; on depth-limit abort, we can report the path AND fully roll back to
    ;; the original input snapshot — the macrostep is atomic per Spec 005.
    (loop [snap    snap-after-raise
           fx      fx-after-raise
           depth   0
           visited [(:state snap-after-raise)]]
      (cond
        (>= depth always-limit)
        (do (trace/emit-error! :rf.error/machine-always-depth-exceeded
                               {:machine-id (:id machine)
                                :depth      depth
                                :path       visited
                                :recovery   :no-recovery})
            [snapshot []])

        :else
        (let [snap-path (state-path (:state snap))
              always-m  (pick-always-transition machine snap-path snap)]
          (if (nil? always-m)
            [snap fx]
            (let [[snap2 fx2] (apply-transition-once machine snap nil
                                                     (:transition always-m))
                  [snap3 fx3] (drain-raises machine snap2 fx2 raise-limit)]
              (recur snap3
                     (vec (concat fx fx3))
                     (inc depth)
                     (conj visited (:state snap3))))))))))

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
