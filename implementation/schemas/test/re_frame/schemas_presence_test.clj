(ns re-frame.schemas-presence-test
  "Declaration presence is KEY-presence, not value truthiness (Spec 010 §The
  `:schema` value is opaque): an absent `:schema` key means no declaration,
  while a present key hands its exact value, nil included, to the validator.
  Reading a present nil as nothing-to-check would run a `:boundary? true`
  handler unguarded in production."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.spec :as rf.spec]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- spy-validator!
  "Install a validator that records each schema token it is handed and
  returns false. Returns the recording atom."
  []
  (let [seen (atom [])]
    (rf.schemas/set-schema-fns! {:validate (fn [schema _] (swap! seen conj schema) false)})
    seen))

(defn- at-boundary
  "The router's production step-1 verdict for a registered `:wire/received`."
  []
  (rf.spec/validate-at-boundary! :wire/received [:wire/received {:untrusted 1}]
                                 (rf.registrar/lookup :event :wire/received) nil))

(deftest present-nil-token-is-delegated-verbatim-on-event-fx-sub
  (let [seen (spy-validator!)]
    (is (= [false false false]
           [(rf.schemas/validate-event! :ev/x [:ev/x 1] {:schema nil})
            (rf.schemas/validate-fx! :fx/x :ev/x {:a 1} {:schema nil})
            (rf.schemas/validate-sub! :sub/x [:sub/x] 42 {:schema nil})]))
    (is (= [nil nil nil] @seen))))

(deftest boundary-arm-delegates-a-present-nil-schema-in-production
  (let [seen (spy-validator!)]
    (rf/reg-event :wire/received {:schema nil :boundary? true} (fn [_ _] {}))
    (is (= [false [nil]] [(at-boundary) @seen]))))

(deftest nil-registered-validator-disables-validation-for-present-falsey-tokens
  ;; `{:validate nil}` is the documented global opt-out, on every surface.
  (rf.schemas/set-schema-fns! {:validate nil})
  (rf/reg-event :wire/received {:schema nil :boundary? true} (fn [_ _] {}))
  (is (= [true true true true]
         [(rf.schemas/validate-event! :ev/x [:ev/x 1] {:schema nil})
          (rf.schemas/validate-fx! :fx/x :ev/x {:a 1} {:schema false})
          (rf.schemas/validate-sub! :sub/x [:sub/x] 42 {:schema nil})
          (at-boundary)])))
