(ns re-frame.derivation.graph
  "Internal derivation/process GRAPH-INSPECTION helper (EP-0014 slice-7,
  rf2-6xm07h) — the composer that assembles the full static + live
  derivation/process graph by stitching the five algebra-view tooling
  siblings (subs / flows / routes / resources / machines) into ONE
  queryable `DerivationGraph` view.

  Per [spec/Derivations.md] §Graph inspection — internal but structured
  (graduated from EP-0014) and the projected Malli shapes in
  [Spec-Schemas §`:rf/derivation-graph`]. Where each sibling projects ONE
  family's nodes (`re-frame.subs.tooling/sub-algebra-view`,
  `re-frame.flows.tooling/flow-algebra-view`,
  `re-frame.routing.tooling/route-algebra-view`,
  `re-frame.resources.tooling/resource-algebra-view`,
  `re-frame.machines.tooling/machine-algebra-view`, plus their live
  counterparts), this helper composes those per-family projections into a
  single `{:mode :nodes :edges}` graph — the shape an Xray panel renders
  as ONE graph even though the underlying runtime mechanisms are
  subscription cache, flow registry, route slice, resource cache, and
  machine snapshots.

  SLICE SCOPE (EP-0014 issue-1 disposition). This ships NO public
  authoring primitive and NO stable public graph accessor — it is the
  *internal structured shape* the deferred public accessor will one day
  produce, consumed FIRST by Xray and the conformance fixtures (the two
  named first consumers). The public name is deferred until a third
  consumer needs it (the [graduation gate]). There is no
  `re-frame.core` facade export and no api-manifest row: like the five
  siblings' CLJS-side fns, this is reached directly by the consuming tool
  (Xray statically `:require`s the tooling siblings it has), JVM-aliased
  nowhere, and bundle-isolated from production builds (the explicit
  sentinel at the foot of this ns proves no stray `:require` pulled the
  body in).

  BUNDLE ISOLATION + THE CONTRIBUTOR SEAM. The four OPTIONAL feature
  siblings (flows / routing / machines / resources) live in separate
  Maven artefacts that core does NOT depend on — a static `:require` from
  this core ns to `re-frame.flows.tooling` would fail to compile in a
  core-only (no-flows) app. So this helper composes whatever families are
  PRESENT through a *contributor map* rather than a static cross-artefact
  require:

    - `re-frame.subs.tooling` lives in core and IS required directly (it
      is always present).
    - The four optional siblings contribute through the `contributors`
      argument — a `{family {:static-fn f :live-fn f :live-arity …}}`
      map. On the JVM `default-contributors` auto-resolves each sibling's
      tooling fns via `requiring-resolve` (returning only the families
      whose artefact is on the classpath); on CLJS the consuming tool
      (which already statically `:require`s its siblings) passes the
      contributor map explicitly. A family whose artefact is absent
      simply contributes no nodes — exactly the no-flows / no-resources
      story the five siblings already each honour.

  This is the *registrar-derived* slice (Derivations §The EP-0013
  relocation seam): the graph is assembled from the per-family algebra
  views, which are themselves assembled from registration metadata, NOT
  (yet) from an EP-0013 app value."
  (:require [re-frame.subs.tooling :as subs-tooling]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The five families.
;;
;; Each family is one member of the derivation/process algebra (Derivations
;; §The vocabulary). The order is editorial: the canonical reading order of
;; spec/Derivations.md (subscriptions → flows → resources → routes →
;; machines). Tools that group the graph by family read this vector.
;; ---------------------------------------------------------------------------

(def families
  "The five algebra-view families this helper composes (Derivations §Why
  this doc exists — the six surfaces, with runtime-subs folded into
  `:subs`). Each maps to one tooling sibling's static + live projection."
  [:subs :flows :resources :routes :machines])

