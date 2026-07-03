(ns seven-guis.timer.core
  "7GUIs #4 — Timer.

   A progress bar that fills, the elapsed seconds beside it, a slider to set
   the duration, and a Reset button. Time itself is the interesting part.

   The trick: time advances the same way everything else does — through a
   dispatched event. A periodic `:timer/tick` nudges elapsed-ms forward, so
   the clock lives *inside* the update loop rather than off to the side
   mutating state behind the framework's back. No special path for time.

   The rules are simple:
     - The bar fills from 0 to 100% over the duration.
     - The slider retargets the duration live: drag it below elapsed and the
       bar jumps to full; drag it back up and the timer picks up again.
     - Reset puts elapsed back to zero.

   What it shows off:
   - `:dispatch-later` to schedule the next tick
   - One source of truth: elapsed time is just a number in app-db
   - Subscriptions layered to derive the progress percentage
   - A controlled slider that dispatches on every drag

   Terms: events, subscriptions, app-db, frames — docs/core/glossary.md."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Pulling this in registers the hooks that teach the frame how to
            ;; validate against a schema — that's what makes `rf/reg-app-schema` work.
            [re-frame.schemas]
            [re-frame.adapter.reagent :as reagent-adapter]))

(def tick-ms
  "Wall-clock delay, in milliseconds, between one tick and the next. 100ms is
   smooth enough to look continuous without flooding the loop with events."
  100)

;; ============================================================================
;; SCHEMA
;; ============================================================================

;; The whole timer is four numbers. That's it — no objects, no timer handle,
;; nothing hiding outside app-db. This schema spells them out, and the runtime
;; checks every commit against it.
(def TimerState
  [:map
   [:elapsed-ms :int]                  ;; milliseconds counted since the last reset
   [:duration-ms :int]                 ;; where the slider is parked
   [:tick-active? :boolean]            ;; is a tick chain currently running?
   [:tick-gen :int]])                  ;; generation token — the trick for retiring stale ticks (see below)

;; A schema belongs to a frame, so we need a frame in scope to register it.
;; `with-frame` names one. We use `:rf/default` — the same id the render root's
;; `frame-provider` picks (see `run`) — so the schema guards the very frame
;; whose commits it's meant to validate. If you want the why and the how of
;; schemas, that's docs/core/how-to/validate-with-schemas.md.
(rf/with-frame :rf/default
  (rf/reg-app-schema [:timer] {:schema TimerState}))

;; ============================================================================
;; EVENTS
;; ============================================================================
;;
;; Here's the wrinkle that shapes everything below. We schedule each tick with
;; `:dispatch-later`, and `:dispatch-later` is fire-and-forget — there's no
;; handle to cancel a tick once it's in the air. So how do we ever *stop* a
;; tick, or start a clean one over the top of it?
;;
;; The answer is a generation token, `:tick-gen`. Think of it as a wristband at
;; a club: every tick is stamped with the generation it was scheduled under,
;; and when it finally fires it checks whether its stamp still matches the one
;; in app-db. Want to retire whatever's in flight? Bump `:tick-gen`. The old
;; tick still goes off — we can't stop that — but its wristband is now stale,
;; so it quietly does nothing. Then we schedule a fresh tick under the new
;; generation. Net result: exactly one live chain, ever.
;;
;; Two handlers lean on this:
;;   - Reset bumps the generation so a tick scheduled a moment earlier can't
;;     sneak elapsed back up after we've zeroed it. The display stays at 0.0.
;;   - set-duration bumps it to *revive* a finished timer: if the chain already
;;     stopped and the user drags the duration up, one fresh tick under the new
;;     generation gets it moving again — no Reset needed. That's the on-the-fly
;;     slider behaviour the 7GUIs task asks for.

(rf/reg-event :timer/initialise
  {:doc "Set up the timer slice from nothing and kick off the first tick."}
  (fn handler-timer-initialise [{:keys [db]} _]
    {:db (assoc db :timer {:elapsed-ms   0
                           :duration-ms  10000
                           :tick-active? true
                           :tick-gen     0})
     :fx [[:dispatch-later {:ms tick-ms :event [:timer/tick 0]}]]}))

