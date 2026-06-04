(ns re-frame.routing.events
  "Shared navigation-event helpers + the runtime-internal
  `:rf.route.internal/settle-transition` event for re-frame2 routing.

  Owns:
    - `alloc-nav-token` / `alloc-pending-nav-id` — per-frame counter
      allocators (pure: db → [db' id-str]);
    - `emit-activation-traces!` — the `:rf.route/activated` /
      `:rf.route/deactivated` lifecycle pair (Spec 012 §Trace events,
      rf2-dn26r);
    - `identical-route-target?` — Spec 012 §Per-route data loading rule
      3 short-circuit predicate;
    - `merge-route-slice` — the slice-publish merge over
      `[:rf/runtime :routing :current]` (encodes the slice-shape
      contract once for both the programmatic-nav and URL-driven paths,
      rf2-g8tzb);
    - `commit-navigation` — the shared successful-commit assembler
      (nav-token alloc + allocated/activation traces + slice publish +
      fx vector) used by both nav entry points;
    - `:rf.route.internal/settle-transition` — the FIFO-drain
      `:loading → :idle` settle (nav-token-aware so a newer navigation
      mid-drain bumps `:nav-token` and the stale settle becomes a no-op).

  Internal namespace; the public facade is `re-frame.routing`. The
  facade's `(events/reg-event-db :rf.route.internal/settle-transition ...)`
  wires `settle-transition-handler` into the registrar — keeping the
  registration in the facade so a `(require 're-frame.routing :reload)`
  on a fresh registrar (`clear-all!` test fixture) re-runs it. Per the
  rf2-2yabr cohesion split: SHARED-EVENT-HELPERS seam."
  (:require [re-frame.trace :as trace]))

;; Per Spec 012 §Multi-frame routing: nav-token and pending-nav id
;; counters are per-frame. Pure allocators: take db, return [db' id-str].
;; Both share one increment-and-stringify shape over a per-frame counter
;; key under `[:rf/runtime :routing ...]`; the only per-allocator
;; variation is the counter key and the id prefix.
;;
;; The counters are INTENTIONALLY monotone and unbounded — see Spec 012
;; §Navigation tokens, step 1. A token need only be unique within the
;; lifetime of any in-flight async continuation (equality against the
;; current slice token is the only operation on it), which a monotone
;; counter satisfies without ever wrapping. Unlike the bounded siblings
;; under `[:rf/runtime :routing …]` (the scroll-position LRU cap, the
;; decoded-key cap, which bound RETAINED collections), each counter is a
;; single scalar that retains nothing and is GC'd whole on frame-destroy.
;; Overflow is a non-concern: CLJS f64 (exact to 2^53), JVM `long`
;; (2^63). DO NOT wrap/recycle — a recycled value could collide with a
;; token still carried by a slow in-flight continuation, silently
;; re-validating a stale result.

(defn- alloc-counter
  "Pure per-frame counter allocator. Increments the counter at
  `[:rf/runtime :routing counter-key]` and returns
  `[db' (str prefix n)]`."
  [db counter-key prefix]
  (let [n (inc (or (get-in db [:rf/runtime :routing counter-key]) 0))]
    [(assoc-in db [:rf/runtime :routing counter-key] n)
     (str prefix n)]))

(defn alloc-nav-token
  "Pure allocator: returns [db' \"nav-N\"]. Increments the per-frame
  counter at [:rf/runtime :routing :nav-token-counter]."
  [db]
  (alloc-counter db :nav-token-counter "nav-"))

(defn alloc-pending-nav-id
  "Pure allocator: returns [db' \"pn-N\"]. Increments the per-frame
  counter at [:rf/runtime :routing :pending-nav-counter]."
  [db]
  (alloc-counter db :pending-nav-counter "pn-"))

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
  `:rf.route/transitioned` event."
  [prev-id next-id]
  (when (and prev-id (not= prev-id next-id))
    (trace/emit! :rf.event :rf.route/deactivated
                 {:route-id prev-id}))
  (when (and next-id (not= prev-id next-id))
    (trace/emit! :rf.event :rf.route/activated
                 {:route-id next-id})))

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
         (= (:id prev)       id)
         (= (:params prev)   params)
         (= (:query prev)    query)
         (= (:fragment prev) fragment))))

