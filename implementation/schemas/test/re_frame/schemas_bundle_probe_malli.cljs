(ns re-frame.schemas-bundle-probe-malli
  "Bundle-cost probe with an EXPLICIT Malli adapter require (rf2-fqbcy)
  — companion to `re-frame.schemas-bundle-probe`.

  Per rf2-v96fh (schema implies validation) the `re-frame.schemas`
  facade requires `re-frame.schemas.malli` itself, so the explicit
  `[re-frame.schemas.malli]` require below is now redundant — the
  adapter is already on the classpath by the time this ns's other
  requires load. The two probe bundles are therefore the SAME size.

  scripts/check-schemas-bundle.cjs asserts that equality (within a
  small tolerance) as the schema-implies-validation regression guard:
  if a future change reverts the facade's adapter require, the sibling
  `schemas-bundle-probe` (which does NOT require the adapter
  explicitly) would drop back to its ~59 KB Malli-free figure while
  THIS probe stayed at ~91 KB — the equality assertion would then
  fail, catching the regression. Pre-rf2-v96fh this probe was the
  Malli-ON arm of a 'Malli strictly larger' delta guard; Ruling A made
  Malli mandatory for any schemas consumer, so the delta collapsed to
  zero by design and the guard inverted into the equality check.

  shadow-cljs build target `:schemas-bundle-probe-malli` compiles
  this ns under `:advanced` + `goog.DEBUG=false`."
  (:require [re-frame.core    :as rf]
            [re-frame.schemas :as schemas]
            [re-frame.schemas.malli]))

(defn ^:export boot
  "Bundle-probe init-fn — see the no-Malli sibling for the rationale."
  []
  (schemas/set-schema-validator! nil)
  (schemas/reg-app-schema [:probe] [:int])
  (schemas/app-schemas)
  (schemas/app-schemas-digest))
