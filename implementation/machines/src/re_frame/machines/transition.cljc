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

(def done-event-id
  "The reserved inner event-id raised when a compound / parallel node reaches
  its done configuration — re-frame2's `done.state.<id>` (XState v5 `onDone` /
  SCXML §3.7). The completed node's declaration path rides as the single arg:
  `[:rf.machine/done <node-path>]`. Per Conventions.md §The single-root
  reserved set the id lives under the framework-reserved `:rf.machine/*`
  family (so an enclosing machine never mistakes it for an unknown USER event
  — `unhandled-event-no-op?` already exempts the whole `:rf/*` root). Defined
  here (above the resolution helpers) so `pick-transition` /
  `pick-done-transition` can special-case it. See §The done-state signal
  helpers (`compound-done-paths` / `done-raise-fx` / `apply-on-done-action`)
  below for the full mechanism (rf2-bnjb3 / rf2-zlmz7)."
  :rf.machine/done)

(def spawn-error-event-id
  "The reserved inner event-id dispatched into a PARENT machine when one of
  its `:spawn`-spawned children FAILS — re-frame2's spelling of XState v5
  `invoke onError` (control flow, not just observability — rf2-5hlsh). Two
  triggers raise it (both fired from the child's finalize / action-exception
  path in `lifecycle-fx.finalize` / `lifecycle-fx.registration`):

    1. the child reaches a designated ERROR `:final?` leaf (`:error? true`);
    2. an uncaught child action exception (`:rf.error/machine-action-exception`).

  The dispatched event shape is `[<parent-id> [:rf.machine.spawn/error
  <invoke-id> <error>]]` — `<invoke-id>` is the absolute prefix-path of the
  parent's `:spawn`-bearing state (so the resolver routes to the right
  `:spawn :on-error`), `<error>` is the error payload (the child's
  `:output-key` slot for the error-leaf trigger, or the exception envelope for
  the action-exception trigger). `pick-transition` special-cases it via
  `pick-spawn-error-transition`, which resolves the active `:spawn`-bearing
  state's `:on-error` `:on`-shaped transition spec at that state's own level —
  SYMMETRIC with the `:spawn :on-done` teardown hook, but a TRANSITION
  (parent state change) rather than a `:data`-only callback. Lives under the
  framework-reserved `:rf.machine.spawn/*` family (so `unhandled-event-no-op?`
  exempts it). See `pick-spawn-error-transition` below."
  :rf.machine.spawn/error)

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
;;                                         (+ :tags :all-state for a region —
;;                                          see the cross-region note below)
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
;;
;; ---- cross-region guard/action context (rf2-46ly6 / rf2-69d1n) ------------
;;
;; XState v5 / SCXML gold standard: a parallel region's guard can predicate
;; on a SIBLING region's active state via `stateIn(stateValue)` (XState v5
;; `xstate/guards`) / the `In(stateID)` predicate (W3C SCXML B.1). This is
;; the canonical orthogonal-region coordination primitive — region A's
;; `:submit` guarded by "region B is in `:valid`".
;;
;; re-frame2 expresses this WITHOUT a separate `stateIn` primitive (per
;; rf2-69d1n — behavioural parity, not API mimicry). When `call-guard` /
;; `call-action` run for a REGION of a parallel machine, the context map
;; gains two cross-region keys, threaded in by `parallel/reduce-regions`:
;;
;;   :tags       — the MACHINE-WIDE active-configuration tag union across
;;                 EVERY region (the coarse `stateIn` substitute; a sibling
;;                 region advertises a state-tag and any region's guard
;;                 reads `(contains? (:tags ctx) :some/tag)`). It reflects
;;                 the EVOLVING macrostep snapshot — a sibling region that
;;                 transitioned earlier in the same macrostep is visible.
;;   :all-state  — the full region-name → active-state map (the PRECISE
;;                 `stateIn` equivalent; `(= :valid (:form (:all-state ctx)))`
;;                 reads a sibling region's discrete state value directly).
;;
;; `:all-state` is the unambiguous PARALLEL-REGION marker — only
;; `reduce-regions` ever sets it. A flat / compound machine's working
;; snapshot never carries it, so `call-guard` / `call-action` surface
;; NEITHER key for flat / compound machines: their guard/action ctx is
;; exactly `{:data :event :state :meta}`, unchanged. (A flat machine's
;; `stateIn` substitute is its own `:state` — Spec 005 §Guards.) Keying the
;; cross-region keys off `:all-state` presence means flat machines stay
;; untouched without stripping the inherited committed `:tags` slot from
;; their snapshot.

(defn- callback-ctx
  "Build the unified context-map handed to a `:guard` / `:action` / `:entry`
  / `:exit` callback. Per Spec 005 §Guards / §Actions (rf2-grw4i /
  rf2-v0rrr) the base shape is `{:data :event :state :meta}`.
  Per rf2-46ly6 / rf2-69d1n, a parallel REGION's snapshot additionally
  carries the machine-wide `:tags` union and the full `:all-state` region
  map (threaded by `parallel/reduce-regions`) — the cross-region
  coordination keys (XState v5 `stateIn` / SCXML `In()`). `:all-state` is
  the parallel-region marker: present iff this is a region snapshot, so
  flat / compound machines surface neither key and their ctx is unchanged."
  [snapshot event]
  (cond-> {:data  (:data snapshot)
           :event event
           :state (:state snapshot)
           :meta  (:meta snapshot)}
    (contains? snapshot :all-state) (assoc :all-state (:all-state snapshot)
                                           :tags      (:tags snapshot))))

(defn- call-guard
  "Invoke a resolved guard fn against a snapshot + event with the unified
  context-map contract — `(fn [{:keys [data event state meta]}] boolean)`.
  Per Spec 005 §Guards (rf2-grw4i / rf2-v0rrr); a parallel region's guard
  additionally receives `:tags` + `:all-state` (rf2-46ly6 / rf2-69d1n)."
  [g snapshot event]
  (g (callback-ctx snapshot event)))

(defn- call-action
  "Invoke a resolved action fn against a snapshot + event with the unified
  context-map contract — `(fn [{:keys [data event state meta]}] effects)`.
  Per Spec 005 §Actions (rf2-grw4i / rf2-v0rrr); a parallel region's action
  additionally receives `:tags` + `:all-state` (rf2-46ly6 / rf2-69d1n)."
  [f snapshot event]
  (f (callback-ctx snapshot event)))

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

(defn classify-delay-source
  "Classify an `:after` delay-key into its source form — the closed set
  `{:literal :sub :fn}` owned by the `:after` grammar (Spec 005 §Delayed
  `:after` transitions). A `number?` key is the literal-ms form; a
  `vector?` key is a subscription-vector (`[:sub-id & args]`); a `fn?`
  key is the computed-once-at-entry form. Any other shape falls back to
  `:literal` (the schema constrains the canonical forms; this is
  defensive).

  The single home for the classification, shared by the pure side
  (`build-after-fx`, which tags the `:rf.machine.timer/scheduled` /
  `/skipped-on-server` trace) and the fx side
  (`re-frame.machines.timer/schedule-after-timer!`, which routes the
  delay resolution + watcher install) so the two can never disagree on
  what a given delay-key's source is."
  [delay-key]
  (cond
    (number? delay-key) :literal
    (vector? delay-key) :sub
    (fn? delay-key)     :fn
    :else               :literal))

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

  Returns a vector of candidate transition maps. Keeping `:on` and `:after`
  on this ONE normaliser is what stops the two value-form grammars drifting
  apart — the guarded candidate-vector form (`[{:guard g :target s}
  {:target s2 :action a}]`) resolves identically whether it is reached
  through an `:on` clause or an `:after` delay entry.

  rf2-16gxd — the FORBIDDEN-TRANSITION value form. A nil VALUE (the key is
  PRESENT in the table with value nil — `{:on {:logout nil}}`) normalises to
  a single unguarded INTERNAL candidate `[{}]`, exactly as the empty map
  `{:on {:logout {}}}` does. Both are enabled, targetless (internal) no-ops
  that the leaf→root walk treats as a match — halting deepest-wins at this
  level and thereby BLOCKING a parent's inherited transition for the event
  (re-frame2's spelling of XState v5 `on: {LOGOUT: undefined}` / a SCXML
  targetless internal `<transition event=\"E\"/>`). nil is the natural
  Clojure analogue of XState `undefined`, so the nil and empty-map forms are
  unified here — there is no nil-vs-`{}` trap. NOTE: this is the rule for a
  PRESENT value; an ABSENT key is a different thing entirely (no candidates,
  the walk continues to the parent). Callers that look up an OPTIONAL slot
  (`match-on-clause`'s `:on` tier select, `pick-done-transition` / the
  parallel-root `apply-on-done-action`'s `:on-done`) gate the call on the
  key's PRESENCE so absence never reaches this nil arm.

  `bad-value-id` names the error category to throw for an unrecognised
  value form so each caller surfaces its own slot-specific taxonomy
  (`:on` → `machine-bad-on-clause`, `:after` → `machine-bad-after-spec`)."
  [v bad-value-id]
  (cond
    (nil? v)                        [{}]
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

(defn ns-wildcard-key
  "Per Spec 005 §Wildcard transitions §Namespaced (partial) event
  descriptors (rf2-z4t2v). The namespace-wildcard descriptor for an
  event-id is its KEYWORD NAMESPACE paired with the `*` name — `:foo/bar`
  → `:foo/*`, `:mouse/down` → `:mouse/*`. This is re-frame2's spelling of
  XState v5's partial (prefix) event descriptor `mouse.*` (SCXML §3.12.1
  dot-prefix tokens); re-frame2 events are namespaced keywords, so the
  keyword namespace is the natural prefix tier.

  Returns the `:ns/*` keyword for a namespaced `event-id`, or nil when the
  event-id is not a keyword or carries no namespace (a bare `:go` has no
  namespace tier — only the total `:*` can catch it). `(keyword ns \"*\")`
  is a valid keyword: `(name :mouse/*)` is `\"*\"` and `(namespace
  :mouse/*)` is `\"mouse\"`, so the namespace-wildcard form is detectable
  and distinct from the total `:*` (whose `namespace` is nil)."
  [event-id]
  (when (keyword? event-id)
    (when-let [ns (namespace event-id)]
      (keyword ns "*"))))

(defn- match-on-clause
  "Given a node-or-machine map carrying an `:on` table, return the first
  candidate transition for `event-id` whose guard passes — resolving the
  three event-descriptor tiers most-specific-first (exact, the `:ns/*`
  namespace-wildcard, then the total `:*` wildcard) — or nil. Per Spec 005
  §Transition resolution / §Wildcard transitions — the per-level matching
  rule applied identically at every state-node and at the machine root.

  rf2-z4t2v — three descriptor tiers, in PRIORITY order at each level:
    1. EXACT `event-id` candidates;
    2. the NAMESPACE-WILDCARD `:ns/*` (`ns-wildcard-key` — `:mouse/*`
       catches any `:mouse/...` event; re-frame2's spelling of XState v5's
       partial descriptor `mouse.*`). Absent for a non-namespaced event-id;
    3. the TOTAL `:*` wildcard.
  Most-specific wins: exact > namespace-wildcard > total.

  rf2-icj9t — each tier is the LEAST-PRIORITY ENABLED transition relative
  to the tiers above it, NOT a 'no more specific KEY exists' fallback. A
  tier is consulted whenever NO higher tier yielded an ENABLED candidate —
  the higher key is absent, OR every one of its guarded candidates returned
  false (or threw). So a guard-blocked exact `event-id` falls through to
  `:ns/*`, a guard-blocked `:ns/*` falls through to `:*`, and only when no
  tier at this level is enabled does `match-on-clause` return nil — letting
  `pick-transition`'s leaf→root walk descend to the parent (whose own
  three-tier resolution then repeats). This matches XState v5's
  transition-selection order (descend the priority ladder within a state —
  exact, partial-descriptor, catch-all — before walking to its ancestor;
  a guard-failed transition is simply not selected, leaving lower-priority
  ones eligible).

  rf2-e7yhv — when the returned match came from EITHER wildcard tier (the
  more specific tiers were absent or all guard-blocked, and a wildcard's
  candidate fired) the transition is stamped `:rf/via-wildcard? true`.
  This rides the `:transition` slot through `apply-transition-once` into a
  `:rf.error/machine-action-exception` trace (when the wildcard's action
  throws — the xstate-v5 'fail loudly on unknown' idiom) so a consumer can
  attribute the throw to a wildcard action rather than a named transition.
  The namespace-wildcard is a wildcard for this purpose, exactly as `:*`."
  [machine node event-id event snapshot]
  (let [on            (:on node)
        select        (fn [k]
                        ;; rf2-16gxd — gate on PRESENCE. An ABSENT key yields
                        ;; no candidates (this tier is not enabled → fall
                        ;; through to the next tier, then to the parent). A
                        ;; PRESENT key normalises its value, where a nil value
                        ;; (`{:on {E nil}}`) is the FORBIDDEN-transition form —
                        ;; it normalises to an enabled internal candidate
                        ;; (`[{}]`) exactly like the empty map `{:on {E {}}}`,
                        ;; so the walk halts here and blocks a parent's
                        ;; inherited E. Distinguishing absent from present-nil
                        ;; is the whole point of the forbidden idiom: absence ≠
                        ;; block.
                        (when (contains? on k)
                          (select-passing-candidate
                            machine
                            (normalise-candidates
                              (get on k)
                              :rf.error/machine-bad-on-clause)
                            snapshot event)))
        ;; Tier 1 — exact event-id. Tier 2 — `:ns/*` (skipped for a
        ;; non-namespaced event-id; `ns-key` is nil and `select` is never
        ;; called). Tier 3 — total `:*`. Most-specific wins; each tier is
        ;; consulted only when the tiers above yielded no ENABLED candidate
        ;; (absent key OR every guarded candidate guard-blocked) — rf2-icj9t.
        ns-key        (ns-wildcard-key event-id)]
    (if-let [exact-hit (select event-id)]
      exact-hit
      (if-let [ns-hit (when ns-key (select ns-key))]
        (assoc ns-hit :rf/via-wildcard? true)
        (when-let [star-hit (select :*)]
          (assoc star-hit :rf/via-wildcard? true))))))

(defn- pick-done-transition
  "Per Spec 005 §Final states §The done-state signal (rf2-bnjb3 / rf2-zlmz7):
  resolve the synthetic completion event `[:rf.machine/done <node-path>]` —
  raised when the compound / parallel node at `<node-path>` reached its done
  configuration — to a transition. Two resolution arms, in priority order:

   1. **`:on-done` on the done node** (the XState `onDone` placement, reading
      like `:spawn`'s `:on-done`). The `:on-done` value is normalised through
      the SAME candidate machinery as an `:on` clause (a keyword target,
      vector-path target, single transition map, or guarded candidate vector)
      and resolved RELATIVE TO THE DONE NODE'S OWN LEVEL — its `:decl-path` is
      the done node's path, so a keyword target is a SIBLING of the compound /
      parallel node (the natural \"sub-flow done → advance the outer flow\"
      placement). This is the headline spelling.
   2. **An enclosing explicit `:on {:rf.machine/done …}`** (the lower-level
      escape hatch). When the done node declares no `:on-done` (or its
      candidates all guard-fail), fall through to the standard leaf→root `:on`
      walk on the CURRENT active path so an ancestor handling the reserved
      event-id explicitly can take it. A guard reads the raised node-path off
      `:event` (`(= <path> (second event))`) to disambiguate which node is
      done when several could raise it.

  **Parallel-region scoping — by region IDENTITY, not state-name shape
  (rf2-12ekv, superseding the rf2-m3arq shape-match).** A region-local
  compound's done signal is re-broadcast across EVERY sibling region by the
  parent internal-event queue (`parallel/drain-parent-queue` — the correct
  XState v5 / SCXML `:raise` rule), so the raised done reaches every region's
  resolver. XState v5 / SCXML scope `done.state.<id>` to the region that raised
  it BY NODE IDENTITY — a SIBLING region must NOT catch another region's done,
  even one whose compound shares the leading state-name (a common shape:
  per-region `:flow` sub-flows, `:loading`/`:loaded` axes).

  The earlier rf2-m3arq gate compared two REGION-RELATIVE paths with
  `prefix-of?` — a state-name SHAPE match, not a region-identity test — so it
  leaked whenever the sibling shared the leading state-name, and it only guarded
  arm 2 (arm 1 was ungated). The root-cause fix (rf2-12ekv) makes the done-raise
  carry a REGION-NAME HEAD (`done-raise-fx` stamps `:rf/region` onto the raised
  path — the same region-name-prefixing discipline `:after` / `:spawn`
  `:on-error` already use). Here we strip that head and decline a FOREIGN
  region's done by region NAME, mirroring `pick-after-transition`
  (`decline-region?` = `(not= region (first carried-path))`) and
  `pick-spawn-error-transition`. The region-stripped path is then region-
  relative again for BOTH arms — arm 1 resolves the done node via the stripped
  `done-path`, arm 2 walks `:on` for the stripped event — so a sibling sharing
  the path-SHAPE no longer matches: identity (the region-name head), not shape,
  decides. Flat / compound machines carry no `:rf/region` and the done-raise
  carries no head, so the strip / decline is inert — an unguarded
  `:on {:rf.machine/done …}` stays unambiguous (only the one machine raises into
  itself).

  `done-path` is the node's declaration path (the event's second element,
  region-stripped when `machine` is a region). Returns `{:transition t
  :decl-path p}` or nil (no `:on-done` and no enclosing handler — the done
  signal is then a benign no-op, exactly as an unhandled reserved-`:rf/*`
  event; a foreign region's done declines to nil here too)."
  [machine path event snapshot]
  (let [[_ raw-done-path] event
        region        (:rf/region machine)
        ;; rf2-12ekv — region-identity scoping. The done-raise carries a
        ;; region-name HEAD when raised from a parallel region (`done-raise-fx`
        ;; stamps `:rf/region`). Within a region's resolution `machine` is the
        ;; region body (region-relative `node-at`) and `path` is in-region, so
        ;; strip the region-name head. A head naming a DIFFERENT region is not
        ;; this region's done — decline (the broadcast routes the done to its
        ;; OWN region only). Mirrors `pick-after-transition` /
        ;; `pick-spawn-error-transition`. A flat / compound machine (no
        ;; `:rf/region`) carries no head — the strip is inert.
        decline-region? (and region
                             (vector? raw-done-path)
                             (not= region (first raw-done-path)))
        done-path     (cond
                        (not (vector? raw-done-path)) raw-done-path
                        region (vec (rest raw-done-path))
                        :else  raw-done-path)
        ;; The done event also rides on `:event` for a guard / action reading
        ;; `(second event)`; re-stamp it region-relative so the in-region view
        ;; is consistent (the region-name head is an internal routing detail).
        event         (if (and region (vector? raw-done-path))
                        (assoc (vec event) 1 done-path)
                        event)
        done-node     (when (vector? done-path) (node-at machine done-path))
        ;; rf2-16gxd — gate on PRESENCE of `:on-done`. The forbidden-transition
        ;; nil→`[{}]` rule in `normalise-candidates` is for a PRESENT value;
        ;; an ABSENT `:on-done` must yield no candidates so resolution falls
        ;; through to the enclosing explicit `:on {:rf.machine/done …}` walk
        ;; (it must NOT synthesise a blocking internal no-op on the done node).
        on-done-cands (when (and done-node (contains? done-node :on-done))
                        (normalise-candidates (:on-done done-node)
                                              :rf.error/machine-bad-on-done-clause))
        on-done-hit   (when (seq on-done-cands)
                        (select-passing-candidate machine on-done-cands snapshot event))]
    (when-not decline-region?
      (if on-done-hit
        {:transition on-done-hit :decl-path (vec done-path)}
        ;; Fall through to the standard leaf→root `:on` walk (an ancestor's
        ;; explicit `:on {:rf.machine/done …}`), then the root `:on`.
        (or
          (path-walk/walk-path-leaf-to-root
            machine path
            (fn [prefix n]
              (when-let [hit (match-on-clause machine n done-event-id event snapshot)]
                {:transition hit :decl-path prefix})))
          (when-let [hit (match-on-clause machine machine done-event-id event snapshot)]
            {:transition hit :decl-path []}))))))

(defn- pick-spawn-error-transition
  "Per Spec 005 §Final states §`:on-error` — child-failure control flow
  (rf2-5hlsh; XState v5 `invoke onError`): resolve the synthetic parent event
  `[:rf.machine.spawn/error <invoke-id> <error>]` — dispatched into the PARENT
  when one of its `:spawn`-spawned children failed — to a transition.

  `<invoke-id>` is the absolute prefix-path of the parent's `:spawn`-bearing
  state. The resolver routes by it: find the `:spawn`-bearing state node at
  `<invoke-id>` (the SAME placement the spawn was declared at, so the
  `:on-error` is resolved at THAT state's level — a keyword target is a
  SIBLING of the `:spawn`-bearing state, the natural \"child failed → move the
  parent out of the spawning state\" placement). Its `:spawn :on-error` value
  is an `:on`-shaped transition spec (a keyword target, vector-path target,
  single transition map `{:target :guard :actions}`, or guarded candidate
  vector) — normalised + guard-resolved through the SAME candidate machinery as
  an `:on` clause. The error payload rides on `:event` (`(nth ev 2)`) so a
  guard / action can branch on it.

  Two resolution arms, in priority order — symmetric with
  `pick-done-transition`:

   1. **`:on-error` on the parent's `:spawn` map** — the headline spelling.
      Resolved at the `:spawn`-bearing state's decl-path. Only fires when the
      parent's ACTIVE path still includes that state (the spawning state is
      still occupied — the common case, since the child failing is what should
      move the parent out of it).
   2. **An enclosing explicit `:on {:rf.machine.spawn/error …}`** — the
      lower-level escape hatch (kept additive). When no `:spawn :on-error` is
      declared (or its candidates all guard-fail), the raised event walks the
      active path leaf→root like any event, so an ancestor handling the
      reserved id explicitly can take it. A guard reads the invoke-id /error off
      `:event` to disambiguate which spawn failed.

  Returns `{:transition t :decl-path p}` or nil (no `:on-error` and no
  enclosing handler — the failure signal is then a benign no-op, exactly like
  an unhandled reserved-`:rf/*` event; the existing trace emission + the
  explicit dispatch-back-to-parent escape hatch remain the lower-level forms)."
  [machine path event snapshot]
  (let [[_ raw-invoke-id] event
        region         (:rf/region machine)
        ;; Per Spec 005 §Per-region `:spawn` scoping (rf2-l67o): a `:spawn`
        ;; declared inside a parallel region carries a region-name-prefixed
        ;; invoke-id (`prefix-region-spawn-id`). Within a region's resolution
        ;; `machine` is the region body (region-relative `node-at`) and `path`
        ;; is in-region, so strip the region-name head — mirroring the
        ;; `pick-after-transition` region handling. A prefix naming a DIFFERENT
        ;; region is not this region's spawn; decline so the broadcast routes
        ;; to the bearing region only.
        invoke-id      (cond
                         (not (vector? raw-invoke-id)) raw-invoke-id
                         region (when (= region (first raw-invoke-id))
                                  (vec (rest raw-invoke-id)))
                         :else  raw-invoke-id)
        ;; The `:spawn`-bearing state lives at `invoke-id` (absolute prefix
        ;; path, region-stripped above). It is only resolvable when still on
        ;; the active `path` — a transition that already exited the spawning
        ;; state cannot land an `:on-error` (the spawn is gone). `prefix-of?`
        ;; mirrors the `:after` staleness check.
        spawn-node     (when (and (vector? invoke-id)
                                  (seq invoke-id)
                                  (prefix-of? invoke-id path))
                         (node-at machine invoke-id))
        on-error       (get-in spawn-node [:spawn :on-error])
        on-error-cands (when (and spawn-node (some? on-error))
                         (normalise-candidates on-error
                                               :rf.error/machine-bad-on-error-clause))
        on-error-hit   (when (seq on-error-cands)
                         (select-passing-candidate machine on-error-cands snapshot event))]
    (if on-error-hit
      {:transition on-error-hit :decl-path (vec invoke-id)}
      ;; Fall through to the standard leaf→root `:on` walk (an ancestor's
      ;; explicit `:on {:rf.machine.spawn/error …}`), then the root `:on`.
      (or
        (path-walk/walk-path-leaf-to-root
          machine path
          (fn [prefix n]
            (when-let [hit (match-on-clause machine n spawn-error-event-id event snapshot)]
              {:transition hit :decl-path prefix})))
        (when-let [hit (match-on-clause machine machine spawn-error-event-id event snapshot)]
          {:transition hit :decl-path []})))))

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
  delegating to pick-after-transition, and the synthetic
  `[:rf.machine/done <node-path>]` completion event (rf2-bnjb3 / rf2-zlmz7)
  by delegating to `pick-done-transition` (the done node's `:on-done`, then
  an enclosing explicit `:on {:rf.machine/done …}`)."
  [machine path event snapshot]
  (let [event-id (first event)]
    (cond
      (= :rf.machine.timer/after-elapsed event-id)
      (pick-after-transition machine path event snapshot)

      (= done-event-id event-id)
      (pick-done-transition machine path event snapshot)

      (= spawn-error-event-id event-id)
      (pick-spawn-error-transition machine path event snapshot)

      :else
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
                    (let [delay-source (classify-delay-source delay-key)
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
  of `state` declares `:final? true`. Finality is a pure recompute from
  the post-transition `:state` — it is NOT stamped onto the snapshot
  (there is no `:rf/finished?` slot; per Spec 005 §Persistence posture the
  pure `machine-transition` surface stays free of runtime-only
  bookkeeping).

  This answers \"is the active leaf final?\" — NOT \"does the whole machine
  finish?\". Per rf2-bnjb3 / rf2-zlmz7 (the done-state / `:on-done` signal)
  the two questions diverge: a `:final?` leaf that is a DIRECT CHILD of the
  machine root is whole-machine finality (auto-destroy / spawning parent's
  `:on-done`); a `:final?` leaf EMBEDDED inside a compound signals only that
  the enclosing compound is DONE (an in-machine `done.state.<compound>`
  raise an enclosing transition can take — the machine keeps running). The
  lifecycle-handler boundary uses `top-level-final?` (not this fn) to gate
  whole-machine auto-destroy; this fn is the building block.

  Note: parallel-region machines compose finality across regions — the
  parent is `:final?` only when EVERY region's active leaf is `:final?`.
  This fn answers the per-state question; the parallel-region union is
  computed by the orchestrator (`re-frame.machines.parallel` /
  `re-frame.machines.lifecycle-fx.finalize`)."
  [machine state]
  (let [node (node-at machine (state-path state))]
    (final-state-node? node)))

(defn top-level-final?
  "Per Spec 005 §Final states §Embedded vs top-level (rf2-bnjb3 / rf2-zlmz7):
  true iff `state`'s active leaf is `:final?` AND it is a DIRECT CHILD of the
  machine root — i.e. a length-1 state path. This is the WHOLE-MACHINE
  finality the lifecycle-handler boundary gates auto-destroy / spawning-
  parent `:on-done` on.

  The distinction from `final-on-leaf?` is the D7 reconciliation: entering a
  top-level `:final?` leaf still terminates the actor (singleton auto-destroy,
  or child → parent `:on-done`); entering a `:final?` leaf NESTED inside a
  compound instead signals `done.state.<compound>` (a transitionable in-
  machine completion event — see `compound-done-paths`) and the machine keeps
  running. For a region of a parallel machine the region body is the root, so
  this is computed against the region's in-region path; the parallel parent's
  whole-machine finality is `all-regions-final?` (in
  `re-frame.machines.lifecycle-fx.finalize`)."
  [machine state]
  (let [path (state-path state)]
    (and (= 1 (count path))
         (final-state-node? (node-at machine path)))))

;; ---- done-state / :on-done completion signal (rf2-bnjb3 / rf2-zlmz7) -------
;;
;; Per Spec 005 §Final states §The done-state signal. XState v5 `onDone` /
;; SCXML §3.7 `done.state.<id>`: when a COMPOUND state reaches a `<final>`
;; child — or, for a `<parallel>`, when EVERY region reaches its final state —
;; the processor raises `done.state.<id>` INTO the machine. An enclosing
;; transition (`onDone` on the compound/parallel node, or an ancestor's
;; `<transition event='done.state.id'>`) can take it, advancing the outer
;; flow WHILE the machine keeps running — the canonical "do these sub-flows,
;; then continue" pattern.
;;
;; re-frame2 ships this as a first-class transitionable signal (replacing the
;; former `:raise`-from-the-final-leaf's-`:entry` substitute, which collided
;; with the `:final?`-auto-destroys rule — the rf2-zlmz7 footgun):
;;
;;   - The reserved event-id is `:rf.machine/done`; the completed node's
;;     declaration PATH rides as the event's single arg —
;;     `[:rf.machine/done <node-path>]`. This is re-frame2's spelling of
;;     XState's `done.state.<id>` / SCXML's `done.state.id`: the id is the
;;     node path, carried as data rather than baked into the event-id keyword
;;     (so the `:on` table stays keyed on a single reserved keyword, and the
;;     resolver routes by the path arg — see `pick-done-transition`).
;;   - The author declares `:on-done` ON the compound / parallel node (the
;;     XState `onDone` placement, reading exactly like `:spawn`'s `:on-done`).
;;     Its value is an `:on`-shaped transition spec resolved RELATIVE TO THE
;;     NODE'S OWN LEVEL (a keyword target is a sibling of the compound/parallel
;;     node). An ancestor may instead handle the raised event explicitly with
;;     `:on {:rf.machine/done {:guard <matches the path> …}}` — the lower-level
;;     escape hatch.
;;   - The raise is injected by `apply-transition-once` the moment the
;;     committed configuration makes a node NEWLY done, so it drains through
;;     the SAME FIFO `:raise` queue + macrostep loop: the `:on-done`
;;     transition fires in the same macrostep, deterministically, bounded by
;;     `:raise-depth-limit`, atomically.

(defn- compound-done?
  "True iff the compound at `compound-path` (a non-empty prefix of the active
  leaf path `leaf-path`) has its ACTIVE DIRECT CHILD be a `:final?` leaf — the
  node at depth `(count compound-path)` along `leaf-path`. XState/SCXML: a
  compound is done when it reaches a `<final>` child."
  [machine leaf-path compound-path]
  (let [d           (count compound-path)
        child-path  (subvec (vec leaf-path) 0 (inc d))]
    (and (< d (count leaf-path))
         (final-state-node? (node-at machine child-path)))))

(defn compound-done-paths
  "Per Spec 005 §Final states §The done-state signal (rf2-zlmz7): given the
  POST-transition leaf path `leaf-path`, return the vector of EMBEDDED
  compound declaration-paths that are NEWLY done — each compound whose active
  direct child is a `:final?` leaf, EXCLUDING the machine root (a `:final?`
  leaf that is a direct child of the root is whole-machine finality, NOT a
  compound-done signal — see `top-level-final?` / the D7 reconciliation).

  Only the IMMEDIATE-parent compound of the final leaf can be done (its child
  is the final leaf); ancestors above it are not done unless THEY too reach a
  `:final?` direct child, which cannot happen in the same configuration (the
  active child of a grandparent is a compound, not a `:final?` leaf). So this
  returns at most one path — the final leaf's parent compound — but is shaped
  as a vector for symmetry with the parallel done-paths and to stay robust if
  the grammar later admits compound `:final?` (today rejected at
  registration).

  Returns `[]` when the leaf is not final, or is final at the root (top-level
  finality). For a region of a parallel machine `machine` is the region body
  (the region's root), so a region-local compound's done is detected here and
  the parallel parent's all-regions done is handled separately."
  [machine leaf-path]
  (let [leaf-path (vec leaf-path)
        n         (count leaf-path)]
    (if (or (< n 2)
            (not (final-state-node? (node-at machine leaf-path))))
      ;; Not final, or final at the root (length-1) — no compound-done signal.
      []
      ;; The final leaf is at depth n-1; its parent compound is at depth n-1,
      ;; path `leaf-path[0..n-2]`. That parent is NOT the root (n >= 2). It is
      ;; the single newly-done compound.
      (let [parent-path (subvec leaf-path 0 (dec n))]
        (if (compound-done? machine leaf-path parent-path)
          [parent-path]
          [])))))

(defn done-raise-fx
  "Per Spec 005 §Final states §The done-state signal: build the
  `[:raise [:rf.machine/done <node-path>]]` fx entries for every EMBEDDED
  compound the committed `leaf-path` makes newly done. The raises enter the
  macrostep's FIFO `:raise` queue (verbatim `:raise` fx, drained by
  `drain-or-defer-raises` / the parallel parent queue) so the enclosing
  `:on-done` transition fires in the SAME macrostep. Returns `[]` when no
  compound is newly done (the common case — most transitions land on an
  ordinary leaf). `machine` is the flat / compound machine or a region body.

  **Region-identity scoping (rf2-12ekv).** When `machine` is a parallel region
  (`:rf/region` present), `compound-done-paths` returns a REGION-RELATIVE path
  (region-body `node-at`). The parent internal-event queue re-broadcasts the
  raise across EVERY sibling region (the correct XState v5 / SCXML `:raise`
  rule — `drain-parent-queue`), so a bare region-relative path carries NO
  region-identity discriminator: a sibling sharing the leading state-name would
  falsely match it. Stamp the region name as the path HEAD — the SAME
  region-name-prefixing discipline `:after` (carried `decl-path`) and `:spawn`
  `:on-error` (`prefix-region-spawn-id` on the invoke-id) already use — so the
  done-raise becomes `[:rf.machine/done [<region-name> & <region-relative-path>]]`.
  `pick-done-transition` then strips the region head and declines a foreign
  region's done by region NAME (identity), not state-name shape. XState v5 /
  SCXML raise `done.state.<region-id>`, not a bare state-name; this matches that
  by node identity. Flat / compound machines (no `:rf/region`) carry no head —
  the path stays region-relative and the resolver's gate is inert."
  [machine leaf-path]
  (let [region (:rf/region machine)]
    (mapv (fn [compound-path]
            [:raise [done-event-id (if region
                                     (vec (cons region compound-path))
                                     compound-path)]])
          (compound-done-paths machine leaf-path))))

(defn apply-on-done-action
  "Per Spec 005 §Final states §The done-state signal (rf2-bnjb3): run a
  PARALLEL ROOT's `:on-done` transition's `:action` against `snap`'s `:data`,
  threading the standard `{:data :fx}` effects-map contract. The parallel
  root's `:on-done` carries no in-machine `:target` (root-only parallel has no
  sibling flat state — registration rejects a `:target`), so this runs the
  selected candidate's `:action` and collects its `:fx`. The `:on-done` value
  is normalised through the SAME candidate machinery as an `:on` clause
  (a guarded candidate-vector resolves first-guard-pass-wins; a bare action-
  less keyword / map is a data no-op). Returns a `result/ok` carrying
  `[snap' fx]` (the action's `:data` merged, `:fx` collected), or a
  `result/fail` if the action threw. A nil / no-candidate `:on-done` returns
  `(ok snap [])` unchanged. Invoked with the synthetic
  `[:rf.machine/done []]` event so a 3-arity action introspecting `:event`
  sees the reserved done discriminator."
  [machine snap on-done]
  (let [cands (normalise-candidates on-done :rf.error/machine-bad-on-done-clause)
        event [done-event-id []]
        tspec (select-passing-candidate machine cands snap event)]
    (if (nil? tspec)
      (result/ok snap [])
      (let [r (run-action machine snap (:action tspec) event :on-done)]
        (if (result/fail? r)
          r
          (let [new-data (cond-> (:data snap)
                           (contains? r :data) (merge (:data r)))]
            (result/ok (assoc snap :data new-data) (vec (or (:fx r) [])))))))))

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
    :internal?      — the EFFECTIVE internal flag (rf2-eicq0): true iff the
                      transition has no `:target`, OR its target lands on the
                      active path (self / proper ancestor) WITHOUT
                      `:reenter? true`. An active-path target is internal by
                      default (XState-v5); `:reenter? true` makes it external
                      (exit/entry re-run, `:after`/`:spawn` restart).
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
        ;; `targetless?` is the structural "no `:target` declared" predicate.
        ;; It is NOT the same as the effective `internal?` flag computed
        ;; below: under the XState-v5 model (rf2-eicq0) a TARGETED transition
        ;; whose target lands on the ACTIVE PATH (self or proper ancestor) is
        ;; ALSO internal by default — it only becomes external when the
        ;; transition opts in with `:reenter? true`. So `internal?` = no
        ;; target, OR an active-path target without `:reenter?`.
        targetless?   (nil? raw-target)
        ;; `:reenter? true` is the opt-in for an EXTERNAL self/ancestor
        ;; transition: re-run `:exit` then `:entry`, restart the target's
        ;; `:after` timers + tear-down-and-respawn its `:spawn`/`:spawn-all`
        ;; children. Absent / false ⇒ a self/ancestor target is internal
        ;; (no exit/entry churn). The flag is meaningful only for a target on
        ;; the active path; for a disjoint-subtree target the LCCA already
        ;; lies above both source and target, so exit/entry fire regardless
        ;; (the flag is a no-op there). Per Spec 005 §Self-transitions.
        reenter?      (true? (:reenter? transition))
        ;; The target BEFORE initial-cascade re-descent. Needed to detect
        ;; the self/ancestor transition: a `:target` (the `:same-state`
        ;; sentinel, or a keyword naming the declaring state's own key)
        ;; that resolves onto the active path.
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
        hist-node     (when (and (not targetless?) target-base0)
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
        ;; ---- Exit-set boundary: the true LCCA (rf2-emz8l) ----------------
        ;; Per Spec 005 §Entry/exit cascading and SCXML §3.13: the exit set
        ;; of an EXTERNAL transition is bounded by the LEAST COMMON COMPOUND
        ;; ANCESTOR — the deepest compound state that is a PROPER ancestor of
        ;; BOTH the source leaf and the target node. Everything strictly
        ;; below the LCCA on the active path exits; the target plus its
        ;; ancestors below the LCCA (and the target's `:initial` cascade)
        ;; enters. `lca-len` is the depth of that LCCA (= the count of the
        ;; common-ancestor prefix that survives the transition).
        ;;
        ;; The LCCA is computed against `target-base` (the resolved target
        ;; BEFORE its own `:initial` re-descent), NOT `target-leaf` — the
        ;; initial cascade is part of ENTERING the target, not of locating
        ;; the common ancestor. Two geometries arise:
        ;;
        ;;  (1) TARGET ON THE ACTIVE PATH — `target-base` is a prefix of
        ;;      `src-path` (the target is the source itself, OR a proper
        ;;      ANCESTOR of the source). Per the XState-v5 model (rf2-eicq0)
        ;;      this is INTERNAL BY DEFAULT — the source neither exits nor
        ;;      re-enters; the transition's `:action` fires and the
        ;;      configuration is unchanged (same shape as a targetless
        ;;      internal transition). Only when the transition opts in with
        ;;      `:reenter? true` does the EXTERNAL restart geometry apply:
        ;;      pull `lca-len` UP to the target's PARENT
        ;;      (`(count target-base) - 1`) so the target lands in BOTH the
        ;;      exit and entry cascades — it is exited (re-running its
        ;;      `:exit`), the transition `:action` fires, the target is
        ;;      re-entered (re-running its `:entry`) and its `:initial` chain
        ;;      re-descends (`target-leaf`). That is the RESTART-the-compound
        ;;      geometry XState v5 gives an external/`reenter:true` transition
        ;;      to self or an ancestor (and the v4/SCXML DEFAULT, which v5
        ;;      flipped — see Spec 005 §Self-transitions). Without the pull-up
        ;;      a re-entering active-path target would have its plain
        ;;      common-prefix LCA equal the full target depth and the
        ;;      transition would be a SILENT NO-OP (rf2-emz8l). `max 0` keeps
        ;;      a root-level target (`target-base == []`) sane: with
        ;;      `:reenter?` the whole machine exits + re-enters from the root.
        ;;
        ;;  (2) TARGET IN A DISJOINT SUBTREE — sibling-leaf, cross-level to a
        ;;      sibling subtree, or to the root. `target-base` is NOT a prefix
        ;;      of `src-path`, so source and target diverge at the common-
        ;;      prefix node, which is a proper ancestor of both and therefore
        ;;      the LCCA. `lca-len` is the plain common-prefix length; the
        ;;      common-ancestor node neither exits nor enters. (Here the LCP
        ;;      of `src-path` with `target-base` and with `target-leaf` agree
        ;;      — they diverge before `target-base` ends — so this arm is left
        ;;      computing against `target-leaf`, unchanged.) `:reenter?` is a
        ;;      no-op here: the LCCA already lies above both, so exit/entry
        ;;      fire regardless.
        ;;
        ;;  An INTERNAL transition (no `:target`, OR an active-path target
        ;;  without `:reenter?`) is untouched — it never reaches the cascade
        ;;  (`internal?` short-circuits exit/entry below).
        ;;
        ;; `target-on-active-path?` is the GEOMETRIC predicate (target lands
        ;; on the active path), independent of the `:reenter?` opt-in. The
        ;; EXTERNAL pull-up fires only when re-entry is also REQUESTED
        ;; (`reenter-active-path?`); otherwise an active-path target is folded
        ;; into the effective `internal?` flag below.
        target-on-active-path? (and (not targetless?)
                                    (= (count target-base)
                                       (common-prefix-length src-path target-base)))
        ;; A HISTORY restore is an external re-entry BY NATURE — it resolves
        ;; the pseudo-state to a concrete config and re-enters the compound,
        ;; recording the outgoing config on the way (rf2-mle6e). It must NOT
        ;; be folded into the internal-default even when the resolved leaf
        ;; happens to coincide with the source (the never-entered fall-back to
        ;; the compound's `:initial` can land back on the current leaf). So a
        ;; history target ALWAYS re-enters, regardless of `:reenter?`.
        external-re-entry?     (or reenter? (some? history-restore))
        reenter-active-path?   (and target-on-active-path? external-re-entry?)
        ;; The EFFECTIVE internal flag threaded to every downstream phase
        ;; (cascade-steps, `commit-snapshot` state preservation, after-fx /
        ;; after-cancel / destroy / done-raise / history-record). A
        ;; self/ancestor target WITHOUT `:reenter?` is internal — XState-v5
        ;; default (rf2-eicq0). Targetless is always internal. A history
        ;; restore is never internal (it re-enters by nature, above).
        internal?     (or targetless?
                          (and target-on-active-path? (not external-re-entry?)))
        lca-len       (cond
                        internal?            (count src-path)
                        reenter-active-path? (max 0 (dec (count target-base)))
                        :else                (common-prefix-length src-path target-leaf))
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
              (let [;; Per Spec 005 §Final states §The done-state signal
                    ;; (rf2-bnjb3 / rf2-zlmz7): if the committed configuration
                    ;; makes an EMBEDDED compound newly done (its active direct
                    ;; child is a `:final?` leaf), raise `[:rf.machine/done
                    ;; <compound-path>]` into the macrostep's FIFO `:raise`
                    ;; queue so the enclosing `:on-done` transition fires in
                    ;; the SAME macrostep (drained by `drain-or-defer-raises` /
                    ;; the parallel parent queue). Only an EXTERNAL transition
                    ;; (a new configuration was entered) can newly satisfy a
                    ;; compound's done condition — an internal transition keeps
                    ;; the same `:state`, so the signal (if any) already fired
                    ;; on the entry that reached the final leaf. A TOP-LEVEL
                    ;; `:final?` leaf (direct child of the root) raises NO
                    ;; compound-done — it is whole-machine finality handled at
                    ;; the lifecycle boundary (auto-destroy / spawning parent's
                    ;; `:on-done`), the D7 reconciliation.
                    done-fx (when-not (:internal? cascade)
                              (done-raise-fx machine (state-path (:state snap-after-spawns))))
                    all-fx (vec (concat fx
                                        (or after-cancel-fx [])
                                        (or destroy-fx [])
                                        spawn-fx
                                        (or after-fx [])
                                        (or done-fx [])))]
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

(def raise-depth-limit-default
  ;; Per Spec 005 §Drain semantics: bounds the recursive `:raise` queue
  ;; drain. Symmetric with `always-depth-limit-default` — 16 is generous
  ;; for hand-authored event-chains and catches accidental cycles.
  ;; Overridable per machine via `:raise-depth-limit`.
  ;;
  ;; Public (not `^:private`) so the parallel layer's parent-owned
  ;; internal-event-queue drain (`parallel.cljc`, rf2-yi7ts) bounds its
  ;; re-broadcast loop on the SAME default, keeping one source of truth
  ;; for the limit rather than duplicating the magic 16.
  16)

;; Forward-declared so `drain-raises` can call `machine-transition-single`
;; directly. The recursive `:raise` step is always against an already-
;; resolved single (flat / compound) machine context — for a parallel
;; parent, `parallel-machine-transition` (in `re-frame.machines.parallel`)
;; owns the macrostep's internal-event queue and re-broadcasts each raise
;; across ALL regions itself, so a region NEVER drains its own raises (see
;; the `:rf/region` defer below). For a flat / compound machine the
;; recursive call uses the SAME `machine` value (`parallel?` false).
;; Bypassing the public parallel-dispatch entry here avoids a per-raise
;; cross-namespace var deref on CLJS and keeps the parallel layer cleanly
;; above the single-machine drain.
(declare machine-transition-single)

(defn- split-raise-fx
  "Partition `fx-vec` into `{:raises [...] :rest [...]}` — `:raises` is the
  subvector of `[:raise <event-vec>]` entries (kept verbatim so they re-enter
  a raise queue as raises), `:rest` is every other fx entry, both preserving
  source order. Used by `drain-raises` to peel a deferred nested macrostep's
  surfaced raises off its real (do-fx-bound) fx so the raises can be appended
  to the FIFO queue while the real fx accumulate (rf2-nr434)."
  [fx-vec]
  (reduce (fn [acc [fx-id :as entry]]
            (if (= :raise fx-id)
              (update acc :raises conj entry)
              (update acc :rest conj entry)))
          {:raises [] :rest []}
          fx-vec))

(defn- drain-raises
  "Drain the :raise queue inside fx-vec. Each :raise becomes an inline
  recursive machine-transition-single call; non-:raise fx pass through to
  the accumulator. Returns a `result/ok` Result carrying the post-drain
  `[snap accum-fx]`, or a `result/fail` Result if any recursive step
  failed.

  **FIFO drain (rf2-nr434 — XState v5 / SCXML parity).** The queue is
  drained breadth-first: a raised event's OWN raises are APPENDED to the
  BACK of the queue, behind the still-pending sibling raises, exactly as
  SCXML's internal-event queue pops the front and a `<raise>` enqueues at
  the back. So a transition that raises `[A]` then `[B]`, where A's
  handler itself raises `[C]`, processes them `A, B, C` — `C` goes behind
  `B`, NOT ahead of it. (The earlier engine prepended — `(concat fx2
  rest-pending)` — for a depth-first `A, C, B`; that was an unblessed
  divergence, now aligned to the XState v5 gold standard.) A linear
  self-chain (each step raises exactly one event) settles to the same
  order under either discipline; FIFO vs depth-first differ only when a
  single transition raises ≥2 events AND an earlier one transitively
  raises more.

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
  toward the same `:raise-depth-limit` — order discipline (FIFO vs
  depth-first) does not change the COUNT of raises drained, so the bound
  trips at the same depth either way.

  This is the FLAT / COMPOUND-machine queue owner. A parallel parent does
  NOT reach here: `parallel-machine-transition` owns the macrostep's
  internal-event queue and re-broadcasts each region-emitted raise across
  every region (rf2-yi7ts). A region therefore DEFERS its raises — see
  `drain-or-defer-raises` and `machine-transition-single`'s `defer?`."
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
          ;; Pop this raise, apply its event-transition + its OWN `:always`
          ;; settling via a recursive `machine-transition-single`, but DEFER
          ;; that recursion's raises (`defer-raises? true`) so they surface
          ;; back here UN-drained rather than the nested call draining them
          ;; itself (which would be depth-first). Pass `(inc depth)` as the
          ;; transitive seed so the nested call's depth bound continues from
          ;; this drain's count (rf2-b88nm).
          (let [step-result (machine-transition-single machine snap args (inc depth) true)]
            (if (result/fail? step-result)
              step-result
              (result/with-ok [snap2 fx2] step-result
                ;; Split the deferred result's fx into the raised events
                ;; (`new-raises`) and the real do-fx-bound fx (`real-fx`).
                ;; FIFO (rf2-nr434 — XState/SCXML parity): APPEND `new-raises`
                ;; to the BACK of the queue, behind the still-pending sibling
                ;; raises (`rest-pending`), so a sibling raised earlier drains
                ;; before this raise's nested raises. (Was a depth-first
                ;; prepend that fully drained the nested chain first — the
                ;; unblessed divergence this aligns.)
                (let [{new-raises :raises real-fx :rest} (split-raise-fx fx2)]
                  (recur (concat rest-pending new-raises)
                         (into accum real-fx)
                         snap2
                         (inc depth))))))

          (recur rest-pending
                 (conj accum [fx-id args])
                 snap
                 depth))))))

(defn- drain-or-defer-raises
  "FIFO raise drain, OR a pass-through defer, per the macrostep's queue
  ownership. Two callers defer (`defer-raises?` true), so the `:raise` fx
  entries stay in `fx-vec` and ride out un-drained on the Result for the
  queue-owner to harvest and re-feed:

   - **FIFO recursion (rf2-nr434).** When `drain-raises` pops a raise it
     re-enters `machine-transition-single` with `defer-raises? true`, so
     that raise's OWN raises surface back to `drain-raises`' queue and get
     APPENDED to the back (behind the pending siblings) — true breadth-first
     ordering, matching SCXML's internal-event queue. Without deferral the
     recursive call would fully drain its nested raises before returning,
     re-introducing depth-first order.

   - **Parallel-region broadcast (rf2-yi7ts).** A REGION of a parallel
     parent always defers (its `machine-transition-single` runs with
     `defer-raises? true`); its raises belong to the PARENT macrostep's one
     internal-event queue, which re-broadcasts each across every region
     against the full evolving snapshot (XState/SCXML parity: `raise` enqueues
     on the machine's single internal queue, not a per-region one).

  When NOT deferring (the queue-owning top-level flat / compound drain), this
  delegates to the local FIFO `drain-raises`. `:always` is unaffected either
  way — it stays region-local and settles within whichever microstep owns it.
  Returns a `result/ok` (or a `result/fail` if a local drain step threw)."
  [machine snapshot fx-vec depth-limit start-depth defer-raises?]
  (if defer-raises?
    (result/ok snapshot fx-vec)
    (drain-raises machine snapshot fx-vec depth-limit start-depth)))

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

(defn drain-to-fixed-point
  "Shared settling tail of the single-machine macrostep — steps 3-5 of
  Spec 005 §Drain semantics §Level 3, factored out of
  `machine-transition-single` (rf2-505ic) so the machine-BIRTH cascade
  reuses the SAME raise-drain + `:always` fixed-point loop the event-driven
  macrostep uses, rather than duplicating it.

   3. Drain the local `:raise` queue FIFO (rf2-nr434 — XState v5 / SCXML
      internal-event-queue parity), OR defer per `defer?`.
   4. `:always` microstep loop — walk the active path leaf→root for any
      matching `:always`; apply it, drain its raises, re-check from the
      new state. Repeat to a fixed point.
   5. Stamp the active-configuration tag union (`commit-tags`) on the
      settled snapshot and return it, with `::microsteps` (the count of
      `:always` iterations) and `::cascade` (the structured step vector)
      stamped on the Result.

  `start-result` is the `result/ok` carrying the snapshot + fx + cascade
  that SEEDS the drain. For the event-driven macrostep that is the
  post-exit/action/entry `apply-transition-once` Result; for machine birth
  it is the post-initial-entry cascade Result. Either way its `::cascade`
  becomes the base cascade and the `:always` loop appends one `:microstep`
  step per eventless iteration.

  `rollback-snapshot` is the snapshot the macrostep atomically reverts to
  if the `:always` loop trips `:always-depth-limit` — the macrostep is
  atomic per Spec 005 §Bounded depth. The event-driven caller passes the
  PRE-event snapshot (the whole macrostep unwinds); the birth caller passes
  the POST-cascade snapshot (the initial state is already the committed
  configuration — only the runaway `:always` settling is abandoned).

  `raise-depth` seeds the transitive `:raise` depth counter (rf2-b88nm);
  `defer?` is the effective region-defer flag (a parallel region defers its
  raises to the parent macrostep — rf2-yi7ts). XState v5 / SCXML parity:
  `:always` (eventless) transitions settle as part of the SAME step that
  entered the state, so a transient state passed through here is never
  externally observed.

  Returns a `result/ok` (snapshot + fx, with `::microsteps` / `::cascade`)
  or a `result/fail` if an `:always` action or a drained raise threw."
  [machine start-result rollback-snapshot raise-depth defer?]
  (let [always-limit (get machine :always-depth-limit always-depth-limit-default)
        raise-limit  (get machine :raise-depth-limit  raise-depth-limit-default)]
    (if (result/fail? start-result)
      start-result
      (result/with-ok [snap-after-event fx-after-event] start-result
        (let [raised (drain-or-defer-raises machine snap-after-event fx-after-event raise-limit raise-depth defer?)
              ;; Per rf2-n9f4z: seed the macrostep cascade with the seeding
              ;; transition's (event-driven exit/action/entry, OR birth
              ;; initial-entry) steps; the `:always` loop appends one
              ;; `:microstep` step per eventless iteration (carrying that
              ;; microstep's own nested cascade). The accumulated vector is
              ;; the structured explanation the outer `:rf.machine/
              ;; transition` trace carries (rf2-52u5n).
              base-cascade (result/cascade start-result)]
          (if (result/fail? raised)
            raised
            (result/with-ok [snap-after-raise fx-after-raise] raised
              ;; Step 4: :always microstep loop. Track visited state-paths so that,
              ;; on depth-limit abort, we can report the path AND fully roll back to
              ;; `rollback-snapshot` — the macrostep is atomic per Spec 005.
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
                      (result/ok rollback-snapshot []))

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
                              (let [raised2 (drain-or-defer-raises machine snap2 fx2 raise-limit raise-depth defer?)]
                                (if (result/fail? raised2)
                                  raised2
                                  (result/with-ok [snap3 fx3] raised2
                                    (recur snap3
                                           (vec (concat fx fx3))
                                           (inc depth)
                                           (conj visited (:state snap3))
                                           (conj cascade micro-step))))))))))))))))))))

