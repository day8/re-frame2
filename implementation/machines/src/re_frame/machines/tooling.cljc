(ns re-frame.machines.tooling
  "Machines tooling sibling of `re-frame.machines` — carries the
  derivation/process algebra view of registered machines, their live
  snapshots, and their spawned actors (`machine-algebra-view`,
  `machine-instance-algebra-view`, `machine-selector?`) that pair tools
  (Xray, re-frame2-pair-mcp) and the conformance fixtures query, but no
  production application code reads.

  Mirrors the `re-frame.flows.tooling` split (and the
  `re-frame.subs.tooling` / `re-frame.trace.tooling` splits):
    - CLJS consumers needing the surface call
      `re-frame.machines.tooling/<name>` directly. Apps that load the
      machines artefact but never attach a tool DCE the body wholesale (the
      sibling ns is loaded only when a test fixture, tool, or dev preload
      requires it; the `re-frame.machines` facade never `:require`s it).
    - JVM consumers keep an ergonomic `re-frame.machines/<name>` alias
      (gated under `#?(:clj ...)`). The JVM has no bundle to protect; the
      alias costs nothing. The whole machines artefact is already
      bundle-isolated from production builds (counter never `:require`s
      `re-frame.machines`), so this sibling can never reach a no-machines
      app's bundle; the explicit sentinel at the foot of this ns
      additionally proves no stray `:require` ever pulled the body in.

  READ-ONLY over the registry + live runtime-db. This ns touches NEITHER the
  core registrar write-path NOR the machine registration signatures — exactly
  as slice-2's subs.tooling read the sub registry, and slice-3's flows.tooling
  read the flow registry, without touching the registrar. It reads:
    - the registered-machine spec map STRAIGHT OFF the `:event` registrar
      slot's `:rf/machine?` / `:rf/machine` stamp (Spec 005 §Querying
      machines) — the SAME registrar read `re-frame.machines/machines` +
      `…/machine-meta` perform. It reads the registrar directly (NOT through
      the `re-frame.machines` facade) so the JVM alias on that facade can
      require THIS sibling without a load cycle (`machines` → `machines.tooling`
      → `machines` would not load) — mirroring how `flows.tooling` reads
      `flows.registry` rather than the `flows` facade;
    - live snapshots (singleton + spawned actors) off the frame's runtime-db
      partition at `[:rf.runtime/machines :snapshots …]`, resolving a spawned
      actor's TYPE spec through `re-frame.machines.lifecycle-fx.resolver`.

  Per [Derivations.md](../../../../../../spec/Derivations.md) §Machines expose
  algebra views and the projected Malli shapes in
  [Spec-Schemas §`:rf/derivation-node`](../../../../../../spec/Spec-Schemas.md)."
  (:require [re-frame.derivation.node :as node]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.machines.lifecycle-fx.resolver :as resolver]
            [re-frame.machines.paths :as paths]
            [re-frame.registrar :as registrar]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- algebra views --------------------------------------------------------
