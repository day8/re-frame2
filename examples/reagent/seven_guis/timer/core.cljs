(ns seven-guis.timer.core
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
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas.
            ;; Loading the ns here registers its late-bind hooks so
            ;; rf/reg-app-schema resolves.
            [re-frame.schemas]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

(def tick-ms
  "Wall-clock delay between successive timer ticks. Kebab-case per the
   sibling `examples/reagent/long_running_work` convention."
  100)

;; ============================================================================
;; SCHEMA
;; ============================================================================

(def TimerState
  [:map
   [:elapsed-ms :int]                  ;; how long since the last reset
   [:duration-ms :int]                  ;; the slider's current value
   [:tick-active? :boolean]             ;; whether a tick is in flight
   [:tick-gen :int]])                   ;; generation token; bumped on Reset to retire stale ticks

;; EP-0002: reg-app-schema is context-required frame-local; a
;; bare ns-load call raises :rf.error/no-frame-context. This example runs in
;; :rf/default (see `run`/`reg-frame app-frame`), so name it explicitly so the
;; schema binds to the app frame whose commits it validates.
(with-frame :rf/default
  (rf/reg-app-schema [:timer] {:schema TimerState}))

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
;; a fresh tick under the new generation, so the chain continues. This
;; generation guard makes the 0.0 reading correct regardless of dispatch
;; timing — Reset uses ordinary `dispatch`, like every other UI handler.
;;
;; :timer/set-duration reuses the same generation mechanism: when the tick
;; chain has already stopped (elapsed reached the old duration) and the
;; user raises the duration, the handler bumps :tick-gen and arms one fresh
;; tick — resuming the chain without a Reset. This is what makes "the slider
;; changes the duration on the fly" hold after completion.

(rf/reg-event :timer/initialise
  {:doc "Seed the timer slice and start the periodic tick."}
  (fn handler-timer-initialise [{:keys [db]} _]
    {:db (assoc db :timer {:elapsed-ms   0
                           :duration-ms  10000
                           :tick-active? true
                           :tick-gen     0})
     :fx [[:dispatch-later {:ms tick-ms :event [:timer/tick 0]}]]}))

(rf/reg-event :timer/tick
  {:doc "Advance elapsed by one tick. Schedules the next tick if still ticking.
         Stale ticks (gen != current :tick-gen) are dropped — see header note."}
  (fn handler-timer-tick [{:keys [db]} [_ gen]]
    (let [{:keys [elapsed-ms duration-ms tick-active? tick-gen]} (:timer db)]
      (if (not= gen tick-gen)
        ;; Stale tick from a retired generation (Reset bumped :tick-gen). Drop it.
        {}
        (let [next-elapsed (min (+ elapsed-ms tick-ms) duration-ms)
              done?        (>= next-elapsed duration-ms)]
          (cond-> {:db (assoc-in db [:timer :elapsed-ms] next-elapsed)}
            ;; Continue ticking while not done and tick still active.
            (and tick-active? (not done?))
            (assoc :fx [[:dispatch-later {:ms tick-ms :event [:timer/tick gen]}]])))))))

(rf/reg-event :timer/set-duration
  {:doc "User dragged the slider. Update the duration, and — if the tick
         chain had already stopped because elapsed reached the *old*
         duration — re-arm it under a bumped generation so a longer
         duration resumes ticking without a Reset. Bumping :tick-gen
         retires any still-pending tick, so exactly one chain runs.
         If a chain is still live (elapsed < old duration) we just
         update the duration; the running tick keeps going against the
         new target."
   :schema [:cat [:= :timer/set-duration] :int]}
  (fn handler-timer-set-duration [{:keys [db]} [_ ms]]
    (let [{:keys [elapsed-ms duration-ms tick-active? tick-gen]} (:timer db)
          ;; The chain stops scheduling once elapsed reaches duration
          ;; (see :timer/tick's `done?`). Detect that stopped state.
          was-stopped? (>= elapsed-ms duration-ms)
          ;; Re-arm only when stopped, still active, and the new
          ;; duration leaves elapsed below target (room to advance).
          rearm?       (and was-stopped? tick-active? (> ms elapsed-ms))
          next-gen     (if rearm? (inc tick-gen) tick-gen)
          db'          (cond-> (assoc-in db [:timer :duration-ms] ms)
                         rearm? (assoc-in [:timer :tick-gen] next-gen))]
      (cond-> {:db db'}
        rearm? (assoc :fx [[:dispatch-later {:ms tick-ms :event [:timer/tick next-gen]}]])))))

(rf/reg-event :timer/reset
  {:doc "User clicked Reset. Zero elapsed, retire any in-flight tick by
         bumping :tick-gen, and arm a fresh tick under the new generation."}
  (fn handler-timer-reset [{:keys [db]} _]
    (let [next-gen (inc (get-in db [:timer :tick-gen]))]
      {:db (-> db
               (assoc-in [:timer :elapsed-ms]   0)
               (assoc-in [:timer :tick-active?] true)
               (assoc-in [:timer :tick-gen]     next-gen))
       :fx [[:dispatch-later {:ms tick-ms :event [:timer/tick next-gen]}]]})))

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
      ;; Ordinary `dispatch` — the idiom for every UI event handler.
      ;; The 0.0 reading is made observable by the :tick-gen generation
      ;; guard (see EVENTS header note), not by synchronous dispatch.
      [:button {:data-testid "timer-reset"
                :on-click #(dispatch [:timer/reset])} "Reset"]]]))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
(defonce react-root (atom nil))

;; EP-0002: under the carried invariant the runtime never
;; synthesises a frame from absence — an app must establish its frame
;; explicitly. `init!` installs the adapter (it does NOT create the frame),
;; `reg-frame` registers the app frame, the boot dispatch runs under
;; `with-frame`, and the render is wrapped in a `frame-provider` so every
;; in-tree `dispatch`/`subscribe` resolves to the app frame. Matches the
;; canonical mount in examples/reagent/counter/core.cljs.
(def app-frame :rf/default)

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame app-frame {})
  (rf/with-frame app-frame
    (rf/dispatch-sync [:timer/initialise]))
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider-existing {:frame app-frame}
                 [timer-view]])))
