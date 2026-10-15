(ns re-frame.schemas-implies-validation-test
  "Requiring the facade activates default validation without an explicit Malli require."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(deftest validate-app-schema-returns-boolean
  (rf/reg-app-schema [:n] [:int])
  (is (= [true false] [(rf.schemas/validate-app-schema! {:n 42})
                       (rf.schemas/validate-app-schema! {:n "boom"})])))
