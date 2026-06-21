(ns re-frame.routing.events
  "Shared navigation-event helpers + the runtime-internal
  `:rf.route.internal/settle-transition` event for re-frame2 routing.

  Owns:
    - `emit-activation-traces!` — the `:rf.route/activated` /
      `:rf.route/deactivated` lifecycle pair (Spec 012 §Trace events,
      rf2-dn26r);
    - `identical-route-target?` — Spec 012 §Per-route data loading rule
      3 short-circuit predicate;
    - `merge-route-slice` — the slice-publish merge over
      `[:rf.runtime/routing :current]` (encodes the slice-shape
      contract once for both the programmatic-nav and URL-driven paths,
      rf2-g8tzb);
    - `commit-navigation` — the shared successful-commit assembler
      (nav-token alloc + allocated/activation traces + slice publish +
      fx vector) used by both nav entry points;
    - `:rf.route.internal/settle-transition` — the FIFO-drain
      `:loading → :idle` settle (nav-token-aware so a newer navigation
      mid-drain bumps `:nav-token` and the stale settle becomes a no-op).

  Internal namespace; the public facade is `re-frame.routing`. The
  facade's `(events/reg-event :rf.route.internal/settle-transition ...)`
  wires `settle-transition-handler` into the registrar — keeping the
  registration in the facade so a `(require 're-frame.routing :reload)`
  on a fresh registrar (`clear-all!` test fixture) re-runs it. Per the
  rf2-2yabr cohesion split: SHARED-EVENT-HELPERS seam."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.routing.classification :as classification]
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

  rf2-dbmj6x — `frame` is the in-flight cascade's carried frame stamp
  (threaded from the nav handler's `:rf.frame/id` cofx via
  `commit-navigation`). Both lifecycle traces stamp it under `:tags
  :frame` so they enter the emitting frame's epoch (epoch-capture buffers
  only frame-tagged traces — `re-frame.epoch.capture` §168-221) and obey
  the frame-level trace-disable gate (`re-frame.trace/emit!` §397-398);
  pre-fix the untagged emits silently dropped from Xray and leaked past a
  `:rf.trace/frame-no-emit?` tool frame. `(cond-> … frame (assoc …))`
  mirrors the rf2-7d30s / rf2-w3qgc precedent — a nil stamp simply omits
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

;; Per Spec 012 §Per-route data loading rule 3: same-route-id
;; navigations with IDENTICAL params/query (and identical fragment) do
;; not re-fire `:on-match` — the runtime compares the prospective slice
;; against the current one and skips the dispatch when nothing relevant
;; changed. Re-firing the loaders would re-fetch unchanged data on every
;; redundant navigation (clicking the already-active nav link, a
;; duplicate `[:rf.route/navigate :route/cart]`, popstate to the current
;; URL), which is the data-refetch thrash the rule forbids. The
;; fragment-only case (id/params/query equal, fragment changed) is the
;; sibling short-circuit (rf2-8oxj6); this predicate is the stricter
;; "nothing at all changed" case.
(defn identical-route-target?
  "True when the prospective navigation target (`id`/`params`/`query`/
  `fragment`) is identical to the current route slice — a complete
  no-op re-navigation. `prev` is the current slice (or nil before first
  nav)."
  [prev id params query fragment]
  (boolean
    (and prev
         (= (:route-id prev) id)
         (= (:params prev)   params)
         (= (:query prev)    query)
         (= (:fragment prev) fragment))))

