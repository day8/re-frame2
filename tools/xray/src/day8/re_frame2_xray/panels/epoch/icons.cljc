(ns day8.re-frame2-xray.panels.epoch.icons
  "Inline SVG glyphs used by the Epoch panel (rf2-sc3r1). Two icons
  are required per the bead body's §Icon Requirements:

  - **ExternalLink** — 13×13 lucide glyph trailing click-to-source
    affordances. Already shipped under `panels/event/icons` for the
    Event lens — the Epoch panel re-uses that hiccup form via a
    re-export so both panels read the same Figma authority.
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
  Both panels render the same Figma authority glyph so a Xray operator
  reads the affordance vocabulary as one verb."
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

;; ---- Warning triangle ---------------------------------------------------

(def ^:private alert-triangle-svg
  "Lucide `alert-triangle` icon as a hiccup-shaped svg. 13×13
  square, `viewBox 0 0 24 24`, `stroke: currentColor`. Used by the
  SCHEMA-VIOLATIONS section header (rf2-17vxj) to signal warning
  chrome without rising to the alarmist `:error` tone of an `✗`."
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
   [:path {:d "M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0Z"}]
   [:line {:x1 "12" :y1 "9"  :x2 "12" :y2 "13"}]
   [:line {:x1 "12" :y1 "17" :x2 "12.01" :y2 "17"}]])

(defn alert-triangle
  "Render the lucide `alert-triangle` glyph (rf2-17vxj). Inherits
  colour from the enclosing element via `currentColor` so the
  glyph rides the warning-tone section header."
  []
  alert-triangle-svg)

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
