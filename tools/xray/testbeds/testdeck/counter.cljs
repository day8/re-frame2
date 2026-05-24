(ns testdeck.counter
  "Testdeck COUNTER feature (rf2-6qgbs.1) — the simplest reactive
  surface, shaped to exercise four observable behaviours cleanly. A
  TEST surface, not a tutorial: every behaviour below is a feature
  being exercised, never a deliberate bug.

  Shared by both testdeck surfaces (two-frame isolation testbed +
  step-deck rf2-6qgbs.2). Registered once globally; resolves per-frame
  via the dispatch envelope's `:frame`.

  ## What this surface exercises

  1. **L2 sub create + destroy is observable.** `:counter/parity` is a
     Layer-2 derived sub (`:<- [:counter/value]`). A sub is created
     when the first live view subscribes it and destroyed when the
     last unsubscribes (Spec 006 §Sub lifecycle). The parity pill in
     `testdeck.panel`'s Counter body is gated behind a local toggle, so
     showing/hiding the pill creates and destroys the L2 sub on demand
     — Xray's Views lens shows the node appear and disappear.

  2. **A view taking a CHANGEABLE argument.** `:counter/greater-than?`
     is a dynamic sub parameterised by a threshold carried in the query
     vector: `[:counter/greater-than? n]`. The panel reads
     `[:counter/greater-than? 5]` — the canonical `show-greater-than-
     five = (> counter 5)`. The argument changes as the panel's
     threshold control moves, so the same sub registration backs many
     distinct cache entries (one per threshold), each cascading from
     `:counter/value`.

  3. **A `now` coeffect.** `:counter/inc` and `:counter/dec` inject the
     `:testdeck/now` cofx to stamp the last-clicked wall-clock time
     into app-db — keeping the handlers pure functions of
     `(coeffects, event) → effects` (Spec 002 §Cofx) rather than
     calling `(js/Date.)` inside the handler body.

  4. **A clean handler-exception path.** `:counter/throw` deliberately
     throws — but as a FEATURE being exercised, not a buggy demo. The
     router catches it, the cascade carries a
     `:rf.error/handler-exception` issue, and Xray's Issues lens
     surfaces it scoped to the frame it fired in. It is the supported
     way to make the error surface light up; the button is labelled as
     such."
  (:require [re-frame.core :as rf]))

;; ============================================================================
;; COFX — :testdeck/now  (wall-clock injection)
;; ============================================================================
;;
;; A single injection point for the clock so handlers stay pure. Tests
;; can override it; SSR can hydrate it. Registered here (rather than
;; calling `(.getTime (js/Date.))` inline) per Spec 002 §Cofx.

(rf/reg-cofx :testdeck/now
  {:doc "Inject the current wall-clock time (ms since epoch) into
         coeffects under `:testdeck/now`."}
  (fn cofx-testdeck-now [ctx]
    (rf/assoc-coeffect ctx :testdeck/now (.getTime (js/Date.)))))

;; ============================================================================
;; APP-DB SLICE
;; ============================================================================
;;
;; The counter feature owns the `:counter` slot. Seeded by the testdeck
;; panel's frame `:on-create` via `initial-db` so each frame starts from
;; an identical seed and diverges as the user clicks.

(def initial-db
  "Initial `:counter` slice. Merged into each frame's app-db at
  `:on-create`. `:show-parity?` and `:threshold` are local UI state held
  IN app-db (not a component-local atom) so they are frame-scoped and
  observable in Xray — toggling parity create/destroys the L2 sub, and
  the threshold drives the changeable-arg view."
  {:counter {:value         0
             :last-clicked  nil
             :show-parity?  true
             :threshold     5}})

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event-fx :counter/inc
  {:doc "Increment the counter and stamp the click time via the
         `:testdeck/now` cofx."}
  [(rf/inject-cofx :testdeck/now)]
  (fn handler-counter-inc [{:keys [db testdeck/now]} _ev]
    {:db (-> db
             (update-in [:counter :value] (fnil inc 0))
             (assoc-in  [:counter :last-clicked] now))}))

(rf/reg-event-fx :counter/dec
  {:doc "Decrement the counter and stamp the click time via the
         `:testdeck/now` cofx."}
  [(rf/inject-cofx :testdeck/now)]
  (fn handler-counter-dec [{:keys [db testdeck/now]} _ev]
    {:db (-> db
             (update-in [:counter :value] (fnil dec 0))
             (assoc-in  [:counter :last-clicked] now))}))

(rf/reg-event-db :counter/reset
  {:doc "Reset the counter value to zero (leaves the last-clicked stamp)."}
  (fn handler-counter-reset [db _ev]
    (assoc-in db [:counter :value] 0)))

(rf/reg-event-db :counter/throw
  {:doc "Clean handler-exception path. Throws so the router's catch
         surfaces a `:rf.error/handler-exception` issue in Xray's
         Issues lens, scoped to this frame. A FEATURE being exercised
         — the supported way to light up the error surface — NOT a
         buggy demo."}
  (fn handler-counter-throw [_db _ev]
    (throw (ex-info "Counter demo exception (intentional — exercises the error surface)"
                    {:feature :testdeck/counter
                     :surface :handler-exception}))))

;; --- UI state (frame-scoped, in app-db so Xray observes it) ---------------

(rf/reg-event-db :counter/toggle-parity
  {:doc "Toggle the parity pill. When on, the :counter/parity L2 sub is
         created (a live view reads it); when off it is destroyed
         (last reader gone) — the observable sub create/destroy
         surface."}
  (fn handler-counter-toggle-parity [db _ev]
    (update-in db [:counter :show-parity?] not)))

(rf/reg-event-db :counter/set-threshold
  {:doc "Set the show-greater-than threshold (the changeable argument to
         the `:counter/greater-than?` dynamic sub)."}
  (fn handler-counter-set-threshold [db [_ n]]
    (assoc-in db [:counter :threshold] n)))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

;; L1 — reads app-db directly. Cascaded ✗ (app-db input).
(rf/reg-sub :counter/value
  (fn [db _] (get-in db [:counter :value])))

(rf/reg-sub :counter/last-clicked
  (fn [db _] (get-in db [:counter :last-clicked])))

(rf/reg-sub :counter/show-parity?
  (fn [db _] (get-in db [:counter :show-parity?])))

(rf/reg-sub :counter/threshold
  (fn [db _] (get-in db [:counter :threshold])))

;; L2 — derived. Cascaded ✓ (← :counter/value). Created when the panel's
;; parity pill is first rendered; destroyed when it is toggled off — the
;; observable create/destroy surface (behaviour 1 in the ns docstring).
(rf/reg-sub :counter/parity
  :<- [:counter/value]
  (fn [value _]
    (if (even? value) "even" "odd")))

;; L2 — DYNAMIC, parameterised by a threshold in the query vector.
;; `[:counter/greater-than? n]` cascades from `:counter/value`; the
;; panel reads `[:counter/greater-than? 5]` for the canonical
;; show-greater-than-five surface (behaviour 2). The argument is
;; changeable: a different `n` is a distinct cache entry over the same
;; registration.
(rf/reg-sub :counter/greater-than?
  :<- [:counter/value]
  (fn [value [_ threshold]]
    (> value threshold)))
