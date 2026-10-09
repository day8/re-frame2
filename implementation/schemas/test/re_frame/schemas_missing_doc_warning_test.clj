(ns re-frame.schemas-missing-doc-warning-test
  "`:rf.warning/missing-doc` for app-db schemas (Spec 001 §`:doc` is
  dev-warned when absent): `reg-app-schema` hands the registrar's shared
  emitter kind `:app-schema`, the canonical path and its metadata, and the
  registrar's own tests pin the emitter's rules. The bulk form is exempt: its
  entries have no per-path `:doc` slot. Production builds erase the emit."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- missing-doc-warnings
  "The kind, id and source namespace of each missing-doc warning recorded."
  [recorded]
  (into []
        (comp (filter #(and (= :warning (:op-type %))
                            (= :rf.warning/missing-doc (:operation %))))
              (map (comp (juxt :kind :id (comp :ns :source-coords)) :tags)))
        @recorded))

(deftest missing-doc-fires-once-for-an-undocumented-macro-registration
  (with-trace-recorder! [recorded]
    (rf/reg-app-schema [:user] [:map [:name :string]])
    (rf/reg-app-schema [:session] {:doc "The signed-in session."} :map)
    (when rf.interop/debug-enabled?
      (is (= [[:app-schema [:user] 're-frame.schemas-missing-doc-warning-test]]
             (missing-doc-warnings recorded))))))

(deftest missing-doc-silent-for-the-bulk-form
  (with-trace-recorder! [recorded]
    (rf/reg-app-schemas {[:auth] :map
                         [:cart] :map})
    (when rf.interop/debug-enabled?
      (is (empty? (missing-doc-warnings recorded)))
      (is (= 're-frame.schemas-missing-doc-warning-test
             (:ns (rf.schemas/app-schema-meta {:frame :rf/default :path [:auth]})))
          "each entry still carries the bulk call site's coords"))))
