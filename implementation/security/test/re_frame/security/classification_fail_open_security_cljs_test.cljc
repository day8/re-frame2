(ns re-frame.security.classification-fail-open-security-cljs-test
  "Adversarial test for path-based classification at egress.

  A `:sensitive` effect classifies an app-db path, not a value. The egress
  walker redacts that path, but an identical value copied to an unclassified
  path remains visible. Classification therefore provides egress hygiene, not
  taint tracking or a secrecy guarantee."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; The fixture supplies the default frame required by dispatch and egress.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private secret "S3CR3T-rf2-nk2h6m-DO-NOT-LEAK")

(deftest classified-path-redacts-at-egress
  ;; The app keeps the raw value; egress redacts the classified path and ships
  ;; the unclassified copy raw (classification is path-based and fail-open).
  (rf/reg-event :auth/login-and-copy
    (fn [{:keys [db]} _]
      {:db        (-> db
                      (assoc-in [:auth :token] secret)
                      (assoc-in [:ui :rendered-token] secret))
       :sensitive [[:auth :token]]}))
  (rf/dispatch-sync [:auth/login-and-copy])
  (let [db (rf.frame/frame-app-db-value :rf/default)]
    (is (= [{:auth {:token secret}           :ui {:rendered-token secret}}
            {:auth {:token :rf/redacted}     :ui {:rendered-token secret}}]
           [db (rf.elision/elide-wire-value db)]))))
