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
       - classifies blocking vs background — a blocking resource records
         its scoped key under `[:rf.runtime/routing :resource-blocking
         nav-token]` so the route transition stays `:loading` (and SSR
         has a wait point) until it settles, while non-blocking ones
         fetch in the background;
       - releases the PREVIOUS route's owner token (route leave /
         supersession) so its resources become GC-eligible when no other
         owner needs them;
       - surfaces a params-schema failure as a route/resource PLANNING
         error (`:rf.error/resource-route-plan` in the route slice +
         Xray), never a silent cache miss.

  Blocking is drained by the resource reply handlers
  (`re-frame.resources.events`): on a blocking resource settling they
  drop its scoped key from the nav-token's blocking set and, when the set
  empties, land the route transition at `:idle`; a blocking FIRST-load
  failure flips the route transition to `:error` and populates
  `:rf.route/error` (mirroring the `:on-match` error trap). Stale
  navigations are suppressed by nav-token: a settle for a superseded
  nav-token is a no-op (the blocking slot for that token is gone).

  Per Spec 016 §Route integration."
  (:require [re-frame.error :as error]
            [re-frame.late-bind :as late-bind]
            [re-frame.resources.registry :as registry]
            [re-frame.resources.scope-registry :as scope-registry]
            [re-frame.resources.state :as state]
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
;; under `[:rf.runtime/routing …]`), so:
;;   - the route transition stays `:loading` while the set is non-empty
;;     (the `:routing/route-blocking?` predicate routing's settle handler
;;     consults);
;;   - a newer navigation's nav-token has its OWN slot, so a stale
;;     blocking drain (old nav-token) is structurally a no-op (its slot is
;;     released on leave);
;;   - the slot is per-nav-token EDN (scoped keys), not host state, so it
;;     rides epoch restore / SSR coherently with the rest of the routing
;;     runtime-db.

(def routing-key
  "The routing-runtime subtree key (`:rf.runtime/routing`). The blocking
  slot is a sibling of `:current` here. Mirrors the literal routing uses;
  duplicated (not imported) so resources never statically `:require`s
  routing."
  :rf.runtime/routing)

(defn blocking-path
  "Runtime-db-relative path to the blocking scoped-key set for a
  nav-token: `[:rf.runtime/routing :resource-blocking <nav-token>]`. Per
  Spec 016 §Route integration."
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
  (`[:rf.runtime/routing :resource-blocking]`), keyed by nav-token. Used to
  drop a superseded token's slot wholesale (rf2-l2gofj)."
  []
  [routing-key :resource-blocking])

(defn clear-blocking-slot
  "Pure: dissoc the ENTIRE blocking slot for a superseded `nav-token`
  (`[:rf.runtime/routing :resource-blocking <nav-token>]`). Returns the
  updated runtime-db (a no-op when no slot exists). Per Spec 016 §Route
  integration (rf2-l2gofj).

  WHY this is needed even though `drain-blocking` already clears stale-token
  slots: drain is REPLY-driven — it only fires when a route-owned resource
  actually settles AND that resource still lists the old nav-token among its
  `:active-owners`. On supersession the prior owner is RELEASED from every
  entry's `:active-owners` immediately, so a subsequent reply no longer
  carries the old token and `drain-blocking` never visits the stale slot.
  Worse, a resource that is aborted / never replies (orphaned in-flight) on
  supersession leaves its old-token blocking entry permanently. Clearing the
  whole slot deterministically at the route transition (when the prior owner
  is released) guarantees old-token blocking state cannot accumulate or be
  mistaken for live blocking. The CURRENT nav-token's slot is never the
  target here — only a superseded token's."
  [runtime-db nav-token]
  (if (contains? (get-in runtime-db (blocking-slots-map-path)) nav-token)
    (update-in runtime-db (blocking-slots-map-path) dissoc nav-token)
    runtime-db))

