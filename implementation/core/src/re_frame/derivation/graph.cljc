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
      argument — a `{family {:static-fn f :live-fn f :live-shape …}}`
      map. On the JVM `default-contributors` auto-resolves each sibling's
      tooling fns via `requiring-resolve` (returning only the families
      whose artefact is on the classpath); on CLJS the consuming tool
      (which already statically `:require`s its siblings) passes the
      contributor map explicitly. A family whose artefact is absent
      simply contributes no nodes — exactly the no-flows / no-resources
      story the five siblings already each honour.

  FAMILY-AGNOSTIC ASSEMBLY (rf2-k0meap.8). The central assembler stitches
  nodes + edges WITHOUT knowing any family's mechanics. Every
  family-specific decision — how a family's projection keys lift into the
  canonical node id, and how a node's declared projection yields edges —
  lives in the family's CONTRIBUTOR CONTRACT (`:node-id-fn` / `:edge-fn`),
  beside the family that owns it (see `family-contract` below). A
  contributor that omits these inherits the per-family contract by family
  key; a contributor that supplies its own participates without any edit
  to the central `node-id` / `collect-*` / `graph-edges` functions — a NEW
  family needs only a contributor (its own contract pieces or a new
  `family-contract` entry), never a new conditional in the assembler.

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
  `:subs`). Each maps to one tooling sibling's static + live projection.
  This is the canonical READING ORDER; the assembler composes whatever
  families a `contributors` map actually carries (a synthetic family beyond
  these five participates too — rf2-k0meap.8), these first."
  [:subs :flows :resources :routes :machines])

(defn- contributor-families
  "The families to compose for a `contributors` map: the canonical
  `families` (in reading order) that the map carries, followed by any EXTRA
  families the map carries beyond the canonical five (a synthetic family —
  rf2-k0meap.8). Family-agnostic: the assembler iterates THIS, never a
  hard-coded family list, so a contributor-supplied family participates with
  no central edit."
  [contributors]
  (let [present (filter #(contains? contributors %) families)
        extra   (remove (set families) (keys contributors))]
    (concat present extra)))

;; ---------------------------------------------------------------------------
;; Family-local node identity.
;;
;; Each family's projection returns nodes keyed by the family's natural key
;; (sub-id, flow-id, route-id, resource-id, machine-id, or a concrete live
;; key). The graph keys every node by its CANONICAL NODE ID (Derivations
;; §Graph inspection — canonical node ids under EP-0012 identity rules): the
;; `[:sub …] / [:flow …] / [:resource …] / [:machine …]` / `:rf/route`
;; tagged form a tool draws edges between.
;;
;; Each family OWNS its node-id construction (rf2-k0meap.8) — a one-arg
;; `(fn [node] -> node-id)` published on the contributor's `:node-id-fn`.
;; The central collector calls it; it never branches on family. A NEW
;; family supplies its own `:node-id-fn` (or a `family-contract` entry) and
;; participates with no edit to the collector.
;; ---------------------------------------------------------------------------

(defn- sub-node-id
  "The `:subs` family node id. A STATIC sub node's `:id` is the bare sub-id
  keyword → `[:sub :cart/total]`; a LIVE cache-entry node's `:id` is the
  concrete query vector → `[:sub [:cart/total …args]]`. Wrapping
  unconditionally keeps every subscription node id uniformly `[:sub …]`."
  [node]
  [:sub (:id node)])

(defn- flow-node-id
  "The `:flows` family node id — FRAME-SCOPED (rf2-k0meap.2). The same
  `flow-id` may register against two frames with different `:inputs` /
  `:output` / `:path`, so the node id carries the owning frame to keep the
  per-frame flow facts distinct (Derivations §Flows expose algebra views —
  the view preserves `{frame-id {flow-id node}}`). The frame is read off
  the node's `:owner` `[:frame <frame-id>]` (the lifecycle owner the flow
  view stamps); absent an `:owner` the id degrades to `[:flow <flow-id>]`
  (a hand-built fixture)."
  [node]
  (let [owner (:owner node)
        id    (:id node)]
    (if (and (vector? owner) (= :frame (first owner)))
      [:flow (second owner) id]
      [:flow id])))

(defn- resource-node-id
  "The `:resources` family node id — `[:resource <resource-id-or-scoped-key>]`."
  [node]
  [:resource (:id node)])

