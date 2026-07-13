(ns re-frame.ui.lease-descriptor-jvm-test
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ui.lease-descriptor :as lease]))

(defn- error-id [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo ex
      (:rf.error/id (ex-data ex)))))

(deftest descriptor-grammar-is-closed-and-preserves-presence
  (testing "nil is the inactive descriptor"
    (is (nil? (lease/validate-descriptor! nil))))
  (testing "valid maps are returned without normalization"
    (doseq [descriptor [{:resource :feed/items}
                        {:resource :feed/items :params nil}
                        {:resource :feed/items :scope :rf.scope/global
                         :params {:page 1}}]]
      (is (identical? descriptor (lease/validate-descriptor! descriptor))))
    (is (not (contains? (lease/validate-descriptor! {:resource :feed/items})
                        :params)))
    (is (contains? (lease/validate-descriptor!
                    {:resource :feed/items :params nil})
                   :params)))
  (testing "malformed dynamic values fail before capture"
    (doseq [descriptor [42
                        [:feed/items]
                        {}
                        {:resource :unqualified}
                        {:resource "feed/items"}
                        {:resource :feed/items :extra true}]]
      (is (= :rf.error/ui-tree-malformed
             (error-id #(lease/validate-descriptor! descriptor)))
          (pr-str descriptor)))))
