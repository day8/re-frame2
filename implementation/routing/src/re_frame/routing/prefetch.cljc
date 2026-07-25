(ns re-frame.routing.prefetch
  "`:rf.route/prefetch` event for re-frame2 routing — EP-0037 R3 §Resource-only
  intent prefetch / Spec 012 §Route-plan prefetch — warm-mode intent preload.

  Warm-mode resource-only intent preload: run a named destination's effective
  parent-to-leaf resource plan OWNERLESSLY, WITHOUT navigating. Prefetch:

    - accepts ONLY a closed named `:rf/route-address` (never a raw `:url`); an
      invalid address rejects BEFORE planning with `:rf.error/prefetch-bad-
      address`, dispatching no ensures and leaving current readiness untouched.
      Invalid covers BOTH halves: the structural shape, and whether the named
      destination RESOLVES — an unregistered `:to`, a missing required path
      param, or `:params` / `:query` that fail the route's schemas reject here
      too, so prefetch can only ever warm the same registered, validated
      destination a full activation would (`destination-error` below);
    - resolves the destination + the effective parent-to-leaf branch (routing
      owns the `:parent` walk — `re-frame.routing.events/resolve-branch`) and
      hands it to the late-bound `:routing/on-route-prefetch` warm plan (owned
      by the Resources artefact), which runs each unique requirement through an
      ownerless `ensure` with cause `[:route-prefetch route-id]`;
    - writes NO route state (no `:rf/route` slice, nav-token, URL, history,
      scroll, focus, or pending-navigation) and runs NO `:can-leave` /
      `:can-enter` / `:on-match` — warmup is not activation;
    - is FRAME-SCOPED: the ensure dispatches ride `[:dispatch …]` fx, which
      target the handler's own frame, so a prefetch never warms a sibling
      frame;
    - is a NO-OP beyond its summary trace when Resources is absent or the
      effective plan is empty (prefetch does not make Resources a mandatory
      routing dependency);
    - emits exactly ONE `:rf.route/prefetched` summary trace; its underlying
      resource-plan / ensure traces carry `:plan-cause :prefetch` and no
      nav-token (Spec 016 §Route-plan prefetch — warm-mode). A planning failure
      surfaces the ordinary `:rf.error/resource-route-plan` diagnostic (with
      `:plan-cause :prefetch`), dispatches no partial ensures, and — because
      prefetch owns no route state — alters no route readiness.

  Prefetch is a performance hint, not an authorization boundary: prefetching an
  entry-deniable destination is permitted and means nothing beyond a warmed
  cache. Activation still evaluates and may deny entry.

  Internal namespace; the public facade is `re-frame.routing`. The facade owns
  the `events/reg-event :rf.route/prefetch` call so a `:reload` re-wires it on a
  fresh registrar."
  (:require [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.routing.address :as address]
            [re-frame.routing.events :as routing-events]
            [re-frame.routing.registry :as registry]
            [re-frame.routing.resolve :as resolver]
            [re-frame.trace :as trace]))

(defn- destination-error
  "The named-destination RESOLUTION / VALIDATION gate — run after the structural
  address gate and BEFORE any planning.

  `address/prefetch-address-error` proves the request is a closed
  `:rf/route-address`; it cannot know whether that destination is REGISTERED, or
  whether the supplied `:params` / `:query` satisfy the route's declared schemas.
  While the structural gate was the only gate, prefetch warmed destinations a
  real activation refuses: `{:to :route/does-not-exist}` returned `{}` after a
  SUCCESS summary trace, and a registered `/probe/:id` with `:id` omitted reached
  the warm hook as `{:params {}}` — the WRONG resource identity — where the same
  address through `route-url` raises `:rf.error/no-such-route` /
  `:rf.error/missing-route-param`.

  So the destination resolves through `registry/route-url`, the ONE
  named-destination resolution / validation boundary the programmatic door
  already lowers to (Spec 012 §Bidirectional URL ↔ params): the only surface that
  adjudicates a NAMED address against the route's registration AND its `:params`
  / `:query` schemas. Prefetch owns no URL (no history, no slice), so the built
  URL is discarded — the boundary is consulted for its VERDICT, which is exactly
  the fact prefetch was missing. No new resolver, no new public surface.

  Returns nil when the destination resolves, else the
  `:rf.error/prefetch-bad-address` payload `{:reason <kw> :keys [<offending>]}`
  (plus `:route-id` when the throw named one). `:reason` is the bare NAME of the
  `route-url` error id — `:no-such-route`, `:missing-route-param`,
  `:route-url-validation`, `:route-url-non-edn-value` — disjoint from the
  structural gate's reasons, with `:unresolved-destination` as the total
  fall-through for a throw that carries no `:rf.error/id`.

  The verdict is projected to STRUCTURE ONLY. `route-url`'s ex-data for
  `:rf.error/route-url-validation` embeds `:value` (the caller's raw params /
  query) and `:error` (a Malli explainer that reproduces the failing value
  verbatim) — the URL-carrier class the navigate door has to redact at its own
  emit site (`navigate/redact-route-error-tags`, rf2-zsm03). A trace tag is an
  egress surface the route's `:sensitive` classification cannot reach, so this
  carries the offending KEY and the route id and never a value."
  [address]
  (try
    (registry/route-url address)
    nil
    (catch #?(:clj Throwable :cljs :default) ex
      (let [{:keys [route-id param slot] :as data} (ex-data ex)]
        (cond-> {:reason (if-let [id (:rf.error/id data)]
                           (keyword (name id))
                           :unresolved-destination)
                 :keys   (cond
                           param [param]
                           slot  [slot]
                           :else [:to])}
          route-id (assoc :route-id route-id))))))

