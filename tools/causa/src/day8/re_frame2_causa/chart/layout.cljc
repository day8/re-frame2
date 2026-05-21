(ns day8.re-frame2-causa.chart.layout
  "Re-export shim for the MachineChart graph-parse surface.

  rf2-o9arp — the canonical implementation lives in
  `day8.re-frame2-machines-viz.chart.layout`. This ns survives as a
  thin re-export so the Causa-side panels keep importing through
  `day8.re-frame2-causa.chart.layout/parse-definition` /
  `highlight-id` / `node-id` without rewiring every call site at
  once.

  rf2-gpzb4 (2026-05-21 xyflow override) — the SVG-side positioning
  primitives (`node-width`, `rank-gap`, `layout`, `layered-fallback`)
  are GONE. xyflow + elkjs own positioning now. The substrate-agnostic
  graph parse (`parse-definition`, `node-id`, `highlight-id`, the
  `event-segment` + `edge-label` xstate-label builders) survives
  because it's pure data → data and used by SCXML / Mermaid emitters
  too."
  (:require [day8.re-frame2-machines-viz.chart.layout :as mv-layout]))

;; ---- public surface ----------------------------------------------------

(def parse-definition  mv-layout/parse-definition)
(def node-id           mv-layout/node-id)
(def highlight-id      mv-layout/highlight-id)
(def event-segment     mv-layout/event-segment)
(def edge-label        mv-layout/edge-label)