(defn machine-transition-single
  "Pure function. Single-machine (flat or compound) implementation of the
  macrostep. Per Spec 005 §Drain semantics §Level 3:
   1. Pick the matching transition for the event (deepest-wins resolution
      along the state path).
   2. Run the exit cascade → transition's action → entry cascade
      (`apply-transition-once`).
   3. Drain the local `:raise` queue FIFO (rf2-nr434 — XState v5 / SCXML
      internal-event-queue parity).
   4. `:always` microstep loop — walk path leaf→root for any matching
      `:always`; apply, drain raises, loop.
   5. Commit (return) the snapshot once `:always` reaches fixed point.

  Steps 3-5 (the raise-drain + `:always` fixed-point settling + tag commit)
  live in the shared `drain-to-fixed-point` (rf2-505ic) so machine BIRTH
  reuses the identical settling tail — see `re-frame.machines.parallel`'s
  `settle-birth` / `apply-initial-entry-cascade`.

  Returns a `result/ok` Result on success or a `result/fail` Result if
  any action or `:data`-fn threw. Bounded by `:raise-depth-limit` and
  `:always-depth-limit` (both default 16). Parallel-region routing lives
  in `re-frame.machines.parallel`'s `machine-transition` — the dispatch
  checks `parallel?` and either broadcasts across regions or falls
  through to this fn.

  **Raise deferral (`defer-raises?`).** Steps 3 / 4 either DRAIN the
  `:raise` queue locally or DEFER it (pass `:raise` fx through un-drained
  on the Result). Two callers defer, both so the queue-owner above sees a
  faithful FIFO queue:

   - `drain-raises` re-enters this fn with `defer-raises? true` when it
     pops a raise (rf2-nr434), so the popped raise's OWN raises surface
     back un-drained and `drain-raises` appends them to the BACK of its
     FIFO queue (true breadth-first; a self-draining recursion would be
     depth-first).
   - A REGION of a parallel parent (`:rf/region` present) ALWAYS defers
     (rf2-yi7ts): its raises belong to the PARENT macrostep's one
     internal-event queue, which re-broadcasts each across EVERY region
     against the full evolving snapshot (XState v5 / SCXML: `raise` targets
     the machine's single internal queue, never a per-region one).

  `:always` stays region-local and settles within whichever microstep owns
  it, regardless of deferral. The effective defer flag is `(or defer-raises?
  (some? (:rf/region machine)))` so a region defers even when reached via
  the bare public arity.

  `raise-depth` is the count of `:raise` recursions already consumed
  before reaching this call. The public entry passes 0; `drain-raises`
  passes its running count so a self-chaining single-raise accumulates
  transitive depth against the SAME `:raise-depth-limit` rather than
  resetting per nested call (rf2-b88nm). It seeds the `:raise` drains
  below — both the pre-commit drain and the per-`:always`-step drain — so
  raises emitted anywhere in this macrostep continue counting from the
  inbound transitive depth."
  ([machine snapshot event]
   (machine-transition-single machine snapshot event 0 false))
  ([machine snapshot event raise-depth]
   (machine-transition-single machine snapshot event raise-depth false))
  ([machine snapshot event raise-depth defer-raises?]
  (let [;; A region of a parallel parent always defers (its raises lift to
        ;; the parent macrostep), even when reached via the bare public
        ;; arity — see the docstring's `defer-raises?` note. The depth
        ;; LIMITS themselves (`:always-depth-limit` / `:raise-depth-limit`)
        ;; are read inside the shared `drain-to-fixed-point`.
        defer?       (or defer-raises? (some? (:rf/region machine)))
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
    ;; Steps 3-5: hand the post-event seed Result to the shared
    ;; `drain-to-fixed-point` — raise-drain FIFO, `:always` fixed-point
    ;; loop, tag commit (rf2-505ic factored this out so machine birth
    ;; reuses the identical settling tail). The atomic-rollback target is
    ;; the PRE-event `snapshot` (the whole macrostep unwinds on
    ;; `:always`-depth abort). `handled?` rides the Result for the
    ;; parallel parent's all-regions-declined no-op aggregation.
    (result/with-handled
     (drain-to-fixed-point machine result-after-event snapshot raise-depth defer?)
     handled?))))