;;
;; Per [Derivations.md] §Machines expose algebra views — a machine is the
;; canonical `:process` member of the derivation/process algebra (a derivation
;; WITH state, lifecycle, and commands over time — Derivations §Process,
;; §Machine process and selector). Every `reg-machine` registration is an
;; instance of the algebra: a `:process` (refined `:machine-process`) whose
;; snapshot is MATERIALIZED into the runtime-db partition, evaluated
;; `:on-transition` (plus `:scheduled` when the machine declares `:after`
;; timers and `:on-reply` when it commands async work that replies), owned by
;; its `:machine-instance` lifecycle.
;;
;; A machine SELECTOR — an ordinary `reg-sub` over `[:rf/machine …]` — is NOT
;; a member surfaced here: selectors are not a second subscription system,
;; they stay ORDINARY subscription `:derivation` nodes, classified by
;; `re-frame.subs.tooling/sub-algebra-view` like any other sub. This
;; ns provides only the `machine-selector?` recognizer so a graph tool can
;; label a subscription node that reads a machine snapshot with the
;; `:machine-selector` refinement + the `:selector` edge role — the machine
;; itself remains the stateful `:process`, the selector an ephemeral
;; `:derivation` over its materialized snapshot.
;;
;; This is the *registrar-derived* slice (Derivations §The relocation seam):
;; the machine-process node is assembled from the machine spec metadata at
;; the surface that registered it. The live INSTANCE view additionally reads
;; the frame's runtime-db snapshots. These functions live in the
;; bundle-isolated tooling sibling, consumed by Xray + the conformance
;; fixtures; no `re-frame.core` facade export.
;;
;; The fixed classifications for EVERY machine-process node (Derivations
;; §Machines expose algebra views — the machine column):
;;   :kind          :process             (the superkind — Derivations §Two superkinds)
;;   :refinement    :machine-process     (the informative refinement of :process)
;;   :storage       :runtime-db          (the snapshot is durable runtime-db state)
;;   :lifecycle     :machine-instance    (machine destroy releases it)
;;   :materialized? true                 (the snapshot has a durable runtime-db address)
;; The per-machine axes that vary are the declared INPUTS (the events the
;; machine handles), the EVALUATION policy set (always `:on-transition`; plus
;; `:scheduled` / `:on-reply` when the spec declares timers / async commands),
;; and the OUTPUT snapshot path.
;;
;; A machine consumes the shared derivation vocabulary verbatim — it does NOT
;; redefine the `:rf/storage-class` / `:rf/evaluation-policy` / `:rf/lifecycle`
;; enums or the `:rf/derivation-node` shape (those are owned by Spec-Schemas /
;; Derivations).

(def ^:private reserved-event-ns
  "Event-id namespaces the framework owns — synthetic machine-internal
  triggers (`:rf.machine.timer/after-elapsed`, `:rf.machine/start`, …) and
  the reserved `:rf/*` carve-out. These are NOT app-declared inputs the
  algebra surfaces as the machine's dependency graph; they are the runtime's
  own plumbing. A wildcard `:*` `:on` key likewise names no concrete input."
  #{"rf.machine" "rf.machine.timer" "rf"})

(defn- machine-spec
  "The registered machine SPEC for `machine-id` (the value at `:rf/machine`),
  or nil when no machine is registered under that id. Delegates to the leaf
  `resolver/spec-from-registry` — the same registrar read
  `re-frame.machines/machine-meta` performs (this sibling requires `resolver`
  directly, NOT the `re-frame.machines` facade, which requires THIS sibling for
  its JVM alias — the dependency must point one way)."
  [machine-id]
  (resolver/spec-from-registry machine-id))

(defn- registered-machine-ids
  "Every registered machine-id — every `:event` registration whose metadata
  carries `:rf/machine? true`. The same filter `re-frame.machines/machines`
  performs, inlined to avoid the facade require (see `machine-spec`)."
  []
  (->> (registrar/registrations :event)
       (keep (fn [[id m]] (when (:rf/machine? m) id)))))

(defn- declared-event?
  "True iff `event-id` is an author-declared `:on` trigger (a keyword whose
  namespace is not a reserved framework namespace, and not the `:*` wildcard).
  The wildcard `:*` matches every event and so names no concrete input edge —
  it is a fallthrough, not a declared dependency."
  [event-id]
  (and (keyword? event-id)
       (not= :* event-id)
       (not (contains? reserved-event-ns (namespace event-id)))))

