(ns re-frame.bench.hicasso.z3vlz-slim-substrate
  "The reagent-slim substrate under test (rf2-z3vlz).

  THE `:require` LIST OF THIS NAMESPACE IS THE EXPERIMENT. It names
  `reagent2.*` and the reagent-slim adapter and NOTHING ELSE from any
  other substrate — no `reagent.core`, no `uix.core` — so a bundle whose
  entry reaches the probe only through here compiles reagent-slim ALONE.
  That is the single-substrate arm the bead asks for, and adding a
  convenience require here would silently destroy it.

  The mount door, the drain and the raw control are all reagent-slim's own
  documented surfaces:

    - `reagent2.dom.client/create-root` + `render` is what a reagent-slim
      application calls;
    - the drain is `reagent2.dom.client/flush-render!` wrapping
      `reagent2.ratom/flush!` — SETTLE, THEN RENDER. Both halves are
      load-bearing and this is the same drain HD-008's arm used, so a
      difference between the two runs cannot be the drain."
  (:require [re-frame.adapter.reagent-slim :as slim-adapter]
            [re-frame.bench.hicasso.z3vlz-probe :as probe]
            [reagent2.core :as r2]
            [reagent2.dom.client :as rdc2]
            [reagent2.ratom :as ratom2]))

(defonce ^:private raw-cells (r2/atom (vec (repeat probe/cells-n 0))))

(defn- raw-list
  "The positive control's component: plain reagent-slim reading a plain
  `reagent2.core/atom`, no re-frame on the path."
  []
  [:ul.grid {:role "list"}
   (for [i (range probe/cells-n)]
     ^{:key i} [:li.row
                [:span.lbl "cell "]
                [:span.cell {:data-i i} (str (get @raw-cells i))]])])

(def adapter slim-adapter/adapter)

(def substrate
  {:label       :reagent-slim
   :create-root (fn [container] (rdc2/create-root container))
   :render      (fn [root tree] (rdc2/render root tree))
   :unmount     (fn [root] (rdc2/unmount root))
   ;; reagent-slim's drain brings its OWN `flushSync` boundary, and its
   ;; `f` slot is exactly where the subscription-graph settle belongs.
   :drain!      (fn [] (rdc2/flush-render! (fn [] (ratom2/flush!))))
   :raw-element (fn [] [raw-list])
   :raw-write!  (fn [v] (reset! raw-cells (vec (repeat probe/cells-n v))))})
