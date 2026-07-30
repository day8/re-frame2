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
  `reagent2.core/atom`, no re-frame on the path.

  The deref is in the COMPONENT BODY, not inside the `for`. A deref that
  happens only when a lazy seq child is realised is captured by whatever
  reactive context realises it, which is not necessarily this component's
  render reaction — and a control whose reactivity depends on that
  subtlety cannot certify anything about the arm beside it."
  []
  (let [cells @raw-cells]
    [:ul.grid {:role "list"}
     (for [i (range probe/cells-n)]
       ^{:key i} [:li.row
                  [:span.lbl "cell "]
                  [:span.cell {:data-i i} (str (get cells i))]])]))

(def adapter slim-adapter/adapter)

(def substrate
  {:label       :reagent-slim
   :create-root (fn [container] (rdc2/create-root container))
   :render      (fn [root tree] (rdc2/render root tree))
   :unmount     (fn [root] (rdc2/unmount root))
   ;; reagent-slim's drain brings its OWN `flushSync` boundary, and its
   ;; `f` slot is exactly where the subscription-graph settle belongs.
   :drain!      (fn [] (rdc2/flush-render! (fn [] (ratom2/flush!))))
   ;; THE DOCUMENTED PRODUCTION CONTRACT: the write goes in `f`, so the
   ;; `forceUpdate` it provokes is issued INSIDE `flush-render!`'s
   ;; `react-dom/flushSync` boundary and commits before the call returns.
   ;; `reagent2.dom.client/flush-render!` says so in as many words, and the
   ;; adapter's own `reagent_slim_flush_render_dom_cljs_test` pins it.
   :drain-with! (fn [f] (rdc2/flush-render! (fn [] (f) (ratom2/flush!))))
   :raw-element (fn [] [raw-list])
   :raw-write!  (fn [v] (reset! raw-cells (vec (repeat probe/cells-n v))))})