(defn- state-nodes
  "Every state-map node in a machine spec's state tree, in a deterministic
  pre-order walk. A `reg-machine` spec describes its states under `:states`
  (flat / compound / hierarchical — each value is itself a state map that may
  nest its own `:states`) OR under `:regions` (a parallel machine — each
  region value is a state map). Returns a flat seq of every state map so a
  caller can scan their `:on` / `:after` / `:spawn` keys uniformly, however
  deeply nested.

  Pure structural walk — it NEVER invokes a guard / action / `:after` fn (the
  don't-execute rule, Derivations §Static and live graphs)."
  [state-map]
  (when (map? state-map)
    (let [children (concat (vals (:states state-map))
                           (vals (:regions state-map)))]
      (cons state-map (mapcat state-nodes children)))))

(defn- declared-event-inputs
  "The author-declared event triggers a machine reacts to, lowered to the
  `[:event <event-id>]` declared-input form (Derivations §Declared input), in
  a stable sorted order. Every `:on` key across the whole state tree (flat,
  compound, hierarchical, and parallel-region) that names a concrete
  app-declared event — reserved framework namespaces and the `:*` wildcard
  filtered out (`declared-event?`). De-duplicated (the same event may be
  handled in several states) and sorted so the projection is deterministic."
  [machine]
  (->> (state-nodes machine)
       (mapcat (comp keys :on))
       (filter declared-event?)
       (distinct)
       (sort)
       (mapv (fn [event-id] [:event event-id]))))

(defn- declares-after?
  "True iff any state in the machine's tree declares an `:after` delayed
  transition — the spec drives a wall-clock timer that delivers a synthetic
  `:rf.machine.timer/after-elapsed` causal input (Spec 005 §Timed
  transitions), so the machine's evaluation policy includes `:scheduled`
  (Derivations §Evaluation policy)."
  [machine]
  (boolean (some (comp seq :after) (filter map? (state-nodes machine)))))

(defn- declares-spawn?
  "True iff any state declares `:spawn` or `:spawn-all` — the machine spawns
  child actors on entry (Spec 005 §Declarative :spawn). Surfaced on the
  process node under `:spawns?` so a graph tool knows this process is an
  actor parent without re-walking the spec."
  [machine]
  (boolean (some (fn [s] (or (:spawn s) (:spawn-all s)))
                 (filter map? (state-nodes machine)))))

(defn- evaluation-policy
  "The machine's evaluation-policy SET (Derivations §Evaluation policy; the
  `:rf/evaluation-policy` enum). A machine always evaluates `:on-transition`
  (a transition is the only thing that advances its snapshot). Additionally:
    - `:scheduled` when the spec declares any `:after` timer — a scheduler
      delivers the synthetic timer event;
    - `:on-reply` when the spec spawns / commands async work that returns a
      causal reply event (Spec 005 §Cross-machine messaging; an actor parent
      consumes its children's reply events). The conservative trigger is the
      presence of `:spawn` — a spawning parent reacts to reply events from
      its children.
  Returns a set so the node carries the `EvaluationSpec` set form."
  [machine]
  (cond-> #{:on-transition}
    (declares-after? machine) (conj :scheduled)
    (declares-spawn? machine) (conj :on-reply)))

(defn- with-metadata
  "Attach the `:source` coordinates, `:doc`, and `:schema` (the machine's
  `[:schemas :data]` schema) to a node when present. The macro-stamped
  `:reg-machine` spec co-locates `:ns` / `:line` / `:file` source coords on the
  spec map (and the registrar metadata mirrors them); `:doc` / `:schemas` are
  user-supplied spec keys. Functions stay opaque — a machine's transition
  logic is its `:states` / `:actions` / `:guards`, never serialized here."
  [node machine meta]
  (cond-> (node/attach-source node meta)
    (contains? machine :doc)              (assoc :doc (:doc machine))
    (get-in machine [:schemas :data])     (assoc :schema (get-in machine [:schemas :data]))))

(defn- node-for
  "Build the derivation/process algebra view of one machine spec (Derivations
  §The node shape, §Machines expose algebra views; Spec-Schemas
  §`:rf/derivation-node`). `id` is the machine's canonical fact identity — the
  registered machine-id for a singleton, the actor-id for a live spawned
  instance. `source-id` is the id whose registrar metadata carries the source
  coordinates (the registered TYPE id — a spawned actor inherits its type's
  coords).

  The fixed-classification spine — a `:process` (refinement `:machine-process`)
  whose materialized snapshot lands in runtime-db, evaluated `:on-transition`
  (+ derived policies), owned by its `:machine-instance` — plus the per-machine
  `:inputs` (the
  declared `[:event …]` triggers), `:output` (the runtime-db snapshot path),
  `:source-form`, `:spawns?` flag, and source coords / schema / doc."
  [id source-id machine meta]
  (-> (node/node-base id [:runtime (paths/snapshot-path id)]
                      {:kind          :process
                       :storage       :runtime-db
                       :evaluation    (evaluation-policy machine)
                       :lifecycle     :machine-instance
                       :materialized? true})
      (assoc :refinement  :machine-process
             :source-form {:kind :reg-machine :id source-id}
             :inputs      (declared-event-inputs machine)
             :owner       [:machine id]
             :spawns?     (declares-spawn? machine))
      (with-metadata machine meta)))

