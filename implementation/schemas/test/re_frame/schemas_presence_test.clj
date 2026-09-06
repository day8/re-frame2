(ns re-frame.schemas-presence-test
  "rf2-6eh5h — declaration presence is KEY-presence, not value truthiness.

  The invariant under test (Spec 010 §The `:schema` value is opaque): an
  ABSENT `:schema` key means \"no declaration\", while a PRESENT key must
  hand its exact value — nil and false included — to the registered
  validator. Before this bead the meta-bearing hot path (`run-validation`)
  used `if-let`, so explicit nil / false tokens silently bypassed the
  validator; and the PRODUCTION boundary interceptor treated a nil schema
  as impossible and returned the context unchanged — a release-resident
  fail-open in which `{:schema nil :interceptors [:rf.schema/at-boundary]}`
  registered successfully (the registrar checks `contains?`) and the
  handler then ran UNGUARDED on exactly the untrusted payloads the
  interceptor exists to gate.

  What is pinned here:

   1. **Exact-token delegation** (AC 1) — a spy validator proves nil and
      false reach `validate-event!` / `validate-fx!` / `validate-sub!`
      verbatim, once per consult, and the false verdict returns per the
      `run-validation` contract.
   2. **The production boundary fail-open is closed** (AC 2, JVM half) —
      with `re-frame.spec/dev-mode?` rebound false (the documented JVM
      route to the interceptor's production branch), a registered
      `{:schema nil}` boundary handler's `:before` delegates nil to the
      validator and sets `:rf/skip-handler?`. The CLJS half rides the
      production-compiled `re-frame.schemas-boundary-prod-test` suite.
   3. **Default Malli fails CLOSED** (AC 5) — a present nil / false
      schema takes the malformed-schema route (`:rf.error/malformed-schema`
      + false), never registration-success-plus-runtime-no-op.
   4. **The controls** (AC 4) — an omitted `:schema` key never consults
      the validator, and `set-schema-validator!` nil still disables every
      surface. These make the spy proof non-vacuous: restoring an
      `if-let` / nil guard turns the present-falsey cases red while the
      controls stay green."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.spec :as rf.spec]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- spy-validator!
  "Install a validator that records every schema token it is handed and
  returns false. Returns the recording atom."
  []
  (let [seen (atom [])]
    (rf.schemas/set-schema-validator!
      (fn [schema _value] (swap! seen conj schema) false))
    seen))

(defn- ops [traces op]
  (filterv #(= op (:operation %)) traces))

;; ===========================================================================
;; AC 1 — the exact declared token reaches the validator on event / fx / sub
;; ===========================================================================

(deftest present-falsey-tokens-are-delegated-verbatim-on-event-fx-sub
  (doseq [token [nil false]]
    (testing (str "a present " (pr-str token) " :schema token is handed "
                  "verbatim to the validator, once per consult, and the "
                  "false verdict returns")
      (let [seen (atom [])]
        (rf.schemas/set-schema-validator!
          (fn [schema _value] (swap! seen conj schema) false))
        (is (false? (rf.schemas/validate-event! :ev/x [:ev/x 1] {:schema token}))
            "event surface returns false — the caller skips the handler")
        (is (false? (rf.schemas/validate-fx! :fx/x :ev/x {:a 1} {:schema token}))
            "fx surface returns false — the caller skips the fx")
        (is (false? (rf.schemas/validate-sub! :sub/x [:sub/x] 42 {:schema token}))
            "sub surface returns false — the caller replaces with default")
        (is (= [token token token] @seen)
            "the EXACT declared token was delegated, exactly once per surface")))))

(deftest explicit-nil-schema-skips-the-handler-through-the-real-dispatch-path
  (testing "rf2-6eh5h — a reg-event handler declaring {:schema nil} is
            validated (spy validator sees nil, returns false) and skipped
            on dispatch; the failure emits :where :event"
    (let [seen  (spy-validator!)
          calls (atom 0)]
      (rf/reg-event :ev/nil-schema
        {:schema nil}
        (fn [{:keys [db]} _] (swap! calls inc) {:db db}))
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:ev/nil-schema {:whatever 1}])
        (is (= 0 @calls) "handler skipped — nil was validated, not ignored")
        (is (= [nil] @seen) "the exact nil token reached the validator once")
        (is (= 1 (count (ops @traces :rf.error/schema-validation-failure)))
            "exactly one :rf.error/schema-validation-failure fired")
        (is (= :event (-> (ops @traces :rf.error/schema-validation-failure)
                          first :tags :where)))))))

;; ===========================================================================
;; AC 2 (JVM half) — the production boundary interceptor delegates nil
;; ===========================================================================

(deftest boundary-registration-with-explicit-nil-schema-succeeds
  (testing "the fail-open's precondition, pinned: {:schema nil} + the
            boundary interceptor REGISTERS (the registrar checks key
            presence), and the registered metadata preserves the nil"
    (rf/reg-event :wire/received
      {:schema       nil
       :interceptors [:rf.schema/at-boundary]}
      (fn [_ _] {}))
    (let [meta (rf.registrar/lookup :event :wire/received)]
      (is (some? meta) "registration succeeded")
      (is (contains? meta :schema) "the :schema key is present")
      (is (nil? (:schema meta)) "…and its value is the authored nil"))))