;; ---------------------------------------------------------------------------
;; The contributor seam.
;;
;; A contributor is `{:static-fn  (fn [] -> family-static-projection)
;;                    :live-fn    (fn [frame-id] -> family-live-projection)
;;                    :live-shape :map | :node}`.
;;
;; `:live-shape :map`  — the live-fn returns `{node-key node}` (subs / flows
;;                       per-frame / resources / machines instances).
;; `:live-shape :node` — the live-fn returns ONE node or nil (the route
;;                       slice).
;;
;; The `:static-fn` / `:live-fn` are usually two DISTINCT sibling fns (the
;; subs / resources / routes / machines pattern: a registration-derived
;; static view + a runtime-cache / runtime-db live view). FLOWS are the
;; exception: a flow has no separate ephemeral cache to snapshot — it
;; materializes into app-db and is frame-scoped — so its ONE
;; `flow-algebra-view` serves both slots. The static-fn is its zero-arity
;; (every frame's flows, `{frame-id {flow-id node}}`); the live-fn is the
;; SAME symbol invoked one-arity (`(flow-algebra-view frame-id)` →
;; `{flow-id node}`, the `:map` shape). The "live" flow projection is thus
;; the per-frame static projection — there is nothing further to realize.
;;
;; The subs contributor is built from the in-core sibling; the four optional
;; contributors are resolved (JVM) or supplied (CLJS).
;; ---------------------------------------------------------------------------

(def subs-contributor
  "The `:subs` family contributor — `re-frame.subs.tooling` lives in core
  and is required directly. Both the static `sub-algebra-view` and the
  live (per-frame cache) `sub-cache-algebra-view` are in-core fns.

  The flow / runtime / frame-state subscription families are ALL one
  `:subs` family here: they are all `re-frame.subs` registrations and all
  lower through the one `sub-algebra-view` (Derivations §Subscriptions
  expose algebra views — a `reg-runtime-sub` is an ordinary algebra node)."
  {:static-fn  subs-tooling/sub-algebra-view
   :live-fn    subs-tooling/sub-cache-algebra-view
   :live-shape :map})

#?(:clj
   (defn- resolve-sibling
     "JVM-only: resolve one optional sibling's `[static-sym live-sym]`
     tooling fns via `requiring-resolve`, returning a contributor map, or
     nil when the artefact is absent from the classpath (the
     `requiring-resolve` throws `FileNotFoundException` / returns nil).
     Used by `default-contributors` so a core-only JVM process composes
     only the families whose artefact is loaded — the no-flows /
     no-resources story."
     [static-sym live-sym live-shape]
     (try
       (let [s (requiring-resolve static-sym)
             l (requiring-resolve live-sym)]
         (when (and s l)
           {:static-fn @s :live-fn @l :live-shape live-shape}))
       (catch java.io.FileNotFoundException _ nil)
       (catch Throwable _ nil))))

