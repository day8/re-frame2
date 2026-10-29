(ns day8.re-frame2-machines-viz.adapters.react-chart
  "Shared React-component bridge for the `MachineChart` substrate
  adapters.

  ## Why this exists

  `MachineChart` is a Reagent component whose renderer is xyflow, a
  React library. This bridge gives a UIx host the same chart.

  The lever is that xyflow IS React: the Reagent `MachineChart` bottoms
  out at a React element tree. `reagent.core/as-element` turns the
  component vector `[MachineChart props]` into a plain React element that
  ANY React host — UIx or raw React — can mount. So every substrate
  renders the SAME chart through one bridge; there is no fork of the
  chart per substrate, only a thin host-idiomatic shell.

  ## Props contract

  Identical to the Reagent `MachineChart` (see `chart/MachineChart`'s
  docstring + `spec/API.md` §Props). The shells pass a CLJS props map
  straight through as the component's single map argument. Callback
  props (`:on-state-click`, …) carry through unchanged.

  ## Bundle isolation

  Same contract as the rest of machines-viz — xyflow + elkjs are dev-
  only; nothing under `implementation/` may `:require` this. The
  per-substrate shells live here in the tool jar so a UIx host
  opts into the bundle cost deliberately (per `000-Vision.md`
  §Surface set — user-app drop-in)."
  (:require [reagent.core :as r]
            [day8.re-frame2-machines-viz.chart :as chart]))

(defn chart-element
  "Build a React element for `MachineChart` from a CLJS `props` map.
  The per-substrate shells call this; the returned element is a plain
  React element so UIx (`$`) — and raw React — both
  mount it identically.

  `props` is the same closed map the Reagent `MachineChart` accepts
  (see `spec/API.md` §Props). Reagent caches the React class it derives
  for `MachineChart`, so every element carries the same type and a host
  re-render never remounts the chart."
  [props]
  (r/as-element [chart/MachineChart (or props {})]))
