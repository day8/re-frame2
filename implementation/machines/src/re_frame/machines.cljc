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
      fires the transition only when the carried epoch matches. Per
      rf2-3y3y, `:after` delays admit three forms — pos-int? literal,
      subscription vector ([:sub-id & args]; re-resolves on sub change),
      and (fn [snapshot] ms) computed once at state entry. State-level
      :after subsumes the pre-release :timeout-ms slot on :invoke /
      :invoke-all, which is dropped (registration-time error category
      :rf.error/invoke-timeout-ms-removed).
    - Declarative :invoke that desugars into [:rf.machine/spawn args]
      on entry and [:rf.machine/destroy actor-id] on exit; deterministic
      actor ids via a per-process counter.
    - Declarative :invoke-all (rf2-6vmw) — spawn-and-join sugar over N
      parallel :invoke's plus a join condition (:all / :any / {:n N} /
      {:fn pred}); the runtime owns join state at
      [:rf/spawned <parent> <invoke-id> :join] and dispatches one of
      :on-all-complete / :on-some-complete / :on-any-failed when the
      condition resolves; cancel-on-decision = true (default) tears
      down surviving siblings via the standard exit-cascade.
    - The :raise reserved fx-id (machine-internal pre-commit dispatch).
    - Snapshot at [:rf/machines <id>] in app-db.
    - Pure machine-transition fn (JVM- and CLJS-runnable, deterministic).

  Conformance fixtures cover all of the above (machine-transition,
  hierarchical-{compound,cross-level,parent-fallthrough}-transition,
  always-{single-microstep,depth-exceeded}, after-{single-delay,
  stale-detection,hierarchy}, invoke-spawn-on-entry-destroy-on-exit,
  invoke-all-{join-all-completes,join-any-fails-cancels,n-of-cancels-extras})."
  (:require [re-frame.registrar :as registrar]
            [re-frame.events :as events]
            [re-frame.frame :as frame]
            [re-frame.fx :as fx]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.subs :as subs]
            [re-frame.substrate.adapter :as adapter]
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
  user opt-in 3-arity from variadic helpers like (constantly ...) or
  (fn [data event & rest] ...). On the JVM, walks the fn class's
  declared invoke methods (variadics are RestFns with no declared
  invoke(O,O,O) on the fn class itself); on CLJS, inspects the compiled
  fn surface — `cljs$lang$maxFixedArity` is set on every variadic and
  records its max-fixed-arg count, so a variadic with max-fixed < 3 is a
  2-plus-rest helper and is NOT a 3-arity declaration. A non-variadic fn
  whose `.-length` is 3, or a multi-arity fn carrying an explicit
  `cljs$core$IFn$_invoke$arity$3` dispatch slot, IS a 3-arity declaration."
  [f]
  #?(:clj  (boolean
             (some
               (fn [^java.lang.reflect.Method m]
                 (and (= "invoke" (.getName m))
                      (= 3 (.getParameterCount m))))
               (.getDeclaredMethods (class f))))
     :cljs (let [variadic? (some? (.-cljs$lang$maxFixedArity f))]
             (cond
               ;; Variadic of any flavour — (constantly ...), 2-plus-rest,
               ;; 3-plus-rest. RestFns on the JVM don't expose a declared
               ;; `invoke(O,O,O)` on the user's class, so the JVM check
               ;; classifies all variadics as non-3-arity. To converge,
               ;; CLJS treats every variadic the same way: routes through
               ;; the 2-arity path per the docstring's "distinguishes
               ;; variadic helpers" promise. The previously-buggy CLJS
               ;; check matched 2-plus-rest as 3-arity via the auto-
               ;; generated `cljs$core$IFn$_invoke$arity$3` rest-dispatch
               ;; slot — that's what this branch fixes (rf2-l04j).
               variadic?
               false
               ;; Plain 3-fixed-arity (e.g. `(fn [a b c] ...)`).
               (= 3 (.-length f))
               true
               ;; Multi-arity fn with an explicit 3-arity dispatch case
               ;; — `(fn ([a] ...) ([a b c] ...))`. The user IS opting
               ;; in to a 3-arity surface; route through it.
               (some? (unchecked-get f "cljs$core$IFn$_invoke$arity$3"))
               true
               :else
               false))))

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
;; :invoke-bearing state the runtime emits a :rf.machine/spawn fx and
;; assigns the spawned actor a deterministic id of the form
;; `<machine-id>#<n>`. The counter is per machine-id so independent
;; invokes don't cross-contaminate.

(defonce ^:private spawn-counter
  ;; Keyed by [frame-id machine-id] so independent frames don't share
  ;; one actor-id sequence — preserves frame isolation and makes
  ;; deterministic replay / restore reliable. Pure machine-transition
  ;; calls (the conformance fixture corpus) pass a sentinel frame.
  (atom {}))   ;; [frame-id machine-id] → int

(defonce ^:private after-timers
  ;; Per Spec 005 §Delayed :after transitions and rf2-3y3y. Runtime-owned
  ;; timer-handle table for in-flight :after timers. Keyed by
  ;; [frame-id parent-id invoke-id delay-key] so multiple delays per
  ;; :after map have their own slot. Value:
  ;;   {:handle <opaque host-clock handle>
  ;;    :reaction <subscription reaction or nil>
  ;;    :sub-watcher-key <key passed to add-watch>
  ;;    :resolved-ms <int>
  ;;    :epoch <int>
  ;;    :state <state-keyword>
  ;;    :delay-source <:literal | :sub | :fn>}
  ;; Cancellation (state exit + sub re-resolution) clears the entry and
  ;; releases the handle / detaches the watcher.
  (atom {}))

(defn- next-spawn-id
  [frame-id machine-id]
  (let [k [frame-id machine-id]
        n (-> (swap! spawn-counter update k (fnil inc 0))
              (get k))]
    (keyword (namespace machine-id)
             (str (name machine-id) "#" n))))

