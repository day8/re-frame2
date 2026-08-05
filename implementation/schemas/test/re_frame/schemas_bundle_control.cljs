(ns re-frame.schemas-bundle-control
  "Malli-free CONTROL for the schemas bundle-cost gate (rf2-v4o7e) —
  the A arm of the A/B whose B arm is `re-frame.schemas-bundle-probe`.

  ## Why a control exists at all

  Spec 010 §Bundle cost budgets a MARGINAL quantity: what requiring
  `re-frame.schemas` adds on top of an app that already uses re-frame2.
  The gate used to assert an ABSOLUTE gzipped ceiling on the probe, so
  every unrelated framework change moved its number — between
  2026-05-14 and 2026-08-05 the probe grew 80.1 -> 124.9 KB, of which
  Malli's marginal cost was 1.0 KB and `cljs.core` + `re-frame.core`
  were 43.8 KB. The gate fired at the schemas artefact for growth that
  happened entirely outside it (rf2-kybsf).

  This namespace is the subtrahend that fixes that. It requires
  `re-frame.core` and NOTHING ELSE, so `probe - control` is exactly the
  schemas opt-in: `re-frame.schemas` plus the `re-frame.schemas.malli`
  adapter the facade pulls in with it. Both bundles carry the same
  `cljs.core` and the same `re-frame.core`, so both cancel.

  ## Why it requires ONLY the core facade

  The A/B has to price what a consumer can actually BUY. Per rf2-v96fh
  (schema implies validation) `re-frame.schemas` `:require`s the Malli
  adapter in its own ns-form, so no consumer can opt into schemas
  without Malli — the full schemas+adapter surface IS the opt-in.
  A counterfactual build with the facade's adapter require stripped
  measures a posture nobody can buy; it stays an attribution technique
  (used once, on rf2-kybsf, to split 'Malli grew' from 'core grew'),
  never the gate's control arm.

  ## Why `boot` deliberately touches nothing

  The control's rooting must be a SUBSET of the probe's, or the margin
  understates the schemas cost. The probe's `boot` touches only
  `re-frame.schemas` symbols — it roots no `re-frame.core` symbol
  beyond what the require itself pulls — so this one touches none
  either, and the `[re-frame.core :as rf]` require below is the whole
  point of the file rather than an oversight. Adding an `rf/…` call
  here would root core surface the probe does not, shrinking the
  measured margin and quietly loosening the gate. Keep it a no-op.

  shadow-cljs build target `:schemas-bundle-control` compiles this ns
  under `:advanced` + `goog.DEBUG=false` — identical settings to
  `:schemas-bundle-probe`, which is what makes the two figures
  subtractable. `scripts/check-schemas-bundle.cjs` reads both bundles'
  gzipped sizes and asserts the margin between them, two-sided."
  (:require [re-frame.core :as rf]))

(defn ^:export boot
  "Control init-fn. Rooting the module's entry point is its ONLY job:
  the bundle it produces is `cljs.core` + `re-frame.core` and nothing
  else, which is precisely the quantity the gate subtracts away.

  Named `boot` to match the probe's entry point."
  []
  nil)
