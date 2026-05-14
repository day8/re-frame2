(ns re-frame.schemas-bundle-probe
  "Bundle-cost probe (rf2-fqbcy) — measures the gzipped impact of
  requiring `re-frame.schemas` WITHOUT the Malli adapter, per Spec
  010 §Bundle cost row 2 (`[re-frame.schemas]` required, no Malli).

  The companion probe `re-frame.schemas-bundle-probe-malli` requires
  the Malli adapter on top — the difference between the two bundles
  is the Malli surface cost.

  shadow-cljs build target `:schemas-bundle-probe` compiles this ns
  under `:advanced` + `goog.DEBUG=false`. scripts/check-schemas-bundle.cjs
  reads the bundle's gzipped size and asserts it sits within the
  spec envelope (≤ 100 KB per the spec's 97.2 KB row + margin).

  The probe namespace does NO runtime work — it exists only to root
  the schemas-artefact's dead-code-elimination graph so Closure
  retains the namespace's body. Each public symbol is touched once
  to anchor it; the exported run! fn is the bundle's entry point."
  (:require [re-frame.core    :as rf]
            [re-frame.schemas :as schemas]))

(defn ^:export boot
  "Bundle-probe init-fn. Touches the schemas surface so DCE retains
  every published symbol; the probe's bundle then reflects the cost
  every consumer that requires `re-frame.schemas` would pay.

  Named `boot` (not `run!`) to avoid shadowing `cljs.core/run!`."
  []
  (rf/set-schema-validator! nil)
  (schemas/reg-app-schema [:probe] [:int])
  (schemas/app-schemas)
  (schemas/app-schemas-digest))
