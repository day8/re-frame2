(ns re-frame.routing.on-match-error
  "`:on-match` error trap for re-frame2 routing.

  Per Spec 012 §Per-route error handling: if any :on-match event errors
  (a handler throws, a registered fx errors, or a downstream handler
  errors during the drain — per Spec 009's structured error contract),
  the runtime:
    1. Sets `:rf.route/transition` to `:error`.
    2. Populates `:rf.route/error` with the structured error map
       (schema `:rf/error` per Spec 009 §error-contract).
    3. If the route declares `:on-error`, dispatches it. The handler
       reads `(:error (:rf/route db))` for the error context.

  Mechanism: a corpus-wide listener on the always-on error-emit
  substrate (per rf2-bacs4 / Spec 009 §What IS available in production)
  receives every `:rf.error/handler-exception` record. The listener
  discriminates 'is this exception from an :on-match dispatch?' by:
    - reading the failing record's `:frame`
    - reading that frame's `:rf/route` slice
    - checking `:transition` is `:loading` (the slice is mid-drain)
    - checking the failing event-id is in the active route's `:on-match`

  All four together identify the error as originating from an :on-match
  cascade for the currently-loading route. The listener then dispatches
  `:rf.route.internal/on-match-error` with the structured error map;
  that event flips `:transition`, populates `:rf.route/error`, and
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

(defn- on-match-event-ids
  "Return a set of event-ids declared in `route-meta`'s `:on-match`.
  Empty when the route declares no `:on-match`. Used by the error-emit
  listener to discriminate which handler-exception records originated
  from an `:on-match` dispatch."
  [route-meta]
  (into #{}
        (comp (filter vector?)
              (map first))
        (or (:on-match route-meta) [])))

(defn on-match-error-handler
  "`:rf.route.internal/on-match-error` event-fx handler. Registered by
  the `re-frame.routing` façade so a `:reload` of the façade re-runs the
  registration."
  [{:keys [db]} [_ {:keys [error nav-token]}]]
    ;; Per Spec 012 §Per-route error handling. Nav-token-guarded: if a
    ;; newer navigation has already bumped :nav-token, this error
    ;; belongs to a superseded drain and is dropped (matches
    ;; :rf.route.internal/settle-transition's epoch check).
    (let [current-token (get-in db [:rf/route :nav-token])
          current-id    (get-in db [:rf/route :id])
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
                   (assoc-in [:rf/route :transition] :error)
                   (assoc-in [:rf/route :error]      error))}
          ;; Spec 012 §Per-route error handling: a declared :on-error
          ;; receives no payload — the handler reads (:error (:rf/route
          ;; db)) for the error context. Vector form `[:ev-id ...]`
          ;; dispatches as-is; bare keyword wraps as `[:ev-id]`.
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
  [{:keys [error event-id frame exception] :as _record}]
  (when (= :rf.error/handler-exception error)
    (let [db            (frame/frame-app-db-value frame)
          route-slice   (when db (:rf/route db))
          route-id      (:id route-slice)
          transition    (:transition route-slice)
          nav-token     (:nav-token route-slice)
          route-meta    (when route-id (registrar/lookup :route route-id))
          on-match-ids  (on-match-event-ids route-meta)]
      ;; Three discriminators all must hold:
      ;;   1. The slice is mid-drain (`:loading`).
      ;;   2. The failing event-id is in the active route's `:on-match`.
      ;;   3. A nav-token is present (otherwise routing is uninitialised).
      ;; All three together mean: the failing handler was an :on-match
      ;; dispatch for the currently-loading route.
      (when (and (= :loading transition)
                 nav-token
                 (contains? on-match-ids event-id))
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
          ;; :rf/dispatch-origin :router so Xray's L2 timeline + tools
          ;; filter pills can discriminate framework-origin events from
          ;; user-origin events.
          (router/dispatch! [:rf.route.internal/on-match-error
                             {:error     error-map
                              :nav-token nav-token}]
                            {:frame frame :rf/dispatch-origin :router}))))))

;; The façade (`re-frame.routing`) wires the listener via
;; `error-emit/register-error-listener! :rf.route/on-match-error-trap`
;; so a `:reload` of the façade re-registers it on a fresh registrar
;; (the `clear-all!` test-fixture path). Per Spec 009 §What IS available
;; in production the listener id is namespaced under `:rf.route/*` so
;; accidental re-registration by another artefact is rejected by the
;; corpus-wide substrate's id check.
