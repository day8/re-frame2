(ns re-frame.machine-cascade-instrumentation-cljs-test
  "The `:rf.machine/transition` trace's structured `:cascade` (Spec 005 §The
  structured transition cascade): the exit / action / entry / `:microstep` /
  `:raised-transition` steps, in execution order, that explain how a macrostep
  reached its after-state. The HVAC fixture mirrors the machine-epochs testbed's
  `:hvac/controller`, whose `:data :trail` records the order its actions ran."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  ;; `with-trace-capture` is a CLJ macro in a .cljc ns, so CLJS must also
  ;; require the ns as a macro ns under the same alias.
  #?(:cljs (:require-macros [re-frame.machines.test-support :as rf.machines.test-support])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- transition-of
  "The last `:rf.machine/transition` trace `drive!` emits."
  [drive!]
  (rf.machines.test-support/with-trace-capture seen
    (drive!)
    (last (filterv #(= :rf.machine/transition (:operation %)) @seen))))

(defn- cascade-of [drive!] (-> (transition-of drive!) :tags :cascade))

(defn- kinds+actions [steps] (mapv (juxt :kind :action) steps))

(defn- raised-steps [cascade]
  (filterv #(= :raised-transition (:kind %)) cascade))

;; Appends `label` to `[:data :trail]`, so the trail records the order actions ran.
(defn- trail-action [label]
  (fn [{data :data}]
    {:data (update data :trail (fnil conj []) label)}))

;; ---- HVAC: parallel, a deep-compound :climate region and a flat :fan region -

(defn- reg-hvac! []
  (rf/reg-machine :hvac/controller
    {:type :parallel
     :data {:trail []}
     :regions
     {:climate
      {:initial :idle
       :states
       {:idle    {:on {:hvac/power-cycle {:target :running :action :enter-running}}}
        :running {:initial :conditioning
                  :entry   :enter-running-level
                  :exit    :exit-running-level
                  :on      {:hvac/power-cycle {:target :idle :action :back-to-idle}}
                  :states
                  {:conditioning
                   {:initial :heating
                    :entry   :enter-conditioning
                    :exit    :exit-conditioning
                    :states
                    {:heating {:entry :enter-heating
                               :exit  :exit-heating
                               :on    {:hvac/mode-toggle {:target :cooling :action :swap-mode}}}
                     :cooling {:entry :enter-cooling
                               :exit  :exit-cooling
                               :on    {:hvac/mode-toggle {:target :heating :action :swap-mode}}}}}}}}}
      :fan
      {:initial :off
       :states
       {:off {:on {:hvac/power-cycle {:target :on :action :fan-on}}}
        :on  {:entry :enter-fan-on
              :exit  :exit-fan-on
              :on    {:hvac/power-cycle {:target :off :action :fan-off}
                      :hvac/nudge {:target :same-state :reenter? true :action :nudge-fan}
                      :hvac/tweak {:action :tweak-fan}}}}}}
     :actions
     {:enter-running       (trail-action :action:power-on)
      :enter-running-level (trail-action :entry:running)
      :exit-running-level  (trail-action :exit:running)
      :back-to-idle        (trail-action :action:power-off)
      :enter-conditioning  (trail-action :entry:conditioning)
      :exit-conditioning   (trail-action :exit:conditioning)
      :enter-heating       (trail-action :entry:heating)
      :exit-heating        (trail-action :exit:heating)
      :enter-cooling       (trail-action :entry:cooling)
      :exit-cooling        (trail-action :exit:cooling)
      :swap-mode           (trail-action :action:swap-mode)
      :fan-on              (trail-action :action:fan-on)
      :fan-off             (trail-action :action:fan-off)
      :enter-fan-on        (trail-action :entry:fan-on)
      :exit-fan-on         (trail-action :exit:fan-on)
      :nudge-fan           (trail-action :action:nudge)
      :tweak-fan           (trail-action :action:tweak)}})
  (rf/dispatch-sync [:hvac/controller [:rf.machine/start]]))

(defn- hvac! [ev] (rf/dispatch-sync [:hvac/controller [ev]]))

(deftest power-cycle-emits-ordered-cascade-matching-the-trail-oracle
  (reg-hvac!)
  (let [cascade (cascade-of #(hvac! :hvac/power-cycle))]
    ;; A complete configuration walk: the action-free exits of :idle / :off are
    ;; steps too. States are region-relative; the action sits at its decl-path.
    (is (= [[:climate :exit   [:idle]                           nil]
            [:climate :action [:idle]                           :enter-running]
            [:climate :entry  [:running]                        :enter-running-level]
            [:climate :entry  [:running :conditioning]          :enter-conditioning]
            [:climate :entry  [:running :conditioning :heating] :enter-heating]
            [:fan     :exit   [:off]                            nil]
            [:fan     :action [:off]                            :fan-on]
            [:fan     :entry  [:on]                             :enter-fan-on]]
           (mapv (juxt :region :kind :state :action) cascade)))
    ;; Each acting step's :data-delta holds the trail as of that step, so its
    ;; last label is the one that step's action wrote.
    (is (= (get-in @(rf/subscribe [:rf/machine :hvac/controller]) [:data :trail])
           (mapv #(last (get-in % [:data-delta :trail])) (filter :action cascade)))
        "the cascade is the order the actions actually ran")))

(deftest powered-hvac-cascades-follow-lca-and-self-transition-geometry
  (reg-hvac!)
  (hvac! :hvac/power-cycle)
  (let [region-steps (fn [region ev]
                       (kinds+actions (filterv #(= region (:region %)) (cascade-of #(hvac! ev)))))]
    (is (= [[:exit :exit-heating] [:action :swap-mode] [:entry :enter-cooling]]
           (region-steps :climate :hvac/mode-toggle))
        "across the :conditioning LCA: exit, action, entry")
    (is (= [[:exit :exit-fan-on] [:action :nudge-fan] [:entry :enter-fan-on]]
           (region-steps :fan :hvac/nudge))
        "an external self-transition exits and re-enters")
    (is (= [[:action :tweak-fan]] (region-steps :fan :hvac/tweak))
        "a targetless transition is action-only")))

(deftest flat-transition-emits-minimal-cascade
  (rf/reg-machine :casc/flat
    {:initial :a
     :actions {:go-act (fn [{d :data}] {:data (assoc d :went true)})}
     :states  {:a {:on {:go {:target :b :action :go-act}}}
               :b {}}})
  (rf/dispatch-sync [:casc/flat [:rf.machine/start]])
  (is (= [{:kind :exit   :state [:a] :region nil :action nil     :data-delta {}}
          {:kind :action :state [:a] :region nil :action :go-act :data-delta {:went true}}
          {:kind :entry  :state [:b] :region nil :action nil     :data-delta {}}]
         (cascade-of #(rf/dispatch-sync [:casc/flat [:go]])))))

(deftest always-microstep-appears-in-cascade
  (rf/reg-machine :casc/quiz
    {:initial :asking
     :data    {:correct 9}
     :guards  {:enough? (fn [{d :data}] (>= (:correct d) 10))}
     :actions {:count (fn [{d :data}] {:data {:correct (inc (:correct d))}})
               :win   (fn [{d :data}] {:data (assoc d :won true)})}
     :states  {:asking {:always [{:guard :enough? :target :winner :action :win}]
                        :on     {:answer {:action :count}}}
               :winner {}}})
  (rf/dispatch-sync [:casc/quiz [:rf.machine/start]])
  (let [cascade (cascade-of #(rf/dispatch-sync [:casc/quiz [:answer]]))
        micro   (peek cascade)]
    (is (= [[:action :count] [:microstep nil]] (kinds+actions cascade)))
    (is (= [0 :asking :winner [[:exit nil] [:action :win] [:entry nil]]]
           ((juxt :microstep-index :from :to (comp kinds+actions :steps)) micro))
        "the eventless transition carries its own nested steps")))

;; A same-macrostep raised event selects a real transition with its own
;; geometry; the cascade records it as one `:raised-transition` wrapper per
;; handled dequeue, in FIFO order, rather than dropping or flattening its rows.

(deftest raised-event-transition-is-recorded-as-its-own-cascade-boundary
  (rf/reg-machine :casc/settle
    {:initial :idle
     :actions {:start        (fn [_] {:fx [[:raise [:settle]]]})
               :settled      (fn [_] nil)
               :exit-working (fn [_] nil)
               :enter-done   (fn [_] nil)}
     :states  {:idle    {:on {:go {:target :working :action :start}}}
               :working {:exit :exit-working
                         :on   {:settle {:target :done :action :settled}}}
               :done    {:entry :enter-done}}})
  (rf/dispatch-sync [:casc/settle [:rf.machine/start]])
  (let [tr      (transition-of #(rf/dispatch-sync [:casc/settle [:go]]))
        cascade (-> tr :tags :cascade)]
    (is (= [:idle :done] (map #(get-in tr [:tags % :state]) [:before :after]))
        "the headline trace spans the whole macrostep")
    (is (= [[:exit nil] [:action :start] [:entry nil] [:raised-transition nil]]
           (kinds+actions cascade))
        "the external :go rows stay top-level; the raise follows as one wrapper")
    (is (= [[:settle] :working :done nil [[:exit :exit-working] [:action :settled] [:entry :enter-done]]]
           ((juxt :event :from :to :region (comp kinds+actions :steps)) (peek cascade))))))

(deftest raised-wrappers-follow-actual-fifo-dequeue-order
  (rf/reg-machine :casc/fifo
    {:initial :a
     :actions {:fan-out (fn [_] {:fx [[:raise [:first]] [:raise [:second]]]})
               :noop    (fn [_] {})}
     :states  {:a {:on {:go {:target :b :action :fan-out}}}
               :b {:on {:first {:target :c :action :noop}}}
               :c {:on {:second {:target :d :action :noop}}}
               :d {}}})
  (rf/dispatch-sync [:casc/fifo [:rf.machine/start]])
  (is (= [[[:first] :b :c] [[:second] :c :d]]
         (mapv (juxt :event :from :to)
               (raised-steps (cascade-of #(rf/dispatch-sync [:casc/fifo [:go]])))))
      "each wrapper's from/to is its own hop, in dequeue order"))

(deftest always-enabled-by-a-raised-transition-rides-inside-its-wrapper
  (rf/reg-machine :casc/raise-always
    {:initial :a
     :data    {:n 0}
     :actions {:kick  (fn [_] {:fx [[:raise [:tick]]]})
               :count (fn [{d :data}] {:data {:n (inc (:n d))}})
               :win   (fn [{d :data}] {:data (assoc d :won true)})}
     :states  {:a {:on {:go {:target :b :action :kick}}}
               :b {:on {:tick {:target :c :action :count}}}
               :c {:always [{:target :d :action :win}]}
               :d {}}})
  (rf/dispatch-sync [:casc/raise-always [:rf.machine/start]])
  (let [cascade (cascade-of #(rf/dispatch-sync [:casc/raise-always [:go]]))
        steps   (:steps (peek cascade))]
    (is (= [[:exit nil] [:action :kick] [:entry nil] [:raised-transition nil]]
           (kinds+actions cascade))
        "no top-level :microstep: the :always was enabled by the raise")
    (is (= [[:exit nil] [:action :count] [:entry nil] [:microstep nil]]
           (kinds+actions steps)))
    (is (= [:c :d [[:exit nil] [:action :win] [:entry nil]]]
           ((juxt :from :to (comp kinds+actions :steps)) (peek steps))))))

(deftest ignored-and-guard-blocked-raises-fabricate-no-wrapper
  (rf/reg-machine :casc/ignored
    {:initial :a
     :guards  {:open? (fn [_] false)}
     :actions {:kick (fn [_] {:fx [[:raise [:nobody-handles-this]] [:raise [:blocked]]]})
               :noop (fn [_] {})}
     :states  {:a {:on {:go {:target :b :action :kick}}}
               :b {:on {:blocked {:guard :open? :target :c :action :noop}}}
               :c {}}})
  (rf/dispatch-sync [:casc/ignored [:rf.machine/start]])
  (is (= [] (raised-steps (cascade-of #(rf/dispatch-sync [:casc/ignored [:go]]))))
      "an unhandled or guard-blocked internal event is not a transition"))

(deftest targetless-raised-transition-records-its-action-boundary
  (rf/reg-machine :casc/targetless
    {:initial :a
     :data    {:hits 0}
     :actions {:kick (fn [_] {:fx [[:raise [:ping]]]})
               :bump (fn [{d :data}] {:data {:hits (inc (:hits d))}})}
     :states  {:a {:on {:go {:target :b :action :kick}}}
               :b {:on {:ping {:action :bump}}}}})
  (rf/dispatch-sync [:casc/targetless [:rf.machine/start]])
  (is (= [[[:ping] :b :b [[:action :bump]]]]
         (mapv (juxt :event :from :to (comp kinds+actions :steps))
               (raised-steps (cascade-of #(rf/dispatch-sync [:casc/targetless [:go]])))))))

(deftest synthetic-done-state-signal-uses-the-same-raised-boundary
  (rf/reg-machine :casc/done
    {:initial :work
     :actions {:finish (fn [{d :data}] {:data (assoc d :finished true)})}
     :states  {:work    {:initial :step
                         :on      {:rf.machine/done {:target :wrapped :action :finish}}
                         :states  {:step {:on {:go {:target :end}}}
                                   :end  {:final? true}}}
               :wrapped {}}})
  (rf/dispatch-sync [:casc/done [:rf.machine/start]])
  (is (= [[:rf.machine/done [:wrapped] [[:exit nil] [:exit nil] [:action :finish] [:entry nil]]]]
         (mapv (juxt (comp first :event) :to (comp kinds+actions :steps))
               (raised-steps (cascade-of #(rf/dispatch-sync [:casc/done [:go]])))))))

;; ---- root-parallel: the parent queue owns raises and eventless rounds -----

(deftest parallel-raised-rebroadcast-is-grouped-under-its-internal-event
  ;; Flattened into the top level, :right's rows would read to Xray as
  ;; evidence that :right handled :go.
  (rf/reg-machine :casc/par
    {:type :parallel
     :regions
     {:left  {:initial :l0
              :states  {:l0 {:on {:go {:target :l1 :action :left-go}}}
                        :l1 {}}}
      :right {:initial :r0
              :states  {:r0 {:on {:settle {:target :r1 :action :right-settle}}}
                        :r1 {}}}}
     :actions {:left-go      (fn [_] {:fx [[:raise [:settle]]]})
               :right-settle (fn [_] nil)}})
  (rf/dispatch-sync [:casc/par [:rf.machine/start]])
  (let [cascade (cascade-of #(rf/dispatch-sync [:casc/par [:go]]))]
    (is (= [[:left :exit nil] [:left :action :left-go] [:left :entry nil]
            [nil :raised-transition nil]]
           (mapv (juxt :region :kind :action) cascade)))
    (is (= [[:settle] {:left :l1 :right :r1}
            [[:right :exit nil] [:right :action :right-settle] [:right :entry nil]]]
           ((juxt :event :to #(mapv (juxt :region :kind :action) (:steps %))) (peek cascade))))))

(deftest parallel-raise-then-enabled-always-round-appends-in-execution-order
  ;; :go takes :main :r0 -> :r1 and raises [:settle]; :settle takes :r1 -> :r2;
  ;; :r2's :always then advances to :r3. The parent loop owns the eventless
  ;; round, so the round the raise enabled is the next TOP-LEVEL :microstep
  ;; after the wrapper rather than nested in it: a round is selected across
  ;; all regions at once, so it cannot belong to the internal event.
  (rf/reg-machine :casc/par-chain
    {:type :parallel
     :regions
     {:main {:initial :r0
             :states  {:r0 {:on {:go {:target :r1 :action :kick}}}
                       :r1 {:on {:settle {:target :r2 :action :settled}}}
                       :r2 {:always [{:target :r3 :action :advance}]}
                       :r3 {}}}
      :aux  {:initial :a0
             :states  {:a0 {}}}}
     :actions {:kick    (fn [_] {:fx [[:raise [:settle]]]})
               :settled (fn [_] nil)
               :advance (fn [_] nil)}})
  (rf/dispatch-sync [:casc/par-chain [:rf.machine/start]])
  (let [tr      (transition-of #(rf/dispatch-sync [:casc/par-chain [:go]]))
        cascade (-> tr :tags :cascade)
        w       (first (raised-steps cascade))
        round   (first (filterv #(= :microstep (:kind %)) cascade))]
    (is (= [{:main :r0 :aux :a0} {:main :r3 :aux :a0}]
           (map #(get-in tr [:tags % :state]) [:before :after]))
        "the event, its raise and the round the raise enabled settle in one macrostep")
    (is (= [:raised-transition :microstep]
           (filterv #{:raised-transition :microstep} (mapv :kind cascade)))
        "the raise's boundary, then the round it enabled: execution order")
    (is (= [[:settle] {:main :r1 :aux :a0} {:main :r2 :aux :a0} []]
           ((juxt :event :from :to #(filterv (comp #{:microstep} :kind) (:steps %))) w))
        "the wrapper spans the raised hop only; the round is not nested in it")
    (is (= [:main :r2 :r3] ((juxt :region :from :to) round))
        "the round starts where the raise left off")))

(deftest cascade-carries-only-deltas-not-whole-data
  (rf/reg-machine :casc/delta
    {:initial :a
     :data    {:big (vec (range 1000)) :n 0}
     :actions {:bump (fn [{d :data}] {:data {:n (inc (:n d))}})}
     :states  {:a {:on {:go {:target :b :action :bump}}}
               :b {}}})
  (rf/dispatch-sync [:casc/delta [:rf.machine/start]])
  (is (= {:n 1}
         (->> (cascade-of #(rf/dispatch-sync [:casc/delta [:go]]))
              (filter #(= :action (:kind %)))
              first
              :data-delta))
      "the unchanged large :big slot stays out of the step's delta"))
