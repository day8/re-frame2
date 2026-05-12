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

  ## Phase 1 scope

  Foundation only: a single sub `:rf.causa/trace-buffer` that returns
  the Causa-side ring buffer's contents. Subsequent panel beads will
  add per-panel events / subs / fxs (event-detail selection, scrubber
  position, panel-switcher state, etc.)."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

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
    (rf/reg-sub :rf.causa/trace-buffer
      (fn [_db _query]
        (trace-bus/buffer))))
  nil)

(defn reset-for-test!
  "Reset the registry's idempotency sentinel so test fixtures can drive
  multiple registration cycles. Test-only — never call from production
  code."
  []
  (reset! registered? false)
  nil)