;; Per Spec 012 §The route slice and Spec-Schemas §`:rf/runtime-db` the
;; published slice carries exactly `{:route-id :params :query :fragment
;; :transition :error :nav-token}` under `[:rf.runtime/routing
;; :current]`. (`:route-id` is the self-describing slice key; the
;; consumer-facing sub-id stays `:rf.route/id`, rf2-3a5nk7.) Both nav entry points (programmatic
;; `:rf.route/navigate` and URL-driven `:rf.route/transitioned` /
;; `:rf.route/handle-url-change`) write the same merge shape after
;; allocating a nav-token. This helper encodes the slice-shape contract
;; in ONE place so the two writers and the layer-1 read
;; (`re-frame.routing.subs/route-sub-fn`) stay symmetric. `:error` is
;; always nil on a successful commit; the error-trap path
;; (`re-frame.routing.on-match-error`) sets it independently.
;;
;; The merge is targeted at `:current` (not at `:routing`), so the
;; sibling routing-runtime key under `[:rf.runtime/routing ...]`
;; (`:pending-navigation` — the only remaining runtime-db sibling) is
;; untouched. The nav-token / pending-nav counters are NOT runtime-db
;; siblings — they live in a host-side transient cache per rf2-oosjmh
;; (mirroring the scroll-position cache, rf2-1hncp2).
(defn merge-route-slice
  "Pure slice-publish: merges the new slice fields over the existing
  `:current` map at `[:rf.runtime/routing :current]`. Returns the
  updated db.

  `slice` is a map of `{:route-id :params :query :fragment :transition
  :nav-token}`. `:error` is forced to `nil` (the successful-commit
  contract); callers needing an error-state slice go through the
  `:rf.route/on-match-error` trap which writes `:error` explicitly."
  [db {:keys [route-id params query fragment transition nav-token]}]
  (update-in db [:rf.runtime/routing :current] merge
             {:route-id   route-id
              :params     params
              :query      query
              :fragment   fragment
              :transition transition
              :error      nil
              :nav-token  nav-token}))

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
;;      → the `:on-match` dispatches → the FIFO `settle-transition` (only
;;      when an `:on-match` drain exists, Spec 012 §Per-route data loading
;;      §2) → the scroll fx.
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

  `app-db` is the navigation handler's app-db coeffect value (EP-0016 D3
  slice 3): it is threaded UNCHANGED into the `:routing/on-route-entry`
  hook so a cross-feature route-resource `{:from-db <id>}` `:scope` (the
  Resources artefact) can resolve db-derived viewer scope at route entry,
  BEFORE the resource work is planned. Routing itself never reads app-db;
  it is a pure pass-through of the causal world input.

  rf2-dbmj6x — `frame` is the in-flight cascade's carried frame stamp (the
  nav handler's `:rf.frame/id` cofx; both nav entry points already
  validate it via `frame/require-frame-stamp!` and thread it in). It is
  stamped onto the `:rf.route.nav-token/allocated` trace and passed to
  `emit-activation-traces!` so the lifecycle pair carries `:frame` too.
  Without it those frame-known traces miss epoch capture (which buffers
  only frame-tagged events) and bypass the frame-level trace-disable gate.
  Returns the effects map `{:rf.db/runtime :fx}`."
  [rdb {:keys [route-id params query fragment transition]} on-match-vec
   {:keys [prev-id prev-nav-token capture-fx scroll-fx push-fx nav-allocation app-db frame]}]
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
        ;; rf2-vdyrls — cross-feature route `:resources` plan (Spec 016 §Route
        ;; integration). The Resources artefact publishes the LATE-BOUND
        ;; `:routing/on-route-entry` hook; routing consults it by key (no
        ;; static dep — resources is optional) and gets back
        ;; `{:fx [...] :blocking #{<scoped-key> …} :plan-error err?}`. The
        ;; ensure dispatches + prior-owner release are spliced into the commit
        ;; fx; the blocking set is written into the nav-token's blocking slot
        ;; ATOMICALLY with the commit (so it is present before any ensure
        ;; reply can drain it, keeping the route :loading until blocking
        ;; resources settle — Spec 016 §Route integration); a planning error
        ;; (a fail-closed params/scope throw) is recorded on the slice's
        ;; `:error`, visible to the `:rf/route` sub + Xray. No-op when no
        ;; Resources artefact / no `:resources` route metadata.
        route-meta (registrar/lookup :route route-id)
        plan       (when-let [on-entry (late-bind/get-fn :routing/on-route-entry)]
                     (on-entry {:route-meta     route-meta
                                :route-id       route-id
                                :params         params
                                :query          query
                                :fragment       fragment
                                :nav-token      token
                                :prev-id        prev-id
                                :prev-nav-token prev-nav-token
                                :ctx            {}
                                ;; EP-0016 D3 slice 3: the route-entry app-db,
                                ;; threaded from the nav handler's coeffect so a
                                ;; `{:from-db …}` route-resource scope resolves
                                ;; db-derived viewer identity at route entry.
                                :app-db         app-db}))
        committed  (cond-> committed
                     (seq (:blocking plan))
                     (-> (assoc-in [:rf.runtime/routing :resource-blocking token]
                                   (:blocking plan))
                         ;; a blocking route resource keeps the transition
                         ;; :loading (its SSR wait point) even when the route
                         ;; declares no `:on-match` (the caller computed
                         ;; :transition :idle from on-match absence). Per Spec
                         ;; 016 §Route integration.
                         (assoc-in [:rf.runtime/routing :current :transition] :loading))
                     (:plan-error plan)
                     (assoc-in [:rf.runtime/routing :current :error]
                               (:plan-error plan)))
        ;; EP-0025 routes follow-on (rf2-3r6k8i): lower the activating route's
        ;; projection-relative `:sensitive` / `:large` classification into the
        ;; per-frame elision registry, RE-ROOTED under `[:rf.runtime/routing
        ;; :current …]`, ATOMICALLY with the slice publish (the same runtime-db
        ;; partition). Routes are a SINGLETON current-route, so this REPLACES the
        ;; prior route's `:source :route` entries — a route change drops the
        ;; leaving route's classification, and a route that declares none (incl.
        ;; `:rf.route/not-found`, whose `route-meta` may be nil) clears the
        ;; route-sourced entries. The declared paths redact the slice's
        ;; `:query` / `:params` at egress for as long as the route is active;
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
                      (mapv (fn [ev] [:dispatch ev]) on-match-vec)
                      ;; rf2-vdyrls: the resource ensure dispatches + prior-
                      ;; owner release (Spec 016 §Route integration).
                      (:fx plan)
                      ;; Per Spec 012 §Per-route data loading §2: settle
                      ;; :loading → :idle after the on-match drain. FIFO
                      ;; order means the settle runs after every on-match
                      ;; event already queued above. rf2-vdyrls: also settle
                      ;; after a resources plan so a route with `:resources`
                      ;; but no `:on-match` still lands :idle (the settle is
                      ;; blocking-aware — it stays :loading while a blocking
                      ;; resource is pending, draining when the slot empties).
                      (when (or (seq on-match-vec) (:fx plan))
                        [[:dispatch [:rf.route.internal/settle-transition token]]])
                      (when scroll-fx [scroll-fx])))}))

