(ns re-frame.machine-transition-purity-test
  "`machine-transition`'s RETURNED VALUE is a deterministic function of its
  arguments: identical (machine, snapshot, event) triples produce identical
  Results, including the spawn ids inside emitted `:rf.machine/spawn` fx. The
  spawn-id counter lives in the snapshot at `:rf/spawn-counter`, never in
  module-level state.

  \"Pure\" is scoped to the REDUCTION. The engine still emits Spec 009 trace
  events inline (this namespace's `capture-error-depth!` reads the depth-limit
  boundary from them); per Spec 005 that trace is observability, never part of
  the snapshot / fx value and never read back into the reduction."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]))

(def ^:private auth-flow-spec
  "On `[:submit]` from `:idle`, enters `:authenticating`, whose `:spawn` emits
  one `:rf.machine/spawn` fx for an `:http/post` child."
  {:initial :idle
   :states  {:idle           {:on {:submit :authenticating}}
             :authenticating {:spawn {:machine-id :http/post}}}})

(deftest machine-transition-is-pure
  (testing "identical args produce identical Results; the spawn id comes from
            the snapshot's counter"
    (let [snap {:state :idle :data {}}
          r    (rf.machines/machine-transition auth-flow-spec snap [:submit])]
      (is (= r (rf.machines/machine-transition auth-flow-spec snap [:submit])))
      ;; The contract slots of the spawn fx; the rest of its arg map is free
      ;; to evolve. `:rf/transition-pure` is the pure-call parent sentinel.
      (is (= [[:rf.machine/spawn {:rf/spawned-id :http/post#1
                                  :rf/parent-id  :rf/transition-pure
                                  :rf/invoke-id  [:authenticating]}]]
             (mapv (fn [[fx-id arg]]
                     [fx-id (select-keys arg [:rf/spawned-id :rf/parent-id :rf/invoke-id])])
                   (:fx r))))
      (is (= {:state            :authenticating
              :data             {:rf/spawned {[:authenticating] :http/post#1}}
              :rf/spawn-counter {:http/post 1}}
             (select-keys (:snapshot r) [:state :data :rf/spawn-counter])))))

  (testing "a snapshot whose counter is pre-populated keeps allocating from where it left off"
    (let [{:keys [snapshot fx]} (rf.machines/machine-transition
                                  auth-flow-spec
                                  {:state :idle :data {} :rf/spawn-counter {:http/post 3}}
                                  [:submit])]
      (is (= :http/post#4 (-> fx first second :rf/spawned-id)))
      (is (= {:http/post 4} (:rf/spawn-counter snapshot))))))

(deftest trace-is-observability-not-reduction
  (let [m      {:initial :idle
                :actions {:bump (fn [{d :data}] {:data {:n (inc (:n d))}})}
                :states  {:idle {:on {:go {:target :done :action :bump}}}
                          :done {}}}
        input  {:state :idle :data {:n 0}}
        bare   (rf.machines/machine-transition m input [:go])
        [observed traces] (rf.machines.test-support/with-trace-capture seen
                            [(rf.machines/machine-transition m input [:go]) @seen])]
    (is (= {:state :done :data {:n 1}} (:snapshot bare)))
    (is (= bare observed) "the Result does not depend on a listener being registered")
    (is (some #(and (= :rf.machine/action-ran (:operation %))
                    (= :bump (-> % :tags :action-id)))
              traces)
        "the listener did observe the transition's trace")))

(deftest pure-machine-transition
  (let [m {:id      :traffic-light
           :initial :red
           :states  {:red   {:on {:tick {:target :green}}}
                     :green {:on {:tick {:target :yellow}}}}}]
    (is (= [:green :yellow]
           (map #(-> (rf.machines/machine-transition m {:state % :data {}} [:tick]) :snapshot :state)
                [:red :green])))))

(deftest machine-always-microstep
  (testing ":always fires after an event nothing handled, under a true guard"
    (let [m {:initial :checking
             :guards  {:authed? (fn [{data :data}] (:authed? data))}
             :states  {:checking {:always [{:guard :authed? :target :authed}]}
                       :authed   {}}}]
      (is (= :authed (-> (rf.machines/machine-transition m {:state :checking :data {:authed? true}} [:noop])
                         :snapshot
                         :state))))))

;; ---- depth-limit boundary --------------------------------------------------
;;
;; Spec 005 §Bounded depth: the `:always` loop and the `:raise` drain both
;; bound on `(>= depth limit)`, so a limit of N permits exactly N steps and the
;; depth-exceeded error trace's `:depth` equals the limit (not limit+1).

(defn- capture-error-depth!
  "Run a pure `machine-transition`, returning the `:depth` tag of the first
  `error-op` trace it emitted (nil if none)."
  [error-op definition snapshot event]
  (rf.machines.test-support/with-trace-capture seen
    (rf.machines/machine-transition definition snapshot event)
    (->> @seen
         (filter #(= error-op (:operation %)))
         first
         :tags
         :depth)))

(deftest always-depth-boundary-permits-exactly-limit-microsteps
  ;; :a and :b ping-pong via always-true `:always` guards.
  (is (= 4 (capture-error-depth!
             :rf.error/machine-always-depth-exceeded
             {:initial            :start
              :always-depth-limit 4
              :guards             {:p? (fn [_] true)}
              :states             {:start {:on {:go {:target :a}}}
                                   :a     {:always [{:guard :p? :target :b}]}
                                   :b     {:always [{:guard :p? :target :a}]}}}
             {:state :start :data {}} [:go]))))

(deftest raise-depth-boundary-matches-always-boundary
  ;; Six raises in one batch against a limit of 4: the drain handles depths
  ;; 0..3 and aborts at 4, the same boundary as the `:always` loop.
  (is (= 4 (capture-error-depth!
             :rf.error/machine-raise-depth-exceeded
             {:initial           :idle
              :raise-depth-limit 4
              :actions           {:fan-out (fn [_] {:fx (vec (repeat 6 [:raise [:noop]]))})}
              :states            {:idle    {:on {:start {:target :running :action :fan-out}
                                                 :noop  :idle}}
                                  :running {:on {:noop :idle}}}}
             {:state :idle :data {}} [:start]))))

(deftest machine-raise-pre-commit
  (testing ":raise is handled inside the macrostep, never surfaced as fx"
    (let [m {:initial :idle
             :actions {:start (fn [_] {:fx [[:raise [:bump]] [:raise [:bump]]]})
                       :bump  (fn [{data :data}] {:data {:n (inc (:n data))}})}
             :states  {:idle {:on {:start {:target :busy :action :start}}}
                       :busy {:on {:bump {:action :bump}}}}}]
      (is (= {:status :ok :snapshot {:state :busy :data {:n 2}} :fx [] :handled? true}
             (rf.machines/machine-transition m {:state :idle :data {:n 0}} [:start]))))))
