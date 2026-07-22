(ns re-frame.freehand.compiled-views
  "The `tree-views` declarations, PROMOTED.

  Every declaration below is its twin in
  [[re-frame.freehand.tree-views]] with `{:compiled true}` added and
  nothing else changed — same docstring, same parameter vector, same
  body, character for character. That is not a convention this namespace
  asks a reader to trust: `compiled-source-delta-jvm-test` reads both
  files and proves the only textual difference is the option map, and
  `compiled-promotion-cljs-test` renders these against the SAME
  `FH-STRUCT-006` fixture rows — the same call sites, the same pinned
  trees — that the interpreted twins are pinned to.

  The two files exist separately for one uninteresting reason: a view id
  is derived from where a declaration LIVES, so two declarations of the
  same name cannot share a namespace. Everything else about them is
  identical, which is the point."
  (:require [re-frame.freehand :as v]))

(v/defview panel
  "Forwards its children beneath an owned heading — the ordinary
  children-forwarding shape."
  {:compiled true}
  [{:keys [title children]}]
  [:section.panel [:h2 title] children])

(v/defview nothing
  "Renders nothing at all — a nil-rooted view, which must still be a
  boundary node so it stays addressable."
  {:compiled true}
  [_]
  nil)

(v/defview pair
  "Fragment-rooted: its two children are adopted by the boundary rather
  than sitting inside a redundant fragment."
  {:compiled true}
  [_]
  [:<> [:i "a"] [:b "b"]])

(v/defview row
  "A keyed list row — the child boundary a `for` run mounts."
  {:compiled true}
  [{:keys [label]}]
  [:li.row label])

(v/defview page
  "The composite the mounted browser assertion renders: sugar, a flag-map
  class, a converted attribute name, static text, and a keyed run of child
  boundaries."
  {:compiled true}
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
  {:compiled true}
  [{:keys [width]}]
  [:circle {:cx 12 :cy 12 :r 6 :stroke-width width}])

(v/defview props-page
  "The composite the React prop-grammar assertion mounts: HTML author
  spellings that React canonicalises, an SVG attribute directly and
  through a boundary, and a `data-*` name whose casing must survive."
  {:compiled true}
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
  "Fixture view-name keyword -> the declared view. Keyed identically to
  `tree-views/by-name`, so one fixture table drives both."
  {:panel      panel
   :nothing    nothing
   :pair       pair
   :row        row
   :page       page
   :mark       mark
   :props-page props-page})