(deftest boundary-interceptor-delegates-a-present-nil-schema-in-production
  (testing "rf2-6eh5h HEADLINE — the production branch of
            :rf.schema/at-boundary hands a present nil schema to the
            registered validator and rejects on its false verdict; the
            handler is NOT invoked. Before the fix the interceptor's
            (nil? schema) arm returned the context unchanged and the
            handler ran unguarded."
    (let [seen (spy-validator!)]
      (rf/reg-event :wire/received
        {:schema       nil
         :interceptors [:rf.schema/at-boundary]}
        (fn [_ _] {}))
      (with-redefs [rf.spec/dev-mode? (constantly false)]
        (let [before (:before rf/validate-at-boundary-interceptor)
              ctx    (before {:coeffects {:event [:wire/received {:untrusted 1}]}})]
          (is (= [nil] @seen)
              "the boundary delegated the EXACT nil token to the validator")
          (is (true? (:rf/skip-handler? ctx))
              "the invalid event is rejected — the handler will not run"))))))

(deftest boundary-interceptor-fails-closed-on-nil-schema-under-default-malli
  (testing "AC 5 — with the DEFAULT Malli validator a present nil schema
            cannot silently run a boundary handler: Malli throws on the
            non-schema form, the seam isolates the throw to false, and the
            boundary rejects (the malformed-schema fail-closed route)"
    ;; Fixture reset restored the default Malli validator.
    (rf/reg-event :wire/received
      {:schema       nil
       :interceptors [:rf.schema/at-boundary]}
      (fn [_ _] {}))
    (with-redefs [rf.spec/dev-mode? (constantly false)]
      (let [before (:before rf/validate-at-boundary-interceptor)
            ctx    (before {:coeffects {:event [:wire/received {:untrusted 1}]}})]
        (is (true? (:rf/skip-handler? ctx))
            "rejected — never registration success plus runtime no-op")))))

(deftest boundary-nil-validator-still-disables-validation-in-production
  (testing "AC 4 control — set-schema-validator! nil remains the documented
            global opt-out: even a present nil schema passes the boundary
            unchecked when validation is disabled"
    (rf.schemas/set-schema-validator! nil)
    (rf/reg-event :wire/received
      {:schema       nil
       :interceptors [:rf.schema/at-boundary]}
      (fn [_ _] {}))
    (with-redefs [rf.spec/dev-mode? (constantly false)]
      (let [before (:before rf/validate-at-boundary-interceptor)
            ctx    (before {:coeffects {:event [:wire/received {:untrusted 1}]}})]
        (is (not (:rf/skip-handler? ctx))
            "no validator registered → no validation → handler runs")))))

;; ===========================================================================
;; AC 5 — default Malli takes the malformed-schema fail-closed route
;; ===========================================================================

(deftest default-malli-fails-closed-on-present-falsey-schemas
  (doseq [token [nil false]]
    (testing (str "with default Malli a present " (pr-str token)
                  " schema fails CLOSED via :rf.error/malformed-schema")
      (with-trace-recorder! [traces]
        (is (false? (rf.schemas/validate-event! :ev/x [:ev/x 1] {:schema token}))
            "false — the caller runs its normal recovery (skip)")
        (let [mal (ops @traces :rf.error/malformed-schema)]
          (is (= 1 (count mal))
              "exactly one malformed-schema trace fired — the fail-closed route")
          (is (= :event (-> mal first :tags :where))))))))

;; ===========================================================================
;; AC 4 — the controls that make the spy proofs non-vacuous
;; ===========================================================================

(deftest omitted-schema-key-never-consults-the-validator
  (testing "an ABSENT :schema key remains a no-op on every meta surface —
            the validator is never invoked and every surface passes"
    (let [seen (spy-validator!)]
      (is (true? (rf.schemas/validate-event! :ev/x [:ev/x 1] {:doc "no schema"})))
      (is (true? (rf.schemas/validate-event! :ev/x [:ev/x 1] nil))
          "nil registration metadata is 'no declaration' too")
      (is (true? (rf.schemas/validate-fx! :fx/x :ev/x {:a 1} {})))
      (is (true? (rf.schemas/validate-sub! :sub/x [:sub/x] 42 {})))
      (is (= [] @seen) "the validator was never consulted"))))

(deftest nil-registered-validator-disables-validation-for-present-falsey-tokens
  (testing "set-schema-validator! nil disables validation even for a
            present nil / false declaration — the documented global opt-out"
    (rf.schemas/set-schema-validator! nil)
    (is (true? (rf.schemas/validate-event! :ev/x [:ev/x 1] {:schema nil})))
    (is (true? (rf.schemas/validate-fx! :fx/x :ev/x {:a 1} {:schema false})))
    (is (true? (rf.schemas/validate-sub! :sub/x [:sub/x] 42 {:schema nil})))))
