(ns re-frame.schemas-implies-validation-test
  "Requiring the `re-frame.schemas` facade alone wires the default Malli
  validator: the facade requires `re-frame.schemas.malli`, so registering a
  schema always validates rather than soft-passing every value (Spec 010
  §Recommended soft-pass). This namespace deliberately requires only the
  facade. The behavioural consequence, the default validator rejecting a
  malformed value, is `re-frame.schemas-test/validate-app-schema-returns-boolean`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.late-bind :as rf.late-bind]
            ;; DELIBERATELY require ONLY the facade — NOT
            ;; `re-frame.schemas.malli`. The whole point is that
            ;; requiring the facade is sufficient to wire Malli.
            [re-frame.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(deftest malli-hook-is-wired-by-requiring-the-facade
  (testing "requiring only the facade binds both Malli hooks"
    (is (some? (rf.late-bind/get-fn :schemas/malli-validate))
        ":schemas/malli-validate is bound — the facade pulled the adapter")
    (is (some? (rf.late-bind/get-fn :schemas/malli-explain))
        ":schemas/malli-explain is bound too")))
