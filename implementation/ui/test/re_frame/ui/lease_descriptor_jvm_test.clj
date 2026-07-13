(ns re-frame.ui.lease-descriptor-jvm-test
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ui.lease-descriptor :as lease]))

(defn- error-id [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo ex
      (:rf.error/id (ex-data ex)))))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo ex
      (ex-data ex))))

(def ^:private base-thrown-keys
  "The canonical thrown-error slots (Spec 009 §The thrown-error shape)."
  #{:rf.error/id :where :recovery :reason})

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

(deftest descriptor-evidence-matches-the-catalogued-lease-arm
  ;; The Spec 009 :rf.error/ui-tree-malformed LEASE-DESCRIPTOR arm
  ;; (rf2-vxgfnd.113) permits EXACTLY this payload per branch. Each case
  ;; pins the COMPLETE ex-data key set, so an undocumented evidence key
  ;; added to the runtime — or one silently dropped — fails here until
  ;; the catalogue row moves in the same change.
  (testing "every branch throws as re-frame.ui/lease with :fix-lease-descriptor"
    (doseq [descriptor [42 [:feed/items] {} {:resource "feed/items"}
                        {:resource :feed/items :extra true}]]
      (let [data (error-data #(lease/validate-descriptor! descriptor))]
        (is (= 're-frame.ui/lease (:where data)) (pr-str descriptor))
        (is (= :fix-lease-descriptor (:recovery data)) (pr-str descriptor)))))
  (testing "non-map descriptor: base slots + :descriptor-summary, nothing else"
    (doseq [descriptor [42 [:feed/items] "feed" :feed/items]]
      (is (= (into base-thrown-keys [:descriptor-summary])
             (set (keys (error-data #(lease/validate-descriptor! descriptor)))))
          (pr-str descriptor))))
  (testing "unknown keys: base slots + :descriptor-keys + deterministic :unknown"
    (let [data (error-data #(lease/validate-descriptor!
                             {:resource :feed/items :zebra 1 :extra true}))]
      (is (= (into base-thrown-keys [:descriptor-keys :unknown])
             (set (keys data))))
      (is (= [:extra :zebra] (:unknown data))
          "offending keys are reported sorted by printed form")))
  (testing "missing :resource: base slots + :descriptor-keys, nothing else"
    (is (= (into base-thrown-keys [:descriptor-keys])
           (set (keys (error-data #(lease/validate-descriptor! {})))))))
  (testing "non-qualified :resource: base slots + :descriptor-keys + :resource-summary"
    (doseq [descriptor [{:resource :unqualified} {:resource "feed/items"}]]
      (is (= (into base-thrown-keys [:descriptor-keys :resource-summary])
             (set (keys (error-data #(lease/validate-descriptor! descriptor)))))
          (pr-str descriptor)))))
