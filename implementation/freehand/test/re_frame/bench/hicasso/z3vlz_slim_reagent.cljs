(ns re-frame.bench.hicasso.z3vlz-slim-reagent
  "RUNG 2 — reagent-slim WITH stock Reagent compiled alongside it.

  One namespace more than rung 1 and nothing else changed. If rung 1 is
  reactive and this is not, candidate (b) is established AND NARROWED to
  exactly this pair — stock `reagent` and `reagent2` in one bundle —
  without uix having to be in the room.

  `?install=reagent` runs the identical probe with the stock-Reagent
  substrate installed instead. That is the bead's positive control (the
  arm that passed 78 of 78 while the slim arm failed 78 of 78), preserved
  here as the brief requires: a negative result about reagent-slim is only
  meaningful beside a positive one taken in the same harness."
  (:require [re-frame.bench.hicasso.z3vlz-probe :as probe]
            [re-frame.bench.hicasso.z3vlz-reagent-substrate :as stock]
            [re-frame.bench.hicasso.z3vlz-slim-substrate :as slim]
            [re-frame.core :as rf]))

(defn install-stock?
  "`?install=reagent` selects the stock-Reagent control arm; anything else
  (including no query string) runs the arm under test."
  []
  (boolean (some->> (some-> js/window .-location .-search)
                    (re-find #"install=reagent"))))

(defn ^:export -main []
  (let [stock? (install-stock?)]
    (rf/init! (if stock? stock/adapter slim/adapter))
    (probe/run! (if stock? stock/substrate slim/substrate)
                {:bundle      :slim+reagent
                 :installed   (if stock? :reagent :reagent-slim)
                 :compiled-in [:reagent2 :reagent]}
                (if stock? :z3vlz/slim-reagent-stock :z3vlz/slim-reagent-slim))))
