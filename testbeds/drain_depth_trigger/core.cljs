(ns drain-depth-trigger.core
  "Shared framework-behavior testbed — a single handler that recursively
  dispatches itself in its `:fx`, with a configurable depth ceiling
  registered on the frame via `:drain-depth`. A consumer (Xray, Story,
  re-frame2-pair-mcp) observes the runtime's run-to-completion drain hitting
  the depth limit — and the **per-event durability** contract (per [spec/002
  §Run-to-completion rule 3] / [spec/002 §Drain versus event — the epoch
  unit] / [spec/009 §Error catalogue]):

    - Rule 3 — when the drain depth limit is reached, the runtime does
               NOT roll the whole drain back. The unit of atomicity is
               the *event*, not the drain: every `::recurse` event the
               drain already settled kept its own `:db` write and its
               own durable `:ok` epoch. There is NO pre-drain snapshot
               and NO whole-drain rollback. The runtime discards the
               remaining queued events (the next, *halting* event never
               runs) and emits `:rf.error/drain-depth-exceeded` carrying
               `:rollback? false`.
    - The frame's epoch record for the halting event lands with outcome
      `:halted-depth` per [Spec-Schemas §`:rf/epoch-record` Outcomes]
      (rf2-v0jwt). Because that event never ran, its `:db-before` and
      `:db-after` both equal the durable last-settled `app-db`.
      Consumers read this record off `rf/epoch-history`.

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
  failure shape at a deterministic depth a spec can assert against —
  and the durable `:depth-reached` counter (which reads back to the
  ceiling, NOT to 0) is the positive evidence that per-event writes
  survive the halt."
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

(rf/reg-event ::initialise
  (fn [_cofx _ev]
    {:db
     {;; Counter the recursive handler bumps. After a halt this reads
      ;; back to the drain-depth ceiling (each settled ::recurse event
      ;; kept its own durable :db write) — positive evidence of per-event
      ;; durability (rule 3: no whole-drain rollback).
      :depth-reached 0
      ;; Frame's :drain-depth, mirrored for the view. Re-registers
      ;; the frame on change via `:rf/reg-frame` so the runtime sees
      ;; the updated ceiling on the next drain.
      :drain-depth   default-drain-depth}}))

;; ----------------------------------------------------------------------------
;; The runaway handler
;; ----------------------------------------------------------------------------
;;
;; ::recurse is a `reg-event`. Its body returns a `:db` that increments
;; the depth counter AND an `:fx` that queues another ::recurse against
;; the same frame. The runtime processes the queue in source order in
;; one run-to-completion drain; each ::recurse appends one ::recurse,
;; the queue never empties, and depth grows linearly with iteration
;; count.
;;
;; Per [spec/002 §Run-to-completion] / [§Drain versus event]:
;;   - The whole cascade runs within one drain until the depth ceiling
;;     trips; rule 3 halts it there.
;;   - The atomicity unit is the EVENT, not the drain. Each ::recurse
;;     that already ran settled its own durable :db write + :ok epoch —
;;     there is NO whole-drain rollback and NO pre-drain snapshot.
;;   - The halting event (the one that would have tripped the ceiling)
;;     never runs; the runtime discards the rest of the queue and emits
;;     `:rf.error/drain-depth-exceeded` with `:rollback? false`.
;;   - `:depth-reached` reads back to the ceiling (the count of settled
;;     events), NOT to 0 — the durable writes are kept.

(rf/reg-event ::recurse
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

(rf/reg-event ::reset
  (fn [{:keys [db]} _ev]
    {:db (assoc db :depth-reached 0)}))

;; ----------------------------------------------------------------------------
;; Drain-depth control
;; ----------------------------------------------------------------------------
;;
;; Re-registering the default frame with a new `:drain-depth` updates the
;; ceiling on subsequent drains (per [spec/002 §Surgical update]). The
;; `:initial-events` are NOT re-dispatched on a surgical update — only the
;; depth ceiling changes — so `:depth-reached` survives across edits.

(rf/reg-event ::set-drain-depth
  (fn [{:keys [db]} [_ new-depth]]
    (rf/reg-frame :rf/default {:drain-depth new-depth})
    {:db (assoc db :drain-depth new-depth)}))

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
          run-to-completion drain ceiling halts the cascade at the
          depth limit. The atomicity unit is the event, not the drain:
          each settled ::recurse kept its own durable :db write, so
          :depth-reached reads back to the ceiling — NOT 0 — positive
          evidence of per-event durability (rule 3: no whole-drain
          rollback)."]

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
      "  (= ceiling after halt — per-event durability evidence, rule 3)"  "\n"
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
  ;; EP-0002 (rf2-9o48ih): scope the boot dispatch to the registered app
  ;; frame and wrap the render in a `frame-provider` — the runtime never
  ;; synthesises a frame from absence (the carried invariant).
  (rf/with-frame :rf/default
    (rf/dispatch-sync [::initialise]))
  (rdc/render react-root [rf/frame-provider {:frame :rf/default} [root]]))
