(ns re-frame.resources.route
  "LATE-BOUND routing integration for resources. Per Spec 016 §Route
  integration.

  Two cross-feature seams, both LATE-BOUND in BOTH directions (resources
  never statically `:require`s routing; routing never statically
  `:require`s resources):

  1. **Accepted-key extension** — routing rejects unknown bare
     route-metadata keys at registration (Spec 012 §Reserved
     route-metadata keys). Resources publishes the
     `:routing/extra-route-keys` hook (returning `#{:resources}`);
     routing's `accepted-route-keys` unions it in, so `:resources` is
     accepted exactly like the existing cross-feature `:head` key (owned
     by SSR). An app that loads resources but not routing carries no
     route code; a routing-only app sees no resources behaviour; a route
     containing `:resources` in an app that omits the Resources artefact
     is correctly rejected by routing.

  2. **On-route-entry plan** — resources publishes the
     `:routing/on-route-entry` hook; routing's `commit-navigation` (the
     single shared successful-commit assembler for both the programmatic
     and URL-driven nav paths) consults it and splices the returned fx
     into the commit. The plan:

       - resolves each `:resources` entry's scope + canonical params,
         evaluating `:when` (a not-sentinel-nil-params guard) and
         ordering by `:after` route-local dependencies;
       - marks each resource active with owner `[:route route-id
         nav-token]` and ensures it with cause `[:route-entry route-id
         nav-token]`;
       - classifies blocking vs background — a blocking requirement that
         does NOT already have usable data AT COMMIT records its scoped
         key under `[:rf.runtime/routing :resource-blocking nav-token]`
         (keyed by its CEDN-1 byte `key-id`),
         so the route transition stays `:loading` (and SSR has a wait
         point) until it resolves, while non-blocking ones fetch in the
         background and an already-fresh blocking one records nothing (the
         route commits `:idle`, with no transient `:loading`);
       - releases the PREVIOUS route's owner token (route leave /
         supersession) so its resources become GC-eligible when no other
         owner needs them;
       - surfaces a params-schema failure as a route/resource PLANNING
         error (`:rf.error/resource-route-plan` in the route slice +
         Xray), never a silent cache miss.

  Readiness after the commit is reconciled by the ONE projector below
  (`reconcile-readiness`): every path that changes a blocking
  requirement's facts — a resource settle, a retained-owner adoption, an
  `ensure` fresh-skip, SSR hydration, epoch restore — re-projects
  `:transition` / `:error` from those facts rather than deciding for
  itself what the change meant. Stale navigations are suppressed by
  nav-token: only the live token's slot is projected, and a superseded
  token's slot is dropped wholesale on owner release.

  Per Spec 016 §Route integration."
  (:require [re-frame.error :as error]
            [re-frame.late-bind :as late-bind]
            [re-frame.resources.registry :as registry]
            [re-frame.resources.scope-registry :as scope-registry]
            [re-frame.resources.state :as state]
            [re-frame.resources.work-ledger :as work-ledger]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

(def resources-route-key
  "The route-metadata key the Resources artefact owns (`:resources`). Per
  Spec 016 §Route integration."
  :resources)

(def extra-route-keys
  "The cross-feature route-metadata keys this artefact contributes to the
  routing accepted set (`#{:resources}`). Published under
  `:routing/extra-route-keys`; routing's `accepted-route-keys` unions it
  in. Per Spec 016 §Route integration."
  #{resources-route-key})

;; ---- routing-runtime blocking slot ----------------------------------------
;;
;; Blocking route resources are tracked under their nav-token in the
;; routing-runtime subtree (a sibling of `:current` / `:pending-navigation`
;; under `[:rf.runtime/routing …]`) as a `{<key-id> <scoped-key>}` map — the
;; same byte-exact carrier shape `:entries` uses (rf2-btdl1), so:
;;   - the slot names the OUTSTANDING blocking requirements for that
;;     activation; `reconcile-readiness` below prunes each as it resolves and
;;     projects `:transition` / `:error` from what remains (EP-0037 R1: route
;;     readiness is the resource-derived projection, not a routing settle
;;     step);
;;   - a newer navigation's nav-token has its OWN slot, so a stale
;;     blocking drain (old nav-token) is structurally a no-op (its slot is
;;     released on leave);
;;   - the slot is per-nav-token EDN (byte `key-id`s to their kind-preserving
;;     scoped keys), not host state, so it rides epoch restore / SSR
;;     coherently with the rest of the routing runtime-db.

(def routing-key
  "The routing-runtime subtree key (`:rf.runtime/routing`). The blocking
  slot is a sibling of `:current` here. Mirrors the literal routing uses;
  duplicated (not imported) so resources never statically `:require`s
  routing."
  :rf.runtime/routing)

(defn blocking-path
  "Runtime-db-relative path to the blocking identity map for a nav-token:
  `[:rf.runtime/routing :resource-blocking <nav-token>]`, holding
  `{<key-id> <scoped-key>}` (rf2-btdl1 — byte-exact membership, no order
  promise). Per Spec 016 §Route integration."
  [nav-token]
  [routing-key :resource-blocking nav-token])

(defn current-path
  "Runtime-db-relative path to the live route slice
  (`[:rf.runtime/routing :current]`). Resources reads/writes `:transition`
  / `:error` here to land/flip the route on a blocking-resource settle."
  []
  [routing-key :current])

(defn blocking-slots-map-path
  "Runtime-db-relative path to the WHOLE per-nav-token blocking-slots map
  (`[:rf.runtime/routing :resource-blocking]`), keyed by nav-token — each
  value is that token's `{<key-id> <scoped-key>}` map. Used to drop a
  superseded token's slot wholesale (rf2-l2gofj)."
  []
  [routing-key :resource-blocking])

(defn clear-blocking-slot
  "Pure: dissoc the ENTIRE blocking slot for a superseded `nav-token`
  (`[:rf.runtime/routing :resource-blocking <nav-token>]`). Returns the
  updated runtime-db (a no-op when no slot exists). Per Spec 016 §Route
  integration (rf2-l2gofj).

  WHY a superseded token's slot needs an explicit clear: `reconcile-readiness`
  projects the LIVE nav-token only, so it never visits a stale slot at all —
  and a reply-driven prune could not be relied on even if it did, because on
  supersession the prior owner is RELEASED from every entry's `:active-owners`
  immediately, and a resource that is aborted / never replies (orphaned
  in-flight) would leave its old-token blocking entry forever. Clearing the
  whole slot deterministically at the route transition (when the prior owner
  is released) is what guarantees old-token blocking state cannot accumulate.
  The CURRENT nav-token's slot is never the target here — only a superseded
  token's."
  [runtime-db nav-token]
  (if (contains? (get-in runtime-db (blocking-slots-map-path)) nav-token)
    (update-in runtime-db (blocking-slots-map-path) dissoc nav-token)
    runtime-db))

;; ---- routing-runtime plan-identity slot (EP-0037 R2) ----------------------
;;
;; The FULL set of scoped resource identities a nav-token's effective
;; parent-to-leaf plan owns (blocking AND non-blocking) is recorded under
;; `[:rf.runtime/routing :resource-plan <nav-token>]`, a sibling of
;; `:resource-blocking` and carried in the same `{<key-id> <scoped-key>}`
;; shape (rf2-btdl1). The NEXT full activation reads the SUPERSEDED
;; nav-token's slot to compute the kept/added/removed plan diff for
;; attach-before-release owner handoff + the partial-revalidation law (Spec
;; 016 §Effective parent-chain resource plans). Like the blocking slot it is
;; per-nav-token EDN in the routing runtime-db, so it rides epoch restore /
;; SSR coherently, and a superseded token's slot is cleared when its route
;; owner is released.

(defn plan-path
  "Runtime-db-relative path to the plan-identity map for a nav-token:
  `[:rf.runtime/routing :resource-plan <nav-token>]`, holding
  `{<key-id> <scoped-key>}`. Per Spec 016 §Effective parent-chain resource
  plans."
  [nav-token]
  [routing-key :resource-plan nav-token])

(defn plan-slots-map-path
  "Runtime-db-relative path to the WHOLE per-nav-token plan-identity map
  (`[:rf.runtime/routing :resource-plan]`), keyed by nav-token."
  []
  [routing-key :resource-plan])

(defn plan-identities
  "The scoped resource identities the plan for `nav-token` owned, read from
  `runtime-db` as `{<key-id> <scoped-key>}` (`{}` when none). The plan-diff's
  PREVIOUS-identity input."
  [runtime-db nav-token]
  (or (get-in runtime-db (plan-path nav-token)) {}))

(defn clear-plan-slot
  "Pure: dissoc the ENTIRE plan-identity slot for a superseded `nav-token`.
  Returns the updated runtime-db (a no-op when no slot exists). Cleared
  deterministically at the route transition when the prior owner is released,
  symmetric with `clear-blocking-slot`."
  [runtime-db nav-token]
  (if (contains? (get-in runtime-db (plan-slots-map-path)) nav-token)
    (update-in runtime-db (plan-slots-map-path) dissoc nav-token)
    runtime-db))

;; ---- the reply-driven readiness projector ---------------------------------
;; ---- (Spec 012 §Route readiness is a resource projection /
;; ----  Spec 016 §Route integration / §SSR wait point) ----------------------
;;
;; Route readiness (`:rf.route/transition` / `:rf.route/error`) is a PURE
;; projection over the active plan's BLOCKING resource requirements, read in
;; Spec 016 resource vocabulary. `reconcile-readiness` is the ONE implementation
;; of that table on the REPLY-DRIVEN side, and every path that can change a
;; blocking requirement's facts AFTER the activation commit projects through it:
;; a retained-owner adoption / handoff, an `ensure` fresh-skip, every resource
;; settle, SSR hydration, and epoch restore. No caller decides for itself what
;; a settle "meant": they write the entry, then hand the runtime-db to
;; `reconcile-readiness`, which RE-READS the facts. That is what keeps a second
;; readiness state machine from existing on this side.
;;
;; The ACTIVATION COMMIT is the one path that does NOT come through here.
;; Routing seeds the slice from the plan's blocking set with its own statement
;; of the same table (`re-frame.routing.readiness/project-at-commit`), because
;; packaging forbids sharing one function: `deps.edn` holds routing as a
;; TEST-ONLY dep of this artefact and the routing integration is late-bound, so
;; this namespace cannot `:require` it. The two agree — error beats loading
;; beats idle in both — and changing the precedence means changing both sites.
;; `re-frame.readiness-projector-conformance-cljs-test` (this artefact's test
;; tree — the only one that can require both namespaces) is what makes that
;; enforceable: it drives every Spec 012 input class through BOTH halves, each
;; in its own vocabulary, and fails on a divergence. It pins agreement; it does
;; NOT imply the two should be unified.
;;
;; Routing owns no part of the reply-driven half: it provides the
;; `:routing/on-route-entry` plan hook, seeds the slice at commit, and
;; (EP-0037 R1) consults no settle event and no blocking predicate.
;;
;; The nav-token's blocking slot is the OUTSTANDING requirement set for that
;; activation — the requirements that still have to resolve. A requirement that
;; resolves is pruned, so a later invalidation / refetch of an already-satisfied
;; requirement never re-blocks a route that has landed. A superseded token's
;; whole slot is dropped by `clear-blocking-slot` when its route owner is
;; released.

(defn requirement-state
  "Classify ONE blocking route-resource requirement from its durable cache
  `entry` (nil when the entry is absent). The four outcomes are the Spec 016
  facts the Spec 012 readiness table is written in:

    `:ready`   — the identity has its OWN usable data (`state/has-data?`), so
                 the requirement is met. `:keep-previous?` PREVIOUS data is a
                 projection POINTER (`:previous-key`) and never this entry's
                 `:data`, so previous data can NOT complete a newly-keyed
                 requirement's first load.
    `:failed`  — a blocking FIRST load failed: no usable data, status `:error`.
                 A BACKGROUND-refresh failure never reaches here — `entry-
                 failed` returns a `:fetching`-with-data entry to `:loaded` and
                 records `:refresh-error`, which reads `:ready`. So a refresh
                 failure stays on the resource's `:refresh-error` channel and
                 does not make the route `:error`.
    `:pending` — work capable of settling the requirement exists: in flight
                 (`:loading` / `:fetching`), or not started yet (an absent
                 entry, or a never-attempted one the plan is about to
                 `ensure`).
    `:inert`   — settled with no usable data and NOTHING left to settle it: a
                 first load that was ABORTED lands `:idle` with its `:attempt`
                 spent. It neither completes nor fails the route — an aborted
                 blocking first load un-blocks rather than manufacturing a
                 spurious route error.

  PURE. Per Spec 012 §Route readiness is a resource projection."
  [entry]
  (cond
    (nil? entry)                            :pending
    (state/has-data? entry)                 :ready
    (= :error (:status entry))              :failed
    (contains? #{:loading :fetching} (:status entry)) :pending
    (zero? (:attempt entry 0))              :pending
    :else                                   :inert))

(defn requirement-met?
  "True iff a requirement in `entry`'s state no longer holds the route — it is
  `:ready` (has its own usable data) or `:inert` (settled with nothing left to
  settle it). Such a requirement is pruned from the nav-token's outstanding
  blocking set. Per Spec 012 §Route readiness is a resource projection.

  This is the POST-COMMIT prune question — 'can this OUTSTANDING requirement
  still hold the route?'. The AT-COMMIT question the planner asks is different
  (`requirement-ready?` below): at commit every non-`:ready` requirement either
  has live work or is about to be `ensure`d, so `:inert` there means 'about to
  be reloaded', not 'nothing left to settle it'."
  [entry]
  (contains? #{:ready :inert} (requirement-state entry)))

(defn requirement-ready?
  "True iff `entry` ALREADY has its own usable data — the ONE reason a blocking
  requirement is not recorded as outstanding AT COMMIT (Spec 012 §Route
  readiness is a resource projection: a blocking requirement that already has
  usable data has nothing to wait for, so the commit projects `:idle` with no
  transient `:loading`).

  Every OTHER at-commit state must be recorded, because the plan guarantees
  work for it: the identity is either adopted with live work in flight
  (`adoptable?`) or handed to `ensure`, which starts a fresh attempt for
  anything that is not a fresh `:loaded` entry. Recording it is what makes the
  route wait for the data it declared blocking."
  [entry]
  (= :ready (requirement-state entry)))

(defn adoptable?
  "True iff a RETAINED plan identity (one the superseded nav-token's plan also
  owned) can be adopted by the next activation WITHOUT a fetch — the plan-diff's
  `kept` classification. `entry` is its durable cache entry (nil when absent);
  `runtime-db` supplies the work ledger.

  Prior-plan MEMBERSHIP alone is NOT enough (rf2-kqxe6.6). `:rf.resource/adopt-
  owner` attaches an owner and issues no request, so adopting an identity that
  cannot produce data commits a blocking slot nothing can ever drain — a
  permanent `:loading`. An identity is genuinely reusable in exactly two cases:

    - it has its OWN usable data (`:ready`) — the partial-revalidation law
      applies and navigation must not revalidate it; or
    - GENUINELY LIVE work will settle it (`work-ledger/live-work?`) — adopting
      joins that work, and attach-before-release keeps it from being aborted.

  Everything else takes the ordinary ensure/readiness path: an ABSENT entry
  (cleared / removed / GC'd / a hydration-like mismatch — adopting it is a
  literal no-op), a never-attempted one, a `:failed` first load, an `:inert`
  one (settled with no data and nothing left to settle it), and one whose
  `:current-work` POINTER names dead or doomed work.

  Derived from `requirement-state` — this adds NO second readiness table. The
  one split it makes is INSIDE `:pending`, which readiness deliberately
  conflates (\"in flight\" and \"not started yet\" both mean the route waits);
  adoption is the one caller for which they differ. PURE. Per Spec 016 §Plan
  diff and owner handoff."
  [runtime-db entry]
  (case (requirement-state entry)
    :ready   true
    :pending (work-ledger/live-work? runtime-db (:current-work entry))
    false))

(defn- route-blocking-error
  "Build the structured `:rf.error/resource-route-blocking` error for a
  BLOCKING route resource that failed its first load. ONE source of truth
  for both the route-slice `:error` (read by the `:rf/route` sub + Xray)
  and the error-trace tags (rf2-u5aj91) — they MUST agree. The tags
  conform to `ResourceRouteBlockingTags` (Spec-Schemas): `:resource-id`,
  `:nav-token`, `:error` (the resource's first-load failure envelope),
  `:reason`; `:category` is stamped from the operation by the trace
  envelope. Per Spec 016 §Route integration + Spec 009 §Error catalogue."
  [entry nav-token]
  {:rf.error/id :rf.error/resource-route-blocking
   :operation   :rf.error/resource-route-blocking
   :resource-id (:resource/id entry)
   :nav-token   nav-token
   :recovery    :no-recovery
   :error       (:error entry)
   :reason      "A blocking route resource failed its first load."})

(defn reconcile-readiness
  "Re-project the CURRENT route's readiness from the live Spec 016 resource
  facts, and prune the requirements that are done. THE reply-driven projector —
  every caller that has just written a resource entry (a settle, an adoption, a
  fresh-skip, hydration, restore) hands it the updated runtime-db instead of
  deciding for itself what the change meant. Returns the updated runtime-db.

  Its commit-time sibling is `re-frame.routing.readiness/project-at-commit`,
  which seeds the slice from the plan's blocking set in ROUTING vocabulary
  (plan-error / blocking / usable-data). Packaging requires two sites rather
  than one shared fn — see the block comment above — so the table below and
  that namespace's table must be edited together.

  The projection (Spec 012 §Route readiness is a resource projection):

  | current nav-token's outstanding blocking map | `:transition` | `:error`        |
  |----------------------------------------------|---------------|-----------------|
  | contains a `:failed` requirement             | `:error`      | first failure   |
  | otherwise contains a `:pending` requirement  | `:loading`    | nil             |
  | empty (all requirements met, or none)        | `:idle`       | nil             |

  Requirements that are `:ready` or `:inert` (`requirement-met?`) are pruned
  from the slot, so the slot always names what is still outstanding for this
  activation and a later invalidation of an already-satisfied requirement
  cannot re-block a landed route. A `:failed` requirement is NOT pruned — the
  route stays `:error` until that identity actually loads (e.g. a
  `:rf.resource/refetch` retry), which then projects back to `:idle`.

  NO blocking slot for the live nav-token (a route with no blocking resources,
  a route whose requirements have all resolved, or a committed PLANNING failure
  — which records `:error` on the slice and writes no slot) is a structural
  no-op: the slice is left exactly as committed. A superseded nav-token's slot
  is never consulted; `clear-blocking-slot` drops it wholesale on owner
  release.

  EDGE-TRIGGERED, on two separate edges. The SLICE is rewritten only when the
  projection actually differs from what it already carries, so re-projecting on
  every resource settle is idle for unrelated resources. The
  `:rf.error/resource-route-blocking` error TRACE (rf2-u5aj91) fires on the
  narrower edge — only when the slice was not ALREADY `:error` — so it is one
  trace per transition INTO `:error`, not one per settle. The two edges differ
  because `:error` is a pure function of the CURRENT outstanding set: a second
  blocking requirement failing later can change WHICH failure is deterministically
  first (the slice value moves, correctly) without the route ever leaving
  `:error` (so nothing new happened to report). Reading the PRE-write
  `:transition` off `db'` is what makes this a pure edge test — no latch, no
  remembered first failure, no extra state (rf2-kqxe6.17). That trace is the one
  out-of-band observability side effect — the same emit-inside-the-pure-
  transform discipline `route-resource-plan` uses for
  `:rf.error/resource-route-plan`. `:emit-error? false` suppresses it for the
  epoch-restore path, which defers its trace rows until the atomic install
  succeeds (rf2-obi8rr).

  Per Spec 016 §Route integration."
  ([runtime-db] (reconcile-readiness runtime-db nil))
  ([runtime-db {:keys [emit-error?] :or {emit-error? true}}]
   (let [slice-path (current-path)
         nav-token  (get-in runtime-db (conj slice-path :nav-token))
         blocking   (get-in runtime-db (blocking-path nav-token))]
     (if (empty? blocking)
       runtime-db
       (let [entry-of    (fn [k-id] (get-in runtime-db (state/entry-path-by-id k-id)))
             outstanding (into {} (remove (comp requirement-met? entry-of key)) blocking)
             ;; deterministic first failure: the outstanding slot promises no
             ;; order, so the pick is ordered by the canonical CEDN-1 byte
             ;; key-id the slot is already keyed on — stable across settles
             ;; that prune siblings, and read off the carrier rather than
             ;; recomputed (rf2-btdl1).
             failed      (->> (sort-by key outstanding)
                              (filter (comp #(= :failed (requirement-state %)) entry-of key))
                              first)
             transition  (cond failed :error (seq outstanding) :loading :else :idle)
             err         (when failed (route-blocking-error (entry-of (key failed)) nav-token))
             db'         (assoc-in runtime-db (blocking-path nav-token) outstanding)]
         (if (and (= transition (get-in db' (conj slice-path :transition)))
                  (= err        (get-in db' (conj slice-path :error))))
           db'
           (do
             (when (and err emit-error?
                        ;; rf2-kqxe6.17 — the EDGE into `:error`. `db'` still
                        ;; carries the PREVIOUS transition here, so an already-
                        ;; `:error` route that merely re-picks a canonically
                        ;; earlier failure updates the slice silently.
                        (not= :error (get-in db' (conj slice-path :transition))))
               ;; rf2-u5aj91: the route slice carries the structured :error AND
               ;; the trace/error stream sees `:rf.error/resource-route-blocking`
               ;; (tags conform to ResourceRouteBlockingTags).
               (trace/emit-error! :rf.error/resource-route-blocking
                                  (dissoc err :rf.error/id :operation)))
             (-> db'
                 (assoc-in (conj slice-path :transition) transition)
                 (assoc-in (conj slice-path :error) err)))))))))

;; ---- plan execution + the route-resource planning ctx seam (rf2-ac71vm) ---
;;
;; A route-resource `:scope` / `:when` resolver is `(fn [route ctx] …)`. The
;; ctx is the reserved entry context routing threads through its
;; `:routing/on-route-entry` hook (currently `{}`); db-derived viewer scope
;; comes from a `{:from-db …}` named-resolver reference, not from the ctx.
;; The seam is REAL (not a placeholder): the planner fails CLOSED when a
;; resolver needs the ctx but planning was handed no ctx (a `nil`), rather
;; than silently feeding the resolver `nil` and letting it collapse to an
;; empty-param / fallback-scope read.

(defn- planning-error
  "Build the canonical route-resource PLANNING ex-info via the central
  `error/thrown-ex-info` builder (Spec 009 §The thrown-error shape) — the
  same shape its intra-artefact sibling `registry/registration-error`
  follows. The fail-closed boundary `route-resource-plan` catches the throw
  and surfaces it on the route slice + Xray (never a silent cache miss).

  `reason` is the human sentence; the builder LEADS the message with it and
  TRAILS the `[:rf.error/resource-route-plan]` greppability token (rule 4),
  and stamps the canonical `:where` / `:recovery` slots. `extra` carries the
  surface-specific ex-data (`:resource-id`, `:from-db`, `:missing-after`,
  `:cycle`, …); a `:recovery` key inside it overrides the `:fix-params`
  default (each fail-closed site names its own `:fix-scope` / `:fix-after` /
  `:fix-route-integration`). Per Spec 016 §Route integration (a failed
  params / scope resolution is a planning error, NOT a silent fallback)."
  [reason extra]
  (error/thrown-ex-info
    :rf.error/resource-route-plan
    'rf/route-resource-plan
    reason
    {:recovery (get extra :recovery :fix-params)
     :extra    (dissoc extra :recovery)}))

(defn- when-passes?
  "Evaluate a route-resource entry's `:when` predicate. Absent `:when`
  passes. The predicate is `(fn [route ctx] …)`; truthy admits the
  resource. Per Spec 016 §Route integration (conditional resources use
  `:when`, NOT sentinel nil params). Fails CLOSED: a `:when` predicate that
  THROWS is a planning error surfaced on the route slice, never a silent
  admit/deny (rf2-ac71vm)."
  [{when-fn :when :keys [resource]} route ctx]
  (if when-fn
    (try
      (boolean (when-fn route ctx))
      (catch #?(:clj Throwable :cljs :default) ex
        (throw (planning-error
                 (str "route resource " resource " :when predicate threw — "
                      "a route/resource planning error, not a silent gate. "
                      "Per Spec 016 §Route integration.")
                 {:resource-id resource :recovery :fix-when :cause (ex-data ex)}))))
    true))

(defn- resolve-entry-params
  "Resolve a route-resource entry's params via its `:params` `(fn [route]
  …)`. An ABSENT `:params` resolver legitimately yields `{}` (the resource
  takes no params). A PRESENT `:params` resolver that returns `nil` is a
  fail-closed PLANNING error — it INTENDED to compute params and could not,
  which must NOT silently collapse to an empty-param read of a different
  cache key (rf2-ac71vm). Per Spec 016 §Route integration."
  [{params-fn :params :keys [resource]} route]
  (if params-fn
    (let [p (params-fn route)]
      (if (nil? p)
        (throw (planning-error
                 (str "route resource " resource " :params resolver returned "
                      "nil — a planning error, not a silent empty-param read. "
                      "Gate a conditional resource with :when (NOT nil params). "
                      "Per Spec 016 §Route integration.")
                 {:resource-id resource :recovery :fix-params}))
        p))
    {}))

(defn- resolve-entry-scope
  "Resolve a route-resource entry's scope to a CONCRETE value (the route
  tier of the precedence ladder), or nil when the entry declares NO route
  `:scope` (the spec scope policy then governs — `resolve-scope-for-event`
  with no route scope). A route-resource `:scope` may be:

    - a `{:from-db <id>}` named-resolver REFERENCE (EP-0016 D3 slice 3),
      resolved against the route-entry `app-db` at use time — db-derived
      viewer scope (session / tenant / account);
    - a `(fn [route ctx] …)` resolver, evaluated against the route + entry
      ctx (the route-resource resolver form — Spec 016 §Scope resolution
      precedence tier 2).

  A PRESENT `:scope` that resolves to `nil` (a reference whose declared
  inputs are absent, or a fn returning nil) is a fail-closed PLANNING error
  — it INTENDED to resolve the tenant / user / leak-boundary scope and could
  not, which must NOT silently fall through to the resource's spec policy or
  a `:rf.scope/global` read (rf2-ac71vm / Spec 016 §Resolver references —
  nil at a scope-requiring site is fail-closed). Per Spec 016 §Scope
  resolution (precedence tier 2)."
  [{scope-fn :scope :keys [resource]} route ctx app-db]
  (cond
    ;; a {:from-db …} reference — db-derived route-resource scope (slice 3)
    (scope-registry/from-db-reference? scope-fn)
    (let [s (scope-registry/resolve-from-db-reference
              scope-fn (or app-db {}) 'rf.resource/route-entry)]
      (if (nil? s)
        (throw (planning-error
                 (str "route resource " resource " :scope {:from-db "
                      (pr-str (:from-db scope-fn)) "} resolved nil against the "
                      "route-entry app-db — a planning error, not a silent "
                      "fallback to the spec policy or a global read. The named "
                      "resolver's declared :inputs are absent (e.g. no "
                      "logged-in user). Per Spec 016 §Resolver references / "
                      "§Scope resolution.")
                 {:resource-id resource :recovery :fix-scope :from-db (:from-db scope-fn)}))
        s))
    ;; a (fn [route ctx] …) resolver — the route-resource resolver form
    (fn? scope-fn)
    (let [s (scope-fn route ctx)]
      (if (nil? s)
        (throw (planning-error
                 (str "route resource " resource " :scope resolver returned "
                      "nil — a planning error, not a silent fallback to the "
                      "resource's spec scope policy or a global read. The "
                      "scope is the tenant / user / leak boundary and MUST "
                      "fail closed. Per Spec 016 §Scope resolution.")
                 {:resource-id resource :recovery :fix-scope}))
        s))
    ;; no route `:scope` — the spec policy governs (resolve-scope-for-event)
    :else nil))

;; ---- :after — DISPATCH-ORDER waterfall, fail-closed (rf2-xeb4l1) ----------
;;
;; DECISION (rf2-xeb4l1): `:after` is DISPATCH-ORDER ONLY, not a runtime
;; data-waterfall. The route plan is a PURE synchronous planner: it resolves
;; every entry's params + scope at route entry, BEFORE any resource can
;; settle, so a later entry's params CANNOT depend on an earlier entry's
;; loaded DATA (that would require re-running the plan after each settle — a
;; different architecture, out of scope for this slice). What `:after` DOES
;; guarantee is ENSURE-DISPATCH ORDER: a dependent entry's
;; `:rf.resource/ensure` is dispatched AFTER every entry it names, so the
;; dependency's fetch is kicked off first (the params themselves still come
;; from the ROUTE, not the dependency's data). Xray reads the declared
;; `:after` edges to show the dependency graph.
;;
;; This is NARROWER than the aspirational Spec 016 §Route integration wording
;; ("when its params depend on the first resource's data"); the spec line is
;; flagged for reconciliation to "dispatch-order" (a true data-waterfall is a
;; deferred slice). The validation below is the part both agree on and is now
;; FAIL-CLOSED: a missing or cyclic `:after` target is a PLANNING error (the
;; route slice's `:error` + Xray), NOT silent degradation to declaration
;; order — a typo'd dependency id is a real authoring bug.

(defn- order-by-after
  "Stable topological order of route-resource `entries` by their `:after`
  route-local-id dependencies (Spec 016 §Route integration — `:after`
  targets route-local `:id`s, NOT resource ids; the same resource may
  appear more than once with different params/ids). Entries with no
  `:after` keep declaration order; a dependent entry sorts after every
  local id it names.

  FAIL-CLOSED (rf2-xeb4l1): a `:after` target naming an id no entry
  declares, or an `:after` cycle, throws a route-resource `planning-error`
  (surfaced on the route slice + Xray, never silent declaration-order
  degradation). Returns the ordered vector."
  [entries]
  (let [local-id  (fn [e] (:id e))
        deps      (fn [e] (set (:after e)))
        known-ids (into #{} (keep local-id) entries)
        ;; missing-target validation: every :after id MUST name a declared
        ;; route-local :id (the same resource may appear under several ids).
        _ (doseq [e entries]
            (when-let [missing (seq (remove known-ids (deps e)))]
              (throw (planning-error
                       (str "route resource " (:resource e) " (local id "
                            (pr-str (local-id e)) ") declares :after " (pr-str (set missing))
                            " — no route-local :id matches. `:after` MUST target a "
                            "route-local :id declared on another entry. A typo'd "
                            "dependency is a planning error, not silent ordering. "
                            "Per Spec 016 §Route integration.")
                       {:resource-id (:resource e) :recovery :fix-after
                        :missing-after (vec missing) :local-id (local-id e)}))))
        ;; iterative stable settle: emit any entry whose deps are all already
        ;; emitted, declaration order within a pass. A pass that emits nothing
        ;; while entries REMAIN means a cycle (every remaining dep is unmet) —
        ;; fail closed.
        n         (count entries)]
    (loop [remaining (vec entries)
           emitted   []
           seen-ids  #{}
           guard     0]
      (cond
        (empty? remaining) emitted
        (> guard n)
        (throw (planning-error
                 (str "route resources have a cyclic :after dependency among "
                      (pr-str (into #{} (keep local-id) remaining))
                      " — a planning error, not silent declaration-order "
                      "fallthrough. Per Spec 016 §Route integration.")
                 {:recovery :fix-after
                  :cycle (into #{} (keep local-id) remaining)}))
        :else
        (let [{ready true held false}
              (group-by (fn [e] (every? #(contains? seen-ids %) (deps e)))
                        remaining)]
          (if (empty? ready)
            (throw (planning-error
                     (str "route resources have a cyclic :after dependency among "
                          (pr-str (into #{} (keep local-id) remaining))
                          " — a planning error, not silent declaration-order "
                          "fallthrough. Per Spec 016 §Route integration.")
                     {:recovery :fix-after
                      :cycle (into #{} (keep local-id) remaining)}))
            (recur (vec held)
                   (into emitted ready)
                   (into seen-ids (keep local-id ready))
                   (inc guard))))))))

;; ---- EP-0037 R2 — effective parent-chain composition ----------------------
;;
;; A full activation composes `:resources` from every route on the active
;; parent-to-leaf branch (Spec 016 §Effective parent-chain resource plans).
;; `branch` arrives as `[{:route-id :route-meta} …]` parent-most-first (routing
;; owns the `:parent` walk; resources composes). Each contributor's
;; `:params`/`:scope`/`:when` fn receives the LEAF target as its `route`
;; argument; the planner records the contributing route separately. Occurrences
;; are ordered by an occurrence graph (branch-order + admitted local `:after`),
;; then collapsed by resolved `[scope resource-id canonical-params]` identity
;; and stable-topologically ordered; a collapse-created cycle fails the plan.
;; A per-contributor failure carries the CONTRIBUTING route + local declaration
;; id out with it (`attributed`) — every contributor's resolvers run against
;; the LEAF target, so the leaf id alone cannot say WHICH declaration failed.

(defn- attributed
  "The per-contributor planning failure `ex`, with the CONTRIBUTING route id
  (and the local declaration id, when the failure is entry-scoped) attached to
  its ex-data under `:contributor`. The caller re-throws it.

  WHY the throw needs enriching (rf2-kqxe6.6): a parent-chain plan resolves
  EVERY contributor's declarations against the LEAF target, so the leaf
  `:route-id` the plan error carries cannot say WHICH declaration failed — an
  ancestor's nil `:params` resolver reported the leaf route and the resource id
  and nothing else. Spec 016 §Effective parent-chain resource plans rule 3
  requires the error to identify BOTH the contributor route id and the resource
  declaration, and the planner already knows both here, where the contributor
  context is still in scope. `plan-error` surfaces it on the route slice + the
  error trace.

  The ex-data is carried through verbatim (`:reason` / `:recovery` /
  `:resource-id` keep their site-specific values, so a nil-scope failure stays
  self-explaining); the ORIGINAL ex is the cause.

  `:contributor` is the ONE key the planner owns and always stamps. A `:when` /
  `:params` / `:scope` resolver is programmer code that may throw any `ex-info`
  it likes, including one carrying its own unnamespaced `:contributor` — and
  honouring that would publish a FALSE attribution on the route slice and the
  error trace, which is exactly what rule 3 forbids. The planner knows the
  ACTUAL contributor here and wins; the caller's value stays reachable on the
  cause (`(ex-data (ex-cause e))`). No idempotence guard is needed: the two call
  sites are structurally disjoint (the `order-by-after` attribution happens in
  the inner reduce's COLLECTION expression, before the per-entry catch can see
  anything), so no exception is ever attributed twice."
  [ex contributor]
  (ex-info (ex-message ex) (assoc (ex-data ex) :contributor contributor) ex))

(defn- materialize-occurrences
  "Resolve every admitted occurrence across the parent-to-leaf `branch`
  against the LEAF `route` target. Returns the vector of occurrence maps in
  (branch-index, local-order) order. Per contributor: validate + locally order
  its whole declared `:resources` (`order-by-after`, fail-closed on a
  missing/cyclic local `:after`), then, for each `:when`-admitted entry,
  resolve scope + canonical params (fail-closed) and compute its scoped-key
  identity (plus its byte `key-id`, the grouping key everything downstream
  uses). A validation/`:when`/params/scope THROW propagates to the plan
  boundary (a committed failed activation), CARRYING the contributing route +
  local declaration id (`attributed`). EP-0037 R2 rules 2-4."
  [branch route ctx app-db]
  (first
    (reduce
      (fn [acc-n [branch-index {:keys [route-meta]
                                contributor-id :route-id}]]
        (reduce
          (fn [[acc n] entry]
            (try
              (if-not (when-passes? entry route ctx)
                [acc n]
                (let [resource-id (:resource entry)
                      spec        (registry/require-resource-spec!
                                    resource-id 'rf.resource/route-entry)
                      raw-params  (resolve-entry-params entry route)
                      route-scope (resolve-entry-scope entry route ctx app-db)
                      scope       (registry/resolve-scope-for-event
                                    resource-id spec {:route-scope route-scope :db app-db}
                                    'rf.resource/route-entry)
                      cparams     (registry/validate+canonicalize-params
                                    resource-id spec raw-params
                                    'rf.resource/route-entry)
                      scoped-key  (state/scoped-resource-key* scope resource-id cparams)]
                  [(conj acc {:occ-id         [contributor-id (:id entry) n]
                              :seq            n
                              :branch-index   branch-index
                              :route-id       contributor-id
                              :local-id       (:id entry)
                              :after          (set (:after entry))
                              :resource       resource-id
                              :scope          scope
                              :cparams        cparams
                              :scoped-key     scoped-key
                              ;; rf2-btdl1 — the CEDN-1 byte identity, computed
                              ;; ONCE here and used as the grouping / carrier
                              ;; key everywhere downstream. `=` is coarser than
                              ;; this (vector-vs-list params), so grouping on
                              ;; the scoped key itself collapses a supported
                              ;; pair before any ensure is dispatched.
                              :key-id         (state/key-id scoped-key)
                              :blocking?      (boolean (:blocking? entry))
                              :keep-previous? (boolean (:keep-previous? entry))})
                   (inc n)]))
              (catch #?(:clj Throwable :cljs :default) ex
                (throw (attributed ex {:route-id contributor-id
                                       :local-id (:id entry)})))))
          acc-n
          ;; rule 2: validate + locally order the WHOLE declared vector. A
          ;; missing / cyclic local `:after` names its own local id, but only
          ;; this frame knows WHICH contributor declared it.
          (try
            (order-by-after (:resources route-meta))
            (catch #?(:clj Throwable :cljs :default) ex
              (throw (attributed ex (cond-> {:route-id contributor-id}
                                      (:local-id (ex-data ex))
                                      (assoc :local-id (:local-id (ex-data ex))))))))))
      [[] 0]
      (map-indexed vector branch))))

(defn- occurrence-edges
  "The 'must-precede' edges (`[from-occ-id to-occ-id]`) among admitted
  occurrences: the branch-order constraint (every ancestor occurrence precedes
  every descendant occurrence) plus admitted local `:after` (a dependency in
  the SAME contributor precedes its dependent). EP-0037 R2 rule 5."
  [occs]
  (into #{}
        (concat
          (for [a occs, b occs
                :when (< (:branch-index a) (:branch-index b))]
            [(:occ-id a) (:occ-id b)])
          (for [this occs, dep occs
                :when (and (= (:route-id this) (:route-id dep))
                           (not= (:occ-id this) (:occ-id dep))
                           (contains? (:after this) (:local-id dep)))]
            [(:occ-id dep) (:occ-id this)]))))

(defn- redundant-child-advisories
  "The redundant-child advisories: a group whose occurrences span more than one
  contributor route means a descendant redundantly declared an identity an
  ancestor already contributes. Names the ancestor-most declaration and each
  redundant descendant declaration (Spec 016 §Effective parent-chain resource
  plans — the child copy is mechanically discoverable so it can be deleted)."
  [groups]
  (into []
        (mapcat
          (fn [[_key-id occs]]
            (let [by-seq (sort-by :seq occs)]
              (when (> (count (into #{} (map :route-id) occs)) 1)
                (let [ancestor (first by-seq)]
                  (for [child (rest by-seq)
                        :when (not= (:route-id child) (:route-id ancestor))]
                    {:resource   (:resource ancestor)
                     ;; the advisory names the KIND-PRESERVING scoped key, not
                     ;; the byte `key-id` the group is keyed on (rf2-btdl1).
                     :scoped-key (:scoped-key ancestor)
                     :ancestor   {:route-id (:route-id ancestor) :local-id (:local-id ancestor)}
                     :child      {:route-id (:route-id child)    :local-id (:local-id child)}}))))))
        groups))

(defn- collapse-and-order
  "Collapse occurrences by resource identity and stable-topologically order the
  grouped graph. Returns `{:ordered [dedup-req …] :advisories […]}` or
  `{:cycle [scoped-key …]}` when identity collapse makes the grouped graph
  cyclic (EP-0037 R2 rule 6 — the plan then fails and dispatches no ensures).
  A dedup-req combines the group per the constraint-preserving rules: blocking
  when ANY contributor blocks, `:keep-previous?` when ANY requests it, every
  contributor retained in `:contributors`; the earliest occurrence (min `:seq`)
  fixes the group's position + is the topo tie-breaker.

  rf2-btdl1 — the grouping grain is the CEDN-1 byte `key-id`, NOT Clojure `=`
  over the scoped key. `=` is coarser exactly where resource identity is not
  (vector-vs-list params — rf2-wgutc2), so grouping on the scoped key made two
  route entries requiring genuinely distinct identities collapse into ONE
  dedup-req: one ensure dispatched, the second byte identity never fetched.
  That is a DISPATCH defect, not a diagnostic one. The dedup-req still carries
  the kind-preserving `:scoped-key` — that is what consumers join on — beside
  the `:key-id` the carriers are keyed on."
  [occs]
  (let [occ-by-id  (into {} (map (juxt :occ-id identity)) occs)
        group-of   (fn [oid] (:key-id (occ-by-id oid)))
        groups     (group-by :key-id occs)
        gkeys      (set (keys groups))
        gedges     (into #{}
                         (comp (map (fn [[u v]] [(group-of u) (group-of v)]))
                               (remove (fn [[gu gv]] (= gu gv))))
                         (occurrence-edges occs))
        group-rank (into {} (map (fn [[gk os]] [gk (reduce min (map :seq os))])) groups)
        succ       (reduce (fn [m [gu gv]] (update m gu (fnil conj #{}) gv)) {} gedges)
        indeg0     (reduce (fn [m [_ gv]] (update m gv inc))
                           (zipmap gkeys (repeat 0)) gedges)]
    (loop [indeg indeg0, emitted []]
      (if (empty? indeg)
        {:ordered
         (mapv (fn [gk]
                 (let [os     (sort-by :seq (groups gk))
                       rep    (first os)]
                   {:scoped-key     (:scoped-key rep)
                    :key-id         gk
                    :resource       (:resource rep)
                    :scope          (:scope rep)
                    :cparams        (:cparams rep)
                    :blocking?      (boolean (some :blocking? os))
                    :keep-previous? (boolean (some :keep-previous? os))
                    :contributors   (mapv (fn [o] {:route-id     (:route-id o)
                                                   :local-id     (:local-id o)
                                                   :branch-index (:branch-index o)})
                                          os)}))
               emitted)
         :advisories (redundant-child-advisories groups)}
        (let [ready (keep (fn [[g d]] (when (zero? d) g)) indeg)]
          (if (empty? ready)
            ;; the cycle is REPORTED in scoped keys — a byte `key-id` blob
            ;; names nothing an author could act on.
            {:cycle (mapv (comp :scoped-key first groups) (keys indeg))}
            (let [g      (first (sort-by group-rank ready))
                  indeg' (reduce (fn [m s] (update m s dec))
                                 (dissoc indeg g) (succ g))]
              (recur indeg' (conj emitted g)))))))))

(defn- branch-plan-error
  "Build the route/resource PLANNING error MAP for a fail-loud branch-resolution
  failure (an unresolved `:parent` or a `:parent` cycle — EP-0037 R2 rule 1),
  handed up from routing's fail-loud branch walk as `branch-error`. The MAP
  shape mirrors `plan-error` (the route-slice `:error` + Xray consume it)."
  [route-id nav-token {:keys [kind route-id* chain]}]
  {:rf.error/id :rf.error/resource-route-plan
   :route-id    route-id
   :nav-token   nav-token
   :recovery    :fix-parent
   :reason      (case kind
                  :unknown-parent
                  (str "route " route-id " declares a :parent chain naming the "
                       "unregistered route " (pr-str route-id*) " — branch "
                       "resolution is fail-loud, not silently truncated. Per Spec "
                       "016 §Effective parent-chain resource plans.")
                  :parent-cycle
                  (str "route " route-id " has a :parent cycle at " (pr-str route-id*)
                       " (chain " (pr-str chain) ") — branch resolution is fail-loud. "
                       "Per Spec 016 §Effective parent-chain resource plans.")
                  (str "route " route-id " branch resolution failed. Per Spec 016 "
                       "§Effective parent-chain resource plans."))
   :cause       {:branch-error kind :parent route-id*}})

(defn- collapse-cycle-error
  "Build the route/resource PLANNING error MAP for a collapse-created cycle
  among the grouped identities `cyclic` (EP-0037 R2 rule 6)."
  [route-id nav-token cyclic]
  {:rf.error/id :rf.error/resource-route-plan
   :route-id    route-id
   :nav-token   nav-token
   :recovery    :fix-after
   :reason      (str "route " route-id " resource plan has a cyclic dependency "
                     "after identity collapse among " (pr-str cyclic) " — a later "
                     "occurrence's :after edge would contradict an earlier "
                     "occurrence's position once the shared identity is deduped. "
                     "The plan fails rather than silently dropping an edge. Per "
                     "Spec 016 §Effective parent-chain resource plans.")
   :cause       {:cyclic-identities (vec cyclic)}})

(defn- plan-error
  "Build the structured route/resource PLANNING error for a params/scope
  failure on a route resource (Spec 016 §Route integration — a failed
  params schema is a route/resource planning error visible in route state
  + Xray, NOT a silent cache miss). `ex` is the canonicalization /
  validation throw caught from the resource runtime's fail-closed
  boundary — OR a `route`-side `planning-error` (nil params / nil scope /
  a throwing `:when`, rf2-ac71vm). The error is recorded on the route
  slice's `:error` by `commit-navigation` (visible to the `:rf/route` sub +
  Xray) and emitted as a `:rf.error/resource-route-plan` error trace.

  When the caught ex carries a route-side `planning-error` ex-data, its
  specific `:reason` / `:recovery` are surfaced (so a nil-scope vs
  nil-params vs invalid-params failure stays self-explaining); otherwise
  the generic params/scope-did-not-resolve message stands.

  rf2-kqxe6.6: `:contributor` names the CONTRIBUTING route + local declaration
  (`{:route-id … :local-id …}`, stamped by `materialize-occurrences`), so a
  failure in an ANCESTOR declaration is not reported as if the leaf had
  declared it. `:route-id` remains the LEAF target — the two together are what
  Spec 016 §Effective parent-chain resource plans rule 3 requires. It is absent
  only for a failure that belongs to no single declaration (branch resolution,
  collapse cycle).

  rf2-9g3qzi: this map carries NO `:operation` slot. The canonical
  thrown-error shape (frame.cljc `no-frame-context-payload`) reserves
  `:operation` for the DISTINCT runtime op (`:dispatch` / `:subscribe`) —
  here the only op is the planning step itself, already named by
  `:rf.error/id :rf.error/resource-route-plan`. The error trace's
  `:operation` / `:category` come from the EXPLICIT `:rf.error/resource-
  route-plan` arg `emit-error!` is called with (route.cljc), not this map,
  so a duplicated `:operation` shadowing `:rf.error/id` carried no
  information."
  [route-id nav-token resource-id ex]
  (let [data (ex-data ex)]
    (cond-> {:rf.error/id :rf.error/resource-route-plan
             :route-id    route-id
             :resource-id resource-id
             :nav-token   nav-token
             :recovery    (get data :recovery :fix-params)
             :reason      (or (:reason data)
                              (str "route " route-id " resource " resource-id
                                   " failed planning (params / scope did not resolve) — a "
                                   "route/resource planning error, not a silent cache miss. "
                                   "Per Spec 016 §Route integration."))
             :cause       data}
      (:contributor data) (assoc :contributor (:contributor data)))))

(defn route-resource-plan
  "Build the resource ensure/release plan for a route's `:resources`
  metadata on entry. Pure planner over the route + ctx; the runtime
  effects (ensure dispatches, blocking-slot write, prior-owner release,
  planning-error trace) are returned as an fx vector + the blocking
  identity map. Per Spec 016 §Route integration.

  `route` is the resolved route value
  (`{:id :params :query :fragment …}`), `ctx` the reserved entry context
  (the seam routing threads through `:routing/on-route-entry`; currently `{}`
  — a `:scope` / `:when` resolver receives it as its trailing argument).
  `entry-ctx` carries `:nav-token`, `:prev-id`, `:prev-nav-token`, `:app-db`
  (the route-entry app-db value — see the `:app-db` paragraph below), and
  `:runtime-db` (the pre-commit runtime-db, read for the AT-COMMIT resource
  facts that decide which blocking requirements still have to resolve —
  `requirement-ready?`). Returns `{:fx [...] :blocking {<key-id> <scoped-key>}
  :identities {<key-id> <scoped-key>} :plan-error err?}` — both identity
  carriers are byte-keyed maps with NO order promise (rf2-btdl1).

  FAIL-CLOSED structural inputs (rf2-ac71vm): a `nil` `ctx` or a missing
  `nav-token` is a planning bug, not a silently-defaulted read — the owner
  token (`[:route route-id nav-token]`) IS the route's ownership identity,
  so planning with no nav-token would mint an unreleasable owner. Both
  throw at the boundary (a programming error in the routing↔resources seam,
  surfaced loudly rather than swallowed).

  Per entry: eval `:when` (a not-sentinel-nil guard — a falsey `:when`
  gates the resource out, a THROWING `:when` is a planning error); resolve
  scope + canonical params (fail-closed — a nil/invalid scope or params is
  a PLANNING error surfaced on the route slice, NOT a silent global /
  empty-param fallback); order by `:after` route-local dependencies (a
  missing / cyclic target is a planning error); emit `:rf.resource/ensure`
  with owner `[:route route-id nav-token]` + cause `[:route-entry route-id
  nav-token]`; classify blocking (recorded under the nav-token so the
  transition stays `:loading`) vs background. `:keep-previous?` is threaded
  onto the ensure payload so the runtime projects the previous key's data
  while the new key first-loads. The previous route's owner token is
  released.

  `app-db` is the route-entry app-db value (threaded from the navigation
  handler's coeffect by `on-route-entry-fx` / routing's `commit-navigation`)
  — a `{:from-db <id>}` route-resource `:scope` (or the resource's spec
  `:scope` policy) resolves against it at route entry, BEFORE planning the
  resource work (EP-0016 D3 slice 3). A reference that resolves nil is a
  fail-closed planning error (route planning MUST NOT substitute global)."
  [route ctx {:keys [nav-token prev-id prev-nav-token app-db runtime-db branch
                     branch-error prev-identities]}]
  ;; rf2-ac71vm — fail closed on missing/invalid structural planning inputs.
  ;; These are seam-contract bugs (routing must thread a ctx + nav-token),
  ;; surfaced loudly rather than collapsing into an empty-ctx / no-owner read.
  (when (nil? ctx)
    (throw (planning-error
             (str "route-resource-plan was handed a nil ctx for route "
                  (pr-str (:id route)) " — the entry ctx seam MUST be a map "
                  "(empty is fine; nil is a routing↔resources seam bug). A "
                  ":scope / :when resolver reads it. Per Spec 016 §Route "
                  "integration.")
             {:route-id (:id route) :recovery :fix-route-integration})))
  (when (nil? nav-token)
    (throw (planning-error
             (str "route-resource-plan was handed no nav-token for route "
                  (pr-str (:id route)) " — the nav-token IS the route owner "
                  "identity ([:route route-id nav-token]); planning without "
                  "one would mint an unreleasable owner. Per Spec 016 §Route "
                  "integration.")
             {:route-id (:id route) :recovery :fix-route-integration})))
  (let [route-id  (:id route)
        owner     [:route route-id nav-token]
        cause     [:route-entry route-id nav-token]
        ;; EP-0037 R2: compose the effective parent-to-leaf branch. Routing
        ;; owns the `:parent` walk and hands the resolved metas down as
        ;; `:branch` (parent-most-first); a direct call with no `:branch` (or
        ;; the leaf-only tests) synthesises a single-element branch from the
        ;; `route`'s own `:resources`, so leaf-only planning is the 1-segment
        ;; case of the same composer.
        branch    (or branch [{:route-id route-id
                               :route-meta {:resources (:resources route)}}])
        ;; release the PREVIOUS route's owner (route leave / supersession).
        ;; Ordered LAST in the fx (attach-before-release): the resource runtime
        ;; drops the owner from every entry's :active-owners, but only AFTER
        ;; this plan has attached its owner to every kept + added identity, so a
        ;; still-in-flight ancestor shared by both plans is never momentarily
        ;; ownerless (never aborted). Per Spec 016 §Plan diff and owner handoff.
        release-fx (when (and prev-id prev-nav-token
                              (not= [prev-id prev-nav-token] [route-id nav-token]))
                     [[:dispatch [:rf.resource/release-owner
                                  {:owner [:route prev-id prev-nav-token]}]]])
        ;; Compose + collapse. A branch-resolution failure (fail-loud), a
        ;; per-contributor `:after`/`:when`/params/scope throw, or a
        ;; collapse-created cycle is a PLANNING error → a committed failed
        ;; activation (empty next ownership, no ensures, prior owner released).
        {:keys [ordered advisories plan-error]}
        (cond
          branch-error
          (let [err (branch-plan-error route-id nav-token branch-error)]
            (trace/emit-error! :rf.error/resource-route-plan err)
            {:plan-error err})
          :else
          (try
            (let [occs      (materialize-occurrences branch route ctx app-db)
                  collapsed (collapse-and-order occs)]
              (if-let [cyclic (:cycle collapsed)]
                (let [err (collapse-cycle-error route-id nav-token cyclic)]
                  (trace/emit-error! :rf.error/resource-route-plan err)
                  {:plan-error err})
                collapsed))
            (catch #?(:clj Throwable :cljs :default) ex
              ;; when / params / scope / :after PLANNING failure — surface on
              ;; the route slice + Xray, never a silent cache miss. The error
              ;; names the resource when the ex carries it.
              (let [err (plan-error route-id nav-token
                                    (:resource-id (ex-data ex)) ex)]
                (trace/emit-error! :rf.error/resource-route-plan err)
                {:plan-error err}))))
        ;; EP-0037 R2 plan diff: partition the dedup'd identities into
        ;; added (ensure with the next owner + freshness) vs kept (adopt the
        ;; next owner WITHOUT a fetch — the partial-revalidation law: a kept,
        ;; unchanged ancestor is not revalidated by navigation). Removed
        ;; identities (in prev, not next) are released by the whole-prior-owner
        ;; release-fx. Per Spec 016 §Plan diff and owner handoff.
        ;;
        ;; rf2-kqxe6.6 — an identity is KEPT when it is in the previous plan AND
        ;; its entry is genuinely REUSABLE at commit (`adoptable?`: own usable
        ;; data, or genuinely live work). Prior-plan membership alone is not
        ;; enough: `adopt-owner` issues no request, so adopting an identity that
        ;; has been cleared / removed / GC'd, or that is settled with no data
        ;; and no live work, would commit a blocking slot nothing could ever
        ;; drain — a permanent `:loading`. A retained-but-unusable identity is
        ;; therefore treated exactly like an added one: the ordinary
        ;; ensure/readiness path. `runtime-db` is the pre-commit runtime-db
        ;; routing threads into the hook; without it (a direct planner call in a
        ;; unit) nothing reads as adoptable and everything ensures — the
        ;; fail-safe direction, matching the blocking read below.
        ;; rf2-dlkou (merged-PR audit of #7228) — the identity carriers are keyed
        ;; by the CEDN-1 BYTE key-id, NOT by Clojure `=`.
        ;;
        ;; Resource identity is `state/key-id`, which is collection-KIND
        ;; sensitive (rf2-wgutc2): `{:p [1 2]}` and `{:p '(1 2)}` are two
        ;; entries under two `state/entry-path`s, and yet the two scoped keys
        ;; are `=` to Clojure and hash alike. A raw `set` of scoped keys
        ;; therefore COLLAPSES a supported pair before anything downstream can
        ;; canonicalize it — the row's `sort-by state/key-id` runs after the
        ;; loss, not before it. What that cost: a navigation whose prior plan
        ;; held `{:p '(1 2)}` and whose next plan holds `{:p [1 2]}` reported
        ;; `:removed 0` and an EMPTY `:removed-identities`, because the prior
        ;; identity tested as still-present against a set that only knows `=`.
        ;; The dropped identity was real — its entry lives at its own byte path
        ;; and the prior owner's release did let it go — so the row contradicted
        ;; the runtime. `adopted?` read the same way, and only avoided adopting
        ;; across the pair because `adoptable?` looks the ENTRY up by byte path
        ;; and found none: a fail-safe accident rather than a decision.
        ;;
        ;; Both carriers are consequently maps from `key-id` to the scoped key.
        ;; Membership is byte-exact; the EMITTED value is still the scoped key,
        ;; because that is what a consumer joins on.
        ;;
        ;; rf2-btdl1 — and so is the HANDOFF. `prev-identities` arrives as the
        ;; byte-keyed map routing recorded under `[:rf.runtime/routing
        ;; :resource-plan <token>]`, which needs no keying; a direct planner
        ;; call may hand any collection of scoped keys, which does.
        prev-by-id (if (map? prev-identities)
                     prev-identities
                     (into {} (map (juxt state/key-id identity)) prev-identities))
        next-by-id (into {} (map (juxt :key-id :scoped-key)) ordered)
        entry-of  (fn [k-id] (get-in runtime-db (state/entry-path-by-id k-id)))
        adopted?  (fn [{:keys [key-id]}]
                    (and (contains? prev-by-id key-id)
                         (adoptable? runtime-db (entry-of key-id))))
        req-fx    (mapv
                    (fn [{:keys [resource scope cparams keep-previous?] :as req}]
                      (let [base {:resource resource :scope scope :params cparams
                                  :owner owner :cause cause}]
                        (if (adopted? req)
                          ;; kept + reusable — pure owner adoption, NO fetch
                          [:dispatch [:rf.resource/adopt-owner base]]
                          ;; added, or retained-but-unusable — ordinary ensure
                          [:dispatch [:rf.resource/ensure
                                      (cond-> base
                                        keep-previous? (assoc :keep-previous? true))]])))
                    ordered)
        ;; EP-0037 R1 — read the Spec 016 facts AT COMMIT. A blocking
        ;; requirement whose identity ALREADY has usable data
        ;; (`requirement-ready?`) is recorded nowhere: it has nothing left to
        ;; wait for, so the commit projects `:idle` immediately instead of a
        ;; transient `:loading` that the very next `ensure` fresh-skip would
        ;; undo. EVERY other state IS recorded — the plan has just guaranteed
        ;; work for it (an adoption joins live work; an ensure starts a fresh
        ;; attempt for anything that is not a fresh `:loaded` entry), so the
        ;; route must wait for the data it declared blocking rather than
        ;; committing `:idle` over an identity with none. Without a
        ;; `runtime-db` nothing reads as ready and every blocking requirement is
        ;; recorded, which is the fail-safe direction.
        blocking  (into {}
                        (comp (filter :blocking?)
                              (remove #(requirement-ready? (entry-of (:key-id %))))
                              (map (juxt :key-id :scoped-key)))
                        ordered)
        ;; EP-0037 R2 plan-diff projection (Tooling: old/new kept/added/removed
        ;; identities): `:ensured` counts the ADDED identities (real ensures),
        ;; `:kept` the adopted (owner-handed-off, not re-ensured) identities, and
        ;; `:removed` the prior-plan identities this plan drops. A retained
        ;; identity that could not be adopted counts as ensured, so the trace
        ;; reports what actually happened.
        ;;
        ;; rf2-dlkou (the rf2-9sluz ruling) — the counts stay as the compact
        ;; headline and the row ALSO carries the exact partition, so one trace
        ;; answers "which identity was ensured / kept / removed on this
        ;; navigation" without diffing two consecutive rows. The vectors are
        ;; named for what the runtime DID rather than for the diff: since the
        ;; retained-entry liveness repair a retained-but-unusable identity takes
        ;; the ordinary ensure path, so a vector named `:added` would disagree
        ;; with the `:ensured` count beside it. `:ensured-identities` /
        ;; `:kept-identities` ride `ordered`'s grouped plan order — the same
        ;; order `:identities` carries, and it MEANS something there: it is the
        ;; order the plan executes.
        ;;
        ;; rf2-dlkou (merged-PR audit) — `:removed-identities` is a MEMBERSHIP
        ;; answer, not an ordered one. Removal is not an ordered operation: the
        ;; whole prior owner goes in ONE `release-fx`, so there is no order for
        ;; the row to report. Nor is one available — the routing handoff records
        ;; `(:identities plan)` (an unordered MAP, rf2-btdl1) under
        ;; `[:rf.runtime/routing :resource-plan <token>]` and hands it straight
        ;; back as the next activation's `prev-identities`, so filtering the
        ;; caller's collection in place would only republish map-iteration order
        ;; while CLAIMING the prior plan's.
        ;;
        ;; Dropping to a de-duplicated prior collection is necessary but NOT
        ;; sufficient, and the CLJS lane proved it: a small CLJS set is backed by
        ;; an ARRAY map, so it iterates in INSERTION order and the caller's
        ;; sequence walks straight back out — `[v a b c]` and its exact reverse
        ;; produced reversed rows — while a JVM hash set happens to iterate in a
        ;; content-derived order that hid the leak. The vector is therefore
        ;; ordered by `key-id`, the CEDN-1 byte identity `:entries` is already
        ;; keyed on: total over canonical scoped keys and identical on both
        ;; hosts. That is what actually makes the row a pure function of the
        ;; removal MEMBERSHIP — the same removal set yields the same vector for
        ;; every caller shape on every host — and `:removed` is its size BY
        ;; CONSTRUCTION rather than a second, separately-derived count that a
        ;; duplicate-bearing `prev-identities` could put out of step. The order
        ;; is CANONICAL, not meaningful: consumers read membership from it,
        ;; exactly as they do from `:blocking`. Spec 009 §Where trace emission
        ;; lives states it.
        ;;
        ;; The membership itself is byte-exact now (`prev-by-id` / `next-by-id`
        ;; above), so the `key-id` ordering is read off the carrier's own keys
        ;; rather than recomputed — one derivation of the identity, used for both
        ;; the difference and the order.
        ;;
        ;; NOTHING REMAINS UPSTREAM, and that is the point of rf2-btdl1: the
        ;; byte-distinct `=` pair survives the WHOLE path now. `ordered` comes
        ;; from `collapse-and-order`, which groups by `key-id`, so two route
        ;; entries requiring the pair produce two dedup-reqs and two ensures;
        ;; and the routing handoff records a plan's identities as a byte-keyed
        ;; MAP, so both members ride to the next activation and each is diffed,
        ;; blocked, reconciled and drained on its own. A caller that supplies
        ;; both as `prev-identities` — a direct planner call OR the live handoff
        ;; — gets both reported.
        ;;
        ;; Nothing else from the EP's §Tooling list is projected — no
        ;; occurrence/dependency groups, no per-contributor requirement mapping,
        ;; no local `:after` edges (internal planning mechanics; Xray's static
        ;; route/resource graph, the planning-failure evidence, and
        ;; `:redundant-children` are the authorities).
        ensured-identities (into [] (comp (remove adopted?) (map :scoped-key)) ordered)
        kept-identities    (into [] (comp (filter adopted?) (map :scoped-key)) ordered)
        removed-identities (into []
                                 (comp (remove (fn [[k-id _]] (contains? next-by-id k-id)))
                                       (map val))
                                 (sort-by key prev-by-id))
        added-count        (count ensured-identities)
        removed-count      (count removed-identities)]
    (trace/emit! :rf.event :rf.resource/route-plan
                 (cond-> {:route-id           route-id
                          :nav-token          nav-token
                          :branch             (mapv :route-id branch)
                          :ensured            added-count
                          :kept               (- (count ordered) added-count)
                          :removed            removed-count
                          ;; an ORDERED TRACE VECTOR of scoped keys, read off
                          ;; the byte-keyed carrier's values (rf2-btdl1) — a
                          ;; membership answer with no promised order, exactly
                          ;; as before.
                          :blocking           (vec (vals blocking))
                          ;; the planner's GROUPED PLAN ORDER — `ordered` is
                          ;; post-dedupe (one entry per collapsed identity
                          ;; group), so this carries each identity exactly once,
                          ;; in the order the plan executes. `next-by-id` is the
                          ;; same content byte-keyed and rides the RETURN map
                          ;; below, which `commit-navigation` consumes.
                          :identities         (mapv :scoped-key ordered)
                          :ensured-identities ensured-identities
                          :kept-identities    kept-identities
                          :removed-identities removed-identities}
                   (seq advisories) (assoc :redundant-children advisories)
                   plan-error       (assoc :plan-error true)))
    ;; The blocking + identity MAPS (`{<key-id> <scoped-key>}`) and plan-error
    ;; ride back to `commit-navigation`, which writes the blocking + plan slots
    ;; under the nav-token + records a plan-error on the route slice's `:error`,
    ;; ATOMICALLY with the commit. The fx (attach effects then the prior-owner
    ;; release) are spliced into the commit fx — attach-before-release.
    {:fx         (vec (concat req-fx release-fx))
     :blocking   blocking
     :identities next-by-id
     :advisories advisories
     :plan-error plan-error}))

(defn on-route-entry-fx
  "The `:routing/on-route-entry` hook body routing's `commit-navigation`
  consults. Returns `{:fx [...] :blocking {<key-id> <scoped-key>} :identities
  {<key-id> <scoped-key>} :plan-error
  err?}`, or nil when there is NOTHING to do (the new route declares no
  `:resources` AND there is no prior route owner to release). The plan
  runs whenever either side has work: it ensures the new route's resources
  AND releases the PREVIOUS route's owner — so navigating from a
  resource-owning route to a plain route still releases the prior owner
  (route leave is not conditional on the NEW route declaring resources).
  `commit-navigation` splices `:fx` into the commit fx, writes `:blocking`
  into the nav-token's blocking slot, and records `:plan-error` on the
  slice — all atomic with the commit. `entry` is the hook arg
  `{:route-meta :route-id :params :query :fragment :nav-token :prev-id
  :prev-nav-token :ctx}`. The `:ctx` is passed through to the planner
  UNCHANGED (no silent nil→`{}` default): an absent ctx is a routing↔
  resources seam bug the planner fails closed on, not a silently-empty read
  (rf2-ac71vm). Routing's `commit-navigation` always threads an (at-least-
  empty) `:ctx`.

  `:app-db` is the route-entry app-db value (EP-0016 D3 slice 3): routing's
  navigation handlers carry the app-db coeffect and thread it through
  `commit-navigation` into this hook, so a `{:from-db <id>}` route-resource
  `:scope` (or the resource's spec `:scope` policy) resolves against the
  CURRENT db BEFORE the resource work is planned. An absent `:app-db` (a
  routing build that predates this thread) resolves references against `{}`
  — fail-closed (a `{:from-db …}` scope then resolves nil and surfaces as a
  route planning error, never a silent global). Per Spec 016 §Route
  integration / §Resolver references.

  `:runtime-db` is the pre-commit runtime-db value (threaded by routing's
  `commit-navigation` alongside `:app-db`). It carries the Spec 016 resource
  facts the plan reads AT COMMIT to decide which blocking requirements still
  have to resolve, so an already-fresh blocking resource commits `:idle`
  rather than a transient `:loading`. An absent `:runtime-db` records every
  blocking requirement — the fail-safe direction. Per Spec 012 §Route
  readiness is a resource projection.

  EP-0037 R2: `:branch` is the effective parent-to-leaf contributor chain
  (`[{:route-id :route-meta} …]` parent-most-first) routing resolves from the
  target's `:parent` links; `:branch-error` is a fail-loud branch-resolution
  descriptor (an unresolved / cyclic `:parent`); `:prev-identities` is the
  byte-keyed `{<key-id> <scoped-key>}` map of resource identities the
  SUPERSEDED nav-token's plan owned (the plan-diff's previous membership; a
  direct planner call may hand any collection of scoped keys instead). A
  routing build that predates R2 threads no
  `:branch`, and the planner falls back to a single-segment (leaf-only) branch."
  [{:keys [route-meta route-id params query fragment nav-token
           prev-id prev-nav-token ctx app-db runtime-db branch branch-error
           prev-identities]}]
  (let [branch (or branch [{:route-id route-id :route-meta route-meta}])]
    (when (or branch-error prev-id
              (some (fn [c] (seq (:resources (:route-meta c)))) branch))
      (let [route {:id        route-id
                   :params    params
                   :query     query
                   :fragment  fragment
                   :resources (:resources route-meta)}]
        (route-resource-plan route ctx
                             {:nav-token       nav-token
                              :prev-id         prev-id
                              :prev-nav-token  prev-nav-token
                              :app-db          app-db
                              :runtime-db      runtime-db
                              :branch          branch
                              :branch-error    branch-error
                              :prev-identities prev-identities})))))

;; ---- EP-0037 R3 — warm-mode intent prefetch -------------------------------
;;
;; A prefetch composes the SAME effective parent-to-leaf branch plan a full
;; activation builds (the same `:parent` walk, `:when` / `:params` / `:scope`
;; resolution, and `[scope resource-id canonical-params]` identity dedupe), but
;; runs each unique requirement through an OWNERLESS `ensure` with cause
;; `[:route-prefetch route-id]`. It differs from `route-resource-plan` on a
;; small, precise set of points (Spec 016 §Route-plan prefetch — warm-mode):
;;   - ownerless ensures (no owner → GC-eligible if the nav never happens);
;;   - `:blocking?` inert (no nav-token, no blocking slot, no readiness change);
;;   - no plan diff / owner handoff / prior-owner release (prefetch owns no
;;     prior plan) — every unique requirement is a plain `ensure`;
;;   - a planning failure emits `:rf.error/resource-route-plan` with
;;     `:plan-cause :prefetch` and NO nav-token, dispatches no partial ensures,
;;     and touches no route state (a preload owns none).
;; Later activation reuse is automatic: the activation `ensure` for the same
;; identity JOINS this warm work (dedupe) and attaches its real owner.

(defn route-resource-warm-plan
  "Build the WARM-mode resource plan for a prefetch of `route` over the
  effective parent-to-leaf `branch` (`[{:route-id :route-meta} …]` parent-most-
  first). Returns `{:fx [ensure-dispatch …] :warmed <n> :plan-error err?}`. Each
  unique requirement dispatches `[:rf.resource/ensure {:resource :scope :params
  :cause}]` — OWNERLESS, cause `[:route-prefetch route-id]`, no `:blocking?` /
  `:keep-previous?` / `:owner`. A branch-resolution failure, a per-contributor
  `:when` / params / scope throw, or a collapse-created cycle is a PLANNING
  error: it emits `:rf.error/resource-route-plan` with `:plan-cause :prefetch`
  and no `:nav-token`, and dispatches NO partial ensures. The one summary
  `:rf.resource/route-plan` trace carries `:plan-cause :prefetch`. Per Spec 016
  §Route-plan prefetch — warm-mode."
  [route {:keys [app-db branch branch-error]}]
  (let [route-id (:id route)
        cause    [:route-prefetch route-id]
        branch   (or branch [{:route-id   route-id
                              :route-meta {:resources (:resources route)}}])
        ;; a warm-mode planning error owns no route state — strip the nav-token
        ;; slot the activation builders stamp and mark the warm cause.
        warm-err (fn [err] (-> err (dissoc :nav-token) (assoc :plan-cause :prefetch)))
        {:keys [ordered plan-error]}
        (cond
          branch-error
          (let [err (warm-err (branch-plan-error route-id nil branch-error))]
            (trace/emit-error! :rf.error/resource-route-plan err)
            {:plan-error err})
          :else
          (try
            (let [occs      (materialize-occurrences branch route {} app-db)
                  collapsed (collapse-and-order occs)]
              (if-let [cyclic (:cycle collapsed)]
                (let [err (warm-err (collapse-cycle-error route-id nil cyclic))]
                  (trace/emit-error! :rf.error/resource-route-plan err)
                  {:plan-error err})
                collapsed))
            (catch #?(:clj Throwable :cljs :default) ex
              (let [err (warm-err (plan-error route-id nil
                                              (:resource-id (ex-data ex)) ex))]
                (trace/emit-error! :rf.error/resource-route-plan err)
                {:plan-error err}))))
        ;; WARM: ownerless ensure per unique identity — no owner, no blocking,
        ;; no keep-previous?. `:blocking?` on a contributor is inert here.
        req-fx (mapv (fn [{:keys [resource scope cparams]}]
                       [:dispatch [:rf.resource/ensure
                                   {:resource resource :scope scope
                                    :params   cparams  :cause  cause}]])
                     ordered)]
    (trace/emit! :rf.event :rf.resource/route-plan
                 (cond-> {:route-id   route-id
                          :plan-cause :prefetch
                          :branch     (mapv :route-id branch)
                          :ensured    (count ordered)}
                   plan-error (assoc :plan-error true)))
    {:fx (vec req-fx) :warmed (count ordered) :plan-error plan-error}))

(defn on-route-prefetch-fx
  "The `:routing/on-route-prefetch` hook body routing's `:rf.route/prefetch`
  handler consults. `entry` carries `{:route-id :params :query :fragment :branch
  :branch-error :app-db}` (the resolved destination + the effective parent-to-
  leaf branch routing walked from the target's `:parent` links). Returns
  `{:fx [...] :warmed <n> :plan-error err?}`, or nil when there is NOTHING to
  warm — no branch contributor declares `:resources` and branch resolution did
  not fail. No-op on an app that never loads routing (the hook simply sits
  unread). Per Spec 016 §Route-plan prefetch — warm-mode."
  [{:keys [route-id params query fragment app-db branch branch-error]}]
  (let [branch (or branch [{:route-id route-id :route-meta nil}])]
    (when (or branch-error
              (some (fn [c] (seq (:resources (:route-meta c)))) branch))
      (route-resource-warm-plan
        {:id route-id :params params :query query :fragment fragment}
        {:app-db app-db :branch branch :branch-error branch-error}))))

(defn install-routing-integration!
  "Publish the LATE-BOUND routing integrations: the
  `:routing/extra-route-keys` accepted-key extension (so routing accepts
  the `:resources` route-metadata key), the `:routing/on-route-entry`
  plan hook (so `commit-navigation` runs the resource ensure/release plan
  on entry), and the `:routing/on-route-prefetch` warm-mode hook (so
  `:rf.route/prefetch` warms the destination's branch resources ownerlessly).
  All are no-op-effect on an app that never loads routing — the hooks simply
  sit unread. Idempotent. Per Spec 016 §Route integration + §Route-plan
  prefetch — warm-mode."
  []
  (late-bind/set-fn! :routing/extra-route-keys
                     (fn extra-route-keys-thunk [] extra-route-keys))
  (late-bind/set-fn! :routing/on-route-entry    on-route-entry-fx)
  (late-bind/set-fn! :routing/on-route-prefetch on-route-prefetch-fx)
  nil)