(defn- machine-node-id
  "The `:machines` family node id — `[:machine <machine-or-actor-id>]`."
  [node]
  [:machine (:id node)])

(defn- route-node-id
  "The `:routes` family node id. Every route materializes the ONE slice
  fact `:rf/route` (Derivations §Routes expose algebra views), so a node's
  own `:id` IS the node id — the per-route source-form id distinguishes
  static route nodes by their map key, not by `:id` (see the route family's
  `:static-key-fn` in `family-contract`)."
  [node]
  (:id node))

;; ---------------------------------------------------------------------------
;; Family-local edge extraction.
;;
;; Edges are explicit `{:from <node-id> :to <node-id> :role <role>}` records
;; (Derivations §Graph inspection — explicit edge records). They are derived
;; from each node's DECLARED projection — never by executing anything:
;;
;;   - `:input`    — a node's `[:sub q]` declared input → an edge from the
;;                   upstream sub node to this node (every family carries the
;;                   common input-edge derivation).
;;   - `:param`    — a route node's `:resource-edges` (route-owned resource
;;                   activation — Derivations §Route-owned resource
;;                   activation edges) ride through verbatim; PLUS the live
;;                   route-owned realized resource owner edges (live only).
;;   - `:selector` — a machine `:process` node → each of the machine
;;                   SELECTOR subscription nodes that read THAT machine
;;                   (Derivations §Machines: selectors are ordinary
;;                   derivations the machine draws a :selector edge to).
;;
;; A `:parametric` `:inputs` marker contributes NO static edges (the
;; don't-execute rule — Derivations §Static and live graphs); its realized
;; edges appear only in the live graph (where the live sub-cache node's
;; `:inputs` are concrete `[:sub q]` forms).
;;
;; Each family OWNS the edges its nodes contribute BEYOND the common
;; `:input` edges (rf2-k0meap.8) via the contributor's `:edge-fn`
;; `(fn [ctx] -> [edges…])`, where `ctx` is the graph-wide assembly context
;; `{:mode :nodes :node-ids …}`. The central assembler runs the common
;; `:input` derivation over every node, then folds in each present family's
;; `:edge-fn`. A family with no extra edges supplies no `:edge-fn`.
;; ---------------------------------------------------------------------------

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

(defn- input-edges
  "Edges from one node's DECLARED `:inputs` (Derivations §Declared input).
  Only `[:sub <query-vector>]` inputs become graph edges here — they name
  another subscription node by its `[:sub …]` id, which is exactly the
  canonical node id. `[:db …]` / `[:runtime …]` / `[:event …]` /
  `[:param …]` / `[:scope …]` inputs name source facts / triggers that are
  not (yet) nodes in the graph, so they do not become node→node edges. The
  `:parametric` marker contributes nothing (the don't-execute rule).

  This is the ONE edge derivation common to EVERY family (a node of any
  family may declare `[:sub …]` inputs), so the central assembler runs it
  over every node — it is NOT a per-family `:edge-fn`."
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

(defn- realized-route-owner
  "The live route node's realized owner `[:route route-id nav-token]`, or
  nil. The live route slice carries `:owner` when both the matched route id
  and the nav-token are known (`route-slice-algebra-view`); a static route
  node carries no `:owner`."
  [node]
  (let [owner (:owner node)]
    (when (and (vector? owner) (= :route (first owner)))
      owner)))

(defn- route-edges
  "The `:routes` family `:edge-fn`. Composes a route's STATIC route-owned
  resource activation edges (`:resource-edges` — Derivations §Route-owned
  resource activation edges) with the LIVE realized route-owned resource
  owner edges (rf2-k0meap.1; live only).

  STATIC: the sibling already produced `{:from … :to [:resource <id>]
  :role :param :target :parametric}` records; we re-target `:from` to the
  route node's id (the `:rf/route` slice the route materializes) and carry
  the static `:resource` id + `:target` / `:blocking?` through verbatim.

  LIVE ([Derivations.md] §Route-owned resource activation edges — \"The
  realized resource owner edge (`[:route route-id nav-token]` → the
  concrete scoped key) is LIVE state, surfaced by the live view\"): where
  the STATIC graph marks a route's resource target `:parametric` (the
  concrete scoped key requires a live match + scope resolution, so the
  don't-execute rule withholds it), the LIVE graph RESOLVES that into a
  concrete edge once navigation has committed and a scoped resource entry
  is owned by the route. We read each live resource node's lifecycle owner
  set (`:lifecycle :owners`): an owner shaped `[:route route-id nav-token]`
  is a route-owned activation; we connect the matching LIVE route node (the
  `:rf/route` slice whose realized `:owner` equals that owner) to the
  concrete `[:resource <scoped-key>]` node, `:role :param`, carrying the
  realized owner under `:owner`. Live-only: no realized owner edge in
  `:static` mode (a static graph has no realized owners) and none when no
  live route node carries a matching realized owner (an orphaned scope
  owner — e.g. a `:rf.scope/global` activation — is not a route edge)."
  [{:keys [mode nodes]}]
  (let [static (->> nodes
                    (mapcat (fn [[node-id node]]
                              (when (= :rf/route (:id node))
                                (->> (:resource-edges node)
                                     (map (fn [edge]
                                            (-> edge
                                                (assoc :from node-id)
                                                (update :to (fn [to]
                                                              (if (vector? to)
                                                                to
                                                                [:resource to]))))))))))
                    vec)
        live   (if (not= :live mode)
                 []
                 (let [route-owners (into {}
                                          (keep (fn [[node-id node]]
                                                  (when-let [owner (realized-route-owner node)]
                                                    [owner node-id])))
                                          nodes)]
                   (if (empty? route-owners)
                     []
                     (vec
                      (for [[node-id node] nodes
                            :when     (and (vector? node-id) (= :resource (first node-id)))
                            owner     (get-in node [:lifecycle :owners])
                            :when     (and (vector? owner) (= :route (first owner)))
                            :let      [route-node-id (get route-owners owner)]
                            :when     route-node-id]
                        {:from  route-node-id
                         :to    node-id
                         :role  :param
                         :owner owner})))))]
    (into (vec static) live)))

(defn- machine-edges
  "The `:machines` family `:edge-fn`. The `:selector`-role edges from each
  machine `:process` node to the machine-selector subscription nodes that
  read THAT machine (Derivations §Machines: selectors are ordinary
  derivations; the machine draws the `:selector` edge to them). The
  `:selector-targets` extractor (the optional `machine-selector-targets`
  fn on the `:machines` contributor) returns the SET of machine ids one
  selector subscription reads.

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
  [{:keys [node-ids selector-targets]}]
  (let [machine-node-ids (->> node-ids (filter #(and (vector? %) (= :machine (first %)))))
        subs-node-ids    (->> node-ids (filter #(and (vector? %) (= :sub (first %)))))]
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
           {:from m-id :to sub-id :role :selector}))))))

;; ---------------------------------------------------------------------------
;; The per-family CONTRACT (rf2-k0meap.8).
;;
;; Every family-specific decision the central assembler used to branch on
;; lives HERE, beside the family, as DATA the central code reads uniformly:
;;
;;   :node-id-fn   (fn [node] -> node-id)   — lift the projection's node into
;;                                            its canonical family-tagged id.
;;   :static-key-fn (fn [proj-key] -> key)  — OPTIONAL: when a family keys its
;;                                            STATIC projection by something
;;                                            OTHER than the node-id (routes
;;                                            key by the per-route source-form
;;                                            id so per-route resource edges
;;                                            are not collapsed onto the one
;;                                            `:rf/route` slice). Defaults to
;;                                            the node-id.
;;   :flatten-fn   (fn [projection] -> {key node}) — OPTIONAL: when a family's
;;                                            STATIC projection is not a flat
;;                                            `{key node}` map (flows project
;;                                            doubly-keyed `{frame-id {flow-id
;;                                            node}}`). Defaults to identity.
;;   :edge-fn      (fn [ctx] -> [edges…])    — OPTIONAL: the edges this family's
;;                                            nodes contribute BEYOND the common
;;                                            `:input` edges (routes own the
;;                                            `:param` resource-activation edges;
;;                                            machines own the `:selector`
;;                                            edges). `ctx` is the graph-wide
;;                                            `{:mode :nodes :node-ids
;;                                            :selector-targets}`.
;;
;; A contributor MAY carry any of these directly (a synthetic / test family);
;; otherwise the central assembler inherits them from this table by family
;; key. Adding a NEW family = one entry here (or a self-describing
;; contributor) — NO edit to `node-id`, the collectors, or `graph-edges`.
;; ---------------------------------------------------------------------------

(def family-contract
  "The per-family graph contract — `:node-id-fn` (always) plus the optional
  `:static-key-fn` / `:flatten-fn` / `:edge-fn` hooks (see the comment
  above). The central assembler reads a contributor's own hook first and
  falls back to this table by family key, so every family-specific
  mechanic stays beside the family it describes and the assembler is
  family-agnostic (rf2-k0meap.8)."
  {:subs      {:node-id-fn sub-node-id}
   :flows     {:node-id-fn flow-node-id
               ;; flows project doubly-keyed {frame-id {flow-id node}} —
               ;; flatten the frame dimension to a flat {[frame-id flow-id]
               ;; node} map. The COMPOSITE key keeps two frames' same flow-id
               ;; distinct through the flatten (a bare flow-id key would
               ;; collapse them); the collector re-keys by the node's
               ;; frame-scoped `:node-id-fn`, so the composite key is only a
               ;; uniqueness device, not the final node id.
               :flatten-fn (fn [projection]
                             (into {}
                                   (mapcat (fn [[frame-id flow-map]]
                                             (map (fn [[flow-id node]]
                                                    [[frame-id flow-id] node])
                                                  flow-map)))
                                   projection))}
   :resources {:node-id-fn resource-node-id}
   :routes    {:node-id-fn  route-node-id
               ;; many source forms, one fact id (:rf/route) — key each
               ;; STATIC route node by [:rf/route <source-form-id>] so
               ;; per-route resource edges are not collapsed onto the one
               ;; slice. The map's key is the per-route id; the node's :id
               ;; is the shared :rf/route slice name.
               :static-key-fn (fn [route-id] [:rf/route route-id])
               :edge-fn     route-edges}
   :machines  {:node-id-fn machine-node-id
               :edge-fn    machine-edges}})

(defn- contract-for
  "The effective contract for `family` from its `contributor`: the
  contributor's own hooks (a self-describing / synthetic family) layered
  over the per-family `family-contract` defaults. A contributor that omits
  every hook inherits the standard contract by family key; one that
  supplies its own participates with NO central edit (rf2-k0meap.8)."
  [family contributor]
  (merge (get family-contract family)
         (select-keys contributor [:node-id-fn :static-key-fn :flatten-fn :edge-fn])))

(defn node-id
  "The canonical graph node id for one algebra node in `family`
  (Derivations §Graph inspection / §Fact identity). Delegates to the
  family's `:node-id-fn` contract (rf2-k0meap.8) — the central code does
  NOT branch on family. The family-tagged forms:

    :subs      → `[:sub <id>]`  (bare sub-id keyword for a STATIC node;
                 concrete query vector for a LIVE cache-entry node).
    :flows     → `[:flow <frame-id> <flow-id>]` — FRAME-SCOPED; degrades to
                 `[:flow <flow-id>]` for an `:owner`-less fixture.
    :resources → `[:resource <resource-id-or-scoped-key>]`.
    :machines  → `[:machine <machine-or-actor-id>]`.
    :routes    → the route fact id `:rf/route` (every route materializes
                 the ONE slice).

  Provided for tools that need the canonical id of a single projection
  node; the assembler uses the family contract directly."
  [family node]
  (if-let [f (:node-id-fn (get family-contract family))]
    (f node)
    [family (:id node)]))

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
;; A contributor MAY additionally carry its own contract hooks (`:node-id-fn`
;; / `:static-key-fn` / `:flatten-fn` / `:edge-fn`) — see `family-contract`;
;; omitting them inherits the standard per-family contract by family key.
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
   (defn- absent-ns-fnf?
     "Is this `FileNotFoundException` the EXPECTED-absence signal for an
     un-loaded optional family namespace `ns-sym` (vs a real transitive
     load failure of a PRESENT namespace)? `requiring-resolve` of an absent
     namespace throws an FNF whose message names that namespace's munged
     resource path (`re_frame/flows/tooling__init.class, …`). A FNF naming
     a DIFFERENT resource — a dep the present sibling failed to load — is a
     real error, not expected absence. We match on the munged path so only
     the requested namespace's own absence is tolerated (rf2-k0meap.8)."
     [^java.io.FileNotFoundException e ns-sym]
     ;; Clojure munges the namespace to a classpath resource path: dots →
     ;; slashes AND dashes → underscores (re-frame.flows.tooling →
     ;; re_frame/flows/tooling). The FNF message names that munged path, so
     ;; the absence test must munge identically (both substitutions).
     (let [munged (-> (name ns-sym)
                      (.replace \- \_)
                      (.replace \. \/))
           msg    (str (.getMessage e))]
       (boolean
        (or (.contains msg (str munged "__init.class"))
            (.contains msg (str munged ".clj"))
            (.contains msg (str munged ".cljc")))))))

#?(:clj
   (defn- resolve-var
     "JVM-only: resolve ONE fully-qualified `sym` via `requiring-resolve`,
     distinguishing the three genuinely-different outcomes the broad
     `catch Throwable` used to flatten into nil (rf2-k0meap.8):

       - the symbol's NAMESPACE is absent from the classpath — the optional
         artefact is not loaded. `requiring-resolve` throws a
         `FileNotFoundException` naming that namespace; we return `::absent`,
         the EXPECTED-absence signal a core-only / no-flows app produces.
       - the namespace IS present but the VAR is missing — API drift (the
         sibling renamed / dropped a tooling fn). `requiring-resolve`
         returns nil with no throw; that is NOT expected absence, so we
         throw an explicit `ex-info` rather than silently dropping the
         family.
       - the namespace throws DURING LOAD (an init/compile failure, or a
         FileNotFoundException for a TRANSITIVE dep of a present ns) — a real
         error. We do NOT catch it; it propagates.

     Returns the resolved var on success, `::absent` for expected absence."
     [sym]
     (let [ns-sym   (symbol (namespace sym))
           resolved (try
                      (requiring-resolve sym)
                      (catch java.io.FileNotFoundException e
                        (if (absent-ns-fnf? e ns-sym)
                          ::absent                       ;; expected absence
                          (throw e))))]                  ;; real load failure
       (cond
         (= ::absent resolved) ::absent
         (nil? resolved)
         (throw (ex-info (str "re-frame.derivation.graph: optional contributor var "
                              sym " is unresolvable though its namespace loaded — "
                              "API drift, not expected absence (rf2-k0meap.8). "
                              "A genuinely-absent optional family must throw "
                              "FileNotFoundException for its namespace.")
                         {:sym sym :ns ns-sym}))
         :else resolved))))

#?(:clj
   (defn- resolve-sibling
     "JVM-only: resolve one optional sibling's `[static-sym live-sym]`
     tooling fns into a contributor map, or nil when the artefact is absent
     from the classpath (the no-flows / no-resources story). Used by
     `default-contributors` so a core-only JVM process composes only the
     families whose artefact is loaded.

     Narrowed loading (rf2-k0meap.8): a genuinely-ABSENT family (its
     namespace is not on the classpath) returns nil — that is the only
     tolerated absence. A real load/init error or API drift (a present
     namespace missing the expected var) is NOT masked as 'family absent':
     it surfaces (`resolve-var` rethrows / throws ex-info). The broad
     `catch Throwable _ nil` that used to swallow ALL of these is gone."
     [static-sym live-sym live-shape]
     (let [s (resolve-var static-sym)]
       (if (= ::absent s)
         nil                                            ;; expected absence
         (let [l (resolve-var live-sym)]
           (when-not (= ::absent l)                     ;; both present → contribute
             {:static-fn @s :live-fn @l :live-shape live-shape}))))))

(defn default-contributors
  "The default `{family contributor}` map.

  On the JVM, auto-resolves the four optional siblings' tooling fns via
  `requiring-resolve` (returning only the families whose artefact is on
  the classpath) and always includes the in-core `:subs` contributor. The
  `:machines` contributor additionally carries the `machine-selector-targets`
  extractor on `:selector-targets`, so the assembled graph draws PRECISE
  machine→selector edges and refines the selector subscription nodes
  (rf2-4qmiij / rf2-k0meap.2 / rf2-k0meap.8) — the machine selector-target
  surface EP-0014's machine-selector refinement reads.

  On CLJS, returns ONLY the in-core `:subs` contributor — the consuming
  tool (which statically `:require`s the optional siblings it has) supplies
  the rest (including the `:machines` `:selector-targets`) via the
  `contributors` argument to `derivation-graph` / `live-derivation-graph`.
  This keeps the four optional sibling bodies out of a no-feature CLJS
  bundle (a static `:require` from this core ns would defeat the
  bundle-isolation the siblings each guard with their sentinel).

  Optional-loading diagnostics (rf2-k0meap.8): a genuinely-absent optional
  family contributes nothing (the no-flows / no-resources story); but a
  real load/init failure or API drift in a PRESENT sibling now SURFACES
  rather than being silently masked as 'family absent' (see
  `resolve-sibling` / `resolve-var`)."
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
                                       (let [v (resolve-var selector-targets-sym)]
                                         (when-not (= ::absent v) @v))))])))
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
;; Node collection.
;;
;; The collectors are FAMILY-AGNOSTIC (rf2-k0meap.8): they read the family's
;; contract (`:node-id-fn` / `:flatten-fn` / `:static-key-fn`) and never
;; branch on family. Each collected node carries `:rf/family` so a tool can
;; group or filter.
;; ---------------------------------------------------------------------------

