(ns re-frame.security.error-slot-egress-security-cljs-test
  "Error-slot redaction follows machine and route sensitivity."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            ;; These facades publish navigation validation hooks.
            [re-frame.schemas]
            [re-frame.routing]
            [re-frame.routing.navigate :as rf.routing.navigate]
            #?(:clj  [re-frame.test-support :as rf.test-support :refer [with-trace-recorder!]]
               :cljs [re-frame.test-support :as rf.test-support :refer-macros [with-trace-recorder!]])
            [re-frame.trace :as rf.trace]
            [re-frame.security.gen :as rf.security.gen]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture))

(def ^:private sentinel "S3CR3T-rf2-zsm03-ERROR-SLOT-DO-NOT-LEAK")

(defn- contains-sentinel?
  "Scan all trace slots, including stringified data, for the sentinel."
  [x]
  (rf.security.gen/contains-string? x sentinel))

(def ^:private exception-data {:user-token sentinel :doc-id [sentinel]})

(defn- emit-machine-action-exception
  "Emit through production projection and return the error envelope."
  [ids]
  (with-trace-recorder! [traces]
    (rf.trace/emit-error! :rf.error/machine-action-exception
                          (into {} (remove (comp nil? val))
                                (merge {:frame             :rf/default
                                        :action-id         :do/thing
                                        :state-path        [:running]
                                        :exception-message "boom"
                                        :exception-data    exception-data
                                        :reason            "Machine action threw."
                                        :recovery          :no-recovery}
                                       ids)))
    (first (filter #(= :rf.error/machine-action-exception (:operation %))
                   @traces))))

;; Actor identity wins; an unresolved frame fails closed.
(deftest machine-exception-data-redaction-follows-the-addressed-machine
  (rf/reg-event :sec/sensitive-machine {:sensitive [[:data :token]]} (fn [_ _] nil))
  (rf/reg-event :sec/plain-machine (fn [_ _] nil))
  (doseq [[label ids] [["sensitive by :machine-id" {:machine-id :sec/sensitive-machine}]
                       ["sensitive :actor-id beats a plain :machine-id"
                        {:actor-id :sec/sensitive-machine :machine-id :sec/plain-machine}]
                       ["plain machine, no frame" {:actor-id :sec/plain-machine :frame nil}]]]
    (let [out (emit-machine-action-exception ids)]
      (is (= [:rf/redacted true false]
             [(-> out :tags :exception-data) (:sensitive? out) (contains-sentinel? out)])
          label)))
  (let [out (emit-machine-action-exception {:machine-id :sec/plain-machine})]
    (is (= [exception-data nil] [(-> out :tags :exception-data) (:sensitive? out)])
        "a plain machine in a known frame keeps :exception-data verbatim")))

(defn- capture-navigate-failure
  "Invalid route params reject before browser effects."
  [params-schema]
  (rf/reg-route :sec/route {:params params-schema} "/doc/:doc")
  (with-trace-recorder! [traces]
    (rf.routing.navigate/navigate-handler {:db {} :rf.frame/id :rf/default}
                                          [:rf.route/navigate {:to :sec/route :params {:doc sentinel}}])
    (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                   @traces))))

(deftest navigate-error-redacted-for-sensitive-route
  (let [trace (capture-navigate-failure [:map [:doc {:sensitive? true} :int]])]
    (is (= [:rf/redacted true false]
           [(-> trace :tags :error) (:sensitive? trace) (contains-sentinel? trace)]))))

(deftest navigate-error-verbatim-for-plain-route
  (let [trace (capture-navigate-failure [:map [:doc :int]])]
    (is (= [true false] [(map? (-> trace :tags :error)) (contains? trace :sensitive?)])
        ":error rides as the raw ex-data map with no :sensitive? stamp")))
