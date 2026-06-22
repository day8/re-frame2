(ns re-frame.resources.tooling
  "Resources tooling sibling of `re-frame.resources` — carries the EP-0014
  slice-4 derivation/process algebra view of registered resources
  (`resource-algebra-view`, the STATIC view) and of a frame's live cache
  entries (`resource-cache-algebra-view`, the LIVE view) that pair tools
  (Xray, re-frame2-pair-mcp) and the conformance fixtures query, but no
  production application code reads.

  Mirrors the rf2-s8w3nw `re-frame.flows.tooling` split (slice-3) and the
  rf2-bmzq0 `re-frame.subs.tooling` split (slice-2) before it:
    - CLJS consumers needing the surface call
      `re-frame.resources.tooling/<name>` directly. An app that loads the
      resources artefact but never attaches a tool DCEs the body wholesale
      (the sibling ns is loaded only when a test fixture, tool, or dev
      preload requires it; the `re-frame.resources` facade never
      `:require`s it).
    - JVM consumers keep an ergonomic `re-frame.resources/<name>` alias
      (gated under `#?(:clj ...)`). The JVM has no bundle to protect; the
      alias costs nothing. The whole resources artefact is already
      bundle-isolated from production builds (counter never `:require`s
      `re-frame.resources`), so this sibling can never reach a no-resources
      app's bundle; the explicit sentinel at the foot of this ns
      additionally proves no stray `:require` ever pulled the body in.

  READ-ONLY over the registry + runtime. This ns touches NEITHER the
  resource registrar write-path (`reg-resource` / `clear-resource`,
  `re-frame.resources.registry`) NOR the resource events / mutation
  write-path (`re-frame.resources.events`) — exactly as slice-3's
  flows.tooling read the flow registry without touching its registrar.
  The STATIC view reads the `:resource` registry through the existing
  `resource-meta` / `resource-ids` introspection seams; the LIVE view reads
  the per-frame `:rf.runtime/resources` entries + `:rf.runtime/work-ledger`
  records through the existing `frame/frame-runtime-db-value` read seam (the
  same seam `re-frame.resources/resources` + the SSR drain read).

  Per [Derivations.md](../../../../../../spec/Derivations.md) §Resources
  expose process nodes (graduated from EP-0014) and the projected Malli
  shapes in [Spec-Schemas §`:rf/derivation-node`](../../../../../../spec/Spec-Schemas.md)."
  (:require [re-frame.derivation.node :as node]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.resources.registry :as registry]
            [re-frame.resources.scope-registry :as scope-registry]
            [re-frame.resources.ssr :as ssr]
            [re-frame.resources.state :as state]
            [re-frame.resources.transport :as transport]
            [re-frame.resources.work-ledger :as work-ledger]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- algebra views (EP-0014 slice-4) -------------------------------------
;;
;; Per [Derivations.md] §Resources expose process nodes (graduated from
;; EP-0014) and the projected Malli shapes in [Spec-Schemas
;; §`:rf/derivation-node`]. A resource is the canonical PROCESS member of
;; the derivation/process algebra (Derivations §Process — "Resources and
;; machines are processes"), distinct from the subscription / flow
;; DERIVATIONS: it has remote authority, owners, stale suppression, and
;; host-transient in-flight work over time. A single `reg-resource`
;; declaration lowers to MORE THAN ONE algebra node (Derivations §Source
;; form and algebra view): a `:process` node for the cache entry PLUS the
;; derived read facts (the `:rf.resource/*` selectors) over that entry —
;; the latter are surfaced under `:selectors` on the process node, not
;; minted as separate nodes here (they are ordinary subscriptions, already
;; covered by slice-2's sub view).
;;
;; This is the *registrar-derived* slice (Derivations §The EP-0013
;; relocation seam): the static algebra view is assembled from the
;; resource-spec metadata at the surface that registered it, NOT (yet) from
;; an EP-0013 app value. It feeds the later internal graph-inspection helper
;; (EP-0014 bead-plan item 7); slice-4 ships NO public accessor — these
;; functions live in the bundle-isolated tooling sibling, consumed by Xray +
;; the conformance fixtures (the two named first consumers). No
;; `re-frame.core` facade export (EP-0014 issue-1 disposition).
;;
;; The fixed classifications for EVERY resource process node (Derivations
;; §Resources expose process nodes):
;;   :kind        :process              (the superkind; refined :resource-process)
;;   :storage     :runtime-db           (the LOCAL cache entry lives in runtime-db)
;;   :authority   {:kind :remote …}     (the source of truth is external — the remote axis)
;;   :evaluation  #{:on-route :on-reply :scheduled :manual}  (a multi-trigger process)
;;   :lifecycle   :scoped-resource-key  (a scoped resource key owns the cache entry)
;; The per-resource axes that vary are the declared INPUTS (params + scope),
;; the OUTPUT runtime-db address, the `:authority` transport, and (for a
;; `{:from-db <id>}` scope) the named-resolver enrichment.
;;
;; A resource consumes the slice-1 vocabulary verbatim — it does NOT
;; redefine the `:rf/storage-class` / `:rf/evaluation-policy` / `:rf/lifecycle`
;; enums or the `:rf/derivation-node` shape (those are owned by Spec-Schemas /
;; Derivations).

(def resource-superkind
  "The two-superkind classification a tool that understands only
  `:derivation` / `:process` reads (Derivations §The node shape). A
  resource is a `:process` — a derivation WITH state, lifecycle, and
  commands over time."
  :process)

(def resource-refined-kind
  "The informative refinement (Derivations §The node shape — refined kinds
  are informative, the superkind is canonical). A resource's source-form
  refinement is `:resource-process`."
  :resource-process)

(def resource-storage
  "The LOCAL storage class of a resource process node (Derivations
  §Resources expose process nodes): the cache entry lives in the
  framework-owned `runtime-db` partition. The REMOTE authority is the
  separate `:authority` axis — `:remote` is NOT a storage class (EP-0014
  issue-2 split)."
  :runtime-db)

(def resource-lifecycle
  "The lifecycle class of a resource process node (Derivations §Lifecycle
  and owner): a scoped resource key owns the cache entry; owners, freshness
  policy, and GC release it. The unqualified lifecycle-CATEGORY kind
  `:scoped-resource-key` (sibling to `:frame` / `:route` / `:machine-instance`)
  — distinct from the `:resource/key` durable data field that carries the
  concrete key value (one name per fact, EP-0007)."
  :scoped-resource-key)

