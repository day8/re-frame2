(ns re-frame.freehand.bench.b4-views
  "B4's edited surfaces, INTERPRETED — a four-field form and an editing
  grid, each beside a heavy sibling that a ticker keeps dirty.

  Its twin in [[re-frame.freehand.bench.b4-views-compiled]] is this file's
  declarations with `{:compiled true}` added and nothing else changed;
  `compiled-source-delta-jvm-test` reads both files and proves it. That
  sameness is what makes B4's counts a claim about LOWERING: a
  per-keystroke count taken over two surfaces that are not the same
  surface is a count of two surfaces.

  Every field is a paved-path controlled input — a `value` from a
  subscription and a literal event vector on `:on-input` — so each takes
  the synchronous door lane, which is the lane a browser's editing
  behaviour actually depends on.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.bench.b4-instrument :as inst]))

(v/defview field
  "One controlled field. Its own subscription, its own committed handler,
  and a witness attribute naming the view that rendered it."
  [{:keys [idx]}]
  [:input.field {:id        (str "field-" idx)
                 :data-view (inst/rendered! :field)
                 :value     (v/sub [:b4/field idx])
                 :on-input  [:b4/type-field idx :re-frame.freehand/value]}])

(v/defview form
  "The four-field form — a keyed run of controlled fields under one
  owner, which is the shape a real edit screen has."
  [{:keys [fields]}]
  [:section.form {:data-view (inst/rendered! :form)}
   (for [i (range fields)]
     [field {:key i :idx i}])])

(v/defview cell
  "One cell of the editing grid. The same controlled shape as a form
  field, at grid cardinality."
  [{:keys [idx]}]
  [:input.cell {:id        (str "cell-" idx)
                :data-view (inst/rendered! :cell)
                :value     (v/sub [:b4/cell idx])
                :on-input  [:b4/type-cell idx :re-frame.freehand/value]}])

(v/defview grid
  "The editing grid — one boundary and one subscription per cell, which
  is where a per-keystroke count stops being a small number."
  [{:keys [cells]}]
  [:section.grid {:data-view (inst/rendered! :grid)}
   (for [i (range cells)]
     [cell {:key i :idx i}])])

(v/defview heavy
  "The contention. It subscribes to a ticker and renders a run of rows,
  so a tick is real reconciliation work happening beside an edit rather
  than a flag being flipped."
  [{:keys [width]}]
  [:ul.heavy {:data-view (inst/rendered! :heavy)
              :data-tick (str (v/sub [:b4/tick]))}
   (for [i (range width)]
     [:li.heavy-row {:key i} i])])

(v/defview form-stage
  "The form, and the dirty sibling, under one root — so the edit and the
  contention are in one commit discipline, on one page, at one moment."
  [{:keys [fields width]}]
  [:div.stage
   [form {:fields fields}]
   [heavy {:width width}]])

(v/defview grid-stage
  "The grid, and the dirty sibling, under one root."
  [{:keys [cells width]}]
  [:div.stage
   [grid {:cells cells}]
   [heavy {:width width}]])
