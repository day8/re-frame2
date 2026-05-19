(ns day8.re-frame2-causa.chart.layout
  "Re-export shim for the MachineChart layout primitive.

  rf2-o9arp — the canonical implementation lives in
  `day8.re-frame2-machines-viz.chart.layout`. This ns survives as a
  thin re-export so the Causa-side panels keep importing through
  `day8.re-frame2-causa.chart.layout/layout` / `highlight-id` /
  `node-id` without rewiring every call site at once."
  (:require [day8.re-frame2-machines-viz.chart.layout :as mv-layout]))

;; ---- public surface ----------------------------------------------------

(def node-width        mv-layout/node-width)
(def node-height       mv-layout/node-height)
(def rank-gap          mv-layout/rank-gap)
(def node-gap          mv-layout/node-gap)
(def chart-margin      mv-layout/chart-margin)

(def parse-definition  mv-layout/parse-definition)
(def node-id           mv-layout/node-id)
(def layout            mv-layout/layout)
(def layered-fallback  mv-layout/layered-fallback)
(def highlight-id      mv-layout/highlight-id)