(defn- collect-nodes
  "Lift one family's `{key node}` projection into `{node-id node}`, stamping
  `:rf/family`. Family-agnostic: the `node-id-fn` from the family's contract
  computes each canonical id."
  [family node-id-fn projection]
  (reduce-kv
   (fn [acc _k node]
     (assoc acc (node-id-fn node) (assoc node :rf/family family)))
   {}
   projection))

(defn- family-static-nodes
  "Collect one family's STATIC nodes from its contributor, returning a
  `{node-id node}` map. A family with no contributor (artefact absent)
  contributes `{}`. The family's contract drives the shape:

    - `:flatten-fn` flattens a non-flat projection to `{key node}` (flows
      project doubly-keyed `{frame-id {flow-id node}}`); defaults to
      identity.
    - `:static-key-fn` is applied to the (flattened) projection key when a
      family keys its STATIC nodes by something OTHER than the node-id
      (routes key by `[:rf/route <source-form-id>]` so per-route resource
      edges are not collapsed onto the one `:rf/route` slice); defaults to
      the `:node-id-fn`."
  [family contributor]
  (if-not contributor
    {}
    (let [{:keys [node-id-fn flatten-fn static-key-fn]} (contract-for family contributor)
          projection ((:static-fn contributor))
          flat       (if flatten-fn (flatten-fn projection) projection)]
      (reduce-kv
       (fn [acc k node]
         ;; `:static-key-fn` (when a family keys its STATIC nodes by the
         ;; projection KEY — routes by the per-route source-form id) wins;
         ;; otherwise the canonical `:node-id-fn` of the NODE.
         (assoc acc (if static-key-fn (static-key-fn k) (node-id-fn node))
                (assoc node :rf/family family)))
       {}
       flat))))

