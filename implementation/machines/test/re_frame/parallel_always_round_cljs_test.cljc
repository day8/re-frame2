(ns re-frame.parallel-always-round-cljs-test
  "Cross-region `:always` is a parent-owned select-then-apply loop.

  These are pure-engine CLJ/CLJS fixtures: the same source pins declaration-
  order invariance, event-before-eventless selection, birth convergence,
  round-honest observability, eventless-before-raised FIFO, and atomic failure
  semantics in both runtimes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.result :as result]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- initial-snapshot [m]
  (parallel/build-initial-snapshot m {:bootstrap-pending? false}))

(defn- ordered-regions [order bodies]
  (into {} (map (fn [rn] [rn (get bodies rn)])) order))

(defn- event-order-machine [order guard-reads]
  (let [bodies
        {:a {:initial :idle
             :states {:idle {:on {:go {:target :done :action :mark-a}}}
                      :done {}}}
         :b {:initial :idle
             :states {:idle    {:on {:go {:target :waiting :action :mark-b}}}
                      :waiting {:always {:target :ready :guard :both-marked?}}
                      :ready   {}}}}]
    {:type :parallel
     :data {:a? false :b? false}
     :region-order order
     :actions {:mark-a (fn [{:keys [data]}] {:data (assoc data :a? true)})
               :mark-b (fn [{:keys [data]}] {:data (assoc data :b? true)})}
     :guards {:both-marked?
              (fn [{:keys [data]}]
                (swap! guard-reads conj (select-keys data [:a? :b?]))
                (and (:a? data) (:b? data)))}
     :regions (ordered-regions order bodies)}))

