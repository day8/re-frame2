(ns re-frame.schemas-record-type-tag-test
  "The always-on `:errors` record for a rejected app-db candidate tags the
  failing leaf from a closed vocabulary, never from the leaf's host class: the
  record is built from framework literals so no payload rides it. The CLJS
  sibling pins the disclosure half, where `type` reads a value-owned
  `constructor`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.interop :as rf.interop]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- rejection-reasons
  "The `:reason` of every app-db rejection record that validating `db` emits
  on the always-on `:errors` stream."
  [db]
  (let [errors (atom [])]
    (rf.error-emit/register-error-listener! ::rec (fn [r] (swap! errors conj r)))
    (try
      (rf.schemas/validate-app-schema! db :tenant/set-bad)
      (finally
        (rf.error-emit/unregister-error-listener! ::rec)))
    (into []
          (comp (filter #(and (= :rf.error/schema-validation-failure (:error %))
                              (= :app-db (:where %))))
                (map :reason))
          @errors)))

(deftest rejection-record-reason-carries-no-host-class-name
  (testing "a leaf outside the named tags reads `got object`, never its class
            name, and the named tags keep their `type-of-value` spelling"
    (when rf.interop/debug-enabled?
      (rf/reg-app-schema [:tenant] [:map [:id :int]])
      (let [[reason :as reasons] (rejection-reasons {:tenant {:id #{:not :an :int}}})]
        (is (= 1 (count reasons)))
        (is (str/includes? reason "got object") reason)
        (is (not (str/includes? reason "PersistentHashSet")) reason)
        (is (str/includes? reason "[:tenant]") "the reason names its registered path"))
      (is (str/includes? (first (rejection-reasons {:tenant {:id "text"}})) "got string"))
      (is (str/includes? (first (rejection-reasons {:tenant {:id nil}})) "got nil")))))