(rf/reg-event :timer/tick
  {:doc "Nudge elapsed forward by one tick and, if there's still road ahead,
         schedule the next one. A tick carrying a stale generation is ignored."}
  (fn handler-timer-tick [{:keys [db]} [_ gen]]
    (let [{:keys [elapsed-ms duration-ms tick-active? tick-gen]} (:timer db)]
      (if (not= gen tick-gen)
        ;; Wrong wristband — this tick belongs to a retired generation. Ignore it.
        {}
        (let [next-elapsed (min (+ elapsed-ms tick-ms) duration-ms)
              done?        (>= next-elapsed duration-ms)]
          (cond-> {:db (assoc-in db [:timer :elapsed-ms] next-elapsed)}
            ;; Keep the chain alive only while there's room left and we haven't been stopped.
            (and tick-active? (not done?))
            (assoc :fx [[:dispatch-later {:ms tick-ms :event [:timer/tick gen]}]])))))))

(rf/reg-event :timer/set-duration
  {:doc "The user dragged the slider, so retarget the duration. Two cases. If a
         tick chain is still running, we just write the new target and let the
         live tick race toward it. If the chain had already finished but the new
         duration leaves room to keep going, we revive it with one fresh tick
         under a bumped generation — see the EVENTS note above for why that bump
         matters."
   :schema [:cat [:= :timer/set-duration] :int]}
  (fn handler-timer-set-duration [{:keys [db]} [_ ms]]
    (let [{:keys [elapsed-ms duration-ms tick-active? tick-gen]} (:timer db)
          ;; The chain stops scheduling itself the moment elapsed catches duration.
          was-stopped? (>= elapsed-ms duration-ms)
          ;; Revive only if it had stopped, is still active, and the new target
          ;; sits ahead of where we are — i.e. there's actually somewhere to go.
          rearm?       (and was-stopped? tick-active? (> ms elapsed-ms))
          next-gen     (if rearm? (inc tick-gen) tick-gen)
          db'          (cond-> (assoc-in db [:timer :duration-ms] ms)
                         rearm? (assoc-in [:timer :tick-gen] next-gen))]
      (cond-> {:db db'}
        rearm? (assoc :fx [[:dispatch-later {:ms tick-ms :event [:timer/tick next-gen]}]])))))

(rf/reg-event :timer/reset
  {:doc "Reset was clicked. Zero elapsed, retire whatever tick is in flight by
         bumping :tick-gen, and start a clean chain under the new generation."}
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
;;
;; The two base subs just read raw numbers out of app-db. The ones above them
;; build on those — seconds and percentage are *derived*, never stored. The
;; `:<-` arrows wire each sub to its inputs, so it recomputes only when an
;; input actually changes. The view asks for the polished values and stays out
;; of the arithmetic. Subscriptions: docs/core/concepts/subscriptions.md.

(rf/reg-sub :timer/elapsed-ms  (fn [db _] (get-in db [:timer :elapsed-ms])))
(rf/reg-sub :timer/duration-ms (fn [db _] (get-in db [:timer :duration-ms])))

;; Elapsed milliseconds, dressed up as seconds with one decimal for display.
(rf/reg-sub :timer/elapsed-seconds
  :<- [:timer/elapsed-ms]
  (fn [ms _] (.toFixed (/ ms 1000.0) 1)))

(rf/reg-sub :timer/progress-pct
  {:doc "How far along we are, 0 to 100. A zero duration counts as full —
         otherwise we'd be dividing by zero, and nobody enjoys that."}
  :<- [:timer/elapsed-ms]
  :<- [:timer/duration-ms]
  (fn [[e d] _]
    (cond
      (zero? d) 100
      :else (min 100 (* 100 (/ e d))))))

;; ============================================================================
;; VIEW
;; ============================================================================

(rf/reg-view timer-view []
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

;; We stash the React root in an atom and build it lazily inside `run`, never at
;; ns-load. Loading a namespace shouldn't touch the DOM — if it did, two example
;; namespaces loaded together would both race to `create-root` the shared `#app`
;; element, and exactly one of them would win. Lazy keeps that drama away.
(defonce react-root (atom nil))

;; All the frame's lifecycle happens in one spot — the `frame-provider` below.
;; On first mount it creates the frame, applies its config, and fires
;; `:initial-events` once to seed app-db. After that, every `dispatch` and
;; `subscribe` in the tree resolves to this frame. On hot reload it reuses the
;; frame it already has and skips re-seeding, so the timer keeps right on ticking
;; while you edit. Frames: docs/core/concepts/frames.md.
;;
;; `app-frame` is just an id we chose — `:rf/default`, the same one the schema
;; block up top binds to.
(def app-frame :rf/default)

(defn run []
  ;; `init!` tells the runtime to render through the Reagent adapter. That's all
  ;; it does — it does *not* create a frame. The `frame-provider` below does that.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :initial-events [[:timer/initialise]]}
                 [timer-view]])))
