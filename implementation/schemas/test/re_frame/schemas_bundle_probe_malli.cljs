(ns re-frame.schemas-bundle-probe-malli
  "Bundle-cost probe with Malli adapter (rf2-fqbcy) — companion to
  `re-frame.schemas-bundle-probe`. Per Spec 010 §Bundle cost row 3
  (`[re-frame.schemas]` + `[malli.core]` required, no validation) —
  measures the ~24 KB cost of pulling Malli into a bundle that
  already requires the schemas surface.

  shadow-cljs build target `:schemas-bundle-probe-malli` compiles
  this ns under `:advanced` + `goog.DEBUG=false`. The accompanying
  CI gate (scripts/check-schemas-bundle.cjs) asserts the bundle's
  gzipped size sits within the spec envelope (≤ 125 KB per the
  spec's 120.8 KB row + margin)."
  (:require [re-frame.core    :as rf]
            [re-frame.schemas :as schemas]
            [re-frame.schemas.malli]))

(defn ^:export boot
  "Bundle-probe init-fn — see the no-Malli sibling for the rationale."
  []
  (rf/set-schema-validator! nil)
  (schemas/reg-app-schema [:probe] [:int])
  (schemas/app-schemas)
  (schemas/app-schemas-digest))
