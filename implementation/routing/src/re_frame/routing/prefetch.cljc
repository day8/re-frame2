(ns re-frame.routing.prefetch
  "`:rf.route/prefetch` event for re-frame2 routing — EP-0037 R3 §Resource-only
  intent prefetch / Spec 012 §Route-plan prefetch — warm-mode intent preload.

  Warm-mode resource-only intent preload: run a named destination's effective
  parent-to-leaf resource plan OWNERLESSLY, WITHOUT navigating. Prefetch:

    - accepts ONLY a closed named `:rf/route-address` (never a raw `:url`); an
      invalid address rejects BEFORE planning with `:rf.error/prefetch-bad-
      address`, dispatching no ensures and leaving current readiness untouched;
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
            [re-frame.trace :as trace]))

(defn prefetch-handler
  "`:rf.route/prefetch` event handler. Registered by the facade so a `:reload`
  re-wires it on a fresh registrar.

  Per Spec 012 §Route-plan prefetch the event carries ONE flat named
  `:rf/route-address`. The handler:
    1. requires the carried frame stamp (`:rf.frame/id`) — a cascade-event
       invariant, so the summary trace lands in the emitting frame's epoch;
    2. rejects a request that is not a closed `:rf/route-address` with
       `:rf.error/prefetch-bad-address` BEFORE any planning (no ensures, current
       readiness untouched);
    3. resolves the destination route-id + params and the effective parent-to-
       leaf branch, then consults the late-bound `:routing/on-route-prefetch`
       warm plan;
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
    (if-let [bad (address/prefetch-address-error request)]
      ;; Invalid address: reject BEFORE planning. No ensures dispatched, current
      ;; route readiness untouched. Distinct channel from a resource PLANNING
      ;; failure (a well-formed address whose plan can't be built).
      (do
        (trace/emit-error! :rf.error/prefetch-bad-address
                           (cond-> {:where    :event
                                    :reason   (:reason bad)
                                    :keys     (:keys bad)
                                    :recovery :no-recovery}
                             frame (assoc :frame frame)))
        {})
      (let [route-id (:to request)
            params   (:params request {})
            query    (:query request {})
            fragment (:fragment request)
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
