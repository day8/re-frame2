(ns re-frame.machines
  "State machines. Per Spec 005.

  Implements the v1 grammar:
    - Transition tables with :on, :entry, :exit, :guard, :action.
    - Flat states (single keyword) AND hierarchical states (vector path)
      with deepest-wins resolution and LCA exit/entry cascade.
    - :always microsteps with bounded depth and atomic rollback on
      depth-exceeded.
    - :after delayed transitions with per-machine :rf/after-epoch
      tracking; the synthetic :rf.machine.timer/after-elapsed event
      fires the transition only when the carried epoch matches.
    - Declarative :invoke that desugars into [:spawn args] on entry
      and [:destroy-machine actor-id] on exit; deterministic actor ids
      via a per-process counter.
    - The :raise reserved fx-id (machine-internal pre-commit dispatch).
    - Snapshot at [:rf/machines <id>] in app-db.
    - Pure machine-transition fn (JVM- and CLJS-runnable, deterministic).

  Conformance fixtures cover all of the above (machine-transition,
  hierarchical-{compound,cross-level,parent-fallthrough}-transition,
  always-{single-microstep,depth-exceeded}, after-{single-delay,
  stale-detection,hierarchy}, invoke-spawn-on-entry-destroy-on-exit)."
  (:require [re-frame.registrar :as registrar]
            [re-frame.events :as events]
            [re-frame.frame :as frame]
            [re-frame.fx :as fx]
            [re-frame.late-bind :as late-bind]
            [re-frame.subs :as subs]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

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

;; ---- guard/action contract --------------------------------------------------
;;
;; Per Spec 005 §Guards / §Actions, the canonical signature is:
;;
;;   (fn [data event] ...)              ; 2-arity — the 99% case
;;   (fn [data event ctx] ...)          ; 3-arity — opt-in introspection
;;
;; `data` is the machine's :data slot directly (a map). `event` is the inbound
;; event vector. `ctx` (3-arity escape hatch) is `{:state ... :meta ...}` —
;; the snapshot's discrete-state and any user `:meta`. Returning to the 2-
;; arity surface from the 3-arity body is a one-line `(let [data data] ...)`;
;; no migration cost when introspection is no longer needed.

(defn- declares-3-arity?
  "True iff f explicitly declares a 3-fixed-arg invocation. Distinguishes
  user opt-in 3-arity from variadic helpers like (constantly ...). On the
  JVM, walks the fn class's declared invoke methods (variadics are
  RestFns with no declared invoke(O,O,O) on the fn class itself); on
  CLJS, looks for the multi-arity dispatch slot or matches .-length."
  [f]
  #?(:clj  (boolean
             (some
               (fn [^java.lang.reflect.Method m]
                 (and (= "invoke" (.getName m))
                      (= 3 (.getParameterCount m))))
               (.getDeclaredMethods (class f))))
     :cljs (or (= 3 (.-length f))
               (some? (unchecked-get f "cljs$core$IFn$_invoke$arity$3")))))

(defn- call-guard
  "Invoke a resolved guard fn against a snapshot + event with the
  canonical contract. 2-arity sees [data event]; 3-arity opt-in sees
  [data event {:state :meta}]."
  [g snapshot event]
  (let [data (:data snapshot)]
    (if (declares-3-arity? g)
      (g data event {:state (:state snapshot) :meta (:meta snapshot)})
      (g data event))))

(defn- call-action
  "Invoke a resolved action fn against a snapshot + event with the
  canonical contract. 2-arity sees [data event]; 3-arity opt-in sees
  [data event {:state :meta}]."
  [f snapshot event]
  (let [data (:data snapshot)]
    (if (declares-3-arity? f)
      (f data event {:state (:state snapshot) :meta (:meta snapshot)})
      (f data event))))

;; ---- spawn counter --------------------------------------------------------
;;
;; Per Spec 005 §Declarative :invoke (sugar over spawn): on entry to an
;; :invoke-bearing state the runtime emits a :spawn fx and assigns the
;; spawned actor a deterministic id of the form `<machine-id>#<n>`. The
;; counter is per machine-id so independent invokes don't cross-contaminate.

