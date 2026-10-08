(ns re-frame.machine-cofx-attach-test
  "A named guard / action's `:rf.cofx/requires` is ensured before transition
  selection on every slot the runtime selects from; an inline declaration is
  refused at registration."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.cofx-attach :as rf.machines.cofx-attach]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  (:import [clojure.lang ExceptionInfo]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private machine-state rf.machines.test-support/machine-state)

(def ^:private rolled-six
  {:rf.cofx/requires [:test/roll8]
   :fn (fn [{cofx :rf.cofx}] (= 6 (:test/roll8 cofx)))})

(defn- reg-roll! [] (rf/reg-cofx :test/roll8 {:recordable? true} (fn [] 6)))

(defn- ensured-ids [m snap event]
  (set (map :id (rf.machines.cofx-attach/ensure-set-for
                  (rf.machines.cofx-attach/index-ensure-sets m) snap event))))

(defn- registration-error-id [machine]
  (try (rf.machines/make-machine-handler machine) nil
       (catch ExceptionInfo e (:rf.error/id (ex-data e)))))

(deftest inline-requires-is-refused-at-registration
  (doseq [[label machine]
          [["an inline :on transition map"
            {:initial :idle
             :states  {:idle {:on {:go {:rf.cofx/requires [:rf/time-ms] :target :done}}}
                       :done {}}}]
           ["a :guards entry map with no :fn"
            {:initial :idle
             :guards  {:bad {:rf.cofx/requires [:rf/time-ms]}}
             :states  {:idle {:on {:go {:target :done :guard :bad}}}
                       :done {}}}]
           ["a :type :choice candidate"
            {:initial :idle
             :states  {:idle     {:on {:go :checking}}
                       :checking {:type   :choice
                                  :choice [{:rf.cofx/requires [:rf/time-ms] :target :a}
                                           {:target :b}]}
                       :a {} :b {}}}]]]
    (is (= :rf.error/machine-cofx-requires-inline (registration-error-id machine)) label)))

(deftest candidate-action-and-always-closure-facts-are-ensured
  ;; :go's action, :b's :always action and :c's :always guard (two hops past
  ;; the candidate target) each require a fact the ensure step must generate
  ;; before the one macrostep that settles the whole chain.
  (rf/reg-cofx :test/token {:recordable? true} (fn [] :GENERATED))
  (rf/reg-cofx :test/jitter {:recordable? true} (fn [] 42))
  (reg-roll!)
  (rf/reg-machine :attach/closure
    {:initial :a
     :data    {}
     :guards  {:rolled-six? rolled-six}
     :actions {:capture {:rf.cofx/requires [:test/token]
                         :fn (fn [{:keys [data] cofx :rf.cofx}]
                               {:data (assoc data :captured (:test/token cofx))})}
               :jitter  {:rf.cofx/requires [:test/jitter]
                         :fn (fn [{:keys [data] cofx :rf.cofx}]
                               {:data (assoc data :jitter (:test/jitter cofx))})}}
     :states  {:a    {:on {:go {:target :b :action :capture}}}
               :b    {:always {:action :jitter :target :c}}
               :c    {:always {:guard :rolled-six? :target :done}}
               :done {}}})
  (rf/dispatch-sync [:attach/closure [:go]])
  (is (= {:state :done :data {:captured :GENERATED :jitter 42}}
         (select-keys (rf.machines.test-support/snapshot :attach/closure) [:state :data]))))

