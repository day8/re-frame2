(ns day8.re-frame2-xray.panels.machine-after-rings-runtime-test
  "The countdown-ring fold over the timer rows the machines runtime really
  emits. Every `:rf.machine.timer/*` row reports the armed milliseconds under
  `:delay`, so a timer whose delay is not a literal — an ISO-8601 string, a
  `:delays` name, a fn — still draws a ring, and its `/cancelled` row closes
  the record its `/scheduled` row opened."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [day8.re-frame2-xray.panels.machine-after-rings-helpers :as rings]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- waiting-machine [after-key extra]
  (merge {:initial :idle
          :data    {:ms 250}
          :states  {:idle    {:on {:go :waiting}}
                    :waiting {:after {after-key :done}
                              :on    {:leave :idle}}
                    :done    {}}}
         extra))

(defn- trace-buffer
  "Enter `:waiting` (the host clock stubbed) and leave it again, returning
  the trace rows captured up to the arm and up to the leave."
  [machine-id]
  (let [buf (atom [])]
    (rf/register-listener! :trace ::rings (fn [ev] (swap! buf conj ev)))
    (try
      (with-redefs [rf.interop/schedule-after! (fn [_thunk _ms] ::handle)]
        (rf/dispatch-sync [machine-id [:go]]))
      (let [armed @buf]
        (rf/dispatch-sync [machine-id [:leave]])
        [armed @buf])
      (finally (rf/unregister-listener! :trace ::rings)))))

(deftest a-non-literal-delay-draws-a-ring-that-its-cancel-closes
  (doseq [[id label after-key extra]
          [[:rings-rt/iso   "an ISO-8601 duration" "PT0.25S" {}]
           [:rings-rt/named "a :delays name"       :warn     {:delays {:warn 250}}]
           [:rings-rt/fn    "a delay fn"           (fn [{:keys [snapshot]}] (-> snapshot :data :ms)) {}]]]
    (rf/reg-machine id (waiting-machine after-key extra))
    (let [[armed all] (trace-buffer id)
          ring        (first (rings/timers-for-machine armed id))]
      (is (= [250 1.0] [(:duration-ms ring) (rings/ring-fraction ring (:armed-at ring))])
          (str label ": the armed timer draws a full countdown ring"))
      (is (= [:cancelled] (mapv :status (rings/project-timers all id)))
          (str label ": leaving the state closes that ring")))))
