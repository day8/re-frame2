(ns re-frame.freehand.spike.er01.interpreted
  "SPIKE SCAFFOLDING — ER-01 arm I. Deleted before this bead's PR.

  The fixed-size virtual table, INTERPRETED: an ordinary Hiccup body
  under an ordinary declaration."
  (:require [re-frame.freehand :as v]))

(v/defview table
  "A windowed table: a header run of `cols` cells, then `rows` keyed row
  elements each holding a keyed run of `cols` cells."
  [{:keys [rows cols]}]
  [:div.vtable
   [:div.vthead
    (for [c (range cols)]
      [:div.vth {:key c} (str "c" c)])]
   [:div.vtbody
    (for [r rows]
      [:div.vtrow {:key (:id r) :data-index (:index r)}
       (for [c (range cols)]
         [:div.vtcell {:key c} (str (:id r) ":" c)])])]])
