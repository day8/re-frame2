(ns re-frame.bench.hicasso.z3vlz-slim-uix
  "RUNG 3 — reagent-slim WITH the UIx adapter compiled alongside it, and
  stock Reagent ABSENT.

  The other half of the bisection. HD-008's bundle carries three adapters,
  so `mixed` on its own would not say WHICH coexistence matters. Rung 2
  adds stock Reagent alone; this adds UIx alone. Between them the collider
  is named rather than guessed.

  The UIx adapter is REFERENCED, not merely required — `adapter-ref` is
  exported onto `window` — so `:advanced` cannot prove the namespace dead
  and elide the load-time hook publication that candidate (c) is about."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.z3vlz-probe :as rf.bench.hicasso.z3vlz-probe]
            [re-frame.bench.hicasso.z3vlz-slim-substrate :as rf.bench.hicasso.z3vlz-slim-substrate]
            [re-frame.core :as rf]))

(def ^:export adapter-ref rf.adapter.uix/adapter)

(defn ^:export -main []
  (set! (.-Z3VLZ_UIX_ADAPTER js/window) (pr-str (:kind adapter-ref)))
  (rf/init! rf.bench.hicasso.z3vlz-slim-substrate/adapter)
  (rf.bench.hicasso.z3vlz-probe/run-probe! rf.bench.hicasso.z3vlz-slim-substrate/substrate
              {:bundle      :slim+uix
               :installed   :reagent-slim
               :compiled-in [:reagent2 :uix]}
              :z3vlz/slim-uix))
