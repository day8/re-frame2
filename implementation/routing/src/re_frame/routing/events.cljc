(ns re-frame.routing.events
  "Shared navigation-event helpers for re-frame2 routing.

  Owns:
    - `emit-activation-traces!` — the `:rf.route/activated` /
      `:rf.route/deactivated` lifecycle pair (Spec 012 §Trace events,
      rf2-dn26r);
    - `merge-route-slice` — the slice-publish merge over
      `[:rf.runtime/routing :current]` (encodes the slice-shape
      contract once for both the programmatic-nav and URL-driven paths,
      rf2-g8tzb);
    - `commit-navigation` — the shared successful-commit assembler
      (nav-token alloc + allocated/activation traces + slice publish +
      fire-and-forget `:on-match` dispatch + resource-derived readiness
      projection) used by both nav entry points.

  EP-0037 R1: `:on-match` is FIRE-AND-FORGET — it dispatches (in order,
  run-to-completion) only after a valid plan, never sets the transition,
  and there is no `:loading → :idle` settle event. Route readiness is the
  pure resource projection (`re-frame.routing.readiness`); a blocking
  route resource lands `:idle` / `:error` through the Resources reply
  handlers' reconciliation (Spec 016 §Route integration).

  Internal namespace; the public facade is `re-frame.routing`. Per the
  rf2-2yabr cohesion split: SHARED-EVENT-HELPERS seam."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.routing.classification :as classification]
            [re-frame.routing.readiness :as readiness]
            [re-frame.trace :as trace]))

;; Per Spec 012 §Multi-frame routing: nav-token and pending-nav id
;; counters are per-frame, monotone, and unbounded — see Spec 012
;; §Navigation tokens, step 1. A token need only be unique within the
;; lifetime of any in-flight async continuation (equality against the
;; current slice token is the only operation on it), which a monotone
;; counter satisfies without ever wrapping. Overflow is a non-concern:
;; CLJS f64 (exact to 2^53), JVM `long` (2^63). DO NOT wrap/recycle — a
;; recycled value could collide with a token still carried by a slow
;; in-flight continuation, silently re-validating a stale result.
;;
;; rf2-oosjmh: the two ALLOCATORS (the monotone high-water counters) are
;; HOST-SIDE TRANSIENT state, not runtime-db — they live in
;; `re-frame.routing.nav-counters`'s host cache so an epoch restore (which
;; replaces the runtime-db partition WHOLESALE) cannot rewind them and
;; recycle a token (the invariant above is the whole point of the move).
;;
;; rf2-vcop6y: the minted nav-token / pending-nav-id are RECORDABLE. The
;; handlers stay PURE: they take delivery of a recordable, generator-backed
;; allocation cofx — `:rf.route/nav-allocation {:token :counter}` (commit)
;; or `:rf.route/pending-nav-allocation {:id :counter}` (block) — whose
;; generator mints from the host snapshot at processing-start and whose value
;; the cofx machinery RECORDS onto the causal token (so replay re-presents the
;; SAME id, the replay-determinism fix). The handler writes only the id into
;; runtime-db and emits a `:rf.route/commit-nav-counter` fx carrying the
;; allocation's `:counter` (WRITE — host high-water `max` bump).
;; `commit-navigation` below takes the recordable `:nav-allocation` and
;; assembles the bump fx — the same write-via-fx shape rf2-1hncp2's scroll
;; cache uses.

