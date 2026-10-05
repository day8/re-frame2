(ns re-frame.schemas-implies-validation-test
  "Tests for \"schema implies validation\".

  ## The footgun this closes

  The CLJS reference's default validator delegates to Malli via the
  late-bind hook `:schemas/malli-validate`, published by the SEPARATE
  `re-frame.schemas.malli` adapter ns. If requiring `re-frame.schemas`
  did not load that adapter, an app that loaded `re-frame.schemas` but
  FORGOT to also require `[re-frame.schemas.malli]` would register schemas
  that SILENTLY VALIDATE NOTHING — the default validator soft-passes every
  value (Spec 010 §Recommended soft-pass). \"I registered a schema\" would
  NOT imply \"it validates.\"

  ## The contract

  The `re-frame.schemas` facade `:require`s `re-frame.schemas.malli`
  itself, so loading the schemas artefact wires Malli automatically.
  Registering a schema therefore ALWAYS validates — there is no inert
  \"registered but soft-passing\" state.

  ## What this asserts (and how it would FAIL without the wiring)

  This namespace requires ONLY `re-frame.schemas` — deliberately NOT
  `re-frame.schemas.malli`. That mirrors the footgun app: load the
  schemas artefact, register a schema, never require the adapter.

  `malli-hook-is-wired-by-requiring-the-facade` asserts the
  `:schemas/malli-validate` hook is bound after requiring only the facade.
  Without the wiring this hook would be unbound (the adapter ns never
  loaded), so the assertion would FAIL. The behavioural consequence — the
  default validator rejects a malformed value for a registered slot — is
  `re-frame.schemas-test/validate-app-schema-returns-boolean`, which goes
  red with this test when the facade stops loading the adapter.

  Per the public-surface contract this is a lock on the artefact's
  load-time wiring, not on any private internals."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.late-bind :as rf.late-bind]
            ;; DELIBERATELY require ONLY the facade — NOT
            ;; `re-frame.schemas.malli`. The whole point is that
            ;; requiring the facade is sufficient to wire Malli.
            [re-frame.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

;; ---- the wiring is live by requiring the facade alone ---------------------

(deftest malli-hook-is-wired-by-requiring-the-facade
  (testing "requiring `re-frame.schemas` (without
            `re-frame.schemas.malli`) wires the default Malli validator —
            the `:schemas/malli-validate` late-bind hook is bound, so the
            default validator does not soft-pass."
    (is (some? (rf.late-bind/get-fn :schemas/malli-validate))
        ":schemas/malli-validate is bound — the facade pulled the adapter")
    (is (some? (rf.late-bind/get-fn :schemas/malli-explain))
        ":schemas/malli-explain is bound too")))
