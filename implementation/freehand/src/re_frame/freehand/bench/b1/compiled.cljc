(ns re-frame.freehand.bench.b1.compiled
  "The B1 template, COMPILED — the [[re-frame.freehand.bench.b1.interpreted]]
  declaration PROMOTED.

  The declaration below is its twin with `{:compiled true}` added and
  nothing else changed — same docstring, same parameter vector, same body,
  character for character. That is not a convention this namespace asks a
  reader to trust: `compiled-source-delta-jvm-test` reads both files and
  proves the only textual difference is the option map, and B1's own
  deterministic gate renders both arms every iteration and requires their
  structural output to be EQUAL.

  Both facts are load-bearing for the measurement. A timing comparison
  between two arms that are not the same template is a comparison of two
  templates."
  (:require [re-frame.freehand :as v]))

(v/defview template
  "A fixed skeleton over a keyed run of rows, each row a fixed group of
  elements — the finite template B1 renders in both modes."
  {:compiled true}
  [{:keys [rows]}]
  [:section.grid
   [:h2.title "Grid"]
   [:ul.rows
    (for [i (range rows)]
      [:li.row {:key i}
       [:span.index i]
       [:span.label "row"]
       [:em.badge "*"]])]])
