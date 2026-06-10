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
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.resources.registry :as registry]
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

(defn drain-blocking
  "Pure: a route-owned resource `scoped-key` (with durable `entry`) just
  SETTLED (`:settled` `:success` | `:failure`). Drop it from every
  nav-token blocking slot it belongs to; when a slot empties for the
  CURRENT nav-token land the route `:transition` at `:idle`; a blocking
  FIRST-load `:failure` flips the current route's `:transition` to
  `:error` and populates `:rf.route/error` (mirroring the `:on-match`
  trap). A settle for a SUPERSEDED nav-token (its slot already released on
  route leave) is a structural no-op. Returns the updated runtime-db. Per
  Spec 016 §Route integration."
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
                ;; blocking FIRST-load failure → route :error
                (and (= :failure settled) (= :error (:status entry)))
                (-> db'
                    (assoc-in (conj (current-path) :transition) :error)
                    (assoc-in (conj (current-path) :error)
                              {:rf.error/id :rf.error/resource-route-blocking
                               :operation   :rf.error/resource-route-blocking
                               :resource-id (:resource/id entry)
                               :nav-token   nav-token
                               :recovery    :no-recovery
                               :error       (:error entry)
                               :reason      "A blocking route resource failed its first load."}))
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

;; ---- plan execution (Spec 016 §Route integration) -------------------------

(defn- when-passes?
  "Evaluate a route-resource entry's `:when` predicate. Absent `:when`
  passes. The predicate is `(fn [route ctx] …)`; truthy admits the
  resource. Per Spec 016 §Route integration (conditional resources use
  `:when`, NOT sentinel nil params)."
  [{when-fn :when} route ctx]
  (if when-fn (boolean (when-fn route ctx)) true))

(defn- resolve-entry-params
  "Resolve a route-resource entry's params via its `:params`
  `(fn [route] …)` (or `{}` when absent). Per Spec 016 §Route
  integration."
  [{params-fn :params} route]
  (if params-fn (or (params-fn route) {}) {}))

(defn- resolve-entry-scope
  "Resolve a route-resource entry's scope via its `:scope`
  `(fn [route ctx] …)` resolver, or nil when the entry declares none (the
  spec policy then governs — `resolve-scope-for-event` with no route
  scope). Per Spec 016 §Scope resolution (precedence tier 2)."
  [{scope-fn :scope} route ctx]
  (when scope-fn (scope-fn route ctx)))

(defn- order-by-after
  "Stable topological order of the admitted entries by their `:after`
  route-local-id dependencies (Spec 016 §Route integration — `:after`
  targets route-local `:id`s, NOT resource ids; the same resource may
  appear more than once with different params/ids). Entries with no
  `:after` keep declaration order; a dependent entry sorts after every
  local id it names. A missing / cyclic `:after` target degrades to
  declaration order (defensive — the waterfall is advisory, not a hard
  gate in this slice). Returns the ordered vector."
  [entries]
  (let [local-id (fn [e] (:id e))
        deps     (fn [e] (set (:after e)))
        ;; iterative stable settle: emit any entry whose unmet deps are
        ;; all already emitted (or unknown), declaration order within a
        ;; pass. Bounded by entry count to defend against a cycle.
        n        (count entries)]
    (loop [remaining (vec entries)
           emitted   []
           seen-ids  #{}
           guard     0]
      (cond
        (empty? remaining) emitted
        (> guard n)        (into emitted remaining) ;; cycle / bad ref — degrade
        :else
        (let [{ready true held false}
              (group-by (fn [e]
                          (every? (fn [d] (or (contains? seen-ids d)
                                              ;; unknown local id — don't block
                                              (not (some #(= d (local-id %)) entries))))
                                  (deps e)))
                        remaining)]
          (if (empty? ready)
            (into emitted remaining) ;; nothing ready (cycle) — degrade
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
  boundary. The error is recorded on the route slice's `:error` by
  `commit-navigation` (visible to the `:rf/route` sub + Xray) and emitted
  as a `:rf.error/resource-route-plan` error trace."
  [route-id nav-token resource-id ex]
  {:rf.error/id :rf.error/resource-route-plan
   :operation   :rf.error/resource-route-plan
   :route-id    route-id
   :resource-id resource-id
   :nav-token   nav-token
   :recovery    :fix-params
   :reason      (str "route " route-id " resource " resource-id
                     " failed planning (params / scope did not resolve) — a "
                     "route/resource planning error, not a silent cache miss. "
                     "Per Spec 016 §Route integration.")
   :cause       (ex-data ex)})

(defn route-resource-plan
  "Build the resource ensure/release plan for a route's `:resources`
  metadata on entry. Pure planner over the route + ctx; the runtime
  effects (ensure dispatches, blocking-slot write, prior-owner release,
  planning-error trace) are returned as an fx vector + the blocking
  scoped-key set. Per Spec 016 §Route integration.

  `route` is the resolved route value
  (`{:id :params :query :fragment …}`), `ctx` the entry context (carries
  e.g. `:current-session-scope`), `entry-ctx` carries `:nav-token`,
  `:prev-id`, `:prev-nav-token`. Returns
  `{:fx [...] :blocking #{<scoped-key> …}}`.

  Per entry: eval `:when` (a not-sentinel-nil guard); resolve scope +
  canonical params (fail-closed — a failure is a PLANNING error, surfaced
  on the route slice, not a silent miss); order by `:after` route-local
  dependencies; emit `:rf.resource/ensure` with owner `[:route route-id
  nav-token]` + cause `[:route-entry route-id nav-token]`; classify
  blocking (recorded under the nav-token so the transition stays
  `:loading`) vs background. `:keep-previous?` is threaded onto the
  ensure payload so the runtime projects the previous key's data while the
  new key first-loads. The previous route's owner token is released."
  [route ctx {:keys [nav-token prev-id prev-nav-token]}]
  (let [route-id  (:id route)
        resources (:resources route)
        owner     [:route route-id nav-token]
        cause     [:route-entry route-id nav-token]
        admitted  (filterv #(when-passes? % route ctx) resources)
        ordered   (order-by-after admitted)
        ;; release the PREVIOUS route's owner (route leave / supersession):
        ;; the resource runtime drops the owner from every entry's
        ;; :active-owners; abort-when-no-owner + stale suppression by
        ;; nav-token handle in-flight work (Spec 016 §Route integration).
        release-fx (when (and prev-id prev-nav-token
                              (not= [prev-id prev-nav-token] [route-id nav-token]))
                     [[:dispatch [:rf.resource/release-owner
                                  {:owner [:route prev-id prev-nav-token]}]]])
        ;; reduce the ordered entries into ensure fx + the blocking set +
        ;; the FIRST planning error (a fail-closed scope/params throw).
        {:keys [fx blocking plan-error]}
        (reduce
          (fn [acc entry]
            (let [resource-id (:resource entry)]
              (try
                (let [spec       (registry/require-resource-spec!
                                   resource-id 'rf.resource/route-entry)
                      raw-params (resolve-entry-params entry route)
                      route-scope (resolve-entry-scope entry route ctx)
                      ;; fail-closed resolution: throws a PLANNING error on
                      ;; a missing / invalid scope or non-conforming params.
                      scope      (registry/resolve-scope-for-event
                                   resource-id spec {:route-scope route-scope}
                                   'rf.resource/route-entry)
                      cparams    (registry/validate+canonicalize-params
                                   resource-id spec raw-params
                                   'rf.resource/route-entry)
                      scoped-key (state/scoped-resource-key scope resource-id cparams)
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
                        (update :blocking conj scoped-key))))
                (catch #?(:clj Throwable :cljs :default) ex
                  ;; params / scope PLANNING failure — surface on the route
                  ;; slice + Xray, never a silent cache miss. FIRST error wins
                  ;; (mirrors the :on-match first-error-wins discipline).
                  (let [err (plan-error route-id nav-token resource-id ex)]
                    (trace/emit-error! :rf.error/resource-route-plan err)
                    (cond-> acc
                      (nil? (:plan-error acc)) (assoc :plan-error err)))))))
          {:fx [] :blocking #{} :plan-error nil}
          ordered)]
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
  :prev-nav-token :ctx}`. Per Spec 016 §Route integration."
  [{:keys [route-meta route-id params query fragment nav-token
           prev-id prev-nav-token ctx]}]
  (when (or (seq (:resources route-meta)) prev-id)
    (let [route {:id        route-id
                 :params    params
                 :query     query
                 :fragment  fragment
                 :resources (:resources route-meta)}]
      (route-resource-plan route (or ctx {})
                           {:nav-token      nav-token
                            :prev-id        prev-id
                            :prev-nav-token prev-nav-token}))))

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