(deftest guard-facts-are-ensured-for-root-parallel-root-and-choice-selection
  (reg-roll!)
  (doseq [[id machine expected]
          [[:attach/root-on
            {:initial :idle
             :guards  {:g rolled-six}
             :on      {:go {:target :done :guard :g}}
             :states  {:idle {} :done {}}}
            :done]
           [:attach/parallel-root-on
            {:type    :parallel
             :guards  {:g rolled-six}
             :on      {:go {:target [[:a :two] [:b :two]] :guard :g}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}
            {:a :two :b :two}]
           [:attach/choice
            {:initial :idle
             :guards  {:g rolled-six}
             :states  {:idle     {:on {:go :checking}}
                       :checking {:type :choice :choice [{:guard :g :target :hit} {:target :miss}]}
                       :hit {} :miss {}}}
            :hit]]]
    (rf/reg-machine id machine)
    (rf/dispatch-sync [id [:go]])
    (is (= expected (machine-state id)) (str id))))

;; The reg-machine macro walks an inline literal; both arms must keep a named
;; entry map's :rf.cofx/requires and :fn at the top level, never re-wrap it.
(deftest inline-literal-named-cofx-guard-fires-in-both-macro-arms
  (reg-roll!)
  (doseq [[id dev?] [[:attach/inline-dev true] [:attach/inline-prod false]]]
    (with-redefs [rf.interop/debug-enabled? dev?]
      (rf/reg-machine id
        {:initial :idle
         :guards  {:rolled-six? {:rf.cofx/requires [:test/roll8]
                                 :fn (fn [{cofx :rf.cofx}] (= 6 (:test/roll8 cofx)))}}
         :states  {:idle {:on {:go {:target :done :guard :rolled-six?}}}
                   :done {}}}))
    (rf/dispatch-sync [id [:go]])
    (is (= :done (machine-state id)) (str id))))

(deftest region-body-root-on-guard-fact-is-ensured
  (rf/reg-cofx :test/roll8 {:recordable? true} (fn [] 6))
  (let [snap {:state {:a :idle :b :x} :data {}}]
    (testing "CASE: the guard on region :a's own root :on"
      (is (contains? (ensured-ids {:type    :parallel
                                   :guards  {:g rolled-six}
                                   :regions {:a {:initial :idle
                                                 :on      {:go {:target :done :guard :g}}
                                                 :states  {:idle {} :done {}}}
                                             :b {:initial :x :states {:x {}}}}}
                                  snap [:go])
                     :test/roll8)))
    (testing "CONTROL: the same guard on region :a's leaf :on"
      (is (contains? (ensured-ids {:type    :parallel
                                   :guards  {:g rolled-six}
                                   :regions {:a {:initial :idle
                                                 :states  {:idle {:on {:go {:target :done :guard :g}}}
                                                           :done {}}}
                                             :b {:initial :x :states {:x {}}}}}
                                  snap [:go])
                     :test/roll8)))))

;; Each synthetic event selects from a slot the :on walk never visits.
(deftest synthetic-slot-candidate-facts-are-ensured
  (reg-roll!)
  (let [flow {:initial :s1
              :on-done {:target :after :guard :g}
              :states  {:s1 {} :fin {:final? true}}}
        par  {:type    :parallel
              :guards  {:g rolled-six}
              :regions {:a {:initial :flow :states {:flow flow :after {}}}
                        :b {:initial :x :states {:x {}}}}}
        two  {:initial :one :states {:one {} :two {}}}]
    (are [ids m snap event] (= ids (ensured-ids m snap event))
      ;; a region compound's :on-done, raised with its region head
      #{:test/roll8} par {:state {:a [:flow :fin] :b :x}} [:rf.machine/done [:a :flow]]
      ;; a done raised under a FOREIGN region head is not :a's
      #{}            par {:state {:a [:flow :fin] :b :x}} [:rf.machine/done [:b :flow]]
      ;; a single :spawn's :on-error
      #{:test/roll8} {:initial :working
                      :guards  {:g rolled-six}
                      :states  {:working {:spawn {:machine-id :x/child
                                                  :on-error   {:target :errored :guard :g}}}
                                :errored {}}}
                     {:state :working} [:rf.machine.spawn/error [:working] {:boom 1}]
      ;; a state :after
      #{:test/roll8} {:initial :waiting
                      :guards  {:g rolled-six}
                      :states  {:waiting {:after {5000 {:guard :g :target :done}}} :done {}}}
                     {:state :waiting} [:rf.machine.timer/after-elapsed 5000 1 [:waiting]]
      ;; a region state's :after, whose decl-path carries the region head
      #{:test/roll8} {:type    :parallel
                      :guards  {:g rolled-six}
                      :regions {:a {:initial :waiting
                                    :states  {:waiting {:after {3000 {:guard :g :target :done}}}
                                              :done    {}}}
                                :b two}}
                     {:state {:a :waiting :b :one}} [:rf.machine.timer/after-elapsed 3000 1 [:a :waiting]]
      ;; a parallel root's :after action
      #{:test/roll8} {:type    :parallel
                      :actions {:g rolled-six}
                      :after   {1000 {:target [[:a :two] [:b :two]] :action :g}}
                      :regions {:a two :b two}}
                     {:state {:a :one :b :one}} [:rf.machine.timer/after-elapsed 1000 1 []])))
