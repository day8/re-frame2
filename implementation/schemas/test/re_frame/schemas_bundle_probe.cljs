(ns re-frame.schemas-bundle-probe
  "SCHEMAS arm of the bundle-cost A/B (rf2-fqbcy, rf2-kybsf, rf2-v4o7e) —
  the B arm whose A arm is `re-frame.schemas-bundle-control`.

  ## What the pair measures

  Spec 010 §Bundle cost budgets a MARGINAL quantity: what requiring
  `re-frame.schemas` adds on top of an app that already uses re-frame2.
  The two builds differ by EXACTLY ONE require —

    schemas-bundle-control   `[re-frame.core]` only.
    schemas-bundle-probe     `[re-frame.core]` + `[re-frame.schemas]`.

  — so `probe - control` is the schemas opt-in and nothing else: both
  arms carry the same `cljs.core` and the same `re-frame.core`, and
  both cancel. `scripts/check-schemas-bundle.cjs` asserts that margin
  TWO-SIDED — at most 45 KB gzipped, at least 20 KB. It asserts no
  absolute size at all: a probe's absolute figure is mostly `cljs.core`
  and `re-frame.core`, which the perf-bundle and bundle-isolation gates
  own, and an absolute ceiling here duly spent 83 days measuring their
  growth rather than the schemas artefact's (rf2-kybsf). The checker's
  header carries the matched run both bounds were set from.

  ## Why requiring the facade alone still prices Malli

  Per rf2-v96fh (schema implies validation) the `re-frame.schemas`
  facade `:require`s `re-frame.schemas.malli` in its own ns-form, so
  this probe — which requires ONLY the facade, NOT the adapter —
  nonetheless pulls Malli's validation surface into the bundle. That
  is the deliberate Ruling-A tradeoff: requiring the schemas artefact
  implies Malli is wired, so a registered schema always validates
  rather than soft-passing into a silent no-op. There is no 'schemas
  required, no Malli' posture to price (the only Malli-free posture is
  not requiring the schemas artefact at all — the no-feature counter
  app, pinned by the counter bundle-isolation gate). That is also why
  the control is core-only: this probe's posture is the only schemas
  posture a consumer can buy.

  The gate's FLOOR is where that invariant shows up in bundle shape.
  Delete the adapter require from the facade's ns-form and Malli leaves
  this bundle: the margin falls to a measured 9.7 KB and the checker
  exits 1. The invariant's PRIMARY owner is the behavioural test
  `schemas/test/re_frame/schemas_implies_validation_test.clj`; the floor
  is the bundle-shaped corroboration, and it is what replaced the old
  byte-equality guard — that guard compared this probe against a
  near-identical `-malli` sibling, and both retired together, the
  sibling being a redundant require rather than a distinct posture.

  ## Why `boot` touches only schemas symbols

  The probe namespace does NO runtime work — it exists only to root
  the schemas-artefact's dead-code-elimination graph so Closure
  retains the namespace's body. Each public symbol is touched once
  to anchor it; the exported boot fn is the bundle's entry point. It
  roots no `re-frame.core` symbol, and the control roots none either:
  core surface rooted by one arm and not the other lands in the margin
  and misprices the opt-in in whichever direction the asymmetry runs.

  shadow-cljs build target `:schemas-bundle-probe` compiles this ns
  under `:advanced` + `goog.DEBUG=false` — identical settings to
  `:schemas-bundle-control`, which is what makes the two figures
  subtractable."
  (:require [re-frame.core    :as rf]
            [re-frame.schemas :as schemas]))

(defn ^:export boot
  "Probe init-fn. Touches the schemas surface so DCE retains every
  published symbol; the probe's MARGIN over `schemas-bundle-control`
  then reflects the cost every consumer that requires
  `re-frame.schemas` would pay.

  Touches nothing from `re-frame.core` — that require is the arm the
  two builds share, and rooting core surface here would inflate the
  margin with a cost the control never subtracts away.

  Named `boot` (not `run!`) to avoid shadowing `cljs.core/run!`."
  []
  (schemas/set-schema-validator! nil)
  (schemas/reg-app-schema [:probe] [:int])
  (schemas/app-schemas)
  (schemas/app-schemas-digest))