(defn machine-algebra-view
  "Return the STATIC derivation/process algebra view of every registered
  machine ([Derivations.md] §Machines expose algebra views,
  [Spec-Schemas §`:rf/derivation-node`]).

  Pure data over the machine registry — read through the public
  `re-frame.machines/machines` + `…/machine-meta` query API (Spec 005
  §Querying machines), never touching the registrar write-path or the machine
  registration signatures. The algebra-view companion to `machines`: where
  `machines` returns the bare machine-id vector, this lowers every registered
  machine into the normalized `:rf/derivation-node` shape the
  derivation/process algebra names, so a tool can show subscriptions, flows,
  resources, route facts, and machines as ONE family.

  A machine is the canonical `:process` member (a derivation WITH state,
  lifecycle, and commands over time — Derivations §Process). Each node carries:

  - `:id`          — the machine id (its canonical fact identity).
  - `:kind`        — `:process` (the superkind; Derivations §Two superkinds).
  - `:refinement`  — `:machine-process` (the informative refinement — a process
                     WITH state, lifecycle, and commands over time;
                     Derivations §The node shape).
  - `:source-form` — `{:kind :reg-machine :id <machine-id>}`.
  - `:inputs`      — the declared inputs (Derivations §Declared input): each
                     author-declared `:on` event trigger across the whole
                     state tree (flat / compound / hierarchical / parallel),
                     lowered to `[:event <event-id>]`, de-duplicated and
                     sorted. Reserved framework events (`:rf.machine/*`,
                     `:rf.machine.timer/*`, `:rf/*`) and the `:*` wildcard are
                     not declared inputs.
  - `:output`      — `[:runtime [:rf.runtime/machines :snapshots <id>]]` — the
                     machine MATERIALIZES its snapshot into the runtime-db
                     partition (machine snapshots are durable runtime-db
                     state).
  - `:storage`     — `:runtime-db`.
  - `:evaluation`  — the policy SET: always `:on-transition`; plus `:scheduled`
                     when the spec declares `:after` timers, plus `:on-reply`
                     when it spawns child actors that reply (Derivations
                     §Evaluation policy).
  - `:lifecycle`   — `:machine-instance` (machine destroy releases it).
  - `:owner`       — `[:machine <id>]`.
  - `:materialized?` — `true` (the snapshot has a durable runtime-db address).
  - `:spawns?`     — `true` iff the spec declares `:spawn` / `:spawn-all` (this
                     process is an actor parent; its live children appear in
                     `machine-instance-algebra-view`).
  - `:source` / `:schema` / `:doc` — present when the registration carried them
                     (`:schema` is the machine's `[:schemas :data]` schema;
                     source coords auto-captured by the `reg-machine` macro).

  Zero-arity returns `{machine-id <node>}` for every registered machine (`{}`
  when none). The one-arity form returns the single node for `machine-id`, or
  `nil` if it is not registered as a machine. JVM-runnable — the machine spec
  is partition-agnostic registration metadata.

  Machine SELECTORS (subs over `[:rf/machine …]`) are NOT surfaced here: they
  remain ordinary subscription `:derivation` nodes
  (`re-frame.subs.tooling/sub-algebra-view`); use `machine-selector?` to label
  one with the `:machine-selector` refinement.

  This ships NO public accessor: it lives in the bundle-isolated tooling
  sibling and is consumed by Xray + the conformance fixtures; the public
  name is deferred until a third consumer needs it."
  ([]
   (reduce
     (fn [acc machine-id]
       (if-let [machine (machine-spec machine-id)]
         (assoc acc machine-id
                (node-for machine-id machine-id machine
                          (registrar/lookup :event machine-id)))
         acc))
     {}
     (registered-machine-ids)))
  ([machine-id]
   (when-let [machine (machine-spec machine-id)]
     (node-for machine-id machine-id machine
               (registrar/lookup :event machine-id)))))