(defn prefetch-handler
  "`:rf.route/prefetch` event handler. Registered by the facade so a `:reload`
  re-wires it on a fresh registrar.

  Per Spec 012 §Route-plan prefetch the event carries ONE flat named
  `:rf/route-address`. The handler:
    1. requires the carried frame stamp (`:rf.frame/id`) — a cascade-event
       invariant, so the summary trace lands in the emitting frame's epoch;
    2. rejects a request that is not a closed `:rf/route-address`, or whose
       destination does not RESOLVE against the route registry and its schemas,
       with `:rf.error/prefetch-bad-address` BEFORE any planning (no ensures, no
       summary trace, current readiness untouched);
    3. resolves the destination through the ONE `ResolvedTarget` seam every
       navigation door lowers to (`re-frame.routing.resolve/resolved-target`, so
       the warm plan is keyed by the identity the activation will commit — the
       route's `:query-defaults` included) plus the effective parent-to-leaf
       branch, then consults the late-bound `:routing/on-route-prefetch` warm
       plan;
    4. emits one `:rf.route/prefetched` summary trace and splices the warm
       plan's ownerless ensure dispatches into the returned fx. It writes NO
       runtime-db — prefetch owns no route state.

  `app-db` (the `:db` coeffect) is threaded to the warm plan so a `{:from-db
  <id>}` route-resource `:scope` resolves against the current db, exactly as it
  does for a navigation's `:routing/on-route-entry`."
  [{frame  :rf.frame/id
    app-db :db}
   [_ request]]
  (let [frame (frame/require-frame-stamp!
                frame :rf.route/prefetch
                {:where 'rf.route/prefetch-handler})]
    ;; TWO gates, in this order, both BEFORE planning: the STRUCTURAL address
    ;; gate (is this a closed `:rf/route-address`?) and then the DESTINATION
    ;; gate (does that address resolve to a registered, schema-valid
    ;; destination?). `or` short-circuits, so a malformed request never reaches
    ;; the registry and the structural `:reason` still wins — Spec 012 §Route-plan
    ;; prefetch's "an invalid address rejects BEFORE planning".
    (if-let [bad (or (address/prefetch-address-error request)
                     (destination-error request))]
      ;; Invalid address: reject BEFORE planning. No ensures dispatched, NO
      ;; success summary trace, current route readiness untouched. Distinct
      ;; channel from a resource PLANNING failure (a well-formed address, for a
      ;; destination that DOES resolve, whose resource plan can't be built).
      (do
        (trace/emit-error! :rf.error/prefetch-bad-address
                           (cond-> {:where    :event
                                    :reason   (:reason bad)
                                    :keys     (:keys bad)
                                    :recovery :no-recovery}
                             (:route-id bad) (assoc :route-id (:route-id bad))
                             frame           (assoc :frame frame)))
        {})
      (let [;; The destination lowers to the ONE ResolvedTarget seam every
            ;; navigation door lowers to (`resolver/resolved-target`), so a warm
            ;; plan is built from the SAME facts the activation will commit —
            ;; including the route's declared `:query-defaults`. Prefetch
            ;; resolving the address itself is what made R3's headline capability
            ;; silently inert for a route declaring defaults (rf2-kqxe6.23):
            ;; hovering warmed `{:tab nil}` while the click activated
            ;; `{:tab :overview}`, so the same link produced TWO cache entries —
            ;; the warm one ownerless, GC-eligible and never reused, and the
            ;; click arriving as a fresh `:attempt 1`. No error, no warning; the
            ;; feature simply did nothing.
            {:keys [route-id params query fragment]}
            (resolver/resolved-target {:route-id (:to request)
                                       :params   (:params request {})
                                       :query    (:query request {})
                                       :fragment (:fragment request)})
            ;; Routing owns the `:parent` walk; the Resources warm plan composes
            ;; the contributors. Branch resolution is fail-loud — an unresolved
            ;; / cyclic `:parent` rides down as `:branch-error` and becomes a
            ;; warm-mode planning failure (never a silently-truncated branch).
            {:keys [branch branch-error]} (routing-events/resolve-branch route-id)
            ;; The optional Resources artefact publishes `:routing/on-route-
            ;; prefetch`; it returns `{:fx [ensure-dispatch …] :warmed <n>
            ;; :plan-error err?}` or nil when there is nothing to warm. No-op
            ;; (nil) when no Resources artefact / no branch resources.
            plan (when-let [warm (late-bind/get-fn :routing/on-route-prefetch)]
                   (warm {:route-id     route-id
                          :params       params
                          :query        query
                          :fragment     fragment
                          :branch       branch
                          :branch-error branch-error
                          :app-db       app-db}))]
        ;; The ONE summary trace (Spec 012 §Trace events — :rf.route/prefetched).
        ;; Frame-stamped for epoch capture. NOT an activation trace: no
        ;; activated / nav-token-allocated pair fires for a prefetch.
        (trace/emit! :rf.event :rf.route/prefetched
                     (cond-> {:route-id route-id
                              :warmed   (or (:warmed plan) 0)}
                       (:plan-error plan) (assoc :plan-error true)
                       frame              (assoc :frame frame)))
        ;; The warm plan's ownerless ensure dispatches ride the frame the
        ;; prefetch ran in (a `[:dispatch …]` fx targets the handler's frame).
        ;; No `:rf.db/runtime` — prefetch writes no route state.
        (cond-> {}
          (seq (:fx plan)) (assoc :fx (:fx plan)))))))
