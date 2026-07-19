(ns re-frame.ui.digest-probe.warm-watch.view
  "A consumer view that references :probe-card WITHOUT a `:require` edge to the
  declaring `card` source. Shadow's dependency tracking therefore never schedules
  this source when `card`'s declaration changes; only the coarse manifest-change
  invalidation (rf2-vxgfnd.141, dimension 3) forces it to recompile so it re-bakes
  its property/attribute lowering against the changed manifest. Whether this
  source is recompiled on a warm pass is exactly the coarse-invalidation witness
  the runner asserts.

  It references :model AND :size so a manifest shrink of EITHER is observable in
  the baked classification, and :data-x is always a plain attribute."
  (:require [re-frame.ui :refer [defview]]))

(defview probe-view []
  [:probe-card {:model "m" :size "s" :data-x "d"}])
