(ns re-frame.machine-schema-check-seam-test
  "Both machine schema boundaries report through the `:schemas/check!` seam
  (Spec 010 §The validation seam): a `:where :machine-data` or
  `:where :machine-output` failure carries `:failing-id`, `:frame` and, in
  development, `:explain-humanized`, and a validator that throws reports
  `:rf.error/malformed-schema` rather than an ordinary failure."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]  ;; loaded for its late-bind hooks (`rf/reg-machine`)
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- traces-of
  "The `op` traces at `where` that `f` emits."
  [op where f]
  (rf.machines.test-support/with-trace-capture traces
    (f)
    (filterv #(and (= op (:operation %)) (= where (-> % :tags :where))) @traces)))

(defn- with-throwing-validator
  "Run `f` under a validator that throws, as Malli does on a malformed form."
  [f]
  (let [installed (rf.schemas/schema-fns)]
    (try
      (rf.schemas/set-schema-fns! {:validate (fn [_ _] (throw (ex-info "schema boom" {})))})
      (f)
      (finally (rf.schemas/set-schema-fns! installed)))))

(defn- reg-counter! [id]
  (rf/reg-machine id
    {:initial :idle
     :data    {:n 0}
     :schemas {:data [:map [:n :int]]}
     :states  {:idle {:on {:bump {:target :idle
                                  :action (fn [{data :data}] {:data (assoc data :n "two")})}}}}}))

(defn- reg-finisher! [id]
  (rf/reg-machine id
    {:initial :running
     :data    {}
     :schemas {:output :int}
     :states  {:running {:on {:fin {:target :done
                                    :action (fn [{data :data}] {:data (assoc data :result "nope")})}}}
               :done    {:final? true :output-key :result}}}))

(def ^:private seam-slots [:failing-id :machine-id :frame])

(deftest machine-data-failure-carries-the-seam-tags
  (reg-counter! :seam/counter)
  (rf/dispatch-sync [:seam/counter [:noop]])
  (let [[ev :as evs] (traces-of :rf.error/schema-validation-failure :machine-data
                       #(rf/dispatch-sync [:seam/counter [:bump]]))]
    (is (= 1 (count evs)))
    (is (= {:failing-id :seam/counter :machine-id :seam/counter :frame :rf/default}
           (select-keys (:tags ev) seam-slots)))
    (is (= {:n ["should be an integer"]} (get-in ev [:tags :explain-humanized])))))

(deftest machine-data-validator-throw-is-a-malformed-schema
  (reg-counter! :seam/counter)
  (rf/dispatch-sync [:seam/counter [:noop]])
  (let [evs (with-throwing-validator
              #(traces-of :rf.error/malformed-schema :machine-data
                 (fn [] (rf/dispatch-sync [:seam/counter [:bump]]))))]
    (is (= [{:failing-id :seam/counter :machine-id :seam/counter :frame :rf/default}]
           (mapv #(select-keys (:tags %) seam-slots) evs)))
    (is (= 0 (:n (:data (rf.machines.test-support/snapshot :seam/counter))))
        "fail closed: the candidate transition is rejected")))

(deftest machine-output-failure-carries-the-seam-tags
  (reg-finisher! :seam/finisher)
  (rf/dispatch-sync [:seam/finisher [:noop]])
  (let [[ev :as evs] (traces-of :rf.error/schema-validation-failure :machine-output
                       #(rf/dispatch-sync [:seam/finisher [:fin]]))]
    (is (= 1 (count evs)))
    (is (= {:failing-id :seam/finisher :machine-id :seam/finisher :frame :rf/default}
           (select-keys (:tags ev) seam-slots)))
    (is (= ["should be an integer"] (get-in ev [:tags :explain-humanized])))))

(deftest machine-output-validator-throw-is-a-malformed-schema-and-completes
  (reg-finisher! :seam/finisher)
  (rf/dispatch-sync [:seam/finisher [:noop]])
  (let [evs (with-throwing-validator
              #(traces-of :rf.error/malformed-schema :machine-output
                 (fn [] (rf/dispatch-sync [:seam/finisher [:fin]]))))]
    (is (= [{:failing-id :seam/finisher :machine-id :seam/finisher :frame :rf/default}]
           (mapv #(select-keys (:tags %) seam-slots) evs)))
    (is (= :done (:state (rf.machines.test-support/snapshot :seam/finisher)))
        "the machine still finishes")))