(defn- family-live-nodes
  "Collect one family's LIVE nodes for `frame-id`, returning `{node-id
  node}`. Honours the contributor's `:live-shape`: `:map` projections
  return `{key node}`; `:node` projections (the route slice) return ONE
  node or nil. A live-fn that returns nil (a missing/destroyed frame or an
  unmaterialized fact — or a production CLJS build where the body DCEs)
  contributes `{}`. Family-agnostic: the family's `:node-id-fn` keys every
  live node (a LIVE route node IS keyed by its node-id — the `:rf/route`
  slice — unlike the static per-route source-form key)."
  [family contributor frame-id]
  (if-not contributor
    {}
    (let [{:keys [node-id-fn]} (contract-for family contributor)
          live-fn (:live-fn contributor)
          result  (live-fn frame-id)]
      (case (:live-shape contributor)
        :node (if (nil? result)
                {}
                {(node-id-fn result) (assoc result :rf/family family)})
        ;; :map — {key node}; nil (prod DCE / missing frame) → {}.
        (collect-nodes family node-id-fn (or result {}))))))

;; ---------------------------------------------------------------------------
;; Edge assembly.
;;
;; The central edge assembler is FAMILY-AGNOSTIC (rf2-k0meap.8): it runs the
;; common `:input` derivation over EVERY node, then folds in each PRESENT
;; family's `:edge-fn` (the edges that family's nodes own beyond `:input`).
;; ---------------------------------------------------------------------------

(defn- graph-edges
  "Every edge across the assembled `nodes` map, in `mode` (`:static` /
  `:live`). Composes the common per-node `:input` edges (every family) with
  each present family's `:edge-fn` extra edges (routes → `:param`
  resource-activation + live realized owner edges; machines → `:selector`
  edges), de-duplicated. The assembler does NOT branch on family — it folds
  the per-family `:edge-fn`s from `contributors` through one
  graph-wide context (rf2-k0meap.8). `selector-targets` is threaded into
  the context for the machine `:edge-fn`."
  [mode nodes contributors selector-targets]
  (let [node-ids (keys nodes)
        ctx      {:mode mode :nodes nodes :node-ids node-ids
                  :selector-targets selector-targets}
        ;; the ONE edge derivation common to every family.
        input    (reduce-kv
                  (fn [acc node-id node]
                    (into acc (input-edges mode node-id node)))
                  []
                  nodes)
        ;; each PRESENT family's extra edges, from its contract :edge-fn.
        extra    (mapcat
                  (fn [family]
                    (when-let [edge-fn (:edge-fn (contract-for family (get contributors family)))]
                      (edge-fn ctx)))
                  (contributor-families contributors))]
    (->> (concat input extra)
         distinct
         vec)))

;; ---------------------------------------------------------------------------
;; The DerivationGraph assembly.
;; ---------------------------------------------------------------------------

(defn- selector-target-ids
  "The SET of `[:sub …]` node ids that are machine selectors (the `:to`
  end of every `:selector` edge). Drives the `:machine-selector`
  refinement enrichment (rf2-k0meap.2)."
  [selector-edges]
  (into #{} (map :to) selector-edges))

(defn- mark-machine-selectors
  "Stamp `:refinement :machine-selector` on every subscription node that is
  the `:to` end of a `:selector` edge (rf2-k0meap.2; [Derivations.md]
  §Machines: \"A graph tool labels such a subscription node with the
  `:machine-selector` refinement\"; EP-0014 §Examples — the selector
  algebra carries that refinement).

  A machine selector REMAINS an ordinary `:derivation` subscription (EP-0014
  issue-4: machines are not a second subscription system) — its `:kind`
  superkind is untouched. The `:refinement` axis is COLOUR, not contract: it
  lets a tool render the selector distinctly and connect it to its machine
  process. We derive the selector set from the SAME `:selector` edges the
  composer already emits (the precise machine-selector-targets extractor),
  so a node is refined `:machine-selector` iff a machine actually draws a
  `:selector` edge to it — never the cross product."
  [nodes selector-edges]
  (let [selector-ids (selector-target-ids selector-edges)]
    (if (empty? selector-ids)
      nodes
      (reduce
       (fn [acc node-id]
         (update acc node-id assoc :refinement :machine-selector))
       nodes
       selector-ids))))

(defn- assemble
  "Assemble a `DerivationGraph` from a `{node-id node}` map + a `:mode`.
  Computes the edge set (folding each present family's `:edge-fn`; the
  optional machine-selector target extractor threads through
  `selector-targets`), then enriches selector subscription nodes with the
  `:machine-selector` refinement (rf2-k0meap.2)."
  [mode nodes contributors selector-targets extra]
  (let [edges          (graph-edges mode nodes contributors selector-targets)
        selector-edges (filter #(= :selector (:role %)) edges)
        nodes          (mark-machine-selectors nodes selector-edges)]
    (merge
     {:mode  mode
      :nodes nodes
      :edges edges}
     extra)))

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
               machine `:process` to its selector subscriptions (whose sub
               nodes are enriched with `:refinement :machine-selector`,
               rf2-k0meap.2). A `:parametric` input set contributes NO
               static edge (the don't-execute rule).

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
                (contributor-families contributors))
         selector-targets (get-in contributors [:machines :selector-targets])]
     (assemble :static nodes contributors selector-targets nil))))

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
               static graph could not enumerate for a parametric sub); the
               REALIZED route-owned resource edges (a live route node's
               `[:route route-id nav-token]` owner → the concrete
               `[:resource <scoped-key>]` node it keeps alive, `:role
               :param`, carrying the realized `:owner` — the live resolution
               of the static graph's `:parametric` route-resource marker,
               rf2-k0meap.1); plus the same `:selector` edges. Selector
               subscription nodes are enriched with `:refinement
               :machine-selector` (rf2-k0meap.2).

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
                (contributor-families contributors))
         selector-targets (get-in contributors [:machines :selector-targets])]
     (assemble :live nodes contributors selector-targets {:frame frame-id}))))

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