(def resource-evaluation
  "The evaluation policy SET of a resource process node (Derivations
  §Evaluation policy — a process with more than one trigger carries the
  set). A resource runs on route activation, manual refresh/ensure, async
  reply, and scheduled stale/GC timers."
  #{:on-route :on-reply :scheduled :manual})

(def ^:private resource-selectors
  "The public `:rf.resource/*` passive read-fact ids that derive over a
  resource's runtime-db cache entry (Derivations §Resources expose process
  nodes — \"the public selectors listed in `:selectors`\"; Spec 016
  §Subscriptions). They are SEPARATE on-demand derivations over the
  entry — reading them does NOT start resource work (Derivations §Evaluation
  policy rule 1). Surfaced under the process node's `:selectors` so a tool
  can see the read facts a resource projects, without minting them as
  separate process nodes here. Matches the `register-subs!` family in
  `re-frame.resources.subs`."
  [:rf.resource/state
   :rf.resource/data
   :rf.resource/status
   :rf.resource/loading?
   :rf.resource/fetching?
   :rf.resource/stale?
   :rf.resource/error
   :rf.resource/refresh-error
   :rf.resource/has-data?
   :rf.resource/previous-data])

(defn- resource-commands
  "The process COMMAND descriptors a resource emits (Derivations §Process —
  \"processes may produce command outputs, but commands are NOT facts\";
  the EP-0014 §Resource declaration example `:commands` slot). A resource
  drives the managed-HTTP transport (the `:transport`, defaulting to
  `:rf.http/managed`) and lowers its completions onto the framework-internal
  reply targets (Spec 016 — `:rf.resource.internal/succeeded` /
  `…/failed`). The command becomes a fact only when the host returns a reply
  event (Derivations §Process)."
  [transport-id]
  [{:effect  transport-id
    :replies {:success :rf.resource.internal/succeeded
              :failure :rf.resource.internal/failed}}])

