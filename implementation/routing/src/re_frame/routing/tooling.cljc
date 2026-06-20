(ns re-frame.routing.tooling
  "Routing tooling sibling of `re-frame.routing` — carries the EP-0014
  slice-5 derivation/process algebra view of registered routes
  (`route-algebra-view`, the static graph) and of a frame's live route
  slice (`route-slice-algebra-view`, the live graph) that pair tools (Xray,
  re-frame2-pair-mcp) and the conformance fixtures query, but no production
  application code reads.

  Mirrors the rf2-s8w3nw `re-frame.flows.tooling` split (and the rf2-bmzq0
  `re-frame.subs.tooling` / rf2-qwm0a `re-frame.trace.tooling` splits before
  it):
    - CLJS consumers needing the surface call
      `re-frame.routing.tooling/<name>` directly. Apps that load the
      routing artefact but never attach a tool DCE the body wholesale (the
      sibling ns is loaded only when a test fixture, tool, or dev preload
      requires it; the `re-frame.routing` facade never `:require`s it).
    - JVM consumers keep an ergonomic `re-frame.routing/<name>` alias (gated
      under `#?(:clj ...)`). The JVM has no bundle to protect; the alias
      costs nothing. The whole routing artefact is already bundle-isolated
      from production builds (the counter example never `:require`s
      `re-frame.routing`), so this sibling can never reach a no-routing
      app's bundle; the explicit sentinel at the foot of this ns
      additionally proves no stray `:require` ever pulled the body in.

  READ-ONLY over the registry. Like slice-2's subs.tooling read the sub
  registrar and slice-3's flows.tooling read the flow registry, this ns
  touches NEITHER the routing registrar write-path (`reg-route` /
  `clear-route`) NOR the route registration signatures. It reads the
  `:route` registrar kind (the same source `re-frame.routing.registry/route-table`
  iterates) and a frame's live runtime-db route slice through the existing
  `re-frame.frame/frame-runtime-db-value` read seam.

  Per [Derivations.md](../../../../../../spec/Derivations.md) (graduated
  from EP-0014; §Routes expose algebra views) and the projected Malli
  shapes in [Spec-Schemas §`:rf/derivation-node`](../../../../../../spec/Spec-Schemas.md)."
  (:require [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.derivation.node :as node]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- algebra views (EP-0014 slice-5) -------------------------------------
;;
;; Per [Derivations.md] §Routes expose algebra views — a route is the
;; canonical `:runtime-db` / `:on-route` / `:frame` PROCESS-LIKE member of
;; the derivation/process algebra. A route transition materializes the route
;; slice (match facts, params, query, transition state), invokes
;; loaders/resources, and suppresses stale continuations by navigation token
;; (Derivations §Process). The route FACT it materializes is named `:rf/route`
;; — the one consumer-facing name Spec 012 already gives the route slice (one
;; name per fact, per EP-0007).
;;
;; This is the *registrar-derived* slice (Derivations §The EP-0013 relocation
;; seam): the algebra view is assembled from the route registrar metadata at
;; the surface that registered each route, NOT (yet) from an EP-0013 app
;; value. It feeds the later internal graph-inspection helper (EP-0014
;; bead-plan item 7); slice-5 ships NO public accessor — these functions live
;; in the bundle-isolated tooling sibling, consumed by Xray + the conformance
;; fixtures (the two named first consumers). No `re-frame.core` facade export
;; (EP-0014 issue-1 disposition).
;;
;; The fixed classifications for EVERY route fact node (Derivations §Routes
;; expose algebra views):
;;   :kind       :process      (the SUPERKIND — a route transition is
;;                              process-like: it materializes a slice, invokes
;;                              loaders, and suppresses stale continuations;
;;                              Spec-Schemas DerivationKind is the closed
;;                              [:derivation :process] enum)
;;   :refinement :route-fact   (the INFORMATIVE refinement — Derivations §Two
;;                              superkinds, refinable: a tool that understands
;;                              only the two superkinds classifies the node by
;;                              :kind; :refinement carries the route-fact detail)
;;   :output     [:runtime [:rf.runtime/routing :current]]
;;   :storage    :runtime-db   (the route slice is framework-owned durable state)
;;   :evaluation :on-route     (materialized on route activation / egress /
;;                              match change)
;;   :lifecycle  :frame        (the frame owns the slice; destroy-frame! releases it)
;; The per-route axis that varies is the route's source-form id and its
;; route-owned resource activation edges.
;;
;; A route consumes the slice-1 vocabulary verbatim — it does NOT redefine the
;; `:rf/storage-class` / `:rf/evaluation-policy` / `:rf/lifecycle` enums or
;; the `:rf/derivation-node` shape (those are owned by Spec-Schemas /
;; Derivations).

(def ^:private routing-runtime-key
  "The runtime-db subtree key the route slice lives under
  (`:rf.runtime/routing`). The live route fact is the `:current` map beneath
  it — `[:rf.runtime/routing :current]` per Spec 012 §The route slice and
  Spec-Schemas §`:rf/runtime-db`. Held as a const so the projected output
  address and the live read path stay in lockstep."
  :rf.runtime/routing)

(def ^:private route-output
  "The output fact address EVERY route fact node materializes into — the
  route slice at `[:rf.runtime/routing :current]` in the runtime-db
  partition. The route fact's canonical name is `:rf/route` (the slice's one
  consumer-facing name, per EP-0007); the output ADDRESS is the durable
  runtime-db path the transition installs the slice value at."
  [:runtime [routing-runtime-key :current]])

;; The route transition is driven by the routing nav/url-change causal events
;; — these are the route fact's declared `:on-route` inputs. Per EP-0014
;; §Route Fact Input and Derivations §Evaluation policy rule 4 (an `:on-route`
;; process MUST declare the route fact / nav-token / owner / route lifecycle
;; boundary it depends on). The set is fixed across routes — the route slice
;; is materialized by the SAME framework events regardless of which route
;; matched — so it is the spine, not a per-route axis.
(def ^:private route-transition-inputs
  [[:event :rf.route/navigate]
   [:event :rf.route/transitioned]
   [:event :rf.route/handle-url-change]])

(defn- resource-activation-edge
  "Lower ONE `:resources` route-metadata entry to a route-owned resource
  activation edge (Derivations §Routes expose algebra views — route-owned
  resource activation edges; EP-0014 §Route Fact Input — the route-owned
  resource edge).

  The edge runs FROM the route fact's params slot
  (`[:runtime [:rf.runtime/routing :current :params]]`) TO the resource the
  route activates, with `:role :param` — the route's matched params flow into
  the resource's canonical params. Per the DON'T-EXECUTE rule (Derivations
  §The don't-execute rule): static inspection NEVER invokes the entry's
  `:params` / `:scope` / `:when` functions, so the resource target is marked
  `:parametric` — the realized scoped key `[cache-scope resource-id
  canonical-params]` (Derivations §Fact identity) only exists once a concrete
  route match + scope resolution has run, which is live state, not a static
  fact. The edge carries the static `:resource` id so a tool can show WHICH
  resource the route owns even while the concrete key stays parametric.

  `:blocking?` is surfaced when declared (it is static route-declaration
  metadata — whether the route transition stays `:loading` until the resource
  settles, Spec 016 §Route integration) so a tool can distinguish a blocking
  loader edge from a background one without executing anything."
  [entry]
  (let [resource-id (:resource entry)]
    (cond-> {:from   [:runtime [routing-runtime-key :current :params]]
             :to     [:resource resource-id]
             :role   :param
             ;; The realized scoped key requires a concrete match + resolved
             ;; scope — not statically enumerable (the don't-execute rule).
             :target :parametric}
      (contains? entry :blocking?) (assoc :blocking? (:blocking? entry)))))

(defn- resource-activation-edges
  "Project a route's `:resources` route-metadata vector into the route-owned
  resource activation edges, in declaration order. Returns `nil` when the
  route declares no `:resources` (the common case — a routing-only app, or a
  route with no loaders), so the node carries the `:resource-edges` key only
  when it actually owns resource activation. Each entry lowers per
  `resource-activation-edge` (the don't-execute rule keeps every edge static).

  `:resources` is a CROSS-FEATURE route-metadata key owned by the Resources
  artefact (Spec 016 §Route integration; `re-frame.resources.route`), accepted
  by routing via the late-bound `:routing/extra-route-keys` extension. A
  routing-only app never sees it; this projection reads it READ-only off the
  registrar exactly as it reads every other route-metadata key."
  [route-meta]
  (when-let [resources (seq (:resources route-meta))]
    (mapv resource-activation-edge resources)))

(defn- with-source-coords
  "Attach the registrar's source-coordinate / doc metadata to a route node
  when present — the `:source` map (Derivations §Errors and diagnostics, via
  the shared `re-frame.derivation.node` leaf) and the `:doc`. Source coords
  are auto-captured by `reg-route` via `re-frame.source-coords/merge-coords`
  (Spec 001 §Source-coordinate capture)."
  [node route-meta]
  (cond-> (node/attach-source node route-meta)
    (contains? route-meta :doc) (assoc :doc (:doc route-meta))))

(defn- node-for
  "Build the derivation/process algebra view of one registered route from its
  registrar metadata (Derivations §The node shape; Spec-Schemas
  §`:rf/derivation-node`). Pure data over the registrar — the same
  registration-known facts the route-table is built from, lowered into the
  route fact node shape.

  The node's `:id` is the route FACT identity `:rf/route` (Derivations §Fact
  identity — a route fact is identified by its slice/projection name, not by
  the per-route registration id), so every registered route projects to the
  same fact id: each `reg-route` is one more source form that materializes the
  ONE route slice. The per-route registration id is recorded under
  `:source-form {:kind :reg-route :id <route-id>}`, and the route's matched
  params flow into its `:resource-edges` (route-owned resource activation).

  The fixed-classification spine — a `:process` (refinement `:route-fact`)
  whose output materializes the route slice into runtime-db, evaluated
  `:on-route`, owned by its `:frame` — plus the route-transition `:inputs`,
  the `:source-form`, the route-owned resource activation `:resource-edges`
  (when declared), and source coords / doc."
  [route-id route-meta]
  (-> (node/node-base :rf/route route-output
                      {:kind          :process
                       :storage       :runtime-db
                       :evaluation    :on-route
                       :lifecycle     :frame
                       :materialized? true})
      (assoc :refinement  :route-fact
             :source-form {:kind :reg-route :id route-id}
             :inputs      route-transition-inputs)
      (cond-> (seq (:resources route-meta))
        (assoc :resource-edges (resource-activation-edges route-meta)))
      (with-source-coords route-meta)))

(defn route-algebra-view
  "Return the STATIC derivation/process algebra view of every registered
  route (EP-0014 slice-5; [Derivations.md], [Spec-Schemas
  §`:rf/derivation-node`]).

  Pure data over the `:route` registrar kind — no app-db, no runtime-db, no
  live route slice. The algebra-view companion to
  `re-frame.routing.registry/route-table`: where the route-table reports the
  rank-sorted `[id meta]` match candidates, this lowers every registered
  route into the normalized `:rf/derivation-node` shape the derivation/process
  algebra names, so a tool can show subscriptions, flows, resources, route
  facts, and machine selectors as ONE family.

  Shape: `{route-id <route-fact-node>}` where each node carries:

  - `:id`          — `:rf/route` (the route FACT identity — every route
                     materializes the one route slice, named by its one
                     consumer-facing name per EP-0007; the per-route
                     registration id is under `:source-form`).
  - `:kind`        — `:process` (the superkind — a route transition is
                     process-like; Spec-Schemas `DerivationKind` is the closed
                     `[:derivation :process]` enum).
  - `:refinement`  — `:route-fact` (the informative refinement — Derivations
                     §Two superkinds, refinable: tools that understand only
                     the two superkinds classify by `:kind`).
  - `:source-form` — `{:kind :reg-route :id <route-id>}`.
  - `:inputs`      — the route-transition causal events
                     (`[:event :rf.route/navigate]` /
                     `[:event :rf.route/transitioned]` /
                     `[:event :rf.route/handle-url-change]`) — the
                     `:on-route` triggers the route fact depends on
                     (Derivations §Evaluation policy rule 4).
  - `:output`      — `[:runtime [:rf.runtime/routing :current]]` — the route
                     slice is materialized into the runtime-db partition.
  - `:storage`     — `:runtime-db` (the route slice is framework-owned durable
                     state).
  - `:evaluation`  — `:on-route` (materialized on route activation / egress /
                     match change).
  - `:lifecycle`   — `:frame` (the frame owns the slice; destroy-frame!
                     releases it).
  - `:materialized?` — `true` (the route slice has a durable runtime-db
                     address).
  - `:resource-edges` — present ONLY when the route declares `:resources`
                     (Spec 016 §Route integration): the route-owned resource
                     activation edges, each
                     `{:from [:runtime [:rf.runtime/routing :current :params]]
                       :to [:resource <id>] :role :param :target :parametric}`
                     (plus `:blocking?` when declared). The concrete scoped
                     key is `:parametric` — it requires a live match + scope
                     resolution (the don't-execute rule; static inspection
                     never runs the entry's `:params` / `:scope` / `:when`
                     functions).
  - `:source` / `:doc` — present when the registration carried them (source
                     coords auto-captured by `reg-route`).

  Zero-arity returns the map for every registered route (`{}` when none). The
  one-arity form returns the single node for `route-id`, or `nil` if it is not
  registered. JVM-runnable — the route registration metadata is
  partition-agnostic.

  Slice-5 ships NO public accessor (EP-0014 issue-1 disposition): this lives
  in the bundle-isolated tooling sibling and is consumed by Xray + the
  conformance fixtures; the public name is deferred until a third consumer
  needs it. There is no `re-frame.core/route-algebra-view` facade export."
  ([]
   (let [routes (registrar/registrations :route)]
     (reduce-kv
       (fn [acc route-id route-meta]
         (assoc acc route-id (node-for route-id route-meta)))
       {}
       routes)))
  ([route-id]
   (when-let [route-meta (registrar/lookup :route route-id)]
     (node-for route-id route-meta))))

(defn route-slice-algebra-view
  "Return the LIVE derivation/process algebra view of a frame's route slice
  (EP-0014 slice-5; [Derivations.md] §Static and live graphs).

  The live counterpart to `route-algebra-view`: where the static view reports
  the route fact node assembled from registration metadata, the live view
  reports the route fact materialized in a frame's runtime-db at
  `[:rf.runtime/routing :current]` — the concrete matched route, its params,
  query, transition state, and nav-token (the live route owner, Derivations
  §Lifecycle and owner).

  Returns a single `:route-fact` node, or `nil` for a missing / destroyed
  frame, or `nil` when no route slice has been materialized yet (no navigation
  has committed). The node carries:

  - `:id`          — `:rf/route` (the live route fact identity).
  - `:kind`        — `:process` (refinement `:route-fact`).
  - `:output` / `:storage` / `:evaluation` / `:lifecycle` / `:materialized?`
                   — the same fixed classifications as the static node.
  - `:route-id`    — the live matched route id (the slice's `:route-id` slot),
                     so a tool can correlate the live fact back to the
                     `:source-form` of the static node it realized.
  - `:params`      — the live matched path params (a value summary; redaction
                     is the graph-inspection helper's job per Derivations
                     §Redaction metadata).
  - `:query`       — the live matched query params.
  - `:transition`  — the live transition state (`:idle` / `:loading` /
                     `:error`) — lifecycle evidence of an in-flight or settled
                     route transition.
  - `:nav-token`   — the live nav-token (the route OWNER identity — Derivations
                     §Lifecycle and owner: `[:route route-id nav-token]`; route
                     exit / supersession releases it).
  - `:owner`       — `[:route <route-id> <nav-token>]` — the live route owner,
                     present when both the matched id and nav-token are known.

  CLJS- AND JVM-runnable: it reads the frame's runtime-db value through the
  existing `re-frame.frame/frame-runtime-db-value` read seam (a single
  container deref — on the JVM the container holds a plain value, so this
  works there too, unlike the sub-cache reaction-deref slice-2 helper).
  Returns `nil` for a missing/destroyed frame or an unmaterialized slice."
  [frame-id]
  (when-let [runtime-db (frame/frame-runtime-db-value frame-id)]
    (when-let [slice (get-in runtime-db [routing-runtime-key :current])]
      (let [route-id  (:route-id slice)
            nav-token (:nav-token slice)]
        (cond-> {:id            :rf/route
                 :kind          :process
                 :refinement    :route-fact
                 :output        route-output
                 :storage       :runtime-db
                 :evaluation    :on-route
                 :lifecycle     :frame
                 :materialized? true
                 :route-id      route-id
                 :params        (:params slice)
                 :query         (:query slice)
                 :transition    (:transition slice)
                 :nav-token     nav-token}
          (and (some? route-id) (some? nav-token))
          (assoc :owner [:route route-id nav-token]))))))

;; ---- bundle-isolation sentinel ------------------------------------------
;;
;; Per rf2-s8w3nw / rf2-bmzq0 / rf2-qwm0a (the flows.tooling + subs.tooling +
;; trace.tooling split pattern): `implementation/scripts/check-bundle-isolation.cjs`
;; greps the counter bundle for this exact string. The string lives ONLY in
;; this file's source body — no other namespace, no docstring, no test fixture
;; references it — so its presence in the production counter bundle proves the
;; tooling sibling's body got pulled in (most likely via a stray `:require`
;; from a production-reachable ns). The whole routing artefact is already
;; bundle-isolated from counter (counter never `:require`s `re-frame.routing`),
;; so this sentinel is the belt-and-braces guard the sibling pattern
;; standardises. The string survives `:advanced` because string literals are
;; not renamed; it sits outside any gate so DCE cannot drop the literal
;; independently of the surrounding ns body.

(defonce ^:private bundle-isolation-sentinel
  "rf.routing.tooling/sentinel:rf2-eiiifu-2026-06-12:do-not-rename")
