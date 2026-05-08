(ns re-frame.elision-probe
  "Production-elision probe (Spec 009 §Production builds).

  This namespace is compiled under `:advanced` with
  `:closure-defines {goog.DEBUG false}` by the `:elision-probe` shadow-cljs
  build. The resulting JS bundle is then grep'd by
  `scripts/check-elision.cjs` for dev-only string sentinels — they MUST
  NOT appear, because their containing branches are gated on
  `re-frame.interop/debug-enabled?` (an alias of `goog.DEBUG`).

  The probe touches every gated surface so that, in a non-elided control
  build (`goog.DEBUG=true`), the sentinels reliably *do* appear — that
  control is what gives the elision assertion teeth.

  Surfaces exercised:

  - `register-trace-cb!` / `remove-trace-cb!` / `emit-trace!`
    (Spec 009 §Emitting trace events)
  - `reg-app-schema` + `validate-*!` (Spec 010 §Production builds)
  - `register!` / `unregister!` / `clear-kind!` registrar trace emit
    (Spec 009 §:op-type vocabulary — :rf.registry/*)
  - dispatch through `dispatch-sync` to walk the router → events → fx
    pipeline (Spec 009 §Where trace emission lives)
  - `:rf.http/managed` Spec 014 trace ops (`:rf.http/retry-attempt`,
    `:rf.warning/decode-defaulted`) — emitted only inside
    `(when interop/debug-enabled? ...)` branches.

  Note the probe does NOT need to assert anything at runtime; it exists
  to root the dead-code-elimination graph at every surface. The grep
  test is the assertion."
  (:require [re-frame.core         :as rf]
            [re-frame.registrar    :as registrar]
            [re-frame.schemas      :as schemas]
            [re-frame.trace        :as trace]
            [re-frame.http-managed :as http-managed]))

;; ---- trace listener API ---------------------------------------------------

(defn ^:export touch-trace! []
  ;; Reach into every documented trace API so :advanced keeps the surface
  ;; alive and we're testing the *body* gates, not surface-pruning.
  (rf/register-trace-cb! ::probe (fn [_ev] nil))
  (rf/emit-trace! :event :rf.probe/touched {:source :probe})
  (rf/remove-trace-cb! ::probe)
  ;; rf2-smee — trace ring buffer (Spec 009 §Retain-N).  These public
  ;; entry points must elide their bodies in production.
  (trace/configure-trace-buffer! {:depth 50})
  (let [_buf (trace/trace-buffer {:op-type :event})]
    nil)
  (trace/clear-trace-buffer!))

;; ---- schemas surface ------------------------------------------------------

(defn ^:export touch-schemas! []
  (rf/reg-app-schema [:user :name] :string)
  (schemas/validate-app-db! {:user {:name "ok"}})
  (schemas/validate-app-db! {:user {:name 42}}    :probe/event)
  (schemas/validate-event!  :probe/event [:probe/event 1] {:spec :int})
  (schemas/validate-cofx!   :probe/cofx :probe/event {} {:spec :map})
  (schemas/validate-sub-return! :probe/sub [:probe/sub] :foo {:spec :keyword}))

;; ---- registrar trace emit -------------------------------------------------

(defn ^:export touch-registrar! []
  ;; reg-event-db / dispatch-sync exercise registrar/register! and the
  ;; router/events/fx trace emit sites in one shot.
  (rf/reg-event-db :probe/init  (fn [_db _ev] {:counter 0}))
  (rf/reg-event-db :probe/inc   (fn [db _ev] (update db :counter inc)))
  (rf/reg-sub      :probe/count (fn [db _q] (:counter db)))
  (rf/dispatch-sync [:probe/init])
  (rf/dispatch-sync [:probe/inc])
  ;; Re-register to fire :rf.registry/handler-replaced.
  (rf/reg-event-db :probe/inc   (fn [db _ev] (update db :counter (fnil inc 0))))
  ;; Exercise the registrar's unregister!/clear-kind! emit sites so that
  ;; the :rf.registry/handler-cleared keyword has a path into the
  ;; reachability graph too.
  (registrar/unregister!  :event :probe/inc)
  (registrar/clear-kind!  :sub))

;; ---- Spec 014 :rf.http/managed surface (rf2-cfig) -------------------------

(defn ^:export touch-http-managed! []
  ;; Spec 014 — `:rf.http/managed` ships gated trace ops:
  ;;
  ;;   :rf.http/retry-attempt           (info trace, both transports)
  ;;   :rf.warning/decode-defaulted     (warning trace, both transports)
  ;;
  ;; All emit sites are wrapped in `(when interop/debug-enabled? ...)`,
  ;; so under :advanced + goog.DEBUG=false the branches DCE and the
  ;; string sentinels (e.g. "rf.http/retry-attempt") should NOT appear
  ;; in the production bundle.
  ;;
  ;; The probe roots the dependency graph by:
  ;;   1. requiring re-frame.http-managed (forces its ns body to be
  ;;      compiled into the bundle, which is where the gated branches
  ;;      live);
  ;;   2. referencing the public canned-stub fx via dispatch — pulls in
  ;;      `dispatch-reply!`, `build-reply-event`, and the surrounding
  ;;      retry / decode ctx so the gated emits sit in a reachable
  ;;      module graph (DCE only eliminates branches it can prove dead;
  ;;      reachability comes from the require + the dispatch path).
  (rf/reg-event-fx :probe/http-touch
    (fn [_ _]
      {:fx [[:rf.http/managed-canned-success
             {:request {:method :get :url "/probe"}
              :on-success nil
              :value   {:probed true}}]]}))
  (rf/dispatch-sync [:probe/http-touch])
  ;; Touch clear-all-in-flight! so the in-flight registry surface is
  ;; reachable; the probe doesn't actually issue a real request.
  (http-managed/clear-all-in-flight!))

;; ---- entry point ----------------------------------------------------------

(defn ^:export run []
  (touch-trace!)
  (touch-schemas!)
  (touch-registrar!)
  (touch-http-managed!)
  ;; Reference trace/emit! directly through the trace ns alias so its
  ;; body, not just the public re-frame.core re-export, is reachable.
  (trace/emit! :event :rf.probe/direct-touch {:source :probe}))
