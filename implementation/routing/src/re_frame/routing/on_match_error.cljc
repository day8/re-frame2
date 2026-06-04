(ns re-frame.routing.on-match-error
  "`:on-match` error trap for re-frame2 routing.

  Per Spec 012 §Per-route error handling: if any :on-match event errors
  (a handler throws, a registered fx errors, or a downstream handler
  errors during the drain — per Spec 009's structured error contract),
  the runtime:
    1. Sets `[:rf/runtime :routing :current :transition]` to `:error`.
    2. Populates `[:rf/runtime :routing :current :error]` with the
       structured error map (schema `:rf/error` per Spec 009 §error-contract).
    3. If the route declares `:on-error`, dispatches it. The handler
       reads `(get-in db [:rf/runtime :routing :current :error])` for
       the error context.

  Mechanism: a corpus-wide listener on the always-on error-emit
  substrate (per rf2-bacs4 / Spec 009 §What IS available in production)
  receives every `:rf.error/handler-exception` record. The listener
  discriminates 'is this exception from an :on-match dispatch?' by:
    - reading the failing record's `:frame`
    - reading that frame's route slice at [:rf/runtime :routing :current]
    - checking `:transition` is `:loading` (the slice is mid-drain)
    - checking the failing record's full `:event` vector IS one of the
      active route's declared `:on-match` vectors (rf2-cgh8q —
      full-vector identity, not bare event-id membership; falls back to
      id-membership only when the event was wire-elided)

  All four together identify the error as originating from an :on-match
  cascade for the currently-loading route. The listener then dispatches
  `:rf.route.internal/on-match-error` with the structured error map;
  that event flips `:transition`, populates the slice's `:error`, and
  chains `:on-error`. Per rf2-576on the trap event is runtime-internal —
  sub-namespaced under `:rf.route.internal/*` so the user-facing
  `:rf.route/*` surface stays tidy.

  The listener is always-on (survives `:advanced` + `goog.DEBUG=false`)
  so production builds with the trace surface elided still observe
  :on-match errors and route them to :on-error policies.

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the `events/reg-event-fx` + `error-emit/register-error-listener!`
  calls so a `(require 're-frame.routing :reload)` on a fresh registrar
  (`clear-all!` test fixture) re-wires both. Per the rf2-2yabr cohesion
  split: ON-MATCH-ERROR-TRAP seam."
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

  rf2-cgh8q — the attribution discriminator. Earlier this was bare
  event-id MEMBERSHIP: `(contains? (on-match-event-ids meta) event-id)`.
  That mis-attributes a NON-routing throw to the loading route whenever
  the app concurrently dispatches an event whose id happens to coincide
  with one of the route's `:on-match` ids during the loading window — the
  healthy route is then forced to `:error` and a spurious `:on-error`
  chains. The id-only check cannot tell the route's own
  `[[:app/load-x 'from-route']]` dispatch apart from a button handler's
  `[:app/load-x 'from-button']`.

  The tighter, still-always-on discriminator is full event-VECTOR
  identity against the route's declared `:on-match` vectors: a button
  dispatch carrying different args (or a bare `[:app/load-x]` versus the
  route's `[:app/load-x 42]`) no longer collides. The full `:event`
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
  "`:rf.route.internal/on-match-error` event-fx handler. Registered by
  the `re-frame.routing` façade so a `:reload` of the façade re-runs the
  registration."
  [{:keys [db]} [_ {:keys [error nav-token]}]]
    ;; Per Spec 012 §Per-route error handling. Nav-token-guarded: if a
    ;; newer navigation has already bumped :nav-token, this error
    ;; belongs to a superseded drain and is dropped (matches
    ;; :rf.route.internal/settle-transition's epoch check).
    (let [current-token (get-in db [:rf/runtime :routing :current :nav-token])
          current-id    (get-in db [:rf/runtime :routing :current :id])
          route-meta    (when current-id (registrar/lookup :route current-id))
          on-error-ev   (:on-error route-meta)]
      (if (not= nav-token current-token)
        ;; Stale — the trap fired for an :on-match throw from a previous
        ;; navigation that has since been superseded. Drop silently
        ;; (the corpus-wide error-emit substrate already surfaced the
        ;; underlying :rf.error/handler-exception for observability).
        {}
        (cond->
          {:db (-> db
                   (assoc-in [:rf/runtime :routing :current :transition] :error)
                   (assoc-in [:rf/runtime :routing :current :error]      error))}
          ;; Spec 012 §Per-route error handling: a declared :on-error
          ;; receives no payload — the handler reads
          ;; `(get-in db [:rf/runtime :routing :current :error])` for
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
  dispatches `:rf.route.internal/on-match-error` (rf2-576on) to the
  offending frame so the slice flips to `:error` and `:on-error`
  chains.

  Per Spec 012 §Per-route error handling and rf2-ye7sh."
  [{:keys [error event-id frame exception] :as record}]
  (when (= :rf.error/handler-exception error)
    (let [db            (frame/frame-app-db-value frame)
          route-slice   (when db (get-in db [:rf/runtime :routing :current]))
          route-id      (:id route-slice)
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