(defonce ^:private spawn-counter
  ;; Keyed by [frame-id machine-id] so independent frames don't share
  ;; one actor-id sequence — preserves frame isolation and makes
  ;; deterministic replay / restore reliable. Pure machine-transition
  ;; calls (the conformance fixture corpus) pass a sentinel frame.
  (atom {}))   ;; [frame-id machine-id] → int

(defn- next-spawn-id
  [frame-id machine-id]
  (let [k [frame-id machine-id]
        n (-> (swap! spawn-counter update k (fnil inc 0))
              (get k))]
    (keyword (namespace machine-id)
             (str (name machine-id) "#" n))))

(defn reset-counters!
  "Reset the spawn-counter (and other test-mode counters) so id
  allocation is stable across fixture runs."
  []
  (reset! spawn-counter {}))

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

(defn- pick-after-transition
  "Per Spec 005 §Delayed :after transitions. The synthetic event
  [:rf.machine.timer/after-elapsed delay carried-epoch] arrives. Walk
  path leaf→root for an :after table at that delay. If the carried
  epoch matches the snapshot's current :rf/after-epoch, return the
  transition. Otherwise return :stale (so the caller can emit
  :rf.machine.timer/stale-after).

  When no :after table is found at any level along the current path,
  the synthetic timer event was carried from a state the machine has
  since exited. Per Spec 005 §Epoch-based stale detection — any
  non-matching epoch on a synthetic timer event is a stale carry by
  definition — return :stale so the caller emits
  :rf.machine.timer/stale-after. Matching epochs with no table are
  treated as a no-op (return nil): nothing to fire, and the epoch
  invariant says the timer wasn't really stale either."
  [machine path event snapshot]
  (let [[_ delay carried-epoch] event
        current-epoch (get-in snapshot [:data :rf/after-epoch])]
    (loop [i (dec (count path))]
      (if (neg? i)
        ;; No :after table found anywhere along the path — the timer was
        ;; scheduled by an :after-bearing state we've since exited. If the
        ;; epoch doesn't match, surface it as stale; if it does match, the
        ;; carry is benign and we suppress silently.
        (when (not= carried-epoch current-epoch)
          {:stale?          true
           :state           (last path)
           :delay           delay
           :scheduled-epoch carried-epoch
           :current-epoch   current-epoch})
        (let [prefix (vec (take (inc i) path))
              n      (node-at machine prefix)
              t      (get-in n [:after delay])]
          (cond
            (nil? t)
            (recur (dec i))

            (= carried-epoch current-epoch)
            {:transition (if (keyword? t) {:target t} t)
             :decl-path  prefix
             :delay      delay
             :epoch      carried-epoch}

            :else
            {:stale?         true
             :state          (last prefix)
             :delay          delay
             :scheduled-epoch carried-epoch
             :current-epoch   current-epoch}))))))

(defn- pick-transition
  "Walk path leaf→root looking for a transition that matches event-id and
  whose guard passes. Per Spec 005 §Transition resolution — deepest-wins
  with parent fallthrough.

  Special-cases the synthetic :rf.machine.timer/after-elapsed event by
  delegating to pick-after-transition.

  Returns {:transition t :decl-path prefix} or {:stale? true ...} or nil."
  [machine path event snapshot]
  (let [event-id (first event)]
    (cond
      (= :rf.machine.timer/after-elapsed event-id)
      (pick-after-transition machine path event snapshot)

      :else
      (loop [i (dec (count path))]
        (when (>= i 0)
          (let [prefix (vec (take (inc i) path))
                n      (node-at machine prefix)
                cands  (normalise-on-clause
                         (or (get-in n [:on event-id])
                             (get-in n [:on :*])))
                hit    (some (fn [t]
                               (let [g (resolve-guard machine (:guard t))]
                                 (when (call-guard g snapshot event) t)))
                             cands)]
            (if hit
              {:transition hit :decl-path prefix}
              (recur (dec i)))))))))

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

