(ns re-frame.final-auto-destroy-elision-drop-cljs-test
  "A spawned actor's `:final?` auto-destroy drops its `:sensitive` / `:large`
  claims and the finalize commit does not re-install them. The router honours
  an effect-carried `:rf.runtime/elision` verbatim, so one built from the
  handler's pre-drop coeffect would leave every finished actor's claim behind
  for good. A singleton rests in its final state with its `:data` intact, so
  it keeps its claims."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(defn- machine-claim-actors
  "Every actor id named by a `:source :machine` claim on either axis of the
  frame's elision registry."
  []
  (set (for [[_axis decls] (:rf.runtime/elision (rf.machines.test-support/runtime-db))
             :when (map? decls)
             [_path owners] decls
             owner owners
             :when (= :machine (:source owner))]
         (:actor-id owner))))

(def ^:private classified-finisher
  {:sensitive [[:data :token]]
   :large     [[:data :blob]]
   :initial   :idle
   :data      {:token "t" :blob "b"}
   :states    {:idle {:on {:finish :done}}
               :done {:final? true}}})

(deftest final-auto-destroy-drops-a-spawned-actors-claims
  (rf/reg-machine :fade/kid classified-finisher)
  (rf/reg-machine :fade/host {:initial :hosting
                              :states  {:hosting {:spawn {:machine-id :fade/kid}}}})
  (rf/dispatch-sync [:fade/host [:rf.machine/start]])
  (let [claimed-while-live? (contains? (machine-claim-actors) :fade/kid#1)]
    (rf/dispatch-sync [:fade/kid#1 [:finish]])
    (is (= [true false] [claimed-while-live? (contains? (machine-claim-actors) :fade/kid#1)])
        "the claim lowered at spawn is gone after the :final? auto-destroy")))

(deftest a-final-singleton-keeps-its-claims
  (rf/reg-machine :fade/single classified-finisher)
  (rf/dispatch-sync [:fade/single [:rf.machine/start]])
  (rf/dispatch-sync [:fade/single [:finish]])
  (is (contains? (machine-claim-actors) :fade/single)
      "its final :data still holds the token, so the claim stays"))
