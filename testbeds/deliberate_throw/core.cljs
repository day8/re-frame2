(ns deliberate-throw.core
  "Shared framework-behavior testbed — four trigger sites that throw on
  `:on-click` dispatch. Each button is wired to a different layer of the
  six-domino cascade so a consumer (Causa, Story, re-frame2-pair-mcp) observes
  the corresponding :rf.error/* category emerge once per click.

  This is NOT a tutorial. The bodies are deliberately stark — no error
  handling, no recovery, no diagnostic prose. The whole point is to
  produce a single, predictable failure per button so external test
  harnesses can assert against it.

  Triggered categories (per [spec/009 §Error contract]):

    Button A → :rf.error/handler-exception        (synchronous handler throw)
    Button B → :rf.error/fx-handler-exception     (fx body throw)
    Button C → :rf.error/flow-eval-exception      (flow :output throw)
    Button D → :rf.error/machine-action-exception (machine :actions throw)"
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.flows]                            ;; load-time hook for reg-flow
            [re-frame.machines]                          ;; load-time hook for create-machine-handler
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ----------------------------------------------------------------------------
;; App-db
;; ----------------------------------------------------------------------------
;;
;; Minimal by design — three counters that the throwing handlers *would*
;; bump if their cascades completed. A consumer that sees the value at
;; [:click-count :handler] stay at 0 after a Button-A click has positive
;; evidence the handler threw (the `:db` effect did not commit).

(rf/reg-event-db ::initialise
  (fn [_db _ev]
    {:click-count {:handler 0 :fx 0 :flow 0 :machine 0}
     ;; Flow input — bumping this triggers Button C's flow recompute.
     :flow-input  0}))

;; ----------------------------------------------------------------------------
;; Button A — synchronous throw in event handler
;; ----------------------------------------------------------------------------

(rf/reg-event-db ::throw-in-handler
  (fn [_db _ev]
    ;; HOT PATH — the throw site for :rf.error/handler-exception.
    ;; The runtime's outer interceptor catches; emits the structured
    ;; error event with this fn's :handler-id and the original message.
    (throw (ex-info "deliberate-throw / handler" {:where :handler}))))

;; ----------------------------------------------------------------------------
;; Button B — throw inside fx body
;; ----------------------------------------------------------------------------
;;
;; The handler returns `{:fx [[::boom ...]]}` cleanly; the throw happens
;; later, during the fx walk. Per [spec/010 §Per-step recovery] the
;; offending fx is skipped; other fx in the same vector continue. The
;; runtime emits :rf.error/fx-handler-exception with this fx-id.

(rf/reg-fx ::boom
  (fn [_frame-ctx _args]
    ;; HOT PATH — the throw site for :rf.error/fx-handler-exception.
    (throw (ex-info "deliberate-throw / fx" {:where :fx}))))

(rf/reg-event-fx ::throw-in-fx
  (fn [{:keys [db]} _ev]
    {:db (update-in db [:click-count :fx] inc)
     :fx [[::boom {}]]}))

;; ----------------------------------------------------------------------------
;; Button C — throw inside flow :output
;; ----------------------------------------------------------------------------
;;
;; The flow is registered once at ns-load (registration order doesn't
;; matter — the runtime topsorts before the first drain). Button C's
;; handler bumps :flow-input; on the post-handler flows pass, the
;; runtime walks this flow, calls :output with the new input, and the
;; throw fires there.
;;
;; Per [spec/013 §Failure semantics]: two trace events fire in order —
;; :rf.flow/failed (per-flow attribution) followed by the cascade-level
;; :rf.error/flow-eval-exception (router outer catch). The handler's
;; `:db` is preserved (prior writes survive); the failing flow's
;; `last-inputs` is NOT advanced.

(rf/reg-flow
  {:id     ::throws
   :inputs [[:flow-input]]
   :output (fn [_input]
             ;; HOT PATH — the throw site for :rf.error/flow-eval-exception.
             (throw (ex-info "deliberate-throw / flow" {:where :flow})))
   :path   [:flow-output]
   :doc    "Flow :output that throws every recompute."})

(rf/reg-event-db ::throw-in-flow
  (fn [db _ev]
    (-> db
        (update-in [:click-count :flow] inc)
        ;; Bumping :flow-input is what causes the flow to recompute.
        ;; The flow's :output throws — see ::throws above.
        (update :flow-input inc))))

;; ----------------------------------------------------------------------------
;; Button D — throw inside machine action
;; ----------------------------------------------------------------------------
;;
;; The machine has one transition; the transition references the
;; :throw action by keyword; the action body throws.
;;
;; Per [spec/005 §Errors] and [spec/009 :rf.error/machine-action-exception]:
;; the machine layer catches and emits the machine-scoped category, NOT
;; :rf.error/handler-exception. The snapshot does not commit; accumulated
;; :fx from earlier slots in the cascade is dropped; :always does not
;; fire on the failed cascade.

(rf/reg-event-fx ::throw-in-machine
  (rf/create-machine-handler
    {:initial :idle
     :actions {:throw
               (fn [_data _event]
                 ;; HOT PATH — the throw site for :rf.error/machine-action-exception.
                 (throw (ex-info "deliberate-throw / machine action"
                                 {:where :machine})))}
     :states  {:idle {:on {::tick {:target :idle :action :throw}}}}}))

;; ----------------------------------------------------------------------------
;; Subs + view
;; ----------------------------------------------------------------------------

(rf/reg-sub :handler-count (fn [db _] (get-in db [:click-count :handler])))
(rf/reg-sub :fx-count      (fn [db _] (get-in db [:click-count :fx])))
(rf/reg-sub :flow-count    (fn [db _] (get-in db [:click-count :flow])))
(rf/reg-sub :machine-count (fn [db _] (get-in db [:click-count :machine])))

(reg-view buttons []
  [:div {:data-testid "deliberate-throw" :style {:font-family "sans-serif" :padding "1em"}}
   [:h1 "deliberate-throw testbed"]
   [:p "Each button triggers exactly one :rf.error/* category. Click and observe."]
   [:div {:style {:display :flex :gap "0.5em" :flex-wrap :wrap}}
    [:button {:data-testid "throw-handler"
              :on-click #(dispatch [::throw-in-handler])}
     "A · handler-exception"]
    [:button {:data-testid "throw-fx"
              :on-click #(dispatch [::throw-in-fx])}
     "B · fx-handler-exception"]
    [:button {:data-testid "throw-flow"
              :on-click #(dispatch [::throw-in-flow])}
     "C · flow-eval-exception"]
    [:button {:data-testid "throw-machine"
              :on-click #(dispatch [::throw-in-machine [::tick]])}
     "D · machine-action-exception"]]
   [:p {:style {:margin-top "1em" :color "#666"}}
    "handler=" [:span {:data-testid "handler-count"} @(subscribe [:handler-count])]
    " · fx=" [:span {:data-testid "fx-count"} @(subscribe [:fx-count])]
    " · flow=" [:span {:data-testid "flow-count"} @(subscribe [:flow-count])]
    " · machine=" [:span {:data-testid "machine-count"} @(subscribe [:machine-count])]]])

(reg-view root []
  [buttons])

;; ----------------------------------------------------------------------------
;; Mount
;; ----------------------------------------------------------------------------

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [::initialise])
  (rdc/render react-root [root]))
