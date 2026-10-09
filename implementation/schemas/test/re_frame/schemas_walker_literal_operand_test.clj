(ns re-frame.schemas-walker-literal-operand-test
  "The operator-aware opacity walk: literal operands are data, so an ordinary
  `[:= 42]` or `[:enum 1 2]` neither redacts its failures nor warns at
  registration, while an opaque value in a real child position, a local
  registry and an explicit `[:ref ...]` all fail closed. The CLJS half asserts
  the same shared corpus."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.schemas.walker-literal-operand-fixtures :as rf.schemas.walker-literal-operand-fixtures]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(def ^:private tags {:value [:demo/e 99] :received [:demo/e 99] :explain :exp})

(deftest opacity-walk-classifies-the-shared-corpus
  (doseq [s rf.schemas.walker-literal-operand-fixtures/not-opaque-forms]
    (is (false? (rf.schemas/schema-has-opaque-child? s)) (pr-str s)))
  (doseq [s (concat rf.schemas.walker-literal-operand-fixtures/opaque-forms
                    rf.schemas.walker-literal-operand-fixtures/registry-ref-forms)]
    (is (true? (rf.schemas/schema-has-opaque-child? s)) (pr-str s))))

(deftest nested-registry-ref-warns-walker-opaque-as-unknown
  (with-trace-recorder! [recorded]
    (rf/reg-app-schema [:account] [:map [:home [:ref :fixture/user]]])
    (is (= [{:schema-kind :unknown :path [:account]}]
           (into []
                 (comp (filter #(= :rf.warning/schema-walker-opaque (:operation %)))
                       (map #(select-keys (:tags %) [:schema-kind :path])))
                 @recorded)))))

(deftest non-sensitive-literal-event-failure-rides-verbatim
  (let [trace (with-trace-recorder! [traces]
                (rf.schemas/validate-event! :demo/e [:demo/e 99]
                                            {:schema [:cat [:= :demo/e] [:= 42]]})
                (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces)))]
    (is (not (contains? trace :sensitive?)))
    (is (= [[:demo/e 99] [:demo/e 99]]
           [(-> trace :tags :value) (-> trace :tags :received)]))
    (is (not= :rf/redacted (-> trace :tags :explain)))))

(deftest redact-validation-tags-registry-ref-redacts-and-stamps
  (doseq [schema rf.schemas.walker-literal-operand-fixtures/registry-ref-forms]
    (is (= {:value :rf/redacted :received :rf/redacted :explain :rf/redacted :sensitive? true}
           (rf.schemas/redact-validation-tags schema tags))
        (pr-str schema))))

(deftest redact-validation-tags-map-entry-keyed-ref-rides-verbatim
  (is (= tags (rf.schemas/redact-validation-tags
                [:map [:ref {:optional true} :string]] tags))))
