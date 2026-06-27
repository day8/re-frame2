(ns long-running-work.views
  "The UI for the long-running-work example.

   Three pieces on screen:

   - A control panel — Start / Cancel / Reset, plus a Hide button.
   - A progress bar — the overall fraction of items done across every
     shard.
   - A per-shard breakdown — one little bar per worker, so you can
     watch the three children racing along independently.

   The interesting one is Hide. It toggles a wrapper that mounts and
   unmounts the work-bench, and when that wrapper unmounts, its
   `r/with-let` cleanup dispatches `:cancel` into `:work/flow`. That's
   the whole recipe for tying cancellation to a component's lifecycle:
   one ordinary dispatch. The parent's `:working` state handles it by
   transitioning out, which tears down every child it spawned. The
   workers, meanwhile, have never heard of React — this single
   dispatch is the only seam where React's lifecycle reaches them."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [long-running-work.worker])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; SUB-COMPONENTS
;; ============================================================================

(reg-view ^{:doc "The overall progress bar. Reads :work/progress-fraction
                  (a number from 0.0 to 1.0) and grows a div's width to
                  match."}
          progress-bar []
  (let [frac     @(subscribe [:work/progress-fraction])
        done     @(subscribe [:work/items-done])
        total    @(subscribe [:work/total-items])
        state    @(subscribe [:work/flow-state])
        percent  (int (* 100 (or frac 0)))]
    [:div.progress-bar-wrapper
     {:style {:margin "1em 0"}}
     [:div {:style {:display "flex" :justify-content "space-between" :margin-bottom "4px"}}
      [:span {:data-testid "progress-label"}
       (str "Progress: " done " / " total " items")]
      [:span {:data-testid "progress-percent"} (str percent " %")]]
     [:div {:style {:width "100%" :height "20px"
                    :background "#eee" :border-radius "4px"
                    :overflow "hidden"}}
      [:div {:data-testid "progress-fill"
             :style {:width (str percent "%")
                     :height "100%"
                     :background (case state
                                   :complete  "#4caf50"
                                   :cancelled "#ff9800"
                                   :error     "#f44336"
                                   :working   "#2196f3"
                                   "#9e9e9e")
                     :transition "width 60ms linear"}}]]]))

(reg-view ^{:doc "The per-shard breakdown. One little bar per worker, so
                  you can watch the parallel children making their own
                  independent progress."}
          shard-breakdown []
  (let [progress @(subscribe [:work/progress-map])
        total    (-> @(subscribe [:work/flow-data]) :total)]
    [:div.shard-breakdown
     {:style {:margin "1em 0"}}
     [:h3 "Per-shard progress"]
     (for [[shard-id processed] (sort progress)]
       ^{:key shard-id}
       [:div {:style {:display "flex" :align-items "center" :gap "8px" :margin "4px 0"}}
        [:span {:style {:font-family "monospace" :min-width "40px"}
                :data-testid (str "shard-label-" (name shard-id))}
         (name shard-id)]
        [:div {:style {:flex 1 :height "10px" :background "#eee" :border-radius "3px"}}
         [:div {:data-testid (str "shard-fill-" (name shard-id))
                :style {:width  (str (if (pos? total)
                                       (int (* 100 (/ processed total)))
                                       0) "%")
                        :height "100%"
                        :background "#3f51b5"
                        :transition "width 60ms linear"}}]]
        [:span {:style {:font-family "monospace" :min-width "60px" :text-align "right"}
                :data-testid (str "shard-counter-" (name shard-id))}
         (str processed " / " total)]])]))

(reg-view ^{:doc "The status line — the parent machine's current :state,
                  plus its recorded :outcome once it lands in a terminal
                  state (the little badge at the end)."}
          status-line []
  (let [state   @(subscribe [:work/flow-state])
        outcome @(subscribe [:work/outcome])]
    [:p {:data-testid "status-line"}
     [:strong "State: "]
     [:span {:data-testid "state-value"} (when state (name state))]
     (when outcome
       [:span {:style {:margin-left "1em"}}
        [:strong "Outcome: "]
        [:span {:data-testid "outcome-value"} (name outcome)]])]))

(reg-view ^{:doc "The control panel. Three buttons, three events into
                  the parent machine:

                  - Start  → [:work/flow [:start]]. The parent goes
                             :idle → :working and spawns 3 children
                             via :spawn-all.
                  - Cancel → [:work/flow [:cancel]]. The parent goes
                             :working → :cancelled, and leaving :working
                             fires :rf.machine/destroy at every child
                             still standing (that's the :spawn-all
                             :exit cascade at work).
                  - Reset  → [:work/flow [:reset]]. Back to :idle with
                             :progress wiped clean."}
          controls []
  (let [running? @(subscribe [:work/running?])
        state    @(subscribe [:work/flow-state])
        idle?    (= state :idle)]
    [:div.controls
     {:style {:display "flex" :gap "8px" :margin "1em 0"}}
     [:button {:data-testid "start"
               :disabled    (not idle?)
               :on-click    #(dispatch [:work/flow [:start]])}
      "Start work"]
     [:button {:data-testid "cancel"
               :disabled    (not running?)
               :on-click    #(dispatch [:work/flow [:cancel]])}
      "Cancel"]
     [:button {:data-testid "reset"
               :disabled    (or running? idle?)
               :on-click    #(dispatch [:work/flow [:reset]])}
      "Reset"]]))

;; ============================================================================
;; WORK-BENCH WRAPPER — where unmount becomes cancellation
;; ============================================================================

(reg-view ^{:doc "The work-bench — the widget that holds the whole demo,
                  and the one that vanishes when you click 'Hide'. Watch
                  its `r/with-let` cleanup: on unmount it dispatches
                  [:work/flow [:cancel]] into the parent. The parent's
                  :cancel transition leaves :working, that exit fires
                  :rf.machine/destroy at every surviving child, and the
                  work tidies itself away. One dispatch — the single
                  point where React's lifecycle reaches the machines."}
          work-bench []
  (r/with-let [_ nil]
    [:div.work-bench
     {:style {:padding "1em" :border "1px solid #ccc" :border-radius "6px"}}
     [:h2 "Long-running work"]
     [:p "Three workers process 100 items each in parallel.
          Each worker yields to the browser between chunks via "
      [:code ":after"] " so the UI stays responsive."]
     [status-line]
     [controls]
     [progress-bar]
     [shard-breakdown]]
    ;; Reagent runs this finally-clause when the component unmounts.
    ;; It does one thing — dispatch :cancel at the parent — and the
    ;; :working state's :cancel transition handles all the rest.
    (finally
      (dispatch [:work/flow [:cancel]]))))

;; ============================================================================
;; ROOT VIEW — Show/Hide toggle around the work-bench
;; ============================================================================
;;
;; Note where the Show/Hide flag lives: a plain boolean in app-db, kept
;; well away from the :work/flow machine. Visibility is the view's
;; business, not the machine's — the view simply renders the work-bench
;; or it doesn't. Hit Hide while a job is running and the dominoes fall:
;; component unmounts -> with-let's finally -> dispatch :cancel ->
;; parent leaves :working -> cascade tears every child down.

(rf/reg-event :ui/initialise
  (fn [{:keys [db]} _]
    {:db (assoc db :ui {:show-bench? true})}))

(rf/reg-event :ui/toggle-bench
  (fn [{:keys [db]} _]
    {:db (update-in db [:ui :show-bench?] not)}))

(rf/reg-sub :ui/show-bench?
  (fn [db _] (get-in db [:ui :show-bench?] true)))

(reg-view ^{:doc "The top-level root: a Show/Hide button and the
                  work-bench, rendered only when shown. Hiding the bench
                  unmounts it, and that's what kicks off the cascade —
                  see work-bench above."}
          root-view []
  (let [show? @(subscribe [:ui/show-bench?])]
    [:div.app
     {:style {:max-width "700px" :margin "2em auto"
              :font-family "sans-serif"}}
     [:h1 "re-frame2 — Pattern-LongRunningWork"]
     [:p "Demonstrates "
      [:code ":spawn-all"]
      ": one parent coordinator, N parallel workers, declarative
       spawn-and-join with cooperative cancellation."]
     [:button {:data-testid "toggle-bench"
               :on-click    #(dispatch [:ui/toggle-bench])
               :style       {:margin "0 0 1em 0"}}
      (if show? "Hide work-bench (cancels & unmounts)" "Show work-bench")]
     (when show?
       [work-bench])]))
