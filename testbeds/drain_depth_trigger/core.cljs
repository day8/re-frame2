(ns drain-depth-trigger.core
  "Shared framework-behavior testbed — a single handler that recursively
  dispatches itself in its `:fx`, with a configurable depth ceiling
  registered on the frame via `:drain-depth`. A consumer (Causa, Story,
  re-frame2-pair-mcp) observes the runtime's run-to-completion drain hitting
  the depth limit and the atomic rollback (per [spec/002
  §Run-to-completion rule 3] / [spec/009 §Error catalogue]):

    - Rule 3 — when the drain depth limit is reached, the runtime
               rolls the frame's `app-db` back atomically to the
               value snapshotted at the start of the drain.
    - The frame's epoch record for the failed cascade lands with
      outcome `:halted-depth` per [Spec-Schemas §`:rf/epoch-record`
      Outcomes] (rf2-v0jwt). Consumers read this record off
      `rf/epoch-history`.

  Two buttons drive the surface:

    Start (recurse) → dispatches `::recurse`. The handler increments
                      `:depth-reached` and queues another `::recurse`
                      via `:fx [[:dispatch [::recurse]]]`. The drain
                      processes the queue in a tight loop until the
                      frame's `:drain-depth` ceiling fires.

    Reset           → resets `:depth-reached` for re-runs.

  An input bound to `:drain-depth` lets the user lower the ceiling
  for fast specs (the default 100 is fine, but a Playwright spec that
  asserts on halt observability can dial down to 5 for a sub-second
  run; the surface re-registers the frame on change).

  This is NOT a tutorial — the bodies are stark. The whole point is
  to give a consumer ONE click that produces a drain-depth-exceeded
  failure shape at a deterministic depth a spec can assert against."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ----------------------------------------------------------------------------
;; Constants
;; ----------------------------------------------------------------------------

(def default-drain-depth
  "Default depth ceiling registered on the surface's frame. Low enough
  that the runaway cascade halts in well under a second of wall-clock
  time, high enough to demonstrate the runtime ran multiple iterations
  before the rollback fired. The default framework `:drain-depth` is
  100 (per Spec 002 §`:drain-depth`); this surface ships with 25 so a
  visual demo halts visibly faster."
  25)

;; ----------------------------------------------------------------------------
;; App-db
;; ----------------------------------------------------------------------------

(rf/reg-event-db ::initialise
  (fn [_db _ev]
    {;; Counter the recursive handler bumps. After a halt this reads
     ;; back to 0 — positive evidence of the atomic rollback (rule 3).
     :depth-reached 0
     ;; Frame's :drain-depth, mirrored for the view. Re-registers
     ;; the frame on change via `:rf/reg-frame` so the runtime sees
     ;; the updated ceiling on the next drain.
     :drain-depth   default-drain-depth}))

;; ----------------------------------------------------------------------------
;; The runaway handler
;; ----------------------------------------------------------------------------
;;
;; ::recurse is `reg-event-fx`. Its body returns a `:db` that increments
;; the depth counter AND an `:fx` that queues another ::recurse against
;; the same frame. The runtime processes the queue in source order in
;; one run-to-completion drain; each ::recurse appends one ::recurse,
;; the queue never empties, and depth grows linearly with iteration
;; count.
;;
;; Per [spec/002 §Run-to-completion]:
;;   - The whole cascade runs to fixed point within one drain.
;;   - The drain is depth-bounded; rule 3 fires the rollback when the
;;     depth limit is reached.
;;   - The frame's `app-db` is restored to the snapshot taken at the
;;     start of the drain. `:depth-reached` reads back to 0.

(rf/reg-event-fx ::recurse
  (fn [{:keys [db]} _ev]
    ;; HOT PATH — the recursion site. The handler ALWAYS dispatches
    ;; another ::recurse; there is no termination branch on purpose —
    ;; only the runtime's depth ceiling can halt this cascade.
    {:db (update db :depth-reached (fnil inc 0))
     :fx [[:dispatch [::recurse]]]}))

;; ----------------------------------------------------------------------------
;; Reset
;; ----------------------------------------------------------------------------
;;
;; Note — this testbed historically registered an error-emit listener
;; (`register-error-listener!`) intending to flip a `:halted?`
;; mirror when `:rf.error/drain-depth-exceeded` fired. That listener
;; never fired: the runtime's depth-exceeded path emits ONLY via
;; `trace/emit-error!`, not `error-emit/dispatch-on-error!` (the
;; substrate `register-error-listener!` subscribes to). Per
;; rf2-86k63 the mirror has been removed — the framework-side
;; observables (`:depth-reached` rolling back to 0, the `:halted-depth`
;; epoch record on `rf/epoch-history`) already cover the contract
;; under test and are what the cross-cutting scenario asserts on.

(rf/reg-event-db ::reset
  (fn [db _ev]
    (assoc db :depth-reached 0)))

;; ----------------------------------------------------------------------------
;; Drain-depth control
;; ----------------------------------------------------------------------------
;;
;; Re-registering the default frame with a new `:drain-depth` updates the
;; ceiling on subsequent drains (per [spec/002 §Surgical update]). The
;; `:on-create` event is NOT re-fired on a surgical update — only the
;; depth ceiling changes — so `:depth-reached` survives across edits.

(rf/reg-event-db ::set-drain-depth
  (fn [db [_ new-depth]]
    (rf/reg-frame :rf/default {:drain-depth new-depth})
    (assoc db :drain-depth new-depth)))

;; ----------------------------------------------------------------------------
;; Subs + view
;; ----------------------------------------------------------------------------

(rf/reg-sub :depth-reached (fn [db _] (:depth-reached db)))
(rf/reg-sub :drain-depth   (fn [db _] (:drain-depth db)))

(reg-view buttons []
  (let [depth-reached @(subscribe [:depth-reached])
        drain-depth   @(subscribe [:drain-depth])]
    [:div {:data-testid "drain-depth-trigger"
           :style       {:font-family "sans-serif" :padding "1em"}}
     [:h1 "drain-depth-trigger testbed"]
     [:p "One handler that dispatches itself in :fx. The runtime's
          run-to-completion drain ceiling halts the cascade and rolls
          :depth-reached back to 0 — positive evidence of the atomic
          rollback (rule 3)."]

     [:div {:style {:display :flex :gap "0.5em" :flex-wrap :wrap
                    :align-items :center :margin-bottom "0.5em"}}
      [:label
       "Drain depth ceiling:"
       [:input {:data-testid "drain-depth"
                :type        "number"
                :min         1
                :value       drain-depth
                :style       {:width "5em" :margin-left "0.25em"}
                :on-change   (fn [e]
                               (let [v (js/parseInt (.. e -target -value) 10)]
                                 (dispatch [::set-drain-depth v])))}]]
      [:button {:data-testid "start"
                :on-click    #(dispatch [::recurse])}
       "Start (recurse — halts at depth)"]
      [:button {:data-testid "reset"
                :on-click    #(dispatch [::reset])}
       "Reset"]]

     [:p {:style {:color "#666" :white-space :pre-wrap}}
      "depth-reached=" [:span {:data-testid "depth-reached"} depth-reached]
      "  (= 0 after halt — rule 3 rollback evidence)"  "\n"
      "drain-depth="   [:span {:data-testid "drain-depth-mirror"} drain-depth]]]))

(reg-view root []
  [buttons])

;; ----------------------------------------------------------------------------
;; Mount
;; ----------------------------------------------------------------------------

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  ;; Register the default frame with the low ceiling BEFORE init —
  ;; the Start click only needs the runtime's drain to fire the halt;
  ;; per [spec/002 §Surgical update] re-registering only changes the
  ;; supplied keys (here :drain-depth), the other defaults survive.
  (rf/reg-frame :rf/default {:drain-depth default-drain-depth})
  (rf/dispatch-sync [::initialise])
  (rdc/render react-root [root]))
