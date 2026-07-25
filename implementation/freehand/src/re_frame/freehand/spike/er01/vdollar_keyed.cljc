(ns re-frame.freehand.spike.er01.vdollar-keyed
  "SPIKE SCAFFOLDING — ER-01 arm $k. Deleted before this bead's PR.

  Arm $ with the compiled emitter's `node/keyed-run` proof restored at
  both list sites. Its only purpose is ATTRIBUTION: it isolates how much
  of arm C's allocation over arm $ is the key-uniqueness proof rather
  than lowering overhead."
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.node :as node]
            #?(:clj [re-frame.freehand.spike.er01.dollar :refer [$]]))
  #?(:cljs (:require-macros [re-frame.freehand.spike.er01.dollar :refer [$]])))

(v/defview table
  "A windowed table: a header run of `cols` cells, then `rows` keyed row
  elements each holding a keyed run of `cols` cells."
  [{:keys [rows cols]}]
  ($ :div.vtable
     ($ :div.vthead
        (node/keyed-run
          (for [c (range cols)]
            ($ :div.vth {:key c} (str "c" c)))))
     ($ :div.vtbody
        (node/keyed-run
          (for [r rows]
            ($ :div.vtrow {:key (:id r) :data-index (:index r)}
               (node/keyed-run
                 (for [c (range cols)]
                   ($ :div.vtcell {:key c} (str (:id r) ":" c))))))))))
