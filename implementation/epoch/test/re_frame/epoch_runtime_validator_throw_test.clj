(ns re-frame.epoch-runtime-validator-throw-test
  "A pair-tool write of the runtime-db partition is checked against the
  framework-owned runtime-db validator, `:machines/validate-machine-data!`.
  A validator that THROWS has not vouched for the value it was handed, so
  `failing-runtime-paths` reports the snapshots root as failing and
  `replace-frame-state!` refuses the write — the same fail-closed verdict
  the router gives a throwing machine-data validator on a dispatched
  transition, and the one `failing-schema-paths` gives a throwing app-db
  validator."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch]
            [re-frame.epoch.tool-pair :as rf.epoch.tool-pair]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private snapshots-path [:rf.runtime/machines :snapshots])

(def ^:private well-formed-runtime-db {:rf.runtime/machines {:snapshots {}}})

(defn- with-runtime-validator
  "Run `body-fn` with `f` published as the runtime-db validator, then put
  back whatever was published before, so no other test sees `f`."
  [f body-fn]
  (let [saved (rf.late-bind/get-fn :machines/validate-machine-data!)]
    (try
      (rf.late-bind/set-fn! :machines/validate-machine-data! f)
      (body-fn)
      (finally
        (rf.late-bind/set-fn! :machines/validate-machine-data! saved)))))

(deftest a-throwing-runtime-validator-fails-the-snapshots-path
  (testing "a validator that throws reports the snapshots root as failing"
    (with-runtime-validator
      (fn [_runtime-db _event-id _frame-id] (throw (ex-info "validator broke" {})))
      #(is (= [snapshots-path]
              (rf.epoch.tool-pair/failing-runtime-paths :app/main well-formed-runtime-db)))))
  (testing "CONTROL — the same call under a validator that answers true is valid"
    (with-runtime-validator
      (fn [_runtime-db _event-id _frame-id] true)
      #(is (= [] (rf.epoch.tool-pair/failing-runtime-paths :app/main well-formed-runtime-db))))))

(deftest ^:requires-debug replace-frame-state-refuses-a-runtime-db-the-validator-cannot-walk
  ;; The shipped validator walks `[:rf.runtime/machines :snapshots]` as a
  ;; map of snapshots, so a value there that is not one throws inside the walk.
  (rf/make-frame {:id ::f :doc "pair-tool write target"})
  (let [before (rf/frame-state-value ::f)]
    (is (false? (rf/replace-frame-state!
                  ::f {:rf.db/runtime {:rf.runtime/machines {:snapshots :not-a-map}}}))
        "the write is refused")
    (is (= before (rf/frame-state-value ::f)) "the frame-state is untouched")
    (is (true? (rf/replace-frame-state! ::f {:rf.db/runtime well-formed-runtime-db}))
        "CONTROL — a runtime-db the validator can walk is accepted on the same frame")))
