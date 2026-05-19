(ns day8.re-frame2-causa.chart.elk-layout
  "Re-export shim for the MachineChart ELK-driven layout.

  rf2-o9arp — the canonical implementation lives in
  `day8.re-frame2-machines-viz.chart.elk-layout`. This ns survives
  as a thin re-export so the Causa-side panel keeps importing
  through `day8.re-frame2-causa.chart.elk-layout/...` without
  rewiring every call site at once."
  (:require [day8.re-frame2-machines-viz.chart.elk-layout :as mv-elk]))

;; ---- public surface ----------------------------------------------------

(def elk-status               mv-elk/elk-status)
(def ensure-elk!              mv-elk/ensure-elk!)
(def reset-elk-state-for-test! mv-elk/reset-elk-state-for-test!)
(def ->elk-graph              mv-elk/->elk-graph)
(def elk-result->chart-layout mv-elk/elk-result->chart-layout)
(def cached-layout            mv-elk/cached-layout)
(def reset-cache-for-test!    mv-elk/reset-cache-for-test!)
(def compute-layout!          mv-elk/compute-layout!)
(def layout-or-fallback       mv-elk/layout-or-fallback)
