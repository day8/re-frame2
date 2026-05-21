(ns day8.re-frame2-causa.chart.svg
  "Re-export shim for the MachineChart primitives.

  rf2-o9arp — the canonical implementation lives in
  `day8.re-frame2-machines-viz.*`. This ns survives as a thin
  re-export so Causa-side panels (machine-after-rings, cluster
  sparklines) keep importing through `day8.re-frame2-causa.chart.svg/...`
  without rewiring every call site at once.

  rf2-gpzb4 (2026-05-21 xyflow override) — the `render` +
  `render-from-definition` + `compound-containers` SVG entries are
  GONE. The chart's rendering surface moved to the xyflow-based
  `day8.re-frame2-machines-viz.chart/MachineChart` Reagent component;
  the SVG-hiccup contract is no longer the public render API.

  What survives:

    - `countdown-ring` — Causa's `panels/machine_after_rings.cljs`
      overlay still paints rings ON TOP of the chart canvas. The
      ring primitive is host-side hiccup; positioning is the host's
      problem (the overlay walks the chart's DOM to find node
      bboxes).
    - `sparkline` — used by cluster-row state-change rate indicators
      and various Story / Causa stats surfaces.

  Both primitives now live in
  `day8.re-frame2-machines-viz.chart.primitives` per the migration."
  (:require [day8.re-frame2-machines-viz.chart.primitives :as mv-prim]))

;; ---- public surface ----------------------------------------------------

(def countdown-ring mv-prim/countdown-ring)
(def sparkline      mv-prim/sparkline)
