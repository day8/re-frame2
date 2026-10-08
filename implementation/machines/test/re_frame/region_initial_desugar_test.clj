(ns re-frame.region-initial-desugar-test
  "A region body is desugared (`:timeout` onto `:after`, `:type :choice` onto
  `:always`) where its synthetic region-spec is built and cached, so the birth
  path, which applies the region directly rather than through the per-dispatch
  desugar, sees the lowered form."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
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
