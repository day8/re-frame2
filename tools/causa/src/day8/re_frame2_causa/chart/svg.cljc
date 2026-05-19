(ns day8.re-frame2-causa.chart.svg
  "Re-export shim for the MachineChart SVG renderer.

  rf2-o9arp — the canonical implementation lives in
  `day8.re-frame2-machines-viz.chart.svg`. This ns survives as a
  thin re-export so the Causa-side panels (machine-inspector,
  machine-after-rings, machine-inspector-sim, etc.) keep importing
  through `day8.re-frame2-causa.chart.svg/render` without
  rewiring every call site at once. Subsequent housekeeping passes
  can collapse the indirection — for now the shim keeps the
  public surface stable for parallel workers (notably the
  rf2-o5f5f.2 Static Machines surface)."
  (:require [day8.re-frame2-machines-viz.chart.svg :as mv-svg]))

;; ---- public surface ----------------------------------------------------

(def compound-containers mv-svg/compound-containers)
(def render               mv-svg/render)
(def render-from-definition mv-svg/render-from-definition)
(def countdown-ring       mv-svg/countdown-ring)
(def sparkline            mv-svg/sparkline)
