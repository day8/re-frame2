(ns re-frame.freehand.spike.er01.vdollar
  "SPIKE SCAFFOLDING — ER-01 arm $. Deleted before this bead's PR.

  The same table, written with the `$` constructor. The declaration is an
  ORDINARY interpreted one — no `{:compiled true}`, no compiler, no
  grammar — because a body that returns an already-built node needs
  nothing from either: `node/collect` already takes the `node?` branch on
  it. That is the finding, not a convenience."
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.spike.er01.dollar :refer [$]]))

(v/defview table
  "A windowed table: a header run of `cols` cells, then `rows` keyed row
  elements each holding a keyed run of `cols` cells."
  [{:keys [rows cols]}]
  ($ :div.vtable
     ($ :div.vthead
        (for [c (range cols)]
          ($ :div.vth {:key c} (str "c" c))))
     ($ :div.vtbody
        (for [r rows]
          ($ :div.vtrow {:key (:id r) :data-index (:index r)}
             (for [c (range cols)]
               ($ :div.vtcell {:key c} (str (:id r) ":" c))))))))
