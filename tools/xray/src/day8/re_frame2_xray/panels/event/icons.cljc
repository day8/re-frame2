(ns day8.re-frame2-xray.panels.event.icons
  "Inline SVG glyphs — small lucide-style icons per the Figma authority
  (`tools/xray/design-reference/xray_devtools_reference.cljs`). Surviving
  utility re-exported from `panels.epoch.icons` (the Epoch panel
  superseded the original Event panel under rf2-5gl5r; the glyph set
  travelled with the rename).

  Shared, single-source-of-truth helper so every click-to-source
  affordance renders the SAME glyph at the SAME size with the SAME
  stroke + fill convention. The canonical glyph is the lucide-style
  `external-link` SVG (open window with an outbound arrow) — visually
  richer and more recognisably an 'open in editor' verb than the
  unicode `↗` arrow.

  Pure data → hiccup (a static svg vector); no substrate dependency.
  `.cljc` so the rendered tree is JVM-portable (the event-detail panel
  tests render the tree from `clojure -M:test`).")

(def ^:private external-link-svg
  "The lucide `external-link` icon as a hiccup-shaped svg vector. 13px
  square (the Figma authority's exact size), `viewBox 0 0 24 24` (the
  lucide-icons coordinate system), `stroke: currentColor` so the glyph
  inherits the surrounding link colour. `stroke-linecap` + `linejoin
  round` mirror the lucide default so the corners + endpoints match
  the rest of the lucide family."
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
   [:path     {:d "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"}]
   [:polyline {:points "15 3 21 3 21 9"}]
   [:line     {:x1 "10" :y1 "14" :x2 "21" :y2 "3"}]])

(defn external-link
  "Render the lucide `external-link` glyph — the canonical 'open in
  editor / open in new tab' verb on click-to-source affordances.
  Inherits its colour from the enclosing link via `currentColor` so
  the glyph reads as part of the link, not as separate chrome. Always
  returns the same static hiccup vector — the fn-form is preserved so
  call sites read as a component."
  []
  external-link-svg)