(defn machine-instance-algebra-view
  "Return the LIVE derivation/process algebra view of a frame's machine
  snapshots — one `:rf/derivation-node` per LIVE instance, singleton AND
  spawned actor ([Derivations.md] §Static and live graphs).

  The live counterpart to `machine-algebra-view`: where the static view
  reports one node per registered machine TYPE, the live view reports one node
  per concrete snapshot currently materialized at
  `[:rf.runtime/machines :snapshots <actor-id>]` in the frame's runtime-db —
  the realized machine instances the static graph cannot enumerate (a spawned
  actor has NO per-instance registration; its liveness IS the presence of its
  snapshot — Spec 005 §Liveness is derived from runtime-db).

  Per-instance node:

  - `:id`          — the actor-id (the live fact identity — the registered id
                     for a singleton, the `<type>#<n>` / explicit
                     `:fixed-actor-id` for a spawned actor).
  - `:source-form` — `{:kind :reg-machine :id <type-id>}` — the TYPE the
                     instance resolves to (a keyword `:rf/machine-type` for a
                     registered-type spawn; the type-id keyed by the spec for
                     an inline `:definition` spawn). Singletons are their own
                     type.
  - `:inputs` / `:evaluation` / `:spawns?` — derived from the RESOLVED type
                     spec (a spawned actor's reactions are its type's), exactly
                     as the static view.
  - `:output`      — `[:runtime [:rf.runtime/machines :snapshots <actor-id>]]`
                     — this instance's own snapshot slot.
  - `:state`       — the instance's current `:state` (the live lifecycle
                     evidence the `:machine-instance` owner is materialized).
  - `:spawned?`    — `true` iff the snapshot carries the reserved
                     `:rf/machine-type` discriminator `install-spawn!` stamps on
                     every spawned actor (a singleton snapshot never carries it).
  - the fixed `:kind` / `:storage` / `:lifecycle` / `:materialized?` spine, the
                     `:owner`, and source coords / schema / doc — same as the
                     static node.

  An instance whose snapshot carries an unresolvable `:rf/machine-type` (a
  cleared type) is skipped — it cannot be classified. The `:value` of the
  snapshot is NOT inlined (a graph tool reads it from the snapshot slot and
  redacts at egress per Derivations §Redaction metadata); only the `:state`
  summary is surfaced.

  The zero-arity form reads the ambient `re-frame.frame/current-frame`; the
  one-arity form takes an explicit `frame-id` (the form the graph composer's
  `:live-fn` calls).

  CLJS-and-JVM runnable, but dev-gated on `interop/debug-enabled?` (the
  `goog.DEBUG` mirror) so production builds DCE the runtime-db walk. Returns
  `nil` for a missing/destroyed frame and in production builds, `{}` for a
  frame with no live machine snapshots."
  ([] (machine-instance-algebra-view (frame/current-frame)))
  ([frame-id]
   (when interop/debug-enabled?
     (when-let [runtime-db (frame/frame-runtime-db-value frame-id)]
       (let [snapshots (get-in runtime-db (paths/snapshot-path))]
         (reduce-kv
           (fn [acc actor-id snapshot]
             (let [machine-type (:rf/machine-type snapshot)
                   ;; A singleton snapshot carries no `:rf/machine-type`; its
                   ;; spec is the registered machine under its own id. A
                   ;; spawned actor resolves its TYPE spec from the snapshot
                   ;; (registered-type keyword → registrar; inline-definition
                   ;; map → verbatim) via the leaf resolver.
                   spawned?     (some? machine-type)
                   spec         (if spawned?
                                  (resolver/spec-from-snapshot snapshot)
                                  (machine-spec actor-id))
                   ;; The source-id whose coords / source-form we inherit: the
                   ;; registered type keyword (a keyword `:rf/machine-type`,
                   ;; else the actor-id itself for a singleton). An inline
                   ;; `:definition` spawn has no registered type — key its
                   ;; source-form by the actor-id.
                   source-id    (cond
                                  (keyword? machine-type) machine-type
                                  spawned?                actor-id
                                  :else                   actor-id)]
               (if spec
                 (assoc acc actor-id
                        (-> (node-for actor-id source-id spec
                                      (registrar/lookup :event source-id))
                            (assoc :spawned? spawned?)
                            (cond-> (contains? snapshot :state)
                              (assoc :state (:state snapshot)))))
                 acc)))
           {}
           (or snapshots {})))))))