;; Sentinel: when an action throws, run-action returns this map instead of
;; an effects map. collect-actions / apply-transition-once / machine-transition
;; propagate it; the outer event handler converts it into a no-:db return so
;; the cascade halts without committing a snapshot. Per Spec 005 §Errors and
;; Cross-Spec-Interactions §11 — Machine action throws.
(defn- action-failure?
  [x]
  (and (map? x) (contains? x :rf.machine/action-failure)))

(defn- run-action [machine snap action-ref event]
  (if action-ref
    (let [f (resolve-action machine action-ref)]
      (try
        (let [result (call-action f snap event)]
          (or result {}))
        (catch #?(:clj Throwable :cljs :default) e
          {:rf.machine/action-failure
           {:action-ref action-ref
            :exception  e}})))
    {}))

(defn- collect-actions
  "Walk action-refs in order, calling each with snap+event and threading
  the resulting :data updates forward (so each action sees the previous
  one's data). Returns [final-snapshot fx-vec], or
  [::action-failed failure-info] if any action threw — per Spec 005
  §Errors, the cascade halts on the first throw and the snapshot does
  not commit."
  [machine snap event action-refs]
  (reduce
    (fn [[s fx] aref]
      (if aref
        (let [r (run-action machine s aref event)]
          (if (action-failure? r)
            (reduced [::action-failed (:rf.machine/action-failure r)])
            (let [new-data   (cond-> (:data s)
                               (contains? r :data) (merge (:data r)))
                  new-snap   (assoc s :data new-data)
                  new-fx     (vec (concat fx (or (:fx r) [])))]
              [new-snap new-fx])))
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

  Per Spec 005 §Delayed :after transitions, every external transition
  advances :data.:rf/after-epoch (so any in-flight timer captured before
  the change becomes stale). A target leaf that declares :after schedules
  a fresh timer at the new epoch via a :rf.machine.timer/scheduled trace.

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
        result       (collect-actions machine snapshot event all-refs)]
    ;; Per Spec 005 §Errors and Cross-Spec-Interactions §11 — Machine
    ;; action throws: if any action in the cascade threw, halt the
    ;; cascade by short-circuiting out of apply-transition-once. The
    ;; failure marker is propagated up to machine-transition which
    ;; emits :rf.error/machine-action-exception and returns no commit.
    (if (and (vector? result) (= ::action-failed (first result)))
      [::action-failed (assoc (second result)
                              :decl-path  decl-path
                              :transition transition
                              :state-path src-path)]
      (let [[snap-after fx] result
        ;; Per Spec 005 §Delayed :after transitions §Hierarchy interaction:
        ;; the epoch advances iff any state being EXITED or ENTERED in this
        ;; transition declares an :after table (that's the in-flight timer
        ;; either being scheduled, or being invalidated). Sibling-leaf
        ;; transitions that don't cross an :after-bearing state leave the
        ;; epoch alone. Internal transitions never advance.
        exited-nodes  (when-not internal?
                        (->> (nodes-along-path machine src-path)
                             (drop lca-len)
                             (map (fn [[_ n]] n))))
        entered-nodes (when-not internal?
                        (->> (nodes-along-path machine target-leaf)
                             (drop lca-len)
                             (map (fn [[_ n]] n))))
        epoch-bumps?  (and (not internal?)
                           (boolean
                             (some :after (concat exited-nodes entered-nodes))))
        new-state    (cond
                       internal?            (:state snapshot)
                       (vector? raw-target) (vec target-leaf)
                       (keyword? raw-target) (if (= 1 (count target-leaf))
                                               (first target-leaf)
                                               (vec target-leaf))
                       :else                (denormalise-state target-leaf (:state snapshot)))
        snap-final   (cond
                       internal?
                       (assoc snap-after :state new-state)

                       epoch-bumps?
                       (let [new-epoch (inc (or (get-in snap-after [:data :rf/after-epoch]) 0))]
                         (-> snap-after
                             (assoc :state new-state)
                             (assoc-in [:data :rf/after-epoch] new-epoch)))

                       :else
                       (assoc snap-after :state new-state))]
    ;; Schedule :after timers declared on any newly-entered ancestor whose
    ;; level didn't already host an active timer (i.e. nodes BELOW the LCA
    ;; on the entry side). This skips sibling-leaf transitions that didn't
    ;; cross the :after-bearing parent.
    ;;
    ;; Per Spec 005 §SSR mode and Cross-Spec-Interactions §4 (Machines × SSR),
    ;; :after is a no-op under :platform :server: the timer is not scheduled
    ;; and the synthetic timer-elapsed event is never queued. Emit
    ;; :rf.machine.timer/skipped-on-server in place of :scheduled so observers
    ;; see the gate fired.
    (when-not internal?
      (let [server? (= :server (:rf/platform machine))]
        (doseq [n entered-nodes]
          (when-let [after-map (:after n)]
            (let [epoch      (get-in snap-final [:data :rf/after-epoch])
                  ;; Find the state-id whose node IS n.
                  leaf-state (some (fn [[p node]] (when (= node n) (last p)))
                                   (nodes-along-path machine target-leaf))]
              (doseq [[delay _target] after-map]
                (if server?
                  (trace/emit! :machine :rf.machine.timer/skipped-on-server
                               {:state    leaf-state
                                :delay    delay
                                :epoch    epoch
                                :platform :server
                                :recovery :skipped})
                  (trace/emit! :machine :rf.machine.timer/scheduled
                               {:state leaf-state :delay delay :epoch epoch}))))))))
    ;; Per Spec 005 §Declarative :invoke (sugar over spawn): nodes being
    ;; EXITED with :invoke emit :destroy-machine (reading the recorded
    ;; actor id from :data.:pending — the conventional slot the user's
    ;; :on-spawn writes); nodes being ENTERED with :invoke emit :spawn,
    ;; allocate a deterministic actor id, and run :on-spawn to give the
    ;; user a chance to record the id in :data.
    (let [destroy-fx
          (when-not internal?
            (vec
              (mapcat
                (fn [n]
                  (when-let [_inv (:invoke n)]
                    (let [actor-id (get-in snapshot [:data :pending])]
                      (when actor-id
                        [[:destroy-machine actor-id]]))))
                exited-nodes)))
          [snap-after-spawns spawn-fx]
          (if internal?
            [snap-final []]
            (reduce
              (fn [[s acc-fx] n]
                (if-let [inv (:invoke n)]
                  (let [machine-id  (:machine-id inv)
                        ;; Frame-scoped allocation per Spec 002 frame isolation.
                        ;; Pure-call machine-transition (no frame context) uses
                        ;; a sentinel so the corpus's deterministic ids hold.
                        frame-id    (or (:rf/frame machine) :rf/transition-pure)
                        spawned-id  (next-spawn-id frame-id machine-id)
                        spawn-args  (-> inv (assoc :id-prefix machine-id))
                        on-spawn-fn (let [aref (:on-spawn inv)]
                                      (when aref
                                        (or (chase-ref (:on-spawn-actions machine) aref)
                                            (chase-ref (:actions machine) aref))))
                        ;; Per Spec 005 §Declarative :invoke (sugar over spawn):
                        ;; the :on-spawn callback signature is (fn [data id] new-data).
                        ;; Per rf2-een2 / rf2-smba: the callback operates on :data
                        ;; (not the snapshot wrapper), uniform with regular actions
                        ;; whose canonical contract is (fn [data event] effects).
                        ;; The runtime patches the returned data back into the snapshot.
                        s'          (if on-spawn-fn
                                      (let [data     (:data s)
                                            new-data (on-spawn-fn data spawned-id)]
                                        (if new-data
                                          (assoc s :data new-data)
                                          s))
                                      s)]
                    [s' (conj acc-fx [:spawn spawn-args])])
                  [s acc-fx]))
              [snap-final []]
              entered-nodes))
          all-fx (vec (concat fx (or destroy-fx []) spawn-fx))]
      [snap-after-spawns all-fx])))))

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
                             (when (call-guard g snapshot nil) t)))
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
          (let [step-result ((resolve `machine-transition) machine snap args)]
            (if (and (vector? step-result)
                     (= ::action-failed (first step-result)))
              ;; Per Spec 005 §Errors: an action throw inside a raised
              ;; transition halts the whole cascade. Propagate the
              ;; failure marker out of the drain so machine-transition's
              ;; outer level can short-circuit.
              step-result
              (let [[snap2 fx2] step-result]
                (recur (concat fx2 rest-pending)
                       accum
                       snap2
                       (inc depth)))))

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
        ;; Trace timer firing / staleness BEFORE running the transition,
        ;; so listeners see the firing event in the order it occurred.
        _ (when (and match (:stale? match))
            (trace/emit! :machine :rf.machine.timer/stale-after
                         {:state           (:state match)
                          :delay           (:delay match)
                          :scheduled-epoch (:scheduled-epoch match)
                          :current-epoch   (:current-epoch match)
                          :recovery        :replaced-with-default}))
        _ (when (and match (not (:stale? match)) (:delay match))
            (trace/emit! :machine :rf.machine.timer/fired
                         {:state  (last (:decl-path match))
                          :delay  (:delay match)
                          :epoch  (:epoch match)
                          :fired? true}))
        result-after-event
        (cond
          (and match (:stale? match))
          [snapshot []]   ;; suppress: snapshot unchanged

          match
          (apply-transition-once
            machine snapshot event
            (assoc (:transition match) :decl-path (:decl-path match)))

          :else
          [snapshot []])]
    ;; Per Spec 005 §Errors and Cross-Spec-Interactions §11 — Machine
    ;; action throws: short-circuit the macrostep on failure. The
    ;; sentinel `[::action-failed info]` propagates to create-machine-handler
    ;; which emits the trace and returns no-:db (cascade halts, snapshot
    ;; uncommitted, accumulated :fx dropped).
    (if (and (vector? result-after-event)
             (= ::action-failed (first result-after-event)))
      result-after-event
      (let [[snap-after-event fx-after-event] result-after-event
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
                (let [step-result (apply-transition-once machine snap nil
                                                          (:transition always-m))]
                  (if (and (vector? step-result)
                           (= ::action-failed (first step-result)))
                    ;; Per Cross-Spec §11: ":always microstep does not fire on
                    ;; the failed cascade." Propagate the failure so the
                    ;; handler emits the error and skips the commit.
                    step-result
                    (let [[snap2 fx2] step-result
                          [snap3 fx3] (drain-raises machine snap2 fx2 raise-limit)]
                      (recur snap3
                             (vec (concat fx fx3))
                             (inc depth)
                             (conj visited (:state snap3))))))))))))))

;; ---- create-machine-handler -----------------------------------------------

(defn- snapshot-path [machine-id]
  [:rf/machines machine-id])

(defn create-machine-handler
  "Returns a function suitable for registration with reg-event-fx.

  Per Spec 005 §Registration — the machine IS the event handler.
  The machine spec MUST NOT carry :id; the machine's id is the
  surrounding registration's event-id, derived at handler-call time
  from the dispatched event vector's first element. This keeps the
  spec map a pure description of behaviour and makes the same spec
  reusable across multiple registrations (e.g. (reg-event-fx :a m)
  and (reg-event-fx :b m) produce two independent machines)."
  [machine]
  ;; Validate guard/action references at construction time. machine-id
  ;; isn't known yet (it's the registration-site id), so error tags use
  ;; a placeholder; real misuse traces at handler-call time fill it in.
  (doseq [[s state-node] (:states machine)]
    (let [transitions (mapcat
                       (fn [[_ t]]
                         (if (vector? t) t [t]))
                       (:on state-node))]
      (doseq [t transitions]
        (when-let [g (:guard t)]
          (when (and (keyword? g)
                     (not (contains? (:guards machine) g)))
            (throw (ex-info ":rf.error/machine-unresolved-guard"
                            {:guard g :state s}))))
        (when-let [a (:action t)]
          (when (and (keyword? a)
                     (not (contains? (:actions machine) a)))
            (throw (ex-info ":rf.error/machine-unresolved-action"
                            {:action a :state s})))))))
  (fn [{:keys [db frame] :as _cofx} event]
    (let [;; Per Spec 005 §Registration: id comes from event[0] (the
          ;; surrounding reg-event-fx id), NOT from the spec map.
          machine-id (first event)
          ;; Per Spec 009 §:op-type vocabulary: :rf.machine/event-received
          ;; fires at the top of the handler so consumers see the inbound
          ;; event before any state derivation.
          _ (trace/emit! :rf.machine/event-received :rf.machine/event-received
                         {:machine-id machine-id
                          :event      event
                          :frame      (or frame :rf/default)})
          ;; Stamp the live frame onto the machine def so spawn-id
          ;; allocation (frame-scoped per Spec 002) gets the right key.
          ;; Also stamp :rf/platform — read from the frame's :config so
          ;; :after timer scheduling can gate on :server (per Spec 005
          ;; §SSR mode and Cross-Spec-Interactions §4).
          frame-id   (or frame :rf/default)
          platform   (or (get-in (frame/frame-meta frame-id) [:config :platform])
                         :client)
          machine    (assoc machine
                            :rf/frame    frame-id
                            :rf/platform platform)
          path       (snapshot-path machine-id)
          ;; Per Spec 005 §Initial-state cascading: when the snapshot is
          ;; first synthesised, descend the declared :initial through any
          ;; compound state's :initial chain to a leaf path. Without this,
          ;; a machine declared {:initial :foo :states {:foo {:initial :bar
          ;; :states {:bar {} :baz {}}}}} would land at :state :foo and
          ;; subsequent transitions resolve against the wrong path.
          initial-decl  (:initial machine)
          initial-path  (initial-cascade machine (state-path initial-decl))
          initial-state (denormalise-state initial-path initial-decl)
          initial    {:state initial-state :data (or (:data machine) {})}
          snapshot   (or (get-in db path) initial)
          ;; Sub-event routing per Spec 005 §Registration. The outer
          ;; event is [:machine-id <inner-event> & extra-args]. Extra
          ;; args are conj'd onto the inner event — the convention
          ;; http-style fx callbacks rely on:
          ;;   :on-success [:machine-id [:inner-id]]   →
          ;;   (conj on-success response) yields
          ;;   [:machine-id [:inner-id] response]      →
          ;;   inner-event = [:inner-id response]
          inner-event (cond
                        (and (vector? event)
                             (>= (count event) 2)
                             (vector? (second event)))
                        (let [inner (second event)
                              extra (drop 2 event)]
                          (if (seq extra)
                            (vec (concat inner extra))
                            inner))

                        :else event)
          step-result (machine-transition machine snapshot inner-event)]
      ;; Per Spec 005 §Errors and Cross-Spec-Interactions §11 — Machine
      ;; action throws: when the cascade halted on an action exception,
      ;; emit `:rf.error/machine-action-exception` (NOT the generic
      ;; `:rf.error/handler-exception`) with machine-scoped diagnostic
      ;; detail. The snapshot does NOT commit (no :db effect) and any
      ;; :fx accumulated earlier in the same cascade is dropped — the
      ;; transition is all-or-nothing.
      (if (and (vector? step-result)
               (= ::action-failed (first step-result)))
        (let [info       (second step-result)
              ex         (:exception info)
              ex-msg     #?(:clj  (when ex (.getMessage ^Throwable ex))
                            :cljs (when ex (.-message ex)))
              ex-data    (when ex (ex-data ex))
              action-ref (:action-ref info)]
          (trace/emit-error! :rf.error/machine-action-exception
                             {:machine-id        machine-id
                              :action-id         action-ref
                              :state-path        (:state-path info)
                              :transition        (:transition info)
                              :event             inner-event
                              :failing-id        machine-id
                              :handler-id        machine-id
                              :frame             (or frame :rf/default)
                              :exception         ex
                              :exception-message ex-msg
                              :exception-data    ex-data
                              :reason            "Machine action threw."
                              :recovery          :no-recovery})
          ;; No :db, no :fx — cascade halts atomically. The pre-action
          ;; snapshot at [:rf/machines machine-id] is preserved.
          {})
        (let [[next-snapshot fx] step-result
              new-db (assoc-in db path next-snapshot)]
          (trace/emit! :machine :rf.machine/transition
                       {:machine-id  machine-id
                        :event       inner-event
                        :before      snapshot
                        :after       next-snapshot})
          ;; Per Spec 009 §:op-type vocabulary: :rf.machine/snapshot-updated
          ;; fires whenever the handler writes the new snapshot to
          ;; [:rf/machines machine-id]. Distinct from :rf.machine/transition
          ;; in that it documents the app-db slot mutation specifically.
          (when (not= snapshot next-snapshot)
            (trace/emit! :rf.machine/snapshot-updated :rf.machine/snapshot-updated
                         {:machine-id machine-id
                          :path       path
                          :before     snapshot
                          :after      next-snapshot
                          :frame      (or frame :rf/default)}))
          {:db new-db
           :fx fx})))))

;; ---- reg-machine convenience ----------------------------------------------

(defn reg-machine
  "Convenience: register a machine as an event handler under
  machine-id. Equivalent to
  (reg-event-fx machine-id (create-machine-handler machine)).

  Per Spec 005 §Querying machines, the registration metadata is stamped
  with :rf/machine? true and :rf/machine (the spec map). (rf/machines)
  filters the :event registry by :rf/machine?; (rf/machine-meta id)
  reads the spec back out via the standard registrar query API."
  [machine-id machine]
  (let [handler-fn (create-machine-handler machine)]
    (events/reg-event-fx machine-id
                         {:rf/machine? true
                          :rf/machine  machine}
                         handler-fn)
    ;; Per Spec 009 §:op-type vocabulary: :rf.machine.lifecycle/created
    ;; fires when a machine instance is registered. Tools observe this
    ;; to track the machine population over hot reloads / boot.
    (trace/emit! :rf.machine.lifecycle/created :rf.machine.lifecycle/created
                 {:machine-id machine-id
                  :initial    (:initial machine)})
    machine-id))

;; ---- query API (Spec 005 §Querying machines) -----------------------------
;;
;; Two thin lookup fns over the existing event registry — derived views,
;; not a new registry kind. (rf/machines) filters event handlers whose
;; registration metadata carries :rf/machine? true; (rf/machine-meta id)
;; returns the registered machine's spec map (the same map passed to
;; reg-machine). Both are pure reads against the global registrar, so
;; they're queryable across all frames (machine snapshots are per-frame;
;; the registration is global).

(defn machines
  "Return a sequence of machine-ids — every event handler whose
  registration metadata carries :rf/machine? true. Per Spec 005
  §Querying machines."
  []
  (->> (registrar/handlers :event)
       (keep (fn [[id m]] (when (:rf/machine? m) id)))
       (vec)))

(defn machine-meta
  "Return the registered machine's spec map (:initial, :data, :guards,
  :actions, :states, :doc, source coords) for machine-id, or nil if
  no machine is registered under that id. Per Spec 005 §Querying
  machines."
  [machine-id]
  (let [m (registrar/lookup :event machine-id)]
    (when (:rf/machine? m)
      (:rf/machine m))))

;; ---- framework-shipped sub -----------------------------------------------
;;
;; Per Spec 005 §Subscribing to machines via sub-machine: the framework
;; ships :rf/machine as the canonical entry point. (rf/sub-machine id) in
;; core.cljc is sugar over (subscribe [:rf/machine id]).
;;
;; Lives in this namespace (rather than core.cljc) so the smoke-test
;; fixture's require :reload re-installs it after registrar/clear-all!.

(subs/reg-sub :rf/machine
  (fn [db [_ machine-id]]
    (get-in db [:rf/machines machine-id])))

;; ---- machine-internal effect handlers ------------------------------------
;;
;; Per Spec 005 §Declarative :invoke (sugar over spawn) the runtime
;; effects `:spawn` and `:destroy-machine` are emitted into the fx
;; vector by `apply-transition-once` whenever entry/exit cascades cross
;; an :invoke-bearing state. Per rf2-xbtj these handlers live in this
;; namespace (rather than `re-frame.fx`'s reserved case-block) so an app
;; that doesn't pull in `day8/re-frame-2-machines` carries neither the
;; trace strings (`:rf.machine/spawned`, `:rf.machine/destroyed`) nor
;; the handler symbols on its production-elision bundle.
;;
;; The fns are `defn`s (not `fn`s passed inline to `reg-fx`) so the
;; late-bind hook table can publish them under
;; `:machines/spawn-fx` / `:machines/destroy-machine-fx` for any
;; downstream namespace that wants to invoke them programmatically (the
;; conformance corpus does not — it consumes the fx vector directly —
;; but the hooks keep the producer surface uniform with the rest of the
;; machines namespace's late-bound entry points).

(defn spawn-fx
  "fx handler for `:spawn`. Emits `:rf.machine/spawned`. The runtime
  layer here just records the spawn intent; full actor lifecycle
  (auto-start, message routing, supervision) is the user's
  responsibility for now."
  [{:keys [frame]} args]
  (trace/emit! :machine :rf.machine/spawned
               {:frame      frame
                :machine-id (:machine-id args)
                :id-prefix  (:id-prefix args)
                :start      (:start args)
                :on-spawn   (:on-spawn args)}))

(defn destroy-machine-fx
  "fx handler for `:destroy-machine`. Emits `:rf.machine/destroyed`.
  Per Spec 005, dispatched on exit from an :invoke-bearing state."
  [{:keys [frame]} args]
  (trace/emit! :machine :rf.machine/destroyed
               {:frame    frame
                :actor-id args}))

(fx/reg-fx :spawn           spawn-fx)
(fx/reg-fx :destroy-machine destroy-machine-fx)

;; ---- late-bind hook registration ------------------------------------------
;;
;; Per rf2-xbtj the machines surface ships in
;; `day8/re-frame-2-machines`. `re-frame.core` and `re-frame.test-support`
;; MUST NOT `:require [re-frame.machines]` — the artefact is optional, and
;; a static require would force every consumer of the core artefact to
;; drag the namespace's `:rf/machine` sub registration (and the spawn
;; counter, the `:spawn` / `:destroy-machine` fx handlers, the
;; transition machinery) onto the classpath. The public-API re-exports
;; (`reg-machine`, `create-machine-handler`, `machine-transition`,
;; `machines`, `machine-meta`) and the test-support reset helper are
;; published through the late-bind table; consumers without the
;; machines artefact see the hooks unregistered and the surface
;; throws / returns safe defaults cleanly.

(late-bind/set-fn! :machines/reg-machine            reg-machine)
(late-bind/set-fn! :machines/create-machine-handler create-machine-handler)
(late-bind/set-fn! :machines/machine-transition     machine-transition)
(late-bind/set-fn! :machines/machines               machines)
(late-bind/set-fn! :machines/machine-meta           machine-meta)
(late-bind/set-fn! :machines/reset-counters!        reset-counters!)
(late-bind/set-fn! :machines/spawn-fx               spawn-fx)
(late-bind/set-fn! :machines/destroy-machine-fx     destroy-machine-fx)
