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
            [clojure.string :as str]
            [re-frame.machines.path-walk :as path-walk]
            [re-frame.machines.result :as result
             #?@(:cljs [:include-macros true])]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

(def start-marker
  "The reserved synthetic creation marker — the inner event vector
  `[:rf.machine/start]`. xstate parity with `createActor(m).start()`.

  Two roles, both `:entry`-only (it NEVER reaches an `:on` map):
    1. As an EAGER kick (`[:machine-id [:rf.machine/start]]`) it brings a
       machine to life now rather than on its first real event. Per F‴
       (rf2-gl588) it is a PURE init-kick — the lifecycle handler runs the
       initial-entry cascade then STOPS; the marker is never fed into the
       transition step as a trigger.
    2. As the placeholder `:event` value threaded through the initial-entry
       cascade so `:entry` actions reading `(fn [{:keys [event]}] …)` see a
       non-nil, reserved-namespace discriminator on the BIRTH call.

  Renamed from `:rf.machine/bootstrap` (pre-alpha, no back-compat shim).
  The canonical definition lives here in the leaf engine namespace so both
  `parallel` (the cascade) and `lifecycle-fx.registration` (the handler)
  reference one source of truth without a require cycle."
  :rf.machine/start)

(defn- chase-ref
  "Follow a keyword reference chain through the machine's named-bindings
  map until it hits a fn (or fails). Tolerates one level of indirection
  like {:short-name :registered-id} where :registered-id resolves to a fn.

  Per rf2-npvsx every `:guards` / `:actions` / `:on-spawn-actions` entry
  is the co-located `{:fn <fn> ...}` map (the `:source-*` slots are
  dev-only). A registry VALUE may therefore be that entry map, a bare fn
  (programmatic `reg-machine*` with raw fns, or a `(constantly …)`
  fallback), or a keyword (indirection). Unwrap `:fn` from an entry map
  before treating it as the resolved callback."
  [registry ref]
  (loop [r ref seen #{}]
    (cond
      (fn? r)                       r
      (and (map? r) (:fn r))        (:fn r)
      (contains? seen r)            nil
      (keyword? r)                  (if-let [nxt (get registry r)]
                                      (recur nxt (conj seen r))
                                      nil)
      :else                         nil)))

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
;; Xray's `:rf.xray/machine-transitions-for-focused-event` sub) groups
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
  evaluation) and return true.

  Per rf2-82a0u: when the guard fn throws, emit
  `:rf.machine/guard-evaluated` with `:outcome :threw` and the
  `:exception` slot, then treat the guard as failed (return `false`)
  so the candidate-walk continues evaluating siblings — Spec 005
  §Guards is silent on throw semantics; the engine's existing
  implicit behaviour (let it propagate) hides the failure entirely
  from the trace stream. Treating throw as `:fail` matches the
  documented `:action`-threw convention (the action that throws emits
  one trace and the cascade halts; for guards the cascade should walk
  past the throwing candidate to the next one, which is the
  `:rf/transition` candidate-walk semantic — \"this candidate
  declined; try the next\")."
  [machine guard-ref snapshot event]
  (if (nil? guard-ref)
    true
    (let [g          (resolve-guard machine guard-ref)
          machine-id (or (:rf/parent-id machine) (:id machine))
          ;; Per rf2-ko8jb: epoch-capture admission requires `:frame`.
          ;; `(:rf/frame machine)` is stamped by `prepare-machine-ctx`
          ;; (registration.cljc) before the engine is invoked; nil-safe
          ;; for pure-function callers (conformance corpus, JVM fixtures).
          frame-id   (:rf/frame machine)
          input      {:data (:data snapshot) :event event}]
      (try
        (let [outcome (boolean (call-guard g snapshot event))]
          (trace/emit! :rf.machine :rf.machine/guard-evaluated
                       {:machine-id machine-id
                        :guard-id   guard-ref
                        :input      input
                        :outcome    (if outcome :pass :fail)
                        :frame      frame-id})
          outcome)
        (catch #?(:clj Throwable :cljs :default) e
          (trace/emit! :rf.machine :rf.machine/guard-evaluated
                       {:machine-id machine-id
                        :guard-id   guard-ref
                        :input      input
                        :outcome    :threw
                        :exception  e
                        :frame      frame-id})
          false)))))

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
  (if (and (keyword? original) (= 1 (count path)))
    (first path)
    (vec path)))

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

(defn stamp-tags
  "Elide-or-assoc the `:tags` slot on `snapshot`. Per Spec 005 §State
  tags §Snapshot shape change: the slot is OPTIONAL — an empty union
  dissociates the key entirely (keeping snapshots small for the common
  no-tags case); a non-empty union assocs it. The single home for the
  elision rule, shared by `commit-tags` (single / compound) and
  `commit-tags-parallel` (parallel-region) so the two can never drift."
  [snapshot tags]
  (if (empty? tags)
    (dissoc snapshot :tags)
    (assoc snapshot :tags tags)))

(defn- commit-tags
  "Stamp the active-configuration tag union onto `snapshot` at `:tags`.
  Per Spec 005 §State tags §Snapshot shape change."
  [machine snapshot]
  (stamp-tags snapshot (compute-tags machine (:state snapshot))))

(defn- normalise-candidates
  "Normalise a transition-table value into a vector of candidate
  transition maps. The single source of truth for the value-form grammar
  shared by `:on`, `:after` (and, by extension, any future slot whose
  value is a transition spec — `:always` already carries the explicit
  candidate-vector form). Per Spec 005 §Transitions §Multiple-candidate
  transitions and §Delayed `:after` transitions, the value may be:

    a keyword              -> treat as {:target <kw>}        (sibling target)
    a vector of state ids  -> treat as {:target <vec>}       (absolute path)
    a vector of maps       -> multiple guarded candidates    (first guard-pass wins)
    a single transition map

  Returns a vector of candidate transition maps (possibly empty for a nil
  value). Keeping `:on` and `:after` on this ONE normaliser is what stops
  the two value-form grammars drifting apart — the guarded candidate-vector
  form (`[{:guard g :target s} {:target s2 :action a}]`) resolves
  identically whether it is reached through an `:on` clause or an `:after`
  delay entry.

  `bad-value-id` names the error category to throw for an unrecognised
  value form so each caller surfaces its own slot-specific taxonomy
  (`:on` → `machine-bad-on-clause`, `:after` → `machine-bad-after-spec`)."
  [v bad-value-id]
  (cond
    (nil? v)                        []
    (keyword? v)                    [{:target v}]
    (and (vector? v)
         (every? map? v)
         (seq v))                   v
    (vector? v)                     [{:target v}]
    (map? v)                        [v]
    :else (throw (ex-info (str bad-value-id) {:value v}))))

