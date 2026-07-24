(ns re-frame.freehand.bench.b4-views-compiled
  "B4's edited surfaces, COMPILED — [[re-frame.freehand.bench.b4-views]]
  PROMOTED.

  Every declaration below is its interpreted twin with `{:compiled true}`
  added and nothing else changed — same docstring, same parameter vector,
  same body, character for character. `compiled-source-delta-jvm-test`
  reads both files and proves it, which is what lets B4 report one set of
  per-keystroke counts per mode and call the difference between them a
  difference of lowering.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.bench.b4-instrument :as inst]))

(v/defview field
  "One controlled field. Its own subscription, its own committed handler,
  and a witness attribute naming the view that rendered it."
  {:compiled true}
  [{:keys [idx]}]
  [:input.field {:id        (str "field-" idx)
                 :data-view (inst/rendered! :field)
                 :value     (v/sub [:b4/field idx])
                 :on-input  [:b4/type-field idx :re-frame.freehand/value]}])

(v/defview form
  "The four-field form — a keyed run of controlled fields under one
  owner, which is the shape a real edit screen has."
  {:compiled true}
  [{:keys [fields]}]
  [:section.form {:data-view (inst/rendered! :form)}
   (for [i (range fields)]
     [field {:key i :idx i}])])

(v/defview cell
  "One cell of the editing grid. The same controlled shape as a form
  field, at grid cardinality."
  {:compiled true}
  [{:keys [idx]}]
  [:input.cell {:id        (str "cell-" idx)
                :data-view (inst/rendered! :cell)
                :value     (v/sub [:b4/cell idx])
                :on-input  [:b4/type-cell idx :re-frame.freehand/value]}])

(v/defview grid
  "The editing grid — one boundary and one subscription per cell, which
  is where a per-keystroke count stops being a small number."
  {:compiled true}
  [{:keys [cells]}]
  [:section.grid {:data-view (inst/rendered! :grid)}
   (for [i (range cells)]
     [cell {:key i :idx i}])])

(v/defview heavy
  "The contention. It subscribes to a ticker and renders a run of rows,
  so a tick is real reconciliation work happening beside an edit rather
  than a flag being flipped."
  {:compiled true}
  [{:keys [width]}]
  [:ul.heavy {:data-view (inst/rendered! :heavy)
              :data-tick (str (v/sub [:b4/tick]))}
   (for [i (range width)]
     [:li.heavy-row {:key i} i])])

(v/defview form-stage
  "The form, and the dirty sibling, under one root — so the edit and the
  contention are in one commit discipline, on one page, at one moment."
  {:compiled true}
  [{:keys [fields width]}]
  [:div.stage
   [form {:fields fields}]
   [heavy {:width width}]])

(v/defview grid-stage
  "The grid, and the dirty sibling, under one root."
  {:compiled true}
  [{:keys [cells width]}]
  [:div.stage
   [grid {:cells cells}]
   [heavy {:width width}]])
