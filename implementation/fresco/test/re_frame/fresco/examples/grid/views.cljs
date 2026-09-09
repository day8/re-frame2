(ns re-frame.fresco.examples.grid.views
  "THE 100-CELL CONTROLLED GRID — one hundred fields, each written the way
  the guide writes one.

  [[cell]] is the four-field editor's `text-field` with a coordinate in
  place of a field keyword. That is the claim this application makes and
  the reason it is worth having beside the editor: **breadth costs
  nothing in authoring**. There is no virtualization, no memo hint, no
  batching call, no `shouldComponentUpdate`, and no second way of writing
  a field for when there are a hundred of them.

  ## Four boundary kinds, and only one of them is on the typing path

      grid       reads [::subs/dimensions]        — mount only
      grid-row   reads [::subs/dimensions]        — mount only
      row-total  reads [::subs/row-total row]     — when ITS row changes
      cell       reads [::subs/cell row col]      — when ITS cell changes

  A keystroke moves one cell's address. One cell body runs, and one row
  total runs because its row genuinely changed. Nothing else in the tree
  is notified — the layout bodies read a value a keystroke cannot touch,
  and the other ninety-nine cells read addresses the write did not
  reach. `grid.scaling-dom-cljs-test` measures that count at two grid
  sizes and asserts it does not move with the size.

  ## The keys are coordinates, and they have to be

  Every child in both `for` loops carries a `:key` derived from its
  position in the model. A list keyed by index reuses the wrong element
  the moment the order changes, and on a page of controlled fields that
  means a caret and a composition landing in the wrong cell with nothing
  on screen to say so.

  ## What was NOT needed here

  `defview`, `sub`, `::h/value`. That is the entire public surface a
  hundred controlled fields required — no `::h/revision` (the grid has no
  reset), no `h/event` (every intent is a vector, including the
  three-argument one), and nothing at all from the optional modules or
  the native tier — the `:require` below is the whole of it."
  (:require [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.examples.grid.events :as rf.fresco.examples.grid.events]
            [re-frame.fresco.examples.grid.subs :as rf.fresco.examples.grid.subs]))

(rf.fresco/defview cell
  "One controlled cell. Two props, one read, one intent.

  The intent carries THREE arguments — the row, the column and the
  marker — and is therefore positional. See `grid.events` §`::h/value` is
  positional here too."
  [{:keys [row col]}]
  (let [id (rf.fresco.examples.grid.events/cell-id row col)]
    [:td
     [:input {:id           id
              :type         "text"
              :size         4
              :aria-label   (rf.fresco.examples.grid.events/cell-label row col)
              :data-cell    id
              :value        (rf.fresco/sub [::rf.fresco.examples.grid.subs/cell row col])
              :on-input     [::rf.fresco.examples.grid.events/edit row col ::rf.fresco/value]}]]))

(rf.fresco/defview row-total
  "One row's sum.

  Its own boundary, reading its own row's derived value. Fold it into
  [[grid-row]] and every keystroke in the row would re-run the row's
  layout body and props-compare ten cells; here it re-runs one body that
  renders one number."
  [{:keys [row]}]
  [:td.total {:data-total (str row)} (str (rf.fresco/sub [::rf.fresco.examples.grid.subs/row-total row]))])

(rf.fresco/defview grid-row
  "One row: its cells and its total.

  Reads the dimensions, which do not move while anybody is typing, so
  this body runs at mount and not again."
  [{:keys [row]}]
  (let [{:keys [cols]} (rf.fresco/sub [::rf.fresco.examples.grid.subs/dimensions])]
    [:tr {:data-row (str row)}
     (for [col (range cols)]
       [cell {:key (str col) :row row :col col}])
     [row-total {:key "total" :row row}]]))

(rf.fresco/defview grid
  "The whole application."
  [_]
  (let [{:keys [rows]} (rf.fresco/sub [::rf.fresco.examples.grid.subs/dimensions])]
    [:main#hundred-cell-grid
     [:h1 "Grid"]
     [:table
      [:tbody
       (for [row (range rows)]
         [grid-row {:key (str row) :row row}])]]]))
