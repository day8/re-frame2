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

(def by-name
  "Fixture view-name keyword -> the declared view. A fixture is EDN, so it
  names a view rather than carrying one."
  {:panel   panel
   :nothing nothing
   :pair    pair
   :row     row
   :page    page})