(defn emit-activation-traces!
  "Per Spec 012 §Trace events: emit `:rf.route/deactivated`
  for the previously-active route id (when leaving one) and
  `:rf.route/activated` for the newly-active route id (when entering a
  different one). Both fire as part of every successful navigation
  commit; same-id navigation (path/query change with no route-id shift)
  emits NEITHER — the route stays active across the transition. Mirrors
  the flow-lifecycle symmetry: the activated/deactivated pair is to
  routes what `:rf.flow/computed` is to flows in giving tools a
  per-transition lifecycle signal independent of the underlying
  `:rf.route/transitioned` event.

  `frame` is the in-flight cascade's carried frame stamp
  (threaded from the nav handler's `:rf.frame/id` cofx via
  `commit-navigation`). Both lifecycle traces stamp it under `:tags
  :frame` so they enter the emitting frame's epoch (epoch-capture buffers
  only frame-tagged traces — `re-frame.epoch.capture` §168-221) and obey
  the frame-level trace-disable gate (`re-frame.trace/emit!` §397-398).
  A nil stamp simply omits
  the tag rather than synthesising `:rf/default`."
  [frame prev-id next-id]
  (when (and prev-id (not= prev-id next-id))
    (trace/emit! :rf.event :rf.route/deactivated
                 (cond-> {:route-id prev-id}
                   frame (assoc :frame frame))))
  (when (and next-id (not= prev-id next-id))
    (trace/emit! :rf.event :rf.route/activated
                 (cond-> {:route-id next-id}
                   frame (assoc :frame frame)))))

;; Per Spec 012 §The route slice and Spec-Schemas §`:rf/runtime-db` the
;; published slice carries exactly `{:route-id :params :query :fragment
;; :transition :error :nav-token}` under `[:rf.runtime/routing
;; :current]`. (`:route-id` is the self-describing slice key; the
;; consumer-facing sub-id stays `:rf.route/id`, rf2-3a5nk7.) Both nav entry points (programmatic
;; `:rf.route/navigate` and URL-driven `:rf.route/transitioned` /
;; `:rf.route/handle-url-change`) write the same merge shape after
;; allocating a nav-token. This helper encodes the slice-shape contract
;; in ONE place so the two writers and the layer-1 read
;; (`re-frame.routing.subs/route-sub-fn`) stay symmetric. `:error` starts nil
;; here; `commit-navigation` then projects readiness (`:transition` / `:error`)
;; from the resource plan via `readiness/project-at-commit`.
;;
;; The merge targets `:current`, not the routing root, so sibling state is
;; untouched. Siblings include `:pending-navigation` and, when the resources
;; artefact is active, resource-blocking bookkeeping. Allocator counters and
;; scroll positions are host-side transient caches rather than runtime-db
;; siblings.
(defn merge-route-slice
  "Pure slice-publish: merges the new slice fields over the existing
  `:current` map at `[:rf.runtime/routing :current]`. Returns the
  updated db.

  `slice` is a map of `{:route-id :params :query :fragment :transition
  :nav-token}`. `:error` is forced to `nil` here; `commit-navigation`
  overwrites `:transition` / `:error` with the resource-derived readiness
  projection (`readiness/project-at-commit`) after building the plan."
  [db {:keys [route-id params query fragment transition nav-token]}]
  (update-in db [:rf.runtime/routing :current] merge
             {:route-id   route-id
              :params     params
              :query      query
              :fragment   fragment
              :transition transition
              :error      nil
              :nav-token  nav-token}))

