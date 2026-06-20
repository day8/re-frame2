(ns re-frame.flows.tooling
  "Flows tooling sibling of `re-frame.flows` — carries the
  derivation/process algebra view of registered flows (`flow-algebra-view`)
  that pair tools (Xray, re-frame2-pair-mcp) and the conformance fixtures
  query, but no production application code reads.

  Mirrors the `re-frame.subs.tooling` / `re-frame.trace.tooling` split:
    - CLJS consumers needing the surface call
      `re-frame.flows.tooling/<name>` directly. Apps that load the flows
      artefact but never attach a tool DCE the body wholesale (the sibling
      ns is loaded only when a test fixture, tool, or dev preload requires
      it; the `re-frame.flows` facade never `:require`s it).
    - JVM consumers keep an ergonomic `re-frame.flows/<name>` alias (gated
      under `#?(:clj ...)`). The JVM has no bundle to protect; the alias
      costs nothing. The whole flows artefact is already bundle-isolated
      from production builds (counter never `:require`s `re-frame.flows`),
      so this sibling can never reach a no-flows app's bundle; the
      explicit sentinel at the foot of this ns additionally proves no
      stray `:require` ever pulled the body in.

  READ-ONLY over the registry: this ns touches NEITHER the core registrar
  write-path NOR the flow registration signatures, reading the per-frame flow
  registry through the public accessor
  `re-frame.flows.registry/flows-snapshot`.

  Per [Derivations.md](../../../../../../spec/Derivations.md) and the
  projected Malli shapes in [Spec-Schemas
  §`:rf/derivation-node`](../../../../../../spec/Spec-Schemas.md)."
  (:require [re-frame.derivation.node :as node]
            [re-frame.flows.registry :as registry]
            [re-frame.registrar :as registrar]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- algebra views -------------------------------------------------------
;;
;; Per [Derivations.md] §Worked equivalence — the flow is the canonical
;; `:app-db` / `:after-event` / `:frame` MATERIALIZED member of the
;; derivation/process algebra (the subscription's policy twin: same
;; whole-value function, different output / storage / evaluation /
;; lifecycle). Every `reg-flow` registration is an instance of the algebra:
;; a `:derivation` (subscriptions AND flows are derivations, never processes
;; — Derivations §Derivation), whose output is MATERIALIZED into app-db,
;; evaluated `:after-event` (inside the event drain, per Spec 013 §Drain
;; integration), owned by the `:frame` it registered against.
;;
;; This is the *registrar-derived* slice (Derivations §The EP-0013
;; relocation seam): the algebra view is assembled from the flow-map
;; metadata at the surface that registered it. These functions live in the
;; bundle-isolated tooling sibling, consumed by Xray + the conformance
;; fixtures; there is no `re-frame.core` facade export.
;;
;; The five fixed classifications for EVERY flow node (Derivations §Worked
;; equivalence — the flow column):
;;   :kind          :derivation
;;   :storage       :app-db               (materialized into the app-db partition)
;;   :evaluation    :after-event          (evaluated inside the event drain)
;;   :lifecycle     :frame                (the frame owns it; destroy-frame! releases it)
;;   :materialized? true                  (the output has a durable app-db address)
;; The per-flow axes that vary are the declared INPUTS and the OUTPUT path.
;;
;; A flow consumes the slice-1 vocabulary verbatim — it does NOT redefine
;; the `:rf/storage-class` / `:rf/evaluation-policy` / `:rf/lifecycle` enums
;; or the `:rf/derivation-node` shape (those are owned by Spec-Schemas /
;; Derivations).

(defn- declared-input
  "Lower ONE flow `:inputs` path to its algebra declared-input form
  (Derivations §Declared input). A bare path is an app-db read `[:db path]`;
  a partition-qualified `[:rf.db/runtime …rest]` path is a runtime-db read
  `[:runtime …rest]` (the partition key stripped). Flow inputs are always
  concrete app-db / runtime-db paths — never `:sub` edges or the
  `:parametric` marker — because a flow declares its dependency graph
  statically as paths (Spec 013 §Flow shape).

  Routes the partition test and the key strip through the SHARED
  `registry/runtime-input?` / `registry/input-resolve-path` primitives
  so the projection, the value resolver, and the elision declaration-path
  resolver all key on the one home for the rule."
  [path]
  (if (registry/runtime-input? path)
    [:runtime (registry/input-resolve-path path)]
    [:db (registry/input-resolve-path path)]))

(defn- declared-inputs
  "Project a flow's `:inputs` vector into the algebra view's declared
  inputs — each path lowered per `declared-input`, in declaration order
  (so a downstream tool can reconstruct the dependency graph the `:derive`
  fn's positional args expect)."
  [flow]
  (mapv declared-input (:inputs flow)))

(defn- frame-matched-source-coords
  "Return the `:ns` / `:line` / `:file` source-coordinate map for `flow-id`
  on `frame-id`, or `nil` when none apply. The per-frame `flows` registry
  stores the raw flow-map WITHOUT source coords — `reg-flow` runs the flow
  through `source-coords/merge-coords` only when stamping the `:flow`
  registrar slot. So coords are read (READ-only) from that slot.

  The `:flow` registrar slot is keyed by flow-id ALONE and holds the
  MOST-RECENTLY-REGISTERED frame's metadata (Spec 013 §Frame-scoping), so it
  is frame-blind. To stay frame-SAFE we attach the coords only when the
  slot's stamped `:frame` matches THIS node's frame — a different frame's
  same-id flow never inherits the wrong source location. A frame whose
  same-id flow is not the slot's current owner simply carries no `:source`
  (the conservative, never-wrong choice over a cross-frame mis-attribution)."
  [frame-id flow-id]
  (let [slot (registrar/lookup :flow flow-id)]
    (when (= frame-id (:frame slot))
      (let [source (node/source-coords slot)]
        (when (seq source) source)))))

(defn- with-metadata
  "Attach source coordinates, the `:schema` fact, the `:doc`, and the opaque
  `:derive` body token to a node when present. `:schema` / `:doc` are
  user-supplied flow-map keys, read straight off the frame-scoped flow-map
  (frame-safe). The `:source` coords are read frame-matched from the
  registrar slot (the only place `reg-flow` records them). The `:derive` fn
  is the flow's whole-value derivation — surfaced under `:derive` as an
  opaque token so a tool can show the function identity / source without it
  being serialized."
  [node frame-id flow]
  (let [flow-id (:id flow)
        source  (frame-matched-source-coords frame-id flow-id)]
    (cond-> node
      (some? source)           (assoc :source source)
      (contains? flow :schema) (assoc :schema (:schema flow))
      (contains? flow :doc)    (assoc :doc (:doc flow))
      (contains? flow :derive) (assoc :derive (:derive flow)))))

(defn- node-for
  "Build the derivation/process algebra view of one flow-map (Derivations
  §The node shape; Spec-Schemas §`:rf/derivation-node`). `frame-id` is the
  frame the flow registered against — flows are frame-scoped (Spec 013
  §Frame-scoping), so the owning frame is part of the node's lifecycle
  contract and is surfaced under `:owner`.

  The fixed-classification spine — a `:derivation` whose materialized output
  lands in app-db, evaluated `:after-event`, owned by its `:frame` — plus
  the per-flow `:inputs` (lowered app-db / runtime-db paths), `:output`
  (`[:db <:output-path>]`), `:source-form`, opaque `:derive` token, and source
  coords / schema / doc."
  [frame-id flow]
  (let [flow-id (:id flow)]
    (-> (node/node-base flow-id [:db (vec (:output-path flow))]
                        {:kind          :derivation
                         :storage       :app-db
                         :evaluation    :after-event
                         :lifecycle     :frame
                         :materialized? true})
        (assoc :source-form {:kind :reg-flow :id flow-id}
               :inputs      (declared-inputs flow)
               :owner       [:frame frame-id])
        (with-metadata frame-id flow))))

(defn flow-algebra-view
  "Return the STATIC derivation/process algebra view of every registered
  flow ([Derivations.md], [Spec-Schemas §`:rf/derivation-node`]).

  Pure data over the per-frame flow registry — read through the public
  `re-frame.flows.registry/flows-snapshot` seam, never touching the registrar
  write-path or the flow registration signatures. The algebra
  view companion to `flows-snapshot`: where `flows-snapshot` returns the raw
  `{frame-id {flow-id flow-map}}` registry, this lowers every flow into the
  normalized `:rf/derivation-node` shape the derivation/process algebra
  names, so a tool can show subscriptions, flows, resources, route facts,
  and machine selectors as ONE family.

  Flows are FRAME-SCOPED (Spec 013 §Frame-scoping): the same flow-id can
  register against two frames with different `:inputs` / `:derive` / `:output-path`,
  so the view preserves the frame dimension — keyed the same way as
  `flows-snapshot`.

  Each node carries:

  - `:id`          — the flow id (its canonical fact identity).
  - `:kind`        — `:derivation` (flows are derivations, never processes —
                     Derivations §Derivation).
  - `:source-form` — `{:kind :reg-flow :id <flow-id>}`.
  - `:inputs`      — the declared inputs (Derivations §Declared input): each
                     `:inputs` path lowered to `[:db path]` (a bare app-db
                     read) or `[:runtime …rest]` (a `[:rf.db/runtime …]`
                     partition-qualified runtime-db read — EP-0001 §535-551),
                     in declaration order.
  - `:output`      — `[:db <:output-path>]` — the flow MATERIALIZES its whole value
                     into the app-db partition at its `:output-path` (flow writes
                     are app-db only — Spec 013 §Input partition).
  - `:storage`     — `:app-db`.
  - `:evaluation`  — `:after-event` (evaluated inside the event drain — Spec
                     013 §Drain integration).
  - `:lifecycle`   — `:frame`.
  - `:owner`       — `[:frame <frame-id>]` (the frame the flow registered
                     against; `destroy-frame!` releases it).
  - `:materialized?` — `true`.
  - `:derive`      — the opaque `:derive` body-fn token (never serialized).
  - `:source` / `:schema` / `:doc` — present when the registration carried
                     them (source coords auto-captured by `reg-flow` via
                     `source-coords/merge-coords`).

  Zero-arity returns `{frame-id {flow-id <node>}}` for every registered flow
  (`{}` when none). The one-arity form returns `{flow-id <node>}` for a
  single frame (`{}` when the frame has no flows). JVM-runnable — the flow
  registry is partition-agnostic registration metadata.

  Lives in the bundle-isolated tooling sibling and is consumed by Xray + the
  conformance fixtures. There is no `re-frame.core/flow-algebra-view` facade
  export."
  ([]
   (let [snapshot (registry/flows-snapshot)]
     (reduce-kv
       (fn [acc frame-id flow-map]
         (assoc acc frame-id
                (reduce-kv
                  (fn [m flow-id flow]
                    (assoc m flow-id (node-for frame-id flow)))
                  {}
                  flow-map)))
       {}
       snapshot)))
  ([frame-id]
   (let [flow-map (get (registry/flows-snapshot) frame-id)]
     (reduce-kv
       (fn [m flow-id flow]
         (assoc m flow-id (node-for frame-id flow)))
       {}
       (or flow-map {})))))

;; ---- bundle-isolation sentinel ------------------------------------------
;;
;; Per the subs.tooling + trace.tooling split pattern:
;; `implementation/scripts/check-bundle-isolation.cjs` greps the counter
;; bundle for this exact string. The string lives ONLY in this
;; file's source body — no other namespace, no docstring, no test fixture
;; references it — so its presence in the production counter bundle proves
;; the tooling sibling's body got pulled in (most likely via a stray
;; `:require` from a production-reachable ns). The whole flows artefact is
;; already bundle-isolated from counter (counter never `:require`s
;; `re-frame.flows`), so this sentinel is the belt-and-braces guard the
;; sibling pattern standardises. The string survives `:advanced` because
;; string literals are not renamed; it sits outside any gate so DCE cannot
;; drop the literal independently of the surrounding ns body.

(defonce ^:private bundle-isolation-sentinel
  "rf.flows.tooling/sentinel:rf2-s8w3nw-2026-06-12:do-not-rename")