;; Per Spec 012 §The route slice and Spec-Schemas §`:rf/runtime` the
;; published slice carries exactly `{:id :params :query :fragment
;; :transition :error :nav-token}` under `[:rf/runtime :routing
;; :current]`. Both nav entry points (programmatic
;; `:rf.route/navigate` and URL-driven `:rf.route/transitioned` /
;; `:rf.route/handle-url-change`) write the same merge shape after
;; allocating a nav-token. This helper encodes the slice-shape contract
;; in ONE place so the two writers and the layer-1 read
;; (`re-frame.routing.subs/route-sub-fn`) stay symmetric. `:error` is
;; always nil on a successful commit; the error-trap path
;; (`re-frame.routing.on-match-error`) sets it independently.
;;
;; The merge is targeted at `:current` (not at `:routing`), so the
;; sibling routing-runtime keys under `[:rf/runtime :routing ...]`
;; (`:scroll-positions` / `:scroll-positions-order` /
;; `:nav-token-counter` / `:pending-nav-counter`) are untouched.
(defn merge-route-slice
  "Pure slice-publish: merges the new slice fields over the existing
  `:current` map at `[:rf/runtime :routing :current]`. Returns the
  updated db.

  `slice` is a map of `{:id :params :query :fragment :transition
  :nav-token}`. `:error` is forced to `nil` (the successful-commit
  contract); callers needing an error-state slice go through the
  `:rf.route/on-match-error` trap which writes `:error` explicitly."
  [db {:keys [id params query fragment transition nav-token]}]
  (update-in db [:rf/runtime :routing :current] merge
             {:id         id
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
;;   1. allocate a fresh per-frame nav-token (the cascade-begin marker);
;;   2. emit `:rf.route.nav-token/allocated`, then `emit-activation-
;;      traces!` — IN THAT ORDER so trace consumers see
;;      {allocated → deactivated? → activated?} (rf2-dn26r);
;;   3. publish the seven-key slice via `merge-route-slice`;
;;   4. assemble the fx vector: capture-scroll (the leaving route's
;;      position) → [push-fx, when the programmatic path must drive the
;;      browser URL] → the `:on-match` dispatches → the FIFO
;;      `settle-transition` (only when an `:on-match` drain exists,
;;      Spec 012 §Per-route data loading §2) → the scroll fx.
;; The URL-driven path passes no `push-fx` (the browser URL already
;; changed); the programmatic path passes `[:rf.nav/push-url ...]` /
;; `[:rf.nav/replace-url ...]`. Holding the commit shape in ONE place
;; keeps the trace ordering, the slice contract, and the fx assembly
;; from drifting between the two callers.
(defn commit-navigation
  "Pure navigation-commit assembler shared by the programmatic-nav and
  URL-driven paths. `db` is the pre-token-allocation db. `slice` carries
  `{:id :params :query :fragment :transition}` (the published fields
  minus `:nav-token`, which this fn allocates). `on-match-vec` is the
  resolved `:on-match` event vector (possibly empty). `capture-fx` /
  `scroll-fx` are the pre-built fx entries (or nil) and `push-fx` is the
  optional history-mutation fx entry (nil on the URL-driven path).
  Returns the event-fx cofx map `{:db :fx}`."
  [db {:keys [id params query fragment transition]} on-match-vec
   {:keys [prev-id capture-fx scroll-fx push-fx]}]
  (let [[db' token] (alloc-nav-token db)]
    (trace/emit! :rf.event :rf.route.nav-token/allocated
                 {:route-id id :nav-token token})
    (emit-activation-traces! prev-id id)
    {:db (merge-route-slice db' {:id         id
                                 :params     params
                                 :query      query
                                 :fragment   fragment
                                 :transition transition
                                 :nav-token  token})
     :fx (vec (concat (when capture-fx [capture-fx])
                      (when push-fx    [push-fx])
                      (mapv (fn [ev] [:dispatch ev]) on-match-vec)
                      ;; Per Spec 012 §Per-route data loading §2: settle
                      ;; :loading → :idle after the on-match drain. FIFO
                      ;; order means the settle runs after every on-match
                      ;; event already queued above.
                      (when (seq on-match-vec)
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
  "`:rf.route.internal/settle-transition` event-db handler. Registered by
  the `re-frame.routing` façade so a `:reload` of the façade re-runs the
  registration."
  [db [_ token]]
  (let [current (get-in db [:rf/runtime :routing :current :nav-token])]
    (if (and (= current token)
             (= :loading (get-in db [:rf/runtime :routing :current :transition])))
      (assoc-in db [:rf/runtime :routing :current :transition] :idle)
      db)))
