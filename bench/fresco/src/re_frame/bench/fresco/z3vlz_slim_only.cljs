(ns re-frame.bench.fresco.z3vlz-slim-only
  "RUNG 1 — reagent-slim ALONE. THE DECIDING EXPERIMENT.

  The question is whether a SINGLE-SUBSTRATE reagent-slim bundle —
  reagent-slim alone, no stock reagent compiled in — re-renders on a
  write. If it does, HD-008's finding is a mixed-bundle artefact (b) or a
  late-binding collision (c) and no user is affected. If it does not, it is
  an adapter defect (a): shipped, first-class, and a correctness bug.

  This entry's whole content is one `rf/init!` and one probe call. What
  makes it the experiment is what it does NOT require: no
  `re-frame.adapter.reagent`, no `reagent.core`, no `uix.core`, and
  nothing that reaches them transitively. `re-frame.bench.fresco.lane`
  (through the probe) requires only `react-dom` and the order guard; the
  slim substrate namespace requires only `reagent2.*`. The
  `:compiled-in` manifest below is checked against the BUILD'S OWN source
  map by the driver, so the claim is verified rather than asserted.

  Built and driven by `z3vlz_run.cjs` beside this file, on the shared
  `:fresco-bench` build id."
  (:require [re-frame.bench.fresco.z3vlz-probe :as rf.bench.fresco.z3vlz-probe]
            [re-frame.bench.fresco.z3vlz-slim-substrate :as rf.bench.fresco.z3vlz-slim-substrate]
            [re-frame.core :as rf]))

(defn ^:export -main []
  (rf/init! rf.bench.fresco.z3vlz-slim-substrate/adapter)
  (rf.bench.fresco.z3vlz-probe/run-probe! rf.bench.fresco.z3vlz-slim-substrate/substrate
              {:bundle      :slim-only
               :installed   :reagent-slim
               :compiled-in [:reagent2]}
              :z3vlz/slim-only))
