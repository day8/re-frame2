(ns re-frame.schemas-implies-validation-test
  "Regression tests for rf2-v96fh — \"schema implies validation\"
  (Mike-ruled A).

  ## The footgun this closes

  Pre-rf2-v96fh, requiring `re-frame.schemas` did NOT wire the default
  Malli validator. The CLJS reference's default validator delegates to
  Malli via the late-bind hook `:schemas/malli-validate`, published by
  the SEPARATE `re-frame.schemas.malli` adapter ns. An app that loaded
  `re-frame.schemas` but FORGOT to also require `[re-frame.schemas.malli]`
  registered schemas that then SILENTLY VALIDATED NOTHING — the default
  validator soft-passed every value (Spec 010 §Recommended soft-pass).
  \"I registered a schema\" did NOT imply \"it validates.\"

  ## The fix (Ruling A)

  The `re-frame.schemas` facade now `:require`s `re-frame.schemas.malli`
  itself, so loading the schemas artefact wires Malli automatically.
  Registering a schema therefore ALWAYS validates — the inert
  \"registered but soft-passing\" state is gone.

  ## What these tests assert (and how they would have FAILED before)

  This namespace requires ONLY `re-frame.schemas` — deliberately NOT
  `re-frame.schemas.malli`. That mirrors the footgun app: load the
  schemas artefact, register a schema, never require the adapter.

    1. `malli-hook-is-wired-by-requiring-the-facade` — the
       `:schemas/malli-validate` hook is bound after requiring only the
       facade. Pre-fix this hook was unbound (the adapter ns was never
       loaded), so this assertion would FAIL.
    2. `bad-write-to-registered-slot-validates` — a malformed app-db
       commit to a slot with a registered schema fires
       `:rf.error/schema-validation-failure`. Pre-fix the default
       validator soft-passed and NO trace fired — this would FAIL.
    3. `valid-write-passes` — a conforming commit fires no failure
       trace (the validation is real, not a blanket reject).

  Per the public-surface contract these are behavioural locks on the
  artefact's load-time wiring, not on any private internals."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            ;; DELIBERATELY require ONLY the facade — NOT
            ;; `re-frame.schemas.malli`. The whole point of rf2-v96fh is
            ;; that requiring the facade is sufficient to wire Malli.
            [re-frame.schemas]
            [re-frame.schemas.test-fixture :as tf]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each tf/reset-runtime)

(defn- failures-of
  [recorded]
  (filterv #(= :rf.error/schema-validation-failure (:operation %))
           @recorded))

;; ---- the wiring is live by requiring the facade alone ---------------------

(deftest malli-hook-is-wired-by-requiring-the-facade
  (testing "Per rf2-v96fh: requiring `re-frame.schemas` (without
            `re-frame.schemas.malli`) wires the default Malli validator —
            the `:schemas/malli-validate` late-bind hook is bound. Pre-fix
            the adapter ns was never loaded by the facade and this hook
            was nil, so the default validator soft-passed."
    (is (some? (late-bind/get-fn :schemas/malli-validate))
        ":schemas/malli-validate is bound — the facade pulled the adapter")
    (is (some? (late-bind/get-fn :schemas/malli-explain))
        ":schemas/malli-explain is bound too")))

;; ---- registering a schema implies validation ------------------------------

(deftest bad-write-to-registered-slot-validates
  (testing "Per rf2-v96fh: a malformed commit to a slot with a registered
            schema fires :rf.error/schema-validation-failure — without any
            explicit `re-frame.schemas.malli` require. Pre-fix this was a
            silent no-op (the default validator soft-passed)."
    (rf/reg-app-schema [:count] [:int])
    (with-trace-recorder! [recorded]
      (with-redefs [interop/debug-enabled? true]
        (rf/reg-event :count/break (fn [{:keys [db]} _] {:db (assoc db :count "not-an-int")}))
        ;; validate-app-schema! runs the registered app-db schema set over
        ;; the supplied db value, exactly as the post-handler step does.
        (re-frame.schemas/validate-app-schema! {:count "not-an-int"} :count/break))
      (let [violations (failures-of recorded)]
        (is (= 1 (count violations))
            "exactly one schema-validation-failure fired — schema implies validation")
        (let [v (first violations)]
          (is (= :app-db (-> v :tags :where)))
          (is (= [:count] (-> v :tags :path)))
          (is (= "not-an-int" (-> v :tags :value))))))))

(deftest valid-write-passes
  (testing "Per rf2-v96fh: a conforming commit fires no failure trace —
            the validation is real (it accepts good values), not a blanket
            reject."
    (rf/reg-app-schema [:count] [:int])
    (with-trace-recorder! [recorded]
      (with-redefs [interop/debug-enabled? true]
        (re-frame.schemas/validate-app-schema! {:count 7} :count/ok))
      (is (empty? (failures-of recorded))
          "no failure trace — the well-typed value conforms"))))
