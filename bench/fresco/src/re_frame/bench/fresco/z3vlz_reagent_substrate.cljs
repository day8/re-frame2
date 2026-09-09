(ns re-frame.bench.hicasso.z3vlz-reagent-substrate
  "The stock-Reagent substrate (rf2-z3vlz) — the bead's positive control,
  and the thing whose PRESENCE IN THE BUNDLE is candidate (b).

  This namespace does double duty, and both are deliberate:

  1. It is the arm that passed 78 of 78 in HD-008's harness while the slim
     arm failed 78 of 78. A negative result about reagent-slim is only
     meaningful beside it, so it is carried into every mixed bundle here
     and run through the identical probe.
  2. Requiring it is what MAKES a bundle mixed. Candidate (b) is that
     stock `reagent` and `reagent2` coexisting in one `:advanced` bundle
     is itself the fault; the way to test that is to add exactly this
     namespace and change nothing else.

  Its arms are USED, not merely required, in every bundle that names it —
  a require whose code `:advanced` could prove dead would not reproduce
  the bundle the bead describes."
  (:require ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.bench.hicasso.z3vlz-probe :as rf.bench.hicasso.z3vlz-probe]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [reagent.ratom :as reagent-ratom]))

(defonce ^:private raw-cells (r/atom (vec (repeat rf.bench.hicasso.z3vlz-probe/cells-n 0))))

(defn- raw-list []
  (let [cells @raw-cells]
    [:ul.grid {:role "list"}
     (for [i (range rf.bench.hicasso.z3vlz-probe/cells-n)]
       ^{:key i} [:li.row
                  [:span.lbl "cell "]
                  [:span.cell {:data-i i} (str (get cells i))]])]))

(def adapter rf.adapter.reagent/adapter)

(def substrate
  {:label       :reagent
   :create-root (fn [container] (rdc/create-root container))
   :render      (fn [root tree] (rdc/render root tree))
   :unmount     (fn [root] (rdc/unmount root))
   ;; SETTLE, THEN RENDER. `ratom/flush!` runs the queued Reactions and
   ;; `reagent.core/flush` renders the dirty components; draining only the
   ;; component queue leaves the page reading a stale snapshot.
   :drain!      (fn [] (react-dom/flushSync (fn [] (reagent-ratom/flush!) (r/flush))))
   ;; The stock counterpart of the slim `:drain-with!`. Stock Reagent's
   ;; component queue is rAF-scheduled, so it is still full a microtask
   ;; later and this arm passes under every order — which is precisely why
   ;; it passed 78 of 78 in HD-008 beside a slim arm that failed 78 of 78.
   :drain-with! (fn [f] (react-dom/flushSync (fn [] (f) (reagent-ratom/flush!) (r/flush))))
   :raw-element (fn [] [raw-list])
   :raw-write!  (fn [v] (reset! raw-cells (vec (repeat rf.bench.hicasso.z3vlz-probe/cells-n v))))})
