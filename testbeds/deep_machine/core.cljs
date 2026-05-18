(ns deep-machine.core
  "Shared framework-behavior testbed — one machine that, in a single
  declaration, exercises every machine grammar surface a tool (Causa,
  Story, re-frame2-pair-mcp) needs to discriminate when visualising the
  hierarchy and the transition cascade. A consumer sees:

    - **Parallel regions** at the top level (`:type :parallel` with
      two regions, `:work` and `:health`). One event broadcasts to
      both regions per [spec/005 §Parallel regions].
    - **Hierarchical compound states** 5+ levels deep on the `:work`
      region — the active leaf's full path is
      `[:work :phase-a :sub-a :nested-a :deep-a :leaf-a]` (region +
      five compound levels + leaf). A 'jump to substate' click on
      any rung must lift to the correct ancestor.
    - **`:always`** (eventless transitions) on the `:work` region's
      `:resolving` decision node — a microstep cascade that settles
      before the snapshot commits.
    - **`:after`** (delayed transitions) on the `:health` region —
      `:warming` auto-transitions to `:ready` after 200ms.
    - **`:invoke`** (single child actor) on the deep leaf
      `:leaf-a` — spawns a `:helper/tick` child whose lifecycle is
      bound to the leaf's; on leaf exit the desugared destroy fires.
    - **`:invoke-all`** (parallel children + join) on `:phase-b` —
      three `:helper/job` workers spawn in parallel; each child
      dispatches the parent's `:on-child-done` keyword from its
      terminal `:done` `:entry`; on the third the runtime fires
      `:on-all-complete` ([:helper/all-finished]) and the parent
      transitions to `:done-b`.

  This is NOT a tutorial — the bodies are minimal. The whole point is
  to give a consumer ONE machine whose snapshot, transitions, and
  spawn-registry slots exercise every spec/005 capability in one
  click trace. Each Button below drives the machine into a state
  that exposes one feature."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ----------------------------------------------------------------------------
;; Child machines (singletons reused for :invoke / :invoke-all)
;; ----------------------------------------------------------------------------
;;
;; Two minimal child machines. They exist solely so the parent can
;; declare `:invoke` and `:invoke-all` against well-formed
;; `:machine-id`s. Each child's body is intentionally trivial —
;; the load-bearing thing the testbed shows is the parent's
;; spawn/destroy/join shape, not what the children compute.

(def helper-tick-machine
  {:initial :ticking
   :states
   {:ticking
    ;; Single-actor child: ticks once on entry, then sits idle until
    ;; the parent's leaf exits and the desugared destroy tears it
    ;; down. The :tick action mutates :data so a consumer can see
    ;; the child's snapshot diverge from the parent's.
    {:tags  #{:helper/ticking}
     :entry (fn [data _ev] {:data (assoc data :ticked? true)})}}})

(rf/reg-machine :helper/tick helper-tick-machine)

(def helper-job-machine
  {:initial :running

   :actions
   {:dispatch-child-done
    ;; Terminal action: dispatch the parent's :on-child-done keyword.
    ;; Per Spec 005 §Child completion protocol the event shape is
    ;;   [<parent-id> [<on-child-done-keyword> <child-id> & extra]]
    ;; The runtime stamps :rf/parent-id and :rf/invoke-all-child-id
    ;; into each spawned child's :data at allocate-time, so the child
    ;; reads them out for the dispatch-back. The parent's
    ;; create-machine-handler intercepts the event and updates the
    ;; join state at [:rf/spawned :deep/main [:work :phase-b]].
    (fn action-dispatch-child-done [data _event]
      {:fx [[:dispatch [(:rf/parent-id data)
                        [:helper/child-done (:rf/invoke-all-child-id data)]]]]})}

   :states
   {:running
    {:tags    #{:helper/running}
     ;; Each child runs once and transitions to its terminal :done
     ;; leaf. The dispatch-back fires from :done's :entry — see below.
     :on      {:helper.job/finish :done}}
    :done
    {:meta   {:terminal? true}
     :final? true
     :tags   #{:helper/done}
     :entry  :dispatch-child-done}}})

(rf/reg-machine :helper/job helper-job-machine)

;; ----------------------------------------------------------------------------
;; The main machine — :deep/main
;; ----------------------------------------------------------------------------
;;
;; Two parallel regions:
;;
;;   :work    — five-deep compound hierarchy with :always cascade,
;;              :invoke at the deepest leaf, and :invoke-all on
;;              :phase-b. The :always cascade settles before commit.
;;
;;   :health  — flat region with :after-driven transition. Provides
;;              a separate region clock that a consumer can verify
;;              keeps its own `:rf/after-epoch-by-region` slot per
;;              [spec/005 §Per-region scoping].

(def main-machine
  {:type :parallel

   :data {:tick-count            0
          :phase-a-ran?          false
          :phase-b-all-finished? false}

   :guards
   {:phase-a-ran?
    (fn guard-phase-a-ran? [data _ev]
      (true? (:phase-a-ran? data)))}

   :actions
   {:bump-tick
    (fn action-bump-tick [data _ev]
      {:data (update data :tick-count (fnil inc 0))})

    :mark-phase-a-ran
    (fn action-mark-phase-a-ran [data _ev]
      {:data (assoc data :phase-a-ran? true)})

    :record-all-finished
    ;; Transition action — fires on the parent's :on-all-complete
    ;; event after the third child's :on-child-done is intercepted
    ;; by the runtime. The transition target settles into :done-b;
    ;; this action stamps a :data flag so the view's mirror shows
    ;; the join resolved.
    (fn action-record-all-finished [data _event]
      {:data (assoc data :phase-b-all-finished? true)})}

   :regions
   {;; ---- :work region — 5-deep compound + :always + :invoke + :invoke-all ----
    :work
    {:initial :idle
     :states
     {:idle
      ;; Region-root leaf. :work/go descends into :phase-a (five
      ;; compound levels unwind in one transition); :work/spawn
      ;; sidesteps into the :invoke-all-bearing :phase-b.
      {:tags #{:work/idle}
       :on   {:work/go    :phase-a
              :work/spawn :phase-b}}

      :phase-a
      ;; Compound level 1 of 5. Drops to :sub-a on entry.
      {:tags    #{:work/phase-a}
       :initial :sub-a
       :on      {:work/reset :idle}
       :states
       {:sub-a
        ;; Compound level 2 of 5. Drops to :nested-a on entry.
        {:tags    #{:work/sub-a}
         :initial :nested-a
         :states
         {:nested-a
          ;; Compound level 3 of 5. Drops to :deep-a on entry.
          {:tags    #{:work/nested-a}
           :initial :deep-a
           :states
           {:deep-a
            ;; Compound level 4 of 5. Drops to :leaf-a on entry.
            {:tags    #{:work/deep-a}
             :initial :leaf-a
             :states
             {:leaf-a
              ;; Compound level 5 — the deepest leaf. Full active
              ;; path on entry:
              ;;   [:work :phase-a :sub-a :nested-a :deep-a :leaf-a]
              ;;
              ;; Carries the :invoke declaration — the desugared
              ;; entry/exit fx spawn / destroy the :helper/tick
              ;; child. A consumer that walks `[:rf/spawned
              ;; :deep/main [:work :phase-a :sub-a :nested-a :deep-a
              ;; :leaf-a]]` sees the spawned child while this leaf
              ;; is active.
              ;;
              ;; The :work/done transition propagates back up
              ;; through every compound's :exit cascade and lands
              ;; the :work region in :resolving (sibling of
              ;; :phase-a). The desugared destroy of :helper/tick
              ;; fires on leaf exit.
              {:tags   #{:work/leaf-a :work/deepest}
               :entry  :bump-tick
               :invoke {:machine-id :helper/tick
                        :data       (fn [_snap _ev] {:ticked? false})}
               :on    {:work/done {:target :resolving
                                   :action :mark-phase-a-ran}}}}}}}}}}}

      :resolving
      ;; Eventless decision: was :phase-a fully traversed (i.e. did
      ;; :leaf-a's :work/done fire and stamp :phase-a-ran?)? The
      ;; :always cascade is first-match-wins. A consumer sees a
      ;; microstep cascade — entry into :resolving, :always fires,
      ;; exit from :resolving, entry into :done-a — without an
      ;; event appearing on the trace stream.
      {:always [{:guard :phase-a-ran? :target :done-a}
                {:target :idle}]}

      :done-a
      ;; Leaf — terminal-ish for the :phase-a path. :work/reset
      ;; cycles back to :idle.
      {:tags #{:work/done-a}
       :on   {:work/reset :idle}}

      :phase-b
      ;; Sibling of :phase-a, NOT nested under it. Hosts the
      ;; :invoke-all — three :helper/job children spawn in
      ;; parallel; each child dispatches the parent's
      ;; `:on-child-done` keyword (`:helper/child-done`) from its
      ;; terminal `:done` `:entry`. The runtime intercepts these
      ;; events at the parent's create-machine-handler boundary,
      ;; updates the join state at [:rf/spawned :deep/main
      ;; [:work :phase-b]], and — once all three have reported —
      ;; dispatches the parent's `:on-all-complete` event vector
      ;; (`[:helper/all-finished]`) which the parent's :on table
      ;; routes to `:done-b`.
      ;;
      ;; A consumer that watches the trace stream after :work/spawn
      ;; sees one :rf.machine.invoke-all/started plus three
      ;; :rf.machine/spawn fxs in source order; the children's
      ;; terminal states dispatch :helper/child-done back to the
      ;; parent; after the third, :rf.machine.invoke-all/all-completed
      ;; fires and `[:helper/all-finished]` lands.
      {:tags #{:work/phase-b}
       :invoke-all
       {:children        [{:id :j1 :machine-id :helper/job :data {:id :j1}}
                          {:id :j2 :machine-id :helper/job :data {:id :j2}}
                          {:id :j3 :machine-id :helper/job :data {:id :j3}}]
        :on-child-done   :helper/child-done
        :on-child-error  :helper/child-error
        :on-all-complete [:helper/all-finished]}
       :on   {:helper/all-finished {:target :done-b
                                    :action :record-all-finished}
              :work/reset          :idle}}

      :done-b
      {:tags #{:work/done-b}
       :on   {:work/reset :idle}}}}

    ;; ---- :health region — flat with :after ----
    :health
    ;; Independent of :work. Cycles :cold → :warming → :ready via an
    ;; :after delay. Per [spec/005 §Per-region scoping] the
    ;; :rf/after-epoch slot is per-region (lives at
    ;; `[:data :rf/after-epoch-by-region :health]`) — a :work
    ;; transition does not invalidate this region's in-flight
    ;; timer.
    {:initial :cold
     :states
     {:cold
      {:tags #{:health/cold}
       :on   {:health/heat :warming}}

      :warming
      ;; HOT PATH — :after schedules a :dispatch-later for the
      ;; synthetic `:rf.machine.timer/after-elapsed` event; on
      ;; expiry the transition fires.
      {:tags  #{:health/warming :health/transient}
       :after {200 :ready}}

      :ready
      {:tags #{:health/ready}
       :on   {:health/cool :cold}}}}}})

(rf/reg-machine :deep/main main-machine)

;; ----------------------------------------------------------------------------
;; App-db init
;; ----------------------------------------------------------------------------

(rf/reg-event-fx ::initialise
  (fn [_ _]
    ;; Boot the parent machine to its initial state (idle + cold).
    ;; The runtime stamps :rf/bootstrap-pending? on the snapshot;
    ;; the first event clears it after the initial-entry cascade.
    {:fx [[:dispatch [:deep/main [:rf.machine/bootstrap]]]]}))

;; ----------------------------------------------------------------------------
;; Subs + view
;; ----------------------------------------------------------------------------

(rf/reg-sub :work-state
  :<- [:rf/machine :deep/main]
  (fn [snap _]
    (get-in snap [:state :work])))

(rf/reg-sub :health-state
  :<- [:rf/machine :deep/main]
  (fn [snap _]
    (get-in snap [:state :health])))

(rf/reg-sub :tags
  :<- [:rf/machine :deep/main]
  (fn [snap _]
    (:tags snap)))

(rf/reg-sub :tick-count
  :<- [:rf/machine :deep/main]
  (fn [snap _]
    (get-in snap [:data :tick-count])))

(rf/reg-sub :phase-b-all-finished?
  :<- [:rf/machine :deep/main]
  (fn [snap _]
    (get-in snap [:data :phase-b-all-finished?])))

(reg-view buttons []
  (let [work-state            @(subscribe [:work-state])
        health-state          @(subscribe [:health-state])
        tags                  @(subscribe [:tags])
        tick-count            @(subscribe [:tick-count])
        phase-b-all-finished? @(subscribe [:phase-b-all-finished?])]
    [:div {:data-testid "deep-machine" :style {:font-family "sans-serif" :padding "1em"}}
     [:h1 "deep-machine testbed"]
     [:p "One machine, every grammar surface. Click through to drive
          the parallel regions independently."]

     [:div {:style {:display :flex :gap "0.5em" :flex-wrap :wrap}}
      [:button {:data-testid "work-go"
                :on-click #(dispatch [:deep/main [:work/go]])}
       "1 · :work/go  (descend 5 levels — :invoke spawns)"]
      [:button {:data-testid "work-done"
                :on-click #(dispatch [:deep/main [:work/done]])}
       "2 · :work/done  (:always cascade → :done-a; :invoke destroys)"]
      [:button {:data-testid "work-spawn"
                :on-click #(dispatch [:deep/main [:work/spawn]])}
       "3 · :work/spawn  (:invoke-all — 3 children)"]
      [:button {:data-testid "helper-finish-j1"
                :on-click #(dispatch [:helper/job [:helper.job/finish] :j1])}
       "4a · finish j1"]
      [:button {:data-testid "helper-finish-j2"
                :on-click #(dispatch [:helper/job [:helper.job/finish] :j2])}
       "4b · finish j2"]
      [:button {:data-testid "helper-finish-j3"
                :on-click #(dispatch [:helper/job [:helper.job/finish] :j3])}
       "4c · finish j3"]
      [:button {:data-testid "work-reset"
                :on-click #(dispatch [:deep/main [:work/reset]])}
       "5 · :work/reset"]
      [:button {:data-testid "health-heat"
                :on-click #(dispatch [:deep/main [:health/heat]])}
       "H · :health/heat  (:after 200ms → :ready)"]
      [:button {:data-testid "health-cool"
                :on-click #(dispatch [:deep/main [:health/cool]])}
       "C · :health/cool"]]

     [:p {:style {:margin-top "1em" :color "#666" :white-space :pre-wrap}}
      "work-state="            [:span {:data-testid "work-state"}            (pr-str work-state)]            "\n"
      "health-state="          [:span {:data-testid "health-state"}          (pr-str health-state)]          "\n"
      "tags="                  [:span {:data-testid "tags"}                  (pr-str tags)]                  "\n"
      "tick-count="            [:span {:data-testid "tick-count"}            tick-count]                     "\n"
      "phase-b-all-finished?=" [:span {:data-testid "phase-b-all-finished?"} (pr-str phase-b-all-finished?)]]]))

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
