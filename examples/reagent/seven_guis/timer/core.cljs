(ns seven-guis.timer.core
  "7GUIs #4 — Timer.

   A progress bar that fills as time elapses, a numeric display of elapsed
   time, a slider that sets the duration, and a Reset button.

   Rules:
     - The progress bar fills from 0 to 100% over the duration.
     - The slider changes the duration on the fly: shrinking it advances the
       bar to full immediately when elapsed already exceeds duration.
     - Reset sets elapsed back to zero.

   This is the 7GUIs test of time. A periodic event ticks elapsed time
   forward through the same dispatch pipeline as every other change, so the
   timer never lives outside the update model.

   What it shows:
   - `:dispatch-later` to schedule the next tick
   - One source of truth: elapsed time lives in app-db
   - Layered subscriptions deriving the progress percentage
   - A controlled slider that dispatches on every change

   Terms: events, subscriptions, app-db, frames — docs/guide/glossary.md."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Requiring this registers the hooks that make `rf/reg-app-schema` work.
            [re-frame.schemas]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

(def tick-ms
  "Wall-clock delay in milliseconds between successive timer ticks."
  100)

;; ============================================================================
;; SCHEMA
;; ============================================================================

(def TimerState
  [:map
   [:elapsed-ms :int]                  ;; milliseconds since the last reset
   [:duration-ms :int]                 ;; the slider's current value
   [:tick-active? :boolean]            ;; whether a tick is in flight
   [:tick-gen :int]])                  ;; generation token; bumped on Reset to retire stale ticks

;; `reg-app-schema` binds the schema to a frame, so it needs a frame in scope.
;; `with-frame` names one. We use `:rf/default`, the same id the render root's
;; `frame-provider` uses (see `run`), so the schema lands on the frame whose
;; commits it validates. Schemas: docs/guide/how-to/validate-with-schemas.md.
(with-frame :rf/default
  (rf/reg-app-schema [:timer] {:schema TimerState}))

;; ============================================================================
;; EVENTS
;; ============================================================================
;;
;; The tick chain runs on `:dispatch-later`, which has no cancel API. So we
;; guard ticks with a generation token. Each tick carries the :tick-gen it was
;; scheduled under. A handler that wants to retire the in-flight tick bumps
;; :tick-gen; when that stale tick finally fires its gen no longer matches, so
;; it no-ops. The handler then schedules a fresh tick under the new generation,
;; keeping exactly one chain alive.
;;
;; Reset uses this to show 0.0 reliably: it zeros :elapsed-ms and bumps the
;; generation, so a tick scheduled just before Reset can't re-increment elapsed
;; after the zeroing. :timer/set-duration uses it too: when the chain has
;; already stopped (elapsed reached the old duration) and the user raises the
;; duration, it bumps the generation and arms one fresh tick — resuming the
;; chain without a Reset. That is what makes the slider change duration on the
;; fly even after the timer has finished.

(rf/reg-event :timer/initialise
  {:doc "Seed the timer slice and start the periodic tick."}
  (fn handler-timer-initialise [{:keys [db]} _]
    {:db (assoc db :timer {:elapsed-ms   0
                           :duration-ms  10000
                           :tick-active? true
                           :tick-gen     0})
     :fx [[:dispatch-later {:ms tick-ms :event [:timer/tick 0]}]]}))

(rf/reg-event :timer/tick
  {:doc "Advance elapsed by one tick, scheduling the next while still ticking.
         A tick whose generation no longer matches :tick-gen is dropped."}
  (fn handler-timer-tick [{:keys [db]} [_ gen]]
    (let [{:keys [elapsed-ms duration-ms tick-active? tick-gen]} (:timer db)]
      (if (not= gen tick-gen)
        ;; Stale tick from a retired generation. Drop it.
        {}
        (let [next-elapsed (min (+ elapsed-ms tick-ms) duration-ms)
              done?        (>= next-elapsed duration-ms)]
          (cond-> {:db (assoc-in db [:timer :elapsed-ms] next-elapsed)}
            ;; Continue ticking while not done and tick still active.
            (and tick-active? (not done?))
            (assoc :fx [[:dispatch-later {:ms tick-ms :event [:timer/tick gen]}]])))))))

(rf/reg-event :timer/set-duration
  {:doc "User dragged the slider. Update the duration. If the tick chain had
         already stopped (elapsed reached the old duration) and the new
         duration leaves room to advance, re-arm one fresh tick under a
         bumped generation so it resumes without a Reset. While a chain is
         still live, just update the duration — the running tick keeps going
         against the new target."
   :schema [:cat [:= :timer/set-duration] :int]}
  (fn handler-timer-set-duration [{:keys [db]} [_ ms]]
    (let [{:keys [elapsed-ms duration-ms tick-active? tick-gen]} (:timer db)
          ;; The chain stops scheduling once elapsed reaches duration.
          was-stopped? (>= elapsed-ms duration-ms)
          ;; Re-arm only when stopped, still active, and the new duration
          ;; leaves elapsed below target (room to advance).
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
      [:button {:data-testid "timer-reset"
                :on-click #(dispatch [:timer/reset])} "Reset"]]]))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; The React root is held in an atom and created lazily inside `run`, not at
;; ns-load. ns-load must produce no DOM side effects, so co-required example
;; namespaces don't race `create-root` onto the shared `#app` element.
(defonce react-root (atom nil))

;; The frame lifecycle lives in one place: the `frame-provider` below. On first
;; mount it creates the frame, applies its config, and runs `:initial-events`
;; once to seed app-db. From then on every `dispatch`/`subscribe` in the tree
;; resolves to that frame. On hot reload it reuses the existing frame and skips
;; re-seeding, so the timer keeps ticking across re-mounts.
;; Frames: docs/guide/concepts/frames.md.
;;
;; `app-frame` is just an id we pick — `:rf/default`, the same id the schema
;; block above binds to.
(def app-frame :rf/default)

(defn run []
  ;; `init!` installs the reactive adapter for the process. It does not create
  ;; the frame — the `frame-provider` below does that.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :initial-events [[:timer/initialise]]}
                 [timer-view]])))
