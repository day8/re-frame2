(ns re-frame.ui.proof-pack.single-view
  "G-18 subject: a consumer that imports EXACTLY ONE view (`controlled-input`)
  from the multi-view proof-pack library. Under an advanced build the five
  unimported siblings — and their sentinels, schemas, docs, and dev
  registration — must be absent (fixture-first)."
  (:require [re-frame.ui.proof-pack.library :as lib]))

(defn -main []
  ;; Root ONLY controlled-input in the reachability graph.
  (unchecked-set js/globalThis "__RF2_PP_SINGLE__" lib/controlled-input))
