(ns day8.re-frame2-xray.panels.epoch.icons
  "Inline SVG glyphs used by the Epoch panel (rf2-sc3r1). Two icons
  are required per the bead body's §Icon Requirements:

  - **ExternalLink** — 13×13 lucide glyph trailing click-to-source
    affordances. The hiccup form lives in `panels/event/icons` (a
    leftover ns from the retired Event panel that is now re-exported
    from here); the re-export keeps a single Figma-authority glyph
    while preserving the existing import path.
  - **CornerDownRight** — 13×13 lucide-style arrow used in the
    handler step's `:db` diff / `:fx` sub-headers (per the bead body's
    §3 HANDLER row design — DB CHANGES + FX sub-blocks).

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
  sub-headers (`:db diff` / `:fx`) to signal indented continuation
  per the bead body."
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
  hiccup vector — the fn-form is preserved so call sites read as
  a component."
  []
  corner-down-right-svg)

;; ---- Warning triangle (retired with rf2-xgeag) --------------------------
;;
;; The `alert-triangle` SVG was used by the now-retired aggregate
;; SCHEMA-VIOLATIONS step's header (rf2-17vxj). With rf2-xgeag's
;; inline sub-block + tail-step shape the title row uses the Unicode
;; `⚠` glyph inline; no SVG required.

;; ---- Arrow right (cascade-link) -----------------------------------------

(def ^:private arrow-right-svg
  "Lucide `arrow-right` icon as a hiccup-shaped svg. 13×13 square,
  `viewBox 0 0 24 24`, `stroke: currentColor`. Used by the
  CHILD-DISPATCHES section (rf2-yx1ae) for the per-child 'jump to'
  affordance — the arrow signals 'follow this dispatch to the
  child cascade'."
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
   [:line     {:x1 "5" :y1 "12" :x2 "19" :y2 "12"}]
   [:polyline {:points "12 5 19 12 12 19"}]])

(defn arrow-right
  "Render the lucide `arrow-right` glyph (rf2-yx1ae). Inherits
  colour from the enclosing element via `currentColor`."
  []
  arrow-right-svg)
