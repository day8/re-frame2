(ns re-frame.after-timer-trace-ms-test
  "Every `:rf.machine.timer/*` row reports the timer's milliseconds under
  `:delay` and the `:after` key it was declared under under `:delay-key`
  (Spec 005 §Trace events). A delay that is not a literal — an ISO-8601
  string, a `:delays` name, a fn — is resolved only when the timer arms, so
  `/scheduled` is emitted there, and the armed milliseconds travel with the
  elapsed event to the `/fired` and `/stale-after` rows."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.router :as rf.router]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private delay-fn (fn [{:keys [snapshot]}] (-> snapshot :data :ms)))

(defn- waiting-machine
  "`:waiting`'s one `:after` is keyed by `after-key`; `:leave` exits it."
  [after-key extra]
  (merge {:initial :idle
          :data    {:ms 250}
          :states  {:idle    {:on {:go :waiting}}
                    :waiting {:after {after-key :done}
                              :on    {:leave :idle}}
                    :done    {}}}
         extra))

(def ^:private cases
  [[:trace-ms/iso   "an ISO-8601 duration" "PT0.25S"  {}]
   [:trace-ms/named "a :delays name"       :warn      {:delays {:warn delay-fn}}]
   [:trace-ms/fn    "a delay fn"           delay-fn   {}]])

(defn- arm!
  "Dispatch `event` to `machine-id` with the host clock stubbed; the armed
  timers' thunks."
  [machine-id event]
  (let [thunks (atom [])]
    (with-redefs [rf.interop/schedule-after! (fn [thunk _ms] (swap! thunks conj thunk) ::handle)]
      (rf/dispatch-sync [machine-id event]))
    @thunks))

(defn- run-thunk!
  "Run a timer thunk with `dispatch!` as the router's dispatch."
  [thunk dispatch!]
  (let [orig (rf.late-bind/get-fn :router/dispatch!)]
    (try
      (rf.late-bind/set-fn! :router/dispatch! dispatch!)
      (thunk)
      (finally (rf.late-bind/set-fn! :router/dispatch! orig)))))

(defn- row [captured op]
  (-> (filter #(= op (:operation %)) captured) first :tags (select-keys [:delay :delay-key])))

(deftest scheduled-and-fired-rows-carry-the-armed-ms-beside-the-key
  (doseq [[id label after-key extra] cases]
    (rf/reg-machine id (waiting-machine after-key extra))
    (rf.machines.test-support/with-trace-capture captured
      (let [[thunk] (arm! id [:go])]
        (run-thunk! thunk rf.router/dispatch-sync!)
        (is (= {:delay 250 :delay-key after-key} (row @captured :rf.machine.timer/scheduled))
            (str label ": /scheduled"))
        (is (= {:delay 250 :delay-key after-key} (row @captured :rf.machine.timer/fired))
            (str label ": /fired"))
        (is (= :done (rf.machines.test-support/machine-state id)) label)))))

(deftest stale-and-cancelled-rows-carry-the-armed-ms-beside-the-key
  (doseq [[id label after-key extra] cases]
    (rf/reg-machine id (waiting-machine after-key extra))
    (rf.machines.test-support/with-trace-capture captured
      (let [[thunk] (arm! id [:go])
            sent    (atom nil)]
        ;; The timer fires, and its event is held while the state is left
        ;; and the timer cancelled, so it arrives stale.
        (run-thunk! thunk (fn [ev _opts] (reset! sent ev)))
        (rf/dispatch-sync [id [:leave]])
        (rf/dispatch-sync @sent)
        (is (= {:delay 250 :delay-key after-key} (row @captured :rf.machine.timer/stale-after))
            (str label ": /stale-after"))))
    (rf/reg-machine id (waiting-machine after-key extra))
    (rf.machines.test-support/with-trace-capture captured
      (arm! id [:go])
      (rf/dispatch-sync [id [:leave]])
      (is (= {:delay 250 :delay-key after-key} (row @captured :rf.machine.timer/cancelled))
          (str label ": /cancelled")))))

(deftest a-hand-dispatched-elapsed-event-reports-the-ms-the-definition-fixes
  (testing "no timer armed it, so the literal, ISO or named value stands in;
            a fn's value is known only to a timer, so the row omits :delay"
    (doseq [[id after-key extra expected]
            [[:trace-ms/hand-literal 5000      {}                    {:delay 5000 :delay-key 5000}]
             [:trace-ms/hand-iso     "PT2S"    {}                    {:delay 2000 :delay-key "PT2S"}]
             [:trace-ms/hand-named   :warn     {:delays {:warn 300}} {:delay 300 :delay-key :warn}]
             [:trace-ms/hand-fn      delay-fn  {}                    {:delay-key delay-fn}]]]
      (rf/reg-machine id (waiting-machine after-key extra))
      (rf.machines.test-support/with-trace-capture captured
        (arm! id [:go])
        (rf/dispatch-sync [id [:rf.machine.timer/after-elapsed after-key 1 [:waiting]]])
        (is (= expected (row @captured :rf.machine.timer/fired)) (pr-str after-key))))))
