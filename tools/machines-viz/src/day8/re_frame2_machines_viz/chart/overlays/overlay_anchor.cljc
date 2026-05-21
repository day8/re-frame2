(ns day8.re-frame2-machines-viz.chart.overlays.overlay-anchor
  "Pure anchoring geometry shared by the xyflow chart's HTML/SVG
  overlays (rf2-3ow55 · xyflow Phase 2 — the `:spawn-all` join
  inspector + the cancellation-cascade visualiser).

  ## Why a separate .cljc

  Each overlay walks the rendered chart DOM to find a bearing node's
  bounding rect, then anchors a card / waterfall next to it. The DOM
  walk + ref plumbing is CLJS-only (lives in each overlay's `.cljs`),
  but the math that turns a node's viewport rect + the overlay
  container's rect into the card's overlay-local `{:x :y}` anchor is
  pure data → data, so it lives here where the JVM test corpus can
  pin it. Same split as `after_rings_geometry.cljc`.

  ## Coordinate model

  `getBoundingClientRect` returns viewport-relative rects (`{:left
  :top :width :height}`). An overlay `<div>`/`<svg>` is absolutely
  positioned at the chart wrapper's top-left, so a node anchor in
  overlay-local space is the node's viewport position MINUS the
  container's viewport origin. xyflow bakes pan/zoom into the rendered
  rects so no separate viewport transform is needed (same posture as
  `after_rings_geometry`)."
  (:require [clojure.string :as str]))

(def card-gap-px
  "Gap (px) between the bearing node's bounding box and the anchored
  overlay card so the card sits clear of the node + its affordance."
  12)

(defn node->card-testid
  "The xyflow node `data-testid` an overlay queries for a bearing
  node-id. Mirrors `chart.nodes/state-node`'s `(str \"rf-mv-chart-node-
  <id>\")` contract. Returns nil for a nil / blank node-id so the
  overlay skips rather than querying a garbage selector."
  [node-id]
  (when (and node-id (not (str/blank? (str node-id))))
    (str "rf-mv-chart-node-" node-id)))

(defn anchor-right-of
  "Anchor a card to the RIGHT of a bearing node. Takes the node's
  viewport rect + the overlay container's rect; returns
  `{:x :y :node-cx :node-cy}` in overlay-local coordinates (the card's
  top-left + the node's centre for the connector line), or nil when
  either rect is missing / degenerate.

  Pure fn — JVM-runnable. Used by the `:spawn-all` join inspector
  (the card sits beside the spawn-all-bearing state)."
  [node-rect container-rect]
  (when (and node-rect container-rect
             (pos? (or (:width node-rect) 0))
             (pos? (or (:height node-rect) 0)))
    (let [cx-origin (or (:left container-rect) 0)
          cy-origin (or (:top container-rect) 0)
          nl        (- (or (:left node-rect) 0) cx-origin)
          nt        (- (or (:top node-rect) 0) cy-origin)
          nw        (double (or (:width node-rect) 0))
          nh        (double (or (:height node-rect) 0))]
      {:x       (+ nl nw card-gap-px)
       :y       nt
       :node-cx (+ nl (/ nw 2.0))
       :node-cy (+ nt (/ nh 2.0))})))

(defn anchor-below
  "Anchor a card/waterfall BELOW a bearing node. Same inputs as
  `anchor-right-of`; returns `{:x :y :node-cx :node-cy}` with the
  anchor at the node's bottom edge + the gap. Used by the
  cancellation-cascade waterfall (which flows downward from the
  parent's exit). Pure fn — JVM-runnable."
  [node-rect container-rect]
  (when (and node-rect container-rect
             (pos? (or (:width node-rect) 0))
             (pos? (or (:height node-rect) 0)))
    (let [cx-origin (or (:left container-rect) 0)
          cy-origin (or (:top container-rect) 0)
          nl        (- (or (:left node-rect) 0) cx-origin)
          nt        (- (or (:top node-rect) 0) cy-origin)
          nw        (double (or (:width node-rect) 0))
          nh        (double (or (:height node-rect) 0))]
      {:x       nl
       :y       (+ nt nh card-gap-px)
       :node-cx (+ nl (/ nw 2.0))
       :node-cy (+ nt (/ nh 2.0))})))

(defn join-resolved?
  "Pure resolution check for a `:spawn-all` join given the join
  condition + the set of done child keys + total child count. Mirrors
  Spec 005 §`:spawn-all` join semantics (`:all` / `:any` / `{:n N}`).
  A `{:fn ...}` condition is opaque to the overlay (the host decides),
  so this returns the host-supplied `:resolved?` verbatim when present.

  Used by the join inspector to colour the 'Resolved' line + compute
  the 'waiting for K of N' remainder. Pure data → boolean."
  [{:keys [join children resolved?]}]
  (let [done   (count (filter :done? children))
        failed (count (filter :failed? children))
        total  (count children)]
    (cond
      (some? resolved?) (boolean resolved?)
      (= join :all)     (= done total)
      (= join :any)     (pos? done)
      (and (map? join)
           (:n join))   (>= done (:n join))
      ;; {:fn ...} or unknown — the host owns truth; default false.
      :else             (boolean (and (pos? total) (zero? failed)
                                      (= done total))))))

(defn join-summary
  "A short `\"K/N done\"` (+ failed/cancelled when present) summary
  string for a join spec's children. Pure data → string."
  [{:keys [children]}]
  (let [total     (count children)
        done      (count (filter :done? children))
        failed    (count (filter :failed? children))
        cancelled (count (filter :cancelled? children))]
    (str done "/" total " done"
         (when (pos? failed)    (str " · " failed " failed"))
         (when (pos? cancelled) (str " · " cancelled " cancelled")))))

;; ---- cancellation-cascade summary (pure, JVM-runnable) -----------------

(defn cascade-counts
  "Pure summary of a cancellation cascade's steps →
  `{:destroyed :aborted :cleaned}`. Used in the cascade card's header
  line. `:kind` ∈ `:exit` / `:destroy` / `:abort` / `:cleanup`.
  JVM-runnable."
  [steps]
  {:destroyed (count (filter #(= :destroy (:kind %)) steps))
   :aborted   (count (filter #(= :abort   (:kind %)) steps))
   :cleaned   (count (filter #(= :cleanup (:kind %)) steps))})

(defn cascade-summary-line
  "Pure header summary string for a cascade-spec (`{:steps [...]}`),
  e.g. `\"destroyed 1 actor · aborted 3 requests\"`. JVM-runnable."
  [{:keys [steps]}]
  (let [{:keys [destroyed aborted cleaned]} (cascade-counts steps)
        parts (cond-> []
                (pos? destroyed) (conj (str "destroyed " destroyed
                                            (if (= 1 destroyed) " actor" " actors")))
                (pos? aborted)   (conj (str "aborted " aborted
                                            (if (= 1 aborted) " request" " requests")))
                (pos? cleaned)   (conj (str cleaned " cleanup"
                                            (if (= 1 cleaned) "" "s"))))]
    (if (seq parts)
      (str/join " · " parts)
      "no cascade steps")))
