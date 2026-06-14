(ns re-frame.schemas-bundle-probe
  "Bundle-cost probe (rf2-fqbcy) — measures the gzipped cost of
  requiring `re-frame.schemas`, per Spec 010 §Bundle cost.

  Per rf2-v96fh (schema implies validation) the `re-frame.schemas`
  facade now `:require`s `re-frame.schemas.malli` itself, so this
  probe — which requires ONLY `re-frame.schemas`, NOT the adapter —
  nonetheless pulls Malli's validation surface into the bundle. That
  is the deliberate Ruling-A tradeoff: requiring the schemas artefact
  implies Malli is wired, so a registered schema always validates
  rather than soft-passing into a silent no-op. There is no longer a
  'schemas required, no Malli' bundle posture (the only Malli-free
  posture is not requiring the schemas artefact at all — the no-feature
  counter app, pinned by the counter bundle-isolation gate).

  The companion probe `re-frame.schemas-bundle-probe-malli` requires
  the adapter EXPLICITLY on top; under Ruling A the explicit require
  is a no-op (the facade already loaded it), so the two bundles are now
  the same size — `scripts/check-schemas-bundle.cjs` asserts that
  equality as the schema-implies-validation regression guard (a revert
  of the facade require would drop THIS probe back to the ~59 KB
  Malli-free figure and break the equality).

  shadow-cljs build target `:schemas-bundle-probe` compiles this ns
  under `:advanced` + `goog.DEBUG=false`. scripts/check-schemas-bundle.cjs
  reads the bundle's gzipped size and asserts it sits within the
  spec envelope.

  The probe namespace does NO runtime work — it exists only to root
  the schemas-artefact's dead-code-elimination graph so Closure
  retains the namespace's body. Each public symbol is touched once
  to anchor it; the exported boot fn is the bundle's entry point."
  (:require [re-frame.core    :as rf]
            [re-frame.schemas :as schemas]))

(defn ^:export boot
  "Bundle-probe init-fn. Touches the schemas surface so DCE retains
  every published symbol; the probe's bundle then reflects the cost
  every consumer that requires `re-frame.schemas` would pay.

  Named `boot` (not `run!`) to avoid shadowing `cljs.core/run!`."
  []
  (schemas/set-schema-validator! nil)
  (schemas/reg-app-schema [:probe] [:int])
  (schemas/app-schemas)
  (schemas/app-schemas-digest))
