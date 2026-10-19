(ns re-frame.flows-output-app-schema-test
  "A flow's output shape is declared once, with `reg-app-schema` at the flow's
  `:output-path` (Spec 013 §Flow output validation). The commit boundary
  validates the flow-augmented candidate, so an event whose flow output
  violates that schema is rejected whole: neither the handler's write nor the
  flow's installs, and the failure is one `:rf.error/schema-validation-failure`
  with `:where :app-db` and `:rollback? true`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  (fn [test-fn]
    (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
    (test-fn)))

(defn- db [] (rf/app-db-value :rf/default))

(defn- failures [traces]
  (filterv #(= :rf.error/schema-validation-failure (:operation %)) @traces))

(deftest a-flow-output-violating-its-app-schema-rejects-the-event
  (rf/reg-app-schema [:rect :area] [:int {:min 0}])
  (rf/reg-event :rect/set (fn [{:keys [db]} [_ w h]] {:db (assoc db :w w :h h)}))
  (rf/reg-flow :rect/area {:inputs [[:w] [:h]] :output-path [:rect :area]}
    (fn [w h] (* w h)))
  (rf/make-frame {:id :rf/default :flows [:rect/area]})
  (rf/dispatch-sync [:rect/set 3 4])
  (let [before (db)]
    (is (= 12 (get-in before [:rect :area])))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:rect/set 3 -4])
      (is (= before (db)) "the handler's write and the flow's write are rejected together")
      (is (= [[:app-db [:rect :area] -12 true]]
             (mapv (comp (juxt :where :path :value :rollback?) :tags) (failures traces)))))))