(defn default-contributors
  "The default `{family contributor}` map.

  On the JVM, auto-resolves the four optional siblings' tooling fns via
  `requiring-resolve` (returning only the families whose artefact is on
  the classpath) and always includes the in-core `:subs` contributor.

  On CLJS, returns ONLY the in-core `:subs` contributor — the consuming
  tool (which statically `:require`s the optional siblings it has) supplies
  the rest via the `contributors` argument to `derivation-graph` /
  `live-derivation-graph`. This keeps the four optional sibling bodies out
  of a no-feature CLJS bundle (a static `:require` from this core ns would
  defeat the bundle-isolation the siblings each guard with their sentinel)."
  []
  (merge
   {:subs subs-contributor}
   #?(:clj
      (into {}
            (keep (fn [[family static-sym live-sym live-shape selector-targets-sym]]
                    (when-let [c (resolve-sibling static-sym live-sym live-shape)]
                      ;; the machines family additionally carries the
                      ;; optional `machine-selector-targets` extractor so the
                      ;; graph can draw `:selector` edges to the SPECIFIC
                      ;; machine each selector reads, never the cross product
                      ;; (Derivations §Machines: selectors are ordinary
                      ;; derivations the machine draws a :selector edge to;
                      ;; rf2-4qmiij — precise targeting, no false edges).
                      [family (cond-> c
                                selector-targets-sym
                                (assoc :selector-targets
                                       (some-> (requiring-resolve selector-targets-sym) deref)))])))
            ;; flows reuse the ONE `flow-algebra-view` for both slots
            ;; (static = zero-arity all-frames, live = one-arity per-frame —
            ;; a flow has no separate ephemeral cache to snapshot; see the
            ;; contributor-seam comment above).
            [[:flows     're-frame.flows.tooling/flow-algebra-view
                         're-frame.flows.tooling/flow-algebra-view :map]
             [:resources 're-frame.resources.tooling/resource-algebra-view
                         're-frame.resources.tooling/resource-cache-algebra-view :map]
             [:routes    're-frame.routing.tooling/route-algebra-view
                         're-frame.routing.tooling/route-slice-algebra-view :node]
             [:machines  're-frame.machines.tooling/machine-algebra-view
                         're-frame.machines.tooling/machine-instance-algebra-view :map
                         're-frame.machines.tooling/machine-selector-targets]])
      :cljs nil)))

;; ---------------------------------------------------------------------------
;; Node identity + collection.
;;
;; Each family's projection returns nodes keyed by the family's natural key
;; (sub-id, flow-id, route-id, resource-id, machine-id, or a concrete live
;; key). The graph keys every node by its CANONICAL NODE ID (Derivations
;; §Graph inspection — canonical node ids under EP-0012 identity rules): the
;; `[:sub …] / [:flow …] / [:resource …] / [:machine …]` / `:rf/route`
;; tagged form a tool draws edges between.
;; ---------------------------------------------------------------------------

(defn node-id
  "The canonical graph node id for one algebra node in `family`
  (Derivations §Graph inspection / §Fact identity). The node's own `:id`
  is the family-local fact identity; this lifts it into the family-tagged
  node id the graph + its edges reference:

    :subs      → `[:sub <id>]`, where `<id>` is the node's `:id`: a bare
                 sub-id keyword for a STATIC node (`[:sub :cart/total]`), or
                 the concrete query vector for a LIVE cache-entry node
                 (`[:sub [:cart/total …args]]`). Wrapping unconditionally
                 keeps every subscription node id uniformly tagged.
    :flows     → `[:flow <flow-id>]`.
    :resources → `[:resource <resource-id-or-scoped-key>]`.
    :machines  → `[:machine <machine-or-actor-id>]`.
    :routes    → the route fact id `:rf/route` (every route materializes
                 the ONE slice — Derivations §Routes expose algebra views;
                 the per-route source-form id distinguishes static nodes)."
  [family node]
  (let [id (:id node)]
    (case family
      ;; a STATIC sub node's :id is the bare sub-id keyword → [:sub <id>];
      ;; a LIVE cache-entry node's :id is the concrete query vector →
      ;; [:sub [<id> …args]]. Both forms are valid sub node ids in their
      ;; respective graphs.
      :subs      [:sub id]
      :flows     [:flow id]
      :resources [:resource id]
      :machines  [:machine id]
      :routes    id
      [family id])))

(defn- sub-input-node-id
  "Normalize a `[:sub query-vector]` DECLARED INPUT to the node id of the
  subscription it names, in `mode`. In a `:static` graph, subscription
  nodes are keyed by bare sub-id (`[:sub :cart/items]`), so a declared
  input `[:sub [:cart/items …args]]` resolves to `[:sub :cart/items]` (the
  query vector's head — the sub-id). In a `:live` graph, nodes are keyed
  by the concrete query vector, so the input resolves to `[:sub
  query-vector]` verbatim. This is what lets an edge's upstream end MATCH a
  node key in the same graph."
  [mode query-vector]
  (if (= :static mode)
    [:sub (if (vector? query-vector) (first query-vector) query-vector)]
    [:sub query-vector]))

(defn- family-static-nodes
  "Collect one family's STATIC nodes from its contributor, returning a
  `{node-id node}` map (the node carries `:rf/family` so a tool can group
  or filter by family). A family with no contributor (artefact absent)
  contributes `{}`. Subs / flows / machines / resources project a
  `{key node}` map; flows are doubly-keyed `{frame-id {flow-id node}}` so
  we flatten the frame dimension; routes project `{route-id node}` (all
  carrying the same `:rf/route` fact id, distinguished by source-form)."
  [family contributor]
  (if-not contributor
    {}
    (let [projection ((:static-fn contributor))]
      (case family
        ;; flows are keyed {frame-id {flow-id node}} — flatten to nodes.
        :flows
        (reduce-kv
         (fn [acc _frame-id flow-map]
           (reduce-kv
            (fn [m _flow-id node]
              (assoc m (node-id family node) (assoc node :rf/family family)))
            acc
            flow-map))
         {}
         projection)
        ;; routes: many source forms, one fact id — key each STATIC route
        ;; node by [:rf/route <source-form-id>] so per-route resource
        ;; edges are not collapsed onto one slot.
        :routes
        (reduce-kv
         (fn [acc route-id node]
           (assoc acc [:rf/route route-id] (assoc node :rf/family family)))
         {}
         projection)
        ;; subs / resources / machines: {natural-key node}.
        (reduce-kv
         (fn [acc _k node]
           (assoc acc (node-id family node) (assoc node :rf/family family)))
         {}
         projection)))))

(defn- family-live-nodes
  "Collect one family's LIVE nodes for `frame-id`, returning `{node-id
  node}`. Honours the contributor's `:live-shape`: `:map` projections
  return `{key node}`; `:node` projections (the route slice) return ONE
  node or nil. A live-fn that returns nil (a missing/destroyed frame or an
  unmaterialized fact — or a production CLJS build where the body DCEs)
  contributes `{}`."
  [family contributor frame-id]
  (if-not contributor
    {}
    (let [live-fn (:live-fn contributor)
          result  (live-fn frame-id)]
      (case (:live-shape contributor)
        :node (if (nil? result)
                {}
                {(node-id family result) (assoc result :rf/family family)})
        ;; :map — {key node}; nil (prod DCE / missing frame) → {}.
        (reduce-kv
         (fn [acc _k node]
           (assoc acc (node-id family node) (assoc node :rf/family family)))
         {}
         (or result {}))))))

;; ---------------------------------------------------------------------------
;; Edge extraction.
;;
;; Edges are explicit `{:from <node-id> :to <node-id> :role <role>}` records
;; (Derivations §Graph inspection — explicit edge records). They are derived
;; from each node's DECLARED projection — never by executing anything:
;;
;;   - `:input`    — a node's `[:sub q]` declared input → an edge from the
;;                   upstream sub node to this node.
;;   - `:param`    — a route node's `:resource-edges` (route-owned resource
;;                   activation — Derivations §Route-owned resource
;;                   activation edges) ride through verbatim.
;;   - `:selector` — a machine `:process` node → each of the machine
;;                   SELECTOR subscription nodes that read THAT machine
;;                   (Derivations §Machines: selectors are ordinary
;;                   derivations; the machine draws the `:selector` edge to
;;                   them). The selector's TARGET machine ids are mined from
;;                   its static `[:rf/machine …]` inputs (not the boolean
;;                   recognizer), so the edge runs from the specific machine
;;                   each selector reads — never the cross product
;;                   (rf2-4qmiij). Computed by `machine-selector-edges`.
;;
;; A `:parametric` `:inputs` marker contributes NO static edges (the
;; don't-execute rule — Derivations §Static and live graphs); its realized
;; edges appear only in the live graph (where the live sub-cache node's
;; `:inputs` are concrete `[:sub q]` forms).
;; ---------------------------------------------------------------------------

(defn- input-edges
  "Edges from one node's DECLARED `:inputs` (Derivations §Declared input).
  Only `[:sub <query-vector>]` inputs become graph edges here — they name
  another subscription node by its `[:sub …]` id, which is exactly the
  canonical node id (`node-id :subs`). `[:db …]` / `[:runtime …]` /
  `[:event …]` / `[:param …]` / `[:scope …]` inputs name source facts /
  triggers that are not (yet) nodes in the graph, so they do not become
  node→node edges. The `:parametric` marker contributes nothing (the
  don't-execute rule)."
  [mode to-id node]
  (let [inputs (:inputs node)]
    (if (or (not (sequential? inputs)) (= :parametric inputs))
      []
      (->> inputs
           (keep (fn [in]
                   (when (and (vector? in) (= :sub (first in)))
                     {:from (sub-input-node-id mode (second in))
                      :to   to-id
                      :role :input})))
           vec))))

(defn- resource-activation-edges
  "A route node's `:resource-edges` (route-owned resource activation —
  Derivations §Route-owned resource activation edges) lifted to graph
  edges. The sibling already produced `{:from … :to [:resource <id>]
  :role :param :target :parametric}` records; we re-target `:from` to this
  route node's id (the `:rf/route` slice the route materializes) and carry
  the static `:resource` id + `:target` / `:blocking?` through verbatim."
  [from-id node]
  (->> (:resource-edges node)
       (map (fn [edge]
              (-> edge
                  (assoc :from from-id)
                  (update :to (fn [to] (if (vector? to) to [:resource to]))))))
       vec))

(defn- machine-selector-edges
  "The `:selector`-role edges from each machine `:process` node to the
  machine-selector subscription nodes that read THAT machine (Derivations
  §Machines: selectors are ordinary derivations; the machine draws the
  `:selector` edge to them). `selector-targets` is the optional
  `machine-selector-targets` extractor from the machines sibling (passed
  through the `:machines` contributor's `:selector-targets` slot, or nil
  when machines is absent): it returns the SET of machine ids one selector
  subscription reads.

  Precise targeting (rf2-4qmiij): for each subscription node we ask which
  machine ids it selects, then emit a `:selector` edge ONLY from each
  `[:machine target-id]` node that actually EXISTS in the graph — never the
  cross product of every machine against every selector. A selector that
  names a machine which is not a node (unregistered / a different family)
  contributes no edge for that target. Indexing is by the machine-node-id
  SET so the scan is `O(subs × targets-per-sub)`, not `O(machines × subs)`.

  Returns `[]` when the extractor or machine nodes are absent — the
  don't-execute rule applies (the extractor reads registrar metadata, never
  runs a sub body)."
  [machine-node-ids subs-node-ids selector-targets]
  (if (or (nil? selector-targets) (empty? machine-node-ids))
    []
    (let [machine-node-set (set machine-node-ids)]
      (vec
       (for [sub-id    subs-node-ids
             :let      [raw (second sub-id)]            ;; [:sub <id-or-q>] → id|q
             :let      [sid (if (vector? raw) (first raw) raw)]
             :when     (keyword? sid)
             target-id (selector-targets sid)
             :let      [m-id [:machine target-id]]
             :when     (contains? machine-node-set m-id)]
         {:from m-id :to sub-id :role :selector})))))

(defn- graph-edges
  "Every edge across the assembled `nodes` map, in `mode` (`:static` /
  `:live`). Composes the per-node `:input` edges, the route `:param`
  resource-activation edges, and the machine→selector `:selector` edges,
  de-duplicated. `selector-targets` is the optional machine-selector target
  extractor (nil when machines is absent)."
  [mode nodes selector-targets]
  (let [machine-ids (->> nodes keys (filter #(and (vector? %) (= :machine (first %)))) vec)
        subs-ids    (->> nodes keys (filter #(and (vector? %) (= :sub (first %)))) vec)
        per-node    (reduce-kv
                     (fn [acc node-id node]
                       (-> acc
                           (into (input-edges mode node-id node))
                           (cond->
                             (= :rf/route (:id node))
                             (into (resource-activation-edges node-id node)))))
                     []
                     nodes)
        selector    (machine-selector-edges machine-ids subs-ids selector-targets)]
    (->> (concat per-node selector)
         distinct
         vec)))

;; ---------------------------------------------------------------------------
;; The DerivationGraph assembly.
;; ---------------------------------------------------------------------------

(defn- assemble
  "Assemble a `DerivationGraph` from a `{node-id node}` map + a `:mode`.
  Computes the edge set (the optional machine-selector target extractor
  threads through `selector-targets`)."
  [mode nodes selector-targets extra]
  (merge
   {:mode  mode
    :nodes nodes
    :edges (graph-edges mode nodes selector-targets)}
   extra))

(defn derivation-graph
  "Return the STATIC `DerivationGraph` — the full registration-derived
  derivation/process graph composed from every PRESENT family (EP-0014
  slice-7; [Derivations.md] §Graph inspection — internal but structured;
  [Spec-Schemas §`:rf/derivation-graph`]).

  Composes the five algebra-view siblings' STATIC projections (subs,
  flows, resources, routes, machines) into ONE `{:mode :static :nodes …
  :edges …}` graph:

  - `:mode`  — `:static`.
  - `:nodes` — `{node-id node}` keyed by canonical node id
               (`[:sub …] / [:flow …] / [:resource …] / [:machine …]` /
               `[:rf/route <route-id>]`). Each node is the sibling's
               algebra view verbatim plus `:rf/family`, carrying its
               declared `:inputs`, `:output`, the storage / evaluation /
               lifecycle classifications, `:source-form`, and (for
               parametric subs) the `:parametric` marker — the sibling's
               shape is NOT reshaped here.
  - `:edges` — explicit `{:from :to :role}` records: `:input` edges from a
               node's `[:sub …]` declared inputs, `:param` edges from a
               route's `:resource-edges`, and `:selector` edges from each
               machine `:process` to its selector subscriptions. A
               `:parametric` input set contributes NO static edge (the
               don't-execute rule).

  `contributors` (optional) is the `{family contributor}` map; it defaults
  to `default-contributors` (JVM: every artefact on the classpath; CLJS:
  the in-core `:subs` family only, so a CLJS tool passes its loaded
  siblings explicitly). A family absent from `contributors` simply
  contributes no nodes — the no-flows / no-resources story.

  Pure data over the per-family projections — no app-db, no per-frame
  cache, no reactive runtime; JVM-runnable. Slice-7 ships NO public
  accessor (EP-0014 issue-1): this is reached directly by Xray + the
  conformance fixtures; the public name is deferred until a third consumer
  needs it."
  ([] (derivation-graph (default-contributors)))
  ([contributors]
   (let [nodes (reduce
                (fn [acc family]
                  (merge acc (family-static-nodes family (get contributors family))))
                {}
                families)
         selector-targets (get-in contributors [:machines :selector-targets])]
     (assemble :static nodes selector-targets nil))))

(defn live-derivation-graph
  "Return the LIVE `DerivationGraph` for `frame-id` — the full graph
  derived from a frame at a point in time (EP-0014 slice-7;
  [Derivations.md] §Static and live graphs). The live counterpart to
  `derivation-graph`: where the static graph reports registration-known
  nodes + `:parametric` markers, the live graph composes the five
  siblings' LIVE projections — concrete subscription query vectors with
  REALIZED `[:sub …]` input edges, active resource cache entries keyed by
  scoped resource key, live machine instances + spawned actors, and the
  materialized route slice with its nav-token owner.

  - `:mode`  — `:live`.
  - `:frame` — `frame-id`.
  - `:nodes` — `{node-id node}` per realized fact (concrete query vectors,
               scoped resource keys, actor ids, the route slice).
  - `:edges` — the REALIZED edges: a live sub-cache node's concrete
               `[:sub q]` inputs become `:input` edges (the edges the
               static graph could not enumerate for a parametric sub), plus
               the same `:selector` edges.

  `contributors` defaults to `default-contributors`. The live projections
  are dev-gated (gated on `interop/debug-enabled?` inside each sibling) —
  in a production CLJS build the live-fns return nil and every family
  contributes `{}`, so the live graph is empty (the bodies DCE). Returns
  `{:mode :live :frame frame-id :nodes {} :edges []}` for a
  missing/destroyed frame, an app with nothing materialized, or a
  production build."
  ([frame-id] (live-derivation-graph frame-id (default-contributors)))
  ([frame-id contributors]
   (let [nodes (reduce
                (fn [acc family]
                  (merge acc (family-live-nodes family (get contributors family) frame-id)))
                {}
                families)
         selector-targets (get-in contributors [:machines :selector-targets])]
     (assemble :live nodes selector-targets {:frame frame-id}))))

;; ---- bundle-isolation sentinel ------------------------------------------
;;
;; Per rf2-2axssk / rf2-eiiifu / rf2-gn9juw / rf2-s8w3nw / rf2-bmzq0 /
;; rf2-qwm0a (the five algebra-view siblings + the trace.tooling split
;; pattern): `implementation/scripts/check-bundle-isolation.cjs` greps the
;; counter bundle for this exact string. The string lives ONLY in this
;; file's source body — no other namespace, no docstring, no test fixture
;; references it — so its presence in the production counter bundle proves
;; this graph-inspection composer's body got pulled in (most likely via a
;; stray `:require` from a production-reachable ns). This composer is
;; consumed only by dev tools (Xray) + the conformance fixtures, which
;; `:require` it directly; the counter example never does. The string
;; survives `:advanced` because string literals are not renamed; it sits
;; outside any `interop/debug-enabled?` gate so DCE cannot drop the literal
;; independently of the surrounding ns body.

(defonce ^:private bundle-isolation-sentinel
  "rf.derivation.graph/sentinel:rf2-6xm07h-2026-06-12:do-not-rename")
