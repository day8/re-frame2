(ns re-frame.freehand.spike.er01.compiled
  "SPIKE SCAFFOLDING — ER-01 arm C. Deleted before this bead's PR.

  Arm I with `{:compiled true}` added and nothing else changed."
  (:require [re-frame.freehand :as v]))

(v/defview table
  "A windowed table: a header run of `cols` cells, then `rows` keyed row
  elements each holding a keyed run of `cols` cells."
  {:compiled true}
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
