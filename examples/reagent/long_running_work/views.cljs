(ns long-running-work.views
  "Views for the long-running-work example.

   Three pieces:

   - The control panel: Start / Cancel / Reset / Hide buttons.
   - The progress bar: aggregate fraction of items done across all
     shards.
   - The per-shard breakdown: small bar per worker so the reader can
     see the three parallel children making independent progress.

   The 'Hide' button toggles a wrapper component that mounts /
   unmounts the work-bench. When the wrapper unmounts, its
   `r/with-let` cleanup fires a `:cancel` event into `:work/flow`.
   This is the canonical 're-frame2 way' to wire cooperative
   cancellation to a React lifecycle boundary: a registered event
   that the parent's :working state handles by transitioning out,
   triggering the standard exit cascade (which destroys every
   spawned child). The worker machines themselves are
   lifecycle-agnostic — they don't know anything about React. The
   one place the React lifecycle peeks through is this single
   dispatch in the wrapper's cleanup."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [long-running-work.worker])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

;; ============================================================================
;; SUB-COMPONENTS
;; ============================================================================

(reg-view ^{:doc "Aggregate progress bar. Reads the :work/progress-fraction
                  sub (0.0 .. 1.0) and draws a div whose width scales."}
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

(reg-view ^{:doc "Per-shard breakdown. Three little bars, one per
                  worker, so the reader can see the parallel children
                  making independent progress."}
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

(reg-view ^{:doc "Status line — shows the parent machine's :state and
                  the recorded :outcome (the terminal-state badge)."}
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

(reg-view ^{:doc "Control panel. Start / Cancel / Reset.

                  - Start: dispatches [:work/flow [:start]] — parent
                           transitions :idle → :working, spawns 3
                           children via :invoke-all.
                  - Cancel: dispatches [:work/flow [:cancel]] — parent
                           transitions :working → :cancelled, the
                           exit cascade emits :rf.machine/destroy for
                           every surviving child (per the
                           :invoke-all desugared :exit).
                  - Reset: dispatches [:work/flow [:reset]] — returns
                           the parent to :idle with cleared :progress."}
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
;; WORK-BENCH WRAPPER — demonstrates the unmount cascade
;; ============================================================================

(reg-view ^{:doc "The work-bench — the actual demo widget. This is the
                  view that *gets unmounted* when the user clicks
                  'Hide'. Its `r/with-let` cleanup is the load-bearing
                  hook for the unmount cascade: on unmount, it
                  dispatches [:work/flow [:cancel]] into the parent
                  machine. The parent's :working state's :cancel
                  transition exits :working, the :invoke-all
                  desugared :exit fires :rf.machine/destroy for every
                  surviving child, and the work goes away cleanly.

                  The worker machines themselves don't know anything
                  about React — they're host-agnostic. The only
                  place the React lifecycle peeks through is this
                  one dispatch in the cleanup."}
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
    ;; Reagent's with-let cleanup runs on component unmount. The
    ;; only thing this needs to do is dispatch :cancel into the
    ;; parent flow machine; the machine's :working :cancel
    ;; transition takes care of the rest.
    (finally
      (dispatch [:work/flow [:cancel]]))))

;; ============================================================================
;; ROOT VIEW — Show/Hide toggle around the work-bench
;; ============================================================================
;;
;; The Show/Hide toggle is an *app-db boolean*, not part of the
;; :work/flow machine. The machine has nothing to do with the
;; component's visibility. The view either renders work-bench (which
;; on mount activates the worker pipeline once the user clicks
;; Start) or it doesn't. When the user clicks Hide while work is
;; running, the component unmounts → with-let's finally → dispatch
;; :cancel → parent exits :working → cascade tears every child down.

(rf/reg-event-db :ui/initialise
  (fn [db _]
    (assoc db :ui {:show-bench? true})))

(rf/reg-event-db :ui/toggle-bench
  (fn [db _]
    (update-in db [:ui :show-bench?] not)))

(rf/reg-sub :ui/show-bench?
  (fn [db _] (get-in db [:ui :show-bench?] true)))

(reg-view ^{:doc "Top-level root. A Show/Hide button and the
                  conditionally-rendered work-bench. Unmounting the
                  bench is what triggers the cascade — see work-bench
                  above."}
          root-view []
  (let [show? @(subscribe [:ui/show-bench?])]
    [:div.app
     {:style {:max-width "700px" :margin "2em auto"
              :font-family "sans-serif"}}
     [:h1 "re-frame2 — Pattern-LongRunningWork"]
     [:p "Demonstrates "
      [:code ":invoke-all"]
      ": one parent coordinator, N parallel workers, declarative
       spawn-and-join with cooperative cancellation."]
     [:button {:data-testid "toggle-bench"
               :on-click    #(dispatch [:ui/toggle-bench])
               :style       {:margin "0 0 1em 0"}}
      (if show? "Hide work-bench (cancels & unmounts)" "Show work-bench")]
     (when show?
       [work-bench])]))