(defn- authority-for
  "Build the `:authority` remote-axis map for a resource (Derivations
  §Authority — the remote axis). The source of truth is external
  (`:kind :remote`, `:system :server`); `:transport` MIRRORS the registered
  transport (a recomputable PROJECTION of the Spec 016 registration fact —
  never a second authoritative home). `transport-id` is the spec's
  `:transport`, defaulting to the only initial-scope transport
  (`transport/default-transport`, `:rf.http/managed`)."
  [transport-id]
  {:kind      :remote
   :system    :server
   :transport transport-id})

(defn- declared-inputs
  "Lower a resource spec into the algebra view's declared inputs
  (Derivations §Declared input / §Resources expose process nodes). A
  resource's identity is `[cache-scope resource-id canonical-params]`, so
  its static declared inputs are `[:param :rf.params]` (the params, whose
  concrete shape is per-call — the `:params-schema` validates them) and a
  `[:scope <policy>]` input naming the registered scope policy.

  The DON'T-EXECUTE rule (Derivations §The don't-execute rule): static
  inspection NEVER runs a scope/param resolver. A `{:from-db <id>}` named
  resolver scope is reported as the reference shape (`[:scope {:from-db <id>}]`)
  — a genuinely STATIC fact (the resolver id + its declared inputs surface
  via `scope-resolver` enrichment) — while the params stay generic. A fn
  resolver scope (an inline `(route, ctx)` / fn-of-nothing) is reported as
  `[:scope :rf.scope/resolver]` (an opaque marker — the fn is never run);
  an explicit `:rf.scope/global` / `:rf.scope/from-caller` / literal
  data-value scope is reported verbatim."
  [spec]
  (let [scope (:scope spec)]
    [[:param :rf.params]
     [:scope (cond
               (scope-registry/from-db-reference? scope) scope        ;; {:from-db <id>} — static reference
               (fn? scope)                                :rf.scope/resolver
               :else                                      scope)]]))

(defn- scope-resolver-enrichment
  "Named-resolver enrichment (Derivations §Named-resolver enrichment,
  EP-0014 issue-3): when a resource scope is a `{:from-db <resolver-id>}`
  reference rather than an inline function, the static graph reports the
  RESOLVER ID and its DECLARED INPUTS even while the params stay generic —
  because the resolver's inputs are declared, not executed. Read READ-ONLY
  through `scope-registry/scope-resolver-meta` (the resolver inputs as
  `[:db <rf-path>]` descriptors). Returns the `:scope-resolver` map, or nil
  when the scope is not a `{:from-db …}` reference (or the resolver is not
  registered — a registration-time forward reference; the static graph then
  carries the reference id alone, no inputs)."
  [spec]
  (let [scope (:scope spec)]
    (when (scope-registry/from-db-reference? scope)
      (let [scope-id (:from-db scope)
            rmeta    (scope-registry/scope-resolver-meta scope-id)]
        (cond-> {:id scope-id}
          (some? rmeta)
          ;; the resolver's declared inputs are static facts — each stored
          ;; `[:db <rf-path>]` descriptor lowers to a `[:db <rf-path>]`
          ;; declared input (Derivations §Declared input). The names key
          ;; the descriptor map; the value is the `[:db path]` source.
          (assoc :inputs (vec (vals (:inputs rmeta)))
                 :whole-db? (boolean (:whole-db? rmeta))))))))

