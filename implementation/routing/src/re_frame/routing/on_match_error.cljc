(ns re-frame.routing.on-match-error
  "`:on-match` error trap for re-frame2 routing.

  When a declared `:on-match` event produces an attributed
  `:rf.error/handler-exception` record, the runtime:
    1. Sets `[:rf.runtime/routing :current :transition]` to `:error`.
    2. Populates `[:rf.runtime/routing :current :error]` with the
       structured error map (schema `:rf/error` per Spec 009 §error-contract).
    3. If the route declares `:on-error`, dispatches it. The handler
       reads `(get-in db [:rf.runtime/routing :current :error])` for
       the error context.

  Mechanism: a corpus-wide listener on the always-on error-emit
  substrate (Spec 009 §What IS available in production)
  receives every `:rf.error/handler-exception` record. The listener
  discriminates 'is this exception from an :on-match dispatch?' by:
    - reading the failing record's `:frame`
    - reading that frame's route slice at [:rf.runtime/routing :current]
    - checking `:transition` is `:loading` (the slice is mid-drain)
    - checking the failing record's full `:event` vector IS one of the
      active route's declared `:on-match` vectors (full-vector identity,
      not bare event-id membership; falls back to
      id-membership only when the event was wire-elided)

  All four together identify the error as originating from an :on-match
  cascade for the currently-loading route. The listener then dispatches
  `:rf.route.internal/on-match-error` with the structured error map;
  that event flips `:transition`, populates the slice's `:error`, and
  chains `:on-error`. The trap event is runtime-internal,
  sub-namespaced under `:rf.route.internal/*` so the user-facing
  `:rf.route/*` surface stays tidy.

  ## Failure semantics — later :on-match events + first-error-wins

  Per Spec 012 §Per-route error handling §Failure semantics, the navigation
  cascade runs inside re-frame2's
  locked FIFO run-to-completion drain (Spec 002 §Run-to-completion),
  which does NOT cancel events already queued. `commit-navigation`
  queues every `:on-match` dispatch (and the FIFO `settle-transition`)
  up front; the trap's `:rf.route.internal/on-match-error` is dispatched
  from inside the throwing handler's error path and lands at the BACK of
  the queue. So for `:on-match [[:load/fail] [:load/next]]`, `:load/next`
  (and the settle) run before the error event — later route loaders are
  NOT aborted. This continuation is the documented, intentional
  consequence of the locked drain (true loader-cancellation would need a
  generic front-of-queue / cancellation primitive in the core router,
  not a routing-local concern).

  What IS guaranteed:
    - **Final state is always `:error`.** The settle handler guards on
      `(= :loading transition)`, and the error event runs after settle,
      so the slice lands on `:error` regardless of queue interleaving.
    - **First-error-wins.** When MULTIPLE `:on-match` events throw in the
      same transition, `on-match-error-handler` drops every same-nav-token
      error after the slice is already `:error` — the FIRST attributed
      failure is the recorded `:error`/attribution, and `:on-error` fires
      exactly once. Aligns with xstate v5: an errored transition's first
      error is the recorded one. A NEWER navigation (nav-token bump) is
      dropped by the stale-token guard and resets the slice off `:error`
      through its own commit, so failure-after-recovery still records.

  The listener is always-on (survives `:advanced` + `goog.DEBUG=false`)
  so production builds with the trace surface elided still observe
  :on-match errors and route them to :on-error policies.

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the `events/reg-event` + `error-emit/register-error-listener!`
  calls so a `(require 're-frame.routing :reload)` on a fresh registrar
  (`clear-all!` test fixture) re-wires both."
  (:require [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.router :as router]))

(defn- on-match-event-vecs
  "Return the set of full event VECTORS declared in `route-meta`'s
  `:on-match`. Empty when the route declares no `:on-match`. Used by the
  error-emit listener to discriminate which handler-exception records
  originated from an `:on-match` dispatch — full-vector identity is the
  tightest always-on discriminator (see `on-match-attributed?`)."
  [route-meta]
  (into #{}
        (filter vector?)
        (or (:on-match route-meta) [])))

(defn- on-match-event-ids
  "Return the set of event-ids (head keywords) declared in `route-meta`'s
  `:on-match`. The coarse fallback discriminator used only when the
  failing record's full `:event` vector is unavailable (wire-elided to
  `:rf.size/large-elided` per Spec 009 §size-elision)."
  [route-meta]
  (into #{}
        (comp (filter vector?)
              (map first))
        (or (:on-match route-meta) [])))

