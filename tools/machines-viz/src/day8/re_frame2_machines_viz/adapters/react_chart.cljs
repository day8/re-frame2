(ns day8.re-frame2-machines-viz.adapters.react-chart
  "Shared React-component bridge for the `MachineChart` substrate
  adapters (rf2-yg9he · xyflow Phase 2).

  ## Why this exists

  The xyflow migration (#1806) shipped `MachineChart` Reagent-only —
  that was the accepted Phase 1 trade-off when the chart went from
  substrate-agnostic hiccup to a React-component-shaped renderer (xyflow
  is a React library). This bead restores UIx + Helix substrate parity.

  The lever is that xyflow IS React: the Reagent `MachineChart` bottoms
  out at a React element tree (via `r/as-element` inside its node + edge
  components). `reagent.core/reactify-component` lifts the whole Reagent
  component to a plain React component class that ANY React host — UIx,
  Helix, or raw React — can mount with `createElement` / `$`. So all
  three substrates render the SAME chart through one bridge; there is no
  fork of the chart per substrate, only a thin host-idiomatic shell.

  ## The bridge

  `MachineChartReactClass` is the reactified Reagent `MachineChart`,
  memoised (reactify-component returns a fresh class each call; React
  caches component types by reference, so we reactify ONCE at ns-load).
  `chart-element` builds a React element from a CLJS props map for the
  per-substrate shells.

  ## Props contract

  Identical to the Reagent `MachineChart` (see `chart/MachineChart`'s
  docstring + `spec/API.md` §Props). The shells pass a CLJS props map
  straight through; reactify-component hands it to the Reagent component
  as its single map argument. Callback props (`:on-state-click`, …)
  carry through unchanged.

  ## Bundle isolation

  Same contract as the rest of machines-viz — xyflow + elkjs are dev-
  only; nothing under `implementation/` may `:require` this. The
  per-substrate shells live here in the tool jar so a UIx / Helix host
  opts into the bundle cost deliberately (per `000-Vision.md`
  §Surface set — user-app drop-in)."
  (:require [reagent.core :as r]
            [day8.re-frame2-machines-viz.chart :as chart]))

(def MachineChartReactClass
  "The reactified Reagent `MachineChart`, as a plain React component
  class. Reactified ONCE at ns-load (React caches component types by
  reference; reactifying per-render would defeat that cache and churn
  the subtree). Any React host mounts it via `createElement` / `$`
  with a single CLJS props map."
  (r/reactify-component chart/MachineChart))

(defn chart-element
  "Build a React element for `MachineChart` from a CLJS `props` map.
  The per-substrate shells call this; the returned element is a plain
  React element so UIx (`$`) and Helix (`$`) — and raw React — all
  mount it identically.

  `props` is the same closed map the Reagent `MachineChart` accepts
  (see `spec/API.md` §Props).

  The reactified Reagent component reads its render argument from the
  React prop `argv` — a Reagent-shaped component vector
  `[component props & children]`. The render fn is invoked with
  `(nth argv 1)`, so the props map sits at index 1; index 0 is an
  ignored placeholder (the component constructor in normal Reagent
  use). Building the React element directly (rather than via Reagent's
  `:>` interop) keeps the shells substrate-neutral."
  [props]
  (r/create-element MachineChartReactClass
                    #js {:argv #js [MachineChartReactClass (or props {})]}))
