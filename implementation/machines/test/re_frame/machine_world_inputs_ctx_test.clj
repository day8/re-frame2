(ns re-frame.machine-world-inputs-ctx-test
  "Machine guard / action / entry callbacks read host facts from the dispatch's
  causal `:rf.cofx` token on their ctx (EP-0010 §The World-Input Rule), never an
  ambient clock; a pure `machine-transition` call carries no such key."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)

;; A fixed epoch ms the host clock will never return, so an ambient read cannot match it.
(def ^:private SCRIPTED-TIME-MS 1234500000)

(deftest entry-action-reads-the-causal-token
  ;; The boot cascade's :entry runs under the first dispatch, so it reads that
  ;; dispatch's token, arbitrary host facts included.
  (rf/reg-machine :world/entry
    {:initial :ready
     :actions {:stamp (fn [{cofx :rf.cofx}]
                        {:data (select-keys cofx [:rf/time-ms :rng-seed :new-uuid])})}
     :states  {:ready {:entry :stamp}}})
  (let [token {:rf/time-ms SCRIPTED-TIME-MS
               :rng-seed   987654321
               :new-uuid   "11111111-2222-3333-4444-555555555555"}]
    (rf/dispatch-sync [:world/entry [:noop]] {:rf.cofx token})
    (is (= token (:data (snapshot :world/entry))))))

(deftest region-guard-reads-causal-time-ms
  (let [m {:type    :parallel
           :guards  {:at-scripted-time? (fn [{cofx :rf.cofx}]
                                          (= SCRIPTED-TIME-MS (:rf/time-ms cofx)))}
           :regions {:a {:initial :idle
                         :states  {:idle {:on {:go {:target :done :guard :at-scripted-time?}}}
                                   :done {}}}
                     :b {:initial :x :states {:x {}}}}}]
    (testing "the token reaches a parallel region's guard ctx"
      (rf/reg-machine :world/region m)
      (rf/dispatch-sync [:world/region [:go]] {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= :done (get-in (snapshot :world/region) [:state :a]))))
    (testing "and the same guard blocks on a non-matching token"
      (rf/reg-machine :world/region-block m)
      (rf/dispatch-sync [:world/region-block [:go]] {:rf.cofx {:rf/time-ms (inc SCRIPTED-TIME-MS)}})
      (is (= :idle (get-in (snapshot :world/region-block) [:state :a]))))))

(deftest pure-fn-callback-ctx-has-no-world-inputs-key
  (let [captured (atom nil)]
    (rf.machines/machine-transition
      {:initial :idle
       :guards  {:capture (fn [ctx] (reset! captured ctx) true)}
       :states  {:idle {:on {:go {:target :done :guard :capture}}}
                 :done {}}}
      {:state :idle :data {}}
      [:go])
    (is (= #{:data :event :state :meta} (set (keys @captured))))))
