(ns re-frame.schemas-presence-test
  "Declaration presence is KEY-presence, not value truthiness.

  The invariant under test (Spec 010 §The `:schema` value is opaque): an
  ABSENT `:schema` key means \"no declaration\", while a PRESENT key must
  hand its exact value — nil and false included — to the registered
  validator. An `if-let` in the meta-bearing hot path (`run-validation`)
  would let explicit nil / false tokens silently bypass the validator; and
  a PRODUCTION boundary interceptor that treated a nil schema as
  impossible and returned the context unchanged would be a
  release-resident fail-open: `{:schema nil :boundary? true}` registers
  successfully (the registrar checks `contains?`), so the handler would
  run UNGUARDED on exactly the untrusted payloads the interceptor exists
  to gate.

  What is pinned here:

   1. **Exact-token delegation** — a spy validator proves nil and
      false reach `validate-event!` / `validate-fx!` / `validate-sub!`
      verbatim, once per consult, and the false verdict returns per the
      `run-validation` contract.
   2. **The production boundary does not fail open** (JVM half) —
      with `re-frame.spec/dev-mode?` rebound false (the documented JVM
      route to the interceptor's production branch), a registered
      `{:schema nil}` boundary handler's `:before` delegates nil to the
      validator and sets `:rf/skip-handler?`. The CLJS half rides the
      production-compiled `re-frame.schemas-boundary-prod-test` suite.
   3. **Default Malli fails CLOSED** — a present nil / false
      schema takes the malformed-schema route (`:rf.error/malformed-schema`
      + false), never registration-success-plus-runtime-no-op.
   4. **The controls** — an omitted `:schema` key never consults
      the validator, and `set-schema-fns!` with a nil `:validate` still disables every
      surface. These make the spy proof non-vacuous: an
      `if-let` / nil guard would turn the present-falsey cases red while the
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
    (rf.schemas/set-schema-fns!
      {:validate (fn [schema _value] (swap! seen conj schema) false)})
    seen))

