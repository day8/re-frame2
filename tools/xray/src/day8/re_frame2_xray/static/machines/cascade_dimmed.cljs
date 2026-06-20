(ns day8.re-frame2-xray.static.machines.cascade-dimmed
  "Cascade pill — DIMMED in the Static surface.

  The Cancellation Cascade is a Dynamic-only surface (it composes
  against the trace ring buffer which is event-coupled — there is no
  spine in Static mode). The pill is rendered in the strip for
  muscle-memory consistency with the Dynamic sub-strip (same DOM, same
  letter mnemonic), but it's GREYED and the click is a no-op. A tooltip
  surfaces the why."
  (:require [day8.re-frame2-xray.theme.tokens
             :refer [tokens sans-stack type-scale]]))

(def tooltip-text
  "Surfaced as the pill's `title` AND its `aria-disabled` description."
  "Cancellation cascade is a Dynamic-only surface. Switch to Dynamic mode to view.")

(defn pill
  "Render the dimmed Cascade pill. The button is disabled — keyboard
  + click events are gated by the `disabled` attribute so screen
  readers announce 'unavailable' rather than 'click to view'."
  []
  [:button
   {:data-testid    "rf-xray-static-machines-pill-cascade"
    :role           "tab"
    :aria-disabled  "true"
    :aria-selected  "false"
    :disabled       true
    :title          tooltip-text
    :aria-label     (str "Cascade — disabled in Static. " tooltip-text)
    :style {:background    "transparent"
            :border        (str "1px dashed " (:border-default tokens))
            :border-radius "10px"
            :color         (:text-tertiary tokens)
            :cursor        "not-allowed"
            :font-family   sans-stack
            :font-size     (:caption type-scale)
            :font-weight   400
            :padding       "3px 12px"
            :white-space   "nowrap"
            ;; Faded — single-source for the dim signal.
            :opacity       0.5}}
   "Cascade"])
