(ns re-frame.bench.hicasso.z3vlz-mixed
  "RUNG 4 — THE BEAD'S OWN BUNDLE. All three adapters compiled together —
  stock `reagent`, `reagent2` (reagent-slim) and `uix` — exactly as
  HD-008's `:advanced` bench bundle does, with reagent-slim installed.

  This rung exists to prove the probe REPRODUCES the reported symptom
  before any conclusion is drawn from the rungs below it. A single-substrate
  bundle that re-renders is only evidence about the bead if the mixed
  bundle, in the same probe, does not: otherwise the difference could be
  the probe rather than the bundle.

  `?install=reagent` runs the identical probe with stock Reagent installed
  — the bead's positive control, in the bead's own bundle."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.z3vlz-probe :as probe]
            [re-frame.bench.hicasso.z3vlz-reagent-substrate :as stock]
            [re-frame.bench.hicasso.z3vlz-slim-substrate :as slim]
            [re-frame.core :as rf]))

(def ^:export adapter-ref uix-adapter/adapter)

(defn- install-stock? []
  (boolean (some->> (some-> js/window .-location .-search)
                    (re-find #"install=reagent"))))

(defn ^:export -main []
  (set! (.-Z3VLZ_UIX_ADAPTER js/window) (pr-str (:kind adapter-ref)))
  (let [stock? (install-stock?)]
    (rf/init! (if stock? stock/adapter slim/adapter))
    (probe/run-probe! (if stock? stock/substrate slim/substrate)
                {:bundle      :mixed
                 :installed   (if stock? :reagent :reagent-slim)
                 :compiled-in [:reagent2 :reagent :uix]}
                (if stock? :z3vlz/mixed-stock :z3vlz/mixed-slim))))
