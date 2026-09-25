(ns day8.re-frame2-xray.panels.epoch.icons
  "Inline SVG glyphs used by the Epoch panel:

  - **ExternalLink** — 13×13 lucide glyph trailing click-to-source
    affordances. The hiccup form lives in `panels/event/icons` and is
    re-exported from here, so both import paths render one
    Figma-authority glyph.
  - **CornerDownRight** — 13×13 lucide-style arrow used in the
    handler step's `:db` diff / `:fx` sub-headers (the HANDLER row's
    DB CHANGES + FX sub-blocks).

  Pure data → hiccup (a static svg vector); JVM-portable so panel
  tests can render the tree via `clojure -M:test`."
  (:require [day8.re-frame2-xray.panels.event.icons :as event-icons]))

;; ---- External link -----------------------------------------------------

(defn external-link
  "Re-export of `panels.event.icons/external-link` — the lucide-style
  open-in-editor glyph (13×13). Same hiccup, same currentColor stroke.
  One Figma-authority glyph keeps the affordance vocabulary uniform
  across surfaces."
  []
  (event-icons/external-link))

;; ---- Corner down right --------------------------------------------------

(def ^:private corner-down-right-svg
  "Lucide `corner-down-right` icon as a hiccup-shaped svg. 13×13
  square, `viewBox 0 0 24 24`, `stroke: currentColor` so the glyph
  rides the surrounding text colour. Used in the HANDLER step's
  sub-headers (`:db diff` / `:fx`) to signal indented continuation."
  [:svg {:width            "13"
         :height           "13"
         :viewBox          "0 0 24 24"
         :fill             "none"
         :stroke           "currentColor"
         :stroke-width     "2"
         :stroke-linecap   "round"
         :stroke-linejoin  "round"
         :aria-hidden      "true"
         :focusable        "false"}
   [:polyline {:points "15 10 20 15 15 20"}]
   [:path     {:d "M4 4v7a4 4 0 0 0 4 4h12"}]])

(defn corner-down-right
  "Render the lucide `corner-down-right` glyph. Inherits its colour
  from the enclosing element via `currentColor` so the arrow reads
  as part of the section header. Always returns the same static
  hiccup vector — the fn-form lets call sites read as a component."
  []
  corner-down-right-svg)

;; ---- Warning triangle ---------------------------------------------------
;;
;; There is no warning-triangle SVG: the schema-violation title row uses
;; the Unicode `⚠` glyph inline.
