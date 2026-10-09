(ns re-frame.flows-direct-clear-settle-failure-cljs-test
  "Spec 013 §The same boundary for a direct `clear-flow`, point 2, on both
  hosts: when the settle a direct clear runs hits a throwing `:derive`, the
  deregistration and vacation stand, the settle's candidate db is discarded
  unwritten, and the ordinary `:rf.error/flow-eval-exception` reaches the
  direct caller. Only the human sentence differs from the drain's, because no
  drain ran and no event aborted."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.flows :as rf.flows]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter substrate/adapter}))

(deftest direct-clear-settle-derive-throw-reports-the-direct-boundary
  (let [derives (atom 0)]
    (rf/reg-event :seed (fn [_ _] {:db {:x 2}}))
    (rf/reg-flow :probe/a {:inputs [[:x]] :output-path [:a]} identity)
    ;; B is total on 2 and refuses A's absence, so it fails only inside the
    ;; settle the clear performs.
    (rf/reg-flow :probe/b {:inputs [[:a]] :output-path [:b]}
      (fn [a]
        (swap! derives inc)
        (if (nil? a) (throw (ex-info "dependent refuses nil" {})) a)))
    (rf/dispatch-sync [:seed])
    (let [data (ex-data (try (rf/clear :flow :probe/a) nil
                             (catch #?(:clj Throwable :cljs :default) e e)))
          msg  (:reason data)]
      (is (= 2 @derives) "the dependent was evaluated inside the clear")
      (is (= {:rf.error/id :rf.error/flow-eval-exception :rf.flow/failed-id :probe/b
              :rf.flow/failed-phase :derive :rf.flow/output-path [:b] :recovery :no-recovery}
             (select-keys data [:rf.error/id :rf.flow/failed-id :rf.flow/failed-phase
                                :rf.flow/output-path :recovery])))
      (is (some? (:cause data)))
      (is (re-find #"clear-flow" msg) "the sentence names the call the reader made")
      (is (nil? (re-find #"during the drain|the event aborts" msg))
          "and claims no drain and no aborted event")
      (is (not (contains? (get (rf.flows/flows-snapshot) :rf/default) :probe/a))
          "the clear stands")
      (is (= {:x 2 :b 2} (rf/app-db-value :rf/default))
          "the vacation committed and the settle candidate did not"))))
