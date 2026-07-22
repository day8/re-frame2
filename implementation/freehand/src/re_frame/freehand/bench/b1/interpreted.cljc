(ns re-frame.freehand.bench.b1.interpreted
  "The B1 template, INTERPRETED — one boundary over a finite template.

  Its twin in [[re-frame.freehand.bench.b1.compiled]] is this declaration
  with `{:compiled true}` added and nothing else changed, character for
  character; `compiled-source-delta-jvm-test` reads both files and proves
  it. The two files exist separately only because a view id is derived
  from where a declaration LIVES, so two declarations of one name cannot
  share a namespace.

  The template is shaped for what B1 measures and nothing else. It is ONE
  boundary — the mount-storm shape with a boundary per row is B2's
  workload, and mixing them would attribute boundary cost to the
  interpretation walk. Under that one boundary sits a fixed skeleton and a
  keyed run of rows whose body is a fixed group of elements, so the node
  count is an exact linear function of the `:rows` fixture parameter and
  the walk has literal elements, a list site, dynamic text and static text
  to do work on.

  Everything here is host-neutral: no subscriptions, no host objects, no
  React. That is what lets the same declaration be rendered, and measured,
  by both emitters on both hosts."
  (:require [re-frame.freehand :as v]))

(v/defview template
  "A fixed skeleton over a keyed run of rows, each row a fixed group of
  elements — the finite template B1 renders in both modes."
  [{:keys [rows]}]
  [:section.grid
   [:h2.title "Grid"]
   [:ul.rows
    (for [i (range rows)]
      [:li.row {:key i}
       [:span.index i]
       [:span.label "row"]
       [:em.badge "*"]])]])