;; Per Spec 012 §Per-route data loading §2. FIFO drain queues
;; :rf.route.internal/settle-transition after the :on-match events so
;; :transition lands at :idle once the synchronous portion completes.
;; The settle is nav-token-aware: a newer navigation mid-drain bumps
;; :nav-token, and the stale settle becomes a no-op so the new :loading
;; isn't clobbered.
;;
;; Per Spec 012 §Per-route error handling: if any :on-match event errors
;; the runtime flips :transition :error, populates :rf.route/error, and
;; dispatches :on-error (when declared). The settle handler additionally
;; guards on `(= :loading current-transition)` so a settle queued AFTER
;; an :on-match throw does NOT clobber :error back to :idle — the throw's
;; trap (`:rf.route.internal/on-match-error` in
;; `re-frame.routing.on-match-error`) ran first and the slice now
;; carries :error.
;;
;; Per rf2-576on: this event is RUNTIME-INTERNAL — fired by the runtime
;; itself; never user-dispatched. The `:rf.route.internal/*` sub-
;; namespace separates the runtime's plumbing events from the user-
;; facing `:rf.route/*` surface (`:rf.route/navigate`, `:rf.route/
;; continue`, etc.). Same audience-split principle as
;; `:rf.route.nav-token/*` (Spec 012 §Navigation tokens).
(defn settle-transition-handler
  "`:rf.route.internal/settle-transition` event handler. Registered by
  the `re-frame.routing` façade so a `:reload` of the façade re-runs the
  registration.

  EP-0001 (rf2-vzld77): the route slice is durable framework runtime-db
  state, so this reads the `:rf.db/runtime` coeffect and returns a
  `:rf.db/runtime` effect (the runtime-db sibling of a `reg-event`
  handler's `:db` effect)."
  [{rt :rf.db/runtime} [_ token]]
  (let [runtime-db (or rt {})
        current    (get-in runtime-db [:rf.runtime/routing :current :nav-token])
        ;; rf2-vdyrls: a BLOCKING route resource keeps the transition
        ;; :loading past the `:on-match` drain — it is the route's SSR wait
        ;; point. The Resources artefact publishes the LATE-BOUND
        ;; `:routing/route-blocking?` predicate (true while any blocking
        ;; resource for the current nav-token is unsettled); the resource
        ;; reply handlers drain the slot + land :idle themselves when the
        ;; last blocking resource settles. So this settle is a no-op while a
        ;; blocking resource is pending — it would otherwise prematurely flip
        ;; :loading → :idle ahead of the data. No-op consult (false) when no
        ;; Resources artefact is loaded. Per Spec 016 §Route integration.
        blocking?  (boolean
                     (when-let [pred (late-bind/get-fn :routing/route-blocking?)]
                       (pred runtime-db)))]
    {:rf.db/runtime
     (if (and (= current token)
              (not blocking?)
              (= :loading (get-in runtime-db [:rf.runtime/routing :current :transition])))
       (assoc-in runtime-db [:rf.runtime/routing :current :transition] :idle)
       runtime-db)}))
