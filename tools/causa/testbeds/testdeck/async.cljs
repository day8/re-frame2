(ns testdeck.async
  "Testdeck ASYNC & ERRORS feature (rf2-6qgbs.1) — an async effect plus
  an error-surfacing path. A TEST surface: the slow request and the
  failed request are FEATURES being exercised (they light up Causa's
  Trace / Issues lenses), not deliberate bugs.

  Shared by both testdeck surfaces (two-frame isolation testbed +
  step-deck rf2-6qgbs.2). Registered once globally; resolves per-frame
  via the dispatch envelope's `:frame` — the response lands back on the
  frame the request fired from, because subs are per-frame and the
  reply must land where the request originated.

  ## What this surface exercises

    Fetch        — fires `:testdeck.async/fetch`, an in-process async
                   fx that resolves ~SLOW-MS later with a value. The
                   ~600ms delay exceeds Spec 009's slow-effect
                   threshold, so each fetch surfaces legitimately in
                   Causa's Issues lens as a slow effect. NOT a bug — a
                   normal slow request, calibrated to demonstrate the
                   affordance.
    Fetch (fail) — the same fx with `:fail? true`; it rejects, and the
                   failure cascade carries the error into app-db and
                   surfaces in the Issues lens.

  ## In-process async fx

  Nothing under `tools/causa/testbeds/` ships in production, so the
  async fx is a `js/setTimeout`-backed mock rather than real HTTP. The
  originating frame id is carried through the closure (Spec 002 §`:fx`
  ordering — the fx ctx's `:frame`) so the deferred reply dispatch
  lands on the same frame, keeping the two-frame testbed's per-frame
  isolation intact."
  (:require [re-frame.core :as rf]))

;; ============================================================================
;; TIMING
;; ============================================================================

(def SLOW-MS
  "Async fx delay. ~600ms is the load-bearing figure: long enough to
  exceed Spec 009's slow-effect threshold (so Causa's Issues lens
  surfaces each fetch as a legitimate, non-bug slow effect), short
  enough not to make the surface tedious to drive."
  600)

;; ============================================================================
;; APP-DB SLICE
;; ============================================================================

(def initial-db
  "Initial `:async` slice. Merged into each frame's app-db at
  `:on-create`."
  {:async {:status :idle      ;; :idle | :loading | :loaded | :error
           :value  nil
           :error  nil
           :count  0}})

;; ============================================================================
;; ASYNC FX — :testdeck.async/fetch
;; ============================================================================
;;
;; In-process mock: resolves ~SLOW-MS after dispatch with the wall-clock
;; time, or rejects when `:fail?` is set. The frame id from the fx ctx
;; is captured in the closure so the deferred reply lands on the
;; originating frame.

(rf/reg-fx :testdeck.async/fetch
  (fn fx-testdeck-async-fetch [{:keys [frame]} {:keys [fail?]}]
    (js/setTimeout
      (fn []
        (if fail?
          (rf/dispatch [:testdeck.async/failed "Async request failed (mock)"]
                       {:frame frame})
          (rf/dispatch [:testdeck.async/loaded
                        (str "Fetched @ " (.toISOString (js/Date.)))]
                       {:frame frame})))
      SLOW-MS)))

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event-fx :testdeck.async/start
  {:doc "Begin an async fetch. Marks :loading and issues the slow fx.
         `{:fail? true}` drives the rejection path."}
  (fn handler-async-start [{:keys [db]} [_ {:keys [fail?]}]]
    {:db (-> db
             (assoc-in [:async :status] :loading)
             (update-in [:async :count] (fnil inc 0)))
     :fx [[:testdeck.async/fetch {:fail? (boolean fail?)}]]}))

(rf/reg-event-db :testdeck.async/loaded
  {:doc "Successful async reply. Lands on the originating frame."}
  (fn handler-async-loaded [db [_ value]]
    (-> db
        (assoc-in [:async :status] :loaded)
        (assoc-in [:async :value]  value)
        (assoc-in [:async :error]  nil))))

(rf/reg-event-db :testdeck.async/failed
  {:doc "Failed async reply. Lands on the originating frame and feeds
         the error surface."}
  (fn handler-async-failed [db [_ message]]
    (-> db
        (assoc-in [:async :status] :error)
        (assoc-in [:async :value]  nil)
        (assoc-in [:async :error]  message))))

(rf/reg-event-db :testdeck.async/reset
  {:doc "Reset the async slice to idle."}
  (fn handler-async-reset [db _ev]
    (assoc db :async (:async initial-db))))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :testdeck.async/status
  (fn [db _] (get-in db [:async :status])))

(rf/reg-sub :testdeck.async/value
  (fn [db _] (get-in db [:async :value])))

(rf/reg-sub :testdeck.async/error
  (fn [db _] (get-in db [:async :error])))

(rf/reg-sub :testdeck.async/count
  (fn [db _] (get-in db [:async :count])))
