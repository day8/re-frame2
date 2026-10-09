(ns re-frame.schemas-validator-unavailable-warning-test
  "`:rf.warning/schema-validator-unavailable`: registering a schema while the
  Malli hook is unbound and the default validator is still installed warns
  once per process, because every check would soft-pass. A bound Malli hook,
  or a validator the app installed itself, suppresses it."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- reasons
  "The `:reason` of each validator-unavailable warning recorded."
  [recorded]
  (into []
        (comp (filter #(and (= :warning (:op-type %))
                            (= :rf.warning/schema-validator-unavailable (:operation %))))
              (map (comp :reason :tags)))
        @recorded))

(defn- with-unbound-malli-validate
  "Run `f` with the `:schemas/malli-validate` hook unbound, as when the Malli
  adapter is not loaded, restoring the hook afterwards."
  [f]
  (let [prior (rf.late-bind/get-fn :schemas/malli-validate)]
    (try
      (swap! rf.late-bind/hooks dissoc :schemas/malli-validate)
      (rf.late-bind/invalidate-cache! :schemas/malli-validate)
      (f)
      (finally
        (when prior
          (rf.late-bind/set-fn! :schemas/malli-validate prior))))))

(deftest warning-fires-once-from-reg-app-schemas-bulk
  (with-trace-recorder! [recorded]
    (with-unbound-malli-validate
      #(rf/reg-app-schemas {[:user]    [:map [:id :int]]
                            [:cart]    [:vector :any]
                            [:session] [:map [:tok :string]]}))
    (let [[reason :as all] (reasons recorded)]
      (is (= 1 (count all)))
      ;; Spec 009's warning row: the reason names both fixes.
      (is (every? #(re-find % reason) [#"re-frame\.schemas\.malli" #"set-schema-fns!"])
          reason))))

(deftest warning-suppressed-when-explicit-validator-installed
  (with-trace-recorder! [recorded]
    (with-unbound-malli-validate
      (fn []
        (rf.schemas/set-schema-fns! {:validate (fn [_schema _value] true)})
        (rf/reg-app-schema [:user] [:map [:id :int]])))
    (is (empty? (reasons recorded)))))

(deftest warning-suppressed-when-malli-validate-hook-bound
  (with-trace-recorder! [recorded]
    (rf/reg-app-schema [:user] [:map])
    (is (empty? (reasons recorded)))))
