(ns day8.re-frame2-xray.test-helpers.static-machines-tree
  "The node lane's door onto the Static Machines sub-tab (rf2-k97c.3).

  ## Why this exists

  The sub-tab's three views are now Fresco boundaries — `panel/panel`,
  `browse-list/browse-list` and `definition-detail/detail` are
  `rf.fresco/defview`s, i.e. real React function components. A
  boundary's body may only run inside a React render window, so
  `(panel/panel)` is no longer a callable that answers hiccup and the
  ~30 existing node-lane rows that walked its return value could not
  survive unchanged.

  The repair is `defview`'s own documented extract-a-helper spelling:
  each of the three files exports a PURE `*-tree` fn of its read's
  values, the boundary is the thin thing that reads and calls it, and
  the node lane drives the tree fns. This ns is the composer that does
  that driving.

  ## It REPRODUCES THE BOUNDARIES' READS — same subs, same order

  That is what makes a node-lane row evidence about the shipped panel
  rather than about a parallel fixture. Each fn below is a line-for-line
  mirror of the boundary beside it, with `rf.fresco/sub` swapped for
  `rf/subscribe` (the node lane has no React render window and no
  collector extent) and nothing else changed:

    * the `detail` composer keeps the two SEQUENCED reads — `sub-mode`
      and `copy-mermaid-status` are parameterised by the `selected-id`
      the `data` read answers;
    * it keeps the `:sim` CONDITIONAL — the five sim slots are read only
      on that sub-mode, exactly as the boundary reads them;
    * it passes `identity` as `detail-tree`'s `as-child`, so the
      Topology and Sim bodies stay fn-headed hiccup vectors that
      `rf.test-helpers/expand-tree` walks precisely as it always has.
      The boundary passes `reagent.core/as-element` there; that
      crossing's evidence is the browser lane's, not this one's.

  SINGLE-SOURCED ON PURPOSE. Four suites need this composition
  (`panel_cljs_test`, `browse_list_cljs_test`, `copy_mermaid_cljs_test`
  and the multi-frame e2e). Four private copies of a read reproduction
  is four things to keep in step with one boundary, and the failure mode
  when one drifts is a row that stays green while measuring a tree the
  panel does not render.

  ## Call these INSIDE a frame scope

  Every fn here subscribes ambiently, so each call belongs inside
  `(rf/with-frame :rf/xray …)` — the same requirement the `reg-view`
  bodies had, and the same one every existing caller already satisfies."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.static.machines.browse-list :as browse-list]
            [day8.re-frame2-xray.static.machines.definition-detail
             :as definition-detail]
            [day8.re-frame2-xray.static.machines.panel :as panel]))

(defn browse-list-tree
  "The L4-LEFT pane's hiccup, read the way `browse-list/browse-list`
  reads it. `dispatch` defaults to
  `(:dispatch (rf/capture-frame))` — the SAME door the boundary uses, so
  a handler this lane pulls off the tree and fires later carries the
  frame exactly as the shipped one does."
  ([] (browse-list-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (browse-list/browse-list-tree
     @(rf/subscribe [:rf.xray.static.machines/data])
     @(rf/subscribe [:rf.xray.static.machines/search])
     @(rf/subscribe [:rf.xray.static.machines/sort-key])
     dispatch)))

(defn detail-tree
  "The L4-RIGHT pane's hiccup, read the way `definition-detail/detail`
  reads it — including the two selected-id-parameterised reads and the
  `:sim`-only sim family. `as-child` is `identity` here: the node lane
  wants the Topology / Sim bodies left as fn-headed hiccup."
  ([] (detail-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (let [{:keys [selected-id] :as data}
         @(rf/subscribe [:rf.xray.static.machines/data])
         sub-mode @(rf/subscribe [:rf.xray.static.machines/sub-mode
                                  selected-id])]
     (definition-detail/detail-tree
       {:data        data
        :definitions @(rf/subscribe [:rf.xray/machine-definitions])
        :sub-mode    sub-mode
        :fit-signal  @(rf/subscribe [:rf.xray/machine-tab-fit-signal])
        :copy-status @(rf/subscribe
                        [:rf.xray.static.machines/copy-mermaid-status
                         selected-id])
        :sim-values  (when (= sub-mode :sim)
                       {:sim         @(rf/subscribe
                                        [:rf.xray.static.machines/sim-state])
                        :transitions @(rf/subscribe
                                        [:rf.xray.static.machines/sim-available-transitions])
                        :suggestions @(rf/subscribe
                                        [:rf.xray.static.machines/sim-event-suggestions])
                        :current     @(rf/subscribe
                                        [:rf.xray.static.machines/sim-current-state])
                        :last-trans  @(rf/subscribe
                                        [:rf.xray.static.machines/sim-last-transition])})}
       dispatch
       identity))))

(defn panel-tree
  "The WHOLE sub-tab's hiccup — the shipped two-pane chrome from
  `panel/panel-tree` with both panes' bodies expanded into it. This is
  what `(panel/panel)` used to answer, and it is the drop-in every
  migrated node-lane row takes."
  ([] (panel-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (panel/panel-tree (browse-list-tree dispatch)
                     (detail-tree dispatch))))
