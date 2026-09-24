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

  ## What these tests assert (and how they would FAIL without the wiring)

  This namespace requires ONLY `re-frame.schemas` — deliberately NOT
  `re-frame.schemas.malli`. That mirrors the footgun app: load the
  schemas artefact, register a schema, never require the adapter.

    1. `malli-hook-is-wired-by-requiring-the-facade` — the
       `:schemas/malli-validate` hook is bound after requiring only the
       facade. Without the wiring this hook would be unbound (the adapter
       ns never loaded), so this assertion would FAIL.
    2. `bad-write-to-registered-slot-validates` — a malformed app-db
       commit to a slot with a registered schema fires
       `:rf.error/schema-validation-failure`. Without the wiring the
       default validator would soft-pass and NO trace would fire — this
       would FAIL.
    3. `valid-write-passes` — a conforming commit fires no failure
       trace (the validation is real, not a blanket reject).

  Per the public-surface contract these are behavioural locks on the
  artefact's load-time wiring, not on any private internals."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            ;; DELIBERATELY require ONLY the facade — NOT
            ;; `re-frame.schemas.malli`. The whole point is that
            ;; requiring the facade is sufficient to wire Malli.
            [re-frame.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- failures-of
  [recorded]
  (filterv #(= :rf.error/schema-validation-failure (:operation %))
           @recorded))

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

;; ---- registering a schema implies validation ------------------------------

(deftest bad-write-to-registered-slot-validates
  (testing "a malformed commit to a slot with a registered
            schema fires :rf.error/schema-validation-failure — without any
            explicit `re-frame.schemas.malli` require."
    (rf/reg-app-schema [:count] [:int])
    (with-trace-recorder! [recorded]
      (with-redefs [rf.interop/debug-enabled? true]
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
  (testing "a conforming commit fires no failure trace —
            the validation is real (it accepts good values), not a blanket
            reject."
    (rf/reg-app-schema [:count] [:int])
    (with-trace-recorder! [recorded]
      (with-redefs [rf.interop/debug-enabled? true]
        (re-frame.schemas/validate-app-schema! {:count 7} :count/ok))
      (is (empty? (failures-of recorded))
          "no failure trace — the well-typed value conforms"))))
