(ns re-frame.region-initial-desugar-test
  "Registration desugars a machine's whole definition once (`:timeout` onto
  `:after`, `:type :choice` onto `:always`), region bodies included, so the
  birth path, which applies a region directly, sees the lowered form, and no
  transition desugars again."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.choice :as rf.machines.choice]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timeout :as rf.machines.timeout]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)

(deftest region-initial-timeout-arms-at-birth
  (let [traces (atom [])]
    (rf/reg-machine :rf.region-desugar/timeout
      {:type    :parallel
       :regions {:left {:initial :waiting
                        :states  {:waiting {:timeout "PT5S" :on-timeout {:target :done}}
                                  :done    {}}}}})
    (rf/register-listener! :trace ::t (fn [ev] (swap! traces conj ev)))
    (rf/dispatch-sync [:rf.region-desugar/timeout [:rf.machine/start]])
    (rf/unregister-listener! :trace ::t)
    (is (some #(and (= :rf.machine.timer/scheduled (:operation %)) (= 5000 (:delay (:tags %))))
              @traces)
        "the region-initial :timeout armed its lowered :after at birth")))

(deftest region-initial-choice-resolves-at-birth
  (rf/reg-machine :rf.region-desugar/choice
    {:type    :parallel
     :regions {:left {:initial :pick
                      :states  {:pick {:type :choice :choice [{:target :yes}]}
                                :yes  {}}}}})
  (rf/dispatch-sync [:rf.region-desugar/choice [:rf.machine/start]])
  (is (= :yes (get-in (snapshot :rf.region-desugar/choice) [:state :left]))
      "the transient :choice node settled past at birth"))

(deftest a-registered-machine-runs-without-desugaring
  (rf/reg-machine :rf.region-desugar/once
    {:type    :parallel
     :regions {:left {:initial :waiting
                      :states  {:waiting {:timeout 5000 :on-timeout {:target :done}
                                          :on      {:go :pick}}
                                :pick    {:type :choice :choice [{:target :done}]}
                                :done    {}}}}})
  (let [calls            (atom 0)
        desugar-timeouts rf.machines.timeout/desugar-timeouts
        desugar-choices  rf.machines.choice/desugar-choices]
    (with-redefs [rf.machines.timeout/desugar-timeouts (fn [m] (swap! calls inc) (desugar-timeouts m))
                  rf.machines.choice/desugar-choices   (fn [m] (swap! calls inc) (desugar-choices m))]
      (rf/dispatch-sync [:rf.region-desugar/once [:rf.machine/start]])
      (rf/dispatch-sync [:rf.region-desugar/once [:go]]))
    (is (= [:done 0] [(get-in (snapshot :rf.region-desugar/once) [:state :left]) @calls])
        "birth and a transition through the :choice node ran on the registered definition, desugaring nothing")))
