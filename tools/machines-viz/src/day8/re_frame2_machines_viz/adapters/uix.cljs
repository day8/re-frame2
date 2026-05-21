(ns day8.re-frame2-machines-viz.adapters.uix
  "UIx substrate shell for `MachineChart` (rf2-yg9he · xyflow Phase 2).

  ## Why this exists

  The xyflow migration (#1806) shipped `MachineChart` Reagent-only.
  This shell restores UIx substrate parity: a UIx host renders the
  SAME xyflow chart through the shared React bridge
  (`adapters.react-chart`), which reactifies the Reagent component to a
  plain React class. There is no fork of the chart — only this thin,
  UIx-idiomatic surface.

  ## Surface

  `MachineChart` is a UIx component (`defui`). A UIx host mounts it the
  usual way:

      (:require [day8.re-frame2-machines-viz.adapters.uix :as mv-uix])

      ($ mv-uix/MachineChart {:machine-id  :auth/login-flow
                              :definition  defn
                              :current-state :idle})

  Props are the closed map the Reagent `MachineChart` accepts (see
  `spec/API.md` §Props); the shell forwards them to the bridge
  unchanged. `:children` is ignored (the chart owns its subtree).

  ## Bundle isolation

  Tool-jar only; xyflow + elkjs are dev-only. Nothing under
  `implementation/` may `:require` this. A UIx host opts into the
  bundle cost deliberately."
  (:require [uix.core :refer [defui $]]
            [day8.re-frame2-machines-viz.adapters.react-chart :as react-chart]))

(defui MachineChart
  "UIx component wrapping the xyflow `MachineChart` via the shared
  React bridge. `props` is a CLJS map matching the Reagent
  `MachineChart` contract; forwarded to `react-chart/chart-element`
  which builds the reactified-Reagent React element. Renders the
  identical chart a Reagent host renders."
  [props]
  ;; `defui` already hands us a CLJS map; `:children` (if any) is
  ;; dropped — the chart manages its own subtree.
  ($ :<> (react-chart/chart-element (dissoc props :children))))
