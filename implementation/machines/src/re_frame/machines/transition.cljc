(ns re-frame.machines.transition
  "Pure machine-transition engine. Per Spec 005 §State machines.

  This namespace is the JVM- and CLJS-runnable core of the machine
  grammar — transition resolution along hierarchical paths, the
  exit/action/entry cascade, the macrostep drain semantics for `:raise`
  and `:always`, and the parallel-region broadcast layer. Everything
  here is a pure function over the [machine snapshot event] triple —
  no module-level mutable state. Per rf2-gr8q the declarative-`:spawn`
  spawn-id allocator lives inside the snapshot under `:rf/spawn-counter`
  (a per-machine-id integer map); the reducer threads the bumped
  counter through the returned snapshot.

  The fx vectors built here name `:rf.machine/spawn`,
  `:rf.machine/destroy`, `:rf.machine/spawn-all-init`,
  `:rf.machine/after-schedule`, and `:rf.machine/after-cancel`; the
  actual fx handlers live in `re-frame.machines.lifecycle-fx.{spawn,
  destroy,registration}` and `re-frame.machines.timer`. This namespace
  stays effect-free so it can be loaded and exercised on the JVM by
  the conformance fixtures (Spec 005 §Conformance fixtures)."
  (:require [clojure.set :as set]
            [re-frame.machines.path-walk :as path-walk]
            [re-frame.machines.result :as result
             #?@(:cljs [:include-macros true])]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

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
;; Per Spec 005 §Guards / §Actions (rf2-grw4i / rf2-v0rrr), the canonical
;; signature for every machine callback is a SINGLE context-map argument:
;;
;;   (fn [{:keys [data event state meta]}] ...)
;;
;; The context map carries every key the slot is meaningfully wired to;
;; user code destructures the ones it needs. Keys present per slot type:
;;
;;   :guard / :action / :entry / :exit  → :data :event :state :meta
;;   :on-done                            → :data :result
;;   :on-spawn                           → :data :id
;;   :after  (delay-fn)                  → :snapshot
;;   :spawn :data (init-fn)              → :snapshot :event
;;
;; Return shapes are slot-specific (Spec 005 §Return shapes):
;;   :guard                                                → boolean
;;   :action / :entry / :exit / :on-done / :spawn :data    → new :data map
;;   :on-spawn / :after                                    → see slot docs
;;     (on-spawn: advisory, nil; after: positive-int ms delay)
;;
;; Uniform input shape: future slot additions extend the ctx keys without
;; expanding the arity-permutation matrix. Destructuring at the call site
;; makes meaningful keys explicit; the runtime delivers them in a single
;; map.

(defn- call-guard
  "Invoke a resolved guard fn against a snapshot + event with the unified
  context-map contract — `(fn [{:keys [data event state meta]}] boolean)`.
  Per Spec 005 §Guards (rf2-grw4i / rf2-v0rrr)."
  [g snapshot event]
  (g {:data  (:data snapshot)
      :event event
      :state (:state snapshot)
      :meta  (:meta snapshot)}))

(defn- call-action
  "Invoke a resolved action fn against a snapshot + event with the unified
  context-map contract — `(fn [{:keys [data event state meta]}] effects)`.
  Per Spec 005 §Actions (rf2-grw4i / rf2-v0rrr)."
  [f snapshot event]
  (f {:data  (:data snapshot)
      :event event
      :state (:state snapshot)
      :meta  (:meta snapshot)}))

;; ---- guard / action evaluation traces -------------------------------------
;;
;; Per Spec 009 §Instrumentation and rf2-2nwfd: every user-declared guard
;; evaluation emits `:rf.machine/guard-evaluated`; every user-declared
;; action invocation emits `:rf.machine/action-ran`. Both traces ride
;; through the standard trace bus, so `*handler-scope*` auto-stamps
;; `:dispatch-id` into `:tags` — downstream cascade-correlation (e.g.
;; Causa's `:rf.causa/machine-transitions-for-focused-event` sub) groups
;; them with the originating event without any explicit threading here.
;;
;; The synthesised `(constantly true)` returned by `resolve-guard` for a
;; nil guard-ref, and the synthesised `(constantly nil)` returned by
;; `resolve-action` for a nil action-ref, are NOT user-declared
;; evaluations — `evaluate-guard` / `run-action` only emit when
;; `guard-ref` / `action-ref` is non-nil.

(defn- evaluate-guard
  "Resolve and invoke a user-declared guard, emitting
  `:rf.machine/guard-evaluated` for cascade-discoverability. Returns the
  guard's boolean outcome. When `guard-ref` is nil the guard is the
  synthesised always-true — skip the trace (it is not a user-declared
  evaluation) and return true."
  [machine guard-ref snapshot event]
  (if (nil? guard-ref)
    true
    (let [g       (resolve-guard machine guard-ref)
          outcome (boolean (call-guard g snapshot event))]
      (trace/emit! :machine :rf.machine/guard-evaluated
                   {:machine-id (or (:rf/parent-id machine) (:id machine))
                    :guard-id   guard-ref
                    :input      {:data  (:data snapshot)
                                 :event event}
                    :outcome    (if outcome :pass :fail)
                    ;; Per rf2-ko8jb: epoch-capture admission requires
                    ;; `:frame`. `(:rf/frame machine)` is stamped by
                    ;; `prepare-machine-ctx` (registration.cljc) before
                    ;; the engine is invoked; nil-safe for pure-function
                    ;; callers (conformance corpus, JVM fixtures).
                    :frame      (:rf/frame machine)})
      outcome)))

;; ---- spawn-id allocator (in-snapshot) -------------------------------------
;;
;; Per Spec 005 §Declarative :spawn (sugar over spawn) and rf2-gr8q: on
;; entry to a :spawn-bearing state the runtime emits a :rf.machine/spawn
;; fx and assigns the spawned actor a deterministic id of the form
;; `<machine-id>#<n>`. The counter lives inside the snapshot under the
;; reserved key `:rf/spawn-counter` — a per-machine-id integer map. This
;; makes `apply-transition-once` an honest pure function: identical
;; (machine snapshot event) triples produce identical [next-snapshot
;; effects] pairs including spawn-id sequencing. Each spawn bumps
;; the snapshot's counter via update-in and the bumped value is the
;; allocated id.
;;
;; `build-initial-snapshot` (in re-frame.machines.parallel — unified per
;; rf2-fgqs4 across the singleton-registration and spawn paths) stamps
;; `{:rf/spawn-counter {}}` on every freshly-registered machine's
;; initial snapshot so the slot is always present for live runtime
;; spawns. Hand-built snapshots (the conformance fixtures) may omit the
;; key — the reducer uses `(fnil inc 0)` so absent slots default to 0.

(defn- format-spawn-id
  "Format a spawned actor id of the form `<machine-id>#<n>` preserving
  any namespace on the machine-id."
  [machine-id n]
  (keyword (namespace machine-id)
           (str (name machine-id) "#" n)))

(defn- allocate-spawned-id
  "Pure allocator. Given a snapshot and the spawned actor's machine-id,
  return `[snap' spawned-id]` where snap' carries the bumped counter at
  `[:rf/spawn-counter <machine-id>]` and spawned-id is
  `<machine-id>#<bumped-n>`. Per rf2-gr8q the counter lives in-snapshot
  so machine-transition is deterministic from its arguments."
  [snap machine-id]
  (let [snap' (update-in snap [:rf/spawn-counter machine-id] (fnil inc 0))
        n     (get-in snap' [:rf/spawn-counter machine-id])]
    [snap' (format-spawn-id machine-id n)]))

;; ---- state-path helpers (hierarchical) ------------------------------------
;;
;; Per Spec 005 §State paths and §Entry/exit cascading along the LCA, the
;; snapshot's :state is a vector path from root to leaf (e.g.
;; [:authenticated :cart :paying]). Flat machines used :state :foo for
;; compactness; we accept both and normalise internally.

(defn state-path
  "Coerce a snapshot's :state — either a keyword or a vector path — into
  a normalised vector path."
  [state]
  (cond
    (vector? state) state
    (keyword? state) [state]
    :else (throw (ex-info ":rf.error/machine-bad-state-form" {:state state}))))

(defn denormalise-state
  "Re-shape a vector path back to the same form as the input snapshot's
  :state. If `original` was a keyword and the path is length-1, return
  the keyword; otherwise return the vector."
  [path original]
  (cond
    (and (keyword? original) (= 1 (count path))) (first path)
    :else (vec path)))

(defn node-at
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

(defn initial-cascade
  "Given a target path landing on a possibly-compound node, descend
  through :initial chain until we reach a leaf. Returns the leaf path."
  [machine path]
  (loop [p path]
    (let [n (node-at machine p)]
      (if (and (map? n) (:initial n) (:states n))
        (recur (conj p (:initial n)))
        p))))

;; ---- :fsm/tags — active-configuration tag union ---------------------------
;;
;; Per Spec 005 §State tags (rf2-ee0d / Nine States Stage 1). A state node
;; may declare `:tags <set-of-keywords>`. The runtime maintains a derived
;; `:tags` slot on the snapshot — the union of every currently-active
;; state's tag set.

(defn- node-tags
  "Return the `:tags` set declared on a state-node body, or `nil` if no
  `:tags` slot is present. Non-set values (e.g. a vector or a single
  keyword) coerce to a set so the union math doesn't care about the
  literal form the author wrote — the schema constrains the canonical
  form (`[:set :keyword]`); coercion here is defensive."
  [node]
  (when-let [t (:tags node)]
    (cond
      (set? t)        t
      (sequential? t) (set t)
      (keyword? t)    #{t}
      :else           nil)))

(defn compute-tags
  "Per Spec 005 §State tags: walk the active configuration for `state`
  and return the union of every active state-node's `:tags` set.
  Returns a set (possibly empty) — never `nil`."
  [machine state]
  (let [path  (state-path state)
        nodes (nodes-along-path machine path)]
    (transduce (keep (fn [[_ n]] (node-tags n))) set/union #{} nodes)))

(defn- commit-tags
  "Stamp the active-configuration tag union onto `snapshot` at `:tags`.
  Per Spec 005 §State tags §Snapshot shape change: the slot is OPTIONAL
  — when the union is empty, the runtime elides the key entirely to
  keep snapshots small for the common (no-tags) case."
  [machine snapshot]
  (let [tags (compute-tags machine (:state snapshot))]
    (if (empty? tags)
      (dissoc snapshot :tags)
      (assoc snapshot :tags tags))))

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

(defn- after-epoch-path
  "Return the path inside the snapshot's `:data` map where the
  `:after`-timer epoch MAP lives for `machine`.

  Per Spec 005 §Delayed `:after` transitions §Hierarchy interaction, the
  epoch is tracked **per scheduling node** — the slot holds a map
  `{<decl-path-vector> <non-negative int>}` rather than a single scalar.
  This is the per-level tracking the normative external contract
  (005 §Hierarchy interaction) requires: a leaf-only sibling transition
  bumps only the leaf's entry, leaving a still-active parent's entry — and
  thus its in-flight `:after` timer — untouched, while a transition that
  exits the parent bumps the parent's entry so its pending timers go
  stale on next firing.

  For flat / compound machines the map lives at `[:data :rf/after-epoch]`.
  Per Spec 005 §Per-region `:always` / `:after` / `:spawn` scoping
  (rf2-l67o / Stage 2): when `machine` is a region of a parallel-region
  parent (signalled by `:rf/region`), the map is region-scoped —
  `[:data :rf/after-epoch-by-region <region-name>]` — so a sibling
  region's transition doesn't invalidate this region's in-flight timers
  via the shared `:data` slot."
  [machine]
  (if-let [rn (:rf/region machine)]
    [:data :rf/after-epoch-by-region rn]
    [:data :rf/after-epoch]))

(defn- node-epoch
  "Read the per-node `:after` epoch for the scheduling node at `decl-path`
  from `snapshot`. Absent nodes read as 0 (a node never entered has no
  in-flight timer; a carried epoch of 0 against an absent node is the
  bootstrap-then-stale shape). `decl-path` is the absolute state path the
  `:after` was declared at (for a region, the path WITHIN the region —
  matching the `:rf/spawn-id` the timer carries)."
  [machine snapshot decl-path]
  (or (get-in snapshot (conj (after-epoch-path machine) (vec decl-path))) 0))

(defn- prefix-of?
  "True iff `pre` is a (possibly equal) prefix of `whole` — i.e. the
  scheduling node at `pre` is still on the active `whole` path."
  [pre whole]
  (and (<= (count pre) (count whole))
       (= (vec pre) (vec (take (count pre) whole)))))

(defn- pick-after-transition
  "Per Spec 005 §Delayed :after transitions §Hierarchy interaction. The
  synthetic event

      [:rf.machine.timer/after-elapsed delay-key carried-epoch carried-decl-path]

  arrives. `carried-decl-path` (the scheduling node's absolute path) is
  carried by the runtime so the staleness check is per scheduling node —
  the per-level tracking the normative external contract (005 §Hierarchy
  interaction) requires. Legacy 3-element events (no decl-path) fall back
  to a leaf→root walk for the delay-key, resolving against the matched
  node's per-path epoch — sufficient when delay-keys do not collide
  across hierarchy levels.

  A timer is **live** iff its scheduling node is still on the active path
  (its `carried-decl-path` is a prefix of the current path) AND the
  carried epoch equals that node's current per-path epoch. A leaf-only
  sibling transition under a parent leaves the parent's per-path epoch
  untouched, so the parent's in-flight `:after` timer stays live; a
  transition that exits the parent bumps the parent's per-path epoch, so
  the parent's pending timers observe a mismatch and drop as stale.

  Returns one of:
    nil — no matching :after entry; benign (timer carried from a state
          we've exited and re-entered without that delay-key).
    {:stale? true ...} — node exited, or per-node epoch mismatch
          (re-entry); caller emits :rf.machine.timer/stale-after.
    {:transition t :decl-path p :delay :epoch} — guard pass; caller
          fires the transition through the standard cascade and emits
          :rf.machine.timer/fired with :fired? true.
    {:guard-suppressed? true :state :delay :epoch} — guard returned
          false; caller emits :rf.machine.timer/fired with :fired? false
          and other in-flight :after timers continue per Spec 005
          §Multi-stage interaction with :guard."
  [machine path event snapshot]
  (let [[_ delay-key carried-epoch raw-carried-decl-path] event
        region        (:rf/region machine)
        ;; Per Spec 005 §Per-region :after scoping: the runtime carries a
        ;; region-name-prefixed decl-path (`prefix-region-invoke-id`) for
        ;; timers scheduled inside a parallel region. Within a region's
        ;; `pick-after-transition` the active path is in-region, so strip
        ;; the region-name head. A carried path naming a DIFFERENT region
        ;; is not this region's timer — decline (the broadcast routes the
        ;; firing to the bearing region only).
        decline-region?  (and region
                              raw-carried-decl-path
                              (not= region (first raw-carried-decl-path)))
        carried-decl-path (cond
                            (nil? raw-carried-decl-path) nil
                            region (vec (rest raw-carried-decl-path))
                            :else  (vec raw-carried-decl-path))
        resolve-hit
        (fn [prefix tspec]
          (let [guard-ref (:guard tspec)
                pass?     (evaluate-guard machine guard-ref snapshot event)]
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
               :epoch             carried-epoch})))]
    (cond
      decline-region? nil

      (some? carried-decl-path)
      ;; Per-node path supplied by the runtime — route directly to the
      ;; scheduling node so colliding delay-keys across hierarchy levels
      ;; resolve unambiguously.
      (let [decl-path carried-decl-path
            cur-epoch (node-epoch machine snapshot decl-path)
            node      (when (prefix-of? decl-path path)
                        (node-at machine decl-path))
            t         (when node (get-in node [:after delay-key]))]
        (cond
          ;; Node still active and its per-path epoch matches the carried
          ;; epoch → the timer is live; resolve its transition + guard.
          (and t (= carried-epoch cur-epoch))
          (resolve-hit decl-path (if (keyword? t) {:target t} t))

          ;; Node still active but the per-path epoch advanced (a re-entry
          ;; scheduled a fresh timer) → this in-flight timer is stale.
          ;; Likewise when the node has been exited (no longer on the
          ;; active path) → stale.
          :else
          {:stale?          true
           :state           (last decl-path)
           :delay           delay-key
           :scheduled-epoch carried-epoch
           :current-epoch   cur-epoch}))

      ;; Legacy 3-element event — resolve via the leaf→root walk.
      :else
      (let [hit
            (path-walk/walk-path-leaf-to-root
              machine path
              (fn [prefix n]
                (when-let [t (get-in n [:after delay-key])]
                  (let [cur-epoch (node-epoch machine snapshot prefix)]
                    (if (= carried-epoch cur-epoch)
                      (resolve-hit prefix (if (keyword? t) {:target t} t))
                      {:stale?          true
                       :state           (last prefix)
                       :delay           delay-key
                       :scheduled-epoch carried-epoch
                       :current-epoch   cur-epoch})))))]
        (cond
          hit    hit
          ;; No `:after` table matched along any level of the path — the
          ;; timer carried in from a state the machine has since exited.
          ;; Surface it as stale so the lifecycle emits
          ;; `:rf.machine.timer/stale-after`.
          :else  {:stale?          true
                  :state           (last path)
                  :delay           delay-key
                  :scheduled-epoch carried-epoch
                  :current-epoch   nil})))))

(defn- pick-transition
  "Walk path leaf→root looking for a transition that matches event-id and
  whose guard passes. Per Spec 005 §Transition resolution — deepest-wins
  with parent fallthrough (the rule named in `path-walk/walk-path-leaf-
  to-root`).

  Special-cases the synthetic :rf.machine.timer/after-elapsed event by
  delegating to pick-after-transition."
  [machine path event snapshot]
  (let [event-id (first event)]
    (if (= :rf.machine.timer/after-elapsed event-id)
      (pick-after-transition machine path event snapshot)
      (path-walk/walk-path-leaf-to-root
        machine path
        (fn [prefix n]
          (let [cands (normalise-on-clause
                        (or (get-in n [:on event-id])
                            (get-in n [:on :*])))
                hit   (some (fn [t]
                              (when (evaluate-guard machine (:guard t) snapshot event)
                                t))
                            cands)]
            (when hit
              {:transition hit :decl-path prefix})))))))

(defn- target-path
  "Compute the absolute target path for a transition. Per Spec 005:
   - keyword target → sibling at decl-path's level (replace last element).
   - vector target → absolute path from root.
   - nil target (internal transition) → nil; the caller wraps the call
     in `some->>` so the nil short-circuits the initial-cascade descent.

  Per rf2-adwxh the explicit `(nil? target) nil` arm is dropped — when
  target is neither vector nor keyword, the `cond` falls through to
  nil, which is the documented internal-transition contract."
  [decl-path _source-path target]
  (cond
    (vector? target)     target
    (keyword? target)
    (let [parent (vec (drop-last decl-path))]
      (conj parent target))))

(defn- common-prefix-length [a b]
  (count (take-while true? (map = a b))))

;; When an action throws, `run-action` returns a `result/fail` carrying
;; `{:action-ref :exception}`. `collect-actions` propagates the failure;
;; `apply-transition-once` / `machine-transition-single` enrich it with
;; transition-level context; the outer event handler converts it into a
;; no-`:db` return so the cascade halts without committing a snapshot.
;; Per Spec 005 §Errors and Cross-Spec-Interactions §11 — Machine action
;; throws.

(defn- run-action
  "Run one action ref and return either a plain effects map (success) or a
  `result/fail` Result (the action threw). Successful actions may return
  `nil` (treated as `{}`).

  Per rf2-2nwfd: every user-declared action invocation emits
  `:rf.machine/action-ran` with the action-ref, the canonical input
  `{:data :event}`, and an outcome — the action's return value on
  success (or `:ok` when the action returned nil), or
  `:rf.error/action-threw` on the exceptional path. The synthesised
  no-op for `nil` action-ref is not user-declared — skip the trace."
  [machine snap action-ref event]
  (if action-ref
    (let [f         (resolve-action machine action-ref)
          parent-id (or (:rf/parent-id machine) (:id machine))
          ;; Per rf2-ko8jb: epoch-capture admission requires `:frame`.
          frame-id  (:rf/frame machine)]
      (try
        (let [r (call-action f snap event)]
          (trace/emit! :machine :rf.machine/action-ran
                       {:machine-id parent-id
                        :action-id  action-ref
                        :input      {:data  (:data snap)
                                     :event event}
                        :outcome    (if (nil? r) :ok r)
                        :frame      frame-id})
          (or r {}))
        (catch #?(:clj Throwable :cljs :default) e
          (trace/emit! :machine :rf.machine/action-ran
                       {:machine-id parent-id
                        :action-id  action-ref
                        :input      {:data  (:data snap)
                                     :event event}
                        :outcome    :rf.error/action-threw
                        :exception  e
                        :frame      frame-id})
          (result/fail {:action-ref action-ref
                        :exception  e}))))
    {}))

(defn- collect-actions
  "Walk action-refs in order, calling each with snap+event and threading
  the resulting :data updates forward (so each action sees the previous
  one's data). Returns a `result/ok` carrying `[final-snapshot fx-vec]`,
  or the `result/fail` Result the first throwing action produced — per
  Spec 005 §Errors, the cascade halts on the first throw and the
  snapshot does not commit."
  [machine snap event action-refs]
  (reduce
    (fn [acc aref]
      (if aref
        (result/with-ok [snap fx] acc
          (let [r (run-action machine snap aref event)]
            (if (result/fail? r)
              (reduced r)
              (let [new-data (cond-> (:data snap)
                               (contains? r :data) (merge (:data r)))
                    new-snap (assoc snap :data new-data)
                    new-fx   (vec (concat fx (or (:fx r) [])))]
                (result/ok new-snap new-fx)))))
        acc))
    (result/ok snap [])
    action-refs))

;; ---- apply-transition-once helpers (extracted per rf2-g1s1) ---------------
;;
;; Each helper builds one fx-vector slice that flows out of apply-transition-
;; once. The slices compose in order (after-cancel, destroy, spawn, after-
;; schedule) because the runtime semantics require timers to cancel before
;; spawned children tear down, children to tear down before new children
;; spawn, and new timers to schedule last (so the freshly-bumped epoch is
;; what's stamped on them).

(defn- materialise-data
  "Resolve a :spawn spec's `:data` slot. Per Spec 005 §Declarative `:spawn`
  and rf2-h131, `:data` admits a fn form `(fn [{:keys [snapshot event]}]
  data)` so the spawn's initial data can depend on the parent snapshot at
  the moment of entry. Per rf2-grw4i / rf2-v0rrr the fn is invoked with
  the unified context-map shape. The fn runs against the post-action
  snapshot (any :action :data writes are visible). Returns
  `[::ok-data <materialised-data>]` on success, or a `result/fail` Result
  carrying `{:exception <e>}` if the fn threw — caller stamps
  `:action-ref` / `:spawn-id` / `:child-id` onto the Result before
  propagating."
  [d snap event]
  (if (fn? d)
    (try
      [::ok-data (d {:snapshot snap :event event})]
      (catch #?(:clj Throwable :cljs :default) e
        (result/fail {:exception e})))
    [::ok-data d]))

(defn- build-after-fx
  "Per Spec 005 §SSR mode and Cross-Spec-Interactions §4 (Machines × SSR):
  `:after` is a no-op under `:platform :server`. Per rf2-3y3y: emit the
  canonical `:rf.machine.timer/scheduled` (or /skipped-on-server) trace
  synchronously here AND emit `:rf.machine/after-schedule` fx; the fx
  handler resolves the delay (literal / sub-vec / fn) and installs the
  host-clock timer.

  Walks the entered pairs (each `[prefix-path node]`) looking for `:after`
  declarations. The `:scheduled` trace fires per delay-key; the fx carries
  the same key for the fx-side resolution."
  [machine entered-pairs internal? snap-final]
  (when-not internal?
    (let [parent-id (or (:rf/parent-id machine) :rf/transition-pure)
          server?   (= :server (:rf/platform machine))
          ;; Per rf2-ko8jb: epoch-capture admission requires `:frame`.
          frame-id  (:rf/frame machine)]
      (vec
        (mapcat
          (fn [[prefix n]]
            (when-let [after-map (:after n)]
              ;; Per Spec 005 §Hierarchy interaction: each scheduling node
              ;; carries ITS OWN per-path epoch (just bumped in commit-
              ;; snapshot), so a parent and a child entered in the same
              ;; cascade get independent epochs and the synthetic event
              ;; carries the decl-path for unambiguous staleness routing.
              (let [leaf-state (last prefix)
                    epoch      (node-epoch machine snap-final prefix)]
                (mapv
                  (fn [[delay-key _target]]
                    (let [delay-source (cond
                                         (number? delay-key) :literal
                                         (vector? delay-key) :sub
                                         (fn? delay-key)     :fn
                                         :else               :literal)
                          ;; Pure-context delay value: literal keys ARE the
                          ;; resolved ms; sub-vec / fn are resolved at the fx
                          ;; layer (which emits a fresh /scheduled with the
                          ;; actual resolved-ms once it has frame access).
                          ms-tag       delay-key]
                      (if server?
                        (trace/emit! :machine :rf.machine.timer/skipped-on-server
                                     (cond-> {:machine-id   parent-id
                                              :state        leaf-state
                                              :delay        ms-tag
                                              :delay-source delay-source
                                              :epoch        epoch
                                              :platform     :server
                                              :frame        frame-id
                                              :recovery     :skipped}
                                       (= :sub delay-source)
                                       (assoc :sub-id (first delay-key))))
                        (trace/emit! :machine :rf.machine.timer/scheduled
                                     (cond-> {:machine-id   parent-id
                                              :state        leaf-state
                                              :delay        ms-tag
                                              :delay-source delay-source
                                              :epoch        epoch
                                              :frame        frame-id}
                                       (= :sub delay-source)
                                       (assoc :sub-id (first delay-key))))))
                    [:rf.machine/after-schedule
                     {:rf/parent-id parent-id
                      :rf/spawn-id (vec prefix)
                      :state        leaf-state
                      :delay-key    delay-key
                      :epoch        epoch
                      :server?      server?}])
                  after-map))))
          entered-pairs)))))

(defn- build-after-cancel-fx
  "Per rf2-3y3y: when exiting an `:after`-bearing state node, emit
  `:rf.machine/after-cancel` fx so the runtime tears down any pending
  wall-clock timer (and watcher, for sub-vec delays). The epoch advance
  backstops correctness; explicit cancellation releases the timer handle
  promptly and avoids zombie sub watchers across state re-entries."
  [parent-id exited-pairs internal?]
  (when-not internal?
    (vec
      (for [[prefix n] exited-pairs
            :when (:after n)]
        [:rf.machine/after-cancel
         {:rf/parent-id parent-id
          :rf/spawn-id (vec prefix)}]))))

(defn- build-destroy-fx
  "Per Spec 005 §Declarative `:spawn` and rf2-t07u
  (Option A revised): nodes being EXITED with `:spawn` emit
  `:rf.machine/destroy` carrying `{:rf/parent-id ... :rf/spawn-id ...}`
  so the destroy-machine fx handler resolves the live actor id from the
  runtime-owned `[:rf/spawned <parent-id> <invoke-id>]` slot in app-db.

  Per Spec 005 §Spawn-and-join via `:spawn-all` (rf2-6vmw): on exit, tear
  down EVERY child the parent spawned plus the join-state slot. The
  destroy-fx handler reads the map at `[:rf/spawned <parent> <invoke-id>]`
  and iterates `:children` to destroy each, then clears the slot."
  [parent-id exited-pairs internal?]
  (when-not internal?
    (vec
      (mapcat
        (fn [[prefix n]]
          (cond
            (:spawn n)
            [[:rf.machine/destroy {:rf/parent-id parent-id
                                   :rf/spawn-id (vec prefix)}]]
            (:spawn-all n)
            [[:rf.machine/destroy {:rf/parent-id  parent-id
                                   :rf/spawn-id  (vec prefix)
                                   :rf/spawn-all true}]]
            :else nil))
        exited-pairs))))

;; ---- spawn primitive: shared by :spawn and :spawn-all per-child ----------
;;
;; Per Spec 005 §Spawn-and-join via :spawn-all, `:spawn-all` is "spawn-
;; and-join sugar over N parallel :spawn's plus a join condition". The
;; impl mirrors the concept: both handlers compose `allocate-one` (id
;; allocation), `spawn-one` (`:data` materialisation + spawn-fx build),
;; and `apply-on-spawn` (advisory callback). The mode-specific spawn-args
;; wiring is the only delta — a small `args-builder` closure per mode.

(defn- allocate-one
  "Allocate one spawned-id from `inv-spec`'s `:machine-id` against `snap`'s
  in-snapshot counter (rf2-gr8q). When `inv-spec` carries an explicit
  `:spawn-id` literal (per-state singleton) the counter is NOT bumped.
  Returns `[snap' spawned-id]`."
  [snap inv-spec]
  (if-let [explicit (:spawn-id inv-spec)]
    [snap explicit]
    (allocate-spawned-id snap (:machine-id inv-spec))))

(defn- apply-on-spawn
  "Run `inv-spec`'s `:on-spawn` advisory callback against `snap`'s `:data`.
  Per Spec 005 §Declarative `:spawn` (rf2-grw4i / rf2-v0rrr), the signature
  is `(fn [{:keys [data id]}] _)` — context-map input, advisory return
  (any return value is ignored). Per rf2-t07u (Option A revised) the
  runtime tracks the spawn-id at `[:rf/spawned parent-id invoke-id]`;
  `:on-spawn` is purely observational — callers needing snapshot-level
  side effects emit `[:rf.machine/update-snapshot ...]` from a regular
  `:action` instead."
  [machine snap inv-spec spawned-id]
  (when-let [f (let [aref (:on-spawn inv-spec)]
                 (when aref
                   (or (chase-ref (:on-spawn-actions machine) aref)
                       (chase-ref (:actions machine) aref))))]
    (f {:data (:data snap) :id spawned-id}))
  snap)

(defn- spawn-one
  "Single-spawn primitive shared by `:spawn` and `:spawn-all` per-child.
  Materialises any `:data` fn-form against `mat-snap` + `event` (Spec 005
  §Spec-spec keys / rf2-h131); on failure returns a `result/fail` Result
  stamped with `failure-extra`. On success builds the spawn-args via
  `args-builder` (mode-specific wiring of `:rf/parent-id` /
  `:rf/spawn-id` / `:rf/spawn-all-id` keys) and returns a `result/ok`
  Result carrying the single-element `[[:rf.machine/spawn args]]` fx vec.

  `:on-spawn` is intentionally NOT invoked here — the caller threads it
  separately because `:spawn-all`'s on-spawn callbacks thread `:data`
  writes across siblings."
  [inv-spec mat-snap event spawned-id args-builder failure-extra]
  (let [mat-result (if (contains? inv-spec :data)
                     (materialise-data (:data inv-spec) mat-snap event)
                     [::ok-data nil])]
    (if (result/fail? mat-result)
      (result/fail-with mat-result failure-extra)
      (let [mat-data   (second mat-result)
            inv-spec'  (if (contains? inv-spec :data)
                         (assoc inv-spec :data mat-data)
                         inv-spec)
            spawn-args (args-builder inv-spec' spawned-id)]
        (result/ok spawn-args [[:rf.machine/spawn spawn-args]])))))

;; ---- :spawn / :spawn-all spawn reducers ----------------------------------

(defn- handle-invoke-spawn
  "Handle the `:spawn` branch of the spawn reducer in
  `apply-transition-once`. Allocates one spawned-id, delegates the
  `:data` materialisation and spawn-args assembly to `spawn-one`, then
  runs the `:on-spawn` advisory callback.

  Returns `[snap-after acc-fx']` for the reducer, or a `reduced` wrapper
  around a `result/fail` Result (stamped with
  `:action-ref :rf.spawn/data-fn` and `:spawn-id`) on `:data` failure."
  [machine parent-id s acc-fx prefix n event]
  (let [inv          (:spawn n)
        invoke-id    (vec prefix)
        [s-alloc id] (allocate-one s inv)
        args-builder (fn [inv' spawned-id]
                       (-> inv'
                           (assoc :id-prefix     (:machine-id inv'))
                           (assoc :rf/spawned-id spawned-id)
                           (assoc :rf/parent-id  parent-id)
                           (assoc :rf/spawn-id  invoke-id)))
        spawn-r      (spawn-one inv s-alloc event id args-builder
                                {:action-ref :rf.spawn/data-fn
                                 :spawn-id  invoke-id})]
    (if (result/fail? spawn-r)
      (reduced spawn-r)
      (let [spawn-fx (result/fx spawn-r)
            s'       (apply-on-spawn machine s-alloc inv id)]
        [s' (into acc-fx spawn-fx)]))))

(defn- handle-invoke-all-spawn
  "Handle the `:spawn-all` branch of the spawn reducer in
  `apply-transition-once`. Per Spec 005 §Spawn-and-join via `:spawn-all`
  (rf2-6vmw), `:spawn-all` is spawn-and-join sugar over N parallel
  `:spawn`'s plus a join condition. The implementation mirrors the
  concept:

   1. Allocate one spawned-id per child up-front (thread the snapshot's
      counter through children in declaration order).
   2. Build the join-state seed map and the `:rf.machine/spawn-all-init`
      fx that seeds `[:rf/spawned <parent> <invoke-id>]` in app-db.
   3. For each child, delegate to `spawn-one` to materialise `:data` and
      build its `:rf.machine/spawn` fx (short-circuits on the first
      child's `:data` failure).
   4. Run each child's `:on-spawn` advisory callback in declaration order,
      threading `:data` writes across siblings.

  Returns `[snap-after acc-fx']` for the reducer, or a `reduced` wrapper
  around a `result/fail` Result (stamped with
  `:action-ref :rf.spawn-all/data-fn`, `:spawn-id`, and the failing
  `:child-id`) on `:data` failure."
  [machine parent-id s acc-fx prefix n event]
  (let [inv-all   (:spawn-all n)
        children  (:children inv-all)
        invoke-id (vec prefix)
        ;; (1) Allocate per-child ids deterministically; thread the snapshot.
        [s-alloc children-with-ids]
        (reduce
          (fn [[snap acc] child]
            (let [[snap' id] (allocate-one snap child)]
              [snap' (conj acc (assoc child :rf/spawned-id id))]))
          [s []]
          children)
        ;; (2) Seed the join state with the allocated ids.
        children-map (into {} (map (juxt :id :rf/spawned-id)) children-with-ids)
        join-state   {:children  children-map
                      :done      #{}
                      :failed    #{}
                      :resolved? false
                      :spec      inv-all
                      :spawn-id invoke-id}
        init-fx      [:rf.machine/spawn-all-init
                      {:rf/parent-id parent-id
                       :rf/spawn-id invoke-id
                       :join-state   join-state}]
        ;; (3) Materialise + build spawn fxs per child via `spawn-one`.
        spawn-fxs-r
        (reduce
          (fn [acc child]
            (let [args-builder
                  (fn [child' spawned-id]
                    (-> child'
                        (dissoc :id)
                        (assoc :id-prefix              (:machine-id child'))
                        (assoc :rf/spawned-id           spawned-id)
                        (assoc :rf/parent-id            parent-id)
                        (assoc :rf/spawn-all-id        invoke-id)
                        (assoc :rf/spawn-all-child-id  (:id child))))
                  r (spawn-one child s-alloc event
                               (:rf/spawned-id child)
                               args-builder
                               {:action-ref :rf.spawn-all/data-fn
                                :spawn-id  invoke-id
                                :child-id   (:id child)})]
              (if (result/fail? r)
                (reduced r)
                (into acc (result/fx r)))))
          []
          children-with-ids)]
    (if (result/fail? spawn-fxs-r)
      (reduced spawn-fxs-r)
      ;; (4) Thread :on-spawn advisory callbacks across siblings.
      (let [s' (reduce
                 (fn [snap child]
                   (apply-on-spawn machine snap child (:rf/spawned-id child)))
                 s-alloc
                 children-with-ids)]
        [s' (-> acc-fx (conj init-fx) (into spawn-fxs-r))]))))

(defn final-state-node?
  "Per Spec 005 §Final states (rf2-gn80): true iff the state-node declares
  `:final? true`. The marker is a first-class state-spec key (D1) — NOT
  stashed under `:meta` — so authors and AI agents see it at the state
  level."
  [node]
  (true? (:final? node)))

(defn final-on-leaf?
  "Per Spec 005 §Final states (rf2-gn80): true iff the state at the LEAF
  of `path` declares `:final? true`. Used by `apply-transition-once` to
  tag the resulting snapshot with `:rf/finished?` so the orchestrating
  lifecycle handler can fire `:on-done` + auto-destroy.

  Note: parallel-region machines compose finality across regions — the
  parent is `:final?` only when EVERY region's active leaf is `:final?`.
  This fn answers the per-state question; the parallel-region union is
  computed by the orchestrator (`re-frame.machines.parallel` /
  `re-frame.machines.lifecycle-fx.finalize`)."
  [machine state]
  (let [node (node-at machine (state-path state))]
    (final-state-node? node)))

;; ---- destroy-time exit cascade (rf2-nahfm) --------------------------------
;;
;; Per Spec 005 §Declarative `:spawn` §Composition with explicit `:entry`
;; / `:exit` and §Final states §Composition with `:entry` / `:exit`: when
;; a spawned actor is torn down, its active configuration's `:exit`
;; actions run BEFORE the teardown clears the snapshot. That covers
;; every destroy entry-point — explicit `:rf.machine/destroy`,
;; declarative-`:spawn` exit-cascade destroy, `:spawn-all` per-child
;; teardown, and final-state auto-destroy.
;;
;; `run-active-exit-cascade` is the single helper the destroy path
;; reaches for. It returns the post-cascade snapshot + fx so the caller
;; can (a) apply any `:exit`-time `:data` writes to the snapshot the
;; teardown observes (e.g. `:on-done` reads), and (b) surface the fx
;; the `:exit` action emitted. Parallel-region machines run an exit
;; cascade per region; the dispatcher lives in `re-frame.machines.parallel`
;; so this layer stays parallel-agnostic.

(defn run-active-exit-cascade
  "Synthesise the destroy-time exit cascade for `machine` against its
  active `snapshot`. Walks the active state's full path leaf→root and
  collects every node's `:exit` action-ref (shallowest-first reversed,
  matching how `compute-cascade-paths` orders the exit cascade with
  `lca-len = 0`). Runs them via `collect-actions`.

  Returns a `re-frame.machines.result/Result` — a `result/ok` carrying
  the post-cascade snapshot + accumulated fx, or a `result/fail` if any
  `:exit` action threw. Callers (destroy / finalize / invoke-all child
  teardown) emit a `[:rf.machine/bootstrap-exit]` synthetic event so
  3-arity `:exit` fns that introspect the event see a discriminator
  distinguishing destroy-time exit from transition-driven exit.

  This is the SINGLE entry-point — every destroy path threads through
  here so a spec change to destroy-time `:exit` semantics is one-edit-
  touches-all. Per Spec 005 §Final states §Composition: the final
  state's `:exit` runs from the auto-destroy teardown — same ordering
  convention as the user's `:exit` running before the auto-destroy for
  ordinary `:spawn`-bearing states."
  [machine snapshot]
  (let [path        (state-path (:state snapshot))
        active-pairs (nodes-along-path machine path)
        ;; Leaf→root: exit cascade reverses `nodes-along-path` (which
        ;; returns shallowest-first), matching `compute-cascade-paths`'s
        ;; `(map ... (reverse exited-pairs))` ordering.
        exit-refs   (keep (fn [[_ n]] (:exit n)) (reverse active-pairs))]
    (collect-actions machine snapshot [:rf.machine/destroy-exit] (vec exit-refs))))

;; ---- apply-transition-once: cascade phases --------------------------------
;;
;; Per Spec 005 §Entry/exit cascading along the LCA, one transition flows
;; through four named phases. Each phase is a pure helper; `apply-transition-
;; once` composes them. The decomposition is per rf2-8sz7f / audit §T6.
;;
;;   compute-cascade-paths  — derive src/target paths, LCA, exit/entry/action
;;                            refs, the `[prefix node]` pair vectors, and
;;                            the epoch-bumps? predicate. Pure geometry.
;;
;;   run-cascade            — feed the ordered ref-vec through
;;                            `collect-actions`: exit shallowest-first →
;;                            action at LCA → entry shallowest-first.
;;                            Returns the post-cascade Result (snap+fx).
;;
;;   commit-snapshot        — stamp `:state` (denormalised to match the
;;                            input shape) and bump the `:after` epoch when
;;                            any exited/entered node carries `:after`.
;;
;;   run-spawn-phase        — reduce over `entered-pairs` dispatching to
;;                            `handle-invoke-spawn` / `handle-invoke-all-
;;                            spawn`. Threads snapshot + acc-fx; short-
;;                            circuits to `result/fail` on `:data`-fn throws.

(defn- compute-cascade-paths
  "Phase 1 — derive the transition's geometry. Returns a map with:
    :src-path       — source state path (vector).
    :target-leaf    — initial-cascaded target path (nil for internal).
    :internal?      — true iff the transition has no `:target`.
    :lca-len        — common-prefix length of src and target.
    :exit-refs      — `:exit` action-refs, leaf→LCA (reverse order).
    :entry-refs     — `:entry` action-refs, LCA→leaf.
    :action-refs    — single-element vec carrying the transition's `:action`.
    :all-refs       — `(concat exit-refs action-refs entry-refs)` — the
                      ordered cascade ref-vec fed to `collect-actions`.
    :exited-pairs   — `[[prefix node] ...]` for states being exited (in
                      cascade order — leaf→LCA reversed gives shallowest-
                      first; this slot is unreversed for spawn/destroy
                      identification by prefix).
    :entered-pairs  — same shape, for states being entered.
    :after-bump-paths — the decl-paths (prefix vectors) of exited/entered
                      nodes that declare an `:after` table. `commit-
                      snapshot` bumps each one's per-path epoch so its
                      pending timers go stale; a still-active parent that
                      is neither exited nor entered keeps its epoch (and
                      thus its live timer). Per Spec 005 §Hierarchy
                      interaction (the per-level tracking the normative
                      external contract requires)."
  [machine snapshot transition]
  (let [src-path      (state-path (:state snapshot))
        decl-path     (:decl-path transition (vec (take 1 src-path)))
        raw-target    (:target transition)
        target-leaf   (some->> (target-path decl-path src-path raw-target)
                               (initial-cascade machine))
        internal?     (nil? raw-target)
        lca-len       (if internal?
                        (count src-path)
                        (common-prefix-length src-path target-leaf))
        ;; Walk each path once; reuse the `[prefix node]` pair vectors
        ;; for both the cascade ref derivation AND the spawn/destroy fx
        ;; emission downstream (per audit §T6 #2 — eliminate the double
        ;; nodes-along-path call). `nodes-along-path` returns a vector, so
        ;; `subvec` is one zero-copy slice — the prior `(vec (drop ...))`
        ;; built a lazy seq, then realised it, then `vec`'d (three
        ;; allocations). Per rf2-ijbg2.
        exited-pairs  (when-not internal?
                        (let [pairs (nodes-along-path machine src-path)]
                          (subvec pairs (min lca-len (count pairs)))))
        entered-pairs (when-not internal?
                        (let [pairs (nodes-along-path machine target-leaf)]
                          (subvec pairs (min lca-len (count pairs)))))
        exit-refs     (when-not internal?
                        (map (fn [[_ n]] (:exit n)) (reverse exited-pairs)))
        entry-refs    (when-not internal?
                        (map (fn [[_ n]] (:entry n)) entered-pairs))
        action-refs   [(:action transition)]
        ;; Per Spec 005 §Hierarchy interaction: bump the per-path epoch
        ;; ONLY for the `:after`-bearing nodes that are actually exited or
        ;; entered by this transition. A still-active parent above the LCA
        ;; appears in neither pair-vec, so its per-path epoch — and its
        ;; in-flight `:after` timer — survive a child-only transition.
        after-bump-paths (when-not internal?
                           (->> (concat exited-pairs entered-pairs)
                                (keep (fn [[prefix n]] (when (:after n) (vec prefix))))
                                distinct
                                vec))]
    {:src-path      src-path
     :decl-path     decl-path
     :raw-target    raw-target
     :target-leaf   target-leaf
     :internal?     internal?
     :lca-len       lca-len
     :exited-pairs  exited-pairs
     :entered-pairs entered-pairs
     :exit-refs     exit-refs
     :entry-refs    entry-refs
     :action-refs   action-refs
     :all-refs      (concat exit-refs action-refs entry-refs)
     :after-bump-paths after-bump-paths}))

(defn- run-cascade
  "Phase 2 — run the ordered cascade (`exit` shallowest-first → `action`
  at LCA → `entry` shallowest-first) via `collect-actions`. Returns the
  Result from `collect-actions` — either `result/ok` with the post-cascade
  snapshot + accumulated fx, or a `result/fail` carrying the throwing
  action's diagnostic map."
  [machine snapshot event cascade]
  (collect-actions machine snapshot event (:all-refs cascade)))

(defn- bump-after-epochs
  "Bump the per-path `:after` epoch for each decl-path in `bump-paths`
  (the exited/entered `:after`-bearing nodes). Each path's counter is
  monotonic — `(inc (or old 0))` — so a re-entry always lands on a fresh
  value that any in-flight timer from the prior visit observes as a
  mismatch. Paths absent from `bump-paths` (a still-active parent) keep
  their counter, so their pending timers stay live. Per Spec 005
  §Hierarchy interaction."
  [machine snap bump-paths]
  (let [epoch-base (after-epoch-path machine)]
    (reduce (fn [s p]
              (update-in s (conj epoch-base p) (fnil inc 0)))
            snap
            bump-paths)))

(defn- commit-snapshot
  "Phase 3 — write the new `:state` onto the post-cascade snapshot and
  bump the per-path `:after` epoch for each exited/entered `:after`-
  bearing node. Per Spec 005 §Delayed `:after` transitions §Hierarchy
  interaction. Internal transitions preserve the input snapshot's
  `:state` unchanged."
  [machine snapshot snap-after cascade]
  ;; Per rf2-adwxh: the `cond` has three arms — `internal?` (raw-target
  ;; is nil; preserve current state), vector target (use the cascade-
  ;; descended leaf as a vector), keyword target (collapse a single-
  ;; element leaf to a keyword, else vectorise). A pre-rf2-adwxh `:else`
  ;; arm was dead: `internal?` already covers the nil-raw-target case,
  ;; and `:target` validation upstream rejects anything other than
  ;; keyword/vector/nil.
  (let [{:keys [internal? raw-target target-leaf after-bump-paths]} cascade
        new-state (cond
                    internal?             (:state snapshot)
                    (vector? raw-target)  (vec target-leaf)
                    (keyword? raw-target) (if (= 1 (count target-leaf))
                                            (first target-leaf)
                                            (vec target-leaf)))]
    (if internal?
      (assoc snap-after :state new-state)
      (bump-after-epochs machine
                         (assoc snap-after :state new-state)
                         after-bump-paths))))

(defn- run-spawn-phase
  "Phase 4 — reduce over `entered-pairs` dispatching `:spawn` /
  `:spawn-all` declarations to their respective spawn handlers. Threads
  the post-commit snapshot + an fx accumulator; a `reduced` from either
  handler short-circuits to a `result/fail` Result. Returns either
  `result/ok` carrying `[snap-after-spawns spawn-fx]` or the propagated
  failure."
  [machine event snap-final cascade]
  (let [{:keys [internal? entered-pairs]} cascade
        parent-id (or (:rf/parent-id machine) :rf/transition-pure)]
    (if internal?
      (result/ok snap-final [])
      (let [step (reduce
                   (fn [[s acc-fx] [prefix n]]
                     (cond
                       (:spawn n)
                       (handle-invoke-spawn machine parent-id s acc-fx prefix n event)

                       (:spawn-all n)
                       (handle-invoke-all-spawn machine parent-id s acc-fx prefix n event)

                       :else
                       [s acc-fx]))
                   [snap-final []]
                   entered-pairs)]
        (if (result/fail? step)
          step
          (let [[snap-after-spawns spawn-fx] step]
            (result/ok snap-after-spawns spawn-fx)))))))

(defn apply-transition-once
  "Apply one transition (exit cascade → action → entry cascade → state
  change). Returns a `result/ok` Result carrying the new snapshot + fx,
  or a `result/fail` Result if any action or `:data` fn threw.

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

  Per Spec 005 §Final states (rf2-gn80): the returned snapshot is NOT
  tagged with `:rf/finished?` here — that flag is recomputed at the
  lifecycle-handler boundary so the pure-call surface (conformance corpus,
  JVM pure-fn tests) stays free of transient runtime metadata.

  Per rf2-8sz7f / audit §T6 the body composes four named phases:
  `compute-cascade-paths` → `run-cascade` → `commit-snapshot` →
  `run-spawn-phase`. Each phase is a pure helper above.

  `transition` is the transition map with a synthetic :decl-path key
  recording where in the state-path tree the transition was declared."
  [machine snapshot event transition]
  (let [cascade  (compute-cascade-paths machine snapshot transition)
        cascade-r (run-cascade machine snapshot event cascade)]
    (if (result/fail? cascade-r)
      (result/fail-with cascade-r {:decl-path  (:decl-path cascade)
                                   :transition transition
                                   :state-path (:src-path cascade)})
      (result/with-ok [snap-after fx] cascade-r
        (let [snap-final      (commit-snapshot machine snapshot snap-after cascade)
              parent-id       (or (:rf/parent-id machine) :rf/transition-pure)
              after-fx        (build-after-fx machine (:entered-pairs cascade)
                                              (:internal? cascade) snap-final)
              after-cancel-fx (build-after-cancel-fx parent-id (:exited-pairs cascade)
                                                     (:internal? cascade))
              destroy-fx      (build-destroy-fx parent-id (:exited-pairs cascade)
                                                (:internal? cascade))
              spawn-r         (run-spawn-phase machine event snap-final cascade)]
          (if (result/fail? spawn-r)
            (result/fail-with spawn-r {:decl-path  (:decl-path cascade)
                                       :transition transition
                                       :state-path (:src-path cascade)})
            (result/with-ok [snap-after-spawns spawn-fx] spawn-r
              (let [all-fx (vec (concat fx
                                        (or after-cancel-fx [])
                                        (or destroy-fx [])
                                        spawn-fx
                                        (or after-fx [])))]
                (result/ok snap-after-spawns all-fx)))))))))

(defn- pick-always-transition
  "Per Spec 005 §Eventless :always transitions: walk path leaf→root for
  an `:always` whose guard passes (the deepest-wins rule named in
  `path-walk/walk-path-leaf-to-root`). Returns
  `{:transition t :decl-path p}` or nil."
  [machine path snapshot]
  (path-walk/walk-path-leaf-to-root
    machine path
    (fn [prefix n]
      (let [always (:always n)
            always (cond
                     (nil? always)    []
                     (vector? always) always
                     :else            [always])
            hit    (some (fn [t]
                           (when (evaluate-guard machine (:guard t) snapshot nil)
                             t))
                         always)]
        (when hit
          {:transition (assoc hit :decl-path prefix) :decl-path prefix})))))

(def ^:private always-depth-limit-default
  ;; Per Spec 005 §Drain semantics: bounds the `:always` microstep loop
  ;; (each iteration drains every match leaf→root). 16 leaves plenty of
  ;; headroom for legitimate cascades while still catching `:always`
  ;; loops within a single macrostep. Overridable per machine via
  ;; `:always-depth-limit`.
  16)

(def ^:private raise-depth-limit-default
  ;; Per Spec 005 §Drain semantics: bounds the recursive `:raise` queue
  ;; drain. Symmetric with `always-depth-limit-default` — 16 is generous
  ;; for hand-authored event-chains and catches accidental cycles.
  ;; Overridable per machine via `:raise-depth-limit`.
  16)

;; Forward-declared so `drain-raises` can call `machine-transition-single`
;; directly. The recursive `:raise` step is always against an already-
;; resolved single (or region) machine context — for a parallel parent,
;; `parallel-machine-transition` (in `re-frame.machines.parallel`) has
;; already routed into `machine-transition-single` per-region, and the
;; recursive call from inside drain-raises uses the SAME `machine` value
;; (the region-machine, with `parallel?` false). Bypassing the public
;; parallel-dispatch entry here avoids a per-raise cross-namespace var
;; deref on CLJS and keeps the parallel layer cleanly above the single-
;; machine drain.
(declare machine-transition-single)

(defn- drain-raises
  "Drain the :raise queue inside fx-vec. Each :raise becomes an inline
  recursive machine-transition-single call; non-:raise fx pass through to
  the accumulator. Returns a `result/ok` Result carrying the post-drain
  `[snap accum-fx]`, or a `result/fail` Result if any recursive step
  failed."
  [machine snapshot fx-vec depth-limit]
  (loop [pending fx-vec
         accum   []
         snap    snapshot
         depth   0]
    (cond
      (> depth depth-limit)
      (do (trace/emit-error! :rf.error/machine-raise-depth-exceeded
                             {;; The live runtime spec carries the machine
                              ;; id under `:rf/parent-id`; the spec map forbids
                              ;; `:id`. Mirror the guard/action traces'
                              ;; fallback so the trace names the real machine.
                              :machine-id (or (:rf/parent-id machine) (:id machine))
                              :depth      depth
                              ;; Per rf2-ko8jb: epoch-capture admission
                              ;; requires `:frame`.
                              :frame      (:rf/frame machine)
                              :recovery   :no-recovery})
          (result/ok snap accum))

      (empty? pending)
      (result/ok snap accum)

      :else
      (let [[fx-id args] (first pending)
            rest-pending (rest pending)]
        (case fx-id
          :raise
          (let [step-result (machine-transition-single machine snap args)]
            (if (result/fail? step-result)
              step-result
              (result/with-ok [snap2 fx2] step-result
                (recur (concat fx2 rest-pending)
                       accum
                       snap2
                       (inc depth)))))

          (recur rest-pending
                 (conj accum [fx-id args])
                 snap
                 depth))))))

(defn- emit-pick-traces!
  "Fire the three pre-transition timer traces for a `pick-transition`
  match — `:rf.machine.timer/stale-after`, `:rf.machine.timer/fired`
  (guard-suppressed), and `:rf.machine.timer/fired` (success). Each
  branch is mutually exclusive given the `match` shape, but we spell
  them sequentially so listeners observe document order if a future
  match shape lights up more than one. No-op when `match` is nil or
  carries no relevant marker.

  Per rf2-ko8jb the `:frame` tag is REQUIRED for epoch-capture
  admission (`re-frame.epoch.capture/capture-event!` silently drops
  events whose tags lack `:frame`). The caller threads `frame-id`
  resolved from `(:rf/frame machine)` so timer-firing observability
  reaches the cascade's `:trace-events` slot."
  [frame-id match]
  (when match
    (when (:stale? match)
      (trace/emit! :machine :rf.machine.timer/stale-after
                   {:state           (:state match)
                    :delay           (:delay match)
                    :scheduled-epoch (:scheduled-epoch match)
                    :current-epoch   (:current-epoch match)
                    :frame           frame-id
                    :recovery        :replaced-with-default}))
    (when (:guard-suppressed? match)
      (trace/emit! :machine :rf.machine.timer/fired
                   {:state  (:state match)
                    :delay  (:delay match)
                    :epoch  (:epoch match)
                    :fired? false
                    :frame  frame-id}))
    (when (and (not (:stale? match))
               (not (:guard-suppressed? match))
               (:delay match))
      (trace/emit! :machine :rf.machine.timer/fired
                   {:state  (last (:decl-path match))
                    :delay  (:delay match)
                    :epoch  (:epoch match)
                    :fired? true
                    :frame  frame-id}))))

(defn machine-transition-single
  "Pure function. Single-machine (flat or compound) implementation of the
  macrostep. Per Spec 005 §Drain semantics §Level 3:
   1. Pick the matching transition for the event (deepest-wins resolution
      along the state path).
   2. Run the exit cascade → transition's action → entry cascade
      (`apply-transition-once`).
   3. Drain the local `:raise` queue depth-first.
   4. `:always` microstep loop — walk path leaf→root for any matching
      `:always`; apply, drain raises, loop.
   5. Commit (return) the snapshot once `:always` reaches fixed point.

  Returns a `result/ok` Result on success or a `result/fail` Result if
  any action or `:data`-fn threw. Bounded by `:raise-depth-limit` and
  `:always-depth-limit` (both default 16). Parallel-region routing lives
  in `re-frame.machines.parallel`'s `machine-transition` — the dispatch
  checks `parallel?` and either broadcasts across regions or falls
  through to this fn."
  [machine snapshot event]
  (let [always-limit (get machine :always-depth-limit always-depth-limit-default)
        raise-limit  (get machine :raise-depth-limit  raise-depth-limit-default)
        path             (state-path (:state snapshot))
        match            (pick-transition machine path event snapshot)
        ;; Trace timer firing / staleness / guard-suppression BEFORE
        ;; running the transition, so listeners see events in the order
        ;; they occurred.
        _ (emit-pick-traces! (:rf/frame machine) match)
        result-after-event
        (cond
          (and match (:stale? match))
          (result/ok snapshot [])

          (and match (:guard-suppressed? match))
          (result/ok snapshot [])

          match
          (apply-transition-once
            machine snapshot event
            (assoc (:transition match) :decl-path (:decl-path match)))

          :else
          (result/ok snapshot []))]
    (if (result/fail? result-after-event)
      result-after-event
      (result/with-ok [snap-after-event fx-after-event] result-after-event
        (let [raised (drain-raises machine snap-after-event fx-after-event raise-limit)]
          (if (result/fail? raised)
            raised
            (result/with-ok [snap-after-raise fx-after-raise] raised
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
                                         {;; The live runtime spec carries the
                                          ;; machine id under `:rf/parent-id`;
                                          ;; the spec map forbids `:id`. Mirror
                                          ;; the guard/action traces' fallback.
                                          :machine-id (or (:rf/parent-id machine)
                                                          (:id machine))
                                          :depth      depth
                                          :path       visited
                                          ;; Per rf2-ko8jb: epoch-capture
                                          ;; admission requires `:frame`.
                                          :frame      (:rf/frame machine)
                                          :recovery   :no-recovery})
                      (result/ok snapshot []))

                  :else
                  (let [snap-path (state-path (:state snap))
                        always-m  (pick-always-transition machine snap-path snap)]
                    (if (nil? always-m)
                      ;; Macrostep fixed-point reached. Recompute the
                      ;; active-configuration tag union on the committed snapshot
                      ;; AFTER the new state is settled but BEFORE traces fire
                      ;; (so the outer handler's `:rf.machine/transition` trace
                      ;; carries the new tag set).
                      (result/ok (commit-tags machine snap) fx)
                      (let [step-result (apply-transition-once machine snap nil
                                                                (:transition always-m))]
                        (if (result/fail? step-result)
                          step-result
                          (result/with-ok [snap2 fx2] step-result
                            (let [raised2 (drain-raises machine snap2 fx2 raise-limit)]
                              (if (result/fail? raised2)
                                raised2
                                (result/with-ok [snap3 fx3] raised2
                                  (recur snap3
                                         (vec (concat fx fx3))
                                         (inc depth)
                                         (conj visited (:state snap3))))))))))))))))))))
