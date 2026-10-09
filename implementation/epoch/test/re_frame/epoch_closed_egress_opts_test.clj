(ns re-frame.epoch-closed-egress-opts-test
  "`project-egress` grades ONE closed opts vocabulary at the door, before it
  hands an `:rf/epoch-record` to the epoch arm. A bare spelling of a shared axis
  must throw rather than be dropped: dropped, the caller's opt-in would read as
  applied while the record egressed under the fail-closed floor."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.epoch]
            [re-frame.projection :as rf.projection]))

(defn- bad-opts-ex-data
  "The `ex-data` of a `:rf.error/bad-egress-opts` throw from `f`, or nil when
  nothing throws. Any other error is re-thrown so it reports itself."
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (let [d (ex-data e)]
        (if (= :rf.error/bad-egress-opts (:rf.error/id d))
          d
          (throw e))))))

(def ^:private a-record
  ;; The `:kind` stamp routes it to the epoch arm.
  {:kind     :rf/epoch-record
   :frame    :app/main
   :epoch-id 1
   :db-after {:auth {:password "s3cret"}}})

(deftest project-egress-rejects-the-unqualified-shared-axes
  (testing "the bare spellings of the shared axes are refused, naming the key"
    (is (= [[:include-sensitive?] [:include-large?]]
           (mapv (fn [k] (:unknown-keys (bad-opts-ex-data #(rf/project-egress a-record {k true}))))
                 [:include-sensitive? :include-large?]))))
  (testing "CONTROL — every member of the door's own vocabulary passes on an
            epoch record, so the refusal is not a guard that rejects everything"
    (is (= [] (filterv (fn [k]
                         (let [v (if (= k :rf.egress/profile) :rf.egress/off-box-tool true)]
                           (bad-opts-ex-data #(rf/project-egress a-record {k v}))))
                       (sort rf.projection/project-egress-opt-keys))))))