;; ---- blocking drain (Spec 016 §Route integration / §SSR wait point) -------
;;
;; The resource reply handlers call these PURE helpers when a route-owned
;; resource settles, so all resource→route blocking logic stays inside the
;; Resources artefact (it already writes `:rf.db/runtime`). Routing only
;; provides the entry hook + the `:routing/route-blocking?` settle
;; predicate; it never reaches into resources.

(defn route-blocking?
  "True iff ANY route resource is still blocking the route transition for
  the route slice's CURRENT nav-token. The `:routing/route-blocking?`
  predicate routing's `settle-transition` consults so a blocking route's
  transition stays `:loading` past the `:on-match` drain. Reads only the
  current nav-token's blocking slot — a superseded token's stale slot
  never holds the live transition. Per Spec 016 §Route integration."
  [runtime-db]
  (let [nav-token (get-in runtime-db (conj (current-path) :nav-token))]
    (boolean (seq (get-in runtime-db (blocking-path nav-token))))))

(defn- owner-nav-tokens
  "The set of route nav-tokens that own `scoped-key` per the entry's
  `:active-owners` (a route owner is `[:route route-id nav-token]`). Used
  to find which blocking slot(s) a settled resource belongs to without a
  reverse index — the per-entry owner set is small and authoritative."
  [entry]
  (into #{}
        (comp (filter #(and (vector? %) (= :route (first %))))
              (map #(nth % 2)))
        (:active-owners entry)))

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

(defn drain-blocking
  "A route-owned resource `scoped-key` (with durable `entry`) just SETTLED
  (`:settled` `:success` | `:failure`). Drop it from every nav-token
  blocking slot it belongs to; when a slot empties for the CURRENT
  nav-token land the route `:transition` at `:idle`; a blocking FIRST-load
  `:failure` flips the current route's `:transition` to `:error` and
  populates `:rf.route/error` (mirroring the `:on-match` trap). A settle
  for a SUPERSEDED nav-token (its slot already released on route leave) is
  a structural no-op. Returns the updated runtime-db.

  Pure DATA transform over runtime-db, PLUS one out-of-band observability
  side effect: a blocking FIRST-load failure additionally emits the
  `:rf.error/resource-route-blocking` error trace (rf2-u5aj91) so a failed
  required server-state read is visible on the trace/error stream and in
  Xray, not only in route state. Same emit-inside-planner discipline
  `route-resource-plan` uses for `:rf.error/resource-route-plan`. Per Spec
  016 §Route integration."
  [runtime-db scoped-key entry settled]
  (let [tokens       (owner-nav-tokens entry)
        current      (get-in runtime-db (conj (current-path) :nav-token))]
    (reduce
      (fn [db nav-token]
        (let [slot  (get-in db (blocking-path nav-token))]
          (if-not (contains? slot scoped-key)
            db
            (let [slot' (disj slot scoped-key)
                  db'   (assoc-in db (blocking-path nav-token) slot')]
              (cond
                ;; only the live transition is affected; a stale token's
                ;; drain just clears its (released) slot.
                (not= nav-token current) db'
                ;; blocking FIRST-load failure → route :error + error trace
                (and (= :failure settled) (= :error (:status entry)))
                (let [err (route-blocking-error entry nav-token)]
                  ;; rf2-u5aj91: emit the error trace alongside the route-slice
                  ;; write — the route slice carries the structured :error AND
                  ;; the trace/error stream sees `:rf.error/resource-route-
                  ;; blocking` (tags conform to ResourceRouteBlockingTags).
                  (trace/emit-error! :rf.error/resource-route-blocking
                                     (dissoc err :rf.error/id :operation))
                  (-> db'
                      (assoc-in (conj (current-path) :transition) :error)
                      (assoc-in (conj (current-path) :error) err)))
                ;; last blocking resource settled (success, or a
                ;; refresh-failure that kept data) → land :idle, but only
                ;; while still mid-load (don't clobber an :error a sibling
                ;; blocking failure already recorded).
                (and (empty? slot')
                     (= :loading (get-in db' (conj (current-path) :transition))))
                (assoc-in db' (conj (current-path) :transition) :idle)
                :else db')))))
      runtime-db
      tokens)))

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
    {:rf.error/id :rf.error/resource-route-plan
     :route-id    route-id
     :resource-id resource-id
     :nav-token   nav-token
     :recovery    (get data :recovery :fix-params)
     :reason      (or (:reason data)
                      (str "route " route-id " resource " resource-id
                           " failed planning (params / scope did not resolve) — a "
                           "route/resource planning error, not a silent cache miss. "
                           "Per Spec 016 §Route integration."))
     :cause       data}))

(defn route-resource-plan
  "Build the resource ensure/release plan for a route's `:resources`
  metadata on entry. Pure planner over the route + ctx; the runtime
  effects (ensure dispatches, blocking-slot write, prior-owner release,
  planning-error trace) are returned as an fx vector + the blocking
  scoped-key set. Per Spec 016 §Route integration.

  `route` is the resolved route value
  (`{:id :params :query :fragment …}`), `ctx` the reserved entry context
  (the seam routing threads through `:routing/on-route-entry`; currently `{}`
  — a `:scope` / `:when` resolver receives it as its trailing argument).
  `entry-ctx` carries `:nav-token`, `:prev-id`, `:prev-nav-token`, and
  `:app-db` (the route-entry app-db value — see the `:app-db` paragraph
  below). Returns `{:fx [...] :blocking #{<scoped-key> …} :plan-error err?}`.

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
  [route ctx {:keys [nav-token prev-id prev-nav-token app-db]}]
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
        resources (:resources route)
        owner     [:route route-id nav-token]
        cause     [:route-entry route-id nav-token]
        ;; release the PREVIOUS route's owner (route leave / supersession):
        ;; the resource runtime drops the owner from every entry's
        ;; :active-owners; abort-when-no-owner + stale suppression by
        ;; nav-token handle in-flight work (Spec 016 §Route integration).
        release-fx (when (and prev-id prev-nav-token
                              (not= [prev-id prev-nav-token] [route-id nav-token]))
                     [[:dispatch [:rf.resource/release-owner
                                  {:owner [:route prev-id prev-nav-token]}]]])
        ;; `:after` is validated + ordered against the FULL declared set
        ;; (route-local ids of EVERY entry, not just the `:when`-admitted
        ;; ones) so a missing / cyclic target is a PLANNING error regardless
        ;; of `:when` (rf2-xeb4l1). The order-by-after throw is a plan-level
        ;; failure (no single resource id), caught here.
        ordered-or-err
        (try {:ordered (order-by-after resources)}
             (catch #?(:clj Throwable :cljs :default) ex
               (let [err (plan-error route-id nav-token nil ex)]
                 (trace/emit-error! :rf.error/resource-route-plan err)
                 {:plan-error err})))
        ;; reduce the ordered entries into ensure fx + the blocking set +
        ;; the FIRST planning error (a fail-closed when / scope / params
        ;; throw). A plan-level `:after` error (above) short-circuits the
        ;; reduce — no entry is ensured (FIRST-error-wins, route still
        ;; commits).
        {:keys [fx blocking plan-error]}
        (reduce
          (fn [acc entry]
            (let [resource-id (:resource entry)]
              (try
                ;; `:when` is evaluated HERE (inside the fail-closed boundary)
                ;; so a throwing predicate is a planning error, not an escape
                ;; (rf2-ac71vm). A falsey `:when` gates the resource out — no
                ;; ensure, no sentinel nil params.
                (if-not (when-passes? entry route ctx)
                  acc
                  (let [spec       (registry/require-resource-spec!
                                     resource-id 'rf.resource/route-entry)
                        raw-params (resolve-entry-params entry route)
                        ;; EP-0016 D3 slice 3: a `{:from-db …}` route-resource
                        ;; `:scope` resolves against the route-entry app-db
                        ;; here, BEFORE planning the resource work; a `(fn
                        ;; [route ctx])` resolver evaluates against route+ctx.
                        route-scope (resolve-entry-scope entry route ctx app-db)
                        ;; fail-closed resolution: throws a PLANNING error on
                        ;; a missing / invalid scope or non-conforming params.
                        ;; `:db` carries the route-entry app-db so a `{:from-db
                        ;; …}` SPEC policy (no route `:scope`) also resolves
                        ;; against db at route entry.
                        scope      (registry/resolve-scope-for-event
                                     resource-id spec {:route-scope route-scope :db app-db}
                                     'rf.resource/route-entry)
                        cparams    (registry/validate+canonicalize-params
                                     resource-id spec raw-params
                                     'rf.resource/route-entry)
                        ;; rf2-rplgkw: scope (resolve-scope-for-event →
                        ;; canonicalize-scope) + cparams already canonical.
                        scoped-key (state/scoped-resource-key* scope resource-id cparams)
                        blocking?  (boolean (:blocking? entry))
                        ensure-ev  [:rf.resource/ensure
                                    (cond-> {:resource resource-id
                                             :scope    scope
                                             :params   cparams
                                             :owner    owner
                                             :cause    cause}
                                      (:keep-previous? entry)
                                      (assoc :keep-previous? true))]]
                    (-> acc
                        (update :fx conj [:dispatch ensure-ev])
                        (cond-> blocking?
                          (update :blocking conj scoped-key)))))
                (catch #?(:clj Throwable :cljs :default) ex
                  ;; when / params / scope PLANNING failure — surface on the
                  ;; route slice + Xray, never a silent cache miss. FIRST error
                  ;; wins (mirrors the :on-match first-error-wins discipline).
                  (let [err (plan-error route-id nav-token resource-id ex)]
                    (trace/emit-error! :rf.error/resource-route-plan err)
                    (cond-> acc
                      (nil? (:plan-error acc)) (assoc :plan-error err)))))))
          {:fx [] :blocking #{} :plan-error (:plan-error ordered-or-err)}
          (:ordered ordered-or-err))]
    (trace/emit! :rf.event :rf.resource/route-plan
                 {:route-id route-id :nav-token nav-token
                  :ensured (count fx) :blocking (vec blocking)})
    ;; The blocking set + plan-error ride back to `commit-navigation`, which
    ;; writes the blocking slot under the nav-token + records a plan-error on
    ;; the route slice's `:error`, ATOMICALLY with the commit (so the slot is
    ;; present before any ensure reply can drain it). The fx (prior-owner
    ;; release + ensure dispatches) are spliced into the commit fx.
    {:fx         (vec (concat release-fx fx))
     :blocking   blocking
     :plan-error plan-error}))

(defn on-route-entry-fx
  "The `:routing/on-route-entry` hook body routing's `commit-navigation`
  consults. Returns `{:fx [...] :blocking #{<scoped-key> …} :plan-error
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
  integration / §Resolver references."
  [{:keys [route-meta route-id params query fragment nav-token
           prev-id prev-nav-token ctx app-db]}]
  (when (or (seq (:resources route-meta)) prev-id)
    (let [route {:id        route-id
                 :params    params
                 :query     query
                 :fragment  fragment
                 :resources (:resources route-meta)}]
      (route-resource-plan route ctx
                           {:nav-token      nav-token
                            :prev-id        prev-id
                            :prev-nav-token prev-nav-token
                            :app-db         app-db}))))

(defn install-routing-integration!
  "Publish the LATE-BOUND routing integrations: the
  `:routing/extra-route-keys` accepted-key extension (so routing accepts
  the `:resources` route-metadata key) and the `:routing/on-route-entry`
  plan hook (so `commit-navigation` runs the resource ensure/release plan
  on entry). Both are no-op-effect on an app that never loads routing —
  the hooks simply sit unread. Idempotent. Per Spec 016 §Route
  integration."
  []
  (late-bind/set-fn! :routing/extra-route-keys
                     (fn extra-route-keys-thunk [] extra-route-keys))
  (late-bind/set-fn! :routing/on-route-entry  on-route-entry-fx)
  (late-bind/set-fn! :routing/route-blocking? route-blocking?)
  nil)
