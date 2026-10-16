(ns day8.re-frame2-xray.substrate
  "Xray's hiccup -> React element crossing.

  Xray's shell is hiccup, and several of its views are FRESCO
  BOUNDARIES. A boundary body may hand an `rf/reg-view` island
  down to React as a child, and Fresco's component ABI wants a React
  ELEMENT there rather than a fn-headed hiccup vector. The boundaries take
  the spelling as an `as-child` PARAMETER: a plain hiccup caller (and the
  node lane) passes `identity`, leaving the island a vector; a boundary
  passes [[as-element]].

  Stock Reagent is the one ratom runtime, so its walk is the right one
  whichever adapter the host installed: under Reagent it is the installed
  renderer's own walk, and under UIx or Fresco — Xray paints through its
  own React root — Fresco's codec passes the already-built element through
  untouched."
  (:require [reagent.core :as reagent]))

(defn as-element
  "Convert `hiccup` to a React element through stock Reagent's walk. This
  is the `as-child` spelling every Xray Fresco boundary passes for its
  Reagent islands; pass `identity` instead from a plain hiccup caller or
  the node lane, where the island stays a vector."
  [hiccup]
  (reagent/as-element hiccup))
