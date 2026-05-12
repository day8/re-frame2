(ns day8.re-frame2-causa.registry
  "Causa's framework registrations — events, subs, fxs under the
  `:rf.causa/*` namespace prefix.

  ## Why the namespace prefix matters (rf2-tijr Option C)

  Per rf2-tijr the registrar is process-global; Causa's registrations
  share the registry with the host app. The `:rf.causa/*` prefix is the
  collision-avoidance contract: Causa never registers under a
  non-`:rf.causa/*` keyword, so a host registering `:user/login` and
  Causa registering `:rf.causa/buffer-cleared` cannot stamp on each
  other.

  ## Why the registrations target the `:rf/causa` frame

  Per rf2-tijr Option C the panel's state lives in a frame named
  `:rf/causa` — a sibling of the host's `:rf/default`. Subscribers /
  dispatchers wrapped inside `[rf/frame-provider {:frame :rf/causa}
  ...]` resolve to that frame; a Causa view subscribing to
  `:rf.causa/trace-buffer` reads `:rf/causa`'s app-db, not the host's.

  Even though the registrar is process-global, each registered handler
  operates *against the active frame's db* — so the registry namespace
  prefix and the frame isolation work together: prefix prevents id
  collision, frame-provider prevents db reads/writes from leaking into
  the host.

  ## Phase 2 scope (rf2-op3bz)

  Phase 1 (rf2-n6x4q) shipped only `:rf.causa/trace-buffer`. Phase 2
  adds the event-detail panel's wiring:

    - `:rf.causa/selected-panel`           sub — current panel-id
    - `:rf.causa/select-panel`             event-db — set current panel
    - `:rf.causa/selected-dispatch-id`     sub — focused cascade
    - `:rf.causa/event-detail`             sub — projected cascade
    - `:rf.causa/select-dispatch-id`       event-db — set focused
    - `:rf.causa/clear-selected-dispatch-id` event-db — clear focus

  Subsequent panel beads add their own per-panel events / subs / fxs."
  (:require [re-frame.core :as rf]
            [re-frame.trace.projection :as projection]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- defaults ------------------------------------------------------------

(def default-panel-id
  "The hero panel — `:event-detail` — is Causa's default landing per
  spec/007-UX-IA.md §The default landing view + §10 Lock 7. Exposed
  as a Var so the shell and tests share the source of truth."
  :event-detail)

;; ---- subscriptions -------------------------------------------------------

;; Causa's trace-buffer sub returns the Causa-side ring buffer
;; contents (NOT the framework's `(rf/trace-buffer)`). Reading directly
;; from `trace-bus/buffer` is the right shape here because the buffer
;; is process-global, not per-frame — every Causa shell mounted across
;; any frame should see the same trace stream. The sub thunks the
;; pure-data accessor so reactive contexts get a fresh read on every
;; recompute.
(defonce ^:private registered?
  ;; Idempotency sentinel. Re-loading the namespace (shadow-cljs
  ;; `:after-load`) must not re-register the sub (would harmlessly
  ;; replace the handler, but emits a `:rf.warning/handler-replaced`
  ;; trace that pollutes the dev console on every reload).
  (atom false))

(defn register-causa-handlers!
  "Idempotent registration of Causa's :rf.causa/* events, subs, fxs.
  Called from `day8.re-frame2-causa.preload` at load time. Safe to
  call multiple times — second + subsequent calls are no-ops."
  []
  (when (compare-and-set! registered? false true)
    ;; ---- subs ----------------------------------------------------
    (rf/reg-sub :rf.causa/trace-buffer
      (fn [_db _query]
        (trace-bus/buffer)))

    ;; Currently-active panel — drives the canvas's switch logic in
    ;; shell.cljs. Default is the hero panel per §10 Lock 7.
    (rf/reg-sub :rf.causa/selected-panel
      (fn [db _query]
        (get db :selected-panel default-panel-id)))

    ;; The dispatch-id the user has drilled into. nil = empty state
    ;; (cascade list, per spec/007-UX-IA.md §The default landing view).
    (rf/reg-sub :rf.causa/selected-dispatch-id
      (fn [db _query]
        (get db :selected-dispatch-id)))

    ;; Event-detail composite — produces everything the panel needs in
    ;; one read so the view stays a thin renderer. Shape:
    ;;
    ;;     {:cascades             [...]   ; all cascades, oldest first
    ;;      :selected-dispatch-id <id>    ; nil when no selection
    ;;      :selected-cascade     {...}}  ; nil when no selection
    ;;                                    ; OR when the id is no
    ;;                                    ; longer in the buffer
    ;;
    ;; The projection runs against the live buffer on every recompute.
    ;; Per spec/007-UX-IA.md §Performance budget the panel renders at
    ;; most ~200 cascades; the projection is O(n) over the buffer.
    (rf/reg-sub :rf.causa/event-detail
      ;; Signal layer: depend on the trace-buffer sub +
      ;; selected-dispatch-id sub so this composite recomputes when
      ;; either changes. The `:<-` chain is the only sub-registration
      ;; form in v2 (per Spec 002 §Subscriptions composing —
      ;; reg-sub-raw is dropped; see `re-frame.subs/parse-reg-sub-args`).
      :<- [:rf.causa/trace-buffer]
      :<- [:rf.causa/selected-dispatch-id]
      (fn [[buffer selected-id] _query]
        (let [cascades (projection/group-cascades buffer)
              by-id    (when selected-id
                         (some #(when (= selected-id (:dispatch-id %)) %)
                               cascades))]
          {:cascades             cascades
           :selected-dispatch-id selected-id
           :selected-cascade     by-id})))

    ;; ---- events --------------------------------------------------
    (rf/reg-event-db :rf.causa/select-panel
      (fn [db [_ panel-id]]
        (assoc db :selected-panel panel-id)))

    (rf/reg-event-db :rf.causa/select-dispatch-id
      (fn [db [_ dispatch-id]]
        (assoc db :selected-dispatch-id dispatch-id)))

    (rf/reg-event-db :rf.causa/clear-selected-dispatch-id
      (fn [db _event]
        (dissoc db :selected-dispatch-id))))
  nil)

(defn reset-for-test!
  "Reset the registry's idempotency sentinel so test fixtures can drive
  multiple registration cycles. Test-only — never call from production
  code."
  []
  (reset! registered? false)
  nil)