(defn- with-metadata
  "Attach source coordinates, the `:schema` data fact, the `:doc`, and the
  opaque `:derive` request-fn token to a node when present. `:schema` is the
  resource's `:data-schema` (the decoded-data fact the resource produces);
  `:doc` is user-supplied. The `:source` coords are read READ-ONLY off the
  registrar `slot` — `reg-resource` stamps `:ns` / `:file` / `:line` at the
  slot's top level via `source-coords/merge-coords`, while `:rf/resource`
  holds the spec — so coords ride the slot, not the spec. The `:request`
  fn is the resource's whole-value process driver, surfaced under `:derive`
  as an opaque token (never serialized)."
  [node spec slot]
  (cond-> (node/attach-source node slot)
    (contains? spec :data-schema) (assoc :schema (:data-schema spec))
    (some? (:doc spec))           (assoc :doc (:doc spec))
    (contains? spec :request)     (assoc :derive (:request spec))))

(defn- static-node-for
  "Build the STATIC derivation/process algebra view of one resource from its
  registered spec (Derivations §Resources expose process nodes;
  Spec-Schemas §`:rf/derivation-node`). `resource-id` is the node's
  canonical fact identity when static (Derivations §Fact identity — a
  resource fact is identified by resource id when static, by
  `[cache-scope resource-id canonical-params]` when concrete).

  The fixed-classification spine — a `:process` whose cache entry lands in
  runtime-db, external `:authority`, evaluated on the resource trigger set,
  owned by its scoped resource key (the `:scoped-resource-key` lifecycle) —
  plus the per-resource declared `:inputs`
  (params + scope), the runtime-db `:output` address, the `:selectors`
  read-fact ids, the `:commands` transport descriptors, the optional
  `:scope-resolver` named-resolver enrichment, and source coords / schema /
  doc / opaque `:derive` request token."
  [resource-id spec slot]
  (let [transport-id (or (:transport spec) transport/default-transport)
        resolver     (scope-resolver-enrichment spec)]
    (-> (node/node-base resource-id [:runtime [state/resources-key :entries]]
                        {:kind          resource-superkind
                         :storage       resource-storage
                         :evaluation    resource-evaluation
                         :lifecycle     resource-lifecycle
                         :materialized? true})
        (assoc :refinement  resource-refined-kind
               :source-form {:kind :reg-resource :id resource-id}
               :inputs      (declared-inputs spec)
               :authority   (authority-for transport-id)
               :selectors   resource-selectors
               :commands    (resource-commands transport-id))
        (cond-> (some? resolver) (assoc :scope-resolver resolver))
        (with-metadata spec slot))))