(defn- select-passing-candidate
  "Walk `candidates` (already normalised by `normalise-candidates`) in
  declaration order and return the first whose `:guard` passes against
  `snapshot`/`event`, or nil if none pass. Per Spec 005 §Transition
  resolution — first-match-wins over the candidate list, applied
  identically wherever a transition-table value is resolved (`:on`,
  `:after`, `:always`). An unguarded candidate (no `:guard`) always
  passes — it is the documented unconditional fallback that ends a
  guarded candidate list."
  [machine candidates snapshot event]
  (some (fn [t]
          (when (evaluate-guard machine (:guard t) snapshot event)
            t))
        candidates))

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
        ;; region-name-prefixed decl-path (`prefix-region-spawn-id`) for
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
        ;; `t` is the RAW `:after` table value at this delay-key — a bare
        ;; keyword target, a single transition map, a vector-of-state-ids
        ;; absolute target, OR a guarded candidate-vector
        ;; `[{:guard g :target s} {:target s2 :action a}]`. It is normalised
        ;; and walked through the SAME candidate machinery as an `:on`
        ;; clause (`normalise-candidates` + `select-passing-candidate`), so
        ;; the value-form grammar can never drift between the two slots.
        ;; Per Spec 005 §Multiple-candidate transitions / §Delayed `:after`
        ;; transitions: first guard-pass wins; an unguarded candidate is the
        ;; unconditional fallback.
        (fn [prefix t]
          (let [cands  (normalise-candidates t :rf.error/machine-bad-after-spec)
                tspec  (select-passing-candidate machine cands snapshot event)]
            (if tspec
              {:transition tspec
               :decl-path  prefix
               :delay      delay-key
               :epoch      carried-epoch}
              ;; No candidate's guard passed (every guarded candidate's
              ;; guard returned false and there is no unguarded fallback).
              ;; Per Spec 005 §Multi-stage interaction with :guard: the
              ;; timer is "fired and discarded" — no transition, no epoch
              ;; advance; sibling :after timers continue.
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
          (resolve-hit decl-path t)

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
                      (resolve-hit prefix t)
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

(defn- match-on-clause
  "Given a node-or-machine map carrying an `:on` table, return the first
  candidate transition for `event-id` (explicit then `:*` wildcard) whose
  guard passes, or nil. Per Spec 005 §Transition resolution — the
  per-level matching rule applied identically at every state-node and at
  the machine root.

  rf2-e7yhv — when the match came from the `:*` WILDCARD branch (no
  explicit `event-id` entry, the wildcard matched), the returned
  transition is stamped `:rf/via-wildcard? true`. This rides the
  `:transition` slot through `apply-transition-once` into a
  `:rf.error/machine-action-exception` trace (when the wildcard's action
  throws — the xstate-v5 'fail loudly on unknown' idiom) so a consumer can
  attribute the throw to a `:*` wildcard action rather than a named
  transition."
  [machine node event-id event snapshot]
  (let [explicit (get-in node [:on event-id])
        wildcard (when (nil? explicit) (get-in node [:on :*]))
        cands    (normalise-candidates
                   (or explicit wildcard)
                   :rf.error/machine-bad-on-clause)
        hit      (select-passing-candidate machine cands snapshot event)]
    (cond-> hit
      (and hit (some? wildcard)) (assoc :rf/via-wildcard? true))))

(defn- pick-transition
  "Walk path leaf→root looking for a transition that matches event-id and
  whose guard passes. Per Spec 005 §Transition resolution — deepest-wins
  with parent fallthrough (the rule named in `path-walk/walk-path-leaf-
  to-root`).

  The walk terminates at the **machine root's own `:on`** (Spec 005
  §Transition resolution steps 6-7: top-level (root) `:on` explicit
  match, then `:*` wildcard). The root `:on` is the documented place to
  factor common transitions every state inherits (`:logout` from every
  authenticated descendant). A root-`:on` hit is stamped with an empty
  `:decl-path` so `target-path` resolves a keyword target root-relative
  (`(drop-last [])` → `[]`, so `:idle` lands at `[:idle]`) and a vector
  target stays absolute — matching the declaring-state rule where the
  root is the keyword target's parent level.

  Special-cases the synthetic :rf.machine.timer/after-elapsed event by
  delegating to pick-after-transition."
  [machine path event snapshot]
  (let [event-id (first event)]
    (if (= :rf.machine.timer/after-elapsed event-id)
      (pick-after-transition machine path event snapshot)
      (or
        ;; Steps 1-5: leaf→root over the active state-path nodes.
        (path-walk/walk-path-leaf-to-root
          machine path
          (fn [prefix n]
            (when-let [hit (match-on-clause machine n event-id event snapshot)]
              {:transition hit :decl-path prefix})))
        ;; Steps 6-7: the machine root's own `:on` fallback. Consulted
        ;; only when no state-path node handled the event.
        (when-let [hit (match-on-clause machine machine event-id event snapshot)]
          {:transition hit :decl-path []})))))

(defn unhandled-event-no-op?
  "True iff a nil `pick-transition` result for `event` should emit the
  benign `:rf.machine.event/unhandled-no-op` trace. Per Spec 005
  §Transition resolution the no-op marks an UNKNOWN USER event a machine
  declined (xstate-v5 parity — ignored, never thrown).

  Carve-out (rf2-t4582 — a conscious refinement of rf2-ugdas): the no-op
  classifies an unknown USER / domain event, NOT framework lifecycle
  traffic. Per [Conventions.md §The single-root reserved set] the
  framework reserves the `:rf/*` root namespace for every framework-owned
  id, *machine lifecycle events included*; the stories library's
  lifecycle / assertion events ride the same reserved root
  (`:rf.story.lifecycle/*`, `:rf.assert/*`). A reserved-`:rf/*` event that
  resolves to no transition is benign framework init, not an event the
  machine author forgot to handle:

   - the synthetic creation marker `[:rf.machine/start]` (per Spec 005
     §Synthetic creation marker) is a placeholder threaded into the
     initial-entry actions so they carry an `:event` key — the start RAN
     the entry cascade and INSTALLED the state; it is the machine's BIRTH,
     not a no-op. (Per F‴ / rf2-gl588 an eager `[:machine-id
     [:rf.machine/start]]` kick is a PURE init that STOPS — it never
     reaches this no-op site as a trigger; the rule subsumes the marker
     only as the cascade-threaded `:event` placeholder.);
   - the spawn kick-off `[:rf.machine.spawn/spawned]` (Spec 005 §spawn
     kick-off) is dispatched into every spawned actor so generic children
     may declare a first transition — an actor that declines it simply
     has no such clause, which is not a missed user event;
   - domain machines that optimistically broadcast reserved-namespace
     lifecycle pings (the stories runtime firing
     `:rf.story.lifecycle/events-complete` at a machine already resting in
     `:ready`) ride the same root.

  This re-SEPARATES the spawn-kickoff exemption rf2-ugdas folded into the
  general rule, and ALIGNS with xstate: xstate's own init (`xstate.init`)
  runs the initial-entry and is NOT reported as an unhandled event — only
  unknown user events are silently ignored. Distinguishing re-frame2's
  bootstrap / spawn-kickoff makes us MORE like xstate, not a v5-parity
  violation. SEVERITY is unchanged from rf2-ugdas (benign — nothing
  throws); only the SEMANTIC classification is restored, so reserved-
  `:rf/*` framework init does not read as an unknown-user-event no-op.

  (`:rf.machine.timer/after-elapsed` is special-cased in `pick-transition`
  and `:rf.machine/start` is `:entry`-only — and, per F‴ (rf2-gl588), the
  eager start kick STOPS after initial-entry without ever running the
  transition step — so neither reaches the no-op site as a trigger; both
  are reserved-namespace, so the rule subsumes them regardless.)

  Public so the parallel-region aggregate path
  (`parallel/parallel-machine-transition`) shares the single source of
  truth."
  [event]
  (let [event-id (first event)
        ns       (when (keyword? event-id) (namespace event-id))]
    (not (and ns (or (= ns "rf") (str/starts-with? ns "rf."))))))

(defn- target-path
  "Compute the absolute target path for a transition. Per Spec 005:
   - `:same-state` sentinel → the declaring state's OWN path (`decl-path`).
     Marks an EXTERNAL self-transition: the state is exited and re-entered
     (`:exit` then `:entry` both fire), the configuration unchanged. The
     self-re-entry geometry — pulling the LCA up to the declaring state's
     parent so the state appears in both the exit and entry cascades — is
     `compute-cascade-paths`' job; here `:same-state` simply names the
     declaring state as the target. Per Spec 005 §Self-transitions +
     Spec-Schemas TransitionTarget (rf2-46ban).
   - keyword target → sibling at decl-path's level (replace last element).
     A keyword that names the declaring state's OWN key resolves to
     `decl-path` too, so it is the same external self-transition as
     `:same-state` (Spec 005 §Entry/exit cascading — \"if `:target` names
     the same state as the source, the transition is external\").
   - vector target → absolute path from root.
   - nil target (internal transition) → nil; the caller wraps the call
     in `some->>` so the nil short-circuits the initial-cascade descent.

  Per rf2-adwxh the explicit `(nil? target) nil` arm is dropped — when
  target is neither vector nor keyword, the `cond` falls through to
  nil, which is the documented internal-transition contract."
  [decl-path target]
  (cond
    (= :same-state target) (vec decl-path)
    (vector? target)       target
    (keyword? target)
    (let [parent (vec (drop-last decl-path))]
      (conj parent target))))

(defn- common-prefix-length [a b]
  (count (take-while true? (map = a b))))

;; ---- history pseudo-states (rf2-mle6e.3) ----------------------------------
;;
;; Per Spec 005 §History states (`:type :history` — shallow / deep /
;; default-target). A `:type :history` node under a compound's `:states` is
;; a TARGETABLE PSEUDO-STATE: never occupied, it resolves a transition to
;; the compound's recorded (or default) configuration. The engine:
;;
;;   - RESTORES on re-entry — `compute-cascade-paths` swaps the pseudo-state
;;     target for the resolved leaf BEFORE the LCA geometry, so the standard
;;     exit/action/entry cascade applies unchanged (46ban's external-self-
;;     transition LCA fix is the precedent: resolve the real path first,
;;     then let the geometry run on it);
;;   - RECORDS on exit — `apply-transition-once` writes the exited compound's
;;     last-active configuration into the snapshot `:rf/history` slot as part
;;     of the exit-cascade commit;
;;   - the `:rf/history` slot is keyed by the compound's DECLARATION PATH,
;;     region-qualified (head segment = region name) under `:type :parallel`
;;     so two regions' structurally-identical compound paths never collide.
;;
;; `:rf/history` lives inside the snapshot (a revertible value), so the
;; recording rides `pr-str` round-trip, SSR hydration, and Tool-Pair epoch
;; replay for free — no side-table.

(defn history-node?
  "True iff `node` is a history pseudo-state (`:type :history`)."
  [node]
  (= :history (:type node)))

(defn- history-child
  "Return `[hist-key hist-node]` for the (single) history pseudo-state
  declared directly under compound at `compound-path`, or nil if the
  compound owns none. Per Spec 005 §History states — a compound owns at
  most one (registration-validated)."
  [machine compound-path]
  (let [compound (if (empty? compound-path)
                   machine
                   (node-at machine compound-path))]
    (some (fn [[k n]] (when (history-node? n) [k n]))
          (:states compound))))

(defn- history-key
  "The `:rf/history` map key for compound at `compound-path` — the
  declaration path, region-qualified (head = region name) under a
  parallel region. The single-machine engine carries `:rf/region` for a
  region-spec; flat / compound machines carry none."
  [machine compound-path]
  (let [region (:rf/region machine)]
    (vec (if region (cons region compound-path) compound-path))))

(defn- valid-leaf-path?
  "True iff `path` resolves to a real (non-history) state-node in the
  current definition. Used to detect a DANGLING recorded path after hot
  reload (rf2-wgfv0): a recorded config referencing a removed substate."
  [machine path]
  (let [n (node-at machine path)]
    (and (some? n) (not (history-node? n)))))

(defn- resolve-history-target
  "Per Spec 005 §Restoring — on transition to the pseudo-state. Resolve a
  history pseudo-state at `hist-path` (absolute, ending in the history
  node's key) to a concrete leaf path, given the current `snapshot`'s
  `:rf/history` slot. `hist-node` is the pseudo-state node.

  Returns `{:leaf <path> :source :recorded|:default ...}`:
   - `:recorded` — a recording exists for the owning compound AND it is
     still a valid path in the current definition. Deep history restores
     the full recorded leaf path; shallow restores the recorded direct
     child then `initial-cascade`s its `:initial` chain. Additionally
     carries `:restored-config` — the recorded value (per spec/009 the
     `:rf.machine.history/restored` `:restored-config` tag).
   - `:default` — no (valid) recording: the owning compound was never
     entered, or the recorded config is a DANGLING path a hot-reloaded
     definition removed (rf2-wgfv0). Falls back to the pseudo-state's
     `:default-target` (initial-cascaded), or — when absent — the owning
     compound's `:initial` cascade, exactly as a first-ever entry would.
     Additionally carries `:fallback` — `:default-target` when the
     pseudo-state declared one, else `:initial` (the spec/009 `:fallback`
     tag)."
  [machine snapshot hist-path hist-node]
  (let [compound-path (vec (drop-last hist-path))
        hkey          (history-key machine compound-path)
        recorded      (get-in snapshot [:rf/history hkey])
        deep?         (true? (:deep? hist-node))
        ;; Which fallback resolves the leaf on the `:default` path:
        ;; `:default-target` when the pseudo-state declared one (keyword or
        ;; vector form), else the owning compound's `:initial`.
        fallback      (if (some? (:default-target hist-node)) :default-target :initial)
        default-leaf  (fn []
                        (let [dt (:default-target hist-node)]
                          (cond
                            ;; `:default-target` keyword names a DIRECT CHILD
                            ;; of the owning compound (not a sibling — unlike
                            ;; a transition `:target`); build the absolute
                            ;; child path then descend any `:initial` chain.
                            (keyword? dt) (initial-cascade machine (conj compound-path dt))
                            ;; Vector form is an absolute path.
                            (vector? dt)  (initial-cascade machine dt)
                            ;; Absent => the owning compound's own `:initial`
                            ;; cascade (cascade from the compound itself).
                            :else         (initial-cascade machine compound-path))))]
    (cond
      ;; Deep — recorded value is the full absolute leaf path.
      (and recorded deep? (vector? recorded) (valid-leaf-path? machine recorded))
      {:leaf recorded :source :recorded :restored-config recorded}

      ;; Shallow — recorded value is the direct-child KEYWORD; rebuild the
      ;; absolute child path and cascade its `:initial` chain.
      (and recorded (not deep?) (keyword? recorded)
           (let [child-path (conj compound-path recorded)]
             (valid-leaf-path? machine child-path)))
      {:leaf (initial-cascade machine (conj compound-path recorded))
       :source :recorded :restored-config recorded}

      ;; No recording, or a DANGLING recorded path (hot-reload removed the
      ;; substate) — graceful fallback to default-target / :initial.
      :else
      {:leaf (default-leaf) :source :default :fallback fallback})))

(defn- record-history-config
  "Per Spec 005 §Recording — on compound-state exit. Given a compound at
  `compound-path` that owns a history pseudo-state and the FULL active leaf
  path the machine occupied (`active-path`), compute the recorded
  configuration: the absolute leaf path beneath the compound for DEEP
  history, or the direct-child keyword for SHALLOW. Returns `[hkey config]`
  (the `:rf/history` map entry) or nil when the compound owns no history or
  the active path doesn't descend through it."
  [machine compound-path active-path]
  (when-let [[_ hist-node] (history-child machine compound-path)]
    (let [depth (count compound-path)]
      ;; The active leaf must lie beneath this compound (the compound is on
      ;; the source path AND has at least one substate active below it).
      (when (and (> (count active-path) depth)
                 (= compound-path (vec (take depth active-path))))
        (let [hkey  (history-key machine compound-path)
              deep? (true? (:deep? hist-node))]
          (if deep?
            [hkey (vec active-path)]                  ;; full absolute leaf path
            [hkey (nth active-path depth)]))))))       ;; direct child keyword

(defn- record-exit-history
  "Per Spec 005 §Recording — on compound-state exit. Pure. Given the machine,
  the pre-transition active leaf path (`src-path`), and the transition's
  `lca-len`, write each history-bearing compound's last-active configuration
  into the snapshot's `:rf/history` slot.

  A history-owning compound `C` records iff the exit cascade leaves the
  child subtree beneath `C` — i.e. `C` is a prefix of `src-path` with an
  active child below it (`(count src-path) > (count C-path)`) AND that
  child is being exited (`lca-len <= (count C-path)`, so the child at depth
  `(count C-path)` sits at-or-below the LCA boundary). The compound `C`
  itself need not be exited — moving between two children of `C` (LCA = C)
  still tears down `C`'s current child subtree, which is exactly what
  history captures. A transition staying entirely within `C`'s current
  child (LCA strictly below `C`'s child) records nothing for `C`.

  Returns `[snapshot recorded]` where `recorded` is the seq of
  `{:compound-path :recorded-config :kind :prev-config}` maps (for the
  `:rf.machine.history/recorded` trace, per spec/009). `:compound-path` is
  the region-qualified declaration path (the `:rf/history` key); `:kind` is
  `:deep`/`:shallow`; `:prev-config` is the value the slot held BEFORE this
  write (omitted on the first-ever recording for the compound — the slot was
  previously unallocated). The slot is allocated lazily — a transition
  leaving no history-bearing compound's child subtree leaves the snapshot
  untouched."
  [machine snapshot src-path lca-len]
  (let [src-path (vec src-path)
        n        (count src-path)]
    ;; Walk every prefix of `src-path` that could own history (depth 0..n-1;
    ;; a leaf at depth n-1 has no child below it). A prefix `C-path` records
    ;; iff its child at depth `(count C-path)` is exited: `lca-len <= depth`.
    (reduce
      (fn [[snap recs] depth]
        (let [compound-path (subvec src-path 0 depth)
              entry         (when (<= lca-len depth)
                              (record-history-config machine compound-path src-path))]
          (if entry
            (let [[hkey config] entry
                  deep?         (true? (:deep? (second (history-child machine compound-path))))
                  ;; Read the value the slot held BEFORE this write, off the
                  ;; accumulating snapshot. `contains?` distinguishes a
                  ;; never-allocated slot (first-ever recording — `:prev-config`
                  ;; absent per spec) from one holding a value (incl. nil).
                  had-prev?     (contains? (:rf/history snap) hkey)
                  prev-config   (get-in snap [:rf/history hkey])]
              [(assoc-in snap [:rf/history hkey] config)
               (conj recs (cond-> {:compound-path hkey
                                   :recorded-config config
                                   :kind (if deep? :deep :shallow)}
                            had-prev? (assoc :prev-config prev-config)))])
            [snap recs])))
      [snapshot []]
      (range 0 n))))

;; ---- history trace events (rf2-mle6e.3; spec/009 contract: rf2-mle6e.2) ----
;;
;; Per Spec 005 §History states + Spec 009 §Trace events. The engine emits
;; observability traces under the machine-activity family `:rf.machine.
;; history/*` (op-type `:rf.machine`, like `:rf.machine/transition` — NOT a
;; severity discriminator, so consumers' issue-projection predicates never
;; classify them as issues):
;;
;;   - `:rf.machine.history/restored` — a transition resolved to a history
;;     pseudo-state. Tags (spec/009): `:compound-path` (region-qualified
;;     declaration path — the `:rf/history` key), `:kind` (`:shallow`/
;;     `:deep`), `:source` (`:recorded` | `:default`), `:fallback`
;;     (`:default-target`/`:initial`, present ONLY on `:source :default`),
;;     `:restored-config` (the recorded config that drove the restore,
;;     ABSENT on `:source :default`), `:resolved-leaf` (the concrete leaf
;;     entered), `:frame`.
;;   - `:rf.machine.history/recorded` — a history-bearing compound's exit
;;     cascade wrote its last-active configuration. Tags (spec/009):
;;     `:compound-path`, `:kind`, `:recorded-config` (the value written —
;;     deep leaf path / shallow child keyword), `:prev-config` (the value
;;     overwritten; ABSENT on the first-ever recording), `:frame`.
;;
;; Shapes match spec/009 §History trace events EXACTLY (rf2-mle6e.2).

(defn- emit-history-restored!
  "Per spec/009 §`:rf.machine.history/restored`. `source` is `:recorded` |
  `:default`. On the `:default` path `restored-config` is nil (no recording
  drove the restore) so it is OMITTED, and `fallback` (`:default-target` |
  `:initial`) names which fallback resolved the leaf; on the `:recorded`
  path `fallback` is absent and `restored-config` carries the recorded
  value."
  [machine compound-path resolved-leaf source kind restored-config fallback]
  (trace/emit! :rf.machine :rf.machine.history/restored
               (cond-> {:machine-id    (or (:rf/parent-id machine) (:id machine))
                        :compound-path compound-path
                        :kind          kind
                        :source        source
                        :resolved-leaf (vec resolved-leaf)
                        :frame         (:rf/frame machine)}
                 (= :default source) (assoc :fallback fallback)
                 (not= :default source) (assoc :restored-config restored-config))))

(defn- emit-history-recorded!
  "Per spec/009 §`:rf.machine.history/recorded`. `recorded` is the per-write
  map from `record-exit-history` — carries `:compound-path`,
  `:recorded-config`, `:kind`, and (when not the first-ever recording)
  `:prev-config`."
  [machine recorded]
  (trace/emit! :rf.machine :rf.machine.history/recorded
               (merge {:machine-id (or (:rf/parent-id machine) (:id machine))
                       :frame      (:rf/frame machine)}
                      recorded)))

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
  no-op for `nil` action-ref is not user-declared — skip the trace.

  Per rf2-82a0u every emit also carries `:phase` from the closed set
  `:exit / :transition / :entry / :always / :after-action /
  :initial-entry / :destroy-exit` so the Xray Handler section's
  LIFECYCLE rendering can group rows by phase without spec-walking
  at render time."
  [machine snap action-ref event phase]
  (if action-ref
    (let [f         (resolve-action machine action-ref)
          parent-id (or (:rf/parent-id machine) (:id machine))
          ;; Per rf2-ko8jb: epoch-capture admission requires `:frame`.
          frame-id  (:rf/frame machine)]
      (try
        (let [r (call-action f snap event)]
          (trace/emit! :rf.machine :rf.machine/action-ran
                       {:machine-id parent-id
                        :action-id  action-ref
                        :phase      phase
                        :input      {:data  (:data snap)
                                     :event event}
                        :outcome    (if (nil? r) :ok r)
                        :frame      frame-id})
          (or r {}))
        (catch #?(:clj Throwable :cljs :default) e
          (trace/emit! :rf.machine :rf.machine/action-ran
                       {:machine-id parent-id
                        :action-id  action-ref
                        :phase      phase
                        :input      {:data  (:data snap)
                                     :event event}
                        :outcome    :rf.error/action-threw
                        :exception  e
                        :frame      frame-id})
          (result/fail {:action-ref action-ref
                        :exception  e}))))
    {}))

;; ---- structured cascade steps (rf2-n9f4z) ---------------------------------
;;
;; Per Spec 005 §Transition cascade instrumentation: each cascade phase the
;; engine runs is recorded as one self-describing STEP map so tooling
;; (Xray's epoch panel — rf2-52u5n) can explain HOW a transition reached
;; its after-state without re-deriving the LCA geometry. A step carries:
;;
;;   {:kind   :exit | :action | :entry        ;; which cascade boundary
;;    :state  <state-path-vector>              ;; the state exited/entered;
;;                                             ;;   LCA-relative path for :action
;;    :region <region-name-or-nil>             ;; parallel region (nil flat/compound)
;;    :action <action-id-or-nil>               ;; the action that fired (nil = no
;;                                             ;;   :exit/:entry/:action declared)
;;    :data-delta {<k> <new-v>}                ;; the :data keys THIS step's action
;;                                             ;;   added/changed (empty when no
;;                                             ;;   action, or the action wrote no
;;                                             ;;   :data)
;;    :source :recorded | :default}            ;; ADDITIVE, history-only: present
;;                                             ;;   on an :entry step produced by a
;;                                             ;;   history restore (spec/009 line
;;                                             ;;   291), matching the
;;                                             ;;   :rf.machine.history/restored
;;                                             ;;   event's :source; ABSENT on every
;;                                             ;;   non-history step (rf2-mle6e.3)
;;
;; The step vector is built in `compute-cascade-paths` (which owns the
;; ordered prefix paths + action refs) and the per-step `:data-delta` is
;; filled in by `collect-actions` as it threads `:data` forward — the SAME
;; pass that runs the actions, so nothing is recomputed. `:microstep` steps
;; are appended by the `:always` loop in `machine-transition-single`.

(defn- data-delta
  "The map of keys whose value CHANGED (added or differs) from `before`
  to `after`. Empty map when nothing changed. Used to record each cascade
  step's `:data` contribution without carrying the whole (possibly large)
  `:data` map per step — the step delta is the minimal explanation of what
  the action did."
  [before after]
  (reduce-kv (fn [acc k v]
               (if (= v (get before k)) acc (assoc acc k v)))
             {}
             after))

(defn- collect-actions
  "Walk `step` maps in order, calling each step's `:action` with snap+event
  and threading the resulting :data updates forward (so each action sees
  the previous one's data). Returns a `result/ok` carrying
  `[final-snapshot fx-vec cascade-steps]` (the third slot rides the Result
  via `result/with-cascade`), or the `result/fail` Result the first
  throwing action produced — per Spec 005 §Errors, the cascade halts on
  the first throw and the snapshot does not commit.

  Per rf2-82a0u + rf2-n9f4z each input `step` is a map
  `{:kind <structural> :phase <driver> :action <ref> :state <path>
    :region <name>}`:
   - `:kind` is the STRUCTURAL boundary recorded on the cascade step —
     `:exit` / `:action` / `:entry` (the rf2-52u5n consumer contract); the
     destroy path passes `:exit`.
   - `:phase` is the DRIVER phase stamped on the `:rf.machine/action-ran`
     emit (rf2-82a0u) — `:exit` / `:entry` for cascade boundaries, the
     transition-phase (`:transition` / `:always` / `:after-action` /
     `:initial-entry`) for the action step, `:destroy-exit` for the
     destroy path. Defaults to `:kind` when absent.
   - `:action` may be nil — the iteration still records a step (so a state
     entered/exited WITHOUT an `:entry`/`:exit` action still shows in the
     cascade), but runs no action and contributes an empty `:data-delta`.

  Per rf2-n9f4z the `:data-delta` of each step is computed against the
  snapshot's `:data` immediately before the step's action ran, so the
  cascade explains the per-step `:data` contribution. The recorded step
  carries the STRUCTURAL fields only (`:kind` / `:state` / `:region` /
  `:action` / `:data-delta`) — the driver `:phase` is an input that routes
  the emit, not part of the step shape the consumer reads. The accumulated
  cascade-step vector rides the returned `:ok` Result via
  `result/with-cascade`; a `:fail` (a throwing action) carries no cascade —
  the snapshot does not commit, so the partial walk is not surfaced."
  [machine snap event steps]
  ;; Internal accumulator is a `[result cascade-steps]` tuple so the
  ;; cascade threads alongside the Result without widening `result/ok`'s
  ;; arity (which would churn every caller). `reduced` short-circuits on
  ;; the first throwing action, carrying the bare `:fail` Result.
  (let [acc (reduce
              (fn [[acc-r cascade] {:keys [kind phase action state region source]}]
                (result/with-ok [snap fx] acc-r
                  (let [before-data (:data snap)
                        emit-phase  (or phase kind)
                        ;; Per spec/009 §History trace events (line 291): a
                        ;; history-driven `:entry` step additively carries
                        ;; `:source` (set in `compute-cascade-paths`); a
                        ;; non-history step has none, so only stamp it when
                        ;; the input step supplied it.
                        base-step   (cond-> {:kind   kind
                                             :state  state
                                             :region region
                                             :action action}
                                      source (assoc :source source))]
                    (if action
                      (let [r (run-action machine snap action event emit-phase)]
                        (if (result/fail? r)
                          (reduced [r cascade])
                          (do
                            ;; Per Spec 005 §Hard-disallow `:db` (005:463): a
                            ;; machine action's effect map MUST NOT carry `:db`.
                            ;; When present, emit the structured error and DROP
                            ;; the `:db` key — the remaining `:data` / `:fx`
                            ;; effects flow through. Canonical id / op-type /
                            ;; tags / recovery per Spec 009 §Error event
                            ;; catalogue (Ownership.md:48):
                            ;; `:rf.error/machine-action-wrote-db`, op-type
                            ;; `:error`, tags `{:machine-id :action-id
                            ;; :state-path :offending-value}`, recovery
                            ;; `:logged-and-skipped`.
                            (when (and (map? r) (contains? r :db))
                              (trace/emit-error! :rf.error/machine-action-wrote-db
                                                 {:machine-id      (or (:rf/parent-id machine)
                                                                       (:id machine))
                                                  :action-id       action
                                                  :state-path      (:state snap)
                                                  :offending-value (:db r)
                                                  ;; Per rf2-ko8jb: epoch-capture
                                                  ;; admission requires `:frame`.
                                                  :frame           (:rf/frame machine)
                                                  :recovery        :logged-and-skipped}))
                            (let [new-data (cond-> before-data
                                             (contains? r :data) (merge (:data r)))
                                  new-snap (assoc snap :data new-data)
                                  new-fx   (vec (concat fx (or (:fx r) [])))
                                  step     (assoc base-step
                                                  :data-delta (data-delta before-data new-data))]
                              [(result/ok new-snap new-fx) (conj cascade step)]))))
                      ;; No action declared for this boundary — record the
                      ;; state-entered/exited step anyway (empty delta) so the
                      ;; cascade carries the full configuration walk, then
                      ;; carry the snapshot + fx unchanged.
                      [acc-r (conj cascade (assoc base-step :data-delta {}))]))))
              [(result/ok snap []) []]
              steps)
        [final-r cascade] acc]
    (result/with-cascade final-r cascade)))

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
                        (trace/emit! :rf.machine :rf.machine.timer/skipped-on-server
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
                        (trace/emit! :rf.machine :rf.machine.timer/scheduled
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
  runtime-owned `[:rf/runtime :machines :spawned <parent-id> <invoke-id>]` slot in app-db.

  Per Spec 005 §Spawn-and-join via `:spawn-all` (rf2-6vmw): on exit, tear
  down EVERY child the parent spawned plus the join-state slot. The
  destroy-fx handler reads the map at `[:rf/runtime :machines :spawned <parent> <invoke-id>]`
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
  "Allocate one spawned-id from `spawn-spec`'s `:machine-id` against `snap`'s
  in-snapshot counter (rf2-gr8q). When `spawn-spec` carries an explicit
  `:spawn-id` literal (per-state singleton) the counter is NOT bumped.
  Returns `[snap' spawned-id]`."
  [snap spawn-spec]
  (if-let [explicit (:spawn-id spawn-spec)]
    [snap explicit]
    (allocate-spawned-id snap (:machine-id spawn-spec))))

(defn- apply-on-spawn
  "Run `spawn-spec`'s `:on-spawn` advisory callback against `snap`'s `:data`.
  Per Spec 005 §Declarative `:spawn` (rf2-grw4i / rf2-v0rrr), the signature
  is `(fn [{:keys [data id]}] _)` — context-map input, advisory return
  (any return value is DROPPED). Per rf2-t07u (Option A revised) the
  runtime tracks the spawn-id at `[:rf/runtime :machines :spawned parent-id invoke-id]`;
  `:on-spawn` is purely observational — callers needing snapshot-level
  side effects emit `[:rf.machine/update-snapshot {:rf/machine-id <id>
  :rf/patch {...}}]` from a regular `:action`'s `:fx` vector instead (the
  fx is registered in `re-frame.machines` and handled by
  `re-frame.machines.lifecycle-fx.update-snapshot`).

  No-silent-swallow (rf2-dtth6): the snapshot is returned UNCHANGED — a
  callback that returns a non-nil value (e.g. the canonical-looking
  `(assoc data :pending id)`) has that value silently dropped, which is the
  exact trap the advisory contract sets. Surface it: emit a dev-only
  `:rf.warning/on-spawn-return-ignored` advisory naming the three working
  id-recording alternatives. `trace/emit!` is gated on
  `interop/debug-enabled?` (Closure DCE / JVM flag) so this is production-
  free and adds no module-level mutable state — the engine stays a pure
  function of `[machine snapshot event]`."
  [machine snap spawn-spec spawned-id]
  (when-let [f (let [aref (:on-spawn spawn-spec)]
                 (when aref
                   (or (chase-ref (:on-spawn-actions machine) aref)
                       (chase-ref (:actions machine) aref))))]
    (let [ret (f {:data (:data snap) :id spawned-id})]
      (when (some? ret)
        (trace/emit! :warning :rf.warning/on-spawn-return-ignored
                     {:machine-id (or (:rf/parent-id machine) (:id machine))
                      :spawned-id spawned-id
                      :returned   ret
                      :remedy     [:system-id
                                   [:rf/runtime :machines :spawned :<parent> :<invoke-id>]
                                   :rf.machine/update-snapshot]}))))
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
  [spawn-spec mat-snap event spawned-id args-builder failure-extra]
  (let [mat-result (if (contains? spawn-spec :data)
                     (materialise-data (:data spawn-spec) mat-snap event)
                     [::ok-data nil])]
    (if (result/fail? mat-result)
      (result/fail-with mat-result failure-extra)
      (let [mat-data    (second mat-result)
            spawn-spec' (if (contains? spawn-spec :data)
                          (assoc spawn-spec :data mat-data)
                          spawn-spec)
            spawn-args  (args-builder spawn-spec' spawned-id)]
        (result/ok spawn-args [[:rf.machine/spawn spawn-args]])))))

;; ---- :spawn / :spawn-all spawn reducers ----------------------------------

(defn- handle-spawn-decl
  "Handle the `:spawn` branch of the spawn reducer in
  `apply-transition-once`. Allocates one spawned-id, delegates the
  `:data` materialisation and spawn-args assembly to `spawn-one`, then
  runs the `:on-spawn` advisory callback.

  Returns `[snap-after acc-fx']` for the reducer, or a `reduced` wrapper
  around a `result/fail` Result (stamped with
  `:action-ref :rf.spawn/data-fn` and `:spawn-id`) on `:data` failure."
  [machine parent-id s acc-fx prefix n event]
  (let [spawn-spec   (:spawn n)
        invoke-id    (vec prefix)
        [s-alloc id] (allocate-one s spawn-spec)
        args-builder (fn [spec' spawned-id]
                       (-> spec'
                           (assoc :id-prefix     (:machine-id spec'))
                           (assoc :rf/spawned-id spawned-id)
                           (assoc :rf/parent-id  parent-id)
                           (assoc :rf/spawn-id  invoke-id)))
        spawn-r      (spawn-one spawn-spec s-alloc event id args-builder
                                {:action-ref :rf.spawn/data-fn
                                 :spawn-id  invoke-id})]
    (if (result/fail? spawn-r)
      (reduced spawn-r)
      (let [spawn-fx (result/fx spawn-r)
            s'       (apply-on-spawn machine s-alloc spawn-spec id)]
        [s' (into acc-fx spawn-fx)]))))

(defn- handle-spawn-all-decl
  "Handle the `:spawn-all` branch of the spawn reducer in
  `apply-transition-once`. Per Spec 005 §Spawn-and-join via `:spawn-all`
  (rf2-6vmw), `:spawn-all` is spawn-and-join sugar over N parallel
  `:spawn`'s plus a join condition. The implementation mirrors the
  concept:

   1. Allocate one spawned-id per child up-front (thread the snapshot's
      counter through children in declaration order).
   2. Build the join-state seed map and the `:rf.machine/spawn-all-init`
      fx that seeds `[:rf/runtime :machines :spawned <parent> <invoke-id>]` in app-db.
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
  (let [spawn-all-spec (:spawn-all n)
        children  (:children spawn-all-spec)
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
                      :spec      spawn-all-spec
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
  of `state` declares `:final? true`. The lifecycle-handler boundary calls
  this on the POST-transition snapshot to RECOMPUTE finality and, when
  true, fire `:on-done` + auto-destroy. Finality is a pure recompute from
  the post-transition `:state` — it is NOT stamped onto the snapshot
  (there is no `:rf/finished?` slot; per Spec 005 §Persistence posture the
  pure `machine-transition` surface stays free of runtime-only
  bookkeeping).

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
  `:exit` action threw. Callers (destroy / finalize / `:spawn-all` per-
  child teardown) emit a `[:rf.machine/bootstrap-exit]` synthetic event so
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
        ;; Per rf2-82a0u every action-ran emit carries `:phase`; destroy-
        ;; time exit cascades stamp `:destroy-exit` so the Xray Handler
        ;; section can attribute the action to the actor-teardown cause.
        ;; Per rf2-n9f4z `collect-actions` walks cascade STEP maps. The
        ;; destroy-time exit cascade records one `:exit` step per active
        ;; node carrying an `:exit` action (deepest-first), so an actor
        ;; teardown surfaces the same structured cascade shape as a
        ;; transition-driven exit. Nodes without an `:exit` action are
        ;; skipped here (unlike the transition cascade, which records every
        ;; configuration boundary) — destroy is a teardown, not a
        ;; configuration walk a tool renders the geometry of.
        region      (:rf/region machine)
        steps       (->> (reverse active-pairs)
                         (keep (fn [[prefix n]]
                                 (when (:exit n)
                                   ;; Structural `:kind :exit` (the cascade
                                   ;; step's shape); driver `:phase
                                   ;; :destroy-exit` (the `action-ran` emit
                                   ;; phase, rf2-82a0u).
                                   {:kind :exit :phase :destroy-exit
                                    :state (vec prefix) :region region
                                    :action (:exit n)})))
                         vec)]
    (collect-actions machine snapshot [:rf.machine/destroy-exit] steps)))

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
;;                            `handle-spawn-decl` / `handle-spawn-all-decl`.
;;                            Threads snapshot + acc-fx; short-circuits to
;;                            `result/fail` on `:data`-fn throws.

(defn- compute-cascade-paths
  "Phase 1 — derive the transition's geometry. Returns a map with:
    :src-path       — source state path (vector).
    :target-leaf    — initial-cascaded target path (nil for internal).
    :internal?      — true iff the transition has no `:target`.
    :lca-len        — common-prefix length of src and target.
    :cascade-steps  — per rf2-n9f4z, the vec of cascade STEP maps in
                      execution order (`:exit` × N deepest-first → the
                      transition `:action` @ LCA → `:entry` × N
                      shallowest-first + initial-descent) — the input to
                      `collect-actions`. Each step is
                      `{:kind <phase> :state <path> :region <name-or-nil>
                        :action <ref-or-nil>}`; `:kind` doubles as the
                      `action-ran` `:phase` (rf2-82a0u) AND the structured
                      cascade step's kind (rf2-52u5n consumer contract).
                      The caller (`apply-transition-once`) supplies the
                      transition-phase per cascade-driver (`:transition` /
                      `:always` / `:after-action` / `:initial-entry`); a
                      `:initial-entry` driver collapses entries to
                      `:initial-entry`. A boundary with no declared
                      `:exit`/`:entry` action still yields a step (`:action`
                      nil) so the configuration walk is complete; the
                      transition `:action` step appears only when an
                      `:action` was declared. `collect-actions` fills each
                      step's `:data-delta` as it threads `:data`.
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
                      external contract requires).
    :history-restore — per Spec 005 §Restoring (rf2-mle6e.3): present iff
                      this transition resolved to a `:type :history`
                      pseudo-state — the spec/009 `:rf.machine.history/
                      restored` tag bag `{:compound-path :resolved-leaf
                      :source :kind (+:restored-config|+:fallback)}`
                      (`:source` is `:recorded` | `:default`). The
                      pseudo-state is swapped for `:resolved-leaf` BEFORE the
                      LCA geometry above, so `:target-leaf` / `:lca-len` /
                      `:entered-pairs` already reflect the resolved path.
                      `apply-transition-once` emits the
                      `:rf.machine.history/restored` trace from it."
  [machine snapshot transition transition-phase]
  (let [src-path      (state-path (:state snapshot))
        decl-path     (:decl-path transition (vec (take 1 src-path)))
        raw-target    (:target transition)
        internal?     (nil? raw-target)
        ;; The target BEFORE initial-cascade re-descent. Needed to detect
        ;; the external self-transition: a `:target` (the `:same-state`
        ;; sentinel, or a keyword naming the declaring state's own key)
        ;; that resolves to the declaring state itself.
        target-base0  (target-path decl-path raw-target)
        ;; Per Spec 005 §Restoring — on transition to the pseudo-state: when
        ;; `target-base0` lands on a history pseudo-state, resolve it to the
        ;; recorded (or default / dangling-fallback) leaf BEFORE the LCA
        ;; geometry. The resolved leaf is what the entry cascade enters and
        ;; what the snapshot's `:state` records — the pseudo-state is never a
        ;; configuration member (the 46ban precedent: resolve the real path,
        ;; then run the standard geometry on it). `:history-restore` rides
        ;; the result so `apply-transition-once` can emit the
        ;; `:rf.machine.history/restored` trace.
        hist-node     (when (and (not internal?) target-base0)
                        (let [n (node-at machine target-base0)]
                          (when (history-node? n) n)))
        history-restore (when hist-node
                          (let [{:keys [leaf source restored-config fallback]}
                                (resolve-history-target machine snapshot
                                                        target-base0 hist-node)]
                            ;; The spec/009 `:rf.machine.history/restored` tag
                            ;; bag, threaded to `apply-transition-once`'s emit.
                            ;; `:restored-config` rides only on `:recorded`;
                            ;; `:fallback` only on `:default` (mirrors the emit's
                            ;; cond->). `:kind` maps the grammar `:deep?`.
                            (cond-> {:compound-path (history-key machine (vec (drop-last target-base0)))
                                     :resolved-leaf leaf
                                     :source        source
                                     :kind          (if (true? (:deep? hist-node)) :deep :shallow)}
                              (= :recorded source) (assoc :restored-config restored-config)
                              (= :default source)  (assoc :fallback fallback))))
        ;; The effective base after history resolution: the resolved leaf
        ;; (already fully cascaded to a leaf) when restoring, else the
        ;; declared target.
        target-base   (if history-restore (:resolved-leaf history-restore) target-base0)
        target-leaf   (some->> target-base (initial-cascade machine))
        ;; Per Spec 005 §Self-transitions + §Entry/exit cascading: an
        ;; EXTERNAL self-transition re-enters the declaring state — `:exit`
        ;; then transition `:action` then `:entry` all fire, the
        ;; configuration unchanged. The LCA-driven cascade fires `:exit` /
        ;; `:entry` only on states BELOW the LCA, so a self-target's plain
        ;; common-prefix LCA (= the full declaring path) would fire neither.
        ;; Pull the LCA up to the declaring state's PARENT so the declaring
        ;; state lands in both the exit and entry cascades. A self-target on
        ;; a compound state re-enters it AND re-runs its `:initial` child
        ;; cascade (`target-leaf` re-descended above). An INTERNAL
        ;; self-transition (no `:target`) is untouched — it never reaches
        ;; here (`internal?` short-circuits exit/entry below). rf2-46ban.
        self-transition? (and (not internal?) (= target-base decl-path))
        lca-len       (cond
                        internal?        (count src-path)
                        self-transition? (dec (count target-base))
                        :else            (common-prefix-length src-path target-leaf))
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
        ;; Per rf2-82a0u: phase per cascade-slot per `transition-phase`.
        ;; Bootstrap entries collapse to `:initial-entry` — the bead's
        ;; closed set distinguishes "entry from the bootstrap cascade"
        ;; from "entry from a regular `:on`-driven transition".
        entry-phase   (if (= :initial-entry transition-phase) :initial-entry :entry)
        ;; Per rf2-n9f4z: the cascade STEP maps `collect-actions` walks —
        ;; one per boundary, in exit (deepest-first) → action @ LCA → entry
        ;; (shallowest-first + initial-descent) order. Each carries the
        ;; state path it fires at + the region (for parallel machines) so
        ;; tooling can render the structured cascade (rf2-52u5n) without
        ;; re-deriving the LCA geometry.
        ;;
        ;; Two ORTHOGONAL dimensions ride each step:
        ;;   :kind  — the STRUCTURAL boundary: `:exit` / `:action` / `:entry`
        ;;            (closed set; `:microstep` is appended by the `:always`
        ;;            loop). This is the consumer (rf2-52u5n) contract.
        ;;   :phase — the action-ran DRIVER phase (rf2-82a0u): `:exit` /
        ;;            `:entry` for cascade boundaries, but the transition
        ;;            `:action` carries the caller-supplied `transition-phase`
        ;;            (`:transition` / `:always` / `:after-action` /
        ;;            `:initial-entry`), and an `:initial-entry` driver
        ;;            collapses entries to `:initial-entry`. `:phase` is what
        ;;            `run-action` stamps on the `:rf.machine/action-ran`
        ;;            emit; keeping it distinct from `:kind` means the
        ;;            structural step shape doesn't smear the driver phase.
        ;;
        ;; A boundary with NO `:exit`/`:entry` action still yields a step
        ;; (`:action` nil) so the configuration walk is complete; the
        ;; transition `:action` step is recorded only when an `:action` was
        ;; declared (a bare nil transition-action is not a cascade boundary).
        region        (:rf/region machine)
        exit-steps    (when-not internal?
                        (mapv (fn [[prefix n]]
                                {:kind :exit :phase :exit :state (vec prefix)
                                 :region region :action (:exit n)})
                              (reverse exited-pairs)))
        action-steps  (when (:action transition)
                        [{:kind :action :phase transition-phase :state (vec decl-path)
                          :region region :action (:action transition)}])
        ;; Per spec/009 §History trace events (line 291): each `:entry` step
        ;; produced by a history restore additively carries `:source`
        ;; (`:recorded`|`:default`) matching the `:rf.machine.history/restored`
        ;; event's `:source`; a step with no `:source` key was not
        ;; history-driven. All entry steps of a history-driven transition came
        ;; from the resolved leaf's entry cascade, so all carry the source.
        hist-source   (:source history-restore)
        entry-steps   (when-not internal?
                        (mapv (fn [[prefix n]]
                                (cond-> {:kind :entry :phase entry-phase :state (vec prefix)
                                         :region region :action (:entry n)}
                                  hist-source (assoc :source hist-source)))
                              entered-pairs))
        cascade-steps (vec (concat exit-steps action-steps entry-steps))
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
     :cascade-steps cascade-steps
     :after-bump-paths after-bump-paths
     ;; Per Spec 005 §Restoring (rf2-mle6e.3): present iff this transition
     ;; resolved to a history pseudo-state — the spec/009 `:rf.machine.
     ;; history/restored` tag bag `{:compound-path :resolved-leaf :source
     ;; :kind (+:restored-config|+:fallback)}`. `apply-transition-once`
     ;; emits the `:rf.machine.history/restored` trace from it.
     :history-restore history-restore}))

(defn- run-cascade
  "Phase 2 — run the ordered cascade (`exit` deepest-first → `action`
  at LCA → `entry` shallowest-first) via `collect-actions`. Returns the
  Result from `collect-actions` — either `result/ok` with the post-cascade
  snapshot + accumulated fx (and the structured `::cascade` step vector
  via `result/with-cascade` — rf2-n9f4z), or a `result/fail` carrying the
  throwing action's diagnostic map."
  [machine snapshot event cascade]
  (collect-actions machine snapshot event (:cascade-steps cascade)))

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
                       (handle-spawn-decl machine parent-id s acc-fx prefix n event)

                       (:spawn-all n)
                       (handle-spawn-all-decl machine parent-id s acc-fx prefix n event)

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
  recording where in the state-path tree the transition was declared.

  Per rf2-82a0u `transition-phase` is the closed-set keyword stamped
  on the transition's `:action` `action-ran` emit — one of
  `:transition` (regular `:on` match), `:always` (eventless step),
  `:after-action` (timer-driven), `:initial-entry` (bootstrap cascade).
  Exit / entry actions stamp `:exit` / `:entry` regardless of the
  driver. The 4-arity defaults to `:transition` for the conformance-
  corpus / JVM-fixture callers that exercise `apply-transition-once`
  directly (pure-fn tests of the geometry, not the live engine)."
  ([machine snapshot event transition]
   (apply-transition-once machine snapshot event transition :transition))
  ([machine snapshot event transition transition-phase]
  (let [cascade  (compute-cascade-paths machine snapshot transition transition-phase)
        cascade-r (run-cascade machine snapshot event cascade)]
    (if (result/fail? cascade-r)
      (result/fail-with cascade-r {:decl-path  (:decl-path cascade)
                                   :transition transition
                                   :state-path (:src-path cascade)})
      (result/with-ok [snap-after fx] cascade-r
        (let [;; Per rf2-n9f4z: the structured cascade steps `collect-actions`
              ;; recorded ride `cascade-r` via `::cascade`; re-stamp them onto
              ;; the final Result (the `result/ok` below builds a fresh Result
              ;; that would otherwise drop them) so `machine-transition-single`
              ;; can accumulate the macrostep's full step sequence.
              cascade-steps   (result/cascade cascade-r)
              snap-committed  (commit-snapshot machine snapshot snap-after cascade)
              ;; Per Spec 005 §Recording — on compound-state exit
              ;; (rf2-mle6e.3): as part of the exit-cascade commit, write
              ;; each history-bearing exited compound's last-active config
              ;; into `:rf/history`, keyed by the (region-qualified)
              ;; declaration path. The active config recorded is the
              ;; PRE-transition source leaf (`:src-path`).
              [snap-final history-recorded]
                              (if (:internal? cascade)
                                [snap-committed []]
                                (record-exit-history machine snap-committed
                                                     (:src-path cascade)
                                                     (:lca-len cascade)))
              ;; Per Spec 005 §Restoring + Spec 009 (mle6e.2): emit the
              ;; history traces. `restored` when this transition resolved a
              ;; history pseudo-state; `recorded` once per history-bearing
              ;; compound exited.
              _ (when-let [{:keys [compound-path resolved-leaf source kind
                                   restored-config fallback]}
                           (:history-restore cascade)]
                  (emit-history-restored! machine compound-path resolved-leaf
                                          source kind restored-config fallback))
              _ (doseq [rec history-recorded]
                  (emit-history-recorded! machine rec))
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
                (result/with-cascade
                  (result/ok snap-after-spawns all-fx)
                  cascade-steps))))))))))

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
  failed.

  `start-depth` seeds the loop's raise-depth counter with the count of
  raises ALREADY processed transitively before this drain — see
  `machine-transition-single` (rf2-b88nm). Threading it makes the depth
  bound *transitive*: a self-chaining single-raise (state A raises an
  event into state B, which itself raises, …) recurses through nested
  `machine-transition-single` → `drain-raises` frames, and each frame's
  drain continues counting from where its caller left off. Without the
  seed every nested drain would restart at 0, so a runaway self-chain
  would blow the host call stack instead of firing the clean
  `:rf.error/machine-raise-depth-exceeded`. Breadth (raise siblings in a
  single fx vector) and transitive depth (nested raise chains) both count
  toward the same `:raise-depth-limit`."
  [machine snapshot fx-vec depth-limit start-depth]
  (loop [pending fx-vec
         accum   []
         snap    snapshot
         depth   start-depth]
    (cond
      ;; `>=` (not `>`) so the `:raise` drain permits exactly `depth-limit`
      ;; recursions (depths 0..limit-1) — parity with the `:always` microstep
      ;; loop's `(>= depth always-limit)` bound (rf2-r26e2). Both default 16
      ;; with identical intent per Spec 005 §Bounded depth (005:1276).
      (>= depth depth-limit)
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
          ;; Pass `(inc depth)` as the nested call's transitive seed so a
          ;; raise that itself raises keeps counting against this drain's
          ;; budget rather than restarting at 0 (rf2-b88nm).
          (let [step-result (machine-transition-single machine snap args (inc depth))]
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
      (trace/emit! :rf.machine :rf.machine.timer/stale-after
                   {:state           (:state match)
                    :delay           (:delay match)
                    :scheduled-epoch (:scheduled-epoch match)
                    :current-epoch   (:current-epoch match)
                    :frame           frame-id
                    :recovery        :replaced-with-default}))
    (when (:guard-suppressed? match)
      (trace/emit! :rf.machine :rf.machine.timer/fired
                   {:state  (:state match)
                    :delay  (:delay match)
                    :epoch  (:epoch match)
                    :fired? false
                    :frame  frame-id}))
    (when (and (not (:stale? match))
               (not (:guard-suppressed? match))
               (:delay match))
      (trace/emit! :rf.machine :rf.machine.timer/fired
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
  through to this fn.

  `raise-depth` is the count of `:raise` recursions already consumed
  before reaching this call. The public entry passes 0; `drain-raises`
  passes its running count so a self-chaining single-raise accumulates
  transitive depth against the SAME `:raise-depth-limit` rather than
  resetting per nested call (rf2-b88nm). It seeds the `:raise` drains
  below — both the pre-commit drain and the per-`:always`-step drain — so
  raises emitted anywhere in this macrostep continue counting from the
  inbound transitive depth."
  ([machine snapshot event]
   (machine-transition-single machine snapshot event 0))
  ([machine snapshot event raise-depth]
  (let [always-limit (get machine :always-depth-limit always-depth-limit-default)
        raise-limit  (get machine :raise-depth-limit  raise-depth-limit-default)
        path             (state-path (:state snapshot))
        match            (pick-transition machine path event snapshot)
        ;; Per Spec 005 §Parallel regions (005:1168-1171): a region reports
        ;; whether the inbound event resolved to a real transition so the
        ;; parent can warn exactly once when EVERY region declines. A stale
        ;; / guard-suppressed timer is not a handled user event.
        handled?         (boolean (and match
                                       (not (:stale? match))
                                       (not (:guard-suppressed? match))))
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
          ;; Per rf2-82a0u: the transition's `:action` `action-ran` emit
          ;; carries `:phase :after-action` when the match came from a
          ;; firing `:after` timer (the synthetic `:rf.machine.timer/
          ;; after-elapsed` event), `:transition` otherwise.
          (apply-transition-once
            machine snapshot event
            (assoc (:transition match) :decl-path (:decl-path match))
            (if (:delay match) :after-action :transition))

          :else
          (do
            ;; No transition matched at any level (including the root `:on`
            ;; fallback / its `:*` wildcard). Per Spec 005 §Transition
            ;; resolution the runtime emits the BENIGN no-op trace and
            ;; leaves the snapshot unchanged — xstate-v5 parity: v5 removed
            ;; the `strict` flag, so an unhandled event is ignored, not an
            ;; error. The canonical id / op-type / tags are owned by Spec 009
            ;; §`:op-type` vocabulary: `:rf.machine.event/unhandled-no-op`,
            ;; op-type `:rf.machine` (machine-activity family, NOT `:error` /
            ;; `:warning`), tags `{:machine-id :event :state}`. Benign is not
            ;; invisible — xstate emits nothing here, but re-frame2 keeps an
            ;; info-grade observability trace so a debugger can report it.
            ;; Because the op-type is `:rf.machine` (not a severity
            ;; discriminator), the Xray issue-projection predicate does not
            ;; classify it as an issue — no pink wash, no ribbon entry, for
            ;; free. (rf2-ugdas; retires `:rf.error/machine-unhandled-event`.)
            ;;
            ;; rf2-t4582 — reserved-`:rf/*` lifecycle carve-out (a conscious
            ;; refinement of rf2-ugdas). The no-op classifies an unknown
            ;; USER event; framework lifecycle traffic — the synthetic
            ;; creation marker `[:rf.machine/start]` (cascade-threaded
            ;; `:event` placeholder), the spawn kick-off
            ;; `[:rf.machine.spawn/spawned]`, the stories-runtime lifecycle
            ;; pings — is NOT an unknown user event, so `unhandled-event-
            ;; no-op?` gates the emit. Severity is unchanged (nothing
            ;; throws); only the SEMANTIC classification is restored, so
            ;; the machine's BIRTH renders its `:initial-entry` cascade,
            ;; not a no-op. This matches xstate (its own `xstate.init` runs
            ;; the initial-entry and is not reported as unhandled).
            ;;
            ;; A region of a parallel-region machine carries `:rf/region`;
            ;; per Spec 005 §Transition broadcast a single declining region
            ;; MUST NOT emit — only when EVERY region declines does the
            ;; machine emit once. That aggregate emission lives in
            ;; `parallel-machine-transition`, so suppress the per-region
            ;; emission here.
            (when (and (nil? (:rf/region machine))
                       (unhandled-event-no-op? event))
              (trace/emit! :rf.machine :rf.machine.event/unhandled-no-op
                           {:machine-id (or (:rf/parent-id machine) (:id machine))
                            :event      event
                            :state      (:state snapshot)
                            ;; Per rf2-ko8jb: epoch-capture admission
                            ;; requires `:frame`.
                            :frame      (:rf/frame machine)}))
            (result/ok snapshot [])))]
    (result/with-handled
     (if (result/fail? result-after-event)
      result-after-event
      (result/with-ok [snap-after-event fx-after-event] result-after-event
        (let [raised (drain-raises machine snap-after-event fx-after-event raise-limit raise-depth)
              ;; Per rf2-n9f4z: seed the macrostep cascade with the
              ;; event-driven transition's exit/action/entry steps; the
              ;; `:always` loop appends one `:microstep` step per eventless
              ;; iteration (carrying that microstep's own nested cascade).
              ;; The accumulated vector is the structured explanation the
              ;; outer `:rf.machine/transition` trace carries (rf2-52u5n).
              base-cascade (result/cascade result-after-event)]
          (if (result/fail? raised)
            raised
            (result/with-ok [snap-after-raise fx-after-raise] raised
              ;; Step 4: :always microstep loop. Track visited state-paths so that,
              ;; on depth-limit abort, we can report the path AND fully roll back to
              ;; the original input snapshot — the macrostep is atomic per Spec 005.
              (loop [snap    snap-after-raise
                     fx      fx-after-raise
                     depth   0
                     visited [(:state snap-after-raise)]
                     cascade base-cascade]
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
                      ;; Macrostep rolls back atomically — no cascade survives
                      ;; the abort.
                      (result/ok snapshot []))

                  :else
                  (let [snap-path (state-path (:state snap))
                        always-m  (pick-always-transition machine snap-path snap)]
                    (if (nil? always-m)
                      ;; Macrostep fixed-point reached. Recompute the
                      ;; active-configuration tag union on the committed snapshot
                      ;; AFTER the new state is settled but BEFORE traces fire
                      ;; (so the outer handler's `:rf.machine/transition` trace
                      ;; carries the new tag set). `depth` is the count of
                      ;; `:always` microsteps taken — stamped onto the Result
                      ;; via `::microsteps` (per Spec 005 §Trace events) so
                      ;; `commit-or-finalize` can carry `:microsteps` on the
                      ;; outer `:rf.machine/transition` trace. Per rf2-n9f4z
                      ;; the accumulated `cascade` rides via `::cascade`.
                      (-> (result/ok (commit-tags machine snap) fx)
                          (result/with-microsteps depth)
                          (result/with-cascade cascade))
                      ;; Per rf2-82a0u: `:always` microstep's transition
                      ;; `:action` `action-ran` emit carries `:phase
                      ;; :always` so the Handler section can group
                      ;; eventless cascades distinctly from `:on`-driven
                      ;; transitions.
                      (let [step-result (apply-transition-once machine snap nil
                                                                (:transition always-m)
                                                                :always)]
                        (if (result/fail? step-result)
                          step-result
                          (result/with-ok [snap2 fx2] step-result
                            ;; Per Spec 005 §Trace events: one
                            ;; `:rf.machine.microstep/transition` per microstep,
                            ;; carrying the from/to states and the 0-based
                            ;; microstep index, so visualisers/debuggers see the
                            ;; inner `:always` cascade the outer trace hides.
                            ;; Per rf2-ejtpd: stamp `:source :always` so the
                            ;; trigger-kind classifier is uniform with the
                            ;; dispatch-envelope vocabulary (Spec-Schemas
                            ;; §`:rf/dispatch-envelope`). `:always` microsteps
                            ;; do not produce their own envelope (intra-
                            ;; macrostep); the trace is the surface where the
                            ;; closed-set value is observable.
                            (trace/emit! :rf.machine :rf.machine.microstep/transition
                                         {:machine-id     (or (:rf/parent-id machine)
                                                              (:id machine))
                                          :from           (:state snap)
                                          :to             (:state snap2)
                                          :microstep-index depth
                                          :source         :always
                                          ;; Per rf2-ko8jb: epoch-capture
                                          ;; admission requires `:frame`.
                                          :frame          (:rf/frame machine)})
                            ;; Per rf2-n9f4z: append a `:microstep` cascade
                            ;; step carrying the microstep's own nested
                            ;; exit/action/entry `:steps` (from the eventless
                            ;; transition's `apply-transition-once` cascade) so
                            ;; the eventless cascade is explainable alongside
                            ;; the headline transition rather than hidden
                            ;; behind a bare count.
                            (let [micro-step {:kind            :microstep
                                              :region          (:rf/region machine)
                                              :microstep-index depth
                                              :from            (:state snap)
                                              :to              (:state snap2)
                                              :steps           (result/cascade step-result)}]
                              ;; Seed the per-`:always`-step drain with the
                              ;; macrostep's inbound transitive `raise-depth`
                              ;; (rf2-b88nm) so raises emitted by an `:always`
                              ;; cascade reached via a raise chain keep counting
                              ;; against the same `:raise-depth-limit`.
                              (let [raised2 (drain-raises machine snap2 fx2 raise-limit raise-depth)]
                                (if (result/fail? raised2)
                                  raised2
                                  (result/with-ok [snap3 fx3] raised2
                                    (recur snap3
                                           (vec (concat fx fx3))
                                           (inc depth)
                                           (conj visited (:state snap3))
                                           (conj cascade micro-step))))))))))))))))))
     handled?))))