;; EP-0037 R2 §Effective parent-chain resource plans: a full activation plans
;; the composed parent-to-leaf branch. Routing owns the `:parent` walk (it owns
;; the registry + the `:parent` semantics) and hands the resolved contributor
;; metas to the late-bound `:routing/on-route-entry` plan; the Resources
;; artefact composes their `:resources`. Branch resolution is FAIL-LOUD — an
;; unregistered `:parent` or a `:parent` cycle aborts the plan (a committed
;; failed activation), never a silently-truncated branch. This is the PLANNING
;; walk; `re-frame.routing.subs/chain-from-meta` (the display sub) swallows
;; cycles defensively and is not a substitute here.
(defn resolve-branch
  "Fail-loud parent-to-leaf branch resolution for `leaf-id`. Returns
  `{:branch [{:route-id :route-meta} …]}` parent-most-first on success, or
  `{:branch-error {:kind :unknown-parent|:parent-cycle :route-id* … :chain …}}`
  when a `:parent` names an unregistered route or the chain cycles. The leaf's
  own route-meta may legitimately be nil (e.g. `:rf.route/not-found`) — that is
  a single-segment branch with no resources, not an unknown-parent error; only a
  followed `:parent` that resolves to no registration is an error."
  [leaf-id]
  (loop [cur leaf-id, acc (list), seen #{}, first? true]
    (if (contains? seen cur)
      {:branch-error {:kind :parent-cycle :route-id* cur
                      :chain (vec (reverse (conj acc cur)))}}
      (let [meta (registrar/lookup :route cur)]
        (if (and (nil? meta) (not first?))
          {:branch-error {:kind :unknown-parent :route-id* cur}}
          (let [acc'   (conj acc {:route-id cur :route-meta meta})
                parent (:parent meta)]
            (if parent
              (recur parent acc' (conj seen cur) false)
              {:branch (vec acc')})))))))

;; Per Spec 012 §Navigation is an event / §URL changes are events: a
;; successful navigation commit is identical across the two entry points
;; (programmatic `:rf.route/navigate` and URL-driven `:rf.route/
;; transitioned` / `:rf.route/handle-url-change`) once the target slice
;; fields have been resolved. Both:
;;   1. allocate a fresh per-frame nav-token (the cascade-begin marker)
;;      from the injected host-side counter snapshot — PURE: read the
;;      next id, publish it, and emit the high-water bump as an fx
;;      (rf2-oosjmh);
;;   2. emit `:rf.route.nav-token/allocated`, then `emit-activation-
;;      traces!` — IN THAT ORDER so trace consumers see
;;      {allocated → deactivated? → activated?} (rf2-dn26r);
;;   3. publish the seven-key slice via `merge-route-slice`;
;;   4. assemble the fx vector: the nav-counter bump (`:rf.route/commit-
;;      nav-counter`) → capture-scroll (the leaving route's position) →
;;      [push-fx, when the programmatic path must drive the browser URL]
;;      → the fire-and-forget `:on-match` dispatches (ONLY on a valid plan —
;;      a planning failure dispatches none, Spec 012 §Failed activation) →
;;      the resource ensure/release fx → the scroll fx. There is NO settle
;;      event: readiness is the resource projection.
;; The URL-driven path passes no `push-fx` (the browser URL already
;; changed); the programmatic path passes `[:rf.nav/push-url ...]` /
;; `[:rf.nav/replace-url ...]`. Holding the commit shape in ONE place
;; keeps the trace ordering, the slice contract, and the fx assembly
;; from drifting between the two callers.
(defn commit-navigation
  "Pure navigation-commit assembler shared by the programmatic-nav and
  URL-driven paths. `rdb` is the pre-commit runtime-db value. `slice`
  carries `{:route-id :params :query :fragment :transition}` (the
  published fields minus `:nav-token`, which rides the recordable
  allocation). `on-match-vec` is
  the resolved `:on-match` event vector (possibly empty). `capture-fx` /
  `scroll-fx` are the pre-built fx entries (or nil) and `push-fx` is the
  optional history-mutation fx entry (nil on the URL-driven path).
  `nav-allocation` is the RECORDABLE allocation `{:token \"nav-N\"
  :counter N}` delivered by the generator-backed `:rf.route/nav-allocation`
  cofx (rf2-vcop6y) — the nav-token is published from `:token` (recorded so
  replay re-presents the same token) and the host high-water bump
  (`:counter`) rides a `:rf.route/commit-nav-counter` fx.

  `app-db` is passed unchanged to the optional `:routing/on-route-entry`
  hook. A resource scope declared with `{:from-db <id>}` can therefore resolve
  against the same causal app-db value before resource work is planned;
  routing itself does not inspect app-db.

  rf2-dbmj6x — `frame` is the in-flight cascade's carried frame stamp (the
  nav handler's `:rf.frame/id` cofx; both nav entry points already
  validate it via `frame/require-frame-stamp!` and thread it in). It is
  stamped onto the `:rf.route.nav-token/allocated` trace and passed to
  `emit-activation-traces!` so the lifecycle pair carries `:frame` too.
  Without it those frame-known traces miss epoch capture (which buffers
  only frame-tagged events) and bypass the frame-level trace-disable gate.

  rf2-cqyq2 — `branch-contributors` / `branch-error` are the route plan's
  already-resolved `:parent` walk (`resolve-branch`, called ONCE per navigation
  in `re-frame.routing.resolve/route-plan`), threaded in by the door. Both doors
  build the plan immediately before calling this, so the value is in hand; this
  hop used to re-walk the chain itself, which is how the plan came to REPORT one
  branch (the display walk) and EXECUTE another (this one). Reading it off the
  plan makes the reported branch and the composed branch the same value by
  construction, and costs one walk fewer per navigation.

  Returns the effects map `{:rf.db/runtime :fx}`."
  [rdb {:keys [route-id params query fragment transition]} on-match-vec
   {:keys [prev-id prev-nav-token capture-fx scroll-fx push-fx nav-allocation app-db frame
           branch-contributors branch-error]}]
  (let [;; rf2-vcop6y: the nav-token rides the RECORDABLE `:rf.route/nav-allocation`
        ;; cofx — `:token` is published into the slice (recorded + replay-stable),
        ;; `:counter` advances the host high-water via the bump fx below.
        {token :token counter :counter} nav-allocation
        committed (merge-route-slice rdb {:route-id   route-id
                                          :params     params
                                          :query      query
                                          :fragment   fragment
                                          :transition transition
                                          :nav-token  token})
        ;; The optional Resources artefact publishes the late-bound
        ;; `:routing/on-route-entry` hook and returns
        ;; `{:fx [...] :blocking {<key-id> <scoped-key>} :identities {…}
        ;; :plan-error err?}`. The
        ;; ensure dispatches + prior-owner release are spliced into the commit
        ;; fx; the blocking map is written into the nav-token's blocking slot
        ;; ATOMICALLY with the commit (so it is present before any ensure
        ;; reply can drain it, keeping the route :loading until blocking
        ;; resources settle — Spec 016 §Route integration); a planning error
        ;; (a fail-closed params/scope throw) is recorded on the slice's
        ;; `:error`, visible to the `:rf/route` sub + Xray. No-op when no
        ;; Resources artefact / no `:resources` route metadata.
        route-meta (registrar/lookup :route route-id)
        ;; EP-0037 R2: read the SUPERSEDED nav-token's owned identities — the
        ;; plan diff's previous membership (`[:rf.runtime/routing :resource-plan
        ;; <token>]`, a resources-written sibling of `:resource-blocking`; both
        ;; are `{<key-id> <scoped-key>}` maps, byte-exact so a vector-params and
        ;; a list-params identity cannot collapse into one — rf2-btdl1).
        ;; Routing owns the `:parent` walk (`resolve-branch`, run once in
        ;; `route-plan` and threaded in as `branch-contributors` /
        ;; `branch-error`); the Resources plan composes + diffs.
        prev-identities (get-in rdb [:rf.runtime/routing :resource-plan prev-nav-token])
        plan       (when-let [on-entry (late-bind/get-fn :routing/on-route-entry)]
                     (on-entry {:route-meta      route-meta
                                :route-id        route-id
                                :params          params
                                :query           query
                                :fragment        fragment
                                :nav-token       token
                                :prev-id         prev-id
                                :prev-nav-token  prev-nav-token
                                :ctx             {}
                                ;; EP-0037 R2 branch composition + plan diff.
                                ;; The hook's documented key names; the values
                                ;; are the route plan's already-resolved walk.
                                :branch          branch-contributors
                                :branch-error    branch-error
                                :prev-identities prev-identities
                                ;; Preserve the handler's causal app-db input for
                                ;; any `{:from-db …}` resource-scope resolver.
                                :app-db          app-db
                                ;; EP-0037 R1: the PRE-COMMIT runtime-db, so the
                                ;; plan can read the Spec 016 resource facts AT
                                ;; COMMIT — a blocking requirement that already
                                ;; has usable data is not recorded as blocking,
                                ;; and the seed below projects `:idle` with no
                                ;; transient `:loading`. Routing does not
                                ;; interpret it; it only threads it.
                                :runtime-db      rdb}))
        ;; EP-0037 R1: route readiness is the PURE resource projection over
        ;; the (leaf-only, until R2) plan — NEVER driven by `:on-match`. Seed
        ;; `:transition` / `:error` from the freshly-built plan through the one
        ;; projector (`readiness/project-at-commit`): a planning failure →
        ;; `:error`, a pending blocking first load → `:loading`, otherwise
        ;; `:idle`. A blocking route resource additionally records its scoped
        ;; keys under the nav-token so the Resources reply handlers reconcile
        ;; `:loading` → `:idle` / `:error` (its SSR wait point) as each settles
        ;; through the same table. Per Spec 012 §Route readiness is a resource
        ;; projection + Spec 016 §Route integration.
        {r-transition :transition r-error :error} (readiness/project-at-commit plan)
        committed  (-> committed
                       (assoc-in [:rf.runtime/routing :current :transition] r-transition)
                       (assoc-in [:rf.runtime/routing :current :error] r-error)
                       (cond-> (seq (:blocking plan))
                         (assoc-in [:rf.runtime/routing :resource-blocking token]
                                   (:blocking plan)))
                       ;; EP-0037 R2: record the plan's full owned identity map
                       ;; under the nav-token so the NEXT full activation diffs
                       ;; kept/added/removed for attach-before-release handoff.
                       (cond-> (seq (:identities plan))
                         (assoc-in [:rf.runtime/routing :resource-plan token]
                                   (:identities plan))))
        ;; Lower the activating route's projection-relative `:sensitive` /
        ;; `:large` classification into the
        ;; per-frame elision registry, RE-ROOTED under `[:rf.runtime/routing
        ;; :current …]`, ATOMICALLY with the slice publish (the same runtime-db
        ;; partition). Routes are a SINGLETON current-route, so this REPLACES the
        ;; prior route's `:source :route` entries — a route change drops the
        ;; leaving route's classification, and a route that declares none (incl.
        ;; `:rf.route/not-found`, whose `route-meta` may be nil) clears the
        ;; route-sourced entries. The declared query/params paths redact those
        ;; projections at egress for as long as the route is active;
        ;; frame teardown drops the whole runtime-db elision slot with the frame.
        committed (classification/lower-for-route committed route-id route-meta)]
    ;; rf2-dbmj6x — stamp the carried `:frame` so the nav-token-allocated
    ;; trace enters epoch capture + obeys the frame trace-disable gate
    ;; (the lifecycle pair below carries it via `emit-activation-traces!`).
    (trace/emit! :rf.event :rf.route.nav-token/allocated
                 (cond-> {:route-id route-id :nav-token token}
                   frame (assoc :frame frame)))
    (emit-activation-traces! frame prev-id route-id)
    ;; EP-0001 (rf2-vzld77): the route slice is durable framework runtime-db
    ;; state, so `rdb` here is the RUNTIME-DB value and the commit returns
    ;; `:rf.db/runtime`, not `:db`.
    {:rf.db/runtime committed
     :fx (vec (concat ;; rf2-oosjmh: persist the nav-token high-water mark
                      ;; into the host-side counter cache (WRITE half of the
                      ;; pure seam). FIRST so the bump lands before any
                      ;; on-match continuation reads the snapshot.
                      [[:rf.route/commit-nav-counter
                        {:counter-key :nav-token-counter :value counter}]]
                      (when capture-fx [capture-fx])
                      (when push-fx    [push-fx])
                      ;; EP-0037 R1: `:on-match` is FIRE-AND-FORGET and runs
                      ;; ONLY after a valid plan — a committed planning-failure
                      ;; target dispatches NONE of its events (Spec 012 §Failed
                      ;; activation / §Per-route data loading). Dispatch order +
                      ;; run-to-completion; it never drives readiness and there
                      ;; is no settle event.
                      (when-not (:plan-error plan)
                        (mapv (fn [ev] [:dispatch ev]) on-match-vec))
                      ;; rf2-vdyrls: the resource ensure dispatches + prior-
                      ;; owner release (Spec 016 §Route integration). Route
                      ;; readiness reconciles to :idle / :error through the
                      ;; Resources reply handlers as blocking resources settle
                      ;; — no routing-side settle event.
                      (:fx plan)
                      (when scroll-fx [scroll-fx])))}))