(defn resource-algebra-view
  "Return the STATIC derivation/process algebra view of every registered
  resource (EP-0014 slice-4; [Derivations.md] §Resources expose process
  nodes, [Spec-Schemas §`:rf/derivation-node`]).

  Pure data over the `:resource` registry — read READ-ONLY through the
  existing `resource-meta` / `resource-ids` introspection seams, never
  touching the registrar write-path (`reg-resource` / `clear-resource`) or
  the resource events / mutation write-path. The algebra-view companion to
  `resources`: where `resources` returns `{:resource-ids […] :entries {…}}`,
  this lowers every registered resource into the normalized
  `:rf/derivation-node` PROCESS shape the derivation/process algebra names,
  so a tool can show subscriptions, flows, resources, route facts, and
  machine selectors as ONE family.

  A resource is the canonical PROCESS member of the algebra (never a
  derivation — Derivations §Process). Each node carries:

  - `:id`          — the resource id (its canonical fact identity when static).
  - `:kind`        — `:process` (the superkind; Derivations §Two superkinds).
  - `:refinement`  — `:resource-process` (the informative refinement;
                     Derivations §The node shape).
  - `:source-form` — `{:kind :reg-resource :id <resource-id>}`.
  - `:inputs`      — the declared inputs (Derivations §Declared input): the
                     params (`[:param :rf.params]`) and the scope policy
                     (`[:scope <policy>]` — a `{:from-db <id>}` reference
                     verbatim, `:rf.scope/resolver` for an inline fn (never
                     run — the don't-execute rule), or an explicit / literal
                     scope verbatim).
  - `:scope-resolver` — present only for a `{:from-db <id>}` scope: the
                     resolver id + its declared `[:db <rf-path>]` inputs
                     (the named-resolver enrichment — static facts because
                     the inputs are declared, not executed).
  - `:output`      — `[:runtime [:rf.runtime/resources :entries]]` — the
                     resource MATERIALIZES its cache entries into the
                     runtime-db partition.
  - `:storage`     — `:runtime-db` (the LOCAL representation home).
  - `:authority`   — `{:kind :remote :system :server :transport <id>}` — the
                     REMOTE axis: the source of truth is external; `:transport`
                     mirrors the registered transport.
  - `:evaluation`  — `#{:on-route :on-reply :scheduled :manual}` (a
                     multi-trigger process).
  - `:lifecycle`   — `:scoped-resource-key`.
  - `:selectors`   — the `:rf.resource/*` read-fact ids that derive over the
                     entry (separate on-demand derivations; reading one does
                     not start work).
  - `:commands`    — the transport command descriptors + their reply targets
                     (commands are NOT facts — Derivations §Process).
  - `:materialized?` — `true` (the cache entry has a durable runtime-db address).
  - `:derive`      — the opaque `:request` body-fn token (never serialized).
  - `:source` / `:schema` / `:doc` — present when the registration carried
                     them (`:schema` is the `:data-schema`; source coords
                     auto-captured by `reg-resource` via
                     `source-coords/merge-coords`).

  Zero-arity returns `{resource-id <node>}` for every registered resource
  (`{}` when none). The one-arity form returns the single node for
  `resource-id`, or `nil` if it is not registered. JVM-runnable — the
  resource registry is partition-agnostic registration metadata.

  Slice-4 ships NO public accessor (EP-0014 issue-1 disposition): this lives
  in the bundle-isolated tooling sibling and is consumed by Xray + the
  conformance fixtures; the public name is deferred until a third consumer
  needs it. There is no `re-frame.core/resource-algebra-view` facade export."
  ([]
   (reduce
     (fn [acc resource-id]
       (let [slot (registrar/lookup registry/resource-kind resource-id)
             spec (:rf/resource slot)]
         (cond-> acc
           (some? spec) (assoc resource-id (static-node-for resource-id spec slot)))))
     {}
     (registry/resource-ids)))
  ([resource-id]
   (let [slot (registrar/lookup registry/resource-kind resource-id)
         spec (:rf/resource slot)]
     (when (some? spec)
       (static-node-for resource-id spec slot)))))

;; ---- live view (Derivations §Static and live graphs) ---------------------
;;
;; The LIVE counterpart: where the static view reports the resource id and a
;; generic params input, the live view reports one PROCESS node per concrete
;; cache entry, keyed by its scoped resource key
;; `[cache-scope resource-id canonical-params]` — the resource's canonical
;; fact identity when concrete (Derivations §Fact identity). The concrete
;; scope + params are realized edges (Derivations §Static and live graphs);
;; the live owners, status, in-flight work-ledger links, and host-transient
;; in-flight handle address are the live lifecycle state.

;; ---- EP-0015 tool-egress projection (rf2-0t0l3w) -------------------------
;;
;; `resource-cache-algebra-view` is a TOOL-facing egress boundary (Xray,
;; re-frame2-pair-mcp, conformance fixtures). EP-0015 treats resource entries,
;; work-ledger rows, scopes, params, and owner/cause summaries as EGRESS
;; RECORDS, and requires projected payloads at tool boundaries (Spec 015 §10;
;; Derivations §Resources expose process nodes — the live-resource identity
;; case where scope/params are embedded in the node KEY / `:id` / `:output` /
;; `:inputs` / `:work-ledger`, which a generic value-path walk cannot reach).
;;
;; The projection reuses the SAME resource OWNER classification the SSR /
;; durable-egress path uses — never a tooling-private elider — through the
;; shared `ssr/disposition+project-key` pipeline (rf2-366u0g):
;;   - `classification/whole-entry-disposition` (inside the shared helper)
;;     resolves the coarse `:sensitive?` / `:large?` root-prop claim. EP-0025
;;     (rf2-71dr8t) removed the named-scope-resolver derived-sensitivity
;;     inheritance arm (no sensitivity propagation);
;;   - `ssr/project-scoped-key` (inside the shared helper) then projects the
;;     scoped key per that disposition — `:redact` / `:omit` replace scope +
;;     params with opaque content-addressed `{:rf/redacted <digest>}` tokens
;;     (distinct values stay distinct, so graph connectivity by projected key
;;     is preserved), while `:serialize` applies the resource's per-slot
;;     `:params` projection-relative declarations and otherwise rides verbatim
;;     (non-sensitive identity is preserved).
;; The resource-id (position 1 of the 3-tuple) always survives, so a tool still
;; sees WHICH resource each node names and edges still join nodes by the same
;; projected key.

(defn- project-key-for-egress
  "Project a `scoped-key` for TOOL egress against the resource owner spec +
  the `frame-id` classification (rf2-0t0l3w). Returns `[projected-key
  disposition]`. `:redact` / `:omit` replace scope + params with opaque
  tokens; `:serialize` projects per-slot `:params-schema` marks (a no-op when
  none) — the resource-id always survives. Pure. Delegates to the shared
  `ssr/disposition+project-key` pipeline (rf2-366u0g — the SAME owner
  classification + key projection the SSR durable-egress + off-box trace-egress
  paths use), dropping the spec it does not need."
  [scoped-key frame-id]
  (let [[projected-key disposition _spec] (ssr/disposition+project-key scoped-key frame-id)]
    [projected-key disposition]))

(defn- project-work-id
  "Project the scoped key embedded in a resource work-id
  `[:rf.work/resource <scoped-key> <generation>]` for tool egress
  (rf2-0t0l3w). A work-id of another shape rides unchanged. Pure."
  [work-id frame-id]
  (if (and (vector? work-id) (= :rf.work/resource (first work-id)))
    (assoc work-id 1 (first (project-key-for-egress (nth work-id 1) frame-id)))
    work-id))

(defn- live-work-ledger-link
  "Build the in-flight `:work-ledger` + `:host-transient` slots for a live node,
  PROJECTING the work-id's embedded scoped key + the record's `:resource/key`
  for tool egress (rf2-0t0l3w) so neither leaks the raw scope/params. Returns
  the node `acc` with both slots assoc'd."
  [acc runtime-db work-id frame-id]
  (let [record  (work-ledger/get-record runtime-db work-id)
        wid'    (project-work-id work-id frame-id)
        summary (select-keys record
                             [:work/id :work/kind :status
                              :resource/key :generation
                              :transport :owners :causes])
        rec'    (cond-> (assoc summary :work/id wid')
                  (contains? summary :resource/key)
                  (update :resource/key
                          #(first (project-key-for-egress % frame-id))))]
    (assoc acc
           :work-ledger    {:work/id wid' :record rec'}
           :host-transient [[:rf.http/in-flight wid']])))

(defn- live-node-for
  "Build the LIVE process node for one concrete cache entry. `scoped-key` is
  `[cache-scope resource-id canonical-params]` — the entry's canonical live
  fact identity. The static fixed classifications hold; the live axes are the
  realized `:inputs` (concrete `[:scope …]` + `[:param …]`), the concrete
  `:output` runtime-db entry address, the live `:lifecycle` map (the
  `:scoped-resource-key` kind + the entry's `:active-owners`), the entry `:status`,
  and — when an attempt is in flight — the work-ledger record summary +
  the host-transient in-flight handle address (Derivations §Output —
  `:host-transient` in-flight work).

  rf2-0t0l3w: the scoped key is PROJECTED for tool egress (`project-key-for-egress`)
  before it rides ANY identity position — the `:id`, the realized `[:scope …]`
  / `[:param …]` inputs, the `:output` runtime-path tail, the in-flight
  work-ledger record's `:resource/key`, and the host-transient handle address.
  A `:sensitive?` / `:large?` resource therefore never egresses its raw
  scope/params here (the value-path walk cannot reach them); a plain resource
  rides verbatim."
  [runtime-db scoped-key frame-id entry static-node]
  (let [[proj-key _disp]           (project-key-for-egress scoped-key frame-id)
        [proj-scope _ proj-params] proj-key
        resource-id (second scoped-key)
        work-id  (:current-work entry)]
    (cond-> {:id            proj-key
             :kind          resource-superkind
             :refinement    resource-refined-kind
             :source-form   {:kind :reg-resource :id resource-id}
             :inputs        [[:scope proj-scope] [:param proj-params]]
             :output        [:runtime (state/entry-path proj-key)]
             :storage       resource-storage
             :authority     (:authority static-node)
             :evaluation    resource-evaluation
             :lifecycle     {:kind   resource-lifecycle
                             :owners (or (:active-owners entry) #{})}
             :selectors     resource-selectors
             :materialized? true
             :status        (:status entry)}
      ;; an in-flight attempt links the durable work-ledger record (a small
      ;; serializable summary — the in-flight identity, owners, causes,
      ;; transport, status) AND names the host-transient in-flight handle
      ;; address (the abortable handle lives OUTSIDE durable frame-state, in
      ;; the `[frame-id work-id]` side table — Derivations §Output). rf2-0t0l3w:
      ;; both are PROJECTED for tool egress (the record's `:resource/key` + the
      ;; work-id's embedded scoped key).
      (some? work-id)
      (live-work-ledger-link runtime-db work-id frame-id)
      ;; carry the static node's source coords / schema / doc / derive token
      ;; through to the live node (the same resource registration backs both).
      (contains? static-node :source) (assoc :source (:source static-node))
      (contains? static-node :schema) (assoc :schema (:schema static-node))
      (contains? static-node :doc)    (assoc :doc (:doc static-node))
      (contains? static-node :derive) (assoc :derive (:derive static-node)))))

(defn resource-cache-algebra-view
  "Return the LIVE derivation/process algebra view of a frame's resource
  cache — one `:rf/derivation-node` per concrete cache entry (EP-0014
  slice-4; [Derivations.md] §Static and live graphs).

  The live counterpart to `resource-algebra-view`: where the static view
  reports the resource id and a generic params input, the live view reports
  the REALIZED `[:scope …]` + `[:param …]` edges for each concrete entry,
  keyed by the entry's scoped resource key
  `[cache-scope resource-id canonical-params]` — its canonical fact identity
  when concrete (Derivations §Fact identity). Pure data over the frame's
  `:rf.runtime/resources` `:entries` + `:rf.runtime/work-ledger` records,
  read READ-ONLY through `frame/frame-runtime-db-value` (the same read seam
  `re-frame.resources/resources` + the SSR drain use) — never touching the
  runtime write-path.

  Per-entry node (the static fixed classifications hold — `:kind :process`,
  `:storage :runtime-db`, external `:authority`, the resource trigger set,
  `:lifecycle :scoped-resource-key`), plus the live axes:

  - `:id`          — the concrete scoped resource key (the live fact identity).
  - `:inputs`      — the realized `[[:scope <scope>] [:param <params>]]` edges
                     for THIS entry.
  - `:output`      — `[:runtime [:rf.runtime/resources :entries <scoped-key>]]`
                     (the concrete entry address).
  - `:lifecycle`   — `{:kind :scoped-resource-key :owners <active-owners>}` (the live
                     owner set keeping the entry alive — Derivations §Lifecycle
                     and owner).
  - `:status`      — the entry's lifecycle FSM status (`:idle` / `:loading` /
                     `:fetching` / `:loaded` / `:error`).
  - `:work-ledger` — present when an attempt is in flight: the entry's
                     `:current-work` id + a small serializable summary of the
                     linked work-ledger record (identity, owners, causes,
                     transport, status — the work-ledger link).
  - `:host-transient` — present when an attempt is in flight: the in-flight
                     handle address `[[:rf.http/in-flight <work-id>]]` (the
                     abortable handle lives OUTSIDE durable frame-state).
  - `:selectors` / `:authority` / `:source` / `:schema` / `:doc` / `:derive`
                     — carried through from the resource's static node.

  Returns `{<key-id> <node>}` for every live entry in the frame's cache —
  keyed on the CEDN-1 byte `key-id` STRING (the same key the runtime storage
  / SSR wire / indexes use), with each node carrying its kind-preserving
  scoped-resource-key VECTOR under `:id`. `{}` when the frame has no entries,
  an unknown / destroyed frame, or no resource cache subtree. JVM-runnable —
  the cache entries + work records are serializable EDN runtime-db facts (no
  host handles ride them; those live in the side table)."
  [frame-id]
  (let [runtime-db (frame/frame-runtime-db-value frame-id)
        entries    (get-in runtime-db (state/entries-path))]
    (reduce-kv
      ;; rf2-9e0tyq / rf2-ka2nkx — `:entries` is keyed on the opaque byte
      ;; `key-id`; the live node's `:id` / inputs use the entry's own
      ;; `:resource/key` VECTOR (the canonical fact identity), read from the
      ;; entry, not the map key. The RETURNED map MUST stay keyed on the byte
      ;; `key-id` too: rekeying onto the `=`-colliding scoped-key vector would
      ;; `assoc` one CEDN-distinct entry over the other (a list-params and a
      ;; vector-params entry are Clojure-= as vectors), reporting ONE node for
      ;; TWO live entries (rf2-ka2nkx).
      (fn [acc _k-id entry]
        (let [scoped-key  (:resource/key entry)
              resource-id (second scoped-key)
              static-node (resource-algebra-view resource-id)
              node        (live-node-for runtime-db scoped-key frame-id
                                         entry static-node)]
          ;; rf2-0t0l3w: re-key the returned map on the PROJECTED key's byte
          ;; `key-id` (the node's `:id` is the projected scoped key) so the map
          ;; key, the `:id`, and every other identity position agree and carry
          ;; no raw secret. A non-sensitive resource projects to the SAME key,
          ;; so its byte key-id is unchanged; a `:redact` / `:omit` resource's
          ;; opaque content-addressed tokens stay distinct per distinct value,
          ;; so two CEDN-distinct entries never collapse onto one map key.
          (assoc acc (state/key-id (:id node)) node)))
      {}
      (or entries {}))))

;; ---- bundle-isolation sentinel ------------------------------------------
;;
;; Per rf2-s8w3nw / rf2-bmzq0 / rf2-qwm0a (the flows.tooling + subs.tooling +
;; trace.tooling split pattern): `implementation/scripts/check-bundle-isolation.cjs`
;; greps the counter bundle for this exact string. The string lives ONLY in
;; this file's source body — no other namespace, no docstring, no test
;; fixture references it — so its presence in the production counter bundle
;; proves the tooling sibling's body got pulled in (most likely via a stray
;; `:require` from a production-reachable ns). The whole resources artefact is
;; already bundle-isolated from counter (counter never `:require`s
;; `re-frame.resources`), so this sentinel is the belt-and-braces guard the
;; sibling pattern standardises. The string survives `:advanced` because
;; string literals are not renamed; it sits outside any gate so DCE cannot
;; drop the literal independently of the surrounding ns body.

(defonce ^:private bundle-isolation-sentinel
  "rf.resources.tooling/sentinel:rf2-gn9juw-2026-06-12:do-not-rename")
