(ns day8.re-frame2-xray.static.machines.instances-jump
  "Instances-mode JUMP — clicking the Instances pill (or the per-row
  `→ Dynamic` chip in the browse-list) switches Xray to Dynamic mode,
  opens the Dynamic Machines tab, and selects this machine
  (rf2-o5f5f.2).

  ## Why this lives in its own ns

  The browse-list rows AND the right-pane sub-strip both dispatch the
  same JUMP; centralising the dispatcher means the two surfaces never
  drift. Per the bead's §Instances mode the JUMP is a Static-side
  affordance that hands off to the Dynamic-side Machines tab via the
  existing events:

    `:rf.xray/set-mode :dynamic`        — flip mode pill back
    `:rf.xray/select-tab :machines`     — surface the Dynamic Machines tab
    `:rf.xray/select-machine-id <mid>`  — focus the panel on this machine

  Three dispatches; one click. Mode B/C auto-detect (Mode B for 2-8
  instances, Mode C for ≥8 per consolidated-design §0ter.3) is the
  Dynamic panel's responsibility — the static-side JUMP just lands the
  selection; the post-collapse Dynamic Machines panel runs event-driven
  off the focused event, so the selected-machine-id slot drives the
  Sim engine + the jump/focus landing today (per `panels/machine_inspector.
  cljs/select-machine-id`; the share-URL surface that previously also
  consumed the slot was removed in rf2-nugvv)."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens sans-stack type-scale]]))

(defn dispatch-jump-via
  "Dispatch the three events that telegraph the JUMP through the
  caller-supplied frame-aware `dispatch-fn` (rf2-nesy9). The chip /
  pill render inside the `browse-list` / `definition-detail` reg-views
  and thread that reg-view's injected `dispatch` in here, so the three
  events land on the SURROUNDING instance frame — not a `:rf/xray`
  literal. `dispatch-fn` defaults to a `default-frame-id`-bound
  dispatcher for callers without a captured one.

  Safe to call with a nil `machine-id` — the mode + tab flips still
  fire (so the user lands on Dynamic Machines) but the selection
  dispatch is suppressed (no value to write)."
  ([machine-id]
   (dispatch-jump-via machine-id
                      #(rf/dispatch % {:frame defaults/default-frame-id})))
  ([machine-id dispatch-fn]
   (dispatch-fn [:rf.xray/set-mode :dynamic])
   (dispatch-fn [:rf.xray/select-tab :machines])
   (when (some? machine-id)
     (dispatch-fn [:rf.xray/select-machine-id machine-id]))
   nil))

(defn dispatch-jump-sync!
  "Test-only synchronous variant. Production code paths through the
  async `dispatch-jump-via` because UI clicks are inherently async;
  tests bypass the queue so post-dispatch assertions read the new slots
  without a flush. Pins to `defaults/default-frame-id` (the production
  shell) — tests assert against that frame's app-db."
  [machine-id]
  (rf/dispatch-sync [:rf.xray/set-mode :dynamic] {:frame defaults/default-frame-id})
  (rf/dispatch-sync [:rf.xray/select-tab :machines] {:frame defaults/default-frame-id})
  (when (some? machine-id)
    (rf/dispatch-sync [:rf.xray/select-machine-id machine-id]
                      {:frame defaults/default-frame-id}))
  nil)

(defn pill
  "Render the right-pane Instances pill. Sits inside the 4-mode sub-
  strip alongside Topology / Sim / Cascade. Carries a live-instance
  count badge (when `live-count > 0`) so the user reads how many live
  instances the JUMP will land in.

  Per the bead's §Instances mode the Static surface stays static —
  this pill is a JUMP affordance, not a mode the right pane renders.

  `dispatch` (rf2-nesy9) is the frame-aware dispatcher threaded from the
  caller's reg-view so the JUMP lands on the surrounding instance
  frame (a plain fn invoked as a Reagent component cannot recover the
  frame itself)."
  [dispatch {:keys [machine-id live-count active?]}]
  (let [label  "Instances"
        suffix (when (and (number? live-count) (pos? live-count))
                 (str " " live-count))]
    [:button
     {:data-testid "rf-xray-static-machines-pill-instances"
      :data-machine-id (str machine-id)
      :data-live-count (str (or live-count 0))
      :role        "tab"
      :aria-selected (if active? "true" "false")
      :on-click    (fn [_] (dispatch-jump-via machine-id dispatch))
      :title       (str "Open " machine-id " in Dynamic Machines tab"
                        " (mnemonic: i)")
      :aria-label  (str "Instances — JUMPs to Dynamic Machines tab. "
                       (or live-count 0) " live instance"
                       (when-not (= 1 live-count) "s"))
      :style {:background    "transparent"
              :border        (str "1px solid "
                                  (if active?
                                    (:accent tokens)
                                    (:border-default tokens)))
              :border-radius "10px"
              :color         (:accent tokens)
              :cursor        "pointer"
              :font-family   sans-stack
              :font-size     (:caption type-scale)
              :font-weight   600
              :padding       "3px 12px"
              :white-space   "nowrap"}}
     label
     (when suffix
       [:span {:data-testid "rf-xray-static-machines-pill-instances-badge"
               :style {:color (:accent tokens)
                       :margin-left "4px"
                       :font-family sans-stack}}
        suffix])]))