(defn- ops [traces op]
  (filterv #(= op (:operation %)) traces))

;; ===========================================================================
;; The exact declared token reaches the validator on event / fx / sub
;; ===========================================================================

(deftest present-falsey-tokens-are-delegated-verbatim-on-event-fx-sub
  (doseq [token [nil false]]
    (testing (str "a present " (pr-str token) " :schema token is handed "
                  "verbatim to the validator, once per consult, and the "
                  "false verdict returns")
      (let [seen (atom [])]
        (rf.schemas/set-schema-fns!
          {:validate (fn [schema _value] (swap! seen conj schema) false)})
        (is (false? (rf.schemas/validate-event! :ev/x [:ev/x 1] {:schema token}))
            "event surface returns false — the caller skips the handler")
        (is (false? (rf.schemas/validate-fx! :fx/x :ev/x {:a 1} {:schema token}))
            "fx surface returns false — the caller skips the fx")
        (is (false? (rf.schemas/validate-sub! :sub/x [:sub/x] 42 {:schema token}))
            "sub surface returns false — the caller replaces with default")
        (is (= [token token token] @seen)
            "the EXACT declared token was delegated, exactly once per surface")))))

(deftest explicit-nil-schema-skips-the-handler-through-the-real-dispatch-path
  (testing "a reg-event handler declaring {:schema nil} is
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
;; JVM half — the production boundary interceptor delegates nil
;; ===========================================================================

(deftest boundary-registration-with-explicit-nil-schema-succeeds
  (testing "the fail-open's precondition, pinned: {:schema nil} + the
            boundary flag REGISTERS (the registrar checks key
            presence), and the registered metadata preserves the nil"
    (rf/reg-event :wire/received
      {:schema    nil
       :boundary? true}
      (fn [_ _] {}))
    (let [meta (rf.registrar/lookup :event :wire/received)]
      (is (some? meta) "registration succeeded")
      (is (contains? meta :schema) "the :schema key is present")
      (is (nil? (:schema meta)) "…and its value is the authored nil"))))

(deftest boundary-arm-delegates-a-present-nil-schema-in-production
  (testing "HEADLINE — the production boundary arm hands a present
            nil schema to the registered validator and rejects on its false
            verdict; the handler is NOT invoked (a (nil? schema) arm
            returning the context unchanged would let the handler run
            unguarded)."
    (let [seen (spy-validator!)]
      (rf/reg-event :wire/received
        {:schema    nil
         :boundary? true}
        (fn [_ _] {}))
      (with-redefs [rf.spec/dev-mode? (constantly false)]
        (let [meta    (rf.registrar/lookup :event :wire/received)
              verdict (rf.spec/validate-at-boundary!
                        :wire/received [:wire/received {:untrusted 1}] meta nil)]
          (is (= [nil] @seen)
              "the boundary delegated the EXACT nil token to the validator")
          (is (false? verdict)
              "the invalid event is rejected — the handler will not run"))))))

(deftest boundary-arm-fails-closed-on-nil-schema-under-default-malli
  (testing "with the DEFAULT Malli validator a present nil schema
            cannot silently run a boundary handler: Malli throws on the
            non-schema form, the seam isolates the throw to false, and the
            boundary rejects (the malformed-schema fail-closed route)"
    ;; Fixture reset restored the default Malli validator.
    (rf/reg-event :wire/received
      {:schema    nil
       :boundary? true}
      (fn [_ _] {}))
    (with-redefs [rf.spec/dev-mode? (constantly false)]
      (let [meta (rf.registrar/lookup :event :wire/received)]
        (is (false? (rf.spec/validate-at-boundary!
                      :wire/received [:wire/received {:untrusted 1}] meta nil))
            "rejected — never registration success plus runtime no-op")))))

(deftest boundary-nil-validator-still-disables-validation-in-production
  (testing "control — set-schema-fns! {:validate nil} is the documented
            global opt-out: even a present nil schema passes the boundary
            unchecked when validation is disabled"
    (rf.schemas/set-schema-fns! {:validate nil})
    (rf/reg-event :wire/received
      {:schema    nil
       :boundary? true}
      (fn [_ _] {}))
    (with-redefs [rf.spec/dev-mode? (constantly false)]
      (let [meta (rf.registrar/lookup :event :wire/received)]
        (is (true? (rf.spec/validate-at-boundary!
                     :wire/received [:wire/received {:untrusted 1}] meta nil))
            "no validator registered → no validation → handler runs")))))

;; ===========================================================================
;; Default Malli takes the malformed-schema fail-closed route
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
;; The controls that make the spy proofs non-vacuous
;; ===========================================================================

(deftest omitted-schema-key-never-consults-the-validator
  (testing "an ABSENT :schema key is a no-op on every meta surface —
            the validator is never invoked and every surface passes"
    (let [seen (spy-validator!)]
      (is (true? (rf.schemas/validate-event! :ev/x [:ev/x 1] {:doc "no schema"})))
      (is (true? (rf.schemas/validate-event! :ev/x [:ev/x 1] nil))
          "nil registration metadata is 'no declaration' too")
      (is (true? (rf.schemas/validate-fx! :fx/x :ev/x {:a 1} {})))
      (is (true? (rf.schemas/validate-sub! :sub/x [:sub/x] 42 {})))
      (is (= [] @seen) "the validator was never consulted"))))

(deftest nil-registered-validator-disables-validation-for-present-falsey-tokens
  (testing "set-schema-fns! {:validate nil} disables validation even for a
            present nil / false declaration — the documented global opt-out"
    (rf.schemas/set-schema-fns! {:validate nil})
    (is (true? (rf.schemas/validate-event! :ev/x [:ev/x 1] {:schema nil})))
    (is (true? (rf.schemas/validate-fx! :fx/x :ev/x {:a 1} {:schema false})))
    (is (true? (rf.schemas/validate-sub! :sub/x [:sub/x] 42 {:schema nil})))))
