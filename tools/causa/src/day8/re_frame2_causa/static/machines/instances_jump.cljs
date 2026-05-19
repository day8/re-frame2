(ns day8.re-frame2-causa.static.machines.instances-jump
  "Instances-mode JUMP — clicking the Instances pill (or the per-row
  `→ Runtime` chip in the browse-list) switches Causa to Runtime mode,
  opens the Runtime Machines tab, and selects this machine
  (rf2-o5f5f.2).

  ## Why this lives in its own ns

  The browse-list rows AND the right-pane sub-strip both dispatch the
  same JUMP; centralising the dispatcher means the two surfaces never
  drift. Per the bead's §Instances mode the JUMP is a Static-side
  affordance that hands off to the Runtime-side Machines tab via the
  existing events:

    `:rf.causa/set-mode :runtime`        — flip mode pill back
    `:rf.causa/select-tab :machines`     — surface the Runtime Machines tab
    `:rf.causa/select-machine-id <mid>`  — focus the panel on this machine

  Three dispatches; one click. Mode B/C auto-detect (Mode B for 2-8
  instances, Mode C for ≥8 per consolidated-design §0ter.3) is the
  Runtime panel's responsibility — the static-side JUMP just lands the
  selection; the post-collapse Runtime Machines panel runs event-driven
  off the focused event, so the selected-machine-id slot drives the
  Sim engine + share-URL contract today (per `panels/machine_inspector.
  cljs/select-machine-id`)."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens sans-stack type-scale]]))

(defn dispatch-jump!
  "Dispatch the three events that telegraph the JUMP. Called from the
  browse-list's per-row chip + the right-pane Instances pill.

  Safe to call with a nil `machine-id` — the mode + tab flips still
  fire (so the user lands on Runtime Machines) but the selection
  dispatch is suppressed (no value to write).

  All three dispatches target `:rf/causa` so the Static-mode chrome
  reads the new mode + tab slots."
  [machine-id]
  (rf/dispatch [:rf.causa/set-mode :runtime] {:frame :rf/causa})
  (rf/dispatch [:rf.causa/select-tab :machines] {:frame :rf/causa})
  (when (some? machine-id)
    (rf/dispatch [:rf.causa/select-machine-id machine-id] {:frame :rf/causa}))
  nil)

(defn dispatch-jump-sync!
  "Test-only synchronous variant of `dispatch-jump!`. Production code
  paths through the async `dispatch-jump!` because UI clicks are
  inherently async; tests bypass the queue so post-dispatch assertions
  read the new slots without a flush."
  [machine-id]
  (rf/dispatch-sync [:rf.causa/set-mode :runtime] {:frame :rf/causa})
  (rf/dispatch-sync [:rf.causa/select-tab :machines] {:frame :rf/causa})
  (when (some? machine-id)
    (rf/dispatch-sync [:rf.causa/select-machine-id machine-id]
                      {:frame :rf/causa}))
  nil)

(defn pill
  "Render the right-pane Instances pill. Sits inside the 4-mode sub-
  strip alongside Topology / Sim / Cascade. Carries a live-instance
  count badge (when `live-count > 0`) so the user reads how many live
  instances the JUMP will land in.

  Per the bead's §Instances mode the Static surface stays static —
  this pill is a JUMP affordance, not a mode the right pane renders."
  [{:keys [machine-id live-count active?]}]
  (let [label  "Instances"
        suffix (when (and (number? live-count) (pos? live-count))
                 (str " " live-count))]
    [:button
     {:data-testid "rf-causa-static-machines-pill-instances"
      :data-machine-id (str machine-id)
      :data-live-count (str (or live-count 0))
      :role        "tab"
      :aria-selected (if active? "true" "false")
      :on-click    (fn [_] (dispatch-jump! machine-id))
      :title       (str "Open " machine-id " in Runtime Machines tab"
                        " (mnemonic: i)")
      :aria-label  (str "Instances — JUMPs to Runtime Machines tab. "
                       (or live-count 0) " live instance"
                       (when-not (= 1 live-count) "s"))
      :style {:background    "transparent"
              :border        (str "1px solid "
                                  (if active?
                                    (:cyan tokens)
                                    (:border-default tokens)))
              :border-radius "10px"
              :color         (:accent-violet tokens)
              :cursor        "pointer"
              :font-family   sans-stack
              :font-size     (:caption type-scale)
              :font-weight   600
              :padding       "3px 12px"
              :white-space   "nowrap"}}
     label
     (when suffix
       [:span {:data-testid "rf-causa-static-machines-pill-instances-badge"
               :style {:color (:cyan tokens)
                       :margin-left "4px"
                       :font-family sans-stack}}
        suffix])]))
