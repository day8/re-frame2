(ns re-frame.join-strict-mint-cljs-test
  "A `:spawn-all` join completion honours the EFFECTIVE cofx mint policy: under
  `:strict` it replays from a recorded fact without consulting the host, an absent
  fact fails `:rf.error/missing-required-cofx` with no fold, and the policy
  crosses the child-to-parent edge into the join resolution."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.late-bind :as rf.late-bind]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(defn- join-state [parent-id]
  (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- missing-required-errors []
  (rf.machines.test-support/events-of :rf.error/missing-required-cofx))

(defn- reg-roll!
  "Register `:strictmint/roll`, a generator-backed recordable cofx whose supplier
  counts its calls in `calls` and returns `value`."
  [calls value]
  (rf/reg-cofx :strictmint/roll
    {:recordable? true
     :doc "Test generator-backed recordable fact: a join completion's minted roll."}
    (fn [] (swap! calls inc) value)))

(defn- mk-completing-child
  "A join child whose transition into its `:final?` state requires `:strictmint/roll`
  and stamps it into the `:output-key` slot, so the roll rides the completion."
  []
  {:initial :running
   :data    {:id nil}
   :actions {:record-id  (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})
             :stamp-roll {:rf.cofx/requires [:strictmint/roll]
                          :fn (fn [{data :data cofx :rf.cofx}]
                                {:data (assoc data :roll (:strictmint/roll cofx))})}}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done :action :stamp-roll}}}
             :done {:final? true :output-key :roll}}})

(defn- mk-plain-child
  "A join child with no coeffect requirement."
  []
  {:initial :running
   :data    {:id nil}
   :actions {:record-id (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done}}}
             :done {:final? true :output-key :id}}})

(defn- reg-parent!
  "A two-child `:all` join parent: `:a` is the generator-backed target, `:b` a
  never-driven sibling holding the join open."
  [parent-kw target-kw plain-kw]
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        {:children        [{:id :a :machine-id target-kw :start [:set-id :a]}
                                           {:id :b :machine-id plain-kw  :start [:set-id :b]}]
                         :join            :all
                         :on-all-complete [:all/done]}
                        :on {:abort :idle}}}}))

(defn- with-dispatch-observer
  "Run `body-fn` with `:router/dispatch!` wrapped by a pass-through observer that
  records every `[event opts]` into `sink`."
  [sink body-fn]
  (let [real (rf.late-bind/get-fn :router/dispatch!)]
    (try
      (rf.late-bind/set-fn! :router/dispatch!
                         (fn [event opts]
                           (swap! sink conj [event opts])
                           (real event opts)))
      (body-fn)
      (finally
        (rf.late-bind/set-fn! :router/dispatch! real)))))

(defn- completion-of
  "The first observed completion carrier map for `child-id` in `sink`."
  [sink parent-id child-id]
  (some (fn [[event _opts]]
          (when (= parent-id (first event))
            (let [inner (second event)]
              (when (and (vector? inner)
                         (= :rf.machine.spawn/done (first inner))
                         (= child-id (:child-id (nth inner 2 nil))))
                (nth inner 2)))))
        @sink))

(deftest recorded-completion-strict-replays-without-host-generation
  (let [calls (atom 0)]
    (reg-roll! calls 6)
    (rf/reg-event :sm3/restore-runtime (fn [_ [_ rt]] {:rf.db/runtime rt}))
    (rf/reg-machine :sm3/ta (mk-completing-child))
    (rf/reg-machine :sm3/pb (mk-plain-child))
    (reg-parent! :sm3/rp :sm3/ta :sm3/pb)
    (rf/dispatch-sync [:sm3/rp [:start]])
    (let [pre-fold (rf.machines.test-support/runtime-db)
          a        (get-in (join-state :sm3/rp) [:children :a])
          sink     (atom [])
          done     #(:done (join-state :sm3/rp))
          replay!  (fn [cofx policy]
                     (rf/dispatch-sync [:sm3/restore-runtime pre-fold])
                     (reset! calls 0)
                     (rf.machines.test-support/reset-captured!)
                     (rf/dispatch-sync [a [:go]] {:rf.cofx cofx :rf.cofx/mint-policy policy}))]
      ;; Record a genuine :live completion, reading the minted roll off its carrier.
      (with-dispatch-observer sink #(rf/dispatch-sync [a [:go]]))
      (let [roll (:result (completion-of sink :sm3/rp :a))]
        (is (= [1 #{:a} 6] [@calls (done) roll]))
        (replay! {:strictmint/roll roll :rf/time-ms 1} :strict)
        (is (= [0 #{:a}] [@calls (done)]) "strict replay folds from the recorded fact")
        (replay! {:rf/time-ms 1} :strict)
        (is (= [0 #{} 1] [@calls (done) (count (missing-required-errors))])
            "without the fact, strict fails missing-required and folds nothing")
        (replay! {:rf/time-ms 1} :live)
        (is (= [1 #{:a}] [@calls (done)]) "the same stripped replay under :live mints and folds")))))

(defn- reg-resolution-parent!
  "A one-child `:all` join parent whose resolution transition runs an action
  requiring `:strictmint/roll`, so the policy the completion carrier runs under
  decides whether the parent reaches `:ready`."
  [parent-kw child-kw]
  (rf/reg-machine parent-kw
    {:initial :idle
     :data    {}
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        {:children        [{:id :a :machine-id child-kw :start [:set-id :a]}]
                         :join            :all
                         :on-all-complete [:all/done]}
                        :on {:all/done {:target :ready :action :res-action}}}
               :ready  {}}
     :actions {:res-action
               {:rf.cofx/requires [:strictmint/roll]
                :fn (fn [{data :data cofx :rf.cofx}]
                      {:data (assoc data :roll (:strictmint/roll cofx))})}}}))

(deftest completion-carrier-inherits-strict-into-the-join-resolution
  (doseq [[parent-kw child-kw opts expected]
          [[:sm5l/rp :sm5l/ca nil                                                      [1 true 0]]
           [:sm5s/rp :sm5s/ca {:rf.cofx {:rf/time-ms 1} :rf.cofx/mint-policy :strict} [0 false 1]]]]
    (let [calls (atom 0)]
      (reg-roll! calls 6)
      (rf/reg-machine child-kw (mk-plain-child))
      (reg-resolution-parent! parent-kw child-kw)
      (rf/dispatch-sync [parent-kw [:start]])
      (rf.machines.test-support/reset-captured!)
      (let [finish [(get-in (join-state parent-kw) [:children :a]) [:go]]]
        (if opts (rf/dispatch-sync finish opts) (rf/dispatch-sync finish)))
      (is (= expected [@calls
                       (= :ready (rf.machines.test-support/machine-state parent-kw))
                       (count (missing-required-errors))])
          (str "[generator calls, parent reached :ready, missing-required] under " (or opts :live))))))