(defn- on-match-attributed?
  "True when the failing handler-exception `record` should be attributed
  to the active route's `:on-match` drain.

  Bare event-id membership would mis-attribute a non-routing throw to the
  loading route whenever
  the app concurrently dispatches an event whose id happens to coincide
  with one of the route's `:on-match` ids during the loading window — the
  healthy route is then forced to `:error` and a spurious `:on-error`
  chains. The id-only check cannot tell the route's own
  `[[:app/load-x 'from-route']]` dispatch apart from a button handler's
  `[:app/load-x 'from-button']`.

  The tighter, still-always-on discriminator is full event-VECTOR
  identity against the route's declared `:on-match` vectors: a button
  dispatch carrying different args (or a bare `[:app/load-x]` versus the
  route's `[:app/load-x 42]`) does not collide. The full `:event`
  vector rides the always-on error record (Spec 009 §Record shape), so
  this survives `:advanced` + `goog.DEBUG=false` like the rest of the
  trap.

  Wire elision (Spec 009 §size-elision) can replace a LARGE `:event`
  with the sentinel `:rf.size/large-elided`. In that one case the full
  vector is unavailable, so we fall back to the coarse id-membership
  check rather than silently dropping a genuine on-match error — losing
  the `:on-error` chain for a large-payload loader is the worse failure
  than the (already low-likelihood) coincidence the vector check closes.

  Residual coincidence — a DIFFERENT cascade dispatching the byte-for-
  byte IDENTICAL on-match vector during the loading window — remains
  indistinguishable from the genuine on-match throw on the always-on
  substrate (the error record carries no cause/cascade correlation; the
  epoch cause-event-id surface is dev-only and elides in production).
  Documented as an accepted limitation in Spec 012 §`:on-match`
  exception attribution."
  [route-meta {:keys [event event-id]}]
  (let [elided? (= event :rf.size/large-elided)]
    (if (and (vector? event) (not elided?))
      ;; Full-vector identity — the tight discriminator.
      (contains? (on-match-event-vecs route-meta) event)
      ;; `:event` was wire-elided (large payload): fall back to the
      ;; coarse id-membership check so a genuine on-match throw on a
      ;; large-payload loader still routes to `:on-error`.
      (contains? (on-match-event-ids route-meta) event-id))))

(defn on-match-error-handler
  "`:rf.route.internal/on-match-error` event handler. Registered by
  the `re-frame.routing` façade so a `:reload` of the façade re-runs the
  registration."
  [{rdb :rf.db/runtime} [_ {:keys [error nav-token]}]]
    ;; Per Spec 012 §Per-route error handling. Nav-token-guarded: if a
    ;; newer navigation has already bumped :nav-token, this error
    ;; belongs to a superseded drain and is dropped (matches
    ;; :rf.route.internal/settle-transition's epoch check). EP-0001
    ;; (rf2-vzld77): the route slice is durable routing runtime-db state.
    (let [db            (or rdb {})
          current-token (get-in db [:rf.runtime/routing :current :nav-token])
          current-trans (get-in db [:rf.runtime/routing :current :transition])
          current-id    (get-in db [:rf.runtime/routing :current :route-id])
          route-meta    (when current-id (registrar/lookup :route current-id))
          on-error-ev   (:on-error route-meta)]
      (cond
        ;; Stale — the trap fired for an :on-match throw from a previous
        ;; navigation that has since been superseded. Drop silently
        ;; (the corpus-wide error-emit substrate already surfaced the
        ;; underlying :rf.error/handler-exception for observability).
        (not= nav-token current-token)
        {}

        ;; First error wins. The locked FIFO run-to-completion drain (Spec 002)
        ;; does
        ;; not cancel already-queued events, so when a route declares
        ;; `:on-match [[:load/fail1] [:load/fail2]]` and BOTH throw, two
        ;; `:rf.route.internal/on-match-error` events land for the same
        ;; nav-token. Without this guard the SECOND failure clobbers the
        ;; first in the slice (wrong attribution) and `:on-error` fires
        ;; twice. The route enters `:error` at the first attributed throw;
        ;; once it is `:error` for the CURRENT nav-token, later same-token
        ;; failures are dropped — the first error/attribution and a single
        ;; `:on-error` dispatch are preserved. A NEWER navigation (token
        ;; bump) is handled by the stale branch above and resets the slice
        ;; off `:error` through its own commit, so a genuine
        ;; failure-after-recovery still records. Aligns with xstate v5:
        ;; an errored transition's first error is the recorded one.
        (= :error current-trans)
        {}

        :else
        (cond->
          {:rf.db/runtime (-> db
                              (assoc-in [:rf.runtime/routing :current :transition] :error)
                              (assoc-in [:rf.runtime/routing :current :error]      error))}
          ;; Spec 012 §Per-route error handling: a declared :on-error
          ;; receives no payload — the handler reads
          ;; `(get-in db [:rf.runtime/routing :current :error])` for
          ;; the error context. Vector form `[:ev-id ...]` dispatches
          ;; as-is; bare keyword wraps as `[:ev-id]`.
          on-error-ev
          (assoc :fx [[:dispatch (if (vector? on-error-ev)
                                   on-error-ev
                                   [on-error-ev])]])))))

(defn on-match-error-listener
  "Corpus-wide `register-error-listener!` fn. Inspects every
  `:rf.error/handler-exception` record; when the failing event-id was
  dispatched as part of the active route's `:on-match` (per the
  discrimination logic in this ns's docstring),
  dispatches `:rf.route.internal/on-match-error` to the
  offending frame so the slice flips to `:error` and `:on-error`
  chains.

  Per Spec 012 §Per-route error handling."
  [{:keys [error event-id frame exception] :as record}]
  (when (= :rf.error/handler-exception error)
    ;; EP-0001 (rf2-vzld77): the route slice is durable routing runtime-db state.
    (let [rdb           (frame/frame-runtime-db-value frame)
          route-slice   (when rdb (get-in rdb [:rf.runtime/routing :current]))
          route-id      (:route-id route-slice)
          transition    (:transition route-slice)
          nav-token     (:nav-token route-slice)
          route-meta    (when route-id (registrar/lookup :route route-id))]
      ;; Three discriminators all must hold:
      ;;   1. The slice is mid-drain (`:loading`).
      ;;   2. The failing record is attributed to the active route's
      ;;      `:on-match` drain by full-vector identity (rf2-cgh8q),
      ;;      falling back to id-membership only when the event was
      ;;      wire-elided — see `on-match-attributed?`.
      ;;   3. A nav-token is present (otherwise routing is uninitialised).
      ;; All three together mean: the failing handler was an :on-match
      ;; dispatch for the currently-loading route.
      (when (and (= :loading transition)
                 nav-token
                 (on-match-attributed? route-meta record))
        ;; Build the structured error map (Spec 009 §error-contract).
        ;; The exception itself carries the diagnostic detail; we surface
        ;; the canonical :rf.error/ id + tags so apps can switch on it
        ;; the same way they do for any other Spec 009 error.
        ;;
        ;; Per rf2-m78lu: stamp `:rf.route/on-match-id` /
        ;; `:rf.route/on-match-frame` directly onto the error map so
        ;; route-attribution travels with the structured error to every
        ;; downstream consumer — tools reading `:rf.error/handler-
        ;; exception` outside this listener's discrimination context
        ;; (Xray's event lens, an off-box Sentry shipper, an SSR error
        ;; projection) can identify the throw as :on-match-attributed
        ;; without re-running the discrimination logic. Same pattern as
        ;; the flow-attribution slot `:rf.flow/failed-id` (rf2-je5p8 /
        ;; Spec 013 §Failure semantics).
        (let [error-map {:operation             :rf.error/handler-exception
                         :failing-id            event-id
                         :event-id              event-id
                         :frame                 frame
                         :rf.route/on-match-id    event-id
                         :rf.route/on-match-frame frame
                         :exception             exception
                         :exception-message #?(:clj (when exception
                                                      (.getMessage ^Throwable exception))
                                               :cljs (some-> exception .-message))
                         :reason            "An :on-match event threw."}]
          ;; Per rf2-t1lxr: routing-internal dispatches self-tag with
          ;; :source :router so Xray's L2 timeline + tools filter pills
          ;; can discriminate framework-origin events from user-origin
          ;; events. Per rf2-1ve9h `:source` is the single closed-enum
          ;; functional-origin axis on the dispatch envelope.
          (router/dispatch! [:rf.route.internal/on-match-error
                             {:error     error-map
                              :nav-token nav-token}]
                            {:frame frame :source :router}))))))

;; The façade (`re-frame.routing`) wires the listener via
;; `error-emit/register-error-listener! :rf.route/on-match-error-trap`
;; so a `:reload` of the façade re-registers it on a fresh registrar
;; (the `clear-all!` test-fixture path). Per Spec 009 §What IS available
;; in production the listener id is namespaced under `:rf.route/*` so
;; accidental re-registration by another artefact is rejected by the
;; corpus-wide substrate's id check.
