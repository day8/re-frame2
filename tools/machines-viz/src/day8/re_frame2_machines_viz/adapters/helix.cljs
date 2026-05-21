(ns day8.re-frame2-machines-viz.adapters.helix
  "Helix substrate shell for `MachineChart` (rf2-yg9he · xyflow Phase 2).

  ## Why this exists

  The xyflow migration (#1806) shipped `MachineChart` Reagent-only.
  This shell restores Helix substrate parity: a Helix host renders the
  SAME xyflow chart through the shared React bridge
  (`adapters.react-chart`), which reactifies the Reagent component to a
  plain React class. There is no fork of the chart — only this thin,
  Helix-idiomatic surface.

  ## Surface

  `MachineChart` is a Helix component (`defnc`). A Helix host mounts it
  the usual way:

      (:require [day8.re-frame2-machines-viz.adapters.helix :as mv-helix]
                [helix.core :refer [$]])

      ($ mv-helix/MachineChart {:machine-id  :auth/login-flow
                                :definition  defn
                                :current-state :idle})

  Props are the closed map the Reagent `MachineChart` accepts (see
  `spec/API.md` §Props). The `:props` Helix-feature is enabled so the
  component receives the raw props as a single map argument (Helix
  otherwise spreads named props); the shell forwards them to the
  bridge unchanged.

  ## Bundle isolation

  Tool-jar only; xyflow + elkjs are dev-only. Nothing under
  `implementation/` may `:require` this. A Helix host opts into the
  bundle cost deliberately."
  (:require [helix.core :refer [defnc]]
            [day8.re-frame2-machines-viz.adapters.react-chart :as react-chart]))

(defnc MachineChart
  "Helix component wrapping the xyflow `MachineChart` via the shared
  React bridge. With the `:props` feature the whole props object
  arrives as `props`; the shell forwards it to
  `react-chart/chart-element` which builds the reactified-Reagent
  React element. Renders the identical chart a Reagent host renders.

  `:children` is dropped — the chart manages its own subtree."
  [props]
  {:helix/features {:check-invalid-hooks-usage false}}
  (react-chart/chart-element (dissoc props :children)))
