(ns timer.timer
  "7GUIs #4 — Timer.

   A progress bar that fills as time elapses, a numeric display of elapsed
   time, a slider that sets the duration, and a Reset button.

   Rules:
     - The progress bar fills from 0 to 100% over the duration.
     - The slider changes the duration on the fly: shrinking it should
       advance the bar past the threshold immediately if elapsed > duration.
     - Reset sets elapsed back to zero.

   The 7GUIs page calls this out as a test of *concurrency / time*. The
   classic trap is to handle the timer outside the framework's update model,
   creating races. The re-frame2 approach: a periodic event ticks elapsed
   time forward through the same dispatch pipeline as everything else.

   Demonstrates:
   - `:dispatch-later` for timer ticks                    (CP-1, effect-map)
   - One source of truth (elapsed)                        (state-in-app-db)
   - Layered subs for derived progress %                   (CP-2)
   - Controlled-input slider via dispatch on change       (CP-4)"
  (:require [reagent2.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas.
            ;; Loading the ns here registers its late-bind hooks so
            ;; rf/reg-app-schema resolves.
            [re-frame.schemas]
            [re-frame.views]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

(def TICK-MS 100)

;; ============================================================================
;; SCHEMA
;; ============================================================================

(def TimerState
  [:map
   [:elapsed-ms :int]                  ;; how long since the last reset
   [:duration-ms :int]                  ;; the slider's current value
   [:tick-active? :boolean]             ;; whether a tick is in flight
   [:tick-gen :int]])                   ;; generation token; bumped on Reset to retire stale ticks

(rf/reg-app-schema [:timer] TimerState)

;; ============================================================================
;; EVENTS
;; ============================================================================
;;
;; The tick chain is driven by `:dispatch-later`, which has no cancel API.
;; To avoid a race where Reset zeros :elapsed-ms but a previously-scheduled
;; tick lands ~milliseconds later and re-increments it (causing the DOM to
;; never observably show 0.0), each tick carries the :tick-gen value it was
;; scheduled with. Reset bumps :tick-gen, so any in-flight tick from the
;; previous generation no-ops when it eventually fires. Reset also schedules
;; a fresh tick under the new generation, so the chain continues.
;;
;; The Reset *click handler* (see view) calls `dispatch-sync`, not
;; `dispatch`. Under Reagent 2 (flushSync render), this guarantees the
;; app-db update and DOM commit complete before the next scheduled tick
;; (or before a polling test observer reads the DOM), so the brief 0.0
;; reading is observable. The :tick-gen guard above and the synchronous
;; commit here address two distinct races and are both required.

(rf/reg-event-fx :timer/initialise
  {:doc "Seed the timer slice and start the periodic tick."}
  (fn handler-timer-initialise [{:keys [db]} _]
    {:db (assoc db :timer {:elapsed-ms   0
                           :duration-ms  10000
                           :tick-active? true
                           :tick-gen     0})
     :fx [[:dispatch-later {:ms TICK-MS :event [:timer/tick 0]}]]}))

(rf/reg-event-fx :timer/tick
  {:doc "Advance elapsed by one tick. Schedules the next tick if still ticking.
         Stale ticks (gen != current :tick-gen) are dropped — see header note."}
  (fn handler-timer-tick [{:keys [db]} [_ gen]]
    (let [{:keys [elapsed-ms duration-ms tick-active? tick-gen]} (:timer db)]
      (if (not= gen tick-gen)
        ;; Stale tick from a retired generation (Reset bumped :tick-gen). Drop it.
        {}
        (let [next-elapsed (min (+ elapsed-ms TICK-MS) duration-ms)
              done?        (>= next-elapsed duration-ms)]
          (cond-> {:db (assoc-in db [:timer :elapsed-ms] next-elapsed)}
            ;; Continue ticking while not done and tick still active.
            (and tick-active? (not done?))
            (assoc :fx [[:dispatch-later {:ms TICK-MS :event [:timer/tick gen]}]])))))))

(rf/reg-event-db :timer/set-duration
  {:doc "User dragged the slider."
   :schema [:cat [:= :timer/set-duration] :int]}
  (fn handler-timer-set-duration [db [_ ms]]
    (assoc-in db [:timer :duration-ms] ms)))

(rf/reg-event-fx :timer/reset
  {:doc "User clicked Reset. Zero elapsed, retire any in-flight tick by
         bumping :tick-gen, and arm a fresh tick under the new generation."}
  (fn handler-timer-reset [{:keys [db]} _]
    (let [next-gen (inc (get-in db [:timer :tick-gen]))]
      {:db (-> db
               (assoc-in [:timer :elapsed-ms]   0)
               (assoc-in [:timer :tick-active?] true)
               (assoc-in [:timer :tick-gen]     next-gen))
       :fx [[:dispatch-later {:ms TICK-MS :event [:timer/tick next-gen]}]]})))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :timer/elapsed-ms  (fn [db _] (get-in db [:timer :elapsed-ms])))
(rf/reg-sub :timer/duration-ms (fn [db _] (get-in db [:timer :duration-ms])))

(rf/reg-sub :timer/elapsed-seconds
  :<- [:timer/elapsed-ms]
  (fn [ms _] (.toFixed (/ ms 1000.0) 1)))

(rf/reg-sub :timer/progress-pct
  {:doc "Fraction of duration elapsed, clamped to [0, 100]."}
  :<- [:timer/elapsed-ms]
  :<- [:timer/duration-ms]
  (fn [[e d] _]
    (cond
      (zero? d) 100
      :else (min 100 (* 100 (/ e d))))))

;; ============================================================================
;; VIEW
;; ============================================================================

(reg-view timer-view []
  (let [progress @(subscribe [:timer/progress-pct])
        seconds  @(subscribe [:timer/elapsed-seconds])
        duration @(subscribe [:timer/duration-ms])]
    [:div.timer
     [:div.row
      [:label "Elapsed time:"]
      [:div.bar {:style {:width "300px" :background "#ddd"}}
       [:div.fill {:style {:width  (str progress "%")
                           :height "20px"
                           :background "#4a9"}}]]]
     [:div.row [:label {:data-testid "timer-elapsed"} seconds " s"]]
     [:div.row
      [:label "Duration: "]
      [:input {:type      "range"
               :min       0 :max 30000 :step 100
               :value     duration
               :on-change #(dispatch [:timer/set-duration
                                      (js/parseInt (.. % -target -value))])}]
      [:span (.toFixed (/ duration 1000.0) 1) " s"]]
     [:div.row
      ;; dispatch-sync (not dispatch): under Reagent 2's flushSync render
      ;; model, this guarantees app-db is updated and the DOM commits the
      ;; 0.0 reading before control returns to the browser — and crucially
      ;; before the next scheduled :timer/tick can fire. With async dispatch
      ;; the post-Reset DOM commit could lag the next tick (and the test's
      ;; 50ms poll), causing the brief 0.0 window to be missed.
      [:button {:data-testid "timer-reset"
                :on-click #(rf/dispatch-sync [:timer/reset])} "Reset"]]]))

;; ============================================================================
;; HEADLESS TESTS  (scheduling-light; we don't drive real time, just verify
;;                  the event/sub graph)
;; ============================================================================

(defn timer-tests []
  (with-frame [f (rf/make-frame {:on-create    [:timer/initialise]
                                    :fx-overrides {:dispatch-later nil}})]   ;; suppress real timers
    ;; Initial state.
    (assert (zero? (rf/compute-sub [:timer/elapsed-ms] (rf/get-frame-db f))))
    (assert (= 10000 (rf/compute-sub [:timer/duration-ms] (rf/get-frame-db f))))

    ;; Manual ticks → elapsed advances; progress derives correctly.
    ;; (Pass the current generation so the ticks aren't dropped as stale.)
    (dotimes [_ 50]
      (let [gen (get-in (rf/get-frame-db f) [:timer :tick-gen])]
        (rf/dispatch-sync [:timer/tick gen] {:frame f})))
    (assert (= 5000 (rf/compute-sub [:timer/elapsed-ms] (rf/get-frame-db f))))
    (assert (= 50.0 (rf/compute-sub [:timer/progress-pct] (rf/get-frame-db f))))

    ;; Shrinking duration below elapsed → progress clamps to 100, ticks stop on next tick.
    (rf/dispatch-sync [:timer/set-duration 4000] {:frame f})
    (assert (= 100 (rf/compute-sub [:timer/progress-pct] (rf/get-frame-db f))))

    ;; Reset → elapsed back to zero, generation bumped.
    (let [gen-before (get-in (rf/get-frame-db f) [:timer :tick-gen])]
      (rf/dispatch-sync [:timer/reset] {:frame f})
      (assert (zero? (rf/compute-sub [:timer/elapsed-ms] (rf/get-frame-db f))))
      (assert (= (inc gen-before)
                 (get-in (rf/get-frame-db f) [:timer :tick-gen])))
      ;; A stale tick (old generation) is dropped — :elapsed-ms stays at 0.
      (rf/dispatch-sync [:timer/tick gen-before] {:frame f})
      (assert (zero? (rf/compute-sub [:timer/elapsed-ms] (rf/get-frame-db f)))))))

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-slim-adapter/adapter)
  (rf/dispatch-sync [:timer/initialise])
  (rdc/render root [timer-view]))