(defn machine-selector?
  "True iff the subscription registered under `sub-id` is a MACHINE SELECTOR —
  an ordinary `reg-sub` whose static `:<-` inputs include a `[:rf/machine …]`
  (or `[:rf.machine/has-tag? …]`) query vector (Derivations §Machine process
  and selector).

  Machine selectors are NOT a second subscription system — they stay ORDINARY
  ephemeral `:derivation` subscription nodes (classified by
  `re-frame.subs.tooling/sub-algebra-view`). This recognizer lets a graph tool
  add the `:machine-selector` refinement to such a node and draw the
  `:selector`-role edge from the machine `:process` to the selector
  `:derivation` (the machine is the stateful process; the selector an ephemeral
  derivation over its materialized snapshot — Derivations §Machine process and
  selector). Only the STATIC `:<-` form is recognized: a `:parametric`
  input-fn sub's realized edges are not statically enumerable (the don't-execute
  rule), so a parametric selector is recognized only in the live sub-cache view.

  Returns `false` for a non-machine sub, an unregistered id, and a parametric
  input-fn sub. JVM-runnable — pure data over the registrar."
  [sub-id]
  (let [meta (registrar/lookup :sub sub-id)]
    (boolean
      (and meta
           (= :static (:input-kind meta))
           (some (fn [q] (and (vector? q)
                              (contains? #{:rf/machine :rf.machine/has-tag?}
                                         (first q))))
                 (:input-signals meta))))))

(defn machine-selector-targets
  "The SET of machine ids the subscription registered under `sub-id` reads
  as a machine selector (Derivations §Machine process and selector). Where
  `machine-selector?` answers only the boolean \"is this a
  selector?\", this returns the actual TARGET machine ids — the second
  element of each accepted `[:rf/machine machine-id …]` /
  `[:rf.machine/has-tag? machine-id …]` static `:<-` input.

  A graph tool needs the target, not just the boolean: a machine selector
  draws a `:selector` edge from the SPECIFIC machine it reads, never from
  every registered machine. The boolean `machine-selector?` could only say
  \"this sub is a selector\", which would force the graph composer to
  cross-product every machine node against every selector — a false-edge in
  multi-machine apps. This fn lets the composer draw the edge from exactly
  the `[:machine target-id]` node(s) the selector names.

  Only the STATIC `:<-` form is mined: the machine-id MUST be a literal
  keyword in the static input vector. A `[:rf.machine/has-tag? machine-id
  tag]` selector still names ONE machine id (the second element). A
  parametric input-fn sub's realized edges are not statically enumerable
  (the don't-execute rule), so a parametric selector yields `#{}` here and
  is recognized only in the live sub-cache view. An input vector whose
  machine-id is not a keyword (e.g. a param-threaded form) contributes no
  target — skipped, never executed.

  Returns `#{}` for a non-machine sub, an unregistered id, a parametric
  input-fn sub, or a selector whose machine-id is non-literal. JVM-runnable
  — pure data over the registrar."
  [sub-id]
  (let [meta (registrar/lookup :sub sub-id)]
    (if-not (and meta (= :static (:input-kind meta)))
      #{}
      (into #{}
            (keep (fn [q]
                    (when (and (vector? q)
                               (contains? #{:rf/machine :rf.machine/has-tag?}
                                          (first q))
                               (keyword? (second q)))
                      (second q))))
            (:input-signals meta)))))

;; ---- bundle-isolation sentinel ------------------------------------------
;;
;; Following the flows / subs / trace tooling split pattern:
;; `implementation/scripts/check-bundle-isolation.cjs` greps the counter
;; bundle for this exact string. The string lives ONLY in this
;; file's source body — no other namespace, no docstring, no test fixture
;; references it — so its presence in the production counter bundle proves the
;; tooling sibling's body got pulled in (most likely via a stray `:require`
;; from a production-reachable ns). The whole machines artefact is already
;; bundle-isolated from counter (counter never `:require`s `re-frame.machines`),
;; so this sentinel is the belt-and-braces guard the sibling pattern
;; standardises. The string survives `:advanced` because string literals are
;; not renamed; it sits outside any gate so DCE cannot drop the literal
;; independently of the surrounding ns body.

(defonce ^:private bundle-isolation-sentinel
  "rf.machines.tooling/sentinel:rf2-2axssk-2026-06-12:do-not-rename")
