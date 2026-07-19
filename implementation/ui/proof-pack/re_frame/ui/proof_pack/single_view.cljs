(ns re-frame.ui.proof-pack.single-view
  "G-18 subject: a consumer that imports EXACTLY ONE view (`controlled-input`)
  from the multi-view proof-pack library. Under an advanced build the five
  unimported siblings' render sentinels must be absent (fixture-first) — that
  sibling render-sentinel isolation is the whole of G-18's claim. The
  production absence of the library's schemas, docs projections, and dev
  registration is a separate roster owned by G-11, not this consumer."
  (:require [re-frame.ui.proof-pack.library :as lib]))

(defn -main []
  ;; Root ONLY controlled-input in the reachability graph.
  (unchecked-set js/globalThis "__RF2_PP_SINGLE__" lib/controlled-input))