(defn reset-counters!
  "Reset the spawn-counter and the :after-timer table so id allocation
  and timer-handle bookkeeping are stable across fixture runs. Cancels
  every in-flight :after timer the runtime is currently tracking."
  []
  (doseq [[_ entry] @after-timers]
    (when-let [h (:handle entry)]
      (try (interop/clear-timeout! h)
           (catch #?(:clj Throwable :cljs :default) _ nil)))
    (when (and (:reaction entry) (:sub-watcher-key entry))
      (try (remove-watch (:reaction entry) (:sub-watcher-key entry))
           (catch #?(:clj Throwable :cljs :default) _ nil))))
  (reset! after-timers {})
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
  [:rf.machine.timer/after-elapsed delay-key carried-epoch] arrives.
  Walk path leaf→root for an :after table containing delay-key. If the
  carried epoch matches the snapshot's current :rf/after-epoch, evaluate
  the transition's :guard (if any).

  Returns one of:
    nil — no matching :after entry; benign (timer carried from a state
          we've exited and re-entered without that delay-key).
    {:stale? true ...} — epoch mismatch; caller emits
          :rf.machine.timer/stale-after.
    {:transition t :decl-path p :delay :epoch} — guard pass; caller
          fires the transition through the standard cascade and emits
          :rf.machine.timer/fired with :fired? true.
    {:guard-suppressed? true :state :delay :epoch} — guard returned
          false; caller emits :rf.machine.timer/fired with :fired? false
          and other in-flight :after timers continue per Spec 005
          §Multi-stage interaction with :guard.

  When no :after table is found at any level along the current path,
  the synthetic timer event was carried from a state the machine has
  since exited. Matching epochs with no table are treated as a no-op
  (return nil); non-matching is surfaced as stale."
  [machine path event snapshot]
  (let [[_ delay-key carried-epoch] event
        current-epoch (get-in snapshot [:data :rf/after-epoch])]
    (loop [i (dec (count path))]
      (if (neg? i)
        (when (not= carried-epoch current-epoch)
          {:stale?          true
           :state           (last path)
           :delay           delay-key
           :scheduled-epoch carried-epoch
           :current-epoch   current-epoch})
        (let [prefix (vec (take (inc i) path))
              n      (node-at machine prefix)
              t      (get-in n [:after delay-key])]
          (cond
            (nil? t)
            (recur (dec i))

            (not= carried-epoch current-epoch)
            {:stale?         true
             :state          (last prefix)
             :delay          delay-key
             :scheduled-epoch carried-epoch
             :current-epoch   current-epoch}

            :else
            (let [tspec (if (keyword? t) {:target t} t)
                  guard-ref (:guard tspec)
                  pass?
                  (if guard-ref
                    (let [g (resolve-guard machine guard-ref)]
                      (boolean (call-guard g snapshot event)))
                    true)]
              (if pass?
                {:transition tspec
                 :decl-path  prefix
                 :delay      delay-key
                 :epoch      carried-epoch}
                ;; Guard returned false. Per Spec 005 §Multi-stage
                ;; interaction with :guard: the timer is "fired and
                ;; discarded" — no transition, no epoch advance; sibling
                ;; :after timers continue.
                {:guard-suppressed? true
                 :state             (last prefix)
                 :delay             delay-key
                 :epoch             carried-epoch}))))))))

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
        ;;
        ;; Per rf2-t07u (Option A revised) — drop the `:data :pending` magic
        ;; for :invoke spawn-id tracking. The runtime carries `[prefix-path
        ;; node]` pairs through here so spawn / destroy fx emissions can
        ;; identify each :invoke-bearing state by its absolute prefix-path
        ;; (the per-state "invoke-id") and write/read the runtime-owned
        ;; [:rf/spawned <parent-id> <prefix-path>] slot. The destructured
        ;; -nodes vectors below preserve the legacy bare-node shape for the
        ;; :after epoch / scheduling bookkeeping that doesn't need paths.
        exited-pairs  (when-not internal?
                        (->> (nodes-along-path machine src-path)
                             (drop lca-len)
                             (vec)))
        entered-pairs (when-not internal?
                        (->> (nodes-along-path machine target-leaf)
                             (drop lca-len)
                             (vec)))
        exited-nodes  (mapv second exited-pairs)
        entered-nodes (mapv second entered-pairs)
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
                       (assoc snap-after :state new-state))
        ;; Per Spec 005 §SSR mode and Cross-Spec-Interactions §4 (Machines × SSR),
        ;; :after is a no-op under :platform :server: the timer is not
        ;; scheduled and the synthetic timer-elapsed event is never queued.
        ;; Per rf2-3y3y: emit the canonical :rf.machine.timer/scheduled (or
        ;; /skipped-on-server) trace synchronously here AND emit
        ;; :rf.machine/after-schedule fx; the fx handler resolves the delay
        ;; (literal / sub-vec / fn), calls interop/set-timeout! to fire a
        ;; synthetic [:rf.machine.timer/after-elapsed delay-key epoch] event
        ;; back into the parent on expiry, and (for sub-vec delays) installs
        ;; a watcher that cancels + reschedules on sub-change. The trace
        ;; tags carry :delay-source <:literal | :sub | :fn> so observers can
        ;; discriminate the three delay forms; the fx handler emits
        ;; :rf.machine.timer/cancelled-on-resolution + a fresh /scheduled
        ;; trace pair on subscription-driven re-resolution.
        server?
        (= :server (:rf/platform machine))
        after-fx
        (when-not internal?
          (let [parent-id (or (:rf/parent-id machine) :rf/transition-pure)
                epoch     (get-in snap-final [:data :rf/after-epoch])]
            (vec
              (mapcat
                (fn [[prefix n]]
                  (when-let [after-map (:after n)]
                    (let [leaf-state (last prefix)]
                      (mapv
                        (fn [[delay-key _target]]
                          (let [delay-source (cond
                                               (number? delay-key) :literal
                                               (vector? delay-key) :sub
                                               (fn? delay-key)     :fn
                                               :else               :literal)
                                ;; Pure-context delay value: literal keys
                                ;; ARE the resolved ms; sub-vec / fn are
                                ;; resolved at the fx layer (the fx handler
                                ;; emits a fresh /scheduled with the actual
                                ;; resolved-ms once it has frame access).
                                ms-tag       (if (number? delay-key)
                                               delay-key
                                               delay-key)]
                            (if server?
                              (trace/emit! :machine :rf.machine.timer/skipped-on-server
                                           (cond-> {:machine-id   parent-id
                                                    :state        leaf-state
                                                    :delay        ms-tag
                                                    :delay-source delay-source
                                                    :epoch        epoch
                                                    :platform     :server
                                                    :recovery     :skipped}
                                             (= :sub delay-source)
                                             (assoc :sub-id (first delay-key))))
                              (trace/emit! :machine :rf.machine.timer/scheduled
                                           (cond-> {:machine-id   parent-id
                                                    :state        leaf-state
                                                    :delay        ms-tag
                                                    :delay-source delay-source
                                                    :epoch        epoch}
                                             (= :sub delay-source)
                                             (assoc :sub-id (first delay-key))))))
                          [:rf.machine/after-schedule
                           {:rf/parent-id parent-id
                            :rf/invoke-id (vec prefix)
                            :state        leaf-state
                            :delay-key    delay-key
                            :epoch        epoch
                            :server?      server?}])
                        after-map))))
                entered-pairs))))]
    ;; Per Spec 005 §Declarative :invoke (sugar over spawn) and rf2-t07u
    ;; (Option A revised): nodes being EXITED with :invoke emit
    ;; :rf.machine/destroy carrying `{:rf/parent-id ... :rf/invoke-id ...}`
    ;; so the destroy-machine fx handler resolves the live actor id from
    ;; the runtime-owned [:rf/spawned <parent-id> <invoke-id>] slot in
    ;; app-db (no longer reads `:data.:pending`); nodes being ENTERED with
    ;; :invoke emit :rf.machine/spawn carrying the same
    ;; `{:rf/parent-id :rf/invoke-id}` keys and run the user's :on-spawn
    ;; callback (now purely advisory — the runtime tracks the spawn-id
    ;; itself).
    ;;
    ;; The "invoke-id" is the absolute prefix-path of the :invoke-bearing
    ;; state node — that disambiguates two states named e.g. `:loading` in
    ;; different parents. The "parent-id" is the surrounding registration's
    ;; event-id (machine-id), stamped onto the machine map by the handler
    ;; boundary as `:rf/parent-id`. Pure machine-transition calls (which
    ;; don't have a parent registration) carry a sentinel.
    (let [parent-id   (or (:rf/parent-id machine) :rf/transition-pure)
          ;; Per rf2-3y3y: when exiting an :after-bearing state node, emit
          ;; :rf.machine/after-cancel fx so the runtime tears down any
          ;; pending wall-clock timer (and watcher, for sub-vec delays).
          ;; The epoch advance backstops correctness; explicit cancellation
          ;; releases the timer handle promptly and avoids zombie sub
          ;; watchers across state re-entries.
          after-cancel-fx
          (when-not internal?
            (vec
              (for [[prefix n] exited-pairs
                    :when (:after n)]
                [:rf.machine/after-cancel
                 {:rf/parent-id parent-id
                  :rf/invoke-id (vec prefix)}])))
          destroy-fx
          (when-not internal?
            (vec
              (mapcat
                (fn [[prefix n]]
                  (cond
                    (:invoke n)
                    [[:rf.machine/destroy {:rf/parent-id parent-id
                                           :rf/invoke-id (vec prefix)}]]
                    (:invoke-all n)
                    ;; Per Spec 005 §Spawn-and-join via :invoke-all (rf2-6vmw):
                    ;; on exit, tear down EVERY child the parent spawned plus
                    ;; the join-state slot. The destroy-fx handler reads the
                    ;; map at [:rf/spawned <parent> <invoke-id>] and iterates
                    ;; :children to destroy each, then clears the slot.
                    [[:rf.machine/destroy {:rf/parent-id  parent-id
                                           :rf/invoke-id  (vec prefix)
                                           :rf/invoke-all true}]]
                    :else nil))
                exited-pairs)))
          [snap-after-spawns spawn-fx]
          (if internal?
            [snap-final []]
            (reduce
              (fn [[s acc-fx] [prefix n]]
                (cond
                  (:invoke n)
                  (let [inv         (:invoke n)
                        machine-id  (:machine-id inv)
                        invoke-id   (vec prefix)
                        ;; Frame-scoped allocation per Spec 002 frame isolation.
                        ;; Pure-call machine-transition (no frame context) uses
                        ;; a sentinel so the corpus's deterministic ids hold.
                        frame-id    (or (:rf/frame machine) :rf/transition-pure)
                        spawned-id  (or (:invoke-id inv)
                                        (next-spawn-id frame-id machine-id))
                        ;; Carry the resolved id forward so spawn-fx registers
                        ;; the live handler under the SAME id the :on-spawn
                        ;; callback observed (rf2-suue lifecycle wiring) AND
                        ;; the runtime can write [:rf/spawned parent-id
                        ;; invoke-id] -> spawned-id (rf2-t07u).
                        spawn-args  (-> inv
                                        (assoc :id-prefix    machine-id)
                                        (assoc :rf/spawned-id spawned-id)
                                        (assoc :rf/parent-id  parent-id)
                                        (assoc :rf/invoke-id  invoke-id))
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
                        ;; Per rf2-t07u (Option A revised): :on-spawn is now purely
                        ;; advisory user-side bookkeeping — the runtime no longer
                        ;; depends on the user writing the id under any specific
                        ;; :data slot. Runtime tracks the spawn-id itself in
                        ;; [:rf/spawned parent-id invoke-id].
                        s'          (if on-spawn-fn
                                      (let [data     (:data s)
                                            new-data (on-spawn-fn data spawned-id)]
                                        (if new-data
                                          (assoc s :data new-data)
                                          s))
                                      s)]
                    ;; Per rf2-3y3y: :timeout-ms / :on-timeout slots on
                    ;; :invoke are dropped; wall-clock guards on
                    ;; :invoke-bearing states are expressed via the parent
                    ;; state's :after slot. Registration-time validation
                    ;; rejects any :timeout-ms / :on-timeout key with
                    ;; :rf.error/invoke-timeout-ms-removed.
                    [s' (vec (concat acc-fx
                                     [[:rf.machine/spawn spawn-args]]))])

                  (:invoke-all n)
                  ;; Per Spec 005 §Spawn-and-join via :invoke-all (rf2-6vmw).
                  ;; Allocate one spawned-id per child up front, build the
                  ;; join-state seed, emit a single :rf.machine/invoke-all-init
                  ;; fx (which seeds [:rf/spawned <parent> <invoke-id>] in
                  ;; app-db) followed by N :rf.machine/spawn fxs.
                  (let [inv-all     (:invoke-all n)
                        children    (:children inv-all)
                        invoke-id   (vec prefix)
                        frame-id    (or (:rf/frame machine) :rf/transition-pure)
                        ;; Allocate per-child ids (deterministic via spawn-counter).
                        children'   (mapv
                                      (fn [child]
                                        (let [machine-id (:machine-id child)
                                              spawned-id (or (:invoke-id child)
                                                             (next-spawn-id frame-id machine-id))]
                                          (assoc child :rf/spawned-id spawned-id)))
                                      children)
                        ;; Build the join-state seed map.
                        children-map (into {} (map (fn [c] [(:id c) (:rf/spawned-id c)])) children')
                        join-state  {:children  children-map
                                     :done      #{}
                                     :failed    #{}
                                     :resolved? false
                                     :spec      inv-all
                                     :invoke-id invoke-id}
                        init-fx     [:rf.machine/invoke-all-init
                                     {:rf/parent-id parent-id
                                      :rf/invoke-id invoke-id
                                      :join-state   join-state}]
                        ;; Build per-child spawn-args.
                        child-spawn-fxs
                        (mapv
                          (fn [child]
                            (let [machine-id (:machine-id child)
                                  spawned-id (:rf/spawned-id child)
                                  spawn-args (-> child
                                                 (dissoc :id)
                                                 (assoc :id-prefix    machine-id)
                                                 (assoc :rf/spawned-id spawned-id)
                                                 (assoc :rf/parent-id  parent-id)
                                                 ;; The :invoke-all-id ties the spawn back
                                                 ;; to the parent's join state without
                                                 ;; re-using the :invoke-id slot meaning.
                                                 (assoc :rf/invoke-all-id invoke-id)
                                                 (assoc :rf/invoke-all-child-id (:id child)))]
                              [:rf.machine/spawn spawn-args]))
                          children')
                        ;; Run :on-spawn callbacks per child (advisory).
                        s' (reduce
                             (fn [snap child]
                               (let [aref (:on-spawn child)
                                     f    (when aref
                                            (or (chase-ref (:on-spawn-actions machine) aref)
                                                (chase-ref (:actions machine) aref)))]
                                 (if f
                                   (let [data     (:data snap)
                                         new-data (f data (:rf/spawned-id child))]
                                     (if new-data (assoc snap :data new-data) snap))
                                   snap)))
                             s
                             children')]
                    ;; Per rf2-3y3y: :timeout-ms / :on-timeout slots on
                    ;; :invoke-all are dropped; wall-clock guards on the
                    ;; whole-join are expressed via the :invoke-all-bearing
                    ;; state's :after slot.
                    [s' (vec (concat acc-fx [init-fx] child-spawn-fxs))])

                  :else
                  [s acc-fx]))
              [snap-final []]
              entered-pairs))
          all-fx (vec (concat fx
                              (or after-cancel-fx [])
                              (or destroy-fx [])
                              spawn-fx
                              (or after-fx [])))]
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
        ;; Trace timer firing / staleness / guard-suppression BEFORE
        ;; running the transition, so listeners see events in the order
        ;; they occurred.
        _ (when (and match (:stale? match))
            (trace/emit! :machine :rf.machine.timer/stale-after
                         {:state           (:state match)
                          :delay           (:delay match)
                          :scheduled-epoch (:scheduled-epoch match)
                          :current-epoch   (:current-epoch match)
                          :recovery        :replaced-with-default}))
        ;; Per Spec 005 §Multi-stage interaction with :guard: a guard-
        ;; suppressed :after still emits :rf.machine.timer/fired with
        ;; :fired? false; the snapshot is unchanged and sibling timers
        ;; continue.
        _ (when (and match (:guard-suppressed? match))
            (trace/emit! :machine :rf.machine.timer/fired
                         {:state  (:state match)
                          :delay  (:delay match)
                          :epoch  (:epoch match)
                          :fired? false}))
        _ (when (and match
                     (not (:stale? match))
                     (not (:guard-suppressed? match))
                     (:delay match))
            (trace/emit! :machine :rf.machine.timer/fired
                         {:state  (last (:decl-path match))
                          :delay  (:delay match)
                          :epoch  (:epoch match)
                          :fired? true}))
        result-after-event
        (cond
          (and match (:stale? match))
          [snapshot []]   ;; suppress: snapshot unchanged

          (and match (:guard-suppressed? match))
          [snapshot []]   ;; guard false: snapshot unchanged; siblings continue

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

;; ---- :invoke-all join-event interception (rf2-6vmw) ----------------------
;;
;; Per Spec 005 §Spawn-and-join via :invoke-all, the parent's handler
;; boundary intercepts events whose inner-event-id matches the active
;; state's :on-child-done / :on-child-error. The interception:
;;
;;   1. Resolves the active :invoke-all-bearing state by walking the
;;      snapshot's :state path leaf→root looking for a state node whose
;;      :invoke-all declares the matching event keyword.
;;   2. Reads the join state at [:rf/spawned <parent> <invoke-id>].
;;   3. Adds <child-id> (event[1]) to :done or :failed.
;;   4. If :resolved? is already true, the event is silently dropped
;;      (post-resolution late-completion).
;;   5. Else evaluates the join condition. On resolution:
;;        - latches :resolved? true
;;        - if :cancel-on-decision? (default true), emits per-sibling
;;          :rf.machine/destroy fx and :rf.machine.invoke/cancelled-on-
;;          join-resolution traces
;;        - dispatches the parent join event via :fx [[:dispatch ...]]
;;   6. Writes the new join state back into app-db.
;;
;; Returns either:
;;   - nil if the event is NOT a child-done/error for any active
;;     :invoke-all (caller continues with normal machine-transition)
;;   - a {:db ... :fx ...} effect map if the event was intercepted.

(defn- find-active-invoke-all
  "Walk the snapshot's :state path leaf→root looking for an
  :invoke-all-bearing state whose :on-child-done or :on-child-error
  matches the given inner-event-id. Returns
  {:invoke-id <prefix-path> :spec <invoke-all-spec> :kind :done|:failed}
  or nil."
  [machine snapshot inner-event-id]
  (let [path (state-path (:state snapshot))]
    (loop [i (dec (count path))]
      (when (>= i 0)
        (let [prefix (vec (take (inc i) path))
              n      (node-at machine prefix)
              ia     (:invoke-all n)]
          (cond
            (and ia (= inner-event-id (:on-child-done ia)))
            {:invoke-id prefix :spec ia :kind :done}
            (and ia (= inner-event-id (:on-child-error ia)))
            {:invoke-id prefix :spec ia :kind :failed}
            :else
            (recur (dec i))))))))

(defn- join-condition-met?
  "Evaluate the join condition against the current join state.
  Returns truthy iff the join has resolved on the success-side
  (:on-all-complete / :on-some-complete should fire)."
  [spec join-state]
  (let [join     (:join spec :all)
        children (:children spec)
        n-total  (count children)
        n-done   (count (:done   join-state))
        n-failed (count (:failed join-state))]
    (cond
      (= :all join)
      (= n-done n-total)

      (= :any join)
      (>= n-done 1)

      (and (map? join) (pos-int? (:n join)))
      (>= n-done (:n join))

      (and (map? join) (fn? (:fn join)))
      ((:fn join) {:done   (:done   join-state)
                   :failed (:failed join-state)
                   :total  n-total})

      :else false)))

(defn- intercept-invoke-all-event
  "Per Spec 005 §Child completion protocol (rf2-6vmw). When the parent's
  handler receives an event whose inner event-id matches the active
  :invoke-all-bearing state's :on-child-done / :on-child-error, the
  runtime updates the join state and (on resolution) cancels surviving
  siblings + dispatches the join event. The event is NOT fed into the
  machine's normal :on lookup.

  `db` is the frame's current app-db; `path` is [:rf/machines parent-id];
  `snapshot` is (get-in db path) (the parent's current snapshot);
  `parent-id` is the parent machine's id; `inner-event` is the routed
  inner event vector.

  Returns nil (NOT a child-event for any active :invoke-all) or a
  re-frame effect map with :db (updated app-db) and :fx (per-sibling
  destroys + the join-event dispatch)."
  [machine db path snapshot parent-id inner-event]
  (let [inner-id (first inner-event)
        match    (find-active-invoke-all machine snapshot inner-id)]
    (when match
      (let [{:keys [invoke-id spec kind]} match
            child-id   (second inner-event)
            ;; Read the live join state from app-db (the seed was written
            ;; by :rf.machine/invoke-all-init on entry).
            join-state (get-in db [:rf/spawned parent-id invoke-id])]
        (cond
          ;; Pure-call snapshot: no app-db join state seeded yet — fall
          ;; through to no-op (the runtime tracks join state via the fx
          ;; handlers, not via the pure machine-transition).
          (or (not (map? join-state))
              (not (contains? join-state :children)))
          {:db db :fx []}

          ;; Already resolved: ignore late-completion. Trace once for
          ;; observability so tools can correlate.
          (:resolved? join-state)
          (do (trace/emit! :machine :rf.machine.invoke-all/late-completion
                           {:machine-id parent-id
                            :invoke-id  invoke-id
                            :child-id   child-id
                            :kind       kind})
              {:db db :fx []})

          :else
          (let [;; Update the join state.
                join-state' (case kind
                              :done   (update join-state :done   (fnil conj #{}) child-id)
                              :failed (update join-state :failed (fnil conj #{}) child-id))
                ;; Evaluate. The :on-any-failed path resolves first if
                ;; (a) the spec declares it, (b) we just added a failed
                ;; entry. Otherwise check the success-side condition.
                cancel?  (let [c (:cancel-on-decision? spec)]
                           (if (nil? c) true (boolean c)))
                fail-fired?
                (and (= kind :failed)
                     (vector? (:on-any-failed spec)))
                success-fired?
                (and (not fail-fired?)
                     (join-condition-met? spec join-state'))
                resolution-event
                (cond
                  fail-fired?    (:on-any-failed spec)
                  success-fired? (cond
                                   (= :all (:join spec :all)) (:on-all-complete spec)
                                   :else                       (:on-some-complete spec)))
                resolved? (boolean (or fail-fired? success-fired?))
                join-state'' (assoc join-state' :resolved? resolved?)
                ;; Emit the appropriate trace events.
                _ (when fail-fired?
                    (trace/emit! :machine :rf.machine.invoke-all/any-failed
                                 {:machine-id parent-id
                                  :invoke-id  invoke-id
                                  :failed-id  child-id
                                  :failed     (:failed join-state'')
                                  :done       (:done   join-state'')}))
                _ (when (and success-fired? (= :all (:join spec :all)))
                    (trace/emit! :machine :rf.machine.invoke-all/all-completed
                                 {:machine-id parent-id
                                  :invoke-id  invoke-id
                                  :done       (:done join-state'')}))
                _ (when (and success-fired? (not= :all (:join spec :all)))
                    (trace/emit! :machine :rf.machine.invoke-all/some-completed
                                 {:machine-id parent-id
                                  :invoke-id  invoke-id
                                  :done       (:done join-state'')
                                  :join       (:join spec)}))
                ;; If resolved + cancel?: build per-sibling destroy fx
                ;; for children NOT in :done or :failed (the in-flight
                ;; survivors).
                cancel-fx
                (when (and resolved? cancel?)
                  (let [completed-ids (into #{} (concat (:done join-state'')
                                                        (:failed join-state'')))
                        survivors     (->> (:children join-state'')
                                           (remove (fn [[cid _]] (contains? completed-ids cid))))
                        join-event-kw (cond
                                        fail-fired?    :on-any-failed
                                        (= :all (:join spec :all)) :on-all-complete
                                        :else :on-some-complete)]
                    (doseq [[cid spawned-id] survivors]
                      (trace/emit! :machine :rf.machine.invoke/cancelled-on-join-resolution
                                   {:machine-id parent-id
                                    :invoke-id  invoke-id
                                    :child-id   cid
                                    :spawned-id spawned-id
                                    :join-event join-event-kw}))
                    (mapv (fn [[_ spawned-id]]
                            [:rf.machine/destroy spawned-id])
                          survivors)))
                ;; Build the dispatch fx that fires the join-event into
                ;; the parent. The dispatched event is in standard
                ;; sub-event shape: [<parent-id> <event-vec>].
                dispatch-fx
                (when resolution-event
                  [[:dispatch (into [parent-id resolution-event] [])]])
                fx (vec (concat (or cancel-fx []) (or dispatch-fx [])))
                new-db (assoc-in db [:rf/spawned parent-id invoke-id] join-state'')]
            {:db new-db :fx fx}))))))

;; ---- :invoke / :invoke-all :timeout-ms interception — REMOVED (rf2-3y3y) -
;;
;; The pre-release :timeout-ms / :on-timeout slot on :invoke / :invoke-all
;; was DROPPED per rf2-3y3y. State-level :after on the parent state
;; subsumes the wall-clock guard, with the standard exit-cascade
;; destroying spawned children. See MIGRATION §M-41 for the rewrite
;; recipe.
;;
;; Registration-time validation rejects any :timeout-ms / :on-timeout key
;; on :invoke / :invoke-all with :rf.error/invoke-timeout-ms-removed (see
;; validate-no-invoke-timeout-ms! below).

(defn- validate-no-invoke-timeout-ms!
  "Per rf2-3y3y / Spec 005 §Wall-clock timeouts on :invoke — use parent
  state's :after, the pre-release :timeout-ms / :on-timeout slots on
  :invoke and :invoke-all are DROPPED. State-level :after on the parent
  state subsumes the wall-clock guard, with the standard exit-cascade
  destroying spawned children when the timer fires.

  Registration-time error: :rf.error/invoke-timeout-ms-removed — emitted
  if either :timeout-ms or :on-timeout is set on an :invoke or
  :invoke-all spec. The error carries a migration message pointing at
  MIGRATION §M-41."
  [state-key state-node]
  (doseq [[slot-key spec]
          [[:invoke     (:invoke state-node)]
           [:invoke-all (:invoke-all state-node)]]]
    (when (map? spec)
      (let [t  (:timeout-ms spec)
            ot (:on-timeout spec)]
        (when (or (some? t) (some? ot))
          (throw (ex-info ":rf.error/invoke-timeout-ms-removed"
                          {:state      state-key
                           :slot       slot-key
                           :timeout-ms t
                           :on-timeout ot
                           :reason
                           (str ":timeout-ms / :on-timeout on " slot-key
                                " were dropped per rf2-3y3y. Use the parent "
                                "state's :after slot for wall-clock guards. "
                                "See MIGRATION.md §M-41 for the rewrite "
                                "recipe.")
                           :migration "MIGRATION.md §M-41"})))))))

(defn- validate-invoke-all!
  "Per Spec 005 §Spawn-and-join via :invoke-all (rf2-6vmw): walk the
  state tree at registration time and reject malformed :invoke-all
  declarations.

  Three error categories:
    - :rf.error/machine-invoke-all-bad-shape — a child invoke-spec is
      missing :id; or :invoke-all is not a vector; or the join-event
      slots are missing per the required-iff rules; or no :machine-id
      / :definition.
    - :rf.error/machine-invoke-all-duplicate-id — two children share an
      :id keyword inside the same :invoke-all block.
    - :rf.error/machine-invoke-all-with-invoke — a state node declares
      both :invoke and :invoke-all (mutually exclusive)."
  [state-key state-node]
  (let [inv-all (:invoke-all state-node)]
    (when inv-all
      (when (:invoke state-node)
        (throw (ex-info ":rf.error/machine-invoke-all-with-invoke"
                        {:state state-key})))
      (when-not (map? inv-all)
        (throw (ex-info ":rf.error/machine-invoke-all-bad-shape"
                        {:state state-key
                         :reason "invoke-all slot must be a map"})))
      (let [children (:children inv-all)]
        (when-not (and (vector? children) (seq children))
          (throw (ex-info ":rf.error/machine-invoke-all-bad-shape"
                          {:state state-key
                           :reason ":children must be a non-empty vector of child specs"})))
        (doseq [c children]
          (when-not (and (map? c) (keyword? (:id c)))
            (throw (ex-info ":rf.error/machine-invoke-all-bad-shape"
                            {:state state-key
                             :reason "each child invoke-spec must declare an :id keyword"
                             :child c})))
          (when-not (or (:machine-id c) (:definition c))
            (throw (ex-info ":rf.error/machine-invoke-all-bad-shape"
                            {:state state-key
                             :reason "each child invoke-spec must declare :machine-id or :definition"
                             :child c}))))
        (let [ids (map :id children)]
          (when (not= (count ids) (count (set ids)))
            (let [dup (->> (frequencies ids) (filter (fn [[_ n]] (> n 1))) (map first))]
              (throw (ex-info ":rf.error/machine-invoke-all-duplicate-id"
                              {:state state-key :duplicate-ids dup}))))))
      (when-not (keyword? (:on-child-done inv-all))
        (throw (ex-info ":rf.error/machine-invoke-all-bad-shape"
                        {:state state-key
                         :reason ":on-child-done is required (event keyword)"})))
      (when-not (keyword? (:on-child-error inv-all))
        (throw (ex-info ":rf.error/machine-invoke-all-bad-shape"
                        {:state state-key
                         :reason ":on-child-error is required (event keyword)"})))
      (let [join (:join inv-all :all)]
        (cond
          (= :all join)
          (when-not (vector? (:on-all-complete inv-all))
            (throw (ex-info ":rf.error/machine-invoke-all-bad-shape"
                            {:state state-key
                             :reason ":on-all-complete event-vector is required when :join is :all (default)"})))
          (or (= :any join)
              (and (map? join) (or (pos-int? (:n join))
                                   (fn? (:fn join)))))
          (when-not (vector? (:on-some-complete inv-all))
            (throw (ex-info ":rf.error/machine-invoke-all-bad-shape"
                            {:state state-key
                             :reason ":on-some-complete event-vector is required when :join is :any / {:n N} / {:fn ...}"})))
          :else
          (throw (ex-info ":rf.error/machine-invoke-all-bad-shape"
                          {:state state-key
                           :reason ":join must be :all, :any, {:n pos-int}, or {:fn fn?}"
                           :join join})))))))

(defn- walk-state-nodes
  "Yield [state-key state-node] pairs for every node under :states,
  recursing through :states maps. Used by registration-time validators."
  [machine]
  (letfn [(walk [path nodes]
            (mapcat
              (fn [[k n]]
                (cons [k n]
                      (when (:states n)
                        (walk (conj path k) (:states n)))))
              nodes))]
    (walk [] (:states machine))))

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
  ;; Per Spec 005 §Spawn-and-join via :invoke-all (rf2-6vmw): walk the
  ;; full state tree (including nested :states) and reject malformed
  ;; :invoke-all declarations at registration time. Per rf2-3y3y: reject
  ;; the dropped :timeout-ms / :on-timeout slots on :invoke / :invoke-all
  ;; (use parent state's :after instead — see MIGRATION §M-41).
  (doseq [[s n] (walk-state-nodes machine)]
    (validate-invoke-all! s n)
    (validate-no-invoke-timeout-ms! s n))
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
                            :rf/frame     frame-id
                            :rf/platform  platform
                            ;; Per rf2-t07u (Option A revised): stamp the
                            ;; surrounding registration's event-id (the
                            ;; machine-id, derived from event[0]) so
                            ;; apply-transition-once can emit
                            ;; :rf.machine/spawn / :rf.machine/destroy
                            ;; fx whose args carry
                            ;; :rf/parent-id (used by the fx handlers to
                            ;; address the runtime-owned [:rf/spawned
                            ;; <parent-id> <invoke-id>] registry slot).
                            :rf/parent-id machine-id)
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
          ;; Per Spec 005 §Snapshot shape (`{:state :data :meta?}`) and
          ;; §Versioned via :meta: a machine's spec-level :meta (e.g.
          ;; `:rf/snapshot-version`, user introspection tags) propagates
          ;; into the lazily-synthesised initial snapshot so the 3-arity
          ;; ctx (and any downstream version-check) sees the same :meta
          ;; the spec declares. Without this, a `:meta` declared on the
          ;; spec is invisible until a transition explicitly seeds it
          ;; via an action's `:meta` write — divergent with the
          ;; pure-surface harness which receives the spec :meta directly.
          initial    (cond-> {:state initial-state
                              :data  (or (:data machine) {})}
                       (some? (:meta machine)) (assoc :meta (:meta machine)))
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
          ;; Per Spec 005 §Spawn-and-join via :invoke-all (rf2-6vmw):
          ;; intercept :on-child-done / :on-child-error events for any
          ;; :invoke-all-bearing state currently active. The intercept
          ;; updates the runtime-owned join state and (on resolution)
          ;; fires per-sibling cancellations + the join event. Returns
          ;; nil if the event is not a child-event for the current state.
          ;;
          ;; Per rf2-3y3y: the pre-release :rf.machine.invoke.timeout/elapsed
          ;; interception is removed alongside the dropped :timeout-ms
          ;; slot on :invoke / :invoke-all. Wall-clock guards on
          ;; :invoke-bearing states are now expressed via the parent
          ;; state's :after slot, whose synthetic
          ;; :rf.machine.timer/after-elapsed event flows through the
          ;; standard pick-after-transition path.
          intercepted (intercept-invoke-all-event machine db path snapshot machine-id inner-event)]
      (if intercepted
        ;; The event was a join-protocol event; the intercept did its
        ;; bookkeeping in app-db and set up the resolution dispatch.
        intercepted
        (let [step-result (machine-transition machine snapshot inner-event)]
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
           :fx fx}))))))) ;; close: inner if, let step-result, outer if, let with snapshot/inner-event, fn, defn

;; ---- reg-machine* — plain-fn surface (rf2-8bp3) ---------------------------

(defn reg-machine*
  "Plain-fn surface beneath the `reg-machine` macro. Registers a machine
  as an event handler under `machine-id`. Equivalent to
  `(reg-event-fx machine-id (create-machine-handler machine))`.

  Per Spec 005 §reg-machine vs reg-machine*: the macro `reg-machine`
  walks the literal spec form at expansion time and stamps per-element
  source coordinates onto the spec's `:rf.machine/source-coords` key.
  This fn assumes no such walking — it accepts whatever spec map the
  caller has already constructed (which may or may not carry an
  `:rf.machine/source-coords` index pre-stamped by the macro path or
  by a code-gen pipeline). Use this fn for runtime registration with
  computed ids, fixture-synthesised specs, or REPL workflows.

  Per Spec 005 §Querying machines, the registration metadata is stamped
  with :rf/machine? true and :rf/machine (the spec map). (rf/machines)
  filters the :event registry by :rf/machine?; (rf/machine-meta id)
  reads the spec back out via the standard registrar query API.

  Per Spec 001 §Source-coordinate capture, the call-site `:ns` /
  `:line` / `:file` carried by `re-frame.source-coords/*pending-coords*`
  (set by the `reg-machine` macro) is merged into the registration
  metadata via the `reg-event-fx` defn's `merge-coords` call. Programmatic
  callers that bypass the macro see no top-level coords — that's the
  documented Spec 001 behaviour for programmatic registration."
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
;; effects `:rf.machine/spawn` and `:rf.machine/destroy` are emitted
;; into the fx vector by `apply-transition-once` whenever entry/exit
;; cascades cross an :invoke-bearing state. Per rf2-xbtj these handlers
;; live in this namespace (rather than `re-frame.fx`'s reserved
;; case-block) so an app that doesn't pull in
;; `day8/re-frame-2-machines` carries neither the trace strings
;; (`:rf.machine/spawned`, `:rf.machine/destroyed`) nor the handler
;; symbols on its production-elision bundle.
;;
;; The fns are `defn`s (not `fn`s passed inline to `reg-fx`) so the
;; late-bind hook table can publish them under
;; `:machines/spawn-fx` / `:machines/destroy-machine-fx` for any
;; downstream namespace that wants to invoke them programmatically (the
;; conformance corpus does not — it consumes the fx vector directly —
;; but the hooks keep the producer surface uniform with the rest of the
;; machines namespace's late-bound entry points).

;; ---- spawn / destroy live-handler wiring ---------------------------------
;;
;; `apply-transition-once` emits `[:rf.machine/spawn args]` and
;; `[:rf.machine/destroy actor-id]` into the fx vector whenever
;; entry/exit cascades cross an :invoke-bearing state. Until rf2-suue,
;; `spawn-fx` and `destroy-machine-fx` were trace-only stubs — the
;; spawned actor was a deterministic id, but no event handler was
;; registered against that id and no snapshot lived under
;; [:rf/machines <id>]. Per Spec 005 §Spawning the actor is itself
;; an event handler whose id is the actor address; this section closes
;; that gap.
;;
;; The two-tier registry described in Spec 005 (frame-local handlers that
;; revert with the frame's snapshot) is not yet built — for v1 the
;; registration goes through the global registrar via `events/reg-event-fx`.
;; Frame isolation is preserved by the snapshot living at
;; `[:rf/machines <id>]` inside the spawning frame's app-db; the registered
;; handler is global but reads/writes that frame-local snapshot via the
;; cofx's :db.

(defn- compute-actor-id
  "Resolve the actor id for a spawn. Per Spec 005 §Declarative :invoke
  Spec-spec keys: `:invoke-id` is an explicit id (per-state singleton);
  otherwise the runtime allocated id is sourced from the spawn args
  the transition runner produced via next-spawn-id. The runner doesn't
  pass the resolved id through directly — it carries `:id-prefix` and
  invokes `:on-spawn` with the allocated id — so the fx handler needs
  to ask for the same id via the same allocator. We thread the id via
  the args' `:rf/spawned-id` slot (added by apply-transition-once below)."
  [args frame-id]
  (or (:invoke-id args)
      (:rf/spawned-id args)
      ;; Fallback: re-allocate (shouldn't happen for declarative :invoke,
      ;; but supports hand-emitted [:rf.machine/spawn args] forms from
      ;; action :fx).
      (next-spawn-id frame-id (or (:id-prefix args) (:machine-id args)))))

(defn- update-frame-app-db!
  "Apply `f` to the frame's app-db value via the substrate adapter.
  Returns the new value. Skips silently if the frame is missing
  (frame destroyed during drain)."
  [frame-id f]
  (when-let [container (frame/get-frame-db frame-id)]
    (let [old-db (adapter/read-container container)
          new-db (f old-db)]
      (adapter/replace-container! container new-db)
      new-db)))

(defn- resolve-spawn-machine
  "Resolve the machine spec to register for a spawn. `:machine-id`
  references a registered machine — read its spec back from the
  registrar via the `:rf/machine` metadata. `:definition` carries an
  inline spec map. Returns the spec map or nil if neither resolves.
  Per [005 §Spawn-spec keys]."
  [args]
  (let [machine-id (:machine-id args)
        defn       (:definition args)]
    (cond
      defn        defn
      machine-id  (let [m (registrar/lookup :event machine-id)]
                    (when (:rf/machine? m)
                      (:rf/machine m))))))

(defn spawn-fx
  "fx handler for `:rf.machine/spawn`. Per Spec 005 §Spawning, the spawned actor
  is itself an event handler at `<spawned-id>`; its snapshot lives at
  `[:rf/machines <spawned-id>]` in the spawning frame's app-db.

  Lifecycle wired here:
   1. Resolve the spawn's machine spec (`:machine-id` from the registrar
      OR an inline `:definition`).
   2. Register the live event handler under the spawned id via
      `create-machine-handler` / `reg-event-fx`. Re-spawn under the
      same id replaces — last-write-wins, matching standard
      re-registration.
   3. Initialise the actor's snapshot at [:rf/machines <spawned-id>]
      using the spec's :initial / :data (overridden by the spawn args'
      :data). Per rf2-ijm7 the runtime stamps `:rf/self-id` (the
      spawned actor's own address) and, when applicable,
      `:rf/parent-id` + `:rf/invoke-id` into the actor's initial
      `:data` under the framework-reserved `:rf/*` namespace — so
      spawned actors can dispatch back to their parent without
      hard-coding the parent's id at spec-write time (a precondition
      for generic re-usable child machines like the `:rf.http/managed`
      wrapper).
   4. If `:system-id` present, bind it in the per-frame
      [:rf/system-ids] reverse index. Collisions emit
      `:rf.error/system-id-collision` and rebind (last-write-wins, same
      semantics as handler re-registration).
   5. If `:rf/parent-id` + `:rf/invoke-id` present (declarative `:invoke`
      desugar — rf2-t07u Option A revised), bind the spawned id at
      `[:rf/spawned <parent-id> <invoke-id>]` so the runtime can locate
      it on destroy without depending on the user's `:on-spawn` having
      written the id under any particular `:data` slot.
   6. If `:start` event-vector present, dispatch
      `[<spawned-id> <start>]` so the new actor receives its initial
      event. When `:start` is absent (per rf2-ijm7), the runtime
      dispatches a synthetic `[<spawned-id> [:rf.machine/spawned]]` so
      generic child machines may declare a leaf-level `:on
      :rf.machine/spawned :target ...` transition that fires the
      actor's first work on entry. Machines that don't handle
      `:rf.machine/spawned` see the event as a benign no-op (no
      matching `:on` clause leaves the snapshot unchanged)."
  [{:keys [frame]} args]
  (let [frame-id   (or frame :rf/default)
        spawned-id (compute-actor-id args frame-id)
        spec       (resolve-spawn-machine args)
        ;; data override per Spec 005 §Spawn-spec keys
        spec'      (if (and spec (contains? args :data))
                     (assoc spec :data (:data args))
                     spec)
        system-id  (:system-id args)
        ;; Per rf2-t07u (Option A revised): the runtime tracks each
        ;; declarative-:invoke spawn at [:rf/spawned <parent-id> <invoke-id>]
        ;; — populated only when the spawn carries both. Imperative
        ;; from-action `[:rf.machine/spawn ...]` calls leave these absent
        ;; and the slot is left untouched (the user owns destroy via
        ;; hand-emitted `[:rf.machine/destroy actor-id]` in those cases,
        ;; exactly as before).
        parent-id  (:rf/parent-id args)
        invoke-id  (:rf/invoke-id args)
        track?     (and parent-id invoke-id)
        ;; Per rf2-ijm7: stamp framework-reserved keys into the spawned
        ;; actor's initial :data so the actor knows its own address
        ;; (:rf/self-id) and, for declarative-:invoke spawns, its
        ;; parent's address (:rf/parent-id) + its invoke-id
        ;; (:rf/invoke-id). The `:rf/`-namespace inside :data is
        ;; reserved for runtime-managed keys per Spec 005 §`:after`
        ;; epoch counter; user code never reads or writes under
        ;; `:rf/*`. Stamping unconditionally (self-id) plus
        ;; conditionally (parent-id / invoke-id) keeps non-:invoke
        ;; imperative spawns untouched in the parent-tracking case
        ;; while uniformly giving every spawned actor its own id.
        spec''     (if spec'
                     (let [base-data (or (:data spec') {})
                           data'     (cond-> (assoc base-data :rf/self-id spawned-id)
                                       parent-id (assoc :rf/parent-id parent-id)
                                       invoke-id (assoc :rf/invoke-id invoke-id))]
                       (assoc spec' :data data'))
                     spec')]
    (trace/emit! :machine :rf.machine/spawned
                 {:frame      frame-id
                  :machine-id (:machine-id args)
                  :spawned-id spawned-id
                  :id-prefix  (:id-prefix args)
                  :start      (:start args)
                  :on-spawn   (:on-spawn args)
                  :system-id  system-id
                  :parent-id  parent-id
                  :invoke-id  invoke-id})
    (when spec''
      ;; (2) Register the live handler under the spawned id. Re-using
      ;; reg-machine* (the plain-fn surface beneath the macro, per
      ;; rf2-8bp3) so the same `:rf/machine?` metadata + lifecycle
      ;; trace flows. The macro form is reserved for user-call-site
      ;; literal-spec source-coord stamping; spawn synthesises specs
      ;; at runtime, so the plain-fn surface is the right entry.
      (reg-machine* spawned-id spec''))
    ;; (3) Initialise the snapshot + (4) bind :system-id + (5) bind the
    ;; runtime-owned spawn registry (atomically under one app-db swap so
    ;; observers see consistent state).
    (when-let [container (frame/get-frame-db frame-id)]
      (let [initial-decl  (:initial spec'')
            initial-path  (when spec''
                            (initial-cascade spec'' (state-path initial-decl)))
            initial-state (when spec''
                            (denormalise-state initial-path initial-decl))
            initial-snap  (when spec''
                            {:state initial-state
                             :data  (or (:data spec'') {})})
            old-db        (adapter/read-container container)
            ;; Detect collision BEFORE the swap so we can emit the
            ;; error trace with the displaced binding's id.
            existing      (when system-id
                            (get-in old-db [:rf/system-ids system-id]))
            new-db
            (cond-> old-db
              spec''     (assoc-in [:rf/machines spawned-id] initial-snap)
              system-id  (assoc-in [:rf/system-ids system-id] spawned-id)
              track?     (assoc-in [:rf/spawned parent-id invoke-id] spawned-id))]
        (when (and system-id existing (not= existing spawned-id))
          (trace/emit-error! :rf.error/system-id-collision
                             {:frame             frame-id
                              :system-id         system-id
                              :existing-machine  existing
                              :rebound-to        spawned-id
                              :reason            (str ":system-id " system-id
                                                      " was already bound to "
                                                      existing
                                                      "; rebinding to " spawned-id
                                                      " (last-write-wins).")
                              :recovery          :warned-and-replaced}))
        (adapter/replace-container! container new-db)
        (when system-id
          (trace/emit! :machine :rf.machine/system-id-bound
                       {:frame      frame-id
                        :system-id  system-id
                        :machine-id spawned-id}))))
    ;; (6) Fire the :start event into the new actor. Per rf2-ijm7,
    ;; spawns that don't supply :start receive a synthetic
    ;; [:rf.machine/spawned] so generic child machines (e.g. the
    ;; `:rf.http/managed` machine-shape wrapper) can declare their
    ;; first transition out of an :initial state at spec-write time
    ;; without requiring the parent to set up a :start kick.
    (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
      (let [start (:start args)]
        (if (some? start)
          (dispatch! [spawned-id start] {:frame frame-id})
          (dispatch! [spawned-id [:rf.machine/spawned]] {:frame frame-id}))))
    spawned-id))

(defn invoke-all-init-fx
  "fx handler for `:rf.machine/invoke-all-init` (rf2-6vmw). Per Spec 005
  §Spawn-and-join via :invoke-all, on entry to an :invoke-all-bearing
  state the runtime emits this fx (alongside per-child :rf.machine/spawn
  fxs) to seed the join state at [:rf/spawned <parent> <invoke-id>] in
  the frame's app-db. The seed map shape is:

    {:children {<child-id> <spawned-id>, ...}
     :done      #{}
     :failed    #{}
     :resolved? false
     :spec      <invoke-all-spec>}

  Subsequent :on-child-done / :on-child-error events arrive at the
  parent's create-machine-handler boundary and are intercepted by
  intercept-invoke-all-event, which updates this slot and (on
  resolution) cancels surviving siblings + dispatches the join event."
  [{:keys [frame]} args]
  (let [frame-id   (or frame :rf/default)
        parent-id  (:rf/parent-id args)
        invoke-id  (:rf/invoke-id args)
        join-state (:join-state args)
        children   (:children join-state)]
    (when-let [container (frame/get-frame-db frame-id)]
      (let [old-db (adapter/read-container container)
            new-db (assoc-in old-db [:rf/spawned parent-id invoke-id] join-state)]
        (adapter/replace-container! container new-db)))
    (trace/emit! :machine :rf.machine.invoke-all/started
                 {:machine-id parent-id
                  :invoke-id  invoke-id
                  :child-ids  (set (keys children))
                  :children   children
                  :frame      frame-id})
    nil))

;; ---- in-flight HTTP abort cascade (rf2-wvkn) ------------------------------
;;
;; Per Spec 005 §Cancellation cascade — in-flight `:rf.http/managed`
;; aborts: when a spawned state-machine actor is destroyed, every
;; in-flight `:rf.http/managed` request the actor had issued is
;; aborted. The abort is fired through the late-bind hook
;; `:http/abort-on-actor-destroy` — re-frame.machines does NOT
;; statically `:require` re-frame.http-managed; the destroy path
;; looks up this fn at call time. When the http artefact is not on
;; the classpath the hook resolves to nil and the destroy proceeds
;; without any abort cascade — apps that don't issue managed-HTTP
;; requests pay nothing.

(defn- abort-actor-in-flight-http!
  "Fire the late-bind hook that aborts every in-flight `:rf.http/managed`
  request for the destroyed actor. Idempotent and safe to call when
  the http artefact is absent (returns nil)."
  [actor-id]
  (when actor-id
    (when-let [abort! (late-bind/get-fn :http/abort-on-actor-destroy)]
      (try (abort! actor-id)
           (catch #?(:clj Throwable :cljs :default) _ nil))))
  nil)

(defn- destroy-single-actor!
  "Destroy a single spawned actor: dissoc the snapshot at
  [:rf/machines <id>], clear any :system-id binding pointing at it,
  unregister the live event handler. Used by destroy-machine-fx for
  the keyword-form legacy/imperative destroy AND iterated for each
  child in an :invoke-all teardown.

  Per rf2-wvkn (Spec 005 §Cancellation cascade), also fires the
  `:http/abort-on-actor-destroy` late-bind hook so any in-flight
  `:rf.http/managed` requests the actor had issued are aborted."
  [frame-id actor-id]
  (when actor-id
    ;; rf2-wvkn — abort in-flight HTTP first. Late-bind hook; nil-safe.
    (abort-actor-in-flight-http! actor-id)
    (let [released-sid
          (when (frame/get-frame-db frame-id)
            (let [db (adapter/read-container (frame/get-frame-db frame-id))]
              (some (fn [[sid mid]]
                      (when (= mid actor-id) sid))
                    (get db :rf/system-ids))))]
      (when-let [container (frame/get-frame-db frame-id)]
        (let [old-db (adapter/read-container container)
              new-db (cond-> old-db
                       true         (update :rf/machines dissoc actor-id)
                       released-sid (update :rf/system-ids dissoc released-sid))]
          (adapter/replace-container! container new-db)))
      (when released-sid
        (trace/emit! :machine :rf.machine/system-id-released
                     {:frame      frame-id
                      :system-id  released-sid
                      :machine-id actor-id}))
      (registrar/unregister! :event actor-id)
      released-sid)))

(declare destroy-machine-fx-single)

(defn destroy-machine-fx
  "fx handler for `:rf.machine/destroy`. Per Spec 005 §Spawning, destroy
  unregisters the spawned actor's event handler, clears its snapshot at
  `[:rf/machines <id>]` in the spawning frame's app-db, and (if the
  actor was system-id-bound) clears the `[:rf/system-ids]` reverse
  index entry. Emits `:rf.machine/destroyed` and (when applicable)
  `:rf.machine/system-id-released`.

  Per rf2-t07u (Option A revised), `args` can be either:
    - a keyword `actor-id` — the legacy / imperative form (action emits
      `[:rf.machine/destroy actor-id]` directly with the recorded id), OR
    - a map `{:rf/parent-id ... :rf/invoke-id ...}` — the declarative-
      `:invoke` exit-cascade form, where the runtime resolves the actor
      id from `[:rf/spawned <parent-id> <invoke-id>]` in the frame's
      app-db (no longer reads the user's `:data.:pending`).

  Per rf2-6vmw, the map form may also carry `:rf/invoke-all true` —
  the declarative-:invoke-all exit-cascade form. The slot at
  [:rf/spawned <parent-id> <invoke-id>] holds a join-state map whose
  :children sub-map has every spawned child id. The handler iterates
  :children and tears each one down, then clears the slot.

  The map form clears the `[:rf/spawned <parent-id> <invoke-id>]` slot
  alongside the snapshot + system-id bookkeeping; the keyword form
  leaves it untouched (the imperative spawn never wrote it)."
  [{:keys [frame]} args]
  (let [frame-id  (or frame :rf/default)
        tracked?  (map? args)
        invoke-all? (and tracked? (true? (:rf/invoke-all args)))
        parent-id (when tracked? (:rf/parent-id args))
        invoke-id (when tracked? (:rf/invoke-id args))]
    (cond
      ;; ---- :invoke-all teardown form (rf2-6vmw) ----
      ;; Read the join-state map, destroy each :children entry, clear
      ;; the slot. Emit one :rf.machine/destroyed trace per child.
      invoke-all?
      (let [join-state (when-let [container (frame/get-frame-db frame-id)]
                         (get-in (adapter/read-container container)
                                 [:rf/spawned parent-id invoke-id]))
            children   (when (map? join-state) (:children join-state))]
        (doseq [[child-id spawned-id] children]
          (trace/emit! :machine :rf.machine/destroyed
                       {:frame      frame-id
                        :actor-id   spawned-id
                        :parent-id  parent-id
                        :invoke-id  invoke-id
                        :child-id   child-id})
          (destroy-single-actor! frame-id spawned-id))
        ;; Clear the slot + lazy-allocation prune.
        (when-let [container (frame/get-frame-db frame-id)]
          (let [old-db (adapter/read-container container)
                new-db (cond-> old-db
                         true (update-in [:rf/spawned parent-id]
                                         dissoc invoke-id)
                         (empty? (get-in old-db [:rf/spawned parent-id]))
                         (update :rf/spawned dissoc parent-id))
                new-db (cond-> new-db
                         (empty? (get new-db :rf/spawned))
                         (dissoc :rf/spawned))]
            (adapter/replace-container! container new-db)))
        nil)

      :else
      (destroy-machine-fx-single {:frame frame} args))))

(defn- destroy-machine-fx-single
  "Original :rf.machine/destroy implementation — handles the keyword
  (legacy/imperative) form and the single-:invoke (tracked map) form."
  [{:keys [frame]} args]
  (let [frame-id  (or frame :rf/default)
        tracked?  (map? args)
        parent-id (when tracked? (:rf/parent-id args))
        invoke-id (when tracked? (:rf/invoke-id args))
        ;; Resolve the actor id. For the tracked (map) form, read it
        ;; from the runtime-owned [:rf/spawned ...] slot. For the
        ;; legacy (keyword) form, the args IS the id.
        actor-id
        (cond
          (not tracked?) args
          :else (when-let [container (frame/get-frame-db frame-id)]
                  (get-in (adapter/read-container container)
                          [:rf/spawned parent-id invoke-id])))
        ;; Locate any :system-id binding for this actor BEFORE the swap
        ;; so we can name the released system-id in the trace.
        released-sid
        (when (and actor-id (frame/get-frame-db frame-id))
          (let [db (adapter/read-container (frame/get-frame-db frame-id))]
            (some (fn [[sid mid]]
                    (when (= mid actor-id) sid))
                  (get db :rf/system-ids))))]
    (trace/emit! :machine :rf.machine/destroyed
                 {:frame      frame-id
                  :actor-id   actor-id
                  :system-id  released-sid
                  :parent-id  parent-id
                  :invoke-id  invoke-id})
    ;; Tracked-form destroy with no resolved actor-id is a benign no-op:
    ;; the spawn slot was already cleared (e.g. by an earlier explicit
    ;; destroy) or the spawn was suppressed (SSR / platform gating).
    (when actor-id
      ;; rf2-wvkn — abort in-flight HTTP first (Spec 005 §Cancellation
      ;; cascade). Late-bind hook; nil-safe when the http artefact is
      ;; absent.
      (abort-actor-in-flight-http! actor-id)
      ;; Clear snapshot + system-id binding + (rf2-t07u) the spawn registry
      ;; slot, atomically under one app-db swap.
      (when-let [container (frame/get-frame-db frame-id)]
        (let [old-db (adapter/read-container container)
              new-db (cond-> old-db
                       true          (update :rf/machines dissoc actor-id)
                       released-sid  (update :rf/system-ids dissoc released-sid)
                       tracked?      (update-in [:rf/spawned parent-id]
                                                dissoc invoke-id))
              ;; Tidy up the per-parent map if it just emptied — same
              ;; lazy-allocation invariant as :rf/system-ids.
              new-db (cond-> new-db
                       (and tracked?
                            (empty? (get-in new-db [:rf/spawned parent-id])))
                       (update :rf/spawned dissoc parent-id))
              new-db (cond-> new-db
                       (and tracked? (empty? (get new-db :rf/spawned)))
                       (dissoc :rf/spawned))]
          (adapter/replace-container! container new-db)))
      (when released-sid
        (trace/emit! :machine :rf.machine/system-id-released
                     {:frame      frame-id
                      :system-id  released-sid
                      :machine-id actor-id}))
      ;; Unregister the live handler. Last so any in-flight trace emit
      ;; against the actor still resolves before the slot disappears.
      (registrar/unregister! :event actor-id))
    nil))

;; ---- :after timer scheduling (rf2-3y3y) -----------------------------------
;;
;; Per Spec 005 §Delayed :after transitions, on entry to an :after-bearing
;; state node the runtime schedules one real wall-clock timer per :after
;; entry via interop/set-timeout!. On expiry, the timer fires the
;; synthetic event
;;
;;   [<parent-id> [:rf.machine.timer/after-elapsed <delay-key> <epoch>]]
;;
;; back into the parent machine via the late-bound :router/dispatch! hook.
;; Pick-after-transition resolves <delay-key> against the active state
;; path's :after table; epoch-mismatch surfaces as
;; :rf.machine.timer/stale-after, epoch-match drives the transition
;; through the standard cascade.
;;
;; The delay-key is the original :after map key — pos-int? for literal,
;; vector for subscription, fn for fn-form. This preserves stable identity
;; across re-resolution (sub) and across machine re-entries.
;;
;; A per-frame timer table tracks live handles so cancellation (state
;; exit) and subscription-driven re-resolution can clear them. The epoch
;; mechanism backstops correctness; explicit cancellation is an
;; optimisation that promptly releases the host-clock handle.
;;
;; SSR mode (`:platform :server`): `:after-schedule` no-ops scheduling and
;; emits :rf.machine.timer/skipped-on-server in place of /scheduled per
;; Spec 005 §SSR mode.

(defn- after-timer-key [frame-id parent-id invoke-id delay-key]
  [frame-id parent-id (vec invoke-id) delay-key])

(defn- classify-delay-source [delay-key]
  (cond
    (number? delay-key)  :literal
    (vector? delay-key)  :sub
    (fn? delay-key)      :fn
    :else                :literal))

(defn- resolve-delay-ms
  "Resolve an :after map key to a positive-integer ms delay. For pos-int?
  literal: returns the value. For subscription vector: subscribes via the
  late-bound subscribe-value hook and uses the resolved value. For fn:
  invokes (f snapshot) once.

  Returns [resolved-ms reaction-or-nil]. The reaction is non-nil only for
  subscription-vector delays; the caller installs an add-watch on it to
  trigger re-resolution. Subscription resolution uses
  `:subs/subscribe` / `:subs/subscribe-value` / `:subs/unsubscribe` if
  available; if not, falls back to nil ms (treated as 0)."
  [frame-id delay-key snapshot]
  (cond
    (number? delay-key)
    [delay-key nil]

    (fn? delay-key)
    (let [v (try (delay-key snapshot)
                 (catch #?(:clj Throwable :cljs :default) _ nil))]
      [v nil])

    (vector? delay-key)
    ;; subscribe to keep the reaction live; caller will add-watch for
    ;; change-detection then unsubscribe on cancellation.
    (let [reaction (subs/subscribe frame-id delay-key)
          v        (when reaction
                     (try @reaction
                          (catch #?(:clj Throwable :cljs :default) _ nil)))]
      [v reaction])

    :else
    [nil nil]))

(declare schedule-after-timer!)

(defn- cancel-after-timer-entry!
  "Cancel and clear a single :after timer-table entry. Idempotent."
  [k]
  (when-let [entry (get @after-timers k)]
    (when-let [h (:handle entry)]
      (try (interop/clear-timeout! h)
           (catch #?(:clj Throwable :cljs :default) _ nil)))
    (when (and (:reaction entry) (:sub-watcher-key entry))
      (try (remove-watch (:reaction entry) (:sub-watcher-key entry))
           (catch #?(:clj Throwable :cljs :default) _ nil))
      (let [[frame-id _ _ delay-key] k]
        (when (and (vector? delay-key) frame-id)
          (try (subs/unsubscribe frame-id delay-key)
               (catch #?(:clj Throwable :cljs :default) _ nil)))))
    (swap! after-timers dissoc k)))

(defn- on-sub-changed!
  "Watch callback invoked when a subscription-vector delay's value
  changes. Per Spec 005 §Dynamic delay re-resolution: cancel the prior
  in-flight timer, emit :rf.machine.timer/cancelled-on-resolution, and
  reschedule a fresh timer at the new resolution time. Epoch is
  unchanged (the snapshot's :state hasn't moved); we read it back from
  the live snapshot at reschedule-time so a concurrent state change is
  caught by the epoch invariant when the new timer fires."
  [frame-id parent-id invoke-id delay-key state old-v new-v]
  (when-not (= old-v new-v)
    (let [k (after-timer-key frame-id parent-id invoke-id delay-key)
          prior-entry (get @after-timers k)
          prior-ms    (:resolved-ms prior-entry)]
      (trace/emit! :machine :rf.machine.timer/cancelled-on-resolution
                   {:machine-id parent-id
                    :state      state
                    :delay      prior-ms
                    :reason     :sub-changed
                    :sub-id     (first delay-key)})
      (cancel-after-timer-entry! k)
      ;; Reschedule. Read the current snapshot to pick up the live
      ;; epoch; if the snapshot is gone (frame destroyed) or has moved
      ;; past the :after-bearing state, the rescheduled timer will fire
      ;; stale and be safely suppressed by the epoch check.
      (when-let [container (frame/get-frame-db frame-id)]
        (let [db   (adapter/read-container container)
              snap (get-in db [:rf/machines parent-id])
              ;; Only reschedule if the active state path still includes
              ;; the :after-bearing prefix.
              active (when snap (state-path (:state snap)))
              still-here? (and active
                                (= (vec invoke-id)
                                   (vec (take (count invoke-id) active))))]
          (when still-here?
            (let [epoch (or (get-in snap [:data :rf/after-epoch]) 0)]
              ;; Sub-driven re-resolution emits a fresh :scheduled trace
              ;; (paired with the :cancelled-on-resolution above).
              (schedule-after-timer! frame-id parent-id invoke-id state
                                      delay-key epoch false snap
                                      {:emit-scheduled-trace? true}))))))))

(defn- schedule-after-timer!
  "Internal helper: resolve the delay, install the host-clock timer, and
  (for sub-vec delays) install the change-watcher. The
  :rf.machine.timer/scheduled (or /skipped-on-server) trace is emitted by
  the pure-code side (apply-transition-once) at machine-transition time;
  this fn emits a fresh /scheduled (paired with :cancelled-on-resolution)
  only when called from a subscription-change watcher.

  Idempotent against the timer-table key — cancels any prior entry
  before installing the new one."
  [frame-id parent-id invoke-id state delay-key epoch server? snapshot
   {:keys [emit-scheduled-trace?]}]
  (let [delay-source (classify-delay-source delay-key)
        k            (after-timer-key frame-id parent-id invoke-id delay-key)]
    (cancel-after-timer-entry! k)
    (cond
      server?
      ;; Pure-side already emitted :skipped-on-server; no-op here.
      nil

      :else
      (let [[resolved-ms reaction] (resolve-delay-ms frame-id delay-key snapshot)]
        (cond
          (or (not (number? resolved-ms))
              (not (pos? resolved-ms)))
          ;; Bad delay resolution — emit advisory and skip.
          (trace/emit! :machine :rf.warning/no-clock-configured
                       {:machine-id   parent-id
                        :state        state
                        :delay-key    delay-key
                        :delay-source delay-source
                        :recovery     :skipped})

          :else
          (let [_ (when emit-scheduled-trace?
                    (trace/emit! :machine :rf.machine.timer/scheduled
                                 (cond-> {:machine-id   parent-id
                                          :state        state
                                          :delay        resolved-ms
                                          :delay-source delay-source
                                          :epoch        epoch}
                                   (= :sub delay-source)
                                   (assoc :sub-id (first delay-key)))))
                handle
                (interop/set-timeout!
                  (fn []
                    (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
                      (dispatch! [parent-id [:rf.machine.timer/after-elapsed
                                              delay-key epoch]]
                                 {:frame frame-id})))
                  resolved-ms)
                watch-key (when (= :sub delay-source)
                            [::after-watch frame-id parent-id invoke-id delay-key])]
            (when (and reaction watch-key)
              (try
                (add-watch reaction watch-key
                           (fn [_ _ old-v new-v]
                             (on-sub-changed! frame-id parent-id invoke-id
                                              delay-key state old-v new-v)))
                (catch #?(:clj Throwable :cljs :default) _ nil)))
            (swap! after-timers assoc k
                   {:handle          handle
                    :reaction        reaction
                    :sub-watcher-key watch-key
                    :resolved-ms     resolved-ms
                    :epoch           epoch
                    :state           state
                    :delay-source    delay-source})
            handle))))))

(defn after-schedule-fx
  "fx handler for `:rf.machine/after-schedule`. Per Spec 005 §Delayed
  `:after` transitions and rf2-3y3y, on entry to an :after-bearing state
  node the runtime emits one of these per :after entry. The handler
  resolves the delay (literal pos-int? / subscription vector / fn),
  schedules a real wall-clock timer via `interop/set-timeout!`, and (for
  subscription delays) installs an add-watch that triggers
  cancel-and-reschedule on sub-value change.

  The synthetic event dispatched on expiry is

      [<parent-id> [:rf.machine.timer/after-elapsed <delay-key> <epoch>]]

  which routes through pick-after-transition's epoch check and (on match)
  through the standard transition cascade.

  No-op under `:platform :server` (per Spec 005 §SSR mode); emits
  :rf.machine.timer/skipped-on-server in place of /scheduled."
  [{:keys [frame]} args]
  (let [frame-id   (or frame :rf/default)
        parent-id  (:rf/parent-id args)
        invoke-id  (:rf/invoke-id args)
        state      (:state args)
        delay-key  (:delay-key args)
        epoch      (:epoch args)
        server?    (boolean (:server? args))
        snapshot   (when-let [container (frame/get-frame-db frame-id)]
                     (get-in (adapter/read-container container)
                             [:rf/machines parent-id]))]
    ;; Initial state-entry scheduling — the :scheduled trace was already
    ;; emitted synchronously by apply-transition-once (the pure side). For
    ;; sub-vec delays, the fx layer's resolution may yield a different
    ;; :delay value than the pure-side reported as :delay-key; if so, the
    ;; sub-changed watcher emits a follow-up /scheduled with the resolved
    ;; ms once the subscription's first-read completes — but for the
    ;; common case where the sub's value is stable across the schedule
    ;; window the pure-side trace stands.
    (schedule-after-timer! frame-id parent-id invoke-id state
                            delay-key epoch server? snapshot
                            {:emit-scheduled-trace? false})
    nil))

(defn after-cancel-fx
  "fx handler for `:rf.machine/after-cancel`. Per rf2-3y3y, emitted on
  exit from an :after-bearing state node to release the host-clock timer
  handles and any subscription watchers attached to the prior visit's
  timers. The epoch-mismatch invariant backstops correctness if a timer
  fires before this fx runs; this handler is the fast-path that prevents
  zombie watchers and releases timer slots promptly."
  [{:keys [frame]} args]
  (let [frame-id  (or frame :rf/default)
        parent-id (:rf/parent-id args)
        invoke-id (vec (:rf/invoke-id args))]
    (doseq [[k _entry] @after-timers
            :when (and (= frame-id  (nth k 0))
                       (= parent-id (nth k 1))
                       (= invoke-id (nth k 2)))]
      (cancel-after-timer-entry! k))
    nil))

;; ---- query API for system-id (Spec 005 §Named addressing via :system-id) -

(defn machine-by-system-id
  "Look up the spawned-machine id currently bound to `system-id` in the
  active frame's `[:rf/system-ids]` reverse index, or nil. The `frame`
  arg defaults to the current frame (per `frame/current-frame`); pass
  an explicit frame-id for cross-frame lookups.

  Per Spec 005 §Named addressing via :system-id."
  ([system-id]
   (machine-by-system-id system-id (frame/current-frame)))
  ([system-id frame-id]
   (when-let [container (frame/get-frame-db frame-id)]
     (get-in (adapter/read-container container) [:rf/system-ids system-id]))))

(fx/reg-fx :rf.machine/spawn             spawn-fx)
(fx/reg-fx :rf.machine/destroy           destroy-machine-fx)
(fx/reg-fx :rf.machine/invoke-all-init   invoke-all-init-fx)
(fx/reg-fx :rf.machine/after-schedule    after-schedule-fx)
(fx/reg-fx :rf.machine/after-cancel      after-cancel-fx)

;; ---- late-bind hook registration ------------------------------------------
;;
;; Per rf2-xbtj the machines surface ships in
;; `day8/re-frame-2-machines`. `re-frame.core` and `re-frame.test-support`
;; MUST NOT `:require [re-frame.machines]` — the artefact is optional, and
;; a static require would force every consumer of the core artefact to
;; drag the namespace's `:rf/machine` sub registration (and the spawn
;; counter, the `:rf.machine/spawn` / `:rf.machine/destroy` fx handlers, the
;; transition machinery) onto the classpath. The public-API re-exports
;; (`reg-machine*`, `create-machine-handler`, `machine-transition`,
;; `machines`, `machine-meta`) and the test-support reset helper are
;; published through the late-bind table; consumers without the
;; machines artefact see the hooks unregistered and the surface
;; throws / returns safe defaults cleanly.
;;
;; Per Spec 005 §reg-machine vs reg-machine* (rf2-8bp3): the late-bind
;; hook key is `:machines/reg-machine` (the legacy slot name) and points
;; at `reg-machine*` — the plain-fn surface. The `reg-machine` macro at
;; the `re-frame.core` boundary is import-time-only (CLJS macroexpansion
;; runs before ns-load); the runtime always reaches through this hook to
;; the plain-fn surface.

(late-bind/set-fn! :machines/reg-machine            reg-machine*)
(late-bind/set-fn! :machines/create-machine-handler create-machine-handler)
(late-bind/set-fn! :machines/machine-transition     machine-transition)
(late-bind/set-fn! :machines/machines               machines)
(late-bind/set-fn! :machines/machine-meta           machine-meta)
(late-bind/set-fn! :machines/machine-by-system-id   machine-by-system-id)
(late-bind/set-fn! :machines/reset-counters!        reset-counters!)
(late-bind/set-fn! :machines/spawn-fx               spawn-fx)
(late-bind/set-fn! :machines/destroy-machine-fx     destroy-machine-fx)
(late-bind/set-fn! :machines/invoke-all-init-fx     invoke-all-init-fx)
(late-bind/set-fn! :machines/after-schedule-fx      after-schedule-fx)
(late-bind/set-fn! :machines/after-cancel-fx        after-cancel-fx)

;; rf2-ijm7 — load-order resilience for the `:rf.http/managed` machine-shape
;; wrapper. The wrapper is registered by re-frame.http-managed via the
;; `:machines/reg-machine` hook published above; but if http-managed loaded
;; BEFORE this namespace (the load-order is determined by the consuming app's
;; require graph, not by either artefact), the wrapper's bottom-of-ns call
;; found a nil hook and skipped its registration. We close that race by
;; re-invoking the http artefact's `:http/register-managed-machine!` hook
;; from here — if http-managed is on the classpath the hook is set and the
;; wrapper registers now; if it isn't, the hook is nil and this is a no-op.
(when-let [reg-fn (late-bind/get-fn :http/register-managed-machine!)]
  (reg-fn))
