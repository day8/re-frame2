(ns re-frame.after-timer-declaring-node-test
  "Every `:rf.machine.timer/*` row names the node that declared its timer:
  `:decl-path` is that node's path, and inside a parallel region `:region`
  names the region and the path is region-relative (Spec 009 §`:rf.machine.timer/*`).
  The leaf `:state` alone cannot tell two regions' same-named leaves apart,
  and each region owns its own timer for the same `:after` key — and its own
  `:rf.reply/work-id`, whose declaring path is region-qualified. Each entry
  of one node's `:after` map is its own timer too, so the work-id ends with
  the `:after` key it was declared under."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.router :as rf.router]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- region [extra-on]
  {:initial :off
   :states  {:off  {:on {:go :idle}}
             :idle {:after {250 :done} :on extra-on}
             :done {}}})

(def ^:private two-regions
  {:type    :parallel
   :regions {:left  (region {:leave :off})
             :right (region {})}})

(defn- rows [captured op]
  (->> captured
       (filter #(= op (:operation %)))
       (mapv #(select-keys (:tags %) [:state :decl-path :region]))))

(defn- reply-ledger
  "The timer rows' reply statuses, in order, under the `:rf.reply/work-id`
  each row joins."
  [captured]
  (reduce (fn [ledger {:keys [tags]}]
            (if-let [work-id (:rf.reply/work-id tags)]
              (update ledger work-id (fnil conj []) (:rf.reply/status tags))
              ledger))
          {}
          (filter #(= "rf.machine.timer" (namespace (:operation %))) captured)))

(defn- expire!
  "Expire host-clock callbacks the way a real host does: each dispatches its
  elapsed event through the router, here a synchronous one."
  [thunks]
  (let [orig (rf.late-bind/get-fn :router/dispatch!)]
    (try
      (rf.late-bind/set-fn! :router/dispatch! rf.router/dispatch-sync!)
      (run! #(%) thunks)
      (finally (rf.late-bind/set-fn! :router/dispatch! orig)))))

(deftest each-region-timer-is-its-own-reply-work-attempt
  (rf/reg-machine :decl-node/work-ids two-regions)
  (rf.machines.test-support/with-trace-capture captured
    (let [thunks (atom [])]
      (with-redefs [rf.interop/schedule-after! (fn [thunk _ms] (swap! thunks conj thunk) ::handle)]
        (rf/dispatch-sync [:decl-node/work-ids [:go]]))
      (rf/dispatch-sync [:decl-node/work-ids [:leave]])
      ;; Both host clocks expire; the left fire finds its slot already
      ;; claimed by the cancel and dispatches nothing.
      (expire! @thunks))
    (is (= {[:rf.work/timer [:decl-node/work-ids :left :idle 250] 1]  [:cancelled]
            [:rf.work/timer [:decl-node/work-ids :right :idle 250] 1] [:ok]}
           (reply-ledger @captured)))))

(deftest each-after-entry-on-one-node-is-its-own-reply-work-attempt
  ;; `:waiting` arms two timers per visit. The short one fires and its
  ;; transition exits the node, cancelling the long one; the second visit arms
  ;; both afresh under the next epoch, and leaving cancels them.
  (rf/reg-machine :decl-node/entries
                  {:initial :idle
                   :states  {:idle    {:on {:go :waiting}}
                             :waiting {:after {250 :done 1000 :done}
                                       :on    {:leave :idle}}
                             :done    {:on {:go :waiting}}}})
  (rf.machines.test-support/with-trace-capture captured
    (let [armed (atom [])]
      (with-redefs [rf.interop/schedule-after! (fn [thunk ms] (swap! armed conj [ms thunk]) ::handle)]
        (rf/dispatch-sync [:decl-node/entries [:go]])
        (expire! (keep (fn [[ms thunk]] (when (= 250 ms) thunk)) @armed))
        (rf/dispatch-sync [:decl-node/entries [:go]])
        (rf/dispatch-sync [:decl-node/entries [:leave]])))
    (let [[visit-1 visit-2] (distinct (keep #(when (= :rf.machine.timer/scheduled (:operation %))
                                               (get-in % [:tags :epoch]))
                                            @captured))]
      (is (= {[:rf.work/timer [:decl-node/entries :waiting 250] visit-1]  [:ok]
              [:rf.work/timer [:decl-node/entries :waiting 1000] visit-1] [:cancelled]
              [:rf.work/timer [:decl-node/entries :waiting 250] visit-2]  [:cancelled]
              [:rf.work/timer [:decl-node/entries :waiting 1000] visit-2] [:cancelled]}
             (reply-ledger @captured))))))

(deftest equal-millisecond-names-are-two-reply-work-attempts
  ;; `:warning` and `:expiry` both resolve to 250ms and are still two timers:
  ;; each guard-suppressed firing completes its own attempt.
  (rf/reg-machine :decl-node/names
                  {:initial :idle
                   :delays  {:warning 250 :expiry 250}
                   :guards  {:never (constantly false)}
                   :states  {:idle    {:on {:go :waiting}}
                             :waiting {:after {:warning {:guard :never :target :done}
                                               :expiry  {:guard :never :target :done}}}
                             :done    {}}})
  (rf.machines.test-support/with-trace-capture captured
    (let [thunks (atom [])]
      (with-redefs [rf.interop/schedule-after! (fn [thunk _ms] (swap! thunks conj thunk) ::handle)]
        (rf/dispatch-sync [:decl-node/names [:go]]))
      (expire! @thunks))
    (is (= {[:rf.work/timer [:decl-node/names :waiting :warning] 1] [:ok]
            [:rf.work/timer [:decl-node/names :waiting :expiry] 1]  [:ok]}
           (reply-ledger @captured)))))

(deftest region-timer-rows-name-their-region
  (rf/reg-machine :decl-node/regions two-regions)
  (rf.machines.test-support/with-trace-capture captured
    (with-redefs [rf.interop/schedule-after! (fn [_thunk _ms] ::handle)]
      (rf/dispatch-sync [:decl-node/regions [:go]]))
    (let [epoch (into {} (keep (fn [ev]
                                 (when (= :rf.machine.timer/scheduled (:operation ev))
                                   [(get-in ev [:tags :region]) (get-in ev [:tags :epoch])])))
                      @captured)]
      (is (= #{{:state :idle :decl-path [:idle] :region :left}
               {:state :idle :decl-path [:idle] :region :right}}
             (set (rows @captured :rf.machine.timer/scheduled))))
      (rf/dispatch-sync [:decl-node/regions [:leave]])
      (is (= [{:state :idle :decl-path [:idle] :region :left}]
             (rows @captured :rf.machine.timer/cancelled)))
      (rf/dispatch-sync [:decl-node/regions [:rf.machine.timer/after-elapsed
                                             250 (:left epoch) [:left :idle]]])
      (is (= [{:state :idle :decl-path [:idle] :region :left}]
             (rows @captured :rf.machine.timer/stale-after)))
      (rf/dispatch-sync [:decl-node/regions [:rf.machine.timer/after-elapsed
                                             250 (:right epoch) [:right :idle]]])
      (is (= [{:state :idle :decl-path [:idle] :region :right}]
             (rows @captured :rf.machine.timer/fired))))))

(deftest a-server-region-skip-names-its-region
  (let [machine (-> (rf.machines.parallel/install-region-cache two-regions)
                    (assoc :rf/platform :server :rf/frame :decl-node/server
                           :rf/parent-id :decl-node/server-actor))
        snap    (rf.machines.parallel/build-initial-snapshot machine {:bootstrap-pending? false})]
    (rf.machines.test-support/with-trace-capture captured
      (rf.machines.parallel/machine-transition machine snap [:go])
      (is (= #{{:state :idle :decl-path [:idle] :region :left}
               {:state :idle :decl-path [:idle] :region :right}}
             (set (rows @captured :rf.machine.timer/skipped-on-server)))))))

(deftest a-compound-timer-row-carries-its-full-path-and-no-region
  (rf/reg-machine :decl-node/compound
                  {:initial :idle
                   :states  {:idle  {:on {:go :outer}}
                             :outer {:initial :inner
                                     :states  {:inner {:after {250 :done}}
                                               :done  {}}}}})
  (rf.machines.test-support/with-trace-capture captured
    (with-redefs [rf.interop/schedule-after! (fn [_thunk _ms] ::handle)]
      (rf/dispatch-sync [:decl-node/compound [:go]]))
    (is (= [{:state :inner :decl-path [:outer :inner]}]
           (rows @captured :rf.machine.timer/scheduled)))))
