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

(def ^:private exception-message (str "rejected " sentinel))

(def ^:private exception-slots [:exception-data :exception-message :reason :exception])

(defn- emit-machine-action-exception
  "Emit through production projection and return the error envelope."
  [ids]
  (with-trace-recorder! [traces]
    (rf.trace/emit-error! :rf.error/machine-action-exception
                          (into {} (remove (comp nil? val))
                                (merge {:frame             :rf/default
                                        :action-id         :do/thing
                                        :state-path        [:running]
                                        :exception         (ex-info exception-message exception-data)
                                        :exception-message exception-message
                                        :exception-data    exception-data
                                        :reason            "Machine action threw."
                                        :recovery          :no-recovery}
                                       ids)))
    (first (filter #(= :rf.error/machine-action-exception (:operation %))
                   @traces))))

;; Actor identity wins; an unresolved frame fails closed. A sensitive row
;; redacts its exception text and the raw exception with its data, because a
;; message routinely quotes the value that failed.
(deftest machine-exception-slot-redaction-follows-the-addressed-machine
  (rf/reg-event :sec/sensitive-machine {:sensitive [[:data :token]]} (fn [_ _] nil))
  (rf/reg-event :sec/plain-machine (fn [_ _] nil))
  (doseq [[label ids] [["sensitive by :machine-id" {:machine-id :sec/sensitive-machine}]
                       ["sensitive :actor-id beats a plain :machine-id"
                        {:actor-id :sec/sensitive-machine :machine-id :sec/plain-machine}]
                       ["plain machine, no frame" {:actor-id :sec/plain-machine :frame nil}]]]
    (let [out (emit-machine-action-exception ids)]
      (is (= [(zipmap exception-slots (repeat :rf/redacted)) true false]
             [(select-keys (:tags out) exception-slots) (:sensitive? out) (contains-sentinel? out)])
          label)))
  (let [out (emit-machine-action-exception {:machine-id :sec/plain-machine})]
    (is (= [exception-data exception-message "Machine action threw." nil]
           [(-> out :tags :exception-data) (-> out :tags :exception-message)
            (-> out :tags :reason) (:sensitive? out)])
        "a plain machine in a known frame keeps its exception slots verbatim")
    (is (contains-sentinel? (-> out :tags :exception))
        "the raw :exception prints its message and data, so it is redacted with them")))

(defn- emit-machine-row
  "Emit `op` with `tags` (nil slots dropped) through production projection and
  return its envelope."
  [op tags]
  (let [tags (into {} (remove (comp nil? val)) tags)]
    (with-trace-recorder! [traces]
      (if (= "rf.error" (namespace op))
        (rf.trace/emit-error! op tags)
        (rf.trace/emit! :rf.machine op tags))
      (first (filter #(= op (:operation %)) @traces)))))

;; An `:on-done` callback's row and a guard's `:outcome :threw` row carry the
;; thrown value with no `:exception-data`; they redact on the same condition.
(deftest machine-rows-without-exception-data-redact-on-the-same-condition
  (rf/reg-event :sec/sensitive-machine {:sensitive [[:data :token]]} (fn [_ _] nil))
  (rf/reg-event :sec/plain-machine (fn [_ _] nil))
  (doseq [[op row] [[:rf.error/machine-action-exception
                     {:failing-id        :rf.machine.spawn/on-done
                      :exception         (ex-info exception-message exception-data)
                      :exception-message exception-message
                      :reason            ":on-done callback threw."
                      :recovery          :no-recovery}]
                    [:rf.machine/guard-evaluated
                     {:guard-id  :sec/check
                      :outcome   :threw
                      :exception (ex-info exception-message exception-data)}]]
          [label ids redacted?] [["sensitive machine" {:actor-id :sec/sensitive-machine :frame :rf/default} true]
                                 ["plain machine, no frame" {:actor-id :sec/plain-machine :frame nil} true]
                                 ["plain machine" {:actor-id :sec/plain-machine :frame :rf/default} false]]]
    (let [out (emit-machine-row op (merge row ids))]
      (is (= [redacted? (not redacted?)]
             [(boolean (:sensitive? out)) (contains-sentinel? out)])
          (str op " / " label)))))

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
