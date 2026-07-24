(ns re-frame.freehand.tree-views
  "The declared views the `FH-STRUCT` rows render.

  They live in a namespace of their own, and not inside a suite, for one
  reason: a view's `:view-id` is derived from the namespace it is declared
  in, and a `FH-STRUCT` fixture pins expected trees LITERALLY — view-ids
  included. Declaring the views here gives those ids a stable spelling
  that a fixture can name, and lets the structural suite (both hosts) and
  the mounted browser suite render the SAME declarations rather than two
  look-alikes.

  Everything here is host-neutral: no subscriptions, no host objects, no
  React. That is what makes the same declaration renderable by both
  emitters."
  (:require [re-frame.freehand :as v]))

(v/defview panel
  "Forwards its children beneath an owned heading — the ordinary
  children-forwarding shape."
  [{:keys [title children]}]
  [:section.panel [:h2 title] children])

(v/defview nothing
  "Renders nothing at all — a nil-rooted view, which must still be a
  boundary node so it stays addressable."
  [_]
  nil)

(v/defview pair
  "Fragment-rooted: its two children are adopted by the boundary rather
  than sitting inside a redundant fragment."
  [_]
  [:<> [:i "a"] [:b "b"]])

(v/defview row
  "A keyed list row — the child boundary a `for` run mounts."
  [{:keys [label]}]
  [:li.row label])

(v/defview page
  "The composite the mounted browser assertion renders: sugar, a flag-map
  class, a converted attribute name, static text, and a keyed run of child
  boundaries."
  [{:keys [items]}]
  [:section.panel#main {:class {:open true :busy false} :tab-index 3}
   [:h2 "Details"]
   [:ul.rows
    (for [i items]
      [row {:key i :label i}])]])

(v/defview mark
  "An SVG leaf declared as its own view, so the SAME authored attribute is
  mounted once directly under `<svg>` and once across a declared-view
  boundary. React renders a boundary as a real component, so the body runs
  with no walk above it — which is exactly where a context-sensitive
  attribute rule loses its context."
  [{:keys [width]}]
  [:circle {:cx 12 :cy 12 :r 6 :stroke-width width}])

(v/defview props-page
  "The composite the React prop-grammar assertion mounts: HTML author
  spellings that React canonicalises, an SVG attribute directly and
  through a boundary, and a lowercase `data-*` name that reaches the DOM
  verbatim (a mixed-case `data-*` is refused before it can mount — Spec
  004B §Attribute names, option c — so the page carries only the lowercase
  spelling)."
  [_]
  [:section#props {:tab-index 3 :data-priority "high"}
   ;; contentEditable rides a CHILDLESS element on purpose: React warns
   ;; about a contentEditable subtree it also manages, and that warning is
   ;; about the declaration rather than about the prop spelling.
   [:span#ce {:content-editable true}]
   [:form#f {:accept-charset "utf-8"}
    [:label {:for "n"} "Name"]
    [:input#n {:read-only true :max-length 8}]]
   [:svg#s {:view-box "0 0 24 24"}
    [:rect#r {:x 1 :y 1 :width 22 :height 22 :stroke-width 2 :fill-opacity 0.5}]
    [mark {:width 3}]]])

(def by-name
  "Fixture view-name keyword -> the declared view. A fixture is EDN, so it
  names a view rather than carrying one."
  {:panel      panel
   :nothing    nothing
   :pair       pair
   :row        row
   :page       page
   :mark       mark
   :props-page props-page})