(deftest event-round-is-order-invariant-and-sees-complete-event-set
  (testing "A/B and B/A settle identically; no :always guard sees an event-
            action prefix"
    (let [reads-ab (atom [])
          reads-ba (atom [])
          m-ab (event-order-machine [:a :b] reads-ab)
          m-ba (event-order-machine [:b :a] reads-ba)
          r-ab (parallel/machine-transition m-ab (initial-snapshot m-ab) [:go])
          r-ba (parallel/machine-transition m-ba (initial-snapshot m-ba) [:go])]
      (is (and (result/ok? r-ab) (result/ok? r-ba)))
      (is (= {:a :done :b :ready} (:state (result/snap r-ab)))
          "A/B reaches the eventless target in the triggering macrostep")
      (is (= (select-keys (result/snap r-ab) [:state :data])
             (select-keys (result/snap r-ba) [:state :data]))
          "reversing declaration order cannot change selected :always work")
      (is (every? #(= {:a? true :b? true} %) (concat @reads-ab @reads-ba))
          "the first eventless SELECT happens only after both event actions"))))

(defn- birth-order-machine [order]
  (let [bodies
        {:a {:initial :boot
             :states {:boot {:always {:target :done :action :mark-a}}
                      :done {}}}
         :b {:initial :waiting
             :states {:waiting {:always {:target :ready :guard :a-marked?}}
                      :ready {}}}}]
    {:type :parallel
     :data {:a? false}
     :region-order order
     :actions {:mark-a (fn [{:keys [data]}] {:data (assoc data :a? true)})}
     :guards {:a-marked? (fn [{:keys [data]}] (:a? data))}
     :regions (ordered-regions order bodies)}))

(deftest birth-rounds-are-order-invariant
  (testing "all regions enter before the identical parent loop converges birth"
    (let [m-ab (birth-order-machine [:a :b])
          m-ba (birth-order-machine [:b :a])
          r-ab (parallel/apply-initial-entry-cascade m-ab (initial-snapshot m-ab))
          r-ba (parallel/apply-initial-entry-cascade m-ba (initial-snapshot m-ba))]
      (is (and (result/ok? r-ab) (result/ok? r-ba)))
      (is (= {:a :done :b :ready} (:state (result/snap r-ab))))
      (is (= (select-keys (result/snap r-ab) [:state :data])
             (select-keys (result/snap r-ba) [:state :data])))
      (is (= 2 (result/microsteps r-ab))
          "A writes in round 0; B observes it from a fresh snapshot in round 1")
      (is (= 2 (result/microsteps r-ba))
          "microsteps count parent rounds, independent of region order"))))

(defn- co-selected-machine []
  {:type :parallel
   :data {:log []}
   :region-order [:a :b]
   :actions {:a-always (fn [{:keys [data]}]
                         {:data (update data :log conj :a)
                          :fx [[:note :a]]})
             :b-always (fn [{:keys [data]}]
                         {:data (update data :log conj :b)
                          :fx [[:note :b]]})}
   :regions
   {:a {:initial :idle
        :states {:idle {:on {:go :staged}}
                 :staged {:always {:target :done :action :a-always}}
                 :done {}}}
    :b {:initial :idle
        :states {:idle {:on {:go :staged}}
                 :staged {:always {:target :done :action :b-always}}
                 :done {}}}}})

(deftest co-selected-regions-are-one-observable-round
  (testing "two regional transitions share index 0 and count as one round"
    (let [emits (atom [])
          m (co-selected-machine)
          r (with-redefs [trace/emit! (fn [& xs] (swap! emits conj xs))]
              (parallel/machine-transition m (initial-snapshot m) [:go]))
          micros (filterv #(= :microstep (:kind %)) (result/cascade r))
          micro-traces (filterv #(= :rf.machine.microstep/transition
                                    (second %)) @emits)]
      (is (result/ok? r))
      (is (= 1 (result/microsteps r)))
      (is (= [:a :b] (mapv :region micros)))
      (is (= [0 0] (mapv :microstep-index micros)))
      (is (= [0 0] (mapv #(-> % last :microstep-index) micro-traces)))
      (is (= [:a :b] (mapv #(-> % last :region) micro-traces)))
      (is (= [:a :b] (get-in (result/snap r) [:data :log])))
      (is (= [[:note :a] [:note :b]] (result/fx r))
          "action and fx application stay in canonical region order"))))

(deftest eventless-round-precedes-raised-fifo
  (testing "a seed raise waits until the post-event :always fixed point"
    (let [m {:type :parallel
             :data {:log []}
             :region-order [:a :b]
             :actions
             {:event (fn [{:keys [data]}]
                       {:data (update data :log conj :event)
                        :fx [[:raise [:tick]]]})
              :always (fn [{:keys [data]}]
                        {:data (update data :log conj :always)})
              :raised (fn [{:keys [data]}]
                        {:data (update data :log conj :raised)})}
             :regions
             {:a {:initial :idle
                  :states {:idle {:on {:go {:target :mid :action :event}}}
                           :mid {:always {:target :done :action :always}}
                           :done {}}}
              :b {:initial :waiting
                  :states {:waiting {:on {:tick {:target :seen :action :raised}}}
                           :seen {}}}}}
          r (parallel/machine-transition m (initial-snapshot m) [:go])]
      (is (result/ok? r))
      (is (= [:event :always :raised] (get-in (result/snap r) [:data :log])))
      (is (= {:a :done :b :seen} (:state (result/snap r)))))))

(deftest parent-always-action-failure-is-atomic
  (let [m {:type :parallel
           :data {}
           :region-order [:a :b]
           :actions {:boom (fn [_] (throw (ex-info "boom" {})))}
           :regions
           {:a {:initial :idle
                :states {:idle {:on {:go :armed}}
                         :armed {:always {:target :done :action :boom}}
                         :done {}}}
            :b {:initial :idle
                :states {:idle {:on {:go :moved}} :moved {}}}}}
        r (parallel/machine-transition m (initial-snapshot m) [:go])]
    (is (result/fail? r))
    (is (nil? (result/snap r)) "failed macrostep publishes no partial snapshot")
    (is (nil? (result/fx r)) "failed macrostep releases no partial effects")))

(deftest parent-always-depth-counts-rounds-and-rolls-back
  (let [m {:type :parallel
           :data {}
           :always-depth-limit 2
           :region-order [:a]
           :regions
           {:a {:initial :idle
                :states {:idle {:on {:go :x}}
                         :x {:always :y}
                         :y {:always :x}}}}}
        r (parallel/machine-transition m (initial-snapshot m) [:go])]
    (is (result/fail? r))
    (is (= :rf.error/machine-always-depth-exceeded
           (:error-id (result/info r))))
    (is (= 2 (:depth (result/info r))))
    (is (nil? (result/snap r)))
    (is (nil? (result/fx r)))))

(deftest all-rounds-publish-once
  (testing "event set plus two eventless rounds is one frame-state publication"
    (let [m (birth-order-machine [:b :a])]
      (rf/reg-machine :parallel-round/publication m)
      (rf/make-frame {:id :parallel-round/frame
                      :doc "parent always round publication fixture"})
      ;; Registration is outside the observation window. The first dispatch
      ;; performs lazy birth (including both parent rounds) and the declined
      ;; external event in one handler result / frame-state transform.
      (let [container (frame/frame-state-container :parallel-round/frame)
            writes    (atom 0)
            watch-key ::publication]
        (add-watch container watch-key
                   (fn [_ _ before after]
                     (when (not= before after) (swap! writes inc))))
        (try
          (rf/dispatch-sync [:parallel-round/publication [:poke]]
                            {:frame :parallel-round/frame})
          (finally
            (remove-watch container watch-key)))
        (is (= 1 @writes)
            "birth entry + every eventless round commits through one write")
        (is (= {:a :done :b :ready}
               (:state (mtest/snapshot :parallel-round/frame
                                      :parallel-round/publication))))))))
