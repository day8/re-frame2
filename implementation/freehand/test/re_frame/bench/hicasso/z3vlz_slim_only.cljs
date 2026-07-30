(ns re-frame.bench.hicasso.z3vlz-slim-only
  "RUNG 1 — reagent-slim ALONE. THE EXPERIMENT THAT SETTLES rf2-z3vlz.

  The bead's question is whether a SINGLE-SUBSTRATE reagent-slim bundle —
  reagent-slim alone, no stock reagent compiled in — re-renders on a
  write. If it does, HD-008's finding is a mixed-bundle artefact (b) or a
  late-binding collision (c) and no user is affected. If it does not, it is
  an adapter defect (a): shipped, first-class, and a correctness bug.

  This entry's whole content is one `rf/init!` and one probe call. What
  makes it the experiment is what it does NOT require: no
  `re-frame.adapter.reagent`, no `reagent.core`, no `uix.core`, and
  nothing that reaches them transitively. `re-frame.bench.hicasso.lane`
  (through the probe) requires only `react-dom` and the order guard; the
  slim substrate namespace requires only `reagent2.*`. The
  `:compiled-in` manifest below is checked against the BUILD'S OWN source
  map by the driver, so the claim is verified rather than asserted.

  Built and driven by
  `implementation/freehand/test/re_frame/bench/hicasso/z3vlz_run.cjs` on
  rf2-2rtt6.2's `:hicasso-bench` build id — no new build id, so
  `implementation/shadow-cljs.edn` is untouched."
  (:require [re-frame.bench.hicasso.z3vlz-probe :as probe]
            [re-frame.bench.hicasso.z3vlz-slim-substrate :as slim]
            [re-frame.core :as rf]))

(defn ^:export -main []
  (rf/init! slim/adapter)
  (probe/run! slim/substrate
              {:bundle      :slim-only
               :installed   :reagent-slim
               :compiled-in [:reagent2]}
              :z3vlz/slim-only))
