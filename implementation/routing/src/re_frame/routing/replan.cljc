(ns re-frame.routing.replan
  "`:rf.route/replan-resources` event for re-frame2 routing — Spec 012
  §Replanning the active route's resources / Spec 016 §Route-plan replan —
  same-token reconciliation (rf2-y8jjk).

  Rerun the ACTIVE route's effective parent-to-leaf resource plan against the
  CURRENT app-db WITHOUT navigating. The one causal door for an app-db-derived
  identity input (principal, tenant, permission set, locale, impersonation)
  that changed with no route change: a `{:from-db …}` resource subscription
  re-keys reactively when its resolver inputs change, but re-keying is PASSIVE
  — the newly selected scoped key sits `:idle` until some cause ensures it, and
  identical navigation is deliberately a no-op (Spec 012 §Navigation is an
  event rule 3), so before this command nothing public could rerun the standing
  plan. Replan:

    - carries ONE closed payload map, `{:cause <edn>}` — `:cause` is REQUIRED
      and non-nil (the ordinary Spec 016 cause slot, e.g. `[:session-restore]`;
      it rides VERBATIM as the `:cause` of every ensure / adopt the replan
      dispatches). A malformed event — bad arity, a non-map payload, a missing
      or nil `:cause`, an unknown key — or a dispatch with NO active route
      slice is rejected BEFORE any planning through `:rf.error/replan-bad-
      request` (`:reason` one of `:bad-event-arity` / `:not-a-map` /
      `:unknown-key` / `:missing-cause` / `:no-active-route`), returning `{}`
      with the slice untouched. Silently defaulting the one field the command
      exists to carry would defeat it, and a silent no-op on no route is
      exactly the 2am mystery this command exists to remove;
    - reads the CURRENT slice — route id, params, query, fragment, nav-token —
      and preserves every one of them byte-for-byte. NO nav-token is minted and
      no allocation cofx is declared: the standing owner
      `[:route route-id nav-token]` stays the owner;
    - resolves the currently REGISTERED parent-to-leaf branch through routing's
      own walk (`re-frame.routing.events/resolve-branch`, the one
      `commit-navigation` uses) and hands it, with the pre-commit runtime-db
      and the token's recorded plan identities, to the late-bound
      `:routing/on-route-replan` hook (owned by the Resources artefact), which
      reruns the ONE canonical planner in replan mode and reconciles
      ATOMICALLY under the unchanged owner: kept + adoptable identities are
      adopted with no fetch, added / retained-but-unusable identities are
      ensured under the owner with the caller's cause, and the owner is
      released ONLY from the identities the new plan drops (a same-owner SUBSET
      release, ordered after the attach fx);
    - REPLACES the durable `[:rf.runtime/routing :resource-blocking <token>]`
      and `[… :resource-plan <token>]` slots unconditionally — written, or
      removed when the new map is empty, never left holding the prior value
      (the activation commit's conditional write is safe only because a FRESH
      token has no old slot) — and re-projects `:transition` / `:error` on
      `:current` through the one commit-time projector
      (`rf.routing.readiness/project-at-commit`), so a successful replan CLEARS an earlier
      `:rf.error/resource-route-plan` on the slice — which is the repair;
    - FAILS CLOSED: a planning failure is a COMMITTED FAILED REPLAN with the
      same semantics as a committed failed activation — no partial ensures,
      `:rf.error/resource-route-plan` with `:plan-cause :replan` and the
      nav-token PRESENT, the slice `:error` installed, BOTH slots cleared, and
      the standing owner released from ALL its previous identities (the
      Resources artefact's whole-owner release). Deliberately destructive:
      keeping a departed identity's plan alive after a scope change would let
      polling / invalidation settle bytes fetched under the NEW credentials
      into the OLD scope's entry;
    - runs NOTHING else: no `:can-leave` / `:can-enter`, no `:on-match`, no
      history / URL, no scroll, no focus, no pending-navigation, and none of
      the `:rf.route/planned` / activated / nav-token traces — it is not a
      navigation and not a reload. Unchanged usable identities are NOT
      force-refetched: the verb is replan-resources, not reload;
    - is FRAME-SCOPED (the dispatches ride `[:dispatch …]` fx targeting the
      handler's own frame) and a NO-OP `{}` when the Resources artefact is
      absent (hook unbound) — the event ships with routing, the semantics with
      Resources, exactly like `:rf.route/prefetch`.

  Evidence needs no new trace operation: the existing `:rf.resource/route-plan`
  row carries `:plan-cause :replan` plus the caller cause under `:replan-cause`,
  the ensure / adopt dispatches carry the caller cause as their ordinary
  `:cause`, and the `:rf.route/replan-resources` event itself is the ledger
  row.

  Internal namespace; the public facade is `re-frame.routing`. The facade owns
  the `events/reg-event :rf.route/replan-resources` call so a `:reload`
  re-wires it on a fresh registrar."
  (:require [clojure.set :as set]
            [re-frame.frame :as rf.frame]
            [re-frame.identity :as rf.identity]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.routing.events :as rf.routing.events]
            [re-frame.routing.readiness :as rf.routing.readiness]
            [re-frame.trace :as rf.trace]))

(def ^:private request-keys
  "The CLOSED replan payload roster. One key, required."
  #{:cause})

(defn replan-request-error
  "The always-on STRUCTURAL GATE for a `[:rf.route/replan-resources {request}]`
  event — the event-VECTOR shape and the closed payload map in one place, run
  BEFORE the slice is read and BEFORE any planning (Spec 012 §Replanning the
  active route's resources). The event is exactly a TWO-element vector whose
  payload is a MAP over the closed roster `#{:cause}` with a non-nil `:cause`.
  Returns nil when well-formed, else `{:reason <kw> :keys [<offending>]}`
  naming the first violation, which the handler surfaces as
  `:rf.error/replan-bad-request`. Structure is checked before content — an
  unknown key is reported ahead of a missing cause — and offending unknown
  keys ride in the same total canonical order (`rf.identity/canonical-bytes`) the
  navigate and prefetch gates use, so heterogeneous EDN keys never trip a
  `compare`-based sort. Total over the roster. The fifth reason,
  `:no-active-route`, is the handler's own (it needs the slice)."
  [event-vec]
  (let [request (second event-vec)]
    (cond
      (not= 2 (count event-vec))
      {:reason :bad-event-arity :keys []}

      (not (map? request))
      {:reason :not-a-map :keys []}

      (seq (set/difference (set (keys request)) request-keys))
      {:reason :unknown-key
       :keys   (vec (sort-by rf.identity/canonical-bytes
                             (set/difference (set (keys request)) request-keys)))}

      (nil? (:cause request))
      {:reason :missing-cause :keys [:cause]}

      :else nil)))

(defn- replace-slot
  "Pure: REPLACE the per-nav-token slot at `slots-path` for `nav-token` with
  `m` — written when non-empty, REMOVED when empty. Never left holding the
  prior value. The activation commit's `(cond-> (seq m) assoc-in …)` is safe
  only because a fresh token has no old slot; a replan writes under the SAME
  token, so an empty next map must actively clear the previous one (Spec 016
  §Route-plan replan — same-token reconciliation)."
  [rdb slots-path nav-token m]
  (if (seq m)
    (assoc-in rdb (conj slots-path nav-token) m)
    (if (contains? (get-in rdb slots-path) nav-token)
      (update-in rdb slots-path dissoc nav-token)
      rdb)))

(defn replan-handler
  "`:rf.route/replan-resources` event handler. Registered by the facade so a
  `:reload` re-wires it on a fresh registrar.

  The handler:
    1. requires the carried frame stamp (`:rf.frame/id`) — a cascade-event
       invariant, so the diagnostic and the planner's rows land in the
       emitting frame's epoch;
    2. rejects a malformed event / payload, or a dispatch with no active route
       slice, with `:rf.error/replan-bad-request` BEFORE any planning (slice
       untouched, no ensures, `{}`);
    3. reads the current slice, resolves the REGISTERED branch through the one
       planning walk (`resolve-branch`), and consults the late-bound
       `:routing/on-route-replan` hook with the current app-db, the PRE-COMMIT
       runtime-db, and the token's recorded plan identities as the diff's
       previous membership. Hook unbound (no Resources artefact), or nothing to
       replan (no branch resources, no prior plan, no branch error) → `{}`;
    4. on a plan: REPLACES both per-token slots unconditionally, re-projects
       `:transition` / `:error` through `rf.routing.readiness/project-at-commit`, and
       splices the plan's fx (attach effects, then the same-owner subset release
       — or, on a failed plan, the whole-owner release) into the returned fx.

  Cofx: `:db`, `:rf.db/runtime`, `:rf.frame/id` only — no
  `:rf.route/nav-allocation` / `:rf.route/pending-nav-allocation`. It does NOT
  route through `commit-navigation` (which allocates a token and emits the
  activation traces); the slice's route id / params / query / fragment /
  nav-token are preserved byte-for-byte."
  [{frame   :rf.frame/id
    rdb-raw :rf.db/runtime
    app-db  :db}
   [_ request :as event-vec]]
  (let [frame   (rf.frame/require-frame-stamp!
                  frame :rf.route/replan-resources
                  {:where 'rf.route/replan-handler})
        rdb     (or rdb-raw {})
        current (get-in rdb [:rf.runtime/routing :current])
        ;; The event-shape + payload gate first; then the slice gate. `or`
        ;; short-circuits so a malformed request is reported as such even when
        ;; no route is active.
        bad     (or (replan-request-error event-vec)
                    (when (nil? (:route-id current))
                      {:reason :no-active-route :keys []}))]
    (if bad
      ;; Rejected BEFORE planning: no ensures, no planner row, slice untouched.
      ;; Distinct channel from a resource PLANNING failure (a well-formed
      ;; request whose plan cannot be built — `:rf.error/resource-route-plan`
      ;; with `:plan-cause :replan`). Frame-attributed so it lands in the
      ;; emitting frame's epoch.
      (do
        (rf.trace/emit-error! :rf.error/replan-bad-request
                           (cond-> {:where    :event
                                    :reason   (:reason bad)
                                    :keys     (:keys bad)
                                    :recovery :no-recovery}
                             frame (assoc :frame frame)))
        {})
      (let [{:keys [route-id params query fragment nav-token]} current
            route-meta (rf.registrar/lookup :route route-id)
            ;; Routing owns the `:parent` walk — the SAME fail-loud resolution
            ;; `commit-navigation` uses, never a second one. An unresolved /
            ;; cyclic `:parent` rides down as `:branch-error` and becomes a
            ;; committed failed replan.
            {:keys [branch branch-error]} (rf.routing.events/resolve-branch route-id)
            ;; The token's recorded plan identities are the diff's PREVIOUS
            ;; membership (byte-keyed, rf2-btdl1). Absent after a committed
            ;; failed activation — then everything is `added`, which is the
            ;; repair.
            prev-identities (get-in rdb [:rf.runtime/routing :resource-plan nav-token])
            plan (when-let [replan (rf.late-bind/get-fn :routing/on-route-replan)]
                   (replan {:route-meta      route-meta
                            :route-id        route-id
                            :params          params
                            :query           query
                            :fragment        fragment
                            :nav-token       nav-token
                            :ctx             {}
                            :app-db          app-db
                            :runtime-db      rdb
                            :branch          branch
                            :branch-error    branch-error
                            :prev-identities prev-identities
                            :cause           (:cause request)}))]
        (if (nil? plan)
          ;; No Resources artefact, or nothing to replan. Not an error: the
          ;; event ships with routing, the semantics with Resources.
          {}
          (let [{r-transition :transition r-error :error} (rf.routing.readiness/project-at-commit plan)
                rdb' (-> rdb
                         (assoc-in [:rf.runtime/routing :current :transition] r-transition)
                         (assoc-in [:rf.runtime/routing :current :error] r-error)
                         (replace-slot [:rf.runtime/routing :resource-blocking] nav-token (:blocking plan))
                         (replace-slot [:rf.runtime/routing :resource-plan]     nav-token (:identities plan)))]
            ;; EP-0001: the route slice is durable framework runtime-db state —
            ;; the handler returns `:rf.db/runtime`, never `:db`. The plan's fx
            ;; (attach effects, then the release) ride the frame the replan ran
            ;; in (a `[:dispatch …]` fx targets the handler's frame).
            (cond-> {:rf.db/runtime rdb'}
              (seq (:fx plan)) (assoc :fx (:fx plan)))))))))
